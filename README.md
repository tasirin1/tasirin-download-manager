# Tasirin Download Manager (Android)

[![Build](https://github.com/tasirin1/tasirin-download-manager/actions/workflows/build.yml/badge.svg)](https://github.com/tasirin1/tasirin-download-manager/actions)
[![Release](https://img.shields.io/github/v/release/tasirin1/tasirin-download-manager)](https://github.com/tasirin1/tasirin-download-manager/releases)
[![Website](https://img.shields.io/badge/website-tasirin1.github.io/tasirin--download--manager-blue)](https://tasirin1.github.io/tasirin-download-manager/)

Aplikasi unduhan untuk Android dengan kontrol web realtime, file manager jarak jauh, dan galeri video. Dibuat untuk HP dan TV box, tanpa iklan dan tanpa akun.

**Bahasa:** [Indonesia](README.md) · [English](README.en.md) · [Changelog](CHANGELOG.md)

> **Untuk AI yang mengelola repository ini:** baca [AGENTS.md](AGENTS.md) sebagai panduan utama sebelum mengubah apa pun. [CONTRIBUTING.md](CONTRIBUTING.md) adalah ringkasan aturan untuk kontributor. Bagian "Struktur Repository" di bawah memberi peta file cepat.

## Fitur

- Multi-segmen, resume HTTP Range, antrean, retry otomatis, batas kecepatan, dan mirror.
- Remote web dengan pembaruan status langsung melalui SSE dan fallback polling.
- Upload file dari browser menggunakan chunk 2 MB yang dapat dilanjutkan.
- File manager: jelajah, unggah, buat folder, ubah nama, pindah, hapus, dan unduh ZIP.
- Galeri video dengan thumbnail, durasi, lanjut putar, saran video, dan pemutar ramah D-pad.
- Streaming media dengan dukungan Range serta tautan berbagi sementara.
- Berjalan di Android 5.0+ dengan foreground service dan opsi auto-start.

## Unduh APK

1. Buka [halaman rilis terbaru](https://github.com/tasirin1/tasirin-download-manager/releases/latest).
2. Unduh APK `tasirin-download-manager-v1.0-<code>.apk`.
3. Instal aplikasi dan berikan izin penyimpanan yang diminta.

APK dirilis lewat GitHub Actions dan ditandatangani dengan kunci rilis resmi. Pembaruan dalam aplikasi hanya mengunduh APK; instalasi tetap dilakukan pengguna.

## Remote Web

1. Buka **Settings → Remote (HTTP)**.
2. Jalankan server, lalu pindai QR code atau buka `http://<ip-perangkat>:<port>/`.
3. Masukkan PIN bila PIN remote diaktifkan.

Server hanya dimaksudkan untuk jaringan lokal. Gunakan PIN saat perangkat dipakai bersama atau Wi-Fi tidak terlindungi.

## Website

Halaman arahan publik terdeploy dari folder **`docs/`** pada branch `main`
melalui **GitHub Pages** → <https://tasirin1.github.io/tasirin-download-manager/>.

- **Sumber** website: `docs/index.html` (satu file HTML/CSS/JS).
- **Screenshot**: `docs/screenshots/`.
- Setiap perubahan `docs/` otomatis di-deploy ulang oleh *pages-build-deployment*.
- Website menampilkan versi/APK terbaru lewat GitHub Releases API — tidak perlu
  meng-edit detail versi secara manual di HTML.

## Struktur Repository

Peta cepat untuk pengelolaan dan debugging:

```
docs/index.html               Website GitHub Pages (satu file)
remote.src.html               Sumber readable remote web (jangan edit assets/remote.html)
assets/remote.html            Remote web minified (digenapi oleh scripts/prepare_remote.py)
app/src/main/java/...         Kode aplikasi Android (Kotlin)
scripts/                      Guard & utilitas: check_repo.py, prepare_remote.py, security_audit.py
CHANGELOG.md                  Riwayat perubahan per rilis (wajib update)
.github/workflows/            CI: build.yml, codeql.yml, gitleaks.yml, dll.
LICENSE / SECURITY.md         Lisensi MIT & kebijakan keamanan
```

Peta file yang lebih detail, arsitektur, keputusan historis, dan pola bug ada di
[AGENTS.md](AGENTS.md).

## Membangun & Rilis

Rilis resmi hanya dibuat melalui GitHub Actions — push ke `main` menjalankan
test, lint, build APK signed, dan me-refresh release `v1.0`. Jangan ubah
`versionName`/`versionCode` manual.

Untuk debug lokal:

```bash
./gradlew assembleDebug
```

Guard lokal sebelum commit:

```bash
python3 scripts/check_repo.py                 # satu pintu guard struktur + remote + README + audit
python3 scripts/security_audit.py --self-test # audit statis bug/error/keamanan
python3 scripts/prepare_remote.py --check     # sinkron remote.html + node check + i18n
node scripts/upload_smoke_test.js             # smoke test alur upload remote
```

## Keamanan & Privasi

- Tidak memerlukan akun dan tidak mengirim data ke server pengembang.
- Sesi remote dilindungi cookie acak; PIN disimpan sebagai hash PBKDF2.
- Path server dibatasi pada root yang sah, upload memiliki batas ukuran, dan token stream ditandatangani serta kedaluwarsa.
- CI menjalankan unit test, lint, CodeQL, Gitleaks, pemindaian internal, dan verifikasi tanda tangan APK.
- Play Protect dapat menampilkan peringatan pada aplikasi sideload dengan akses semua file karena model risikonya konservatif. Periksa build CI sebelum memasang APK.

Kerentanan keamanan dilaporkan privat — lihat [SECURITY.md](SECURITY.md).

## Lisensi

MIT — lihat [LICENSE](LICENSE).
