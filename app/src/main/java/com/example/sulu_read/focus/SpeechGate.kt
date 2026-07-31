package com.example.sulu_read.focus

import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import com.example.sulu_read.domain.model.AppLanguage

private const val MAX_HYPOTHESES = 5

/**
 * Listens for a single spoken word and returns every hypothesis the recognizer offered,
 * so the matcher can accept a word that was only the recognizer's third guess.
 *
 * Recognition is not guaranteed on every device or for every language, so callers must
 * handle [listenOnce]'s `onUnavailable` by falling back to a self-check button. The reading
 * mode has to work with no microphone at all.
 */
class SpeechGate(private val context: Context) {

    private var recognizer: SpeechRecognizer? = null

    fun listenOnce(
        languageCode: String,
        onResult: (List<String>) -> Unit,
        onUnavailable: () -> Unit
    ) {
        // Deliberately not gated on [isAvailable]. That check reports false on devices that
        // recognise speech perfectly well — a Xiaomi running Android 14 with three
        // RecognitionServices installed and Google TTS set as the system default still
        // answers false — and a false negative costs the reader the entire voice gate.
        // Try for real, and fall back only when an attempt actually fails.
        val activeRecognizer = recognizer
            ?: SpeechRecognizer.createSpeechRecognizer(context)?.also { recognizer = it }
            ?: run {
                onUnavailable()
                return
            }

        activeRecognizer.setRecognitionListener(object : RecognitionListener {
            override fun onResults(results: Bundle?) {
                val hypotheses = results
                    ?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                    ?.toList()
                    .orEmpty()
                onResult(hypotheses)
            }

            override fun onError(error: Int) {
                when (error) {
                    // Nothing was said. Not a failure, and not a wrong answer either.
                    SpeechRecognizer.ERROR_SPEECH_TIMEOUT,
                    SpeechRecognizer.ERROR_NO_MATCH -> onResult(emptyList())
                    // Transient: the previous session had not finished releasing the mic.
                    SpeechRecognizer.ERROR_RECOGNIZER_BUSY,
                    SpeechRecognizer.ERROR_CLIENT -> onResult(emptyList())
                    else -> onUnavailable()
                }
            }

            override fun onReadyForSpeech(params: Bundle?) = Unit
            override fun onBeginningOfSpeech() = Unit
            override fun onRmsChanged(rmsdB: Float) = Unit
            override fun onBufferReceived(buffer: ByteArray?) = Unit
            override fun onEndOfSpeech() = Unit
            override fun onPartialResults(partialResults: Bundle?) = Unit
            override fun onEvent(eventType: Int, params: Bundle?) = Unit
        })

        val locale = AppLanguage.localeFor(languageCode)
        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(
                RecognizerIntent.EXTRA_LANGUAGE_MODEL,
                RecognizerIntent.LANGUAGE_MODEL_FREE_FORM
            )
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, locale.toLanguageTag())
            putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, MAX_HYPOTHESES)
            putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, false)
            // Deliberately NOT EXTRA_PREFER_OFFLINE. Asking for offline recognition does not
            // fall back to the network when the language pack is missing — it fails outright
            // with a language-unavailable error, and Kazakh in particular is rarely installed
            // offline. Preferring offline would have quietly disabled the voice gate for the
            // languages this app exists to serve.
        }

        runCatching { activeRecognizer.startListening(intent) }
            .onFailure { onUnavailable() }
    }

    fun cancel() {
        runCatching { recognizer?.cancel() }
    }

    fun release() {
        runCatching { recognizer?.destroy() }
        recognizer = null
    }
}
