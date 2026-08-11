package com.tasirin.httpdownloadmanager.remote

import java.io.File

/** Logika keamanan & validasi server remote yang murni (bisa diuji tanpa Android). */
object ServerSecurity {

    /** Path dalam jangkauan root yang diizinkan (menangkal path traversal). */
    fun isPathAllowed(path: String, roots: List<File>): Boolean {
        if (path.isBlank()) return false
        val target = runCatching { File(path).canonicalFile.absolutePath }.getOrNull()
            ?: return false
        return roots.any { root ->
            val rp = runCatching { root.canonicalFile.absolutePath }.getOrNull()
                ?: return@any false
            target == rp || target.startsWith(rp + File.separator)
        }
    }

    /** Lock PIN masih aktif: percobaan login ditolak. */
    fun isPinLocked(now: Long, lockUntil: Long): Boolean = now < lockUntil

    /** Waktu lock baru bila percobaan gagal mencapai ambang; 0 = belum lock. */
    fun pinLockUntilAfter(failures: Int, maxAttempts: Int, lockMs: Long, now: Long): Long {
        if (failures < maxAttempts) return 0L
        return now + lockMs
    }

    /** Offset chunk upload valid: non-negatif dan tidak melebihi batas file. */
    fun isChunkOffsetAllowed(offset: Long, maxFileBytes: Long): Boolean = offset in 0..maxFileBytes

    /** Token share kedaluwarsa (expiresAt == now masih dianggap valid). */
    fun isShareExpired(expiresAt: Long, now: Long): Boolean = expiresAt < now
}
