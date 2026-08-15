package com.example.sulu_read.focus

import com.example.sulu_read.domain.model.AppLanguage

// Letter NAMES, not letter sounds. The spell step of the ladder exists because the source
// method forbids sounding letters out: «Говори только названия букв по одной».
private val RussianLetterNames: Map<Char, String> = mapOf(
    'а' to "а", 'б' to "бэ", 'в' to "вэ", 'г' to "гэ", 'д' to "дэ", 'е' to "е", 'ё' to "ё",
    'ж' to "жэ", 'з' to "зэ", 'и' to "и", 'й' to "и краткое", 'к' to "ка", 'л' to "эль",
    'м' to "эм", 'н' to "эн", 'о' to "о", 'п' to "пэ", 'р' to "эр", 'с' to "эс", 'т' to "тэ",
    'у' to "у", 'ф' to "эф", 'х' to "ха", 'ц' to "цэ", 'ч' to "че", 'ш' to "ша", 'щ' to "ща",
    'ъ' to "твёрдый знак", 'ы' to "ы", 'ь' to "мягкий знак", 'э' to "э", 'ю' to "ю", 'я' to "я"
)

/**
 * The Kazakh alphabet, named the Kazakh way.
 *
 * This has to be its own table rather than a handful of additions to the Russian one. The two
 * alphabets share all 33 Russian letters but do not name them alike: Kazakh consonants take a
 * following "е" where Russian takes "э", so т is "те" and not "тэ", б is "бе" and not "бэ". An
 * earlier version merged the tables and kept only the nine Kazakh-specific characters, on the
 * stated belief that "the three alphabets share no keys" — they share thirty-three — so every
 * Kazakh word was spelled out to the child in Russian letter names.
 *
 * Names marked below as unreviewed are the ones a Kazakh-speaking teacher should confirm against
 * a school Әліппе. They are not guesses in the sense of being unsupported, but they are not
 * verified either, and this is read aloud to a child learning the alphabet.
 */
private val KazakhLetterNames: Map<Char, String> = mapOf(
    // Vowels are named as the sound they make.
    'а' to "а", 'ә' to "ә", 'е' to "е", 'ё' to "ё", 'и' to "и", 'о' to "о", 'ө' to "ө",
    'у' to "у", 'ұ' to "ұ", 'ү' to "ү", 'ы' to "ы", 'і' to "і", 'э' to "э", 'ю' to "ю",
    'я' to "я",

    // Consonants take a following "е". This is the block that was wrong.
    'б' to "бе", 'в' to "ве", 'г' to "ге", 'д' to "де", 'ж' to "же", 'з' to "зе",
    'к' to "ке", 'п' to "пе", 'т' to "те", 'ц' to "це", 'ч' to "че",

    // Back-harmony pair of к/г, named with the back vowel to match.
    'қ' to "қа", 'ғ' to "ға",

    'х' to "ха", 'һ' to "һа", 'ш' to "ша", 'щ' to "ща",

    // UNREVIEWED. The Russian э-forms are certainly wrong for Kazakh; these are the standard
    // Kazakh forms but have not been checked against a textbook.
    'л' to "эл", 'м' to "эм", 'н' to "эн", 'р' to "эр", 'с' to "эс", 'ф' to "эф",
    'ң' to "ең",

    // UNREVIEWED. Previously these read out the whole Russian phrases "и краткое",
    // "твёрдый знак" and "мягкий знак" in the middle of a Kazakh word.
    'й' to "қысқа и", 'ъ' to "айыру белгісі", 'ь' to "жіңішкелік белгісі"
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

/**
 * Letter names for a word, in the alphabet the word belongs to.
 *
 * Latin always falls through to the English names regardless of the language asked for: a Latin
 * character has no Cyrillic name, and the alternative is handing the raw character to the
 * speech engine, which reads "a" as a word rather than as a letter.
 */
fun letterNamesFor(word: String, languageCode: String): List<String> {
    val cyrillicNames = when (AppLanguage.fromCode(languageCode)) {
        AppLanguage.Kazakh -> KazakhLetterNames
        else -> RussianLetterNames
    }

    return word
        .lowercase()
        .filter { it.isLetterOrDigit() }
        .map { character ->
            EnglishLetterNames[character]
                ?: cyrillicNames[character]
                ?: character.toString()
        }
}
