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

private const val MINIMUM_UTTERANCE_MILLIS = 300

// A beginning reader stares at a hard word for a long time before saying it, and on the legacy
// path every session that ends on silence has to be started again. Ending the session is the
// expensive thing there, so the silence budget is generous — it is not trying to detect the end
// of an utterance, only to notice that the reader has stopped altogether.
private const val CONTINUOUS_COMPLETE_SILENCE_MILLIS = 8_000

// In a segmented session this same silence ends a *segment*, not the session, so a short value is
// the good one: it is how quickly a spoken word is finalised and handed over, and the microphone
// stays open across it either way.
private const val SEGMENT_SILENCE_MILLIS = 1_500

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
 * still handle `onUnavailable`. The reading mode has to work with no microphone at all: without
 * one the reader still moves the focus themselves and simply gets no review of their reading.
 */
class SpeechGate(private val context: Context) {

    private var recognizer: SpeechRecognizer? = null
    private var isOnDevice = false

    // Languages this device turned out not to have an on-device model for. Remembered so the
    // discovery costs one failed session per language rather than one per word.
    private val networkOnlyLanguages = mutableSetOf<String>()

    private data class Request(
        val languageCode: String,
        val onPartial: (String) -> Unit,
        val onSegment: (List<String>) -> Unit,
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

    /**
     * Opens one listening session and keeps it open.
     *
     * @param onPartial the engine's running guess at the segment it is currently hearing. It
     *   revises this continually, so each call replaces the last rather than adding to it.
     * @param onSegment a finished piece of the reading. The session is still open and the
     *   microphone never closed; more segments will follow. This is what makes the capture
     *   continuous — on the legacy path the equivalent moment is also the end of the session.
     * @param onEnded the session really is over and the microphone is closed. Call again to
     *   open another one.
     */
    fun startContinuous(
        languageCode: String,
        onPartial: (String) -> Unit,
        onSegment: (List<String>) -> Unit,
        onEnded: (List<String>) -> Unit,
        onUnavailable: () -> Unit
    ) {
        start(Request(languageCode, onPartial, onSegment, onEnded, onUnavailable))
    }

    private fun start(request: Request) {
        val activeRecognizer = obtainRecognizer(request.languageCode) ?: run {
            request.onUnavailable()
            return
        }

        activeRecognizer.setRecognitionListener(object : RecognitionListener {
            override fun onPartialResults(partialResults: Bundle?) {
                hypothesesFrom(partialResults).firstOrNull()?.let(request.onPartial)
            }

            /**
             * A finished piece of a session that is still running.
             *
             * This is the whole point of the segmented session: the reader keeps reading, the
             * microphone never closes, and each word arrives here as the engine settles on it.
             * Without it the session ended after every pause and the words spoken during the
             * teardown-and-restart were simply gone — reported to the reader as never heard.
             */
            override fun onSegmentResults(segmentResults: Bundle) {
                request.onSegment(hypothesesFrom(segmentResults))
            }

            override fun onEndOfSegmentedSession() {
                request.onEnded(emptyList())
            }

            override fun onResults(results: Bundle?) {
                val hypotheses = hypothesesFrom(results)
                // On the legacy path this one callback is both things at once: the last piece of
                // the reading, and the end of the session.
                request.onSegment(hypotheses)
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
                    SpeechRecognizer.ERROR_CLIENT,
                    // The network went away, or the server did. This one session is lost and the
                    // next may well work, so it ends like any other. Reporting it as "recognition
                    // is unavailable" is what used to retire the microphone for the rest of a
                    // reading over one dropped request — and on a device with no on-device model
                    // for the language, where every session goes over the network, that is the
                    // ordinary failure rather than an exotic one.
                    SpeechRecognizer.ERROR_NETWORK,
                    SpeechRecognizer.ERROR_NETWORK_TIMEOUT,
                    SpeechRecognizer.ERROR_SERVER,
                    SpeechRecognizer.ERROR_AUDIO,
                    // Throttling, and the recognition service process going away — the Google app
                    // updating, or a low-memory kill. Both clear on the next session, and both
                    // are exactly what a device that opens sessions often runs into. The two
                    // constants only exist from API 31 and 33, but referencing them is safe on
                    // anything older: they are compile-time constants an old platform never
                    // reports.
                    SpeechRecognizer.ERROR_SERVER_DISCONNECTED,
                    SpeechRecognizer.ERROR_TOO_MANY_REQUESTS -> request.onEnded(emptyList())
                    // What is left really is unavailable: no permission, or a service that will
                    // not serve this request at all.
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
            // Int, not Long. These extras are read with getInt, so a Long silently misses and the
            // engine falls back to its own default — which is what made the "generous" silence
            // budget below have no effect at all, and left sessions closing every few seconds.
            putExtra(
                RecognizerIntent.EXTRA_SPEECH_INPUT_MINIMUM_LENGTH_MILLIS,
                MINIMUM_UTTERANCE_MILLIS
            )

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                // One session, many results. The value of EXTRA_SEGMENTED_SESSION is the *key* of
                // the extra that decides where one segment ends and the next begins — here, a
                // pause in the reading. The reader can then read a whole page into a microphone
                // that is opened once, instead of one that closed after every pause and dropped
                // whatever was said while it was being built again.
                putExtra(
                    RecognizerIntent.EXTRA_SEGMENTED_SESSION,
                    RecognizerIntent.EXTRA_SPEECH_INPUT_COMPLETE_SILENCE_LENGTH_MILLIS
                )
                putExtra(
                    RecognizerIntent.EXTRA_SPEECH_INPUT_COMPLETE_SILENCE_LENGTH_MILLIS,
                    SEGMENT_SILENCE_MILLIS
                )
            } else {
                putExtra(
                    RecognizerIntent.EXTRA_SPEECH_INPUT_COMPLETE_SILENCE_LENGTH_MILLIS,
                    CONTINUOUS_COMPLETE_SILENCE_MILLIS
                )
                putExtra(
                    RecognizerIntent.EXTRA_SPEECH_INPUT_POSSIBLY_COMPLETE_SILENCE_LENGTH_MILLIS,
                    CONTINUOUS_COMPLETE_SILENCE_MILLIS
                )
            }
        }
    }


    /**
     * Closes the session and asks for the final transcript.
     *
     * Different from [cancel] in the one way that matters to the reading review: cancelling
     * throws away what the engine has heard, so the last words of a reading would simply be
     * missing from the report.
     */
    fun stop() {
        runCatching { recognizer?.stopListening() }
    }

    fun cancel() {
        runCatching { recognizer?.cancel() }
    }

    fun release() {
        runCatching { recognizer?.destroy() }
        recognizer = null
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
