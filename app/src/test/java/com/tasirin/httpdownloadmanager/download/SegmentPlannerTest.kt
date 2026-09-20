package com.tasirin.httpdownloadmanager.download

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SegmentPlannerTest {
    @Test
    fun plan_membagiHabis_tanpaCelah() {
        val segs = SegmentPlanner.plan(1000, 4)
        assertEquals(4, segs.size)
        assertEquals(0L, segs.first().start)
        assertEquals(999L, segs.last().end)
        for (i in 1 until segs.size) {
            assertEquals(segs[i - 1].end + 1, segs[i].start)
        }
    }

    @Test
    fun plan_sisa_ditampungSegmenTerakhir() {
        val segs = SegmentPlanner.plan(1000, 3)
        assertEquals(3, segs.size)
        assertEquals(999L, segs.last().end)
        assertTrue(segs.last().end - segs.last().start >= 333L)
    }

    @Test
    fun plan_countKecil_dipaksaDua() {
        assertEquals(2, SegmentPlanner.plan(100, 1).size)
    }
}
