package com.tasirin.httpdownloadmanager.util

import com.tasirin.httpdownloadmanager.App
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

object SocialMediaExtractor {

    /** Batas total waktu ekstraksi media sosial (ms). */
    private const val EXTRACT_TOTAL_TIMEOUT_MS = 25_000L

    /** Regex X/Twitter — dihoist agar tidak dikompilasi ulang tiap panggilan. */
    private val X_URL_RE = Regex("""(?:https?://(?:www\.)?|\.?)x\.com/""")

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
    private val YT_ID_SHORTS_RE = Regex("/shorts/([A-Za-z0-9_-]{11})")
    private val YT_ID_V_RE = Regex("[?&]v=([A-Za-z0-9_-]{11})")
    private val YT_ID_YOUTU_RE = Regex("youtu\\.be/([A-Za-z0-9_-]{11})")
    private val SANITIZE_BAD_CHARS_RE = Regex("[^A-Za-z0-9_\\-. ]")
    private val FB_VIDEO_ID_JSON_RE = Regex("\"(?:video_id|videoID|videoId)\"\\s*:\\s*\"([0-9]+)\"")
    private val FB_VIDEO_ID_ATTR_RE = Regex("data-video-id=\"([0-9]+)\"")
    private val FB_OG_V_RE = Regex("[?&]v=([0-9]+)")
    private val FB_HD_SRC_RE = Regex("\"hd_src\"\\s*:\\s*\"([^\"]+)\"")
    private val FB_BROWSER_HD_RE = Regex("\"browser_native_hd_url\"\\s*:\\s*\"([^\"]+)\"")
    private val FB_PLAYABLE_HD_RE = Regex("\"playable_url_quality_hd\"\\s*:\\s*\"([^\"]+)\"")
    private val FB_SD_SRC_RE = Regex("\"sd_src\"\\s*:\\s*\"([^\"]+)\"")
    private val FB_BROWSER_SD_RE = Regex("\"browser_native_sd_url\"\\s*:\\s*\"([^\"]+)\"")
    private val FB_BROWSER_URL_RE = Regex("\"browser_native_url\"\\s*:\\s*\"([^\"]+)\"")
    private val FB_PLAYABLE_RE = Regex("\"playable_url\"\\s*:\\s*\"([^\"]+)\"")
    private val FB_MP4_TOKEN_RE = Regex("\\.mp4")
    private val FB_OG_VIDEO_RE = Regex("<meta[^>]+property=\"og:video[^\"]*\"[^>]+content=\"([^\"]+)\"")
    private val FB_SHARE_ID_RE = Regex("/v/([A-Za-z0-9_-]+)")
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
        if (lower.contains("cdninstagram.com") || lower.contains("cdninstagram")) return false
        if (lower.contains("tiktokcdn.com") || lower.contains("tiktokcdn")) return false
        return lower.contains("tiktok.com/") ||
                lower.contains("instagram.com/p/") ||
                lower.contains("instagram.com/reel/") ||
                lower.contains("instagram.com/tv/") ||
                lower.contains("instagr.am/p/") ||
                lower.contains("instagr.am/reel/") ||
                lower.contains("twitter.com/") ||
                lower.contains("facebook.com/") ||
                lower.contains("fb.watch/") ||
                X_URL_RE.containsMatchIn(lower) ||
                lower.contains("youtube.com/") ||
                lower.contains("youtu.be/")
    }

    /** Ekstrak URL terbaik (satu opsi). */
    suspend fun extract(url: String, headers: String = ""): Result? = withContext(Dispatchers.IO) {
        try {
            val lower = url.lowercase()
            when {
                lower.contains("tiktok.com/") || lower.contains("vm.tiktok.com/") ->
                    extractTikTok(url)
                lower.contains("instagram.com/") || lower.contains("instagr.am/") ->
                    extractInstagram(url)
                lower.contains("twitter.com/") || lower.contains("x.com/") ->
                    extractTwitter(url)
                lower.contains("facebook.com/") || lower.contains("fb.watch/") ->
                    extractFacebook(url)
                lower.contains("youtube.com/") || lower.contains("youtu.be/") ->
                    extractYouTube(url)
                else -> null
            }
        } catch (_: Exception) { null }
    }

    /** Ekstrak semua opsi resolusi yang tersedia. */
    suspend fun extractAll(url: String, headers: String = ""): List<Result> = withContext(Dispatchers.IO) {
        // Batas keras total ekstraksi: rantai fallback (piped/invidious/embed)
        // punya timeout sendiri, tapi jangan sampai menahan thread server atau
        // dialog probe terlalu lama bila semua mirror lambat/gagal.
        withTimeoutOrNull(EXTRACT_TOTAL_TIMEOUT_MS) {
            try {
                val lower = url.lowercase()
                when {
                    lower.contains("tiktok.com/") || lower.contains("vm.tiktok.com/") ->
                        extractAllTikTok(url)
                    lower.contains("instagram.com/") || lower.contains("instagr.am/") ->
                        extractAllInstagram(url)
                    lower.contains("twitter.com/") || lower.contains("x.com/") ->
                        extractAllTwitter(url)
                    lower.contains("facebook.com/") || lower.contains("fb.watch/") ->
                        extractAllFacebook(url)
                    lower.contains("youtube.com/") || lower.contains("youtu.be/") ->
                        extractAllYouTube(url)
                    else -> emptyList()
                }
            } catch (_: Exception) { emptyList() }
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
        val json = httpGet("https://www.tikwm.com/api/?url=$encoded&hd=1") ?: return emptyList()
        val obj = JSONObject(json)
        if (obj.optInt("code", -1) != 0) return emptyList()
        val data = obj.optJSONObject("data") ?: return emptyList()
        val title = data.optString("title", "")
        val author = try {
            data.optJSONObject("author")?.optString("unique_id", "")
        } catch (_: Exception) { "" }
        val id = data.optString("id", "")
        val namePrefix = "TikTok_${author}_$id".trim('_')
        val options = mutableListOf<Result>()

        val hdUrl = data.optString("hdplay", "")
        if (hdUrl.startsWith("http")) {
            options.add(Result(hdUrl, "$namePrefix.mp4", title, "HD", "video/mp4"))
        }
        val sdUrl = data.optString("play", "")
        if (sdUrl.startsWith("http")) {
            options.add(Result(sdUrl, "$namePrefix.mp4", title, "SD", "video/mp4"))
        }
        val wmUrl = data.optString("wmplay", "")
        if (wmUrl.startsWith("http") && wmUrl != sdUrl) {
            options.add(Result(wmUrl, "${namePrefix}_wm.mp4", title, "SD (watermark)", "video/mp4"))
        }
        return options
    }

    // ── Instagram ────────────────────────────────────────────────────────

    private val GOOGLEBOT_HEADERS = mapOf(
        "User-Agent" to "Mozilla/5.0 (compatible; Googlebot/2.1; +http://www.google.com/bot.html)",
        "Accept" to "text/html"
    )

    private val IG_HEADERS = mapOf(
        "User-Agent" to "Mozilla/5.0 (compatible; Googlebot/2.1; +http://www.google.com/bot.html)",
        "Accept" to "text/html"
    )

    private fun extractInstagram(url: String): Result? {
        val options = extractAllInstagram(url)
        return options.firstOrNull()
    }

    private fun extractAllInstagram(url: String): List<Result> {
        val shortcode = IG_SHORTCODE_RE.find(url)
            ?.groupValues?.get(1) ?: return emptyList()
        val options = mutableListOf<Result>()

        // Strategi 1: embed page — data carousel presisi (hanya foto/video milik post ini)
        // Format contextJSON":"{...}" berisi edge_sidecar_to_children lengkap.
        val embedResult = httpGetWithCookies(
            "https://www.instagram.com/p/$shortcode/embed/captioned/", IG_HEADERS
        )
        val embedHtml = embedResult?.body
        val igCookies = embedResult?.cookies.orEmpty()
        App.logEvent("IG DEBUG: embed page ${embedHtml?.length ?: 0} chars, shortcode=$shortcode")
        if (embedHtml != null && embedHtml.length > 1000) {
            val media = extractContextJson(embedHtml)
            if (media != null) {
                val all = extractAllFromMedia(media, shortcode, igCookies)
                if (all.isNotEmpty()) {
                    App.logEvent("IG DEBUG: embed carousel ${all.size} items, cookies=${igCookies.take(30)}")
                    options.addAll(all)
                }
            }
        }

        // Strategi 2: halaman utama via Googlebot — hanya bila embed gagal
        if (options.isEmpty()) {
            val httpResult = httpGetWithCookies("https://www.instagram.com/p/$shortcode/", GOOGLEBOT_HEADERS)
            val pageCookies = httpResult?.cookies.orEmpty()
            val pageHtml = httpResult?.body
            App.logEvent("IG DEBUG: main page ${pageHtml?.length ?: 0} chars, shortcode=$shortcode")
            if (pageHtml != null && pageHtml.length > 1000) {
                val displayUrls = extractAllDisplayUrlsFromPage(pageHtml)
                App.logEvent("IG DEBUG: found ${displayUrls.size} image URLs, video=${extractVideoFromPage(pageHtml) != null}")
                displayUrls.forEachIndexed { idx, imgUrl ->
                    options.add(Result(imgUrl, "Instagram_${shortcode}_${idx+1}.jpg",
                        "Instagram $shortcode", "Photo ${idx+1}", "image/jpeg", cookies = pageCookies))
                }
                val videoUrl = extractVideoFromPage(pageHtml)
                if (videoUrl != null) {
                    options.add(0, Result(videoUrl, "Instagram_${shortcode}.mp4",
                        "Instagram $shortcode", "Video", "video/mp4", cookies = pageCookies))
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
                        results.add(Result(cv, "Instagram_${shortcode}.mp4", "Instagram $shortcode", "Video", "video/mp4", cookies = cookies))
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
                results.add(Result(videoUrl, "Instagram_${shortcode}.mp4", "Instagram $shortcode", "Video", "video/mp4", cookies = cookies))
            }
            val displayUrl = media.optString("display_url", "")
            if (displayUrl.startsWith("http") && results.isEmpty()) {
                results.add(Result(displayUrl, "Instagram_${shortcode}.jpg", "Instagram $shortcode", "Photo", "image/jpeg", cookies = cookies))
            }
        }
        return results
    }


    // ── YouTube ────────────────────────────────────────────────────────

    private fun extractYouTube(url: String): Result? {
        val options = extractAllYouTube(url)
        return options.firstOrNull()
    }

    private fun extractAllYouTube(url: String): List<Result> {
        val videoId = extractYouTubeId(url) ?: return emptyList()

        // Strategi 1: VISIONOS player API — URL stream tanpa n-signature.
        // URL adaptif/HLS dari client ini bisa langsung di-download (tidak 403).
        val vision = extractYouTubeViaVisionos(videoId)
        if (vision != null) {
            App.logEvent("YT DEBUG: VISIONOS OK → HLS ${vision.directUrl.take(80)}...")
            return listOf(vision)
        }

        // Strategi 2: halaman WEB (ytInitialPlayerResponse) + fallback Piped/Invidious.
        return extractYouTubeFromPage(url, videoId)
    }

    /** Ekstrak URL non-HLS dari YouTube: halaman WEB langsung + fallback Piped/Invidious.
     *  Dipanggil bila VISIONOS HLS gagal (media playlist butuh pot token). */
    suspend fun extractNonHlsYouTube(url: String): Result? = withContext(Dispatchers.IO) {
        try {
            val videoId = extractYouTubeId(url) ?: return@withContext null
            // Strategi 0: coba adaptiveFormats dari VISIONOS (URL langsung tanpa HLS)
            val visionAdaptive = extractYouTubeViaVisionosAdaptive(videoId)
            if (visionAdaptive != null) return@withContext visionAdaptive
            // Strategi 1: halaman WEB + Piped/Invidious/Cobalt
            val results = extractYouTubeFromPage(url, videoId)
            results.firstOrNull { it.directUrl.startsWith("http") }
        } catch (_: Exception) { null }
    }

    private fun extractYouTubeFromPage(url: String, videoId: String): List<Result> {
        val httpResult = httpGetWithCookies(
            "https://www.youtube.com/watch?v=$videoId",
            mapOf(
                "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Safari/537.36",
                "Accept-Language" to "en-US,en;q=0.9"
            ),
            timeoutMs = 20000
        ) ?: return emptyList()
        val pageHtml = httpResult.body
        val ytCookies = httpResult.cookies
        App.logEvent("YT DEBUG: page ${pageHtml.length} chars, id=$videoId, cookies=${ytCookies.take(40)}")

        val match = YT_PLAYER_RESP_RE
            .find(pageHtml)
            ?: YT_PLAYER_RESP_LAX_RE.find(pageHtml)
            ?: run {
                App.logEvent("YT DEBUG: ytInitialPlayerResponse not found")
                return emptyList()
            }

        try {
            val data = JSONObject(match.groupValues[1])
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
            App.logEvent("YT DEBUG: formats=${muxedFormats?.length() ?: 0}, adaptive=${adaptiveFormats?.length() ?: 0}")

            for (fmt in allFormats) {
                val videoUrl = fmt.optString("url", "")
                if (videoUrl.startsWith("http")) {
                    val quality = fmt.optString("qualityLabel", "Unknown")
                    val mimeType = fmt.optString("mimeType", "video/mp4")
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
                    App.logEvent("YT DEBUG: using Piped fallback (${piped.size} stream)")
                    return piped
                }
                val invidious = runCatching { extractYouTubeViaInvidious(videoId) }.getOrElse { e ->
                    App.logEvent("YT DEBUG: invidious error: ${e.message?.take(80)}")
                    null
                }
                if (invidious != null) {
                    App.logEvent("YT DEBUG: using Invidious fallback")
                    return listOf(invidious)
                }
                val cobalt = runCatching { extractYouTubeViaCobalt(videoId) }.getOrElse { e ->
                    App.logEvent("YT DEBUG: cobalt error: ${e.message?.take(80)}")
                    null
                }
                if (cobalt != null) {
                    App.logEvent("YT DEBUG: using Cobalt fallback")
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
    private fun extractYouTubeViaVisionos(videoId: String): Result? {
        // Butuh visitorData + cookies dari halaman agar API tidak LOGIN_REQUIRED.
        val page = httpGetWithCookies(
            "https://www.youtube.com/shorts/$videoId",
            mapOf(
                "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 Chrome/131.0.0.0",
                "Accept-Language" to "en-US,en;q=0.9"
            ),
            timeoutMs = 20000
        ) ?: return null
        val visitor = YT_VISITOR_DATA_RE.find(page.body)?.groupValues?.get(1)
            ?: YT_VISITOR_DATA_LOW_RE.find(page.body)?.groupValues?.get(1)
            ?: return null
        App.logEvent("YT DEBUG: VISIONOS visitorData ${visitor.take(24)}..., cookies=${page.cookies.take(24)}")

        val body = buildString {
            append("{\"context\":{\"client\":{")
            append("\"clientName\":\"VISIONOS\",")
            append("\"clientVersion\":\"1.02\",")
            append("\"deviceMake\":\"Apple\",")
            append("\"deviceModel\":\"RealityDevice17,1\",")
            append("\"userAgent\":\"Mozilla/5.0 (Macintosh; Intel Mac OS X 15_7_3) AppleWebKit/605.1.15\",")
            append("\"osName\":\"visionOS\",")
            append("\"osVersion\":\"26.5.23O471\",")
            append("\"hl\":\"en\",")
            append("\"visitorData\":\"$visitor\"")
            append("}},\"videoId\":\"$videoId\"}")
        }
        val json = httpPostJson(
            "https://www.youtube.com/youtubei/v1/player",
            body,
            mapOf(
                "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 Chrome/131.0.0.0",
                "Origin" to "https://www.youtube.com",
                "Referer" to "https://www.youtube.com/",
                "X-Goog-Visitor-Id" to visitor,
                "X-YouTube-Client-Name" to "101",
                "X-YouTube-Client-Version" to "1.02"
            ),
            timeoutMs = 20000
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
                    val url = best.getString("url")
                    val mime = best.optString("mimeType", "video/mp4")
                    val quality = best.optString("qualityLabel", "Unknown")
                    App.logEvent("YT DEBUG: VISIONOS adaptive fallback → $quality ${mime.take(20)}")
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
    private fun extractYouTubeViaVisionosAdaptive(videoId: String): Result? {
        val page = httpGetWithCookies(
            "https://www.youtube.com/watch?v=$videoId",
            mapOf(
                "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 Chrome/131.0.0.0",
                "Accept-Language" to "en-US,en;q=0.9"
            ),
            timeoutMs = 20000
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
            append("\"userAgent\":\"Mozilla/5.0 (Macintosh; Intel Mac OS X 15_7_3) AppleWebKit/605.1.15\",")
            append("\"osName\":\"visionOS\",")
            append("\"osVersion\":\"26.5.23O471\",")
            append("\"hl\":\"en\",")
            append("\"visitorData\":\"$visitor\"")
            append("}},\"videoId\":\"$videoId\"}")
        }
        val json = httpPostJson(
            "https://www.youtube.com/youtubei/v1/player",
            body,
            mapOf(
                "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 Chrome/131.0.0.0",
                "Origin" to "https://www.youtube.com",
                "Referer" to "https://www.youtube.com/",
                "X-Goog-Visitor-Id" to visitor,
                "X-YouTube-Client-Name" to "101",
                "X-YouTube-Client-Version" to "1.02"
            ),
            timeoutMs = 20000
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
            val mime = best.optString("mimeType", "video/mp4")
            val quality = best.optString("qualityLabel", "Unknown")
            App.logEvent("YT DEBUG: VISIONOS adaptive → $quality ${mime.take(20)}")
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
            val conn = URL(item.directUrl).openConnection() as HttpURLConnection
            try {
                conn.requestMethod = "HEAD"
                conn.connectTimeout = 6000
                conn.readTimeout = 6000
                conn.setRequestProperty("User-Agent",
                    "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 Chrome/131.0.0.0")
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
                    mapOf("User-Agent" to "Mozilla/5.0", "Accept" to "application/json"),
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
                        val mime = s.optString("mimeType", "video/mp4")
                        val ext = if (mime.contains("webm")) "webm" else "mp4"
                        val safeName = sanitizeFileName(title)
                        val quality = s.optString("quality", "Unknown")
                        options.add(Result(u, "${safeName}.$ext", title, quality, "video/mp4"))
                    }
                }
                options
            }.getOrNull()
            if (!result.isNullOrEmpty()) {
                App.logEvent("YT DEBUG: piped $instance OK (${result.size} stream)")
                return result
            }
            App.logEvent("YT DEBUG: piped $instance failed/unavailable")
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
                App.logEvent("YT DEBUG: invidious $instance resolved stream")
                return Result(resolved, null, "YouTube_$videoId", "360p", "video/mp4")
            }
            App.logEvent("YT DEBUG: invidious $instance failed/unavailable")
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
            App.logEvent("YT DEBUG: cobalt trying $instance")
            val resp = httpPostJson(
                "$instance/",
                body,
                mapOf("User-Agent" to "Mozilla/5.0", "Accept" to "application/json"),
                timeoutMs = 15000
            )
            if (resp == null) {
                App.logEvent("YT DEBUG: cobalt $instance failed/unavailable")
                continue
            }
            return runCatching {
                val obj = JSONObject(resp)
                val status = obj.optString("status", "")
                val url = obj.optString("url", "")
                if (status.isNotEmpty() && url.startsWith("http")) {
                    val fileName = obj.optString("filename", "YouTube_$videoId.mp4")
                    App.logEvent("YT DEBUG: cobalt $instance OK → $status")
                    Result(url, fileName, "YouTube_$videoId", "Auto", "video/mp4")
                } else {
                    // Cobalt v10+ bisa mengembalikan error dalam "text" field
                    val err = obj.optString("text", obj.optString("error", status))
                    App.logEvent("YT DEBUG: cobalt $instance no url: status=$status err=$err")
                    null
                }
            }.getOrNull()?.let { return it }
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
                val conn = URL(current).openConnection() as HttpURLConnection
                try {
                    conn.instanceFollowRedirects = false
                    conn.connectTimeout = 4000
                    conn.readTimeout = 4000
                    conn.setRequestProperty("User-Agent",
                        "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 Chrome/131.0.0.0")
                    conn.setRequestProperty("Accept", "video/mp4,*/*")
                    val code = conn.responseCode
                    if (code in 301..308) {
                        val loc = conn.getHeaderField("Location")
                        if (loc.isNullOrBlank()) return null
                        current = java.net.URI(current).resolve(loc).toString()
                        return@runCatching null
                    }
                    val type = conn.contentType ?: ""
                    if (code in 200..299 && type.contains("video", ignoreCase = true)) {
                        return current
                    }
                    return null
                } finally { runCatching { conn.disconnect() } }
            }.getOrNull()?.let { return it }
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
        return name.replace(SANITIZE_BAD_CHARS_RE, "_")
            .replace(SANITIZE_WS_RE, "_")
            .take(80)
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
                        options.add(Result(directUrl, "Twitter_${user}.mp4", text, "Video", "video/mp4"))
                    }
                }
                "photo" -> {
                    val directUrl = item.optString("url")
                    if (directUrl.startsWith("http")) {
                        options.add(Result(directUrl, "Twitter_${user}.jpg", text, "Photo", "image/jpeg"))
                    }
                }
            }
        }
        return options
    }

    // ── Facebook ─────────────────────────────────────────────────────────

    private fun extractFacebook(url: String): Result? {
        val options = extractAllFacebook(url)
        return options.firstOrNull { it.quality.contains("HD", ignoreCase = true) }
            ?: options.firstOrNull()
    }

    private fun extractAllFacebook(url: String): List<Result> {
        // Share link diarahkan otomatis ke halaman video kanonik (instanceFollowRedirects).
        // Halaman publik video Facebook memuat hd_src/sd_src/playable_url di JSON-nya
        // untuk bot/browser; CDN fbsbx bisa langsung diunduh dengan cookie halaman.
        val page = httpGetWithCookies(url, GOOGLEBOT_HEADERS, 20000) ?: return emptyList()
        val html = page.body
        val cookies = page.cookies
        App.logEvent("FB DEBUG: page ${html.length} chars, url=${url.take(80)}")
        val videoId = extractFacebookVideoId(url, html) ?: return emptyList()
        App.logEvent("FB DEBUG: videoId=$videoId")
        val options = extractFacebookFromHtml(html, videoId, cookies, "page")
        if (options.isNotEmpty()) return options

        // Strategi 2: halaman plugin embed — sering memuat hd_src/sd_src walau
        // halaman utama tidak (mis. konten yang butuh login untuk halaman biasa).
        val plugin = httpGetWithCookies(
            "https://www.facebook.com/plugins/video.php?href=${URLEncoder.encode(url, "UTF-8")}&show_text=false",
            GOOGLEBOT_HEADERS, 20000
        )?.body.orEmpty()
        App.logEvent("FB DEBUG: plugin ${plugin.length} chars")
        val fromPlugin = extractFacebookFromHtml(plugin, videoId, cookies, "plugin")
        if (fromPlugin.isNotEmpty()) return fromPlugin

        // Strategi 3: halaman embed lama /video/embed/<id> — cadangan terakhir.
        val embed = httpGetWithCookies(
            "https://www.facebook.com/video/embed?video_id=$videoId", GOOGLEBOT_HEADERS, 20000
        )?.body.orEmpty()
        App.logEvent("FB DEBUG: embed ${embed.length} chars")
        return extractFacebookFromHtml(embed, videoId, cookies, "embed")
    }

    private fun extractFacebookFromHtml(html: String, videoId: String, cookies: String, source: String): List<Result> {
        if (html.length < 200) return emptyList()
        val found = linkedMapOf<String, String>() // url -> quality label
        // og:video biasanya mengarah ke halaman video, bukan file media — dilewati.
        val candidates = listOf(
            FB_HD_SRC_RE to "HD",
            FB_BROWSER_HD_RE to "HD",
            FB_PLAYABLE_HD_RE to "HD",
            FB_SD_SRC_RE to "SD",
            FB_BROWSER_SD_RE to "SD",
            FB_PLAYABLE_RE to "SD",
            FB_BROWSER_URL_RE to ""
        )
        // Diagnostik: berapa banyak tiap pola muncul — untuk menelusuri bila gagal.
        App.logEvent("FB DEBUG: $source" +
            " hd_src=${FB_HD_SRC_RE.findAll(html).count()} playableHD=${FB_PLAYABLE_HD_RE.findAll(html).count()}" +
            " sd_src=${FB_SD_SRC_RE.findAll(html).count()} playable=${FB_PLAYABLE_RE.findAll(html).count()}" +
            " browserNative=${FB_BROWSER_HD_RE.findAll(html).count() + FB_BROWSER_SD_RE.findAll(html).count() + FB_BROWSER_URL_RE.findAll(html).count()}" +
            " mp4Hits=${FB_MP4_TOKEN_RE.findAll(html).count()}")
        candidates.forEach { (regex, label) ->
            var start = 0
            while (true) {
                val m = regex.find(html, start) ?: break
                val url = m.groupValues[1].let { unescapeFb(it) }
                if (url.startsWith("http") && url.length in 20..2000) found.putIfAbsent(url, label)
                start = m.range.last + 1
            }
        }
        // Fallback: URL mp4 langsung yang diapit tanda kutip di halaman (semua kualitas).
        extractFacebookMp4Urls(html).forEach { url ->
            val q = if (url.contains("quality=hd") || url.contains("_hd")) "HD" else "SD"
            found.putIfAbsent(url, q)
        }
        App.logEvent("FB DEBUG: $source found=${found.size} first=${found.keys.firstOrNull()?.take(120) ?: "-"}")
        val options = mutableListOf<Result>()
        found.forEach { (url, quality) ->
            options.add(Result(url, "Facebook_${videoId}.mp4", "Facebook $videoId", quality, "video/mp4", cookies))
        }
        return options
    }

    /** Pindai semua URL mp4 yang diapit tanda kutip di halaman (menangani JSON
     *  escape \/ untuk slash); dedup dilakukan oleh caller. */
    private fun extractFacebookMp4Urls(html: String): List<String> {
        val out = mutableListOf<String>()
        var idx = html.indexOf(".mp4")
        var guard = 0
        while (idx >= 0 && guard++ < 100) {
            var start = idx
            while (start > 0 && html[start - 1] != '"') start--
            var end = idx + 4
            while (end < html.length && html[end] != '"') end++
            if (start >= 0 && end > idx + 4) {
                val url = unescapeFb(html.substring(start, end))
                if (url.startsWith("http") && url.contains(".mp4") && url.length in 20..2000) out.add(url)
            }
            idx = html.indexOf(".mp4", idx + 4)
            if (out.size >= 8) break
        }
        return out
    }

    private fun extractFacebookVideoId(url: String, html: String): String? {
        FB_VIDEO_ID_JSON_RE.find(html)?.groupValues?.get(1)?.let { return it }
        FB_VIDEO_ID_ATTR_RE.find(html)?.groupValues?.get(1)?.let { return it }
        FB_OG_VIDEO_RE.find(html)?.groupValues?.get(1)?.let { og ->
            val v = FB_OG_V_RE.find(og)?.groupValues?.get(1)
            if (v != null) return v
        }
        FB_SHARE_ID_RE.find(url)?.groupValues?.get(1)?.let { return it }
        return null
    }

    /** URL media di JSON Facebook memakai escape JSON (\/, \u0025, dst) — unescape. */
    private fun unescapeFb(s: String): String {
        return s.replace("\\/", "/")
            .replace("\\u0025", "%")
            .replace("\\u0026", "&")
            .replace("\\u003d", "=")
            .replace("\\x26", "&")
            .replace("\\x3d", "=")
            .replace("\\u002F", "/")
            .replace("&amp;", "&")
    }

    // ── HTTP ─────────────────────────────────────────────────────────────

    data class HttpResult(val body: String, val cookies: String = "")

    private fun httpGetWithCookies(urlStr: String, headers: Map<String, String> = emptyMap(), timeoutMs: Int = 15000): HttpResult? {
        val conn = URL(urlStr).openConnection() as HttpURLConnection
        try {
            conn.connectTimeout = timeoutMs
            conn.readTimeout = timeoutMs
            conn.instanceFollowRedirects = true
            headers.forEach { (k, v) -> conn.setRequestProperty(k, v) }
            if (!headers.containsKey("User-Agent")) {
                conn.setRequestProperty("User-Agent",
                    "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36")
            }
            val code = conn.responseCode
            if (code !in 200..299) return null
            val cookies = conn.headerFields.entries
                .filter { it.key.equals("set-cookie", ignoreCase = true) }
                .flatMap { it.value }
                .map { it.substringBefore(';') }
                .joinToString("; ")
            val body = BufferedReader(InputStreamReader(conn.inputStream)).use { it.readText() }
            return HttpResult(body, cookies)
        } catch (_: Exception) { return null } finally { conn.disconnect() }
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
        val conn = URL(urlStr).openConnection() as HttpURLConnection
        try {
            conn.requestMethod = "POST"
            conn.connectTimeout = timeoutMs
            conn.readTimeout = timeoutMs
            conn.setRequestProperty("Content-Type", "application/json")
            conn.setRequestProperty("Accept", "application/json")
            headers.forEach { (k, v) -> conn.setRequestProperty(k, v) }
            conn.doOutput = true
            conn.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }
            val code = conn.responseCode
            if (code !in 200..299) return null
            return BufferedReader(InputStreamReader(conn.inputStream)).use { it.readText() }
        } catch (_: Exception) { return null } finally { conn.disconnect() }
    }
}
