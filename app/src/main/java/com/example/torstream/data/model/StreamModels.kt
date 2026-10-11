package com.example.torstream.data.model

enum class StreamStatus {
    IDLE,
    RESOLVING_METADATA,
    CONNECTING_PEERS,
    BUFFERING,
    STREAMING,
    PAUSED,
    ERROR
}

data class TorrentFileInfo(
    val index: Int,
    val name: String,
    val sizeBytes: Long = 0L,
    val isVideo: Boolean = true,
    val extension: String = "mkv"
) {
    val formattedSize: String
        get() {
            if (sizeBytes <= 0) return "Resolving from swarm..."
            val mb = sizeBytes / (1024.0 * 1024.0)
            return if (mb >= 1024) {
                String.format("%.2f GB", mb / 1024.0)
            } else {
                String.format("%.0f MB", mb)
            }
        }
}

data class StreamTelemetry(
    val downloadSpeedBps: Long = 0L,
    val uploadSpeedBps: Long = 0L,
    val connectedPeers: Int = 0,
    val totalSeeds: Int = 0,
    val bufferHealthSeconds: Float = 0f,
    val totalPieces: Int = 64,
    val bufferedPieces: Set<Int> = emptySet(),
    val activeDownloadingPieces: Set<Int> = emptySet(),
    val status: StreamStatus = StreamStatus.IDLE,
    val statusMessage: String = "Ready to stream",
    val bytesStreamed: Long = 0L
) {
    val downloadSpeedFormatted: String
        get() = formatSpeed(downloadSpeedBps)

    val uploadSpeedFormatted: String
        get() = formatSpeed(uploadSpeedBps)

    val bufferPercent: Int
        get() = if (totalPieces > 0) ((bufferedPieces.size.toFloat() / totalPieces) * 100).toInt() else 0

    private fun formatSpeed(bytesPerSec: Long): String {
        return when {
            bytesPerSec >= 1024 * 1024 -> String.format("%.1f MB/s", bytesPerSec / (1024.0 * 1024.0))
            bytesPerSec >= 1024 -> String.format("%.0f KB/s", bytesPerSec / 1024.0)
            else -> "$bytesPerSec B/s"
        }
    }
}

data class StreamItem(
    val id: String,
    val title: String,
    val magnetUri: String,
    val infoHash: String,
    val selectedFileName: String = "",
    val selectedFileIndex: Int = 0,
    val files: List<TorrentFileInfo> = emptyList(),
    val trackers: List<String> = emptyList(),
    val directStreamUrl: String? = null,
    val durationMs: Long = 0L,
    val fileSizeBytes: Long = 0L,
    val category: String = "Magnet Stream",
    val description: String = "",
    val resolutionTag: String = "HD",
    val lastPlayedPositionMs: Long = 0L,
    val lastPlayedTimestamp: Long = 0L
) {
    val displayFileTitle: String
        get() = if (selectedFileName.isNotBlank()) selectedFileName else title

    val formattedDuration: String
        get() {
            if (durationMs <= 0) return "--:--"
            val totalSeconds = durationMs / 1000
            val minutes = totalSeconds / 60
            val seconds = totalSeconds % 60
            val hours = minutes / 60
            return if (hours > 0) {
                String.format("%d:%02d:%02d", hours, minutes % 60, seconds)
            } else {
                String.format("%02d:%02d", minutes, seconds)
            }
        }

    val formattedSize: String
        get() {
            val bytes = if (fileSizeBytes > 0) fileSizeBytes else files.getOrNull(selectedFileIndex)?.sizeBytes ?: 0L
            if (bytes <= 0) return "Resolving size..."
            val mb = bytes / (1024.0 * 1024.0)
            return if (mb >= 1024) {
                String.format("%.2f GB", mb / 1024.0)
            } else {
                String.format("%.0f MB", mb)
            }
        }
}
