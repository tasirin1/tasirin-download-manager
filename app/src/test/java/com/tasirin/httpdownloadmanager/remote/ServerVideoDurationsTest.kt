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
    fun `prune mengevakuasi seq terkecil tanpa peduli urutan sisip`() {
        // Issue #1388: korban eviksi hanya ditentukan seq di dalam
        // nilai — jar org.json tak menjamin urutan keys(), jadi uji
        // TIDAK PERNAH mengasersi urutan keys(); yang diasersi isi
        // hasil prune. Entri disisipkan dengan seq ACAK (deterministik
        // via Random(42)): yang selamat harus tepat TARGET entri
        // ber-seq terbesar. Dibuktikan port-1388-fix.py skenario A.
        val seqs = (0 until ServerVideoDurations.MAX_ENTRIES + 2).shuffled(kotlin.random.Random(42))
        val cache = JSONObject()
        for (s in seqs) {
            cache.put(
                "token-$s",
                JSONObject().put("d", s.toLong()).put("s", s.toLong())
            )
        }
        val pruned = ServerVideoDurations.prune(cache)
        assertEquals(ServerVideoDurations.TARGET_ENTRIES, pruned.length())
        for (s in 0..501) assertFalse("token-$s harus tereviksi", pruned.has("token-$s"))
        for (s in 502 until ServerVideoDurations.MAX_ENTRIES + 2) {
            assertTrue("token-$s harus selamat", pruned.has("token-$s"))
        }
    }

    @Test
    fun `entri yang disegarkan dengan seq baru lolos prune berikutnya`() {
        // Entri tertua (seq 0) yang baru di-probe ulang membawa seq
        // terbesar lewat putRefreshed, jadi prune membuang seq
        // terkecil berikutnya (1) lebih dulu. Ekspektasi dibuktikan
        // port-1388-fix.py skenario B.
        val cache = JSONObject()
        repeat(ServerVideoDurations.MAX_ENTRIES + 2) {
            cache.put(
                "token-$it",
                JSONObject().put("d", it.toLong()).put("s", it.toLong())
            )
        }
        ServerVideoDurations.putRefreshed(cache, "token-0", 999L, 5000L)
        val pruned = ServerVideoDurations.prune(cache)
        assertEquals(ServerVideoDurations.TARGET_ENTRIES, pruned.length())
        assertTrue(pruned.has("token-0"))
        assertFalse(pruned.has("token-1"))
        assertEquals(999L, ServerVideoDurations.durationOf(pruned, "token-0"))
    }

    @Test
    fun `nilai Long warisan terbaca benar dan tereviksi paling dulu`() {
        // Berkas lama menyimpan Long polos: durationOf membacanya
        // sebagai durasinya sendiri, dan seqOf memberinya urutan
        // terkecil sehingga prune mengevaksinya sebelum nilai objek
        // mana pun — termasuk objek ber-seq 0. Dibuktikan
        // port-1388-fix.py skenario C.
        val legacy = JSONObject().put("lama", 123456L)
        assertEquals(123456L, ServerVideoDurations.durationOf(legacy, "lama"))
        assertEquals(
            Long.MIN_VALUE,
            ServerVideoDurations.seqOf(legacy.opt("lama"))
        )
        val cache = JSONObject().put("warisan", 42L)
        repeat(ServerVideoDurations.MAX_ENTRIES) {
            cache.put(
                "token-$it",
                JSONObject().put("d", it.toLong()).put("s", it.toLong())
            )
        }
        val pruned = ServerVideoDurations.prune(cache)
        assertFalse(pruned.has("warisan"))
        assertFalse(pruned.has("token-0"))
        assertTrue(pruned.has("token-1999"))
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
        // Issue #1389: stempel di masa depan (jam mundur) tidak
        // menahan probe ulang; port Python port-1389 membuktikannya.
        assertFalse(ServerVideoDurations.isProbeFailureThrottled(now + 60_000, now, ttl))
        assertTrue(ServerVideoDurations.isProbeFailureThrottled(now, now, ttl))
    }
}
