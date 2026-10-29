#!/usr/bin/env python3
"""Verifikasi otomatis path yang baru dikerjakan (contek vaultwarden-host).

Memastikan penghapusan kode tak merusak acuan dan aturan repo tetap
ditaati, tanpa toolchain Android (hanya baca kode + git diff + parse XML).
Diadaptasi untuk repo ini: Kotlin (.kt), remote.src.html, keputusan historis
(QR/zxing, values-night, SpeedChartView, endpoint mati).

Pakai:
    python3 tools/verifikasi-path.py                 # kerja lokal + belum push
    python3 tools/verifikasi-path.py --semua         # seluruh repo
    python3 tools/verifikasi-path.py --rentang A...B # rentang commit (dipakai CI)
    python3 tools/verifikasi-path.py app/... Main.kt

Keluar 0 bila lolos (peringatan tak menggagalkan), 1 bila ada yang gagal.
"""
import argparse
import os
import re
import subprocess
import sys
import xml.etree.ElementTree as ET

AKAR = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
MAIN = os.path.join(AKAR, "app", "src", "main")
UJI = os.path.join(AKAR, "app", "src", "test")
KATA_KUNCI_KOTLIN = {
    "if", "for", "while", "when", "catch", "return", "new", "throw",
    "else", "do", "try", "synchronized", "assert", "case", "fun",
    "val", "var", "class", "object", "interface", "import", "package",
}


def muat_bekas_hapusan():
    # Fail-closed (issue #183): daftar hilang/kosong TIDAK boleh menurunkan
    # guard diam-diam ke satu nama cadangan — gerbang harus berhenti keras
    # supaya kehilangannya langsung terlihat.
    path = os.path.join(AKAR, "tools", "bekas-hapusan.txt")
    nama = set()
    try:
        with open(path, encoding="utf-8") as f:
            for baris in f:
                baris = baris.strip()
                if baris and not baris.startswith("#"):
                    nama.add(baris.split()[0])
    except (OSError, UnicodeDecodeError):
        # UnicodeDecodeError adalah ValueError, bukan OSError (issue
        # #614): berkas yang ADA tetapi non-UTF-8 dulu lolos dari
        # except ini dan menggugurkan gerbang dengan traceback saat
        # import, melewatkan vonis bernama fail-closed di bawah.
        nama = set()
    if not nama:
        raise SystemExit(
            "GAGAL: tools/bekas-hapusan.txt hilang, kosong, atau tak "
            "terbaca sebagai UTF-8 — daftar nama terlarang adalah "
            "bagian dari gerbang; pulihkan berkasnya."
        )
    return nama


BEKAS_HAPUSAN = muat_bekas_hapusan()
OVERRIDE_AMAN = {
    "onCreate", "onStart", "onResume", "onPause", "onStop", "onDestroy",
    "onReceive", "onHandleIntent", "onBind", "onUnbind", "onUpgrade",
    "onAlarm", "onBoot", "onClick", "onFocusChange",
}


def jalan(*args):
    try:
        hasil = subprocess.run(
            list(args), cwd=AKAR, capture_output=True, text=True, timeout=60)
    except Exception:
        return None
    return hasil.stdout if hasil.returncode == 0 else None


def git_hidup():
    """True bila git menjawab rev-parse di repo ini DAN HEAD terbaca.
    jalan() mengembalikan None untuk SEMUA kegagalan git, jadi tanpa cek
    ini repo tanpa .git (atau git mati) mengubah diff kosong menjadi
    vonis LOLOS semu (issue #646; mode jalur ikut ditutup issue #661).
    --git-dir saja terlalu dangkal (issue #662): ia sukses walau HEAD
    korup dan semua diff gagal, sehingga gerbang menilai index melawan
    pohon kosong dan memberi diagnosis palsu ke berkas tak disentuh —
    HEAD wajib ikut terverifikasi agar vonisnya jujur "git tidak
    berfungsi"."""
    return (
        jalan("git", "rev-parse", "--git-dir") is not None
        and jalan("git", "rev-parse", "--verify", "HEAD") is not None
    )


def jalan_output(*args):
    """Seperti jalan() tetapi stdout dikembalikan apa adanya walau
    returncode bukan nol (None hanya bila perintah gagal dijalankan).
    Untuk perintah yang justru BERISI informasi saat keluar bukan-nol,
    mis. `git diff --check` (issue #407)."""
    try:
        hasil = subprocess.run(
            list(args), cwd=AKAR, capture_output=True, text=True, timeout=60)
    except Exception:
        return None
    return hasil.stdout


def daftar_berkas_berubah(rentang):
    if rentang:
        for pemisah in ("...", ".."):
            if pemisah in rentang:
                a, b = rentang.split(pemisah, 1)
                if set(a) == {"0"} or not a:
                    return None
                # -z: nama non-ASCII tidak dikutip oktal oleh git,
                # kalau tidak pola nama (secret/keystore) tak cocok
                # pada nama asli dan berkasnya lolos (issue #312,
                # jebakan yang sama dengan #213 di nama_tak_lacak).
                out = jalan("git", "diff", "--name-only", "-z",
                            a + pemisah + b)
                if out is not None:
                    return [x for x in out.split("\0") if x]
                return None
    return None


def nama_tak_lacak():
    # -z: git tidak mengutip nama non-ASCII (core.quotepath menulis
    # oktal '"Prob\303\251.kt"' sehingga open() gagal dan berkas
    # terlewati diam-diam dari suntikan diff --semua, issue #213).
    out = jalan("git", "ls-files", "--others", "--exclude-standard", "-z")
    return [x for x in out.split("\0") if x] if out else []


def baca(path):
    try:
        with open(os.path.join(AKAR, path), encoding="utf-8", errors="replace") as f:
            return f.read()
    except OSError:
        return ""


def jalan_repo(rel, ext=(".kt",)):
    for akar, dirs, berkas in os.walk(os.path.join(AKAR, rel)):
        dirs[:] = [d for d in dirs if d != ".git"]
        for nama in berkas:
            if ext is None or nama.endswith(ext):
                yield os.path.relpath(os.path.join(akar, nama), AKAR)


def himpun_definisi():
    definisi = {}
    acuan = {}

    def rapikan(jenis, nama):
        return nama.replace(".", "_") if jenis == "style" else nama

    pola_def = re.compile(r'<(style|string|color|dimen|plurals)\s+name="([^"]+)"')
    pola_array = re.compile(r'<(string-array|integer-array|array)\s+name="([^"]+)"')

    def tambah(kunci, nama):
        definisi.setdefault(kunci, set()).add(rapikan(kunci, nama))

    akar_res = os.path.join(MAIN, "res")
    for path in jalan_repo(os.path.join("app", "src", "main", "res"), ext=(".xml",)):
        isi = baca(path)
        for jenis, nama in pola_def.findall(isi):
            tambah(jenis, nama)
        for jenis, nama in pola_array.findall(isi):
            tambah("array", nama)
        for nama in re.findall(r'@\+id/([A-Za-z0-9_]+)', isi):
            tambah("id", nama)
    if os.path.isdir(akar_res):
        for nama_dir in sorted(os.listdir(akar_res)):
            if nama_dir.startswith("values"):
                continue
            tipe = nama_dir.split("-")[0]
            wadah = os.path.join(akar_res, nama_dir)
            if not os.path.isdir(wadah):
                continue
            for akar, _, berkas in os.walk(wadah):
                for nama in berkas:
                    tambah(tipe, os.path.splitext(nama)[0])

    pola_pakai = [
        ("style", r"@style/([A-Za-z0-9_.]+)", r"(?<!android\.)R\.style\.([A-Za-z0-9_]+)"),
        ("string", r"@string/([A-Za-z0-9_]+)", r"(?<!android\.)R\.string\.([A-Za-z0-9_]+)"),
        ("color", r"@color/([A-Za-z0-9_]+)", r"(?<!android\.)R\.color\.([A-Za-z0-9_]+)"),
        ("dimen", r"@dimen/([A-Za-z0-9_]+)", r"(?<!android\.)R\.dimen\.([A-Za-z0-9_]+)"),
        ("array", r"@array/([A-Za-z0-9_]+)", r"(?<!android\.)R\.array\.([A-Za-z0-9_]+)"),
        ("drawable", r"@drawable/([A-Za-z0-9_]+)", r"(?<!android\.)R\.drawable\.([A-Za-z0-9_]+)"),
        ("layout", None, r"(?<!android\.)R\.layout\.([A-Za-z0-9_]+)"),
        ("id", r"@id/([A-Za-z0-9_]+)", r"(?<!android\.)R\.id\.([A-Za-z0-9_]+)"),
        ("plurals", r"@plurals/([A-Za-z0-9_]+)", r"(?<!android\.)R\.plurals\.([A-Za-z0-9_]+)"),
    ]
    pola_generik_xml = re.compile(r"@([a-z]+)/([A-Za-z0-9_.]+)")
    pola_generik_r = re.compile(r"(?<!android\.)R\.([a-z]+)\.([A-Za-z0-9_]+)")
    pola_android = re.compile(r"@android:[a-z]+/[A-Za-z0-9_.]+")
    pola_string = re.compile(r'"(?:[^"\\]|\\.)*"')
    pemindai = [p for p in jalan_repo("app", ext=(".kt", ".xml",))
                if "/src/test/" not in p.replace(os.sep, "/")]
    pemindai.append("app/src/main/AndroidManifest.xml")
    for path in pemindai:
        isi = baca(path)
        if not isi:
            continue
        isi = pola_android.sub("", isi)
        isi = pola_string.sub('""', isi)
        for jenis, pola_xml, pola_r in pola_pakai:
            for pola in (pola_xml, pola_r):
                if pola:
                    for nama in re.findall(pola, isi):
                        acuan.setdefault(jenis, set()).add(rapikan(jenis, nama))
        for tipe, nama in pola_generik_xml.findall(isi):
            acuan.setdefault(tipe, set()).add(rapikan(tipe, nama))
        for tipe, nama in pola_generik_r.findall(isi):
            acuan.setdefault(tipe, set()).add(rapikan(tipe, nama))
    return definisi, acuan


def cek_xml(gagal):
    buruk = []
    for path in jalan_repo(os.path.join("app", "src", "main", "res"), ext=(".xml",)):
        try:
            ET.parse(os.path.join(AKAR, path))
        except ET.ParseError as e:
            buruk.append("%s (%s)" % (path, e))
    for path in ("app/src/main/AndroidManifest.xml",):
        try:
            ET.parse(os.path.join(AKAR, path))
        except ET.ParseError:
            buruk.append("%s (rusak)" % path)
        except OSError:
            # Manifest hilang/ter-rename adalah vonis bernama, bukan
            # traceback FileNotFoundError yang menggugurkan gerbang
            # (issue #587, kelas yang sama dengan #562/#571).
            buruk.append("%s (hilang)" % path)
    if buruk:
        gagal.append("XML rusak: " + ", ".join(buruk))


AWALAN_STYLE_PUSTAKA = (
    "TextAppearance_AppCompat", "Theme_AppCompat", "Widget_AppCompat",
    "Platform_AppCompat", "Base_V7_", "Base_Widget_", "Animation_AppCompat",
)


def cek_acuan_yatim(gagal):
    definisi, acuan = himpun_definisi()
    for jenis, dipakai in sorted(acuan.items()):
        ada = definisi.get(jenis, set())
        if jenis == "style":
            dipakai = {n for n in dipakai
                       if not n.startswith(AWALAN_STYLE_PUSTAKA)}
        yatim = sorted(n for n in dipakai if n not in ada)
        if yatim:
            gagal.append("Acuan %s yatim (dipakai tapi tak terdefinisi): %s"
                         % (jenis, ", ".join(yatim[:10])))


def namespace_aplikasi():
    tetap = baca("app/build.gradle.kts")
    cocok = re.search(r'namespace\s*=\s*"([^"]+)"', tetap)
    return cocok.group(1) if cocok else "com.tasirin.httpdownloadmanager"


def cek_manifest(gagal):
    try:
        pohon = ET.parse(os.path.join(AKAR, "app/src/main/AndroidManifest.xml"))
    except (ET.ParseError, OSError):
        # Rusak sudah divonis cek_xml; hilang divonis cek_xml juga
        # ("(hilang)") — di sini cukup berhenti agar tak traceback.
        return
    ns = namespace_aplikasi()
    for el in pohon.getroot().iter():
        nama = el.get("{http://schemas.android.com/apk/res/android}name", "")
        if not nama or el.tag not in ("activity", "service", "receiver", "provider"):
            continue
        if not nama.startswith(".") and not nama.startswith(ns):
            continue
        rel = nama[1:] if nama.startswith(".") else nama[len(ns) + 1:]
        penuh = os.path.join(MAIN, "java", *ns.split("."), *rel.split("."))
        for ext in (".kt", ".java"):
            if os.path.isfile(penuh + ext):
                break
        else:
            gagal.append("Komponen manifest tanpa kelas: %s (hapus %s?)" % (nama, rel))


def cek_duplikat_definisi(gagal):
    pola = re.compile(r'<(style|string|color|dimen|plurals|string-array|integer-array|array)\s+name="([^"]+)"')
    for path in jalan_repo(os.path.join("app", "src", "main", "res"), ext=(".xml",)):
        if "/values" not in path.replace(os.sep, "/"):
            continue
        hitung = {}
        for jenis, nama in pola.findall(baca(path)):
            kunci = jenis + "/" + nama
            hitung[kunci] = hitung.get(kunci, 0) + 1
        ganda = sorted(k for k, n in hitung.items() if n > 1)
        if ganda:
            gagal.append("Definisi ganda di %s: %s" % (path, ", ".join(ganda[:5])))


def berkas_dihapus(rentang):
    args = None
    if rentang and ("..." in rentang or ".." in rentang):
        for pemisah in ("...", ".."):
            if pemisah in rentang:
                a, b = rentang.split(pemisah, 1)
                if set(a) == {"0"} or not a:
                    return []
                args = ["git", "diff", "--name-status", "-z", a + pemisah + b]
                break
    if args is None:
        args = ["git", "diff", "--name-status", "-z"]
    out = jalan(*args)
    if out is None:
        return []
    # -z (issue #376): tanpa ini git mengutip path non-ASCII gaya C
    # ('"Prob\303\251.kt"') sehingga path hasil split tidak cocok dengan
    # syarat app/src/main/java/ + .kt/.java di cek_berkas_dihapus dan
    # penghapusan berkas non-ASCII luput dinilai — kelas bug yang sama
    # dengan #346/#358. Format -z: token status dan path bergantian
    # dipisah NUL; status R/C membawa DUA path (asal lalu tujuan).
    return parse_hapus_name_status_z(out)


def parse_hapus_name_status_z(out):
    """Ekstrak path terhapus dari keluaran `git diff --name-status -z`.

    Dipakai bersama oleh berkas_dihapus (diff kerja/rentang) dan cabang
    staged di utama() (issue #406: cabang itu sebelumnya mem-parse
    --name-status TANPA -z dengan split baris, sehingga penghapusan
    staged berkas non-ASCII lolos dengan cara yang sama seperti #376).
    """
    token = out.split("\0")
    hapus = []
    i = 0
    while i < len(token):
        status = token[i]
        i += 1
        if not status:
            continue
        jumlah_path = 2 if status[0] in "RC" else 1
        for _ in range(jumlah_path):
            if i >= len(token):
                break
            if status.startswith("D"):
                hapus.append(token[i])
            i += 1
    return hapus


def disebut_kata(nama, lewat=()):
    pola = re.compile(r"\b%s\b" % re.escape(nama))
    temu = []
    for path in jalan_repo("app", ext=(".kt", ".xml")):
        if path in lewat:
            continue
        for i, baris in enumerate(baca(path).splitlines(), 1):
            if pola.search(baris):
                temu.append("%s:%d" % (path, i))
                if len(temu) >= 6:
                    return temu
    return temu


def cek_berkas_dihapus(gagal, hapus):
    for path in hapus:
        if not (path.startswith("app/src/main/java/") and path.endswith((".kt", ".java"))):
            continue
        kelas = os.path.splitext(os.path.basename(path))[0]
        siapa = disebut_kata(kelas, lewat=[path])
        if siapa:
            gagal.append("Berkas %s dihapus tapi %s masih disebut: %s"
                         % (path, kelas, ", ".join(siapa)))


def _urai_path_git(token):
    """Urai satu token path dari header 'diff --git'.

    Git mengutip path non-ASCII/berkarakter khusus dengan gaya C
    ("b/Prob\\303\\251.kt"); tanpa penguraian ini path hasil parse
    berakhir dengan 'kt"' dan tersaring keluar dari syarat .kt/.java
    sehingga isi berkas itu luput dari semua cek isi-diff (issue
    #346). Token tak terutip dikembalikan apa adanya.
    """
    if not token.startswith('"'):
        return token
    mentah = bytearray()
    i = 1
    while i < len(token) and token[i] != '"':
        c = token[i]
        if c == "\\" and i + 1 < len(token):
            nxt = token[i + 1]
            if nxt in "01234567":
                j = i + 1
                oktal = ""
                while j < len(token) and len(oktal) < 3 and token[j] in "01234567":
                    oktal += token[j]
                    j += 1
                mentah.append(int(oktal, 8))
                i = j
                continue
            mentah.extend(
                {"n": b"\n", "t": b"\t", '"': b'"', "\\": b"\\"}.get(
                    nxt, nxt.encode("utf-8", "surrogateescape")
                )
            )
            i += 2
            continue
        mentah.extend(c.encode("utf-8", "surrogateescape"))
        i += 1
    return mentah.decode("utf-8", "surrogateescape")


_HEADER_DIFF_RE = re.compile(
    r'^diff --git ("(?:[^"\\]|\\.)*"|\S+) ("(?:[^"\\]|\\.)*"|\S+)\s*$'
)


def path_dari_header_diff(baris):
    """Path sisi-b dari baris header 'diff --git'; "" bila tak terurai."""
    m = _HEADER_DIFF_RE.match(baris)
    if not m:
        return ""
    path = _urai_path_git(m.group(2))
    return path[2:] if path.startswith("b/") else path


def tambah_kt(diff):
    path = ""
    for baris in diff.splitlines():
        if baris.startswith("diff --git "):
            path = path_dari_header_diff(baris)
        elif baris.startswith("+") and not baris.startswith("+++"):
            if path.endswith((".kt", ".java")):
                yield path, baris[1:]


def hapus_kt(diff):
    path = ""
    for baris in diff.splitlines():
        if baris.startswith("diff --git "):
            path = path_dari_header_diff(baris)
        elif baris.startswith("-") and not baris.startswith("---"):
            if path.endswith((".kt", ".java")):
                yield path, baris[1:]


def kupas_kotlin(isi):
    keluar = []
    i, n = 0, len(isi)
    while i < n:
        dua = isi[i:i + 2]
        tiga = isi[i:i + 3]
        if tiga == '"""':
            i += 3
            while i < n and isi[i:i + 3] != '"""':
                i += 1
            i += 3
        elif dua == '//':
            while i < n and isi[i] != '\n':
                i += 1
        elif dua == '/*':
            # Komentar blok Kotlin bisa BERSARANG (issue #482): berhenti
            # di */ yang menyeimbangkan /* terluar, bukan */ pertama.
            i += 2
            kedalaman = 1
            while i < n and kedalaman:
                if isi[i:i + 2] == '/*':
                    kedalaman += 1
                    i += 2
                elif isi[i:i + 2] == '*/':
                    kedalaman -= 1
                    i += 2
                else:
                    i += 1
        elif isi[i] in ('"', "'"):
            kutip = isi[i]
            i += 1
            while i < n:
                if isi[i] == '\\':
                    i += 2
                    continue
                if isi[i] == kutip:
                    i += 1
                    break
                if kutip == '"' and isi[i] == '\n':
                    break
                i += 1
        else:
            keluar.append(isi[i])
            i += 1
    return "".join(keluar)


def kupas_komentar(isi):
    keluar = []
    i, n = 0, len(isi)
    while i < n:
        dua = isi[i:i + 2]
        tiga = isi[i:i + 3]
        if tiga == '"""':
            keluar.append('"""')
            i += 3
            while i < n and isi[i:i + 3] != '"""':
                keluar.append(isi[i])
                i += 1
            keluar.append('"""')
            i += 3
        elif dua[0] == '"' or dua[0] == "'":
            # String/char literal disalin utuh: `//` di dalam string
            # (mis. "://") bukan komentar (tanpa ini sisa baris ikut
            # terhapus dan cek import salah vonis "import mati", mis.
            # Locale di HlsParser.kt yang dipakai sebaris string "://").
            kutip = dua[0]
            keluar.append(isi[i])
            i += 1
            while i < n:
                if kutip == '"' and isi[i] == '\n':
                    break
                keluar.append(isi[i])
                if isi[i] == '\\':
                    i += 1
                    if i < n:
                        keluar.append(isi[i])
                elif isi[i] == kutip:
                    i += 1
                    break
                i += 1
        elif dua == '//':
            while i < n and isi[i] != '\n':
                i += 1
        elif dua == '/*':
            # Sadar sarang seperti kupas_kotlin (issue #482).
            i += 2
            kedalaman = 1
            while i < n and kedalaman:
                if isi[i:i + 2] == '/*':
                    kedalaman += 1
                    i += 2
                elif isi[i:i + 2] == '*/':
                    kedalaman -= 1
                    i += 2
                else:
                    i += 1
        else:
            keluar.append(isi[i])
            i += 1
    return "".join(keluar)


def kupas_komentar_jaga_baris(isi):
    """Seperti kupas_komentar, tetapi posisi baris DIPERTAHANKAN: isi
    komentar diganti spasi dan newline di dalam komentar blok tetap
    disalin, sehingga nomor baris hasil kupas sama persis dengan
    sumbernya. String/char literal disalin utuh seperti kupas_komentar.
    Dipakai cek-cek yang melaporkan nomor baris (pemanggil) dan yang
    mencocokkan per baris diff (cek_panggil_bekas_hapusan) agar sebutan
    nama fungsi di dalam KOMENTAR tidak divonis sebagai panggilan
    (issue #471)."""
    keluar = []
    i, n = 0, len(isi)
    while i < n:
        dua = isi[i:i + 2]
        tiga = isi[i:i + 3]
        if tiga == '"""':
            keluar.append('"""')
            i += 3
            while i < n and isi[i:i + 3] != '"""':
                keluar.append(isi[i])
                i += 1
            keluar.append('"""')
            i += 3
        elif dua[0] == '"' or dua[0] == "'":
            kutip = dua[0]
            keluar.append(isi[i])
            i += 1
            while i < n:
                if kutip == '"' and isi[i] == '\n':
                    break
                keluar.append(isi[i])
                if isi[i] == '\\':
                    i += 1
                    if i < n:
                        keluar.append(isi[i])
                elif isi[i] == kutip:
                    i += 1
                    break
                i += 1
        elif dua == '//':
            while i < n and isi[i] != '\n':
                keluar.append(' ')
                i += 1
        elif dua == '/*':
            # Sadar sarang (issue #482): */ pertama di dalam sarang
            # tidak menutup komentar terluar.
            keluar.append('  ')
            i += 2
            kedalaman = 1
            while i < n and kedalaman:
                if isi[i:i + 2] == '/*':
                    kedalaman += 1
                    keluar.append('  ')
                    i += 2
                elif isi[i:i + 2] == '*/':
                    kedalaman -= 1
                    keluar.append('  ')
                    i += 2
                else:
                    keluar.append('\n' if isi[i] == '\n' else ' ')
                    i += 1
        else:
            keluar.append(isi[i])
            i += 1
    return "".join(keluar)


def kupas_kode_jaga_baris(isi, kosongkan_string=False):
    """Pengupas komentar SADAR TEMPLATE (issue #483) yang menjaga nomor
    baris seperti kupas_komentar_jaga_baris, tetapi melacak keadaan
    string secara benar: string bisa bersarang di dalam ekspresi
    template ${...} dan ekspresi bisa memuat string lagi, sehingga
    "/*" di dalam string bersarang TIDAK membuka komentar hantu yang
    menelan kode sesudahnya (lubang LOLOS-palsu: panggilan nyata ke
    fungsi terhapus lolos karena terkupas jadi spasi). Komentar blok
    sadar sarang (#482). Bila kosongkan_string, isi string/raw-string
    ikut dikosongkan (issue #484: sebutan nama di dalam string bukan
    panggilan) — ekspresi template tetap diperlakukan sebagai kode
    karena panggilan di dalam ${...} adalah panggilan nyata."""
    keluar = []
    i, n = 0, len(isi)
    konteks = []  # tumpukan (mode, kedalaman_template) untuk dipulihkan
    mode = "kode"  # 'kode' | 'string' | 'raw'
    tpl = 0  # kedalaman {} ekspresi template yang sedang terbuka

    def salin(teks):
        keluar.append(teks if not (kosongkan_string and mode != "kode") else
                      "".join("\n" if c == "\n" else " " for c in teks))

    while i < n:
        dua = isi[i:i + 2]
        tiga = isi[i:i + 3]
        if mode == "kode":
            if dua == "//":
                while i < n and isi[i] != "\n":
                    keluar.append(" ")
                    i += 1
            elif dua == "/*":
                keluar.append("  ")
                i += 2
                dalam = 1
                while i < n and dalam:
                    if isi[i:i + 2] == "/*":
                        dalam += 1
                        keluar.append("  ")
                        i += 2
                    elif isi[i:i + 2] == "*/":
                        dalam -= 1
                        keluar.append("  ")
                        i += 2
                    else:
                        keluar.append("\n" if isi[i] == "\n" else " ")
                        i += 1
            elif tiga == '"""':
                konteks.append((mode, tpl))
                mode = "raw"
                salin('"""')
                i += 3
            elif isi[i] == '"':
                konteks.append((mode, tpl))
                mode = "string"
                salin('"')
                i += 1
            elif isi[i] == "'":
                # Literal char: konsumsi utuh (tak ada template di dalamnya).
                j = i + 1
                while j < n:
                    if isi[j] == "\\":
                        j += 2
                        continue
                    if isi[j] == "'" or isi[j] == "\n":
                        break
                    j += 1
                salin(isi[i:j + 1] if j < n and isi[j] == "'" else isi[i:j])
                i = j + 1 if j < n and isi[j] == "'" else j
            elif isi[i] == "{" and tpl > 0:
                tpl += 1
                keluar.append("{")
                i += 1
            elif isi[i] == "}" and tpl > 0:
                tpl -= 1
                keluar.append("}")
                i += 1
                if tpl == 0 and konteks:
                    mode, tpl = konteks.pop()
            else:
                keluar.append(isi[i])
                i += 1
        else:  # 'string' atau 'raw'
            if mode == "raw" and tiga == '"""':
                salin('"""')
                i += 3
                mode, tpl = konteks.pop() if konteks else ("kode", 0)
            elif mode == "string" and isi[i] == "\\":
                salin(isi[i:i + 2])
                i += 2
            elif mode == "string" and isi[i] == '"':
                salin('"')
                i += 1
                mode, tpl = konteks.pop() if konteks else ("kode", 0)
            elif dua == "${":
                konteks.append((mode, tpl))
                mode = "kode"
                tpl = 1
                keluar.append("${")
                i += 2
            else:
                salin(isi[i])
                i += 1
    return "".join(keluar)


def cek_kurung(gagal, berubah):
    for path in berubah:
        if not path.endswith((".kt", ".java")):
            continue
        penuh = os.path.join(AKAR, path)
        if not os.path.isfile(penuh):
            continue
        isi = kupas_kotlin(baca(path))
        for buka, tutup, nama in (("{", "}", "kurawal"), ("(", ")", "lengkung"), ("[", "]", "siku")):
            selisih = isi.count(buka) - isi.count(tutup)
            if selisih:
                gagal.append("Kurung %s tak seimbang di %s (%+d) — kompilasi pasti gagal"
                             % (nama, path, selisih))
                break


POLA_BOROS = [
    ("String.format(", "Formatter+Locale mahal di ART lama; pakai concat/manual"),
    ("SimpleDateFormat(", "buat sekali (objek bersama), jangan di loop/request"),
    ("Calendar.getInstance(", "hitung manual tanpa Calendar berat"),
    (".split(", "kompilasi Pattern tiap panggil; pecah manual/indexOf bila panas"),
    ("Thread(", "pakai coroutine/pool bersama, hemat stack"),
    ("System.out.println", "pakai Log/buffer log, bukan stdout"),
    (".printStackTrace()", "telan/abaikan eksplisit + catat log"),
]


def cek_pola_boros(peringatan, diff):
    tampil = set()
    for _, isi in tambah_kt(diff):
        for pola, saran in POLA_BOROS:
            if pola in isi and pola not in tampil:
                tampil.add(pola)
                peringatan.append("Pola boros di baris baru %s — %s: %s"
                                  % (pola.strip(), saran, isi.strip()[:70]))


def cek_izin_baru(peringatan, diff):
    for baris in diff.splitlines():
        if baris.startswith("+") and "uses-permission" in baris:
            cocok = re.search(r'android:name="([^"]+)"', baris)
            if cocok:
                peringatan.append("Izin baru %s — pastikan perlu + tercatat README"
                                  % cocok.group(1))


def cek_definisi_baru(peringatan, diff):
    pola = re.compile(r'\+\s*<(style|string|color|dimen|plurals|string-array|integer-array|array)\s+name="([^"]+)"')
    _, acuan = himpun_definisi()
    sudah = set()
    for baris in diff.splitlines():
        cocok = pola.match(baris)
        if cocok and (cocok.group(1), cocok.group(2)) not in sudah:
            sudah.add((cocok.group(1), cocok.group(2)))
            jenis, nama = cocok.group(1), cocok.group(2)
            kunci = "array" if "array" in jenis else jenis
            if nama not in acuan.get(kunci, set()):
                peringatan.append("Definisi baru %s/%s tanpa acuan — hapus bila tak jadi dipakai"
                                  % (kunci, nama))


def cek_pengingat_changelog(peringatan, berubah):
    if not berubah or "CHANGELOG.md" in berubah:
        return
    if any(b.startswith(("app/src/main/", "remote.src.html",
                          "app/build.gradle.kts", "scripts/", "tools/"))
           for b in berubah):
        peringatan.append("Kode aplikasi berubah tanpa CHANGELOG.md — catat per rilis (aturan repo)")


TERLARANG_KEMBALI = [
    ("zxing", "QR/zxing dihapus total 2026-09-11 — jangan hidupkan kembali tanpa diskusi"),
    ("QrCode", "QrCode.kt dihapus — jangan hidupkan kembali tanpa diskusi"),
    ("QrEncoder", "QrEncoder.kt dihapus — jangan hidupkan kembali tanpa diskusi"),
    ("/api/qr", "endpoint /api/qr dihapus — jangan hidupkan kembali"),
    ("/api/status", "endpoint /api/status dihapus — status cukup snapshotJson/SSE"),
    ("values-night", "tema gelap native dihapus — app selalu terang"),
    ("SpeedChartView", "widget grafik kecepatan dihapus — kecepatan cukup teks"),
    ("renderSpeedTotal", "bilah status remote dihapus 2026-08-13 — pakai applyServerStatus"),
    ("refreshStatus", "bilah status remote dihapus 2026-08-13 — pakai applyServerStatus"),
]


def cek_aturan_repo(gagal, berubah):
    # Fail-closed (issue #190): berkas hilang/kosong tidak boleh membuat
    # kunci minSdk terlewati diam-diam, dan komentar tidak boleh menyamar
    # sebagai penetapan minSdk (regex lama mengambil kecocokan pertama
    # termasuk dari komentar '// minSdk = 23').
    if not os.path.isfile(os.path.join(AKAR, "app/build.gradle.kts")):
        gagal.append("app/build.gradle.kts tak ditemukan — kunci minSdk tak bisa diverifikasi")
        tetap = ""
    else:
        tetap = baca("app/build.gradle.kts")
    # Strip komentar SADAR string (issue #211): pemindai karakter
    # melacak literal string (termasuk raw string tiga-quote) sehingga
    # pola seperti "/*" atau "//" DI DALAM string tidak dianggap
    # komentar — stripper baris sebelumnya masuk mode blok palsu dan
    # menelan penetapan minSdk asli. Komentar sungguhan (termasuk
    # spoof '// minSdk = 23') tetap terhapus. ISI string dikosongkan
    # (issue #225): teks bersih ini hanya dipakai kunci minSdk, dan
    # string berisi 'minSdk = 23' sebelum penetapan asli dulu menipu
    # regex kecocokan pertama menjadi false accept. Sadar TEMPLATE
    # juga (issue #255): ekspresi ${...} di dalam string adalah kode
    # yang boleh memuat string bersarang — quote bersarang itu dulu
    # menutup string luar lebih awal sehingga isi string template
    # bocor sebagai kode (spoof 'minSdk = 23' di dalam ${ "..." }).
    def strip_komentar(teks):
        out = []
        i, n = 0, len(teks)

        def skip_kutip(i):
            # Lewati literal char mulai di i (teks[i] == "'"); kembalikan
            # indeks sesudahnya atau None bila bukan literal char sah.
            # Tanpa ini quote di dalam char membuka string hantu yang
            # menelan komentar/baris berikutnya (regresi #211, #226).
            j = i + 1
            if j < n and teks[j] == "\\":
                j += 2
            elif j < n:
                j += 1
            if j < n and teks[j] == "'":
                return j + 1
            return None

        def skip_blok(i):
            # Lewati komentar blok Kotlin BERSARANG (issue #227): hitung
            # kedalaman, jangan berhenti di */ pertama — sisa
            # ' minSdk = 23 */' sesudahnya dulu bocor sebagai kode.
            depth = 1
            j = i + 2
            while j < n and depth > 0:
                if teks.startswith("/*", j):
                    depth += 1
                    j += 2
                elif teks.startswith("*/", j):
                    depth -= 1
                    j += 2
                else:
                    j += 1
            return j

        def skip_template(i):
            # Lewati ekspresi template ${...}; i tepat sesudah '${'.
            # Isinya kode: literal/komentar di dalamnya dilewati dengan
            # aturan yang sama agar '}' di dalamnya tidak menutup
            # template lebih awal (issue #255).
            depth = 1
            while i < n and depth > 0:
                if teks.startswith('"""', i) or teks[i] == '"':
                    i = skip_string(i)
                elif teks[i] == "'":
                    end = skip_kutip(i)
                    i = end if end is not None else i + 1
                elif teks.startswith("//", i):
                    j = teks.find("\n", i)
                    i = n if j < 0 else j
                elif teks.startswith("/*", i):
                    i = skip_blok(i)
                elif teks[i] == "{":
                    depth += 1
                    i += 1
                elif teks[i] == "}":
                    depth -= 1
                    i += 1
                else:
                    i += 1
            return i

        def skip_string(i):
            # Lewati string (biasa/raw) mulai di pembukanya; template
            # ${...} di dalamnya dilewati sebagai kode (skip_template).
            if teks.startswith('"""', i):
                j = i + 3
                while j < n:
                    if teks.startswith('"""', j):
                        return j + 3
                    if teks.startswith("${", j):
                        j = skip_template(j + 2)
                    else:
                        j += 1
                return n
            j = i + 1
            while j < n:
                if teks[j] == "\\":
                    j += 2
                elif teks[j] == '"':
                    return j + 1
                elif teks.startswith("${", j):
                    j = skip_template(j + 2)
                else:
                    j += 1
            return j

        while i < n:
            if teks.startswith('"""', i) or teks[i] == '"':
                i = skip_string(i)
                out.append('""')
            elif teks[i] == "'":
                end = skip_kutip(i)
                if end is not None:
                    out.append(teks[i:end])
                    i = end
                else:
                    out.append(teks[i])
                    i += 1
            elif teks.startswith("//", i):
                j = teks.find("\n", i)
                i = n if j < 0 else j
            elif teks.startswith("/*", i):
                i = skip_blok(i)
            else:
                out.append(teks[i])
                i += 1
        return "".join(out)

    bersih_teks = strip_komentar(tetap)
    def nilai_konstanta(ekspresi):
        # Penilai ekspresi int konstan terbatas (issue #333): literal
        # dengan underskor di antara digit (2_3 sah di Kotlin), kurung,
        # dan operator + - * / %. Bentuk sah seperti '20 + 3' dan '(23)'
        # dulu ditolak regex digit polos walau nilainya tetap 23. Di
        # luar tata bahasa ini -> None (gagal-tertutup lewat jalur lain).
        token = re.findall(r"\d(?:_?\d)*|[()+\-*/%]", ekspresi)
        if not token or "".join(token) != re.sub(r"\s+", "", ekspresi):
            return None
        pos = [0]

        def faktor():
            if pos[0] >= len(token):
                raise ValueError
            t = token[pos[0]]
            if t == "(":
                pos[0] += 1
                v = jumlah()
                if pos[0] >= len(token) or token[pos[0]] != ")":
                    raise ValueError
                pos[0] += 1
                return v
            if t == "-":
                pos[0] += 1
                return -faktor()
            if not re.fullmatch(r"\d(?:_?\d)*", t):
                raise ValueError
            pos[0] += 1
            return int(t.replace("_", ""))

        def suku():
            v = faktor()
            while pos[0] < len(token) and token[pos[0]] in ("*", "/", "%"):
                op = token[pos[0]]
                pos[0] += 1
                w = faktor()
                if op == "*":
                    v = v * w
                elif w == 0:
                    raise ValueError
                elif op == "/":
                    # Pembagian Int Kotlin memotong ke arah nol.
                    q = abs(v) // abs(w)
                    v = q if (v < 0) == (w < 0) else -q
                else:
                    r = abs(v) % abs(w)
                    v = r if v >= 0 else -r
            return v

        def jumlah():
            v = suku()
            while pos[0] < len(token) and token[pos[0]] in ("+", "-"):
                op = token[pos[0]]
                pos[0] += 1
                w = suku()
                v = v + w if op == "+" else v - w
            return v

        try:
            hasil = jumlah()
        except ValueError:
            return None
        return hasil if pos[0] == len(token) else None

    nilai_minsdk = None
    minsdk_ambigu = False
    # Semua penetapan minSdk berupa ekspresi konstan dinilai nilainya
    # (issue #333). Jalur literal ikut dinilai ambigu (issue #270):
    # penetapan bernilai beda di berkas yang sama (umpan di blok lain)
    # tidak boleh kalah oleh penetapan pertama.
    literal = []
    for rhs in re.findall(r"minSdk\s*=\s*([^\n;]+)", bersih_teks):
        v = nilai_konstanta(rhs.strip())
        if v is not None:
            literal.append(v)
    if literal:
        if len(set(literal)) > 1:
            gagal.append(
                "penetapan minSdk literal ambigu (nilai %s) — kunci "
                "minSdk menolak menebak di antara penetapan ganda"
                % ", ".join(str(v) for v in sorted(set(literal))))
            minsdk_ambigu = True
        else:
            nilai_minsdk = literal[0]
    if nilai_minsdk is None and not minsdk_ambigu:
        # Bentuk variabel (issue #212): 'minSdk = minSdkVersi' dengan
        # 'val minSdkVersi = 23' di berkas yang sama diselesaikan ke
        # nilainya — refactor sah yang memusatkan angka SDK tidak lagi
        # ditolak sebagai "tak ditemukan". Variabel tak dikenal tetap
        # gagal-tertutup lewat cabang tak ditemukan di bawah.
        via = re.search(r"minSdk\s*=\s*([A-Za-z_][\w.]*)", bersih_teks)
        if via:
            nama = re.escape(via.group(1).rsplit(".", 1)[-1])
            # Semua definisi dinilai (issue #229): definisi pertama menang
            # dulu kalah oleh shadowing val lokal bernilai lain (false
            # accept). Nilai yang berbeda-beda = ambigu -> gagal-tertutup
            # dengan pesan jelas, bukan menebak salah satu. Nilai
            # definisi boleh ekspresi konstan juga (issue #333).
            semua = []
            for rhs in re.findall(
                    r"(?:val|var)\s+" + nama +
                    r"\s*(?::\s*Int\s*)?=\s*([^\n;]+)", bersih_teks):
                v = nilai_konstanta(rhs.strip())
                if v is not None:
                    semua.append(v)
            # Definisi delegasi 'by lazy { 23 }' ikut dinilai (issue
            # #256): tanpa ini refactor lazy yang sah ditolak sebagai
            # tak ditemukan, dan definisi lazy bayangan bernilai lain
            # lolos dari cek ambigu di bawah.
            for rhs in re.findall(
                    r"(?:val|var)\s+" + nama +
                    r"\s*(?::\s*Int\s*)?by\s+lazy\s*\{\s*([^\n}]+)",
                    bersih_teks):
                v = nilai_konstanta(rhs.strip())
                if v is not None:
                    semua.append(v)
            if len(set(semua)) > 1:
                gagal.append(
                    "definisi %s ambigu (nilai %s) — kunci minSdk menolak "
                    "menebak di antara definisi ganda/shadowing"
                    % (via.group(1),
                       ", ".join(str(v) for v in sorted(set(semua)))))
                minsdk_ambigu = True
            elif semua:
                nilai_minsdk = semua[0]
    if nilai_minsdk is None and not minsdk_ambigu and os.path.isfile(os.path.join(AKAR, "app/build.gradle.kts")):
        gagal.append("minSdk tak ditemukan di app/build.gradle.kts — kunci minSdk tak bisa diverifikasi")
    if nilai_minsdk is not None and nilai_minsdk != 23:
        gagal.append("minSdk %s != 23 (aturan: tetap Android 6+, keputusan owner 2026-10-28)" % nilai_minsdk)
    for path in berubah:
        rendah = path.lower()
        if "values-night" in rendah:
            gagal.append("Larangan historis: values-night (%s) — app selalu terang" % path)
        nama = rendah.rsplit("/", 1)[-1]
        if rendah.endswith((".apk", ".aab", ".dex", ".jks", ".keystore",
                            ".p12", ".pfx", ".zip")) or nama == "keystore":
            # Daftar diselaraskan dengan cek-cepat (#332): versi lama
            # tak mengenal .p12/.pfx/.zip dan basename keystore polos.
            gagal.append("Berkas terlarang masuk repo: " + path)
        elif nama.startswith(".env") or ".env." in nama or \
                nama.endswith(".env"):
            # Semua varian env sama berbahayanya (#291 + #332):
            # .env, .env.*, .envrc, *.env, dan config.env.local —
            # dulu config.env.* lolos karena tak diawali '.env.' dan
            # tak berakhiran '.env'. Path sudah di-lowercase di atas
            # sehingga huruf besar ikut tertangkap.
            gagal.append("Berkas secret masuk diff: " + path)
        elif path.startswith("app/src/main/assets/bin/"):
            gagal.append("Binary tak boleh dibundel ke APK: " + path)
        elif path.startswith("app/src/main/"):
            try:
                ukuran = os.path.getsize(os.path.join(AKAR, path))
            except OSError:
                ukuran = 0
            if ukuran > 1024 * 1024:
                gagal.append("Berkas >1 MB di app/src (APK wajib kecil): " + path)


def cek_larangan_hidup_lagi(gagal, diff):
    for path, isi in tambah_kt(diff):
        for kunci, alasan in TERLARANG_KEMBALI:
            if kunci in ("values-night",):
                continue
            if kunci in isi:
                gagal.append("Larangan historis dilanggar di %s (%s): %s — %s"
                             % (path, kunci, isi.strip()[:70], alasan))
                break


def nama_fun_hilang(diff, lewati_berkas=()):
    hilang = []
    for path, baris in hapus_kt(diff):
        rapi = baris.rstrip()
        if "fun " not in baris:
            continue
        if path in lewati_berkas or not os.path.isfile(os.path.join(AKAR, path)):
            continue
        # Segmen receiver opsional (issue #510): deklarasi ekstensi
        # "fun String.nama(...)" atau "fun List<T>.nama(...)" dulu
        # menangkap TIPE-nya sebagai nama lalu gagal di tuntutan kurung
        # sesudahnya — penghapusan fungsi ekstensi tak tercatat sama
        # sekali oleh keluarga cek ini.
        cocok = re.search(
            r"\bfun\s+(?:<[^>]*>\s*)?(?:[\w<][\w<>, ?]*\.)?(\w+)\s*[\(<]",
            baris)
        if cocok and cocok.group(1) not in KATA_KUNCI_KOTLIN:
            hilang.append(cocok.group(1))
    return hilang


def masih_didefinisikan(nama):
    # Bentuk simetris dengan nama_fun_hilang (issue #510): definisi
    # ekstensi "fun Receiver.nama(...)" juga berarti nama masih hidup.
    pola = re.compile(
        r"\bfun\s+(?:<[^>]*>\s*)?(?:[\w<][\w<>, ?]*\.)?%s\s*[\(<]"
        % re.escape(nama))
    for path in jalan_repo(os.path.join("app", "src", "main", "java"), ext=(".kt", ".java")):
        if pola.search(baca(path)):
            return True
    return False


def pemanggil(nama):
    pola = re.compile(r"\b%s\s*\(" % re.escape(nama))
    temu = []
    for path in jalan_repo("app", ext=(".kt", ".java", ".xml")):
        # Komentar dikupas dulu (nomor baris dipertahankan): sebutan
        # nama di komentar bukan panggilan (issue #471 — insiden
        # "cache (" putaran 14 yang memaksa redaksi 5 komentar).
        # Pengupas sadar template (issue #483) + isi string ikut
        # dikosongkan (issue #484): sebutan nama di dalam string bukan
        # panggilan; ekspresi template tetap kode dan tetap terhitung.
        bersih = kupas_kode_jaga_baris(baca(path), kosongkan_string=True) if path.endswith((".kt", ".java")) else baca(path)
        for i, baris in enumerate(bersih.splitlines(), 1):
            if pola.search(baris):
                temu.append("%s:%d" % (path, i))
                if len(temu) >= 6:
                    return temu
    return temu


def cek_fun_hilang(gagal, peringatan, diff, hapus=()):
    lewati = set(hapus)
    for nama in sorted(set(nama_fun_hilang(diff, lewati))):
        if masih_didefinisikan(nama):
            continue
        siapa = pemanggil(nama)
        if not siapa:
            continue
        pesan = "Fungsi %s() dihapus tapi masih dipanggil: %s" % (nama, ", ".join(siapa))
        if nama in OVERRIDE_AMAN:
            peringatan.append(pesan + " (callback siklus hidup?)")
        else:
            gagal.append(pesan)


def tambah_kt_bernomor(diff):
    """Seperti tambah_kt, tetapi menghasilkan juga nomor baris BARU
    tiap baris tambah (dari header hunk @@), agar cek dapat mengupas
    dengan konteks berkas utuh (issue #481)."""
    path = ""
    nomor = 0
    for baris in diff.splitlines():
        if baris.startswith("diff --git "):
            path = path_dari_header_diff(baris)
            nomor = 0
        elif baris.startswith("@@"):
            m = re.search(r"\+(\d+)", baris)
            nomor = int(m.group(1)) if m else 0
        elif baris.startswith("+") and not baris.startswith("+++"):
            if path.endswith((".kt", ".java")):
                yield path, nomor, baris[1:]
            nomor += 1
        elif baris.startswith(" "):
            nomor += 1


def cek_panggil_bekas_hapusan(gagal, diff):
    # Baris tambah dikelompokkan per berkas, isi berkas UTUH dikupas,
    # dan pencocokan hanya pada nomor baris tambah (issue #481): kupas
    # per baris (pola #471) tak tahu baris lanjutan komentar blok
    # multi-baris — KDoc tiga baris yang menyebut nama terhapus tetap
    # GAGAL palsu karena baris lanjutannya tanpa penanda komentar.
    # Pengupasnya sadar template (#483) + isi string dikosongkan
    # (#484); berkas yang tak terbaca jatuh ke kupas per baris.
    per_path = {}
    for path, nomor, teks in tambah_kt_bernomor(diff):
        per_path.setdefault(path, []).append((nomor, teks))
    bersih_per_path = {}
    mentah_per_path = {}
    for path in per_path:
        isi = baca(path)
        if isi:
            mentah_per_path[path] = isi.splitlines()
            bersih_per_path[path] = kupas_kode_jaga_baris(
                isi, kosongkan_string=True
            ).splitlines()
    for nama in sorted(BEKAS_HAPUSAN):
        pola = re.compile(r"\b%s\s*\(" % re.escape(nama))
        ketemu = None
        for path, daftar in per_path.items():
            bersih = bersih_per_path.get(path)
            mentah = mentah_per_path.get(path)
            for nomor, teks in daftar:
                # Penjaga keselarasan (issue #487): nomor dari segmen
                # diff gabungan (mode default: rentang HEAD + pohon
                # kerja + staged disatukan) bisa menunjuk keadaan
                # berkas yang BERBEDA dari yang dibaca baca(). Bila
                # teks mentah pada nomor itu tak sama persis dengan
                # teks tercatat, nilai baris tercatatnya sendiri
                # dengan kupas per-baris — jangan menilai baris
                # tetangga (panggilan nyata bisa LOLOS diam-diam).
                selaras = (
                    bersih is not None and mentah is not None
                    and 1 <= nomor <= len(bersih)
                    and mentah[nomor - 1] == teks
                )
                if selaras:
                    kandidat = bersih[nomor - 1]
                else:
                    kandidat = kupas_kode_jaga_baris(teks, kosongkan_string=True)
                if pola.search(kandidat):
                    ketemu = teks
                    break
            if ketemu is not None:
                break
        if ketemu is not None:
            gagal.append("Baris baru memanggil %s() yang sudah dihapus: %s"
                         % (nama, ketemu.strip()[:80]))


def hunk_fungsi(diff):
    path = ""
    for baris in diff.splitlines():
        if baris.startswith("diff --git "):
            path = path_dari_header_diff(baris)
        elif baris.startswith("@@ ") and path.endswith((".kt", ".java")):
            ekor = baris.split("@@", 2)[-1]
            cocok = re.search(r"(\w+)\s*\(", ekor)
            if cocok:
                yield path, cocok.group(1)


def cek_uji_ubah(peringatan, diff):
    sudah = set()
    for _, nama in hunk_fungsi(diff):
        if not nama or nama in sudah or nama in KATA_KUNCI_KOTLIN:
            continue
        if nama[:1].isupper():
            continue
        sudah.add(nama)
        if not disebut_di_uji(nama):
            peringatan.append(
                "Fungsi %s() diubah tapi tanpa jejak di app/src/test — "
                "tambah/cek uji logika murninya agar salah-fix ketahuan" % nama)


def cek_hapusan_belum_tercatat(peringatan, diff):
    tercatat = muat_bekas_hapusan()
    for nama in sorted(set(nama_fun_hilang(diff))):
        if nama in tercatat or masih_didefinisikan(nama):
            continue
        peringatan.append(
            "Fungsi %s() dihapus tapi belum di tools/bekas-hapusan.txt — "
            "tambahkan agar push berikut tetap terjaga" % nama)


def cek_locale_baru(gagal, diff):
    for _, isi in tambah_kt(diff):
        if re.search(r"\.to(Lower|Upper)Case\(\)", isi):
            gagal.append("Baris baru toLower/UpperCase() tanpa Locale — bug locale "
                         "Turki; pakai varian Locale: %s" % isi.strip()[:80])


def cek_import_mati(gagal):
    for path in jalan_repo("app", ext=(".kt",)):
        bersih = kupas_komentar(baca(path))
        tanpa = re.sub(r"^\s*import\s+.*$", "", bersih, flags=re.M)
        for m in re.finditer(r"^\s*import\s+([\w.]+)\s*(?:as\s+\w+)?\s*$", bersih, flags=re.M):
            jalan_imp = m.group(1)
            if jalan_imp.endswith(".*"):
                continue
            nama = jalan_imp.split(".")[-1]
            if not re.search(r"\b%s\b" % re.escape(nama), tanpa):
                # Tanpa break (#295): SEMUA import mati di berkas ini
                # dilaporkan sekaligus, bukan hanya yang pertama.
                gagal.append("Import mati di %s: %s" % (path, jalan_imp))


def disebut_di_uji(nama):
    pola = re.compile(r"\b%s\s*\(" % re.escape(nama))
    for path in jalan_repo(os.path.join("app", "src", "test"), ext=(".kt", ".java")):
        if pola.search(baca(path)):
            return True
    return False


def cek_uji_baru(peringatan, diff):
    pola = re.compile(r"\bfun\s+(?:<[^>]*>\s*)?(\w+)\s*[\(<]")
    sudah = set()
    for _, baris in tambah_kt(diff):
        cocok = pola.search(baris)
        if cocok and cocok.group(1) not in KATA_KUNCI_KOTLIN:
            nama = cocok.group(1)
            if nama not in sudah:
                sudah.add(nama)
                if not disebut_di_uji(nama):
                    peringatan.append(
                        "Fungsi baru %s() tanpa jejak di app/src/test "
                        "(aturan: uji logika murni baru)" % nama)


def cek_remote_dm(gagal):
    src = baca("remote.src.html")
    if not src:
        # Hilang/kosong tak boleh melewatkan seluruh cek diam-diam
        # (issue #616): vonis bernama, bukan LOLOS semu.
        gagal.append("remote.src.html hilang atau kosong — cek remote tak bisa berjalan")
    if src:
        defs = re.findall(r"function\s+fmtDate\b|fmtDate\s*=\s*function|const\s+fmtDate\s*=|let\s+fmtDate\s*=", src)
        if len(defs) > 1:
            gagal.append("remote.src.html: fmtDate terdefinisi %d× (wajib SATU — hoisting menimpa)" % len(defs))
        if "tabDownloads" in src and not re.search(r"tabDownloads.{0,400}?(addEventListener|onclick|\.click)", src, re.S):
            gagal.append("remote.src.html: tabDownloads tanpa handler klik (pola tabGallery/tabFiles)")
    ex = os.path.join(AKAR, "app", "src", "main", "assets", "extract.js")
    try:
        with open(ex, encoding="utf-8", errors="replace") as f:
            baris = f.read().splitlines()
        if len(baris) < 5:
            gagal.append("assets/extract.js tampak di-minify satu baris (pemicu Wacatac) — wajib readable")
        elif baris and max(len(b) for b in baris) > 5000:
            gagal.append("assets/extract.js ada baris >5000 karakter (risiko flag satu-baris) — pecah readable")
    except OSError:
        # Hilang/tak terbaca adalah vonis, bukan lolos diam-diam
        # (issue #616): extract.js dimuat WebExtractActivity saat
        # runtime — ketiadaannya tak boleh tak terlihat gerbang ini.
        gagal.append("assets/extract.js hilang atau tak terbaca (dimuat WebExtractActivity saat runtime)")


def cek_pola_berisiko(gagal, peringatan, diff, berubah=()):
    for _, isi in tambah_kt(diff):
        if "System.out.println" in isi:
            peringatan.append("println di diff (pakai Log/buffer): " + isi.strip()[:80])
        if ".printStackTrace()" in isi:
            peringatan.append("printStackTrace di diff (catat log eksplisit): " + isi.strip()[:80])
    # jalan_output, BUKAN jalan (issue #407): git diff --check keluar
    # bukan-nol justru saat menemukan masalah spasi, dan jalan() menelan
    # stdout pada returncode bukan-nol — peringatan ini sebelumnya kode
    # mati yang tak pernah bisa tampil.
    # Sejak issue #472 temuan whitespace adalah GAGAL, bukan peringatan:
    # rantai pre-push (verifikasi-path + cek-cepat) adalah lapisan
    # terakhir sebelum push dan cek-cepat tak memeriksa whitespace,
    # jadi commit kotor yang melewati pre-commit (--no-verify/amend)
    # dulu tetap lolos di titik push — insiden EOF ganda ae3f368
    # putaran 14 buktinya. Perlakuan kini selaras check_repo.py yang
    # memang keras untuk whitespace.
    # Cakupan DIBATASI ke berkas yang sedang diverifikasi (issue #485):
    # tanpa pathspec, --check menilai seluruh pohon kerja/indeks
    # sehingga kotoran WIP berkas tak terkait menggagalkan verifikasi
    # per-path dan bahkan membatalkan push commit bersih (WIP unstaged
    # yang tak ikut ter-push). Kekerasan status GAGAL-nya tetap.
    ekor = ["--", *berubah] if berubah else []
    cek = jalan_output("git", "diff", "--check", *ekor)
    if cek:
        gagal.append("Rapikan spasi diff --check: " + cek.strip().splitlines()[0][:100])
    # Varian staged (issue #426): diff polos tak melihat indeks, jadi
    # whitespace yang sudah di-git add lolos gerbang ini juga.
    cek_staged = jalan_output("git", "diff", "--cached", "--check", *ekor)
    if cek_staged:
        gagal.append(
            "Rapikan spasi diff --check (staged): " + cek_staged.strip().splitlines()[0][:100]
        )


def utama():
    parser = argparse.ArgumentParser(description="Verifikasi path yang baru dikerjakan.")
    parser.add_argument("jalur", nargs="*", help="berkas spesifik (opsional)")
    parser.add_argument("--semua", action="store_true", help="cek seluruh repo")
    parser.add_argument("--rentang", default="", help="rentang diff A...B (dipakai CI)")
    args = parser.parse_args()

    gagal = []
    peringatan = []
    diff = ""
    if args.jalur:
        # Gerbang kesehatan git yang sama dengan mode default (issue
        # #661): mode jalur sangat bergantung diff (pengecualian hapus
        # #647 + seluruh keluarga cek berbasis diff), jadi git mati tak
        # boleh mengosongkannya diam-diam menjadi LOLOS.
        if not git_hidup():
            gagal.append(
                "git tidak berfungsi di repo ini (rev-parse gagal)"
                " — mode jalur bergantung diff, gerbang tidak bisa"
                " menilai (fail-closed)."
            )
        # Dedupe menjaga urutan pertama (issue #500): jalur yang disebut
        # dua kali dulu dinilai dua kali — peringatan per-baris dari cek
        # berbasis diff berduplikat (termasuk suntikan #496) dan
        # penghitung "berkas diperiksa" mengganda, walau vonis akhir
        # sama. Satu penyebutan cukup.
        # Path yang disebut tetapi TIDAK ADA wajib divonis, bukan
        # dibuang diam-diam (issue #625): dulu gerbang menjawab
        # 'LOLOS (0 berkas diperiksa)' untuk path salah ketik, dan
        # campuran sah+hantu hanya melaporkan yang sah — pemanggilan
        # manual per-path bisa 'memverifikasi' berkas yang tak
        # pernah diperiksa.
        # Pengecualian penghapusan sah (issue #647): path yang tercatat
        # TERHAPUS terhadap HEAD (status D) bukan "tak ditemukan" —
        # menyebutnya untuk memverifikasi penghapusannya adalah alur
        # yang sah. Path seperti itu tetap masuk daftar berubah agar
        # diff HEAD memuat penghapusannya dan keluarga cek
        # penghapusan-berdampak (bekas-hapusan, fungsi hilang) tetap
        # menilainya; hanya vonis "wajib ada" yang dikecualikan.
        jalur_terhapus = set()
        for j in dict.fromkeys(args.jalur):
            if not os.path.exists(os.path.join(AKAR, j)):
                status_hapus = jalan(
                    "git", "diff", "HEAD", "--name-status", "--", j
                ) or ""
                if status_hapus.startswith("D"):
                    jalur_terhapus.add(j)
                    continue
                gagal.append(
                    "berkas tak ditemukan: " + j +
                    " — path eksplisit wajib ada (salah ketik?)"
                )
        berubah = list(dict.fromkeys(
            j for j in args.jalur
            if os.path.exists(os.path.join(AKAR, j)) or j in jalur_terhapus
        ))
        # Lawan HEAD, bukan diff unstaged polos (issue #493): perubahan
        # staged murni dulu menghasilkan diff kosong sehingga SELURUH
        # keluarga cek berbasis diff (bekas-hapusan, fungsi hilang,
        # pola berisiko) tak berjalan dan mode jalur menjawab LOLOS
        # semu. git diff HEAD menilai gabungan staged+unstaged.
        diff = jalan("git", "diff", "HEAD", "--", *berubah) or ""
        # Berkas tak terlacak yang disebut eksplisit (issue #496): git
        # diff HEAD tak pernah mengeluarkan isinya, jadi suntikkan
        # sebagai diff semu ber-header @@ seperti mode --semua (#213,
        # penomoran diperbaiki #487) agar keluarga cek berbasis diff
        # ikut menilainya — tanpa ini verifikasi per-path atas berkas
        # yang baru dibuat menjawab LOLOS semu untuk cek isi.
        tak_lacak = set(nama_tak_lacak())
        for nama in berubah:
            if nama not in tak_lacak:
                continue
            try:
                with open(os.path.join(AKAR, nama), encoding="utf-8") as f:
                    isi = f.read(2_000_001)
            except (OSError, UnicodeDecodeError):
                continue
            if len(isi) > 2_000_000:
                # Batas baca tercapai: suntikan parsial WAJIB diberi
                # tahu (issue #501) — tanpa ini cek berbasis diff buta
                # diam-diam sesudah batas.
                peringatan.append(
                    "Isi " + nama + " terpotong di 2000000 karakter — "
                    "cek berbasis diff parsial (issue #501)"
                )
                isi = isi[:2_000_000]
            nama_header = nama.replace("\n", "_").replace("\r", "_")
            baris_isi = isi.splitlines()
            diff += (
                "\ndiff --git a/" + nama_header + " b/" + nama_header
                + "\n+++ b/" + nama_header + "\n"
                + "@@ -0,0 +1," + str(len(baris_isi)) + " @@\n"
                + "\n".join("+" + baris for baris in baris_isi)
            )
    elif args.rentang:
        daftar = daftar_berkas_berubah(args.rentang)
        if daftar is None:
            # Fail-closed (issue #182): dulu keadaan ini jatuh ke "cek
            # keadaan penuh" dengan diff kosong sehingga SEMUA cek berbasis
            # diff (larangan hidup lagi, fungsi terhapus, locale) terlewati
            # dan hasil bisa LOLOS. Samakan dengan check_changelog (#151):
            # rentang tak valid = gerbang gagal, bukan lolos diam-diam.
            gagal.append(
                "rentang " + args.rentang + " tak valid — diff tak bisa "
                "diverifikasi; periksa base sebelum push (fail-closed)."
            )
            berubah = []
        else:
            berubah = daftar
            a, b = re.split(r"\.\.+", args.rentang, maxsplit=1)
            diff = jalan("git", "diff", a + "..." + b) or ""
    else:
        # Sumber daftar perubahan wajib hidup (issue #646): jalan()
        # mengembalikan None untuk SEMUA kegagalan git, dan rantai
        # `or ""` di bawah mengubahnya menjadi daftar + diff kosong —
        # tanpa cek ini repo tanpa .git (atau git mati) menjawab
        # "LOLOS (0 berkas diperiksa)" padahal tak satu pun cek
        # berbasis diff berjalan. Fail-closed seperti #182/#625.
        # Mode --semua dikecualikan: sumber daftarnya jalan_repo
        # (pindai berkas), bukan git.
        if not args.semua and not git_hidup():
            gagal.append(
                "git tidak berfungsi di repo ini (rev-parse gagal)"
                " — sumber daftar perubahan mati, gerbang tidak bisa"
                " menilai dalam mode diff (fail-closed)."
            )
        hulu = jalan("git", "rev-parse", "--abbrev-ref", "--symbolic-full-name", "@{u}")
        if hulu:
            diff = jalan("git", "diff", hulu.strip() + "...HEAD") or ""
            daftar = jalan("git", "diff", "--name-only", "-z", hulu.strip() + "...HEAD") or ""
            berubah = [x for x in daftar.split("\0") if x]
        else:
            diff = jalan("git", "diff", "HEAD~1...HEAD") or ""
            berubah = [x for x in (jalan("git", "diff", "--name-only", "-z", "HEAD~1...HEAD") or "").split("\0") if x]
        # core.quotepath=false: nama non-ASCII tampil mentah, bukan
        # kutipan oktal yang tak dikenali daftar berkas (#312).
        kotor = jalan("git", "-c", "core.quotepath=false", "status", "--porcelain") or ""
        for baris in kotor.splitlines():
            nama = baris[3:].strip().strip('"')
            if nama and nama not in berubah:
                berubah.append(nama)
        diff += (jalan("git", "diff") or "")
        diff += jalan("git", "diff", "--cached") or ""
    berubah += [b for b in nama_tak_lacak() if b not in berubah]
    if args.semua:
        # Seluruh repo, semua jenis berkas (issue #198): versi lama hanya
        # .kt di app/ sehingga larangan per-path cek_aturan_repo (ekstensi
        # terlarang, binary di assets/bin/, ukuran) tak pernah menilai
        # berkas lain pada mode ini.
        berubah = [p for p in jalan_repo(".", ext=None)]
        # Berkas baru belum terlacak tidak punya diff git — suntikkan
        # isinya sebagai baris '+' agar cek berbasis diff (panggilan
        # fungsi terhapus) ikut menilainya pada mode --semua.
        for nama in nama_tak_lacak():
            try:
                with open(os.path.join(AKAR, nama), encoding="utf-8") as f:
                    isi = f.read(2_000_001)
            except (OSError, UnicodeDecodeError):
                continue
            if len(isi) > 2_000_000:
                # Peringatan terpotong yang sama seperti mode jalur
                # (issue #501): penilaian diff atas berkas ini parsial.
                peringatan.append(
                    "Isi " + nama + " terpotong di 2000000 karakter — "
                    "cek berbasis diff parsial (issue #501)"
                )
                isi = isi[:2_000_000]
            # Format diff git sungguhan (header + +++): pengurai diff
            # (tambah_kt dkk.) hanya mengakui baris '+' di bawah header
            # 'diff --git' yang path-nya berakhiran .kt/.java. Nama di
            # header disanitasi dari newline/CR (issue #230): nama berkas
            # ber-newline memotong header menjadi dua baris sehingga
            # token terakhir tidak berakhiran .kt dan isi berkas lolos
            # tak dinilai. Header ini semu — yang penting ekstensinya;
            # nama asli tetap dipakai cek per-path lewat `berubah`.
            nama_header = nama.replace("\n", "_").replace("\r", "_")
            baris_isi = isi.splitlines()
            # Header hunk semu WAJIB ada (issue #487): tanpa @@,
            # tambah_kt_bernomor memulai nomor dari 0 sehingga baris
            # ke-k dinomori k-1 — panggilan nyata di baris terakhir
            # berkas LOLOS total dan kutipan GAGAL menunjuk tetangga.
            diff += (
                "\ndiff --git a/" + nama_header + " b/" + nama_header
                + "\n+++ b/" + nama_header + "\n"
                + "@@ -0,0 +1," + str(len(baris_isi)) + " @@\n"
                + "\n".join("+" + baris for baris in baris_isi)
            )

    cek_xml(gagal)
    cek_acuan_yatim(gagal)
    cek_manifest(gagal)
    cek_duplikat_definisi(gagal)
    cek_import_mati(gagal)
    cek_remote_dm(gagal)
    cek_aturan_repo(gagal, berubah)
    cek_larangan_hidup_lagi(gagal, diff)
    cek_kurung(gagal, berubah)
    hapus = berkas_dihapus(args.rentang)
    if not args.rentang:
        # Cabang staged memakai parser -z yang sama (issue #406): cara
        # lama tanpa -z + split baris membuat path non-ASCII terkuotasi
        # gaya C gugur di syarat startswith cek_berkas_dihapus.
        keluar = jalan("git", "diff", "--cached", "--name-status", "-z")
        if keluar:
            hapus += parse_hapus_name_status_z(keluar)
    cek_berkas_dihapus(gagal, hapus)
    if not args.semua:
        cek_pengingat_changelog(peringatan, berubah)
    if diff:
        cek_panggil_bekas_hapusan(gagal, diff)
        cek_locale_baru(gagal, diff)
        cek_fun_hilang(gagal, peringatan, diff, hapus)
        cek_hapusan_belum_tercatat(peringatan, diff)
        cek_uji_baru(peringatan, diff)
        cek_uji_ubah(peringatan, diff)
        cek_pola_berisiko(gagal, peringatan, diff, berubah)
        cek_pola_boros(peringatan, diff)
        cek_izin_baru(peringatan, diff)
        cek_definisi_baru(peringatan, diff)

    for w in peringatan:
        print("PERINGATAN: " + w)
    if gagal:
        for g in gagal:
            print("GAGAL: " + g)
        print("Hasil: GAGAL (%d) — perbaiki sebelum push." % len(gagal))
        return 1
    print("Hasil: LOLOS (%d berkas diperiksa%s)." % (
        len(berubah), (", %d peringatan" % len(peringatan)) if peringatan else ""))
    return 0


if __name__ == "__main__":
    sys.exit(utama())
