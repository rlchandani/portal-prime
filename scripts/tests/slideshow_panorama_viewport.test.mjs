/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */
import test from 'node:test';
import assert from 'node:assert/strict';
import { createSceneFixture } from './helpers/slideshow_scene_fixture.mjs';

function fixture(options = {}) {
  const events = new Map();
  const r = createSceneFixture({
    mode: 'panorama', W: 1600, H: 1000, photoMotion: false,
    photos: [{ seed: 'wide', w: 3000, h: 1000 }], ...options,
    configureWindow(window) {
      window.addEventListener = (name, fn) => {
        if (!events.has(name)) events.set(name, new Set());
        events.get(name).add(fn);
      };
      window.removeEventListener = (name, fn) => events.get(name)?.delete(fn);
    },
  });
  const root = r.nodes()[0];
  root.clientWidth = 1600; root.clientHeight = 1000;
  return Object.assign(r, { events, resize(w, h) {
    root.clientWidth = w; root.clientHeight = h;
    for (const fn of [...events.get('resize') || []]) fn();
  }, photos: () => r.prints().filter(n => n.style.backgroundImage?.startsWith('url(')) });
}

function geometry(image) {
  return ['width', 'height', 'top', '--shift', 'transform'].map(k => image.style[k]);
}

function fits(image, W, H) {
  const width = parseFloat(image.style.width), height = parseFloat(image.style.height),
    top = parseFloat(image.style.top), travel = parseFloat(image.style['--shift']) * width / 100;
  assert.ok(width >= W && width <= W * 2, `Strip width ${width} exceeds two viewports ${W * 2}`);
  assert.ok(height > 0 && height <= H, `Photo height ${height} exceeds viewport ${H}`);
  assert.ok(top >= 0 && top + height <= H);
  assert.ok(Math.abs(top - (H - height) / 2) < .00001);
  assert.ok(travel <= .00001 && travel >= -W - .00001);
}

for (const moving of [false, true]) {
  test(`retained ${moving ? 'moving' : 'still'} Panorama uses current bounds without replacing its photo`, async () => {
    const r = fixture({ photoMotion: moving });
    try {
      await r.cleanup.ready;
      const image = r.photos()[0], url = image.style.backgroundImage,
        requests = r.resolutions.length, cursor = r.cursor.i, animation = image.style.animation;
      r.resize(640, 400); await r.advanceTo(16);
      fits(image, 640, 400);
      assert.equal(r.photos()[0], image);
      assert.equal(image.style.backgroundImage, url);
      assert.equal(r.resolutions.length, requests);
      assert.equal(r.cursor.i, cursor);
      assert.equal(image.style.animation, animation);
      assert.equal(image.style.backgroundSize, 'contain');
    } finally { r.cleanup(); }
  });
}

test('pending Panorama decode publishes using the latest viewport', async () => {
  const r = fixture({ deferLoad: true });
  try {
    await r.flush();
    r.resize(640, 400); await r.advanceTo(16);
    r.finishLoad(); await r.cleanup.ready;
    fits(r.photos()[0], 640, 400);
    assert.equal(r.resolutions.length, 1);
  } finally { r.cleanup(); }
});

test('paused Panorama defers a resize burst and reflows the latest size on resume', async () => {
  const r = fixture();
  try {
    await r.cleanup.ready;
    const image = r.photos()[0], before = geometry(image), requests = r.resolutions.length;
    r.cleanup.pause(); r.resize(800, 500); r.resize(640, 400);
    await r.advanceTo(50000);
    assert.deepEqual(geometry(image), before);
    r.cleanup.resume(); await r.advanceTo(50016);
    fits(image, 640, 400);
    assert.equal(r.photos()[0], image);
    assert.equal(r.resolutions.length, requests);
  } finally { r.cleanup(); }
});

test('initial Panorama keeps its original literal geometry and motion duration', async () => {
  const r = fixture({ photoMotion: true });
  try {
    await r.cleanup.ready;
    const image = r.photos()[0];
    assert.deepEqual(geometry(image), ['3000px', '1000px', '0px', '-46.666666666666664%', 'none']);
    assert.equal(image.style.animation, 'dolly 5000ms linear forwards');
    assert.equal(image.style.willChange, 'transform');
    fits(image, 1600, 1000);
  } finally { r.cleanup(); }
});

test('cached Panorama focal point remains still within its original viewport', async () => {
  const r = fixture({ photoMotion: true, photos: [{ seed: 'face', w: 10000, h: 1000, fx: .8, fy: .4 }] });
  try {
    await r.cleanup.ready;
    const image = r.photos()[0], width = parseFloat(image.style.width),
      shift = Number(/translateX\(([^)]+)%\)/.exec(image.style.transform)[1]) * width / 100;
    assert.ok(width * .8 + shift >= 0 && width * .8 + shift <= 1600);
    assert.equal(image.style.animation, 'none');
    assert.equal(image.style.willChange, 'auto');
  } finally { r.cleanup(); }
});

test('closed Panorama removes resize work and stale callbacks cannot reflow released strips', async () => {
  const r = fixture(); await r.cleanup.ready;
  r.resize(640, 400);
  const callbacks = [...r.pending.values()].map(t => t.fn), images = r.prints();
  r.cleanup();
  const after = images.map(geometry), writes = r.writes.length, requests = r.resolutions.length;
  for (const fn of callbacks) { fn(); await r.flush(); }
  r.resize(800, 500); await r.advanceTo(60000);
  assert.deepEqual(images.map(geometry), after);
  assert.equal(r.events.get('resize')?.size || 0, 0);
  assert.equal(r.writes.length, writes);
  assert.equal(r.resolutions.length, requests);
  assert.equal(r.photos().length, 0);
  assert.equal(r.pending.size, 0);
});
