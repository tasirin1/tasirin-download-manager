package com.tasirin.httpdownloadmanager.remote

import com.tasirin.httpdownloadmanager.util.Hex
import com.tasirin.httpdownloadmanager.util.StoragePrefs
import com.tasirin.httpdownloadmanager.util.sha256Hex
import java.io.File
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/** Logika keamanan & validasi server remote yang murni (bisa diuji tanpa Android). */
object ServerSecurity {

    private val UPLOAD_ID_RE = Regex("^[A-Za-z0-9_-]{8,64}$")

    /** Aksi POST dari remote wajib lewat fetch/XHR; header ini memaksa
     *  request lintas situs melewati preflight CORS sebelum sampai server.
     *  GET pembuat ZIP juga dijaga: file ZIP ditulis ke disk (efek samping),
     *  sehingga tanpa header ini bisa dipicu CSRF via tag img/media. */
    fun isStateChangeAllowed(method: String, uri: String, requestedWith: String?): Boolean {
        if (method.equals("POST", ignoreCase = true)) {
            if (uri == "/api/login") return true
            return requestedWith == "XMLHttpRequest"
        }
        if (method.equals("GET", ignoreCase = true) && isHeaderRequiredGetUri(uri)) {
            return requestedWith == "XMLHttpRequest"
        }
        return true
    }

    /** GET yang wajib membawa header XHR: pembuat ZIP (efek samping disk) dan
     *  penerbit token unduh ZIP (tanpanya CSRF bisa mencetak token — walau
     *  token tak bisa dibaca lintas-origin tanpa CORS, tolak sejak awal). */
    fun isHeaderRequiredGetUri(uri: String): Boolean {
        val path = uri.substringBefore('?').trimEnd('/')
        return isZipSideEffectUri(uri) || path == "/api/zip_token"
    }

    /** Endpoint GET yang menulis file ZIP ke cache disk. */
    fun isZipSideEffectUri(uri: String): Boolean {
        val path = uri.substringBefore('?').trimEnd('/')
        return path == "/api/fs_zip" || path == "/api/media_zip"
    }

    /** Redaksi secret pada query untuk log request: token sesi/media, PIN,
     *  token verifikasi upload, token unduh ZIP, dan kredensial generik
     *  disamarkan; ID item/upload bukan rahasia sehingga tetap tampil agar
     *  log bisa dikorelasikan saat debug. Delimiter wajib ([?&] atau awal):
     *  versi lama opsional sehingga `mytoken=x` ikut teredaksi. */
    private val LOG_SECRET_RE =
        Regex("(^|[?&])((?:token|pin|verify|zt|password|secret|auth)=)[^&]*", RegexOption.IGNORE_CASE)

    fun redactQueryForLog(query: String?): String {
        if (query.isNullOrEmpty()) return ""
        return "?" + LOG_SECRET_RE.replace(query.trimStart('?'), "$1$2<redacted>")
    }

    /** Redaksi token pada path untuk log request: `/share/<token>` dan
     *  `/stream_part/<token>` setara password (berlaku 24 jam) sehingga URI
     *  mentah tidak boleh masuk buffer log / ekspor TXT. ID item biasa
     *  (`/file/<id>`) bukan rahasia dan tetap tampil untuk korelasi debug. */
    private val LOG_PATH_TOKEN_RE =
        Regex("(^|/)(share|stream_part)/[^/?#\\s]+", RegexOption.IGNORE_CASE)

    fun redactUriForLog(uri: String): String {
        if (uri.isEmpty()) return uri
        return LOG_PATH_TOKEN_RE.replace(uri, "$1$2/<redacted>")
    }

    /** True bila salah satu segmen path adalah ".." (traversal), tanpa menolak
     *  nama sah yang hanya mengandung titik ganda seperti "my..video".
     *  Pindai manual tanpa split/trim: dipanggil di jalur request upload
     *  sehingga alokasi daftar + string trim per panggilan hanya membebani GC. */
    fun containsTraversalSegment(path: String): Boolean {
        var i = 0
        val n = path.length
        while (i <= n) {
            var j = i
            while (j < n && path[j] != '/') j++
            // Samakan trim(): abaikan whitespace tepi (char <= spasi).
            var s = i
            var e = j
            while (s < e && path[s] <= ' ') s++
            while (e > s && path[e - 1] <= ' ') e--
            if (e - s == 2 && path[s] == '.' && path[s + 1] == '.') return true
            i = j + 1
        }
        return false
    }

    /** ID internal upload hanya boleh token aman; larang separator dan traversal. */
    fun isUploadIdAllowed(id: String): Boolean = UPLOAD_ID_RE.matches(id)

    /** Canonicalisasi daftar root SEKALI (satu I/O per root): hasilnya
     *  dipakai ulang oleh isPathAllowedFast agar request file manager tak
     *  membayar canonicalize N root di tiap dari belasan cek per request.
     *  Root yang gagal di-canonical dibuang (sama seperti cek per-panggilan). */
    fun canonicalRootPaths(roots: List<File>): List<String> =
        roots.mapNotNull { runCatching { it.canonicalFile.absolutePath }.getOrNull() }.distinct()

    /** Varian cepat isPathAllowed dengan root yang sudah di-canonical:
     *  semantik identik (target tetap di-canonical per panggilan — satu path),
     *  hemat canonicalize N root per panggilan. Murni agar bisa di-unit-test. */
    fun isPathAllowedFast(path: String, canonicalRoots: List<String>): Boolean {
        if (path.isBlank()) return false
        val target = runCatching { File(path).canonicalFile.absolutePath }.getOrNull()
            ?: return false
        return canonicalRoots.any { rp -> target == rp || target.startsWith(rp + File.separator) }
    }

    /** Path dalam jangkauan root yang diizinkan (menangkal path traversal). */
    fun isPathAllowed(path: String, roots: List<File>): Boolean =
        isPathAllowedFast(path, canonicalRootPaths(roots))

    /** True bila [canonicalTarget] PERSIS salah satu root yang diizinkan.
     *  Root itu sendiri adalah batas wilayah, bukan isi yang boleh
     *  dimanipulasi: tanpa penjaga ini satu panggilan delete bisa menghapus
     *  utuh root (folder unduhan, atau /storage/emulated/0 saat Full access)
     *  karena isPathAllowed menerima target == root. Murni, bisa diuji. */
    fun isAllowedRootItself(canonicalTarget: String, canonicalRoots: List<String>): Boolean =
        canonicalRoots.any { it == canonicalTarget }

    /** True bila [dir] memuat direktori turunan (termasuk symlink direktori)
     *  yang lokasi kanonikalnya KELUAR dari root yang diizinkan.
     *  `deleteRecursively` mengikuti symlink direktori anak, jadi pra-cek
     *  kanonikal hanya pada direktori teratas tidak cukup: symlink
     *  bersarang bisa menyeret penghapusan ke target luar root (issue
     *  #159). Symlink FILE tidak dinilai — menghapusnya hanya melepas
     *  tautan, tidak menyentuh target. Ketidakmampuan memverifikasi satu
     *  pun anak dianggap berbahaya (gagal-tertutup). */
    fun hasEscapingSymlink(dir: File, canonicalRoots: List<String>): Boolean {
        val visited = HashSet<String>()
        val stack = ArrayDeque<File>()
        stack.add(dir)
        while (stack.isNotEmpty()) {
            val cur = stack.removeLast()
            val children = runCatching { cur.listFiles() }.getOrNull() ?: continue
            for (child in children) {
                if (!child.isDirectory) continue
                val canon = runCatching { child.canonicalFile.absolutePath }.getOrNull()
                    ?: return true
                if (!isPathAllowedFast(canon, canonicalRoots)) return true
                if (visited.add(canon)) stack.add(child)
            }
        }
        return false
    }

    /** Path adalah induk (strict) dari salah satu root? Dipakai untuk listing
     *  browse-only di File Manager: folder di atas root (mis. /storage di atas
     *  /storage/emulated/0/Download) boleh dilihat isinya, tapi aksi tulis
     *  tetap hanya untuk path yang lolos [isPathAllowed]. */
    fun isBrowseableAncestor(path: String, roots: List<File>): Boolean =
        isBrowseableAncestorFast(path, canonicalRootPaths(roots))

    /** Varian cepat isBrowseableAncestor (root sudah di-canonical). */
    fun isBrowseableAncestorFast(path: String, canonicalRoots: List<String>): Boolean {
        if (path.isBlank()) return false
        val target = runCatching { File(path).canonicalFile.absolutePath }.getOrNull()
            ?: return false
        return canonicalRoots.any { rp -> rp.startsWith(target + File.separator) }
    }

    /** Validasi tujuan tulis dari remote: blank memakai default, `m:` hanya
     * relative path MediaStore, sedangkan path file harus di dalam root. */
    fun isRemoteDestinationAllowed(path: String, roots: List<File>): Boolean {
        val clean = path.trim()
        if (clean.isEmpty()) return true
        if (clean.startsWith("m:")) {
            val relative = clean.removePrefix("m:").trim().trim('/')
            if (relative.isEmpty()) return true
            if (relative.contains('\\')) return false
            return relative.split('/').none { it.isEmpty() || it == "." || it == ".." }
        }
        // Trim ulang setelah prefix dilepas: "f: /sdcard/x" sah dan
        // tidak boleh ditolak hanya karena spasi pemisah prefix.
        val filePath = if (clean.startsWith("f:")) clean.removePrefix("f:").trim() else clean
        return isPathAllowed(filePath, roots)
    }

    /** true bila ada segmen kosong/./.. — validasi sekali jalan untuk
     *  varian fast (jalur request, tanpa alokasi daftar antara). */
    private fun hasBadSegment(s: String): Boolean {
        var i = 0
        val n = s.length
        while (i <= n) {
            var j = i
            while (j < n && s[j] != '/') j++
            val len = j - i
            if (len == 0 || (len == 1 && s[i] == '.') ||
                (len == 2 && s[i] == '.' && s[i + 1] == '.')
            ) return true
            i = j + 1
        }
        return false
    }

    /** Varian cepat isRemoteDestinationAllowed (root sudah di-canonical). */
    fun isRemoteDestinationAllowedFast(path: String, canonicalRoots: List<String>): Boolean {
        val clean = path.trim()
        if (clean.isEmpty()) return true
        if (clean.startsWith("m:")) {
            val relative = clean.removePrefix("m:").trim().trim('/')
            if (relative.isEmpty()) return true
            if (relative.contains('\\')) return false
            if (hasBadSegment(relative)) return false
            return true
        }
        val filePath = if (clean.startsWith("f:")) clean.removePrefix("f:").trim() else clean
        return isPathAllowedFast(filePath, canonicalRoots)
    }

    /** Path relatif MediaStore hanya boleh dipakai bila berada di bawah root
     *  file yang sama; mode akses penuh tetap memvalidasi bentuk path. */
    fun isMediaStorePathAllowed(
        relativePath: String,
        roots: List<File>,
        fullAccess: Boolean,
        mediaRoot: String = "/storage/emulated/0"
    ): Boolean {
        val clean = relativePath.trim().trim('/')
        if (clean.isEmpty()) return fullAccess
        if (clean.contains('\\') || clean.split('/').any { it.isEmpty() || it == "." || it == ".." }) {
            return false
        }
        if (fullAccess) return true
        val virtualPath = "$mediaRoot/$clean"
        return isPathAllowed(virtualPath, roots)
    }

    /** Varian cepat isMediaStorePathAllowed (root sudah di-canonical). */
    fun isMediaStorePathAllowedFast(
        relativePath: String,
        canonicalRoots: List<String>,
        fullAccess: Boolean,
        mediaRoot: String = "/storage/emulated/0"
    ): Boolean {
        val clean = relativePath.trim().trim('/')
        if (clean.isEmpty()) return fullAccess
        if (clean.contains('\\') || hasBadSegment(clean)) {
            return false
        }
        if (fullAccess) return true
        val virtualPath = "$mediaRoot/$clean"
        return isPathAllowedFast(virtualPath, canonicalRoots)
    }

    /** Token stream parsial berumur pendek (id.expiry.signature HMAC-SHA256).
     *  HMAC (bukan SHA-256 polos) agar tak rentan length-extension. */
    fun createPartialToken(itemId: String, expiresAt: Long, secret: String): String {
        val payload = "$itemId.$expiresAt"
        // Domain "partial:" memisahkan HMAC dari token verify-upload yang
        // berbagi secret + format kawat sama; tanpa ini token satu jalur
        // lolos validasi jalur lain bila ID tabrakan.
        return "$payload.${hmacSha256Hex(secret, "partial:$payload")}"
    }

    // Batas panjang token: HMAC dihitung setelah validasi bentuk agar
    // string raksasa tidak memaksa alokasi/split mahal (DoS murah via LAN).
    private const val MAX_TOKEN_LEN = 512

    fun isPartialTokenValid(token: String, itemId: String, now: Long, secret: String): Boolean {
        if (token.length > MAX_TOKEN_LEN || itemId.length > MAX_TOKEN_LEN) return false
        val parts = token.split('.')
        if (parts.size != 3 || parts[0] != itemId) return false
        val expiresAt = parts[1].toLongOrNull() ?: return false
        if (expiresAt < now) return false
        val expected = hmacSha256Hex(secret, "partial:${parts[0]}.${parts[1]}")
        return StoragePrefs.constantEquals(parts[2], expected)
    }

    /** Token status upload mencegah enumerasi ID oleh klien lain di LAN. */
    fun createUploadVerifyToken(uploadId: String, expiresAt: Long, secret: String): String {
        val payload = "$uploadId.$expiresAt"
        return "$payload.${hmacSha256Hex(secret, "upload:$payload")}"
    }

    fun isUploadVerifyTokenValid(
        token: String?,
        uploadId: String,
        now: Long,
        secret: String
    ): Boolean {
        val cleanToken = token?.trim().orEmpty()
        if (cleanToken.length > MAX_TOKEN_LEN || uploadId.length > MAX_TOKEN_LEN) return false
        val parts = cleanToken.split('.')
        if (parts.size != 3 || parts[0] != uploadId) return false
        val expiresAt = parts[1].toLongOrNull() ?: return false
        if (expiresAt < now) return false
        val expected = hmacSha256Hex(secret, "upload:${parts[0]}.${parts[1]}")
        return StoragePrefs.constantEquals(parts[2], expected)
    }

    /** HMAC-SHA256 hex murni JVM (tanpa Android) supaya bisa diuji unit. */
    internal fun hmacSha256Hex(secret: String, data: String): String {
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(secret.toByteArray(Charsets.UTF_8), "HmacSHA256"))
        return Hex.encode(mac.doFinal(data.toByteArray(Charsets.UTF_8)))
    }

    /** Paginasi galeri murni: clamp mentah ke >=0 agar endpoint tak duplikasi logika. */
    fun clampGalleryPage(raw: String?): Int =
        (raw?.trim()?.toIntOrNull() ?: 0).coerceAtLeast(0)

    /** Batas scan galeri: query nama pakai penuh, selain itu cukup 2 halaman ke depan. */
    fun galleryScanLimit(page: Int, pageSize: Int, maxEntries: Int, hasQuery: Boolean): Int {
        if (hasQuery) return maxEntries
        val safePage = page.coerceAtLeast(0).coerceAtMost(maxEntries)
        val safeSize = pageSize.coerceIn(1, maxEntries)
        return ((safePage * safeSize) + (safeSize * 2)).coerceAtMost(maxEntries)
    }

    /** Lock PIN masih aktif: percobaan login ditolak. */
    fun isPinLocked(now: Long, lockUntil: Long): Boolean = now < lockUntil

    /** Waktu lock baru bila percobaan gagal mencapai ambang; 0 = belum lock. */
    fun pinLockUntilAfter(failures: Int, maxAttempts: Int, lockMs: Long, now: Long): Long {
        if (failures < maxAttempts) return 0L
        return now + lockMs
    }

    /** Escape argumen LIKE SQLite (`\`, `%`, `_`): path dari request
     *  tidak boleh menjadi wildcard yang menjaring folder lain. Dipakai
     *  bersama klausa `ESCAPE '\\'`. */
    fun escapeLike(s: String): String {
        val sb = StringBuilder(s.length)
        for (c in s) {
            if (c == '\\' || c == '%' || c == '_') sb.append('\\')
            sb.append(c)
        }
        return sb.toString()
    }

    /** Offset chunk upload valid: non-negatif dan tidak melebihi batas file. */
    fun isChunkOffsetAllowed(offset: Long, maxFileBytes: Long): Boolean = offset in 0..maxFileBytes

    /** URI dokumen hanya sah bila masih berada di tree berizin yang dipersistenkan.
     *  Tanpa ini, authority SAF tidak boleh dipakai sebagai izin otomatis. */
    fun isSafUriAllowed(
        authority: String?,
        uriPath: String?,
        permittedRoots: List<Pair<String, String>>
    ): Boolean {
        val cleanAuthority = authority?.takeIf { it.isNotEmpty() } ?: return false
        val cleanPath = uriPath?.takeIf { it.isNotEmpty() } ?: return false
        return permittedRoots.any { (rootAuthority, rootPath) ->
            val cleanRoot = rootPath.trimEnd('/')
            cleanAuthority == rootAuthority && cleanRoot.isNotEmpty() && (
                cleanPath == cleanRoot || cleanPath.startsWith("$cleanRoot/")
            )
        }
    }

    /** Token share kedaluwarsa (expiresAt == now masih dianggap valid). */
    fun isShareExpired(expiresAt: Long, now: Long): Boolean = expiresAt < now

    /** ID pendek publik untuk satu token share (issue #259): 12 hex
     *  pertama SHA-256(token). Token adalah rahasia pembawa — daftar
     *  share di UI hanya membawa id turunan ini, dan pencabutan per id
     *  mencari token yang hash-nya cocok. 48 bit cukup unik untuk
     *  maksimal 256 token aktif. */
    fun shareShortId(token: String): String = sha256Hex(token).take(12)

    /** Ambil nilai cookie persis per nama dari header Cookie. Pencarian
     *  substring mentah (indexOf) bisa cocok di dalam nama/nilai cookie lain
     *  (mis. "adm_pin=" mengandung "dm_pin=") sehingga auth gagal atau salah;
     *  parse per segmen yang dipisah ';' agar hanya nama yang pas yang dipakai. */
    fun sessionCookieValue(header: String?, name: String): String? {
        if (header.isNullOrBlank() || name.isBlank()) return null
        val prefix = "${name.trim()}="
        // Loop tanpa split() — hindari alokasi list per request HTTP.
        var start = 0
        while (start <= header.length) {
            val semi = header.indexOf(';', start)
            val end = if (semi >= 0) semi else header.length
            if (end > start) {
                val seg = header.substring(start, end).trim()
                if (seg.startsWith(prefix)) {
                    return seg.substring(prefix.length).trim().takeIf { it.isNotEmpty() }
                }
            }
            if (semi < 0) break
            start = semi + 1
        }
        return null
    }

    /** Validasi nama file dari remote/native: tidak kosong, tanpa separator
     *  path, bukan traversal (".." / "../" / "..\\"), dan tanpa titik/spasi
     *  di akhir (filesystem FAT/SD memangkasnya sehingga "a. " bertabrakan
     *  dengan "a" dan menimpa file lain). Satu definisi di sini agar endpoint
     *  tidak memakai aturan inline yang berbeda-beda. */
    fun isFileNameValid(name: String): Boolean =
        name.isNotBlank() && '/' !in name && '\\' !in name &&
        name != "." && name != ".." && !name.startsWith("../") && !name.startsWith("..\\") &&
        !name.endsWith(" ") && !name.endsWith(".")
}
