package com.tasirin.httpdownloadmanager.remote

/** Bukti kelengkapan satu sesi upload chunk (issue #1036): mencatat indeks
 *  chunk yang sungguh diterima server beserta panjangnya, supaya finalisasi
 *  bisa menolak berkas yang potongan tengahnya tak pernah tiba. Sebelumnya
 *  finalisasi hanya dipicu chunk terakhir dan tak ada state sesi sama
 *  sekali — offset == EOF selalu lolos — sehingga berkas tak lengkap bisa
 *  terbit sebagai upload sukses.
 *
 *  Murni (tanpa Android) agar teruji JVM. Semua pemanggilan terjadi di
 *  bawah kunci per-ID server (synchronized(lock.lock) di writeUploadChunk),
 *  jadi kelas ini sengaja tanpa sinkronisasi sendiri. */
class UploadChunkTracker {
    private var expectedChunks = 0
    private var lengths = LongArray(0)
    private var received = BooleanArray(0)

    var receivedCount = 0
        private set
    var totalBytes = 0L
        private set

    /** Mulai sesi baru dengan jumlah chunk [chunks] (0 = kosongkan). */
    fun reset(chunks: Int) {
        expectedChunks = chunks
        lengths = LongArray(chunks)
        received = BooleanArray(chunks)
        receivedCount = 0
        totalBytes = 0L
    }

    /** Samakan sesi dengan jumlah chunk request ini: jumlah yang berbeda
     *  berarti sesi/percobaan lain, catatan lama tak bisa dipercaya. */
    fun ensureSession(chunks: Int) {
        if (expectedChunks != chunks) reset(chunks)
    }

    /** Tanam potongan awalan yang sudah ada di berkas tmp dari sesi
     *  sebelum server restart (tracker per-instance hilang, tmp di disk
     *  bertahan untuk resume — pola retensi #252). Protokol klien resmi
     *  menulis berurutan dengan ukuran chunk tetap, jadi offset chunk
     *  [chunkIdx] adalah chunkIdx x ukuran chunk: awalan utuh di disk
     *  pasti chunk 0..k-1 berukuran penuh. Chunk yang sedang dikirim tak
     *  pernah ditebak (walau byte-nya kebetulan sudah di disk) — ia dicatat
     *  hanya sesudah penulisannya sendiri berhasil. No-op bila tracker
     *  sudah punya catatan sesi ini. */
    fun seedPrefix(chunkIdx: Int, offset: Long, tmpLength: Long) {
        if (receivedCount > 0 || chunkIdx <= 0 || offset <= 0L) return
        if (offset % chunkIdx != 0L) return
        val chunkSize = offset / chunkIdx
        if (chunkSize <= 0L) return
        val present = minOf(chunkIdx.toLong(), tmpLength / chunkSize).toInt()
        for (i in 0 until present) {
            if (!received[i]) {
                received[i] = true
                lengths[i] = chunkSize
                receivedCount++
                totalBytes += chunkSize
            }
        }
    }

    /** Catat satu chunk yang penulisannya berhasil. Kirim-ulang indeks
     *  yang sama tak menggandakan hitungan; panjang yang berubah menyesuaikan
     *  total (selisihnya nanti tertangkap isComplete melawan panjang tmp). */
    fun record(chunkIdx: Int, length: Long) {
        if (chunkIdx < 0 || chunkIdx >= expectedChunks) return
        if (received[chunkIdx]) {
            totalBytes += length - lengths[chunkIdx]
        } else {
            received[chunkIdx] = true
            receivedCount++
            totalBytes += length
        }
        lengths[chunkIdx] = length
    }

    /** Lengkap hanya bila SEMUA indeks tercatat dan jumlah panjangnya
     *  persis panjang berkas tmp yang akan diterbitkan. */
    fun isComplete(tmpLength: Long): Boolean =
        expectedChunks > 0 && receivedCount == expectedChunks && totalBytes == tmpLength

    fun missingCount(): Int = expectedChunks - receivedCount
}
