package com.tasirin.httpdownloadmanager.util

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.assertEquals
import org.junit.Test

class SocialMediaExtractorTest {

    @Test
    fun `isSocialMediaUrl - deteksi URL beranda sosial`() {
        assertTrue(SocialMediaExtractor.isSocialMediaUrl("https://www.youtube.com/watch?v=abc"))
        assertTrue(SocialMediaExtractor.isSocialMediaUrl("https://youtu.be/abc"))
        assertTrue(SocialMediaExtractor.isSocialMediaUrl("https://www.tiktok.com/@user/video/123"))
        assertTrue(SocialMediaExtractor.isSocialMediaUrl("https://vm.tiktok.com/abc/"))
        assertTrue(SocialMediaExtractor.isSocialMediaUrl("https://www.instagram.com/p/abc/"))
        assertTrue(SocialMediaExtractor.isSocialMediaUrl("https://www.instagram.com/reel/abc/"))
        assertTrue(SocialMediaExtractor.isSocialMediaUrl("https://x.com/username/status/123"))
        // Facebook tidak lagi didukung — jangan dikenali sebagai media sosial.
        assertFalse(SocialMediaExtractor.isSocialMediaUrl("https://www.facebook.com/share/v/abc/"))
    }

    @Test
    fun `isSocialMediaUrl - CDN media bukan URL sosial`() {
        // CDN media sudah di-extract; jangan salah deteksi ulang.
        assertFalse(SocialMediaExtractor.isSocialMediaUrl(
            "https://scontent-cdn1-1.cdninstagram.com/v/t51.2885-15/123.jpg"
        ))
        assertFalse(SocialMediaExtractor.isSocialMediaUrl(
            "https://v16m.tiktokcdn-us.com/video/tos/123.mp4"
        ))
        // CDN Facebook tidak dikenali (fitur FB dihapus).
        assertFalse(SocialMediaExtractor.isSocialMediaUrl(
            "https://lookaside.fbsbx.com/lookaside/crawler/media/?media_id=1020806507787076"
        ))
        assertFalse(SocialMediaExtractor.isSocialMediaUrl(
            "https://video.fbcdn.net/v/t43.1/123.mp4"
        ))
    }

    @Test
    fun `isSocialMediaUrl - false positive mencegah domain mirip`() {
        // notyoutube.com seharusnya TIDAK terdeteksi sebagai YouTube
        assertFalse(SocialMediaExtractor.isSocialMediaUrl("https://notyoutube.com/video/123"))
        assertFalse(SocialMediaExtractor.isSocialMediaUrl("https://evil-tiktok.com/steal"))
        assertFalse(SocialMediaExtractor.isSocialMediaUrl("https://fakeinstagram.com/p/abc"))
        assertFalse(SocialMediaExtractor.isSocialMediaUrl("https://x-twitter.com/malicious"))
    }

    @Test
    fun `isSocialMediaUrl - path tanpa slashaman bukan URL sosial`() {
        // youtube.com tanpa pathslash (bare domain) tidak terdeteksi
        assertFalse(SocialMediaExtractor.isSocialMediaUrl("https://youtube.com"))
        assertFalse(SocialMediaExtractor.isSocialMediaUrl("https://youtu.be"))
        assertFalse(SocialMediaExtractor.isSocialMediaUrl("https://tiktok.com"))
        assertFalse(SocialMediaExtractor.isSocialMediaUrl("https://instagram.com"))
    }

    @Test
    fun `isSocialMediaUrl - subdomain mobile ikut terdeteksi`() {
        assertTrue(SocialMediaExtractor.isSocialMediaUrl("https://m.youtube.com/watch?v=abc"))
        assertTrue(SocialMediaExtractor.isSocialMediaUrl("https://music.youtube.com/watch?v=abc"))
        assertTrue(SocialMediaExtractor.isSocialMediaUrl("https://m.tiktok.com/@user/video/123"))
        // Subdomain asing tetap ditolak (bukan m./music./www.).
        assertFalse(SocialMediaExtractor.isSocialMediaUrl("https://evil.youtube.com/watch?v=abc"))
    }

    @Test
    fun `isSocialMediaUrl - HTTP juga terdeteksi`() {
        assertTrue(SocialMediaExtractor.isSocialMediaUrl("http://youtube.com/watch?v=abc"))
        assertTrue(SocialMediaExtractor.isSocialMediaUrl("http://tiktok.com/@user/video/123"))
    }

    @Test
    fun `isSocialMediaUrl - xvideos terdeteksi`() {
        assertTrue(SocialMediaExtractor.isSocialMediaUrl("https://www.xvideos.com/video.12345/judul-video"))
        assertTrue(SocialMediaExtractor.isSocialMediaUrl("http://m.xvideos.com/video.123/judul"))
        assertTrue(SocialMediaExtractor.isSocialMediaUrl("https://mobile.xvideos.com/video.123/judul"))
        assertFalse(SocialMediaExtractor.isSocialMediaUrl("https://notxvideos.com/video/123"))
        assertFalse(SocialMediaExtractor.isSocialMediaUrl("https://xvideos.com"))
    }

    @Test
    fun `isSocialMediaUrl - xnxx terdeteksi`() {
        assertTrue(SocialMediaExtractor.isSocialMediaUrl("https://www.xnxx.com/video-abc/judul"))
        assertTrue(SocialMediaExtractor.isSocialMediaUrl("http://m.xnxx.com/video-abc/judul"))
        assertTrue(SocialMediaExtractor.isSocialMediaUrl("https://www.xnxxvideos.me/video-abc/judul"))
        assertFalse(SocialMediaExtractor.isSocialMediaUrl("https://notxnxx.com/video/123"))
        assertFalse(SocialMediaExtractor.isSocialMediaUrl("https://xnxx.com"))
    }

    @Test
    fun `parseXnxxPage - prefix XNXX dan suffix judul`() {
        val html = "<html><head><title>Great video - XNXX.COM</title></head><body>" +
            "<script>html5player.setVideoUrlHigh('https://cdn.xnxx-cdn.com/high.mp4');" +
            "html5player.setVideoUrlLow('https://cdn.xnxx-cdn.com/low.mp4');</script></body></html>"
        val opts = SocialMediaExtractor.parseXnxxPage(html)
        assertEquals(2, opts.size)
        assertEquals("Great video", opts[0].title)
        assertTrue(opts[0].fileName!!.startsWith("XNXX_"))
        assertTrue(opts[0].fileName!!.endsWith("_HD.mp4"))
        assertEquals("HD", opts[0].quality)
        assertEquals("SD", opts[1].quality)
    }

    @Test
    fun `parseXVideosPage - high low hls`() {
        val html = "<html><head><title>Cool video - XVIDEOS.COM</title></head><body>" +
            "<script>html5player.setVideoTitle('Cool video');" +
            "html5player.setVideoUrlHigh('https://cdn77-vid.xvideos-cdn.com/abc-high.mp4');" +
            "html5player.setVideoUrlLow('https://cdn77-vid.xvideos-cdn.com/abc-low.mp4');" +
            "html5player.setVideoHLS('https://cdn77-vid.xvideos-cdn.com/hls/abc.m3u8');</script></body></html>"
        val opts = SocialMediaExtractor.parseXVideosPage(html)
        assertEquals(3, opts.size)
        assertEquals("HD", opts[0].quality)
        assertTrue(opts[0].directUrl.endsWith("-high.mp4"))
        assertEquals("SD", opts[1].quality)
        assertTrue(opts[2].isHls)
        assertTrue(opts[2].directUrl.endsWith(".m3u8"))
    }

    @Test
    fun `parseXVideosPage - fallback og video`() {
        val html = "<html><head>" +
            "<meta property=\"og:title\" content=\"OG Title\"/>" +
            "<meta property=\"og:video\" content=\"https://cdn.xvideos-cdn.com/og.mp4\"/>" +
            "</head><body>watch</body></html>"
        val opts = SocialMediaExtractor.parseXVideosPage(html)
        assertEquals(1, opts.size)
        assertEquals("OG Title", opts[0].title)
        assertTrue(opts[0].directUrl.endsWith("og.mp4"))
    }

    @Test
    fun `parseXVideosPage - kosong bila tak ada video`() {
        assertTrue(SocialMediaExtractor.parseXVideosPage("<html><body>hello</body></html>").isEmpty())
    }

    @Test
    fun `isSocialMediaUrl - URL kosong dan bukan HTTP`() {
        assertFalse(SocialMediaExtractor.isSocialMediaUrl(""))
        assertFalse(SocialMediaExtractor.isSocialMediaUrl("ftp://youtube.com/watch"))
        assertFalse(SocialMediaExtractor.isSocialMediaUrl("file:///etc/passwd"))
    }
}
