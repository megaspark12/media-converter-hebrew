package com.mediaconverter.app.data.db

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Dao
interface DownloadDao {

    @Query("SELECT * FROM downloads ORDER BY createdAt DESC")
    fun getAllDownloads(): Flow<List<DownloadEntity>>

    @Query("SELECT * FROM downloads WHERE id = :id")
    suspend fun getDownloadById(id: Long): DownloadEntity?

    @Query("SELECT * FROM downloads WHERE id = :id")
    fun observeDownloadById(id: Long): Flow<DownloadEntity?>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertDownload(download: DownloadEntity): Long

    @Delete
    suspend fun deleteDownload(download: DownloadEntity)

    @Query("UPDATE downloads SET progress = :progress, status = :status WHERE id = :id AND status IN ('pending', 'downloading')")
    suspend fun updateProgress(id: Long, progress: Int, status: String): Int

    @Query("UPDATE downloads SET status = 'failed', errorMessage = :error, errorCode = :errorCode WHERE id = :id AND status IN ('pending', 'downloading')")
    suspend fun markFailed(id: Long, error: String, errorCode: String): Int

    @Query("UPDATE downloads SET status = 'completed', progress = 100, outputUri = :outputUri, mimeType = :mimeType, fileSize = :fileSize, completedAt = :completedAt WHERE id = :id AND status IN ('pending', 'downloading')")
    suspend fun markCompleted(
        id: Long,
        outputUri: String,
        mimeType: String,
        fileSize: Long,
        completedAt: Long = System.currentTimeMillis(),
    ): Int

    @Query("UPDATE downloads SET status = 'pending', retryCount = retryCount + 1, errorMessage = :error, errorCode = :errorCode WHERE id = :id AND status IN ('pending', 'downloading')")
    suspend fun markRetryPending(id: Long, error: String, errorCode: String): Int

    @Query("UPDATE downloads SET status = 'cancelled', errorMessage = 'Cancelled', errorCode = :errorCode WHERE id = :id AND status IN ('pending', 'downloading')")
    suspend fun markCancelled(id: Long, errorCode: String): Int
}
