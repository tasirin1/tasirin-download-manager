package com.tasirin.httpdownloadmanager.download

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RangeRejectResumeTest {
    @Test fun keepsPartialWhenProgressExists() {
        assertTrue(shouldKeepPartialOnRangeReject(1))
        assertTrue(shouldKeepPartialOnRangeReject(60_856_461))
    }

    @Test fun restartsFromZeroWhenNoProgress() {
        assertFalse(shouldKeepPartialOnRangeReject(0))
        assertFalse(shouldKeepPartialOnRangeReject(-5))
    }

    @Test fun freshUrlSizeMustMatchStored() {
        assertTrue(isFreshResumeSizeValid(61_147_728, 61_147_728))
        assertFalse(isFreshResumeSizeValid(61_147_728, 60_000_000))
    }

    @Test fun unknownSizeAllowsBestEffortResume() {
        assertTrue(isFreshResumeSizeValid(61_147_728, -1))
        assertTrue(isFreshResumeSizeValid(0, 61_147_728))
    }
}
