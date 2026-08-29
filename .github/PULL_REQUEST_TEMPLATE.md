## Ringkasan

<!-- Apa yang diubah dan kenapa (1-3 kalimat). -->

## Perubahan

<!-- - **Nama**: deskripsi singkat -->

## Verifikasi

<!-- Centang yang relevan. -->
- [ ] `python3 scripts/prepare_remote.py --check` lulus (bila remote web berubah)
- [ ] `CHANGELOG.md` diperbarui dan menyebut nomor PR ini
- [ ] Tidak ada build lokal — CI yang membangun APK
- [ ] (Bila `docs/` berubah) website GitHub Pages akan ter-deploy otomatis oleh
      *pages-build-deployment*; versi/ukur APK tidak diedit manual di `docs/index.html`
      (diambil otomatis dari GitHub Releases API).

## Catatan untuk AI / maintainer

- Baca [AGENTS.md](AGENTS.md) sebelum mengubah repo.
- Perubahan `app/src/main`, `remote.src.html`, `app/build.gradle.kts`, atau
  `scripts/` WAJIB menyertakan entri `CHANGELOG.md` (dijaga otomatis oleh CI).
