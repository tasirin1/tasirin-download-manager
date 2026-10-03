package com.tasirin.httpdownloadmanager.download

import org.junit.Assert.assertEquals
import org.junit.Test

class HlsDurationTest {

    @Test
    fun `durasi normal dikonversi ke mikrodetik`() {
        assertEquals(10_000_000L, hlsSegmentDurationUs(10.0))
        assertEquals(0L, hlsSegmentDurationUs(0.0))
    }

    @Test
    fun `durasi negatif tak hingga dijepit nol`() {
        assertEquals(0L, hlsSegmentDurationUs(-5.0))
        assertEquals(0L, hlsSegmentDurationUs(Double.NaN))
        assertEquals(0L, hlsSegmentDurationUs(Double.POSITIVE_INFINITY))
    }

    @Test
    fun `durasi raksasa dijepit 24 jam`() {
        assertEquals(86_400_000_000L, hlsSegmentDurationUs(1e12))
    }
}
