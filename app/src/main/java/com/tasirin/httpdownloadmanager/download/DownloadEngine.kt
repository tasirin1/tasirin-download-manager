package com.tasirin.httpdownloadmanager.download

import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Looper
import android.os.SystemClock
import android.util.Base64
import androidx.core.content.edit
import androidx.core.net.toUri
import com.tasirin.httpdownloadmanager.App
import com.tasirin.httpdownloadmanager.data.DownloadItem
import com.tasirin.httpdownloadmanager.data.DownloadRepository
import com.tasirin.httpdownloadmanager.data.DownloadSegment
import com.tasirin.httpdownloadmanager.data.DownloadState
import com.tasirin.httpdownloadmanager.util.FileNames
import com.tasirin.httpdownloadmanager.util.FileSaver
import com.tasirin.httpdownloadmanager.util.Formats
import com.tasirin.httpdownloadmanager.util.Checksums
import com.tasirin.httpdownloadmanager.util.Hex
import com.tasirin.httpdownloadmanager.util.MimeTypes
import com.tasirin.httpdownloadmanager.util.StorageCleanup
import com.tasirin.httpdownloadmanager.util.NotificationHelper
import com.tasirin.httpdownloadmanager.util.Crypto
import com.tasirin.httpdownloadmanager.util.StoragePrefs
import com.tasirin.httpdownloadmanager.util.TlsCompat
import com.tasirin.httpdownloadmanager.util.SocialMediaExtractor
import com.tasirin.httpdownloadmanager.util.readBounded
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.isActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlin.random.Random
import kotlin.coroutines.coroutineContext
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.IOException
import java.io.OutputStream
import java.io.RandomAccessFile
import java.net.HttpURLConnection
import javax.net.ssl.HttpsURLConnection
import java.net.URL
import java.net.URLDecoder
import java.security.MessageDigest
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.UUID
import java.util.Collections
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong

import java.net.HttpCookie
import java.net.CookieManager
import java.net.CookiePolicy
import org.json.JSONArray
import org.json.JSONObject
class DownloadEngine(appContext: Context) {
    // Engine hidup seumur proses (disimpan statis di App.engine): simpan
    // Application context saja, jangan pernah Activity (anti-leak).
    @SuppressLint("StaticFieldLeak")
    private val context: Context = appContext.applicationContext
        .also { SocialMediaExtractor.init(it) }
    private val fileSaver by lazy { FileSaver(context) }

    // Cookie manager in-memory: store per-host cookies so subsequent requests
    // can carry server-set session cookies (helps sites that require cookies,
    // but does not bypass Cloudflare JS challenges).
    private val cookieManager = CookieManager(null, CookiePolicy.ACCEPT_ALL).also {
        // Jangan menimpa CookieHandler global bila sudah dipegang pihak lain
        // (mis. WebView/test); klaim hanya bila belum ada atau milik sendiri.
        val current = runCatching { java.net.CookieHandler.getDefault() }.getOrNull()
        if (current == null || current === it) {
            runCatching { java.net.CookieHandler.setDefault(it) }
        }
        // loadPersistedCookies() TIDAK lagi di sini: baca + parse cookie
        // persisten ikut muatan latar di init (#242), bukan main thread.
    }

    /** Buka koneksi; untuk https tambahkan trust anchor CA lama (Android 6-7). */
    private fun openConn(url: String): HttpURLConnection {
        val conn = URL(url).openConnection() as HttpURLConnection
        if (conn is HttpsURLConnection) {
            TlsCompat.apply(conn, context)
        }
        return conn
    }

    private val repository = DownloadRepository(context)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val jobs = ConcurrentHashMap<String, Job>()

    // Penerus monitor yang menunggu hasil verifikasi checksum (#215):
    // id penerus -> penggantian terparkir. Dipensiunkan commitReplacement,
    // dipulihkan rollbackReplacement.
    private data class PendingReplacement(
        val predecessor: DownloadItem,
        /** Berkas byte pendahulu asli (nama .replaced). */
        val backup: File,
        /** Byte percobaan finalisasi sebelumnya yang belum terverifikasi
         *  (#249): ada hanya bila .replaced asli sudah ada saat publishItem
         *  berjalan lagi — dibuang di commit maupun rollback. */
        val stale: File? = null
    )

    private val pendingReplacements =
        ConcurrentHashMap<String, PendingReplacement>()
    private val retryAttempts = ConcurrentHashMap<String, Int>()
    // Set concurrent via newSetFromMap (bukan ConcurrentHashMap.newKeySet):
    // newKeySet/KeySetView butuh API 24 — di Android 6 (minSdk 23) lempar
    // NoSuchMethodError saat engine dibangun (crash tiap startup); Set polos
    // aman di semua API. Berlaku untuk pendingRetries & failedHlsUrls.
    private val pendingRetries: MutableSet<String> =
        Collections.newSetFromMap(ConcurrentHashMap<String, Boolean>())
    /** URL HLS yang sudah gagal (media playlist 403/404) — jangan retry; re-extract dari YouTube URL. */
    private val failedHlsUrls: MutableSet<String> =
        Collections.newSetFromMap(ConcurrentHashMap<String, Boolean>())
    private val FAILED_HLS_URLS_MAX = 256

    /** Negative-cache sesi: aman dihapus total bila penuh (hanya memicu
     *  satu percobaan ulang HLS, bukan korupsi). */
    private fun rememberFailedHlsUrl(url: String) {
        if (failedHlsUrls.size >= FAILED_HLS_URLS_MAX) failedHlsUrls.clear()
        failedHlsUrls.add(url)
    }
    /** URL sosial media original per item (YouTube/TikTok/etc) — disimpan saat ekstraksi
     *  menghasilkan URL HLS yang berbeda; dipakai untuk re-extract saat HLS gagal. */
    private val originalSocialUrls = ConcurrentHashMap<String, String>()
    private val activeConns = ConcurrentHashMap<String, MutableSet<HttpURLConnection>>()
    private val speedTracker = SpeedTracker()
    private var saveJob: Job? = null
    private var progressSaveJob: Job? = null
    private var lastProgressSaveAt = 0L
    // URL yang pernah gagal di sesi ini: cadangan yang sama tidak dicoba
    // berulang-ulang (hemat waktu saat ISP/proxy menolak beberapa host).
    // LinkedHashMap(accessOrder=true) = LRU otomatis — akses terakhir dipindah
    // ke akhir, sehingga eviction selalu membuang entry paling tua.
    // Dipakai dari multiple coroutines (handleFailure + runDownload).
    private val failedUrlLock = Any()
    private val failedUrls = LinkedHashMap<String, Boolean>(0, 0.75f, true)
    private val FAILED_URLS_MAX = 256

    private fun rememberFailedUrl(url: String) {
        synchronized(failedUrlLock) {
            failedUrls[url] = true
            // Evict LRU: bila melebihi batas, buang setengah yang paling tua
            if (failedUrls.size > FAILED_URLS_MAX) {
                val iter = failedUrls.keys.iterator()
                var drop = failedUrls.size / 2
                while (iter.hasNext() && drop > 0) { iter.next(); iter.remove(); drop-- }
            }
        }
    }

    private fun isFailedUrl(url: String): Boolean = synchronized(failedUrlLock) {
        failedUrls.containsKey(url)
    }

    // Cached maxConcurrent — hindari recompute dari SharedPreferences tiap cek.
    // Ditulis jalur pengaturan dan dibaca thread IO: wajib terlihat lintas
    // thread, jadi @Volatile (issue #369).
    @Volatile private var cachedMaxConcurrent: Int = StoragePrefs.maxConcurrent(context)

    fun refreshMaxConcurrent() {
        cachedMaxConcurrent = StoragePrefs.maxConcurrent(context)
        // Batas yang NAIK wajib langsung menggerakkan antrean (issue
        // #503): tanpa pemicu ini item PENDING tak pernah dinilai ulang
        // terhadap batas baru dan slot tambahan menganggur sampai ada
        // unduhan aktif yang selesai sendiri. startQueued aman dan
        // idempoten — ia menilai ulang slot dan melewati item yang
        // sedang backoff retry.
        startQueued()
    }

    // Cached timeout values — hindari recompute dari SharedPreferences tiap koneksi
    @Volatile private var connectTimeoutMs: Int = StoragePrefs.getConnectTimeoutSec(context) * 1000
    @Volatile private var readTimeoutMs: Int = StoragePrefs.getReadTimeoutSec(context) * 1000

    fun refreshTimeouts() {
        connectTimeoutMs = StoragePrefs.getConnectTimeoutSec(context) * 1000
        readTimeoutMs = StoragePrefs.getReadTimeoutSec(context) * 1000
    }

    // Cached User-Agent — baca SharedPreferences tiap koneksi (segmen x retry)
    // hanya membuang lock + lookup; pola sama seperti timeout di atas.
    @Volatile private var cachedUserAgent: String = StoragePrefs.getUserAgent(context)

    fun refreshUserAgent() {
        cachedUserAgent = StoragePrefs.getUserAgent(context)
    }

    @Volatile
    private var interruptedResumed = false

    /** Gerbang muatan awal (#242): selesai sesudah antrean + cookie
     *  persisten terpasang dari latar. Simpan/resume/cleanup menunggu
     *  gerbang ini agar daftar yang belum utuh tidak menimpa data
     *  tersimpan atau membersihkan partial milik item yang belum termuat. */
    private val loadGate = CompletableDeferred<Unit>()
    // Antrean aksi yang ditunda sampai gerbang muat terbuka (#318):
    // dulu tiap pemanggil pre-gate melepas launch await sendiri —
    // resumeAll yang dipanggil berkali-kali selama start melepas
    // banyak coroutine yang bangun bersamaan dan urutannya tak
    // dijamin. Kini satu job penunggu menjalankan aksi terdaftar
    // berurutan; kunci yang sama terdaftar dua kali digabung.
    private val deferredGateLock = Any()
    private val deferredGateQueue = ArrayDeque<Pair<String, () -> Unit>>()
    private var deferredGateJobActive = false

    private fun deferUntilLoaded(key: String, action: () -> Unit) {
        val startJob = synchronized(deferredGateLock) {
            if (deferredGateQueue.any { it.first == key }) {
                false
            } else {
                deferredGateQueue.addLast(key to action)
                if (deferredGateJobActive) {
                    false
                } else {
                    deferredGateJobActive = true
                    true
                }
            }
        }
        if (!startJob) return
        scope.launch {
            loadGate.await()
            while (true) {
                val next = synchronized(deferredGateLock) {
                    if (deferredGateQueue.isEmpty()) {
                        deferredGateJobActive = false
                        null
                    } else {
                        deferredGateQueue.removeFirst()
                    }
                } ?: break
                // Satu aksi yang melempar tak boleh membunuh drain
                // (issue #390): coroutine yang mati di sini meninggalkan
                // deferredGateJobActive=true selamanya sehingga semua
                // aksi tunda berikutnya hilang diam-diam. Tangkap, catat,
                // dan lanjutkan ke aksi berikutnya.
                runCatching { next.second() }
                    .onFailure { App.logEvent("Deferred action failed (${next.first}): ${it.message?.take(60)}") }
            }
        }
    }
    // True bila ada mutasi yang meminta simpan selama gerbang muat
    // masih tertutup (scheduleSave/flushSave teredam diam-diam):
    // flush penutup init wajib menghormatinya, kalau tidak mutasi
    // pre-gate seperti remove() hilang tak tersimpan (#304).
    @Volatile
    private var saveDeferredPreGate = false

    /** Tunggu sampai muatan awal antrean terpasang (gerbang #242). */
    suspend fun awaitInitialLoad() = loadGate.await()
    /** True sesudah muatan awal terpasang. Sebelum itu daftar belum
     *  boleh dinilai: masih bisa kosong palsu selama muatan latar
     *  berjalan (#319). */
    fun isInitialLoadComplete(): Boolean = loadGate.isCompleted

    private val _items = MutableStateFlow<List<DownloadItem>>(emptyList())
    val items: StateFlow<List<DownloadItem>> = _items.asStateFlow()

    // Progres segmen per item (indeks segmen -> byte): segmen menulis di sini
    // tiap detik TANPA emisi StateFlow; flush berkala menggabungkannya menjadi
    // SATU updateItem per item (sebelumnya 1 salinan daftar + 1 emisi per
    // segmen per detik) — hemat CPU/GC saat banyak segmen paralel.
    private val segProgress = ConcurrentHashMap<String, java.util.concurrent.atomic.AtomicLongArray>()
    private val segFlushJobs = ConcurrentHashMap<String, Job>()
    /** Sampel kecepatan/ETA terakhir per item (throttle 1 dtk saat flush 500ms). */
    // Satu holder (waktu, sampel) agar baca/tulis/replace atomik: dua map terpisah
    // bisa sobek (waktu baru + sampel lama) saat flush dan clear balapan.
    private val lastSpeedSample = ConcurrentHashMap<String, Pair<Long, Pair<Long, Long>>>()
    // Total byte bersama untuk speed limiter multi-segmen; menghindari pencarian
    // item + penjumlahan segmen di setiap chunk saat batas kecepatan aktif.
    private val throttleTotals = ConcurrentHashMap<String, AtomicLong>()

    init {
        // Muat antrean + cookie persisten di LATAR (#242): dulu
        // repository.load() (parse JSON seluruh antrean + dekripsi
        // Keystore per item) dan loadPersistedCookies() berjalan sinkron
        // di main thread dari App.onCreate — jank start sebanding ukuran
        // antrean di perangkat lemah. _items mulai kosong dan terisi
        // sekali di sini; loadGate menahan simpan/resume/cleanup sampai
        // muatan pertama terpasang.
        scope.launch {
            try {
                val loaded = runCatching { repository.load() }
                    .getOrDefault(emptyList())
                runCatching { loadPersistedCookies() }
                // Item yang ditambahkan selama jendela muat tidak hilang:
                // gabungkan yang belum ada di hasil muatan.
                // Penggabungan wajib atomik terhadap mutasi lain (#263):
                // snapshot extras + penetapan daftar berjalan di dalam
                // monitor yang sama dengan update()/updateItem
                // (@Synchronized), supaya penambahan dari UI/remote yang
                // masuk di antara keduanya tidak tertimpa salinan gabungan
                // lalu hilang dari daftar dan penyimpanan sekaligus.
                val extras = synchronized(this) {
                    val ex = _items.value.filter { cur ->
                        loaded.none { it.id == cur.id }
                    }
                    _items.value = (loaded + ex)
                        .sortedByDescending { it.addedAt }
                    ex
                }
                // Rekonsiliasi cadangan .replaced yatim (issue #237):
                // proses yang mati di jendela verifikasi checksum
                // meninggalkan penggantian terparkir (pendingReplacements
                // hanya hidup di memori). Wajib SEBELUM loadGate dibuka
                // (#262): penunggu gerbang (resume/cleanup) tidak boleh
                // berjalan bersamaan dengan penukaran berkas di sini.
                // Bungkus seperti tetangga init lain (issue #368):
                // eksepsi rekonsiliasi tidak boleh keluar dari launch
                // init dan melewatkan startBackgroundLoops().
                val reconciled = runCatching { reconcileOrphanedReplacements() }
                    .onFailure {
                        App.logEvent("REPLACE RECONCILE: failed at startup: ${it.message}")
                    }
                    .getOrDefault(false)
                loadGate.complete(Unit)
                // Migrasi penyimpanan lemah (issue #209): kredensial
                // plaintext warisan/fallback ditulis ulang terenkripsi
                // SEKARANG saat start, bukan menunggu persist penuh
                // berikutnya yang entah kapan.
                // Kedua flush penutup init dibungkus runCatching
                // (issue #349): eksepsi persist di sini tidak boleh
                // keluar dari launch init, karena startBackgroundLoops()
                // sesudah blok ini adalah satu-satunya penjalan
                // monitorLoop -- kegagalan simpan sesaat tidak boleh
                // mematikan monitor untuk seluruh sesi proses.
                if (repository.hasWeakStoredCredentials()) {
                    App.logEvent(
                        "STORAGE: weak credential storage found, re-encrypting"
                    )
                    runCatching { flushSave() }
                        .onFailure { App.logEvent("STORAGE: re-encrypt flush failed: ${it.message}") }
                } else if (extras.isNotEmpty() || reconciled || saveDeferredPreGate) {
                    // Simpan yang teredam gerbang selama jendela muat
                    // (termasuk hasil rekonsiliasi: flushSave internalnya
                    // ikut teredam selama gerbang masih tertutup).
                    runCatching { flushSave() }
                        .onFailure { App.logEvent("STORAGE: deferred flush failed: ${it.message}") }
                }
            } finally {
                loadGate.complete(Unit)
            }
            startBackgroundLoops()
        }
    }

    private fun startBackgroundLoops() {
        scope.launch { monitorLoop() }
    }

    private val cancelledConns = ConcurrentHashMap<String, Long>()
    private fun disconnectActive(id: String) {
        cancelledConns[id] = System.currentTimeMillis()
        // Map tak terbatas bila item dihapus tanpa pernah start lagi; eviksi
        // entri tertua (bukan acak) agar cancel yang masih aktif tak hidup lagi.
        if (cancelledConns.size > 512) {
            runCatching {
                val oldest = cancelledConns.entries.minByOrNull { it.value }?.key
                if (oldest != null) cancelledConns.remove(oldest)
            }
        }
        activeConns.remove(id)?.forEach { connection ->
            runCatching { connection.disconnect() }
        }
    }

    /** Lacak koneksi yang BARU selesai dibuka. Job yang dibatalkan selama
     *  connect/redirect (operasi soket blocking tidak langsung patuh
     *  pembatalan coroutine) wajib membuang koneksinya di sini: tanpa cek
     *  ini, koneksi yatim job lama bisa terdaftar ke job baru sesudah
     *  pause-resume cepat menghapus bendera cancelledConns di launchItem,
     *  lalu ikut menulis file parsial segmen yang sama (issue #200).
     *  Cek ensureActive memakai identitas job coroutine itu sendiri,
     *  jadi tidak bergantung pada bendera sama sekali. */
    private suspend fun trackOpenedConnection(
        id: String,
        connection: HttpURLConnection
    ): HttpURLConnection {
        try {
            coroutineContext.ensureActive()
        } catch (e: CancellationException) {
            runCatching { connection.disconnect() }
            throw e
        }
        return trackConnection(id, connection)
    }

    private fun trackConnection(id: String, connection: HttpURLConnection): HttpURLConnection {
        if (cancelledConns.containsKey(id)) {
            runCatching { connection.disconnect() }
            return connection
        }
        // getOrPut Kotlin tidak atomik di ConcurrentHashMap: dua segmen yang
        // mulai bersamaan bisa membuat dua set dan satu set hilang (koneksi
        // bocor saat pause/cancel). Kunci eksplisit (aman minSdk 23).
        synchronized(activeConns) {
            activeConns.getOrPut(id) { Collections.newSetFromMap(ConcurrentHashMap()) }.add(connection)
        }
        if (cancelledConns.containsKey(id)) {
            untrackConnection(id, connection)
        }
        return connection
    }

    private fun untrackConnection(id: String, connection: HttpURLConnection) {
        activeConns[id]?.remove(connection)
        activeConns.remove(id, emptySet())
        // Bendera batal dibuang saat koneksi terakhir id ini lepas, TANPA
        // memindai seluruh daftar item seperti sebelumnya (issue #373):
        // jalur panas per-segmen tidak boleh membayar O(jumlah item).
        // Aman karena perlindungan koneksi yatim job yang dibatalkan
        // tidak lagi bergantung pada bendera ini — trackOpenedConnection
        // menilai identitas job coroutine lewat ensureActive (#200), dan
        // launchItem selalu membersihkan bendera sebelum job baru mulai.
        val wasCancelled = cancelledConns.containsKey(id)
        if (activeConns[id] == null) cancelledConns.remove(id)
        // Sukses jangan disconnect: biarkan socket keep-alive dipakai ulang.
        // Cancel/pause tetap diputus via disconnectActive().
        if (wasCancelled) runCatching { connection.disconnect() }
    }

    @Synchronized
    fun addDownload(
        url: String,
        fileName: String?,
        username: String = "",
        password: String = "",
        headers: String = "",
        method: String = "GET",
        postBody: String = "",
        speedLimitKbps: Int = 0,
        priority: Int = 0,
        checksum: String = "",
        destination: String = "",
        folderPath: String = "",
        mirrors: List<String> = emptyList(),
        monitor: Boolean = false,
        replacesItemId: String = "",
        preferredHeight: Int = 0,
        preferredAudioLang: String = ""
    ): String {
        val cleanUrl = url.trim()
        // Hanya http(s) yang didukung engine; skema lain (file/ftp/…) hanya
        // gagal obscure di openConnection — tolak sejak antre (defense in depth
        // bersama validasi endpoint remote).
        if (cleanUrl.isEmpty() ||
            (!cleanUrl.startsWith("http://", ignoreCase = true) &&
                !cleanUrl.startsWith("https://", ignoreCase = true))
        ) {
            return ""
        }
        val customName = fileName?.trim().orEmpty()
        // Nama + item dibuat dan disisipkan di DALAM monitor
        // (updateTransformed): membaca _items.value di luar kunci lalu
        // update() berbasis salinan itu membuat dua addDownload yang
        // berjalan bersamaan saling menimpa — satu unduhan hilang
        // dari daftar (pola #274).
        var addedItem: DownloadItem? = null
        updateTransformed { cur ->
            // Nama dipakai sebagai kunci file partial (.part): dua item bernama
            // sama (mis. fallback timestamp sedetik yang sama) akan menulis file
            // yang sama dan saling merusak. Unikkan saat antre, dinilai
            // terhadap daftar terkini di dalam kunci.
            val name = FileNames.unique(FileNames.safe(customName.ifEmpty { guessFileName(cleanUrl) })) { n ->
                // Penerus monitor (#199) mengambil alih nama pendahulunya —
                // pendahulu dikecualikan dari tabrakan agar nama tidak
                // bersufiks " (1)".
                cur.any { it.fileName == n && it.id != replacesItemId }
            }
            val newItem = DownloadItem(
                id = UUID.randomUUID().toString(),
                url = cleanUrl,
                fileName = name,
                state = DownloadState.PENDING,
                bytesDownloaded = 0,
                totalBytes = 0,
                nameIsCustom = customName.isNotEmpty(),
                autoResume = true,
                username = username,
                password = password,
                headers = headers,
                method = method,
                postBody = postBody,
                destination = destination,
                folderPath = folderPath,
                speedLimitKbps = speedLimitKbps,
                priority = priority,
                checksum = checksum,
                mirrors = mirrors,
                monitor = monitor,
                replacesItemId = replacesItemId,
                preferredHeight = preferredHeight,
                preferredAudioLang = preferredAudioLang
            )
            addedItem = newItem
            ArrayList<DownloadItem>(cur.size + 1).apply { add(newItem); addAll(cur) }
        }
        val item = addedItem ?: return ""
        flushSave()
        val host = runCatching { URL(cleanUrl).host }.getOrDefault("")
        App.logEvent("DOWNLOAD ADDED: ${item.fileName} (${host.ifEmpty { "custom URL" }})")
        attemptStart(item.id)
        return item.id
    }

    fun pause(id: String): Boolean {
        val target = _items.value.find { it.id == id } ?: return false
        // Hanya antrean aktif yang bisa dijeda: tanpa guard, item COMPLETED/
        // FAILED yang ter-pause (mis. via /api/action) kehilangan statusnya.
        if (target.state != DownloadState.DOWNLOADING && target.state != DownloadState.PENDING) return false
        App.logEvent("DOWNLOAD PAUSED: ${target.fileName}")
        retryAttempts.remove(id)
        pendingRetries.remove(id)
        speedTracker.reset(id)
        jobs.remove(id)?.cancel()
        disconnectActive(id)
        updateItem(id) {
            it.copy(state = DownloadState.PAUSED, autoResume = false, speedBps = 0, etaSeconds = 0)
        }
        clearSegProgress(id)
        scheduleSave()
        return true
    }

    fun resume(id: String): Boolean {
        val item = _items.value.find { it.id == id } ?: return false
        // Zombie DOWNLOADING (proses mati saat aktif, tanpa job berjalan):
        // antrekan ulang supaya tidak macet selamanya dan tombol resume
        // bisa dipakai lagi. DOWNLOADING dengan job aktif sudah berjalan —
        // anggap sukses idempoten.
        if (item.state == DownloadState.DOWNLOADING) {
            if (jobs[item.id]?.isActive == true) return true
        } else if (item.state != DownloadState.PAUSED && item.state != DownloadState.FAILED &&
            item.state != DownloadState.CANCELLED
        ) return false
        App.logEvent("DOWNLOAD RESUMED: ${item.fileName}")
        retryAttempts.remove(id)
        pendingRetries.remove(id)
        clearSegProgress(id)
        updateItem(id) { it.copy(state = DownloadState.PENDING, autoResume = true) }
        attemptStart(id)
        return true
    }

    fun resumeInterrupted() {
        if (interruptedResumed) return
        interruptedResumed = true
        // Antrean belum termuat (muatan latar #242): tunda sampai gerbang
        // terbuka agar resume tidak menilai daftar kosong lalu selesai.
        if (!loadGate.isCompleted) {
            scope.launch { loadGate.await(); doResumeInterrupted() }
            return
        }
        doResumeInterrupted()
    }

    private fun doResumeInterrupted() {
        // DOWNLOADING ikut dipulihkan: state ini terpersist saat proses mati
        // mendadak sehingga tanpa ini item zombie macet selamanya (tidak
        // auto-lanjut dan tombol resume menolaknya).
        // Batch: satu salinan list + satu emisi StateFlow, bukan N kali
        // updateItem (pola sama seperti resumeAutoPaused/pauseAll).
        val ids = _items.value.filter {
            it.autoResume && (it.state == DownloadState.PAUSED || it.state == DownloadState.PENDING ||
                it.state == DownloadState.DOWNLOADING)
        }.map { it.id }
        if (ids.isNotEmpty()) {
            val idSet = ids.toSet()
            updateTransformed { daftar ->
                daftar.map { item ->
                    // Cek ulang status SEGAR di dalam kunci (pola #220):
                    // item yang berubah sejak filter snapshot (mis. baru
                    // selesai/di-pause pengguna) tidak dipaksa PENDING.
                    val stillTarget = item.id in idSet && item.autoResume &&
                        (item.state == DownloadState.PAUSED ||
                            item.state == DownloadState.PENDING ||
                            item.state == DownloadState.DOWNLOADING)
                    if (stillTarget) {
                        item.copy(state = DownloadState.PENDING, autoResume = true)
                    } else {
                        item
                    }
                }
            }
        }
        startQueued()
    }

    fun cancel(id: String): Boolean {
        val target = _items.value.find { it.id == id } ?: return false
        // COMPLETED tidak bisa di-cancel: status selesai bukan sesuatu yang
        // bisa dibatalkan (hapus memakai remove/delete).
        if (target.state == DownloadState.COMPLETED) return false
        // Cancel langsung menghapus progres + item dari daftar (tidak lagi
        // menyisakan status CANCELLED): tombol Cancel terasa instan walau
        // coroutine segmen masih menyelesaikan pembatalan di background.
        // State CANCELLED dipertahankan di enum untuk item lama yang tersimpan.
        App.logEvent("DOWNLOAD CANCELLED: ${target.fileName}")
        retryAttempts.remove(id)
        pendingRetries.remove(id)
        originalSocialUrls.remove(id)
        speedTracker.reset(id)
        clearSegProgress(id)
        jobs.remove(id)?.cancel()
        disconnectActive(id)
        updateTransformed { daftar -> daftar.filterNot { it.id == id } }
        if (activeConns[id] == null) cancelledConns.remove(id)
        // Opsi "Delete partial file when cancelled": tanpa ini toggle
        // Pengaturan tidak berpengaruh (partial menumpuk sampai cleanup).
        if (StoragePrefs.isDeletePartialOnCancel(context)) {
            runCatching { fileSaver.deleteFiles(target) }
        }
        scheduleSave()
        return true
    }

    fun remove(id: String): Boolean {
        val item = _items.value.find { it.id == id } ?: return false
        App.logEvent("DOWNLOAD DELETED: ${item.fileName}")
        retryAttempts.remove(id)
        pendingRetries.remove(id)
        originalSocialUrls.remove(id)
        speedTracker.reset(id)
        clearSegProgress(id)
        jobs.remove(id)?.cancel()
        disconnectActive(id)
        updateTransformed { daftar -> daftar.filterNot { it.id == id } }
        if (activeConns[id] == null) cancelledConns.remove(id)
        fileSaver.deleteFiles(item)
        scheduleSave()
        // Hapus eksplisit item terakhir: niat antrean kosong jelas — tulis
        // blob kosong agar sisa blob korup (degraded) tak menetap selamanya.
        // Wajib menunggu loadGate (#261): sebelum muatan awal terpasang,
        // daftar yang kosong belum tentu antrean memang kosong — clearAll
        // di jendela itu menimpa blob antrean tersimpan yang belum dibaca.
        if (_items.value.isEmpty() && loadGate.isCompleted) repository.clearAll()
        return true
    }

    fun clearCompleted() {
        // Hanya membersihkan daftar; file hasil download TIDAK dihapus.
        // State per-ID ikut dibersihkan seperti clearFailed agar tak ada
        // sisa retry/progres/url-sosial dari item yang sudah dibuang.
        val removed = _items.value.filter { it.state == DownloadState.COMPLETED }
        removed.forEach {
            retryAttempts.remove(it.id)
            pendingRetries.remove(it.id)
            originalSocialUrls.remove(it.id)
            speedTracker.reset(it.id)
            clearSegProgress(it.id)
            jobs.remove(it.id)?.cancel()
            disconnectActive(it.id)
            // Seperti clearFailed: flag koneksi dipertahankan bila masih ada
            // koneksi aktif (segmen yang datang terlambat tetap diputus).
            if (activeConns[it.id] == null) cancelledConns.remove(it.id)
        }
        updateTransformed { daftar -> daftar.filterNot { it.state == DownloadState.COMPLETED } }
        scheduleSave()
        if (_items.value.isEmpty() && loadGate.isCompleted) repository.clearAll()
    }

    fun clearFailed() {
        // Hanya membersihkan daftar item gagal; file parsial ikut dibersihkan.
        val failed = _items.value.filter { it.state == DownloadState.FAILED }
        failed.forEach { item ->
            retryAttempts.remove(item.id)
            pendingRetries.remove(item.id)
            originalSocialUrls.remove(item.id)
            speedTracker.reset(item.id)
            clearSegProgress(item.id)
            jobs.remove(item.id)?.cancel()
            disconnectActive(item.id)
            if (activeConns[item.id] == null) cancelledConns.remove(item.id)
            App.logEvent("DOWNLOAD DELETED: ${item.fileName}")
        }
        // Batch delete: satu loop, bukan N kali filter list
        failed.forEach { fileSaver.deleteFiles(it) }
        updateTransformed { daftar -> daftar.filterNot { it.state == DownloadState.FAILED } }
        scheduleSave()
        if (_items.value.isEmpty() && loadGate.isCompleted) repository.clearAll()
    }

    fun importStream(
        fileName: String,
        destination: String = "",
        folderPath: String = "",
        length: Long,
        writer: (OutputStream) -> Unit
    ): FileSaver.PublishResult {
        val name = sanitizeFileName(fileName)
        val saver = fileSaver
        val published = saver.saveStream(name, destination, folderPath, writer)
        val finalName = published.fileName ?: name
        // Ukuran riil hasil tulis, bukan angka deklarasi: upload chunked
        // (length 0) atau Content-Length yang meleset membuat daftar tampil
        // 0 byte / salah. Stat file dulu, fallback ke angka deklarasi.
        val actualSize = published.filePath?.let { path ->
            runCatching { File(path).length() }.getOrDefault(-1L)
        }?.takeIf { it >= 0 }
            ?: published.contentUri?.let { uri ->
                runCatching {
                    context.contentResolver.openAssetFileDescriptor(uri.toUri(), "r")
                        ?.use { it.length } ?: -1L
                }.getOrDefault(-1L)
            }?.takeIf { it >= 0 }
        val size = actualSize ?: length.coerceAtLeast(0L)
        val item = DownloadItem(
            id = UUID.randomUUID().toString(),
            url = "upload://$finalName",
            fileName = finalName,
            state = DownloadState.COMPLETED,
            bytesDownloaded = size,
            totalBytes = size,
            contentUri = published.contentUri,
            filePath = published.filePath,
            nameIsCustom = true,
            autoResume = false,
            finishedAt = System.currentTimeMillis()
        )
        updateTransformed { cur -> ArrayList<DownloadItem>(cur.size + 1).apply { add(item); addAll(cur) } }
        flushSave()
        return published
    }

    fun probeHlsVariants(
        url: String,
        username: String = "",
        password: String = "",
        headers: String = ""
    ): List<HlsVariant>? {
        return runCatching {
            // Kredensial/header dialog IKUT dibawa (issue #473): master
            // m3u8 berproteksi Cookie/Referer/Authorization menjawab 403
            // untuk probe buta konteks, sehingga pemilih kualitas gugur
            // diam-diam padahal unduhannya sendiri sehat karena
            // addDownload membawa konteks yang sama.
            val conn = openAuthenticatedConnection(
                url, method = "GET", username = username, password = password, headers = headers
            )
            try {
                val code = conn.responseCode
                if (code !in 200..299) return null
                // Baca terbatas: master playlist normal kecil; body raksasa dari
                // URL nakal tidak boleh dimuat penuh ke memori.
                val body = conn.inputStream.use { readBounded(it, HLS_PROBE_MAX_BYTES) }
                // Media playlist (single-quality): HLS valid tanpa pilihan
                // kualitas — daftar kosong, bukan null (null = bukan HLS).
                if (HlsParser.isMediaPlaylist(body)) return emptyList()
                // Fallback longgar seperti parseHlsPlan (issue #465):
                // master berformat STREAM-INF tak umum (kasus YouTube
                // 2026) yang gagal di parseMaster ketat tetap harus
                // menawarkan pemilih kualitas di dialog, bukan langsung
                // antre dengan kualitas bawaan.
                HlsParser.parseMaster(body, conn.url.toString())
                    ?: HlsParser.parseMasterLenient(body, conn.url.toString())
            } finally {
                conn.disconnect()
            }
        }.getOrNull()
    }

    /** Probe manifest HLS untuk daftar bahasa audio yang tersedia.
     *  Mengembalikan list rendisi unik (language + name), dipakai dialog
     *  Add Download untuk spinner "Audio language". */
    fun probeHlsAudioLanguages(url: String, headers: String = ""): List<HlsRendition> {
        return runCatching {
            val conn = openAuthenticatedConnection(
                url, method = "GET", username = "", password = "", headers = headers
            )
            try {
                val code = conn.responseCode
                if (code !in 200..299) return emptyList()
                val body = conn.inputStream.use { readBounded(it, HLS_PROBE_MAX_BYTES) }
                val renditions = HlsParser.parseAudioRenditions(body, conn.url.toString())
                renditions.distinctBy { it.language.lowercase(Locale.ROOT) to it.name.lowercase(Locale.ROOT) }
            } finally {
                conn.disconnect()
            }
        }.getOrElse { emptyList() }
    }

    fun probeUrl(
        url: String,
        username: String = "",
        password: String = "",
        headers: String = ""
    ): UrlProbe? {
        val clean = url.trim()
        if (clean.isEmpty()) return null
        if (!clean.startsWith("http://", ignoreCase = true) && !clean.startsWith("https://", ignoreCase = true)) return null
        return runCatching {
            val conn = openAuthenticatedConnection(
                clean, method = "HEAD",
                username = username, password = password, headers = headers
            )
            var headCode = 0
            var headProbe: UrlProbe? = null
            try {
                headCode = conn.responseCode
                if (headCode in 200..299) {
                    headProbe = UrlProbe(
                        fileName = contentDispositionName(conn.getHeaderField("Content-Disposition")),
                        sizeBytes = contentLength(conn),
                        contentType = conn.getHeaderField("Content-Type"),
                        etag = conn.getHeaderField("ETag")
                    )
                }
            } finally {
                conn.disconnect()
            }
            if (headProbe != null) return headProbe
            if (headCode in 200..299) return null
            val fallback = openAuthenticatedConnection(
                clean, method = "GET", username = username, password = password, headers = headers
            ) { c, _, _ -> c.setRequestProperty("Range", "bytes=0-0") }
            try {
                val fcode = fallback.responseCode
                if (fcode != 200 && fcode != 206) return null
                val totalFromRange = if (fcode == 206) {
                    // 206 untuk Range bytes=0-0 membawa total di Content-Range (bytes 0-0/<total>),
                    // bukan di Content-Length (=1). Tanpa ini probe lapor 1 byte lalu monitor
                    // mengira versi baru dan mengunduh ulang tiap 30 menit.
                    fallback.getHeaderField("Content-Range")?.substringAfter('/')?.trim()?.toLongOrNull()?.takeIf { it > 0 }
                } else null
                UrlProbe(
                    fileName = contentDispositionName(fallback.getHeaderField("Content-Disposition")),
                    sizeBytes = totalFromRange ?: contentLength(fallback).takeIf { it > 0 } ?: -1L,
                    contentType = fallback.getHeaderField("Content-Type"),
                    etag = fallback.getHeaderField("ETag")
                )
            } finally {
                fallback.disconnect()
            }
        }.getOrNull()
    }

    /** Buka koneksi dengan redirect manual. Kredensial dan header sensitif
     *  hanya dikirim bila target tetap satu origin dengan [authAnchorUrl]
     *  (bawaan: URL awal koneksi ini). Pemanggil HLS meneruskan URL item
     *  sebagai jangkar agar kredensial tidak ikut ke host segmen/varian
     *  mana pun yang ditunjuk isi playlist (issue #340). */
    private fun openAuthenticatedConnection(
        url: String,
        method: String,
        username: String,
        password: String,
        headers: String,
        presetRange: Long? = null,
        authAnchorUrl: String = url,
        configure: (HttpURLConnection, String, String) -> Unit = { _, _, _ -> }
    ): HttpURLConnection {
        var current = url
        // Metode efektif per hop (issue #504): redirect 301/302/303 atas
        // POST berubah menjadi GET tanpa body di hop berikutnya
        // (semantik RFC 9110 dan perilaku browser); hanya 307/308 yang
        // mempertahankan metode+body. configure menerima metode efektif
        // hop ini agar penulis body bisa melewati hop yang sudah GET.
        var effectiveMethod = method
        repeat(MAX_REDIRECTS) {
            val conn = openConn(current)
            conn.instanceFollowRedirects = false
            conn.requestMethod = effectiveMethod
            conn.connectTimeout = if (effectiveMethod == "HEAD") 8_000 else connectTimeoutMs
            conn.readTimeout = if (effectiveMethod == "HEAD") 8_000 else readTimeoutMs
            val ua = cachedUserAgent.ifEmpty { DEFAULT_USER_AGENT }
            conn.setRequestProperty("User-Agent", ua)
            conn.setRequestProperty("Accept", "*/*")
            conn.setRequestProperty("Accept-Language", "en-US,en;q=0.9,id;q=0.8")
            try {
                val origin = java.net.URL(current).let { "${it.protocol}://${it.host}" }
                conn.setRequestProperty("Referer", "$origin/")
            } catch (_: Exception) { /* Referer opsional, tidak wajib */ } // audit-ignore: file_scoped_catch_all, kotlin_empty_catch (Referer opsional: URL tak ter-parse tidak boleh menggagalkan koneksi)
            // YouTube/Instagram CDN butuh Referer + Origin agar HTTP 403 tidak muncul
            if (current.contains("googlevideo.com") || current.contains("youtube.com")) {
                conn.setRequestProperty("Referer", "https://www.youtube.com/")
                conn.setRequestProperty("Origin", "https://www.youtube.com")
            } else if (current.contains("cdninstagram.com") || current.contains("scontent")) {
                conn.setRequestProperty("Referer", "https://www.instagram.com/")
                conn.setRequestProperty("Origin", "https://www.instagram.com")
            } else if (current.contains("phncdn.com") || current.contains("pornhub.com")) {
                conn.setRequestProperty("Referer", "https://www.pornhub.com/")
                conn.setRequestProperty("Origin", "https://www.pornhub.com")
            }
            if (current == authAnchorUrl || isSameOrigin(authAnchorUrl, current)) {
                applyAuthHeaders(conn, username, password, headers)
            }
            // Header Range resume wajib di-set SEBELUM connect/responseCode:
            // setRequestProperty setelah konek melempar IllegalStateException
            // di HttpURLConnection (Android maupun JDK).
            if (presetRange != null && presetRange > 0) {
                conn.setRequestProperty("Range", "bytes=$presetRange-")
            }
            configure(conn, current, effectiveMethod)
            val code = conn.responseCode
            // Hanya redirect asli yang diikuti: 304 (Not Modified) serta
            // 305/306 (proxy/unused) bukan redirect dan tidak punya Location.
            if (code != 301 && code != 302 && code != 303 && code != 307 && code != 308) return conn

            val location = conn.getHeaderField("Location")
            conn.disconnect()
            val next = redirectTarget(current, location)
                ?: throw IOException("Invalid redirect")
            if ((code == 301 || code == 302 || code == 303) && effectiveMethod == "POST") {
                effectiveMethod = "GET"
            }
            current = next
        }
        throw IOException("Too many redirects")
    }

    fun pauseAll() {
        // Aksi massal wajib menunggu muatan awal (#264): menilai daftar
        // kosong di jendela muat membuat aksi pengguna batal diam-diam.
        if (!loadGate.isCompleted) {
            deferUntilLoaded("pauseAll") { doPauseAll() }
            return
        }
        doPauseAll()
    }

    private fun doPauseAll() {
        val targets = _items.value.filter {
            it.state == DownloadState.DOWNLOADING || it.state == DownloadState.PENDING
        }
        if (targets.isEmpty()) return

        // Batch: satu salinan list + satu emisi StateFlow + satu penyimpanan,
        // bukan N kali update/save saat pengguna menekan Pause All.
        targets.forEach { item ->
            App.logEvent("DOWNLOAD PAUSED: ${item.fileName}")
            retryAttempts.remove(item.id)
            pendingRetries.remove(item.id)
            speedTracker.reset(item.id)
            jobs.remove(item.id)?.cancel()
            disconnectActive(item.id)
            clearSegProgress(item.id)
        }
        val ids = targets.map { it.id }.toSet()
        updateTransformed { daftar ->
            daftar.map { item ->
                // Cek ulang status SEGAR (pola #220): item yang selesai
                // atau sudah ter-pause di antara filter dan transform
                // tidak dipaksa dari snapshot basi.
                val stillTarget = item.id in ids &&
                    (item.state == DownloadState.DOWNLOADING ||
                        item.state == DownloadState.PENDING)
                if (stillTarget) {
                    item.copy(
                        state = DownloadState.PAUSED,
                        autoResume = false,
                        speedBps = 0,
                        etaSeconds = 0
                    )
                } else {
                    item
                }
            }
        }
        scheduleSave()
    }

    fun resumeAll() {
        // Aksi massal wajib menunggu muatan awal (#264): menilai daftar
        // kosong di jendela muat membuat aksi pengguna batal diam-diam.
        if (!loadGate.isCompleted) {
            deferUntilLoaded("resumeAll") { doResumeAll() }
            return
        }
        doResumeAll()
    }

    private fun doResumeAll() {
        // Batch: satu filter + satu update, bukan N kali find() di resume().
        // DOWNLOADING zombie (tanpa job aktif) ikut diantrekan ulang —
        // selaras resume(id); DOWNLOADING ber-job aktif memang sedang
        // berjalan dan tidak disentuh (issue #203).
        val targets = _items.value.filter {
            it.state == DownloadState.PAUSED || it.state == DownloadState.FAILED ||
                it.state == DownloadState.CANCELLED ||
                (it.state == DownloadState.DOWNLOADING && jobs[it.id]?.isActive != true)
        }
        if (targets.isEmpty()) return
        val ids = targets.map { it.id }.toSet()
        targets.forEach { item ->
            retryAttempts.remove(item.id)
            pendingRetries.remove(item.id)
            clearSegProgress(item.id)
        }
        updateTransformed { daftar ->
            daftar.map { item ->
                // Periksa ulang status di daftar SEGAR (#220): item yang berubah
                // di antara filter dan update (selesai lewat resume per-item atau
                // baru di-pause) tidak boleh dipaksa kembali ke PENDING.
                val stillTarget = item.id in ids &&
                    (item.state == DownloadState.PAUSED ||
                        item.state == DownloadState.FAILED ||
                        item.state == DownloadState.CANCELLED ||
                        (item.state == DownloadState.DOWNLOADING &&
                            jobs[item.id]?.isActive != true))
                if (stillTarget) item.copy(state = DownloadState.PENDING, autoResume = true) else item
            }
        }
        startQueued()
    }

    /** Pindahkan item ke atas/atas-antrean (daftar tampil terbaru di atas).
     *  Pertukaran posisi hanya pada item yang tidak aktif download, supaya
     *  tidak mengganggu segmen yang sedang berjalan. */
    fun moveUp(id: String) {
        // Pertukaran dinilai + diterapkan di dalam monitor (#274):
        // indeks dari snapshot luar kunci bisa basi saat daftar berubah
        // bersamaan (item terhapus/bertambah) -> tukar salah sasaran
        // atau IndexOutOfBounds.
        var movedName: String? = null
        updateTransformed { cur ->
            val idx = cur.indexOfFirst { it.id == id }
            if (idx <= 0) {
                cur
            } else {
                movedName = cur[idx].fileName
                val items = cur.toMutableList()
                items[idx] = items[idx - 1]
                items[idx - 1] = cur[idx]
                items
            }
        }
        val name = movedName ?: return
        scheduleSave()
        App.logEvent("DOWNLOAD MOVED UP: $name")
    }

    /** Pindahkan item ke bawah antrean. */
    fun moveDown(id: String) {
        var movedName: String? = null
        updateTransformed { cur ->
            val idx = cur.indexOfFirst { it.id == id }
            if (idx < 0 || idx >= cur.size - 1) {
                cur
            } else {
                movedName = cur[idx].fileName
                val items = cur.toMutableList()
                items[idx] = items[idx + 1]
                items[idx + 1] = cur[idx]
                items
            }
        }
        val name = movedName ?: return
        scheduleSave()
        App.logEvent("DOWNLOAD MOVED DOWN: $name")
    }

    fun retryFailed() {
        // Batch: satu salinan daftar + satu emisi StateFlow, bukan N kali
        // updateItem saat pengguna menekan Retry Failed (sama seperti pauseAll).
        val ids = _items.value.filter { it.state == DownloadState.FAILED }.map { it.id }
        if (ids.isEmpty()) return
        val idSet = ids.toSet()
        ids.forEach { id ->
            retryAttempts.remove(id)
            pendingRetries.remove(id)
        }
        updateTransformed { daftar ->
            daftar.map { item ->
                // Cek ulang status SEGAR (pola #220): item FAILED dari
                // snapshot yang sudah di-resume manual lalu selesai
                // tidak boleh dipaksa PENDING lagi (unduh ulang ganda).
                val stillTarget = item.id in idSet &&
                    item.state == DownloadState.FAILED
                if (stillTarget) {
                    item.copy(state = DownloadState.PENDING, autoResume = true, error = null)
                } else {
                    item
                }
            }
        }
        startQueued()
    }

    fun resumeAutoPaused() {
        // Callback jaringan wajib menunggu muatan awal (#264):
        // onAvailable tidak menembak ulang sampai jaringan berubah
        // lagi, jadi no-op di jendela muat membuat unduhan yang
        // ter-auto-pause macet tanpa pemicu berikutnya.
        if (!loadGate.isCompleted) {
            deferUntilLoaded("resumeAutoPaused") { doResumeAutoPaused() }
            return
        }
        doResumeAutoPaused()
    }

    private fun doResumeAutoPaused() {
        if (!StoragePrefs.isBackgroundEnabled(context)) return
        val ids = _items.value.filter {
            it.autoResume && it.state == DownloadState.PAUSED
        }.map { it.id }
        if (ids.isEmpty()) return
        // Batch: satu salinan list + satu emisi StateFlow, bukan N kali
        // updateItem saat NetworkCallback.onAvailable menembak berulang
        // (sama seperti pauseAll/resumeAll/retryFailed).
        val idSet = ids.toSet()
        ids.forEach { id ->
            retryAttempts.remove(id)
            pendingRetries.remove(id)
            clearSegProgress(id)
        }
        updateTransformed { daftar ->
            daftar.map { item ->
                // Cek ulang status SEGAR (pola #220): pemicu otomatis
                // NetworkCallback tidak boleh menghidupkan lagi item
                // yang sudah selesai/diambil alih sejak snapshot.
                val stillTarget = item.id in idSet && item.autoResume &&
                    item.state == DownloadState.PAUSED
                if (stillTarget) {
                    item.copy(state = DownloadState.PENDING, autoResume = true, error = null)
                } else {
                    item
                }
            }
        }
        startQueued()
    }

    fun cleanupOrphans() {
        // Antrean belum termuat (muatan latar #242): tunda — menilai
        // partial sebagai yatim dari daftar kosong akan menghapus berkas
        // milik item yang belum sempat termuat.
        if (!loadGate.isCompleted) {
            deferUntilLoaded("cleanupOrphans") { cleanupOrphans() }
            return
        }
        // Load terdegradasi (blob antrean corrupt): daftar kosong bukan
        // berarti tak ada partial aktif — lewati agar file user tak terhapus.
        if (shouldSkipOrphanCleanup(_items.value.size, repository.hadStoredItems())) {
            App.logEvent("ORPHAN CLEANUP SKIPPED: stored queue unreadable, keeping partial files")
            return
        }
        fileSaver.cleanupOrphanPartials(_items.value)
    }

    fun freeSpaceBytes(): Long = fileSaver.destinationFreeBytes()

    /** Sisa ruang dengan mempertimbangkan volume tujuan aktual (SD card /
     *  folder custom `f:` bisa beda volume dengan penyimpanan internal). */
    fun freeSpaceBytesFor(folderPath: String): Long = fileSaver.destinationFreeBytes(folderPath)

    @Synchronized
    fun rename(id: String, newName: String): Boolean {
        val item = _items.value.find { it.id == id } ?: return false
        if (item.state != DownloadState.COMPLETED) return false
        // Unikkan terhadap item lain agar file hasil rename tidak menimpa
        // milik item lain (renameTo Linux menggantikan target diam-diam).
        val clean = FileNames.unique(FileNames.safe(newName.trim())) { n ->
            n != item.fileName && _items.value.any { it.fileName == n }
        }
        if (clean.isBlank() || clean == item.fileName) return false
        val renamed = fileSaver.rename(item, clean)
        if (renamed != null) {
            // FileSaver.rename mengembalikan URI untuk item MediaStore/SAF dan
            // path untuk file: salah kolom membuat serveFile gagal (File("content://…")
            // tak ada -> notFound tanpa mencoba contentUri). Nama yang dicatat
            // adalah nama AKTUAL hasil rename (bisa diunikkkan lagi di dalam
            // FileSaver terhadap berkas asing), bukan nama yang diminta
            // (issue #458).
            val actualName = renamed.fileName ?: clean
            updateItem(id) {
                if (!it.contentUri.isNullOrEmpty()) {
                    it.copy(fileName = actualName, contentUri = renamed.contentUri ?: it.contentUri)
                } else {
                    it.copy(fileName = actualName, filePath = renamed.filePath ?: it.filePath)
                }
            }
            return true
        }
        return false
    }

    fun setLimitAndPriority(id: String, speedLimitKbps: Int, priority: Int) {
        updateItem(id) { it.copy(speedLimitKbps = speedLimitKbps, priority = priority) }
    }

    fun setMonitor(id: String, enabled: Boolean) {
        val item = _items.value.find { it.id == id } ?: return
        updateItem(id) { it.copy(monitor = enabled && item.state == DownloadState.COMPLETED) }
        scheduleSave()
    }

    private fun shouldInvalidateResume(item: DownloadItem, currentEtag: String?): Boolean {
        return item.bytesDownloaded > 0 && item.etag.isNotBlank() &&
            !currentEtag.isNullOrBlank() && currentEtag != item.etag
    }

    /** Hitung offset resume + selaraskan file parsial dengan catatan bytes.
     *  Idempoten: aman dipanggil dua kali untuk item yang sama (panggilan
     *  kedua selalu KEEP). Dipanggil SEBELUM membuka koneksi agar header
     *  Range bisa di-preset (lihat presetRange). */
    private fun prepareResumeOffset(item: DownloadItem, saver: FileSaver): Long {
        var downloaded = item.bytesDownloaded
        val partialFile = saver.partialFile(item.fileName)
        when (resumeAction(downloaded, partialFile.length())) {
            ResumeAction.TRUNCATE_TO_RECORD -> {
                val truncated = runCatching {
                    RandomAccessFile(partialFile, "rw").use { it.setLength(downloaded) }
                    true
                }.getOrDefault(false)
                if (!truncated) {
                    downloaded = 0
                    partialFile.delete()
                }
            }
            ResumeAction.RESTART -> {
                downloaded = 0
                partialFile.delete()
            }
            ResumeAction.KEEP -> Unit
        }
        return downloaded
    }

    private suspend fun invalidateChangedResume(item: DownloadItem, etag: String?) {
        if (!shouldInvalidateResume(item, etag)) return
        val saver = fileSaver
        saver.partialFiles(item).forEach { runCatching { it.delete() } }
        updateItem(item.id) {
            it.copy(
                bytesDownloaded = 0,
                totalBytes = 0,
                segments = emptyList(),
                speedBps = 0,
                etaSeconds = 0,
                etag = ""
            )
        }
        clearSegProgress(item.id)
    }

    private suspend fun monitorLoop() {
        while (true) {
            delay(MONITOR_INTERVAL_MS)
            runCatching { checkForUpdates() }
        }
    }

    private suspend fun checkForUpdates() {
        val targets = _items.value.filter {
            it.monitor && it.state == DownloadState.COMPLETED &&
                // Skema tak peka huruf (issue #475): URL kapital yang
                // ditempel persis dari dialog tetap terpantau, selaras
                // pemeriksaan skema probeUrl yang ignoreCase.
                (it.url.startsWith("http://", ignoreCase = true) ||
                    it.url.startsWith("https://", ignoreCase = true)) &&
                // Item HLS dikecualikan (issue #341): item.url adalah URL
                // manifest sedangkan totalBytes adalah total byte segmen --
                // dua besaran tak sebanding, dan etag HLS selalu kosong,
                // sehingga probe HEAD ke manifest bisa selalu terbaca
                // "ukuran berubah" dan memicu unduh ulang penuh berulang.
                !isHlsManifestUrl(it.url)
        }
        for (item in targets) {
            runCatching {
                // Jangan tumpuk bila versi baru sudah sedang diunduh/diantre.
                val alreadyActive = _items.value.any {
                    it.id != item.id && it.fileName == item.fileName &&
                        (it.state == DownloadState.PENDING || it.state == DownloadState.DOWNLOADING)
                }
                if (alreadyActive) return@runCatching
                val probe = probeUrl(item.url, item.username, item.password, item.headers) ?: return@runCatching
                val sizeChanged = item.totalBytes > 0 && probe.sizeBytes > 0 &&
                    probe.sizeBytes != item.totalBytes
                val etagChanged = item.etag.isNotBlank() && !probe.etag.isNullOrBlank() &&
                    probe.etag != item.etag
                if (sizeChanged || etagChanged) {
                    App.logEvent(
                        "MONITOR: new version detected for ${item.fileName} " +
                            "(${Formats.bytes(probe.sizeBytes)})"
                    )
                    // Monitoring diteruskan ke item baru; item lama dimatikan
                    // agar tick berikutnya tidak mengantre duplikat penuh lagi.
                    val newId = addDownload(
                        url = item.url,
                        fileName = item.fileName,
                        username = item.username,
                        password = item.password,
                        headers = item.headers,
                        method = item.method,
                        postBody = item.postBody,
                        speedLimitKbps = item.speedLimitKbps,
                        priority = item.priority,
                        // Checksum TIDAK diwarisi (issue #512): checksum
                        // pendahulu adalah sidik jari isi versi LAMA,
                        // sedangkan pemicu penerus justru perubahan isi —
                        // mewarisinya membuat verifikasi penerus mustahil
                        // lolos. Kosong di sini agar captureHeaderChecksum
                        // menangkap checksum segar versi baru dari header
                        // responsnya; server tanpa checksum = penerus
                        // terbit tanpa verifikasi, seperti unduhan biasa.
                        checksum = "",
                        destination = item.destination,
                        folderPath = item.folderPath,
                        monitor = true,
                        replacesItemId = item.id
                    )
                    if (newId.isNotEmpty()) {
                        updateItem(item.id) { it.copy(monitor = false) }
                        scheduleSave()
                    }
                }
            }
        }
    }

    @Volatile private var sharedLimiter: GlobalRateLimiter? = null
    private val limiterLock = Any()

    private fun globalRateLimiter(): GlobalRateLimiter? {
        val limit = StoragePrefs.speedLimitKbps(context)
        if (limit <= 0) {
            // Batalkan throttle global bila pengguna mematikan limit
            // supaya download baru tidak terkena throttle sisa sesi lama.
            synchronized(limiterLock) { sharedLimiter = null }
            return null
        }
        synchronized(limiterLock) {
            val current = sharedLimiter
            // Samakan nilai bila prefs berubah tanpa lewat refreshSpeedLimit
            // (mis. ganti 512->2048 yang sebelumnya diam-diam tetap 512).
            if (current != null) {
                current.setLimit(limit)
                return current
            }
            val created = GlobalRateLimiter(limit)
            sharedLimiter = created
            return created
        }
    }

    /** Samakan limiter global dengan prefs setelah spinner limit diubah:
     *  instance dipegang bersama (termasuk download yang sedang jalan),
     *  jadi setLimit langsung berlaku tanpa restart. Pola sama seperti
     *  refreshMaxConcurrent/refreshTimeouts/refreshUserAgent. */
    fun refreshSpeedLimit() {
        val limit = StoragePrefs.speedLimitKbps(context)
        synchronized(limiterLock) {
            if (limit <= 0) sharedLimiter = null
            else sharedLimiter?.setLimit(limit) ?: run { sharedLimiter = GlobalRateLimiter(limit) }
        }
    }

    fun move(id: String, destTreeUri: Uri) {
        val item = _items.value.find { it.id == id } ?: return
        if (item.state != DownloadState.COMPLETED) return
        val result = fileSaver.move(item, destTreeUri)
        if (result != null) {
            updateItem(id) {
                it.copy(
                    contentUri = result.contentUri,
                    filePath = result.filePath,
                    fileName = result.fileName ?: item.fileName
                )
            }
            // Invalidasi cache galeri supaya file yang dipindah langsung terdeteksi.
            com.tasirin.httpdownloadmanager.App.httpServer.invalidateFsRootsCache()
        }
    }

    private fun attemptStart(id: String) {
        val item = _items.value.find { it.id == id } ?: return
        if (item.state != DownloadState.PENDING) return
        if (canStartNow()) {
            ensureServiceRunning()
            StorageCleanup.runIfLow(context, _items.value)
            launchItem(item)
        }
    }

    private fun canStartNow(): Boolean {
        if (jobs.size < cachedMaxConcurrent) return true
        return jobs.values.count { it.isActive } < cachedMaxConcurrent
    }

    private fun startQueued() {
        val max = cachedMaxConcurrent
        val smallFirst = StoragePrefs.isSmallFirstEnabled(context)
        val pending = _items.value
            .filter { it.state == DownloadState.PENDING }
            .sortedWith(downloadQueueOrder(smallFirst))
        var active = jobs.values.count { it.isActive }
        if (active < max && pending.isNotEmpty()) {
            ensureServiceRunning()
        }
        StorageCleanup.runIfLow(context, _items.value)
        for (item in pending) {
            if (active >= max) break
            if (pendingRetries.contains(item.id)) continue
            if (launchItem(item)) active++
        }
    }

    private fun launchItem(item: DownloadItem): Boolean {
        cancelledConns.remove(item.id)
        synchronized(jobs) {
            if (jobs[item.id]?.isActive == true) return false
            if (jobs.size >= cachedMaxConcurrent && jobs.values.count { it.isActive } >= cachedMaxConcurrent) {
                return false
            }
            val job = scope.launch {
                try {
                    updateItem(item.id) { it.copy(state = DownloadState.DOWNLOADING) }
                    App.logEvent("DOWNLOAD STARTED: ${item.fileName}")
                    runDownload(item)
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Throwable) {
                    // Error runtime (mis. NoSuchMethodError) diubah jadi status FAILED,
                    // bukan force close.
                    handleFailure(item.id, e.message)
                }
            }
            jobs[item.id] = job
            job.invokeOnCompletion {
                // Hapus hanya bila masih job ini: pause/cancel menghapus job
                // lama dari map lalu resume bisa meluncurkan job pengganti
                // sebelum handler ini jalan; remove() buta akan menendang job
                // baru yang hidup (tak terhentikan + tak terhitung konkuren).
                jobs.remove(item.id, job)
                startQueued()
            }
            return true
        }
    }

    private fun publishItem(
        saver: FileSaver,
        partial: File,
        fileName: String,
        item: DownloadItem
    ): FileSaver.PublishResult {
        val cleanName = FileNames.safe(fileName)
        // Penerus monitor (#199, diperketat #215): berkas terbit pendahulu
        // TIDAK dihapus di muka — versi lama adalah satu-satunya salinan
        // bagus sampai penerus terbukti terbit DAN lolos checksum. Bila
        // path-nya berkas biasa, berkas itu digeser ke nama cadangan
        // (nama tujuan bebas, byte utuh); publish yang melempar di sini
        // memulihkan cadangan sendiri. Pendahulu murni URI MediaStore
        // (tanpa path) tidak bisa digeser, jadi tetap dihapus di muka.
        val predecessor = item.replacesItemId.takeIf { it.isNotEmpty() }
            ?.let { pid -> _items.value.find { it.id == pid } }
        var backup: File? = null
        var stale: File? = null
        if (predecessor != null) {
            val predFile = predecessor.filePath
                ?.takeIf { it.isNotEmpty() }?.let { File(it) }
            if (predFile != null && predFile.isFile) {
                val aside = File(predFile.parentFile, predFile.name + ".replaced")
                if (aside.isFile) {
                    // Cadangan dari finalisasi terputus masih ada: byte ASLI
                    // pendahulu ada di dalamnya dan TIDAK boleh dihapus
                    // sebelum ada yang terverifikasi (#249 — dulu aside lama
                    // dihapus di sini sehingga rollback memulihkan byte
                    // percobaan, bukan versi bagus asli). Aside lama tetap
                    // cadangan resmi; berkas yang kini menempati path (byte
                    // percobaan) digeser ke .stale untuk dibuang nanti.
                    val staleFile = File(
                        predFile.parentFile, predFile.name + ".replaced.stale"
                    )
                    runCatching { if (staleFile.isFile) staleFile.delete() }
                    if (runCatching { predFile.renameTo(staleFile) }
                            .getOrDefault(false)
                    ) {
                        backup = aside
                        stale = staleFile
                    } else {
                        // Geser .stale gagal (kasus IO eksotik): kembali ke
                        // perilaku lama agar alur tetap satu cadangan.
                        runCatching { aside.delete() }
                        if (runCatching { predFile.renameTo(aside) }
                                .getOrDefault(false)
                        ) {
                            backup = aside
                        }
                    }
                } else if (runCatching { predFile.renameTo(aside) }
                        .getOrDefault(false)
                ) {
                    backup = aside
                }
            }
            if (backup == null) {
                deletePublished(
                    FileSaver.PublishResult(
                        contentUri = predecessor.contentUri,
                        filePath = predecessor.filePath,
                        fileName = predecessor.fileName
                    )
                )
            }
        }
        val result = try {
            if (item.folderPath.isNotBlank()) {
                saver.publishToPath(partial, cleanName, item.folderPath)
                    ?: throw IOException("Destination folder is invalid or not writable: ${item.folderPath}")
            } else {
                saver.publish(partial, cleanName, item.destination)
            }
        } catch (e: Exception) {
            val b = backup
            val target = predecessor?.filePath
                ?.takeIf { it.isNotEmpty() }?.let { File(it) }
            if (b != null && target != null && !target.exists()) {
                runCatching { b.renameTo(target) }
            }
            stale?.let { s -> runCatching { s.delete() } }
            throw e
        }
        if (predecessor != null) {
            val b = backup
            if (b == null) {
                updateTransformed { daftar -> daftar.filterNot { it.id == predecessor.id } }
            } else {
                // Ada checksum yang akan diverifikasi pemanggil (tertulis
                // di item atau sidecar di samping hasil terbit): entri
                // pendahulu + berkas cadangan dipertahankan sampai hasil
                // verifikasi jelas lewat commit/rollbackReplacement. Tanpa
                // checksum, versi lama langsung dipensiunkan di sini.
                val expectsVerify = parseChecksum(item.checksum) != null ||
                    saver.sidecarChecksum(
                        item.copy(
                            contentUri = result.contentUri ?: item.contentUri,
                            filePath = result.filePath ?: item.filePath
                        )
                    ) != null
                if (expectsVerify) {
                    pendingReplacements[item.id] =
                        PendingReplacement(predecessor, b, stale)
                } else {
                    runCatching { b.delete() }
                    stale?.let { s -> runCatching { s.delete() } }
                    updateTransformed { daftar -> daftar.filterNot { it.id == predecessor.id } }
                }
            }
        }
        return result
    }

    /** Pulihkan penggantian terparkir yang ditinggal mati proses (#237).
     *  publishItem menggeser berkas pendahulu ke "<nama>.replaced" selama
     *  checksum penerus diverifikasi; commit menghapus cadangan itu dan
     *  rollback menamainya kembali. Bila proses mati di antaranya, cadangan
     *  masih ada saat start dan keputusan yang aman adalah rollback: byte
     *  penerus yang belum terverifikasi dibuang, byte pendahulu dipulihkan,
     *  dan entri penerus kehilangan penunjuk berkas terbitnya — tanpa itu,
     *  menghapus entri pendahulu/penerus dari UI bisa menghapus berkas
     *  yang salah karena keduanya menunjuk path yang sama. */
    private fun reconcileOrphanedReplacements(): Boolean {
        // Snapshot hanya INPUT keputusan berkas; hasil akhirnya
        // diterapkan ke daftar TERKINI di dalam monitor di bawah —
        // penetapan langsung _items.value dari salinan ini menimpa
        // mutasi pengguna (pause/resume/remove/add) yang mendarat
        // selama loop berkas berjalan (#304).
        val snapshot = _items.value
        val clearedIds = mutableSetOf<String>()
        for (successor in snapshot.filter { it.replacesItemId.isNotEmpty() }) {
            val predecessor = snapshot.find { it.id == successor.replacesItemId }
            // Path target bisa juga diturunkan dari penerus sendiri
            // (#317): pendahulu yang sudah dihapus pengguna (atau
            // filePath-nya kosong) membuat loop lama berhenti SEBELUM
            // cabang pembersih stale, jadi .replaced.stale di sebelah
            // path terbit penerus menetap selamanya. Penerus terbit
            // di path yang sama dengan pendahulunya, jadi filePath
            // penerus adalah petunjuk yang sah.
            val path = predecessor?.filePath?.takeIf { it.isNotEmpty() }
                ?: successor.filePath?.takeIf { it.isNotEmpty() }
                ?: continue
            val target = File(path)
            val aside = File(target.parentFile, target.name + ".replaced")
            if (!aside.isFile) {
                // Tanpa cadangan, .replaced.stale di sebelahnya yatim
                // (#276): sisa proses yang mati di antara dua penghapusan
                // commitReplacement (cadangan terhapus, stale belum).
                // Byte percobaan tidak dirujuk item mana pun dan tidak
                // bisa menjadi pasangan finalisasi berjalan (finalisasi
                // selalu memegang cadangannya) — buang agar tidak menetap
                // sebesar berkas penuh di folder terbit pengguna.
                runCatching {
                    File(target.parentFile, target.name + ".replaced.stale").delete()
                }
                continue
            }
            // Pendahulu sudah tidak ada di daftar: cadangannya JANGAN
            // dipulihkan (itu menghidupkan lagi berkas yang sengaja
            // dihapus pengguna). Stale-nya sudah dibuang di cabang
            // tanpa cadangan bila tak ada; bila cadangan masih ada,
            // biarkan apa adanya untuk keputusan jalur lain.
            if (predecessor == null) {
                // Stale tetap byte percobaan gagal: buang juga di sini.
                runCatching {
                    File(target.parentFile, target.name + ".replaced.stale").delete()
                }
                continue
            }
            if (target.exists()) runCatching { target.delete() }
            val restored = runCatching { aside.renameTo(target) }
                .getOrDefault(false)
            if (!restored) continue
            // Byte percobaan finalisasi sebelumnya (#249) ikut dibuang:
            // rollback berarti kembali ke byte pendahulu asli.
            runCatching {
                File(target.parentFile, target.name + ".replaced.stale").delete()
            }
            clearedIds += successor.id
            App.logEvent(
                "REPLACE RECONCILE: restored ${target.name} from backup " +
                    "after process death"
            )
        }
        if (clearedIds.isEmpty()) return false
        // Penerus tidak pernah selesai (commit selalu mendahului
        // penandaan COMPLETED): kosongkan penunjuk berkas terbitnya,
        // dinilai atas daftar terkini di dalam kunci. flushSave tidak
        // dipanggil di sini: satu-satunya pemanggil (init) berjalan
        // pre-gate sehingga panggilan itu selalu no-op — persistensi
        // ditanggung flush penutup init (lewat saveDeferredPreGate
        // yang ditandai scheduleSave di updateTransformed ini).
        updateTransformed { cur ->
            cur.map {
                if (it.id in clearedIds) {
                    it.copy(filePath = null, contentUri = null)
                } else {
                    it
                }
            }
        }
        return true
    }

    /** Verifikasi penerus lolos: pensiunkan pendahulu (#215). */
    private fun commitReplacement(successorId: String) {
        val pending = pendingReplacements.remove(successorId) ?: return
        val predecessor = pending.predecessor
        runCatching { pending.backup.delete() }
        pending.stale?.let { s -> runCatching { s.delete() } }
        // Baris MediaStore pendahulu menunjuk path lama yang kini ditempati
        // penerus; hapus HANYA baris URI-nya (filePath dikosongkan agar
        // berkas penerus tidak ikut terhapus).
        if (!predecessor.contentUri.isNullOrEmpty()) {
            deletePublished(
                FileSaver.PublishResult(
                    contentUri = predecessor.contentUri,
                    filePath = null,
                    fileName = predecessor.fileName
                )
            )
        }
        updateTransformed { daftar -> daftar.filterNot { it.id == predecessor.id } }
    }

    /** Verifikasi penerus gagal: pulihkan berkas pendahulu (#215). */
    private fun rollbackReplacement(successorId: String) {
        val pending = pendingReplacements.remove(successorId) ?: return
        val target = pending.predecessor.filePath
            ?.takeIf { it.isNotEmpty() }?.let { File(it) } ?: return
        // Target yang masih ada di titik ini memuat byte penerus yang
        // GAGAL verifikasi — pemanggil sudah mencoba deletePublished,
        // tetapi kegagalannya ditelan diam-diam (#275). Singkirkan dulu
        // agar cadangan bisa dipulihkan; tanpa ini byte korup bertahan
        // menyamar sebagai versi lama yang bagus.
        if (target.exists()) {
            runCatching { target.delete() }
        }
        val restored = if (!target.exists()) {
            runCatching { pending.backup.renameTo(target) }
                .getOrDefault(false)
        } else {
            false
        }
        // Berkas percobaan (.stale) hanya dibuang bila pemulihan tuntas
        // atau cadangan memang sudah hilang; bila cadangan masih ada
        // (pemulihan gagal), keduanya dipertahankan agar rekonsiliasi
        // start berikutnya (#237) masih bisa memulihkan versi lama.
        if (restored || !pending.backup.exists()) {
            pending.stale?.let { s -> runCatching { s.delete() } }
        }
    }

    private fun organizeIfEnabled(
        saver: FileSaver,
        result: FileSaver.PublishResult,
        fileName: String
    ): FileSaver.PublishResult {
        return if (StoragePrefs.isAutoSortEnabled(context)) {
            saver.organizeByType(result, fileName)
        } else {
            result
        }
    }

    // Penerus monitor yang berakhir FAILED terminal: hidupkan lagi
    // monitor pendahulunya (issue #512). Byte versi lama utuh
    // (rollback penggantian), jadi pemantauan bisa mencoba lagi di
    // tick berikut — tanpa ini monitor mati diam-diam: pendahulu
    // monitor=false sejak penerus dibuat, dan penerus yang tidak
    // COMPLETED tidak dinilai filter monitor siapa pun lagi.
    private fun hidupkanLagiMonitorPendahulu(item: DownloadItem) {
        val predId = item.replacesItemId ?: return
        updateItem(predId) { p ->
            if (p.state == DownloadState.COMPLETED && !p.monitor) {
                p.copy(monitor = true)
            } else {
                p
            }
        }
    }

    private fun handleFailure(id: String, message: String?) {
        val item = _items.value.find { it.id == id } ?: return
        speedTracker.reset(id)
        // Buang sampel speed/ETA + penyangga segmen agar retry tidak memakai
        // angka basi sesaat (≤1 dtk) dari attempt yang gagal.
        lastSpeedSample.remove(id)
        clearSegProgress(id)
        val maxRetries = StoragePrefs.maxRetries(context)
        val attempts = (retryAttempts[id] ?: 0) + 1
        val rangeRejected = isRangeRejectError(message)
        val slowRejected = isSlowError(message)
        rememberFailedUrl(item.url)
        // YouTube CDN (googlevideo.com) menolak URL yang butuh n-signature dengan
        // HTTP 403 permanen — retry tidak akan pernah berhasil, jadi gagalkan
        // segera dengan pesan yang jelas (mirror/URL lain juga sia-sia).
        val ytSignatureBlocked = message?.contains("HTTP 403") == true &&
            (item.url.contains("googlevideo.com") || item.url.contains("youtube.com"))
        if (ytSignatureBlocked) {
            retryAttempts.remove(id)
            App.logEvent(
                "DOWNLOAD FAILED: ${item.fileName} — YouTube blocked this URL (n-signature). " +
                "Try again later or use another downloader."
            )
            val finished = updateItem(id) {
                it.copy(
                    state = DownloadState.FAILED,
                    error = message,
                    autoResume = false,
                    speedBps = 0,
                    etaSeconds = 0
                )
            }
            flushSave()
            hidupkanLagiMonitorPendahulu(item)
            // Item terhapus di tengah pemrosesan gagal -> jangan kirim
            // notifikasi untuk unduhan yang sudah tidak ada (issue #392).
            if (finished != null) NotificationHelper.notifyItemFinished(context, finished)
            return
        }
        // Fitur mirror: gagal dari URL aktif -> pindah ke URL cadangan berikutnya.
        // Bila host GitHub tak terjangkau, buat mirror proxy otomatis.
        val autoMirrors = if (item.mirrors.isEmpty() &&
            (isConnectError(message) || isSlowError(message)) && isGitHubUrl(item.url)
        ) {
            githubMirrors(item.url)
        } else {
            emptyList()
        }
        val allMirrors = (if (item.mirrors.isNotEmpty()) item.mirrors else autoMirrors)
            .filterNot { isFailedUrl(it) }
        // Error Range pasti gagal lagi di URL yang sama, dan mirror yang pernah
        // gagal tidak perlu dicoba ulang: langsung coba cadangan berikutnya.
        if (allMirrors.isNotEmpty() && (rangeRejected || slowRejected || isConnectError(message))) {
            val next = allMirrors.first()
            App.logEvent("DOWNLOAD FAILED: ${item.fileName} — ${message ?: "?"} (switching to mirror: $next)")
            updateItem(id) {
                it.copy(
                    url = next,
                    mirrors = allMirrors.drop(1),
                    state = DownloadState.PENDING,
                    error = null,
                    autoResume = true
                )
            }
            retryAttempts.remove(id)
            scope.launch {
                delay(1_500)
                if (_items.value.find { it.id == id }?.state == DownloadState.PENDING) {
                    attemptStart(id)
                }
            }
            return
        }
        if (rangeRejected) {
            // Server/proxy menolak resume (HTTP 403/501/416): mengulang URL yang
            // sama hanya membuang waktu, jadi tandai gagal tanpa percobaan ulang.
            retryAttempts.remove(id)
            App.logEvent("DOWNLOAD FAILED: ${item.fileName} — ${message ?: "?"} (server refused resume; try another URL/mirror)")
            val finishedRange = updateItem(id) {
                it.copy(
                    state = DownloadState.FAILED,
                    error = message,
                    autoResume = false,
                    speedBps = 0,
                    etaSeconds = 0
                )
            }
            flushSave()
            if (finishedRange != null) NotificationHelper.notifyItemFinished(context, finishedRange)
            return
        }
        if (maxRetries > 0 && attempts <= maxRetries && item.autoResume) {
            retryAttempts[id] = attempts
            updateItem(id) { it.copy(retryCount = attempts) }
            val baseBackoff = (RETRY_DELAY_1_MS shl (attempts - 1))
                .coerceAtMost(RETRY_DELAY_MAX_MS)
            // Jitter 0-25% agar beberapa item tidak retry serentak (thundering herd).
            val backoff = baseBackoff + Random.nextLong(0, (baseBackoff / 4) + 1)
            val retryInfo = "Failed (attempt $attempts/$maxRetries) — retrying in ${Formats.eta(backoff / 1000)}"
            App.logEvent("DOWNLOAD FAILED: ${item.fileName} — ${message ?: "?"} ($retryInfo)")
            updateItem(id) { it.copy(state = DownloadState.PENDING, error = retryInfo) }
            pendingRetries.add(id)
            scope.launch {
                delay(backoff)
                pendingRetries.remove(id)
                if (_items.value.find { it.id == id }?.state == DownloadState.PENDING) {
                    updateItem(id) { it.copy(error = null) }
                    attemptStart(id)
                }
            }
        } else if (item.autoResume && (isNetworkError(message) || isTailIncompleteError(message))) {
            // Gagal karena jaringan/ekor terpotong (Size mismatch, segmen
            // incomplete): partial tetap valid, jadi jangan FAILED — biarkan
            // PAUSED agar otomatis lanjut saat koneksi pulih.
            retryAttempts.remove(id)
            App.logEvent("DOWNLOAD PAUSED (network): ${item.fileName} — ${message ?: "?"}")
            updateItem(id) {
                it.copy(
                    state = DownloadState.PAUSED,
                    error = message,
                    autoResume = true,
                    speedBps = 0,
                    etaSeconds = 0
                )
            }
            flushSave()
        } else {
            retryAttempts.remove(id)
            App.logEvent("DOWNLOAD FAILED: ${item.fileName} — ${message ?: "?"}")
            val finishedElse = updateItem(id) {
                it.copy(
                    state = DownloadState.FAILED,
                    error = message,
                    autoResume = false,
                    speedBps = 0,
                    etaSeconds = 0
                )
            }
            flushSave()
            hidupkanLagiMonitorPendahulu(item)
            if (finishedElse != null) NotificationHelper.notifyItemFinished(context, finishedElse)
        }
    }

    private fun isSlowError(message: String?): Boolean {
        if (message.isNullOrEmpty()) return false
        return message.contains("speed too low", ignoreCase = true) ||
            message.contains("connection stalled", ignoreCase = true)
    }

    private fun isConnectError(message: String?): Boolean {
        if (message.isNullOrEmpty()) return false
        return message.contains("failed to connect", ignoreCase = true) ||
            message.contains("unable to resolve host", ignoreCase = true) ||
            message.contains("unknownhost", ignoreCase = true) ||
            message.contains("connect timed out", ignoreCase = true) ||
            message.contains("connection refused", ignoreCase = true) ||
            message.contains("network is unreachable", ignoreCase = true) ||
            message.contains("timeout", ignoreCase = true)
    }


    private fun githubMirrors(url: String): List<String> {
        // URL signed release-asset (release-assets.githubusercontent.com) hanya
        // bisa di-proxy oleh gh-proxy.com; mirror lain menolak (403/501).
        val signedAsset = url.contains("release-assets.githubusercontent.com") ||
            url.contains("/github-production-release-asset/")
        val prefixes = if (signedAsset) {
            listOf(
                "https://gh-proxy.com/",
                "https://ghfast.top/",
                "https://ghproxy.net/"
            )
        } else {
            listOf(
                "https://ghfast.top/",
                "https://gh-proxy.com/",
                "https://ghproxy.net/"
            )
        }
        return prefixes.map { it + url }
    }

    private fun isNetworkError(message: String?): Boolean {
        if (message.isNullOrBlank()) return false
        return message.contains("unknownhost", ignoreCase = true) ||
            message.contains("timeout", ignoreCase = true) ||
            message.contains("failed to connect", ignoreCase = true) ||
            message.contains("connect exception", ignoreCase = true) ||
            message.contains("network", ignoreCase = true) ||
            message.contains("socket", ignoreCase = true) ||
            // Badan terpotong di tengah jalan (EOF prematur) adalah gangguan
            // jaringan transien: pesan "HLS segment truncated" dari
            // fetchHlsSegment wajib lolos klasifikasi ini agar retry segmen
            // benar-benar berjalan (issue #167).
            message.contains("truncated", ignoreCase = true)
    }

    private suspend fun runDownload(item: DownloadItem, skipSocial: Boolean = false) {
        // Resume HLS: URL sudah berupa manifest m3u8 dari ekstraksi sebelumnya.
        // Arahkan ulang ke downloadHls agar tidak diunduh sebagai file polos.
        // TAPI: bila URL ini sudah gagal sebelumnya (failedHlsUrls), jangan retry
        // HLS yang sama — lewati ke ekstraksi sosial media supaya URL baru didapat.
        if (isHlsManifestUrl(item.url) && !failedHlsUrls.contains(item.url)) {
            val hlsName = item.fileName
            updateItem(item.id) { it.copy(state = DownloadState.DOWNLOADING) }
            downloadHls(item, hlsName)
            return
        }
        // HLS URL sudah gagal sebelumnya — re-extract dari URL sosial media original.
        val originalUrl = originalSocialUrls[item.id]
        if (originalUrl != null && isHlsManifestUrl(item.url)) {
            App.logEvent("HLS: re-extracting from original URL (manifest was failed)")
            updateItem(item.id) { it.copy(url = originalUrl) }
            return runDownload(item.copy(url = originalUrl), skipSocial)
        }
        // Social media: ekstrak direct URL menggunakan API publik
        // (tikwm.com untuk TikTok, embed page JSON untuk Instagram, vxtwitter untuk Twitter)
        // skipSocial=true dipakai saat URL sudah hasil ekstraksi (mis. URL CDN
        // hasil ekstraksi media sosial) — jangan di-extract ulang karena bisa salah deteksi.
        if (!skipSocial && SocialMediaExtractor.isSocialMediaUrl(item.url)) {
            val host = runCatching { java.net.URL(item.url).host }.getOrDefault("?")
            App.logEvent("SOCIAL: extracting direct URL from $host ...")
            val result = SocialMediaExtractor.extract(item.url, item.headers)
            if (result != null && result.directUrl != item.url) {
                App.logEvent("SOCIAL: extracted direct URL from $host → ${result.directUrl.take(80)}...")
                App.logEvent("SOCIAL: fileName=${result.fileName}, cookies=${result.cookies.length} chars")
                // Simpan URL sosial media original untuk SEMUA hasil ekstraksi
                // (bukan cuma HLS): URL CDN langsung bertanda-tangan (mis.
                // xvideos-cdn) bisa kedaluwarsa di tengah unduhan — retry butuh
                // re-extract URL segar dari halaman asli agar progres puluhan
                // MB tidak dibuang (lihat refreshExpiredCdnUrl).
                originalSocialUrls[item.id] = item.url
                // YouTube via HLS: segmen .ts digabung jadi satu file.
                if (result.isHls) {
                    // Nama dari API pihak ketiga wajib disanitasi (separator di
                    // dalamnya membuat dua item berbagi file .part yang sama di
                    // disk). Nama kustom user selalu menang bila ada.
                    val hlsName = if (!item.nameIsCustom) sanitizeFileName(result.fileName ?: item.fileName)
                    else item.fileName
                    val hlsHeaders = if (result.cookies.isNotEmpty()) {
                        val existing = item.headers.trim()
                        if (existing.isNotEmpty()) "${existing}\nCookie: ${result.cookies}" else "Cookie: ${result.cookies}"
                    } else item.headers
                    updateItem(item.id) {
                        it.copy(url = result.directUrl, fileName = if (!item.nameIsCustom) hlsName else item.fileName, headers = hlsHeaders)
                    }
                    try {
                        downloadHls(
                            item.copy(url = result.directUrl, fileName = hlsName, headers = hlsHeaders),
                            hlsName,
                            adaptiveAudioUrl = result.audioUrl
                        )
                        return
                    } catch (e: IOException) {
                        // HLS gagal (mis. media playlist butuh pot token / 403).
                        // Tandai URL HLS sebagai gagal supaya resume/retry tidak
                        // mengulang HLS yang sama — re-extract dari YouTube URL.
                        rememberFailedHlsUrl(result.directUrl)
                        // Fallback: coba ekstrak non-HLS (page/Piped/Invidious).
                        App.logEvent("HLS: download failed (${e.message?.take(50)}), trying non-HLS fallback")
                        val fallback = SocialMediaExtractor.extractNonHlsYouTube(item.url, item.headers)
                        if (fallback != null && fallback.directUrl != item.url && !fallback.isHls) {
                            App.logEvent("SOCIAL: non-HLS fallback: ${fallback.directUrl.take(80)}")
                            val fbName = if (!item.nameIsCustom) sanitizeFileName(fallback.fileName ?: item.fileName)
                            else item.fileName
                            val fbHeaders = if (fallback.cookies.isNotEmpty()) {
                                val existing = item.headers.trim()
                                if (existing.isNotEmpty()) "${existing}\nCookie: ${fallback.cookies}" else "Cookie: ${fallback.cookies}"
                            } else item.headers
                            updateItem(item.id) { it.copy(url = fallback.directUrl, fileName = if (!item.nameIsCustom) fbName else item.fileName, headers = fbHeaders) }
                            val fbItem = item.copy(url = fallback.directUrl, fileName = fbName, headers = fbHeaders)
                            if (fallback.audioUrl.isNotEmpty()) {
                                App.logEvent("SOCIAL: non-HLS adaptive pair → merging video+audio into MP4")
                                downloadAdaptiveMuxed(fbItem, fallback.videoUrl, fallback.audioUrl, fbName)
                                return
                            }
                            return runDownload(fbItem, skipSocial = true)
                        }
                        // Non-HLS juga gagal — kembalikan URL ke original supaya
                        // retry ulang melewati ekstraksi sosial media (bukan langsung HLS).
                        updateItem(item.id) { it.copy(url = item.url) }
                        throw e
                    }
                }
                // Non-HLS extraction path (bukan HLS)
                val newName = sanitizeFileName(result.fileName ?: item.fileName)
                // Gabung cookies dari extraction ke headers untuk CDN download
                // (#238: cookie user tidak ditimpa cookie ekstraksi).
                val mergedHeaders = SocialMediaExtractor.mergeCookiesIntoHeaders(
                    item.headers, result.cookies
                )
                updateItem(item.id) { it.copy(
                    url = result.directUrl,
                    fileName = if (!item.nameIsCustom) newName else item.fileName,
                    headers = mergedHeaders
                ) }
                // resultItem wajib memakai nama yang sama dengan updateItem di
                // atas (kustom user menang) — sebelumnya file terpublish memakai
                // nama ekstraksi walau user mengetik nama sendiri.
                val effectiveName = if (!item.nameIsCustom) newName else item.fileName
                val resultItem = item.copy(url = result.directUrl, fileName = effectiveName, headers = mergedHeaders)
                if (result.audioUrl.isNotEmpty()) {
                    App.logEvent("SOCIAL: adaptive pair → merging video+audio into MP4")
                    downloadAdaptiveMuxed(resultItem, result.videoUrl, result.audioUrl, effectiveName)
                    return
                }
                return runDownload(resultItem, skipSocial = true)
            }
            // Ekstraksi gagal — post mungkin private/deleted atau platform memblokir.
            // HentaiHaven memakai challenge Cloudflare: arahkan ke browser
            // ekstraksi dalam aplikasi (tombol di dialog tambah URL).
            throw IOException(
                if (SocialMediaExtractor.isHentaiHavenUrl(item.url)) {
                    "Cannot download from $host (Cloudflare protection). " +
                    "Use 'Open in app browser' in the add-download dialog to extract the video first."
                } else {
                    "Cannot download from $host. " +
                    "This post may be private, deleted, or temporarily unavailable. " +
                    "Try opening the post in your browser first."
                }
            )
        }
        val saver = fileSaver
        val freeNow = saver.freeBytes()
        if (freeNow < MIN_FREE_BYTES) {
            throw IOException(
                "Storage almost full (free ${Formats.bytes(freeNow)})"
            )
        }
        coroutineContext.ensureActive()
        val globalLimit = StoragePrefs.speedLimitKbps(context)
        val limit = if (item.speedLimitKbps > 0) item.speedLimitKbps else globalLimit
        // Limit global dipakai bersama antar item (total throughput global),
        // limit per-item tetap dihitung per item.
        val throttle = if (item.speedLimitKbps > 0) {
            SpeedThrottle(limit, null)
        } else {
            SpeedThrottle(limit, globalRateLimiter())
        }

        if (item.segments.isNotEmpty()) {
            runSegmented(item, saver, throttle, item.totalBytes, null)
            return
        }

        var useSegments = false
        var segmentedTotal = 0L
        var probeHeaders: ServerHeaders? = null
        val probe = openAuthenticatedConnection(
            item.url, method = "HEAD",
            username = item.username,
            password = item.password,
            headers = item.headers
        )
        try {
            val code = probe.responseCode
            if (code in 200..299) {
                captureHeaderChecksum(item, probe)
                probeHeaders = headersOf(probe)
                invalidateChangedResume(_items.value.find { it.id == item.id } ?: item, probeHeaders.etag)
                val total = contentLength(probe)
                val ranges = probe.getHeaderField("Accept-Ranges").equals("bytes", ignoreCase = true)
                // Mirror (proxy GitHub) umumnya tidak mendukung Range, jadi
                // lewati multi-segmen untuk URL cadangan.
                if (ranges && total >= SEGMENT_MIN_BYTES &&
                    StoragePrefs.segmentCount(context) > 1 && item.mirrors.isEmpty()
                ) {
                    useSegments = true
                    segmentedTotal = total
                }
            }
        } catch (_: Exception) { // audit-ignore: file_scoped_catch_all, kotlin_empty_catch (HEAD tak didukung adalah hasil sah: lanjut dengan GET biasa)
            // HEAD tidak didukung; lanjut dengan GET biasa
        } finally {
            probe.disconnect()
        }
        coroutineContext.ensureActive()

        if (useSegments) {
            runSegmented(item, saver, throttle, segmentedTotal, probeHeaders)
            return
        }

        // Offset resume dihitung dulu (sekaligus truncate/selaraskan file
        // parsial): header Range harus sudah terpasang saat koneksi dibuka.
        val resumeFrom = prepareResumeOffset(item, saver)
        val conn = openAuthenticatedConnection(
            item.url, method = if (item.method == "POST") "POST" else "GET",
            username = item.username,
            password = item.password,
            headers = item.headers,
            presetRange = resumeFrom.takeIf { it > 0 }
        ) { connection, _, hopMethod ->
            // Body hanya ditulis pada hop yang metodenya masih POST
            // (issue #504): hop hasil konversi 301/302/303 sudah GET.
            if (item.method == "POST" && hopMethod == "POST" && item.postBody.isNotEmpty()) {
                connection.doOutput = true
                connection.outputStream.use { it.write(item.postBody.toByteArray(Charsets.UTF_8)) }
            }
        }
        try {
            runSingle(item, conn, saver, throttle)
        } finally {
            conn.disconnect()
        }
    }

    private fun isHlsManifestUrl(url: String): Boolean {
        return url.contains("manifest.googlevideo.com") ||
            url.contains("/api/manifest/hls") ||
            url.contains(".m3u8", ignoreCase = true)
    }

    /** Unduh HLS (m3u8): pilih varian terbaik, unduh semua segmen .ts lalu
     *  gabung jadi satu file dapat diputar. Tidak butuh ffmpeg — segmen dari
     *  YouTube HLS sudah berisi video+audio gabungan (MPEG-TS). */
    private suspend fun downloadHls(item: DownloadItem, outName: String, adaptiveAudioUrl: String = "") {
        val saver = fileSaver
        val freeNow = saver.freeBytes()
        if (freeNow < MIN_FREE_BYTES) throw IOException("Storage almost full (free ${Formats.bytes(freeNow)})")

        val master = fetchText(item.url, item.headers, HLS_PROBE_MAX_BYTES)
            ?: throw IOException("Cannot fetch HLS manifest")
        val plan = parseHlsPlan(master, item.url, item.preferredHeight, item.headers, item.preferredAudioLang)
        if (plan == null || plan.videoSegments.isEmpty()) {
            throw IOException("No HLS segments found")
        }
        App.logEvent(
            "HLS: ${plan.videoSegments.size} video segments, " +
                (plan.audioSegments?.size ?: 0) + " audio segments"
        )

        val globalLimit = StoragePrefs.speedLimitKbps(context)
        val limit = if (item.speedLimitKbps > 0) item.speedLimitKbps else globalLimit
        val throttle = if (item.speedLimitKbps > 0) {
            SpeedThrottle(limit, null)
        } else {
            SpeedThrottle(limit, globalRateLimiter())
        }

        val baseName = outName.removeSuffix(".ts").removeSuffix(".mp4").ifBlank { outName }
        // Nama temp WAJIB unik per item: baseName membuang ekstensi sehingga
        // dua item yang namanya hanya beda ekstensi ("film.mp4" vs "film.ts")
        // menghasilkan temp identik dan saling menghapus/menimpa saat jalan
        // bersamaan. Potongan id item (UUID, aman nama file) jadi pembeda;
        // nama final tetap dari baseName. Pembeda ditaruh di DEPAN: di ekor
        // ia bisa terpotong FileNames.safe untuk nama ±200 karakter sehingga
        // tabrakan kembali pada kasus tepi itu (issue #171).
        val tempBase = "~" + item.id.take(8) + baseName
        // Temp video/audio TIDAK dihapus di awal (issue #385): segmen yang
        // sudah selesai dipertahankan untuk resume percobaan berikutnya;
        // downloadSegmentsToFile membuangnya sendiri bila penanda .segs
        // tak cocok dengan rencana ini. Hasil remux selalu dibuat ulang
        // dari nol (kerja lokal, bukan jaringan), jadi mp4 tetap dihapus.
        val videoTs = saver.partialFile("$tempBase.video", segment = null)
        val audioAdts = saver.partialFile("$tempBase.audio", segment = null)
        val mp4 = saver.partialFile("$tempBase.muxed", segment = null).apply { delete() }
        val progress = HlsProgress()
        progress.estimateTotalBytes = plan.estimateTotalBytes
        if (plan.estimateTotalBytes > 0) {
            App.logEvent(
                "HLS: estimasi total ${Formats.bytes(plan.estimateTotalBytes)} " +
                    "(BANDWIDTH x durasi #EXTINF) — denominator stabil sejak awal"
            )
        }
        val buffer = ByteArray(BUFFER_SIZE)
        coroutineContext.ensureActive()

        // Estimasi dari BANDWIDTH x durasi tersedia sebelum segmen diunduh:
        // tolak awal bila jelas tak muat, bukan ENOSPC di tengah jalan.
        if (plan.estimateTotalBytes > 0 &&
            saver.destinationFreeBytes(item.folderPath) < plan.estimateTotalBytes + MIN_FREE_BYTES
        ) {
            throw IOException(
                "Not enough storage for HLS: need ~${Formats.bytes(plan.estimateTotalBytes)}, " +
                    "available ${Formats.bytes(saver.destinationFreeBytes(item.folderPath))}"
            )
        }

        try {
            // 1) Unduh segmen video (MPEG-TS) ke file temp.
            progress.totalSegments = plan.videoSegments.size + (plan.audioSegments?.size ?: 0)
            downloadSegmentsToFile(
                item, plan.videoSegments, videoTs, buffer, throttle, progress,
                fallbackQuery = plan.segmentQueryFallback
            )

            // 2) Unduh segmen audio (ADTS AAC) ke file temp bila terpisah.
            var audioStream: AdtsAac.Stream? = null
            val audioPlan = plan.audioSegments
            if (!audioPlan.isNullOrEmpty()) {
                try {
                    // Strip tag ID3 per-segmen supaya file diisi ADTS bersih yang
                    // bisa dibaca parser ADTS.
                    downloadSegmentsToFile(
                        item, audioPlan, audioAdts, buffer, throttle, progress,
                        stripId3Segments = true
                    )
                    val stream = AdtsAac.open(audioAdts)
                    if (stream != null && !stream.isEmpty) audioStream = stream
                } catch (e: CancellationException) {
                    // Pembatalan bukan kegagalan audio: jangan ditelan jadi
                    // "audio skipped" (pola fetchHlsSegmentWithRetry).
                    throw e
                } catch (e: Exception) {
                    audioStream = null
                }
                if (audioStream == null) App.logEvent("HLS: audio download/parse failed, audio skipped")
            }

            // 2b) Audio playlist HLS di-404 YouTube — coba audio langsung dari
            //     adaptiveFormats VISIONOS (URL M4A tanpa n-transform).
            var adaptiveAudioFile: File? = null
            if (audioStream == null) {
                adaptiveAudioFile = tryDownloadAdaptiveAudio(
                    item, adaptiveAudioUrl, tempBase, buffer, throttle, progress, saver
                )
            }

            coroutineContext.ensureActive()

            // 3) Remux video + audio jadi MP4 bila audio tersedia.
            var remuxed = false
            if (audioStream != null) {
                remuxed = HlsMp4Muxer.remux(videoTs, audioStream, mp4, plan.videoSegmentDurationsUs)
            } else if (adaptiveAudioFile != null) {
                remuxed = HlsMp4Muxer.remuxWithAudioFile(
                    videoTs, adaptiveAudioFile, mp4, plan.videoSegmentDurationsUs
                )
            }
            if (remuxed) {
                App.logEvent("HLS: remux OK → MP4 with audio")
                val fileName = "$baseName.mp4"
                ensureItemPresent(item.id, mp4)
                val published0 = publishItem(saver, mp4, fileName, item)
                val finalName = published0.fileName ?: fileName
                // Sama seperti jalur berkas tunggal: checksum diverifikasi
                // SEBELUM pendahulu dipensiunkan (#236 — commitReplacement
                // tanpa verifikasi menghapus versi lama yang bagus).
                verifyChecksum(item.id, published0, saver)?.let {
                    deletePublished(published0)
                    rollbackReplacement(item.id)
                    throw IOException(it)
                }
                commitReplacement(item.id)
                val published = organizeIfEnabled(saver, published0, finalName)
                ensurePublishedKept(item.id, published0, published)
                // Temp + penanda resume baru dihapus SESUDAH publish dan
                // verifikasi checksum berhasil (issue #430): gagal di
                // jendela sebelumnya harus bisa retry dengan resume
                // per segmen (#385), bukan unduh ulang dari segmen 0.
                deleteHlsTempWithSidecar(videoTs)
                deleteHlsTempWithSidecar(audioAdts)
                adaptiveAudioFile?.delete()
                finishHls(item, progress.downloaded, published, finalName)
                return
            }

            // 4) Fallback: publish video-only .ts seperti semula.
            App.logEvent("HLS: remux failed (no audio available), falling back to video-only .ts")
            runCatching { mp4.delete() }
            // Audio adaptive yang sudah terunduh penuh tak terpakai lagi
            // di jalur .ts — hapus sekarang, jangan jadi yatim (issue #431).
            adaptiveAudioFile?.delete()
            val fileName = "$baseName.ts"
            ensureItemPresent(item.id, videoTs)
            val published0 = publishItem(saver, videoTs, fileName, item)
            val finalName = published0.fileName ?: fileName
            verifyChecksum(item.id, published0, saver)?.let {
                deletePublished(published0)
                rollbackReplacement(item.id)
                throw IOException(it)
            }
            commitReplacement(item.id)
            val published = organizeIfEnabled(saver, published0, finalName)
            ensurePublishedKept(item.id, published0, published)
            // Sidecar audio baru dihapus sesudah publish + checksum
            // berhasil (issue #430) — videoTs sendiri adalah sumber
            // yang diterbitkan, bukan temp yang dihapus di sini.
            deleteHlsTempWithSidecar(audioAdts)
            finishHls(item, progress.downloaded, published, finalName)
        } catch (e: Exception) {
            // Temp video/audio dipertahankan untuk resume (issue #385) —
            // termasuk jalur pause (CancellationException): membuangnya
            // di sini berarti jeda/gagal di segmen terakhir tetap
            // mengunduh ulang dari segmen 0. Temp yatim sesudah item
            // selesai/dihapus dibersihkan pemindaian orphan .part seperti
            // partial lain. Hanya hasil remux yang dibuang (dibuat ulang).
            runCatching { mp4.delete() }
            throw e
        }
    }

    private class HlsProgress {
        var downloaded = 0L
        var lastNotify = 0L
        var totalSegments = 0
        var segmentsDone = 0
        var avgSegBytes = 0L
        /** Jumlah Content-Length pasti segmen yang sudah di-fetch (mutlak). */
        var exactBytes = 0L
        /** Ukuran segmen referensi stabil (dari segmen pertama yang berukuran wajar). */
        var refSegBytes = 0L
        /** Total mutlak akhir bila semua segmen sudah punya ukuran pasti; else estimasi. */
        var exactTotal = 0L
        /** Estimasi awal total dari BANDWIDTH x durasi (dipakai sebelum segmen
         *  selesai — exactTotal masih 0). */
        var estimateTotalBytes = 0L
    }

    /** Hapus temp HLS beserta penanda resume (.segs) dan spool-nya
     *  (issue #424): jalur akhir yang hanya menghapus berkas temp
     *  meninggalkan sidecar yatim bila segmen audio gagal di tengah lalu
     *  unduhan selesai lewat jalur lain (audio adaptive / fallback .ts).
     *  Dipakai hanya di jalur SELESAI, sesudah publish dan verifikasi
     *  checksum berhasil (issue #430) — jalur gagal/jeda sengaja
     *  mempertahankan temp + penanda untuk resume (#385). */
    private fun deleteHlsTempWithSidecar(temp: File) {
        temp.delete()
        File(temp.parent, temp.name + ".segs").delete()
        File(temp.parent, temp.name + ".spool").delete()
    }

    /** Sidik jari rencana segmen untuk resume HLS (issue #385): hash
     *  daftar URL + mode strip; rencana yang berbeda (varian/kualitas
     *  lain) menghasilkan sidik berbeda sehingga temp lama dibuang. */
    private fun hlsSegmentsFingerprint(urls: List<String>, stripId3Segments: Boolean): String {
        val digest = java.security.MessageDigest.getInstance("SHA-256")
        digest.update((if (stripId3Segments) "strip\n" else "raw\n").toByteArray(Charsets.UTF_8))
        for (u in urls) {
            digest.update(u.toByteArray(Charsets.UTF_8))
            digest.update(10)
        }
        return digest.digest().joinToString("") { "%02x".format(it.toInt() and 0xFF) }
    }

    /** Unduh daftar segmen ke satu file. Tiap segmen di-stream dulu ke
     *  berkas spool dan hanya disalin ke tujuan sesudah sukses penuh
     *  (anti-korup), tanpa ditampung utuh di RAM (issue #371); segmen
     *  audio boleh di-strip ID3 secara streaming saat penyalinan.
     *  Segmen yang sudah selesai dipertahankan antar-percobaan lewat
     *  berkas penanda `.segs` (issue #385): percobaan berikutnya dengan
     *  rencana yang sama melanjutkan dari segmen berikutnya dalam mode
     *  append, bukan mengunduh ulang dari segmen 0. Penanda yang tak
     *  cocok (rencana berubah / ukuran berkas tak sesuai catatan) membuat
     *  unduhan mulai segar — resume tak pernah menempel pada data yang
     *  tak terverifikasi. */
    private suspend fun downloadSegmentsToFile(
        item: DownloadItem,
        urls: List<String>,
        target: File,
        buffer: ByteArray,
        throttle: SpeedThrottle,
        progress: HlsProgress,
        stripId3Segments: Boolean = false,
        fallbackQuery: String = ""
    ) {
        val spool = File(target.absoluteFile.parentFile, target.name + ".spool")
        val sidecar = File(target.absoluteFile.parentFile, target.name + ".segs")
        val fingerprint = hlsSegmentsFingerprint(urls, stripId3Segments)
        var startIndex = 0
        // Penghitung PER BERKAS untuk penanda .segs (progress bersama
        // video+audio, jadi tak bisa dipakai sebagai catatan per berkas).
        var fileWritten = 0L
        if (sidecar.isFile && target.isFile) {
            val lines = runCatching { sidecar.readLines() }.getOrDefault(emptyList())
            val done = lines.getOrNull(1)?.toIntOrNull() ?: -1
            val writtenBytes = lines.getOrNull(2)?.toLongOrNull() ?: -1L
            if (lines.getOrNull(0) == fingerprint && done in 0..urls.size &&
                writtenBytes >= 0 && target.length() == writtenBytes
            ) {
                startIndex = done
                fileWritten = writtenBytes
                progress.downloaded += writtenBytes
                progress.segmentsDone += done
                progress.exactBytes += writtenBytes
                if (done > 0) {
                    App.logEvent("HLS: resuming from segment $done/${urls.size} (${target.name})")
                }
            } else {
                runCatching { target.delete() }
                runCatching { sidecar.delete() }
            }
        }
        try {
            BufferedOutputStream(FileOutputStream(target, startIndex > 0), BUFFER_SIZE).use { out ->
                for (index in startIndex until urls.size) {
                    val url = urls[index]
                    coroutineContext.ensureActive()
                    fetchHlsSegmentWithRetry(
                        item, url, buffer, throttle, progress.downloaded, spool,
                        fallbackQuery = fallbackQuery,
                        notify = { segNow -> reportHlsProgress(item, progress, segNow) },
                        onLength = { declared ->
                            // Segmen referensi dari header Content-Length (ukuran
                            // mentah) hanya untuk estimasi sisa; ukuran PASTI segmen
                            // yang sudah selesai memakai hasil tulis riil di bawah
                            // supaya konsisten dengan bytesDownloaded (audio di-strip
                            // ID3 sehingga ukuran tulis bisa lebih kecil).
                            if (progress.refSegBytes == 0L && declared >= HLS_REF_SEGMENT_MIN) {
                                progress.refSegBytes = declared
                            }
                        }
                    )
                    val written = BufferedInputStream(FileInputStream(spool)).use { segIn ->
                        if (stripId3Segments) {
                            AdtsAac.stripId3Stream(segIn, out)
                        } else {
                            segIn.copyTo(out, BUFFER_SIZE)
                        }
                    }
                    spool.delete()
                    progress.downloaded += written
                    progress.segmentsDone++
                    // Song referensi cadangan: bila header tidak memberi ukuran wajar,
                    // pakai ukuran tulis riil segmen data pertama.
                    if (progress.refSegBytes == 0L && written >= HLS_REF_SEGMENT_MIN) {
                        progress.refSegBytes = written
                    }
                    if (progress.segmentsDone > 0) {
                        progress.avgSegBytes = progress.downloaded / progress.segmentsDone
                    }
                    // Total mendekati mutlak: jumlah ukuran riil segmen selesai +
                    // estimasi stabil (refSegBytes x sisa), supaya angka tidak bergoyang.
                    val known = progress.exactBytes + written
                    progress.exactBytes = known
                    val remaining = progress.totalSegments - progress.segmentsDone
                    progress.exactTotal = known +
                        (if (remaining > 0 && progress.refSegBytes > 0) {
                            progress.refSegBytes * remaining
                        } else 0L)
                    // Catat progres per berkas untuk resume (issue #385):
                    // sesudah segmen ini utuh di target, penanda .segs
                    // menunjuk segmen berikutnya. Gagal tulis penanda tak
                    // menggagalkan unduhan — resume hanya tak terjadi.
                    fileWritten += written
                    runCatching {
                        sidecar.writeText("$fingerprint\n${index + 1}\n$fileWritten\n")
                    }
                }
                // Semua segmen selesai: penanda resume tak diperlukan lagi.
                runCatching { sidecar.delete() }
            }
        } finally {
            spool.delete()
        }
    }

    /** Unduh satu file adaptive (audio M4A / video MP4) dari URL VISIONOS ke
     *  file temp. Mengembalikan false bila HTTP gagal atau file kosong. */
    private suspend fun downloadAdaptiveFile(
        item: DownloadItem,
        url: String,
        target: File,
        buffer: ByteArray,
        throttle: SpeedThrottle,
        progress: HlsProgress
    ): Boolean {
        if (url.isBlank()) return false
        val conn = trackOpenedConnection(
            item.id,
            openAuthenticatedConnection(
                url, method = "GET",
                username = item.username,
                password = item.password,
                headers = item.headers,
                authAnchorUrl = item.url
            )
        )
        try {
            val code = conn.responseCode
            if (code !in 200..299) return false
            val total = contentLength(conn)
            BufferedOutputStream(FileOutputStream(target), BUFFER_SIZE).use { out ->
                val input = conn.inputStream
                try {
                    var bytes = 0L
                    var zeroStreak = 0
                    while (true) {
                        val read = input.read(buffer)
                        if (read == -1) break
                        // Guard zero-read (issue #389), pola yang sama
                        // seperti loop streaming lain di berkas ini.
                        if (read == 0) {
                            if (++zeroStreak >= MAX_ZERO_READS) {
                                throw IOException("adaptive file stalled (zero reads)")
                            }
                            continue
                        }
                        zeroStreak = 0
                        bytes += read
                        if (bytes > HLS_SEGMENT_MAX_BYTES * 8L) {
                            throw IOException("adaptive file too large")
                        }
                        out.write(buffer, 0, read)
                        throttle.sleepIfNeeded { progress.downloaded + bytes }
                        reportHlsProgress(item, progress, bytes, total)
                    }
                } finally {
                    runCatching { input.close() }
                }
            }
            progress.downloaded += target.length()
            return target.length() > 0L
        } finally {
            untrackConnection(item.id, conn)
        }
    }

    /** Unduh audio adaptive (M4A AAC) dari URL VISIONOS ke file temp. Dipakai
     *  saat audio playlist HLS di-404 YouTube. Mengembalikan null bila URL
     *  kosong atau download gagal — caller lalu jatuh ke video-only .ts. */
    private suspend fun tryDownloadAdaptiveAudio(
        item: DownloadItem,
        adaptiveAudioUrl: String,
        tempBase: String,
        buffer: ByteArray,
        throttle: SpeedThrottle,
        progress: HlsProgress,
        saver: FileSaver
    ): File? {
        if (adaptiveAudioUrl.isBlank()) return null
        var audioM4a: File? = null
        return try {
            val f = saver.partialFile("$tempBase.audioM4a", segment = null).apply { delete() }
            audioM4a = f
            val ok = downloadAdaptiveFile(item, adaptiveAudioUrl, f, buffer, throttle, progress)
            if (!ok || f.length() < 1_000L) {
                App.logEvent("HLS: adaptive audio download failed, skipped")
                f.delete()
                return null
            }
            App.logEvent("HLS: adaptive audio downloaded (${Formats.bytes(f.length())}), remuxing...")
            f
        } catch (e: CancellationException) {
            // Berkas adaptive tidak bisa di-resume (tiap percobaan mulai
            // dari nol), jadi partial-nya dibuang juga saat batal —
            // jangan tinggalkan yatim (issue #431).
            audioM4a?.delete()
            throw e
        } catch (e: Exception) {
            audioM4a?.delete()
            App.logEvent("HLS: adaptive audio download failed (${e.message?.take(50)}), skipped")
            null
        }
    }

    /** Unduh video MP4 + audio M4A adaptive lalu remux jadi satu MP4 bersuara.
     *  Dipakai saat HLS gagal total dan URL adaptive tersedia. */
    private suspend fun downloadAdaptiveMuxed(
        item: DownloadItem,
        adaptiveVideoUrl: String,
        adaptiveAudioUrl: String,
        outName: String
    ) {
        val saver = fileSaver
        val baseName = outName.removeSuffix(".mp4").removeSuffix(".webm").ifBlank { outName }
        // Sama seperti downloadHls: temp unik per item lewat potongan id di
        // DEPAN, karena baseName tanpa ekstensi bertabrakan antar item dan
        // pembeda di ekor bisa terpotong untuk nama sangat panjang (#171).
        val tempBase = "~" + item.id.take(8) + baseName
        val videoMp4 = saver.partialFile("$tempBase.videoMp4", segment = null).apply { delete() }
        val audioM4a = saver.partialFile("$tempBase.audioM4a", segment = null).apply { delete() }
        val mp4 = saver.partialFile("$tempBase.muxed", segment = null).apply { delete() }
        val progress = HlsProgress()
        val buffer = ByteArray(BUFFER_SIZE)
        val globalLimit = StoragePrefs.speedLimitKbps(context)
        val limit = if (item.speedLimitKbps > 0) item.speedLimitKbps else globalLimit
        val throttle = if (item.speedLimitKbps > 0) {
            SpeedThrottle(limit, null)
        } else {
            SpeedThrottle(limit, globalRateLimiter())
        }
        try {
            val videoOk = downloadAdaptiveFile(item, adaptiveVideoUrl, videoMp4, buffer, throttle, progress)
            if (!videoOk) throw IOException("adaptive video download failed")
            val audioOk = downloadAdaptiveFile(item, adaptiveAudioUrl, audioM4a, buffer, throttle, progress)
            if (!audioOk) throw IOException("adaptive audio download failed")
            coroutineContext.ensureActive()
            val remuxed = HlsMp4Muxer.remuxMp4s(videoMp4, audioM4a, mp4)
            if (!remuxed) throw IOException("adaptive remux failed")
            val fileName = "$baseName.mp4"
            videoMp4.delete()
            audioM4a.delete()
            ensureItemPresent(item.id, mp4)
            val published0 = publishItem(saver, mp4, fileName, item)
            val finalName = published0.fileName ?: fileName
            verifyChecksum(item.id, published0, saver)?.let {
                deletePublished(published0)
                rollbackReplacement(item.id)
                throw IOException(it)
            }
            commitReplacement(item.id)
            val published = organizeIfEnabled(saver, published0, finalName)
            ensurePublishedKept(item.id, published0, published)
            finishHls(item, progress.downloaded, published, finalName)
        } catch (e: Exception) {
            runCatching { videoMp4.delete() }
            runCatching { audioM4a.delete() }
            runCatching { mp4.delete() }
            throw e
        }
    }

    private fun reportHlsProgress(
        item: DownloadItem,
        progress: HlsProgress,
        segNow: Long,
        total: Long = 0
    ) {
        val totalNow = progress.downloaded + segNow
        val now = monotonicNow()
        if (now - progress.lastNotify >= 1000) {
            progress.lastNotify = now
            // Total HLS asli tidak diketahui (segmen tidak punya Content-Length
            // konsisten). Persentase dihitung dari jumlah segmen (progressPercentOverride)
            // supaya bar bergerak tanpa menampilkan total palsu di UI.
            val percent = if (progress.totalSegments > 0) {
                // Fraksi segmen aktif pakai rata2 ukuran segmen selesai.
                val frac = if (progress.avgSegBytes > 0) {
                    (segNow.toDouble() / progress.avgSegBytes).coerceIn(0.0, 1.0)
                } else {
                    0.0
                }
                (((progress.segmentsDone + frac) * 100) / progress.totalSegments).toInt()
            } else {
                0
            }
            // File adaptive (video/audio MP4): Content-Length riil, total
            // kumulatif = byte yang sudah selesai + ukuran file aktif.
            val effTotal = if (total > 0) {
                progress.downloaded + total
            } else {
                // HLS segmen: pakai total pasti (Content-Length segmen selesai +
                // refSegBytes x sisa) bila sudah ada; sebelum segmen pertama
                // selesai, fallback ke estimasi BANDWIDTH x durasi supaya
                // denominator tidak nol/bergoyang sejak detik awal.
                if (progress.exactTotal > 0) progress.exactTotal else progress.estimateTotalBytes
            }
            val (speed, eta) = speedTracker.sample(item.id, totalNow, effTotal)
            val etaFromSegments = if (total == 0L && speed > 0 && effTotal > 0) {
                ((effTotal - totalNow) / speed).coerceAtLeast(0L)
            } else {
                eta
            }
            updateItem(item.id, persist = false) {
                it.copy(
                    state = DownloadState.DOWNLOADING,
                    bytesDownloaded = totalNow,
                    totalBytes = effTotal,
                    progressPercentOverride = if (total > 0) -1 else percent,
                    // Denominasi masih estimasi (BANDWIDTH x durasi) selama
                    // ukuran segmen riil (exactTotal) belum tersedia; UI memakai
                    // flag ini untuk menampilkan "~" di depan total.
                    totalBytesEstimated = total == 0L && progress.exactTotal <= 0,
                    speedBps = speed,
                    etaSeconds = etaFromSegments
                )
            }
            scheduleProgressSave()
        }
    }

    private fun finishHls(
        item: DownloadItem,
        downloaded: Long,
        published: FileSaver.PublishResult,
        finalName: String
    ) {
        speedTracker.reset(item.id)
        // Ukuran final dari berkas terbit sebenarnya bila terukur
        // (issue #348): jumlah byte segmen MPEG-TS bukan ukuran MP4
        // hasil remux (beda header MP4 dan padding TS), jadi memakai
        // angka segmen membuat ukuran tampil tidak sama dengan berkas
        // aslinya. Terbit MediaStore/SAF tanpa path lokal terukur
        // jatuh kembali ke jumlah byte segmen.
        val actualSize = published.filePath?.let { path ->
            runCatching { File(path).takeIf { it.isFile }?.length() }.getOrNull()
        }?.takeIf { it > 0 } ?: downloaded
        val finishedHls = updateItem(item.id) {
            it.copy(
                state = DownloadState.COMPLETED,
                fileName = published.fileName ?: finalName,
                bytesDownloaded = actualSize,
                totalBytes = actualSize,
                contentUri = published.contentUri,
                filePath = published.filePath,
                autoResume = false,
                speedBps = 0,
                etaSeconds = 0,
                progressPercentOverride = -1,
                totalBytesEstimated = false,
                finishedAt = System.currentTimeMillis()
            )
        } ?: item
        flushSave()
        // Cookie sesi dari unduhan HLS ikut dipersists (issue #391):
        // sebelumnya hanya jalur single-stream yang memanggil ini.
        persistCookies()
        NotificationHelper.notifyItemFinished(context, finishedHls)
    }

    /** Unduh satu segmen HLS dengan retry sekali untuk error jaringan sementara
     *  (mis. Socket closed di perangkat dengan koneksi tidak stabil). */
    private suspend fun fetchHlsSegmentWithRetry(
        item: DownloadItem,
        url: String,
        buffer: ByteArray,
        throttle: SpeedThrottle,
        committed: Long,
        spool: File,
        notify: (Long) -> Unit,
        onLength: (Long) -> Unit = {},
        fallbackQuery: String = ""
    ): Long {
        var attempts = 0
        while (true) {
            coroutineContext.ensureActive()
            try {
                return fetchHlsSegment(
                    item, url, buffer, throttle, committed, spool, notify, onLength
                )
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                val msg = e.message.orEmpty()
                val denied = msg.contains("404") || msg.contains("403")
                if (denied && fallbackQuery.isNotEmpty() && !url.contains("?")) {
                    // CDN signed-URL: segmen relatif tanpa token ditolak —
                    // coba sekali lagi dengan query token playlist varian.
                    App.logEvent("HLS: segment 404/403, retrying with playlist query")
                    return fetchHlsSegment(
                        item, url + fallbackQuery, buffer, throttle,
                        committed, spool, notify, onLength
                    )
                }
                attempts++
                if (attempts >= 2 || !isNetworkError(e.message)) throw e
            }
        }
    }

    /** Unduh satu segmen HLS dengan streaming ke berkas spool [spool]
     *  (bukan ditampung utuh di RAM — issue #371); mengembalikan jumlah
     *  byte mentah yang terunduh. Berkas tujuan akhir hanya disentuh
     *  pemanggil sesudah fungsi ini sukses penuh, jadi sifat anti-korup
     *  per segmen tetap sama seperti saat segmen ditampung di memori. */
    private suspend fun fetchHlsSegment(
        item: DownloadItem,
        url: String,
        buffer: ByteArray,
        throttle: SpeedThrottle,
        committed: Long,
        spool: File,
        notify: (Long) -> Unit,
        onLength: (Long) -> Unit = {}
    ): Long {
        val segConn = trackOpenedConnection(
            item.id,
            openAuthenticatedConnection(
                url, method = "GET",
                username = item.username,
                password = item.password,
                headers = item.headers,
                authAnchorUrl = item.url
            )
        )
        try {
            val code = segConn.responseCode
            if (code !in 200..299) throw IOException("HLS segment HTTP $code")
            // Content-Length header segmen (bila ada) = ukuran pasti segmen ini.
            val declared = segConn.contentLength.toLong().takeIf { it > 0 }
            if (declared != null) onLength(declared)
            var segBytes = 0L
            val input = segConn.inputStream
            try {
                BufferedOutputStream(FileOutputStream(spool), BUFFER_SIZE).use { segOut ->
                    var zeroStreak = 0
                    while (true) {
                        val read = input.read(buffer)
                        if (read == -1) break
                        // Guard zero-read seperti runSingle/downloadSegment
                        // (issue #389): tanpa ini stream yang mengembalikan
                        // 0 beruntun membuat loop busy-spin selamanya —
                        // jalur HLS tak punya watchdog stall.
                        if (read == 0) {
                            if (++zeroStreak >= MAX_ZERO_READS) {
                                throw IOException("HLS segment stalled (zero reads)")
                            }
                            continue
                        }
                        zeroStreak = 0
                        segBytes += read
                        if (segBytes > HLS_SEGMENT_MAX_BYTES) {
                            throw IOException("HLS segment too large")
                        }
                        segOut.write(buffer, 0, read)
                        throttle.sleepIfNeeded { committed + segBytes }
                        notify(segBytes)
                    }
                }
                // Verifikasi panjang terhadap Content-Length yang dinyatakan:
                // EOF prematur pada respons chunked bisa tiba tanpa exception
                // sehingga segmen pendek ikut tergabung menjadi hasil korup
                // berstatus COMPLETED. Lempar agar retry segmen berjalan.
                // Hanya KEKURANGAN byte yang digagalkan — kelebihan ditoleransi
                // seperti kebijakan verifySize, karena klaim Content-Length
                // bisa basi (gzip/proxy) dan stream-lah yang berkuasa
                // (issue #170).
                if (declared != null && segBytes < declared) {
                    throw IOException("HLS segment truncated: got $segBytes of $declared bytes")
                }
            } finally {
                runCatching { input.close() }
            }
            return segBytes
        } finally {
            untrackConnection(item.id, segConn)
        }
    }

    private data class HlsPlan(
        val videoSegments: List<String>,
        val videoSegmentDurationsUs: List<Long> = emptyList(),
        val audioSegments: List<String>? = null,
        /** Estimasi total byte dari BANDWIDTH master x total durasi #EXTINF.
         *  Dihitung sekali di awal supaya denominator "/≈MB" stabil sejak
         *  detik pertama (tidak menunggu segmen pertama selesai). */
        val estimateTotalBytes: Long = 0L,
        /** Query "?..." playlist varian untuk retry segmen 403/404: CDN
         *  signed-URL (mis. phncdn) menaruh token di query playlist,
         *  sedangkan segmen relatif tidak membawanya. */
        val segmentQueryFallback: String = ""
    )

    /** Query "?..." dari URL playlist untuk fallback segmen 404/403. */
    private fun playlistQuery(url: String): String {
        val q = url.substringAfter('?', "").substringBefore('#')
        return if (q.isNotEmpty()) "?$q" else ""
    }

    /** Pilih varian terbaik dari master playlist + segmen video/audio terkait. */
    private fun parseHlsPlan(body: String, baseUrl: String, preferredHeight: Int = 0, headers: String = "", preferredAudioLang: String = ""): HlsPlan? {
        // Segmen terenkripsi yang diunduh mentah = file sampah berstatus
        // COMPLETED: gagal cepat dengan pesan jelas, bukan unduh sia-sia.
        if (isEncryptedHlsPlaylist(body)) {
            throw IOException("Encrypted HLS (EXT-X-KEY) not supported")
        }
        // Playlist byterange tak dikenal pengumpul segmen (issue
        // #513): URI media yang sama akan diunduh UTUH per segmen lalu
        // ditempel berulang — berkas korup berstatus COMPLETED. Gagal
        // cepat, sejajar gerbang enkripsi.
        if (isByteRangeHlsPlaylist(body)) {
            throw IOException("Byte-range HLS (EXT-X-BYTERANGE) not supported")
        }
        if (!body.contains("#EXT-X-STREAM-INF")) {
            // Media playlist langsung (bukan master) — tanpa audio terpisah.
            // Satu lintasan: segmen + durasi #EXTINF dibaca sekaligus agar
            // probe 1 MB tak membayar split/chaining ganda (lihat HlsParser).
            val direct = HlsParser.parseDirectMedia(body, baseUrl)
            val segments = direct.segments
            App.logEvent("HLS: direct media playlist, ${segments.size} segments")
            if (segments.isEmpty()) return null
            if (!body.contains("#EXT-X-ENDLIST")) {
                throw IOException("Live HLS not supported (playlist without EXT-X-ENDLIST)")
            }
            // Cek enkripsi kedua di sini dulu ada dan tak terjangkau
            // (kode mati, issue #423): gerbang di pintu masuk fungsi
            // sudah melempar untuk body yang sama.
            val durations = direct.durationsUs
            return HlsPlan(
                segments, videoSegmentDurationsUs = durations,
                estimateTotalBytes = estimateBytes(durations, 0L),
                segmentQueryFallback = playlistQuery(baseUrl)
            )
        }
        var variants = HlsParser.parseMaster(body, baseUrl)
        // Fallback longgar (sudah terurut): bila parseMaster gagal (mis. format
        // YouTube 2026 yang memformat #EXT-X-STREAM-INF berbeda), pakai hasil
        // parseMasterLenient dari HlsParser (satu-satunya pemilik regex varian).
        if (variants.isNullOrEmpty()) {
            val fallbackVariants = HlsParser.parseMasterLenient(body, baseUrl)
            if (fallbackVariants != null) {
                App.logEvent("HLS: parseMaster failed, fallback found ${fallbackVariants.size} variants")
                variants = fallbackVariants
            } else {
                App.logEvent("HLS: parseMaster failed, no fallback variants found, dumping first 500 chars")
                App.logEvent("HLS: body[0..500]=${body.take(500).replace("\n","|").replace("\r","")}")
                return null
            }
        }
        // Pemilihan varian nyata dilakukan daftar candidates di bawah.
        // Perhitungan calon tunggal sebelumnya dibuang tanpa dipakai
        // (issue #363); yang dipertahankan hanya penjaga daftar kosong,
        // termasuk hasil fallback yang boleh berupa daftar kosong.
        if (variants.isEmpty()) return null
        // Urutkan varian dari kualitas tertinggi ke terendah untuk fallback.
        // YouTube kadang memblokir media playlist varian tertinggi (404) tapi
        // membiarkan varian rendah lewat. Coba beberapa varian sampai ada yang berhasil.
        val candidates = if (preferredHeight > 0) {
            // Saat user pilih resolusi: urutkan dari paling dekat target ke terjauh.
            // AVC didahulukan, TETAPI saringan kerasnya tak boleh mengosongkan
            // daftar (issue #386): varian HEVC/VP9/AV1 saja — atau CODECS
            // kosong, umum pada master html5player — sebelumnya membuat
            // unduhan gagal total padahal varian unduhable ada. Bila tak ada
            // AVC, semua varian ikut diperingkat menurut kedekatan resolusi.
            val avc = variants.filter { it.codecs.contains("avc") }
            (if (avc.isNotEmpty()) avc else variants)
                .sortedBy { kotlin.math.abs(it.height - preferredHeight) }
        } else {
            // Default: urutkan dari bandwidth tertinggi ke terendah
            variants.sortedByDescending { it.bandwidth }
        }
        val audioRenditions = HlsParser.parseAudioRenditions(body, baseUrl)
        // Alasan fatal (live/terenkripsi) dicatat tapi tak menghentikan loop:
        // varian berikut masih dicoba; bila semua gagal, alasan jelas
        // dilempar ulang (bukan "No HLS segments found" yang samar).
        var fatalError: IOException? = null
        for (candidate in candidates) {
            val videoSegs = try {
                mediaSegmentsWithDurations(candidate.url, headers, baseUrl)
            } catch (e: IOException) {
                if (fatalError == null) fatalError = e
                null
            }
            if (videoSegs == null) {
                continue
            }
            val videoSegments = videoSegs.map { it.first }
            val videoDurations = videoSegs.map { it.second }
            val audioSegments = candidate.audioGroupId?.let { group ->
                val groupRenditions = audioRenditions.filter { it.groupId == group }
                // Jika user memilih bahasa audio spesifik, prioritaskan rendition
                // yang LANGUAGE-nya cocok; sisanya fallback setelahnya.
                val isDubbed = { r: HlsRendition ->
                    r.name.contains("dub", ignoreCase = true) ||
                    r.name.contains("dubbed", ignoreCase = true) ||
                    r.name.contains("dubbing", ignoreCase = true)
                }
                val ordered = if (preferredAudioLang.isNotBlank()) {
                    groupRenditions.filter { it.language.equals(preferredAudioLang, ignoreCase = true) } +
                        groupRenditions.filter { !it.language.equals(preferredAudioLang, ignoreCase = true) }
                } else {
                    // Prioritas default: (1) default + bukan dubbing, (2) default saja,
                    // (3) non-default + bukan dubbing, (4) sisanya.
                    groupRenditions.filter { it.isDefault && !isDubbed(it) } +
                        groupRenditions.filter { it.isDefault && isDubbed(it) } +
                        groupRenditions.filter { !it.isDefault && !isDubbed(it) } +
                        groupRenditions.filter { !it.isDefault && isDubbed(it) }
                }
                var audioResult: List<String>? = null
                for (rendition in ordered) {
                    val segs = runCatching { mediaSegments(rendition.url, headers, baseUrl) }.getOrNull()
                    if (!segs.isNullOrEmpty()) {
                        audioResult = segs
                        break
                    }
                }
                audioResult
            }
            App.logEvent(
                "HLS plan: ${candidate.codecs} ${candidate.bandwidth/1000}kbps ${candidate.frameRate}fps, " +
                    "${videoSegments.size} video, ${audioSegments?.size ?: 0} audio segments"
            )
            val audioBytes = if (audioSegments != null) {
                estimateAudioBytes(audioSegments.size, videoDurations)
            } else 0L
            return HlsPlan(
                videoSegments, videoDurations, audioSegments,
                estimateTotalBytes = saturatingAdd(estimateBytes(videoDurations, candidate.bandwidth), audioBytes),
                segmentQueryFallback = playlistQuery(candidate.url)
            )
        }
        fatalError?.let { throw it }
        return null
    }

    /** Parse segmen dari media playlist, mengembalikan (url, durasi_us). */
    private fun mediaSegmentsWithDurations(
        playlistUrl: String,
        headers: String = "",
        authAnchorUrl: String = playlistUrl
    ): List<Pair<String, Long>>? {
        // Ambil media playlist; gagal HTTP/exception = null agar kandidat berikut dicoba.
        val body: String? = try {
            val conn = openAuthenticatedConnection(
                playlistUrl, method = "GET", username = "", password = "", headers = headers,
                authAnchorUrl = authAnchorUrl
            )
            try {
                val code = conn.responseCode
                if (code !in 200..299) {
                    return null
                }
                val b = readBounded(conn.inputStream, HLS_PROBE_MAX_BYTES)
                b
            } finally { conn.disconnect() }
        } catch (_: Exception) { null }
        if (body == null) return null
        // Playlist live (event/DVR sliding window) tidak punya EXT-X-ENDLIST:
        // gagal cepat dengan pesan jelas, bukan mengunduh snapshot window
        // yang tak berujung (dan mengulang dari nol tiap retry HLS).
        if (!body.contains("#EXT-X-ENDLIST")) {
            throw IOException("Live HLS not supported (playlist without EXT-X-ENDLIST)")
        }
        if (isEncryptedHlsPlaylist(body)) {
            throw IOException("Encrypted HLS (EXT-X-KEY) not supported")
        }
        if (isByteRangeHlsPlaylist(body)) {
            throw IOException("Byte-range HLS (EXT-X-BYTERANGE) not supported")
        }
        val result = mutableListOf<Pair<String, Long>>()
        val lines = body.lines()
        var i = 0
        while (i < lines.size) {
            val line = lines[i].trim()
            if (line.startsWith("#EXTINF:")) {
                // Format: #EXTINF:<durasi>, atau #EXTINF:<durasi>, ...
                val dur = line.substringAfter(":").substringBefore(",").trim()
                    .toDoubleOrNull() ?: 0.0
                // URI segmen adalah baris non-kosong non-tag BERIKUTNYA
                // (issue #387): asumsi lama "tepat di baris sesudahnya"
                // membuat baris kosong/tag di tengah (mis.
                // #EXT-X-PROGRAM-DATE-TIME dari sebagian CDN) melewatkan
                // segmen asli — dan untuk baris kosong, resolveUrl
                // mengembalikan URL playlist itu sendiri sehingga playlist
                // terunduh sebagai segmen. Durasi hanya dicatat berpasangan
                // dengan URI yang benar-benar ditemukan.
                var j = i + 1
                while (j < lines.size) {
                    val next = lines[j].trim()
                    if (next.isNotEmpty() && !next.startsWith("#")) break
                    j++
                }
                if (j < lines.size) {
                    val url = HlsParser.resolveUrl(playlistUrl, lines[j].trim())
                    // Predikat bersama HlsParser.isHttpUrl (issue #477):
                    // awalan "http" saja menerima skema mirip-http
                    // tanpa "//" (http:seg.ts, httpx://) yang lolos
                    // pass-through resolveUrl sebagai segmen gadungan.
                    if (HlsParser.isHttpUrl(url)) {
                        result.add(url to hlsSegmentDurationUs(dur))
                    }
                    i = j + 1; continue
                }
                i = j; continue
            }
            i++
        }
        return if (result.isEmpty()) null else result
    }

    private fun mediaSegments(
        playlistUrl: String,
        headers: String = "",
        authAnchorUrl: String = playlistUrl
    ): List<String>? =
        mediaSegmentsWithDurations(playlistUrl, headers, authAnchorUrl)?.map { it.first }

    /** Estimasi byte video dari BANDWIDTH (bit/detik) x total durasi #EXTINF.
     *  Sumber: master playlist — tersedia sebelum segmen diunduh. */
    private fun estimateBytes(durationsUs: List<Long>, bandwidth: Long): Long {
        val totalUs = durationsUs.sum()
        if (totalUs <= 0 || bandwidth <= 0) return 0L
        // Kali-dulu-bagi-kemudian meluap bila BANDWIDTH raksasa (nilai
        // playlist tak-terpercaya) atau video sangat panjang: estimasi negatif
        // meracuni total + cek storage pra-unduh lolos palsu.
        return safeMulDiv(totalUs, bandwidth, 8_000_000L)
    }

    /** Estimasi audio rendition terpisah (YouTube HLS): ukuran pastinya baru
     *  diketahui setelah playlist audio di-fetch. Pendekatan awal memakai
     *  bitrate AAC umum 128 kbps x durasi video (durasi audio ~= video).
     *  0 bila data tidak cukup. */
    private fun estimateAudioBytes(audioSegmentCount: Int, videoDurationsUs: List<Long>): Long {
        val totalUs = videoDurationsUs.sum()
        if (totalUs <= 0 || audioSegmentCount <= 0) return 0L
        // 128 kbps AAC, bit -> byte (÷8); bagi-dulu agar video
        // >~20 jam tak meluap seperti estimateBytes.
        return safeMulDiv(totalUs, 128_000L, 8_000_000L)
    }

    private fun fetchText(url: String, headers: String, maxBytes: Int): String? {
        return runCatching {
            val conn = openAuthenticatedConnection(
                url, method = "GET", username = "", password = "", headers = headers
            )
            try {
                val code = conn.responseCode
                if (code !in 200..299) return null
                readBounded(conn.inputStream, maxBytes)
            } finally {
                conn.disconnect()
            }
        }.getOrNull()
    }

    /** Batalkan finalize bila item sudah hilang dari antrean (cancel/remove/
     *  clear tepat di tengah merge/remux/publish yang blocking sehingga tak
     *  bisa diinterupsi): tanpa ini file tetap terbit yatim — ada di folder
     *  tujuan tapi tanpa entri daftar, tanpa notifikasi, dan tak terkelola.
     *  Scrap ikut dibuang langsung agar tak jadi yatim di downloadDir. */
    private suspend fun ensureItemPresent(id: String, vararg scrap: File) {
        coroutineContext.ensureActive()
        if (_items.value.none { it.id == id }) {
            scrap.forEach { runCatching { it.delete() } }
            throw CancellationException("item $id removed during finalize")
        }
    }

    /** Buang hasil publish yang yatim bila item hilang tepat di jendela
     *  publish/organize yang blocking (cancel/remove sudah lewat sehingga
     *  deleteFiles tak melihat file final): tanpa ini file tetap mendarat di
     *  folder tujuan tanpa entri daftar dan tak terkelola. */
    private suspend fun ensurePublishedKept(id: String, vararg published: FileSaver.PublishResult) {
        coroutineContext.ensureActive()
        if (_items.value.none { it.id == id }) {
            published.forEach { deletePublished(it) }
            throw CancellationException("item $id removed after publish")
        }
    }

    private suspend fun runSingle(
        item: DownloadItem,
        conn: HttpURLConnection,
        saver: FileSaver,
        throttle: SpeedThrottle
    ) {
        var fileName = item.fileName
        var partialFile = saver.partialFile(fileName)
        val health = DownloadHealthWatchdog(
            if (item.speedLimitKbps > 0) item.speedLimitKbps else StoragePrefs.speedLimitKbps(context)
        )
        // Resume anti-korup: selaraskan file parsial dengan catatan bytes
        // (pangkas bila file lebih maju, buang bila data hilang). Idempoten:
        // pemanggil runDownload sudah memanggilnya dulu untuk preset Range.
        var downloaded = prepareResumeOffset(item, saver)
        coroutineContext.ensureActive()
        trackConnection(item.id, conn)
        try {
        // JANGAN setRequestProperty di sini: koneksi dari
        // openAuthenticatedConnection sudah connected (responseCode dibaca
        // untuk deteksi redirect) dan itu melempar IllegalStateException.
        // Header Range resume sudah di-preset sebelum connect.
        conn.connect()

        val code = conn.responseCode
        if (code !in 200..299) throw IOException("HTTP $code")
        captureHeaderChecksum(item, conn)

        val resolvedName = FileNames.unique(resolveFinalName(item, headersOf(conn))) { n ->
            _items.value.any { it.id != item.id && it.fileName == n }
        }
        if (resolvedName != fileName) {
            val newPartial = saver.partialFile(resolvedName)
            val keepOld = downloaded > 0 && partialFile.exists()
            // Rename gagal sementara byte lama ada: pertahankan nama lama agar
            // byte tetap nyambung ke file yang benar (tanpa ini: file lama
            // yatim + retry mengunduh ulang penuh dari nol).
            if (keepOld && !partialFile.renameTo(newPartial)) {
                App.logEvent("DOWNLOAD ${item.fileName}: keeping partial name (rename failed)")
            } else {
                if (!keepOld) partialFile.delete()
                partialFile = newPartial
                fileName = resolvedName
                updateItem(item.id) { it.copy(fileName = fileName) }
            }
        }

        val lengthHeader = contentLength(conn)
        var total = if (lengthHeader > 0) lengthHeader else 0L
        if (code == 206) {
            // Verifikasi server benar-benar melanjutkan dari posisi kita.
            val cr = conn.getHeaderField("Content-Range")
            val actualStart = cr?.substringAfter("bytes ")?.substringBefore("-")?.trim()?.toLongOrNull()
            if (cr != null && actualStart == null) {
                throw IOException("Malformed Content-Range: $cr")
            }
            if (actualStart != null && actualStart != downloaded) {
                throw IOException("Server resumed from byte $actualStart, not $downloaded")
            }
            val crTotal = cr?.substringAfter('/')?.trim()?.toLongOrNull()?.takeIf { it > 0 }
            total = crTotal ?: (total + downloaded)
        } else if (downloaded > 0) {
            // Server tidak mendukung resume (minta lanjut, dikirim dari awal):
            // progres terpaksa dibuang — catat eksplisit agar tidak terlihat
            // seperti "download ulang misterius" di log.
            App.logEvent(
                "DOWNLOAD ${item.fileName}: server does not support resume " +
                    "(HTTP $code, ignoring Range) — restarting from zero, " +
                    "${Formats.bytes(downloaded)} discarded"
            )
            downloaded = 0
            partialFile.writeBytes(ByteArray(0))
            // throttle.reset(downloaded) di bawah memakai nilai 0 ini,
            // jadi baseline batas kecepatan ikut benar setelah fallback.
        }
        // Resume 206: byte terunduh sudah di disk, jadi yang dibutuhkan hanya
        // sisanya (total penuh menolak resume besar yang sebenarnya muat).
        val needBytes = if (code == 206) (total - downloaded).coerceAtLeast(0) else total
        if (needBytes > 0 && saver.destinationFreeBytes(item.folderPath) < needBytes) {
            throw IOException(
                "Not enough storage: need ${Formats.bytes(needBytes)}, " +
                    "available ${Formats.bytes(saver.destinationFreeBytes(item.folderPath))}"
            )
        }

        throttle.reset(downloaded)
        val input = conn.inputStream
        val output = BufferedOutputStream(FileOutputStream(partialFile, true), BUFFER_SIZE)
        val buffer = ByteArray(BUFFER_SIZE)
        var lastNotify = 0L
        var iters = 0
        var zeroStreak = 0
        try {
            while (true) {
                val read = input.read(buffer)
                if (read == -1) break
                // Guard zero-read seperti downloadSegment di atas.
                if (read == 0) {
                    if (++zeroStreak >= MAX_ZERO_READS) {
                        throw IOException("Connection stalled (zero reads) — retrying")
                    }
                    continue
                }
                zeroStreak = 0
                output.write(buffer, 0, read)
                downloaded += read
                throttle.sleepIfNeeded { downloaded }
                // Cek jam tiap 8 chunk (512KB): baca jam per iterasi boros.
                if ((iters++ and 7) != 0) continue
                val now = monotonicNow()
                // Progres di-throttle 1x/detik: salinan daftar + emisi StateFlow
                // (ke UI, notifikasi, SSE) tidak perlu 2x/detik — hemat CPU/GC
                // saat banyak download paralel, UI tetap terasa halus.
                if (now - lastNotify >= 1000) {
                    lastNotify = now
                    coroutineContext.ensureActive()
                    val (speed, eta) = speedTracker.sample(item.id, downloaded, total)
                    health.check(now, downloaded, total, speed)
                    // Progress tick: updateItem buat copy baru supaya StateFlow
                    // emit ke UI & SSE (in-place mutation tanpa copy tidak
                    // trigger emission karena StateFlow pakai equals-based dedup).
                    updateItem(item.id, persist = false) {
                        it.copy(
                            state = DownloadState.DOWNLOADING,
                            bytesDownloaded = downloaded,
                            totalBytes = total,
                            speedBps = speed,
                            etaSeconds = eta
                        )
                    }
                }
            }
            output.flush()
            coroutineContext.ensureActive()
        } finally {
            runCatching { input.close() }
            runCatching { output.close() }
        }

        ensureItemPresent(item.id, partialFile)
        verifySize(downloaded, total, fileName)

        val published0 = publishItem(saver, partialFile, fileName, item)
        val finalName = published0.fileName ?: fileName
        verifyChecksum(item.id, published0, saver)?.let {
            deletePublished(published0)
            rollbackReplacement(item.id)
            throw IOException(it)
        }
        commitReplacement(item.id)
        val published = organizeIfEnabled(saver, published0, finalName)
        ensurePublishedKept(item.id, published0, published)
        speedTracker.reset(item.id)
        val serverEtag = conn.getHeaderField("ETag").orEmpty()
        val finishedSingle = updateItem(item.id) {
            it.copy(
                state = DownloadState.COMPLETED,
                fileName = published.fileName ?: finalName,
                bytesDownloaded = downloaded,
                totalBytes = if (total > 0) total else downloaded,
                contentUri = published.contentUri,
                filePath = published.filePath,
                autoResume = false,
                speedBps = 0,
                etaSeconds = 0,
                etag = serverEtag,
                finishedAt = System.currentTimeMillis()
            )
        } ?: item
        flushSave()
        persistCookies()
        App.logEvent("DOWNLOAD COMPLETED: $finalName (${Formats.bytes(downloaded)})")
        NotificationHelper.notifyItemFinished(context, finishedSingle)
        } catch (e: IOException) {
            if (!coroutineContext.isActive) throw CancellationException()
            throw e
        } finally {
            untrackConnection(item.id, conn)
        }
    }

    private suspend fun runSegmented(
        item: DownloadItem,
        saver: FileSaver,
        throttle: SpeedThrottle,
        total: Long,
        headers: ServerHeaders?
    ) {
        coroutineContext.ensureActive()
        var fileName = item.fileName
        var segments = item.segments
        if (segments.isEmpty()) {
            val resolvedName = FileNames.unique(resolveFinalName(item, headers)) { n ->
                _items.value.any { it.id != item.id && it.fileName == n }
            }
            fileName = resolvedName
            segments = createSegments(total)
            if (total > 0 && saver.destinationFreeBytes(item.folderPath) < total) {
                throw IOException(
                    "Not enough storage: need ${Formats.bytes(total)}, " +
                        "available ${Formats.bytes(saver.destinationFreeBytes(item.folderPath))}"
                )
            }
            updateItem(item.id) {
                it.copy(
                    fileName = fileName,
                    totalBytes = total,
                    segments = segments,
                    bytesDownloaded = 0,
                    speedBps = 0,
                    etaSeconds = 0
                )
            }
        }

        // Sisa penyangga/flush dari percobaan sebelumnya dibuang: segmen baru
        // menulis ulang dari state item saat ini (nilai basi tidak boleh
        // menimpa progres percobaan baru).
        clearSegProgress(item.id)
        val initialDone = segments.sumOf { it.downloaded }
        resetThrottleTotal(item.id, initialDone)
        throttle.reset(initialDone)
        try {
            coroutineScope {
                segments.forEach { seg ->
                    launch {
                        downloadSegment(item.id, fileName, seg, saver, throttle)
                    }
                }
            }
        } catch (e: IOException) {
            val current = _items.value.find { it.id == item.id }
            if (isRangeRejectError(e.message)) {
                val keptBytes = (current?.segments ?: item.segments).sumOf { it.downloaded }
                if (shouldKeepPartialOnRangeReject(keptBytes)) {
                    // URL ini sebelumnya MELAYANI Range (progres sudah masuk)
                    // lalu tiba-tiba menolak — ciri khas signed CDN URL yang
                    // kedaluwarsa di tengah jalan (kasus nyata: xvideos-cdn77,
                    // 60,8/61,1 MB masuk lalu size-mismatch → retry 6 detik
                    // kemudian kena HTTP 200). Coba re-extract URL segar lalu
                    // resume dari progres yang ada.
                    rememberFailedUrl(item.url)
                    val fresh = refreshExpiredCdnUrl(item.id)
                    if (fresh != null) {
                        App.logEvent(
                            "DOWNLOAD ${item.fileName}: link expired, refreshed URL — " +
                                "resuming from ${Formats.bytes(keptBytes)}"
                        )
                        updateItem(item.id) { it.copy(url = fresh) }
                        // Pesan ini SENGAJA tidak match isRangeRejectError agar
                        // handleFailure memberi retry ke URL segar, bukan FAILED.
                        throw IOException(
                            "Server stopped supporting Range (link may have expired) — " +
                                "retrying resume from ${Formats.bytes(keptBytes)}"
                        )
                    }
                    // Tidak ada URL segar (bukan link kedaluwarsa atau re-extract
                    // menghasilkan URL yang sama, mis. CDN transien HTTP 501):
                    // mengulang Range hanya gagal lagi, jadi jatuh ke fallback
                    // satu-alur di bawah sebagai upaya terakhir — ekor 99%
                    // tetap selesai walau harus unduh ulang, bukan FAILED.
                    App.logEvent(
                        "DOWNLOAD ${item.fileName}: Range rejected after " +
                            "${Formats.bytes(keptBytes)} (${e.message}) — " +
                            "no fresh URL, re-downloading in one pass"
                    )
                } else {
                    // Tanpa progres sama sekali = server memang tak dukung Range
                    // (mis. proxy transparan ISP): semua percobaan Range akan gagal
                    // selamanya, jadi buang segmen lalu unduh sekali jalan tanpa
                    // Range (partial lama ikut dibuang).
                    rememberFailedUrl(item.url)
                    App.logEvent(
                        "DOWNLOAD ${item.fileName}: Range rejected (${e.message}), " +
                            "downloading again in one pass"
                    )
                }
                saver.partialFiles(current ?: item).forEach { runCatching { it.delete() } }
                updateItem(item.id) {
                    it.copy(
                        segments = emptyList(),
                        bytesDownloaded = 0,
                        speedBps = 0,
                        etaSeconds = 0
                    )
                }
                clearSegProgress(item.id)
                // Wajib lewat koneksi terautentikasi: redirect manual dengan
                // strip kredensial beda-origin + blokir SSRF (seperti jalur
                // utama). openConn mentah mengikuti redirect otomatis sistem
                // dan mengirim ulang Authorization/Cookie ke host asing.
                // UA/Accept/Referer/YouTube-Instagram sudah dipasang di dalam.
                val fallbackConn = openAuthenticatedConnection(
                    item.url, method = "GET",
                    username = item.username, password = item.password, headers = item.headers
                )
                try {
                    // JANGAN set header/timeout di sini: koneksi sudah connected
                    // (setRequestProperty melempar IllegalStateException). Auth,
                    // cookie (via CookieHandler global), timeout, dan UA sudah
                    // dipasang di dalam openAuthenticatedConnection; kredensial
                    // juga sengaja TIDAK dipaksa ulang agar tidak bocor ke host
                    // redirect beda-origin.
                    runSingle(item, fallbackConn, saver, throttle)
                } finally {
                    fallbackConn.disconnect()
                }
                return
            }
            throw e
        }

        ensureItemPresent(item.id)
        val current = _items.value.find { it.id == item.id } ?: return
        verifySize(current.bytesDownloaded, current.totalBytes, current.fileName)

        val merged = saver.mergeSegments(fileName, segments.size)
        ensureItemPresent(item.id, merged)
        val published0 = publishItem(saver, merged, fileName, item)
        val finalName = published0.fileName ?: fileName
        verifyChecksum(item.id, published0, saver)?.let {
            deletePublished(published0)
            rollbackReplacement(item.id)
            throw IOException(it)
        }
        commitReplacement(item.id)
        val published = organizeIfEnabled(saver, published0, finalName)
        ensurePublishedKept(item.id, published0, published)
        speedTracker.reset(item.id)
        val finishedMulti = updateItem(item.id) {
            it.copy(
                state = DownloadState.COMPLETED,
                fileName = published.fileName ?: finalName,
                bytesDownloaded = current.bytesDownloaded,
                totalBytes = current.totalBytes,
                contentUri = published.contentUri,
                filePath = published.filePath,
                segments = emptyList(),
                autoResume = false,
                speedBps = 0,
                etaSeconds = 0,
                etag = headers?.etag.orEmpty(),
                finishedAt = System.currentTimeMillis()
            )
        } ?: item
        flushSave()
        // Cookie sesi dari unduhan multi-segmen ikut dipersists (issue #391).
        persistCookies()
        clearSegProgress(item.id)
        App.logEvent("DOWNLOAD COMPLETED: $finalName (${Formats.bytes(current.bytesDownloaded)})")
        NotificationHelper.notifyItemFinished(context, finishedMulti)
    }

    /** Minta URL CDN segar saat link kedaluwarsa di tengah unduhan:
     *  re-extract dari halaman sosial asli (disimpan saat ekstraksi pertama),
     *  lalu validasi ukuran via HEAD supaya resume segmen tidak menggabung
     *  file beda. Kembalikan URL baru bila layak resume, else null. */
    private suspend fun refreshExpiredCdnUrl(id: String): String? {
        val item = _items.value.find { it.id == id } ?: return null
        val original = originalSocialUrls[id] ?: return null
        coroutineContext.ensureActive()
        val result = runCatching { SocialMediaExtractor.extract(original, item.headers) }.getOrNull()
            ?: return null
        if (result.directUrl == item.url || result.isHls) return null
        if (item.totalBytes > 0) {
            // Validasi HEAD tak boleh melempar: openAuthenticatedConnection
            // ikut menyambung + membaca responseCode saat redirect, sehingga
            // blip jaringan di sini harus berarti "tak ada URL segar" (jatuh
            // ke fallback satu-alur di pemanggil), bukan meledak keluar dan
            // melewatkan fallback lalu retry-Range berulang sampai FAILED.
            val probe = runCatching {
                openAuthenticatedConnection(
                    result.directUrl, method = "HEAD",
                    username = item.username, password = item.password, headers = item.headers
                )
            }.getOrNull() ?: return null
            try {
                if (probe.responseCode !in 200..299) return null
                if (!isFreshResumeSizeValid(item.totalBytes, contentLength(probe))) return null
            } catch (_: Exception) {
                return null
            } finally {
                probe.disconnect()
            }
        }
        return result.directUrl
    }

    private suspend fun downloadSegment(
        id: String,
        fileName: String,
        segment: DownloadSegment,
        saver: FileSaver,
        throttle: SpeedThrottle
    ) {
        val item = _items.value.find { it.id == id } ?: return
        val partial = saver.partialFile(fileName, segment.index)
        val health = DownloadHealthWatchdog(
            if (item.speedLimitKbps > 0) item.speedLimitKbps else StoragePrefs.speedLimitKbps(context)
        )
        var downloaded = segment.downloaded
        var lastSegBytes = downloaded
        var lastSegAt = monotonicNow()
        // Resume anti-korup per segmen: ukuran file parsial harus sinkron.
        // File sedikit lebih panjang dari catatan (flush progres tiap 500 ms)
        // hanya dipangkas, bukan dibuang — tanpa ini, tiap putus jaringan
        // membuat SEMUA segmen mengulang dari nol.
        when (resumeAction(downloaded, partial.length())) {
            ResumeAction.TRUNCATE_TO_RECORD -> {
                val truncated = runCatching {
                    RandomAccessFile(partial, "rw").use { it.setLength(downloaded) }
                    true
                }.getOrDefault(false)
                if (!truncated) {
                    addThrottleTotal(id, -downloaded)
                    downloaded = 0
                    partial.delete()
                    updateSegment(id, segment.index, 0)
                }
            }
            ResumeAction.RESTART -> {
                addThrottleTotal(id, -downloaded)
                downloaded = 0
                partial.delete()
                updateSegment(id, segment.index, 0)
            }
            ResumeAction.KEEP -> Unit
        }
        coroutineContext.ensureActive()
        val conn = openAuthenticatedConnection(
            item.url, method = "GET",
            username = item.username,
            password = item.password,
            headers = item.headers
        ) { connection, _, _ ->
            connection.setRequestProperty("Range", "bytes=${segment.start + downloaded}-${segment.end}")
        }
        trackOpenedConnection(id, conn)
        try {
            val code = conn.responseCode
            if (code != 206) throw IOException("Server does not support Range (HTTP $code)")
            // Verifikasi server melanjutkan dari offset segmen yang benar
            // (seperti runSingle): respons 206 tanpa Content-Range dibiarkan,
            // tapi offset salah berarti byte korup bila diteruskan.
            val segStart = segment.start + downloaded
            val segCr = conn.getHeaderField("Content-Range")
            if (segCr != null) {
                val segActual = segCr.substringAfter("bytes ").substringBefore("-").trim().toLongOrNull()
                if (segActual == null) throw IOException("Malformed Content-Range: $segCr")
                if (segActual != segStart) {
                    throw IOException("Segment ${segment.index} resumed from byte $segActual, not $segStart")
                }
            }
            // Ujung respons tak boleh melebihi yang diminta: server yang
            // mengirim di luar Range (body lebih panjang) akan korup bila
            // diserap — loop tulis di bawah dibatasi budget yang sama.
            // Ujung lebih kecil ditoleransi (kekurangan tertangkap cek
            // incomplete lalu retry mengisi sisa).
            val segEnd = segCr?.substringAfter("-")?.substringBefore("/")?.substringBefore(";")
                ?.trim()?.toLongOrNull()
            if (!isSegmentEndAllowed(segEnd, segment.end)) {
                throw IOException("Segment ${segment.index} returned range ending at $segEnd, not ${segment.end}")
            }

            val input = conn.inputStream
            val output = BufferedOutputStream(FileOutputStream(partial, true), BUFFER_SIZE)
            val buffer = ByteArray(BUFFER_SIZE)
            var lastNotify = 0L
            var segIters = 0
            var zeroStreak = 0
            // Sisa byte yang boleh ditulis untuk segmen ini; server nakal yang
            // mengabaikan batas akhir Range tak boleh menumpuk byte ekstra ke
            // file parsial (merge korup lolos verifySize yang membolehkan overrun).
            var budget = (segment.end - segment.start + 1) - downloaded
            try {
                while (true) {
                    val read = input.read(buffer)
                    if (read == -1) break
                    // Kontrak InputStream membolehkan read() = 0 (bukan EOF):
                    // tanpa guard, socket macet membuat loop busy-spin CPU
                    // sampai watchdog 30 dtk. Gagal cepat agar retry/mirror
                    // segera jalan (pola MAX_ZERO_READS di Streams/HttpBody).
                    if (read == 0) {
                        if (++zeroStreak >= MAX_ZERO_READS) {
                            throw IOException("Segment ${segment.index} connection stalled (zero reads)")
                        }
                        continue
                    }
                    zeroStreak = 0
                    val capped = cappedSegmentWrite(read, budget)
                    if (capped < read) {
                        throw IOException("Segment ${segment.index} exceeded requested range")
                    }
                    output.write(buffer, 0, capped)
                    downloaded += capped
                    budget -= capped
                    val sharedTotal = addThrottleTotal(id, capped.toLong())
                    throttle.sleepIfNeeded { sharedTotal }
                    if ((segIters++ and 7) != 0) continue
                    val now = monotonicNow()
                    if (now - lastNotify >= 1000) {
                        lastNotify = now
                        coroutineContext.ensureActive()
                        val segTotal = segment.end - segment.start + 1
                        val speed = if (now > lastSegAt) {
                            ((downloaded - lastSegBytes) * 1000L) / (now - lastSegAt)
                        } else 0L
                        lastSegBytes = downloaded
                        lastSegAt = now
                        health.check(now, downloaded, segTotal, speed)
                        // Progres ke penyangga; updateItem nyata dilakukan flush.
                        recordSegmentProgress(id, segment.index, downloaded, item.segments.size)
                    }
                }
                output.flush()
                coroutineContext.ensureActive()
            } finally {
                runCatching { input.close() }
                runCatching { output.close() }
            }
            if (downloaded < (segment.end - segment.start + 1)) {
                throw IOException("Segment ${segment.index} incomplete")
            }
            updateSegment(id, segment.index, downloaded)
        } catch (e: IOException) {
            if (!coroutineContext.isActive) throw CancellationException()
            throw e
        } finally {
            untrackConnection(id, conn)
        }
    }

    @Synchronized
    private fun updateSegment(id: String, index: Int, downloaded: Long) {
        updateItem(id, persist = false) { item ->
            val segs = item.segments.map { if (it.index == index) it.copy(downloaded = downloaded) else it }
            val totalDone = segs.sumOf { it.downloaded }
            // Sampel kecepatan/ETA maks 1x/detik (EMA + format ETA mahal bila
            // N item aktif x flush 500ms); byte/segmen tetap update tiap flush.
            val nowMs = monotonicNow()
            val prev = lastSpeedSample[id]
            val (speed, eta) = if (prev != null && nowMs - prev.first < 1000) {
                prev.second
            } else {
                val sampled = speedTracker.sample(id, totalDone, item.totalBytes)
                lastSpeedSample[id] = nowMs to sampled
                sampled
            }
            item.copy(
                segments = segs,
                bytesDownloaded = totalDone,
                speedBps = speed,
                etaSeconds = eta
            )
        }
        scheduleProgressSave()
    }

    /** Tulis progres segmen ke penyangga (tanpa emisi StateFlow). Setiap indeks
     *  hanya ditulis oleh segmen yang sama, jadi aman tanpa kunci tambahan. */
    // segCount diteruskan pemanggil agar hot-path tiap detik tidak memindai
    // _items O(n) hanya untuk tahu ukuran array.
    private fun recordSegmentProgress(id: String, index: Int, downloaded: Long, segCount: Int) {
        if (segCount <= 0 || index < 0 || index >= segCount) return
        // Pembuatan/penggantian array wajib atomik di bawah satu lock yang sama
        // dengan clearSegProgress/flush: tanpa ini remove paralel bisa yatim
        // (tulis ke array yang sudah dibuang) dan baca flush bisa basi
        // (elemen LongArray bukan volatile). Ukuran beda = percobaan baru,
        // ganti array agar indeks tak terbuang diam-diam.
        val arr = synchronized(segProgress) {
            val existing = segProgress[id]
            if (existing == null || existing.length() != segCount) {
                val fresh = java.util.concurrent.atomic.AtomicLongArray(segCount)
                for (i in 0 until segCount) fresh.set(i, -1L)
                segProgress[id] = fresh
                fresh
            } else {
                existing
            }
        }
        if (index >= arr.length()) return
        arr.set(index, downloaded)
        scheduleSegFlush(id)
    }

    /** Jadwalkan penggabungan progres; paling banyak SATU job flush per item
     *  (tick 1 dtk dari banyak segmen tidak menumpuk job). */
    private fun scheduleSegFlush(id: String) {
        if (segFlushJobs.containsKey(id)) return
        // Kunci sempit pada map (bukan synchronized(this)): lock engine global
        // dipakai semua @Synchronized (updateItem/flush) sehingga tiap tick
        // segmen memblokir seluruh engine dan bikin jank saat banyak unduhan.
        synchronized(segFlushJobs) {
            if (segFlushJobs.containsKey(id)) return
            segFlushJobs[id] = scope.launch {
                delay(SEG_FLUSH_INTERVAL_MS)
                // Hapus SEBELUM flush: record yang datang setelah snapshot tapi
                // sebelum remove lama hilang tanpa flush susulan (progress basi
                // sampai tick berikutnya). Hapus dulu agar record susulan
                // menjadwalkan job baru; overlap flush aman (flush @Synchronized).
                segFlushJobs.remove(id)
                runCatching { flushSegmentProgress(id) }
            }
        }
    }

    /** Gabungkan semua progres segmen tertunda menjadi SATU updateItem (satu
     *  salinan daftar + satu emisi StateFlow per interval per item). */
    @Synchronized
    private fun flushSegmentProgress(id: String) {
        // Snapshot di bawah lock agar baca tak balapan dengan tulis segmen/clear.
        val pending = synchronized(segProgress) {
            segProgress[id]?.let { arr -> LongArray(arr.length()) { i -> arr.get(i) } }
        } ?: return
        val current = _items.value.find { it.id == id } ?: return
        // Jangan menimpa item yang sudah pause/gagal/selesai.
        if (current.state != DownloadState.DOWNLOADING) return
        // Monoton WAJIB: buffer adalah snapshot berkala, sedangkan nilai final
        // segmen ditulis langsung oleh updateSegment. Menerapkan snapshot yang
        // lebih kecil dari nilai sekarang memundurkan progres dan membuat
        // verifikasi akhir mengira segmen belum lengkap (unduhan 100% sempat
        // berubah PAUSED). Pola sama seperti overlayProgress di codec.
        val dirty = current.segments.any { seg ->
            seg.index < pending.size && pending[seg.index] > seg.downloaded
        }
        if (!dirty) return
        updateItem(id, persist = false) { item ->
            var totalDone = 0L
            val segs = item.segments.map { seg ->
                val p = if (seg.index < pending.size) pending[seg.index] else -1L
                val next = if (p > seg.downloaded) seg.copy(downloaded = p) else seg
                totalDone += next.downloaded
                next
            }
            // Sampel kecepatan/ETA maks 1x/detik (EMA + format ETA mahal bila
            // N item aktif x flush 500ms); byte/segmen tetap update tiap flush.
            val nowMs = monotonicNow()
            val prev = lastSpeedSample[id]
            val (speed, eta) = if (prev != null && nowMs - prev.first < 1000) {
                prev.second
            } else {
                val sampled = speedTracker.sample(id, totalDone, item.totalBytes)
                lastSpeedSample[id] = nowMs to sampled
                sampled
            }
            item.copy(
                segments = segs,
                bytesDownloaded = totalDone,
                speedBps = speed,
                etaSeconds = eta
            )
        }
        scheduleProgressSave()
    }

    /** Buang penyangga progres + batalkan flush job (download selesai/gagal/
     *  ulang dari awal). Nilai final tetap sudah ditulis via updateSegment. */
    private fun resetThrottleTotal(id: String, value: Long) {
        // Satu lock dengan addThrottleTotal: put polos balapan dengan
        // getOrPut+add paralel sehingga hitungan hilang (over-throttle)
        // atau reset tertimpa counter basi (bypass throttle).
        synchronized(throttleTotals) { throttleTotals[id] = AtomicLong(value) }
    }

    private fun addThrottleTotal(id: String, delta: Long): Long =
        // getOrPut tidak atomik: pembuatan counter + addAndGet wajib satu lock
        // agar reset paralel tidak membuang hitungan (bypass/over-throttle).
        synchronized(throttleTotals) {
            throttleTotals.getOrPut(id) {
                val current = _items.value.find { it.id == id }?.bytesDownloaded ?: 0L
                AtomicLong(current)
            }.addAndGet(delta)
        }

    private fun clearSegProgress(id: String) {
        synchronized(segProgress) { segProgress.remove(id) }
        // Lock sama dengan add/reset: remove polos bisa menghapus setelah
        // add membuat ulang (counter yatim) atau sebelum reset (reset basi).
        synchronized(throttleTotals) { throttleTotals.remove(id) }
        lastSpeedSample.remove(id)
        segFlushJobs.remove(id)?.cancel()
    }

    private fun verifySize(downloaded: Long, total: Long, label: String) {
        // total = klaim Content-Length. Underrun sekecil apa pun berarti file
        // terpotong dan tidak boleh dilaporkan COMPLETED (toleransi lama
        // menutupi video korup). Overrun tetap diterima (klaim bisa basi,
        // mis. gzip/proxy) tapi dicatat: tanpa checksum, kelebihan byte
        // injeksi tak terdeteksi sehingga layak terlihat di log.
        if (total > 0 && downloaded < total) {
            throw IOException("Size mismatch: expected $total (Content-Length), received $downloaded")
        }
        if (total > 0 && downloaded > total) {
            App.logEvent("SIZE OVERRUN: $label — received $downloaded > claimed $total (kept, no checksum)")
        }
    }

    private fun applyAuthHeaders(
        conn: HttpURLConnection,
        username: String,
        password: String,
        headers: String
    ) {
        if (username.isNotEmpty()) {
            val raw = "$username:$password"
            val encoded = Base64.encodeToString(raw.toByteArray(Charsets.UTF_8), Base64.NO_WRAP)
            conn.setRequestProperty("Authorization", "Basic $encoded")
        }
        // Loop tanpa split() — hindari alokasi List<String> per request.
        var start = 0
        while (start <= headers.length) {
            val nl = headers.indexOf('\n', start)
            val end = if (nl >= 0) nl else headers.length
            if (end > start) {
                val line = headers.substring(start, end)
                val idx = line.indexOf(':')
                if (idx > 0) {
                    val key = line.substring(0, idx).trim()
                    val value = line.substring(idx + 1).trim()
                    if (key.isNotEmpty()) conn.setRequestProperty(key, value)
                }
            }
            if (nl < 0) break
            start = nl + 1
        }
    }

    // Didelegasikan ke SegmentPlanner murni agar mudah diuji (lihat SegmentPlannerTest).
    private fun createSegments(total: Long): List<DownloadSegment> =
        SegmentPlanner.plan(total, StoragePrefs.segmentCount(context))

    private fun parseChecksum(raw: String): Pair<String, String>? {
        val clean = raw.trim()
        if (clean.isEmpty()) return null
        val (algo, rest) = when {
            clean.startsWith("md5:", ignoreCase = true) -> "MD5" to clean.substring(4)
            clean.startsWith("sha1:", ignoreCase = true) -> "SHA-1" to clean.substring(5)
            clean.startsWith("sha256:", ignoreCase = true) -> "SHA-256" to clean.substring(7)
            else -> {
                // Tanpa awalan: tebak algoritma dari panjang hex polos
                // (MD5 32, SHA-1 40, SHA-256 64). Sebelumnya nilai polos
                // selalu ditafsir MD5 sehingga hash SHA polos mustahil
                // cocok dan unduhan utuh terhapus (issue #342).
                val bare = clean.trim()
                when (bare.length) {
                    32 -> "MD5" to bare
                    40 -> "SHA-1" to bare
                    64 -> "SHA-256" to bare
                    else -> return null
                }
            }
        }
        val value = rest.trim().lowercase(Locale.ROOT)
        val expectedLen = when (algo) {
            "MD5" -> 32
            "SHA-1" -> 40
            else -> 64
        }
        // Validasi format eksak per algoritma: nilai cacat/non-hex
        // ditolak di muka sebagai "tanpa checksum", bukan disimpan
        // sebagai ekspektasi yang mustahil cocok (issue #342).
        if (value.length != expectedLen ||
            !value.all { it in '0'..'9' || it in 'a'..'f' }
        ) return null
        return algo to value
    }

    /** Hapus file yang baru terpublish bila checksum gagal: tanpa ini file korup
     *  tertinggal di pustaka dan retry membuat duplikat "nama (1)"
     *  via uniqueTargetFile. */
    private fun deletePublished(published: FileSaver.PublishResult) {
        runCatching {
            val path = published.filePath
            if (!path.isNullOrEmpty()) File(path).delete()
        }
        runCatching {
            val uri = published.contentUri
            if (!uri.isNullOrEmpty()) context.contentResolver.delete(uri.toUri(), null, null)
        }
    }

    private fun verifyChecksum(
        itemId: String,
        published: FileSaver.PublishResult,
        saver: FileSaver
    ): String? {
        val current = _items.value.find { it.id == itemId } ?: return null
        val expected = parseChecksum(current.checksum) ?: saver.sidecarChecksum(
            current.copy(
                contentUri = published.contentUri ?: current.contentUri,
                filePath = published.filePath ?: current.filePath
            )
        ) ?: return null
        val (algo, hex) = expected
        val digest = computeDigest(published, algo, saver)
            ?: return "Cannot read file for checksum verification"
        if (!digest.equals(hex, ignoreCase = true)) {
            return "Checksum $algo mismatch (expected $hex, got $digest)"
        }
        updateItem(itemId) { it.copy(checksumVerified = true) }
        flushSave()
        return null
    }

    private fun computeDigest(
        published: FileSaver.PublishResult,
        algo: String,
        saver: FileSaver
    ): String? = runCatching {
        val input = when {
            !published.filePath.isNullOrEmpty() -> File(published.filePath).inputStream()
            !published.contentUri.isNullOrEmpty() ->
                context.contentResolver.openInputStream(published.contentUri.toUri())
            else -> null
        } ?: return null
        input.use { stream ->
            // SHA-256 streaming via digest per-thread (sekali per file selesai).
            val md = if (algo.equals("SHA-256", ignoreCase = true)) {
                com.tasirin.httpdownloadmanager.util.sha256Digest()
            } else {
                MessageDigest.getInstance(algo)
            }
            val buf = ByteArray(BUFFER_SIZE)
            while (true) {
                val read = stream.read(buf)
                if (read == -1) break
                md.update(buf, 0, read)
            }
            Hex.encode(md.digest())
        }
    }.getOrNull()

    private fun contentLength(conn: HttpURLConnection): Long {
        return conn.getHeaderField("Content-Length")?.trim()?.toLongOrNull() ?: -1L
    }

    /** Header yang bisa membawa checksum file — dipakai untuk deteksi otomatis
     *  (server mengirim Digest/Content-MD5/X-Checksum-*). */
    private fun checksumHeadersOf(conn: HttpURLConnection): Map<String, String> {
        val map = HashMap<String, String>(CHECKSUM_HEADER_NAMES.size)
        for (n in CHECKSUM_HEADER_NAMES) {
            conn.getHeaderField(n)?.let { map[n] = it }
        }
        return map
    }

    /** Isi checksum item dari header respons bila user belum mengisinya manual. */
    private fun captureHeaderChecksum(item: DownloadItem, conn: HttpURLConnection) {
        if (item.checksum.isNotBlank()) return
        val detected = Checksums.fromHeaders(checksumHeadersOf(conn)) ?: return
        updateItem(item.id) { it.copy(checksum = detected) }
        App.logEvent("DOWNLOAD ${item.fileName}: checksum ${detected.substringBefore(':')} detected from server headers")
    }

    private fun headersOf(conn: HttpURLConnection): ServerHeaders {
        return ServerHeaders(
            contentDisposition = conn.getHeaderField("Content-Disposition"),
            contentType = conn.getHeaderField("Content-Type"),
            etag = conn.getHeaderField("ETag")
        )
    }

    private fun resolveFinalName(item: DownloadItem, headers: ServerHeaders?): String {
        var name = item.fileName
        if (!item.nameIsCustom) {
            val dispositionName = headers?.contentDisposition?.let { contentDispositionName(it) }
            if (!dispositionName.isNullOrBlank()) {
                name = sanitizeFileName(dispositionName)
            }
            if (name.isBlank()) {
                name = guessFileName(item.url)
            }
            val contentType = headers?.contentType?.substringBefore(';')?.trim().orEmpty()
            val ext = MimeTypes.extensionFor(contentType)
            if (ext != null && name.substringAfterLast('.', "").isEmpty() && !name.endsWith('.')) {
                name += ext
            }
            name = sanitizeFileName(name)
        }
        return name.takeIf { it.isNotBlank() } ?: item.fileName
    }

    private fun contentDispositionName(header: String?): String? {
        if (header.isNullOrBlank()) return null
        val star = CONTENT_DISPOSITION_STAR.find(header)
        if (star != null) {
            val value = star.groupValues[1].trim()
            val idx = value.indexOf("''")
            if (idx >= 0) {
                val decoded = runCatching {
                    URLDecoder.decode(value.substring(idx + 2), "UTF-8")
                }.getOrNull()
                if (!decoded.isNullOrBlank()) return decoded
            }
        }
        val plain = CONTENT_DISPOSITION_PLAIN.find(header)
        return plain?.groupValues?.get(1)?.trim()?.takeIf { it.isNotEmpty() }
    }

    private fun sanitizeFileName(name: String): String = FileNames.safe(name)

    private fun ensureServiceRunning() {
        runCatching {
            val intent = Intent(context, DownloadService::class.java)
            if (Build.VERSION.SDK_INT >= 26) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }
        // Jika foreground service gagal dimulai (mis. pembatasan Android 12+ saat
        // di latar belakang), download tetap dijalankan di proses aplikasi.
    }

    @Synchronized
    private fun updateItem(
        id: String,
        persist: Boolean = true,
        transform: (DownloadItem) -> DownloadItem
    ): DownloadItem? {
        // Salin per-indeks + lewati emit bila sama: hindari map O(n) dengan
        // transform untuk semua item pada tiap tick progres 1 detik.
        // Kembalikan item baru supaya pemanggil tak perlu find() ulang O(n).
        val cur = _items.value
        val idx = cur.indexOfFirst { it.id == id }
        if (idx < 0) return null
        val next = transform(cur[idx])
        if (next == cur[idx]) return cur[idx]
        val copy = ArrayList(cur)
        copy[idx] = next
        update(copy, persist)
        // URL sosial asli hanya dibaca untuk item yang masih bisa
        // berjalan ulang; sesudah COMPLETED ia tidak dibaca lagi dan
        // tidak boleh menetap seumur proses (issue #364).
        if (next.state == DownloadState.COMPLETED) originalSocialUrls.remove(id)
        return next
    }

    /** Terapkan [transform] ke daftar TERKINI secara atomik (#274):
     *  baca-ubah-tulis berjalan di dalam monitor yang sama dengan
     *  update()/updateItem(), bukan membaca _items.value di luar kunci
     *  lalu menulis hasil berbasis salinan basi — mutasi konkuren
     *  (finalisasi paralel vs aksi pengguna) bisa tertimpa dan hilang. */
    @Synchronized
    private fun updateTransformed(
        transform: (List<DownloadItem>) -> List<DownloadItem>
    ) {
        update(transform(_items.value))
    }

    @Synchronized
    private fun update(items: List<DownloadItem>, persist: Boolean = true) {
        _items.value = items
        if (persist) scheduleSave()
    }

    @Synchronized
    private fun scheduleSave() {
        // Sebelum muatan pertama terpasang, daftar belum utuh: simpan
        // sekarang bisa menimpa antrean tersimpan dengan daftar kosong
        // (#242). Penambahan selama jendela muat disimpan flushSave
        // penutup muatan.
        if (!loadGate.isCompleted) {
            saveDeferredPreGate = true
            return
        }
        saveJob?.cancel()
        saveJob = scope.launch {
            delay(SAVE_DEBOUNCE_MS)
            repository.save(_items.value)
            logEncryptFallbackIfUsed()
        }
    }

    /** Progres ringan: JSON kecil (id -> bytes/total) tanpa enkripsi dan tanpa
     *  detail segmen, disimpan paling cepat tiap 2 detik selama download aktif. */
    @Synchronized
    private fun scheduleProgressSave() {
        if (!loadGate.isCompleted) return
        val now = monotonicNow()
        if (now - lastProgressSaveAt < PROGRESS_SAVE_INTERVAL_MS) return
        lastProgressSaveAt = now
        progressSaveJob?.cancel()
        progressSaveJob = scope.launch {
            repository.saveProgress(_items.value)
        }
    }

    @Synchronized
    private fun flushSave() {
        // Sama seperti scheduleSave: jangan persist daftar belum utuh.
        if (!loadGate.isCompleted) {
            saveDeferredPreGate = true
            return
        }
        saveJob?.cancel()
        saveJob = null
        // Sinkron: state pause/cancel/selesai/add tidak boleh hilang bila
        // proses mati sebelum antrean apply() SharedPreferences jalan.
        // Tapi commit() sinkron di main thread = ANR saat daftar besar,
        // jadi di main thread tulis apply dulu lalu commit di IO.
        if (Looper.myLooper() == Looper.getMainLooper()) {
            // Encode SEKALI, tulis dua cara (issue #370): apply dulu
            // supaya antrean tulis segera berjalan, lalu commit hasil
            // encode yang sama di latar — bukan encode ulang penuh.
            val prepared = repository.prepareSave(_items.value)
            if (prepared != null) {
                repository.applyPrepared(prepared)
                scope.launch { repository.commitPrepared(prepared) }
            }
        } else {
            repository.saveImmediate(_items.value)
        }
        logEncryptFallbackIfUsed()
    }

    /** Konsumsi sinyal fallback enkripsi (issue #209): flag ini sebelumnya
     *  write-only — tak ada yang tahu kredensial jatuh ke plaintext saat
     *  keystore bermasalah. Dipanggil dari flushSave DAN simpan debounce
     *  (#222: jalur debounce dulu memanggil repository.save langsung,
     *  melewati konsumsi ini, jadi kejadian fallback di sesi panjang bisa
     *  tak pernah tercatat). */
    private fun logEncryptFallbackIfUsed() {
        if (Crypto.consumeEncryptFallbackUsed()) {
            App.logEvent("STORAGE: encryption fallback used during save (keystore issue?)")
        }
    }

    private fun guessFileName(url: String): String {
        val noQuery = url.substringBefore('?').substringBefore('#')
        val path = noQuery.toUri().lastPathSegment.orEmpty()
        val candidate = path.trim()
        // Segmen path seperti "file=123.mp4" adalah nama valid (punya ekstensi);
        // tolak '=' hanya bila tanpa titik (kunci query nyasar ke path).
        if (candidate.isNotEmpty() && (!candidate.contains('=') || candidate.contains('.'))) return candidate
        return "download_${DEFAULT_NAME_FORMAT.get().format(Date())}"
    }

    private val cookiePrefs by lazy {
        context.getSharedPreferences("cookies", android.content.Context.MODE_PRIVATE)
    }
    // Debounce cookie write: hindari I/O berlebih saat banyak download selesai simultan
    private var cookieWriteJob: Job? = null

    /** Simpan cookie ke SharedPreferences agar persist antar restart. */
    private fun persistCookies() {
        cookieWriteJob?.cancel()
        cookieWriteJob = scope.launch {
            delay(COOKIE_WRITE_DEBOUNCE_MS)
            persistCookiesImmediate()
        }
    }

    private fun persistCookiesImmediate() {
        try {
            val arr = JSONArray()
            val now = System.currentTimeMillis()
            cookieManager.cookieStore.cookies.forEach { c ->
                if (c.hasExpired()) return@forEach
                val ttlSec = if (c.maxAge > 0) c.maxAge else COOKIE_LEGACY_MAX_AGE_SEC
                arr.put(JSONObject().apply {
                    put("name", c.name)
                    put("value", c.value)
                    put("domain", c.domain.orEmpty())
                    put("path", c.path.orEmpty())
                    put("maxAge", c.maxAge)
                    put("expAt", now + ttlSec * 1000L)
                })
            }
            // Cookie (termasuk cookie sesi) dienkripsi seperti kredensial
            // item — bukan plaintext di SharedPreferences (issue #204).
            cookiePrefs.edit { putString("cookies", Crypto.encrypt(arr.toString())) }
        } catch (_: Exception) { /* cookie persist is best-effort */ } // audit-ignore: file_scoped_catch_all, kotlin_empty_catch (persist cookie best-effort: gagal tulis tak boleh menggagalkan unduhan)
    }

    /** Muat cookie dari SharedPreferences; yang kedaluwarsa dibuang. */
    private fun loadPersistedCookies() {
        try {
            // Decrypt menoleransi data lama: payload plaintext pra-#204
            // (JSON tanpa prefix Crypto) dikembalikan apa adanya.
            val raw = cookiePrefs.getString("cookies", null) ?: return
            val plain = Crypto.decrypt(raw)
            if (plain.isEmpty() && Crypto.isEncrypted(raw)) {
                // Blob v1: tak terdekripsi (kunci Keystore ter-invalidasi
                // dsb, #221): seluruh cookie persisten hilang — catat agar
                // tidak lenyap diam-diam tanpa jejak diagnosis.
                App.logEvent("COOKIES: stored cookies undecryptable, dropped")
            }
            val arr = JSONArray(plain)
            val now = System.currentTimeMillis()
            for (i in 0 until arr.length()) {
                runCatching {
                    val obj = arr.getJSONObject(i)
                    val expAt = obj.optLong("expAt", 0L)
                    if (expAt > 0 && expAt <= now) return@runCatching
                    val cookie = HttpCookie(obj.getString("name"), obj.getString("value"))
                    cookie.domain = obj.getString("domain")
                    cookie.path = obj.getString("path")
                    if (expAt > 0) {
                        cookie.maxAge = ((expAt - now) / 1000L).coerceAtLeast(1L)
                    } else {
                        val storedAge = obj.optLong("maxAge", COOKIE_LEGACY_MAX_AGE_SEC)
                        cookie.maxAge = if (storedAge < 0) COOKIE_LEGACY_MAX_AGE_SEC else storedAge
                    }
                    if (cookie.hasExpired()) return@runCatching
                    cookieManager.cookieStore.add(null, cookie)
                }
            }
        } catch (_: Exception) { /* cookie persist is best-effort */ } // audit-ignore: file_scoped_catch_all, kotlin_empty_catch (persist cookie best-effort: gagal tulis tak boleh menggagalkan unduhan)
    }

    companion object {
        private val CONTENT_DISPOSITION_STAR = Regex("filename\\*=([^;]+)")
        private val DEFAULT_NAME_FORMAT: ThreadLocal<SimpleDateFormat> = ThreadLocal.withInitial {
            SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US)
        }
        private val CONTENT_DISPOSITION_PLAIN = Regex("filename=\"?([^\";]+)\"?")
        private const val BUFFER_SIZE = 64 * 1024
        private const val MAX_ZERO_READS = 64
        private const val MAX_REDIRECTS = 5
        private const val HLS_PROBE_MAX_BYTES = 1_000_000
        private const val HLS_SEGMENT_MAX_BYTES = 64L * 1024 * 1024
        /** Segmen HLS ukuran ini dianggap segmen data (bukan init/small). */
        private const val HLS_REF_SEGMENT_MIN = 256L * 1024
        private const val SEGMENT_MIN_BYTES = 5L * 1024 * 1024
        private const val RETRY_DELAY_1_MS = 5_000L
        private const val RETRY_DELAY_MAX_MS = 300_000L
        private const val MIN_FREE_BYTES = 2L * 1024 * 1024
        private const val SAVE_DEBOUNCE_MS = 400L
        private const val PROGRESS_SAVE_INTERVAL_MS = 2_000L
        private val CHECKSUM_HEADER_NAMES = listOf(
            "Digest", "X-Checksum-Sha256", "X-Checksum-Sha1",
            "X-Checksum-MD5", "Content-MD5"
        )
        private const val SEG_FLUSH_INTERVAL_MS = 500L
        private const val COOKIE_WRITE_DEBOUNCE_MS = 2_000L
        /** Umur fallback cookie format lama (tanpa maxAge tersimpan). */
        private const val COOKIE_LEGACY_MAX_AGE_SEC = 7L * 24 * 60 * 60
        /** Lewati cleanup yatim bila antrean kosong tapi prefs menyimpan
         *  blob (tanda load terdegradasi, bukan fresh install). */
        internal fun shouldSkipOrphanCleanup(itemCount: Int, hadStored: Boolean): Boolean =
            itemCount == 0 && hadStored

        private const val MONITOR_INTERVAL_MS = 30 * 60 * 1000L
        // User-Agent realistis agar situs download tidak memblokir koneksi.
        private const val DEFAULT_USER_AGENT =
            "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Mobile Safari/537.36"
    }
}

/** Jam monotonik untuk interval (throttle/watchdog/progres): jam dinding bisa
 *  melompat (NTP/ubah jam manual) dan membuat throttle macet berjam-jam atau
 *  watchdog salah vonis stall. Fallback ke currentTimeMillis di JVM unit test. */
/** Ujung Content-Range respons segmen valid: tak dikirim (null) atau tidak
 *  melebihi ujung yang diminta. Murni agar bisa di-unit-test. */
internal fun isSegmentEndAllowed(returnedEnd: Long?, requestedEnd: Long): Boolean =
    returnedEnd == null || returnedEnd <= requestedEnd

/** Porsi baca yang boleh ditulis ke file parsial segmen: tak pernah melebihi
 *  sisa budget Range. Nol/negatif berarti budget habis — pemanggil wajib gagal
 *  eksplisit, bukan menyerap byte korup. Murni agar bisa di-unit-test. */
internal fun cappedSegmentWrite(read: Int, budgetLeft: Long): Int =
    minOf(read.toLong(), budgetLeft.coerceAtLeast(0)).toInt()

/** Partial dipertahankan bila Range ditolak SETELAH ada progres: penolakan di
 *  tengah jalan = link kedaluwarsa/transien, bukan server tanpa-Range.
 *  Murni agar bisa di-unit-test. */
internal fun shouldKeepPartialOnRangeReject(keptBytes: Long): Boolean = keptBytes > 0

/** Penolakan resume sejati dari lapis koneksi (server/proxy tak layani Range).
 *  SENGAJA tidak mencakup "stopped supporting Range" — itu pesan retry-able
 *  internal (URL segar sudah dipasang, tinggal coba lagi); bila ikut match,
 *  handleFailure langsung FAILED tanpa memberi kesempatan pada URL segar.
 *  Murni agar bisa di-unit-test. */
internal fun isRangeRejectError(message: String?): Boolean {
    if (message.isNullOrBlank()) return false
    return message.contains("does not support Range", ignoreCase = true) ||
        message.contains("HTTP 416", ignoreCase = true) ||
        message.contains("refused resume", ignoreCase = true)
}

/** Ekor terpotong (server menutup koneksi sebelum Content-Length terpenuhi):
 *  transient seperti putus jaringan — partial tetap valid, jadi setelah retry
 *  habis jangan FAILED melainkan PAUSED agar auto-lanjut saat koneksi pulih.
 *  Murni agar bisa di-unit-test. */
internal fun isTailIncompleteError(message: String?): Boolean {
    if (message.isNullOrBlank()) return false
    return message.contains("Size mismatch", ignoreCase = true) ||
        message.contains("incomplete", ignoreCase = true)
}

/** Ukuran file hasil re-extract wajib sama dengan file lama agar resume segmen
 *  tidak korup; freshTotal <= 0 = server tak memberi tahu (izinkan resume
 *  best-effort). Murni agar bisa di-unit-test. */
internal fun isFreshResumeSizeValid(storedTotal: Long, freshTotal: Long): Boolean =
    freshTotal <= 0 || storedTotal <= 0 || freshTotal == storedTotal

internal fun monotonicNow(): Long =
    runCatching { SystemClock.elapsedRealtime() }.getOrDefault(System.currentTimeMillis())

/** Durasi segmen HLS (mikrodetik) dari detik EXTINF: dijepit 0 dtk–24 jam
 *  agar playlist rusak/jahat tak membuat overflow Long negatif yang merusak
 *  estimasi ukuran dan muxer. Murni agar bisa di-unit-test. */
internal fun hlsSegmentDurationUs(durSec: Double): Long {
    if (!durSec.isFinite()) return 0L
    return ((durSec.coerceIn(0.0, 86_400.0)) * 1_000_000).toLong()
}

/** (a*b)/div tanpa overflow Long: bagi dulu baru kali, sisa dihitung
 *  terpisah. Jenuh di Long.MAX bila operan raksasa (BANDWIDTH playlist
 *  jahat). Murni agar bisa di-unit-test. */
internal fun safeMulDiv(a: Long, b: Long, div: Long): Long {
    if (a <= 0 || b <= 0 || div <= 0) return 0L
    val q = a / div
    val r = a % div
    // q*b / r*b masih bisa meluap bila b raksasa: jenuh, jangan bungkus negatif.
    val hi = if (q > 0 && b > Long.MAX_VALUE / q) return Long.MAX_VALUE else q * b
    val lo = if (r > 0 && b > Long.MAX_VALUE / r) return Long.MAX_VALUE else (r * b) / div
    return if (hi > Long.MAX_VALUE - lo) Long.MAX_VALUE else hi + lo
}

/** Jumlah jenuh: dua estimasi yang sudah dijepit MAX tak boleh bungkus
 *  negatif saat dijumlah. Murni agar bisa di-unit-test. */
internal fun saturatingAdd(a: Long, b: Long): Long =
    if (b > 0 && a > Long.MAX_VALUE - b) Long.MAX_VALUE else (a + b).coerceAtLeast(0L)

internal fun redirectTarget(base: String, location: String?): String? {
    if (location.isNullOrBlank()) return null
    return runCatching {
        val target = URL(URL(base), location)
        if (target.protocol != "http" && target.protocol != "https") return null
        if (isBlockedRedirectHost(target.host.orEmpty())) return null
        target.toString()
    }.getOrNull()
}

/** Host literal yang tidak boleh dituju redirect: loopback, unspecified,
 *  dan metadata cloud. Bukan blokir DNS umum agar unduhan LAN (NAS
 *  192.168.x/10.x) tetap jalan; hanya literal berbahaya yang ditolak. */
internal fun isBlockedRedirectHost(host: String): Boolean {
    val h = host.trim().trimEnd('.').lowercase(Locale.ROOT)
    if (h.isEmpty()) return true
    if (h == "localhost" || h.endsWith(".localhost")) return true
    if (h == "0.0.0.0" || h == "::" || h == "::1" || h == "[::1]" || h == "[::]") return true
    if (h == "169.254.169.254" || h == "metadata.google.internal") return true
    if (h.startsWith("127.")) return true
    if (h.startsWith("[::ffff:127.")) return true
    // Bentuk numerik IPv4 (desimal penuh/okt/heks, mis. 2130706433 = 127.0.0.1):
    // tolak bila oktet pertama 127 atau link-local 169.254.x.x.
    val numeric = h.removePrefix("[").removeSuffix("]")
    // Unspecified IPv6 panjang (0:0:...:0) dan mapped-IPv4 wildcard
    // (::ffff:0.0.0.0): setara 0.0.0.0 — tolak seperti bentuk ringkas "::".
    if (numeric == "0:0:0:0:0:0:0:0" || numeric == "::ffff:0.0.0.0") return true
    // Mapped-IPv4 loopback tanpa kurung (mis. dari URL yang tak dinormalisasi).
    if (numeric.startsWith("::ffff:127.")) return true
    // IPv6 literal bentuk penuh (mis. 0:0:0:0:0:0:0:1 = ::1): host URL bisa
    // tak ternormalisasi sehingga lolos semua cabang string di atas yang
    // hanya kenal bentuk ringkas. getByName pada literal tak memicu DNS;
    // guard regex memastikan hanya literal valid yang diparse (tanpa ini
    // string sampah memicu lookup DNS tanpa timeout di thread worker).
    // Link-local (fe80::/10) SENGAJA lolos seperti LAN IPv4 — hanya loopback
    // dan unspecified yang ditolak.
    if (numeric.contains(':') && IPV6_LITERAL_RE.matches(numeric)) {
        val addr = runCatching { java.net.InetAddress.getByName(numeric) }.getOrNull()
        if (addr != null && (addr.isLoopbackAddress || addr.isAnyLocalAddress)) return true
    }
    val parts = numeric.split('.')
    if (parts.size == 4) {
        val first = parseIpv4Part(parts[0])
        val second = parseIpv4Part(parts[1])
        if (first == 127) return true
        if (first == 169 && second == 254) return true
        // 0.0.0.0 tersamar (0x0.0.0.0, 00.0.0.0): wildcard = localhost,
        // tanpa ini hanya string persis "0.0.0.0" yang tertolak.
        if (first == 0 && second == 0 &&
            parseIpv4Part(parts[2]) == 0 && parseIpv4Part(parts[3]) == 0
        ) return true
    } else if (parts.size == 3) {
        // Bentuk ringkas a.b.c (c 16-bit, mis. 127.0.1 = 127.0.0.1):
        // tanpa ini, redirect ke 127.0.0.1 lolos sebagai "127.0.1".
        val first = parseIpv4Part(parts[0])
        val second = parseIpv4Part(parts[1])
        if (first == 127) return true
        if (first == 169 && second == 254) return true
        if (first == 0 && second == 0 && parseIpv4Part(parts[2]) == 0) return true
    } else if (parts.size == 2) {
        // Bentuk ringkas a.b (b 24-bit, mis. 127.1 = 127.0.0.1).
        val first = parseIpv4Part(parts[0])
        if (first == 127) return true
        if (first == 0 && parseIpv4Part(parts[1]) == 0) return true
        if (first == 169) {
            // 169.254.0.0/16 sebagai satu angka 24-bit di bagian kedua.
            val second = parseIpv4Part(parts[1])
            if (second != null && second in (254 shl 16)..((254 shl 16) or 0xFFFF)) return true
        }
    } else if (parts.size == 1) {
        val long = parseIpv4Part(numeric)
        if (long != null) {
            // Angka tunggal 0 ("http://0/") = 0.0.0.0.
            if (long == 0) return true
            if ((long ushr 24) == 127) return true
            if ((long ushr 16) == ((169 shl 8) or 254)) return true
        }
    }
    // Hostname yang lolos semua cek literal: resolve DNS dan tolak bila
    // menunjuk loopback/unspecified (DNS-rebinding: evil.com -> 127.0.0.1).
    // LAN privat (192.168.x/10.x) SENGAJA lolos agar unduhan NAS tetap jalan.
    // Literal IP tak perlu resolve (sudah diputus di atas); DNS gagal =
    // lolos (fail-open: konek pun akan gagal sendiri, dan guard ini tak boleh
    // memblokir redirect sah saat DNS tersendat).
    if (!isIpLiteral(numeric) && HOSTNAME_RE.matches(h)) {
        val addrs = runCatching { java.net.InetAddress.getAllByName(h) }.getOrNull()
            ?: return false
        if (addrs.any { it.isLoopbackAddress || it.isAnyLocalAddress }) return true
    }
    return false
}

/** True bila host adalah literal IP (bukan nama yang butuh resolve DNS).
 *  Murni agar bisa di-unit-test. */
internal fun isIpLiteral(host: String): Boolean {
    val s = host.trim().trimEnd('.').lowercase(Locale.ROOT).removePrefix("[").removeSuffix("]")
    if (s.contains(':')) return IPV6_LITERAL_RE.matches(s)
    val parts = s.split('.')
    if (parts.size == 1) return parseIpv4Part(s) != null
    return parts.size in 2..4 && parts.all { parseIpv4Part(it) != null }
}

/** Hostname valid (label alfanumerik + strip, tanpa spasi/garis bawah):
 *  guard sebelum resolve agar sampah tak memicu lookup DNS. */
internal val HOSTNAME_RE = Regex(
    "^[a-z0-9]([a-z0-9-]{0,61}[a-z0-9])?(\\.[a-z0-9]([a-z0-9-]{0,61}[a-z0-9])?)*$",
    RegexOption.IGNORE_CASE
)

internal fun parseIpv4Part(part: String): Int? {
    val p = part.trim().lowercase(Locale.ROOT)
    if (p.isEmpty()) return null
    return runCatching {
        when {
            p.startsWith("0x") -> p.substring(2).toLong(16)
            p.length > 1 && p.startsWith("0") && p.all { it in '0'..'7' } -> p.toLong(8)
            p.all { it.isDigit() } -> p.toLong(10)
            else -> return null
        }.takeIf { it in 0..4294967295L }?.toInt()
    }.getOrNull()
}

private val HLS_KEY_METHOD_RE = Regex("METHOD=([^,\\s\"]+)", RegexOption.IGNORE_CASE)

/** Literal IPv6 valid (grup heks dipisah ':', satu '::' kompresi diizinkan,
 *  IPv4-embedded bertitik diizinkan): guard sebelum InetAddress.getByName
 *  agar hostname/sampah tak memicu lookup DNS. */
internal val IPV6_LITERAL_RE = Regex(
    "^([0-9a-fA-F]{0,4}:){2,7}[0-9a-fA-F:.]{0,45}$|^::[0-9a-fA-F:.]{0,45}$"
)

/** Playlist HLS terenkripsi bila ada #EXT-X-KEY dengan METHOD selain NONE.
 *  Murni agar bisa di-unit-test. */
internal fun isEncryptedHlsPlaylist(body: String): Boolean {
    if (!body.contains("#EXT-X-KEY")) return false
    val methods = HLS_KEY_METHOD_RE.findAll(body).map { it.groupValues[1].uppercase(Locale.ROOT) }.toList()
    // Tanpa METHOD eksplisit: anggap terenkripsi (fail-closed) daripada
    // mengunduh ciphertext yang dilaporkan sukses.
    if (methods.isEmpty()) return true
    return methods.any { it != "NONE" }
}

/** Playlist HLS byterange memakai #EXT-X-BYTERANGE: tiap "segmen"
 *  adalah rentang byte dari SATU berkas media yang sama, bukan berkas
 *  terpisah. Murni agar bisa di-unit-test. */
internal fun isByteRangeHlsPlaylist(body: String): Boolean =
    body.contains("#EXT-X-BYTERANGE")

internal fun isSameOrigin(first: String, second: String): Boolean = runCatching {
    val firstUrl = URL(first)
    val secondUrl = URL(second)
    firstUrl.protocol == secondUrl.protocol &&
        firstUrl.host.equals(secondUrl.host, ignoreCase = true) &&
        firstUrl.effectivePort() == secondUrl.effectivePort()
}.getOrDefault(false)

private fun URL.effectivePort(): Int =
    port.takeIf { it >= 0 } ?: if (protocol == "https") 443 else 80

/** Keputusan resume anti-korup: apa yang dilakukan terhadap file parsial
 *  sebelum melanjutkan unduhan. Dipisah jadi fungsi murni agar bisa
 *  di-unit-test (lihat ResumeActionTest). */
internal enum class ResumeAction {
    /** File lebih panjang dari catatan: pangkas ke posisi catatan, lanjut. */
    TRUNCATE_TO_RECORD,

    /** Data hilang / sisa tanpa catatan: mulai dari nol (file dibuang). */
    RESTART,

    /** File sudah sinkron dengan catatan: tidak ada yang perlu dilakukan. */
    KEEP
}

internal fun resumeAction(recorded: Long, fileLength: Long): ResumeAction = when {
    recorded <= 0L -> if (fileLength > 0L) ResumeAction.RESTART else ResumeAction.KEEP
    fileLength < recorded -> ResumeAction.RESTART
    fileLength > recorded -> ResumeAction.TRUNCATE_TO_RECORD
    else -> ResumeAction.KEEP
}

private data class ServerHeaders(
    val contentDisposition: String?,
    val contentType: String?,
    val etag: String? = null
)

data class UrlProbe(
    val fileName: String?,
    val sizeBytes: Long,
    val contentType: String?,
    val etag: String? = null
)

private const val MAX_THROTTLE_SLEEP_MS = 2_000L

private class SpeedThrottle(
    private val limitKbps: Int,
    private val shared: GlobalRateLimiter?
) {
    private val lock = Any()
    private var startTime = monotonicNow()
    private var startBytes = 0L
    private var lastSeen = 0L

    fun reset(start: Long) {
        synchronized(lock) {
            startTime = monotonicNow()
            startBytes = start
            lastSeen = start
        }
    }

    suspend fun sleepIfNeeded(totalDownloaded: () -> Long) {
        if (limitKbps <= 0) return
        // Total dihitung SEBELUM lock: pemindaian daftar item di dalam lock
        // menahan segmen lain dan memperlebar window kontensi tiap chunk.
        // Early-return di atas menjaga download tanpa batas tetap tanpa biaya scan.
        val total = totalDownloaded()
        val delayMs = synchronized(lock) {
            val delta = (total - lastSeen).coerceAtLeast(0L)
            lastSeen = total
            val g = shared
            if (g != null) {
                if (delta <= 0) 0L else g.waitFor(delta)
            } else {
                val limit = limitKbps * 1024L
                val elapsed = monotonicNow() - startTime
                val expected = startBytes + (elapsed * limit) / 1000L
                if (total > expected) (((total - expected) * 1000L) / limit).coerceAtMost(MAX_THROTTLE_SLEEP_MS) else 0L
            }
        }
        if (delayMs > 0) delay(delayMs)
    }
}

/** True bila host URL adalah GitHub (termasuk subdomain/asset host): cek host
 *  persis, bukan substring full URL — `https://evil.com/?x=github.com` tidak
 *  boleh memicu mirror proxy pihak ketiga. Internal agar bisa di-unit-test. */
internal fun isGitHubUrl(url: String): Boolean {
    if (!url.startsWith("https://", ignoreCase = true)) return false
    val host = runCatching { java.net.URL(url).host }.getOrDefault("").lowercase(Locale.ROOT)
    return host == "github.com" || host.endsWith(".github.com") ||
        host == "githubusercontent.com" || host.endsWith(".githubusercontent.com") ||
        host == "github.io" || host.endsWith(".github.io")
}

// Internal (bukan private) agar bisa di-unit-test langsung (lihat WatchdogTest).
/** Watchdog per-unduhan: mendeteksi koneksi macet (tanpa byte baru) atau
 *  kecepatan anjlok (di bawah ambang minimum terus-menerus), lalu melempar
 *  IOException supaya handleFailure bisa pindah mirror / retry. Otomatis
 *  nonaktif bila pengguna memasang batas kecepatan di bawah ambang. */
internal class DownloadHealthWatchdog(limitKbps: Int) {
    private val limitedLow = limitKbps > 0 && limitKbps * 1024L <= MIN_GOOD_SPEED_BPS
    private var lastBytes = 0L
    private var lastAt = monotonicNow()
    private var slowSince = 0L

    fun check(now: Long, downloaded: Long, total: Long, speed: Long) {
        if (total > 0 && downloaded >= total) return
        if (downloaded != lastBytes) {
            lastBytes = downloaded
            lastAt = now
            // Byte bergerak = koneksi hidup: maafkan lambat. Tanpa ini,
            // unduhan di jaringan lemah (mis. 2G, <2 KB/s tapi stabil)
            // divonis mati tiap 20 detik lalu retry gagal terus — padahal
            // yang boleh divonis hanya byte yang macet total.
            slowSince = 0L
        }
        if (total <= 0) {
            // Total tak diketahui (HLS/unknown-length): hanya vonis macet
            // total (speed nol), bukan vonis lambat — kecepatan segmen HLS
            // memang burst sehingga ambang 2 KB/s memberi vonis palsu.
            if (speed == 0L && now - lastAt >= STALL_TIMEOUT_MS) {
                throw IOException(
                    "Connection stalled: no data for ${STALL_TIMEOUT_MS / 1000} seconds — retrying"
                )
            }
            return
        }
        if (speed > 0 && !limitedLow && speed < MIN_GOOD_SPEED_BPS) {
            if (slowSince == 0L) slowSince = now
            if (now - slowSince >= LOW_SPEED_TIMEOUT_MS) {
                throw IOException(
                    "Speed too low (${Formats.bytes(speed)}/s) — try mirror/retry"
                )
            }
        } else if (speed == 0L && now - lastAt >= STALL_TIMEOUT_MS) {
            throw IOException(
                "Connection stalled: no data for ${STALL_TIMEOUT_MS / 1000} seconds — retrying"
            )
        } else {
            slowSince = 0L
        }
    }

    companion object {
        private const val MIN_GOOD_SPEED_BPS = 2L * 1024
        private const val LOW_SPEED_TIMEOUT_MS = 20_000L
        private const val STALL_TIMEOUT_MS = 30_000L
    }
}

/** Pembatas kecepatan bersama antar item: sliding window 10 dtk.
 *  Window di-reset saat idle > 10 dtk, jadi pause/resume tidak menghentikan
 *  throttle (akumulasi sejak dibuat membuat throttle mati setelah jeda lama). */
private class GlobalRateLimiter(@Volatile private var limitKbps: Int) {
    private val lock = Any()
    private var windowStart = monotonicNow()
    private var windowBytes = 0L

    /** Ganti batas tanpa mereset window: download yang sedang jalan langsung
     *  ikut nilai baru (window lama tetap valid sebagai riwayat throughput). */
    fun setLimit(kbps: Int) {
        limitKbps = kbps.coerceAtLeast(1)
    }

    fun waitFor(bytes: Long): Long {
        synchronized(lock) {
            val now = monotonicNow()
            if (now - windowStart > GLOBAL_WINDOW_MS) {
                windowStart = now
                windowBytes = 0
            }
            windowBytes += bytes
            val limit = limitKbps * 1024L
            val elapsed = now - windowStart
            val targetMs = (windowBytes * 1000L) / limit
            return if (targetMs > elapsed) (targetMs - elapsed).coerceAtMost(MAX_THROTTLE_SLEEP_MS) else 0L
        }
    }

    companion object {
        private const val GLOBAL_WINDOW_MS = 10_000L
    }
}
