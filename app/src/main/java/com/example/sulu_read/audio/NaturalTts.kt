package com.example.sulu_read.audio

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
    // A Kazakh-only letter is decisive: no Russian or English word contains one.
    if (letters.any { it in KazakhSpecificLetters }) {
        return AppLanguage.Kazakh.code
    }

    // Scripts are weighed against each other rather than tested with `any`. Testing Latin first
    // meant a single stray Latin character — a unit, an abbreviation, a brand name inside an
    // otherwise Cyrillic sentence — handed the whole sentence to the English voice, which then
    // read Russian aloud as if it were English.
    val latinCount = letters.count { it.isLatinLetter() }
    val cyrillicCount = letters.count { it.isCyrillicLetter() }
    return when {
        latinCount > cyrillicCount -> AppLanguage.English.code
        cyrillicCount > latinCount -> if (normalizedFallback == AppLanguage.Kazakh.code) {
            AppLanguage.Kazakh.code
        } else {
            AppLanguage.Russian.code
        }
        // Neither script present, or a genuine tie: the reader's own setting decides.
        else -> normalizedFallback
    }
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
    // minSdk is 24, so the pre-Lollipop branch this used to carry was unreachable — and it
    // called the overload that takes no utterance id, which would have left the reader's
    // "is the app speaking?" flag stuck on, because no progress callback can fire without one.
    speak(text, queueMode, Bundle(), utteranceId)
}

private fun TextToSpeech.findBestVoice(locale: Locale): Voice? {
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
    // A network voice is the one that goes silent on a school bus or in a village with no
    // signal — exactly where this app is used. Rewarding it meant the best-scoring voice was
    // routinely the one that could not speak at all offline.
    if (isNetworkConnectionRequired) {
        score -= 12
    }
    return score
}

private fun Char.isLatinLetter(): Boolean {
    return this in 'A'..'Z' || this in 'a'..'z'
}

private fun Char.isCyrillicLetter(): Boolean {
    return this in '\u0400'..'\u04FF'
}
