#!/usr/bin/env python3
"""Pemindai statis ringan untuk bug, error, dan celah keamanan repo ini.

Scanner ini melengkapi lint, unit test, CodeQL, dan Gitleaks dengan aturan
yang spesifik pada pola proyek: Android/Java/Kotlin, XML manifest, remote
web JavaScript, serta skrip Python. Aturan sengaja menjaga hasil tetap
pendek: temuan "error" menggagalkan CI, sedangkan "warning" memberi sinyal
untuk ditinjau tanpa memblokir build.

Pemakaian:
  python3 scripts/security_audit.py                 # scan + laporan manusia
  python3 scripts/security_audit.py --json          # laporan machine-readable
  python3 scripts/security_audit.py --strict        # warning juga menggagalkan
  python3 scripts/security_audit.py --self-test     # uji mesin aturan

Suppression per baris:
  ... // audit-ignore: id_aturan
  // audit-ignore-next: id_aturan   (di baris SEBELUM baris sasaran)
Gunakan hanya untuk pengecualian yang benar-benar aman dan tulis alasannya
di komentar/baris terdekat. Tidak ada mekanisme ignore global.
"""

from __future__ import annotations

import argparse
import json
import re
import sys
import tempfile
from dataclasses import dataclass
from pathlib import Path
from typing import Callable, Iterable


ROOT = Path(__file__).resolve().parent.parent
MAX_FILE_BYTES = 2 * 1024 * 1024
IGNORE_RE = re.compile(r"audit-ignore\s*:\s*([A-Za-z0-9_,\s-]+)")
NEXT_IGNORE_RE = re.compile(r"audit-ignore-next\s*:\s*([A-Za-z0-9_,\s-]+)")


@dataclass(frozen=True)
class Rule:
    rule_id: str
    severity: str
    title: str
    pattern: str
    languages: frozenset[str]
    flags: int = re.IGNORECASE
    excluded_paths: frozenset[str] = frozenset()
    # True = abaikan match yang jatuh di dalam string "..." (mis. pola
    # `catch(e){}` pada JavaScript yang ditempel di literal string Kotlin).
    skip_in_strings: bool = False
    # True = jalankan juga lintasan multi-baris (issue #193): pola sink
    # yang berhenti di '\n' buta terhadap argumen yang dibungkus ke baris
    # berikut oleh formatter.
    multiline: bool = False


RULES = [
    Rule(
        "secret_github_token",
        "error",
        "Possible GitHub token committed",
        r"\b(?:github_pat_|ghp_)[A-Za-z0-9_]{30,}",
        frozenset({"text"}),
    ),
    Rule(
        "secret_aws_access_key",
        "error",
        "Possible AWS access key committed",
        r"\bAKIA[0-9A-Z]{16}\b",
        frozenset({"text"}),
    ),
    Rule(
        "secret_private_key",
        "error",
        "Private key material committed",
        r"-----BEGIN [A-Z0-9 ]*PRIVATE KEY-----",
        frozenset({"text"}),
    ),
    Rule(
        "sensitive_log",
        "error",
        "Sensitive identifier may be written to Android log",
        r"\bLog\.[vdiew]\s*\([^;\n]*(?:token|pin|password|secret|credential)",
        frozenset({"kotlin", "java"}),
        multiline=True,
    ),
    Rule(
        "webview_js_bridge",
        "error",
        "JavaScript bridge exposes Android internals to web content",
        r"\baddJavascriptInterface\s*\(",
        frozenset({"kotlin", "java"}),
    ),
    Rule(
        "webview_javascript_enabled",
        "error",
        "WebView JavaScript execution is enabled",
        r"\bsetJavaScriptEnabled\s*\(\s*true\s*\)",
        frozenset({"kotlin", "java"}),
    ),
    Rule(
        "world_readable_file",
        "error",
        "World-readable/writable local file mode",
        r"\bMODE_WORLD_(?:READABLE|WRITEABLE)\b",
        frozenset({"kotlin", "java"}),
    ),
    Rule(
        "backup_enabled",
        "error",
        "Android backup is enabled for local app data",
        r'\bandroid:allowBackup\s*=\s*"true"',
        frozenset({"xml"}),
    ),
    Rule(
        "sql_injection_sink",
        "error",
        "SQL statement appears to be concatenated at runtime",
        r"\b(?:rawQuery|execSQL)\s*\([^;\n]*(?:\+\s*|\$\{)",
        frozenset({"kotlin", "java"}),
        multiline=True,
    ),
    Rule(
        "external_path_sink",
        "error",
        "File path is built directly from an external request parameter",
        r"\b(?:File|Paths\.get)\s*\([^;\n]*(?:parms\[|getParameter|queryParameters|request\.query)",
        frozenset({"kotlin", "java"}),
        multiline=True,
    ),
    Rule(
        "shell_true",
        "error",
        "Operating-system command runs through a shell",
        r"\bsubprocess\.\w+\s*\([^;\n]*shell\s*=\s*True|\bRuntime\.getRuntime\(\)\.exec\s*\(",
        frozenset({"python", "kotlin", "java"}),
    ),
    Rule(
        "js_dynamic_exec",
        "error",
        "Dynamic JavaScript execution/document write sink",
        r"\b(?:eval\s*\(|new\s+Function\s*\(|document\.write\s*\()",
        frozenset({"javascript", "html"}),
    ),
    Rule(
        "dom_xss_source",
        "error",
        "External request data reaches innerHTML without a visible boundary",
        r"\binnerHTML\s*=.*(location\.|location\[|window\.location|document\.URL|decodeURI|JSON\.parse|parms\[|request\.query)",
        frozenset({"javascript", "html"}),
    ),
    Rule(
        "unsafe_blank_window",
        "error",
        "New browser window/tab opened without opener isolation",
        r"\bwindow\.open\s*\((?!.*noopener)",
        frozenset({"javascript", "html"}),
    ),
    Rule(
        "cleartext_base_config",
        "warning",
        "Android cleartext traffic is enabled globally",
        r'cleartextTrafficPermitted\s*=\s*"true"',
        frozenset({"xml"}),
    ),
    Rule(
        "kotlin_empty_catch",
        "warning",
        "Empty exception handler can hide runtime failures",
        r"\bcatch\s*\([^)]*\)\s*\{\s*\}",
        frozenset({"kotlin", "java"}),
        skip_in_strings=True,
    ),
    Rule(
        "debug_stack_trace",
        "warning",
        "Raw stack trace printing bypasses application logging",
        r"\.printStackTrace\s*\(",
        frozenset({"kotlin", "java"}),
    ),
    Rule(
        "global_coroutine_scope",
        "warning",
        "Unstructured GlobalScope coroutine can outlive its owner",
        r"\bGlobalScope\b",
        frozenset({"kotlin"}),
    ),
    Rule(
        "non_null_assertion",
        "warning",
        "Non-null assertion can crash when platform/API data changes",
        r"!!(?![=])",
        frozenset({"kotlin"}),
        excluded_paths=frozenset({"app/src/test/"}),
    ),
    Rule(
        "maintenance_marker",
        "warning",
        "Unresolved maintenance marker",
        r"\b(?:TODO|FIXME|HACK|XXX)\b",  # audit-ignore: maintenance_marker
        frozenset({"text"}),
    ),    Rule(
        "header_comparison_case_sensitive",
        "warning",
        "HTTP header compared without ignoreCase (Accept-Ranges, Content-Type, etc.)",
        r"""getHeaderField\s*\([^)]+\)\s*==\s*"[^"]+""" ,
        frozenset({"kotlin", "java"}),
    ),

    Rule(
        "unbounded_read_text",
        "warning",
        "Response/file body read without byte limit (OOM risk on bad server)",
        "[.]readText[(][)]",
        frozenset({"kotlin", "java"}),
        excluded_paths=frozenset({"app/src/test/"}),
    ),
    Rule(
        "header_equals_case_sensitive",
        "warning",
        "HTTP header compared with equals() without ignoreCase",
                "getHeaderField[(][^;\\n]*[.]equals[(]\\s*\"[^\"]*\"\\s*[)]",
        frozenset({"kotlin", "java"}),
    ),
    Rule(
        "file_scoped_catch_all",
        "warning",
        "Broad catch-all that swallows exceptions silently (no logging/throw)",
        r"""catch\s*\(_:\s*Exception\)\s*\{\s*\}""" ,
        frozenset({"kotlin"}),
    ),

]


EXTENSION_LANGUAGES = {
    ".kt": "kotlin",
    ".kts": "kotlin",
    ".java": "java",
    ".xml": "xml",
    ".html": "html",
    ".js": "javascript",
    ".py": "python",
    ".yml": "text",
    ".yaml": "text",
    ".md": "text",
    ".toml": "text",
    ".properties": "text",
    ".txt": "text",
}

EXCLUDED_PARTS = {
    ".git",
    ".gradle",
    ".idea",
    "build",
    "node_modules",
    "__pycache__",
}

EXCLUDED_FILES = {
    # Hasil minifier akan diperiksa sinkron oleh prepare_remote.py. Scan sumber
    # saja supaya suppression dan nomor baris tetap relevan untuk developer.
    Path("app/src/main/assets/remote.html"),
    Path("gradle/verification-metadata.xml"),
}

# Risiko yang disengaja dan sudah didokumentasikan. Ini bukan tempat menyembunyikan
# temuan baru; gunakan hanya untuk keputusan produk yang terlihat di komentar kode.
ACCEPTED_RISKS = {
    (
        "cleartext_base_config",
        "app/src/main/res/xml/network_security_config.xml",
    ): "Download manager perlu menerima URL HTTP dari pengguna.",
}


def language_for(path: Path) -> str | None:
    return EXTENSION_LANGUAGES.get(path.suffix.lower())


def repository_files(root: Path) -> list[Path]:
    import subprocess

    if (root / ".git").exists():
        result = subprocess.run(
            ["git", "ls-files", "-z"],
            cwd=root,
            check=True,
            capture_output=True,
        )
        names = result.stdout.decode("utf-8", "surrogateescape").split("\0")
        candidates = [root / name for name in names if name]
    else:
        candidates = [path for path in root.rglob("*") if path.is_file()]

    selected: list[Path] = []
    for absolute in candidates:
        try:
            relative = absolute.resolve().relative_to(root.resolve())
        except (ValueError, FileNotFoundError):
            continue
        if any(part in EXCLUDED_PARTS for part in relative.parts):
            continue
        if relative in EXCLUDED_FILES:
            continue
        if language_for(relative) is None:
            continue
        selected.append(absolute)
    return sorted(selected, key=lambda path: path.as_posix())


def read_lines(path: Path) -> list[str] | None:
    try:
        if path.stat().st_size > MAX_FILE_BYTES:
            return None
        return path.read_text(encoding="utf-8").splitlines()
    except (OSError, UnicodeError):
        return None


def _in_string(line: str, pos: int) -> bool:
    # True bila posisi jatuh di dalam literal string "..." (hitung kutip
    # tak-escaped sebelumnya; ganjil = di dalam). Cukup untuk string
    # sebaris Kotlin/Java; triple-quote multi-baris tidak dilacak.
    in_str = False
    i = 0
    while i < pos:
        char = line[i]
        if char == "\\":
            i += 2
            continue
        if char == '"':
            in_str = not in_str
        i += 1
    return in_str


def blank_literals(text: str) -> str:
    """Salinan teks dengan ISI literal string/char diblanko jadi spasi.

    Panjang dan posisi newline dipertahankan persis 1:1, jadi offset dan
    nomor baris hasil pemindaian pada salinan ini valid juga untuk teks
    asli. Kurung dan titik-koma di dalam string tidak lagi bisa memutus
    atau memanjangkan rentang multi-baris (issue #254). Ekspresi template
    Kotlin `${...}` dan `$nama` di dalam string adalah KODE — disalin apa
    adanya (literal bersarang di dalamnya ikut diblanko rekursif); string
    mentah triple-quote tidak punya escape tetapi templatenya tetap aktif.
    Literal char hanya diakui bila bentuknya sah (maks 8 char: '\\uXXXX');
    apostrof liar di luar literal dibiarkan sebagai char biasa.
    """
    out = list(text)
    n = len(text)

    def blank(i: int, j: int) -> None:
        for k in range(i, j):
            if out[k] != "\n":
                out[k] = " "

    def char_literal_end(i: int) -> int | None:
        j = i + 1
        if j >= n:
            return None
        if text[j] == "\\":
            if j + 1 < n and text[j + 1] == "u":
                if (
                    j + 6 < n
                    and all(c in "0123456789abcdefABCDEF" for c in text[j + 2 : j + 6])
                    and text[j + 6] == "'"
                ):
                    return j + 7
                return None
            if j + 2 < n and text[j + 2] == "'":
                return j + 3
            return None
        if text[j] in "'\n":
            return None
        if j + 1 < n and text[j + 1] == "'":
            return j + 2
        return None

    def scan_template(i: int) -> int:
        # i tepat sesudah '${': ekspresi kode disalin apa adanya sampai '}'
        # penyeimbang; literal di dalamnya diblanko lewat scan_literal.
        depth = 1
        while i < n and depth:
            char = text[i]
            if char == "{":
                depth += 1
                i += 1
            elif char == "}":
                depth -= 1
                i += 1
            elif char in "\"'":
                i = scan_literal(i)
            else:
                i += 1
        return i

    def scan_string_body(i: int, raw: bool) -> int:
        # i tepat sesudah pembuka string; blanko isi sampai penutup.
        while i < n:
            char = text[i]
            if raw and text.startswith('"""', i):
                return i + 3
            if not raw:
                if char == '"':
                    return i + 1
                if char == "\n":
                    return i
                if char == "\\":
                    blank(i, min(i + 2, n))
                    i += 2
                    continue
            if char == "$" and i + 1 < n and text[i + 1] == "{":
                i = scan_template(i + 2)
                continue
            if char == "$" and i + 1 < n and (text[i + 1].isalpha() or text[i + 1] == "_"):
                # Template sederhana $nama: referensi variabel = kode.
                j = i + 1
                while j < n and (text[j].isalnum() or text[j] == "_"):
                    j += 1
                i = j
                continue
            blank(i, i + 1)
            i += 1
        return i

    def scan_literal(i: int) -> int:
        if text[i] == "'":
            end = char_literal_end(i)
            if end is None:
                return i + 1
            blank(i + 1, end - 1)
            return end
        if text.startswith('"""', i):
            return scan_string_body(i + 3, raw=True)
        return scan_string_body(i + 1, raw=False)

    i = 0
    while i < n:
        if text[i] in "\"'":
            i = scan_literal(i)
        else:
            i += 1
    return "".join(out)


def ignored_on_line(line: str, previous: str | None, rule_id: str) -> bool:
    markers: list[str] = []
    for source in (line, previous):
        if not source:
            continue
        match = IGNORE_RE.search(source)
        if match:
            markers.extend(part.strip() for part in match.group(1).split(","))
    return rule_id in markers


def scan_text(
    relative_path: Path,
    lines: list[str],
    rules: Iterable[Rule] = RULES,
) -> list[dict[str, object]]:
    language = language_for(relative_path) or "text"
    relative_posix = relative_path.as_posix()
    compiled = [
        (rule, re.compile(rule.pattern, rule.flags))
        for rule in rules
        if (language in rule.languages or "text" in rule.languages)
        and not any(relative_posix.startswith(prefix) for prefix in rule.excluded_paths)
    ]
    findings: list[dict[str, object]] = []
    seen: set[tuple[Path, int, str]] = set()

    for index, line in enumerate(lines):
        previous = lines[index - 1] if index else None
        # Konvensi umum (seperti eslint-disable-next-line): marker
        # audit-ignore-next di baris SEBELUM menekan temuan baris ini.
        # Semantik lama terbalik (marker di baris SESUDAH yang menekan),
        # jebakan laten yang membingungkan — diperbaiki di issue #150.
        prev_next_ignore = NEXT_IGNORE_RE.search(previous) if previous else None
        for rule, pattern in compiled:
            if ignored_on_line(line, previous, rule.rule_id):
                continue
            # Token persis (belah koma + strip) seperti ignored_on_line:
            # pencocokan substring membuat prosa yang kebetulan memuat id
            # rule ikut menekan temuan (issue #175).
            if prev_next_ignore and rule.rule_id in (
                part.strip() for part in prev_next_ignore.group(1).split(",")
            ):
                continue
            match = pattern.search(line)
            if not match:
                continue
            if rule.skip_in_strings and _in_string(line, match.start()):
                continue
            if (rule.rule_id, relative_posix) in ACCEPTED_RISKS:
                continue
            key = (relative_path, index + 1, rule.rule_id)
            if key in seen:
                continue
            seen.add(key)
            excerpt = line.strip()
            if len(excerpt) > 180:
                excerpt = excerpt[:177] + "..."
            findings.append(
                {
                    "path": relative_path.as_posix(),
                    "line": index + 1,
                    "column": match.start() + 1,
                    "rule_id": rule.rule_id,
                    "severity": rule.severity,
                    "title": rule.title,
                    "excerpt": excerpt,
                }
            )

    # Lintasan multi-baris (issue #193) untuk aturan bertanda multiline:
    # varian pola tanpa batas '\n' dijalankan pada teks utuh; rentang
    # dibatasi 300 karakter dan berhenti di ';' maupun kurung tutup yang
    # TIDAK seimbang — kurung tutup penutup panggilan mengakhiri
    # pernyataan, jadi kata sensitif dari pernyataan LAIN sesudah
    # panggilan selesai tidak ikut tertuduh (false positive issue #201),
    # tetapi pasangan kurung seimbang di dalam rentang (panggilan
    # bersarang ala foo() / COUNT(*) hasil formatter) boleh dilewati agar
    # bocoran multi-baris bersarang tetap tertangkap (issue #216), kini
    # sampai sarang DUA tingkat — kelompok luar boleh memuat satu tingkat
    # kelompok lagi di dalamnya (issue #240: foo(bar()) sempat lolos).
    # Lintasan ini berjalan pada salinan yang isi literal string/char-nya
    # sudah diblanko (blank_literals, issue #254): kurung/titik-koma di
    # dalam string tidak lagi memutus atau memanjangkan rentang, dengan
    # offset yang tetap 1:1 terhadap teks asli.
    # Temuan dipetakan ke baris awal match dan didedupe terhadap hasil
    # per baris lewat `seen`.
    full = blank_literals("\n".join(lines))
    for rule, pattern in compiled:
        if not rule.multiline:
            continue
        long_pattern = re.compile(
            rule.pattern.replace(
                "[^;\\n]*",
                r"(?:\((?:[^()]|\([^()]*\))*\)|[^();]){0,300}",
            ),
            rule.flags,
        )
        for match in long_pattern.finditer(full):
            start_index = full.count("\n", 0, match.start())
            line = lines[start_index]
            previous = lines[start_index - 1] if start_index else None
            if ignored_on_line(line, previous, rule.rule_id):
                continue
            prev_next_ignore = (
                NEXT_IGNORE_RE.search(previous) if previous else None
            )
            if prev_next_ignore and rule.rule_id in (
                part.strip() for part in prev_next_ignore.group(1).split(",")
            ):
                continue
            if (rule.rule_id, relative_posix) in ACCEPTED_RISKS:
                continue
            key = (relative_path, start_index + 1, rule.rule_id)
            if key in seen:
                continue
            line_start = full.rfind("\n", 0, match.start()) + 1
            column = match.start() - line_start
            if rule.skip_in_strings and _in_string(line, column):
                continue
            seen.add(key)
            excerpt = line.strip()
            if len(excerpt) > 180:
                excerpt = excerpt[:177] + "..."
            findings.append(
                {
                    "path": relative_path.as_posix(),
                    "line": start_index + 1,
                    "column": column + 1,
                    "rule_id": rule.rule_id,
                    "severity": rule.severity,
                    "title": rule.title,
                    "excerpt": excerpt,
                }
            )
    findings.sort(key=lambda item: (int(item["line"]), int(item["column"])))
    return findings


def scan_path(root: Path, rules: Iterable[Rule] = RULES) -> list[dict[str, object]]:
    findings: list[dict[str, object]] = []
    for absolute in repository_files(root):
        relative = absolute.resolve().relative_to(root.resolve())
        lines = read_lines(absolute)
        if lines is None:
            continue
        findings.extend(scan_text(relative, lines, rules))
    return sorted(
        findings,
        key=lambda item: (
            item["severity"] != "error",
            str(item["path"]),
            int(item["line"]),  # type: ignore[arg-type]
            str(item["rule_id"]),
        ),
    )


def print_github_annotations(findings: list[dict[str, object]], limit: int = 50) -> None:
    for finding in findings[:limit]:
        command = "error" if finding["severity"] == "error" else "warning"
        print(
            f"::{command} file={finding['path']},line={finding['line']}"
            f"::[{finding['rule_id']}] {finding['title']}"
        )


def print_report(findings: list[dict[str, object]]) -> None:
    errors = sum(item["severity"] == "error" for item in findings)
    warnings = sum(item["severity"] == "warning" for item in findings)
    for finding in findings:
        label = "ERROR" if finding["severity"] == "error" else "WARN "
        print(
            f"{label} {finding['path']}:{finding['line']}:{finding['column']} "
            f"[{finding['rule_id']}] {finding['title']}"
        )
        print(f"      {finding['excerpt']}")
    print(f"AUDIT SUMMARY: {errors} error, {warnings} warning, {len(RULES)} rules.")


def run_self_test() -> int:
    github_token = "ghp_" + ("a1BcD2eF3gH4iJ5kL6mN7oP8qR9sT0uV")
    samples = {
        Path("sample.kt"): [
            "class Sample {",
            "    val mode = Context.MODE_WORLD_READABLE",
            "    fun log(token: String) = Log.d(\"x\", token)",
            "    fun empty() = runCatching { }",
            "    fun bad() { try { work() } catch (_: Exception) {} }",
            "    fun leaky() = conn.inputStream.bufferedReader().readText()",
            "    fun hdr(c: HttpURLConnection) = c.getHeaderField(\"Accept-Ranges\").equals(\"bytes\")",
            "}",
        ],
        Path("sample_multi.kt"): [
            # Pola multi-baris (issue #193): argumen dibungkus formatter
            # ke baris berikut tetap wajib terdeteksi oleh lintasan kedua.
            "class Wrap {",
            "    fun leak(t: String) = Log.d(\"x\",",
            "        t + token)",
            "    fun q(db: SQLiteDatabase, u: String) = db.rawQuery(\"SELECT * FROM t WHERE u = '\"",
            "        + u, null)",
            "}",
        ],
        Path("sample_nested.kt"): [
            # Regresi issue #216: kata sensitif sesudah pasangan kurung
            # seimbang satu tingkat (panggilan bersarang hasil formatter)
            # tetap wajib terdeteksi oleh lintasan multi-baris.
            "class Nested {",
            "    fun leak() = Log.d(\"x\",",
            "        foo() + token)",
            "    fun q(db: SQLiteDatabase, u: String) = db.rawQuery(",
            "        \"SELECT COUNT(*) FROM t WHERE u = '\" + u, null)",
            "    fun f() = File(",
            "        baseDir(ctx), parms[\"id\"])",
            "}",
        ],
        Path("sample_nested2.kt"): [
            # Regresi issue #240: sarang DUA tingkat sebelum kata sensitif
            # (foo(bar()) / base(foo())) tetap wajib terdeteksi oleh
            # lintasan multi-baris — dengan rentang satu tingkat keduanya
            # lolos tanpa temuan.
            "class Nested2 {",
            "    fun leak() = Log.d(\"x\",",
            "        foo(bar()) + token)",
            "    fun f() = File(",
            "        base(foo()), parms[\"id\"])",
            "}",
        ],
        Path("sample_fp.kt"): [
            # Regresi issue #201: Log polos yang kurungnya DITUTUP, lalu
            # pernyataan LAIN menyebut token dalam jarak <300 karakter —
            # tidak boleh tertuduh sensitive_log oleh lintasan multi-baris.
            "class Fp {",
            "    fun a() = Log.d(\"unduh\", \"mulai unduhan baru\")",
            "    fun b(item: String) {",
            "        val token = tokenUntuk(item)",
            "        simpan(token)",
            "    }",
            "}",
        ],
        Path("sample_strparen.kt"): [
            # Regresi issue #254: kurung/titik-koma DI DALAM string tidak
            # boleh mengubah rentang multi-baris — "(" di string menelan
            # token pernyataan berikut (FP), "(" tak berpasangan menutupi
            # bocoran asli, dan ";" di string memutus rentang (FN).
            "class StrParen {",
            "    fun a() = Log.d(\"x (\", a)",
            "    fun b() = token",
            "    fun leak() = Log.d(\"tag (\",",
            "        token)",
            "    fun leak2() = Log.d(\"a;b\",",
            "        secret)",
            "}",
        ],
        Path("sample_str.kt"): [
            "class Grab {",
            '    val js = "(function(){try{work();}catch(e){}})"',
            "}",
        ],
        Path("sample.js"): [
            "// audit-ignore-next: js_dynamic_exec",
            "const value = eval(userInput);",
            "const capability = new Function('a => a');",
        ],
        Path("sample2.js"): [
            # Marker prosa yang hanya MEMUAT id rule sebagai substring
            # tidak boleh menekan temuan (issue #175).
            "// audit-ignore-next: bukan_js_dynamic_exec_sama_sekali",
            "const other = eval(userInput);",
        ],
        Path("sample.txt"): [
            f"GITHUB_TOKEN={github_token}",
            "TODO: validate this flow",  # audit-ignore: maintenance_marker
        ],
    }
    with tempfile.TemporaryDirectory() as directory:
        root = Path(directory)
        for path, lines in samples.items():
            (root / path).write_text("\n".join(lines) + "\n", encoding="utf-8")
        findings = scan_path(root)

    by_rule = {str(item["rule_id"]) for item in findings}
    required = {
        "secret_github_token",
        "sensitive_log",
        "world_readable_file",
        "js_dynamic_exec",
        "kotlin_empty_catch",
        "maintenance_marker",
        "unbounded_read_text",
        "header_equals_case_sensitive",
    }
    missing = required - by_rule
    suppressed = any(
        item["rule_id"] == "js_dynamic_exec" and item["line"] == 2
        and item["path"] == "sample.js"
        for item in findings
    )
    str_leak = [
        item
        for item in findings
        if item["rule_id"] == "kotlin_empty_catch" and item["path"] == "sample_str.kt"
    ]
    prose_suppressed = not any(
        item["rule_id"] == "js_dynamic_exec" and item["path"] == "sample2.js"
        for item in findings
    )
    multi_leak = any(
        item["rule_id"] == "sensitive_log" and item["line"] == 2
        and item["path"] == "sample_multi.kt"
        for item in findings
    )
    multi_sql = any(
        item["rule_id"] == "sql_injection_sink" and item["line"] == 4
        and item["path"] == "sample_multi.kt"
        for item in findings
    )
    fp_hit = any(
        item["rule_id"] == "sensitive_log" and item["path"] == "sample_fp.kt"
        for item in findings
    )
    nested_log = any(
        item["rule_id"] == "sensitive_log" and item["path"] == "sample_nested.kt"
        for item in findings
    )
    nested_sql = any(
        item["rule_id"] == "sql_injection_sink" and item["path"] == "sample_nested.kt"
        for item in findings
    )
    nested_path = any(
        item["rule_id"] == "external_path_sink" and item["path"] == "sample_nested.kt"
        for item in findings
    )
    nested2_log = any(
        item["rule_id"] == "sensitive_log" and item["path"] == "sample_nested2.kt"
        for item in findings
    )
    nested2_path = any(
        item["rule_id"] == "external_path_sink" and item["path"] == "sample_nested2.kt"
        for item in findings
    )
    strparen_fp = any(
        item["rule_id"] == "sensitive_log" and item["line"] == 2
        and item["path"] == "sample_strparen.kt"
        for item in findings
    )
    strparen_leak = any(
        item["rule_id"] == "sensitive_log" and item["line"] == 4
        and item["path"] == "sample_strparen.kt"
        for item in findings
    )
    strparen_semi = any(
        item["rule_id"] == "sensitive_log" and item["line"] == 6
        and item["path"] == "sample_strparen.kt"
        for item in findings
    )
    if missing or suppressed or str_leak or prose_suppressed or not multi_leak or not multi_sql or fp_hit or not nested_log or not nested_sql or not nested_path or not nested2_log or not nested2_path or strparen_fp or not strparen_leak or not strparen_semi:
        print(json.dumps(findings, indent=2))
        print(f"SELF TEST GAGAL: missing={sorted(missing)}, suppressed={suppressed}, str_leak={len(str_leak)}, prose_suppressed={prose_suppressed}, multi_leak={multi_leak}, multi_sql={multi_sql}, fp_hit={fp_hit}, nested_log={nested_log}, nested_sql={nested_sql}, nested_path={nested_path}, nested2_log={nested2_log}, nested2_path={nested2_path}, strparen_fp={strparen_fp}, strparen_leak={strparen_leak}, strparen_semi={strparen_semi}")
        return 1
    print(f"SELF TEST OK: {len(required)} aturan representatif terdeteksi, suppression aktif.")
    return 0


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--json", action="store_true", help="output findings as JSON")
    parser.add_argument("--strict", action="store_true", help="warnings also fail")
    parser.add_argument("--self-test", action="store_true", help="test rule engine only")
    args = parser.parse_args()

    if args.self_test:
        return run_self_test()

    findings = scan_path(ROOT)
    errors = sum(item["severity"] == "error" for item in findings)
    warnings = sum(item["severity"] == "warning" for item in findings)

    if args.json:
        print(json.dumps({"findings": findings, "errors": errors, "warnings": warnings}, indent=2))
    else:
        print_report(findings)
        print_github_annotations(findings)

    if errors:
        print(f"AUDIT GAGAL: {errors} security/error finding harus diperbaiki.", file=sys.stderr)
        return 1
    if args.strict and warnings:
        print(f"AUDIT GAGAL (--strict): {warnings} warning harus ditinjau/diperbaiki.", file=sys.stderr)
        return 1
    print("AUDIT OK: tidak ada temuan ber-level error.")
    return 0


if __name__ == "__main__":
    sys.exit(main())
