/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */
import test from 'node:test';
import assert from 'node:assert/strict';
import { createSceneFixture } from './helpers/slideshow_scene_fixture.mjs';

const photos = Array.from({ length: 16 }, (_, i) => ({
  seed: 'reserved-' + i, w: [1600, 400, 600, 6000][i % 4], h: [1000, 800, 600, 1000][i % 4],
}));
const layouts = [
  ['bottom clock', 2160, 1440, [{ left: 0, top: 1120, right: 2160, bottom: 1440 }]],
  ['portrait clock', 640, 960, [{ left: 0, top: 740, right: 640, bottom: 960 }]],
  ['right panel', 1920, 1080, [{ left: 1440, top: 0, right: 1920, bottom: 1080 }]],
  ['left panel', 1024, 768, [{ left: 0, top: 0, right: 240, bottom: 768 }]],
  ['clock and calendar', 2160, 1440, [
    { left: 0, top: 1150, right: 2160, bottom: 1440 },
    { left: 1700, top: 0, right: 2160, bottom: 1440 },
  ]],
  ['no occupied space', 1024, 768, []],
];
function bounds(card) {
  const w = parseFloat(card.style.width), h = parseFloat(card.style.height);
  const angle = Math.abs(Number(/rotate\(([-\d.]+)deg\)/.exec(card.style.transform)[1])) * Math.PI / 180;
  const bw = w * Math.cos(angle) + h * Math.sin(angle);
  const bh = w * Math.sin(angle) + h * Math.cos(angle);
  const cx = parseFloat(card.style.left) + w / 2, cy = parseFloat(card.style.top) + h / 2;
  return { left: cx - bw / 2, top: cy - bh / 2, right: cx + bw / 2, bottom: cy + bh / 2 };
}
const live = r => r.prints().filter(image => image.style.backgroundImage?.startsWith('url(') &&
  !r.slot(image).classList.contains('retired'));
function clear(r, occupied, W, H) {
  assert.equal(live(r).length, 6);
  for (const image of live(r)) {
    const box = bounds(r.slot(image));
    assert.ok(box.left >= 0 && box.top >= 0 && box.right <= W && box.bottom <= H);
    assert.equal(image.style.backgroundSize, 'contain');
    for (const area of occupied) assert.ok(box.right <= area.left || box.left >= area.right ||
      box.bottom <= area.top || box.top >= area.bottom, 'Rotated paper must not overlap reserved space');
  }
}
function fixture(W, H, occupied = [], { bridge = true } = {}) {
  let current = occupied, draws = 0;
  const listeners = new Set();
  const r = createSceneFixture({ mode: 'polaroid', photos, W, H,
    random: { valueOf() { draws++; return .99; } },
    configureWindow(window) {
      window.addEventListener = (type, fn) => { if (type === 'resize') listeners.add(fn); };
      window.removeEventListener = (type, fn) => { if (type === 'resize') listeners.delete(fn); };
      if (bridge) window.Android = { getPhotoLayoutBounds: () =>
        JSON.stringify({ width: W * 2, height: H * 2, occupied: current.map(rect =>
          Object.fromEntries(Object.entries(rect).map(([key, value]) => [key, value * 2]))) }) };
    },
  });
  return { r, resize(next) { current = next; for (const fn of listeners) fn(); },
    draws: () => draws, listeners };
}

for (const [name, W, H, occupied] of layouts) {
  test(`Polaroid keeps whole rotated prints clear of ${name}`, async () => {
    const { r } = fixture(W, H, occupied);
    try {
      assert.equal(await r.cleanup.ready, true);
      clear(r, occupied, W, H);
      await r.advanceTo(6000);
      clear(r, occupied, W, H);
    } finally { r.cleanup(); }
  });
}

test('Polaroid reserves the existing lower fallback before native bounds arrive', async () => {
  const W = 2160, H = 1440, { r } = fixture(W, H, [], { bridge: false });
  try {
    await r.cleanup.ready;
    clear(r, [{ left: 0, top: H - Math.min(H * .22, 180), right: W, bottom: H }], W, H);
  } finally { r.cleanup(); }
});

test('Polaroid reflows retained prints once per resize frame without new images or random scatter', async () => {
  const W = 2160, H = 1440, f = fixture(W, H), r = f.r;
  try {
    await r.cleanup.ready;
    const state = live(r).map(image => [image.style.backgroundImage, r.slot(image).style.transform]);
    const draws = f.draws(), requests = r.resolutions.length;
    const occupied = [{ left: 0, top: 1000, right: W, bottom: H }];
    f.resize(occupied); f.resize(occupied); f.resize(occupied);
    assert.equal([...r.pending.values()].filter(task => task.at === 16).length, 1);
    await r.advanceTo(16);
    clear(r, occupied, W, H);
    assert.deepEqual(live(r).map(image => [image.style.backgroundImage, r.slot(image).style.transform]), state);
    assert.equal(f.draws(), draws); assert.equal(r.resolutions.length, requests);
    r.cleanup();
    assert.equal(f.listeners.size, 0);
    f.resize([]);
    assert.equal(r.pending.size, 0);
  } finally { r.cleanup(); }
});

test('Polaroid postpones a bounds reflow while paused and applies it on resume', async () => {
  const W = 2160, H = 1440, f = fixture(W, H), r = f.r;
  try {
    await r.cleanup.ready;
    const before = live(r).map(image => bounds(r.slot(image)));
    const requests = r.resolutions.length, draws = f.draws();
    r.cleanup.pause();
    const occupied = [{ left: 1500, top: 0, right: W, bottom: H }];
    f.resize(occupied); await r.advanceTo(1000);
    assert.deepEqual(live(r).map(image => bounds(r.slot(image))), before);
    r.cleanup.resume(); await r.advanceTo(1016);
    clear(r, occupied, W, H);
    assert.equal(f.draws(), draws); assert.equal(r.resolutions.length, requests);
  } finally { r.cleanup(); }
});
