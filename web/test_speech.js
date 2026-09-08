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

// Focus mode makes no sound, so there is nothing to mask: the gate no longer carries app-speech
// windows at all. Asserted rather than assumed — a mask that quietly comes back would mean the
// app is speaking again over an open microphone.
assert.strictEqual(typeof gate.markAppSpeech, "undefined", "no app-speech window is recorded");
assert.strictEqual(typeof gate.markAppSpeechStart, "undefined");
assert.strictEqual(typeof gate.wasAppSpeaking, "undefined", "no segment is dropped as the app's voice");
assert.ok(!("speakWindows" in gate));

console.log("all speech gate checks passed");
