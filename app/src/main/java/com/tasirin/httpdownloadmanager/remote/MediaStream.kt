package com.tasirin.httpdownloadmanager.remote

import fi.iki.elonen.NanoHTTPD
import java.io.IOException
import java.io.InputStream
import java.net.URLEncoder

internal fun notFound(): NanoHTTPD.Response = NanoHTTPD.newFixedLengthResponse(
    NanoHTTPD.Response.Status.NOT_FOUND,
    "text/plain; charset=utf-8",
    "File not found"
)


/** Respons media/streaming dengan dukungan HTTP Range (resume & seek). */
internal fun streamMedia(
    name: String,
    mime: String,
    input: InputStream,
    total: Long,
    rangeHeader: String?,
    download: Boolean,
    prepositioned: Boolean = false,
    parsedRange: Pair<Long, Long>? = null,
    rangeParsed: Boolean = false
): NanoHTTPD.Response {
    val fallbackName = buildString {
        name.forEach { char ->
            if (char.code in 32..126 && char != '"' && char != '\\') append(char) else append('_')
        }
    }.take(180).ifEmpty { "download" }
    val encodedName = URLEncoder
        .encode(name.take(180), "UTF-8")
        .replace("+", "%20")
    val disposition = if (download) {
        "attachment; filename=\"$fallbackName\"; filename*=UTF-8''$encodedName"
    } else {
        "inline; filename=\"$fallbackName\"; filename*=UTF-8''$encodedName"
    }
    // Hasil parse pemanggil dipakai apa adanya bila sudah diparse
    // (issue #1381: serveMedia mem-parse untuk memosisikan stream lalu
    // meneruskannya ke sini — tanpa ini regex matchEntire berjalan 2x
    // per request). rangeParsed=false (pemanggil lain) = parse di sini,
    // perilaku lama persis. parsedRange null + rangeParsed true berarti
    // "sudah diparse, hasilnya tak ada rentang" — jalur 416 di bawah
    // tetap menilai header-nya sendiri.
    val range = when {
        rangeParsed -> parsedRange
        total > 0 -> parseRange(rangeHeader, total)
        else -> null
    }
    if (range == null && total > 0 && rangeHeader != null && isSatisfiableRange(rangeHeader, total)) {
        runCatching { input.close() }
        return NanoHTTPD.newFixedLengthResponse(
            NanoHTTPD.Response.Status.RANGE_NOT_SATISFIABLE,
            "text/plain; charset=utf-8",
            "Range Not Satisfiable"
        ).also { it.addHeader("Content-Range", "bytes */$total") }
    }
    val response = runCatching {
        when {
            range != null -> {
                val (start, end) = range
                val partLen = end - start + 1
                if (start > 0 && !prepositioned && !skipFully(input, start)) {
                    throw IOException("Unexpected end of stream")
                }
                NanoHTTPD.newFixedLengthResponse(
                    NanoHTTPD.Response.Status.PARTIAL_CONTENT, mime, input, partLen
                ).also {
                    it.addHeader("Content-Range", "bytes $start-$end/$total")
                }
            }
            total > 0 -> NanoHTTPD.newFixedLengthResponse(
                NanoHTTPD.Response.Status.OK, mime, input, total
            )
            else -> NanoHTTPD.newChunkedResponse(NanoHTTPD.Response.Status.OK, mime, input)
        }
    }.getOrElse {
        runCatching { input.close() }
        return NanoHTTPD.newFixedLengthResponse(
            NanoHTTPD.Response.Status.INTERNAL_ERROR,
            "text/plain; charset=utf-8",
            "Streaming error"
        )
    }
    response.addHeader("Accept-Ranges", "bytes")
    response.addHeader("Content-Disposition", disposition)
    return response
}

private val RANGE_RE = Regex("bytes=(\\d*)-(\\d*)", RegexOption.IGNORE_CASE)

internal fun parseRange(header: String?, total: Long): Pair<Long, Long>? {
    if (header.isNullOrBlank() || total <= 0 || header.contains(',')) return null
    val match = RANGE_RE.matchEntire(header.trim()) ?: return null
    val startText = match.groupValues[1]
    val endText = match.groupValues[2]
    return when {
        startText.isNotEmpty() -> {
            // Grup awal non-kosong yang gagal parse berarti digitnya
            // meluap Long: posisi awal raksasa tak terpenuhi -> null
            // (416 mengikuti), BUKAN ditafsir sebagai permintaan
            // sufiks seperti sebelumnya (issue #933).
            val start = startText.toLongOrNull() ?: return null
            if (start < 0 || start >= total) return null
            // Akhir yang meluap dijepit ke ujung berkas.
            val end = if (endText.isEmpty()) {
                total - 1
            } else {
                endText.toLongOrNull() ?: (total - 1)
            }
            // Akhir sebelum awal (mis. bytes=5-3) tidak terpenuhi -> 416,
            // bukan rentang 1-byte semu seperti sebelumnya (coerceIn).
            if (end < start) return null
            start to end.coerceAtMost(total - 1)
        }
        endText.isNotEmpty() -> {
            // Sufiks: panjang yang meluap Long berarti >= total, jadi
            // seluruh berkas (semantik RFC), bukan 416 (issue #933).
            val suffix = endText.toLongOrNull() ?: return 0L to (total - 1)
            if (suffix <= 0) return null
            (total - suffix).coerceAtLeast(0) to (total - 1)
        }
        else -> null
    }
}

/** Offset awal header Range mentah: -1 bila header tak ada, 0 bila
 *  mulai dari nol / bentuk sufiks / tak terurai (ketiganya setara
 *  unduhan dari awal), selain itu offset-nya. Unit "bytes=" dinilai
 *  TIDAK peka huruf (issue #1417), selaras RANGE_RE di atas dan
 *  RFC 9110 — penilaian peka-huruf lama membuat gerbang token ZIP
 *  menilai lanjutan "Bytes=5000-" sebagai inisiasi. Murni agar bisa
 *  di-unit-test. */
internal fun zipRangeStartOf(header: String?): Long {
    val h = header ?: return -1L
    val v = h.trim()
    if (!v.startsWith("bytes=", ignoreCase = true)) return 0L
    val start = v.substring("bytes=".length).substringBefore('-').trim()
    if (start.isEmpty()) return 0L // bentuk sufiks bytes=-N
    return start.toLongOrNull() ?: 0L
}

/** Header Range berbentuk valid (sintaks bytes=) tapi tak terpenuhi total:
 *  layak dibalas 416, bukan 200 full-body. */
internal fun isSatisfiableRange(header: String?, total: Long): Boolean {
    if (header.isNullOrBlank() || total <= 0) return false
    if (header.contains(',')) return false
    return RANGE_RE.matchEntire(header.trim()) != null
}

internal fun skipFully(input: InputStream, requested: Long): Boolean {
    if (requested <= 0) return true
    var remaining = requested
    while (remaining > 0) {
        val skipped = input.skip(remaining)
        if (skipped <= 0) {
            if (input.read() == -1) return false
            remaining--
        } else {
            remaining -= skipped
        }
    }
    return true
}
