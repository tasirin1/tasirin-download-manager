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
    // Format asset baru (pendek): tasirin-download-manager-v1.0.<n>.apk.
    // Kode versi dibaca dari tag rilis (v1.0.<n> -> 100000+n). Format lama
    // tasirin-download-manager-v1.0.<n>-<code>.apk tetap diterima sebagai
    // fallback (kode dari nama file) agar riwayat rilis lama tetap terbaca.
    private val LEGACY_APK_CODE_RE = Regex("-(\\d+)\\.apk$")
    private val TAG_RUN_RE = Regex("^v?1\\.0\\.(\\d+)$")
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
            if (!name.endsWith(".apk", ignoreCase = true)) continue
            val code = codeFromRelease(tag, name) ?: continue
            val url = a.optString("browser_download_url", "")
            if (url.isEmpty() || !url.startsWith("https://", ignoreCase = true)) continue
            val info = UpdateInfo(code, tag, url, a.optLong("size"), page)
            if (best == null || code > best.versionCode) best = info
        }
        best
    }.getOrNull()

    /** Kode versi dari nama asset lawas atau tag rilis.
     *  Murni (tanpa Android) agar bisa di-unit-test; pemanggil wajib
     *  memfilter asset `.apk` dulu (mapping.txt tidak boleh lolos). */
    internal fun codeFromRelease(tag: String, assetName: String): Int? {
        LEGACY_APK_CODE_RE.find(assetName)?.groupValues?.get(1)?.toIntOrNull()
            ?.let { return it }
        val run = TAG_RUN_RE.find(tag.trim())?.groupValues?.get(1)?.toLongOrNull()
            ?.takeIf { it in 0..20_000_000 }
            ?: return null
        return (100000L + run).toInt()
    }

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
