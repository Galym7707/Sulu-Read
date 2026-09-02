package com.example.sulu_read.focus

import android.Manifest
import android.content.pm.PackageManager
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Lightbulb
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.MicOff
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import com.example.sulu_read.R
import com.example.sulu_read.audio.applyNaturalVoice
import com.example.sulu_read.audio.canSpeak
import com.example.sulu_read.audio.detectSpeechLanguageCode
import com.example.sulu_read.audio.speakCompat
import com.example.sulu_read.ui.screens.AiHelpState
import com.example.sulu_read.ui.theme.FieldSurface
import com.example.sulu_read.ui.theme.ListeningColor
import com.example.sulu_read.ui.theme.TryAgainColor
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext


private const val PROGRESS_BAR_HEIGHT_DP = 12

// Material's minimum accessible touch target. The default button height falls under it once
// padding is accounted for on small screens.
private const val MIN_TOUCH_TARGET_DP = 56

// Breath between one session ending and the next beginning. Only paid when the engine actually
// closed a session, not between words, so it can be short; it exists to stop a device that
// returns "heard nothing" instantly from spinning the recognizer in a tight loop.
private const val SESSION_RESTART_DELAY_MILLIS = 120L

@Composable
fun FocusReaderScreen(
    text: String,
    languageCode: String,
    aiHelpState: AiHelpState,
    onRequestMeaningHint: (String) -> Unit,
    onDismissHint: () -> Unit,
    onCollectTriggerWords: (List<String>) -> Unit,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val words = remember(text) { buildFocusWords(text) }
    var ladder by remember(text) { mutableStateOf(FocusLadderState()) }
    // The reader starts the session once; the microphone then stays with them word after
    // word. Stopping after every word would make them tap the phone more often than they read.
    var isSessionActive by remember(text) { mutableStateOf(false) }
    var isSpeaking by remember { mutableStateOf(false) }
    var listenAttempt by remember { mutableStateOf(0) }
    var isFlashing by remember { mutableStateOf(false) }
    var micDenied by remember { mutableStateOf(false) }
    // A session that ended almost as soon as it opened is a device refusing, not a reader
    // pausing. Only that case is worth waiting before retrying; pausing after an ordinary
    // session just adds dead air between one word and the next.
    var wasInstantFailure by remember { mutableStateOf(false) }

    // Everything the analyser has heard this reading. Sessions close on their own after a long
    // silence and are opened again, so the closed ones are kept apart from the one still
    // running: the engine keeps rewriting the live transcript until its session ends, and
    // appending revisions of the same speech would double every word in the review.
    var closedTranscript by remember(text) { mutableStateOf(emptyList<String>()) }
    var liveTranscript by remember(text) { mutableStateOf(emptyList<String>()) }
    // The words the reader actually moved through, in the order they visited them. Once they
    // drive the focus themselves this is no longer "the first N words": they go back over a
    // line, or skip one, and the review has to line the transcript up against what was read.
    var visited by remember(text) { mutableStateOf(listOf(0)) }
    var reviewRequested by remember(text) { mutableStateOf(false) }
    // Frozen at the moment the review is asked for. Aligning against the live list instead meant
    // that every tap after "stop and check" added a word to a transcript that could no longer
    // grow, so the panel in front of the reader grew one fresh "not heard" per tap — for words
    // the app had deliberately stopped listening to.
    var reviewTargets by remember(text) { mutableStateOf(emptyList<Int>()) }
    var review by remember(text) { mutableStateOf<List<WordReview>?>(null) }
    // Uptime of the last thing the recogniser heard. Feeds the silence timer, which otherwise
    // measures nothing but how long the reader has gone without tapping.
    var heardSpeechAt by remember(text) { mutableStateOf(0L) }

    val speechGate = remember(context) { SpeechGate(context) }
    // Starts optimistic. SpeechRecognizer.isRecognitionAvailable() returns false on devices
    // that recognise speech fine, so the self-check fallback is offered only once a real
    // attempt has failed, not on a pre-flight guess.
    var micUnavailable by remember(context) { mutableStateOf(false) }
    DisposableEffect(speechGate) {
        onDispose { speechGate.release() }
    }

    // Binding to the recognition service takes real time. Doing it here rather than inside the
    // first session keeps that cost off the reader's first word, which is the one where a delay
    // reads as the app being broken.
    LaunchedEffect(speechGate, languageCode) {
        speechGate.prepare(languageCode)
    }

    val mainHandler = remember { Handler(Looper.getMainLooper()) }
    var textToSpeech by remember { mutableStateOf<TextToSpeech?>(null) }
    // The utterance whose completion means the app has finished speaking. Held in a reference
    // rather than Compose state because the engine's callbacks read it off the main thread.
    val lastUtteranceId = remember { AtomicReference<String?>(null) }
    // Null until the engine has answered. A Kazakh reader on a phone with no Kazakh voice was
    // simply given a Russian one, with nothing on screen saying so — the app knew and did not
    // tell them.
    var voiceMissing by remember(languageCode) { mutableStateOf(false) }
    DisposableEffect(context, languageCode) {
        lateinit var engine: TextToSpeech
        engine = TextToSpeech(context) { status ->
            if (status == TextToSpeech.SUCCESS) {
                val available = engine.canSpeak(languageCode)
                mainHandler.post { voiceMissing = !available }
            }
        }
        // Utterance callbacks arrive off the main thread, so hop back before touching state.
        engine.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
            override fun onStart(utteranceId: String?) {
                mainHandler.post { isSpeaking = true }
            }

            // Only the utterance that was queued last ends the speaking window. The Letters rung
            // queues two — the letter names, then the word itself — and clearing the flag on
            // whichever finished first opened the microphone in the gap between them, so the app
            // was recorded saying the very word the reader was stuck on and then credited them
            // with having read it.
            override fun onDone(utteranceId: String?) {
                if (utteranceId != lastUtteranceId.get()) {
                    return
                }
                mainHandler.post { isSpeaking = false }
            }

            @Deprecated("Required by UtteranceProgressListener")
            override fun onError(utteranceId: String?) {
                if (utteranceId != lastUtteranceId.get()) {
                    return
                }
                mainHandler.post { isSpeaking = false }
            }
        })
        textToSpeech = engine
        onDispose {
            engine.stop()
            engine.shutdown()
            textToSpeech = null
            // Stopping an utterance produces no onDone, so without this a language change while
            // the app was speaking would leave the flag raised — and the flag is what gates
            // opening a recognition session, so the microphone would never come back.
            isSpeaking = false
        }
    }

    val currentWord = words.getOrNull(ladder.wordIndex)

    fun speak(value: String, queueMode: Int = TextToSpeech.QUEUE_FLUSH) {
        val engine = textToSpeech ?: return
        val speechLanguage = detectSpeechLanguageCode(value, languageCode)
        engine.applyNaturalVoice(speechLanguage)
        engine.setSpeechRate(ladder.ttsRate())
        // Close the open session before the first syllable, not merely stop opening new ones.
        // Setting isSpeaking only makes the listening effect decline to *start* a session; the
        // one already running keeps the microphone open, and on a real device the mic was still
        // open while the app spoke — so the letter names and the word itself went into the
        // transcript and were later credited to the reader as words they had read. Stopped
        // rather than cancelled, so everything heard before this point survives into the review.
        speechGate.stop()
        // Set here rather than in onStart: the recognizer must not be re-armed during the gap
        // before the engine actually begins, or the app hears its own voice.
        isSpeaking = true
        // A refusal arrives as a return value and in no other way — no progress callback follows
        // it. Left unchecked the flag stays raised, and since it is what gates opening a session,
        // one phone without the voice data would silence the microphone for the whole reading
        // and report every remaining word as never heard.
        // Claimed before the call, so the utterance queued last is the one whose completion is
        // allowed to reopen the microphone.
        val utteranceId = "focus-${value.hashCode()}"
        lastUtteranceId.set(utteranceId)
        if (engine.speakCompat(value, queueMode, utteranceId) != TextToSpeech.SUCCESS) {
            isSpeaking = false
        }
    }

    // The reader owns the focus. Nothing else in this screen moves it: not the recogniser, not
    // a timer. Being carried forward by a machine that thinks it heard the word is what made
    // the old mode unusable for a reader whose pronunciation the engine does not know.
    fun moveFocusTo(target: Int) {
        // Read out of state rather than using the `currentWord` this lambda closed over. The tap
        // handler in the text block is installed once — its pointerInput is keyed on the word
        // ranges, which never change for a given text — so a captured value stays frozen at the
        // first word for the whole reading, and the practice set would fill with that one word
        // instead of the ones the reader struggled over.
        val leaving = words.getOrNull(ladder.wordIndex)?.spoken.orEmpty()
        val next = ladder.onFocusMoved(target, leaving, words.size)
        if (next.wordIndex == ladder.wordIndex) {
            return
        }
        // Only moving forward records a word as read. Going back over a line would otherwise put
        // that word in the list a second time while the transcript holds one reading of it, and
        // the review would have to call one of the two occurrences unheard — turning the
        // self-correction this mode exists to encourage into a reported mistake.
        if (next.wordIndex > ladder.wordIndex && next.wordIndex < words.size) {
            visited = visited + next.wordIndex
        }
        ladder = next
    }

    val micPermissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission()
    ) { granted ->
        micDenied = !granted
        isSessionActive = granted
    }

    fun startListening() {
        val hasPermission = ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.RECORD_AUDIO
        ) == PackageManager.PERMISSION_GRANTED

        if (!hasPermission) {
            micPermissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
            return
        }
        isSessionActive = true
    }

    // One session spans many words. Deliberately NOT keyed on the word index or the ladder step:
    // re-keying on those tore the recogniser down and started it again between every pair of
    // words, and the reader paid the restart — a fixed delay, the service session setup, and
    // often a RECOGNIZER_BUSY retry because cancelling does not free the microphone at once.
    // The nudge timer moving the step used to cost a restart mid-word for the same reason.
    //
    // The listener now only writes down what it hears. Nothing here judges the reading or moves
    // the focus; the transcript is read against the text once, at the end.
    LaunchedEffect(isSessionActive, isSpeaking, listenAttempt) {
        if (!isSessionActive || isSpeaking || currentWord == null) {
            return@LaunchedEffect
        }
        if (wasInstantFailure) {
            delay(SESSION_RESTART_DELAY_MILLIS)
        }

        // Per session, not per reading: it answers "did this session hear anything at all", which
        // is what separates a device refusing to listen from a reader who has gone quiet.
        var heardThisSession = false

        speechGate.startContinuous(
            languageCode = detectSpeechLanguageCode(currentWord.spoken, languageCode),
            onPartial = { transcript ->
                // Only a partial with words in it replaces what is held. Engines emit an empty
                // hypothesis when their endpointer resets, and taking that at face value wiped
                // the running record of the session — on the legacy path the only record there
                // is — so a reading that then ended on an error was reported as never heard.
                val heard = tokenizeTranscript(transcript)
                if (heard.isNotEmpty()) {
                    liveTranscript = heard
                    // The one signal that says the reader is reading. The silence timer below
                    // waits on it, so a slow reader working audibly through a long word is not
                    // talked over by help they did not ask for.
                    heardSpeechAt = SystemClock.uptimeMillis()
                }
            },
            onSegment = { hypotheses ->
                // A piece of the reading the engine has settled on. Its own final answer beats
                // the last partial: partials are guesses it was still revising. If it settled on
                // nothing, the partials are all there is.
                val settled = hypotheses.firstOrNull()
                    ?.let { tokenizeTranscript(it) }
                    ?.takeIf { it.isNotEmpty() }
                    ?: liveTranscript
                closedTranscript = closedTranscript + settled
                // Cleared because the next partials describe the next segment, not this one.
                // Leaving it would put the same words in the review twice.
                liveTranscript = emptyList()
                if (settled.isNotEmpty()) {
                    heardThisSession = true
                    heardSpeechAt = SystemClock.uptimeMillis()
                }
            },
            onEnded = { hypotheses ->
                // Only reached when the microphone really has closed. On a device that honours
                // segmented sessions that is rare — the reader pressing stop, the app speaking,
                // or an error — so the restart below is no longer the normal course of events.
                //
                // A segment cut off mid-way by whatever ended the session still belongs to the
                // reading, so what the engine had heard by then is kept rather than dropped.
                if (liveTranscript.isNotEmpty()) {
                    closedTranscript = closedTranscript + liveTranscript
                    liveTranscript = emptyList()
                }
                wasInstantFailure = !heardThisSession && hypotheses.isEmpty()
                listenAttempt += 1
            },
            onUnavailable = {
                isSessionActive = false
                micUnavailable = true
            }
        )
    }

    // The text ran out. Close the microphone rather than cancelling it: cancelling throws away
    // the engine's answer for the last words read, which are the ones the reader has just said.
    LaunchedEffect(currentWord == null) {
        if (currentWord != null) {
            return@LaunchedEffect
        }
        if (isSessionActive) {
            speechGate.stop()
            isSessionActive = false
        }
        // Deliberately not skipped for an empty text. The finished branch shows the review panel,
        // and a panel that is never asked for a verdict sits on a spinner for ever — which is
        // what a blank page used to render.
        reviewTargets = visited
        reviewRequested = true
    }

    // Keyed on the transcript as well as on the request: the last session's final answer lands
    // after the reader has already finished, and a review that ignored it would be missing the
    // end of their reading. Off the main thread because lining a whole page up against a whole
    // transcript is real work, and it happens while the reader is looking at the screen.
    LaunchedEffect(reviewRequested, closedTranscript, liveTranscript, reviewTargets) {
        if (!reviewRequested) {
            return@LaunchedEffect
        }
        val tokens = closedTranscript + liveTranscript
        // Numbers stay in. They used to be filtered out here on the grounds that "5" could never
        // match a transcript that says "пять" — true of a letter comparison, and the wrong fix:
        // it meant a child who read every number perfectly was never credited for any of them.
        // The review now understands numerals in all three languages, in either spelling.
        val targets = reviewTargets.mapNotNull { words.getOrNull(it)?.spoken }
        review = withContext(Dispatchers.Default) { reviewReading(tokens, targets) }
    }

    // Silence help. A reader who stares at a word without speaking is the one who most needs
    // a nudge, and under the old rules got nothing until they guessed wrong out loud. Neither
    // stage records a failure: onHelpRequested only raises the step.
    //
    // Keyed on the word and on the last thing heard. The word alone was not enough once the
    // recogniser stopped moving the focus: nothing else fed reading activity back, so a reader
    // audibly working through a long word had the letters spoken over them at nine seconds for
    // doing exactly what the mode asks. Speaking now restarts the wait. Keying on the step too
    // would restart the timer every time this effect moved the step, and the reader would be
    // flashed at forever instead of ever reaching the second stage.
    LaunchedEffect(ladder.wordIndex, heardSpeechAt) {
        delay(NUDGE_AFTER_MILLIS)
        if (ladder.step != FocusStep.Focus) {
            return@LaunchedEffect
        }
        ladder = ladder.onHelpRequested(FocusStep.Sweep)

        delay(OFFER_HELP_AFTER_MILLIS - NUDGE_AFTER_MILLIS)
        if (ladder.step != FocusStep.Focus) {
            return@LaunchedEffect
        }
        ladder = ladder.onHelpRequested(FocusStep.Letters)
    }

    // Sweep step: drop the word back to the size of its neighbours, then flash it emphasised
    // for the 200 ms the source specifies, then leave it emphasised. The pulse is what draws
    // the eye back to a reader who has lost their place.
    LaunchedEffect(ladder.step, ladder.wordIndex) {
        if (ladder.step != FocusStep.Sweep) {
            isFlashing = false
            return@LaunchedEffect
        }
        isFlashing = false
        delay(SWEEP_FLASH_MILLIS)
        isFlashing = true
        delay(SWEEP_FLASH_MILLIS)
        isFlashing = false
        ladder = ladder.onNudgeFinished()
    }

    // Letters and Meaning speak on entry, at the adaptive pace.
    LaunchedEffect(ladder.step, ladder.wordIndex) {
        val word = currentWord ?: return@LaunchedEffect
        when (ladder.step) {
            FocusStep.Letters -> {
                speak(letterNamesFor(word.spoken, detectSpeechLanguageCode(word.spoken, languageCode)).joinToString(" , "))
                // Queued behind the letters rather than flushing them. A fixed wait and then a
                // flush cut the letters off wherever they had got to — at the slow rate this
                // step uses, about two of them — so the deepest rung of the help ladder said
                // "эн, а" and then the whole word, while the screen listed all nine letters.
                speak(word.spoken, TextToSpeech.QUEUE_ADD)
            }
            FocusStep.Meaning -> {
                onRequestMeaningHint(word.spoken)
                speak(word.spoken)
            }
            FocusStep.Focus, FocusStep.Sweep -> Unit
        }
    }

    Column(
        modifier = modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        Text(
            text = stringResource(R.string.focus_mode_title),
            style = MaterialTheme.typography.titleMedium
        )
        // Once the analyser has an opinion, it is the better answer to "how much did you read
        // without help": it counts words that actually came out right. Before that there is only
        // the ladder's own count of words taken without asking for help. Reporting the ladder's
        // number after a reading nobody heard would put "100%" directly above a panel saying
        // nothing was heard — two verdicts on one screen, and the flattering one is the wrong one.
        val readShare = remember(review, ladder.recentCleanReads) {
            review
                ?.takeIf { it.isNotEmpty() }
                ?.let { entries ->
                    entries.count { it.outcome == ReadOutcome.Correct }.toFloat() / entries.size
                }
                ?: ladder.masteryShare()
        }
        Text(
            text = stringResource(R.string.focus_progress, (readShare * 100).toInt()),
            style = MaterialTheme.typography.bodyMedium
        )
        // The bar carries the meaning and the line above names it. A bar on its own leaves the
        // reader guessing what it measures, and a percentage on its own is a number a child has
        // to interpret; together each covers the other's weakness.
        LinearProgressIndicator(
            progress = { readShare },
            modifier = Modifier
                .fillMaxWidth()
                .height(PROGRESS_BAR_HEIGHT_DP.dp)
                .clip(RoundedCornerShape(6.dp))
        )

        FocusTextBlock(
            words = words,
            focusIndex = ladder.wordIndex,
            isFocusEmphasised = ladder.step != FocusStep.Sweep || isFlashing,
            onWordTapped = ::moveFocusTo
        )

        if (currentWord == null) {
            Text(text = stringResource(R.string.focus_finished))
            if (reviewRequested) {
                ReadingReviewPanel(review = review)
            }
            val practiseWords = remember(ladder.triggerWords, review) {
                (ladder.triggerWords + misreadWordsFrom(review)).distinct()
            }
            if (practiseWords.isNotEmpty()) {
                Button(
                    onClick = { onCollectTriggerWords(practiseWords) },
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text(text = stringResource(R.string.focus_practise_words))
                }
            }
        } else {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Icon(
                    imageVector = if (isSessionActive && !isSpeaking) Icons.Default.Mic
                    else Icons.Default.MicOff,
                    contentDescription = null,
                    tint = ListeningColor
                )
                Text(
                    text = stringResource(
                        if (isSessionActive && !isSpeaking) R.string.focus_listening
                        else R.string.focus_listen
                    ),
                    style = MaterialTheme.typography.titleMedium,
                    color = ListeningColor
                )
            }
            // The controls come directly under the reading block, before anything that can grow.
            // They used to sit below the step help, the warnings and the review panel, and on a
            // large phone that pushed the microphone button off the bottom of the screen: the
            // reader was told to read aloud by a line they could see, while the control that
            // starts the listening was somewhere below the fold and the analysis — the whole
            // point of the mode — silently never ran. Everything of variable height now renders
            // after this block, so nothing can move it and nothing can push it away.
            //
            // Stacked, not side by side. Two weighted buttons in a row each get an exact half
            // width, and the icon plus the button's own padding ate most of it before the label
            // was measured — about 74dp of room for a label needing 84dp — so Compose broke the
            // word instead: "Слушат" / "ь". Full-width buttons have no such constraint, they
            // survive a large system font size, and two big stacked targets are easier for this
            // reader to hit anyway.
            Column(
                modifier = Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                // The one control the reader uses on every word, so it is the big one at the
                // top of the stack. Nothing else advances the text.
                Button(
                    onClick = { moveFocusTo(ladder.wordIndex + 1) },
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(min = MIN_TOUCH_TARGET_DP.dp)
                ) {
                    Text(
                        text = stringResource(R.string.focus_next_word),
                        maxLines = 1
                    )
                }

                // Always rendered. It used to be hidden the moment recognition failed once, and
                // since it is the only way to start a session *and* the only way to ask for a
                // review, a single dropped network call ended the analysis for that text with no
                // way back — on a phone with no on-device model for the language, and every
                // session going over the network, that is the ordinary failure, not a rare one.
                // Now it is the retry: pressing it clears the failure and tries again.
                FilledTonalButton(
                    onClick = {
                        if (isSessionActive) {
                            // Stopped, not cancelled: the reader asking to stop is also the
                            // reader asking what they got wrong, and cancelling would throw
                            // away the engine's answer for everything since the last pause.
                            speechGate.stop()
                            isSessionActive = false
                            reviewTargets = visited
                            reviewRequested = true
                        } else {
                            reviewRequested = false
                            review = null
                            micUnavailable = false
                            startListening()
                        }
                    },
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(min = MIN_TOUCH_TARGET_DP.dp)
                ) {
                    Icon(
                        imageVector = if (isSessionActive) Icons.Default.Stop else Icons.Default.Mic,
                        contentDescription = null,
                        modifier = Modifier.size(ButtonDefaults.IconSize)
                    )
                    Spacer(modifier = Modifier.width(ButtonDefaults.IconSpacing))
                    Text(
                        text = stringResource(
                            if (isSessionActive) R.string.focus_check_now
                            else R.string.focus_listen_start
                        ),
                        maxLines = 1
                    )
                }

                FilledTonalButton(
                    onClick = {
                        ladder = ladder.onHelpRequested(
                            when (ladder.step) {
                                FocusStep.Focus, FocusStep.Sweep -> FocusStep.Letters
                                FocusStep.Letters, FocusStep.Meaning -> FocusStep.Meaning
                            }
                        )
                    },
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(min = MIN_TOUCH_TARGET_DP.dp)
                ) {
                    Icon(
                        imageVector = Icons.Default.Lightbulb,
                        contentDescription = null,
                        modifier = Modifier.size(ButtonDefaults.IconSize)
                    )
                    Spacer(modifier = Modifier.width(ButtonDefaults.IconSpacing))
                    Text(text = stringResource(R.string.focus_help), maxLines = 1)
                }
            }

            // Said once, below the controls: the text itself is the third control. Without it a
            // reader taps the button forever and never discovers they can go back over a line.
            Text(
                text = stringResource(R.string.focus_tap_word_hint),
                style = MaterialTheme.typography.bodySmall
            )

            when (ladder.step) {
                FocusStep.Letters -> {
                    Text(
                        text = stringResource(R.string.focus_step_letters),
                        style = MaterialTheme.typography.bodyMedium
                    )
                    Text(
                        text = letterNamesFor(
                            currentWord.spoken,
                            detectSpeechLanguageCode(currentWord.spoken, languageCode)
                        )
                            .joinToString(" · "),
                        style = MaterialTheme.typography.titleMedium
                    )
                }

                FocusStep.Meaning -> MeaningHint(
                    aiHelpState = aiHelpState,
                    onDismissHint = onDismissHint
                )

                FocusStep.Focus, FocusStep.Sweep -> Unit
            }

            if (micUnavailable || micDenied) {
                Text(
                    text = stringResource(
                        if (micDenied) R.string.focus_mic_permission
                        else R.string.focus_mic_unavailable
                    ),
                    style = MaterialTheme.typography.bodySmall
                )
            }

            if (voiceMissing) {
                Text(
                    text = stringResource(R.string.tts_voice_missing),
                    style = MaterialTheme.typography.bodySmall,
                    color = TryAgainColor
                )
            }

            // Asked for part-way through a reading. Shown here rather than replacing the text,
            // because the reader is still in the middle of it and the words are the thing they
            // came for.
            if (reviewRequested) {
                ReadingReviewPanel(review = review)
            }
        }

        if (ladder.suggestPause) {
            PausePanel(onContinue = { ladder = ladder.onPauseAcknowledged() })
        }
    }
}

/**
 * What the analyser made of the reading, once the reading is over.
 *
 * Only the words that did not come out right are listed. A reader who has just finished a page
 * does not need to be shown the whole page back with ticks on it, and a list of everything they
 * got wrong is the one thing they can act on — it is also what feeds the practice set.
 */
@Composable
private fun ReadingReviewPanel(review: List<WordReview>?, modifier: Modifier = Modifier) {
    val mistakes = remember(review) { review?.let(::mistakesFrom) }

    Column(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .background(FieldSurface)
            .padding(14.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Text(
            text = stringResource(R.string.focus_review_title),
            style = MaterialTheme.typography.titleMedium
        )
        when {
            review == null -> CircularProgressIndicator()
            review.isEmpty() -> Text(
                text = stringResource(R.string.focus_review_nothing_heard),
                style = MaterialTheme.typography.bodyMedium
            )

            mistakes.isNullOrEmpty() -> Text(
                text = stringResource(R.string.focus_review_clean),
                style = MaterialTheme.typography.bodyMedium
            )

            else -> mistakes.forEach { entry ->
                Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Text(
                        text = entry.word,
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                        color = TryAgainColor
                    )
                    Text(
                        // A misreading is reported with what the analyser actually heard, so the
                        // reader can hear the difference. A word it never heard is reported as
                        // exactly that and nothing more: the microphone may simply have missed
                        // it, and telling a child they read a word wrong when they did not is
                        // worse than saying nothing.
                        text = when (val heard = entry.heard) {
                            null -> stringResource(R.string.focus_review_not_heard)
                            else -> stringResource(R.string.focus_review_heard, heard)
                        },
                        style = MaterialTheme.typography.bodyMedium
                    )
                }
            }
        }
    }
}

private fun misreadWordsFrom(review: List<WordReview>?): List<String> {
    return review.orEmpty()
        .filter { it.outcome == ReadOutcome.Misread }
        .map { it.word }
        .distinct()
}

@Composable
private fun MeaningHint(aiHelpState: AiHelpState, onDismissHint: () -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .background(FieldSurface)
            .padding(14.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Text(
            text = stringResource(R.string.focus_step_meaning),
            style = MaterialTheme.typography.bodyMedium
        )
        when (aiHelpState) {
            is AiHelpState.Loading -> CircularProgressIndicator()
            is AiHelpState.Success -> {
                Text(
                    text = aiHelpState.result,
                    style = MaterialTheme.typography.bodyLarge
                )
                TextButton(onClick = onDismissHint) {
                    Text(text = stringResource(R.string.focus_hint_dismiss))
                }
            }

            is AiHelpState.Error -> {
                Text(
                    text = stringResource(aiHelpState.messageResId),
                    style = MaterialTheme.typography.bodyMedium
                )
                TextButton(onClick = onDismissHint) {
                    Text(text = stringResource(R.string.focus_hint_dismiss))
                }
            }

            AiHelpState.Idle -> Unit
        }
    }
}

@Composable
private fun PausePanel(onContinue: () -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .background(FieldSurface)
            .padding(14.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        Text(
            text = stringResource(R.string.focus_pause_title),
            style = MaterialTheme.typography.titleMedium
        )
        Button(onClick = onContinue) {
            Text(text = stringResource(R.string.focus_pause_continue))
        }
    }
}
