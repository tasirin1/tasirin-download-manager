package com.tasirin.httpdownloadmanager.util

import android.annotation.SuppressLint
import android.content.ContentUris
import android.content.Context
import android.database.ContentObserver
import android.media.MediaScannerConnection
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.os.Handler
import android.os.Looper
import android.provider.MediaStore
import android.util.Base64
import androidx.documentfile.provider.DocumentFile
import java.io.File

object MediaLibrary {

    private const val SCAN_TTL_MS = 30_000L
    /** Cache lebih lama untuk hasil fallback scan filesystem (MediaStore
     *  kosong): traversal seluruh storage mahal (~2-3 dtk di TV box). Aman
     *  karena perubahan MediaStore tetap membatalkan cache via ContentObserver
     *  dan notification. */
    private const val FALLBACK_SCAN_TTL_MS = 600_000L
    /** Batas absolut entry yang di-hold di memori (galeri + remote web). */
    const val GALLERY_MAX_ENTRIES = 3000
    private const val THUMB_MAX_AGE_MS = 7L * 24 * 60 * 60 * 1000

    // TTL disimpan per-cache: cache fallback (600 dtk) yang masih valid
    // tak boleh memakai TTL 600 dtk bila MediaStore sudah pulih (30 dtk).
    private data class ScanCache(
        val ts: Long,
        val items: List<MediaEntry>,
        val total: Int,
        val ttlMs: Long,
        val usedFallback: Boolean
    )
    @Volatile
    private var scanCache: ScanCache? = null
    private var scanCacheFolderKey: List<String> = emptyList()
    private val scanLock = Any()
    @Volatile private var observerRegistered = false
    // Tahan ContentObserver seumur proses: observer lokal yang tak direferensikan
    // bisa dikumpulkan GC sehingga onChange berhenti dipanggil diam-diam dan
    // cache galeri basi sampai TTL habis (pola sama seperti NetworkCallback di App).
    @Volatile private var mediaObserver: ContentObserver? = null

    /** Hasil scan galeri: [items] = daftar lengkap hingga GALLERY_MAX_ENTRIES
     *  (cache; pemotongan per halaman dilakukan di scanCached/scan),
     *  [total] = jumlah entry unik sebenarnya — dipakai server untuk
     *  menghitung `hasMore` tanpa menahan daftar penuh. */
    class MediaScanResult(
        val items: List<MediaEntry>,
        val total: Int,
        /** true bila hasil berasal dari fallback scan filesystem (MediaStore
         *  kosong) — cache harus lebih lama karena traversal-nya mahal. */
        val usedFallback: Boolean = false
    )

    /** Koleksi MediaStore untuk root folder media (dipakai saat browsing). */
    // Escape LIKE lokal (duplikat disengaja 10 baris agar util tak bergantung ke remote;
    // ServerSecurity punya versi sendiri untuk jalur server).
    private fun escapeLike(s: String): String {
        val sb = StringBuilder(s.length)
        for (c in s) {
            if (c == '\\' || c == '%' || c == '_') sb.append('\\')
            sb.append(c)
        }
        return sb.toString()
    }

    // Samakan segmen pertama path tanpa alokasi trim/substring/lowercase.
    private fun firstSegmentEquals(path: String, vararg names: String): Boolean {
        var s = 0
        val n = path.length
        while (s < n && path[s] == '/') s++
        var e = s
        while (e < n && path[e] != '/') e++
        for (name in names) {
            if (e - s != name.length) continue
            if (path.regionMatches(s, name, 0, name.length, ignoreCase = true)) return true
        }
        return false
    }

    fun mediaCollectionForRoot(root: String): Uri {
        return when {
            firstSegmentEquals(root, "pictures", "dcim") -> MediaStore.Images.Media.EXTERNAL_CONTENT_URI
            firstSegmentEquals(root, "movies") -> MediaStore.Video.Media.EXTERNAL_CONTENT_URI
            else -> downloadsCollection()
        }
    }

    /** Koleksi MediaStore untuk menyimpan file; fallback ke Downloads bila
     *  MIME tidak cocok dengan koleksi media (mis. APK ke Pictures). */
    fun mediaCollectionFor(relativePath: String?, mime: String): Uri {
        val root = relativePath.orEmpty()
        return when {
            firstSegmentEquals(root, "pictures", "dcim") ->
                if (mime.startsWith("image/")) MediaStore.Images.Media.EXTERNAL_CONTENT_URI
                else downloadsCollection()
            firstSegmentEquals(root, "movies") ->
                if (mime.startsWith("video/")) MediaStore.Video.Media.EXTERNAL_CONTENT_URI
                else downloadsCollection()
            else -> downloadsCollection()
        }
    }

    /** Koleksi tempat sebuah URI MediaStore BENAR-BENAR hidup, dibaca
     *  dari segmen path URI-nya sendiri (issue #444) — bukan dari mime
     *  nama berkas saat ini: sesudah rename lintas-ekstensi (video
     *  .mp4 -> .bin) mime nama berjalan tak lagi mencerminkan koleksi
     *  tempat berkas diterbitkan, dan kueri keunikan ke koleksi yang
     *  salah membuat duplikat nama di folder asal lolos lagi. */
    fun mediaCollectionForUri(uri: Uri): Uri {
        val seg = uri.pathSegments
        return when {
            seg.contains("video") -> MediaStore.Video.Media.EXTERNAL_CONTENT_URI
            seg.contains("images") -> MediaStore.Images.Media.EXTERNAL_CONTENT_URI
            seg.contains("audio") -> MediaStore.Audio.Media.EXTERNAL_CONTENT_URI
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
        val durationMs: Long = 0L,
        val relativePath: String? = null,
        // Nama volume MediaStore asal entri (API 29+; "external_primary"
        // untuk penyimpanan internal). Dipakai kunci dedupe (issue
        // #532): RELATIVE_PATH relatif terhadap volume ASAL, bukan
        // selalu root primer.
        val volumeName: String? = null
    )

    /** Kunci identitas lintas sumber untuk dedupe scan (issue #515).
     *  Entri MediaStore ber-DATA null dulu berkunci contentUri sedangkan
     *  pasangan filesystem-nya berkunci path absolut — kunci berbeda,
     *  video yang sama tampil dua kali. Kini keduanya diturunkan ke
     *  path relatif terhadap [galleryRoot] bila memungkinkan: entri
     *  ber-filePath memakai path relatifnya, entri tanpa filePath
     *  memakai relativePath MediaStore + nama. Di luar itu (API < 29
     *  tanpa relativePath, atau path di luar root) jatuh ke identitas
     *  lama. Murni agar bisa di-unit-test. */
    internal fun kunciDedupeGaleri(
        filePath: String?,
        relativePath: String?,
        name: String,
        contentUri: String?,
        token: String,
        galleryRoot: String,
        volumeName: String? = null
    ): String {
        if (filePath != null) {
            val root = galleryRoot.trimEnd('/')
            return if (filePath.startsWith("$root/")) {
                "rel:" + filePath.substring(root.length + 1)
            } else {
                "abs:$filePath"
            }
        }
        // Volume non-primer (issue #532): relativePath entri ini
        // relatif terhadap volume ASALNYA (mis. SD card), bukan
        // galleryRoot primer — menyatukannya ke rel: akan menabrak
        // berkas BERBEDA senama di akar/subfolder primer. Jatuh ke
        // uri: seperti sebelum #515 (unik per baris, tanpa dedupe
        // lintas sumber yang memang tak terjangkau untuk volume itu).
        if (volumeName != null && volumeName != "external_primary") {
            return "uri:" + (contentUri ?: token)
        }
        if (relativePath != null) {
            val rel = relativePath.trim('/')
            // Kosong sesudah trim = berkas tepat di AKAR volume
            // (issue #525): kuncinya rel:<nama>, sama seperti pasangan
            // filesystem <root>/<nama> — bukan jatuh ke uri:.
            return if (rel.isEmpty()) "rel:$name" else "rel:$rel/$name"
        }
        return "uri:" + (contentUri ?: token)
    }

    private val IMAGE_EXTS = setOf("jpg", "jpeg", "png", "gif", "webp", "bmp")
    private val VIDEO_EXTS = setOf("mp4", "mkv", "webm", "avi", "mov", "3gp", "m4v", "mpg", "mpeg", "ts", "m2ts")

    // Cek akhiran tanpa alokasi substring+lowercase per file saat scan ribuan file.
    private fun hasExt(name: String, ext: String): Boolean {
        if (name.length <= ext.length + 1) return false
        if (name[name.length - ext.length - 1] != '.') return false
        return name.endsWith(ext, ignoreCase = true)
    }

    private fun hasAnyExt(name: String, exts: Set<String>): Boolean {
        for (e in exts) if (hasExt(name, e)) return true
        return false
    }

    private fun isGalleryVideo(name: String): Boolean = hasAnyExt(name, VIDEO_EXTS)

    fun mediaKind(name: String): String? {
        return when {
            hasAnyExt(name, IMAGE_EXTS) -> "image"
            hasAnyExt(name, VIDEO_EXTS) -> "video"
            else -> null
        }
    }

    // Memo token: scan ulang tiap 30 dtk meng-encode path yang sama berulang-ulang.
    private val tokenCache: MutableMap<String, String> =
        java.util.Collections.synchronizedMap(object : LinkedHashMap<String, String>(4096, 0.75f, true) {
            override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, String>): Boolean = size > 4000
        })

    private fun cachedToken(raw: String): String {
        tokenCache[raw]?.let { return it }
        val v = Base64.encodeToString(raw.toByteArray(Charsets.UTF_8), Base64.NO_WRAP or Base64.URL_SAFE)
        tokenCache[raw] = v
        return v
    }

    fun tokenForPath(path: String): String = cachedToken("f:$path")

    fun tokenForUri(uri: String): String = cachedToken("u:$uri")

    /** Token query terpanjang yang masih di-decode: token asli (base64
     *  path/URI) selalu pendek; tanpa batas, token raksasa di query langsung
     *  menjadi byte[] di RAM sebelum validasi apa pun. */
    const val MAX_TOKEN_LENGTH = 4096

    fun decodeToken(token: String): String? {
        if (token.length > MAX_TOKEN_LENGTH) return null
        return runCatching {
            String(
                Base64.decode(token, Base64.URL_SAFE or Base64.NO_WRAP),
                Charsets.UTF_8
            )
        }.getOrNull()
    }

    /** Scan dengan cache 30 detik + auto-invalidasi saat MediaStore berubah.
     *  [maxEntries] membatasi berapa entry di-hold di memori; galeri mulai dari
     *  halaman kecil lalu menaikkan limit saat pengguna scroll (load-more). */
    fun scan(
        context: Context,
        partialProgress: Map<String, Int> = emptyMap(),
        maxEntries: Int = GALLERY_MAX_ENTRIES,
        selectedFolders: List<String> = emptyList()
    ): MediaScanResult {
        val base = scanCached(context, maxEntries, selectedFolders)
        if (partialProgress.isEmpty()) return base
        val items = base.items.map { entry ->
            if (entry.isPartial) {
                val p = partialProgress[entry.name]
                if (p != null) entry.copy(progressPercent = p) else entry
            } else {
                entry
            }
        }
        // Teruskan flag fallback: scanCached memilih TTL dari flag ini dan
        // konsumen masa depan tak boleh mengira hasil fallback itu MediaStore.
        return MediaScanResult(items, base.total, base.usedFallback)
    }

    /** Isi cache scan galeri di background (dipanggil server saat start) supaya
     *  request galeri pertama tidak menunggu scan penuh — terutama saat fallback
     *  filesystem berjalan (~2-3 dtk di TV box dengan MediaStore kosong). */
    fun prewarm(context: Context) {
        runCatching {
            scan(
                context,
                maxEntries = GALLERY_MAX_ENTRIES,
                selectedFolders = StoragePrefs.getGalleryFolders(context)
            )
        }
    }

    /** Beri tahu MediaStore ada file baru/berubah + invalidasi cache scan,
     *  supaya galeri langsung mendeteksi file yang baru ditulis aplikasi
     *  (download selesai, upload, pindah/rename dari file manager). */
    fun notifyMediaChanged(context: Context, vararg paths: String) {
        synchronized(scanLock) { scanCache = null }
        val valid = paths.map { it.trim() }.filter { it.isNotEmpty() }.distinct()
        if (valid.isEmpty()) return
        runCatching {
            MediaScannerConnection.scanFile(
                context.applicationContext, valid.toTypedArray(), null, null
            )
        }
    }

    /** Kosongkan cache scan saja (issue #548): untuk terbitan
     *  MediaStore yang tidak punya path filesystem untuk dipindai
     *  MediaScannerConnection. Tanpa kait ini, invalidasi galeri
     *  sesudah unduhan selesai lewat jalur MediaStore hanya
     *  bergantung pada ContentObserver yang ter-debounce 10 detik,
     *  jadi berkas baru bisa tak terlihat sampai TTL cache berakhir. */
    fun invalidateScanCache() {
        synchronized(scanLock) { scanCache = null }
    }

    /** Kondisi cache scan terpakai: masih dalam TTL dan (hasil lama memuat
     *  cukup entry untuk limit ini ATAU scan lama tuntas). Dipisah jadi fungsi
     *  murni supaya bisa di-unit-test. */
    fun scanCacheUsable(ageMs: Long, ttlMs: Long, itemsSize: Int, total: Int, limit: Int): Boolean =
        ageMs < ttlMs && (itemsSize >= limit || itemsSize == total)

    /** MediaStore bisa menyimpan baris lama setelah file dihapus/dipindah
     *  langsung oleh filesystem. Galeri hanya percaya URI bila file fisiknya
     *  tetap ada atau item memang hanya memiliki content URI. */
    fun isMediaEntryReadable(
        filePath: String?,
        contentUri: String?,
        fileExists: (String) -> Boolean = { path -> File(path).isFile }
    ): Boolean = when {
        // DATA null (umum di Android 11+) bukan bukti file ada: wajib skema
        // content:// yang valid; keberadaan penuh divalidasi malas saat
        // serve/thumbnail (openInputStream gagal -> notFound), bukan saat scan.
        filePath.isNullOrBlank() -> contentUri?.startsWith("content://") == true
        else -> fileExists(filePath)
    }

    /** Root storage eksternal utama (sama dengan yang dipakai SettingsActivity
     *  saat normalisasi folder galeri). RELATIVE_PATH MediaStore dihitung
     *  relatif terhadap root ini. */
    fun externalRootPath(): String =
        if (Build.VERSION.SDK_INT >= 29) "/storage/emulated/0"
        else runCatching { Environment.getExternalStorageDirectory().absolutePath }
            .getOrDefault("/storage/emulated/0")

    /** Alias root penyimpanan primer Android 5-6: kolom DATA MediaStore kadang
     *  memakai symlink lama (/sdcard, /mnt/sdcard, /storage/emulated/legacy)
     *  padahal folder galeri disimpan dengan externalRoot (/storage/emulated/0).
     *  Suppress SdCardPath: daftar ini hanya dipakai untuk PENCARIAN prefix
     *  pada string path, bukan untuk I/O langsung ke /sdcard. */
    @SuppressLint("SdCardPath")
    private val PRIMARY_STORAGE_ALIASES = arrayOf(
        "/storage/emulated/0", "/storage/emulated/legacy", "/sdcard", "/mnt/sdcard"
    )

    /** Folder izin dengan bentuk turunan yang dihitung SEKALI per scan:
     *  loop per-entry (ribuan baris MediaStore) sebelumnya mengalokasi
     *  `lowercase()` untuk tiap folder di tiap baris — boros GC di HP kentang.
     *  Perbandingan case-insensitive memakai regionMatches agar tanpa alokasi. */
    internal class AllowedFolder(val raw: String, root: String) {
        val cleanRoot: String = root.trim().trimEnd('/')
        val prefix: String = "$raw/"
        val relLc: String? =
            if (raw.startsWith(cleanRoot)) raw.removePrefix(cleanRoot).trim('/').lowercase(java.util.Locale.ROOT) else null
        val isWholeRoot: Boolean = raw == cleanRoot || relLc?.isEmpty() == true
    }

    // internal (bukan private) agar uji galeri menyusun jalur produksi
    // yang sama persis, bukan wrapper khusus uji (issue #684).
    internal fun allowedFoldersFor(selectedFolders: List<String>, externalRoot: String): List<AllowedFolder> =
        selectedFolders.map { it.trim().trimEnd('/') }.filter { it.isNotEmpty() }
            .map { AllowedFolder(it, externalRoot) }

    internal fun inSelectedFoldersPrecomputed(
        dataPath: String?,
        relativePath: String?,
        allowed: List<AllowedFolder>,
        volumeName: String? = null
    ): Boolean {
        val fp = dataPath?.trim()?.trimEnd('/').orEmpty()
        if (fp.isNotEmpty()) {
            return allowed.any { pathMatchesFolder(fp, it) }
        }
        // Volume non-primer (issue #538): relativePath baris ini
        // relatif terhadap volume ASALNYA (mis. SD card), bukan root
        // primer tempat folder terpilih tinggal — cabang relatif di
        // bawah tidak boleh meloloskannya; hanya cabang filePath di
        // atas (path absolut) yang sah untuk baris seperti itu.
        if (volumeName != null && volumeName != "external_primary") return false
        val rel = relativePath?.trim('/')?.trimEnd('/').orEmpty()
        if (rel.isNotEmpty()) {
            return allowed.any { folder ->
                if (folder.isWholeRoot) return@any true
                val relFolderLc = folder.relLc ?: return@any false
                relFolderLc.isEmpty() || rel.equals(relFolderLc, ignoreCase = true) ||
                    rel.regionMatches(0, relFolderLc, 0, relFolderLc.length, ignoreCase = true) &&
                    rel.length > relFolderLc.length && rel[relFolderLc.length] == '/'
            }
        }
        return false
    }

    /** Cocokkan path absolut file dengan folder galeri. Tiga strategi:
     *  1) awalan langsung (kasus standar);
     *  2) case-insensitive — konsisten dengan SQLite LIKE NOCASE di API < 29
     *     dan filesystem FAT/SD;
     *  3) bila path memakai alias root primer (/sdcard, /mnt/sdcard, ...),
     *     bandingkan bagian relatifnya terhadap folder — menutup DATA Android
     *     5-6 yang memakai symlink lama. */
    private fun pathMatchesFolder(fp: String, folder: AllowedFolder): Boolean {
        if (fp == folder.raw || fp.startsWith(folder.prefix)) return true
        // Awalan case-insensitive tanpa alokasi lowercase (panas: per baris scan).
        if (fp.length >= folder.raw.length &&
            fp.regionMatches(0, folder.raw, 0, folder.raw.length, ignoreCase = true) &&
            (fp.length == folder.raw.length || fp[folder.raw.length] == '/')
        ) return true
        val relLc = folder.relLc ?: return false
        if (folder.isWholeRoot) return true // seluruh root dipilih
        for (alias in PRIMARY_STORAGE_ALIASES) {
            // Prefix alias wajib case-insensitive seperti kode lama (DATA Android 5-6
            // kadang "/SDCARD/..."): regionMatches tanpa alokasi "$alias/" per entry.
            if (fp.length > alias.length &&
                fp.regionMatches(0, alias, 0, alias.length, ignoreCase = true) &&
                fp[alias.length] == '/'
            ) {
                val rel = fp.substring(alias.length + 1)
                if (rel.length < relLc.length) return false
                return rel.regionMatches(0, relLc, 0, relLc.length, ignoreCase = true) &&
                    (rel.length == relLc.length || rel[relLc.length] == '/')
            }
        }
        return false
    }

    private fun scanCached(context: Context, maxEntries: Int, selectedFolders: List<String> = emptyList()): MediaScanResult {
        ensureObserver(context)
        synchronized(scanLock) {
            // Jam monotonik: TTL cache tidak goyah bila jam dinding berubah.
            val now = android.os.SystemClock.elapsedRealtime()
            val limit = maxEntries.coerceIn(1, GALLERY_MAX_ENTRIES)
            // Cache hanya valid bila folder selection sama (kosong = semua)
            scanCache?.let { cached ->
                if (scanCacheFolderKey == selectedFolders &&
                    scanCacheUsable(now - cached.ts, cached.ttlMs, cached.items.size, cached.total, limit)) {
                    // usedFallback ikut diteruskan dari cache — issue
                    // #405: sebelumnya cache-hit mengembalikannya false
                    // walau data yang sama datang dari fallback, membuat
                    // kontrak hasil tak konsisten antar panggilan.
                    return MediaScanResult(cached.items.take(limit), cached.total, cached.usedFallback)
                }
            }
            val result = scanUncached(context, selectedFolders)
            scanCache = ScanCache(
                now, result.items, result.total,
                if (result.usedFallback) FALLBACK_SCAN_TTL_MS else SCAN_TTL_MS,
                result.usedFallback
            )
            scanCacheFolderKey = selectedFolders
            return MediaScanResult(result.items.take(limit), result.total, result.usedFallback)
        }
    }

    /** Invalidasi cache saat ada foto/video/file baru atau terhapus. */
    private fun ensureObserver(context: Context) {
        if (observerRegistered) return
        synchronized(this) {
            if (observerRegistered) return
            // Flag HANYA setelah sukses: gagal di tengah (mis. registrasi
            // kedua melempar) lalu retry membuat observer ganda yang
            // menumpuk selamanya (invalidate duplikat tiap perubahan media).
            var ok = false
            runCatching {
                val appContext = context.applicationContext
                val observer = object : ContentObserver(Handler(Looper.getMainLooper())) {
                    private var lastInvalidate = 0L
                    override fun onChange(selfChange: Boolean) {
                        val now = android.os.SystemClock.elapsedRealtime()
                        if (now - lastInvalidate > 10_000L) {
                            lastInvalidate = now
                            synchronized(scanLock) { scanCache = null }
                        }
                    }
                }
                val resolver = appContext.contentResolver
                resolver.registerContentObserver(
                    MediaStore.Video.Media.EXTERNAL_CONTENT_URI, true, observer
                )
                if (Build.VERSION.SDK_INT >= 29) {
                    resolver.registerContentObserver(
                        MediaStore.Downloads.EXTERNAL_CONTENT_URI, true, observer
                    )
                }
                mediaObserver = observer
            }.onSuccess {
                ok = true
            }.onFailure {
                ok = false
            }
            observerRegistered = ok
        }
    }

    private fun scanUncached(context: Context, selectedFolders: List<String> = emptyList()): MediaScanResult {
        val list = mutableListOf<MediaEntry>()

        fun addFile(f: File, isPartial: Boolean = false) {
            if (!f.isFile) return
            val name = if (isPartial) f.name.removeSuffix(".part") else f.name
            if (!isGalleryVideo(name)) return
            list.add(
                MediaEntry(
                    name = name,
                    size = f.length(),
                    modified = f.lastModified(),
                    isVideo = true,
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
            if (!isGalleryVideo(name)) return
            val uri = df.uri.toString()
            list.add(
                MediaEntry(
                    name = name,
                    size = runCatching { df.length() }.getOrDefault(0L),
                    modified = runCatching { df.lastModified() }.getOrDefault(0L),
                    isVideo = true,
                    token = tokenForUri(uri),
                    contentUri = uri
                )
            )
        }

        // 1) Folder teks (Android 5-7 / folder kustom lewat path)
        StoragePrefs.getTextFolder(context)?.let { tf ->
            val videoFilter = java.io.FilenameFilter { _, name -> isGalleryVideo(name) }
            runCatching { File(tf).listFiles(videoFilter)?.forEach { addFile(it) } }
        }

        // 2) Folder kustom (SAF tree)
        StoragePrefs.getFolderUri(context)?.let { uri ->
            runCatching {
                val tree = DocumentFile.fromTreeUri(context, uri) ?: return@runCatching
                fun addDocTree(doc: DocumentFile, depth: Int) {
                    if (list.size >= GALLERY_MAX_ENTRIES || depth > 16) return
                    val children = runCatching { doc.listFiles() }.getOrNull() ?: return
                    for (child in children) {
                        if (list.size >= GALLERY_MAX_ENTRIES) return
                        if (child.isDirectory) addDocTree(child, depth + 1)
                        else addDoc(child)
                    }
                }
                addDocTree(tree, 0)
            }
        }

        // 3) Folder internal aplikasi (termasuk file .part yang masih berjalan)
        //    Selalu disertakan (bukan MediaStore) — partial download harus terlihat.
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

        // 4) Hanya video dari MediaStore. Penampil foto sengaja dihapus, jadi
        //    query Images tidak perlu dan hanya memboroskan RAM/CPU.
        //    Filter berdasarkan selectedFolders (relative path) bila ada.
        //    Root + folder ternormalisasi dihitung SEKALI untuk scan ini
        //    (query loop & retainAll memanggil filter per-entry; galeri bisa
        //    berisi ribuan video, jadi hindari alokasi string berulang).
        val galleryRoot = externalRootPath()
        val folderFilterActive = selectedFolders.isNotEmpty()
        val allowedFolders = if (folderFilterActive) {
            allowedFoldersFor(selectedFolders, galleryRoot)
        } else {
            emptyList()
        }
        // Penghitung baris MediaStore yang LOLOS saringan keterbacaan
        // dan benar-benar masuk daftar — memicu fallback scan
        // filesystem. Dulu baris MENTAH kursor yang dihitung (issue
        // #516): indeks berisi baris basi semua (setiap baris gugur di
        // isMediaEntryReadable) tidak pernah memicu fallback sehingga
        // galeri kosong padahal walk filesystem bisa menemukan
        // berkasnya.
        var mediaStoreAccepted = 0
        runCatching {
            val resolver = context.contentResolver
            val collection = MediaStore.Video.Media.EXTERNAL_CONTENT_URI
            val projection = buildList {
                add(MediaStore.MediaColumns._ID)
                add(MediaStore.MediaColumns.DISPLAY_NAME)
                add(MediaStore.MediaColumns.SIZE)
                add(MediaStore.MediaColumns.DATE_MODIFIED)
                add(MediaStore.MediaColumns.DATA)
                if (Build.VERSION.SDK_INT >= 29) add(MediaStore.MediaColumns.RELATIVE_PATH)
                if (Build.VERSION.SDK_INT >= 29) add(MediaStore.MediaColumns.VOLUME_NAME)
                add(MediaStore.Video.Media.DURATION)
            }.toTypedArray()
            // Bangun selection SQL untuk filter folder. API 29+ memakai
            // RELATIVE_PATH (kolom diindeks; DATA deprecated & sering null di
            // Android 11+ — LIKE di kolom null tidak menjaring apa pun dan
            // membuat video di luar folder terpilih ikut tampil). API < 29
            // TIDAK memakai pre-filter SQL: kolom DATA di Android 5-6 kadang
            // memakai alias root (/sdcard, /mnt/sdcard) yang tidak cocok dengan
            // LIKE path absolut, jadi semua baris video diambil dan filter
            // final diserahkan ke pathMatchesFolder (alias + case-insensitive).
            val selection: String?
            val selectionArgs: Array<String>?
            if (selectedFolders.isNotEmpty()) {
                val relCol: String? =
                    if (Build.VERSION.SDK_INT >= 29) MediaStore.MediaColumns.RELATIVE_PATH else null
                if (relCol != null) {
                    val clauses = mutableListOf<String>()
                    val args = mutableListOf<String>()
                    val root = galleryRoot
                    for (folder in selectedFolders) {
                        val rel = folder.trimEnd('/').removePrefix(root).trim('/')
                        if (rel.isEmpty()) continue // seluruh root dipilih → tanpa batasan
                        // Nama folder asli bisa mengandung "%"/"_" (mis. "100%"):
                        // escape agar LIKE tidak melebar ke folder lain.
                        clauses += "($relCol LIKE ? ESCAPE '\\' OR $relCol = ?)"
                        args += escapeLike(rel) + "/%"
                        args += "$rel/"
                    }
                    if (clauses.isEmpty()) {
                        selection = null
                        selectionArgs = null
                    } else {
                        selection = clauses.joinToString(" OR ")
                        selectionArgs = args.toTypedArray()
                    }
                } else {
                    selection = null
                    selectionArgs = null
                }
            } else {
                selection = null
                selectionArgs = null
            }
            resolver.query(
                collection, projection, selection, selectionArgs,
                "${MediaStore.MediaColumns.DATE_MODIFIED} DESC"
            )?.use { c ->
                val iId = c.getColumnIndexOrThrow(MediaStore.MediaColumns._ID)
                val iName = c.getColumnIndexOrThrow(MediaStore.MediaColumns.DISPLAY_NAME)
                val iSize = c.getColumnIndexOrThrow(MediaStore.MediaColumns.SIZE)
                val iMod = c.getColumnIndexOrThrow(MediaStore.MediaColumns.DATE_MODIFIED)
                val iData = c.getColumnIndexOrThrow(MediaStore.MediaColumns.DATA)
                val iDur = c.getColumnIndex(MediaStore.Video.Media.DURATION)
                val iRel = if (Build.VERSION.SDK_INT >= 29) {
                    c.getColumnIndexOrThrow(MediaStore.MediaColumns.RELATIVE_PATH)
                } else {
                    -1
                }
                val iVol = if (Build.VERSION.SDK_INT >= 29) {
                    c.getColumnIndex(MediaStore.MediaColumns.VOLUME_NAME)
                } else {
                    -1
                }
                while (c.moveToNext()) {
                    val name = c.getString(iName) ?: continue
                    val filePath = c.getString(iData)?.takeIf { it.isNotBlank() }
                    // Nilai mentah dipertahankan (issue #525): string
                    // kosong berarti berkas di akar volume dan harus
                    // sampai ke kunci dedupe sebagai akar, bukan null.
                    // Konsumen lain (saringan folder, mediaCollectionFor)
                    // memperlakukan "" dan null setara.
                    val relativePath = if (iRel >= 0) {
                        c.getString(iRel)
                    } else {
                        null
                    }
                    val uri = ContentUris.withAppendedId(collection, c.getLong(iId)).toString()
                    val volumeName = if (iVol >= 0) c.getString(iVol) else null
                    if (!isMediaEntryReadable(filePath, uri)) continue
                    if (folderFilterActive && !inSelectedFoldersPrecomputed(filePath, relativePath, allowedFolders, volumeName)) continue
                    mediaStoreAccepted++
                    list.add(
                        MediaEntry(
                            name = name,
                            size = c.getLong(iSize),
                            modified = c.getLong(iMod) * 1000L,
                            isVideo = true,
                            token = tokenForUri(uri),
                            filePath = filePath,
                            contentUri = uri,
                            durationMs = if (iDur >= 0) c.getLong(iDur) else 0L,
                            relativePath = relativePath,
                            volumeName = volumeName
                        )
                    )
                }
            }
        }

        // 4b) MediaStore kosong/rusak (umum di Android 5-6 setelah file dicolok
        //     via USB/PC — indeks tidak pernah ter-scan): fallback scan
        //     filesystem seluruh storage primer supaya mode "scan semua" tetap
        //     menemukan video tanpa bergantung pada baris MediaStore.
        if (!folderFilterActive && mediaStoreAccepted == 0) {
            runCatching {
                val root = File(galleryRoot)
                if (root.isDirectory) {
                    val seenDirs = mutableSetOf<String>()
                    root.walkTopDown().maxDepth(24).onEnter { dir ->
                        val canonical = runCatching { dir.canonicalPath }.getOrNull() ?: return@onEnter false
                        // Direktori kanonis yang sudah dikunjungi = siklus
                        // symlink: pangkas subtree agar tidak fan-out eksponensial.
                        if (!seenDirs.add(canonical)) return@onEnter false
                        true
                    }
                        .filter { it.isFile && isGalleryVideo(it.name) }
                        // Anggaran sisa global, bukan 3000 penuh per
                        // sumber (issue #413): gabungan walk bisa
                        // mematerialkan 3000 x jumlah folder sebelum
                        // dipangkas di akhir.
                        .take((GALLERY_MAX_ENTRIES - list.size).coerceAtLeast(0))
                        .forEach { addFile(it) }
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

        // 6) Folder galeri terpilih dipindai LANGSUNG dari filesystem
        //    (rekursif). MediaStore kadang tidak mengindeks file yang dicolok
        //    via USB/PC di Android 5-6, atau melaporkan kolom DATA dengan root
        //    berbeda dari folder yang diketik user — scan langsung menjamin
        //    video di folder pilihan tetap tampil apa pun isi MediaStore.
        //    Dedupe di bawah memakai filePath, jadi baris MediaStore yang sama
        //    tidak akan dobel (durasi dari MediaStore tetap menang karena
        //    section 4 lebih dulu). Dibatasi satu plafon GALLERY_MAX_ENTRIES
        //    bersama untuk seluruh tambahan blok ini (issue #413: sebelumnya
        //    take penuh PER folder, jadi N folder terpilih bisa mematerialkan
        //    3000 x N entri sebelum pangkas akhir) — tetapi dihitung dari
        //    tambahan blok ini SENDIRI, bukan sisa global (issue #446):
        //    sisa global bisa nol tepat saat baris MediaStore >= plafon,
        //    sehingga berkas tak-terindeks tak pernah masuk daftar dan
        //    janji blok ini gugur pada perpustakaan besar.
        if (folderFilterActive) {
            var addedByScan = 0
            for (folder in allowedFolders) {
                val dir = File(folder.raw)
                if (!dir.isDirectory) continue
                runCatching {
                    val seenDirs = mutableSetOf<String>()
                    dir.walkTopDown().maxDepth(24).onEnter { d ->
                        val canonical = runCatching { d.canonicalPath }.getOrNull() ?: return@onEnter false
                        if (!seenDirs.add(canonical)) return@onEnter false
                        true
                    }
                        .filter { it.isFile && isGalleryVideo(it.name) }
                        .take((GALLERY_MAX_ENTRIES - addedByScan).coerceAtLeast(0))
                        .forEach { addFile(it); addedByScan++ }
                }
            }
        }

        // Hapus duplikat: file yang sama bisa muncul sebagai path (f:) dan
        // sebagai MediaStore (u:) — dedupe berdasar path file bila ada.
        // Filter folder (sebelum dedupe): item dengan path absolut dicocokkan
        // langsung; item SAF/MediaStore TANPA path (kolom DATA null pada
        // Android 11+) hanya lolos bila folder asalnya cocok via RELATIVE_PATH.
        // Item tanpa keduanya tidak ditampilkan saat filter aktif (bukan
        // dilewatkan seperti bug lama).
        if (folderFilterActive) {
            list.retainAll { entry ->
                inSelectedFoldersPrecomputed(entry.filePath, entry.relativePath, allowedFolders, entry.volumeName)
            }
        }
        // Simpan sampai GALLERY_MAX_ENTRIES (bukan hanya maxEntries halaman):
        // fallback scan filesystem mahal, jadi cache harus bisa melayani
        // load-more berikutnya tanpa scan ulang. Pemakaian aktual tetap
        // dipotong per halaman di scanCached/scan.
        // Total = distinct penuh SEBELUM take: total terpotong membuat
        // hasMore selalu false dan load-more berhenti di 3000 walau masih ada sisa.
        val allDistinct = list
            .distinctBy {
                kunciDedupeGaleri(
                    it.filePath, it.relativePath, it.name,
                    it.contentUri, it.token, galleryRoot,
                    volumeName = it.volumeName
                )
            }
            .sortedByDescending { it.modified }
        val deduped = allDistinct.take(GALLERY_MAX_ENTRIES)
        val usedFallback = !folderFilterActive && mediaStoreAccepted == 0
        return MediaScanResult(deduped, allDistinct.size, usedFallback)
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
