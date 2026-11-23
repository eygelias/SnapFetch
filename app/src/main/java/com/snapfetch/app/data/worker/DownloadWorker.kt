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
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
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

        // ── Validate URL ────────────────────────────────────────────
        if (streamUrl.isNullOrBlank()) {
            Log.e(TAG, "No stream URL provided")
            DownloadNotificationManager.showFailureNotification(
                context, notificationId,
                "No se pudo obtener la URL de descarga del video. Intenta de nuevo."
            )
            return@withContext Result.failure()
        }

        Log.d(TAG, "Starting download: $streamUrl")

        // ── Start foreground service with initial notification ───────
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

        // ── Prepare file name & MediaStore target ───────────────────
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

            // ── Execute real HTTP download ──────────────────────────
            val downloadSuccess = downloadHttpStream(
                url = streamUrl,
                outputStream = outputStream,
                title = title,
                platform = platform
            )

            if (!downloadSuccess || isStopped) {
                StorageHelper.deletePendingEntry(context, mediaUri)
                if (!isStopped) {
                    DownloadNotificationManager.showFailureNotification(
                        context, notificationId,
                        "La descarga falló. El servidor no respondió correctamente."
                    )
                }
                return@withContext Result.failure()
            }

            // Finalize MediaStore entry (marks IS_PENDING = 0)
            StorageHelper.finalizeMediaStoreEntry(context, mediaUri)

            Log.d(TAG, "✅ Download complete: $fileName → $mediaUri")

            // Trigger completion notification
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
            } catch (_: Exception) {
            }
        }
    }

    /**
     * Downloads the binary stream from [url] and writes it to [outputStream],
     * updating the foreground notification with real progress.
     */
    private suspend fun downloadHttpStream(
        url: String,
        outputStream: OutputStream,
        title: String,
        platform: String
    ): Boolean {
        val request = Request.Builder()
            .url(url)
            .header("User-Agent", "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 (KHTML, like Gecko)")
            .header("Accept", "*/*")
            .header("Accept-Encoding", "identity")  // avoid gzip for progress tracking
            .build()

        httpClient.newCall(request).execute().use { response ->
            if (!response.isSuccessful) {
                Log.e(TAG, "HTTP download failed: ${response.code} ${response.message}")
                return false
            }

            val body = response.body ?: return false
            val totalBytes = body.contentLength()
            val inputStream: InputStream = body.byteStream()

            Log.d(TAG, "Downloading: totalBytes=$totalBytes")

            val buffer = ByteArray(16 * 1024)
            var bytesCopied: Long = 0
            var lastProgress = -1

            var bytes = inputStream.read(buffer)
            while (bytes >= 0) {
                if (isStopped) {
                    Log.d(TAG, "Download cancelled by user")
                    return false
                }

                outputStream.write(buffer, 0, bytes)
                bytesCopied += bytes

                if (totalBytes > 0) {
                    val progress = ((bytesCopied * 100) / totalBytes).toInt().coerceAtMost(100)
                    if (progress != lastProgress && (progress - lastProgress >= 2 || progress == 100)) {
                        lastProgress = progress
                        updateForegroundProgress(progress, title, platform)
                    }
                } else {
                    // Unknown total – update every ~256 KB
                    val mbDownloaded = bytesCopied / (256 * 1024)
                    if (mbDownloaded > lastProgress) {
                        lastProgress = mbDownloaded.toInt()
                        updateForegroundProgress(-1, title, platform) // indeterminate
                    }
                }

                bytes = inputStream.read(buffer)
            }

            Log.d(TAG, "Stream finished: $bytesCopied bytes written")

            // Verify we got actual data
            if (bytesCopied < 1024) {
                Log.e(TAG, "Downloaded file too small ($bytesCopied bytes) – likely not a valid video")
                return false
            }

            return true
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
