package com.tasirin.httpdownloadmanager.util

import android.content.ContentUris
import android.content.Context
import android.database.ContentObserver
import android.media.MediaScannerConnection
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.os.Handler
import android.os.HandlerThread
import android.provider.MediaStore
import android.util.Base64
import androidx.documentfile.provider.DocumentFile
import java.io.File

object MediaLibrary {

    private const val SCAN_TTL_MS = 15_000L
    /** Batas absolut entry yang di-hold di memori (galeri + remote web). */
    const val GALLERY_MAX_ENTRIES = 3000
    private const val THUMB_MAX_AGE_MS = 7L * 24 * 60 * 60 * 1000

    @Volatile
    private var scanCache: Triple<Long, List<MediaEntry>, Int>? = null
    private var observerRegistered = false

    /** Hasil scan galeri: [items] dibatasi sesuai [maxEntries] (halaman aktif +
     *  buffer, bukan 3000 entri penuh), [total] = jumlah entry unik sebenarnya
     *  — dipakai server untuk menghitung `hasMore` tanpa menahan daftar penuh. */
    class MediaScanResult(val items: List<MediaEntry>, val total: Int)

    /** Koleksi MediaStore untuk root folder media (dipakai saat browsing). */
    fun mediaCollectionForRoot(root: String): Uri {
        return when (root.trim('/').substringBefore('/').lowercase()) {
            "pictures", "dcim" -> MediaStore.Images.Media.EXTERNAL_CONTENT_URI
            "movies" -> MediaStore.Video.Media.EXTERNAL_CONTENT_URI
            else -> downloadsCollection()
        }
    }

    /** Koleksi MediaStore untuk menyimpan file; fallback ke Downloads bila
     *  MIME tidak cocok dengan koleksi media (mis. APK ke Pictures). */
    fun mediaCollectionFor(relativePath: String?, mime: String): Uri {
        val root = relativePath?.trim('/')?.substringBefore('/').orEmpty().lowercase()
        return when {
            root == "pictures" || root == "dcim" ->
                if (mime.startsWith("image/")) MediaStore.Images.Media.EXTERNAL_CONTENT_URI
                else downloadsCollection()
            root == "movies" ->
                if (mime.startsWith("video/")) MediaStore.Video.Media.EXTERNAL_CONTENT_URI
                else downloadsCollection()
            else -> downloadsCollection()
        }
    }

    /** Koleksi Downloads (API 29+); fallback aman "Files" untuk Android 5-9.
     *  Pemanggil sudah guard API 29, jadi fallback tidak pernah terpakai. */
    private fun downloadsCollection(): Uri =
        if (Build.VERSION.SDK_INT >= 29) MediaStore.Downloads.EXTERNAL_CONTENT_URI
        else MediaStore.Files.getContentUri("external")

    data class MediaEntry(
        val name: String,
        val size: Long,
        val modified: Long,
        val isVideo: Boolean,
        val token: String,
        val filePath: String? = null,
        val contentUri: String? = null,
        val isPartial: Boolean = false,
        val progressPercent: Int = -1,
        val durationMs: Long = 0L
    )

    private val IMAGE_EXTS = setOf("jpg", "jpeg", "png", "gif", "webp", "bmp")
    private val VIDEO_EXTS = setOf("mp4", "mkv", "webm", "avi", "mov", "3gp", "m4v", "mpg", "mpeg")

    fun mediaKind(name: String): String? {
        val ext = name.substringAfterLast('.', "").lowercase()
        return when {
            IMAGE_EXTS.contains(ext) -> "image"
            VIDEO_EXTS.contains(ext) -> "video"
            else -> null
        }
    }

    fun tokenForPath(path: String): String =
        Base64.encodeToString("f:$path".toByteArray(Charsets.UTF_8), Base64.NO_WRAP or Base64.URL_SAFE)

    fun tokenForUri(uri: String): String =
        Base64.encodeToString("u:$uri".toByteArray(Charsets.UTF_8), Base64.NO_WRAP or Base64.URL_SAFE)

    fun decodeToken(token: String): String? = runCatching {
        String(
            Base64.decode(token, Base64.URL_SAFE or Base64.NO_WRAP),
            Charsets.UTF_8
        )
    }.getOrNull()

    /** Scan dengan cache 15 detik + auto-invalidasi saat MediaStore berubah.
     *  [maxEntries] membatasi berapa entry di-hold di memori; galeri mulai dari
     *  halaman kecil lalu menaikkan limit saat pengguna scroll (load-more). */
    fun scan(
        context: Context,
        partialProgress: Map<String, Int> = emptyMap(),
        maxEntries: Int = GALLERY_MAX_ENTRIES
    ): MediaScanResult {
        val base = scanCached(context, maxEntries)
        if (partialProgress.isEmpty()) return base
        val items = base.items.map { entry ->
            if (entry.isPartial) {
                val p = partialProgress[entry.name]
                if (p != null) entry.copy(progressPercent = p) else entry
            } else {
                entry
            }
        }
        return MediaScanResult(items, base.total)
    }

    /** Beri tahu MediaStore ada file baru/berubah + invalidasi cache scan,
     *  supaya galeri langsung mendeteksi file yang baru ditulis aplikasi
     *  (download selesai, upload, pindah/rename dari file manager). */
    fun notifyMediaChanged(context: Context, vararg paths: String) {
        scanCache = null
        val valid = paths.map { it.trim() }.filter { it.isNotEmpty() }.distinct()
        if (valid.isEmpty()) return
        runCatching {
            MediaScannerConnection.scanFile(
                context.applicationContext, valid.toTypedArray(), null, null
            )
        }
    }

    /** Kondisi cache scan terpakai: masih dalam TTL dan (hasil lama memuat
     *  cukup entry untuk limit ini ATAU scan lama tuntas). Dipisah jadi fungsi
     *  murni supaya bisa di-unit-test. */
    fun scanCacheUsable(ageMs: Long, ttlMs: Long, itemsSize: Int, total: Int, limit: Int): Boolean =
        ageMs < ttlMs && (itemsSize >= limit || itemsSize == total)

    private fun scanCached(context: Context, maxEntries: Int): MediaScanResult {
        ensureObserver(context)
        val now = System.currentTimeMillis()
        val limit = maxEntries.coerceIn(1, GALLERY_MAX_ENTRIES)
        scanCache?.let { (ts, items, total) ->
            if (scanCacheUsable(now - ts, SCAN_TTL_MS, items.size, total, limit)) {
                return MediaScanResult(items.take(limit), total)
            }
        }
        val result = scanUncached(context, limit)
        scanCache = Triple(now, result.items, result.total)
        return result
    }

    /** Invalidasi cache saat ada foto/video/file baru atau terhapus. */
    private fun ensureObserver(context: Context) {
        if (observerRegistered) return
        synchronized(this) {
            if (observerRegistered) return
            observerRegistered = true
            runCatching {
                val appContext = context.applicationContext
                val thread = HandlerThread("media-scan-observer").apply { start() }
                val observer = object : ContentObserver(Handler(thread.looper)) {
                    private var lastInvalidate = 0L
                    override fun onChange(selfChange: Boolean) {
                        val now = System.currentTimeMillis()
                        if (now - lastInvalidate > 10_000L) {
                            lastInvalidate = now
                            scanCache = null
                        }
                    }
                }
                val resolver = appContext.contentResolver
                resolver.registerContentObserver(
                    MediaStore.Images.Media.EXTERNAL_CONTENT_URI, true, observer
                )
                resolver.registerContentObserver(
                    MediaStore.Video.Media.EXTERNAL_CONTENT_URI, true, observer
                )
                if (Build.VERSION.SDK_INT >= 29) {
                    resolver.registerContentObserver(
                        MediaStore.Downloads.EXTERNAL_CONTENT_URI, true, observer
                    )
                }
            }
        }
    }

    private fun scanUncached(context: Context, maxEntries: Int): MediaScanResult {
        val list = mutableListOf<MediaEntry>()

        fun addFile(f: File, isPartial: Boolean = false) {
            if (!f.isFile) return
            val name = if (isPartial) f.name.removeSuffix(".part") else f.name
            val kind = mediaKind(name) ?: return
            list.add(
                MediaEntry(
                    name = name,
                    size = f.length(),
                    modified = f.lastModified(),
                    isVideo = kind == "video",
                    token = tokenForPath(f.absolutePath),
                    filePath = f.absolutePath,
                    isPartial = isPartial,
                    progressPercent = -1
                )
            )
        }

        fun addDoc(df: DocumentFile) {
            if (!df.isFile) return
            val name = df.name ?: return
            val kind = mediaKind(name) ?: return
            val uri = df.uri.toString()
            list.add(
                MediaEntry(
                    name = name,
                    size = runCatching { df.length() }.getOrDefault(0L),
                    modified = runCatching { df.lastModified() }.getOrDefault(0L),
                    isVideo = kind == "video",
                    token = tokenForUri(uri),
                    contentUri = uri
                )
            )
        }

        // 1) Folder teks (Android 5-7 / folder kustom lewat path)
        StoragePrefs.getTextFolder(context)?.let { tf ->
            runCatching { File(tf).listFiles()?.forEach { addFile(it) } }
        }

        // 2) Folder kustom (SAF tree)
        StoragePrefs.getFolderUri(context)?.let { uri ->
            runCatching {
                DocumentFile.fromTreeUri(context, uri)?.listFiles()?.forEach { addDoc(it) }
            }
        }

        // 3) Folder internal aplikasi (termasuk file .part yang masih berjalan)
        runCatching {
            val dir = File(context.filesDir, "downloads")
            dir.listFiles()?.forEach { f ->
                if (f.isFile && f.name.endsWith(".part")) {
                    addFile(f, isPartial = true)
                } else {
                    addFile(f)
                }
            }
        }

        // 4) Foto & video dari device lewat MediaStore. Bila folder galeri foto
        //    / video diatur, scan dibatasi ke folder itu saja (bukan seluruh
        //    penyimpanan). Kosong = semua storage. Urutan: gambar dulu, video.
        runCatching {
            val resolver = context.contentResolver
            val collections = listOf(
                MediaStore.Images.Media.EXTERNAL_CONTENT_URI to false,
                MediaStore.Video.Media.EXTERNAL_CONTENT_URI to true
            )
            for ((collection, isVideo) in collections) {
                runCatching {
                    // DURATION hanya ada di tabel Video; projection gambar yang
                    // memuat kolom ini bisa bikin query gagal di sebagian device.
                    // RELATIVE_PATH baru ada di Android 10+; di bawah itu query
                    // dengan kolom ini akan error dan seluruh scan gagal.
                    val projection = buildList {
                        add(MediaStore.MediaColumns._ID)
                        add(MediaStore.MediaColumns.DISPLAY_NAME)
                        add(MediaStore.MediaColumns.SIZE)
                        add(MediaStore.MediaColumns.DATE_MODIFIED)
                        add(MediaStore.MediaColumns.DATA)
                        if (Build.VERSION.SDK_INT >= 29) add(MediaStore.MediaColumns.RELATIVE_PATH)
                        if (isVideo) add(MediaStore.Video.Media.DURATION)
                    }.toTypedArray()
                    resolver.query(
                        collection, projection, null, null,
                        "${MediaStore.MediaColumns.DATE_MODIFIED} DESC"
                    )?.use { c ->
                        val iId = c.getColumnIndexOrThrow(MediaStore.MediaColumns._ID)
                        val iName = c.getColumnIndexOrThrow(MediaStore.MediaColumns.DISPLAY_NAME)
                        val iSize = c.getColumnIndexOrThrow(MediaStore.MediaColumns.SIZE)
                        val iMod = c.getColumnIndexOrThrow(MediaStore.MediaColumns.DATE_MODIFIED)
                        val iData = c.getColumnIndexOrThrow(MediaStore.MediaColumns.DATA)
                        val iRel = c.getColumnIndex(MediaStore.MediaColumns.RELATIVE_PATH)
                        val iDur = c.getColumnIndex(MediaStore.Video.Media.DURATION)
                        while (c.moveToNext()) {
                            val name = c.getString(iName) ?: continue
                            val uri = ContentUris.withAppendedId(collection, c.getLong(iId)).toString()
                            val dataPath = c.getString(iData)?.takeIf { it.isNotBlank() }
                            list.add(
                                MediaEntry(
                                    name = name,
                                    size = c.getLong(iSize),
                                    modified = c.getLong(iMod) * 1000L,
                                    isVideo = isVideo,
                                    token = tokenForUri(uri),
                                    filePath = dataPath,
                                    contentUri = uri,
                                    durationMs = if (isVideo && iDur >= 0) c.getLong(iDur) else 0L
                                )
                            )
                        }
                    }
                }
            }
        }

        // 5) Folder Download publik (lama, untuk Android < 10 bila MediaStore
        //    tidak mengembalikan apa pun karena izin belum diberikan).
        if (Build.VERSION.SDK_INT < 29) {
            runCatching {
                val dir = Environment.getExternalStoragePublicDirectory(
                    Environment.DIRECTORY_DOWNLOADS
                )
                if (dir.isDirectory) dir.listFiles()?.forEach { addFile(it) }
            }
        }

        // Hapus duplikat: file yang sama bisa muncul sebagai path (f:) dan
        // sebagai MediaStore (u:) — dedupe berdasar path file bila ada.
        // Batasi jumlah entry yang di-hold di memori: cukup untuk 30 halaman
        // galeri (100/halaman) dan membatasi beban RAM di device Android 5+.
        val deduped = list
            .distinctBy { it.filePath ?: it.contentUri ?: it.token }
            .sortedByDescending { it.modified }
        return MediaScanResult(deduped.take(maxEntries), deduped.size)
    }

    /** Hapus thumbnail disk yang sudah lama tak terpakai (> 7 hari). Dipanggil
     *  saat aplikasi mulai; server remote punya pembersih serupa saat start. */
    fun cleanupOldThumbs(context: Context, maxAgeMs: Long = THUMB_MAX_AGE_MS): Long {
        var freed = 0L
        runCatching {
            val dir = File(context.cacheDir, "thumbs")
            if (!dir.isDirectory) return freed
            val now = System.currentTimeMillis()
            dir.listFiles()?.forEach { f ->
                if (f.isFile && now - f.lastModified() > maxAgeMs) {
                    runCatching {
                        val size = f.length()
                        if (f.delete()) freed += size
                    }
                }
            }
        }
        return freed
    }

}
