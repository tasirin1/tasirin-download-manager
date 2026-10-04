package com.tasirin.httpdownloadmanager.util

import android.content.Context
import com.tasirin.httpdownloadmanager.R
import java.security.KeyStore
import java.security.cert.CertificateFactory
import java.security.cert.X509Certificate
import javax.net.ssl.HttpsURLConnection
import javax.net.ssl.SSLContext
import javax.net.ssl.TrustManagerFactory
import javax.net.ssl.X509TrustManager

/**
 * Android 6-7 tidak menyimpan root CA Let's Encrypt (ISRG Root X1) dan beberapa
 * root modern lain, jadi HTTPS ke GitHub (release-assets, api, dll) gagal dengan
 * "Trust anchor for certification path not found". CDN XVideos/XNXX memakai rantai
 * Sectigo R46 yang juga belum ada di sebagian perangkat (termasuk yang dimatikan
 * manual di Trusted credentials), sehingga unduhan gagal dengan error yang sama.
 * Util ini menambah root CA yang di-bundle sebagai anchor tambahan. Hostname
 * verification tetap aktif — hanya menambah trust anchor, tidak menonaktifkan
 * verifikasi apa pun.
 */
object TlsCompat {

    private val EXTRA_ROOTS = listOf(
        R.raw.isrg_root_x1,
        R.raw.digicert_global_root_g2,
        R.raw.sectigo_public_server_auth_root_r46
    )

    @Volatile
    private var sslContext: SSLContext? = null

    fun apply(conn: HttpsURLConnection, context: Context) {
        val ctx = sslContext(context) ?: return
        conn.sslSocketFactory = ctx.socketFactory
    }

    private fun sslContext(context: Context): SSLContext? {
        sslContext?.let { return it }
        synchronized(this) {
            sslContext?.let { return it }
            sslContext = build(context)
            return sslContext
        }
    }

    // Sengaja: gabung trust anchor sistem + root lama (Android 6-7) dalam SATU
    // store sehingga rantai cross-signed yang butuh kedua sisi tetap lolos.
    // (Sebelumnya: coba-sistem-lalu-ekstra; rantai gabungan selalu gagal dan
    // exception asli sistem tertutup exception ekstra.)
    private fun build(context: Context): SSLContext? = runCatching {
        val systemTmf = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm())
        systemTmf.init(null as KeyStore?)
        val systemTm = systemTmf.trustManagers.filterIsInstance<X509TrustManager>()
            .firstOrNull() ?: return@runCatching null

        val cf = CertificateFactory.getInstance("X.509")
        val ks = KeyStore.getInstance(KeyStore.getDefaultType()).apply { load(null, null) }
        systemTm.acceptedIssuers.forEachIndexed { i, cert ->
            runCatching { ks.setCertificateEntry("sys-$i", cert) }
        }
        var loaded = 0
        for (resId in EXTRA_ROOTS) {
            runCatching {
                val cert = context.resources.openRawResource(resId).use {
                    cf.generateCertificate(it) as X509Certificate
                }
                ks.setCertificateEntry("root-$resId", cert)
                loaded++
            }
        }
        if (loaded == 0) return@runCatching null

        val tmf = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm())
        tmf.init(ks)
        val tm = tmf.trustManagers.filterIsInstance<X509TrustManager>()
            .firstOrNull() ?: return@runCatching null
        val ctx = SSLContext.getInstance("TLS")
        ctx.init(null, arrayOf<X509TrustManager>(tm), null)
        ctx
    }.getOrNull()
}
