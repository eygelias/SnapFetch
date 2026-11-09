package com.snapfetch.app.utils

import com.snapfetch.app.domain.model.SocialPlatform
import java.util.regex.Pattern

object UrlExtractor {

    private val URL_REGEX: Pattern = Pattern.compile(
        "https?://(?:www\\.)?[-a-zA-Z0-9@:%._+~#=]{1,256}\\.[a-zA-Z0-9()]{1,6}\\b(?:[-a-zA-Z0-9()@:%_+.~#?&/=]*)"
    )

    /**
     * Finds and cleans the first valid social media URL from shared plain text.
     */
    fun findSocialUrl(rawText: String?): String? {
        if (rawText.isNullOrBlank()) return null

        val matcher = URL_REGEX.matcher(rawText)
        while (matcher.find()) {
            val candidate = matcher.group()
            val platform = SocialPlatform.fromUrl(candidate)
            if (platform != SocialPlatform.UNKNOWN) {
                return cleanTrackingParams(candidate)
            }
        }

        // If no recognizable social platform, return the first valid URL as fallback
        val matcherFallback = URL_REGEX.matcher(rawText)
        if (matcherFallback.find()) {
            return matcherFallback.group()
        }

        return null
    }

    /**
     * Removes common tracking and analytics query parameters.
     */
    fun cleanTrackingParams(url: String): String {
        return try {
            val uri = java.net.URI(url)
            val query = uri.query ?: return url

            val trackingKeys = setOf(
                "utm_source", "utm_medium", "utm_campaign", "utm_term",
                "utm_content", "igsh", "si", "feature", "share_id", "fbclid"
            )

            val cleanedQuery = query.split("&")
                .filter { param ->
                    val key = param.substringBefore("=").lowercase()
                    key !in trackingKeys
                }
                .joinToString("&")

            val newUri = java.net.URI(
                uri.scheme,
                uri.authority,
                uri.path,
                if (cleanedQuery.isNotBlank()) cleanedQuery else null,
                null
            )
            newUri.toString()
        } catch (_: Exception) {
            url
        }
    }
}
