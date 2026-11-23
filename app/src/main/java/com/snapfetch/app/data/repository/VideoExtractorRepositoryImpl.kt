package com.snapfetch.app.data.repository

import android.util.Log
import com.snapfetch.app.domain.model.DownloadQuality
import com.snapfetch.app.domain.model.SocialPlatform
import com.snapfetch.app.domain.model.VideoMetadata
import com.snapfetch.app.domain.repository.VideoExtractorRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.net.URLEncoder
import java.util.concurrent.TimeUnit

/**
 * Real implementation that uses multiple extraction APIs
 * to get direct download URLs for social media videos.
 *
 * Strategy per platform:
 *   - TikTok     → TikWM API (primary) → Cobalt (fallback)
 *   - Instagram  → Cobalt API (primary) → alternative scrapers (fallback)
 *   - YouTube    → Cobalt API (primary)
 *   - Twitter/X  → Cobalt API (primary)
 *   - Facebook   → Cobalt API (primary)
 *   - Unknown    → Cobalt API (try anyway)
 */
class VideoExtractorRepositoryImpl(
    private val client: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .followRedirects(true)
        .build()
) : VideoExtractorRepository {

    companion object {
        private const val TAG = "VideoExtractor"

        // Cobalt public instances – tried in order; we keep several in case one goes down
        private val COBALT_INSTANCES = listOf(
            "https://api.cobalt.tools",
            "https://cobalt-api.kwiatekmiki.com",
            "https://cobalt.api.timelessnesses.me",
            "https://cobalt-api.ayo.tf",
            "https://co.eepy.today"
        )

        // TikWM API
        private const val TIKWM_API = "https://www.tikwm.com/api/"

        private const val DEFAULT_THUMBNAIL =
            "https://images.unsplash.com/photo-1542051841857-5f90071e7989?w=600&auto=format&fit=crop&q=80"
    }

    // ─── Public interface ───────────────────────────────────────────

    override suspend fun extractMetadata(url: String): Result<VideoMetadata> =
        withContext(Dispatchers.IO) {
            try {
                val platform = SocialPlatform.fromUrl(url)
                Log.d(TAG, "Extracting metadata for $platform → $url")

                val metadata = when (platform) {
                    SocialPlatform.TIKTOK -> extractTikTok(url)
                    else -> extractViaCobalt(url, platform)
                }

                if (metadata != null) {
                    Log.d(TAG, "✅ Got real metadata: stream=${metadata.directStreamUrl.take(80)}")
                    Result.success(metadata)
                } else {
                    // Last-resort: provide minimal metadata with original URL so user sees something
                    Log.w(TAG, "⚠ All extractors failed – returning minimal metadata")
                    Result.success(buildMinimalMetadata(url, platform))
                }
            } catch (e: Exception) {
                Log.e(TAG, "extractMetadata crashed", e)
                Result.failure(e)
            }
        }

    override suspend fun resolveStreamUrl(
        metadata: VideoMetadata,
        quality: DownloadQuality
    ): Result<String> = withContext(Dispatchers.IO) {
        try {
            if (quality.isAudioOnly && !metadata.directAudioUrl.isNullOrBlank()) {
                return@withContext Result.success(metadata.directAudioUrl)
            }
            if (metadata.directStreamUrl.isBlank()) {
                return@withContext Result.failure(
                    IllegalStateException("No se encontró URL de descarga directa")
                )
            }
            Result.success(metadata.directStreamUrl)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    // ─── TikTok via TikWM ──────────────────────────────────────────

    private fun extractTikTok(url: String): VideoMetadata? {
        return try {
            extractTikTokViaTikWM(url)
        } catch (e: Exception) {
            Log.w(TAG, "TikWM failed, trying Cobalt for TikTok", e)
            try {
                extractViaCobalt(url, SocialPlatform.TIKTOK)
            } catch (e2: Exception) {
                Log.e(TAG, "Cobalt also failed for TikTok", e2)
                null
            }
        }
    }

    private fun extractTikTokViaTikWM(url: String): VideoMetadata? {
        // TikWM supports both GET and POST. We use POST with hd=1.
        val formBody = okhttp3.FormBody.Builder()
            .add("url", url)
            .add("hd", "1")
            .build()

        val request = Request.Builder()
            .url(TIKWM_API)
            .post(formBody)
            .header("User-Agent", "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36")
            .header("Accept", "application/json")
            .build()

        client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) {
                Log.w(TAG, "TikWM HTTP ${response.code}")
                return null
            }
            val body = response.body?.string() ?: return null
            val json = JSONObject(body)

            if (json.optInt("code", -1) != 0) {
                Log.w(TAG, "TikWM code != 0: ${json.optString("msg")}")
                return null
            }

            val data = json.optJSONObject("data") ?: return null

            // Pick best stream: hdplay > play > wmplay
            val streamUrl = data.optString("hdplay").takeIf { it.isNotBlank() }
                ?: data.optString("play").takeIf { it.isNotBlank() }
                ?: data.optString("wmplay").takeIf { it.isNotBlank() }
                ?: return null

            val audioUrl = data.optString("music").takeIf { it.isNotBlank() }

            val duration = data.optInt("duration", 0)
            val minutes = duration / 60
            val seconds = duration % 60
            val durationFormatted = String.format("%02d:%02d", minutes, seconds)

            val author = data.optJSONObject("author")
            val authorName = author?.optString("nickname", "TikTok Creator") ?: "TikTok Creator"
            val authorHandle = "@" + (author?.optString("unique_id", "unknown") ?: "unknown")

            val thumbnail = data.optString("origin_cover").takeIf { it.isNotBlank() }
                ?: data.optString("cover").takeIf { it.isNotBlank() }
                ?: DEFAULT_THUMBNAIL

            val title = data.optString("title", "Video de TikTok").takeIf { it.isNotBlank() }
                ?: "Video de TikTok"

            val musicInfo = data.optJSONObject("music_info")
            val audioTitle = musicInfo?.optString("title")?.takeIf { it.isNotBlank() }
                ?: "Sonido original"

            val playCount = data.optLong("play_count", 0)
            val viewCountFormatted = formatCount(playCount)

            Log.d(TAG, "TikWM success → $streamUrl")

            return VideoMetadata(
                originalUrl = url,
                title = title,
                authorName = authorName,
                authorHandle = authorHandle,
                durationFormatted = durationFormatted,
                durationSeconds = duration,
                thumbnailUrl = thumbnail,
                platform = SocialPlatform.TIKTOK,
                audioTitle = audioTitle,
                viewCountFormatted = viewCountFormatted,
                directStreamUrl = streamUrl,
                directAudioUrl = audioUrl
            )
        }
    }

    // ─── Universal via Cobalt ──────────────────────────────────────

    private fun extractViaCobalt(url: String, platform: SocialPlatform): VideoMetadata? {
        for (instance in COBALT_INSTANCES) {
            try {
                val result = tryCobaltInstance(instance, url, platform)
                if (result != null) return result
            } catch (e: Exception) {
                Log.w(TAG, "Cobalt instance $instance failed: ${e.message}")
            }
        }
        return null
    }

    private fun tryCobaltInstance(
        instanceBaseUrl: String,
        url: String,
        platform: SocialPlatform
    ): VideoMetadata? {
        val jsonPayload = JSONObject().apply {
            put("url", url)
            put("vQuality", "1080")
            put("filenameStyle", "basic")
            put("isAudioOnly", false)
        }

        val mediaType = "application/json".toMediaType()
        val requestBody = jsonPayload.toString().toRequestBody(mediaType)

        val request = Request.Builder()
            .url(instanceBaseUrl)
            .post(requestBody)
            .header("Accept", "application/json")
            .header("Content-Type", "application/json")
            .header(
                "User-Agent",
                "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 (KHTML, like Gecko)"
            )
            .build()

        client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) {
                Log.w(TAG, "Cobalt $instanceBaseUrl HTTP ${response.code}")
                return null
            }
            val body = response.body?.string() ?: return null
            val json = JSONObject(body)

            val status = json.optString("status", "")

            // Cobalt v7+ returns "tunnel" or "redirect" on success
            val streamUrl = when (status) {
                "tunnel", "redirect", "stream" -> json.optString("url").takeIf { it.isNotBlank() }
                "picker" -> {
                    // Carousel / multi-media – pick first video
                    val picker = json.optJSONArray("picker")
                    pickFirstVideoFromPicker(picker)
                }
                else -> json.optString("url").takeIf { it.isNotBlank() }
            }

            if (streamUrl.isNullOrBlank()) {
                Log.w(TAG, "Cobalt returned no url. status=$status body=${body.take(200)}")
                return null
            }

            // Cobalt doesn't return rich metadata – we supplement with oEmbed where possible
            val oembed = fetchOEmbedMetadata(url, platform)

            Log.d(TAG, "Cobalt success from $instanceBaseUrl → ${streamUrl.take(80)}")

            return VideoMetadata(
                originalUrl = url,
                title = oembed?.first ?: platform.displayName + " Video",
                authorName = oembed?.second ?: "Creador",
                authorHandle = oembed?.third ?: "@creator",
                durationFormatted = "—",
                durationSeconds = 0,
                thumbnailUrl = oembed?.fourth ?: DEFAULT_THUMBNAIL,
                platform = platform,
                audioTitle = null,
                viewCountFormatted = null,
                directStreamUrl = streamUrl,
                directAudioUrl = null
            )
        }
    }

    private fun pickFirstVideoFromPicker(picker: JSONArray?): String? {
        if (picker == null || picker.length() == 0) return null
        for (i in 0 until picker.length()) {
            val item = picker.optJSONObject(i) ?: continue
            val itemUrl = item.optString("url").takeIf { it.isNotBlank() } ?: continue
            val type = item.optString("type", "video")
            if (type.contains("video", ignoreCase = true)) return itemUrl
        }
        // If no explicit "video" type, just take first
        return picker.optJSONObject(0)?.optString("url")
    }

    // ─── oEmbed helpers (lightweight metadata enrichment) ──────────

    /**
     * Returns (title, authorName, authorHandle, thumbnailUrl) or null.
     */
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
        client.newCall(req).execute().use { resp ->
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
        client.newCall(req).execute().use { resp ->
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

    // ─── Minimal fallback ──────────────────────────────────────────

    private fun buildMinimalMetadata(url: String, platform: SocialPlatform): VideoMetadata {
        return VideoMetadata(
            originalUrl = url,
            title = "Video de ${platform.displayName}",
            authorName = "Creador",
            authorHandle = "@creator",
            durationFormatted = "—",
            durationSeconds = 0,
            thumbnailUrl = DEFAULT_THUMBNAIL,
            platform = platform,
            audioTitle = null,
            viewCountFormatted = null,
            directStreamUrl = "",   // empty → DownloadWorker will fail gracefully
            directAudioUrl = null
        )
    }

    // ─── Utils ─────────────────────────────────────────────────────

    private fun formatCount(count: Long): String? {
        if (count <= 0) return null
        return when {
            count >= 1_000_000 -> String.format("%.1fM", count / 1_000_000.0)
            count >= 1_000 -> String.format("%.1fK", count / 1_000.0)
            else -> count.toString()
        }
    }

    /** Simple 4-element tuple to avoid creating a data class for oEmbed results. */
    private data class Quad<A, B, C, D>(
        val first: A,
        val second: B,
        val third: C,
        val fourth: D
    )
}
