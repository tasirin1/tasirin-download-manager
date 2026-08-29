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
        assertTrue(SocialMediaExtractor.isSocialMediaUrl("https://www.facebook.com/share/v/abc/"))
        assertTrue(SocialMediaExtractor.isSocialMediaUrl("https://x.com/username/status/123"))
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
        // lookaside fbsbx mengandung substring "x.com/" tapi bukan Twitter.
        assertFalse(SocialMediaExtractor.isSocialMediaUrl(
            "https://lookaside.fbsbx.com/lookaside/crawler/media/?media_id=1020806507787076"
        ))
        assertFalse(SocialMediaExtractor.isSocialMediaUrl(
            "https://video.fbcdn.net/v/t43.1/123.mp4"
        ))
    }
}
