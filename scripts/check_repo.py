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

def fast_checks() -> list[Result]:
    results = [no_local_sdk()]
    results += list(static_checks())
    results.append(agents_md_completeness())
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
