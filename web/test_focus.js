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
const review = reviewReading(["эм", "кинга", "стол"], ["книга", "стол", "дом"]);
assert.strictEqual(review[0].outcome, ReadOutcome.Misread);
assert.strictEqual(review[0].heard, "кинга");
assert.strictEqual(review[1].outcome, ReadOutcome.Correct);
assert.strictEqual(review[2].outcome, ReadOutcome.Silent);

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
const numReview = reviewReading(["страница", "двадцать", "пять", "готова"], ["страница", "25", "готова"]);
assert.ok(numReview.every((r) => r.outcome === ReadOutcome.Correct));
assert.strictEqual(numReview[1].heard, "двадцать пять");
const numReview2 = reviewReading(["страница", "25", "готова"], ["страница", "двадцать", "пять", "готова"]);
assert.ok(numReview2.every((r) => r.outcome === ReadOutcome.Correct));
assert.deepStrictEqual(numReview2.slice(1, 3).map((r) => r.heard), ["25", "25"]);
const yearReview = reviewReading(["бір", "мың", "тоғыз", "жүз", "тоқсан", "бес", "жыл"], ["1995", "жыл"]);
assert.ok(yearReview.every((r) => r.outcome === ReadOutcome.Correct));
const wrongNumber = reviewReading(["страница", "шесть"], ["страница", "5"]);
assert.strictEqual(wrongNumber[1].outcome, ReadOutcome.Misread);
assert.strictEqual(wrongNumber[1].heard, "шесть");

console.log("all focus logic checks passed");
