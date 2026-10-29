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

    // Varian jenjang TERATAS (issue #621): jenjang itu tak punya
    // penerus untuk dipromosikan, jadi nilai tepat di bawah 1024
    // yang pembulatannya mencapai 1024.00 ditahan di 1023.99 —
    // kelas #603 yang tersisa di langit-langit. Nilai yang sungguh
    // >= 1024 di jenjang teratas tampil apa adanya.
    private fun twoDecimalsTop(v: Double): String =
        if (v >= 1023.995 && v < 1024.0) "1023.99" else twoDecimals(v)

    private fun pad2(n: Long): String = if (n < 10) "0$n" else n.toString()

    // Ambang promosi satuan (issue #579): nilai yang pembulatannya
    // mencapai 1024.0/1024.00 harus naik satuan, bukan tampil sebagai
    // "1024.0 KB" tepat di bawah ambang. oneDecimal mencapai 1024.0
    // mulai 1023.95; twoDecimals mencapai 1024.00 mulai 1023.995.
    fun bytes(bytes: Long): String {
        if (bytes < 1024) return "$bytes B"
        val kb = bytes / 1024.0
        if (kb < 1023.95) return oneDecimal(kb) + " KB"
        val mb = kb / 1024.0
        if (mb < 1023.95) return oneDecimal(mb) + " MB"
        val gb = mb / 1024.0
        if (gb < 1023.995) return twoDecimals(gb) + " GB"
        val tb = gb / 1024.0
        if (tb < 1023.995) return twoDecimals(tb) + " TB"
        return twoDecimalsTop(tb / 1024.0) + " PB"
    }

    fun speed(bps: Long): String {
        if (bps < 1024) return "$bps B/s"
        val kb = bps / 1024.0
        if (kb < 1023.95) return oneDecimal(kb) + " KB/s"
        val mb = kb / 1024.0
        // Skema desimal sama dengan bytes() (issue #603): KB/MB satu
        // desimal, GB ke atas dua desimal — sebelumnya MB di sini dua
        // desimal sehingga besaran yang sama tampil beda gaya antara
        // label ukuran dan label kecepatan.
        if (mb < 1023.95) return oneDecimal(mb) + " MB/s"
        val gb = mb / 1024.0
        if (gb < 1023.995) return twoDecimals(gb) + " GB/s"
        return twoDecimalsTop(gb / 1024.0) + " TB/s"
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
