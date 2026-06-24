package com.mediaconverter.app

import android.app.Application
import android.util.Log
import com.yausername.ffmpeg.FFmpeg
import com.yausername.youtubedl_android.YoutubeDL
import kotlinx.coroutines.DelicateCoroutinesApi
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.launch

class MediaConverterApp : Application() {
    override fun onCreate() {
        super.onCreate()
        
        try {
            FFmpeg.getInstance().init(this)
            YoutubeDL.getInstance().init(this)
            Log.d("MediaConverterApp", "YoutubeDL and FFmpeg initialized successfully")
            
            // Update yt-dlp in the background to prevent 403 Forbidden errors
            @OptIn(DelicateCoroutinesApi::class)
            GlobalScope.launch(Dispatchers.IO) {
                try {
                    YoutubeDL.getInstance().updateYoutubeDL(this@MediaConverterApp, YoutubeDL.UpdateChannel.STABLE)
                    Log.d("MediaConverterApp", "YoutubeDL updated successfully")
                } catch (e: Exception) {
                    Log.e("MediaConverterApp", "Failed to update YoutubeDL", e)
                }
            }
        } catch (e: Exception) {
            Log.e("MediaConverterApp", "Failed to initialize YoutubeDL", e)
        }
    }
}
