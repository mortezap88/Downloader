package com.example.torstream.data.local

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Dao
interface StreamHistoryDao {
    @Query("SELECT * FROM stream_history ORDER BY lastPlayedTimestamp DESC")
    fun getAllStreams(): Flow<List<StreamHistoryEntity>>

    @Query("SELECT * FROM stream_history WHERE id = :id LIMIT 1")
    suspend fun getById(id: String): StreamHistoryEntity?

    @Query("SELECT * FROM stream_history WHERE infoHash = :infoHash LIMIT 1")
    suspend fun getByInfoHash(infoHash: String): StreamHistoryEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertOrUpdate(entity: StreamHistoryEntity)

    @Query("UPDATE stream_history SET lastPlayedPositionMs = :positionMs, lastPlayedTimestamp = :timestamp WHERE id = :id")
    suspend fun updatePlaybackPosition(id: String, positionMs: Long, timestamp: Long)

    @Query("UPDATE stream_history SET selectedFileName = :fileName, selectedFileIndex = :fileIndex, lastPlayedPositionMs = 0 WHERE id = :id")
    suspend fun updateSelectedFile(id: String, fileName: String, fileIndex: Int)

    @Query("DELETE FROM stream_history WHERE id = :id")
    suspend fun deleteById(id: String)

    @Query("DELETE FROM stream_history")
    suspend fun clearAll()
}
