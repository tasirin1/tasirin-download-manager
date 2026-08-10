package com.tasirin.httpdownloadmanager.util

import java.security.MessageDigest

/** Enkode byte ke hex huruf kecil — dipakai untuk kunci cache thumbnail dan
 *  verifikasi sertifikat; satu implementasi cepat menggantikan tiga duplikat. */
object Hex {

    private val DIGITS = "0123456789abcdef".toCharArray()

    fun encode(bytes: ByteArray): String {
        val sb = StringBuilder(bytes.size * 2)
        for (b in bytes) {
            val v = b.toInt() and 0xFF
            sb.append(DIGITS[v ushr 4])
            sb.append(DIGITS[v and 0x0F])
        }
        return sb.toString()
    }
}

/** Hash SHA-256 dalam bentuk hex huruf kecil — dipakai untuk menyimpan PIN
 *  server sebagai hash (plaintext tidak pernah ditulis ke disk). */
fun sha256Hex(input: String): String {
    val digest = MessageDigest.getInstance("SHA-256")
        .digest(input.toByteArray(Charsets.UTF_8))
    return Hex.encode(digest)
}
