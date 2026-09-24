#!/usr/bin/env python3
"""Cek integritas dokumen rbitassistant.

Dijalankan secara manual (belum ada CI):

    .venv-doccheck/bin/python tools/check_docs.py

Yang diperiksa:
  1. Setiap blok kode ```yaml di docs/*.md harus benar-benar ter-parse sebagai YAML,
     dan bila blok itu berupa katalog intent (punya kunci `id`), skema dasarnya
     harus lengkap: id, patterns (list), slots (map), action.
  2. Setiap tautan markdown relatif ([teks](path) dan [teks](path#anchor)) harus
     menunjuk berkas yang ada, dan anchor-nya harus benar-benar ada di berkas itu.

Keluar dengan kode 1 bila ada satu saja kegagalan.
"""

from __future__ import annotations

import re
import sys
import unicodedata
from pathlib import Path

import yaml

REPO = Path(__file__).resolve().parent.parent
DOCS = REPO / "docs"

FENCE_RE = re.compile(r"```(?P<lang>[a-zA-Z0-9_+-]*)\n(?P<body>.*?)```", re.DOTALL)
LINK_RE = re.compile(r"(?<!\!)\[(?P<text>[^\]]*)\]\((?P<target>[^)\s]+)\)")
HEADING_RE = re.compile(r"^(#{1,6})\s+(?P<title>.+?)\s*$", re.MULTILINE)


def slugify(title: str) -> str:
    """Meniru slug anchor GitHub: huruf kecil, spasi jadi '-', tanda baca dibuang."""
    out = []
    for ch in title.lower():
        cat = unicodedata.category(ch)
        if ch in " -":
            out.append("-")
        elif cat.startswith("L") or cat.startswith("N"):
            out.append(ch)
        # tanda baca lain dibuang
    slug = "".join(out)
    while "--" in slug:
        slug = slug.replace("--", "-")
    return slug.strip("-")


def anchors_of(path: Path) -> set[str]:
    text = path.read_text(encoding="utf-8")
    # buang blok kode agar '#' di dalam kode tidak dianggap heading
    text = FENCE_RE.sub("", text)
    return {slugify(m.group("title")) for m in HEADING_RE.finditer(text)}


def check_yaml_blocks(failures: list[str]) -> int:
    """Parse semua blok ```yaml di docs/**. Kembalikan jumlah blok yang diperiksa."""
    checked = 0
    for md in sorted(DOCS.rglob("*.md")):
        text = md.read_text(encoding="utf-8")
        for idx, block in enumerate(FENCE_RE.finditer(text)):
            if block.group("lang") != "yaml":
                continue
            checked += 1
            rel = md.relative_to(REPO)
            try:
                data = yaml.safe_load(block.group("body"))
            except yaml.YAMLError as exc:
                failures.append(f"{rel}: blok yaml #{idx + 1} gagal di-parse: {exc}")
                continue
            if isinstance(data, dict) and "id" in data:
                for key in ("patterns", "slots", "action"):
                    if key not in data:
                        failures.append(f"{rel}: intent '{data['id']}' kehilangan kunci '{key}'")
                if not isinstance(data.get("patterns"), list) or not data.get("patterns"):
                    failures.append(f"{rel}: intent '{data.get('id')}' patterns harus list tak kosong")
                if not isinstance(data.get("slots"), dict):
                    failures.append(f"{rel}: intent '{data.get('id')}' slots harus mapping")
                print(f"  ok  {rel} blok #{idx + 1}: intent '{data.get('id')}' "
                      f"({len(data.get('patterns') or [])} pola, {len(data.get('slots') or {})} slot)")
            else:
                print(f"  ok  {rel} blok #{idx + 1}: YAML valid (bukan katalog intent)")
    return checked


def check_links(failures: list[str]) -> int:
    """Verifikasi semua tautan relatif + anchor di README.md dan docs/**."""
    checked = 0
    for md in [REPO / "README.md", *sorted(DOCS.rglob("*.md"))]:
        text = md.read_text(encoding="utf-8")
        for m in LINK_RE.finditer(text):
            target = m.group("target")
            if target.startswith(("http://", "https://", "mailto:")):
                continue
            checked += 1
            path_part, _, anchor = target.partition("#")
            rel = md.relative_to(REPO)
            if path_part:
                dest = (md.parent / path_part).resolve()
                if not dest.exists():
                    failures.append(f"{rel}: tautan '{target}' -> berkas tidak ada")
                    continue
            else:
                dest = md
            if anchor:
                if dest.suffix == ".md":
                    if anchor not in anchors_of(dest):
                        failures.append(f"{rel}: tautan '{target}' -> anchor '#{anchor}' tidak ditemukan di {dest.name}")
    return checked


def main() -> int:
    failures: list[str] = []
    print("[1/2] Mem-parse blok YAML di docs/**")
    n_yaml = check_yaml_blocks(failures)
    print(f"[2/2] Memeriksa tautan internal di README.md + docs/**")
    n_links = check_links(failures)

    print()
    if failures:
        print(f"GAGAL — {len(failures)} masalah:")
        for f in failures:
            print(f"  - {f}")
        return 1
    print(f"LULUS — {n_yaml} blok YAML ter-parse, {n_links} tautan internal valid.")
    return 0


if __name__ == "__main__":
    sys.exit(main())
