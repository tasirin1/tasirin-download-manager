<p align="center">
  <img src="app/src/main/res/mipmap-xxxhdpi/ic_launcher.png" alt="Tasirin Download Manager" width="96"><br>
  <b>Tasirin Download Manager — Android</b><br>
  Download manager + remote control web lengkap, nyaman dipakai di TV box & HP.
</p>

# Tasirin Download Manager (Android)

[![Build](https://github.com/tasirin1/tasirin-download-manager/actions/workflows/build.yml/badge.svg)](https://github.com/tasirin1/tasirin-download-manager/actions)
[![Release](https://img.shields.io/github/v/release/tasirin1/tasirin-download-manager)](https://github.com/tasirin1/tasirin-download-manager/releases)

**Satu aplikasi untuk semua kebutuhan file di perangkat Android:** unduh cepat, kelola dari browser lewat jaringan Wi-Fi, jelajah file, mainkan galeri ala YouTube, dan pantau semuanya secara realtime — cocok dipakai di HP maupun TV box (Android 5.0+ / API 21+).

Dibangun dengan **Kotlin + Jetpack**, tanpa iklan, tanpa akun. Kode terbuka di GitHub dan setiap pembaruan otomatis di-build menjadi APK siap pasang.

Repo ini juga **panduan pengelolaan untuk manusia maupun AI** (lihat [Panduan pengelolaan repo](#panduan-pengelolaan-repo-untuk-manusia--ai)).

## Daftar isi

- [Fitur](#fitur)
- [Unduh](#unduh)
- [Update otomatis APK](#update-otomatis-apk)
- [Remote Web Realtime](#remote-web-realtime)
- [Player video ala YouTube](#player-video-ala-youtube)
- [Penyimpanan](#penyimpanan)
- [Galeri](#galeri)
- [Pengaturan](#pengaturan)
- [Troubleshooting](#troubleshooting)
- [Build](#build)
- [Panduan pengelolaan repo (untuk manusia & AI)](#panduan-pengelolaan-repo-untuk-manusia--ai)
- [Lisensi](#lisensi)

## Fitur

| | |
|---|---|
| 🚀 **Manajer unduhan lengkap** | multi-segmen, resume dengan Range, antrean pintar, batas kecepatan, auto-retry |
| 📡 **Remote web realtime (SSE)** | kontrol dari browser perangkat lain, update langsung tanpa refresh manual |
| 🖥️ **Player video ala YouTube** | seekbar merah, double-tap ±10 detik, gesture volume/kecerahan, auto-next |
| 🗂️ **File manager remote** | jelajah, upload, hapus massal, ZIP folder, pratinjau media langsung |
| 🖼️ **Galeri media device** | thumbnail 16:9, durasi asli, filter foto/video, folder foto & video terpisah |
| 📶 **Siap untuk jaringan pelan** | timeout connect/read bisa diatur, mirror otomatis, polling adaptif |
| 🔋 **Siap untuk TV box** | dukungan D-pad/remote, server jalan di background, auto-start saat boot |

Fitur unduhan:

- **Progress realtime**: persentase, ukuran, kecepatan KB/s–MB/s, **ETA stabil** (rata-rata bergerak, tidak melompat-lompat) + grafik kecepatan
- **Multi-segmen** untuk file besar yang mendukung Range, unduhan paralel dengan antrean (jumlah maks. bisa diatur)
- **Antrean pintar**: file kecil didahulukan (opsional) + prioritas manual per download
- Jeda, lanjutkan (resume HTTP Range), batalkan, hapus, ulangi gagal — semua tersimpan otomatis
- **Foreground service**: download lanjut walau app ditutup, retry otomatis dengan jeda bertahap, **auto-start saat boot**
- **Lanjut otomatis saat koneksi pulih**: download yang terputus karena jaringan hilang dilanjutkan sendiri (Android 5–6 via broadcast, 7+ via NetworkCallback)
- **Batas kecepatan & prioritas per-download**, auth HTTP Basic + header custom (Referer, Cookie, dll.)
- Tempel banyak URL sekaligus, riwayat URL, **Share dari aplikasi lain** langsung ke dialog tambah
- Checksum opsional (MD5/SHA1/SHA256), nama duplikat otomatis `nama (1).ext`
- **HLS / m3u8**: deteksi otomatis dan pilih kualitas (variant) sebelum unduh
- **Pantau pembaruan**: item download bisa dipantau berkala — periksa versi baru di URL yang sama dan unduh otomatis
- **Auto-sort lengkap** setelah selesai: `Videos/`, `Photos/`, `Music/`, `Documents/`, `APK/` (pengaturan)
- **Fallback cerdas saat Range ditolak**: server yang tidak mendukung Range otomatis diunduh sekali jalan (single-stream), tanpa gagal total
- **Mirror otomatis** untuk server yang lambat/gagal, URL gagal di-blacklist agar tidak dicoba ulang tanpa henti
- Tema ikuti sistem (otomatis / terang / gelap), bahasa Indonesia/Inggris (ikut sistem)

## Unduh

APK terbaru selalu tersedia di **GitHub Releases** — setiap push ke `main` langsung di-build dan rilis diperbarui otomatis:

**[⬇️ Unduh APK terbaru](https://github.com/tasirin1/tasirin-download-manager/releases/latest)**

APK release sudah **ditandatangani dengan kunci rilis resmi** (bukan debug), jadi lebih dipercaya Android/Play Protect. Pasang di HP / TV box (Android 5.0+), beri izin Penyimpanan saat diminta.

## Update otomatis APK

- App mengecek update ke **release repo ini** sendiri (`Updater.kt`): versi dibaca
  dari nama asset `tasirin-download-manager-v1.0-<code>.apk`, memilih kode
  tertinggi.
- Ada versi baru → dialog **Pembaruan tersedia** di Pengaturan → unduh APK ke
  cache (verifikasi ukuran) → install via installer sistem
  (`REQUEST_INSTALL_PACKAGES`).
- Tidak perlu sideload manual: release di-refresh tiap build CI.

## Remote Web Realtime

Server remote bawaan berjalan **penuh di latar belakang** dan bisa **auto-start saat boot** — semua diatur di **⋮ → Pengaturan**.

1. Di Pengaturan, mulai server (atau biarkan menyala otomatis saat boot)
2. Scan **QR code**, atau buka `http://<ip-device>:<port>/` di browser perangkat lain
3. Masukkan **PIN** jika diatur

Fitur halaman remote:

- **Realtime via SSE**: progress & status datang langsung dari device tanpa refresh manual; fallback polling otomatis bila jaringan memblokir streaming
- **Polling adaptif**: 2 detik saat ada aktivitas, 10 detik saat idle — hemat baterai
- **Item aktif otomatis di urutan atas** + total kecepatan live di bar atas
- **Tampilan mobile**: FAB "+", menu aksi ala bottom-sheet, filter sticky, skeleton loading, empty state informatif
- **Indikator koneksi**: "diperbarui X dtk lalu", titik status berkedip merah saat koneksi putus, refresh otomatis saat tab kembali fokus
- **Upload file & folder** dari browser: chunk 2 MB dengan retry (putus tidak mulai dari nol), drag & drop, nama duplikat otomatis, konfirmasi sebelum tab ditutup
- **File manager remote**: jelajah, buat folder, rename, pindah, hapus massal, **download folder sebagai ZIP**, breadcrumb, pratinjau media langsung
- **Galeri remote ala YouTube**: thumbnail 16:9, badge durasi asli (cache di device), load bertahap, **filter Semua/Foto/Video**
- **Bagikan file via tautan sementara** (berlaku 24 jam, tanpa PIN) + QR code
- **Streaming** file selesai (HTTP Range untuk video/audio) atau download langsung
- Status baterai & penyimpanan, pilihan port, server background + auto-start
- **Auto-lock**: halaman remote minta PIN lagi setelah 10 menit tanpa aktivitas

### Endpoint API (HttpControlServer)

| Endpoint | Fungsi |
|---|---|
| `/api/status`, `/api/snapshot` | Status server & device (versi, port, PIN, ringkasan cepat) |
| `/api/events` | SSE — event realtime (download, log, galeri) |
| `/api/pin_enabled` | Apakah PIN diaktifkan |
| `/api/login`, `/api/logout` | Sesi PIN (token) |
| `/api/downloads` | Daftar download + status |
| `/api/add` | Tambah URL download |
| `/api/action` | Jeda/lanjut/batal/hapus/prioritas, dll. |
| `/api/fs`, `/api/fs_action` | Jelajah file & aksi (buat/hapus/rename/pindah) |
| `/api/fs_dupes`, `/api/fs_zip` | Deteksi duplikat, unduh folder sebagai ZIP |
| `/api/upload`, `/api/upload_verify` | Upload chunk 2 MB + verifikasi |
| `/api/gallery`, `/api/media`, `/api/thumb` | Galeri, streaming (Range), thumbnail |
| `/api/delete_media` | Hapus media galeri |
| `/api/qr`, `/api/share` | Kode QR, tautan berbagi sementara (24 jam) |

## Player video ala YouTube

- Seekbar merah + buffered, **double-tap ±10 detik**, gesture kecerahan/volume
- Kecepatan putar 0.5×–2×, lanjut dari posisi terakhir
- **Saran video** di bawah player, **AUTO (auto-next)** menyala otomatis

## Penyimpanan

Default file disimpan ke **Folder Downloads**. `minSdk 21` dipertahankan (Android 5+ tetap didukung), `targetSdk 34`. Android 5–10 memakai `WRITE_EXTERNAL_STORAGE` + legacy storage (akses penuh); Android 11+ memakai `MANAGE_EXTERNAL_STORAGE` ("Akses semua file").

- **Input path teks**: ketik path mentah seperti `/storage/emulated/0/Download` — folder otomatis dibuat kalau belum ada
- **Folder tambahan (mount)**: ketuk **+** untuk menambah path lain (mis. `/sdcard/Movies`) agar ikut tampil di file manager — cocok untuk folder buatan Total Commander, folder SD card, dll.
- Pilihan tersimpan otomatis dan persisten (bertahan setelah restart)

## Galeri

- **Folder foto & video diatur terpisah** di Pengaturan: tentukan folder galeri foto dan folder galeri video masing-masing (mis. video saja di `/sdcard/Movies/Files`, foto dibiarkan scan semua)
- Kosongkan untuk scan seluruh storage; path `/sdcard/...` otomatis dikenali sebagai `/storage/emulated/0/...`
- Tampilkan durasi video, thumbnail cepat, putar langsung, hapus file

## Pengaturan

Menu **⋮ → Pengaturan**:

- **Unduhan**: resume otomatis, auto-start saat boot, unduhan bersamaan (1–5), jumlah segmen, batas kecepatan, percobaan ulang (0–5), unduh file kecil dulu, **timeout connect (5–60 dtk) & read (10–120 dtk)** untuk jaringan lambat/WISP
- **Server**: port, PIN, QR code, background + auto-start saat boot
- **Penyimpanan**: folder tujuan + folder tambahan
- **Galeri**: folder foto & video terpisah
- **Pembersihan**: hapus log/download yang sudah selesai
- **Log Server realtime**: log aktivitas seluruh sistem (request HTTP, download, galeri, kesalahan) bisa di-cari, di-sorot, dan **diekspor ke file TXT** — mudah untuk lapor bug

Catatan: beberapa vendor (MIUI, dll.) punya pembatasan baterai ketat — aktifkan *auto-start* di pengaturan sistem agar service tidak dimatikan.

## Troubleshooting

**Remote tidak bisa dibuka dari perangkat lain**
- Pastikan server **berjalan** (status di app), perangkat di **jaringan yang sama**,
  dan URL memakai IP lokal. Coba scan QR dari Pengaturan.

**PIN diminta terus / auto-lock**
- Auto-lock mengunci halaman remote setelah 10 menit tanpa aktivitas — masukkan
  PIN lagi. Lupa PIN → matikan lalu nyalakan lagi PIN di Pengaturan (PIN disimpan
  lokal di device, bukan di server).

**Download gagal "Server tidak mendukung Range"**
- Otomatis diunduh sekali jalan (single-stream) — tidak gagal total. Server yang
  tidak mendukung resume tetap bisa diunduh.

**Download berhenti saat HP tidur**
- Aktifkan **auto-start/background** di Pengaturan sistem (vendor MIUI dsb.
  membatasi service). Di app pastikan *foreground service* aktif dan izin baterai
  diabaikan sudah diberikan.

**Koneksi ke GitHub gagal (update APK/checksum)**
- Android 5/6 butuh trust anchor TLS lama (sudah dibundel). Cek koneksi,
  perangkat lain coba lagi; update aman diulang.

**Galeri kosong di Android 6**
- Sudah ditangani: projection MediaStore dibuat kondisional (kolom `RELATIVE_PATH`
  hanya ada di API 29+). Pastikan folder galeri benar atau kosongkan untuk scan semua.

## Build

### Resmi (GitHub Actions) — satu-satunya sumber rilis

Workflow `.github/workflows/build.yml` berjalan otomatis setiap push ke `main`
(atau manual via **Actions → Build APK → Run workflow**). Hasilnya langsung jadi
**release baru** (`v1.0`, APK `tasirin-download-manager-v1.0-<code>.apk`).

Untuk release bertanda tangan, isi secrets `KEYSTORE_BASE64`, `KEYSTORE_PASSWORD`,
`KEY_ALIAS`, `KEY_PASSWORD` di pengaturan repo — APK release (signed) yang
diunggah ke release, bukan APK debug.

> **⚠️ Backup keystore & password-nya selamanya.** Kunci release menandatangani
> semua rilis — kalau hilang, perangkat tidak bisa update APK lama tanpa uninstall,
> dan ganti kunci baru bikin Play Protect curiga. Simpan aman (password manager),
> jangan pernah commit ke repo (`.gitignore` sudah menutup `*.jks` & `keystore.b64`).

### Lokal (debug saja)

```bash
./gradlew assembleDebug
# Hasil: app/build/outputs/apk/debug/app-debug.apk
```

Build lokal **hanya untuk debugging cepat** — tidak pernah menjadi sumber rilis
resmi. Seluruh perubahan dikirim sebagai commit + push, lalu CI yang membangun.

**Persyaratan**: Android 5.0+ (minSdk 21), Java 17 + Android SDK.

---

# Panduan pengelolaan repo

Bagian ini untuk **manusia maupun AI** yang ingin memahami, mengubah, atau
mengelola repository ini dengan benar.

## Struktur repository

```
.
├── .github/workflows/build.yml       # CI: bump versionCode → build APK → release
├── app/build.gradle.kts              # minSdk 21 / targetSdk 34, signing via property, R8
├── app/src/main/
│   ├── AndroidManifest.xml           # permission & komponen (service, receiver, provider)
│   ├── assets/
│   │   └── remote.html               # SELURUH halaman remote web (HTML+CSS+JS satu file)
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
│       ├── remote/HttpControlServer.kt # Server HTTP remote (nanohttpd) + semua endpoint API
│       ├── ui/DownloadAdapter.kt     # RecyclerView adapter daftar download
│       └── util/
│           ├── Updater.kt            # Self-update APK dari GitHub release
│           ├── FileSaver.kt          # Simpan file (MediaStore / folder, auto-sort)
│           ├── MediaLibrary.kt       # Scan galeri + thumbnail (kondisional API 29+)
│           ├── StoragePrefs.kt       # Semua kunci SharedPreferences ("storage_settings")
│           ├── MimeTypes.kt, Crypto.kt, Formats.kt, FileNames.kt,
│           ├── NotificationHelper.kt, TlsCompat.kt            # Pendukung
├── app/src/test/                     # Unit test JVM (junit4): Formats, FileNames,
│                                     # MimeTypes, DownloadItem — jalan di CI
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
  unduh + verifikasi ukuran, lalu install via FileProvider.
- **Kunci SharedPreferences** (`storage_settings`): `folder_uri`, `folder_name`,
  `text_folder_path`, `extra_folders`, `background_download`, `auto_start_boot`,
  `server_background`, `server_autostart_boot`, `server_port`, `server_pin`,
  `pin_enforced`, `fs_full_access`, `max_concurrent`, `segments`,
  `speed_limit_kbps`, `max_retries`, `connect_timeout_sec`, `read_timeout_sec`,
  `small_first`, `delete_partial_on_cancel`, `recent_urls`, `sort_mode`,
  `auto_sort`, `battery_exempt`, `gallery_image_folder`, `gallery_video_folder`.

## Aturan pengembangan

1. **Build resmi HANYA via GitHub Actions** — jangan build lokal untuk rilis.
   Build lokal (`./gradlew assembleDebug`) hanya untuk debugging cepat dan tidak
   pernah menggantikan CI.
2. **Bahasa**: kode, komentar, string UI, dan commit memakai **Bahasa Indonesia**.
3. **Gaya commit**: `type(scope): deskripsi` — tipe yang dipakai di repo ini:
   `feat`, `fix`, `ui`, `perf`, `refactor`, `docs`, `chore`, `rebrand`
   (contoh: `ui(remote): ...`, `perf(gallery): ...`). Satu commit satu tujuan.
4. **Jangan ubah `versionName`/`versionCode` manual** — `versionName` tetap
   `"1.0"`; `versionCode` di-bump otomatis oleh CI (`100000 + run_number`).
5. **Jaga kompatibilitas Android 5 (minSdk 21)**: API baru harus punya fallback
   (contoh: `RELATIVE_PATH`, `NetworkCallback`); jangan naikkan minSdk.
6. **`targetSdk 34`**: storage di Android 11+ wajib `MANAGE_EXTERNAL_STORAGE`
   ("Akses semua file") — jangan turunkan tanpa strategi storage pengganti.
7. **Remote web = UI utama**: setiap perubahan halaman remote (dan endpoint API)
   harus tetap mobile-first dan ramah D-pad TV; jangan menambah dependensi berat
   (APK tetap kecil); hindari *switch* di remote — pakai tombol biasa.
8. **Jaringan jangan di main thread**; polling adaptif (2s aktif / 10s idle);
   SSE wajib punya fallback polling & reconnect.
9. **Jangan commit keystore** (`*.jks`, `keystore.b64` sudah di-`.gitignore`).
10. **PR**: workflow ikut build (tanpa release) — gunakan untuk mengecek
    compile/CI sebelum merge ke `main`.
11. **Lint & unit test wajib hijau** sebelum merge — `lintDebug` (abortOnError
    aktif) mengawal API >21 jangan sampai lolos, `testDebugUnitTest` menjaga
    logika murni (`Formats`, `FileNames`, `MimeTypes`, `DownloadItem`).
12. **Update dependensi (AGP/Kotlin/Gradle) bertahap** — jangan lompat beberapa
    versi sekaligus; tiap langkah lewat CI dulu.

## Cara memicu build & release

- **Push ke `main`** → workflow `build.yml` jalan → release `v1.0` di-*refresh*
  (dihapus & dibuat ulang, `--latest`) berisi APK
  `tasirin-download-manager-v1.0-<code>.apk`.
- **Pull request** → build saja (verifikasi), **tidak** publish release.
- **Manual**: GitHub → Actions → *Build APK* → *Run workflow*
  (atau `gh workflow run build.yml -R tasirin1/tasirin-download-manager`).
- **Jangan edit release manual** — selalu lewat workflow.

## Alur pipeline (build.yml)

1. Checkout → JDK 17 → Android SDK → Gradle (cache + verifikasi wrapper).
2. **Bump versionCode**: `100000 + run_number` ditulis ke `app/build.gradle.kts`.
3. `assembleDebug` (artifact `app-debug`).
4. **Lint + unit test**: `lintDebug` (abortOnError) + `testDebugUnitTest`.
5. `assembleRelease` **hanya bila `KEYSTORE_BASE64` terisi** (artifact `app-release`).
6. Publish release `v1.0` dengan APK signed (fallback debug bila tanpa secrets).

## Secrets yang dibutuhkan (Settings → Secrets and variables → Actions)

| Secret               | Fungsi                              |
|----------------------|-------------------------------------|
| `KEYSTORE_BASE64`    | File `keystore.jks` di-encode base64 |
| `KEYSTORE_PASSWORD`  | Password keystore                   |
| `KEY_ALIAS`          | Alias kunci signing                 |
| `KEY_PASSWORD`       | Password kunci alias                |

Keystore yang sama dipakai juga oleh repo **Tasirin Vaultwarden Host** — simpan
satu salinan aman (jangan di commit, jangan hanya di satu perangkat).

## Menambah/mengubah fitur — file mana yang disentuh

- **Perilaku unduhan (segment, retry, fallback Range, HLS)** → `DownloadEngine.kt`
  (+ `DownloadService.kt` bila menyangkut foreground service/notifikasi).
- **Endpoint API / halaman remote** → `HttpControlServer.kt` (endpoint) +
  `assets/remote.html` (HTML/CSS/JS satu file).
- **Galeri / thumbnail** → `MediaLibrary.kt` + `GalleryActivity.kt`.
- **Pengaturan baru** → `SettingsActivity.kt` + `StoragePrefs.kt`
  (simpan kunci baru di sana) + `remote.html` bila perlu ditampilkan remote.
- **Self-update APK** → `Updater.kt` (format nama asset `-<code>.apk` wajib
  dipertahankan agar versi terbaca).
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

## Peta jalan: targetSdk 35 (Android 15/16)

**Belum dikerjakan — hanya rencana.** Aplikasi tetap `targetSdk 34` + `minSdk 21`
sekarang; pembaruan ini tidak wajib karena APK disebar via GitHub (bukan Play
Store). Tujuan: perangkat Android 15/16 tetap berfungsi penuh saat nanti naik
target.

- **Fase 1 — sekarang**: lint + unit test aktif (pengaman API 21), dependensi
  di-update bertahap via CI. `versionName`/`versionCode` tetap diatur CI.
- **Fase 2 — saat siap**: naikkan `compileSdk`/`targetSdk` ke 35.
  - **FGS dari `BOOT_COMPLETED`**: saat targetSdk 35, service `dataSync` tidak
    boleh lagi start langsung dari receiver boot — migrasi ke `WorkManager`
    (periode) atau `JobScheduler` untuk download lanjut + server auto-start.
  - **Edge-to-edge dipaksakan**: layout perlu menangani `systemBars` insets
    (Android 15 mewajibkan), revisi `MainActivity`/`SettingsActivity`/`LogActivity`.
  - **Izin notifikasi & storage**: alur yang ada (runtime request + special
    access) tetap berlaku — verifikasi ulang di Android 15.
- **Fase 3 — setelah hijau**: uji manual di perangkat Android 15/16 (auto-start
  boot, download background, server remote, galeri) sebelum dirilis via push ke
  `main`.
- **Pantangan**: jangan naikkan `minSdk` (tetap 21) dan jangan gabung perubahan
  ini dengan PR fitur lain.

## Lisensi

MIT — lihat [LICENSE](LICENSE).
