package com.tasirin.httpdownloadmanager.util

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.ActivityCompat
import androidx.core.app.NotificationCompat
import com.tasirin.httpdownloadmanager.MainActivity
import com.tasirin.httpdownloadmanager.R
import com.tasirin.httpdownloadmanager.data.DownloadItem
import com.tasirin.httpdownloadmanager.data.DownloadState
import com.tasirin.httpdownloadmanager.download.DownloadService

object NotificationHelper {

    const val CHANNEL_ID = "downloads"
    const val NOTIFICATION_ID = 1001
    const val ACTION_PAUSE_ALL = "com.tasirin.httpdownloadmanager.PAUSE_ALL"
    const val ACTION_RESUME_ALL = "com.tasirin.httpdownloadmanager.RESUME_ALL"

    fun createChannel(context: Context) {
        if (Build.VERSION.SDK_INT >= 26) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                context.getString(R.string.notification_channel),
                NotificationManager.IMPORTANCE_LOW
            )
            val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            manager.createNotificationChannel(channel)
        }
    }

    fun foregroundNotification(context: Context): Notification = buildNotification(context, emptyList())

    fun updateNotification(
        context: Context,
        items: List<DownloadItem>,
        serverActive: Boolean = false
    ) {
        if (Build.VERSION.SDK_INT >= 33 &&
            ActivityCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            return
        }
        val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        manager.notify(NOTIFICATION_ID, buildNotification(context, items, serverActive))
    }

    private fun buildNotification(
        context: Context,
        items: List<DownloadItem>,
        serverActive: Boolean = false
    ): Notification {
        val active = items.filter {
            it.state == DownloadState.DOWNLOADING || it.state == DownloadState.PENDING
        }
        val intent = Intent(context, MainActivity::class.java)
        val pending = PendingIntent.getActivity(
            context, 0, intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val builder = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(context.getString(R.string.notification_title))
            .setOngoing(active.isNotEmpty() || serverActive)
            .setOnlyAlertOnce(true)
            .setContentIntent(pending)

        val hasResumable = items.any {
            it.state == DownloadState.PAUSED || it.state == DownloadState.FAILED
        }
        if (active.isNotEmpty()) {
            val pauseIntent = Intent(context, DownloadService::class.java)
                .setAction(ACTION_PAUSE_ALL)
            val pausePending = PendingIntent.getService(
                context, 1, pauseIntent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
            builder.addAction(
                R.drawable.ic_pause,
                context.getString(R.string.pause_all),
                pausePending
            )
        }
        if (hasResumable) {
            val resumeIntent = Intent(context, DownloadService::class.java)
                .setAction(ACTION_RESUME_ALL)
            val resumePending = PendingIntent.getService(
                context, 2, resumeIntent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
            builder.addAction(
                R.drawable.ic_play,
                context.getString(R.string.resume_all),
                resumePending
            )
        }

        if (active.isNotEmpty()) {
            val totalBytes = active.sumOf { it.totalBytes }
            val downloadedBytes = active.sumOf { it.bytesDownloaded }
            builder.setContentText(
                context.resources.getQuantityString(
                    R.plurals.notification_active_files, active.size, active.size
                )
            )
            if (totalBytes > 0) {
                builder.setProgress(100, (downloadedBytes * 100 / totalBytes).toInt(), false)
            } else {
                builder.setProgress(0, 0, true)
            }
        } else if (serverActive) {
            builder.setContentText(context.getString(R.string.notification_server_active))
                .setProgress(0, 0, false)
        } else {
            val completed = items.count { it.state == DownloadState.COMPLETED }
            builder.setContentText(
                context.resources.getQuantityString(
                    R.plurals.notification_done, completed, completed
                )
            )
                .setProgress(0, 0, false)
        }
        return builder.build()
    }
}
