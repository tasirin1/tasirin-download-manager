#!/usr/bin/env python3
"""Ringkasan coverage JaCoCo ke log + job summary. Dipakai job `build`.

Membaca XML laporan (dibangkitkan jacocoTestReport); absennya laporan hanya
warning agar langkah upload-CI tak gagal keras.
"""
import glob
import os
import xml.etree.ElementTree as ET


def main():
    files = glob.glob("app/build/reports/jacoco/jacocoTestReport/jacocoTestReport.xml")
    if not files:
        print("::warning::Laporan JaCoCo tidak ditemukan")
        return 0
    root = ET.parse(files[0]).getroot()
    summary = []
    for ctype in ("LINE", "BRANCH", "INSTRUCTION", "METHOD"):
        covered = missed = 0
        # HANYA counter anak langsung <report> (issue #378): root.iter
        # menjumlahkan juga counter package/class/sourcefile sehingga
        # angka tercetak menghitung berlapis-lapis (mis. (36/40) untuk
        # cakupan sejati (9/10)); persen kebetulan mirip, hitungannya salah.
        for counter in root.findall("counter"):
            if counter.get("type") == ctype:
                covered += int(counter.get("covered", 0))
                missed += int(counter.get("missed", 0))
        if covered + missed:
            line = f"- {ctype}: {covered / (covered + missed):.1%} ({covered}/{covered + missed})"
            summary.append(line)
            print(f"JaCoCo {ctype}: {covered / (covered + missed):.1%} ({covered}/{covered + missed})")
    step_summary = os.environ.get("GITHUB_STEP_SUMMARY")
    if step_summary and summary:
        with open(step_summary, "a", encoding="utf-8") as fh:
            fh.write("### JaCoCo coverage (unit test)\n\n" + "\n".join(summary) + "\n")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
