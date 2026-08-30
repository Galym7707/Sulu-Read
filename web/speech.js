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

function canSpeakLanguage(languageCode) {
  if (!window.speechSynthesis) return false;
  if (cachedVoices.length === 0) return true; // voices not loaded yet: stay optimistic, as the app does
  return findBestVoice(languageCode) !== null;
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
  // letter-sound correspondence that is worse than silence. The caller shows tts_voice_missing.
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

class WebSpeechGate {
  constructor() {
    this.recognition = null;
    this.active = false;
    this.request = null;
    this.endedFired = false;
  }

  static available() {
    return Boolean(window.SpeechRecognition || window.webkitSpeechRecognition);
  }

  prepare() { /* browser engines need no warm-up bind */ }

  // One session, kept open; finals stream out as segments while interims drive the live
  // transcript — the browser's continuous mode is the segmented session the app asks for.
  startContinuous(languageCode, { onPartial, onSegment, onEnded, onUnavailable }) {
    const Ctor = window.SpeechRecognition || window.webkitSpeechRecognition;
    if (!Ctor) { onUnavailable(); return; }
    this.stopInternal();

    const recognition = new Ctor();
    recognition.lang = localeFor(languageCode);
    recognition.continuous = true;
    recognition.interimResults = true;
    recognition.maxAlternatives = 10;

    this.endedFired = false;
    let unavailable = false;

    recognition.onresult = (event) => {
      let interim = "";
      for (let i = event.resultIndex; i < event.results.length; i++) {
        const result = event.results[i];
        if (result.isFinal) {
          const hypotheses = [];
          for (let j = 0; j < result.length; j++) hypotheses.push(result[j].transcript);
          onSegment(hypotheses);
        } else {
          interim += result[0].transcript + " ";
        }
      }
      if (interim.trim()) onPartial(interim);
    };

    recognition.onerror = (event) => {
      // Permission refusals retire the microphone; everything else ends this one session and
      // the caller opens another — a dropped network call must not end the analysis for good.
      if (event.error === "not-allowed" || event.error === "service-not-allowed") {
        unavailable = true;
      }
    };

    recognition.onend = () => {
      this.active = false;
      if (this.endedFired) return;
      this.endedFired = true;
      if (unavailable) onUnavailable();
      else onEnded([]);
    };

    this.recognition = recognition;
    this.active = true;
    try { recognition.start(); }
    catch { this.active = false; onUnavailable(); }
  }

  // Stop, not abort: stopping lets pending finals arrive, which is the difference between the
  // last words of a reading being reviewed and being thrown away.
  stop() {
    if (this.recognition && this.active) {
      try { this.recognition.stop(); } catch { /* already stopped */ }
    }
  }

  stopInternal() {
    if (this.recognition) {
      this.endedFired = true; // silence the old session's onend
      try { this.recognition.abort(); } catch { /* fine */ }
      this.recognition = null;
      this.active = false;
    }
  }

  release() { this.stopInternal(); }
}

/* ---------------- iOS: gesture unlock + voice warm-up ---------------- */

// MITIGATION, not a guaranteed fix. iOS requires a user gesture for speechSynthesis.speak();
// the sources disagree on whether one unlock covers the page or every playback. Focus mode's
// first utterance comes from the 5s/9s silence ladder — a timer — so without this a child who
// waits hears nothing. Capture phase, so it runs before app handlers. The real backstop is
// ttsWatchdog below: if onstart never fires, nothing is playing, whatever the engine claimed.
//
// It doubles as the voice-list warm-up. getVoices() is empty on iOS until the list loads and
// onvoiceschanged sometimes never fires, so canSpeakLanguage()'s optimistic branch reports
// Kazakh TTS as available on a device that has no Kazakh voice. Warming it on the very first
// tap means voices are loaded long before any FocusReader is constructed.
let ttsBlocked = false;

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

// The watchdog the unlock cannot replace. Returns a cancel function.
const TTS_START_WATCHDOG_MILLIS = 1500;
function ttsWatchdog(utterance, onSilent) {
  if (!utterance) { onSilent(); return () => {}; }
  let started = false;
  const previous = utterance.onstart;
  const timer = setTimeout(() => {
    if (!started) { ttsBlocked = true; onSilent(); }
  }, TTS_START_WATCHDOG_MILLIS);
  utterance.onstart = (event) => { started = true; clearTimeout(timer); if (previous) previous(event); };
  return () => clearTimeout(timer);
}
function ttsIsBlocked() { return ttsBlocked; }

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

   Two things it must get right, both learned the hard way:
   - It does NOT pause while the app speaks. MediaRecorder.pause() keeps encoding audio on iOS
     (WebKit bug 279432, open), so pausing would put the app's own voice in the file and credit
     the child with the word the app just read to them. Instead every utterance's window is
     recorded and any chunk overlapping one is dropped.
   - The AudioContext is created inside the user's tap, BEFORE the getUserMedia await. Created
     after, it stays suspended, the level meter reads a flat zero, and the 5s/9s help ladder
     then fires on every word no matter how well the child is reading. */

const CHUNK_MILLIS = 3000;          // near-live: the check runs about one chunk behind
const SILENCE_RMS = 0.012;          // tune against a real room; log sampleLevel() to pick it

class ServerSpeechGate {
  constructor() {
    this.stream = null;
    this.recorder = null;
    this.audioContext = null;
    this.analyser = null;
    this.speakWindows = [];   // {start, end} ms since capture began, while the APP was talking
    this.startedAt = 0;
    this.active = false;
    this.chunkIndex = 0;
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

    const mimeType = ServerSpeechGate.pickMimeType();
    try {
      this.recorder = new MediaRecorder(this.stream, mimeType ? { mimeType } : undefined);
    } catch {
      this.releaseStream();
      onUnavailable("unavailable");
      return;
    }

    this.startedAt = Date.now();
    this.speakWindows = [];
    this.chunkIndex = 0;
    this.active = true;

    this.recorder.ondataavailable = (event) => {
      if (!event.data || event.data.size === 0) return;
      const index = this.chunkIndex++;
      const chunkStart = index * CHUNK_MILLIS;
      const chunkEnd = chunkStart + CHUNK_MILLIS;
      // Drop any chunk the app was talking through for more than half its length.
      const spoken = this.speakWindows.reduce((total, w) =>
        total + Math.max(0, Math.min(w.end, chunkEnd) - Math.max(w.start, chunkStart)), 0);
      if (spoken > CHUNK_MILLIS / 2) return;

      transcribeChunk(event.data, languageCode, mimeType).then((result) => {
        if (!this.active && result === null) return;
        if (result === null) { onPartial(""); return; }   // a failed chunk is not silence
        if (result.trim()) onSegment([result]);
      });
    };
    this.recorder.onstop = () => { this.active = false; onEnded([]); };
    this.recorder.onerror = () => { this.active = false; onEnded([]); };

    try { this.recorder.start(CHUNK_MILLIS); }
    catch { this.releaseStream(); this.active = false; onUnavailable("unavailable"); }
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

  /** Record that the APP was talking, so the chunks covering it are discarded. */
  markAppSpeech(start, end) {
    if (!this.startedAt) return;
    this.speakWindows.push({ start: start - this.startedAt, end: end - this.startedAt });
  }

  stop() {
    if (this.recorder && this.recorder.state !== "inactive") {
      try { this.recorder.stop(); } catch { /* already stopped */ }
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

/** Returns the transcript, "" for genuine silence, or null when the request itself failed. */
async function transcribeChunk(blob, languageCode, mimeType) {
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
