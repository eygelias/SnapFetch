package com.snapfetch.app.data.repository

import android.content.Context
import android.util.Log
import com.snapfetch.app.SnapFetchApplication
import com.snapfetch.app.domain.model.DownloadQuality
import com.snapfetch.app.domain.model.SocialPlatform
import com.snapfetch.app.domain.model.VideoMetadata
import com.snapfetch.app.domain.repository.VideoExtractorRepository
import com.snapfetch.app.utils.YoutubeDLHelper
import com.yausername.youtubedl_android.YoutubeDL
import com.yausername.youtubedl_android.mapper.VideoInfo
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject
import java.net.URLEncoder
import java.util.concurrent.TimeUnit
import java.util.regex.Pattern

/**
 * Robust extraction engine combining:
 * 1. Dedicated zero-watermark TikTok extractor (via TikWM API).
 * 2. High-speed Googlebot HTML renderer for Instagram (bypasses login wall, extracts direct fbcdn MP4).
 * 3. Local yt-dlp native extraction (Facebook, YouTube Shorts, X/Twitter).
 * 4. Lightweight oEmbed fallbacks.
 */
class VideoExtractorRepositoryImpl(
    private val context: Context = SnapFetchApplication.instance,
    private val httpClient: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(20, TimeUnit.SECONDS)
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

            // 1. TikTok: dedicated watermark-free extractor
            if (platform == SocialPlatform.TIKTOK) {
                val tiktokMeta = extractTikTok(url)
                if (tiktokMeta != null) {
                    Log.d(TAG, "✅ TikTok metadata extracted: '${tiktokMeta.title}' by ${tiktokMeta.authorHandle}")
                    return@withContext Result.success(tiktokMeta)
                }
            }

            // 2. Instagram: Googlebot rendered page extractor (direct fbcdn video CDN URL)
            if (platform == SocialPlatform.INSTAGRAM) {
                val instaMeta = extractInstagram(url)
                if (instaMeta != null) {
                    Log.d(TAG, "✅ Instagram metadata extracted: '${instaMeta.title}' by ${instaMeta.authorHandle}")
                    return@withContext Result.success(instaMeta)
                }
            }

            // 3. Local yt-dlp engine for Facebook, YouTube Shorts, X/Twitter, etc.
            YoutubeDLHelper.init(context)
            if (YoutubeDLHelper.isReady()) {
                val ytdlMeta = extractWithYoutubeDL(url, platform)
                if (ytdlMeta != null) {
                    Log.d(TAG, "✅ Local yt-dlp metadata: '${ytdlMeta.title}' by ${ytdlMeta.authorHandle}")
                    return@withContext Result.success(ytdlMeta)
                }
            }

            // 4. Lightweight oEmbed fallback
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

            // 5. Generic fallback with original URL
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
        if (quality.isAudioOnly && !metadata.directAudioUrl.isNullOrBlank()) {
            Result.success(metadata.directAudioUrl)
        } else if (metadata.directStreamUrl.isNotBlank()) {
            Result.success(metadata.directStreamUrl)
        } else if (metadata.originalUrl.isNotBlank()) {
            Result.success(metadata.originalUrl)
        } else {
            Result.failure(IllegalStateException("URL vacía"))
        }
    }

    // ─── TikTok Extractor ───────────────────────────────────────────

    fun extractTikTok(url: String): VideoMetadata? {
        return try {
            val encoded = URLEncoder.encode(url, "UTF-8")
            val req = Request.Builder()
                .url("https://www.tikwm.com/api/?url=$encoded")
                .header("User-Agent", "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36")
                .build()

            httpClient.newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) return null
                val bodyStr = resp.body?.string() ?: return null
                val json = JSONObject(bodyStr)
                if (json.optInt("code", -1) != 0) return null
                val data = json.optJSONObject("data") ?: return null

                val title = data.optString("title").ifBlank { "Video de TikTok" }
                val author = data.optJSONObject("author")
                val authorName = author?.optString("nickname")?.ifBlank { "Creador de TikTok" } ?: "Creador de TikTok"
                val uniqueId = author?.optString("unique_id")?.ifBlank { "tiktok" } ?: "tiktok"
                val authorHandle = if (uniqueId.startsWith("@")) uniqueId else "@$uniqueId"
                val durationSec = data.optInt("duration", 0)
                val durationStr = String.format("%02d:%02d", durationSec / 60, durationSec % 60)
                val thumbnail = data.optString("cover").ifBlank {
                    data.optString("origin_cover").ifBlank { DEFAULT_THUMBNAIL }
                }
                val playUrl = data.optString("play").ifBlank {
                    data.optString("wmplay").ifBlank { url }
                }
                val musicUrl = data.optString("music").ifBlank { null }

                VideoMetadata(
                    originalUrl = url,
                    title = title,
                    authorName = authorName,
                    authorHandle = authorHandle,
                    durationFormatted = durationStr,
                    durationSeconds = durationSec,
                    thumbnailUrl = thumbnail,
                    platform = SocialPlatform.TIKTOK,
                    audioTitle = data.optJSONObject("music_info")?.optString("title"),
                    viewCountFormatted = data.optInt("play_count", 0).takeIf { it > 0 }?.let { "$it vistas" },
                    directStreamUrl = playUrl,
                    directAudioUrl = musicUrl
                )
            }
        } catch (e: Exception) {
            Log.w(TAG, "extractTikTok error: ${e.message}")
            null
        }
    }

    // ─── Instagram Extractor ────────────────────────────────────────

    fun extractInstagram(url: String): VideoMetadata? {
        return try {
            val shortcodePattern = Pattern.compile("(?:instagram\\.com|instagr\\.am)/(?:reel|reels|p|tv)/([A-Za-z0-9_-]+)")
            val matcher = shortcodePattern.matcher(url)
            val shortcode = if (matcher.find()) matcher.group(1) else null ?: return null

            val targetUrl = "https://www.instagram.com/reel/$shortcode/"
            val req = Request.Builder()
                .url(targetUrl)
                .header("User-Agent", "Googlebot/2.1 (+http://www.google.com/bot.html)")
                .header("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8")
                .header("Accept-Language", "es-ES,es;q=0.9,en;q=0.8")
                .build()

            httpClient.newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) return null
                val html = resp.body?.string() ?: return null

                // 1. Extract direct video MP4 URL
                val vvMatcher = Pattern.compile("\"video_versions\"\\s*:\\s*(\\[[^\\]]+\\])").matcher(html)
                val directStreamUrl = if (vvMatcher.find()) {
                    val array = JSONArray(vvMatcher.group(1))
                    if (array.length() > 0) array.getJSONObject(0).optString("url") else null
                } else {
                    val ogvMatcher = Pattern.compile("<meta property=\"og:video(?::secure_url)?\" content=\"([^\"]+)\"").matcher(html)
                    if (ogvMatcher.find()) ogvMatcher.group(1).replace("&amp;", "&") else null
                } ?: return null

                // 2. Extract caption / title
                val capMatcher = Pattern.compile("\"caption\"\\s*:\\s*\\{[^}]*\"text\"\\s*:\\s*\"([^\"]+)\"").matcher(html)
                val rawCaption = if (capMatcher.find()) capMatcher.group(1) else "Video de Instagram"
                val title = unescapeUnicode(rawCaption).take(120).trim()

                // 3. Extract author / username
                val ownerMatcher = Pattern.compile("\"(?:owner|user)\"\\s*:\\s*\\{[^}]*\"username\"\\s*:\\s*\"([^\"]+)\"").matcher(html)
                val username = if (ownerMatcher.find()) ownerMatcher.group(1) else "instagram"
                val authorHandle = if (username.startsWith("@")) username else "@$username"

                // 4. Extract thumbnail
                val ogImgMatcher = Pattern.compile("<meta property=\"og:image\" content=\"([^\"]+)\"").matcher(html)
                val thumbnail = if (ogImgMatcher.find()) {
                    ogImgMatcher.group(1).replace("&amp;", "&")
                } else {
                    val dispMatcher = Pattern.compile("\"display_url\"\\s*:\\s*\"([^\"]+)\"").matcher(html)
                    if (dispMatcher.find()) dispMatcher.group(1).replace("\\u0026", "&").replace("\\/", "/") else DEFAULT_THUMBNAIL
                }

                VideoMetadata(
                    originalUrl = url,
                    title = title.ifBlank { "Video de Instagram" },
                    authorName = username,
                    authorHandle = authorHandle,
                    durationFormatted = "—",
                    durationSeconds = 0,
                    thumbnailUrl = thumbnail,
                    platform = SocialPlatform.INSTAGRAM,
                    audioTitle = null,
                    viewCountFormatted = null,
                    directStreamUrl = directStreamUrl,
                    directAudioUrl = null
                )
            }
        } catch (e: Exception) {
            Log.w(TAG, "extractInstagram error: ${e.message}")
            null
        }
    }

    private fun extractWithYoutubeDL(url: String, platform: SocialPlatform): VideoMetadata? {
        return try {
            val info: VideoInfo = YoutubeDL.getInstance().getInfo(url)
            val durationSec = info.duration.coerceAtLeast(0)
            val minutes = durationSec / 60
            val seconds = durationSec % 60
            val durationFormatted = String.format("%02d:%02d", minutes, seconds)

            val author = info.uploader ?: "Creador"
            val handle = if (author.startsWith("@")) author else "@$author"
            val title = if (!info.title.isNullOrBlank()) info.title!! else "Video de ${platform.displayName}"
            val thumbnail = if (!info.thumbnail.isNullOrBlank()) info.thumbnail!! else DEFAULT_THUMBNAIL

            // Direct URL from yt-dlp if available
            val directUrl = info.url ?: url

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
                directStreamUrl = directUrl,
                directAudioUrl = directUrl
            )
        } catch (e: Exception) {
            Log.w(TAG, "yt-dlp getInfo failed: ${e.message}")
            null
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

    private fun unescapeUnicode(input: String): String {
        val sb = StringBuilder()
        var i = 0
        while (i < input.length) {
            val c = input[i]
            if (c == '\\' && i + 1 < input.length && input[i + 1] == 'u' && i + 5 < input.length) {
                try {
                    val code = input.substring(i + 2, i + 6).toInt(16)
                    sb.append(code.toChar())
                    i += 6
                    continue
                } catch (_: Exception) {}
            }
            sb.append(c)
            i++
        }
        return sb.toString()
    }

    private data class Quad<A, B, C, D>(
        val first: A,
        val second: B,
        val third: C,
        val fourth: D
    )
}
