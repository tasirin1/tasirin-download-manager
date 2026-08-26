package com.tasirin.httpdownloadmanager.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class MimeTypesTest {

    @Test
    fun `forFile - ekstensi dikenal`() {
        assertEquals("application/vnd.android.package-archive", MimeTypes.forFile("app.apk"))
        assertEquals("application/pdf", MimeTypes.forFile("dokumen.pdf"))
        assertEquals("video/mp4", MimeTypes.forFile("video.mp4"))
        assertEquals("video/x-matroska", MimeTypes.forFile("film.mkv"))
        assertEquals("image/jpeg", MimeTypes.forFile("foto.JPG"))
        assertEquals("text/plain", MimeTypes.forFile("catatan.txt"))
    }

    @Test
    fun `forFile - ekstensi tidak dikenal atau kosong`() {
        assertEquals("application/octet-stream", MimeTypes.forFile("arsip.xyz"))
        assertEquals("application/octet-stream", MimeTypes.forFile("tanpa_ekstensi"))
    }

    @Test
    fun `extensionFor - mime dengan parameter`() {
        assertEquals(".pdf", MimeTypes.extensionFor("application/pdf; charset=utf-8"))
        assertEquals(".mp4", MimeTypes.extensionFor("video/mp4"))
        assertEquals(".jpg", MimeTypes.extensionFor("image/jpeg"))
    }

    @Test
    fun `extensionFor - mime tidak dikenal`() {
        assertNull(MimeTypes.extensionFor("application/x-tidak-dikenal"))
        assertNull(MimeTypes.extensionFor(null))
    }
}
