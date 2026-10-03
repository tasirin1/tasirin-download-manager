package com.tasirin.httpdownloadmanager

import android.annotation.SuppressLint
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import androidx.core.net.toUri
import android.webkit.CookieManager
import android.webkit.WebChromeClient
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Button
import android.widget.ProgressBar
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.isVisible
import com.tasirin.httpdownloadmanager.util.applyEdgeToEdge
import com.tasirin.httpdownloadmanager.util.readBounded
import org.json.JSONArray
import org.json.JSONObject

/** Browser ekstraksi video dalam aplikasi (WebView sistem, tanpa dependensi).
 *  Dipakai untuk situs yang memblokir fetch server langsung (challenge
 *  Cloudflare, mis. HentaiHaven): WebView mewarisi tantangan + cookie browser
 *  asli sehingga halaman lolos, lalu URL video + cookie dikembalikan ke
 *  pemanggil untuk diunduh engine seperti unduhan biasa. */
class WebExtractActivity : AppCompatActivity() {

    companion object {
        const val EXTRA_URL = "extra_url"
        const val EXTRA_VIDEO_URL = "extra_video_url"
        const val EXTRA_COOKIES = "extra_cookies"
        const val EXTRA_TITLE = "extra_title"
        const val EXTRA_WATCH_URL = "extra_watch_url"
        const val EXTRA_EPISODES_JSON = "extra_episodes_json"
        private const val GRAB_INTERVAL_MS = 1500L
        private const val MAX_GRAB_TRIES = 40
        private const val MAX_EPISODES = 50
    }

    private lateinit var webView: WebView
    private lateinit var progress: ProgressBar
    private lateinit var statusText: TextView
    private lateinit var grabButton: Button

    private var watchUrl = ""
    private var grabTries = 0
    private var finished = false
    private var loopStarted = false
    private var manualGrab = false
    private var lastEpisodesJson = ""
    private var allowedHost = ""
    private var cachedGrabScript: String? = null
    private val handler = Handler(Looper.getMainLooper())

    private val grabLoop = object : Runnable {
        override fun run() {
            if (finished) return
            loopStarted = true
            if (grabTries >= MAX_GRAB_TRIES) {
                if (lastEpisodesJson.isNotBlank() && lastEpisodesJson != "[]") {
                    onEpisodesOnly(lastEpisodesJson, webView.title.orEmpty())
                } else {
                    statusText.text = getString(R.string.web_extract_failed)
                    progress.isVisible = false
                }
                return
            }
            grabTries++
            runGrabScript()
            handler.postDelayed(this, GRAB_INTERVAL_MS)
        }
    }

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_web_extract)
        applyEdgeToEdge(findViewById(R.id.web_extract_root))
        watchUrl = intent.getStringExtra(EXTRA_URL).orEmpty()
        // Kunci masuk: hanya URL http(s) dengan host. WebView ekstraksi bukan
        // browser umum (tidak ada address bar), jadi jangan muat skema aneh
        // (intent:, file:, javascript:) yang bisa disalahgunakan pemanggil.
        allowedHost = runCatching { watchUrl.toUri() }.getOrNull()?.host.orEmpty()
        if (watchUrl.isBlank() ||
            !(watchUrl.startsWith("http://") || watchUrl.startsWith("https://")) ||
            allowedHost.isEmpty()
        ) {
            setResult(RESULT_CANCELED)
            finish()
            return
        }
        webView = findViewById(R.id.web_extract_view)
        progress = findViewById(R.id.web_extract_progress)
        statusText = findViewById(R.id.web_extract_status)
        grabButton = findViewById(R.id.btn_web_grab)
        val closeButton: Button = findViewById(R.id.btn_web_close)
        statusText.text = getString(R.string.web_extract_loading)
        val settings = webView.settings
        settings.javaScriptEnabled = true
        // domStorage tetap aktif: challenge Cloudflare + player video butuh
        // localStorage/sessionStorage; tanpa JS bridge sehingga JS halaman
        // tak bisa memanggil kode aplikasi (hanya dibaca via evaluateJavascript).
        settings.domStorageEnabled = true
        settings.loadsImagesAutomatically = false
        settings.setSupportMultipleWindows(false)
        settings.cacheMode = WebSettings.LOAD_DEFAULT
        CookieManager.getInstance().setAcceptThirdPartyCookies(webView, false)
        webView.webChromeClient = WebChromeClient()
        webView.webViewClient = object : WebViewClient() {
            // true = host tujuan sama dengan halaman awal ( challenge
            // Cloudflare/redirect login selalu satu host, jadi ini cukup).
            fun isAllowedTarget(url: String): Boolean =
                runCatching { url.toUri() }.getOrNull()?.host?.equals(allowedHost, ignoreCase = true) == true

            // Signature lama melayani API 21-23; versi request melayani 24+.
            // Blokir navigasi keluar host (iklan/redirect): ekstraksi cukup
            // membaca DOM halaman awal, tak perlu menjelajah situs lain.
            @Suppress("DEPRECATION")
            override fun shouldOverrideUrlLoading(view: WebView, url: String): Boolean =
                !isAllowedTarget(url)

            @SuppressLint("NewApi") // Override API 24+; aman di minSdk 21 (tak pernah dipanggil di bawah 24).
            override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean =
                !isAllowedTarget(request.url.toString())

            override fun onPageFinished(view: WebView, url: String) {
                statusText.text = getString(R.string.web_extract_ready)
                startGrabLoop()
            }

            // Signature lama (deprecated di 23) sengaja dipakai agar aman minSdk 21:
            // hanya error main frame yang dilaporkan, error sub-frame (iklan) diabaikan.
            @Suppress("DEPRECATION")
            override fun onReceivedError(
                view: WebView,
                errorCode: Int,
                description: String?,
                failingUrl: String?
            ) {
                if (failingUrl != null && failingUrl == view.url) {
                    statusText.text = getString(R.string.web_extract_failed)
                    progress.isVisible = false
                }
            }

            // Callback API 23+: tanpa ini error main frame di perangkat modern
            // tak pernah tampil dan status macet di "loading".
            @SuppressLint("NewApi")
            override fun onReceivedError(
                view: WebView,
                request: WebResourceRequest,
                error: WebResourceError
            ) {
                if (request.isForMainFrame) {
                    statusText.text = getString(R.string.web_extract_failed)
                    progress.isVisible = false
                }
            }
        }
        grabButton.setOnClickListener { manualGrab = true; runGrabScript() }
        closeButton.setOnClickListener {
            setResult(RESULT_CANCELED)
            finish()
        }
        webView.loadUrl(watchUrl)
        // Jaring pengaman: mulai polling walau onPageFinished telat (halaman berat).
        handler.postDelayed({ startGrabLoop() }, GRAB_INTERVAL_MS * 3)
    }

    /** Muat skrip grab sekali dari assets (gagal -> null, polling mencoba lagi).
     *  Baca dibatasi 64KB via readBounded (file ~3KB; tanpa batas = warning audit). */
    private fun grabScript(): String? {
        cachedGrabScript?.let { return it }
        val js = runCatching {
            assets.open("extract.js").use { readBounded(it, 64 * 1024) }
        }.getOrNull()?.trim()
        if (js.isNullOrEmpty()) return null
        cachedGrabScript = js
        return js
    }

    /** Satu rantai polling saja (onPageFinished + timer pengaman bisa berlomba). */
    private fun startGrabLoop() {
        if (finished || loopStarted) return
        loopStarted = true
        handler.post(grabLoop)
    }

    /** Cari URL video di DOM utama + iframe: tag video/source (termasuk
     *  `data-src` lazy-load), meta og:video/twitter stream, lalu pindaian
     *  `.mp4` (diutamakan) / `.m3u8` pada HTML + pola JS `file:`/`src:`. */
    private fun runGrabScript() {
        if (finished) return
        // Skrip dibaca dari assets/extract.js (bukan string inline): dex tak lagi
        // memuat blob skrip terpaket yang memicu heuristik antivirus (Wacatac).
        val js = grabScript() ?: return
        webView.evaluateJavascript(js) { raw ->
            if (finished) return@evaluateJavascript
            val payload = raw?.trim('"')?.replace("\\\"", "\"")?.replace("\\\\", "\\").orEmpty()
            val obj = runCatching { JSONObject(payload) }.getOrNull() ?: return@evaluateJavascript
            val episodesJson = trimEpisodes(obj.optJSONArray("episodes")?.toString().orEmpty())
            if (episodesJson.isNotBlank() && episodesJson != "[]") lastEpisodesJson = episodesJson
            val src = obj.optString("src").replace("\\/", "/")
            if (src.startsWith("http")) {
                onVideoFound(src, obj.optString("title"), episodesJson)
            } else if (manualGrab) {
                manualGrab = false
                if (episodesJson.isNotBlank() && episodesJson != "[]") {
                    onEpisodesOnly(episodesJson, obj.optString("title"))
                } else {
                    statusText.text = getString(R.string.web_extract_failed)
                }
            } else if (obj.optBoolean("hasVideo") || obj.optBoolean("hasBlob")) {
                statusText.text = getString(R.string.web_extract_ready)
            }
        }
    }

    private fun onVideoFound(videoUrl: String, title: String, episodesJson: String) {
        finished = true
        handler.removeCallbacks(grabLoop)
        statusText.text = getString(R.string.web_extract_found)
        setResult(
            RESULT_OK,
            Intent()
                .putExtra(EXTRA_VIDEO_URL, videoUrl)
                .putExtra(EXTRA_COOKIES, extractCookies())
                .putExtra(EXTRA_TITLE, title)
                .putExtra(EXTRA_WATCH_URL, watchUrl)
                .putExtra(EXTRA_EPISODES_JSON, episodesJson)
        )
        finish()
    }

    /** Halaman tanpa video tapi berisi daftar episode (mis. halaman seri):
     *  kembalikan daftarnya agar pemanggil bisa menawarkan unduh batch. */
    private fun onEpisodesOnly(episodesJson: String, title: String) {
        finished = true
        handler.removeCallbacks(grabLoop)
        statusText.text = getString(R.string.web_extract_found)
        setResult(
            RESULT_OK,
            Intent()
                .putExtra(EXTRA_VIDEO_URL, "")
                .putExtra(EXTRA_COOKIES, extractCookies())
                .putExtra(EXTRA_TITLE, title)
                .putExtra(EXTRA_WATCH_URL, watchUrl)
                .putExtra(EXTRA_EPISODES_JSON, episodesJson)
        )
        finish()
    }

    /** Cookie WebView halaman aktif (diteruskan sebagai header `Cookie`).
     *  Pakai URL aktif WebView dulu: setelah redirect Cloudflare/login, cookie
     *  menempel di host final, bukan URL awal sehingga unduhan tak lagi 403. */
    private fun extractCookies(): String = runCatching {
        val cm = CookieManager.getInstance()
        val current = if (::webView.isInitialized) {
            runCatching { webView.url }.getOrNull().orEmpty()
        } else ""
        current.takeIf { it.startsWith("http") }?.let { cm.getCookie(it) }?.takeIf { it.isNotEmpty() }
            ?: cm.getCookie(watchUrl)
    }.getOrNull().orEmpty()

    /** Batasi daftar episode titipan JS agar intent hasil tetap ringan. */
    private fun trimEpisodes(json: String): String {
        if (json.isBlank()) return ""
        val arr = runCatching { JSONArray(json) }.getOrNull() ?: return ""
        if (arr.length() <= MAX_EPISODES) return arr.toString()
        val out = JSONArray()
        for (i in 0 until MAX_EPISODES) out.put(arr.optJSONObject(i) ?: continue)
        return out.toString()
    }

    override fun onDestroy() {
        finished = true
        handler.removeCallbacks(grabLoop)
        if (::webView.isInitialized) {
            (webView.parent as? android.view.ViewGroup)?.removeView(webView)
            webView.destroy()
        }
        super.onDestroy()
    }

}
