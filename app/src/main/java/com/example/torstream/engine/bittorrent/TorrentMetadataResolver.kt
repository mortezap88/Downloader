package com.example.torstream.engine.bittorrent

import android.util.Log
import com.example.torstream.data.model.TorrentFileInfo
import com.example.torstream.engine.bencode.BencodeParser
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.nio.charset.StandardCharsets

object TorrentMetadataResolver {
    private const val TAG = "MetadataResolver"

    data class ResolvedMetadata(
        val name: String,
        val totalSize: Long,
        val files: List<TorrentFileInfo>,
        val pieceLength: Long,
        val webSeeds: List<String> = emptyList()
    )

    suspend fun resolve(
        infoHashHex: String,
        fallbackName: String,
        trackers: List<String>,
        client: OkHttpClient
    ): ResolvedMetadata = withContext(Dispatchers.IO) {
        val cleanHash = infoHashHex.trim().lowercase()

        // 1. Try public torrent cache networks with browser User-Agent
        val cacheUrls = listOf(
            "https://itorrents.org/torrent/${cleanHash}.torrent",
            "https://itorrents.org/torrent/${cleanHash.uppercase()}.torrent",
            "https://torrage.info/torrent.php?h=${cleanHash}",
            "https://torrage.info/torrent.php?h=${cleanHash.uppercase()}"
        )

        for (url in cacheUrls) {
            try {
                val req = Request.Builder()
                    .url(url)
                    .header("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36")
                    .header("Accept", "*/*")
                    .build()

                val resp = client.newCall(req).execute()
                if (resp.isSuccessful && resp.body != null) {
                    val rawBytes = resp.body!!.bytes()
                    resp.close()
                    val bytes = if (rawBytes.size > 2 && rawBytes[0] == 0x1f.toByte() && rawBytes[1] == 0x8b.toByte()) {
                        try {
                            java.util.zip.GZIPInputStream(java.io.ByteArrayInputStream(rawBytes)).readBytes()
                        } catch (e: Exception) { rawBytes }
                    } else rawBytes

                    if (bytes.size > 15) {
                        val prefix = String(bytes.copyOfRange(0, minOf(bytes.size, 15)), StandardCharsets.ISO_8859_1).lowercase()
                        if (!prefix.contains("<!doct") && !prefix.contains("<html")) {
                            val parsed = parseTorrentBytes(bytes, fallbackName)
                            if (parsed != null && parsed.files.isNotEmpty()) {
                                Log.d(TAG, "Successfully resolved real metadata from $url: ${parsed.files.size} files, ${parsed.webSeeds.size} webseeds!")
                                return@withContext parsed
                            }
                        }
                    }
                } else {
                    resp.close()
                }
            } catch (e: Exception) {
                Log.d(TAG, "Cache query $url error: ${e.message}")
            }
        }

        // 2. Try apibay.org file index if available
        try {
            val req = Request.Builder()
                .url("https://apibay.org/f.php?id=$cleanHash")
                .header("User-Agent", "Mozilla/5.0")
                .build()
            val resp = client.newCall(req).execute()
            if (resp.isSuccessful && resp.body != null) {
                val jsonStr = resp.body!!.string()
                resp.close()
                val parsedFiles = parseApibayFiles(jsonStr)
                if (parsedFiles.isNotEmpty()) {
                    val total = parsedFiles.sumOf { it.sizeBytes }
                    return@withContext ResolvedMetadata(
                        name = fallbackName,
                        totalSize = total,
                        files = parsedFiles,
                        pieceLength = 2097152L
                    )
                }
            } else {
                resp.close()
            }
        } catch (e: Exception) {
            Log.d(TAG, "Apibay query error: ${e.message}")
        }

        // 3. Fallback: single file with 0 size until peer ut_metadata arrives
        val ext = if (fallbackName.endsWith(".mp4", ignoreCase = true)) "mp4" else "mkv"
        return@withContext ResolvedMetadata(
            name = fallbackName,
            totalSize = 0L,
            files = listOf(
                TorrentFileInfo(
                    index = 0,
                    name = fallbackName,
                    sizeBytes = 0L,
                    isVideo = true,
                    extension = ext
                )
            ),
            pieceLength = 2097152L
        )
    }

    fun parseTorrentBytes(bytes: ByteArray, fallbackName: String): ResolvedMetadata? {
        val root = BencodeParser.decode(bytes) as? Map<*, *> ?: return null
        val info = root["info"] as? Map<*, *> ?: return null

        val pLen = (info["piece length"] as? Long) ?: 2097152L
        val nameObj = info["name"]
        val torrentName = when (nameObj) {
            is ByteArray -> String(nameObj, StandardCharsets.UTF_8)
            is String -> nameObj
            else -> fallbackName
        }

        // Extract BEP 19 url-list (WebSeeds)
        val urlListObj = root["url-list"]
        val baseWebSeeds = mutableListOf<String>()
        when (urlListObj) {
            is ByteArray -> baseWebSeeds.add(String(urlListObj, StandardCharsets.UTF_8))
            is String -> baseWebSeeds.add(urlListObj)
            is List<*> -> {
                urlListObj.forEach { item ->
                    when (item) {
                        is ByteArray -> baseWebSeeds.add(String(item, StandardCharsets.UTF_8))
                        is String -> baseWebSeeds.add(item)
                    }
                }
            }
        }

        val filesRaw = info["files"] as? List<*>
        if (filesRaw != null) {
            // Multi-file torrent (e.g. TV Season, Collections)
            val filesList = mutableListOf<TorrentFileInfo>()
            val resolvedWebSeeds = mutableListOf<String>()
            var total = 0L

            filesRaw.forEachIndexed { idx, fileObj ->
                val fMap = fileObj as? Map<*, *> ?: return@forEachIndexed
                val len = (fMap["length"] as? Long) ?: 0L
                val pathList = fMap["path"] as? List<*>
                val pathStr = pathList?.joinToString("/") { seg ->
                    when (seg) {
                        is ByteArray -> String(seg, StandardCharsets.UTF_8)
                        is String -> seg
                        else -> seg.toString()
                    }
                } ?: "file_$idx"

                total += len
                val ext = if (pathStr.contains('.')) pathStr.substringAfterLast('.').lowercase() else "mkv"
                val isVideo = ext in listOf("mkv", "mp4", "avi", "mov", "webm", "ts", "m4v")

                filesList.add(
                    TorrentFileInfo(
                        index = idx,
                        name = pathStr,
                        sizeBytes = len,
                        isVideo = isVideo,
                        extension = ext
                    )
                )

                // Match webseed for this file
                val matchedWs = baseWebSeeds.firstOrNull()?.let { base ->
                    if (base.endsWith("/")) "$base$torrentName/$pathStr" else base
                } ?: ""
                resolvedWebSeeds.add(matchedWs)
            }

            return ResolvedMetadata(
                name = torrentName,
                totalSize = total,
                files = filesList,
                pieceLength = pLen,
                webSeeds = resolvedWebSeeds.filter { it.isNotBlank() }
            )
        } else {
            // Single file torrent
            val len = (info["length"] as? Long) ?: 0L
            val ext = if (torrentName.contains('.')) torrentName.substringAfterLast('.').lowercase() else "mkv"
            val singleWs = baseWebSeeds.firstOrNull()?.let { base ->
                if (base.endsWith("/")) "$base$torrentName" else base
            }
            return ResolvedMetadata(
                name = torrentName,
                totalSize = len,
                files = listOf(
                    TorrentFileInfo(
                        index = 0,
                        name = torrentName,
                        sizeBytes = len,
                        isVideo = true,
                        extension = ext
                    )
                ),
                pieceLength = pLen,
                webSeeds = if (singleWs != null) listOf(singleWs) else emptyList()
            )
        }
    }

    private fun parseApibayFiles(jsonStr: String): List<TorrentFileInfo> {
        val files = mutableListOf<TorrentFileInfo>()
        try {
            val jsonArray = org.json.JSONArray(jsonStr)
            for (i in 0 until jsonArray.length()) {
                val obj = jsonArray.getJSONObject(i)
                val name = obj.optString("name", "")
                if (name.isBlank() || name.contains("Filelist not found", ignoreCase = true)) continue
                val size = obj.optLong("size", 0L)
                val ext = if (name.contains('.')) name.substringAfterLast('.').lowercase() else "mkv"
                val isVideo = ext in listOf("mkv", "mp4", "avi", "mov", "webm", "ts", "m4v")
                files.add(
                    TorrentFileInfo(
                        index = i,
                        name = name,
                        sizeBytes = size,
                        isVideo = isVideo,
                        extension = ext
                    )
                )
            }
        } catch (e: Exception) {
            // ignore
        }
        return files
    }
}
