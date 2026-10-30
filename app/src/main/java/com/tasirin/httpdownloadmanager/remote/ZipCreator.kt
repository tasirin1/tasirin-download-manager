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

    /** Hasil zipTokens: entri yang sungguh tertulis + item terlewat
     *  (token yang tak menyumbang apa pun dan berkas yang terlewat di
     *  dalam folder token), untuk pelaporan jujur pemanggil (#874). */
    data class ZipTokensResult(val entriesWritten: Int, val skippedItems: Int)

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
    // Mengembalikan (entri tertulis, berkas terlewat) agar pemanggil
    // bisa melaporkan isi arsip dengan jujur sampai granularitas anak
    // folder (issue #874). Penolakan penjaga (izin/kedalaman/siklus/
    // budget) adalah keputusan traversal, bukan 'terlewat', dan tidak
    // dihitung di sisi mana pun.
    fun zipFile(
        zos: ZipOutputStream,
        file: File,
        prefix: String,
        depth: Int = 0,
        seen: MutableSet<String> = mutableSetOf(),
        budget: ZipBudget = ZipBudget(),
        usedNames: MutableMap<String, Int> = mutableMapOf(),
        isFileAllowed: (String) -> Boolean
    ): Pair<Int, Int> {
        // Izin dicek terhadap canonical path: symlink file di dalam root
        // yang menunjuk ke luar root lolos bila hanya absolutePath (lokasi
        // link) yang diperiksa, lalu inputStream() membaca target luar.
        val canonicalForAllow = runCatching { file.canonicalPath }.getOrNull()
        if (canonicalForAllow == null || !isFileAllowed(canonicalForAllow)) return 0 to 0
        // Symlink melingkar (folder menunjuk leluhurnya) membuat rekursi tak
        // berujung -> StackOverflow; hentikan via canonical + batas kedalaman.
        // Pakai ulang canonical di atas: resolusi kedua untuk File yang sama
        // hanya mengulang syscall tanpa informasi baru.
        if (depth > MAX_ZIP_DEPTH) return 0 to 0
        if (file.isDirectory) {
            if (!seen.add(canonicalForAllow)) return 0 to 0
        }
        // Dedupe seperti zipTokens/zipMedia: dua nama yang ternormalisasi
        // sama (kontrol C0 -> '_') membuat ZipException duplikat — cabang
        // file menelan diam-diam, cabang direktori menggugurkan seluruh ZIP.
        val entryPath = uniqueZipName(
            safeEntryPath(if (prefix.isEmpty()) file.name else "$prefix/${file.name}")
                .ifEmpty { return 0 to 0 },
            usedNames
        )
        if (!budget.tryTake()) return 0 to 0
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
                return 1 to 0
            }
            var written = 0
            var skipped = 0
            children.sortedWith(
                Comparator { a, b -> a.name.compareTo(b.name, ignoreCase = true) }
            ).forEach { child ->
                // Anak sesudah plafon tak pernah dikunjungi rekursi:
                // hitung sebagai terlewat agar ekor budget tak hilang
                // dari laporan (issue #884).
                if (budget.count >= MAX_ZIP_ENTRIES) {
                    skipped++
                    return@forEach
                }
                val (w, s) = zipFile(zos, child, entryPath, depth + 1, seen, budget, usedNames, isFileAllowed)
                written += w
                skipped += s
            }
            return written to skipped
        } else if (file.isFile) {
            // Gagal MEMBUKA sumber = berkas terlewat (issue #866, selaras
            // #857 untuk token flat): berkas yang hilang di antara
            // listing dan buka tak boleh membunuh seluruh ZIP folder.
            val expected = file.length()
            val input = runCatching { file.inputStream() }.getOrNull() ?: return 0 to 1
            // Kegagalan MENYALIN di tengah tetap TIDAK ditelan (issue
            // #846): entri tanpa ukuran/CRC di muka memakai data
            // descriptor, jadi salinan yang putus di tengah akan
            // difinalkan closeEntry sebagai berkas utuh bila ditelan.
            // Galat dibiarkan naik; createTempZip membuang seluruh ZIP
            // dan pemanggil menerima kegagalan yang jujur.
            zos.putNextEntry(ZipEntry(entryPath))
            try {
                input.use { copyZipEntryVerified(it, zos, expected, true, file.name) }
            } finally {
                runCatching { zos.closeEntry() }
            }
            return 1 to 0
        }
        // Bukan berkas maupun folder lagi (hilang di antara listing
        // dan gilirannya): terlewat, bukan fatal.
        return 0 to 1
    }

    /** ZIP daftar token media (dipakai /api/media_zip: unduh banyak foto/video,
     *  file, atau folder filesystem secara rekursif). */
    // Mengembalikan hitungan entri tertulis + item terlewat
    // (issue #864, diperdalam #874): keputusan lewati di dalam sini
    // (token rusak, tak diizinkan, gagal buka #857, budget habis,
    // berkas terlewat di dalam folder) sebelumnya hilang tanpa jejak
    // sehingga pemanggil tak bisa melaporkan yang gugur.
    fun zipTokens(
        zos: ZipOutputStream,
        tokens: List<String>,
        context: Context,
        isFileAllowed: (String) -> Boolean,
        isUriAllowed: (android.net.Uri) -> Boolean = { false }
    ): ZipTokensResult {
        val used = mutableMapOf<String, Int>()
        val budget = ZipBudget()
        var entriesWritten = 0
        var skippedItems = 0
        tokens.forEachIndexed { index, token ->
            if (budget.count >= MAX_ZIP_ENTRIES) {
                // Token sisa sesudah plafon tak pernah dikunjungi:
                // hitung semuanya sebagai terlewat (issue #884).
                skippedItems += tokens.size - index
                return ZipTokensResult(entriesWritten, skippedItems)
            }
            val raw = MediaLibrary.decodeToken(token)
            if (raw == null) {
                skippedItems++
                return@forEachIndexed
            }
            // Galat I/O di dalam blok (baca sumber putus, listFiles gagal)
            // TIDAK ditelan runCatching ini: ia dilempar ulang sesudah
            // blok agar seluruh ZIP gagal secara terbuka (issue #846).
            // Keputusan lewati-token lain (izin, token rusak) tetap di
            // dalam blok seperti sebelumnya.
            val outcome = runCatching {
                val name: String
                val input: java.io.InputStream?
                var expectedSize = -1L
                var expectedStrictZero = false
                if (raw.startsWith("f:")) {
                    val f = File(raw.removePrefix("f:"))
                    val canon = runCatching { f.canonicalPath }.getOrNull()
                    if (canon == null || !isFileAllowed(canon)) {
                        skippedItems++
                        return@forEachIndexed
                    }
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
                        var dirWritten = 0
                        // Folder kosong tetap menulis entri foldernya
                        // sendiri (issue #962), sejajar zipFile: tanpa
                        // ini token folder kosong menghasilkan ZIP
                        // 0-entri yang tersaji sebagai sukses.
                        if (children.isEmpty() && budget.tryTake()) {
                            zos.putNextEntry(ZipEntry("$root/"))
                            zos.closeEntry()
                            dirWritten = 1
                        }
                        children.sortedWith(
                            Comparator { a, b -> a.name.compareTo(b.name, ignoreCase = true) }
                        ).forEach { child ->
                            // Anak sesudah plafon: terlewat, dihitung
                            // (issue #884); label kembali ke loop anak
                            // ini, bukan loop token.
                            if (budget.count >= MAX_ZIP_ENTRIES) {
                                skippedItems++
                                return@forEach
                            }
                            val (w, s) = zipFile(zos, child, root, depth = 1, seen = dirSeen, isFileAllowed = isFileAllowed, budget = budget, usedNames = dirUsed)
                            dirWritten += w
                            skippedItems += s
                        }
                        entriesWritten += dirWritten
                        // Folder yang tak menulis satu entri pun (kosong
                        // atau seluruh anaknya gugur) adalah item
                        // terlewat, bukan kontributor (issue #873).
                        if (dirWritten == 0) skippedItems++
                        return@runCatching
                    }
                    // canon + izin sudah diverifikasi di atas untuk File yang
                    // sama: tanpa resolusi canonical dan cek izin kedua.
                    name = f.name
                    // Gagal MEMBUKA sumber = token terlewat (issue #857):
                    // berkas yang hilang di antara listing dan buka tak
                    // boleh membunuh seluruh batch. Yang menggagalkan
                    // ZIP hanya gagal MENYALIN di tengah (issue #846).
                    if (f.isFile) {
                        expectedSize = f.length()
                        expectedStrictZero = true
                    }
                    input = if (f.isFile) {
                        runCatching { f.inputStream() }.getOrNull()
                    } else null
                } else {
                    if (!raw.startsWith("u:")) {
                        skippedItems++
                        return@forEachIndexed
                    }
                    val uri = raw.removePrefix("u:").toUri()
                    if (!isUriAllowed(uri)) {
                        skippedItems++
                        return@forEachIndexed
                    }
                    name = displayNameFor(context, uri)
                    expectedSize = sizeFor(context, uri)
                    input = runCatching {
                        context.contentResolver.openInputStream(uri)
                    }.getOrNull()
                }
                if (input == null) {
                    skippedItems++
                    return@runCatching
                }
                // Konsumsi budget juga di jalur file tunggal: tanpa ini guard
                // MAX_ZIP_ENTRIES mati untuk daftar token flat (hanya rekursi
                // direktori via zipFile yang memanggil tryTake). Stream yang
                // sudah dibuka wajib ditutup di jalur budget habis (#233):
                // return polos membocorkan satu FD per token sisa.
                if (!budget.tryTake()) {
                    runCatching { input.close() }
                    skippedItems++
                    return@forEachIndexed
                }
                val entry = uniqueZipName(safeEntryPath(name).ifEmpty { "file" }, used)
                // Tanpa runCatching: salinan yang putus harus menggagalkan
                // ZIP, bukan memfinalkan entri terpotong (issue #846).
                zos.putNextEntry(ZipEntry(entry))
                try {
                    input.use { copyZipEntryVerified(it, zos, expectedSize, expectedStrictZero, name) }
                } finally {
                    runCatching { zos.closeEntry() }
                }
                entriesWritten++
            }
            outcome.exceptionOrNull()?.let { if (it is IOException) throw it }
        }
        return ZipTokensResult(entriesWritten, skippedItems)
    }

    /** Salin isi satu entri lalu verifikasi jumlah byte tersalin
     *  terhadap ukuran sumber yang ditangkap sebelumnya (issue #958):
     *  berkas yang berubah ukuran di tengah salin berakhir pada EOF
     *  dini secara bersih dan dulu difinalkan sebagai entri utuh.
     *  Ketidakcocokan melempar IOException — ZIP gagal dengan jujur,
     *  selaras doktrin #846. Ukuran dianggap diketahui bila > 0, atau
     *  >= 0 bila [strictZero] (jalur berkas: File.length otoritatif,
     *  termasuk 0; provider MediaStore bisa melaporkan 0 untuk berkas
     *  valid sehingga 0 di sana berarti tak diketahui). */
    private fun copyZipEntryVerified(
        input: java.io.InputStream,
        zos: ZipOutputStream,
        expected: Long,
        strictZero: Boolean,
        name: String
    ) {
        val copied = input.copyTo(zos, IO_BUF)
        val known = if (strictZero) expected >= 0 else expected > 0
        if (known && copied != expected) {
            throw IOException("File changed while zipping: $name")
        }
    }

    private fun sizeFor(context: Context, uri: android.net.Uri): Long = runCatching {
        val projection = arrayOf(MediaStore.MediaColumns.SIZE)
        context.contentResolver.query(uri, projection, null, null, null)?.use { c ->
            if (c.moveToFirst()) {
                val idx = c.getColumnIndex(MediaStore.MediaColumns.SIZE)
                if (idx >= 0) c.getLong(idx) else -1L
            } else {
                -1L
            }
        } ?: -1L
    }.getOrDefault(-1L)

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

    /** Cap isi sumber ZIP filesystem (issue #961; dipindah dari
     *  HttpControlServer pada issue #979 agar terjangkau uji JVM —
     *  fungsi ini hanya bergantung java.io.File): berkas tunggal =
     *  ukuran+mtime; folder = lipatan (path, ukuran, mtime) seluruh isi
     *  yang terjangkau jalan kaki rekursif terbatas, dengan himpunan
     *  kanonik anti-putaran symlink seperti zipFile. Jalan kaki stat
     *  murni (tanpa baca isi) — jauh lebih murah daripada membuat ZIP,
     *  dan pada cache-hit ia menggantikan pembuatan ulang itu. */
    // Urutan total nama untuk semua sortir jalan kaki cap
    // (issue #1005): ignoreCase, seri dipecah case-sensitive —
    // tanpa pemecah seri, pasangan A.txt/a.txt mengikuti urutan
    // listFiles() yang tak dijamin API dan cap bisa berdenyut.
    private val stampNameOrder = Comparator<File> { a, b ->
        val c = a.name.compareTo(b.name, ignoreCase = true)
        if (c != 0) c else a.name.compareTo(b.name)
    }

    internal fun zipFsSourceStamp(root: File): String {
        if (root.isFile) return "f" + root.length() + ":" + root.lastModified()
        if (!root.isDirectory) return "none"
        var hash = -3750763034362895579L
        var count = 0
        var truncated = false
        val seen = mutableSetOf<String>()
        val stack = ArrayDeque<File>()
        stack.add(root)
        while (stack.isNotEmpty() && !truncated) {
            val dir = stack.removeLast()
            val canon = runCatching { dir.canonicalPath }.getOrNull() ?: dir.absolutePath
            if (!seen.add(canon)) continue
            // Anak diurutkan lebih dulu (issue #980): listFiles tak
            // menjamin urutan dan lipatan hash peka-urutan — tanpa ini
            // cap folder yang diam bisa berdenyut antarpermintaan.
            // Pola pengurutan yang sama dengan zipFile.
            val children = dir.listFiles()?.sortedWith(stampNameOrder) ?: continue
            for (child in children) {
                if (count >= ZIP_STAMP_MAX_ENTRIES) {
                    truncated = true
                    break
                }
                count++
                hash = zipStampFold(hash, child.absolutePath)
                hash = zipStampFold(hash, child.length().toString())
                hash = zipStampFold(hash, child.lastModified().toString())
                if (child.isDirectory) stack.add(child)
            }
        }
        if (truncated) {
            // Subpohon yang tak sempat dibuka tetap dilipat dangkal
            // (issue #972): path+ukuran+mtime direktori yang masih
            // terantre menangkap perubahan struktural di sana tanpa
            // biaya membukanya satu per satu. Anak langsung direktori
            // terantre ikut dilipat (issue #981, terbatas anggaran
            // yang sama) agar perubahan satu tingkat di bawahnya —
            // tambah/hapus berkas di sana — ikut menggerakkan cap;
            // perubahan isi berkas yang sudah ada di kedalaman itu
            // tetap di luar jangkauan cap dangkal.
            var shallowBudget = ZIP_STAMP_MAX_ENTRIES
            while (stack.isNotEmpty()) {
                val queued = stack.removeLast()
                hash = zipStampFold(hash, queued.absolutePath)
                hash = zipStampFold(hash, queued.length().toString())
                hash = zipStampFold(hash, queued.lastModified().toString())
                if (shallowBudget > 0) {
                    val kidArr = queued.listFiles()
                    if (kidArr != null) {
                        // Seleksi terbatas mengikuti sisa anggaran
                        // (issue #993): anak jauh melampaui anggaran
                        // jangan disortir penuh — cukup ambil yang
                        // terkecil menurut urutan nama (hasil lipatan
                        // identik dengan awalan sortir penuh, biaya
                        // terikat anggaran, bukan ukuran direktori).
                        val kids: List<File> = if (kidArr.size <= shallowBudget) {
                            kidArr.sortedWith(stampNameOrder)
                        } else {
                            val order = stampNameOrder
                            val heap = java.util.PriorityQueue<File>(
                                shallowBudget, order.reversed()
                            )
                            for (kid in kidArr) {
                                val top = heap.peek()
                                if (heap.size < shallowBudget) {
                                    heap.add(kid)
                                } else if (top != null && order.compare(kid, top) < 0) {
                                    heap.poll()
                                    heap.add(kid)
                                }
                            }
                            heap.sortedWith(order)
                        }
                        for (kid in kids) {
                            shallowBudget--
                            hash = zipStampFold(hash, kid.absolutePath)
                            hash = zipStampFold(hash, kid.length().toString())
                            hash = zipStampFold(hash, kid.lastModified().toString())
                        }
                    }
                }
            }
        }
        return "d" + count + ":" + hash + ":" + (if (truncated) "t" else "c") +
            ":" + root.lastModified()
    }

    private const val ZIP_STAMP_MAX_ENTRIES = 8192

    /** Satu langkah lipat hash FNV-1a 64 untuk cap sumber ZIP
     *  (issue #961); di sini — bukan di HttpControlServer — agar
     *  terjangkau uji JVM (issue #973). Murni: tanpa I/O/Android. */
    internal fun zipStampFold(hash: Long, text: String): Long {
        var h = hash
        for (ch in text) {
            h = h xor ch.code.toLong()
            h *= 1099511628211L
        }
        return h
    }

    internal fun uniqueZipName(base: String, used: MutableMap<String, Int>): String {
        val count = used.getOrDefault(base, 0)
        used[base] = count + 1
        if (count == 0) return base
        val dot = base.lastIndexOf('.')
        // Nama HASIL wajib ikut terdaftar (issue #912): tanpa ini berkas
        // literal bernama "movie (1).mp4" bertabrakan dengan hasil dedupe
        // "movie.mp4" kedua, dan putNextEntry melempar duplicate entry
        // yang menggagalkan seluruh ZIP. Nomor dinaikkan sampai kandidat
        // benar-benar bebas; urutan tanpa tabrakan menghasilkan nama
        // yang persis sama dengan perilaku lama.
        var n = count
        var candidate = suffixedZipName(base, dot, n)
        while (used.getOrDefault(candidate, 0) > 0) {
            n++
            candidate = suffixedZipName(base, dot, n)
        }
        used[candidate] = 1
        return candidate
    }

    private fun suffixedZipName(base: String, dot: Int, n: Int): String =
        if (dot > 0) {
            base.substring(0, dot) + " ($n)" + base.substring(dot)
        } else {
            "$base ($n)"
        }

    // Mengembalikan (entri tertulis, entri terlewat) seperti zipFile
    // agar fsZip bisa melaporkan isi arsipnya dengan jujur (issue #886).
    fun zipMedia(zos: ZipOutputStream, relative: String, context: Context): Pair<Int, Int> {
        if (Build.VERSION.SDK_INT < 29) return 0 to 0
        val base = relative.trim('/')
        val folder = base + "/"
        val resolver = context.contentResolver
        val collection = MediaLibrary.mediaCollectionForRoot(base)
        val projection = arrayOf(
            MediaStore.MediaColumns._ID,
            MediaStore.MediaColumns.DISPLAY_NAME,
            MediaStore.MediaColumns.RELATIVE_PATH,
            MediaStore.MediaColumns.SIZE
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
        var written = 0
        var skipped = 0
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
                val iSize = c.getColumnIndex(MediaStore.MediaColumns.SIZE)
                while (c.moveToNext()) {
                    if (!budget.tryTake()) {
                        // Baris ini dan sisanya tak terkunjungi:
                        // terhitung terlewat (selaras issue #884).
                        if (c.count > 0) skipped += c.count - c.position
                        break
                    }
                    val relPath = c.getString(iRel) ?: continue
                    val name = c.getString(iName) ?: continue
                    if (!relPath.startsWith(folder)) continue
                    val dirPart = relPath.removePrefix(folder).trimEnd('/')
                    val rawEntry = safeEntryPath(if (dirPart.isEmpty()) name else "$dirPart/$name")
                    if (rawEntry.isEmpty()) continue
                    val entry = uniqueZipName(rawEntry, used)
                    // Gagal MEMBUKA entri = entri terlewat (selaras
                    // issue #857); gagal MENYALIN di tengah tetap fatal.
                    val input = runCatching {
                        resolver.openInputStream(
                            ContentUris.withAppendedId(collection, c.getLong(iId))
                        )
                    }.getOrNull()
                    if (input == null) {
                        skipped++
                    } else {
                        val expected = if (iSize >= 0) {
                            runCatching { c.getLong(iSize) }.getOrDefault(-1L)
                        } else {
                            -1L
                        }
                        input.use {
                            // Tanpa runCatching: stream MediaStore yang putus
                            // di tengah tidak boleh memfinalkan entri
                            // terpotong sebagai berkas utuh (issue #846).
                            zos.putNextEntry(ZipEntry(entry))
                            try {
                                copyZipEntryVerified(it, zos, expected, false, name)
                            } finally {
                                runCatching { zos.closeEntry() }
                            }
                        }
                        written++
                    }
                }
            }
        }
        if (queryFailed) throw IOException("MediaStore query failed for $folder")
        // Pengecualian APA PUN yang mengakhiri blok cursor secara
        // abnormal menggagalkan ZIP (issue #867): sebelumnya hanya
        // IOException yang dilempar ulang, jadi iterasi yang putus
        // (SQLiteException provider) menghasilkan arsip PARSIAL yang
        // disajikan sukses dan ter-cache, dan getColumnIndexOrThrow
        // yang gagal menghasilkan arsip KOSONG yang sama — bug asal
        // #858 lewat pintu lain. Lewati-entri yang disengaja (gagal
        // buka per entri) sudah terbungkus runCatching di dalam blok.
        outcome.exceptionOrNull()?.let { throw it }
        return written to skipped
    }
}
