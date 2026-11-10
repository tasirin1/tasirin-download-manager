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
            assertTrue(header!!.startsWith("attachment; filename=\"video _baru_ _.mp4\""))
            assertTrue(header.contains("filename*=UTF-8''video%20%22baru%22%20%C3%A9.mp4"))
        }
    }

    @Test
    fun zipRangeStartOf_unitTidakPekaHuruf() {
        // Issue #1417: ekspektasi dibuktikan port-1417.py (port 1:1).
        assertEquals(-1L, zipRangeStartOf(null))
        assertEquals(5000L, zipRangeStartOf("bytes=5000-"))
        assertEquals(5000L, zipRangeStartOf("Bytes=5000-"))
        assertEquals(1048576L, zipRangeStartOf("BYTES=1048576-2048"))
        assertEquals(42L, zipRangeStartOf("bYtEs=42-"))
    }

    @Test
    fun zipRangeStartOf_bentukDariAwal() {
        assertEquals(0L, zipRangeStartOf("bytes=0-"))
        assertEquals(0L, zipRangeStartOf("bytes=-500"))
        assertEquals(0L, zipRangeStartOf("items=500-"))
        assertEquals(0L, zipRangeStartOf("bytes=abc-"))
        assertEquals(0L, zipRangeStartOf(""))
        assertEquals(0L, zipRangeStartOf("bytes="))
        assertEquals(42L, zipRangeStartOf(" bytes=42- "))
        assertEquals(500L, zipRangeStartOf("bytes= 500 -"))
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

    @Test
    fun parseRange_akhirSebelumAwal_null() {
        assertNull(parseRange("bytes=5-3", 1000))
        assertNull(parseRange("bytes=999-100", 1000))
    }

    @Test
    fun parseRange_akhirLewatTotal_dipotong() {
        assertEquals(900L to 999L, parseRange("bytes=900-1500", 1000))
    }

    @Test
    fun parseRange_komponenOverflowLong() {
        // Awal yang meluap Long tak terpenuhi (null -> 416), BUKAN
        // ditafsir sebagai sufiks 5 byte terakhir (issue #933).
        assertNull(parseRange("bytes=99999999999999999999999999-5", 1000))
        // Panjang sufiks yang meluap berarti >= total: seluruh berkas.
        assertEquals(
            0L to 999L,
            parseRange("bytes=-99999999999999999999999999", 1000)
        )
        // Akhir yang meluap tetap dijepit ke ujung berkas.
        assertEquals(
            5L to 999L,
            parseRange("bytes=5-99999999999999999999999999", 1000)
        )
    }
}
