/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */
// The screen-aware Shuffle on the Portal Mini (mock mini-screensaver-20261120): layout classes,
// the compact single-photo rule, the upright pool and the upright mode layouts. The Portal+
// ("L") output is pinned separately by the reviewed snapshots in slideshow_photo_motion.
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
// Objects from the vm realm compare by value.
const same = (a, b) => assert.deepEqual(JSON.parse(JSON.stringify(a)), b);
const near = (a, b, eps = .01) => assert.ok(Math.abs(a - b) < eps, `${a} != ${b}`);

test('layout class follows the window size like LayoutClass.kt, never the model', () => {
  assert.equal(SS.layoutClass(1920, 1219), 'L');
  assert.equal(SS.layoutClass(1219, 1920), 'L');
  assert.equal(SS.layoutClass(800, 1280), 'CP');
  assert.equal(SS.layoutClass(800, 1220), 'CP');
  assert.equal(SS.layoutClass(1280, 800), 'CL');
  assert.equal(SS.layoutClass(1280, 740), 'CL');
  assert.equal(SS.layoutClass(999, 999), 'CL');
  assert.equal(SS.layoutClass(1000, 1600), 'L');
  assert.equal(SS.layoutClass(0, 0), 'L');
  assert.equal(SS.layoutClass(NaN, 800), 'L');
});

test('upright Shuffle never picks Cinema; turned and the Portal+ keep all twelve', () => {
  const pool = Array.from({ length: 12 }, (_, i) => ({ seed: 'p' + i, w: i % 3 ? 1500 : 3600, h: 1000 }));
  assert.ok(!SS.poolFor('CP', SS.MODE_IDS).includes('cinema'));
  assert.equal(SS.poolFor('CP', SS.MODE_IDS).length, 11);
  same(SS.poolFor('CL', SS.MODE_IDS), [...SS.MODE_IDS]);
  same(SS.poolFor('L', SS.MODE_IDS), [...SS.MODE_IDS]);
  const original = Math.random;
  try {
    for (let i = 0; i < 400; i++) {
      Math.random = () => (i % 97) / 97;
      assert.notEqual(SS.pickNextMode(SS.MODE_IDS, pool, null, 'CP'), 'cinema');
    }
    Math.random = () => .999;
    assert.equal(SS.pickNextMode(['cinema'], pool, null, 'CP'), 'kenBurns');
    assert.equal(SS.pickNextMode(['cinema'], pool, null, 'CL'), 'cinema');
    assert.equal(SS.pickNextMode(['cinema'], pool, null), 'cinema');
  } finally { Math.random = original; }
  assert.equal(SS.modeFor('CP', 'cinema'), 'panorama');
  assert.equal(SS.modeFor('CL', 'cinema'), 'cinema');
  assert.equal(SS.modeFor('CP', 'diptych'), 'diptych');
});

test('compact single: portraits fill upright, landscapes fill turned, the rest sit whole', () => {
  const portrait = SS.compactSingle(2 / 3, 800, 1280, 'CP');
  assert.equal(portrait.mode, 'fill');
  same(portrait.rect, { x: 0, y: 0, w: 800, h: 1280 });
  assert.equal(SS.compactSingle(3 / 2, 1280, 800, 'CL').mode, 'fill');
  // 3:4 upright keeps 83 %: still full-bleed.
  assert.equal(SS.compactSingle(3 / 4, 800, 1280, 'CP').mode, 'fill');
  // A 3:2 landscape upright keeps 42 %: shown whole in the zone above the clock.
  const landscape = SS.compactSingle(3 / 2, 800, 1280, 'CP');
  assert.equal(landscape.mode, 'whole');
  same(landscape.zone, { x: 32, y: 40, w: 736, h: 950 });
  near(landscape.rect.w, 736); near(landscape.rect.h, 736 / 1.5);
  near(landscape.rect.y + landscape.rect.h / 2, 40 + 950 / 2);
  // A portrait turned is whole in the 64 / 40 zone.
  const turned = SS.compactSingle(2 / 3, 1280, 800, 'CL');
  assert.equal(turned.mode, 'whole');
  same(turned.zone, { x: 64, y: 40, w: 1152, h: 720 });
  near(turned.rect.h, 720); near(turned.rect.w, 480);
  const pano = SS.compactSingle(3, 800, 1280, 'CP');
  assert.equal(pano.mode, 'sweep');
  assert.equal(pano.rect.h, 1280); assert.equal(pano.rect.w, 3840); assert.equal(pano.travel, 800 - 3840);
  assert.equal(SS.compactSingle(NaN, 800, 1280, 'CP').mode, 'whole');
});

test('vertical filmstrip cards fit the upright screen (was 1271 dp on 800)', () => {
  const card = SS.verticalCard(1.5, 800, 1280);
  near(card.w * 1.12, 704); near(card.h, card.w / 1.5);
  const tall = SS.verticalCard(2 / 3, 800, 1280);
  near(tall.h * 1.12, 640); near(tall.w, tall.h * 2 / 3);
  assert.ok(SS.stackable({ w: 1500, h: 1000 }));
  assert.ok(!SS.stackable({ w: 1000, h: 1500 }));
  assert.ok(!SS.stackable({ w: 3000, h: 1000 }));
});

const portraits = () => Array.from({ length: 8 }, (_, i) => ({ seed: 'portrait-' + i, w: 1000, h: 1500 }));
const landscapes = () => Array.from({ length: 8 }, (_, i) => ({ seed: 'landscape-' + i, w: 1500, h: 1000 }));
const heroes = r => r.nodes().filter(n => n.classList.contains('ss-hero') && n.style.backgroundImage?.startsWith('url('));
const fills = r => r.nodes().filter(n => n.classList.contains('ss-compact-fill') && n.style.display !== 'none' &&
  n.style.backgroundImage?.startsWith('url('));

for (const mode of ['kenBurns', 'push']) {
  test(`${mode}: an upright portrait fills the screen with no backdrop`, async () => {
    const r = createSceneFixture({ mode, W: 800, H: 1280, cls: 'CP', photos: portraits() });
    try {
      assert.equal(await r.cleanup.ready, true);
      const hero = heroes(r).at(-1);
      assert.ok(hero.classList.contains('ss-compact'));
      assert.equal(hero.style.backgroundSize, 'cover');
      assert.equal(hero.style.width, '800px'); assert.equal(hero.style.height, '1280px');
      assert.equal(fills(r).length, 0);
    } finally { r.cleanup(); }
  });

  test(`${mode}: an upright landscape is whole over its own small blurred copy`, async () => {
    const r = createSceneFixture({ mode, W: 800, H: 1280, cls: 'CP', photos: landscapes() });
    try {
      assert.equal(await r.cleanup.ready, true);
      const hero = heroes(r).at(-1);
      assert.equal(hero.style.backgroundSize, 'contain');
      assert.equal(hero.style.left, '32px'); assert.equal(hero.style.width, '736px');
      assert.equal(fills(r).length, 1);
      assert.ok(r.resolutions.some(n => n.edge === 320), 'backdrop uses the 320 px copy');
    } finally { r.cleanup(); }
  });

  test(`${mode}: the Portal+ keeps its shipped contained photo and no compact layer`, async () => {
    const r = createSceneFixture({ mode, photos: landscapes() });
    try {
      assert.equal(await r.cleanup.ready, true);
      assert.ok(heroes(r).every(n => !n.classList.contains('ss-compact')));
      assert.equal(r.nodes().filter(n => n.classList.contains('ss-compact-fill')).length, 0);
      assert.ok(!r.resolutions.some(n => n.edge === 320));
    } finally { r.cleanup(); }
  });
}

test('diptych upright stacks two landscapes in a column', async () => {
  const r = createSceneFixture({ mode: 'diptych', W: 800, H: 1280, cls: 'CP', photos: landscapes(), random: .3 });
  try {
    assert.equal(await r.cleanup.ready, true);
    const frame = r.nodes().find(n => n.classList.contains('ss-frame') && n.classList.contains('on'));
    assert.ok(frame.classList.contains('column'));
    assert.equal(frame.children.filter(n => n.classList.contains('ss-cell')).length, 2);
  } finally { r.cleanup(); }
});

test('diptych upright shows a portrait alone instead of two narrow bands', async () => {
  const r = createSceneFixture({ mode: 'diptych', W: 800, H: 1280, cls: 'CP', photos: portraits(), random: .9 });
  try {
    assert.equal(await r.cleanup.ready, true);
    const frame = r.nodes().find(n => n.classList.contains('ss-frame') && n.classList.contains('on'));
    assert.equal(frame.children.filter(n => n.classList.contains('ss-cell')).length, 1);
  } finally { r.cleanup(); }
});

test('diptych turned and on the Portal+ keeps the side-by-side pair', async () => {
  for (const options of [{ W: 1280, H: 800, cls: 'CL' }, {}]) {
    const r = createSceneFixture({ mode: 'diptych', photos: portraits(), random: .9, ...options });
    try {
      assert.equal(await r.cleanup.ready, true);
      const frame = r.nodes().find(n => n.classList.contains('ss-frame') && n.classList.contains('on'));
      assert.ok(!frame.classList.contains('column'));
      assert.equal(frame.children.filter(n => n.classList.contains('ss-cell')).length, 2);
    } finally { r.cleanup(); }
  }
});

test('hero + sidebar upright puts the hero on top across the width', async () => {
  const mixed = Array.from({ length: 9 }, (_, i) => ({ seed: 'm' + i, w: i % 3 ? 1000 : 1500, h: i % 3 ? 1500 : 1000 }));
  const r = createSceneFixture({ mode: 'heroSidebar', W: 800, H: 1280, cls: 'CP', photos: mixed });
  try {
    assert.equal(await r.cleanup.ready, true);
    const stage = r.nodes().find(n => n.classList.contains('ss-hs'));
    assert.ok(stage.classList.contains('upright'));
    assert.equal(stage.children[0].style.flex, `0 0 ${(800 - 48) / 1.5}px`);
  } finally { r.cleanup(); }
});

test('filmstrip fallback runs a vertical strip upright', async () => {
  const r = createSceneFixture({ mode: 'filmstrip', W: 800, H: 1280, cls: 'CP', photos: landscapes() });
  try {
    assert.equal(await r.cleanup.ready, true);
    const track = r.nodes().find(n => n.classList.contains('ss-track'));
    assert.ok(track.classList.contains('vertical'));
    assert.match(track.style.transform, /^translateY\(/);
    const slide = track.children[0];
    near(parseFloat(slide.style.width) * 1.12, 704);
  } finally { r.cleanup(); }
});
