package com.tasirin.httpdownloadmanager.util

object FileNames {

    fun unique(fileName: String, taken: (String) -> Boolean): String {
        if (!taken(fileName)) return fileName
        val dot = fileName.lastIndexOf('.')
        val base = if (dot > 0) fileName.substring(0, dot) else fileName
        val ext = if (dot > 0) fileName.substring(dot) else ""
        var i = 1
        while (taken("$base ($i)$ext")) i++
        return "$base ($i)$ext"
    }

    // Sanitasi nama file: buang separator, karakter kontrol, dan nama
    // reserved "." / ".."; pangkas titik/spasi akhir (Windows) dan batasi
    // panjang agar aman dipakai sebagai File(downloadDir, nama).
    fun safe(fileName: String): String {
        var clean = fileName.replace('/', '_').replace('\\', '_')
        val sb = StringBuilder(clean.length)
        for (c in clean) {
            if (c.code in 0x00..0x1F || c.code == 0x7F) sb.append('_')
            else sb.append(c)
        }
        clean = sb.toString().trim()
        clean = clean.trimEnd('.', ' ')
        if (clean.isEmpty() || clean == "." || clean == "..") return "download"
        if (clean.length > 200) {
            val dot = clean.lastIndexOf('.')
            clean = if (dot in 1..190) clean.substring(0, 190) + clean.substring(dot)
            else clean.substring(0, 200)
        }
        return clean
    }
}
