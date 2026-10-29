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
    fun `bytes - tepat di bawah ambang naik satuan, dan ada TB`() {
        // Issue #579: 1048575 byte dulu tampil "1024.0 KB".
        assertEquals("1.0 MB", Formats.bytes(1048575))
        assertEquals("1.00 GB", Formats.bytes(1073741823))
        assertEquals("5.00 TB", Formats.bytes(5497558138880L))
    }

    @Test
    fun `bytes dan speed - jenjang teratas tak mencetak 1024_00`() {
        // Issue #621: titik temuan audit — jenjang terminal dulu
        // membulat ke "1024.00 TB"/"1024.00 GB/s"; kini naik ke
        // PB / TB/s, dan pita tepat di bawah 1024 pada jenjang
        // teratas ditahan di 1023.99.
        assertEquals("1.00 PB", Formats.bytes(1125894409284608L))
        assertEquals("1023.99 PB", Formats.bytes(1152915875107325113L))
        assertEquals("1.00 TB/s", Formats.speed(1099506259067L))
        assertEquals("1023.99 TB/s", Formats.speed(1125894409285484L))
    }

    @Test
    fun `speed - tepat di bawah ambang naik satuan`() {
        assertEquals("1.0 MB/s", Formats.speed(1048575))
        assertEquals("1.00 GB/s", Formats.speed(1073741824))
    }

    @Test
    fun `speed - satuan dasar`() {
        assertEquals("512 B/s", Formats.speed(512))
        assertEquals("1.0 KB/s", Formats.speed(1024))
        assertEquals("2.5 MB/s", Formats.speed(2621440))
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
