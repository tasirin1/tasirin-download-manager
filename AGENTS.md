# Panduan pengelolaan repo (untuk AI)

Baca file ini SEBELUM mengubah, memperbaiki, atau mengelola repository ini.
Panduan lengkap yang lain (fitur, cara pakai, troubleshooting) ada di
`README.md` (Indonesia) dan `README.en.md` (Inggris) — jaga keduanya sinkron.

## Struktur repository

```
.
├── .github/workflows/build.yml       # CI: guard CHANGELOG → cek remote web → bump versionCode → build APK → release
├── .github/workflows/update-deps-verification.yml  # (manual) generate gradle/verification-metadata.xml
├── .github/workflows/auto-merge.yml  # Auto-merge PR Dependabot yang aman (non-Gradle)
├── .github/workflows/codeql.yml       # CodeQL: analisis keamanan statis Java/Kotlin (push, PR, mingguan)
├── .github/workflows/gitleaks.yml     # Deteksi secret ter-commit (push & PR)
├── .github/workflows/stale.yml       # Tutup PR/issue tidak aktif (mingguan)
├── .github/workflows/labeler.yml     # Label otomatis per path (config: .github/labeler.yml)
├── .github/dependabot.yml            # Update dependensi terjadwal (grouped, auto-merge aman)
├── .github/labeler.yml               # Mapping path → label untuk labeler.yml
├── .github/PULL_REQUEST_TEMPLATE.md  # Template PR (wajib ringkasan + verifikasi)
├── .github/ISSUE_TEMPLATE/           # Template issue (bug report & feature request)
├── CONTRIBUTING.md                   # Panduan kontribusi singkat (baca juga AGENTS.md)
├── .githooks/pre-commit              # Hook opsional: prepare_remote --check + unit test cepat
├── gradle/libs.versions.toml          # Version catalog — pusat versi dependensi & plugin
├── app/build.gradle.kts              # minSdk 21 / targetSdk 36, compileSdk 36, desugaring, R8
├── CHANGELOG.md                       # Riwayat perubahan per rilis (update manual)
├── docs/screenshots/                 # Screenshot README (remote-web.png, gallery.png, downloads.png)
├── remote.src.html                   # SUMBER readable remote web (SELURUH halaman)
├── scripts/prepare_remote.py         # Minify remote.src.html → assets/remote.html + guard CI
├── scripts/check_readme_sync.py      # Guard CI: struktur heading README.md vs README.en.md sinkron
├── scripts/upload_smoke_test.js      # Smoke test alur upload (stub DOM/XHR, tanpa dependensi)
├── app/src/main/
│   ├── AndroidManifest.xml           # permission & komponen (service, receiver, provider)
│   ├── assets/
│   │   └── remote.html               # Remote web minified (digenapi dari remote.src.html)
│   ├── res/raw/                      # trust anchor TLS (digicert_global_root_g2.pem, isrg_root_x1.pem)
│   └── java/com/tasirin/httpdownloadmanager/
│       ├── App.kt                    # Application — inisialisasi engine download
│       ├── MainActivity.kt           # UI utama: daftar download, dialog tambah URL, About
│       ├── GalleryActivity.kt        # Galeri video perangkat (video-only)
│       ├── SettingsActivity.kt       # Pengaturan lengkap + self-update APK
│       ├── LogActivity.kt            # Log server realtime + ekspor TXT
│       ├── data/
│       │   ├── DownloadItem.kt       # Model + state download
│       │   └── DownloadRepository.kt # Persistensi daftar download
│       ├── download/
│       │   ├── DownloadEngine.kt     # Inti unduhan: Range, multi-segmen, HLS, retry, mirror
│       │   └── DownloadService.kt    # Foreground service + notifikasi + lanjut otomatis
│       ├── receiver/BootReceiver.kt  # Auto-start saat boot (download & server)
│       ├── remote/HttpControlServer.kt # Server HTTP remote (nanohttpd) + endpoint API
│       ├── remote/MediaStream.kt     # Streaming + HTTP Range + notFound (helper respons)
│       ├── remote/ServerSecurity.kt  # Logika keamanan murni (path, PIN lock, upload, share)
│       ├── remote/ServerStreams.kt   # Stream gabungan partial, upload stream, delete-on-close
│       ├── remote/ShareToken.kt      # Token berbagi file sementara
│       ├── remote/QrCode.kt          # QR PNG untuk /api/qr (pakai util/QrEncoder)
│       ├── ui/DownloadAdapter.kt     # RecyclerView adapter daftar download
│       └── util/
│           ├── Updater.kt            # Cek & unduh APK update (tanpa auto-install) + verifikasi tanda tangan
│           ├── FileSaver.kt          # Simpan file (MediaStore / folder, auto-sort)
│           ├── MediaLibrary.kt       # Scan video + thumbnail (kondisional API 29+)
│           ├── StoragePrefs.kt       # Semua kunci SharedPreferences ("storage_settings")
│           ├── StorageCleanup.kt     # Auto-cleanup saat storage menipis (partial, thumbs, upload tmp)
│           ├── QrEncoder.kt          # Encoder QR mandiri (tanpa zxing di APK)
│           ├── BitmapUtil.kt         # scaleDown bersama (galeri + server remote)
│           ├── Spinners.kt, Streams.kt  # Helper spinner + baca stream terbatas
│           ├── MimeTypes.kt, Crypto.kt, Formats.kt, FileNames.kt,
│           ├── NotificationHelper.kt, TlsCompat.kt            # Pendukung
├── app/src/test/                     # Unit test JVM (junit4): download queue/HLS/resume/speed,
│                                     # item codec, formats/names/mime/hex/pin/QR,
│                                     # checksums, streams, scan cache, server log/security/stream
├── gradle/verification-metadata.xml  # Checksum sha256 semua dependensi (verifikasi strict di CI)
└── gradle wrapper                    # build via ./gradlew (CI saja untuk rilis)
```

Catatan: `widget/SpeedChartView.kt` tidak ada lagi. Kecepatan ditampilkan sebagai teks pada item aktif/upload; jangan tambahkan grafik tanpa kebutuhan nyata.

## Arsitektur ringkas

- **MainActivity** menampilkan daftar download (adapter) dan mengarahkan aksi ke
  `DownloadEngine`; halaman **remote web** adalah UI utama pemakaian harian
  (`remote.html` satu file, dipakai server langsung dari `assets`).
- **DownloadEngine** (inti): tiap item = state (antre/menunggu/aktif/jeda/gagal/
  selesai) + monitor kecepatan & ETA; multi-segmen via HTTP Range; fallback
  single-stream saat Range ditolak; HLS/m3u8 probe; mirror & blacklist URL.
- **DownloadService** menjalankan engine di *foreground service*; `NetworkCallback`
  (Android 7+) / broadcast (5–6) untuk melanjutkan download saat koneksi pulih.
- **HttpControlServer** (nanohttpd, port default `8080`): endpoint JSON + SSE
  (`/api/events`), login PIN per-IP dengan throttle/lock, upload chunk 2 MB yang
  wajib punya ID dan diserialisasi per-ID, ZIP folder, streaming Range, galeri
  video-only, thumbnail, serta stream parsial bertoken.
- **MediaLibrary** memindai **video saja** dari MediaStore/file (kolom `DURATION`
  bila tersedia; `RELATIVE_PATH` hanya API ≥ 29), TTL 15 detik, thumbnail 16:9
  di-cache, dan akses cache scan dilindungi lock agar tidak scan paralel duplikat.
- **Updater** membaca release GitHub, memilih asset APK dengan kode tertinggi,
  memverifikasi SHA-256 sertifikat release, lalu **hanya mengunduh APK**. Instalasi
  tetap manual oleh pengguna.
- **Kunci SharedPreferences aktif** (`storage_settings`): `folder_uri`, `folder_name`,
  `text_folder_path`, `extra_folders`, `background_download`, `auto_start_boot`,
  `server_background`, `server_autostart_boot`, `server_port`, `server_pin`,
  `pin_enforced`, `fs_full_access`, `server_read_only`, `max_concurrent`, `segments`,
  `speed_limit_kbps`, `max_retries`, `connect_timeout_sec`, `read_timeout_sec`,
  `small_first`, `delete_partial_on_cancel`, `recent_urls`, `sort_mode`,
  `auto_sort`, `battery_exempt`, `collapsed_sections`, `thumb_cleanup_last`.
  Kunci galeri foto/video terpisah sudah tidak dipakai; scanner galeri sekarang video-only.

## Keputusan & larangan historis

Hal berikut sengaja dihapus/dilarang — JANGAN dihidupkan kembali tanpa alasan
kuat dan tanpa diskusi:

- **Auto-install APK** (`REQUEST_INSTALL_PACKAGES`) — dihapus; `Updater.kt`
  download-only + verifikasi tanda tangan (kurangi sinyal berbahaya Play Protect).
- **Tema gelap native** (`values-night`) — dihapus; app selalu tema terang.
- **Bilah status remote web** (`#deviceStatus`, `renderStatus`,
  `refreshStatus`, `renderSpeedTotal`) — dihapus 2026-08-13; info redundan
  dengan File Manager. Status penting (`readOnly`, port berubah, versi)
  ditangani `applyServerStatus()`.
- **Endpoint `/api/status`** — dihapus (tidak ada klien lagi); status cukup
  lewat `snapshotJson`/SSE.
- **zxing di runtime** — hanya `testImplementation`; encoder QR sendiri
  (`util/QrEncoder.kt`).
- **`values-en`** — tidak ada; default `values/strings.xml` = Inggris.
- **minSdk** — tetap 21 (Android 5+), jangan naikkan.
- **Tombol tab Downloads** — butuh handler klik sendiri (pola
  `tabGallery`/`tabFiles`); jangan hapus handler-nya.
- **`fmtDate`** — hanya SATU definisi di `remote.src.html` (dua definisi
  saling menimpa karena hoisting).

- **Penampil foto & galeri foto remote** — dihapus (2026-08-14); galeri remote dan
  native hanya menampilkan/memindai video. Jangan tambahkan kembali penampil foto
  atau filter All/Photos/Videos tanpa alasan kuat dan diskusi.
- **Pencarian/filter galeri remote** — dihapus supaya area video maksimal. Endpoint
  boleh menerima parameter legacy untuk kompatibilitas klien lama, tapi UI baru
  jangan menampilkannya.
- **Gesture brightness/volume player** — dihapus; interaksi pemutar memakai tombol,
  seekbar, double-tap ±10 detik, dan kontrol ramah D-pad.
- **Endpoint `/api/delete_media`** — tidak ada lagi; penghapusan media galeri remote
  dihapus bersama fitur foto.
- **Stream parsial tanpa token** — dilarang. `/stream_part/<id>` wajib lewat
  `createPartialStreamUrl()` dan tervalidasi oleh `ServerSecurity.isPartialTokenValid()`.

## Pola bug yang pernah terjadi & guard-nya

| Pola bug | Penyebab | Guard |
|---|---|---|
| Tab Downloads tidak bisa diklik dari File Manager/Galeri | `tabDownloads` tidak punya handler klik | smoke test klik `tabDownloads` di `scripts/upload_smoke_test.js` |
| Tanggal file tampil format salah | dua `fmtDate` (hoisting, definisi kedua menang) | aturan satu definisi (lihat keputusan historis) |
| Kata Indonesia lolos ke UI remote | kata pendek tidak ada di daftar larangan | `BANNED_ID` di `scripts/prepare_remote.py` — tambahkan kata baru saat ketemu |
| Upload gagal diam-diam "listEl.appendChild is not a function" | argumen `uploadFiles()` tertukar | `check_upload_call` di `prepare_remote.py` + smoke test 4 chunk |
| Path traversal / PIN bypass | logika keamanan bocor ke endpoint | unit test `ServerSecurity` — jangan pindahkan logika ke `HttpControlServer` |
| File Manager HTTP 500 setelah stop/start server | `stopServer()` men-shutdown `statPool`, tapi toggle server memakai instance yang sama → pool tetap `Terminated`, semua `statPool.submit()` lempar `RejectedExecutionException` (PR #91) | `liveStatPool()` membuat pool baru otomatis saat pool lama shutdown — jangan balikkan ke `statPool` langsung |
| `GALLERY SCAN` berulang tiap request galeri | cache scan dianggap "belum lengkap" karena `items.size >= limit` gagal saat total file < limit → scan MediaStore penuh berulang dalam masa TTL | kondisi cache juga menerima scan tuntas: `items.size == total` (di `MediaLibrary.scanCached` + `scannedGallery`) |
| Checksum dari header server salah di-parse (Digest/Content-MD5/X-Checksum-*) | base64 vs hex, huruf besar/kecil, preferensi sha-256 | unit test `ChecksumsTest` — format output wajib "algo:hex" huruf kecil (dipahami `parseChecksum` engine) |
| Tujuan tulis remote keluar root | validasi lama hanya cek prefix `f:` | gunakan `ServerSecurity.isRemoteDestinationAllowed(folderPath, allowedFsRoots())` untuk semua tujuan download/upload |
| Koneksi segmen bocor saat pause/cancel | satu ID hanya menyimpan koneksi terakhir sehingga segmen lain tetap hidup | semua koneksi disimpan di set per-ID melalui `trackConnection()`/`disconnectActive()`; jangan tulis langsung ke `activeConns` |
| File hasil merge korup saat gagal | bagian `.part.*` dihapus di tengah merge | merge ke staging dulu, finalisasi via rename, hapus parts hanya setelah sukses (`FileSaver.mergeSegments`) |
| Resume mencampur resource lama/baru | server mengganti file tanpa URL berubah | reset partial bila ETag tersimpan berubah (`invalidateChangedResume`) |
| Upload chunk dobel/race finalisasi | fallback ID dari nama/path dan penulisan paralel | client wajib ID eksplisit; server mengunci penulisan per-ID (`uploadLockFor`) |
| Login throttle global bisa diblokir IP lain / boros memori | counter volatile global | state gagal per-IP di `loginAttempts`, map dibatasi dan dibersihkan |
| Token media bocor di log request | query string dicetak mentah | `appendRequestLog()` redact `token` dan `pin`; pertahankan regex redaction |
| Scan galeri duplikat saat load-more | request paralel melewati cache | scan/cache invalidation harus tetap di dalam `MediaLibrary.scanLock` |
| ZIP folder dibuat berulang oleh Range paralel | `putIfAbsent` hanya mencegah duplikasi cache, bukan proses create | serialisasi `zipCached()` per key dengan strip lock; recheck cache di dalam lock |
| Upload progress mundur/macet saat retry | baseline progres global tidak direset ketika file dimulai ulang dari chunk 0 | reset kontribusi file via `resetFileProgress()` sebelum attempt |
| Batch fs action tetap lanjut setelah gagal | `postFsAction()` menelan error sehingga promise selalu resolved | helper melaporkan error lalu rethrow; caller berhenti dan refresh state |
| SSE reconnect diam bisa gagal masuk state "give up" | flag diberi nilai `true` sebelum reconnect manual | pakai counter percobaan sekali + grace window sebelum menutup EventSource |

## Aturan pengembangan

1. **Build resmi HANYA via GitHub Actions** — jangan build lokal untuk rilis.
   Build lokal (`./gradlew assembleDebug`) hanya untuk debugging cepat dan tidak
   pernah menggantikan CI.
2. **Bahasa**:
   - **UI aplikasi memakai Bahasa Inggris** (default `values/strings.xml`, tanpa
     `values-en` lagi — terjemahan default = Inggris; `remote.html` juga Inggris).
     Jangan menulis teks UI baru dalam Bahasa Indonesia.
   - **Komentar kode, dokumentasi internal, dan commit tetap Bahasa Indonesia**
     (kecuali konten yang memang untuk pengguna internasional, seperti README.en.md).
3. **Gaya commit**: `type(scope): deskripsi` — tipe yang dipakai di repo ini:
   `feat`, `fix`, `ui`, `perf`, `refactor`, `docs`, `chore`, `rebrand`
   (contoh: `ui(remote): ...`, `perf(gallery): ...`). Satu commit satu tujuan.
4. **Jangan ubah `versionName`/`versionCode` manual** — `versionName` tetap
   `"1.0"`; `versionCode` di-bump otomatis oleh CI (`100000 + run_number`).
5. **Jaga kompatibilitas Android 5 (minSdk 21)**: API baru harus punya fallback
   (contoh: `RELATIVE_PATH`, `NetworkCallback`); jangan naikkan minSdk.
6. **`targetSdk 36`**: storage di Android 11+ wajib `MANAGE_EXTERNAL_STORAGE`
   ("Akses semua file") — jangan turunkan tanpa strategi storage pengganti.
7. **Remote web = UI utama**: setiap perubahan halaman remote (dan endpoint API)
   harus tetap mobile-first dan ramah D-pad TV; jangan menambah dependensi berat
   (APK tetap kecil); hindari *switch* di remote — pakai tombol biasa.
   Catatan: zxing hanya di `testImplementation` (verifikasi decode QR) — APK
   memakai encoder sendiri (`util/QrEncoder.kt`), jangan kembalikan zxing ke
   runtime tanpa alasan kuat.
   `assets/remote.html` sengaja di-minify (hemat ukuran; gzip transfer sudah
   otomatis di nanohttpd). **Sumber readable = `remote.src.html` di root repo**:
   ubah di sana, lalu jalankan `python3 scripts/prepare_remote.py` dan commit
   KEDUA file (CI memverifikasi sinkron lewat `--check`; jangan edit
   `assets/remote.html` manual).
8. **Guard remote web**: step CI `scripts/prepare_remote.py --check` memverifikasi
   sinkron `remote.src.html` ↔ `remote.html`, `node --check` pada semua `<script>`,
   larangan kata Indonesia di string UI (remote.html, values/*.xml, Kotlin), dan
   smoke test alur upload klien (`scripts/upload_smoke_test.js`). Jalankan juga
   sebelum commit.
9. **Jaringan jangan di main thread**; polling adaptif (2s aktif / 10s idle);
   SSE wajib punya fallback polling & reconnect.
10. **Jangan commit keystore** (`*.jks`, `keystore.b64` sudah di-`.gitignore`).
11. **PR**: workflow ikut build (tanpa release) — gunakan untuk mengecek
    compile/CI sebelum merge ke `main`.
12. **Lint & unit test wajib hijau** sebelum merge — `lintDebug` (abortOnError
    aktif) mengawal API >21 jangan sampai lolos, `testDebugUnitTest` menjaga
    logika murni (`Formats`, `FileNames`, `MimeTypes`, `DownloadItem`,
    `ServerSecurity`). Cakupan unit test (JaCoCo) di CI: `jacocoTestReport` +
    `jacocoTestCoverageVerification` (ambang LINE 5%, lihat
    `app/build.gradle.kts` — naikkan seiring bertambahnya test); ringkasan
    cakupan dicetak di job summary.
13. **Update dependensi (AGP/Kotlin/Gradle) bertahap** — jangan lompat beberapa
    versi sekaligus; tiap langkah lewat CI dulu. Versi dipusatkan di
    `gradle/libs.versions.toml` (version catalog) — update cukup di satu
    tempat, Dependabot ikut membacanya.
14. **Changelog wajib per perubahan kode** — commit yang mengubah `app/src/main`,
    `remote.src.html`, `app/build.gradle.kts`, atau `scripts/` wajib menyertakan
    entri `CHANGELOG.md`. Untuk PR, sebut nomor PR di isi entri setelah dibuat.
    **Dijaga otomatis CI** berdasarkan diff push/PR. Satu commit/PR = satu tujuan;
    jangan campur fitur + refactor besar + docs.
15. **Jangan berhenti di tengah alur rilis** — setiap push rilis ke `main` wajib
    dipantau sampai workflow Build APK sukses dan asset APK terbaru ada di release
    `v1.0` (lihat "Cara cek rilis terbaru"). Normal flow adalah PR; owner boleh push
    hotfix/docs langsung hanya jika CI tetap dipantau penuh.
16. **Pre-commit hook opsional** — aktifkan dengan `git config core.hooksPath
    .githooks` (jalankan `prepare_remote.py --check` + unit test cepat).
    Hook tidak wajib; CI tetap penentu.
17. **Cek kesehatan keystore otomatis di CI** — workflow membandingkan
    fingerprint sertifikat signing dari `KEYSTORE_BASE64` dengan
    `c2785a61...`; mismatch = build gagal (keystore salah/korup terdeteksi
    lebih awal).

## Cara memicu build & release

- **Normal flow**: PR → build verifikasi tanpa publish → merge ke `main`.
- **Hotfix owner**: push langsung ke `main` diperbolehkan bila memang disengaja,
  tapi workflow tetap wajib dipantau sampai sukses dan release ter-refresh.
- **Push sukses ke `main`** → workflow `build.yml` menjalankan guard, test,
  build/release, lalu me-refresh release `v1.0` dengan APK
  `tasirin-download-manager-v1.0-<code>.apk` (`code = 100000 + run_number`).
- **Dependabot** → update dikelompokkan (`androidx`, `kotlinx`,
  `gradle-tools`, `actions`). PR yang TIDAK menyentuh dependensi Gradle
  (mis. update GitHub Actions) di-**auto-merge** setelah CI hijau (workflow
  `auto-merge.yml`). PR dependensi Gradle tetap manual karena wajib
  regenerasi metadata verifikasi. Yang di-ignore (perlu upgrade toolchain
  manual): major AGP & Kotlin, `activity`, `lifecycle` (minSdk 23),
  `androidx.core` (compileSdk 37). Guard CHANGELOG dikecualikan untuk author
  `dependabot[bot]`.
- **Setiap perubahan dependensi wajib ikut regenerasi metadata verifikasi**
  (`gradle/verification-metadata.xml`) — verifikasi strict aktif, jadi
  dependensi baru/tanpa checksum membuat CI gagal. Alur: jalankan workflow
  manual `update-deps-verification.yml` (pakai `--ref <branch>`), unduh
  artifact, commit metadata, lalu push bersama PR-nya.
- **Manual**: GitHub → Actions → *Build APK* → *Run workflow*
  (atau `gh workflow run build.yml -R tasirin1/tasirin-download-manager`).
- **Jangan edit release manual** — selalu lewat workflow.

## Alur pipeline (build.yml)

1. Checkout → **guard CHANGELOG** (perubahan kode wajib update `CHANGELOG.md`;
   dikecualikan untuk PR Dependabot) → **`scripts/prepare_remote.py --check`**
   (sinkron remote.html, node --check, guard i18n) + **cek sinkron
   README/README.en** (struktur heading) → JDK 17 → **cek kesehatan
   keystore** (fingerprint `c2785a61...` + **masa berlaku**: error < 90 hari,
   warning < 180 hari) → Android SDK → Gradle (cache + verifikasi wrapper +
   **verifikasi dependensi strict** lewat `gradle/verification-metadata.xml`).
2. **Bump versionCode**: `100000 + run_number` ditulis ke `app/build.gradle.kts`.
3. `assembleDebug` (artifact `app-debug`).
4. **Lint + unit test**: `lintDebug` (abortOnError) + `testDebugUnitTest`.
5. `assembleRelease` **hanya bila `KEYSTORE_BASE64` terisi** (artifact `app-release`).
6. **Verifikasi tanda tangan APK release** (`keytool -printcert -jarfile`
   dibandingkan `c2785a61...`) — membuktikan APK benar ditandatangani kunci resmi.
7. **Cek ukuran APK** (maks 3,5 MB — jaga APK tetap kecil).
8. Publish release `v1.0`: APK signed + **`mapping.txt`** (deobfuscation R8).
9. **VirusTotal scan** (opsional, hanya bila `VT_API_KEY` terisi) — submit APK
   rilis/debug, polling sampai analisis selesai, lalu ringkasan deteksi (X/Y
   engine) dicetak di log + job summary; jalan di push `main` DAN di PR
   (repositori sama) supaya APK yang mau di-merge sudah di-scan.

Catatan: workflow memakai `concurrency` (run lama di ref sama dibatalkan) dan
ada **build terjadwal mingguan** (Senin 03:00 UTC) yang hanya memverifikasi
build — tidak mem-publish release. **CodeQL** (Java/Kotlin) dan **gitleaks**
(deteksi secret) berjalan di tiap push/PR; temuan CodeQL muncul di tab
Security & alerts repositori.

## Secrets yang dibutuhkan (Settings → Secrets and variables → Actions)

| Secret               | Fungsi                              |
|----------------------|-------------------------------------|
| `KEYSTORE_BASE64`    | File `keystore.jks` di-encode base64 |
| `KEYSTORE_PASSWORD`  | Password keystore                   |
| `KEY_ALIAS`          | Alias kunci signing                 |
| `KEY_PASSWORD`       | Password kunci alias                |
| `VT_API_KEY`         | (Opsional) API key VirusTotal — scan APK di `main` & PR, tanpa ini step dilewati |

Keystore yang sama dipakai juga oleh repo **Tasirin Vaultwarden Host** — simpan
satu salinan aman (jangan di commit, jangan hanya di satu perangkat).

### Verifikasi keystore mana yang dipakai

Fingerprint SHA-256 sertifikat signing release (alias `tasirin`) — BUKAN
rahasia, sudah tertanam di `Updater.kt` sebagai `RELEASE_CERT_SHA256`:

```
c2785a618082683755eeae867e0a2e01f450b1fd448859d1ec21cf854c5713d1
```

Cara cek APK rilis: `keytool -printcert -jarfile <apk>` lalu bandingkan
baris SHA-256 (hapus titik dua, huruf kecil). Bila berbeda, keystore yang
dipakai CI bukan yang resmi — perbaiki sebelum rilis.

## Menambah/mengubah fitur — file mana yang disentuh

- **Perilaku unduhan (segment, retry, fallback Range, HLS)** → `DownloadEngine.kt`
  (+ `DownloadService.kt` bila menyangkut foreground service/notifikasi).
- **Endpoint API / halaman remote** → `HttpControlServer.kt` (endpoint) +
  `remote.src.html` (lalu jalankan `scripts/prepare_remote.py`).
- **Keamanan server (path/tujuan FS, lock PIN, throttle login, offset upload,
  token share/stream parsial)** → `remote/ServerSecurity.kt` (fungsi murni +
  unit test). Orkestrasi endpoint boleh di `HttpControlServer.kt`, tapi keputusan
  keamanan jangan didupkan/dipindah keluar helper ini.
- **Pembersihan storage otomatis** → `util/StorageCleanup.kt` (partial file,
  thumbnail lama, sisa upload).
- **Galeri / thumbnail** → `MediaLibrary.kt` + `GalleryActivity.kt`.
- **Pengaturan baru** → `SettingsActivity.kt` + `StoragePrefs.kt`
  (simpan kunci baru di sana) + `remote.html` bila perlu ditampilkan remote.
- **Self-update APK** → `Updater.kt` — **download-only** (format nama asset
  `-<code>.apk` wajib dipertahankan agar versi terbaca). Jangan kembalikan
  auto-install (`REQUEST_INSTALL_PACKAGES`): sengaja dihapus untuk menurunkan
  sinyal berbahaya bagi Play Protect aplikasi sideload.
- **Log server** → `LogActivity.kt` + buffer log (lihat `App.kt`/engine).
- **Versi app** → jangan manual; CI yang mengatur (lihat aturan di atas).

## Cara cek rilis terbaru & verifikasi build

- Release `v1.0` di-refresh tiap push ke `main`; asset APK selalu
  `tasirin-download-manager-v1.0-<code>.apk` dengan `code = 100000 + run_number`.
- Jangan ubah `versionName`/`versionCode` manual (di-bump CI).

```bash
gh run list --branch main --limit 1            # build terakhir
gh run watch <run-id> --exit-status
gh run view <run-id> --json status,conclusion
gh release view v1.0 --json assets -q '.assets[].name'
```

Pastikan conclusion `success`, CodeQL/Gitleaks tidak gagal, dan release punya APK
+ `mapping.txt`. Verifikasi manual: pasang APK di HP, buka remote web dari browser,
tes tambah URL/upload ringan, dan buka galeri video.

## Peta jalan: targetSdk 36/37 (Android 16/17)

**Selesai (Fase 1–3).** `targetSdk 36` + `compileSdk 36` aktif dan sudah
diuji manual di perangkat Android 15/16 (auto-start boot, download background,
server remote, galeri). Keputusan naik ke `targetSdk 37` masih **opsional** —
jangan digabung dengan PR fitur lain.

- **Fase 1 — selesai**: lint + unit test aktif (pengaman API 21), dependensi
  di-update bertahap via CI, toolchain Gradle 9.6.1 + AGP 9.0.1 (built-in
  Kotlin, KGP dibundel AGP), core library desugaring aktif.
  `versionName`/`versionCode` tetap diatur CI.
- **Fase 2a — selesai**: `compileSdk 36`.
- **Fase 2b — selesai**: `targetSdk 35`.
  - **FGS dari `BOOT_COMPLETED`**: `dataSync` FGS dilarang start langsung dari
    receiver boot — `BootReceiver` kini menjadwalkan `BootResumeJobService`
    (JobScheduler, API 21+) yang meneruskan ke `DownloadService`.
  - **Edge-to-edge dipaksakan**: `applyEdgeToEdge()` (insets system bars)
    dipasang di `MainActivity`/`SettingsActivity`/`LogActivity`/`GalleryActivity`.
  - **Izin notifikasi & storage**: alur runtime request + special access tetap.
- **Fase 2c — selesai**: `targetSdk 36`.
  - **Predictive back default**: aktif otomatis untuk targetSdk 36; app tidak
    memakai `onBackPressed()` custom (hanya `OnBackPressedDispatcher` AndroidX),
    jadi animasi back bawaan tetap berfungsi.
  - **16 KB page size**: app murni Java/Kotlin tanpa native library — tidak
    terdampak.
- **Fase 3 — selesai (2026-08-11)**: uji manual di perangkat Android 15/16
  (auto-start boot, download background, server remote, galeri) — semua berjalan
  baik. `targetSdk 37` belum dinaikkan (opsional; lint `OldTargetApi` tetap
  di-suppress).
- **Pantangan**: jangan naikkan `minSdk` (tetap 21), dan jangan gabung perubahan
  targetSdk dengan PR fitur lain.
