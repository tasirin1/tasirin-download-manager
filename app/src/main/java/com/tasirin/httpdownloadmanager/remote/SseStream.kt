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
    // Pil tutup: membangunkan queue.poll yang sedang menunggu 25 dtk
    // supaya koneksi mati tidak menggantung; diabaikan oleh read normal.
    private val POISON = ByteArray(0)

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
        // offer (bukan put): jangan blokir thread pemanggil saat antrean penuh.
        queue.offer(POISON)
    }

    override fun read(b: ByteArray, off: Int, len: Int): Int {
        // Kontrak InputStream: tolak argumen di luar batas sebelum menyentuh antrean.
        if (off < 0 || len < 0 || off > b.size || len > b.size - off) throw IndexOutOfBoundsException()
        if (len == 0) return 0
        var written = 0
        while (written < len) {
            val cur = current
            if (cur != null && pos < cur.size) {
                val n = minOf(len - written, cur.size - pos)
                System.arraycopy(cur, pos, b, off + written, n)
                pos += n
                written += n
                if (written >= len) break
                current = null
                continue
            }
            if (written > 0) break
            val byte = read()
            if (byte < 0) return if (written > 0) written else -1
            b[off + written] = byte.toByte()
            written++
        }
        return written
    }

    override fun close() {
        isClosed = true
        queue.offer(POISON)
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
                // Pil tutup atau antrean yang ditutup: akhiri stream, jangan
                // sajikan heartbeat palsu setelah klien/server menutup.
                if (next.isEmpty() || isClosed) return -1
                current = next
                pos = 0
            } else {
                if (isClosed) return -1
                // Tidak ada data selama timeout: kirim komentar heartbeat
                // supaya koneksi tidak diputus proxy/timeout.
                current = ": ping\n\n".toByteArray(Charsets.UTF_8)
                pos = 0
            }
        }
    }
}
