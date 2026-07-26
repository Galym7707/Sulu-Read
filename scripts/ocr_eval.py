"""Synthetic OCR accuracy evaluation for Kazakh/Russian text.

Development and CI tool. Not imported by the FastAPI application.

Usage:
    python scripts/ocr_eval.py
    python scripts/ocr_eval.py --save-baseline
    python scripts/ocr_eval.py --engine easyocr      # requires rendered images
"""

import argparse
import json
import random
import sys
from pathlib import Path

REPOSITORY_ROOT = Path(__file__).resolve().parents[1]
if str(REPOSITORY_ROOT) not in sys.path:
    sys.path.insert(0, str(REPOSITORY_ROOT))

from backend.app.services.ocr_correction import correct_ocr_text  # noqa: E402
from scripts.ocr_eval_corpus import CORPUS  # noqa: E402

KAZAKH_TO_LOOKALIKE_BASE = {
    "қ": "к",
    "ғ": "г",
    "ә": "а",
    "ө": "о",
    "ұ": "у",
    "ү": "у",
    "і": "и",
    "ң": "н",
    "һ": "х",
}
KAZAKH_TO_LOOKALIKE = dict(KAZAKH_TO_LOOKALIKE_BASE)
KAZAKH_TO_LOOKALIKE.update(
    {key.upper(): value.upper() for key, value in KAZAKH_TO_LOOKALIKE_BASE.items()}
)

HOMOGLYPH_INJECTION_BASE = {
    "а": "a",
    "с": "c",
    "е": "e",
    "к": "k",
    "м": "m",
    "о": "o",
    "р": "p",
    "т": "t",
    "х": "x",
    "у": "y",
}
HOMOGLYPH_INJECTION = dict(HOMOGLYPH_INJECTION_BASE)
HOMOGLYPH_INJECTION.update(
    {key.upper(): value.upper() for key, value in HOMOGLYPH_INJECTION_BASE.items()}
)

KAZAKH_LETTERS_TRACKED = "әғқңөұүһі"

DEFAULT_KAZAKH_DROP_RATE = 0.6
DEFAULT_HOMOGLYPH_RATE = 0.05
BASELINE_PATH = REPOSITORY_ROOT / "scripts" / "ocr_eval_baseline.json"
RESULTS_DIRECTORY = REPOSITORY_ROOT / "scripts" / "ocr_eval_results"


def corrupt(
    text: str,
    seed: int,
    kazakh_drop_rate: float = DEFAULT_KAZAKH_DROP_RATE,
    homoglyph_rate: float = DEFAULT_HOMOGLYPH_RATE,
) -> str:
    """Apply a deterministic model of the observed OCR error classes."""
    generator = random.Random(seed)
    characters = []
    for character in text:
        if character in KAZAKH_TO_LOOKALIKE and generator.random() < kazakh_drop_rate:
            characters.append(KAZAKH_TO_LOOKALIKE[character])
            continue
        if character in HOMOGLYPH_INJECTION and generator.random() < homoglyph_rate:
            characters.append(HOMOGLYPH_INJECTION[character])
            continue
        characters.append(character)
    return "".join(characters)


def levenshtein(source, target) -> int:
    if source == target:
        return 0

    previous_row = list(range(len(target) + 1))
    for source_index, source_item in enumerate(source, start=1):
        current_row = [source_index]
        for target_index, target_item in enumerate(target, start=1):
            current_row.append(
                min(
                    previous_row[target_index] + 1,
                    current_row[target_index - 1] + 1,
                    previous_row[target_index - 1] + (source_item != target_item),
                )
            )
        previous_row = current_row
    return previous_row[-1]


def character_error_rate(reference: str, hypothesis: str) -> float:
    return levenshtein(reference, hypothesis) / max(1, len(reference))


def word_error_rate(reference: str, hypothesis: str) -> float:
    reference_words = reference.split()
    return levenshtein(reference_words, hypothesis.split()) / max(1, len(reference_words))


def kazakh_letter_recovery(reference: str, hypothesis: str) -> dict:
    """Per-letter survival counts for the Kazakh-specific alphabet."""
    table = {}
    for letter in KAZAKH_LETTERS_TRACKED:
        expected = reference.lower().count(letter)
        if not expected:
            continue
        table[letter] = {
            "expected": expected,
            "present": hypothesis.lower().count(letter),
        }
    return table


def merge_recovery(total: dict, addition: dict) -> None:
    for letter, counts in addition.items():
        bucket = total.setdefault(letter, {"expected": 0, "present": 0})
        bucket["expected"] += counts["expected"]
        bucket["present"] += counts["present"]


def evaluate(engine: str = "synthetic-noise") -> dict:
    """Run the corpus and return raw vs corrected metrics."""
    rows = []
    raw_recovery: dict = {}
    corrected_recovery: dict = {}

    for index, item in enumerate(CORPUS):
        reference = item["text"]
        if engine == "synthetic-noise":
            raw = corrupt(reference, seed=1000 + index)
        else:
            raw = recognize_with_engine(reference, engine)

        corrected = correct_ocr_text(raw, language_hint=item["language_hint"])

        merge_recovery(raw_recovery, kazakh_letter_recovery(reference, raw))
        merge_recovery(corrected_recovery, kazakh_letter_recovery(reference, corrected))

        rows.append(
            {
                "id": item["id"],
                "language_hint": item["language_hint"],
                "reference": reference,
                "raw": raw,
                "corrected": corrected,
                "cer_raw": character_error_rate(reference, raw),
                "cer_corrected": character_error_rate(reference, corrected),
                "wer_raw": word_error_rate(reference, raw),
                "wer_corrected": word_error_rate(reference, corrected),
            }
        )

    russian_rows = [row for row in rows if row["language_hint"] == "ru"]
    return {
        "engine": engine,
        "snippets": len(rows),
        "cer_raw": _mean(row["cer_raw"] for row in rows),
        "cer_corrected": _mean(row["cer_corrected"] for row in rows),
        "wer_raw": _mean(row["wer_raw"] for row in rows),
        "wer_corrected": _mean(row["wer_corrected"] for row in rows),
        "russian_cer_raw": _mean(row["cer_raw"] for row in russian_rows),
        "russian_cer_corrected": _mean(row["cer_corrected"] for row in russian_rows),
        "kazakh_letters_raw": raw_recovery,
        "kazakh_letters_corrected": corrected_recovery,
        "rows": rows,
    }


def _mean(values) -> float:
    collected = list(values)
    if not collected:
        return 0.0
    return sum(collected) / len(collected)


def recognize_with_engine(text: str, engine: str) -> str:
    """Render `text` to an image and run a real OCR engine over it."""
    image_path = render_snippet(text)
    try:
        if engine == "easyocr":
            import easyocr

            reader = easyocr.Reader(["ru", "mn", "en"], gpu=False)
            result = reader.readtext(str(image_path), detail=0, paragraph=True)
            return " ".join(result)
        if engine == "groq":
            from main import read_text_with_groq_vision

            return read_text_with_groq_vision(image_path.read_bytes(), image_path.name, "kk")
        raise SystemExit(f"Unknown engine: {engine}")
    finally:
        image_path.unlink(missing_ok=True)


FONT_CANDIDATES = (
    "C:/Windows/Fonts/arial.ttf",
    "C:/Windows/Fonts/times.ttf",
    "/usr/share/fonts/truetype/dejavu/DejaVuSans.ttf",
    "/Library/Fonts/Arial.ttf",
)


def render_snippet(text: str) -> Path:
    try:
        from PIL import Image, ImageDraw, ImageFont
    except ImportError:
        raise SystemExit("Pillow is required for --engine easyocr/groq. Install it first.")

    font_path = next((path for path in FONT_CANDIDATES if Path(path).exists()), None)
    if font_path is None:
        raise SystemExit(
            "No usable font found. Add a TTF path with Kazakh Cyrillic glyphs "
            "to FONT_CANDIDATES in scripts/ocr_eval.py."
        )

    font = ImageFont.truetype(font_path, 42)
    image = Image.new("RGB", (1400, 160), (250, 249, 245))
    draw = ImageDraw.Draw(image)
    draw.text((30, 50), text, font=font, fill=(25, 25, 25))

    RESULTS_DIRECTORY.mkdir(parents=True, exist_ok=True)
    output_path = RESULTS_DIRECTORY / "render.jpg"
    image.save(output_path, quality=80)
    return output_path


def print_report(report: dict) -> None:
    print(f"engine: {report['engine']}   snippets: {report['snippets']}")
    print(f"CER  raw {report['cer_raw']:.4f}  ->  corrected {report['cer_corrected']:.4f}")
    print(f"WER  raw {report['wer_raw']:.4f}  ->  corrected {report['wer_corrected']:.4f}")
    print(
        f"CER (Russian only)  raw {report['russian_cer_raw']:.4f}"
        f"  ->  corrected {report['russian_cer_corrected']:.4f}"
    )
    if report["cer_raw"]:
        reduction = 1 - report["cer_corrected"] / report["cer_raw"]
        print(f"CER reduction: {reduction * 100:.1f}%")

    print("\nKazakh letter survival (present / expected):")
    for letter in KAZAKH_LETTERS_TRACKED:
        raw = report["kazakh_letters_raw"].get(letter)
        corrected = report["kazakh_letters_corrected"].get(letter)
        if not raw:
            continue
        print(
            f"  {letter}:  raw {raw['present']}/{raw['expected']}"
            f"   corrected {corrected['present']}/{corrected['expected']}"
        )


def main() -> None:
    parser = argparse.ArgumentParser(description="Evaluate OCR text correction.")
    parser.add_argument(
        "--engine",
        default="synthetic-noise",
        choices=("synthetic-noise", "easyocr", "groq"),
    )
    parser.add_argument("--save-baseline", action="store_true")
    parser.add_argument("--json", action="store_true", help="Print the full report as JSON.")
    arguments = parser.parse_args()

    report = evaluate(arguments.engine)
    if arguments.json:
        print(json.dumps(report, ensure_ascii=False, indent=2))
    else:
        print_report(report)

    if arguments.save_baseline:
        summary = {key: value for key, value in report.items() if key != "rows"}
        BASELINE_PATH.write_text(
            json.dumps(summary, ensure_ascii=False, indent=2),
            encoding="utf-8",
        )
        print(f"\nBaseline written to {BASELINE_PATH}")


if __name__ == "__main__":
    main()
