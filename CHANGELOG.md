# Changelog

Semua perubahan penting dicatat di sini. Format mengikuti
[Keep a Changelog](https://keepachangelog.com/id-ID/1.1.0/) dan rilis mengikuti
alur CI: `versionName` tetap `1.0`, `versionCode` = `100000 + run_number`.
APK terbaru selalu ada di [GitHub Releases](https://github.com/tasirin1/tasirin-download-manager/releases).

## [v1.0 — 2026-08-15] — Version catalog dependensi (PR #105)

### Ditambahkan
- `gradle/libs.versions.toml` — versi dependensi & plugin dipusatkan (AGP,
  androidx, nanohttpd, desugar, coroutines, test). `build.gradle.kts` dan
  `app/build.gradle.kts` memakai alias catalog; resolusi artifact tidak
  berubah (verification-metadata tetap valid).

## [v1.0 — 2026-08-15] — Guard sinkron README dwibahasa (PR #104)

### Ditambahkan
- `scripts/check_readme_sync.py` + step CI — struktur heading README.md dan
  README.en.md wajib sinkron (level & urutan), teks boleh beda karena
  terjemahan. Pre-commit hook ikut memeriksa.

## [v1.0 — 2026-08-15] — Cakupan unit test JaCoCo (PR #102)

### Ditambahkan
- **JaCoCo coverage di CI** — `jacocoTestReport` (XML + ringkasan LINE/
  BRANCH/INSTRUCTION/METHOD di job summary) dan `jacocoTestCoverageVerification`
  (ambang LINE 5%, bisa dinaikkan seiring pertumbuhan test).
- `verification-metadata.xml` diperbarui (artifact JaCoCo ikut diverifikasi).

## [v1.0 — 2026-08-15] — Security scanning CodeQL + gitleaks (PR #98)

### Ditambahkan
- **CodeQL** (Java/Kotlin) — analisis keamanan statis di push `main`, semua
  PR, dan terjadwal mingguan; hasilnya di tab Security repositori.
- **Gitleaks** — deteksi secret/token ter-commit di tiap push & PR (guard
  tambahan untuk keystore/token).

## [v1.0 — 2026-08-15] — Keandalan rilis & automasi repo (PR #97)

### Ditambahkan
- **Concurrency CI** — push/PR baru di ref yang sama membatalkan run lama
  (hemat menit Actions & antrean).
- **Cek masa berlaku keystore di CI** — error bila sertifikat < 90 hari,
  warning bila < 180 hari (tidak ketahuan mendadak saat release gagal).
- **Verifikasi tanda tangan APK hasil build** — `keytool -printcert -jarfile`
  pada APK release dibandingkan fingerprint kunci resmi `c2785a61...`.
- **`mapping.txt` (R8) dilampirkan ke release** — untuk deobfuscate stack
  trace saat crash (tidak masuk APK).
- **Dependabot grouped + auto-merge** — update dikelompokkan (`androidx`,
  `kotlinx`, `gradle-tools`, `actions`); PR yang tidak menyentuh dependensi
  Gradle di-auto-merge setelah CI hijau, PR dependensi Gradle diberi komentar
  panduan regenerasi metadata.
- **Workflow `Stale`** — PR/issue tidak aktif 30/60 hari ditandai, ditutup
  setelah 14 hari tanpa respons (label `dependencies` dikecualikan).
- **Workflow `Labeler`** — label otomatis per path (remote-web, gallery,
  download-engine, settings, ci, docs).
- **Build terjadwal mingguan** (Senin 03:00 UTC) — verifikasi build dengan
  toolchain terbaru; tidak mem-publish release.
- Guard CHANGELOG kini dikecualikan untuk PR Dependabot (bump dependensi
  dirangkum di rilis maintenance).

## [v1.0 — 2026-08-14] — Verifikasi dependensi Gradle + fix guard CHANGELOG di push main

### Diperbaiki
- **Guard CHANGELOG gagal di push `main`** — clone shallow tidak punya commit
  `before` sehingga `git diff` exit 128 dan mematikan build. Kini checkout
  memakai `fetch-depth: 0` + fallback aman (`|| true`) (PR #95).

### Ditambahkan
- **Verifikasi dependensi Gradle aktif (strict)** — `gradle/verification-
  metadata.xml` (sha256, 402 komponen: AGP, Kotlin, lint, aapt2, semua
  dependency runtime/test) di-commit ke repo; `org.gradle.dependency.
  verification=strict` di `gradle.properties` membuat CI menolak perubahan
  dependensi yang tidak punya checksum.
- **Workflow `update-deps-verification` digenerate via `build` penuh** —
  metadata sekarang mencakup artifact yang hanya muncul saat task nyata
  (aapt2 binary), bukan sekadar `help`.

## [v1.0 — 2026-08-14] — Kualitas maintenance: guard CI, keystore check, CONTRIBUTING, screenshot

### Ditambahkan
- **Guard CHANGELOG di CI** — PR yang mengubah `app/src/main`,
  `remote.src.html`, `app/build.gradle.kts`, atau `scripts/` tanpa update
  `CHANGELOG.md` langsung gagal (aturan wajib changelog kini otomatis).
- **Release notes otomatis dari CHANGELOG** — deskripsi release `v1.0` diambil
  dari entri CHANGELOG terbaru.
- **Cek kesehatan keystore di CI** — fingerprint sertifikat signing
  (`c2785a61...`) diverifikasi dari `KEYSTORE_BASE64`; mismatch = build gagal.
- **Workflow manual `update-deps-verification`** — generate
  `gradle/verification-metadata.xml` (verifikasi dependensi Gradle; diaktifkan
  di PR lanjutan).
- **`CONTRIBUTING.md`** — panduan kontribusi singkat (alur PR, bahasa,
  remote web, hook).
- **Pre-commit hook opsional** (`.githooks/pre-commit`) — `prepare_remote.py
  --check` + unit test cepat.
- **Section Screenshot di README/README.en** — folder `docs/screenshots/`
  siap diisi `remote-web.png`, `gallery.png`, `downloads.png`.

## [v1.0 — 2026-08-14] — Checksum otomatis dari header server + unit test cache galeri (PR #93)

### Ditambahkan
- **Deteksi checksum otomatis dari header HTTP** — saat server mengirim
  `Digest` (RFC 3230), `Content-MD5`, atau `X-Checksum-Sha256/Sha1/MD5`,
  checksum dipakai otomatis (tanpa isi manual) lalu diverifikasi setelah
  unduh selesai; item yang terverifikasi ditandai badge ✓ di remote web.
- **Unit test baru**: `ChecksumsTest` (parser header Digest/base64/hex) dan
  `ScanCacheTest` (kondisi cache galeri) — mem-guard dua pola bug yang
  didokumentasikan di AGENTS.md.

### Diperbaiki
- Kondisi cache galeri dipindah ke `MediaLibrary.scanCacheUsable()` (fungsi
  murni) supaya bisa diuji — perilaku sama dengan PR #92.

## [v1.0 — 2026-08-14] — Perf: cache scan galeri tidak berulang saat galeri kecil (PR #92)

### Diperbaiki
- **`GALLERY SCAN` berulang per request galeri** — kondisi cache hanya menerima
  `items.size >= limit`, jadi galeri dengan total media < limit (mis. 77 file
  vs limit 200–3000) memicu scan MediaStore penuh untuk tiap halaman dalam
  masa TTL. Cache kini terpakai juga saat scan lama tuntas
  (`items.size == total`), di `MediaLibrary.scanCached()` dan
  `scannedGallery()` — satu scan cukup per 15 detik.

### Dokumentasi
- AGENTS.md — tabel **Pola bug & guard-nya** ditambah: pool `statPool`
  mati setelah stop/start server (PR #91) dan scan galeri berulang.

## [v1.0 — 2026-08-14] — Fix: listing file manager 500 setelah restart server (PR #91)

### Diperbaiki
- **File Manager HTTP 500 setelah stop/start server** — `stopServer()` men-shutdown
  `statPool`, tapi toggle server memakai instance `HttpControlServer` yang sama,
  jadi pool tetap `Terminated` dan semua `GET /api/fs?path=<subfolder>` gagal
  dengan `RejectedExecutionException`. `liveStatPool()` kini membuat pool baru
  otomatis saat dibutuhkan.

## [v1.0 — 2026-08-13] — Pengelolaan repo untuk AI: AGENTS.md diperkuat, template PR/issue, branch protection

### Diperbaiki
- **AGENTS.md diperkuat** (PR #90):
  - Section **Keputusan & larangan historis** — daftar fitur yang sengaja
    dihapus/dilarang (auto-install, tema gelap native, bilah status,
    `/api/status`, zxing runtime, `values-en`, naikkan minSdk) supaya AI
    tidak menghidupkannya lagi.
  - Section **Pola bug & guard-nya** — tabel bug berulang + smoke test/guard
    yang melindunginya.
  - **Cara cek rilis terbaru** — pola asset APK `-v1.0-<code>.apk` dan
    perintah `gh` untuk memantau build/release.
  - **Verifikasi keystore** — fingerprint SHA-256 sertifikat signing
    (`c2785a61...`) + cara cek dengan `keytool`.
  - Aturan baru: changelog wajib per PR (sebut nomor PR), satu PR satu
    tujuan, pantau build `main` sampai rilis.
- **Template PR & Issue** — `.github/PULL_REQUEST_TEMPLATE.md`,
  `.github/ISSUE_TEMPLATE/bug_report.md`, `feature_request.md`, `config.yml`.
- **Branch protection `main`** (pengaturan GitHub) — wajib PR, wajib CI
  "Build APK" hijau, larang force-push & hapus branch.

## [v1.0 — 2026-08-13] — Audit kode: hapus payload status mati & optimasi polling web

### Diperbaiki
- **Hapus kode mati server** — endpoint `/api/status` (`statusJson`) tidak
  dipakai klien lagi sejak bilah status dihapus; dibuang bersama helper
  `storageWriteOk()` dan `batteryStatus()` (field baterai tidak pernah
  dikonsumsi klien mana pun). Payload SSE/snapshot kini hanya berisi
  `port`/`readOnly`/`appVersion`/`appBuild` — hemat JSON build & I/O
  `freeSpaceBytes()` tiap heartbeat.
- **Cache elemen DOM di jalur polling** — `#status` dan `#list` diambil sekali
  di awal (pola yang sama dengan elemen lain), tidak `getElementById` lagi di
  tiap tick snapshot (1 detik saat download aktif).
- **Hilangkan alokasi tak perlu** — `render()` tidak lagi menyalin array
  (`items.slice()`) tiap snapshot; array hasil JSON sudah baru dan tidak
  dimutasi.
- **README sinkron** — tabel endpoint di `README.md`/`README.en.md`
  menghapus `/api/status`.

## [v1.0 — 2026-08-13] — Hapus bilah status remote web

### Diperbaiki
- **Bilah status dihapus** — kartu status ("Device connected / Free storage /
  kecepatan) di halaman remote web tidak lagi ditampilkan; informasinya
  redundan dengan File Manager (kapasitas penyimpanan sudah terlihat di root
  storage). HTML, CSS, dan JS terkait (`renderStatus`, `refreshStatus`,
  `renderSpeedTotal`) dibuang — remote.html hemat ~3,9 KB.
- **Logika penting dipertahankan** — flag `serverReadOnly` (menyembunyikan
  tombol upload/select), banner "Server moved to port", dan versi server di
  footer tetap jalan lewat helper `applyServerStatus()` yang dipanggil dari
  SSE & polling snapshot.
- **Smoke test baru** — verifikasi `applyServerStatus` menetapkan flag
  read-only dan memunculkan banner pindah port.

## [v1.0 — 2026-08-13] — Perbaikan tab Downloads tidak bisa diklik dari File Manager/Galeri

### Diperbaiki
- **Tab Downloads kini bisa diklik kembali** — tombol `tabDownloads` di tab bar
  remote web tidak punya handler klik sejak fitur bottom nav diperkenalkan,
  jadi dari File Manager/Galeri tidak ada cara kembali ke halaman download
  lewat tab. Handler ditambahkan mengikuti pola tab Galeri/File.
- **Smoke test baru** — guard regresi memastikan klik tab Downloads dari
  halaman Files kembali ke route downloads.

## [v1.0 — 2026-08-13] — Audit kode: hapus fmtDate duplikat, perbaiki string UI upload

### Diperbaiki
- **Hapus `fmtDate` duplikat (kode mati)** — dua definisi `fmtDate` di
  `remote.src.html` menimpa satu sama lain (hoisting); definisi pertama tidak
  pernah dieksekusi dan dihapus. Satu implementasi tersisa, perilaku tampilan
  tidak berubah.
- **String UI upload ke Inggris** — teks "sisa ~" pada progres upload chunk
  diganti "ETA ~" (guard i18n tidak menangkap kata "sisa" sebelumnya).
- **Guard i18n diperkuat** — kata "sisa" ditambahkan ke daftar larangan
  `BANNED_ID` di `scripts/prepare_remote.py` agar tidak muncul lagi.

## [v1.0 — 2026-08-13] — Sembunyikan tombol penampil foto saat zoom

### Diperbaiki
- **Tombol penampil foto tersembunyi saat zoom aktif** — tombol atas dan
  panah kiri/kanan otomatis disembunyikan begitu foto diperbesar (tap ganda,
  pinch, atau scroll); tombol muncul kembali saat kembali ke ukuran penuh.
- **Tap tunggal saat zoom** — mengetuk foto/area kosong saat zoom aktif tidak
  lagi membolak-balik tombol; toggle chrome hanya berlaku di ukuran penuh.
- **Smoke test baru** — verifikasi `mm-chrome-hidden` ditambahkan saat zoom
  dan dihapus saat reset.

## [v1.0 — 2026-08-13] — Audit efisiensi: hoist ikon, cache baris, JSON FS ringan, pencarian log tanpa alokasi

### Diperbaiki
- **Hoist daftar ekstensi ikon** (remote web) — array ekstensi video/gambar/
  audio/arsip/dokumen dipindah ke konstanta `FILE_ICON_*`/`FS_ICON_*` sehingga
  tidak dialokasikan ulang di tiap pemanggilan ikon saat render baris
  (daftar download, file manager, galeri).
- **Cache referensi baris** — elemen `.item-sub` disimpan di baris saat
  dirender; pembaruan progres tiap polling tidak perlu `querySelector`
  per baris lagi.
- **JSON halaman file media ringan** (`fsListMedia`) — entri file disimpan
  sebagai data ringan dulu; `JSONObject` + token Base64 baru dibuat untuk
  halaman aktif (hemat alokasi saat folder berisi ribuan file).
- **Pencarian log tanpa alokasi** — `highlightLog` memakai
  `indexOf(ignoreCase = true)` alih-alih membuat salinan `lowercase()` per
  baris.
- **Fix i18n** — teks "terpakai" pada kapasitas root File Manager diganti
  "used" (guard kata Indonesia tidak menangkap kata ini sebelumnya).

## [v1.0 — 2026-08-13] — Perbaikan pan penampil foto saat zoom

### Diperbaiki
- **Geser foto saat zoom** — gambar yang diperbesar kini bisa digeser untuk
  menjelajah area tertentu; sebelumnya `mmImgClamp()` selalu memaksa posisi
  ke tengah sehingga pan (sentuh, mouse, dan pinch) langsung dibatalkan.
  Tepi gambar tetap dikunci agar tidak lepas dari layar.
- **Smoke test pan/zoom** — `upload_smoke_test.js` kini memverifikasi bahwa
  clamp tidak mengembalikan gambar ke tengah saat digeser dan tepi tetap
  terkunci (guard CI).

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
