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
