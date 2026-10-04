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
    fun `baca nol beruntun tak gantung - kembalikan parsial`() {
        // Kontrak InputStream membolehkan read() = 0: helper wajib gagal cepat
        // (pola guard HttpBody), bukan busy-loop selamanya.
        var calls = 0
        val stall = object : java.io.InputStream() {
            override fun read(): Int = -1
            override fun read(b: ByteArray, off: Int, len: Int): Int {
                calls++
                return 0
            }
        }
        assertEquals("", readBounded(stall, 1024))
        assert(calls in 1..64)
    }

    @Test
    fun `nol sesekali lalu data tetap dibaca penuh`() {
        var zeros = 0
        val flaky = object : java.io.InputStream() {
            val data = "hello".toByteArray(Charsets.UTF_8)
            var pos = 0
            override fun read(): Int = -1
            override fun read(b: ByteArray, off: Int, len: Int): Int {
                if (zeros < 3) {
                    zeros++
                    return 0
                }
                if (pos >= data.size) return -1
                b[off] = data[pos++]
                return 1
            }
        }
        assertEquals("hello", readBounded(flaky, 1024))
    }

    @Test
    fun `teks multibyte terpotong di tengah tetap dibaca`() {
        val text = "indonesia☕kopi"
        val bytes = text.toByteArray(Charsets.UTF_8)
        val half = readBounded(ByteArrayInputStream(bytes), bytes.size / 2)
        assert(half.isNotEmpty())
    }
}
