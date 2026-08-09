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
import com.tasirin.httpdownloadmanager.App
import java.io.File

object MediaLibrary {

    private const val SCAN_TTL_MS = 15_000L

    @Volatile
    private var scanCache: Pair<Long, List<MediaEntry>>? = null
    private var observerRegistered = false

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

    /** Scan dengan cache 5 detik + auto-invalidasi saat MediaStore berubah. */
    fun scan(context: Context, partialProgress: Map<String, Int> = emptyMap()): List<MediaEntry> {
        val base = scanCached(context)
        if (partialProgress.isEmpty()) return base
        return base.map { entry ->
            if (entry.isPartial) {
                val p = partialProgress[entry.name]
                if (p != null) entry.copy(progressPercent = p) else entry
            } else {
                entry
            }
        }
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

    private fun scanCached(context: Context): List<MediaEntry> {
        ensureObserver(context)
        val now = System.currentTimeMillis()
        scanCache?.let { (ts, list) ->
            if (now - ts < SCAN_TTL_MS) return list
        }
        val list = scanUncached(context)
        scanCache = now to list
        return list
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
                    override fun onChange(selfChange: Boolean) {
                        scanCache = null
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

    private fun scanUncached(context: Context): List<MediaEntry> {
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
        val galleryImageFolder = StoragePrefs.getGalleryImageFolder(context)
        val galleryVideoFolder = StoragePrefs.getGalleryVideoFolder(context)
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
                            val folderCfg = if (isVideo) galleryVideoFolder else galleryImageFolder
                            if (folderCfg != null) {
                                val dataPath0 = c.getString(iData)?.takeIf { it.isNotBlank() }
                                val relPath0 = if (iRel >= 0) c.getString(iRel) else null
                                if (!mediaInFolder(dataPath0, relPath0, folderCfg)) continue
                            }
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

        // 4b) MediaStore Android lama (API < 29) sering tidak mengindeks file
        //     baru yang ditulis lewat file manager/upload. Scan folder galeri
        //     langsung dari filesystem biar file baru langsung terdeteksi.
        if (Build.VERSION.SDK_INT < 29 || StoragePrefs.isFsFullAccessEnabled(context)) {
            runCatching {
                listOfNotNull(galleryImageFolder, galleryVideoFolder).forEach { cfg ->
                    val dir = galleryDir(cfg)
                    if (dir != null && dir.isDirectory) {
                        dir.listFiles()?.forEach { addFile(it) }
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

        if (galleryImageFolder != null || galleryVideoFolder != null) {
            App.logEvent(
                "GALERI SCAN: ${list.count { !it.isVideo }} foto, " +
                    "${list.count { it.isVideo }} video " +
                    "(foto: ${galleryImageFolder ?: "semua"}, video: ${galleryVideoFolder ?: "semua"})"
            )
        }

        // Hapus duplikat: file yang sama bisa muncul sebagai path (f:) dan
        // sebagai MediaStore (u:) — dedupe berdasar path file bila ada.
        return list
            .distinctBy { it.filePath ?: it.contentUri ?: it.token }
            .sortedByDescending { it.modified }
    }

    /** Cek apakah file media masuk folder galeri terpilih.
     *  Format konfigurasi: "f:/path" (atau path polos) atau "m:RelativePath".
     *  Kosong = semua storage. */
    /** Resolve konfigurasi folder galeri ("f:/path" atau path polos) menjadi
     *  File tujuan di filesystem; null untuk format "m:RelativePath". */
    private fun galleryDir(config: String): File? {
        val raw = config.trim().removePrefix("f:").trim()
        if (raw.isEmpty()) return null
        val norm = when {
            raw == "sdcard" -> "/storage/emulated/0"
            raw.startsWith("sdcard/") -> "/storage/emulated/0/" + raw.removePrefix("sdcard")
            raw.startsWith("/sdcard") -> "/storage/emulated/0" + raw.removePrefix("/sdcard")
            raw == "mnt/sdcard" -> "/storage/emulated/0"
            raw.startsWith("mnt/sdcard/") -> "/storage/emulated/0/" + raw.removePrefix("mnt/sdcard")
            raw.startsWith("/mnt/sdcard") -> "/storage/emulated/0" + raw.removePrefix("/mnt/sdcard")
            raw.startsWith("/storage/emulated/0") -> raw
            raw.startsWith("/") -> raw
            raw == "storage/self/primary" -> "/storage/emulated/0"
            raw.startsWith("storage/self/primary/") ->
                "/storage/emulated/0/" + raw.removePrefix("storage/self/primary")
            else -> "/" + raw.trim('/')
        }
        return File(norm)
    }

    private fun mediaInFolder(filePath: String?, relativePath: String?, config: String): Boolean {
        val cfg = config.trim()
        if (cfg.isEmpty()) return true
        if (cfg.startsWith("m:") || cfg.startsWith("M:")) {
            val rel = cfg.removePrefix("m:").removePrefix("M:").trim('/')
            if (rel.isEmpty()) return true
            if (relativePath != null) return relativePath.trim('/').startsWith(rel)
            val fp = filePath?.replace('\\', '/') ?: return false
            return fp.substringAfterLast("/storage/emulated/0/", fp).trim('/').startsWith(rel)
        }
        val dir = cfg.removePrefix("f:").trim('/')
        if (dir.isEmpty()) return true
        val fp = filePath?.replace('\\', '/') ?: return false
        // MediaStore melaporkan path asli (/storage/emulated/0/...), sedangkan
        // pengguna biasa menulis /sdcard/... — samakan dulu.
        val norm = when {
            dir == "sdcard" || dir.startsWith("sdcard/") ->
                "storage/emulated/0/" + dir.removePrefix("sdcard").trim('/')
            dir == "mnt/sdcard" || dir.startsWith("mnt/sdcard/") ->
                "storage/emulated/0/" + dir.removePrefix("mnt/sdcard").trim('/')
            dir == "storage/self/primary" || dir.startsWith("storage/self/primary/") ->
                "storage/emulated/0/" + dir.removePrefix("storage/self/primary").trim('/')
            else -> dir
        }
        return fp.removePrefix("/").startsWith("$norm/")
    }
}
