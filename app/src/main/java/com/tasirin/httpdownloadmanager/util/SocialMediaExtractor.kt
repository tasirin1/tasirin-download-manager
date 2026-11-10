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
import java.util.Collections
import java.util.concurrent.Callable
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ExecutorService
import java.util.concurrent.Future
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

    // Anggaran total probe 403 opsi YouTube (issue #805): probe malas
    // sekuensial dari kandidat teratas (issue #1431), tiap HEAD
    // connect+read 6+6 dtk, plus margin.
    private const val FORBIDDEN_PROBE_BUDGET_MS = 15_000L

    // Anggaran tahap halaman YouTube (issue #824): WAJIB sama dengan
    // runStageCapped(30_000L) di kedua pemanggil extractYouTubeFromPage.
    // Dari cap itu disisakan cadangan untuk rantai fallback
    // Piped/Invidious/Cobalt; di bawah MIN, probe dilewati sama sekali.
    private const val STAGE_CAP_MS = 30_000L
    private const val FALLBACK_RESERVE_MS = 8_000L
    private const val MIN_PROBE_BUDGET_MS = 2_000L

    // Balapan fallback lapis ketiga (issue #1430): tiga layanan dicoba
    // konkuren; batas total + interval sapuan prioritasnya.
    private const val FALLBACK_RACE_BUDGET_MS = 20_000L
    private const val FALLBACK_RACE_POLL_MS = 50L

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
    private val TT_HOST_RE = Regex("""\A(?:https?://)(?:(?:www\.|m\.)?tiktok\.com/|vm\.tiktok\.com/|vt\.tiktok\.com/)""")
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
    private val IG_SIZE_MARKER_RE = Regex("""s(\d+)x(\d+)""")
    private val IG_VIDEO_URL_RE = Regex("\"url\"\\s*:\\s*\"(https?://[^\"]+\\.mp4[^\"]*)\"")
    private val IG_CONTEXT_OR_TOKEN_RE = Regex("contextJSON\\s*=\\s*\"|\"token\"\\s*:\\s*\"")
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
    // Bentuk path lain yang membawa ID langsung (issue #1291): tautan
    // Share siaran langsung/premiere (/live/), sematan (/embed/), dan
    // bentuk lama (/v/) — tanpa ini host lolos deteksi tetapi ekstraksi
    // langsung kosong dan unduhan gagal total tanpa penjelasan.
    private val YT_ID_PATH_RE = Regex("/(?:live|embed|v)/([A-Za-z0-9_-]{11})")
    private val YT_ID_V_RE = Regex("[?&]v=([A-Za-z0-9_-]{11})")
    private val YT_ID_YOUTU_RE = Regex("youtu\\.be/([A-Za-z0-9_-]{11})")
    private val HP_URL_HIGH_RE = Regex("""setVideoUrlHigh\s*\(\s*['"](https?://[^'"]+)['"]""")
    private val HP_URL_LOW_RE = Regex("""setVideoUrlLow\s*\(\s*['"](https?://[^'"]+)['"]""")
    private val HP_TITLE_RE = Regex("""setVideoTitle\s*\(\s*['"](.+?)['"]\s*\)""")
    private val HP_OG_VIDEO_RE = Regex("""<meta[^>]+property\s*=\s*["']og:video["'][^>]+content\s*=\s*["'](https?://[^"']+)["']""")
    private val HP_OG_TITLE_RE = Regex("""<meta[^>]+property\s*=\s*["']og:title["'][^>]+content\s*=\s*["'](.+?)["']""")
    private val HP_TITLE_TAG_RE = Regex("""<title>(.+?)</title>""")
    private val PH_MEDIA_VIDEO_URL_RE = Regex("\"videoUrl\"\\s*:\\s*\"(https?:[^\"]+)\"")
    private val PH_MEDIA_QUALITY_RE = Regex("\"quality\"\\s*:\\s*\"?(\\d+)")
    private val PH_FLASHVARS_Q_RE = Regex("\"quality_(\\d+)p\"\\s*:\\s*\"(https?:[^\"]+)\"")
    private val PH_VIDEO_URL_RE = Regex("\"video_url\"\\s*:\\s*\"(https?:[^\"]+)\"")
    private val HH_RES_TAG_RE = Regex(
        """(?<![0-9])(2160|1440|1080|720|480|360|240)p(?![0-9A-Za-z])""",
        RegexOption.IGNORE_CASE
    )
    private val HH_DIM_TAG_RE = Regex("""(?<![0-9])([0-9]{3,4})x([0-9]{3,4})(?![0-9])""")
    private val HH_HOST_TAG_RE = Regex("""://([^/?#]+)""")
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
        // Varian ".com" tak perlu disebut terpisah (issue #1155):
        // "cdninstagram.com" selalu mengandung "cdninstagram".
        if (lower.contains("cdninstagram")) return false
        if (lower.contains("tiktokcdn")) return false
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

    /** True bila URL adalah halaman YouTube — host ketat memakai
     *  YT_HOST_RE yang sama dengan penentu isSocialMediaUrl dan
     *  dispatch extract, agar server (socialOptions) dan ekstraktor
     *  tak pernah berbeda vonis (issue #828). */
    fun isYouTubeUrl(url: String): Boolean =
        YT_HOST_RE.containsMatchIn(url.lowercase(Locale.ROOT))

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
        // optJSONObject/optString tak pernah melempar (try/catch lama kode
        // mati, issue #1466); objek author yang absen menghasilkan null
        // yang dulu ter-render harfiah "null" ke nama berkas.
        val author = data.optJSONObject("author")?.optString("unique_id", "")
        val id = data.optString("id", "")
        val namePrefix = tikTokNamePrefix(author, id)
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
        // Posting foto/slideshow (issue #1469): field images respons
        // tikwm dulu diabaikan total sehingga tautan foto selalu tanpa
        // hasil; kini menjadi opsi gambar seperti jalur foto situs lain.
        options.addAll(tikTokImageResults(data, namePrefix, title))
        return options
    }

    /** Hasil gambar dari field `images` respons tikwm (murni, issue
     *  #1469): tiap entri http menjadi Result "Photo <n>" bernama
     *  "<prefix>_<n>.jpg"; nomor berurutan atas hasil yang diterima
     *  (entri tak valid tak meninggalkan lubang nomor). */
    internal fun tikTokImageResults(
        data: JSONObject,
        namePrefix: String,
        title: String
    ): List<Result> {
        val images = data.optJSONArray("images") ?: return emptyList()
        val out = mutableListOf<Result>()
        for (i in 0 until images.length()) {
            val img = images.optString(i, "")
            if (!img.startsWith("http")) continue
            val n = out.size + 1
            out.add(Result(img, "${namePrefix}_$n.jpg", title, "Photo $n", "image/jpeg"))
        }
        return out
    }

    /** Awalan nama berkas TikTok (murni, issue #1466): komponen kosong/
     *  absen dibuang sehingga nama tak memuat literal "null" dan tak
     *  meninggalkan underscore ganda; author + id lengkap menghasilkan
     *  bentuk lama "TikTok_<author>_<id>". */
    internal fun tikTokNamePrefix(author: String?, id: String): String =
        listOf("TikTok", author?.trim().orEmpty(), id.trim())
            .filter { it.isNotBlank() }
            .joinToString("_")

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

        // img_index dihitung SEBELUM strategi 2 (issue #1180): strategi
        // itu membutuhkannya untuk memutuskan posisi video (lihat bawah).
        val imgIndex = IG_IMG_INDEX_RE.find(url)?.groupValues?.get(1)?.toIntOrNull()

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
                    val videoResult = Result(videoUrl, "Instagram_${shortcode}.mp4",
                        "Instagram $shortcode", "Video", MIME_MP4, cookies = pageCookies)
                    // Video disisipkan di depan HANYA bila tak ada
                    // img_index (issue #1180): img_index menomori item
                    // carousel, dan sisipan di indeks 0 menggeser nomor
                    // semua foto sesudahnya sehingga pilihan pengguna
                    // meleset satu. Dengan img_index, video ditambahkan
                    // di akhir agar nomor foto hasil pindai halaman —
                    // satu-satunya penomoran yang strategi ini punya —
                    // tetap sejajar posisi carousel untuk foto.
                    if (imgIndex == null) {
                        options.add(0, videoResult)
                    } else {
                        options.add(videoResult)
                    }
                }
            }
        }

        // Dukungan img_index dari URL: pilih item tertentu di carousel
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
        // Satu lintasan pola longgar (issue #1441): pola utama adalah
        // subhimpunan pola cadangan (span sama untuk URL cdninstagram —
        // keduanya rakus sampai kutip penutup), jadi tiap kecocokan
        // diklasifikasikan per string pendeknya: yang memenuhi pola
        // utama masuk keranjang utama, sisanya cadangan. Pilihan akhir
        // persis perilaku lama: utama bila tak kosong, selain itu
        // cadangan — tanpa memindai halaman dua kali.
        val primary = mutableListOf<String>()
        val fallback = mutableListOf<String>()
        IG_IMG_URL_FALLBACK_RE.findAll(html).forEach { match ->
            val raw = unescapePlayer(match.value)
            if (raw.startsWith("http") && !raw.contains("/t51.2885-")) {
                if (IG_IMG_URL_RE.containsMatchIn(match.value)) {
                    primary.add(raw)
                } else {
                    fallback.add(raw)
                }
            }
        }
        val decoded = if (primary.isNotEmpty()) primary else fallback
        // Dedup per ID file (contoh: 774314790_18387324052161_1234):
        // pemenang per ID adalah varian berdimensi terbesar (issue
        // #1414) — tanpa penanda (resolusi penuh) selalu terbesar, seri
        // mempertahankan kemunculan pertama.
        val byFileId = linkedMapOf<String, String>()
        val idRegex = IG_FILE_ID_RE
        decoded.forEach { url ->
            val fid = idRegex.find(url)?.groupValues?.get(1) ?: url
            byFileId[fid] = pickIgVariant(byFileId[fid], url)
        }
        return byFileId.values.take(20).toList()
    }

    /** Luas varian foto Instagram dari penanda sNNNxNNN (issue #1414);
     *  tanpa penanda = resolusi penuh, dinilai terbesar. Dimensi
     *  dijepit agar perkalian tak meluap pada penanda absurd. */
    internal fun igVariantArea(url: String): Long {
        val m = IG_SIZE_MARKER_RE.find(url) ?: return Long.MAX_VALUE
        val w = m.groupValues[1].toLongOrNull() ?: return Long.MAX_VALUE
        val h = m.groupValues[2].toLongOrNull() ?: return Long.MAX_VALUE
        return w.coerceAtMost(1_000_000L) * h.coerceAtMost(1_000_000L)
    }

    /** Pemenang dedup varian per ID berkas (issue #1414): kandidat hanya
     *  menggantikan bila dimensinya lebih besar; seri mempertahankan
     *  yang pertama. Logika lama berbasis substring membiarkan varian
     *  s150 menimpa s640 yang sudah tersimpan. internal agar bisa
     *  di-unit-test. */
    internal fun pickIgVariant(existing: String?, candidate: String): String =
        if (existing == null || igVariantArea(candidate) > igVariantArea(existing)) candidate
        else existing

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
        // Satu lintasan gabungan atas halaman (issue #1441): pola
        // gabungan menemukan kedua format dalam SATU findAll; kandidat
        // format 2 dicoba langsung sesuai urutan halaman, kandidat
        // format 3 ditampung dan dicoba sesudahnya — urutan coba persis
        // perilaku lama (semua format 2 sebelum format 3), string hanya
        // berjalan sekali. SEMUA kecocokan per format tetap dicoba
        // (issue #652).
        val tokenCandidates = mutableListOf<MatchResult>()
        for (match in IG_CONTEXT_OR_TOKEN_RE.findAll(html)) {
            if (match.value.startsWith("contextJSON")) {
                igTokenMedia(html, match)?.let { return it }
            } else {
                tokenCandidates.add(match)
            }
        }
        for (match in tokenCandidates) {
            igTokenMedia(html, match)?.let { return it }
        }
        return null
    }

    /** Coba satu kandidat token format 2/3: regex hanya MELACAK kutip
     *  pembuka; isi token dipindai sadar-escape (issue #635) — tangkapan
     *  regex berhenti di kutip ter-escape pertama dan tak pernah lolos
     *  parse JSON. Mengembalikan objek media bila token memuatnya. */
    private fun igTokenMedia(html: String, match: MatchResult): JSONObject? {
        val raw = scanEscapedJsString(html, match.range.last + 1) ?: return null
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
        // URL adaptif dari client ini bisa langsung di-download (tidak 403).
        // Kedua strategi dibungkus cap tahap (#266): fetch pemblokir tidak
        // patuh withTimeoutOrNull, jadi tanpa cap satu strategi bisa
        // menduduki thread IO jauh melewati batas total 60 detik.
        val (vision, visionPage) = runStageCapped(24_000L) {
            extractYouTubeViaVisionos(videoId, user)
        } ?: (null to null)
        if (vision != null) {
            return listOf(vision)
        }

        // Strategi 2: halaman WEB (ytInitialPlayerResponse) + fallback Piped/Invidious.
        // Halaman yang sudah diunduh strategi VISIONOS untuk visitorData
        // dipasok sebagai prefetchedPage (issue #1429, memasok seam
        // #1413): satu unduhan halaman per ekstraksi bila isinya cukup;
        // strategi halaman tetap mengunduh halaman watch sendiri bila
        // halaman pakai-ulang tak memuat player response.
        return runStageCapped(30_000L) {
            extractYouTubeFromPage(url, videoId, user, prefetchedPage = visionPage)
        } ?: emptyList()
    }

    /** ytInitialPlayerResponse dari HTML halaman: ekstraksi seimbang
     *  lebih dulu, lalu dua regex cadangan. Null bila tak ditemukan. */
    private fun findPlayerResponseJson(pageHtml: String): String? =
        extractBalancedPlayerResponse(pageHtml)
            ?: YT_PLAYER_RESP_RE.find(pageHtml)?.groupValues?.get(1)
            ?: YT_PLAYER_RESP_LAX_RE.find(pageHtml)?.groupValues?.get(1)

    private fun extractYouTubeFromPage(
        url: String,
        videoId: String,
        user: Map<String, String> = emptyMap(),
        prefetchedPage: HttpResult? = null
    ): List<Result> {
        // Awal tahap untuk anggaran probe #824: kedua pemanggil
        // membungkus fungsi ini dengan runStageCapped(30_000L).
        val stageStartNanos = System.nanoTime()
        fun downloadWatchPage(): HttpResult? = httpGetWithCookies(
            "https://www.youtube.com/watch?v=$videoId",
            mapOf(
                "User-Agent" to YT_PAGE_UA,
                "Accept-Language" to YT_LANG
            ) + user,
            timeoutMs = PAGE_TIMEOUT_MS
        )
        // Halaman hasil unduhan strategi sebelumnya dipakai ulang bila
        // diberikan (issue #1413): unduhan ratusan KB itu tak perlu
        // diulang. Bila halaman pakai-ulang tak memuat player response
        // (issue #1429: sumbernya halaman shorts VISIONOS yang bisa
        // bervarian ringkas), halaman watch tetap diunduh sekali sebagai
        // cadangan agar strategi ini tak kehilangan peluang lamanya.
        var httpResult = prefetchedPage ?: downloadWatchPage() ?: return emptyList()
        var playerJson = findPlayerResponseJson(httpResult.body)
        if (playerJson == null && prefetchedPage != null) {
            httpResult = downloadWatchPage() ?: return emptyList()
            playerJson = findPlayerResponseJson(httpResult.body)
        }
        val pageHtml = httpResult.body
        val ytCookies = httpResult.cookies
        val playerJsonText = playerJson ?: return emptyList()

        try {
            val data = JSONObject(playerJsonText)
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
            // Nama aman dihitung SEKALI di luar loop (issue #1178):
            // sanitasi judul yang sama tak perlu diulang per format
            // dan per stream adaptif.
            val safeName = sanitizeFileName(title)

            for (fmt in allFormats) {
                val videoUrl = fmt.optString("url", "")
                if (videoUrl.startsWith("http")) {
                    val quality = fmt.optString("qualityLabel", "Unknown")
                    val mimeType = fmt.optString("mimeType", MIME_MP4)
                    val ext = if (mimeType.contains("webm")) "webm" else "mp4"
                    options.add(Result(videoUrl, "${safeName}.$ext", title, quality, mimeType, cookies = ytCookies))
                }
            }
            // Entri adaptiveFormats dinilai terpisah (issue #537):
            // format video-only di sana dulu dicampur setara dengan
            // formats progresif sehingga ditawarkan sebagai unduhan
            // utuh yang bisu. Kini video-only hanya masuk sebagai
            // pasangan adaptif dengan audio terbaik dari daftar yang
            // sama; tanpa pasangan, ia dilewati. Entri audio-only juga
            // dilewati sebagai opsi mandiri (issue #1324): tempatnya
            // hanya sebagai pasangan adaptif — menawarkannya sebagai
            // unduhan "video" .mp4 yang isinya hanya suara adalah
            // jebakan untuk pengguna.
            if (adaptiveFormats != null) {
                val audioAd = bestAdaptivePair(streamingData).second
                for (i in 0 until adaptiveFormats.length()) {
                    val fmt = adaptiveFormats.optJSONObject(i) ?: continue
                    val videoUrl = fmt.optString("url", "")
                    if (!videoUrl.startsWith("http")) continue
                    val quality = fmt.optString("qualityLabel", "Unknown")
                    val mimeType = fmt.optString("mimeType", MIME_MP4)
                    val ext = if (mimeType.contains("webm")) "webm" else "mp4"
                    if (isVideoOnlyMime(mimeType)) {
                        if (audioAd.isEmpty()) continue
                        options.add(
                            Result(
                                videoUrl, "${safeName}.$ext", title, quality, mimeType,
                                cookies = ytCookies, videoUrl = videoUrl, audioUrl = audioAd
                            )
                        )
                    } else if (!isAudioOnlyMime(mimeType)) {
                        options.add(Result(videoUrl, "${safeName}.$ext", title, quality, mimeType, cookies = ytCookies))
                    }
                }
            }
            // If the page-derived URLs are all n-transformed (403 on the CDN),
            // fall back to Piped instances which return pre-resolved download URLs.
            // Probe 403 berjalan malas dari kandidat teratas (issue
            // #1431, menyempurnakan #805): kandidat terbukti 403 sebelum
            // kandidat sehat pertama disaring keluar; fallback hanya
            // berjalan bila tak ada opsi sehat tersisa. Anggaran probe menyadari
            // SISA tahap (issue #824): cap tahap 30 dtk dikurangi waktu
            // yang sudah terbakar fetch halaman dan cadangan rantai
            // fallback — anggaran tetap 15 dtk bisa membuat total
            // melewati cap dan runStageCapped membuang seluruh hasil
            // tahap termasuk opsi sehat. Sisa terlalu kecil = jangan
            // probe sama sekali; opsi apa adanya lebih baik daripada
            // tahap yang dibuang.
            val elapsedMs = (System.nanoTime() - stageStartNanos) / 1_000_000
            val probeBudgetMs = minOf(
                FORBIDDEN_PROBE_BUDGET_MS,
                STAGE_CAP_MS - elapsedMs - FALLBACK_RESERVE_MS
            )
            val alive = if (probeBudgetMs < MIN_PROBE_BUDGET_MS) options
                else filterForbidden(options, probeBudgetMs)
            if (alive.isEmpty()) {
                val raced = extractYouTubeViaFallbackRace(videoId)
                if (raced.isNotEmpty()) {
                    return raced
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

    /** Hasil fetch VISIONOS lengkap: player terparse (null bila strategi
     *  ini gagal di tahap mana pun) + halaman sumber visitorData, yang
     *  tetap berguna sebagai prefetchedPage strategi halaman walau
     *  player-nya gagal (issue #1429). */
    private data class VisionosFetch(
        val player: VisionosPlayer?,
        val page: HttpResult?
    )

    /** Ambil visitorData dari [pageUrl] lalu POST ke player API
     *  VISIONOS (#281). */
    private fun fetchVisionosPlayer(
        pageUrl: String,
        videoId: String,
        user: Map<String, String>
    ): VisionosFetch {
        // Butuh visitorData + cookies dari halaman agar API tidak LOGIN_REQUIRED.
        val page = httpGetWithCookies(
            pageUrl,
            mapOf(
                "User-Agent" to YT_UA,
                "Accept-Language" to YT_LANG
            ) + user,
            timeoutMs = PAGE_TIMEOUT_MS
        ) ?: return VisionosFetch(null, null)
        val visitor = YT_VISITOR_DATA_RE.find(page.body)?.groupValues?.get(1)
            ?: YT_VISITOR_DATA_LOW_RE.find(page.body)?.groupValues?.get(1)
            ?: return VisionosFetch(null, page)

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
        ) ?: return VisionosFetch(null, page)
        val player = runCatching {
            val obj = JSONObject(json)
            if (obj.optJSONObject("playabilityStatus")?.optString("status") != "OK") {
                return@runCatching null
            }
            val title = obj.optJSONObject("videoDetails")?.optString("title")
                ?: "YouTube_$videoId"
            VisionosPlayer(obj.optJSONObject("streamingData"), title, page.cookies)
        }.getOrNull()
        return VisionosFetch(player, page)
    }

    /** Strategi VISIONOS: player API mengembalikan URL adaptif tanpa
     *  n-signature, sehingga bisa langsung di-download (tidak HTTP 403).
     *  Mengembalikan hasil + halaman sumber visitorData (untuk dipakai
     *  ulang strategi halaman sebagai prefetchedPage, issue #1429). */
    private fun extractYouTubeViaVisionos(
        videoId: String,
        user: Map<String, String> = emptyMap()
    ): Pair<Result?, HttpResult?> {
        val fetch = fetchVisionosPlayer(
            "https://www.youtube.com/shorts/$videoId", videoId, user
        )
        val player = fetch.player ?: return null to fetch.page
        val result = runCatching {
            val title = player.title
            val streamingData = player.streamingData
            val safeName = sanitizeFileName(title)
            // adaptiveFormats langsung — TV client
            // biasanya mengembalikan URL langsung tanpa n-signature.
            val adaptive = streamingData?.optJSONArray("adaptiveFormats")
            if (adaptive != null && adaptive.length() > 0) {
                val (videoAd, audioAd) = bestAdaptivePair(streamingData)
                val best = adaptiveFormatByUrl(adaptive, videoAd)
                if (best != null) {
                    // optString: kunci url kadang absen di respons TV client;
                    // jangan lempar agar fallback adaptif lain tetap jalan.
                    val url = best.optString("url", "")
                    if (url.isEmpty()) return@runCatching null
                    // Pasangan audio WAJIB ada (issue #1412, rasional
                    // #537): format adaptive selalu video-only, jadi
                    // audioAd kosong (audio hanya WebM/opus yang tak bisa
                    // di-mux) akan menghasilkan unduhan bisu yang tampak
                    // utuh — lewati strategi ini, samakan dengan
                    // penolakan jalur halaman.
                    if (audioAd.isEmpty()) return@runCatching null
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
        return result to fetch.page
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
     *  Probe HEAD kini MALAS (issue #1431): kandidat dinilai dari
     *  teratas dan probe BERHENTI pada kandidat pertama yang tidak
     *  403 — sebelumnya semua opsi (30-45 request) di-probe paralel
     *  padahal hanya satu opsi yang akhirnya diunduh. Kandidat yang
     *  terbukti 403 sebelum kandidat sehat itu dibuang; kandidat
     *  sesudahnya dipertahankan TANPA probe (status tak diketahui,
     *  perlakuan yang sama seperti probe gagal/timeout selama ini:
     *  hanya 403 yang terbukti yang dibuang, sejalan isUrlForbidden
     *  yang getOrDefault false). [budgetMs] dihitung pemanggil dari
     *  SISA waktu tahapnya (issue #824), bukan konstanta buta: tahap
     *  halaman dicap 30 dtk oleh runStageCapped dan fetch halaman
     *  sendiri bisa memakan belasan detik; anggaran habis di tengah
     *  probe = sisa kandidat dipertahankan apa adanya. */
    private fun filterForbidden(options: List<Result>, budgetMs: Long): List<Result> =
        filterForbiddenProbe(options, budgetMs, { System.nanoTime() }) { url, cookies ->
            isUrlForbidden(url, cookies)
        }

    /** Inti [filterForbidden] dengan probe + jam yang disuntik agar
     *  bisa di-unit-test tanpa jaringan. Sesudah video kandidat lolos,
     *  audioUrl pasangan adaptifnya ikut di-probe (issue #1467): audio
     *  yang terbukti 403 membuang pasangan sama seperti video 403 —
     *  sebelumnya pasangan bisa terkunci dengan video sehat dan audio
     *  mati, kegagalannya baru ketahuan saat unduhan audio berjalan.
     *  Probe tetap malas: hanya kandidat yang akan dikunci yang
     *  audionya diperiksa, dan hanya selama anggaran belum habis
     *  (anggaran habis = kandidat dipertahankan apa adanya). */
    internal fun filterForbiddenProbe(
        options: List<Result>,
        budgetMs: Long,
        nanoTime: () -> Long,
        isForbidden: (String, String) -> Boolean
    ): List<Result> {
        if (options.isEmpty()) return options
        val deadline = nanoTime() + budgetMs * 1_000_000L
        val kept = ArrayList<Result>(options.size)
        var confirmedAlive = false
        for (opt in options) {
            if (confirmedAlive) {
                kept.add(opt)
                continue
            }
            if (nanoTime() >= deadline) {
                kept.add(opt)
                continue
            }
            if (isForbidden(opt.directUrl, opt.cookies)) {
                // Terbukti 403: buang, lanjut probe kandidat berikutnya.
                continue
            }
            if (opt.audioUrl.isNotBlank() && nanoTime() < deadline &&
                isForbidden(opt.audioUrl, opt.cookies)
            ) {
                // Audio pasangan terbukti 403: buang pasangan, lanjut
                // probe kandidat berikutnya (issue #1467).
                continue
            }
            kept.add(opt)
            confirmedAlive = true
        }
        return kept
    }

    private fun isUrlForbidden(url: String, cookies: String): Boolean {
        return runCatching {
            val conn = openTlsConn(url)
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
                if (cookies.isNotEmpty()) conn.setRequestProperty("Cookie", cookies)
                conn.responseCode == 403
            } finally { conn.disconnect() }
        }.getOrDefault(false)
    }

    /** Kendali berhenti balapan fallback (issue #1448): Future.cancel(true)
     *  saja tidak menghentikan tugas yang kalah — tidak ada pemeriksaan
     *  interupsi di jalur ini dan I/O HttpURLConnection yang memblokir
     *  tidak menanggapi interupsi thread, jadi tugas kalah terus
     *  mengiterasi instansnya dan menduduki stageExecutor jauh melampaui
     *  anggaran balapan. Kontrol ini adalah registry bersama per balapan:
     *  tiap koneksi yang dibuka tugas balapan didaftarkan; [stop]
     *  membalik bendera (diperiksa tiap iterasi instans + tiap hop) DAN
     *  men-disconnect semua koneksi terdaftar — disconnect-lah yang
     *  menembus read/connect yang sedang memblokir. */
    internal class FallbackRaceControl(
        // Seam uji (issue #1482): disconnect pada koneksi yang belum
        // terhubung tak teramati di JVM, jadi uji menyuntik pencatat
        // untuk mengunci perilaku inti #1448 (stop MEMUTUS koneksi),
        // bukan hanya bendera stopped. Produksi memakai disconnect()
        // sungguhan persis seperti sebelumnya.
        private val disconnectAction: (HttpURLConnection) -> Unit = { it.disconnect() }
    ) {
        @Volatile
        var stopped: Boolean = false
            private set

        private val connections: MutableSet<HttpURLConnection> =
            Collections.newSetFromMap(ConcurrentHashMap<HttpURLConnection, Boolean>())

        fun register(conn: HttpURLConnection) {
            connections.add(conn)
            // Balapan sudah berhenti sebelum koneksi ini sempat
            // terdaftar: putuskan segera agar tak menggantung.
            if (stopped) runCatching { disconnectAction(conn) }
        }

        fun unregister(conn: HttpURLConnection) {
            connections.remove(conn)
        }

        fun stop() {
            stopped = true
            for (conn in connections) runCatching { disconnectAction(conn) }
        }
    }

    /** Balapan fallback lapis ketiga (issue #1430): Piped, Invidious,
     *  dan Cobalt dicoba KONKUREN — satu tugas per layanan di
     *  stageExecutor (pola yang sama dengan probe filterForbidden);
     *  tiap layanan tetap mengiterasi instansnya sendiri secara
     *  sekuensial. Sebelumnya rantai sekuensial 5+6+1 instans bisa
     *  membayar belasan timeout berurutan sebelum menyerah.
     *  Hasil sah PERTAMA yang tiba menang dan tugas lain dibatalkan;
     *  bila beberapa hasil tiba pada sapuan periksa yang sama, urutan
     *  prioritas Piped > Invidious > Cobalt yang menentukan. Balapan
     *  dibatasi FALLBACK_RACE_BUDGET_MS; cap tahap pemanggil tetap
     *  menjadi pagar terluar. */
    private fun extractYouTubeViaFallbackRace(videoId: String): List<Result> {
        // Satu kontrol per balapan (issue #1448): pemenang/anggaran habis
        // menghentikan yang kalah sungguhan lewat stop() — bukan hanya
        // cancel(true) yang tak menembus I/O memblokir.
        val race = FallbackRaceControl()
        val racers: List<() -> List<Result>> = listOf(
            { extractYouTubeViaPiped(videoId, race) },
            { extractYouTubeViaInvidious(videoId, race)?.let { listOf(it) } ?: emptyList() },
            { extractYouTubeViaCobalt(videoId, race)?.let { listOf(it) } ?: emptyList() }
        )
        // Tipe ditulis EKSPLISIT penuh di blok ini (perbaikan
        // kompilasi): submit(Callable<T>) di dalam map + try/catch
        // tanpa tipe harapan dari konteks membuat inferensi generik
        // kompilator runtuh (NewConstraintError atas Future<T>),
        // sehingga isDone/get/cancel ikut tak ter-resolve.
        val futures = ArrayList<Future<List<Result>>?>(racers.size)
        for (racer in racers) {
            val future: Future<List<Result>>? = try {
                stageExecutor.submit(
                    Callable<List<Result>> {
                        runCatching { racer() }.getOrDefault(emptyList<Result>())
                    }
                )
            } catch (_: Exception) {
                null
            }
            futures.add(future)
        }
        fun stopLosers() {
            race.stop()
            for (other in futures) other?.cancel(true)
        }
        val deadline = System.nanoTime() + FALLBACK_RACE_BUDGET_MS * 1_000_000L
        val settled = BooleanArray(futures.size) { futures[it] == null }
        var settledCount = settled.count { it }
        while (System.nanoTime() < deadline) {
            // Sapuan prioritas: periksa dari prioritas tertinggi, jadi
            // hasil yang tiba bersamaan dimenangkan prioritasnya; hasil
            // sah pertama pada sapuan mana pun langsung menang.
            for (i in futures.indices) {
                val f: Future<List<Result>> = futures[i] ?: continue
                if (settled[i] || !f.isDone) continue
                settled[i] = true
                settledCount++
                val result: List<Result> = try {
                    f.get()
                } catch (_: Exception) {
                    emptyList<Result>()
                }
                if (result.isNotEmpty()) {
                    stopLosers()
                    return result
                }
            }
            if (settledCount >= futures.size) return emptyList()
            try {
                Thread.sleep(FALLBACK_RACE_POLL_MS)
            } catch (_: InterruptedException) {
                stopLosers()
                return emptyList()
            }
        }
        stopLosers()
        return emptyList()
    }

    // Urutan = urutan coba; instans terverifikasi hidup didahulukan.
    // Probe 2026-11-08 (issue #1326): projectsegfau.lt menjawab 200,
    // adminforge.de 301 (hidup); kavin.rocks menjawab 526 dan instans
    // utamanya memang sudah ditutup — dibuang dari daftar. leptons.xyz
    // (502 sesaat), founderweb.com, dan api.piped.yt (tak terjangkau
    // dari jaringan probe) dipertahankan: satu probe tak cukup untuk
    // memvonis mati dan cap tahap membatasi biaya instans yang gagal.
    private val PIPED_INSTANCES = listOf(
        "pipedapi.in.projectsegfau.lt/streams/",
        "pipedapi.adminforge.de/streams/",
        "pipedapi.leptons.xyz/streams/",
        "pipedapi.video.founderweb.com/streams/",
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
    /** Mime audio murni: entri adaptiveFormats berjenis ini hanya
     *  berguna sebagai pasangan video adaptif (bestAdaptivePair). */
    internal fun isAudioOnlyMime(mimeType: String): Boolean =
        mimeType.startsWith("audio/")

    internal fun isVideoOnlyMime(mimeType: String): Boolean {
        if (!mimeType.startsWith("video/")) return false
        val codecs = mimeType.substringAfter("codecs=", "").trim().trim('"')
        if (codecs.isEmpty()) return false
        return listOf("mp4a", "opus", "vorbis", "aac", "mp3", "ac-3", "ec-3")
            .none { codecs.contains(it, ignoreCase = true) }
    }

    private fun extractYouTubeViaPiped(
        videoId: String,
        race: FallbackRaceControl? = null
    ): List<Result> {
        for (instance in PIPED_INSTANCES) {
            // Bendera berhenti balapan (issue #1448): tugas yang kalah
            // berhenti di instans berikutnya, bukan mengiterasi semua.
            if (race?.stopped == true) return emptyList()
            val result = runCatching {
                val http = httpGetWithCookies(
                    "https://$instance$videoId",
                    mapOf("User-Agent" to API_UA, "Accept" to "application/json"),
                    timeoutMs = 4000,
                    race = race
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
                val safeName = sanitizeFileName(title)
                for (i in 0 until streams.length()) {
                    val s = streams.optJSONObject(i) ?: continue
                    val u = s.optString("url", "")
                    if (u.startsWith("http")) {
                        val mime = s.optString("mimeType", MIME_MP4)
                        val ext = if (mime.contains("webm")) "webm" else "mp4"
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

    private fun extractYouTubeViaInvidious(
        videoId: String,
        race: FallbackRaceControl? = null
    ): Result? {
        for (instance in INVIDIOUS_INSTANCES) {
            if (race?.stopped == true) return null
            val resolved = resolveInvidiousLatest(instance, videoId, "18", race)
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

    private fun extractYouTubeViaCobalt(
        videoId: String,
        race: FallbackRaceControl? = null
    ): Result? {
        val watchUrl = "https://www.youtube.com/watch?v=$videoId"
        val body = """{"url":"$watchUrl","filenameStyle":"pretty","downloadMode":"auto"}"""
        for (instance in COBALT_INSTANCES) {
            if (race?.stopped == true) return null
            val resp = httpPostJson(
                "$instance/",
                body,
                mapOf("User-Agent" to API_UA, "Accept" to "application/json"),
                timeoutMs = 15000,
                race = race
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
    private fun resolveInvidiousLatest(
        instance: String,
        videoId: String,
        itag: String,
        race: FallbackRaceControl? = null
    ): String? {
        val start = "https://$instance/latest_version?id=$videoId&itag=$itag"
        var current = start
        for (i in 0 until 6) {
            if (race?.stopped == true) return null
            try {
                val conn = openTlsConn(current)
                race?.register(conn)
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
                } finally {
                    race?.unregister(conn)
                    runCatching { conn.disconnect() }
                }
            } catch (_: Exception) {
                // Pengecualian jaringan: instance ini gagal — jangan
                // ulang URL yang sama; serahkan failover ke pemanggil.
                return null
            }
        }
        return null
    }

    // Internal agar bentuk-bentuk ID teruji langsung (pola parser murni).
    internal fun extractYouTubeId(url: String): String? {
        YT_ID_SHORTS_RE.find(url)
            ?.groupValues?.get(1)?.let { return it }
        YT_ID_V_RE.find(url)
            ?.groupValues?.get(1)?.let { return it }
        YT_ID_YOUTU_RE.find(url)
            ?.groupValues?.get(1)?.let { return it }
        YT_ID_PATH_RE.find(url)
            ?.groupValues?.get(1)?.let { return it }
        return null
    }

    private fun sanitizeFileName(name: String): String {
        // FileNames.safe menangani slash/trim/nama kosong; aturan ketat sosmed lanjut di sini.
        val cleaned = FileNames.safe(name).replace(SANITIZE_BAD_CHARS_RE, "_")
            .replace(SANITIZE_WS_RE, "_")
        // Potongan 80 lewat safeCut (issue #1206): take(80) polos bisa
        // membelah pasangan surrogate tepat di batas, menyisakan
        // surrogate yatim di nama berkas (FileNames.safe sendiri sudah
        // sadar-pasangan sejak #941; potongan terakhir ini yang polos).
        return cleaned.take(FileNames.safeCut(cleaned, minOf(80, cleaned.length)))
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
        title = FileNames.take(unescapePlayer(title.trim()), 120)
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
        if (options.isEmpty()) {
            val ogVideo = HP_OG_VIDEO_RE.find(html)?.groupValues?.get(1)?.let(::unescapePlayer)
            if (!ogVideo.isNullOrEmpty() && ogVideo.startsWith("http")) {
                if (!ogVideo.contains(".m3u8")) {
                    options.add(Result(ogVideo, "${namePrefix}_${safeName}.mp4", title, "Video", MIME_MP4))
                }
            }
        }
        return options
    }

    /** Unescape URL/judul dari JS/HTML (`\\/`, `\\u0026`, `&amp;`) — dipakai
     *  bersama ekstraktor Instagram & Pornhub/XNXX agar satu definisi. */
    private fun unescapePlayer(raw: String): String {
        return decodeUnicodeEscapes(raw.replace("\\/", "/"))
            .replace("&amp;", "&")
    }

    /** Unescape token JSON ber-escape (`\\u002F`, `\\u0026`, `\\"`, `\\/`)
     *  untuk `contextJSON`/`token` Instagram — satu definisi untuk 3 format.
     *  Internal agar logika murni ini teruji langsung (Murni). */
    internal fun unescapeJsToken(raw: String): String {
        return decodeUnicodeEscapes(raw)
            .replace("\\\"", "\"")
            .replace("\\/", "/")
    }

    private val UNICODE_ESCAPE_RE = Regex("\\\\u([0-9a-fA-F]{4})")

    // Dekode escape unicode JSON/JS secara umum (issue #1325):
    // penggantian harfiah lama hanya mengenal dua kode dalam ejaan
    // huruf besar, padahal heksa JSON tak peka huruf — encoder yang
    // memancarkan ejaan huruf kecil membiarkan escape-nya utuh di
    // URL/token hasil. Pola yang sama seperti unescapeFb (#283).
    // Internal agar teruji langsung.
    internal fun decodeUnicodeEscapes(raw: String): String =
        UNICODE_ESCAPE_RE.replace(raw) { m ->
            m.groupValues[1].toInt(16).toChar().toString()
        }

    // ── Pornhub ─────────────────────────────────────────────────────
    // Halaman watch (`/view_video.php?viewkey=...`) memuat varian di JSON
    // `mediaDefinitions` (`"videoUrl"` + `"quality"`) dan fallback `flashvars`
    // (`"quality_720p"`, `"video_url"`). Unduhan langsung yang sebelumnya hanya
    // menyimpan halaman HTML kini diekstrak jadi MP4 seperti XNXX/XVideos.

    private val PH_HEADERS = mapOf(
        "User-Agent" to DEFAULT_UA,
        "Accept" to "text/html,application/xhtml+xml",
        "Referer" to "https://www.pornhub.com/"
    )

    private fun extractPornhub(url: String, user: Map<String, String> = emptyMap()): Result? =
        preferProgressiveMp4(extractAllPornhub(url, user))

    /** MP4 progresif diutamakan.
     *  Opsi sudah urut kualitas menurun sehingga MP4 pertama = MP4 terbaik. */
    internal fun preferProgressiveMp4(options: List<Result>): Result? {
        return options.firstOrNull()
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
        title = FileNames.take(unescapePlayer(title.trim()), 120)
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
        // Kandidat manifes (m3u8) dibuang: aplikasi hanya mengunduh
        // yang ditawarkan hanya berkas MP4 progresif langsung.
        val usedLabels = mutableMapOf<String, Int>()
        return found.entries.sortedByDescending { it.value }
            .filter { (url, _) -> !url.contains(".m3u8") }
            .map { (url, quality) ->
                // Label selalu memuat tinggi aktual (issue #1461):
                // label "HD" bersama untuk semua >= 720 membuat 720p
                // dan 1080p tak terbedakan di daftar opsi maupun nama
                // berkas.
                val baseLabel = when {
                    quality > 0 -> "${quality}p"
                    else -> "Video"
                }
                // Tabrakan label dalam daftar yang sama (issue #1491):
                // dua URL berbeda bisa berkualitas sama (lintasan
                // mediaDefinitions vs flashvars, atau cadangan generik
                // kualitas-0) — label + nama berkas adalah fungsi murni
                // kualitas, jadi tanpa penomoran keduanya persis sama
                // dan tak terbedakan. Kemunculan ke-2 dst diberi
                // akhiran -2/-3, pola penomoran #1465 di HentaiHaven.
                val n = (usedLabels[baseLabel] ?: 0) + 1
                usedLabels[baseLabel] = n
                val label = if (n > 1) "$baseLabel-$n" else baseLabel
                Result(url, "Pornhub_${safeName}_${label}.mp4", title, label, MIME_MP4, cookies = cookies)
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
        return options.firstOrNull()
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
        title = FileNames.take(unescapePlayer(title.trim()), 120)
        if (title.isBlank()) title = "HentaiHaven_video"
        val safeName = sanitizeFileName(title)
        val candidates = mutableListOf<String>()
        fun addCandidate(raw: String?) {
            val url = cleanHentaiHavenUrl(raw) ?: return
            // Kandidat manifes (m3u8) dibuang: aplikasi hanya mengunduh
            // yang ditawarkan hanya berkas media langsung.
            if (url.contains(".m3u8")) return
            candidates.add(url)
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
            .filter { url -> url.startsWith("http") && seen.add(url) }
        if (distinct.size <= 1) {
            return distinct.map { url ->
                Result(url, "HentaiHaven_${safeName}.mp4", title, "Video", MIME_MP4)
            }
        }
        // Lebih dari satu rendisi (issue #1465): dulu semua kandidat
        // bernama + berlabel persis sama sehingga tak terbedakan di
        // daftar opsi dan bisa saling menimpa nama berkas. Beri pembeda
        // rendisi dari URL (resolusi, lalu host); tag yang bertabrakan
        // diberi nomor urut. Kandidat tunggal tak berubah (terkunci uji).
        val usedTags = mutableMapOf<String, Int>()
        return distinct.map { url ->
            val base = hhRenditionTag(url)
            val n = (usedTags[base] ?: 0) + 1
            usedTags[base] = n
            val tag = if (n > 1) "$base-$n" else base
            Result(url, "HentaiHaven_${safeName}_$tag.mp4", title, "Video $tag", MIME_MP4)
        }
    }

    /** Pembeda rendisi kandidat HentaiHaven dari URL-nya (murni):
     *  pola resolusi baku "<n>p", lalu pola WxH (pakai tinggi), lalu
     *  host (port ':' menjadi '_' agar aman di nama berkas). */
    internal fun hhRenditionTag(url: String): String {
        HH_RES_TAG_RE.find(url)?.let { return it.groupValues[1] + "p" }
        HH_DIM_TAG_RE.find(url)?.let { return it.groupValues[2] + "p" }
        val host = HH_HOST_TAG_RE.find(url)?.groupValues?.get(1) ?: "cdn"
        return host.replace(':', '_')
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
            out.add(EpisodeLink(url, FileNames.take(obj.optString("title").trim(), 80)))
        }
        return out
    }

    // ── Twitter/X ────────────────────────────────────────────────────────

    private fun extractTwitter(url: String): Result? {
        val options = extractAllTwitter(url)
        return options.firstOrNull()
    }

    private fun extractAllTwitter(url: String): List<Result> {
        // Deteksi rute menerima x.com TANPA skema (X_URL_RE membuat
        // skema opsional), tetapi URL() melempar tanpa skema dan
        // pengecualiannya tertelan pemanggil menjadi daftar kosong —
        // tempelan "x.com/user/status/..." selalu gagal diam-diam
        // (issue #1177). Samakan di pintu masuk: beri skema bila
        // belum ada, petakan juga varian www.x.com seperti detektor.
        val withScheme = if (url.startsWith("http://", ignoreCase = true) ||
            url.startsWith("https://", ignoreCase = true)
        ) {
            url
        } else {
            "https://" + url
        }
        val cleanUrl = withScheme
            .replace("https://www.x.com/", "https://twitter.com/")
            .replace("https://x.com/", "https://twitter.com/")
        val path = URL(cleanUrl).path
        val json = httpGet("https://api.vxtwitter.com/twitter$path") ?: return emptyList()
        val obj = JSONObject(json)
        val tweet = obj.optJSONObject("tweet") ?: return emptyList()
        val user = tweet.optJSONObject("user")?.optString("name") ?: "Twitter"
        val text = tweet.optString("text", "")
        val media = tweet.optJSONArray("media") ?: return emptyList()
        val options = mutableListOf<Result>()
        // Sanitasi nama pengguna sekali di luar loop (issue #1205,
        // kelas #1178): inputnya sama untuk semua item media.
        val safeUser = sanitizeFileName(user)

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
                        options.add(Result(directUrl, "Twitter_${safeUser}$suffix.mp4", text, "Video", MIME_MP4))
                    }
                }
                "photo" -> {
                    val directUrl = item.optString("url")
                    if (directUrl.startsWith("http")) {
                        options.add(Result(directUrl, "Twitter_${safeUser}$suffix.jpg", text, "Photo", "image/jpeg"))
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
            // Henti-dini HD (issue #1439): HD adalah pilihan pertama
            // pemanggil — begitu satu URL HD sah terkumpul dari lintasan
            // HD, sisa lintasan (dan pindai .mp4 di bawah) tak mengubah
            // pilihan itu, jadi halaman utuh tak dipindai 7x percuma.
            // Urutan kandidat HD-dulu menjaga firstOrNull{HD} identik.
            if (label == "HD" && found.values.any { it == "HD" }) break
        }
        // Cadangan: URL mp4 langsung yang diapit tanda kutip di halaman.
        // Hanya bila belum ada HD — temuan HD dari sini pun tak akan
        // menggeser HD regex yang sudah terkumpul lebih dulu.
        if (found.values.none { it == "HD" }) {
            extractFacebookMp4Urls(html).forEach { mediaUrl ->
                val q = if (mediaUrl.contains("quality=hd") || mediaUrl.contains("_hd")) "HD" else "SD"
                found.putIfAbsent(mediaUrl, q)
            }
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

    private fun httpGetWithCookies(urlStr: String, headers: Map<String, String> = emptyMap(), timeoutMs: Int = 15000, race: FallbackRaceControl? = null): HttpResult? {
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
            if (race?.stopped == true) return null
            val remainingMs = chainDeadline -
                android.os.SystemClock.elapsedRealtime()
            if (remainingMs <= 0L) return null
            val conn = openTlsConn(current)
            race?.register(conn)
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
            } catch (_: Exception) { return null } finally {
                race?.unregister(conn)
                conn.disconnect()
            }
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
        timeoutMs: Int = 15000,
        race: FallbackRaceControl? = null
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
            if (race?.stopped == true) return null
            val remainingMs = chainDeadline -
                android.os.SystemClock.elapsedRealtime()
            if (remainingMs <= 0L) return null
            val conn = openTlsConn(current)
            race?.register(conn)
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
            } catch (_: Exception) { return null } finally {
                race?.unregister(conn)
                conn.disconnect()
            }
        }
        return null
    }
}
