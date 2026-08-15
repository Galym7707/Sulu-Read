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

/**
 * What the engine could actually offer for a language.
 *
 * The previous version answered this with a Boolean, and every caller discarded it. That is why
 * Kazakh was read aloud in a Russian voice without anything anywhere noticing: the failure was
 * reported and thrown away at all four call sites.
 */
enum class VoiceResult {
    /** A voice for this language was selected. */
    Voice,

    /** No named voice, but the engine accepted the language. */
    LanguageOnly,

    /** The engine knows this language but its data is not downloaded. The user can fix this. */
    MissingData,

    /** This engine cannot speak this language at all. A different engine is needed. */
    NotSupported
}

fun TextToSpeech.applyNaturalVoice(languageCode: String): VoiceResult {
    val locale = AppLanguage.localeFor(languageCode)
    val voice = findBestVoice(locale)
    if (voice != null && setVoice(voice) == TextToSpeech.SUCCESS) {
        return VoiceResult.Voice
    }
    return when (setLanguage(locale)) {
        TextToSpeech.LANG_MISSING_DATA -> VoiceResult.MissingData
        TextToSpeech.LANG_NOT_SUPPORTED -> VoiceResult.NotSupported
        else -> VoiceResult.LanguageOnly
    }
}

/**
 * Whether this engine can speak a language, without changing what it is currently set to.
 *
 * Used to tell the reader the truth before they press play, rather than after they have listened
 * to a Russian voice mispronouncing Kazakh.
 */
fun TextToSpeech.canSpeak(languageCode: String): Boolean {
    val locale = AppLanguage.localeFor(languageCode)
    return runCatching { isLanguageAvailable(locale) }.getOrDefault(TextToSpeech.LANG_NOT_SUPPORTED) >=
        TextToSpeech.LANG_AVAILABLE
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
    if (!this.locale.isSameLanguageAs(locale)) {
        return Int.MIN_VALUE
    }

    // Ordered, not weighted. The previous version added `quality * 10`, which is 1000 to 5000,
    // against an offline penalty of 12 — so a network-only voice one quality tier up always won,
    // the exact opposite of what the comment below asks for. Each term here outranks every term
    // beneath it.
    var score = 0

    // A network voice is the one that goes silent on a school bus or in a village with no
    // signal — exactly where this app is used.
    if (!isNetworkConnectionRequired) {
        score += 100_000
    }
    if (this.locale.country.equals(locale.country, ignoreCase = true)) {
        score += 10_000
    }
    // Voice.QUALITY_* run 100..500.
    score += quality
    score += when (latency) {
        Voice.LATENCY_VERY_LOW -> 3
        Voice.LATENCY_LOW -> 2
        Voice.LATENCY_NORMAL -> 1
        else -> 0
    }
    return score
}

/**
 * Whether two locales name the same language, across ISO-639 forms.
 *
 * Engines are inconsistent here: many report a voice's language as "kaz", "rus" or "eng"
 * (ISO 639-2) while [Locale.getLanguage] for the app's own locales gives "kk", "ru", "en"
 * (ISO 639-1). Locale does not reconcile the two, so a plain comparison can reject every voice
 * the engine has and silently fall through to setLanguage.
 */
private fun Locale.isSameLanguageAs(other: Locale): Boolean {
    if (language.equals(other.language, ignoreCase = true)) {
        return true
    }
    val mine = runCatching { isO3Language }.getOrNull().orEmpty()
    val theirs = runCatching { other.isO3Language }.getOrNull().orEmpty()
    return mine.isNotEmpty() && mine.equals(theirs, ignoreCase = true)
}

private fun Char.isLatinLetter(): Boolean {
    return this in 'A'..'Z' || this in 'a'..'z'
}

private fun Char.isCyrillicLetter(): Boolean {
    return this in '\u0400'..'\u04FF'
}
