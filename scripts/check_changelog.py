#!/usr/bin/env python3
"""Guard CI: perubahan kode wajib menyertakan update CHANGELOG.md.

Dipakai job `verify` di build.yml (tanpa SDK). Argumen: base-sha PR dan
event.before push; salah satunya boleh kosong (skrip memilih yang valid).
Keluar 1 + ::error:: bila kode berubah tanpa CHANGELOG.md.
"""
import re
import subprocess
import sys

CODE_PREFIXES = ("app/src/main/", "scripts/", "tools/", "gradle/", ".github/")
CODE_EXACT = ("remote.src.html", "app/build.gradle.kts")


def api_verified_bot(base):
    # Metadata git (nama+email) bisa dipalsukan siapa pun yang bisa push
    # (issue #290): author DAN committer bebas diatur lewat git config,
    # jadi cek nama+email saja tidak membuktikan apa pun. Kebenaran ada
    # di sisi GitHub: commit bot ASLI terhubung ke akun dependabot[bot]
    # (author.login hasil resolusi GitHub) DAN bertanda tangan
    # terverifikasi GitHub (commit.verification.verified) — keduanya
    # tidak bisa diproduksi dari push biasa. Gagal-tertutup: gh tidak
    # tersedia, API gagal, atau satu commit tak terbukti -> BUKAN
    # rentang bot (guard berjalan normal, tidak dilewati).
    remote = subprocess.run(
        ["git", "remote", "get-url", "origin"],
        capture_output=True, text=True)
    if remote.returncode != 0:
        return False
    m = re.search(r"github\.com[:/]([^/]+)/([^/]+?)(?:\.git)?$",
                  remote.stdout.strip())
    if not m:
        return False
    slug = m.group(1) + "/" + m.group(2)
    proc = subprocess.run(
        ["git", "log", "--no-merges", "--format=%H", base + "..HEAD"],
        capture_output=True, text=True)
    if proc.returncode != 0:
        return False
    shas = [x.strip() for x in proc.stdout.splitlines() if x.strip()]
    if not shas:
        return False
    for sha in shas:
        out = subprocess.run(
            ["gh", "api", "repos/" + slug + "/commits/" + sha,
             "--jq", "(.author.login == \"dependabot[bot]\") and "
                     "(.commit.verification.verified == true)"],
            capture_output=True, text=True)
        if out.returncode != 0 or out.stdout.strip() != "true":
            return False
    return True


def all_dependabot(base):
    # Seluruh commit non-merge pada rentang authored dependabot[bot]?
    # Commit merge sengaja dikecualikan: author-nya adalah orang yang
    # menekan merge, jadi merge MANUAL PR bot oleh owner tetap terdeteksi
    # sebagai rentang bot murni (issue #202). Pengecualian workflow
    # (actor dependabot) hanya menolong event yang dipicu bot sendiri.
    # Nama DAN email wajib cocok (issue #228/#257) sebagai saringan awal,
    # lalu kebenaran diputuskan verifikasi API GitHub (issue #290).
    proc = subprocess.run(
        ["git", "log", "--no-merges", "--format=%an%x1f%ae", base + "..HEAD"],
        capture_output=True, text=True)
    if proc.returncode != 0:
        return False
    authors = [a.strip() for a in proc.stdout.splitlines() if a.strip()]
    def is_bot(line):
        name, _, email = line.partition("\x1f")
        # Email wajib PERSIS bentuk noreply bot (issue #257): endswith
        # lama menerima alamat manusia/penyerang yang sekadar BERAKHIRAN
        # sufiks bot, mis. 'xdependabot[bot]@users.noreply.github.com'.
        # Dua bentuk sah: polos dan berawalan ID numerik GitHub.
        return name == "dependabot[bot]" and (
            email == "dependabot[bot]@users.noreply.github.com"
            or re.fullmatch(
                r"\d+\+dependabot\[bot\]@users\.noreply\.github\.com",
                email) is not None)
    return bool(authors) and all(is_bot(a) for a in authors) \
        and api_verified_bot(base)


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
        if all_dependabot(base):
            print("Guard CHANGELOG: dilewati - seluruh commit non-merge "
                  "pada rentang ini authored dependabot[bot] (PR bot murni, "
                  "termasuk yang di-merge manual).")
            return 0
        print("::error::Perubahan kode (app/src/main, remote.src.html, "
              "app/build.gradle.kts, scripts/) wajib menyertakan update CHANGELOG.md")
        print("\n".join(needs))
        return 1
    print("Guard CHANGELOG: OK")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
