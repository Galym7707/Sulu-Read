package com.example.sulu_read.focus

/** What the analyser concluded about one word the reader moved past. */
enum class ReadOutcome { Correct, Misread, Silent }

data class WordReview(
    val word: String,
    /** The transcript token that landed on this word, or null when nothing did. */
    val heard: String?,
    val outcome: ReadOutcome
)

// A wrong word costs less than skipping the word and skipping the token separately, so a token
// that lands near a target is reported as a misreading of it rather than as two unrelated
// errors. That is the difference between "you said кинга here" and "you skipped a word and then
// said something extra", and only the first is useful to the reader.
private const val SUBSTITUTION_COST = 3
private const val SKIP_COST = 2

// ...but only for a token that could be that word. Charging every mismatch the same 3 made the
// table prefer pairing ANY leftover token with ANY leftover word, because 3 always beats the 4
// it costs to drop them separately — so one word of engine filler was reported as a misreading
// of a word the reader had not even reached. Anything that resembles nothing on the page costs
// more than dropping both, which is how filler ends up stepped over and the word stays unheard.
private const val UNRELATED_COST = 2 * SKIP_COST + 1

private const val MOVE_MATCH: Byte = 0
private const val MOVE_SUBSTITUTE: Byte = 1
private const val MOVE_SKIP_TARGET: Byte = 2
private const val MOVE_SKIP_TOKEN: Byte = 3

// A number can span several words on either side: "25" on the page is "двадцать пять" from the
// microphone, and "двадцать пять" on the page comes back from most engines as "25". Six words is
// enough for "бір мың тоғыз жүз тоқсан бес" — 1995 in Kazakh — which is as long as a school
// number gets. A span move is encoded above the plain moves as base + targetSpan * 8 + tokenSpan.
private const val MAX_NUMERAL_SPAN = 6
private const val MOVE_NUMERAL_BASE = 16

/**
 * Lines a whole session's transcript up against the words the reader walked through, and says
 * which words came out wrong.
 *
 * This is deliberately done once at the end rather than word by word while reading. The reader
 * moves the focus themselves now, and a recogniser answers a few hundred milliseconds after the
 * mouth does, so attributing each token to whichever word happened to be focused at that instant
 * mislabels every word the reader passed quickly. Aligning the two sequences afterwards has no
 * such race: a word skipped in silence stays a skipped word instead of shifting every later word
 * onto the wrong token.
 *
 * Filler the engine invents ("эм", "the") and the reader's own false starts are stepped over as
 * extra tokens rather than counted against a word.
 *
 * Numbers are aligned as numbers. A run of up to [MAX_NUMERAL_SPAN] words on one side may pair
 * with a run on the other when both spell the same value and one side is written in digits —
 * the digit side is what a plain word comparison could never match, so it is the only case that
 * needs this. Before this, targets written in digits were dropped from the review altogether and
 * a child who read them correctly was never credited.
 *
 * ponytail: plain O(targets × tokens) alignment, sized for one page of text. If focus mode ever
 * runs over something book-length, band it (Ukkonen) instead of widening the table.
 */
fun reviewReading(spokenTokens: List<String>, targets: List<String>): List<WordReview> {
    if (targets.isEmpty() || spokenTokens.isEmpty()) {
        return emptyList()
    }

    val targetCount = targets.size
    val tokenCount = spokenTokens.size
    val width = tokenCount + 1
    // The whole table is kept rather than one row: a numeral span reaches back several rows.
    val cost = IntArray((targetCount + 1) * width)
    val moves = ByteArray((targetCount + 1) * width)

    for (tokenIndex in 1..tokenCount) {
        cost[tokenIndex] = tokenIndex * SKIP_COST
        moves[tokenIndex] = MOVE_SKIP_TOKEN
    }

    for (targetIndex in 1..targetCount) {
        cost[targetIndex * width] = targetIndex * SKIP_COST
        moves[targetIndex * width] = MOVE_SKIP_TARGET

        for (tokenIndex in 1..tokenCount) {
            val target = targets[targetIndex - 1]
            val token = spokenTokens[tokenIndex - 1]
            val accepted = isSpokenWordAccepted(target, listOf(token))
            val pairingCost = when {
                accepted -> 0
                isPlausibleMisreading(target, token) -> SUBSTITUTION_COST
                else -> UNRELATED_COST
            }
            val diagonal = cost[(targetIndex - 1) * width + (tokenIndex - 1)] + pairingCost
            val skipTarget = cost[(targetIndex - 1) * width + tokenIndex] + SKIP_COST
            val skipToken = cost[targetIndex * width + (tokenIndex - 1)] + SKIP_COST
            var best = minOf(diagonal, skipTarget, skipToken)

            // Ties go to the skips whenever the pairing is between a word and a token that have
            // nothing in common: every branch that equals `best` is equally optimal, so the tie
            // is free to be broken in favour of the answer that does not accuse the reader.
            var move: Byte = when {
                best == diagonal && accepted -> MOVE_MATCH
                best == diagonal && pairingCost == SUBSTITUTION_COST -> MOVE_SUBSTITUTE
                best == skipTarget -> MOVE_SKIP_TARGET
                best == skipToken -> MOVE_SKIP_TOKEN
                else -> MOVE_SUBSTITUTE
            }

            // The digit side of a numeral pairing is always a single element and always sits at
            // the end of its run, which is this cell — so a cell with no digits on either side
            // cannot end a span and is skipped without building any.
            if (isDigits(normalizeForMatch(target)) || isDigits(normalizeForMatch(token))) {
                for (targetSpan in 1..minOf(MAX_NUMERAL_SPAN, targetIndex)) {
                    for (tokenSpan in 1..minOf(MAX_NUMERAL_SPAN, tokenIndex)) {
                        if (targetSpan == 1 && tokenSpan == 1) {
                            continue
                        }
                        val targetRun = targets.subList(targetIndex - targetSpan, targetIndex)
                        val tokenRun = spokenTokens.subList(tokenIndex - tokenSpan, tokenIndex)
                        val hasDigitSide =
                            (targetSpan == 1 && isDigits(normalizeForMatch(targetRun[0]))) ||
                                (tokenSpan == 1 && isDigits(normalizeForMatch(tokenRun[0])))
                        if (!hasDigitSide) {
                            continue
                        }
                        val targetNumber = numeralDigits(targetRun) ?: continue
                        val tokenNumber = numeralDigits(tokenRun) ?: continue
                        if (targetNumber != tokenNumber) {
                            continue
                        }
                        val candidate = cost[(targetIndex - targetSpan) * width + (tokenIndex - tokenSpan)]
                        if (candidate < best) {
                            best = candidate
                            move = (MOVE_NUMERAL_BASE + targetSpan * 8 + tokenSpan).toByte()
                        }
                    }
                }
            }

            cost[targetIndex * width + tokenIndex] = best
            moves[targetIndex * width + tokenIndex] = move
        }
    }

    val reviews = arrayOfNulls<WordReview>(targetCount)
    var targetIndex = targetCount
    var tokenIndex = tokenCount
    while (targetIndex > 0) {
        val move = moves[targetIndex * width + tokenIndex].toInt()
        if (move >= MOVE_NUMERAL_BASE) {
            val targetSpan = (move - MOVE_NUMERAL_BASE) / 8
            val tokenSpan = (move - MOVE_NUMERAL_BASE) % 8
            val heard = spokenTokens.subList(tokenIndex - tokenSpan, tokenIndex).joinToString(" ")
            for (index in (targetIndex - targetSpan) until targetIndex) {
                reviews[index] = WordReview(word = targets[index], heard = heard, outcome = ReadOutcome.Correct)
            }
            targetIndex -= targetSpan
            tokenIndex -= tokenSpan
            continue
        }

        when (move.toByte()) {
            MOVE_MATCH, MOVE_SUBSTITUTE -> {
                reviews[targetIndex - 1] = WordReview(
                    word = targets[targetIndex - 1],
                    heard = spokenTokens[tokenIndex - 1],
                    outcome = if (move.toByte() == MOVE_MATCH) ReadOutcome.Correct else ReadOutcome.Misread
                )
                targetIndex -= 1
                tokenIndex -= 1
            }

            MOVE_SKIP_TARGET -> {
                reviews[targetIndex - 1] = WordReview(
                    word = targets[targetIndex - 1],
                    heard = null,
                    outcome = ReadOutcome.Silent
                )
                targetIndex -= 1
            }

            else -> tokenIndex -= 1
        }
    }

    return reviews.filterNotNull()
}

/**
 * The words worth showing the reader afterwards, in reading order and without repeats.
 *
 * A word read wrong twice is one thing to practise, not two lines on a results screen. A word
 * read wrong and then read right is not a mistake at all: each word is judged by the last
 * attempt at it, so a reader who goes back and fixes something sees it disappear from the list
 * rather than being told off for the attempt they already corrected.
 */
fun mistakesFrom(reviews: List<WordReview>): List<WordReview> {
    val lastAttempts = reviews
        .groupBy { normalizeForMatch(it.word) }
        .mapValues { (_, attempts) -> attempts.last() }
    return reviews
        .distinctBy { normalizeForMatch(it.word) }
        .mapNotNull { lastAttempts[normalizeForMatch(it.word)] }
        .filter { it.outcome != ReadOutcome.Correct }
}
