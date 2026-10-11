package com.example.torstream.data.repository

import com.example.torstream.data.local.StreamHistoryDao
import com.example.torstream.data.local.StreamHistoryEntity
import com.example.torstream.data.model.StreamItem
import com.example.torstream.data.model.TorrentFileInfo
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

class StreamRepository(private val dao: StreamHistoryDao) {

    val allStreams: Flow<List<StreamItem>> = dao.getAllStreams().map { list ->
        list.map { it.toStreamItem() }
    }

    suspend fun getStreamItemById(id: String): StreamItem? {
        return dao.getById(id)?.toStreamItem()
    }

    suspend fun saveStreamItem(item: StreamItem, positionMs: Long = 0L) {
        val existing = dao.getById(item.id)
        val filesJson = serializeFiles(item.files)
        val entity = StreamHistoryEntity(
            id = item.id,
            title = item.title,
            magnetUri = item.magnetUri,
            infoHash = item.infoHash,
            selectedFileName = item.selectedFileName,
            selectedFileIndex = item.selectedFileIndex,
            filesJson = if (filesJson.isNotEmpty()) filesJson else (existing?.filesJson ?: ""),
            directStreamUrl = item.directStreamUrl,
            durationMs = item.durationMs,
            fileSizeBytes = item.fileSizeBytes,
            category = item.category,
            description = item.description,
            resolutionTag = item.resolutionTag,
            lastPlayedPositionMs = if (positionMs > 0) positionMs else (existing?.lastPlayedPositionMs ?: item.lastPlayedPositionMs),
            lastPlayedTimestamp = System.currentTimeMillis()
        )
        dao.insertOrUpdate(entity)
    }

    suspend fun updatePlaybackPosition(id: String, positionMs: Long) {
        dao.updatePlaybackPosition(id, positionMs, System.currentTimeMillis())
    }

    suspend fun updateSelectedFile(id: String, fileName: String, fileIndex: Int) {
        dao.updateSelectedFile(id, fileName, fileIndex)
    }

    suspend fun deleteStream(id: String) {
        dao.deleteById(id)
    }

    suspend fun clearAll() {
        dao.clearAll()
    }

    private fun serializeFiles(files: List<TorrentFileInfo>): String {
        if (files.isEmpty()) return ""
        return files.joinToString(";;;") { "${it.index}:::${it.name}:::${it.sizeBytes}:::${it.isVideo}:::${it.extension}" }
    }

    private fun deserializeFiles(serialized: String): List<TorrentFileInfo> {
        if (serialized.isBlank()) return emptyList()
        return try {
            serialized.split(";;;").mapNotNull { entry ->
                val parts = entry.split(":::")
                if (parts.size >= 5) {
                    TorrentFileInfo(
                        index = parts[0].toIntOrNull() ?: 0,
                        name = parts[1],
                        sizeBytes = parts[2].toLongOrNull() ?: 0L,
                        isVideo = parts[3].toBooleanStrictOrNull() ?: true,
                        extension = parts[4]
                    )
                } else null
            }
        } catch (e: Exception) {
            emptyList()
        }
    }

    private fun StreamHistoryEntity.toStreamItem(): StreamItem {
        val parsedFiles = deserializeFiles(this.filesJson)
        return StreamItem(
            id = this.id,
            title = this.title,
            magnetUri = this.magnetUri,
            infoHash = this.infoHash,
            selectedFileName = this.selectedFileName,
            selectedFileIndex = this.selectedFileIndex,
            files = parsedFiles,
            directStreamUrl = this.directStreamUrl,
            durationMs = this.durationMs,
            fileSizeBytes = this.fileSizeBytes,
            category = this.category,
            description = this.description,
            resolutionTag = this.resolutionTag,
            lastPlayedPositionMs = this.lastPlayedPositionMs,
            lastPlayedTimestamp = this.lastPlayedTimestamp
        )
    }
}
