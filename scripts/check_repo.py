#!/usr/bin/env python3
"""Pemeriksa kesehatan repo dengan satu pintu untuk kontributor dan AI."""

from __future__ import annotations

import argparse
import os
import shutil
import subprocess
import sys
import time
from dataclasses import dataclass
from pathlib import Path


ROOT = Path(__file__).resolve().parents[1]
REQUIRED_FILES = (
    "AGENTS.md",
    "CHANGELOG.md",
    ".github/CODEOWNERS",
    "CONTRIBUTING.md",
    "LICENSE",
    "README.md",
    "README.en.md",
    "SECURITY.md",
)


@dataclass(frozen=True)
class Result:
    name: str
    ok: bool
    seconds: float
    output: str


def run(name: str, argv: list[str], cwd: Path = ROOT) -> Result:
    started = time.monotonic()
    completed = subprocess.run(
        argv,
        cwd=cwd,
        text=True,
        stdout=subprocess.PIPE,
        stderr=subprocess.STDOUT,
        check=False,
    )
    return Result(
        name=name,
        ok=completed.returncode == 0,
        seconds=time.monotonic() - started,
        output=completed.stdout.strip(),
    )


def python_tool(name: str, script: str, args: list[str] | None = None) -> Result:
    return run(name, [sys.executable, str(ROOT / script), *(args or [])])


def static_checks() -> tuple[Result, ...]:
    missing = [name for name in REQUIRED_FILES if not (ROOT / name).is_file()]
    detail = "Semua file pengelolaan wajib ada."
    if missing:
        detail = "File wajib tidak ditemukan: " + ", ".join(missing)
    return (Result("struktur repo", not missing, 0.0, detail),)


def _local_sdk_markers() -> list[str]:
    """Penanda Android SDK terpasang/terkonfigurasi lokal."""
    markers: list[str] = []
    env = {**os.environ}
    for var in ("ANDROID_HOME", "ANDROID_SDK_ROOT", "ANDROID_SDK_HOME"):
        val = env.get(var, "").strip()
        if val:
            markers.append(f"{var}={val}")
    if shutil.which("sdkmanager"):
        markers.append(f"sdkmanager={shutil.which('sdkmanager')}")
    if (ROOT / "local.properties").is_file():
        markers.append(str(ROOT / "local.properties"))
    for probe in (
        Path.home() / ".android",
        Path.home() / "Android",
        Path("/opt/android-sdk"),
        Path("/usr/lib/android-sdk"),
    ):
        if probe.exists() and (probe / "sdkmanager").exists():
            markers.append(str(probe))
    return markers


def no_local_sdk() -> Result:
    """Saran larangan: SDK lokal tidak boleh diinstal (boros RAM/disk)."""
    in_ci = os.environ.get("CI") in ("true", "1")
    markers = _local_sdk_markers()
    if not in_ci and markers:
        return Result(
            name="no local SDK",
            ok=False,
            seconds=0.0,
            output=(
                "Android SDK lokal terdeteksi (larangan repo): "
                + "; ".join(markers)
                + ". Hapus SDK lokal dan gunakan CI saja untuk build/lint/test."
            ),
        )
    return Result("no local SDK", True, 0.0, "")



def agents_md_completeness() -> Result:
    """AGENTS.md wajib punya bagian 'Best practices untuk AI'."""
    agents = ROOT / "AGENTS.md"
    if not agents.is_file():
        return Result("AGENTS.md exists", False, 0.0, "AGENTS.md tidak ditemukan")
    content = agents.read_text(encoding="utf-8")
    required_sections = [
        "Pola bug yang pernah terjadi",
        "Best practices untuk AI",
        "Aturan pengembangan",
    ]
    missing = [s for s in required_sections if s not in content]
    if missing:
        return Result(
            "AGENTS.md completeness", False, 0.0,
            f"AGENTS.md kurang bagian: {', '.join(missing)}"
        )
    return Result("AGENTS.md completeness", True, 0.0, "OK")



def admin_consistency() -> Result:
    """Penjaga relevansi administrasi: pesan stale, path CODEOWNERS/labeler,
    dan angka toolchain di AGENTS.md harus sinkron dengan sumber aslinya."""
    problems: list[str] = []
    try:
        stale = (ROOT / ".github/workflows/stale.yml").read_text(encoding="utf-8")
        issue_days = pr_days = ""
        for line in stale.splitlines():
            s = line.strip()
            if s.startswith("days-before-issue-stale:"):
                issue_days = s.split(":", 1)[1].strip()
            elif s.startswith("days-before-pr-stale:"):
                pr_days = s.split(":", 1)[1].strip()
        if issue_days and f"{issue_days} hari" not in stale:
            problems.append(f"pesan stale issue tidak menyebut {issue_days} hari")
        if pr_days and f"{pr_days} hari" not in stale:
            problems.append(f"pesan stale PR tidak menyebut {pr_days} hari")
    except OSError as exc:
        problems.append(f"stale.yml tak terbaca: {exc}")
    try:
        owners = (ROOT / ".github/CODEOWNERS").read_text(encoding="utf-8")
        if "assets/remote.html" in owners and "app/src/main/assets/remote.html" not in owners:
            problems.append("CODEOWNERS masih memakai path usang assets/remote.html")
        for lineno, line in enumerate(owners.splitlines(), start=1):
            s = line.strip()
            if not s or s.startswith("#"):
                continue
            path = s.split()[0].lstrip("/")
            if path in ("*", "*.jks", "keystore.b64"):
                continue
            if any(ch in path for ch in ("*", "?", "[")):
                prefix = path.split("*")[0].split("?")[0].split("[")[0].rstrip("/")
                if prefix and not (ROOT / prefix).exists():
                    problems.append(f"CODEOWNERS:{lineno} awalan tak ada: {prefix}")
            elif not (ROOT / path).exists():
                problems.append(f"CODEOWNERS:{lineno} path tak ada: {path}")
    except OSError as exc:
        problems.append(f"CODEOWNERS tak terbaca: {exc}")
    try:
        import yaml as _yaml
        labeler = _yaml.safe_load((ROOT / ".github/labeler.yml").read_text(encoding="utf-8")) or {}

        def _paths(node):
            if isinstance(node, str):
                yield node
            elif isinstance(node, list):
                for item in node:
                    yield from _paths(item)
            elif isinstance(node, dict):
                for key, val in node.items():
                    if key in ("any", "all") and isinstance(val, list):
                        yield from _paths(val)

        for path in _paths(labeler):
            clean = str(path).split("*")[0].rstrip("/")
            if not clean or clean.startswith("#"):
                continue
            if not (ROOT / clean).exists():
                problems.append(f"labeler path tak ada: {path}")
    except OSError as exc:
        problems.append(f"labeler.yml tak terbaca: {exc}")
    except ImportError:
        problems.append("modul yaml tak tersedia untuk validasi labeler")
    try:
        import re as _re
        agents = (ROOT / "AGENTS.md").read_text(encoding="utf-8")
        wrapper = (ROOT / "gradle/wrapper/gradle-wrapper.properties").read_text(encoding="utf-8")
        catalog = (ROOT / "gradle/libs.versions.toml").read_text(encoding="utf-8")
        app_build = (ROOT / "app/build.gradle.kts").read_text(encoding="utf-8")
        gradle_ver = _re.search(r"gradle-(\d+\.\d+\.\d+)-bin\.zip", wrapper)
        agp_ver = _re.search(r'^agp\s*=\s*"([^"]+)"', catalog, _re.M)
        if gradle_ver and gradle_ver.group(1) not in agents:
            problems.append(f"AGENTS.md tak menyebut Gradle {gradle_ver.group(1)}")
        if agp_ver and agp_ver.group(1) not in agents:
            problems.append(f"AGENTS.md tak menyebut AGP {agp_ver.group(1)}")
        jacoco = _re.search(r'minimum\s*=\s*"0\.(\d+)"', app_build)
        if jacoco:
            pct = f"8,{jacoco.group(1)[1:]}" if jacoco.group(1).startswith("0") else jacoco.group(1)
            # Bentuk umum: "0.085" -> "8,5" di dokumen Indonesia.
            expect = "8,5" if jacoco.group(0).endswith('"0.085"') else None
            if expect and expect not in agents:
                problems.append("AGENTS.md ambang JaCoCo tak sinkron dengan app/build.gradle.kts")
    except OSError as exc:
        problems.append(f"cek toolchain tak terbaca: {exc}")
    if problems:
        return Result("admin konsisten", False, 0.0, "; ".join(problems))
    return Result("admin konsisten", True, 0.0, "stale/CODEOWNERS/labeler/toolchain sinkron.")


def fast_checks() -> list[Result]:
    results = [no_local_sdk()]
    results += list(static_checks())
    results.append(agents_md_completeness())
    results.append(admin_consistency())
    results.extend(
        [
            python_tool("remote web", "scripts/prepare_remote.py", ["--check"]),
            run("upload smoke", ["node", str(ROOT / "scripts/upload_smoke_test.js")]),
            python_tool("readme sync", "scripts/check_readme_sync.py"),
            python_tool("audit self-test", "scripts/security_audit.py", ["--self-test"]),
            python_tool("security audit", "scripts/security_audit.py"),
        ]
    )
    if shutil.which("git"):
        results.append(run("whitespace", ["git", "diff", "--check"]))
    else:
        results.append(Result("whitespace", True, 0.0, "Git tidak tersedia; dilewati."))
    return results


def android_checks(gradle: str) -> list[Result]:
    tasks = ["lintDebug", "testDebugUnitTest"]
    return [run("android " + task, [gradle, task]) for task in tasks]


def print_result(result: Result, index: int, total: int) -> None:
    marker = "OK" if result.ok else "GAGAL"
    elapsed = f"{result.seconds:.1f}s" if result.seconds >= 0.05 else ""
    suffix = f" ({elapsed})" if elapsed else ""
    print(f"[{index}/{total}] {result.name}: {marker}{suffix}", flush=True)
    if not result.ok and result.output:
        print(result.output, flush=True)


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument(
        "--pre-commit",
        action="store_true",
        help="jalankan unit test juga bila Java dan Gradle wrapper tersedia",
    )
    parser.add_argument(
        "--android",
        action="store_true",
        help="wajibkan lint dan unit test Android (butuh SDK)",
    )
    args = parser.parse_args()

    results = fast_checks()
    gradle = str(ROOT / "gradlew")
    include_android = args.android or (
        args.pre_commit and os.name != "nt" and Path(gradle).is_file() and shutil.which("java")
    )
    if include_android:
        results.extend(android_checks(gradle))

    for index, result in enumerate(results, start=1):
        print_result(result, index, len(results))

    failed = [result for result in results if not result.ok]
    if failed:
        names = ", ".join(result.name for result in failed)
        print(f"\nHASIL: GAGAL ({names})", flush=True)
        return 1
    mode = "penuh" if include_android else "cepat"
    total_seconds = sum(result.seconds for result in results)
    print(f"\nHASIL: SEMUA SEHAT ({mode}; {total_seconds:.1f}s)", flush=True)
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
