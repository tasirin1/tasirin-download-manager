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
    fun `readBoundedFlagged - penanda terpotong hanya bila aliran melewati plafon`() {
        // Issue #1241: pas di plafon dan memang habis = TIDAK terpotong.
        val exact = readBoundedFlagged(ByteArrayInputStream("abcde".toByteArray()), 5)
        assertEquals("abcde", exact.text)
        assertEquals(false, exact.truncated)
        // Melewati plafon = terpotong.
        val over = readBoundedFlagged(ByteArrayInputStream("abcdefgh".toByteArray()), 5)
        assertEquals("abcde", over.text)
        assertEquals(true, over.truncated)
        // Di bawah plafon = tidak terpotong.
        val under = readBoundedFlagged(ByteArrayInputStream("abc".toByteArray()), 5)
        assertEquals("abc", under.text)
        assertEquals(false, under.truncated)
    }

    @Test
    fun `decodePercentEncoded - plus literal dan charset dihormati`() {
        // Issue #1242: '+' BUKAN spasi di RFC 5987 (URLDecoder salah).
        assertEquals("a+b c.txt", decodePercentEncoded("a+b%20c.txt", "UTF-8"))
        // Charset label menentukan decode byte %E9: e-acute di Latin-1.
        assertEquals("caf\u00E9.txt", decodePercentEncoded("caf%E9.txt", "ISO-8859-1"))
        // Persen rusak dibiarkan apa adanya, bukan gagal total.
        assertEquals("100%zz", decodePercentEncoded("100%zz", "UTF-8"))
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
