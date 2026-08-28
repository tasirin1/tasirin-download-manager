package com.tasirin.httpdownloadmanager.download

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class AdtsAacTest {

    // Satu frame ADTS AAC nyata dari segment audio HLS YouTube (22050 Hz stereo,
    // AAC-LC, frame length 285 byte).
    private val frame = byteArrayOf(
        0xff.toByte(), 0xf1.toByte(), 0x5c.toByte(), 0x80.toByte(), 0x23.toByte(), 0xbf.toByte(),
        0xfc.toByte(), 0x21.toByte(), 0x30.toByte(), 0x05.toByte(), 0x00.toByte(), 0xa0.toByte(),
        0x1b.toByte(), 0x77.toByte(), 0xc9.toByte(), 0x05.toByte(), 0x74.toByte(), 0x20.toByte(),
        0x00.toByte(), 0x00.toByte(), 0x00.toByte(), 0x01.toByte(), 0xc1.toByte(), 0x80.toByte(),
        0x03.toByte(), 0x00.toByte(), 0x37.toByte(), 0xf9.toByte()
    )

    @Test
    fun `parse satu frame ADTS - sample rate dan channel benar`() {
        val stream = AdtsAac.parse(frame)!!
        assertEquals(22050, stream.sampleRate)
        assertEquals(2, stream.channels)
        assertEquals(1, stream.frames.size)
        // AAC-LC (profile 1) + sfIndex 7 + 2ch
        assertEquals(0x13.toByte(), stream.csd0[0])
        assertEquals(0x90.toByte(), stream.csd0[1])
    }

    @Test
    fun `id3 tag di awal dilewati`() {
        // ID3v2 header (10 byte) + ukuran 0 + frame ADTS
        val id3 = byteArrayOf(
            0x49, 0x44, 0x33, 0x04, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00
        )
        val data = id3 + frame
        assertEquals(10, AdtsAac.id3TagSize(data, 0))
        val stream = AdtsAac.parse(data)!!
        assertEquals(22050, stream.sampleRate)
        assertEquals(1, stream.frames.size)
    }

    @Test
    fun `stripId3 menghilangkan tag ID3 tapi menyisakan ADTS utuh`() {
        val id3 = byteArrayOf(
            0x49, 0x44, 0x33, 0x04, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00
        )
        val data = id3 + frame + id3 + frame
        val clean = AdtsAac.stripId3(data)
        assertEquals(0, AdtsAac.id3TagSize(clean, 0))
        val stream = AdtsAac.parse(clean)!!
        assertEquals(2, stream.frames.size)
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
}
