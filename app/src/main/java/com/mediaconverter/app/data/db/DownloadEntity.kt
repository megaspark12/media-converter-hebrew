package com.mediaconverter.app.data.db

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "downloads")
data class DownloadEntity(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,
    val url: String,
    val title: String = "",
    val platform: String = "unknown", // "youtube", "facebook", "other"
    val format: String = "mp4", // "mp4" or "mp3"
    val quality: String = "best",
    val progress: Int = 0,
    val status: String = "pending", // "pending", "downloading", "completed", "failed"
    val filePath: String = "",
    val fileSize: Long = 0,
    val thumbnailUrl: String = "",
    val errorMessage: String = "",
    val createdAt: Long = System.currentTimeMillis(),
    val completedAt: Long = 0,
)
