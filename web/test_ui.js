// ponytail: regression checks for the DOM helper. Run: node test_ui.js
// el() is the single choke point every screen renders through, so a bug here is invisible in
// review and total at runtime — which is exactly what happened: `disabled: cond ? "" : undefined`
// wrote the string "undefined", and any value on a boolean attribute means true, so the
// training Check button and the book pager were permanently disabled.
const fs = require("fs");
const vm = require("vm");
const assert = require("assert");

class FakeNode {
  constructor(tag) { this.tag = tag; this.attrs = {}; this.children = []; this.listeners = {}; this.style = {}; }
  setAttribute(k, v) { this.attrs[k] = v; }
  hasAttribute(k) { return k in this.attrs; }
  addEventListener(k, fn) { (this.listeners[k] ||= []).push(fn); }
  append(...kids) { this.children.push(...kids); }
  get nodeType() { return 1; }
}
global.document = {
  createElement: (t) => new FakeNode(t),
  createTextNode: (t) => ({ nodeType: 3, text: String(t) })
};

const src = fs.readFileSync("app.js", "utf8");
const elSrc = src.slice(src.indexOf("function el("), src.indexOf("/* ---------------- global state"));
vm.runInThisContext(elSrc);

// The bug: an undefined attribute must not reach the DOM at all.
const enabled = el("button", { disabled: undefined }, "Check");
assert.ok(!enabled.hasAttribute("disabled"), "undefined must not become a disabled attribute");

const disabled = el("button", { disabled: "" }, "Check");
assert.ok(disabled.hasAttribute("disabled"), "empty string must still disable");

assert.ok(!el("div", { title: null }).hasAttribute("title"), "null must not become an attribute");

// Falsy-but-real values must survive: 0 and "" are legitimate attribute values.
assert.strictEqual(el("div", { "aria-valuenow": 0 }).attrs["aria-valuenow"], 0);

// Children: null/undefined/false skipped, everything else appended.
const row = el("div", null, "a", null, undefined, false, "b");
assert.strictEqual(row.children.length, 2, "null/undefined/false children are skipped");

// Handlers still bind, and a null attrs object is tolerated.
let clicked = 0;
const btn = el("button", { onclick: () => clicked++ });
btn.listeners.click[0]();
assert.strictEqual(clicked, 1);

console.log("all ui helper checks passed");
