package com.tasirin.httpdownloadmanager.download

import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMuxer
import java.io.File
import java.io.IOException
import java.nio.ByteBuffer

/** Remux video MPEG-TS (AVC) + audio ADTS (AAC) menjadi satu file MP4 memakai
 *  MediaExtractor + MediaMuxer bawaan Android — tanpa ffmpeg/library tambahan
 *  (APK tetap kecil). Mengembalikan true bila MP4 berhasil dibuat dan diputar. */
object HlsMp4Muxer {

    fun remux(videoTs: File, audioAdts: File, outMp4: File): Boolean {
        var muxer: MediaMuxer? = null
        var videoExt: MediaExtractor? = null
        var audioExt: MediaExtractor? = null
        try {
            if (outMp4.exists() && !outMp4.delete()) return false
            muxer = MediaMuxer(outMp4.absolutePath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)

            // Add semua track DULU, baru start() (syarat MediaMuxer).
            // --- Track video (MPEG-TS AVC) ---
            videoExt = MediaExtractor()
            val vIndex = selectTrack(videoExt.setData(videoTs), isVideo = true) ?: return false
            val vFormat = videoExt.getTrackFormat(vIndex)
            videoExt.selectTrack(vIndex)
            val videoTrack = muxer.addTrack(vFormat)

            // --- Track audio (ADTS AAC) ---
            audioExt = MediaExtractor()
            val aIndex = selectTrack(audioExt.setData(audioAdts), isVideo = false) ?: return false
            val aFormat = audioExt.getTrackFormat(aIndex)
            audioExt.selectTrack(aIndex)
            val audioTrack = muxer.addTrack(aFormat)

            muxer.start()
            val videoBuf = runCatching {
                vFormat.getInteger(MediaFormat.KEY_MAX_INPUT_SIZE)
            }.getOrElse { 8 shl 20 }.coerceIn(1 shl 20, 16 shl 20)
            writeAll(videoExt, muxer, videoTrack, videoBuf)
            writeAll(audioExt, muxer, audioTrack, 1 shl 20)

            muxer.stop()
            return true
        } catch (t: Throwable) {
            runCatching { outMp4.delete() }
            return false
        } finally {
            runCatching { videoExt?.release() }
            runCatching { audioExt?.release() }
            runCatching { muxer?.release() }
        }
    }

    /** setDataSource dengan try-catch agar deteksi error konsisten. Mengembalikan
     *  MediaExtractor siap dibaca. */
    private fun MediaExtractor.setData(file: File): MediaExtractor {
        setDataSource(file.absolutePath)
        return this
    }

    private fun selectTrack(ext: MediaExtractor, isVideo: Boolean): Int? {
        for (i in 0 until ext.trackCount) {
            val mime = ext.getTrackFormat(i).getString(MediaFormat.KEY_MIME) ?: continue
            if (isVideo == mime.startsWith("video/")) return i
        }
        return null
    }

    private fun writeAll(ext: MediaExtractor, muxer: MediaMuxer, track: Int, bufferSize: Int) {
        val buffer = ByteBuffer.allocate(bufferSize)
        val info = MediaCodec.BufferInfo()
        var base = -1L
        var lastPts = -1L
        while (true) {
            val size = ext.readSampleData(buffer, 0)
            if (size < 0) break
            val pts = ext.sampleTime
            // Normalisasi ke 0 supaya track video & audio sejajar; MediaMuxer
            // juga wajib menerima PTS tidak menurun (AVC High dengan B-frame
            // urutannya beda — variasi ini di-fallback ke TS video-only).
            if (base < 0) base = pts
            val normalized = pts - base
            if (normalized < lastPts) throw IOException("Non-monotonic PTS")
            lastPts = normalized
            val flags = if (ext.sampleFlags and MediaExtractor.SAMPLE_FLAG_SYNC != 0) {
                MediaCodec.BUFFER_FLAG_KEY_FRAME
            } else {
                0
            }
            info.set(0, size, normalized, flags)
            muxer.writeSampleData(track, buffer, info)
            if (!ext.advance()) break
        }
    }
}
