package com.tasirin.httpdownloadmanager

import android.app.Application
import android.annotation.SuppressLint
import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.os.Build
import androidx.appcompat.app.AppCompatDelegate
import com.tasirin.httpdownloadmanager.download.DownloadEngine
import com.tasirin.httpdownloadmanager.remote.HttpControlServer
import com.tasirin.httpdownloadmanager.util.CrashLog
import com.tasirin.httpdownloadmanager.util.MediaLibrary
import com.tasirin.httpdownloadmanager.util.SocialMediaExtractor
import com.tasirin.httpdownloadmanager.util.packageInfoCompat
import com.tasirin.httpdownloadmanager.util.versionCodeCompat
import com.tasirin.httpdownloadmanager.util.StoragePrefs

class App : Application() {
    override fun onCreate() {
        super.onCreate()
        // Tema gelap menyeluruh (keputusan owner 2026-10-30): paksa mode
        // malam AppCompat agar dialog/menu/kontrol bawaan ikut gelap di
        // semua perangkat, apa pun mode sistemnya.
        AppCompatDelegate.setDefaultNightMode(AppCompatDelegate.MODE_NIGHT_YES)
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
        // Server dinyalakan langsung dari Application supaya tetap jalan
        // walau halaman utama gagal terbuka (mis. crash di Activity).
        if (StoragePrefs.isServerBackgroundEnabled(this) &&
            StoragePrefs.isServerStartAllowed(this)
        ) {
            Thread {
                // Wajib lewat helper berkunci yang sama dengan jalur
                // lain (#286): start langsung di sini bisa menyela
                // restartHttpServer yang sedang menukar instance,
                // server berakhir menyala pada instance yang salah.
                startHttpServer()
            }.start()
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
            val cm = getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
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
            // himpunan dan onLost mengosongkannya.
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
                            availableNetworks.clear()
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
        }
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
        // PERANGKAT mati. Mulai true (asumsi tersedia) agar tak pernah
        // menggerbang sebelum callback pertama melaporkan keadaan.
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
                    runCatching { httpServer.startServer() }
                }
            }
            } finally {
                serverStartOps.decrementAndGet()
            }
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
                    runCatching { httpServer.startServer() }.isSuccess
                }
            } finally {
                serverStartOps.decrementAndGet()
            }
        }

        /** Stop server di bawah kunci siklus hidup yang sama (#286). */
        fun stopHttpServer(): Boolean =
            synchronized(restartServerLock) {
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
