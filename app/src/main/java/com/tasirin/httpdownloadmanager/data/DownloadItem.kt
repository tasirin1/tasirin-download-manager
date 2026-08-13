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
    val state: DownloadState,
    val bytesDownloaded: Long,
    val totalBytes: Long,
    val error: String? = null,
    val contentUri: String? = null,
    val filePath: String? = null,
    val addedAt: Long = System.currentTimeMillis(),
    val finishedAt: Long = 0,
    val nameIsCustom: Boolean = false,
    val autoResume: Boolean = false,
    val username: String = "",
    val password: String = "",
    val headers: String = "",
    val destination: String = "",
    val folderPath: String = "",
    val speedLimitKbps: Int = 0,
    val priority: Int = 0,
    val checksum: String = "",
    val checksumVerified: Boolean = false,
    val mirrors: List<String> = emptyList(),
    val monitor: Boolean = false,
    val etag: String = "",
    val segments: List<DownloadSegment> = emptyList(),
    val speedBps: Long = 0,
    val etaSeconds: Long = 0
) {
    val progressPercent: Int
        get() = if (totalBytes > 0) ((bytesDownloaded * 100) / totalBytes).toInt() else 0
}
