#!/usr/bin/env python3
"""Guard CI: struktur heading README.md vs README.en.md harus sinkron.

Teks boleh berbeda (terjemahan), tapi LEVEL dan URUTAN heading wajib sama
supaya kedua dokumen tidak melenceng. Exit 0 = sinkron, 1 = tidak sinkron.
"""
import re
import sys
from pathlib import Path


def headings(path: Path) -> list[int]:
    levels = []
    # Lacak blok kode berpagar (issue #527): baris mirip-heading di
    # dalam contoh kode (mis. komentar konfigurasi) BUKAN heading —
    # tanpa pelacakan ini ia terhitung heading hantu dan menyalahkan
    # struktur dokumen yang sebenarnya sinkron.
    # Pagar dicatat KARAKTER + PANJANGNYA (issue #534): CommonMark
    # mengakui pagar ~~~ dan pagar lebih panjang (4+ backtick, pola
    # wajar untuk mendokumentasikan contoh pagar di dalam pagar);
    # penutup harus sekarakter dan sepanjang minimal pembuka, tanpa
    # info string.
    fence_char = ""
    fence_len = 0
    for line in path.read_text(encoding="utf-8").splitlines():
        stripped = line.lstrip()
        fm = re.match(r"^(`{3,}|~{3,})", stripped)
        if fence_char:
            if (
                fm
                and fm.group(1)[0] == fence_char
                and len(fm.group(1)) >= fence_len
                and not stripped[len(fm.group(1)):].strip()
            ):
                fence_char = ""
                fence_len = 0
            continue
        if fm:
            fence_char = fm.group(1)[0]
            fence_len = len(fm.group(1))
            continue
        m = re.match(r"^(#{1,6})\s+", line)
        if m:
            levels.append(len(m.group(1)))
    return levels


def main() -> int:
    id_path = Path("README.md")
    en_path = Path("README.en.md")
    if not id_path.exists() or not en_path.exists():
        print("::error::README.md / README.en.md tidak ditemukan.")
        return 1
    # Baca per berkas dengan vonis bernama (issue #613): README yang
    # tersimpan non-UTF-8 (Latin-1/UTF-16) dulu menggugurkan guard ini
    # dengan traceback UnicodeDecodeError mentah — dan pemanggilnya
    # (cek-cepat) menelan semua kegagalan menjadi pesan 'struktur
    # heading tidak sinkron' yang salah total.
    levels: dict[str, list[int]] = {}
    for label, path in (("README.md", id_path), ("README.en.md", en_path)):
        try:
            levels[label] = headings(path)
        except (OSError, UnicodeDecodeError) as exc:
            print(f"::error::{label} tak terbaca sebagai UTF-8: {exc}")
            return 1
    id_levels = levels["README.md"]
    en_levels = levels["README.en.md"]
    if id_levels != en_levels:
        print("::error::Struktur heading README.md dan README.en.md tidak sinkron.")
        print(f"README.md   : {id_levels}")
        print(f"README.en.md: {en_levels}")
        return 1
    print(f"OK: struktur heading sinkron ({len(id_levels)} heading, level sama).")
    return 0


if __name__ == "__main__":
    sys.exit(main())
