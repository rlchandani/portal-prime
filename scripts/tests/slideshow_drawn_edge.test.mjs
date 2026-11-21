/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */
// Decode size: a photo is asked for at the size it is drawn (device px at the top of its motion),
// never more than the copy its request delivered before and never fewer pixels than are drawn.
import test from 'node:test';
import assert from 'node:assert/strict';
import { readFileSync } from 'node:fs';
import vm from 'node:vm';

const assets = new URL('../../app/src/main/assets/screensaver/', import.meta.url);
function load() {
  const context = vm.createContext({ Android: { getPhotos: () => '[]' },
    document: { createElement: () => ({ style: {} }) }, innerWidth: 1920, innerHeight: 1280 });
  context.window = context;
  for (const file of ['photos.js', 'lib-engine.js', 'modes.js'])
    vm.runInContext(readFileSync(new URL(file, assets), 'utf8'), context, { filename: file });
  return context.SS;
}
const SS = load();
const near = (a, b, eps = 1e-6) => assert.ok(Math.abs(a - b) < eps, `${a} != ${b}`);
// The host's copy steps (EngineScaledCache.TIERS), mirrored to check what is actually delivered.
const HOST = [600, 700, 800, 900, 1000, 1100, 1200, 1350, 1500, 1650, 1800, 2000, 2200, 2400];
const OLD_HOST = [600, 1200, 1800, 2400];
const deliver = (tiers, edge) => tiers.find(t => t >= Math.min(2400, edge)) || 2400;
// The Portal+: 2160 x 1440 device px at 180 dpi, so 1920 x 1280 CSS px at dpr 1.125.
const W = 1920, H = 1280, DPR = 1.125;

test('largeEdge asks for the drawn device pixels, capped at the copy the request got before', () => {
  assert.equal(SS.largeEdge(2200, 1000, 1.125), 1125);
  assert.equal(SS.largeEdge(2200, 3000, 1.125), 2400, 'cap is the former tier of 2200, not 2200');
  assert.equal(SS.largeEdge(1400, 1500, 1), 1500, 'a 1400 request delivered 1800 before');
  assert.equal(SS.largeEdge(1800, 2000, 1), 1800);
  assert.equal(SS.largeEdge(1200, 100, 1), 320, 'never below the bridge floor');
  assert.equal(SS.largeEdge(2400, 1000.2, 1), 1001, 'rounded up, never down');
  assert.equal(SS.largeEdge(1800, 1000, NaN), 1000, 'an unknown dpr is 1');
});

test('largeEdge without a drawn size delivers exactly the copy it did before', () => {
  for (const [asked, before] of [[320, 600], [1000, 1200], [1100, 1200], [1400, 1800], [2200, 2400], [2400, 2400], [9999, 2400]]) {
    for (const drawn of [undefined, null, NaN, 0, -5]) assert.equal(SS.largeEdge(asked, drawn, DPR), before);
  }
  assert.equal(SS.largeEdge(undefined, undefined, DPR), 2400);
});

test('a delivered copy is never larger than before and never softer than drawn (property)', () => {
  let seed = 7;
  const rand = () => (seed = (seed * 16807) % 2147483647) / 2147483647;
  for (let i = 0; i < 5000; i++) {
    const asked = 300 + Math.floor(rand() * 2200), drawn = 50 + rand() * 3000, dpr = .75 + rand() * 2;
    const before = deliver(OLD_HOST, asked);
    const now = deliver(HOST, SS.largeEdge(asked, drawn, dpr));
    assert.ok(now <= before, `${asked}/${drawn}/${dpr}: ${now} > ${before}`);
    // At least one photo px per device px wherever the former copy had that much.
    assert.ok(now >= Math.min(before, Math.ceil(drawn * dpr)), `${asked}/${drawn}/${dpr}: ${now} soft`);
  }
});

test('the former host tiers are a subset of the new ones', () => {
  for (const t of OLD_HOST) assert.ok(HOST.includes(t));
  for (let edge = 1; edge <= 2400; edge += 7) assert.ok(deliver(HOST, edge) <= deliver(OLD_HOST, edge));
});

test('compact screens keep the display long edge as their default drawn size', () => {
  assert.equal(SS.compactEdge(2200, undefined, 800, 1280, 1), 1280);
  assert.equal(SS.compactEdge(2200, 900, 800, 1280, 1), 900);
  assert.equal(SS.compactEdge(2200, null, 800, 1280, 1), 2400, 'unknown shape: the copy it got before');
});

test('contain and cover long edges', () => {
  near(SS.containEdge(1.5, 1000, 1000), 1000);
  near(SS.containEdge(2 / 3, 1000, 1000), 1000);
  near(SS.containEdge(2 / 3, 1000, 600), 600);
  near(SS.coverEdge(1.5, 1920, 1280), 1920);
  near(SS.coverEdge(16 / 9, 1920, 1280), 1280 * 16 / 9);
  near(SS.coverEdge(2 / 3, 1920, 1280), 1920 * 1.5);
  near(SS.containEdge(NaN, 500, 400), 400); // an unknown aspect is square
});

test('Portal+ Ken Burns fill keeps the full 2400 px copy (it is drawn larger than that)', () => {
  const photo = { w: 4032, h: 3024 };
  const plan = SS.largeSingle(photo, W, H);
  assert.equal(plan.mode, 'fill');
  const drawn = SS.largeDrawnEdge(plan, 4 / 3, 0, W, H, 1.15, true);
  near(drawn, 1920 * 1.15);
  assert.equal(SS.largeEdge(2200, drawn, DPR), 2400);
  // The 1.04 drift of Push and Memories draws 2247 device px: still the 2400 px copy.
  assert.equal(deliver(HOST, SS.largeEdge(2200, SS.largeDrawnEdge(plan, 4 / 3, 0, W, H, 1.04, false), DPR)), 2400);
});

test('Portal+ whole portrait: Ken Burns stops where it touches the screen', () => {
  const photo = { w: 3024, h: 4032 };
  const plan = SS.largeSingle(photo, W, H);
  assert.equal(plan.mode, 'whole');
  const fit = Math.min(W / plan.rect.w, H / plan.rect.h);
  near(SS.largeDrawnEdge(plan, .75, 0, W, H, 1.15, true), plan.rect.h * Math.min(1.15, fit));
  near(SS.largeDrawnEdge(plan, .75, 0, W, H, 1.04, false), plan.rect.h * 1.04);
  const edge = SS.largeEdge(2200, SS.largeDrawnEdge(plan, .75, 0, W, H, 1.15, true), DPR);
  assert.equal(edge, Math.ceil(H * DPR), 'the whole photo reaches the screen height, no further');
  assert.equal(deliver(HOST, edge), 1500);
});

test('Portal+ portrait pair: each photo at its drawn width or height', () => {
  const plan = SS.pairPlan([.75, 2 / 3], W, H);
  const fit = Math.min(W / plan.rect.w, H / plan.rect.h), top = Math.min(1.15, fit);
  [0, 1].forEach(i => near(SS.largeDrawnEdge(plan, [.75, 2 / 3][i], i, W, H, 1.15, true),
    Math.max(plan.widths[i], plan.rect.h) * top));
  assert.equal(SS.largeDrawnEdge(null, 1, 0, W, H, 1.15, true), null);
});

test('Polaroid print box and the Diptych cells', () => {
  assert.equal(SS.polaroidDrawnEdge(1.5), 600);
  assert.equal(SS.polaroidDrawnEdge(1), 600);
  near(SS.polaroidDrawnEdge(2 / 3), 720);
  const vmin = H / 100;
  const [a, b] = SS.diptychDrawnEdges([2 / 3, 2 / 3], W, H);
  near(a, H - 6 * vmin); near(b, H - 6 * vmin);
  const [land] = SS.diptychDrawnEdges([1.5], W, H);
  near(land, Math.min(W - 6 * vmin, (H - 6 * vmin) * 1.5));
});

test('Cinema: a contained photo at its drawn size in the 78 % scope; wide photos keep their copy', () => {
  const scopeH = H * .78;
  near(SS.cinemaDrawnEdge(4 / 3, W, H), scopeH * 4 / 3);
  assert.equal(deliver(HOST, SS.largeEdge(2400, SS.cinemaDrawnEdge(4 / 3, W, H), DPR)), 1500);
  near(SS.cinemaDrawnEdge(3 / 4, W, H), scopeH, 1e-6);
  assert.equal(deliver(HOST, SS.largeEdge(2400, SS.cinemaDrawnEdge(3 / 4, W, H), DPR)), 1200);
  near(SS.cinemaDrawnEdge(2.2, W, H), W, 1e-6, 'a 2.2:1 photo is the screen width');
  assert.equal(SS.cinemaDrawnEdge(2.21, W, H), null, 'wide photos are cropped and drift');
  assert.equal(SS.cinemaDrawnEdge(3, W, H), null);
  assert.equal(SS.largeEdge(2400, SS.cinemaDrawnEdge(3, W, H) ?? undefined, DPR), 2400);
});

test('Cinema: the copy covers the laid-out image box for every contained shape (property)', () => {
  // Mirrors mode-impls cinema layoutImage for a non-wide photo.
  for (let r = .3; r <= 2.2; r += .01) {
    const scopeH = H * .78, w = Math.min(W, scopeH * r), h = w / r;
    const copy = deliver(HOST, SS.largeEdge(2400, SS.cinemaDrawnEdge(r, W, H), DPR));
    assert.ok(copy >= Math.ceil(Math.max(w, h) * DPR) - 1e-9, `${r}: ${copy}`);
    assert.ok(copy <= 2400);
  }
});
