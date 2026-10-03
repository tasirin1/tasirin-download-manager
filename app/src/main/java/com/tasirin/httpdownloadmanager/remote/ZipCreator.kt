package com.tasirin.httpdownloadmanager.remote

import androidx.core.net.toUri
import android.content.ContentUris
import android.content.Context
import android.os.Build
import android.provider.MediaStore
import com.tasirin.httpdownloadmanager.util.MediaLibrary
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/** Pembuat arsip ZIP (folder filesystem + folder media Android 10+). */
object ZipCreator {

    /** Kedalaman maksimum rekursi ZIP (anti symlink-cycle). */
    private const val MAX_ZIP_DEPTH = 64

    /** Batas total entri per arsip: folder raksasa di TV box bisa memblokir
     *  thread HTTP ber menit-menit / OOM bila tak dibatasi. */
    const val MAX_ZIP_ENTRIES = 5000

    /** Akumulator jumlah entri yang sudah ditulis (diteruskan saat rekursi). */
    class ZipBudget(var count: Int = 0) {
        fun tryTake(): Boolean {
            if (count >= MAX_ZIP_ENTRIES) return false
            count++
            return true
        }
    }

    /** Nama dari filesystem/MediaStore tidak boleh dipakai mentah sebagai
     *  path ZIP; normalisasi mencegah Zip Slip di extractor pihak ketiga. */
    internal fun safeEntryPath(path: String): String {
        // Satu pass StringBuilder: tanpa replace/split/map/join antara
        // (4 koleksi per file x 5000 entri). Hasil identik: '\\' -> '/',
        // segmen kosong/./.. dibuang, kontrol C0 -> '_'.
        val out = StringBuilder(path.length)
        val n = path.length
        var i = 0
        while (i < n) {
            while (i < n && (path[i] == '/' || path[i] == '\\')) i++
            if (i >= n) break
            var j = i
            while (j < n && path[j] != '/' && path[j] != '\\') j++
            val isDot = j - i == 1 && path[i] == '.'
            val isDotDot = j - i == 2 && path[i] == '.' && path[i + 1] == '.'
            if (!isDot && !isDotDot) {
                if (out.isNotEmpty()) out.append('/')
                var k = i
                while (k < j) {
                    val c = path[k]
                    out.append(if (c.code in 0x00..0x1F) '_' else c)
                    k++
                }
            }
            i = j
        }
        return out.toString()
    }

    // isFileAllowed wajib terakhir agar trailing lambda tetap jalan.
    fun zipFile(
        zos: ZipOutputStream,
        file: File,
        prefix: String,
        depth: Int = 0,
        seen: MutableSet<String> = mutableSetOf(),
        budget: ZipBudget = ZipBudget(),
        isFileAllowed: (String) -> Boolean
    ) {
        // Izin dicek terhadap canonical path: symlink file di dalam root
        // yang menunjuk ke luar root lolos bila hanya absolutePath (lokasi
        // link) yang diperiksa, lalu inputStream() membaca target luar.
        val canonicalForAllow = runCatching { file.canonicalPath }.getOrNull()
        if (canonicalForAllow == null || !isFileAllowed(canonicalForAllow)) return
        // Symlink melingkar (folder menunjuk leluhurnya) membuat rekursi tak
        // berujung -> StackOverflow; hentikan via canonical + batas kedalaman.
        if (depth > MAX_ZIP_DEPTH) return
        if (file.isDirectory) {
            val canonical = runCatching { file.canonicalPath }.getOrNull() ?: return
            if (!seen.add(canonical)) return
        }
        val entryPath = safeEntryPath(if (prefix.isEmpty()) file.name else "$prefix/${file.name}")
        if (!budget.tryTake()) return
        if (file.isDirectory) {
            val children = runCatching { file.listFiles() }.getOrNull()
            if (children.isNullOrEmpty()) {
                zos.putNextEntry(ZipEntry("$entryPath/"))
                zos.closeEntry()
                return
            }
            children.sortedWith(
                Comparator { a, b -> a.name.compareTo(b.name, ignoreCase = true) }
            ).forEach { child ->
                if (budget.count >= MAX_ZIP_ENTRIES) return@forEach
                zipFile(zos, child, entryPath, depth + 1, seen, budget, isFileAllowed)
            }
        } else if (file.isFile) {
            runCatching {
                zos.putNextEntry(ZipEntry(entryPath))
                try {
                    file.inputStream().use { it.copyTo(zos) }
                } finally {
                    runCatching { zos.closeEntry() }
                }
            }
        }
    }

    /** ZIP daftar token media (dipakai /api/media_zip: unduh banyak foto/video,
     *  file, atau folder filesystem secara rekursif). */
    fun zipTokens(
        zos: ZipOutputStream,
        tokens: List<String>,
        context: Context,
        isFileAllowed: (String) -> Boolean,
        isUriAllowed: (android.net.Uri) -> Boolean = { false }
    ) {
        val used = mutableMapOf<String, Int>()
        val budget = ZipBudget()
        tokens.forEach { token ->
            if (budget.count >= MAX_ZIP_ENTRIES) return
            val raw = MediaLibrary.decodeToken(token) ?: return@forEach
            runCatching {
                val name: String
                val input: java.io.InputStream?
                if (raw.startsWith("f:")) {
                    val f = File(raw.removePrefix("f:"))
                    val canon = runCatching { f.canonicalPath }.getOrNull()
                    if (canon == null || !isFileAllowed(canon)) return@forEach
                    if (f.isDirectory) {
                        val root = uniqueZipName(f.name, used)
                        val children = runCatching { f.listFiles() }.getOrNull() ?: return@runCatching
                        children.sortedWith(
                            Comparator { a, b -> a.name.compareTo(b.name, ignoreCase = true) }
                        ).forEach { child -> if (budget.count >= MAX_ZIP_ENTRIES) return@forEach
                            zipFile(zos, child, root, depth = 1, seen = mutableSetOf(), isFileAllowed = isFileAllowed, budget = budget) }
                        return@runCatching
                    }
                    name = f.name
                    val canonFile = runCatching { f.canonicalPath }.getOrNull()
                    input = if (f.isFile && canonFile != null && isFileAllowed(canonFile)) f.inputStream() else null
                } else {
                    if (!raw.startsWith("u:")) return@forEach
                    val uri = raw.removePrefix("u:").toUri()
                    if (!isUriAllowed(uri)) return@forEach
                    name = displayNameFor(context, uri)
                    input = context.contentResolver.openInputStream(uri)
                }
                if (input == null) {
                    return@runCatching
                }
                val entry = uniqueZipName(safeEntryPath(name).ifEmpty { "file" }, used)
                runCatching {
                    zos.putNextEntry(ZipEntry(entry))
                    try {
                        input.use { it.copyTo(zos) }
                    } finally {
                        runCatching { zos.closeEntry() }
                    }
                }
            }
        }
    }

    private fun displayNameFor(context: Context, uri: android.net.Uri): String = runCatching {
        val projection = arrayOf(MediaStore.MediaColumns.DISPLAY_NAME)
        context.contentResolver.query(uri, projection, null, null, null)?.use { c ->
            if (c.moveToFirst()) {
                val idx = c.getColumnIndex(MediaStore.MediaColumns.DISPLAY_NAME)
                if (idx >= 0) c.getString(idx) else null
            } else {
                null
            }
        }
    }.getOrNull()?.takeIf { it.isNotBlank() }
        ?: uri.lastPathSegment?.substringAfterLast('/')
        ?: "file"

    internal fun uniqueZipName(base: String, used: MutableMap<String, Int>): String {
        val count = used.getOrDefault(base, 0)
        used[base] = count + 1
        if (count == 0) return base
        val dot = base.lastIndexOf('.')
        return if (dot > 0) {
            base.substring(0, dot) + " ($count)" + base.substring(dot)
        } else {
            "$base ($count)"
        }
    }

    fun zipMedia(zos: ZipOutputStream, relative: String, context: Context) {
        if (Build.VERSION.SDK_INT < 29) return
        val base = relative.trim('/')
        val folder = base + "/"
        val resolver = context.contentResolver
        val collection = MediaLibrary.mediaCollectionForRoot(base)
        val projection = arrayOf(
            MediaStore.MediaColumns._ID,
            MediaStore.MediaColumns.DISPLAY_NAME,
            MediaStore.MediaColumns.RELATIVE_PATH
        )
        // folder dari request: escape wildcard LIKE agar "%"/"_" di nama
        // folder tidak menjaring direktori lain di luar folder terpilih.
        val likeArg = ServerSecurity.escapeLike(folder) + "%"
        // Batas entri + dedup nama seperti zipTokens/zipFile: folder media
        // raksasa tanpa budget memblokir thread HTTP ber menit-menit, dan
        // DISPLAY_NAME duplikat (diizinkan MediaStore) membuat ZipException
        // duplikat sehingga file kedua hilang diam-diam.
        val used = mutableMapOf<String, Int>()
        val budget = ZipBudget()
        runCatching {
            resolver.query(
                collection, projection,
                "${MediaStore.MediaColumns.RELATIVE_PATH} LIKE ? ESCAPE '\\'",
                arrayOf(likeArg), null
            )?.use { c ->
                val iId = c.getColumnIndexOrThrow(MediaStore.MediaColumns._ID)
                val iName = c.getColumnIndexOrThrow(MediaStore.MediaColumns.DISPLAY_NAME)
                val iRel = c.getColumnIndexOrThrow(MediaStore.MediaColumns.RELATIVE_PATH)
                while (c.moveToNext()) {
                    if (!budget.tryTake()) break
                    val relPath = c.getString(iRel) ?: continue
                    val name = c.getString(iName) ?: continue
                    if (!relPath.startsWith(folder)) continue
                    val dirPart = relPath.removePrefix(folder).trimEnd('/')
                    val rawEntry = safeEntryPath(if (dirPart.isEmpty()) name else "$dirPart/$name")
                    if (rawEntry.isEmpty()) continue
                    val entry = uniqueZipName(rawEntry, used)
                    resolver.openInputStream(
                        ContentUris.withAppendedId(collection, c.getLong(iId))
                    )?.use { input ->
                        runCatching {
                            zos.putNextEntry(ZipEntry(entry))
                            try {
                                input.copyTo(zos)
                            } finally {
                                runCatching { zos.closeEntry() }
                            }
                        }
                    }
                }
            }
        }
    }
}
