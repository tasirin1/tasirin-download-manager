package com.tasirin.httpdownloadmanager.util

import com.tasirin.httpdownloadmanager.App
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
        // CDN URLs (scontent-*.cdninstagram.com) bukan halaman post — skip
        if (lower.contains("cdninstagram.com") || lower.contains("cdninstagram")) return false
        if (lower.contains("tiktokcdn.com") || lower.contains("tiktokcdn")) return false
        return lower.contains("tiktok.com/") ||
                lower.contains("instagram.com/p/") ||
                lower.contains("instagram.com/reel/") ||
                lower.contains("instagram.com/tv/") ||
                lower.contains("instagr.am/p/") ||
                lower.contains("instagr.am/reel/") ||
                lower.contains("twitter.com/") ||
                lower.contains("x.com/")
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
                else -> null
            }
        } catch (e: Exception) { null }
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
        val author = try { data.optJSONObject("author")?.optString("unique_id", "") } catch (_: Exception) { "" }
        val id = data.optString("id", "")
        return Result(directUrl, "TikTok_${author}_$id.mp4".trim('_'), title)
    }

    // ── Instagram ────────────────────────────────────────────────────────

    // Headers yang dibutuhkan Instagram agar tidak block
    private val IG_HEADERS = mapOf(
        "User-Agent" to "Mozilla/5.0 (Linux; Android 13; SM-A055F) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Mobile Safari/537.36",
        "Accept" to "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8",
        "Accept-Language" to "en-US,en;q=0.9",
        "Sec-Fetch-Dest" to "document",
        "Sec-Fetch-Mode" to "navigate",
        "Sec-Fetch-Site" to "none",
        "Sec-Fetch-User" to "?1"
    )

    private fun extractInstagram(url: String): Result? {
        val shortcode = Regex("/(?:p|reel|tv)/([A-Za-z0-9_-]+)").find(url)
            ?.groupValues?.get(1) ?: return null

        val imgIndex = Regex("""img_index=(\d+)""").find(url)?.groupValues?.get(1)?.toIntOrNull()?.minus(1)

        // Googlebot UA — halaman utama berisi video_versions + display_url di escaped JSON
        val googlebotHeaders = mapOf(
            "User-Agent" to "Mozilla/5.0 (compatible; Googlebot/2.1; +http://www.google.com/bot.html)",
            "Accept" to "text/html"
        )

        // Strategi 1: halaman utama via Googlebot — cari video_versions atau display_url
        val pageHtml = httpGet("https://www.instagram.com/p/$shortcode/", googlebotHeaders)
        if (pageHtml != null && pageHtml.length > 1000) {
            App.logEvent("IG DEBUG: main page ${pageHtml.length} chars")

            val videoUrl = extractVideoFromPage(pageHtml)
            if (videoUrl != null) {
                App.logEvent("IG DEBUG: found video URL from page")
                return Result(videoUrl, "Instagram_${shortcode}.mp4", "Instagram $shortcode")
            }

            val displayUrl = extractDisplayUrlFromPage(pageHtml)
            if (displayUrl != null) {
                App.logEvent("IG DEBUG: found display_url from page")
                return Result(displayUrl, "Instagram_${shortcode}.jpg", "Instagram $shortcode")
            }

            // Fallback: og:tags
            val ogVideo = extractOgContent(pageHtml, "og:video")
            if (ogVideo != null && ogVideo.startsWith("http")) {
                return Result(ogVideo, "Instagram_${shortcode}.mp4", "Instagram $shortcode")
            }
            val ogImage = extractOgContent(pageHtml, "og:image")
            if (ogImage != null && ogImage.startsWith("http")) {
                return Result(ogImage, "Instagram_${shortcode}.jpg", "Instagram $shortcode")
            }
        }

        // Strategi 2: embed page (contextJSON legacy)
        val embedHtml = httpGet(
            "https://www.instagram.com/p/$shortcode/embed/captioned/", IG_HEADERS
        )
        if (embedHtml != null) {
            val media = extractContextJson(embedHtml)
            if (media != null) {
                val result = extractFromMedia(media, shortcode, imgIndex)
                if (result != null) return result
            }
        }

        return null
    }

    /** Extract video URL dari escaped JSON di halaman Instagram (Googlebot UA). */
    private fun extractVideoFromPage(html: String): String? {
        val idx = html.indexOf("video_versions")
        if (idx < 0) return null
        val raw = html.substring(idx, minOf(idx + 5000, html.length))
        // Unescape JSON: \\u002F -> /, \\u0026 -> &
        val unescaped = raw
            .replace("\\u002F", "/")
            .replace("\\u0026", "&")
            .replace("\\/", "/")
        val pattern = Regex(""""url":"(https://[^"]+)"""")
        for (match in pattern.findAll(unescaped)) {
            val url = match.groupValues[1]
            if (url.contains(".mp4")) return url
        }
        return null
    }

    private fun extractDisplayUrlFromPage(html: String): String? {
        // Cari semua scontent image URLs dari halaman
        val pattern = Regex("""https?://scontent[^"\\]+(?:\.jpg|\.webp|\.png)""")
        val candidates = mutableListOf<String>()
        for (match in pattern.findAll(html)) {
            var url = match.groupValues[0]
                .replace("\\u0026", "&")
                .replace("\\/", "/")
                .replace("&amp;", "&")
            if (url.startsWith("http")) {
                candidates.add(url)
            }
        }
        if (candidates.isEmpty()) return null

        // Prioritas: URL dengan stp=s640x640 (terbukti work dengan auth tokens lengkap)
        // > URL dengan oh= dan oe= (auth tokens) > lainnya
        val withStp = candidates.filter { it.contains("s640x640") }
        if (withStp.isNotEmpty()) {
            return withStp.first()
        }

        // URL dengan auth tokens lengkap (oh= + oe=)
        val withAuth = candidates.filter { it.contains("oh=") && it.contains("oe=") }
        if (withAuth.isNotEmpty()) {
            return withAuth.first()
        }

        // Fallback: URL terpanjang (paling banyak params)
        return candidates.maxByOrNull { it.length }
    }




    /** Ekstrak URL media dari object contextJSON — tangani single video, carousel/sidecar, dan image. */
    private fun extractFromMedia(media: JSONObject, shortcode: String, imgIndex: Int?): Result? {
        val typename = media.optString("__typename", "")
        App.logEvent("IG DEBUG: typename=$typename, has_video_url=${media.has("video_url")}, has_sidecar=${media.has("edge_sidecar_to_children")}")

        // 1. Single video langsung
        val videoUrl = media.optString("video_url", "")
        if (videoUrl.startsWith("http")) {
            return Result(videoUrl, "Instagram_${shortcode}.mp4", "Instagram $shortcode")
        }

        // 2. Carousel (GraphSidecar) — cek children
        val sidecar = media.optJSONObject("edge_sidecar_to_children")
        val edges = sidecar?.optJSONArray("edges")
        App.logEvent("IG DEBUG: sidecar_edges=${edges?.length() ?: 0}")

        if (edges != null && edges.length() > 0) {
            // Log semua children untuk debug
            for (i in 0 until edges.length()) {
                val node = edges.optJSONObject(i)?.optJSONObject("node")
                val nt = node?.optString("__typename", "?") ?: "?"
                val nv = node?.optString("video_url", "")?.take(60) ?: ""
                val ni = node?.optString("display_url", "")?.take(60) ?: ""
                App.logEvent("IG DEBUG: child[$i] typename=$nt video=${nv.isNotEmpty()} display=${ni.isNotEmpty()}")
            }

            // Tentukan index: imgIndex dari URL atau default 0 (item pertama)
            val idx = (imgIndex ?: 0).coerceIn(0, edges.length() - 1)

            // Cari video di index yang diminta
            val targetNode = edges.optJSONObject(idx)?.optJSONObject("node")
            if (targetNode != null) {
                val childVideo = targetNode.optString("video_url", "")
                if (childVideo.startsWith("http")) {
                    App.logEvent("IG DEBUG: found video at index $idx")
                    return Result(childVideo, "Instagram_${shortcode}.mp4", "Instagram $shortcode")
                }
                // Tidak ada video di index ini → ambil gambarnya
                val childImage = targetNode.optString("display_url", "")
                if (childImage.startsWith("http")) {
                    return Result(childImage, "Instagram_${shortcode}.jpg", "Instagram $shortcode")
                }
            }

            // Fallback: scan semua children cari video pertama
            for (i in 0 until edges.length()) {
                val node = edges.optJSONObject(i)?.optJSONObject("node") ?: continue
                if (node.optBoolean("is_video", false)) {
                    val cv = node.optString("video_url", "")
                    if (cv.startsWith("http")) {
                        App.logEvent("IG DEBUG: fallback scan found video at child[$i]")
                        return Result(cv, "Instagram_${shortcode}.mp4", "Instagram $shortcode")
                    }
                }
            }

            // Tidak ada video sama sekali → ambil display_url dari item pertama
            val firstNode = edges.optJSONObject(0)?.optJSONObject("node")
            val fallbackImage = firstNode?.optString("display_url", "")
                ?: media.optString("display_url", "")
            if (fallbackImage.startsWith("http")) {
                return Result(fallbackImage, "Instagram_${shortcode}.jpg", "Instagram $shortcode")
            }
        }

        // 3. Single image — ambil display_url
        val displayUrl = media.optString("display_url", "")
        if (displayUrl.startsWith("http")) {
            return Result(displayUrl, "Instagram_${shortcode}.jpg", "Instagram $shortcode")
        }

        return null
    }

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

    /** Ekstrak content attribute dari og:tag. */
    private fun extractOgContent(html: String, property: String): String? {
        // Format: og:image" content="URL" atau property="og:image" content="URL"
        val p1 = Regex("""$property"\s+content="([^"]+)""").find(html)
        if (p1 != null) return unescape(p1.groupValues[1])
        val p2 = Regex("""property="$property"\s+content="([^"]+)""").find(html)
        if (p2 != null) return unescape(p2.groupValues[1])
        return null
    }

    private fun unescape(s: String): String =
        s.replace("\\u0026", "&").replace("\\/", "/").replace("&amp;", "&")

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
