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
}
