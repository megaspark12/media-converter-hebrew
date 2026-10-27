package com.mediaconverter.app.data.db

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "downloads")
data class DownloadEntity(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,
    val url: String,
    val title: String = "",
    val platform: String = "unknown", // SupportedPlatform.value: "youtube", "facebook", "instagram", "tiktok", "x"
    val format: String = "mp4", // "mp4" or "mp3"
    val quality: String = "best",
    val progress: Int = 0,
    val status: String = "pending", // "pending", "downloading", "completed", "failed"
    val filePath: String = "",
    val outputUri: String = "",
    val mimeType: String = "",
    val fileSize: Long = 0,
    val thumbnailUrl: String = "",
    val errorMessage: String = "",
    val errorCode: String = "",
    val ytDlpVersion: String = "",
    val retryCount: Int = 0,
    val playlistIndex: Int = 0, // 1-based item of a carousel or multi-video post; 0 = the link's single video
    val createdAt: Long = System.currentTimeMillis(),
    val completedAt: Long = 0,
)
