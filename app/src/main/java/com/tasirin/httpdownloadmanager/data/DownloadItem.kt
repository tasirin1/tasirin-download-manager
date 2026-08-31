package com.tasirin.httpdownloadmanager.data

enum class DownloadState {
    PENDING, DOWNLOADING, PAUSED, COMPLETED, FAILED, CANCELLED
}

data class DownloadSegment(
    val index: Int,
    val start: Long,
    val end: Long,
    val downloaded: Long
)

data class DownloadItem(
    val id: String,
    val url: String,
    val fileName: String,
    var state: DownloadState,
    var bytesDownloaded: Long,
    var totalBytes: Long,
    var error: String? = null,
    val contentUri: String? = null,
    val filePath: String? = null,
    val addedAt: Long = System.currentTimeMillis(),
    var finishedAt: Long = 0,
    val nameIsCustom: Boolean = false,
    var autoResume: Boolean = false,
    val username: String = "",
    val password: String = "",
    val headers: String = "",
    val destination: String = "",
    val folderPath: String = "",
    val speedLimitKbps: Int = 0,
    val priority: Int = 0,
    val checksum: String = "",
    var checksumVerified: Boolean = false,
    val mirrors: List<String> = emptyList(),
    val monitor: Boolean = false,
    val etag: String = "",
    val method: String = "GET",
    val postBody: String = "",
    val segments: List<DownloadSegment> = emptyList(),
    var speedBps: Long = 0,
    var etaSeconds: Long = 0,
    val preferredHeight: Int = 0,
    var progressPercentOverride: Int = -1
) {
    val progressPercent: Int
        // Untuk HLS total asli tidak diketahui, jadi persentase dihitung dari
        // jumlah segmen (via progressPercentOverride); jangan pakai total palsu.
        get() = if (progressPercentOverride >= 0) {
            progressPercentOverride
        } else if (totalBytes > 0) {
            ((bytesDownloaded * 100) / totalBytes).toInt()
        } else {
            0
        }
}
