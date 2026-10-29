package com.tasirin.httpdownloadmanager.download

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AdtsAacTest {

    /** Bangun satu frame ADTS lengkap (header 7 byte + payload). */
    private fun adtsFrame(profile: Int, sfIndex: Int, channelConfig: Int, payloadLen: Int): ByteArray {
        val frameLen = 7 + payloadLen
        val b = ByteArray(frameLen)
        b[0] = 0xff.toByte()
        b[1] = 0xf1.toByte() // sync 0xFFF + MPEG-4 + layer 00 + protection absent
        b[2] = ((profile shl 6) or (sfIndex shl 2) or (channelConfig shr 2)).toByte()
        b[3] = (((channelConfig and 0x03) shl 6) or (frameLen shr 11)).toByte()
        b[4] = ((frameLen shr 3) and 0xff).toByte()
        b[5] = ((frameLen and 0x07) shl 5).toByte()
        b[6] = 0
        for (i in 7 until frameLen) b[i] = (0x11 + (i and 0x3f)).toByte()
        return b
    }

    private val id3 = byteArrayOf(
        0x49, 0x44, 0x33, 0x04, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00
    )

    @Test
    fun `parse satu frame ADTS - sample rate dan channel benar`() {
        val stream = AdtsAac.parse(adtsFrame(1, 7, 2, 100))!!
        assertEquals(22050, stream.sampleRate)
        assertEquals(2, stream.channels)
        assertEquals(1, stream.frameCount)
        // AAC-LC (profile 1) + sfIndex 7 + 2ch
        assertEquals(0x13.toByte(), stream.csd0[0])
        assertEquals(0x90.toByte(), stream.csd0[1])
    }

    @Test
    fun `id3 tag di awal dilewati`() {
        val data = id3 + adtsFrame(1, 7, 2, 100)
        assertEquals(10, AdtsAac.id3TagSize(data, 0))
        val stream = AdtsAac.parse(data)!!
        assertEquals(22050, stream.sampleRate)
        assertEquals(1, stream.frameCount)
    }

    @Test
    fun `stripId3 menghilangkan tag ID3 tapi menyisakan ADTS utuh`() {
        val data = id3 + adtsFrame(1, 7, 2, 100) + id3 + adtsFrame(1, 7, 2, 100)
        val clean = AdtsAac.stripId3(data)
        assertEquals(0, AdtsAac.id3TagSize(clean, 0))
        val stream = AdtsAac.parse(clean)!!
        assertEquals(2, stream.frameCount)
    }

    @Test
    fun `stripId3Stream setara stripId3 dan menghitung byte tertulis`() {
        val data = id3 + adtsFrame(1, 7, 2, 100) + id3 + adtsFrame(1, 7, 2, 50)
        val expected = AdtsAac.stripId3(data)
        val out = java.io.ByteArrayOutputStream()
        val written = AdtsAac.stripId3Stream(java.io.ByteArrayInputStream(data), out)
        assertEquals(expected.size.toLong(), written)
        assertEquals(expected.toList(), out.toByteArray().toList())
        // Data tanpa tag dan data kosong lolos apa adanya.
        val plain = adtsFrame(1, 7, 2, 10)
        val out2 = java.io.ByteArrayOutputStream()
        assertEquals(plain.size.toLong(), AdtsAac.stripId3Stream(java.io.ByteArrayInputStream(plain), out2))
        assertEquals(plain.toList(), out2.toByteArray().toList())
        assertEquals(0L, AdtsAac.stripId3Stream(
            java.io.ByteArrayInputStream(ByteArray(0)), java.io.ByteArrayOutputStream()))
    }

    @Test
    fun `data bukan ADTS - null`() {
        assertNull(AdtsAac.parse(ByteArray(4)))
        assertNull(AdtsAac.parse(byteArrayOf(0x01, 0x02, 0x03, 0x04, 0x05, 0x06, 0x07)))
    }

    @Test
    fun `durasi - frame 22050 Hz`() {
        assertEquals(1024L * 1_000_000L / 22050L, AdtsAac.durationUs(1, 22050))
        assertEquals(0L, AdtsAac.durationUs(0, 22050))
    }

    @Test
    fun `forEachFrame menerjemahkan payload tanpa header ADTS`() {
        val stream = AdtsAac.parse(id3 + adtsFrame(1, 7, 2, 100) + adtsFrame(1, 7, 2, 100))!!
        assertFalse(stream.isEmpty)
        val sizes = ArrayList<Int>()
        stream.forEachFrame { sizes.add(it.size) }
        assertEquals(listOf(100, 100), sizes)
    }

    @Test
    fun `open membaca frame dari file dengan id3 dan id3 menyela`() {
        val data = id3 + adtsFrame(1, 7, 2, 100) + id3 + adtsFrame(1, 7, 2, 100) + adtsFrame(1, 7, 2, 100)
        val tmp = File.createTempFile("adts", ".aac")
        try {
            tmp.writeBytes(data)
            val stream = AdtsAac.open(tmp)!!
            assertEquals(22050, stream.sampleRate)
            assertEquals(2, stream.channels)
            assertEquals(3, stream.frameCount)
            val sizes = ArrayList<Int>()
            stream.forEachFrame { sizes.add(it.size) }
            assertEquals(listOf(100, 100, 100), sizes)
        } finally {
            tmp.delete()
        }
    }

    @Test
    fun `stripId3Stream setara stripId3 pada stream menetes dan tag besar`() {
        // Tag besar (300 byte badan) + tag di tengah + potongan baca kecil
        // memaksa tag melewati banyak tepi potongan — jalur cepat per-run
        // (issue #395) wajib menghasilkan byte yang sama persis dengan
        // stripId3 versi array.
        val bigTag = ByteArray(310)
        System.arraycopy(id3, 0, bigTag, 0, id3.size)
        // Ukuran syncsafe 300 pada header tag besar.
        bigTag[6] = 0; bigTag[7] = 0; bigTag[8] = 2; bigTag[9] = 44
        for (i in 10 until bigTag.size) bigTag[i] = 0x55
        val data = bigTag + adtsFrame(1, 7, 2, 100) + id3 + adtsFrame(1, 7, 2, 50) + id3
        val expected = AdtsAac.stripId3(data)
        for (drip in intArrayOf(1, 3, 7, 64)) {
            val trickle = object : java.io.InputStream() {
                private var pos = 0
                override fun read(): Int =
                    if (pos >= data.size) -1 else data[pos++].toInt() and 0xFF
                override fun read(b: ByteArray, off: Int, len: Int): Int {
                    if (pos >= data.size) return -1
                    val n = minOf(drip, len, data.size - pos)
                    System.arraycopy(data, pos, b, off, n)
                    pos += n
                    return n
                }
            }
            val out = java.io.ByteArrayOutputStream()
            val written = AdtsAac.stripId3Stream(trickle, out)
            assertEquals("drip=$drip", expected.size.toLong(), written)
            assertEquals("drip=$drip", expected.toList(), out.toByteArray().toList())
        }
    }

    @Test
    fun `stripId3Stream stream selalu nol - melempar bukan loop selamanya`() {
        // Kontrak InputStream membolehkan read() = 0; refill tanpa guard
        // berputar selamanya pada stream seperti ini (issue #389).
        val zeroStream = object : java.io.InputStream() {
            override fun read(): Int = 0
            override fun read(b: ByteArray, off: Int, len: Int): Int = 0
        }
        try {
            AdtsAac.stripId3Stream(zeroStream, java.io.ByteArrayOutputStream())
            org.junit.Assert.fail("harus melempar IOException zero-read")
        } catch (e: java.io.IOException) {
            assertTrue(e.message!!.contains("zero reads"))
        }
    }

    @Test
    fun `open setara parse untuk frame terakhir mungil di bawah 10 byte`() {
        // Frame terakhir 9 byte (header 7 + payload 2): jalur ByteArray
        // menerimanya; jalur File dulu membuangnya karena kandidat
        // header dibaca tepat 10 byte (issue #602).
        val data = adtsFrame(1, 7, 2, 20) + adtsFrame(1, 7, 2, 2)
        val viaArray = ArrayList<Int>()
        AdtsAac.parse(data)!!.forEachFrame { viaArray.add(it.size) }
        assertEquals(listOf(20, 2), viaArray)
        val tmp = File.createTempFile("adts", ".aac")
        try {
            tmp.writeBytes(data)
            val stream = AdtsAac.open(tmp)!!
            assertEquals(2, stream.frameCount)
            val viaFile = ArrayList<Int>()
            stream.forEachFrame { viaFile.add(it.size) }
            assertEquals(listOf(20, 2), viaFile)
        } finally {
            tmp.delete()
        }
    }

    @Test
    fun `open setara parse untuk frame mungil di tengah aliran`() {
        // Frame mungil (payload 1 byte) di TENGAH berkas: kandidat 10
        // byte memuat byte frame berikutnya; dulu readFrame menyerah
        // dan seluruh audio sesudahnya hilang (issue #619). Pushback
        // membuat jalur File setara jalur ByteArray.
        val data = adtsFrame(1, 7, 2, 20) + adtsFrame(1, 7, 2, 1) + adtsFrame(1, 7, 2, 20)
        val viaArray = ArrayList<Int>()
        AdtsAac.parse(data)!!.forEachFrame { viaArray.add(it.size) }
        assertEquals(listOf(20, 1, 20), viaArray)
        val tmp = File.createTempFile("adts", ".aac")
        try {
            tmp.writeBytes(data)
            val viaFile = ArrayList<Int>()
            AdtsAac.open(tmp)!!.forEachFrame { viaFile.add(it.size) }
            assertEquals(listOf(20, 1, 20), viaFile)
        } finally {
            tmp.delete()
        }
    }

    @Test
    fun `open setara parse untuk dua frame mungil berurutan`() {
        // Pushback harus rantai: sisa frame pertama adalah kandidat
        // frame kedua yang juga mungil.
        val data = adtsFrame(1, 7, 2, 1) + adtsFrame(1, 7, 2, 2) + adtsFrame(1, 7, 2, 20)
        val tmp = File.createTempFile("adts", ".aac")
        try {
            tmp.writeBytes(data)
            val viaFile = ArrayList<Int>()
            AdtsAac.open(tmp)!!.forEachFrame { viaFile.add(it.size) }
            assertEquals(listOf(1, 2, 20), viaFile)
        } finally {
            tmp.delete()
        }
    }

    @Test
    fun `open menerima berkas satu frame mungil 9 byte`() {
        val tmp = File.createTempFile("adts", ".aac")
        try {
            tmp.writeBytes(adtsFrame(1, 7, 2, 2))
            val stream = AdtsAac.open(tmp)!!
            val sizes = ArrayList<Int>()
            stream.forEachFrame { sizes.add(it.size) }
            assertEquals(listOf(2), sizes)
        } finally {
            tmp.delete()
        }
    }

    @Test
    fun `open file non ADTS - null`() {
        val tmp = File.createTempFile("adts", ".aac")
        try {
            tmp.writeBytes(byteArrayOf(0x01, 0x02, 0x03, 0x04, 0x05, 0x06, 0x07))
            assertNull(AdtsAac.open(tmp))
        } finally {
            tmp.delete()
        }
    }
}
