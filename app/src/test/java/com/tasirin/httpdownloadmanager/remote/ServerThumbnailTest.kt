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
    fun `cap segar berarti masih bertumbuh`() {
        val now = 1_000_000L
        // #418: mtime dalam jendela 20 dtk (termasuk sedikit di masa
        // depan karena jam tak sinkron) = bertumbuh; di luar = diam.
        // #440: masa depan hanya ditoleransi sampai batas skew 60 dtk —
        // mtime jauh di masa depan adalah cap salah, bukan pertumbuhan.
        assertTrue(isThumbStampGrowing("${now - 5_000}:100", now))
        assertTrue(isThumbStampGrowing("${now + 5_000}:100", now))
        assertTrue(isThumbStampGrowing("${now + 30_000}:100", now))
        assertFalse(isThumbStampGrowing("${now + 120_000}:100", now))
        assertFalse(isThumbStampGrowing("${now + 86_400_000}:100", now))
        assertFalse(isThumbStampGrowing("${now - 25_000}:100", now))
        assertFalse(isThumbStampGrowing("", now))
        assertFalse(isThumbStampGrowing("bukan-angka:100", now))
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
