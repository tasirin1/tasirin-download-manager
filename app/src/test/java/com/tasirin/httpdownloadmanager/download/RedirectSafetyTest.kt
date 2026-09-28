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
    fun `redirect target blocks loopback and metadata hosts`() {
        assertNull(redirectTarget("https://example.com/a", "http://127.0.0.1/x"))
        assertNull(redirectTarget("https://example.com/a", "http://localhost:8080/x"))
        assertNull(redirectTarget("https://example.com/a", "http://169.254.169.254/latest"))
        assertNull(redirectTarget("https://example.com/a", "http://2130706433/x"))
        // LAN privat tetap diizinkan (unduhan NAS lokal sah)
        assertEquals(
            "http://192.168.1.10/file.bin",
            redirectTarget("https://example.com/a", "http://192.168.1.10/file.bin")
        )
    }

    @Test
    fun `blocked redirect host covers short ipv4 forms`() {
        // Bentuk ringkas inet_aton: 127.1 dan 127.0.1 = 127.0.0.1.
        assertTrue(isBlockedRedirectHost("127.1"))
        assertTrue(isBlockedRedirectHost("127.0.1"))
        assertTrue(isBlockedRedirectHost("0x7f.1"))
        assertTrue(isBlockedRedirectHost("0x7f.0.1"))
        assertNull(redirectTarget("https://example.com/a", "http://127.1/x"))
        assertNull(redirectTarget("https://example.com/a", "http://127.0.1/x"))
        // Bukan loopback: 2/3 bagian biasa tetap lolos.
        assertFalse(isBlockedRedirectHost("126.1"))
        assertFalse(isBlockedRedirectHost("example.com"))
    }

    @Test
    fun `blocked redirect host covers numeric and local variants`() {
        assertTrue(isBlockedRedirectHost("127.0.0.1"))
        assertTrue(isBlockedRedirectHost("localhost"))
        assertTrue(isBlockedRedirectHost("0.0.0.0"))
        assertTrue(isBlockedRedirectHost("169.254.169.254"))
        assertFalse(isBlockedRedirectHost("192.168.1.10"))
        assertFalse(isBlockedRedirectHost("example.com"))
    }

    @Test
    fun `blocked redirect host covers unspecified wildcard variants`() {
        // 0.0.0.0 tersamar desimal/oktal/heks = wildcard, wajib ditolak.
        assertTrue(isBlockedRedirectHost("0"))
        assertTrue(isBlockedRedirectHost("0x0"))
        assertTrue(isBlockedRedirectHost("0x0.0.0.0"))
        assertTrue(isBlockedRedirectHost("00.0.0.0"))
        assertTrue(isBlockedRedirectHost("0.0.0"))
        assertTrue(isBlockedRedirectHost("0.0"))
        assertNull(redirectTarget("https://example.com/a", "http://0/x"))
        assertNull(redirectTarget("https://example.com/a", "http://0x0.0.0.0/x"))
        // IPv6 unspecified panjang & mapped wildcard.
        assertTrue(isBlockedRedirectHost("[0:0:0:0:0:0:0:0]"))
        assertTrue(isBlockedRedirectHost("[::ffff:0.0.0.0]"))
        assertTrue(isBlockedRedirectHost("::ffff:127.0.0.1"))
        // Bukan wildcard: tetap lolos.
        assertFalse(isBlockedRedirectHost("0.0.0.1"))
        assertFalse(isBlockedRedirectHost("10.0.0.1"))
    }

    @Test
    fun `blocked redirect host covers full-form ipv6 loopback`() {
        // Bentuk penuh tak ternormalisasi (0:0:0:0:0:0:0:1 = ::1) wajib
        // ditolak seperti bentuk ringkasnya.
        assertTrue(isBlockedRedirectHost("[0:0:0:0:0:0:0:1]"))
        assertTrue(isBlockedRedirectHost("0:0:0:0:0:0:0:1"))
        assertTrue(isBlockedRedirectHost("0000:0000:0000:0000:0000:0000:0000:0001"))
        assertTrue(isBlockedRedirectHost("[0:0:0:0:0:0:0:0]"))
        assertNull(redirectTarget("https://example.com/a", "http://[0:0:0:0:0:0:0:1]/x"))
        assertNull(redirectTarget("https://example.com/a", "http://[0:0:0:0:0:0:0:0]/x"))
        // Bukan loopback/unspecified: global unicast dan link-local (LAN)
        // tetap lolos seperti kebijakan LAN IPv4.
        assertFalse(isBlockedRedirectHost("[2001:db8::1]"))
        assertFalse(isBlockedRedirectHost("2001:db8::1"))
        assertFalse(isBlockedRedirectHost("[fe80::1]"))
        assertFalse(isBlockedRedirectHost("::ffff:192.168.1.1"))
    }

    @Test
    fun `same origin ignores default ports but rejects cross origin`() {
        assertTrue(isSameOrigin("https://example.com/a", "https://EXAMPLE.com/b"))
        assertTrue(isSameOrigin("http://example.com/a", "http://example.com:80/b"))
        assertFalse(isSameOrigin("https://example.com/a", "http://example.com/a"))
        assertFalse(isSameOrigin("https://example.com/a", "https://cdn.example.com/a"))
    }
}
