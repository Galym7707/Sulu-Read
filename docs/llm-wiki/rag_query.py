"""BM25 retrieval over the Sulu Read LLM wiki vault.

Usage:
    python docs/llm-wiki/rag_query.py "порог замешательства"
    python docs/llm-wiki/rag_query.py --selftest

ponytail: no embeddings, no index on disk, stdlib only. The vault is a few hundred KB, so
scoring it from scratch on every query is cheaper than keeping an index correct. Swap in a
vector store only if the vault grows past a few MB or cross-lingual recall starts mattering.
"""

import math
import re
import sys
from collections import Counter
from pathlib import Path

VAULT_ROOT = Path(__file__).resolve().parent
SEARCH_DIRS = ("wiki", "raw")
TOKEN_RE = re.compile(r"[0-9a-zA-Zа-яёА-ЯЁәғқңөұүһіӘҒҚҢӨҰҮҺІ]+")
BM25_K1 = 1.5
BM25_B = 0.75
SNIPPET_CHARS = 260


def tokenize(text):
    return [match.group(0).lower() for match in TOKEN_RE.finditer(text)]


def split_sections(text):
    """Split markdown into (heading, body) chunks on '#'-level headings."""
    sections = []
    heading = "(начало файла)"
    body = []
    for line in text.splitlines():
        if line.startswith("#"):
            if any(part.strip() for part in body):
                sections.append((heading, "\n".join(body)))
            heading = line.lstrip("#").strip() or heading
            body = []
            continue
        body.append(line)
    if any(part.strip() for part in body):
        sections.append((heading, "\n".join(body)))
    return sections


def load_chunks(root=VAULT_ROOT):
    chunks = []
    for directory in SEARCH_DIRS:
        for path in sorted((root / directory).rglob("*.md")):
            text = path.read_text(encoding="utf-8")
            for heading, body in split_sections(text):
                chunks.append(
                    {
                        "path": path.relative_to(root).as_posix(),
                        "section": heading,
                        "body": body,
                        "tokens": Counter(tokenize(f"{path.stem} {heading} {body}")),
                    }
                )
    return chunks


def bm25_scores(chunks, query):
    query_tokens = tokenize(query)
    if not chunks or not query_tokens:
        return []

    lengths = [sum(chunk["tokens"].values()) for chunk in chunks]
    average_length = sum(lengths) / len(lengths)
    document_count = len(chunks)

    scores = [0.0] * document_count
    for token in set(query_tokens):
        containing = sum(1 for chunk in chunks if token in chunk["tokens"])
        if not containing:
            continue
        # BM25 idf, floored at zero so a token in every chunk cannot push scores negative.
        idf = max(0.0, math.log(1 + (document_count - containing + 0.5) / (containing + 0.5)))
        for index, chunk in enumerate(chunks):
            frequency = chunk["tokens"].get(token, 0)
            if not frequency:
                continue
            denominator = frequency + BM25_K1 * (
                1 - BM25_B + BM25_B * lengths[index] / average_length
            )
            scores[index] += idf * frequency * (BM25_K1 + 1) / denominator
    return scores


def search(query, limit=5, root=VAULT_ROOT):
    chunks = load_chunks(root)
    scores = bm25_scores(chunks, query)
    ranked = sorted(zip(scores, chunks), key=lambda pair: -pair[0])
    return [(score, chunk) for score, chunk in ranked[:limit] if score > 0]


def snippet(body, query):
    """Show the window around the first query token found, not just the head of the chunk."""
    lowered = body.lower()
    position = min(
        (lowered.find(token) for token in tokenize(query) if lowered.find(token) >= 0),
        default=0,
    )
    start = max(0, position - SNIPPET_CHARS // 3)
    return " ".join(body[start : start + SNIPPET_CHARS].split())


def selftest():
    hits = search("порог замешательства")
    assert hits, "vault retrieval returned nothing for a term that is definitely in the vault"
    assert "dezorientaciya" in hits[0][1]["path"], f"unexpected top hit: {hits[0][1]['path']}"

    exercise_hits = search("названия букв по одной")
    assert any(
        "uprazhneniya-chteniya" in chunk["path"] or "dyslexiadar" in chunk["path"]
        for _, chunk in exercise_hits
    ), "spell-reading rule is not retrievable"

    assert not search("zzzqqqxxx"), "nonsense query must score nothing"
    print("selftest ok")


def main(argv):
    # Windows consoles default to cp1251 here, which cannot encode Cyrillic quotes or arrows.
    if hasattr(sys.stdout, "reconfigure"):
        sys.stdout.reconfigure(encoding="utf-8", errors="replace")
    if not argv:
        print(__doc__)
        return 1
    if argv[0] == "--selftest":
        selftest()
        return 0

    query = " ".join(argv)
    hits = search(query)
    if not hits:
        print(f"Ничего не найдено по запросу: {query}")
        return 0
    for score, chunk in hits:
        print(f"\n[{score:.2f}] {chunk['path']} → «{chunk['section']}»")
        print(f"    {snippet(chunk['body'], query)}")
    return 0


if __name__ == "__main__":
    sys.exit(main(sys.argv[1:]))
