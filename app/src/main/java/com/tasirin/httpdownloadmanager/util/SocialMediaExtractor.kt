package com.tasirin.httpdownloadmanager.util

import com.tasirin.httpdownloadmanager.download.RedirectStrategy
import java.io.BufferedReader
import java.io.InputStream
import java.io.InputStreamReader
import java.net.HttpURLConnection
import java.net.URL
import java.util.regex.Pattern
import javax.net.ssl.HttpsURLConnection

/**
 * Ekstrak URL media langsung dari halaman social media (Instagram, Facebook,
 * TikTok, Twitter/X, dll). Bila berhasil mengembalikan URL langsung ke file
 * media, download engine akan mengunduh dari URL tersebut.
 *
 * Cara kerja:
 * 1. Download halaman HTML (head) dengan User-Agent browser
 * 2. Cari meta tag og:video / og:image / video_url / display_url
 * 3. Kembalikan URL pertama yang ditemukan
 *
 * CATATAN: Tidak semua situs bisa diekstrak — Instagram embed, Facebook
 * publik, dan beberapa situs lain mendukung. Situs yang butuh login atau
 * JavaScript rendering tidak akan berhasil.
 */
object SocialMediaExtractor {

    /** Pattern untuk mendeteksi URL social media yang bisa dicoba diekstrak. */
    private val SOCIAL_HOSTS = setOf(
        "instagram.com", "www.instagram.com",
        "facebook.com", "www.facebook.com", "fb.watch",
        "tiktok.com", "www.tiktok.com",
        "twitter.com", "www.twitter.com", "x.com", "www.x.com",
        "vm.tiktok.com",
        "youtube.com", "www.youtube.com", "youtu.be",
        "reddit.com", "www.reddit.com",
        "pinterest.com", "www.pinterest.com",
        "snapchat.com", "www.snapchat.com",
        "threads.net", "www.threads.net"
    )

    /** User-Agent browser realistis untuk menghindari pemblokiran. */
    private const val BROWSER_UA =
        "Mozilla/5.0 (Linux; Android 14; Pixel 8) AppleWebKit/537.36 " +
            "(KHTML, like Gecko) Chrome/124.0.0.0 Mobile Safari/537.36"

    /** Batas ukuran halaman yang diunduh (512 KB cukup untuk meta tags). */
    private const val MAX_PAGE_BYTES = 512 * 1024

    /** Apakah URL ini dari situs social media yang dikenal? */
    fun isSocialMediaUrl(url: String): Boolean = runCatching {
        val host = URL(url).host?.lowercase() ?: return false
        SOCIAL_HOSTS.any { it == host || host.endsWith(".$it") }
    }.getOrDefault(false)

    /**
     * Coba ekstrak URL media langsung dari halaman social media.
     * Mengembalikan direct URL bila ditemukan, null bila gagal.
     *
     * @param url URL halaman social media
     * @param connectTimeoutMs timeout koneksi (ms)
     * @return direct media URL atau null
     */
    fun extractMediaUrl(
        url: String,
        connectTimeoutMs: Int = 15_000
    ): String? {
        if (!isSocialMediaUrl(url)) return null

        return try {
            val conn = openPageConnection(url, connectTimeoutMs)
            try {
                val code = conn.responseCode
                if (code !in 200..399) return null

                // Baca halaman secara terbatas — cukup untuk mencari meta tags
                val html = readLimited(conn.inputStream, MAX_PAGE_BYTES)
                if (html.isEmpty()) return null

                // Coba ekstrak berdasarkan prioritas
                extractFromOgVideo(html)
                    ?: extractFromOgImage(html)
                    ?: extractFromJsonVideoUrl(html)
                    ?: extractFromJsonDisplayUrl(html)
                    ?: extractFromHtmlVideo(html)
                    ?: extractFromHtmlSource(html)
            } finally {
                conn.disconnect()
            }
        } catch (_: Exception) {
            null
        }
    }

    /** Buka koneksi ke halaman dengan User-Agent browser. */
    private fun openPageConnection(url: String, timeoutMs: Int): HttpURLConnection {
        val conn = URL(url).openConnection() as HttpURLConnection
        conn.connectTimeout = timeoutMs
        conn.readTimeout = timeoutMs
        conn.setRequestProperty("User-Agent", BROWSER_UA)
        conn.setRequestProperty("Accept", "text/html,application/xhtml+xml,*/*")
        conn.setRequestProperty("Accept-Language", "en-US,en;q=0.9")
        conn.instanceFollowRedirects = true
        return conn
    }

    /** Baca stream secara terbatas (hemat memori). */
    private fun readLimited(input: InputStream, maxBytes: Int): String {
        val reader = BufferedReader(InputStreamReader(input, Charsets.UTF_8))
        val sb = StringBuilder(maxBytes)
        var totalRead = 0
        val buf = CharArray(8192)
        while (totalRead < maxBytes) {
            val n = reader.read(buf)
            if (n < 0) break
            sb.append(buf, 0, n)
            totalRead += n
        }
        return sb.toString()
    }

    // ========== Instagram & umum: meta tags ==========

    /** Cari `<meta property="og:video" content="URL">`. */
    private fun extractFromOgVideo(html: String): String? {
        val pattern = Pattern.compile(
            """<meta[^>]+property=["']og:video["'][^>]+content=["']([^"']+)["']""",
            Pattern.CASE_INSENSITIVE
        )
        val matcher = pattern.matcher(html)
        if (matcher.find()) return cleanUrl(matcher.group(1))

        // Urutan terbalik (content sebelum property)
        val pattern2 = Pattern.compile(
            """<meta[^>]+content=["']([^"']+)["'][^>]+property=["']og:video["']""",
            Pattern.CASE_INSENSITIVE
        )
        val matcher2 = pattern2.matcher(html)
        if (matcher2.find()) return cleanUrl(matcher2.group(1))
        return null
    }

    /** Cari `<meta property="og:image" content="URL">`. */
    private fun extractFromOgImage(html: String): String? {
        val pattern = Pattern.compile(
            """<meta[^>]+property=["']og:image["'][^>]+content=["']([^"']+)["']""",
            Pattern.CASE_INSENSITIVE
        )
        val matcher = pattern.matcher(html)
        if (matcher.find()) return cleanUrl(matcher.group(1))

        val pattern2 = Pattern.compile(
            """<meta[^>]+content=["']([^"']+)["'][^>]+property=["']og:image["']""",
            Pattern.CASE_INSENSITIVE
        )
        val matcher2 = pattern2.matcher(html)
        if (matcher2.find()) return cleanUrl(matcher2.group(1))
        return null
    }

    // ========== Instagram: JSON embedded data ==========

    /** Cari `"video_url":"URL"` di JSON embedded Instagram. */
    private fun extractFromJsonVideoUrl(html: String): String? {
        val pattern = Pattern.compile(
            """"video_url"\s*:\s*"([^"]+\.mp4[^"]*)""""
        )
        val matcher = pattern.matcher(html)
        if (matcher.find()) return unescapeJson(matcher.group(1))
        return null
    }

    /** Cari `"display_url":"URL"` di JSON embedded (gambar/video). */
    private fun extractFromJsonDisplayUrl(html: String): String? {
        val pattern = Pattern.compile(
            """"display_url"\s*:\s*"([^"]+)""""
        )
        val matcher = pattern.matcher(html)
        if (matcher.find()) return unescapeJson(matcher.group(1))
        return null
    }

    // ========== HTML video element ==========

    /** Cari `<video src="URL">` atau `<video><source src="URL">`. */
    private fun extractFromHtmlVideo(html: String): String? {
        // <video src="URL">
        val videoPattern = Pattern.compile(
            """<video[^>]+src=["']([^"']+)["']""",
            Pattern.CASE_INSENSITIVE
        )
        val videoMatcher = videoPattern.matcher(html)
        if (videoMatcher.find()) return cleanUrl(videoMatcher.group(1))

        // <source src="URL">
        val sourcePattern = Pattern.compile(
            """<source[^>]+src=["']([^"']+)["']""",
            Pattern.CASE_INSENSITIVE
        )
        val sourceMatcher = sourcePattern.matcher(html)
        if (sourceMatcher.find()) return cleanUrl(sourceMatcher.group(1))
        return null
    }

    // ========== YouTube: direct format ==========

    /** Untuk YouTube, coba ekstrak direct video URL dari adaptive formats. */
    private fun extractFromHtmlSource(html: String): String? {
        // YouTube: cari direct video link (format: /videoplayback?...)
        if (html.contains("googlevideo.com") || html.contains("videoplayback")) {
            val pattern = Pattern.compile(
                """(https?://[^"'\s]+googlevideo\.com/videoplayback[^"'\s]+)"""
            )
            val matcher = pattern.matcher(html)
            if (matcher.find()) return cleanUrl(matcher.group(1))
        }
        return null
    }

    // ========== Helpers ==========

    /** Bersihkan URL dari escape JSON dan whitespace. */
    private fun cleanUrl(url: String?): String? {
        if (url.isNullOrBlank()) return null
        val cleaned = url.trim()
            .replace("\\u0026", "&")
            .replace("\\/", "/")
            .replace("&amp;", "&")
        if (!cleaned.startsWith("http")) return null
        return cleaned
    }

    /** Unescape JSON string (escape sequences umum). */
    private fun unescapeJson(s: String?): String? {
        if (s == null) return null
        return s
            .replace("\\/", "/")
            .replace("\\u0026", "&")
            .replace("\\u003C", "<")
            .replace("\\u003E", ">")
            .replace("\\n", "\n")
            .replace("\\t", "\t")
            .replace("\\\\", "\\")
    }
}
