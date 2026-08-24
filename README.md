# Tasirin Download Manager (Android)

[![Build](https://github.com/tasirin1/tasirin-download-manager/actions/workflows/build.yml/badge.svg)](https://github.com/tasirin1/tasirin-download-manager/actions)
[![Release](https://img.shields.io/github/v/release/tasirin1/tasirin-download-manager)](https://github.com/tasirin1/tasirin-download-manager/releases)

Aplikasi unduhan untuk Android dengan kontrol web realtime, file manager jarak jauh, dan galeri video. Dibuat untuk HP dan TV box, tanpa iklan dan tanpa akun.

**Bahasa:** [Indonesia](README.md) · [English](README.en.md) · [Changelog](CHANGELOG.md)

## Fitur

- Multi-segmen, resume HTTP Range, antrean, retry otomatis, batas kecepatan, dan mirror.
- Remote web dengan pembaruan status langsung melalui SSE dan fallback polling.
- Upload file dari browser menggunakan chunk 2 MB yang dapat dilanjutkan.
- File manager: jelajah, unggah, buat folder, ubah nama, pindah, hapus, dan unduh ZIP.
- Galeri video dengan thumbnail, durasi, lanjut putar, saran video, dan pemutar ramah D-pad.
- Streaming media dengan dukungan Range serta tautan berbagi sementara.
- Berjalan di Android 5.0+ dengan foreground service dan opsi auto-start.

## Unduh

1. Buka [halaman rilis terbaru](https://github.com/tasirin1/tasirin-download-manager/releases/latest).
2. Unduh APK `tasirin-download-manager-v1.0-<code>.apk`.
3. Instal aplikasi dan berikan izin penyimpanan yang diminta.

APK dirilis lewat GitHub Actions dan ditandatangani dengan kunci rilis resmi. Pembaruan dalam aplikasi hanya mengunduh APK; instalasi tetap dilakukan pengguna.

## Remote Web

1. Buka **Settings → Remote (HTTP)**.
2. Jalankan server, lalu pindai QR code atau buka `http://<ip-perangkat>:<port>/`.
3. Masukkan PIN bila PIN remote diaktifkan.

Server hanya dimaksudkan untuk jaringan lokal. Gunakan PIN saat perangkat dipakai bersama atau Wi-Fi tidak terlindungi.

## Keamanan & Privasi

- Tidak memerlukan akun dan tidak mengirim data ke server pengembang.
- Sesi remote dilindungi cookie acak; PIN disimpan sebagai hash PBKDF2.
- Path server dibatasi pada root yang sah, upload memiliki batas ukuran, dan token stream ditandatangani serta kedaluwarsa.
- CI menjalankan unit test, lint, CodeQL, Gitleaks, pemindaian internal, dan verifikasi tanda tangan APK.
- Play Protect dapat menampilkan peringatan pada aplikasi sideload dengan akses semua file karena model risikonya konservatif. Periksa build CI sebelum memasang APK.

## Build

Rilis resmi hanya dibuat melalui GitHub Actions:

```bash
# Push ke main akan menjalankan test, lint, build signed APK, dan release.
```

Untuk debug lokal:

```bash
./gradlew assembleDebug
```

Pemindaian statis dapat dijalankan sebelum commit:

```bash
python3 scripts/security_audit.py --self-test
python3 scripts/security_audit.py
python3 scripts/prepare_remote.py --check
node scripts/upload_smoke_test.js
```

## Lisensi

MIT — lihat [LICENSE](LICENSE).
