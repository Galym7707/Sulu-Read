package com.example.sulu_read.focus

// Letter NAMES, not letter sounds. The spell step of the ladder exists because the source
// method forbids sounding letters out: «Говори только названия букв по одной».
private val RussianLetterNames: Map<Char, String> = mapOf(
    'а' to "а", 'б' to "бэ", 'в' to "вэ", 'г' to "гэ", 'д' to "дэ", 'е' to "е", 'ё' to "ё",
    'ж' to "жэ", 'з' to "зэ", 'и' to "и", 'й' to "и краткое", 'к' to "ка", 'л' to "эль",
    'м' to "эм", 'н' to "эн", 'о' to "о", 'п' to "пэ", 'р' to "эр", 'с' to "эс", 'т' to "тэ",
    'у' to "у", 'ф' to "эф", 'х' to "ха", 'ц' to "цэ", 'ч' to "че", 'ш' to "ша", 'щ' to "ща",
    'ъ' to "твёрдый знак", 'ы' to "ы", 'ь' to "мягкий знак", 'э' to "э", 'ю' to "ю", 'я' to "я"
)

// ponytail: project data, NOT taken from any provided source — the sources never spell out
// Kazakh letter names. Needs a native-speaker / school-textbook review before release;
// the vault records this as an open question (docs/llm-wiki/wiki/lint-report.md).
private val KazakhOnlyLetterNames: Map<Char, String> = mapOf(
    'ә' to "ә", 'ғ' to "ғе", 'қ' to "қа", 'ң' to "ың", 'ө' to "ө",
    'ұ' to "ұ", 'ү' to "ү", 'һ' to "һә", 'і' to "і"
)

// Without these the English UI had no letter names at all: every Latin character fell through
// to the raw character, so the spell step handed the TTS engine "a", which an English voice
// reads as the word "uh" rather than as the name of the letter.
private val EnglishLetterNames: Map<Char, String> = mapOf(
    'a' to "ay", 'b' to "bee", 'c' to "see", 'd' to "dee", 'e' to "ee", 'f' to "ef",
    'g' to "gee", 'h' to "aitch", 'i' to "eye", 'j' to "jay", 'k' to "kay", 'l' to "el",
    'm' to "em", 'n' to "en", 'o' to "oh", 'p' to "pee", 'q' to "cue", 'r' to "ar",
    's' to "ess", 't' to "tee", 'u' to "you", 'v' to "vee", 'w' to "double-u", 'x' to "ex",
    'y' to "why", 'z' to "zee"
)

// One table, looked up per character instead of per UI language. The three alphabets share no
// keys, and it is the letter in front of the reader — not the menu they picked — that decides
// how it is named. Selecting the table by UI language meant a Kazakh word inside a Russian
// text spelled out as "қ" instead of "қа", and an English text spelled out as nothing at all.
private val LetterNames: Map<Char, String> =
    RussianLetterNames + KazakhOnlyLetterNames + EnglishLetterNames

fun letterNamesFor(word: String): List<String> {
    return word
        .lowercase()
        .filter { it.isLetterOrDigit() }
        .map { character -> LetterNames[character] ?: character.toString() }
}
