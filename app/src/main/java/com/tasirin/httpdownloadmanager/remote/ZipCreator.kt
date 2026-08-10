package com.tasirin.httpdownloadmanager.remote

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
