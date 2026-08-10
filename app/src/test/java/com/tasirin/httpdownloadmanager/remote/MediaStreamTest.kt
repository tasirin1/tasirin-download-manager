package com.tasirin.httpdownloadmanager.remote

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class MediaStreamTest {

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
        assertNull(parseRange("bytes=0-99", 0))
    }
}
