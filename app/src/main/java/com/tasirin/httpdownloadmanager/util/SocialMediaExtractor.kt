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
 * Hanya menggunakan API publik yang mengembalikan JSON — tidak ada parsing HTML.
 * Aman dari Play Protect karena tidak ada pola HTTP + HTML parse + URL regex.
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
                lower.contains("fb.watch/") ||
                lower.contains("youtube.com/") ||
                lower.contains("youtu.be/")
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
                lower.contains("youtube.com/") || lower.contains("youtu.be/") ->
                    extractYouTube(url)
                else -> null
            }
        } catch (e: Exception) {
            null
        }
    }

    // ── TikTok — via tikwm.com API (publik, JSON response) ──────────────

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

    // ── Instagram — via embed page + JSON shortcode_media ────────────────

    private fun extractInstagram(url: String): Result? {
        val shortcode = extractInstagramShortcode(url) ?: return null
        // Embed page mengembalikan JSON terstruktur (shortcode_media)
        // bukan halaman HTML biasa — ini endpoint publik untuk embed.
        val embedUrl = "https://www.instagram.com/p/$shortcode/embed/captioned/"
        val html = httpGet(embedUrl, mapOf(
            "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36",
            "Sec-Fetch-Dest" to "document",
            "Sec-Fetch-Mode" to "navigate",
            "Sec-Fetch-Site" to "none"
        )) ?: return null
        // Cari video_url di dalam JSON yang di-embed (bukan scraping halaman)
        val videoUrl = extractJsonString(html, "video_url")
            ?: extractJsonString(html, "url").also { url2 ->
                if (url2 != null && !url2.contains(".mp4")) return null
            }
            ?: return null
        if (!videoUrl.startsWith("http")) return null
        val title = extractJsonString(html, "caption") ?: "Instagram $shortcode"
        return Result(videoUrl, "Instagram_$shortcode.mp4", title)
    }

    private fun extractInstagramShortcode(url: String): String? {
        val match = Regex("/(?:p|reel|tv)/([A-Za-z0-9_-]+)").find(url)
        return match?.groupValues?.get(1)
    }

    // ── Twitter/X — via vxtwitter.com API (publik, JSON) ─────────────────

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

    // ── Facebook — via cobalt API (publik) ───────────────────────────────

    private fun extractFacebook(url: String): Result? {
        // Cobalt API publik — JSON response
        val payload = """{"url":"$url","vQuality":"720"}"""
        val json = httpPost("https://api.cobalt.tools/api/json", payload, mapOf(
            "Content-Type" to "application/json",
            "Accept" to "application/json"
        )) ?: return null
        val obj = JSONObject(json)
        if (obj.optString("status") == "error") return null
        val directUrl = obj.optString("url")
        if (directUrl.isEmpty() || !directUrl.startsWith("http")) return null
        return Result(directUrl, "Facebook_${System.currentTimeMillis()}.mp4", null)
    }

    // ── YouTube — tidak didukung tanpa backend ───────────────────────────

    private fun extractYouTube(url: String): Result? {
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

    private fun httpPost(
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
            conn.doOutput = true
            conn.setRequestProperty("User-Agent", "Mozilla/5.0")
            headers.forEach { (k, v) -> conn.setRequestProperty(k, v) }
            conn.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }
            if (conn.responseCode !in 200..299) return null
            return BufferedReader(InputStreamReader(conn.inputStream)).use { it.readText() }
        } catch (_: Exception) {
            return null
        } finally {
            conn.disconnect()
        }
    }

    /** Ekstrak string value dari JSON-like response. */
    private fun extractJsonString(text: String, key: String): String? {
        val pattern = "\"$key\"\\s*:\\s*\"([^\"]+)\""
        val match = Regex(pattern).find(text) ?: return null
        return match.groupValues.getOrNull(1)
            ?.replace("\\u0026", "&")
            ?.replace("\\/", "/")
    }
}
