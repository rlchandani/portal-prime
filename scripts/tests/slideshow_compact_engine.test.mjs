/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */
// Screen-aware screensaver phase 2 (mock mini-screensaver-20261120, AB.*): Ken Burns and Push pan
// along the long axis, the upright Memories slot table, the Gallery wall tile count by area, the
// Polaroid re-grid on rotation, and the compact decode/backdrop savings. The Portal+ ("L") output
// stays pinned by the reviewed snapshots elsewhere; the L checks here only prove the gates.
import test from 'node:test';
import assert from 'node:assert/strict';
import { readFileSync } from 'node:fs';
import vm from 'node:vm';
import { createSceneFixture } from './helpers/slideshow_scene_fixture.mjs';

const assets = new URL('../../app/src/main/assets/screensaver/', import.meta.url);
function modes() {
  const context = { window: {} };
  vm.createContext(context);
  vm.runInContext(readFileSync(new URL('modes.js', assets), 'utf8'), context);
  return context.window.SS;
}
const SS = modes();
const plain = value => JSON.parse(JSON.stringify(value));
const near = (a, b, eps = .01) => assert.ok(Math.abs(a - b) < eps, `${a} != ${b}`);
const px = value => parseFloat(value);

const portraits = (n = 8) => Array.from({ length: n }, (_, i) => ({ seed: 'portrait-' + i, w: 1000, h: 1500 }));
const landscapes = (n = 8) => Array.from({ length: n }, (_, i) => ({ seed: 'landscape-' + i, w: 1500, h: 1000 }));
const mixed = (n = 12) => Array.from({ length: n }, (_, i) => ({
  seed: 'mixed-' + i, w: i % 3 ? 1500 : 1000, h: i % 3 ? 1000 : 1500,
}));
const heroes = r => r.nodes().filter(n => n.classList.contains('ss-hero') && n.style.backgroundImage?.startsWith('url('));
const shown = r => heroes(r).find(n => n.parent.classList.contains('on'));

// ---- geometry ----------------------------------------------------------------------------------

test('compact pan: upright landscapes fill the height and travel the whole width, alternating', () => {
  const a = SS.compactPan(1.5, 800, 1280, 'CP', 0);
  assert.equal(a.mode, 'pan'); assert.equal(a.axis, 'x');
  assert.deepEqual(plain(a.rect), { x: 0, y: 0, w: 1920, h: 1280 });
  assert.equal(a.from, 0); assert.equal(a.to, 800 - 1920);
  const b = SS.compactPan(1.5, 800, 1280, 'CP', 1);
  assert.equal(b.from, 800 - 1920); assert.equal(b.to, 0);
  // A square upright is not wider than tall: it covers with the gentle zoom.
  assert.equal(SS.compactPan(1, 800, 1280, 'CP', 0).mode, 'fill');
});

test('compact pan: turned portraits fill the width and travel top to bottom', () => {
  const p = SS.compactPan(2 / 3, 1280, 800, 'CL', 0);
  assert.equal(p.mode, 'pan'); assert.equal(p.axis, 'y');
  near(p.rect.w, 1280); near(p.rect.h, 1920);
  assert.equal(p.from, 0); near(p.to, 800 - 1920);
  // Every beat runs top to bottom.
  assert.equal(SS.compactPan(2 / 3, 1280, 800, 'CL', 1).from, 0);
});

test('compact pan: panoramas sweep at full height in both orientations', () => {
  for (const [W, H] of [[800, 1280], [1280, 800]]) {
    for (const beat of [0, 1]) {
      const p = SS.compactPan(3, W, H, SS.layoutClass(W, H), beat);
      assert.equal(p.axis, 'x'); assert.equal(p.rect.h, H); assert.equal(p.rect.w, 3 * H);
      assert.equal(p.from, 0); assert.equal(p.to, W - 3 * H);
    }
  }
});

test('compact pan: a photo matching the screen covers with 1 -> 1.09 (push 1 -> 1.06)', () => {
  const up = SS.compactPan(2 / 3, 800, 1280, 'CP', 0);
  assert.equal(up.mode, 'fill');
  assert.deepEqual(plain(up.rect), { x: 0, y: 0, w: 800, h: 1280 });
  assert.deepEqual(plain(up.zoom), [1, 1.09]); assert.equal(up.drift, -2);
  assert.equal(SS.compactPan(2 / 3, 800, 1280, 'CP', 1).drift, 2);
  assert.deepEqual(plain(SS.compactPan(2 / 3, 800, 1280, 'CP', 2).zoom), [1.09, 1]);
  const turned = SS.compactPan(1.5, 1280, 800, 'CL', 0);
  assert.equal(turned.mode, 'fill');
  const push = SS.compactPan(2 / 3, 800, 1280, 'CP', 2, true);
  assert.deepEqual(plain(push.zoom), [1, 1.06]); assert.equal(push.drift, 0);
  assert.equal(SS.compactPan(NaN, 800, 1280, 'CP', 0).mode, 'fill');
});

test('compact decode size follows the drawn size, capped by the request', () => {
  // A cover crop of a 2:3 portrait upright: 853 x 1280, plus the 9 % zoom.
  near(SS.compactDrawnEdge(SS.compactPan(2 / 3, 800, 1280, 'CP', 0), 2 / 3), 1280 * 1.09);
  near(SS.compactDrawnEdge(SS.compactPan(1, 800, 1280, 'CP', 0), 1), 1280 * 1.09);
  near(SS.compactDrawnEdge(SS.compactPan(1.5, 800, 1280, 'CP', 0), 1.5), 1920);
  assert.equal(SS.compactDrawnEdge(null, 1), null);
  assert.equal(SS.compactEdge(2200, undefined, 800, 1280, 1), 1280);
  assert.equal(SS.compactEdge(1800, undefined, 1280, 800, 1.5), 1800);
  assert.equal(SS.compactEdge(2200, undefined, 800, 1280, 1.5), 1920);
  assert.equal(SS.compactEdge(2200, 1920, 800, 1280, 1), 1920);
  assert.equal(SS.compactEdge(2200, 3840, 800, 1280, 1), 2200);
  assert.equal(SS.compactEdge(2200, null, 800, 1280, 1), 2200, 'unknown shape keeps the request');
  assert.equal(SS.compactEdge(1100, undefined, 800, 1280, NaN), 1100);
  assert.equal(SS.compactEdge(1200, 10, 800, 1280, 1), 320);
});

test('gallery plan scales the tile count and rows to the compact area', () => {
  assert.deepEqual(plain(SS.galleryPlan('CP', 800, 1280)),
    { count: 7, area: { x: 28, y: 28, w: 744, h: 980 }, target: 256, gutter: 12, maxRows: 5 });
  const turned = SS.galleryPlan('CL', 1280, 800);
  assert.equal(turned.count, 6); assert.equal(turned.maxRows, 3); assert.equal(turned.gutter, 14);
  assert.deepEqual(plain(turned.area), { x: 28, y: 28, w: 1224, h: 744 });
  assert.ok(turned.target >= 800 * .42);
  assert.equal(SS.galleryPlan('L', 2160, 1440), null);
});

test('polaroid grid follows the window shape', () => {
  assert.deepEqual(plain(SS.polaroidGrid(6, 800, 1280)), { cols: 2, rows: 3 });
  assert.deepEqual(plain(SS.polaroidGrid(6, 1280, 800)), { cols: 4, rows: 2 });
  assert.deepEqual(plain(SS.polaroidGrid(6, 2160, 1440)), { cols: 3, rows: 2 });
});

// ---- Ken Burns and Push ------------------------------------------------------------------------

for (const mode of ['kenBurns', 'push']) {
  test(`${mode}: upright landscapes pan across, alternating direction per beat`, async () => {
    const r = createSceneFixture({ mode, W: 800, H: 1280, cls: 'CP', photos: landscapes() });
    try {
      assert.equal(await r.cleanup.ready, true);
      let hero = shown(r);
      assert.equal(hero.style.width, '1920px'); assert.equal(hero.style.height, '1280px');
      assert.equal(hero.style.left, '0px'); assert.equal(hero.style.top, '0px');
      assert.match(hero.style.animation, /^compactPanX 5400ms ease-in-out forwards$/);
      assert.equal(hero.style['--pan-from'], '0px'); assert.equal(hero.style['--pan-to'], '-1120px');
      assert.equal(hero.style.willChange, 'transform');
      assert.equal(hero.children[0].style.backgroundImage, 'none', 'no border over a full-bleed pan');
      assert.equal(r.nodes().filter(n => n.classList.contains('ss-compact-fill')).length, 0);
      await r.advanceTo(5000);
      hero = heroes(r).at(-1);
      assert.equal(hero.style['--pan-from'], '-1120px'); assert.equal(hero.style['--pan-to'], '0px');
    } finally { r.cleanup(); }
  });

  test(`${mode}: turned portraits pan top to bottom`, async () => {
    const r = createSceneFixture({ mode, W: 1280, H: 800, cls: 'CL', photos: portraits() });
    try {
      assert.equal(await r.cleanup.ready, true);
      const hero = shown(r);
      assert.equal(hero.style.width, '1280px'); assert.equal(hero.style.height, '1920px');
      assert.match(hero.style.animation, /^compactPanY /);
      assert.equal(hero.style['--pan-from'], '0px'); assert.equal(hero.style['--pan-to'], '-1120px');
    } finally { r.cleanup(); }
  });

  test(`${mode}: a matching photo covers the screen with its zoom`, async () => {
    const r = createSceneFixture({ mode, W: 800, H: 1280, cls: 'CP', photos: portraits() });
    try {
      assert.equal(await r.cleanup.ready, true);
      const hero = shown(r);
      assert.equal(hero.style.backgroundSize, 'cover');
      assert.equal(hero.style.width, '800px'); assert.equal(hero.style.height, '1280px');
      assert.match(hero.style.animation, /^compactZoom /);
      assert.equal(hero.style['--z1'], mode === 'push' ? 1.06 : 1.09);
      // Decoded at the drawn size (the cover crop plus its zoom), not the 2200 px request.
      const request = r.resolutions.at(-1);
      assert.equal(request.edge, 2200);
      near(request.drawn, 1280 * (mode === 'push' ? 1.06 : 1.09));
    } finally { r.cleanup(); }
  });

  test(`${mode}: a cached face holds the pan still on the face`, async () => {
    const photos = landscapes().map(p => ({ ...p, fx: .9, fy: .4 }));
    const r = createSceneFixture({ mode, W: 800, H: 1280, cls: 'CP', photos });
    try {
      assert.equal(await r.cleanup.ready, true);
      const hero = shown(r);
      assert.equal(hero.style.animation, 'none');
      // Face at 90 % of 1920: centred would be 400 - 1728, clamped to the far edge (-1120).
      assert.equal(hero.style.transform, 'translateX(-1120px)');
    } finally { r.cleanup(); }
  });

  test(`${mode}: motion off rests the pan on the middle of the photo`, async () => {
    const r = createSceneFixture({ mode, W: 800, H: 1280, cls: 'CP', photos: landscapes(), photoMotion: false });
    try {
      assert.equal(await r.cleanup.ready, true);
      const hero = shown(r);
      assert.equal(hero.style.animation, 'none'); assert.equal(hero.style.willChange, 'auto');
      assert.equal(hero.style.transform, 'translateX(-560px)');
    } finally { r.cleanup(); }
  });

  test(`${mode}: the Portal+ requests and lays out exactly as shipped`, async () => {
    const r = createSceneFixture({ mode, photos: landscapes() });
    try {
      assert.equal(await r.cleanup.ready, true);
      assert.ok(r.resolutions.every(n => n.edge === 2200 && !('drawn' in n)));
      const hero = shown(r);
      assert.equal(hero.style.backgroundSize, 'contain');
      assert.ok(!hero.classList.contains('ss-compact'));
      assert.ok(!/compactPan|compactZoom/.test(hero.style.animation || ''));
      assert.ok(hero.children[0].style.backgroundImage.startsWith('url('), 'border kept');
    } finally { r.cleanup(); }
  });
}

// ---- Memories ----------------------------------------------------------------------------------

function memories(options) {
  const r = createSceneFixture({ mode: 'memories', ...options });
  const root = r.nodes()[0];
  root.clientWidth = options.W; root.clientHeight = options.H;
  return r;
}
async function toCollage(r) {
  await r.cleanup.ready;
  await r.advanceTo(27000);
  r.end(r.prints().at(-1));
}

test('memories upright: the collage uses the tall slot table, tilted cards and the serif title', async () => {
  const photos = Array.from({ length: 9 }, (_, i) => ({ seed: 'm' + i, w: 1500, h: 1000 }));
  const r = memories({ W: 800, H: 1280, cls: 'CP', photos });
  try {
    await toCollage(r);
    const cards = r.nodes().filter(n => n.classList.contains('ss-memory-card'));
    assert.equal(cards.length, 3);
    const slots = [[.06, .09, .7, .3], [.42, .3, .52, .42], [.08, .55, .62, .26]];
    cards.forEach((card, i) => {
      const [fx, fy, fw, fh] = slots[i], box = { x: fx * 800, y: fy * 1280, w: fw * 800, h: fh * 1280 };
      const imageH = Math.min(box.h - 24, (box.w - 24) / 1.5), w = imageH * 1.5 + 24, h = imageH + 24;
      near(px(card.style.left), box.x + (box.w - w) / 2); near(px(card.style.top), box.y + (box.h - h) / 2);
      near(px(card.style.width), w); near(px(card.style.height), h);
      assert.equal(card.style.transform, `rotate(${[-2, 1.5, -1][i]}deg)`);
    });
    const title = r.nodes().find(n => n.classList.contains('ss-memory-caption'));
    assert.equal(title.textContent, 'On this day');
    assert.ok(title.classList.contains('upright'));
    const style = r.computed(title);
    assert.equal(style.left, '40px'); assert.equal(style.top, '34px');
    assert.match(style['font-family'], /serif$/);
  } finally { r.cleanup(); }
});

test('memories turned keeps the shipped collage and title', async () => {
  const photos = Array.from({ length: 9 }, (_, i) => ({ seed: 'm' + i, w: 1500, h: 1000 }));
  const r = memories({ W: 1280, H: 800, cls: 'CL', photos });
  try {
    await toCollage(r);
    const cards = r.nodes().filter(n => n.classList.contains('ss-memory-card'));
    assert.equal(cards.length, 3);
    assert.ok(cards.every(card => !card.style.transform));
    const [x, y, sw, sh] = [.08, .16, .42, .5], box = { w: sw * 1280, h: sh * 800 };
    const imageH = Math.min(box.h - 24, (box.w - 24) / 1.5);
    near(px(cards[0].style.left), 1280 * x + (box.w - imageH * 1.5 - 24) / 2);
    near(px(cards[0].style.top), 800 * y + (box.h - imageH - 24) / 2);
    const title = r.nodes().find(n => n.classList.contains('ss-memory-caption'));
    assert.equal(title.textContent, 'Photo memories');
    assert.ok(!title.classList.contains('upright'));
  } finally { r.cleanup(); }
});

// A canvas 2D stub and an Image that loads on the next microtask.
function canvasWindow(calls) {
  return (window, context) => {
    const create = context.document.createElement;
    context.document = { createElement(tag) {
      const node = create(tag);
      if (tag === 'canvas') {
        node.tag = 'canvas';
        node.getContext = () => ({
          filter: 'none', imageSmoothingQuality: 'low',
          drawImage: (...args) => calls.push(['drawImage', ...args.slice(1)]),
          fillRect: (...args) => calls.push(['fillRect', ...args]),
          set fillStyle(v) { calls.push(['fillStyle', v]); },
        });
      }
      return node;
    } };
    context.Image = class {
      constructor() { this.naturalWidth = 480; this.naturalHeight = 320; }
      set src(value) {
        this.current = value;
        if (value) Promise.resolve().then(() => this.onload && this.onload());
      }
      get src() { return this.current; }
    };
  };
}

test('memories upright: a whole photo sits over a pre-blurred canvas, not a live CSS blur', async () => {
  const calls = [];
  const r = memories({ W: 800, H: 1280, cls: 'CP', photos: landscapes(), configureWindow: canvasWindow(calls) });
  try {
    assert.equal(await r.cleanup.ready, true);
    const fill = r.nodes().find(n => n.classList.contains('ss-compact-fill'));
    assert.ok(fill.classList.contains('ss-preblur'));
    assert.equal(fill.style.backgroundImage, 'none');
    const canvas = fill.children[0];
    assert.equal(canvas.tag, 'canvas'); assert.equal(canvas.width, 30); assert.equal(canvas.height, 48);
    assert.equal(calls.filter(c => c[0] === 'drawImage').length, 1);
    assert.ok(calls.some(c => c[0] === 'fillStyle' && c[1] === 'rgba(0,0,0,0.45)'));
    const style = r.computed(fill);
    assert.equal(style.filter, 'none'); assert.equal(style.transform, 'none'); assert.equal(style.inset, '0');
    assert.ok(r.resolutions.some(n => n.edge === 320));
  } finally { r.cleanup(); }
});

test('memories upright without canvas support keeps the blurred 320 px copy', async () => {
  const r = memories({ W: 800, H: 1280, cls: 'CP', photos: landscapes() });
  try {
    assert.equal(await r.cleanup.ready, true);
    const fill = r.nodes().find(n => n.classList.contains('ss-compact-fill'));
    assert.ok(!fill.classList.contains('ss-preblur'));
    assert.ok(fill.style.backgroundImage.startsWith('url('));
    assert.match(r.computed(fill).filter, /^blur\(/);
  } finally { r.cleanup(); }
});

// ---- Gallery wall ------------------------------------------------------------------------------

function gallery(options) {
  const r = createSceneFixture({ mode: 'galleryWall', photos: mixed(), ...options });
  const root = r.nodes()[0];
  if (options.W) { root.clientWidth = options.W; root.clientHeight = options.H; }
  return r;
}
const gcells = r => r.nodes().filter(n => n.classList.contains('ss-gcell'));
function rect(cell) {
  return { x: px(cell.style.left), y: px(cell.style.top), w: px(cell.style.width), h: px(cell.style.height) };
}

test('gallery wall upright: seven tiles above the clock, rows about 0.2 H', async () => {
  const r = gallery({ W: 800, H: 1280, cls: 'CP' });
  try {
    assert.equal(await r.cleanup.ready, true);
    const cells = gcells(r).map(rect);
    assert.equal(cells.length, 7);
    for (const c of cells) {
      assert.ok(c.x >= 28 - .5 && c.y >= 28 - .5, JSON.stringify(c));
      assert.ok(c.x + c.w <= 772 + .5 && c.y + c.h <= 1008 + .5, JSON.stringify(c));
      assert.ok(c.h <= 1280 * .2 * 1.6 && c.h >= 150, `row height ${c.h}`);
    }
    assert.ok(new Set(cells.map(c => Math.round(c.y))).size <= 5);
    const wall = r.nodes().find(n => n.classList.contains('ss-wall'));
    assert.ok(wall.classList.contains('compact'));
    assert.equal(r.computed(r.nodes().find(n => n.classList.contains('ss-gcell'))).transition, 'none');
  } finally { r.cleanup(); }
});

test('gallery wall turned: six tiles in at most three tall rows', async () => {
  const r = gallery({ W: 1280, H: 800, cls: 'CL' });
  try {
    assert.equal(await r.cleanup.ready, true);
    const cells = gcells(r).map(rect);
    assert.equal(cells.length, 6);
    for (const c of cells) {
      assert.ok(c.x >= 28 - .5 && c.y >= 28 - .5 && c.x + c.w <= 1252 + .5 && c.y + c.h <= 772 + .5, JSON.stringify(c));
      assert.ok(c.h >= 240, `tile ${c.h} dp tall (was ~159)`);
    }
    assert.ok(new Set(cells.map(c => Math.round(c.y))).size <= 3);
  } finally { r.cleanup(); }
});

test('gallery wall on the Portal+ keeps ten tiles and the animated reflow', async () => {
  const r = gallery({});
  try {
    assert.equal(await r.cleanup.ready, true);
    assert.equal(gcells(r).length, 10);
    assert.ok(!r.nodes().find(n => n.classList.contains('ss-wall')).classList.contains('compact'));
    assert.match(r.computed(gcells(r)[0]).transition, /^left /);
  } finally { r.cleanup(); }
});

// ---- Polaroid ----------------------------------------------------------------------------------

function polaroid(options) {
  const events = new Map();
  let win;
  const r = createSceneFixture({ mode: 'polaroid', photos: mixed(), ...options, configureWindow(window) {
    win = window;
    window.addEventListener = (name, callback) => events.set(name, callback);
    window.removeEventListener = () => {};
  } });
  const root = r.nodes()[0];
  root.clientWidth = options.W || 2160; root.clientHeight = options.H || 1440;
  let now = 0;
  return Object.assign(r, {
    root, SS: win.SS,
    async resize(w, h) {
      root.clientWidth = w; root.clientHeight = h; events.get('resize')(); await r.advanceTo(now += 16);
    },
    advance: async ms => r.advanceTo(now += ms),
    cards: () => r.nodes().filter(n => n.classList.contains('ss-polaroid') && n.classList.contains('on')),
  });
}
// The grid cell (column, row) holding each card's centre.
function cellsOf(r, cols, rows) {
  const area = r.SS.photoLayoutArea({ root: r.root, W: r.root.clientWidth, H: r.root.clientHeight });
  return r.cards().map(card => {
    const cx = px(card.style.left) + px(card.style.width) / 2, cy = px(card.style.top) + px(card.style.height) / 2;
    const col = Math.floor((cx - area.x) / (area.w / cols)), row = Math.floor((cy - area.y) / (area.h / rows));
    assert.ok(col >= 0 && col < cols && row >= 0 && row < rows, `card at ${cx},${cy} outside the ${cols}x${rows} grid`);
    return row * cols + col;
  });
}

test('polaroid re-grids when the Mini turns: 2 x 3 upright becomes 4 x 2 turned', async () => {
  const r = polaroid({ W: 800, H: 1280, cls: 'CP' });
  try {
    assert.equal(await r.cleanup.ready, true);
    assert.equal(r.cards().length, 6);
    assert.equal(new Set(cellsOf(r, 2, 3)).size, 6);
    const photos = r.prints().map(n => n.style.backgroundImage);
    await r.resize(1280, 800);
    assert.equal(new Set(cellsOf(r, 4, 2)).size, 6, 'one print per cell of the new grid');
    assert.deepEqual(r.prints().map(n => n.style.backgroundImage), photos, 'same prints, no reload');
    await r.resize(800, 1280);
    assert.equal(new Set(cellsOf(r, 2, 3)).size, 6);
  } finally { r.cleanup(); }
});

test('polaroid keeps dropping prints into free cells after a re-grid', async () => {
  const r = polaroid({ W: 800, H: 1280, cls: 'CP' });
  try {
    assert.equal(await r.cleanup.ready, true);
    await r.resize(1280, 800);
    await r.advance(6000);
    assert.equal(r.cards().length, 6);
    assert.equal(new Set(cellsOf(r, 4, 2)).size, 6);
  } finally { r.cleanup(); }
});

test('polaroid on the Portal+ keeps its boot grid on resize', async () => {
  const r = polaroid({});
  try {
    assert.equal(await r.cleanup.ready, true);
    const before = cellsOf(r, 3, 2);
    await r.resize(1440, 2160);
    assert.deepEqual(cellsOf(r, 3, 2), before);
  } finally { r.cleanup(); }
});

// ---- Scheduler: compact decode cap -------------------------------------------------------------

const scheduler = readFileSync(new URL('scheduler.js', assets), 'utf8');
async function engineEdges(W, H, dpr) {
  const photos = landscapes().map((p, i) => ({ ...p, url: 'generated://edge/' + i }));
  const edges = [];
  let context, window;
  const f = createSceneFixture({ photos, mode: 'framed', photoMs: 600000, configureWindow: (w, c) => {
    context = c; window = w;
    w.PHOTOS = photos; w.devicePixelRatio = dpr;
    w.photoUrl = (p, edge) => { edges.push(edge); return p.url; };
    c.innerWidth = W; c.innerHeight = H; c.screen = { width: W, height: H };
    c.addEventListener = () => {}; c.removeEventListener = () => {};
  } });
  f.cleanup(); edges.length = 0;
  const stage = f.nodes()[0]; stage.innerHTML = '';
  vm.runInContext(scheduler, context, { filename: 'scheduler.js' });
  const engine = window.SS.startEngine({ stage, mode: 'framed', intervalSec: 600, photoMotion: false });
  await f.flush(); await f.advanceTo(1800);
  engine.stop?.();
  return edges;
}

test('compact screens decode at most the display long edge; the Portal+ keeps its requests', async () => {
  assert.deepEqual([...new Set(await engineEdges(800, 1280, 1))], [1280]);
  assert.deepEqual([...new Set(await engineEdges(1280, 800, 1.25))], [1600]);
  assert.deepEqual([...new Set(await engineEdges(2160, 1440, 1))], [1800]);
});
