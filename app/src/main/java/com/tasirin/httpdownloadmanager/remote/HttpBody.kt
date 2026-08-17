package com.tasirin.httpdownloadmanager.remote

import fi.iki.elonen.NanoHTTPD
import java.io.IOException
import java.io.OutputStream
import java.net.URLDecoder

private const val MAX_BODY_SIZE = 4L * 1024 * 1024
private const val MAX_UPLOAD_BYTES = 2L * 1024 * 1024 * 1024

/** Baca form POST (x-www-form-urlencoded) dari body sesi NanoHTTPD,
 *  gabung dengan parameter query. Dibatasi 4 MB. */
internal fun readForm(session: NanoHTTPD.IHTTPSession): Map<String, String> {
    val map = mutableMapOf<String, String>()
    session.parms.forEach { (k, v) -> map[k] = v }
    val length = (session.headers["content-length"]?.toLongOrNull() ?: 0L)
        .coerceIn(0L, MAX_BODY_SIZE).toInt()
    if (length > 0) {
        // Baca per-baris tanpa alokasi ByteArray(length) penuh — hemats memori
        // untuk form kecil (100 byte) yang sebelumnya alokasi 4MB.
        val buf = ByteArray(8192)
        val sb = StringBuilder(length.coerceAtMost(65536))
        var remaining = length
        while (remaining > 0) {
            val toRead = minOf(buf.size, remaining)
            val read = session.inputStream.read(buf, 0, toRead)
            if (read == -1) break
            sb.append(String(buf, 0, read, Charsets.UTF_8))
            remaining -= read
        }
        // Loop tanpa split("&") — hindari alokasi List<String> per request POST.
        val body = sb.toString()
        var start = 0
        while (start <= body.length) {
            val amp = body.indexOf('&', start)
            val end = if (amp >= 0) amp else body.length
            if (end > start) {
                val eq = body.indexOf('=', start)
                if (eq > start && eq < end) {
                    val key = URLDecoder.decode(body.substring(start, eq), "UTF-8")
                    val value = URLDecoder.decode(body.substring(eq + 1, end), "UTF-8")
                    map[key] = value
                }
            }
            if (amp < 0) break
            start = amp + 1
        }
    }
    return map
}

/** Habiskan body sesi tanpa memprosesnya (untuk request yang body-nya
 *  sengaja diabaikan) supaya koneksi bisa dipakai ulang. */
internal fun drainBody(session: NanoHTTPD.IHTTPSession) {
    val length = (session.headers["content-length"]?.toLongOrNull() ?: 0L)
        .coerceAtMost(MAX_UPLOAD_BYTES)
    if (length <= 0) return
    val buffer = ByteArray(64 * 1024)
    var remaining = length
    while (remaining > 0) {
        val chunk = minOf(buffer.size.toLong(), remaining).toInt()
        val read = session.inputStream.read(buffer, 0, chunk)
        if (read == -1) break
        remaining -= read
    }
}

/** Salin body upload ke OutputStream tanpa menutup session.inputStream
 *  (NanoHTTPD menutupnya sendiri setelah serve() selesai). */
internal fun copyUploadBody(session: NanoHTTPD.IHTTPSession, length: Long, out: OutputStream) {
    val input = session.inputStream
    val buffer = ByteArray(64 * 1024)
    var remaining = length
    while (remaining > 0) {
        val chunk = minOf(buffer.size.toLong(), remaining).toInt()
        val read = input.read(buffer, 0, chunk)
        if (read == -1) break
        out.write(buffer, 0, read)
        remaining -= read
    }
    if (remaining > 0) {
        throw IOException(
            "Connection lost: only ${length - remaining} of $length bytes received"
        )
    }
}
