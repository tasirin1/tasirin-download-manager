package com.tasirin.httpdownloadmanager.download

import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMuxer
import java.io.File
import java.io.IOException
import java.nio.ByteBuffer

/** Remux video MP4 (video-only) + audio MP4/M4A (AAC, keduanya dari
 *  adaptiveFormats) menjadi satu file MP4 memakai MediaExtractor +
 *  MediaMuxer bawaan Android (tanpa ffmpeg, APK tetap kecil). Kedua
 *  track memakai PTS asli file masing-masing (dinormalisasi drift agar
 *  selalu naik). */
object AdaptiveMp4Muxer {

    /** Remux video MP4 (video-only) + audio MP4/M4A (AAC, keduanya dari
     *  adaptiveFormats YouTube) menjadi satu MP4. Kedua track memakai PTS
     *  asli file masing-masing (dinormalisasi drift agar selalu naik). */
    fun remuxMp4s(
        videoMp4: File,
        audioMp4: File,
        outMp4: File
    ): Boolean {
        var muxer: MediaMuxer? = null
        var videoExt: MediaExtractor? = null
        var audioExt: MediaExtractor? = null
        try {
            if (outMp4.exists() && !outMp4.delete()) return false
            muxer = MediaMuxer(outMp4.absolutePath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)

            // --- Track video (MP4 video-only) via MediaExtractor ---
            val openedVideo = openTrack(videoMp4, true) ?: run { runCatching { outMp4.delete() }; return false }
            videoExt = openedVideo.first
            val videoTrack = muxer.addTrack(openedVideo.second)

            // --- Track audio (AAC dalam MP4/M4A) via MediaExtractor ---
            val openedAudio = openTrack(audioMp4, false) ?: run { runCatching { outMp4.delete() }; return false }
            audioExt = openedAudio.first
            val audioTrack = muxer.addTrack(openedAudio.second)

            muxer.start()
            // Kedua track wajib menghasilkan sampel (issue #1217).
            if (writeTrackPts(videoExt, muxer, videoTrack) <= 0) {
                throw IOException("remuxMp4s: no video samples in source")
            }
            if (writeTrackPts(audioExt, muxer, audioTrack) <= 0) {
                throw IOException("remuxMp4s: no audio samples in source")
            }
            muxer.stop()
            return true
        } catch (t: Throwable) {
            runCatching { outMp4.delete() }
            return false
        } finally {
            releaseAll(muxer, videoExt, audioExt)
        }
    }

    /** Batas frame tunggal saat buffer tumbuh: klaim ukuran bohong di atas
     *  ini digagalkan aman (outer catch menghapus partial), bukan OOM. */
    private const val MAX_MUX_FRAME_BYTES = 64 shl 20

    /** Lepas muxer/extractor tanpa lempar — dipakai di blok finally. */
    private fun releaseAll(muxer: MediaMuxer?, vararg exts: MediaExtractor?) {
        exts.forEach { runCatching { it?.release() } }
        runCatching { muxer?.release() }
    }

    /** Buka extractor + pilih track pertama yang cocok (video/audio) + select.
     *  Extractor gagal dibuka dilepas di sini; yang sukses dilepas pemanggil
     *  via releaseAll di finally. */
    private fun openTrack(file: File, video: Boolean): Pair<MediaExtractor, MediaFormat>? {
        val ext = MediaExtractor()
        return try {
            ext.setDataSource(file.absolutePath)
            val index = (if (video) selectVideoTrack(ext) else selectAudioTrack(ext))
                ?: run { runCatching { ext.release() }; return null }
            ext.selectTrack(index)
            Pair(ext, ext.getTrackFormat(index))
        } catch (t: Throwable) {
            runCatching { ext.release() }
            null
        }
    }

    private fun selectVideoTrack(ext: MediaExtractor): Int? {
        for (i in 0 until ext.trackCount) {
            val mime = ext.getTrackFormat(i).getString(MediaFormat.KEY_MIME) ?: continue
            if (mime.startsWith("video/")) return i
        }
        return null
    }

    private fun selectAudioTrack(ext: MediaExtractor): Int? {
        for (i in 0 until ext.trackCount) {
            val mime = ext.getTrackFormat(i).getString(MediaFormat.KEY_MIME) ?: continue
            if (mime.startsWith("audio/")) return i
        }
        return null
    }

    /** Normalisasi PTS track (MP4/M4A) dengan drift minimal agar selalu naik,
     *  lalu tulis semua sampel ke muxer. */
    private fun writeTrackPts(
        ext: MediaExtractor,
        muxer: MediaMuxer,
        track: Int
    ): Int {
        var buffer = ByteBuffer.allocate(1 shl 20)
        val info = MediaCodec.BufferInfo()
        // Penanda boolean terpisah (issue #944): sampleTime sah bisa -1,
        // jadi sentinel -1 pada firstPts menelan sampel pertama dan
        // menjadikan sampel kedua basis — menghasilkan PTS-sama.
        var written = 0
        var hasFirstPts = false
        var firstPts = 0L
        var lastPts = -1L
        var drift = 0L
        while (true) {
            buffer.clear()
            var size = ext.readSampleData(buffer, 0)
            if (size < 0) {
                // Sampel korup di tengah aliran, bukan selalu EOS
                // (doktrin #320, issue #1188): advance() false = EOS
                // sungguhan; true = sampel ini terlewati dan track
                // berlanjut. Tanpa pembedaan ini satu sampel rusak
                // memotong sisa track diam-diam sementara remux tetap
                // dilaporkan sukses.
                if (!ext.advance()) break
                continue
            }
            // Sampel lebih besar dari buffer tetap 1 MiB (keyframe besar):
            // baca ulang dengan buffer lebih besar TANPA advance (sampel
            // sama), pola yang sama seperti jalur video di berkas ini.
            // Tanpa jalur ini remux gagal total / track terpotong diam-diam.
            if (size > buffer.capacity()) {
                if (size > MAX_MUX_FRAME_BYTES) {
                    throw IOException("oversized sample $size bytes in writeTrackPts")
                }
                buffer = ByteBuffer.allocate(size)
                size = ext.readSampleData(buffer, 0)
                if (size < 0) {
                    // Sama seperti cabang korup utama di atas.
                    if (!ext.advance()) break
                    continue
                }
            }
            buffer.position(0)
            buffer.limit(size)
            val pts = ext.sampleTime
            if (!hasFirstPts) {
                firstPts = pts
                hasFirstPts = true
            }
            var normalized = pts - firstPts + drift
            // Kontrak "selalu naik" (issue #944): kesamaan juga dinaikkan
            // — sebelumnya dua sampel ber-PTS identik lolos apa adanya.
            if (lastPts >= 0 && normalized <= lastPts) {
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
            written++
            if (!ext.advance()) break
        }
        return written
    }
}
