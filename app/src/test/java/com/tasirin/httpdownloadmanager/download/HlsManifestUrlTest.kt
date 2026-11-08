package com.tasirin.httpdownloadmanager.download

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Uji isHlsManifestUrl (issue #1184): pengecualian manifes HLS dinilai
 *  dari host/path ter-parse, bukan substring URL utuh, sehingga URL
 *  berkas biasa yang query-nya memuat string pemicu tak salah masuk
 *  jalur HLS. Nilai harapan dibuktikan dengan port Python 1:1. */
class HlsManifestUrlTest {

    @Test
    fun `host manifest googlevideo dikenali`() {
        assertTrue(isHlsManifestUrl("https://manifest.googlevideo.com/api/manifest/hls_variant/x"))
    }

    @Test
    fun `akhiran m3u8 pada path dikenali`() {
        assertTrue(isHlsManifestUrl("https://example.com/video.m3u8"))
        assertTrue(isHlsManifestUrl("https://example.com/video.m3u8?token=abc"))
        assertTrue(isHlsManifestUrl("https://example.com/MOVIE.M3U8"))
    }

    @Test
    fun `pola api manifest hls pada path dikenali`() {
        assertTrue(isHlsManifestUrl("https://cdn.example.com/api/manifest/hls/playlist"))
    }

    @Test
    fun `string pemicu di query bukan manifes`() {
        assertFalse(isHlsManifestUrl("https://files.example.com/dl?src=manifest.googlevideo.com/x"))
        assertFalse(isHlsManifestUrl("https://example.com/watch?file=clip.m3u8"))
        assertFalse(isHlsManifestUrl("https://example.com/dl?next=/api/manifest/hls"))
    }

    @Test
    fun `host mirip dan berkas biasa bukan manifes`() {
        assertFalse(isHlsManifestUrl("https://vx.manifest.googlevideo.com.evil.com/x"))
        assertFalse(isHlsManifestUrl("https://example.com/movie.mp4"))
    }
}
