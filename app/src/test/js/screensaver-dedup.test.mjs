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

console.log("SS.fitPlan — mismatched photos pan through their hidden extent");
test("portrait in a wide cell pans vertically, sized to full photo height", () => {
  // photo 3:4 (0.75) in a 2:1 cell — cover-crop would show only the middle band
  const plan = SS.fitPlan(0.75, 2.0);
  assert.equal(plan.mode, "panV");
  assert.ok(Math.abs(plan.size - (2.0 / 0.75) * 100) < 0.01); // element height ≈ 266.7% of cell
});
test("pano in a tall cell pans horizontally", () => {
  const plan = SS.fitPlan(2.4, 0.9);
  assert.equal(plan.mode, "panH");
  assert.ok(Math.abs(plan.size - (2.4 / 0.9) * 100) < 0.01);
});
test("near-matching aspects keep the normal cover + Ken Burns", () => {
  assert.equal(SS.fitPlan(1.5, 1.5).mode, "cover");
  assert.equal(SS.fitPlan(1.5, 1.7).mode, "cover"); // mild mismatch: crop is fine
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
