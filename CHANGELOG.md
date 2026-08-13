# Changelog

Semua perubahan penting dicatat di sini. Format mengikuti
[Keep a Changelog](https://keepachangelog.com/id-ID/1.1.0/) dan rilis mengikuti
alur CI: `versionName` tetap `1.0`, `versionCode` = `100000 + run_number`.
APK terbaru selalu ada di [GitHub Releases](https://github.com/tasirin1/tasirin-download-manager/releases).

## [v1.0 — 2026-08-13] — Tampilan utama remote web: bottom nav, hero status, ikon state, persen di bar, tanggal selesai & retry

### Diperbaiki
- **Bottom navigation** — akses Gallery & File Manager pindah dari pill atas ke
  bar navigasi tetap di bawah (Downloads/Gallery/Files) ber-ikon + label,
  dengan badge jumlah download aktif di tab Downloads; ramah jempol dan D-pad.
- **Hero status card** — kartu status perangkat menyatu dengan free storage dan
  kecepatan aktif (⚡ total speed · N active) yang diperbarui live; pill
  kecepatan di topbar dihapus.
- **Ikon state berwarna** — ikon file di kiri item diberi warna sesuai state:
  hijau selesai, merah gagal/dibatalkan, amber jeda/antre, biru aktif.
- **Persen di ujung progress bar** — persentase tampil di sisi kanan bar;
  bar penuh berwarna hijau saat selesai.
- **Tanggal selesai** — item COMPLETED menampilkan ukuran + waktu selesai
  (field `finishedAt` baru di model, codec, engine, dan API download).
- **Tombol Retry di kartu gagal** — item FAILED kini punya tombol Retry
  langsung di bawah pesan error.

## [v1.0 — 2026-08-13] — Audit efisiensi: cache row FS, sort tanpa lowercase, hoist format/escape, wakelock guard

### Diperbaiki
- **Cache baris File Manager** — `fsFindFsRow()` kini memakai cache
  `fsRowCache` sehingga pencarian baris oleh pembaruan langsung (snapshot)
  tidak perlu memindai seluruh daftar setiap kali; cache di-reset saat
  render/skeleton dan diisi saat baris ditambahkan.
- **Sort tanpa alokasi `lowercase()`** — daftar download (nama, status) dan
  pencarian galeri/URL terbaru kini memakai `CASE_INSENSITIVE_ORDER` /
  `indexOf(ignoreCase = true)` alih-alih membuat string baru untuk tiap item.
- **`fmtEta` memakai satuan "h"** — singkatan jam konsisten dengan satuan
  lain (m/s) dan tidak lagi memakai "j".
- **Hoist `escapeHtml`** — tabel entitas HTML dipindah ke konstanta `HTML_ENT`
  sehingga tidak dibuat ulang di tiap panggilan escape.
- **Hoist `SimpleDateFormat` ekspor log** — formatter waktu ekspor TXT dibuat
  sekali di `LogActivity` alih-alih tiap kali ekspor.
- **Guard wakelock** — `acquire()` hanya dipanggil bila wakelock belum
  di-hold, mencegah acquire bertumpuk saat status download berubah.

## [v1.0 — 2026-08-13] — Penampil foto: zoom/pan, swipe tutup, aksi, preload, slideshow, dimensi

### Ditambahkan
- **Pinch zoom + pan** — perbesar dengan dua jari (atau roda mouse di
  desktop) dan geser untuk menjelajah foto yang diperbesar.
- **Double-tap zoom ke titik sentuh** — perbesar tepat di lokasi yang
  disentuh; double-tap lagi untuk zoom-out.
- **Swipe ke bawah untuk menutup penampil** — saat tidak diperbesar, geser
  ke bawah kembali ke galeri.
- **Tombol aksi di penampil** — Download (⬇), Delete (🗑), dan Slideshow (▶)
  di bar atas; hapus foto langsung dari penampil tanpa keluar dulu.
- **Preload foto tetangga** — foto sebelum/sesudah dimuat diam-diam supaya
  pindah foto tidak blank.
- **Spinner loading + pesan error** — indikator saat foto dimuat dan toast
  bila gagal.
- **Slideshow otomatis** — putar otomatis tiap 3,5 detik, berhenti saat
  disentuh/di-close.
- **Info dimensi foto (W×H)** — ukuran piksel tampil di bar atas penampil.
- **Smoke test math zoom** — verifikasi titik sentuh dipertahankan saat
  zoom-in/out (guard CI).

## [v1.0 — 2026-08-12] — Scroll posisi File Manager, indikator volume/brightness ala YouTube, smoke test navigasi FS

### Ditambahkan
- **Posisi scroll folder dipertahankan** — kembali naik folder (Back/Up)
  mengembalikan posisi scroll folder sebelumnya; tiap folder menyimpan
  posisinya sendiri selama sesi.
- **Indikator volume & brightness saat swipe vertikal** — pemutar video kini
  menampilkan indikator ala YouTube (ikon + persen + bar vertikal) saat
  menggeser sisi kiri layar untuk brightness dan sisi kanan untuk volume.
- **Smoke test navigasi File Manager** — `upload_smoke_test.js` kini juga
  memverifikasi `parentFsPath`, breadcrumb (`fsCrumbParts`/`collapseCrumbs`),
  back-stack + Back, tombol Up/Home, dan badge NEW; mencegah regresi di CI.

## [v1.0 — 2026-08-12] — File Manager: Back naik folder, lokasi tersimpan, progress upload inline, badge NEW

### Ditambahkan
- **Tombol Back = naik folder** — browser/Android back (dan panah kiri TV)
  menaikkan satu level folder via history stack; di root, back kembali ke
  tab sebelumnya.
- **Lokasi terakhir disimpan** (`localStorage`) — buka remote kembali
  langsung kembali ke folder terakhir; tombol **Home** untuk balik ke root
  storage. Bila folder tersimpan tidak lagi ada, otomatis fallback ke root.
- **Progress upload inline di baris file** — file yang sedang di-upload ke
  folder aktif menampilkan progress bar tipis di barisnya secara realtime.
- **Badge "NEW"** — file yang baru di-upload ditandai badge kecil selama
  sesi; tetap tampil setelah daftar di-refresh.

## [v1.0 — 2026-08-12] — Polling realtime saat transfer aktif, tombol Search selebar baris

### Diubah
- **Polling adaptif lebih realtime** — interval cepat 2 detik → **1 detik**
  selama ada transfer aktif (download/upload), tanpa syarat "data berubah"
  lagi; idle tetap 10 detik. SSE tetap sumber utama; polling hanya pengaman.
  Blok penanda perubahan yang tidak terpakai ikut dihapus.
- **Tombol Search File Manager selebar baris** — tidak lagi menyisakan
  kolom sempit saat membungkus di layar sempit; baris input pencarian tetap
  muncul di bawahnya saat tombol ditekan.

## [v1.0 — 2026-08-12] — Status bar kontras, pencarian File Manager, scan VirusTotal di PR

### Ditambahkan
- **Pencarian File Manager di remote web** — tombol kecil "Search" di toolbar
  membuka satu baris input; filter berjalan di sisi klien (hanya baris yang
  sudah dimuat), tanpa beban RAM/endpoint server.
- **VirusTotal ikut di-scan pada PR** — tidak hanya push `main`; APK PR ikut
  dicek sebelum merge (butuh `VT_API_KEY`).

### Diperbaiki
- **Ikon status/navigation bar paksa gelap** (`SystemBarStyle.light`) — app
  selalu tema terang, jadi ikon tidak lagi berubah putih saat mode gelap
  sistem aktif (`EdgeToEdge.kt`).

### Catatan audit
- Desugaring: hanya `Iterable.forEach` sintetis yang terpakai (tanpa
  `java.time`/`stream`/`Optional`); sisanya sudah dipangkas R8 — tidak ada
  yang bisa dihemat lebih lanjut. Audit kode mati: tidak ditemukan
  fungsi/properti tak terpakai.

## [v1.0 — 2026-08-12] — Perbaikan tema: benar-benar terang (Light) bukan gelap

### Diperbaiki
- **Tema masih gelap setelah PR #73** — `Theme.AppCompat.NoActionBar` adalah
  varian gelap, jadi latar hitam + kartu putih + teks putih nyaris tak
  terbaca. Ganti ke `Theme.AppCompat.Light.NoActionBar`; `TvOutlinedButton`
  diberi teks `@color/primary` agar senada desain lama.

## [v1.0 — 2026-08-12] — Perbaikan: latar biru di tampilan utama setelah hapus Material

### Diperbaiki
- **Latar biru splash menutupi semua halaman** — tema aplikasi sebelumnya
  mewarisi `Theme.HttpDownloadManager.Splash` (windowBackground biru) dan
  tanpa `installSplashScreen()` tidak ada yang menukar ke tema terang,
  sehingga teks gelap nyaris tak terlihat. Sekarang: aplikasi default
  `Theme.HttpDownloadManager` (terang), splash hanya di `MainActivity`
  via `android:theme` manifest + `setTheme()` klasik sebelum konten digambar.

## [v1.0 — 2026-08-12] — APK lebih kecil: tanpa Material, splashscreen klasik, R8 agresif

### Diubah
- **Library Material (1.12.0) dihapus** — MaterialToolbar → Toolbar AppCompat,
  MaterialButton → Button, MaterialCardView → FrameLayout + drawable rounded,
  MaterialAlertDialogBuilder → AlertDialog, Snackbar → Toast. Tema beralih ke
  `Theme.AppCompat.NoActionBar` (satu tema terang; `values-night` dihapus).
  Ukuran APK turun beberapa ratus KB tanpa menghapus fitur.
- **core-splashscreen dihapus** — splash klasik via `windowBackground` tema
  (kompat Android 5+), perilaku sama tanpa library tambahan.
- **R8 lebih agresif** — `-allowaccessmodification`, `-optimizationpasses 5`,
  dan `-repackageclasses ''` untuk memperkecil dex.
- **`remote.html` lebih ringkas** — `prepare_remote.py` kini juga membuang
  indentasi, baris kosong, dan komentar `//` pada blok JS (sumber readable
  tetap `remote.src.html`); guard CI (sync + `node --check` + smoke upload)
  tetap hijau.
- **Ikon launcher round dihapus** — cukup satu set ikon launcher biasa.

## [v1.0 — 2026-08-12] — Hemat RAM: pagination file manager, cache galeri terbatas, cleanup thumbnail terjadwal

### Diperbaiki
- **File Manager remote di-paginate (1000 entri/request + tombol "Load more")** —
  folder raksasa tidak lagi membangun JSON semua entri + statistik semua
  subfolder sekaligus di memori server.
- **Cache galeri dibatasi halaman aktif + 1 buffer** — scan tidak lagi menahan
  sampai 3000 entri di memori saat browsing biasa (total tetap akurat untuk
  `hasMore`; saat ada filter/q, scan penuh dipakai supaya hasil pencarian akurat).
- **Pembersihan thumbnail cache maksimal 1x per 7 hari** — tidak lagi memindai
  folder thumb setiap kali aplikasi start.

## [v1.0 — 2026-08-11] — Remote web: tombol Select sejajar Upload di File Manager

### Diperbaiki
- **Tombol Select File Manager kini sejajar dengan tombol Upload** — di layar
  sempit (≤600px) Select tidak lagi turun ke baris sendiri selebar penuh; grid
  toolbar dirapikan dari 5 ke 4 kolom (hilangkan kolom kosong di kanan).

## [v1.0 — 2026-08-11] — Pengaturan: kurangi jumlah view (hilangkan warning lint)

### Diperbaiki
- **Layout `activity_settings.xml` turun dari 81 ke 66 view** — warning lint
  `TooManyViews` ("more than 80 views, bad for performance") hilang. Header
  section collapsible tidak lagi memakai `LinearLayout` + `TextView` +
  `ImageView` (cukup satu `TextView` dengan chevron `drawableEnd`, swap
  `ic_chevron`/`ic_chevron_up`); wrapper `content_*` per section dihapus —
  kontrol langsung menempel di section, padding dipindah ke card. Inflasi
  dan memori halaman Pengaturan lebih hemat.

## [v1.0 — 2026-08-11] — Audit efisiensi: cache galeri, throttle, R8

### Diperbaiki
- **Cache durasi video & dimensi gambar galeri remote di memori** — request
  halaman galeri tidak lagi membaca `video_durations.json` + header gambar dari
  disk berulang kali (hemat I/O saat scroll/search/pagination).
- **Throttle progres download jadi 1x/detik** (sebelumnya 2x/detik) — salinan
  daftar & emisi StateFlow ke UI/notifikasi/SSE dikurangi saat banyak download
  paralel.
- **SSE ticker status diperlambat jadi 10 dtk** (sebelumnya 3 dtk) — client
  yang butuh data segar memakai `/api/snapshot`.
- **Statistik subfolder dihitung paralel** di File Manager remote — listing
  folder besar tidak lagi menunggu N `listFiles()` berurutan di storage lambat.
- **Remote web**: cache node daftar download per id (tanpa `querySelectorAll`
  tiap poll), cache posisi video per render galeri (baca `localStorage`
  maksimal sekali per token).
- **GalleryActivity**: formatter tanggal di-cache per-locale (tidak dibuat
  per bind).
- **R8**: flag `-mergeinterfacesaggressively` — APK sedikit lebih kecil.

## [v1.0 — 2026-08-11] — Download batch mode select

### Ditambahkan
- **Tombol Download di mode select File Manager remote**: pilih beberapa file
  dan/atau folder sekaligus, lalu unduh sekali sebagai ZIP (folder di-zip
  rekursif; endpoint `/api/media_zip` kini menerima `paths`).

## [v1.0 — 2026-08-11] — Remote web: hapus tombol unduh cepat per baris

### Diperbaiki
- **Tombol ⬇ Download di tiap baris File Manager remote dihapus** — rawan
  tertekan tidak sengaja (apalagi saat dikontrol dari remote TV / D-pad).
  Aksi download tetap tersedia lewat menu **⋯** pada baris yang sama
  (Stream / Download / Download folder ZIP).

## [v1.0 — 2026-08-11] — Audit efisiensi lanjutan

### Diperbaiki
- **Sortir daftar file tanpa alokasi string per entri**: pemanggilan
  `lowercase()` per nama (membuat string baru untuk tiap file tiap kali
  direktori di-list) diganti comparator `compareTo(ignoreCase = true)` di
  File Manager remote, browsing media, pencarian duplikat, dan pembuatan ZIP.
- **Update progres upload lebih ringan**: baris status upload di remote web
  di-cache — sebelumnya `getElementById` + `querySelector` dipanggil ulang
  untuk tiap event progress (bisa puluhan kali per detik di browser TV tua).
- **Empty-folder CTA di remote web** tidak lagi menampilkan tombol
  Upload/New Folder saat server dalam mode read-only (konsisten dengan mode
  read-only yang baru).

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
