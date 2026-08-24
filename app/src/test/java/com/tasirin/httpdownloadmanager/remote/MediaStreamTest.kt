package com.tasirin.httpdownloadmanager.remote

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.assertNull
import org.junit.Test
import java.io.ByteArrayInputStream

class MediaStreamTest {

    @Test
    fun streamMedia_namaNonAsciiPakaiFallbackRfc5987() {
        ByteArrayInputStream(ByteArray(4)).use { input ->
            val response = streamMedia(
                name = "video \"baru\" é.mp4",
                mime = "video/mp4",
                input = input,
                total = 4L,
                rangeHeader = null,
                download = true
            )
            val header = response.getHeader("Content-Disposition")
            assertTrue(header!!.startsWith("attachment; filename=\"video __baru__ _.mp4\""))
            assertTrue(header.contains("filename*=UTF-8''video%20%22baru%22%20%C3%A9.mp4"))
        }
    }

    @Test
    fun parseRange_awalSampaiAkhir() {
        assertEquals(0L to 99L, parseRange("bytes=0-99", 1000))
        assertEquals(500L to 999L, parseRange("bytes=500-", 1000))
    }

    @Test
    fun parseRange_sufiks() {
        assertEquals(900L to 999L, parseRange("bytes=-100", 1000))
    }

    @Test
    fun parseRange_tidakValid_null() {
        assertNull(parseRange(null, 1000))
        assertNull(parseRange("", 1000))
        assertNull(parseRange("bytes=x-y", 1000))
        assertNull(parseRange("bytes=0-99,200-299", 1000))
        assertNull(parseRange("bytes=0-99", 0))
    }
}
