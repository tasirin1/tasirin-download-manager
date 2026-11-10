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
import com.tasirin.httpdownloadmanager.util.Formats
import com.tasirin.httpdownloadmanager.download.DownloadService
import java.util.concurrent.atomic.AtomicInteger

object NotificationHelper {
    // Basis ID notifikasi per-item di luar ID foreground service (1001):
    // counter dari 0 akan menabrak notifikasi ongoing setelah ~1001 item selesai.
    // Batas atas + bungkus: Int meluap jadi negatif (~2M selesai) dan ID
    // negatif berperilaku aneh di NotificationManager.
    internal const val NOTIF_ID_BASE = 10000
    internal const val NOTIF_ID_MAX = 900_000
    private val nextNotifId = AtomicInteger(NOTIF_ID_BASE)

    /** ID berikutnya dengan bungkus ke basis saat mencapai batas. Murni agar
     *  bisa di-unit-test. */
    internal fun wrapNotifId(current: Int): Int =
        if (current >= NOTIF_ID_MAX) NOTIF_ID_BASE else current + 1

    const val CHANNEL_ID = "downloads"
    const val NOTIFICATION_ID = 1001
    const val ACTION_PAUSE_ALL = "com.tasirin.httpdownloadmanager.PAUSE_ALL"
    const val ACTION_RESUME_ALL = "com.tasirin.httpdownloadmanager.RESUME_ALL"

    // PendingIntent untuk intent yang konstan seumur proses (issue
    // #1453): sebelumnya buildNotification meminta ulang hingga tiga
    // PendingIntent ke sistem pada tiap refresh (hingga 1x/detik).
    // Objeknya aman dipakai ulang lintas refresh karena target, aksi,
    // requestCode, dan flag-nya tetap; yang berubah per refresh hanya
    // Notification-nya. Dibuat malas + double-checked agar aman dari
    // pemanggilan bersamaan, dan selalu dari applicationContext agar
    // cache seumur proses tidak menahan Activity/Service pemanggil.
    @Volatile
    private var cachedContentPendingIntent: PendingIntent? = null

    @Volatile
    private var cachedPausePendingIntent: PendingIntent? = null

    @Volatile
    private var cachedResumePendingIntent: PendingIntent? = null

    private fun contentPendingIntent(context: Context): PendingIntent {
        cachedContentPendingIntent?.let { return it }
        return synchronized(this) {
            cachedContentPendingIntent ?: run {
                val appContext = context.applicationContext
                val intent = Intent(appContext, MainActivity::class.java)
                    .setPackage(appContext.packageName)
                PendingIntent.getActivity(
                    appContext, 0, intent,
                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
                ).also { cachedContentPendingIntent = it }
            }
        }
    }

    private fun pausePendingIntent(context: Context): PendingIntent {
        cachedPausePendingIntent?.let { return it }
        return synchronized(this) {
            cachedPausePendingIntent ?: run {
                val appContext = context.applicationContext
                val intent = Intent(appContext, DownloadService::class.java)
                    .setAction(ACTION_PAUSE_ALL)
                    .setPackage(appContext.packageName)
                PendingIntent.getService(
                    appContext, 1, intent,
                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
                ).also { cachedPausePendingIntent = it }
            }
        }
    }

    private fun resumePendingIntent(context: Context): PendingIntent {
        cachedResumePendingIntent?.let { return it }
        return synchronized(this) {
            cachedResumePendingIntent ?: run {
                val appContext = context.applicationContext
                val intent = Intent(appContext, DownloadService::class.java)
                    .setAction(ACTION_RESUME_ALL)
                    .setPackage(appContext.packageName)
                PendingIntent.getService(
                    appContext, 2, intent,
                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
                ).also { cachedResumePendingIntent = it }
            }
        }
    }

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

    fun foregroundNotification(context: Context): Notification =
        buildNotification(context, emptyList(), initial = true)

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
        serverActive: Boolean = false,
        // Notifikasi awal startForeground (issue #1401): belum ada
        // unduhan apa pun, jadi cabang "selesai" tak boleh menampilkan
        // plurals done dengan angka 0 — teks netral dipakai. Jalur
        // updateNotification tak meneruskan flag ini (perilaku lama).
        initial: Boolean = false
    ): Notification {
        // Satu pass (bukan filter + any + count): tiap refresh notifikasi
        // saat download aktif memindai daftar 3x + 1 ArrayList buangan.
        val active = ArrayList<DownloadItem>(items.size)
        var hasResumable = false
        var completed = 0
        for (item in items) {
            when (item.state) {
                DownloadState.DOWNLOADING, DownloadState.PENDING -> active.add(item)
                DownloadState.PAUSED, DownloadState.FAILED -> hasResumable = true
                DownloadState.COMPLETED -> completed++
                else -> {}
            }
        }
        val builder = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(context.getString(R.string.notification_title))
            .setOngoing(active.isNotEmpty() || serverActive)
            .setOnlyAlertOnce(true)
            .setContentIntent(contentPendingIntent(context))

        if (active.isNotEmpty()) {
            builder.addAction(
                R.drawable.ic_pause,
                context.getString(R.string.pause_all),
                pausePendingIntent(context)
            )
        }
        if (hasResumable) {
            builder.addAction(
                R.drawable.ic_play,
                context.getString(R.string.resume_all),
                resumePendingIntent(context)
            )
        }

        if (active.isNotEmpty()) {
            builder.setContentText(
                context.resources.getQuantityString(
                    R.plurals.notification_active_files, active.size, active.size
                )
            )
            // Hanya total diketahui yang dihitung: item pending tanpa total
            // tak boleh mengencerkan denominator sehingga persen macet.
            val progress = com.tasirin.httpdownloadmanager.data.aggregateDownloadProgress(active)
            if (progress != null) {
                builder.setProgress(100, progress, false)
            } else {
                builder.setProgress(0, 0, true)
            }
        } else if (serverActive) {
            builder.setContentText(context.getString(R.string.notification_server_active))
                .setProgress(0, 0, false)
        } else if (initial) {
            builder.setContentText(context.getString(R.string.notification_service_running))
                .setProgress(0, 0, false)
        } else {
            builder.setContentText(
                context.resources.getQuantityString(
                    R.plurals.notification_done, completed, completed
                )
            )
                .setProgress(0, 0, false)
        }
        return builder.build()
    }

    /** Notifikasi per-item: selesai/gagal — muncul di bawah notifikasi service. */
    fun notifyItemFinished(context: Context, item: DownloadItem) {
        if (Build.VERSION.SDK_INT >= 33 &&
            ActivityCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) !=
            PackageManager.PERMISSION_GRANTED
        ) return
        val success = item.state == DownloadState.COMPLETED
        val id = nextNotifId.getAndUpdate(::wrapNotifId)
        // Judul gagal + galat tak dikenal lewat resource (issue #1078):
        // dulu dua literal Kotlin ini satu-satunya teks notifikasi yang
        // tak bisa dilokalkan, sementara seluruh teks lain di berkas
        // ini lewat R.string.
        val title = if (success) {
            item.fileName
        } else {
            context.getString(R.string.notification_failed_title, item.fileName)
        }
        val text = if (success) {
            Formats.bytes(item.bytesDownloaded)
        } else {
            item.error ?: context.getString(R.string.notification_unknown_error)
        }
        // requestCode stabil (bukan id unik): intent selalu sama (buka MainActivity
        // tanpa extra) sehingga PendingIntent bersama di atas dipakai ulang;
        // requestCode unik per unduhan selesai justru menumpuk objek
        // PendingIntent di sistem seumur proses. Id unik hanya untuk baris
        // notifikasi di bawah.
        val notif = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(title)
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setContentIntent(contentPendingIntent(context))
            .setAutoCancel(true)
            .setOnlyAlertOnce(true)
            .build()
        val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        manager.notify(id, notif)
    }
}
