#!/usr/bin/env python3
"""Siapkan & periksa remote web (dipakai lokal dan CI).

Mode:
  python3 scripts/prepare_remote.py            -> regenerasi remote.html (minified)
                                                 dari remote.src.html (readable).
  python3 scripts/prepare_remote.py --check    -> verifikasi sinkron, node --check,
                                                 larangan kata Indonesia pada UI,
                                                 dan smoke test alur upload
                                                 (scripts/upload_smoke_test.js).
                                                 (exit 1 bila ada masalah)
"""
import html
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
    "belum", "ketuk", "tambah", "hapus", "batal", "jeda",
    "lanjut", "gagal", "selesai", "galeri", "pilih", "tutup",
    "simpan", "unggah", "beranda", "kembali", "muat", "unduh",
    "tidak", "lihat", "buka", "aktifkan", "pengaturan", "tentang",
    "perangkat", "memori", "kosong", "ganti", "ubah", "salin",
    "pindah", "buat", "masuk", "keluar", "kecepatan", "tersisa",
    "menunggu", "antre", "memindai", "dipilih", "dihapus", "menyelesaikan",
    "diperbarui", "diterima", "ditolak", "ditemukan", "dibuat", "diunduh",
    "diunggah", "berhasil", "terkunci", "silahkan", "mohon", "harap",
    "pastikan", "periksa", "kirim", "wajib", "opsional", "izinkan",
    "izin", "verifikasi", "pengguna", "antarmuka", "kata sandi", "penyimpanan",
    "baterai", "jaringan", "koneksi", "dimulai", "berhenti", "dihentikan",
    "memulai", "menghentikan", "dikenal", "diizinkan", "dibatasi", "dipindai",
    "berlaku", "tanpa", "sedang", "masih", "ingin", "perlu",
    "boleh", "harus", "bisa", "dapat", "unduhan", "berkas",
    "halaman", "tombol", "alamat", "sesi", "kunci", "terakhir",
    "pertama", "lainnya", "dari", "untuk", "dengan", "atau",
    "jika", "karena", "saat", "akan", "sudah", "tampilan",
    "layar", "warna", "gelap", "terang", "kamera", "mendukung",
    "menolak", "melanjutkan", "dilanjutkan", "dibatalkan", "dipause", "menyimpan",
    "bersihkan", "dibersihkan", "memperbarui", "sisa",
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


def minify_js(js: str) -> str:
    # Aman untuk sumber ini (tanpa template literal / line-continuation):
    # membuang indentasi, baris kosong, dan komentar // di awal baris tidak
    # mengubah semantik; baris dipertahankan supaya ASI (semicolon insertion)
    # tetap aman. node --check di CI memverifikasi hasilnya.
    out = []
    for line in js.split("\n"):
        line = line.strip()
        if not line:
            continue
        if line.startswith("//"):
            continue
        out.append(line)
    return "\n".join(out)


def minify_html(html: str) -> str:
    # Komentar HTML dibuang (sumber readable tetap di remote.src.html).
    html = re.sub(r"<!--.*?-->", "", html, flags=re.S)
    scripts = []

    def shield_script(match):
        block = match.group(0)
        inner = re.match(r"<script>(.*)</script>", block, re.S)
        if inner:
            block = "<script>" + minify_js(inner.group(1)) + "</script>"
        scripts.append(block)
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


AFFIXES = "me|men|mem|meng|di|ter|ber|ke|pe|peng|se|dip|memp|meny|memper|menge"

# Sufiks produktif Indonesia (issue #535): tanpa grup ini pola yang
# menuntut batas kata tepat sesudah akar LOLOS untuk bentuk imperatif
# dan posesif yang justru paling alami di teks UI — Tambahkan,
# Simpankan, Hapuslah, gagalnya. Boleh BERTUMPUK sampai tiga (issue
# #539/#549: Tambahkanlah, Tambahkannya, tambahkannyalah) dan daftarnya
# memuat posesif -ku/-mu.
SUFFIXES = "kan|an|i|nya|lah|kah|ku|mu"

# Pola terlarang dikompilasi SEKALI di level modul (#294): versi lama
# mengompilasi seluruh pola BANNED_ID di SETIAP panggilan banned_hits.
# (Jumlah persisnya tidak ditulis di sini: angka keras di komentar ini
# pernah basi sesudah dedup daftarnya — issue #334.)
_BANNED_PATTERNS = [
    (
        word,
        re.compile(
            r"\b(?:" + AFFIXES + r")?(?:" + re.escape(word) + r")"
            r"(?:" + SUFFIXES + r"){0,3}\b",
            re.IGNORECASE,
        ),
    )
    for word in BANNED_ID
]


def _normalisasi_guard(text: str) -> str:
    # Normalisasi evasi guard bahasa (issue #335): kata terlarang yang
    # disamarkan lewat escape unicode/hex JS-Kotlin ("hap\\u0075s"),
    # entitas HTML ("hap&#117;s"), atau pemecahan string ("hap"+"us")
    # tampil UTUH di layar tetapi lolos pencocokan teks mentah.
    # Backslash ganda disamarkan lebih dulu: "\\u0075" harfiah
    # (backslash + u0075) BUKAN escape dan tidak boleh ter-decode.
    text = text.replace("\\\\", "\x00")
    text = html.unescape(text)
    text = re.sub(r"\\u([0-9a-fA-F]{4})",
                  lambda m: chr(int(m.group(1), 16)), text)
    text = re.sub(r"\\x([0-9a-fA-F]{2})",
                  lambda m: chr(int(m.group(1), 16)), text)
    text = re.sub(r"""["']\s*\+\s*["']""", "", text)
    return text.replace("\x00", "\\\\")


def banned_hits(text: str) -> list:
    text = _normalisasi_guard(text)
    # Kata utuh ATAU bentuk berimbuhan Indonesia (meN-/di-/ter-/ber-/peN- + kata
    # dasar), mis. "Mengunggah" = meng + unggah. Awalan opsional + akar kata
    # dengan batas kata: "sizing" tidak tertangkap oleh akar "izin".
    hits = []
    for word, pat in _BANNED_PATTERNS:
        for m in pat.finditer(text):
            hits.append((word, text[max(0, m.start() - 20):m.end() + 20]))
    return hits


# Kata kunci JS yang diikuti ekspresi: sesudah kata ini '/' membuka
# literal regex, bukan pembagian.
_JS_KATA_EKSPRESI = {
    "return", "typeof", "case", "in", "of", "new", "delete", "void",
    "instanceof", "yield", "await", "do", "else",
}


def _buang_komentar_js_src(src: str) -> str:
    r"""Buang komentar JS dengan pemindai sadar string DAN literal regex.

    Tokenizer regex lama (#273) tidak mengenal literal regex: pada
    /https?:\/\// dua garis miring terakhir terbaca sebagai awal
    komentar '//', sehingga sisa baris terbuang sebelum guard bahasa
    berjalan dan kata terlarang sesudahnya lolos (#314). Pemindai ini
    membedakan '/' pembuka regex dari '/' pembagian lewat kategori
    token sebelumnya (regex hanya sah sesudah operator/kata kunci
    ekspresi), menyalin string/template/regex utuh, dan membuang
    komentar baris/blok. Batas heuristik yang diterima: pembagian
    langsung sesudah '++'/'--' bisa terbaca sebagai regex.
    """
    out = []
    i, n = 0, len(src)
    prev = ""  # "" awal | "op" operator | "val" nilai
    while i < n:
        c = src[i]
        if c in "'\"`":
            j = i + 1
            while j < n:
                if src[j] == "\\":
                    j += 2
                    continue
                if src[j] == c:
                    j += 1
                    break
                if c != "`" and src[j] == "\n":
                    break
                j += 1
            out.append(src[i:j])
            i = j
            prev = "val"
            continue
        if c == "/" and i + 1 < n and src[i + 1] == "/":
            j = src.find("\n", i)
            i = n if j == -1 else j
            continue
        if c == "/" and i + 1 < n and src[i + 1] == "*":
            j = src.find("*/", i + 2)
            i = n if j == -1 else j + 2
            continue
        if c == "/" and prev != "val":
            j = i + 1
            in_class = False
            ok = False
            while j < n:
                ch = src[j]
                if ch == "\\":
                    j += 2
                    continue
                if ch == "\n":
                    break
                if ch == "[":
                    in_class = True
                elif ch == "]":
                    in_class = False
                elif ch == "/" and not in_class:
                    ok = True
                    j += 1
                    break
                j += 1
            if ok:
                while j < n and src[j].isalpha():
                    j += 1
                out.append(src[i:j])
                i = j
                prev = "val"
                continue
            out.append(c)
            i += 1
            prev = "op"
            continue
        if c.isalnum() or c in "_$":
            j = i
            while j < n and (src[j].isalnum() or src[j] in "_$"):
                j += 1
            kata = src[i:j]
            out.append(kata)
            prev = "op" if kata in _JS_KATA_EKSPRESI else "val"
            i = j
            continue
        out.append(c)
        if c in ")]":
            prev = "val"
        elif not c.isspace():
            prev = "op"
        i += 1
    return "".join(out)


def strip_comments(text: str) -> str:
    # Komentar HTML dibuang global (tidak bisa bersarang).
    text = re.sub(r"<!--.*?-->", "", text, flags=re.S)

    # Komentar JS hanya dibuang DI DALAM blok <script>, secara sadar
    # string. Regex lama menghapus dari '//' pertama di baris — pada
    # berkas minified (satu baris bisa berisi seluruh script) itu
    # membuang sisa baris termasuk string label UI sesudah URL pertama,
    # jadi kata terlarang di sana lolos dari guard bahasa (#273).
    def bersihkan(match):
        return _buang_komentar_js_src(match.group(0))

    return re.sub(r"<script>.*?</script>", bersihkan, text, flags=re.S)


def scan_remote() -> list:
    html = OUT.read_text(encoding="utf-8")
    # Nilai properti content: di dalam blok style ikut dipindai
    # (issue #536): teks itu ter-render sebagai UI (lencana/label).
    # Dibatasi ke content: saja (issue #540): mengekstrak SEMUA
    # string terkutip menyapu nama berkas aset url("...") dan nilai
    # properti lain yang bukan teks UI — positif palsu.
    hits = []
    for block in re.findall(r"<style>(.*?)</style>", html, flags=re.S):
        # IGNORECASE (issue #550): nama properti CSS tidak peka
        # huruf — CONTENT: kapital ter-render sama persis tetapi
        # dulu lolos dari ekstraksi ini.
        for decl in re.findall(r"content\s*:\s*([^;]+)", block, flags=re.IGNORECASE):
            for dq, sq in re.findall(r'"([^"\n]*)"|\'([^\'\n]*)\'', decl):
                hits += banned_hits(dq or sq)
    html = re.sub(r"<style>.*?</style>", "", html, flags=re.S)
    return hits + banned_hits(strip_comments(html))


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
    # Tokenizer satu-pass: raw string, komentar blok, komentar baris, char
    # literal ('"'), atau string biasa — hanya isi string biasa yang dipindai.
    # `//` di dalam string (mis. "https://") ikut termakan token string
    # sehingga tidak dianggap komentar dan tidak merusak pairing (false +/-).
    token_re = re.compile(
        r'"""[\s\S]*?"""|/\*.*?\*/|//[^\n]*|\'(?:[^\'\\\n]|\\.)*\''
        r'|"((?:[^"\\]|\\.)*)"', re.S
    )
    for path in sorted((ROOT / "app" / "src" / "main" / "java").rglob("*.kt")):
        src = path.read_text(encoding="utf-8")
        for m in token_re.finditer(src):
            lit = m.group(1)
            if lit is None:
                continue
            for word, _ in banned_hits(lit):
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


def run_upload_smoke() -> str:
    # Smoke test alur upload klien (stub DOM/XHR, tanpa dependensi). Menangkap
    # regresi seperti argumen uploadFiles() yang tertukar (listEl = callback).
    script = ROOT / "scripts" / "upload_smoke_test.js"
    try:
        proc = subprocess.run(
            ["node", str(script)], capture_output=True, text=True, cwd=str(ROOT)
        )
    except FileNotFoundError:
        return "node tidak ditemukan di PATH (dibutuhkan untuk smoke test upload)"
    if proc.returncode != 0:
        return (proc.stderr or proc.stdout or "").strip()
    print((proc.stdout or "").strip())
    return ""


def check_upload_call(text: str) -> list:
    # Guard regresi: pemanggilan uploadFiles() di startFsUpload pernah
    # tertukar urutannya (fsProgressList dikirim sebagai `done`, callback
    # sebagai `listEl`) sehingga upload selalu gagal diam-diam dengan
    # "listEl.appendChild is not a function". Pastikan callback (argumen
    # ke-8 = done) SELALU mendahului fsProgressList (argumen ke-9 = listEl).
    problems = []
    start = text.find("function startFsUpload")
    if start < 0:
        # Fail-closed (issue #194): jangkar hilang/berganti nama tidak
        # boleh menonaktifkan guard ini diam-diam.
        problems.append(
            "startFsUpload tidak ditemukan — guard argumen uploadFiles() "
            "tidak bisa memeriksa (jangkar hilang/berganti nama?)"
        )
        return problems
    call = text.find("uploadFiles(", start)
    if call < 0:
        problems.append("startFsUpload tidak memanggil uploadFiles()")
        return problems
    body = text[call + len("uploadFiles("):]
    depth = 0
    end = -1
    for i, ch in enumerate(body):
        if ch == "(":
            depth += 1
        elif ch == ")":
            if depth == 0:
                end = i
                break
            depth -= 1
    if end < 0:
        problems.append("uploadFiles(): argumen tidak bisa diurai")
        return problems
    args = body[:end]
    cb_at = args.find("function (ok, failed)")
    list_at = args.find("fsProgressList")
    if cb_at < 0 or list_at < 0:
        problems.append(
            "uploadFiles(): pola argumen (callback/fsProgressList) tidak ditemukan"
        )
        return problems
    if list_at < cb_at:
        problems.append(
            "uploadFiles(): argumen done/listEl tertukar - callback harus sebelum fsProgressList"
        )
    return problems


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

        problems.extend(check_upload_call(src_text))

        smoke_error = run_upload_smoke()
        if smoke_error:
            problems.append("smoke test upload gagal: " + smoke_error)

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
