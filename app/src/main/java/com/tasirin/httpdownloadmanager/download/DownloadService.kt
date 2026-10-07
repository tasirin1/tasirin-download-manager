package com.tasirin.httpdownloadmanager.download

import android.annotation.SuppressLint
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

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var lastUiUpdate = 0L
    // ID item yang terakhir terlihat aktif — dipakai mendeteksi item selesai/
    // mulai agar refresh notifikasi tidak ter-skip throttle (anti progress nyangkut).
    private var lastActiveIds: Set<String> = emptySet()
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
        scope.launch(Dispatchers.IO) {
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
                        // agar tidak boros baterai/CPU. Tapi bila keanggotaan item
                        // aktif berubah (ada yang selesai/mulai — mis. download
                        // terakhir rampung saat server background masih hidup),
                        // refresh wajib jalan: tanpa ini notifikasi beku di
                        // tampilan progress terakhir karena tak ada emisi berikutnya.
                        val activeIds = items.filter {
                            it.state == DownloadState.DOWNLOADING || it.state == DownloadState.PENDING
                        }.map { it.id }.toSet()
                        val membershipChanged = activeIds != lastActiveIds
                        lastActiveIds = activeIds
                        // Monotonik (bukan wall-clock): perubahan jam sistem maju/mundur
                        // tak boleh membuat throttle macet lama atau spam refresh.
                        val now = android.os.SystemClock.elapsedRealtime()
                        if (!membershipChanged && now - lastUiUpdate < 1000) return@runCatching
                        lastUiUpdate = now
                        NotificationHelper.updateNotification(this@DownloadService, items, serverActive)
                    }
                }
            }
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val action = intent?.action
        when (action) {
            NotificationHelper.ACTION_PAUSE_ALL -> App.engine.pauseAll()
            NotificationHelper.ACTION_RESUME_ALL -> App.engine.resumeAll()
        }
        // Baru diminta jalan = ada kerja (jangan NOT_STICKY: restart-mode ikut
        // return terakhir sehingga unduhan aktif kehilangan perlindungan).
        if (action == NotificationHelper.ACTION_RESUME_ALL) return START_STICKY
        // STICKY hanya bila masih ada kerja: restart service kosong oleh sistem
        // hanya mem-flash notifikasi sesaat sebelum stopSelf di emisi pertama.
        val keepAlive = App.engine.items.value.any {
            it.state == DownloadState.DOWNLOADING || it.state == DownloadState.PENDING
        } || (StoragePrefs.isServerBackgroundEnabled(this) && App.httpServer.isAlive)
        return if (keepAlive) START_STICKY else START_NOT_STICKY
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
        val lock = wakeLock
        wakeLock = null
        if (lock != null && runCatching { lock.isHeld }.getOrDefault(false)) {
            runCatching { lock.release() }
        }
        scope.cancel()
        super.onDestroy()
    }

    /** PARTIAL_WAKE_LOCK tanpa timeout selama ada item aktif: refresh lock
     *  hanya terjadi saat flow items meng-emit, sehingga timeout 15 menit bisa
     *  kedaluwarsa diam-diam saat koneksi stall tanpa tick progres. Lock tanpa
     *  timeout tetap aman: ikut lepas saat proses mati dan dilepas eksplisit
     *  saat antrean idle (cabang else di bawah) serta saat service destroy. */
    // Acquire/release lintas callback lifecycle (bukan try/finally satu
    // metode) dan tanpa timeout adalah desain sengaja — bukan kebocoran.
    @SuppressLint("Wakelock", "WakelockTimeout")
    private fun updateWakeLock(active: Boolean) {
        if (active) {
            if (wakeLock == null) {
                val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
                wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "httpdm:download")
                    .apply { setReferenceCounted(false) }
            }
            val lock = wakeLock
            if (lock != null && runCatching { !lock.isHeld }.getOrDefault(true)) {
                runCatching { lock.acquire() }
            }
        } else {
            val lock = wakeLock
            wakeLock = null
            // Cek isHeld: release lock yang sudah kedaluwarsa/dilepas
            // melempar RuntimeException yang hanya berisik di log.
            if (lock != null && runCatching { lock.isHeld }.getOrDefault(false)) {
                runCatching { lock.release() }
            }
        }
    }
}
