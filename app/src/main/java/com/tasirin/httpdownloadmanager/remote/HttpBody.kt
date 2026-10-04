package com.tasirin.httpdownloadmanager.remote

import fi.iki.elonen.NanoHTTPD
import java.io.IOException
import java.io.OutputStream
import java.net.URLDecoder

private const val MAX_BODY_SIZE = 4L * 1024 * 1024
private const val MAX_UPLOAD_BYTES = 2L * 1024 * 1024 * 1024
/** Batas baca 0-byte beruntun: kontrak InputStream membolehkan read() = 0
 *  (bukan EOF), dan loop tanpa penanganan ini busy-loop selamanya menahan
 *  thread HTTP. Socket blocking praktis tak pernah 0; puluhan kali
 *  beruntun = stream macet, wajib gagal cepat. */
private const val MAX_ZERO_READS = 32

/** Body POST melebihi batas; koneksi harus ditutup agar sisa body tidak
 * terbaca sebagai request HTTP berikutnya pada keep-alive. */
internal class BodyTooLargeException : IOException("Request body too large")

/** Body chunked tanpa Content-Length terpakai: panjang tak bisa dihitung di
 *  muka, jadi pemanggil tak boleh mengasumsikan body sudah habis (desync
 *  keep-alive) atau mengabaikannya diam-diam (form terpotong). Murni agar
 *  bisa di-unit-test tanpa sesi HTTP. */
/** Panjang body multipart yang tak boleh masuk parseBody: negatif berarti
 *  invalid, nol + chunked berarti tmp tanpa batas sampai EOF (DoS disk).
 *  Nol non-chunked (form kosong) tetap diproses seperti sebelumnya.
 *  Murni agar bisa di-unit-test tanpa sesi HTTP. */
internal fun isUnsafeMultipartLength(declared: Long, headers: Map<String, String>): Boolean =
    declared < 0 || (declared == 0L && isChunkedBody(headers))

internal fun isChunkedBody(headers: Map<String, String>): Boolean {
    val encoding = headers.entries
        .firstOrNull { it.key.equals("transfer-encoding", ignoreCase = true) }
        ?.value.orEmpty()
    return encoding.contains("chunked", ignoreCase = true)
}

/** Baca form POST (x-www-form-urlencoded) dari body sesi NanoHTTPD,
 *  gabung dengan parameter query. Dibatasi 4 MB. */
internal fun readForm(session: NanoHTTPD.IHTTPSession): Map<String, String> {
    val map = mutableMapOf<String, String>()
    session.getParameters().forEach { (k, v) ->
        v.firstOrNull()?.let { map[k] = it }
    }
    val contentType = session.headers["content-type"].orEmpty()
    if (contentType.startsWith("multipart/form-data", ignoreCase = true)) {
        // parseBody NanoHTTPD menulis file tmp tanpa batas internal — tolak
        // sejak awal bila Content-Length deklarasi melebihi batas form.
        val declared = session.headers["content-length"]?.toLongOrNull() ?: 0L
        if (isUnsafeMultipartLength(declared, session.headers)) {
            // Tanpa Content-Length valid (negatif/chunked), parseBody menyedot
            // body sampai EOF ke tmp tanpa batas: POST chunked raksasa ke
            // /api/login (pra-auth) bisa memenuhi disk sebelum cek ukuran tmp
            // di bawah berjalan. Tolak eksplisit seperti cabang urlencoded.
            if (declared < 0) throw IOException("Invalid content length")
            throw BodyTooLargeException()
        }
        if (declared > MAX_BODY_SIZE) throw BodyTooLargeException()
        // parseBody melempar saat body rusak/terpotong: teruskan sebagai
        // IOException agar pemanggil menutup koneksi, bukan diam-diam memakai
        // parameter query saja sebagai form valid (fail-open).
        val files = mutableMapOf<String, String>()
        try {
            session.parseBody(files)
            session.getParameters().forEach { (k, v) -> v.firstOrNull()?.let { map[k] = it } }
            // parseBody menulis tmp tanpa batas bila Content-Length tak ada
            // (chunked): tolak bila jumbo agar disk tak penuh diam-diam.
            val oversized = files.values.any { tmp ->
                runCatching { java.io.File(tmp).length() }.getOrDefault(0L) > MAX_BODY_SIZE
            }
            if (oversized) throw BodyTooLargeException()
        } catch (e: BodyTooLargeException) {
            files.values.forEach { tmp -> runCatching { java.io.File(tmp).delete() } }
            throw e
        } catch (e: Exception) {
            files.values.forEach { tmp -> runCatching { java.io.File(tmp).delete() } }
            throw IOException("Malformed multipart body")
        }
        files.values.forEach { tmp -> runCatching { java.io.File(tmp).delete() } }
        return map
    }
    val rawLength = session.headers["content-length"]?.toLongOrNull() ?: 0L
    if (rawLength < 0) throw IOException("Invalid content length")
    if (rawLength > MAX_BODY_SIZE) throw BodyTooLargeException()
    if (rawLength == 0L && isChunkedBody(session.headers)) {
        // Body chunked tak bisa dibaca terbatas di sini: tolak eksplisit
        // (413 + tutup koneksi) agar form tak diparse diam-diam dari query saja.
        throw BodyTooLargeException()
    }
    val length = rawLength.toInt()
    if (length > 0) {
        // Kumpulkan byte dulu, decode UTF-8 sekali di akhir: decode per chunk
        // membelah karakter multi-byte di batas 8KB menjadi mojibake
        // (mis. postBody/URL beraksen rusak diam-diam).
        val buf = ByteArray(8192)
        val collected = java.io.ByteArrayOutputStream(length.coerceAtMost(65536))
        var remaining = length
        var zeroStreak = 0
        while (remaining > 0) {
            val toRead = minOf(buf.size, remaining)
            val read = session.inputStream.read(buf, 0, toRead)
            // Stream putus sebelum Content-Length terpenuhi: jangan parse
            // parsial sebagai form valid (bisa jadi login/pin terpotong).
            if (read == -1) throw IOException("Request body truncated")
            // Baca 0 bukan EOF: tanpa guard loop busy-loop selamanya.
            if (read == 0) {
                if (++zeroStreak >= MAX_ZERO_READS) throw IOException("Request body stalled")
                continue
            }
            zeroStreak = 0
            collected.write(buf, 0, read)
            remaining -= read
        }
        // Loop tanpa split("&") — hindari alokasi List<String> per request POST.
        val body = String(collected.toByteArray(), Charsets.UTF_8)
        var start = 0
        while (start <= body.length) {
            val amp = body.indexOf('&', start)
            val end = if (amp >= 0) amp else body.length
            if (end > start) {
                val eq = body.indexOf('=', start)
                if (eq > start && eq < end) {
                    val key = runCatching { URLDecoder.decode(body.substring(start, eq), "UTF-8") }.getOrNull()
                    val value = runCatching { URLDecoder.decode(body.substring(eq + 1, end), "UTF-8") }.getOrNull()
                    if (key != null && value != null) map[key] = value
                }
            }
            if (amp < 0) break
            start = amp + 1
        }
    }
    return map
}

/** Parameter query/form sesi — pengganti `parms` yang deprecated di
 *  NanoHTTPD 2.3.1. Mengembalikan nilai pertama per key (null bila kosong). */
internal fun NanoHTTPD.IHTTPSession.param(key: String): String? =
    getParameters()[key]?.firstOrNull()

/** Habiskan body sesi tanpa memprosesnya (untuk request yang body-nya
 *  sengaja diabaikan) supaya koneksi bisa dipakai ulang.
 *  Kembalikan true bila body habis terdrain; false bila body melebihi batas
 *  atau stream terputus lebih awal — pemanggil wajib menutup koneksi
 *  (closeConnection) agar sisa byte tidak dibaca sebagai request berikutnya. */
internal fun drainBody(session: NanoHTTPD.IHTTPSession): Boolean {
    val declared = session.headers["content-length"]?.toLongOrNull() ?: 0L
    if (declared <= 0) {
        // Tanpa Content-Length (mis. chunked) sisa body tak bisa dihitung:
        // paksa tutup koneksi agar byte body tak terbaca sebagai request berikutnya.
        if (isChunkedBody(session.headers)) return false
        return true
    }
    // Content-Length + chunked sekaligus = request ambigu (smuggling);
    // klien normal tak pernah mengirim keduanya: tutup koneksi.
    if (isChunkedBody(session.headers)) return false
    if (declared > MAX_UPLOAD_BYTES) return false
    val buffer = ByteArray(64 * 1024)
    var remaining = declared
    var zeroStreak = 0
    while (remaining > 0) {
        val chunk = minOf(buffer.size.toLong(), remaining).toInt()
        val read = session.inputStream.read(buffer, 0, chunk)
        if (read == -1) return false
        // Baca 0 bukan EOF: tanpa guard loop busy-loop selamanya; false =
        // pemanggil menutup koneksi seperti stream terputus.
        if (read == 0) {
            if (++zeroStreak >= MAX_ZERO_READS) return false
            continue
        }
        zeroStreak = 0
        remaining -= read
    }
    return true
}

/** Salin body upload ke OutputStream tanpa menutup session.inputStream
 *  (NanoHTTPD menutupnya sendiri setelah serve() selesai).
 *  length <= 0 (chunked tanpa Content-Length): baca sampai EOF dengan batas
 *  MAX_UPLOAD_BYTES agar body tidak tertinggal di stream keep-alive. */
internal fun copyUploadBody(session: NanoHTTPD.IHTTPSession, length: Long, out: OutputStream) {
    val input = session.inputStream
    val buffer = ByteArray(64 * 1024)
    if (length <= 0) {
        var total = 0L
        var zeroStreak = 0
        while (true) {
            val read = input.read(buffer)
            if (read == -1) break
            // Baca 0 bukan EOF: tanpa guard loop busy-loop selamanya.
            if (read == 0) {
                if (++zeroStreak >= MAX_ZERO_READS) throw IOException("Upload body stalled")
                continue
            }
            zeroStreak = 0
            total += read
            if (total > MAX_UPLOAD_BYTES) throw BodyTooLargeException()
            out.write(buffer, 0, read)
        }
        return
    }
    var remaining = length
    var zeroStreak = 0
    while (remaining > 0) {
        val chunk = minOf(buffer.size.toLong(), remaining).toInt()
        val read = input.read(buffer, 0, chunk)
        if (read == -1) break
        // Baca 0 bukan EOF: tanpa guard loop busy-loop selamanya.
        if (read == 0) {
            if (++zeroStreak >= MAX_ZERO_READS) throw IOException("Upload body stalled")
            continue
        }
        zeroStreak = 0
        out.write(buffer, 0, read)
        remaining -= read
    }
    if (remaining > 0) {
        throw IOException(
            "Connection lost: only ${length - remaining} of $length bytes received"
        )
    }
}
