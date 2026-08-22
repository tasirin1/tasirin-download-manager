<p align="center">
  <img src="app/src/main/res/mipmap-xxxhdpi/ic_launcher.png" alt="Tasirin Download Manager" width="96"><br>
  <b>Tasirin Download Manager — Android</b><br>
  Download manager + remote control web lengkap, nyaman dipakai di TV box & HP.
</p>

# Tasirin Download Manager (Android)

[![Build](https://github.com/tasirin1/tasirin-download-manager/actions/workflows/build.yml/badge.svg)](https://github.com/tasirin1/tasirin-download-manager/actions)
[![Release](https://img.shields.io/github/v/release/tasirin1/tasirin-download-manager)](https://github.com/tasirin1/tasirin-download-manager/releases)

<p align="center"><b>&#127760; Bahasa: <a href="README.md">Indonesia</a> &middot; <a href="README.en.md">English</a> &middot; <a href="CHANGELOG.md">Changelog</a></b></p>

**Satu aplikasi untuk semua kebutuhan file di perangkat Android:** unduh cepat, kelola dari browser lewat jaringan Wi-Fi, jelajah file, mainkan galeri ala YouTube, dan pantau semuanya secara realtime — cocok dipakai di HP maupun TV box (Android 5.0+ / API 21+).

Dibangun dengan **Kotlin + Jetpack**, tanpa iklan, tanpa akun. Kode terbuka di GitHub dan setiap pembaruan otomatis di-build menjadi APK siap pasang.

> **Untuk AI yang mengelola repo ini: baca [AGENTS.md](AGENTS.md) dulu** — berisi
> struktur, arsitektur, aturan pengembangan, dan alur build/release. AI wajib
> membacanya sebelum mengubah atau memelihara kode.

## Daftar isi

- [Screenshot](#screenshot)
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
- [Panduan pengelolaan untuk AI (AGENTS.md)](#panduan-pengelolaan-untuk-ai-agentsmd)
- [Lisensi](#lisensi)

## Screenshot

> Belum ada gambar terpasang — kirim tangkapan layar (remote web, galeri, daftar
> download) lalu taruh di `docs/screenshots/` dengan nama `remote-web.png`,
> `gallery.png`, `downloads.png` (atau beri tahu AI/contributor, gambar langsung
> dipasang di sini).

<p align="center">
  <img src="docs/screenshots/remote-web.png" alt="Remote web" width="280">
  <img src="docs/screenshots/gallery.png" alt="Galeri" width="280">
  <img src="docs/screenshots/downloads.png" alt="Daftar download" width="280">
</p>

## Fitur

| | |
|---|---|
| 🚀 **Manajer unduhan lengkap** | multi-segmen, resume dengan Range, antrean pintar, batas kecepatan, auto-retry |
| 📡 **Remote web realtime (SSE)** | kontrol dari browser perangkat lain, update langsung tanpa refresh manual |
| 🖥️ **Player video ala YouTube** | seekbar merah, double-tap ±10 detik, auto-next, tampilan ramah D-pad |
| 🗂️ **File manager remote** | jelajah, upload, hapus massal, ZIP folder, pratinjau media langsung |
| 🎬 **Galeri video perangkat** | thumbnail 16:9, durasi asli, lanjut putar, saran video |
| 📶 **Siap untuk jaringan pelan** | timeout connect/read bisa diatur, mirror otomatis, polling adaptif |
| 🔋 **Siap untuk TV box** | dukungan D-pad/remote, server jalan di background, auto-start saat boot |

Fitur unduhan:

- **Progress realtime**: persentase, ukuran, kecepatan KB/s–MB/s, **ETA stabil** (rata-rata bergerak, tidak melompat-lompat)
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
- Antarmuka terang yang konsisten; bahasa UI: Inggris

## Unduh

APK terbaru selalu tersedia di **GitHub Releases** — setiap push ke `main` langsung di-build dan rilis diperbarui otomatis:

**[⬇️ Unduh APK terbaru](https://github.com/tasirin1/tasirin-download-manager/releases/latest)**

APK release sudah **ditandatangani dengan kunci rilis resmi** (bukan debug), jadi lebih dipercaya Android/Play Protect. Pasang di HP / TV box (Android 5.0+), beri izin Penyimpanan saat diminta.

## Update otomatis APK

- App mengecek update ke **release repo ini** sendiri (`Updater.kt`): versi dibaca
  dari nama asset `tasirin-download-manager-v1.0-<code>.apk`, memilih kode
  tertinggi.
- Ada versi baru → dialog **Pembaruan tersedia** di Pengaturan → unduh APK ke
  folder **Downloads** (tanda tangan release diverifikasi) → **pasang manual**
  dari file manager. Aplikasi sengaja **tidak** meminta izin "install aplikasi
  lain" (`REQUEST_INSTALL_PACKAGES`) supaya profil risikonya lebih rendah bagi
  Play Protect.

## Play Protect memperingatkan "membahayakan data"

Ini **normal untuk aplikasi sideload** (bukan dari Play Store) yang meminta
"Access all files" (`MANAGE_EXTERNAL_STORAGE`) — dibutuhkan File Manager remote
dan akses path langsung. Plus aplikasi menjalankan server HTTP lokal yang bisa
mengunduh/mengunggah file, sehingga heuristik Play Protect menganggapnya
"berisiko terhadap data". Bukan malware:

- Scan **VirusTotal 0/75** untuk semua rilis terakhir (tautan report tampil di
  log build GitHub Actions).
- APK ditandatangani kunci rilis resmi; tanda tangan diverifikasi sebelum
  update dipakai.
- Tanpa izin "Access all files", download & galeri tetap berfungsi (via
  MediaStore); hanya File Manager remote yang butuh izin tersebut.

Saat instalasi muncul peringatan, pilih **Install anyway**. Izin "install
aplikasi lain" yang dulu ikut memicu peringatan sudah dihapus sejak rilis ini.

## Remote Web Realtime

Server remote bawaan berjalan **penuh di latar belakang** dan bisa **auto-start saat boot** — semua diatur di **⋮ → Pengaturan**.

1. Di Pengaturan, mulai server (atau biarkan menyala otomatis saat boot)
2. Scan **QR code**, atau buka `http://<ip-device>:<port>/` di browser perangkat lain
3. Masukkan **PIN** jika diatur

Fitur halaman remote:

- **Realtime via SSE**: progress & status datang langsung dari device tanpa refresh manual; fallback polling otomatis bila jaringan memblokir streaming
- **Polling adaptif**: 2 detik saat ada aktivitas, 10 detik saat idle — hemat baterai
- **Item aktif otomatis di urutan atas**, dengan kecepatan/ETA pada tiap item aktif
- **Tampilan mobile**: FAB "+", menu aksi ala bottom-sheet, filter sticky, skeleton loading, empty state informatif
- **Indikator koneksi**: "diperbarui X dtk lalu", titik status berkedip merah saat koneksi putus, refresh otomatis saat tab kembali fokus
- **Upload file & folder** dari browser: chunk 2 MB dengan retry (putus tidak mulai dari nol), drag & drop, nama duplikat otomatis, konfirmasi sebelum tab ditutup
- **File manager remote**: jelajah, buat folder, rename, pindah, hapus massal, **download folder sebagai ZIP**, breadcrumb, pratinjau media langsung
- **Galeri video ala YouTube**: thumbnail 16:9, durasi asli (cache di device), load bertahap, dan saran video
- **Bagikan file via tautan sementara** (berlaku 24 jam, tanpa PIN) + QR code
- **Streaming** file selesai (HTTP Range untuk video/audio) atau download langsung
- Pilihan port, server background, dan auto-start saat boot
- **Auto-lock**: halaman remote minta PIN lagi setelah 10 menit tanpa aktivitas

### Endpoint API (HttpControlServer)

| Endpoint | Fungsi |
|---|---|
| `/api/snapshot` | Status server & device (versi, port, ringkasan cepat) |
| `/api/events` | SSE — event realtime (download, log, galeri) |
| `/api/pin_enabled` | Apakah PIN diaktifkan |
| `/api/login`, `/api/logout` | Sesi PIN (token) |
| `/api/downloads` | Daftar download + status |
| `/api/add` | Tambah URL download |
| `/api/action` | Jeda/lanjut/batal/hapus/prioritas, dll. |
| `/api/fs`, `/api/fs_action` | Jelajah file & aksi (buat/hapus/rename/pindah) |
| `/api/fs_zip` | Unduh folder sebagai ZIP |
| `/api/upload`, `/api/upload_verify` | Upload chunk 2 MB + verifikasi |
| `/api/gallery`, `/api/media`, `/api/media_zip`, `/api/thumb` | Galeri video, streaming (Range), unduh batch ZIP, thumbnail |
| `/api/qr`, `/api/share` | Kode QR, tautan berbagi sementara (24 jam) |

## Player video ala YouTube

- Seekbar merah dengan status buffer, **double-tap ±10 detik**, kontrol ramah D-pad
- Kecepatan putar 0.5×–2×, lanjut dari posisi terakhir
- **Saran video** di bawah player, **AUTO (auto-next)** menyala otomatis

## Penyimpanan

Default file disimpan ke **Folder Downloads**. `minSdk 21` dipertahankan (Android 5+ tetap didukung), `targetSdk 36`. Android 5–10 memakai `WRITE_EXTERNAL_STORAGE` + legacy storage (akses penuh); Android 11+ memakai `MANAGE_EXTERNAL_STORAGE` ("Akses semua file").

- **Input path teks**: ketik path mentah seperti `/storage/emulated/0/Download` — folder otomatis dibuat kalau belum ada
- **Folder tambahan (mount)**: ketuk **+** untuk menambah path lain (mis. `/sdcard/Movies`) agar ikut tampil di file manager — cocok untuk folder buatan Total Commander, folder SD card, dll.
- Pilihan tersimpan otomatis dan persisten (bertahan setelah restart)

## Galeri

- Galeri remote difokuskan untuk **video**, sehingga layar lebih luas dan scan lebih hemat resource
- Tampilkan durasi video, thumbnail cepat, pemutar bawaan, dan saran video
- File yang masih diunduh tetap bisa diputar progresif dari daftar galeri

## Pengaturan

Menu **⋮ → Pengaturan**:

- **Unduhan**: resume otomatis, auto-start saat boot, unduhan bersamaan (1–5), jumlah segmen, batas kecepatan, percobaan ulang (0–5), unduh file kecil dulu, **timeout connect (5–60 dtk) & read (10–120 dtk)** untuk jaringan lambat/WISP
- **Server**: port, PIN, QR code, background + auto-start saat boot
- **Penyimpanan**: folder tujuan + folder tambahan
- **Galeri**: otomatis video-only; tidak ada konfigurasi folder foto/video terpisah
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

## Panduan pengelolaan untuk AI (AGENTS.md)

Panduan pengelolaan repo yang dulu ada di sini (struktur, arsitektur, aturan
pengembangan, cara memicu build & release, pipeline CI, secrets, file yang
disentuh per fitur, verifikasi build, dan peta jalan targetSdk 36) sudah
dipindahkan ke **[AGENTS.md](AGENTS.md)**.

> **Wajib untuk AI**: baca `AGENTS.md` sebelum mengubah, memperbaiki, atau
> memelihara repository ini — semua instruksi maintenance ada di sana.

## Lisensi

MIT — lihat [LICENSE](LICENSE).
