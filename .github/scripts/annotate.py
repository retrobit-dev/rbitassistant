#!/usr/bin/env python3
"""Ubah kegagalan build Gradle menjadi annotation GitHub Actions.

Alasan: agen yang mengembangkan repo ini hanya bisa membaca annotation lewat API
check-runs, bukan log mentah. GitHub membatasi 10 annotation error per langkah,
jadi kesalahan dikelompokkan ke beberapa annotation berisi banyak baris.
"""
import glob
import os
import re
import sys
import xml.etree.ElementTree as ET

ROOT = os.getcwd()


def esc(s: str) -> str:
    return s.replace("%", "%25").replace("\r", "%0D").replace("\n", "%0A")


def emit(msg: str, title: str, file: str | None = None, line: str | None = None) -> None:
    loc = ""
    if file:
        loc = f"file={file}"
        if line:
            loc += f",line={line}"
        loc += ","
    print(f"::error {loc}title={title}::{esc(msg[:3500])}")


def main() -> None:
    log = open(sys.argv[1], encoding="utf-8", errors="replace").read() if len(sys.argv) > 1 else ""
    used = 0

    # 1. Galat compiler Kotlin: "e: file:///path/File.kt:12:5 pesan"
    kt = re.findall(r"^e: (?:file://)?(/\S+?\.kts?):(\d+):(\d+) (.+)$", log, re.M)
    seen, uniq = set(), []
    for f, ln, col, msg in kt:
        key = (f, ln, msg)
        if key not in seen:
            seen.add(key)
            uniq.append((os.path.relpath(f, ROOT), ln, col, msg))
    if uniq:
        emit("\n".join(f"{f}:{ln}:{col} {m}" for f, ln, col, m in uniq[:80]), f"Kotlin: {len(uniq)} galat")
        used += 1
        for f, ln, col, m in uniq[:5]:
            emit(m, "Kotlin", f, ln)
            used += 1

    # 2. Tes JUnit yang gagal
    fails = []
    for x in glob.glob("app/build/test-results/**/*.xml", recursive=True):
        for tc in ET.parse(x).getroot().iter("testcase"):
            for tag in ("failure", "error"):
                el = tc.find(tag)
                if el is not None:
                    body = (el.get("message") or "") + "\n" + (el.text or "")
                    fails.append(f"{tc.get('classname')}.{tc.get('name')}:\n{body[:3000]}")
    for fl in fails[:2]:
        emit(fl, "Tes gagal")
        used += 1

    # 3. Ringkasan Gradle "What went wrong"
    for block in re.findall(r"\* What went wrong:\n(.*?)(?:\n\* Try:|\Z)", log, re.S)[:2]:
        emit(block.strip()[:3000], "Gradle")
        used += 1

    # 4. Galat lain (AAPT, manifest, dependency) yang tidak terangkum di atas
    other = [l for l in log.splitlines() if re.search(r"(ERROR:|error:|AAPT|Could not resolve|FAILURE)", l)]
    if other and used < 9:
        emit("\n".join(dict.fromkeys(other))[:3000], "Baris galat lain")
        used += 1

    if used == 0:
        emit("\n".join(log.splitlines()[-60:]), "Ekor log")


if __name__ == "__main__":
    main()
