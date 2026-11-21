/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */
// Screensaver polish (mock screensaver-polish-20261121) in the running modes on the Portal+:
// portrait pairs and their whole-photo fallback in Ken Burns / Push / Memories, the pre-blurred
// backdrop's lifetime (computed before the dissolve, released with its layer), pause, and the
// exact-fit Hero + Sidebar grid.
import test from 'node:test';
import assert from 'node:assert/strict';
import { createSceneFixture } from './helpers/slideshow_scene_fixture.mjs';

const near = (a, b, eps = 1e-6) => assert.ok(Math.abs(a - b) < eps, `${a} != ${b}`);
const px = value => parseFloat(value);
const portrait = (seed, extra) => ({ seed, w: 1000, h: 1500, ...extra });
const landscape = seed => ({ seed, w: 1500, h: 1000 });
const square = seed => ({ seed, w: 1000, h: 1000 });

// A canvas 2D stub and an Image that loads on the next microtask (or on demand when [hold]).
function canvasWindow(log, { hold = false } = {}) {
  return (window, context) => {
    const create = context.document.createElement;
    log.canvases = []; log.images = []; log.draws = [];
    context.document = { createElement(tag) {
      const node = create(tag);
      if (tag === 'canvas') {
        node.tag = 'canvas'; log.canvases.push(node);
        node.getContext = () => ({
          filter: 'none', imageSmoothingQuality: 'low',
          drawImage: () => log.draws.push(log.now()), fillRect() {}, set fillStyle(v) {},
        });
      }
      return node;
    } };
    context.Image = class {
      constructor() { this.naturalWidth = 480; this.naturalHeight = 320; log.images.push(this); }
      set src(value) {
        this.current = value;
        if (value && !hold) Promise.resolve().then(() => this.onload && this.onload());
      }
      get src() { return this.current; }
    };
  };
}
function scene(mode, photos, options = {}) {
  const log = { now: () => 0 };
  const r = createSceneFixture({ mode, photos, configureWindow: canvasWindow(log, options), ...options });
  let time = 0;
  log.now = () => time;
  const advance = async t => { time = t; await r.advanceTo(t); };
  return Object.assign(r, { log, advance });
}
const heroes = r => r.nodes().filter(n => n.classList.contains('ss-hero') && n.style.backgroundImage?.startsWith('url('));
const layerOf = (r, node) => { let n = node; while (n.parent && !/ss-layer|ss-memory-scene/.test(n.className)) n = n.parent; return n; };
const shown = r => heroes(r).find(n => layerOf(r, n).classList.contains('on'));
const urls = node => [...node.style.backgroundImage.matchAll(/url\("([^"]+)"\)/g)].map(m => m[1]);

for (const mode of ['kenBurns', 'push', 'memories']) {
  test(`${mode}: two portraits share one height, nothing cropped, over one pre-blurred backdrop`, async () => {
    const r = scene(mode, [portrait('a'), landscape('b'), portrait('c'), landscape('d')]);
    try {
      assert.equal(await r.cleanup.ready, true);
      const hero = shown(r);
      assert.deepEqual(urls(hero), ['generated://a', 'generated://c'], 'the next portrait of the deck');
      const W = 2160, H = 1440, m = Math.round(1440 * .035), h = H - 2 * m, w = h * 2 / 3;
      const sizes = hero.style.backgroundSize.split(', ').map(v => v.split(' ').map(px));
      sizes.forEach(([sw, sh]) => { near(sw, w); near(sh, h); });
      assert.equal(hero.style.backgroundPosition, 'left center, right center');
      near(px(hero.style.width), 2 * w + 24); near(px(hero.style.height), h);
      near(px(hero.style.left), (W - 2 * w - 24) / 2); near(px(hero.style.top), m);
      assert.ok(!hero.classList.contains('ss-whole'), 'no box shadow across the gap');
      // The backdrop is the first photo's own 320 px copy, pre-blurred into a canvas.
      const fill = r.nodes().find(n => n.classList.contains('ss-compact-fill') && n.style.display !== 'none');
      assert.ok(fill.classList.contains('ss-preblur'));
      assert.equal(fill.children[0].tag, 'canvas');
      assert.deepEqual(r.resolutions.filter(n => n.edge === 320).map(n => n.photo.seed), ['a']);
      // The pair is asked for at its drawn size at the top of its motion: Ken Burns stops where
      // the pair touches the screen (H / h, under its 1.15 cap); the drift reaches 1.04.
      const top = mode === 'kenBurns' ? Math.min(1.15, H / h) : 1.04;
      const pair = r.resolutions.filter(n => n.edge !== 320);
      assert.deepEqual(pair.map(n => n.edge), [2200, 2200]);
      pair.forEach(n => near(n.drawn, h * top));
      // Motion and dissolve keep the shipped feel.
      if (mode === 'kenBurns') {
        assert.ok(hero.classList.contains('ss-motion'));
        assert.match(r.computed(hero).animation, /^kb /);
        assert.match(r.computed(layerOf(r, hero)).transition, /^opacity 2\.2s /);
      } else {
        assert.equal(hero.style.animation, 'cinematicDrift 5400ms linear forwards');
        assert.match(r.computed(layerOf(r, hero)).transition, mode === 'push' ? /^opacity 2\.2s / : /^opacity 2s /);
      }
      // The deck moves on past both photos.
      await r.advance(5000);
      assert.deepEqual(urls(heroes(r).at(-1)), ['generated://b']);
    } finally { r.cleanup(); }
  });

  test(`${mode}: a portrait with no portrait nearby is shown whole over its own backdrop`, async () => {
    const r = scene(mode, [portrait('a'), ...Array.from({ length: 7 }, (_, i) => landscape('l' + i)), portrait('z')]);
    try {
      assert.equal(await r.cleanup.ready, true);
      const hero = shown(r);
      assert.deepEqual(urls(hero), ['generated://a']);
      assert.equal(hero.style.backgroundSize, 'contain');
      assert.ok(hero.classList.contains('ss-whole'));
      const m = Math.round(1440 * .035);
      near(px(hero.style.height), 1440 - 2 * m); near(px(hero.style.width), (1440 - 2 * m) * 2 / 3);
      assert.equal(r.resolutions.find(n => n.photo.seed === 'a' && n.edge !== 320).edge, 2200);
      assert.ok(r.log.canvases.length >= 1);
    } finally { r.cleanup(); }
  });

  test(`${mode}: a matched photo fills the screen with no backdrop work`, async () => {
    const r = scene(mode, [landscape('a'), landscape('b')]);
    try {
      assert.equal(await r.cleanup.ready, true);
      const hero = shown(r);
      assert.equal(hero.style.backgroundSize, 'cover');
      assert.equal(r.log.canvases.length, 0);
      assert.ok(!r.resolutions.some(n => n.edge === 320));
    } finally { r.cleanup(); }
  });
}

test('the backdrop is drawn before the dissolve starts, never during it', async () => {
  const photos = [square('s0'), portrait('a'), square('s1'), square('s2'), square('s3'), square('s4'), square('s5'), square('s6')];
  const r = scene('kenBurns', photos);
  try {
    await r.cleanup.ready;
    for (let beat = 1; beat <= 4; beat++) {
      const before = r.log.draws.length;
      await r.advance(beat * 5000);
      const incoming = shown(r) && heroes(r).find(n => layerOf(r, n).classList.contains('on') && layerOf(r, n).opacity() < 1);
      assert.ok(incoming, 'a dissolve is running');
      assert.equal(r.log.draws.length, before + 1, 'this beat\'s backdrop is already drawn');
      assert.ok(r.log.draws.slice(before).every(at => at <= beat * 5000));
      await r.advance(beat * 5000 + 2300);
      assert.equal(r.log.draws.length, before + 1, 'nothing is drawn while the photo dissolves in');
      assert.equal(layerOf(r, incoming).opacity(), 1);
    }
  } finally { r.cleanup(); }
});

test('a retired layer releases its backdrop canvas', async () => {
  const r = scene('kenBurns', [square('a'), square('b'), square('c'), square('d')]);
  try {
    await r.cleanup.ready;
    const first = r.log.canvases[0];
    assert.ok(first.width > 0 && first.height > 0);
    await r.advance(5000); await r.advance(7400);
    assert.equal(first.width, 0); assert.equal(first.height, 0);
    assert.ok(r.log.canvases.at(-1).width > 0, 'the visible photo keeps its own');
    // Over many beats only the two live layers hold pixels.
    for (let beat = 2; beat <= 12; beat++) {
      await r.advance(beat * 5000); await r.advance(beat * 5000 + 2400);
    }
    assert.ok(r.log.canvases.filter(c => c.width > 0).length <= 2);
    r.cleanup();
    assert.equal(r.log.canvases.filter(c => c.width > 0).length, 0, 'cleanup releases every canvas');
  } finally { r.cleanup(); }
});

test('a beat torn down while its backdrop is being drawn publishes nothing and frees the canvas', async () => {
  const r = scene('kenBurns', [square('a'), square('b')], { hold: true });
  try {
    await r.flush();
    const image = r.log.images.at(-1);
    assert.ok(image && image.onload, 'the backdrop decode is pending');
    r.cleanup();
    image.onload(); await r.flush();
    assert.equal(heroes(r).length, 0);
    assert.ok(r.log.canvases.every(c => c.width === 0));
    assert.equal(await r.cleanup.ready, false);
  } finally { r.cleanup(); }
});

test('pause holds a prepared pair off screen until resume', async () => {
  const r = scene('push', [portrait('a'), portrait('b'), portrait('c'), portrait('d')]);
  try {
    await r.cleanup.ready;
    r.cleanup.pause();
    await r.advance(60000);
    assert.deepEqual(urls(heroes(r).at(-1)), ['generated://a', 'generated://b'], 'no swap while paused');
    r.cleanup.resume();
    await r.advance(65000);
    assert.deepEqual(urls(heroes(r).at(-1)), ['generated://c', 'generated://d']);
  } finally { r.cleanup(); }
});

test('a face in either photo holds the pair still', async () => {
  const r = scene('kenBurns', [portrait('a'), portrait('b', { fx: .5, fy: .3 })]);
  try {
    await r.cleanup.ready;
    const hero = shown(r);
    assert.equal(urls(hero).length, 2);
    assert.equal(hero.style.animation, 'none'); assert.equal(hero.style.willChange, 'auto');
    assert.ok(!hero.classList.contains('ss-motion'));
  } finally { r.cleanup(); }
});

test('hero + sidebar on the Portal+: hero left, 20 px gutters, clear of the clock; later swaps glide', async () => {
  const photos = Array.from({ length: 9 }, (_, i) => i % 3 ? portrait('p' + i) : landscape('l' + i));
  const r = createSceneFixture({ mode: 'heroSidebar', photos });
  try {
    const stage = r.nodes().find(n => n.classList.contains('ss-hs'));
    assert.ok(!stage.classList.contains('glide'), 'the startup layout never glides');
    assert.equal(await r.cleanup.ready, true); await r.flush();
    assert.ok(stage.classList.contains('glide'));
    assert.match(r.computed(r.nodes().find(n => n.classList.contains('ss-cell'))).transition, /^left \.8s/);
    const [hero, a, b] = r.nodes().filter(n => n.classList.contains('ss-cell')).map(c =>
      ({ x: px(c.style.left), y: px(c.style.top), w: px(c.style.width), h: px(c.style.height) }));
    near(hero.w / hero.h, 1.5); near(a.w / a.h, 2 / 3); near(b.w / b.h, 2 / 3);
    near(a.x, hero.x + hero.w + 20); near(b.x, a.x); near(b.y, a.y + a.h + 20);
    near(hero.y, a.y); near(hero.y + hero.h, b.y + b.h);
    // No host bounds: the default lower strip (min(22 % H, 180 px)) stays clear for the clock.
    assert.ok(hero.y + hero.h <= 1440 - 180 + 1e-6);
    assert.ok(r.nodes().every(n => !/photo-doodles/.test(r.computed(n)['background-image'] || '')), 'no doodles');
  } finally { r.cleanup(); }
});

test('ken burns keeps a whole photo or pair on screen: the zoom stops at the screen edge', async () => {
  for (const [photos, random] of [[[portrait('a'), portrait('b')], .9], [[portrait('a'), portrait('b')], .1],
    [[square('a'), square('b')], .9]]) {
    const r = scene('kenBurns', photos, { random });
    try {
      await r.cleanup.ready;
      const hero = shown(r), w = px(hero.style.width), h = px(hero.style.height);
      const s = [Number(hero.style['--s0']), Number(hero.style['--s1'])];
      const fit = Math.min(2160 / w, 1440 / h);
      assert.deepEqual([...s].sort(), [1, Math.min(1.15, fit)].sort(), 'zooms between rest and the edge');
      assert.ok(Math.max(...s) * h <= 1440 + 1e-6 && Math.max(...s) * w <= 2160 + 1e-6);
      for (const k of ['--x0', '--y0', '--x1', '--y1']) assert.equal(hero.style[k], '0%', 'centred, no drift off screen');
      assert.equal(s[0] < s[1], random > .4, 'the same zoom-in / zoom-out draw as before');
    } finally { r.cleanup(); }
  }
  // A full-bleed photo keeps the shipped 1.04 <-> 1.15 Ken Burns with its drift.
  const r = scene('kenBurns', [landscape('a'), landscape('b')], { random: .9 });
  try {
    await r.cleanup.ready;
    const hero = shown(r);
    assert.deepEqual([hero.style['--s0'], hero.style['--s1']].map(Number), [1.04, 1.15]);
  } finally { r.cleanup(); }
});
