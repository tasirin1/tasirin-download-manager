package com.tasirin.httpdownloadmanager.download

import android.annotation.SuppressLint
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import androidx.annotation.RequiresApi
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
    // @Volatile (issue #989): field ini dibaca/ditulis lintas thread
    // main (onDestroy) dan thread IO kolektor tanpa kunci.
    @Volatile
    private var wakeLock: PowerManager.WakeLock? = null

    // Penanda hancur untuk balapan lepas wake lock (issue #982):
    // kolektor di thread IO memeriksa ini sebelum acquire.
    @Volatile
    private var destroyed = false
    // Start server latar sedang berjalan di coroutine terpisah (issue
    // #474): selama bendera ini menyala, kolektor dan onStartCommand
    // tidak boleh menilai server "mati" hanya karena bind belum
    // selesai (loop retry bind bisa ~600 ms) — menilai dari isAlive
    // saja membuat emisi pertama bisa stopSelf dan membatalkan start
    // yang sedang berjalan, sehingga server latar mati senyap.
    @Volatile
    private var serverStartPending = false

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        NotificationHelper.createChannel(this)
        // Kegagalan startForeground berkonsekuensi berat di API 31+
        // (proses dibunuh sistem) — jangan ditelan diam-diam; catat
        // nama exception-nya saja sebagai jejak diagnosis (issue #205).
        runCatching { startForegroundCompat() }
            .onFailure {
                App.logEvent("SERVICE: startForeground failed (${it.javaClass.simpleName})")
            }
        App.logEvent("SERVICE STARTED (background downloads)")
        if (StoragePrefs.isBackgroundEnabled(this)) {
            App.engine.resumeInterrupted()
        }
        if (StoragePrefs.isServerBackgroundEnabled(this) &&
            StoragePrefs.isServerStartAllowed(this) && !App.httpServer.isAlive
        ) {
            serverStartPending = true
            scope.launch(Dispatchers.IO) {
                try {
                    App.startHttpServer()
                } finally {
                    serverStartPending = false
                }
            }
        }
        scope.launch(Dispatchers.IO) {
            // Kolektor tidak boleh menilai emisi sebelum muatan awal
            // terpasang (#264): emisi pertama saat cold start adalah
            // daftar kosong muatan latar #242, dan cabang berhenti di
            // bawah akan membunuh service sebelum antrean terbaca.
            // StateFlow memutar ulang nilai terbaru begitu koleksi
            // dimulai, jadi menunggu gerbang di sini tidak melewatkan
            // emisi apa pun.
            App.engine.awaitInitialLoad()
            App.engine.items.collect { items ->
                runCatching {
                    val active = items.any {
                        it.state == DownloadState.DOWNLOADING || it.state == DownloadState.PENDING
                    }
                    updateWakeLock(active)
                    val serverActive = StoragePrefs.isServerBackgroundEnabled(this@DownloadService) &&
                        (App.httpServer.isAlive || serverStartPending || App.serverStartInProgress())
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
                        // Satu pass tanpa daftar sementara: filter+map+toSet
                        // membangun 2 ArrayList buangan per emisi tick.
                        val activeIds = buildSet {
                            for (item in items) {
                                if (item.state == DownloadState.DOWNLOADING ||
                                    item.state == DownloadState.PENDING
                                ) add(item.id)
                            }
                        }
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
        // Selama muatan awal belum terpasang daftar bisa kosong
        // PALSU (muatan latar #242): menilai keepAlive dari daftar
        // kosong di jendela itu mengembalikan NOT_STICKY walau
        // antrean tersimpan punya kerja aktif — perlindungan restart
        // hilang untuk start ini (#319). Anggap hidup sampai muatan
        // selesai; kolektor emisi pertama akan stopSelf bila memang
        // tidak ada kerja.
        val keepAlive = !App.engine.isInitialLoadComplete() ||
            App.engine.items.value.any {
                it.state == DownloadState.DOWNLOADING || it.state == DownloadState.PENDING
            } || (StoragePrefs.isServerBackgroundEnabled(this) &&
                (App.httpServer.isAlive || serverStartPending || App.serverStartInProgress()))
        return if (keepAlive) START_STICKY else START_NOT_STICKY
    }

    /** Android 15+: dataSync FGS dibatasi enam jam saat aplikasi di latar. */
    @RequiresApi(35)
    override fun onTimeout(startId: Int, fgsType: Int) {
        App.logEvent("SERVICE TIMEOUT: foreground type=$fgsType; pausing work")
        runCatching { App.engine.pauseAll() }
        App.stopHttpServer()
        ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
        stopSelf(startId)
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
        // Urutan dibalik (issue #982): tandai hancur + batalkan scope
        // DULU, baru lepas lock yang terbaca sesudahnya. Urutan lama
        // (lepas dulu, batal kemudian) memberi celah bagi kolektor
        // yang sedang memproses emisi untuk meng-acquire lock baru
        // sesudah pelepasan — lock tanpa pemilik sampai timeout.
        destroyed = true
        scope.cancel()
        val lock = wakeLock
        wakeLock = null
        if (lock != null && runCatching { lock.isHeld }.getOrDefault(false)) {
            runCatching { lock.release() }
        }
        super.onDestroy()
    }

    // Sabuk-pengaman: bila jalur release terlewat (bug masa depan), lock tak
    // dipegang selamanya. Refresh otomatis: tiap emisi flow memanggil
    // updateWakeLock(true) yang re-acquire saat isHeld false, sehingga unduhan
    // sehat (emisi tiap tick progres) tak pernah kehilangan lock; stall total
    // >1 jam melepas CPU (hemat baterai) dan dipegang lagi saat pulih via
    // NetworkCallback/retry -> emisi -> re-acquire di bawah.
    private companion object {
        const val WAKELOCK_TIMEOUT_MS = 60 * 60 * 1000L
    }

    // Acquire/release lintas callback lifecycle (bukan try/finally satu metode)
    // adalah desain sengaja — bukan kebocoran.
    @SuppressLint("Wakelock")
    private fun updateWakeLock(active: Boolean) {
        // Service yang sedang dihancurkan tak boleh acquire lagi
        // (issue #982): tak ada pemilik yang akan melepasnya.
        if (active && destroyed) return
        if (active) {
            if (wakeLock == null) {
                val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
                wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "httpdm:download")
                    .apply { setReferenceCounted(false) }
            }
            val lock = wakeLock
            // Periksa destroyed sekali lagi tepat sebelum acquire
            // (issue #989): cek di pintu fungsi dan acquire tidak
            // atomik — onDestroy bisa tuntas di sela-selanya dan
            // lock yang ter-acquire sesudah itu tak berpemilik.
            if (lock != null && !destroyed &&
                runCatching { !lock.isHeld }.getOrDefault(true)
            ) {
                runCatching { lock.acquire(WAKELOCK_TIMEOUT_MS) }
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
