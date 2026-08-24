package com.tasirin.httpdownloadmanager.remote

import com.tasirin.httpdownloadmanager.util.StoragePrefs
import com.tasirin.httpdownloadmanager.util.sha256Hex
import java.io.File

/** Logika keamanan & validasi server remote yang murni (bisa diuji tanpa Android). */
object ServerSecurity {

    private val UPLOAD_ID_RE = Regex("^[A-Za-z0-9_-]{8,64}$")

    /** ID internal upload hanya boleh token aman; larang separator dan traversal. */
    fun isUploadIdAllowed(id: String): Boolean = UPLOAD_ID_RE.matches(id)

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

    /** Path adalah induk (strict) dari salah satu root? Dipakai untuk listing
     *  browse-only di File Manager: folder di atas root (mis. /storage di atas
     *  /storage/emulated/0/Download) boleh dilihat isinya, tapi aksi tulis
     *  tetap hanya untuk path yang lolos [isPathAllowed]. */
    fun isBrowseableAncestor(path: String, roots: List<File>): Boolean {
        if (path.isBlank()) return false
        val target = runCatching { File(path).canonicalFile.absolutePath }.getOrNull()
            ?: return false
        return roots.any { root ->
            val rp = runCatching { root.canonicalFile.absolutePath }.getOrNull()
                ?: return@any false
            rp.startsWith(target + File.separator)
        }
    }

    /** Validasi tujuan tulis dari remote: blank memakai default, `m:` hanya
     * relative path MediaStore, sedangkan path file harus di dalam root. */
    fun isRemoteDestinationAllowed(path: String, roots: List<File>): Boolean {
        val clean = path.trim()
        if (clean.isEmpty()) return true
        if (clean.startsWith("m:")) {
            val relative = clean.removePrefix("m:")
            if (relative.isEmpty()) return true
            if (relative.contains('\\')) return false
            return relative.split('/').none { it.isEmpty() || it == "." || it == ".." }
        }
        val filePath = if (clean.startsWith("f:")) clean.removePrefix("f:") else clean
        return isPathAllowed(filePath, roots)
    }

    /** Path relatif MediaStore hanya boleh dipakai bila berada di bawah root
     *  file yang sama; mode akses penuh tetap memvalidasi bentuk path. */
    fun isMediaStorePathAllowed(
        relativePath: String,
        roots: List<File>,
        fullAccess: Boolean
    ): Boolean {
        val clean = relativePath.trim().trim('/')
        if (clean.isEmpty()) return fullAccess
        if (clean.contains('\\') || clean.split('/').any { it.isEmpty() || it == "." || it == ".." }) {
            return false
        }
        if (fullAccess) return true
        val virtualPath = "/storage/emulated/0/$clean"
        return isPathAllowed(virtualPath, roots)
    }

    /** Token stream parsial berumur pendek (id.expiry.signature). */
    fun createPartialToken(itemId: String, expiresAt: Long, secret: String): String {
        val payload = "$itemId.$expiresAt"
        return "$payload.${sha256Hex(payload + ":" + secret)}"
    }

    fun isPartialTokenValid(token: String, itemId: String, now: Long, secret: String): Boolean {
        val parts = token.split('.')
        if (parts.size != 3 || parts[0] != itemId) return false
        val expiresAt = parts[1].toLongOrNull() ?: return false
        if (expiresAt < now) return false
        val expected = sha256Hex("${parts[0]}.${parts[1]}:$secret")
        return StoragePrefs.constantEquals(parts[2], expected)
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
