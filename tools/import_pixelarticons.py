#!/usr/bin/env python3
"""Impor ikon pixelarticons (MIT, Gerrit Halfmann) sebagai VectorDrawable Android.

Paket diambil dari registry npm dengan versi TERKUNCI dan diverifikasi sha512,
lalu setiap SVG 24x24 diubah menjadi app/src/main/res/drawable/px_<nama>.xml.
Lisensi MIT ikut disalin ke assets agar tampil di Pengaturan → Lisensi.

Pemakaian:
    python3 tools/import_pixelarticons.py            # impor daftar ICONS di bawah
    python3 tools/import_pixelarticons.py zap wifi   # tambah ikon lain
"""
from __future__ import annotations

import base64
import hashlib
import io
import re
import sys
import tarfile
import urllib.request
import xml.etree.ElementTree as ET
from pathlib import Path

VERSION = "2.4.1"
URL = f"https://registry.npmjs.org/pixelarticons/-/pixelarticons-{VERSION}.tgz"
SHA512 = "NIiwuEmBrFT6YG5PdMnOPRsGm0rbz4TiH0P5sgftjrQugxgdF+xtZPgGrgAOL76KUMJ04kk5Bq2lVLnmBg7IPw=="

# nama pixelarticons -> dipakai untuk
ICONS = {
    "mic": "tombol bicara",
    "stop-solid": "hentikan",
    "send": "kirim teks",
    "settings-cog-2": "pengaturan",
    "close": "tutup / batal",
    "check": "ya / setuju",
    "copy": "salin",
    "trash": "hapus riwayat",
    "robot-face-happy": "wajah asisten (layar sambutan, layar kosong)",
}

REPO = Path(__file__).resolve().parent.parent
DRAWABLE = REPO / "app/src/main/res/drawable"
LICENSES = REPO / "app/src/main/assets/licenses"
SVG_NS = "{http://www.w3.org/2000/svg}"


def fetch() -> tarfile.TarFile:
    data = urllib.request.urlopen(URL, timeout=60).read()
    got = base64.b64encode(hashlib.sha512(data).digest()).decode()
    if got != SHA512:
        sys.exit(f"sha512 tidak cocok untuk {URL}: {got}")
    return tarfile.open(fileobj=io.BytesIO(data), mode="r:gz")


def to_vector(svg: str, name: str) -> str:
    root = ET.fromstring(svg)
    if root.get("viewBox", "0 0 24 24") != "0 0 24 24":
        raise ValueError(f"{name}: viewBox bukan 24x24")
    paths = []
    for el in root.iter():
        tag = el.tag.replace(SVG_NS, "")
        if tag in ("svg",):
            continue
        if tag != "path":
            raise ValueError(f"{name}: elemen <{tag}> belum didukung")
        d = re.sub(r"\s+", " ", el.get("d", "")).strip()
        fill_type = '\n        android:fillType="evenOdd"' if el.get("fill-rule") == "evenodd" else ""
        paths.append(f'    <path\n        android:fillColor="#FF000000"{fill_type}\n        android:pathData="{d}" />')
    return (
        '<?xml version="1.0" encoding="utf-8"?>\n'
        f"<!-- pixelarticons {VERSION} \"{name}\" (MIT, Gerrit Halfmann). Dibuat oleh tools/import_pixelarticons.py -->\n"
        '<vector xmlns:android="http://schemas.android.com/apk/res/android"\n'
        '    android:width="24dp"\n    android:height="24dp"\n'
        '    android:viewportWidth="24"\n    android:viewportHeight="24">\n'
        + "\n".join(paths)
        + "\n</vector>\n"
    )


def main(argv: list[str]) -> None:
    names = argv[1:] or list(ICONS)
    tar = fetch()
    for n in names:
        member = tar.extractfile(f"package/svg/{n}.svg")
        if member is None:
            sys.exit(f"ikon '{n}' tidak ada di pixelarticons {VERSION}")
        out = DRAWABLE / f"px_{n.replace('-', '_')}.xml"
        out.write_text(to_vector(member.read().decode(), n), encoding="utf-8")
        print(f"  {out.relative_to(REPO)}")
    LICENSES.mkdir(parents=True, exist_ok=True)
    lic = tar.extractfile("package/LICENSE").read().decode()
    (LICENSES / "pixelarticons.txt").write_text(f"pixelarticons {VERSION}\nhttps://github.com/halfmage/pixelarticons\n\n{lic}", encoding="utf-8")
    print(f"  {(LICENSES / 'pixelarticons.txt').relative_to(REPO)}")


if __name__ == "__main__":
    main(sys.argv)
