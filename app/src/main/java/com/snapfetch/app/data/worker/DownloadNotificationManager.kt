package com.snapfetch.app.data.worker

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.media.AudioAttributes
import android.media.RingtoneManager
import android.net.Uri
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.work.WorkManager
import com.snapfetch.app.R
import com.snapfetch.app.ui.main.MainActivity

object DownloadNotificationManager {

    const val CHANNEL_ID = "snapfetch_downloads_channel_v4"
    const val CHANNEL_COMPLETED_ID = "snapfetch_completed_channel_v4"
    const val NOTIFICATION_ID_BASE = 4001

    fun createNotificationChannels(context: Context) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val notificationManager: NotificationManager =
                context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

            // Delete legacy low-importance channels if any
            try {
                notificationManager.deleteNotificationChannel("snapfetch_downloads_channel")
                notificationManager.deleteNotificationChannel("snapfetch_downloads_channel_v2")
                notificationManager.deleteNotificationChannel("snapfetch_downloads_channel_v3")
            } catch (_: Exception) {}

            // 1. Progress channel: Prominent in notification shade alongside messages (IMPORTANCE_DEFAULT)
            val progressChannel = NotificationChannel(
                CHANNEL_ID,
                "SnapFetch Descargas en Progreso",
                NotificationManager.IMPORTANCE_DEFAULT
            ).apply {
                description = "Muestra la barra de progreso activa en la cortina de notificaciones"
                setShowBadge(true)
                setSound(null, null) // Silent updates so it does not chime on every percent
                enableVibration(false)
                lockscreenVisibility = Notification.VISIBILITY_PUBLIC
            }
            notificationManager.createNotificationChannel(progressChannel)

            // 2. High priority channel for download completed alerts (with sound and heads-up banner)
            val completedChannel = NotificationChannel(
                CHANNEL_COMPLETED_ID,
                "SnapFetch Descargas Finalizadas",
                NotificationManager.IMPORTANCE_HIGH
            ).apply {
                description = "Avisos emergentes cuando un video se termina de descargar"
                setShowBadge(true)
                enableVibration(true)
                val soundUri = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION)
                val audioAttributes = AudioAttributes.Builder()
                    .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                    .setUsage(AudioAttributes.USAGE_NOTIFICATION)
                    .build()
                setSound(soundUri, audioAttributes)
                lockscreenVisibility = Notification.VISIBILITY_PUBLIC
            }
            notificationManager.createNotificationChannel(completedChannel)
        }
    }

    // Backward-compatible alias
    fun createNotificationChannel(context: Context) = createNotificationChannels(context)

    fun formatBytes(bytes: Long): String {
        if (bytes <= 0) return ""
        val kb = bytes / 1024.0
        val mb = kb / 1024.0
        val gb = mb / 1024.0
        return when {
            gb >= 1.0 -> String.format(java.util.Locale.US, "%.1f GB", gb)
            mb >= 1.0 -> String.format(java.util.Locale.US, "%.1f MB", mb)
            else -> String.format(java.util.Locale.US, "%.0f KB", kb)
        }
    }

    fun buildProgressNotification(
        context: Context,
        notificationId: Int,
        workId: String,
        title: String,
        platformName: String,
        progressPercent: Int,
        bytesDownloaded: Long = 0L,
        totalBytes: Long = 0L,
        isIndeterminate: Boolean = false
    ): Notification {
        val openIntent = Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP
        }
        val openPendingIntent = PendingIntent.getActivity(
            context,
            notificationId,
            openIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val cancelIntent = WorkManager.getInstance(context).createCancelPendingIntent(java.util.UUID.fromString(workId))

        val progressText = when {
            isIndeterminate -> if (bytesDownloaded > 0) "Descargando... ${formatBytes(bytesDownloaded)}" else "Preparando descarga..."
            totalBytes > 0 -> "$progressPercent% • ${formatBytes(bytesDownloaded)} de ${formatBytes(totalBytes)}"
            bytesDownloaded > 0 -> "$progressPercent% • ${formatBytes(bytesDownloaded)}"
            else -> "$progressPercent% • Sin salir de $platformName"
        }

        val builder = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setContentTitle("⬇️ Descargando video ($platformName)")
            .setContentText(progressText)
            .setSubText(if (isIndeterminate) null else "$progressPercent%")
            .setStyle(NotificationCompat.BigTextStyle().bigText(
                "⬇️ $title\n\n$progressText • Guardando en /Movies/SnapFetch"
            ))
            .setProgress(100, progressPercent.coerceIn(0, 100), isIndeterminate)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setContentIntent(openPendingIntent)
            .addAction(android.R.drawable.ic_menu_close_clear_cancel, "Cancelar", cancelIntent)
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .setCategory(NotificationCompat.CATEGORY_PROGRESS)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)

        // Show immediately on Android 12+ without the default 10-second system delay
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            builder.setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
        }

        return builder.build()
    }

    /**
     * Explicitly dismisses the ongoing download progress notification from the status bar.
     */
    fun dismissProgressNotification(context: Context, notificationId: Int) {
        val notificationManager =
            context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        notificationManager.cancel(notificationId)
    }

    fun showCompletionNotification(
        context: Context,
        notificationId: Int,
        title: String,
        fileUri: Uri,
        mimeType: String
    ) {
        // First ensure any lingering progress notification is removed
        dismissProgressNotification(context, notificationId)

        val viewIntent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(fileUri, mimeType)
            flags = Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK
        }
        val completionNotificationId = NOTIFICATION_ID_BASE + 5000 + (notificationId % 1000)

        val viewPendingIntent = PendingIntent.getActivity(
            context,
            completionNotificationId,
            viewIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val notification = NotificationCompat.Builder(context, CHANNEL_COMPLETED_ID)
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setContentTitle("✅ ¡Descarga finalizada!")
            .setContentText(title)
            .setStyle(NotificationCompat.BigTextStyle().bigText(
                "✅ $title\n\nTu video ya está guardado en la galería (Movies/SnapFetch).\nToca aquí para reproducirlo."
            ))
            .setAutoCancel(true)
            .setContentIntent(viewPendingIntent)
            .addAction(android.R.drawable.ic_media_play, "Abrir video", viewPendingIntent)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setDefaults(NotificationCompat.DEFAULT_ALL)
            .build()

        val notificationManager =
            context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        notificationManager.notify(completionNotificationId, notification)
    }

    fun showFailureNotification(context: Context, notificationId: Int, errorMessage: String) {
        // First ensure progress bar is dismissed
        dismissProgressNotification(context, notificationId)

        val failureNotificationId = NOTIFICATION_ID_BASE + 6000 + (notificationId % 1000)

        val notification = NotificationCompat.Builder(context, CHANNEL_COMPLETED_ID)
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setContentTitle("⚠️ Error al descargar")
            .setContentText(errorMessage)
            .setStyle(NotificationCompat.BigTextStyle().bigText(
                "⚠️ No se pudo completar la descarga:\n$errorMessage\n\nVerifica que el video sea público e inténtalo de nuevo."
            ))
            .setAutoCancel(true)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setDefaults(NotificationCompat.DEFAULT_ALL)
            .build()

        val notificationManager =
            context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        notificationManager.notify(failureNotificationId, notification)
    }
}
