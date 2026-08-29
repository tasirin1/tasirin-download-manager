package com.tasirin.httpdownloadmanager

import android.annotation.SuppressLint
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.webkit.CookieManager
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.appcompat.app.AppCompatActivity
import com.tasirin.httpdownloadmanager.databinding.ActivityScribdExtractorBinding
import com.tasirin.httpdownloadmanager.util.applyEdgeToEdge
import org.json.JSONTokener

/**
 * Ekstraktor Scribd berbasis WebView. Scribd memblokir klien HTTP biasa dengan
 * Fastly Client Challenge (butuh TLS + JS browser asli), jadi halaman dirender
 * di Chromium WebView perangkat — challenge lolos sendiri di IP pengguna.
 * Setelah viewer tampil, script [R.raw.scribd_harvest] menggulir dokumen,
 * mengumpulkan URL gambar tiap halaman (html.scribdassets.com) + judul,
 * lalu hasil dikembalikan lewat ActivityResult ke MainActivity.
 */
class ScribdExtractorActivity : AppCompatActivity() {

    companion object {
        const val EXTRA_URL = "scribd_url"
        const val EXTRA_PAGE_URLS = "scribd_page_urls"
        const val EXTRA_COOKIES = "scribd_cookies"
        const val EXTRA_TITLE = "scribd_title"

        private const val TIMEOUT_MS = 150_000L
        private const val POLL_MS = 1_200L
    }

    private lateinit var binding: ActivityScribdExtractorBinding
    private var pageUrl: String = ""
    private var harvestStarted = false
    private var settled = false
    private val handler = Handler(Looper.getMainLooper())
    private val timeout = Runnable { finishCanceled(getString(R.string.scribd_extract_failed)) }
    private val poll = object : Runnable {
        override fun run() {
            if (!settled) pollHarvest()
        }
    }

    private data class Harvest(val urls: List<String>, val title: String, val cookies: String)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityScribdExtractorBinding.inflate(layoutInflater)
        setContentView(binding.root)
        applyEdgeToEdge(binding.root)
        pageUrl = intent.getStringExtra(EXTRA_URL)?.trim().orEmpty()
        if (pageUrl.isEmpty()) {
            setResult(RESULT_CANCELED)
            finish()
            return
        }
        binding.status.text = getString(R.string.scribd_rendering)
        binding.cancel.setOnClickListener { finishCanceled(null) }
        setupWebView()
        handler.postDelayed(timeout, TIMEOUT_MS)
    }

    @SuppressLint("SetJavaScriptEnabled")
    private fun setupWebView() {
        val webView = binding.webView
        val settings = webView.settings
        settings.javaScriptEnabled = true
        settings.domStorageEnabled = true
        settings.loadsImagesAutomatically = true
        settings.mediaPlaybackRequiresUserGesture = false
        settings.allowFileAccess = false
        settings.allowContentAccess = false
        settings.setSupportZoom(false)
        settings.cacheMode = WebSettings.LOAD_DEFAULT
        webView.webViewClient = object : WebViewClient() {
            override fun onPageFinished(view: WebView?, url: String?) {
                super.onPageFinished(view, url)
                if (!harvestStarted) {
                    harvestStarted = true
                    binding.status.text = getString(R.string.scribd_extracting)
                    val js = resources.openRawResource(R.raw.scribd_harvest)
                        .bufferedReader()
                        .use { it.readText() }
                    view?.evaluateJavascript(js, null)
                    view?.evaluateJavascript("window.__scribdStartHarvest()", null)
                    handler.postDelayed(poll, 2_500L)
                }
            }
        }
        webView.loadUrl(pageUrl)
    }

    private fun pollHarvest() {
        binding.webView.evaluateJavascript("window.__scribdHarvestResult || ''") { value ->
            if (settled) return@evaluateJavascript
            val json = unquoteJs(value)
            if (json.isNotEmpty() && json != "null") {
                val harvest = parseResult(json)
                if (harvest != null) {
                    finishOk(harvest)
                    return@evaluateJavascript
                }
            }
            handler.postDelayed(poll, POLL_MS)
        }
    }

    private fun parseResult(json: String): Harvest? {
        return try {
            val obj = JSONTokener(json).nextValue() as? org.json.JSONObject ?: return null
            val arr = obj.optJSONArray("urls")
            val urls = ArrayList<String>()
            if (arr != null) {
                for (i in 0 until arr.length()) {
                    val u = arr.optString(i)
                    if (u.startsWith("http")) urls.add(u)
                }
            }
            if (urls.isEmpty()) return null
            val title = obj.optString("title", "")
            val hosts = listOfNotNull(
                runCatching { java.net.URL(pageUrl).host }.getOrNull(),
                "html.scribdassets.com",
                "www.scribdassets.com"
            )
            val cookies = hosts.mapNotNull { host ->
                CookieManager.getInstance().getCookie(host)
            }.filter { it.isNotBlank() }.joinToString("; ")
            Harvest(urls, title, cookies)
        } catch (_: Exception) {
            null
        }
    }

    private fun unquoteJs(value: String?): String {
        if (value.isNullOrBlank()) return ""
        val v = value.trim()
        if (v.length >= 2 && v.startsWith('"') && v.endsWith('"')) {
            return try {
                JSONTokener(v).nextValue().toString()
            } catch (_: Exception) {
                v.substring(1, v.length - 1)
            }
        }
        return v
    }

    private fun finishOk(harvest: Harvest) {
        if (settled) return
        settled = true
        handler.removeCallbacks(timeout)
        val data = Intent().apply {
            putStringArrayListExtra(EXTRA_PAGE_URLS, ArrayList(harvest.urls))
            putExtra(EXTRA_TITLE, harvest.title)
            putExtra(EXTRA_COOKIES, harvest.cookies)
        }
        setResult(RESULT_OK, data)
        finish()
    }

    private fun finishCanceled(message: String?) {
        if (settled) return
        settled = true
        handler.removeCallbacks(timeout)
        if (message != null) {
            binding.status.text = message
        }
        setResult(RESULT_CANCELED)
        finish()
    }

    override fun onDestroy() {
        settled = true
        handler.removeCallbacksAndMessages(null)
        runCatching { binding.webView.destroy() }
        super.onDestroy()
    }
}
