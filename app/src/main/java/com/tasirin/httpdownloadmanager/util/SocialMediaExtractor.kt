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
    private val X_URL_RE = Regex("""(?:^|https?://)(?:www\.)?x\.com/""")
    /** Regex presisi untuk deteksi domain — mencegah false-positive (mis. notyoutube.com) */
    private val YT_HOST_RE = Regex("""(?:https?://)(?:www\.|m\.|music\.)?youtube\.com/|(?:https?://)youtu\.be/""")
    private val TT_HOST_RE = Regex("""(?:https?://)(?:www\.|m\.)?tiktok\.com/|(?:https?://)vm\.tiktok\.com/""")
    private val IG_HOST_RE = Regex("""(?:https?://)(?:www\.)?instagram\.com/(?:p|reel|tv)/|(?:https?://)instagr\.am/(?:p|reel)/""")
    private val TW_HOST_RE = Regex("""(?:https?://)(?:www\.)?twitter\.com/""")
    private val IG_BROAD_HOST_RE = Regex("""(?:https?://)(?:www\.)?instagram\.com/|(?:https?://)instagr\.am/""")
    private val XV_HOST_RE = Regex("""(?:https?://)(?:www\.|m\.|mobile\.)?xvideos\.com/""")
    private val XN_HOST_RE = Regex("""(?:https?://)(?:www\.|m\.|mobile\.)?xnxx\.com/|(?:https?://)(?:www\.)?xnxxvideos\.me/""")
    private val PH_HOST_RE = Regex("""(?:https?://)(?:www\.|m\.|mobile\.)?pornhub\.com/|(?:https?://)(?:www\.)?pornhubpremium\.com/""")
    /** HentaiHaven: halaman watch (`/watch/<slug>/`) dilindungi challenge
     *  Cloudflare sehingga fetch server pasti 403; ekstraksi memakai WebView
     *  (`WebExtractActivity`) + parser murni di bawah sebagai fallback. */
    // audit-ignore: maintenance_marker (nama domain resmi situs, bukan marker)
    private val HH_HOST_RE = Regex("""(?:https?://)(?:www\.)?hentaihaven\.xxx/""")

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
    private val IG_CONTEXT_JSON_RE = Regex("contextJSON\\s*=\\s*\"(.+?)\"")
    private val IG_TOKEN_RE = Regex("\"token\"\\s*:\\s*\"(.+?)\"")
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
    private val PH_MEDIA_DEF_RE = Regex("\"videoUrl\"\\s*:\\s*\"(https?:[^\"]+)\"[^}]*?\"quality\"\\s*:\\s*\"?(\\d+)")
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

    fun isSocialMediaUrl(url: String): Boolean {
        val lower = url.lowercase()
        // Jalur cepat: URL file langsung (kasus umum) gugur tanpa regex.
        if (!lower.contains("tiktok.com") && !lower.contains("instagram.com") &&
            !lower.contains("twitter.com") && !lower.contains("x.com/") &&
            !lower.contains("youtube.com") && !lower.contains("youtu.be") &&
            !lower.contains("instagr.am") && !lower.contains("xvideos.com") &&
            !lower.contains("xnxx.com") && !lower.contains("xnxxvideos.me") &&
            !lower.contains("pornhub.com") && !lower.contains("pornhubpremium.com") &&
            !lower.contains("hentaihaven")
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
                HH_HOST_RE.containsMatchIn(lower)
    }

    /** True bila URL adalah halaman HentaiHaven (butuh ekstraksi WebView). */
    fun isHentaiHavenUrl(url: String): Boolean =
        HH_HOST_RE.containsMatchIn(url.lowercase())

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
            val lower = url.lowercase()
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
                val lower = url.lowercase()
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
        var obj: JSONObject? = null
        for (host in hosts) {
            val json = httpGet("$host/api/?url=$encoded&hd=1") ?: continue
            val parsed = runCatching { JSONObject(json) }.getOrNull() ?: continue
            if (parsed.optInt("code", -1) == 0) {
                obj = parsed
                break
            }
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
            val raw = match.value
                .replace("\\u002F", "/")
                .replace("\\u0026", "&")
                .replace("\\/", "/")
                .replace("&amp;", "&")
            if (raw.startsWith("http") && !raw.contains("/t51.2885-")) {
                decoded.add(raw)
            }
        }
        if (decoded.isEmpty()) {
            // Strategi cadangan: pola CDN lain yang belum tertangkap di atas
            val fallbackRegex = IG_IMG_URL_FALLBACK_RE
            fallbackRegex.findAll(html).forEach { match ->
                val raw = match.value
                    .replace("\\u002F", "/")
                    .replace("\\u0026", "&")
                    .replace("\\/", "/")
                    .replace("&amp;", "&")
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
        val unescaped = raw
            .replace("\\u002F", "/")
            .replace("\\u0026", "&")
            .replace("\\/", "/")
            .replace("&amp;", "&")
        val videoRegex = IG_VIDEO_URL_RE
        val match = videoRegex.find(unescaped) ?: return null
        return match.groupValues[1]
            .replace("\\u002F", "/")
            .replace("\\u0026", "&")
            .replace("&amp;", "&")
    }

    private fun extractContextJson(html: String): JSONObject? {
        // Format 1 (embed page): contextJSON":"{escaped JSON}"
        val embedKey = "contextJSON\":\""
        val embedIdx = html.indexOf(embedKey)
        if (embedIdx >= 0) {
            val start = embedIdx + embedKey.length
            var i = start
            while (i < html.length) {
                val c = html[i]
                if (c == '\\' && i + 1 < html.length) { i += 2; continue }
                if (c == '"') break
                i++
            }
            if (i > start) {
                val token = html.substring(start, i)
                    .replace("\\u002F", "/")
                    .replace("\\u0026", "&")
                    .replace("\\\"", "\"")
                    .replace("\\/", "/")
                    .replace("\\\\", "\\")
                try {
                    val obj = JSONObject(token)
                    val gql = obj.optJSONObject("gql_data")
                    val media = gql?.optJSONObject("shortcode_media")
                    if (media != null) return media
                    val context = obj.optJSONObject("context")
                    val ctxMedia = context?.optJSONObject("media")
                    if (ctxMedia != null) return ctxMedia
                } catch (_: Exception) { /* lanjut */ }
            }
        }
        // Format 2: contextJSON = "..." (JS assignment)
        // Format 3: "token": "..."
        for (pattern in listOf(
            IG_CONTEXT_JSON_RE,
            IG_TOKEN_RE,
        )) {
            val match = pattern.find(html) ?: continue
            val token = match.groupValues[1]
                .replace("\\u002F", "/")
                .replace("\\u0026", "&")
                .replace("\\\"", "\"")
                .replace("\\/", "/")
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
            } catch (_: Exception) { /* lanjut */ }
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
                        results.add(Result(cv, "Instagram_${shortcode}.mp4", "Instagram $shortcode", "Video", MIME_MP4, cookies = cookies))
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
        val vision = extractYouTubeViaVisionos(videoId, user)
        if (vision != null) {
            return listOf(vision)
        }

        // Strategi 2: halaman WEB (ytInitialPlayerResponse) + fallback Piped/Invidious.
        return extractYouTubeFromPage(url, videoId, user)
    }

    /** Ekstrak URL non-HLS dari YouTube: halaman WEB langsung + fallback Piped/Invidious.
     *  Dipanggil bila VISIONOS HLS gagal (media playlist butuh pot token). */
    suspend fun extractNonHlsYouTube(url: String, headers: String = ""): Result? = withContext(Dispatchers.IO) {
        try {
            val videoId = extractYouTubeId(url) ?: return@withContext null
            val user = parseUserHeaders(headers)
            // Strategi 0: coba adaptiveFormats dari VISIONOS (URL langsung tanpa HLS)
            val visionAdaptive = extractYouTubeViaVisionosAdaptive(videoId, user)
            if (visionAdaptive != null) return@withContext visionAdaptive
            // Strategi 1: halaman WEB + Piped/Invidious/Cobalt
            val results = extractYouTubeFromPage(url, videoId, user)
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
            val title = data.optString("title", "YouTube_$videoId")
            val streamingData = data.optJSONObject("streamingData") ?: return emptyList()
            // Baca both formats (muxed) AND adaptiveFormats (separate video/audio)
            val muxedFormats = streamingData.optJSONArray("formats")
            val adaptiveFormats = streamingData.optJSONArray("adaptiveFormats")
            val allFormats = mutableListOf<JSONObject>()
            muxedFormats?.let { for (i in 0 until it.length()) it.optJSONObject(i)?.let { f -> allFormats.add(f) } }
            adaptiveFormats?.let { for (i in 0 until it.length()) it.optJSONObject(i)?.let { f -> allFormats.add(f) } }
            if (allFormats.isEmpty()) return emptyList()
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
            // If the page-derived URLs are all n-transformed (403 on the CDN),
            // fall back to Piped instances which return pre-resolved download URLs.
            if (options.isEmpty() || options.firstOrNull()?.let { isUrlForbidden(it) } == true) {
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
            }
            return options
        } catch (_: Exception) {
            // Gagal mem-parse halaman/response YouTube — biarkan fallback lain lanjut.
        }
        return emptyList()
    }

    /** Strategi VISIONOS: player API mengembalikan URL HLS/adaptif tanpa
     *  n-signature, sehingga bisa langsung di-download (tidak HTTP 403). */
    private fun extractYouTubeViaVisionos(
        videoId: String,
        user: Map<String, String> = emptyMap()
    ): Result? {
        // Butuh visitorData + cookies dari halaman agar API tidak LOGIN_REQUIRED.
        val page = httpGetWithCookies(
            "https://www.youtube.com/shorts/$videoId",
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
            append("\"userAgent\":\" + VISIONOS_UA + \",")
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
            val title = obj.optJSONObject("videoDetails")?.optString("title") ?: "YouTube_$videoId"
            val streamingData = obj.optJSONObject("streamingData")
            val safeName = sanitizeFileName(title)
            // Strategi 1: HLS manifest — cobalah dulu untuk kualitas terbaik.
            val hls = streamingData?.optString("hlsManifestUrl")
            if (!hls.isNullOrEmpty()) {
                // Audio CDN HLS sering di-404 YouTube; siapkan url audio/video dari
                // adaptiveFormats sebagai cadangan agar hasil akhir tetap bersuara.
                val (videoAd, audioAd) = bestAdaptivePair(streamingData)
                return@runCatching Result(
                    hls, "YouTube_$safeName.ts", title, "HLS", "application/x-mpegURL",
                    cookies = page.cookies, isHls = true,
                    videoUrl = videoAd, audioUrl = audioAd
                )
            }
            // Strategi 2: adaptiveFormats langsung (tanpa HLS) — TV client
            // biasanya mengembalikan URL langsung tanpa n-signature.
            val adaptive = streamingData?.optJSONArray("adaptiveFormats")
            if (adaptive != null && adaptive.length() > 0) {
                // Pilih varian video terbaik (prioritas: 720p/1080p AVC)
                val candidates = mutableListOf<JSONObject>()
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
                        cookies = page.cookies, videoUrl = url, audioUrl = audioAd
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
        val page = httpGetWithCookies(
            "https://www.youtube.com/watch?v=$videoId",
            mapOf(
                "User-Agent" to YT_UA,
                "Accept-Language" to YT_LANG
            ),
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
            append("\"userAgent\":\" + VISIONOS_UA + \",")
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
            val title = obj.optJSONObject("videoDetails")?.optString("title") ?: "YouTube_$videoId"
            val streamingData = obj.optJSONObject("streamingData")
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
                cookies = page.cookies, videoUrl = url, audioUrl = audioAd
            )
        }.getOrNull()
    }

/** Pilih pasangan video+audio adaptive terbaik dari streamingData VISIONOS
 *  (URL langsung, tanpa n-transform). Prioritas video: MP4/AVC (bisa diremux
 *  ke MP4 mulus), lalu MP4 lain, lalu WebM. Prioritas audio: MP4/M4A AAC
 *  (itag 140, lalu 139), lalu audio MP4 lain. Audio WebM (opus) dikembalikan
 *  kosong karena muxer MP4 tidak menerima opus. */
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

    private fun bestAdaptivePair(streamingData: JSONObject?): Pair<String, String> {
    val adaptive = streamingData?.optJSONArray("adaptiveFormats") ?: return "" to ""
    var videoUrl = ""
    var audioUrl = ""
    var videoRank = Int.MAX_VALUE
    var audioRank = Int.MAX_VALUE
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
                if (rank < videoRank) {
                    videoRank = rank
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
                if (rank < audioRank) {
                    audioRank = rank
                    audioUrl = url
                }
            }
        }
    }
    return videoUrl to audioUrl
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
                val options = mutableListOf<Result>()
                for (i in 0 until streams.length()) {
                    val s = streams.optJSONObject(i) ?: continue
                    val u = s.optString("url", "")
                    if (u.startsWith("http")) {
                        val mime = s.optString("mimeType", MIME_MP4)
                        val ext = if (mime.contains("webm")) "webm" else "mp4"
                        val safeName = sanitizeFileName(title)
                        val quality = s.optString("quality", "Unknown")
                        options.add(Result(u, "${safeName}.$ext", title, quality, MIME_MP4))
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
     *  Seluruh body per-iteration dibungkus runCatching: timeout/koneksi gagal
     *  (SocketTimeoutException, IOException) TIDAK boleh menghentikan rantai
     *  fallback YouTube — cukup dianggap gagal dan lanjut ke instance lain. */
    private fun resolveInvidiousLatest(instance: String, videoId: String, itag: String): String? {
        val start = "https://$instance/latest_version?id=$videoId&itag=$itag"
        var current = start
        for (i in 0 until 6) {
            runCatching {
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
                        return@runCatching null
                    }
                    val type = conn.contentType ?: ""
                    if (code in 200..299 && type.contains("video", ignoreCase = true)) {
                        return current
                    }
                    return null
                } finally { runCatching { conn.disconnect() } }
            }.getOrNull()
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

    /** Unescape URL/judul dari JS/HTML (`\\/`, `\\u0026`, `&amp;`). */
    private fun unescapePlayer(raw: String): String {
        return raw.replace("\\/", "/")
            .replace("\\u002F", "/")
            .replace("\\u0026", "&")
            .replace("&amp;", "&")
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
        PH_MEDIA_DEF_RE.findAll(html).forEach {
            addCandidate(it.groupValues.getOrNull(1), it.groupValues.getOrNull(2)?.toIntOrNull() ?: 0)
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
        // audit-ignore: maintenance_marker (nama domain resmi situs, bukan marker)
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
        val ordered = distinct.filter { !it.second } + distinct.filter { it.second }
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

        for (i in 0 until media.length()) {
            val item = media.optJSONObject(i) ?: continue
            when (item.optString("type")) {
                "video" -> {
                    val directUrl = item.optString("url")
                    if (directUrl.startsWith("http")) {
                        options.add(Result(directUrl, "Twitter_${sanitizeFileName(user)}.mp4", text, "Video", MIME_MP4))
                    }
                }
                "photo" -> {
                    val directUrl = item.optString("url")
                    if (directUrl.startsWith("http")) {
                        options.add(Result(directUrl, "Twitter_${sanitizeFileName(user)}.jpg", text, "Photo", "image/jpeg"))
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
            val scheme = u.protocol.lowercase()
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
        return headers.filterKeys { k ->
            !k.equals("Cookie", ignoreCase = true) &&
                !k.equals("Authorization", ignoreCase = true) &&
                !k.equals("Proxy-Authorization", ignoreCase = true)
        }
    }

    private fun httpGetWithCookies(urlStr: String, headers: Map<String, String> = emptyMap(), timeoutMs: Int = 15000): HttpResult? {
        var current = urlStr
        var activeHeaders = headers
        repeat(6) {
            val conn = openTlsConn(current)
            try {
                conn.connectTimeout = timeoutMs
                conn.readTimeout = timeoutMs
                conn.instanceFollowRedirects = false
                activeHeaders.forEach { (k, v) -> conn.setRequestProperty(k, v) }
                if (!activeHeaders.containsKey("User-Agent")) {
                    conn.setRequestProperty("User-Agent",
                        DEFAULT_UA)
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
                    return@repeat
                }
                if (code !in 200..299) return null
                val cookies = conn.headerFields.entries
                    .filter { it.key.equals("set-cookie", ignoreCase = true) }
                    .flatMap { it.value }
                    .map { it.substringBefore(';') }
                    .joinToString("; ")
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
        repeat(6) {
            val conn = openTlsConn(current)
            try {
                conn.requestMethod = method
                conn.connectTimeout = timeoutMs
                conn.readTimeout = timeoutMs
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
