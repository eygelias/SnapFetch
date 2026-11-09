package com.snapfetch.app.domain.model

data class VideoMetadata(
    val originalUrl: String,
    val title: String,
    val authorName: String,
    val authorHandle: String,
    val durationFormatted: String = "01:24",
    val durationSeconds: Int = 84,
    val thumbnailUrl: String,
    val platform: SocialPlatform,
    val audioTitle: String? = null,
    val viewCountFormatted: String? = null,
    val defaultQuality: DownloadQuality = DownloadQuality.FHD_1080P,
    val directStreamUrl: String,
    val directAudioUrl: String? = null
)
