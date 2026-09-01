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
    ),
    Rule(
        "external_path_sink",
        "error",
        "File path is built directly from an external request parameter",
        r"\b(?:File|Paths\.get)\s*\([^;\n]*(?:parms\[|getParameter|queryParameters|request\.query)",
        frozenset({"kotlin", "java"}),
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
        next_line = lines[index + 1] if index + 1 < len(lines) else None
        next_ignore = NEXT_IGNORE_RE.search(next_line) if next_line else None
        for rule, pattern in compiled:
            if ignored_on_line(line, previous, rule.rule_id):
                continue
            if next_ignore and rule.rule_id in next_ignore.group(1):
                continue
            match = pattern.search(line)
            if not match:
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
            "}",
        ],
        Path("sample.js"): [
            "const value = eval(userInput);",
            "// audit-ignore-next: js_dynamic_exec",
            "const capability = new Function('a => a');",
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
    }
    missing = required - by_rule
    suppressed = any(
        item["rule_id"] == "js_dynamic_exec" and item["line"] == 1
        for item in findings
    )
    if missing or suppressed:
        print(json.dumps(findings, indent=2))
        print(f"SELF TEST GAGAL: missing={sorted(missing)}, suppressed={suppressed}")
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
