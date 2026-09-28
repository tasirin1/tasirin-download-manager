package com.tasirin.httpdownloadmanager.remote

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class HttpBodyTest {
    @Test
    fun `chunked body detected case-insensitively`() {
        assertTrue(isChunkedBody(mapOf("transfer-encoding" to "chunked")))
        assertTrue(isChunkedBody(mapOf("Transfer-Encoding" to "Chunked")))
        assertTrue(isChunkedBody(mapOf("transfer-encoding" to "gzip, chunked")))
        assertFalse(isChunkedBody(mapOf("transfer-encoding" to "identity")))
        assertFalse(isChunkedBody(mapOf("content-length" to "100")))
        assertFalse(isChunkedBody(emptyMap()))
    }

    @Test
    fun `unsafe multipart ditolak sebelum parseBody`() {
        // Negatif = invalid; nol + chunked = tmp tanpa batas sampai EOF.
        assertTrue(isUnsafeMultipartLength(-5, emptyMap()))
        assertTrue(isUnsafeMultipartLength(0, mapOf("transfer-encoding" to "chunked")))
        // Nol non-chunked (form kosong) dan panjang valid tetap diproses.
        assertFalse(isUnsafeMultipartLength(0, emptyMap()))
        assertFalse(isUnsafeMultipartLength(0, mapOf("content-length" to "0")))
        assertFalse(isUnsafeMultipartLength(100, emptyMap()))
    }
}
