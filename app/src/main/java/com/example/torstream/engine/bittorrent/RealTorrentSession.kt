package com.example.torstream.engine.bittorrent

import android.net.Uri
import android.util.Log
import com.example.torstream.data.model.StreamStatus
import com.example.torstream.data.model.StreamTelemetry
import com.example.torstream.data.model.TorrentFileInfo
import com.example.torstream.engine.bencode.BencodeParser
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.InputStream
import java.io.PipedInputStream
import java.io.PipedOutputStream
import java.net.InetSocketAddress
import java.nio.charset.StandardCharsets
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicLong
import kotlin.random.Random

class RealTorrentSession(
    val infoHashHex: String,
    val trackers: List<String>,
    val webSeeds: List<String>,
    private val scope: CoroutineScope,
    private val okHttpClient: OkHttpClient
) {
    private val TAG = "RealTorrentSession"

    val infoHashBytes = hexToBytes(infoHashHex)
    val myPeerId = generatePeerId()

    private val _telemetry = MutableStateFlow(StreamTelemetry())
    val telemetry: StateFlow<StreamTelemetry> = _telemetry.asStateFlow()

    private val activePeers = CopyOnWriteArrayList<PeerWireClient>()
    private val discoveredPeers = CopyOnWriteArrayList<InetSocketAddress>()

    // Real resolved metadata
    var resolvedFiles = mutableListOf<TorrentFileInfo>()
    var resolvedTotalSize: Long = 0L
    var pieceLength: Long = 0L
    var isMetadataResolved: Boolean = false

    private val bytesInWindow = AtomicLong(0L)
    private var lastSpeedCheck = System.currentTimeMillis()
    private var isSessionActive = true

    // Piece cache: pieceIndex -> piece ByteArray
    private val blockCache = ConcurrentHashMap<Long, ByteArray>()

    init {
        startTelemetryTracker()
    }

    fun start() {
        scope.launch(Dispatchers.IO) {
            _telemetry.value = _telemetry.value.copy(
                status = StreamStatus.RESOLVING_METADATA,
                statusMessage = "Connecting to trackers to find swarm peers..."
            )

            // 1. Announce to trackers to get REAL peer list and REAL seeds count
            announceToTrackers()

            // 2. Connect to real peers to download metadata (BEP 9)
            connectToPeersForMetadata()
        }
    }

    private suspend fun announceToTrackers() {
        val udpTrackers = trackers.filter { it.startsWith("udp://", ignoreCase = true) }
        val defaultList = if (udpTrackers.isEmpty()) {
            listOf(
                "udp://tracker.opentrackr.org:1337",
                "udp://open.stealth.si:80",
                "udp://tracker.openbittorrent.com:6969"
            )
        } else udpTrackers

        var totalSeedsFound = 0
        var totalLeechersFound = 0

        for (trackerUrl in defaultList) {
            try {
                val uri = Uri.parse(trackerUrl)
                val host = uri.host ?: continue
                val port = if (uri.port > 0) uri.port else 1337

                val result = UdpTrackerClient.announce(host, port, infoHashBytes, myPeerId)
                if (result != null) {
                    totalSeedsFound = maxOf(totalSeedsFound, result.seeders)
                    totalLeechersFound = maxOf(totalLeechersFound, result.leechers)
                    discoveredPeers.addAll(result.peers)
                    Log.d(TAG, "Tracker $host returned ${result.peers.size} real peers!")
                }
            } catch (e: Exception) {
                Log.d(TAG, "Tracker announce error: ${e.message}")
            }
        }

        _telemetry.value = _telemetry.value.copy(
            totalSeeds = totalSeedsFound,
            statusMessage = if (discoveredPeers.isNotEmpty()) {
                "Found ${discoveredPeers.size} peers in swarm (Seeds: $totalSeedsFound)"
            } else {
                "Waiting for peers from swarm..."
            }
        )
    }

    private suspend fun connectToPeersForMetadata() {
        val peersToTry = discoveredPeers.take(15)
        for (peerAddr in peersToTry) {
            if (!isSessionActive || isMetadataResolved) break
            val client = PeerWireClient(
                peerAddress = peerAddr,
                infoHash = infoHashBytes,
                peerId = myPeerId,
                onMetadataReceived = { rawInfoDict ->
                    parseRawInfoDictionary(rawInfoDict)
                },
                onBlockReceived = { pIdx, begin, data ->
                    val blockKey = (pIdx.toLong() shl 32) or (begin.toLong() and 0xFFFFFFFFL)
                    blockCache[blockKey] = data
                    bytesInWindow.addAndGet(data.size.toLong())
                }
            )
            activePeers.add(client)
            scope.launch(Dispatchers.IO) {
                client.connectAndRun()
                activePeers.remove(client)
            }
        }
    }

    fun parseRawInfoDictionary(rawInfoDict: ByteArray) {
        if (isMetadataResolved) return
        try {
            val dict = BencodeParser.decode(rawInfoDict) as? Map<*, *> ?: return
            val pLen = (dict["piece length"] as? Long) ?: 2097152L
            pieceLength = pLen

            val filesList = mutableListOf<TorrentFileInfo>()
            val filesRaw = dict["files"] as? List<*>
            if (filesRaw != null) {
                // Multi-file torrent
                var total = 0L
                filesRaw.forEachIndexed { idx, fileObj ->
                    val fileMap = fileObj as? Map<*, *> ?: return@forEachIndexed
                    val len = (fileMap["length"] as? Long) ?: 0L
                    val pathList = fileMap["path"] as? List<*>
                    val pathStr = pathList?.joinToString("/") { item ->
                        when (item) {
                            is ByteArray -> String(item, StandardCharsets.UTF_8)
                            is String -> item
                            else -> item.toString()
                        }
                    } ?: "file_$idx"

                    total += len
                    val ext = if (pathStr.contains('.')) pathStr.substringAfterLast('.').lowercase() else "mkv"
                    val isVideo = ext in listOf("mp4", "mkv", "avi", "mov", "webm", "m4v")
                    filesList.add(
                        TorrentFileInfo(
                            index = idx,
                            name = pathStr,
                            sizeBytes = len,
                            isVideo = isVideo,
                            extension = ext
                        )
                    )
                }
                resolvedFiles = filesList
                resolvedTotalSize = total
            } else {
                // Single file torrent
                val nameObj = dict["name"]
                val name = when (nameObj) {
                    is ByteArray -> String(nameObj, StandardCharsets.UTF_8)
                    is String -> nameObj
                    else -> "video.mkv"
                }
                val len = (dict["length"] as? Long) ?: 0L
                val ext = if (name.contains('.')) name.substringAfterLast('.').lowercase() else "mkv"
                resolvedFiles = mutableListOf(
                    TorrentFileInfo(
                        index = 0,
                        name = name,
                        sizeBytes = len,
                        isVideo = true,
                        extension = ext
                    )
                )
                resolvedTotalSize = len
            }

            isMetadataResolved = true
            _telemetry.value = _telemetry.value.copy(
                status = StreamStatus.BUFFERING,
                statusMessage = "Real metadata verified: ${resolvedFiles.size} files found (${formatBytes(resolvedTotalSize)})"
            )
            Log.d(TAG, "REAL METADATA PARSED: ${resolvedFiles.size} files, total bytes: $resolvedTotalSize")
        } catch (e: Exception) {
            Log.e(TAG, "Error parsing bencode info dict: ${e.message}")
        }
    }

    fun openStream(
        fileIndex: Int,
        rangeStart: Long,
        rangeEnd: Long
    ): StreamSource? {
        val file = resolvedFiles.getOrNull(fileIndex)
        val fileSize = file?.sizeBytes ?: resolvedTotalSize
        val end = if (rangeEnd > 0) rangeEnd else (fileSize - 1)
        val contentLength = if (fileSize > 0) (end - rangeStart + 1) else -1L

        // Calculate exact file offset in the torrent data stream
        val fileOffset = resolvedFiles.take(fileIndex).sumOf { it.sizeBytes }

        // Check if there is an active webseed
        val webseedUrl = webSeeds.getOrNull(fileIndex) ?: webSeeds.firstOrNull()
        if (!webseedUrl.isNullOrBlank()) {
            return openWebSeedStream(webseedUrl, rangeStart, end, fileSize)
        }

        // If no webseed, stream directly from swarm peers for this specific file range
        return openPeerWireStream(fileIndex, rangeStart, end, fileSize, fileOffset)
    }

    private fun openWebSeedStream(
        url: String,
        rangeStart: Long,
        rangeEnd: Long,
        totalSize: Long
    ): StreamSource? {
        val reqBuilder = Request.Builder().url(url)
        if (rangeStart > 0 || rangeEnd > 0) {
            val rangeHeader = if (rangeEnd > 0) "bytes=$rangeStart-$rangeEnd" else "bytes=$rangeStart-"
            reqBuilder.header("Range", rangeHeader)
        }

        return try {
            val resp = okHttpClient.newCall(reqBuilder.build()).execute()
            if (!resp.isSuccessful || resp.body == null) {
                resp.close()
                null
            } else {
                val body = resp.body!!
                val inStream = body.byteStream()
                val measuredStream = object : InputStream() {
                    override fun read(): Int {
                        val b = inStream.read()
                        if (b != -1) bytesInWindow.incrementAndGet()
                        return b
                    }

                    override fun read(b: ByteArray, off: Int, len: Int): Int {
                        val r = inStream.read(b, off, len)
                        if (r > 0) bytesInWindow.addAndGet(r.toLong())
                        return r
                    }

                    override fun close() {
                        inStream.close()
                        resp.close()
                    }
                }

                StreamSource(
                    inputStream = measuredStream,
                    contentType = resp.header("Content-Type") ?: "video/mp4",
                    contentLength = body.contentLength(),
                    contentRange = resp.header("Content-Range"),
                    statusCode = resp.code
                )
            }
        } catch (e: Exception) {
            Log.e(TAG, "Webseed stream error: ${e.message}")
            null
        }
    }

    private fun openPeerWireStream(
        fileIndex: Int,
        rangeStart: Long,
        rangeEnd: Long,
        fileSize: Long,
        fileOffset: Long
    ): StreamSource? {
        val pipedOut = PipedOutputStream()
        val pipedIn = PipedInputStream(pipedOut, 128 * 1024)

        scope.launch(Dispatchers.IO) {
            try {
                val pLen = if (pieceLength > 0) pieceLength else 2097152L
                val blockSize = 16384
                var currentTorrentByte = fileOffset + rangeStart
                val torrentEndByte = fileOffset + rangeEnd

                while (isActive && isSessionActive && (torrentEndByte <= 0 || currentTorrentByte <= torrentEndByte)) {
                    val pieceIndex = (currentTorrentByte / pLen).toInt()
                    val pieceOffset = (currentTorrentByte % pLen).toInt()

                    // Request blocks from connected unchoked peers
                    val unchokedPeers = activePeers.filter { it.isConnected() && !it.isChoked }
                    if (unchokedPeers.isNotEmpty()) {
                        val peer = unchokedPeers[Random.nextInt(unchokedPeers.size)]
                        peer.requestBlock(pieceIndex, pieceOffset, blockSize)
                    }

                    // Check if block arrived in cache
                    val blockKey = (pieceIndex.toLong() shl 32) or (pieceOffset.toLong() and 0xFFFFFFFFL)
                    val data = blockCache.remove(blockKey)

                    if (data != null) {
                        pipedOut.write(data)
                        pipedOut.flush()
                        currentTorrentByte += data.size
                    } else {
                        delay(25)
                    }
                }
            } catch (e: Exception) {
                Log.d(TAG, "Peer stream ended: ${e.message}")
            } finally {
                try {
                    pipedOut.close()
                } catch (ignored: Exception) {}
            }
        }

        return StreamSource(
            inputStream = pipedIn,
            contentType = "video/x-matroska",
            contentLength = if (fileSize > 0) (rangeEnd - rangeStart + 1) else -1L,
            contentRange = if (fileSize > 0) "bytes $rangeStart-$rangeEnd/$fileSize" else null,
            statusCode = if (rangeStart > 0) 206 else 200
        )
    }

    private fun startTelemetryTracker() {
        scope.launch(Dispatchers.Default) {
            while (isActive && isSessionActive) {
                val now = System.currentTimeMillis()
                val elapsed = maxOf(1L, now - lastSpeedCheck)
                val bytes = bytesInWindow.getAndSet(0L)
                val realSpeedBps = (bytes * 1000L) / elapsed
                lastSpeedCheck = now

                // REAL connected peers: count of active TCP sockets
                val realPeersCount = activePeers.count { it.isConnected() }

                _telemetry.value = _telemetry.value.copy(
                    downloadSpeedBps = realSpeedBps,
                    uploadSpeedBps = 0L,
                    connectedPeers = realPeersCount,
                    bytesStreamed = _telemetry.value.bytesStreamed + bytes,
                    status = if (realSpeedBps > 0) StreamStatus.STREAMING else _telemetry.value.status
                )

                delay(1000)
            }
        }
    }

    fun stop() {
        isSessionActive = false
        activePeers.forEach { it.close() }
        activePeers.clear()
        blockCache.clear()
        _telemetry.value = StreamTelemetry(status = StreamStatus.IDLE)
    }

    data class StreamSource(
        val inputStream: InputStream,
        val contentType: String,
        val contentLength: Long,
        val contentRange: String?,
        val statusCode: Int
    )

    private fun generatePeerId(): ByteArray {
        val id = ByteArray(20)
        val prefix = "-TS1000-".toByteArray(StandardCharsets.US_ASCII)
        System.arraycopy(prefix, 0, id, 0, prefix.size)
        for (i in prefix.size until 20) {
            id[i] = Random.nextInt(0, 255).toByte()
        }
        return id
    }

    private fun hexToBytes(hex: String): ByteArray {
        val len = hex.length
        val data = ByteArray(len / 2)
        var i = 0
        while (i < len) {
            data[i / 2] = ((Character.digit(hex[i], 16) shl 4) + Character.digit(hex[i + 1], 16)).toByte()
            i += 2
        }
        return data
    }

    private fun formatBytes(bytes: Long): String {
        val mb = bytes / (1024.0 * 1024.0)
        return if (mb >= 1024) String.format("%.2f GB", mb / 1024.0) else String.format("%.0f MB", mb)
    }
}
