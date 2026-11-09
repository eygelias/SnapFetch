package com.snapfetch.app.data.worker

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.work.WorkManager
import com.snapfetch.app.R
import com.snapfetch.app.ui.main.MainActivity

object DownloadNotificationManager {

    const val CHANNEL_ID = "snapfetch_downloads_channel"
    const val NOTIFICATION_ID_BASE = 4001

    fun createNotificationChannel(context: Context) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val name = context.getString(R.string.notif_channel_name)
            val descriptionText = context.getString(R.string.notif_channel_desc)
            val importance = NotificationManager.IMPORTANCE_LOW
            val channel = NotificationChannel(CHANNEL_ID, name, importance).apply {
                description = descriptionText
                setShowBadge(false)
            }
            val notificationManager: NotificationManager =
                context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            notificationManager.createNotificationChannel(channel)
        }
    }

    fun buildProgressNotification(
        context: Context,
        notificationId: Int,
        workId: String,
        title: String,
        platformName: String,
        progressPercent: Int,
        isIndeterminate: Boolean = false
    ): Notification {
        // Intent to open Main app dashboard if tapped
        val openIntent = Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP
        }
        val openPendingIntent = PendingIntent.getActivity(
            context,
            notificationId,
            openIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        // Action: Cancel download
        val cancelIntent = WorkManager.getInstance(context).createCancelPendingIntent(java.util.UUID.fromString(workId))

        val progressText = if (isIndeterminate) {
            "Preparando descarga..."
        } else {
            "$progressPercent% • Sin salir de $platformName"
        }

        return NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setContentTitle("⬇️ Descargando en segundo plano...")
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
        val viewPendingIntent = PendingIntent.getActivity(
            context,
            notificationId + 100,
            viewIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setContentTitle("✅ Video guardado con éxito")
            .setContentText(title)
            .setStyle(NotificationCompat.BigTextStyle().bigText(
                "Tu descarga está lista en /Movies/SnapFetch.\nToca para reproducir el video."
            ))
            .setAutoCancel(true)
            .setContentIntent(viewPendingIntent)
            .addAction(android.R.drawable.ic_media_play, "Abrir", viewPendingIntent)
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .build()

        val notificationManager =
            context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        notificationManager.notify(notificationId, notification)
    }

    fun showFailureNotification(context: Context, notificationId: Int, errorMessage: String) {
        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setContentTitle("⚠️ Error al descargar")
            .setContentText(errorMessage)
            .setAutoCancel(true)
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .build()

        val notificationManager =
            context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        notificationManager.notify(notificationId, notification)
    }
}
