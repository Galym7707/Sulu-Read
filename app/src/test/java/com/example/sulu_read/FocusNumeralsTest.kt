package com.example.sulu_read

import com.example.sulu_read.focus.isDigits
import com.example.sulu_read.focus.numeralDigits
import com.example.sulu_read.focus.numeralValue
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class FocusNumeralsTest {
    @Test
    fun readsDigitsAsThemselves() {
        assertEquals(25, numeralValue(listOf("25")))
        assertEquals("1995", numeralDigits(listOf("1995")))
        assertEquals(7, numeralValue(listOf("7.")))
    }

    @Test
    fun readsRussianNumerals() {
        assertEquals(5, numeralValue(listOf("пять")))
        assertEquals(0, numeralValue(listOf("ноль")))
        assertEquals(10, numeralValue(listOf("десять")))
        assertEquals(17, numeralValue(listOf("семнадцать")))
        assertEquals(25, numeralValue(listOf("двадцать", "пять")))
        assertEquals(125, numeralValue(listOf("сто", "двадцать", "пять")))
        assertEquals(2026, numeralValue(listOf("две", "тысячи", "двадцать", "шесть")))
        assertEquals(1000, numeralValue(listOf("тысяча")))
        assertEquals(20000, numeralValue(listOf("двадцать", "тысяч")))
    }

    @Test
    fun readsKazakhNumerals() {
        assertEquals(5, numeralValue(listOf("бес")))
        assertEquals(11, numeralValue(listOf("он", "бір")))
        assertEquals(25, numeralValue(listOf("жиырма", "бес")))
        assertEquals(200, numeralValue(listOf("екі", "жүз")))
        assertEquals(100, numeralValue(listOf("жүз")))
        assertEquals(1995, numeralValue(listOf("бір", "мың", "тоғыз", "жүз", "тоқсан", "бес")))
        assertEquals(1005, numeralValue(listOf("мың", "бес")))
    }

    @Test
    fun readsKazakhNumeralsWrittenByARussianModeRecogniser() {
        // The engine had no ө, ү, і, қ or ғ to write, so it wrote the nearest Russian letter.
        assertEquals(4, numeralValue(listOf("торт")))
        assertEquals(3, numeralValue(listOf("уш")))
        assertEquals(1995, numeralValue(listOf("быр", "мын", "тогыз", "жуз", "токсан", "бес")))
    }

    @Test
    fun readsEnglishNumerals() {
        assertEquals(5, numeralValue(listOf("five")))
        assertEquals(15, numeralValue(listOf("fifteen")))
        assertEquals(25, numeralValue(listOf("twenty", "five")))
        // One token to the transcript splitter, two words to the parser.
        assertEquals(25, numeralValue(listOf("twenty-five")))
        assertEquals(125, numeralValue(listOf("one", "hundred", "twenty", "five")))
        assertEquals(2026, numeralValue(listOf("two", "thousand", "twenty", "six")))
    }

    @Test
    fun refusesWordsInTheWrongOrder() {
        // A child who read the parts of a number out of order did not read the number.
        assertNull(numeralValue(listOf("три", "двадцать")))
        assertNull(numeralValue(listOf("два", "три")))
        assertNull(numeralValue(listOf("пять", "сто")))
        assertNull(numeralValue(listOf("десять", "пять")))
    }

    @Test
    fun refusesThingsThatAreNotNumbers() {
        assertNull(numeralValue(listOf("книга")))
        assertNull(numeralValue(listOf("пять", "книг")))
        assertNull(numeralValue(emptyList()))
        assertNull(numeralValue(listOf("   ")))
    }

    @Test
    fun digitsCheckIsStrict() {
        assertTrue(isDigits("2026"))
        assertFalse(isDigits("20а"))
        assertFalse(isDigits(""))
    }
}
