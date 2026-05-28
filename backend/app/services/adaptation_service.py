from .syllabification import adapt_text, extract_word_features, prepare_text_for_adaptation


def build_adaptation_payload(*, source: str, text: str, title: str | None, max_text_chars: int) -> dict:
    normalized_text = prepare_text_for_adaptation(text, source=source)
    truncated = len(normalized_text) > max_text_chars
    if truncated:
        normalized_text = normalized_text[:max_text_chars].rstrip()

    words = extract_word_features(normalized_text)
    return {
        "status": "success",
        "source": source,
        "title": title,
        "original_text": normalized_text,
        "adapted_text": adapt_text(normalized_text),
        "word_count": len(words),
        "unique_word_count": len({word.original.lower() for word in words}),
        "truncated": truncated,
        "words": [
            {
                "original": word.original,
                "adapted": word.adapted,
                "syllables": word.syllables,
                "language_hint": word.language_hint,
                "vowel_harmony": word.vowel_harmony,
            }
            for word in words
        ],
    }
