package com.tasirin.httpdownloadmanager.util

object MimeTypes {

    // Cache mime per nama file: daftar download/galeri me-rebind nama yang sama
    // tiap tick 400ms/scroll sehingga lookup ekstensi berulang sia-sia.
    private val cache = java.util.concurrent.ConcurrentHashMap<String, String>()

    fun forFile(fileName: String): String {
        cache[fileName]?.let { return it }
        // Region ekstensi tanpa alokasi substring+lowercase (beda locale aman
        // via ignoreCase; perilaku ASCII identik dengan versi lowercase).
        val dot = fileName.lastIndexOf('.')
        val s = if (dot < 0) fileName.length else dot + 1
        fun eq(name: String): Boolean =
            fileName.length - s == name.length &&
                fileName.regionMatches(s, name, 0, name.length, ignoreCase = true)
        val mime = when {
            eq("apk") -> "application/vnd.android.package-archive"
            eq("pdf") -> "application/pdf"
            eq("zip") || eq("rar") || eq("7z") || eq("tar") || eq("gz") || eq("xz") -> "application/zip"
            eq("mp3") -> "audio/mpeg"
            eq("m4a") -> "audio/mp4"
            eq("mp4") -> "video/mp4"
            eq("aac") -> "audio/aac"
            eq("wav") -> "audio/wav"
            eq("ogg") -> "audio/ogg"
            eq("flac") -> "audio/flac"
            eq("opus") -> "audio/opus"
            eq("mkv") -> "video/x-matroska"
            eq("3gp") -> "video/3gpp"
            eq("webm") -> "video/webm"
            eq("avi") -> "video/x-msvideo"
            eq("mov") -> "video/quicktime"
            eq("ts") -> "video/mp2t"
            eq("m2ts") -> "video/mp2t"
            eq("jpg") || eq("jpeg") -> "image/jpeg"
            eq("png") -> "image/png"
            eq("gif") -> "image/gif"
            eq("webp") -> "image/webp"
            eq("bmp") -> "image/bmp"
            eq("txt") || eq("md") || eq("log") || eq("csv") -> "text/plain"
            eq("html") || eq("htm") -> "text/html"
            eq("json") -> "application/json"
            eq("xml") -> "application/xml"
            else -> "application/octet-stream"
        }
        if (cache.size > 2000) cache.clear()
        cache[fileName] = mime
        return mime
    }

    fun extensionFor(contentType: String?): String? {
        // Tanpa alokasi substring/trim/lowercase: potong di ';', abaikan spasi
        // tepi via indeks, bandingkan ignoreCase langsung.
        if (contentType == null) return null
        var s = 0
        var e = contentType.indexOf(';').let { if (it < 0) contentType.length else it }
        while (s < e && contentType[s].isWhitespace()) s++
        while (e > s && contentType[e - 1].isWhitespace()) e--
        fun eq(name: String): Boolean =
            e - s == name.length && contentType.regionMatches(s, name, 0, name.length, ignoreCase = true)
        return when {
            eq("application/pdf") -> ".pdf"
            eq("application/zip") -> ".zip"
            eq("application/x-rar-compressed") -> ".rar"
            eq("application/x-7z-compressed") -> ".7z"
            eq("application/json") -> ".json"
            eq("application/xml") || eq("text/xml") -> ".xml"
            eq("application/vnd.android.package-archive") -> ".apk"
            eq("image/jpeg") -> ".jpg"
            eq("image/png") -> ".png"
            eq("image/gif") -> ".gif"
            eq("image/webp") -> ".webp"
            eq("audio/mpeg") || eq("audio/mp3") -> ".mp3"
            eq("audio/mp4") -> ".m4a"
            eq("audio/ogg") || eq("audio/opus") -> ".ogg"
            eq("video/mp4") -> ".mp4"
            eq("video/x-matroska") -> ".mkv"
            eq("video/webm") -> ".webm"
            eq("video/mp2t") -> ".ts"
            eq("text/plain") -> ".txt"
            eq("text/html") -> ".html"
            eq("text/csv") -> ".csv"
            else -> null
        }
    }
}
