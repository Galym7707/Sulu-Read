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

// Word-final obstruents are devoiced in both Russian and Kazakh — "дуб" leaves the mouth as
// "дуп" no matter who is speaking — so whichever of the pair the recognizer wrote down says
// nothing about whether the word was read correctly. Applied only in final position: the same
// swap at the start of a word separates real words (дом/том), which is why the plain edit
// distance below still has to see it.
private val FinalDevoiced: Map<Char, Char> = mapOf(
    'б' to 'п',
    'в' to 'ф',
    'г' to 'к',
    'д' to 'т',
    'ж' to 'ш',
    'з' to 'с'
)

// Spellings that a speaker without native English gives the same sound. These are the
// substitutions that make an accented reading come back from the recognizer as a different
// string while the reading itself was right.
private val LatinDigraphs: List<Pair<String, String>> = listOf(
    "th" to "t",
    "ph" to "f",
    "gh" to "g",
    "ck" to "k",
    "kn" to "n",
    "wr" to "r",
    "wh" to "w"
)

private val LatinEquivalents: Map<Char, Char> = mapOf(
    'w' to 'v',
    'z' to 's',
    'q' to 'k',
    'y' to 'i'
)

// "c" is the one Latin letter whose sound depends on what follows it, so it cannot go in the
// table above: mapping it to "k" everywhere would turn "city" into "kiti" and stop it matching
// a correct reading.
private val SoftCFollowers = setOf('e', 'i', 'y')

// Short words live in dense neighbourhoods — book/look/took, дом/том, men/man — so a single
// substitution there is far more likely to be a real misreading than recognizer noise, and
// letting it pass would defeat the point of the gate. Longer words have room to absorb one.
private const val SHORT_WORD_MAX_LENGTH = 4
private const val MEDIUM_WORD_MAX_LENGTH = 7
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

private fun collapseRuns(value: String): String {
    val builder = StringBuilder(value.length)
    value.forEach { character ->
        if (builder.isEmpty() || builder.last() != character) {
            builder.append(character)
        }
    }
    return builder.toString()
}

private fun isLatin(value: String): Boolean {
    return value.any { it in 'a'..'z' }
}

/**
 * Reduces a word to how it sounds rather than how it is spelled, within one script.
 *
 * The rules are deliberately script-scoped. Sharing one equivalence table across alphabets is
 * what would let a Cyrillic vowel rule quietly decide that two different English words match,
 * so a Latin word is only ever rewritten by the Latin rules and vice versa.
 *
 * Note this maps sounds, never vowel quality: "cat" and "cut" must stay distinct, because
 * telling those apart is the reading skill being trained.
 */
internal fun phoneticKey(raw: String): String {
    val folded = fold(normalizeForMatch(raw))
    if (folded.isEmpty()) {
        return ""
    }

    val rewritten = if (isLatin(folded)) {
        var value = folded
        LatinDigraphs.forEach { (from, to) -> value = value.replace(from, to) }
        value.mapIndexed { index, character ->
            when {
                character != 'c' -> LatinEquivalents[character] ?: character
                value.getOrNull(index + 1) in SoftCFollowers -> 's'
                else -> 'k'
            }
        }.joinToString("")
    } else {
        val devoicedLast = FinalDevoiced[folded.last()]
        if (devoicedLast == null) folded else folded.dropLast(1) + devoicedLast
    }

    return collapseRuns(rewritten)
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

/**
 * Every string worth testing from one recognizer hypothesis.
 *
 * A hypothesis is not reliably a single word. A reader who hesitates gets "эм книга" back, and
 * some engines prepend filler of their own, so the whole phrase is tested and so is each word
 * in it. Without this the reader is marked wrong for having paused before speaking.
 */
private fun candidatesFrom(hypothesis: String): List<String> {
    val words = hypothesis.trim().split(Regex("\\s+")).filter { it.isNotBlank() }
    return if (words.size <= 1) listOf(hypothesis) else words + hypothesis
}

/**
 * How much of a running transcript the reader has got through.
 *
 * @param wordsMatched consecutive targets read correctly, starting at the first one offered.
 * @param tokensConsumed transcript tokens accounted for, so the next pass over a longer version
 *   of the same transcript does not match the same speech twice.
 */
data class StreamMatch(val wordsMatched: Int, val tokensConsumed: Int)

/**
 * Walks a live transcript against the words still to be read.
 *
 * This exists so recognition does not have to stop at every word. One session stays open while
 * the reader reads on, and each time the engine revises its transcript the new part is matched
 * against the next target, then the one after it. A fluent reader can clear several words from a
 * single utterance, and nobody waits for a recogniser to be torn down and started again.
 *
 * Tokens that match nothing are stepped over rather than failing the read: engines insert filler
 * ("эм", "the"), and the reader's own hesitation lands in the transcript too. Matching stops at
 * the first target that is not found, so words are only ever cleared in order.
 */
fun matchSpokenStream(tokens: List<String>, targets: List<String>): StreamMatch {
    var wordsMatched = 0
    var tokensConsumed = 0
    var tokenIndex = 0

    while (wordsMatched < targets.size && tokenIndex < tokens.size) {
        val target = targets[wordsMatched]
        val hit = (tokenIndex until tokens.size).firstOrNull { candidate ->
            isSpokenWordAccepted(target, listOf(tokens[candidate]))
        } ?: break

        wordsMatched += 1
        tokenIndex = hit + 1
        tokensConsumed = tokenIndex
    }

    return StreamMatch(wordsMatched = wordsMatched, tokensConsumed = tokensConsumed)
}

fun tokenizeTranscript(transcript: String): List<String> {
    return transcript.trim().split(Regex("\\s+")).filter { it.isNotBlank() }
}

fun isSpokenWordAccepted(target: String, heardAlternatives: List<String>): Boolean {
    val foldedTarget = fold(normalizeForMatch(target))
    if (foldedTarget.isEmpty()) {
        return false
    }

    val targetPhonetic = phoneticKey(target)
    val tolerance = toleranceFor(foldedTarget.length)

    return heardAlternatives
        .flatMap { candidatesFrom(it) }
        .any { heard ->
            val foldedHeard = fold(normalizeForMatch(heard))
            when {
                foldedHeard.isEmpty() -> false
                foldedHeard == foldedTarget -> true
                // Same letters in a different order is a reading error, not recognizer noise.
                isTransposition(foldedTarget, foldedHeard) -> false
                // Identical once both sides are reduced to sound. No tolerance is allowed on
                // top of this: the phonetic key has already discarded detail, and stacking an
                // edit budget on it would start accepting genuinely different words.
                targetPhonetic.isNotEmpty() && phoneticKey(heard) == targetPhonetic -> true
                else -> editDistance(foldedTarget, foldedHeard) <= tolerance
            }
        }
}
