package com.tasirin.httpdownloadmanager.util

import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.os.Build
import android.content.Context
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest
import javax.net.ssl.HttpsURLConnection

data class UpdateInfo(
    val versionCode: Int,
    val versionName: String,
    val apkUrl: String,
    val apkSize: Long
)

/** Cek & unduh APK rilis terbaru dari GitHub. Instalasi dilakukan manual oleh
 *  pengguna (tanpa REQUEST_INSTALL_PACKAGES — mengurangi sinyal berbahaya
 *  bagi Play Protect untuk aplikasi sideload). */
object Updater {
    private val APK_NAME_RE = Regex("-(\\d+)\\.apk$")
    private const val LATEST_API =
        "https://api.github.com/repos/tasirin1/tasirin-download-manager/releases/latest"
    private const val MAX_REDIRECTS = 5
    private const val UA = "TasirinDownloadManager"
    /** SHA-256 fingerprint sertifikat release resmi (alias `tasirin`), huruf kecil tanpa titik dua.
     *  Didapat dari `keytool -list -v` keystore rilis; APK dengan tanda tangan lain ditolak. */
    private const val RELEASE_CERT_SHA256 =
        "c2785a618082683755eeae867e0a2e01f450b1fd448859d1ec21cf854c5713d1"

    fun checkLatest(context: Context): UpdateInfo? = runCatching {
        val body = get(context, LATEST_API) ?: return null
        val json = JSONObject(body)
        val tag = json.optString("tag_name")
        val assets = json.optJSONArray("assets") ?: return null
        var best: UpdateInfo? = null
        for (i in 0 until assets.length()) {
            val a = assets.optJSONObject(i) ?: continue
            val name = a.optString("name", "")
            val code = APK_NAME_RE.find(name)?.groupValues?.get(1)?.toIntOrNull()
                ?: continue
            val url = a.optString("browser_download_url", "")
            if (url.isEmpty()) continue
            val info = UpdateInfo(code, tag, url, a.optLong("size"))
            if (best == null || code > best.versionCode) best = info
        }
        best
    }.getOrNull()

    fun download(
        context: Context,
        info: UpdateInfo,
        onProgress: (done: Long, total: Long) -> Unit
    ): File? = runCatching {
        val dir = File(context.cacheDir, "updates").apply { mkdirs() }
        val target = File(dir, "update-${info.versionCode}.apk")
        if (target.exists() && target.length() == info.apkSize) return target

        var url = info.apkUrl
        var redirects = 0
        while (redirects <= MAX_REDIRECTS) {
            val conn = URL(url).openConnection() as HttpURLConnection
            if (conn is HttpsURLConnection) TlsCompat.apply(conn, context)
            conn.connectTimeout = 15_000
            conn.readTimeout = 30_000
            conn.setRequestProperty("User-Agent", UA)
            conn.instanceFollowRedirects = false
            val code = conn.responseCode
            if (code in 300..399) {
                val loc = conn.getHeaderField("Location") ?: return null
                conn.disconnect()
                url = loc
                redirects++
                continue
            }
            if (code !in 200..299) {
                conn.disconnect()
                return null
            }
            val total = conn.contentLength.toLong().coerceAtLeast(0L)
            conn.inputStream.use { input ->
                target.outputStream().use { out ->
                    val buf = ByteArray(64 * 1024)
                    var done = 0L
                    while (true) {
                        val n = input.read(buf)
                        if (n < 0) break
                        out.write(buf, 0, n)
                        done += n
                        onProgress(done, total)
                    }
                }
            }
            conn.disconnect()
            break
        }
        if (info.apkSize > 0 && target.length() != info.apkSize) {
            target.delete()
            null
        } else {
            target
        }
    }.getOrNull()

    /** Pastikan APK ditandatangani sertifikat release resmi sebelum dipasang.
     *  API 28+ memakai GET_SIGNING_CERTIFICATES (v2/v3), Android 5-8 memakai
     *  GET_SIGNATURES — dua-duanya mengembalikan byte DER sertifikat. */
    fun isSignatureValid(context: Context, file: File): Boolean = runCatching {
        val flags = if (Build.VERSION.SDK_INT >= 28) {
            PackageManager.GET_SIGNING_CERTIFICATES
        } else {
            PackageManager.GET_SIGNATURES
        }
        var info = context.packageManager.getPackageArchiveInfo(file.absolutePath, flags)
        var certs = signingCerts(info)
        if (certs.isEmpty() && Build.VERSION.SDK_INT >= 28) {
            // Fallback: APK bertanda tangan v1 saja tidak selalu mengisi signingInfo.
            info = context.packageManager.getPackageArchiveInfo(
                file.absolutePath, PackageManager.GET_SIGNATURES
            )
            certs = signingCerts(info)
        }
        certs.any { cert ->
            val digest = MessageDigest.getInstance("SHA-256").digest(cert)
            Hex.encode(digest).equals(RELEASE_CERT_SHA256, ignoreCase = true)
        }
    }.getOrDefault(false)

    private fun signingCerts(info: PackageInfo?): List<ByteArray> {
        if (info == null) return emptyList()
        if (Build.VERSION.SDK_INT >= 28) {
            info.signingInfo?.let { si ->
                val signers = si.apkContentsSigners
                if (!signers.isNullOrEmpty()) return signers.map { it.toByteArray() }
            }
        }
        return info.signatures?.map { it.toByteArray() } ?: emptyList()
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
        val body = conn.inputStream.bufferedReader().use { it.readText() }
        conn.disconnect()
        body
    }.getOrNull()
}
