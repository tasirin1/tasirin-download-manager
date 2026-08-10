package com.tasirin.httpdownloadmanager.util

import android.content.Context
import android.util.Log
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Penulis log crash terpusat (dipakai App dan server remote; sebelumnya
 *  duplikat di dua tempat dengan format sama). */
object CrashLog {

    private const val MAX_BYTES = 100_000
    private const val FILE_NAME = "crash.log"

    fun append(context: Context, tag: String, t: Throwable) {
        runCatching {
            val file = File(context.filesDir, FILE_NAME)
            val stamp = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(Date())
            val text = buildString {
                appendLine("=== $stamp [$tag] ===")
                appendLine(Log.getStackTraceString(t))
                appendLine()
            }
            val existing = if (file.exists()) file.readText() else ""
            file.writeText((existing + text).takeLast(MAX_BYTES))
        }
    }
}
