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
import com.tasirin.httpdownloadmanager.data.DownloadItem
import com.tasirin.httpdownloadmanager.data.DownloadState
import com.tasirin.httpdownloadmanager.util.NotificationHelper
import com.tasirin.httpdownloadmanager.util.StoragePrefs
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.update

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

    // Monitor bersama updateWakeLock <-> onDestroy (issue #1003):
    // cek-acquire dan teardown berjalan atomik satu sama lain,
    // menutup sisa celah check-then-act #989 secara tuntas.
    private val wakeLockGuard = Any()
    // Start server latar sedang berjalan di coroutine terpisah (issue
    // #474): selama bendera ini menyala, kolektor dan onStartCommand
    // tidak boleh menilai server "mati" hanya karena bind belum
    // selesai (loop retry bind bisa ~600 ms) — menilai dari isAlive
    // saja membuat emisi pertama bisa stopSelf dan membatalkan start
    // yang sedang berjalan, sehingga server latar mati senyap.
    @Volatile
    private var serverStartPending = false

    // Gerbang penilaian berhenti (issue #1490): tiga penilai
    // stopSelfIfNoWork (kolektor items, penilaian penutup start
    // server, kolektor reevaluate) berjalan di thread IO terpisah.
    // Tanpa serialisasi, pasangan posting -> cabut notifikasi bisa
    // berhimpit: penilai B memposting SESUDAH penilai A
    // men-stopForeground(REMOVE), meninggalkan notifikasi basi di
    // shade. Monitor + flag check-and-set membuat urutan
    // posting -> cabut -> stopSelf terjadi atomik dan hanya sekali.
    private val stopDecisionGuard = Any()

    @Volatile
    private var stopSelfDone = false

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
        // Konsumsi penanda boot di awal onCreate (issue #1208).
        val fromBoot = nextStartFromBoot
        nextStartFromBoot = false
        if (StoragePrefs.isServerBackgroundEnabled(this) &&
            StoragePrefs.isServerStartAllowed(this) && !App.httpServer.isAlive &&
            (!fromBoot || StoragePrefs.isServerAutoStartEnabled(this))
        ) {
            serverStartPending = true
            scope.launch(Dispatchers.IO) {
                try {
                    App.startHttpServer()
                } finally {
                    serverStartPending = false
                    // Penilaian penutup (issue #1034): start yang GAGAL
                    // sesudah emisi kolektor pertama lewat tak pernah
                    // dinilai ulang karena tak ada emisi items baru.
                    // Hanya bila muatan awal sudah selesai — sebelum
                    // itu daftar masih kosong palsu (#264) dan kolektor
                    // yang akan menilai lewat emisi pertamanya. Start
                    // yang BERHASIL membuat penilaian ini no-op
                    // (isAlive -> server dinilai aktif).
                    if (App.engine.isInitialLoadComplete()) {
                        val itemsNow = App.engine.items.value
                        val activeNow = itemsNow.any {
                            it.state == DownloadState.DOWNLOADING ||
                                it.state == DownloadState.PENDING
                        }
                        stopSelfIfNoWork(itemsNow, activeNow, isServerActive())
                    }
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
                    val serverActive = isServerActive()
                    if (!stopSelfIfNoWork(items, active, serverActive)) {
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
                        // Nilai ulang keadaan SEGAR tepat sebelum posting
                        // (issue #1510): stopSelfIfNoWork di atas menilai
                        // snapshot emisi ini, tetapi penilai berhenti lain
                        // bisa sudah menyelesaikan rangkaian berhentinya
                        // (posting final -> stopForeground(REMOVE) ->
                        // stopSelf) dari snapshot yang lebih baru di
                        // antara cek itu dan posting ini. Memposting
                        // snapshot basi sesudah pencabutan melahirkan
                        // notifikasi ongoing mandiri yang tak ada yang
                        // mencabutnya. Bila keadaan segar sudah tak punya
                        // pekerjaan aktif dan server tak aktif, lewati
                        // posting progres — penilai yang menang sudah
                        // menangani notifikasi akhirnya.
                        val freshItems = App.engine.items.value
                        val freshActive = freshItems.any {
                            it.state == DownloadState.DOWNLOADING ||
                                it.state == DownloadState.PENDING
                        }
                        if (!freshActive && !isServerActive()) return@runCatching
                        NotificationHelper.updateNotification(this@DownloadService, items, serverActive)
                    }
                }
            }
        }
        scope.launch(Dispatchers.IO) {
            // Penilaian ulang atas sinyal restart server gagal (issue
            // #1039): nilai replay awal hanya garis awal, bukan pemicu —
            // penilaian reguler tetap milik kolektor items + penilaian
            // penutup start onCreate (#1034). Gerbang muatan awal sama
            // seperti penilaian penutup #1034 (#264).
            var seenReevaluate = reevaluateRequests.value
            reevaluateRequests.collect { req ->
                if (req == seenReevaluate) return@collect
                seenReevaluate = req
                if (!App.engine.isInitialLoadComplete()) return@collect
                val itemsNow = App.engine.items.value
                val activeNow = itemsNow.any {
                    it.state == DownloadState.DOWNLOADING ||
                        it.state == DownloadState.PENDING
                }
                stopSelfIfNoWork(itemsNow, activeNow, isServerActive())
            }
        }
    }

    /** Server dinilai aktif dari pref + keadaan hidupnya saat ini juga
     *  (start yang sedang berjalan dihitung aktif, warisan #474/#488). */
    private fun isServerActive(): Boolean =
        StoragePrefs.isServerBackgroundEnabled(this) &&
            (App.httpServer.isAlive || serverStartPending || App.serverStartInProgress())

    /** Satu-satunya keputusan berhenti service (issue #1034): kolektor
     *  memanggilnya per emisi, dan penyelesaian start server di
     *  onCreate memanggilnya sekali lagi — kegagalan start yang telat
     *  tak menghasilkan emisi items, jadi tanpa penilaian penutup itu
     *  service bisa tertinggal foreground dengan notifikasi "server
     *  aktif" yang basi. Mengembalikan true bila service dihentikan. */
    private fun stopSelfIfNoWork(
        items: List<DownloadItem>,
        active: Boolean,
        serverActive: Boolean
    ): Boolean {
        if (active || serverActive) return false
        // Serial + hanya sekali (issue #1490): penilai kedua yang
        // berhimpit tak lagi memposting ulang notifikasi sesudah
        // pencabutan pertama — service memang sudah berhenti.
        synchronized(stopDecisionGuard) {
            if (stopSelfDone) return true
            // Nilai ulang keadaan SEGAR di dalam gerbang (issue
            // #1530): parameter di atas adalah snapshot saat pemanggil
            // berjalan — start server/unduhan yang tiba di jendela
            // antara penilaian pemanggil dan masuk gerbang ini (start
            // server juga mereset latch lewat onStartCommand, #1515)
            // bisa sudah menghidupkan pekerjaan saat rangkaian berhenti
            // dijalankan dari snapshot basi, menyisakan server hidup
            // tanpa perlindungan foreground (#1028). Definisi segarnya
            // persis milik ketiga pemanggil: any DOWNLOADING/PENDING
            // atas items.value saat ini + isServerActive(). Bila segar
            // menunjukkan ada kerja, berhenti DIBATALKAN (return
            // false) dan latch TIDAK dipasang — penilai berikutnya
            // tetap bebas menilai ulang.
            val freshItems = App.engine.items.value
            val freshActive = freshItems.any {
                it.state == DownloadState.DOWNLOADING ||
                    it.state == DownloadState.PENDING
            }
            if (freshActive || isServerActive()) return false
            stopSelfDone = true
            NotificationHelper.updateNotification(this, freshItems, serverActive)
            ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
            stopSelf()
        }
        return true
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // Start baru = service diminta hidup lagi (issue #1515): latch
        // stopSelfDone (#1490) wajib direset di dalam monitor yang
        // sama. Tanpa reset, instance yang hidup lagi lewat start ini
        // sesudah stopSelf() tak bisa berhenti untuk kedua kalinya —
        // penilai berikutnya menerima true dari latch TANPA
        // stopForeground/stopSelf dijalankan, dan kolektor berhenti
        // memperbarui notifikasi.
        synchronized(stopDecisionGuard) { stopSelfDone = false }
        // Konsumsi penanda boot juga di sini (issue #1239): service
        // yang sudah berjalan tak melewati onCreate saat job boot
        // men-start-nya, jadi tanpa ini penanda bertahan true dan
        // salah atribut ke start dingin non-boot berikutnya. Nilai
        // tak dipakai di jalur ini — keputusan server untuk proses
        // ini sudah dibuat di onCreate-nya.
        nextStartFromBoot = false
        val action = intent?.action
        when (action) {
            NotificationHelper.ACTION_PAUSE_ALL -> App.engine.pauseAll()
            NotificationHelper.ACTION_RESUME_ALL -> App.engine.resumeAll()
        }
        // Penilaian berhenti penutup start (issue #1532, cermin
        // #1515): reset latch di atas membuka kembali kemampuan
        // berhenti, tetapi fungsi ini sendiri tak pernah menilai dan
        // ketiga penilai lain bersifat terpicu (emisi items, sinyal
        // reevaluate, penutup start onCreate yang tak berjalan untuk
        // instance yang sudah hidup). Start yang tak membawa
        // pekerjaan — bentuk paling bersih ACTION_RESUME_ALL saat tak
        // ada yang terjedakan: resumeAll tak mengubah state sehingga
        // tak ada emisi — meninggalkan service hidup lagi dengan
        // notifikasi final basi dan tanpa satu pun penilai terjadwal.
        // Nilai di sini dari keadaan segar SESUDAH aksi ditangani:
        // pauseAll/resumeAll menerapkan perubahan state secara
        // sinkron bila muatan selesai, jadi start yang sungguh
        // membawa kerja terbaca aktif dan tak dihentikan. Gerbang
        // muatan awal sama seperti penilai lain (#264) — daftar
        // kosong palsu di jendela muat tidak dinilai.
        if (App.engine.isInitialLoadComplete()) {
            val itemsNow = App.engine.items.value
            val activeNow = itemsNow.any {
                it.state == DownloadState.DOWNLOADING ||
                    it.state == DownloadState.PENDING
            }
            stopSelfIfNoWork(itemsNow, activeNow, isServerActive())
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
        App.logEvent("SERVICE TIMEOUT: foreground type=$fgsType; pausing work (autoResume kept)")
        // Jeda paksa sistem, bukan inten pengguna (issue #1218):
        // autoResume dipertahankan agar unduhan lanjut sendiri pada
        // start berikutnya, sama seperti jalur proses mati mendadak.
        runCatching { App.engine.pauseAll(keepAutoResume = true) }
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
        // Seluruhnya di bawah monitor yang sama dengan
        // updateWakeLock (issue #1003) agar tak bisa disela acquire.
        synchronized(wakeLockGuard) {
            destroyed = true
            scope.cancel()
            val lock = wakeLock
            wakeLock = null
            if (lock != null && runCatching { lock.isHeld }.getOrDefault(false)) {
                runCatching { lock.release() }
            }
        }
        super.onDestroy()
    }

    // Sabuk-pengaman: bila jalur release terlewat (bug masa depan), lock tak
    // dipegang selamanya. Refresh otomatis: tiap emisi flow memanggil
    // updateWakeLock(true) yang re-acquire saat isHeld false, sehingga unduhan
    // sehat (emisi tiap tick progres) tak pernah kehilangan lock; stall total
    // >1 jam melepas CPU (hemat baterai) dan dipegang lagi saat pulih via
    // NetworkCallback/retry -> emisi -> re-acquire di bawah.
    companion object {
        const val WAKELOCK_TIMEOUT_MS = 60 * 60 * 1000L

        // Penanda start berikutnya berasal dari jalur boot (issue
        // #1208): diisi BootResumeJobService tepat sebelum
        // startForegroundService dan dikonsumsi onCreate instance
        // yang lahir darinya. Start boot hanya boleh menyalakan
        // server bila autostart server menyala; start ulang lengket
        // (sticky) dan start dari interaksi pengguna tidak terpengaruh.
        @Volatile
        var nextStartFromBoot = false

        // Sinyal penilaian ulang berhenti (issue #1039): restart server
        // yang GAGAL (mis. ganti port, port baru terpakai) tak menghasilkan
        // emisi items dan tak punya penilaian penutup seperti start dari
        // onCreate (#1034), jadi service bisa tertinggal foreground dengan
        // notifikasi "server aktif" basi. App menaikkan sinyal ini sesudah
        // restart gagal; instance service yang hidup menilainya dengan
        // stopSelfIfNoWork yang sama. Nilai replay StateFlow hanya dibaca
        // sebagai garis awal oleh kolektor (bukan pemicu).
        private val reevaluateRequests =
            kotlinx.coroutines.flow.MutableStateFlow(0L)

        /** Minta instance service yang hidup menilai ulang berhentinya. */
        fun requestReevaluate() {
            // update{} = CAS atomik (issue #1114): value += 1 adalah
            // baca-ubah-tulis yang bisa menelan satu sinyal bila dua
            // kegagalan restart tiba bersamaan dari thread berbeda.
            reevaluateRequests.update { it + 1 }
        }

        /** Pastikan DownloadService berjalan (issue #1028). Server mode
         *  background hanya terlindungi dari rebut proses sistem bila
         *  service ini hidup — logika berhenti kolektornya sendiri sudah
         *  memperhitungkan server aktif, dan onCreate service ikut
         *  men-start server. Sebelumnya hanya jalur unduhan dan jalur
         *  boot yang men-start service, jadi server yang dinyalakan dari
         *  Settings/App/MainActivity tanpa unduhan bisa mati senyap
         *  saat proses cached direbut sistem. Kegagalan start (mis.
         *  pembatasan start FGS dari latar Android 12+) ditelan seperti
         *  jalur engine: pen-start lain (job boot, start unduhan) tetap
         *  akan menjalankannya, dan server sendiri sudah menyala. */
        fun ensureRunning(context: Context) {
            runCatching {
                val intent = Intent(context, DownloadService::class.java)
                if (Build.VERSION.SDK_INT >= 26) {
                    context.startForegroundService(intent)
                } else {
                    context.startService(intent)
                }
            }
        }
    }

    // Acquire/release lintas callback lifecycle (bukan try/finally satu metode)
    // adalah desain sengaja — bukan kebocoran.
    @SuppressLint("Wakelock")
    private fun updateWakeLock(active: Boolean) {
        // Di bawah monitor yang sama dengan onDestroy (issue #1003):
        // cek + buat + acquire tak bisa disela teardown.
        synchronized(wakeLockGuard) {
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
}
