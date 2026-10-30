#!/usr/bin/env bash
# Cek cepat pra-push tanpa toolchain Android (contek vaultwarden-host).
# Gagal cepat sebelum push agar hemat menit CI.
set -euo pipefail
gagal=0
if rg --version >/dev/null 2>&1; then ADA_RG=1; else ADA_RG=0; fi
# 1. Path vektor harus 0.x (lint InvalidVectorPath menggagalkan build).
if [ "$ADA_RG" = 1 ] && rg --pcre2 -n '' app/src/main/res/drawable >/dev/null 2>&1; then
  if rg -n --pcre2 '(?<![0-9])\.-?[0-9]' app/src/main/res/drawable 2>/dev/null | grep -q .; then
    echo "GAGAL: path vektor tanpa nol depan (pakai 0.9 bukan .9)"; gagal=1;
  fi
elif grep -R -P '' . >/dev/null 2>&1; then
  if grep -R -n -P '(?<![0-9])\.-?[0-9]' app/src/main/res/drawable 2>/dev/null | grep -q .; then
    echo "GAGAL: path vektor tanpa nol depan (pakai 0.9 bukan .9)"; gagal=1;
  fi
elif grep -R -E -n '(^|[^0-9])\.-?[0-9]' app/src/main/res/drawable 2>/dev/null | grep -q .; then
  echo "GAGAL: path vektor tanpa nol depan (pakai 0.9 bukan .9)"; gagal=1;
fi
# 2. Jangan commit binary/APK/zip/secret ke repo. Pindai berkas TERLACAK
# (git ls-files), bukan index staged: versi lama (diff --cached) selalu
# kosong di CI sehingga cek ini no-op permanen di sana (issue #195).
# -z + tr: tanpa itu git mengutip nama non-ASCII menjadi oktal
# ("s\303\251cret.jks") sehingga pola ekstensi tak pernah cocok dan
# keystore bernama non-ASCII lolos gerbang ini (issue #312).
# Kegagalan ls-files wajib bernama (issue #663): dulu kegagalannya
# ditelan 2>/dev/null dan daftar kosong terbaca "tak ada berkas
# terlarang" — pindai ini no-op senyap di checkout rusak. Daftar
# ditulis ke berkas temp agar pemisah NUL dari -z utuh (variabel bash
# tak bisa menyimpan byte NUL).
# Kegagalan mktemp sendiri wajib bernama (issue #670): assignment polos
# di bawah set -e membunuh skrip seketika dengan pesan mentah mktemp
# dan tanpa satu pun cek berjalan.
tmp_terlacak=$(mktemp) || { echo "GAGAL: mktemp gagal membuat berkas temp (periksa TMPDIR) — pindai berkas terlarang tak bisa menilai (fail-closed)"; exit 1; }
if ! git ls-files -z > "$tmp_terlacak" 2>/dev/null; then
  echo "GAGAL: git ls-files gagal — pindai berkas terlarang tak bisa menilai (fail-closed)"; gagal=1;
elif tr '\0' '\n' < "$tmp_terlacak" | grep -E -i -q '\.(apk|aab|dex|zip|jks|keystore|p12|pfx)$|(^|/)keystore$|(^|/)[^/]*keystore[^/]*\.b64$|\.env($|\.)|(^|/)\.envrc$'; then
  echo "GAGAL: binary/APK/zip/secret terlacak di repo (jks|keystore|p12|pfx|.env|.envrc dilarang)"; gagal=1;
fi
rm -f "$tmp_terlacak"
# 3. Struktur heading README Indonesia vs Inggris wajib sinkron.
# Delegasikan ke guard Python yang otoritatif (issue #541): hitungan
# grep mentah di sini dulu ikut menghitung baris contoh di dalam blok
# berpagar, sehingga vonisnya berlawanan dengan check_readme_sync.py
# (yang sudah sadar pagar, #527/#534) atas dokumen yang sah.
# Keluaran skrip DITAMPILKAN (issue #613): menelannya ke /dev/null
# membuat setiap kegagalan — termasuk README non-UTF-8 — terlabeli
# 'struktur heading tidak sinkron' yang belum tentu penyebabnya.
if ! python3 scripts/check_readme_sync.py; then
  echo "GAGAL: check_readme_sync.py (penyebab pada pesan di atas)"; gagal=1;
fi
# 4. Remote web: sumber readable wajib ada + extract.js wajib readable.
if [ ! -f remote.src.html ]; then echo "GAGAL: remote.src.html hilang"; gagal=1; fi
if [ ! -f app/src/main/assets/remote.html ]; then echo "GAGAL: app/src/main/assets/remote.html hilang"; gagal=1; fi
ex="app/src/main/assets/extract.js"
if [ -f "$ex" ]; then
  lines=$(wc -l < "$ex" | tr -d ' ')
  if [ "${lines:-0}" -lt 5 ]; then echo "GAGAL: $ex tampak di-minify satu baris (pemicu Wacatac)"; gagal=1; fi
else
  echo "GAGAL: $ex hilang"; gagal=1;
fi
# 5. fmtDate wajib TEPAT SATU definisi di remote.src.html (issue #725):
# nol definisi sama mematikannya dengan ganda (hoisting menimpa), dan
# jumlah dihitung per KEMUNCULAN (grep -o), bukan per baris — dua
# definisi sebaris dulu terbaca n=1 oleh grep -c.
if [ -f remote.src.html ]; then
  n=$(grep -o -E 'function[[:space:]]+fmtDate|fmtDate[[:space:]]*=[[:space:]]*function|(const|let)[[:space:]]+fmtDate[[:space:]]*=' remote.src.html | wc -l)
  if [ "${n:-0}" -ne 1 ]; then echo "GAGAL: fmtDate terdefinisi $n kali di remote.src.html (wajib tepat 1)"; gagal=1; fi
fi
exit $gagal
