package com.tasirin.httpdownloadmanager.util

import android.content.Context
import android.util.Log
import java.io.BufferedWriter
import java.io.File
import java.io.FileOutputStream
import java.io.OutputStreamWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Penulis log crash terpusat (dipakai App dan server remote; sebelumnya
 *  duplikat di dua tempat dengan format sama). */
object CrashLog {

    private val trimLock = Any()
    private const val MAX_BYTES = 100_000
    private const val FILE_NAME = "crash.log"
    /** ThreadLocal formatter (SimpleDateFormat tidak thread-safe). */
    private val stampFormat: ThreadLocal<SimpleDateFormat> = ThreadLocal.withInitial {
        SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US)
    }

    fun append(context: Context, tag: String, t: Throwable) {
        runCatching {
            // Simpan di folder data eksternal: /Android/data/<pkg>/crash.log
            // (terlihat dari file manager tanpa root; otomatis dihapus saat uninstall).
            val dir = context.getExternalFilesDir(null) ?: context.filesDir
            val file = File(dir, FILE_NAME)
            val stamp = (stampFormat.get()
                ?: SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US)).format(Date())
            val text = buildString {
                appendLine("=== $stamp [$tag] ===")
                appendLine(Log.getStackTraceString(t))
                appendLine()
            }
            // Append mode: tidak perlu readText() seluruh file.
            BufferedWriter(
                OutputStreamWriter(FileOutputStream(file, true), Charsets.UTF_8)
            ).use { it.write(text) }
            // Trim bila melebihi batas: baca dari akhir file (RandomAccessFile)
            // supaya tidak perlu load seluruh file ke memori.
            // synchronized: mencegah race condition saat dua thread crash bersamaan.
            synchronized(trimLock) {
                if (file.length() > MAX_BYTES * 1.5) {
                    java.io.RandomAccessFile(file, "rw").use { raf ->
                        val len = raf.length()
                        val buf = ByteArray(MAX_BYTES)
                        raf.seek(len - MAX_BYTES)
                        raf.readFully(buf)
                        raf.setLength(MAX_BYTES.toLong())
                        raf.seek(0)
                        raf.write(buf)
                    }
                }
            }
        }
    }
}
