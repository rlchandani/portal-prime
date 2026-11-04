/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */
import test from 'node:test';
import assert from 'node:assert/strict';
import { createSceneFixture } from './helpers/slideshow_scene_fixture.mjs';

const sources = Array.from({ length: 12 }, (_, i) => ({
  seed: 'layout-' + i, w: [1200, 800, 1000, 2400][i % 4], h: [800, 1200, 1000, 800][i % 4],
}));
const cells = r => r.nodes().filter(n => n.classList.contains('ss-mcell'));
const active = r => r.prints().filter(n => n.style.backgroundImage?.startsWith('url('));
function assertWhole(r) {
  for (const image of active(r)) {
    const source = sources.find(p => p.seed === /layout-\d+/.exec(image.style.backgroundImage)[0]);
    const w = parseFloat(image.style.width), h = parseFloat(image.style.height);
    assert.ok(Math.abs(w / h - source.w / source.h) < .005,
      `Photo frame must follow the complete decoded shape: ${source.seed} ${w}x${h}`);
    assert.equal(image.style.backgroundSize, 'contain');
    assert.equal(image.style.animation, 'none', 'A spotlight may lift the complete frame, never crop its photo');
  }
}

test('Mosaic presents six unique proportional whole photos and reflows replacement shapes', async () => {
  const r = createSceneFixture({ mode: 'mosaic', photoMotion: false, photos: sources });
  try {
    assert.equal(await r.cleanup.ready, true);
    assert.equal(cells(r).length, 6);
    assert.equal(active(r).length, 6);
    assertWhole(r);
    for (const at of [5000, 7500, 10000, 15000]) {
      await r.advanceTo(at);
      assertWhole(r);
      assert.equal(new Set(cells(r).map(c => c.children.at(-1).children[0].style.backgroundImage)).size, 6);
    }
  } finally { r.cleanup(); }
});

test('Framed uses available width rather than a sixty-percent cap, preserving photo shape', async () => {
  const r = createSceneFixture({ photoMotion: false, photos: [sources[0]] });
  try {
    await r.cleanup.ready;
    const photo = r.incoming();
    assert.ok(parseFloat(photo.style.width) > 1500, 'Landscape should use the larger reviewed print area');
    assert.ok(Math.abs(parseFloat(photo.style.width) / parseFloat(photo.style.height) - 1.5) < .005);
    assert.equal(photo.style.backgroundSize, 'contain');
  } finally { r.cleanup(); }
});

test('measured native overlay reservations keep complete Mosaic frames above a wide clock', async () => {
  const r = createSceneFixture({ mode: 'mosaic', photoMotion: false, photos: sources, W: 1200, H: 800,
    configureWindow(window) {
      window.Android = { getPhotoLayoutBounds: () => JSON.stringify({
        width: 2400, height: 1600, occupied: [{ left: 0, top: 1200, right: 2400, bottom: 1600 }],
      }) };
    } });
  try {
    await r.cleanup.ready;
    for (const cell of cells(r)) {
      assert.ok(parseFloat(cell.style.top) + parseFloat(cell.style.height) < 600,
        'Native window pixels must be mapped to CSS pixels before reserving the clock');
    }
  } finally { r.cleanup(); }
});

for (const snapshot of [null, { width: 0, height: 800, occupied: [] },
  { width: 1200, height: 800, occupied: Array(33).fill({ left: 0, top: 0, right: 2, bottom: 2 }) }]) {
  test(`invalid native geometry falls back to bounded whole photos: ${snapshot === null ? 'null' : snapshot.width}`, async () => {
    const r = createSceneFixture({ mode: 'mosaic', photoMotion: false, photos: sources,
      configureWindow(window) { window.Android = { getPhotoLayoutBounds: () => JSON.stringify(snapshot) }; } });
    try { await r.cleanup.ready; assert.equal(cells(r).length, 6); assertWhole(r); }
    finally { r.cleanup(); }
  });
}

test('one-photo and tiny Mosaic libraries stay positive and inside the viewport', async () => {
  for (const [W, H] of [[320, 480], [1280, 800], [3, 2]]) {
    const r = createSceneFixture({ mode: 'mosaic', photoMotion: false, photos: [sources[1]], W, H });
    try {
      await r.cleanup.ready;
      const cell = cells(r)[0];
      const x = parseFloat(cell.style.left), y = parseFloat(cell.style.top);
      const w = parseFloat(cell.style.width), h = parseFloat(cell.style.height);
      assert.ok(w > 0 && h > 0 && x >= 0 && y >= 0 && x + w <= W + .001 && y + h <= H + .001);
      assert.equal(cells(r).length, 1);
    } finally { r.cleanup(); }
  }
});

test('tiny Framed geometry keeps the entire decorated frame inside its viewport', async () => {
  for (const [W, H] of [[16, 8], [3, 2]]) {
    const r = createSceneFixture({ photoMotion: false, photos: [sources[0]], W, H });
    try {
      await r.cleanup.ready;
      const photo = r.incoming(), edge = photo.parent.parent.parent, mat = photo.parent.parent;
      const padding = parseFloat(edge.style.padding ?? r.computed(edge).padding) +
        parseFloat(mat.style.padding ?? r.computed(mat).padding);
      const x = parseFloat(edge.style.left), y = parseFloat(edge.style.top);
      const w = parseFloat(photo.style.width) + padding * 2, h = parseFloat(photo.style.height) + padding * 2;
      assert.ok(x >= 0 && y >= 0 && x + w <= W + .001 && y + h <= H + .001);
    } finally { r.cleanup(); }
  }
});

test('Paper motion lifts complete frames and respects still/face-protected choices', async () => {
  for (const [photoMotion, face, moving] of [[true, false, true], [false, false, false], [true, true, false]]) {
    const r = createSceneFixture({ photoMotion, photos: [{ ...sources[0], ...(face ? { fx: .5, fy: .5 } : {}) }] });
    try {
      await r.cleanup.ready;
      const photo = r.incoming(), edge = photo.parent.parent.parent;
      assert.equal(photo.style.animation, 'none');
      assert.equal(r.computed(edge).animation !== 'none', moving);
      assert.equal(photo.style.backgroundSize, 'contain');
    } finally { r.cleanup(); }
  }
  for (const mode of ['framed', 'mosaic']) {
    const r = createSceneFixture({ mode, W: 3, H: 2, photoMotion: true, photos: [sources[0]] });
    try {
      await r.cleanup.ready;
      const frame = mode === 'mosaic' ? cells(r)[0] : r.incoming().parent.parent.parent;
      assert.ok(parseFloat(frame.style.top) + parseFloat(frame.style['--paper-lift']) >= -.001,
        'Even the lifted frame must stay inside a tiny viewport');
    } finally { r.cleanup(); }
  }
});

test('Mosaic resize coalesces, waits while paused, and reuses admitted photos without decoding', async () => {
  const listeners = new Set();
  const r = createSceneFixture({ mode: 'mosaic', photoMotion: false, photos: sources,
    configureWindow(window) {
      window.addEventListener = (name, fn) => { if (name === 'resize') listeners.add(fn); };
      window.removeEventListener = (name, fn) => { if (name === 'resize') listeners.delete(fn); };
    } });
  try {
    await r.cleanup.ready;
    const root = r.nodes()[0], loaded = r.loads.length, cursor = r.cursor.i;
    const geometry = cells(r).map(c => c.style.width);
    r.cleanup.pause();
    for (const width of [1000, 900, 800]) {
      root.clientWidth = width; root.clientHeight = 1440; listeners.forEach(fn => fn());
    }
    await r.advanceTo(1000);
    assert.deepEqual(cells(r).map(c => c.style.width), geometry);
    r.cleanup.resume(); await r.advanceTo(1016);
    assert.notDeepEqual(cells(r).map(c => c.style.width), geometry);
    assert.equal(r.loads.length, loaded); assert.equal(r.cursor.i, cursor);
    assertWhole(r);
  } finally { r.cleanup(); }
  assert.equal(listeners.size, 0);
});
