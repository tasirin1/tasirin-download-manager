package com.tasirin.httpdownloadmanager.util

import android.content.Context
import com.tasirin.httpdownloadmanager.App
import com.tasirin.httpdownloadmanager.data.DownloadItem

/** Pembersihan otomatis saat storage menipis, supaya download tidak mati
 *  di tengah jalan karena "storage penuh". */
object StorageCleanup {

    /** Ambang free space (byte) yang memicu pembersihan. */
    const val LOW_THRESHOLD_BYTES = 512L * 1024 * 1024

    /** Jarak antar pembersihan minimum (menghindari I/O boros). */
    private const val MIN_INTERVAL_MS = 5 * 60 * 1000L

    /** Umur maksimal sisa upload chunk (up_*.tmp) di cache. */
    private const val UPLOAD_TMP_MAX_AGE_MS = 24L * 60 * 60 * 1000

    // Throttle memakai jam monotonik agar lompatan jam dinding (NTP/zona)
    // tidak merusak interval; dipasang di bawah lock agar dua pemanggil
    // paralel tidak lolos dan membersihkan ganda.
    private val throttleLock = Any()
    @Volatile private var lastRunElapsed = 0L

    /** Jalankan bila free space di bawah ambang; kembalikan byte yang dibebaskan. */
    fun runIfLow(
        context: Context,
        items: List<DownloadItem>,
        now: Long = System.currentTimeMillis()
    ): Long {
        synchronized(throttleLock) {
            if (android.os.SystemClock.elapsedRealtime() - lastRunElapsed < MIN_INTERVAL_MS) return 0L
            lastRunElapsed = android.os.SystemClock.elapsedRealtime()
        }
        val saver = FileSaver(context)
        val free = saver.destinationFreeBytes()
        if (free > LOW_THRESHOLD_BYTES) return 0L
        var freed = 0L
        freed += saver.cleanupOrphanPartials(items)
        freed += MediaLibrary.cleanupOldThumbs(context)
        freed += cleanupUploadTemps(context)
        if (freed > 0) {
            App.logEvent(
                "STORAGE LOW (${Formats.bytes(free)} free) — freed ${Formats.bytes(freed)}"
            )
        }
        return freed
    }

    /** Hapus sisa upload chunk (up_*.tmp) yang sudah basi dari cacheDir. */
    private fun cleanupUploadTemps(
        context: Context,
        maxAgeMs: Long = UPLOAD_TMP_MAX_AGE_MS
    ): Long {
        val dir = context.cacheDir
        if (!dir.isDirectory) return 0L
        val now = System.currentTimeMillis()
        var freed = 0L
        runCatching {
            dir.listFiles()?.forEach { f ->
                val name = f.name
                if (f.isFile && name.startsWith("up_") && name.endsWith(".tmp") &&
                    now - f.lastModified() > maxAgeMs
                ) {
                    runCatching {
                        val size = f.length()
                        if (f.delete()) freed += size
                    }
                }
            }
        }
        return freed
    }
}
