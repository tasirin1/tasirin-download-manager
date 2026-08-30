# Contributing — Tasirin Download Manager

Terima kasih sudah mau berkontribusi! Panduan singkat ini menjaga repo tetap
rapi, APK kecil, dan rilis selalu hijau. **Untuk AI yang mengelola repo: baca
[AGENTS.md](AGENTS.md) dulu — dokumen ini aturan ringkas untuk kontributor.**

## Alur kerja

1. **Normal flow lewat Pull Request** — branch `main` dilindungi dan wajib
   lolos `Build APK`. Owner boleh push hotfix/docs langsung saat darurat,
   tetapi workflow rilis tetap wajib dipantau sampai sukses.
2. **Satu PR, satu tujuan.** PR besar yang mencampur banyak hal sulit
   di-review dan sulit di-rollback.
3. **Wajib update `CHANGELOG.md`** pada commit/PR yang menyentuh kode aplikasi
   (`app/src/main`, `remote.src.html`, `app/build.gradle.kts`, `scripts/`) —
   dijamin otomatis oleh CI (guard CHANGELOG).
4. **Jangan build lokal untuk rilis.** Rilis resmi hanya lewat GitHub
   Actions (workflow `Build APK`). Build lokal hanya untuk debugging cepat.
5. **Jangan install Android SDK lokal** (sdkmanager/platform/build-tools) di
   mesin pengembangan — boros RAM/disk. Verifikasi cepat pakai
   `python3 scripts/check_repo.py`; compile/lint/test resmi tetap di CI.

## Aturan bahasa & gaya

- **UI aplikasi & remote web: Bahasa Inggris.** Komentar kode, commit,
  dan dokumentasi internal: Bahasa Indonesia.
- Gaya commit: `type(scope): deskripsi` — `feat`, `fix`, `ui`, `perf`,
  `refactor`, `docs`, `chore`, `rebrand`. Contoh: `fix(remote): ...`.
- Jangan ubah `versionName`/`versionCode` manual — diatur CI.

## Remote web (penting)

- Sumber halaman remote adalah **`remote.src.html`** (di root repo).
  `assets/remote.html` adalah hasil minify — **jangan edit langsung**.
- Setelah mengubah `remote.src.html`, jalankan:
  `python3 scripts/prepare_remote.py`
  lalu commit **kedua** file. CI memverifikasi sinkron lewat `--check`.

## Sebelum commit (opsional tapi disarankan)

Pasang pre-commit hook sekali:

```bash
git config core.hooksPath .githooks
```

Hook menjalankan `python3 scripts/check_repo.py --pre-commit`: struktur
repository, remote web, smoke upload, README, audit keamanan, whitespace, dan
unit test bila Java/Gradle tersedia.

## PR Dependabot

- Update GitHub Actions (tidak menyentuh dependensi Gradle) di-auto-merge
  setelah CI hijau — tidak perlu ditindaklanjuti.
- Update dependensi Gradle **tidak** di-auto-merge: verifikasi dependensi
  strict aktif, jadi wajib regenerasi `gradle/verification-metadata.xml`
  (workflow *Update dependency verification metadata* dengan `--ref` branch
  PR, commit artifact hasilnya) sebelum CI bisa hijau. Panduan otomatis
  dikirim sebagai komentar di PR.

## Label otomatis & issue/PR lama

- Workflow `Labeler` memberi label otomatis per path (remote-web, gallery,
  download-engine, settings, ci, docs).
- Workflow `Stale` menandai issue/PR tidak aktif 60/30 hari dan menutupnya
  bila 14 hari tidak ada respons (label `dependencies` dikecualikan).

## Menjalankan guard lokal

```bash
python3 scripts/check_repo.py                # semua guard cepat repo
python3 scripts/check_repo.py --android      # tambah lint + unit test Android
```

Kerentanan keamanan tidak boleh dibuat sebagai issue publik; ikuti
[SECURITY.md](SECURITY.md).

## Keamanan

- Jangan commit keystore (`*.jks`, `keystore.b64`).
- Jangan tempel token/secret di issue, PR, atau chat.
