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

    @Test fun httpMirrorFailoverMatchesPerUrlFailuresOnly() {
        // Issue #544: kegagalan HTTP per-URL memicu failover mirror.
        assertTrue(isHttpMirrorFailoverError("HTTP 404"))
        assertTrue(isHttpMirrorFailoverError("HTTP 410"))
        assertTrue(isHttpMirrorFailoverError("HTTP 403"))
        assertTrue(isHttpMirrorFailoverError("HTTP 500"))
        assertTrue(isHttpMirrorFailoverError("HTTP 503"))
        assertFalse(isHttpMirrorFailoverError("HTTP 401"))
        assertFalse(isHttpMirrorFailoverError("HTTP 429"))
        assertFalse(isHttpMirrorFailoverError("HTTP 416"))
        assertFalse(isHttpMirrorFailoverError("failed to connect"))
        assertFalse(isHttpMirrorFailoverError(null))
        assertFalse(isHttpMirrorFailoverError(""))
    }

    @Test fun hlsUrlRejectionMatchesOnly403And404() {
        // Issue #1410: failedHlsUrls hanya untuk penolakan URL oleh
        // server; ekspektasi dibuktikan port-1410.py (port 1:1).
        assertTrue(isHlsUrlRejectionError("HTTP 403"))
        assertTrue(isHlsUrlRejectionError("HTTP 404"))
        assertTrue(isHlsUrlRejectionError("HTTP 404 Not Found"))
        assertFalse(isHlsUrlRejectionError("HTTP 410"))
        assertFalse(isHlsUrlRejectionError("HTTP 500"))
        assertFalse(isHlsUrlRejectionError("HTTP 401"))
        assertFalse(isHlsUrlRejectionError("HTTP 429"))
        assertFalse(isHlsUrlRejectionError("Not enough storage for HLS: need ~10 MB, free 2 MB"))
        assertFalse(isHlsUrlRejectionError("Live HLS not supported (playlist without EXT-X-ENDLIST)"))
        assertFalse(isHlsUrlRejectionError("Cannot fetch HLS manifest"))
        assertFalse(isHlsUrlRejectionError("No HLS segments found"))
        assertFalse(isHlsUrlRejectionError(null))
        assertFalse(isHlsUrlRejectionError(""))
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
        assertFalse(isTailIncompleteError("Gateway returned an incomplete response"))
        assertFalse(isTailIncompleteError("Server does not support Range (HTTP 200)"))
        assertFalse(isTailIncompleteError(null))
    }
}
