package com.tasirin.httpdownloadmanager.download

/** Parser ADTS AAC murni JVM — dipakai untuk audio HLS YouTube yang berupa
 *  ADTS (bukan MPEG-TS). Menghasilkan csd-0 (AudioSpecificConfig) dan frame
 *  AAC mentah tanpa header ADTS agar siap ditulis ke MediaMuxer. */
object AdtsAac {

    class Stream(
        val sampleRate: Int,
        val channels: Int,
        val csd0: ByteArray,
        val frames: List<ByteArray>
    )

    private val SAMPLE_RATES = intArrayOf(
        96000, 88200, 64000, 48000, 44100, 32000, 24000, 22050, 16000, 12000, 11025, 8000, 7350
    )

    const val SAMPLES_PER_FRAME = 1024L

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
        val sampleRate = SAMPLE_RATES[first.sfIndex]
        val csd = csd0(first.profile, first.sfIndex, first.channels)
        val frames = ArrayList<ByteArray>()
        var p = pos
        var guard = 0
        while (p + 7 <= data.size) {
            val tag = id3TagSize(data, p)
            if (tag > 0) {
                p += tag
                continue
            }
            val h = header(data, p) ?: break
            if (h.frameLen < h.headerLen || p + h.frameLen > data.size) break
            frames.add(data.copyOfRange(p + h.headerLen, p + h.frameLen))
            p += h.frameLen
            if (++guard > 1_000_000) break
        }
        if (frames.isEmpty()) return null
        return Stream(sampleRate, first.channels, csd, frames)
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
}
