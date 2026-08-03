package com.example.sulu_read.focus

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import com.example.sulu_read.domain.model.AppLanguage

// A wide hypothesis list is cheap and it is what carries an accented reading: the engine's own
// first guess leans towards the pronunciation it was trained on. Only the best hypothesis drives
// the live transcript, but the rest are still returned when a session ends.
private const val MAX_HYPOTHESES = 10

private const val MINIMUM_UTTERANCE_MILLIS = 300L

// Continuous mode is not trying to end the utterance quickly - the whole point is that the
// session spans several words - so it tolerates a reader pausing to think.
private const val CONTINUOUS_COMPLETE_SILENCE_MILLIS = 2_000L

/**
 * Streams what the reader says, so the reading mode can take words off the transcript as they
 * arrive rather than stopping and restarting recognition around each one.
 *
 * Recognition is not guaranteed on every device or for every language, so callers must handle
 * `onUnavailable` by falling back to a self-check button. The reading mode has to work with no
 * microphone at all.
 */
class SpeechGate(private val context: Context) {

    private var recognizer: SpeechRecognizer? = null

    /**
     * Opens one session and reports the transcript as it develops, until the engine ends it.
     *
     * The per-word alternative made the reader wait for a full stop-and-start of the recognition
     * service before every single word — a fixed delay, then session setup, and often a
     * RECOGNIZER_BUSY retry on top because cancelling does not release the microphone
     * immediately. Here the session outlives the word: the reader reads on, and the caller takes
     * words off the front of the transcript as they arrive.
     *
     * @param onTranscript best hypothesis so far, called repeatedly as it is revised.
     * @param onEnded the engine finished this session, with its final hypotheses (empty if it
     *   heard nothing). The caller decides whether to open another one.
     */
    fun startContinuous(
        languageCode: String,
        onTranscript: (String) -> Unit,
        onEnded: (List<String>) -> Unit,
        onUnavailable: () -> Unit
    ) {
        val activeRecognizer = recognizer
            ?: SpeechRecognizer.createSpeechRecognizer(context)?.also { recognizer = it }
            ?: run {
                onUnavailable()
                return
            }

        activeRecognizer.setRecognitionListener(object : RecognitionListener {
            override fun onPartialResults(partialResults: Bundle?) {
                hypothesesFrom(partialResults).firstOrNull()?.let(onTranscript)
            }

            override fun onResults(results: Bundle?) {
                val hypotheses = hypothesesFrom(results)
                hypotheses.firstOrNull()?.let(onTranscript)
                onEnded(hypotheses)
            }

            override fun onError(error: Int) {
                when (error) {
                    // Silence, or the mic was not free yet. Neither is a wrong answer and
                    // neither means recognition is unavailable: the caller just opens another
                    // session.
                    SpeechRecognizer.ERROR_SPEECH_TIMEOUT,
                    SpeechRecognizer.ERROR_NO_MATCH,
                    SpeechRecognizer.ERROR_RECOGNIZER_BUSY,
                    SpeechRecognizer.ERROR_CLIENT -> onEnded(emptyList())
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

        runCatching { activeRecognizer.startListening(continuousIntent(languageCode)) }
            .onFailure { onUnavailable() }
    }

    private fun continuousIntent(languageCode: String): Intent {
        val locale = AppLanguage.localeFor(languageCode)
        return Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, locale.toLanguageTag())
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_PREFERENCE, locale.toLanguageTag())
            putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, MAX_HYPOTHESES)
            putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
            // Longer than the single-word timings on purpose. Ending the session early is what
            // this mode exists to avoid: a reader thinking between two words should not cost a
            // whole session restart.
            putExtra(
                RecognizerIntent.EXTRA_SPEECH_INPUT_COMPLETE_SILENCE_LENGTH_MILLIS,
                CONTINUOUS_COMPLETE_SILENCE_MILLIS
            )
            putExtra(
                RecognizerIntent.EXTRA_SPEECH_INPUT_POSSIBLY_COMPLETE_SILENCE_LENGTH_MILLIS,
                CONTINUOUS_COMPLETE_SILENCE_MILLIS
            )
            putExtra(
                RecognizerIntent.EXTRA_SPEECH_INPUT_MINIMUM_LENGTH_MILLIS,
                MINIMUM_UTTERANCE_MILLIS
            )
        }
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
