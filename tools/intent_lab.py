#!/usr/bin/env python3
"""Spesifikasi router tingkat 1–2 rbitassistant yang bisa dijalankan.

Ini BUKAN kode produksi. Router sungguhan akan ditulis dalam Kotlin
(core/router). Berkas ini ada karena sandbox tempat rencana ditulis tidak bisa
membangun Android (docs/architecture.md §14), sementara katalog intent perlu
diuji sekarang. Kontrak antara keduanya adalah testdata/golden_intents.json:
router Kotlin nanti WAJIB lulus berkas yang sama.

Cakupan:
  - Tingkat 1: normalisasi (data di intents/_normalizer.yaml)
  - Tingkat 2: pencocokan pola deklaratif (intents/*.yaml)
  - Resolver slot sederhana: aplikasi & kontak dicocokkan PERSIS dengan
    testdata/fixtures/device.yaml (fuzzy = tingkat 3, di luar cakupan)
Tidak dicakup: tingkat 3 (fuzzy) dan tingkat 4 (LLM). Ucapan yang tidak cocok
dengan pola mana pun dilaporkan sebagai "chat" — artinya diteruskan ke tingkat
berikutnya.

Pemakaian:
    .venv-doccheck/bin/python tools/intent_lab.py "tolong buka wa dong"
    .venv-doccheck/bin/python tools/intent_lab.py --golden
"""

from __future__ import annotations

import json
import re
import sys
from dataclasses import dataclass, field
from pathlib import Path
from typing import Any

import yaml

REPO = Path(__file__).resolve().parent.parent
INTENTS_DIR = REPO / "intents"
NORMALIZER_FILE = INTENTS_DIR / "_normalizer.yaml"
DEVICE_FILE = REPO / "testdata" / "fixtures" / "device.yaml"
GOLDEN_FILE = REPO / "testdata" / "golden_intents.json"
UTTERANCES_FILE = REPO / "testdata" / "utterances_id.txt"

REQUIRED_KEYS = ("id", "patterns", "slots", "examples", "action", "confirmation", "needs_network")
CONFIRMATION_VALUES = {"never", "always"}
SLOT_TYPES = {"app_name", "contact", "number", "clock", "enum", "text"}


class CatalogError(Exception):
    """Katalog intent tidak valid. Pesan berisi berkas dan alasannya."""


# ---------------------------------------------------------------------------
# Tingkat 1: normalisasi
# ---------------------------------------------------------------------------

@dataclass
class Normalizer:
    abbreviations: dict[str, str]
    prefix_fillers: list[str]
    suffix_fillers: list[str]
    units: dict[str, int]
    multipliers: dict[str, int]

    @classmethod
    def load(cls, path: Path = NORMALIZER_FILE) -> "Normalizer":
        data = yaml.safe_load(path.read_text(encoding="utf-8"))
        return cls(
            abbreviations={str(k): str(v) for k, v in data["abbreviations"].items()},
            prefix_fillers=sorted(data["prefix_fillers"], key=lambda s: -len(s.split())),
            suffix_fillers=sorted(data["suffix_fillers"], key=lambda s: -len(s.split())),
            units={str(k): int(v) for k, v in data["numbers"]["units"].items()},
            multipliers={str(k): int(v) for k, v in data["numbers"]["multipliers"].items()},
        )

    def __call__(self, text: str) -> str:
        s = text.lower()
        s = s.replace("%", " persen ")
        s = re.sub(r"\b(\d{1,2})[.:](\d{2})\b", r"\1:\2", s)
        s = re.sub(r"[^\w: ]|_", " ", s)
        s = " ".join(s.split())
        s = self._abbreviate(s)
        s = self._numbers(s)
        s = self._strip_fillers(s)
        return s

    def _abbreviate(self, s: str) -> str:
        # Frasa terpanjang dulu, hanya pada batas kata.
        for key in sorted(self.abbreviations, key=lambda k: -len(k.split())):
            s = re.sub(rf"(?<!\S){re.escape(key)}(?!\S)", self.abbreviations[key], s)
        return s

    def _numbers(self, s: str) -> str:
        """Ganti setiap deret kata bilangan dengan angkanya.

        Deret yang sah: setelah satuan < 100 (satu..sebelas) harus datang
        pengali (belas/puluh/ratus/ribu) atau deret berakhir. Jadi
        "dua puluh lima" -> 25, tetapi "dua tiga" -> "2 3" (dua bilangan).
        """
        words = s.split()
        out: list[str] = []
        i = 0
        while i < len(words):
            if words[i] not in self.units:
                out.append(words[i])
                i += 1
                continue
            j = i + 1
            prev_small = self.units[words[i]] < 100
            while j < len(words):
                w = words[j]
                if w in self.multipliers:
                    prev_small = False
                elif w in self.units and not prev_small:
                    prev_small = self.units[w] < 100
                else:
                    break
                j += 1
            out.append(str(self._parse_number(words[i:j])))
            i = j
        return " ".join(out)

    def _parse_number(self, words: list[str]) -> int:
        total, current = 0, 0
        for w in words:
            if w in self.units:
                v = self.units[w]
                if v >= 100:                    # seratus, seribu
                    total += current + v
                    current = 0
                else:
                    current += v
            elif w == "belas":
                total += current + 10
                current = 0
            elif w == "ribu":
                total = (total + current) * 1000
                current = 0
            else:                               # puluh, ratus
                total += current * self.multipliers[w]
                current = 0
        return total + current

    def _strip_fillers(self, s: str) -> str:
        changed = True
        while changed and s:
            changed = False
            for f in self.prefix_fillers:
                if s == f or s.startswith(f + " "):
                    rest = s[len(f):].strip()
                    if rest:                   # jangan kosongkan ucapan seluruhnya
                        s, changed = rest, True
                        break
            for f in self.suffix_fillers:
                if s.endswith(" " + f):
                    s, changed = s[: -len(f)].strip(), True
                    break
        return s


# ---------------------------------------------------------------------------
# Tingkat 2: bahasa pola
# ---------------------------------------------------------------------------
#
#   kata        literal
#   {slot}      menangkap slot; regex ditentukan tipe slot
#   (a|b c)     salah satu alternatif (tiap alternatif boleh beberapa kata)
#   [ ... ]     opsional; boleh berisi slot, alternatif, atau opsional lain
#   [a|b]       singkatan untuk [(a|b)]
#
# Pola dicocokkan terhadap SELURUH ucapan (berjangkar di awal dan akhir).
# Satu slot hanya boleh muncul sekali per pola.

TOKEN_RE = re.compile(r"\s*(\{[a-z_]+\}|[()\[\]|]|[^\s()\[\]|{}]+)")


def _tokenize(pattern: str) -> list[str]:
    pos, tokens = 0, []
    pattern = pattern.strip()
    while pos < len(pattern):
        m = TOKEN_RE.match(pattern, pos)
        if not m or m.end() == pos:
            raise CatalogError(f"pola tidak bisa diurai di posisi {pos}: {pattern!r}")
        tokens.append(m.group(1))
        pos = m.end()
    return tokens


@dataclass
class CompiledPattern:
    source: str
    regex: re.Pattern[str]
    slots: list[str]


class _PatternCompiler:
    """Mengubah pola menjadi regex. Setiap token diawali satu spasi literal,
    dan teks dicocokkan sebagai ' ' + ucapan, supaya bagian opsional tidak
    meninggalkan spasi ganda."""

    def __init__(self, tokens: list[str], slot_regex: dict[str, str], source: str):
        self.t, self.i, self.slot_regex, self.source = tokens, 0, slot_regex, source
        self.seen: list[str] = []

    def compile(self) -> CompiledPattern:
        body = self._seq(stop=set())
        if self.i != len(self.t):
            raise CatalogError(f"token berlebih '{self.t[self.i]}' di pola {self.source!r}")
        return CompiledPattern(self.source, re.compile(rf"^{body}$"), self.seen)

    def _seq(self, stop: set[str]) -> str:
        parts = []
        while self.i < len(self.t) and self.t[self.i] not in stop:
            parts.append(self._item())
        if not parts:
            raise CatalogError(f"urutan kosong di pola {self.source!r}")
        return "".join(parts)

    def _item(self) -> str:
        tok = self.t[self.i]
        self.i += 1
        if tok == "(":
            alts = [self._seq(stop={"|", ")"})]
            while self.i < len(self.t) and self.t[self.i] == "|":
                self.i += 1
                alts.append(self._seq(stop={"|", ")"}))
            self._expect(")")
            return "(?:" + "|".join(alts) + ")"
        if tok == "[":                      # [a|b] = [(a|b)]
            alts = [self._seq(stop={"|", "]"})]
            while self.i < len(self.t) and self.t[self.i] == "|":
                self.i += 1
                alts.append(self._seq(stop={"|", "]"}))
            self._expect("]")
            return "(?:" + "|".join(alts) + ")?"
        if tok in {")", "]", "|"}:
            raise CatalogError(f"'{tok}' tanpa pasangan di pola {self.source!r}")
        if tok.startswith("{"):
            name = tok[1:-1]
            if name not in self.slot_regex:
                raise CatalogError(f"slot '{{{name}}}' tidak dideklarasikan (pola {self.source!r})")
            if name in self.seen:
                raise CatalogError(f"slot '{{{name}}}' muncul dua kali di pola {self.source!r}")
            self.seen.append(name)
            return f" (?P<{name}>{self.slot_regex[name]})"
        return " " + re.escape(tok)

    def _expect(self, tok: str) -> None:
        if self.i >= len(self.t) or self.t[self.i] != tok:
            raise CatalogError(f"kurang '{tok}' di pola {self.source!r}")
        self.i += 1


# ---------------------------------------------------------------------------
# Katalog & resolver
# ---------------------------------------------------------------------------

@dataclass
class Slot:
    name: str
    type: str
    values: dict[str, str] = field(default_factory=dict)   # untuk enum: ucapan -> kanonik
    max_words: int | None = None

    def regex(self) -> str:
        if self.type == "number":
            return r"\d+"
        if self.type == "clock":
            return r"\d{1,2}(?::\d{2})?"
        if self.type == "enum":
            keys = sorted(self.values, key=len, reverse=True)
            return "|".join(re.escape(k) for k in keys)
        return r"\S+(?: \S+)*?"          # app_name, contact, text: lazy, dicek resolver


@dataclass
class Intent:
    id: str
    file: str
    slots: dict[str, Slot]
    patterns: list[CompiledPattern]
    examples: list[str]
    action: str
    confirmation: str
    needs_network: bool


class Device:
    def __init__(self, path: Path, norm: Normalizer):
        data = yaml.safe_load(path.read_text(encoding="utf-8"))
        self.apps = self._index(data["installed_apps"], norm)
        self.contacts = self._index(data["contacts"], norm)

    @staticmethod
    def _index(items: list[dict[str, Any]], norm: Normalizer) -> dict[str, str]:
        idx: dict[str, str] = {}
        for it in items:
            for alias in [it["name"], *it.get("aliases", [])]:
                idx[norm(str(alias))] = it["name"]
        return idx


def load_catalog(norm: Normalizer) -> list[Intent]:
    intents: list[Intent] = []
    seen_ids: dict[str, str] = {}
    for path in sorted(INTENTS_DIR.glob("*.yaml")):
        if path.name.startswith("_"):
            continue
        rel = path.relative_to(REPO).as_posix()
        try:
            data = yaml.safe_load(path.read_text(encoding="utf-8"))
        except yaml.YAMLError as exc:
            where = getattr(exc, "problem_mark", None)
            loc = f":{where.line + 1}" if where else ""
            raise CatalogError(f"{rel}{loc}: YAML tidak valid ({getattr(exc, 'problem', exc)})") from None
        if not isinstance(data, dict):
            raise CatalogError(f"{rel}: harus berupa mapping")
        missing = [k for k in REQUIRED_KEYS if k not in data]
        if missing:
            raise CatalogError(f"{rel}: kunci wajib hilang: {', '.join(missing)}")
        iid = data["id"]
        if path.stem != iid:
            raise CatalogError(f"{rel}: nama berkas harus sama dengan id '{iid}'")
        if iid in seen_ids:
            raise CatalogError(f"{rel}: id '{iid}' sudah dipakai di {seen_ids[iid]}")
        seen_ids[iid] = rel
        if data["confirmation"] not in CONFIRMATION_VALUES:
            raise CatalogError(f"{rel}: confirmation harus salah satu {sorted(CONFIRMATION_VALUES)}")
        if not isinstance(data["needs_network"], bool):
            raise CatalogError(f"{rel}: needs_network harus true/false")

        slots: dict[str, Slot] = {}
        for name, spec in (data["slots"] or {}).items():
            # Router Kotlin memakai named group Java, yang tidak menerima '_'.
            if not isinstance(name, str) or not re.fullmatch(r"[a-z][a-z0-9]*", name):
                raise CatalogError(f"{rel}: nama slot {name!r} harus [a-z][a-z0-9]* (named group Java)")
            stype = spec.get("type")
            if stype not in SLOT_TYPES:
                raise CatalogError(f"{rel}: slot '{name}' bertipe '{stype}', harus salah satu {sorted(SLOT_TYPES)}")
            values: dict[str, str] = {}
            if stype == "enum":
                raw = spec.get("values")
                if isinstance(raw, list):
                    pairs = [(v, v) for v in raw]
                elif isinstance(raw, dict):
                    pairs = list(raw.items())
                else:
                    raise CatalogError(f"{rel}: slot enum '{name}' butuh 'values' (list atau map)")
                for k, v in pairs:
                    # YAML 1.1 membaca on/off/yes/no tanpa kutip sebagai boolean, YAML 1.2
                    # tidak. Parser Kotlin bisa berbeda dari PyYAML -> wajib string eksplisit.
                    if not isinstance(k, str) or not isinstance(v, str):
                        raise CatalogError(
                            f"{rel}: slot enum '{name}': nilai {k!r}: {v!r} bukan string — "
                            f"beri tanda kutip (mis. \"on\"), karena YAML 1.1 dan 1.2 membacanya berbeda")
                values = {k: v for k, v in pairs}
            slots[name] = Slot(name, stype, values, spec.get("max_words"))

        slot_regex = {n: s.regex() for n, s in slots.items()}
        patterns = []
        for p in data["patterns"]:
            try:
                patterns.append(_PatternCompiler(_tokenize(p), slot_regex, p).compile())
            except CatalogError as exc:
                raise CatalogError(f"{rel}: {exc}") from None
            except re.error as exc:
                raise CatalogError(f"{rel}: pola {p!r} menghasilkan regex tidak valid ({exc})") from None
        used = {s for cp in patterns for s in cp.slots}
        unused = set(slots) - used
        if unused:
            raise CatalogError(f"{rel}: slot dideklarasikan tapi tidak dipakai pola mana pun: {sorted(unused)}")
        if not data["examples"]:
            raise CatalogError(f"{rel}: minimal satu contoh (examples)")

        intents.append(Intent(iid, rel, slots, patterns, list(data["examples"]),
                              data["action"], data["confirmation"], data["needs_network"]))
    if not intents:
        raise CatalogError("intents/: tidak ada katalog intent")
    return intents


# ---------------------------------------------------------------------------
# Router
# ---------------------------------------------------------------------------

@dataclass
class Route:
    kind: str                      # "command" | "chat" | "ambiguous"
    normalized: str
    intent: str | None = None
    slots: dict[str, Any] = field(default_factory=dict)
    pattern: str | None = None
    candidates: list[str] = field(default_factory=list)
    reason: str = ""


class Router:
    def __init__(self) -> None:
        self.norm = Normalizer.load()
        self.catalog = load_catalog(self.norm)
        self.device = Device(DEVICE_FILE, self.norm)

    def _resolve(self, slot: Slot, raw: str) -> Any:
        """Kembalikan nilai kanonik, atau None bila slot tidak valid."""
        if slot.max_words is not None and len(raw.split()) > slot.max_words:
            return None
        if slot.type == "number":
            return int(raw)
        if slot.type == "clock":
            h, _, m = raw.partition(":")
            hh, mm = int(h), int(m or 0)
            return f"{hh}:{mm:02d}" if hh <= 23 and mm <= 59 else None
        if slot.type == "enum":
            return slot.values.get(raw)
        if slot.type == "app_name":
            return self.device.apps.get(raw)
        if slot.type == "contact":
            return self.device.contacts.get(raw)
        return raw                                              # text

    def route(self, utterance: str) -> Route:
        text = self.norm(utterance)
        subject = " " + text
        # (literal_chars, intent, slots, pattern)
        hits: list[tuple[int, Intent, dict[str, Any], str]] = []
        for intent in self.catalog:
            best: tuple[int, dict[str, Any], str] | None = None
            for cp in intent.patterns:
                m = cp.regex.match(subject)
                if not m:
                    continue
                values: dict[str, Any] = {}
                captured = 0
                ok = True
                for name, raw in m.groupdict().items():
                    if raw is None:
                        continue
                    val = self._resolve(intent.slots[name], raw)
                    if val is None:
                        ok = False
                        break
                    values[name] = val
                    captured += len(raw)
                if not ok:
                    continue
                literal = len(text) - captured
                if best is None or literal > best[0]:
                    best = (literal, values, cp.source)
            if best:
                hits.append((best[0], intent, best[1], best[2]))

        if not hits:
            return Route("chat", text, reason="tidak ada pola yang cocok -> tingkat 3/4")
        hits.sort(key=lambda h: -h[0])
        top = [h for h in hits if h[0] == hits[0][0]]
        if len(top) > 1:
            return Route("ambiguous", text, candidates=sorted(h[1].id for h in top),
                         reason="beberapa intent sama spesifiknya")
        lit, intent, values, src = top[0]
        return Route("command", text, intent.id, values, src,
                     reason=f"{lit} karakter literal dari {len(text)}")


# ---------------------------------------------------------------------------
# Uji golden
# ---------------------------------------------------------------------------

@dataclass
class GoldenReport:
    passed: int = 0
    known_gaps: int = 0
    failures: list[str] = field(default_factory=list)
    per_intent: dict[str, int] = field(default_factory=dict)
    chat_cases: int = 0
    examples_checked: int = 0


def run_golden(router: Router) -> GoldenReport:
    rep = GoldenReport()
    ids = {i.id for i in router.catalog}
    data = json.loads(GOLDEN_FILE.read_text(encoding="utf-8"))
    seen: set[str] = set()

    for n, case in enumerate(data["cases"], start=1):
        utt, expect = case["text"], case["expect"]
        tag = f"golden #{n} {utt!r}"
        if utt in seen:
            rep.failures.append(f"{tag}: duplikat")
        seen.add(utt)
        if expect != "chat" and expect not in ids:
            rep.failures.append(f"{tag}: intent '{expect}' tidak ada di katalog")
            continue
        r = router.route(utt)
        got = r.intent if r.kind == "command" else r.kind
        ok = got == expect and (expect == "chat" or r.slots == case.get("slots", {}))
        if case.get("known_gap"):
            if ok:
                rep.failures.append(f"{tag}: ditandai known_gap tetapi sekarang LULUS — hapus tandanya")
            else:
                rep.known_gaps += 1
            continue
        if ok:
            rep.passed += 1
            if expect == "chat":
                rep.chat_cases += 1
            else:
                rep.per_intent[expect] = rep.per_intent.get(expect, 0) + 1
        else:
            detail = f"dapat {got}"
            if r.kind == "command":
                detail += f" {r.slots} via pola {r.pattern!r}"
            elif r.kind == "ambiguous":
                detail += f" {r.candidates}"
            want = expect if expect == "chat" else f"{expect} {case.get('slots', {})}"
            rep.failures.append(f"{tag} -> normal {r.normalized!r}: harap {want}, {detail}")

    # Setiap intent minimal 3 kasus golden positif yang lulus.
    for iid in sorted(ids):
        if rep.per_intent.get(iid, 0) < 3:
            rep.failures.append(f"intent '{iid}': hanya {rep.per_intent.get(iid, 0)} kasus golden lulus, minimal 3")
    if rep.chat_cases < 15:
        rep.failures.append(f"hanya {rep.chat_cases} kasus 'chat' (jebakan), minimal 15")

    # Contoh di YAML juga harus benar-benar terarah ke intent-nya sendiri.
    for intent in router.catalog:
        for ex in intent.examples:
            rep.examples_checked += 1
            r = router.route(ex)
            if not (r.kind == "command" and r.intent == intent.id):
                got = r.intent if r.kind == "command" else r.kind
                rep.failures.append(f"{intent.file}: contoh {ex!r} terarah ke {got}, bukan {intent.id}")

    # Korpus Fase 0 harus bagian dari golden (supaya transkrip ASR bisa langsung dinilai).
    if UTTERANCES_FILE.exists():
        for ln, line in enumerate(UTTERANCES_FILE.read_text(encoding="utf-8").splitlines(), start=1):
            line = line.strip()
            if line and not line.startswith("#") and line not in seen:
                rep.failures.append(f"{UTTERANCES_FILE.relative_to(REPO)}:{ln}: {line!r} tidak ada di golden")
    return rep


def main(argv: list[str]) -> int:
    try:
        router = Router()
    except CatalogError as exc:
        print(f"KATALOG TIDAK VALID: {exc}")
        return 1
    if argv[1:2] == ["--golden"]:
        rep = run_golden(router)
        for f in rep.failures:
            print(f"  - {f}")
        status = "GAGAL" if rep.failures else "LULUS"
        print(f"{status} — {rep.passed} kasus golden lulus ({rep.chat_cases} jebakan chat), "
              f"{rep.known_gaps} celah yang diketahui, {rep.examples_checked} contoh YAML, "
              f"{len(router.catalog)} intent.")
        return 1 if rep.failures else 0
    for utt in argv[1:] or [line for line in sys.stdin.read().splitlines() if line.strip()]:
        r = router.route(utt)
        print(json.dumps({"input": utt, **r.__dict__}, ensure_ascii=False))
    return 0


if __name__ == "__main__":
    sys.exit(main(sys.argv))
