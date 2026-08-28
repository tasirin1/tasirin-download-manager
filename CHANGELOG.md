## [Unreleased]
- **Sinkron pemilihan resolusi media sosial remote web** — Tambahkan pemilihan resolusi/kualitas di halaman remote web Add Download (sama dengan aplikasi utama). Saat link YouTube terdeteksi: spinner resolusi tetap (Auto/1080p/720p/480p/360p/240p) dengan `preferredHeight` dikirim ke server. Untuk TikTok/Instagram/Twitter: opsi kualitas diambil dari `/api/social_options` endpoint baru dan URL/cookies dipakai langsung. Endpoint `/api/social_options` menangani YouTube (daftar statis) dan platform lain (ekstraksi via `SocialMediaExtractor`).
- **Fix pemilihan resolusi YouTube tidak berfungsi** — Bug: saat user memilih resolusi (mis. 240p), logika pemilihan memfilter FRAME-RATE tertinggi GLOBAL lebih dulu. Karena 720/1080 ber-frame 60 sedangkan 240/360/480 ber-frame 30, semua pilihan jatuh ke varian tertinggi (full). Menghapus filter FRAME-RATE pada jalur eksplisit sehingga tiap resolusi memakai varian AVC paling dekat dengan tinggi target (dengan fps aslinya).
- **Pemilihan resolusi YouTube 1080/720/480/360/240** — Saat link YouTube terdeteksi di dialog Add Download, spinner menampilkan daftar resolusi tetap (Auto/1080/720/480/360/240). Pilihan disimpan sebagai `preferredHeight` pada item download dan saat download HLS dimulai, engine memilih varian HLS yang paling mendekati tinggi target dari master playlist. Aplikasi tetap memakai varian AVC tanpa B-frame dan FRAME-RATE tertinggi di resolusi tersebut (mencegah frame drop). Playlist lain (TikTok/IG/Twitter) tetap memakai kualitas hasil ekstraksi.
- **Pemilihan resolusi media sosial di dialog Add Download** — Saat link video media sosial (TikTok/Instagram/YouTube/Twitter) terdeteksi di kolom URL, muncul spinner resolusi di bawah kolom link. User bisa memilih kualitas (HD/SD/Photo/Video, dst) atau "Auto (best)"; URL CDN hasil ekstraksi yang masih segar dipakai langsung beserta cookies, dengan fallback ke ekstraksi otomatis untuk Auto.
- **Fix frame drop video HLS YouTube** — Varian 480p (itag 231) ternyata VFR: YouTube membuang frame di bitrate rendah sehingga hasil download patah-patah/tidak sesuai video asli. Pemilihan varian kini memakai atribut `FRAME-RATE` (parsing baru di `HlsParser`) dan memilih varian AVC tanpa B-frame (`avc1.4D`) dengan FRAME-RATE tertinggi — kembali ke 720p/60fps (itag 311) yang mempertahankan seluruh frame asli. Timeline video tetap deterministik (durasi `#EXTINF` dibagi rata per frame) sehingga sinkron dan lancar; fallback PTS-with-drift untuk playlist tanpa EXTINF.
- **Fix YouTube video fps/blur** — Filter pemilihan varian HLS memakai tinggi resolusi (`≤1080p`) sehingga mengeksklusi video portrait 720×1280 dan downloader jatuh ke itag 229 (240p, 291 kbps ≈ 4 fps). Ganti filter berbasis bandwidth ≤ 6 Mbps + AVC `avc1.4D` (tanpa B-frame) → pilih itag 311 (720p, 3.79 Mbps, 60 fps) sehingga hasil video jauh lebih mulus dan tajam. Hapus dead code `variantHeight`; tambah log `HLS plan:` untuk varian terpilih.
- **Fix HLS tanpa suara (lanjutan)** — `MediaExtractor` tidak dapat menulis file ADTS langsung ke MP4 (format `is-adts`/sample ber-header). Audio sekarang ditulis ke muxer secara manual dari frame AAC murni hasil `AdtsAac.parse`, dengan PTS kontinu (1024 sampel/frame), jadi track audio di MP4 terbentuk deterministik. Video MPEG-TS toleran terhadap PTS yang restart di tiap segmen (YouTube me-reset PTS): drift ditambah otomatis sehingga `MediaMuxer` tidak menolak PTS menurun. Perbaiki juga buffer `readSampleData`/`writeSampleData` (set `position`/`limit` ke ukuran sampel) agar MP4 tidak korup/gemuk. Tambah log `HLS:` untuk diagnosa jumlah segmen & hasil remux.
- **Fix Instagram reel jadi gambar** — Regex ekstraksi `video_versions` memakai double-backslash sehingga URL video `.mp4` reel tidak pernah cocok dan fallback ke foto. Ganti ke `\.mp4` tunggal, reel kini terdownload sebagai video.
- **Bersihkan warning build** — Empty catch `kotlin_empty_catch` di `SocialMediaExtractor` (beri komentar dokumentatif); `PackageInfo.versionCode` deprecated diganti helper `versionCodeCompat()` (longVersionCode API 28+); `session.parms` deprecated NanoHTTPD diganti helper `param()` berbasis `getParameters()` (24 titik); `GET_SIGNATURES`/`signatures` di `Updater` di-suppress (dibutuhkan Android 5-8); nullable `SimpleDateFormat` di `CrashLog` diberi fallback; lint `UseKtx View.isVisible` di `MainActivity`; hapus import tak terpakai.

- **Fix HLS tanpa suara** — File `.ts` hasil download YouTube HLS (VISIONOS) berisi video saja karena master playlist memisahkan audio ke rendition `#EXT-X-MEDIA:TYPE=AUDIO`. Downloader kini juga mengunduh segmen audio (ADTS AAC, tag ID3 di-strip), lalu remux video (MPEG-TS AVC) + audio (ADTS AAC) jadi satu file **MP4** memakai `MediaExtractor`+`MediaMuxer` bawaan Android (tanpa ffmpeg, APK tetap kecil). Pemilihan varian AVC profil `avc1.4D` (tanpa B-frame) agar remux mulus; jika remux/audio gagal, tetap fallback ke video-only `.ts`. Tambah `HlsParser.parseAudioRenditions`, parser ADTS murni JVM (`AdtsAac`) + uji unit (PR #120).
- **Fix HLS resume & segmen stabil** — Item HLS (YouTube) yang resume setelah gagal di tengah tidak lagi mengunduh manifest sebagai file teks: URL manifest dideteksi (`isHlsManifestUrl`) dan dialihkan kembali ke downloader HLS. Tiap segmen `.ts` di-buffer di memori dan baru ditulis ke file setelah sukses, dengan retry sekali untuk error jaringan sementara (mis. Socket closed) — mencegah byte parsial korup di file gabungan dan mengembalikan laporan progres per detik.
- **Fix YouTube download (VISIONOS + HLS)** — URL googlevideo dari halaman WEB butuh `n`-signature (HTTP 403). Ganti ekstraksi utama pakai player API **VISIONOS** (dengan visitorData dari halaman) yang mengembalikan **URL HLS tanpa n-transform**. Downloader baru **HLS** mengunduh segmen `.ts` berisi video+audio gabungan lalu menggabungkannya jadi satu file `.ts` yang dapat diputar (tanpa ffmpeg). Tetap ada fallback Piped/Invidious + gagal cepat bila VISIONOS tidak tersedia. Tambah log `YT DEBUG` untuk tiap langkah.
- **Fix Instagram extraction 403/fail** — Kembalikan User-Agent Googlebot untuk halaman utama Instagram (Chrome mobile menyebabkan redirect ke login wall sehingga extraction kosong). Escape `&amp;` pada URL CDN scontent (tanpa unescape parameter query rusak → HTTP 403). Tambah `IG DEBUG`/`YT DEBUG` logging untuk tracing extraction.
- **Fix build social** — Tambah field `cookies` pada `SocialMediaExtractor.Result` (default empty) yang hilang saat refactor cookies. Menyelesaikan `Unresolved reference 'cookies'` di `DownloadEngine` sehingga build kembali sukses.
- **Fix Instagram carousel** — Post carousel (GraphSidecar) yang berisi video kini terdownload sebagai .mp4 (bukan gambar cover). Support `img_index` dari URL untuk memilih item spesifik di carousel. Scan semua children mencari video pertama bila index tidak ada. Raw string regex agar compile sukses; suppress EmptyCatchBlock lint; tambah IG DEBUG logging. **Ganti strategi extraction**: contextJSON di embed page sudah tidak tersedia (Instagram removed); sekarang pakai Googlebot UA di halaman utama untuk cari `video_versions` dan `display_url` di escaped JSON. **Fix gambar 403 + full resolusi**: `extractDisplayUrlFromPage` unescape JSON + regex `[^"]+` capture URL lengkap. Filter image cek extension sebelum `?` (bukan endswith). Prioritas: `ig_cache_key` tanpa stp (1440x1800 full) > `p1080x1080` > `s640x640`.
- **Fix social media CDN download** — Kembalikan cookies support untuk YouTube/Instagram extraction. Tambah debug logging. Cookies dari page request dikirim ke CDN saat download.
- **Revert Instagram UA** — Kembalikan User-Agent Instagram ke Googlebot karena browser UA menyebabkan extraction gagal.
- **Fix Instagram CDN 403** — Ganti User-Agent dari Googlebot ke browser Chrome untuk request halaman Instagram agar cookies yang diterima CDN kompatibel. Hapus fitur cookies yang tidak efektif.
- **Remove quality picker** — Hapus pemilihan resolusi saat download social media. CDN URLs (YouTube/Instagram) expire terlalu cepat saat user memilih kualitas, menyebabkan HTTP 403.
- **Fix social media CDN 403** — Extract cookies dari halaman YouTube/Instagram saat ekstraksi, lalu kirim cookies saat download dari CDN (googlevideo.com, scontent-cdninstagram.com).
- **Fix CDN HTTP 403** — Tambahkan `Origin` header untuk YouTube (`googlevideo.com`) dan Instagram (`cdninstagram.com`) agar CDN tidak menolak request.
- **Fix YouTube download 403** — Tambah Referer header `https://www.youtube.com/` saat download dari googlevideo.com agar HTTP 403 tidak muncul. Hapus prefix `YouTube_` ganda dari nama file.
- **Fix social quality picker build** — Perbaiki string template escaping (`${namePrefix}_wm`) di TikTok extractor supaya compile sukses.
- **Social media quality picker** — Saat download video dari TikTok/Instagram/YouTube/Twitter, muncul dialog pemilihan resolusi. TikTok: HD/SD/watermark. YouTube: semua opsi format. Instagram: foto/video. Twitter: video/foto.
- **Fix YouTube regex escape** — Perbaiki escape sequence di regex YouTube (`\.`, `\s`) supaya compile sukses.
- **YouTube download support** — Tambah ekstraksi URL direct YouTube via Piped API (youtube.com/shorts, youtube.com/watch, youtu.be). Pilih video stream resolusi tertinggi. Fallback ke beberapa instance Piped.
- **Fix brace error** — Perbaiki closing brace berlebih di StoragePrefs setelah penghapusan recent URLs.
- **Remove recent URLs** — Hapus fitur recent URLs dari dialog Add Download (termasuk UI, StoragePrefs functions, dan addRecentUrl di DownloadEngine). Dialog lebih ringkas.
- **Simplify Add Download dialog** — Sembunyikan semua field lanjutan (filename, username, password, headers, checksum, mirrors, speed limit, priority) di belakang toggle "Advanced ▶". Dialog hanya menampilkan URL input, info storage, info file, dan recent URLs. Toggle expand/collapse untuk akses field lanjutan.
- **Social media download (v2)** — Ekstrak direct URL dari TikTok (tikwm.com API), Instagram (Googlebot page → video_versions + ig_cache_key), Twitter/X (vxtwitter API). Instagram: prioritas ig_cache_key (full resolusi tanpa crop) > s640x640 > og:image. Twitter: support photo fallback. CDN URLs di-skip (bukan download halaman HTML). Aktif otomatis saat paste URL media sosial.



- **Fix syntax error** — Perbaiki char literal invalid `'\\'` di `isNameValid()` (`HttpControlServer.kt`) yang menyebabkan build gagal.

- **Optimasi kode** — Cache upload buffer per 10 detik; ekstrak helper `isNameValid()` & `closeConnection()`; `pruneCompletedUploads` pakai `minOrNull()` alih-alih `sortedDescending()` (hemat alokasi); `SseStream` pakai konstanta `TIMEOUT_SECONDS` (25 dtk); eliminasi assignment `User-Agent` redundan di `openAuthenticatedConnection`.

- **Landing page** - Tambah website download di `docs/index.html` untuk GitHub Pages: download button, fitur, FAQ, QR code, dark theme.

- **Perbaikan lint warning** - Tambah import KTX `SharedPreferences.edit` di `DownloadEngine`; ganti hardcoded string "User-Agent" dan "Or type custom User-Agent" dengan `@string` resource; gunakan KTX `SharedPreferences.edit` extension di `DownloadEngine`.

- **Fix MIME type mp4** - Ganti MIME type `.mp4` dari `audio/mp4` ke `video/mp4` di `MimeTypes.kt` — mencegah player video salah render di browser remote.
- **Hapus unused import** - Hapus `kotlinx.coroutines.flow.collect` yang tidak terpakai di `HttpControlServer.kt`.

- **Fix thread-safety bug** - Ganti `SimpleDateFormat` (tidak thread-safe) dengan `DateTimeFormatter` di `DownloadEngine` dan `LogActivity` — mencegah corrupt nama file saat download paralel.

- **UI video player** - Tambah bayangan pada tombol mute dan fullscreen; perpanjang bar volume (105px default, 135px hover).
- **Fix CrashLog race condition** - Tambah synchronized lock pada trim CrashLog mencegah corrupt saat dua thread crash bersamaan.
- **Fix resource leak** - Bungkus `serveMedia()` dan `serveFile()` dalam try-finally supaya InputStream tertutup bila `streamMedia()` lempar exception tak terduga.
- **Fix ZipCreator path** - `zipMedia()` pakai `trimEnd('/')` pada `relPath` supaya path entry ZIP tetap benar walau MediaStore berubah.
- **Fix cookie fallback** - `fallbackConn` di DownloadEngine sekarang menerapkan cookie dari CookieManager ke koneksi fallback (situs yang butuh session cookie).
- **Fix thread-safe prefs** - `cachedPrefs` di StoragePrefs ditambah `@Volatile` supaya aman diakses dari multiple thread di Android 5.


## [v1.0 — 2026-08-24] — Audit keep-alive body
### Fixed
- **Seek zen mode** — Sembunyikan tombol modal dan daftar video berikutnya selama dua-tap lompatan durasi.
- **Double-tap seek** — Sembunyikan seluruh overlay tombol pemutar selama indikator lompatan dua-tap tampil.
- **Player slider depth** — Tambahkan bayangan halus pada bar durasi dan volume agar terlihat jelas di atas video tanpa mengubah posisi kontrol.
- **MediaStore authorization** — Pisahkan izin baca galeri dari izin tulis/hapus dan wajibkan URI SAF masih berasal dari tree berizin.
- **Stale media access** — Validasi ulang path/URI saat membaca durasi video agar cache galeri lama tidak mengakses storage yang sudah tidak diizinkan.
- **Upload keep-alive** — Tutup koneksi saat upload ditolak sebelum body dibaca agar body besar tidak menahan thread atau meracuni koneksi.
- **ZIP disk limit** — Hentikan pembuatan ZIP melewati batas 256 MB dan bersihkan cache akses ketika root storage berubah.
- **Upload verification** — Wajibkan token bertanda tangan pada cek status upload dan redaksi token tersebut dari log agar ID upload tidak bisa dienumerasi.
- **Rename overwrite** — Tolak rename file/folder remote bila nama target sudah ada.
- **Browser detection** — Hapus tes `new Function` yang diblokir CSP sehingga browser modern tidak lagi salah ditandai terlalu tua.
- **AGP update** — Naikkan Android Gradle Plugin ke `9.3.2` dan segarkan metadata verifikasi dependensi.
- **Thumbnail authorization** — Validasi path/URI sebelum membaca cache thumbnail agar thumbnail lama tetap terkunci setelah konfigurasi akses storage berubah.
- **Remote hardening** — Tambah header `nosniff`, referrer, permissions, dan CSP pada halaman remote tanpa mengubah perilaku UI.
- **CSRF guard** — Wajibkan header khusus pada seluruh POST remote kecuali login agar form lintas situs tidak bisa memicu aksi unduhan/upload/file.
- **Video control surface** — Hapus latar pill, border, dan bayangan pada lapisan kontrol volume/durasi tanpa memindahkan tombol pemutar.
- **Seek preview** — Samakan geometri preview durasi dengan ukuran titik pemutar yang baru.
- **Thumbnail surface** — Batasi endpoint thumbnail ke video saja dan hapus decoder gambar sisa fitur foto.
- **Cache race** — Satukan pasangan signature/JSON unduhan agar request paralel tidak bisa membaca versi campuran.
- **Media cache invalidation** — Bersihkan cache MediaStore secara konsisten setelah file dipindah, dibuat, atau dihapus.
- **Log hygiene** — Redaksi ID upload pada log permintaan agar ID sementara tidak tersalin sebagai capability.
- **Video control size** — Perbesar tombol, bar durasi, dan bar volume secara proporsional agar target sentuh lebih nyaman.
- **Form POST besar** — Tolak body di atas 4 MB sebelum dibaca dan paksa koneksi ditutup agar sisa body tidak meracuni request berikutnya.
- **Login state** — Bersihkan entri throttle kedaluwarsa lebih awal agar percobaan login dari banyak IP tidak menetap sampai batas 512 entri.
- **Download memory** — Hapus data pelacak kecepatan saat daftar unduhan selesai dibersihkan.
- **Remote session cache** — Batasi posisi scroll dan tanda file baru agar sesi panjang tidak menumpuk objek DOM state.
- **Redirect credentials** — Gunakan redirect manual dan hanya kirim ulang kredensial pada origin yang sama.
- **MediaStore access** — Tolak URI MediaStore tanpa relative path di Android 10+ sebagai kegagalan aman.
- **ZIP entry names** — Normalisasi nama entri ZIP untuk menahan Zip Slip di extractor pihak ketiga.
- **Observer thread** — Ganti HandlerThread permanen dengan handler utama untuk invalidasi scan galeri.
- **Documentation** — Ringkas README Indonesia/Inggris menjadi panduan pengguna yang profesional.
- **Lint hygiene** — Rapikan blok dan indentasi pembuatan entri ZIP tanpa mengubah perilaku.
- **Volume control** — Perbarui bar volume pemutar video agar melebar halus saat hover/fokus dan tetap terlihat di layar sentuh.
- **Video controls** — Rombak tata letak pemutar ke gaya panel One UI dengan seekbar terangkat, waktu terpisah, aksi sekunder rapi, dan aksen biru Samsung.
- **Playback controls** — Pindahkan tombol sebelumnya/pause/berikutnya ke tengah video, hapus overlay besar duplikat, dan bersihkan fitur kecepatan serta auto-play.
- **Transparent controls** — Hapus latar gelap kontrol pemutar, perkecil ukuran tombol, dan lebarkan bar volume pada desktop maupun layar sentuh.
- **Seek accuracy** — Samakan lebar progres/buffer dengan lintasan thumb durasi, perbaiki offset preview waktu, dan tambah panjang bar volume.
- **Clean sliders** — Hapus jejak putih pada track sisa bar durasi, hilangkan indikator buffer putih, dan buat track sisa volume transparan.
- **Maintenance system** — Tambahkan pintu pemeriksaan repo terpusat, CODEOWNERS, kebijakan keamanan, standar editor, dan audit otomatis asset rilis di CI.
- **Slider clarity** — Kembalikan track putih durasi/volume dan hapus lapisan glow atau ring tambahan yang tampak sebagai garis pembatas tipis.
- **Seek alignment** — Gunakan titik durasi kustom dari variabel progres yang sama dengan bar biru agar posisi thumb tidak meleset di WebView berbeda.
## [v1.0 — 2026-08-24] — Audit agresif traversal dan race
- **ZIP symlink traversal** — Rekursi folder kini memvalidasi ulang setiap child terhadap root sah sehingga symlink keluar root tidak ikut diarsipkan.
- **Stale file access** — Endpoint file dan share memvalidasi ulang path/URI lama setelah konfigurasi akses storage berubah.
- **Logout DoS** — Logout hanya bisa dijalankan sesi ber-PIN yang valid; pengunjung tanpa sesi tidak lagi bisa memaksa rotasi cookie.
- **Upload resource cap** — Batasi jumlah lock upload aktif dan finalisasi paralel untuk menahan lonjakan request di perangkat RAM kecil.
- **Download queue race** — Pemeriksaan batas unduhan paralel dipindah ke dalam sinkronisasi peluncuran job.
- **Segment progress leak** — Progres segmen dibersihkan saat pause/resume agar tidak menyisa atau menulis status basi.
- **File Manager ordering** — Respons navigasi/load-more lama dibuang bila pengguna sudah berpindah folder.
## [v1.0 — 2026-08-24] — Audit resource dan ZIP token
- **ZIP authorization** — Validasi ulang semua token media pada endpoint batch ZIP agar path/URI buatan tidak bisa melewati root yang diizinkan.
- **Share limits** — Batasi jumlah tautan berbagi aktif dan kunci pembaruannya untuk mencegah penumpukan token.
- **Thumbnail locks** — Evict lock thumbnail yang tidak aktif supaya browsing galeri besar tidak menambah penggunaan RAM terus-menerus.
- **Gallery requests** — Cegah scroll memicu banyak request load-more paralel di remote web.
- **Failed URL cache** — Batasi daftar URL gagal per sesi agar banyak item gagal tidak tumbuh tanpa batas.
## [v1.0 — 2026-08-24] — System audit bug dan keamanan
### Added
- **Security/error scanner** — Tambahkan audit statis internal dengan self-test, output JSON/CI, suppression eksplisit, dan aturan untuk secret, logging sensitif, WebView, SQL/path sink, JavaScript berbahaya, dan penanda maintenance.
- **Scanner hygiene** — Tandai contoh aturan dan fixture self-test sebagai pengecualian agar scanner tidak melaporkan dirinya sendiri.
- **CI guard** — Jalankan scanner otomatis pada push/PR sebelum build Android, serta di optional pre-commit hook.
- **Thread interruption** — Kembalikan status interrupt setelah retry server tertidur dan dibatalkan.
- **Null safety** — Hilangkan assertion tidak perlu pada cache thumbnail, keystore signing, secret sesi, storage prefs, dan startup exception.
## [v1.0 — 2026-08-24] — Perbaikan efisiensi dan audit lanjutan
- **Remote disk safety** — Batasi cache ZIP folder berdasarkan jumlah dan total ukuran agar browsing folder tidak menumpuk file sementara.
- **Download header** — Tambahkan fallback RFC 5987 pada nama file non-ASCII supaya unduhan tidak rusak namanya di browser.
- **QR endpoint** — Batasi panjang teks QR untuk mencegah pemakaian CPU/memori berlebih dari input eksternal.
- **UI thread** — Pindahkan rename download dan pemberhentian server saat PIN dihapus ke background thread.
- **Persistensi** — Debounce penyimpanan status pause/clear agar tombol batch tidak menulis disk berkali-kali.
- **Video resume storage** — Batasi jumlah posisi video yang disimpan di browser dan gunakan ID upload acak dengan fallback WebView lama.
- **Cache eviction** — Perbaiki penghapusan ZIP tertua agar kompatibel dengan compiler Kotlin yang dipakai CI.
## [v1.0 — 2026-08-24] — Penguatan audit keamanan lanjutan
- **PIN hardening** — Simpan PIN baru sebagai PBKDF2-SHA1 bersalt dengan 100.000 iterasi, dan migrasikan hash lama setelah login berhasil.
- **Remote session** — Ganti cookie login dari hash PIN menjadi token sesi acak 256-bit, dengan rotasi saat logout atau PIN berubah.
- **MediaStore boundary** — Listing, ZIP, upload/download, dan pemindahan `m:` kini tunduk pada root folder serta mode akses penuh.
- **Upload buffer race** — Reservasi byte chunk secara global agar banyak upload paralel tidak melewati batas disk/cache.
- **Upload chunk validation** — Tolak nomor chunk/jumlah chunk di luar rentang yang sah.
- **Share token entropy** — Naikkan token berbagi menjadi 128-bit penuh.
- **Update hardening** — Wajibkan ukuran APK yang valid, batas 100 MB, dan hentikan unduhan bila byte meluber.
- **Backup surface** — Matikan Android backup karena prefs menyimpan konfigurasi server dan secret lokal.
- **Legacy backup** — Matikan backup content di Android 5–11 dan transfer/cloud extraction di Android 12+.
- **CodeQL PendingIntent** — Kunci semua notification intent ke package aplikasi.
- **Audit test** — Jadikan base MediaStore dapat diinjeksi agar guard bisa diuji lintas lingkungan.
## [v1.0 — 2026-08-24] — Perbaikan audit keamanan
- **Build audit** — Tambahkan impor `FileNames` yang hilang pada engine unduhan.
- **Audit test** — Perbaiki ekspektasi sanitasi nama agar sesuai hasil aman tanpa separator jalur.
- **Upload path traversal** — Validasi ID upload menjadi token ketat agar file sementara tidak bisa ditulis keluar cache melalui `../`.
- **Partial stream secret** — Ganti secret token yang bisa ditebak dengan secret acak 256-bit yang disimpan lokal.
- **Download filename** — Sanitasi nama kustom sejak item dibuat dan validasi ulang file parsial sebagai defense in depth.
- **Log injection** — Normalisasi control character pada log server agar parameter tidak bisa membuat baris log palsu.
- **Updater transport** — Wajibkan HTTPS pada URL APK dan redirect update.
- **Remote XSS hardening** — Escape teks empty-state dan tambahkan `noopener,noreferrer` pada tab eksternal.
- **Upload exhaustion** — Batasi total file upload sementara berdasarkan buffer dan ruang kosong yang tersedia.
- **Legacy filename safety** — Validasi ulang nama lama pada stream parsial dan operasi pindah file.
## [v1.0 — 2026-08-24] — Samakan gaya pemutar dengan YouTube
### Changed
- **Player controls** — Ganti panel membulat menjadi overlay gradien transparan dengan tombol bulat ala YouTube.
- **Progress layout** — Pindahkan bar progress merah ke tepi bawah secara full-width, sementara volume kembali berdampingan dengan tombol suara.
## [v1.0 — 2026-08-24] — Tampilkan volume pemutar biasa
- **Player volume** — Slider volume tidak lagi hilang pada mode non-fullscreen dan kini selalu tersedia di samping bar progress.
- **Volume feedback** — Tambahkan indikator isi slider dan dukungan gaya Firefox tanpa mengubah kontrol keyboard.
- **Control hierarchy** — Pisahkan volume dari tombol utama agar baris kontrol lebih lega di layar kecil dan TV.
## [v1.0 — 2026-08-24] — Rapikan kontrol pemutar video
- **Control layout** — Jadikan tombol dan bar progress panel bawah yang lebih terstruktur, dengan target sentuh lebih stabil dan waktu video memakai ruang fleksibel.
- **Progress slider** — Perbesar area geser, pertegas posisi putar, dan tambahkan gaya Firefox tanpa mengubah perilaku pemutar.
## [v1.0 — 2026-08-22] — Perbaikan hasil audit
- **Download queue** — Cegah race kecil pada start/complete job sehingga slot antre tidak bisa tergantung atau dobel.
- **Server lifecycle** — Hentikan auto-heal pool statistik saat server sedang di-stop agar thread tidak hidup ulang tanpa perlu.
- **Remote actions** — Aksi batch File Manager berhenti saat satu operasi gagal dan menyegarkan daftar sesuai kondisi nyata.
- **SSE fallback** — Perbaiki state reconnect sekali saat stream diam, termasuk grace window sebelum EventSource ditutup.
- **Upload retry** — Reset baseline progres agregat saat file diulang dari awal agar persentase tidak macet/mundur palsu.
- **ZIP efficiency** — Serialisasi pembuatan ZIP per selection key untuk beberapa request Range paralel.
- **List rendering** — Perbarui pesan error item bila teks error berubah tanpa mengubah state.
- **Gallery freshness** — Validasi path fisik entri MediaStore agar video yang sudah dihapus atau dipindahkan tidak lagi muncul sebagai item mati.
- **Server lifecycle** — Cegah start/stop ganda sehingga restart background tidak menimbulkan race pool/socket.
- **Compile safety** — Hilangkan overload helper thumbnail dan perbaiki rethrow pembatalan coroutine.
- **Startup performance** — Pindahkan scan orphan dan start server dari main thread ke background.
- **Batch actions** — Pause All kini satu update state/simpanan; delete/cancel file dari UI berjalan di IO dispatcher.
- **Server efficiency** — Cache listing folder pagination, filter log request rutin media, buat SSE catch-up tiap detik, dan satukan generator thumbnail galeri/remote.
## [v1.0 — 2026-08-22] — Perbarui panduan repo
- **AI guide** — Sinkronkan `AGENTS.md` dengan perilaku aktif: galeri video-only, updater download-only, kunci settings terkini, token stream parsial, throttle login per-IP, upload serial per-ID, keamanan tujuan tulis, merge staging, reset resume ETag, dan lock scan galeri.
- **Documentation** — Rapikan README Indonesia/Inggris, kontribusi, dan panduan screenshot dari fitur yang sudah dihapus (filter foto, status baterai/kecepatan global, folder galeri terpisah, gesture brightness/volume).
## [v1.0 — 2026-08-22] — Perbarui profil aplikasi
- **About screen** — Ganti teks informal dengan ringkasan profesional: developer, tujuan produk, platform, kontrol remote, keamanan, performa, penyimpanan, dan transparansi sideload.
- **Documentation** — Sinkronkan fitur aktif di README Indonesia/Inggris: galeri kini video-only, player tanpa gesture brightness/volume, tema terang konsisten, dan endpoint hapus media galeri tidak lagi disebut.
## [v1.0 — 2026-08-22] — Perbaikan keamanan, sinkronisasi, dan lifecycle
- **Remote destination** — Validasi semua tujuan download/upload terhadap root yang diizinkan, termasuk path `f:`, root `m:`, dan relative path `m:`.
- **Segment connections** — Pause/cancel/hapus kini menutup semua koneksi segmen aktif, bukan hanya koneksi terakhir.
- **Merge safety** — Gabungan segmen ditulis ke file staging dulu, difinalisasi kompatibel Android 5, lalu bagian parsial dihapus setelah berhasil.
- **Resume validation** — Resume di-reset bila ETag resource berubah agar file tidak tercampur versi lama dan baru.
- **Upload integrity** — Chunk upload wajib memakai ID, diserialisasi per-ID, dan rentang offset+ukuran divalidasi.
- **Login/session hardening** — Throttle login per IP dengan map terbatas; cookie PIN memakai `HttpOnly`/`SameSite`; logout jadi POST.
- **Server responses** — Sembunyikan pesan exception internal dari HTTP 500 dan redact token/PIN pada log request.
- **Partial stream access** — Endpoint `/stream_part/` kini memakai token lokal bertanda tangan berumur pendek.
- **Gallery & actions** — CTA galeri kosong selalu aktif, aksi remote menampilkan error nyata, dan form tidak dibersihkan saat gagal.
- **Media scan race** — Cache scan MediaStore dan invalidasinya disinkronkan untuk mencegah scan paralel duplikat.
- **Settings lifecycle** — Port tidak valid menghentikan penyimpanan lebih awal, port pembanding memakai nilai tersimpan, dan operasi server keluar dari main thread.
- **Open folder fallback** — Coba intent file manager secara langsung sebelum membuka aplikasi Downloads sistem.
- **Compile follow-up** — Perbaiki urutan status server pada toggle Settings referensi utilitas pembanding token, dan kurung unit test keamanan.
## [v1.0 — 2026-08-22] — Perbaiki tampilan pemutar video
- **Adaptive player** — Video portrait/4:3 memakai rasio asli dan area player dibatasi di layar pendek agar judul serta saran video tetap terlihat.
- **Viewport & safe-area** — Modal memakai dynamic viewport; overlay atas/bawah menghormati notch dan gesture bar.
- **D-pad aksesibilitas** — Baris saran video menjadi tombol fokusabel dengan fokus terlihat, dan shortcut keyboard tidak menimpa aktivasi tombol.
- **Playlist context** — Klik saran dan auto-next tetap memakai playlist Gallery/File Manager yang aktif.
- **Up next order** — Saran dimulai dari video berikutnya sebelum melengkapi video sebelumnya.
- **Control layout** — Waktu pemutar tidak menyusut sampai hilang pada layar sempit.
- **Fullscreen orientation** — Lock landscape dipanggil setelah fullscreen benar-benar aktif.
## [v1.0 — 2026-08-21] — Stabilkan tombol pemutar video
- **Player controls** — Cegah klik close membocorkan event play ke player, duplikasi sentuhan di WebView lama, dan konflik shortcut keyboard saat tombol pemutar sedang fokus.
- **Playlist konteks** — Pisahkan urutan video Gallery dan File Manager sehingga next/prev serta auto-next tidak melompat ke galeri yang tidak berhubungan.
- **Mute state** — Perbaiki ikon mute dan sinkronkan tampilannya dengan volume nol.
- **Navigasi video** — Nonaktifkan prev/next di batas playlist dan tambahkan fallback fullscreen untuk browser terbatas.
- **Control layout** — Perbesar target seek, cegah overflow kontrol di layar sempit, tampilkan fokus D-pad dengan jelas, stabilkan lebar tombol speed, dan hormati safe-area.
## [v1.0 — 2026-08-21] — Optimasi galeri, streaming, dan realtime
- **Video-only gallery** — Scan native/remote hanya memuat video; permintaan foto langsung kosong tanpa query MediaStore.
- **Media range seek** — Metadata nama/MIME di-cache dan stream file/MediaStore diposisikan langsung ke offset Range, tanpa membaca ulang byte awal.
- **Thumbnail dedup** — Permintaan thumbnail untuk media sama berbagi lock sehingga tidak decode paralel berulang.
- **Gallery progress** — Perubahan persentase cukup merge di memori; scan ulang hanya saat daftar download aktif berubah.
- **Remote DOM** — Upload progress dibatasi ~100 ms, baris download di-patch langsung, dan seleksi galeri memakai `Set`.
- **Speed limiter** — Total byte multi-segmen disimpan pada atomic counter, bukan dijumlahkan ulang tiap chunk.
- **Server log** — Tambah revision cache agar polling log tidak join ulang 300 baris bila tidak ada log baru.
- **SSE signature** — Sertakan nama, total, progress, limit, waktu selesai, checksum, dan error agar cache JSON tidak menyajikan kolom basi.
- **Compile fix** — Tambah import `DownloadItem` dan konversi byte segmen ke `Long` untuk counter speed limiter.
## [v1.0 — 2026-08-21] — Optimasi ukuran APK
- **APK size** — Kecualikan metadata `.kotlin_builtins` dan `DebugProbesKt.bin` yang tidak dibaca runtime untuk mengurangi ukuran APK tanpa mengubah fitur.
## [v1.0 — 2026-08-21] — Efisiensi remote & I/O background
- **Remote theme cleanup** — Hapus sisa tema gelap, tombol tema, dan state `dm_theme` agar UI selalu terang sesuai keputusan desain.
- **Realtime route** — SSE kini hanya rebuild daftar download saat tab Downloads aktif; tab lain cukup update toolbar/selection.
- **Main-thread I/O** — Pindahkan cleanup junk, export log, cek update, unduh update, verifikasi APK, dan simpan APK ke background thread.
- **Update progress** — Throttle refresh progress minimal 100 ms untuk mengurangi update UI berlebihan.
- **Upload text** — Samakan teks status retry upload ke Bahasa Inggris.
- **Compile fix** — Gunakan qualifier Activity dan tipe resource eksplisit pada toast ekspor log.
## [v1.0 — 2026-08-21] — Fix open folder untuk file selesai
- **Open Folder** — Tambah fallback chain: DocumentsContract → File URI → Downloads app bawaan. Sebelumnya hanya 1 intent tanpa `resolveActivity()` check, jadi gagal di Android TV box yang tidak punya DocumentsUI.
## [v1.0 — 2026-08-20] — Fix lint warnings + update Gradle
- **Lint warnings** — `UnusedAttribute`: ubah `tools:targetApi` dari "23" ke "24" di manifest (networkSecurityConfig API 24+). `InsecureBaseConfiguration`: tambah `tools:ignore` di network_security_config.xml (cleartext memang diperlukan untuk download manager).
- **Gradle wrapper** — Update dari 9.7.0 ke 9.7.1 (patch update).
## [v1.0 — 2026-08-20] — Fix statPool ThreadPoolExecutor crash + unused import cleanup
- **ThreadPoolExecutor crash** — Kembalikan `@Volatile` pada `statPool` (dihapus sebelumnya, padahal diperlukan untuk thread safety). Tambah `runCatching` di `RejectedExecutionHandler` supaya exception tidak crash app saat pool di-shutdown bersamaan. Tambah reset pool di `startServer()` bila pool terminated (stop/start server).
- **Unused import** — Hapus `import android.graphics.BitmapFactory` dari `HttpControlServer.kt` (kode pakai FQN `android.graphics.BitmapFactory.Options()`).
## [v1.0 — 2026-08-20] — Fix compile errors in safeRun + ServerThumbnail
- **Compile** — `logError` accepts `Throwable` (was `Exception`); `safeRun` callers use `?:` instead of `.getOrElse`; added `toUri` import to `ServerThumbnail.kt`; fixed `return@safeRun` in crossinline lambda.
## [v1.0 — 2026-08-20] — Extract ServerThumbnail, safeRun logging, gallery progress, fsRoots cache fix
### Refactored
- **ServerThumbnail.kt** — Extract thumbnail functions (`getOrCreateThumb`, `generateThumb`, `videoThumb`, `imageThumb`) dari `HttpControlServer.kt` ke file terpisah.
- **silent runCatching** — Tambah `safeRun()` helper: error otomatis logged (sebelumnya hilang diam-diam).
- **Gallery upload progress** — Gallery sekarang `collect` download flow → progress update real-time saat upload via remote web.
- **cachedFsRoots invalidation** — Settings save sekarang panggil `invalidateFsRootsCache()` + `invalidateStatusCache()` (sebelumnya file manager pakai cache lama).
## [v1.0 — 2026-08-20] — Fix ThreadPool crash, video read-ahead, gallery scan efficiency
- **ThreadPool crash** — `rejectedExecutionHandler` sekarang re-submit task gagal ke pool baru (sebelumnya task hilang → HTTP 500). Hapus `@Volatile` dari `statPool` (akses selalu via `@Synchronized`).
- **Gallery scan berulang** — Tambah debounce ContentObserver dari 3 detik ke 10 detik supaya scan tidak berulang tiap perubahan media kecil.
- **Video player boros request** — Tambah read-ahead buffering di server: chunk < 512 KB diperbesar otomatis (max 2 MB) supaya browser tidak langsung minta lagi → jumlah HTTP range request berkurang signifikan.
### Existing
- **Batch operations File Manager** — Tombol Download, Move, Delete sudah ada di mode Select.
## [v1.0 — 2026-08-20] — Remove unused imports and dead string
### Removed
- Unused `SuppressLint` and `App` imports from `MediaLibrary.kt` (leftover from gallery folder removal).
- Dead string resource `filter_videos` (gallery filter removed earlier).
## [v1.0 — 2026-08-19] — Add Stream button to File Manager
- **Stream button** — Tombol ▶ di setiap baris file di File Manager. Sekali klik membuka file di tab baru: foto ditampilkan browser, video/audio pakai player. Hanya muncul untuk file (bukan folder), tersembunyi saat mode Select.
- **CSS `.fs-stream-btn`** — Tombol bulat 36px, warna abu-abu, hover biru (konsisten dengan tombol actions).
## [v1.0 — 2026-08-19] — Remove all dead code: photo viewer remnants, gallery settings, deleteMedia
### Dihapus
- **Gallery settings section** — Hapus `section_gallery.xml` + tombol nav Gallery di Settings. Gallery tidak punya pengaturan folder lagi.
- **Gallery filter bar** — Hapus filter All/Photos/Videos dari `activity_gallery.xml` + `GalleryFilter` enum dari `GalleryActivity`.
- **`confirmDelete`** — Hapus fungsi delete file dari galeri (tidak ada backend).
- **`galleryDir` / `mediaInFolder`** — Hapus dari `MediaLibrary` (tidak dipanggil).
- **String resources** — Hapus `filter_images`, `gallery_delete_*`, `settings_gallery_*`, `settings_section_gallery`.
- **`onLongClick`** — Hapus dari `GalleryAdapter` (tidak ada aksi delete).
- **Import `MediaStore`** — Hapus dari `DownloadEngine` (tidak dipakai).
## [v1.0 — 2026-08-19] — Remove dead code: photo folder settings, deleteMedia, IMAGE filter
- **Photo gallery folder setting** — Hapus `input_gallery_image` dari SettingsActivity + layout `section_gallery.xml`. Hapus `wireGallerySection()`, `applyGalleryFolders()`, `getGalleryImageFolder()`, `setGalleryImageFolder()`.
- **Video gallery folder setting** — Hapus `input_gallery_video` dari SettingsActivity. Hapus `getGalleryVideoFolder()`, `setGalleryVideoFolder()`. Gallery scan tidak lagi difilter per folder.
- **`/api/delete_media` endpoint** — Hapus endpoint + `deleteMedia()` function dari HttpControlServer.
- **`deleteMedia()` di DownloadEngine** — Tidak dipanggil dari mana pun.
- **`GalleryFilter.IMAGE`** — Hapus filter IMAGE dari GalleryActivity (hanya ALL + VIDEO).
- **String resource** — Hapus `settings_gallery_image_label`.
## [v1.0 — 2026-08-19] — Remove all gestures except double-tap
- **Swipe gesture** — Geser kiri/kanan untuk ganti video dihapus.
- **Brightness/volume gesture** — Sudah dihapus sebelumnya, sekarang variable `mmGesture` juga dihapus.
- **`mmLastTapX`** — Variable tracking posisi X untuk swipe dihapus.
### Dipertahankan
- **Double-tap** — Kiri: -10s, Kanan: +10s, Tengah: toggle play.
- **Single-tap** — Tengah: toggle play/pause, Samping: show/hide controls.
- **Mouse double-click** — Fullscreen.
## [v1.0 — 2026-08-19] — Fix unhandled Promise rejections in doFsOp
### Diperbaiki
- **Unhandled Promise rejection** — `postFsAction().then(loadFs)` di `doFsOp()` tidak punya `.catch()`. Saat network error atau server down, error tidak ditangkap. Fix: tambah `.catch()` dengan `fsMsg()` error display.
## [v1.0 — 2026-08-19] — Fix video player layout: title/date sticky below player
- **Judul/tanggal di bawah saran video** — `mmDesc` (judul/tanggal) dan `mmVideoWrap` (pemain video) dipindahkan ke luar `mmBody` sebagai sibling langsung `#mediaModal`. Struktur baru: `mmTop → mmVideoWrap → mmDesc → mmBody(related only)`. Pemain video + judul/tanggal tetap terkunci, hanya saran video yang bisa di-scroll.
- **Hapus `mmVideoHeader`** — Wrapper yang tidak diperlukan lagi setelah restruktur.
## [v1.0 — 2026-08-19] — Gallery: remove search/filter + 3-column grid
- **Search & filter galeri** — Hapus bar search, tombol filter, dan related JS. Galeri hanya menampilkan video tanpa pencarian/filter.
### Diubah
- **Grid galeri 3 kolom** — Ubah dari `auto-fill, minmax(170px)` ke `repeat(3, 1fr)` untuk tampilan lebih luas.
## [v1.0 — 2026-08-19] — Fix video title sticky + gallery toolbar leak
- **Judul/tanggal video ikut scroll** — `#mmDesc` berada di dalam `#mmBody` (scrollable). Fix: pindahkan `#mmDesc` ke luar `#mmBody` sehingga tetap terkunci di bawah pemutar video saat related videos di-scroll.
- **Tombol toolbar muncul di galeri** — `render()` menampilkan `downloadsToolbar` berdasarkan jumlah download tanpa memeriksa route aktif. Fix: tambah pengecekan `currentRoute() === 'downloads'` sebelum menampilkan toolbar.
## [v1.0 — 2026-08-19] — Fix unhandled async errors in remote web
- **Async error handling** — `postFsAction()` dan `doActionNow()` tidak punya try-catch. Saat network error atau server down, unhandled rejection terjadi tanpa feedback ke user. Fix: tambah try-catch + `fsMsg()` error display.
## [v1.0 — 2026-08-19] — Fix video player layout: restore missing CSS
- **Pemain video berantakan** — CSS untuk `#mmTop`, `#mmBack`, `#mmBody` (flex layout), dan `position: relative` pada `#mmPlayer` tidak sengaja terhapus saat penghapusan penampil foto. Tanpa flex layout, pemutar video tidak terkontrol ukurannya. Fix: kembalikan CSS layout modal.
## [v1.0 — 2026-08-19] — Fix video player zoom: restore missing player CSS
- **Pemain video terlalu zoom** — CSS untuk `#mmVideoWrap`, `#mmPlayer`, `#mmVideo` (termasuk aspect-ratio 16:9 via `::before`, `object-fit: contain`, `position: absolute`) tidak sengaja terhapus saat penghapusan penampil foto. Tanpa CSS ini, video tampil tanpa batas ukuran. Fix: kembalikan CSS player.
## [v1.0 — 2026-08-19] — Fix video player: restore missing CSS rule
- **Pemutar video tidak tampil** — Rule CSS `#mediaModal.open { display: flex; }` tidak sengaja terhapus saat penghapusan penampil foto (commit 65e648a). Tanpa rule ini, modal tetap `display: none` meskipun class `.open` ditambahkan. Fix: kembalikan rule CSS.
## [v1.0 — 2026-08-19] — Code cleanup: remove dead code + cache DOM elements
- **Dead code `doDeleteMedia`** — Fungsi frontend yang tidak pernah dipanggil sejak tombol hapus galeri dihapus.
- **Dead code `mmType === 'image'`** — Referensi keyboard handler dan click handler untuk mode foto yang sudah tidak ada.
- **Unused imports** — `Intent` di `HttpControlServer.kt` dan `File` di `StorageCleanup.kt`.
### Dioptimasi
- **Cache DOM elements** — `mmVideoHeader` dan `mmVideoWrap` di-cache sebagai `const` (sebelumnya 4x `getElementById` per operasi).
- **HTML nesting mediaModal** — Hapus `</div>` ekstra yang menyebabkan depth mismatch.
## [v1.0 — 2026-08-19] — Fix HTML nesting + remove dead photo code
- **HTML nesting mediaModal** — `</div>` ekstra setelah `mmDesc` menutup `mmBody` terlalu awal, mendorong `mmRelated` keluar dari modal. Fix: hapus `</div>` ekstra.
- **Dead code foto** — Hapus referensi `mmType === 'image'` yang sudah tidak relevan (keyboard handler, mmBody click handler).
- **Galeri video-only** — Hapus tombol "Photos" dari filter galeri, default `galleryFilter` ke `'video'`, perbarui placeholder search ke "Search videos…".
## [v1.0 — 2026-08-19] — Remove photo viewer + fix video player
- **Penampil foto** — Hapus seluruh fitur penampil foto (zoom, pan, swipe, slideshow) dari remote web. Penampil foto memiliki bug HTML nesting yang mempengaruhi pemutar video. Ukuran remote web berkurang ~15 KB (152 KB → 138 KB).
- **Pemutar video: judul tertutup kontrol** — `#mmPlayer` tidak punya batas tinggi, sehingga `::before` (padding-top 56.25% = rasio 16:9) bisa membuat player sangat tinggi di layar lebar, mendorong `#mmDesc` (judul) ke bawah hingga tertutup gradient kontrol. Fix: tambah `max-height:56vh; overflow:hidden; position:relative` ke `#mmPlayer`.
- **Pemutar video hilang** — `mmVideoWrap` disembunyikan saat reset tapi tidak pernah di-unhide di jalur video `openMedia()`. Fix: tambah `mmVideoWrap.classList.remove("hidden")` di jalur video.
## [v1.0 — 2026-08-18] — Fix statPool race condition causing File Manager HTTP 500
- **File Manager HTTP 500 (RejectedExecutionException)** — `statPool` (thread pool untuk statistik subfolder) mengalami race condition TOCTOU: `liveStatPool()` mengecek `isShutdown` dan mengembalikan pool, tapi antara pengecekan dan `submit()`, `stopServer()` memanggil `shutdownNow()` yang mematikan pool. Pool tetap terminated selamanya karena tidak ada yang membuat pool baru, menyebabkan SEMUA request `/api/fs?path=<sdcard>` gagal dengan HTTP 500 dan `completed tasks = 169`. Fix: tambah `rejectedExecutionHandler` ke `ThreadPoolExecutor` yang auto-heal — bila pool di-shutdown, pool baru otomatis dibuat sehingga request berikutnya langsung pulih.
### Ditambah
- **Dokumentasi `stopServer()` upload finalization** — Jelaskan bahwa coroutine upload finalization di `serverScope` sengaja dibiarkan selesai natural (beberapa ms) saat server stop, bukan di-cancel, untuk mencegah operasi tulis file terpotong.
## [v1.0 — 2026-08-18] — Remove delete buttons from gallery
- **Tombol hapus di galeri** — Hapus ikon tempat sampah di setiap sel
  galeri, tombol hapus di penampil foto, dan tombol batch delete di
  mode select. Hanya tombol select, download ZIP, dan slideshow yang
  tersisa.
## [v1.0 — 2026-08-18] — Fix gallery pagination hasMore bug + code quality
- **Galeri: `hasMore` salah saat scan terbatas** — `hasMore` sebelumnya
  memakai `matched > pageEnd` (total item > batas halaman), yang salah
  saat `scanLimit` memotong scan. Contoh: 150 item, halaman 2
  (start=200) → `matched=150 > pageEnd=300` → `hasMore=false` padahal
  halaman 1 masih punya item. Fix: hitung `pageCount` (item per halaman)
  dan cek `pageCount >= PAGE_SIZE && (matched < scan.total || scan.items.size < scan.total)`.
- **Cache pruning indentasi salah** — `fsMediaCache` & `fsStatsCache`
  eviction code visual scope tidak konsisten (bukan bug fungsional,
  tapi membingungkan untuk maintenance).
- **`CrashLog` thread-safety** — `SimpleDateFormat` dibagi antar thread
  (crash handler bisa dipanggil dari thread berbeda). Fix: `ThreadLocal`.
## [v1.0 — 2026-08-18] — Sticky video header: title/date locked during scroll
- **Judul & tanggal video terkunci** — Wrap `mmVideoWrap` + `mmDesc`
  dalam container `mmVideoHeader` (`position: sticky`). Judul, ukuran,
  dan tanggal sekarang tetap terlihat di bawah pemutar video saat
  pengguna scroll saran video. Hanya daftar saran yang bisa di-scroll.
## [v1.0 — 2026-08-18] — Fix photo viewer: mmImage was inside hidden mmVideoWrap
- **Penampil foto gelap (akar masalah)** — `<img id="mmImage">` berada
  di dalam `<div id="mmVideoWrap">`. Saat mode foto aktif, CSS
  `#mediaModal.mm-img #mmVideoWrap { display: none }` menyembunyikan
  seluruh kontainer termasuk gambar. Pindahkan `mmImage`,
  `mmImgSpin`, `mmDesc` ke luar `mmVideoWrap` supaya tetap terlihat
  saat mode foto.
## [v1.0 — 2026-08-18] — Fix photo viewer black screen (deeper)
- **Penampil foto gelap (lanjutan)** — `DocumentFile.fromSingleUri()`
  return null di Android 6 untuk MediaStore URI → name="media" →
  MIME `application/octet-stream` → browser gagal render. Fix:
  fallback ke `mediaStoreName()` + `ContentResolver.getType()` untuk
  MIME yang benar.
## [v1.0 — 2026-08-18] — Fix photo viewer black screen
- **Penampil foto hanya gelap (hitam)** — MIME type wildcard `image/*`
  dan `video/*` tidak valid untuk HTTP Content-Type; browser gagal
  render gambar/video. Diganti MIME spesifik (`image/jpeg`,
  `video/mp4`, dll).
## [v1.0 — 2026-08-18] — Reduce Play Protect flags
- **Manifest cleanup untuk Play Protect** — ganti `usesCleartextTraffic`
  dengan `networkSecurityConfig` (lebih spesifik); hapus
  `requestLegacyExternalStorage` (tidak relevan di targetSdk 36);
  batasi `WRITE_EXTERNAL_STORAGE` ke `maxSdkVersion=28` (hanya
  Android 5-9).
- **network_security_config.xml baru** — trust anchor TLS kustom
  (DigiCert G2 + ISRG X1) untuk server remote; cleartext tetap
  diizinkan untuk download HTTP.
## [v1.0 — 2026-08-18] — Fix compile error
- **isStopped unresolved reference** — NanoHTTPD 2.3.1 tidak punya
  properti isStopped; diganti !isAlive.
- **CrashLog trim Int/Long mismatch** — setLength() butuh Long;
  tambah .toLong() pada MAX_BYTES.
## [v1.0 — 2026-08-18] — Efisiensi cache, debounce observer, memory fixes
- **Cache fsRoots/status tidak invalidate saat settings berubah** —
  `invalidateFsRootsCache()` dan `invalidateStatusCache()` dipanggil
  saat server start, supaya perubahan folder/port/readOnly langsung berlaku.
- **Gallery cache invalidasi tidak dipanggil setelah move download** —
  `DownloadEngine.move()` sekarang invalidate fsRoots cache.
- **RejectedExecutionException di file manager** — `liveStatPool().submit()`
  dibungkus `runCatching` supaya pool terminated tidak crash server (HTTP 500).
- **qrCache.clear() menghapus semua entry** — diganti evict entry paling lama
  saat cache penuh (max 8).
- **GalleryAdapter CoroutineScope leak** — scope di-cancel di `onDestroy()`
  supaya tidak bocor saat activity destroyed.
- **GALLERY SCAN berulang terlalu sering** — ContentObserver di-debounce
  minimum 3 detik antar invalidasi (sebelumnya: tiap perubahan MediaStore
  langsung invalidate cache → scan ulang berulang).
- **videoDurationsCache tidak terbatas** — prune otomatis saat >2000 entries
  (buang 500 entry paling lama).
- **completedUploads/failedUploads cap 400 clear semua** — diganti prune
  entry paling lama bila melebihi 400 (pertahankan 200 terbaru).
- **CrashLog.trim baca seluruh file ke memori** — diganti RandomAccessFile
  in-place trim (hemat RAM di device rendah).
- **fsMediaCache.clear() terlalu agresif** — diganti invalidasi path spesifik
  (parent dir) untuk delete/rename/move/mkdir (hemat RAM, cache tetap valid
  untuk path lain).
# Changelog
Semua perubahan penting dicatat di sini. Format mengikuti
[Keep a Changelog](https://keepachangelog.com/id-ID/1.1.0/) dan rilis mengikuti
alur CI: `versionName` tetap `1.0`, `versionCode` = `100000 + run_number`.
APK terbaru selalu ada di [GitHub Releases](https://github.com/tasirin1/tasirin-download-manager/releases).
## [v1.0 — 2026-08-18] — Video player sticky title + scrollable suggestions
- **Judul & tanggal video sticky** — wrapper `mmVideoWrap` mempertahankan
  pemutar video + deskripsi (judul/tanggal) di posisi atas saat
  menggulir daftar saran video di bawahnya.
## [v1.0 — 2026-08-18] — Fix rename, cache TTL, concurrency, gallery sync
- **Rename download tidak update `filePath`** — setelah rename file via UI,
  `DownloadItem.filePath` tetap path lama → Open/Move gagal. Fix: `FileSaver.rename()`
  sekarang return path baru + `DownloadEngine.rename()` update `filePath`.
- **Rename tidak invalidate MediaStore** — file di-rename di disk tapi galeri
  masih tampil nama lama. Fix: `FileSaver.rename()` panggil `notifyMediaChanged()`.
- **NanoHTTPD startServer race** — pool internal bisa belum ready setelah stop();
  retry loop 3x (600ms) supaya server tidak gagal start.
- **`imageDimCache` tidak punya TTL** — entries gambar yang dihapus tetap di-cache
  selamanya. Tambah TTL 2 menit + evict expired entries saat cache penuh.
- **`fsLoadMore()` tidak ada guard klik ganda** — tambah `pointerEvents: none`
  saat loading untuk cegah parallel requests.
- **Gallery count setelah delete tidak sync** — single delete decrement count
  secara lokal tanpa sync server. Fix: reload gallery dari server setelah delete.
- **`mmImgClamp()` tidak clamp saat zoom out** — gambar bisa geser keluar viewport
  saat pinch-out ke bawah 1x. Fix: reset translate ke (0,0) sebelum reset zoom.
## [v1.0 — 2026-08-18] — Bug fixes: crash pool, cache invalidation, thread safety
- **`RejectedExecutionException` saat stop/start server** — NanoHTTPD internal pool
  terminated sebelum semua request selesai → crash berulang. Tambah guard
  `isStopped` di `serve()` + `RejectedExecutionException` catch → 503 response.
  Tambah delay 200ms di `stopServer()` supaya pool benar-benar terminated.
- **Gallery cache tidak di-invalidate saat upload selesai** — `videoDurationsCache`
  tidak di-reset → video baru tidak punya durasi di galeri sampai server restart.
  Fix: invalidate di `deleteMedia()` dan `handleUploadFinalize`.
- **Gallery cache tidak di-invalidate saat mkdir** — `fsAction("mkdir")` tidak
  clear gallery cache → folder baru tidak terdeteksi galeri selama 30 detik.
- **`pruneCompletedUploads()` tidak dipanggil periodik** — hanya dipanggil saat
  upload chunk baru. Tambah periodic cleanup setiap 10 detik di SSE pump.
- **`fsStatsCache` tidak punya periodic cleanup** — entries menumpuk tanpa batas.
  Tambah `pruneFsStats()` dipanggil dari SSE pump.
- **Gallery scan TTL terlalu pendek (15 detik)** — naikkan ke 30 detik untuk
  device lambat (Android 5-6). Mengurangi scan ulang saat scroll galeri.
- **QR cache tidak punya TTL** — entries QR di-cache selamanya sampai eviction
  by size. Tambah TTL 5 menit → cache segar tanpa boros RAM.
- **`credCache` thread safety** — eviction loop (size + iterator + remove) tidak
  atomic pada `Collections.synchronizedMap`. Bisa race condition pada 2 thread
  bersamaan. Fix: `synchronized(credCache)` block di sekitar eviction.
- **`mmRelated` scroll overlap** — daftar saran video tumpang tindih dengan
  sticky video wrapper di small screens. Tambah padding-top.
- **`fsUpMenu` tidak auto-close** — dropdown upload tetap terbuka jika user tidak
  pilih opsi. Tambah auto-close 5 detik + clear timer saat close manual.
## [v1.0 — 2026-08-17] — Hapus dialog crash + pindah crash log ke folder eksternal
- **Dialog crash saat startup dihapus** — tidak lagi menampilkan dialog
  error sebelumnya saat app baru dibuka.
- **Crash log dipindah ke folder data eksternal** (`/Android/data/<pkg>/crash.log`)
  — terlihat dari file manager tanpa root, otomatis dihapus saat uninstall.
## [v1.0 — 2026-08-17] — Fix critical: StoragePrefs recursive crash (PR #118)
- **`StoragePrefs.prefs()` recursive call** — method memanggil dirinya sendiri
  saat `cachedPrefs == null` → StackOverflowError → force close saat app baru
  dibuka. Dipanggil langsung dari `App.onCreate()` → `isServerBackgroundEnabled()`.
  Fix: panggil `context.getSharedPreferences()` langsung.
## [v1.0 — 2026-08-17] — Efisiensi round 3: alokasi memori + I/O (PR #118)
- **`Checksums.base64Decode`** — `ArrayList<Byte>` + `toByteArray()` diganti
  `ByteArray` langsung (hilangkan alokasi ganda: List backing array + salinan).
- **`MediaLibrary.scanCached`** — `list.count { !it.isVideo }` + `list.count { it.isVideo }`
  (scan 2×) diganti single-pass `forEach` counter.
- **DownloadEngine batch ops** — `pauseAll`/`resumeAll`/`retryFailed`/`clearCompleted`
  tidak lagi membuat list `ids` intermediate; filter + forEach langsung.
- **`DownloadEngine.applyAuthHeaders`** — `headers.split('\n').forEach` diganti
  `indexOf('\n')` loop (hilangkan alokasi `List<String>` per request).
- **`FileSaver.mergeSegments`** — `outputStream()` dibungkus `BufferedOutputStream`
  (hemat syscall kecil ke disk saat merge banyak segmen).
- **`HttpBody.readForm`** — `split("&").forEach` diganti `indexOf('&')` loop
  (hilangkan alokasi `List<String>` per POST request).
- **`LogActivity.highlightLog`** — `substring().uppercase()` per baris diganti
  `contains(ignoreCase = true)` (hilangkan alokasi String uppercase per baris log).
## [v1.0 — 2026-08-17] — Efisiensi round 2: cache eviction + PIN optimization (PR #118)
- **`fsStatsCache` partial eviction** — hapus separuh entry via iterator
  saat > 300 (menggantikan `clear()` yang menyebabkan thundering herd).
- **`fsMediaCache` partial eviction** — hapus separuh entry via iterator
  saat melebihi `FS_MEDIA_CACHE_MAX_ENTRIES`.
- **`credCache` partial eviction** — hapus separuh entry via iterator
  saat > 128 (menggantikan `clear()` yang memaksa re-encrypt semua kredensial).
- **Cache expected PIN bytes** — `pinOk()` tidak lagi memanggil
  `toByteArray()` di setiap request; hash di-cache satu kali per sesi.
- **`isSlowError` / `isConnectError`** — `.orEmpty().lowercase()` diganti
  `?.lowercase() ?: return false` (hilangkan alokasi String intermediate).
- **`pinOk` indexOf** — `split(";").map { trim() }` diganti `indexOf`
  langsung (hilangkan alokasi list per request).
- **`appendRequestLog`** — `queryParameterString` dievaluasi setelah
  `isPolling` check (bukan sebelumnya).
## [v1.0 — 2026-08-17] — Efisiensi kode: cache + buffering + alokasi memori (PR #119)
- **SharedPreferences cache di StoragePrefs** — 58x `getSharedPreferences()`
  diganti cached instance, mengurangi overhead IPC di tiap akses prefs.
- **CrashLog append mode** — `readText()` + `writeText()` penuh diganti
  `BufferedWriter` append mode + trim periodis (hemat I/O saat banyak crash).
- **Cache `itemsJson()` berdasarkan signature** — JSON array download tidak
  dibangun ulang 2×/detik bila tidak ada perubahan (hemat GC).
- **`imageDimCache` eviction tanpa `toList()`** — hapus separuh entry via
  iterator langsung, tanpa alokasi list besar (~3000 key).
- **Cache `allowedFsRoots()`** — roots dibangun ulang hanya saat settings
  berubah, bukan 16× per request file manager.
- **`readForm()` body allocation** — baca per-byte ke `StringBuilder` (8KB
  buffer) tanpa alokasi `ByteArray(length)` penuh untuk form kecil.
- **Cache `SimpleDateFormat`** — di CrashLog (`stampFormat`) dan
  DownloadEngine (`DEFAULT_NAME_FORMAT`) — alokasi berulang dihapus.
- **Cache `statusObject()`** — JSONObject status (port, readOnly, versi)
  tidak dibangun ulang tiap request polling.
- **`BufferedOutputStream` di FileSaver** — upload chunk besar dibungkus
  buffer (hemat syscall kecil ke disk).
## [v1.0 — 2026-08-17] — Keamanan path traversal + thread safety (PR #118)
- **Path traversal di rename/mkdir File Manager** — parameter `name` pada
  action `rename` dan `mkdir` tidak memeriksa `..`, memungkinkan aksi file
  di luar direktori yang diizinkan. Penambahan sanitasi `..` di kedua action.
- **Path traversal di upload name** — parameter `name` upload tidak sanitasi
  `..`; meskipun mitigasi ada di `sanitizeFileName()` engine, penambahan
  replace `..` → `_` di HTTP server sebagai defense-in-depth.
- **Thread safety `jobs` & `retryAttempts` di DownloadEngine** — kedua map
  menggunakan `mutableMapOf()` biasa (bukan thread-safe) tapi diakses dari
  `Dispatchers.IO` dan `invokeOnCompletion` callback di thread berbeda.
  Diganti ke `ConcurrentHashMap` untuk mencegah `ConcurrentModificationException`.
- **Duplikasi data di `moveMediaToFile`** — jika copy ke filesystem berhasil
  tapi `resolver.delete(uri)` gagal, file ada di dua tempat tanpa rollback.
  Penambahan rollback: hapus file target bila delete gagal.
- **Cache stale di `moveFileToMediaStore`** — invalidasi cache hanya terjadi
  bila `file.delete()` berhasil; jika gagal, listing media tetap stale.
  Cache kini di-clear setelah copy ke MediaStore berhasil.
- **Upload name traversal defense-in-depth** — `uploadUniqueName` menolak
  `folderPath` yang mengandung `..` sebagai lapis keamanan tambahan.
- **Thundering herd di `imageDimCache`** — cache dimensi gambar di-clear
  seluruhnya saat > 5000 entry, menyebabkan spike re-fetch. Diganti
  partial eviction (hapus separuh entry) untuk menjaga performa.
## [v1.0 — 2026-08-17] — Pemutar video sticky + saran video scrollable (PR #116)
- **Pemutar video terkunci di atas** (`position: sticky`) saat pengguna
  menggulir daftar saran video di bawahnya — pengalaman seperti YouTube
  mobile. Shadow halus di bawah player memisahkan area pemutar dari konten
  yang bergulir.
## [v1.0 — 2026-08-16] — Resume unduhan tidak buang progres + keamanan thread server (PR #115)
- **Tiap putus jaringan mengulang unduhan dari nol** — catatan progres
  (`bytesDownloaded`/segmen) disimpan 1×/detik, sedangkan file `.part` terus
  bertambah di antara tick; saat koneksi putus, file parsial hampir selalu
  "lebih panjang" dari catatan sehingga pemeriksaan anti-korup membuang
  SELURUH progres. File parsial yang sedikit lebih maju dari catatan kini
  dipangkas ke posisi tercatat lalu unduhan lanjut; mulai dari nol hanya bila
  data benar-benar hilang (file lebih pendek dari catatan atau sisa `.part`
  tanpa catatan). Berlaku untuk unduhan tunggal dan multi-segmen.
- **Galeri bisa 500/JSON korup saat dibuka bersamaan** — cache durasi video
  (`video_durations.json`) adalah `JSONObject` bersama yang diubah banyak
  thread server (nanohttpd) tanpa sinkronisasi (contoh: request galeri
  paralel dari beberapa tab/device). Akses baca/tulis kini dikunci sehingga
  `put`/`optLong` paralel tidak korup.
- **SSE bisa membuat dua pump kembar** — dua koneksi `/api/events` datang
  bersamaan bisa sama-sama lolos cek `sseJob?.isActive` sebelum variabel
  terisi, membuat dua pump yang push frame ganda ke semua klien. Pembuatan
  pump kini dikunci dan pump yang berhenti tidak menimpa referensi pump yang
  lebih baru.
## [v1.0 — 2026-08-16] — Navigasi File Manager di luar root + pump SSE (PR #114)
- **Tombol Up & breadcrumb File Manager berhenti di folder kosong di luar root
  yang diizinkan** — naik dari root (mis. `f:/storage/emulated/0`) menuju
  `f:/storage/emulated` atau `/storage` menampilkan "Empty folder" karena
  listing ditolak keamanan. Folder induk dari root sekarang bisa di-browse
  (mode browse-only: tanpa statistik subfolder, token, dan tanpa aksi tulis;
  delete/rename/move/upload tetap ditolak).
- **Pump SSE bocor collector saat tidak ada klien** — saat pump berhenti normal
  (SSE tanpa klien 2 tick), coroutine `items.collect` tidak di-cancel sehingga
  tetap menempel selamanya dan memakan CPU di setiap emisi item (menumpuk tiap
  siklus buka-tutup halaman remote). Collector kini di-cancel di `finally`.
## [v1.0 — 2026-08-16] — Perbaikan cache media & fetch ganda di File Manager (PR #113)
- **Aksi di folder media (MediaStore, path `m:`) tidak me-refresh daftar** —
  hapus/rename/pindah file lewat File Manager di folder media tidak membuang
  cache listing (`fsMediaCache`) dan snapshot galeri, jadi perubahan baru
  terlihat setelah 5–15 detik. Kedua cache kini di-invalidasi pada delete,
  rename, move, dan perpindahan file ↔ MediaStore.
- **File Manager mem-fetch daftar lokasi storage berulang** — `fsLocationsLoaded`
  di-reset di tiap kunjungan tab Files dan `loadFsLocations()` bisa dipanggil
  dua kali bersamaan (applyRoute + loadFs), menghasilkan `GET /api/fs?path=`
  ganda tiap masuk tab. Flag sekarang tidak di-reset dan ada guard in-flight
  (`fsLocationsLoading`).
## [v1.0 — 2026-08-15] — Pemutar video: hapus gesture kecerahan & volume (PR #112)
- **Gesture kecerahan & volume di pemutar video dihapus** — geser vertikal di sisi kiri
  layar (brightness) dan sisi kanan (volume) rentan kepencet di layar sentuh; volume tetap
  bisa diatur lewat slider di kontrol pemutar. Geser kiri/kanan untuk ganti video, tap
  play/pause, dan double-tap seek tetap berfungsi. Overlay indikator level (`#mmLevel`)
  ikut dihapus.
## [v1.0 — 2026-08-15] — Perbaikan bug galeri (pagination filter, sinkron hapus, cache) & cache ZIP (PR #111)
- **Galeri ber-filter (pencarian/foto-video) memuat halaman kosong terus-menerus** —
  `hasMore` lama dihitung dari total seluruh media (`matched < scan.total`), jadi setelah
  hasil filter habis browser tetap meminta halaman berikutnya; hitungan "N file" juga
  menampilkan total semua media. `hasMore` kini `matched > pageEnd` dan `total` = jumlah
  hasil filter saat ada `q`/`type`.
- **Hapus foto/video dari grid galeri tidak sinkron** — cell hilang tetapi daftar item dan
  counter tidak ikut diperbarui, sehingga "Select all" dan prev/next masih menyertakan file
  yang sudah dihapus. Item kini di-splice dari `galleryItems` dan counter dikurangi.
- **Galeri tampil basi sampai 15 detik setelah hapus/upload/pindah media** — cache snapshot
  galeri di server tidak dibuang saat media berubah. Cache kini di-invalidasi pada delete
  media, finalisasi upload, dan aksi file manager (hapus/rename/move).
- **Ganti filter/ketik pencarian bisa tidak berefek saat request lama belum selesai** —
  flag `galleryLoading` membuat reset ter-skip dan respons lama bisa menimpa data baru.
  Diganti nomor urut request (`gallerySeq`); respons basi diabaikan.
- **Unduh folder membuat ZIP ganda saat browser mengirim beberapa request Range bersamaan** —
  dua request yang sama-sama miss cache membuat arsip baru dan file kalah bocor di `cacheDir`;
  kini `putIfAbsent` menjamin satu ZIP per path/token dan file kalah langsung dihapus.
## [v1.0 — 2026-08-15] — Perbaikan bug: back File Manager, panah ganda, cache ZIP (PR #110)
- **Menutup video/foto dari File Manager ikut naik 1–2 folder** — entry
  history "guard media" membuat handler `popstate` file manager mengira tombol
  back (atau tombol tutup) sebagai navigasi folder, lalu `mmPopGuard`
  melakukan `history.back()` kedua sehingga folder naik dua tingkat. Pop yang
  memang untuk menutup media kini ditandai (`mmGuardPopPending`) dan diabaikan
  handler file manager.
- **Panah kiri/kanan di media ganda** — dua handler `keydown` aktif
  bersamaan: di pemutar video panah memindah video SEKALIGUS seek ±5s, di
  penampil foto melompat 2 item per tekan. Aksi panah kini hanya ditangani
  satu handler (seek video / ganti foto).
- **Badge Downloads tidak ter-update saat polling di tab lain** —
  `pollSnapshot` hanya me-render daftar di tab Downloads; badge tab Downloads
  kini tetap diperbarui walau sedang di Galeri/File Manager (SSE mati).
- **Unduh folder (ZIP) di-zip ulang untuk tiap request Range** — browser
  mengirim beberapa request `206` untuk satu unduhan; `fsZip`/`mediaZip`
  membuat arsip baru tiap request (boros CPU + disk di Android TV, terlihat
  "ZIP CREATED" berulang di log). ZIP kini di-cache 60 detik per path/token
  dan dipakai ulang untuk request berikutnya.
## [v1.0 — 2026-08-15] — Perbaikan bug video player & penampil foto (PR #109)
- **Tap tengah pemutar video tidak bisa pause/play di layar sentuh** — handler
  `touchend` membalik play/pause, lalu *click sintetis* Android menyusul dan
  membatalkannya (play langsung di-pause 220 ms, pause langsung di-play lagi).
  Kini `click` mengabaikan sentuhan (`pointerType === 'touch'`) karena semua
  aksi sentuh sudah ditangani `touchend`; tombol play besar (overlay) ikut
  berfungsi normal.
- **Drag slider seek / volume / tombol bar tidak sengaja memicu play-pause
  atau ganti video** — event sentuhan di kontrol membubble ke `mmPlayer`;
  handler kini mengabaikan sentuhan yang berasal dari `#mmCtrl`/`#mmPlayerTop`
  (slider, tombol, volume).
- **Posisi resume video tersimpan ke token yang salah saat pindah video
  (prev/next/auto-next)** — event `pause` asinkron menyimpan posisi video lama
  ke token video baru, sehingga video baru tiba-tiba "Resume from" waktu asing.
  Posisi lama kini disimpan eksplisit sebelum ganti token dan event `pause`
  memakai token video yang benar-benar aktif.
- **Auto-next memutar video yang salah urutan** — daftar lanjutan memakai
  "video lain pertama" (misal B lanjut ke A, mundur). Kini auto-next memakai
  video berikutnya sesuai urutan galeri (`mmVideoList.slice(idx + 1)`).
- **Media dibuka dari File Manager kehilangan prev/next & hitungan** —
  `mmVideoList`/`mmImageList` kosong karena `galleryItems` belum dimuat; item
  saat ini kini dimasukkan ke daftar sebagai fallback.
- **File audio (mp3/m4a/aac/dll) dibuka dari File Manager malah tampil di
  penampil foto** — audio kini dibuka di pemutar (label "audio"), bukan gagal
  "Failed to load photo".
- **Jempol slider seek melompat saat digeser** — `timeupdate` menimpa posisi
  slider saat drag; ada penanda `mmSeekDrag` + fallback event `change`.
- **Pesan gagal muat video terlalu singkat & tidak informatif** — toast kini
  2,6 detik dan menyebut format mungkin tidak didukung perangkat.
## [v1.0 — 2026-08-15] — Audit efisiensi: hot path engine, file manager, polling (PR #108)
- **Engine: biaya scan daftar saat throttle** — `SpeedThrottle.sleepIfNeeded`
  kini menerima lambda, jadi total byte per item hanya dihitung saat ada batas
  kecepatan (sebelumnya dihitung setiap pembacaan buffer 64 KB walau tanpa
  limit).
- **Engine: progres segmen digabung (coalesce)** — segmen menulis progres ke
  penyangga ringan, lalu SATU `updateItem`/StateFlow per item per interval
  (sebelumnya 1 salinan daftar + 1 emisi per segmen per detik; di item dengan
  banyak segmen ini membebani UI/notifikasi/SSE). Nilai akhir tiap segmen
  tetap ditulis langsung (verifikasi ukuran & resume tidak berubah).
- **File manager: halaman default 1000 → 250 entri** (`FS_PAGE`) — JSON,
  statistik subfolder, dan render DOM per request jauh lebih ringan; tombol
  "Load more" menangani sisanya (server default disesuaikan ke 300).
- **File manager media: cache listing MediaStore 5 dtk** — membrowse folder
  media tidak lagi me-query ulang seluruh koleksi tiap halaman; cache
  dibatalkan saat upload/aksi file/rename/move.
- **Log server: polling berhenti saat layar tidak terlihat** (onStart/onStop)
  dan hitung baris tanpa alokasi `text.lines()` per tick.
- **SSE: pump dihentikan saat tidak ada klien** — coroutine heartbeat dan
  collector item tidak berjalan sia-sia; `ensureSsePump()` menyalakan lagi saat
  klien baru masuk.
- **Main UI: statistik + tombol batch dihitung dalam satu iterasi** daftar
  (sebelumnya 4× iterasi per emisi progress).
- **Remote web: pilihan file memakai `Set`** (hapus `indexOf` O(n) per baris
  saat render mode pilih) dan pencarian file manager di-debounce 250 ms.
## [v1.0 — 2026-08-15] — Version catalog dependensi (PR #106)
### Ditambahkan
- `gradle/libs.versions.toml` — versi dependensi & plugin dipusatkan (AGP,
  androidx, nanohttpd, desugar, coroutines, test). `build.gradle.kts` dan
  `app/build.gradle.kts` memakai alias catalog; resolusi artifact tidak
  berubah (verification-metadata tetap valid).
## [v1.0 — 2026-08-15] — Guard sinkron README dwibahasa (PR #104)
- `scripts/check_readme_sync.py` + step CI — struktur heading README.md dan
  README.en.md wajib sinkron (level & urutan), teks boleh beda karena
  terjemahan. Pre-commit hook ikut memeriksa.
## [v1.0 — 2026-08-15] — Cakupan unit test JaCoCo (PR #102)
- **JaCoCo coverage di CI** — `jacocoTestReport` (XML + ringkasan LINE/
  BRANCH/INSTRUCTION/METHOD di job summary) dan `jacocoTestCoverageVerification`
  (ambang LINE 5%, bisa dinaikkan seiring pertumbuhan test).
- `verification-metadata.xml` diperbarui (artifact JaCoCo ikut diverifikasi).
## [v1.0 — 2026-08-15] — Security scanning CodeQL + gitleaks (PR #98)
- **CodeQL** (Java/Kotlin) — analisis keamanan statis di push `main`, semua
  PR, dan terjadwal mingguan; hasilnya di tab Security repositori.
- **Gitleaks** — deteksi secret/token ter-commit di tiap push & PR (guard
  tambahan untuk keystore/token).
## [v1.0 — 2026-08-15] — Keandalan rilis & automasi repo (PR #97)
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
- **Guard CHANGELOG gagal di push `main`** — clone shallow tidak punya commit
  `before` sehingga `git diff` exit 128 dan mematikan build. Kini checkout
  memakai `fetch-depth: 0` + fallback aman (`|| true`) (PR #95).
- **Verifikasi dependensi Gradle aktif (strict)** — `gradle/verification-
  metadata.xml` (sha256, 402 komponen: AGP, Kotlin, lint, aapt2, semua
  dependency runtime/test) di-commit ke repo; `org.gradle.dependency.
  verification=strict` di `gradle.properties` membuat CI menolak perubahan
  dependensi yang tidak punya checksum.
- **Workflow `update-deps-verification` digenerate via `build` penuh** —
  metadata sekarang mencakup artifact yang hanya muncul saat task nyata
  (aapt2 binary), bukan sekadar `help`.
## [v1.0 — 2026-08-14] — Kualitas maintenance: guard CI, keystore check, CONTRIBUTING, screenshot
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
- **Deteksi checksum otomatis dari header HTTP** — saat server mengirim
  `Digest` (RFC 3230), `Content-MD5`, atau `X-Checksum-Sha256/Sha1/MD5`,
  checksum dipakai otomatis (tanpa isi manual) lalu diverifikasi setelah
  unduh selesai; item yang terverifikasi ditandai badge ✓ di remote web.
- **Unit test baru**: `ChecksumsTest` (parser header Digest/base64/hex) dan
  `ScanCacheTest` (kondisi cache galeri) — mem-guard dua pola bug yang
  didokumentasikan di AGENTS.md.
- Kondisi cache galeri dipindah ke `MediaLibrary.scanCacheUsable()` (fungsi
  murni) supaya bisa diuji — perilaku sama dengan PR #92.
## [v1.0 — 2026-08-14] — Perf: cache scan galeri tidak berulang saat galeri kecil (PR #92)
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
- **File Manager HTTP 500 setelah stop/start server** — `stopServer()` men-shutdown
  `statPool`, tapi toggle server memakai instance `HttpControlServer` yang sama,
  jadi pool tetap `Terminated` dan semua `GET /api/fs?path=<subfolder>` gagal
  dengan `RejectedExecutionException`. `liveStatPool()` kini membuat pool baru
  otomatis saat dibutuhkan.
## [v1.0 — 2026-08-13] — Pengelolaan repo untuk AI: AGENTS.md diperkuat, template PR/issue, branch protection
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
- **Tab Downloads kini bisa diklik kembali** — tombol `tabDownloads` di tab bar
  remote web tidak punya handler klik sejak fitur bottom nav diperkenalkan,
  jadi dari File Manager/Galeri tidak ada cara kembali ke halaman download
  lewat tab. Handler ditambahkan mengikuti pola tab Galeri/File.
- **Smoke test baru** — guard regresi memastikan klik tab Downloads dari
  halaman Files kembali ke route downloads.
## [v1.0 — 2026-08-13] — Audit kode: hapus fmtDate duplikat, perbaiki string UI upload
- **Hapus `fmtDate` duplikat (kode mati)** — dua definisi `fmtDate` di
  `remote.src.html` menimpa satu sama lain (hoisting); definisi pertama tidak
  pernah dieksekusi dan dihapus. Satu implementasi tersisa, perilaku tampilan
  tidak berubah.
- **String UI upload ke Inggris** — teks "sisa ~" pada progres upload chunk
  diganti "ETA ~" (guard i18n tidak menangkap kata "sisa" sebelumnya).
- **Guard i18n diperkuat** — kata "sisa" ditambahkan ke daftar larangan
  `BANNED_ID` di `scripts/prepare_remote.py` agar tidak muncul lagi.
## [v1.0 — 2026-08-13] — Sembunyikan tombol penampil foto saat zoom
- **Tombol penampil foto tersembunyi saat zoom aktif** — tombol atas dan
  panah kiri/kanan otomatis disembunyikan begitu foto diperbesar (tap ganda,
  pinch, atau scroll); tombol muncul kembali saat kembali ke ukuran penuh.
- **Tap tunggal saat zoom** — mengetuk foto/area kosong saat zoom aktif tidak
  lagi membolak-balik tombol; toggle chrome hanya berlaku di ukuran penuh.
- **Smoke test baru** — verifikasi `mm-chrome-hidden` ditambahkan saat zoom
  dan dihapus saat reset.
## [v1.0 — 2026-08-13] — Audit efisiensi: hoist ikon, cache baris, JSON FS ringan, pencarian log tanpa alokasi
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
- **Geser foto saat zoom** — gambar yang diperbesar kini bisa digeser untuk
  menjelajah area tertentu; sebelumnya `mmImgClamp()` selalu memaksa posisi
  ke tengah sehingga pan (sentuh, mouse, dan pinch) langsung dibatalkan.
  Tepi gambar tetap dikunci agar tidak lepas dari layar.
- **Smoke test pan/zoom** — `upload_smoke_test.js` kini memverifikasi bahwa
  clamp tidak mengembalikan gambar ke tengah saat digeser dan tepi tetap
  terkunci (guard CI).
## [v1.0 — 2026-08-13] — Tampilan utama remote web: bottom nav, hero status, ikon state, persen di bar, tanggal selesai & retry
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
- **Polling adaptif lebih realtime** — interval cepat 2 detik → **1 detik**
  selama ada transfer aktif (download/upload), tanpa syarat "data berubah"
  lagi; idle tetap 10 detik. SSE tetap sumber utama; polling hanya pengaman.
  Blok penanda perubahan yang tidak terpakai ikut dihapus.
- **Tombol Search File Manager selebar baris** — tidak lagi menyisakan
  kolom sempit saat membungkus di layar sempit; baris input pencarian tetap
  muncul di bawahnya saat tombol ditekan.
## [v1.0 — 2026-08-12] — Status bar kontras, pencarian File Manager, scan VirusTotal di PR
- **Pencarian File Manager di remote web** — tombol kecil "Search" di toolbar
  membuka satu baris input; filter berjalan di sisi klien (hanya baris yang
  sudah dimuat), tanpa beban RAM/endpoint server.
- **VirusTotal ikut di-scan pada PR** — tidak hanya push `main`; APK PR ikut
  dicek sebelum merge (butuh `VT_API_KEY`).
- **Ikon status/navigation bar paksa gelap** (`SystemBarStyle.light`) — app
  selalu tema terang, jadi ikon tidak lagi berubah putih saat mode gelap
  sistem aktif (`EdgeToEdge.kt`).
### Catatan audit
- Desugaring: hanya `Iterable.forEach` sintetis yang terpakai (tanpa
  `java.time`/`stream`/`Optional`); sisanya sudah dipangkas R8 — tidak ada
  yang bisa dihemat lebih lanjut. Audit kode mati: tidak ditemukan
  fungsi/properti tak terpakai.
## [v1.0 — 2026-08-12] — Perbaikan tema: benar-benar terang (Light) bukan gelap
- **Tema masih gelap setelah PR #73** — `Theme.AppCompat.NoActionBar` adalah
  varian gelap, jadi latar hitam + kartu putih + teks putih nyaris tak
  terbaca. Ganti ke `Theme.AppCompat.Light.NoActionBar`; `TvOutlinedButton`
  diberi teks `@color/primary` agar senada desain lama.
## [v1.0 — 2026-08-12] — Perbaikan: latar biru di tampilan utama setelah hapus Material
- **Latar biru splash menutupi semua halaman** — tema aplikasi sebelumnya
  mewarisi `Theme.HttpDownloadManager.Splash` (windowBackground biru) dan
  tanpa `installSplashScreen()` tidak ada yang menukar ke tema terang,
  sehingga teks gelap nyaris tak terlihat. Sekarang: aplikasi default
  `Theme.HttpDownloadManager` (terang), splash hanya di `MainActivity`
  via `android:theme` manifest + `setTheme()` klasik sebelum konten digambar.
## [v1.0 — 2026-08-12] — APK lebih kecil: tanpa Material, splashscreen klasik, R8 agresif
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
- **File Manager remote di-paginate (1000 entri/request + tombol "Load more")** —
  folder raksasa tidak lagi membangun JSON semua entri + statistik semua
  subfolder sekaligus di memori server.
- **Cache galeri dibatasi halaman aktif + 1 buffer** — scan tidak lagi menahan
  sampai 3000 entri di memori saat browsing biasa (total tetap akurat untuk
  `hasMore`; saat ada filter/q, scan penuh dipakai supaya hasil pencarian akurat).
- **Pembersihan thumbnail cache maksimal 1x per 7 hari** — tidak lagi memindai
  folder thumb setiap kali aplikasi start.
## [v1.0 — 2026-08-11] — Remote web: tombol Select sejajar Upload di File Manager
- **Tombol Select File Manager kini sejajar dengan tombol Upload** — di layar
  sempit (≤600px) Select tidak lagi turun ke baris sendiri selebar penuh; grid
  toolbar dirapikan dari 5 ke 4 kolom (hilangkan kolom kosong di kanan).
## [v1.0 — 2026-08-11] — Pengaturan: kurangi jumlah view (hilangkan warning lint)
- **Layout `activity_settings.xml` turun dari 81 ke 66 view** — warning lint
  `TooManyViews` ("more than 80 views, bad for performance") hilang. Header
  section collapsible tidak lagi memakai `LinearLayout` + `TextView` +
  `ImageView` (cukup satu `TextView` dengan chevron `drawableEnd`, swap
  `ic_chevron`/`ic_chevron_up`); wrapper `content_*` per section dihapus —
  kontrol langsung menempel di section, padding dipindah ke card. Inflasi
  dan memori halaman Pengaturan lebih hemat.
## [v1.0 — 2026-08-11] — Audit efisiensi: cache galeri, throttle, R8
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
- **Tombol Download di mode select File Manager remote**: pilih beberapa file
  dan/atau folder sekaligus, lalu unduh sekali sebagai ZIP (folder di-zip
  rekursif; endpoint `/api/media_zip` kini menerima `paths`).
## [v1.0 — 2026-08-11] — Remote web: hapus tombol unduh cepat per baris
- **Tombol ⬇ Download di tiap baris File Manager remote dihapus** — rawan
  tertekan tidak sengaja (apalagi saat dikontrol dari remote TV / D-pad).
  Aksi download tetap tersedia lewat menu **⋯** pada baris yang sama
  (Stream / Download / Download folder ZIP).
## [v1.0 — 2026-08-11] — Audit efisiensi lanjutan
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
- `localeFilters` hanya `"en"` (UI Inggris; `id` sudah tidak ada).
- **Fase 3 peta jalan selesai**: uji manual di perangkat Android 15/16 berhasil
  (auto-start boot, download background, server remote, galeri). `targetSdk 37`
  sengaja belum dinaikkan.
## [v1.0-build100349] — 2026-08-10 — Bahasa Inggris penuh
- `README.en.md` + pemilih bahasa Indonesia/English di `README.md` &
  `README.en.md`.
- Baris About baru: "Language: English (app & remote web)" dan
  "Package: full-featured, only ~1.7 MB".
- Seluruh string UI aplikasi (`values/`) dan halaman remote web
  (`remote.html`) menjadi **Bahasa Inggris** (folder `values-en` dihapus).
- Pesan log server & error user-visible di engine dan server memakai Inggris.
- `AGENTS.md` disinkronkan: UI = Inggris, komentar/commit tetap Indonesia.
## [v1.0 — 2026-08-10] — Perbaikan batch 3
- Cache QR, `scaleDown` tunggal (BitmapUtil), `onTrimMemory`.
- PIN dicocokkan constant-time (anti timing attack).
- `HttpControlServer` dipecah (MediaStream, ServerStreams, ShareToken, SseStream).
- Test baru: ServerLog, readBounded, codec null-entry, sha256Hex, PIN normalize.
- Lint KTX (scale, createBitmap, set, isVisible); setPixels framework untuk QR.
- Dependensi di-update bertahap via CI (Gradle 9.7.0).
## [v1.0 — 2026-08-09] — Perbaikan batch 2
- Helper `setupSpinner` untuk semua Spinner; tombol bulk via view binding.
- Galeri memakai DiffUtil + paginasi scan bertahap (hemat memori Android 5+).
- PIN disimpan sebagai hash SHA-256 (tanpa plaintext di disk).
- Encoder QR mandiri tanpa zxing di runtime (decode tetap diverifikasi di test).
- Tombol jeda/lanjut semua di notifikasi.
## [v1.0 — 2026-08-09] — Desain UI remote & pemutar video
- Desain ulang file manager: ikon tipe, sticky bar, batch action, detail upload,
  info root.
- Galeri remote & pemutar video ala YouTube: lightbox, multi-select, kontrol
  ramping, top bar back, navigasi foto/video.
- Penampil foto ala galeri; tombol kembali Android menutup media.
## [v1.0 — 2026-08-05] — Efisiensi build & keamanan rilis
- Efisiensi build & ukuran APK (dependensi, locale filter, packaging, caching).
- Workflow VirusTotal: submit APK rilis + polling hasil + ringkasan deteksi di log.
- Helper `Permissions` untuk izin runtime; sentralisasi crash log.
## [v1.0 — 2026-08-03 s/d 08-05] — Rilis awal & fitur inti
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
