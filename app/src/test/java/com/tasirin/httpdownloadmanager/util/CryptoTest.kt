package com.tasirin.httpdownloadmanager.util

import org.junit.Assert.assertEquals
import org.junit.Test

class CryptoTest {

    @Test
    fun `decrypt penanda plain eksplisit dikupas`() {
        assertEquals("secret", Crypto.decrypt("plain:secret"))
        assertEquals("", Crypto.decrypt("plain:"))
    }

    @Test
    fun `decrypt legacy tanpa prefix tetap plaintext`() {
        assertEquals("legacy-token", Crypto.decrypt("legacy-token"))
    }
}
