package com.tasirin.httpdownloadmanager.util

import java.io.ByteArrayInputStream
import org.junit.Assert.assertEquals
import org.junit.Test

class StreamsTest {

    @Test
    fun `membaca lebih pendek dari batas`() {
        val input = ByteArrayInputStream("hello".toByteArray(Charsets.UTF_8))
        assertEquals("hello", readBounded(input, 1024))
    }

    @Test
    fun `membatasi byte yang melebihi batas`() {
        val input = ByteArrayInputStream("abcdefghij".toByteArray(Charsets.UTF_8))
        assertEquals("abcde", readBounded(input, 5))
    }

    @Test
    fun `stream kosong`() {
        assertEquals("", readBounded(ByteArrayInputStream(ByteArray(0)), 100))
    }

    @Test
    fun `teks multibyte terpotong di tengah tetap dibaca`() {
        val text = "indonesia☕kopi"
        val bytes = text.toByteArray(Charsets.UTF_8)
        val half = readBounded(ByteArrayInputStream(bytes), bytes.size / 2)
        assert(half.isNotEmpty())
    }
}
