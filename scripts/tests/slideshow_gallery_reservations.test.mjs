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
  seed: 'gallery-reserved-' + i,
  w: [1600, 400, 600, 6000][i % 4], h: [1000, 800, 600, 1000][i % 4],
}));
const layouts = [
  ['bottom clock', 2160, 1440, [{ left: 0, top: 1020, right: 2160, bottom: 1440 }]],
  ['portrait clock', 640, 960, [{ left: 0, top: 660, right: 640, bottom: 960 }]],
  ['right panel', 1920, 1080, [{ left: 1360, top: 0, right: 1920, bottom: 1080 }]],
  ['left panel', 1024, 768, [{ left: 0, top: 0, right: 260, bottom: 768 }]],
  ['clock and calendar', 2160, 1440, [
    { left: 0, top: 1100, right: 2160, bottom: 1440 },
    { left: 1680, top: 0, right: 2160, bottom: 1440 },
  ]],
  ['no occupied space', 1024, 768, []],
  ['narrow free strip', 160, 100, [{ left: 12, top: 0, right: 160, bottom: 100 }]],
];
const cells = r => r.nodes().filter(node => node.classList.contains('ss-gcell'));
const box = node => ({ left: parseFloat(node.style.left), top: parseFloat(node.style.top),
  right: parseFloat(node.style.left) + parseFloat(node.style.width),
  bottom: parseFloat(node.style.top) + parseFloat(node.style.height) });
const geometry = r => cells(r).map(box);
const owners = r => cells(r).map(cell => cell.children.map(image => [
  image.style.backgroundImage, image.className, r.state(image).opacity,
]));
function clear(r, occupied, W, H) {
  assert.equal(cells(r).length, 10, 'Retain every initially selected Gallery photo');
  for (const cell of cells(r)) {
    const b = box(cell);
    assert.ok(b.right > b.left && b.bottom > b.top, 'Every tile has positive dimensions');
    assert.ok(b.left >= 0 && b.top >= 0 && b.right <= W + .01 && b.bottom <= H + .01);
    for (const area of occupied) assert.ok(b.right <= area.left || b.left >= area.right ||
      b.bottom <= area.top || b.top >= area.bottom, 'Whole Gallery tiles must clear occupied space');
    for (const image of cell.children.filter(image => image.style.backgroundImage?.startsWith('url('))) {
      assert.equal(image.style.backgroundSize, 'contain, 480px 360px');
      assert.equal(r.computed(image)['background-color'], '#1c1b19');
    }
  }
}
function fixture(W = 2160, H = 1440, occupied = [], options = {}) {
  let current = occupied, draws = 0;
  const listeners = new Set();
  const r = createSceneFixture({ mode: 'galleryWall', photos, W, H, ...options,
    random: { valueOf() { draws++; return .5; } },
    configureWindow(window) {
      window.addEventListener = (type, fn) => { if (type === 'resize') listeners.add(fn); };
      window.removeEventListener = (type, fn) => { if (type === 'resize') listeners.delete(fn); };
      if (options.bridge !== false) window.Android = { getPhotoLayoutBounds: () =>
        options.malformed ? '{bad' : JSON.stringify({ width: W * 2, height: H * 2,
          occupied: current.map(rect => Object.fromEntries(
            Object.entries(rect).map(([key, value]) => [key, value * 2]))) }) };
    },
  });
  return { r, resize(next) { current = next; for (const fn of listeners) fn(); },
    draws: () => draws, listeners };
}

for (const [name, W, H, occupied] of layouts) {
  test(`Gallery keeps full tiles clear of ${name}`, async () => {
    const { r } = fixture(W, H, occupied);
    try {
      assert.equal(await r.cleanup.ready, true); clear(r, occupied, W, H);
      await r.advanceTo(6200); clear(r, occupied, W, H);
    } finally { r.cleanup(); }
  });
}

for (const options of [{ bridge: false }, { malformed: true }]) {
  test(`Gallery reserves the lower fallback with ${options.bridge === false ? 'absent' : 'malformed'} bounds`, async () => {
    const { r } = fixture(2160, 1440, [], options);
    try {
      await r.cleanup.ready;
      clear(r, [{ left: 0, top: 1260, right: 2160, bottom: 1440 }], 2160, 1440);
    } finally { r.cleanup(); }
  });
}

test('Gallery coalesces bounds changes without replacing owners, resolving photos or drawing randomness', async () => {
  const f = fixture(), r = f.r;
  try {
    await r.cleanup.ready;
    const nodes = cells(r), state = owners(r), requests = r.resolutions.length, loads = r.loads.length, draws = f.draws();
    const occupied = [{ left: 0, top: 900, right: 2160, bottom: 1440 }];
    f.resize(occupied); f.resize(occupied); f.resize(occupied);
    assert.equal([...r.pending.values()].filter(task => task.at === 16).length, 1);
    await r.advanceTo(16); clear(r, occupied, 2160, 1440);
    assert.deepEqual(cells(r), nodes); assert.deepEqual(owners(r), state);
    assert.equal(r.resolutions.length, requests); assert.equal(r.loads.length, loads); assert.equal(f.draws(), draws);
    r.cleanup(); assert.equal(f.listeners.size, 0);
    f.resize([]); assert.equal(r.pending.size, 0);
  } finally { r.cleanup(); }
});

test('Gallery holds a pending bounds reflow while paused and applies it after resume', async () => {
  const f = fixture(), r = f.r;
  try {
    await r.cleanup.ready;
    const before = geometry(r), state = owners(r), requests = r.resolutions.length, draws = f.draws();
    r.cleanup.pause();
    const occupied = [{ left: 1500, top: 0, right: 2160, bottom: 1440 }];
    f.resize(occupied); await r.advanceTo(1000); assert.deepEqual(geometry(r), before);
    r.cleanup.resume(); await r.advanceTo(1016); clear(r, occupied, 2160, 1440);
    assert.deepEqual(owners(r), state); assert.equal(r.resolutions.length, requests); assert.equal(f.draws(), draws);
  } finally { r.cleanup(); }
});

test('Gallery uses the latest bounds after deferred initial decode and retires queued resize work', async () => {
  const f = fixture(2160, 1440, [], { deferLoad: true }), r = f.r;
  try {
    await r.flush(); assert.equal(cells(r).length, 0);
    const occupied = [{ left: 0, top: 900, right: 2160, bottom: 1440 }];
    f.resize(occupied); await r.advanceTo(16);
    r.finishLoad(); await r.flush(); assert.equal(await r.cleanup.ready, true);
    clear(r, occupied, 2160, 1440);
    f.resize([]); r.cleanup(); await r.advanceTo(32);
    assert.equal(f.listeners.size, 0); assert.equal(r.pending.size, 0);
  } finally { r.cleanup(); }
});

test('Gallery keeps current photos through resize during a deferred replacement', async () => {
  const f = fixture(), r = f.r;
  try {
    await r.cleanup.ready; r.deferNext(); await r.advanceTo(5000);
    const state = owners(r), requests = r.resolutions.length, loads = r.loads.length, draws = f.draws();
    const occupied = [{ left: 0, top: 950, right: 2160, bottom: 1440 }];
    f.resize(occupied); await r.advanceTo(5016); clear(r, occupied, 2160, 1440);
    assert.deepEqual(owners(r), state); assert.equal(r.resolutions.length, requests);
    assert.equal(r.loads.length, loads); assert.equal(f.draws(), draws);
    r.finishLoad(); await r.flush(); await r.advanceTo(6200); clear(r, occupied, 2160, 1440);
  } finally { r.cleanup(); }
});
