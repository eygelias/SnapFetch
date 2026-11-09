package com.snapfetch.app.domain.model

data class UserPreferences(
    val downloadMode: DownloadMode = DownloadMode.FAST,
    val preferredQuality: DownloadQuality = DownloadQuality.FHD_1080P,
    val rememberPreference: Boolean = true,
    val customDownloadFolder: String? = null
)
