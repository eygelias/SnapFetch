package com.snapfetch.app.domain.model

enum class SocialPlatform(
    val displayName: String,
    val shortName: String,
    val domainKeywords: List<String>
) {
    TIKTOK("TikTok", "TikTok", listOf("tiktok.com", "douyin.com")),
    INSTAGRAM("Instagram", "Instagram", listOf("instagram.com", "instagr.am")),
    YOUTUBE("YouTube Shorts", "YouTube", listOf("youtube.com", "youtu.be")),
    TWITTER_X("X (Twitter)", "X", listOf("twitter.com", "x.com")),
    UNKNOWN("Enlace Web", "Web", emptyList());

    companion object {
        fun fromUrl(url: String): SocialPlatform {
            val lower = url.lowercase()
            return entries.firstOrNull { platform ->
                platform.domainKeywords.any { lower.contains(it) }
            } ?: UNKNOWN
        }
    }
}
