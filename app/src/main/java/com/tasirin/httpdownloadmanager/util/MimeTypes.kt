package com.tasirin.httpdownloadmanager.util

object MimeTypes {

    fun forFile(fileName: String): String {
        val ext = fileName.substringAfterLast('.', "").lowercase()
        return when (ext) {
            "apk" -> "application/vnd.android.package-archive"
            "pdf" -> "application/pdf"
            "zip", "rar", "7z", "tar", "gz", "xz" -> "application/zip"
            "mp3" -> "audio/mpeg"
            "m4a" -> "audio/mp4"
            "mp4" -> "video/mp4"
            "aac" -> "audio/aac"
            "wav" -> "audio/wav"
            "ogg" -> "audio/ogg"
            "flac" -> "audio/flac"
            "opus" -> "audio/opus"
            "mkv" -> "video/x-matroska"
            "3gp" -> "video/3gpp"
            "webm" -> "video/webm"
            "avi" -> "video/x-msvideo"
            "mov" -> "video/quicktime"
            "ts" -> "video/mp2t"
            "m2ts" -> "video/mp2t"
            "jpg", "jpeg" -> "image/jpeg"
            "png" -> "image/png"
            "gif" -> "image/gif"
            "webp" -> "image/webp"
            "bmp" -> "image/bmp"
            "txt", "md", "log", "csv" -> "text/plain"
            "html", "htm" -> "text/html"
            "json" -> "application/json"
            "xml" -> "application/xml"
            else -> "application/octet-stream"
        }
    }

    fun extensionFor(contentType: String?): String? {
        val mime = contentType?.substringBefore(';')?.trim()?.lowercase().orEmpty()
        return when (mime) {
            "application/pdf" -> ".pdf"
            "application/zip" -> ".zip"
            "application/x-rar-compressed" -> ".rar"
            "application/x-7z-compressed" -> ".7z"
            "application/json" -> ".json"
            "application/xml", "text/xml" -> ".xml"
            "application/vnd.android.package-archive" -> ".apk"
            "image/jpeg" -> ".jpg"
            "image/png" -> ".png"
            "image/gif" -> ".gif"
            "image/webp" -> ".webp"
            "audio/mpeg", "audio/mp3" -> ".mp3"
            "audio/mp4" -> ".m4a"
            "audio/ogg", "audio/opus" -> ".ogg"
            "video/mp4" -> ".mp4"
            "video/x-matroska" -> ".mkv"
            "video/webm" -> ".webm"
            "video/mp2t" -> ".ts"
            "text/plain" -> ".txt"
            "text/html" -> ".html"
            "text/csv" -> ".csv"
            else -> null
        }
    }
}
