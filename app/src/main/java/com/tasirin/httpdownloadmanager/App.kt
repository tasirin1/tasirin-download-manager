package com.tasirin.httpdownloadmanager

import android.app.Application
import android.annotation.SuppressLint
import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.os.Build
import com.tasirin.httpdownloadmanager.download.DownloadEngine
import com.tasirin.httpdownloadmanager.remote.HttpControlServer
import com.tasirin.httpdownloadmanager.util.CrashLog
import com.tasirin.httpdownloadmanager.util.MediaLibrary
import com.tasirin.httpdownloadmanager.util.StoragePrefs

class App : Application() {
    override fun onCreate() {
        super.onCreate()
        installCrashLogger()
        httpServer = HttpControlServer(this)
        engine = DownloadEngine(this)
        runCatching { engine.cleanupOrphans() }
        // Bersihkan thumbnail disk lama di latar belakang (RAM tidak terpengaruh,
        // tapi disk cache tidak menumpuk tanpa menunggu server remote menyala).
        Thread { MediaLibrary.cleanupOldThumbs(this) }.start()
        // Server dinyalakan langsung dari Application supaya tetap jalan
        // walau halaman utama gagal terbuka (mis. crash di Activity).
        if (StoragePrefs.isServerBackgroundEnabled(this) &&
            StoragePrefs.isServerStartAllowed(this)
        ) {
            runCatching { httpServer.startServer() }
        }
        registerNetworkCallback()
        logEvent(
            "APP STARTED v" + runCatching {
                val info = packageManager.getPackageInfo(packageName, 0)
                info.versionName + " (build " + info.versionCode + ")"
            }.getOrDefault("?") + " (Android " + Build.VERSION.RELEASE +
                " API " + Build.VERSION.SDK_INT + ", " + Build.MANUFACTURER +
                " " + Build.MODEL + ")"
        )
    }

    // Android 7+ tidak menerima broadcast CONNECTIVITY_CHANGE untuk receiver
    // statis di manifest, jadi pakai NetworkCallback untuk fitur lanjutkan
    // download otomatis saat koneksi pulih (Android 5-6 pakai varian lama).
    private fun registerNetworkCallback() {
        runCatching {
            val cm = getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
            val request = NetworkRequest.Builder()
                .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
                .build()
            val callback = object : ConnectivityManager.NetworkCallback() {
                override fun onAvailable(network: Network) {
                    runCatching { engine.resumeAutoPaused() }
                }
            }
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
        const val CRASH_LOG_FILE = "crash.log"
        // StaticFieldLeak: sengaja ditahan — kedua objek menyimpan Application
        // context saja (dijamin di konstruktor) dan hidup seumur proses.
        @SuppressLint("StaticFieldLeak")
        lateinit var engine: DownloadEngine
        @SuppressLint("StaticFieldLeak")
        lateinit var httpServer: HttpControlServer

        /** Catat kejadian ke log realtime server (aman dipanggil dari mana saja). */
        fun logEvent(message: String) {
            runCatching { if (::httpServer.isInitialized) httpServer.appendLog(message) }
        }

        /** Buat ulang server remote dengan port terbaru dari prefs.
         *  NanoHTTPD mengunci port saat konstruksi, jadi ganti port = instance baru.
         *  Server tetap hidup bila sebelumnya hidup. */
        fun restartHttpServer(context: Context) {
            val wasAlive = httpServer.isAlive
            runCatching { httpServer.stopServer() }
            // Wajib applicationContext: instance ini hidup seumur proses dan
            // disimpan statis — Activity context (dari SettingsActivity) akan
            // bocor. HttpControlServer juga menormalkan ulang di konstruktor.
            httpServer = HttpControlServer(context.applicationContext)
            if (wasAlive) {
                runCatching { httpServer.startServer() }
            }
        }

        fun appendCrash(context: android.content.Context, tag: String, t: Throwable) {
            CrashLog.append(context, tag, t)
            // Ringkasan satu baris masuk log server realtime + ekspor TXT,
            // supaya crash juga terlihat dari remote web tanpa buka file.
            logEvent("CRASH [$tag]: ${t.javaClass.simpleName}: ${t.message?.take(160)}")
        }
    }
}
