package com.tasirin.httpdownloadmanager.download

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class RedirectSafetyTest {
    @Test
    fun `redirect target resolves relative location and blocks non-http`() {
        assertEquals(
            "https://example.com/file.bin",
            redirectTarget("https://example.com/a", "/file.bin")
        )
        assertNull(redirectTarget("https://example.com/a", "ftp://example.com/file"))
        assertNull(redirectTarget("https://example.com/a", ""))
        assertNull(redirectTarget("https://example.com/a", null))
    }

    @Test
    fun `same origin ignores default ports but rejects cross origin`() {
        assertTrue(isSameOrigin("https://example.com/a", "https://EXAMPLE.com/b"))
        assertTrue(isSameOrigin("http://example.com/a", "http://example.com:80/b"))
        assertFalse(isSameOrigin("https://example.com/a", "http://example.com/a"))
        assertFalse(isSameOrigin("https://example.com/a", "https://cdn.example.com/a"))
    }
}
