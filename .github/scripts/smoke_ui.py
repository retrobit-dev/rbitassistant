#!/usr/bin/env python3
"""Uji asap APK di emulator: buka aplikasi, periksa UI, ketik perintah, cari crash.

Hasil dilaporkan sebagai annotation (::notice / ::error) karena hanya itu yang bisa
dibaca agen pengembang. Keluar dengan kode 1 bila ada pemeriksaan wajib yang gagal.
"""
import re
import subprocess
import sys
import time
import xml.etree.ElementTree as ET

PKG = "dev.retrobit.assistant"
problems: list[str] = []
report: list[str] = []


def sh(*args: str, check: bool = False) -> str:
    r = subprocess.run(["adb", *args], capture_output=True, text=True, timeout=120)
    if check and r.returncode != 0:
        raise RuntimeError(f"adb {' '.join(args)}: {r.stderr.strip()}")
    return r.stdout


def esc(s: str) -> str:
    return s.replace("%", "%25").replace("\r", "%0D").replace("\n", "%0A")


def dump() -> ET.Element:
    for _ in range(3):
        sh("shell", "uiautomator", "dump", "/sdcard/ui.xml")
        xml = sh("exec-out", "cat", "/sdcard/ui.xml")
        if "<hierarchy" in xml:
            return ET.fromstring(xml[xml.index("<hierarchy"):])
        time.sleep(2)
    raise RuntimeError("uiautomator dump gagal")


def texts(root: ET.Element) -> list[str]:
    return [n.get("text", "") for n in root.iter("node") if n.get("text")]


def center(node: ET.Element) -> tuple[int, int]:
    x1, y1, x2, y2 = map(int, re.findall(r"\d+", node.get("bounds", "[0,0][0,0]")))
    return (x1 + x2) // 2, (y1 + y2) // 2


def tap_text(root: ET.Element, text: str) -> bool:
    """Ketuk elemen dengan teks ATAU content-desc (tombol ikon) tertentu."""
    for n in root.iter("node"):
        if n.get("text") == text or n.get("content-desc") == text:
            x, y = center(n)
            sh("shell", "input", "tap", str(x), str(y))
            return True
    return False


def scroll_tap(text: str, tries: int = 5) -> bool:
    """Seperti tap_text, tetapi menggulir elemen scrollable bila teks belum terlihat."""
    for _ in range(tries):
        root = dump()
        if tap_text(root, text):
            return True
        scroll = next((n for n in root.iter("node") if n.get("scrollable") == "true"), None)
        if scroll is None:
            return False
        x1, y1, x2, y2 = map(int, re.findall(r"\d+", scroll.get("bounds")))
        cx = (x1 + x2) // 2
        sh("shell", "input", "swipe", str(cx), str(y1 + (y2 - y1) * 4 // 5), str(cx), str(y1 + (y2 - y1) // 5), "400")
        time.sleep(1)
    return False


def alive() -> bool:
    return bool(sh("shell", "pidof", PKG).strip())


def type_and_send(sentence: str) -> list[str]:
    root = dump()
    field = next((n for n in root.iter("node") if n.get("class") == "android.widget.EditText"), None)
    if field is None:
        problems.append("kolom ketik tidak ditemukan")
        return []
    x, y = center(field)
    sh("shell", "input", "tap", str(x), str(y))
    time.sleep(1)
    sh("shell", "input", "text", sentence.replace(" ", "%s"))
    time.sleep(1)
    sh("shell", "input", "keyevent", "111")  # ESC: tutup keyboard agar tombol Kirim terlihat
    time.sleep(1)
    if not tap_text(dump(), "Kirim"):
        problems.append("tombol Kirim tidak ditemukan")
        return []
    time.sleep(4)
    return texts(dump())


def main() -> int:
    time.sleep(3)
    if not alive():
        problems.append("proses aplikasi tidak berjalan setelah dibuka")
    else:
        root = dump()
        t = texts(root)
        if "Selamat datang di Rbit Asisten" in t:
            report.append("Layar sambutan tampil")
            if not scroll_tap("Lewati"):
                problems.append("tombol Lewati tidak ditemukan")
            time.sleep(3)
            root = dump()
            t = texts(root)
        else:
            problems.append("layar sambutan tidak tampil pada pemasangan baru")
        report.append("Layar utama: " + " | ".join(x[:60] for x in t[:8]))
        if "Rbit Asisten" not in t:
            problems.append("judul 'Rbit Asisten' tidak tampil")

        after = type_and_send("jam berapa sekarang")
        reply = [x for x in after if x.startswith("Sekarang")]
        report.append("Perintah 'jam berapa sekarang' -> " + (reply[-1] if reply else "TIDAK ADA BALASAN"))
        if not reply:
            problems.append("perintah what_time tidak dibalas")

        after = type_and_send("apa ibukota jepang")
        chat = [x for x in after if "Alasan:" in x]
        report.append("Chat tanpa API key -> " + (chat[-1].replace("\n", " ")[:160] if chat else "TIDAK ADA BALASAN"))
        if not chat:
            problems.append("jalur chat/offline tidak membalas")

        if tap_text(dump(), "Pengaturan"):
            time.sleep(3)
            if "API key Gemini" not in texts(dump()):
                problems.append("layar Pengaturan tidak terbuka")
            diag: list[str] = []
            seen_settings: list[str] = []
            for _ in range(6):  # panel Diagnostik ada di bawah; uiautomator hanya melihat yang tampil
                root = dump()
                t = texts(root)
                seen_settings += [x for x in t if x not in seen_settings]
                diag = [x for x in t if "Versi 0." in x]
                if diag:
                    break
                scroll = next((n for n in root.iter("node") if n.get("scrollable") == "true"), None)
                if scroll is None:
                    break
                x1, y1, x2, y2 = map(int, re.findall(r"\d+", scroll.get("bounds")))
                cx = (x1 + x2) // 2
                sh("shell", "input", "swipe", str(cx), str(y1 + (y2 - y1) * 4 // 5), str(cx), str(y1 + (y2 - y1) // 5), "400")
                time.sleep(1)
            if not diag:
                report.append("Teks di Pengaturan: " + " | ".join(x[:40] for x in seen_settings))
            report.append("Layar: " + sh("shell", "wm", "size").strip())
            report.append("Diagnostik emulator:\n" + (diag[0] if diag else "(tidak tampil)"))
            if not diag:
                problems.append("panel Diagnostik tidak tampil")
        else:
            problems.append("tombol Pengaturan tidak ditemukan")
        if not alive():
            problems.append("aplikasi mati selama uji")

    crash = sh("logcat", "-d", "-b", "crash")
    if PKG in crash or "FATAL EXCEPTION" in crash:
        problems.append("crash:\n" + crash[-2500:])

    print(f"::notice title=Uji asap emulator::{esc(chr(10).join(report))}")
    if problems:
        print(f"::error title=Uji asap gagal::{esc(chr(10).join(problems))}")
        return 1
    return 0


if __name__ == "__main__":
    sys.exit(main())
