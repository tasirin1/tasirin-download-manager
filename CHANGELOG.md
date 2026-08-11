# Changelog

Semua perubahan penting dicatat di sini. Format mengikuti
[Keep a Changelog](https://keepachangelog.com/id-ID/1.1.0/) dan rilis mengikuti
alur CI: `versionName` tetap `1.0`, `versionCode` = `100000 + run_number`.
APK terbaru selalu ada di [GitHub Releases](https://github.com/tasirin1/tasirin-download-manager/releases).

## [v1.0 — 2026-08-11] — Play Protect: kurangi sinyal berbahaya + server read-only

### Diperbaiki
- **Izin &quot;install aplikasi lain&quot; (`REQUEST_INSTALL_PACKAGES`) dihapus** —
  update APK kini hanya mengunduh ke folder Downloads (tanda tangan release
  diverifikasi) lalu dipasang manual dari file manager. Izin ini termasuk
  pemicu utama peringatan Play Protect untuk aplikasi sideload.
- **Mode server read-only** (Pengaturan → Server): upload, ubah nama, pindah,
  hapus, dan buat folder dari remote web ditolak di sisi server; UI remote
  menyembunyikan tombol aksi dan menampilkan banner. Download & browsing tetap
  berfungsi.
- **Penjelasan peringatan Play Protect** ditambahkan di README (ID/EN) dan
  dialog About: peringatan &quot;membahayakan data&quot; adalah normal untuk
  aplikasi sideload dengan &quot;All Files Access&quot; (bukan malware; scan
  VirusTotal 0/75 untuk semua rilis terakhir).
- **Klarifikasi izin storage** di Pengaturan: &quot;All Files Access&quot; hanya
  diperlukan File Manager remote &amp; path langsung — download & galeri tetap
  berfungsi via MediaStore tanpanya.

## [v1.0 — 2026-08-11] — Upload macet: akar masalah ditemukan & diperbaiki

### Diperbaiki
- **Upload file kini benar-benar berfungsi**: pemanggilan `uploadFiles()` di
  `startFsUpload` menukar argumen `done` dan `listEl` — elemen daftar progres
  dikirim sebagai `done` dan fungsi callback dikirim sebagai `listEl`, sehingga
  `listEl.appendChild` melempar "listEl.appendChild is not a function" dan
  upload gagal diam-diam di semua perangkat/browser (terkonfirmasi dari log
  server: tidak ada `POST /api/upload` yang sampai). Urutan argumen diperbaiki
  (callback = `done`, `fsProgressList` = `listEl`).
- **Kegagalan upload tidak lagi menggantung tanpa pesan**: seluruh alur upload
  dibungkus pengaman — error tampil sebagai `Failed: <file> — <pesan>` dan
  tombol Upload tidak pernah terkunci selamanya.
- **Fallback `Blob.slice` untuk browser tua**: `webkitSlice`/`mozSlice`
  dicoba bila `slice` tidak tersedia, dan bila sama sekali tidak didukung
  muncul pesan error yang jelas.
- **Guard regresi**: `scripts/prepare_remote.py --check` kini memverifikasi
  urutan argumen `uploadFiles()` dan menjalankan smoke test alur upload klien
  (`scripts/upload_smoke_test.js`, stub DOM/XHR tanpa dependensi) — bug
  semacam ini tidak bisa lolos CI lagi.

## [v1.0 — 2026-08-11] — Perbaikan polling, SSE, & upload (temuan dari log perangkat nyata)

### Diperbaiki
- **Polling remote tidak lagi membombardir server**: interval cepat 700 ms
  diubah ke 2 detik dan hanya aktif saat ada transfer yang benar-benar
  berjalan (data berubah); tanpa perubahan selama 15 detik polling kembali
  ke 10 detik. Sebelumnya TV box memicu `GET /api/snapshot` ~1,4x/detik
  terus-menerus (terlihat di log server: ratusan baris polling).
- **SSE mati otomatis di WebView/browser tua**: EventSource "terbuka" tapi
  `onmessage` tidak pernah datang (buffer WebView lama); sebelumnya koneksi
  dipaksa reconnect tiap ~10 detik tanpa henti. Sekarang: 1 percobaan
  reconnect, bila masih diam SSE dimatikan untuk sesi itu dan polling
  adaptif mengambil alih.
- **Log server tidak dibanjiri polling**: `GET /api/snapshot`, `/api/events`,
  dan `/api/pin_enabled` (status 200) tidak lagi dicatat — buffer 300 baris
  terisi kejadian penting (download, upload, aksi) saja; request gagal tetap
  dicatat.
- **File Manager tidak memanggil `/api/fs?path=` berulang** saat buka halaman
  root (3-4x menjadi 1-2x).
- **Upload folder di browser tanpa `webkitdirectory`**: kini muncul peringatan
  jelas bahwa struktur folder tidak dipertahankan (sebelumnya diam-diam
  berubah jadi upload file flat ke folder aktif).
- **Upload file 0 byte** ditolak di sisi klien dengan pesan jelas
  ("empty file (0 bytes)") alih-alih gagal diam-diam.
- **`fsUploading` tidak macet** bila seleksi upload kosong.

## [v1.0 — 2026-08-11] — Audit lanjutan (detail)

### Diperbaiki
- Pakai `RANGE_RE` yang sudah di-hoist di `parseRange` (sebelumnya masih
  membuat `Regex` baru per permintaan Range — konstanta tidak terpakai).
- Reuse `URL_SPLIT` di `MainActivity` saat mengekstrak URL dari teks share;
  sekaligus membuang koma yang menempel di akhir URL ("url,url" terpisah
  benar, bukan jadi satu URL rusak).
- Hoist regex `.part` ke companion object di `SettingsActivity` (tidak
  dikompilasi ulang tiap pembersihan).
- Rapikan KDoc `restartHttpServer` yang terpisah dari fungsinya di `App.kt`.
- `.gitignore`: abaikan `__pycache__/` hasil `prepare_remote.py`.

## [v1.0 — 2026-08-11] — Audit kode & efisiensi

### Diperbaiki
- Buang kode mati: `stopServiceIfIdle` di `MainActivity` (tidak pernah dipanggil).
- Hoist `Regex` yang dikompilasi berulang: parser HLS (per baris playlist),
  `parseRange` streaming (per permintaan Range), nama file dari header
  Content-Disposition (per download), nama asset APK updater, dan pemisah
  whitespace checksum.
- Ganti `split("\n".toRegex())` dengan `split('\n')` (tanpa kompilasi regex).
- Perbaiki string Indonesia yang lolos guard: `'Mengunggah '` → `'Uploading '`
  di remote web.
- Guard i18n CI makin ketat: mendeteksi kata berimbuhan Indonesia
  (meN-/di-/ter-/ber- + kata dasar, mis. "Mengunggah") tanpa false positive.

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
