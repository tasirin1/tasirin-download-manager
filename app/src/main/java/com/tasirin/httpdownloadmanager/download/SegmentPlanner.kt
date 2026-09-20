package com.tasirin.httpdownloadmanager.download

import com.tasirin.httpdownloadmanager.data.DownloadSegment

/** Perencana segmen unduhan murni (bisa diuji JVM tanpa Android).
 * Prasyarat: total >= count (pemanggil engine hanya multi-segmen bila total >= 5MB). */
object SegmentPlanner {
    fun plan(total: Long, count: Int): List<DownloadSegment> {
        val safeCount = count.coerceAtLeast(2)
        require(total > 0) { "Total must be positive" }
        val size = total / safeCount
        return (0 until safeCount).map { i ->
            val start = i * size
            val end = if (i == safeCount - 1) total - 1 else start + size - 1
            DownloadSegment(index = i, start = start, end = end, downloaded = 0)
        }
    }
}
