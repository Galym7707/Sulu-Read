package com.example.sulu_read.focus

import kotlin.math.min

// Pairs a speech recognizer and a beginning reader confuse routinely. Folding both sides
// through this map means such a swap costs nothing, while a real misreading still costs.
private val FoldedLetters: Map<Char, Char> = mapOf(
    'ё' to 'е',
    'й' to 'и',
    'ъ' to 'ь',
    'қ' to 'к',
    'ғ' to 'г',
    'ң' to 'н',
    'һ' to 'х',
    'ө' to 'о',
    'ұ' to 'у',
    'ү' to 'у',
    'ә' to 'а',
    'і' to 'ы'
)

private const val SHORT_WORD_MAX_LENGTH = 3
private const val MEDIUM_WORD_MAX_LENGTH = 6
private const val MEDIUM_WORD_TOLERANCE = 1
private const val LONG_WORD_TOLERANCE = 2

fun normalizeForMatch(raw: String): String {
    return raw
        .lowercase()
        .filter { it.isLetterOrDigit() }
}

private fun fold(normalized: String): String {
    return normalized.map { character -> FoldedLetters[character] ?: character }.joinToString("")
}

private fun toleranceFor(length: Int): Int = when {
    length <= SHORT_WORD_MAX_LENGTH -> 0
    length <= MEDIUM_WORD_MAX_LENGTH -> MEDIUM_WORD_TOLERANCE
    else -> LONG_WORD_TOLERANCE
}

private fun isTransposition(first: String, second: String): Boolean {
    return first != second && first.toList().sorted() == second.toList().sorted()
}

private fun editDistance(first: String, second: String): Int {
    var previousRow = IntArray(second.length + 1) { it }
    for (firstIndex in 1..first.length) {
        val currentRow = IntArray(second.length + 1)
        currentRow[0] = firstIndex
        for (secondIndex in 1..second.length) {
            val substitutionCost = if (first[firstIndex - 1] == second[secondIndex - 1]) 0 else 1
            currentRow[secondIndex] = min(
                min(currentRow[secondIndex - 1] + 1, previousRow[secondIndex] + 1),
                previousRow[secondIndex - 1] + substitutionCost
            )
        }
        previousRow = currentRow
    }
    return previousRow[second.length]
}

fun isSpokenWordAccepted(target: String, heardAlternatives: List<String>): Boolean {
    val foldedTarget = fold(normalizeForMatch(target))
    if (foldedTarget.isEmpty()) {
        return false
    }

    val tolerance = toleranceFor(foldedTarget.length)
    return heardAlternatives.any { heard ->
        val foldedHeard = fold(normalizeForMatch(heard))
        when {
            foldedHeard.isEmpty() -> false
            foldedHeard == foldedTarget -> true
            // Same letters in a different order is a reading error, not recognizer noise.
            isTransposition(foldedTarget, foldedHeard) -> false
            else -> editDistance(foldedTarget, foldedHeard) <= tolerance
        }
    }
}
