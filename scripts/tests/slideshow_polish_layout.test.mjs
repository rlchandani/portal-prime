/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */
// Screensaver polish (mock screensaver-polish-20261121): the Portal+ single-photo fill rule, the
// portrait pair, the exact-fit trio grid and the pairing's deck behaviour. Pure geometry only.
import test from 'node:test';
import assert from 'node:assert/strict';
import { readFileSync } from 'node:fs';
import vm from 'node:vm';

const assets = new URL('../../app/src/main/assets/screensaver/', import.meta.url);
function load(host) {
  const context = { window: host ? { Android: host } : {} };
  vm.createContext(context);
  vm.runInContext(readFileSync(new URL('modes.js', assets), 'utf8'), context);
  return context.window.SS;
}
const SS = load();
const near = (a, b, eps = 1e-6) => assert.ok(Math.abs(a - b) < eps, `${a} != ${b}`);
const same = (a, b, message) => assert.deepEqual(JSON.parse(JSON.stringify(a ?? null)), b, message);
const photo = (seed, w, h, extra = {}) => ({ seed, w, h, ...extra });

// ---- fill rule ---------------------------------------------------------------------------------

test('fill rule: a crop keeping >= 80 % fills, below that the photo is shown whole', () => {
  // 3:2 Portal+ (2160 x 1440): 4:3 keeps 89 %, 16:9 keeps 84 %, 1:1 keeps 67 %, 2:3 keeps 44 %.
  for (const [w, h, mode] of [[4, 3, 'fill'], [16, 9, 'fill'], [3, 2, 'fill'], [1, 1, 'whole'], [2, 3, 'whole'], [3, 1, 'whole']]) {
    assert.equal(SS.largeSingle(photo('p', w, h), 2160, 1440).mode, mode, `${w}:${h}`);
  }
  // Exactly 80 % is kept: 1.2 : 1 on a 1.5 : 1 screen.
  near(SS.coverCrop(photo('p', 1200, 1000), 1500, 1000).visible, .8);
  assert.equal(SS.largeSingle(photo('p', 1200, 1000), 1500, 1000).mode, 'fill');
  assert.equal(SS.largeSingle(photo('p', 1190, 1000), 1500, 1000).mode, 'whole');
  // 16:9 Portal+ panels follow the same rule.
  assert.equal(SS.largeSingle(photo('p', 4, 3), 1920, 1080).mode, 'whole', '4:3 keeps 75 % on 16:9');
});

test('fill rule: a face box cut by the crop shows the photo whole', () => {
  // 16:9 on a 3:2 screen keeps the middle 84 % of the width (8 %..92 %).
  const inside = photo('p', 1600, 900, { fx: .5, fy: .4, fb: [.3, .2, .7, .6] });
  const plan = SS.largeSingle(inside, 2160, 1440);
  assert.equal(plan.mode, 'fill');
  assert.deepEqual({ ...plan.rect }, { x: 0, y: 0, w: 2160, h: 1440 });
  // A group spanning edge to edge cannot survive the crop.
  const wide = photo('p', 1600, 900, { fx: .5, fy: .4, fb: [.04, .2, .96, .6] });
  assert.equal(SS.largeSingle(wide, 2160, 1440).mode, 'whole');
  // The crop is centred on the face, clamped to the photo, so an off-centre face survives.
  const left = photo('p', 1600, 900, { fx: .1, fy: .4, fb: [.02, .2, .2, .6] });
  const crop = SS.coverCrop(left, 2160, 1440);
  assert.equal(crop.faces, true);
  assert.equal(crop.position, '0.0% 50%');
  // A face point without a box (older cache) cannot fail the rule.
  assert.equal(SS.largeSingle(photo('p', 1600, 900, { fx: .9, fy: .4 }), 2160, 1440).mode, 'fill');
  // A malformed box is ignored, not trusted.
  assert.equal(SS.coverCrop(photo('p', 1600, 900, { fb: [.9, .2, .1, .6] }), 2160, 1440).faces, true);
});

test('whole: the photo is as large as the margin allows and centred', () => {
  const plan = SS.largeSingle(photo('p', 1000, 1500), 2160, 1440);
  assert.equal(plan.mode, 'whole');
  const m = Math.round(1440 * .035);
  near(plan.rect.h, 1440 - 2 * m); near(plan.rect.w, (1440 - 2 * m) * 2 / 3);
  near(plan.rect.x + plan.rect.w / 2, 1080); near(plan.rect.y, m);
  // A panorama is width-bound.
  const pano = SS.largeSingle(photo('p', 3000, 1000), 2160, 1440);
  near(pano.rect.w, 2160 - 2 * m); near(pano.rect.y + pano.rect.h / 2, 720);
  // Unknown shapes are safe.
  assert.equal(SS.largeSingle(photo('p', 0, 0), 2160, 1440).mode, 'whole');
});

// ---- portrait pair -----------------------------------------------------------------------------

test('pair: two portraits at one height, 24 px apart, centred, nothing cropped', () => {
  for (const [W, H] of [[2160, 1440], [1920, 1080], [1280, 800]]) {
    const plan = SS.pairPlan([2 / 3, 3 / 4], W, H), zone = SS.largeMargin(W, H);
    assert.equal(plan.pair, true); assert.equal(plan.mode, 'whole');
    near(plan.widths[0] / plan.rect.h, 2 / 3); near(plan.widths[1] / plan.rect.h, 3 / 4);
    near(plan.rect.w, plan.widths[0] + plan.widths[1] + 24);
    assert.ok(plan.rect.h <= zone.h + 1e-6 && plan.rect.w <= zone.w + 1e-6);
    near(plan.rect.x + plan.rect.w / 2, W / 2); near(plan.rect.y + plan.rect.h / 2, H / 2);
  }
  // On the 3:2 Portal+ two 3:4 portraits span about 90 % of the width.
  const wide = SS.pairPlan([3 / 4, 3 / 4], 2160, 1440);
  assert.ok(wide.rect.w / 2160 > .88 && wide.rect.w / 2160 < .95, String(wide.rect.w / 2160));
  // On a squarer window the pair becomes width-bound and still keeps the 24 px gap.
  const bound = SS.pairPlan([.78, .78], 1200, 1000), zone = SS.largeMargin(1200, 1000);
  near(bound.rect.w, zone.w); assert.ok(bound.rect.h < zone.h);
});

// ---- trio grid ---------------------------------------------------------------------------------

const SIZES = { L: [2160, 1440], CP: [800, 1280], CL: [1280, 800] };
function gutters(boxes, gap) {
  let meets = 0;
  for (const a of boxes) for (const b of boxes) {
    if (a === b) continue;
    const overlapY = a.y < b.y + b.h - 1e-6 && b.y < a.y + a.h - 1e-6;
    const overlapX = a.x < b.x + b.w - 1e-6 && b.x < a.x + a.w - 1e-6;
    if (overlapY) assert.ok(b.x >= a.x + a.w + gap - 1e-6 || a.x >= b.x + b.w + gap - 1e-6 || overlapX, 'gutter too small');
    if (overlapX) assert.ok(b.y >= a.y + a.h + gap - 1e-6 || a.y >= b.y + b.h + gap - 1e-6 || overlapY, 'gutter too small');
    if (overlapY && Math.abs(a.x + a.w + gap - b.x) < 1e-6) meets++;
    if (overlapX && Math.abs(a.y + a.h + gap - b.y) < 1e-6) meets++;
  }
  return meets;
}
function bounds(boxes) {
  const x = Math.min(...boxes.map(b => b.x)), y = Math.min(...boxes.map(b => b.y));
  return { x, y, w: Math.max(...boxes.map(b => b.x + b.w)) - x, h: Math.max(...boxes.map(b => b.y + b.h)) - y };
}
function edgesShared(boxes) {
  // Every outer edge of the composition is touched by at least one tile, and every tile's edges
  // either sit on the outer edge or face a neighbour: a perfectly filled rectangle has
  // sum(tile areas) + gutter area = bounding area, checked via coverage below.
  const o = bounds(boxes);
  for (const side of ['left', 'right', 'top', 'bottom']) {
    assert.ok(boxes.some(b => side === 'left' ? Math.abs(b.x - o.x) < 1e-6 : side === 'right' ?
      Math.abs(b.x + b.w - o.x - o.w) < 1e-6 : side === 'top' ? Math.abs(b.y - o.y) < 1e-6 :
      Math.abs(b.y + b.h - o.y - o.h) < 1e-6), side);
  }
  return o;
}

test('trio: every candidate keeps aspects, constant gutters and a filled outer rectangle', () => {
  const sets = [[1.5, 2 / 3, 2 / 3], [2 / 3, 1.5, 1], [1.5, 1.5, 1.5], [2 / 3, 2 / 3, 2 / 3], [3, 1.5, .75], [1.5, 2 / 3]];
  for (const [cls, [W, H]] of Object.entries(SIZES)) {
    const gap = cls === 'L' ? SS.TRIO_GAP.L : SS.TRIO_GAP.compact;
    const area = { x: 40, y: 30, w: W - 80, h: H - 220 };
    for (const aspects of sets) for (const only of [undefined, 'row', 'column', 'heroLeft', 'heroTop']) {
      if (aspects.length < 3 && only && only.startsWith('hero')) continue;
      const boxes = SS.trioLayout(aspects, area, gap, only), label = `${cls} ${aspects} ${only}`;
      assert.equal(boxes.length, aspects.length, label);
      boxes.forEach((b, i) => near(b.w / b.h, aspects[i], 1e-9));
      assert.ok(gutters(boxes, gap) >= aspects.length - 1, label + ': tiles meet across one gutter');
      const o = edgesShared(boxes);
      // Filled: tiles plus gutters cover the bounding rectangle exactly (no ragged edge).
      const tiles = boxes.reduce((s, b) => s + b.w * b.h, 0);
      assert.ok(tiles < o.w * o.h + 1e-6, label);
      const shape = only || 'best';
      if (shape === 'row') near(o.h, boxes[0].h, 1e-9);
      if (shape === 'column') near(o.w, boxes[0].w, 1e-9);
      if (shape === 'heroLeft') { near(boxes[0].h, o.h, 1e-9); near(boxes[1].x, boxes[2].x, 1e-9); near(boxes[1].w, boxes[2].w, 1e-9); }
      if (shape === 'heroTop') { near(boxes[0].w, o.w, 1e-9); near(boxes[1].y, boxes[2].y, 1e-9); near(boxes[1].h, boxes[2].h, 1e-9); }
      // Inside the area, centred on it, and touching it on at least one axis (as large as fits).
      assert.ok(o.x >= area.x - 1e-6 && o.y >= area.y - 1e-6 && o.x + o.w <= area.x + area.w + 1e-6 &&
        o.y + o.h <= area.y + area.h + 1e-6, label);
      near(o.x + o.w / 2, area.x + area.w / 2); near(o.y + o.h / 2, area.y + area.h / 2);
      assert.ok(Math.abs(o.w - area.w) < 1e-6 || Math.abs(o.h - area.h) < 1e-6, label + ': largest fit');
    }
  }
});

test('trio: the chosen layout is the largest without a postage stamp', () => {
  const area = { x: 0, y: 0, w: 2000, h: 1200 };
  // A landscape hero and two portraits: hero left with the portraits stacked would make the
  // portraits tiny; the row or hero-top shapes cover more.
  const boxes = SS.trioLayout([1.5, 2 / 3, 2 / 3], area, 20);
  const areas = boxes.map(b => b.w * b.h);
  assert.ok(Math.min(...areas) / Math.max(...areas) > .1, 'no postage stamp');
  for (const only of ['row', 'column', 'heroLeft', 'heroTop']) {
    const other = SS.trioLayout([1.5, 2 / 3, 2 / 3], area, 20, only);
    const total = b => b.reduce((s, x) => s + x.w * x.h, 0);
    const balance = b => { const a = b.map(x => x.w * x.h); return Math.min(...a) / Math.max(...a); };
    const score = b => total(b) * (.6 + .4 * Math.min(1, balance(b) * 2.5));
    assert.ok(score(boxes) >= score(other) - 1e-6, only);
  }
  // Degenerate input never throws and never draws.
  same(SS.trioLayout([], area, 20), []);
  same(SS.trioLayout([1, 1, 1], { x: 0, y: 0, w: 0, h: 100 }, 20), []);
  assert.equal(SS.trioLayout([NaN, -1, 1.5], area, 20).length, 3);
});

test('trio: clear of the clock reserved by the host on the Portal+ and the Mini', () => {
  for (const [cls, [W, H]] of Object.entries(SIZES)) {
    // The native clock occupies the lower-left corner in window pixels (device = 2x CSS here).
    const clock = { left: 0, top: (H - 260) * 2, right: 900, bottom: H * 2 };
    const host = { getPhotoLayoutBounds: () => JSON.stringify({ width: W * 2, height: H * 2, occupied: [clock] }) };
    const S = load(host), area = S.photoLayoutArea({ root: {}, W, H });
    const gap = cls === 'L' ? 20 : 14;
    const boxes = S.trioLayout([1.5, 2 / 3, 2 / 3], area, gap, cls === 'CP' ? 'heroTop' : 'heroLeft');
    const c = { x: 0, y: H - 260, right: 450, bottom: H };
    for (const b of boxes) {
      const overlaps = b.x < c.right && b.x + b.w > c.x && b.y < c.bottom && b.y + b.h > c.y;
      assert.ok(!overlaps, `${cls}: ${JSON.stringify(b)} overlaps the clock`);
    }
  }
});

// ---- deck behaviour ----------------------------------------------------------------------------

function deck(pattern) {
  return [...pattern].map((k, i) => k === 'p' ? photo(k + i, 1000, 1500) : photo(k + i, 1500, 1000));
}
// Ken Burns' step: one photo, then its partner when it is a portrait.
function play(d, steps, cursor = { i: 0 }) {
  const shown = [];
  let onScreen = [];
  for (let s = 0; s < steps; s++) {
    const p = SS.take(d, cursor, ['any'])[0];
    const partner = SS.takePartner(d, cursor, p, onScreen);
    const beat = partner ? [p, partner] : [p];
    shown.push(beat.map(x => x.seed)); onScreen = beat.map(x => x.seed);
  }
  return shown;
}

test('pairing: adjacent portraits are taken in deck order', () => {
  const d = deck('ppLpp');
  const cursor = { i: 0 };
  assert.deepEqual(play(d, 3, cursor), [['p0', 'p1'], ['L2'], ['p3', 'p4']]);
  assert.equal(cursor.i, 5); assert.deepEqual(cursor.ahead || [], []);
});

test('pairing: a later portrait is shown early and its own slot is skipped once', () => {
  const d = deck('pLLpL');
  const cursor = { i: 0 };
  const shown = play(d, 3, cursor);
  assert.deepEqual(shown, [['p0', 'p3'], ['L1'], ['L2']]);
  // The next step passes over p3's slot instead of showing it again.
  assert.deepEqual(play(d, 1, cursor), [['L4']]);
  same(cursor.ahead, []);
});

test('pairing: every photo appears exactly once per pass, over many passes', () => {
  const d = deck('pLpLLpppLpLLLp');
  const cursor = { i: 0 }, counts = new Map();
  let slots = 0;
  // Play whole passes: one pass shows d.length photos.
  while (slots < d.length * 20) {
    for (const seed of play(d, 1, cursor)[0]) { counts.set(seed, (counts.get(seed) || 0) + 1); slots++; }
  }
  for (const p of d) {
    const n = counts.get(p.seed) || 0;
    assert.ok(n >= 19 && n <= 21, `${p.seed} shown ${n} times in 20 passes`);
  }
  assert.ok((cursor.ahead || []).length <= 1);
});

test('pairing: never repeats on screen and falls back to one photo without a near portrait', () => {
  // No second portrait within the lookahead: shown whole on its own.
  assert.deepEqual(play(deck('pLLLLLLLp'), 1), [['p0']]);
  // A single portrait deck, and a portrait-only deck of two (the outgoing pair stays excluded).
  assert.deepEqual(play(deck('p'), 2), [['p0'], ['p0']]);
  same(play(deck('pp'), 3), [['p0', 'p1'], ['p0'], ['p1']], 'the photo still on screen is never its partner');
  // Landscapes never pair; the cursor and lookahead state are untouched.
  const cursor = { i: 0 };
  assert.equal(SS.takePartner(deck('Lp'), cursor, photo('L', 1500, 1000), []), null);
  same(cursor, { i: 0 });
  // Unmeasured photos are never assumed to be portraits.
  assert.equal(SS.takePartner([photo('a', 1000, 1500), photo('b', 0, 0)], { i: 1 }, photo('a', 1000, 1500), []), null);
});

test('pairing: other modes honour an early-shown photo', () => {
  const d = deck('pLpLL'), cursor = { i: 0 };
  SS.take(d, cursor, ['any']);
  assert.equal(SS.takePartner(d, cursor, d[0], []).seed, 'p2');
  // A three-photo collage that follows skips p2 instead of repeating it.
  same(SS.take(d, cursor, ['any', 'any', 'any']).map(p => p.seed), ['L1', 'L3', 'L4']);
  same(cursor.ahead, []);
});
