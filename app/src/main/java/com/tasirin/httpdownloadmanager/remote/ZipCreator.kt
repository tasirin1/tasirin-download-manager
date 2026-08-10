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

    fun zipFile(zos: ZipOutputStream, file: File, prefix: String) {
        val entryPath = if (prefix.isEmpty()) file.name else "$prefix/${file.name}"
        if (file.isDirectory) {
            val children = runCatching { file.listFiles() }.getOrNull()
            if (children.isNullOrEmpty()) {
                zos.putNextEntry(ZipEntry("$entryPath/"))
                zos.closeEntry()
                return
            }
            children.sortedBy { it.name.lowercase() }.forEach { child ->
                zipFile(zos, child, entryPath)
            }
        } else if (file.isFile) {
            zos.putNextEntry(ZipEntry(entryPath))
            file.inputStream().use { it.copyTo(zos) }
            zos.closeEntry()
        }
    }

    /** ZIP daftar token media (dipakai /api/media_zip: unduh banyak foto/video). */
    fun zipTokens(zos: ZipOutputStream, tokens: List<String>, context: Context) {
        val used = mutableMapOf<String, Int>()
        tokens.forEach { token ->
            val raw = MediaLibrary.decodeToken(token) ?: return@forEach
            runCatching {
                val name: String
                val input: java.io.InputStream?
                if (raw.startsWith("f:")) {
                    val f = File(raw.removePrefix("f:"))
                    name = f.name
                    input = if (f.isFile) f.inputStream() else null
                } else {
                    val uri = raw.removePrefix("u:").toUri()
                    name = displayNameFor(context, uri)
                    input = context.contentResolver.openInputStream(uri)
                }
                if (input == null) return@runCatching
                val entry = uniqueZipName(name, used)
                zos.putNextEntry(ZipEntry(entry))
                input.use { it.copyTo(zos) }
                zos.closeEntry()
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

    private fun uniqueZipName(base: String, used: MutableMap<String, Int>): String {
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
        runCatching {
            resolver.query(
                collection, projection,
                "${MediaStore.MediaColumns.RELATIVE_PATH} LIKE ?",
                arrayOf("$folder%"), null
            )?.use { c ->
                val iId = c.getColumnIndexOrThrow(MediaStore.MediaColumns._ID)
                val iName = c.getColumnIndexOrThrow(MediaStore.MediaColumns.DISPLAY_NAME)
                val iRel = c.getColumnIndexOrThrow(MediaStore.MediaColumns.RELATIVE_PATH)
                while (c.moveToNext()) {
                    val relPath = c.getString(iRel) ?: continue
                    val name = c.getString(iName) ?: continue
                    if (!relPath.startsWith(folder)) continue
                    val entry = relPath.removePrefix(folder) + name
                    resolver.openInputStream(
                        ContentUris.withAppendedId(collection, c.getLong(iId))
                    )?.use { input ->
                        zos.putNextEntry(ZipEntry(entry))
                        input.copyTo(zos)
                        zos.closeEntry()
                    }
                }
            }
        }
    }
}
