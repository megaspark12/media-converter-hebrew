package com.mediaconverter.app.data

interface MediaInfoProvider {
    suspend fun getVideoInfo(url: NormalizedMediaUrl): Result<VideoInfo>
}
