#!/bin/sh
# Pasang kait git lokal: pre-commit (check_repo) + pre-push (verifikasi
# path + cek cepat) dari satu paket .githooks lewat core.hooksPath.
# Dulu skrip ini menulis .git/hooks/pre-push langsung — begitu
# core.hooksPath terpasang, git mengabaikan .git/hooks seluruhnya
# sehingga kedua mekanisme saling menonaktifkan (issue #192).
# Dipasang sekali per clone: sh tools/pasang-hook.sh
set -e
git config core.hooksPath .githooks
LEGACY=".git/hooks/pre-push"
if [ -f "$LEGACY" ] && grep -q "dipasang tools/pasang-hook.sh" "$LEGACY"; then
    rm "$LEGACY"
    echo "Kait lama $LEGACY (pemasangan gaya lama) dibersihkan."
fi
chmod +x .githooks/pre-commit .githooks/pre-push
if [ -x .githooks/pre-commit ] && [ -x .githooks/pre-push ]; then
    echo "Kait terpasang via core.hooksPath=.githooks:"
    echo "  pre-commit -> python3 scripts/check_repo.py --pre-commit"
    echo "  pre-push   -> python3 tools/verifikasi-path.py + bash scripts/cek-cepat.sh"
else
    echo "PERINGATAN: kait di .githooks tak bisa dieksekusi (penyimpanan"
    echo "  noexec?) — git akan mengabaikannya di sini. Cadangan: gerbang CI"
    echo "  tetap memeriksa tiap push; atau jalankan manual sebelum commit:"
    echo "  python3 scripts/check_repo.py && python3 tools/verifikasi-path.py"
fi
