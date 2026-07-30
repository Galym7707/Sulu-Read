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

fun letterNamesFor(word: String, languageCode: String): List<String> {
    val names = if (languageCode == "kk") RussianLetterNames + KazakhOnlyLetterNames else RussianLetterNames
    return word
        .lowercase()
        .filter { it.isLetterOrDigit() }
        .map { character -> names[character] ?: character.toString() }
}
