package com.tasirin.httpdownloadmanager.remote

import java.io.InputStream
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit

/** Stream respons SSE: antrean terbatas + heartbeat tiap [TIMEOUT_SECONDS] detik
 *  agar koneksi tidak diputus proxy; menutup diri bila antrean penuh. */
internal class SseStream : InputStream() {
    private companion object {
        /** Waktu tunggu polling (detik): harus lebih besar dari interval pump
         *  di HttpControlServer (1 dtk) supaya data selalu ada sebelum timeout. */
        const val TIMEOUT_SECONDS = 25L
    }
    private val queue = LinkedBlockingQueue<ByteArray>(32)

    @Volatile
    var isClosed = false
        private set

    private var current: ByteArray? = null
    private var pos = 0

    fun push(text: String) {
        if (!isClosed && !queue.offer(text.toByteArray(Charsets.UTF_8))) {
            // Antrean penuh berarti klien tidak lagi membaca (koneksi putus).
            isClosed = true
        }
    }

    fun closeStream() {
        isClosed = true
    }

    override fun close() {
        isClosed = true
        super.close()
    }

    override fun read(): Int {
        while (true) {
            val cur = current
            if (cur != null && pos < cur.size) return cur[pos++].toInt() and 0xff
            if (isClosed) return -1
            val next = try {
                queue.poll(TIMEOUT_SECONDS, TimeUnit.SECONDS)
            } catch (_: InterruptedException) {
                Thread.currentThread().interrupt()
                return -1
            }
            if (next != null) {
                current = next
                pos = 0
            } else {
                // Tidak ada data selama timeout: kirim komentar heartbeat
                // supaya koneksi tidak diputus proxy/timeout.
                current = ": ping\n\n".toByteArray(Charsets.UTF_8)
                pos = 0
            }
        }
    }
}
