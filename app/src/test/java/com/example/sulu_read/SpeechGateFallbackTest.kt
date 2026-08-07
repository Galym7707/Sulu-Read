package com.example.sulu_read

import android.speech.SpeechRecognizer
import com.example.sulu_read.focus.isMissingOnDeviceLanguage
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Recognition runs on the device for speed, and drops to the networked recognizer when the
 * device has no model for the language. Which errors mean "drop to the network" and which mean
 * what they usually mean is the whole of that decision, so it is pinned here.
 */
class SpeechGateFallbackTest {

    @Test
    fun missingLanguageSendsTheReaderToTheNetworkRecognizer() {
        assertTrue(isMissingOnDeviceLanguage(SpeechRecognizer.ERROR_LANGUAGE_UNAVAILABLE))
        assertTrue(isMissingOnDeviceLanguage(SpeechRecognizer.ERROR_LANGUAGE_NOT_SUPPORTED))
    }

    @Test
    fun anOnDeviceEngineReportingServerOrNetworkTroubleAlsoFallsBack() {
        // An on-device recognizer has no business reporting either, and if it does the networked
        // one is the thing most likely to work.
        assertTrue(isMissingOnDeviceLanguage(SpeechRecognizer.ERROR_SERVER))
        assertTrue(isMissingOnDeviceLanguage(SpeechRecognizer.ERROR_NETWORK))
    }

    @Test
    fun ordinaryOutcomesMustNotTriggerAFallback() {
        // Silence and a busy microphone are normal parts of a reading session. Treating either
        // as a missing language would throw away on-device recognition on the first pause the
        // reader took, and they would never get it back.
        assertFalse(isMissingOnDeviceLanguage(SpeechRecognizer.ERROR_SPEECH_TIMEOUT))
        assertFalse(isMissingOnDeviceLanguage(SpeechRecognizer.ERROR_NO_MATCH))
        assertFalse(isMissingOnDeviceLanguage(SpeechRecognizer.ERROR_RECOGNIZER_BUSY))
        assertFalse(isMissingOnDeviceLanguage(SpeechRecognizer.ERROR_CLIENT))
    }

    @Test
    fun permissionAndAudioFailuresAreRealFailures() {
        // These mean the reader has to be told something, not that a different engine would do.
        assertFalse(isMissingOnDeviceLanguage(SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS))
        assertFalse(isMissingOnDeviceLanguage(SpeechRecognizer.ERROR_AUDIO))
    }
}
