package com.tasirin.httpdownloadmanager.download

import android.annotation.SuppressLint
import android.content.Context
import android.net.Uri
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
import com.tasirin.httpdownloadmanager.remote.ServerSecurity
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
import com.tasirin.httpdownloadmanager.util.decodePercentEncoded
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Deferred
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
import java.io.BufferedOutputStream
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.io.OutputStream
import java.io.RandomAccessFile
import java.net.HttpURLConnection
import javax.net.ssl.HttpsURLConnection
import java.net.URL
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


    // Throttle attempt yang sedang berjalan per item (issue #747):
    // setLimitAndPriority mendorong batas baru ke throttle aktif agar
    // perubahan limit per-item berlaku langsung di tengah unduhan.
    // Entri dicabut berpasangan-nilai dari invokeOnCompletion job
    // attempt yang mendaftarkannya.
    private val activeThrottles = ConcurrentHashMap<String, SpeedThrottle>()
    // Set concurrent via newSetFromMap (bukan ConcurrentHashMap.newKeySet):
    // newKeySet/KeySetView butuh API 24 — di Android 6 (minSdk 23) lempar
    // NoSuchMethodError saat engine dibangun (crash tiap startup); Set polos
    // aman di semua API.
    // Penanda retry tertunda membawa TOKEN GENERASI per jadwal (issue
    // #865): job tunda hanya boleh bertindak bila tokennya masih yang
    // terdaftar — job basi tak boleh mencabut penanda jadwal lebih baru.
    private val pendingRetryTokens = ConcurrentHashMap<String, Long>()
    private val retryTokenSeq = java.util.concurrent.atomic.AtomicLong(0)
    /** Id pendahulu yang flag monitor-nya DISENTUH eksplisit pengguna
     *  selama jendela penerusnya (issue #521): sentuhan pengguna selalu
     *  menang atas pemulihan otomatis #512/#518/#520. Murni memori —
     *  proses mati mengembalikannya ke perilaku pulih-standar, bukan
     *  regresi. */
    private val monitorSentuhanPengguna: MutableSet<String> =
        Collections.newSetFromMap(ConcurrentHashMap<String, Boolean>())

    /** URL sosial media original per item (YouTube/TikTok/etc) — disimpan saat ekstraksi
     *  menghasilkan URL langsung; dipakai untuk re-extract saat URL CDN kedaluwarsa. */
    private val originalSocialUrls = ConcurrentHashMap<String, String>()
    private val activeConns = ConcurrentHashMap<String, MutableSet<HttpURLConnection>>()
    private val speedTracker = SpeedTracker()
    private var saveJob: Job? = null
    private var progressSaveJob: Job? = null
    private var lastProgressSaveAt = 0L
    // URL yang pernah gagal di sesi ini, PER ITEM (issue #953): cadangan
    // yang sama tidak dicoba berulang-ulang oleh item yang sama (hemat
    // waktu saat ISP/proxy menolak beberapa host), tetapi kegagalan
    // transien satu item tidak boleh membuang mirror item lain yang
    // berbagi URL cermin yang sama — kunci entri adalah id item + URL.
    // LinkedHashMap(accessOrder=true) = LRU otomatis — akses terakhir dipindah
    // ke akhir, sehingga eviction selalu membuang entry paling tua.
    // Dipakai dari multiple coroutines (handleFailure + runDownload).
    private val failedUrlLock = Any()
    private val failedUrls = LinkedHashMap<String, Boolean>(0, 0.75f, true)
    private val FAILED_URLS_MAX = 256

    private fun rememberFailedUrl(itemId: String, url: String) {
        synchronized(failedUrlLock) {
            failedUrls["$itemId\n$url"] = true
            // Evict LRU: bila melebihi batas, buang setengah yang paling tua
            if (failedUrls.size > FAILED_URLS_MAX) {
                val iter = failedUrls.keys.iterator()
                var drop = failedUrls.size / 2
                while (iter.hasNext() && drop > 0) { iter.next(); iter.remove(); drop-- }
            }
        }
    }

    private fun isFailedUrl(itemId: String, url: String): Boolean = synchronized(failedUrlLock) {
        failedUrls.containsKey("$itemId\n$url")
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
            // Kunci yang sama didaftarkan ulang: entri lama dibuang dan
            // pendaftaran baru masuk di ekor (issue #960) — niat TERAKHIR
            // pengguna yang menang. Dulu pendaftaran ulang dibuang,
            // sehingga pola Jeda-Lanjut-Jeda selama muatan awal berakhir
            // Lanjut. Penggabungan panggilan identik beruntun tetap
            // terjaga: antrean tak pernah memuat dua entri berkunci sama.
            deferredGateQueue.removeIf { it.first == key }
            deferredGateQueue.addLast(key to action)
            if (deferredGateJobActive) {
                false
            } else {
                deferredGateJobActive = true
                true
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
                    // Urutan blob ADALAH urutan pengguna (issue #1060):
                    // encode menulis urutan daftar apa adanya dan
                    // moveUp/moveDown mempersist tukarannya lewat
                    // scheduleSave — mengurutkan ulang menurut addedAt
                    // di sini membuang susunan manual itu tiap restart
                    // proses. Extras (ditambahkan selama jendela muat)
                    // adalah yang terbaru dan sudah berurutan tampil
                    // di _items, jadi mereka tetap di depan tanpa sortir.
                    _items.value = ex + loaded
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
        // Kunci yang sama dengan trackConnection (issue #1370): tanpa ini
        // pelepasan bisa menyela getOrPut di dalam kunci sehingga koneksi
        // yang baru terdaftar mendarat di set yatim yang sudah tak terdaftar
        // di map — disconnectActive tak akan pernah menemukannya.
        val wasCancelled = synchronized(activeConns) {
            activeConns[id]?.remove(connection)
            activeConns.remove(id, emptySet())
            // Bendera batal dibuang saat koneksi terakhir id ini lepas, TANPA
            // memindai seluruh daftar item seperti sebelumnya (issue #373):
            // jalur panas per-segmen tidak boleh membayar O(jumlah item).
            // Aman karena perlindungan koneksi yatim job yang dibatalkan
            // tidak lagi bergantung pada bendera ini — trackOpenedConnection
            // menilai identitas job coroutine lewat ensureActive (#200), dan
            // launchItem selalu membersihkan bendera sebelum job baru mulai.
            val cancelled = cancelledConns.containsKey(id)
            if (activeConns[id] == null) cancelledConns.remove(id)
            cancelled
        }
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
        pendingRetryTokens.remove(id)
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
        pendingRetryTokens.remove(id)
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
        pendingRetryTokens.remove(id)
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
        // Cancel atas penerus monitor (issue #520): flag pendahulu
        // dimatikan saat penerus dibuat sebagai detail implementasi,
        // bukan keputusan pengguna tentang pemantauan — hidupkan lagi
        // agar membatalkan unduhan pembaruan tidak membunuh langganan
        // pemantauan secara diam-diam. No-op untuk item biasa.
        hidupkanLagiMonitorPendahulu(target)
        scheduleSave()
        return true
    }

    fun remove(id: String): Boolean {
        val item = _items.value.find { it.id == id } ?: return false
        App.logEvent("DOWNLOAD DELETED: ${item.fileName}")
        retryAttempts.remove(id)
        pendingRetryTokens.remove(id)
        originalSocialUrls.remove(id)
        speedTracker.reset(id)
        clearSegProgress(id)
        jobs.remove(id)?.cancel()
        disconnectActive(id)
        // Keputusan parkiran + penghapusan berkas diserialkan terhadap
        // commitReplacement lewat monitor yang sama (issue #1377):
        // commit menahan monitor dari pengambilan parkiran sampai
        // pendahulu terhapus dari daftar, jadi di dalam blok ini vonis
        // "parkiran tak ada" hanya punya dua arti yang bisa dibedakan —
        // memang tak pernah ada parkiran (item masih di daftar), atau
        // commit sudah selesai lebih dulu (item sudah dibuang commit
        // dari daftar dan berkas di path bersama kini milik penerus
        // terverifikasi). Keduanya menuntut perlakuan berbeda.
        synchronized(pendingReplacements) {
            // Penghapusan pendahulu yang penggantiannya sedang terparkir
            // (issue #1287): berkas di path pendahulu saat ini milik PENERUS
            // yang belum terverifikasi — menghapusnya di sini merusak
            // finalisasi penerus (penerus COMPLETED menunjuk berkas hilang,
            // atau rollback memulihkan berkas yang entri-nya sudah dibuang).
            // Byte pendahulu yang sebenarnya adalah cadangan .replaced:
            // hapus cadangan itu, lepas parkirannya (commit/rollback penerus
            // menjadi no-op bersih), dan bersihkan sisanya lewat salinan
            // tanpa filePath (pola commitReplacement) agar berkas terbit
            // bersama + sidecar penerus tak tersentuh; parsial dan baris
            // MediaStore pendahulu tetap terhapus.
            val parkedKey = pendingReplacements.entries
                .firstOrNull { it.value.predecessor.id == id }?.key
            val parked = parkedKey?.let { pendingReplacements.remove(it) }
            when {
                parked != null -> {
                    runCatching { parked.backup.delete() }
                    parked.stale?.let { st -> runCatching { st.delete() } }
                    fileSaver.deleteFiles(item.copy(filePath = null))
                }
                _items.value.none { it.id == id } -> {
                    // Commit selesai lebih dulu (kasus kedua di atas):
                    // bersihkan seperti jalur parkiran, tanpa filePath.
                    fileSaver.deleteFiles(item.copy(filePath = null))
                }
                else -> fileSaver.deleteFiles(item)
            }
        }
        updateTransformed { daftar -> daftar.filterNot { it.id == id } }
        if (activeConns[id] == null) cancelledConns.remove(id)
        // Sama seperti cancel (issue #520): menghapus penerus monitor
        // menghidupkan lagi pemantauan pendahulu. Pause sengaja tidak
        // (penerus ter-pause + pendahulu hidup melahirkan penerus
        // duplikat di tick berikut).
        hidupkanLagiMonitorPendahulu(item)
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
            pendingRetryTokens.remove(it.id)
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
        // CANCELLED ikut (issue #556): sejajar grup seksi "Failed".
        val failed = _items.value.filter {
            it.state == DownloadState.FAILED || it.state == DownloadState.CANCELLED
        }
        failed.forEach { item ->
            retryAttempts.remove(item.id)
            pendingRetryTokens.remove(item.id)
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
        // Transformasi sejajar seleksi di atas (issue #566): dulu hanya
        // FAILED yang terhapus dari daftar, sehingga item CANCELLED
        // tertinggal selamanya padahal berkas parsialnya sudah dibersihkan.
        updateTransformed { daftar ->
            daftar.filterNot {
                it.state == DownloadState.FAILED || it.state == DownloadState.CANCELLED
            }
        }
        // Penerus monitor yang ikut terhapus massal (issue #530):
        // hidupkan lagi pemantauan pendahulunya, sama seperti
        // cancel/remove per item (#520) — membersihkan daftar gagal
        // tidak boleh membuang langganan pemantauan diam-diam.
        // Helper menghormati tanda sentuhan pengguna #521/#528.
        failed.forEach { hidupkanLagiMonitorPendahulu(it) }
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
     *  (bawaan: URL awal koneksi ini). Pemanggil meneruskan URL item
     *  sebagai jangkar agar kredensial tidak ikut ke host lain mana pun
     *  yang ditunjuk isi respons (issue #340). */
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
            // YouTube/Instagram CDN butuh Referer + Origin agar HTTP 403 tidak muncul.
            // Klasifikasi berbasis host ter-parse (issue #844): substring
            // atas URL utuh — terutama 'scontent' tanpa konteks host —
            // mengirim Referer platform ke host asing yang path/query-nya
            // kebetulan memuat string itu. Host scontent Instagram selalu
            // berawalan 'scontent' (scontent.fxxx.fna.fbcdn.net dst).
            val reqHost = urlHostOf(current)
            if (reqHost == "googlevideo.com" || reqHost.endsWith(".googlevideo.com") ||
                reqHost == "youtube.com" || reqHost.endsWith(".youtube.com")
            ) {
                conn.setRequestProperty("Referer", "https://www.youtube.com/")
                conn.setRequestProperty("Origin", "https://www.youtube.com")
            } else if (reqHost == "cdninstagram.com" || reqHost.endsWith(".cdninstagram.com") ||
                reqHost.startsWith("scontent")
            ) {
                conn.setRequestProperty("Referer", "https://www.instagram.com/")
                conn.setRequestProperty("Origin", "https://www.instagram.com")
            } else if (reqHost == "phncdn.com" || reqHost.endsWith(".phncdn.com") ||
                reqHost == "pornhub.com" || reqHost.endsWith(".pornhub.com")
            ) {
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
            // Koneksi hop ini belum lolos ke pemanggil: kegagalan
            // configure (tulis body POST) atau baca kode respons di
            // tengah hop wajib melepaskannya, kalau tidak soket
            // terhubungnya bocor menunggu timeout/GC (issue #1112).
            val code = try {
                configure(conn, current, effectiveMethod)
                conn.responseCode
            } catch (e: Exception) {
                conn.disconnect()
                throw e
            }
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

    /** [keepAutoResume] untuk jeda paksa SISTEM (issue #1218): jeda
     *  inten pengguna mencabut autoResume (semantik jeda permanen),
     *  tetapi timeout FGS bukan inten pengguna — item harus lanjut
     *  sendiri pada start berikutnya seperti jalur proses mati. */
    fun pauseAll(keepAutoResume: Boolean = false) {
        // Jeda paksa sistem adalah GANGGUAN BARU (issue #1295): segarkan
        // gerbang sekali-pakai resumeInterrupted. Tanpa ini, korban
        // timeout FGS di proses yang masih hidup tak pernah lanjut
        // sendiri — resumeInterrupted pada start berikutnya no-op karena
        // gerbangnya sudah terpakai di start pertama proses ini, dan
        // resumeAutoPaused hanya berjalan dari callback jaringan.
        // Aman: doResumeInterrupted hanya menghidupkan item ber-autoResume
        // yang masih PAUSED/PENDING/DOWNLOADING (cek segar di dalam kunci).
        if (keepAutoResume) interruptedResumed = false
        // Aksi massal wajib menunggu muatan awal (#264): menilai daftar
        // kosong di jendela muat membuat aksi pengguna batal diam-diam.
        if (!loadGate.isCompleted) {
            deferUntilLoaded("pauseAll") { doPauseAll(keepAutoResume) }
            return
        }
        doPauseAll(keepAutoResume)
    }

    private fun doPauseAll(keepAutoResume: Boolean = false) {
        val targets = _items.value.filter {
            it.state == DownloadState.DOWNLOADING || it.state == DownloadState.PENDING
        }
        if (targets.isEmpty()) return

        // Batch: satu salinan list + satu emisi StateFlow + satu penyimpanan,
        // bukan N kali update/save saat pengguna menekan Pause All.
        targets.forEach { item ->
            App.logEvent("DOWNLOAD PAUSED: ${item.fileName}")
            retryAttempts.remove(item.id)
            pendingRetryTokens.remove(item.id)
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
                        autoResume = keepAutoResume && item.autoResume,
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
            pendingRetryTokens.remove(item.id)
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
    fun moveUp(id: String): Boolean {
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
        // Nilai balik jujur (issue #935): false = no-op (sudah di
        // tepi), agar pemanggil tidak mengklaim berhasil pindah.
        val name = movedName ?: return false
        scheduleSave()
        App.logEvent("DOWNLOAD MOVED UP: $name")
        return true
    }

    /** Pindahkan item ke bawah antrean. */
    fun moveDown(id: String): Boolean {
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
        val name = movedName ?: return false
        scheduleSave()
        App.logEvent("DOWNLOAD MOVED DOWN: $name")
        return true
    }

    fun retryFailed() {
        // Aksi massal wajib menunggu muatan awal (#264, issue #863):
        // retryFailed adalah satu-satunya yang belum bergerbang —
        // ketukan dari halaman remote tepat sesudah proses restart
        // menilai daftar kosong dan hilang diam-diam.
        if (!loadGate.isCompleted) {
            deferUntilLoaded("retryFailed") { doRetryFailed() }
            return
        }
        doRetryFailed()
    }

    private fun doRetryFailed() {
        // Batch: satu salinan daftar + satu emisi StateFlow, bukan N kali
        // updateItem saat pengguna menekan Retry Failed (sama seperti pauseAll).
        // CANCELLED ikut (issue #556): grup seksi "Failed" di daftar
        // memang menampung FAILED + CANCELLED, jadi aksi massalnya
        // harus menjangkau isi grup yang sama.
        val ids = _items.value.filter {
            it.state == DownloadState.FAILED || it.state == DownloadState.CANCELLED
        }.map { it.id }
        if (ids.isEmpty()) return
        val idSet = ids.toSet()
        ids.forEach { id ->
            retryAttempts.remove(id)
            pendingRetryTokens.remove(id)
        }
        updateTransformed { daftar ->
            daftar.map { item ->
                // Cek ulang status SEGAR (pola #220): item FAILED dari
                // snapshot yang sudah di-resume manual lalu selesai
                // tidak boleh dipaksa PENDING lagi (unduh ulang ganda).
                val stillTarget = item.id in idSet &&
                    (item.state == DownloadState.FAILED ||
                        item.state == DownloadState.CANCELLED)
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
            pendingRetryTokens.remove(id)
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
        // Validator nama bersama (issue #1165, residu #1139): jalur
        // remote sudah menolak kontrol ISO + pemformat arah Unicode
        // lewat ServerSecurity.isFileNameValid, tetapi rename native
        // hanya memakai FileNames.safe (buang C0+DEL saja) sehingga
        // U+202E dkk. dan kontrol C1 tetap lolos ke nama tersimpan.
        // Satu definisi nama untuk semua jalur: tolak di sini juga.
        if (!ServerSecurity.isFileNameValid(clean)) return false
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
        // Dorong batas baru ke throttle attempt yang sedang berjalan
        // (issue #747): tanpa ini limit per-item baru berlaku sesudah
        // attempt berikutnya — tak seperti limit global yang langsung
        // berlaku. Bila limit per-item dikosongkan, throttle kembali ke
        // mode global (limiter bersama mengikuti prefs saat ini).
        activeThrottles[id]?.let { throttle ->
            if (speedLimitKbps > 0) {
                throttle.updateLimit(speedLimitKbps, null)
            } else {
                throttle.updateLimit(StoragePrefs.speedLimitKbps(context), globalRateLimiter())
            }
        }
    }

    fun setMonitor(id: String, enabled: Boolean) {
        if (_items.value.none { it.id == id }) return
        // Catat sentuhan eksplisit (issue #521): bila item ini sedang
        // menjadi pendahulu penerus yang berjalan, pemulihan otomatis
        // tidak boleh membatalkan keputusan terakhir pengguna ini.
        if (monitorSentuhanPengguna.size >= 256) monitorSentuhanPengguna.clear()
        monitorSentuhanPengguna.add(id)
        // Status dinilai dari item segar di dalam transform (pola
        // #220), bukan snapshot sebelum pemanggilan: status yang
        // berubah di antaranya membuat flag gagal terpasang pada item
        // yang baru COMPLETED (issue #882).
        updateItem(id) { it.copy(monitor = enabled && it.state == DownloadState.COMPLETED) }
        scheduleSave()
    }

    private fun shouldInvalidateResume(item: DownloadItem, currentEtag: String?): Boolean {
        if (item.bytesDownloaded <= 0 || currentEtag.isNullOrBlank()) return false
        // Provenans tak terverifikasi (issue #552): byte parsial
        // sudah ada tetapi etag tersimpan kosong — warisan versi
        // pra-#543 yang tak pernah merekam etag, atau item yang
        // baru pindah mirror (etag host asal dikosongkan saat
        // pindah). Perlakukan sebagai tak terpercaya: mulai segar
        // sekali ini agar byte lama tak terstempel buta sebagai
        // milik resource yang sedang diprobe.
        if (item.etag.isBlank()) return true
        // Batas yang diterima: bila server/probe tak mengirim ETag
        // sama sekali, perbandingan mustahil dan resume berjalan
        // tanpa verifikasi identitas (kondisi di atas sudah pulang
        // false lebih dulu untuk currentEtag kosong).
        return currentEtag != item.etag
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
            // CancellationException tak boleh tertelan runCatching
            // (issue #1132): scope yang dibatalkan harus berhenti di
            // putaran ini, bukan satu putaran terlambat.
            runCatching { checkForUpdates() }
                .onFailure { if (it is CancellationException) throw it }
        }
    }

    private suspend fun checkForUpdates() {
        val targets = _items.value.filter {
            it.monitor && it.state == DownloadState.COMPLETED &&
                // Skema tak peka huruf (issue #475): URL kapital yang
                // ditempel persis dari dialog tetap terpantau, selaras
                // pemeriksaan skema probeUrl yang ignoreCase.
                (it.url.startsWith("http://", ignoreCase = true) ||
                    it.url.startsWith("https://", ignoreCase = true))
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
                        // Mirror pendahulu ikut diwarisi (issue #519):
                        // tanpa ini unduhan pembaruan tak punya jalur
                        // cadangan yang dikonfigurasi pengguna untuk
                        // berkas yang sama.
                        mirrors = item.mirrors,
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
                        // Jendela penerus BARU dibuka (issue #528):
                        // tanda sentuhan #521 yang lebih tua dari
                        // jendela ini — termasuk tanda dari tindakan
                        // MENGAKTIFKAN monitor itu sendiri — harus
                        // gugur; yang dihitung hanya sentuhan SELAMA
                        // jendela penerus ini berjalan. Tanpa ini
                        // pemulihan #512/#518/#520 terblokir pada
                        // kegagalan penerus pertama.
                        monitorSentuhanPengguna.remove(item.id)
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

    /** Mengembalikan keberhasilan (issue #999): FileSaver.move
     *  menelan semua kegagalan menjadi null, jadi tanpa nilai
     *  kembalian ini pemanggil tak bisa memberi tahu pengguna bahwa
     *  pemindahan gagal dan berkas tetap di lokasi lama. */
    fun move(id: String, destTreeUri: Uri): Boolean {
        val item = _items.value.find { it.id == id } ?: return false
        if (item.state != DownloadState.COMPLETED) return false
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
            return true
        }
        return false
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
            if (pendingRetryTokens.containsKey(item.id)) continue
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
                    // Attempt mati terbatalkan: parkiran penggantian yang
                    // belum commit tak boleh yatim (issue #908) — jalur ini
                    // melewati handleFailure seluruhnya. No-op murah bila
                    // tak ada parkiran. Bergerbang pemilik (issue #911):
                    // attempt yang lebih baru untuk id yang sama memiliki
                    // parkirannya sendiri.
                    rollbackReplacementIfOwner(item.id, coroutineContext[Job])
                    throw e
                } catch (e: Throwable) {
                    // Error runtime (mis. NoSuchMethodError) diubah jadi status FAILED,
                    // bukan force close.
                    // Kegagalan yang muncul sesudah job dibatalkan (pause/cancel
                    // menutup soket -> SocketException) BUKAN kegagalan unduhan:
                    // tanpa konversi ini handleFailure bisa menimpa status
                    // PAUSED yang baru ditulis pause() (issue #743).
                    // Rollback parkiran SEBELUM konversi (issue #908):
                    // cabang konversi tak mencapai handleFailure, dan
                    // rollback di sana bersifat no-op murah. Bergerbang
                    // pemilik (issue #911) seperti jalur batal di atas.
                    if (!coroutineContext.isActive) {
                        rollbackReplacementIfOwner(item.id, coroutineContext[Job])
                        throw CancellationException()
                    }
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
            val b = backup
            if (b == null) {
                deletePublished(
                    FileSaver.PublishResult(
                        contentUri = predecessor.contentUri,
                        filePath = predecessor.filePath,
                        fileName = predecessor.fileName
                    )
                )
            } else {
                // Entri pendahulu + berkas cadangan DIPARKIR sekarang —
                // SEBELUM publish berjalan (issue #1408). Dulu parkiran
                // baru dipasang sesudah publish kembali, tetapi publish
                // menulis byte penerus tepat ke path pendahulu yang baru
                // dibebaskan dan salinan antarvolume bisa berjalan
                // berdetik-detik: remove() pendahulu di jendela itu tak
                // menemukan parkiran, menilai pendahulu item biasa, dan
                // menghapus berkas yang sedang ditulis penerus. Dengan
                // parkiran terpasang di sini, remove() jatuh ke jalur
                // parkiran #1287 (berkas terbit bersama tak tersentuh,
                // commit/rollback penerus menjadi no-op bersih).
                // Kegagalan publish melepaskan parkiran ini di catch di
                // bawah karena rollback-nya ditangani langsung di sana.
                pendingReplacements[item.id] =
                    PendingReplacement(predecessor, b, stale)
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
            // Publish gagal: rollback ditangani langsung di sini
            // (cadangan dipulihkan ke path tujuan di bawah), jadi
            // parkiran yang dipasang sebelum publish (issue #1408)
            // dilepas — tanpa ini rollbackReplacement pemanggil akan
            // menilai ulang parkiran yang cadangannya sudah berpindah
            // dan bisa menghapus berkas pendahulu yang baru dipulihkan.
            if (predecessor != null) pendingReplacements.remove(item.id)
            val b = backup
            val target = predecessor?.filePath
                ?.takeIf { it.isNotEmpty() }?.let { File(it) }
            if (b != null && target != null && !target.exists()) {
                runCatching { b.renameTo(target) }
            }
            stale?.let { s -> runCatching { s.delete() } }
            throw e
        }
        if (predecessor != null && backup == null) {
            // Pendahulu tanpa cadangan (murni MediaStore atau geser
            // gagal) sudah dihapus di muka; entrinya dipensiunkan di
            // sini. Awalnya jalur tanpa checksum memensiunkan versi lama
            // langsung juga untuk jalur bercadangan — tetapi pensiun di
            // sini mendahului penjaga keberadaan #881 sehingga menghapus
            // penerus di jendela publish tetap menghilangkan kedua versi
            // (issue #890). Jalur bercadangan tidak diutak-atik: entri +
            // cadangan tetap terparkir (dipasang sebelum publish, issue
            // #1408) sampai pemanggil mencapai commit/rollbackReplacement;
            // kelima situs finalisasi selalu mencapai salah satunya, dan
            // rekonsiliasi start proses menangani parkiran yang ditinggal
            // mati di antaranya.
            updateTransformed { daftar -> daftar.filterNot { it.id == predecessor.id } }
        }
        // Buang snapshot galeri server (issue #558): publishItem
        // adalah corong terbit semua jalur unduhan, dan galleryCache
        // server tak tersentuh notifikasi FileSaver/MediaLibrary.
        // Listing media + ZIP ikut dibuang (issue #660): keduanya hanya
        // terinvalidasi finalisasi upload, jadi unduhan biasa yang
        // selesai tak muncul di sana sampai TTL berakhir.
        App.invalidateServerDownloadCaches(result.filePath)
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
        // Monitor yang sama dengan keputusan parkiran di remove()
        // (issue #1377): ditahan dari pengambilan parkiran sampai
        // pendahulu terhapus dari daftar, agar remove() tak bisa menyela
        // di tengah dan salah vonis "parkiran tak ada" lalu menghapus
        // berkas penerus di path bersama.
        synchronized(pendingReplacements) {
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
    }

    /** Verifikasi penerus gagal: pulihkan berkas pendahulu (#215). */
    /**
     * Rollback parkiran dari jalur pembatalan sebuah attempt (issue #911):
     * hanya bila belum ada attempt yang LEBIH BARU terdaftar untuk id ini.
     * Attempt baru memiliki parkirannya sendiri di bawah kunci yang sama;
     * rollback buta dari unwind job lama bisa mengonsumsi entri attempt
     * baru (kasus terburuk tanpa checksum: commit attempt baru jadi
     * no-op sementara penerus ditandai COMPLETED berisi byte pendahulu).
     * Parkiran basi milik attempt lama yang dilewati di sini ditangani
     * attempt baru secara koheren lewat cabang aside di publishItem.
     */
    private fun rollbackReplacementIfOwner(successorId: String, self: Job?) {
        val registered = synchronized(jobs) { jobs[successorId] }
        if (registered == null || registered === self) {
            rollbackReplacement(successorId)
        }
    }

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
        if (!StoragePrefs.isAutoSortEnabled(context)) return result
        val organized = saver.organizeByType(result, fileName)
        // Relokasi ke subfolder jenis terjadi SESUDAH invalidasi ZIP
        // terarah publishItem (#668): ZIP subfolder tujuan adalah
        // keturunan folder terbit, bukan leluhur, jadi tak ikut terbuang
        // dan menyajikan isi basi sampai TTL-nya habis. Invalidasi ulang
        // dengan path final menutup celah itu (issue #674).
        val newPath = organized.filePath
        if (newPath != null && newPath != result.filePath) {
            App.invalidateServerDownloadCaches(newPath)
        } else if (organized.moved) {
            // Perpindahan berbasis contentUri (MediaStore/SAF): tak ada path
            // berkas untuk invalidasi terarah, dan contentUri MediaStore
            // bahkan tak berubah saat RELATIVE_PATH pindah — penanda moved
            // dari organizeByType yang memicu invalidasi global di sini
            // (issue #677).
            App.invalidateServerDownloadCaches(null)
        }
        return organized
    }

    // Penerus monitor yang berakhir FAILED terminal: hidupkan lagi
    // monitor pendahulunya (issue #512). Byte versi lama utuh
    // (rollback penggantian), jadi pemantauan bisa mencoba lagi di
    // tick berikut — tanpa ini monitor mati diam-diam: pendahulu
    // monitor=false sejak penerus dibuat, dan penerus yang tidak
    // COMPLETED tidak dinilai filter monitor siapa pun lagi.
    private fun hidupkanLagiMonitorPendahulu(item: DownloadItem) {
        // replacesItemId non-null (bawaan ""): penjaga kosong harus
        // eksplisit — Elvis pada String non-null tak pernah terpicu,
        // jadi item biasa dulu tetap mengalir ke updateItem("") yang
        // no-op (issue #1129).
        if (item.replacesItemId.isEmpty()) return
        val predId = item.replacesItemId
        // Pengguna menyentuh flag pendahulu selama jendela penerus ini
        // (issue #521): keputusan terakhirnya menang — jangan hidupkan
        // paksa. Tanda dikonsumsi agar jendela penerus berikutnya
        // dinilai segar.
        if (monitorSentuhanPengguna.remove(predId)) return
        updateItem(predId) { p ->
            if (p.state == DownloadState.COMPLETED && !p.monitor) {
                p.copy(monitor = true)
            } else {
                p
            }
        }
    }

    private fun handleFailure(id: String, message: String?) {
        // Jaring pengaman parkiran penggantian (issue #896) berjalan
        // SEBELUM penilaian item (issue #908): rollback tak bergantung
        // pada item, dan item yang sudah terhapus dari daftar (dihapus
        // pengguna selama verifikasi berjalan) tak boleh membuat
        // rollback terlewat — parkiran yatim bersifat permanen.
        // Jalur yang dirancang selalu commit/rollback sebelum melempar;
        // ini menangkap lemparan tak-terancang di jendela
        // publish->commit. No-op murah bila tak ada parkiran.
        rollbackReplacement(id)
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
        rememberFailedUrl(item.id, item.url)
        // YouTube CDN (googlevideo.com) menolak URL yang butuh n-signature dengan
        // HTTP 403 permanen — retry tidak akan pernah berhasil, jadi gagalkan
        // segera dengan pesan yang jelas (mirror/URL lain juga sia-sia).
        // Klasifikasi berbasis host (issue #843): substring atas URL
        // utuh menggagalkan permanen URL asing yang query-nya memuat
        // string itu padahal 403-nya bisa sesaat.
        val itemHost = urlHostOf(item.url)
        val ytSignatureBlocked = message?.contains("HTTP 403") == true &&
            (itemHost == "googlevideo.com" || itemHost.endsWith(".googlevideo.com") ||
                SocialMediaExtractor.isYouTubeUrl(item.url))
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
        // Item berkredensial/header kustom TIDAK mendapat auto-mirror
        // (issue #948): sesudah switch, item.url adalah URL proxy dan
        // anchor auth ikut berpindah ke sana, jadi username/password +
        // seluruh header kustom (Authorization/Cookie) akan terkirim ke
        // operator proxy publik yang tidak pernah dipilih pengguna.
        // Mirror manual tetap jalan: host-nya dipilih pengguna sendiri.
        val autoMirrors = if (item.mirrors.isEmpty() &&
            (isConnectError(message) || isSlowError(message)) && isGitHubUrl(item.url) &&
            item.username.isEmpty() && item.password.isEmpty() && item.headers.isBlank()
        ) {
            githubMirrors(item.url)
        } else {
            emptyList()
        }
        val allMirrors = (if (item.mirrors.isNotEmpty()) item.mirrors else autoMirrors)
            .filterNot { isFailedUrl(item.id, it) }
        // Error Range pasti gagal lagi di URL yang sama, dan mirror yang pernah
        // gagal tidak perlu dicoba ulang: langsung coba cadangan berikutnya.
        // Kegagalan HTTP per-URL (issue #544) ikut memicu: skenario utama
        // mirror adalah link mati di host asal, yang dulu tak pernah sampai
        // ke cabang ini karena pesan "HTTP <kode>" tak cocok klasifikasi mana pun.
        // Outage lokal tidak boleh membakar rantai mirror (issue #952):
        // error connect saat jaringan PERANGKAT sendiri mati bukan
        // kesalahan host mana pun — switch akan menghapus partial dan
        // menghabiskan mirror satu per satu. Lewati cabang ini dan
        // biarkan retry biasa (partial utuh) yang bekerja; NetworkCallback
        // melanjutkan unduhan saat koneksi pulih.
        // Klasifikasi lambat/macet ikut ditahan (issue #959): pesan
        // stall dari watchdog tak memuat substring connect mana pun,
        // tetapi pada jaringan menetes ia bisa tiba saat bendera
        // konektivitas sudah turun — menahannya di sini tak mengubah
        // perilaku apa pun saat jaringan perangkat naik.
        val outageLocal = (isConnectError(message) || slowRejected) && !App.networkUp
        if (allMirrors.isNotEmpty() && !outageLocal && (rangeRejected || slowRejected || isConnectError(message) ||
                isHttpMirrorFailoverError(message))
        ) {
            val next = allMirrors.first()
            App.logEvent("DOWNLOAD FAILED: ${item.fileName} — ${message ?: "?"} (switching to mirror: $next)")
            // Ketidakpercayaan pada byte host asal ditegakkan SEKARANG
            // (issue #572): dulu ia digantungkan ke probe mirror lewat
            // aturan provenans shouldInvalidateResume, tetapi gerbang
            // fungsi itu pulang false lebih dulu bila mirror tidak
            // mengirim ETag — byte lama lalu terpakai buta terhadap
            // resource mirror. Buang state parsial di tempat, persis
            // efek invalidateChangedResume.
            fileSaver.partialFiles(item).forEach { runCatching { it.delete() } }
            clearSegProgress(id)
            updateItem(id) {
                it.copy(
                    url = next,
                    mirrors = allMirrors.drop(1),
                    state = DownloadState.PENDING,
                    error = null,
                    autoResume = true,
                    bytesDownloaded = 0,
                    totalBytes = 0,
                    segments = emptyList(),
                    speedBps = 0,
                    etaSeconds = 0,
                    // Etag host asal tak berlaku di host baru (issue
                    // #552): kosongkan; byte parsial host asal sudah
                    // dibuang di atas (issue #572) sehingga resume di
                    // mirror selalu mulai segar.
                    etag = ""
                )
            }
            retryAttempts.remove(id)
            // Jeda 1,5 dtk WAJIB didaftarkan sebagai token retry (issue
            // #1372): cabang ini berjalan di dalam job attempt yang gagal,
            // dan begitu handleFailure kembali, invokeOnCompletion langsung
            // memanggil startQueued — tanpa token, item PENDING ini mulai
            // seketika dan launch tertunda menjadi no-op. Pola generasi
            // sama dengan retry biasa (#865): token hanya dicabut oleh
            // jadwal yang mendaftarkannya.
            val mirrorToken = retryTokenSeq.incrementAndGet()
            pendingRetryTokens[id] = mirrorToken
            scope.launch {
                delay(1_500)
                if (pendingRetryTokens.remove(id, mirrorToken) &&
                    _items.value.find { it.id == id }?.state == DownloadState.PENDING
                ) {
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
            // Cabang terminal ketiga (issue #518): pemulihan monitor
            // #512 dulu hanya terpasang di dua cabang terminal lain —
            // penerus yang gagal di sini (mis. resume ditolak 416 oleh
            // server yang isinya berubah) membunuh monitor diam-diam
            // dengan cara yang sama.
            hidupkanLagiMonitorPendahulu(item)
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
            val retryToken = retryTokenSeq.incrementAndGet()
            pendingRetryTokens[id] = retryToken
            scope.launch {
                delay(backoff)
                // remove(id, token) atomik: hanya bertindak bila jadwal
                // ini masih yang terdaftar (issue #865).
                if (pendingRetryTokens.remove(id, retryToken) &&
                    _items.value.find { it.id == id }?.state == DownloadState.PENDING
                ) {
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
            message.contains("connection stalled", ignoreCase = true) ||
            // Varian zero-read tulisan tangan di jalur adaptive
            // (issue #971): "adaptive file stalled (zero reads)" tidak
            // memuat frasa di atas, jadi klasifikasinya timpang antarjalur.
            message.contains("stalled (zero reads)", ignoreCase = true)
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
        // Veto refused (issue #988): pesan refused sungguhan di
        // Android berbentuk komposit ("failed to connect to ...
        // ECONNREFUSED (Connection refused)") yang MEMUAT frasa
        // positif "failed to connect" — tanpa veto yang didahulukan
        // ini, niat #976 (refused = FAILED terminal) kalah oleh
        // pencocokan substring dan item berakhir PAUSED abadi.
        if (message.contains("connection refused", ignoreCase = true)) return false
        return message.contains("unknownhost", ignoreCase = true) ||
            message.contains("timeout", ignoreCase = true) ||
            message.contains("failed to connect", ignoreCase = true) ||
            message.contains("connect exception", ignoreCase = true) ||
            message.contains("network", ignoreCase = true) ||
            message.contains("socket", ignoreCase = true) ||
            // Badan terpotong di tengah jalan (EOF prematur) adalah gangguan
            // jaringan transien: pesan "truncated" dari unduhan berkas
            // wajib lolos klasifikasi ini agar retry benar-benar
            // berjalan (issue #167).
            message.contains("truncated", ignoreCase = true) ||
            // Stall/lambat dan frasa connect yang TRANSIEN LOKAL
            // (issue #969, disaring issue #976): klasifikasi ini
            // menentukan PAUSED-vs-FAILED terminal dan retry level
            // segmen — kegagalan jaringan menetes (watchdog stall)
            // dan DNS ("Unable to resolve host") sebelumnya lolos
            // dari keduanya dan berakhir FAILED permanen. Delegasi
            // utuh ke isConnectError TIDAK dipakai: ia ikut memuat
            // "connection refused", penolakan definitif sisi server
            // yang harus tetap FAILED terminal, bukan PAUSED abadi
            // yang bangkit lagi tiap jaringan berkedip.
            isSlowError(message) ||
            message.contains("unable to resolve host", ignoreCase = true) ||
            message.contains("network is unreachable", ignoreCase = true) ||
            // Frasa transien yang lolos dari semua pola di atas
            // (issue #987): "connect timed out" tak memuat "timeout"
            // ("timed" != "timeout") dan ikut tersapu penyaringan
            // #976; "connection reset" (putus di tengah aliran) dan
            // "broken pipe" (sisi tulisnya) tak memuat satu pun
            // substring klasifier — ketiganya kegagalan jaringan
            // sesaat yang harus berakhir PAUSED, bukan FAILED.
            message.contains("connect timed out", ignoreCase = true) ||
            message.contains("connection reset", ignoreCase = true) ||
            message.contains("broken pipe", ignoreCase = true) ||
            // Pola umum saudara frasa di atas (issue #1002): "timed
            // out" telanjang mencakup Read/Connection timed out dan
            // pesan sintetis repo ("Playlist fetch timed out") yang
            // tak memuat "timeout"; "connection abort" adalah pesan
            // baku ECONNABORTED, pasangan reset dari sisi penerima.
            message.contains("timed out", ignoreCase = true) ||
            message.contains("connection abort", ignoreCase = true)
    }

    private suspend fun runDownload(item: DownloadItem, skipSocial: Boolean = false) {
        // Social media: ekstrak direct URL menggunakan API publik
        // (tikwm.com untuk TikTok, embed page JSON untuk Instagram, vxtwitter untuk Twitter)
        // skipSocial=true dipakai saat URL sudah hasil ekstraksi (mis. URL CDN
        // hasil ekstraksi media sosial) — jangan di-extract ulang karena bisa salah deteksi.
        if (!skipSocial && SocialMediaExtractor.isSocialMediaUrl(item.url)) {
            val host = runCatching { java.net.URL(item.url).host }.getOrDefault("?")
            App.logEvent("SOCIAL: extracting direct URL from $host ...")
            val result = SocialMediaExtractor.extract(item.url, item.headers)
            if (result != null && result.directUrl != item.url) {
                // URL direct bertanda tangan TIDAK dicatat (issue #951):
                // log server bisa diekspor ke TXT publik dan kebijakan
                // repo menyamakan URL bertanda tangan dengan rahasia di
                // log (LOG_SECRET_RE). Host hasil ekstraksi cukup.
                val directHost = runCatching { java.net.URL(result.directUrl).host }.getOrDefault("?")
                App.logEvent("SOCIAL: extracted direct URL from $host → $directHost")
                App.logEvent("SOCIAL: fileName=${result.fileName}, cookies=${result.cookies.length} chars")
                // Simpan URL sosial media original untuk semua hasil ekstraksi:
                // URL CDN langsung bertanda-tangan (mis.
                // xvideos-cdn) bisa kedaluwarsa di tengah unduhan — retry butuh
                // re-extract URL segar dari halaman asli agar progres puluhan
                // MB tidak dibuang (lihat refreshExpiredCdnUrl).
                originalSocialUrls[item.id] = item.url
                // Jalur berkas langsung / pasangan adaptif dari hasil ekstraksi.
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
        // Daftarkan throttle attempt ini (issue #747); dicabut
        // berpasangan-nilai saat job attempt ini selesai.
        activeThrottles[item.id] = throttle
        coroutineContext[Job]?.invokeOnCompletion { activeThrottles.remove(item.id, throttle) }

        // Hasil HEAD blok segmen disimpan untuk dipakai ulang jalur segar
        // (issue #1373): diisi hanya bila probe segmen menjawab 2xx.
        var segFreshHeaders: ServerHeaders? = null
        var segFreshTotal = -1L
        var segFreshRanges = false
        if (item.segments.isNotEmpty()) {
            // Verifikasi identitas resource dulu (issue #543): jalur
            // resume segmen dulu langsung menggabung segmen lokal
            // tanpa pernah bertanya ke server — bila berkas di URL
            // yang sama sudah diganti penerbit, segmen lama + baru
            // tercampur menjadi berkas korup berstatus COMPLETED.
            val segProbe = openAuthenticatedConnection(
                item.url, method = "HEAD",
                username = item.username, password = item.password, headers = item.headers
            )
            var segHeaders: ServerHeaders? = null
            try {
                if (segProbe.responseCode in 200..299) {
                    val h = headersOf(segProbe)
                    segHeaders = h
                    captureHeaderChecksum(item, segProbe)
                    segFreshHeaders = h
                    segFreshTotal = contentLength(segProbe)
                    segFreshRanges = segProbe.getHeaderField("Accept-Ranges")
                        .equals("bytes", ignoreCase = true)
                    invalidateChangedResume(_items.value.find { it.id == item.id } ?: item, h.etag)
                    val probedEtag = h.etag?.takeIf { it.isNotBlank() }
                    if (probedEtag != null) {
                        updateItem(item.id) { it.copy(etag = probedEtag) }
                    }
                }
            } catch (_: Exception) { // audit-ignore: file_scoped_catch_all, kotlin_empty_catch (HEAD tak didukung adalah hasil sah: lanjut resume apa adanya)
                // HEAD tidak didukung; lanjut resume apa adanya.
            } finally {
                segProbe.disconnect()
            }
            val currentSeg = _items.value.find { it.id == item.id } ?: item
            if (currentSeg.segments.isNotEmpty()) {
                // segHeaders diteruskan agar etag hasil probe tidak
                // terhapus lagi oleh penulisan penyelesaian runSegmented.
                runSegmented(currentSeg, saver, throttle, currentSeg.totalBytes, segHeaders)
                return
            }
            // Resource berubah dan segmen sudah dibatalkan oleh
            // invalidateChangedResume: jatuh ke jalur segar di bawah.
        }

        var useSegments = false
        var segmentedTotal = 0L
        var probeHeaders: ServerHeaders? = null
        var freshTotal = -1L
        var freshRanges = false
        if (segFreshHeaders != null) {
            // HEAD kedua dilewati (issue #1373): segmen dibatalkan oleh
            // invalidateChangedResume sesudah resource berubah, tetapi
            // hasil HEAD segmen di atas BARU saja dibaca pada attempt dan
            // URL yang sama (item.url tak berubah di antara kedua blok),
            // jadi etag/total/Accept-Ranges/checksum-nya identik dengan
            // yang akan dibaca ulang — membuka HEAD kedua hanya membuang
            // satu RTT + koneksi terautentikasi (dengan redirect penuh).
            probeHeaders = segFreshHeaders
            freshTotal = segFreshTotal
            freshRanges = segFreshRanges
        } else {
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
                    freshTotal = contentLength(probe)
                    freshRanges = probe.getHeaderField("Accept-Ranges")
                        .equals("bytes", ignoreCase = true)
                }
            } catch (_: Exception) { // audit-ignore: file_scoped_catch_all, kotlin_empty_catch (HEAD tak didukung adalah hasil sah: lanjut dengan GET biasa)
                // HEAD tidak didukung; lanjut dengan GET biasa
            } finally {
                probe.disconnect()
            }
        }
        val freshHeaders = probeHeaders
        if (freshHeaders != null) {
            // Invalidasi ditelan bila melempar (issue #1424, memulihkan
            // perilaku pra-#1373): #1373 memindahkan pemanggilan ini ke
            // luar try probe agar hasil probe bisa dipakai ulang, tetapi
            // pengecualian non-pembatalan dari badan invalidasi kini
            // menggagalkan attempt dan masuk retry, padahal dulu
            // tertelan dan unduhan segar berlanjut. CancellationException
            // tetap dilempar (pola #1132).
            try {
                invalidateChangedResume(_items.value.find { it.id == item.id } ?: item, freshHeaders.etag)
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) { // audit-ignore: file_scoped_catch_all, kotlin_empty_catch (invalidasi gagal tak boleh menggagalkan attempt)
                // Lanjut dengan jalur segar di bawah, seperti pra-#1373.
            }
            // Rekam ETag probe ke item SEKARANG (issue #543):
            // dulu etag hanya ditulis saat COMPLETED, jadi selama
            // unduhan pertama berjalan penjaga invalidateChangedResume
            // tidak pernah bersenjata (etag tersimpan selalu kosong)
            // dan resume pertama selalu lolos tanpa perbandingan.
            val probedEtag = freshHeaders.etag?.takeIf { it.isNotBlank() }
            if (probedEtag != null) {
                updateItem(item.id) { it.copy(etag = probedEtag) }
            }
            // Mirror (proxy GitHub) umumnya tidak mendukung Range, jadi
            // lewati multi-segmen untuk URL cadangan. Item POST juga
            // dilewati: segmen selalu GET dan tak pernah mengirim
            // postBody, jadi berkas hasilnya bukan respons POST —
            // POST tetap jalur tunggal yang menghormati method+body
            // (issue #1130).
            if (freshRanges && freshTotal >= SEGMENT_MIN_BYTES &&
                StoragePrefs.segmentCount(context) > 1 && item.mirrors.isEmpty() &&
                item.method != "POST"
            ) {
                useSegments = true
                segmentedTotal = freshTotal
            }
        }
        coroutineContext.ensureActive()

        if (useSegments) {
            // Item segar dari store (issue #563): invalidateChangedResume
            // pada probe di atas bisa sudah mengosongkan segmen + totalBytes;
            // snapshot item masih membawa tata segmen lama sehingga
            // runSegmented akan melewatkan cabang pembuatan segmen
            // (satu-satunya penulis totalBytes) dan verifySize jadi buta.
            val freshItem = _items.value.find { it.id == item.id } ?: item
            runSegmented(freshItem, saver, throttle, segmentedTotal, probeHeaders)
            return
        }

        // Offset resume dihitung dulu (sekaligus truncate/selaraskan file
        // parsial): header Range harus sudah terpasang saat koneksi dibuka.
        val resumeFrom = prepareResumeOffset(item, saver)
        // Berkas sudah utuh menurut catatan (issue #650): membuka koneksi
        // dengan Range bytes=<total>- hanya memancing 416 yang divonis
        // FAILED terminal oleh penangan Range-reject, padahal yang tersisa
        // hanya verifikasi + terbitkan — lewati koneksi sepenuhnya, pola
        // yang sama dengan gerbang segmen utuh #630.
        if (isResumeAlreadyComplete(resumeFrom, item.totalBytes)) {
            App.logEvent(
                "DOWNLOAD ${item.fileName}: partial already complete " +
                    "(${Formats.bytes(resumeFrom)}) - finalizing without connection"
            )
            finishSingle(
                item, saver, saver.partialFile(item.fileName), item.fileName,
                resumeFrom, item.totalBytes, ""
            )
            return
        }
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

    private class MuxProgress {
        // Tiga penghitung ini disentuh thread unduh dan callback progres
        // secara bersamaan (issue #1374): baca-tulis
        // Long polos bisa kehilangan imbuhan dan check-then-set lastNotify
        // bisa lolos ganda, jadi atomik. Field lain hanya disentuh thread
        // konsumen/pemanggil sehingga tetap polos.
        val downloaded = AtomicLong(0)
        val lastNotify = AtomicLong(0)
        var totalSegments = 0
        var segmentsDone = 0
        var avgSegBytes = 0L
        /** Jumlah Content-Length pasti segmen yang sudah di-fetch (mutlak). */
        var exactBytes = 0L
        /** Ukuran segmen referensi stabil (dari segmen pertama yang berukuran wajar). */
        val refSegBytes = AtomicLong(0)
        /** Total mutlak akhir bila semua segmen sudah punya ukuran pasti; else estimasi. */
        var exactTotal = 0L
        /** Estimasi awal total dari BANDWIDTH x durasi (dipakai sebelum segmen
         *  selesai — exactTotal masih 0). */
        var estimateTotalBytes = 0L
    }

    /** Unduh satu file adaptive (audio M4A / video MP4) dari URL VISIONOS ke
     *  file temp. Mengembalikan false bila HTTP gagal atau file kosong. */
    private suspend fun downloadAdaptiveFile(
        item: DownloadItem,
        url: String,
        target: File,
        buffer: ByteArray,
        throttle: SpeedThrottle,
        progress: MuxProgress
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
            if (code !in 200..299) {
                // Badan galat tak dibaca (issue #1243): soket tak bisa
                // kembali ke pool keep-alive — putuskan eksplisit.
                runCatching { conn.disconnect() }
                return false
            }
            val total = contentLength(conn)
            // Anggaran byte sadar ruang kosong (issue #702): plafon
            // lama 512MB mentah menolak video adaptif panjang yang sah
            // padahal ruang sehat. Batas kini mengikuti ruang bebas
            // volume target dikurangi cadangan MIN_FREE_BYTES, dan
            // Content-Length yang dikenal dicek di muka dengan pesan
            // ruang yang jelas — bukan lagi "too large" yang keliru.
            val freeAtStart = target.absoluteFile.parentFile?.usableSpace ?: Long.MAX_VALUE
            if (total > 0 && total > freeAtStart - MIN_FREE_BYTES) {
                throw IOException(
                    "Not enough storage for adaptive file: need " +
                        "${Formats.bytes(total)}, available ${Formats.bytes(freeAtStart)}"
                )
            }
            // Volume TUJUAN terbit ikut dicek di muka (issue #1160):
            // tiga jalur lain memakai destinationFreeBytes lintas
            // volume, jalur adaptive dulu hanya volume temp — folder
            // tujuan di volume lain (SD card) yang sempit baru ketahuan
            // ENOSPC sesudah unduh+remux tuntas, dan berkas adaptive
            // tak bisa di-resume sehingga retry mengulang dari nol.
            if (total > 0) {
                val destFree = fileSaver.destinationFreeBytes(item.folderPath)
                if (total > destFree - MIN_FREE_BYTES) {
                    throw IOException(
                        "Not enough storage at destination for adaptive file: need " +
                            "${Formats.bytes(total)}, available ${Formats.bytes(destFree)}"
                    )
                }
            }
            val byteCap = (freeAtStart - MIN_FREE_BYTES).coerceAtLeast(0)
            BufferedOutputStream(FileOutputStream(target), BUFFER_SIZE).use { out ->
                val input = conn.inputStream
                try {
                    var bytes = 0L
                    var zeroStreak = 0
                    // Watchdog macet/lambat seperti tiga jalur streaming
                    // lain (issue #597): tanpa ini, koneksi yang menetes
                    // di berkas adaptive (bisa ratusan MB untuk muxing)
                    // tak pernah divonis — hanya guard zero-read dan
                    // read-timeout per-baca yang membatasi.
                    val health = DownloadHealthWatchdog(
                        if (item.speedLimitKbps > 0) item.speedLimitKbps else StoragePrefs.speedLimitKbps(context)
                    )
                    var adaptiveIters = 0
                    var lastAdaptiveCheckBytes = 0L
                    var lastAdaptiveCheckAt = monotonicNow()
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
                        if (bytes > byteCap) {
                            throw IOException("adaptive file exceeds available storage")
                        }
                        out.write(buffer, 0, read)
                        throttle.sleepIfNeeded { progress.downloaded.get() + bytes }
                        reportMuxProgress(item, progress, bytes, total)
                        if ((adaptiveIters++ and 7) == 0) {
                            val now = monotonicNow()
                            if (now - lastAdaptiveCheckAt >= 1000) {
                                val speed = if (now > lastAdaptiveCheckAt) {
                                    ((bytes - lastAdaptiveCheckBytes) * 1000L) / (now - lastAdaptiveCheckAt)
                                } else 0L
                                lastAdaptiveCheckBytes = bytes
                                lastAdaptiveCheckAt = now
                                health.check(now, bytes, total, speed)
                            }
                        }
                    }
                } finally {
                    runCatching { input.close() }
                }
            }
            // Verifikasi panjang terhadap Content-Length (issue #651),
            // pola yang sama dengan verifikasi segmen (#170): EOF prematur
            // pada respons chunked bisa tiba tanpa exception sehingga
            // berkas terpotong lolos sebagai sukses — audio adaptive cukup
            // >= 1000 byte untuk ikut ter-remux dan terbit COMPLETED.
            // Hanya kekurangan byte yang digagalkan; kelebihan ditoleransi
            // karena klaim Content-Length bisa basi (gzip/proxy).
            val writtenBytes = target.length()
            if (total > 0 && writtenBytes < total) {
                throw IOException("adaptive file truncated: got $writtenBytes of $total bytes")
            }
            progress.downloaded.addAndGet(target.length())
            return target.length() > 0L
        } catch (e: Exception) {
            // Jalur galat (issue #1243): badan respons belum tentu
            // terbaca habis, jadi soket tak layak pool — putuskan
            // sebelum lepas. Sukses penuh tak diputus (keep-alive).
            runCatching { conn.disconnect() }
            throw e
        } finally {
            untrackConnection(item.id, conn)
        }
    }

    /** Unduh video MP4 + audio M4A adaptive lalu remux jadi satu MP4 bersuara.
     *  Dipakai untuk hasil ekstraksi berpasangan video+audio terpisah. */
    private suspend fun downloadAdaptiveMuxed(
        item: DownloadItem,
        adaptiveVideoUrl: String,
        adaptiveAudioUrl: String,
        outName: String
    ) {
        val saver = fileSaver
        val baseName = buangSufiksEkstensi(outName, ".mp4", ".webm").ifBlank { outName }
        // Temp unik per item lewat potongan id di DEPAN, karena baseName
        // tanpa ekstensi bertabrakan antar item dan pembeda di ekor bisa
        // terpotong untuk nama sangat panjang (#171).
        val tempBase = "~" + item.id.take(8) + baseName
        val videoMp4 = saver.partialFile("$tempBase.videoMp4", segment = null).apply { delete() }
        val audioM4a = saver.partialFile("$tempBase.audioM4a", segment = null).apply { delete() }
        val mp4 = saver.partialFile("$tempBase.muxed", segment = null).apply { delete() }
        val progress = MuxProgress()
        val buffer = ByteArray(BUFFER_SIZE)
        val globalLimit = StoragePrefs.speedLimitKbps(context)
        val limit = if (item.speedLimitKbps > 0) item.speedLimitKbps else globalLimit
        val throttle = if (item.speedLimitKbps > 0) {
            SpeedThrottle(limit, null)
        } else {
            SpeedThrottle(limit, globalRateLimiter())
        }
        // Daftarkan throttle attempt ini (issue #747); dicabut
        // berpasangan-nilai saat job attempt ini selesai.
        activeThrottles[item.id] = throttle
        coroutineContext[Job]?.invokeOnCompletion { activeThrottles.remove(item.id, throttle) }
        try {
            val videoOk = downloadAdaptiveFile(item, adaptiveVideoUrl, videoMp4, buffer, throttle, progress)
            if (!videoOk) throw IOException("adaptive video download failed")
            val audioOk = downloadAdaptiveFile(item, adaptiveAudioUrl, audioM4a, buffer, throttle, progress)
            if (!audioOk) throw IOException("adaptive audio download failed")
            coroutineContext.ensureActive()
            val remuxed = AdaptiveMp4Muxer.remuxMp4s(videoMp4, audioM4a, mp4)
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
            ensurePresentForCommit(item.id, published0)
            commitReplacement(item.id)
            val published = organizeIfEnabled(saver, published0, finalName)
            ensurePublishedKept(item.id, published0, published)
            finishMuxed(item, progress.downloaded.get(), published, finalName)
        } catch (e: Exception) {
            runCatching { videoMp4.delete() }
            runCatching { audioM4a.delete() }
            runCatching { mp4.delete() }
            // Pause membatalkan job + menutup soket: SocketException dari
            // downloadAdaptiveFile harus jadi pembatalan, bukan kegagalan
            // (pola runSingle/downloadSegment; issue #743).
            if (e is IOException && !coroutineContext.isActive) throw CancellationException()
            throw e
        }
    }

    private fun reportMuxProgress(
        item: DownloadItem,
        progress: MuxProgress,
        segNow: Long,
        total: Long = 0
    ) {
        val totalNow = progress.downloaded.get() + segNow
        val now = monotonicNow()
        val lastNotifySeen = progress.lastNotify.get()
        if (now - lastNotifySeen >= 1000 &&
            progress.lastNotify.compareAndSet(lastNotifySeen, now)
        ) {
            // Total akhir tidak selalu diketahui sejak awal. Persentase
            // dihitung dari jumlah segmen (progressPercentOverride)
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
                progress.downloaded.get() + total
            } else {
                // Pakai total pasti (Content-Length bagian selesai +
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

    private fun finishMuxed(
        item: DownloadItem,
        downloaded: Long,
        published: FileSaver.PublishResult,
        finalName: String
    ) {
        speedTracker.reset(item.id)
        // Entri retry/sampel kecepatan item yang selesai ikut dibuang
        // (issue #574): dulu hanya dibersihkan di jalur jeda/batal/
        // gagal, sehingga menetap seumur proses untuk item COMPLETED.
        retryAttempts.remove(item.id)
        lastSpeedSample.remove(item.id)
        // Ukuran final dari berkas terbit sebenarnya bila terukur
        // (issue #348): jumlah byte segmen MPEG-TS bukan ukuran MP4
        // hasil remux (beda header MP4 dan padding TS), jadi memakai
        // angka segmen membuat ukuran tampil tidak sama dengan berkas
        // aslinya. Terbit MediaStore/SAF tanpa path lokal terukur
        // jatuh kembali ke jumlah byte segmen.
        val actualSize = published.filePath?.let { path ->
            runCatching { File(path).takeIf { it.isFile }?.length() }.getOrNull()
        }?.takeIf { it > 0 } ?: downloaded
        val finishedMux = updateItem(item.id) {
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
        }
        flushSave()
        // Cookie sesi dari unduhan termux ikut dipersists (issue #391):
        // sebelumnya hanya jalur single-stream yang memanggil ini.
        persistCookies()
        if (finishedMux != null) NotificationHelper.notifyItemFinished(context, finishedMux)
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

    /** Seperti ensurePublishedKept tetapi untuk berkas mentah unduhan
     *  SEBELUM publish (issue #1038): progres segmen sudah menyatu di
     *  berkas .part (dulu: hasil merge yang sudah menghapus semua parts),
     *  jadi ensureActive() di jendela finalisasi->publish membuat pause
     *  membuang seluruh progres 100% — berkas utuh tertinggal yatim tak
     *  terpakai resume segmen dan tiap segmen kena RESTART.
     *  Finalisasi dituntaskan bila item masih ada (pola #780); item hilang
     *  tetap membuang scrap + batal, persis ensureItemPresent. */
    private fun ensureItemKept(id: String, vararg scrap: File) {
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
        if (_items.value.none { it.id == id }) {
            published.forEach { deletePublished(it) }
            throw CancellationException("item $id removed after publish")
        }
        // Item masih ada tetapi job dibatalkan = pause yang mendarat di
        // jendela finalisasi (issue #780): berkas SUDAH terbit penuh dan
        // partial sudah berpindah, jadi tidak ada sisa yang bisa di-resume.
        // Finalisasi dituntaskan (item menjadi COMPLETED — sama seperti
        // pause yang mendarat sesudah langkah ini) alih-alih melempar
        // ensureActive() di sini, yang meninggalkan berkas terbit yatim
        // tak terujuk dan memaksa resume mengunduh ulang dari nol.
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
        var rangeBudget: Long? = null
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
            rangeBudget = singleStreamRangeBudget(code, crTotal, downloaded)
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
                // Resume 206 bertotal Content-Range otoritatif: pangkas
                // tulis ke sisa rentang dan berhenti bersih saat rentang
                // habis — byte ekstra server tak boleh menempel ke berkas
                // final (issue #1369). Tanpa budget (klaim Content-Length)
                // tulis penuh seperti sebelumnya.
                val budget = rangeBudget
                val toWrite = if (budget == null) read else cappedSegmentWrite(read, budget)
                output.write(buffer, 0, toWrite)
                downloaded += toWrite
                if (budget != null) {
                    rangeBudget = budget - toWrite
                    if (rangeBudget <= 0L) break
                }
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
                    // Penjaga ruang berjalan (issue #781): jalur single-stream
                    // dulunya satu-satunya jalur streaming tanpa anggaran —
                    // respons chunked melewati cek pra-unduh (total=0) dan
                    // overrun ditoleransi verifySize, jadi loop menulis
                    // sampai disk benar-benar habis. Pola pra-terbang:
                    // berhenti begitu ruang volume partial menyentuh cadangan.
                    // freeBytesOrNull (issue #810): kegagalan StatFs
                    // sesaat tak boleh terbaca ruang 0 — unduhan sehat
                    // bisa dibatalkan palsu 'Storage almost full'.
                    // Baca gagal = lewati cek tick ini; kesalahan tulis
                    // sungguhan tetap muncul dari jalur tulisnya sendiri.
                    val freeNow = saver.freeBytesOrNull()
                    if (freeNow != null && freeNow < MIN_FREE_BYTES) {
                        throw IOException("Storage almost full (free ${Formats.bytes(freeNow)})")
                    }
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

        finishSingle(item, saver, partialFile, fileName, downloaded, total, conn.getHeaderField("ETag").orEmpty())
        } catch (e: IOException) {
            if (!coroutineContext.isActive) throw CancellationException()
            throw e
        } finally {
            untrackConnection(item.id, conn)
        }
    }


    /** Finalisasi unduhan single-stream: verifikasi ukuran -> terbitkan ->
     *  checksum -> tandai COMPLETED. Dipakai runSingle sesudah streaming dan
     *  jalur berkas-sudah-utuh issue #650 (parsial lengkap dari catatan:
     *  koneksi dilewati agar server tak menjawab 416 yang divonis FAILED
     *  terminal oleh penangan Range-reject). */
    private suspend fun finishSingle(
        item: DownloadItem,
        saver: FileSaver,
        partialFile: File,
        fileName: String,
        downloaded: Long,
        total: Long,
        serverEtag: String
    ) {
        ensureItemPresent(item.id, partialFile)
        verifySize(downloaded, total, fileName)

        val published0 = publishItem(saver, partialFile, fileName, item)
        val finalName = published0.fileName ?: fileName
        verifyChecksum(item.id, published0, saver)?.let {
            deletePublished(published0)
            rollbackReplacement(item.id)
            throw IOException(it)
        }
        ensurePresentForCommit(item.id, published0)
        commitReplacement(item.id)
        val published = organizeIfEnabled(saver, published0, finalName)
        ensurePublishedKept(item.id, published0, published)
        speedTracker.reset(item.id)
        // Sama seperti finishMuxed (issue #574).
        retryAttempts.remove(item.id)
        lastSpeedSample.remove(item.id)
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
                // Respons akhir tanpa ETag tidak menghapus etag yang
                // sudah terstempel probe (issue #573): monitor perubahan
                // berbasis etag buta untuk item yang etag-nya terhapus.
                etag = serverEtag.ifBlank { it.etag },
                finishedAt = System.currentTimeMillis()
            )
        }
        flushSave()
        persistCookies()
        App.logEvent("DOWNLOAD COMPLETED: $finalName (${Formats.bytes(downloaded)})")
        if (finishedSingle != null) NotificationHelper.notifyItemFinished(context, finishedSingle)
    }

    /** Siapkan berkas tunggal pra-alokasi untuk jalur segmen (issue #1427)
     *  dan kembalikan segmen yang catatannya mungkin terkoreksi:
     *  - Part warisan per-segmen (`.part.<idx>`, model pra-#1427) dimigrasi
     *    sekali: byte tiap part disalin ke offset segmennya di berkas
     *    tunggal, catatan segmen yang melebihi isi part dipangkas ke isi
     *    part (pola truncate-to-record lama), lalu part dihapus.
     *  - Ada catatan progres tetapi berkas tunggal hilang atau panjangnya
     *    tak sama dengan total: isi berkas tak bisa dipercaya per-wilayah,
     *    jadi catatan di-nol-kan dan berkas dibuat ulang — menerbitkan
     *    berkas berlubang (nol pra-alokasi) berstatus COMPLETED jauh lebih
     *    buruk daripada mengulang unduhan.
     *  - Panjang berkas selalu dinormalkan ke total (setLength) agar
     *    berkas yang diterbitkan persis sebesar total, sama seperti hasil
     *    merge lama. */
    private fun prepareSegmentedFile(
        id: String,
        fileName: String,
        segments: List<DownloadSegment>,
        total: Long,
        saver: FileSaver
    ): List<DownloadSegment> {
        val unified = saver.partialFile(fileName)
        val segTotal = segments.maxOfOrNull { it.end }?.let { end ->
            if (end == Long.MAX_VALUE) total else end + 1
        } ?: total
        val effectiveTotal = maxOf(total, segTotal)
        if (segments.any { saver.partialFile(fileName, it.index).isFile }) {
            var result = segments
            RandomAccessFile(unified, "rw").use { raf ->
                if (effectiveTotal > 0) raf.setLength(effectiveTotal)
                result = segments.map { seg ->
                    val part = saver.partialFile(fileName, seg.index)
                    if (!part.isFile) return@map seg
                    val keep = segmentResumeBytes(
                        seg.downloaded, part.length(), segmentLength(seg.start, seg.end)
                    )
                    if (keep > 0) {
                        raf.seek(seg.start)
                        part.inputStream().use { input ->
                            val buf = ByteArray(BUFFER_SIZE)
                            var left = keep
                            while (left > 0) {
                                val n = input.read(buf, 0, minOf(buf.size.toLong(), left).toInt())
                                if (n <= 0) break
                                raf.write(buf, 0, n)
                                left -= n
                            }
                        }
                    }
                    runCatching { part.delete() }
                    if (keep != seg.downloaded) seg.copy(downloaded = keep) else seg
                }
            }
            if (result != segments) {
                val corrected = result
                updateItem(id) {
                    it.copy(
                        segments = corrected,
                        bytesDownloaded = corrected.sumOf { s -> s.downloaded }
                    )
                }
            }
            return result
        }
        var result = segments
        val recorded = segments.sumOf { it.downloaded }
        if (recorded > 0 &&
            (!unified.isFile || (effectiveTotal > 0 && unified.length() != effectiveTotal))
        ) {
            result = segments.map { it.copy(downloaded = 0L) }
            val zeroed = result
            updateItem(id) {
                it.copy(segments = zeroed, bytesDownloaded = 0, speedBps = 0, etaSeconds = 0)
            }
            runCatching { unified.delete() }
        }
        if (effectiveTotal > 0 && (!unified.isFile || unified.length() != effectiveTotal)) {
            RandomAccessFile(unified, "rw").use { it.setLength(effectiveTotal) }
        }
        return result
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

        // Berkas tunggal pra-alokasi (issue #1427): semua segmen menulis
        // pada offset absolutnya di SATU berkas .part — tak ada lagi
        // berkas per-segmen + merge salinan-penuh di garis finis.
        // Persiapan memvalidasi keadaan di disk (dan memigrasi part
        // warisan pra-#1427) sebelum segmen diluncurkan; catatan segmen
        // bisa terkoreksi olehnya.
        segments = prepareSegmentedFile(item.id, fileName, segments, total, saver)

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
                    rememberFailedUrl(item.id, item.url)
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
                    rememberFailedUrl(item.id, item.url)
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

        // Tak ada merge (issue #1427): berkas .part tunggal yang ditulis
        // segmen pada offset absolutnya SUDAH merupakan berkas utuh —
        // terbitkan langsung. Objek File ini persis yang dulu dihasilkan
        // tahap penggabungan, jadi jalur publish/MediaStore/SAF tak berubah.
        val merged = saver.partialFile(fileName)
        ensureItemKept(item.id, merged)
        val published0 = publishItem(saver, merged, fileName, item)
        val finalName = published0.fileName ?: fileName
        verifyChecksum(item.id, published0, saver)?.let {
            deletePublished(published0)
            rollbackReplacement(item.id)
            throw IOException(it)
        }
        ensurePresentForCommit(item.id, published0)
        commitReplacement(item.id)
        val published = organizeIfEnabled(saver, published0, finalName)
        ensurePublishedKept(item.id, published0, published)
        speedTracker.reset(item.id)
        // Sama seperti finishMuxed (issue #574).
        retryAttempts.remove(item.id)
        lastSpeedSample.remove(item.id)
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
                // Sama seperti runSingle (issue #573): headers null
                // (probe HEAD segmen gagal) tak menghapus etag tersimpan.
                etag = headers?.etag?.takeIf { e -> e.isNotBlank() } ?: it.etag,
                finishedAt = System.currentTimeMillis()
            )
        }
        flushSave()
        // Cookie sesi dari unduhan multi-segmen ikut dipersists (issue #391).
        persistCookies()
        clearSegProgress(item.id)
        App.logEvent("DOWNLOAD COMPLETED: $finalName (${Formats.bytes(current.bytesDownloaded)})")
        if (finishedMulti != null) NotificationHelper.notifyItemFinished(context, finishedMulti)
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
        // Result ber-audioUrl juga ditolak (issue #545): directUrl-nya
        // stream video-only dari pasangan adaptif (#537) — memakai
        // nya sebagai URL resume polos menggabung segmen progresif
        // awal dengan segmen bisu. Pemanggil jatuh ke fallback
        // satu-alur yang aman.
        if (result.directUrl == item.url || result.audioUrl.isNotEmpty()) return null
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
        val partial = saver.partialFile(fileName)
        val health = DownloadHealthWatchdog(
            if (item.speedLimitKbps > 0) item.speedLimitKbps else StoragePrefs.speedLimitKbps(context)
        )
        var downloaded = segment.downloaded
        var lastSegBytes = downloaded
        var lastSegAt = monotonicNow()
        // Resume model berkas tunggal (issue #1427): catatan segmen adalah
        // sumber kebenaran — berkas .part tunggal sudah divalidasi utuh
        // oleh prepareSegmentedFile sebelum segmen diluncurkan (panjang
        // persis total; part warisan termigrasi dan catatannya terpangkas
        // ke isi part). Di sini catatan hanya dipangkas ke rentang segmen
        // (paranoia nilai basi/rusak): menulis ulang byte pada offset yang
        // sama bersifat idempoten karena byte resource yang sama menimpa
        // posisi yang sama, jadi tak ada lagi truncate/buang per segmen.
        val segLen = segmentLength(segment.start, segment.end)
        val clamped = segmentResumeBytes(downloaded, segLen, segLen)
        if (clamped != downloaded) {
            if (clamped < downloaded) addThrottleTotal(id, -(downloaded - clamped))
            downloaded = clamped
            lastSegBytes = downloaded
            updateSegment(id, segment.index, downloaded)
        }
        // Segmen yang sudah utuh dilewati tanpa koneksi (issue #630):
        // memintanya ulang menghasilkan Range tidak valid dan memicu
        // pembuangan seluruh progres oleh penangan penolakan Range.
        // Nilai downloaded di sini sudah direkonsiliasi di atas: catatan
        // yang terpangkas ke 0 membuat segmen tetap diminta ulang secara
        // sah di bawah.
        if (isSegmentComplete(segment.start, segment.end, downloaded)) {
            updateSegment(id, segment.index, downloaded)
            return
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
        // Aliran respons belum dibuka = galat pra-aliran (issue #1260):
        // badan galat tak pernah dibaca sehingga soket tak bisa dipakai
        // ulang — putus eksplisit di finally (pola #1243), jangan
        // menunggu GC. Sesudah aliran dibuka, penutupan aliran di loop
        // dan untrackConnection yang mengatur.
        var streamOpened = false
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
            streamOpened = true
            // Tulis terposisi pada berkas tunggal (issue #1427): tiap
            // segmen memegang RandomAccessFile sendiri — offset bersifat
            // per-deskriptor sehingga segmen paralel tak saling geser —
            // dan seek sekali ke offset absolut segmen ini. Tulis RAF tak
            // memakai buffer perantara: byte yang terhitung progres sudah
            // di tangan OS, tak pernah mendahului isi berkas.
            val output = RandomAccessFile(partial, "rw")
            output.seek(segment.start + downloaded)
            val buffer = ByteArray(BUFFER_SIZE)
            var lastNotify = 0L
            var segIters = 0
            var zeroStreak = 0
            // Sisa byte yang boleh ditulis untuk segmen ini; server nakal yang
            // mengabaikan batas akhir Range tak boleh menumpuk byte ekstra ke
            // file parsial (merge korup lolos verifySize yang membolehkan overrun).
            var budget = segmentLength(segment.start, segment.end) - downloaded
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
                        val segTotal = segmentLength(segment.start, segment.end)
                        val speed = if (now > lastSegAt) {
                            ((downloaded - lastSegBytes) * 1000L) / (now - lastSegAt)
                        } else 0L
                        lastSegBytes = downloaded
                        lastSegAt = now
                        // Penjaga ruang berjalan yang sama dengan jalur
                        // single-stream (#781): jalur segmen sebelumnya
                        // hanya bertumpu pada pra-cek awal attempt,
                        // sehingga ENOSPC di tengah unduhan tampil
                        // sebagai pesan OS mentah dan volume bisa
                        // terdorong ke 0 byte (issue #926). Baca gagal
                        // (freeBytesOrNull null) = lewati cek tick ini.
                        val freeNow = saver.freeBytesOrNull()
                        if (freeNow != null && freeNow < MIN_FREE_BYTES) {
                            throw IOException(
                                "Storage almost full (free ${Formats.bytes(freeNow)})"
                            )
                        }
                        health.check(now, downloaded, segTotal, speed)
                        // Progres ke penyangga; updateItem nyata dilakukan flush.
                        recordSegmentProgress(id, segment.index, downloaded, item.segments.size)
                    }
                }
                // Tak ada flush: tulis RandomAccessFile langsung ke OS.
                coroutineContext.ensureActive()
            } finally {
                runCatching { input.close() }
                runCatching { output.close() }
            }
            if (downloaded < segmentLength(segment.start, segment.end)) {
                throw IOException("Segment ${segment.index} incomplete")
            }
            updateSegment(id, segment.index, downloaded)
        } catch (e: IOException) {
            if (!coroutineContext.isActive) throw CancellationException()
            throw e
        } finally {
            if (!streamOpened) runCatching { conn.disconnect() }
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
        // Label longgar (issue #925): md5:/sha1:/sha-1:/sha256:/sha-256:
        // dengan spasi di sekitar titik dua ikut diterima — label
        // 'SHA-256: <hex>' itulah yang ditampilkan banyak situs, dan
        // sebelumnya gugur diam-diam menjadi "tanpa checksum".
        val labelMatch = CHECKSUM_LABEL_RE.find(clean)
        val labeledAlgo: String?
        val rest0: String
        if (labelMatch != null) {
            labeledAlgo = when (
                labelMatch.groupValues[1].lowercase(Locale.ROOT).replace("-", "")
            ) {
                "md5" -> "MD5"
                "sha1" -> "SHA-1"
                else -> "SHA-256"
            }
            rest0 = clean.substring(labelMatch.value.length)
        } else {
            labeledAlgo = null
            rest0 = clean
        }
        // Token pertama ala keluaran sha256sum ('<hex>  file.zip' /
        // '<hex> *file.zip', issue #925; pola yang sama dengan
        // sidecarChecksum #347). Hex berkelompok-spasi diterima hanya
        // bila SEMUA token hex dan gabungannya berpanjang sah.
        val tokens = rest0.trim().split(WHITESPACE_RE).filter { it.isNotEmpty() }
        if (tokens.isEmpty()) return null
        var candidate = tokens[0]
        // Token pertama yang sudah utuh (panjang sah sendirian) selalu
        // menang: gabungan hanya untuk hex yang memang terpotong
        // kelompok, bukan untuk menelan nama berkas hex sesudah hash
        // yang sudah lengkap (issue #927). Berlabel pun gabungan
        // diizinkan, dengan syarat panjang gabungan persis panjang
        // algoritma labelnya (issue #928).
        if (tokens.size > 1) {
            val labeledLen = when (labeledAlgo) {
                "MD5" -> 32
                "SHA-1" -> 40
                "SHA-256" -> 64
                else -> null
            }
            val joinWanted = if (labeledLen != null) {
                candidate.length != labeledLen
            } else {
                candidate.length != 32 && candidate.length != 40 &&
                    candidate.length != 64
            }
            if (joinWanted) {
                val joined = tokens.joinToString("")
                val lenOk = if (labeledLen != null) {
                    joined.length == labeledLen
                } else {
                    joined.length == 32 || joined.length == 40 ||
                        joined.length == 64
                }
                if (lenOk &&
                    joined.all { it in '0'..'9' || it in 'a'..'f' || it in 'A'..'F' }
                ) {
                    candidate = joined
                }
            }
        }
        // Tanpa label: tebak algoritma dari panjang hex polos
        // (MD5 32, SHA-1 40, SHA-256 64). Sebelumnya nilai polos
        // selalu ditafsir MD5 sehingga hash SHA polos mustahil
        // cocok dan unduhan utuh terhapus (issue #342).
        val algo = labeledAlgo ?: when (candidate.length) {
            32 -> "MD5"
            40 -> "SHA-1"
            64 -> "SHA-256"
            else -> return null
        }
        val value = candidate.lowercase(Locale.ROOT)
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

    /** Penjaga commit sesudah verifikasi checksum (issue #881): digest
     *  adalah pembacaan sinkron yang tak terinterupsi pembatalan, dan
     *  verifyChecksum sendiri mengembalikan null (lolos) bila item
     *  sudah hilang saat ia mulai. Bila pengguna menghapus penerus di
     *  jendela itu, commitReplacement tetap memensiunkan pendahulu
     *  yang bagus untuk penerus yang tak lagi diinginkan — versi lama
     *  hilang permanen, melanggar janji #215. Item hilang = hasil
     *  publish dibuang + rollback, persis jalur checksum gagal. */
    private fun ensurePresentForCommit(
        itemId: String,
        published: FileSaver.PublishResult
    ) {
        if (_items.value.none { it.id == itemId }) {
            deletePublished(published)
            rollbackReplacement(itemId)
            throw CancellationException("item $itemId removed during finalize")
        }
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
                // RFC 5987 (issue #1242): charset dari label nilai,
                // '+' literal — URLDecoder salah untuk keduanya.
                val charsetName = value.substring(0, idx)
                    .ifBlank { "UTF-8" }
                val decoded = decodePercentEncoded(
                    value.substring(idx + 2), charsetName
                )
                if (!decoded.isNullOrBlank()) return decoded
            }
        }
        val plain = CONTENT_DISPOSITION_PLAIN.find(header)
        return plain?.groupValues?.get(1)?.trim()?.takeIf { it.isNotEmpty() }
    }

    private fun sanitizeFileName(name: String): String = FileNames.safe(name)

    private fun ensureServiceRunning() {
        // Badan start tunggal di DownloadService.ensureRunning (#1028):
        // bila foreground service gagal dimulai (mis. pembatasan
        // Android 12+ saat di latar belakang), unduhan tetap dijalankan
        // di proses aplikasi.
        DownloadService.ensureRunning(context)
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

    /** Progres ringan: JSON kecil (id -> bytes/total + downloaded per segmen,
     *  issue #648) tanpa enkripsi, disimpan paling cepat tiap 2 detik selama
     *  download aktif. */
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
            // Seluruh rantai pernah dipindah ke coroutine lepas (#790,
            // demi melepas encode dari main thread) dan itu REGRESI
            // (issue #813): proses yang mati sebelum coroutine IO
            // sempat jalan kehilangan transisi ini seluruhnya, padahal
            // jaminan flushSave justru 'state terantre sebelum fungsi
            // kembali'. flushSave adalah jalur transisi yang dipacu
            // pengguna (add/pause/cancel/selesai), bukan per-tick —
            // biaya encode di sini memang harga jaminannya.
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
    @Volatile private var cookieWriteJob: Job? = null
    private val cookieWriteLock = Any()

    /** Simpan cookie ke SharedPreferences agar persist antar restart. */
    private fun persistCookies() {
        // Cancel+pasang di bawah satu kunci (issue #1320): persistCookies
        // dipanggil dari banyak thread penyelesaian unduhan; tanpa kunci,
        // dua panggilan bisa saling timpa referensi job — job yang kalah
        // tak terbatalkan dan debounce kalah oleh tulis ganda.
        synchronized(cookieWriteLock) {
            cookieWriteJob?.cancel()
            cookieWriteJob = scope.launch {
                delay(COOKIE_WRITE_DEBOUNCE_MS)
                persistCookiesImmediate()
            }
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
        // Dikompilasi sekali: parseChecksum dipanggil per verifikasi
        // (advis gerbang pola-boros atas Regex per-panggil, putaran 62).
        private val CHECKSUM_LABEL_RE =
            Regex("^(md5|sha-?1|sha-?256)\\s*:\\s*", RegexOption.IGNORE_CASE)
        private val WHITESPACE_RE = Regex("\\s+")
        private val CONTENT_DISPOSITION_STAR = Regex("filename\\*=([^;]+)")
        private val DEFAULT_NAME_FORMAT: ThreadLocal<SimpleDateFormat> = ThreadLocal.withInitial {
            SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US)
        }
        private val CONTENT_DISPOSITION_PLAIN = Regex("filename=\"?([^\";]+)\"?")
        private const val BUFFER_SIZE = 64 * 1024
        private const val MAX_ZERO_READS = 64
        private const val MAX_REDIRECTS = 5

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

/** Budget tulis resume single-stream (issue #1369): hanya respons 206 yang
 *  totalnya berasal dari Content-Range yang bersifat otoritatif — sisanya
 *  (total - sudah terunduh) boleh ditulis, selebihnya byte ekstra server.
 *  Respons lain / 206 tanpa total rentang -> null (tanpa budget): total di
 *  sana diturunkan dari klaim Content-Length yang bisa basi dan verifySize
 *  memang mentoleransinya. Murni agar bisa di-unit-test. */
internal fun singleStreamRangeBudget(
    responseCode: Int,
    contentRangeTotal: Long?,
    alreadyDownloaded: Long
): Long? =
    if (responseCode == 206 && contentRangeTotal != null && contentRangeTotal > 0) {
        (contentRangeTotal - alreadyDownloaded).coerceAtLeast(0)
    } else {
        null
    }

/** Partial dipertahankan bila Range ditolak SETELAH ada progres: penolakan di
 *  tengah jalan = link kedaluwarsa/transien, bukan server tanpa-Range.
 *  Murni agar bisa di-unit-test. */
internal fun shouldKeepPartialOnRangeReject(keptBytes: Long): Boolean = keptBytes > 0

/** Segmen sudah utuh: byte tercatat (sesudah rekonsiliasi resume) menutup
 *  seluruh rentang [start, end]. Segmen seperti ini TIDAK boleh diminta
 *  ulang: Range-nya menjadi bytes=<end+1>-<end> yang tidak valid, server
 *  patuh menjawab 416, dan penangan penolakan Range membuang seluruh
 *  progres unduhan (issue #630). Murni agar bisa di-unit-test. */
/** True bila catatan resume single-stream sudah mencapai total yang
 *  diketahui (issue #650): koneksi Range untuk berkas utuh hanya memancing
 *  416 terminal, jadi pemanggil melewati koneksi dan langsung finalisasi. */
internal fun isResumeAlreadyComplete(resumeFrom: Long, totalBytes: Long): Boolean =
    totalBytes > 0 && resumeFrom >= totalBytes

/** Panjang segmen [start, end] inklusif, JENUH di Long.MAX_VALUE
 *  (issue #812): `end - start + 1` polos membungkus ke Long.MIN_VALUE
 *  untuk segmen terbuka ber-end Long.MAX_VALUE (yang memang diterima
 *  parseSegments) — segmen divonis tuntas tanpa diunduh, atau budget
 *  tulis jadi negatif. Pola jenuh yang sama dengan codec (#748/#789). */
internal fun segmentLength(start: Long, end: Long): Long {
    val len = end - start
    return if (len == Long.MAX_VALUE) Long.MAX_VALUE else len + 1
}

internal fun isSegmentComplete(start: Long, end: Long, downloaded: Long): Boolean =
    downloaded >= segmentLength(start, end)

/** Byte resume segmen yang boleh dipercaya (issue #1427): catatan tak
 *  pernah negatif, tak melebihi byte yang sungguh tersedia ([available] =
 *  isi part warisan saat migrasi; panjang segmen pada jalur normal karena
 *  berkas tunggal sudah divalidasi utuh di persiapan), dan tak melebihi
 *  panjang segmennya. Murni agar bisa di-unit-test; nilai harapan
 *  dibuktikan port Python 1:1 (audit-dm-r14/port-1427.py). */
internal fun segmentResumeBytes(recorded: Long, available: Long, segLen: Long): Long =
    minOf(
        recorded.coerceAtLeast(0L),
        available.coerceAtLeast(0L),
        segLen.coerceAtLeast(0L)
    )

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

/** Kegagalan HTTP yang bersifat per-URL (issue #544): URL ini
 *  sendiri yang mati/ditolak — 404/410 (hilang permanen), 403
 *  (hotlink; 403 googlevideo sudah ditangani cabang khusus sebelum
 *  gerbang mirror), dan 5xx (sisi server host ini) — jadi mirror
 *  layak dicoba. Kode lain (401/407 kredensial, 429 batas laju)
 *  sengaja tidak ikut: akan sama saja di mirror. Murni agar bisa
 *  di-unit-test. */
internal fun isHttpMirrorFailoverError(message: String?): Boolean {
    if (message.isNullOrBlank()) return false
    val code = Regex("""HTTP (\d{3})""").find(message)?.groupValues?.get(1)?.toIntOrNull()
        ?: return false
    return code == 404 || code == 410 || code == 403 || code in 500..599
}

/** Ekor terpotong (server menutup koneksi sebelum Content-Length terpenuhi):
 *  transient seperti putus jaringan — partial tetap valid, jadi setelah retry
 *  habis jangan FAILED melainkan PAUSED agar auto-lanjut saat koneksi pulih.
 *  Murni agar bisa di-unit-test. */
/** Pola pesan segmen terpotong milik sendiri (issue #1213). */
private val SEGMENT_INCOMPLETE_RE = Regex("Segment \\d+ incomplete")

internal fun isTailIncompleteError(message: String?): Boolean {
    if (message.isNullOrBlank()) return false
    // Berjangkar pada dua bentuk pesan produsen sendiri (issue #1213,
    // kelas #1186): substring telanjang "incomplete" atas seluruh
    // pesan ikut mengubah galat asing yang kebetulan memuat kata itu
    // menjadi PAUSED auto-lanjut, sehingga kegagalan permanen tak
    // pernah tampil sebagai FAILED.
    return message.startsWith("Size mismatch") ||
        SEGMENT_INCOMPLETE_RE.containsMatchIn(message)
}

/** Ukuran file hasil re-extract wajib sama dengan file lama agar resume segmen
 *  tidak korup; freshTotal <= 0 = server tak memberi tahu (izinkan resume
 *  best-effort). Murni agar bisa di-unit-test. */
internal fun isFreshResumeSizeValid(storedTotal: Long, freshTotal: Long): Boolean =
    freshTotal <= 0 || storedTotal <= 0 || freshTotal == storedTotal

internal fun monotonicNow(): Long =
    runCatching { SystemClock.elapsedRealtime() }.getOrDefault(System.currentTimeMillis())

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

internal fun urlHostOf(url: String): String =
    runCatching {
        java.net.URL(url).host?.trim()?.trimEnd('.')?.lowercase(Locale.ROOT) ?: ""
    }.getOrDefault("")

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


/** Literal IPv6 valid (grup heks dipisah ':', satu '::' kompresi diizinkan,
 *  IPv4-embedded bertitik diizinkan): guard sebelum InetAddress.getByName
 *  agar hostname/sampah tak memicu lookup DNS. */
internal val IPV6_LITERAL_RE = Regex(
    "^([0-9a-fA-F]{0,4}:){2,7}[0-9a-fA-F:.]{0,45}$|^::[0-9a-fA-F:.]{0,45}$"
)


/**
 * Buang sufiks ekstensi dari nama dasar tanpa peka huruf besar/kecil
 * (issue #522). removeSuffix bawaan Kotlin peka kapital, jadi nama
 * kustom pengguna berakhiran .MP4/.TS/.WEBM tidak terbuang dan nama
 * terbit berujung ekstensi ganda (Film.MP4.mp4). Sufiks dicoba
 * berurutan seperti rantai removeSuffix yang digantikannya.
 */
internal fun buangSufiksEkstensi(nama: String, vararg sufiks: String): String {
    var hasil = nama
    for (s in sufiks) {
        if (hasil.lowercase().endsWith(s)) hasil = hasil.dropLast(s.length)
    }
    return hasil
}


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
    limitKbps: Int,
    shared: GlobalRateLimiter?
) {
    // Volatile + bisa diganti di tengah attempt (issue #747): perubahan
    // limit per-item harus berlaku langsung ke unduhan yang sedang jalan,
    // seperti limit global lewat GlobalRateLimiter.setLimit.
    @Volatile private var limitKbps: Int = limitKbps
    @Volatile private var shared: GlobalRateLimiter? = shared

    /** Terapkan batas baru di tengah attempt. Mode per-item (shared
     *  null) dan mode global (shared terisi) bisa bertukar mengikuti
     *  nilai limit per-item terbaru, jadi keduanya diganti bersama. */
    fun updateLimit(kbps: Int, newShared: GlobalRateLimiter?) {
        limitKbps = kbps
        shared = newShared
    }

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
            // Total yang MUNDUR tak boleh menggerakkan lastSeen mundur
            // (issue #1296): total gabungan beberapa aliran (segmen
            // paralel) bisa terlihat mundur antar-panggilan, dan lastSeen
            // yang mundur membuat rentang yang sama ditagih dua kali ke
            // limiter global (delta positif palsu di panggilan berikutnya).
            val delta = if (total > lastSeen) {
                val d = total - lastSeen
                lastSeen = total
                d
            } else {
                0L
            }
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
        if (total > 0 && downloaded == total) return
        // Total terbukti salah (issue #618): server menyatakan
        // Content-Length LEBIH KECIL dari isi sebenarnya (kelebihan
        // byte ditoleransi by-design, kebijakan #170) — begitu byte
        // melewati total, gerbang lama mematikan SEMUA vonis untuk
        // sisa aliran, jadi koneksi yang kemudian macet total hanya
        // dibatasi read-timeout socket. Perlakukan sebagai total tak
        // diketahui (mode macet-saja di bawah): vonis macet hidup
        // lagi, vonis lambat tetap mati karena totalnya tak dipercaya.
        val totalEfektif = if (total > 0 && downloaded > total) 0L else total
        if (downloaded != lastBytes) {
            lastBytes = downloaded
            lastAt = now
            // Byte bergerak = koneksi hidup: maafkan lambat. Tanpa ini,
            // unduhan di jaringan lemah (mis. 2G, <2 KB/s tapi stabil)
            // divonis mati tiap 20 detik lalu retry gagal terus — padahal
            // yang boleh divonis hanya byte yang macet total.
            slowSince = 0L
        }
        if (totalEfektif <= 0) {
            // Total tak diketahui (unknown-length): hanya vonis macet
            // total (speed nol), bukan vonis lambat — kecepatan unduhan
            // segmen memang burst sehingga ambang 2 KB/s memberi vonis palsu.
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
