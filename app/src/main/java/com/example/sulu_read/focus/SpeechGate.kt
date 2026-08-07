package com.example.sulu_read.focus

import android.content.Context
import android.content.Intent
import android.os.Build
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

// A beginning reader stares at a hard word for a long time before saying it, and every session
// that ends on silence has to be started again. Ending the session is the expensive thing here,
// so the silence budget is generous — it is not trying to detect the end of an utterance, only
// to notice that the reader has stopped altogether.
private const val CONTINUOUS_COMPLETE_SILENCE_MILLIS = 8_000L

/**
 * Streams what the reader says, so the reading mode can take words off the transcript as they
 * arrive rather than stopping and restarting recognition around each one.
 *
 * Recognition runs on the device wherever the device can do it. That is the single largest
 * thing that makes a word appear quickly: the default recognizer sends the audio to Google and
 * waits for an answer, which costs a network round trip per session, and for Kazakh and Russian
 * that round trip is most of the delay a reader feels. On-device recognition has no such trip.
 * Not every phone has the language installed, so a device that cannot do it falls back to the
 * networked recognizer automatically and the reader only loses the speed, never the feature.
 *
 * Recognition is not guaranteed on every device or for every language at all, so callers must
 * still handle `onUnavailable` by falling back to a self-check button. The reading mode has to
 * work with no microphone at all.
 */
class SpeechGate(private val context: Context) {

    private var recognizer: SpeechRecognizer? = null
    private var isOnDevice = false

    // Languages this device turned out not to have an on-device model for. Remembered so the
    // discovery costs one failed session per language rather than one per word.
    private val networkOnlyLanguages = mutableSetOf<String>()

    private var pendingRequest: Request? = null

    private data class Request(
        val languageCode: String,
        val onTranscript: (String) -> Unit,
        val onEnded: (List<String>) -> Unit,
        val onUnavailable: () -> Unit
    )

    /**
     * Builds the recognizer before it is needed.
     *
     * Creating one binds to a recognition service, and doing that lazily inside the first
     * session put the whole bind on the reader's first word — the one word where they are most
     * likely to think the app is broken.
     */
    fun prepare(languageCode: String) {
        obtainRecognizer(languageCode)
    }

    fun startContinuous(
        languageCode: String,
        onTranscript: (String) -> Unit,
        onEnded: (List<String>) -> Unit,
        onUnavailable: () -> Unit
    ) {
        start(Request(languageCode, onTranscript, onEnded, onUnavailable))
    }

    private fun start(request: Request) {
        pendingRequest = request
        val activeRecognizer = obtainRecognizer(request.languageCode) ?: run {
            request.onUnavailable()
            return
        }

        activeRecognizer.setRecognitionListener(object : RecognitionListener {
            override fun onPartialResults(partialResults: Bundle?) {
                hypothesesFrom(partialResults).firstOrNull()?.let(request.onTranscript)
            }

            override fun onResults(results: Bundle?) {
                val hypotheses = hypothesesFrom(results)
                hypotheses.firstOrNull()?.let(request.onTranscript)
                request.onEnded(hypotheses)
            }

            override fun onError(error: Int) {
                // The on-device model for this language is missing. Not a failure the reader
                // should ever see: drop to the networked recognizer and carry on with the same
                // session, slower but working.
                if (isOnDevice && isMissingOnDeviceLanguage(error)) {
                    networkOnlyLanguages += AppLanguage.localeFor(request.languageCode).toLanguageTag()
                    requestOnDeviceModel(request.languageCode)
                    rebuildAsNetworkRecognizer()
                    start(request)
                    return
                }

                when (error) {
                    // Silence, or the mic was not free yet. Neither is a wrong answer and
                    // neither means recognition is unavailable: the caller just opens another
                    // session.
                    SpeechRecognizer.ERROR_SPEECH_TIMEOUT,
                    SpeechRecognizer.ERROR_NO_MATCH,
                    SpeechRecognizer.ERROR_RECOGNIZER_BUSY,
                    SpeechRecognizer.ERROR_CLIENT -> request.onEnded(emptyList())
                    else -> request.onUnavailable()
                }
            }

            override fun onReadyForSpeech(params: Bundle?) = Unit
            override fun onBeginningOfSpeech() = Unit
            override fun onRmsChanged(rmsdB: Float) = Unit
            override fun onBufferReceived(buffer: ByteArray?) = Unit
            override fun onEndOfSpeech() = Unit
            override fun onEvent(eventType: Int, params: Bundle?) = Unit
        })

        runCatching { activeRecognizer.startListening(continuousIntent(request.languageCode)) }
            .onFailure { request.onUnavailable() }
    }

    private fun obtainRecognizer(languageCode: String): SpeechRecognizer? {
        recognizer?.let { return it }

        val languageTag = AppLanguage.localeFor(languageCode).toLanguageTag()
        val wantsOnDevice = Build.VERSION.SDK_INT >= Build.VERSION_CODES.S &&
            languageTag !in networkOnlyLanguages &&
            runCatching { SpeechRecognizer.isOnDeviceRecognitionAvailable(context) }.getOrDefault(false)

        if (wantsOnDevice) {
            val onDevice = runCatching {
                SpeechRecognizer.createOnDeviceSpeechRecognizer(context)
            }.getOrNull()
            if (onDevice != null) {
                recognizer = onDevice
                isOnDevice = true
                return onDevice
            }
        }

        val networked = runCatching { SpeechRecognizer.createSpeechRecognizer(context) }.getOrNull()
        recognizer = networked
        isOnDevice = false
        return networked
    }

    /**
     * Asks the system to fetch the on-device model for a language it does not have.
     *
     * Without this the fallback is permanent: a device with no Kazakh model would use the
     * networked recognizer forever and never become fast. The download is handled by the system,
     * which decides when it is reasonable to fetch — this only registers the want. Today's
     * session still runs on the network; the gain arrives next time.
     */
    private fun requestOnDeviceModel(languageCode: String) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) {
            return
        }
        runCatching {
            val downloader = SpeechRecognizer.createOnDeviceSpeechRecognizer(context)
            try {
                downloader.triggerModelDownload(continuousIntent(languageCode))
            } finally {
                downloader.destroy()
            }
        }
    }

    private fun rebuildAsNetworkRecognizer() {
        runCatching { recognizer?.destroy() }
        recognizer = null
        isOnDevice = false
    }

    private fun continuousIntent(languageCode: String): Intent {
        val locale = AppLanguage.localeFor(languageCode)
        return Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, locale.toLanguageTag())
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_PREFERENCE, locale.toLanguageTag())
            putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, MAX_HYPOTHESES)
            putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
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
        pendingRequest = null
    }
}

/**
 * Whether an error means this device has no on-device model for the language.
 *
 * Separated out and given its own name because the difference matters: these codes mean "try the
 * network instead", while every other code means what it usually means. The two language codes
 * only exist from API 33, but referencing them is safe on older devices — they are compile-time
 * constants, and an older platform simply never reports them.
 */
internal fun isMissingOnDeviceLanguage(error: Int): Boolean {
    return error == SpeechRecognizer.ERROR_LANGUAGE_UNAVAILABLE ||
        error == SpeechRecognizer.ERROR_LANGUAGE_NOT_SUPPORTED ||
        error == SpeechRecognizer.ERROR_SERVER ||
        error == SpeechRecognizer.ERROR_NETWORK
}

private fun hypothesesFrom(results: Bundle?): List<String> {
    return results
        ?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
        ?.toList()
        .orEmpty()
}
