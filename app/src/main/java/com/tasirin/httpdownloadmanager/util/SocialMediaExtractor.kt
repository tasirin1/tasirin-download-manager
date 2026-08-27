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
        val title: String?
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

    // ── TikTok ───────────────────────────────────────────────────────────

    private fun extractTikTok(url: String): Result? {
        val encoded = URLEncoder.encode(url, "UTF-8")
        val json = httpGet("https://www.tikwm.com/api/?url=$encoded&hd=1") ?: return null
        val obj = JSONObject(json)
        if (obj.optInt("code", -1) != 0) return null
        val data = obj.optJSONObject("data") ?: return null
        val directUrl = data.optString("hdplay").ifEmpty {
            data.optString("play").ifEmpty { return null }
        }
        if (directUrl.isEmpty()) return null
        val title = data.optString("title", "")
        val author = try {
            data.optJSONObject("author")?.optString("unique_id", "")
        } catch (_: Exception) { "" }
        val id = data.optString("id", "")
        return Result(directUrl, "TikTok_${author}_$id.mp4".trim('_'), title)
    }

    // ── Instagram ────────────────────────────────────────────────────────

    private val GOOGLEBOT_HEADERS = mapOf(
        "User-Agent" to "Mozilla/5.0 (compatible; Googlebot/2.1; +http://www.google.com/bot.html)",
        "Accept" to "text/html"
    )

    private fun extractInstagram(url: String): Result? {
        val shortcode = Regex("/(?:p|reel|tv)/([A-Za-z0-9_-]+)").find(url)
            ?.groupValues?.get(1) ?: return null

        // Strategi 1: halaman utama via Googlebot — cari video atau gambar
        val pageHtml = httpGet("https://www.instagram.com/p/$shortcode/", GOOGLEBOT_HEADERS)
        if (pageHtml != null && pageHtml.length > 1000) {
            // Cari video
            val videoUrl = extractVideoFromPage(pageHtml)
            if (videoUrl != null) {
                return Result(videoUrl, "Instagram_${shortcode}.mp4", "Instagram $shortcode")
            }
            // Cari gambar (prioritas: ig_cache_key full resolusi)
            val displayUrl = extractDisplayUrlFromPage(pageHtml)
            if (displayUrl != null) {
                return Result(displayUrl, "Instagram_${shortcode}.jpg", "Instagram $shortcode")
            }
        }

        // Strategi 2: embed page (contextJSON legacy — beberapa post mungkin masih punya)
        val embedHtml = httpGet(
            "https://www.instagram.com/p/$shortcode/embed/captioned/", IG_HEADERS
        )
        if (embedHtml != null) {
            val media = extractContextJson(embedHtml)
            if (media != null) {
                val result = extractFromMedia(media, shortcode)
                if (result != null) return result
            }
        }

        return null
    }

    /** Extract video URL dari escaped JSON di halaman Instagram. */
    private fun extractVideoFromPage(html: String): String? {
        val idx = html.indexOf("video_versions")
        if (idx < 0) return null
        val raw = html.substring(idx, minOf(idx + 5000, html.length))
        val unescaped = raw
            .replace("\\u002F", "/")
            .replace("\\u0026", "&")
            .replace("\\/", "/")
            .replace("\\\"", "\"")
        val pattern = Regex(""""url":"(https://[^"]+)"""")
        for (match in pattern.findAll(unescaped)) {
            val url = match.groupValues[1]
            if (url.contains(".mp4")) return url
        }
        return null
    }

    /** Extract gambar dari escaped JSON — prioritas ig_cache_key (full resolusi). */
    private fun extractDisplayUrlFromPage(html: String): String? {
        val unescaped = html
            .replace("\\u002F", "/")
            .replace("\\u0026", "&")
            .replace("\\/", "/")
            .replace("\\\"", "\"")
            .replace("&amp;", "&")
        val pattern = Regex("""https?://scontent[^"]+""")
        val candidates = mutableListOf<String>()
        for (match in pattern.findAll(unescaped)) {
            val url = match.groupValues[0]
            val path = url.substringBefore("?")
            if (path.endsWith(".jpg") || path.endsWith(".webp") || path.endsWith(".png")) {
                candidates.add(url)
            }
        }
        if (candidates.isEmpty()) return null

        // Prioritas: ig_cache_key tanpa stp (full resolusi) > p1080x1080 > s640x640
        val withCacheKey = candidates.filter { it.contains("ig_cache_key") }
        if (withCacheKey.isNotEmpty()) {
            val noCrop = withCacheKey.filter { !it.contains("stp=") }
            if (noCrop.isNotEmpty()) return noCrop.first()
            val highRes = withCacheKey.filter { it.contains("p1080x1080") || it.contains("s1080x1080") }
            if (highRes.isNotEmpty()) return highRes.first()
            return withCacheKey.first()
        }

        val withStp = candidates.filter { it.contains("s640x640") }
        if (withStp.isNotEmpty()) return withStp.first()

        return candidates.first()
    }

    // ── Instagram contextJSON (legacy) ───────────────────────────────────

    private val IG_HEADERS = mapOf(
        "User-Agent" to "Mozilla/5.0 (Linux; Android 13; SM-A055F) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Mobile Safari/537.36",
        "Accept" to "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8",
        "Accept-Language" to "en-US,en;q=0.9",
        "Sec-Fetch-Dest" to "document",
        "Sec-Fetch-Mode" to "navigate",
        "Sec-Fetch-Site" to "none",
        "Sec-Fetch-User" to "?1"
    )

    @Suppress("EmptyCatchBlock", "kotlin_empty_catch")
    private fun extractContextJson(html: String): JSONObject? {
        val key = "\"contextJSON\":"
        var searchFrom = 0
        while (true) {
            val idx = html.indexOf(key, searchFrom)
            if (idx == -1) break
            val quoteStart = html.indexOf('"', idx + key.length)
            if (quoteStart == -1) break
            var i = quoteStart + 1
            var escaped = false
            while (i < html.length) {
                val ch = html[i]
                if (escaped) escaped = false
                else if (ch == '\\') escaped = true
                else if (ch == '"') break
                i++
            }
            searchFrom = i + 1
            val token = html.substring(quoteStart, i + 1)
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
        // Single video
        val videoUrl = media.optString("video_url", "")
        if (videoUrl.startsWith("http")) {
            return Result(videoUrl, "Instagram_${shortcode}.mp4", "Instagram $shortcode")
        }
        // Carousel/sidecar
        val sidecar = media.optJSONObject("edge_sidecar_to_children")
        val edges = sidecar?.optJSONArray("edges")
        if (edges != null && edges.length() > 0) {
            // Cari video pertama
            for (i in 0 until edges.length()) {
                val node = edges.optJSONObject(i)?.optJSONObject("node") ?: continue
                if (node.optBoolean("is_video", false)) {
                    val cv = node.optString("video_url", "")
                    if (cv.startsWith("http")) {
                        return Result(cv, "Instagram_${shortcode}.mp4", "Instagram $shortcode")
                    }
                }
            }
            // Fallback: gambar pertama
            val firstNode = edges.optJSONObject(0)?.optJSONObject("node")
            val img = firstNode?.optString("display_url", "")
                ?: media.optString("display_url", "")
            if (img.startsWith("http")) {
                return Result(img, "Instagram_${shortcode}.jpg", "Instagram $shortcode")
            }
        }
        // Single image
        val displayUrl = media.optString("display_url", "")
        if (displayUrl.startsWith("http")) {
            return Result(displayUrl, "Instagram_${shortcode}.jpg", "Instagram $shortcode")
        }
        return null
    }

    // ── YouTube ────────────────────────────────────────────────────────

    private fun extractYouTube(url: String): Result? {
        // Extract video ID from URL
        val videoId = extractYouTubeId(url) ?: return null

        // Try Piped API instances (free, open-source YouTube proxy)
        val pipedInstances = listOf(
            "https://pipedapi.kavin.rocks",
            "https://pipedapi.in.projectsegfau.lt",
            "https://watchapi.whatever.social"
        )
        for (instance in pipedInstances) {
            val json = httpGet("$instance/streams/$videoId", timeoutMs = 20000)
            if (json != null) {
                try {
                    val obj = JSONObject(json)
                    val title = obj.optString("title", "YouTube_$videoId")
                    val duration = obj.optLong("duration", 0L)

                    // Cari video stream terbaik
                    val videoStreams = obj.optJSONArray("videoStreams")
                    if (videoStreams != null) {
                        var bestStream: JSONObject? = null
                        var bestHeight = 0
                        for (i in 0 until videoStreams.length()) {
                            val stream = videoStreams.optJSONObject(i) ?: continue
                            val height = stream.optInt("height", 0)
                            val videoOnly = stream.optBoolean("videoOnly", false)
                            val mime = stream.optString("mimeType", "")
                            if (!videoOnly && height > bestHeight && mime.contains("video")) {
                                bestHeight = height
                                bestStream = stream
                            }
                        }
                        // Fallback: video-only stream (perlu audio terpisah)
                        if (bestStream == null) {
                            for (i in 0 until videoStreams.length()) {
                                val stream = videoStreams.optJSONObject(i) ?: continue
                                val height = stream.optInt("height", 0)
                                val mime = stream.optString("mimeType", "")
                                if (height > bestHeight && mime.contains("video")) {
                                    bestHeight = height
                                    bestStream = stream
                                }
                            }
                        }
                        if (bestStream != null) {
                            val videoUrl = bestStream.optString("url", "")
                            if (videoUrl.startsWith("http")) {
                                val ext = if (bestStream.optString("mimeType", "").contains("webm")) "webm" else "mp4"
                                val safeName = sanitizeFileName(title)
                                return Result(videoUrl, "YouTube_${safeName}.$ext", title)
                            }
                        }
                    }
                } catch (_: Exception) { }
            }
        }
        return null
    }

    private fun extractYouTubeId(url: String): String? {
        // youtube.com/shorts/VIDEO_ID
        Regex("/shorts/([A-Za-z0-9_-]{11})").find(url)
            ?.groupValues?.get(1)?.let { return it }
        // youtube.com/watch?v=VIDEO_ID
        Regex("[?&]v=([A-Za-z0-9_-]{11})").find(url)
            ?.groupValues?.get(1)?.let { return it }
        // youtu.be/VIDEO_ID
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
        val cleanUrl = url.replace("https://x.com/", "https://twitter.com/")
        val path = URL(cleanUrl).path
        val json = httpGet("https://api.vxtwitter.com/twitter$path") ?: return null
        val obj = JSONObject(json)
        val tweet = obj.optJSONObject("tweet") ?: return null
        val media = tweet.optJSONArray("media") ?: return null
        for (i in 0 until media.length()) {
            val item = media.optJSONObject(i) ?: continue
            if (item.optString("type") == "video") {
                val directUrl = item.optString("url")
                if (directUrl.startsWith("http")) {
                    val user = tweet.optJSONObject("user")?.optString("name") ?: "Twitter"
                    return Result(directUrl, "Twitter_${user}.mp4", tweet.optString("text", ""))
                }
            }
        }
        // Fallback: photo
        for (i in 0 until media.length()) {
            val item = media.optJSONObject(i) ?: continue
            if (item.optString("type") == "photo") {
                val directUrl = item.optString("url")
                if (directUrl.startsWith("http")) {
                    val user = tweet.optJSONObject("user")?.optString("name") ?: "Twitter"
                    return Result(directUrl, "Twitter_${user}.jpg", tweet.optString("text", ""))
                }
            }
        }
        return null
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
