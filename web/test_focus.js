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

// Interims are the second opinion Chrome will not put in a final result. An interim that guessed
// the word the child actually said rescues the reading; one that agrees with the final adds
// nothing; and with no interims at all the tokens come through untouched.
const rescuedByInterim = withInterimAlternatives([heardToken("кинга")], [heardToken("книга")]);
assert.deepStrictEqual(rescuedByInterim[0].alternatives, ["книга"]);
assert.strictEqual(reviewReading(rescuedByInterim, ["книга"])[0].outcome, ReadOutcome.Correct);
assert.strictEqual(reviewReading(rescuedByInterim, ["книга"])[0].heard, "кинга");
assert.deepStrictEqual(withInterimAlternatives([heardToken("книга")], [heardToken("Книга,")])[0].alternatives, [],
  "an interim that agrees is not stored as an alternative");
assert.deepStrictEqual(withInterimAlternatives([heardToken("книга")], [])[0].alternatives, [],
  "no interims: unchanged, so a browser that suppresses them is no worse off");
// Aligned, not zipped: an interim that dropped a word must not shift onto the wrong position.
const shifted = withInterimAlternatives(
  heard("книга", "на", "столе"), heard("книга", "столе"));
assert.deepStrictEqual(shifted[1].alternatives, [], "\"столе\" must not become an alternative for \"на\"");

// mistakesFrom: a word corrected on the last attempt drops off the list.
const m = mistakesFrom([
  { word: "дом", heard: "том", outcome: ReadOutcome.Misread },
  { word: "дом", heard: "дом", outcome: ReadOutcome.Correct }
]);
assert.strictEqual(m.length, 0);

// Ladder: deep step collects the word, three deep words in a row suggest a pause.
let ladder = newLadderState();
ladder = ladderOnHelpRequested(ladder, FocusStep.Meaning);
ladder = ladderOnFocusMoved(ladder, 1, "слово1", 10);
assert.deepStrictEqual(ladder.triggerWords, ["слово1"]);
ladder = ladderOnHelpRequested(ladder, FocusStep.Meaning);
ladder = ladderOnFocusMoved(ladder, 2, "слово2", 10);
ladder = ladderOnHelpRequested(ladder, FocusStep.Meaning);
ladder = ladderOnFocusMoved(ladder, 3, "слово3", 10);
assert.strictEqual(ladder.suggestPause, true);

// The ladder is silent end to end. There is no rung that spells a word out, and the one rung
// above Sweep shows a written hint — so nothing in focus mode can put the app's own voice into
// the microphone that is judging the child.
assert.strictEqual(typeof globalThis.letterNamesFor, "undefined", "no letter-name table survives");
assert.deepStrictEqual(Object.keys(FocusStep), ["Focus", "Sweep", "Meaning"]);
assert.ok(!("Letters" in FocusStep), "the spelling rung is gone");
assert.strictEqual(typeof globalThis.ladderTtsRate, "undefined", "nothing paces speech any more");

// extractTrainingWords: >=4 letters, distinct, capped at 40.
assert.deepStrictEqual(extractTrainingWords("кот кітап кітап балалар"), ["кітап", "балалар"]);

// Numerals: digits on the page, words from the microphone, in any of the three languages.
assert.strictEqual(numeralValue(["двадцать", "пять"]), 25);
assert.strictEqual(numeralValue(["две", "тысячи", "двадцать", "шесть"]), 2026);
assert.strictEqual(numeralValue(["бір", "мың", "тоғыз", "жүз", "тоқсан", "бес"]), 1995);
assert.strictEqual(numeralValue(["быр", "мын", "тогыз", "жуз", "токсан", "бес"]), 1995); // Russian-mode spelling
assert.strictEqual(numeralValue(["twenty-five"]), 25);
assert.strictEqual(numeralValue(["три", "двадцать"]), null); // wrong order is not the number
assert.strictEqual(numeralValue(["книга"]), null);
assert.strictEqual(numeralValue(["constructor"]), null); // prototype keys must not read as numbers
assert.ok(isSpokenWordAccepted("5", ["пять"]));
assert.ok(isSpokenWordAccepted("5", ["бес"]));
assert.ok(isSpokenWordAccepted("5", ["five"]));
assert.ok(isSpokenWordAccepted("пять", ["5"]));   // the engine wrote digits for spoken words
assert.ok(!isSpokenWordAccepted("5", ["шесть"]));
assert.ok(!isSpokenWordAccepted("5", ["6"]));
assert.ok(isSpokenWordAccepted("он", ["он"]));      // no digits involved: letter rules apply
assert.ok(!isSpokenWordAccepted("он", ["десять"]));

// Review alignment across numeral spans, in both directions, and a wrong number as a misreading.
const numReview = reviewReading(heard("страница", "двадцать", "пять", "готова"), ["страница", "25", "готова"]);
assert.ok(numReview.every((r) => r.outcome === ReadOutcome.Correct));
assert.strictEqual(numReview[1].heard, "двадцать пять");
const numReview2 = reviewReading(heard("страница", "25", "готова"), ["страница", "двадцать", "пять", "готова"]);
assert.ok(numReview2.every((r) => r.outcome === ReadOutcome.Correct));
assert.deepStrictEqual(numReview2.slice(1, 3).map((r) => r.heard), ["25", "25"]);
const yearReview = reviewReading(heard("бір", "мың", "тоғыз", "жүз", "тоқсан", "бес", "жыл"), ["1995", "жыл"]);
assert.ok(yearReview.every((r) => r.outcome === ReadOutcome.Correct));
// Numeral spans and per-token alternatives are two separate widenings of the same aligner, and
// the merge that brought them together is exactly where one would silently break the other.
const both = reviewReading(
  [heardToken("кинга", ["книга"]), heardToken("двадцать"), heardToken("пять")],
  ["книга", "25"]);
assert.ok(both.every((r) => r.outcome === ReadOutcome.Correct));
assert.strictEqual(both[0].heard, "кинга");        // rescued by an alternative
assert.strictEqual(both[1].heard, "двадцать пять"); // matched as a numeral span
// An alternative must not rescue a genuinely wrong number.
assert.strictEqual(reviewReading([heardToken("шесть", ["шесть"])], ["5"])[0].outcome, ReadOutcome.Misread);

const wrongNumber = reviewReading(heard("страница", "шесть"), ["страница", "5"]);
assert.strictEqual(wrongNumber[1].outcome, ReadOutcome.Misread);
assert.strictEqual(wrongNumber[1].heard, "шесть");

console.log("all focus logic checks passed");
