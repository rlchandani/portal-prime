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
  const listeners = new Set();
  const r = createSceneFixture({ photoMotion: false, ...options, configureWindow(window) {
    window.addEventListener = (name, fn) => { if (name === 'resize') listeners.add(fn); };
    window.removeEventListener = (name, fn) => { if (name === 'resize') listeners.delete(fn); };
  } });
  const root = r.nodes()[0];
  root.clientWidth = 2160; root.clientHeight = 1440;
  return Object.assign(r, { listeners, resize(w, h) {
    root.clientWidth = w; root.clientHeight = h;
    [...listeners].forEach(fn => fn());
  } });
}

test('Framed Print reflows the same decoded photo into a narrower live viewport and back', async () => {
  const r = fixture({ photos: [{ seed: 'retained', w: 1200, h: 800 }] });
  try {
    assert.equal(await r.cleanup.ready, true);
    const photo = r.incoming(), url = photo.style.backgroundImage;
    assert.equal(photo.style.width, '1296px');
    r.resize(800, 1440); await r.advanceTo(16);
    assert.equal(r.incoming(), photo);
    assert.equal(photo.style.width, '480px');
    assert.equal(photo.style.height, '320px');
    r.resize(2160, 1440); await r.advanceTo(32);
    assert.equal(photo.style.width, '1296px');
    assert.equal(photo.style.height, '864px');
    assert.equal(photo.style.backgroundImage, url);
    assert.equal(r.loads.length, 1, 'Reflow must not decode again');
    assert.equal(r.cursor.i, 1, 'Reflow must not advance the photo deck');
  } finally { r.cleanup(); }
});

test('Framed Print coalesces a resize burst and defers covered-scene writes until resume', async () => {
  const r = fixture({ photos: [{ seed: 'retained', w: 1200, h: 800 }] });
  try {
    await r.cleanup.ready;
    const photo = r.incoming();
    r.cleanup.pause();
    const baseline = r.pending.size;
    for (let w = 900; w >= 800; w--) r.resize(w, 1440);
    assert.equal(r.pending.size, baseline + 1, 'A burst owns only one animation frame');
    await r.advanceTo(1000);
    assert.equal(photo.style.width, '1296px', 'A paused scene keeps its pixels untouched');
    r.resize(700, 1440);
    r.cleanup.resume(); await r.advanceTo(1016);
    assert.equal(photo.style.width, '420px', 'Resume uses the latest viewport');
    assert.equal(r.loads.length, 1);
  } finally { r.cleanup(); }
});

test('Framed Print admits a pending decode at the current size and retires its resize listener', async () => {
  const r = fixture({ deferLoad: true, photos: [{ seed: 'retained', w: 1200, h: 800 }] });
  await r.flush();
  r.resize(800, 1440); await r.advanceTo(16);
  r.finishLoad(); assert.equal(await r.cleanup.ready, true);
  const photo = r.incoming();
  assert.equal(photo.style.width, '480px');
  r.resize(600, 1440);
  r.cleanup();
  const width = photo.style.width, writes = r.writes.length;
  assert.equal(r.listeners.size, 0);
  r.resize(2160, 1440); await r.advanceTo(1000);
  assert.equal(photo.style.width, width);
  assert.equal(r.writes.length, writes);
  assert.equal(r.pending.size, 0, 'Close cancels the pending resize frame');
});

test('Framed Print reflows both admitted layers while a dissolve is in progress', async () => {
  const r = fixture({ photos: [{ seed: 'wide', w: 1200, h: 800 }, { seed: 'tall', w: 800, h: 1200 }] });
  try {
    await r.cleanup.ready; await r.advanceTo(5000);
    const layers = r.prints().filter(photo => photo.style.backgroundImage !== 'none');
    assert.equal(layers.length, 2);
    r.resize(800, 1440); await r.advanceTo(5016);
    assert.deepEqual(layers.map(photo => photo.style.width), ['480px', '480px']);
    assert.deepEqual(layers.map(photo => photo.style.height), ['320px', '720px']);
    assert.equal(r.loads.length, 2);
  } finally { r.cleanup(); }
});
