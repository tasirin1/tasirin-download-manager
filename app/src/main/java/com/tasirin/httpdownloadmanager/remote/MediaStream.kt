package com.tasirin.httpdownloadmanager.remote

import fi.iki.elonen.NanoHTTPD
import java.io.InputStream

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
    download: Boolean
): NanoHTTPD.Response {
    val safeName = name.replace("\"", "_").replace("\\", "_")
    val disposition = if (download) {
        "attachment; filename=\"$safeName\""
    } else {
        "inline; filename=\"$safeName\""
    }
    val response = runCatching {
        val range = if (total > 0) parseRange(rangeHeader, total) else null
        when {
            range != null -> {
                val (start, end) = range
                val partLen = end - start + 1
                if (start > 0) skipFully(input, start)
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
            "Error: ${it.message}"
        )
    }
    response.addHeader("Accept-Ranges", "bytes")
    response.addHeader("Content-Disposition", disposition)
    return response
}

private val RANGE_RE = Regex("bytes=(\\d*)-(\\d*)")

internal fun parseRange(header: String?, total: Long): Pair<Long, Long>? {
    if (header.isNullOrBlank() || total <= 0) return null
    val m = Regex("bytes=(\\d*)-(\\d*)").find(header) ?: return null
    val start = m.groupValues[1].toLongOrNull()
    val endRaw = m.groupValues[2].toLongOrNull()
    return when {
        start != null -> {
            val s = start.coerceIn(0, total - 1)
            val e = (endRaw ?: (total - 1)).coerceIn(s, total - 1)
            s to e
        }
        endRaw != null -> {
            val n = endRaw.coerceAtLeast(1)
            (total - n).coerceAtLeast(0) to (total - 1)
        }
        else -> null
    }
}

internal fun skipFully(input: InputStream, n: Long) {
    var remaining = n
    while (remaining > 0) {
        val skipped = input.skip(remaining)
        if (skipped <= 0) {
            if (input.read() == -1) return
            remaining--
        } else {
            remaining -= skipped
        }
    }
}
