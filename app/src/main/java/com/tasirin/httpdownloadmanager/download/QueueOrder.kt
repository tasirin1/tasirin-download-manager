package com.tasirin.httpdownloadmanager.download

import com.tasirin.httpdownloadmanager.data.DownloadItem

/** Urutan antrean download (murni, bisa diuji JVM):
 *  prioritas tertinggi dulu; bila smallFirst aktif, ukuran terkecil dulu
 *  (ukuran belum diketahui dimajukan paling akhir). */
fun downloadQueueOrder(smallFirst: Boolean): Comparator<DownloadItem> =
    compareByDescending<DownloadItem> { it.priority }
        .thenBy {
            if (smallFirst && it.totalBytes > 0) it.totalBytes else Long.MAX_VALUE
        }
