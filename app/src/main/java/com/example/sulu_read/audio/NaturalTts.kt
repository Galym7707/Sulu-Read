package com.example.sulu_read.audio

import android.os.Build
import android.os.Bundle
import android.speech.tts.TextToSpeech
import android.speech.tts.Voice
import com.example.sulu_read.domain.model.AppLanguage
import java.util.Locale

private val KazakhSpecificLetters = setOf(
    'ә', 'ғ', 'қ', 'ң', 'ө', 'ұ', 'ү', 'һ', 'і',
    'Ә', 'Ғ', 'Қ', 'Ң', 'Ө', 'Ұ', 'Ү', 'Һ', 'І'
)

fun detectSpeechLanguageCode(text: String, fallbackCode: String): String {
    val normalizedFallback = AppLanguage.normalizeCode(fallbackCode)
    val letters = text.filter { it.isLetter() }
    if (letters.isBlank()) {
        return normalizedFallback
    }
    if (letters.any { it in KazakhSpecificLetters }) {
        return AppLanguage.Kazakh.code
    }
    if (letters.any { it.isLatinLetter() }) {
        return AppLanguage.English.code
    }
    if (letters.any { it.isCyrillicLetter() }) {
        return if (normalizedFallback == AppLanguage.Kazakh.code) {
            AppLanguage.Kazakh.code
        } else {
            AppLanguage.Russian.code
        }
    }
    return normalizedFallback
}

fun TextToSpeech.applyNaturalVoice(languageCode: String): Boolean {
    val locale = AppLanguage.localeFor(languageCode)
    val voice = findBestVoice(locale)
    if (voice != null && setVoice(voice) == TextToSpeech.SUCCESS) {
        return true
    }
    return setLanguage(locale) >= TextToSpeech.LANG_AVAILABLE
}

fun TextToSpeech.speakCompat(text: String, queueMode: Int, utteranceId: String) {
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
        speak(text, queueMode, Bundle(), utteranceId)
    } else {
        @Suppress("DEPRECATION")
        speak(text, queueMode, null)
    }
}

private fun TextToSpeech.findBestVoice(locale: Locale): Voice? {
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.LOLLIPOP) {
        return null
    }
    return runCatching {
        voices
            ?.asSequence()
            ?.mapNotNull { voice ->
                val score = voice.naturalVoiceScore(locale)
                if (score == Int.MIN_VALUE) null else voice to score
            }
            ?.maxByOrNull { it.second }
            ?.first
    }.getOrNull()
}

private fun Voice.naturalVoiceScore(locale: Locale): Int {
    if (features.orEmpty().contains(TextToSpeech.Engine.KEY_FEATURE_NOT_INSTALLED)) {
        return Int.MIN_VALUE
    }
    if (this.locale.language != locale.language) {
        return Int.MIN_VALUE
    }

    var score = 100
    if (this.locale.country.equals(locale.country, ignoreCase = true)) {
        score += 35
    }
    score += quality * 10
    score += when (latency) {
        Voice.LATENCY_VERY_LOW -> 12
        Voice.LATENCY_LOW -> 8
        Voice.LATENCY_NORMAL -> 4
        else -> 0
    }
    if (isNetworkConnectionRequired) {
        score += 12
    }
    return score
}

private fun Char.isLatinLetter(): Boolean {
    return this in 'A'..'Z' || this in 'a'..'z'
}

private fun Char.isCyrillicLetter(): Boolean {
    return this in '\u0400'..'\u04FF'
}
