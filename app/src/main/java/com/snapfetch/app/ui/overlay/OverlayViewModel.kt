package com.snapfetch.app.ui.overlay

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.work.Constraints
import androidx.work.Data
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import com.snapfetch.app.SnapFetchApplication
import com.snapfetch.app.data.worker.DownloadWorker
import com.snapfetch.app.domain.model.DownloadMode
import com.snapfetch.app.domain.model.DownloadQuality
import com.snapfetch.app.domain.model.UserPreferences
import com.snapfetch.app.domain.model.VideoMetadata
import com.snapfetch.app.domain.repository.PreferencesRepository
import com.snapfetch.app.domain.repository.VideoExtractorRepository
import com.snapfetch.app.utils.UrlExtractor
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

sealed interface OverlayUiState {
    data object Loading : OverlayUiState
    data class Success(val metadata: VideoMetadata) : OverlayUiState
    data class Error(val message: String) : OverlayUiState
}

class OverlayViewModel(
    private val videoExtractorRepository: VideoExtractorRepository,
    private val preferencesRepository: PreferencesRepository
) : ViewModel() {

    private val _uiState = MutableStateFlow<OverlayUiState>(OverlayUiState.Loading)
    val uiState: StateFlow<OverlayUiState> = _uiState.asStateFlow()

    val userPreferences: StateFlow<UserPreferences> = preferencesRepository.preferencesFlow
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5000),
            initialValue = UserPreferences()
        )

    private val _selectedQuality = MutableStateFlow(DownloadQuality.FHD_1080P)
    val selectedQuality: StateFlow<DownloadQuality> = _selectedQuality.asStateFlow()

    private val _isPreferencesExpanded = MutableStateFlow(false)
    val isPreferencesExpanded: StateFlow<Boolean> = _isPreferencesExpanded.asStateFlow()

    fun processSharedContent(rawText: String?) {
        val extractedUrl = UrlExtractor.findSocialUrl(rawText)
        if (extractedUrl.isNullOrBlank()) {
            _uiState.value = OverlayUiState.Error("No se detectó ningún enlace de video compatible en el texto compartido.")
            return
        }

        viewModelScope.launch {
            _uiState.value = OverlayUiState.Loading
            videoExtractorRepository.extractMetadata(extractedUrl)
                .onSuccess { metadata ->
                    _uiState.value = OverlayUiState.Success(metadata)
                    // Set default selected quality according to user preferences
                    _selectedQuality.value = userPreferences.value.preferredQuality
                }
                .onFailure { error ->
                    _uiState.value = OverlayUiState.Error(error.localizedMessage ?: "Error al procesar el enlace.")
                }
        }
    }

    fun togglePreferencesExpanded() {
        _isPreferencesExpanded.value = !_isPreferencesExpanded.value
    }

    fun selectDownloadMode(mode: DownloadMode) {
        viewModelScope.launch {
            if (userPreferences.value.rememberPreference) {
                preferencesRepository.updateDownloadMode(mode)
            }
        }
    }

    fun selectQuality(quality: DownloadQuality) {
        _selectedQuality.value = quality
        viewModelScope.launch {
            if (userPreferences.value.rememberPreference) {
                preferencesRepository.updatePreferredQuality(quality)
            }
        }
    }

    fun toggleRememberPreference(remember: Boolean) {
        viewModelScope.launch {
            preferencesRepository.updateRememberPreference(remember)
        }
    }

    /**
     * Enqueues the background download task in WorkManager and returns immediately.
     */
    fun startDownload(context: Context, metadata: VideoMetadata, quality: DownloadQuality) {
        val constraints = Constraints.Builder()
            .setRequiredNetworkType(NetworkType.CONNECTED)
            .build()

        val inputData = Data.Builder()
            .putString(DownloadWorker.KEY_STREAM_URL, metadata.directStreamUrl)
            .putString(DownloadWorker.KEY_TITLE, metadata.title)
            .putString(DownloadWorker.KEY_AUTHOR, metadata.authorHandle)
            .putString(DownloadWorker.KEY_PLATFORM, metadata.platform.displayName)
            .putString(DownloadWorker.KEY_QUALITY_ID, quality.id)
            .build()

        val downloadWorkRequest = OneTimeWorkRequestBuilder<DownloadWorker>()
            .setConstraints(constraints)
            .setInputData(inputData)
            .addTag("snapfetch_download")
            .build()

        WorkManager.getInstance(context).enqueue(downloadWorkRequest)
    }

    companion object {
        val Factory: ViewModelProvider.Factory = object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST")
            override fun <T : ViewModel> create(modelClass: Class<T>): T {
                val app = SnapFetchApplication.instance
                return OverlayViewModel(
                    videoExtractorRepository = app.videoExtractorRepository,
                    preferencesRepository = app.preferencesRepository
                ) as T
            }
        }
    }
}
