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
import com.snapfetch.app.domain.model.DownloadQuality
import com.snapfetch.app.utils.StorageHelper
import com.snapfetch.app.utils.YoutubeDLHelper
import com.yausername.youtubedl_android.YoutubeDL
import com.yausername.youtubedl_android.YoutubeDLRequest
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.InputStream
import java.io.OutputStream
import java.util.concurrent.TimeUnit

class DownloadWorker(
    private val context: Context,
    workerParams: WorkerParameters
) : CoroutineWorker(context, workerParams) {

    companion object {
        private const val TAG = "DownloadWorker"
        const val KEY_STREAM_URL = "arg_stream_url"
        const val KEY_TITLE = "arg_title"
        const val KEY_AUTHOR = "arg_author"
        const val KEY_PLATFORM = "arg_platform"
        const val KEY_QUALITY_ID = "arg_quality_id"
        const val KEY_PROGRESS = "progress"
        const val KEY_RESULT_URI = "result_uri"
    }

    private val notificationId =
        DownloadNotificationManager.NOTIFICATION_ID_BASE + id.hashCode() % 1000

    private val httpClient = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(120, TimeUnit.SECONDS)
        .followRedirects(true)
        .build()

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

        // 1. Start foreground service with initial notification
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

        // 2. Prepare file name & MediaStore target
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
                "No se pudo crear el archivo en MediaStore"
            )
            return@withContext Result.failure()
        }

        var outputStream: OutputStream? = null

        try {
            outputStream = StorageHelper.openOutputStream(context, mediaUri)
                ?: throw IllegalStateException("No se pudo abrir el stream de salida")

            // Ensure YoutubeDL engine is ready
            YoutubeDLHelper.init(context)

            var success = false

            // Try local yt-dlp first
            if (YoutubeDLHelper.isReady()) {
                success = downloadWithYoutubeDL(streamUrl, outputStream, title, platform, isAudio, fileExtension)
            }

            // Fallback to direct HTTP stream if YoutubeDL did not complete or URL is direct
            if (!success && !isStopped) {
                Log.w(TAG, "YoutubeDL did not succeed, trying direct HTTP stream...")
                success = downloadHttpStream(streamUrl, outputStream, title, platform)
            }

            if (!success || isStopped) {
                StorageHelper.deletePendingEntry(context, mediaUri)
                if (!isStopped) {
                    DownloadNotificationManager.showFailureNotification(
                        context, notificationId,
                        "No se pudo descargar el video. Verifica tu conexión o el enlace."
                    )
                }
                return@withContext Result.failure()
            }

            // Finalize MediaStore entry (marks IS_PENDING = 0)
            StorageHelper.finalizeMediaStoreEntry(context, mediaUri)
            Log.d(TAG, "✅ Download complete: $fileName → $mediaUri")

            // Completion notification
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
                if (isAudio) {
                    addOption("-x")
                    addOption("--audio-format", "mp3")
                } else {
                    addOption("-f", "bestvideo[ext=mp4]+bestaudio[ext=m4a]/best[ext=mp4]/best")
                    addOption("--merge-output-format", "mp4")
                }
            }

            Log.d(TAG, "Executing YoutubeDL for $url -> ${tempFile.absolutePath}")

            var lastReported = -1
            val callback: (Float, Long, String?) -> Unit = { progress, etaInSeconds, _ ->
                if (isStopped) {
                    try {
                        YoutubeDL.getInstance().destroyProcessById(id.toString())
                    } catch (_: Exception) {}
                } else {
                    val p = progress.toInt().coerceIn(0, 100)
                    if (p != lastReported) {
                        lastReported = p
                        Log.v(TAG, "Download progress: $p% (ETA ${etaInSeconds}s)")
                    }
                }
            }

            YoutubeDL.getInstance().execute(
                request = request,
                processId = id.toString(),
                callback = callback
            )

            // Also search for actual file if extension got changed during merge
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
        platform: String
    ): Boolean {
        return try {
            val request = Request.Builder()
                .url(url)
                .header("User-Agent", "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 (KHTML, like Gecko)")
                .header("Accept", "*/*")
                .header("Accept-Encoding", "identity")
                .build()

            httpClient.newCall(request).execute().use { response ->
                if (!response.isSuccessful) return false
                val body = response.body ?: return false
                val totalBytes = body.contentLength()
                val inputStream: InputStream = body.byteStream()

                val buffer = ByteArray(16 * 1024)
                var bytesCopied: Long = 0
                var lastProgress = -1

                var bytes = inputStream.read(buffer)
                while (bytes >= 0) {
                    if (isStopped) return false

                    outputStream.write(buffer, 0, bytes)
                    bytesCopied += bytes

                    if (totalBytes > 0) {
                        val progress = ((bytesCopied * 100) / totalBytes).toInt().coerceAtMost(100)
                        if (progress != lastProgress && (progress - lastProgress >= 2 || progress == 100)) {
                            lastProgress = progress
                            updateForegroundProgress(progress, title, platform)
                        }
                    }
                    bytes = inputStream.read(buffer)
                }

                bytesCopied >= 1024
            }
        } catch (e: Exception) {
            Log.e(TAG, "HTTP stream fallback error", e)
            false
        }
    }

    private suspend fun updateForegroundProgress(
        progress: Int,
        title: String,
        platform: String
    ) {
        val isIndeterminate = progress < 0
        val displayProgress = if (isIndeterminate) 0 else progress

        setProgress(workDataOf(KEY_PROGRESS to displayProgress))

        val notification = DownloadNotificationManager.buildProgressNotification(
            context = context,
            notificationId = notificationId,
            workId = id.toString(),
            title = title,
            platformName = platform,
            progressPercent = displayProgress,
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
    }
}
