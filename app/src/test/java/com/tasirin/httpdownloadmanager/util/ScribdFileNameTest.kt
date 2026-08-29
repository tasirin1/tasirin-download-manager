package com.tasirin.httpdownloadmanager.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ScribdFileNameTest {

    @Test
    fun `plain title keeps pdf extension`() {
        assertEquals("Scribd_Laporan_Magang.pdf", SocialMediaExtractor.scribdFileName("Laporan Magang"))
    }

    @Test
    fun `unsafe characters and spaces become underscore`() {
        assertEquals("Scribd_A_B___C.pdf", SocialMediaExtractor.scribdFileName("A:B / C*"))
    }

    @Test
    fun `blank title falls back to Scribd_Document`() {
        assertEquals("Scribd_Document.pdf", SocialMediaExtractor.scribdFileName("   "))
    }

    @Test
    fun `long title is truncated`() {
        val long = "x".repeat(300)
        val out = SocialMediaExtractor.scribdFileName(long)
        assertTrue(out.length <= "Scribd_".length + 120 + ".pdf".length)
        assertEquals("Scribd_${"x".repeat(120)}.pdf", out)
    }
}
