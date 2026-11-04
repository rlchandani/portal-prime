/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */
import test from 'node:test';
import assert from 'node:assert/strict';
import { createSceneFixture } from './helpers/slideshow_scene_fixture.mjs';

function fixture(count = 3) {
  const events = new Map();
  const r = createSceneFixture({
    mode: 'memories', W: 1600, H: 1000,
    photos: Array.from({ length: count }, (_, i) => ({
      seed: 'viewport-' + i, w: i % 2 ? 600 : 1200, h: i % 2 ? 1000 : 800,
    })),
    configureWindow(window) {
      window.addEventListener = (name, callback) => {
        if (!events.has(name)) events.set(name, new Set());
        events.get(name).add(callback);
      };
      window.removeEventListener = (name, callback) => events.get(name)?.delete(callback);
    },
  });
  const root = r.nodes()[0];
  root.clientWidth = 1600; root.clientHeight = 1000;
  return Object.assign(r, {
    events,
    resize(w, h) {
      root.clientWidth = w; root.clientHeight = h;
      for (const callback of [...events.get('resize') || []]) callback();
    },
    cards: () => r.nodes().filter(n => n.classList.contains('ss-memory-card')),
  });
}

async function collage(r) {
  await r.cleanup.ready;
  await r.advanceTo(27000);
  r.end(r.prints().at(-1));
}

function contained(r, width, height) {
  assert.ok(r.cards().length > 0);
  for (const card of r.cards()) {
    const left = parseFloat(card.style.left), top = parseFloat(card.style.top);
    const w = parseFloat(card.style.width), h = parseFloat(card.style.height);
    assert.ok(left >= 0 && top >= 0 && w > 24 && h > 24);
    assert.ok(left + w <= width, `Card right ${left + w} exceeds viewport ${width}`);
    assert.ok(top + h <= height, `Card bottom ${top + h} exceeds viewport ${height}`);
  }
}

test('retained Memories collage fits a smaller measured viewport without replacing photos', async () => {
  const r = fixture();
  try {
    await collage(r);
    const cards = r.cards(), images = r.prints(), urls = images.map(i => i.style.backgroundImage);
    const requests = r.resolutions.length, cursor = r.cursor.i;
    r.resize(640, 400); await r.advanceTo(27016);
    contained(r, 640, 400);
    assert.deepEqual(r.cards(), cards);
    assert.deepEqual(r.prints(), images);
    assert.deepEqual(images.map(i => i.style.backgroundImage), urls);
    assert.equal(r.resolutions.length, requests);
    assert.equal(r.cursor.i, cursor);
  } finally { r.cleanup(); }
});

test('a new collage uses measured dimensions after an earlier viewport change', async () => {
  const r = fixture();
  try {
    await r.cleanup.ready;
    r.resize(640, 400);
    await collage(r);
    contained(r, 640, 400);
  } finally { r.cleanup(); }
});

test('paused collage defers reflow and uses the latest viewport when resumed', async () => {
  const r = fixture();
  try {
    await collage(r);
    r.cleanup.pause();
    const before = r.cards().map(c => [c.style.left, c.style.top, c.style.width, c.style.height]);
    const requests = r.resolutions.length;
    r.resize(800, 500); r.resize(640, 400);
    await r.advanceTo(50000);
    assert.deepEqual(r.cards().map(c => [c.style.left, c.style.top, c.style.width, c.style.height]), before);
    r.cleanup.resume(); await r.advanceTo(50016);
    contained(r, 640, 400);
    assert.equal(r.resolutions.length, requests);
  } finally { r.cleanup(); }
});

test('initial Memories collage retains its previous full-size geometry', async () => {
  const r = fixture(1);
  try {
    await collage(r); contained(r, 1600, 1000);
    assert.equal(r.cards().length, 1);
    const boxes = r.cards().map(c => [c.style.left, c.style.top, c.style.width, c.style.height]);
    assert.deepEqual(boxes, [['266px', '140px', '1068px', '720px']]);
  } finally { r.cleanup(); }
});

test('single-photo resize keeps its existing image and does not add a collage', async () => {
  const r = fixture();
  try {
    await r.cleanup.ready;
    const image = r.prints()[0], requests = r.resolutions.length;
    r.resize(640, 400); await r.advanceTo(16);
    assert.equal(r.prints()[0], image);
    assert.equal(r.cards().length, 0);
    assert.equal(r.resolutions.length, requests);
  } finally { r.cleanup(); }
});

test('cleanup removes resize work and a queued callback cannot mutate released photos', async () => {
  const r = fixture(); await collage(r);
  r.resize(640, 400);
  const callbacks = [...r.pending.values()].map(t => t.fn);
  r.cleanup();
  const writes = r.writes.length, requests = r.resolutions.length;
  for (const callback of callbacks) { callback(); await r.flush(); }
  r.resize(800, 500); await r.advanceTo(40000);
  assert.equal(r.events.get('resize')?.size || 0, 0);
  assert.equal(r.writes.length, writes);
  assert.equal(r.resolutions.length, requests);
  // The scheduler owns the parent layer; mode cleanup retains its two empty roots.
  assert.equal(r.nodes().length, 3);
  assert.equal(r.prints().length, 0);
  assert.equal(r.cards().length, 0);
  assert.equal(r.pending.size, 0);
});
