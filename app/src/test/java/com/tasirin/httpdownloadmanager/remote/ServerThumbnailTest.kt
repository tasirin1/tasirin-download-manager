package com.tasirin.httpdownloadmanager.remote

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ServerThumbnailTest {

    @Test
    fun `kegagalan thumbnail masih dalam TTL - tidak dicoba ulang`() {
        val ttl = 30L * 60 * 1000
        val now = System.currentTimeMillis()
        assertTrue(isThumbFailureFresh(now - 1000, now, ttl))
        assertTrue(isThumbFailureFresh(now - ttl + 1, now, ttl))
    }

    @Test
    fun `kegagalan thumbnail kedaluwarsa - boleh dicoba ulang`() {
        val ttl = 30L * 60 * 1000
        val now = System.currentTimeMillis()
        assertFalse(isThumbFailureFresh(now - ttl, now, ttl))
        assertFalse(isThumbFailureFresh(now - ttl - 5000, now, ttl))
        assertFalse(isThumbFailureFresh(0, now, ttl))
    }
}
