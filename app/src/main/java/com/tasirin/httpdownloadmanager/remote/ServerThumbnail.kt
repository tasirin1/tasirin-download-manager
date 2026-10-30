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
import java.io.IOException
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong

/** Thumbnail generation & caching — di-extract dari HttpControlServer supaya
 *  file utama tidak terlalu panjang. Semua fungsi menerima [ctx] (app context)
 *  dan lambdas untuk mengecek izin akses file/URI. */

private class ThumbLock {
    val lastUse = AtomicLong(System.currentTimeMillis())

    /** Jumlah pemakai aktif: lock yang sedang menahan decode tidak
     *  boleh dieviksi walau lastUse-nya tua (issue #898). Dinaikkan
     *  oleh thumbLockFor di dalam kunci peta dan diturunkan pemanggil
     *  sesudah blok synchronized selesai. */
    val inUse = AtomicInteger(0)
}

private const val THUMB_FAILURE_TTL_MS = 30L * 60 * 1000

/** Cache kegagalan generate thumbnail per file: video korup / format yang
 *  tidak bisa di-decode tidak perlu dicoba ulang pada tiap request galeri
 *  (sebelumnya tiap scroll memicu MediaMetadataRetriever + tidak ada hasil).
 *  TTL mencegah file rusak "mengunci" thumbnail selamanya bila sudah diperbaiki. */
private val thumbFailures = ConcurrentHashMap<String, Long>()

/** Inode sumber yang menghasilkan thumbnail di slot path-saja (issue #735):
 *  kunci slot itu cuma path, jadi tanpa identitas fisik, thumbnail berkas
 *  lama bisa tersaji untuk berkas BARU di path yang sama selama jendela
 *  bertumbuhnya. Inode stabil selama satu berkas bertumbuh dan selalu
 *  berubah saat berkas diganti (publish rename = inode baru). */
private val thumbSlotInodes = ConcurrentHashMap<String, Long>()

/** Nomor inode berkas sumber untuk raw "f:"; null bila tak bisa dibaca —
 *  pemanggil jatuh ke perilaku lama (sajikan slot apa adanya). */
private fun thumbFileInode(raw: String): Long? = runCatching {
    android.system.Os.stat(raw.substring(2)).st_ino
}.getOrNull()

/** true bila kegagalan thumbnail masih dalam masa TTL (belum perlu dicoba lagi). */
internal fun isThumbFailureFresh(
    storedAt: Long,
    now: Long,
    ttlMs: Long = THUMB_FAILURE_TTL_MS
): Boolean = now - storedAt < ttlMs

/** Satu lock per media mencegah banyak permintaan thumbnail awal men-decode
 *  video yang sama secara paralel (hemat CPU/RAM di device kelas bawah).
 *  Lock tidak aktif dibuang agar browsing ribuan media tidak menumpuk RAM. */
private val thumbLocks = ConcurrentHashMap<String, ThumbLock>()

private fun thumbLockFor(key: String): ThumbLock {
    val now = System.currentTimeMillis()
    // Eviksi + getOrPut dalam satu lock yang sama: eviksi di luar lock bisa
    // membuang lock yang baru dibuat thread lain sehingga dua thread memegang
    // lock berbeda untuk key sama lalu decode paralel dan menulis cache JPEG
    // yang sama secara interleave (thumbnail korup tersaji permanen).
    return synchronized(thumbLocks) {
        if (thumbLocks.size > 512) {
            // Satu removeIf: buang lock menganggur >10 menit; lock yang
            // sedang dipakai tak pernah dibuang (issue #898) — decode
            // yang menggantung lama tampak menganggur dari lastUse.
            thumbLocks.entries.removeIf {
                it.key != key && it.value.inUse.get() == 0 &&
                    now - it.value.lastUse.get() > 600_000L
            }
            if (thumbLocks.size > 768) {
                val droppable = thumbLocks.entries
                    .filter { it.key != key && it.value.inUse.get() == 0 }
                    .sortedBy { it.value.lastUse.get() }
                val drop = minOf(thumbLocks.size - 512, droppable.size)
                for (i in 0 until drop) {
                    thumbLocks.remove(droppable[i].key)
                }
            }
        }
        thumbLocks.getOrPut(key) { ThumbLock() }.also {
            // Di dalam kunci peta: tak ada jendela di mana lock sudah
            // terambil tetapi belum terhitung dipakai.
            it.inUse.incrementAndGet()
        }
    }.also { it.lastUse.set(now) }
}

/** Cap identitas isi sumber thumbnail: mtime+ukuran untuk path berkas,
 *  DATE_MODIFIED+SIZE MediaStore untuk URI media. Kosong bila tak bisa
 *  dibaca (kunci jatuh ke path saja seperti perilaku lama). */
private fun thumbSourceStamp(ctx: Context, raw: String): String = when {
    raw.startsWith("f:") -> runCatching {
        val f = File(raw.substring(2))
        if (f.isFile) "${f.lastModified()}:${f.length()}" else ""
    }.getOrDefault("")
    raw.startsWith("u:") -> runCatching {
        val uri = raw.substring(2).toUri()
        ctx.contentResolver.query(
            uri,
            arrayOf(
                android.provider.MediaStore.MediaColumns.DATE_MODIFIED,
                android.provider.MediaStore.MediaColumns.SIZE
            ),
            null, null, null
        )?.use { c ->
            if (c.moveToFirst()) "${c.getLong(0)}:${c.getLong(1)}" else ""
        } ?: ""
    }.getOrDefault("")
    else -> ""
}

/** Jendela "masih bertumbuh": berkas yang mtime-nya dalam jendela ini
 *  dianggap sedang ditulis (unduhan berjalan). */
internal const val THUMB_GROWING_WINDOW_MS = 20_000L

/** Toleransi mtime masa depan (beda jam antar-perangkat): mtime sedikit
 *  di depan jam lokal masih wajar untuk berkas yang baru ditulis, tetapi
 *  mtime jauh di masa depan adalah cap yang salah, bukan pertumbuhan. */
internal const val THUMB_FUTURE_SKEW_MS = 60_000L

/** true bila cap [stamp] ("mtime:ukuran") menunjukkan berkas yang masih
 *  bertumbuh. Murni agar bisa di-unit-test. */
internal fun isThumbStampGrowing(stamp: String, nowMs: Long): Boolean {
    val mtime = stamp.substringBefore(':').toLongOrNull() ?: return false
    val age = nowMs - mtime
    // Tanpa batas masa depan, nowMs - mtime yang negatif selalu lolos
    // jendela: berkas bertanggal masa depan dianggap bertumbuh SELAMANYA
    // dan invalidasi thumbnail #399 tak pernah aktif untuknya (issue #440).
    return age < THUMB_GROWING_WINDOW_MS && age > -THUMB_FUTURE_SKEW_MS
}

internal fun getOrCreateThumb(
    ctx: Context,
    raw: String,
    isFsPathAllowed: (String) -> Boolean,
    isMediaUriAllowed: (Uri) -> Boolean
): File? {
    // Nama tampil sumber u: dihitung SEKALI di sini (issue #794) lalu
    // diteruskan ke kedua konsumennya — dulu isThumbSourceAllowed dan
    // generateThumb sama-sama me-resolusi (DocumentFile + query
    // DISPLAY_NAME) per cache-miss. Urutan izin dipertahankan: nama
    // hanya di-resolusi bila URI lolos isMediaUriAllowed.
    val mediaName: String? = if (raw.startsWith("u:")) {
        val uri = raw.substring(2).toUri()
        if (isMediaUriAllowed(uri)) resolveMediaDisplayName(ctx, uri) else null
    } else {
        null
    }
    if (!isThumbSourceAllowed(ctx, raw, isFsPathAllowed, isMediaUriAllowed, mediaName)) return null
    // Kunci cache memakai hash penuh 256-bit: 16 hex char (64 bit) bisa
    // tabrakan antar file dan menyajikan thumbnail milik video lain.
    // Cap isi (mtime+ukuran) ikut dikunci (issue #399): berkas yang
    // diganti di path yang sama — unduhan ulang bernama sama — mendapat
    // kunci baru sehingga thumbnail video lama tidak tersaji selamanya;
    // kelas perbaikan yang sama seperti invalidasi durasi #287/#325.
    // Pengecualian berkas yang MASIH BERTUMBUH (issue #418): cap-nya
    // berubah tiap potongan data, jadi kunci bercap tak pernah kena
    // cache dan meninggalkan satu .jpg yatim per cap. Selama mtime
    // masih dalam jendela bertumbuh, kunci jatuh ke path saja (satu
    // slot stabil); sesudah berkas diam, kunci bercap berlaku lagi dan
    // thumbnail dibuat ulang sekali dari isi final. Slot path-saja itu
    // dijaga inode (issue #735): berkas yang DIGANTI di path yang sama
    // berinode baru, jadi thumbnail berkas lama di slot tidak tersaji
    // untuknya — slot dibuat ulang sekali untuk inode baru.
    val stamp = thumbSourceStamp(ctx, raw)
    val growing = raw.startsWith("f:") && isThumbStampGrowing(stamp, System.currentTimeMillis())
    val fileInode: Long? = if (raw.startsWith("f:")) thumbFileInode(raw) else null
    // Berkas diam pun membawa inode dalam capnya (issue #897):
    // mtime+ukuran saja bisa identik pada penggantian ber-cap
    // terpelihara, sehingga thumbnail lama tersaji untuk isi baru.
    // Inode membedakan penggantian itu; stat gagal jatuh ke cap lama.
    val effectiveStamp = when {
        growing -> ""
        stamp.isNotEmpty() && fileInode != null -> "$stamp:$fileInode"
        else -> stamp
    }
    val key = if (effectiveStamp.isEmpty()) sha256Hex(raw) else sha256Hex("$raw|$effectiveStamp")
    val slotInode: Long? = if (growing) fileInode else null
    thumbFailures[key]?.let { at ->
        if (isThumbFailureFresh(at, System.currentTimeMillis())) return null
    }
    val dir = File(ctx.cacheDir, "thumbs").apply { runCatching { mkdirs() } }
    if (!dir.isDirectory) return null
    val cached = File(dir, "$key.jpg")
    val lock = thumbLockFor(key)
    try {
        return synchronized(lock) {
        // Slot path-saja basi bila inode sumbernya bukan yang menghasilkan
        // isi slot (issue #735): catatan hilang (proses baru) juga dianggap
        // basi — dibuat ulang sekali lebih aman daripada menyajikan
        // thumbnail berkas lain. slotInode null = stat gagal, sajikan
        // seperti perilaku lama.
        val slotStale = growing && slotInode != null && thumbSlotInodes[key] != slotInode
        if (cached.isFile && cached.length() > 0 && !slotStale) {
            cached
        } else {
            // Cek ulang cache kegagalan DI DALAM kunci (issue #753):
            // thread pendahulu di kunci yang sama bisa baru saja
            // mencatat kegagalan segar untuk kunci ini — tanpa cek
            // ulang, burst paralel untuk video korup yang sama mengulang
            // decode MediaMetadataRetriever mahal satu per satu, persis
            // beban yang hendak dicegah cache kegagalan itu.
            thumbFailures[key]?.let { at ->
                if (isThumbFailureFresh(at, System.currentTimeMillis())) return@synchronized null
            }
            val bmp = generateThumb(ctx, raw, isFsPathAllowed, isMediaUriAllowed, mediaName)
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
            if (growing && slotInode != null) {
                thumbSlotInodes[key] = slotInode
                if (thumbSlotInodes.size > 1024) {
                    // Pangkas entri LAIN sampai batas (issue #754):
                    // clear() polos ikut membuang entri yang barusan
                    // ditulis, sehingga request berikutnya menilai slot
                    // basi dan membuat thumbnail ulang sekali ekstra.
                    val keys = thumbSlotInodes.keys.iterator()
                    while (thumbSlotInodes.size > 1024 && keys.hasNext()) {
                        val k = keys.next()
                        if (k != key) thumbSlotInodes.remove(k)
                    }
                }
            }
            runCatching {
                // Tulis via staging lalu rename: gagal di tengah tak boleh
                // menyisakan file setengah jadi yang dikira cache valid.
                val staging = File(dir, "$key.jpg.tmp")
                try {
                    FileOutputStream(staging).use { out ->
                        if (!bmp.compress(Bitmap.CompressFormat.JPEG, 72, out)) {
                            throw IOException("Thumbnail compress failed")
                        }
                    }
                    if (!staging.renameTo(cached)) {
                        runCatching { cached.delete() }
                        if (!staging.renameTo(cached) || cached.length() <= 0) {
                            throw IOException("Thumbnail finalize failed")
                        }
                    }
                } finally {
                    runCatching { bmp.recycle() }
                    if (staging.exists()) runCatching { staging.delete() }
                }
                cached
            }.getOrElse {
                runCatching { cached.delete() }
                null
            }
        }
        }.also { lock.lastUse.set(System.currentTimeMillis()) }
    } finally {
        lock.inUse.decrementAndGet()
    }
}

/** Resolusi nama tampil URI media (issue #794): DocumentFile bisa
 *  mengembalikan nama kosong untuk URI MediaStore di sebagian
 *  perangkat, jadi fallback ke kolom DISPLAY_NAME langsung. */
private fun resolveMediaDisplayName(ctx: Context, uri: Uri): String {
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
    return name
}

private fun isThumbSourceAllowed(
    ctx: Context,
    raw: String,
    isFsPathAllowed: (String) -> Boolean,
    isMediaUriAllowed: (Uri) -> Boolean,
    mediaName: String? = null
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
            MediaLibrary.mediaKind(mediaName ?: resolveMediaDisplayName(ctx, uri)) == "video"
        }
        else -> false
    }
}

internal fun generateThumb(
    ctx: Context,
    raw: String,
    isFsPathAllowed: (String) -> Boolean,
    isMediaUriAllowed: (Uri) -> Boolean,
    mediaName: String? = null
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
                if (MediaLibrary.mediaKind(mediaName ?: resolveMediaDisplayName(ctx, uri)) != "video") return null
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
