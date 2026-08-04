"""Finds a picture for a noun the reader tapped.

Two decisions are made here, and they are separate on purpose.

First, is this word worth a picture at all? Only concrete nouns are. Showing a picture for
"бежать" or "красивый" teaches nothing, and showing a wrong picture for an abstract noun teaches
something false, so the check is deliberately conservative and says no when unsure.

Second, which picture? Wikimedia is the source: its images are freely licensed and every one
carries an attribution, which is shown with the picture. Nothing is invented and nothing is
scraped from image search.
"""

from __future__ import annotations

import json
import re
import urllib.error
import urllib.parse
import urllib.request
from dataclasses import dataclass
from functools import lru_cache

from .text_preparation import detect_language

USER_AGENT = "SuluRead/1.0 (dyslexia reading app; educational use)"
REQUEST_TIMEOUT_SECONDS = 12
THUMBNAIL_WIDTH = 640

WIKIPEDIA_HOSTS = {"ru": "ru.wikipedia.org", "kk": "kk.wikipedia.org", "en": "en.wikipedia.org"}

# Endings that mark a Russian or Kazakh word as something other than a noun. A verb or an
# adjective with a picture beside it is a wrong answer, not a missing one.
NON_NOUN_ENDINGS_RU = (
    "ать", "ять", "еть", "ить", "уть", "ыть", "ться", "тся", "ешь", "ишь",
    "ый", "ий", "ое", "ая", "яя", "ые", "ие", "ого", "его", "ому", "ему",
    "но", "ло", "ла", "ли", "ел", "ил", "ал",
)
NON_NOUN_ENDINGS_KK = ("ды", "ді", "ты", "ті", "ған", "ген", "қан", "кен", "ады", "еді", "майды")
NON_NOUN_ENDINGS_EN = ("ing", "ed", "ly", "ous", "ful", "ive", "able", "ible")

# Words that are common, concrete and picturable are the point of the feature; words that are
# grammatical machinery never are.
STOP_WORDS = {
    "и", "а", "но", "да", "не", "ни", "же", "бы", "ли", "то", "как", "что", "кто", "где",
    "он", "она", "они", "мы", "вы", "ты", "я", "это", "тот", "эта", "все", "уже", "еще",
    "мен", "сен", "ол", "біз", "сіз", "олар", "бұл", "сол", "және", "бірақ", "үшін", "деп",
    "the", "and", "but", "not", "for", "with", "this", "that", "they", "you", "was", "were",
}

MIN_NOUN_LENGTH = 3


@dataclass(frozen=True)
class WordPicture:
    word: str
    image_url: str
    page_url: str
    attribution: str
    license_name: str


def normalize_word(raw: str) -> str:
    return raw.strip().strip("«»\"'.,!?;:()[]—–-").lower()


def looks_like_noun(word: str) -> bool:
    """Conservative test for a concrete noun.

    Full morphology for Russian and Kazakh is far more than this feature justifies, so this is a
    filter rather than a parser: it rules out the obvious non-nouns and lets the picture lookup
    itself be the final judge, since a word with no encyclopaedia article gets no picture anyway.
    """
    normalized = normalize_word(word)
    if len(normalized) < MIN_NOUN_LENGTH or normalized in STOP_WORDS:
        return False
    if any(character.isdigit() for character in normalized):
        return False

    language = detect_language(normalized)
    if language == "en":
        endings = NON_NOUN_ENDINGS_EN
    elif language == "kk":
        endings = NON_NOUN_ENDINGS_KK
    else:
        endings = NON_NOUN_ENDINGS_RU

    return not normalized.endswith(endings)


def _request_json(url: str) -> dict | None:
    request = urllib.request.Request(url, headers={"User-Agent": USER_AGENT})
    try:
        with urllib.request.urlopen(request, timeout=REQUEST_TIMEOUT_SECONDS) as response:
            return json.loads(response.read().decode("utf-8"))
    except (urllib.error.URLError, TimeoutError, json.JSONDecodeError, OSError):
        return None


def host_for(language_hint: str) -> str:
    if language_hint.startswith("en"):
        return WIKIPEDIA_HOSTS["en"]
    if language_hint.startswith("ru"):
        return WIKIPEDIA_HOSTS["ru"]
    return WIKIPEDIA_HOSTS["kk"]


@lru_cache(maxsize=2048)
def find_picture(word: str, language_hint: str) -> WordPicture | None:
    """Cached: a class reading the same page taps the same handful of words all lesson."""
    normalized = normalize_word(word)
    if not looks_like_noun(normalized):
        return None

    host = host_for(language_hint)
    url = (
        f"https://{host}/w/api.php?action=query&prop=pageimages|info"
        f"&piprop=thumbnail|name&pithumbsize={THUMBNAIL_WIDTH}&inprop=url"
        f"&titles={urllib.parse.quote(normalized)}&redirects=1&format=json&formatversion=2"
    )
    payload = _request_json(url)
    if payload is None:
        return None

    pages = payload.get("query", {}).get("pages", [])
    if not pages or pages[0].get("missing"):
        return None

    page = pages[0]
    thumbnail = page.get("thumbnail", {}).get("source")
    if not thumbnail:
        return None

    file_name = page.get("pageimage", "")
    attribution, license_name = _image_credit(file_name)

    return WordPicture(
        word=normalized,
        image_url=thumbnail,
        page_url=page.get("fullurl", f"https://{host}/wiki/{urllib.parse.quote(normalized)}"),
        attribution=attribution,
        license_name=license_name,
    )


def _image_credit(file_name: str) -> tuple[str, str]:
    """Author and licence of a Commons file.

    Shown with every picture. These images are free to use but not free of conditions — nearly
    all of them require credit — so a picture whose credit could not be read says so rather than
    appearing with none.
    """
    if not file_name:
        return ("Wikimedia Commons", "See source")

    url = (
        "https://commons.wikimedia.org/w/api.php?action=query&prop=imageinfo"
        "&iiprop=extmetadata&format=json&formatversion=2"
        f"&titles={urllib.parse.quote('File:' + file_name)}"
    )
    payload = _request_json(url)
    if payload is None:
        return ("Wikimedia Commons", "See source")

    pages = payload.get("query", {}).get("pages", [])
    if not pages:
        return ("Wikimedia Commons", "See source")

    info = (pages[0].get("imageinfo") or [{}])[0].get("extmetadata", {})
    artist = _plain_text(info.get("Artist", {}).get("value", "")) or "Wikimedia Commons"
    license_name = info.get("LicenseShortName", {}).get("value", "") or "See source"
    return (artist, license_name)


def _plain_text(markup: str) -> str:
    return re.sub(r"\s+", " ", re.sub(r"<[^>]+>", "", markup)).strip()
