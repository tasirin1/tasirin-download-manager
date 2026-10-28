package com.tasirin.httpdownloadmanager.util

import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.os.StatFs
import android.provider.DocumentsContract
import android.provider.MediaStore
import androidx.core.net.toUri
import androidx.documentfile.provider.DocumentFile
import com.tasirin.httpdownloadmanager.data.DownloadItem
import java.io.File
import java.io.IOException
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.OutputStream

class FileSaver(context: Context) {
    private val appContext = context.applicationContext
    private val downloadDir = File(appContext.filesDir, "downloads").apply { mkdirs() }

    /** URI folder kustom dibaca fresh tiap publish: user bisa ganti folder di
     *  Settings tanpa restart proses (field val sekali-init akan basi). */
    private fun customFolderUri(): Uri? = StoragePrefs.getFolderUri(appContext)

    data class PublishResult(
        val contentUri: String? = null,
        val filePath: String? = null,
        val fileName: String? = null
    )

    fun partialFile(fileName: String, segment: Int? = null): File {
        val cleanName = FileNames.safe(fileName)
        val suffix = if (segment != null) ".part.$segment" else ".part"
        return File(downloadDir, "$cleanName$suffix")
    }

    fun partialFiles(item: DownloadItem): List<File> {
        // Basis .part selalu ikut: item segmen yang jatuh ke single-stream
        // (fallback saat Range ditolak) menulis ke .part tanpa sufiks — tanpa
        // ini deleteFiles menyisakan yatim sampai sapuan orphan 2 jam.
        val files = mutableListOf(partialFile(item.fileName))
        item.segments.forEach { files.add(partialFile(item.fileName, it.index)) }
        return files
    }

    companion object {
        private val MERGE_LOCKS = Array(8) { Any() }
        private val WHITESPACE_RE = Regex("\\s+")
        /** Buffer salin 64KB: default copyTo 8KB lambat ~8x syscall untuk file GB. */
        private const val IO_BUF = 64 * 1024
    }

    private fun mergeLockFor(key: String): Any =
        MERGE_LOCKS[(key.hashCode() and Int.MAX_VALUE) % MERGE_LOCKS.size]

    fun mergeSegments(fileName: String, segmentCount: Int): File =
        synchronized(mergeLockFor(FileNames.safe(fileName))) {
        val target = partialFile(fileName)
        // Staging unik per proses agar dua item bernama sama yang merge
        // paralel tidak saling menimpa file staging yang sama.
        val staging = File(target.parentFile, target.name + ".merge." + System.nanoTime())
        try {
            BufferedOutputStream(staging.outputStream()).use { out ->
                for (index in 0 until segmentCount) {
                    val part = partialFile(fileName, index)
                    if (!part.exists()) throw IOException("Segment $index not found")
                    BufferedInputStream(part.inputStream(), IO_BUF).use { input -> input.copyTo(out, IO_BUF) }
                }
            }
            // renameTo memakai rename(2) di Linux dan menggantikan target lama;
            // fallback menjaga perangkat yang menolak replace otomatis.
            // Bila finalisasi gagal, wajib throw: me-return target yang tidak
            // ada sambil menghapus parts berarti data hilang diam-diam.
            if (!staging.renameTo(target)) {
                runCatching { target.delete() }
                if (!staging.renameTo(target) || !target.isFile) {
                    throw IOException("Failed to finalize merged file")
                }
            }
        } finally {
            if (staging.exists()) runCatching { staging.delete() }
        }
        for (index in 0 until segmentCount) runCatching { partialFile(fileName, index).delete() }
        return target
    }

    fun publishToPath(partial: File, fileName: String, folder: String): PublishResult? {
        val clean = folder.trim()
        // Bentuk MediaStore m:<relPath> — diterima validator server dan
        // ditangani jalur upload saveStream, tetapi dulu lolos mentah ke
        // File() di bawah sehingga publish selalu gagal sesudah unduhan
        // 100% (issue #450). Terbitkan lewat saveToMediaStore dengan
        // relative path dari folderPath; kolom RELATIVE_PATH/IS_PENDING
        // hanya ada di API 29+, di bawah itu null = pemanggil melempar.
        if (clean.startsWith("m:")) {
            if (Build.VERSION.SDK_INT < 29) return null
            val relative = clean.removePrefix("m:").trim().trim('/')
            return runCatching {
                val result = saveToMediaStore(fileName, relative.ifEmpty { null }) { out ->
                    partial.inputStream().use { it.copyTo(out, IO_BUF) }
                }
                partial.delete()
                result
            }.getOrNull()
        }
        // Bentuk f:<path> dan path absolut polos: selesaikan lewat helper
        // bersama agar publish menilai folder yang sama dengan pemeriksa
        // ruang (statFsDirForFolderPath) dan sapuan yatim.
        val dir = dirForFolderPath(clean) ?: return null
        if (!dir.isDirectory && !dir.mkdirs()) return null
        if (!dir.isDirectory) return null
        return runCatching {
            publishFileIntoDir(partial, dir, fileName)
        }.getOrNull()
    }

    fun publish(partial: File, fileName: String, destination: String? = null): PublishResult {
        when (destination) {
            "download" -> {
                return if (Build.VERSION.SDK_INT >= 29) {
                    publishToMediaStore(partial, fileName)
                } else {
                    publishToPublicDir(partial, fileName)
                }
            }
            "internal" -> return publishToInternal(partial, fileName)
        }
        val folderUri = customFolderUri()
        if (folderUri != null) {
            val result = publishToCustomFolder(partial, fileName, folderUri)
            if (result != null) return result
        }
        val textFolder = StoragePrefs.getTextFolder(appContext)
        if (textFolder != null) {
            val result = publishToTextFolder(partial, fileName, textFolder)
            if (result != null) return result
        }
        return if (Build.VERSION.SDK_INT >= 29) {
            publishToMediaStore(partial, fileName)
        } else {
            publishToPublicDir(partial, fileName)
        }
    }

    fun saveStream(
        fileName: String,
        destination: String = "",
        folderPath: String = "",
        writer: (OutputStream) -> Unit
    ): PublishResult {
        val cleanFolder = folderPath.trim().removePrefix("f:")
        if (cleanFolder.isNotBlank()) {
            if (cleanFolder.startsWith("m:")) {
                // Kolom IS_PENDING/RELATIVE_PATH dan koleksi Downloads hanya
                // ada di API 29+: di Android 5-9 insert MediaStore bisa gagal,
                // jadi fallback ke dir publik/internal seperti tujuan download.
                if (Build.VERSION.SDK_INT >= 29) {
                    return saveToMediaStore(fileName, cleanFolder.substring(2), writer)
                }
                writePublicDir(fileName, writer)?.let { return it }
                return writeInternal(fileName, writer)
            }
            val dir = File(cleanFolder)
            if (!dir.isDirectory && !dir.mkdirs()) {
                throw IOException("Destination folder is invalid or not writable: $cleanFolder")
            }
            val target = uniqueTargetFile(File(dir, fileName))
            try {
                BufferedOutputStream(target.outputStream()).use { out -> writer(out) }
            } catch (e: Exception) {
                // Gagal di tengah upload: buang file setengah jadi.
                runCatching { target.delete() }
                throw e
            }
            return PublishResult(filePath = target.absolutePath, fileName = target.name)
        }
        when (destination) {
            "internal" -> return writeInternal(fileName, writer)
            "download" -> {
                if (Build.VERSION.SDK_INT >= 29) return saveToMediaStore(fileName, null, writer)
                writePublicDir(fileName, writer)?.let { return it }
                return writeInternal(fileName, writer)
            }
        }
        customFolderUri()?.let { uri ->
            writeCustomFolder(fileName, uri, writer)?.let { return it }
        }
        StoragePrefs.getTextFolder(appContext)?.let { tf ->
            val dir = File(tf)
            if (dir.isDirectory || dir.mkdirs()) {
                // Nama unik seperti cabang lain: impor upload jangan menimpa
                // file senama yang sudah ada di folder teks.
                val target = uniqueTargetFile(File(dir, fileName))
                try {
                    target.outputStream().use { out -> writer(out) }
                } catch (e: Exception) {
                    runCatching { target.delete() }
                    throw e
                }
                return PublishResult(filePath = target.absolutePath, fileName = target.name)
            }
        }
        return if (Build.VERSION.SDK_INT >= 29) {
            saveToMediaStore(fileName, null, writer)
        } else {
            writePublicDir(fileName, writer) ?: writeInternal(fileName, writer)
        }
    }

    private fun writeInternal(fileName: String, writer: (OutputStream) -> Unit): PublishResult {
        val target = uniqueTargetFile(File(downloadDir, fileName))
        try {
            BufferedOutputStream(target.outputStream()).use { out -> writer(out) }
        } catch (e: Exception) {
            runCatching { target.delete() }
            throw e
        }
        return PublishResult(filePath = target.absolutePath, fileName = target.name)
    }

    @Suppress("DEPRECATION")
    private fun writePublicDir(fileName: String, writer: (OutputStream) -> Unit): PublishResult? {
        val publicDir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
        if (Environment.getExternalStorageState() != Environment.MEDIA_MOUNTED) return null
        runCatching { publicDir.mkdirs() }
        if (!publicDir.isDirectory || !publicDir.canWrite()) return null
        val target = uniqueTargetFile(File(publicDir, fileName))
        try {
            target.outputStream().use { out -> writer(out) }
        } catch (e: Exception) {
            runCatching { target.delete() }
            throw e
        }
        return PublishResult(filePath = target.absolutePath, fileName = target.name)
    }

    private fun saveToMediaStore(
        fileName: String,
        relativePath: String?,
        writer: (OutputStream) -> Unit
    ): PublishResult {
        val resolver = appContext.contentResolver
        val mime = MimeTypes.forFile(fileName)
        val collection = MediaLibrary.mediaCollectionFor(relativePath, mime)
        // Klaim nama atomik dalam proses: cek-duplikat + insert tanpa lock
        // membuat dua publish paralel bernama sama mendapat DISPLAY_NAME kembar
        // (MediaStore tak menolak nama duplikat). Pola sama seperti klaim
        // createNewFile di uniqueTargetFile untuk path filesystem.
        val (uri, unique, values) = synchronized(mergeLockFor(FileNames.safe(fileName))) {
            val unique = uniqueMediaStoreName(fileName, relativePath, collection)
            val values = ContentValues().apply {
                put(MediaStore.Downloads.DISPLAY_NAME, unique)
                put(MediaStore.Downloads.MIME_TYPE, mime)
                relativePath?.let { rel ->
                    put(MediaStore.Downloads.RELATIVE_PATH, rel.trim('/').trimEnd('/') + "/")
                }
                put(MediaStore.Downloads.IS_PENDING, 1)
            }
            val uri = resolver.insert(collection, values)
                ?: throw IOException("Failed to create file in MediaStore")
            Triple(uri, unique, values)
        }
        try {
            resolver.openOutputStream(uri)?.use { out -> writer(out) }
                ?: throw IOException("Failed to open MediaStore output")
            values.clear()
            values.put(MediaStore.Downloads.IS_PENDING, 0)
            resolver.update(uri, values, null, null)
            return PublishResult(contentUri = uri.toString(), fileName = unique)
        } catch (e: Exception) {
            runCatching { resolver.delete(uri, null, null) }
            throw e
        }
    }

    /** Klaim dokumen tujuan di folder/dokumen SAF secara atomik dalam
     *  proses: pilih nama unik + findFile + createFile di dalam kunci
     *  nama yang sama seperti klaim MediaStore (issue #404; pemakai:
     *  writeCustomFolder, publishToCustomFolder, move, dan cabang SAF
     *  organizeByType sejak #410). Tanpa
     *  kunci, dua publish paralel bernama sama sama-sama menilai satu
     *  nama "bebas"; pada provider yang createFile-nya mengembalikan
     *  berkas yang sudah ada, openOutputStream "wt" salah satu men-
     *  truncate hasil publish yang lain. Mengembalikan pasangan
     *  (dokumen, nama unik, baru-dibuat) atau null. */
    private fun claimCustomDoc(
        tree: DocumentFile,
        fileName: String
    ): Triple<DocumentFile, String, Boolean>? =
        synchronized(mergeLockFor(FileNames.safe(fileName))) {
            val unique = uniqueDocumentName(tree, fileName)
            val existing = tree.findFile(unique)
            if (existing != null) {
                Triple(existing, unique, false)
            } else {
                val created = tree.createFile(MimeTypes.forFile(unique), unique)
                    ?: return@synchronized null
                Triple(created, unique, true)
            }
        }

    private fun writeCustomFolder(
        fileName: String,
        folderUri: Uri,
        writer: (OutputStream) -> Unit
    ): PublishResult? = runCatching {
        val tree = DocumentFile.fromTreeUri(appContext, folderUri) ?: return null
        val (target, unique, createdDoc) = claimCustomDoc(tree, fileName) ?: return null
        // Stream gagal dibuka sesudah dokumen dibuat: dokumen 0-byte
        // yatim tertinggal selamanya di folder pengguna (tak dirujuk
        // item mana pun, tak tersapu pembersih partial). Buang dokumen
        // yang BARU dibuat panggilan ini; dokumen lama tak disentuh.
        val output = appContext.contentResolver.openOutputStream(target.uri, "wt")
            ?: run {
                if (createdDoc) runCatching { target.delete() }
                return null
            }
        try {
            output.use { writer(it) }
        } catch (e: Exception) {
            runCatching { target.delete() }
            throw e
        }
        PublishResult(contentUri = target.uri.toString(), fileName = unique)
    }.getOrNull()

    private fun publishToCustomFolder(
        partial: File,
        fileName: String,
        folderUri: Uri
    ): PublishResult? = runCatching {
        val tree = DocumentFile.fromTreeUri(appContext, folderUri) ?: return null
        val (target, unique, createdDoc) = claimCustomDoc(tree, fileName) ?: return null
        // Sama seperti writeCustomFolder: stream null sesudah dokumen
        // dibuat meninggalkan yatim 0-byte — buang yang baru dibuat.
        val output = appContext.contentResolver.openOutputStream(target.uri, "wt")
            ?: run {
                if (createdDoc) runCatching { target.delete() }
                return null
            }
        try {
            output.use { out ->
                partial.inputStream().use { input -> input.copyTo(out, IO_BUF) }
            }
        } catch (e: Exception) {
            // Salinan gagal di tengah jalan: buang target setengah-tulis agar
            // tidak jadi file korup yatim (sama seperti writeCustomFolder).
            runCatching { target.delete() }
            throw e
        }
        partial.delete()
        PublishResult(contentUri = target.uri.toString(), fileName = unique)
    }.getOrNull()

    private fun publishToTextFolder(
        partial: File,
        fileName: String,
        folder: String
    ): PublishResult? = runCatching {
        val dir = File(folder)
        if (!dir.isDirectory && !dir.mkdirs()) return null
        publishFileIntoDir(partial, dir, fileName)
    }.getOrNull()

    fun freeBytes(): Long = runCatching {
        StatFs(downloadDir.absolutePath).availableBytes
    }.getOrDefault(0L)

    fun destinationFreeBytes(folderPath: String = ""): Long {
        // Partial download ditulis di downloadDir internal dan tmp upload di
        // cacheDir; folder teks hanya salah satu tujuan publish. Guard
        // storage-menipis harus memakai volume tersempit di antaranya,
        // bukan hanya folder teks (volume adopted bisa berbeda-beda).
        var free = freeBytes()
        // Stat gagal (volume tak terpasang) jangan meracuni hitungan jadi 0
        // (semua download ditolak palsu): abaikan volume yang gagal dibaca.
        runCatching { StatFs(appContext.cacheDir.absolutePath).availableBytes }
            .getOrNull()?.let { free = minOf(free, it) }
        val textFolder = StoragePrefs.getTextFolder(appContext)
        if (textFolder != null) {
            val dir = File(textFolder)
            if (dir.isDirectory) {
                runCatching { StatFs(dir.absolutePath).availableBytes }
                    .getOrNull()?.let { free = minOf(free, it) }
            }
        }
        // Tujuan custom `f:` bisa berada di volume lain (SD card): tanpa ini,
        // guard memakai angka volume internal sehingga lolos saat kartu penuh
        // atau menolak palsu saat internal sempit tapi kartu lega.
        statFsDirForFolderPath(folderPath)?.let { dir ->
            runCatching { StatFs(dir.absolutePath).availableBytes }
                .getOrNull()?.let { free = minOf(free, it) }
        }
        return free
    }

    fun sidecarChecksum(item: DownloadItem): Pair<String, String>? {
        val path = item.filePath ?: return null
        val file = File(path)
        val parent = file.parentFile ?: return null
        val base = file.name
        val algos = mapOf(".md5" to "MD5", ".sha1" to "SHA-1", ".sha256" to "SHA-256")
        for ((ext, algo) in algos) {
            val side = File(parent, base + ext)
            if (side.exists()) {
                // Sidecar checksum hanya butuh baris pertama; tolak file jumbo agar readText tak OOM.
                if (side.length() > 8192) continue
                val first = runCatching {
                    side.readText().trim().split(WHITESPACE_RE).firstOrNull().orEmpty() // audit-ignore: unbounded_read_text (dibatasi length>8192 di atas)
                }.getOrDefault("")
                // Validasi eksak per algoritma (issue #347): token
                // diterima hanya bila panjangnya pas (MD5 32, SHA-1 40,
                // SHA-256 64) dan seluruhnya heksadesimal. Token cacat
                // diabaikan (dianggap tanpa checksum) daripada menjadi
                // ekspektasi yang mustahil cocok.
                val token = first.lowercase(java.util.Locale.ROOT)
                val expectedLen = when (algo) {
                    "MD5" -> 32
                    "SHA-1" -> 40
                    else -> 64
                }
                if (token.length == expectedLen &&
                    token.all { it in '0'..'9' || it in 'a'..'f' }
                ) return algo to token
            }
        }
        return null
    }

    fun cleanupOrphanPartials(items: List<DownloadItem>): Long {
        var freed = 0L
        runCatching {
            val expected = buildSet {
                items.forEach { item ->
                    addAll(protectedPartialNames(item))
                    // Nama file FINAL item ikut dilindungi: file milik item
                    // yang dikenal tak pernah yatim, apa pun pola namanya
                    // (mis. final bernama persis "clip.part.2").
                    add(FileNames.safe(item.fileName))
                }
            }
            downloadDir.listFiles()?.forEach { f ->
                val name = f.name
                if (isSweepablePartial(name) && name !in expected) {
                    // Hapus file .part yang sudah tua (>2jam) supaya tidak menghapus
                    // file .part yang sedang aktif di-download.
                    val age = System.currentTimeMillis() - f.lastModified()
                    if (age > 2L * 60 * 60 * 1000) {
                        runCatching {
                            val size = f.length()
                            if (f.delete()) freed += size
                        }
                    }
                }
            }
            // Direktori tujuan publish di LUAR downloadDir (issue #402):
            // staging `<nama>.part` dari publishFileIntoDir bisa tertinggal
            // di folder publik/teks/kustom saat proses mati di tengah
            // salin. Sapu HANYA nama yang basisnya persis nama final item
            // yang dikenal (isPublishStagingName) dan sudah tua >2 jam —
            // di folder milik pengguna pola longgar tak aman. Tujuan milik
            // item yang sudah tak ada di daftar memang tak terjangkau
            // sapuan ini; itu batas yang disadari.
            // Cakupan nama PER DIREKTORI (issue #412): folder induk
            // filePath sebuah item hanya boleh menyapu staging milik item
            // ITU — bukan nama semua item — agar berkas .part asing milik
            // pengguna yang kebetulan bernama sama dengan item LAIN di
            // folder itu tak ikut terhapus. Dua tujuan publish bawaan
            // aplikasi (Download publik + folder teks) tetap memakai
            // himpunan global karena item mana pun bisa terbit ke sana.
            val finalNames = items.map { FileNames.safe(it.fileName) }.toSet()
            val namesByDir = mutableMapOf<File, Set<String>>()
            fun addSweepNames(dir: File, names: Set<String>) {
                namesByDir[dir] = (namesByDir[dir] ?: emptySet()) + names
            }
            @Suppress("DEPRECATION")
            addSweepNames(
                Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS),
                finalNames
            )
            StoragePrefs.getTextFolder(appContext)?.let { addSweepNames(File(it), finalNames) }
            items.forEach { item ->
                // Folder tujuan publish kustom item (issue #445): publish
                // yang mati di tengah salin meninggalkan staging di folder
                // folderPath, tetapi filePath item belum berpindah ke sana
                // sehingga folder itu tak pernah masuk peta sapu dan
                // staging-nya yatim permanen. Nama tetap dibatasi per item
                // seperti aturan #412.
                // Lewat dirForFolderPath (issue #460): File() mentah atas
                // bentuk f:/storage/... adalah path relatif yang tak
                // pernah ditemukan loop sapu, sehingga folder f: lolos
                // dari #445 tanpa jejak. Bentuk m: memang bukan direktori
                // File dan helper mengembalikannya null.
                item.folderPath?.takeIf { it.isNotBlank() }
                    ?.let { dirForFolderPath(it) }
                    ?.let { addSweepNames(it, setOf(FileNames.safe(item.fileName))) }
                item.filePath?.takeIf { it.startsWith("/") }
                    ?.let {
                        File(it).parentFile?.let { p ->
                            addSweepNames(p, setOf(FileNames.safe(item.fileName)))
                        }
                    }
            }
            val downloadCanon = runCatching { downloadDir.canonicalFile }.getOrNull()
            namesByDir.forEach { (dir, dirNames) ->
                if (!dir.isDirectory) return@forEach
                if (downloadCanon != null &&
                    runCatching { dir.canonicalFile }.getOrNull() == downloadCanon
                ) return@forEach
                runCatching { dir.listFiles() }.getOrNull()?.forEach { f ->
                    if (!f.isFile) return@forEach
                    if (!isPublishStagingName(f.name, dirNames)) return@forEach
                    val age = System.currentTimeMillis() - f.lastModified()
                    if (age > 2L * 60 * 60 * 1000) {
                        runCatching {
                            val size = f.length()
                            if (f.delete()) freed += size
                        }
                    }
                }
            }
        }
        return freed
    }

    private fun publishToMediaStore(partial: File, fileName: String): PublishResult {
        val result = runCatching {
            saveToMediaStore(fileName, null) { out ->
                partial.inputStream().use { it.copyTo(out, IO_BUF) }
            }
        }.getOrNull()
        if (result != null) {
            partial.delete()
            return result
        }
        return publishToInternal(partial, fileName)
    }

    @Suppress("DEPRECATION")
    private fun publishToPublicDir(partial: File, fileName: String): PublishResult {
        val publicDir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
        if (Environment.getExternalStorageState() == Environment.MEDIA_MOUNTED) {
            runCatching { publicDir.mkdirs() }
            if (publicDir.isDirectory && publicDir.canWrite()) {
                // Gagal terbit di folder publik -> fallback ke internal.
                runCatching { publishFileIntoDir(partial, publicDir, fileName) }
                    .getOrNull()?.let { return it }
            }
        }
        return publishToInternal(partial, fileName)
    }

    private fun publishToInternal(partial: File, fileName: String): PublishResult =
        publishFileIntoDir(partial, downloadDir, fileName)

    /** Terbitkan berkas partial ke nama final di [dir] tanpa menyalin
     *  byte ke nama final secara langsung (issue #372):
     *  - partial di direktori yang sama: rename langsung ke nama final
     *    yang sudah diklaim (O(1), atomik) — bukan salinan penuh.
     *  - partial di direktori lain: salin dulu ke staging bernama
     *    `<nama>.part` di direktori tujuan, lalu rename ke nama final.
     *  Dengan pola ini nama final tidak pernah berisi byte setengah
     *  jadi: kematian proses di tengah salin hanya meninggalkan
     *  partial/staging — di downloadDir disapu pemindaian yatim biasa,
     *  di direktori tujuan luar disapu cleanupOrphanPartials lewat
     *  pencocokan nama final item yang dikenal (issue #402) — dan
     *  rename dalam satu direktori bersifat atomik. */
    private fun publishFileIntoDir(partial: File, dir: File, fileName: String): PublishResult {
        val target = uniqueTargetFile(File(dir, fileName))
        var completed = false
        try {
            val sameDir = runCatching {
                partial.canonicalFile.parentFile == dir.canonicalFile
            }.getOrDefault(false)
            if (sameDir) {
                if (partial.renameTo(target)) {
                    completed = true
                } else {
                    // renameTo menolak menimpa klaim di sebagian filesystem:
                    // jatuh ke salinan langsung ke target yang sudah diklaim.
                    target.outputStream().use { out -> partial.inputStream().use { it.copyTo(out, IO_BUF) } }
                    completed = true
                    partial.delete()
                }
            } else {
                val staging = uniqueTargetFile(File(dir, fileName + ".part"))
                try {
                    staging.outputStream().use { out -> partial.inputStream().use { it.copyTo(out, IO_BUF) } }
                    if (staging.renameTo(target)) {
                        completed = true
                    } else {
                        target.outputStream().use { out -> staging.inputStream().use { it.copyTo(out, IO_BUF) } }
                        completed = true
                    }
                } finally {
                    // Sesudah rename berhasil path staging sudah tidak ada;
                    // sesudah salinan fallback staging dibuang di sini.
                    runCatching { staging.delete() }
                }
                partial.delete()
            }
        } catch (e: Exception) {
            // Target setengah jadi tidak disapu pemindaian orphan (.part saja):
            // hapus di sini agar tidak yatim selamanya. Target yang sudah
            // terbit utuh (completed) tidak disentuh.
            if (!completed) runCatching { target.delete() }
            throw e
        }
        MediaLibrary.notifyMediaChanged(appContext, target.absolutePath)
        return PublishResult(filePath = target.absolutePath, fileName = target.name)
    }

    fun deleteFiles(item: DownloadItem) {
        partialFiles(item).forEach { runCatching { it.delete() } }
        if (!item.contentUri.isNullOrEmpty()) {
            runCatching { appContext.contentResolver.delete(item.contentUri.toUri(), null, null) }
        }
        if (!item.filePath.isNullOrEmpty()) {
            runCatching { File(item.filePath).delete() }
        }
    }

    /** Rename file di disk/MediaStore. Kembalikan PublishResult berisi
     *  path/URI baru DAN nama aktual bila berhasil: nama bisa diunikkkan
     *  lagi di dalam sini terhadap berkas asing (bukan hanya item lain),
     *  dan nama hasil unik itu wajib sampai ke catatan item (issue #458). */
    fun rename(item: DownloadItem, newName: String): PublishResult? {
        // Sanitasi: nama dari dialog user bisa mengandung '/' sehingga File(parent, name)
        // lolos ke subpath dan fileName tersimpan merusak pemetaan partialFile.
        val clean = FileNames.safe(newName.trim())
        // Tolak traversal (".." tanpa separator lolos FileNames.safe);
        // pemanggil remote sudah divalidasi, ini lapis kedua untuk dialog native.
        if (clean.isBlank() || clean == item.fileName || clean.startsWith("..")) return null
        return runCatching {
            when {
                !item.contentUri.isNullOrEmpty() -> {
                    val uri = item.contentUri.toUri()
                    if (Build.VERSION.SDK_INT >= 29 && uri.authority == MediaStore.AUTHORITY) {
                        val rel = mediaRelativePath(uri)?.trim('/')
                        val finalName = if (rel != null) {
                            // Cek keunikan di koleksi tempat berkas BENAR-
                            // BENAR hidup (issue #403): default Downloads
                            // membuat nama yang sudah dipakai di Movies
                            // (koleksi Video) dinyatakan bebas, sehingga
                            // rename menghasilkan dua berkas bernama sama
                            // persis di folder yang sama. Pola sama seperti
                            // saveToMediaStore yang meneruskan koleksinya.
                            // Koleksi dibaca dari URI berkas itu sendiri
                            // (issue #444): turunan mime nama LAMA (#411)
                            // masih menyimpang pada rename KEDUA — sesudah
                            // .mp4 -> .bin, "nama lama" adalah .bin yang
                            // mime-nya bukan video, padahal berkas tetap
                            // hidup di koleksi Video (rename tidak
                            // memindahkan antar-koleksi). URI adalah
                            // satu-satunya sumber yang selalu benar.
                            uniqueMediaStoreName(
                                clean, rel,
                                MediaLibrary.mediaCollectionForUri(uri)
                            )
                        } else {
                            clean
                        }
                        val values = ContentValues().apply {
                            put(MediaStore.Downloads.DISPLAY_NAME, finalName)
                        }
                        val ok = appContext.contentResolver.update(uri, values, null, null) > 0
                        if (ok) PublishResult(contentUri = item.contentUri, fileName = finalName) else null
                    } else {
                        val newUri = DocumentsContract.renameDocument(appContext.contentResolver, uri, clean)
                        if (newUri != null) PublishResult(contentUri = newUri.toString(), fileName = clean) else null
                    }
                }
                !item.filePath.isNullOrEmpty() -> {
                    val file = File(item.filePath)
                    // uniqueTargetFile: renameTo menimpa target yang ada tanpa
                    // peringatan — file item lain bisa hilang diam-diam.
                    val target = uniqueTargetFile(File(file.parentFile, clean))
                    if (file.exists() && file.renameTo(target)) {
                        MediaLibrary.notifyMediaChanged(appContext, file.absolutePath, target.absolutePath)
                        PublishResult(filePath = target.absolutePath, fileName = target.name)
                    } else {
                        // Klaim kosong dari uniqueTargetFile tertinggal bila
                        // rename gagal (mis. beda volume): hapus bila masih kosong.
                        runCatching { if (target.isFile && target.length() == 0L) target.delete() }
                        null
                    }
                }
                else -> null
            }
        }.getOrNull()
    }

    fun move(item: DownloadItem, destTreeUri: Uri): PublishResult? {
        return runCatching {
            val tree = DocumentFile.fromTreeUri(appContext, destTreeUri) ?: return null
            // Klaim berkunci seperti publish folder kustom (issue #410):
            // pola cek-lalu-buat tanpa kunci di sini bisa memilih nama yang
            // sama dengan publish paralel bernama sama.
            val (target, _, createdDoc) = claimCustomDoc(tree, item.fileName) ?: return null
            try {
                // Sumber dibaca di dalam try: bila tak bisa dibuka (URI basi /
                // file hilang / null), target kosong yang terlanjur dibuat
                // wajib dibuang agar tak jadi yatim di folder tujuan.
                val input = when {
                    !item.contentUri.isNullOrEmpty() ->
                        appContext.contentResolver.openInputStream(item.contentUri.toUri())
                    !item.filePath.isNullOrEmpty() ->
                        runCatching { File(item.filePath).inputStream() }.getOrNull()
                    else -> null
                } ?: throw java.io.IOException("Failed to open move source")
                input.use { src ->
                    val out = appContext.contentResolver.openOutputStream(target.uri, "wt")
                        ?: throw java.io.IOException("Failed to open move destination")
                    out.use { dst -> src.copyTo(dst, IO_BUF) }
                }
            } catch (e: Exception) {
                // Hanya dokumen yang BARU dibuat panggilan ini yang boleh
                // dibuang (issue #447): claimCustomDoc bisa mengembalikan
                // dokumen yang sudah ada (lomba eksternal), dan kegagalan
                // sebelum openOutputStream (mis. sumber tak bisa dibuka)
                // tidak menyentuhnya — menghapusnya menghancurkan berkas
                // lama milik pengguna.
                if (createdDoc) runCatching { target.delete() }
                throw e
            }
            deleteFiles(item)
            PublishResult(contentUri = target.uri.toString(), fileName = target.name)
        }.getOrNull()
    }

    private fun uniqueTargetFile(file: File): File {
        // Klaim atomik via createNewFile agar dua publish paralel dengan
        // nama sama tidak saling menimpa target yang sama. File klaim (kosong)
        // TIDAK dihapus: pemanggil menimpanya via outputStream atau renameTo,
        // sehingga klaim tetap berlaku sampai tulis selesai (tanpa jendela
        // TOCTOU antara klaim dan pakai).
        val parent = file.parentFile
        if (parent != null && !parent.isDirectory) parent.mkdirs()
        var candidate = file
        repeat(1000) {
            if (!candidate.exists()) {
                if (runCatching { candidate.createNewFile() }.getOrDefault(false)) {
                    return candidate
                }
                if (!candidate.exists()) {
                    // Klaim gagal tapi path tetap tak ada = direktori tak bisa
                    // ditulisi; sufiks lain tak akan membantu — gagal cepat agar
                    // pemanggil (runCatching) batal bersih, bukan pakai path buntu.
                    throw IOException("Cannot create file in ${parent?.absolutePath}")
                }
                // Klaim kalah race (file muncul di sela exists/create):
                // jatuh ke sufiks unik berikutnya.
            }
            val next = FileNames.unique(candidate.name) { File(parent, it).exists() }
            candidate = if (parent != null) File(parent, next) else File(next)
        }
        // 1000 tabrakan nama (praktis tak terjadi): jangan kembalikan file
        // tak-terklaim yang akan di-truncate pemanggil — gagal eksplisit.
        throw IOException("Cannot allocate unique file in ${parent?.absolutePath}")
    }

    private fun uniqueDocumentName(tree: DocumentFile, fileName: String): String {
        return FileNames.unique(fileName) { tree.findFile(it) != null }
    }

    private fun mediaRelativePath(uri: Uri): String? = runCatching {
        appContext.contentResolver.query(
            uri,
            arrayOf(MediaStore.Downloads.RELATIVE_PATH),
            null, null, null
        )?.use { c ->
            if (c.moveToFirst()) c.getString(c.getColumnIndexOrThrow(MediaStore.Downloads.RELATIVE_PATH)) else null
        }
    }.getOrNull()

    fun uniqueMediaStoreName(
        fileName: String,
        relativePath: String?,
        collection: Uri? = null
    ): String {
        if (Build.VERSION.SDK_INT < 29) return fileName
        // Default di-resolve di sini (setelah guard API 29), bukan di parameter,
        // supaya aman di Android 5 (MediaStore.Downloads baru ada API 29).
        val col = collection ?: MediaStore.Downloads.EXTERNAL_CONTENT_URI
        return runCatching {
            val resolver = appContext.contentResolver
            // Query keberadaan PER KANDIDAT (issue #374): cara lama memuat
            // SELURUH DISPLAY_NAME koleksi ke HashSet pada setiap publish
            // (linear terhadap isi folder Downloads pengguna) hanya untuk
            // memeriksa kandidat FileNames.unique yang biasanya langsung
            // bebas di kandidat pertama.
            fun nameExists(name: String): Boolean {
                val selection = StringBuilder("${MediaStore.Downloads.DISPLAY_NAME}=?")
                val args = mutableListOf(name)
                if (relativePath != null) {
                    selection.append(" AND ${MediaStore.Downloads.RELATIVE_PATH}=?")
                    args.add(relativePath.trim('/') + "/")
                }
                return resolver.query(
                    col,
                    arrayOf(MediaStore.Downloads.DISPLAY_NAME),
                    selection.toString(),
                    args.toTypedArray(),
                    null
                )?.use { c -> c.moveToFirst() } ?: false
            }
            FileNames.unique(fileName) { nameExists(it) }
        }.getOrDefault(fileName)
    }

    fun organizeByType(result: PublishResult, fileName: String): PublishResult {
        if (result.contentUri == null && result.filePath == null) return result
        val sub = subfolderFor(fileName) ?: return result
        return runCatching {
            when {
                !result.contentUri.isNullOrEmpty() -> {
                    val uri = result.contentUri.toUri()
                    if (Build.VERSION.SDK_INT >= 29 && uri.authority == MediaStore.AUTHORITY) {
                        // Hormati tujuan m: kustom user: bila file sudah di
                        // relative path non-default (mis. Movies/Koleksi),
                        // jangan seret paksa ke Download/<Sub>/.
                        val currentRel = mediaRelativePath(uri)?.trim('/')?.trim()
                        if (!currentRel.isNullOrEmpty() &&
                            !currentRel.startsWith("Download", ignoreCase = true)
                        ) return result
                        val targetRel = "Download/$sub"
                        // Sudah di subfolder tujuan: tak ada yang dipindah
                        // (dan cek unik di bawah akan menabrak namanya
                        // sendiri).
                        if (currentRel.equals(targetRel, ignoreCase = true)) return result
                        // Cek keunikan nama di subfolder TUJUAN (issue
                        // #448): update RELATIVE_PATH polos membuat dua
                        // berkas bernama sama dari folder asal berbeda
                        // berkumpul di subfolder yang sama karena
                        // MediaStore menoleransi DISPLAY_NAME duplikat —
                        // kelas masalah yang ditutup #403 untuk rename.
                        val unique = uniqueMediaStoreName(
                            fileName, targetRel,
                            MediaLibrary.mediaCollectionForUri(uri)
                        )
                        val values = ContentValues().apply {
                            put(MediaStore.MediaColumns.RELATIVE_PATH, "$targetRel/")
                            if (unique != fileName) {
                                put(MediaStore.MediaColumns.DISPLAY_NAME, unique)
                            }
                        }
                        appContext.contentResolver.update(uri, values, null, null)
                        if (unique != fileName) result.copy(fileName = unique) else result
                    } else {
                        val doc = DocumentFile.fromSingleUri(appContext, uri) ?: return result
                        val parent = doc.parentFile ?: return result
                        val subDir = parent.findFile(sub)
                            ?: parent.createDirectory(sub)
                            ?: return result
                        // Klaim berkunci seperti jalur SAF lain (issue
                        // #410): nama dipilih + dokumen dibuat atomik di
                        // dalam kunci nama, bukan cek-lalu-buat terpisah
                        // yang bisa tabrakan dengan klaim paralel senama.
                        val (target, _, createdDoc) = claimCustomDoc(subDir, fileName)
                            ?: return result
                        val input = appContext.contentResolver.openInputStream(uri)
                            ?: run {
                                // Target kosong yang BARU dibuat di atas
                                // dibuang; dokumen yang sudah ada (klaim
                                // lomba eksternal) tak disentuh (issue #447).
                                if (createdDoc) {
                                    runCatching { appContext.contentResolver.delete(target.uri, null, null) }
                                }
                                return result
                            }
                        try {
                            input.use { src ->
                                val out = appContext.contentResolver.openOutputStream(target.uri, "wt")
                                    ?: throw java.io.IOException("Failed to open organize destination")
                                out.use { dst -> src.copyTo(dst, IO_BUF) }
                            }
                        } catch (e: Exception) {
                            if (createdDoc) {
                                runCatching { appContext.contentResolver.delete(target.uri, null, null) }
                            }
                            throw e
                        }
                        appContext.contentResolver.delete(uri, null, null)
                        // Nama hasil klaim ikut dikembalikan: nama unik
                        // bisa beda dari fileName asal dan catatan item
                        // wajib memakai nama aktual (issue #457).
                        PublishResult(contentUri = target.uri.toString(), fileName = target.name)
                    }
                }
                !result.filePath.isNullOrEmpty() -> {
                    val file = File(result.filePath)
                    val parent = file.parentFile ?: return result
                    val subDir = File(parent, sub)
                    if (!subDir.isDirectory && !subDir.mkdirs()) return result
                    val target = uniqueTargetFile(File(subDir, file.name))
                    if (file.renameTo(target)) {
                        PublishResult(filePath = target.absolutePath, fileName = target.name)
                    } else {
                        // Klaim kosong uniqueTargetFile tertinggal bila rename
                        // gagal: hapus bila masih kosong seperti rename().
                        runCatching { if (target.isFile && target.length() == 0L) target.delete() }
                        result
                    }
                }
                else -> result
            }
        }.getOrDefault(result)
    }

    private val AUDIO_EXTS = setOf(
        "mp3", "m4a", "aac", "wav", "ogg", "flac", "opus", "wma", "mid"
    )
    private val DOC_EXTS = setOf(
        "pdf", "doc", "docx", "xls", "xlsx", "ppt", "pptx", "txt", "md",
        "csv", "json", "epub", "rtf"
    )

    private fun subfolderFor(fileName: String): String? {
        val ext = fileName.substringAfterLast('.', "").lowercase(java.util.Locale.ROOT)
        val kind = MediaLibrary.mediaKind(fileName)
        return when {
            kind == "video" -> "Videos"
            kind == "image" -> "Photos"
            ext in AUDIO_EXTS -> "Music"
            ext in DOC_EXTS -> "Documents"
            ext == "apk" -> "APK"
            else -> null
        }
    }

}

/** Direktori untuk StatFs bila tujuan custom berupa path file: pakai direktori
 *  itu bila ada, kalau tidak naik ke induk terdekat yang ada (satu filesystem
 *  yang sama). Kosong / `m:` (MediaStore) / SAF / relatif -> null = pakai
 *  volume default. StatFs hanya membaca, jadi traversal tak relevan di sini.
 *  Murni JVM agar bisa di-unit-test. */
internal fun statFsDirForFolderPath(folderPath: String): java.io.File? {
    val clean = folderPath.trim().removePrefix("f:").trim()
    if (clean.isEmpty() || clean.startsWith("m:")) return null
    val dir = java.io.File(clean)
    if (!dir.isAbsolute) return null
    var cur: java.io.File? = dir
    while (cur != null && !cur.exists()) cur = cur.parentFile
    return cur?.takeIf { it.isDirectory }
}

/** folderPath item -> direktori File persisnya: prefiks `f:` dibuang,
 *  spasi pinggir dibuang. Kosong / `m:` (MediaStore, bukan direktori
 *  File) -> null. Berbeda dari statFsDirForFolderPath yang hanya membaca
 *  (naik ke induk yang ada): pemanggil di sini (publish, sapuan yatim)
 *  memakai direktori persisnya, termasuk yang belum ada. Murni JVM agar
 *  bisa di-unit-test. */
internal fun dirForFolderPath(folderPath: String): java.io.File? {
    val clean = folderPath.trim()
    if (clean.isEmpty() || clean.startsWith("m:")) return null
    val path = clean.removePrefix("f:").trim()
    return if (path.isEmpty()) null else java.io.File(path)
}

/** Nama partial milik satu item yang wajib dilindungi sweeper yatim: basis
 *  `.part` SELALU ikut — item bersegmen yang jatuh ke single-stream (fallback
 *  saat Range ditolak) menulis ke basis tanpa sufiks, dan tanpa ini progres
 *  unduhan jeda-lama (>2 jam) terhapus sebagai yatim lalu mengulang dari nol.
 *  Temp HLS ikut dilindungi (issue #409): namanya BUKAN turunan nama item
 *  (`~<id8><basis>.video.part` dari DownloadEngine.downloadHls) dan sejak
 *  #385 isinya adalah data resume lintas-restart — tanpa perlindungan ini
 *  sapuan menghapusnya untuk jeda >2 jam dan resume mulai dari segmen 0.
 *  Murni agar bisa di-unit-test. */
internal fun protectedPartialNames(item: com.tasirin.httpdownloadmanager.data.DownloadItem): Set<String> {
    val base = FileNames.safe(item.fileName)
    return buildSet {
        add("$base.part")
        item.segments.forEach { add("$base.part.${it.index}") }
        // Turunan tempBase engine: basis tanpa ekstensi .ts/.mp4, prefiks
        // "~" + 8 char id. FileNames.safe diterapkan pada nama gabungan
        // persis seperti partialFile di engine. URUTAN WAJIB sama dengan
        // engine (issue #443): engine membuang sufiks dari nama MENTAH
        // (outName) lalu safe() pada gabungan; safe() lebih dulu di sini
        // menyimpang untuk nama tepi — nama reserved (CON.mp4) mendapat
        // awalan "_" hanya di jalur perlindungan, dan nama berakhiran
        // spasi/titik kehilangan sufiks yang bisa dibuang hanya di sini —
        // sehingga temp HLS nama-nama itu tetap tak terlindungi.
        val hlsBase = item.fileName.removeSuffix(".ts").removeSuffix(".mp4").ifBlank { item.fileName }
        val tempBase = "~" + item.id.take(8) + hlsBase
        for (kind in listOf("video", "audio")) {
            val temp = FileNames.safe("$tempBase.$kind") + ".part"
            add(temp)
            add("$temp.segs")
            add("$temp.spool")
        }
    }
}

private val PART_SEGMENT_TAIL_RE = Regex("""\.part\.\d+$""")
private val MERGE_STAGING_TAIL_RE = Regex("""\.merge\.\d+$""")

/** File internal yang boleh disapu bila yatim: partial segmen/basis plus
 *  staging merge (`.merge.<nanoTime>`) yang tertinggal bila proses mati di
 *  tengah merge, spool segmen HLS (`.part.spool`, issue #401) dan penanda
 *  resume HLS (`.part.segs`, #385) yang finally-nya hanya berjalan bila
 *  proses masih hidup. Pola WAJIB terjangkar di ekor nama: pola longgar
 *  ".part." di tengah nama ikut menyapu file FINAL selesai seperti
 *  `film.part.1.mp4` atau `game.part.2.rar` (pola nama arsip pecahan
 *  yang umum). Murni agar bisa di-unit-test. */
internal fun isSweepablePartial(name: String): Boolean =
    name.endsWith(".part") || PART_SEGMENT_TAIL_RE.containsMatchIn(name) ||
        MERGE_STAGING_TAIL_RE.containsMatchIn(name) ||
        name.endsWith(".part.spool") || name.endsWith(".part.segs")

private val UNIQUE_SUFFIX_RE = Regex("""^(.+) \([^()]+\)$""")

/** true bila [name] adalah staging publish (`<nama final>.part`, termasuk
 *  varian unik `<nama final> (n).part` dari uniqueTargetFile) milik salah
 *  satu nama final yang dikenal. Dipakai menyapu direktori TUJUAN di luar
 *  downloadDir (issue #402): di folder milik pengguna, pola longgar
 *  ".part" saja terlalu berbahaya — hanya staging yang basisnya persis
 *  nama final item dikenal yang boleh dihapus. Murni, bisa di-unit-test. */
internal fun isPublishStagingName(name: String, knownFinalNames: Set<String>): Boolean {
    if (!name.endsWith(".part")) return false
    val base = name.removeSuffix(".part")
    if (base in knownFinalNames) return true
    val stripped = UNIQUE_SUFFIX_RE.find(base)?.groupValues?.get(1) ?: return false
    return stripped in knownFinalNames
}
