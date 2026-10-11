package com.example.torstream.engine

import android.util.Log
import com.example.torstream.data.model.StreamItem
import com.example.torstream.data.model.StreamStatus
import com.example.torstream.data.model.StreamTelemetry
import com.example.torstream.engine.bittorrent.RealTorrentSession
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import okhttp3.OkHttpClient
import java.io.BufferedOutputStream
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit

class TorrentStreamServer(
    private val port: Int = 8828,
    private val client: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .build()
) {
    private val TAG = "TorStreamServer"
    private var serverSocket: ServerSocket? = null
    private var serverJob: Job? = null
    private var scope: CoroutineScope? = null

    private val activeStreams = ConcurrentHashMap<String, StreamItem>()
    private var activeSession: RealTorrentSession? = null

    private val _telemetry = MutableStateFlow(StreamTelemetry())
    val telemetry: StateFlow<StreamTelemetry> = _telemetry.asStateFlow()

    private var activeStreamItem: StreamItem? = null
    private var telemetryForwardJob: Job? = null

    val isRunning: Boolean
        get() = serverSocket != null && !serverSocket!!.isClosed

    fun getStreamUrl(item: StreamItem): String {
        activeStreamItem = item
        activeStreams[item.infoHash] = item

        // Start real BitTorrent session
        startRealTorrentSession(item)

        return "http://127.0.0.1:$port/stream?hash=${item.infoHash}&idx=${item.selectedFileIndex}"
    }

    private fun startRealTorrentSession(item: StreamItem) {
        activeSession?.stop()
        val coroutineScope = scope ?: return

        val webseeds = if (item.directStreamUrl != null) listOf(item.directStreamUrl) else emptyList()
        val session = RealTorrentSession(
            infoHashHex = item.infoHash,
            trackers = item.trackers,
            webSeeds = webseeds,
            scope = coroutineScope,
            okHttpClient = client
        ).apply {
            if (item.files.isNotEmpty()) {
                this.resolvedFiles.clear()
                this.resolvedFiles.addAll(item.files)
                this.resolvedTotalSize = item.files.sumOf { it.sizeBytes }
                this.isMetadataResolved = true
            }
        }
        activeSession = session
        session.start()

        telemetryForwardJob?.cancel()
        telemetryForwardJob = coroutineScope.launch {
            session.telemetry.collect { realStats ->
                _telemetry.value = realStats
            }
        }
    }

    fun start(scope: CoroutineScope) {
        this.scope = scope
        if (isRunning) return

        serverJob = scope.launch(Dispatchers.IO) {
            try {
                serverSocket = ServerSocket(port)
                Log.d(TAG, "Torrent stream server started on port $port")

                while (isActive && serverSocket != null && !serverSocket!!.isClosed) {
                    try {
                        val clientSocket = serverSocket!!.accept()
                        launch(Dispatchers.IO) {
                            handleClientSocket(clientSocket)
                        }
                    } catch (e: Exception) {
                        if (!isActive) break
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error starting server: ${e.message}")
            }
        }
    }

    private fun handleClientSocket(socket: Socket) {
        try {
            socket.soTimeout = 30000
            val reader = BufferedReader(InputStreamReader(socket.getInputStream()))
            val out = BufferedOutputStream(socket.getOutputStream())

            val requestLine = reader.readLine() ?: return
            Log.d(TAG, "Request: $requestLine")

            val headers = mutableMapOf<String, String>()
            var line: String? = reader.readLine()
            while (!line.isNullOrEmpty()) {
                val colonIdx = line.indexOf(':')
                if (colonIdx > 0) {
                    val k = line.substring(0, colonIdx).trim().lowercase()
                    val v = line.substring(colonIdx + 1).trim()
                    headers[k] = v
                }
                line = reader.readLine()
            }

            // Extract hash and file index
            val hash = requestLine.substringAfter("hash=", "").substringBefore(" ").substringBefore("&")
            val fileIdx = requestLine.substringAfter("idx=", "0").substringBefore(" ").substringBefore("&").toIntOrNull() ?: 0

            val item = activeStreams[hash] ?: activeStreamItem
            val session = activeSession

            if (item == null || session == null) {
                sendNotFound(out)
                socket.close()
                return
            }

            // Parse Range header
            val rangeHeader = headers["range"]
            var rangeStart = 0L
            var rangeEnd = -1L

            if (!rangeHeader.isNullOrEmpty() && rangeHeader.startsWith("bytes=")) {
                val rangeVal = rangeHeader.removePrefix("bytes=").trim()
                val parts = rangeVal.split("-")
                rangeStart = parts[0].toLongOrNull() ?: 0L
                if (parts.size > 1 && parts[1].isNotEmpty()) {
                    rangeEnd = parts[1].toLongOrNull() ?: -1L
                }
            }

            // Open stream from real BitTorrent session with brief retry for initial peer negotiation
            var streamSource = session.openStream(fileIdx, rangeStart, rangeEnd)
            var retries = 0
            while (streamSource == null && retries < 10 && socket.isConnected && !socket.isClosed) {
                Thread.sleep(250)
                streamSource = session.openStream(fileIdx, rangeStart, rangeEnd)
                retries++
            }

            if (streamSource == null) {
                sendError(out, 503)
                socket.close()
                return
            }

            // Send real HTTP response headers
            val headerBuilder = StringBuilder()
            if (streamSource.statusCode == 206) {
                headerBuilder.append("HTTP/1.1 206 Partial Content\r\n")
            } else {
                headerBuilder.append("HTTP/1.1 200 OK\r\n")
            }

            headerBuilder.append("Content-Type: ${streamSource.contentType}\r\n")
            headerBuilder.append("Accept-Ranges: bytes\r\n")
            if (streamSource.contentLength > 0) {
                headerBuilder.append("Content-Length: ${streamSource.contentLength}\r\n")
            }
            if (!streamSource.contentRange.isNullOrEmpty()) {
                headerBuilder.append("Content-Range: ${streamSource.contentRange}\r\n")
            }
            headerBuilder.append("Connection: keep-alive\r\n")
            headerBuilder.append("Access-Control-Allow-Origin: *\r\n\r\n")

            out.write(headerBuilder.toString().toByteArray())
            out.flush()

            // Stream real bytes directly to ExoPlayer
            val inStream = streamSource.inputStream
            val buffer = ByteArray(32 * 1024)
            var bytesRead: Int
            while (inStream.read(buffer).also { bytesRead = it } != -1) {
                out.write(buffer, 0, bytesRead)
                out.flush()
            }
            inStream.close()

        } catch (e: Exception) {
            Log.d(TAG, "Client socket ended: ${e.message}")
        } finally {
            try {
                socket.close()
            } catch (ignored: Exception) {}
        }
    }

    private fun sendNotFound(out: BufferedOutputStream) {
        val resp = "HTTP/1.1 404 Not Found\r\nContent-Length: 0\r\n\r\n"
        out.write(resp.toByteArray())
        out.flush()
    }

    private fun sendError(out: BufferedOutputStream, code: Int) {
        val resp = "HTTP/1.1 $code Service Unavailable\r\nContent-Length: 0\r\n\r\n"
        out.write(resp.toByteArray())
        out.flush()
    }

    fun stop() {
        telemetryForwardJob?.cancel()
        activeSession?.stop()
        activeSession = null
        serverJob?.cancel()
        try {
            serverSocket?.close()
        } catch (ignored: Exception) {}
        serverSocket = null
        activeStreams.clear()
        activeStreamItem = null
        _telemetry.value = StreamTelemetry(status = StreamStatus.IDLE)
        Log.d(TAG, "Torrent stream server stopped.")
    }
}
