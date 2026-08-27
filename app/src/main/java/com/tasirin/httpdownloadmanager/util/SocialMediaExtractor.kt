package com.tasirin.httpdownloadmanager.util

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

object SocialMediaExtractor {

    data class Result(
        val directUrl: String,
        val fileName: String?,
        val title: String?,
        val quality: String = "",
        val mimeType: String = ""
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
                lower.contains("x.com/") ||
                lower.contains("youtube.com/") ||
                lower.contains("youtu.be/")
    }

    /** Ekstrak URL terbaik (satu opsi). */
    suspend fun extract(url: String): Result? = withContext(Dispatchers.IO) {
        try {
            val lower = url.lowercase()
            when {
                lower.contains("tiktok.com/") || lower.contains("vm.tiktok.com/") ->
                    extractTikTok(url)
                lower.contains("instagram.com/") || lower.contains("instagr.am/") ->
                    extractInstagram(url)
                lower.contains("twitter.com/") || lower.contains("x.com/") ->
                    extractTwitter(url)
                lower.contains("youtube.com/") || lower.contains("youtu.be/") ->
                    extractYouTube(url)
                else -> null
            }
        } catch (_: Exception) { null }
    }

    /** Ekstrak semua opsi resolusi yang tersedia. */
    suspend fun extractAll(url: String): List<Result> = withContext(Dispatchers.IO) {
        try {
            val lower = url.lowercase()
            when {
                lower.contains("tiktok.com/") || lower.contains("vm.tiktok.com/") ->
                    extractAllTikTok(url)
                lower.contains("instagram.com/") || lower.contains("instagr.am/") ->
                    extractAllInstagram(url)
                lower.contains("twitter.com/") || lower.contains("x.com/") ->
                    extractAllTwitter(url)
                lower.contains("youtube.com/") || lower.contains("youtu.be/") ->
                    extractAllYouTube(url)
                else -> emptyList()
            }
        } catch (_: Exception) { emptyList() }
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
        val shortcode = Regex("/(?:p|reel|tv)/([A-Za-z0-9_-]+)").find(url)
            ?.groupValues?.get(1) ?: return emptyList()
        val options = mutableListOf<Result>()

        // Strategi 1: halaman utama via Googlebot
        val pageHtml = httpGet("https://www.instagram.com/p/$shortcode/", GOOGLEBOT_HEADERS)
        if (pageHtml != null && pageHtml.length > 1000) {
            // Cari semua gambar display_url
            val displayUrls = extractAllDisplayUrlsFromPage(pageHtml)
            displayUrls.forEachIndexed { idx, imgUrl ->
                options.add(Result(imgUrl, "Instagram_${shortcode}_${idx+1}.jpg",
                    "Instagram $shortcode", "Photo ${idx+1}", "image/jpeg"))
            }
            // Cari video
            val videoUrl = extractVideoFromPage(pageHtml)
            if (videoUrl != null) {
                options.add(0, Result(videoUrl, "Instagram_${shortcode}.mp4",
                    "Instagram $shortcode", "Video", "video/mp4"))
            }
        }

        // Strategi 2: embed page
        if (options.isEmpty()) {
            val embedHtml = httpGet(
                "https://www.instagram.com/p/$shortcode/embed/captioned/", IG_HEADERS
            )
            if (embedHtml != null) {
                val media = extractContextJson(embedHtml)
                if (media != null) {
                    val result = extractFromMedia(media, shortcode)
                    if (result != null) options.add(result)
                }
            }
        }
        return options
    }

    private fun extractAllDisplayUrlsFromPage(html: String): List<String> {
        val urls = mutableListOf<String>()
        // Cari semua scontent URLs
        val regex = Regex("https?://[^\"]*scontent[^\"]*\\.(?:jpg|png|webp)[^\"]*")
        regex.findAll(html).forEach { match ->
            val raw = match.value
                .replace("\\u002F", "/")
                .replace("\\u0026", "&")
            if (raw !in urls && !raw.contains("s640x640")) {
                urls.add(raw)
            }
        }
        // Prioritas: ig_cache_key URLs (full resolusi)
        val cacheKeyUrls = urls.filter { it.contains("ig_cache_key") }
        if (cacheKeyUrls.isNotEmpty()) return cacheKeyUrls.take(10)
        return urls.take(10)
    }

    private fun extractVideoFromPage(html: String): String? {
        val idx = html.indexOf("video_versions")
        if (idx < 0) return null
        val raw = html.substring(idx, minOf(idx + 5000, html.length))
        val unescaped = raw
            .replace("\\u002F", "/")
            .replace("\\u0026", "&")
            .replace("\\/", "/")
        val videoRegex = Regex(""""url"\s*:\s*"(https?://[^"]+\\.mp4[^"]*)"""")
        val match = videoRegex.find(unescaped) ?: return null
        return match.groupValues[1]
            .replace("\\u002F", "/")
            .replace("\\u0026", "&")
    }

    private fun extractDisplayUrlFromPage(html: String): String? {
        val urls = extractAllDisplayUrlsFromPage(html)
        return urls.firstOrNull()
    }

    private fun extractContextJson(html: String): JSONObject? {
        val regex = Regex("""window\._ sharedData\s*=\s*({.*?});""")
            ?: Regex(""""shortcode_media"\s*:\s*({.*?})\s*[,}]""")
        for (pattern in listOf(
            Regex("contextJSON\\s*=\\s*\"(.+?)\""),
            Regex("\"token\"\\s*:\\s*\"(.+?)\""),
        )) {
            val match = pattern.find(html) ?: continue
            val token = match.groupValues[1]
                .replace("\\\\u002F", "/")
                .replace("\\\\u0026", "&")
                .replace("\\\"", "\"")
                .replace("\\/", "/")
            try {
                val inner = JSONObject(token).toString()
                val obj = JSONObject(inner)
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
            } catch (_: Exception) { }
        }
        return null
    }

    private fun extractFromMedia(media: JSONObject, shortcode: String): Result? {
        val videoUrl = media.optString("video_url", "")
        if (videoUrl.startsWith("http")) {
            return Result(videoUrl, "Instagram_${shortcode}.mp4", "Instagram $shortcode", "Video", "video/mp4")
        }
        val sidecar = media.optJSONObject("edge_sidecar_to_children")
        val edges = sidecar?.optJSONArray("edges")
        if (edges != null && edges.length() > 0) {
            for (i in 0 until edges.length()) {
                val node = edges.optJSONObject(i)?.optJSONObject("node") ?: continue
                if (node.optBoolean("is_video", false)) {
                    val cv = node.optString("video_url", "")
                    if (cv.startsWith("http")) {
                        return Result(cv, "Instagram_${shortcode}.mp4", "Instagram $shortcode", "Video", "video/mp4")
                    }
                }
            }
            val firstNode = edges.optJSONObject(0)?.optJSONObject("node")
            val img = firstNode?.optString("display_url", "")
                ?: media.optString("display_url", "")
            if (img.startsWith("http")) {
                return Result(img, "Instagram_${shortcode}.jpg", "Instagram $shortcode", "Photo", "image/jpeg")
            }
        }
        val displayUrl = media.optString("display_url", "")
        if (displayUrl.startsWith("http")) {
            return Result(displayUrl, "Instagram_${shortcode}.jpg", "Instagram $shortcode", "Photo", "image/jpeg")
        }
        return null
    }

    // ── YouTube ────────────────────────────────────────────────────────

    private fun extractYouTube(url: String): Result? {
        val options = extractAllYouTube(url)
        return options.firstOrNull()
    }

    private fun extractAllYouTube(url: String): List<Result> {
        val videoId = extractYouTubeId(url) ?: return emptyList()

        val pageHtml = httpGet(
            "https://www.youtube.com/shorts/$videoId",
            mapOf(
                "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Safari/537.36",
                "Accept-Language" to "en-US,en;q=0.9"
            ),
            timeoutMs = 20000
        ) ?: return emptyList()

        val match = Regex("""ytInitialPlayerResponse\s*=\s*(\{.*?\});\s*(?:var\s|</script)""")
            .find(pageHtml)
            ?: Regex("""ytInitialPlayerResponse\s*=\s*(\{.*?\});""").find(pageHtml)
            ?: return emptyList()

        try {
            val data = JSONObject(match.groupValues[1])
            val title = data.optString("title", "YouTube_$videoId")
            val streamingData = data.optJSONObject("streamingData") ?: return emptyList()
            val formats = streamingData.optJSONArray("formats") ?: return emptyList()
            val options = mutableListOf<Result>()

            for (i in 0 until formats.length()) {
                val fmt = formats.optJSONObject(i) ?: continue
                val videoUrl = fmt.optString("url", "")
                if (videoUrl.startsWith("http")) {
                    val quality = fmt.optString("qualityLabel", "Unknown")
                    val mimeType = fmt.optString("mimeType", "video/mp4")
                    val ext = if (mimeType.contains("webm")) "webm" else "mp4"
                    val safeName = sanitizeFileName(title)
                    options.add(Result(videoUrl, "YouTube_${safeName}.$ext", title, quality, mimeType))
                }
            }
            return options
        } catch (_: Exception) { }
        return emptyList()
    }

    private fun extractYouTubeId(url: String): String? {
        Regex("/shorts/([A-Za-z0-9_-]{11})").find(url)
            ?.groupValues?.get(1)?.let { return it }
        Regex("[?&]v=([A-Za-z0-9_-]{11})").find(url)
            ?.groupValues?.get(1)?.let { return it }
        Regex("youtu\\.be/([A-Za-z0-9_-]{11})").find(url)
            ?.groupValues?.get(1)?.let { return it }
        return null
    }

    private fun sanitizeFileName(name: String): String {
        return name.replace(Regex("[^A-Za-z0-9_\\-. ]"), "_")
            .replace(Regex("\\s+"), "_")
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

    // ── HTTP ─────────────────────────────────────────────────────────────

    private fun httpGet(urlStr: String, headers: Map<String, String> = emptyMap(), timeoutMs: Int = 15000): String? {
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
            return BufferedReader(InputStreamReader(conn.inputStream)).use { it.readText() }
        } catch (_: Exception) { return null } finally { conn.disconnect() }
    }
}
