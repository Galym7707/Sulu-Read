// ponytail: single runnable check for the ported focus logic. Run: node test_focus.js
const fs = require("fs");
const vm = require("vm");
const assert = require("assert");

global.navigator = { language: "en" };
global.window = { APP_STATE: { languageCode: "kk" } };
for (const file of ["strings.js", "focus.js"]) {
  vm.runInThisContext(fs.readFileSync(require("path").join(__dirname, file), "utf8").replace('"use strict";', ""));
}

// buildFocusWords: punctuation trimmed for spoken, scenes split on sentence ends.
const words = buildFocusWords("Күн сайын кітап оқыған бала тілге бай болады. Сөзді буынға бөліп оқы.");
assert.strictEqual(words[7].display, "болады.");
assert.strictEqual(words[7].spoken, "болады");
assert.strictEqual(words[7].sceneIndex, 0);
assert.strictEqual(words[8].sceneIndex, 1);

// Word acceptance: exact, folds, tolerance, transpositions rejected.
assert.ok(isSpokenWordAccepted("книга", ["книга"]));
assert.ok(isSpokenWordAccepted("дуб", ["дуп"]));   // final devoicing
assert.ok(isSpokenWordAccepted("қала", ["кала"])); // Kazakh consonant fold
assert.ok(!isSpokenWordAccepted("дом", ["том"]));  // short word, zero tolerance
assert.ok(!isSpokenWordAccepted("күн", ["құн"]));  // phonemic Kazakh vowels stay distinct
assert.ok(isSpokenWordAccepted("күн", ["кун"]));   // Russian-mode transcript folds

// Review alignment: filler stepped over, misread reported with what was heard, skip reported.
const heard = (...words) => words.map((w) => heardToken(w));
const review = reviewReading(heard("эм", "кинга", "стол"), ["книга", "стол", "дом"]);
assert.strictEqual(review[0].outcome, ReadOutcome.Misread);
assert.strictEqual(review[0].heard, "кинга");
assert.strictEqual(review[1].outcome, ReadOutcome.Correct);
assert.strictEqual(review[2].outcome, ReadOutcome.Silent);

// Hypotheses -> per-word alternatives. The best hypothesis is the spine; the others contribute
// only where they disagree, and at the position where they disagree.
const tokens = tokensWithAlternatives(["кинга на столе", "книга на столе"]);
assert.deepStrictEqual(tokens.map((t) => t.text), ["кинга", "на", "столе"]);
assert.deepStrictEqual(tokens[0].alternatives, ["книга"]);
assert.deepStrictEqual(tokens[1].alternatives, []);

// A hypothesis with a word missing still lines up after the gap. Pairing by position would put
// "столе" against "на" — an alternative on the wrong word is how one turns into a false accept.
const gapped = tokensWithAlternatives(["книга на столе", "книга столе"]);
assert.deepStrictEqual(gapped[1].alternatives, []);
assert.deepStrictEqual(gapped[2].alternatives, []);

// An alternative equal to the best guess is not kept; the list is capped at four.
assert.deepStrictEqual(tokensWithAlternatives(["Книга", "книга,", "кинга"])[0].alternatives, ["кинга"]);
assert.strictEqual(tokensWithAlternatives(["а", "б", "в", "г", "д", "е", "ж"])[0].alternatives.length, 4);
assert.deepStrictEqual(tokensWithAlternatives([]), []);
assert.deepStrictEqual(tokensWithAlternatives(["   "]), []);

// The whole point: a later hypothesis rescues a reading the first guess got wrong — but the
// panel still reports what the engine actually settled on.
const rescued = reviewReading([heardToken("кинга", ["книга"])], ["книга"]);
assert.strictEqual(rescued[0].outcome, ReadOutcome.Correct);
assert.strictEqual(rescued[0].heard, "кинга");

// Alternatives widen what counts as a correct reading and nothing else. A stray token carrying a
// lucky alternative must still be stepped over, not charged to a word further down the page.
const stray = reviewReading([heardToken("мама"), heardToken("эм", ["это"])], ["мама", "мыла", "раму"]);
assert.deepStrictEqual(stray.map((r) => r.outcome),
  [ReadOutcome.Correct, ReadOutcome.Silent, ReadOutcome.Silent]);

// mistakesFrom: a word corrected on the last attempt drops off the list.
const m = mistakesFrom([
  { word: "дом", heard: "том", outcome: ReadOutcome.Misread },
  { word: "дом", heard: "дом", outcome: ReadOutcome.Correct }
]);
assert.strictEqual(m.length, 0);

// Ladder: deep step collects the word, three deep words in a row suggest a pause.
let ladder = newLadderState();
ladder = ladderOnHelpRequested(ladder, FocusStep.Letters);
ladder = ladderOnFocusMoved(ladder, 1, "слово1", 10);
assert.deepStrictEqual(ladder.triggerWords, ["слово1"]);
ladder = ladderOnHelpRequested(ladder, FocusStep.Letters);
ladder = ladderOnFocusMoved(ladder, 2, "слово2", 10);
ladder = ladderOnHelpRequested(ladder, FocusStep.Meaning);
ladder = ladderOnFocusMoved(ladder, 3, "слово3", 10);
assert.strictEqual(ladder.suggestPause, true);

// Letter names: Kazakh names, not Russian, for a Kazakh reader.
assert.deepStrictEqual(letterNamesFor("бата", "kk"), ["бе", "а", "те", "а"]);
assert.deepStrictEqual(letterNamesFor("бат", "ru"), ["бэ", "а", "тэ"]);

// extractTrainingWords: >=4 letters, distinct, capped at 40.
assert.deepStrictEqual(extractTrainingWords("кот кітап кітап балалар"), ["кітап", "балалар"]);

console.log("all focus logic checks passed");
