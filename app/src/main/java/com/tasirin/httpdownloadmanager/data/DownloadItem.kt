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
    val method: String = "GET",
    val postBody: String = "",
    val segments: List<DownloadSegment> = emptyList(),
    val speedBps: Long = 0,
    val etaSeconds: Long = 0,
    val preferredHeight: Int = 0,
    val preferredAudioLang: String = "",
    val progressPercentOverride: Int = -1,
    val retryCount: Int = 0,
    val totalBytesEstimated: Boolean = false
) {
    val progressPercent: Int
        // Untuk HLS total asli tidak diketahui, jadi persentase dihitung dari
        // jumlah segmen (via progressPercentOverride); jangan pakai total palsu.
        get() = if (state == DownloadState.COMPLETED) {
            // Server kadang melaporkan Content-Length lebih besar dari byte
            // yang benar-benar diterima (mis. CDN Instagram) — download sudah
            // selesai, jadi tampilan harus 100%, bukan 90-95% yang "mentok".
            100
        } else if (progressPercentOverride >= 0) {
            // Server kadang under-report total sehingga hitungan bisa >100.
            progressPercentOverride.coerceIn(0, 100)
        } else if (totalBytes > 0) {
            ((bytesDownloaded * 100) / totalBytes).toInt().coerceIn(0, 100)
        } else {
            0
        }
}

/** Progres gabungan notifikasi: hanya item yang totalnya diketahui yang
 *  dihitung; null = semua tak diketahui sehingga tampil indeterminate.
 *  Byte per item dijepit ke totalnya agar over-report satu CDN tak
 *  mendongkrak persen gabungan di atas 100. Murni agar bisa di-unit-test. */
internal fun aggregateDownloadProgress(items: List<DownloadItem>): Int? {
    val known = items.filter { it.totalBytes > 0 }
    if (known.isEmpty()) return null
    val total = known.sumOf { it.totalBytes }
    if (total <= 0) return null
    val done = known.sumOf { it.bytesDownloaded.coerceIn(0L, it.totalBytes) }
    return ((done * 100) / total).toInt().coerceIn(0, 100)
}

/** Progres galeri per nama file: nama sama (duplikat sebelum unik-final atau
 *  hasil rename) memakai progres tertinggi agar sel tak tertukar angka basi.
 *  Item selesai dikecualikan — galeri memakai entry file asli, bukan parsial. */
internal fun fileProgressByName(items: List<DownloadItem>): Map<String, Int> =
    items.filter { it.state != DownloadState.COMPLETED }
        .groupBy { it.fileName }
        .mapValues { (_, group) -> group.maxOf { it.progressPercent } }
