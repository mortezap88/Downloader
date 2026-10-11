package com.example.torstream.data.local

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "stream_history")
data class StreamHistoryEntity(
    @PrimaryKey
    val id: String,
    val title: String,
    val magnetUri: String,
    val infoHash: String,
    val selectedFileName: String = "",
    val selectedFileIndex: Int = 0,
    val filesJson: String = "",
    val directStreamUrl: String? = null,
    val durationMs: Long = 0L,
    val fileSizeBytes: Long = 0L,
    val category: String = "Magnet Stream",
    val description: String = "",
    val resolutionTag: String = "HD",
    val lastPlayedPositionMs: Long = 0L,
    val lastPlayedTimestamp: Long = System.currentTimeMillis()
)
