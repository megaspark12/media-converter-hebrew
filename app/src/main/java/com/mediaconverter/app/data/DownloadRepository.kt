package com.mediaconverter.app.data

import android.content.Context
import com.mediaconverter.app.data.db.AppDatabase
import com.mediaconverter.app.data.db.DownloadDao
import com.mediaconverter.app.data.db.DownloadEntity

class DownloadRepository(private val context: Context) : DownloadTaskStore {
    private val downloadDao: DownloadDao = AppDatabase.getInstance(context).downloadDao()

    override suspend fun getDownloadById(id: Long): DownloadEntity? = downloadDao.getDownloadById(id)

    override suspend fun updateProgress(id: Long, progress: Int, status: String) =
        downloadDao.updateProgress(id, progress, status)

    override suspend fun markFailed(id: Long, error: String, errorCode: String) =
        downloadDao.markFailed(id, error, errorCode)

    override suspend fun markRetryPending(id: Long, error: String, errorCode: String) =
        downloadDao.markRetryPending(id, error, errorCode)

    override suspend fun markCompleted(id: Long, savedMedia: SavedMedia) =
        downloadDao.markCompleted(id, savedMedia.contentUri, savedMedia.mimeType, savedMedia.sizeBytes)

    suspend fun markCancelled(id: Long) =
        downloadDao.markCancelled(id, ConversionFailure.CANCELLED.code)
}
