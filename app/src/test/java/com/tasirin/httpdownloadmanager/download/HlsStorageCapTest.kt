package com.tasirin.httpdownloadmanager.download

import org.junit.Assert.assertEquals
import org.junit.Test

class HlsStorageCapTest {
    private val minFree = 2L * 1024 * 1024

    @Test fun budgetDividedEvenlyAcrossWindow() {
        // Issue #1411: ekspektasi dibuktikan port-1411.py (port 1:1).
        assertEquals(249_475_712L, hlsSegmentByteCap(1_000_000_000L, minFree, 4))
        assertEquals(25_690_112L, hlsSegmentByteCap(100L * 1024 * 1024, minFree, 4))
    }

    @Test fun reserveIsNeverEaten() {
        assertEquals(0L, hlsSegmentByteCap(1_000_000L, minFree, 4))
        assertEquals(0L, hlsSegmentByteCap(minFree, minFree, 4))
        assertEquals(100L, hlsSegmentByteCap(minFree + 400, minFree, 4))
        assertEquals(100L, hlsSegmentByteCap(minFree + 401, minFree, 4))
    }

    @Test fun degenerateWindowFallsBackToWholeBudget() {
        assertEquals(1_000_000_000L - minFree, hlsSegmentByteCap(1_000_000_000L, minFree, 0))
        assertEquals(1_000_000_000L - minFree, hlsSegmentByteCap(1_000_000_000L, minFree, 1))
    }

    @Test fun hugeFreeSpaceDoesNotOverflow() {
        assertEquals(
            (Long.MAX_VALUE - minFree) / 4,
            hlsSegmentByteCap(Long.MAX_VALUE, minFree, 4)
        )
    }
}
