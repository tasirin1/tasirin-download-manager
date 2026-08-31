package com.tasirin.httpdownloadmanager.remote

import android.content.ContentUris
import android.content.ContentValues
import android.content.Context
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.annotation.SuppressLint
import androidx.core.net.toUri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import android.provider.OpenableColumns
import android.util.Log
import com.tasirin.httpdownloadmanager.App
import com.tasirin.httpdownloadmanager.util.CrashLog
import com.tasirin.httpdownloadmanager.data.DownloadItem
import com.tasirin.httpdownloadmanager.data.DownloadState
import com.tasirin.httpdownloadmanager.util.FileSaver
import com.tasirin.httpdownloadmanager.util.MediaLibrary
import com.tasirin.httpdownloadmanager.util.FileNames
import com.tasirin.httpdownloadmanager.util.Formats
import com.tasirin.httpdownloadmanager.util.MimeTypes
import com.tasirin.httpdownloadmanager.util.StoragePrefs
import com.tasirin.httpdownloadmanager.util.SocialMediaExtractor
import com.tasirin.httpdownloadmanager.util.versionCodeCompat
import fi.iki.elonen.NanoHTTPD
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayInputStream
import java.io.File
import java.io.FileInputStream
import java.io.IOException
import java.io.InputStream
import java.io.RandomAccessFile
import java.io.FileOutputStream
import java.io.BufferedOutputStream
import java.net.Inet4Address
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.Executors
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.RejectedExecutionException
import java.util.zip.ZipOutputStream
import java.net.NetworkInterface
import java.security.MessageDigest

class HttpControlServer(appContext: Context) : NanoHTTPD(StoragePrefs.serverPort(appContext)) {
    // Object ini hidup seumur proses (disimpan statis di App.httpServer):
    // simpan Application context saja, jangan pernah Activity (anti-leak).
    @SuppressLint("StaticFieldLeak")
    private val context: Context = appContext.applicationContext

    @Volatile
    var lastError: String? = null
        private set

    private var cacheCleanupDone = false
    private val serverScope = CoroutineScope(
        SupervisorJob() + Dispatchers.IO + CoroutineExceptionHandler { _, e ->
            runCatching { Log.w("HttpControlServer", "coroutine error", e) }
        }
    )
    private val sseClients = CopyOnWriteArrayList<SseStream>()
    @Volatile private var sseJob: Job? = null
    @Volatile private var sseLastPayload = ""
    @Volatile private var sseLastPushAt = 0L
    private val shareTokens = ConcurrentHashMap<String, ShareEntry>()
    private val shareLock = Any()
    @Volatile private var galleryCache: Pair<Long, MediaLibrary.MediaScanResult>? = null
    private val loginAttempts = ConcurrentHashMap<String, LoginAttempt>()
    private val fsStatsCache = ConcurrentHashMap<String, Pair<Long, Pair<Int, Long>>>()
    private val fsStatsCacheTtlMs = 60_000L
    // Cache listing folder media per root (MediaStore): membrowse halaman demi
    // halaman tidak perlu me-query ulang seluruh koleksi tiap request. Dibatalkan
    // saat ada perubahan media (upload/aksi fs) atau kedaluwarsa 5 dtk.
    private val fsMediaCache = ConcurrentHashMap<String, Pair<Long, Pair<List<String>, List<FsMediaEntry>>>>()
    // Cache listing folder biasa untuk pagination: tanpa ini, tiap offset
    // membaca dan mengurutkan ulang folder besar. Satu slot saja agar RAM tetap
    // kecil di Android 5; aksi tulis langsung membuangnya.
    private class FsFileListing(val path: String, val entries: List<File>, val total: Int)
    @Volatile private var fsFileListingCache: Pair<Long, FsFileListing>? = null
    private val completedUploads = ConcurrentHashMap<String, Pair<String, Long>>()
    private val finalizingUploads = ConcurrentHashMap<String, String>()
    private val failedUploads = ConcurrentHashMap<String, Pair<String, Long>>()
    private val uploadLocks = ConcurrentHashMap<String, UploadLock>()
    private val finalizingGate = Any()
    private val uploadLockGate = Any()
    private val reservedUploadBytes = java.util.concurrent.atomic.AtomicLong()
    private val uploadBufferReservation = Any()
    @Volatile private var cachedUploadBufferBytes = 0L
    @Volatile private var cachedUploadBufferAt = 0L
    // Cache durasi video galeri: metadata tidak berubah, jadi cukup di-hold
    // di memori agar tiap halaman tidak membuka file video berulang-ulang.
    @Volatile private var videoDurationsCache: JSONObject? = null
    // Cache itemsJson berdasarkan signature; satu Pair agar sig & JSON tidak
    // pernah terbaca sebagai versi campuran saat request server paralel.
    @Volatile private var cachedItems: Pair<Int, JSONArray>? = null
    // Cache statusObject: jarang berubah (port, readOnly, versi).
    @Volatile private var cachedStatusJson: JSONObject? = null
    // Statistik folder dihitung paralel: listing folder dengan banyak subfolder
    // tidak lagi menunggu N listFiles() berurutan (lambat di storage TV box).
    // Pool bisa mati saat stopServer() lalu startServer() pada instance yang
    // sama (toggle server di Settings) — liveStatPool() membuat pool baru
    // otomatis supaya listing subfolder tidak gagal setelah restart server.
    @Volatile private var statPool: ThreadPoolExecutor = newStatPool()
    @Volatile private var statPoolEnabled = true
    // Rate limiter per-IP untuk /api/snapshot: cegah client hammered dengan
    // polling cepat (1 req/detik/IP sudah cukup untuk UI responsif).
    private val snapshotLastHit = ConcurrentHashMap<String, Long>()
    private const val SNAPSHOT_RATE_MS = 1_000L
    // Cache allowedFsRoots: dibangun ulang hanya saat settings berubah,
    // bukan setiap request (16x per request file manager).
    @Volatile private var cachedFsRoots: List<File>? = null

    private fun newStatPool(): ThreadPoolExecutor {
        val pool = Executors.newFixedThreadPool(
            Runtime.getRuntime().availableProcessors().coerceIn(2, 4)
        ) as ThreadPoolExecutor
        // Auto-heal: bila pool di-shutdownNow() saat request sedang jalan
        // (race antara liveStatPool() dan stopServer()), pool baru otomatis
        // dibuat supaya request berikutnya tidak selalu gagal dengan
        // RejectedExecutionException.
        pool.rejectedExecutionHandler =
            java.util.concurrent.RejectedExecutionHandler { cmd, _ ->
                synchronized(this@HttpControlServer) {
                    if (statPoolEnabled && statPool.isShutdown) statPool = newStatPool()
                }
                if (statPoolEnabled) {
                    // Pakai referensi lokal agar tidak race dengan stopServer().
                    val active = statPool
                    if (!active.isShutdown) runCatching { active.execute(cmd) }.onFailure { e ->
                        runCatching { logError(e) }
                    }
                }
            }
        return pool
    }

    @Synchronized
    private fun liveStatPool(): ThreadPoolExecutor {
        if (statPoolEnabled && statPool.isShutdown) statPool = newStatPool()
        return statPool
    }
    private var cachedHtml: String? = null
    private val appVersion: String by lazy {
        // SDK 35 menandai versionName nullable — paksa non-null supaya by lazy aman.
        runCatching {
            context.packageManager.getPackageInfo(context.packageName, 0).versionName
        }.getOrNull() ?: "?"
    }
    private val appBuild: Int by lazy {
        runCatching {
            context.packageManager.getPackageInfo(context.packageName, 0).versionCodeCompat().toInt()
        }.getOrDefault(0)
    }
    private val serverLog = ServerLog()
    private val mediaMetaCache = ConcurrentHashMap<String, Pair<Long, MediaMeta>>()
    // Cache QR PNG: /api/qr jarang berubah isinya (URL server + PIN),
    // render bitmap 520x520 tiap panggilan itu boros CPU/RAM.
    private val qrCache = ConcurrentHashMap<String, Pair<Long, ByteArray>>()


    // Cache snapshot terakhir untuk throttle: throttled request mengembalikan
    // JSON yang sama tanpa rebuild, sehingga UI tetap responsif.
    @Volatile private var lastSnapshotJson = "{}"

    /** Bangun snapshot baru DAN update cache. */
    private fun buildSnapshot(): JSONObject {
        val payload = JSONObject()
            .put("items", itemsJson())
            .put("status", statusObject())
        lastSnapshotJson = payload.toString()
        return payload
    }

    /** Kembalikan JSON snapshot cache untuk throttle request. */
    private fun snapshotPayloadCached(): String = lastSnapshotJson

    /** Alamat IP client dari NanoHTTPD session (tanpa port). */
    private fun clientAddress(session: IHTTPSession): String {
        val raw = session.headers["x-forwarded-for"]
            ?: session.remoteIpAddress
        return raw?.substringBefore(",")?.trim().orEmpty()
    }

    override fun serve(session: IHTTPSession): Response {
        // NanoHTTPD internal pool bisa terminated saat stop/start server;
        // tangkap RejectedExecutionException supaya tidak crash.
        if (!isAlive) return newFixedLengthResponse(
            Response.Status.SERVICE_UNAVAILABLE,
            "text/plain; charset=utf-8", "Server restarting"
        )
        val startedAt = System.currentTimeMillis()
        if (!ServerSecurity.isStateChangeAllowed(
                session.method.name, session.uri, session.headers["x-requested-with"]
            )
        ) {
            val denied = newFixedLengthResponse(
                Response.Status.FORBIDDEN,
                "text/plain; charset=utf-8",
                "Forbidden"
            )
            appendRequestLog(session, denied, System.currentTimeMillis() - startedAt)
            return denied
        }
        // Rate-limit per-IP untuk endpoint polling/cache yang sering: mencegah
        // klien/script hammering menguras CPU server saat banyak device meloop.
        if (session.method == Method.GET && session.uri == "/api/snapshot") {
            val ip = clientAddress(session).ifEmpty { "unknown" }
            val now = System.currentTimeMillis()
            val last = snapshotLastHit.getOrDefault(ip, 0L)
            if (now - last < SNAPSHOT_RATE_MS) {
                // Throttle: kembalikan snapshot cache terakhir tanpa rebuild.
                val throttled = newFixedLengthResponse(
                    Response.Status.OK,
                    "application/json; charset=utf-8",
                    snapshotPayloadCached()
                )
                appendRequestLog(session, throttled, System.currentTimeMillis() - startedAt)
                return throttled
            }
            snapshotLastHit[ip] = now
            if (snapshotLastHit.size > 256) {
                val cutoff = now - 60_000L
                snapshotLastHit.entries.removeIf { it.value < cutoff }
            }
        }
        val response = try {
            when {
                session.method == Method.POST && session.uri == "/api/login" -> login(session)
                session.method == Method.GET && session.uri.startsWith("/share/") ->
                    serveShare(session)
                session.method == Method.GET && session.uri.startsWith("/stream_part/") ->
                    servePartial(session)
                pinOk(session) -> when {
                    session.method == Method.GET && session.uri == "/" -> htmlPage()
                    session.method == Method.GET && session.uri == "/api/pin_enabled" ->
                        jsonResponse(JSONObject().put("enabled", pinEnabled()))
                    session.method == Method.GET && session.uri == "/api/downloads" -> downloadsJson()
                    session.method == Method.GET && session.uri == "/api/snapshot" -> snapshotJson()
                    session.method == Method.GET && session.uri == "/api/events" -> sseResponse()
                    session.method == Method.POST && session.uri == "/api/share" -> createShare(session)
                    session.method == Method.GET && session.uri == "/api/qr" -> qrPngResponse(session)
                    session.method == Method.GET && session.uri == "/api/gallery" -> galleryJson(session)
                    session.method == Method.GET && session.uri == "/api/fs" -> fsList(session)
                    session.method == Method.GET && session.uri == "/api/thumb" -> serveThumb(session)
                    session.method == Method.GET && session.uri == "/api/media" -> serveMedia(session)
                    session.method == Method.POST && session.uri == "/api/add" -> addDownload(session)
                    session.method == Method.GET && session.uri.startsWith("/api/social_options") -> socialOptions(session)
                    session.method == Method.POST && session.uri == "/api/upload" -> handleUpload(session)
                    session.method == Method.GET && session.uri == "/api/upload_verify" -> uploadVerify(session)
                    session.method == Method.POST && session.uri == "/api/action" -> runAction(session)
                    session.method == Method.POST && session.uri == "/api/fs_action" -> fsAction(session)
                    session.method == Method.GET && session.uri == "/api/fs_zip" -> fsZip(session)
                    session.method == Method.GET && session.uri == "/api/media_zip" -> mediaZip(session)
                    session.method == Method.GET && session.uri.startsWith("/file/") -> serveFile(session)
                    session.method == Method.POST && session.uri == "/api/logout" -> logout()
                    else -> newFixedLengthResponse(
                        Response.Status.NOT_FOUND,
                        "text/plain; charset=utf-8",
                        "Not found"
                    )
                }
                session.method == Method.GET && session.uri == "/" -> loginPage("")
                else -> unauthorized()
            }
        } catch (e: RejectedExecutionException) {
            appendLog("Server pool terminated, request rejected")
            newFixedLengthResponse(
                Response.Status.SERVICE_UNAVAILABLE,
                "text/plain; charset=utf-8",
                "Server restarting, try again"
            )
        } catch (_: BodyTooLargeException) {
            newFixedLengthResponse(
                Response.Status.PAYLOAD_TOO_LARGE,
                "text/plain; charset=utf-8",
                "Request body too large"
            ).closeConnection()
        } catch (e: Exception) {
            logError(e)
            newFixedLengthResponse(
                Response.Status.INTERNAL_ERROR,
                "text/plain; charset=utf-8",
                "Internal server error"
            )
        }
        secureHeaders(session.uri, response)
        appendRequestLog(session, response, System.currentTimeMillis() - startedAt)
        return response
    }

    private fun appendRequestLog(session: IHTTPSession, response: Response, elapsedMs: Long) {
        // Endpoint polling/media/thumb/listing dipanggil terus saat halaman
        // aktif; tanpa filter ini buffer hanya berisi request rutin.
        if (shouldSkipRequestLog(
                session.method.name, response.status.requestStatus, session.uri
            )
        ) {
            return
        }
        val remote = session.remoteIpAddress.orEmpty()
        val query = session.queryParameterString?.take(160)
            ?.replace(REQUEST_SECRET_RE, "$1<redacted>")
            ?.let { "?$it" }
            .orEmpty()
        appendLog(
            "${session.method.name} ${session.uri}$query -> HTTP ${response.status.requestStatus} " +
                "(${elapsedMs}ms) $remote"
        )
    }

    @Synchronized
    fun startServer() {
        if (isAlive) return
        // Retry loop: NanoHTTPD internal pool bisa belum ready setelah stop();
        // tunggu & coba lagi sampai 3x (total ~600ms) supaya tidak crash.
        invalidateFsRootsCache()
        invalidateStatusCache()
        statPoolEnabled = true
        // Bila statPool terminated (stopServer() sebelumnya), buat baru supaya
        // request pertama setelah restart tidak gagal dengan RejectedExecutionException.
        if (statPool.isTerminated) statPool = newStatPool()
        var lastEx: IOException? = null
        for (attempt in 1..3) {
            try {
                super.start(SERVER_SOCKET_TIMEOUT_MS)
                lastError = null
                cleanupCache()
                appendLog(
                    "SERVER STARTED on port $listeningPort (Android ${Build.VERSION.RELEASE} " +
                        "API ${Build.VERSION.SDK_INT}, ${Build.MANUFACTURER} ${Build.MODEL}, " +
                        "free storage ${Formats.bytes(App.engine.freeSpaceBytes())})"
                )
                return
            } catch (e: IOException) {
                lastEx = e
                if (attempt < 3) {
                    appendLog("SERVER START RETRY $attempt/3: ${e.message}")
                    try { Thread.sleep(200) } catch (_: InterruptedException) {
                        Thread.currentThread().interrupt()
                    }
                }
            }
        }
        lastError = lastEx?.message
        appendLog("SERVER FAILED TO START: ${lastEx?.message}")
        throw lastEx ?: IOException("Unknown server startup failure")
    }

    private fun cleanupCache() {
        if (cacheCleanupDone) return
        cacheCleanupDone = true
        runCatching {
            val now = System.currentTimeMillis()
            val thumbs = File(context.cacheDir, "thumbs")
            if (thumbs.isDirectory) {
                val files = thumbs.listFiles()?.filter { it.isFile }
                    ?.sortedByDescending { it.lastModified() } ?: return
                val keep = 300
                val maxAge = 7L * 24 * 60 * 60 * 1000
                files.forEachIndexed { i, f ->
                    if (i >= keep || now - f.lastModified() > maxAge) f.delete()
                }
            }
            val tmpMaxAge = 24L * 60 * 60 * 1000
            context.cacheDir.listFiles()?.forEach { f ->
                if (f.isFile && now - f.lastModified() > tmpMaxAge &&
                    (f.name.startsWith("up_") || f.name.startsWith("fszip"))
                ) {
                    f.delete()
                }
            }
        }
    }

    @Synchronized
    fun stopServer() {
        if (!isAlive && statPool.isTerminated) return
        appendLog("SERVER STOPPED (port $listeningPort)")
        statPoolEnabled = false
        sseJob?.cancel()
        sseJob = null
        // upload finalization coroutine yang berjalan di serverScope akan
        // selesai secara natural (beberapa ms saja) — jangan cancel scope
        // karena bisa memutus operasi tulis file tengah jalan.
        val frame = "data: {\"shutdown\":true}\n\n"
        sseClients.forEach { it.push(frame) }
        sseClients.forEach { it.closeStream() }
        sseClients.clear()
        shareTokens.clear()
        // Server bisa dimatikan lalu dinyalakan ulang (ganti port / stop-start
        // di Settings) — pool statistik ikut dihentikan; liveStatPool()
        // membuat pool baru otomatis saat dibutuhkan lagi.
        runCatching { statPool.shutdownNow() }
        super.stop()
        // Tunggu sebentar supaya NanoHTTPD internal pool benar-benar terminated
        // sebelum client request berikutnya datang (cegah RejectedExecutionException).
        try { Thread.sleep(200) } catch (_: InterruptedException) {
            Thread.currentThread().interrupt()
        }
    }

    private fun pinEnabled(): Boolean =
        !StoragePrefs.getServerPin(context).isNullOrEmpty()

    /** PIN tersimpan sudah dinormalisasi jadi hash SHA-256 oleh StoragePrefs;
     *  bandingkan dengan timing konstan (anti bocor lewat timing attack). */
    private fun storedPinHash(): String? = StoragePrefs.storedPinHash(context)

    /** Cache secret cookie sesi agar prefs tidak dibaca pada tiap request. */
    @Volatile private var cachedSessionSecret: String? = null
    @Volatile private var cachedSessionSecretBytes: ByteArray? = null

    private fun pinOk(session: IHTTPSession): Boolean {
        if (storedPinHash() == null) return true
        val expected = StoragePrefs.serverSessionSecret(context)
        val cachedSessionBytes = cachedSessionSecretBytes
        val expectedBytes = if (cachedSessionSecret == expected && cachedSessionBytes != null) {
            cachedSessionBytes
        } else {
            expected.toByteArray(Charsets.UTF_8).also {
                cachedSessionSecret = expected
                cachedSessionSecretBytes = it
            }
        }
        val cookie = session.headers["cookie"] ?: return false
        val pin = run {
            var start = cookie.indexOf("dm_pin=")
            if (start < 0) return@run null
            start += 7 // "dm_pin=".length
            val end = cookie.indexOf(';', start)
            if (end > start) cookie.substring(start, end).trim() else cookie.substring(start).trim()
        } ?: return false
        return MessageDigest.isEqual(pin.toByteArray(Charsets.UTF_8), expectedBytes)
    }

    private fun loginAttempt(ip: String): LoginAttempt =
        loginAttempts.getOrPut(ip.ifEmpty { "unknown" }) { LoginAttempt() }

    private fun pruneLoginAttempts(now: Long) {
        if (loginAttempts.isEmpty()) return
        loginAttempts.entries.removeIf {
            it.value.lockedUntil < now &&
                now - it.value.updatedAt > LOGIN_LOCK_MS
        }
        while (loginAttempts.size > MAX_LOGIN_ATTEMPT_ENTRIES) {
            val oldest = loginAttempts.entries.minByOrNull { it.value.updatedAt } ?: break
            loginAttempts.remove(oldest.key)
        }
    }

    private fun login(session: IHTTPSession): Response {
        val ip = session.remoteIpAddress.ifEmpty { "unknown" }
        val now = System.currentTimeMillis()
        pruneLoginAttempts(now)
        val attempt = loginAttempt(ip)
        if (ServerSecurity.isPinLocked(now, attempt.lockedUntil)) {
            val waitSec = ((attempt.lockedUntil - now) / 1000) + 1
            return loginPage("Too many attempts. Try again in $waitSec seconds.")
        }
        val params = readForm(session)
        val pin = params["pin"].orEmpty()
        val stored = storedPinHash()
        return if (stored != null && StoragePrefs.pinMatches(context, pin)) {
            val sessionSecret = StoragePrefs.serverSessionSecret(context)
            synchronized(attempt) {
                attempt.failures = 0
                attempt.lockedUntil = 0L
                attempt.updatedAt = now
            }
            appendLog("LOGIN OK ($ip)")
            val r = newFixedLengthResponse(
                Response.Status.REDIRECT,
                "text/html",
                "<html><body>OK</body></html>"
            )
            r.addHeader(
                "Set-Cookie",
                "dm_pin=$sessionSecret; Max-Age=2592000; Path=/; HttpOnly; SameSite=Strict"
            )
            r.addHeader("Location", "/")
            r
        } else {
            val failures = synchronized(attempt) {
                attempt.failures += 1
                attempt.updatedAt = now
                val lockUntil = ServerSecurity.pinLockUntilAfter(
                    attempt.failures, MAX_LOGIN_ATTEMPTS, LOGIN_LOCK_MS, now
                )
                if (lockUntil > 0) {
                    attempt.lockedUntil = lockUntil
                    attempt.failures = 0
                }
                attempt.failures
            }
            if (attempt.lockedUntil > now) {
                appendLog("LOGIN LOCKED $LOGIN_LOCK_MS ms (too many attempts, from $ip)")
            } else {
                appendLog("LOGIN FAILED: wrong PIN (attempt $failures, from $ip)")
            }
            loginPage("Wrong PIN, try again.")
        }
    }

    private fun logout(): Response {
        appendLog("LOGOUT")
        StoragePrefs.rotateServerSessionSecret(context)
        cachedSessionSecret = null
        cachedSessionSecretBytes = null
        val r = newFixedLengthResponse(
            Response.Status.REDIRECT,
            "text/html",
            "<html><body>OK</body></html>"
        )
        r.addHeader("Set-Cookie", "dm_pin=; Max-Age=0; Path=/; HttpOnly; SameSite=Strict")
        r.addHeader("Location", "/")
        return r
    }

    private fun loginPage(error: String): Response {
        val html = """<!DOCTYPE html>
<html lang="en">
<head>
<meta charset="utf-8">
<meta name="viewport" content="width=device-width, initial-scale=1">
<title>PIN Required</title>
<style>
  * { box-sizing: border-box; }
  html, body { height: 100%; }
  body {
    font-family: system-ui, -apple-system, "Segoe UI", Roboto, sans-serif;
    margin: 0;
    min-height: 100vh;
    display: flex;
    align-items: center;
    justify-content: center;
    padding: 24px;
    background: linear-gradient(135deg, #0D47A1 0%, #1565C0 45%, #1976D2 100%);
    color: #1c1c1c;
  }
  body::before {
    content: "";
    position: fixed;
    inset: 0;
    background:
      radial-gradient(600px 300px at 15% 8%, rgba(255,255,255,.16), transparent 60%),
      radial-gradient(520px 280px at 85% 92%, rgba(255,255,255,.10), transparent 60%);
    pointer-events: none;
  }
  .card {
    position: relative;
    width: 100%;
    max-width: 384px;
    background: #fff;
    border-radius: 22px;
    padding: 36px 32px 28px;
    box-shadow: 0 24px 60px rgba(0, 18, 55, .38);
    animation: rise .5s cubic-bezier(.2, .8, .3, 1);
  }
  @keyframes rise {
    from { opacity: 0; transform: translateY(18px) scale(.98); }
    to   { opacity: 1; transform: translateY(0) scale(1); }
  }
  .icon {
    width: 64px;
    height: 64px;
    margin: 0 auto 16px;
    display: flex;
    align-items: center;
    justify-content: center;
    border-radius: 18px;
    color: #fff;
    background: linear-gradient(135deg, #0D47A1, #1976D2);
    box-shadow: 0 10px 22px rgba(13, 71, 161, .35);
  }
  .icon svg { width: 32px; height: 32px; }
  h1 { font-size: 22px; margin: 0 0 4px; text-align: center; color: #0d2040; }
  .sub { margin: 0 0 24px; font-size: 14px; color: #6b7a90; text-align: center; }
  label {
    display: block;
    font-size: 13px;
    font-weight: 600;
    color: #33415c;
    margin-bottom: 6px;
  }
  .field { margin-bottom: 18px; }
  .field input {
    width: 100%;
    font-size: 20px;
    letter-spacing: 12px;
    text-align: center;
    padding: 14px;
    border: 2px solid #dbe2ee;
    border-radius: 14px;
    outline: none;
    background: #f7f9fc;
    color: #0d2040;
    font-variant-numeric: tabular-nums;
    transition: border-color .15s, box-shadow .15s, background .15s;
  }
  .field input:focus {
    border-color: #1565C0;
    background: #fff;
    box-shadow: 0 0 0 4px rgba(21, 101, 192, .15);
  }
  button {
    width: 100%;
    font-size: 16px;
    font-weight: 700;
    padding: 14px;
    border: none;
    border-radius: 14px;
    color: #fff;
    background: linear-gradient(135deg, #0D47A1, #1976D2);
    cursor: pointer;
    box-shadow: 0 10px 20px rgba(13, 71, 161, .30);
    transition: transform .12s, box-shadow .12s, filter .12s;
  }
  button:hover { filter: brightness(1.08); transform: translateY(-1px); }
  button:active { transform: translateY(1px); box-shadow: 0 4px 10px rgba(13, 71, 161, .25); }
  .err {
    display: none;
    margin: 16px 0 0;
    padding: 10px 12px;
    border-radius: 12px;
    background: #fdecea;
    color: #b00020;
    font-size: 13px;
    text-align: center;
  }
  .err.show { display: block; animation: shake .35s; }
  @keyframes shake {
    0%, 100% { transform: translateX(0); }
    20% { transform: translateX(-6px); }
    40% { transform: translateX(6px); }
    60% { transform: translateX(-4px); }
    80% { transform: translateX(4px); }
  }
  .foot { margin-top: 20px; font-size: 12px; color: #9aa7bb; text-align: center; }
  @media (max-width: 420px) {
    .card { padding: 28px 22px 24px; }
    body { padding: 16px; }
  }
</style>
</head>
<body>
<div class="card">
  <div class="icon">
    <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"><path d="M21 15v4a2 2 0 0 1-2 2H5a2 2 0 0 1-2-2v-4"/><polyline points="7 10 12 15 17 10"/><line x1="12" y1="15" x2="12" y2="3"/></svg>
  </div>
  <h1>Tasirin Download Manager</h1>
  <p class="sub">Remote server locked &middot; enter the PIN to continue</p>
  <form method="POST" action="/api/login" autocomplete="off">
    <label for="pin">PIN</label>
    <div class="field">
      <input type="password" id="pin" name="pin" placeholder="&#9679;&#9679;&#9679;&#9679;" inputmode="numeric" maxlength="10" autofocus>
    </div>
    <button type="submit">Log in &rarr;</button>
  </form>
  <div class="err ${if (error.isEmpty()) "" else "show"}">&#9888;&#65039; $error</div>
  <div class="foot">Tasirin Download Manager &middot; your local network access</div>
</div>
</body>
</html>"""
        return newFixedLengthResponse(
            Response.Status.OK, "text/html; charset=utf-8", html
        )
    }

    private fun unauthorized(): Response = newFixedLengthResponse(
        Response.Status.UNAUTHORIZED,
        "application/json; charset=utf-8",
        JSONObject().put("ok", false).put("error", "PIN required").toString()
    )

    // Catatan ukuran: nanohttpd 2.3.1 otomatis gzip untuk mime text/* dan
    // application/json (useGzipWhenAccepted) selama client kirim Accept-Encoding: gzip —
    // jadi halaman remote, JSON API, dan login page sudah terkompresi tanpa kode manual.
    // SSE (chunked) sengaja tidak di-gzip agar streaming tetap realtime.
    private fun htmlPage(): Response {
        val html = cachedHtml ?: runCatching {
            context.assets.open("remote.html").bufferedReader().use { it.readText() }
        }.getOrDefault("<h1>Remote page unavailable</h1>").also { cachedHtml = it }
        return newFixedLengthResponse(
            Response.Status.OK,
            "text/html; charset=utf-8",
            html
        ).also { it.addHeader("Cache-Control", "no-store, must-revalidate") }
    }

    private fun downloadsJson(): Response {
        return jsonResponse(downloadsPayload())
    }

    /** Gabungan daftar download + status: satu request untuk polling pengaman. */
    private fun snapshotJson(): Response {
        val payload = buildSnapshot()
        return jsonResponse(payload)
    }

    private fun downloadsPayload(): JSONObject {
        return JSONObject().put("items", itemsJson())
    }

    /** Signature ringkas daftar item: tanpa alokasi JSON, dipakai SSE untuk
     *  memutuskan apakah payload perlu di-build ulang (hemat GC di tick 2x/detik). */
    private fun itemsSignature(items: List<DownloadItem>): Int {
        var h = 0
        items.forEach { item ->
            h = h * 31 + item.id.hashCode() * 7 + item.state.hashCode() * 13 +
                item.fileName.hashCode() * 11 + item.totalBytes.hashCode() * 17 +
                item.bytesDownloaded.hashCode() * 19 + item.progressPercent.hashCode() * 23 +
                item.speedBps.hashCode() * 29 + item.etaSeconds.hashCode() * 31 +
                item.speedLimitKbps.hashCode() * 37 + item.finishedAt.hashCode() * 41 +
                item.checksumVerified.hashCode() * 43 + (item.error?.hashCode() ?: 0) * 47
        }
        return h
    }

    private fun itemsJson(): JSONArray {
        val items = App.engine.items.value
        val sig = itemsSignature(items)
        cachedItems?.let { (cachedSig, json) -> if (cachedSig == sig) return json }
        val arr = JSONArray()
        items.forEach { item ->
            val o = JSONObject()
            o.put("id", item.id)
            o.put("fileName", item.fileName)
            o.put("url", item.url)
            o.put("state", item.state.name)
            o.put("bytesDownloaded", item.bytesDownloaded)
            o.put("totalBytes", item.totalBytes)
            o.put("progress", item.progressPercent)
            o.put("speedBps", item.speedBps)
            o.put("etaSeconds", item.etaSeconds)
            o.put("speedLimitKbps", item.speedLimitKbps)
            o.put("addedAt", item.addedAt)
            o.put("finishedAt", item.finishedAt)
            item.error?.let { o.put("error", it) }
            arr.put(o)
        }
        cachedItems = sig to arr
        return arr
    }

    private fun addDownload(session: IHTTPSession): Response {
        val params = readForm(session)
        val url = params["url"]?.trim().orEmpty()
        if (url.isEmpty()) {
            return jsonResponse(JSONObject().put("ok", false).put("error", "empty url"))
        }
        val speed = params["speedLimitKbps"]?.toIntOrNull()?.coerceIn(0, 100_000) ?: 0
        val priority = params["priority"]?.toIntOrNull()?.coerceIn(-1, 1) ?: 0
        val checksum = params["checksum"]?.trim().orEmpty()
        val storage = params["storage"]?.trim().orEmpty()
        val folderPath = params["path"]?.trim().orEmpty()
        val method = params["method"]?.trim()?.uppercase().orEmpty()
            .let { if (it == "POST") "POST" else "GET" }
        val postBody = params["postBody"]?.trim().orEmpty()
        val headers = params["headers"]?.trim().orEmpty()
        val preferredHeight = params["preferredHeight"]?.toIntOrNull()?.coerceIn(0, 4320) ?: 0
        if (!isRemoteDestinationAllowed(folderPath)) {
            return jsonResponse(
                JSONObject().put("ok", false).put("error", "Destination folder not allowed")
            ).closeConnection()
        }
        App.engine.addDownload(
            url = url,
            fileName = params["name"],
            headers = headers,
            method = method,
            postBody = postBody,
            speedLimitKbps = speed,
            priority = priority,
            checksum = checksum,
            destination = storage,
            folderPath = folderPath,
            preferredHeight = preferredHeight
        )
        return jsonResponse(JSONObject().put("ok", true))
    }


    /** Endpoint untuk opsi kualitas media sosial */
    private fun socialOptions(session: IHTTPSession): Response {
        val url = session.param("url")?.trim().orEmpty()
        if (url.isEmpty()) {
            return jsonResponse(JSONObject().put("ok", false).put("error", "empty url"))
        }
        val isYouTube = url.contains("youtube.com/") || url.contains("youtu.be/")
        if (isYouTube) {
            // YouTube: daftar resolusi tetap (sesuai native app)
            val arr = JSONArray()
            val heights = intArrayOf(1080, 720, 480, 360, 240)
            for (h in heights) {
                arr.put(JSONObject()
                    .put("label", "${h}p")
                    .put("preferredHeight", h)
                )
            }
            return jsonResponse(
                JSONObject().put("ok", true).put("platform", "youtube").put("options", arr)
            )
        }
        // Hanya proses URL media sosial yang dikenal; tolak URL acak (SSRF guard)
        if (!SocialMediaExtractor.isSocialMediaUrl(url)) {
            return jsonResponse(
                JSONObject().put("ok", true).put("platform", "none").put("options", JSONArray())
            )
        }
        // Platform lain: ekstrak opsi dari social media extractor
        val results = try {
            kotlinx.coroutines.runBlocking {
                SocialMediaExtractor.extractAll(url)
            }
        } catch (_: Exception) { emptyList() }
        val photos = JSONArray()
        val videos = JSONArray()
        for (r in results) {
            val isVideo = r.mimeType.startsWith("video")
            val label = r.quality.takeIf { it.isNotBlank() }
                ?: r.mimeType.takeIf { it.isNotBlank() }
                ?: if (isVideo) "Video" else "Photo"
            val obj = JSONObject()
                .put("label", label)
                .put("url", r.directUrl)
                .put("fileName", r.fileName ?: "")
                .put("quality", r.quality)
                .put("mimeType", r.mimeType)
                .put("cookies", r.cookies)
            if (isVideo) videos.put(obj) else photos.put(obj)
        }
        return jsonResponse(
            JSONObject().put("ok", true)
                .put("platform", if (results.isNotEmpty()) "social" else "none")
                .put("photos", photos)
                .put("videos", videos)
        )
    }

    private fun handleUpload(session: IHTTPSession): Response {
        if (StoragePrefs.isServerReadOnly(context)) {
            return readOnlyDenied().closeConnection()
        }
        val name = session.param("name")?.trim()?.filterNot { it.isISOControl() }?.take(180)
            ?.replace("/", "_")?.replace("\\", "_")?.replace("\"", "_")?.replace("..", "_")
            ?.takeIf { it.isNotEmpty() }
            ?: "upload_${System.currentTimeMillis()}"
        val storage = session.param("storage")?.trim().orEmpty()
        val folderPath = session.param("path")?.trim().orEmpty()
        if (!isRemoteDestinationAllowed(folderPath)) {
            return jsonResponse(
                JSONObject().put("ok", false).put("error", "Destination folder not allowed")
            )
        }
        val chunkIdx = session.param("chunk")?.toIntOrNull() ?: -1
        val chunks = (session.param("chunks")?.toIntOrNull() ?: 1).coerceAtLeast(1)
        val length = (session.headers["content-length"]?.toLongOrNull() ?: 0L)

        if (chunkIdx >= 0) {
            return handleUploadChunk(session, name, storage, folderPath, chunkIdx, chunks, length)
        }

        if (length <= 0 || length > MAX_UPLOAD_BYTES) {
            return jsonResponse(
                JSONObject().put("ok", false)
                    .put("error", "Invalid size (max ${MAX_UPLOAD_MB} MB)")
            ).closeConnection()
        }
        if (App.engine.freeSpaceBytes() < length) {
            return jsonResponse(
                JSONObject().put("ok", false)
                    .put("error", "Not enough storage for upload")
            ).closeConnection()
        }
        val finalName = uploadUniqueName(name, folderPath)
        return runCatching {
            val published = App.engine.importStream(finalName, storage, folderPath, length) { out ->
                copyUploadBody(session, length, out)
            }
            jsonResponse(JSONObject().put("ok", true).put("name", published.fileName ?: finalName))
        }.getOrElse {
            jsonResponse(JSONObject().put("ok", false).put("error", it.message ?: "upload failed"))
        }
    }

    private fun uploadVerify(session: IHTTPSession): Response {
        val id = session.param("id")?.trim().orEmpty()
        val token = session.param("verify")?.trim()
        if (!ServerSecurity.isUploadVerifyTokenValid(
                token,
                id,
                System.currentTimeMillis(),
                StoragePrefs.partialStreamSecret(context)
            )
        ) {
            return jsonResponse(JSONObject().put("ok", false))
        }
        completedUploads[id]?.let {
            return jsonResponse(JSONObject().put("ok", true).put("name", it.first))
        }
        failedUploads[id]?.let {
            return jsonResponse(JSONObject().put("ok", false).put("error", it.first))
        }
        finalizingUploads[id]?.let {
            return jsonResponse(JSONObject().put("ok", false).put("pending", true).put("name", it))
        }
        return jsonResponse(JSONObject().put("ok", false))
    }

    private fun pruneFsStats() {
        val cutoff = System.currentTimeMillis() - fsStatsCacheTtlMs
        fsStatsCache.entries.removeAll { it.value.first < cutoff }
    }

    private fun pruneCompletedUploads() {
        val cutoff = System.currentTimeMillis() - 30 * 60 * 1000L
        completedUploads.entries.removeIf { it.value.second < cutoff }
        failedUploads.entries.removeIf { it.value.second < cutoff }
        // Bila masih melebihi batas, buang yang paling lama.
        // Pakai minOrNull() alih-alih sortedDescending() supaya tidak
        // mengalokasi List sementara tiap kali dipanggil.
        if (completedUploads.size > 400) {
            val cutoff2 = completedUploads.values.map { it.second }.minOrNull() ?: cutoff
            completedUploads.entries.removeIf { it.value.second <= cutoff2 }
        }
        if (failedUploads.size > 400) {
            val cutoff2 = failedUploads.values.map { it.second }.minOrNull() ?: cutoff
            failedUploads.entries.removeIf { it.value.second <= cutoff2 }
        }
    }

    private fun uploadUniqueName(name: String, folderPath: String): String {
        val clean = folderPath.trim().removePrefix("f:")
        if (clean.isBlank() || clean.startsWith("m:")) return name
        // Tolak path dengan traversal (defense in depth)
        if (clean.contains("..")) return name
        val dir = File(clean)
        if (!dir.isDirectory) return name
        return FileNames.unique(name) { File(dir, it).exists() }
    }

    private fun handleUploadChunk(
        session: IHTTPSession,
        name: String,
        storage: String,
        folderPath: String,
        chunkIdx: Int,
        chunks: Int,
        length: Long
    ): Response {
        val id = session.param("id")?.trim()?.take(64)
            ?.takeIf { ServerSecurity.isUploadIdAllowed(it) }
        ?: run {
                drainBody(session)
                return jsonResponse(JSONObject().put("ok", false).put("error", "Invalid upload id"))
            }
        if (!canAcceptUploadLock(id)) {
            drainBody(session)
            appendLog("UPLOAD #$id REJECTED: too many active upload locks")
            return jsonResponse(JSONObject().put("ok", false).put("error", "Too many uploads in progress"))
        }
        if (chunkIdx < 0 || chunkIdx >= chunks || chunks > MAX_UPLOAD_CHUNKS) {
            drainBody(session)
            return jsonResponse(JSONObject().put("ok", false).put("error", "Invalid chunk range"))
        }
        // Upload sudah selesai / sedang difinalisasi: balas cepat. Body tetap
        // dibaca & dibuang supaya koneksi keep-alive tidak rusak dan browser
        // tidak menganggap permintaan gagal (menutup koneksi saat body masih
        // dikirim = XHR error di client).
        completedUploads[id]?.let { done ->
            drainBody(session)
            appendLog("UPLOAD #$id chunk ${chunkIdx + 1}/$chunks already done -> ok")
            return jsonResponse(JSONObject().put("ok", true).put("name", done.first))
        }
        finalizingUploads[id]?.let { pendingName ->
            drainBody(session)
            appendLog("UPLOAD #$id chunk ${chunkIdx + 1}/$chunks finalizing -> pending")
            return jsonResponse(JSONObject().put("ok", true).put("pending", true).put("name", pendingName))
        }
        if (length > MAX_UPLOAD_BYTES) {
            appendLog("UPLOAD #$id chunk ${chunkIdx + 1}/$chunks REJECTED: chunk too large")
            return jsonResponse(
                JSONObject().put("ok", false)
                    .put("error", "Chunk too large (max ${MAX_UPLOAD_MB} MB)")
            ).closeConnection()
        }
        synchronized(uploadBufferReservation) {
            val now = System.currentTimeMillis()
            if (now - cachedUploadBufferAt > 10_000 || cachedUploadBufferBytes <= 0) {
                cachedUploadBufferBytes = context.cacheDir.listFiles()
                    ?.filter { it.isFile && it.name.startsWith("up_") }
                    ?.sumOf { it.length() } ?: 0L
                cachedUploadBufferAt = now
            }
            val totalUploadBytes = cachedUploadBufferBytes + reservedUploadBytes.get() + length
            if (totalUploadBytes > MAX_UPLOAD_BUFFER_BYTES ||
                App.engine.freeSpaceBytes() < totalUploadBytes
            ) {
                appendLog("UPLOAD #$id chunk ${chunkIdx + 1}/$chunks REJECTED: upload buffer/storage limit")
                return jsonResponse(
                    JSONObject().put("ok", false)
                        .put("error", "Upload buffer or storage limit reached")
                ).closeConnection()
            }
            reservedUploadBytes.addAndGet(length)
        }
        try {
            return writeReservedUploadChunk(
                session, id, name, storage, folderPath,
                chunkIdx, chunks, length
            )
        } finally {
            reservedUploadBytes.addAndGet(-length)
        }
    }

    private fun writeReservedUploadChunk(
        session: IHTTPSession,
        id: String,
        name: String,
        storage: String,
        folderPath: String,
        chunkIdx: Int,
        chunks: Int,
        length: Long
    ): Response {
        val offset = session.param("offset")?.toLongOrNull()
            ?: chunkIdx.toLong() * DEFAULT_CHUNK_BYTES
        if (!ServerSecurity.isChunkOffsetAllowed(offset, MAX_UPLOAD_BYTES)) {
            appendLog("UPLOAD #$id chunk ${chunkIdx + 1}/$chunks REJECTED: invalid offset")
            return jsonResponse(JSONObject().put("ok", false).put("error", "invalid offset"))
                .closeConnection()
        }
        if (length > 0 && offset + length > MAX_UPLOAD_BYTES) {
            appendLog("UPLOAD #$id chunk ${chunkIdx + 1}/$chunks REJECTED: invalid upload range")
            return jsonResponse(JSONObject().put("ok", false).put("error", "invalid upload range"))
                .closeConnection()
        }
        appendLog("UPLOAD #$id chunk ${chunkIdx + 1}/$chunks received: $name (${length}B offset=$offset)")
        val lock = uploadLockFor(id)
        synchronized(lock.lock) {
            return writeUploadChunk(
                session, id, name, storage, folderPath,
                chunkIdx, chunks, length, offset
            )
        }
    }

    private fun uploadLockFor(id: String): UploadLock {
        synchronized(uploadLockGate) {
            val now = System.currentTimeMillis()
            if (uploadLocks.size > 256) {
                uploadLocks.entries.removeIf {
                    now - it.value.lastUse.get() > 30 * 60 * 1000L
                }
            }
            return uploadLocks.getOrPut(id) { UploadLock() }.also { it.lastUse.set(now) }
        }
    }

    private fun canAcceptUploadLock(id: String): Boolean {
        synchronized(uploadLockGate) {
            if (uploadLocks.containsKey(id)) return true
            val now = System.currentTimeMillis()
            if (uploadLocks.size >= MAX_UPLOAD_LOCKS) {
                uploadLocks.entries.removeIf { now - it.value.lastUse.get() > 30 * 60 * 1000L }
            }
            return uploadLocks.size < MAX_UPLOAD_LOCKS
        }
    }

    private fun writeUploadChunk(
        session: IHTTPSession,
        id: String,
        name: String,
        storage: String,
        folderPath: String,
        chunkIdx: Int,
        chunks: Int,
        length: Long,
        offset: Long
    ): Response {
        val tmp = File(context.cacheDir, "up_$id.tmp")
        var resultName = name
        return runCatching {
            when {
                chunkIdx == 0 && !tmp.isFile -> {
                    // Potongan pertama: buat file baru.
                    tmp.parentFile?.mkdirs()
                    RandomAccessFile(tmp, "rw").use { raf ->
                        raf.setLength(0)
                        copyUploadBody(session, length, RandomAccessOutputStream(raf))
                    }
                }
                !tmp.isFile -> {
                    appendLog("UPLOAD #$id chunk ${chunkIdx + 1}/$chunks FAILED: must start from the first chunk")
                    return jsonResponse(
                        JSONObject().put("ok", false).put("error", "Upload must start from the first chunk")
                    ).closeConnection()
                }
                else -> {
                    // Tulis di offset persis: retry potongan sama tidak menggandakan data.
                    RandomAccessFile(tmp, "rw").use { raf ->
                        raf.seek(offset)
                        copyUploadBody(session, length, RandomAccessOutputStream(raf))
                    }
                }
            }
            if (chunkIdx == chunks - 1) {
                if (tmp.length() > MAX_UPLOAD_BYTES) {
                    tmp.delete()
                    return jsonResponse(
                        JSONObject().put("ok", false).put("error", "File too large (max ${MAX_UPLOAD_MB} MB)")
                    )
                }
                if (App.engine.freeSpaceBytes() < tmp.length()) {
                    tmp.delete()
                    return jsonResponse(
                        JSONObject().put("ok", false).put("error", "Not enough storage for upload")
                    )
                }
                val finalName = uploadUniqueName(name, folderPath)
                pruneCompletedUploads()
                val verifyToken = ServerSecurity.createUploadVerifyToken(
                    id,
                    System.currentTimeMillis() + 24L * 60 * 60 * 1000L,
                    StoragePrefs.partialStreamSecret(context)
                )
                val accepted = synchronized(finalizingGate) {
                    if (finalizingUploads.size >= MAX_UPLOAD_FINALIZING) {
                        false
                    } else {
                        finalizingUploads[id] = finalName
                        true
                    }
                }
                if (!accepted) {
                    return jsonResponse(
                        JSONObject().put("ok", false)
                            .put("error", "Server busy finalizing uploads")
                    )
                }
                // Data sudah diterima semua. Balas instan, lalu salin file ke
                // tujuan di background supaya client tidak menunggu lama dan
                // tidak memicu retry (sebelumnya: potongan terakhir lambat ->
                // timeout -> kirim ulang -> proses ganda -> "coba lagi" terus).
                appendLog("UPLOAD #$id last chunk received -> finalizing as $finalName")
                serverScope.launch {
                    try {
                        val published = App.engine.importStream(
                            finalName, storage, folderPath, tmp.length()
                        ) { out ->
                            tmp.inputStream().use { it.copyTo(out) }
                        }
                        published.filePath?.let {
                            fsMediaCache.clear()
                            invalidateFsListingCache()
                            invalidateGalleryCache()
                            videoDurationsCache = null
                            MediaLibrary.notifyMediaChanged(context, it)
                        }
                        completedUploads[id] = finalName to System.currentTimeMillis()
                        appendLog("UPLOAD #$id COMPLETED -> $finalName")
                    } catch (e: Exception) {
                        failedUploads[id] = (e.message ?: "finalization failed") to System.currentTimeMillis()
                        appendLog("UPLOAD #$id finalization FAILED: ${e.message}")
                        logError(e)
                    } finally {
                        tmp.delete()
                        finalizingUploads.remove(id)
                    }
                }
                return jsonResponse(
                    JSONObject()
                        .put("ok", true)
                        .put("pending", true)
                        .put("name", finalName)
                        .put("verify", verifyToken)
                )
            }
            appendLog("UPLOAD #$id chunk ${chunkIdx + 1}/$chunks OK ($resultName)")
            jsonResponse(
                JSONObject()
                    .put("ok", true)
                    .put("name", resultName)
                    .put(
                        "verify",
                        ServerSecurity.createUploadVerifyToken(
                            id,
                            System.currentTimeMillis() + 24L * 60 * 60 * 1000L,
                            StoragePrefs.partialStreamSecret(context)
                        )
                    )
            )
        }.getOrElse {
            // Simpan jejak biar bisa dicek lewat Ekspor Log Error.
            appendLog("UPLOAD #$id chunk ${chunkIdx + 1}/$chunks FAILED: ${it.message}")
            (it as? Exception)?.let { e -> logError(e) }
            jsonResponse(JSONObject().put("ok", false).put("error", it.message ?: "upload failed"))
        }
    }

    private fun fsZip(session: IHTTPSession): Response {
        val raw = session.param("path").orEmpty()
        if (raw.isEmpty()) return notFound()
        if (raw.startsWith(FS_PREFIX) && !isFsPathAllowed(raw.removePrefix(FS_PREFIX))) {
            return notFound()
        }
        if (raw.startsWith(MS_PREFIX) && !isMediaStorePathAllowed(raw.removePrefix(MS_PREFIX))) {
            return notFound()
        }
        val folderName = when {
            raw.startsWith(MS_PREFIX) -> raw.removePrefix(MS_PREFIX).trim('/').substringAfterLast('/')
            else -> File(raw.removePrefix(FS_PREFIX)).name
        }.ifEmpty { "folder" }
        val key = "fs:" + raw
        val tmp = zipCached(key) {
            createTempZip { zos ->
                if (raw.startsWith(MS_PREFIX)) {
                    ZipCreator.zipMedia(zos, raw.removePrefix(MS_PREFIX), context)
                } else {
                    ZipCreator.zipFile(
                        zos, File(raw.removePrefix(FS_PREFIX)), "", ::isFsPathAllowed
                    )
                }
            }
        } ?: return notFound()
        if (tmp.length() == 0L) {
            zipCache.remove(key)
            runCatching { tmp.delete() }
            return notFound()
        }
        appendLog("ZIP CREATED: $folderName.zip (${Formats.bytes(tmp.length())})")
        return streamMedia(
            name = "$folderName.zip",
            mime = "application/zip",
            input = FileInputStream(tmp),
            total = tmp.length(),
            rangeHeader = session.headers["range"] ?: session.headers["Range"],
            download = true
        )
    }

    /** ZIP folder/daftar token dibuat sekali lalu dipakai ulang dalam 60 detik.
     *  Browser mengunduh lewat beberapa request Range (206); tanpa cache ini
     *  folder di-zip ulang untuk tiap request (boros CPU + disk di Android TV). */
    private val zipCache = java.util.concurrent.ConcurrentHashMap<String, Pair<Long, File>>()
    private val zipLocks = Array(8) { Any() }
    private val zipEvictionLock = Any()

    private fun zipLockFor(key: String): Any =
        zipLocks[(key.hashCode() and Int.MAX_VALUE) % zipLocks.size]

    private fun zipCached(key: String, create: () -> File?): File? {
        val now = System.currentTimeMillis()
        synchronized(zipLockFor(key)) {
            zipCache[key]?.let { (createdAt, file) ->
                if (now - createdAt < ZIP_CACHE_TTL_MS && file.isFile && file.length() > 0) {
                    return file
                }
                zipCache.remove(key)
                runCatching { file.delete() }
            }
            val file = create() ?: return null
            if (file.length() > ZIP_CACHE_MAX_BYTES) {
                runCatching { file.delete() }
                return null
            }
            // Browser dapat membuka beberapa request Range sekaligus. Kunci per
            // key benar-benar mencegah ZIP yang sama dibuat dua kali; cukup 8
            // strip lock agar map kunci tidak tumbuh tanpa batas.
            zipCache[key] = now to file
            pruneZipCache(now, key)
            return file
        }
    }

    private fun pruneZipCache(now: Long, keepKey: String) {
        synchronized(zipEvictionLock) {
            zipCache.entries.removeIf { (_, value) ->
                val expired = now - value.first >= ZIP_CACHE_TTL_MS
                if (expired) runCatching { value.second.delete() }
                expired
            }
            var totalBytes = zipCache.values.sumOf { it.second.length() }
            val oldest = zipCache.entries.sortedBy { it.value.first }
            for (entry in oldest) {
                if (zipCache.size <= ZIP_CACHE_MAX && totalBytes <= ZIP_CACHE_MAX_BYTES) break
                if (entry.key == keepKey && zipCache.size == 1) continue
                if (!zipCache.remove(entry.key, entry.value)) continue
                val file = entry.value.second
                val size = file.length()
                runCatching { file.delete() }
                totalBytes -= size
            }
        }
    }

    private fun createTempZip(fill: (ZipOutputStream) -> Unit): File? = try {
        File.createTempFile("fszip", ".zip", context.cacheDir).also { tmpFile ->
            try {
                FileOutputStream(tmpFile).use { raw ->
                    BoundedOutputStream(
                        BufferedOutputStream(raw),
                        ZIP_CACHE_MAX_BYTES
                    ).use { bounded ->
                        ZipOutputStream(bounded).use(fill)
                    }
                }
            } catch (e: Exception) {
                runCatching { tmpFile.delete() }
                throw e
            }
        }
    } catch (e: Exception) {
        logError(e)
        null
    }

    private class BoundedOutputStream(
        private val delegate: java.io.OutputStream,
        private val maxBytes: Long
    ) : java.io.OutputStream() {
        private var written = 0L

        override fun write(byte: Int) {
            if (written + 1 > maxBytes) throw IOException("ZIP exceeds cache limit")
            delegate.write(byte)
            written += 1
        }

        override fun write(buffer: ByteArray, offset: Int, length: Int) {
            if (written + length > maxBytes) throw IOException("ZIP exceeds cache limit")
            delegate.write(buffer, offset, length)
            written += length
        }

        override fun flush() = delegate.flush()

        override fun close() = delegate.close()
    }

    /** Token media wajib melewati validasi yang sama dengan endpoint stream:
     * client tidak boleh membungkus path/URI sembarang menjadi token ZIP. */
    private fun isMediaTokenAllowed(token: String): Boolean {
        val raw = MediaLibrary.decodeToken(token) ?: return false
        return when {
            raw.startsWith(FS_PREFIX) -> isFsPathAllowed(raw.removePrefix(FS_PREFIX))
            raw.startsWith("u:") -> isMediaUriAllowed(raw.substring(2).toUri())
            else -> false
        }
    }

    private fun mediaZip(session: IHTTPSession): Response {
        val requestedTokens = session.param("tokens").orEmpty()
            .split(",").filter { it.isNotBlank() }.distinct()
        val paths = session.param("paths").orEmpty().split(",").filter { it.isNotBlank() }
        if (requestedTokens.size + paths.size > MAX_MEDIA_ZIP_TOKENS) return notFound()
        val tokens = requestedTokens.filter(::isMediaTokenAllowed).toMutableList()
        if (tokens.isEmpty() && paths.isEmpty()) return notFound()
        paths.forEach { p ->
            if (p.startsWith(FS_PREFIX)) {
                val f = File(p.removePrefix(FS_PREFIX))
                if (f.exists() && isFsPathAllowed(f.absolutePath)) {
                    tokens.add(MediaLibrary.tokenForPath(f.absolutePath))
                }
            }
        }
        val uniqueTokens = tokens.distinct()
        tokens.clear()
        tokens.addAll(uniqueTokens)
        if (tokens.isEmpty()) return notFound()
        val key = "tokens:" + tokens.sorted().joinToString(",")
        val tmp = zipCached(key) {
            createTempZip { zos ->
                ZipCreator.zipTokens(zos, tokens, context, ::isFsPathAllowed)
            }
        } ?: return notFound()
        if (tmp.length() == 0L) {
            zipCache.remove(key)
            runCatching { tmp.delete() }
            return notFound()
        }
        appendLog("ZIP MEDIA: ${tokens.size} file (${Formats.bytes(tmp.length())})")
        return streamMedia(
            name = "gallery-${tokens.size}-files.zip",
            mime = "application/zip",
            input = FileInputStream(tmp),
            total = tmp.length(),
            rangeHeader = session.headers["range"] ?: session.headers["Range"],
            download = true
        )
    }


    private fun runAction(session: IHTTPSession): Response {
        val params = readForm(session)
        val id = params["id"].orEmpty()
        when (params["action"]) {
            "pause" -> {
                if (id.isEmpty()) return jsonResponse(JSONObject().put("ok", false))
                App.engine.pause(id)
            }
            "resume" -> {
                if (id.isEmpty()) return jsonResponse(JSONObject().put("ok", false))
                App.engine.resume(id)
            }
            "cancel" -> {
                if (id.isEmpty()) return jsonResponse(JSONObject().put("ok", false))
                App.engine.cancel(id)
            }
            "delete" -> {
                if (id.isEmpty()) return jsonResponse(JSONObject().put("ok", false))
                App.engine.remove(id)
            }
            "rename" -> {
                if (id.isEmpty()) return jsonResponse(JSONObject().put("ok", false))
                val name = params["name"]?.trim().orEmpty()
                if (name.isBlank() || name.contains('/') || name.contains('\\')) {
                    return jsonResponse(JSONObject().put("ok", false).put("error", "invalid name"))
                }
                App.engine.rename(id, name)
            }
            "limit_priority" -> {
                if (id.isEmpty()) return jsonResponse(JSONObject().put("ok", false))
                val speed = params["speedLimitKbps"]?.toIntOrNull()
                    ?.coerceIn(0, 100_000) ?: 0
                val priority = params["priority"]?.toIntOrNull()?.coerceIn(-1, 1) ?: 0
                App.engine.setLimitAndPriority(id, speed, priority)
            }
            "pause_all" -> App.engine.pauseAll()
            "resume_all" -> App.engine.resumeAll()
            "retry_failed" -> App.engine.retryFailed()
            "clear_completed" -> App.engine.clearCompleted()
            else -> return jsonResponse(
                JSONObject().put("ok", false).put("error", "unknown action")
            )
        }
        return jsonResponse(JSONObject().put("ok", true))
    }

    private fun serveFile(session: IHTTPSession): Response {
        val id = session.uri.removePrefix("/file/")
        val item = App.engine.items.value.find {
            it.id == id && it.state == DownloadState.COMPLETED
        } ?: return notFound()
        val download = session.param("dl") == "1"
        val mime = MimeTypes.forFile(item.fileName)
        val input: InputStream
        val total: Long
        val stream: InputStream
        if (!item.filePath.isNullOrEmpty()) {
            val file = File(item.filePath)
            if (!file.exists() || !file.isFile || !isFsPathAllowed(file.absolutePath)) {
                return notFound()
            }
            stream = FileInputStream(file)
            total = file.length()
        } else if (!item.contentUri.isNullOrEmpty()) {
            val uri = item.contentUri.toUri()
            if (!isMediaUriAllowed(uri)) return notFound()
            val resolver = context.contentResolver
            val rawStream = resolver.openInputStream(uri) ?: return notFound()
            val len = resolver.openAssetFileDescriptor(uri, "r")?.use { it.length } ?: -1L
            stream = rawStream
            total = len
        } else {
            return notFound()
        }

        return try {
            streamMedia(
                name = item.fileName,
                mime = mime,
                input = stream,
                total = total,
                rangeHeader = session.headers["range"] ?: session.headers["Range"],
                download = download
            )
        } catch (e: Exception) {
            runCatching { stream.close() }
            throw e
        }
    }

    fun createPartialStreamUrl(itemId: String): String {
        val expiresAt = System.currentTimeMillis() + PARTIAL_STREAM_TTL_MS
        val secret = partialStreamSecret()
        val token = ServerSecurity.createPartialToken(itemId, expiresAt, secret)
        return "/stream_part/$itemId?token=$token&expires=$expiresAt"
    }

    private fun partialStreamSecret(): String =
        StoragePrefs.partialStreamSecret(context)

    private fun servePartial(session: IHTTPSession): Response {
        val id = session.uri.removePrefix("/stream_part/")
        val now = System.currentTimeMillis()
        val secret = partialStreamSecret()
        val token = session.param("token").orEmpty()
        if (!ServerSecurity.isPartialTokenValid(token, id, now, secret)) return notFound()
        val item = App.engine.items.value.find { it.id == id } ?: return notFound()
        if (item.state == DownloadState.COMPLETED) return notFound()
        // Stream file parsial (.part) yang masih berjalan; dukung Range biar
        // player eksternal bisa memutar progresif dan seek dalam batas terunduh.
        val mime = MimeTypes.forFile(item.fileName)
        if (item.segments.isNotEmpty()) {
            // Download segmen: gabungkan potongan yang sudah terunduh secara
            // berurutan agar tetap bisa distream (Range relatif ke gabungan).
            val cleanName = FileNames.safe(item.fileName)
            val parts = item.segments.sortedBy { it.index }.mapNotNull { seg ->
                File(File(context.filesDir, "downloads"), "$cleanName.part.${seg.index}")
                    .takeIf { it.isFile }
            }
            if (parts.isEmpty()) return notFound()
            val total = parts.sumOf { it.length() }
            return streamMedia(
                name = item.fileName,
                mime = mime,
                input = ChainInputStream(parts),
                total = total,
                rangeHeader = session.headers["range"] ?: session.headers["Range"],
                download = false
            )
        }
        val cleanName = FileNames.safe(item.fileName)
        val partial = File(File(context.filesDir, "downloads"), "$cleanName.part")
        if (!partial.exists() || !partial.isFile) return notFound()
        return streamMedia(
            name = item.fileName,
            mime = mime,
            input = FileInputStream(partial),
            total = partial.length(),
            rangeHeader = session.headers["range"] ?: session.headers["Range"],
            download = false
        )
    }

    private fun serveThumb(session: IHTTPSession): Response {
        val token = session.param("token").orEmpty()
        if (token.isEmpty()) return notFound()
        val raw = MediaLibrary.decodeToken(token) ?: return notFound()
        return safeRun("serveThumb") {
            val thumb = getOrCreateThumb(context, raw, ::isFsPathAllowed, ::isMediaUriAllowed)
            if (thumb == null) {
                notFound()
            } else {
                newFixedLengthResponse(
                    Response.Status.OK,
                    "image/jpeg",
                    FileInputStream(thumb),
                    thumb.length()
                ).also { it.addHeader("Cache-Control", "public, max-age=86400") }
            }
        } ?: notFound()
    }





    private data class MediaMeta(val name: String, val mime: String?)


    /** Nama/MIME media stabil dalam sesi pemutaran; cache pendek menghindari
     *  query ContentResolver/DocumentFile pada setiap permintaan HTTP 206. */
    private fun cachedMediaMeta(raw: String): MediaMeta {
        val now = System.currentTimeMillis()
        mediaMetaCache[raw]?.let { (at, meta) ->
            if (now - at < MEDIA_META_TTL_MS) return meta
            mediaMetaCache.remove(raw)
        }
        val meta = when {
            raw.startsWith("f:") -> {
                val file = File(raw.substring(2))
                MediaMeta(file.name, null)
            }
            raw.startsWith("u:") -> {
                val uri = raw.substring(2).toUri()
                val resolver = context.contentResolver
                val name = runCatching {
                    resolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)
                        ?.use { c -> if (c.moveToFirst()) c.getString(0) else null }
                }.getOrNull()
                MediaMeta(name ?: "media", runCatching { resolver.getType(uri) }.getOrNull())
            }
            else -> MediaMeta("media", null)
        }
        if (mediaMetaCache.size > MEDIA_META_CACHE_MAX) {
            val toRemove = mediaMetaCache.size / 2
            var removed = 0
            val iter = mediaMetaCache.entries.iterator()
            while (iter.hasNext() && removed < toRemove) {
                iter.next(); iter.remove(); removed++
            }
        }
        mediaMetaCache[raw] = now to meta
        return meta
    }

    private fun serveMedia(session: IHTTPSession): Response {
        val token = session.param("token").orEmpty()
        if (token.isEmpty()) return notFound()
        val raw = MediaLibrary.decodeToken(token) ?: return notFound()
        val download = session.param("dl") == "1"
        val rangeHeader = session.headers["range"] ?: session.headers["Range"]
        val input: InputStream
        val total: Long
        when {
            raw.startsWith("f:") -> {
                val file = File(raw.substring(2))
                if (!file.isFile || !isFsPathAllowed(file.absolutePath)) return notFound()
                total = file.length()
                val range = parseRange(rangeHeader, total)
                val stream = FileInputStream(file).apply {
                    runCatching { channel.position(range?.first ?: 0L) }
                }
                val meta = cachedMediaMeta(raw)
                return try {
                    streamMedia(
                        name = meta.name,
                        mime = meta.mime ?: MimeTypes.forFile(meta.name),
                        input = stream,
                        total = total,
                        rangeHeader = rangeHeader,
                        download = download,
                        prepositioned = true
                    )
                } catch (e: Exception) {
                    runCatching { stream.close() }
                    throw e
                }
            }
            raw.startsWith("u:") -> {
                val uri = raw.substring(2).toUri()
                if (!isMediaUriAllowed(uri)) return notFound()
                val descriptor = context.contentResolver.openAssetFileDescriptor(uri, "r")
                    ?: return notFound()
                total = descriptor.length
                val range = parseRange(rangeHeader, total)
                val stream = PositionedAssetInputStream(descriptor, range?.first ?: 0L)
                val meta = cachedMediaMeta(raw)
                return try {
                    streamMedia(
                        name = meta.name,
                        mime = meta.mime ?: MimeTypes.forFile(meta.name),
                        input = stream,
                        total = total,
                        rangeHeader = rangeHeader,
                        download = download,
                        prepositioned = true
                    )
                } catch (e: Exception) {
                    runCatching { stream.close() }
                    throw e
                }
            }
            else -> return notFound()
        }
    }

    private fun galleryJson(session: IHTTPSession): Response {
        val q = session.param("q")?.trim()?.lowercase().orEmpty()
        val type = session.param("type")?.trim().orEmpty()
        // Penampil foto sudah dihapus; permintaan foto tidak perlu menyentuh disk.
        if (type == "image") {
            return jsonResponse(
                JSONObject().put("items", JSONArray()).put("hasMore", false).put("total", 0)
            )
        }
        val page = (session.param("page")?.toIntOrNull() ?: 0).coerceAtLeast(0)
        val start = page * GALLERY_PAGE_SIZE
        val pageEnd = start + GALLERY_PAGE_SIZE
        // Scanner sudah video-only; type=video bukan filter tambahan. Pencarian
        // nama tetap memakai batas penuh supaya jumlah hasil dan hasMore akurat.
        val scanLimit = if (q.isNotEmpty()) MediaLibrary.GALLERY_MAX_ENTRIES else {
            (pageEnd + GALLERY_PAGE_SIZE).coerceAtMost(MediaLibrary.GALLERY_MAX_ENTRIES)
        }
        val arr = JSONArray()
        val cache = loadVideoDurations()
        var extracted = 0
        var matched = 0
        var pageCount = 0
        val scan = scannedGallery(scanLimit)
        for (e in scan.items) {
            if (q.isNotEmpty() && e.name.indexOf(q, ignoreCase = true) < 0) continue
            if (matched >= start && matched < pageEnd) {
                val o = JSONObject()
                    .put("name", e.name)
                    .put("size", e.size)
                    .put("modified", e.modified)
                    .put("isVideo", true)
                    .put("token", e.token)
                if (!e.isPartial) {
                    var d = videoDurationOf(cache, e.token)
                    if (d <= 0 && e.durationMs > 0) d = e.durationMs
                    if (d <= 0 && extracted < 20) {
                        d = videoDurationMs(e.token)
                        if (d > 0) cacheVideoDuration(cache, e.token, d)
                        extracted++
                    }
                    o.put("durationMs", d)
                }
                arr.put(o)
                pageCount++
            }
            matched++
        }
        if (extracted > 0) saveVideoDurations(cache)
        return jsonResponse(
            JSONObject()
                .put("items", arr)
                .put("hasMore", pageCount >= GALLERY_PAGE_SIZE && (matched < scan.total || scan.items.size < scan.total))
                .put("total", if (q.isNotEmpty()) matched else scan.total)
        )
    }

    /** Galeri berubah (hapus/upload/pindah media) — buang snapshot 15 detik
     *  supaya request berikutnya langsung scan ulang, tidak tampil basi. */
    private fun invalidateGalleryCache() {
        galleryCache = null
    }

    /** Hapus cache MediaStore setelah perubahan file; cache kecil dan event
     *  jarang, sementara pemetaan path fisik ke key relatif rawan salah. */
    private fun invalidateFsMediaCache() {
        fsMediaCache.clear()
    }

    /** Invalidate fsRoots cache saat settings berubah. */
    fun invalidateFsRootsCache() {
        cachedFsRoots = null
        fsFileListingCache = null
        fsMediaCache.clear()
        galleryCache = null
        synchronized(zipEvictionLock) {
            zipCache.values.forEach { (_, file) -> runCatching { file.delete() } }
            zipCache.clear()
        }
    }

    /** Invalidate statusObject cache saat port/readOnly berubah. */
    fun invalidateStatusCache() {
        cachedStatusJson = null
    }

    private fun scannedGallery(maxEntries: Int): MediaLibrary.MediaScanResult {
        val now = System.currentTimeMillis()
        val cached = galleryCache
        if (cached != null && MediaLibrary.scanCacheUsable(
                now - cached.first, GALLERY_SCAN_TTL_MS,
                cached.second.items.size, cached.second.total, maxEntries
            )
        ) return cached.second
        val result = MediaLibrary.scan(context, maxEntries = maxEntries)
        galleryCache = now to result
        return result
    }

    private fun videoDurationMs(token: String): Long {
        val raw = MediaLibrary.decodeToken(token) ?: return 0L
        return safeRun("videoDurationMs") {
            val mmr = MediaMetadataRetriever()
            try {
                when {
                    raw.startsWith("f:") -> {
                        val file = File(raw.substring(2))
                        if (!file.isFile || !isFsPathAllowed(file.absolutePath)) {
                            return@safeRun 0L
                        }
                        mmr.setDataSource(file.absolutePath)
                    }
                    raw.startsWith("u:") -> {
                        val uri = raw.substring(2).toUri()
                        if (!isMediaUriAllowed(uri)) return@safeRun 0L
                        mmr.setDataSource(context, uri)
                    }
                    else -> return@safeRun 0L
                }
                mmr.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: 0L
            } finally {
                runCatching { mmr.release() }
            }
        } ?: 0L
    }

    private val videoDurationLock = Any()

    private fun loadVideoDurations(): JSONObject {
        videoDurationsCache?.let { return it }
        synchronized(videoDurationLock) {
            videoDurationsCache?.let { return it }
            val loaded = runCatching {
                JSONObject(File(context.filesDir, "video_durations.json").readText())
            }.getOrDefault(JSONObject())
            videoDurationsCache = loaded
            return loaded
        }
    }

    /** Baca durasi dari cache bersama — harus sinkron: banyak thread server
     *  (nanohttpd) bisa membuka galeri bersamaan, dan org.json JSONObject
     *  tidak thread-safe (put/optLong paralel bisa korup atau 500). */
    private fun videoDurationOf(cache: JSONObject, token: String): Long =
        synchronized(videoDurationLock) {
            // Prune cache bila terlalu besar (>2000 entries) saat dibaca.
            if (cache.length() > 2000) {
                val iter = cache.keys()
                var removed = 0
                val toRemove = cache.length() - 1500
                while (iter.hasNext() && removed < toRemove) { iter.next(); iter.remove(); removed++ }
            }
            cache.optLong(token, 0L)
        }

    private fun cacheVideoDuration(cache: JSONObject, token: String, ms: Long) {
        synchronized(videoDurationLock) { cache.put(token, ms) }
    }

    private fun saveVideoDurations(cache: JSONObject) {
        synchronized(videoDurationLock) {
            videoDurationsCache = cache
            if (cache.length() == 0) return
            runCatching {
                File(context.filesDir, "video_durations.json").writeText(cache.toString())
            }
        }
    }

    // ---------- File manager ----------

    private fun fsList(session: IHTTPSession): Response {
        val raw = session.param("path").orEmpty()
        val offset = (session.param("offset")?.toIntOrNull() ?: 0).coerceAtLeast(0)
        val limit = (session.param("limit")?.toIntOrNull() ?: FS_PAGE_SIZE)
            .coerceIn(1, FS_PAGE_MAX)
        return when {
            raw.isEmpty() -> fsRoots()
            raw.startsWith(MS_PREFIX) -> fsListMedia(raw.removePrefix(MS_PREFIX), offset, limit)
            else -> fsListFiles(raw.removePrefix(FS_PREFIX), offset, limit)
        }
    }

    private fun fsRoots(): Response {
        val items = JSONArray()
        fun add(name: String, path: String) {
            val obj = JSONObject()
                .put("name", name)
                .put("path", path)
                .put("kind", "dir")
            // Kapasitas media untuk kartu root di remote web (total & bebas).
            val realPath = path.removePrefix(FS_PREFIX)
            runCatching {
                val stat = android.os.StatFs(realPath)
                obj.put("totalBytes", stat.totalBytes)
                obj.put("freeBytes", stat.availableBytes)
            }
            items.put(obj)
        }
        // Root file manager hanya menampilkan folder yang diatur di Pengaturan:
        // folder tujuan (Folder teks) + folder tambahan. Folder aplikasi dan
        // folder standar device tidak lagi ditampilkan biar bersih.
        var any = false
        StoragePrefs.getTextFolder(context)?.let { tf ->
            val f = File(tf)
            if (f.isDirectory) {
                add(f.name, FS_PREFIX + f.absolutePath)
                any = true
            }
        }
        StoragePrefs.getExtraFolders(context).forEach { path ->
            val f = File(path)
            if (f.isDirectory) {
                add(f.name, FS_PREFIX + f.absolutePath)
                any = true
            }
        }
        // Fallback kalau belum ada folder diatur: tampilkan folder Download
        // bawaan supaya file manager tidak kosong.
        if (!any) {
            Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)?.let { d ->
                if (d.isDirectory) add(d.name, FS_PREFIX + d.absolutePath)
            }
        }
        return jsonResponse(JSONObject().put("items", items))
    }

    /** Listing terurut untuk halaman aktif. Folder sangat besar tidak disimpan
     *  di cache (cukup dipakai sekali) supaya slot cache tidak menahan RAM. */
    private fun cachedDirectoryListing(path: String): Pair<Int, List<File>>? {
        val normalized = File(path).absolutePath.trimEnd('/')
        val now = System.currentTimeMillis()
        fsFileListingCache?.let { (at, listing) ->
            if (listing.path == normalized && now - at < FS_LISTING_TTL_MS) {
                return listing.total to listing.entries
            }
        }
        val dir = File(path)
        if (!dir.isDirectory) return null
        val entries = runCatching { dir.listFiles() }.getOrNull()
            ?.sortedWith(
                compareBy<File> { it.isFile }.thenComparator { a, b ->
                    a.name.compareTo(b.name, ignoreCase = true)
                }
            ) ?: return null
        if (entries.size <= FS_LISTING_CACHE_MAX) {
            fsFileListingCache = now to FsFileListing(normalized, entries, entries.size)
        } else {
            fsFileListingCache = null
        }
        return entries.size to entries
    }

    private fun invalidateFsListingCache() {
        fsFileListingCache = null
    }

    private fun fsListFiles(path: String, offset: Int, limit: Int): Response {
        val items = JSONArray()
        val dir = File(path)
        var total = 0
        // Bila folder bukan root yang sah tetapi induk dari salah satu root
        // (mis. /storage/emulated/0 di atas folder tujuan), listing tetap
        // dibolehkan dalam mode browse-only: tanpa statistik subfolder, token,
        // dan tanpa aksi (delete/rename/move/upload tetap ditolak keamanan).
        val allowed = isFsPathAllowed(path)
        val browseOnly = !allowed && isFsBrowseAncestor(path)
        if (dir.isDirectory && (allowed || browseOnly)) {
            val listing = cachedDirectoryListing(path)
            total = listing?.first ?: 0
            // Hanya halaman aktif yang dibangun JSON-nya; statistik subfolder
            // (itemCount/totalSize) dihitung paralel untuk halaman itu saja.
            val page = listing?.second?.drop(offset)?.take(limit).orEmpty()
            val statFutures = if (allowed) {
                page.filter { it.isDirectory }.associateWith { f ->
                    runCatching { liveStatPool().submit<Pair<Int, Long>> { fsStats(f.absolutePath) } }
                        .getOrNull()
                }
            } else {
                emptyMap()
            }
            page.forEach { f ->
                val o = JSONObject()
                o.put("name", f.name)
                o.put("path", FS_PREFIX + f.absolutePath)
                o.put("kind", if (f.isDirectory) "dir" else "file")
                o.put("size", if (f.isFile) f.length() else 0L)
                o.put("modified", f.lastModified())
                if (f.isDirectory) {
                    val (itemCount, totalSize) = if (allowed) {
                        runCatching { statFutures[f]?.get() }.getOrNull() ?: (0 to 0L)
                    } else {
                        0 to 0L
                    }
                    o.put("itemCount", itemCount)
                    o.put("totalSize", totalSize)
                } else if (allowed) {
                    o.put("token", MediaLibrary.tokenForPath(f.absolutePath))
                }
                items.put(o)
            }
        }
        return jsonResponse(
            JSONObject().put("path", path).put("items", items).put("total", total)
        )
    }

    /** Path berada di atas (induk dari) salah satu root yang sah? Hanya untuk
     *  listing browse-only — aksi tulis tetap butuh isFsPathAllowed. */
    /** Tambah header Connection: close (dipakai saat response error). */
    private fun Response.closeConnection(): Response = this.closeConnection()

    /** Validasi nama file: tidak kosong, tidak ada separator path, tidak traversal. */
    private fun isNameValid(name: String): Boolean =
        name.isNotBlank() && '/' !in name && '\\' !in name &&
        name != ".." && !name.startsWith("../") && !name.startsWith("..\\")

    private fun isFsBrowseAncestor(path: String): Boolean =
        ServerSecurity.isBrowseableAncestor(path, allowedFsRoots())

    private fun fsListMedia(relative: String, offset: Int, limit: Int): Response {
        val items = JSONArray()
        var total = 0
        if (!isMediaStorePathAllowed(relative)) return jsonResponse(
            JSONObject().put("items", items).put("total", total)
        )
        if (Build.VERSION.SDK_INT >= 29) {
            val base = relative.trim('/')
            val now = System.currentTimeMillis()
            val cached = fsMediaCache[base]
            val dirNames: List<String>
            val files: List<FsMediaEntry>
            if (cached != null && now - cached.first < FS_MEDIA_CACHE_TTL_MS) {
                dirNames = cached.second.first
                files = cached.second.second
            } else {
                val folder = if (base.isEmpty()) "" else base + "/"
                val resolver = context.contentResolver
                val collection = MediaLibrary.mediaCollectionForRoot(base)
                val projection = arrayOf(
                    MediaStore.MediaColumns._ID,
                    MediaStore.MediaColumns.DISPLAY_NAME,
                    MediaStore.MediaColumns.SIZE,
                    MediaStore.MediaColumns.DATE_MODIFIED,
                    MediaStore.MediaColumns.RELATIVE_PATH
                )
                val selection = if (folder.isEmpty()) null else "${MediaStore.MediaColumns.RELATIVE_PATH} LIKE ?"
                val selArgs = if (folder.isEmpty()) null else arrayOf("$folder%")
                val dirs = LinkedHashSet<String>()
                // Entri file ringan dulu; JSONObject + token Base64 baru dibuat
                // untuk halaman aktif (hemat alokasi saat folder ribuan file).
                val found = mutableListOf<FsMediaEntry>()
                runCatching {
                    resolver.query(collection, projection, selection, selArgs, null)?.use { c ->
                        val iId = c.getColumnIndexOrThrow(MediaStore.MediaColumns._ID)
                        val iName = c.getColumnIndexOrThrow(MediaStore.MediaColumns.DISPLAY_NAME)
                        val iSize = c.getColumnIndexOrThrow(MediaStore.MediaColumns.SIZE)
                        val iMod = c.getColumnIndexOrThrow(MediaStore.MediaColumns.DATE_MODIFIED)
                        val iRel = c.getColumnIndexOrThrow(MediaStore.MediaColumns.RELATIVE_PATH)
                        while (c.moveToNext()) {
                            val relPath = c.getString(iRel) ?: continue
                            if (folder.isNotEmpty() && !relPath.startsWith(folder)) continue
                            val rest = relPath.removePrefix(folder).trim('/')
                            if (rest.isEmpty() || !rest.contains('/')) {
                                val name = c.getString(iName) ?: continue
                                val uri = ContentUris.withAppendedId(collection, c.getLong(iId)).toString()
                                found.add(
                                    FsMediaEntry(
                                        name = name,
                                        path = MS_PREFIX + uri,
                                        size = c.getLong(iSize),
                                        modified = c.getLong(iMod) * 1000L,
                                        uri = uri
                                    )
                                )
                            } else {
                                dirs.add(rest.substringBefore('/'))
                            }
                        }
                    }
                }
                dirNames = dirs.sortedWith(String.CASE_INSENSITIVE_ORDER)
                files = found
                // Cache hanya daftar berukuran wajar; folder raksasa tidak ditahan di RAM.
                if (files.size <= FS_MEDIA_CACHE_MAX_FILES) {
                    fsMediaCache[base] = now to (dirNames to files)
                    if (fsMediaCache.size > FS_MEDIA_CACHE_MAX_ENTRIES) {
                        val toRemove = fsMediaCache.size / 2
                        var removed = 0
                        val iter = fsMediaCache.keys.iterator()
                        while (iter.hasNext() && removed < toRemove) { iter.next(); iter.remove(); removed++ }
                    }
                }
            }
            val totalCount = dirNames.size + files.size
            total = totalCount
            val pageStart = offset
            val pageEnd = (offset + limit).coerceAtMost(totalCount)
            if (pageStart < dirNames.size) {
                val dirEnd = pageEnd.coerceAtMost(dirNames.size)
                for (i in pageStart until dirEnd) {
                    val sub = dirNames[i]
                    items.put(
                        JSONObject()
                            .put("name", sub)
                            .put("path", "$MS_PREFIX$base/$sub")
                            .put("kind", "dir")
                    )
                }
            }
            if (pageEnd > dirNames.size) {
                val fStart = (pageStart - dirNames.size).coerceAtLeast(0)
                val fEnd = (pageEnd - dirNames.size).coerceAtMost(files.size)
                for (i in fStart until fEnd) {
                    val f = files[i]
                    items.put(
                        JSONObject()
                            .put("name", f.name)
                            .put("path", f.path)
                            .put("kind", "file")
                            .put("size", f.size)
                            .put("modified", f.modified)
                            .put("token", MediaLibrary.tokenForUri(f.uri))
                    )
                }
            }
        }
        return jsonResponse(
            JSONObject().put("path", relative).put("items", items).put("total", total)
        )
    }

    private class FsMediaEntry(
        val name: String,
        val path: String,
        val size: Long,
        val modified: Long,
        val uri: String
    )

    private fun fsStats(path: String): Pair<Int, Long> {
        val now = System.currentTimeMillis()
        val cached = fsStatsCache[path]
        if (cached != null && now - cached.first < FS_STATS_TTL_MS) return cached.second
        var itemCount = 0
        var totalSize = 0L
        runCatching { File(path).listFiles() }.getOrNull()?.forEach { c ->
            itemCount++
            if (c.isFile) totalSize += c.length()
        }
        val result = itemCount to totalSize
        fsStatsCache[path] = now to result
        if (fsStatsCache.size > 300) {
            val toRemove = fsStatsCache.size / 2
            var removed = 0
            val iter = fsStatsCache.keys.iterator()
            while (iter.hasNext() && removed < toRemove) { iter.next(); iter.remove(); removed++ }
        }
        return result
    }

    private fun fsAction(session: IHTTPSession): Response {
        if (StoragePrefs.isServerReadOnly(context)) return readOnlyDenied()
        val params = readForm(session)
        val action = params["action"].orEmpty()
        val path = params["path"].orEmpty()
        val name = params["name"]?.trim().orEmpty()
        val dest = params["dest"]?.trim().orEmpty()
        val ok = when {
            path.isEmpty() -> false
            path.startsWith(MS_PREFIX) -> fsActionMedia(action, path.removePrefix(MS_PREFIX), name, dest)
            else -> fsActionFiles(action, path.removePrefix(FS_PREFIX), name, dest)
        }
        if (ok) invalidateFsListingCache()
        appendLog("FS ${action.uppercase()}: $path -> ${if (ok) "OK" else "FAILED"}")
        return jsonResponse(JSONObject().put("ok", ok))
    }

    private fun fsActionFiles(action: String, path: String, name: String, dest: String): Boolean {
        if (!isFsPathAllowed(path)) return false
        val file = File(path)
        return when (action) {
            "delete" -> runCatching {
                val gone = if (file.isDirectory) file.deleteRecursively() else file.delete()
                if (gone) {
                    invalidateFsMediaCache()
                    invalidateGalleryCache()
                    MediaLibrary.notifyMediaChanged(context, file.absolutePath)
                }
                gone
            }.getOrDefault(false)
            "rename" -> {
                if (!isNameValid(name)) return false
                runCatching {
                    val target = File(file.parentFile, name)
                    if (target.exists()) return@runCatching false
                    val ok = file.renameTo(target)
                    if (ok) {
                        invalidateFsMediaCache()
                        invalidateGalleryCache()
                        MediaLibrary.notifyMediaChanged(
                            context, file.absolutePath, target.absolutePath
                        )
                    }
                    ok
                }.getOrDefault(false)
            }
            "move" -> {
                if (dest.isBlank()) return false
                if (dest.startsWith(MS_PREFIX)) {
                    return runCatching {
                        moveFileToMediaStore(file, dest.removePrefix(MS_PREFIX))
                    }.getOrDefault(false)
                }
                val destDir = File(dest.removePrefix(FS_PREFIX))
                if (!destDir.isDirectory || !isFsPathAllowed(destDir.absolutePath)) return false
                if (file.parentFile?.absolutePath == destDir.absolutePath) return true
                val target = File(destDir, FileNames.safe(file.name))
                if (target.exists()) return false
                if (file.renameTo(target)) {
                    invalidateFsMediaCache()
                    invalidateGalleryCache()
                    MediaLibrary.notifyMediaChanged(context, file.absolutePath, target.absolutePath)
                    return true
                }
                if (!file.isFile) return false
                runCatching {
                    file.copyTo(target, overwrite = false)
                    file.delete()
                    invalidateFsMediaCache()
                    invalidateGalleryCache()
                    MediaLibrary.notifyMediaChanged(context, file.absolutePath, target.absolutePath)
                    true
                }.getOrDefault(false)
            }
            "mkdir" -> {
                if (!isNameValid(name)) return false
                val created = runCatching { File(file, name).mkdirs() }.getOrDefault(false)
                if (created) {
                    invalidateFsMediaCache()
                    invalidateGalleryCache()
                }
                created
            }
            else -> false
        }
    }

    private fun fsActionMedia(action: String, uriStr: String, name: String, dest: String): Boolean {
        val resolver = context.contentResolver
        return runCatching {
            val uri = uriStr.toUri()
            if (!isMediaUriWritable(uri)) return@runCatching false
            val ok = when (action) {
                "delete" -> resolver.delete(uri, null, null) > 0
                "rename" -> {
                    if (!isNameValid(name)) return false
                    val rel = mediaStoreRelativePath(uri)?.trim('/')
                    val finalName = if (rel != null) {
                        val collection = MediaLibrary.mediaCollectionFor(rel, MimeTypes.forFile(name))
                        FileSaver(context).uniqueMediaStoreName(name, rel, collection)
                    } else {
                        name
                    }
                    val values = ContentValues().apply {
                        put(MediaStore.Downloads.DISPLAY_NAME, finalName)
                    }
                    resolver.update(uri, values, null, null) > 0
                }
                "move" -> {
                    if (dest.isBlank()) return false
                if (dest.startsWith(FS_PREFIX)) {
                    return moveMediaToFile(uri, dest.removePrefix(FS_PREFIX))
                }
                val relative = dest.removePrefix(MS_PREFIX)
                if (!isMediaStorePathAllowed(relative)) return false
                val rel = dest.removePrefix(MS_PREFIX).trim('/') + "/"
                    val values = ContentValues().apply {
                        put(MediaStore.MediaColumns.RELATIVE_PATH, rel)
                    }
                    resolver.update(uri, values, null, null) > 0
                }
                else -> false
            }
            if (ok) {
                // MediaStore berubah: buang cache listing folder media & snapshot
                // galeri supaya nama/hapus/pindah langsung terlihat (bukan 5-15 dtk).
                fsMediaCache.clear()
                invalidateGalleryCache()
            }
            ok
        }.getOrDefault(false)
    }

    private fun moveFileToMediaStore(file: File, relative: String): Boolean {
        if (Build.VERSION.SDK_INT < 29) return false
        if (!isFsPathAllowed(file.absolutePath)) return false
        if (!isMediaStorePathAllowed(relative)) return false
        val rel = relative.trim('/')
        val resolver = context.contentResolver
        val mime = MimeTypes.forFile(file.name)
        val collection = MediaLibrary.mediaCollectionFor(rel, mime)
        val name = FileSaver(context).uniqueMediaStoreName(file.name, rel, collection)
        val values = ContentValues().apply {
            put(MediaStore.Downloads.DISPLAY_NAME, name)
            put(MediaStore.Downloads.MIME_TYPE, mime)
            put(MediaStore.Downloads.RELATIVE_PATH, "$rel/")
        }
        val uri = resolver.insert(collection, values) ?: return false
        val written = runCatching {
            val out = resolver.openOutputStream(uri) ?: return false
            out.use { dst -> file.inputStream().use { src -> src.copyTo(dst) } }
            true
        }.getOrDefault(false)
        if (!written) {
            resolver.delete(uri, null, null)
            return false
        }
        // Invalidasi cache setelah copy ke MediaStore berhasil, supaya
        // listing media langsung mendeteksi file baru walau delete asli gagal.
        fsMediaCache.clear()
        invalidateGalleryCache()
        val gone = runCatching { file.delete() }.getOrDefault(false)
        if (!gone) {
            // Copy sukses tapi file asli gagal dihapus: data duplikasi
            // tidak bisa dihindari (file sudah ada di MediaStore), tapi
            // setidaknya cache sudah di-refresh dan file asli masih ada.
            MediaLibrary.notifyMediaChanged(context, file.absolutePath)
        }
        return gone
    }

    private fun moveMediaToFile(uri: Uri, dest: String): Boolean {
        if (!isFsPathAllowed(dest)) return false
        val dir = File(dest)
        if (!dir.isDirectory && !dir.mkdirs()) return false
        val sourceName = mediaStoreName(uri) ?: return false
        val target = FileNames.unique(File(dir, sourceName).name) { File(dir, it).exists() }
            .let { File(dir, it) }
        val resolver = context.contentResolver
        val written = runCatching {
            val input = resolver.openInputStream(uri) ?: return false
            input.use { src -> target.outputStream().use { dst -> src.copyTo(dst) } }
            true
        }.getOrDefault(false)
        if (!written) return false
        val gone = resolver.delete(uri, null, null) > 0
        if (!gone) {
            // Rollback: hapus file yang sudah dicopy supaya tidak ada duplikasi data
            runCatching { target.delete() }
            return false
        }
        fsMediaCache.clear()
        invalidateGalleryCache()
        return true
    }

    private fun mediaStoreName(uri: Uri): String? = runCatching {
        context.contentResolver.query(
            uri, arrayOf(MediaStore.Downloads.DISPLAY_NAME), null, null, null
        )?.use { c ->
            if (c.moveToFirst()) c.getString(c.getColumnIndexOrThrow(MediaStore.Downloads.DISPLAY_NAME)) else null
        }
    }.getOrNull()

    private fun mediaStoreRelativePath(uri: Uri): String? = runCatching {
        context.contentResolver.query(
            uri, arrayOf(MediaStore.Downloads.RELATIVE_PATH), null, null, null
        )?.use { c ->
            if (c.moveToFirst()) c.getString(c.getColumnIndexOrThrow(MediaStore.Downloads.RELATIVE_PATH)) else null
        }
    }.getOrNull()

    // ---------- Keamanan FS: hanya izinkan path di dalam root yang sah ----------

    private fun allowedFsRoots(): List<File> {
        cachedFsRoots?.let { return it }
        val roots = mutableListOf<File>()
        roots.add(File(context.filesDir, "downloads"))
        StoragePrefs.getTextFolder(context)?.let { roots.add(File(it)) }
        StoragePrefs.getExtraFolders(context).forEach { roots.add(File(it)) }
        if (StoragePrefs.isFsFullAccessEnabled(context)) {
            roots.add(File("/storage/emulated/0"))
        }
        if (Build.VERSION.SDK_INT < 29) {
            Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
                ?.let { roots.add(it) }
        }
        cachedFsRoots = roots
        return roots
    }

    private fun isFsPathAllowed(path: String): Boolean =
        ServerSecurity.isPathAllowed(path, allowedFsRoots())

    private fun isRemoteDestinationAllowed(path: String): Boolean {
        if (!ServerSecurity.isRemoteDestinationAllowed(path, allowedFsRoots())) return false
        if (!path.startsWith(MS_PREFIX)) return true
        return ServerSecurity.isMediaStorePathAllowed(
            path.removePrefix(MS_PREFIX), allowedFsRoots(),
            StoragePrefs.isFsFullAccessEnabled(context)
        )
    }

    private fun isMediaStorePathAllowed(relativePath: String): Boolean =
        ServerSecurity.isMediaStorePathAllowed(
            relativePath,
            allowedFsRoots(),
            StoragePrefs.isFsFullAccessEnabled(context)
        )

    /** URI konten hanya sah bila berasal dari MediaStore Download (area aplikasi)
     *  atau dokumen SAF yang memang diberi izin oleh pengguna. */
    private fun isMediaUriAllowed(uri: Uri): Boolean {
        return runCatching {
            when (uri.authority) {
                MediaStore.AUTHORITY -> {
                    if (Build.VERSION.SDK_INT >= 29) {
                        val rel = mediaStoreRelativePath(uri) ?: return@runCatching false
                        val top = rel.trim('/').substringBefore('/')
                        top == "Download" || top == "Pictures" || top == "Movies" || top == "DCIM"
                    } else {
                        // Di bawah API 29 MediaStore.Downloads belum ada; file sah
                        // aplikasi disimpan lewat path/SAF. Token u: MediaStore
                        // buatan = akses baca media umum -> wajib cocok dengan root.
                        val data = mediaStoreData(uri) ?: return@runCatching false
                        isFsPathAllowed(data)
                    }
                }
                "com.android.externalstorage.documents",
                "com.android.providers.downloads.documents" -> ServerSecurity.isSafUriAllowed(
                    uri.authority,
                    uri.encodedPath,
                    context.contentResolver.persistedUriPermissions
                        .filter { it.isReadPermission }
                        .map { it.uri.authority.orEmpty() to it.uri.encodedPath.orEmpty() }
                )
                else -> false
            }
        }.getOrDefault(false)
    }

    /** Aksi destruktif MediaStore memakai batas root yang sama dengan FS;
     *  galeri boleh membaca koleksi umum, tapi tidak otomatis boleh menghapusnya. */
    private fun isMediaUriWritable(uri: Uri): Boolean {
        return runCatching {
            when (uri.authority) {
                MediaStore.AUTHORITY -> {
                    val fullAccess = StoragePrefs.isFsFullAccessEnabled(context)
                    if (Build.VERSION.SDK_INT >= 29) {
                        val relative = mediaStoreRelativePath(uri) ?: return@runCatching false
                        ServerSecurity.isMediaStorePathAllowed(
                            relative.trim('/'), allowedFsRoots(), fullAccess
                        )
                    } else {
                        val data = mediaStoreData(uri) ?: return@runCatching false
                        isFsPathAllowed(data)
                    }
                }
                "com.android.externalstorage.documents",
                "com.android.providers.downloads.documents" -> ServerSecurity.isSafUriAllowed(
                    uri.authority,
                    uri.encodedPath,
                    context.contentResolver.persistedUriPermissions
                        .filter { it.isWritePermission }
                        .map { it.uri.authority.orEmpty() to it.uri.encodedPath.orEmpty() }
                )
                else -> false
            }
        }.getOrDefault(false)
    }

    @Suppress("DEPRECATION")
    private fun mediaStoreData(uri: Uri): String? = runCatching {
        context.contentResolver.query(
            uri, arrayOf(MediaStore.MediaColumns.DATA), null, null, null
        )?.use { c ->
            if (c.moveToFirst()) c.getString(c.getColumnIndexOrThrow(MediaStore.MediaColumns.DATA)) else null
        }
    }.getOrNull()

    private fun statusObject(): JSONObject {
        cachedStatusJson?.let { return it }
        val obj = JSONObject()
        obj.put("port", listeningPort)
        obj.put("readOnly", StoragePrefs.isServerReadOnly(context))
        obj.put("appVersion", appVersion)
        obj.put("appBuild", appBuild)
        cachedStatusJson = obj
        return obj
    }

    private fun readOnlyDenied(): Response =
        jsonResponse(JSONObject().put("ok", false).put("error", "Server is read-only"))

    // ---------- SSE: update real-time ----------

    private fun sseResponse(): Response {
        val stream = SseStream()
        sseClients.add(stream)
        ensureSsePump()
        stream.push("data: ${downloadsPayload()}\n\n")
        val res = newChunkedResponse(
            Response.Status.OK,
            "text/event-stream; charset=utf-8",
            stream
        )
        res.addHeader("Cache-Control", "no-cache, no-transform")
        res.addHeader("Connection", "keep-alive")
        res.addHeader("X-Accel-Buffering", "no")
        return res
    }

    private val ssePumpLock = Any()

    private fun ensureSsePump() {
        // Beberapa koneksi SSE datang bersamaan (tab/device ganda) bisa sama-
        // sama melewati cek `isActive` sebelum sseJob terisi -> dua pump kembar
        // yang push frame ganda ke semua klien. Kunci membuat hanya SATU pump.
        synchronized(ssePumpLock) {
            if (sseJob?.isActive == true) return
            sseJob = serverScope.launch {
                val me = coroutineContext[Job]
                val buildPayload = { withStatus: Boolean ->
                    val payload = JSONObject().put("items", itemsJson())
                    if (withStatus) payload.put("status", statusObject())
                    payload.toString()
                }
                val pushFrame = { payloadText: String ->
                    runCatching {
                        val now = System.currentTimeMillis()
                        if (payloadText != sseLastPayload || now - sseLastPushAt > SSE_HEARTBEAT_MS) {
                            sseLastPayload = payloadText
                            sseLastPushAt = now
                            val frame = "data: $payloadText\n\n"
                            val closed = sseClients.filter { it.isClosed }
                            if (closed.isNotEmpty()) sseClients.removeAll(closed)
                            sseClients.forEach { it.push(frame) }
                        }
                    }
                }
                try {
                    // Tick 1 detik hanya membangun JSON setelah throttle terpenuhi.
                    // Ini menutup celah lama: perubahan yang tertahan throttle tidak
                    // perlu menunggu ticker status 10 dtk.
                    var tick = 0
                    while (true) {
                        delay(1_000)
                        tick++
                        val pruneTick = tick % 10 == 0
                        if (pruneTick) {
                            runCatching { pruneCompletedUploads() }
                            runCatching { pruneFsStats() }
                        }
                        if (sseClients.isEmpty()) {
                            delay(1_000)
                            if (sseClients.isEmpty()) {
                                if (sseJob === me) sseJob = null
                                return@launch
                            }
                        }
                        if (System.currentTimeMillis() - sseLastPushAt < SSE_MIN_INTERVAL_MS) continue
                        pushFrame(buildPayload(pruneTick))
                    }
                } catch (e: CancellationException) {
                    throw e
                }
            }
        }
    }

    // ---------- Berbagi file via tautan sementara ----------

    private fun createShare(session: IHTTPSession): Response {
        val params = readForm(session)
        val id = params["id"].orEmpty()
        val item = App.engine.items.value.find {
            it.id == id && it.state == DownloadState.COMPLETED
        } ?: return jsonResponse(JSONObject().put("ok", false).put("error", "file not found"))
        synchronized(shareLock) { pruneShares() }
        val token = UUID.randomUUID().toString().replace("-", "")
        val expiresAt = System.currentTimeMillis() + SHARE_TTL_MS
        synchronized(shareLock) {
            while (shareTokens.size >= MAX_SHARE_TOKENS) {
                val oldest = shareTokens.entries.minByOrNull { it.value.expiresAt } ?: break
                shareTokens.remove(oldest.key)
            }
            shareTokens[token] = ShareEntry(item.id, expiresAt)
        }
        appendLog("SHARE CREATED: ${item.fileName} (valid for $SHARE_TTL_HOURS hours)")
        return jsonResponse(
            JSONObject().put("ok", true)
                .put("token", token)
                .put("expiresInHours", SHARE_TTL_HOURS)
        )
    }

    private fun pruneShares() {
        val now = System.currentTimeMillis()
        val iter = shareTokens.entries.iterator()
        while (iter.hasNext()) {
            if (ServerSecurity.isShareExpired(iter.next().value.expiresAt, now)) iter.remove()
        }
    }


    private fun serveShare(session: IHTTPSession): Response {
        val token = session.uri.removePrefix("/share/").trim()
        if (token.isEmpty()) return notFound()
        pruneShares()
        val entry = shareTokens[token] ?: return notFound()
        val item = App.engine.items.value.find {
            it.id == entry.itemId && it.state == DownloadState.COMPLETED
        } ?: return notFound()
        val input: InputStream
        val total: Long
        if (!item.filePath.isNullOrEmpty()) {
            val file = File(item.filePath)
            if (!file.exists() || !file.isFile || !isFsPathAllowed(file.absolutePath)) {
                return notFound()
            }
            input = FileInputStream(file)
            total = file.length()
        } else if (!item.contentUri.isNullOrEmpty()) {
            val uri = item.contentUri.toUri()
            if (!isMediaUriAllowed(uri)) return notFound()
            val resolver = context.contentResolver
            val stream = resolver.openInputStream(uri) ?: return notFound()
            total = resolver.openAssetFileDescriptor(uri, "r")?.use { it.length } ?: -1L
            input = stream
        } else {
            return notFound()
        }
        return streamMedia(
            name = item.fileName,
            mime = MimeTypes.forFile(item.fileName),
            input = input,
            total = total,
            rangeHeader = session.headers["range"] ?: session.headers["Range"],
            download = true
        )
    }

    // ---------- QR code ----------

    private fun qrPngResponse(session: IHTTPSession): Response {
        val text = session.param("text").orEmpty()
        if (text.isEmpty() || text.length > MAX_QR_TEXT_LENGTH) return notFound()
        val now = System.currentTimeMillis()
        val cached = qrCache[text]
        val bytes = if (cached != null && now - cached.first < QR_CACHE_TTL_MS) {
            cached.second
        } else {
            QrCode.generate(text)?.also { generated ->
                // Batasi isi cache: evict yang paling lama bila penuh.
                if (qrCache.size >= QR_CACHE_MAX) {
                    val oldest = qrCache.entries.minByOrNull { it.value.first }
                    oldest?.let { qrCache.remove(it.key) }
                }
                qrCache[text] = now to generated
            } ?: return notFound()
        }
        return newFixedLengthResponse(
            Response.Status.OK,
            "image/png",
            ByteArrayInputStream(bytes),
            bytes.size.toLong()
        ).also { it.addHeader("Cache-Control", "no-store") }
    }

    /** Jalur thumbnail bersama untuk remote dan galeri native agar decode,
     * lock per-file, dan cache disk tidak diduplikasi dua implementasi. */
    fun galleryThumbFile(raw: String): File? = safeRun("galleryThumb") {
        getOrCreateThumb(context, raw, ::isFsPathAllowed, ::isMediaUriAllowed)
    }

    fun appendLog(message: String) = serverLog.append(message.replace(LOG_SANITIZE_RE, " "))

    fun logVersion(): Long = serverLog.version()

    fun snapshotLog(): String = serverLog.snapshot()

    fun clearLog() = serverLog.clear()

    private fun logError(e: Throwable) {
        appendLog("ERROR ${e.message}")
        CrashLog.append(context, "serve", e)
    }

    /** Wrapper runCatching + logError: supaya error tidak hilang diam-diam. */
    private inline fun <T> safeRun(tag: String = "", crossinline block: () -> T): T? {
        return runCatching { block() }.onFailure { e ->
            logError(e)
            if (tag.isNotEmpty()) appendLog("  \u2193 $tag")
        }.getOrNull()
    }

    private fun jsonResponse(obj: JSONObject): Response {
        return newFixedLengthResponse(
            Response.Status.OK,
            "application/json; charset=utf-8",
            obj.toString()
        )
    }

    private fun secureHeaders(uri: String, response: Response) {
        response.addHeader("X-Content-Type-Options", "nosniff")
        response.addHeader("Referrer-Policy", "no-referrer")
        response.addHeader("Permissions-Policy", "camera=(), microphone=(), geolocation=()")
        if (uri == "/") {
            response.addHeader(
                "Content-Security-Policy",
                "default-src 'self'; img-src 'self' data:; style-src 'unsafe-inline'; " +
                    "script-src 'unsafe-inline'; media-src 'self'; connect-src 'self'; " +
                    "object-src 'none'; base-uri 'none'; form-action 'self'; frame-ancestors 'none'"
            )
        }
    }

    private class UploadLock {
        val lock = Any()
        val lastUse = AtomicLong(System.currentTimeMillis())
    }

    private class LoginAttempt {
        var failures = 0
        var lockedUntil = 0L
        var updatedAt = 0L
    }

    companion object {
        private val REQUEST_SECRET_RE = Regex("([?&]?(?:token|pin|id|verify)=)[^&]+")
        /** Regex sanitasi log — dihoist agar tidak dikompilasi ulang per panggilan log. */
        private val LOG_SANITIZE_RE = Regex("[\r\n\t]+")
        private const val SERVER_SOCKET_TIMEOUT_MS = 60_000
        private const val FS_PREFIX = "f:"
        private const val MS_PREFIX = "m:"
        private const val MAX_UPLOAD_BYTES = 2L * 1024 * 1024 * 1024
        private const val MAX_UPLOAD_BUFFER_BYTES = 2L * 1024 * 1024 * 1024
        private const val MAX_UPLOAD_MB = 2048
        private const val MAX_UPLOAD_CHUNKS = 1024
        private const val MAX_UPLOAD_LOCKS = 512
        private const val MAX_UPLOAD_FINALIZING = 8
        private const val SHARE_TTL_HOURS = 24
        private const val PARTIAL_STREAM_TTL_MS = 60 * 60 * 1000L
        private const val SHARE_TTL_MS = SHARE_TTL_HOURS * 60L * 60 * 1000
        private const val GALLERY_SCAN_TTL_MS = 30_000L
        private const val GALLERY_PAGE_SIZE = 100
        private const val FS_LISTING_TTL_MS = 3_000L
        private const val FS_LISTING_CACHE_MAX = 5_000
        // Listing file manager di-paginate: maks 1000 entri per request, klien
        // memuat halaman berikutnya lewat tombol "Load more". Folder raksasa
        // tidak lagi membangun JSON semua entri + statistik semua subfolder
        // sekaligus di memori.
        private const val FS_PAGE_SIZE = 300
        private const val FS_PAGE_MAX = 5000
        private const val FS_MEDIA_CACHE_TTL_MS = 5_000L
        private const val FS_MEDIA_CACHE_MAX_FILES = 2_000
        private const val FS_MEDIA_CACHE_MAX_ENTRIES = 50
        private const val DEFAULT_CHUNK_BYTES = 2L * 1024 * 1024
        private const val ZIP_CACHE_TTL_MS = 60_000L
        private const val ZIP_CACHE_MAX = 8
        private const val ZIP_CACHE_MAX_BYTES = 256L * 1024 * 1024
        private const val MAX_LOGIN_ATTEMPTS = 5
        private const val LOGIN_LOCK_MS = 30_000L
        private const val MAX_LOGIN_ATTEMPT_ENTRIES = 512
        private const val MAX_SHARE_TOKENS = 256
        private const val MAX_MEDIA_ZIP_TOKENS = 256
        private const val FS_STATS_TTL_MS = 10_000L
        private const val SSE_MIN_INTERVAL_MS = 1_000L
        private const val QR_CACHE_MAX = 8
        private const val QR_CACHE_TTL_MS = 300_000L
        private const val MAX_QR_TEXT_LENGTH = 2_000
        // Heartbeat: tetap kirim walau tidak ada perubahan, supaya klien tahu
        // koneksi hidup (dan fallback polling klien tidak ikut jalan).
        private const val SSE_HEARTBEAT_MS = 3_000L
        private const val MEDIA_META_TTL_MS = 60_000L
        private const val MEDIA_META_CACHE_MAX = 256

        fun ipv4Addresses(): List<String> = runCatching {
            NetworkInterface.getNetworkInterfaces().toList().flatMap { ni ->
                ni.inetAddresses.toList()
                    .filter { it is Inet4Address && !it.isLoopbackAddress }
                    .map { it.hostAddress.orEmpty() }
            }
        }.getOrDefault(emptyList())
    }
}
