/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */
import test from 'node:test';
import assert from 'node:assert/strict';
import { readFileSync } from 'node:fs';
import vm from 'node:vm';
import { createSceneFixture } from './helpers/slideshow_scene_fixture.mjs';

const loader = readFileSync(new URL('../../app/src/main/assets/screensaver/photos.js', import.meta.url), 'utf8');
function fixture(options = {}) {
  const photos = [], reads = [];
  let metadata = '0.2,0.5,0.8';
  const r = createSceneFixture({ mode: 'panorama', photoMs: 5000, photos, ...options,
    configureWindow(window, context) {
      window.Android = {
        getPhotos: () => JSON.stringify([{ url: 'fixture:group', w: 2400, h: 800, fx: .2, fy: .5, pw: .8 }]),
        getPhotoFocus: id => { reads.push(id); return metadata; },
      };
      vm.runInContext(loader, context);
      photos.push(...window.PHOTOS);
    } });
  return { ...r, photos, reads, update: value => { metadata = value; } };
}

test('new face regions affect the next presentation without reframing the current photo', async () => {
  const r = fixture();
  try {
    await r.cleanup.ready;
    const first = r.incoming(), initial = { ...first.style };
    r.update('0.2,0.5,0.8,0.15,0.2,0.75,0.7');
    await r.advanceTo(4999);
    assert.deepEqual({ ...first.style }, initial);
    assert.equal(r.photos[0].fb, undefined);
    await r.advanceTo(5000);
    assert.deepEqual(Array.from(r.photos[0].fb || []), [.15, .2, .75, .7]);
    assert.ok(Math.abs(parseFloat(r.incoming().style.width) - 2160 / .72) < .00001);
    assert.notEqual(r.incoming(), first);
    assert.equal(r.reads.length, 2, 'One cache read per admitted photo, never per animation frame');
  } finally { r.cleanup(); }
});

test('a result arriving during decode is consumed before geometry publication', async () => {
  const r = fixture({ deferLoad: true });
  try {
    await r.flush();
    r.update('0.2,0.5,0.8,0.15,0.2,0.75,0.7');
    r.finishLoad(); await r.cleanup.ready;
    assert.deepEqual(Array.from(r.photos[0].fb || []), [.15, .2, .75, .7]);
    assert.equal(r.reads.length, 1);
  } finally { r.cleanup(); }
});

test('paused decode completion waits for resume before reading cached face metadata', async () => {
  const r = fixture({ deferLoad: true });
  try {
    await r.flush(); r.cleanup.pause();
    r.update('0.2,0.5,0.8,0.15,0.2,0.75,0.7');
    r.finishLoad(); await r.flush();
    assert.equal(r.reads.length, 0);
    assert.equal(r.photos[0].fb, undefined);
    r.cleanup.resume(); await r.cleanup.ready;
    assert.equal(r.reads.length, 1);
    assert.deepEqual(Array.from(r.photos[0].fb || []), [.15, .2, .75, .7]);
  } finally { r.cleanup(); }
});

test('retired preparations never consume a late cached result', async () => {
  const r = fixture({ deferLoad: true });
  await r.flush(); r.cleanup();
  r.update('0.2,0.5,0.8,0.15,0.2,0.75,0.7');
  r.finishLoad(); await r.flush();
  assert.equal(r.reads.length, 0);
  assert.equal(r.photos[0].fb, undefined);
});

test('malformed metadata never erases the previous trustworthy face fields', async () => {
  for (const invalid of ['', 'NaN,0.5,0.8', ' ,0.5,0.8', '1.2,0.5,0.8',
    '0.2,0.5,0.8,0.8,0.2,0.1,0.7', 'x'.repeat(257)]) {
    const r = fixture();
    try {
      await r.cleanup.ready;
      r.update(invalid); await r.advanceTo(5000);
      assert.equal(r.photos[0].fx, .2);
      assert.equal(r.photos[0].fy, .5);
      assert.equal(r.photos[0].pw, .8);
      assert.equal(r.photos[0].fb, undefined);
    } finally { r.cleanup(); }
  }
});
