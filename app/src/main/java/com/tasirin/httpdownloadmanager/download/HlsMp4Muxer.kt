package com.tasirin.httpdownloadmanager.download

import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMuxer
import java.io.File
import java.nio.ByteBuffer

/** Remux video MPEG-TS (AVC) + audio AAC (ADTS, sudah di-parse) menjadi satu
 *  file MP4 memakai MediaExtractor (video) + MediaMuxer bawaan Android.
 *  Audio ditulis manual dari frame AAC murni (tanpa header ADTS) supaya
 *  deterministik — MediaExtractor untuk file ADTS menghasilkan format
 *  is-adts/sample ber-header yang tidak bisa langsung ditulis ke MP4.
 *  Tanpa ffmpeg/library tambahan (APK tetap kecil). */
object HlsMp4Muxer {

    fun remux(
        videoTs: File,
        audio: AdtsAac.Stream?,
        outMp4: File,
        segmentDurationsUs: List<Long> = emptyList()
    ): Boolean {
        var muxer: MediaMuxer? = null
        var videoExt: MediaExtractor? = null
        try {
            if (outMp4.exists() && !outMp4.delete()) return false
            muxer = MediaMuxer(outMp4.absolutePath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)

            // --- Track video (MPEG-TS AVC) via MediaExtractor ---
            videoExt = MediaExtractor()
            videoExt.setDataSource(videoTs.absolutePath)
            val vIndex = selectVideoTrack(videoExt) ?: return false
            val vFormat = videoExt.getTrackFormat(vIndex)
            videoExt.selectTrack(vIndex)
            val videoTrack = muxer.addTrack(vFormat)

            // --- Track audio (AAC murni) dibangun manual dari ADTS parse ---
            var audioTrack = -1
            if (audio != null && audio.frames.isNotEmpty()) {
                val aFormat = MediaFormat.createAudioFormat(
                    MediaFormat.MIMETYPE_AUDIO_AAC, audio.sampleRate, audio.channels
                )
                aFormat.setByteBuffer("csd-0", ByteBuffer.wrap(audio.csd0))
                aFormat.setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, 1 shl 16)
                audioTrack = muxer.addTrack(aFormat)
            }

            muxer.start()
            val videoBuf = runCatching {
                vFormat.getInteger(MediaFormat.KEY_MAX_INPUT_SIZE)
            }.getOrElse { 8 shl 20 }.coerceIn(1 shl 20, 16 shl 20)
            val videoFrameCount = if (segmentDurationsUs.isNotEmpty()) {
                countVideoFrames(videoTs)
            } else 0
            writeAll(videoExt, muxer, videoTrack, videoBuf, segmentDurationsUs, videoFrameCount)
            if (audioTrack >= 0) {
                audio?.let { stream ->
                    writeAacFrames(muxer, audioTrack, stream.frames, stream.sampleRate)
                }
            }

            muxer.stop()
            return true
        } catch (t: Throwable) {
            runCatching { outMp4.delete() }
            return false
        } finally {
            runCatching { videoExt?.release() }
            runCatching { muxer?.release() }
        }
    }

    private fun selectVideoTrack(ext: MediaExtractor): Int? {
        for (i in 0 until ext.trackCount) {
            val mime = ext.getTrackFormat(i).getString(MediaFormat.KEY_MIME) ?: continue
            if (mime.startsWith("video/")) return i
        }
        return null
    }

    private fun writeAll(
        ext: MediaExtractor,
        muxer: MediaMuxer,
        track: Int,
        bufferSize: Int,
        segmentDurationsUs: List<Long>,
        videoFrameCount: Int
    ) {
        val buffer = ByteBuffer.allocate(bufferSize)
        val info = MediaCodec.BufferInfo()
        if (segmentDurationsUs.isEmpty() || videoFrameCount <= 1) {
            // Playlist tanpa EXTINF atau frame tidak terhitung (jarang):
            // fallback ke PTS dengan drift agar tetap naik.
            writeAllPts(ext, muxer, track, buffer, info, bufferSize)
            return
        }

        // PTS MPEG-TS YouTube tidak bisa diandalkan (restart per segmen, span
        // tidak proporsional dengan #EXTINF) sehingga tidak dipakai. Sebagai
        // gantinya, durasi total video diambil dari #EXTINF lalu dibagi rata
        // ke setiap frame — timeline seragam, naik terus, dan sinkron dengan
        // audio (frame-count × 1024/sampleRate). Hasil: video lancar tanpa
        // stutter maupun desync.
        val totalUs = segmentDurationsUs.sum().coerceAtLeast(1L)
        val stepUs = (totalUs / videoFrameCount).coerceAtLeast(1L)
        var frameIndex = 0L
        while (true) {
            buffer.clear()
            val size = ext.readSampleData(buffer, 0)
            if (size < 0) break
            buffer.position(0)
            buffer.limit(size)
            val pts = frameIndex * stepUs
            val flags = if (ext.sampleFlags and MediaExtractor.SAMPLE_FLAG_SYNC != 0) {
                MediaCodec.BUFFER_FLAG_KEY_FRAME
            } else {
                0
            }
            info.set(0, size, pts, flags)
            muxer.writeSampleData(track, buffer, info)
            if (!ext.advance()) break
            frameIndex++
        }
    }

    /** Hitung jumlah frame video pada file .ts (pass terpisah, extractor baru
     *  dikembalikan ke posisi awal) untuk timeline seragam. */
    private fun countVideoFrames(file: File): Int {
        val ext = MediaExtractor()
        return try {
            ext.setDataSource(file.absolutePath)
            val index = selectVideoTrack(ext) ?: return 0
            ext.selectTrack(index)
            val size = runCatching {
                ext.getTrackFormat(index).getInteger(MediaFormat.KEY_MAX_INPUT_SIZE)
            }.getOrElse { 8 shl 20 }.coerceIn(1 shl 20, 16 shl 20)
            val buffer = ByteBuffer.allocate(size)
            var count = 0
            while (true) {
                buffer.clear()
                if (ext.readSampleData(buffer, 0) < 0) break
                count++
                if (!ext.advance()) break
            }
            count
        } catch (t: Throwable) {
            0
        } finally {
            runCatching { ext.release() }
        }
    }

    /** Fallback: normalisasi PTS dengan drift minimal agar tetap naik. */
    private fun writeAllPts(
        ext: MediaExtractor,
        muxer: MediaMuxer,
        track: Int,
        buffer: ByteBuffer,
        info: MediaCodec.BufferInfo,
        @Suppress("UNUSED_PARAMETER") bufferSize: Int
    ) {
        var firstPts = -1L
        var lastPts = -1L
        var drift = 0L
        while (true) {
            buffer.clear()
            val size = ext.readSampleData(buffer, 0)
            if (size < 0) break
            buffer.position(0)
            buffer.limit(size)
            val pts = ext.sampleTime
            if (firstPts < 0) firstPts = pts
            var normalized = pts - firstPts + drift
            if (lastPts >= 0 && normalized < lastPts) {
                drift += lastPts - normalized + 1
                normalized = lastPts + 1
            }
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

    /** Tulis frame AAC murni (tanpa header ADTS) dengan PTS kontinu — tiap
     *  frame = 1024 sampel audio pada sampleRate. */
    private fun writeAacFrames(
        muxer: MediaMuxer,
        track: Int,
        frames: List<ByteArray>,
        sampleRate: Int
    ) {
        val buffer = ByteBuffer.allocate(1 shl 16)
        val info = MediaCodec.BufferInfo()
        val stepUs = if (sampleRate > 0) {
            1_000_000L * AdtsAac.SAMPLES_PER_FRAME / sampleRate
        } else {
            0L
        }
        var pts = 0L
        for (frame in frames) {
            buffer.clear()
            buffer.put(frame)
            buffer.flip()
            info.set(0, frame.size, pts, 0)
            muxer.writeSampleData(track, buffer, info)
            pts += stepUs
        }
    }
}
