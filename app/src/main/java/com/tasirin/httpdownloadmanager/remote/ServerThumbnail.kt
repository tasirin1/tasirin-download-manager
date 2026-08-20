package com.tasirin.httpdownloadmanager.remote

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.media.MediaMetadataRetriever
import android.net.Uri
import androidx.documentfile.provider.DocumentFile
import com.tasirin.httpdownloadmanager.util.MediaLibrary
import com.tasirin.httpdownloadmanager.util.sha256Hex
import com.tasirin.httpdownloadmanager.util.scaleDown
import java.io.File
import java.io.FileOutputStream

/** Thumbnail generation & caching — di-extract dari HttpControlServer supaya
 *  file utama tidak terlalu panjang. Semua fungsi menerima [ctx] (app context)
 *  dan lambdas untuk mengecek izin akses file/URI. */

internal fun getOrCreateThumb(
    ctx: Context,
    raw: String,
    isFsPathAllowed: (String) -> Boolean,
    isMediaUriAllowed: (Uri) -> Boolean
): File? {
    val key = sha256Hex(raw).take(16)
    val dir = File(ctx.cacheDir, "thumbs").apply { runCatching { mkdirs() } }
    if (!dir.isDirectory) return null
    val cached = File(dir, "$key.jpg")
    if (cached.isFile && cached.length() > 0) return cached
    val bmp = generateThumb(ctx, raw, isFsPathAllowed, isMediaUriAllowed) ?: return null
    return runCatching {
        val out = FileOutputStream(cached)
        try {
            bmp.compress(Bitmap.CompressFormat.JPEG, 72, out)
        } finally {
            runCatching { out.close() }
            runCatching { bmp.recycle() }
        }
        cached
    }.getOrNull()
}

internal fun generateThumb(
    ctx: Context,
    raw: String,
    isFsPathAllowed: (String) -> Boolean,
    isMediaUriAllowed: (Uri) -> Boolean
): Bitmap? {
    return runCatching {
        when {
            raw.startsWith("f:") -> {
                val file = File(raw.substring(2))
                if (!file.isFile || !isFsPathAllowed(file.absolutePath)) return null
                if (MediaLibrary.mediaKind(file.name) == "video") {
                    videoThumb(ctx, path = file.absolutePath)
                } else {
                    imageThumb(ctx, path = file.absolutePath)
                }
            }
            raw.startsWith("u:") -> {
                val uri = raw.substring(2).toUri()
                if (!isMediaUriAllowed(uri)) return null
                val name = DocumentFile.fromSingleUri(ctx, uri)?.name.orEmpty()
                if (MediaLibrary.mediaKind(name) == "video") {
                    videoThumb(ctx, uri = uri)
                } else {
                    imageThumb(ctx, uri = uri)
                }
            }
            else -> null
        }
    }.getOrNull()
}

internal fun videoThumb(
    ctx: Context,
    path: String? = null,
    uri: Uri? = null
): Bitmap? {
    if (path == null && uri == null) return null
    val mmr = MediaMetadataRetriever()
    return try {
        if (path != null) {
            mmr.setDataSource(path)
        } else {
            mmr.setDataSource(ctx, uri)
        }
        val frame = mmr.getFrameAtTime(
            1_000_000L, MediaMetadataRetriever.OPTION_CLOSEST_SYNC
        ) ?: return null
        scaleDown(frame, 480)
    } catch (_: Exception) {
        null
    } finally {
        runCatching { mmr.release() }
    }
}

internal fun imageThumb(
    ctx: Context,
    path: String? = null,
    uri: Uri? = null
): Bitmap? {
    return runCatching {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        if (path != null) {
            BitmapFactory.decodeFile(path, bounds)
        } else {
            uri?.let {
                ctx.contentResolver.openInputStream(it)?.use { s ->
                    BitmapFactory.decodeStream(s, null, bounds)
                }
            }
        }
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
        var sample = 1
        while (bounds.outWidth / (sample * 2) >= 480 &&
            bounds.outHeight / (sample * 2) >= 480
        ) {
            sample *= 2
        }
        val opts = BitmapFactory.Options().apply { inSampleSize = sample }
        val bmp = if (path != null) {
            BitmapFactory.decodeFile(path, opts)
        } else {
            uri?.let {
                ctx.contentResolver.openInputStream(it)?.use { s ->
                    BitmapFactory.decodeStream(s, null, opts)
                }
            }
        } ?: return null
        scaleDown(bmp, 480)
    }.getOrNull()
}
