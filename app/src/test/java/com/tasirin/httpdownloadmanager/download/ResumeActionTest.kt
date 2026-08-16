package com.tasirin.httpdownloadmanager.download

import org.junit.Assert.assertEquals
import org.junit.Test

class ResumeActionTest {

    @Test
    fun `catatan nol dan file kosong - lanjut tanpa aksi`() {
        assertEquals(ResumeAction.KEEP, resumeAction(recorded = 0L, fileLength = 0L))
    }

    @Test
    fun `catatan nol tapi ada sisa file - mulai dari nol`() {
        assertEquals(ResumeAction.RESTART, resumeAction(recorded = 0L, fileLength = 100L))
    }

    @Test
    fun `file lebih pendek dari catatan - mulai dari nol (data hilang)`() {
        assertEquals(ResumeAction.RESTART, resumeAction(recorded = 500L, fileLength = 300L))
    }

    @Test
    fun `file sama dengan catatan - lanjut tanpa aksi`() {
        assertEquals(ResumeAction.KEEP, resumeAction(recorded = 500L, fileLength = 500L))
    }

    @Test
    fun `file lebih panjang dari catatan - pangkas ke posisi catatan`() {
        assertEquals(ResumeAction.TRUNCATE_TO_RECORD, resumeAction(recorded = 500L, fileLength = 900L))
    }
}
