package com.tasirin.httpdownloadmanager.remote

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ServerVideoDurationsTest {

    @Test
    fun `prune cache besar - sisakan TARGET_ENTRIES`() {
        val cache = JSONObject()
        repeat(2500) { cache.put("token-$it", it.toLong()) }
        val pruned = ServerVideoDurations.prune(cache)
        assertTrue(pruned.length() <= ServerVideoDurations.TARGET_ENTRIES)
        assertTrue(pruned.length() > 0)
    }

    @Test
    fun `prune cache kecil - tidak berubah`() {
        val cache = JSONObject().put("a", 1L)
        assertEquals(cache, ServerVideoDurations.prune(cache))
    }

    @Test
    fun `prune menegakkan anggaran byte untuk kunci panjang`() {
        // Issue #940: 2000 entri berkunci 300 char terserialisasi
        // ~620KB — melewati plafon muat 512KB sehingga seluruh cache
        // dibuang di start dingin. Prune byte menyisakan himpunan
        // yang JSON-nya muat di bawah plafon.
        val cache = JSONObject()
        repeat(2000) { cache.put("k".repeat(300) + it, 123456L) }
        val pruned = ServerVideoDurations.prune(cache)
        assertTrue(pruned.length() < 2000)
        assertTrue(pruned.length() > 0)
        assertTrue(pruned.toString().length <= 524_288)
    }

    @Test
    fun `keyFor memasang cap ubah pada token`() {
        // #417: cap berubah -> kunci berubah -> durasi lama tak terwarisi.
        // #441: ukuran ikut dikunci — cap sama tetapi ukuran beda
        // (penggantian penjaga mtime) tetap menghasilkan kunci beda.
        assertEquals("f:abc@123:1000", ServerVideoDurations.keyFor("f:abc", 123L, 1000L))
        assertTrue(
            ServerVideoDurations.keyFor("f:abc", 123L, 1000L) !=
                ServerVideoDurations.keyFor("f:abc", 456L, 1000L)
        )
        assertTrue(
            ServerVideoDurations.keyFor("f:abc", 123L, 1000L) !=
                ServerVideoDurations.keyFor("f:abc", 123L, 2000L)
        )
        // Cap tak tersedia jatuh ke token polos seperti perilaku lama.
        assertEquals("f:abc", ServerVideoDurations.keyFor("f:abc", 0L, 1000L))
        assertEquals("f:abc", ServerVideoDurations.keyFor("f:abc", -5L, 1000L))
    }

    @Test
    fun `probe failure dalam TTL - throttled`() {
        val ttl = ServerVideoDurations.PROBE_FAILURE_TTL_MS
        val now = System.currentTimeMillis()
        assertTrue(ServerVideoDurations.isProbeFailureThrottled(now - 1000, now, ttl))
        assertFalse(ServerVideoDurations.isProbeFailureThrottled(now - ttl - 1, now, ttl))
        assertFalse(ServerVideoDurations.isProbeFailureThrottled(null, now, ttl))
    }
}
