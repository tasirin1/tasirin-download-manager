package com.tasirin.httpdownloadmanager.download

import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
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
import com.tasirin.httpdownloadmanager.util.StoragePrefs
import com.tasirin.httpdownloadmanager.util.TlsCompat
import com.tasirin.httpdownloadmanager.util.SocialMediaExtractor
import com.tasirin.httpdownloadmanager.util.readBounded
import kotlinx.coroutines.CancellationException
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
import java.net.URLDecoder
import java.security.MessageDigest
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
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

    // Cookie manager in-memory: store per-host cookies so subsequent requests
    // can carry server-set session cookies (helps sites that require cookies,
    // but does not bypass Cloudflare JS challenges).
    private val cookieManager = CookieManager(null, CookiePolicy.ACCEPT_ALL).also {
        java.net.CookieHandler.setDefault(it)
        loadPersistedCookies()
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
    private val retryAttempts = ConcurrentHashMap<String, Int>()
    private val activeConns = ConcurrentHashMap<String, MutableSet<HttpURLConnection>>()
    private val speedTracker = SpeedTracker()
    private var saveJob: Job? = null
    private var progressSaveJob: Job? = null
    private var lastProgressSaveAt = 0L
    // URL yang pernah gagal di sesi ini: cadangan yang sama tidak dicoba
    // berulang-ulang (hemat waktu saat ISP/proxy menolak beberapa host).
    // newKeySet() baru ada API 24; pakai setFromMap agar aman di Android 5 (API 21).
    private val failedUrls = Collections.newSetFromMap(ConcurrentHashMap<String, Boolean>())

    private fun rememberFailedUrl(url: String) {
        failedUrls.add(url)
        // Hapus cukup entri supaya ukuran kembali di bawah batas;
        // sebelumnya hanya menghapus 1 entri -> set bisa tumbuh tak terbatas.
        while (failedUrls.size > 256) {
            val iterator = failedUrls.iterator()
            if (iterator.hasNext()) {
                iterator.next()
                iterator.remove()
            } else break
        }
    }

    private val connectTimeoutMs: Int
        get() = StoragePrefs.getConnectTimeoutSec(context) * 1000

    private val readTimeoutMs: Int
        get() = StoragePrefs.getReadTimeoutSec(context) * 1000

    @Volatile
    private var interruptedResumed = false

    private val _items = MutableStateFlow<List<DownloadItem>>(
        repository.load().sortedByDescending { it.addedAt }
    )
    val items: StateFlow<List<DownloadItem>> = _items.asStateFlow()

    // Progres segmen per item (indeks segmen -> byte): segmen menulis di sini
    // tiap detik TANPA emisi StateFlow; flush berkala menggabungkannya menjadi
    // SATU updateItem per item (sebelumnya 1 salinan daftar + 1 emisi per
    // segmen per detik) — hemat CPU/GC saat banyak segmen paralel.
    private val segProgress = ConcurrentHashMap<String, LongArray>()
    private val segFlushJobs = ConcurrentHashMap<String, Job>()
    // Total byte bersama untuk speed limiter multi-segmen; menghindari pencarian
    // item + penjumlahan segmen di setiap chunk saat batas kecepatan aktif.
    private val throttleTotals = ConcurrentHashMap<String, AtomicLong>()

    init {
        startBackgroundLoops()
    }

    private fun startBackgroundLoops() {
        scope.launch { monitorLoop() }
    }

    private fun disconnectActive(id: String) {
        activeConns.remove(id)?.forEach { connection ->
            runCatching { connection.disconnect() }
        }
    }

    private fun trackConnection(id: String, connection: HttpURLConnection): HttpURLConnection {
        activeConns.getOrPut(id) { Collections.newSetFromMap(ConcurrentHashMap()) }.add(connection)
        return connection
    }

    private fun untrackConnection(id: String, connection: HttpURLConnection) {
        activeConns[id]?.remove(connection)
        activeConns.remove(id, emptySet())
        runCatching { connection.disconnect() }
    }

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
        monitor: Boolean = false
    ) {
        val cleanUrl = url.trim()
        if (cleanUrl.isEmpty()) return
        val customName = fileName?.trim().orEmpty()
        val name = FileNames.safe(customName.ifEmpty { guessFileName(cleanUrl) })
        val item = DownloadItem(
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
            monitor = monitor
        )
        update(listOf(item) + _items.value)
        flushSave()
        StoragePrefs.addRecentUrl(context, cleanUrl)
        val host = runCatching { URL(cleanUrl).host }.getOrDefault("")
        App.logEvent("DOWNLOAD ADDED: $name (${host.ifEmpty { "local/custom URL" }})")
        attemptStart(item.id)
    }

    fun pause(id: String) {
        _items.value.find { it.id == id }?.let { App.logEvent("DOWNLOAD PAUSED: ${it.fileName}") }
        retryAttempts.remove(id)
        speedTracker.reset(id)
        jobs.remove(id)?.cancel()
        disconnectActive(id)
        updateItem(id) {
            it.copy(state = DownloadState.PAUSED, autoResume = false, speedBps = 0, etaSeconds = 0)
        }
        clearSegProgress(id)
        scheduleSave()
    }

    fun resume(id: String) {
        val item = _items.value.find { it.id == id } ?: return
        if (item.state != DownloadState.PAUSED && item.state != DownloadState.FAILED) return
        App.logEvent("DOWNLOAD RESUMED: ${item.fileName}")
        retryAttempts.remove(id)
        clearSegProgress(id)
        updateItem(id) { it.copy(state = DownloadState.PENDING, autoResume = true) }
        attemptStart(id)
    }

    fun resumeInterrupted() {
        if (interruptedResumed) return
        interruptedResumed = true
        _items.value.filter {
            it.autoResume && (it.state == DownloadState.PAUSED || it.state == DownloadState.PENDING)
        }.forEach { item ->
            updateItem(item.id) { it.copy(state = DownloadState.PENDING, autoResume = true) }
        }
        startQueued()
    }

    fun cancel(id: String) {
        _items.value.find { it.id == id }?.let { App.logEvent("DOWNLOAD CANCELLED: ${it.fileName}") }
        retryAttempts.remove(id)
        speedTracker.reset(id)
        clearSegProgress(id)
        jobs.remove(id)?.cancel()
        disconnectActive(id)
        if (StoragePrefs.isDeletePartialOnCancel(context)) {
            _items.value.find { it.id == id }?.let { item ->
                FileSaver(context).partialFiles(item).forEach { runCatching { it.delete() } }
            }
        }
        updateItem(id) {
            it.copy(state = DownloadState.CANCELLED, speedBps = 0, etaSeconds = 0)
        }
        scheduleSave()
    }

    fun remove(id: String) {
        _items.value.find { it.id == id }?.let { App.logEvent("DOWNLOAD DELETED: ${it.fileName}") }
        val item = _items.value.find { it.id == id }
        retryAttempts.remove(id)
        speedTracker.reset(id)
        clearSegProgress(id)
        jobs.remove(id)?.cancel()
        disconnectActive(id)
        update(_items.value.filterNot { it.id == id })
        item?.let { FileSaver(context).deleteFiles(it) }
        scheduleSave()
    }

    fun clearCompleted() {
        // Hanya membersihkan daftar; file hasil download TIDAK dihapus.
        val removed = _items.value.filter { it.state == DownloadState.COMPLETED }
        removed.forEach { speedTracker.reset(it.id) }
        update(_items.value.filterNot { it.state == DownloadState.COMPLETED })
        scheduleSave()
    }

    fun importStream(
        fileName: String,
        destination: String = "",
        folderPath: String = "",
        length: Long,
        writer: (OutputStream) -> Unit
    ): FileSaver.PublishResult {
        val name = sanitizeFileName(fileName)
        val size = length
        val saver = FileSaver(context)
        val published = saver.saveStream(name, destination, folderPath, writer)
        val finalName = published.fileName ?: name
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
        update(listOf(item) + _items.value)
        flushSave()
        return published
    }

    fun probeHlsVariants(url: String): List<HlsVariant>? {
        return runCatching {
            val conn = openAuthenticatedConnection(
                url, method = "GET", username = "", password = "", headers = ""
            )
            try {
                val code = conn.responseCode
                if (code !in 200..299) return null
                // Baca terbatas: master playlist normal kecil; body raksasa dari
                // URL nakal tidak boleh dimuat penuh ke memori.
                val body = conn.inputStream.use { readBounded(it, HLS_PROBE_MAX_BYTES) }
                HlsParser.parseMaster(body, conn.url.toString())
            } finally {
                conn.disconnect()
            }
        }.getOrNull()
    }

    fun probeUrl(
        url: String,
        username: String = "",
        password: String = "",
        headers: String = ""
    ): UrlProbe? {
        val clean = url.trim()
        if (clean.isEmpty()) return null
        if (!clean.startsWith("http://") && !clean.startsWith("https://")) return null
        return runCatching {
            val conn = openAuthenticatedConnection(
                clean, method = "HEAD",
                username = username, password = password, headers = headers
            )
            try {
                val code = conn.responseCode
                if (code !in 200..299) return null
                UrlProbe(
                    fileName = contentDispositionName(conn.getHeaderField("Content-Disposition")),
                    sizeBytes = contentLength(conn),
                    contentType = conn.getHeaderField("Content-Type"),
                    etag = conn.getHeaderField("ETag")
                )
            } finally {
                conn.disconnect()
            }
        }.getOrNull()
    }

    /** Buka koneksi dengan redirect manual. Kredensial dan header sensitif
     *  hanya dikirim ulang bila target tetap satu origin dengan URL awal. */
    private fun openAuthenticatedConnection(
        url: String,
        method: String,
        username: String,
        password: String,
        headers: String,
        configure: (HttpURLConnection, String) -> Unit = { _, _ -> }
    ): HttpURLConnection {
        var current = url
        repeat(MAX_REDIRECTS) {
            val conn = openConn(current)
            conn.instanceFollowRedirects = false
            conn.requestMethod = method
            conn.connectTimeout = if (method == "HEAD") 8_000 else connectTimeoutMs
            conn.readTimeout = if (method == "HEAD") 8_000 else readTimeoutMs
            val ua = StoragePrefs.getUserAgent(context)
                .ifEmpty { DEFAULT_USER_AGENT }
            // Instagram CDN images butuh Googlebot UA agar auth tokens valid
            val effectiveUa = if (current.contains("scontent") && current.contains("cdninstagram")) {
                "Mozilla/5.0 (compatible; Googlebot/2.1; +http://www.google.com/bot.html)"
            } else ua
            conn.setRequestProperty("User-Agent", effectiveUa)
            conn.setRequestProperty("Accept", "*/*")
            conn.setRequestProperty("Accept-Language", "en-US,en;q=0.9,id;q=0.8")
            try {
                val origin = java.net.URL(current).let { "${it.protocol}://${it.host}" }
                conn.setRequestProperty("Referer", "$origin/")
            } catch (_: Exception) { /* Referer opsional, tidak wajib */ }
            if (current == url || isSameOrigin(url, current)) {
                applyAuthHeaders(conn, username, password, headers)
            }
            configure(conn, current)
            val code = conn.responseCode
            if (code !in 301..308) return conn

            val location = conn.getHeaderField("Location")
            conn.disconnect()
            val next = redirectTarget(current, location)
                ?: throw IOException("Invalid redirect")
            current = next
        }
        throw IOException("Too many redirects")
    }

    fun pauseAll() {
        val targets = _items.value.filter {
            it.state == DownloadState.DOWNLOADING || it.state == DownloadState.PENDING
        }
        if (targets.isEmpty()) return

        // Batch: satu salinan list + satu emisi StateFlow + satu penyimpanan,
        // bukan N kali update/save saat pengguna menekan Pause All.
        targets.forEach { item ->
            App.logEvent("DOWNLOAD PAUSED: ${item.fileName}")
            retryAttempts.remove(item.id)
            speedTracker.reset(item.id)
            jobs.remove(item.id)?.cancel()
            disconnectActive(item.id)
        }
        val ids = targets.map { it.id }.toSet()
        update(_items.value.map { item ->
            if (item.id in ids) {
                item.copy(
                    state = DownloadState.PAUSED,
                    autoResume = false,
                    speedBps = 0,
                    etaSeconds = 0
                )
            } else {
                item
            }
        })
        scheduleSave()
    }

    fun resumeAll() {
        _items.value.filter {
            it.state == DownloadState.PAUSED || it.state == DownloadState.FAILED
        }.forEach { resume(it.id) }
    }

    fun retryFailed() {
        _items.value.filter { it.state == DownloadState.FAILED }.forEach { item ->
            retryAttempts.remove(item.id)
            updateItem(item.id) {
                it.copy(state = DownloadState.PENDING, autoResume = true, error = null)
            }
        }
        startQueued()
    }

    fun resumeAutoPaused() {
        if (!StoragePrefs.isBackgroundEnabled(context)) return
        val ids = _items.value.filter {
            it.autoResume && it.state == DownloadState.PAUSED
        }.map { it.id }
        if (ids.isEmpty()) return
        ids.forEach { id ->
            retryAttempts.remove(id)
            updateItem(id) {
                it.copy(state = DownloadState.PENDING, autoResume = true, error = null)
            }
        }
        startQueued()
    }

    fun cleanupOrphans() {
        FileSaver(context).cleanupOrphanPartials(_items.value)
    }

    fun freeSpaceBytes(): Long = FileSaver(context).destinationFreeBytes()

    fun rename(id: String, newName: String) {
        val item = _items.value.find { it.id == id } ?: return
        if (item.state != DownloadState.COMPLETED) return
        val newPath = FileSaver(context).rename(item, newName)
        if (newPath != null) {
            updateItem(id) { it.copy(fileName = newName, filePath = newPath) }
        }
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

    private suspend fun invalidateChangedResume(item: DownloadItem, etag: String?) {
        if (!shouldInvalidateResume(item, etag)) return
        val saver = FileSaver(context)
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
                (it.url.startsWith("http://") || it.url.startsWith("https://"))
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
                    addDownload(
                        url = item.url,
                        fileName = item.fileName,
                        username = item.username,
                        password = item.password,
                        headers = item.headers,
                        method = item.method,
                        postBody = item.postBody,
                        speedLimitKbps = item.speedLimitKbps,
                        priority = item.priority,
                        checksum = item.checksum,
                        destination = item.destination,
                        folderPath = item.folderPath
                    )
                }
            }
        }
    }

    @Volatile private var sharedLimiter: GlobalRateLimiter? = null

    private fun globalRateLimiter(): GlobalRateLimiter? {
        val limit = StoragePrefs.speedLimitKbps(context)
        if (limit <= 0) {
            // Batalkan throttle global bila pengguna mematikan limit
            // supaya download baru tidak terkena throttle sisa sesi lama.
            synchronized(this) { sharedLimiter = null }
            return null
        }
        synchronized(this) {
            val current = sharedLimiter
            if (current != null) return current
            val created = GlobalRateLimiter(limit)
            sharedLimiter = created
            return created
        }
    }

    fun move(id: String, destTreeUri: Uri) {
        val item = _items.value.find { it.id == id } ?: return
        if (item.state != DownloadState.COMPLETED) return
        val result = FileSaver(context).move(item, destTreeUri)
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
        return jobs.values.count { it.isActive } < StoragePrefs.maxConcurrent(context)
    }

    private fun startQueued() {
        val max = StoragePrefs.maxConcurrent(context)
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
            if (launchItem(item)) active++
        }
    }

    private fun launchItem(item: DownloadItem): Boolean {
        synchronized(jobs) {
            if (jobs[item.id]?.isActive == true) return false
            if (jobs.values.count { it.isActive } >= StoragePrefs.maxConcurrent(context)) {
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
                jobs.remove(item.id)
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
        if (item.folderPath.isNotBlank()) {
            return saver.publishToPath(partial, cleanName, item.folderPath)
                ?: throw IOException("Destination folder is invalid or not writable: ${item.folderPath}")
        }
        return saver.publish(partial, cleanName, item.destination)
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

    private fun handleFailure(id: String, message: String?) {
        val item = _items.value.find { it.id == id } ?: return
        speedTracker.reset(id)
        val maxRetries = StoragePrefs.maxRetries(context)
        val attempts = (retryAttempts[id] ?: 0) + 1
        val rangeRejected = message?.contains("does not support Range") == true
        val slowRejected = isSlowError(message)
        rememberFailedUrl(item.url)
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
            .filterNot { it in failedUrls }
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
            updateItem(id) {
                it.copy(
                    state = DownloadState.FAILED,
                    error = message,
                    autoResume = false,
                    speedBps = 0,
                    etaSeconds = 0
                )
            }
            flushSave()
            return
        }
        if (maxRetries > 0 && attempts <= maxRetries && item.autoResume) {
            retryAttempts[id] = attempts
            val baseBackoff = (RETRY_DELAY_1_MS shl (attempts - 1))
                .coerceAtMost(RETRY_DELAY_MAX_MS)
            // Jitter 0-25% agar beberapa item tidak retry serentak (thundering herd).
            val backoff = baseBackoff + Random.nextLong(0, (baseBackoff / 4) + 1)
            val retryInfo = "Failed (attempt $attempts/$maxRetries) — retrying in ${Formats.eta(backoff / 1000)}"
            App.logEvent("DOWNLOAD FAILED: ${item.fileName} — ${message ?: "?"} ($retryInfo)")
            updateItem(id) { it.copy(state = DownloadState.PENDING, error = retryInfo) }
            scope.launch {
                delay(backoff)
                if (_items.value.find { it.id == id }?.state == DownloadState.PENDING) {
                    updateItem(id) { it.copy(error = null) }
                    attemptStart(id)
                }
            }
        } else if (item.autoResume && isNetworkError(message)) {
            // Gagal karena jaringan (mati/sinyal hilang): jangan tandai FAILED,
            // biarkan PAUSED agar otomatis lanjut saat koneksi pulih.
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
            updateItem(id) {
                it.copy(
                    state = DownloadState.FAILED,
                    error = message,
                    autoResume = false,
                    speedBps = 0,
                    etaSeconds = 0
                )
            }
            flushSave()
        }
    }

    private fun isSlowError(message: String?): Boolean {
        val m = message?.lowercase() ?: return false
        return m.contains("speed too low") || m.contains("connection stalled")
    }

    private fun isConnectError(message: String?): Boolean {
        val m = message?.lowercase() ?: return false
        return m.contains("failed to connect") ||
            m.contains("unable to resolve host") ||
            m.contains("unknownhost") ||
            m.contains("connect timed out") ||
            m.contains("connection refused") ||
            m.contains("network is unreachable") ||
            m.contains("timeout")
    }

    private fun isGitHubUrl(url: String): Boolean =
        url.startsWith("https://") &&
            (url.contains("github.com") ||
                url.contains("githubusercontent.com") ||
                url.contains("github.io"))

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
        val m = message.lowercase()
        return m.contains("unknownhost") ||
            m.contains("timeout") ||
            m.contains("failed to connect") ||
            m.contains("connect exception") ||
            m.contains("network") ||
            m.contains("socket")
    }

    private suspend fun runDownload(item: DownloadItem, skipSocial: Boolean = false) {
        // Social media: ekstrak direct URL menggunakan API publik
        // (tikwm.com untuk TikTok, embed page JSON untuk Instagram, vxtwitter untuk Twitter)
        if (SocialMediaExtractor.isSocialMediaUrl(item.url)) {
            val host = runCatching { java.net.URL(item.url).host }.getOrDefault("?")
            App.logEvent("SOCIAL: extracting direct URL from $host ...")
            val result = SocialMediaExtractor.extract(item.url)
            if (result != null && result.directUrl != item.url) {
                App.logEvent("SOCIAL: extracted direct URL from $host")
                val newName = result.fileName ?: item.fileName
                updateItem(item.id) { it.copy(
                    url = result.directUrl,
                    fileName = if (!item.nameIsCustom) newName else item.fileName
                ) }
                return runDownload(item.copy(url = result.directUrl, fileName = newName), skipSocial = true)
            }
            // Ekstraksi gagal — jangan download URL asli (hasilnya HTML)
            throw IOException(
                "Could not extract media from $host. " +
                "The post may be private, deleted, or the platform blocked extraction. " +
                "Try copying the direct video/image URL from your browser."
            )
        }
        val saver = FileSaver(context)
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
                val ranges = probe.getHeaderField("Accept-Ranges") == "bytes"
                // Mirror (proxy GitHub) umumnya tidak mendukung Range, jadi
                // lewati multi-segmen untuk URL cadangan.
                if (ranges && total >= SEGMENT_MIN_BYTES &&
                    StoragePrefs.segmentCount(context) > 1 && item.mirrors.isEmpty()
                ) {
                    useSegments = true
                    segmentedTotal = total
                }
            }
        } catch (_: Exception) {
            // HEAD tidak didukung; lanjut dengan GET biasa
        } finally {
            probe.disconnect()
        }
        coroutineContext.ensureActive()

        if (useSegments) {
            runSegmented(item, saver, throttle, segmentedTotal, probeHeaders)
            return
        }

        val conn = openAuthenticatedConnection(
            item.url, method = if (item.method == "POST") "POST" else "GET",
            username = item.username,
            password = item.password,
            headers = item.headers
        ) { connection, _ ->
            if (item.method == "POST" && item.postBody.isNotEmpty()) {
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

    private suspend fun runSingle(
        item: DownloadItem,
        conn: HttpURLConnection,
        saver: FileSaver,
        throttle: SpeedThrottle
    ) {
        var downloaded = item.bytesDownloaded
        var fileName = item.fileName
        var partialFile = saver.partialFile(fileName)
        val health = DownloadHealthWatchdog(
            if (item.speedLimitKbps > 0) item.speedLimitKbps else StoragePrefs.speedLimitKbps(context)
        )
        // Resume anti-korup: catatan bytes harus sinkron dengan ukuran file
        // parsial. Bila file sedikit LEBIH PANJANG dari catatan (catatan
        // tertinggal <1 dtk dari tick progres terakhir — sangat umum saat
        // koneksi putus di tengah interval), cukup pangkas ke posisi catatan
        // lalu lanjut: sebelumnya tiap putus jaringan mengulang unduhan dari
        // nol karena file selalu tampak "lebih maju". Mulai dari nol hanya
        // bila data benar-benar hilang (file lebih pendek dari catatan / sisa
        // .part tanpa catatan sama sekali).
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
        coroutineContext.ensureActive()
        trackConnection(item.id, conn)
        try {
        if (downloaded > 0) conn.setRequestProperty("Range", "bytes=$downloaded-")
        conn.connect()

        val code = conn.responseCode
        if (code !in 200..299) throw IOException("HTTP $code")
        captureHeaderChecksum(item, conn)

        val resolvedName = resolveFinalName(item, headersOf(conn))
        if (resolvedName != fileName) {
            val newPartial = saver.partialFile(resolvedName)
            val keepOld = downloaded > 0 && partialFile.exists()
            val renamed = keepOld && partialFile.renameTo(newPartial)
            if (renamed || !keepOld) {
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
            if (actualStart != null && actualStart != downloaded) {
                throw IOException("Server resumed from byte $actualStart, not $downloaded")
            }
            total += downloaded
        } else if (downloaded > 0) {
            // Server tidak mendukung resume; mulai dari awal.
            downloaded = 0
            partialFile.writeBytes(ByteArray(0))
        }
        if (total > 0 && saver.freeBytes() < total) {
            throw IOException(
                "Not enough storage: need ${Formats.bytes(total)}, " +
                    "available ${Formats.bytes(saver.freeBytes())}"
            )
        }

        throttle.reset(downloaded)
        val input = conn.inputStream
        val output = BufferedOutputStream(FileOutputStream(partialFile, true))
        val buffer = ByteArray(BUFFER_SIZE)
        var lastNotify = 0L
        try {
            while (true) {
                val read = input.read(buffer)
                if (read == -1) break
                output.write(buffer, 0, read)
                downloaded += read
                throttle.sleepIfNeeded { downloaded }
                val now = System.currentTimeMillis()
                // Progres di-throttle 1x/detik: salinan daftar + emisi StateFlow
                // (ke UI, notifikasi, SSE) tidak perlu 2x/detik — hemat CPU/GC
                // saat banyak download paralel, UI tetap terasa halus.
                if (now - lastNotify >= 1000) {
                    lastNotify = now
                    coroutineContext.ensureActive()
                    val (speed, eta) = speedTracker.sample(item.id, downloaded, total)
                    health.check(now, downloaded, total, speed)
                    // Progress tick: jangan panggil save penuh (hemat CPU/GC);
                    // progres ringan disimpan berkala oleh scheduleProgressSave.
                    updateItem(item.id, persist = false) {
                        it.copy(
                            state = DownloadState.DOWNLOADING,
                            bytesDownloaded = downloaded,
                            totalBytes = total,
                            speedBps = speed,
                            etaSeconds = eta
                        )
                    }
                    scheduleProgressSave()
                }
            }
            output.flush()
            coroutineContext.ensureActive()
        } finally {
            runCatching { input.close() }
            runCatching { output.close() }
        }

        verifySize(item.id, downloaded, total)

        val published0 = publishItem(saver, partialFile, fileName, item)
        val finalName = published0.fileName ?: fileName
        verifyChecksum(item.id, published0, saver)?.let {
            throw IOException(it)
        }
        val published = organizeIfEnabled(saver, published0, finalName)
        speedTracker.reset(item.id)
        val serverEtag = conn.getHeaderField("ETag").orEmpty()
        updateItem(item.id) {
            it.copy(
                state = DownloadState.COMPLETED,
                fileName = finalName,
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
        }
        flushSave()
        persistCookies()
        App.logEvent("DOWNLOAD COMPLETED: $finalName (${Formats.bytes(downloaded)})")
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
            val resolvedName = resolveFinalName(item, headers)
            fileName = resolvedName
            segments = createSegments(total)
            if (total > 0 && saver.freeBytes() < total) {
                throw IOException(
                    "Not enough storage: need ${Formats.bytes(total)}, " +
                        "available ${Formats.bytes(saver.freeBytes())}"
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
            if (e.message?.contains("does not support Range") == true) {
                // Server/proxy menolak Range (mis. proxy transparan ISP): semua
                // percobaan Range akan gagal selamanya, jadi buang segmen lalu
                // unduh sekali jalan tanpa Range (partial lama ikut dibuang).
                rememberFailedUrl(item.url)
                App.logEvent(
                    "DOWNLOAD ${item.fileName}: Range rejected (${e.message}), " +
                        "downloading again in one pass"
                )
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
                val fallbackConn = openConn(item.url)
                try {
                    fallbackConn.requestMethod = "GET"
                    fallbackConn.connectTimeout = connectTimeoutMs
                    fallbackConn.readTimeout = readTimeoutMs
                    val fbDefaultUa = if (item.url.contains("scontent") && item.url.contains("cdninstagram")) {
                        "Mozilla/5.0 (compatible; Googlebot/2.1; +http://www.google.com/bot.html)"
                    } else DEFAULT_USER_AGENT
                    fallbackConn.setRequestProperty("User-Agent", fbDefaultUa)
                    fallbackConn.setRequestProperty("Accept", "*/*")
                    fallbackConn.setRequestProperty("Accept-Language", "en-US,en;q=0.9,id;q=0.8")
                    val ua = StoragePrefs.getUserAgent(context)
                    if (ua.isNotEmpty() && !item.url.contains("cdninstagram")) fallbackConn.setRequestProperty("User-Agent", ua)
                    try {
                        val origin = java.net.URL(item.url).let { "${it.protocol}://${it.host}" }
                        fallbackConn.setRequestProperty("Referer", "$origin/")
                    } catch (_: Exception) { /* Referer opsional, tidak wajib */ }
                    // Terapkan cookie dari CookieManager (situs yang butuh session)
                    try {
                        val cookieHeader = cookieManager.cookieStore.cookies
                            .filter { c ->
                                c.domain?.let { d ->
                                    item.url.contains(d.removePrefix("."), ignoreCase = true)
                                } ?: false
                            }
                            .joinToString("; ") { "${it.name}=${it.value}" }
                        if (cookieHeader.isNotEmpty()) {
                            fallbackConn.setRequestProperty("Cookie", cookieHeader)
                        }
                    } catch (_: Exception) { /* Cookie opsional */ }
                    applyAuthHeaders(fallbackConn, item)
                    runSingle(item, fallbackConn, saver, throttle)
                } finally {
                    fallbackConn.disconnect()
                }
                return
            }
            throw e
        }

        val current = _items.value.find { it.id == item.id } ?: return
        verifySize(item.id, current.bytesDownloaded, current.totalBytes)

        val merged = saver.mergeSegments(fileName, segments.size)
        val published0 = publishItem(saver, merged, fileName, item)
        val finalName = published0.fileName ?: fileName
        verifyChecksum(item.id, published0, saver)?.let {
            throw IOException(it)
        }
        val published = organizeIfEnabled(saver, published0, finalName)
        speedTracker.reset(item.id)
        updateItem(item.id) {
            it.copy(
                state = DownloadState.COMPLETED,
                fileName = finalName,
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
        }
        flushSave()
        clearSegProgress(item.id)
        App.logEvent("DOWNLOAD COMPLETED: $finalName (${Formats.bytes(current.bytesDownloaded)})")
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
        var lastSegAt = System.currentTimeMillis()
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
        ) { connection, _ ->
            connection.setRequestProperty("Range", "bytes=${segment.start + downloaded}-${segment.end}")
        }
        trackConnection(id, conn)
        try {
            val code = conn.responseCode
            if (code != 206) throw IOException("Server does not support Range (HTTP $code)")

            val input = conn.inputStream
            val output = BufferedOutputStream(FileOutputStream(partial, true))
            val buffer = ByteArray(BUFFER_SIZE)
            var lastNotify = 0L
            try {
                while (true) {
                    val read = input.read(buffer)
                    if (read == -1) break
                    output.write(buffer, 0, read)
                    downloaded += read
                    val sharedTotal = addThrottleTotal(id, read.toLong())
                    throttle.sleepIfNeeded { sharedTotal }
                    val now = System.currentTimeMillis()
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
                        recordSegmentProgress(id, segment.index, downloaded)
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
            val (speed, eta) = speedTracker.sample(id, totalDone, item.totalBytes)
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
    private fun recordSegmentProgress(id: String, index: Int, downloaded: Long) {
        val count = _items.value.find { it.id == id }?.segments?.size ?: return
        val arr = segProgress.getOrPut(id) { LongArray(count) { -1L } }
        arr[index] = downloaded
        scheduleSegFlush(id)
    }

    /** Jadwalkan penggabungan progres; paling banyak SATU job flush per item
     *  (tick 1 dtk dari banyak segmen tidak menumpuk job). */
    private fun scheduleSegFlush(id: String) {
        if (segFlushJobs.containsKey(id)) return
        synchronized(this) {
            if (segFlushJobs.containsKey(id)) return
            segFlushJobs[id] = scope.launch {
                delay(SEG_FLUSH_INTERVAL_MS)
                runCatching { flushSegmentProgress(id) }
                segFlushJobs.remove(id)
            }
        }
    }

    /** Gabungkan semua progres segmen tertunda menjadi SATU updateItem (satu
     *  salinan daftar + satu emisi StateFlow per interval per item). */
    @Synchronized
    private fun flushSegmentProgress(id: String) {
        val pending = segProgress[id] ?: return
        val current = _items.value.find { it.id == id } ?: return
        // Jangan menimpa item yang sudah pause/gagal/selesai.
        if (current.state != DownloadState.DOWNLOADING) return
        val dirty = current.segments.any { seg ->
            seg.index < pending.size &&
                pending[seg.index] >= 0 && pending[seg.index] != seg.downloaded
        }
        if (!dirty) return
        updateItem(id, persist = false) { item ->
            var totalDone = 0L
            val segs = item.segments.map { seg ->
                val p = if (seg.index < pending.size) pending[seg.index] else -1L
                val next = if (p >= 0 && p != seg.downloaded) seg.copy(downloaded = p) else seg
                totalDone += next.downloaded
                next
            }
            val (speed, eta) = speedTracker.sample(id, totalDone, item.totalBytes)
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
        throttleTotals[id] = AtomicLong(value)
    }

    private fun addThrottleTotal(id: String, delta: Long): Long =
        throttleTotals.getOrPut(id) {
            val current = _items.value.find { it.id == id }?.bytesDownloaded ?: 0L
            AtomicLong(current)
        }.addAndGet(delta)

    private fun clearSegProgress(id: String) {
        segProgress.remove(id)
        throttleTotals.remove(id)
        segFlushJobs.remove(id)?.cancel()
    }

    private fun verifySize(id: String, downloaded: Long, total: Long) {
        if (total > 0 && downloaded != total) {
            throw IOException("Size mismatch: expected $total, received $downloaded")
        }
    }

    private fun applyAuthHeaders(conn: HttpURLConnection, item: DownloadItem) {
        applyAuthHeaders(conn, item.username, item.password, item.headers)
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

    private fun createSegments(total: Long): List<DownloadSegment> {
        val count = StoragePrefs.segmentCount(context).coerceAtLeast(2)
        val size = total / count
        return (0 until count).map { i ->
            val start = i * size
            val end = if (i == count - 1) total - 1 else start + size - 1
            DownloadSegment(index = i, start = start, end = end, downloaded = 0)
        }
    }

    private fun parseChecksum(raw: String): Pair<String, String>? {
        val clean = raw.trim()
        if (clean.isEmpty()) return null
        val (algo, rest) = when {
            clean.startsWith("md5:", ignoreCase = true) -> "MD5" to clean.substring(4)
            clean.startsWith("sha1:", ignoreCase = true) -> "SHA-1" to clean.substring(5)
            clean.startsWith("sha256:", ignoreCase = true) -> "SHA-256" to clean.substring(7)
            else -> "MD5" to clean
        }
        val value = rest.trim().lowercase()
        if (value.length < 16) return null
        return algo to value
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
            val md = MessageDigest.getInstance(algo)
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
        val names = listOf(
            "Digest", "X-Checksum-Sha256", "X-Checksum-Sha1",
            "X-Checksum-MD5", "Content-MD5"
        )
        val map = HashMap<String, String>(names.size)
        for (n in names) {
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
    ) {
        update(_items.value.map { if (it.id == id) transform(it) else it }, persist)
    }

    @Synchronized
    private fun update(items: List<DownloadItem>, persist: Boolean = true) {
        // Urutan daftar sudah dijaga (item baru di-prepend), jadi tidak perlu
        // sort ulang O(n log n) setiap kali progress diperbarui (2x/detik).
        _items.value = items
        if (persist) scheduleSave()
    }

    @Synchronized
    private fun scheduleSave() {
        saveJob?.cancel()
        saveJob = scope.launch {
            delay(SAVE_DEBOUNCE_MS)
            repository.save(_items.value)
        }
    }

    /** Progres ringan: JSON kecil (id -> bytes/total) tanpa enkripsi dan tanpa
     *  detail segmen, disimpan paling cepat tiap 2 detik selama download aktif. */
    @Synchronized
    private fun scheduleProgressSave() {
        val now = System.currentTimeMillis()
        if (now - lastProgressSaveAt < PROGRESS_SAVE_INTERVAL_MS) return
        lastProgressSaveAt = now
        progressSaveJob?.cancel()
        progressSaveJob = scope.launch {
            repository.saveProgress(_items.value)
        }
    }

    @Synchronized
    private fun flushSave() {
        saveJob?.cancel()
        saveJob = null
        repository.save(_items.value)
    }

    private fun guessFileName(url: String): String {
        val noQuery = url.substringBefore('?').substringBefore('#')
        val path = noQuery.toUri().lastPathSegment.orEmpty()
        val candidate = path.trim()
        if (candidate.isNotEmpty() && !candidate.contains('=')) return candidate
        return "download_${DEFAULT_NAME_FORMAT.format(LocalDateTime.now())}"
    }

    private val cookiePrefs by lazy {
        context.getSharedPreferences("cookies", android.content.Context.MODE_PRIVATE)
    }

    /** Simpan cookie ke SharedPreferences agar persist antar restart. */
    private fun persistCookies() {
        try {
            val arr = JSONArray()
            cookieManager.cookieStore.cookies.forEach { c ->
                arr.put(JSONObject().apply {
                    put("name", c.name)
                    put("value", c.value)
                    put("domain", c.domain.orEmpty())
                    put("path", c.path.orEmpty())
                })
            }
            cookiePrefs.edit { putString("cookies", arr.toString()) }
        } catch (_: Exception) { /* cookie persist is best-effort */ }
    }

    /** Muat cookie dari SharedPreferences. */
    private fun loadPersistedCookies() {
        try {
            val raw = cookiePrefs.getString("cookies", null) ?: return
            val arr = JSONArray(raw)
            for (i in 0 until arr.length()) {
                val obj = arr.getJSONObject(i)
                val cookie = HttpCookie(obj.getString("name"), obj.getString("value"))
                cookie.domain = obj.getString("domain")
                cookie.path = obj.getString("path")
                cookieManager.cookieStore.add(null, cookie)
            }
        } catch (_: Exception) { /* cookie persist is best-effort */ }
    }

    companion object {
        private val CONTENT_DISPOSITION_STAR = Regex("filename\\*=([^;]+)")
        private val DEFAULT_NAME_FORMAT = DateTimeFormatter.ofPattern("yyyyMMdd_HHmmss")
        private val CONTENT_DISPOSITION_PLAIN = Regex("filename=\"?([^\";]+)\"?")
        private const val BUFFER_SIZE = 64 * 1024
        private const val MAX_REDIRECTS = 5
        private const val HLS_PROBE_MAX_BYTES = 1_000_000
        private const val SEGMENT_MIN_BYTES = 5L * 1024 * 1024
        private const val RETRY_DELAY_1_MS = 5_000L
        private const val RETRY_DELAY_MAX_MS = 300_000L
        private const val MIN_FREE_BYTES = 2L * 1024 * 1024
        private const val SAVE_DEBOUNCE_MS = 400L
        private const val PROGRESS_SAVE_INTERVAL_MS = 2_000L
        private const val SEG_FLUSH_INTERVAL_MS = 500L
        private const val MONITOR_INTERVAL_MS = 30 * 60 * 1000L
        // User-Agent realistis agar situs download tidak memblokir koneksi.
        private const val DEFAULT_USER_AGENT =
            "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Mobile Safari/537.36"
    }
}

internal fun redirectTarget(base: String, location: String?): String? {
    if (location.isNullOrBlank()) return null
    return runCatching {
        val target = URL(URL(base), location)
        if (target.protocol != "http" && target.protocol != "https") return null
        target.toString()
    }.getOrNull()
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

data class HlsVariant(
    val name: String,
    val url: String,
    val bandwidth: Long
)

data class UrlProbe(
    val fileName: String?,
    val sizeBytes: Long,
    val contentType: String?,
    val etag: String? = null
)

private class SpeedThrottle(
    private val limitKbps: Int,
    private val shared: GlobalRateLimiter?
) {
    private val lock = Any()
    private var startTime = System.currentTimeMillis()
    private var startBytes = 0L
    private var lastSeen = 0L

    fun reset(start: Long) {
        synchronized(lock) {
            startTime = System.currentTimeMillis()
            startBytes = start
            lastSeen = start
        }
    }

    suspend fun sleepIfNeeded(totalDownloaded: () -> Long) {
        if (limitKbps <= 0) return
        val delayMs = synchronized(lock) {
            // Total dihitung di sini (bukan saat pemanggilan) supaya download
            // tanpa batas kecepatan tidak membayar biaya scan daftar item.
            val total = totalDownloaded()
            val delta = (total - lastSeen).coerceAtLeast(0L)
            lastSeen = total
            val g = shared
            if (g != null) {
                if (delta <= 0) 0L else g.waitFor(delta)
            } else {
                val limit = limitKbps * 1024L
                val elapsed = System.currentTimeMillis() - startTime
                val expected = startBytes + (elapsed * limit) / 1000L
                if (total > expected) ((total - expected) * 1000L) / limit else 0L
            }
        }
        if (delayMs > 0) delay(delayMs)
    }
}

/** Watchdog per-unduhan: mendeteksi koneksi macet (tanpa byte baru) atau
 *  kecepatan anjlok (di bawah ambang minimum terus-menerus), lalu melempar
 *  IOException supaya handleFailure bisa pindah mirror / retry. Otomatis
 *  nonaktif bila pengguna memasang batas kecepatan di bawah ambang. */
private class DownloadHealthWatchdog(limitKbps: Int) {
    private val limitedLow = limitKbps > 0 && limitKbps * 1024L <= MIN_GOOD_SPEED_BPS
    private var lastBytes = 0L
    private var lastAt = System.currentTimeMillis()
    private var slowSince = 0L

    fun check(now: Long, downloaded: Long, total: Long, speed: Long) {
        if (total <= 0 || downloaded >= total) return
        if (downloaded != lastBytes) {
            lastBytes = downloaded
            lastAt = now
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
private class GlobalRateLimiter(private val limitKbps: Int) {
    private val lock = Any()
    private var windowStart = System.currentTimeMillis()
    private var windowBytes = 0L

    fun waitFor(bytes: Long): Long {
        synchronized(lock) {
            val now = System.currentTimeMillis()
            if (now - windowStart > GLOBAL_WINDOW_MS) {
                windowStart = now
                windowBytes = 0
            }
            windowBytes += bytes
            val limit = limitKbps * 1024L
            val elapsed = now - windowStart
            val targetMs = (windowBytes * 1000L) / limit
            return if (targetMs > elapsed) (targetMs - elapsed) else 0L
        }
    }

    companion object {
        private const val GLOBAL_WINDOW_MS = 10_000L
    }
}
