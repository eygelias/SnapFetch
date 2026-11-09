package com.snapfetch.app.domain.model

enum class DownloadQuality(
    val id: String,
    val label: String,
    val format: String,
    val resolution: String,
    val estimatedSize: String,
    val isAudioOnly: Boolean
) {
    UHD_4K(
        id = "4k",
        label = "4K UHD",
        format = "MP4",
        resolution = "2160p",
        estimatedSize = "~85 MB",
        isAudioOnly = false
    ),
    FHD_1080P(
        id = "1080p",
        label = "FHD 1080p sin marca",
        format = "MP4",
        resolution = "1080p",
        estimatedSize = "~42 MB",
        isAudioOnly = false
    ),
    HD_720P(
        id = "720p",
        label = "HD 720p rápido",
        format = "MP4",
        resolution = "720p",
        estimatedSize = "~24 MB",
        isAudioOnly = false
    ),
    AUDIO_MP3(
        id = "mp3",
        label = "Audio MP3 (320kbps)",
        format = "MP3",
        resolution = "Audio",
        estimatedSize = "~5 MB",
        isAudioOnly = true
    );

    companion object {
        fun fromId(id: String?): DownloadQuality {
            return entries.firstOrNull { it.id.equals(id, ignoreCase = true) } ?: FHD_1080P
        }
    }
}
