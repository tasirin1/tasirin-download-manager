package com.tasirin.httpdownloadmanager.download

import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import androidx.core.app.ServiceCompat
import com.tasirin.httpdownloadmanager.App
import com.tasirin.httpdownloadmanager.data.DownloadState
import com.tasirin.httpdownloadmanager.util.NotificationHelper
import com.tasirin.httpdownloadmanager.util.StoragePrefs
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

class DownloadService : Service() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var lastUiUpdate = 0L
    private var wakeLock: PowerManager.WakeLock? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        NotificationHelper.createChannel(this)
        runCatching { startForegroundCompat() }
        App.logEvent("SERVICE STARTED (background downloads)")
        if (StoragePrefs.isBackgroundEnabled(this)) {
            App.engine.resumeInterrupted()
        }
        if (StoragePrefs.isServerBackgroundEnabled(this) &&
            StoragePrefs.isServerStartAllowed(this) && !App.httpServer.isAlive
        ) {
            scope.launch(Dispatchers.IO) {
                runCatching { App.httpServer.startServer() }
            }
        }
        scope.launch {
            App.engine.items.collect { items ->
                runCatching {
                    val active = items.any {
                        it.state == DownloadState.DOWNLOADING || it.state == DownloadState.PENDING
                    }
                    updateWakeLock(active)
                    val serverActive = StoragePrefs.isServerBackgroundEnabled(this@DownloadService) &&
                        App.httpServer.isAlive
                    if (!active && !serverActive) {
                        NotificationHelper.updateNotification(this@DownloadService, items, serverActive)
                        ServiceCompat.stopForeground(
                            this@DownloadService,
                            ServiceCompat.STOP_FOREGROUND_REMOVE
                        )
                        stopSelf()
                    } else {
                        // Progress berubah ~4x/detik; batasi refresh UI jadi 1x/detik
                        // agar tidak boros baterai/CPU.
                        val now = System.currentTimeMillis()
                        if (now - lastUiUpdate < 1000) return@runCatching
                        lastUiUpdate = now
                        NotificationHelper.updateNotification(this@DownloadService, items, serverActive)
                    }
                }
            }
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            NotificationHelper.ACTION_PAUSE_ALL -> App.engine.pauseAll()
            NotificationHelper.ACTION_RESUME_ALL -> App.engine.resumeAll()
        }
        return START_STICKY
    }

    private fun startForegroundCompat() {
        val notification = NotificationHelper.foregroundNotification(this)
        if (Build.VERSION.SDK_INT >= 29) {
            startForeground(
                NotificationHelper.NOTIFICATION_ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
            )
        } else {
            startForeground(NotificationHelper.NOTIFICATION_ID, notification)
        }
    }

    override fun onDestroy() {
        runCatching { wakeLock?.release() }
        wakeLock = null
        scope.cancel()
        super.onDestroy()
    }

    /** PARTIAL_WAKE_LOCK hanya selama ada item aktif, timeout 15 menit sebagai
     *  pengaman (tick berikutnya meng-akuisisi ulang) supaya tidak boros baterai. */
    private fun updateWakeLock(active: Boolean) {
        if (active) {
            if (wakeLock == null) {
                val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
                wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "httpdm:download")
                    .apply { setReferenceCounted(false) }
            }
            val lock = wakeLock
            if (lock != null && !lock.isHeld) {
                runCatching { lock.acquire(15 * 60 * 1000L) }
            }
        } else {
            runCatching { wakeLock?.release() }
            wakeLock = null
        }
    }
}
