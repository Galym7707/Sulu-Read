"""Lookup against the bundled hunspell Kazakh dictionary.

The dictionary files in ``backend/app/data`` are third-party data under MPL 1.1;
see ``backend/app/data/LICENSE-kk_KZ.txt``. This module is the only reader of them.

The lookup is deliberately generous. It is used to decide whether an OCR word is
already a real Kazakh word, and accepting too much only means a repair is skipped,
while accepting too little means correct text gets rewritten.
"""

import re
from pathlib import Path
from threading import Lock

DATA_DIRECTORY = Path(__file__).resolve().parents[1] / "data"
DICTIONARY_PATH = DATA_DIRECTORY / "kk_KZ.dic"
AFFIX_PATH = DATA_DIRECTORY / "kk_KZ.aff"

_LEXICON_LOCK = Lock()
_LEXICON_LOADED = False
_LEXICON: "KazakhLexicon | None" = None


class KazakhLexicon:
    """Stems plus suffix rules, answering "is this a Kazakh word?"."""

    def __init__(self, stems: dict[str, str], suffix_rules: dict[str, list]) -> None:
        self._stems = stems
        self._suffix_rules = suffix_rules
        self._suffixes = sorted(suffix_rules, key=len, reverse=True)

    @property
    def stem_count(self) -> int:
        return len(self._stems)

    def contains(self, word: str) -> bool:
        lowered = word.lower()
        if not lowered:
            return False
        if lowered in self._stems:
            return True

        for suffix in self._suffixes:
            if len(suffix) >= len(lowered) or not lowered.endswith(suffix):
                continue
            base = lowered[: -len(suffix)]
            for flag, strip, condition in self._suffix_rules[suffix]:
                stem = base + strip
                flags = self._stems.get(stem)
                if flags is not None and flag in flags and condition.search(stem):
                    return True
        return False


def load_lexicon(dictionary_path: Path, affix_path: Path) -> KazakhLexicon | None:
    try:
        stems = _parse_dictionary(dictionary_path)
        suffix_rules = _parse_affix_rules(affix_path)
    except OSError:
        return None
    except Exception:
        return None

    if not stems or not suffix_rules:
        return None
    return KazakhLexicon(stems, suffix_rules)


def get_lexicon() -> KazakhLexicon | None:
    """Return the bundled lexicon, loading it on first use.

    Returns None when the data files are missing or unreadable, so callers can
    simply make no repairs instead of failing the request.
    """
    global _LEXICON, _LEXICON_LOADED

    if _LEXICON_LOADED:
        return _LEXICON

    with _LEXICON_LOCK:
        if not _LEXICON_LOADED:
            _LEXICON = load_lexicon(DICTIONARY_PATH, AFFIX_PATH)
            _LEXICON_LOADED = True
    return _LEXICON


def _parse_dictionary(path: Path) -> dict[str, str]:
    stems: dict[str, str] = {}
    text = path.read_text(encoding="utf-8", errors="replace").lstrip("﻿")
    for line in text.splitlines()[1:]:
        entry = line.strip()
        if not entry:
            continue
        word, _, flags = entry.partition("/")
        word = word.strip().lower()
        if word:
            stems[word] = stems.get(word, "") + flags.strip()
    return stems


def _parse_affix_rules(path: Path) -> dict[str, list]:
    suffix_rules: dict[str, list] = {}
    text = path.read_text(encoding="utf-8", errors="replace").lstrip("﻿")
    for line in text.splitlines():
        parts = line.split()
        if len(parts) < 5 or parts[0] != "SFX":
            continue

        flag, strip, addition, condition = parts[1], parts[2], parts[3], parts[4]
        if addition == "0":
            continue

        stripped = "" if strip == "0" else strip
        try:
            compiled = re.compile(condition + "$")
        except re.error:
            continue
        suffix_rules.setdefault(addition, []).append((flag, stripped, compiled))
    return suffix_rules
