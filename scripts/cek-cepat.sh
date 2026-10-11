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
# 2. Jangan commit binary/APK/zip/secret ke repo.
if git diff --cached --name-only 2>/dev/null | grep -E -q '\.(apk|aab|dex|zip|jks|p12|pfx)$|keystore|\.env$'; then
  echo "GAGAL: binary/APK/zip/secret ikut staged (jks|keystore|p12|pfx|.env dilarang)"; gagal=1;
fi
# 3. Jumlah section README Indonesia vs Inggris wajib sama (jaga sinkron).
a=$(grep -c '^## ' README.md 2>/dev/null || true)
b=$(grep -c '^## ' README.en.md 2>/dev/null || true)
if [ "$a" != "$b" ]; then echo "GAGAL: jumlah ## README.md ($a) != README.en.md ($b)"; gagal=1; fi
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
# 5. fmtDate wajib SATU definisi di remote.src.html (hoisting menimpa).
if [ -f remote.src.html ]; then
  n=$(grep -c -E 'function[[:space:]]+fmtDate|fmtDate[[:space:]]*=[[:space:]]*function|(const|let)[[:space:]]+fmtDate[[:space:]]*=' remote.src.html || true)
  if [ "${n:-0}" -gt 1 ]; then echo "GAGAL: fmtDate terdefinisi $n kali di remote.src.html (wajib 1)"; gagal=1; fi
fi
exit $gagal
