package com.tasirin.httpdownloadmanager.remote

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Issue #1036: finalisasi upload wajib menolak berkas yang chunk-nya
 *  belum terbukti lengkap. Angka harapan dibuktikan dengan port Python 1:1
 *  atas logika tracker sebelum asersi ditulis. */
class UploadChunkTrackerTest {
    private val mb = 1024L * 1024L

    @Test
    fun `urutan lengkap dinyatakan lengkap`() {
        val t = UploadChunkTracker()
        t.reset(3)
        t.record(0, 2 * mb)
        t.record(1, 2 * mb)
        t.record(2, 1 * mb)
        assertTrue(t.isComplete(5 * mb))
        assertEquals(0, t.missingCount())
    }

    @Test
    fun `chunk tengah hilang ditolak walau chunk terakhir tiba`() {
        // Skenario issue: chunk 0 lalu langsung chunk terakhir menulis di
        // EOF (offset == panjang tmp) — dulu finalisasi menerbitkannya.
        val t = UploadChunkTracker()
        t.reset(3)
        t.record(0, 2 * mb)
        t.record(2, 1 * mb)
        assertFalse(t.isComplete(3 * mb))
        assertEquals(1, t.missingCount())
    }

    @Test
    fun `kirim ulang chunk sama tidak menggandakan total`() {
        val t = UploadChunkTracker()
        t.reset(2)
        t.record(0, 100)
        t.record(0, 100)
        assertEquals(1, t.receivedCount)
        assertEquals(100L, t.totalBytes)
    }

    @Test
    fun `kirim ulang dengan panjang berubah menyesuaikan total`() {
        val t = UploadChunkTracker()
        t.reset(2)
        t.record(0, 100)
        t.record(0, 150)
        assertEquals(150L, t.totalBytes)
        assertFalse(t.isComplete(100))
    }

    @Test
    fun `jumlah chunk berubah mereset sesi`() {
        val t = UploadChunkTracker()
        t.reset(3)
        t.record(0, 10)
        t.ensureSession(5)
        assertEquals(0, t.receivedCount)
        assertEquals(5, t.missingCount())
    }

    @Test
    fun `seed awalan sesudah restart melengkapi resume`() {
        // Tracker baru (server restart), tmp berisi chunk 0-1 penuh;
        // chunk 2 tiba dengan offset 4 MB (2 x 2 MB).
        val t = UploadChunkTracker()
        t.reset(4)
        t.seedPrefix(2, 4 * mb, 4 * mb)
        assertEquals(2, t.receivedCount)
        t.record(2, 2 * mb)
        t.record(3, 1 * mb)
        assertTrue(t.isComplete(7 * mb))
    }

    @Test
    fun `seed awalan parsial hanya menanam chunk utuh`() {
        // tmp 3 MB: chunk 0 utuh (2 MB), chunk 1 baru separuh.
        val t = UploadChunkTracker()
        t.reset(4)
        t.seedPrefix(2, 4 * mb, 3 * mb)
        assertEquals(1, t.receivedCount)
        assertEquals(2 * mb, t.totalBytes)
    }

    @Test
    fun `seed tidak menebak chunk yang sedang dikirim`() {
        // tmp 6 MB (chunk 2 pun sudah di disk dari respons yang hilang):
        // seed tetap berhenti di chunk 0-1; chunk 2 dicatat penulisannya.
        val t = UploadChunkTracker()
        t.reset(4)
        t.seedPrefix(2, 4 * mb, 6 * mb)
        assertEquals(2, t.receivedCount)
    }

    @Test
    fun `satu chunk langsung lengkap`() {
        val t = UploadChunkTracker()
        t.reset(1)
        t.record(0, 123)
        assertTrue(t.isComplete(123))
    }
}
