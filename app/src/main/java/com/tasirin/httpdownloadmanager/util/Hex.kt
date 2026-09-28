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

/** Digest SHA-256 per-thread: MessageDigest tidak thread-safe tapi
 *  getInstance() tiap panggil mahal (dipakai kunci thumbnail per request). */
private val sha256Local: ThreadLocal<MessageDigest> = ThreadLocal.withInitial {
    MessageDigest.getInstance("SHA-256")
}

/** Digest SHA-256 per-thread untuk jalur streaming (reset sebelum dipakai). */
fun sha256Digest(): MessageDigest {
    val md = sha256Local.get()
    md.reset()
    return md
}

fun sha256Strings(vararg parts: String, separator: Byte = 0): ByteArray {
    val md = sha256Local.get()
    md.reset()
    parts.forEachIndexed { i, s ->
        if (i > 0) md.update(separator)
        md.update(s.toByteArray(Charsets.UTF_8))
    }
    return md.digest()
}

fun sha256Bytes(vararg parts: ByteArray, separator: Byte = 0): ByteArray {
    val md = sha256Local.get()
    md.reset()
    parts.forEachIndexed { i, b ->
        if (i > 0) md.update(separator)
        md.update(b)
    }
    return md.digest()
}

/** Hash SHA-256 dalam bentuk hex huruf kecil — dipakai untuk normalisasi nilai
 *  PIN lama dan kebutuhan checksum; plaintext tetap tidak pernah ditambahkan. */
fun sha256Hex(input: String): String {
    return Hex.encode(sha256Bytes(input.toByteArray(Charsets.UTF_8)))
}
