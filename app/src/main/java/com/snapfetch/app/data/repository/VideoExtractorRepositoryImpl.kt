package com.snapfetch.app.data.repository

import android.content.Context
import android.util.Log
import com.snapfetch.app.SnapFetchApplication
import com.snapfetch.app.domain.model.DownloadQuality
import com.snapfetch.app.domain.model.SocialPlatform
import com.snapfetch.app.domain.model.VideoMetadata
import com.snapfetch.app.domain.repository.VideoExtractorRepository
import com.snapfetch.app.utils.YoutubeDLHelper
import com.yausername.youtubedl.VideoInfo
import com.yausername.youtubedl.YoutubeDL
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.net.URLEncoder
import java.util.concurrent.TimeUnit

/**
 * Robust implementation combining local yt-dlp native extraction (via youtubedl-android)
 * with lightweight web oEmbed fallback.
 */
class VideoExtractorRepositoryImpl(
    private val context: Context = SnapFetchApplication.instance,
    private val httpClient: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS)
        .followRedirects(true)
        .build()
) : VideoExtractorRepository {

    companion object {
        private const val TAG = "VideoExtractor"
        private const val DEFAULT_THUMBNAIL =
            "https://images.unsplash.com/photo-1542051841857-5f90071e7989?w=600&auto=format&fit=crop&q=80"
    }

    override suspend fun extractMetadata(url: String): Result<VideoMetadata> =
        withContext(Dispatchers.IO) {
            val platform = SocialPlatform.fromUrl(url)
            Log.d(TAG, "Resolving metadata for $platform → $url")

            // Ensure YoutubeDL is initialized
            YoutubeDLHelper.init(context)

            // 1. Try local yt-dlp engine first
            if (YoutubeDLHelper.isReady()) {
                try {
                    val info: VideoInfo = YoutubeDL.getInstance().getInfo(url)
                    val durationSec = info.duration.coerceAtLeast(0)
                    val minutes = durationSec / 60
                    val seconds = durationSec % 60
                    val durationFormatted = String.format("%02d:%02d", minutes, seconds)

                    val author = info.uploader ?: "Creador"
                    val handle = if (author.startsWith("@")) author else "@$author"
                    val title = info.title?.takeIf { it.isNotBlank() } ?: "Video de ${platform.displayName}"
                    val thumbnail = info.thumbnail?.takeIf { it.isNotBlank() } ?: DEFAULT_THUMBNAIL

                    Log.d(TAG, "✅ Local yt-dlp metadata: '$title' by $handle ($durationFormatted)")

                    return@withContext Result.success(
                        VideoMetadata(
                            originalUrl = url,
                            title = title,
                            authorName = author,
                            authorHandle = handle,
                            durationFormatted = durationFormatted,
                            durationSeconds = durationSec,
                            thumbnailUrl = thumbnail,
                            platform = platform,
                            audioTitle = info.title,
                            viewCountFormatted = null,
                            directStreamUrl = url,
                            directAudioUrl = url
                        )
                    )
                } catch (e: Exception) {
                    Log.w(TAG, "yt-dlp getInfo threw exception: ${e.message}, falling back to oEmbed")
                }
            }

            // 2. Fallback: lightweight oEmbed
            val oembed = fetchOEmbedMetadata(url, platform)
            if (oembed != null) {
                Log.d(TAG, "✅ oEmbed metadata resolved: '${oembed.first}'")
                return@withContext Result.success(
                    VideoMetadata(
                        originalUrl = url,
                        title = oembed.first,
                        authorName = oembed.second,
                        authorHandle = oembed.third,
                        durationFormatted = "—",
                        durationSeconds = 0,
                        thumbnailUrl = oembed.fourth,
                        platform = platform,
                        audioTitle = null,
                        viewCountFormatted = null,
                        directStreamUrl = url,
                        directAudioUrl = url
                    )
                )
            }

            // 3. Fallback: generic metadata with valid target URL
            Log.d(TAG, "Using generic metadata fallback for $url")
            Result.success(
                VideoMetadata(
                    originalUrl = url,
                    title = "Video de ${platform.displayName}",
                    authorName = platform.displayName,
                    authorHandle = "@${platform.shortName.lowercase()}",
                    durationFormatted = "—",
                    durationSeconds = 0,
                    thumbnailUrl = DEFAULT_THUMBNAIL,
                    platform = platform,
                    audioTitle = null,
                    viewCountFormatted = null,
                    directStreamUrl = url,
                    directAudioUrl = url
                )
            )
        }

    override suspend fun resolveStreamUrl(
        metadata: VideoMetadata,
        quality: DownloadQuality
    ): Result<String> = withContext(Dispatchers.IO) {
        if (metadata.originalUrl.isNotBlank()) {
            Result.success(metadata.originalUrl)
        } else {
            Result.failure(IllegalStateException("URL vacía"))
        }
    }

    // ─── oEmbed helpers ─────────────────────────────────────────────

    private fun fetchOEmbedMetadata(
        url: String,
        platform: SocialPlatform
    ): Quad<String, String, String, String>? {
        return try {
            when (platform) {
                SocialPlatform.TIKTOK -> fetchTikTokOEmbed(url)
                SocialPlatform.YOUTUBE -> fetchYouTubeOEmbed(url)
                else -> null
            }
        } catch (_: Exception) {
            null
        }
    }

    private fun fetchTikTokOEmbed(url: String): Quad<String, String, String, String>? {
        val encoded = URLEncoder.encode(url, "UTF-8")
        val req = Request.Builder()
            .url("https://www.tiktok.com/oembed?url=$encoded")
            .header("User-Agent", "Mozilla/5.0")
            .build()
        httpClient.newCall(req).execute().use { resp ->
            if (!resp.isSuccessful) return null
            val json = JSONObject(resp.body?.string() ?: return null)
            return Quad(
                json.optString("title", "Video de TikTok"),
                json.optString("author_name", "TikTok Creator"),
                "@" + json.optString("author_unique_id", "unknown"),
                json.optString("thumbnail_url", DEFAULT_THUMBNAIL)
            )
        }
    }

    private fun fetchYouTubeOEmbed(url: String): Quad<String, String, String, String>? {
        val encoded = URLEncoder.encode(url, "UTF-8")
        val req = Request.Builder()
            .url("https://www.youtube.com/oembed?url=$encoded&format=json")
            .header("User-Agent", "Mozilla/5.0")
            .build()
        httpClient.newCall(req).execute().use { resp ->
            if (!resp.isSuccessful) return null
            val json = JSONObject(resp.body?.string() ?: return null)
            val authorName = json.optString("author_name", "YouTube Creator")
            return Quad(
                json.optString("title", "YouTube Short"),
                authorName,
                "@" + authorName.lowercase().replace(" ", "_"),
                json.optString("thumbnail_url", DEFAULT_THUMBNAIL)
            )
        }
    }

    private data class Quad<A, B, C, D>(
        val first: A,
        val second: B,
        val third: C,
        val fourth: D
    )
}
