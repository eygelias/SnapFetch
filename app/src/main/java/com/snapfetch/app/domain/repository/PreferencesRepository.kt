package com.snapfetch.app.domain.repository

import com.snapfetch.app.domain.model.DownloadMode
import com.snapfetch.app.domain.model.DownloadQuality
import com.snapfetch.app.domain.model.UserPreferences
import kotlinx.coroutines.flow.Flow

interface PreferencesRepository {
    val preferencesFlow: Flow<UserPreferences>

    suspend fun updateDownloadMode(mode: DownloadMode)
    suspend fun updatePreferredQuality(quality: DownloadQuality)
    suspend fun updateRememberPreference(remember: Boolean)
}
