package com.tasirin.httpdownloadmanager.download

import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMuxer
import java.io.File
import java.io.IOException
import java.nio.ByteBuffer
import java.util.concurrent.CancellationException

/** Remux video MP4 (video-only) + audio MP4/M4A (AAC, keduanya dari
 *  adaptiveFormats) menjadi satu file MP4 memakai MediaExtractor +
 *  MediaMuxer bawaan Android (tanpa ffmpeg, APK tetap kecil). Kedua
 *  track memakai PTS asli file masing-masing (dinormalisasi drift agar
 *  selalu naik). */
object AdaptiveMp4Muxer {

    /** Remux video MP4 (video-only) + audio MP4/M4A (AAC, keduanya dari
     *  adaptiveFormats YouTube) menjadi satu MP4. Kedua track memakai PTS
     *  asli file masing-masing (dinormalisasi drift agar selalu naik).
     *
     *  [onProgress] dipanggil berkala dari loop penyalinan track dengan
     *  (byte sampel tersalin, total gabungan kedua berkas sumber) agar
     *  fase gabung punya progres sendiri (diagnosis bug-99 A1: tanpa ini
     *  tampilan membeku di persen akhir unduhan selama remux berjalan).
     *  [isCancelled] diperiksa berkala di loop yang sama; true melempar
     *  CancellationException dan keluaran parsial dibuang di sini.
     *
     *  Kegagalan TIDAK lagi ditelan menjadi boolean bisu (diagnosis
     *  bug-99 A2): semua penyebab dilempar sebagai IOException yang
     *  memuat mime kedua track + pesan penyebab, agar pemanggil bisa
     *  mencatatnya ke log dan menampilkannya ke pengguna. */
    fun remuxMp4s(
        videoMp4: File,
        audioMp4: File,
        outMp4: File,
        onProgress: (copiedBytes: Long, totalBytes: Long) -> Unit = { _, _ -> },
        isCancelled: () -> Boolean = { false }
    ) {
        var muxer: MediaMuxer? = null
        var videoExt: MediaExtractor? = null
        var audioExt: MediaExtractor? = null
        var videoMime: String? = null
        var audioMime: String? = null
        try {
            if (outMp4.exists() && !outMp4.delete()) {
                throw IOException("remuxMp4s: cannot clear previous output ${outMp4.name}")
            }
            muxer = MediaMuxer(outMp4.absolutePath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)

            // --- Track video (MP4 video-only) via MediaExtractor ---
            val openedVideo = openTrack(videoMp4, true)
                ?: throw IOException("remuxMp4s: no video track in ${videoMp4.name}")
            videoExt = openedVideo.first
            videoMime = openedVideo.second.getString(MediaFormat.KEY_MIME)
            val videoTrack = muxer.addTrack(openedVideo.second)

            // --- Track audio (AAC dalam MP4/M4A) via MediaExtractor ---
            val openedAudio = openTrack(audioMp4, false)
                ?: throw IOException("remuxMp4s: no audio track in ${audioMp4.name}")
            audioExt = openedAudio.first
            audioMime = openedAudio.second.getString(MediaFormat.KEY_MIME)
            val audioTrack = muxer.addTrack(openedAudio.second)

            muxer.start()
            // Kedua track wajib menghasilkan sampel (issue #1217).
            val totalSourceBytes = videoMp4.length() + audioMp4.length()
            val videoResult = writeTrackPts(videoExt, muxer, videoTrack, 0L, totalSourceBytes, onProgress, isCancelled)
            if (videoResult.first <= 0) {
                throw IOException("remuxMp4s: no video samples in source")
            }
            val audioResult = writeTrackPts(audioExt, muxer, audioTrack, videoResult.second, totalSourceBytes, onProgress, isCancelled)
            if (audioResult.first <= 0) {
                throw IOException("remuxMp4s: no audio samples in source")
            }
            onProgress(totalSourceBytes, totalSourceBytes)
            muxer.stop()
        } catch (e: CancellationException) {
            // Batal adalah vonis pemanggil, bukan kegagalan remux:
            // keluaran parsial dibuang di sini, status batal diteruskan
            // apa adanya agar rantai coroutine membedakannya dari gagal.
            runCatching { outMp4.delete() }
            throw e
        } catch (t: Throwable) {
            runCatching { outMp4.delete() }
            throw IOException(
                "remuxMp4s (video=${videoMime ?: "?"}, audio=${audioMime ?: "?"})" +
                    ": ${t.message ?: t.javaClass.simpleName}"
            )
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
     *  lalu tulis semua sampel ke muxer. Mengembalikan (jumlah sampel,
     *  byte sampel tertulis). [baseBytes] = byte track sebelumnya yang
     *  sudah tersalin, agar [onProgress] selalu menerima angka kumulatif
     *  gabungan. Cek batal + laporan progres berjalan berkala (tiap
     *  PROGRESS_STEP_SAMPLES sampel), bukan per sampel: di perangkat
     *  lambat loop ini menulis ratusan ribu sampel. */
    private fun writeTrackPts(
        ext: MediaExtractor,
        muxer: MediaMuxer,
        track: Int,
        baseBytes: Long,
        totalBytes: Long,
        onProgress: (copiedBytes: Long, totalBytes: Long) -> Unit,
        isCancelled: () -> Boolean
    ): Pair<Int, Long> {
        var buffer = ByteBuffer.allocate(1 shl 20)
        val info = MediaCodec.BufferInfo()
        // Penanda boolean terpisah (issue #944): sampleTime sah bisa -1,
        // jadi sentinel -1 pada firstPts menelan sampel pertama dan
        // menjadikan sampel kedua basis — menghasilkan PTS-sama.
        var written = 0
        var trackBytes = 0L
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
            trackBytes += size
            if (written % PROGRESS_STEP_SAMPLES == 0) {
                // Batal di tengah remux (diagnosis bug-99 B4): tanpa cek
                // ini muxer menulis sampai berkas tuntas walau item sudah
                // dibatalkan/dihapus, membakar CPU/disk di perangkat.
                if (isCancelled()) throw CancellationException("remux cancelled")
                onProgress(baseBytes + trackBytes, totalBytes)
            }
            if (!ext.advance()) break
        }
        onProgress(baseBytes + trackBytes, totalBytes)
        return written to trackBytes
    }

    /** Jeda sampel antara cek batal + laporan progres di loop remux. */
    private const val PROGRESS_STEP_SAMPLES = 64
}
