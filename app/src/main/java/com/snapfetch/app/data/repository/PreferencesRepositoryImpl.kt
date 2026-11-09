package com.snapfetch.app.data.repository

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.snapfetch.app.domain.model.DownloadMode
import com.snapfetch.app.domain.model.DownloadQuality
import com.snapfetch.app.domain.model.UserPreferences
import com.snapfetch.app.domain.repository.PreferencesRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

private val Context.dataStore: DataStore<Preferences> by preferencesDataStore(name = "snapfetch_preferences")

class PreferencesRepositoryImpl(
    private val context: Context
) : PreferencesRepository {

    private object PreferencesKeys {
        val DOWNLOAD_MODE = stringPreferencesKey("snapfetch_download_mode")
        val PREFERRED_QUALITY = stringPreferencesKey("snapfetch_preferred_quality")
        val REMEMBER_PREFERENCE = booleanPreferencesKey("snapfetch_remember_preference")
    }

    override val preferencesFlow: Flow<UserPreferences> = context.dataStore.data.map { preferences ->
        val modeStr = preferences[PreferencesKeys.DOWNLOAD_MODE] ?: DownloadMode.FAST.name
        val qualityStr = preferences[PreferencesKeys.PREFERRED_QUALITY] ?: DownloadQuality.FHD_1080P.id
        val remember = preferences[PreferencesKeys.REMEMBER_PREFERENCE] ?: true

        UserPreferences(
            downloadMode = DownloadMode.fromName(modeStr),
            preferredQuality = DownloadQuality.fromId(qualityStr),
            rememberPreference = remember
        )
    }

    override suspend fun updateDownloadMode(mode: DownloadMode) {
        context.dataStore.edit { preferences ->
            preferences[PreferencesKeys.DOWNLOAD_MODE] = mode.name
        }
    }

    override suspend fun updatePreferredQuality(quality: DownloadQuality) {
        context.dataStore.edit { preferences ->
            preferences[PreferencesKeys.PREFERRED_QUALITY] = quality.id
        }
    }

    override suspend fun updateRememberPreference(remember: Boolean) {
        context.dataStore.edit { preferences ->
            preferences[PreferencesKeys.REMEMBER_PREFERENCE] = remember
        }
    }
}
