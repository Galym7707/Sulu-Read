package com.example.sulu_read.focus

import kotlin.math.min

// Pairs a speech recognizer and a beginning reader confuse routinely. Folding both sides
// through this map means such a swap costs nothing, while a real misreading still costs.
private val SharedFolds: Map<Char, Char> = mapOf(
    'ё' to 'е',
    'й' to 'и',
    'ъ' to 'ь'
)

// Kazakh letters whose nearest Russian neighbour the recognizer writes when it is transcribing
// in Russian. Near-allophonic: қ and ғ occur only in back-harmony words, к and г only in front-
// harmony ones, so the pair is fixed by the rest of the word and confusing them cannot make one
// real word into another. Safe to fold whenever the target is Kazakh.
private val KazakhConsonantFolds: Map<Char, Char> = mapOf(
    'қ' to 'к',
    'ғ' to 'г',
    'ң' to 'н',
    'һ' to 'х'
)

/**
 * Kazakh vowels that Russian does not have, and і.
 *
 * These are phonemic: күн and құн, түс and тұс, сөз and соз, тіс and тыс, ол and өл are
 * different words. Folding them unconditionally — which is what this file used to do — made the
 * gate accept every one of those as a correct reading of the other, so the gate was strict for
 * Russian and wide open for Kazakh, the exact inversion of what it is for.
 *
 * They are folded only when the recogniser has demonstrably not been transcribing in Kazakh: see
 * [isRussianModeTranscript]. If it wrote a Kazakh letter anywhere, it could have written this
 * distinction too, so a mismatch is evidence of a real misreading and has to cost.
 */
private val KazakhVowelFolds: Map<Char, Char> = mapOf(
    'ө' to 'о',
    'ұ' to 'у',
    'ү' to 'у',
    'ә' to 'а',
    'і' to 'ы'
)

private val KazakhSpecificLetters: Set<Char> =
    (KazakhConsonantFolds.keys + KazakhVowelFolds.keys).toSet()

/**
 * True when a Kazakh word came back written in an alphabet that cannot express it.
 *
 * The recogniser is often running a Russian model — for a Kazakh word made only of shared
 * letters, or when the language could not be set — and then it has no way to write ә, ө, ұ, ү or
 * і at all. In that case a missing Kazakh vowel is the transcriber's limit, not the child's
 * mistake, and folding is right. When the transcript does contain Kazakh letters, it is not.
 */
private fun isRussianModeTranscript(target: String, heard: String): Boolean {
    return target.any { it in KazakhSpecificLetters } && heard.none { it in KazakhSpecificLetters }
}

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

// Compiled once. Both call sites below run inside the reading review's per-cell alignment loop,
// where building a fresh Regex meant a Pattern.compile for every target/token pair on the page.
private val WHITESPACE = Regex("\\s+")

fun normalizeForMatch(raw: String): String {
    return raw
        .lowercase()
        .filter { it.isLetterOrDigit() }
}

/**
 * @param foldKazakhVowels fold ә, ө, ұ, ү and і to their nearest Russian vowel. Only correct
 *   when the recogniser could not have written them — see [isRussianModeTranscript].
 */
private fun fold(normalized: String, foldKazakhVowels: Boolean): String {
    return normalized
        .map { character ->
            SharedFolds[character]
                ?: KazakhConsonantFolds[character]
                ?: (if (foldKazakhVowels) KazakhVowelFolds[character] else null)
                ?: character
        }
        .joinToString("")
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
internal fun phoneticKey(raw: String, foldKazakhVowels: Boolean = false): String {
    val folded = fold(normalizeForMatch(raw), foldKazakhVowels)
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
    val words = hypothesis.trim().split(WHITESPACE).filter { it.isNotBlank() }
    return if (words.size <= 1) listOf(hypothesis) else words + hypothesis
}

/**
 * Whether a heard token is close enough to a target to be worth reporting as a misreading of it.
 *
 * The reading review has to choose, for every token it cannot match, between "this is how the
 * reader said that word" and "this is not the reader reading at all". Only the first is worth
 * showing them. Half the length of the longer side is a deliberately loose budget: a misreading
 * is allowed to be quite wrong and still be a misreading of *that* word, while engine filler and
 * room noise — which share almost nothing with the word on the page — fall outside it.
 */
internal fun isPlausibleMisreading(target: String, heard: String): Boolean {
    val normalizedTarget = normalizeForMatch(target)
    val normalizedHeard = normalizeForMatch(heard)
    if (normalizedTarget.isEmpty() || normalizedHeard.isEmpty()) {
        return false
    }
    // Two different numbers are a misreading of each other, not two unrelated things. On
    // letters alone "5" and "шесть" share nothing, so the alignment would rather report the
    // number as never heard than as read wrong - and only the second tells the reader anything.
    if (isDigits(normalizedTarget) || isDigits(normalizedHeard)) {
        if (numeralDigits(listOf(target)) != null && numeralDigits(listOf(heard)) != null) {
            return true
        }
    }
    val budget = maxOf(normalizedTarget.length, normalizedHeard.length) / 2
    return editDistance(normalizedTarget, normalizedHeard) <= budget
}

fun tokenizeTranscript(transcript: String): List<String> {
    return transcript.trim().split(WHITESPACE).filter { it.isNotBlank() }
}

fun isSpokenWordAccepted(target: String, heardAlternatives: List<String>): Boolean {
    val normalizedTarget = normalizeForMatch(target)
    if (fold(normalizedTarget, foldKazakhVowels = false).isEmpty()) {
        return false
    }

    return heardAlternatives
        .flatMap { candidatesFrom(it) }
        .any { heard ->
            val normalizedHeard = normalizeForMatch(heard)

            // A number is compared as a number, not as letters: "5" and "пять" are the same
            // reading. Only when one side is written in digits, though. Two words are left to the
            // letter rules below — otherwise "он" (he) and "он" (Kazakh ten) would be judged by
            // their numeric value rather than by whether the child said the word on the page.
            if (isDigits(normalizedTarget) || isDigits(normalizedHeard)) {
                val targetNumber = numeralDigits(listOf(target))
                val heardNumber = numeralDigits(listOf(heard))
                if (targetNumber != null && heardNumber != null) {
                    return@any targetNumber == heardNumber
                }
            }

            // Decided per pair, not once for the word: whether folding the Kazakh vowels away is
            // fair depends on what this particular transcript was able to write.
            val foldVowels = isRussianModeTranscript(normalizedTarget, normalizedHeard)
            val foldedTarget = fold(normalizedTarget, foldVowels)
            val foldedHeard = fold(normalizedHeard, foldVowels)
            val tolerance = toleranceFor(foldedTarget.length)

            when {
                foldedHeard.isEmpty() -> false
                foldedTarget.isEmpty() -> false
                foldedHeard == foldedTarget -> true
                // Same letters in a different order is a reading error, not recognizer noise.
                isTransposition(foldedTarget, foldedHeard) -> false
                // Identical once both sides are reduced to sound. No tolerance is allowed on
                // top of this: the phonetic key has already discarded detail, and stacking an
                // edit budget on it would start accepting genuinely different words.
                phoneticKey(target, foldVowels).let {
                    it.isNotEmpty() && phoneticKey(heard, foldVowels) == it
                } -> true
                else -> editDistance(foldedTarget, foldedHeard) <= tolerance
            }
        }
}
