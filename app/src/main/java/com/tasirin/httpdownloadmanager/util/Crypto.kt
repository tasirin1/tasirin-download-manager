package com.tasirin.httpdownloadmanager.util

import androidx.annotation.RequiresApi
import android.os.Build
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

object Crypto {

    private const val ALIAS = "httpdm_cred_v1"
    private const val PREFIX = "v1:"
    private const val TRANSFORMATION = "AES/GCM/NoPadding"
    private val KEY_LOCK = Any()
    @Volatile private var cachedKey: SecretKey? = null

    /** Enkripsi nilai dengan kunci Android Keystore (AES-GCM, API 23+).
     *  Di Android 5.0-5.1 (API 21-22) Keystore AES belum tersedia,
     *  jadi nilai disimpan apa adanya. */
    fun encrypt(plain: String): String {
        if (plain.isEmpty()) return ""
        if (Build.VERSION.SDK_INT < 23) return plain
        return runCatching {
            val cipher = Cipher.getInstance(TRANSFORMATION)
            cipher.init(Cipher.ENCRYPT_MODE, key())
            PREFIX + b64(cipher.iv) + ":" + b64(cipher.doFinal(plain.toByteArray(Charsets.UTF_8)))
        }.getOrDefault(plain)
    }

    /** Dekripsi; nilai lama tanpa prefix dianggap plaintext (data lama). */
    fun decrypt(payload: String?): String {
        if (payload.isNullOrEmpty()) return ""
        if (!payload.startsWith(PREFIX)) return payload
        if (Build.VERSION.SDK_INT < 23) return ""
        return runCatching {
            val body = payload.removePrefix(PREFIX)
            val idx = body.indexOf(':')
            if (idx <= 0) return@runCatching ""
            val iv = Base64.decode(body.substring(0, idx), Base64.NO_WRAP)
            val ct = Base64.decode(body.substring(idx + 1), Base64.NO_WRAP)
            val cipher = Cipher.getInstance(TRANSFORMATION)
            cipher.init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, iv))
            String(cipher.doFinal(ct), Charsets.UTF_8)
        }.getOrDefault("")
    }

    /** AES AndroidKeyStore hanya tersedia API 23+; pemanggil (encrypt/decrypt)
     *  sudah menangani fallback untuk Android 5.0-5.1. */
    @RequiresApi(Build.VERSION_CODES.M)
    private fun key(): SecretKey {
        cachedKey?.let { return it }
        synchronized(KEY_LOCK) {
            cachedKey?.let { return it }
            val ks = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
            val existing = (ks.getEntry(ALIAS, null) as? KeyStore.SecretKeyEntry)?.secretKey
            val k = existing ?: run {
                val gen = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
                gen.init(
                    KeyGenParameterSpec.Builder(
                        ALIAS,
                        KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT
                    )
                        .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                        .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                        .build()
                )
                gen.generateKey()
            }
            cachedKey = k
            return k
        }
    }

    private fun b64(bytes: ByteArray): String =
        Base64.encodeToString(bytes, Base64.NO_WRAP)
}
