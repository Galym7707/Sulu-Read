// Web speech plumbing: the browser equivalents of audio/NaturalTts.kt (speechSynthesis)
// and focus/SpeechGate.kt (SpeechRecognition). Behavior contracts kept identical:
// the TTS reports refusals synchronously, the gate streams partials/segments and
// distinguishes "session ended" from "recognition unavailable".
"use strict";

/* ---------------- TTS ---------------- */

let cachedVoices = [];
function refreshVoices() { cachedVoices = window.speechSynthesis ? speechSynthesis.getVoices() : []; }
if (window.speechSynthesis) {
  refreshVoices();
  speechSynthesis.onvoiceschanged = refreshVoices;
}

// Best voice for a language: local (offline) first — the network voice is the one that goes
// silent with no signal — then exact region, mirroring naturalVoiceScore's ordering.
function findBestVoice(languageCode) {
  const locale = localeFor(languageCode);
  const base = locale.slice(0, 2).toLowerCase();
  let best = null, bestScore = -1;
  for (const voice of cachedVoices) {
    const vlang = (voice.lang || "").toLowerCase().replace("_", "-");
    if (!vlang.startsWith(base)) continue;
    let score = 0;
    if (voice.localService) score += 100000;
    if (vlang === locale.toLowerCase()) score += 10000;
    if (voice.default) score += 1;
    if (score > bestScore) { bestScore = score; best = voice; }
  }
  return best;
}

// Speak one utterance. Returns the utterance, or null when the engine refused — the caller's
// "is the app speaking?" flag must be flipped back on refusal (see speakCompat's contract).
function ttsSpeak(text, { languageCode, rate = 1.0, flush = true, onstart = null, onend = null, onboundary = null } = {}) {
  if (!window.speechSynthesis || !text.trim()) {
    if (onend) onend();
    return null;
  }
  const voice = findBestVoice(languageCode);
  // Refuse rather than mispronounce. With no matching voice the engine falls back to the device
  // language, so a Kazakh word is read with Russian or English phonetics — for a child building
  // letter-sound correspondence that is worse than silence. The caller simply hears nothing.
  if (!voice && cachedVoices.length > 0) {
    if (onend) onend();
    return null;
  }
  if (flush) speechSynthesis.cancel();
  const utterance = new SpeechSynthesisUtterance(text);
  utterance.lang = localeFor(languageCode);
  if (voice) utterance.voice = voice;
  utterance.rate = rate;
  utterance.pitch = 1.0;
  if (onstart) utterance.onstart = onstart;
  if (onend) { utterance.onend = onend; utterance.onerror = onend; }
  if (onboundary) utterance.onboundary = onboundary;
  speechSynthesis.speak(utterance);
  return utterance;
}

function ttsStop() {
  if (window.speechSynthesis) speechSynthesis.cancel();
}

/* ---------------- Speech recognition (SpeechGate) ---------------- */

// Diagnostics for the one thing about a speech engine that cannot be read off the spec: how many
// alternatives it really returns, and whether interim results really arrive. Both decide whether
// a correct-but-accented reading is rescued or reported as a mistake. Opt-in per page load.
const SPEECH_DEBUG = typeof location !== "undefined" && /[?&]speechdebug=1/.test(location.search);

class WebSpeechGate {
  constructor() {
    // The live session, and the only thing that identifies it. A gate-wide "already ended" flag
    // cannot do that job: startContinuous cleared it synchronously, before a queued onend from
    // the session it had just aborted could be delivered, so the dead session's onend was
    // attributed to the new one — ending it, and losing whatever was read across the restart.
    this.recognition = null;
    this.active = false;
  }

  static available() {
    return Boolean(window.SpeechRecognition || window.webkitSpeechRecognition);
  }

  prepare() { /* browser engines need no warm-up bind */ }

  // One session, kept open; finals stream out as segments while interims drive the live
  // transcript — the browser's continuous mode is the segmented session the app asks for.
  startContinuous(languageCode, { onPartial, onSegment, onEnded, onUnavailable }) {
    const Ctor = window.SpeechRecognition || window.webkitSpeechRecognition;
    if (!Ctor) { onUnavailable("unavailable"); return; }
    this.stopInternal();

    const recognition = new Ctor();
    recognition.lang = localeFor(languageCode);
    recognition.continuous = true;
    recognition.interimResults = true;
    recognition.maxAlternatives = 10;

    // Why the microphone is gone, or null while it is fine. Carried rather than a boolean: the
    // caller shows "allow the microphone" for a denial and "not available here" for everything
    // else, and reporting a service refusal as a permission problem asks a parent to grant
    // something they already granted.
    let unavailableReason = null;

    recognition.onresult = (event) => {
      let interim = "";
      for (let i = event.resultIndex; i < event.results.length; i++) {
        const result = event.results[i];
        if (result.isFinal) {
          const hypotheses = [];
          for (let j = 0; j < result.length; j++) hypotheses.push(result[j].transcript);
          // How many alternatives an engine ACTUALLY returns decides whether a near-miss can be
          // rescued or is reported as a misreading, and it cannot be read from the spec: Chrome
          // honours maxAlternatives on some builds and returns one on others. Off unless asked
          // for, so it costs the reader nothing: open the page with ?speechdebug=1.
          if (SPEECH_DEBUG) {
            console.log("[sulu] final i=" + i + " alts=" + result.length +
              " | " + hypotheses.join(" ~ "));
          }
          onSegment(hypotheses);
        } else {
          interim += result[0].transcript + " ";
        }
      }
      if (interim.trim()) {
        if (SPEECH_DEBUG) console.log("[sulu] interim: " + interim.trim());
        onPartial(interim);
      }
    };

    recognition.onerror = (event) => {
      // Only a real refusal retires the microphone; everything else ends this one session and
      // the caller opens another — a dropped network call must not end the analysis for good.
      // service-not-allowed is separated out because it is transient far more often than it is
      // final (an insecure context, a throttled service, the engine restarting), and the caller
      // retries it a bounded number of times instead of giving up on the reading.
      if (event.error === "not-allowed") unavailableReason = "denied";
      else if (event.error === "service-not-allowed") unavailableReason = "service";
    };

    recognition.onend = () => {
      // Identity, not a flag: an aborted session's onend arrives after the next one has already
      // started, and answering it would end the live session.
      if (this.recognition !== recognition) return;
      this.recognition = null;
      this.active = false;
      if (unavailableReason) onUnavailable(unavailableReason);
      else onEnded([]);
    };

    this.recognition = recognition;
    this.active = true;
    try { recognition.start(); }
    // A throw here is the session failing to open, not the microphone being unusable — the
    // caller reopens one after SESSION_RESTART_DELAY_MILLIS rather than retiring it.
    catch { this.recognition = null; this.active = false; onEnded([]); }
  }

  // Stop, not abort: stopping lets pending finals arrive, which is the difference between the
  // last words of a reading being reviewed and being thrown away. Not gated on `active`, so a
  // session whose onend went missing can still be closed before the app speaks.
  stop() {
    if (!this.recognition) return;
    try { this.recognition.stop(); } catch { /* already stopped */ }
  }

  stopInternal() {
    if (!this.recognition) return;
    const old = this.recognition;
    this.recognition = null;   // the identity check in onend now silences `old`
    this.active = false;
    try { old.abort(); } catch { /* fine */ }
  }

  release() { this.stopInternal(); }
}

/* ---------------- iOS: gesture unlock + voice warm-up ---------------- */

// MITIGATION, not a guaranteed fix. iOS requires a user gesture for speechSynthesis.speak(), and
// the plain reader speaks a word when the child taps it — a gesture, but not always one iOS
// accepts. Capture phase, so it runs before app handlers. It doubles as the voice-list warm-up:
// getVoices() is empty on iOS until the list loads and onvoiceschanged sometimes never fires.
//
// Focus mode no longer speaks at all, so nothing here runs off a timer any more and the silent
// -utterance watchdog that guarded that case is gone with it.
function unlockSpeech() {
  if (!window.speechSynthesis) return;
  // A real word, not " ": ttsSpeak refuses whitespace-only text and several engines fire
  // onerror on an empty utterance, which is a poor candidate for the unlocking speak().
  const silent = new SpeechSynthesisUtterance("ok");
  silent.volume = 0;
  speechSynthesis.speak(silent);
  refreshVoices();
  if (cachedVoices.length === 0) setTimeout(refreshVoices, 500);
}

/* ---------------- Can this device check the reading at all? ---------------- */

function isIosDevice() {
  const nav = typeof navigator === "undefined" ? null : navigator;
  if (!nav) return false;
  return /iPad|iPhone|iPod/.test(nav.userAgent || "") ||
    (nav.platform === "MacIntel" && nav.maxTouchPoints > 1);  // iPadOS 13+
}

function isStandaloneApp() {
  if (typeof window === "undefined") return false;
  return window.navigator.standalone === true ||
    Boolean(window.matchMedia && window.matchMedia("(display-mode: standalone)").matches);
}

// Why the reading cannot be checked here, or null when it can. Pure, so test_pwa.js covers it.
//
// installed_app: in a home-screen web app the recogniser is exposed on window but never
//   prompts and never fires — the host process has no NSSpeechRecognitionUsageDescription
//   (WebKit bug 239816). available() returning true is exactly the trap. On iOS 26 the Add to
//   Home Screen sheet defaults "Open as Web App" to ON, so this is now the normal path.
// no_language: Apple's Dictation language list has never included Kazakh, and the Web Speech
//   recogniser is the same service. recognition.lang = "kk-KZ" is accepted and then yields
//   nothing, which without this falls through onend -> onEnded([]) -> maybeListen() -> forever.
function speechCheckBlockReason({ apiPresent, isIos, isStandalone, languageCode }) {
  // Kazakh is unchecked on every platform and always will be until an engine can do it well:
  // Apple has no Kazakh recogniser at all, and Whisper's ~56.5 WER on Kazakh would report
  // correctly-read words as misread more often than a dyslexic child actually errs. Reading
  // Kazakh works fully; only the scoring is off, and the screen says so.
  if (normalizeLangCode(languageCode) === "kk") return "no_language";
  // An installed iOS app cannot use the native recogniser, but it CAN record and have the
  // backend transcribe, so it is only blocked when it can do neither.
  const nativeUsable = apiPresent && !(isIos && isStandalone);
  if (!nativeUsable && !ServerSpeechGate.available()) return "no_api";
  return null;
}

/** The gate this device should use. Same interface either way; FocusReader never branches. */
function createSpeechGate(languageCode) {
  const nativeUsable = WebSpeechGate.available() && !(isIosDevice() && isStandaloneApp());
  if (nativeUsable) return new WebSpeechGate();
  return ServerSpeechGate.available() ? new ServerSpeechGate() : new WebSpeechGate();
}

if (typeof addEventListener === "function") {
  addEventListener("pointerdown", unlockSpeech, { once: true, capture: true });
  // iOS wedges the synthesis queue when the app is backgrounded mid-utterance: onend never
  // fires and every later speak() is silent. Drop the queue on the way out, not on the way
  // back. iOS only — on desktop this would kill background-tab playback, which works today.
  addEventListener("visibilitychange", () => {
    if (typeof document !== "undefined" && document.hidden && isIosDevice()) ttsStop();
  });
}

/* ================= Server-side recognition (iPhone) =================
   In an installed iOS home-screen app the Web Speech recogniser is present but permanently
   denied (WebKit reads NSSpeechRecognitionUsageDescription from a host bundle a web app does
   not have). This gate has the SAME interface as WebSpeechGate, so FocusReader does not know
   which one it is driving: it records the reading, posts it in short chunks, and emits each
   returned transcript through onSegment exactly as a native final result would arrive.

   Three things it must get right, all learned the hard way:
   - Every segment is its OWN complete recording, cut on silence. It used to run one recording
     with a 3000ms timeslice, which is wrong twice over. Only the first blob of a timesliced
     recording carries the container's initialisation segment — ftyp+moov for mp4, the EBML
     header and Tracks for webm — so every later blob was a bare fragment that no decoder can
     open, the backend returned 502 for all of them, and a reading was scored on its first three
     seconds with every word after that reported to the child as never heard. And a cut on the
     wall clock lands mid-word about as often as not, which turns a word the child read
     correctly into a prefix fragment that scores as a misreading. Cutting on silence is what
     SpeechGate.kt buys with SEGMENT_SILENCE_MILLIS; here it also makes each blob self-contained.
   - It does NOT pause while the app speaks. MediaRecorder.pause() keeps encoding audio on iOS
     (WebKit bug 279432, open), so pausing would put the app's own voice in the file and credit
     the child with the word the app just read to them. Instead every utterance's window is
     recorded and any segment overlapping one is dropped.
   - The AudioContext is created inside the user's tap, BEFORE the getUserMedia await. Created
     after, it stays suspended, the level meter reads a flat zero, and the 5s/9s help ladder
     then fires on every word no matter how well the child is reading. */

const SILENCE_RMS = 0.012;          // tune against a real room; log sampleLevel() to pick it

// How the reading is cut into segments. The poll is what watches the level; a segment ends once
// the room has been quiet for SEGMENT_SILENCE_MILLIS, which is SpeechGate.kt's value — long
// enough that the pause inside a hesitant word does not end the segment.
const LEVEL_POLL_MILLIS = 150;
const SEGMENT_SILENCE_MILLIS = 1500;
const MIN_SEGMENT_MILLIS = 900;     // below this there is nothing worth a round trip
const MAX_SEGMENT_MILLIS = 9000;    // a child who reads without pausing still gets checked

class ServerSpeechGate {
  constructor() {
    this.stream = null;
    this.recorder = null;
    this.audioContext = null;
    this.analyser = null;
    this.startedAt = 0;
    this.active = false;
    this.cutTimer = null;
    this.segmentStart = 0;
    this.quietSince = 0;
    this.segmentIndex = 0;
    this.mimeType = "";
    this.languageCode = "ru";
    // Transcripts are independent HTTP requests and finish out of order, but reviewReading is a
    // strictly monotonic alignment that cannot recover from a transposed block. Held here and
    // drained in capture order.
    this.settled = new Map();
    this.nextToEmit = 0;
  }

  static available() {
    return Boolean(navigator.mediaDevices && navigator.mediaDevices.getUserMedia &&
      typeof MediaRecorder !== "undefined");
  }

  // Must be called synchronously inside the tap handler, before any await.
  prepare() {
    if (this.audioContext) return;
    const Ctx = window.AudioContext || window.webkitAudioContext;
    if (Ctx) {
      this.audioContext = new Ctx();
      if (this.audioContext.state === "suspended") this.audioContext.resume();
    }
  }

  static pickMimeType() {
    // Safari produces mp4/aac and rejects webm; Chrome is the reverse. Let the engine choose
    // when neither matches rather than forcing a type it will refuse.
    for (const type of ["audio/mp4", "audio/aac", "audio/webm;codecs=opus", "audio/webm"]) {
      if (MediaRecorder.isTypeSupported && MediaRecorder.isTypeSupported(type)) return type;
    }
    return "";
  }

  async startContinuous(languageCode, { onPartial, onSegment, onEnded, onUnavailable }) {
    // One recorder for the whole reading, and never more than one. Without this guard a second
    // call stacked another live MediaRecorder and another microphone stream on the first, all of
    // them transcribing the same audio into the same transcript.
    if (this.active && this.recorder) return;
    // A gate that failed rather than one that is running: tear the dead one down first, with its
    // callbacks detached, or its onstop reopens the very session this call is replacing.
    if (this.recorder) {
      this.recorder.ondataavailable = null;
      this.recorder.onstop = null;
      this.recorder.onerror = null;
      this.stop();
    }
    this.prepare();
    try {
      this.stream = await navigator.mediaDevices.getUserMedia({
        audio: {
          echoCancellation: true,   // removes most of the app's own voice acoustically too
          noiseSuppression: true,
          autoGainControl: false    // off, so the absolute RMS floor means something
        }
      });
    } catch (error) {
      onUnavailable(error && error.name === "NotAllowedError" ? "denied" : "unavailable");
      return;
    }

    if (this.audioContext) {
      const source = this.audioContext.createMediaStreamSource(this.stream);
      this.analyser = this.audioContext.createAnalyser();
      this.analyser.fftSize = 2048;
      source.connect(this.analyser);
    }

    this.mimeType = ServerSpeechGate.pickMimeType();
    this.languageCode = languageCode;
    this.startedAt = Date.now();
    this.segmentIndex = 0;
    this.settled = new Map();
    this.nextToEmit = 0;
    this.active = true;

    if (!this.openRecorder({ onPartial, onSegment, onEnded })) {
      this.releaseStream();
      this.active = false;
      onUnavailable("unavailable");
      return;
    }

    // The level meter decides where a segment ends. Without an AudioContext there is no level to
    // read, so fall back to cutting on the clock — still one complete file per segment, just
    // blind to where the words are.
    this.cutTimer = setInterval(() => {
      if (!this.active || !this.recorder || this.recorder.state !== "recording") return;
      const elapsed = Date.now() - this.segmentStart;
      if (elapsed >= MAX_SEGMENT_MILLIS) { this.cutSegment({ onPartial, onSegment, onEnded }); return; }
      if (elapsed < MIN_SEGMENT_MILLIS) return;
      if (!this.analyser) {
        if (elapsed >= MAX_SEGMENT_MILLIS / 2) this.cutSegment({ onPartial, onSegment, onEnded });
        return;
      }
      if (this.sampleLevel() > SILENCE_RMS) { this.quietSince = 0; return; }
      if (this.quietSince === 0) { this.quietSince = Date.now(); return; }
      if (Date.now() - this.quietSince >= SEGMENT_SILENCE_MILLIS) {
        this.cutSegment({ onPartial, onSegment, onEnded });
      }
    }, LEVEL_POLL_MILLIS);
  }

  /**
   * Starts one recording. No timeslice: the single blob that arrives on stop is a complete,
   * self-contained file, which is the whole reason segments are cut rather than sliced.
   */
  openRecorder(callbacks) {
    let recorder;
    try {
      recorder = new MediaRecorder(this.stream, this.mimeType ? { mimeType: this.mimeType } : undefined);
    } catch {
      this.recorder = null;
      return false;
    }

    const index = this.segmentIndex++;
    const segmentStart = Date.now();
    this.segmentStart = segmentStart;
    this.quietSince = 0;

    // Nothing here masks the app's own voice any more, because focus mode never speaks: every
    // segment is the child. The mask it replaces was a standing hazard — each leak put the app's
    // reading of a word into the transcript and credited the child with having read it.
    recorder.ondataavailable = (event) => {
      if (!event.data || event.data.size === 0) { this.settle(index, "", callbacks.onSegment); return; }

      transcribeChunk(event.data, this.languageCode, this.mimeType).then((result) => {
        // A failed chunk is not silence. Said out loud so the help ladder is not triggered by a
        // gap the child did not cause; the words themselves are lost either way.
        if (result === null && this.active) callbacks.onPartial("");
        this.settle(index, result || "", callbacks.onSegment);
      });
    };
    recorder.onstop = () => {
      // Identity, as in WebSpeechGate: a recorder that has already been replaced was stopped at
      // a segment boundary, not because the reading ended, and answering for it would report the
      // microphone as closed while the next segment is recording.
      if (this.recorder !== recorder) return;
      this.recorder = null;
      this.active = false;
      callbacks.onEnded([]);
    };
    recorder.onerror = () => {
      if (this.recorder !== recorder) return;
      this.active = false;
      callbacks.onEnded([]);
    };

    try { recorder.start(); }
    catch { this.recorder = null; return false; }
    this.recorder = recorder;
    return true;
  }

  /** Ends the current recording and immediately opens the next one. */
  cutSegment(callbacks) {
    const finished = this.recorder;
    if (!finished || finished.state !== "recording") return;
    // Opened first, so `this.recorder` no longer points at `finished` by the time its onstop is
    // dispatched and the identity check above sees a superseded recorder.
    if (!this.openRecorder(callbacks)) { this.active = false; callbacks.onEnded([]); return; }
    try { finished.stop(); } catch { /* it will not deliver a blob; the next segment carries on */ }
  }

  /**
   * Holds a finished segment until every earlier one has been emitted.
   *
   * Every exit path settles its slot — a dropped segment and a failed request included — or the
   * drain stalls on it and nothing the child says afterwards is ever heard.
   */
  settle(index, text, onSegment) {
    this.settled.set(index, text);
    while (this.settled.has(this.nextToEmit)) {
      const text = this.settled.get(this.nextToEmit);
      this.settled.delete(this.nextToEmit);
      this.nextToEmit += 1;
      if (text.trim()) onSegment([text]);
    }
  }

  /** Root-mean-square input level, 0..1. Drives the silence ladder in place of interim results. */
  sampleLevel() {
    if (!this.analyser) return 0;
    const buffer = new Float32Array(this.analyser.fftSize);
    this.analyser.getFloatTimeDomainData(buffer);
    let sum = 0;
    for (const value of buffer) sum += value * value;
    return Math.sqrt(sum / buffer.length);
  }

  isSpeaking() { return this.sampleLevel() > SILENCE_RMS; }

  /**
   * The reader is done. The last recording is stopped rather than discarded, so its blob still
   * arrives through ondataavailable and the final words reach the review — that is the whole
   * difference between "stop" and "cancel" here.
   *
   * No onEnded follows: the caller asked for this, and it has already closed the session and
   * asked for the review itself. The identity check in onstop is what keeps it quiet.
   */
  stop() {
    if (this.cutTimer) { clearInterval(this.cutTimer); this.cutTimer = null; }
    const finished = this.recorder;
    this.recorder = null;
    this.active = false;
    if (finished && finished.state !== "inactive") {
      try { finished.stop(); } catch { /* already stopped */ }
    }
    this.releaseStream();
  }

  releaseStream() {
    if (this.stream) {
      this.stream.getTracks().forEach((track) => track.stop());
      this.stream = null;
    }
  }

  release() {
    this.active = false;
    this.stop();
    if (this.audioContext) { try { this.audioContext.close(); } catch { /* fine */ } this.audioContext = null; }
  }
}

/**
 * Returns the transcript, "" for genuine silence, or null when the request itself failed.
 *
 * Tried twice. A failed chunk is words the child really said, and by the time it reaches the
 * review there is nothing left to distinguish it from silence — the panel tells them they did
 * not read a word they read out loud. Recovering the words beats labelling the gap, and the
 * in-order drain means the extra round trip cannot reorder the transcript.
 */
async function transcribeChunk(blob, languageCode, mimeType) {
  const first = await postChunk(blob, languageCode, mimeType);
  if (first !== null) return first;
  return postChunk(blob, languageCode, mimeType);
}

async function postChunk(blob, languageCode, mimeType) {
  const form = new FormData();
  const extension = (mimeType || "").includes("webm") ? "webm" : "m4a";
  form.append("file", blob, "chunk." + extension);
  form.append("language_hint", normalizeLangCode(languageCode));
  try {
    const response = await fetch("/api/v1/transcribe", { method: "POST", body: form });
    if (!response.ok) return null;
    const json = await response.json();
    if (json.status !== "success") return null;
    return String(json.text || "");
  } catch {
    return null;
  }
}
