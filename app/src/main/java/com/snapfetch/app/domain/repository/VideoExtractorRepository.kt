package com.snapfetch.app.domain.repository

import com.snapfetch.app.domain.model.DownloadQuality
import com.snapfetch.app.domain.model.VideoMetadata

/**
 * Decoupled repository interface for extracting metadata and stream URLs
 * from social network links (TikTok, Instagram, YouTube, X).
 */
interface VideoExtractorRepository {
    /**
     * Resolves metadata (title, author, thumbnail, duration, stream links)
     * from a given social network URL.
     */
    suspend fun extractMetadata(url: String): Result<VideoMetadata>

    /**
     * Resolves the direct media download link for a given quality.
     */
    suspend fun resolveStreamUrl(
        metadata: VideoMetadata,
        quality: DownloadQuality
    ): Result<String>
}
