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

    @Test fun rangeRejectMatchesRawErrorsOnly() {
        assertTrue(isRangeRejectError("Server does not support Range (HTTP 200)"))
        assertTrue(isRangeRejectError("Server does not support Range (HTTP 501)"))
        assertTrue(isRangeRejectError("HTTP 416"))
        assertTrue(isRangeRejectError("server refused resume; try another URL/mirror"))
        assertFalse(isRangeRejectError(null))
        assertFalse(isRangeRejectError(""))
    }

    @Test fun retryResumeMessageIsNotFinalReject() {
        assertFalse(
            isRangeRejectError(
                "Server stopped supporting Range (link may have expired) — " +
                    "retrying resume from 85.5 MB"
            )
        )
    }

    @Test fun tailIncompleteDetected() {
        assertTrue(isTailIncompleteError("Size mismatch: expected 90474052 (Content-Length), received 89618157"))
        assertTrue(isTailIncompleteError("Segment 3 incomplete"))
        assertFalse(isTailIncompleteError("Server does not support Range (HTTP 200)"))
        assertFalse(isTailIncompleteError(null))
    }
}
