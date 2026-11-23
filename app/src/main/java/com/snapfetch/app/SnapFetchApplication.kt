package com.snapfetch.app

import android.app.Application
import com.snapfetch.app.data.repository.PreferencesRepositoryImpl
import com.snapfetch.app.data.repository.VideoExtractorRepositoryImpl
import com.snapfetch.app.data.worker.DownloadNotificationManager
import com.snapfetch.app.domain.repository.PreferencesRepository
import com.snapfetch.app.domain.repository.VideoExtractorRepository
import com.snapfetch.app.utils.YoutubeDLHelper
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

/**
 * SnapFetchApplication: Initializes application-wide services,
 * notification channels, and repository singletons.
 */
class SnapFetchApplication : Application() {

    lateinit var preferencesRepository: PreferencesRepository
        private set

    lateinit var videoExtractorRepository: VideoExtractorRepository
        private set

    override fun onCreate() {
        super.onCreate()
        instance = this

        // Initialize Notification Channels
        DownloadNotificationManager.createNotificationChannel(this)

        // Initialize Repositories
        preferencesRepository = PreferencesRepositoryImpl(this)
        videoExtractorRepository = VideoExtractorRepositoryImpl(this)

        // Asynchronously initialize local YoutubeDL engine
        CoroutineScope(Dispatchers.IO).launch {
            YoutubeDLHelper.init(this@SnapFetchApplication)
        }
    }

    companion object {
        lateinit var instance: SnapFetchApplication
            private set
    }
}
