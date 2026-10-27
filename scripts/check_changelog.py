#!/usr/bin/env python3
"""Guard CI: perubahan kode wajib menyertakan update CHANGELOG.md.

Dipakai job `verify` di build.yml (tanpa SDK). Argumen: base-sha PR dan
event.before push; salah satunya boleh kosong (skrip memilih yang valid).
Keluar 1 + ::error:: bila kode berubah tanpa CHANGELOG.md.
"""
import subprocess
import sys

CODE_PREFIXES = ("app/src/main/", "scripts/")
CODE_EXACT = ("remote.src.html", "app/build.gradle.kts")


def changed_files(base):
    for args in (["git", "diff", "--name-only", base + "...HEAD"],
                 ["git", "diff", "--name-only", base, "HEAD"]):
        proc = subprocess.run(args, capture_output=True, text=True)
        if proc.returncode == 0:
            return proc.stdout.split()
    return None


def main():
    base = sys.argv[1] if len(sys.argv) > 1 else ""
    before = sys.argv[2] if len(sys.argv) > 2 else ""
    if not base or base == "null":
        base = before
    if not base or base == "0000000000000000000000000000000000000000":
        print("Base commit tidak tersedia (push pertama / manual) — lewati guard CHANGELOG.")
        return 0
    if subprocess.run(["git", "rev-parse", "--verify", "--quiet", base],
                       capture_output=True).returncode != 0:
        # Gagal-TERTUTUP: base tak tertelusur (riwayat ditulis ulang) tidak
        # boleh menyempitkan cek ke HEAD~1 — commit kode awal tanpa entri
        # CHANGELOG bisa lolos diam-diam (issue #151).
        print("::error::Base commit tidak terverifikasi (" + base + ") - "
              "guard CHANGELOG gagal-tertutup, bukan fallback HEAD~1.")
        return 1
    changed = changed_files(base)
    if changed is None:
        print("::error::git diff gagal untuk base " + base +
              " - guard CHANGELOG gagal-tertutup.")
        return 1
    needs = [f for f in changed
             if f.startswith(CODE_PREFIXES) or f in CODE_EXACT]
    if needs and "CHANGELOG.md" not in changed:
        print("::error::Perubahan kode (app/src/main, remote.src.html, "
              "app/build.gradle.kts, scripts/) wajib menyertakan update CHANGELOG.md")
        print("\n".join(needs))
        return 1
    print("Guard CHANGELOG: OK")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
