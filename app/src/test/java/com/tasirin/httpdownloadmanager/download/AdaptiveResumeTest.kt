package com.tasirin.httpdownloadmanager.download

import org.junit.Assert.assertEquals
import org.junit.Test

class AdaptiveResumeTest {

    @Test
    fun `keputusan resume adaptif`() {
        // Nilai harapan dibuktikan port Python 1:1 (audit 14, port-1434.py).
        assertEquals(
            AdaptiveResumeAction.FRESH,
            adaptiveResumeAction(existing = 0, code = 200, rangeStart = null, rangeTotal = null)
        )
        assertEquals(
            AdaptiveResumeAction.APPEND,
            adaptiveResumeAction(existing = 0, code = 206, rangeStart = 0, rangeTotal = 500)
        )
        assertEquals(
            AdaptiveResumeAction.APPEND,
            adaptiveResumeAction(existing = 100, code = 206, rangeStart = 100, rangeTotal = 500)
        )
        assertEquals(
            AdaptiveResumeAction.APPEND,
            adaptiveResumeAction(existing = 100, code = 206, rangeStart = null, rangeTotal = null)
        )
        assertEquals(
            AdaptiveResumeAction.FAIL,
            adaptiveResumeAction(existing = 100, code = 206, rangeStart = 50, rangeTotal = 500)
        )
        assertEquals(
            AdaptiveResumeAction.FAIL,
            adaptiveResumeAction(existing = 0, code = 206, rangeStart = 5, rangeTotal = 500)
        )
        assertEquals(
            AdaptiveResumeAction.FRESH,
            adaptiveResumeAction(existing = 100, code = 200, rangeStart = null, rangeTotal = null)
        )
        assertEquals(
            AdaptiveResumeAction.COMPLETE,
            adaptiveResumeAction(existing = 500, code = 416, rangeStart = null, rangeTotal = 500)
        )
        assertEquals(
            AdaptiveResumeAction.RESTART,
            adaptiveResumeAction(existing = 100, code = 416, rangeStart = null, rangeTotal = 500)
        )
        assertEquals(
            AdaptiveResumeAction.RESTART,
            adaptiveResumeAction(existing = 600, code = 416, rangeStart = null, rangeTotal = 500)
        )
        assertEquals(
            AdaptiveResumeAction.RESTART,
            adaptiveResumeAction(existing = 100, code = 416, rangeStart = null, rangeTotal = null)
        )
        assertEquals(
            AdaptiveResumeAction.FAIL,
            adaptiveResumeAction(existing = 0, code = 416, rangeStart = null, rangeTotal = 500)
        )
        assertEquals(
            AdaptiveResumeAction.FAIL,
            adaptiveResumeAction(existing = 100, code = 404, rangeStart = null, rangeTotal = null)
        )
        assertEquals(
            AdaptiveResumeAction.FAIL,
            adaptiveResumeAction(existing = 100, code = 500, rangeStart = null, rangeTotal = null)
        )
    }
}
