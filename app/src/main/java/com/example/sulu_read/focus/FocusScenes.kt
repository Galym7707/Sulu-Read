package com.example.sulu_read.focus

data class FocusWord(
    val display: String,
    val spoken: String,
    val sceneIndex: Int
)

private const val SENTENCE_END_CHARACTERS = ".!?…:;"
private const val COMMA_CHARACTERS = ","

// A scene is what the reader is asked to picture. Breaking on every comma would produce
// two-word scenes with nothing to picture, so a comma only ends a scene once it has body.
private const val MIN_WORDS_BEFORE_COMMA_BREAK = 6

fun buildFocusWords(text: String): List<FocusWord> {
    val focusWords = mutableListOf<FocusWord>()
    var sceneIndex = 0
    var wordsInScene = 0

    for (token in text.split(Regex("\\s+"))) {
        val display = token.trim()
        if (display.isEmpty() || display.none { it.isLetterOrDigit() }) {
            continue
        }

        val spoken = display.trim { !it.isLetterOrDigit() }
        focusWords += FocusWord(
            display = display,
            spoken = spoken,
            sceneIndex = sceneIndex
        )
        wordsInScene += 1

        val lastCharacter = display.last()
        val endsScene = lastCharacter in SENTENCE_END_CHARACTERS ||
            (lastCharacter in COMMA_CHARACTERS && wordsInScene >= MIN_WORDS_BEFORE_COMMA_BREAK)
        if (endsScene) {
            sceneIndex += 1
            wordsInScene = 0
        }
    }

    return focusWords
}

fun sceneCount(words: List<FocusWord>): Int {
    return words.map { it.sceneIndex }.distinct().size
}
