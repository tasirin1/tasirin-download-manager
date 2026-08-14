package com.tasirin.httpdownloadmanager.remote

import android.content.ContentUris
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.annotation.SuppressLint
import androidx.core.net.toUri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import android.util.Log
import com.tasirin.httpdownloadmanager.App
import com.tasirin.httpdownloadmanager.util.CrashLog
import com.tasirin.httpdownloadmanager.data.DownloadItem
import com.tasirin.httpdownloadmanager.data.DownloadState
import com.tasirin.httpdownloadmanager.util.FileSaver
import com.tasirin.httpdownloadmanager.util.MediaLibrary
import com.tasirin.httpdownloadmanager.util.scaleDown
import com.tasirin.httpdownloadmanager.util.FileNames
import com.tasirin.httpdownloadmanager.util.Formats
import com.tasirin.httpdownloadmanager.util.MimeTypes
import com.tasirin.httpdownloadmanager.util.StoragePrefs
import com.tasirin.httpdownloadmanager.util.sha256Hex
import androidx.documentfile.provider.DocumentFile
import fi.iki.elonen.NanoHTTPD
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collect
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
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.Executors
import java.util.concurrent.ThreadPoolExecutor
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
    private var sseJob: Job? = null
    @Volatile private var sseLastPayload = ""
    @Volatile private var sseLastPushAt = 0L
    private val shareTokens = ConcurrentHashMap<String, ShareEntry>()
    @Volatile private var galleryCache: Pair<Long, MediaLibrary.MediaScanResult>? = null
    @Volatile private var loginFailures = 0
    @Volatile private var loginLockUntil = 0L
    private val fsStatsCache = ConcurrentHashMap<String, Pair<Long, Pair<Int, Long>>>()
    private val completedUploads = ConcurrentHashMap<String, Pair<String, Long>>()
    private val finalizingUploads = ConcurrentHashMap<String, String>()
    private val failedUploads = ConcurrentHashMap<String, Pair<String, Long>>()
    // Cache durasi video & dimensi gambar galeri: file/dimensi tidak berubah,
    // jadi cukup di-hold di memori (dibatasi jumlahnya) — tanpa cache, tiap
    // request halaman galeri membaca ulang file & header gambar dari disk.
    @Volatile private var videoDurationsCache: JSONObject? = null
    private val imageDimCache = ConcurrentHashMap<String, Pair<Int, Int>>()
    // Statistik folder dihitung paralel: listing folder dengan banyak subfolder
    // tidak lagi menunggu N listFiles() berurutan (lambat di storage TV box).
    // Pool bisa mati saat stopServer() lalu startServer() pada instance yang
    // sama (toggle server di Settings) — liveStatPool() membuat pool baru
    // otomatis supaya listing subfolder tidak gagal setelah restart server.
    @Volatile private var statPool: ThreadPoolExecutor = newStatPool()

    private fun newStatPool(): ThreadPoolExecutor =
        Executors.newFixedThreadPool(
            Runtime.getRuntime().availableProcessors().coerceIn(2, 4)
        ) as ThreadPoolExecutor

    @Synchronized
    private fun liveStatPool(): ThreadPoolExecutor {
        if (statPool.isShutdown) statPool = newStatPool()
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
            context.packageManager.getPackageInfo(context.packageName, 0).versionCode
        }.getOrDefault(0)
    }
    private val serverLog = ServerLog()
    // Cache QR PNG: /api/qr jarang berubah isinya (URL server + PIN),
    // render bitmap 520x520 tiap panggilan itu boros CPU/RAM.
    private val qrCache = ConcurrentHashMap<String, ByteArray>()

    override fun serve(session: IHTTPSession): Response {
        val startedAt = System.currentTimeMillis()
        val response = try {
            when {
                session.method == Method.POST && session.uri == "/api/login" -> login(session)
                session.method == Method.GET && session.uri == "/api/logout" -> logout()
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
                    session.method == Method.POST && session.uri == "/api/upload" -> handleUpload(session)
                    session.method == Method.GET && session.uri == "/api/upload_verify" -> uploadVerify(session)
                    session.method == Method.POST && session.uri == "/api/action" -> runAction(session)
                    session.method == Method.POST && session.uri == "/api/delete_media" -> deleteMedia(session)
                    session.method == Method.POST && session.uri == "/api/fs_action" -> fsAction(session)
                    session.method == Method.GET && session.uri == "/api/fs_zip" -> fsZip(session)
                    session.method == Method.GET && session.uri == "/api/media_zip" -> mediaZip(session)
                    session.method == Method.GET && session.uri.startsWith("/file/") -> serveFile(session)
                    else -> newFixedLengthResponse(
                        Response.Status.NOT_FOUND,
                        "text/plain; charset=utf-8",
                        "Not found"
                    )
                }
                session.method == Method.GET && session.uri == "/" -> loginPage("")
                else -> unauthorized()
            }
        } catch (e: Exception) {
            logError(e)
            newFixedLengthResponse(
                Response.Status.INTERNAL_ERROR,
                "text/plain; charset=utf-8",
                "Error: ${e.message}"
            )
        }
        appendRequestLog(session, response, System.currentTimeMillis() - startedAt)
        return response
    }

    private fun appendRequestLog(session: IHTTPSession, response: Response, elapsedMs: Long) {
        // Endpoint polling murni (snapshot/events/pin_enabled) dipanggil terus
        // oleh halaman remote; tanpa pengecualian ini buffer 300 baris penuh
        // hanya oleh polling dan kejadian penting (download, upload, aksi)
        // cepat hilang dari log. Request gagal tetap dicatat.
        val isPolling = response.status.requestStatus == 200 &&
            session.method == Method.GET && (
                session.uri == "/api/snapshot" ||
                    session.uri == "/api/events" ||
                    session.uri == "/api/pin_enabled"
                )
        if (isPolling) return
        val query = session.queryParameterString?.take(160)?.let { "?$it" }.orEmpty()
        val remote = session.remoteIpAddress.orEmpty()
        appendLog(
            "${session.method.name} ${session.uri}$query -> HTTP ${response.status.requestStatus} " +
                "(${elapsedMs}ms) $remote"
        )
    }

    fun startServer() {
        try {
            // NanoHTTPD default SOCKET_READ_TIMEOUT = 5 dtk; terlalu pendek
            // untuk potongan upload 2MB di jaringan lambat -> koneksi diputus
            // saat upload tersendat. Naikkan jadi 60 dtk.
            super.start(SERVER_SOCKET_TIMEOUT_MS)
            lastError = null
            cleanupCache()
            appendLog(
                "SERVER STARTED on port $listeningPort (Android ${Build.VERSION.RELEASE} " +
                    "API ${Build.VERSION.SDK_INT}, ${Build.MANUFACTURER} ${Build.MODEL}, " +
                    "free storage ${Formats.bytes(App.engine.freeSpaceBytes())})"
            )
        } catch (e: IOException) {
            lastError = e.message
            appendLog("SERVER FAILED TO START: ${e.message}")
            throw e
        }
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

    fun stopServer() {
        appendLog("SERVER STOPPED (port $listeningPort)")
        sseJob?.cancel()
        sseJob = null
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
    }

    private fun pinEnabled(): Boolean =
        !StoragePrefs.getServerPin(context).isNullOrEmpty()

    /** PIN tersimpan sudah dinormalisasi jadi hash SHA-256 oleh StoragePrefs;
     *  bandingkan dengan timing konstan (anti bocor lewat timing attack). */
    private fun storedPinHash(): String? = StoragePrefs.storedPinHash(context)

    private fun pinOk(session: IHTTPSession): Boolean {
        val expected = storedPinHash() ?: return true
        val cookie = session.headers["cookie"] ?: return false
        val pin = cookie.split(";").map { it.trim() }
            .firstOrNull { it.startsWith("dm_pin=") }
            ?.substringAfter("dm_pin=") ?: return false
        return constantEquals(pin, expected)
    }

    /** Bandingkan dua string tanpa short-circuit (constant-time). */
    private fun constantEquals(a: String, b: String): Boolean =
        MessageDigest.isEqual(
            a.toByteArray(Charsets.UTF_8),
            b.toByteArray(Charsets.UTF_8)
        )

    private fun login(session: IHTTPSession): Response {
        val now = System.currentTimeMillis()
        if (ServerSecurity.isPinLocked(now, loginLockUntil)) {
            val waitSec = ((loginLockUntil - now) / 1000) + 1
            return loginPage("Too many attempts. Try again in $waitSec seconds.")
        }
        val params = readForm(session)
        val pin = params["pin"].orEmpty()
        val stored = storedPinHash()
        return if (stored != null && StoragePrefs.pinMatches(context, pin)) {
            loginFailures = 0
            appendLog("LOGIN OK (${session.remoteIpAddress})")
            val r = newFixedLengthResponse(
                Response.Status.REDIRECT,
                "text/html",
                "<html><body>OK</body></html>"
            )
            r.addHeader("Set-Cookie", "dm_pin=$stored; Max-Age=2592000; Path=/")
            r.addHeader("Location", "/")
            r
        } else {
            loginFailures++
            val lockUntil = ServerSecurity.pinLockUntilAfter(
                loginFailures, MAX_LOGIN_ATTEMPTS, LOGIN_LOCK_MS, now
            )
            if (lockUntil > 0) {
                loginLockUntil = lockUntil
                loginFailures = 0
                appendLog("LOGIN LOCKED $LOGIN_LOCK_MS ms (too many attempts, from ${session.remoteIpAddress})")
            } else {
                appendLog("LOGIN FAILED: wrong PIN (attempt $loginFailures, from ${session.remoteIpAddress})")
            }
            loginPage("Wrong PIN, try again.")
        }
    }

    private fun logout(): Response {
        appendLog("LOGOUT")
        val r = newFixedLengthResponse(
            Response.Status.REDIRECT,
            "text/html",
            "<html><body>OK</body></html>"
        )
        r.addHeader("Set-Cookie", "dm_pin=; Max-Age=0; Path=/")
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
        val payload = JSONObject()
            .put("items", itemsJson())
            .put("status", statusObject())
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
                item.bytesDownloaded.hashCode() * 17 + item.speedBps.hashCode() * 19 +
                item.etaSeconds.hashCode() * 23 + (item.error?.hashCode() ?: 0) * 29
        }
        return h
    }

    private fun itemsJson(): JSONArray {
        val arr = JSONArray()
        App.engine.items.value.forEach { item ->
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
        if (folderPath.startsWith(FS_PREFIX) &&
            !isFsPathAllowed(folderPath.removePrefix(FS_PREFIX))
        ) {
            return jsonResponse(
                JSONObject().put("ok", false).put("error", "Destination folder not allowed")
            )
        }
        App.engine.addDownload(
            url, params["name"],
            speedLimitKbps = speed,
            priority = priority,
            checksum = checksum,
            destination = storage,
            folderPath = folderPath
        )
        return jsonResponse(JSONObject().put("ok", true))
    }

    private fun handleUpload(session: IHTTPSession): Response {
        if (StoragePrefs.isServerReadOnly(context)) return readOnlyDenied()
        val name = session.parms["name"]?.trim()
            ?.replace("/", "_")?.replace("\\", "_")?.replace("\"", "_")
            ?.takeIf { it.isNotEmpty() }
            ?: "upload_${System.currentTimeMillis()}"
        val storage = session.parms["storage"]?.trim().orEmpty()
        val folderPath = session.parms["path"]?.trim().orEmpty()
        if (folderPath.startsWith(FS_PREFIX) &&
            !isFsPathAllowed(folderPath.removePrefix(FS_PREFIX))
        ) {
            return jsonResponse(
                JSONObject().put("ok", false).put("error", "Destination folder not allowed")
            )
        }
        val chunkIdx = session.parms["chunk"]?.toIntOrNull() ?: -1
        val chunks = (session.parms["chunks"]?.toIntOrNull() ?: 1).coerceAtLeast(1)
        val length = (session.headers["content-length"]?.toLongOrNull() ?: 0L)

        if (chunkIdx >= 0) {
            return handleUploadChunk(session, name, storage, folderPath, chunkIdx, chunks, length)
        }

        if (length <= 0 || length > MAX_UPLOAD_BYTES) {
            return jsonResponse(
                JSONObject().put("ok", false)
                    .put("error", "Invalid size (max ${MAX_UPLOAD_MB} MB)")
            )
        }
        if (App.engine.freeSpaceBytes() < length) {
            return jsonResponse(
                JSONObject().put("ok", false)
                    .put("error", "Not enough storage for upload")
            )
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
        val id = session.parms["id"]?.trim().orEmpty()
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

    private fun pruneCompletedUploads() {
        val cutoff = System.currentTimeMillis() - 30 * 60 * 1000L
        completedUploads.entries.filter { it.value.second < cutoff }
            .forEach { completedUploads.remove(it.key) }
        failedUploads.entries.filter { it.value.second < cutoff }
            .forEach { failedUploads.remove(it.key) }
        if (completedUploads.size > 400) completedUploads.clear()
        if (failedUploads.size > 400) failedUploads.clear()
    }

    private fun uploadUniqueName(name: String, folderPath: String): String {
        val clean = folderPath.trim().removePrefix("f:")
        if (clean.isBlank() || clean.startsWith("m:")) return name
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
        val id = session.parms["id"]?.trim()?.take(64)
            ?: sha256Hex("$name|$folderPath").take(16)
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
            )
        }
        if (App.engine.freeSpaceBytes() < length) {
            appendLog("UPLOAD #$id chunk ${chunkIdx + 1}/$chunks REJECTED: not enough storage")
            return jsonResponse(
                JSONObject().put("ok", false)
                    .put("error", "Not enough storage for upload")
            )
        }
        val offset = session.parms["offset"]?.toLongOrNull()
            ?: chunkIdx.toLong() * DEFAULT_CHUNK_BYTES
        if (!ServerSecurity.isChunkOffsetAllowed(offset, MAX_UPLOAD_BYTES)) {
            appendLog("UPLOAD #$id chunk ${chunkIdx + 1}/$chunks REJECTED: invalid offset")
            return jsonResponse(JSONObject().put("ok", false).put("error", "invalid offset"))
        }
        appendLog("UPLOAD #$id chunk ${chunkIdx + 1}/$chunks received: $name (${length}B offset=$offset)")
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
                    )
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
                finalizingUploads[id] = finalName
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
                    JSONObject().put("ok", true).put("pending", true).put("name", finalName)
                )
            }
            appendLog("UPLOAD #$id chunk ${chunkIdx + 1}/$chunks OK ($resultName)")
            jsonResponse(JSONObject().put("ok", true).put("name", resultName))
        }.getOrElse {
            // Simpan jejak biar bisa dicek lewat Ekspor Log Error.
            appendLog("UPLOAD #$id chunk ${chunkIdx + 1}/$chunks FAILED: ${it.message}")
            (it as? Exception)?.let { e -> logError(e) }
            jsonResponse(JSONObject().put("ok", false).put("error", it.message ?: "upload failed"))
        }
    }

    private fun fsZip(session: IHTTPSession): Response {
        val raw = session.parms["path"].orEmpty()
        if (raw.isEmpty()) return notFound()
        if (raw.startsWith(FS_PREFIX) && !isFsPathAllowed(raw.removePrefix(FS_PREFIX))) {
            return notFound()
        }
        if (raw.startsWith(MS_PREFIX) && raw.removePrefix(MS_PREFIX).contains("..")) {
            return notFound()
        }
        val folderName = when {
            raw.startsWith(MS_PREFIX) -> raw.removePrefix(MS_PREFIX).trim('/').substringAfterLast('/')
            else -> File(raw.removePrefix(FS_PREFIX)).name
        }.ifEmpty { "folder" }
        val tmp = try {
            File.createTempFile("fszip", ".zip", context.cacheDir).also { tmpFile ->
                try {
                    ZipOutputStream(BufferedOutputStream(FileOutputStream(tmpFile))).use { zos ->
                        if (raw.startsWith(MS_PREFIX)) {
                            ZipCreator.zipMedia(zos, raw.removePrefix(MS_PREFIX), context)
                        } else {
                            ZipCreator.zipFile(zos, File(raw.removePrefix(FS_PREFIX)), "")
                        }
                    }
                } catch (e: Exception) {
                    runCatching { tmpFile.delete() }
                    throw e
                }
            }
        } catch (e: Exception) {
            logError(e)
            return notFound()
        }
        if (tmp.length() == 0L) {
            tmp.delete()
            return notFound()
        }
        appendLog("ZIP CREATED: $folderName.zip (${Formats.bytes(tmp.length())})")
        return streamMedia(
            name = "$folderName.zip",
            mime = "application/zip",
            input = DeleteOnCloseStream(FileInputStream(tmp), tmp),
            total = tmp.length(),
            rangeHeader = session.headers["range"] ?: session.headers["Range"],
            download = true
        )
    }

    private fun mediaZip(session: IHTTPSession): Response {
        val tokens = session.parms["tokens"].orEmpty().split(",").filter { it.isNotBlank() }.toMutableList()
        val paths = session.parms["paths"].orEmpty().split(",").filter { it.isNotBlank() }
        if (tokens.isEmpty() && paths.isEmpty()) return notFound()
        paths.forEach { p ->
            if (p.startsWith(FS_PREFIX)) {
                val f = File(p.removePrefix(FS_PREFIX))
                if (f.exists() && isFsPathAllowed(f.absolutePath)) {
                    tokens.add(MediaLibrary.tokenForPath(f.absolutePath))
                }
            }
        }
        if (tokens.isEmpty()) return notFound()
        val tmp = try {
            File.createTempFile("mediazip", ".zip", context.cacheDir).also { tmpFile ->
                try {
                    ZipOutputStream(BufferedOutputStream(FileOutputStream(tmpFile))).use { zos ->
                        ZipCreator.zipTokens(zos, tokens, context)
                    }
                } catch (e: Exception) {
                    runCatching { tmpFile.delete() }
                    throw e
                }
            }
        } catch (e: Exception) {
            logError(e)
            return notFound()
        }
        if (tmp.length() == 0L) {
            tmp.delete()
            return notFound()
        }
        appendLog("ZIP MEDIA: ${tokens.size} file (${Formats.bytes(tmp.length())})")
        return streamMedia(
            name = "gallery-${tokens.size}-files.zip",
            mime = "application/zip",
            input = DeleteOnCloseStream(FileInputStream(tmp), tmp),
            total = tmp.length(),
            rangeHeader = session.headers["range"] ?: session.headers["Range"],
            download = true
        )
    }

    private fun deleteMedia(session: IHTTPSession): Response {
        if (StoragePrefs.isServerReadOnly(context)) return readOnlyDenied()
        val params = readForm(session)
        val token = params["token"].orEmpty()
        if (token.isEmpty()) {
            return jsonResponse(JSONObject().put("ok", false).put("error", "empty token"))
        }
        val raw = MediaLibrary.decodeToken(token)
        if (raw.isNullOrEmpty()) {
            return jsonResponse(JSONObject().put("ok", false).put("error", "invalid token"))
        }
        val allowed = when {
            raw.startsWith(FS_PREFIX) -> isFsPathAllowed(raw.removePrefix(FS_PREFIX))
            raw.startsWith("u:") -> runCatching {
                isMediaUriAllowed(raw.removePrefix("u:").toUri())
            }.getOrDefault(false)
            else -> false
        }
        if (!allowed) {
            return jsonResponse(JSONObject().put("ok", false).put("error", "not allowed"))
        }
        val deleted = App.engine.deleteMedia(raw)
        return jsonResponse(JSONObject().put("ok", deleted))
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
        val download = session.parms["dl"] == "1"
        val mime = MimeTypes.forFile(item.fileName)
        val input: InputStream
        val total: Long
        if (!item.filePath.isNullOrEmpty()) {
            val file = File(item.filePath)
            if (!file.exists() || !file.isFile) return notFound()
            input = FileInputStream(file)
            total = file.length()
        } else if (!item.contentUri.isNullOrEmpty()) {
            val uri = item.contentUri.toUri()
            val resolver = context.contentResolver
            val stream = resolver.openInputStream(uri) ?: return notFound()
            val len = resolver.openAssetFileDescriptor(uri, "r")?.use { it.length } ?: -1L
            input = stream
            total = len
        } else {
            return notFound()
        }

        return streamMedia(
            name = item.fileName,
            mime = mime,
            input = input,
            total = total,
            rangeHeader = session.headers["range"] ?: session.headers["Range"],
            download = download
        )
    }

    private fun servePartial(session: IHTTPSession): Response {
        val id = session.uri.removePrefix("/stream_part/")
        val item = App.engine.items.value.find { it.id == id } ?: return notFound()
        if (item.state == DownloadState.COMPLETED) return notFound()
        // Stream file parsial (.part) yang masih berjalan; dukung Range biar
        // player eksternal bisa memutar progresif dan seek dalam batas terunduh.
        val mime = MimeTypes.forFile(item.fileName)
        if (item.segments.isNotEmpty()) {
            // Download segmen: gabungkan potongan yang sudah terunduh secara
            // berurutan agar tetap bisa distream (Range relatif ke gabungan).
            val parts = item.segments.sortedBy { it.index }.mapNotNull { seg ->
                File(File(context.filesDir, "downloads"), "${item.fileName}.part.${seg.index}")
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
        val partial = File(File(context.filesDir, "downloads"), item.fileName + ".part")
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
        val token = session.parms["token"].orEmpty()
        if (token.isEmpty()) return notFound()
        val raw = MediaLibrary.decodeToken(token) ?: return notFound()
        return runCatching {
            val thumb = getOrCreateThumb(raw)
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
        }.getOrElse { notFound() }
    }

    private fun getOrCreateThumb(raw: String): File? {
        val key = sha256Hex(raw).take(16)
        val dir = File(context.cacheDir, "thumbs").apply { runCatching { mkdirs() } }
        if (!dir.isDirectory) return null
        val cached = File(dir, "$key.jpg")
        if (cached.isFile && cached.length() > 0) return cached
        val bmp = generateThumb(raw) ?: return null
        return runCatching {
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

    private fun generateThumb(raw: String): Bitmap? {
        return runCatching {
            when {
                raw.startsWith("f:") -> {
                    val file = File(raw.substring(2))
                    if (!file.isFile || !isFsPathAllowed(file.absolutePath)) return null
                    if (MediaLibrary.mediaKind(file.name) == "video") {
                        videoThumb(path = file.absolutePath)
                    } else {
                        imageThumb(path = file.absolutePath)
                    }
                }
                raw.startsWith("u:") -> {
                    val uri = raw.substring(2).toUri()
                    if (!isMediaUriAllowed(uri)) return null
                    val name = DocumentFile.fromSingleUri(context, uri)?.name.orEmpty()
                    if (MediaLibrary.mediaKind(name) == "video") {
                        videoThumb(uri = uri)
                    } else {
                        imageThumb(uri = uri)
                    }
                }
                else -> null
            }
        }.getOrNull()
    }

    private fun videoThumb(path: String? = null, uri: Uri? = null): Bitmap? {
        if (path == null && uri == null) return null
        val mmr = MediaMetadataRetriever()
        return try {
            if (path != null) {
                mmr.setDataSource(path)
            } else {
                mmr.setDataSource(context, uri)
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

    private fun imageThumb(path: String? = null, uri: Uri? = null): Bitmap? {
        return runCatching {
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            if (path != null) {
                BitmapFactory.decodeFile(path, bounds)
            } else {
                uri?.let {
                    context.contentResolver.openInputStream(it)?.use { s ->
                        BitmapFactory.decodeStream(s, null, bounds)
                    }
                }
            }
            if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
            var sample = 1
            while (bounds.outWidth / (sample * 2) >= 480 &&
                bounds.outHeight / (sample * 2) >= 480
            ) {
                sample *= 2
            }
            val opts = BitmapFactory.Options().apply { inSampleSize = sample }
            val bmp = if (path != null) {
                BitmapFactory.decodeFile(path, opts)
            } else {
                uri?.let {
                    context.contentResolver.openInputStream(it)?.use { s ->
                        BitmapFactory.decodeStream(s, null, opts)
                    }
                }
            } ?: return null
            scaleDown(bmp, 480)
        }.getOrNull()
    }

    private fun serveMedia(session: IHTTPSession): Response {
        val token = session.parms["token"].orEmpty()
        if (token.isEmpty()) return notFound()
        val raw = MediaLibrary.decodeToken(token) ?: return notFound()
        val download = session.parms["dl"] == "1"
        val input: InputStream
        val total: Long
        val name: String
        when {
            raw.startsWith("f:") -> {
                val file = File(raw.substring(2))
                if (!file.isFile || !isFsPathAllowed(file.absolutePath)) return notFound()
                input = FileInputStream(file)
                total = file.length()
                name = file.name
            }
            raw.startsWith("u:") -> {
                val uri = raw.substring(2).toUri()
                if (!isMediaUriAllowed(uri)) return notFound()
                val resolver = context.contentResolver
                val stream = resolver.openInputStream(uri) ?: return notFound()
                val len = resolver.openAssetFileDescriptor(uri, "r")?.use { it.length } ?: -1L
                input = stream
                total = len
                name = DocumentFile.fromSingleUri(context, uri)?.name ?: "media"
            }
            else -> return notFound()
        }
        return streamMedia(
            name = name,
            mime = MimeTypes.forFile(name),
            input = input,
            total = total,
            rangeHeader = session.headers["range"] ?: session.headers["Range"],
            download = download
        )
    }

    private fun galleryJson(session: IHTTPSession): Response {
        val q = session.parms["q"]?.trim()?.lowercase().orEmpty()
        val type = session.parms["type"]?.trim().orEmpty()
        val page = (session.parms["page"]?.toIntOrNull() ?: 0).coerceAtLeast(0)
        // Iterasi sekali tanpa membuat daftar hasil filter penuh di memori
        // (hemat alokasi untuk galeri besar); hanya halaman aktif yang di-hold.
        val start = page * GALLERY_PAGE_SIZE
        val pageEnd = start + GALLERY_PAGE_SIZE
        // Browsing biasa: cache scan dibatasi ke halaman aktif + 1 buffer
        // (bukan 3000 entri penuh) supaya RAM server tetap rendah. Saat ada
        // filter (q/type) hasil biasanya kecil, jadi scan penuh dipakai agar
        // hasMore tetap akurat tanpa halaman kosong berulang.
        val scanLimit = if (q.isNotEmpty() || type.isNotEmpty()) {
            MediaLibrary.GALLERY_MAX_ENTRIES
        } else {
            (pageEnd + GALLERY_PAGE_SIZE).coerceAtMost(MediaLibrary.GALLERY_MAX_ENTRIES)
        }
        val arr = JSONArray()
        val cache = loadVideoDurations()
        var extracted = 0
        var matched = 0
        val scan = scannedGallery(scanLimit)
        for (e in scan.items) {
            if (q.isNotEmpty() && e.name.indexOf(q, ignoreCase = true) < 0) continue
            if ((type == "video" && !e.isVideo) || (type == "image" && e.isVideo)) continue
            if (matched >= start && matched < pageEnd) {
                val o = JSONObject()
                o.put("name", e.name)
                o.put("size", e.size)
                o.put("modified", e.modified)
                o.put("isVideo", e.isVideo)
                o.put("token", e.token)
                if (e.isVideo && !e.isPartial) {
                    var d = cache.optLong(e.token, 0L)
                    if (d <= 0 && e.durationMs > 0) d = e.durationMs
                    if (d <= 0 && extracted < 20) {
                        d = videoDurationMs(e.token)
                        if (d > 0) cache.put(e.token, d)
                        extracted++
                    }
                    o.put("durationMs", d)
                } else {
                    // Dimensi asli untuk galeri rasio asli (masonry) di remote web.
                    val dim = imageDimensions(e)
                    if (dim != null) {
                        o.put("w", dim.first)
                        o.put("h", dim.second)
                    }
                }
                arr.put(o)
            }
            matched++
        }
        if (extracted > 0) saveVideoDurations(cache)
        return jsonResponse(
            JSONObject()
                .put("items", arr)
                .put("hasMore", matched < scan.total)
                .put("total", scan.total)
        )
    }

    private fun scannedGallery(maxEntries: Int): MediaLibrary.MediaScanResult {
        val now = System.currentTimeMillis()
        val cached = galleryCache
        // Cache terpakai bila hasil lama memuat cukup entry untuk limit ini,
        // atau scan lama sudah tuntas (items.size == total) — hindari scan
        // ulang per halaman saat galeri lebih kecil dari limit.
        if (cached != null && now - cached.first < GALLERY_SCAN_TTL_MS &&
            (cached.second.items.size >= maxEntries || cached.second.items.size == cached.second.total)
        ) return cached.second
        val result = MediaLibrary.scan(context, maxEntries = maxEntries)
        galleryCache = now to result
        return result
    }

    private fun videoDurationMs(token: String): Long {
        val raw = MediaLibrary.decodeToken(token) ?: return 0L
        return runCatching {
            val mmr = MediaMetadataRetriever()
            try {
                when {
                    raw.startsWith("f:") -> mmr.setDataSource(File(raw.substring(2)).absolutePath)
                    raw.startsWith("u:") -> mmr.setDataSource(context, raw.substring(2).toUri())
                    else -> return 0L
                }
                mmr.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: 0L
            } finally {
                runCatching { mmr.release() }
            }
        }.getOrDefault(0L)
    }

    private fun loadVideoDurations(): JSONObject {
        videoDurationsCache?.let { return it }
        val loaded = runCatching {
            JSONObject(File(context.filesDir, "video_durations.json").readText())
        }.getOrDefault(JSONObject())
        videoDurationsCache = loaded
        return loaded
    }

    private fun saveVideoDurations(cache: JSONObject) {
        videoDurationsCache = cache
        if (cache.length() == 0) return
        runCatching {
            File(context.filesDir, "video_durations.json").writeText(cache.toString())
        }
    }

    // ---------- File manager ----------

    private fun fsList(session: IHTTPSession): Response {
        val raw = session.parms["path"].orEmpty()
        val offset = (session.parms["offset"]?.toIntOrNull() ?: 0).coerceAtLeast(0)
        val limit = (session.parms["limit"]?.toIntOrNull() ?: FS_PAGE_SIZE)
            .coerceIn(1, FS_PAGE_MAX)
        return when {
            raw.isEmpty() -> fsRoots()
            raw.startsWith(MS_PREFIX) -> fsListMedia(raw.removePrefix(MS_PREFIX), offset, limit)
            else -> fsListFiles(raw.removePrefix(FS_PREFIX), offset, limit)
        }
    }

    private fun imageDimensions(e: MediaLibrary.MediaEntry): Pair<Int, Int>? {
        // File .part masih berubah saat download berjalan — jangan di-cache.
        if (!e.isPartial) {
            imageDimCache[e.token]?.let { return it }
        }
        val result = runCatching {
            val opts = android.graphics.BitmapFactory.Options().apply { inJustDecodeBounds = true }
            val path = e.filePath
            if (path != null) {
                android.graphics.BitmapFactory.decodeFile(path, opts)
            } else if (e.contentUri != null) {
                context.contentResolver.openInputStream(e.contentUri.toUri())?.use { s ->
                    android.graphics.BitmapFactory.decodeStream(s, null, opts)
                }
            }
            if (opts.outWidth > 0 && opts.outHeight > 0) opts.outWidth to opts.outHeight else null
        }.getOrNull()
        if (result != null && !e.isPartial) {
            if (imageDimCache.size > 5000) imageDimCache.clear()
            imageDimCache[e.token] = result
        }
        return result
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

    private fun fsListFiles(path: String, offset: Int, limit: Int): Response {
        val items = JSONArray()
        val dir = File(path)
        var total = 0
        if (dir.isDirectory && isFsPathAllowed(path)) {
            val entries = runCatching { dir.listFiles() }.getOrNull()
                ?.sortedWith(
                    compareBy<File> { it.isFile }.thenComparator { a, b ->
                        a.name.compareTo(b.name, ignoreCase = true)
                    }
                ).orEmpty()
            total = entries.size
            // Hanya halaman aktif yang dibangun JSON-nya; statistik subfolder
            // (itemCount/totalSize) dihitung paralel untuk halaman itu saja.
            val page = entries.drop(offset).take(limit)
            val statFutures = page.filter { it.isDirectory }.associateWith { f ->
                liveStatPool().submit<Pair<Int, Long>> { fsStats(f.absolutePath) }
            }
            page.forEach { f ->
                val o = JSONObject()
                o.put("name", f.name)
                o.put("path", FS_PREFIX + f.absolutePath)
                o.put("kind", if (f.isDirectory) "dir" else "file")
                o.put("size", if (f.isFile) f.length() else 0L)
                o.put("modified", f.lastModified())
                if (f.isDirectory) {
                    val (itemCount, totalSize) = runCatching {
                        statFutures[f]?.get()
                    }.getOrNull() ?: (0 to 0L)
                    o.put("itemCount", itemCount)
                    o.put("totalSize", totalSize)
                } else {
                    o.put("token", MediaLibrary.tokenForPath(f.absolutePath))
                }
                items.put(o)
            }
        }
        return jsonResponse(
            JSONObject().put("path", path).put("items", items).put("total", total)
        )
    }

    private fun fsListMedia(relative: String, offset: Int, limit: Int): Response {
        val items = JSONArray()
        var total = 0
        if (Build.VERSION.SDK_INT >= 29) {
            val base = relative.trim('/')
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
            val files = mutableListOf<FsMediaEntry>()
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
                            files.add(
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
            val dirNames = dirs.sortedWith(String.CASE_INSENSITIVE_ORDER)
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
        if (fsStatsCache.size > 300) fsStatsCache.clear()
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
        appendLog("FS ${action.uppercase()}: $path -> ${if (ok) "OK" else "FAILED"}")
        return jsonResponse(JSONObject().put("ok", ok))
    }

    private fun fsActionFiles(action: String, path: String, name: String, dest: String): Boolean {
        if (!isFsPathAllowed(path)) return false
        val file = File(path)
        return when (action) {
            "delete" -> runCatching {
                val gone = if (file.isDirectory) file.deleteRecursively() else file.delete()
                if (gone) MediaLibrary.notifyMediaChanged(context, file.absolutePath)
                gone
            }.getOrDefault(false)
            "rename" -> {
                if (name.isBlank() || name.contains('/') || name.contains('\\')) return false
                runCatching {
                    val target = File(file.parentFile, name)
                    val ok = file.renameTo(target)
                    if (ok) {
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
                val target = File(destDir, file.name)
                if (target.exists()) return false
                if (file.renameTo(target)) {
                    MediaLibrary.notifyMediaChanged(context, file.absolutePath, target.absolutePath)
                    return true
                }
                if (!file.isFile) return false
                runCatching {
                    file.copyTo(target, overwrite = false)
                    file.delete()
                    MediaLibrary.notifyMediaChanged(context, file.absolutePath, target.absolutePath)
                    true
                }.getOrDefault(false)
            }
            "mkdir" -> {
                if (name.isBlank() || name.contains('/') || name.contains('\\')) return false
                runCatching { File(file, name).mkdirs() }.getOrDefault(false)
            }
            else -> false
        }
    }

    private fun fsActionMedia(action: String, uriStr: String, name: String, dest: String): Boolean {
        val resolver = context.contentResolver
        return runCatching {
            val uri = uriStr.toUri()
            if (!isMediaUriAllowed(uri)) return@runCatching false
            when (action) {
                "delete" -> resolver.delete(uri, null, null) > 0
                "rename" -> {
                    if (name.isBlank() || name.contains('/') || name.contains('\\')) return false
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
                    val rel = dest.removePrefix(MS_PREFIX).trim('/') + "/"
                    val values = ContentValues().apply {
                        put(MediaStore.MediaColumns.RELATIVE_PATH, rel)
                    }
                    resolver.update(uri, values, null, null) > 0
                }
                else -> false
            }
        }.getOrDefault(false)
    }

    private fun moveFileToMediaStore(file: File, relative: String): Boolean {
        if (Build.VERSION.SDK_INT < 29) return false
        if (!isFsPathAllowed(file.absolutePath)) return false
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
        return runCatching { file.delete() }.getOrDefault(false)
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
        return resolver.delete(uri, null, null) > 0
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
        return roots
    }

    private fun isFsPathAllowed(path: String): Boolean =
        ServerSecurity.isPathAllowed(path, allowedFsRoots())

    /** URI konten hanya sah bila berasal dari MediaStore Download (area aplikasi)
     *  atau dokumen SAF yang memang diberi izin oleh pengguna. */
    private fun isMediaUriAllowed(uri: Uri): Boolean {
        return runCatching {
            when (uri.authority) {
                MediaStore.AUTHORITY -> {
                    if (Build.VERSION.SDK_INT >= 29) {
                        val rel = mediaStoreRelativePath(uri) ?: return@runCatching true
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
                "com.android.providers.downloads.documents" -> true
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
        val obj = JSONObject()
        obj.put("port", listeningPort)
        obj.put("readOnly", StoragePrefs.isServerReadOnly(context))
        obj.put("appVersion", appVersion)
        obj.put("appBuild", appBuild)
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

    private fun ensureSsePump() {
        if (sseJob?.isActive == true) return
        sseJob = serverScope.launch {
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
            var lastItemsSig = Int.MIN_VALUE
            launch {
                runCatching {
                    App.engine.items.collect { items ->
                        // Hindari build JSON tiap tick: push hanya saat isi item
                        // berubah (signature), tetap dengan throttle 1 dtk.
                        val sig = itemsSignature(items)
                        if (sig != lastItemsSig) {
                            lastItemsSig = sig
                            if (sseClients.isNotEmpty() &&
                                System.currentTimeMillis() - sseLastPushAt >= SSE_MIN_INTERVAL_MS
                            ) {
                                pushFrame(buildPayload(false))
                            }
                        }
                    }
                }
            }
            // Ticker status: tetap push walau tidak ada perubahan item,
            // supaya baterai/penyimpanan/port selalu segar. Interval 10 dtk:
            // client yang butuh data lebih sering memakai /api/snapshot.
            while (true) {
                delay(10_000)
                runCatching {
                    if (sseClients.isNotEmpty()) pushFrame(buildPayload(true))
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
        pruneShares()
        val token = UUID.randomUUID().toString().replace("-", "").take(16)
        shareTokens[token] = ShareEntry(item.id, System.currentTimeMillis() + SHARE_TTL_MS)
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
            if (!file.exists() || !file.isFile) return notFound()
            input = FileInputStream(file)
            total = file.length()
        } else if (!item.contentUri.isNullOrEmpty()) {
            val uri = item.contentUri.toUri()
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
        val text = session.parms["text"].orEmpty()
        if (text.isEmpty()) return notFound()
        val bytes = qrCache[text] ?: QrCode.generate(text)?.also { cached ->
            // Batasi isi cache: teks QR berubah-ubah tidak boleh menumpuk.
            if (qrCache.size >= QR_CACHE_MAX) qrCache.clear()
            qrCache[text] = cached
        } ?: return notFound()
        return newFixedLengthResponse(
            Response.Status.OK,
            "image/png",
            ByteArrayInputStream(bytes),
            bytes.size.toLong()
        ).also { it.addHeader("Cache-Control", "no-store") }
    }

    fun appendLog(message: String) = serverLog.append(message)

    fun snapshotLog(): String = serverLog.snapshot()

    fun clearLog() = serverLog.clear()

    private fun logError(e: Exception) {
        appendLog("ERROR ${e.message}")
        CrashLog.append(context, "serve", e)
    }

    private fun jsonResponse(obj: JSONObject): Response {
        return newFixedLengthResponse(
            Response.Status.OK,
            "application/json; charset=utf-8",
            obj.toString()
        )
    }

    companion object {
        private const val SERVER_SOCKET_TIMEOUT_MS = 60_000
        private const val FS_PREFIX = "f:"
        private const val MS_PREFIX = "m:"
        private const val MAX_UPLOAD_BYTES = 2L * 1024 * 1024 * 1024
        private const val MAX_UPLOAD_MB = 2048
        private const val SHARE_TTL_HOURS = 24
        private const val SHARE_TTL_MS = SHARE_TTL_HOURS * 60L * 60 * 1000
        private const val GALLERY_SCAN_TTL_MS = 15_000L
        private const val GALLERY_PAGE_SIZE = 100
        // Listing file manager di-paginate: maks 1000 entri per request, klien
        // memuat halaman berikutnya lewat tombol "Load more". Folder raksasa
        // tidak lagi membangun JSON semua entri + statistik semua subfolder
        // sekaligus di memori.
        private const val FS_PAGE_SIZE = 1000
        private const val FS_PAGE_MAX = 5000
        private const val DEFAULT_CHUNK_BYTES = 2L * 1024 * 1024
        private const val MAX_LOGIN_ATTEMPTS = 5
        private const val LOGIN_LOCK_MS = 30_000L
        private const val FS_STATS_TTL_MS = 10_000L
        private const val SSE_MIN_INTERVAL_MS = 1_000L
        private const val QR_CACHE_MAX = 8
        // Heartbeat: tetap kirim walau tidak ada perubahan, supaya klien tahu
        // koneksi hidup (dan fallback polling klien tidak ikut jalan).
        private const val SSE_HEARTBEAT_MS = 3_000L

        fun ipv4Addresses(): List<String> = runCatching {
            NetworkInterface.getNetworkInterfaces().toList().flatMap { ni ->
                ni.inetAddresses.toList()
                    .filter { it is Inet4Address && !it.isLoopbackAddress }
                    .map { it.hostAddress.orEmpty() }
            }
        }.getOrDefault(emptyList())
    }
}
