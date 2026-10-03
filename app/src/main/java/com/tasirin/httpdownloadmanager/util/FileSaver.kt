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
    private val WHITESPACE_RE = Regex("\\s+")

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
        private val MERGE_LOCK = Any()
    }

    fun mergeSegments(fileName: String, segmentCount: Int): File = synchronized(MERGE_LOCK) {
        val target = partialFile(fileName)
        // Staging unik per proses agar dua item bernama sama yang merge
        // paralel tidak saling menimpa file staging yang sama.
        val staging = File(target.parentFile, target.name + ".merge." + System.nanoTime())
        try {
            BufferedOutputStream(staging.outputStream()).use { out ->
                for (index in 0 until segmentCount) {
                    val part = partialFile(fileName, index)
                    if (!part.exists()) throw IOException("Segment $index not found")
                    BufferedInputStream(part.inputStream(), 64 * 1024).use { input -> input.copyTo(out, 64 * 1024) }
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
        (0 until segmentCount).map { partialFile(fileName, it) }.forEach { part -> runCatching { part.delete() } }
        return target
    }

    fun publishToPath(partial: File, fileName: String, folder: String): PublishResult? {
        val dir = File(folder)
        if (!dir.isDirectory && !dir.mkdirs()) return null
        if (!dir.isDirectory) return null
        return runCatching {
            val target = uniqueTargetFile(File(dir, fileName))
            try {
                target.outputStream().use { out -> partial.inputStream().use { it.copyTo(out) } }
            } catch (e: Exception) {
                // Gagal di tengah salin: buang target setengah jadi agar tidak
                // tertinggal sebagai file korup di folder tujuan.
                runCatching { target.delete() }
                throw e
            }
            partial.delete()
            MediaLibrary.notifyMediaChanged(appContext, target.absolutePath)
            PublishResult(filePath = target.absolutePath, fileName = target.name)
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

    private fun writeCustomFolder(
        fileName: String,
        folderUri: Uri,
        writer: (OutputStream) -> Unit
    ): PublishResult? = runCatching {
        val tree = DocumentFile.fromTreeUri(appContext, folderUri) ?: return null
        val unique = uniqueDocumentName(tree, fileName)
        val target = tree.findFile(unique)
            ?: tree.createFile(MimeTypes.forFile(unique), unique)
            ?: return null
        val output = appContext.contentResolver.openOutputStream(target.uri, "wt") ?: return null
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
        val unique = uniqueDocumentName(tree, fileName)
        val target = tree.findFile(unique)
            ?: tree.createFile(MimeTypes.forFile(unique), unique)
            ?: return null
        val output = appContext.contentResolver.openOutputStream(target.uri, "wt")
            ?: return null
        try {
            output.use { out ->
                partial.inputStream().use { input -> input.copyTo(out) }
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
        val target = uniqueTargetFile(File(dir, fileName))
        try {
            target.outputStream().use { out -> partial.inputStream().use { it.copyTo(out) } }
        } catch (e: Exception) {
            // Gagal di tengah salin: buang target setengah jadi agar tidak
            // tertinggal sebagai file korup di folder teks (seperti publishToPath).
            runCatching { target.delete() }
            throw e
        }
        partial.delete()
        MediaLibrary.notifyMediaChanged(appContext, target.absolutePath)
        PublishResult(filePath = target.absolutePath, fileName = target.name)
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
                if (first.length >= 32) return algo to first.lowercase()
            }
        }
        return null
    }

    fun cleanupOrphanPartials(items: List<DownloadItem>): Long {
        var freed = 0L
        runCatching {
            val expected = buildSet {
                items.forEach { item -> addAll(protectedPartialNames(item)) }
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
        }
        return freed
    }

    private fun publishToMediaStore(partial: File, fileName: String): PublishResult {
        val result = runCatching {
            saveToMediaStore(fileName, null) { out ->
                partial.inputStream().use { it.copyTo(out) }
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
                val target = uniqueTargetFile(File(publicDir, fileName))
                try {
                    target.outputStream().use { out -> partial.inputStream().use { it.copyTo(out) } }
                    partial.delete()
                    MediaLibrary.notifyMediaChanged(appContext, target.absolutePath)
                    return PublishResult(filePath = target.absolutePath, fileName = target.name)
                } catch (_: Exception) {
                    // Gagal di tengah salin: buang target setengah jadi agar
                    // tidak tertinggal sebagai file korup di folder publik.
                    runCatching { target.delete() }
                    // fallback ke penyimpanan internal
                }
            }
        }
        return publishToInternal(partial, fileName)
    }

    private fun publishToInternal(partial: File, fileName: String): PublishResult {
        val target = uniqueTargetFile(File(downloadDir, fileName))
        try {
            target.outputStream().use { out -> partial.inputStream().use { it.copyTo(out) } }
        } catch (e: Exception) {
            // Target setengah jadi tidak disapu pemindaian orphan (.part saja):
            // hapus di sini agar tidak yatim selamanya.
            runCatching { target.delete() }
            throw e
        }
        partial.delete()
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

    /** Rename file di disk/MediaStore. Kembalikan path/URI baru bila berhasil
     *  (dipakai DownloadEngine untuk update DownloadItem.filePath). */
    fun rename(item: DownloadItem, newName: String): String? {
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
                            uniqueMediaStoreName(clean, rel)
                        } else {
                            clean
                        }
                        val values = ContentValues().apply {
                            put(MediaStore.Downloads.DISPLAY_NAME, finalName)
                        }
                        val ok = appContext.contentResolver.update(uri, values, null, null) > 0
                        if (ok) item.contentUri else null
                    } else {
                        val newUri = DocumentsContract.renameDocument(appContext.contentResolver, uri, clean)
                        if (newUri != null) newUri.toString() else null
                    }
                }
                !item.filePath.isNullOrEmpty() -> {
                    val file = File(item.filePath)
                    // uniqueTargetFile: renameTo menimpa target yang ada tanpa
                    // peringatan — file item lain bisa hilang diam-diam.
                    val target = uniqueTargetFile(File(file.parentFile, clean))
                    if (file.exists() && file.renameTo(target)) {
                        MediaLibrary.notifyMediaChanged(appContext, file.absolutePath, target.absolutePath)
                        target.absolutePath
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
            val unique = uniqueDocumentName(tree, item.fileName)
            val target = tree.createFile(MimeTypes.forFile(unique), unique)
                ?: return null
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
                    out.use { dst -> src.copyTo(dst) }
                }
            } catch (e: Exception) {
                runCatching { target.delete() }
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
            val existing = mutableSetOf<String>()
            val selection = relativePath?.let { "${MediaStore.Downloads.RELATIVE_PATH}=?" }
            val args = relativePath?.let { arrayOf(it.trim('/') + "/") }
            resolver.query(
                col,
                arrayOf(MediaStore.Downloads.DISPLAY_NAME),
                selection,
                args,
                null
            )?.use { c ->
                val idx = c.getColumnIndexOrThrow(MediaStore.Downloads.DISPLAY_NAME)
                while (c.moveToNext()) {
                    c.getString(idx)?.let(existing::add)
                }
            }
            FileNames.unique(fileName) { existing.contains(it) }
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
                        val values = ContentValues().apply {
                            put(MediaStore.MediaColumns.RELATIVE_PATH, "Download/$sub/")
                        }
                        appContext.contentResolver.update(uri, values, null, null)
                        result
                    } else {
                        val doc = DocumentFile.fromSingleUri(appContext, uri) ?: return result
                        val parent = doc.parentFile ?: return result
                        val subDir = parent.findFile(sub)
                            ?: parent.createDirectory(sub)
                            ?: return result
                        val target = subDir.findFile(fileName)
                            ?: subDir.createFile(MimeTypes.forFile(fileName), fileName)
                            ?: return result
                        val input = appContext.contentResolver.openInputStream(uri)
                            ?: run {
                                // Target kosong sudah terlanjur dibuat di atas.
                                runCatching { appContext.contentResolver.delete(target.uri, null, null) }
                                return result
                            }
                        try {
                            input.use { src ->
                                val out = appContext.contentResolver.openOutputStream(target.uri, "wt")
                                    ?: throw java.io.IOException("Failed to open organize destination")
                                out.use { dst -> src.copyTo(dst) }
                            }
                        } catch (e: Exception) {
                            runCatching { appContext.contentResolver.delete(target.uri, null, null) }
                            throw e
                        }
                        appContext.contentResolver.delete(uri, null, null)
                        PublishResult(contentUri = target.uri.toString())
                    }
                }
                !result.filePath.isNullOrEmpty() -> {
                    val file = File(result.filePath)
                    val parent = file.parentFile ?: return result
                    val subDir = File(parent, sub)
                    if (!subDir.isDirectory && !subDir.mkdirs()) return result
                    val target = uniqueTargetFile(File(subDir, file.name))
                    if (file.renameTo(target)) {
                        PublishResult(filePath = target.absolutePath)
                    } else {
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
        val ext = fileName.substringAfterLast('.', "").lowercase()
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

/** Nama partial milik satu item yang wajib dilindungi sweeper yatim: basis
 *  `.part` SELALU ikut — item bersegmen yang jatuh ke single-stream (fallback
 *  saat Range ditolak) menulis ke basis tanpa sufiks, dan tanpa ini progres
 *  unduhan jeda-lama (>2 jam) terhapus sebagai yatim lalu mengulang dari nol.
 *  Murni agar bisa di-unit-test. */
internal fun protectedPartialNames(item: com.tasirin.httpdownloadmanager.data.DownloadItem): Set<String> {
    val base = FileNames.safe(item.fileName)
    return buildSet {
        add("$base.part")
        item.segments.forEach { add("$base.part.${it.index}") }
    }
}

/** File internal yang boleh disapu bila yatim: partial segmen/basis plus
 *  staging merge (`.merge.`) yang tertinggal bila proses mati di tengah merge.
 *  Murni agar bisa di-unit-test. */
internal fun isSweepablePartial(name: String): Boolean =
    name.endsWith(".part") || ".part." in name || ".merge." in name
