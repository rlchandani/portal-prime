/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */
// A fill single is copied into a canvas one band per frame before its change (SS.paintStrips), so
// its GPU upload is spread over frames instead of landing in the dissolve's first frame.
import test from 'node:test';
import assert from 'node:assert/strict';
import { readFileSync } from 'node:fs';
import vm from 'node:vm';
import { createSceneFixture } from './helpers/slideshow_scene_fixture.mjs';

const assets = new URL('../../app/src/main/assets/screensaver/', import.meta.url);
const libEngine = readFileSync(new URL('lib-engine.js', assets), 'utf8');
function loadEngine() {
  const context = vm.createContext({ document: { createElement: () => ({ style: {} }) } });
  context.window = context;
  vm.runInContext(libEngine, context, { filename: 'lib-engine.js' });
  return context.SS;
}
const SS = loadEngine();
const near = (a, b, eps = 1e-6) => assert.ok(Math.abs(a - b) <= eps, `${a} != ${b}`);

test('parsePosition reads CSS percentages and rejects anything else', () => {
  assert.deepEqual([...SS.parsePosition('50%')], [.5, .5]);
  assert.deepEqual([...SS.parsePosition('37.5% 50%')], [.375, .5]);
  assert.deepEqual([...SS.parsePosition('50% 0.0%')], [.5, 0]);
  assert.deepEqual([...SS.parsePosition('center')], [.5, .5]);
  assert.deepEqual([...SS.parsePosition(undefined)], [.5, .5]);
  for (const bad of ['10px 50%', 'left top', '120% 50%', '-5% 50%', '1% 2% 3%', 'calc(50%)'])
    assert.equal(SS.parsePosition(bad), null, bad);
});

test('a 4:3 2400 px copy on the Portal+ is a 2400 x 1600 crop in 1 MB bands', () => {
  const L = SS.stripLayout(2400, 1800, 1920, 1280, '50% 50%', 1 << 20);
  assert.deepEqual({ ...L.canvas }, { w: 2400, h: 1600 });
  assert.deepEqual({ ...L.src }, { x: 0, y: 100 });
  near(L.css.left, 0); near(L.css.top, 0); near(L.css.width, 1920); near(L.css.height, 1280);
  assert.equal(L.slices.length, 15);
  assert.ok(L.slices.every(s => s.h * 2400 * 4 <= 1 << 20));
});

test('a face-anchored crop starts where the CSS background would', () => {
  const L = SS.stripLayout(2400, 1800, 1920, 1280, '50% 20%', 1 << 20);
  // cover scale .8: the photo is 1440 CSS px tall, 160 px past the box, 20 % of that above.
  assert.equal(L.src.y, 40);
  near(L.css.top, 0);
});

test('bands cover the canvas once, in order, inside the budget; the crop covers the box (property)', () => {
  let seed = 11;
  const rand = () => (seed = (seed * 16807) % 2147483647) / 2147483647;
  for (let i = 0; i < 3000; i++) {
    const iw = 300 + Math.floor(rand() * 2100), ih = 300 + Math.floor(rand() * 2100);
    const W = 400 + rand() * 1600, H = 400 + rand() * 1600;
    const pos = `${(rand() * 100).toFixed(1)}% ${(rand() * 100).toFixed(1)}%`;
    const budget = 65536 + Math.floor(rand() * 4e6);
    const L = SS.stripLayout(iw, ih, W, H, pos, budget);
    assert.ok(L, `${iw}x${ih} in ${W}x${H}`);
    let y = 0;
    for (const s of L.slices) {
      assert.equal(s.y, y); assert.ok(s.h > 0);
      assert.ok(s.h === 1 || s.h * L.canvas.w * 4 <= budget, 'band over budget');
      y += s.h;
    }
    assert.equal(y, L.canvas.h);
    assert.ok(L.src.x >= 0 && L.src.y >= 0 && L.src.x + L.canvas.w <= iw && L.src.y + L.canvas.h <= ih);
    const eps = 1e-6;
    assert.ok(L.css.left <= eps && L.css.top <= eps, 'crop starts inside the box');
    assert.ok(L.css.left + L.css.width >= W - eps && L.css.top + L.css.height >= H - eps, 'crop ends inside the box');
    // Never more than one whole source pixel past the box on any side.
    const s = L.css.width / L.canvas.w;
    near(L.css.height / L.canvas.h, s, 1e-9);
    assert.ok(-L.css.left < s + eps && -L.css.top < s + eps);
    assert.ok(L.css.left + L.css.width - W < s + eps && L.css.top + L.css.height - H < s + eps);
  }
});

test('stripLayout refuses unknown sizes and positions', () => {
  assert.equal(SS.stripLayout(0, 100, 10, 10, '50%'), null);
  assert.equal(SS.stripLayout(100, 100, NaN, 10, '50%'), null);
  assert.equal(SS.stripLayout(100, 100, 10, 10, '3px 4px'), null);
});

// A painter harness: frames are explicit, bands are tagged bitmaps.
function harness({ gate = () => true, frames = Infinity } = {}) {
  const draws = [], bitmaps = [];
  let frame = 0, release = null;
  const g = { drawImage: (b, x, y) => { assert.equal(b.closed, false, 'drawn after close'); draws.push({ frame, y, b }); } };
  const hooks = {
    frame: () => new Promise(resolve => { release = resolve; }),
    crop: (_, x, y, w, h) => { const b = { x, y, w, h, closed: false, close() { this.closed = true; } }; bitmaps.push(b); return Promise.resolve(b); },
    gate: () => gate(frame),
  };
  async function tick(ok = true) {
    for (let i = 0; i < 20 && !release; i++) await Promise.resolve();
    if (!release) return;
    frame++; const r = release; release = null; r(ok && frame <= frames);
    for (let i = 0; i < 20; i++) await Promise.resolve();
  }
  return { g, hooks, draws, bitmaps, tick, frame: () => frame };
}

test('paintStrips draws one band per frame and closes every band', async () => {
  const L = SS.stripLayout(2400, 1800, 1920, 1280, '50%', 1 << 20);
  const h = harness();
  const done = SS.paintStrips(h.g, {}, L, h.hooks);
  for (let i = 0; i < L.slices.length; i++) await h.tick();
  assert.equal(await done, true);
  assert.deepEqual(h.draws.map(d => d.frame), [...L.slices].map((_, i) => i + 1), 'one band per frame');
  assert.deepEqual(h.draws.map(d => d.y), [...L.slices].map(s => s.y));
  assert.deepEqual(h.bitmaps.map(b => [b.y, b.h]), [...L.slices].map(s => [L.src.y + s.y, s.h]));
  assert.ok(h.bitmaps.every(b => b.closed));
  assert.ok(h.bitmaps.every(b => b.x === L.src.x && b.w === L.canvas.w));
});

test('paintStrips waits while a dissolve runs, then resumes one band per frame', async () => {
  const L = SS.stripLayout(1000, 750, 800, 500, '50%', 800 * 4 * 200);
  const fading = new Set([2, 3, 4]);
  const h = harness({ gate: f => !fading.has(f) });
  const done = SS.paintStrips(h.g, {}, L, h.hooks);
  for (let i = 0; i < 8; i++) await h.tick();
  assert.equal(await done, true);
  assert.deepEqual(h.draws.map(d => d.frame), [1, 5, 6, 7].slice(0, L.slices.length));
  assert.ok(h.draws.every(d => !fading.has(d.frame)), 'no band during a dissolve');
});

test('paintStrips stops at an abandoned frame and closes the band it had cropped', async () => {
  const L = SS.stripLayout(2400, 1800, 1920, 1280, '50%', 1 << 20);
  const h = harness();
  const done = SS.paintStrips(h.g, {}, L, h.hooks);
  await h.tick(); await h.tick(); await h.tick(false);
  assert.equal(await done, false);
  assert.equal(h.draws.length, 2);
  for (let i = 0; i < 5; i++) await Promise.resolve();
  assert.ok(h.bitmaps.every(b => b.closed), 'the cropped-ahead band is closed too');
});

test('paintStrips never waits forever for a busy gate', async () => {
  const L = SS.stripLayout(100, 100, 100, 100, '50%', 1 << 20);
  const h = harness({ gate: () => false });
  const done = SS.paintStrips(h.g, {}, L, { ...h.hooks, maxWaitFrames: 3 });
  for (let i = 0; i < 4; i++) await h.tick();
  assert.equal(await done, true);
  assert.deepEqual(h.draws.map(d => d.frame), [4]);
});

test('paintStrips gives up on a failed crop or draw', async () => {
  const L = SS.stripLayout(2400, 1800, 1920, 1280, '50%', 1 << 20);
  const h = harness();
  let n = 0;
  const done = SS.paintStrips(h.g, {}, L, { ...h.hooks, crop: (...a) => ++n === 3 ? Promise.reject(new Error('x')) : h.hooks.crop(...a) });
  for (let i = 0; i < 4; i++) await h.tick();
  assert.equal(await done, false);
  assert.equal(h.draws.length, 2);
  const h2 = harness();
  const throwing = { drawImage: () => { throw new Error('lost'); } };
  const done2 = SS.paintStrips(throwing, {}, L, h2.hooks);
  await h2.tick();
  assert.equal(await done2, false);
  assert.ok(h2.bitmaps[0].closed);
});

// ---- The modes: Ken Burns, Push and Memories singles on the Portal+ ------------------------
const FILL = Array.from({ length: 8 }, (_, i) => ({ seed: 'fill-' + i, w: 1600, h: 1200 }));
function stripsWorld() {
  const world = { canvases: [], bitmaps: [], draws: [], images: [], body: null, sources: [] };
  return {
    world,
    configureWindow(window, context) {
      const loader = window.preloadDecoded;
      vm.runInContext(libEngine, context, { filename: 'lib-engine.js' });
      window.preloadDecoded = loader;
      const create = context.document.createElement;
      world.body = create('div');
      context.document.body = world.body;
      context.document.createElement = tag => {
        const node = create(tag);
        if (tag !== 'canvas') return node;
        node.listeners = {};
        node.addEventListener = (type, fn) => { (node.listeners[type] ||= []).push(fn); };
        node.getContext = () => ({ drawImage: (b, x, y) => {
          assert.equal(b.closed, false); world.draws.push({ canvas: node, y, at: context.Date.now() });
        } });
        world.canvases.push(node);
        return node;
      };
      context.Image = class {
        constructor() { this.naturalWidth = 0; this.naturalHeight = 0; world.images.push(this); }
        set src(value) {
          this._src = value;
          if (!value) return;
          context.setTimeout(() => { this.naturalWidth = 2400; this.naturalHeight = 1800; this.onload?.(); }, 0);
        }
        get src() { return this._src; }
      };
      context.createImageBitmap = (source, x, y, w, h) => {
        const b = { source, x, y, w, h, closed: false, close() { this.closed = true; } };
        (x === undefined ? world.sources : world.bitmaps).push(b);
        return Promise.resolve(b);
      };
      world.SS = window.SS;
    },
  };
}
// The fixture drains 64 microtasks per timer; the band chain is longer, so time moves in 8 ms
// steps and every step drains again.
function clock(f) {
  let at = 0;
  return async to => { while (at < to) { at = Math.min(to, at + 8); await f.advanceTo(at); } };
}
const heroOf = fixture => fixture.nodes().filter(n => n.classList.contains('ss-hero') && n.parent &&
  n.parent.classList.contains('on')).at(-1);
const canvasIn = node => node.children.find(c => c.className === 'ss-strip-canvas');

for (const mode of ['kenBurns', 'push', 'memories']) {
  test(`${mode}: a fill single is painted in bands before it shows, then composited from the canvas`, async () => {
    const { world, configureWindow } = stripsWorld();
    const f = createSceneFixture({ mode, cls: 'L', W: 1920, H: 1280, photoMs: 8000, photos: FILL, configureWindow });
    const play = clock(f);
    await play(2000);
    assert.equal(await f.cleanup.ready, true);
    const hero = heroOf(f, mode);
    const canvas = canvasIn(hero);
    assert.ok(canvas, 'the hero shows the painted canvas');
    assert.equal(f.computed(hero)['background-image'], 'none');
    assert.equal(f.computed(hero).overflow, 'hidden');
    assert.equal(canvas.width, 2400); assert.equal(canvas.height, 1600);
    assert.equal(canvas.style.width, '1920px');
    const draws = world.draws.filter(d => d.canvas === canvas);
    assert.equal(draws.length, 15);
    assert.ok(draws.every((d, i) => i === 0 || d.at > draws[i - 1].at), 'one band per frame');
    assert.equal(world.body.children.length, 0, 'the warm corner is gone');
    assert.ok(world.bitmaps.every(b => b.closed) && world.sources.every(b => b.closed));
    assert.ok(world.images.every(img => img.src === ''), 'the band source image is dropped');
    // The CSS background never carried this photo, so Chromium has nothing to upload at the change.
    assert.ok(!f.writes.some(w => w.node === hero && /url\(/.test(w.value)));
    // Next change: no band while the 2 s dissolve runs; the retired canvas is freed.
    await play(10600);
    const next = heroOf(f, mode);
    assert.notEqual(next, hero);
    const second = canvasIn(next);
    assert.ok(second, 'the next photo is a canvas too');
    await play(14000);
    assert.equal(canvas.width, 0, 'the retired canvas is freed');
    assert.equal(canvas.parentNode, null);
    f.cleanup();
    assert.equal(second.width, 0);
    assert.equal(world.body.children.length, 0);
  });
}

test('a band never shares a frame with a dissolve', async () => {
  const { world, configureWindow } = stripsWorld();
  const f = createSceneFixture({ mode: 'kenBurns', cls: 'L', W: 1920, H: 1280, photoMs: 3000, photos: FILL, configureWindow });
    const play = clock(f);
  const busy = [];
  const realBegin = world.SS.fades.begin, realEnd = world.SS.fades.end;
  let open = 0;
  world.SS.fades.begin = k => { open++; realBegin(k); };
  world.SS.fades.end = k => { open = Math.max(0, open - 1); realEnd(k); };
  const draw = world.draws.push.bind(world.draws);
  world.draws.push = d => { busy.push(world.SS.fades.busy()); return draw(d); };
  await play(20000);
  assert.ok(world.draws.length >= 45, 'several photos were painted');
  assert.ok(busy.every(b => b === false), 'a band was drawn during a dissolve');
  f.cleanup();
});

test('cleanup while painting stops the bands and frees the canvas', async () => {
  const { world, configureWindow } = stripsWorld();
  const f = createSceneFixture({ mode: 'kenBurns', cls: 'L', W: 1920, H: 1280, photoMs: 8000, photos: FILL, configureWindow });
    const play = clock(f);
  await play(150);
  const drawn = world.draws.length;
  assert.ok(drawn > 0 && drawn < 15, `mid-paint (${drawn})`);
  f.cleanup();
  await play(2000);
  assert.equal(world.draws.length, drawn, 'no band after cleanup');
  assert.ok(world.canvases.every(c => c.width === 0), 'canvas freed');
  assert.equal(world.body.children.length, 0, 'warm corner removed');
  assert.ok(world.bitmaps.every(b => b.closed) && world.sources.every(b => b.closed));
});

test('pause holds the bands; resume finishes them', async () => {
  const { world, configureWindow } = stripsWorld();
  const f = createSceneFixture({ mode: 'kenBurns', cls: 'L', W: 1920, H: 1280, photoMs: 8000, photos: FILL, configureWindow });
    const play = clock(f);
  await play(150);
  f.cleanup.pause();
  const drawn = world.draws.length;
  await play(3000);
  assert.ok(world.draws.length <= drawn + 1, 'at most the band already granted');
  const held = world.draws.length;
  f.cleanup.resume();
  await play(4000);
  assert.ok(world.draws.length > held);
  assert.equal(await f.cleanup.ready, true);
  assert.ok(canvasIn(heroOf(f, 'kenBurns')));
  f.cleanup();
});

test('a lost GPU context falls back to the CSS background', async () => {
  const { world, configureWindow } = stripsWorld();
  const f = createSceneFixture({ mode: 'kenBurns', cls: 'L', W: 1920, H: 1280, photoMs: 8000, photos: FILL, configureWindow });
    const play = clock(f);
  await play(2000);
  const hero = heroOf(f, 'kenBurns'), canvas = canvasIn(hero);
  canvas.listeners.contextlost.forEach(fn => fn());
  assert.equal(canvasIn(hero), undefined);
  assert.equal(canvas.width, 0);
  assert.match(f.computed(hero)['background-image'], /^url\("generated:\/\/fill-/);
  f.cleanup();
});

test('a whole photo or a pair keeps its CSS background', async () => {
  const { world, configureWindow } = stripsWorld();
  const tall = Array.from({ length: 8 }, (_, i) => ({ seed: 'tall-' + i, w: 1200, h: 1600 }));
  const f = createSceneFixture({ mode: 'kenBurns', cls: 'L', W: 1920, H: 1280, photoMs: 8000, photos: tall, configureWindow });
    const play = clock(f);
  await play(2000);
  assert.equal(await f.cleanup.ready, true);
  assert.equal(world.draws.length, 0);
  assert.equal(canvasIn(heroOf(f, 'kenBurns')), undefined);
  f.cleanup();
});
