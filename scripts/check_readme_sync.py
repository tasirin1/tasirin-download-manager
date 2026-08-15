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
    for line in path.read_text(encoding="utf-8").splitlines():
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
    id_levels = headings(id_path)
    en_levels = headings(en_path)
    if id_levels != en_levels:
        print("::error::Struktur heading README.md dan README.en.md tidak sinkron.")
        print(f"README.md   : {id_levels}")
        print(f"README.en.md: {en_levels}")
        return 1
    print(f"OK: struktur heading sinkron ({len(id_levels)} heading, level sama).")
    return 0


if __name__ == "__main__":
    sys.exit(main())
