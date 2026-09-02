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

function normalizeForMatch(raw) {
  return [...raw.toLowerCase()].filter(isLetterOrDigit).join("");
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
  // Two different numbers are a misreading of each other, not two unrelated things. On letters
  // alone "5" and "шесть" share nothing, so the alignment would rather report the number as
  // never heard than as read wrong — and only the second tells the reader anything.
  if (isDigits(normalizedTarget) || isDigits(normalizedHeard)) {
    if (numeralDigits([target]) !== null && numeralDigits([heard]) !== null) return true;
  }
  const budget = Math.floor(Math.max(normalizedTarget.length, normalizedHeard.length) / 2);
  return editDistance(normalizedTarget, normalizedHeard) <= budget;
}

function tokenizeTranscript(transcript) {
  return transcript.trim().split(/\s+/).filter(Boolean);
}

/* ---------------- Numerals.kt ---------------- */

// Reads a number the way a child says it, so "5" and "пять" can be told to be the same thing.
// Ported 1:1 from focus/Numerals.kt: cardinals only, 0..999999, all three languages at once.
// Until this existed, targets written in digits were dropped from the review altogether and a
// child who read them correctly was never credited.
const NumKind = { ZERO: 0, UNIT: 1, TEEN: 2, TENS: 3, HUNDREDS: 4, MUL_HUNDRED: 5, MUL_THOUSAND: 6 };
const NumState = { Start: 0, Unit: 1, Hundreds: 2, Tens: 3, Closed: 4 };

// The Kazakh letters a Russian-mode recogniser cannot write, mapped to what it writes instead.
const KazakhLookalikes = { "ә": "а", "ө": "о", "ұ": "у", "ү": "у", "і": "ы", "қ": "к", "ғ": "г", "ң": "н", "һ": "х" };

// A null-prototype object: a token like "constructor" must look up as absent, not as a function.
const NumeralWords = (() => {
  const table = Object.create(null);
  const put = (kind, entries) => { for (const [word, value] of entries) table[word] = { value, kind }; };

  // Russian. "десять" is a TEEN: nothing follows it the way a unit follows "двадцать".
  put(NumKind.ZERO, [["ноль", 0]]);
  put(NumKind.UNIT, [["один", 1], ["одна", 1], ["одно", 1], ["два", 2], ["две", 2], ["три", 3],
    ["четыре", 4], ["пять", 5], ["шесть", 6], ["семь", 7], ["восемь", 8], ["девять", 9]]);
  put(NumKind.TEEN, [["десять", 10], ["одиннадцать", 11], ["двенадцать", 12], ["тринадцать", 13],
    ["четырнадцать", 14], ["пятнадцать", 15], ["шестнадцать", 16], ["семнадцать", 17],
    ["восемнадцать", 18], ["девятнадцать", 19]]);
  put(NumKind.TENS, [["двадцать", 20], ["тридцать", 30], ["сорок", 40], ["пятьдесят", 50],
    ["шестьдесят", 60], ["семьдесят", 70], ["восемьдесят", 80], ["девяносто", 90]]);
  put(NumKind.HUNDREDS, [["сто", 100], ["двести", 200], ["триста", 300], ["четыреста", 400],
    ["пятьсот", 500], ["шестьсот", 600], ["семьсот", 700], ["восемьсот", 800], ["девятьсот", 900]]);
  put(NumKind.MUL_THOUSAND, [["тысяча", 1000], ["тысячи", 1000], ["тысяч", 1000]]);

  // Kazakh. "он" is TENS, not TEEN: eleven is "он бір", so a unit may follow. Hundreds and
  // thousands are multipliers: "екі жүз", "бір мың".
  put(NumKind.ZERO, [["нөл", 0]]);
  put(NumKind.UNIT, [["бір", 1], ["екі", 2], ["үш", 3], ["төрт", 4], ["бес", 5], ["алты", 6],
    ["жеті", 7], ["сегіз", 8], ["тоғыз", 9]]);
  put(NumKind.TENS, [["он", 10], ["жиырма", 20], ["отыз", 30], ["қырық", 40], ["елу", 50],
    ["алпыс", 60], ["жетпіс", 70], ["сексен", 80], ["тоқсан", 90]]);
  put(NumKind.MUL_HUNDRED, [["жүз", 100]]);
  put(NumKind.MUL_THOUSAND, [["мың", 1000]]);

  // English.
  put(NumKind.ZERO, [["zero", 0]]);
  put(NumKind.UNIT, [["one", 1], ["two", 2], ["three", 3], ["four", 4], ["five", 5], ["six", 6],
    ["seven", 7], ["eight", 8], ["nine", 9]]);
  put(NumKind.TEEN, [["ten", 10], ["eleven", 11], ["twelve", 12], ["thirteen", 13], ["fourteen", 14],
    ["fifteen", 15], ["sixteen", 16], ["seventeen", 17], ["eighteen", 18], ["nineteen", 19]]);
  put(NumKind.TENS, [["twenty", 20], ["thirty", 30], ["forty", 40], ["fifty", 50], ["sixty", 60],
    ["seventy", 70], ["eighty", 80], ["ninety", 90]]);
  put(NumKind.MUL_HUNDRED, [["hundred", 100]]);
  put(NumKind.MUL_THOUSAND, [["thousand", 1000]]);

  // Every Kazakh numeral is also entered as a Russian-mode recogniser writes it: "төрт" and
  // "торт" both read as 4. Object.entries snapshots the keys, so adding during the loop is safe.
  for (const [word, numeral] of Object.entries(table)) {
    const folded = [...word].map((ch) => KazakhLookalikes[ch] || ch).join("");
    if (folded !== word && !(folded in table)) table[folded] = numeral;
  }
  return table;
})();

function isDigits(value) { return /^\p{Nd}+$/u.test(value); }

// One word per element, however they arrived. Splits on hyphens as well as whitespace:
// "twenty-five" is one token to the transcript splitter and two words to the parser, and
// normalizeForMatch would otherwise glue it into "twentyfive", which is in no table.
function numeralPieces(tokens) {
  return tokens
    .flatMap((token) => token.split(/[\s\-\u2011\u2013\u2014]+/))
    .map(normalizeForMatch)
    .filter(Boolean);
}

// The number these words spell, or null. Strict about order: "двадцать три" is 23, but
// "три двадцать" and "два три" are two numbers each and return null — a child who read the parts
// of a number out of order did not read the number.
function numeralValue(tokens) {
  const pieces = numeralPieces(tokens);
  if (pieces.length === 0) return null;
  if (pieces.length === 1 && isDigits(pieces[0])) {
    const parsed = parseInt(pieces[0], 10);
    return Number.isNaN(parsed) ? null : parsed;
  }

  let total = 0, group = 0, hundreds = 0, pendingUnit = 0;
  let state = NumState.Start;
  let thousandsUsed = false;

  for (const piece of pieces) {
    const numeral = NumeralWords[piece];
    if (!numeral) return null;
    switch (numeral.kind) {
      case NumKind.ZERO:
        return pieces.length === 1 ? 0 : null;
      case NumKind.UNIT:
        if (state === NumState.Start) { pendingUnit = numeral.value; state = NumState.Unit; }
        else if (state === NumState.Hundreds || state === NumState.Tens) { group += numeral.value; state = NumState.Closed; }
        else return null;
        break;
      case NumKind.TEEN:
        if (state === NumState.Start || state === NumState.Hundreds) { group += numeral.value; state = NumState.Closed; }
        else return null;
        break;
      case NumKind.TENS:
        if (state === NumState.Start || state === NumState.Hundreds) { group += numeral.value; state = NumState.Tens; }
        else return null;
        break;
      case NumKind.HUNDREDS:
        if (state === NumState.Start) { hundreds = numeral.value; state = NumState.Hundreds; }
        else return null;
        break;
      case NumKind.MUL_HUNDRED:
        // "жүз" alone is 100; "екі жүз" is 200.
        if (state === NumState.Start) { hundreds = 100; state = NumState.Hundreds; }
        else if (state === NumState.Unit) { hundreds = pendingUnit * 100; pendingUnit = 0; state = NumState.Hundreds; }
        else return null;
        break;
      case NumKind.MUL_THOUSAND: {
        if (thousandsUsed) return null;
        // "тысяча" alone is 1000; anything already gathered multiplies it.
        const multiplier = state === NumState.Start ? 1 : state === NumState.Unit ? pendingUnit : hundreds + group;
        total += multiplier * 1000;
        group = 0; hundreds = 0; pendingUnit = 0;
        state = NumState.Start;
        thousandsUsed = true;
        break;
      }
    }
  }
  // A unit still pending is the whole group: "пять" on its own, or "мың бес".
  return total + hundreds + group + pendingUnit;
}

function numeralDigits(tokens) {
  const value = numeralValue(tokens);
  return value === null ? null : String(value);
}

function isSpokenWordAccepted(target, heardAlternatives) {
  const normalizedTarget = normalizeForMatch(target);
  if (!fold(normalizedTarget, false)) return false;
  return heardAlternatives.flatMap(candidatesFrom).some((heard) => {
    const normalizedHeard = normalizeForMatch(heard);
    // A number is compared as a number, not as letters: "5" and "пять" are the same reading.
    // Only when one side is written in digits — two words stay on the letter rules below, or
    // "он" (he) and "он" (Kazakh ten) would be judged by value rather than by what was on the page.
    if (isDigits(normalizedTarget) || isDigits(normalizedHeard)) {
      const targetNumber = numeralDigits([target]);
      const heardNumber = numeralDigits([heard]);
      if (targetNumber !== null && heardNumber !== null) return targetNumber === heardNumber;
    }
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

// A number can span several words on either side: "25" on the page is "двадцать пять" from the
// microphone, and "двадцать пять" on the page comes back from most engines as "25". Six words
// covers "бір мың тоғыз жүз тоқсан бес" — 1995 in Kazakh. A span move is encoded above the plain
// moves as base + targetSpan * 8 + tokenSpan.
const MAX_NUMERAL_SPAN = 6;
const MOVE_NUMERAL_BASE = 16;

// Whole-session alignment of transcript tokens against the words the reader visited. Numbers are
// aligned as numbers: a run of up to MAX_NUMERAL_SPAN words on one side may pair with a run on the
// other when both spell the same value and one side is written in digits.
// ponytail: plain O(targets × tokens) alignment, sized for one page of text.
function reviewReading(spokenTokens, targets) {
  if (targets.length === 0 || spokenTokens.length === 0) return [];
  const targetCount = targets.length;
  const tokenCount = spokenTokens.length;
  const width = tokenCount + 1;
  // The whole table is kept rather than one row: a numeral span reaches back several rows.
  const cost = new Int32Array((targetCount + 1) * width);
  const moves = new Int8Array((targetCount + 1) * width);

  for (let ki = 1; ki <= tokenCount; ki++) { cost[ki] = ki * SKIP_COST; moves[ki] = MOVE_SKIP_TOKEN; }

  for (let ti = 1; ti <= targetCount; ti++) {
    cost[ti * width] = ti * SKIP_COST;
    moves[ti * width] = MOVE_SKIP_TARGET;
    for (let ki = 1; ki <= tokenCount; ki++) {
      const target = targets[ti - 1];
      const token = spokenTokens[ki - 1];
      const accepted = isSpokenWordAccepted(target, [token]);
      const pairingCost = accepted ? 0 : (isPlausibleMisreading(target, token) ? SUBSTITUTION_COST : UNRELATED_COST);
      const diagonal = cost[(ti - 1) * width + (ki - 1)] + pairingCost;
      const skipTarget = cost[(ti - 1) * width + ki] + SKIP_COST;
      const skipToken = cost[ti * width + (ki - 1)] + SKIP_COST;
      let best = Math.min(diagonal, skipTarget, skipToken);
      let move =
        best === diagonal && accepted ? MOVE_MATCH :
        best === diagonal && pairingCost === SUBSTITUTION_COST ? MOVE_SUBSTITUTE :
        best === skipTarget ? MOVE_SKIP_TARGET :
        best === skipToken ? MOVE_SKIP_TOKEN : MOVE_SUBSTITUTE;

      // The digit side of a numeral pairing is always a single element at the end of its run,
      // which is this cell — so a cell with no digits on either side cannot end a span.
      if (isDigits(normalizeForMatch(target)) || isDigits(normalizeForMatch(token))) {
        for (let targetSpan = 1; targetSpan <= Math.min(MAX_NUMERAL_SPAN, ti); targetSpan++) {
          for (let tokenSpan = 1; tokenSpan <= Math.min(MAX_NUMERAL_SPAN, ki); tokenSpan++) {
            if (targetSpan === 1 && tokenSpan === 1) continue;
            const targetRun = targets.slice(ti - targetSpan, ti);
            const tokenRun = spokenTokens.slice(ki - tokenSpan, ki);
            const hasDigitSide =
              (targetSpan === 1 && isDigits(normalizeForMatch(targetRun[0]))) ||
              (tokenSpan === 1 && isDigits(normalizeForMatch(tokenRun[0])));
            if (!hasDigitSide) continue;
            const targetNumber = numeralDigits(targetRun);
            if (targetNumber === null) continue;
            const tokenNumber = numeralDigits(tokenRun);
            if (tokenNumber === null || tokenNumber !== targetNumber) continue;
            const candidate = cost[(ti - targetSpan) * width + (ki - tokenSpan)];
            if (candidate < best) { best = candidate; move = MOVE_NUMERAL_BASE + targetSpan * 8 + tokenSpan; }
          }
        }
      }

      cost[ti * width + ki] = best;
      moves[ti * width + ki] = move;
    }
  }

  const reviews = new Array(targetCount).fill(null);
  let ti = targetCount, ki = tokenCount;
  while (ti > 0) {
    const move = moves[ti * width + ki];
    if (move >= MOVE_NUMERAL_BASE) {
      const targetSpan = Math.floor((move - MOVE_NUMERAL_BASE) / 8);
      const tokenSpan = (move - MOVE_NUMERAL_BASE) % 8;
      const heard = spokenTokens.slice(ki - tokenSpan, ki).join(" ");
      for (let index = ti - targetSpan; index < ti; index++) {
        reviews[index] = { word: targets[index], heard, outcome: ReadOutcome.Correct };
      }
      ti -= targetSpan; ki -= tokenSpan;
    } else if (move === MOVE_MATCH || move === MOVE_SUBSTITUTE) {
      reviews[ti - 1] = {
        word: targets[ti - 1],
        heard: spokenTokens[ki - 1],
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
