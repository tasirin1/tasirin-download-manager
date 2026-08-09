package com.tasirin.httpdownloadmanager.util

import android.annotation.SuppressLint
import android.content.Context
import com.tasirin.httpdownloadmanager.R
import java.security.KeyStore
import java.security.cert.CertificateException
import java.security.cert.CertificateFactory
import java.security.cert.X509Certificate
import javax.net.ssl.HttpsURLConnection
import javax.net.ssl.SSLContext
import javax.net.ssl.TrustManagerFactory
import javax.net.ssl.X509TrustManager

/**
 * Android 6-7 tidak menyimpan root CA Let's Encrypt (ISRG Root X1) dan beberapa
 * root modern lain, jadi HTTPS ke GitHub (release-assets, api, dll) gagal dengan
 * "Trust anchor for certification path not found". Util ini menambah root CA
 * yang di-bundle sebagai anchor tambahan. Hostname verification tetap aktif —
 * hanya menambah trust anchor, tidak menonaktifkan verifikasi apa pun.
 */
object TlsCompat {

    private val EXTRA_ROOTS = listOf(R.raw.isrg_root_x1, R.raw.digicert_global_root_g2)

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

    @SuppressLint("CustomX509TrustManager") // Sengaja: gabung trust anchor sistem + root lama (Android 6-7).
    private fun build(context: Context): SSLContext? = runCatching {
        val systemTmf = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm())
        systemTmf.init(null as KeyStore?)
        val systemTm = systemTmf.trustManagers.filterIsInstance<X509TrustManager>()
            .firstOrNull() ?: return@runCatching null

        val cf = CertificateFactory.getInstance("X.509")
        val ks = KeyStore.getInstance(KeyStore.getDefaultType()).apply { load(null, null) }
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

        val extraTmf = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm())
        extraTmf.init(ks)
        val extraTm = extraTmf.trustManagers.filterIsInstance<X509TrustManager>()
            .firstOrNull() ?: return@runCatching null

        val combined = object : X509TrustManager {
            override fun checkClientTrusted(chain: Array<X509Certificate>, authType: String) {
                systemTm.checkClientTrusted(chain, authType)
            }
            override fun checkServerTrusted(chain: Array<X509Certificate>, authType: String) {
                try {
                    systemTm.checkServerTrusted(chain, authType)
                } catch (e: CertificateException) {
                    extraTm.checkServerTrusted(chain, authType)
                }
            }
            override fun getAcceptedIssuers(): Array<X509Certificate> =
                systemTm.acceptedIssuers + extraTm.acceptedIssuers
        }
        val ctx = SSLContext.getInstance("TLS")
        ctx.init(null, arrayOf<X509TrustManager>(combined), null)
        ctx
    }.getOrNull()
}
