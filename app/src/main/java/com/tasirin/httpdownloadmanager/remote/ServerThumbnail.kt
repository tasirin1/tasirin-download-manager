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

private const val THUMB_FAILURE_TTL_MS = 30L * 60 * 1000

/** Cache kegagalan generate thumbnail per file: video korup / format yang
 *  tidak bisa di-decode tidak perlu dicoba ulang pada tiap request galeri
 *  (sebelumnya tiap scroll memicu MediaMetadataRetriever + tidak ada hasil).
 *  TTL mencegah file rusak "mengunci" thumbnail selamanya bila sudah diperbaiki. */
private val thumbFailures = ConcurrentHashMap<String, Long>()

/** true bila kegagalan thumbnail masih dalam masa TTL (belum perlu dicoba lagi). */
internal fun isThumbFailureFresh(
    storedAt: Long,
    now: Long,
    ttlMs: Long = THUMB_FAILURE_TTL_MS
): Boolean = now - storedAt < ttlMs

/** Satu lock per media mencegah banyak permintaan thumbnail awal men-decode
 *  video yang sama secara paralel (hemat CPU/RAM di device Android 5+).
 *  Lock tidak aktif dibuang agar browsing ribuan media tidak menumpuk RAM. */
private val thumbLocks = ConcurrentHashMap<String, ThumbLock>()

private fun thumbLockFor(key: String): ThumbLock {
    val now = System.currentTimeMillis()
    if (thumbLocks.size > 512) {
        // Satu removeIf: buang lock menganggur >10 menit; bila masih penuh
        // (browsing ribuan media sekaligus), buang kelebihan tertua sekaligus
        // agar tak ada scan dua pass per request galeri.
        thumbLocks.entries.removeIf { it.key != key && now - it.value.lastUse.get() > 600_000L }
        if (thumbLocks.size > 768) {
            val sorted = thumbLocks.entries.sortedBy { it.value.lastUse.get() }
            val drop = thumbLocks.size - 512
            for (i in 0 until drop) {
                val k = sorted.getOrNull(i)?.key ?: break
                if (k != key) thumbLocks.remove(k)
            }
        }
    }
    // getOrPut tidak atomik: dua thread bisa memegang lock berbeda untuk key
    // sama lalu decode paralel dan menulis cache JPEG yang sama secara
    // interleave (thumbnail korup tersaji permanen). Kunci eksplisit.
    return synchronized(thumbLocks) {
        thumbLocks.getOrPut(key) { ThumbLock() }
    }.also { it.lastUse.set(now) }
}

internal fun getOrCreateThumb(
    ctx: Context,
    raw: String,
    isFsPathAllowed: (String) -> Boolean,
    isMediaUriAllowed: (Uri) -> Boolean
): File? {
    if (!isThumbSourceAllowed(ctx, raw, isFsPathAllowed, isMediaUriAllowed)) return null
    // Kunci cache memakai hash penuh 256-bit: 16 hex char (64 bit) bisa
    // tabrakan antar file dan menyajikan thumbnail milik video lain.
    val key = sha256Hex(raw)
    thumbFailures[key]?.let { at ->
        if (isThumbFailureFresh(at, System.currentTimeMillis())) return null
    }
    val dir = File(ctx.cacheDir, "thumbs").apply { runCatching { mkdirs() } }
    if (!dir.isDirectory) return null
    val cached = File(dir, "$key.jpg")
    val lock = thumbLockFor(key)
    return synchronized(lock) {
        if (cached.isFile && cached.length() > 0) {
            cached
        } else {
            val bmp = generateThumb(ctx, raw, isFsPathAllowed, isMediaUriAllowed)
                ?: run {
                    thumbFailures[key] = System.currentTimeMillis()
                    if (thumbFailures.size > 512) {
                        val cutoff = System.currentTimeMillis() - THUMB_FAILURE_TTL_MS
                        thumbFailures.entries.removeAll { it.value < cutoff }
                        // Bila semua masih fresh (mis. 1000 video korup
                        // sekaligus), pangkas tertua sampai batas agar map
                        // tidak tumbuh tanpa batas.
                        while (thumbFailures.size > 1024) {
                            val oldest = thumbFailures.entries.minByOrNull { it.value } ?: break
                            if (!thumbFailures.remove(oldest.key, oldest.value)) break
                        }
                    }
                    return@synchronized null
                }
            thumbFailures.remove(key)
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

private fun isThumbSourceAllowed(
    ctx: Context,
    raw: String,
    isFsPathAllowed: (String) -> Boolean,
    isMediaUriAllowed: (Uri) -> Boolean
): Boolean {
    return when {
        raw.startsWith("f:") -> {
            val file = File(raw.substring(2))
            file.isFile && isFsPathAllowed(file.absolutePath) &&
                MediaLibrary.mediaKind(file.name) == "video"
        }
        raw.startsWith("u:") -> {
            val uri = raw.substring(2).toUri()
            if (!isMediaUriAllowed(uri)) return false
            var name = DocumentFile.fromSingleUri(ctx, uri)?.name.orEmpty()
            if (name.isBlank()) {
                name = runCatching {
                    ctx.contentResolver.query(
                        uri,
                        arrayOf(android.provider.MediaStore.MediaColumns.DISPLAY_NAME),
                        null, null, null
                    )?.use { c -> if (c.moveToFirst()) c.getString(0) else null }
                }.getOrNull().orEmpty()
            }
            MediaLibrary.mediaKind(name) == "video"
        }
        else -> false
    }
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
                // DocumentFile bisa mengembalikan nama kosong untuk URI
                // MediaStore di sebagian perangkat (thumb selalu 404 walau
                // video valid): fallback ke kolom DISPLAY_NAME langsung.
                var name = DocumentFile.fromSingleUri(ctx, uri)?.name.orEmpty()
                if (name.isBlank()) {
                    name = runCatching {
                        ctx.contentResolver.query(
                            uri,
                            arrayOf(android.provider.MediaStore.MediaColumns.DISPLAY_NAME),
                            null, null, null
                        )?.use { c -> if (c.moveToFirst()) c.getString(0) else null }
                    }.getOrNull().orEmpty()
                }
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
