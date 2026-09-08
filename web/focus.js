// Focus-mode logic, ported 1:1 from app/src/main/java/com/example/sulu_read/focus/*.kt
// and audio/NaturalTts.kt (language detection). Pure functions, no DOM.
"use strict";

/* ---------------- FocusScenes.kt ---------------- */

const SENTENCE_END_CHARACTERS = ".!?…:;";
const COMMA_CHARACTERS = ",";
const MIN_WORDS_BEFORE_COMMA_BREAK = 6;

function isLetterOrDigit(ch) {
  return /[\p{L}\p{Nd}]/u.test(ch);
}

function buildFocusWords(text) {
  const focusWords = [];
  let sceneIndex = 0;
  let wordsInScene = 0;
  for (const token of text.split(/\s+/)) {
    const display = token.trim();
    if (!display || ![...display].some(isLetterOrDigit)) continue;
    // trim leading/trailing non-letterOrDigit
    let start = 0, end = display.length;
    while (start < end && !isLetterOrDigit(display[start])) start++;
    while (end > start && !isLetterOrDigit(display[end - 1])) end--;
    const spoken = display.slice(start, end);
    focusWords.push({ display, spoken, sceneIndex });
    wordsInScene += 1;
    const last = display[display.length - 1];
    const endsScene = SENTENCE_END_CHARACTERS.includes(last) ||
      (COMMA_CHARACTERS.includes(last) && wordsInScene >= MIN_WORDS_BEFORE_COMMA_BREAK);
    if (endsScene) { sceneIndex += 1; wordsInScene = 0; }
  }
  return focusWords;
}

/* ---------------- FocusLadder.kt ---------------- */

const FocusStep = { Focus: 0, Sweep: 1, Letters: 2, Meaning: 3 };
const SWEEP_FLASH_MILLIS = 200;
const NUDGE_AFTER_MILLIS = 5000;
const OFFER_HELP_AFTER_MILLIS = 9000;
const MASTERY_TARGET = 0.8;
const MIN_TTS_RATE = 0.6;
const MAX_TTS_RATE = 1.0;
const ACCURACY_WINDOW = 20;
const PAUSE_AFTER_DEEP_WORDS = 3;
const DEEP_STEPS = new Set([FocusStep.Letters, FocusStep.Meaning]);

function newLadderState() {
  return {
    wordIndex: 0, step: FocusStep.Focus, recentCleanReads: [],
    consecutiveDeepWords: 0, triggerWords: [], suggestPause: false
  };
}

function ladderAdvance(state, currentWord, wordCount) {
  const wasDeep = DEEP_STEPS.has(state.step);
  const deepStreak = wasDeep ? state.consecutiveDeepWords + 1 : 0;
  const shouldPause = deepStreak >= PAUSE_AFTER_DEEP_WORDS;
  return {
    ...state,
    wordIndex: Math.min(state.wordIndex + 1, wordCount),
    step: FocusStep.Focus,
    recentCleanReads: [...state.recentCleanReads, state.step === FocusStep.Focus].slice(-ACCURACY_WINDOW),
    consecutiveDeepWords: shouldPause ? 0 : deepStreak,
    triggerWords: wasDeep ? [...state.triggerWords, currentWord] : state.triggerWords,
    suggestPause: shouldPause
  };
}

// The reader moved the focus themselves. Moving on scores the word; going back scores nothing
// but still collects a deep-help word for later practice.
function ladderOnFocusMoved(state, target, currentWord, wordCount) {
  const clamped = Math.max(0, Math.min(target, wordCount));
  if (clamped === state.wordIndex) return state;
  if (clamped < state.wordIndex) {
    return {
      ...state,
      wordIndex: clamped,
      step: FocusStep.Focus,
      triggerWords: DEEP_STEPS.has(state.step) ? [...state.triggerWords, currentWord] : state.triggerWords
    };
  }
  return { ...ladderAdvance(state, currentWord, wordCount), wordIndex: clamped };
}

function ladderOnHelpRequested(state, minimumStep) {
  if (minimumStep <= state.step) return state;
  return { ...state, step: minimumStep };
}

function ladderOnNudgeFinished(state) {
  if (state.step !== FocusStep.Sweep) return state;
  return { ...state, step: FocusStep.Focus };
}

function ladderOnPauseAcknowledged(state) {
  return { ...state, suggestPause: false, consecutiveDeepWords: 0 };
}

function ladderMasteryShare(state) {
  if (state.recentCleanReads.length === 0) return 0;
  return state.recentCleanReads.filter(Boolean).length / state.recentCleanReads.length;
}

function ladderTtsRate(state) {
  const progress = Math.min(1, ladderMasteryShare(state) / MASTERY_TARGET);
  return MIN_TTS_RATE + (MAX_TTS_RATE - MIN_TTS_RATE) * progress;
}

/* ---------------- NaturalTts.kt: language detection ---------------- */

const KAZAKH_SPECIFIC_LETTERS = new Set("әғқңөұүһіӘҒҚҢӨҰҮҺІ");

function detectSpeechLanguageCode(text, fallbackCode) {
  const normalizedFallback = normalizeLangCode(fallbackCode);
  const letters = [...text].filter((ch) => /\p{L}/u.test(ch));
  if (letters.length === 0) return normalizedFallback;
  if (letters.some((ch) => KAZAKH_SPECIFIC_LETTERS.has(ch))) return "kk";
  const latinCount = letters.filter((ch) => /[A-Za-z]/.test(ch)).length;
  const cyrillicCount = letters.filter((ch) => ch >= "Ѐ" && ch <= "ӿ").length;
  if (latinCount > cyrillicCount) return "en";
  if (cyrillicCount > latinCount) return normalizedFallback === "kk" ? "kk" : "ru";
  return normalizedFallback;
}

/* ---------------- WordMatch.kt ---------------- */

const SharedFolds = { "ё": "е", "й": "и", "ъ": "ь" };
const KazakhConsonantFolds = { "қ": "к", "ғ": "г", "ң": "н", "һ": "х" };
const KazakhVowelFolds = { "ө": "о", "ұ": "у", "ү": "у", "ә": "а", "і": "ы" };
const KazakhSpecificFoldLetters = new Set([
  ...Object.keys(KazakhConsonantFolds), ...Object.keys(KazakhVowelFolds)
]);

function isRussianModeTranscript(target, heard) {
  return [...target].some((c) => KazakhSpecificFoldLetters.has(c)) &&
    ![...heard].some((c) => KazakhSpecificFoldLetters.has(c));
}

const FinalDevoiced = { "б": "п", "в": "ф", "г": "к", "д": "т", "ж": "ш", "з": "с" };
const LatinDigraphs = [["th", "t"], ["ph", "f"], ["gh", "g"], ["ck", "k"], ["kn", "n"], ["wr", "r"], ["wh", "w"]];
const LatinEquivalents = { w: "v", z: "s", q: "k", y: "i" };
const SoftCFollowers = new Set(["e", "i", "y"]);
const SHORT_WORD_MAX_LENGTH = 4;
const MEDIUM_WORD_MAX_LENGTH = 7;
const MEDIUM_WORD_TOLERANCE = 1;
const LONG_WORD_TOLERANCE = 2;

// Both of these are pure and both are called from inside reviewReading's O(targets × tokens)
// alignment, so every target's key was rebuilt once per token on the page — a per-character
// Unicode regex test, three allocations, then the whole digraph rewrite again. Android absorbs
// that under a JIT on a background thread; the web pays it on the thread that also has to
// answer the recogniser. Caching took a 200-word page from 622ms to 320ms. One page of text
// bounds the key set, so neither map is ever cleared.
const normalizedCache = new Map();
const phoneticCache = new Map();

function normalizeForMatch(raw) {
  const cached = normalizedCache.get(raw);
  if (cached !== undefined) return cached;
  const value = [...raw.toLowerCase()].filter(isLetterOrDigit).join("");
  normalizedCache.set(raw, value);
  return value;
}

function fold(normalized, foldKazakhVowels) {
  return [...normalized].map((ch) =>
    SharedFolds[ch] || KazakhConsonantFolds[ch] ||
    (foldKazakhVowels ? KazakhVowelFolds[ch] : undefined) || ch
  ).join("");
}

function toleranceFor(length) {
  if (length <= SHORT_WORD_MAX_LENGTH) return 0;
  if (length <= MEDIUM_WORD_MAX_LENGTH) return MEDIUM_WORD_TOLERANCE;
  return LONG_WORD_TOLERANCE;
}

function isTransposition(first, second) {
  return first !== second && [...first].sort().join("") === [...second].sort().join("");
}

function collapseRuns(value) {
  let out = "";
  for (const ch of value) if (!out.endsWith(ch)) out += ch;
  return out;
}

function isLatinWord(value) { return /[a-z]/.test(value); }

function phoneticKey(raw, foldKazakhVowels = false) {
  const cacheKey = (foldKazakhVowels ? "1" : "0") + raw;
  const cached = phoneticCache.get(cacheKey);
  if (cached !== undefined) return cached;
  const value = computePhoneticKey(raw, foldKazakhVowels);
  phoneticCache.set(cacheKey, value);
  return value;
}

function computePhoneticKey(raw, foldKazakhVowels) {
  const folded = fold(normalizeForMatch(raw), foldKazakhVowels);
  if (!folded) return "";
  let rewritten;
  if (isLatinWord(folded)) {
    let value = folded;
    for (const [from, to] of LatinDigraphs) value = value.split(from).join(to);
    rewritten = [...value].map((ch, i) => {
      if (ch !== "c") return LatinEquivalents[ch] || ch;
      return SoftCFollowers.has(value[i + 1]) ? "s" : "k";
    }).join("");
  } else {
    const devoicedLast = FinalDevoiced[folded[folded.length - 1]];
    rewritten = devoicedLast ? folded.slice(0, -1) + devoicedLast : folded;
  }
  return collapseRuns(rewritten);
}

function editDistance(first, second) {
  let previousRow = Array.from({ length: second.length + 1 }, (_, i) => i);
  for (let i = 1; i <= first.length; i++) {
    const currentRow = new Array(second.length + 1);
    currentRow[0] = i;
    for (let j = 1; j <= second.length; j++) {
      const substitutionCost = first[i - 1] === second[j - 1] ? 0 : 1;
      currentRow[j] = Math.min(currentRow[j - 1] + 1, previousRow[j] + 1, previousRow[j - 1] + substitutionCost);
    }
    previousRow = currentRow;
  }
  return previousRow[second.length];
}

function candidatesFrom(hypothesis) {
  const words = hypothesis.trim().split(/\s+/).filter(Boolean);
  return words.length <= 1 ? [hypothesis] : [...words, hypothesis];
}

function isPlausibleMisreading(target, heard) {
  const normalizedTarget = normalizeForMatch(target);
  const normalizedHeard = normalizeForMatch(heard);
  if (!normalizedTarget || !normalizedHeard) return false;
  const budget = Math.floor(Math.max(normalizedTarget.length, normalizedHeard.length) / 2);
  return editDistance(normalizedTarget, normalizedHeard) <= budget;
}

function tokenizeTranscript(transcript) {
  return transcript.trim().split(/\s+/).filter(Boolean);
}

// Enough to rescue a reading the engine's first guess got wrong, few enough that a word on the
// page is not matched against most of the language.
const MAX_ALTERNATIVES_PER_TOKEN = 4;

/**
 * One word of the transcript, and the other words the recogniser thought it might have been.
 * {text, alternatives}. `candidates` is everything worth testing against a word on the page.
 *
 * The engine is asked for ten hypotheses and, until this existed, nine of them were dropped one
 * line after they arrived. That is the difference that matters for an accented reader: the model
 * leans towards the pronunciation it was trained on, so the reading a child actually gave is
 * routinely the engine's second or third guess rather than its first — and scoring only the
 * first tells a child they misread a word they read correctly.
 */
function heardToken(text, alternatives = []) {
  return { text, alternatives, candidates: () => [text, ...alternatives] };
}

/** Tokens with no second opinion — a partial result, which carries only one hypothesis. */
function heardTokens(transcript) {
  return tokenizeTranscript(transcript).map((word) => heardToken(word));
}

/**
 * Turns one segment's hypotheses into tokens that carry the engine's other guesses.
 *
 * Hypotheses are whole utterances, but the review aligns a flat sequence of words, so they have
 * to become per-position alternatives before they are any use. The best hypothesis is the spine
 * — it decides how many tokens there are and what the reader is told they said — and each of the
 * others is lined up against it word by word, so a hypothesis that differs in one word
 * contributes that word at the right position instead of shifting everything after it.
 */
function tokensWithAlternatives(hypotheses) {
  const spine = tokenizeTranscript(hypotheses.length > 0 ? hypotheses[0] : "");
  if (spine.length === 0) return [];
  // Sets keep insertion order, so the better hypotheses' words stay at the front of the list.
  const alternatives = spine.map(() => new Set());
  for (const hypothesis of hypotheses.slice(1)) {
    alignWords(spine, tokenizeTranscript(hypothesis)).forEach((word, index) => {
      if (word !== null && !isSameWord(word, spine[index])) alternatives[index].add(word);
    });
  }
  return spine.map((text, index) =>
    heardToken(text, [...alternatives[index]].slice(0, MAX_ALTERNATIVES_PER_TOKEN)));
}

/**
 * For each word of `spine`, the word of `other` that lines up with it, or null.
 *
 * Word-level edit distance with a flat cost: pairing two words is free when they are the same
 * word and costs one when they are not, so the cheapest path through a hypothesis that swapped a
 * single word pairs that word up rather than treating it as a deletion and an insertion.
 */
function alignWords(spine, other) {
  const paired = new Array(spine.length).fill(null);
  if (other.length === 0) return paired;

  const costs = Array.from({ length: spine.length + 1 }, () => new Array(other.length + 1).fill(0));
  for (let i = 0; i <= spine.length; i++) costs[i][0] = i;
  for (let j = 0; j <= other.length; j++) costs[0][j] = j;
  for (let i = 1; i <= spine.length; i++) {
    for (let j = 1; j <= other.length; j++) {
      const substitution = isSameWord(spine[i - 1], other[j - 1]) ? 0 : 1;
      costs[i][j] = Math.min(
        costs[i - 1][j - 1] + substitution,
        costs[i - 1][j] + 1,
        costs[i][j - 1] + 1
      );
    }
  }

  let i = spine.length, j = other.length;
  while (i > 0 && j > 0) {
    const substitution = isSameWord(spine[i - 1], other[j - 1]) ? 0 : 1;
    if (costs[i][j] === costs[i - 1][j - 1] + substitution) { paired[i - 1] = other[j - 1]; i--; j--; }
    else if (costs[i][j] === costs[i - 1][j] + 1) i--;
    else j--;
  }
  return paired;
}

function isSameWord(first, second) {
  return normalizeForMatch(first) === normalizeForMatch(second);
}

function isSpokenWordAccepted(target, heardAlternatives) {
  const normalizedTarget = normalizeForMatch(target);
  if (!fold(normalizedTarget, false)) return false;
  return heardAlternatives.flatMap(candidatesFrom).some((heard) => {
    const normalizedHeard = normalizeForMatch(heard);
    const foldVowels = isRussianModeTranscript(normalizedTarget, normalizedHeard);
    const foldedTarget = fold(normalizedTarget, foldVowels);
    const foldedHeard = fold(normalizedHeard, foldVowels);
    const tolerance = toleranceFor(foldedTarget.length);
    if (!foldedHeard || !foldedTarget) return false;
    if (foldedHeard === foldedTarget) return true;
    if (isTransposition(foldedTarget, foldedHeard)) return false;
    const key = phoneticKey(target, foldVowels);
    if (key && phoneticKey(heard, foldVowels) === key) return true;
    return editDistance(foldedTarget, foldedHeard) <= tolerance;
  });
}

/* ---------------- ReadingReview.kt ---------------- */

const ReadOutcome = { Correct: "correct", Misread: "misread", Silent: "silent" };
const SUBSTITUTION_COST = 3;
const SKIP_COST = 2;
const UNRELATED_COST = 2 * SKIP_COST + 1;
const MOVE_MATCH = 0, MOVE_SUBSTITUTE = 1, MOVE_SKIP_TARGET = 2, MOVE_SKIP_TOKEN = 3;

// Whole-session alignment of transcript tokens against the words the reader visited.
// ponytail: plain O(targets × tokens) alignment, sized for one page of text.
function reviewReading(spokenTokens, targets) {
  if (targets.length === 0 || spokenTokens.length === 0) return [];
  const targetCount = targets.length;
  const tokenCount = spokenTokens.length;
  const moves = new Int8Array((targetCount + 1) * (tokenCount + 1));
  let previousRow = Array.from({ length: tokenCount + 1 }, (_, i) => i * SKIP_COST);

  for (let ti = 1; ti <= targetCount; ti++) {
    const currentRow = new Array(tokenCount + 1);
    currentRow[0] = ti * SKIP_COST;
    moves[ti * (tokenCount + 1)] = MOVE_SKIP_TARGET;
    for (let ki = 1; ki <= tokenCount; ki++) {
      const target = targets[ti - 1];
      const token = spokenTokens[ki - 1];
      // Every hypothesis the engine offered for this position counts towards accepting the
      // reading, but only its best guess decides whether a mismatch is a misreading of THIS word
      // or something unrelated. Letting the alternatives widen that budget too would pull filler
      // onto words the reader never reached, which is what UNRELATED_COST exists to prevent.
      const accepted = isSpokenWordAccepted(target, token.candidates());
      const pairingCost = accepted ? 0 : (isPlausibleMisreading(target, token.text) ? SUBSTITUTION_COST : UNRELATED_COST);
      const diagonal = previousRow[ki - 1] + pairingCost;
      const skipTarget = previousRow[ki] + SKIP_COST;
      const skipToken = currentRow[ki - 1] + SKIP_COST;
      const best = Math.min(diagonal, skipTarget, skipToken);
      currentRow[ki] = best;
      moves[ti * (tokenCount + 1) + ki] =
        best === diagonal && accepted ? MOVE_MATCH :
        best === diagonal && pairingCost === SUBSTITUTION_COST ? MOVE_SUBSTITUTE :
        best === skipTarget ? MOVE_SKIP_TARGET :
        best === skipToken ? MOVE_SKIP_TOKEN : MOVE_SUBSTITUTE;
    }
    previousRow = currentRow;
  }

  const reviews = new Array(targetCount).fill(null);
  let ti = targetCount, ki = tokenCount;
  while (ti > 0) {
    const move = moves[ti * (tokenCount + 1) + ki];
    if (move === MOVE_MATCH || move === MOVE_SUBSTITUTE) {
      reviews[ti - 1] = {
        word: targets[ti - 1],
        // The engine's own best guess, not whichever alternative rescued the match: the panel is
        // telling the reader what they were heard to say.
        heard: spokenTokens[ki - 1].text,
        outcome: move === MOVE_MATCH ? ReadOutcome.Correct : ReadOutcome.Misread
      };
      ti -= 1; ki -= 1;
    } else if (move === MOVE_SKIP_TARGET) {
      reviews[ti - 1] = { word: targets[ti - 1], heard: null, outcome: ReadOutcome.Silent };
      ti -= 1;
    } else {
      ki -= 1;
    }
  }
  return reviews.filter(Boolean);
}

// Each word judged by the last attempt at it; corrected words drop off the list.
function mistakesFrom(reviews) {
  const lastAttempts = new Map();
  for (const r of reviews) lastAttempts.set(normalizeForMatch(r.word), r);
  const seen = new Set();
  const out = [];
  for (const r of reviews) {
    const key = normalizeForMatch(r.word);
    if (seen.has(key)) continue;
    seen.add(key);
    const last = lastAttempts.get(key);
    if (last && last.outcome !== ReadOutcome.Correct) out.push(last);
  }
  return out;
}

function misreadWordsFrom(review) {
  const out = [];
  for (const r of review || []) {
    if (r.outcome === ReadOutcome.Misread && !out.includes(r.word)) out.push(r.word);
  }
  return out;
}

/* ---------------- LetterNames.kt ---------------- */

const RussianLetterNames = {
  "а": "а", "б": "бэ", "в": "вэ", "г": "гэ", "д": "дэ", "е": "е", "ё": "ё",
  "ж": "жэ", "з": "зэ", "и": "и", "й": "и краткое", "к": "ка", "л": "эль",
  "м": "эм", "н": "эн", "о": "о", "п": "пэ", "р": "эр", "с": "эс", "т": "тэ",
  "у": "у", "ф": "эф", "х": "ха", "ц": "цэ", "ч": "че", "ш": "ша", "щ": "ща",
  "ъ": "твёрдый знак", "ы": "ы", "ь": "мягкий знак", "э": "э", "ю": "ю", "я": "я"
};
const KazakhLetterNames = {
  "а": "а", "ә": "ә", "е": "е", "ё": "ё", "и": "и", "о": "о", "ө": "ө",
  "у": "у", "ұ": "ұ", "ү": "ү", "ы": "ы", "і": "і", "э": "э", "ю": "ю", "я": "я",
  "б": "бе", "в": "ве", "г": "ге", "д": "де", "ж": "же", "з": "зе",
  "к": "ке", "п": "пе", "т": "те", "ц": "це", "ч": "че",
  "қ": "қа", "ғ": "ға",
  "х": "ха", "һ": "һа", "ш": "ша", "щ": "ща",
  "л": "эл", "м": "эм", "н": "эн", "р": "эр", "с": "эс", "ф": "эф", "ң": "ең",
  "й": "қысқа и", "ъ": "айыру белгісі", "ь": "жіңішкелік белгісі"
};
const EnglishLetterNames = {
  a: "ay", b: "bee", c: "see", d: "dee", e: "ee", f: "ef", g: "gee", h: "aitch",
  i: "eye", j: "jay", k: "kay", l: "el", m: "em", n: "en", o: "oh", p: "pee",
  q: "cue", r: "ar", s: "ess", t: "tee", u: "you", v: "vee", w: "double-u",
  x: "ex", y: "why", z: "zee"
};

function letterNamesFor(word, languageCode) {
  const cyrillicNames = normalizeLangCode(languageCode) === "kk" ? KazakhLetterNames : RussianLetterNames;
  return [...word.toLowerCase()].filter(isLetterOrDigit)
    .map((ch) => EnglishLetterNames[ch] || cyrillicNames[ch] || ch);
}

/* ---------------- MainActivity.kt: extractTrainingWords ---------------- */

function extractTrainingWords(text) {
  const matches = text.match(/[A-Za-zА-Яа-яЁёӘәҒғҚқҢңӨөҰұҮүҺһІі]+/g) || [];
  const seen = new Set();
  const out = [];
  for (const w of matches) {
    if (w.length < 4) continue;
    const key = w.toLowerCase();
    if (seen.has(key)) continue;
    seen.add(key);
    out.push(w);
    if (out.length >= 40) break;
  }
  return out;
}

/* ---------------- PremiumReadingScreen.kt: word duration estimate ---------------- */

function estimateWordDurationMillis(word) {
  const readableLength = Math.max(1, [...word].filter(isLetterOrDigit).length);
  return Math.min(1350, Math.max(460, 280 + readableLength * 42));
}
