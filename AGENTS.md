# Panduan pengelolaan repo (untuk AI)

Baca file ini SEBELUM mengubah, memperbaiki, atau mengelola repository ini.
Panduan lengkap yang lain (fitur, cara pakai, troubleshooting) ada di
`README.md` (Indonesia) dan `README.en.md` (Inggris) — jaga keduanya sinkron.

## Struktur repository

```
.
├── .github/workflows/build.yml       # CI: cek remote web → bump versionCode → build APK → release
├── app/build.gradle.kts              # minSdk 21 / targetSdk 36, compileSdk 36, desugaring, R8
├── CHANGELOG.md                       # Riwayat perubahan per rilis (update manual)
├── remote.src.html                   # SUMBER readable remote web (SELURUH halaman)
├── scripts/prepare_remote.py         # Minify remote.src.html → assets/remote.html + guard CI
├── scripts/upload_smoke_test.js      # Smoke test alur upload (stub DOM/XHR, tanpa dependensi)
├── app/src/main/
│   ├── AndroidManifest.xml           # permission & komponen (service, receiver, provider)
│   ├── assets/
│   │   └── remote.html               # Remote web minified (digenapi dari remote.src.html)
│   ├── res/raw/                      # trust anchor TLS (digicert_global_root_g2.pem, isrg_root_x1.pem)
│   └── java/com/tasirin/httpdownloadmanager/
│       ├── App.kt                    # Application — inisialisasi engine download
│       ├── MainActivity.kt           # UI utama: daftar download, dialog tambah URL, About
│       ├── GalleryActivity.kt        # Galeri perangkat (foto/video lokal)
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
│           ├── MediaLibrary.kt       # Scan galeri + thumbnail (kondisional API 29+)
│           ├── StoragePrefs.kt       # Semua kunci SharedPreferences ("storage_settings")
│           ├── StorageCleanup.kt     # Auto-cleanup saat storage menipis (partial, thumbs, upload tmp)
│           ├── QrEncoder.kt          # Encoder QR mandiri (tanpa zxing di APK)
│           ├── BitmapUtil.kt         # scaleDown bersama (galeri + server remote)
│           ├── Spinners.kt, Streams.kt  # Helper spinner + baca stream terbatas
│           ├── MimeTypes.kt, Crypto.kt, Formats.kt, FileNames.kt,
│           ├── NotificationHelper.kt, TlsCompat.kt            # Pendukung
├── app/src/test/                     # Unit test JVM (junit4): Formats, FileNames,
│                                     # MimeTypes, DownloadItem, QrEncoder (decode
│                                     # via zxing test-scope) — jalan di CI
└── gradle wrapper                    # build via ./gradlew (CI saja untuk rilis)
```

Catatan: daftar struktur lama menyebut `widget/SpeedChartView.kt` — file itu sudah
tidak ada (grafik kecepatan digambar inline di `remote.html`); dokumentasi ini sudah
diperbarui.

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
  (`/api/events`), sesi PIN dengan auto-lock 10 menit, upload chunk 2 MB, ZIP
  folder, streaming dengan Range, galeri & thumbnail.
- **MediaLibrary** memindai MediaStore (kolom `DURATION` untuk durasi; `RELATIVE_PATH`
  hanya bila API ≥ 29), TTL 15 detik, thumbnail 16:9 di-cache.
- **Updater** membaca release GitHub, memilih asset APK dengan kode tertinggi,
  memverifikasi tanda tangan (SHA-256 sertifikat release), lalu install via
  FileProvider.
- **Kunci SharedPreferences** (`storage_settings`): `folder_uri`, `folder_name`,
  `text_folder_path`, `extra_folders`, `background_download`, `auto_start_boot`,
  `server_background`, `server_autostart_boot`, `server_port`, `server_pin`,
  `pin_enforced`, `fs_full_access`, `server_read_only`, `max_concurrent`, `segments`,
  `speed_limit_kbps`, `max_retries`, `connect_timeout_sec`, `read_timeout_sec`,
  `small_first`, `delete_partial_on_cancel`, `recent_urls`, `sort_mode`,
  `auto_sort`, `battery_exempt`, `gallery_image_folder`, `gallery_video_folder`.

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
    `ServerSecurity`).
13. **Update dependensi (AGP/Kotlin/Gradle) bertahap** — jangan lompat beberapa
    versi sekaligus; tiap langkah lewat CI dulu.

## Cara memicu build & release

- **Push ke `main`** → workflow `build.yml` jalan → release `v1.0` di-*refresh*
  (dihapus & dibuat ulang, `--latest`) berisi APK
  `tasirin-download-manager-v1.0-<code>.apk`.
- **Pull request** → build saja (verifikasi), **tidak** publish release.
- **Dependabot** → PR update dependensi (Gradle/Actions) lewat alur PR biasa
  (build + lint + test, tanpa release) — review lalu merge. Yang di-ignore
  (perlu upgrade toolchain manual): major AGP & Kotlin, `material`, `activity`,
  `lifecycle` (minSdk 23), `androidx.core` (compileSdk 37).
- **Manual**: GitHub → Actions → *Build APK* → *Run workflow*
  (atau `gh workflow run build.yml -R tasirin1/tasirin-download-manager`).
- **Jangan edit release manual** — selalu lewat workflow.

## Alur pipeline (build.yml)

1. Checkout → **`scripts/prepare_remote.py --check`** (sinkron remote.html,

   node --check, guard i18n) → JDK 17 → Android SDK → Gradle (cache + verifikasi wrapper).
2. **Bump versionCode**: `100000 + run_number` ditulis ke `app/build.gradle.kts`.
3. `assembleDebug` (artifact `app-debug`).
4. **Lint + unit test**: `lintDebug` (abortOnError) + `testDebugUnitTest`.
5. `assembleRelease` **hanya bila `KEYSTORE_BASE64` terisi** (artifact `app-release`).
6. **Cek ukuran APK** (maks 3,5 MB — jaga APK tetap kecil).
7. Publish release `v1.0` dengan APK signed (fallback debug bila tanpa secrets).
8. **VirusTotal scan** (opsional, hanya bila `VT_API_KEY` terisi) — submit APK
   rilis, polling sampai analisis selesai, lalu ringkasan deteksi (X/Y engine)
   dicetak di log + job summary; hanya jalan pada push ke `main` (bukan PR).

## Secrets yang dibutuhkan (Settings → Secrets and variables → Actions)

| Secret               | Fungsi                              |
|----------------------|-------------------------------------|
| `KEYSTORE_BASE64`    | File `keystore.jks` di-encode base64 |
| `KEYSTORE_PASSWORD`  | Password keystore                   |
| `KEY_ALIAS`          | Alias kunci signing                 |
| `KEY_PASSWORD`       | Password kunci alias                |
| `VT_API_KEY`         | (Opsional) API key VirusTotal — scan APK rilis, tanpa ini step dilewati |

Keystore yang sama dipakai juga oleh repo **Tasirin Vaultwarden Host** — simpan
satu salinan aman (jangan di commit, jangan hanya di satu perangkat).

## Menambah/mengubah fitur — file mana yang disentuh

- **Perilaku unduhan (segment, retry, fallback Range, HLS)** → `DownloadEngine.kt`
  (+ `DownloadService.kt` bila menyangkut foreground service/notifikasi).
- **Endpoint API / halaman remote** → `HttpControlServer.kt` (endpoint) +
  `remote.src.html` (lalu jalankan `scripts/prepare_remote.py`).
- **Keamanan server (path FS, lock PIN, offset upload, token share)** →
  `remote/ServerSecurity.kt` (fungsi murni + unit test, jangan taruh logika
  baru langsung di `HttpControlServer`).
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

## Verifikasi setelah build

```bash
gh run watch <run-id> --exit-status
gh run view <run-id> --json status,conclusion
gh release view v1.0 --json assets -q '.assets[].name'
```

Pastikan conclusion `success` dan release punya asset APK dengan nama
`tasirin-download-manager-v1.0-<code>.apk`. Verifikasi manual: pasang APK di HP,
buka remote web dari browser, tes tambah URL, dan buka Galeri.

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
