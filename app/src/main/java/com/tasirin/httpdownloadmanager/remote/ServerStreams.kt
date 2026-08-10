package com.tasirin.httpdownloadmanager.remote

import java.io.File
import java.io.FileInputStream
import java.io.InputStream
import java.io.RandomAccessFile

/** Gabungkan beberapa file partial menjadi satu stream (streaming resume
 *  multi-segmen tanpa menyusun file utuh di memori). */
internal class ChainInputStream(private val files: List<File>) : InputStream() {
    private var current: InputStream? = null
    private var idx = 0

    private fun next(): InputStream? {
        current?.let { runCatching { it.close() } }
        if (idx >= files.size) return null
        val f = files[idx++]
        val stream = FileInputStream(f)
        current = stream
        return stream
    }

    override fun read(): Int {
        while (true) {
            val cur = current ?: next() ?: return -1
            val b = cur.read()
            if (b != -1) return b
            current = null
        }
    }

    override fun read(b: ByteArray, off: Int, len: Int): Int {
        if (len == 0) return 0
        while (true) {
            val cur = current ?: next() ?: return -1
            val n = cur.read(b, off, len)
            if (n != -1) return n
            current = null
        }
    }

    override fun close() {
        current?.let { runCatching { it.close() } }
        current = null
    }
}

/** Tulis ke RandomAccessFile lewat antarmuka OutputStream (upload chunk). */
internal class RandomAccessOutputStream(private val raf: RandomAccessFile) : java.io.OutputStream() {
    override fun write(b: Int) = raf.write(b)
    override fun write(b: ByteArray, off: Int, len: Int) = raf.write(b, off, len)
}

/** Tutup stream + hapus file sementara (tmp upload/ZIP) setelah selesai. */
internal class DeleteOnCloseStream(
    private val delegate: InputStream,
    private val fileToDelete: File?
) : InputStream() {
    override fun read(): Int = delegate.read()
    override fun read(b: ByteArray, off: Int, len: Int): Int = delegate.read(b, off, len)
    override fun close() {
        runCatching { delegate.close() }
        val toDelete = fileToDelete
        if (toDelete != null) runCatching { toDelete.delete() }
    }
}
