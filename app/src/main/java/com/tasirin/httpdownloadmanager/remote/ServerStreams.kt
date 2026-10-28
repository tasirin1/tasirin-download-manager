package com.tasirin.httpdownloadmanager.remote

import java.io.File
import java.io.FileInputStream
import java.io.InputStream
import java.io.RandomAccessFile
import android.content.res.AssetFileDescriptor

/** Gabungkan beberapa file partial menjadi satu stream (streaming resume
 *  multi-segmen tanpa menyusun file utuh di memori). */
internal class ChainInputStream(files: List<File>) : InputStream() {
    // Semua segmen dibuka SEKALIGUS di konstruktor dan panjangnya dibekukan
    // (#232): FD yang terbuka menahan inode versi saat request mulai, jadi
    // unduhan sumber yang di-reset/ditulis ulang/dihapus di tengah stream
    // tidak mencampur byte dua versi ke dalam respons yang totalnya sudah
    // diumumkan; segmen yang menyusut dari snapshot dianggap habis.
    private val streams: List<FileInputStream>
    private val lengths: List<Long>
    val totalLength: Long
    private var idx = 0
    private var remainingInCurrent = 0L
    // Buffer skip dipakai ulang: alokasi 64KB per panggilan boros saat seek besar.
    // Aman: stream respons dipakai satu thread oleh NanoHTTPD.
    private val skipBuf = ByteArray(64 * 1024)

    init {
        val opened = mutableListOf<FileInputStream>()
        val lens = mutableListOf<Long>()
        try {
            for (f in files) {
                opened.add(FileInputStream(f))
                lens.add(f.length())
            }
        } catch (e: Exception) {
            opened.forEach { runCatching { it.close() } }
            throw e
        }
        streams = opened
        lengths = lens
        totalLength = lens.sum()
        remainingInCurrent = lens.firstOrNull() ?: 0L
    }

    private fun next(): FileInputStream? {
        while (idx < streams.size && remainingInCurrent <= 0L) {
            runCatching { streams[idx].close() }
            idx++
            if (idx < streams.size) remainingInCurrent = lengths[idx]
        }
        return if (idx < streams.size) streams[idx] else null
    }

    override fun read(): Int {
        val cur = next() ?: return -1
        val b = cur.read()
        if (b == -1) {
            remainingInCurrent = 0
            return read()
        }
        remainingInCurrent--
        return b
    }

    override fun read(b: ByteArray, off: Int, len: Int): Int {
        // Kontrak InputStream: tolak argumen di luar batas sebelum menyentuh file.
        if (off < 0 || len < 0 || off > b.size || len > b.size - off) throw IndexOutOfBoundsException()
        if (len == 0) return 0
        val cur = next() ?: return -1
        val n = cur.read(b, off, minOf(len.toLong(), remainingInCurrent).toInt())
        if (n == -1) {
            remainingInCurrent = 0
            return read(b, off, len)
        }
        remainingInCurrent -= n
        return n
    }

    override fun skip(n: Long): Long {
        if (n <= 0) return 0
        var remaining = n
        while (remaining > 0) {
            val want = minOf(skipBuf.size.toLong(), remaining).toInt()
            val r = read(skipBuf, 0, want)
            if (r <= 0) break
            remaining -= r
        }
        return n - remaining
    }

    override fun close() {
        streams.forEach { runCatching { it.close() } }
        idx = streams.size
        remainingInCurrent = 0
    }
}

/** Stream AssetFileDescriptor yang mulai dari posisi Range tanpa membaca
 *  dan membuang byte awal. Descriptor ditutup bersama stream-nya. */
internal class PositionedAssetInputStream(
    private val assetDescriptor: AssetFileDescriptor,
    startPosition: Long
) : InputStream() {
    private val stream: FileInputStream = run {
        val s = FileInputStream(assetDescriptor.fileDescriptor)
        val ok = runCatching { s.channel.position(assetDescriptor.startOffset + startPosition); true }.getOrDefault(false)
        if (!ok) {
            runCatching { s.close() }
            runCatching { assetDescriptor.close() }
            throw java.io.IOException("Failed to position media stream")
        }
        s
    }

    override fun read(): Int = stream.read()

    override fun read(b: ByteArray, off: Int, len: Int): Int =
        stream.read(b, off, len)

    override fun close() {
        runCatching { stream.close() }
        runCatching { assetDescriptor.close() }
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
