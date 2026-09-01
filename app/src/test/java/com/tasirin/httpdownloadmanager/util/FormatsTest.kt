package com.tasirin.httpdownloadmanager.util

import org.junit.Assert.assertEquals
import org.junit.Test

class FormatsTest {

    @Test
    fun `bytes - satuan dasar`() {
        assertEquals("0 B", Formats.bytes(0))
        assertEquals("1023 B", Formats.bytes(1023))
        assertEquals("1.5 KB", Formats.bytes(1536))
        assertEquals("1.0 MB", Formats.bytes(1048576))
        assertEquals("1.00 GB", Formats.bytes(1073741824))
    }

    @Test
    fun `speed - satuan dasar`() {
        assertEquals("512 B/s", Formats.speed(512))
        assertEquals("1.0 KB/s", Formats.speed(1024))
        assertEquals("2.50 MB/s", Formats.speed(2621440))
    }

    @Test
    fun `duration - format sesuai rentang`() {
        assertEquals("0:00", Formats.duration(0))
        assertEquals("0:00", Formats.duration(-100))
        assertEquals("0:05", Formats.duration(5000))
        assertEquals("1:05", Formats.duration(65000))
        assertEquals("10:00", Formats.duration(600000))
        assertEquals("1:01:01", Formats.duration(3661000))
    }

    @Test
    fun `eta - format sesuai rentang`() {
        assertEquals("0s", Formats.eta(0))
        assertEquals("0s", Formats.eta(-5))
        assertEquals("45s", Formats.eta(45))
        assertEquals("3m 5s", Formats.eta(185))
        assertEquals("2h 3m", Formats.eta(7380))
        assertEquals("1h 0m", Formats.eta(3600))
    }
}
