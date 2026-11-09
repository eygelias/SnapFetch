package com.snapfetch.app

import android.app.Application
import com.snapfetch.app.data.repository.PreferencesRepositoryImpl
import com.snapfetch.app.data.repository.VideoExtractorRepositoryImpl
import com.snapfetch.app.data.worker.DownloadNotificationManager
import com.snapfetch.app.domain.repository.PreferencesRepository
import com.snapfetch.app.domain.repository.VideoExtractorRepository

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
        videoExtractorRepository = VideoExtractorRepositoryImpl()
    }

    companion object {
        lateinit var instance: SnapFetchApplication
            private set
    }
}
