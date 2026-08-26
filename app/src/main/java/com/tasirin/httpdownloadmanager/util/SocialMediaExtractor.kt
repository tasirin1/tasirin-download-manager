package com.tasirin.httpdownloadmanager.util

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

/**
 * Ekstrak direct media URL dari platform sosial media.
 * Hanya API publik — TikTok (tikwm), Instagram (embed+og:tags), Twitter (vxtwitter).
 */
object SocialMediaExtractor {

    data class Result(
        val directUrl: String,
        val fileName: String?,
        val title: String?
    )

    fun isSocialMediaUrl(url: String): Boolean {
        val lower = url.lowercase()
        return lower.contains("tiktok.com/") ||
                lower.contains("instagram.com/") ||
                lower.contains("instagr.am/") ||
                lower.contains("twitter.com/") ||
                lower.contains("x.com/") ||
                lower.contains("facebook.com/") ||
                lower.contains("fb.watch/")
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

    private fun extractInstagram(url: String): Result? {
        val shortcode = Regex("/(?:p|reel|tv)/([A-Za-z0-9_-]+)").find(url)
            ?.groupValues?.get(1) ?: return null

        // Strategi 1: embed page contextJSON (untuk video)
        val embedHtml = httpGet(
            "https://www.instagram.com/p/$shortcode/embed/captioned/",
            mapOf(
                "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36",
                "Sec-Fetch-Dest" to "document",
                "Sec-Fetch-Mode" to "navigate",
                "Sec-Fetch-Site" to "none"
            )
        )
        if (embedHtml != null) {
            val media = extractContextJson(embedHtml)
            if (media != null) {
                val isVideo = media.optBoolean("is_video", false)
                if (isVideo) {
                    val videoUrl = media.optString("video_url", "")
                    if (videoUrl.startsWith("http")) {
                        return Result(videoUrl, "Instagram_${shortcode}.mp4",
                            getCaption(media) ?: "Instagram $shortcode")
                    }
                }
                // Image dari contextJSON
                val displayUrl = media.optString("display_url", "")
                if (displayUrl.startsWith("http")) {
                    return Result(displayUrl, "Instagram_${shortcode}.jpg",
                        getCaption(media) ?: "Instagram $shortcode")
                }
            }
        }

        // Strategi 2: og:tags dari halaman utama (video ATAU gambar)
        val pageHtml = httpGet(
            "https://www.instagram.com/p/$shortcode/",
            mapOf("User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36")
        )
        if (pageHtml != null) {
            // Coba og:video dulu (post video)
            val ogVideo = extractOgTag(pageHtml, "og:video")
            if (ogVideo != null && ogVideo.startsWith("http")) {
                return Result(ogVideo, "Instagram_${shortcode}.mp4", "Instagram $shortcode")
            }
            // Lalu og:image (post gambar)
            val ogImage = extractOgTag(pageHtml, "og:image")
            if (ogImage != null && ogImage.startsWith("http")) {
                return Result(ogImage, "Instagram_${shortcode}.jpg", "Instagram $shortcode")
            }
        }

        return null
    }

    /** Ekstrak shortcode_media dari contextJSON (double-encoded JSON). */
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

    /** Ekstrak nilai og:tag dari HTML. */
    private fun extractOgTag(html: String, property: String): String? {
        // Cari pattern: og:video" content="URL" atau property="og:video" content="URL"
        val pattern1 = "$property\"\\s+content=\"([^\"]+)\""
        val match1 = Regex(pattern1).find(html)
        if (match1 != null) return unescapeJson(match1.groupValues[1])

        val pattern2 = "property=\"$property\"\\s+content=\"([^\"]+)\""
        val match2 = Regex(pattern2).find(html)
        if (match2 != null) return unescapeJson(match2.groupValues[1])

        return null
    }

    private fun getCaption(media: JSONObject): String? {
        val caption = media.optJSONObject("edge_media_to_caption")
            ?.optJSONArray("edges")
            ?.optJSONObject(0)
            ?.optJSONObject("node")
            ?.optString("text", "")
        return if (caption.isNullOrEmpty()) null else caption
    }

    private fun unescapeJson(s: String): String =
        s.replace("\\u0026", "&").replace("\\/", "/").replace("\\u003C", "<")

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

    // ── HTTP helper ──────────────────────────────────────────────────────

    private fun httpGet(urlStr: String, headers: Map<String, String> = emptyMap(), timeoutMs: Int = 15000): String? {
        val conn = URL(urlStr).openConnection() as HttpURLConnection
        try {
            conn.connectTimeout = timeoutMs
            conn.readTimeout = timeoutMs
            conn.setRequestProperty("User-Agent",
                headers["User-Agent"] ?: "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36")
            headers.forEach { (k, v) -> if (k != "User-Agent") conn.setRequestProperty(k, v) }
            conn.instanceFollowRedirects = true
            if (conn.responseCode !in 200..299) return null
            return BufferedReader(InputStreamReader(conn.inputStream)).use { it.readText() }
        } catch (_: Exception) { return null } finally { conn.disconnect() }
    }
}
