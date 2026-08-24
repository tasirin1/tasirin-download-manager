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

    fun safe(fileName: String): String {
        return fileName.replace('/', '_').replace('\\', '_').trim().ifEmpty { "download" }
    }
}
