// Unit tests for the screensaver's duplicate-avoidance logic (modes.js is pure logic — no DOM).
// Run: node app/src/test/js/screensaver-dedup.test.mjs   (exit 0 = pass)
import { readFileSync } from "node:fs";
import { fileURLToPath } from "node:url";
import { dirname, join } from "node:path";
import vm from "node:vm";
import assert from "node:assert/strict";

const root = join(dirname(fileURLToPath(import.meta.url)), "../../main/assets/screensaver");
const sandbox = { window: {} };
vm.createContext(sandbox);
vm.runInContext(readFileSync(join(root, "modes.js"), "utf8"), sandbox);
const SS = sandbox.window.SS;

// Deck factory: n distinct landscape photos with unique seeds (mirrors photos.js output).
const deckOf = (n) =>
  Array.from({ length: n }, (_, i) => ({ seed: `p${i}`, w: 1600, h: 1067 }));

let failures = 0;
function test(name, fn) {
  try { fn(); console.log(`  ok    ${name}`); }
  catch (e) { failures++; console.log(`  FAIL  ${name}\n        ${e.message}`); }
}

console.log("SS.slots — compositions scale to the pool, never over-ask");
test("clamps the slot count to the deck size", () => {
  assert.equal(SS.slots(deckOf(7), 12), 7); // filmstrip on a 7-photo device: 7 slides, not 12
  assert.equal(SS.slots(deckOf(7), 9), 7);  // mosaic: 7 tiles, not 9
});
test("keeps the design count when the deck is big enough", () => {
  assert.equal(SS.slots(deckOf(50), 12), 12);
  assert.equal(SS.slots(deckOf(9), 9), 9);
});

console.log("SS.unused — photos not currently on screen");
test("filters out on-screen seeds", () => {
  const rest = SS.unused(deckOf(7), ["p0", "p1"]);
  assert.equal(rest.length, 5);
  assert.ok(rest.every((p) => p.seed !== "p0" && p.seed !== "p1"));
});
test("returns empty when everything is on screen (beat should skip)", () => {
  assert.equal(SS.unused(deckOf(3), ["p0", "p1", "p2"]).length, 0);
});

console.log("SS.take — never duplicates while unused photos remain");
test("a full-deck request returns every photo exactly once", () => {
  const deck = deckOf(7);
  const out = SS.take(deck, { i: 0 }, new Array(7).fill("any"));
  assert.equal(new Set(out.map((p) => p.seed)).size, 7);
});
test("excluded (on-screen) seeds are never picked while alternatives exist", () => {
  const deck = deckOf(7);
  const exclude = ["p0", "p1", "p2"];
  const out = SS.take(deck, { i: 0 }, new Array(4).fill("any"), exclude);
  assert.equal(new Set(out.map((p) => p.seed)).size, 4);
  for (const p of out) assert.ok(!exclude.includes(p.seed), `picked excluded ${p.seed}`);
});
test("cursor position never causes a duplicate mid-deck", () => {
  const deck = deckOf(7);
  const out = SS.take(deck, { i: 5 }, new Array(7).fill("any"));
  assert.equal(new Set(out.map((p) => p.seed)).size, 7);
});

process.exit(failures ? 1 : 0);
