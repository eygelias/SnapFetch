package com.snapfetch.app.utils

import android.content.Context
import android.util.Log
import com.yausername.ffmpeg.FFmpeg
import com.yausername.youtubedl_android.YoutubeDL

object YoutubeDLHelper {
    private const val TAG = "YoutubeDLHelper"

    @Volatile
    private var isInitialized = false

    fun init(context: Context) {
        if (isInitialized) return
        synchronized(this) {
            if (isInitialized) return
            try {
                Log.d(TAG, "Initializing YoutubeDL and FFmpeg...")
                YoutubeDL.getInstance().init(context.applicationContext)
                FFmpeg.getInstance().init(context.applicationContext)
                isInitialized = true
                Log.d(TAG, "✅ YoutubeDL and FFmpeg initialized successfully")
            } catch (e: Exception) {
                Log.e(TAG, "❌ Failed to initialize YoutubeDL / FFmpeg", e)
            }
        }
    }

    fun isReady(): Boolean = isInitialized
}
