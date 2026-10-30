package com.tasirin.httpdownloadmanager.util

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONArray
import org.json.JSONObject
import android.content.Context
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.io.InputStream
import java.util.Locale
import java.util.concurrent.Callable
import java.util.concurrent.ExecutorService
import java.util.concurrent.SynchronousQueue
import java.util.concurrent.ThreadFactory
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit
import javax.net.ssl.HttpsURLConnection

object SocialMediaExtractor {

    /** Context aplikasi untuk trust anchor tambahan (Sectigo R46 dkk) pada
     *  API 21-23 yang mengabaikan network-security-config. Diisi sekali dari
     *  [App.onCreate] / [DownloadEngine]; null = pakai trust bawaan sistem. */
    @Volatile
    private var appContext: Context? = null

    fun init(context: Context) {
        appContext = context.applicationContext
    }

    /** Buka koneksi HTTP; untuk HTTPS terapkan trust anchor bundle bila ada
     *  context (tanpa ini halaman watch + API di perangkat lama / root
     *  Sectigo hilang gagal dengan Trust anchor not found). */
    private fun openTlsConn(urlStr: String): HttpURLConnection {
        val conn = URL(urlStr).openConnection() as HttpURLConnection
        val ctx = appContext
        if (ctx != null && conn is HttpsURLConnection) {
            runCatching { TlsCompat.apply(conn, ctx) }
        }
        return conn
    }

    /** Batas total waktu ekstraksi media sosial (ms). */
    private const val EXTRACT_TOTAL_TIMEOUT_MS = 60_000L

    // Anggaran total probe 403 semua opsi YouTube (issue #805): satu
    // gelombang paralel HEAD (connect+read 6+6 dtk) plus margin.
    private const val FORBIDDEN_PROBE_BUDGET_MS = 15_000L

    /** Timeout HTTP halaman/API per request (dipakai 5+ situs, satu tempat). */
    private const val PAGE_TIMEOUT_MS = 20000

    /* Konstanta User-Agent — string UA diulang 7x+ di file ini; satu tempat. */
    private const val YT_PAGE_UA =
        "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Safari/537.36"
    private const val YT_UA =
        "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 Chrome/131.0.0.0"
    private const val YT_LANG = "en-US,en;q=0.9"
    private const val VISIONOS_UA =
        "Mozilla/5.0 (Macintosh; Intel Mac OS X 15_7_3) AppleWebKit/605.1.15"
    private const val API_UA = "Mozilla/5.0"
    private const val DEFAULT_UA = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36"
    private const val MIME_MP4 = "video/mp4"

    /** Regex X/Twitter — x.com harus host (awal string atau setelah skema),
     *  bukan substring (mis. fbsbx.com mengandung "x.com/"). */
    private val X_URL_RE = Regex("""\A(?:https?://)?(?:www\.)?x\.com/""")
    /** Regex presisi untuk deteksi domain — mencegah false-positive (mis. notyoutube.com).
     *  SEMUA regex host di bawah berjangkar \A (awal string) sejak #245:
     *  tanpa jangkar, URL pembungkus host lain yang menyematkan teks URL
     *  platform di query (evil.example/?next=https://facebook.com/...)
     *  lolos deteksi dan halaman pembungkus diambil dengan header user —
     *  Cookie akun bisa terkirim ke host pembungkus. */
    private val YT_HOST_RE = Regex("""\A(?:https?://)(?:(?:www\.|m\.|music\.)?youtube\.com/|youtu\.be/)""")
    private val TT_HOST_RE = Regex("""\A(?:https?://)(?:(?:www\.|m\.)?tiktok\.com/|vm\.tiktok\.com/)""")
    private val IG_HOST_RE = Regex("""\A(?:https?://)(?:(?:www\.)?instagram\.com/(?:p|reel|tv)/|instagr\.am/(?:p|reel)/)""")
    private val TW_HOST_RE = Regex("""\A(?:https?://)(?:www\.)?twitter\.com/""")
    private val IG_BROAD_HOST_RE = Regex("""\A(?:https?://)(?:(?:www\.)?instagram\.com/|instagr\.am/)""")
    private val XV_HOST_RE = Regex("""\A(?:https?://)(?:www\.|m\.|mobile\.)?xvideos\.com/""")
    private val XN_HOST_RE = Regex("""\A(?:https?://)(?:(?:www\.|m\.|mobile\.)?xnxx\.com/|(?:www\.)?xnxxvideos\.me/)""")
    private val PH_HOST_RE = Regex("""\A(?:https?://)(?:(?:www\.|m\.|mobile\.)?pornhub\.com/|(?:www\.)?pornhubpremium\.com/)""")
    /** Google Drive: hanya link berkas (file/d/, open?id=, uc?id=) — link
     *  folder dan halaman Drive lain tidak diekstrak. */
    private val GD_HOST_RE = Regex("""\A(?:https?://)(?:www\.)?drive\.google\.com/(?:drive/u/\d+/)?(?:file/d/|open\?|uc\?)""")
    /** Facebook: regex host presisi agar CDN fbsbx.com/fbcdn.net (media
     *  hasil ekstraksi) tidak ikut dikenali sebagai halaman. */
    private val FB_HOST_RE = Regex("""\A(?:https?://)(?:(?:www\.|m\.|mbasic\.)?facebook\.com/|fb\.watch/)""")
    /** HentaiHaven: halaman watch (`/watch/<slug>/`) dilindungi challenge
     *  Cloudflare sehingga fetch server pasti 403; ekstraksi memakai WebView
     *  (`WebExtractActivity`) + parser murni di bawah sebagai fallback. */
    // audit-ignore-next: maintenance_marker (nama domain resmi situs, bukan marker)
    private val HH_HOST_RE = Regex("""\A(?:https?://)(?:www\.)?hentaihaven\.xxx/""")

    /* Regex tetap — dihoist agar tidak dikompilasi ulang di jalur ekstraksi
     * (Instagram & YouTube) yang dipanggil berulang saat unduh. */
    private val IG_SHORTCODE_RE = Regex("/(?:p|reel|tv)/([A-Za-z0-9_-]+)")
    private val IG_IMG_INDEX_RE = Regex("[?&]img_index=(\\d+)")
    private val IG_IMG_URL_RE =
        Regex("https?://[^\"]*scontent[^\"]*cdninstagram\\.com[^\"]*\\.(?:jpg|jpeg|png|webp)[^\"]*")
    private val IG_IMG_URL_FALLBACK_RE =
        Regex("https?://[^\"]*scontent[^\"]*\\.(?:jpg|jpeg|png|webp)[^\"]*")
    private val IG_FILE_ID_RE = Regex("/(\\d+_\\d+_\\d+)_[a-z0-9]+\\.(?:jpg|jpeg|png|webp)")
    private val IG_VIDEO_URL_RE = Regex("\"url\"\\s*:\\s*\"(https?://[^\"]+\\.mp4[^\"]*)\"")
    private val IG_CONTEXT_JSON_RE = Regex("contextJSON\\s*=\\s*\"")
    private val IG_TOKEN_RE = Regex("\"token\"\\s*:\\s*\"")
    private val YT_PLAYER_RESP_RE =
        Regex("""ytInitialPlayerResponse\s*=\s*(\{.*?\});\s*(?:var\s|</script)""")
    private val YT_PLAYER_RESP_LAX_RE = Regex("""ytInitialPlayerResponse\s*=\s*(\{.*?\});""")
    private val YT_VISITOR_DATA_RE = Regex("""VISITOR_DATA"\s*:\s*"([^"]+)""")
    private val YT_VISITOR_DATA_LOW_RE = Regex("""visitorData"\s*:\s*"([^"]+)""")
    /** Ambil objek JSON `ytInitialPlayerResponse` dengan pencocokan kurung
     *  seimbang (hormati string '"..."' dan escape): regex malas berhenti di
     *  kurung tutup pertama sehingga JSON bersarang selalu terpotong. Murni. */
    internal fun extractBalancedPlayerResponse(pageHtml: String): String? {
        val key = "ytInitialPlayerResponse"
        val keyIdx = pageHtml.indexOf(key)
        if (keyIdx < 0) return null
        // Jangan cocok prefix identifier lebih panjang (mis. ...ResponseFoo):
        // pasangan regex di bawah yang menangani kasus ambigu ini.
        val afterKey = keyIdx + key.length
        if (afterKey < pageHtml.length) {
            val c = pageHtml[afterKey]
            if (c.isLetterOrDigit() || c == '_' || c == '$') return null
        }
        val eqIdx = pageHtml.indexOf('=', keyIdx + key.length)
        if (eqIdx < 0) return null
        var i = eqIdx + 1
        while (i < pageHtml.length && pageHtml[i].isWhitespace()) i++
        if (i >= pageHtml.length || pageHtml[i] != '{') return null
        var depth = 0
        var inString = false
        var escaped = false
        val start = i
        while (i < pageHtml.length) {
            val c = pageHtml[i]
            if (inString) {
                if (escaped) escaped = false
                else if (c == '\\') escaped = true
                else if (c == '"') inString = false
            } else {
                if (c == '"') inString = true
                else if (c == '{') depth++
                else if (c == '}') {
                    depth--
                    if (depth == 0) return pageHtml.substring(start, i + 1)
                }
            }
            i++
        }
        return null
    }

    private val YT_ID_SHORTS_RE = Regex("/shorts/([A-Za-z0-9_-]{11})")
    private val YT_ID_V_RE = Regex("[?&]v=([A-Za-z0-9_-]{11})")
    private val YT_ID_YOUTU_RE = Regex("youtu\\.be/([A-Za-z0-9_-]{11})")
    private val HP_URL_HIGH_RE = Regex("""setVideoUrlHigh\s*\(\s*['"](https?://[^'"]+)['"]""")
    private val HP_URL_LOW_RE = Regex("""setVideoUrlLow\s*\(\s*['"](https?://[^'"]+)['"]""")
    private val HP_HLS_RE = Regex("""setVideoHLS\s*\(\s*['"](https?://[^'"]+?\.m3u8[^'"]*)['"]""")
    private val HP_TITLE_RE = Regex("""setVideoTitle\s*\(\s*['"](.+?)['"]\s*\)""")
    private val HP_OG_VIDEO_RE = Regex("""<meta[^>]+property\s*=\s*["']og:video["'][^>]+content\s*=\s*["'](https?://[^"']+)["']""")
    private val HP_OG_TITLE_RE = Regex("""<meta[^>]+property\s*=\s*["']og:title["'][^>]+content\s*=\s*["'](.+?)["']""")
    private val HP_TITLE_TAG_RE = Regex("""<title>(.+?)</title>""")
    private val PH_MEDIA_VIDEO_URL_RE = Regex("\"videoUrl\"\\s*:\\s*\"(https?:[^\"]+)\"")
    private val PH_MEDIA_QUALITY_RE = Regex("\"quality\"\\s*:\\s*\"?(\\d+)")
    private val PH_FLASHVARS_Q_RE = Regex("\"quality_(\\d+)p\"\\s*:\\s*\"(https?:[^\"]+)\"")
    private val PH_VIDEO_URL_RE = Regex("\"video_url\"\\s*:\\s*\"(https?:[^\"]+)\"")
    private val HH_VIDEO_TAG_RE = Regex("""<video[^>]+src\s*=\s*["'](https?://[^"']+)["']""")
    private val HH_SOURCE_TAG_RE = Regex("""<source[^>]+src\s*=\s*["'](https?://[^"']+)["']""")
    private val HH_DATA_SRC_RE = Regex("""<(?:video|source)[^>]+data-(?:src|video|source)\s*=\s*["'](https?://[^"']+)["']""")
    private val HH_JWPLAYER_RE = Regex("""(?:file|src|source)\s*:\s*["'](https?://[^"']+\.(?:mp4|m3u8)[^"']*)["']""")
    private val HH_TWITTER_STREAM_RE = Regex("""<meta[^>]+(?:name|property)\s*=\s*["']twitter:player:stream["'][^>]+content\s*=\s*["'](https?://[^"']+)["']""")
    private val HH_PRELOAD_RE = Regex("""<link[^>]+href\s*=\s*["'](https?://[^"']+\.(?:mp4|m3u8)[^"']*)["']""")
    private val HH_GENERIC_MEDIA_RE = Regex("""https?://[^\s"'<>]+\.(?:mp4|m3u8)[^\s"'<>]*""")
    private val SANITIZE_BAD_CHARS_RE = Regex("[^A-Za-z0-9_\\-. ]")
    private val SANITIZE_WS_RE = Regex("\\s+")

    data class Result(
        val directUrl: String,
        val fileName: String?,
        val title: String?,
        val quality: String = "",
        val mimeType: String = "",
        val cookies: String = "",
        val isHls: Boolean = false,
        val audioUrl: String = "",
        val videoUrl: String = ""
    )

    /** Gabungkan cookie hasil ekstraksi ke blok header unduhan (#238).
     *  Baris Cookie milik user TIDAK boleh ditimpa: applyAuthHeaders memakai
     *  setRequestProperty per baris sehingga baris Cookie yang muncul
     *  terakhir menang — menempelkan baris "Cookie: ..." baru di akhir
     *  diam-diam membuang cookie akun yang ditempel user (berkas Drive
     *  berbagi-terbatas langsung 403 walau ekstraksi sukses). Pasangan user
     *  dipertahankan dan didahulukan; pasangan hasil ekstraksi hanya
     *  ditambahkan bila namanya belum ada. Murni. */
    internal fun mergeCookiesIntoHeaders(existing: String, cookies: String): String {
        val extra = cookies.trim()
        if (extra.isEmpty()) return existing
        val userPairs = mutableListOf<String>()
        val others = mutableListOf<String>()
        for (rawLine in existing.split("\n")) {
            val line = rawLine.trim()
            if (line.isEmpty()) continue
            val key = line.substringBefore(':', "").trim()
            if (key.equals("Cookie", ignoreCase = true)) {
                userPairs += line.substringAfter(':', "").split(';')
                    .map { it.trim() }.filter { it.isNotEmpty() }
            } else {
                others += line
            }
        }
        val merged = mergeCookiePairs(userPairs.joinToString("; "), extra)
        // Kurung wajib: tanpa itu '+' pada List menempelkan "Cookie: "
        // dan isi gabungan sebagai DUA elemen terpisah, menghasilkan
        // baris "Cookie: " kosong + baris sampah tanpa nama header
        // (uji mergeCookiesIntoHeaders menangkapnya di CI).
        return (others + ("Cookie: " + merged)).joinToString("\n")
    }

    /** Gabung dua nilai Cookie: pasangan [user] dipertahankan dan
     *  didahulukan; pasangan [extra] hanya ditambahkan bila namanya belum
     *  ada. Murni. */
    internal fun mergeCookiePairs(user: String, extra: String): String {
        val merged = user.split(';').map { it.trim() }
            .filter { it.isNotEmpty() }.toMutableList()
        val seen = merged
            .map { it.substringBefore('=').trim().lowercase(Locale.ROOT) }
            .toMutableSet()
        for (pair in extra.split(';').map { it.trim() }.filter { it.isNotEmpty() }) {
            val name = pair.substringBefore('=').trim().lowercase(Locale.ROOT)
            if (seen.add(name)) merged += pair
        }
        return merged.joinToString("; ")
    }

    /** Cookie yang dipasang [setHost] boleh dikirim ke [targetHost] hanya
     *  bila host sama atau berbagi domain TEREGISTRASI yang sama
     *  (mis. drive.google.com -> drive.usercontent.google.com).
     *  Tanpa scoping ini jar fetchDrive mengirim cookie ke host redirect
     *  mana pun (#239). Murni. */
    internal fun cookieJarVisible(setHost: String, targetHost: String): Boolean {
        val s = setHost.trim().lowercase(Locale.ROOT)
        val t = targetHost.trim().lowercase(Locale.ROOT)
        if (s.isEmpty() || t.isEmpty()) return false
        if (s == t) return true
        // Alamat IP tidak punya domain induk (#285): dua IP berbeda yang
        // dua oktet terakhirnya sama (10.0.0.7 vs 192.168.0.7) dulu
        // dianggap satu induk oleh potongan dua label.
        if (isIpv4Literal(s) || isIpv4Literal(t)) return false
        val sd = registrableDomainOf(s)
        val td = registrableDomainOf(t)
        return sd == td && sd.contains('.')
    }

    /** Akhiran publik dua lapis yang umum: di bawah akhiran ini domain
     *  teregistrasi adalah TIGA label (a.co.uk dan b.co.uk adalah dua
     *  pemilik berbeda; example.co.uk satu pemilik). Daftar kurasi
     *  sebagian, bukan daftar suffix publik lengkap. */
    private val TWO_LEVEL_PUBLIC_SUFFIXES = setOf(
        "co.uk", "org.uk", "ac.uk", "gov.uk",
        "com.au", "net.au", "org.au",
        "co.id", "or.id", "ac.id", "go.id", "web.id",
        "co.in", "co.jp", "co.kr", "com.br", "com.cn", "com.tw",
        "co.nz", "co.za", "github.io",
        // Akhiran multi-penyewa (#322): tiap penyewa adalah subdomain
        // langsung dan pemilik berbeda, jadi domain teregistrasi
        // adalah tiga label (a.blogspot.com vs b.blogspot.com tidak
        // boleh saling melihat cookie).
        "blogspot.com", "wordpress.com", "tumblr.com", "livejournal.com",
        "gitlab.io", "netlify.app", "vercel.app", "pages.dev",
        "web.app", "firebaseapp.com", "azurewebsites.net",
        "herokuapp.com", "glitch.me", "surge.sh", "onrender.com",
        "fly.dev", "pythonanywhere.com"
    )

    /** Domain teregistrasi [host]: dua label terakhir, atau tiga bila
     *  dua label terakhirnya akhiran publik dua lapis (#285). */
    private fun registrableDomainOf(host: String): String {
        val labels = host.split('.')
        val last2 = labels.takeLast(2).joinToString(".")
        return if (last2 in TWO_LEVEL_PUBLIC_SUFFIXES) {
            labels.takeLast(3).joinToString(".")
        } else {
            last2
        }
    }

    private fun isIpv4Literal(host: String): Boolean {
        val parts = host.split('.')
        return parts.size == 4 && parts.all { part ->
            part.isNotEmpty() && part.length <= 3 &&
                part.all { c -> c.isDigit() } && part.toInt() <= 255
        }
    }

    fun isSocialMediaUrl(url: String): Boolean {
        val lower = url.lowercase(Locale.ROOT)
        // Jalur cepat: URL file langsung (kasus umum) gugur tanpa regex.
        if (!lower.contains("tiktok.com") && !lower.contains("instagram.com") &&
            !lower.contains("twitter.com") && !lower.contains("x.com/") &&
            !lower.contains("youtube.com") && !lower.contains("youtu.be") &&
            !lower.contains("instagr.am") && !lower.contains("xvideos.com") &&
            !lower.contains("xnxx.com") && !lower.contains("xnxxvideos.me") &&
            !lower.contains("pornhub.com") && !lower.contains("pornhubpremium.com") &&
            !lower.contains("hentaihaven") && !lower.contains("drive.google.com") &&
            !lower.contains("facebook.com") && !lower.contains("fb.watch")
        ) return false
        if (lower.contains("cdninstagram.com") || lower.contains("cdninstagram")) return false
        if (lower.contains("tiktokcdn.com") || lower.contains("tiktokcdn")) return false
        return TT_HOST_RE.containsMatchIn(lower) ||
                IG_HOST_RE.containsMatchIn(lower) ||
                TW_HOST_RE.containsMatchIn(lower) ||
                X_URL_RE.containsMatchIn(lower) ||
                YT_HOST_RE.containsMatchIn(lower) ||
                XV_HOST_RE.containsMatchIn(lower) ||
                XN_HOST_RE.containsMatchIn(lower) ||
                PH_HOST_RE.containsMatchIn(lower) ||
                HH_HOST_RE.containsMatchIn(lower) ||
                GD_HOST_RE.containsMatchIn(lower) ||
                FB_HOST_RE.containsMatchIn(lower)
    }

    /** True bila URL adalah halaman HentaiHaven (butuh ekstraksi WebView). */
    fun isHentaiHavenUrl(url: String): Boolean =
        HH_HOST_RE.containsMatchIn(url.lowercase(Locale.ROOT))

    /** Ekstrak URL terbaik (satu opsi). */
    suspend fun extract(url: String, headers: String = ""): Result? = withContext(Dispatchers.IO) {
        // Batas keras total seperti extractAll: rantai fallback (page/API
        // pihak ketiga) masing-masing punya timeout, tapi jumlahnya bisa
        // menahan slot worker/retry puluhan detik tanpa cap ini.
        withTimeoutOrNull(EXTRACT_TOTAL_TIMEOUT_MS) {
        try {
            // Header user (Cookie/Referer dari WebExtract) wajib ikut saat
            // fetch halaman first-party; tanpa ini re-extract situs ber-cookie
            // tak pernah mereproduksi hasil pertama. TikTok/Twitter hanya
            // memakai API pihak ketiga sehingga tak menerima header user
            // (cookie tak boleh bocor ke host lain).
            val user = parseUserHeaders(headers)
            val lower = url.lowercase(Locale.ROOT)
            when {
                TT_HOST_RE.containsMatchIn(lower) -> extractTikTok(url)
                IG_BROAD_HOST_RE.containsMatchIn(lower) -> extractInstagram(url, user)
                TW_HOST_RE.containsMatchIn(lower) || X_URL_RE.containsMatchIn(lower) ->
                    extractTwitter(url)
                YT_HOST_RE.containsMatchIn(lower) -> extractYouTube(url, user)
                XV_HOST_RE.containsMatchIn(lower) -> extractXVideos(url, user)
                XN_HOST_RE.containsMatchIn(lower) -> extractXnxx(url, user)
                PH_HOST_RE.containsMatchIn(lower) -> extractPornhub(url, user)
                HH_HOST_RE.containsMatchIn(lower) -> extractHentaiHaven(url, user)
                GD_HOST_RE.containsMatchIn(lower) -> extractGoogleDrive(url, user)
                FB_HOST_RE.containsMatchIn(lower) -> extractFacebook(url, user)
                else -> null
            }
        } catch (e: Exception) { if (e is kotlinx.coroutines.CancellationException) throw e; null }
        }
    }

    /** Ekstrak semua opsi resolusi yang tersedia. */
    suspend fun extractAll(url: String, headers: String = ""): List<Result> = withContext(Dispatchers.IO) {
        // Batas keras total ekstraksi: rantai fallback (piped/invidious/embed)
        // punya timeout sendiri, tapi jangan sampai menahan thread server atau
        // dialog probe terlalu lama bila semua mirror lambat/gagal.
        withTimeoutOrNull(EXTRACT_TOTAL_TIMEOUT_MS) {
            try {
                val user = parseUserHeaders(headers)
                val lower = url.lowercase(Locale.ROOT)
                when {
                    TT_HOST_RE.containsMatchIn(lower) -> extractAllTikTok(url)
                    IG_BROAD_HOST_RE.containsMatchIn(lower) -> extractAllInstagram(url, user)
                    TW_HOST_RE.containsMatchIn(lower) || X_URL_RE.containsMatchIn(lower) ->
                        extractAllTwitter(url)
                    YT_HOST_RE.containsMatchIn(lower) -> extractAllYouTube(url, user)
                    XV_HOST_RE.containsMatchIn(lower) -> extractAllXVideos(url, user)
                    XN_HOST_RE.containsMatchIn(lower) -> extractAllXnxx(url, user)
                    PH_HOST_RE.containsMatchIn(lower) -> extractAllPornhub(url, user)
                    HH_HOST_RE.containsMatchIn(lower) -> extractAllHentaiHaven(url, user)
                    GD_HOST_RE.containsMatchIn(lower) -> extractAllGoogleDrive(url, user)
                    FB_HOST_RE.containsMatchIn(lower) -> extractAllFacebook(url, user)
                    else -> emptyList()
                }
            } catch (e: Exception) { if (e is kotlinx.coroutines.CancellationException) throw e; emptyList() }
        } ?: emptyList()
    }

    // ── TikTok ───────────────────────────────────────────────────────────

    private fun extractTikTok(url: String): Result? {
        val options = extractAllTikTok(url)
        return options.firstOrNull { it.quality.contains("HD", ignoreCase = true) }
            ?: options.firstOrNull()
    }

    private fun extractAllTikTok(url: String): List<Result> {
        val encoded = URLEncoder.encode(url, "UTF-8")
        // tikwm.com punya beberapa host; coba www dulu lalu tanpa www sebagai cadangan
        // (salah satu bisa diblokir sesaat oleh ISP atau throttling API).
        val hosts = listOf("https://www.tikwm.com", "https://tikwm.com")
        // Cap tahap (#266): dua host x rantai redirect helper bisa menduduki
        // thread IO jauh melewati batas total bila keduanya menggantung.
        val obj = runStageCapped(20_000L) {
            var found: JSONObject? = null
            for (host in hosts) {
                val json = httpGet("$host/api/?url=$encoded&hd=1") ?: continue
                val parsed = runCatching { JSONObject(json) }.getOrNull() ?: continue
                if (parsed.optInt("code", -1) == 0) {
                    found = parsed
                    break
                }
            }
            found
        }
        val data = obj?.optJSONObject("data") ?: return emptyList()
        val title = data.optString("title", "")
        val author = try {
            data.optJSONObject("author")?.optString("unique_id", "")
        } catch (_: Exception) { "" }
        val id = data.optString("id", "")
        val namePrefix = "TikTok_${author}_$id".trim('_')
        val options = mutableListOf<Result>()

        val hdUrl = data.optString("hdplay", "")
        if (hdUrl.startsWith("http")) {
            options.add(Result(hdUrl, "$namePrefix.mp4", title, "HD", MIME_MP4))
        }
        val sdUrl = data.optString("play", "")
        if (sdUrl.startsWith("http")) {
            options.add(Result(sdUrl, "$namePrefix.mp4", title, "SD", MIME_MP4))
        }
        val wmUrl = data.optString("wmplay", "")
        if (wmUrl.startsWith("http") && wmUrl != sdUrl) {
            options.add(Result(wmUrl, "${namePrefix}_wm.mp4", title, "SD (watermark)", MIME_MP4))
        }
        return options
    }

    // ── Instagram ────────────────────────────────────────────────────────

    private val IG_HEADERS = mapOf(
        "User-Agent" to "Mozilla/5.0 (compatible; Googlebot/2.1; +http://www.google.com/bot.html)",
        "Accept" to "text/html"
    )

    private fun extractInstagram(url: String, user: Map<String, String> = emptyMap()): Result? {
        val options = extractAllInstagram(url, user)
        return options.firstOrNull()
    }

    private fun extractAllInstagram(url: String, user: Map<String, String> = emptyMap()): List<Result> {
        val shortcode = IG_SHORTCODE_RE.find(url)
            ?.groupValues?.get(1) ?: return emptyList()
        val options = mutableListOf<Result>()

        // Strategi 1: embed page — data carousel presisi (hanya foto/video milik post ini)
        // Format contextJSON":"{...}" berisi edge_sidecar_to_children lengkap.
        val embedResult = httpGetWithCookies(
            "https://www.instagram.com/p/$shortcode/embed/captioned/", IG_HEADERS + user
        )
        val embedHtml = embedResult?.body
        val igCookies = embedResult?.cookies.orEmpty()
        if (embedHtml != null && embedHtml.length > 1000) {
            val media = extractContextJson(embedHtml)
            if (media != null) {
                val all = extractAllFromMedia(media, shortcode, igCookies)
                if (all.isNotEmpty()) {
                    options.addAll(all)
                }
            }
        }

        // Strategi 2: halaman utama via Googlebot — hanya bila embed gagal
        if (options.isEmpty()) {
            val httpResult = httpGetWithCookies("https://www.instagram.com/p/$shortcode/", IG_HEADERS + user)
            val pageCookies = httpResult?.cookies.orEmpty()
            val pageHtml = httpResult?.body
            if (pageHtml != null && pageHtml.length > 1000) {
                val displayUrls = extractAllDisplayUrlsFromPage(pageHtml)
                displayUrls.forEachIndexed { idx, imgUrl ->
                    options.add(Result(imgUrl, "Instagram_${shortcode}_${idx+1}.jpg",
                        "Instagram $shortcode", "Photo ${idx+1}", "image/jpeg", cookies = pageCookies))
                }
                val videoUrl = extractVideoFromPage(pageHtml)
                if (videoUrl != null) {
                    options.add(0, Result(videoUrl, "Instagram_${shortcode}.mp4",
                        "Instagram $shortcode", "Video", MIME_MP4, cookies = pageCookies))
                }
            }
        }

        // Dukungan img_index dari URL: pilih item tertentu di carousel
        val imgIndex = IG_IMG_INDEX_RE.find(url)?.groupValues?.get(1)?.toIntOrNull()
        if (imgIndex != null && imgIndex > 0 && imgIndex <= options.size) {
            val selected = options[imgIndex - 1]
            return listOf(selected)
        }
        return options
    }

    private fun extractAllDisplayUrlsFromPage(html: String): List<String> {
        // Semua URL gambar dari CDN Instagram (scontent*.cdninstagram.com).
        // Path CDN bervariasi (t39.30808-6, t51.82787-15, t51.82787-19, dst),
        // jadi jangan dikunci ke satu pola path. Buang profil pic (t51.2885-*),
        // dedup per ID file, lalu utamakan versi resolusi penuh.
        val imgRegex = IG_IMG_URL_RE
        val decoded = mutableListOf<String>()
        imgRegex.findAll(html).forEach { match ->
            val raw = unescapePlayer(match.value)
            if (raw.startsWith("http") && !raw.contains("/t51.2885-")) {
                decoded.add(raw)
            }
        }
        if (decoded.isEmpty()) {
            // Strategi cadangan: pola CDN lain yang belum tertangkap di atas
            val fallbackRegex = IG_IMG_URL_FALLBACK_RE
            fallbackRegex.findAll(html).forEach { match ->
                val raw = unescapePlayer(match.value)
                if (raw.startsWith("http") && !raw.contains("/t51.2885-")) {
                    decoded.add(raw)
                }
            }
        }
        // Dedup per ID file (contoh: 774314790_18387324052161_1234), utamakan
        // URL tanpa marker thumbnail kecil di query (s150x150 / s640x640).
        val byFileId = linkedMapOf<String, String>()
        val idRegex = IG_FILE_ID_RE
        decoded.forEach { url ->
            val fid = idRegex.find(url)?.groupValues?.get(1) ?: url
            val existing = byFileId[fid]
            if (existing == null ||
                (existing.contains("s640x640") && !url.contains("s640x640")) ||
                (existing.contains("s150x150") && !url.contains("s150x150"))
            ) {
                byFileId[fid] = url
            }
        }
        return byFileId.values.take(20).toList()
    }

    private fun extractVideoFromPage(html: String): String? {
        val idx = html.indexOf("video_versions")
        if (idx < 0) return null
        val raw = html.substring(idx, minOf(idx + 5000, html.length))
        val unescaped = unescapePlayer(raw)
        val videoRegex = IG_VIDEO_URL_RE
        val match = videoRegex.find(unescaped) ?: return null
        // Pass kedua via helper (semantik identik dengan rantai lama:
        // amankan sisa escape ganda seperti `&amp;amp;` di URL hasil match).
        return unescapePlayer(match.groupValues[1])
    }

    /** Pindai isi string JS ter-escape mulai dari [from] (tepat sesudah
     *  kutip pembuka) sampai kutip penutup TAK ter-escape pertama; null
     *  bila string tak berujung. Pola yang sama dengan pemindai format 1
     *  di extractContextJson — regex tangkap seperti (.+?)" tidak bisa
     *  dipakai di sini karena berhenti di kutip ter-escape pertama
     *  (issue #635). internal agar bisa di-unit-test. */
    internal fun scanEscapedJsString(html: String, from: Int): String? {
        var i = from
        while (i < html.length) {
            val c = html[i]
            if (c == '\\' && i + 1 < html.length) { i += 2; continue }
            if (c == '"') return html.substring(from, i)
            i++
        }
        return null
    }

    // internal agar bisa di-unit-test (pola yang sama dengan scanEscapedJsString).
    internal fun extractContextJson(html: String): JSONObject? {
        // Format 1 (embed page): contextJSON":"{escaped JSON}"
        val embedKey = "contextJSON\":\""
        // Semua kemunculan dicoba (issue #666): kemunculan pertama yang
        // rusak/decoy tidak lagi membatalkan seluruh format 1 — pola yang
        // sama dengan iterasi findAll format 2/3 pada issue #652.
        var searchFrom = 0
        while (true) {
            val embedIdx = html.indexOf(embedKey, searchFrom)
            if (embedIdx < 0) break
            val start = embedIdx + embedKey.length
            var i = start
            while (i < html.length) {
                val c = html[i]
                if (c == '\\' && i + 1 < html.length) { i += 2; continue }
                if (c == '"') break
                i++
            }
            if (i > start) {
                val token = unescapeJsToken(html.substring(start, i)).replace("\\\\", "\\")
                try {
                    val obj = JSONObject(token)
                    val gql = obj.optJSONObject("gql_data")
                    val media = gql?.optJSONObject("shortcode_media")
                    if (media != null) return media
                    val context = obj.optJSONObject("context")
                    val ctxMedia = context?.optJSONObject("media")
                    if (ctxMedia != null) return ctxMedia
                } catch (_: Exception) { /* lanjut */ } // audit-ignore: file_scoped_catch_all, kotlin_empty_catch (kandidat media tak ter-parse: lanjut ke kandidat berikut)
            }
            // Maju melewati kemunculan ini; token kosong (i == start)
            // pun tetap harus memajukan kursor agar loop berhenti.
            searchFrom = if (i > start) i else start + 1
        }
        // Format 2: contextJSON = "..." (JS assignment)
        // Format 3: "token": "..."
        for (pattern in listOf(
            IG_CONTEXT_JSON_RE,
            IG_TOKEN_RE,
        )) {
            // SEMUA kecocokan per pola dicoba (issue #652): field token /
            // contextJSON polos yang muncul lebih dulu di halaman tak
            // boleh membuat token asli di kecocokan berikutnya tak
            // terjangkau — dulu hanya kecocokan pertama yang dicoba.
            for (match in pattern.findAll(html)) {
                // Regex hanya MELACAK kutip pembuka; isi token dipindai
                // sadar-escape (issue #635) — tangkapan regex berhenti di
                // kutip ter-escape pertama dan tak pernah lolos parse JSON.
                val raw = scanEscapedJsString(html, match.range.last + 1) ?: continue
                val token = unescapeJsToken(raw)
                try {
                    val obj = JSONObject(token)
                    val gql = obj.optJSONObject("gql_data")
                    if (gql != null) {
                        val media = gql.optJSONObject("shortcode_media")
                        if (media != null) return media
                    }
                    val context = obj.optJSONObject("context")
                    if (context != null) {
                        val media = context.optJSONObject("media")
                        if (media != null) return media
                    }
                } catch (_: Exception) { /* lanjut */ } // audit-ignore: file_scoped_catch_all, kotlin_empty_catch (kandidat media tak ter-parse: lanjut ke kandidat berikut)
            }
        }
        return null
    }

    private fun extractAllFromMedia(media: JSONObject, shortcode: String, cookies: String): List<Result> {
        val results = mutableListOf<Result>()
        // Cek carousel dulu
        val sidecar = media.optJSONObject("edge_sidecar_to_children")
        val edges = sidecar?.optJSONArray("edges")
        if (edges != null && edges.length() > 0) {
            for (i in 0 until edges.length()) {
                val node = edges.optJSONObject(i)?.optJSONObject("node") ?: continue
                if (node.optBoolean("is_video", false)) {
                    val cv = node.optString("video_url", "")
                    if (cv.startsWith("http")) {
                        // Berindeks seperti node foto di loop yang sama:
                        // carousel berisi >=2 video sebelumnya menghasilkan
                        // nama + label yang persis sama (issue #432).
                        results.add(Result(cv, "Instagram_${shortcode}_${i+1}.mp4", "Instagram $shortcode", "Video ${i+1}", MIME_MP4, cookies = cookies))
                    }
                } else {
                    val img = node.optString("display_url", "")
                    if (img.startsWith("http")) {
                        results.add(Result(img, "Instagram_${shortcode}_${i+1}.jpg", "Instagram $shortcode", "Photo ${i+1}", "image/jpeg", cookies = cookies))
                    }
                }
            }
        }
        if (results.isEmpty()) {
            // Single video atau foto
            val videoUrl = media.optString("video_url", "")
            if (videoUrl.startsWith("http")) {
                results.add(Result(videoUrl, "Instagram_${shortcode}.mp4", "Instagram $shortcode", "Video", MIME_MP4, cookies = cookies))
            }
            val displayUrl = media.optString("display_url", "")
            if (displayUrl.startsWith("http") && results.isEmpty()) {
                results.add(Result(displayUrl, "Instagram_${shortcode}.jpg", "Instagram $shortcode", "Photo", "image/jpeg", cookies = cookies))
            }
        }
        return results
    }

    // ── YouTube ────────────────────────────────────────────────────────

    private fun extractYouTube(url: String, user: Map<String, String> = emptyMap()): Result? {
        val options = extractAllYouTube(url, user)
        return options.firstOrNull()
    }

    private fun extractAllYouTube(url: String, user: Map<String, String> = emptyMap()): List<Result> {
        val videoId = extractYouTubeId(url) ?: return emptyList()

        // Strategi 1: VISIONOS player API — URL stream tanpa n-signature.
        // URL adaptif/HLS dari client ini bisa langsung di-download (tidak 403).
        // Kedua strategi dibungkus cap tahap (#266): fetch pemblokir tidak
        // patuh withTimeoutOrNull, jadi tanpa cap satu strategi bisa
        // menduduki thread IO jauh melewati batas total 60 detik.
        val vision = runStageCapped(24_000L) { extractYouTubeViaVisionos(videoId, user) }
        if (vision != null) {
            return listOf(vision)
        }

        // Strategi 2: halaman WEB (ytInitialPlayerResponse) + fallback Piped/Invidious.
        return runStageCapped(30_000L) { extractYouTubeFromPage(url, videoId, user) }
            ?: emptyList()
    }

    /** Ekstrak URL non-HLS dari YouTube: halaman WEB langsung + fallback Piped/Invidious.
     *  Dipanggil bila VISIONOS HLS gagal (media playlist butuh pot token). */
    suspend fun extractNonHlsYouTube(url: String, headers: String = ""): Result? = withContext(Dispatchers.IO) {
        try {
            val videoId = extractYouTubeId(url) ?: return@withContext null
            val user = parseUserHeaders(headers)
            // Strategi 0: coba adaptiveFormats dari VISIONOS (URL langsung tanpa HLS)
            // Cap tahap juga di sini (#266): jalur ini tidak punya
            // withTimeoutOrNull sama sekali, jadi dulu benar-benar tak berbatas.
            val visionAdaptive = runStageCapped(24_000L) {
                extractYouTubeViaVisionosAdaptive(videoId, user)
            }
            if (visionAdaptive != null) return@withContext visionAdaptive
            // Strategi 1: halaman WEB + Piped/Invidious/Cobalt
            val results = runStageCapped(30_000L) {
                extractYouTubeFromPage(url, videoId, user)
            } ?: emptyList()
            results.firstOrNull { it.directUrl.startsWith("http") }
        } catch (e: Exception) { if (e is kotlinx.coroutines.CancellationException) throw e; null }
    }

    private fun extractYouTubeFromPage(
        url: String,
        videoId: String,
        user: Map<String, String> = emptyMap()
    ): List<Result> {
        val httpResult = httpGetWithCookies(
            "https://www.youtube.com/watch?v=$videoId",
            mapOf(
                "User-Agent" to YT_PAGE_UA,
                "Accept-Language" to YT_LANG
            ) + user,
            timeoutMs = PAGE_TIMEOUT_MS
        ) ?: return emptyList()
        val pageHtml = httpResult.body
        val ytCookies = httpResult.cookies

        val playerJson = extractBalancedPlayerResponse(pageHtml)
            ?: YT_PLAYER_RESP_RE.find(pageHtml)?.groupValues?.get(1)
            ?: YT_PLAYER_RESP_LAX_RE.find(pageHtml)?.groupValues?.get(1)
            ?: run {
                return emptyList()
            }

        try {
            val data = JSONObject(playerJson)
            // Judul TIDAK ada di level atas player response — ia di
            // videoDetails (atau microformat); membaca "title" di level
            // atas selalu jatuh ke nama generik (issue #433).
            val title = data.optJSONObject("videoDetails")?.optString("title")?.takeIf { it.isNotBlank() }
                ?: data.optJSONObject("microformat")?.optJSONObject("playerMicroformatRenderer")
                    ?.optJSONObject("title")?.optString("simpleText")?.takeIf { it.isNotBlank() }
                ?: "YouTube_$videoId"
            val streamingData = data.optJSONObject("streamingData") ?: return emptyList()
            // Baca both formats (muxed) AND adaptiveFormats (separate video/audio)
            val muxedFormats = streamingData.optJSONArray("formats")
            val adaptiveFormats = streamingData.optJSONArray("adaptiveFormats")
            val allFormats = mutableListOf<JSONObject>()
            muxedFormats?.let { for (i in 0 until it.length()) it.optJSONObject(i)?.let { f -> allFormats.add(f) } }
            if (allFormats.isEmpty() && adaptiveFormats == null) return emptyList()
            val options = mutableListOf<Result>()

            for (fmt in allFormats) {
                val videoUrl = fmt.optString("url", "")
                if (videoUrl.startsWith("http")) {
                    val quality = fmt.optString("qualityLabel", "Unknown")
                    val mimeType = fmt.optString("mimeType", MIME_MP4)
                    val ext = if (mimeType.contains("webm")) "webm" else "mp4"
                    val safeName = sanitizeFileName(title)
                    options.add(Result(videoUrl, "${safeName}.$ext", title, quality, mimeType, cookies = ytCookies))
                }
            }
            // Entri adaptiveFormats dinilai terpisah (issue #537):
            // format video-only di sana dulu dicampur setara dengan
            // formats progresif sehingga ditawarkan sebagai unduhan
            // utuh yang bisu. Kini video-only hanya masuk sebagai
            // pasangan adaptif dengan audio terbaik dari daftar yang
            // sama; tanpa pasangan, ia dilewati. Entri non-video-only
            // (termasuk audio) mengikuti perilaku lama.
            if (adaptiveFormats != null) {
                val audioAd = bestAdaptivePair(streamingData).second
                for (i in 0 until adaptiveFormats.length()) {
                    val fmt = adaptiveFormats.optJSONObject(i) ?: continue
                    val videoUrl = fmt.optString("url", "")
                    if (!videoUrl.startsWith("http")) continue
                    val quality = fmt.optString("qualityLabel", "Unknown")
                    val mimeType = fmt.optString("mimeType", MIME_MP4)
                    val ext = if (mimeType.contains("webm")) "webm" else "mp4"
                    val safeName = sanitizeFileName(title)
                    if (isVideoOnlyMime(mimeType)) {
                        if (audioAd.isEmpty()) continue
                        options.add(
                            Result(
                                videoUrl, "${safeName}.$ext", title, quality, mimeType,
                                cookies = ytCookies, videoUrl = videoUrl, audioUrl = audioAd
                            )
                        )
                    } else {
                        options.add(Result(videoUrl, "${safeName}.$ext", title, quality, mimeType, cookies = ytCookies))
                    }
                }
            }
            // If the page-derived URLs are all n-transformed (403 on the CDN),
            // fall back to Piped instances which return pre-resolved download URLs.
            // Probe SEMUA opsi, bukan hanya yang pertama (issue #805):
            // opsi ber-URL 403 disaring keluar; fallback hanya berjalan
            // bila tak ada opsi sehat tersisa.
            val alive = filterForbidden(options)
            if (alive.isEmpty()) {
                val piped = extractYouTubeViaPiped(videoId)
                if (piped.isNotEmpty()) {
                    return piped
                }
                val invidious = runCatching { extractYouTubeViaInvidious(videoId) }.getOrNull()
                if (invidious != null) {
                    return listOf(invidious)
                }
                val cobalt = runCatching { extractYouTubeViaCobalt(videoId) }.getOrNull()
                if (cobalt != null) {
                    return listOf(cobalt)
                }
                // Semua fallback kosong: kembalikan opsi halaman apa
                // adanya sebagai upaya terakhir (perilaku lama).
                return options
            }
            return alive
        } catch (_: Exception) { // audit-ignore: file_scoped_catch_all, kotlin_empty_catch (gagal parse halaman YouTube: biarkan fallback lain lanjut)
            // Gagal mem-parse halaman/response YouTube — biarkan fallback lain lanjut.
        }
        return emptyList()
    }

    /** Hasil mentah player VISIONOS: data streaming terparse + judul
     *  + cookie halaman sumber (untuk ditempel ke Result pemanggil). */
    private data class VisionosPlayer(
        val streamingData: JSONObject?,
        val title: String,
        val cookies: String
    )

    /** Ambil visitorData dari [pageUrl] lalu POST ke player API
     *  VISIONOS. Dipakai bersama kedua strategi Visionos (#281): blok
     *  ini dulu tersalin dua kali dan sudah berbeda — versi adaptive
     *  tidak meneruskan header user ke pengambilan halaman. */
    private fun fetchVisionosPlayer(
        pageUrl: String,
        videoId: String,
        user: Map<String, String>
    ): VisionosPlayer? {
        // Butuh visitorData + cookies dari halaman agar API tidak LOGIN_REQUIRED.
        val page = httpGetWithCookies(
            pageUrl,
            mapOf(
                "User-Agent" to YT_UA,
                "Accept-Language" to YT_LANG
            ) + user,
            timeoutMs = PAGE_TIMEOUT_MS
        ) ?: return null
        val visitor = YT_VISITOR_DATA_RE.find(page.body)?.groupValues?.get(1)
            ?: YT_VISITOR_DATA_LOW_RE.find(page.body)?.groupValues?.get(1)
            ?: return null

        val body = buildString {
            append("{\"context\":{\"client\":{")
            append("\"clientName\":\"VISIONOS\",")
            append("\"clientVersion\":\"1.02\",")
            append("\"deviceMake\":\"Apple\",")
            append("\"deviceModel\":\"RealityDevice17,1\",")
            append("\"userAgent\":\"$VISIONOS_UA\",")
            append("\"osName\":\"visionOS\",")
            append("\"osVersion\":\"26.5.23O471\",")
            append("\"hl\":\"en\",")
            append("\"visitorData\":\"$visitor\"")
            append("}},\"videoId\":\"$videoId\"}")
        }
        val json = httpPostJson(
            "https://www.youtube.com/youtubei/v1/player",
            body,
            (mapOf(
                "User-Agent" to YT_UA,
                "Origin" to "https://www.youtube.com",
                "Referer" to "https://www.youtube.com/",
                "X-Goog-Visitor-Id" to visitor,
                "X-YouTube-Client-Name" to "101",
                "X-YouTube-Client-Version" to "1.02"
            ) + user),
            timeoutMs = PAGE_TIMEOUT_MS
        ) ?: return null
        return runCatching {
            val obj = JSONObject(json)
            if (obj.optJSONObject("playabilityStatus")?.optString("status") != "OK") return null
            val title = obj.optJSONObject("videoDetails")?.optString("title")
                ?: "YouTube_$videoId"
            VisionosPlayer(obj.optJSONObject("streamingData"), title, page.cookies)
        }.getOrNull()
    }

    /** Strategi VISIONOS: player API mengembalikan URL HLS/adaptif tanpa
     *  n-signature, sehingga bisa langsung di-download (tidak HTTP 403). */
    private fun extractYouTubeViaVisionos(
        videoId: String,
        user: Map<String, String> = emptyMap()
    ): Result? {
        val player = fetchVisionosPlayer(
            "https://www.youtube.com/shorts/$videoId", videoId, user
        ) ?: return null
        return runCatching {
            val title = player.title
            val streamingData = player.streamingData
            val safeName = sanitizeFileName(title)
            // Strategi 1: HLS manifest — cobalah dulu untuk kualitas terbaik.
            val hls = streamingData?.optString("hlsManifestUrl")
            if (!hls.isNullOrEmpty()) {
                // Audio CDN HLS sering di-404 YouTube; siapkan url audio/video dari
                // adaptiveFormats sebagai cadangan agar hasil akhir tetap bersuara.
                val (videoAd, audioAd) = bestAdaptivePair(streamingData)
                return@runCatching Result(
                    hls, "YouTube_$safeName.ts", title, "HLS", "application/x-mpegURL",
                    cookies = player.cookies, isHls = true,
                    videoUrl = videoAd, audioUrl = audioAd
                )
            }
            // Strategi 2: adaptiveFormats langsung (tanpa HLS) — TV client
            // biasanya mengembalikan URL langsung tanpa n-signature.
            val adaptive = streamingData?.optJSONArray("adaptiveFormats")
            if (adaptive != null && adaptive.length() > 0) {
                val (videoAd, audioAd) = bestAdaptivePair(streamingData)
                val best = adaptiveFormatByUrl(adaptive, videoAd)
                if (best != null) {
                    // optString: kunci url kadang absen di respons TV client;
                    // jangan lempar agar fallback HLS/adaptif lain tetap jalan.
                    val url = best.optString("url", "")
                    if (url.isEmpty()) return@runCatching null
                    val mime = best.optString("mimeType", MIME_MP4)
                    val quality = best.optString("qualityLabel", "Unknown")
                    val ext = if (mime.contains("webm")) "webm" else "mp4"
                    return@runCatching Result(
                        url, "YouTube_$safeName.$ext", title, quality, mime,
                        cookies = player.cookies, videoUrl = url, audioUrl = audioAd
                    )
                }
            }
            null
        }.getOrNull()
    }

    /** Strategi VISIONOS adaptive: ambil URL langsung dari adaptiveFormats
     *  tanpa lewat HLS (menghindari media playlist 404). */
    private fun extractYouTubeViaVisionosAdaptive(
        videoId: String,
        user: Map<String, String> = emptyMap()
    ): Result? {
        val player = fetchVisionosPlayer(
            "https://www.youtube.com/watch?v=$videoId", videoId, user
        ) ?: return null
        return runCatching {
            val title = player.title
            val streamingData = player.streamingData
            val adaptive = streamingData?.optJSONArray("adaptiveFormats") ?: return null
            val safeName = sanitizeFileName(title)
            // Pilih video AVC MP4 bila ada (remux MP4 mulus); WebM hanya
            // cadangan terakhir karena muxer MP4 tidak menerima VP9/opus.
            val (videoAd, audioAd) = bestAdaptivePair(streamingData)
            val best = adaptiveFormatByUrl(adaptive, videoAd) ?: return null
            val url = best.getString("url")
            val mime = best.optString("mimeType", MIME_MP4)
            val quality = best.optString("qualityLabel", "Unknown")
            val ext = if (mime.contains("webm")) "webm" else "mp4"
            Result(
                url, "YouTube_$safeName.$ext", title, quality, mime,
                cookies = player.cookies, videoUrl = url, audioUrl = audioAd
            )
        }.getOrNull()
    }

/** Cari objek format adaptive berdasarkan URL (untuk mengambil mime/quality
 *  dari hasil `bestAdaptivePair`). */
    private fun adaptiveFormatByUrl(adaptive: JSONArray?, url: String): JSONObject? {
        if (adaptive == null || url.isEmpty()) return null
        for (i in 0 until adaptive.length()) {
            val fmt = adaptive.optJSONObject(i) ?: continue
            if (fmt.optString("url", "") == url) return fmt
        }
        return null
    }

/** Pilih pasangan video+audio adaptive terbaik dari streamingData VISIONOS
 *  (URL langsung, tanpa n-transform). Prioritas video: MP4/AVC (bisa diremux
 *  ke MP4 mulus), lalu MP4 lain, lalu WebM. Prioritas audio: MP4/M4A AAC
 *  (itag 140, lalu 139), lalu audio MP4 lain. Audio WebM (opus) dikembalikan
 *  kosong karena muxer MP4 tidak menerima opus. */
    internal fun bestAdaptivePair(streamingData: JSONObject?): Pair<String, String> {
        val adaptive = streamingData?.optJSONArray("adaptiveFormats") ?: return "" to ""
        var videoUrl = ""
        var audioUrl = ""
        var videoRank = Int.MAX_VALUE
        var videoHeight = -1
        var videoBitrate = -1
        var audioRank = Int.MAX_VALUE
        var audioBitrate = -1
        for (i in 0 until adaptive.length()) {
            val fmt = adaptive.optJSONObject(i) ?: continue
            val mime = fmt.optString("mimeType", "")
            val url = fmt.optString("url", "")
            if (!url.startsWith("http")) continue
            when {
                mime.contains("video/") -> {
                    val rank = when {
                        mime.contains("mp4") && mime.contains("avc1") -> 0
                        mime.contains("mp4") -> 1
                        else -> 2
                    }
                    // Seri peringkat codec dimenangkan kualitas tertinggi
                    // (issue #388): sebelumnya format PERTAMA per peringkat
                    // yang menang, dan adaptiveFormats terurut itag menaik
                    // sehingga cadangan VISIONOS berisi 144p walau 720p/1080p
                    // AVC tersedia. Kelas codec tetap utama (kompatibilitas
                    // remux), height lalu bitrate menjadi penentu dalam kelas.
                    val height = fmt.optInt("height", 0)
                    val bitrate = fmt.optInt("bitrate", 0)
                    if (rank < videoRank ||
                        (rank == videoRank && (height > videoHeight ||
                            (height == videoHeight && bitrate > videoBitrate)))
                    ) {
                        videoRank = rank
                        videoHeight = height
                        videoBitrate = bitrate
                        videoUrl = url
                    }
                }
                mime.contains("audio/mp4") -> {
                    val itag = fmt.optInt("itag", 0)
                    val rank = when (itag) {
                        140 -> 0
                        139 -> 1
                        else -> 2
                    }
                    // Seri peringkat itag dimenangkan bitrate tertinggi
                    // (issue #421, menyelaraskan sisi audio dengan #388):
                    // semua audio di luar 139/140 berbagi peringkat 2,
                    // sehingga tanpa tie-break ini pemenang ditentukan
                    // urutan array, bukan kualitas.
                    val bitrate = fmt.optInt("bitrate", 0)
                    if (rank < audioRank || (rank == audioRank && bitrate > audioBitrate)) {
                        audioRank = rank
                        audioBitrate = bitrate
                        audioUrl = url
                    }
                }
            }
        }
        return videoUrl to audioUrl
    }

    /** Saring opsi yang URL langsungnya menjawab 403 (issue #805).
     *  Probe HEAD berjalan paralel di stageExecutor dengan anggaran
     *  tunggu bersama; opsi yang probenya gagal/timeout/tertunda
     *  DIPERTAHANKAN — hanya 403 yang terbukti yang dibuang (sejalan
     *  isUrlForbidden yang getOrDefault false). */
    private fun filterForbidden(options: List<Result>): List<Result> {
        if (options.isEmpty()) return options
        val futures = options.map { opt ->
            try {
                stageExecutor.submit(Callable { isUrlForbidden(opt) })
            } catch (_: Exception) {
                null
            }
        }
        val deadline = System.nanoTime() + FORBIDDEN_PROBE_BUDGET_MS * 1_000_000L
        return options.filterIndexed { i, _ ->
            val f = futures[i] ?: return@filterIndexed true
            val remaining = deadline - System.nanoTime()
            if (remaining <= 0) return@filterIndexed true
            try {
                !f.get(remaining, TimeUnit.NANOSECONDS)
            } catch (_: Exception) {
                true
            }
        }
    }

    private fun isUrlForbidden(item: Result): Boolean {
        return runCatching {
            val conn = openTlsConn(item.directUrl)
            try {
                conn.requestMethod = "HEAD"
                conn.connectTimeout = 6000
                conn.readTimeout = 6000
                // Jangan ikuti redirect otomatis: Cookie hanya untuk host asal,
                // redirect manual di sini tidak diteruskan agar tak bocor lintas origin.
                conn.instanceFollowRedirects = false
                conn.setRequestProperty("User-Agent",
                    YT_UA)
                conn.setRequestProperty("Referer", "https://www.youtube.com/")
                if (item.cookies.isNotEmpty()) conn.setRequestProperty("Cookie", item.cookies)
                conn.responseCode == 403
            } finally { conn.disconnect() }
        }.getOrDefault(false)
    }

    private val PIPED_INSTANCES = listOf(
        "pipedapi.adminforge.de/streams/",
        "pipedapi.kavin.rocks/streams/",
        "pipedapi.leptons.xyz/streams/",
        "pipedapi.video.founderweb.com/streams/",
        "pipedapi.in.projectsegfau.lt/streams/",
        "api.piped.yt/streams/"
    )

    /** URL audio terbaik dari audioStreams Piped untuk dipasangkan
     *  ke stream video-only (issue #537): hanya keluarga MP4/M4A yang
     *  diterima muxer (opus/WebM ditolak, sejajar bestAdaptivePair);
     *  di dalam keluarga itu bitrate tertinggi menang. String kosong
     *  = tidak ada pasangan yang bisa dimux. */
    internal fun bestPipedAudioUrl(audioStreams: JSONArray?): String {
        if (audioStreams == null) return ""
        var best = ""
        var bestBitrate = -1
        for (i in 0 until audioStreams.length()) {
            val a = audioStreams.optJSONObject(i) ?: continue
            val u = a.optString("url", "")
            if (!u.startsWith("http")) continue
            val mime = a.optString("mimeType", "")
            val codec = a.optString("codec", "")
            if (!mime.contains("mp4") && !codec.startsWith("mp4a")) continue
            val br = a.optInt("bitrate", 0)
            if (br > bestBitrate) {
                bestBitrate = br
                best = u
            }
        }
        return best
    }

    /** Format video tanpa komponen audio pada daftar codecs
     *  mimeType-nya = video-only (issue #537). mimeType tanpa daftar
     *  codecs tidak ditebak (false: perlakukan sebagai utuh). */
    internal fun isVideoOnlyMime(mimeType: String): Boolean {
        if (!mimeType.startsWith("video/")) return false
        val codecs = mimeType.substringAfter("codecs=", "").trim().trim('"')
        if (codecs.isEmpty()) return false
        return listOf("mp4a", "opus", "vorbis", "aac", "mp3", "ac-3", "ec-3")
            .none { codecs.contains(it, ignoreCase = true) }
    }

    private fun extractYouTubeViaPiped(videoId: String): List<Result> {
        for (instance in PIPED_INSTANCES) {
            val result = runCatching {
                val http = httpGetWithCookies(
                    "https://$instance$videoId",
                    mapOf("User-Agent" to API_UA, "Accept" to "application/json"),
                    timeoutMs = 4000
                ) ?: return@runCatching null
                val obj = JSONObject(http.body)
                val title = obj.optString("title", "YouTube_$videoId")
                val streams = obj.optJSONArray("videoStreams") ?: return@runCatching null
                // Pasangan audio untuk stream video-only (issue #537):
                // videoStreams Piped mencampur stream progresif
                // bersuara dengan stream adaptif video-only (1080p+
                // hampir selalu video-only) — menawarkan yang bisu
                // sebagai unduhan utuh menghasilkan video tanpa suara
                // berstatus COMPLETED.
                val audioPair = bestPipedAudioUrl(obj.optJSONArray("audioStreams"))
                val options = mutableListOf<Result>()
                for (i in 0 until streams.length()) {
                    val s = streams.optJSONObject(i) ?: continue
                    val u = s.optString("url", "")
                    if (u.startsWith("http")) {
                        val mime = s.optString("mimeType", MIME_MP4)
                        val ext = if (mime.contains("webm")) "webm" else "mp4"
                        val safeName = sanitizeFileName(title)
                        val quality = s.optString("quality", "Unknown")
                        // mimeType harus mime stream itu sendiri: ekstensi
                        // sudah diturunkan darinya, jadi berkas .webm yang
                        // tercatat video/mp4 salah label (issue #434).
                        if (s.optBoolean("videoOnly", false)) {
                            // Bisu: hanya ditawarkan sebagai pasangan
                            // adaptif video+audio; tanpa pasangan yang
                            // bisa dimux, lewati sama sekali.
                            if (audioPair.isEmpty()) continue
                            options.add(
                                Result(
                                    u, "${safeName}.$ext", title, quality, mime,
                                    videoUrl = u, audioUrl = audioPair
                                )
                            )
                        } else {
                            options.add(Result(u, "${safeName}.$ext", title, quality, mime))
                        }
                    }
                }
                options
            }.getOrNull()
            if (!result.isNullOrEmpty()) {
                return result
            }
        }
        return emptyList()
    }

    // Instance Invidious publik — /latest_version menyelesaikan transformasi
    // n-signature di sisi server lalu me-redirect ke stream googlevideo.
    private val INVIDIOUS_INSTANCES = listOf(
        "invidious.nerdvpn.de",
        "invidious.f5.si",
        "inv.nadeko.net",
        "vid.puffyan.us",
        "yewtu.be",
        "invidious.privacyredirect.com"
    )

    private fun extractYouTubeViaInvidious(videoId: String): Result? {
        for (instance in INVIDIOUS_INSTANCES) {
            val resolved = resolveInvidiousLatest(instance, videoId, "18")
            if (resolved != null) {
                return Result(resolved, null, "YouTube_$videoId", "360p", MIME_MP4)
            }
        }
        return null
    }

    // ── Cobalt.tools ────────────────────────────────────────────────────────
    // Cobalt adalah service open-source yang resolve n-signature YouTube di
    // sisi server sehingga URL yang dikembalikan bisa langsung di-download.
    private val COBALT_INSTANCES = listOf(
        "https://api.cobalt.tools"
    )

    private fun extractYouTubeViaCobalt(videoId: String): Result? {
        val watchUrl = "https://www.youtube.com/watch?v=$videoId"
        val body = """{"url":"$watchUrl","filenameStyle":"pretty","downloadMode":"auto"}"""
        for (instance in COBALT_INSTANCES) {
            val resp = httpPostJson(
                "$instance/",
                body,
                mapOf("User-Agent" to API_UA, "Accept" to "application/json"),
                timeoutMs = 15000
            )
            if (resp == null) {
                continue
            }
            // Pola seperti Piped di atas: null = lanjut ke instance berikut.
            // (Sebelumnya `return ...?.let { return it }` membuat parse tanpa URL
            // langsung me-return null dari fungsi sehingga loop tak pernah lanjut.)
            val result = runCatching {
                val obj = JSONObject(resp)
                val status = obj.optString("status", "")
                val url = obj.optString("url", "")
                if (status.isNotEmpty() && url.startsWith("http")) {
                    val fileName = obj.optString("filename", "YouTube_$videoId.mp4")
                    Result(url, fileName, "YouTube_$videoId", "Auto", MIME_MP4)
                } else {
                    null
                }
            }.getOrNull()
            if (result != null) return result
        }
        return null
    }

    /** Ikuti redirect /latest_version dan kembalikan URL stream final non-HTML.
     *  Pengecualian jaringan (SocketTimeoutException, IOException) TIDAK
     *  boleh menghentikan rantai fallback YouTube — instance ini dianggap
     *  gagal dan pemanggil lanjut ke instance lain (issue #546: dulu
     *  pengecualian tertelan loop sehingga URL yang sama dicoba sampai
     *  6x sebelum failover). Hanya redirect yang melanjutkan loop. */
    private fun resolveInvidiousLatest(instance: String, videoId: String, itag: String): String? {
        val start = "https://$instance/latest_version?id=$videoId&itag=$itag"
        var current = start
        for (i in 0 until 6) {
            try {
                val conn = openTlsConn(current)
                try {
                    conn.instanceFollowRedirects = false
                    conn.connectTimeout = 4000
                    conn.readTimeout = 4000
                    conn.setRequestProperty("User-Agent",
                        YT_UA)
                    conn.setRequestProperty("Accept", "$MIME_MP4,*/*")
                    val code = conn.responseCode
                    // Hanya redirect sungguhan (304/305/306 bukan redirect bertarget):
                    // samakan daftar dengan httpGetWithCookies/httpPostJson.
                    if (code == 301 || code == 302 || code == 303 || code == 307 || code == 308) {
                        val loc = conn.getHeaderField("Location")
                        if (loc.isNullOrBlank()) return null
                        val next = try {
                            java.net.URI(current).resolve(loc).toString()
                        } catch (_: Exception) { return null }
                        // Instance pihak ketiga tak boleh mengarahkan ke host
                        // terlarang (loopback/metadata) seperti helper HTTP lain.
                        if (!isExtractRedirectAllowed(next)) return null
                        current = next
                        continue
                    }
                    val type = conn.contentType ?: ""
                    if (code in 200..299 && type.contains("video", ignoreCase = true)) {
                        return current
                    }
                    return null
                } finally { runCatching { conn.disconnect() } }
            } catch (_: Exception) {
                // Pengecualian jaringan: instance ini gagal — jangan
                // ulang URL yang sama; serahkan failover ke pemanggil.
                return null
            }
        }
        return null
    }

    private fun extractYouTubeId(url: String): String? {
        YT_ID_SHORTS_RE.find(url)
            ?.groupValues?.get(1)?.let { return it }
        YT_ID_V_RE.find(url)
            ?.groupValues?.get(1)?.let { return it }
        YT_ID_YOUTU_RE.find(url)
            ?.groupValues?.get(1)?.let { return it }
        return null
    }

    private fun sanitizeFileName(name: String): String {
        // FileNames.safe menangani slash/trim/nama kosong; aturan ketat sosmed lanjut di sini.
        return FileNames.safe(name).replace(SANITIZE_BAD_CHARS_RE, "_")
            .replace(SANITIZE_WS_RE, "_")
            .take(80)
    }

    // ── XVideos & XNXX ────────────────────────────────────────────────────
    // Kedua situs satu grup (player `html5player.*` sama) sehingga fetch dan
    // parser halaman watch dipakai bersama; beda hanya prefix nama file, judul
    // default, dan header Referer. Domain mirror xnxxvideos.me didukung
    // best-effort (struktur tak resmi, bisa berubah sewaktu-waktu).

    private val XV_HEADERS = mapOf(
        "User-Agent" to DEFAULT_UA,
        "Accept" to "text/html,application/xhtml+xml",
        "Referer" to "https://www.xvideos.com/"
    )

    private val XN_HEADERS = mapOf(
        "User-Agent" to DEFAULT_UA,
        "Accept" to "text/html,application/xhtml+xml",
        "Referer" to "https://www.xnxx.com/"
    )

    private fun extractBestPlayerOption(options: List<Result>): Result? {
        return options.firstOrNull { it.quality == "HD" }
            ?: options.firstOrNull { !it.isHls }
            ?: options.firstOrNull()
    }

    private fun fetchWatchHtml(url: String, headers: Map<String, String>): String? {
        val html = httpGet(url, headers, timeoutMs = PAGE_TIMEOUT_MS) ?: return null
        if (html.length < 1000) return null
        return html
    }

    private fun extractXVideos(url: String, user: Map<String, String> = emptyMap()): Result? =
        extractBestPlayerOption(extractAllXVideos(url, user))

    private fun extractAllXVideos(url: String, user: Map<String, String> = emptyMap()): List<Result> {
        val html = fetchWatchHtml(url, XV_HEADERS + user) ?: return emptyList()
        return parseXVideosPage(html)
    }

    private fun extractXnxx(url: String, user: Map<String, String> = emptyMap()): Result? =
        extractBestPlayerOption(extractAllXnxx(url, user))

    private fun extractAllXnxx(url: String, user: Map<String, String> = emptyMap()): List<Result> {
        val html = fetchWatchHtml(url, XN_HEADERS + user) ?: return emptyList()
        return parseXnxxPage(html)
    }

    /** Parser murni halaman watch XVideos (tanpa I/O) agar bisa di-unit-test. */
    internal fun parseXVideosPage(html: String): List<Result> =
        parseHtml5PlayerPage(html, "XVideos", "XVideos_video", " - XVIDEOS")

    /** Parser murni halaman watch XNXX (tanpa I/O) agar bisa di-unit-test. */
    internal fun parseXnxxPage(html: String): List<Result> =
        parseHtml5PlayerPage(html, "XNXX", "XNXX_video", " - XNXX")

    /** Parser bersama player html5player (XVideos & XNXX). */
    private fun parseHtml5PlayerPage(html: String, namePrefix: String, defaultTitle: String, titleSuffix: String): List<Result> {
        var title = HP_TITLE_RE.find(html)?.groupValues?.get(1)
            ?: HP_OG_TITLE_RE.find(html)?.groupValues?.get(1)
            ?: HP_TITLE_TAG_RE.find(html)?.groupValues?.get(1)?.substringBefore(titleSuffix)
            ?: defaultTitle
        title = unescapePlayer(title.trim()).take(120)
        if (title.isBlank()) title = defaultTitle
        val safeName = sanitizeFileName(title)
        val options = mutableListOf<Result>()
        val high = HP_URL_HIGH_RE.find(html)?.groupValues?.get(1)?.let(::unescapePlayer)
        if (!high.isNullOrEmpty() && high.startsWith("http")) {
            options.add(Result(high, "${namePrefix}_${safeName}_HD.mp4", title, "HD", MIME_MP4))
        }
        val low = HP_URL_LOW_RE.find(html)?.groupValues?.get(1)?.let(::unescapePlayer)
        if (!low.isNullOrEmpty() && low.startsWith("http") && low != high) {
            options.add(Result(low, "${namePrefix}_${safeName}_SD.mp4", title, "SD", MIME_MP4))
        }
        val hls = HP_HLS_RE.find(html)?.groupValues?.get(1)?.let(::unescapePlayer)
        if (!hls.isNullOrEmpty() && hls.startsWith("http")) {
            options.add(Result(hls, "${namePrefix}_${safeName}.ts", title, "HLS", "application/x-mpegURL", isHls = true))
        }
        if (options.isEmpty()) {
            val ogVideo = HP_OG_VIDEO_RE.find(html)?.groupValues?.get(1)?.let(::unescapePlayer)
            if (!ogVideo.isNullOrEmpty() && ogVideo.startsWith("http")) {
                if (ogVideo.contains(".m3u8")) {
                    options.add(Result(ogVideo, "${namePrefix}_${safeName}.ts", title, "HLS", "application/x-mpegURL", isHls = true))
                } else {
                    options.add(Result(ogVideo, "${namePrefix}_${safeName}.mp4", title, "Video", MIME_MP4))
                }
            }
        }
        return options
    }

    /** Unescape URL/judul dari JS/HTML (`\\/`, `\\u0026`, `&amp;`) — dipakai
     *  bersama ekstraktor Instagram & Pornhub/XNXX agar satu definisi. */
    private fun unescapePlayer(raw: String): String {
        return raw.replace("\\/", "/")
            .replace("\\u002F", "/")
            .replace("\\u0026", "&")
            .replace("&amp;", "&")
    }

    /** Unescape token JSON ber-escape (`\\u002F`, `\\u0026`, `\\"`, `\\/`)
     *  untuk `contextJSON`/`token` Instagram — satu definisi untuk 3 format.
     *  Internal agar logika murni ini teruji langsung (Murni). */
    internal fun unescapeJsToken(raw: String): String {
        return raw.replace("\\u002F", "/")
            .replace("\\u0026", "&")
            .replace("\\\"", "\"")
            .replace("\\/", "/")
    }

    // ── Pornhub ─────────────────────────────────────────────────────
    // Halaman watch (`/view_video.php?viewkey=...`) memuat varian di JSON
    // `mediaDefinitions` (`"videoUrl"` + `"quality"`) dan fallback `flashvars`
    // (`"quality_720p"`, `"video_url"`). Unduhan langsung yang sebelumnya hanya
    // menyimpan halaman HTML kini diekstrak jadi MP4/HLS seperti XNXX/XVideos.

    private val PH_HEADERS = mapOf(
        "User-Agent" to DEFAULT_UA,
        "Accept" to "text/html,application/xhtml+xml",
        "Referer" to "https://www.pornhub.com/"
    )

    private fun extractPornhub(url: String, user: Map<String, String> = emptyMap()): Result? =
        preferProgressiveMp4(extractAllPornhub(url, user))

    /** MP4 progresif diutamakan: single-stream (Range/resume) jauh lebih
     *  andal daripada HLS phncdn yang segmennya rawan HTTP 404. Opsi sudah
     *  urut kualitas menurun sehingga MP4 pertama = MP4 terbaik. */
    internal fun preferProgressiveMp4(options: List<Result>): Result? {
        return options.firstOrNull { !it.isHls } ?: options.firstOrNull()
    }

    private fun extractAllPornhub(url: String, user: Map<String, String> = emptyMap()): List<Result> {
        val result = httpGetWithCookies(url, PH_HEADERS + user, PAGE_TIMEOUT_MS) ?: return emptyList()
        if (result.body.length < 1000) return emptyList()
        return parsePornhubPage(result.body, result.cookies)
    }

    /** Parser murni halaman watch Pornhub (tanpa I/O) agar bisa di-unit-test. */
    internal fun parsePornhubPage(html: String, cookies: String = ""): List<Result> {
        var title = HP_OG_TITLE_RE.find(html)?.groupValues?.get(1)
            ?: HP_TITLE_TAG_RE.find(html)?.groupValues?.get(1)?.substringBefore(" - Pornhub")
            ?: "Pornhub_video"
        title = unescapePlayer(title.trim()).take(120)
        if (title.isBlank()) title = "Pornhub_video"
        val safeName = sanitizeFileName(title)
        val found = LinkedHashMap<String, Int>()
        fun addCandidate(raw: String?, quality: Int) {
            if (raw.isNullOrBlank()) return
            val url = unescapePlayer(raw.trim())
            if (!url.startsWith("http")) return
            val prev = found[url]
            if (prev == null || quality > prev) found[url] = quality
        }
        // Jangan menjodohkan videoUrl dan quality dengan satu regex
        // berentang [^}]*?: setiap videoUrl tanpa quality dalam objek
        // yang sama memindai ulang sisa objek (kuadratik, issue #360).
        // Cari URL satu lintasan, lalu quality hanya pada sisa objek itu
        // sendiri (berhenti di kurung tutup atau videoUrl berikutnya).
        PH_MEDIA_VIDEO_URL_RE.findAll(html).forEach { match ->
            val afterUrl = match.range.last + 1
            val braceEnd = html.indexOf('}', afterUrl).takeIf { it >= 0 } ?: html.length
            val nextVideo = html.indexOf("\"videoUrl\"", afterUrl)
            val segmentEnd = if (nextVideo >= 0) minOf(braceEnd, nextVideo) else braceEnd
            // Cari HANYA di irisan segmen (issue #636): find(html,
            // afterUrl) tanpa batas memindai sisa dokumen untuk tiap
            // kandidat tanpa quality — kuadratik pada HTML besar.
            // Jendela penerimaan tak berubah (dulu disaring takeIf
            // range.first < segmentEnd); kini jendela yang sama menjadi
            // batas pindai itu sendiri.
            val qualityMatch = PH_MEDIA_QUALITY_RE.find(
                html.substring(afterUrl, segmentEnd)
            )
            if (qualityMatch != null) {
                addCandidate(
                    match.groupValues.getOrNull(1),
                    qualityMatch.groupValues.getOrNull(1)?.toIntOrNull() ?: 0
                )
            }
        }
        PH_FLASHVARS_Q_RE.findAll(html).forEach {
            addCandidate(it.groupValues.getOrNull(2), it.groupValues.getOrNull(1)?.toIntOrNull() ?: 0)
        }
        if (found.isEmpty()) {
            addCandidate(PH_VIDEO_URL_RE.find(html)?.groupValues?.getOrNull(1), 0)
        }
        if (found.isEmpty()) {
            addCandidate(HP_OG_VIDEO_RE.find(html)?.groupValues?.getOrNull(1), 0)
        }
        if (found.isEmpty()) {
            addCandidate(HH_TWITTER_STREAM_RE.find(html)?.groupValues?.getOrNull(1), 0)
        }
        if (found.isEmpty()) {
            HH_GENERIC_MEDIA_RE.findAll(html).forEach { addCandidate(it.value, 0) }
        }
        return found.entries.sortedByDescending { it.value }.map { (url, quality) ->
            val isHls = url.contains(".m3u8")
            if (isHls) {
                Result(url, "Pornhub_${safeName}.ts", title, if (quality > 0) "${quality}p" else "HLS", "application/x-mpegURL", cookies = cookies, isHls = true)
            } else {
                val label = when {
                    quality >= 720 -> "HD"
                    quality > 0 -> "${quality}p"
                    else -> "Video"
                }
                Result(url, "Pornhub_${safeName}_${label}.mp4", title, label, MIME_MP4, cookies = cookies)
            }
        }
    }

    // ── HentaiHaven ─────────────────────────────────────────────────────
    // Halaman watch di balik challenge Cloudflare: fetch server langsung
    // hampir selalu 403. Alur utama adalah WebView (`WebExtractActivity`)
    // yang mewarisi challenge + cookie browser asli; fungsi di sini mencoba
    // fetch biasa dulu (berhasil bila challenge longgar) lewat parser murni.

    private val HH_HEADERS = mapOf(
        "User-Agent" to DEFAULT_UA,
        "Accept" to "text/html,application/xhtml+xml",
        // audit-ignore-next: maintenance_marker (nama domain resmi situs, bukan marker)
        "Referer" to "https://hentaihaven.xxx/"
    )

    private fun extractHentaiHaven(url: String, user: Map<String, String> = emptyMap()): Result? {
        val options = extractAllHentaiHaven(url, user)
        return options.firstOrNull { !it.isHls } ?: options.firstOrNull()
    }

    private fun extractAllHentaiHaven(url: String, user: Map<String, String> = emptyMap()): List<Result> {
        val html = fetchWatchHtml(url, HH_HEADERS + user) ?: return emptyList()
        return parseHentaiHavenPage(html)
    }

    /** Parser murni halaman watch HentaiHaven (tanpa I/O) agar bisa di-unit-test. */
    internal fun parseHentaiHavenPage(html: String): List<Result> {
        var title = HP_OG_TITLE_RE.find(html)?.groupValues?.get(1)
            ?: HP_TITLE_TAG_RE.find(html)?.groupValues?.get(1)?.substringBefore(" - ")
            ?: "HentaiHaven_video"
        title = unescapePlayer(title.trim()).take(120)
        if (title.isBlank()) title = "HentaiHaven_video"
        val safeName = sanitizeFileName(title)
        val candidates = mutableListOf<Pair<String, Boolean>>()
        fun addCandidate(raw: String?) {
            val url = cleanHentaiHavenUrl(raw) ?: return
            candidates.add(url to url.contains(".m3u8"))
        }
        HH_VIDEO_TAG_RE.findAll(html).forEach { addCandidate(it.groupValues.getOrNull(1)) }
        HH_SOURCE_TAG_RE.findAll(html).forEach { addCandidate(it.groupValues.getOrNull(1)) }
        HH_DATA_SRC_RE.findAll(html).forEach { addCandidate(it.groupValues.getOrNull(1)) }
        HH_JWPLAYER_RE.findAll(html).forEach { addCandidate(it.groupValues.getOrNull(1)) }
        if (candidates.isEmpty()) {
            addCandidate(HP_OG_VIDEO_RE.find(html)?.groupValues?.getOrNull(1))
        }
        if (candidates.isEmpty()) {
            addCandidate(HH_TWITTER_STREAM_RE.find(html)?.groupValues?.getOrNull(1))
        }
        if (candidates.isEmpty()) {
            HH_PRELOAD_RE.findAll(html).forEach { addCandidate(it.groupValues.getOrNull(1)) }
        }
        if (candidates.isEmpty()) {
            HH_GENERIC_MEDIA_RE.findAll(html).forEach { addCandidate(it.value) }
        }
        val seen = mutableSetOf<String>()
        val distinct = candidates
            .filter { (url, _) -> url.startsWith("http") && seen.add(url) }
        // MP4 langsung diutamakan (lebih kompatibel di engine); HLS sesudahnya.
        // Satu sort stabil (false dulu) sebagai ganti dua filter + gabung:
        // urutan relatif dalam tiap grup identik, tanpa 3 daftar sementara.
        val ordered = distinct.sortedBy { it.second }
        return ordered.map { (url, hls) ->
            if (hls) {
                Result(url, "HentaiHaven_${safeName}.ts", title, "HLS", "application/x-mpegURL", isHls = true)
            } else {
                Result(url, "HentaiHaven_${safeName}.mp4", title, "Video", MIME_MP4)
            }
        }
    }

    /** Bersihkan URL media HH: unescape JS/HTML + pangkas buntut sintaks. */
    private fun cleanHentaiHavenUrl(raw: String?): String? {
        if (raw.isNullOrBlank()) return null
        var url = unescapePlayer(raw.trim())
        url = url.trimEnd('.', ',', ';', ')', ']', '}', '!', '"', '\'')
        if (!url.startsWith("http")) return null
        return url
    }

    /** Batas daftar episode per halaman agar intent hasil tetap ringan. */
    private const val HH_MAX_EPISODES = 50

    /** Tautan episode HentaiHaven dari WebView (JSON `[{url,title}]`). */
    data class EpisodeLink(val url: String, val title: String)

    /** Parse daftar episode titipan WebView: hanya http(s) ber-path `/watch/`,
     *  buang halaman aktif & duplikat (tanpa I/O sehingga bisa di-unit-test). */
    internal fun parseEpisodeLinks(json: String, excludeUrl: String = ""): List<EpisodeLink> {
        val arr = runCatching { JSONArray(json) }.getOrNull() ?: return emptyList()
        val seen = LinkedHashSet<String>()
        val out = mutableListOf<EpisodeLink>()
        for (i in 0 until arr.length()) {
            if (out.size >= HH_MAX_EPISODES) break
            val obj = arr.optJSONObject(i) ?: continue
            var url = obj.optString("url").trim()
            if (url.startsWith("//")) url = "https:$url"
            if (!url.startsWith("http://") && !url.startsWith("https://")) continue
            if (!url.contains("/watch/")) continue
            if (url == excludeUrl) continue
            if (!seen.add(url)) continue
            out.add(EpisodeLink(url, obj.optString("title").trim().take(80)))
        }
        return out
    }

    // ── Twitter/X ────────────────────────────────────────────────────────

    private fun extractTwitter(url: String): Result? {
        val options = extractAllTwitter(url)
        return options.firstOrNull()
    }

    private fun extractAllTwitter(url: String): List<Result> {
        val cleanUrl = url.replace("https://x.com/", "https://twitter.com/")
        val path = URL(cleanUrl).path
        val json = httpGet("https://api.vxtwitter.com/twitter$path") ?: return emptyList()
        val obj = JSONObject(json)
        val tweet = obj.optJSONObject("tweet") ?: return emptyList()
        val user = tweet.optJSONObject("user")?.optString("name") ?: "Twitter"
        val text = tweet.optString("text", "")
        val media = tweet.optJSONArray("media") ?: return emptyList()
        val options = mutableListOf<Result>()

        // Tweet multi-media: beri nomor urut pada nama (issue #397) —
        // sebelumnya semua video/foto satu tweet bernama persis sama dan
        // tak bisa dibedakan di daftar pilihan (Instagram sudah memberi
        // indeks). Media tunggal mempertahankan nama lama tanpa nomor.
        for (i in 0 until media.length()) {
            val item = media.optJSONObject(i) ?: continue
            val suffix = if (media.length() > 1) "_${i + 1}" else ""
            when (item.optString("type")) {
                // "gif" (issue #420): vxtwitter melaporkan GIF sebagai tipe
                // tersendiri dengan URL berkas MP4 di video.twimg.com.
                // Tanpa cabang ini item terlewat diam-diam dan tweet
                // khusus GIF gagal diekstrak total (daftar opsi kosong).
                "video", "gif" -> {
                    val directUrl = item.optString("url")
                    if (directUrl.startsWith("http")) {
                        options.add(Result(directUrl, "Twitter_${sanitizeFileName(user)}$suffix.mp4", text, "Video", MIME_MP4))
                    }
                }
                "photo" -> {
                    val directUrl = item.optString("url")
                    if (directUrl.startsWith("http")) {
                        options.add(Result(directUrl, "Twitter_${sanitizeFileName(user)}$suffix.jpg", text, "Photo", "image/jpeg"))
                    }
                }
            }
        }
        return options
    }

    /** Parse header user format "Key: value" per baris (sama seperti
     *  applyAuthHeaders engine): baris kosong/tanpa ':' dibuang, user menang
     *  atas default platform. Murni agar bisa di-unit-test. */
    internal fun parseUserHeaders(headers: String): Map<String, String> {
        if (headers.isBlank()) return emptyMap()
        val out = LinkedHashMap<String, String>()
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
                    if (key.isNotEmpty() && value.isNotEmpty()) out[key] = value
                }
            }
            if (nl < 0) break
            start = nl + 1
        }
        return out
    }

    // ── Google Drive ─────────────────────────────────────────────────────

    /** ID berkas Drive hanya memakai huruf, angka, '-' dan '_' (umumnya
     *  28-44 karakter); ambang 12 membuang fragmen path yang bukan ID. */
    private val GD_FILE_ID_RE = Regex("""/file/d/([A-Za-z0-9_-]{12,})""")
    private val GD_ID_PARAM_RE = Regex("""[?&]id=([A-Za-z0-9_-]{12,})""")

    /** Ambil ID berkas dari URL berbagi Drive (bentuk file/d/, open?id=,
     *  uc?id=). Link folder dan halaman Drive lain mengembalikan null.
     *  Host dinilai terpisah oleh GD_HOST_RE sebelum fungsi ini dipanggil.
     *  Murni agar bisa di-unit-test. */
    internal fun parseDriveFileId(url: String): String? {
        GD_FILE_ID_RE.find(url)?.let { return it.groupValues[1] }
        return GD_ID_PARAM_RE.find(url)?.groupValues?.get(1)
    }

    /** URL unduh awal Drive: berkas kecil langsung terunduh/ter-redirect
     *  dari sini; berkas besar menjawab halaman konfirmasi HTML. */
    internal fun driveDownloadUrl(fileId: String): String =
        "https://drive.google.com/uc?export=download&id=$fileId"

    // Semua rentang dibatasi (issue #350): pola tak terbatas bersifat
    // kuadratik pada HTML berisi banyak "<form " tanpa penutup (tiap
    // posisi awal findAll memindai ulang jauh ke depan), dan
    // withTimeoutOrNull tidak bisa menginterupsi loop regex. Batasnya
    // jauh di atas ukuran form perantara Drive nyata (tag < 1KB, isi
    // form hanya beberapa field tersembunyi) dan hasil parse terbukti
    // setara untuk halaman realistis.
    private val GD_FORM_BLOCK_RE =
        Regex(
            "<form[^>]{0,1024}?action=\"([^\"]+)\"[^>]{0,1024}?>(.{0,16384}?)(?:</form>|$)",
            setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL)
        )
    private val GD_FORM_FIELD_A_RE =
        Regex("<input[^>]+name=\"([^\"]+)\"[^>]+value=\"([^\"]*)\"", RegexOption.IGNORE_CASE)
    private val GD_FORM_FIELD_B_RE =
        Regex("<input[^>]+value=\"([^\"]*)\"[^>]+name=\"([^\"]+)\"", RegexOption.IGNORE_CASE)

    /** Rakit URL unduh terkonfirmasi dari halaman perantara Drive
     *  (peringatan pemindaian virus untuk berkas besar): form GET ke
     *  drive.usercontent.google.com dengan field id/export/confirm/uuid.
     *  Mengembalikan null bila form unduh tidak ada (halaman kuota habis,
     *  wajib masuk, atau berkas tidak ditemukan). Murni. */
    internal fun parseDriveConfirmUrl(html: String): String? {
        // Form yang dipakai adalah form UNDUH-nya, bukan form pertama
        // dokumen (#247): halaman perantara bisa memuat form lain lebih
        // dulu. Field juga hanya dibaca dari DALAM form itu — field asing
        // bernama sama (mis. id) di luar form tidak boleh mencemari query
        // atau menimpa nilai aslinya.
        for (form in GD_FORM_BLOCK_RE.findAll(html)) {
            val action = form.groupValues[1].replace("&amp;", "&")
            if (!action.contains("usercontent.google.com/download")) continue
            val body = form.groupValues[2]
            val fields = LinkedHashMap<String, String>()
            for (m in GD_FORM_FIELD_A_RE.findAll(body)) {
                fields[m.groupValues[1]] = m.groupValues[2]
            }
            for (m in GD_FORM_FIELD_B_RE.findAll(body)) {
                fields.putIfAbsent(m.groupValues[2], m.groupValues[1])
            }
            if (fields["id"].isNullOrBlank() || fields["uuid"].isNullOrBlank()) {
                // Form unduh tak lengkap tidak menggugurkan form unduh
                // sah BERIKUTNYA (#284): dulu langsung return null.
                continue
            }
            val query = fields.entries.joinToString("&") { (k, v) ->
                "${URLEncoder.encode(k, "UTF-8")}=${URLEncoder.encode(v, "UTF-8")}"
            }
            // Query wajib SEBELUM fragmen (#324): action yang memuat
            // '#' (fragmen di akhir action form) membuat gabungan
            // mentah menempelkan ?id=... SESUDAH fragmen, sehingga
            // server tidak pernah menerima parameternya.
            val hashIdx = action.indexOf('#')
            val base = if (hashIdx >= 0) action.substring(0, hashIdx) else action
            val fragment = if (hashIdx >= 0) action.substring(hashIdx) else ""
            val sep = if (base.contains('?')) "&" else "?"
            return "$base$sep$query$fragment"
        }
        return null
    }

    private val GD_NAME_RE =
        Regex("""<span class="uc-name-size"[^>]*>\s*<a[^>]*>([^<]+)</a>""", RegexOption.IGNORE_CASE)

    /** Nama berkas yang ditampilkan halaman perantara Drive. Murni.
     *  Ini nama berkas ASLI (bukan judul halaman), jadi dibersihkan
     *  FileNames.safe seperti nama unduhan biasa — spasi/unicode utuh,
     *  bukan sanitizeFileName judul yang mengubah spasi jadi garis bawah. */
    internal fun parseDriveDisplayName(html: String): String? =
        GD_NAME_RE.find(html)?.groupValues?.get(1)?.trim()
            ?.takeIf { it.isNotEmpty() }?.let { FileNames.safe(it) }

    private val GD_CD_STAR_RE = Regex("""filename\*\s*=\s*(?:UTF-8|utf-8)''([^;]+)""")
    private val GD_CD_NAME_RE = Regex("""filename\s*=\s*"([^"]+)"|filename\s*=\s*([^;\s]+)""")

    /** Nama berkas dari header Content-Disposition (bentuk filename*=
     *  RFC 5987 didahulukan dari filename= biasa). Murni. Nama asli dari
     *  server: dibersihkan FileNames.safe seperti unduhan biasa. */
    internal fun parseContentDispositionFileName(cd: String?): String? {
        if (cd.isNullOrBlank()) return null
        GD_CD_STAR_RE.find(cd)?.let { m ->
            runCatching { java.net.URLDecoder.decode(m.groupValues[1].trim(), "UTF-8") }
                .getOrNull()?.takeIf { it.isNotBlank() }
                ?.let { return FileNames.safe(it) }
        }
        val raw = GD_CD_NAME_RE.find(cd)?.let { m ->
            m.groupValues[1].ifEmpty { m.groupValues[2] }
        }?.trim() ?: return null
        return raw.takeIf { it.isNotEmpty() }?.let { FileNames.safe(it) }
    }

    /** Hasil fetch Drive: header respons akhir + body hanya bila HTML. */
    private data class DriveFetch(
        val finalUrl: String,
        val contentType: String,
        val contentDisposition: String?,
        val cookies: String,
        val html: String?
    )

    /** GET URL Drive mengikuti redirect manual dengan aturan redirect
     *  ekstraktor (hanya http(s) ke host yang diizinkan, kredensial dibuang
     *  saat host berubah) sambil mengakumulasi cookie antar-hop — halaman
     *  konfirmasi Drive memerlukannya. Body hanya dibaca bila respons HTML;
     *  berkas media besar tidak pernah dimuat ke memori. */
    private fun fetchDrive(urlStr: String, headers: Map<String, String>): DriveFetch? {
        var current = urlStr
        var activeHeaders = headers
        // Jar per nama -> (nilai, host pemasang): cookie hanya dikirim ke
        // host/domain induk yang sama (#239), bukan ke host redirect mana
        // pun seperti jar polos sebelumnya.
        // Jar berkunci (nama, host) seperti httpGetWithCookies (issue
        // #396): kunci nama saja membuat cookie bernama sama dari host
        // kedua menimpa entri + tag host host pertama, sehingga saringan
        // visibilitas menilai dari host pemasang yang salah.
        val cookieJar = LinkedHashMap<Pair<String, String>, Pair<String, String>>()
        // Deadline total rantai hop (#246): 6 hop x 20 dtk bisa 120 dtk,
        // melampaui anggaran ekstraksi 60 dtk dan membuat tahap ini
        // ditinggal withTimeoutOrNull tanpa hasil. Hop berhenti di 45 dtk
        // dan timeout per hop menyusut mengikuti sisa waktu.
        val deadline = android.os.SystemClock.elapsedRealtime() + 45_000L
        repeat(6) {
            val remainingMs = deadline - android.os.SystemClock.elapsedRealtime()
            if (remainingMs <= 0L) return null
            val conn = openTlsConn(current)
            try {
                val hopTimeout = remainingMs.coerceIn(1_000L, PAGE_TIMEOUT_MS.toLong()).toInt()
                conn.connectTimeout = hopTimeout
                conn.readTimeout = hopTimeout
                conn.instanceFollowRedirects = false
                val currentHost = runCatching { java.net.URI(current).host }
                    .getOrNull().orEmpty()
                // Pisahkan Cookie user (kunci huruf apa pun): cek
                // containsKey("Cookie") yang peka huruf dulu membuat jar
                // menimpa cookie: huruf kecil milik user.
                var userCookie = ""
                val sendHeaders = LinkedHashMap<String, String>()
                for ((k, v) in activeHeaders) {
                    if (k.equals("Cookie", ignoreCase = true)) {
                        userCookie = v
                    } else {
                        sendHeaders[k] = v
                    }
                }
                val jarVisible = cookieJar.entries
                    .filter { cookieJarVisible(it.value.second, currentHost) }
                    .joinToString("; ") { "${it.key.first}=${it.value.first}" }
                val effectiveCookie = mergeCookiePairs(userCookie, jarVisible)
                if (effectiveCookie.isNotEmpty()) {
                    sendHeaders["Cookie"] = effectiveCookie
                }
                sendHeaders.forEach { (k, v) -> conn.setRequestProperty(k, v) }
                if (!sendHeaders.containsKey("User-Agent")) {
                    conn.setRequestProperty("User-Agent", DEFAULT_UA)
                }
                val code = conn.responseCode
                conn.headerFields.entries
                    .filter { it.key?.equals("set-cookie", ignoreCase = true) == true }
                    .flatMap { it.value }
                    .forEach { raw ->
                        val pair = raw.substringBefore(';')
                        val eq = pair.indexOf('=')
                        if (eq > 0) {
                            val name = pair.substring(0, eq).trim()
                            val value = pair.substring(eq + 1).trim()
                            // Set-Cookie penghapusan tidak boleh disimpan
                            // lalu terkirim lagi sebagai 'nama=' (#359,
                            // menyelaraskan jar Drive dengan #321).
                            if (value.isEmpty() ||
                                raw.contains("max-age=0", ignoreCase = true)
                            ) {
                                cookieJar.remove(name to currentHost)
                            } else {
                                cookieJar[name to currentHost] = value to currentHost
                            }
                        }
                    }
                if (code == 301 || code == 302 || code == 303 || code == 307 || code == 308) {
                    val loc = conn.getHeaderField("Location") ?: return null
                    val next = try {
                        java.net.URI(current).resolve(loc).toString()
                    } catch (_: Exception) { return null }
                    if (!isExtractRedirectAllowed(next)) return null
                    if (!isExtractSameHost(current, next)) {
                        activeHeaders = stripExtractCredentials(activeHeaders)
                    }
                    current = next
                    return@repeat
                }
                if (code !in 200..299) return null
                val type = conn.contentType.orEmpty()
                val cd = conn.getHeaderField("Content-Disposition")
                // Cookie hasil untuk unduhan: hanya yang ter-scope ke host
                // final (bukan seluruh jar lintas host).
                val cookiesStr = cookieJar.entries
                    .filter { cookieJarVisible(it.value.second, currentHost) }
                    .joinToString("; ") { "${it.key.first}=${it.value.first}" }
                val html = if (type.contains("text/html", ignoreCase = true)) {
                    readBodyLimited(conn.inputStream)
                } else {
                    null
                }
                return DriveFetch(current, type, cd, cookiesStr, html)
            } catch (_: Exception) { return null } finally { conn.disconnect() }
        }
        return null
    }

    private fun extractGoogleDrive(url: String, user: Map<String, String>): Result? {
        val options = extractAllGoogleDrive(url, user)
        return options.firstOrNull()
    }

    private fun extractAllGoogleDrive(url: String, user: Map<String, String>): List<Result> {
        val id = parseDriveFileId(url) ?: return emptyList()
        val first = fetchDrive(driveDownloadUrl(id), user) ?: return emptyList()
        val html = first.html
        if (html != null) {
            // Berkas .html yang memang dibagikan sebagai berkas dilayani
            // sebagai text/html + Content-Disposition: attachment — itu
            // BERKAS, bukan halaman perantara (#248). Periksa disposition
            // sebelum memperlakukan body sebagai halaman konfirmasi.
            val cdName = parseContentDispositionFileName(first.contentDisposition)
            if (cdName != null) {
                val mime = first.contentType.substringBefore(';').trim()
                return listOf(
                    Result(first.finalUrl, cdName, cdName, "Original", mime, first.cookies)
                )
            }
            // Halaman perantara: hanya berguna bila memuat form konfirmasi
            // unduh; halaman kuota/masuk/tidak-ditemukan gagal dengan jujur.
            val confirmUrl = parseDriveConfirmUrl(html) ?: return emptyList()
            val name = parseDriveDisplayName(html)
            return listOf(Result(confirmUrl, name, name, "Original", "", first.cookies))
        }
        val mime = first.contentType.substringBefore(';').trim()
        val name = parseContentDispositionFileName(first.contentDisposition)
        return listOf(Result(first.finalUrl, name, name, "Original", mime, first.cookies))
    }

    // ── Facebook ─────────────────────────────────────────────────────────
    // Dihidupkan lagi atas permintaan owner (sempat dihapus total).
    // Pelajaran iterasi lama yang dipertahankan: halaman video WAJIB diminta
    // dengan UA browser asli — UA Googlebot hanya menerima halaman redirect
    // JS lookaside (bukan file media); URL lookaside crawler + fragmen DASH
    // disaring isValidFacebookMediaUrl; cookie halaman diteruskan ke hasil
    // untuk konten terbatas. Cadangan terakhir kini Cobalt.

    /** Header browser asli untuk halaman Facebook (Googlebot = cadangan). */
    private val FB_HEADERS = mapOf(
        "User-Agent" to YT_PAGE_UA,
        "Accept" to "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8",
        "Accept-Language" to "en-US,en;q=0.9,id;q=0.8"
    )

    private val FB_HD_SRC_RE = Regex("\"hd_src\"\\s*:\\s*\"([^\"]+)\"")
    private val FB_BROWSER_HD_RE = Regex("\"browser_native_hd_url\"\\s*:\\s*\"([^\"]+)\"")
    private val FB_PLAYABLE_HD_RE = Regex("\"playable_url_quality_hd\"\\s*:\\s*\"([^\"]+)\"")
    private val FB_SD_SRC_RE = Regex("\"sd_src\"\\s*:\\s*\"([^\"]+)\"")
    private val FB_BROWSER_SD_RE = Regex("\"browser_native_sd_url\"\\s*:\\s*\"([^\"]+)\"")
    private val FB_PLAYABLE_RE = Regex("\"playable_url\"\\s*:\\s*\"([^\"]+)\"")
    private val FB_BROWSER_URL_RE = Regex("\"browser_native_url\"\\s*:\\s*\"([^\"]+)\"")
    private val FB_VIDEO_ID_JSON_RE = Regex("\"(?:video_id|videoID|videoId)\"\\s*:\\s*\"([0-9]+)\"")
    private val FB_VIDEO_ID_ATTR_RE = Regex("data-video-id=\"([0-9]+)\"")
    private val FB_OG_VIDEO_RE = Regex("<meta[^>]+property=\"og:video[^\"]*\"[^>]+content=\"([^\"]+)\"")
    private val FB_URL_V_RE = Regex("[?&]v=([0-9]+)")
    private val FB_URL_PATH_ID_RE = Regex("/(?:videos|reel)/([0-9]+)")
    private val FB_SHARE_ID_RE = Regex("/v/([A-Za-z0-9_-]+)")

    private fun extractFacebook(url: String, user: Map<String, String>): Result? {
        val options = extractAllFacebook(url, user)
        return options.firstOrNull { it.quality.contains("HD", ignoreCase = true) }
            ?: options.firstOrNull()
    }

    /** Executor tahap ekstraksi berjangkar waktu (#246): fetch pemblokir
     *  (soket) tidak patuh pembatalan coroutine, jadi withTimeoutOrNull
     *  saja tidak bisa memotong tahap yang menggantung. Tahap dijalankan
     *  di thread daemon pool ini dan ditunggu dengan batas keras; benang
     *  yang terlambat selesai sendiri (timeout soketnya terbatas) dan
     *  hasilnya dibuang. Jumlah thread berbatas maksimum 8 (#267): tahap
     *  yang menggantung tidak bisa dibunuh (cancel tidak menginterupsi
     *  soket), jadi pola cached pool (core 0 + SynchronousQueue +
     *  keepAlive 60 dtk) dipertahankan polanya tetapi diberi plafon;
     *  submit berlebih ditolak dan dianggap tahap gagal. */
    private val stageExecutor: ExecutorService by lazy {
        ThreadPoolExecutor(
            0,
            8,
            60L,
            TimeUnit.SECONDS,
            SynchronousQueue(),
            ThreadFactory { r ->
                Thread(r, "extract-stage").apply { isDaemon = true }
            }
        )
    }

    /** Jalankan [block] dengan batas tunggu [capMs]; null bila lewat. */
    private fun <T> runStageCapped(capMs: Long, block: () -> T?): T? {
        // Pool penuh (#267) = tahap gagal, bukan pengecualian ke pemanggil.
        val future = try {
            stageExecutor.submit(Callable { runCatching { block() }.getOrNull() })
        } catch (_: Exception) {
            return null
        }
        return try {
            future.get(capMs, TimeUnit.MILLISECONDS)
        } catch (_: Exception) {
            future.cancel(false)
            null
        }
    }

    private fun extractAllFacebook(url: String, user: Map<String, String>): List<Result> {
        // Tiap strategi punya jatah waktu sendiri (#246): tanpa jatah,
        // tahap pertama yang lambat menghabiskan seluruh anggaran total
        // 60 detik dan cadangan tidak pernah dicoba.
        val (html, cookies) = runStageCapped(24_000L) {
            facebookPage(url, FB_HEADERS + user)
        } ?: ("" to "")
        val videoId = parseFacebookVideoId(url, html)
        val nameId = videoId ?: FB_SHARE_ID_RE.find(url)?.groupValues?.get(1) ?: "video"
        if (html.isNotEmpty()) {
            val options = parseFacebookPage(html, nameId, cookies)
            if (options.isNotEmpty()) return options
        }
        // Strategi 2: halaman plugin embed — sering memuat hd_src/sd_src
        // walau halaman utama tidak.
        val pluginRes = runStageCapped(12_000L) {
            httpGetWithCookies(
                "https://www.facebook.com/plugins/video.php?href=" +
                    URLEncoder.encode(url, "UTF-8") + "&show_text=false",
                FB_HEADERS + user, PAGE_TIMEOUT_MS
            )
        }
        val plugin = pluginRes?.body.orEmpty()
        if (plugin.isNotEmpty()) {
            // Cookie halaman plugin DIGABUNG dengan cookie tahap 1
            // (#278 + perbaikannya): versi #278 memakai cookie plugin
            // sebagai PENGGANTI total, jadi satu cookie pelacak dari
            // plugin membuang seluruh cookie sesi tahap 1. Nama yang
            // dipasang plugin menang (lebih segar/spesifik halaman),
            // sisanya diisi tahap 1 lewat mergeCookiePairs.
            val fromPlugin = parseFacebookPage(
                plugin, nameId,
                mergeCookiePairs(pluginRes?.cookies.orEmpty(), cookies)
            )
            if (fromPlugin.isNotEmpty()) return fromPlugin
        }
        // Strategi 3: halaman embed lama — butuh ID numerik.
        if (videoId != null) {
            val embedRes = runStageCapped(12_000L) {
                httpGetWithCookies(
                    "https://www.facebook.com/video/embed?video_id=$videoId",
                    FB_HEADERS + user, PAGE_TIMEOUT_MS
                )
            }
            val embed = embedRes?.body.orEmpty()
            if (embed.isNotEmpty()) {
                // Sama seperti plugin: cookie halaman embed digabung
                // dengan cookie tahap 1, bukan menggantikannya.
                val fromEmbed = parseFacebookPage(
                    embed, nameId,
                    mergeCookiePairs(embedRes?.cookies.orEmpty(), cookies)
                )
                if (fromEmbed.isNotEmpty()) return fromEmbed
            }
        }
        // Strategi 4: Cobalt — tanpa header user (cookie tak boleh bocor ke
        // pihak ketiga, pola yang sama dengan Twitter).
        val cobalt = runStageCapped(12_000L) { extractFacebookViaCobalt(url) }
        return if (cobalt != null) listOf(cobalt) else emptyList()
    }

    /** Ambil halaman video Facebook. Browser asli dicoba dulu; bila kosong/
     *  gagal, fallback ke Googlebot (kadang tetap memberi URL media).
     *  Fallback bot SENGAJA tanpa header user dan hanya untuk konten
     *  publik: cookie pengguna tidak menolong video yang butuh login di
     *  jalur crawler dan tidak boleh tercampur ke sesi bot. */
    private fun facebookPage(url: String, headers: Map<String, String>): Pair<String, String> {
        val page = httpGetWithCookies(url, headers, PAGE_TIMEOUT_MS)
        if (page != null && page.body.isNotEmpty()) {
            return page.body to page.cookies
        }
        val bot = httpGetWithCookies(url, IG_HEADERS, PAGE_TIMEOUT_MS)
        // Cookie sesi BOT tidak diteruskan (#279): cookie itu bukan
        // cookie halaman watch, jadi tidak boleh menempel ke Result
        // seolah cookie sesi pengguna. URL media dari tampilan crawler
        // adalah URL publik bertanda tangan yang tidak membutuhkannya.
        if (bot != null) return bot.body to ""
        return "" to ""
    }

    /** ID video numerik dari URL (watch?v=, /videos/<id>, /reel/<id>) atau
     *  dari JSON/atribut/og halaman. Null bila tidak ditemukan. Murni. */
    internal fun parseFacebookVideoId(url: String, html: String): String? {
        FB_URL_V_RE.find(url)?.groupValues?.get(1)?.let { return it }
        FB_URL_PATH_ID_RE.find(url)?.groupValues?.get(1)?.let { return it }
        FB_VIDEO_ID_JSON_RE.find(html)?.groupValues?.get(1)?.let { return it }
        FB_VIDEO_ID_ATTR_RE.find(html)?.groupValues?.get(1)?.let { return it }
        FB_OG_VIDEO_RE.find(html)?.groupValues?.get(1)?.let { og ->
            FB_URL_V_RE.find(og)?.groupValues?.get(1)?.let { return it }
        }
        return null
    }

    /** Parse halaman Facebook menjadi opsi unduhan (HD dulu, lalu SD).
     *  Murni agar bisa di-unit-test. */
    internal fun parseFacebookPage(html: String, videoId: String, cookies: String): List<Result> {
        if (html.length < 200) return emptyList()
        val found = linkedMapOf<String, String>()
        val candidates = listOf(
            FB_HD_SRC_RE to "HD",
            FB_BROWSER_HD_RE to "HD",
            FB_PLAYABLE_HD_RE to "HD",
            FB_SD_SRC_RE to "SD",
            FB_BROWSER_SD_RE to "SD",
            FB_PLAYABLE_RE to "SD",
            FB_BROWSER_URL_RE to ""
        )
        for ((regex, label) in candidates) {
            for (m in regex.findAll(html)) {
                val mediaUrl = unescapeFb(m.groupValues[1])
                if (isValidFacebookMediaUrl(mediaUrl) && mediaUrl.length in 20..2000) {
                    found.putIfAbsent(mediaUrl, label)
                }
            }
        }
        // Cadangan: URL mp4 langsung yang diapit tanda kutip di halaman.
        extractFacebookMp4Urls(html).forEach { mediaUrl ->
            val q = if (mediaUrl.contains("quality=hd") || mediaUrl.contains("_hd")) "HD" else "SD"
            found.putIfAbsent(mediaUrl, q)
        }
        return found.map { (mediaUrl, quality) ->
            Result(mediaUrl, "Facebook_$videoId.mp4", "Facebook $videoId", quality, MIME_MP4, cookies)
        }
    }

    /** Pindai URL mp4 yang diapit tanda kutip di halaman (menangani escape
     *  JSON \/); dedup dilakukan pemanggil. */
    private fun extractFacebookMp4Urls(html: String): List<String> {
        val out = mutableListOf<String>()
        var idx = html.indexOf(".mp4")
        var guard = 0
        while (idx >= 0 && guard++ < 100) {
            // Batasi jendela mentah di kedua sisi .mp4 (issue #361):
            // tanpa batas, tiap .mp4 dalam teks panjang tanpa kutip
            // memindai ulang wilayah yang sama sampai awal/akhir dokumen
            // (kuadratik). URL ter-decode maksimum 2000 karakter dan satu
            // karakter hasil escape JSON terpanjang memakai 6 karakter
            // mentah, jadi 12.000 per sisi adalah plafon yang aman.
            val maxRawSide = 12_000
            val startLimit = maxOf(0, idx - maxRawSide)
            var start = idx
            while (start > startLimit && html[start - 1] != '"') start--
            if (start == 0 || html[start - 1] != '"') {
                idx = html.indexOf(".mp4", idx + 4)
                continue
            }
            var end = idx + 4
            val endLimit = minOf(html.length, end + maxRawSide)
            while (end < endLimit && html[end] != '"') end++
            if (end < html.length && html[end] == '"' && end > idx + 4) {
                val mediaUrl = unescapeFb(html.substring(start, end))
                if (isValidFacebookMediaUrl(mediaUrl) && mediaUrl.contains(".mp4") &&
                    mediaUrl.length in 20..2000
                ) {
                    out.add(mediaUrl)
                }
            }
            idx = html.indexOf(".mp4", idx + 4)
            if (out.size >= 8) break
        }
        return out
    }

    /** URL media Facebook yang masih bisa diunduh. Redirect JS lookaside
     *  crawler dan fragmen DASH (BaseURL/SegmentBase) bukan file media —
     *  buang. Murni. */
    internal fun isValidFacebookMediaUrl(url: String): Boolean {
        if (!url.startsWith("http") || url.length > 2000) return false
        // Hasil dekode unescapeFb bisa memuat karakter hasil escape yang
        // tidak sah di URL sungguhan (issue #351): tanda kutip dari
        // u0022 serta karakter kontrol dari u0000-u001F/u007F. Validator
        // lama hanya menangkap "<"; URL berkutip/kontrol lolos lalu
        // gagal dipakai engine tanpa penjelasan.
        if (url.any { it == '"' || it.code < 0x20 || it.code == 0x7F }) return false
        if (url.contains("/lookaside/crawler/")) return false
        if (url.contains("\\u") || url.contains("<") ||
            url.contains("BaseURL") || url.contains("SegmentBase")
        ) {
            return false
        }
        return true
    }

    /** URL media di JSON Facebook memakai escape JSON (\/, \u0026, \x26,
     *  dst) — unescape. Murni. */
    // SATU pola gabungan untuk semua bentuk escape (#323): lintasan
    // terpisah per bentuk memindai ulang hasil dekode lintasan
    // sebelumnya, jadi \\u005c (backslash hasil dekode) + 'x26'
    // sesudahnya ter-dekode lagi menjadi '&' (dekode ganda), sementara
    // \\u005cu0026 berhenti sebagai literal — asimetris. Satu
    // lintasan Regex.replace tidak pernah memindai keluaran sendiri.
    private val FB_ESCAPE_RE = Regex(
        """\\u([0-9a-fA-F]{4})|\\x([0-9a-fA-F]{2})|\\/|&amp;"""
    )

    internal fun unescapeFb(s: String): String {
        // Dekode umum escape heksadesimal JSON (\u(4 digit hex), huruf hex bebas
        // besar/kecil) dan \x 2-digit hex (#283): daftar replace literal lama
        // hanya mengenal beberapa kode terpilih dalam satu ejaan huruf,
        // jadi URL sah bervarian \u002f / \u003D / \u003a tidak
        // ter-dekode lalu ditolak isValidFacebookMediaUrl sebagai URL
        // rusak. Satu lintasan regex: hasil dekode tidak dipindai ulang.
        return FB_ESCAPE_RE.replace(s) { m ->
            when {
                m.groupValues[1].isNotEmpty() ->
                    m.groupValues[1].toInt(16).toChar().toString()
                m.groupValues[2].isNotEmpty() ->
                    m.groupValues[2].toInt(16).toChar().toString()
                m.value == "&amp;" -> "&"
                else -> "/"
            }
        }
    }

    /** Cadangan Cobalt untuk Facebook: API yang sama dengan cadangan
     *  YouTube; body dibangun via JSONObject agar aman dari karakter
     *  khusus di URL. */
    private fun extractFacebookViaCobalt(url: String): Result? {
        val body = JSONObject()
            .put("url", url)
            .put("filenameStyle", "pretty")
            .put("downloadMode", "auto")
            .toString()
        for (instance in COBALT_INSTANCES) {
            val resp = httpPostJson(
                "$instance/", body,
                mapOf("User-Agent" to API_UA, "Accept" to "application/json"),
                timeoutMs = 15000
            ) ?: continue
            val result = runCatching {
                val obj = JSONObject(resp)
                val status = obj.optString("status", "")
                val mediaUrl = obj.optString("url", "")
                if (status.isNotEmpty() && mediaUrl.startsWith("http")) {
                    val fileName = obj.optString("filename", "Facebook.mp4")
                    Result(mediaUrl, fileName, "Facebook", "Auto", MIME_MP4)
                } else {
                    null
                }
            }.getOrNull()
            if (result != null) return result
        }
        return null
    }

    // ── HTTP ─────────────────────────────────────────────────────────────

    data class HttpResult(val body: String, val cookies: String = "")

    /** Batas max body response (16 MB) supaya redirect ke HTML raksasa tidak OOM. */
    private const val MAX_RESPONSE_BYTES = 16L * 1024 * 1024

    /** Baca body response secara terbatas — delegasi ke readBounded bersama. */
    private fun readBodyLimited(input: InputStream): String {
        return readBounded(input, MAX_RESPONSE_BYTES.toInt())
    }

    /** Redirect manual ekstraktor: HttpURLConnection mengirim ulang header Cookie/
     *  Authorization ke host redirect bila instanceFollowRedirects=true, jadi
     *  cookie user bisa bocor lintas origin. Ikuti pola jalur unduh utama
     *  (openAuthenticatedConnection): redirect manual + buang kredensial saat
     *  host berubah. */
    /** Redirect ekstraktor hanya boleh ke http(s) dan bukan host literal
     *  berbahaya (loopback/metadata), selaras `redirectTarget` jalur unduh
     *  utama. Tanpa ini fetch halaman bisa dibelokkan ke server lokal sendiri
     *  atau skema aneh. Murni agar bisa di-unit-test. */
    internal fun isExtractRedirectAllowed(target: String): Boolean {
        return try {
            val u = URL(target)
            val scheme = u.protocol.lowercase(Locale.ROOT)
            if (scheme != "http" && scheme != "https") return false
            !com.tasirin.httpdownloadmanager.download.isBlockedRedirectHost(u.host.orEmpty())
        } catch (_: Exception) { false }
    }

    private fun isExtractSameHost(from: String, to: String): Boolean {
        return try {
            URL(from).host.equals(URL(to).host, ignoreCase = true)
        } catch (_: Exception) { false }
    }

    private fun stripExtractCredentials(headers: Map<String, String>): Map<String, String> {
        var out = headers.filterKeys { k ->
            !k.equals("Cookie", ignoreCase = true) &&
                !k.equals("Authorization", ignoreCase = true) &&
                !k.equals("Proxy-Authorization", ignoreCase = true)
        }
        // Referer lintas-host: turunkan ke origin saja (seperti browser
        // strict-origin-when-cross-origin) — URL watch penuh tak bocor ke
        // pihak ketiga, tapi cek hotlink berbasis origin/host tetap lolos.
        val refKey = out.keys.firstOrNull { it.equals("Referer", ignoreCase = true) }
        if (refKey != null) {
            val origin = refKey.let { out[it] }?.let { refererOrigin(it) }
            out = out.filterKeys { !it.equals("Referer", ignoreCase = true) }
            if (origin != null) out = out + (refKey to "$origin/")
        }
        return out
    }

    /** Origin (skema://host[:port]) dari nilai Referer; null bila bukan
     *  http(s) valid. Murni agar bisa di-unit-test. */
    internal fun refererOrigin(ref: String): String? = runCatching {
        val u = URL(ref.trim())
        if (u.protocol != "http" && u.protocol != "https") return null
        val port = if (u.port > 0 && u.port != u.defaultPort) ":${u.port}" else ""
        "${u.protocol}://${u.host}$port"
    }.getOrNull()

    private fun httpGetWithCookies(urlStr: String, headers: Map<String, String> = emptyMap(), timeoutMs: Int = 15000): HttpResult? {
        var current = urlStr
        var activeHeaders = headers
        // Jar per (nama, host pemasang) -> (nilai, host pemasang),
        // diakumulasi TIAP hop (#280, pola fetchDrive #239): versi
        // lama hanya membaca Set-Cookie respons akhir, jadi cookie
        // sesi yang dipasang hop redirect perantara hilang dari
        // hasil. Kunci memuat host (#321): nama generik yang sama
        // (mis. session) dari host kedua tidak boleh menimpa milik
        // host pertama beserta tag hostnya.
        val cookieJar = LinkedHashMap<Pair<String, String>, Pair<String, String>>()
        // Deadline total rantai (#307, pola fetchDrive #246): timeout
        // per hop yang dipasang ulang PENUH membuat satu rantai 6 hop
        // bisa 90-120 dtk — jauh melampaui cap tahap pemanggil — dan
        // benang tahap yang sudah dicap (cancel tak menginterupsi
        // soket) tetap menduduki stageExecutor selama itu, menolak
        // tahap sehat berikutnya. Anggaran rantai dibatasi dan
        // timeout tiap hop menyusut mengikuti sisa waktu.
        val chainDeadline = android.os.SystemClock.elapsedRealtime() +
            maxOf(timeoutMs * 2L, 30_000L)
        repeat(6) {
            val remainingMs = chainDeadline -
                android.os.SystemClock.elapsedRealtime()
            if (remainingMs <= 0L) return null
            val conn = openTlsConn(current)
            try {
                val hopTimeout = remainingMs
                    .coerceIn(1_000L, timeoutMs.toLong()).toInt()
                conn.connectTimeout = hopTimeout
                conn.readTimeout = hopTimeout
                conn.instanceFollowRedirects = false
                val currentHost = runCatching { java.net.URI(current).host }
                    .getOrNull().orEmpty()
                // Cookie jar WAJIB ikut dikirim ke hop berikutnya
                // (pola fetchDrive): versi lama hanya MENGUMPULKAN
                // Set-Cookie tiap hop untuk hasil akhir, tetapi tidak
                // pernah mengirimkannya — hop lanjutan (endpoint
                // youtubei, watch, plugin) berjalan tanpa cookie sesi
                // yang dipasang hop perantara. Cookie user dipisah
                // dulu (kunci huruf apa pun) lalu digabung, bukan
                // ditimpa.
                var userCookie = ""
                val sendHeaders = LinkedHashMap<String, String>()
                for ((k, v) in activeHeaders) {
                    if (k.equals("Cookie", ignoreCase = true)) {
                        userCookie = v
                    } else {
                        sendHeaders[k] = v
                    }
                }
                val jarVisible = cookieJar.entries
                    .filter { cookieJarVisible(it.value.second, currentHost) }
                    .joinToString("; ") { "${it.key.first}=${it.value.first}" }
                val effectiveCookie = mergeCookiePairs(userCookie, jarVisible)
                if (effectiveCookie.isNotEmpty()) {
                    sendHeaders["Cookie"] = effectiveCookie
                }
                sendHeaders.forEach { (k, v) -> conn.setRequestProperty(k, v) }
                if (!sendHeaders.containsKey("User-Agent")) {
                    conn.setRequestProperty("User-Agent",
                        DEFAULT_UA)
                }
                val code = conn.responseCode
                conn.headerFields.entries
                    .filter { it.key?.equals("set-cookie", ignoreCase = true) == true }
                    .flatMap { it.value }
                    .forEach { raw ->
                        val pair = raw.substringBefore(';')
                        val eq = pair.indexOf('=')
                        if (eq > 0) {
                            val name = pair.substring(0, eq).trim()
                            val value = pair.substring(eq + 1).trim()
                            // Set-Cookie penghapusan (nilai kosong /
                            // Max-Age=0): entri dibuang, bukan disimpan
                            // lalu terkirim sebagai 'nama=' (#321).
                            if (value.isEmpty() ||
                                raw.contains("max-age=0", ignoreCase = true)
                            ) {
                                cookieJar.remove(name to currentHost)
                            } else {
                                cookieJar[name to currentHost] =
                                    value to currentHost
                            }
                        }
                    }
                if (code == 301 || code == 302 || code == 303 || code == 307 || code == 308) {
                    val loc = conn.getHeaderField("Location") ?: return null
                    val next = try {
                        java.net.URI(current).resolve(loc).toString()
                    } catch (_: Exception) { return null }
                    if (!isExtractRedirectAllowed(next)) return null
                    if (!isExtractSameHost(current, next)) {
                        activeHeaders = stripExtractCredentials(activeHeaders)
                    }
                    current = next
                    return@repeat
                }
                if (code !in 200..299) return null
                // Cookie hasil: seluruh jar yang ter-scope ke host final
                // — cookie hop awal yang seinduk ikut terbawa; cookie
                // lintas induk tidak (scoping cookieJarVisible #239).
                val cookies = cookieJar.entries
                    .filter { cookieJarVisible(it.value.second, currentHost) }
                    .joinToString("; ") { "${it.key.first}=${it.value.first}" }
                // Baca terbatas: hindari OOM dari body redirect/HTML raksasa.
                val body = readBodyLimited(conn.inputStream)
                return HttpResult(body, cookies)
            } catch (_: Exception) { return null } finally { conn.disconnect() }
        }
        return null
    }

    private fun httpGet(urlStr: String, headers: Map<String, String> = emptyMap(), timeoutMs: Int = 15000): String? {
        return httpGetWithCookies(urlStr, headers, timeoutMs)?.body
    }

    private fun httpPostJson(
        urlStr: String,
        body: String,
        headers: Map<String, String> = emptyMap(),
        timeoutMs: Int = 15000
    ): String? {
        var current = urlStr
        var method = "POST"
        var payload: ByteArray? = body.toByteArray(Charsets.UTF_8)
        var activeHeaders = headers
        // Deadline total rantai yang sama dengan httpGetWithCookies
        // (#307): rantai POST youtubei juga berjalan di benang tahap
        // yang slotnya terbatas.
        val chainDeadline = android.os.SystemClock.elapsedRealtime() +
            maxOf(timeoutMs * 2L, 30_000L)
        repeat(6) {
            val remainingMs = chainDeadline -
                android.os.SystemClock.elapsedRealtime()
            if (remainingMs <= 0L) return null
            val conn = openTlsConn(current)
            try {
                conn.requestMethod = method
                val hopTimeout = remainingMs
                    .coerceIn(1_000L, timeoutMs.toLong()).toInt()
                conn.connectTimeout = hopTimeout
                conn.readTimeout = hopTimeout
                conn.instanceFollowRedirects = false
                if (method == "POST") {
                    conn.setRequestProperty("Content-Type", "application/json")
                }
                conn.setRequestProperty("Accept", "application/json")
                activeHeaders.forEach { (k, v) -> conn.setRequestProperty(k, v) }
                val body = payload
                if (body != null) {
                    conn.doOutput = true
                    conn.outputStream.use { it.write(body) }
                }
                val code = conn.responseCode
                if (code == 301 || code == 302 || code == 303 || code == 307 || code == 308) {
                    val loc = conn.getHeaderField("Location") ?: return null
                    val next = try {
                        java.net.URI(current).resolve(loc).toString()
                    } catch (_: Exception) { return null }
                    if (!isExtractRedirectAllowed(next)) return null
                    if (!isExtractSameHost(current, next)) {
                        activeHeaders = stripExtractCredentials(activeHeaders)
                    }
                    current = next
                    if (code == 301 || code == 302 || code == 303) {
                        method = "GET"
                        payload = null
                    }
                    return@repeat
                }
                if (code !in 200..299) return null
                return readBodyLimited(conn.inputStream)
            } catch (_: Exception) { return null } finally { conn.disconnect() }
        }
        return null
    }
}
