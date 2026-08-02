package com.example.sulu_read.focus

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import com.example.sulu_read.domain.model.AppLanguage

// One word is being read, not a sentence, so a wide hypothesis list is cheap and it is exactly
// what carries an accented reading: the engine's own first guess leans towards the pronunciation
// it was trained on, and the reader's word is often further down the list.
private const val MAX_HYPOTHESES = 10

// The engine's defaults are tuned for dictation, where it should wait to see if the speaker has
// more to say. Here the utterance is one word and the reader is waiting to move on, so silence
// is allowed to end it far sooner. This is most of what makes recognition feel fast.
private const val COMPLETE_SILENCE_MILLIS = 700L
private const val POSSIBLY_COMPLETE_SILENCE_MILLIS = 700L
private const val MINIMUM_UTTERANCE_MILLIS = 300L

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

    /**
     * @param onPartial called with hypotheses the engine is still revising. A caller that can
     *   already accept one of them should do so and call [cancel]: waiting for the final result
     *   costs the reader the endpointing silence on every single word, which is the difference
     *   between the word turning green as they finish saying it and a pause after each one.
     */
    fun listenOnce(
        languageCode: String,
        onResult: (List<String>) -> Unit,
        onUnavailable: () -> Unit,
        onPartial: (List<String>) -> Unit = {}
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
                onResult(hypothesesFrom(results))
            }

            override fun onPartialResults(partialResults: Bundle?) {
                val hypotheses = hypothesesFrom(partialResults)
                if (hypotheses.isNotEmpty()) {
                    onPartial(hypotheses)
                }
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
            override fun onEvent(eventType: Int, params: Bundle?) = Unit
        })

        val locale = AppLanguage.localeFor(languageCode)
        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(
                RecognizerIntent.EXTRA_LANGUAGE_MODEL,
                RecognizerIntent.LANGUAGE_MODEL_FREE_FORM
            )
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, locale.toLanguageTag())
            // Without this the engine may answer in whatever language it thinks it heard, which
            // for an accented reader is regularly the wrong one. Naming the expected language
            // and nothing else keeps the hypotheses in the language being read.
            putExtra(
                RecognizerIntent.EXTRA_LANGUAGE_PREFERENCE,
                locale.toLanguageTag()
            )
            putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, MAX_HYPOTHESES)
            putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
            putExtra(
                RecognizerIntent.EXTRA_SPEECH_INPUT_COMPLETE_SILENCE_LENGTH_MILLIS,
                COMPLETE_SILENCE_MILLIS
            )
            putExtra(
                RecognizerIntent.EXTRA_SPEECH_INPUT_POSSIBLY_COMPLETE_SILENCE_LENGTH_MILLIS,
                POSSIBLY_COMPLETE_SILENCE_MILLIS
            )
            putExtra(
                RecognizerIntent.EXTRA_SPEECH_INPUT_MINIMUM_LENGTH_MILLIS,
                MINIMUM_UTTERANCE_MILLIS
            )
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

private fun hypothesesFrom(results: Bundle?): List<String> {
    return results
        ?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
        ?.toList()
        .orEmpty()
}
