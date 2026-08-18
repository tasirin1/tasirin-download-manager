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
import java.io.BufferedOutputStream
import java.io.OutputStream

class FileSaver(context: Context) {
    private val WHITESPACE_RE = Regex("\\s+")

    private val appContext = context.applicationContext
    private val downloadDir = File(appContext.filesDir, "downloads").apply { mkdirs() }
    private val customFolderUri = StoragePrefs.getFolderUri(appContext)

    data class PublishResult(
        val contentUri: String? = null,
        val filePath: String? = null,
        val fileName: String? = null
    )

    fun partialFile(fileName: String, segment: Int? = null): File {
        val suffix = if (segment != null) ".part.$segment" else ".part"
        return File(downloadDir, "$fileName$suffix")
    }

    fun partialFiles(item: DownloadItem): List<File> {
        if (item.segments.isEmpty()) {
            return listOf(partialFile(item.fileName))
        }
        return item.segments.map { partialFile(item.fileName, it.index) }
    }

    fun mergeSegments(fileName: String, segmentCount: Int): File {
        val target = partialFile(fileName)
        BufferedOutputStream(target.outputStream()).use { out ->
            for (i in 0 until segmentCount) {
                val part = partialFile(fileName, i)
                if (!part.exists()) throw IOException("Segment $i not found")
                part.inputStream().use { input -> input.copyTo(out) }
                part.delete()
            }
        }
        return target
    }

    fun publishToPath(partial: File, fileName: String, folder: String): PublishResult? {
        val dir = File(folder)
        if (!dir.isDirectory && !dir.mkdirs()) return null
        if (!dir.isDirectory) return null
        return runCatching {
            val target = uniqueTargetFile(File(dir, fileName))
            target.outputStream().use { out -> partial.inputStream().use { it.copyTo(out) } }
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
        val folderUri = customFolderUri
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
                return saveToMediaStore(fileName, cleanFolder.substring(2), writer)
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
        customFolderUri?.let { uri ->
            writeCustomFolder(fileName, uri, writer)?.let { return it }
        }
        StoragePrefs.getTextFolder(appContext)?.let { tf ->
            val dir = File(tf)
            if (dir.isDirectory || dir.mkdirs()) {
                val target = File(dir, fileName)
                target.outputStream().use { out -> writer(out) }
                return PublishResult(filePath = target.absolutePath)
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
        output.use { out ->
            partial.inputStream().use { input -> input.copyTo(out) }
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
        target.outputStream().use { out -> partial.inputStream().use { it.copyTo(out) } }
        partial.delete()
        MediaLibrary.notifyMediaChanged(appContext, target.absolutePath)
        PublishResult(filePath = target.absolutePath, fileName = target.name)
    }.getOrNull()

    fun freeBytes(): Long = runCatching {
        StatFs(downloadDir.absolutePath).availableBytes
    }.getOrDefault(Long.MAX_VALUE)

    fun destinationFreeBytes(): Long {
        val textFolder = StoragePrefs.getTextFolder(appContext)
        if (textFolder != null) {
            val dir = File(textFolder)
            if (dir.isDirectory) {
                return runCatching {
                    StatFs(dir.absolutePath).availableBytes
                }.getOrDefault(freeBytes())
            }
        }
        return freeBytes()
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
                val first = runCatching {
                    side.readText().trim().split(WHITESPACE_RE).firstOrNull().orEmpty()
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
                items.forEach { item ->
                    if (item.segments.isEmpty()) {
                        add(partialFile(item.fileName).name)
                    } else {
                        item.segments.forEach { seg ->
                            add(partialFile(item.fileName, seg.index).name)
                        }
                    }
                }
            }
            downloadDir.listFiles()?.forEach { f ->
                val name = f.name
                if ((name.endsWith(".part") || name.contains(".part.")) && name !in expected) {
                    runCatching {
                        val size = f.length()
                        if (f.delete()) freed += size
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
                    // fallback ke penyimpanan internal
                }
            }
        }
        return publishToInternal(partial, fileName)
    }

    private fun publishToInternal(partial: File, fileName: String): PublishResult {
        val target = uniqueTargetFile(File(downloadDir, fileName))
        target.outputStream().use { out -> partial.inputStream().use { it.copyTo(out) } }
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
        if (newName.isBlank() || newName == item.fileName) return null
        return runCatching {
            when {
                !item.contentUri.isNullOrEmpty() -> {
                    val uri = item.contentUri.toUri()
                    if (Build.VERSION.SDK_INT >= 29 && uri.authority == MediaStore.AUTHORITY) {
                        val rel = mediaRelativePath(uri)?.trim('/')
                        val finalName = if (rel != null) {
                            uniqueMediaStoreName(newName, rel)
                        } else {
                            newName
                        }
                        val values = ContentValues().apply {
                            put(MediaStore.Downloads.DISPLAY_NAME, finalName)
                        }
                        val ok = appContext.contentResolver.update(uri, values, null, null) > 0
                        if (ok) item.contentUri else null
                    } else {
                        val newUri = DocumentsContract.renameDocument(appContext.contentResolver, uri, newName)
                        if (newUri != null) newUri.toString() else null
                    }
                }
                !item.filePath.isNullOrEmpty() -> {
                    val file = File(item.filePath)
                    val target = File(file.parentFile, newName)
                    if (file.exists() && file.renameTo(target)) {
                        MediaLibrary.notifyMediaChanged(appContext, file.absolutePath, target.absolutePath)
                        target.absolutePath
                    } else null
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
            val input = when {
                !item.contentUri.isNullOrEmpty() ->
                    appContext.contentResolver.openInputStream(item.contentUri.toUri())
                !item.filePath.isNullOrEmpty() -> File(item.filePath).inputStream()
                else -> null
            } ?: return null
            input.use { src ->
                val out = appContext.contentResolver.openOutputStream(target.uri, "wt")
                    ?: return null
                out.use { dst -> src.copyTo(dst) }
            }
            deleteFiles(item)
            PublishResult(contentUri = target.uri.toString(), fileName = target.name)
        }.getOrNull()
    }

    private fun uniqueTargetFile(file: File): File {
        if (!file.exists()) return file
        val parent = file.parentFile
        val unique = FileNames.unique(file.name) { File(parent, it).exists() }
        return File(parent, unique)
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
                        val input = appContext.contentResolver.openInputStream(uri) ?: return result
                        input.use { src ->
                            val out = appContext.contentResolver.openOutputStream(target.uri, "wt")
                                ?: return result
                            out.use { dst -> src.copyTo(dst) }
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
