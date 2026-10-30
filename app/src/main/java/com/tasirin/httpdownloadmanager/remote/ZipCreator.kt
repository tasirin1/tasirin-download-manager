package com.tasirin.httpdownloadmanager.remote

import androidx.core.net.toUri
import android.content.ContentUris
import android.content.Context
import android.os.Build
import android.provider.MediaStore
import com.tasirin.httpdownloadmanager.util.MediaLibrary
import java.io.File
import java.io.IOException
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/** Pembuat arsip ZIP (folder filesystem + folder media Android 10+). */
object ZipCreator {

    /** Kedalaman maksimum rekursi ZIP (anti symlink-cycle). */
    private const val MAX_ZIP_DEPTH = 64

    /** Buffer salin 64KB (default copyTo 8KB boros syscall untuk file besar). */
    private const val IO_BUF = 64 * 1024

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
        usedNames: MutableMap<String, Int> = mutableMapOf(),
        isFileAllowed: (String) -> Boolean
    ) {
        // Izin dicek terhadap canonical path: symlink file di dalam root
        // yang menunjuk ke luar root lolos bila hanya absolutePath (lokasi
        // link) yang diperiksa, lalu inputStream() membaca target luar.
        val canonicalForAllow = runCatching { file.canonicalPath }.getOrNull()
        if (canonicalForAllow == null || !isFileAllowed(canonicalForAllow)) return
        // Symlink melingkar (folder menunjuk leluhurnya) membuat rekursi tak
        // berujung -> StackOverflow; hentikan via canonical + batas kedalaman.
        // Pakai ulang canonical di atas: resolusi kedua untuk File yang sama
        // hanya mengulang syscall tanpa informasi baru.
        if (depth > MAX_ZIP_DEPTH) return
        if (file.isDirectory) {
            if (!seen.add(canonicalForAllow)) return
        }
        // Dedupe seperti zipTokens/zipMedia: dua nama yang ternormalisasi
        // sama (kontrol C0 -> '_') membuat ZipException duplikat — cabang
        // file menelan diam-diam, cabang direktori menggugurkan seluruh ZIP.
        val entryPath = uniqueZipName(
            safeEntryPath(if (prefix.isEmpty()) file.name else "$prefix/${file.name}")
                .ifEmpty { return },
            usedNames
        )
        if (!budget.tryTake()) return
        if (file.isDirectory) {
            // listFiles null = galat I/O, BUKAN folder kosong: menyamakan
            // keduanya mencatat folder berisi sebagai entri kosong
            // (issue #846). Gagalkan pembuatan ZIP secara terbuka.
            val children = try {
                file.listFiles()
            } catch (e: Exception) {
                throw IOException("Cannot list folder: " + file.name, e)
            } ?: throw IOException("Cannot list folder: " + file.name)
            if (children.isEmpty()) {
                zos.putNextEntry(ZipEntry("$entryPath/"))
                zos.closeEntry()
                return
            }
            children.sortedWith(
                Comparator { a, b -> a.name.compareTo(b.name, ignoreCase = true) }
            ).forEach { child ->
                if (budget.count >= MAX_ZIP_ENTRIES) return@forEach
                zipFile(zos, child, entryPath, depth + 1, seen, budget, usedNames, isFileAllowed)
            }
        } else if (file.isFile) {
            // Kegagalan baca sumber TIDAK ditelan (issue #846): entri
            // tanpa ukuran/CRC di muka memakai data descriptor, jadi
            // salinan yang putus di tengah difinalkan closeEntry sebagai
            // berkas utuh. Galat dibiarkan naik; createTempZip membuang
            // seluruh ZIP dan pemanggil menerima kegagalan yang jujur.
            zos.putNextEntry(ZipEntry(entryPath))
            try {
                file.inputStream().use { it.copyTo(zos, IO_BUF) }
            } finally {
                runCatching { zos.closeEntry() }
            }
        }
    }

    /** ZIP daftar token media (dipakai /api/media_zip: unduh banyak foto/video,
     *  file, atau folder filesystem secara rekursif). */
    // Mengembalikan jumlah token yang sungguh menyumbang entri
    // (issue #864): keputusan lewati di dalam sini (token rusak, tak
    // diizinkan, gagal buka #857, budget habis) sebelumnya hilang
    // tanpa jejak sehingga pemanggil tak bisa melaporkan yang gugur.
    fun zipTokens(
        zos: ZipOutputStream,
        tokens: List<String>,
        context: Context,
        isFileAllowed: (String) -> Boolean,
        isUriAllowed: (android.net.Uri) -> Boolean = { false }
    ): Int {
        val used = mutableMapOf<String, Int>()
        val budget = ZipBudget()
        var contributed = 0
        tokens.forEach { token ->
            if (budget.count >= MAX_ZIP_ENTRIES) return
            val raw = MediaLibrary.decodeToken(token) ?: return@forEach
            // Galat I/O di dalam blok (baca sumber putus, listFiles gagal)
            // TIDAK ditelan runCatching ini: ia dilempar ulang sesudah
            // blok agar seluruh ZIP gagal secara terbuka (issue #846).
            // Keputusan lewati-token lain (izin, token rusak) tetap di
            // dalam blok seperti sebelumnya.
            val outcome = runCatching {
                val name: String
                val input: java.io.InputStream?
                if (raw.startsWith("f:")) {
                    val f = File(raw.removePrefix("f:"))
                    val canon = runCatching { f.canonicalPath }.getOrNull()
                    if (canon == null || !isFileAllowed(canon)) return@forEach
                    if (f.isDirectory) {
                        // Nama folder mentah tak boleh jadi prefix entri: folder
                        // bernama ".."/backslash/karakter kontrol lolos sebagai
                        // path traversal di extractor pihak ketiga (cabang file
                        // tunggal di bawah sudah lewat safeEntryPath).
                        val root = tokenDirRootName(f.name, used)
                        // listFiles gagal = galat I/O, bukan folder kosong
                        // (issue #846): lempar agar ditangkap outcome dan
                        // dilempar ulang sebagai kegagalan ZIP.
                        val children = try {
                            f.listFiles()
                        } catch (e: Exception) {
                            throw IOException("Cannot list folder: " + f.name, e)
                        } ?: throw IOException("Cannot list folder: " + f.name)
                        // seen + usedNames dipakai bersama seluruh sibling: map
                        // fresh per child membuat nama ternormalisasi kembar
                        // (kontrol C0 -> '_') menimpa entri duplikat sehingga
                        // ZipException lalu file hilang diam-diam, dan symlink
                        // ke target sama di-zip berulang sia-sia. Folder akar
                        // sendiri ditanam ke seen sejak awal: tanpa itu
                        // symlink di dalam yang menunjuk balik ke akar lolos
                        // sebagai belum-dikunjungi dan seluruh isi folder
                        // tersalin lagi satu tingkat (issue #454).
                        val dirSeen = mutableSetOf(canon)
                        val dirUsed = mutableMapOf<String, Int>()
                        children.sortedWith(
                            Comparator { a, b -> a.name.compareTo(b.name, ignoreCase = true) }
                        ).forEach { child -> if (budget.count >= MAX_ZIP_ENTRIES) return@forEach
                            zipFile(zos, child, root, depth = 1, seen = dirSeen, isFileAllowed = isFileAllowed, budget = budget, usedNames = dirUsed) }
                        contributed++
                        return@runCatching
                    }
                    // canon + izin sudah diverifikasi di atas untuk File yang
                    // sama: tanpa resolusi canonical dan cek izin kedua.
                    name = f.name
                    // Gagal MEMBUKA sumber = token terlewat (issue #857):
                    // berkas yang hilang di antara listing dan buka tak
                    // boleh membunuh seluruh batch. Yang menggagalkan
                    // ZIP hanya gagal MENYALIN di tengah (issue #846).
                    input = if (f.isFile) {
                        runCatching { f.inputStream() }.getOrNull()
                    } else null
                } else {
                    if (!raw.startsWith("u:")) return@forEach
                    val uri = raw.removePrefix("u:").toUri()
                    if (!isUriAllowed(uri)) return@forEach
                    name = displayNameFor(context, uri)
                    input = runCatching {
                        context.contentResolver.openInputStream(uri)
                    }.getOrNull()
                }
                if (input == null) {
                    return@runCatching
                }
                // Konsumsi budget juga di jalur file tunggal: tanpa ini guard
                // MAX_ZIP_ENTRIES mati untuk daftar token flat (hanya rekursi
                // direktori via zipFile yang memanggil tryTake). Stream yang
                // sudah dibuka wajib ditutup di jalur budget habis (#233):
                // return polos membocorkan satu FD per token sisa.
                if (!budget.tryTake()) {
                    runCatching { input.close() }
                    return@forEach
                }
                val entry = uniqueZipName(safeEntryPath(name).ifEmpty { "file" }, used)
                // Tanpa runCatching: salinan yang putus harus menggagalkan
                // ZIP, bukan memfinalkan entri terpotong (issue #846).
                zos.putNextEntry(ZipEntry(entry))
                try {
                    input.use { it.copyTo(zos, IO_BUF) }
                } finally {
                    runCatching { zos.closeEntry() }
                }
                contributed++
            }
            outcome.exceptionOrNull()?.let { if (it is IOException) throw it }
        }
        return contributed
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

    /** Nama root arsip untuk token direktori: disanitasi seperti entri
     *  lain agar tak menjadi vektor traversal. Murni agar bisa di-unit-test. */
    internal fun tokenDirRootName(dirName: String, used: MutableMap<String, Int>): String =
        uniqueZipName(safeEntryPath(dirName).ifEmpty { "folder" }, used)

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
        // Galat I/O dari salinan di dalam dilempar ulang sesudah blok
        // (issue #846). Kegagalan QUERY (provider melempar / cursor null)
        // kini juga menggagalkan ZIP (issue #858): sebelumnya ia
        // menghasilkan ZIP kosong 22 byte yang disajikan sebagai sukses
        // dan ter-cache 60 detik. Folder yang sungguh kosong tetap sah
        // (query sukses, nol baris cocok).
        var queryFailed = false
        val outcome = runCatching {
            val cursor = try {
                resolver.query(
                    collection, projection,
                    "${MediaStore.MediaColumns.RELATIVE_PATH} LIKE ? ESCAPE '\\'",
                    arrayOf(likeArg), null
                )
            } catch (e: Exception) {
                null
            }
            if (cursor == null) queryFailed = true
            cursor?.use { c ->
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
                    // Gagal MEMBUKA entri = entri terlewat (selaras
                    // issue #857); gagal MENYALIN di tengah tetap fatal.
                    runCatching {
                        resolver.openInputStream(
                            ContentUris.withAppendedId(collection, c.getLong(iId))
                        )
                    }.getOrNull()?.use { input ->
                        // Tanpa runCatching: stream MediaStore yang putus
                        // di tengah tidak boleh memfinalkan entri
                        // terpotong sebagai berkas utuh (issue #846).
                        zos.putNextEntry(ZipEntry(entry))
                        try {
                            input.copyTo(zos, IO_BUF)
                        } finally {
                            runCatching { zos.closeEntry() }
                        }
                    }
                }
            }
        }
        if (queryFailed) throw IOException("MediaStore query failed for $folder")
        outcome.exceptionOrNull()?.let { if (it is IOException) throw it }
    }
}
