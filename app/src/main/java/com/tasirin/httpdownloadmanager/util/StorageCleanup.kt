package com.tasirin.httpdownloadmanager.util

import android.content.Context
import com.tasirin.httpdownloadmanager.App
import com.tasirin.httpdownloadmanager.data.DownloadItem
import java.io.File

/** Pembersihan otomatis saat storage menipis, supaya download tidak mati
 *  di tengah jalan karena "storage penuh". */
object StorageCleanup {

    /** Ambang free space (byte) yang memicu pembersihan. */
    const val LOW_THRESHOLD_BYTES = 512L * 1024 * 1024

    /** Jarak antar pembersihan minimum (menghindari I/O boros). */
    private const val MIN_INTERVAL_MS = 5 * 60 * 1000L

    /** Umur maksimal sisa upload chunk (up_*.tmp) di cache. */
    private const val UPLOAD_TMP_MAX_AGE_MS = 24L * 60 * 60 * 1000

    @Volatile private var lastRunAt = 0L

    /** Jalankan bila free space di bawah ambang; kembalikan byte yang dibebaskan. */
    fun runIfLow(
        context: Context,
        items: List<DownloadItem>,
        now: Long = System.currentTimeMillis()
    ): Long {
        if (now - lastRunAt < MIN_INTERVAL_MS) return 0L
        val free = FileSaver(context).destinationFreeBytes()
        if (free > LOW_THRESHOLD_BYTES) return 0L
        lastRunAt = now
        var freed = 0L
        freed += FileSaver(context).cleanupOrphanPartials(items)
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
