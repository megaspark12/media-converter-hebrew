package com.mediaconverter.app.data

import android.content.Context
import kotlinx.coroutines.flow.Flow
import com.mediaconverter.app.data.db.AppDatabase
import com.mediaconverter.app.data.db.DownloadDao
import com.mediaconverter.app.data.db.DownloadEntity

class DownloadRepository(private val context: Context) {
    private val downloadDao: DownloadDao = AppDatabase.getInstance(context).downloadDao()

    fun getAllDownloads(): Flow<List<DownloadEntity>> = downloadDao.getAllDownloads()

    fun getActiveDownloads(): Flow<List<DownloadEntity>> = downloadDao.getActiveDownloads()

    fun getCompletedDownloads(): Flow<List<DownloadEntity>> = downloadDao.getCompletedDownloads()

    suspend fun getDownloadById(id: Long): DownloadEntity? = downloadDao.getDownloadById(id)

    suspend fun insertDownload(download: DownloadEntity): Long = downloadDao.insertDownload(download)

    suspend fun updateDownload(download: DownloadEntity) = downloadDao.updateDownload(download)

    suspend fun deleteDownload(download: DownloadEntity) = downloadDao.deleteDownload(download)

    suspend fun deleteAll() = downloadDao.deleteAll()

    suspend fun updateProgress(id: Long, progress: Int, status: String) =
        downloadDao.updateProgress(id, progress, status)

    suspend fun markFailed(id: Long, error: String) = downloadDao.markFailed(id, error)

    suspend fun markCompleted(id: Long, filePath: String, fileSize: Long) =
        downloadDao.markCompleted(id, filePath, fileSize)
}
