package com.tasirin.httpdownloadmanager.util

import java.io.ByteArrayOutputStream
import java.io.File

/** Susun gambar halaman Scribd (JPEG) menjadi satu file PDF tanpa library
 *  tambahan. Byte JPEG disisipkan mentah ke objek image (Filter /DCTDecode)
 *  sehingga ukuran PDF nyaris sama dengan total gambar dan APK tetap kecil. */
object ScribdPdf {

    data class Page(val jpeg: ByteArray, val width: Int, val height: Int, val colorSpace: String)

    data class JpegInfo(val width: Int, val height: Int, val channels: Int)

    fun isJpeg(bytes: ByteArray): Boolean {
        return bytes.size > 2 && bytes[0] == 0xFF.toByte() && bytes[1] == 0xD8.toByte()
    }

    /** Baca dimensi & jumlah channel dari header JPEG (marker SOF). */
    fun jpegInfo(bytes: ByteArray): JpegInfo? {
        var i = 2 // lewati SOI (FF D8)
        while (i + 9 < bytes.size) {
            if (bytes[i] != 0xFF.toByte()) { i++; continue }
            val marker = bytes[i + 1].toInt() and 0xFF
            // Standalone marker (RST, D0-D7) tidak punya payload length.
            if (marker in 0xD0..0xD9) { i += 2; continue }
            if (marker == 0x01) { i += 2; continue } // TEM
            val len = ((bytes[i + 2].toInt() and 0xFF) shl 8) or (bytes[i + 3].toInt() and 0xFF)
            if (len < 2) return null
            when (marker) {
                // SOF0-SOF15 (kecuali DHT C4, JPG C8, DAC CC)
                in 0xC0..0xC3, in 0xC5..0xC7, in 0xC9..0xCB, in 0xCD..0xCF -> {
                    val precision = bytes[i + 4].toInt() and 0xFF
                    if (precision != 8) return null
                    val height = ((bytes[i + 5].toInt() and 0xFF) shl 8) or (bytes[i + 6].toInt() and 0xFF)
                    val width = ((bytes[i + 7].toInt() and 0xFF) shl 8) or (bytes[i + 8].toInt() and 0xFF)
                    val channels = bytes[i + 9].toInt() and 0xFF
                    if (width <= 0 || height <= 0) return null
                    return JpegInfo(width, height, channels)
                }
            }
            i += 2 + len
        }
        return null
    }

    /** Gabung halaman ke satu PDF. Mengembalikan false bila gagal. */
    fun build(out: File, pages: List<Page>): Boolean {
        if (pages.isEmpty()) return false
        return try {
            val body = ByteArrayOutputStream()
            val offsets = ArrayList<Int>(pages.size * 3 + 3)

            body.write("%PDF-1.4\n".toByteArray())
            val catalog = body.size()
            offsets.add(catalog)
            body.write("1 0 obj\n<< /Type /Catalog /Pages 2 0 R >>\nendobj\n".toByteArray())

            // Objek Pages
            val kids = pages.indices.joinToString(" ") { "${3 + it * 3} 0 R" }
            offsets.add(body.size())
            body.write("2 0 obj\n<< /Type /Pages /Kids [$kids] /Count ${pages.size} >>\nendobj\n".toByteArray())

            for ((i, page) in pages.withIndex()) {
                val pageId = 3 + i * 3
                val imgId = pageId + 1
                val contentId = pageId + 2

                // Objek Page
                offsets.add(body.size())
                body.write("$pageId 0 obj\n".toByteArray())
                body.write("<< /Type /Page /Parent 2 0 R /MediaBox [0 0 ${page.width} ${page.height}] ".toByteArray())
                body.write("/Resources << /XObject << /Im0 $imgId 0 R >> >> /Contents $contentId 0 R >>\nendobj\n".toByteArray())

                // Objek Image (JPEG mentah)
                offsets.add(body.size())
                body.write("$imgId 0 obj\n".toByteArray())
                body.write("<< /Type /XObject /Subtype /Image /Width ${page.width} /Height ${page.height} ".toByteArray())
                body.write("/ColorSpace ${page.colorSpace} /BitsPerComponent 8 /Filter /DCTDecode /Length ${page.jpeg.size} >>\nstream\n".toByteArray())
                body.write(page.jpeg)
                body.write("\nendstream\nendobj\n".toByteArray())

                // Objek Contents
                offsets.add(body.size())
                val content = "q\n${page.width} 0 0 ${page.height} 0 0 cm\n/Im0 Do\nQ\n".toByteArray()
                body.write("$contentId 0 obj\n<< /Length ${content.size} >>\nstream\n".toByteArray())
                body.write(content)
                body.write("endstream\nendobj\n".toByteArray())
            }

            val xref = body.size()
            val count = pages.size * 3 + 2 // objek 0 sisanya
            body.write("xref\n0 $count\n".toByteArray())
            body.write("0000000000 65535 f \n".toByteArray())
            for (off in offsets) {
                body.write("%010d 00000 n \n".format(off).toByteArray())
            }
            body.write("trailer\n<< /Size $count /Root 1 0 R >>\nstartxref\n$xref\n%%EOF\n".toByteArray())

            out.writeBytes(body.toByteArray())
            out.length() > 0L
        } catch (_: Exception) {
            runCatching { out.delete() }
            false
        }
    }
}
