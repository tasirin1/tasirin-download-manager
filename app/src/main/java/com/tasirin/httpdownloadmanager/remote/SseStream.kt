package com.tasirin.httpdownloadmanager.remote

import java.io.InputStream
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit

/** Stream respons SSE: antrean terbatas + heartbeat tiap [TIMEOUT_SECONDS] detik
 *  agar koneksi tidak diputus proxy; menutup diri bila antrean penuh.
 *  [authorizedSecret] adalah secret sesi saat koneksi diotorisasi: pump
 *  mencabut stream yang secret tangkapannya tak lagi sama dengan secret
 *  kini (logout / ganti PIN me-rotate secret, issue #731). */
internal class SseStream(val authorizedSecret: String = "") : InputStream() {
    private companion object {
        /** Waktu tunggu polling (detik): harus lebih besar dari interval pump
         *  di HttpControlServer (1 dtk) supaya data selalu ada sebelum timeout. */
        const val TIMEOUT_SECONDS = 25L
    }
    private val queue = LinkedBlockingQueue<ByteArray>(32)
    // Pil tutup: membangunkan queue.poll yang sedang menunggu 25 dtk
    // supaya koneksi mati tidak menggantung; diabaikan oleh read normal.
    private val POISON = ByteArray(0)
    // Heartbeat statis: array read-only, aman dipakai ulang tiap timeout
    // (read hanya menyalin keluar, tak pernah menulis).
    private val PING = ": ping\n\n".toByteArray(Charsets.UTF_8)

    @Volatile
    var isClosed = false
        private set

    private var current: ByteArray? = null
    private var pos = 0

    fun push(text: String) {
        if (isClosed) return
        if (!queue.offer(text.toByteArray(Charsets.UTF_8))) {
            // Antrean penuh berarti klien tidak lagi membaca (koneksi putus).
            isClosed = true
            wakeBlockedReader()
        }
    }

    /** Dorong frame yang TIDAK boleh hilang (frame shutdown): bila
     *  antrean penuh, buang frame TERLAMA yang belum terbaca untuk
     *  memberi tempat, bukan membuang frame ini. push biasa pada
     *  antrean penuh justru membuang frame shutdown untuk klien
     *  lambat — persis klien yang antreannya penuh — sehingga ia
     *  hanya melihat EOF dan reconnect agresif ke server yang
     *  sengaja dimatikan (kembali ke perilaku pra-#288, #327). */
    fun pushUrgent(text: String) {
        if (isClosed) return
        if (!queue.offer(text.toByteArray(Charsets.UTF_8))) {
            queue.poll()
            queue.offer(text.toByteArray(Charsets.UTF_8))
        }
    }

    /** Bangunkan poll(25 dtk) yang sedang menunggu: flag saja tak cukup.
     *  offer dulu agar frame basi tak terbuang sia-sia; hanya bila penuh
     *  (offer gagal) buang satu lalu selipkan pil tutup. Non-blokir. */
    private fun wakeBlockedReader() {
        if (!queue.offer(POISON)) {
            queue.poll()
            queue.offer(POISON)
        }
    }

    fun closeStream() {
        isClosed = true
        wakeBlockedReader()
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
        wakeBlockedReader()
        super.close()
    }

    override fun read(): Int {
        while (true) {
            val cur = current
            if (cur != null && pos < cur.size) return cur[pos++].toInt() and 0xff
            // Stream tertutup hanya berakhir bila antrean juga kosong:
            // frame asli yang terantre sebelum penutupan tetap disajikan
            // (#288, lihat cabang pil tutup di bawah).
            if (isClosed && queue.isEmpty()) return -1
            val next = try {
                queue.poll(TIMEOUT_SECONDS, TimeUnit.SECONDS)
            } catch (_: InterruptedException) {
                Thread.currentThread().interrupt()
                return -1
            }
            if (next != null) {
                // Pil tutup: akhiri stream. Frame ASLI yang terantre
                // sebelum penutupan tetap disajikan walau isClosed sudah
                // naik (#288): frame {"shutdown":true} dari stopServer
                // tiba persis dalam urutan ini (push lalu closeStream) —
                // membuangnya membuat klien hanya melihat EOF dan
                // reconnect agresif ke server yang memang dimatikan.
                // Sesudah frame terakhir tersaji, antrean hanya
                // menyisakan pil tutup sehingga read berikutnya berakhir.
                if (next === POISON) return -1
                current = next
                pos = 0
            } else {
                if (isClosed) return -1
                // Tidak ada data selama timeout: kirim komentar heartbeat
                // supaya koneksi tidak diputus proxy/timeout.
                current = PING
                pos = 0
            }
        }
    }
}
