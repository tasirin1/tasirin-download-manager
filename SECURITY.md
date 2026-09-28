# Kebijakan Keamanan

## Versi yang didukung

Hanya rilis terbaru dari branch `main` yang menerima perbaikan keamanan.
Rilis lama dipertahankan sebagai riwayat, tetapi tidak di-patch.

| Versi | Didukung |
|---|---|
| Rilis terbaru (`releases/latest`) | Ya |
| Rilis lama | Tidak (perbarui dulu, lalu uji ulang) |

## Melaporkan kerentanan

Jangan buat issue publik untuk kerentanan keamanan. Gunakan fitur
**Private vulnerability reporting** di tab Security repository GitHub ini
(atau tautan di template issue bila tab tidak terlihat). Sertakan:

- versi APK atau commit yang diuji;
- langkah reproduksi singkat;
- dampak yang mungkin terjadi;
- proof-of-concept bila tersedia.

Laporan akan ditinjau dan pemilik repository akan menindaklanjuti lewat GitHub dalam waktu 7 hari kerja. Kerentanan kritis akan diprioritaskan.

## Cakupan

Termasuk namun tidak terbatas pada:

- bypass PIN, sesi remote, token media/share, atau kontrol akses file;
- path traversal pada file manager, upload, ZIP, atau stream;
- eksekusi kode, injeksi berbahaya, atau kebocoran data lokal;
- kerentanan pada endpoint HTTP server dan updater.

Di luar cakupan:

- serangan fisik setelah perangkat sudah root/tidak terkunci;
- peringatan Play Protect generik terhadap APK sideload tanpa bukti bypass
  (aplikasi memang memakai `MANAGE_EXTERNAL_STORAGE`; verifikasi lewat build CI);
- laporan tanpa dampak nyata terhadap aplikasi atau datanya;
- hasil pemindaian otomatis tanpa analisis dan langkah reproduksi.

## Praktik keamanan maintainer

- Jangan pernah menyimpan atau mengirim `keystore.jks`, password signing,
  personal access token, atau API key di issue/PR/chat.
- Simpan secret hanya di GitHub Actions Secrets atau password manager.
- Rotasi segera secret yang tidak sengaja terungkap dan cabang akses yang
  tidak lagi dipakai.
- Rilis resmi hanya dari workflow GitHub Actions dengan fingerprint
  certificate yang diverifikasi otomatis.
