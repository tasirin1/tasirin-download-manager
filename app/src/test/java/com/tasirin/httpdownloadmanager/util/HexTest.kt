package com.tasirin.httpdownloadmanager.util

import org.junit.Assert.assertEquals
import org.junit.Test

class HexTest {

    @Test
    fun `encode kosong dan nilai dasar`() {
        assertEquals("", Hex.encode(ByteArray(0)))
        assertEquals("00", Hex.encode(byteArrayOf(0)))
        assertEquals("ff", Hex.encode(byteArrayOf(0xFF.toByte())))
        assertEquals("0a0b", Hex.encode(byteArrayOf(10, 11)))
    }

    @Test
    fun `encode panjang dan konsisten`() {
        val bytes = "tasirin-httpdm".toByteArray(Charsets.UTF_8)
        val first = Hex.encode(bytes)
        val second = Hex.encode(bytes)
        assertEquals(first, second)
        assertEquals(bytes.size * 2, first.length)
        assertEquals("7461736972696e2d68747470646d", first)
    }
}
