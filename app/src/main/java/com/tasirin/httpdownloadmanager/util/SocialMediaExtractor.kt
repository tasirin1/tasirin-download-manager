package com.tasirin.httpdownloadmanager.util

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import org.json.JSONArray
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

/**
 * Ekstrak direct media URL dari platform sosial media.
 * Hanya menggunakan API publik — tidak ada scraping HTML kompleks.
 * TikTok/Instagram/Twitter diproses via API terpisah.
 */
object SocialMediaExtractor {

    data class Result(
        val directUrl: String,
        val fileName: String?,
        val title: String?
    )

    /** Cek apakah URL berasal dari platform sosial media yang didukung. */
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

    /**
     * Coba ekstrak direct media URL. Mengembalikan null bila gagal.
     * Harus dipanggil dari background thread.
     */
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
                lower.contains("facebook.com/") || lower.contains("fb.watch/") ->
                    extractFacebook(url)
                else -> null
            }
        } catch (e: Exception) {
            null
        }
    }

    // ── TikTok — via tikwm.com API ──────────────────────────────────────

    private fun extractTikTok(url: String): Result? {
        val encoded = URLEncoder.encode(url, "UTF-8")
        val apiUrl = "https://www.tikwm.com/api/?url=$encoded&hd=1"
        val json = httpGet(apiUrl) ?: return null
        val obj = JSONObject(json)
        if (obj.optInt("code", -1) != 0) return null
        val data = obj.optJSONObject("data") ?: return null
        val directUrl = data.optString("hdplay").ifEmpty {
            data.optString("play").ifEmpty { return null }
        }
        if (directUrl.isEmpty()) return null
        val title = data.optString("title", "")
        val author = try {
            data.optJSONObject("author")?.optString("unique_id", "") ?: ""
        } catch (_: Exception) { "" }
        val id = data.optString("id", "")
        val fileName = "TikTok_${author}_$id.mp4".trim('_')
        return Result(directUrl, fileName, title)
    }

    // ── Instagram — via embed page contextJSON ───────────────────────────

    private fun extractInstagram(url: String): Result? {
        val shortcode = extractInstagramShortcode(url) ?: return null
        val embedUrl = "https://www.instagram.com/p/$shortcode/embed/captioned/"
        val html = httpGet(embedUrl, mapOf(
            "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36",
            "Sec-Fetch-Dest" to "document",
            "Sec-Fetch-Mode" to "navigate",
            "Sec-Fetch-Site" to "none"
        )) ?: return null

        // 1) Coba contextJSON — double-encoded JSON dengan shortcode_media
        val media = extractFromContextJson(html)
        if (media != null) {
            val isVideo = media.optBoolean("is_video", false)
            if (isVideo) {
                val videoUrl = media.optString("video_url", "")
                if (videoUrl.isNotEmpty() && videoUrl.startsWith("http")) {
                    val title = extractJsonString(media.toString(), "caption")
                        ?: "Instagram $shortcode"
                    return Result(videoUrl, "Instagram_$shortcode.mp4", title)
                }
            }
            // Image/carousel — ambil display_url
            val displayUrl = media.optString("display_url", "")
            if (displayUrl.isNotEmpty() && displayUrl.startsWith("http")) {
                return Result(displayUrl, "Instagram_${shortcode}.jpg",
                    extractJsonString(media.toString(), "caption") ?: "Instagram $shortcode")
            }
        }

        // 2) Fallback: cari video_url langsung di HTML
        val videoUrl = extractJsonString(html, "video_url")
        if (videoUrl != null && videoUrl.startsWith("http")) {
            return Result(videoUrl, "Instagram_$shortcode.mp4", "Instagram $shortcode")
        }

        // 3) Fallback: cari display_url (gambar)
        val displayUrl = extractJsonString(html, "display_url")
        if (displayUrl != null && displayUrl.startsWith("http")) {
            return Result(displayUrl, "Instagram_${shortcode}.jpg", "Instagram $shortcode")
        }

        return null
    }

    /** Ekstrak shortcode dari URL Instagram. */
    private fun extractInstagramShortcode(url: String): String? {
        val match = Regex("/(?:p|reel|tv)/([A-Za-z0-9_-]+)").find(url)
        return match?.groupValues?.get(1)
    }

    /**
     * Ekstrak shortcode_media dari contextJSON di halaman embed.
     * contextJSON adalah JSON string yang di-encode dua kali.
     */
    private fun extractFromContextJson(html: String): JSONObject? {
        val key = "\"contextJSON\":"
        var searchFrom = 0
        while (true) {
            val idx = html.indexOf(key, searchFrom)
            if (idx == -1) break
            val quoteStart = html.indexOf('"', idx + key.length)
            if (quoteStart == -1) break

            // Baca JSON string token (respecting backslash escapes)
            var i = quoteStart + 1
            var escaped = false
            while (i < html.length) {
                val ch = html[i]
                if (escaped) {
                    escaped = false
                } else if (ch == '\\') {
                    escaped = true
                } else if (ch == '"') {
                    break
                }
                i++
            }
            searchFrom = i + 1

            val token = html.substring(quoteStart, i + 1)
            try {
                // Decode pertama: JSON string → string
                val inner = JSONObject(token).toString()
                // Decode kedua: string → object
                val obj = JSONObject(inner)
                // Cari shortcode_media
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
            } catch (_: Exception) {
                // Bukan contextJSON yang dicari, coba yang berikutnya
            }
        }
        return null
    }

    // ── Twitter/X — via vxtwitter.com API ────────────────────────────────

    private fun extractTwitter(url: String): Result? {
        val cleanUrl = url.replace("https://x.com/", "https://twitter.com/")
        val path = URL(cleanUrl).path
        val apiUrl = "https://api.vxtwitter.com/twitter$path"
        val json = httpGet(apiUrl) ?: return null
        val obj = JSONObject(json)
        val tweet = obj.optJSONObject("tweet") ?: return null
        val media = tweet.optJSONArray("media") ?: return null
        for (i in 0 until media.length()) {
            val item = media.optJSONObject(i) ?: continue
            if (item.optString("type") == "video") {
                val directUrl = item.optString("url")
                if (directUrl.isNotEmpty() && directUrl.startsWith("http")) {
                    val user = tweet.optJSONObject("user")?.optString("name") ?: "Twitter"
                    return Result(directUrl, "Twitter_${user}.mp4", tweet.optString("text", ""))
                }
            }
        }
        return null
    }

    // ── Facebook — belum didukung tanpa backend ──────────────────────────

    private fun extractFacebook(url: String): Result? {
        return null
    }

    // ── HTTP helpers ─────────────────────────────────────────────────────

    private fun httpGet(
        urlStr: String,
        headers: Map<String, String> = emptyMap(),
        timeoutMs: Int = 15000
    ): String? {
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
        } catch (_: Exception) {
            return null
        } finally {
            conn.disconnect()
        }
    }

    private fun extractJsonString(text: String, key: String): String? {
        val pattern = "\"$key\"\\s*:\\s*\"([^\"]+)\""
        val match = Regex(pattern).find(text) ?: return null
        return match.groupValues.getOrNull(1)
            ?.replace("\\u0026", "&")
            ?.replace("\\/", "/")
    }
}
