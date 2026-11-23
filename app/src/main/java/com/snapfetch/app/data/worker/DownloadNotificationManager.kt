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

    const val CHANNEL_ID = "snapfetch_downloads_channel"
    const val CHANNEL_COMPLETED_ID = "snapfetch_completed_channel"
    const val NOTIFICATION_ID_BASE = 4001

    fun createNotificationChannels(context: Context) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val notificationManager: NotificationManager =
                context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

            // 1. Silent channel for ongoing progress (low importance, no vibration or sounds per %)
            val progressChannel = NotificationChannel(
                CHANNEL_ID,
                "SnapFetch Descargas en Progreso",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Muestra el progreso de descargas de video en segundo plano"
                setShowBadge(false)
            }
            notificationManager.createNotificationChannel(progressChannel)

            // 2. High priority channel for completion / error alerts (with sound and heads-up banner)
            val completedChannel = NotificationChannel(
                CHANNEL_COMPLETED_ID,
                "SnapFetch Descargas Finalizadas",
                NotificationManager.IMPORTANCE_HIGH
            ).apply {
                description = "Notifica cuando un video se termina de descargar con éxito"
                setShowBadge(true)
                enableVibration(true)
                val soundUri = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION)
                val audioAttributes = AudioAttributes.Builder()
                    .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                    .setUsage(AudioAttributes.USAGE_NOTIFICATION)
                    .build()
                setSound(soundUri, audioAttributes)
            }
            notificationManager.createNotificationChannel(completedChannel)
        }
    }

    // Backward-compatible alias
    fun createNotificationChannel(context: Context) = createNotificationChannels(context)

    fun buildProgressNotification(
        context: Context,
        notificationId: Int,
        workId: String,
        title: String,
        platformName: String,
        progressPercent: Int,
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

        val progressText = if (isIndeterminate) {
            "Preparando descarga..."
        } else {
            "$progressPercent% • Sin salir de $platformName"
        }

        return NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setContentTitle("⬇️ Descargando video en segundo plano...")
            .setContentText(progressText)
            .setStyle(NotificationCompat.BigTextStyle().bigText(
                "⬇️ $title\n$progressPercent% • Sin salir de $platformName • Guardando en /Movies/SnapFetch"
            ))
            .setProgress(100, progressPercent, isIndeterminate)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setContentIntent(openPendingIntent)
            .addAction(android.R.drawable.ic_menu_close_clear_cancel, "Cancelar", cancelIntent)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setCategory(NotificationCompat.CATEGORY_PROGRESS)
            .build()
    }

    fun showCompletionNotification(
        context: Context,
        notificationId: Int,
        title: String,
        fileUri: Uri,
        mimeType: String
    ) {
        val viewIntent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(fileUri, mimeType)
            flags = Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK
        }
        // Use a distinct notification ID so WorkManager does not cancel it when the worker terminates
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
