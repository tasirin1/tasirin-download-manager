package com.tasirin.httpdownloadmanager.util

/** Encoder QR mode byte (ECC L/M) yang mandiri — pengganti dependensi zxing di
 *  APK agar ukuran tetap kecil. Algoritma mengikuti ISO/IEC 18004; kebenaran
 *  diverifikasi dengan decode zxing di unit test (testImplementation, tidak
 *  ikut ke APK). Mask pattern 0 selalu dipakai (format info mencatatnya). */
object QrEncoder {

    enum class Ecc(val bits: Int) { L(0b01), M(0b00) }

    private const val MAX_VERSION = 40

    // Total codeword (data + ECC) per versi 1..40 — Tabel dari ISO/IEC 18004.
    private val TOTAL_CODEWORDS = intArrayOf(
        26, 44, 70, 100, 134, 172, 196, 242, 292, 346, 404, 466, 532, 581, 655,
        733, 815, 901, 991, 1085, 1156, 1258, 1364, 1474, 1588, 1706, 1828, 1921,
        2051, 2185, 2323, 2465, 2611, 2761, 2876, 3034, 3196, 3362, 3532, 3706
    )

    // ECC codeword per blok untuk level L.
    private val ECC_PER_BLOCK_L = intArrayOf(
        7, 10, 15, 20, 26, 18, 20, 24, 30, 18, 20, 24, 26, 30, 22, 24, 28, 30,
        28, 28, 28, 28, 30, 30, 26, 28, 30, 30, 30, 30, 30, 30, 30, 30, 30, 30,
        30, 30, 30, 30
    )

    // Jumlah blok RS untuk level L.
    private val BLOCKS_L = intArrayOf(
        1, 1, 1, 1, 1, 2, 2, 2, 2, 4, 4, 4, 4, 4, 6, 6, 6, 6, 7, 8, 8, 9, 9,
        10, 12, 12, 12, 13, 14, 15, 16, 17, 18, 19, 19, 20, 21, 22, 24, 25
    )

    // ECC codeword per blok untuk level M.
    private val ECC_PER_BLOCK_M = intArrayOf(
        10, 16, 26, 18, 24, 16, 18, 22, 22, 26, 30, 22, 22, 24, 24, 28, 28, 26,
        26, 26, 26, 28, 28, 28, 28, 28, 28, 28, 28, 28, 28, 28, 28, 28, 28, 28,
        28, 28, 28, 28
    )

    // Jumlah blok RS untuk level M.
    private val BLOCKS_M = intArrayOf(
        1, 1, 1, 2, 2, 4, 4, 4, 5, 5, 5, 8, 9, 9, 10, 10, 11, 13, 14, 16, 17,
        17, 18, 20, 21, 23, 25, 26, 28, 29, 31, 33, 35, 37, 38, 40, 43, 45, 47, 49
    )

    // Pusat pola alignment per versi (indeks 0 = versi 1).
    private val ALIGNMENT = arrayOf(
        intArrayOf(),
        intArrayOf(6, 18), intArrayOf(6, 22), intArrayOf(6, 26), intArrayOf(6, 30),
        intArrayOf(6, 34), intArrayOf(6, 22, 38), intArrayOf(6, 24, 42),
        intArrayOf(6, 26, 46), intArrayOf(6, 28, 50), intArrayOf(6, 30, 54),
        intArrayOf(6, 32, 58), intArrayOf(6, 34, 62), intArrayOf(6, 26, 46, 66),
        intArrayOf(6, 26, 48, 70), intArrayOf(6, 26, 50, 74), intArrayOf(6, 30, 54, 78),
        intArrayOf(6, 30, 56, 82), intArrayOf(6, 30, 58, 86), intArrayOf(6, 34, 62, 90),
        intArrayOf(6, 28, 50, 72, 94), intArrayOf(6, 26, 50, 74, 98),
        intArrayOf(6, 30, 54, 78, 102), intArrayOf(6, 28, 54, 80, 106),
        intArrayOf(6, 32, 58, 84, 110), intArrayOf(6, 30, 58, 86, 114),
        intArrayOf(6, 34, 62, 90, 118), intArrayOf(6, 26, 50, 74, 98, 122),
        intArrayOf(6, 30, 54, 78, 102, 126), intArrayOf(6, 26, 52, 78, 104, 130),
        intArrayOf(6, 30, 56, 82, 108, 134), intArrayOf(6, 34, 60, 86, 112, 138),
        intArrayOf(6, 30, 58, 86, 114, 142), intArrayOf(6, 34, 62, 90, 118, 146),
        intArrayOf(6, 30, 54, 78, 102, 126, 150), intArrayOf(6, 24, 50, 76, 102, 128, 154),
        intArrayOf(6, 28, 54, 80, 106, 132, 158), intArrayOf(6, 32, 58, 84, 110, 136, 162),
        intArrayOf(6, 26, 54, 82, 110, 138, 166), intArrayOf(6, 30, 58, 86, 114, 142, 170)
    )

    private val FINDER = arrayOf(
        intArrayOf(1, 1, 1, 1, 1, 1, 1),
        intArrayOf(1, 0, 0, 0, 0, 0, 1),
        intArrayOf(1, 0, 1, 1, 1, 0, 1),
        intArrayOf(1, 0, 1, 1, 1, 0, 1),
        intArrayOf(1, 0, 1, 1, 1, 0, 1),
        intArrayOf(1, 0, 0, 0, 0, 0, 1),
        intArrayOf(1, 1, 1, 1, 1, 1, 1)
    )

    private val EXP = IntArray(512)
    private val LOG = IntArray(256)

    init {
        var x = 1
        for (i in 0 until 255) {
            EXP[i] = x
            LOG[x] = i
            x = x shl 1
            if (x and 0x100 != 0) x = x xor 0x11D
        }
        for (i in 255 until 512) EXP[i] = EXP[i - 255]
    }

    /** Matriks QR: true = modul gelap. [get] memakai koordinat (x, y). */
    class Matrix internal constructor(val size: Int) {
        private val modules = IntArray(size * size) { -1 }
        fun get(x: Int, y: Int): Boolean = modules[y * size + x] == 1
        internal fun set(x: Int, y: Int, value: Boolean) {
            modules[y * size + x] = if (value) 1 else 0
        }
        internal fun isEmpty(x: Int, y: Int): Boolean = modules[y * size + x] == -1
    }

    /** Encode teks (UTF-8, mode byte). Mengembalikan null bila melebihi
     *  kapasitas versi 40. */
    fun encode(text: String, ecc: Ecc = Ecc.L): Matrix? {
        val bytes = text.toByteArray(Charsets.UTF_8)
        var version = 0
        for (v in 1..MAX_VERSION) {
            val dataCodewords = dataCodewordsFor(v, ecc)
            val countBits = if (v <= 9) 8 else 16
            val bitsNeeded = 4 + countBits + bytes.size * 8
            if ((bitsNeeded + 7) / 8 <= dataCodewords) {
                version = v
                break
            }
        }
        if (version == 0) return null
        val dataCodewords = dataCodewordsFor(version, ecc)
        val data = buildDataBits(bytes, version, dataCodewords)
        val interleaved = interleave(data, version, ecc)
        return buildMatrix(interleaved, version, ecc)
    }

    private fun dataCodewordsFor(version: Int, ecc: Ecc): Int {
        val (eccPer, blocks) = when (ecc) {
            Ecc.L -> ECC_PER_BLOCK_L[version - 1] to BLOCKS_L[version - 1]
            Ecc.M -> ECC_PER_BLOCK_M[version - 1] to BLOCKS_M[version - 1]
        }
        return TOTAL_CODEWORDS[version - 1] - eccPer * blocks
    }

    private fun buildDataBits(bytes: ByteArray, version: Int, dataCodewords: Int): IntArray {
        val bits = ArrayList<Boolean>(dataCodewords * 8)
        fun appendBits(value: Int, count: Int) {
            for (i in count - 1 downTo 0) bits.add(((value shr i) and 1) == 1)
        }
        appendBits(0b0100, 4) // mode byte
        appendBits(bytes.size, if (version <= 9) 8 else 16)
        for (b in bytes) appendBits(b.toInt() and 0xFF, 8)
        var terminator = 0
        while (terminator < 4 && bits.size < dataCodewords * 8) {
            bits.add(false)
            terminator++
        }
        while (bits.size % 8 != 0) bits.add(false)
        var pad = 0
        while (bits.size < dataCodewords * 8) {
            appendBits(if (pad % 2 == 0) 0xEC else 0x11, 8)
            pad++
        }
        val out = IntArray(dataCodewords)
        for (i in out.indices) {
            var v = 0
            for (j in 0 until 8) v = (v shl 1) or (if (bits[i * 8 + j]) 1 else 0)
            out[i] = v
        }
        return out
    }

    /** Bagi data ke blok RS, hitung ECC tiap blok, lalu interleave data lalu
     *  ECC (aturan 8.6 ISO/IEC 18004). */
    private fun interleave(data: IntArray, version: Int, ecc: Ecc): IntArray {
        val (eccPerBlock, numBlocks) = when (ecc) {
            Ecc.L -> ECC_PER_BLOCK_L[version - 1] to BLOCKS_L[version - 1]
            Ecc.M -> ECC_PER_BLOCK_M[version - 1] to BLOCKS_M[version - 1]
        }
        val numTotal = TOTAL_CODEWORDS[version - 1]
        val numData = data.size
        // Kelompok 2 punya total codeword 1 lebih banyak (aturan Tabel 9).
        val group2 = numTotal % numBlocks
        val group1 = numBlocks - group2
        val dataPer1 = numData / numBlocks
        val dataPer2 = dataPer1 + 1

        val blocks = mutableListOf<Pair<IntArray, IntArray>>()
        var offset = 0
        for (i in 0 until numBlocks) {
            val dataLen = if (i < group1) dataPer1 else dataPer2
            val blockData = data.copyOfRange(offset, offset + dataLen)
            offset += dataLen
            blocks.add(blockData to rsEncode(blockData, eccPerBlock))
        }
        val result = ArrayList<Int>(numTotal)
        val maxData = blocks.maxOf { it.first.size }
        for (i in 0 until maxData) {
            for ((blockData, _) in blocks) {
                if (i < blockData.size) result.add(blockData[i])
            }
        }
        for (i in 0 until eccPerBlock) {
            for ((_, blockEcc) in blocks) result.add(blockEcc[i])
        }
        return result.toIntArray()
    }

    // ---------- Reed-Solomon over GF(256) ----------

    private fun mul(a: Int, b: Int): Int =
        if (a == 0 || b == 0) 0 else EXP[LOG[a] + LOG[b]]

    /** Polinomial generator RS: hasil kali (x - a^i) untuk i = 0..degree-1.
     *  gen[j] = koefisien x^j. */
    private fun rsGenerator(degree: Int): IntArray {
        var gen = intArrayOf(1)
        for (i in 0 until degree) {
            val next = IntArray(gen.size + 1)
            for (j in gen.indices) {
                next[j] = next[j] xor mul(gen[j], EXP[i])
                next[j + 1] = next[j + 1] xor gen[j]
            }
            gen = next
        }
        return gen
    }

    private fun rsEncode(data: IntArray, eccLen: Int): IntArray {
        val gen = rsGenerator(eccLen)
        val res = IntArray(data.size + eccLen)
        System.arraycopy(data, 0, res, 0, data.size)
        for (i in data.indices) {
            val coef = res[i]
            if (coef != 0) {
                // Pembagian sintetis: koefisien x^j dikalikan coef lalu
                // dikurangi di posisi i + (eccLen - j) (suku leading x^k
                // meniadakan res[i] sendiri).
                for (j in 0 until eccLen) {
                    res[i + eccLen - j] = res[i + eccLen - j] xor mul(gen[j], coef)
                }
            }
        }
        return res.copyOfRange(data.size, res.size)
    }

    // ---------- Matriks ----------

    private fun buildMatrix(data: IntArray, version: Int, ecc: Ecc): Matrix {
        val size = version * 4 + 17
        val m = Matrix(size)
        embedFinderAndSeparators(m)
        m.set(8, size - 8, true) // modul gelap (8.9)
        embedAlignment(m, version)
        embedTiming(m)
        embedFormat(m, ecc)
        if (version >= 7) embedVersion(m, version)
        embedData(m, data)
        return m
    }

    private fun embedFinderAndSeparators(m: Matrix) {
        val size = m.size
        for (y in 0 until 7) {
            for (x in 0 until 7) {
                m.set(x, y, FINDER[y][x] == 1)
                m.set(size - 7 + x, y, FINDER[y][x] == 1)
                m.set(x, size - 7 + y, FINDER[y][x] == 1)
            }
        }
        // Separator putih di sekitar finder.
        for (i in 0 until 8) {
            m.set(i, 7, false)
            m.set(size - 8 + i, 7, false)
            m.set(i, size - 8, false)
        }
        for (i in 0 until 7) {
            m.set(7, i, false)
            m.set(size - 8, i, false)
            m.set(7, size - 7 + i, false)
        }
    }

    private fun embedAlignment(m: Matrix, version: Int) {
        if (version < 2) return
        val coords = ALIGNMENT[version - 1]
        for (y in coords) {
            for (x in coords) {
                if (m.isEmpty(x, y)) {
                    for (dy in 0 until 5) {
                        for (dx in 0 until 5) {
                            val dark = dx == 0 || dx == 4 || dy == 0 || dy == 4 ||
                                (dx == 2 && dy == 2)
                            m.set(x - 2 + dx, y - 2 + dy, dark)
                        }
                    }
                }
            }
        }
    }

    private fun embedTiming(m: Matrix) {
        for (i in 8 until m.size - 8) {
            val bit = (i + 1) % 2 == 1
            if (m.isEmpty(i, 6)) m.set(i, 6, bit)
            if (m.isEmpty(6, i)) m.set(6, i, bit)
        }
    }

    private fun embedFormat(m: Matrix, ecc: Ecc) {
        val typeInfo = (ecc.bits shl 3) or 0 // mask pattern 0
        val format = ((typeInfo shl 10) or bch(typeInfo, 0x537)) xor 0x5412
        val size = m.size
        val coords = arrayOf(
            intArrayOf(8, 0), intArrayOf(8, 1), intArrayOf(8, 2), intArrayOf(8, 3),
            intArrayOf(8, 4), intArrayOf(8, 5), intArrayOf(8, 7), intArrayOf(8, 8),
            intArrayOf(7, 8), intArrayOf(5, 8), intArrayOf(4, 8), intArrayOf(3, 8),
            intArrayOf(2, 8), intArrayOf(1, 8), intArrayOf(0, 8)
        )
        for (i in 0 until 15) {
            val bit = ((format shr i) and 1) == 1
            m.set(coords[i][0], coords[i][1], bit)
            if (i < 8) {
                m.set(size - 1 - i, 8, bit)
            } else {
                m.set(8, size - 15 + i, bit)
            }
        }
        m.set(size - 8, 8, true)
    }

    private fun embedVersion(m: Matrix, version: Int) {
        val bits = (version shl 12) or bch(version, 0x1F25)
        // Bit LSB dulu (urutan penulisan BitArray zxing: bit 0 = bit pertama
        // yang ditambahkan = MSB version).
        var bitIndex = 0
        for (i in 0 until 6) {
            for (j in 0 until 3) {
                val bit = ((bits shr bitIndex) and 1) == 1
                bitIndex++
                m.set(i, m.size - 11 + j, bit)
                m.set(m.size - 11 + j, i, bit)
            }
        }
    }

    /** Isi data zigzag dari kanan-bawah, lompati pola fungsi; mask 0. */
    private fun embedData(m: Matrix, data: IntArray) {
        val bits = ArrayList<Boolean>(data.size * 8)
        for (b in data) {
            for (i in 7 downTo 0) bits.add(((b shr i) and 1) == 1)
        }
        var bitIndex = 0
        var direction = -1
        var x = m.size - 1
        var y = m.size - 1
        while (x > 0) {
            if (x == 6) x -= 1
            while (y >= 0 && y < m.size) {
                for (i in 0 until 2) {
                    val xx = x - i
                    if (!m.isEmpty(xx, y)) continue
                    var bit = bitIndex < bits.size && bits[bitIndex]
                    if (bitIndex < bits.size) bitIndex++
                    if (((xx + y) and 1) == 0) bit = !bit // mask 0
                    m.set(xx, y, bit)
                }
                y += direction
            }
            direction = -direction
            y += direction
            x -= 2
        }
    }

    /** BCH remainder (16-bit) — dipakai untuk format info & version info. */
    private fun bch(value: Int, poly: Int): Int {
        var v = value shl (32 - Integer.numberOfLeadingZeros(poly) - 1)
        val polyMsb = 32 - Integer.numberOfLeadingZeros(poly)
        while (32 - Integer.numberOfLeadingZeros(v) >= polyMsb) {
            v = v xor (poly shl (32 - Integer.numberOfLeadingZeros(v) - polyMsb))
        }
        return v
    }
}
