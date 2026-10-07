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
    // Nilai awal = "sudah lama berjalan": 0 berarti "baru jalan saat boot"
    // sehingga 5 menit pertama setelah reboot pembersihan selalu ter-throttle
    // padahal belum pernah jalan — tepat saat autostart-boot paling butuh.
    @Volatile private var lastRunElapsed = -MIN_INTERVAL_MS

    /** Murni agar bisa diuji unit: true bila jarak sejak run terakhir cukup. */
    internal fun isThrottleOpen(nowElapsed: Long, lastRun: Long): Boolean =
        nowElapsed - lastRun >= MIN_INTERVAL_MS

    /** Jalankan bila free space di bawah ambang; kembalikan byte yang dibebaskan. */
    fun runIfLow(context: Context, items: List<DownloadItem>): Long {
        val saver = FileSaver(context)
        val free = saver.destinationFreeBytes()
        if (free > LOW_THRESHOLD_BYTES) return 0L
        synchronized(throttleLock) {
            val now = android.os.SystemClock.elapsedRealtime()
            if (!isThrottleOpen(now, lastRunElapsed)) return 0L
            lastRunElapsed = now
        }
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
