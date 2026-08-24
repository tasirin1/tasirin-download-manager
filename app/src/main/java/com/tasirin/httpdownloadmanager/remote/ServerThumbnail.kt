package com.tasirin.httpdownloadmanager.remote

import android.content.Context
import android.graphics.Bitmap
import android.media.MediaMetadataRetriever
import android.net.Uri
import androidx.core.net.toUri
import androidx.documentfile.provider.DocumentFile
import com.tasirin.httpdownloadmanager.util.MediaLibrary
import com.tasirin.httpdownloadmanager.util.sha256Hex
import com.tasirin.httpdownloadmanager.util.scaleDown
import java.io.File
import java.io.FileOutputStream
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong

/** Thumbnail generation & caching — di-extract dari HttpControlServer supaya
 *  file utama tidak terlalu panjang. Semua fungsi menerima [ctx] (app context)
 *  dan lambdas untuk mengecek izin akses file/URI. */

private class ThumbLock {
    val lastUse = AtomicLong(System.currentTimeMillis())
}

/** Satu lock per media mencegah banyak permintaan thumbnail awal men-decode
 *  video yang sama secara paralel (hemat CPU/RAM di device Android 5+).
 *  Lock tidak aktif dibuang agar browsing ribuan media tidak menumpuk RAM. */
private val thumbLocks = ConcurrentHashMap<String, ThumbLock>()

private fun thumbLockFor(key: String): ThumbLock {
    val now = System.currentTimeMillis()
    if (thumbLocks.size > 512) {
        val cutoff = thumbLocks.values
            .map { it.lastUse.get() }
            .sorted()
            .getOrNull(thumbLocks.size / 2) ?: now
        thumbLocks.entries.removeIf { it.value.lastUse.get() < cutoff }
    }
    return thumbLocks.getOrPut(key) { ThumbLock() }.also { it.lastUse.set(now) }
}

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
    val lock = thumbLockFor(key)
    return synchronized(lock) {
        if (cached.isFile && cached.length() > 0) {
            cached
        } else {
            val bmp = generateThumb(ctx, raw, isFsPathAllowed, isMediaUriAllowed)
                ?: return@synchronized null
            runCatching {
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
    }.also { lock.lastUse.set(System.currentTimeMillis()) }
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
                if (MediaLibrary.mediaKind(file.name) != "video") return null
                videoThumb(ctx, path = file.absolutePath)
            }
            raw.startsWith("u:") -> {
                val uri = raw.substring(2).toUri()
                if (!isMediaUriAllowed(uri)) return null
                val name = DocumentFile.fromSingleUri(ctx, uri)?.name.orEmpty()
                if (MediaLibrary.mediaKind(name) != "video") return null
                videoThumb(ctx, uri = uri)
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
