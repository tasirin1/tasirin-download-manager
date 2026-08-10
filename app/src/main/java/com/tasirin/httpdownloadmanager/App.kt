package com.tasirin.httpdownloadmanager

import android.app.Application
import android.annotation.SuppressLint
import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.os.Build
import android.util.Log
import com.tasirin.httpdownloadmanager.download.DownloadEngine
import com.tasirin.httpdownloadmanager.remote.HttpControlServer
import com.tasirin.httpdownloadmanager.util.MediaLibrary
import com.tasirin.httpdownloadmanager.util.StoragePrefs
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

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
            "APLIKASI MULAI v" + runCatching {
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
        lateinit var engine: DownloadEngine
        lateinit var httpServer: HttpControlServer

        /** Buat ulang server remote dengan port terbaru dari prefs.
         *  NanoHTTPD mengunci port saat konstruksi, jadi ganti port = instance baru.
         *  Server tetap hidup bila sebelumnya hidup. */
        /** Catat kejadian ke log realtime server (aman dipanggil dari mana saja). */
        fun logEvent(message: String) {
            runCatching { if (::httpServer.isInitialized) httpServer.appendLog(message) }
        }

        fun restartHttpServer(context: Context) {
            val wasAlive = httpServer.isAlive
            runCatching { httpServer.stopServer() }
            httpServer = HttpControlServer(context)
            if (wasAlive) {
                runCatching { httpServer.startServer() }
            }
        }

        fun appendCrash(context: android.content.Context, tag: String, t: Throwable) {
            runCatching {
                val file = File(context.filesDir, CRASH_LOG_FILE)
                val stamp = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(Date())
                val text = buildString {
                    appendLine("=== $stamp [$tag] ===")
                    appendLine(Log.getStackTraceString(t))
                    appendLine()
                }
                val existing = if (file.exists()) file.readText() else ""
                val merged = (existing + text).takeLast(100_000)
                file.writeText(merged)
            }
        }
    }
}
