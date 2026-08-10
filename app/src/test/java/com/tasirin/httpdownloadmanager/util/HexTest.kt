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

    @Test
    fun `sha256Hex - nilai dasar dan konsisten`() {
        // Vektor standar NIST: sha256("abc") = ba7816bf...
        assertEquals(
            "ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad",
            sha256Hex("abc")
        )
        assertEquals(64, sha256Hex("tasirin").length)
        assertEquals(sha256Hex("1234"), sha256Hex("1234"))
    }
}
