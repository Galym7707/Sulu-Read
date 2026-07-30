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

    val isAvailable: Boolean
        get() = SpeechRecognizer.isRecognitionAvailable(context)

    fun listenOnce(
        languageCode: String,
        onResult: (List<String>) -> Unit,
        onUnavailable: () -> Unit
    ) {
        if (!isAvailable) {
            onUnavailable()
            return
        }

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
                    SpeechRecognizer.ERROR_SPEECH_TIMEOUT,
                    SpeechRecognizer.ERROR_NO_MATCH -> onResult(emptyList())
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
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                putExtra(RecognizerIntent.EXTRA_PREFER_OFFLINE, true)
            }
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
