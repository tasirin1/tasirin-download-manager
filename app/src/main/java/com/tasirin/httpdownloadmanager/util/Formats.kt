package com.tasirin.httpdownloadmanager.util

object Formats {

    // Satu desimal tanpa Formatter: String.format("%.1f") mahal bila dipanggil
    // tiap tick progres per item terlihat + tiap cell galeri saat scroll.
    private fun oneDecimal(v: Double): String {
        val r = Math.round(v * 10.0)
        return "" + r / 10 + "." + r % 10
    }

    private fun twoDecimals(v: Double): String {
        val r = Math.round(v * 100.0)
        val dec = r % 100
        return "" + r / 100 + "." + dec / 10 + dec % 10
    }

    private fun pad2(n: Long): String = if (n < 10) "0$n" else n.toString()

    fun bytes(bytes: Long): String {
        if (bytes < 1024) return "$bytes B"
        val kb = bytes / 1024.0
        if (kb < 1024) return oneDecimal(kb) + " KB"
        val mb = kb / 1024.0
        if (mb < 1024) return oneDecimal(mb) + " MB"
        return twoDecimals(mb / 1024.0) + " GB"
    }

    fun speed(bps: Long): String {
        if (bps < 1024) return "$bps B/s"
        val kb = bps / 1024.0
        if (kb < 1024) return oneDecimal(kb) + " KB/s"
        return twoDecimals(kb / 1024.0) + " MB/s"
    }

    /** Durasi video/gambar -> "m:ss" atau "h:mm:ss" (0 -> "0:00"). */
    fun duration(ms: Long): String {
        if (ms <= 0) return "0:00"
        val total = ms / 1000
        val h = total / 3600
        val m = (total % 3600) / 60
        val s = total % 60
        return if (h > 0) {
            "" + h + ":" + pad2(m) + ":" + pad2(s)
        } else {
            "" + m + ":" + pad2(s)
        }
    }

    fun eta(seconds: Long): String {
        if (seconds <= 0) return "0s"
        val h = seconds / 3600
        val m = (seconds % 3600) / 60
        val s = seconds % 60
        return when {
            h > 0 -> "" + h + "h " + m + "m"
            m > 0 -> "" + m + "m " + s + "s"
            else -> "" + s + "s"
        }
    }
}
