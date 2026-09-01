package com.tasirin.httpdownloadmanager.util

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
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
    fun `isSocialMediaUrl - HTTP juga terdeteksi`() {
        assertTrue(SocialMediaExtractor.isSocialMediaUrl("http://youtube.com/watch?v=abc"))
        assertTrue(SocialMediaExtractor.isSocialMediaUrl("http://tiktok.com/@user/video/123"))
    }

    @Test
    fun `isSocialMediaUrl - URL kosong dan bukan HTTP`() {
        assertFalse(SocialMediaExtractor.isSocialMediaUrl(""))
        assertFalse(SocialMediaExtractor.isSocialMediaUrl("ftp://youtube.com/watch"))
        assertFalse(SocialMediaExtractor.isSocialMediaUrl("file:///etc/passwd"))
    }
}
