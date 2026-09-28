package com.tasirin.httpdownloadmanager.util

import android.content.Context
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import javax.net.ssl.HttpsURLConnection

data class UpdateInfo(
    val versionCode: Int,
    val versionName: String,
    val apkUrl: String,
    val apkSize: Long,
    val pageUrl: String = ""
)

/** Cek rilis terbaru dari GitHub (cek-saja, tanpa mengunduh APK).
 *  Aplikasi tidak pernah memegang byte APK update sendiri (pola dropper yang
 *  dicurigai Play Protect): pengguna mengambil versi baru lewat browser dari
 *  halaman rilis resmi, lalu memasang manual. */
object Updater {
    private val APK_NAME_RE = Regex("-(\\d+)\\.apk$")
    private const val LATEST_API =
        "https://api.github.com/repos/tasirin1/tasirin-download-manager/releases/latest"
    private const val UA = "TasirinDownloadManager"

    fun checkLatest(context: Context): UpdateInfo? = runCatching {
        val body = get(context, LATEST_API) ?: return null
        val json = JSONObject(body)
        val tag = json.optString("tag_name")
        val page = json.optString("html_url")
        val assets = json.optJSONArray("assets") ?: return null
        var best: UpdateInfo? = null
        for (i in 0 until assets.length()) {
            val a = assets.optJSONObject(i) ?: continue
            val name = a.optString("name", "")
            val code = APK_NAME_RE.find(name)?.groupValues?.get(1)?.toIntOrNull()
                ?: continue
            val url = a.optString("browser_download_url", "")
            if (url.isEmpty() || !url.startsWith("https://", ignoreCase = true)) continue
            val info = UpdateInfo(code, tag, url, a.optLong("size"), page)
            if (best == null || code > best.versionCode) best = info
        }
        best
    }.getOrNull()

    private fun get(context: Context, url: String): String? = runCatching {
        val conn = URL(url).openConnection() as HttpURLConnection
        if (conn is HttpsURLConnection) TlsCompat.apply(conn, context)
        conn.connectTimeout = 15_000
        conn.readTimeout = 15_000
        conn.setRequestProperty("Accept", "application/vnd.github+json")
        conn.setRequestProperty("User-Agent", UA)
        if (conn.responseCode != 200) {
            conn.disconnect()
            return null
        }
        try {
            conn.inputStream.bufferedReader().use { r ->
                val sb = StringBuilder()
                val buf = CharArray(8192)
                var total = 0
                while (true) {
                    val n = r.read(buf)
                    if (n < 0) break
                    total += n
                    // Respons releases bisa besar; 512KB cukup untuk cari asset.
                    // Respons terpotong tidak dikembalikan agar JSON setengah jadi
                    // tidak salah memilih asset.
                    if (total > 524_288) return null
                    sb.append(buf, 0, n)
                }
                sb.toString()
            }
        } finally {
            conn.disconnect()
        }
    }.getOrNull()
}
