package com.snapfetch.app.data.worker

import android.content.Context
import android.content.pm.ServiceInfo
import android.net.Uri
import android.os.Build
import android.util.Log
import androidx.work.CoroutineWorker
import androidx.work.ForegroundInfo
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import com.snapfetch.app.SnapFetchApplication
import com.snapfetch.app.data.repository.VideoExtractorRepositoryImpl
import com.snapfetch.app.domain.model.DownloadQuality
import com.snapfetch.app.utils.StorageHelper
import com.snapfetch.app.utils.YoutubeDLHelper
import com.yausername.youtubedl_android.YoutubeDL
import com.yausername.youtubedl_android.YoutubeDLRequest
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.InputStream
import java.io.OutputStream
import java.util.concurrent.TimeUnit

/**
 * DownloadWorker: Resilient background downloader supporting:
 * - Direct CDN streaming with real-time progress for TikTok & Instagram.
 * - Local native yt-dlp downloading for Facebook, YouTube Shorts, X/Twitter.
 * - MediaStore Scoped Storage (/Movies/SnapFetch or /Music/SnapFetch).
 * - High-priority completion notification upon download finish.
 */
class DownloadWorker(
    private val context: Context,
    workerParams: WorkerParameters
) : CoroutineWorker(context, workerParams) {

    companion object {
        const val TAG = "DownloadWorker"
        const val KEY_STREAM_URL = "key_stream_url"
        const val KEY_TITLE = "key_title"
        const val KEY_AUTHOR = "key_author"
        const val KEY_PLATFORM = "key_platform"
        const val KEY_QUALITY_ID = "key_quality_id"
        const val KEY_PROGRESS = "key_progress"
        const val KEY_RESULT_URI = "key_result_uri"

        private val httpClient = OkHttpClient.Builder()
            .connectTimeout(30, TimeUnit.SECONDS)
            .readTimeout(60, TimeUnit.SECONDS)
            .followRedirects(true)
            .build()
    }

    private val notificationId: Int =
        DownloadNotificationManager.NOTIFICATION_ID_BASE + (id.hashCode() % 1000).let { if (it < 0) -it else it }

    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        val streamUrl = inputData.getString(KEY_STREAM_URL)
        val title = inputData.getString(KEY_TITLE) ?: "SnapFetch_Video_${System.currentTimeMillis()}"
        val platform = inputData.getString(KEY_PLATFORM) ?: "Social"
        val qualityId = inputData.getString(KEY_QUALITY_ID) ?: DownloadQuality.FHD_1080P.id
        val quality = DownloadQuality.fromId(qualityId)
        val isAudio = quality.isAudioOnly

        if (streamUrl.isNullOrBlank()) {
            Log.e(TAG, "No URL provided")
            DownloadNotificationManager.showFailureNotification(
                context, notificationId,
                "No se pudo obtener el enlace de descarga. Intenta de nuevo."
            )
            return@withContext Result.failure()
        }

        Log.d(TAG, "Starting download for: $streamUrl (isAudio=$isAudio)")

        // 1. Start foreground service with initial silent notification
        val initialNotification = DownloadNotificationManager.buildProgressNotification(
            context = context,
            notificationId = notificationId,
            workId = id.toString(),
            title = title,
            platformName = platform,
            progressPercent = 0,
            isIndeterminate = true
        )

        val foregroundInfo = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            ForegroundInfo(
                notificationId,
                initialNotification,
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
                } else {
                    0
                }
            )
        } else {
            ForegroundInfo(notificationId, initialNotification)
        }
        setForeground(foregroundInfo)

        // 2. Prepare MediaStore target
        val sanitizedTitle = title.replace(Regex("[^a-zA-Z0-9._\\- ]"), "_").take(50).trim()
        val fileExtension = if (isAudio) "mp3" else "mp4"
        val mimeType = if (isAudio) "audio/mpeg" else "video/mp4"
        val fileName = "SnapFetch_${sanitizedTitle}_${System.currentTimeMillis()}.$fileExtension"

        val mediaUri: Uri = StorageHelper.createMediaStoreEntry(
            context = context,
            displayName = fileName,
            mimeType = mimeType,
            isAudio = isAudio
        ) ?: run {
            DownloadNotificationManager.showFailureNotification(
                context, notificationId,
                "No se pudo crear el archivo en el almacenamiento del dispositivo"
            )
            return@withContext Result.failure()
        }

        var outputStream: OutputStream? = null

        try {
            outputStream = StorageHelper.openOutputStream(context, mediaUri)
                ?: throw IllegalStateException("No se pudo abrir el stream de salida en MediaStore")

            var success = false

            // Strategy A: If streamUrl is already a direct CDN media link, download directly via HTTP stream
            if (isDirectMediaUrl(streamUrl)) {
                Log.d(TAG, "URL is direct media link. Using high-speed HTTP streamer...")
                success = downloadHttpStream(streamUrl, outputStream, title, platform, isAudio)
            }

            // Strategy B: If URL is TikTok/Instagram and not direct, attempt on-the-fly extraction
            if (!success && !isStopped) {
                val extractor = SnapFetchApplication.instance.videoExtractorRepository as? VideoExtractorRepositoryImpl
                if (extractor != null) {
                    val lowerUrl = streamUrl.lowercase()
                    if (lowerUrl.contains("tiktok.com") || lowerUrl.contains("douyin.com")) {
                        Log.d(TAG, "Attempting on-the-fly TikTok direct extraction...")
                        val meta = extractor.extractTikTok(streamUrl)
                        val directUrl = if (isAudio) meta?.directAudioUrl else meta?.directStreamUrl
                        if (!directUrl.isNullOrBlank() && directUrl != streamUrl) {
                            success = downloadHttpStream(directUrl, outputStream, title, platform, isAudio)
                        }
                    } else if (lowerUrl.contains("instagram.com") || lowerUrl.contains("instagr.am")) {
                        Log.d(TAG, "Attempting on-the-fly Instagram direct extraction...")
                        val meta = extractor.extractInstagram(streamUrl)
                        val directUrl = meta?.directStreamUrl
                        if (!directUrl.isNullOrBlank() && directUrl != streamUrl) {
                            success = downloadHttpStream(directUrl, outputStream, title, platform, isAudio)
                        }
                    }
                }
            }

            // Strategy C: Local yt-dlp native engine (proven for Facebook, YouTube Shorts, X/Twitter)
            if (!success && !isStopped) {
                Log.d(TAG, "Attempting download with local yt-dlp engine...")
                YoutubeDLHelper.init(context)
                if (YoutubeDLHelper.isReady()) {
                    success = downloadWithYoutubeDL(streamUrl, outputStream, title, platform, isAudio, fileExtension)
                }
            }

            if (!success || isStopped) {
                StorageHelper.deletePendingEntry(context, mediaUri)
                DownloadNotificationManager.dismissProgressNotification(context, notificationId)
                if (!isStopped) {
                    DownloadNotificationManager.showFailureNotification(
                        context, notificationId,
                        "No se pudo completar la descarga del video. Verifica tu conexión o el enlace."
                    )
                }
                return@withContext Result.failure()
            }

            // 3. Finalize MediaStore entry (marks IS_PENDING = 0)
            StorageHelper.finalizeMediaStoreEntry(context, mediaUri)
            Log.d(TAG, "✅ Download successfully finalized: $fileName → $mediaUri")

            // 4. Dismiss progress notification and show high-priority completion notification
            DownloadNotificationManager.dismissProgressNotification(context, notificationId)
            DownloadNotificationManager.showCompletionNotification(
                context = context,
                notificationId = notificationId,
                title = title,
                fileUri = mediaUri,
                mimeType = mimeType
            )

            Result.success(workDataOf(KEY_RESULT_URI to mediaUri.toString()))
        } catch (e: Exception) {
            Log.e(TAG, "Download error", e)
            StorageHelper.deletePendingEntry(context, mediaUri)
            DownloadNotificationManager.dismissProgressNotification(context, notificationId)
            DownloadNotificationManager.showFailureNotification(
                context, notificationId,
                e.localizedMessage ?: "Error inesperado durante la descarga"
            )
            Result.failure()
        } finally {
            try {
                outputStream?.flush()
                outputStream?.close()
            } catch (_: Exception) {}
        }
    }

    override fun onStopped() {
        super.onStopped()
        try {
            DownloadNotificationManager.dismissProgressNotification(context, notificationId)
            YoutubeDL.getInstance().destroyProcessById(id.toString())
        } catch (_: Exception) {}
    }

    private fun isDirectMediaUrl(url: String): Boolean {
        val lower = url.lowercase()
        return lower.contains(".mp4") || lower.contains(".mp3") || lower.contains(".m4a") ||
                lower.contains("tiktokcdn") || lower.contains("fbcdn.net") ||
                lower.contains("cdninstagram.com") || lower.contains("googlevideo.com")
    }

    private suspend fun downloadWithYoutubeDL(
        url: String,
        outputStream: OutputStream,
        title: String,
        platform: String,
        isAudio: Boolean,
        fileExtension: String
    ): Boolean = withContext(Dispatchers.IO) {
        val cacheDir = context.externalCacheDir ?: context.cacheDir
        val tempFile = File(cacheDir, "sf_dl_${id}_${System.currentTimeMillis()}.$fileExtension")

        try {
            val request = YoutubeDLRequest(url).apply {
                addOption("-o", tempFile.absolutePath)
                addOption("--no-mtime")
                addOption("--no-playlist")
                addOption("--add-header", "User-Agent: Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36")
                if (isAudio) {
                    addOption("-x")
                    addOption("--audio-format", "mp3")
                } else {
                    addOption("-f", "bestvideo[ext=mp4]+bestaudio[ext=m4a]/best[ext=mp4]/best")
                    addOption("--merge-output-format", "mp4")
                }
            }

            Log.d(TAG, "Executing YoutubeDL for $url -> ${tempFile.absolutePath}")

            // Emit initial progress
            updateForegroundProgress(
                progress = 0,
                bytesDownloaded = 0L,
                totalBytes = 0L,
                title = title,
                platform = platform
            )

            var lastReported = -1
            var lastUpdateMs = 0L
            val coroutineScope = CoroutineScope(Dispatchers.IO)

            val callback: (Float, Long, String?) -> Unit = { progress, etaInSeconds, _ ->
                if (isStopped) {
                    try {
                        YoutubeDL.getInstance().destroyProcessById(id.toString())
                    } catch (_: Exception) {}
                } else {
                    val p = progress.toInt().coerceIn(0, 100)
                    val now = System.currentTimeMillis()
                    if ((p != lastReported && (p - lastReported >= 2 || p == 100)) && (now - lastUpdateMs >= 300 || p == 100)) {
                        lastReported = p
                        lastUpdateMs = now
                        Log.v(TAG, "yt-dlp progress: $p% (ETA ${etaInSeconds}s)")
                        coroutineScope.launch {
                            try {
                                updateForegroundProgress(
                                    progress = p,
                                    bytesDownloaded = 0L,
                                    totalBytes = 0L,
                                    title = title,
                                    platform = platform
                                )
                            } catch (_: Exception) {}
                        }
                    }
                }
            }

            YoutubeDL.getInstance().execute(
                request = request,
                processId = id.toString(),
                callback = callback
            )

            // Locate downloaded file (extension may change during merge)
            val actualFile = if (tempFile.exists()) {
                tempFile
            } else {
                val prefix = "sf_dl_${id}_"
                cacheDir.listFiles { _, name -> name.startsWith(prefix) }?.firstOrNull()
            }

            if (actualFile == null || !actualFile.exists() || actualFile.length() < 1024) {
                Log.w(TAG, "YoutubeDL output file missing or empty")
                return@withContext false
            }

            Log.d(TAG, "Copying ${actualFile.length()} bytes to MediaStore outputStream...")
            actualFile.inputStream().use { input ->
                input.copyTo(outputStream)
            }
            actualFile.delete()
            return@withContext true
        } catch (e: Exception) {
            Log.e(TAG, "YoutubeDL execution error", e)
            tempFile.delete()
            return@withContext false
        }
    }

    private suspend fun downloadHttpStream(
        url: String,
        outputStream: OutputStream,
        title: String,
        platform: String,
        isAudio: Boolean
    ): Boolean {
        return try {
            val request = Request.Builder()
                .url(url)
                .header("User-Agent", "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 (KHTML, like Gecko)")
                .header("Accept", "*/*")
                .header("Accept-Encoding", "identity")
                .build()

            httpClient.newCall(request).execute().use { response ->
                if (!response.isSuccessful) {
                    Log.e(TAG, "HTTP stream failed with code ${response.code}")
                    return false
                }
                val body = response.body ?: return false

                // Safeguard: Do NOT download HTML or plain text as media!
                val contentType = response.header("Content-Type")?.lowercase().orEmpty()
                if (contentType.startsWith("text/") || contentType.contains("html") || contentType.contains("json")) {
                    Log.e(TAG, "Content-Type is not a media stream: $contentType. Aborting.")
                    return false
                }

                val totalBytes = body.contentLength()
                val inputStream: InputStream = body.byteStream()

                val buffer = ByteArray(32 * 1024)
                var bytesCopied: Long = 0
                var lastProgress = -1
                var lastTimeUpdated = System.currentTimeMillis()

                // Emit initial progress immediately
                updateForegroundProgress(
                    progress = if (totalBytes > 0) 0 else -1,
                    bytesDownloaded = 0L,
                    totalBytes = if (totalBytes > 0) totalBytes else 0L,
                    title = title,
                    platform = platform
                )

                var bytes = inputStream.read(buffer)
                while (bytes >= 0) {
                    if (isStopped) return false

                    outputStream.write(buffer, 0, bytes)
                    bytesCopied += bytes

                    val now = System.currentTimeMillis()
                    if (totalBytes > 0) {
                        val progress = ((bytesCopied * 100) / totalBytes).toInt().coerceIn(0, 100)
                        if ((progress != lastProgress && (progress - lastProgress >= 2 || progress == 100)) || (now - lastTimeUpdated >= 350)) {
                            lastProgress = progress
                            lastTimeUpdated = now
                            updateForegroundProgress(
                                progress = progress,
                                bytesDownloaded = bytesCopied,
                                totalBytes = totalBytes,
                                title = title,
                                platform = platform
                            )
                        }
                    } else {
                        // Chunked or unknown length: update every 350ms
                        if (now - lastTimeUpdated >= 350) {
                            lastTimeUpdated = now
                            updateForegroundProgress(
                                progress = -1,
                                bytesDownloaded = bytesCopied,
                                totalBytes = 0L,
                                title = title,
                                platform = platform
                            )
                        }
                    }
                    bytes = inputStream.read(buffer)
                }

                // Minimum size check: Valid video must be at least 50KB, audio at least 20KB
                val minBytes = if (isAudio) 20 * 1024L else 50 * 1024L
                if (bytesCopied < minBytes) {
                    Log.e(TAG, "Downloaded file too small ($bytesCopied bytes), failing.")
                    false
                } else {
                    true
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "HTTP stream error", e)
            false
        }
    }

    private suspend fun updateForegroundProgress(
        progress: Int,
        bytesDownloaded: Long = 0L,
        totalBytes: Long = 0L,
        title: String,
        platform: String
    ) {
        val isIndeterminate = progress < 0
        val displayProgress = if (isIndeterminate) 0 else progress.coerceIn(0, 100)

        try {
            setProgress(workDataOf(KEY_PROGRESS to displayProgress))

            val notification = DownloadNotificationManager.buildProgressNotification(
                context = context,
                notificationId = notificationId,
                workId = id.toString(),
                title = title,
                platformName = platform,
                progressPercent = displayProgress,
                bytesDownloaded = bytesDownloaded,
                totalBytes = totalBytes,
                isIndeterminate = isIndeterminate
            )
            val info = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                ForegroundInfo(
                    notificationId,
                    notification,
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                        ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
                    } else {
                        0
                    }
                )
            } else {
                ForegroundInfo(notificationId, notification)
            }
            setForeground(info)
        } catch (e: Exception) {
            Log.w(TAG, "Failed to update foreground progress: ${e.message}")
        }
    }
}
