package com.example.sulu_read.focus

/**
 * Reads a number the way a child says it, so "5" and "пять" can be told to be the same thing.
 *
 * A recogniser transcribes speech as words, and sometimes as digits, and a textbook writes
 * numbers either way too. Until this existed, a target written in digits could never match what
 * came back from the microphone — so numbers were simply dropped from the reading review, and a
 * child who read "25" perfectly was never credited for it. This turns either spelling into one
 * canonical digit string, in any of the three languages at once, so the comparison happens on the
 * number rather than on the letters.
 *
 * Cardinals only, 0 to 999 999, which covers page numbers, years, counts and prices — the numbers
 * a school text actually contains. Ordinals ("пятый", "бесінші") are ordinary words and go
 * through the ordinary matcher.
 */

private enum class Kind { ZERO, UNIT, TEEN, TENS, HUNDREDS, MUL_HUNDRED, MUL_THOUSAND }

private class Numeral(val value: Int, val kind: Kind)

// The Kazakh letters the recogniser cannot write when it is running a Russian model, mapped to
// what it writes instead. Every Kazakh numeral is entered under both spellings, so "төрт" and
// "торт" both read as 4. This is the same fold WordMatch applies, kept local because the tables
// there are private to that file and the numeral list is short enough to carry its own copy.
private val KazakhLookalikes: Map<Char, Char> = mapOf(
    'ә' to 'а', 'ө' to 'о', 'ұ' to 'у', 'ү' to 'у', 'і' to 'ы',
    'қ' to 'к', 'ғ' to 'г', 'ң' to 'н', 'һ' to 'х'
)

private fun withKazakhAliases(words: Map<String, Numeral>): Map<String, Numeral> {
    val out = words.toMutableMap()
    for ((word, numeral) in words) {
        val folded = word.map { KazakhLookalikes[it] ?: it }.joinToString("")
        if (folded != word) {
            out.putIfAbsent(folded, numeral)
        }
    }
    return out
}

private val Words: Map<String, Numeral> = withKazakhAliases(
    buildMap {
        fun put(kind: Kind, vararg entries: Pair<String, Int>) {
            entries.forEach { (word, value) -> put(word, Numeral(value, kind)) }
        }

        // Russian. "десять" is a TEEN: nothing follows it the way a unit follows "двадцать".
        put(Kind.ZERO, "ноль" to 0)
        put(
            Kind.UNIT, "один" to 1, "одна" to 1, "одно" to 1, "два" to 2, "две" to 2, "три" to 3,
            "четыре" to 4, "пять" to 5, "шесть" to 6, "семь" to 7, "восемь" to 8, "девять" to 9
        )
        put(
            Kind.TEEN, "десять" to 10, "одиннадцать" to 11, "двенадцать" to 12,
            "тринадцать" to 13, "четырнадцать" to 14, "пятнадцать" to 15, "шестнадцать" to 16,
            "семнадцать" to 17, "восемнадцать" to 18, "девятнадцать" to 19
        )
        put(
            Kind.TENS, "двадцать" to 20, "тридцать" to 30, "сорок" to 40, "пятьдесят" to 50,
            "шестьдесят" to 60, "семьдесят" to 70, "восемьдесят" to 80, "девяносто" to 90
        )
        put(
            Kind.HUNDREDS, "сто" to 100, "двести" to 200, "триста" to 300, "четыреста" to 400,
            "пятьсот" to 500, "шестьсот" to 600, "семьсот" to 700, "восемьсот" to 800,
            "девятьсот" to 900
        )
        put(Kind.MUL_THOUSAND, "тысяча" to 1000, "тысячи" to 1000, "тысяч" to 1000)

        // Kazakh. "он" is TENS, not TEEN: eleven is "он бір", ten-and-one, so a unit may follow.
        // Hundreds and thousands are multipliers: "екі жүз", "бір мың".
        put(Kind.ZERO, "нөл" to 0)
        put(
            Kind.UNIT, "бір" to 1, "екі" to 2, "үш" to 3, "төрт" to 4, "бес" to 5, "алты" to 6,
            "жеті" to 7, "сегіз" to 8, "тоғыз" to 9
        )
        put(
            Kind.TENS, "он" to 10, "жиырма" to 20, "отыз" to 30, "қырық" to 40, "елу" to 50,
            "алпыс" to 60, "жетпіс" to 70, "сексен" to 80, "тоқсан" to 90
        )
        put(Kind.MUL_HUNDRED, "жүз" to 100)
        put(Kind.MUL_THOUSAND, "мың" to 1000)

        // English.
        put(Kind.ZERO, "zero" to 0)
        put(
            Kind.UNIT, "one" to 1, "two" to 2, "three" to 3, "four" to 4, "five" to 5,
            "six" to 6, "seven" to 7, "eight" to 8, "nine" to 9
        )
        put(
            Kind.TEEN, "ten" to 10, "eleven" to 11, "twelve" to 12, "thirteen" to 13,
            "fourteen" to 14, "fifteen" to 15, "sixteen" to 16, "seventeen" to 17,
            "eighteen" to 18, "nineteen" to 19
        )
        put(
            Kind.TENS, "twenty" to 20, "thirty" to 30, "forty" to 40, "fifty" to 50,
            "sixty" to 60, "seventy" to 70, "eighty" to 80, "ninety" to 90
        )
        put(Kind.MUL_HUNDRED, "hundred" to 100)
        put(Kind.MUL_THOUSAND, "thousand" to 1000)
    }
)

private val PieceSeparators = charArrayOf(' ', '-', '‑', '–', '—')

fun isDigits(value: String): Boolean = value.isNotEmpty() && value.all { it.isDigit() }

/**
 * The words of a numeral, one per element, however they arrived.
 *
 * Splits on hyphens as well as whitespace, because "twenty-five" is one token to the transcript
 * splitter and two words to the parser — and normalizeForMatch would otherwise glue it into
 * "twentyfive", which is in no table.
 */
private fun numeralPieces(tokens: List<String>): List<String> {
    return tokens
        .flatMap { it.split(*PieceSeparators) }
        .map { normalizeForMatch(it) }
        .filter { it.isNotEmpty() }
}

private enum class State { Start, Unit, Hundreds, Tens, Closed }

/**
 * The number these words spell, or null if they do not spell one.
 *
 * Strict about order: "двадцать три" is 23, but "три двадцать" is two numbers and "два три" is
 * two numbers, and both return null. Loosening this would let a child who read the digits of a
 * number in the wrong order be marked correct, which is the mistake a reading gate exists to
 * catch.
 */
fun numeralValue(tokens: List<String>): Int? {
    val pieces = numeralPieces(tokens)
    if (pieces.isEmpty()) {
        return null
    }
    if (pieces.size == 1 && isDigits(pieces[0])) {
        return pieces[0].toIntOrNull()
    }

    // A group is everything under a thousand; a thousand-word multiplies the group before it.
    var total = 0
    var group = 0
    var hundreds = 0
    var pendingUnit = 0
    var state = State.Start
    var thousandsUsed = false

    for (piece in pieces) {
        val numeral = Words[piece] ?: return null
        when (numeral.kind) {
            Kind.ZERO -> return if (pieces.size == 1) 0 else null

            Kind.UNIT -> when (state) {
                State.Start -> {
                    pendingUnit = numeral.value
                    state = State.Unit
                }
                State.Hundreds, State.Tens -> {
                    group += numeral.value
                    state = State.Closed
                }
                else -> return null
            }

            Kind.TEEN -> when (state) {
                State.Start, State.Hundreds -> {
                    group += numeral.value
                    state = State.Closed
                }
                else -> return null
            }

            Kind.TENS -> when (state) {
                State.Start, State.Hundreds -> {
                    group += numeral.value
                    state = State.Tens
                }
                else -> return null
            }

            Kind.HUNDREDS -> when (state) {
                State.Start -> {
                    hundreds = numeral.value
                    state = State.Hundreds
                }
                else -> return null
            }

            Kind.MUL_HUNDRED -> when (state) {
                // "жүз" alone is 100; "екі жүз" is 200.
                State.Start -> {
                    hundreds = 100
                    state = State.Hundreds
                }
                State.Unit -> {
                    hundreds = pendingUnit * 100
                    pendingUnit = 0
                    state = State.Hundreds
                }
                else -> return null
            }

            Kind.MUL_THOUSAND -> {
                if (thousandsUsed) {
                    return null
                }
                // "тысяча" alone is 1000; anything already gathered multiplies it.
                val multiplier = when (state) {
                    State.Start -> 1
                    State.Unit -> pendingUnit
                    else -> hundreds + group
                }
                total += multiplier * 1000
                group = 0
                hundreds = 0
                pendingUnit = 0
                state = State.Start
                thousandsUsed = true
            }
        }
    }

    // A unit still pending is the whole group: "пять" on its own, or "мың бес".
    return total + hundreds + group + pendingUnit
}

/** [numeralValue] as the canonical digit string, or null. */
fun numeralDigits(tokens: List<String>): String? = numeralValue(tokens)?.toString()
