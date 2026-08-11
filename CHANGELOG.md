# Changelog

Semua perubahan penting dicatat di sini. Format mengikuti
[Keep a Changelog](https://keepachangelog.com/id-ID/1.1.0/) dan rilis mengikuti
alur CI: `versionName` tetap `1.0`, `versionCode` = `100000 + run_number`.
APK terbaru selalu ada di [GitHub Releases](https://github.com/tasirin1/tasirin-download-manager/releases).

## [v1.0-build100352] — 2026-08-11

### Ditambahkan
- **Guard CI remote web**: `scripts/prepare_remote.py --check` memverifikasi
  sinkron `remote.src.html` ↔ `remote.html`, `node --check` semua `<script>`,
  dan larangan kata Indonesia di string UI (remote.html, values XML, Kotlin).
- **Sumber readable** `remote.src.html` + minifier otomatis ke `assets/remote.html`
  (152,6 KB → 147,4 KB).
- **`ServerSecurity.kt`** + 13 unit test: anti path-traversal, lock PIN,
  validasi offset upload, expiry token share.
- Ringkasan crash satu baris masuk log server realtime; crash log (launch
  sebelumnya) ikut diekspor di TXT log.
- **Auto-cleanup saat storage menipis** (< 512 MB): partial file orphan,
  thumbnail lama, dan sisa upload chunk dibersihkan otomatis sebelum download.
- Banner **"Browser too old"** di remote web untuk browser lama (Chrome < 49).
- `CHANGELOG.md` ini.

### Diubah
- `localeFilters` hanya `"en"` (UI Inggris; `id` sudah tidak ada).
- **Fase 3 peta jalan selesai**: uji manual di perangkat Android 15/16 berhasil
  (auto-start boot, download background, server remote, galeri). `targetSdk 37`
  sengaja belum dinaikkan.

## [v1.0-build100349] — 2026-08-10 — Bahasa Inggris penuh

### Ditambahkan
- `README.en.md` + pemilih bahasa Indonesia/English di `README.md` &
  `README.en.md`.
- Baris About baru: "Language: English (app & remote web)" dan
  "Package: full-featured, only ~1.7 MB".

### Diubah
- Seluruh string UI aplikasi (`values/`) dan halaman remote web
  (`remote.html`) menjadi **Bahasa Inggris** (folder `values-en` dihapus).
- Pesan log server & error user-visible di engine dan server memakai Inggris.
- `AGENTS.md` disinkronkan: UI = Inggris, komentar/commit tetap Indonesia.

## [v1.0 — 2026-08-10] — Perbaikan batch 3

### Ditambahkan
- Cache QR, `scaleDown` tunggal (BitmapUtil), `onTrimMemory`.
- PIN dicocokkan constant-time (anti timing attack).
- `HttpControlServer` dipecah (MediaStream, ServerStreams, ShareToken, SseStream).
- Test baru: ServerLog, readBounded, codec null-entry, sha256Hex, PIN normalize.

### Diperbaiki
- Lint KTX (scale, createBitmap, set, isVisible); setPixels framework untuk QR.
- Dependensi di-update bertahap via CI (Gradle 9.7.0).

## [v1.0 — 2026-08-09] — Perbaikan batch 2

### Ditambahkan
- Helper `setupSpinner` untuk semua Spinner; tombol bulk via view binding.
- Galeri memakai DiffUtil + paginasi scan bertahap (hemat memori Android 5+).
- PIN disimpan sebagai hash SHA-256 (tanpa plaintext di disk).
- Encoder QR mandiri tanpa zxing di runtime (decode tetap diverifikasi di test).
- Tombol jeda/lanjut semua di notifikasi.

## [v1.0 — 2026-08-09] — Desain UI remote & pemutar video

### Ditambahkan
- Desain ulang file manager: ikon tipe, sticky bar, batch action, detail upload,
  info root.
- Galeri remote & pemutar video ala YouTube: lightbox, multi-select, kontrol
  ramping, top bar back, navigasi foto/video.
- Penampil foto ala galeri; tombol kembali Android menutup media.

## [v1.0 — 2026-08-05] — Efisiensi build & keamanan rilis

### Ditambahkan
- Efisiensi build & ukuran APK (dependensi, locale filter, packaging, caching).
- Workflow VirusTotal: submit APK rilis + polling hasil + ringkasan deteksi di log.
- Helper `Permissions` untuk izin runtime; sentralisasi crash log.

## [v1.0 — 2026-08-03 s/d 08-05] — Rilis awal & fitur inti

### Ditambahkan
- Download manager Android 5+ (API 21): multi-URL, antrean paralel, multi-segmen
  via HTTP Range, retry backoff + mirror, batas kecepatan & prioritas per item,
  HLS/m3u8, verifikasi ukuran & checksum, auto-sort subfolder.
- Remote web via HTTP server (port default 8080): tambah/pause/resume/hapus
  download, upload (chunk 2 MB), file manager (browse/rename/move/delete/ZIP),
  galeri foto/video + thumbnail, streaming dengan Range, SSE realtime + fallback
  polling, PIN dengan auto-lock, QR akses.
- Foreground service + auto resume saat koneksi pulih + auto-start saat boot
  (JobScheduler untuk targetSdk 35+).
- Penyimpanan: folder kustom (SAF / path teks), MediaStore, auto-sort
  (`Videos/`, `Photos/`, `Music/`, `Documents/`, `APK/`).
- Galeri lokal: scan MediaStore (kondisional API 29+), thumbnail 16:9, tema
  gelap, pemutar video ala YouTube.
- Self-update APK dari GitHub Release + verifikasi tanda tangan (SHA-256
  sertifikat); ekspor log; dialog crash sebelumnya.
- Unit test JVM (Formats, FileNames, MimeTypes, DownloadItem, QrEncoder,
  MediaStream, PinUtil, ServerSecurity, dll).

## Format

Setiap rilis = push ke `main` → workflow `build.yml` → release `v1.0`
di-refresh berisi APK `tasirin-download-manager-v1.0-<code>.apk`.
Detail teknis untuk AI/maintainer ada di `AGENTS.md`.
