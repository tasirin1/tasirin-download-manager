package com.tasirin.httpdownloadmanager.download

import java.io.BufferedInputStream
import java.io.File
import java.io.FileInputStream
import java.io.InputStream

/** Parser ADTS AAC murni JVM — dipakai untuk audio HLS YouTube yang berupa
 *  ADTS (bukan MPEG-TS). Menghasilkan csd-0 (AudioSpecificConfig) dan frame
 *  AAC mentah tanpa header ADTS agar siap ditulis ke MediaMuxer.
 *
 *  Frame dibaca lazy satu per satu dari sumber (ByteArray atau File) dan
 *  tidak pernah dipegang semua di RAM — muxing video panjang tidak lagi
 *  menahan seluruh file audio (bisa ratusan MB) sekaligus. */
object AdtsAac {

    /** Hasil parse: info format + iterasi frame yang bisa diulang. */
    class Stream(
        val sampleRate: Int,
        val channels: Int,
        val csd0: ByteArray,
        private val frames: Sequence<ByteArray>
    ) {
        /** Jumlah frame AAC (menghitung dengan iterasi penuh sumber). */
        val frameCount: Int get() = frames.count()

        /** true bila tidak ada frame AAC sama sekali. */
        val isEmpty: Boolean get() = frames.none()

        /** Jalankan `action` untuk tiap frame AAC (payload tanpa header ADTS). */
        fun forEachFrame(action: (ByteArray) -> Unit) {
            frames.forEach(action)
        }
    }

    private val SAMPLE_RATES = intArrayOf(
        96000, 88200, 64000, 48000, 44100, 32000, 24000, 22050, 16000, 12000, 11025, 8000, 7350
    )

    const val SAMPLES_PER_FRAME = 1024L

    /** Batas atas ukuran satu frame AAC — frame 96 kHz stereo 1024 sampel
     *  hanya ~4 KB; batas ini cuma pengaman agar file rusak tidak membuang
     *  memori dengan alokasi raksasa. */
    private const val MAX_FRAME_BYTES = 1 shl 16

    /** Ukuran tag ID3v2 di posisi offset (0 bila tidak ada). Video/audio segmen
     *  HLS YouTube sering diawali tag ID3 berisi timestamp transport stream. */
    fun id3TagSize(data: ByteArray, offset: Int): Int {
        if (offset + 10 > data.size) return 0
        if (data[offset] != 0x49.toByte() || data[offset + 1] != 0x44.toByte() ||
            data[offset + 2] != 0x33.toByte()
        ) return 0
        var size = 0
        for (i in 0 until 4) {
            size = (size shl 7) or (data[offset + 6 + i].toInt() and 0x7F)
        }
        var total = 10 + size
        if (data[offset + 5].toInt() and 0x10 != 0) total += 10 // footer
        return total
    }

    /** Lewati tag ID3 (bila ada) di awal data. */
    fun skipId3(data: ByteArray, offset: Int): Int {
        var pos = offset
        while (pos < data.size) {
            val tag = id3TagSize(data, pos)
            if (tag <= 0) break
            pos += tag
        }
        return pos
    }

    /** Hapus semua tag ID3 dari data (biasanya di tiap segmen audio HLS), sisanya
     *  tetap berisi header ADTS utuh agar MediaExtractor dapat membacanya. */
    fun stripId3(data: ByteArray): ByteArray {
        val out = java.io.ByteArrayOutputStream(data.size)
        var pos = 0
        var runStart = 0
        while (pos < data.size) {
            val tag = id3TagSize(data, pos)
            if (tag > 0) {
                out.write(data, runStart, pos - runStart)
                pos += tag
                runStart = pos
            } else {
                pos++
            }
        }
        out.write(data, runStart, data.size - runStart)
        return out.toByteArray()
    }

    /** Parse data ADTS (boleh diawali ID3). null bila tidak ada frame AAC. */
    fun parse(data: ByteArray): Stream? {
        var pos = skipId3(data, 0)
        if (pos + 7 > data.size) return null
        val first = header(data, pos) ?: return null
        if (first.sfIndex >= SAMPLE_RATES.size) return null
        if (first.frameLen < first.headerLen || pos + first.frameLen > data.size) return null
        return Stream(
            SAMPLE_RATES[first.sfIndex],
            first.channels,
            csd0(first.profile, first.sfIndex, first.channels),
            dataFrames(data)
        )
    }

    /** Buka file ADTS (boleh diawali ID3) sebagai stream frame lazy — file
     *  dibaca ulang tiap iterasi, tidak pernah dipegang penuh di RAM. null
     *  bila tidak ada frame AAC (bukan ADTS / terpotong). */
    fun open(file: File): Stream? {
        val input = BufferedInputStream(FileInputStream(file))
        try {
            val first = readFrame(input) ?: return null
            val h = first.header
            if (h.sfIndex >= SAMPLE_RATES.size) return null
            return Stream(
                SAMPLE_RATES[h.sfIndex],
                h.channels,
                csd0(h.profile, h.sfIndex, h.channels),
                fileFrames(file)
            )
        } finally {
            input.close()
        }
    }

    /** Estimasi durasi (mikrodetik) jika semua frame berisi SAMPLES_PER_FRAME
     *  sampel pada sampleRate tertentu. */
    fun durationUs(frameCount: Int, sampleRate: Int): Long {
        if (frameCount <= 0 || sampleRate <= 0) return 0L
        return frameCount * SAMPLES_PER_FRAME * 1_000_000L / sampleRate
    }

    private data class Header(
        val profile: Int,
        val sfIndex: Int,
        val channels: Int,
        val frameLen: Int,
        val headerLen: Int
    )

    private class Frame(val header: Header, val payload: ByteArray)

    /** Iterasi frame dari ByteArray — frame hanya dibentuk saat benar-benar
     *  dipakai (lazy), bukan disalin semua di awal. */
    private fun dataFrames(data: ByteArray): Sequence<ByteArray> = sequence {
        var pos = skipId3(data, 0)
        while (pos + 7 <= data.size) {
            val tag = id3TagSize(data, pos)
            if (tag > 0) {
                pos += tag
                continue
            }
            val h = header(data, pos) ?: break
            if (h.frameLen < h.headerLen || pos + h.frameLen > data.size) break
            yield(data.copyOfRange(pos + h.headerLen, pos + h.frameLen))
            pos += h.frameLen
        }
    }

    /** Iterasi frame dari file — membuka stream baru per iterasi dan membaca
     *  satu frame pada satu waktu. */
    private fun fileFrames(file: File): Sequence<ByteArray> = sequence {
        BufferedInputStream(FileInputStream(file)).use { input ->
            while (true) {
                val frame = readFrame(input) ?: break
                yield(frame.payload)
            }
        }
    }

    /** Baca satu frame ADTS utuh (payload tanpa header) dari stream; lewati
     *  tag ID3 yang mungkin menyela aliran. null saat EOF / data rusak. */
    private fun readFrame(input: InputStream): Frame? {
        val hdr = ByteArray(10)
        val h = nextHeader(input, hdr) ?: return null
        val pre = 10 - h.headerLen
        val payloadLen = h.frameLen - h.headerLen
        if (payloadLen < pre || payloadLen > MAX_FRAME_BYTES) return null
        val payload = ByteArray(payloadLen)
        if (pre > 0) System.arraycopy(hdr, h.headerLen, payload, 0, pre)
        if (!readExact(input, payload, pre, payloadLen - pre)) return null
        return Frame(h, payload)
    }

    /** Baca 10 byte kandidat header berikutnya (header ID3 penuh atau header
     *  ADTS 7/9 byte + awal payload), lewati tag ID3. null saat EOF /
     *  syncword tidak valid. */
    private fun nextHeader(input: InputStream, hdr: ByteArray): Header? {
        while (true) {
            if (!readExact(input, hdr, 0, 10)) return null
            // Tag ID3 (10 byte) bisa menyela aliran ADTS gabungan segmen.
            if (hdr[0] == 0x49.toByte() && hdr[1] == 0x44.toByte() && hdr[2] == 0x33.toByte()) {
                var size = 0
                for (i in 0 until 4) size = (size shl 7) or (hdr[6 + i].toInt() and 0x7F)
                var total = 10 + size
                if (hdr[5].toInt() and 0x10 != 0) total += 10 // footer
                if (total < 10) return null
                if (!skipExact(input, total - 10)) return null
                continue
            }
            val b0 = hdr[0].toInt() and 0xFF
            val b1 = hdr[1].toInt() and 0xFF
            // syncword 0xFFF + layer 00
            if (b0 != 0xFF || (b1 and 0xF6) != 0xF0) return null
            val protectionAbsent = b1 and 0x01
            val b2 = hdr[2].toInt() and 0xFF
            val b3 = hdr[3].toInt() and 0xFF
            val profile = (b2 shr 6) and 0x03
            val sfIndex = (b2 shr 2) and 0x0F
            val channels = ((b2 and 0x01) shl 2) or ((b3 shr 6) and 0x03)
            val frameLen = ((b3 and 0x03) shl 11) or
                ((hdr[4].toInt() and 0xFF) shl 3) or
                ((hdr[5].toInt() and 0xFF) shr 5)
            val headerLen = if (protectionAbsent == 1) 7 else 9
            if (frameLen < headerLen) return null
            return Header(profile, sfIndex, channels, frameLen, headerLen)
        }
    }

    private fun header(data: ByteArray, pos: Int): Header? {
        val b0 = data[pos].toInt() and 0xFF
        val b1 = data[pos + 1].toInt() and 0xFF
        // syncword 0xFFF + layer 00
        if (b0 != 0xFF || (b1 and 0xF6) != 0xF0) return null
        val protectionAbsent = b1 and 0x01
        val b2 = data[pos + 2].toInt() and 0xFF
        val b3 = data[pos + 3].toInt() and 0xFF
        val profile = (b2 shr 6) and 0x03
        val sfIndex = (b2 shr 2) and 0x0F
        val channels = ((b2 and 0x01) shl 2) or ((b3 shr 6) and 0x03)
        val frameLen = ((b3 and 0x03) shl 11) or
            ((data[pos + 4].toInt() and 0xFF) shl 3) or
            ((data[pos + 5].toInt() and 0xFF) shr 5)
        val headerLen = if (protectionAbsent == 1) 7 else 9
        return Header(profile, sfIndex, channels, frameLen, headerLen)
    }

    /** AudioSpecificConfig 2 byte (AAC-LC) untuk MediaFormat csd-0. */
    private fun csd0(profile: Int, sfIndex: Int, channels: Int): ByteArray {
        val aot = profile + 1
        return byteArrayOf(
            (((aot shl 3) and 0xF8) or ((sfIndex shr 1) and 0x07)).toByte(),
            (((sfIndex and 0x01) shl 7) or ((channels and 0x07) shl 3)).toByte()
        )
    }

    private fun readExact(input: InputStream, out: ByteArray, off: Int, len: Int): Boolean {
        var pos = off
        while (pos < off + len) {
            val n = input.read(out, pos, off + len - pos)
            if (n < 0) return false
            pos += n
        }
        return true
    }

    private fun skipExact(input: InputStream, len: Int): Boolean {
        var remaining = len
        while (remaining > 0) {
            val skipped = input.skip(remaining.toLong())
            if (skipped <= 0) {
                if (input.read() < 0) return false
                remaining--
            } else {
                remaining -= skipped.toInt()
            }
        }
        return true
    }
}
