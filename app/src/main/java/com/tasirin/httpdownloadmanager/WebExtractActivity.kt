package com.tasirin.httpdownloadmanager

import android.annotation.SuppressLint
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.webkit.CookieManager
import android.webkit.WebChromeClient
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Button
import android.widget.ProgressBar
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.isVisible
import com.tasirin.httpdownloadmanager.util.applyEdgeToEdge
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
        private const val GRAB_INTERVAL_MS = 1500L
        private const val MAX_GRAB_TRIES = 40
    }

    private lateinit var webView: WebView
    private lateinit var progress: ProgressBar
    private lateinit var statusText: TextView
    private lateinit var grabButton: Button

    private var watchUrl = ""
    private var grabTries = 0
    private var finished = false
    private var loopStarted = false
    private val handler = Handler(Looper.getMainLooper())

    private val grabLoop = object : Runnable {
        override fun run() {
            if (finished) return
            loopStarted = true
            if (grabTries >= MAX_GRAB_TRIES) {
                statusText.text = getString(R.string.web_extract_failed)
                progress.isVisible = false
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
        if (watchUrl.isBlank()) {
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
        settings.domStorageEnabled = true
        settings.loadsImagesAutomatically = false
        settings.setSupportMultipleWindows(false)
        settings.cacheMode = WebSettings.LOAD_DEFAULT
        CookieManager.getInstance().setAcceptThirdPartyCookies(webView, false)
        webView.webChromeClient = WebChromeClient()
        webView.webViewClient = object : WebViewClient() {
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
        }
        grabButton.setOnClickListener { runGrabScript() }
        closeButton.setOnClickListener {
            setResult(RESULT_CANCELED)
            finish()
        }
        webView.loadUrl(watchUrl)
        // Jaring pengaman: mulai polling walau onPageFinished telat (halaman berat).
        handler.postDelayed({ startGrabLoop() }, GRAB_INTERVAL_MS * 3)
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
        val js = "(function(){try{" +
            "var docs=[document];" +
            "try{for(var fi=0;fi<window.frames.length;fi++){" +
            "try{var fd=window.frames[fi].document;if(fd)docs.push(fd);}catch(fe){}}}catch(ie){}" +
            "function abs(u){try{if(u.indexOf('//')===0)return location.protocol+u;" +
            "return new URL(u,location.href).href;}catch(e){return u;}}" +
            "var mp4=[],hls=[],hasVideo=false;" +
            "window.__hhBlob=false;" +
            "function collect(u){u=(u||'').trim();if(!u)return;" +
            "if(u.indexOf('blob:')===0){window.__hhBlob=true;return;}" +
            "if(u.indexOf('//')===0)u=location.protocol+u;" +
            "if(u.indexOf('http')!==0)return;" +
            "u=abs(u);" +
            "if(/\\.m3u8/i.test(u)){if(hls.indexOf(u)<0)hls.push(u);}" +
            "else{if(mp4.indexOf(u)<0)mp4.push(u);}}" +
            "docs.forEach(function(doc){try{" +
            "var vs=doc.getElementsByTagName('video');" +
            "for(var i=0;i<vs.length;i++){hasVideo=true;" +
            "var v=vs[i];" +
            "if(v.currentSrc&&v.currentSrc.indexOf('blob:')!==0)collect(v.currentSrc);" +
            "else if(v.currentSrc)window.__hhBlob=true;" +
            "collect(v.src);collect(v.getAttribute('data-src'));" +
            "collect(v.getAttribute('data-video'));}" +
            "var ss=doc.getElementsByTagName('source');" +
            "for(var j=0;j<ss.length;j++){collect(ss[j].src);" +
            "collect(ss[j].getAttribute('data-src'));}" +
            "var m=doc.querySelector('meta[property=\"og:video\"]');" +
            "if(m)collect(m.content);" +
            "var t=doc.querySelector('meta[name=\"twitter:player:stream\"],meta[property=\"twitter:player:stream\"]');" +
            "if(t)collect(t.content);" +
            "var ls=doc.getElementsByTagName('link');" +
            "for(var k=0;k<ls.length;k++){var hr=ls[k].getAttribute('href')||'';" +
            "if(/\\.mp4|\\.m3u8/.test(hr))collect(hr);}" +
            "}catch(e){}});" +
            "var mp42=[],hls2=[];" +
            "function bucket2(u){u=abs((u||'').trim());if(u.indexOf('http')!==0)return;" +
            "if(/\\.m3u8/i.test(u)){if(hls2.indexOf(u)<0)hls2.push(u);}else{if(mp42.indexOf(u)<0)mp42.push(u);}}" +
            "var h='';" +
            "try{h=document.documentElement.innerHTML.slice(0,1000000).replace(/\\\\\\//g,'/');}catch(e){}" +
            "var fm=h.match(/(?:file|src|source)\\s*:\\s*[\"'](https?:[^\"']+?\\.(?:mp4|m3u8)[^\"']*)[\"']/gi)||[];" +
            "for(var f=0;f<fm.length;f++){var fu=fm[f].replace(/^[^\"']*[\"']/, '').replace(/[\"'].*\$/, '');" +
            "bucket2(fu);}" +
            "var a=h.match(/https?:\\/\\/[^\\s\"'<>]+\\.mp4[^\\s\"'<>]*/gi)||[];" +
            "for(var p=0;p<a.length;p++)bucket2(a[p]);" +
            "var b=h.match(/https?:\\/\\/[^\\s\"'<>]+\\.m3u8[^\\s\"'<>]*/gi)||[];" +
            "for(var q=0;q<b.length;q++)bucket2(b[q]);" +
            "mp4=mp4.concat(mp42);hls=hls.concat(hls2);" +
            "var src=mp4.length>0?mp4[0]:(hls.length>0?hls[0]:'');" +
            "return JSON.stringify({src:src,title:document.title||''," +
            "hasVideo:(hasVideo||window.__hhBlob===true),hasBlob:(window.__hhBlob===true)});" +
            "}catch(e){return JSON.stringify({src:'',title:'',hasVideo:false,hasBlob:false});}})()"
        webView.evaluateJavascript(js) { raw ->
            if (finished) return@evaluateJavascript
            val payload = raw?.trim('"')?.replace("\\\"", "\"")?.replace("\\\\", "\\").orEmpty()
            val obj = runCatching { JSONObject(payload) }.getOrNull() ?: return@evaluateJavascript
            val src = obj.optString("src").replace("\\/", "/")
            if (src.startsWith("http")) {
                onVideoFound(src, obj.optString("title"))
            } else if (obj.optBoolean("hasVideo") || obj.optBoolean("hasBlob")) {
                statusText.text = getString(R.string.web_extract_ready)
            }
        }
    }

    private fun onVideoFound(videoUrl: String, title: String) {
        finished = true
        handler.removeCallbacks(grabLoop)
        statusText.text = getString(R.string.web_extract_found)
        val cookies = runCatching {
            CookieManager.getInstance().getCookie(watchUrl)
        }.getOrNull().orEmpty()
        setResult(
            RESULT_OK,
            Intent()
                .putExtra(EXTRA_VIDEO_URL, videoUrl)
                .putExtra(EXTRA_COOKIES, cookies)
                .putExtra(EXTRA_TITLE, title)
                .putExtra(EXTRA_WATCH_URL, watchUrl)
        )
        finish()
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
