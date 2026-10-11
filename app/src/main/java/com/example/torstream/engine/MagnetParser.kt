package com.example.torstream.engine

import android.net.Uri
import com.example.torstream.data.model.StreamItem
import com.example.torstream.data.model.TorrentFileInfo
import java.net.URLDecoder

object MagnetParser {

    data class ParsedMagnet(
        val infoHash: String,
        val displayName: String,
        val trackers: List<String>,
        val webSeeds: List<String>,
        val files: List<TorrentFileInfo>
    )

    fun parse(input: String): ParsedMagnet? {
        val trimmed = input.trim()
        if (trimmed.isEmpty()) return null

        // Raw 40 hex char hash
        if (trimmed.matches(Regex("^[a-fA-F0-9]{40}$"))) {
            val hash = trimmed.lowercase()
            val name = "Torrent [${hash.take(8).uppercase()}]"
            return ParsedMagnet(
                infoHash = hash,
                displayName = name,
                trackers = defaultTrackers(),
                webSeeds = emptyList(),
                files = listOf(
                    TorrentFileInfo(
                        index = 0,
                        name = name,
                        sizeBytes = 0L, // 0 = not resolved yet from swarm
                        isVideo = true,
                        extension = "mkv"
                    )
                )
            )
        }

        // Base32 32 char hash
        if (trimmed.matches(Regex("^[2-7a-zA-Z]{32}$"))) {
            val hex = base32ToHex(trimmed).lowercase()
            val name = "Torrent [${hex.take(8).uppercase()}]"
            return ParsedMagnet(
                infoHash = hex,
                displayName = name,
                trackers = defaultTrackers(),
                webSeeds = emptyList(),
                files = listOf(
                    TorrentFileInfo(
                        index = 0,
                        name = name,
                        sizeBytes = 0L,
                        isVideo = true,
                        extension = "mkv"
                    )
                )
            )
        }

        // Magnet URI
        if (trimmed.startsWith("magnet:?", ignoreCase = true)) {
            return parseMagnetUri(trimmed)
        }

        // Direct stream / web link
        if (trimmed.startsWith("http://", ignoreCase = true) || trimmed.startsWith("https://", ignoreCase = true)) {
            val name = try {
                val uri = Uri.parse(trimmed)
                uri.lastPathSegment ?: "Stream File"
            } catch (e: Exception) {
                "Stream File"
            }
            val fakeHash = Integer.toHexString(trimmed.hashCode()).padStart(8, '0').repeat(5).take(40)
            val cleanName = name.replace(".mp4", "").replace(".mkv", "").replace("_", " ")
            val ext = if (name.endsWith(".mkv", ignoreCase = true)) "mkv" else "mp4"
            return ParsedMagnet(
                infoHash = fakeHash,
                displayName = cleanName,
                trackers = defaultTrackers(),
                webSeeds = listOf(trimmed),
                files = listOf(
                    TorrentFileInfo(
                        index = 0,
                        name = name,
                        sizeBytes = 0L,
                        isVideo = true,
                        extension = ext
                    )
                )
            )
        }

        return null
    }

    private fun parseMagnetUri(magnetUrl: String): ParsedMagnet? {
        var infoHash = ""
        var displayName = ""
        val trackers = mutableListOf<String>()
        val webSeeds = mutableListOf<String>()

        try {
            val queryString = magnetUrl.substringAfter("magnet:?")
            val params = queryString.split("&")

            for (param in params) {
                val parts = param.split("=", limit = 2)
                if (parts.size == 2) {
                    val key = parts[0].lowercase()
                    val value = try {
                        URLDecoder.decode(parts[1], "UTF-8")
                    } catch (e: Exception) {
                        parts[1]
                    }

                    when (key) {
                        "xt" -> {
                            if (value.startsWith("urn:btih:", ignoreCase = true)) {
                                val rawHash = value.substringAfter("urn:btih:")
                                infoHash = if (rawHash.length == 32) {
                                    base32ToHex(rawHash)
                                } else {
                                    rawHash.lowercase()
                                }
                            }
                        }
                        "dn" -> displayName = value
                        "tr" -> trackers.add(value)
                        "ws", "as" -> webSeeds.add(value)
                    }
                }
            }
        } catch (e: Exception) {
            return null
        }

        if (infoHash.isEmpty()) return null

        if (displayName.isEmpty()) {
            displayName = "Torrent [${infoHash.take(8).uppercase()}]"
        }

        if (trackers.isEmpty()) {
            trackers.addAll(defaultTrackers())
        }

        // Build file list based on real parameters:
        // If web seeds provide distinct files, list them.
        // Otherwise, list the real display name from the magnet.
        val files = mutableListOf<TorrentFileInfo>()
        if (webSeeds.size > 1) {
            webSeeds.forEachIndexed { idx, url ->
                val filename = try {
                    Uri.parse(url).lastPathSegment ?: "file_${idx + 1}"
                } catch (e: Exception) {
                    "file_${idx + 1}"
                }
                val ext = if (filename.endsWith(".mkv", ignoreCase = true)) "mkv" else "mp4"
                files.add(
                    TorrentFileInfo(
                        index = idx,
                        name = filename,
                        sizeBytes = 0L,
                        isVideo = true,
                        extension = ext
                    )
                )
            }
        } else {
            val ext = if (displayName.endsWith(".mp4", ignoreCase = true)) "mp4" else "mkv"
            files.add(
                TorrentFileInfo(
                    index = 0,
                    name = displayName,
                    sizeBytes = 0L, // 0 = unknown until swarm metadata is fetched
                    isVideo = true,
                    extension = ext
                )
            )
        }

        return ParsedMagnet(
            infoHash = infoHash,
            displayName = displayName,
            trackers = trackers,
            webSeeds = webSeeds,
            files = files
        )
    }

    fun toStreamItem(
        parsed: ParsedMagnet,
        originalInput: String,
        selectedFile: TorrentFileInfo
    ): StreamItem {
        val directUrl = parsed.webSeeds.getOrNull(selectedFile.index) ?: parsed.webSeeds.firstOrNull()
        return StreamItem(
            id = "stream_${parsed.infoHash}_${selectedFile.index}",
            title = parsed.displayName,
            magnetUri = originalInput,
            infoHash = parsed.infoHash,
            selectedFileName = selectedFile.name,
            selectedFileIndex = selectedFile.index,
            files = parsed.files,
            trackers = parsed.trackers,
            directStreamUrl = directUrl,
            durationMs = 0L,
            fileSizeBytes = selectedFile.sizeBytes,
            category = "P2P Stream",
            description = selectedFile.name,
            resolutionTag = if (selectedFile.name.contains("1080p", ignoreCase = true)) "1080p" else "HD",
            lastPlayedPositionMs = 0L,
            lastPlayedTimestamp = System.currentTimeMillis()
        )
    }

    private fun base32ToHex(base32: String): String {
        val base32Chars = "ABCDEFGHIJKLMNOPQRSTUVWXYZ234567"
        var buffer = 0L
        var bitsLeft = 0
        val bytes = mutableListOf<Byte>()

        for (c in base32.uppercase()) {
            val valIndex = base32Chars.indexOf(c)
            if (valIndex < 0) continue
            buffer = (buffer shl 5) or valIndex.toLong()
            bitsLeft += 5
            if (bitsLeft >= 8) {
                bitsLeft -= 8
                bytes.add(((buffer shr bitsLeft) and 0xFF).toByte())
            }
        }

        return bytes.joinToString("") { "%02x".format(it) }
    }

    fun defaultTrackers(): List<String> = listOf(
        "udp://tracker.opentrackr.org:1337/announce",
        "udp://open.stealth.si:80/announce",
        "udp://tracker.openbittorrent.com:6969/announce",
        "udp://tracker.coppersurfer.tk:6969/announce",
        "udp://tracker.torrent.eu.org:451/announce"
    )
}
