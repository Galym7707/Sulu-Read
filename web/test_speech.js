// ponytail: single runnable check for the two pieces of ServerSpeechGate arithmetic that are not
// obvious by reading. Run: node test_speech.js
//
// Both exist because getting them wrong is silent: a stalled drain means nothing the child says
// after the first dropped segment is ever heard, and a window that never closes means every
// segment is discarded as "the app was talking" for the rest of the reading. Neither shows up as
// an error — only as a review that says the child read nothing.
const fs = require("fs");
const path = require("path");
const vm = require("vm");
const assert = require("assert");

global.navigator = { language: "ru" };
global.window = { APP_STATE: { languageCode: "ru" } };
for (const file of ["strings.js", "speech.js"]) {
  vm.runInThisContext(fs.readFileSync(path.join(__dirname, file), "utf8").replace('"use strict";', ""));
}

/* ---- settle(): capture order out, whatever order the HTTP responses arrive in ---- */

function drain(calls) {
  const gate = new ServerSpeechGate();
  return { gate, emit: (text) => calls.push(text[0]) };
}

let emitted = [];
let { gate, emit } = drain(emitted);
gate.settle(1, "второй", emit);
assert.deepStrictEqual(emitted, [], "a segment may not be emitted before the one before it");
gate.settle(0, "первый", emit);
assert.deepStrictEqual(emitted, ["первый", "второй"], "the drain reorders into capture order");

// A dropped segment (the app was talking) and a failed request both settle empty. If either
// failed to claim its slot the drain would stall on it forever.
emitted = [];
({ gate, emit } = drain(emitted));
gate.settle(0, "", emit);           // dropped
gate.settle(2, "третий", emit);
gate.settle(1, "", emit);           // failed
assert.deepStrictEqual(emitted, ["третий"], "empty slots advance the cursor instead of blocking it");

// Whitespace-only transcripts are silence, not a word.
emitted = [];
({ gate, emit } = drain(emitted));
gate.settle(0, "   ", emit);
assert.deepStrictEqual(emitted, []);

/* ---- wasAppSpeaking(): the app's own voice must never be scored as the child's reading ---- */

gate = new ServerSpeechGate();
gate.startedAt = 1000;

// Nothing recorded yet: every segment is the child.
assert.ok(!gate.wasAppSpeaking(0, 3000));

// A closed window covering most of a segment drops it; one covering less than half does not.
gate.markAppSpeechStart(1000);          // t=0
gate.markAppSpeech(1000, 3500);         // t=0..2500
assert.ok(gate.wasAppSpeaking(0, 3000), "2500ms of app speech in a 3000ms segment");
assert.ok(!gate.wasAppSpeaking(2000, 6000), "500ms of app speech in a 4000ms segment");

// The Letters rung queues two utterances. They collapse into ONE window, so the letter names are
// inside the discarded span rather than left outside it.
gate = new ServerSpeechGate();
gate.startedAt = 1000;
gate.markAppSpeechStart(1000);          // letter names begin
gate.markAppSpeechStart(1200);          // the word, queued behind them — must not re-open
gate.markAppSpeech(1200, 4000);         // the last utterance ends
assert.deepStrictEqual(gate.speakWindows, [{ start: 0, end: 3000 }],
  "queued utterances are one window, starting at the first");

// An utterance still in flight masks only up to the END of the segment being judged — so an
// onend that never arrives costs one segment, not the whole reading.
gate = new ServerSpeechGate();
gate.startedAt = 1000;
gate.markAppSpeechStart(1000);          // opened at t=0, never closed
assert.ok(gate.wasAppSpeaking(0, 2000), "the segment the app is talking through is dropped");
assert.ok(gate.wasAppSpeaking(1000, 3000), "and so is the next one while it is still open");
gate.markAppSpeech(0, 3500);            // t=..2500
assert.ok(!gate.wasAppSpeaking(4000, 8000), "once closed, later segments are the child again");

console.log("all speech gate checks passed");
