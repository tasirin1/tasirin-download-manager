package com.tasirin.httpdownloadmanager

import android.app.Activity
import android.app.Application
import android.annotation.SuppressLint
import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.os.Build
import android.os.Bundle
import androidx.appcompat.app.AppCompatDelegate
import com.tasirin.httpdownloadmanager.download.DownloadEngine
import com.tasirin.httpdownloadmanager.download.DownloadService
import com.tasirin.httpdownloadmanager.remote.HttpControlServer
import com.tasirin.httpdownloadmanager.remote.ShareEntry
import com.tasirin.httpdownloadmanager.util.CrashLog
import com.tasirin.httpdownloadmanager.util.MediaLibrary
import com.tasirin.httpdownloadmanager.util.SocialMediaExtractor
import com.tasirin.httpdownloadmanager.util.packageInfoCompat
import com.tasirin.httpdownloadmanager.util.versionCodeCompat
import com.tasirin.httpdownloadmanager.util.StoragePrefs

class App : Application() {
    override fun onCreate() {
        super.onCreate()
        // Tema terang biru-putih menyeluruh gaya Vaultwarden Host
        // (keputusan owner 2026-11-08): paksa mode siang AppCompat agar
        // dialog/menu/kontrol bawaan ikut terang di semua perangkat,
        // apa pun mode sistemnya.
        AppCompatDelegate.setDefaultNightMode(AppCompatDelegate.MODE_NIGHT_NO)
        // Sembuhkan divergensi autostart/background warisan di tiap
        // awal proses (issue #1237): jalur boot membaca pref ini
        // sebelum Settings sempat dirender, jadi penyembuhan yang
        // hanya hidup di UI tak pernah menolong sesudah reboot.
        StoragePrefs.healDivergedTogglePairs(this)
        installCrashLogger()
        SocialMediaExtractor.init(this)
        httpServer = HttpControlServer(this)
        engine = DownloadEngine(this)
        Thread {
            runCatching { engine.cleanupOrphans() }
        }.start()
        // Bersihkan thumbnail disk lama maksimal sekali per 7 hari (RAM tidak
        // terpengaruh; disk cache tidak menumpuk tanpa menunggu server remote
        // menyala, tapi start aplikasi tidak membayar scan folder thumb tiap kali).
        val lastThumbCleanup = StoragePrefs.lastThumbCleanup(this)
        if (lastThumbCleanup == 0L ||
            System.currentTimeMillis() - lastThumbCleanup >= THUMB_CLEANUP_INTERVAL_MS
        ) {
            // Cap waktu ditulis SETELAH bersih-bersih sukses: bila proses
            // mati di tengah atau bersih-bersih gagal, start berikutnya
            // mengulang, bukan menunggu 7 hari dengan cache gagal tak dicoba lagi.
            Thread {
                val ok = runCatching { MediaLibrary.cleanupOldThumbs(this) }.isSuccess
                if (ok) StoragePrefs.setThumbCleanupDone(this, System.currentTimeMillis())
            }.start()
        }
        // Server dinyalakan dari Application supaya tetap jalan walau
        // halaman utama gagal terbuka (mis. crash di Activity) — tetapi
        // hanya pada start yang memang dikehendaki (issue #1208):
        // onCreate juga berjalan saat proses dibuat demi broadcast boot
        // atau job, dan selalu mendahului BootReceiver, jadi start
        // tanpa syarat di sini menembus toggle "auto-start server on
        // boot" yang mati — server menyala sesudah reboot walau
        // pengguna mematikannya. Autostart menyala -> nyalakan
        // sekarang (semantik boot); mati -> tunda sampai aktivitas
        // pertama benar-benar dibuka pengguna.
        if (StoragePrefs.isServerBackgroundEnabled(this) &&
            StoragePrefs.isServerStartAllowed(this)
        ) {
            if (StoragePrefs.isServerAutoStartEnabled(this)) {
                startServerAndService()
            } else {
                deferServerStartToFirstActivity()
            }
        }
        registerNetworkCallback()
        logEvent(
            "APP STARTED v" + runCatching {
                val info = packageManager.packageInfoCompat(packageName)
                info.versionName + " (build " + info.versionCodeCompat() + ")"
            }.getOrDefault("?") + " (Android " + Build.VERSION.RELEASE +
                " API " + Build.VERSION.SDK_INT + ", " + Build.MANUFACTURER +
                " " + Build.MODEL + ")"
        )
    }

    // Android 7+ tidak menerima broadcast CONNECTIVITY_CHANGE untuk receiver
    // statis di manifest, jadi pakai NetworkCallback untuk fitur lanjutkan
    // download otomatis saat koneksi pulih (Android 6 pakai varian lama).
    // Callback DITAHAN di field statis: instance lokal yang tak direferensikan
    // bisa dikumpulkan GC sehingga onAvailable berhenti dipanggil diam-diam
    // dan download jeda-jaringan tak pernah lanjut otomatis.
    private fun registerNetworkCallback() {
        runCatching {
            // Kegagalan total wajib berjejak (issue #966): tanpa log,
            // networkUp macet di nilai awal true (gerbang outage tak
            // pernah aktif) dan auto-resume mati, semuanya senyap
            // seumur proses. onFailure dipasang di ujung rantai.
            val cm = getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
            // Tanam keadaan awal dari snapshot sistem (issue #1043):
            // tanpa jaringan sama sekali, callback tak pernah menembak
            // (tak ada network untuk dilaporkan), jadi nilai awal true
            // bertahan dan gerbang outage #952 buta pada start dingin
            // mode pesawat — kegagalan connect pertama langsung masuk
            // failover mirror yang membuang parsial. activeNetwork
            // memberi keadaan sebenarnya saat proses mulai; callback
            // memeliharanya sesudah itu. Gagal baca (izin/keanehan OEM)
            // mempertahankan nilai awal.
            runCatching {
                synchronized(availableNetworks) {
                    networkUp = cm.activeNetwork != null
                }
            }
            val request = NetworkRequest.Builder()
                .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
                .build()
            // Semantik callback berbeda per jalur (issue #956): jalur
            // LISTEN (registerNetworkCallback, API 23) melaporkan SETIAP
            // network — agregat himpunan #954 sah di sana. Jalur default
            // (API 24+) hanya melaporkan network default TERAKHIR:
            // network yang berhenti jadi default tak pernah dilaporkan
            // hilang, jadi entri himpunannya basi menetap dan networkUp
            // macet true. Di jalur default, onAvailable MENGGANTI isi
            // himpunan dan onLost mengosongkannya bila yang hilang
            // memang isi himpunan saat ini (issue #1164).
            val defaultCallback = Build.VERSION.SDK_INT >= 24
            val callback = object : ConnectivityManager.NetworkCallback() {
                override fun onAvailable(network: Network) {
                    synchronized(availableNetworks) {
                        if (defaultCallback) availableNetworks.clear()
                        availableNetworks.add(network)
                        networkUp = true
                    }
                    runCatching { engine.resumeAutoPaused() }
                }

                override fun onLost(network: Network) {
                    // Bendera konektivitas untuk gerbang failover mirror
                    // engine (issue #952): turun hanya bila himpunan
                    // kosong — network lain yang masih hidup menahan
                    // networkUp tetap true (issue #954).
                    synchronized(availableNetworks) {
                        if (defaultCallback) {
                            // Kosongkan hanya bila yang hilang adalah
                            // isi himpunan saat ini (issue #1164):
                            // platform bisa mengirim onAvailable(default
                            // baru) SEBELUM onLost(default lama) saat
                            // default berganti — mengosongkan buta
                            // memasang networkUp=false palsu dan menahan
                            // failover mirror tanpa batas sampai
                            // pergantian jaringan berikutnya. Kabar
                            // hilang untuk network yang bukan isi
                            // himpunan adalah kabar basi, abaikan.
                            if (availableNetworks.contains(network)) {
                                availableNetworks.clear()
                            }
                        } else {
                            availableNetworks.remove(network)
                        }
                        networkUp = availableNetworks.isNotEmpty()
                    }
                }
            }
            networkCallback = callback
            if (Build.VERSION.SDK_INT >= 24) {
                cm.registerDefaultNetworkCallback(callback)
            } else {
                cm.registerNetworkCallback(request, callback)
            }
        }.onFailure {
            logEvent("NETWORK CALLBACK REGISTRATION FAILED: ${it.message?.take(80)}")
        }
    }

    /** Start server + service penjaga proses di thread latar. */
    private fun startServerAndService() {
        Thread {
            // Wajib lewat helper berkunci yang sama dengan jalur
            // lain (#286): start langsung bisa menyela
            // restartHttpServer yang sedang menukar instance,
            // server berakhir menyala pada instance yang salah.
            startHttpServer()
            // Mode background: service-lah yang menjaga proses
            // (dan server ini) tetap hidup di latar (issue #1028).
            DownloadService.ensureRunning(this@App)
        }.start()
    }

    /** Tunda start server sampai aktivitas pertama dibuka pengguna
     *  (issue #1208): pembuatan proses oleh broadcast/job tidak
     *  menghasilkan aktivitas, jadi server tetap mati sesudah boot
     *  bila autostart-nya mati. Callback sekali-pakai: lepas sendiri
     *  sesudah menembak, dan pref dinilai ulang saat itu juga. */
    private fun deferServerStartToFirstActivity() {
        registerActivityLifecycleCallbacks(object : ActivityLifecycleCallbacks {
            override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) {
                unregisterActivityLifecycleCallbacks(this)
                if (StoragePrefs.isServerBackgroundEnabled(this@App) &&
                    StoragePrefs.isServerStartAllowed(this@App)
                ) {
                    startServerAndService()
                }
            }

            override fun onActivityStarted(activity: Activity) {}
            override fun onActivityResumed(activity: Activity) {}
            override fun onActivityPaused(activity: Activity) {}
            override fun onActivityStopped(activity: Activity) {}
            override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) {}
            override fun onActivityDestroyed(activity: Activity) {}
        })
    }

    private fun installCrashLogger() {
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            appendCrash(this, thread.name, throwable)
            previous?.uncaughtException(thread, throwable)
        }
    }

    @SuppressLint("StaticFieldLeak")
    companion object {
        // Tahan NetworkCallback seumur proses (lihat registerNetworkCallback).
        // NetworkCallback bukan Context/activity sehingga aman ditahan statis.
        @Volatile private var networkCallback: ConnectivityManager.NetworkCallback? = null

        // Keadaan konektivitas terakhir dari NetworkCallback (issue #952):
        // engine membacanya untuk menahan failover mirror saat jaringan
        // PERANGKAT mati. Nilai awal true hanya bertahan sampai
        // registerNetworkCallback menanam snapshot activeNetwork saat
        // App.onCreate (issue #1043); sesudah itu callback yang memelihara.
        @Volatile
        var networkUp: Boolean = true
            private set

        // Network yang saat ini tersedia menurut callback (issue
        // #954/#956: agregat di jalur LISTEN, singleton terganti di
        // jalur default):
        // registrasi mengulang onAvailable untuk network yang sudah
        // hidup, jadi himpunan terisi sendiri sejak awal; semua akses
        // disinkronkan pada himpunan ini.
        private val availableNetworks = mutableSetOf<Network>()
        const val CRASH_LOG_FILE = "crash.log"
        private const val THUMB_CLEANUP_INTERVAL_MS = 7L * 24 * 60 * 60 * 1000
        // StaticFieldLeak: sengaja ditahan — kedua objek menyimpan Application
        // context saja (dijamin di konstruktor) dan hidup seumur proses.
        // @Volatile: httpServer DITUKAR saat runtime (restartHttpServer
        // dari SettingsActivity) dan keduanya dibaca lintas thread (UI,
        // engine, server) — publikasi penukaran harus dijamin terlihat
        // (issue #210).
        @SuppressLint("StaticFieldLeak")
        @Volatile
        lateinit var engine: DownloadEngine
        @SuppressLint("StaticFieldLeak")
        @Volatile
        lateinit var httpServer: HttpControlServer

        /** Catat kejadian ke log realtime server (aman dipanggil dari mana saja). */
        fun logEvent(message: String) {
            runCatching { if (::httpServer.isInitialized) httpServer.appendLog(message) }
        }

        /** Buang cache galeri + listing media + ZIP server (issue #660):
         *  penyelesaian unduhan biasa wajib membuang ketiganya — galeri
         *  saja membuat listing media dan ZIP folder menyajikan keadaan
         *  pra-selesai sampai TTL masing-masing berakhir. */
        fun invalidateServerDownloadCaches(publishedFilePath: String?) {
            runCatching {
                if (::httpServer.isInitialized) {
                    httpServer.invalidateDownloadCachesFor(publishedFilePath)
                }
            }
        }

        // Kunci khusus siklus hidup server (issue #260): SettingsActivity
        // memanggil restartHttpServer dari coroutine IO BARU tiap simpan,
        // jadi dua restart bisa berjalan bersamaan dan saling menyela
        // (snapshot dari instance yang sedang dihentikan, dua instance
        // baru berebut port, satu instance bocor tak pernah dihentikan).
        // Sejak #286 kunci yang sama juga membungkus start/stop langsung.
        private val restartServerLock = Any()

        /** Tautan share yang dibawa melewati stop-start manual
         *  (issue #1166): stopServer mengosongkan shareTokens, jadi
         *  stopHttpServer men-snapshot-nya di sini dan startHttpServer
         *  mengembalikannya — sejajar snapshot/restore jalur ganti
         *  port di restartHttpServer, agar link /share 24 jam tak
         *  mati hanya karena saklar server di Settings dimatikan
         *  lalu dinyalakan lagi. */
        private var sharesKeptAcrossStop: Map<String, ShareEntry> = emptyMap()

        // Penghitung operasi start/restart server yang sedang berjalan
        // (issue #488): bendera serverStartPending milik DownloadService
        // hanya menyala untuk start dari onCreate service sendiri,
        // sedangkan restart dari Settings dan start langsung dari
        // Activity berjalan di luar service — selama tukar-instance dan
        // retry bind, httpServer menunjuk instance yang isAlive-nya
        // false sehingga penilaian kolektor service bisa salah berhenti.
        // Indikator ini satu sumber kebenaran untuk SEMUA pintu start.
        private val serverStartOps = java.util.concurrent.atomic.AtomicInteger(0)

        /** True selagi ada start/restart server yang sedang berjalan. */
        fun serverStartInProgress(): Boolean = serverStartOps.get() > 0

        /** Buat ulang server remote dengan port terbaru dari prefs.
         *  NanoHTTPD mengunci port saat konstruksi, jadi ganti port = instance baru.
         *  Server tetap hidup bila sebelumnya hidup. Seluruh rangkaian
         *  snapshot -> stop -> tukar -> restore -> start berjalan di bawah
         *  satu kunci agar restart paralel terserialisasi (#260). */
        fun restartHttpServer(context: Context) {
            serverStartOps.incrementAndGet()
            var startFailed = false
            try {
            synchronized(restartServerLock) {
                val wasAlive = httpServer.isAlive
                // Ambil SEBELUM stop: stopServer mengosongkan shareTokens sehingga
                // link /share 24 jam + status upload-selesai ikut hangus tiap
                // ganti port. Sesi login aman (secret di SharedPreferences).
                val keptShares = runCatching { httpServer.snapshotShares() }.getOrDefault(emptyMap())
                val keptUploads = runCatching { httpServer.snapshotCompletedUploads() }.getOrDefault(emptyMap())
                runCatching { httpServer.stopServer() }
                // Log diambil SESUDAH stop dari instance lama (issue
                // #533): baris SERVER STOPPED ikut terbawa, dan
                // riwayat diagnostik tidak hangus seperti shares dan
                // uploads yang sudah lebih dulu di-snapshot.
                val keptLog = runCatching { httpServer.snapshotLogLines() }.getOrDefault(emptyList())
                // Wajib applicationContext: instance ini hidup seumur proses dan
                // disimpan statis — Activity context (dari SettingsActivity) akan
                // bocor. HttpControlServer juga menormalkan ulang di konstruktor.
                httpServer = HttpControlServer(context.applicationContext)
                runCatching { httpServer.restoreShares(keptShares) }
                runCatching { httpServer.restoreCompletedUploads(keptUploads) }
                runCatching { httpServer.restoreLogLines(keptLog) }
                if (keptShares.isNotEmpty() || keptUploads.isNotEmpty()) {
                    logEvent("SERVER RESTART: kept ${keptShares.size} share links, ${keptUploads.size} uploads")
                }
                if (wasAlive) {
                    startFailed =
                        runCatching { httpServer.startServer() }.isFailure
                }
            }
            } finally {
                serverStartOps.decrementAndGet()
            }
            // Restart yang gagal tak menghasilkan emisi items (issue
            // #1039): minta service menilai ulang berhentinya, dinilai
            // SESUDAH penghitung operasi turun agar serverStartInProgress
            // tak lagi menutupi kegagalan ini sebagai "sedang start".
            if (startFailed) DownloadService.requestReevaluate()
        }

        /** Start server di bawah kunci siklus hidup yang sama (#286):
         *  start/stop langsung tanpa kunci bisa menyela restart yang
         *  sedang berjalan — mis. stop penegakan PIN tiba SEBELUM langkah
         *  start milik restart menukar instance, sehingga server berakhir
         *  MENYALA walau aksi terakhir pengguna mematikannya. */
        fun startHttpServer(): Boolean {
            serverStartOps.incrementAndGet()
            try {
                return synchronized(restartServerLock) {
                    val ok = runCatching { httpServer.startServer() }.isSuccess
                    // Kembalikan tautan share yang disimpan stop manual
                    // sebelumnya (issue #1166); restoreShares membuang
                    // yang sudah kedaluwarsa.
                    if (ok && sharesKeptAcrossStop.isNotEmpty()) {
                        val kept = sharesKeptAcrossStop
                        sharesKeptAcrossStop = emptyMap()
                        runCatching { httpServer.restoreShares(kept) }
                        logEvent("SERVER START: kept ${kept.size} share links across stop")
                    }
                    ok
                }
            } finally {
                serverStartOps.decrementAndGet()
            }
        }

        /** Stop server di bawah kunci siklus hidup yang sama (#286). */
        fun stopHttpServer(): Boolean =
            synchronized(restartServerLock) {
                // Snapshot share SEBELUM stop (issue #1166), hanya bila
                // server hidup: stop atas server yang sudah mati adalah
                // no-op dan tak boleh menimpa snapshot stop sebelumnya
                // dengan peta kosong hasil stopServer yang sudah jalan.
                if (httpServer.isAlive) {
                    sharesKeptAcrossStop =
                        runCatching { httpServer.snapshotShares() }.getOrDefault(emptyMap())
                }
                runCatching { httpServer.stopServer() }.isSuccess
            }

        fun appendCrash(context: android.content.Context, tag: String, t: Throwable) {
            CrashLog.append(context, tag, t)
            // Ringkasan satu baris masuk log server realtime + ekspor TXT,
            // supaya crash juga terlihat dari remote web tanpa buka file.
            logEvent("CRASH [$tag]: ${t.javaClass.simpleName}: ${t.message?.take(160)}")
        }
    }
}
