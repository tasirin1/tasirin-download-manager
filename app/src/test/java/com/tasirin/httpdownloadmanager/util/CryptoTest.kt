package com.tasirin.httpdownloadmanager.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class CryptoTest {

    @Test
    fun `decrypt penanda plain eksplisit dikupas`() {
        assertEquals("secret", Crypto.decrypt("plain:secret"))
        assertEquals("", Crypto.decrypt("plain:"))
    }

    @Test
    fun `isWeakStorage menandai plaintext dan legacy`() {
        assertTrue(Crypto.isWeakStorage("plain:secret"))
        assertTrue(Crypto.isWeakStorage("legacy-token"))
        assertTrue(!Crypto.isWeakStorage("v1:abc:def"))
        assertTrue(!Crypto.isWeakStorage(""))
        assertTrue(!Crypto.isWeakStorage(null))
    }

    @Test
    fun `decrypt legacy tanpa prefix tetap plaintext`() {
        assertEquals("legacy-token", Crypto.decrypt("legacy-token"))
    }
}
