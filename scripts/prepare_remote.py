#!/usr/bin/env python3
"""Siapkan & periksa remote web (dipakai lokal dan CI).

Mode:
  python3 scripts/prepare_remote.py            -> regenerasi remote.html (minified)
                                                 dari remote.src.html (readable).
  python3 scripts/prepare_remote.py --check    -> verifikasi sinkron, node --check,
                                                 dan larangan kata Indonesia pada UI.
                                                 (exit 1 bila ada masalah)
"""
import re
import subprocess
import sys
import tempfile
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
SRC = ROOT / "remote.src.html"
OUT = ROOT / "app" / "src" / "main" / "assets" / "remote.html"

# Kata Indonesia yang tidak boleh muncul di string UI (guard i18n).
BANNED_ID = [
    "belum", "ketuk", "tambah", "hapus", "batal", "jeda", "lanjut", "gagal",
    "selesai", "galeri", "pilih", "tutup", "simpan", "unggah", "beranda",
    "kembali", "muat", "unduh", "tidak", "lihat", "buka", "aktifkan",
    "pengaturan", "tentang", "perangkat", "memori", "kosong", "ganti", "ubah",
    "salin", "pindah", "buat", "masuk", "keluar", "kecepatan", "tersisa",
    "menunggu", "antre", "memindai", "dipilih", "dihapus", "menyelesaikan",
    "diperbarui", "diterima", "ditolak", "ditemukan", "dibuat", "diunduh",
    "diunggah", "berhasil", "terkunci", "silahkan", "mohon", "harap",
    "pastikan", "periksa", "kirim", "wajib", "opsional", "izinkan", "izin",
    "verifikasi", "pengguna", "antarmuka", "kata sandi", "penyimpanan",
    "baterai", "jaringan", "koneksi", "dimulai", "berhenti", "dihentikan",
    "memulai", "menghentikan", "dikenal", "diizinkan", "dibatasi", "dipindai",
    "berlaku", "tanpa", "sedang", "masih", "ingin", "perlu", "boleh", "harus",
    "bisa", "dapat", "unduhan", "berkas", "halaman", "tombol", "alamat",
    "sesi", "kunci", "terakhir", "pertama", "lainnya", "dari", "untuk",
    "dengan", "atau", "jika", "karena", "saat", "akan", "sudah", "tampilan",
    "layar", "warna", "gelap", "terang", "kamera", "mendukung", "menolak",
    "menolak", "melanjutkan", "dilanjutkan", "dibatalkan", "dipause",
    "menyimpan", "penyimpanan", "bersihkan", "dibersihkan", "memperbarui",
]


def minify_css(css: str) -> str:
    css = re.sub(r"/\*.*?\*/", "", css, flags=re.S)
    protected = []

    def shield(match):
        protected.append(match.group(0))
        return "\x00%d\x00" % (len(protected) - 1)

    # Lindungi url(), calc(), dan string dulu supaya isinya tidak diubah.
    css = re.sub(r"url\([^)]*\)", shield, css)
    css = re.sub(r"calc\([^)]*\)", shield, css)
    css = re.sub(r"'[^']*'", shield, css)
    css = re.sub(r'"[^"]*"', shield, css)
    css = re.sub(r"\s+", " ", css)
    css = re.sub(r"\s*([{};,:+>~])\s*", r"\1", css)
    css = re.sub(r"\s*\{\s*", "{", css)

    def unshield(match):
        return protected[int(match.group(1))]

    css = re.sub(r"\x00(\d+)\x00", unshield, css)
    return css.strip()


def minify_html(html: str) -> str:
    # Komentar HTML dibuang (sumber readable tetap di remote.src.html).
    html = re.sub(r"<!--.*?-->", "", html, flags=re.S)
    scripts = []

    def shield_script(match):
        scripts.append(match.group(0))
        return "\x01%d\x01" % (len(scripts) - 1)

    # Blok <script> dijaga utuh: minifier tidak boleh menyentuh isi JS
    # (string JS bisa mengandung "> <" yang harus tetap utuh).
    html = re.sub(r"<script>.*?</script>", shield_script, html, flags=re.S)

    def css_repl(match):
        return "<style>" + minify_css(match.group(1)) + "</style>"

    html = re.sub(r"<style>(.*?)</style>", css_repl, html, flags=re.S)
    html = re.sub(r">\s+<", "><", html)

    def unshield(match):
        return scripts[int(match.group(1))]

    html = re.sub(r"\x01(\d+)\x01", unshield, html)
    return html.strip()


def banned_hits(text: str) -> list:
    hits = []
    for word in BANNED_ID:
        for m in re.finditer(r"\b" + re.escape(word) + r"\b", text, re.IGNORECASE):
            hits.append((word, m.group(0)))
    return hits


def strip_comments(text: str) -> str:
    text = re.sub(r"<!--.*?-->", "", text, flags=re.S)
    text = re.sub(r"//[^\n]*", "", text)
    text = re.sub(r"/\*.*?\*/", "", text, flags=re.S)
    return text


def scan_remote() -> list:
    html = OUT.read_text(encoding="utf-8")
    html = re.sub(r"<style>.*?</style>", "", html, flags=re.S)
    return banned_hits(strip_comments(html))


def scan_xml() -> list:
    hits = []
    for xml_path in sorted((ROOT / "app" / "src" / "main" / "res" / "values").glob("*.xml")):
        text = xml_path.read_text(encoding="utf-8")
        text = re.sub(r"<!--.*?-->", "", text, flags=re.S)
        for m in re.finditer(r"<string[^>]*>(.*?)</string>|<item>(.*?)</item>", text, re.S):
            value = (m.group(1) or m.group(2) or "")
            for word, _ in banned_hits(value):
                hits.append(f"{xml_path.name}:{word}")
    return hits


def scan_kotlin() -> list:
    hits = []
    for path in sorted((ROOT / "app" / "src" / "main" / "java").rglob("*.kt")):
        src = path.read_text(encoding="utf-8")
        src = re.sub(r"//[^\n]*", "", src)
        src = re.sub(r"/\*.*?\*/", "", src, flags=re.S)
        src = re.sub(r'"""', "", src)  # lewati raw string (HTML/CSS)
        for m in re.finditer(r'"((?:[^"\\]|\\.)*)"', src):
            for word, _ in banned_hits(m.group(1)):
                hits.append(f"{path.name}:{word}")
    return hits


def node_check() -> list:
    errors = []
    html = OUT.read_text(encoding="utf-8")
    scripts = re.findall(r"<script>(.*?)</script>", html, re.S)
    for i, script in enumerate(scripts):
        with tempfile.NamedTemporaryFile("w", suffix=".js", delete=False, encoding="utf-8") as f:
            f.write(script)
            name = f.name
        try:
            proc = subprocess.run(
                ["node", "--check", name], capture_output=True, text=True
            )
            if proc.returncode != 0:
                errors.append(f"script #{i}: {proc.stderr.strip()}")
        except FileNotFoundError:
            errors.append("node tidak ditemukan di PATH (dibutuhkan untuk --check)")
        finally:
            Path(name).unlink(missing_ok=True)
    return errors


def main() -> int:
    if not SRC.exists():
        print(f"ERROR: {SRC} tidak ditemukan.")
        return 1
    src_text = SRC.read_text(encoding="utf-8")
    minified = minify_html(src_text)

    if len(sys.argv) > 1 and sys.argv[1] == "--check":
        problems = []

        if OUT.exists() and OUT.read_text(encoding="utf-8") != minified:
            problems.append(
                f"{OUT.name} TIDAK sinkron dengan remote.src.html — "
                "jalankan: python3 scripts/prepare_remote.py lalu commit keduanya."
            )

        js_errors = node_check()
        if js_errors:
            problems.append("node --check gagal: " + " | ".join(js_errors))

        remote_hits = scan_remote()
        if remote_hits:
            problems.append(
                "remote.html memuat kata Indonesia: "
                + ", ".join(sorted({w for w, _ in remote_hits}))
            )
        xml_hits = scan_xml()
        if xml_hits:
            problems.append("values/*.xml memuat kata Indonesia: " + ", ".join(xml_hits))
        kt_hits = scan_kotlin()
        if kt_hits:
            problems.append("string literal Kotlin memuat kata Indonesia: " + ", ".join(kt_hits))

        if problems:
            print("CHECK GAGAL:")
            for p in problems:
                print(" -", p)
            return 1
        print(
            f"CHECK OK: {OUT.name} sinkron, JS valid, "
            f"UI Inggris ({len(minified.encode('utf-8'))} bytes)."
        )
        return 0

    OUT.write_text(minified, encoding="utf-8")
    print(
        f"OK: {SRC.name} -> {OUT.name} "
        f"({len(src_text.encode('utf-8'))} -> {len(minified.encode('utf-8'))} bytes)."
    )
    return 0


if __name__ == "__main__":
    sys.exit(main())
