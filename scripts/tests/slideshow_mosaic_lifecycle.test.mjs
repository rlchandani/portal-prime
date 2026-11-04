/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */
import test from 'node:test';
import assert from 'node:assert/strict';
import { createSceneFixture } from './helpers/slideshow_scene_fixture.mjs';

const photographs = count => Array.from({ length: count }, (_, i) => ({
  seed: 'mosaic-' + i, w: i % 3 === 0 ? 20000 : i % 3 === 1 ? 400 : 1500,
  h: i % 3 === 1 ? 16000 : 1000,
}));
const fixture = options => createSceneFixture({ mode: 'mosaic', photos: photographs(16), ...options });
const images = r => r.prints().filter(image => image.style.backgroundImage?.startsWith('url('));
const cells = r => r.nodes().filter(node => node.classList.contains('ss-mcell'));
const current = r => cells(r).map(cell => cell.children.at(-1).children[0]);
const moving = r => images(r).filter(image => image.style.animation !== 'none' &&
  image.style.animationPlayState === 'running');
function released(image) {
  assert.equal(image.style.backgroundImage, 'none');
  assert.equal(image.style.animation, 'none');
  assert.equal(image.style.willChange, 'auto');
}

test('Mosaic preserves six proportional tiles, each owning two fixed slots with an opaque first photo', async () => {
  const r = fixture();
  try {
    assert.equal(await r.cleanup.ready, true);
    assert.equal(cells(r).length, 6);
    assert.equal(r.prints().length, 12);
    assert.equal(images(r).length, 6);
    assert.equal(new Set(current(r).map(image => image.style.backgroundImage)).size, 6);
    assert.ok(cells(r).every(cell => cell.children.length === 2));
    assert.ok(current(r).every(image => r.slot(image).opacity() === 1));
    assert.equal(r.resolutions.length, 6);
  } finally { r.cleanup(); }
});

test('Mosaic initial decode admits at most two photos at once rather than the entire wall', async () => {
  const r = fixture({ deferLoad: true });
  try {
    await r.flush();
    assert.equal(r.loads.length, 2);
    assert.equal(r.resolutions.length, 2);
    r.finishLoad(); await r.flush(); await r.cleanup.ready;
    assert.equal(images(r).length, 6);
  } finally { r.cleanup(); }
});

test('Mosaic photo bounds never exceed two tile widths/heights, even for extreme aspect mismatch', async () => {
  const r = fixture();
  try {
    await r.cleanup.ready;
    for (const cell of cells(r)) {
      const W = parseFloat(cell.style.width), H = parseFloat(cell.style.height);
      const image = cell.children.at(-1).children[0];
      const width = parseFloat(image.style.width), height = parseFloat(image.style.height);
      assert.ok(width > 0 && width <= W * 2 && height > 0 && height <= H * 2);
      assert.equal(image.style.backgroundSize, 'contain');
      assert.equal(image.style.backgroundRepeat, 'no-repeat');
      if (image.classList.contains('panv')) assert.ok(width <= W);
      if (image.classList.contains('panh')) assert.ok(height <= H);
    }
  } finally { r.cleanup(); }
});

test('Mosaic still photos fit inside their tiles instead of freezing a cropped pan', async () => {
  for (const source of [{ seed: 'portrait', w: 800, h: 1200 },
    { seed: 'panorama', w: 2400, h: 800 }]) {
    for (const options of [
      { photoMotion: false, photos: [source] },
      { photoMotion: true, photos: [{ ...source, fx: .1, fy: .9 }] },
    ]) {
      const r = fixture(options);
      try {
        await r.cleanup.ready;
        for (const cell of cells(r)) {
          const image = cell.children.at(-1).children[0];
          const W = parseFloat(cell.style.width), H = parseFloat(cell.style.height);
          const w = parseFloat(image.style.width), h = parseFloat(image.style.height);
          const x = parseFloat(image.style.left), y = parseFloat(image.style.top);
          assert.ok(x >= 0 && y >= 0 && x + w <= W && y + h <= H,
            'A still photo must remain fully inside its tile');
          assert.equal(image.style.backgroundSize, 'contain');
          assert.equal(image.style.transform, 'none');
          assert.equal(image.style.animation, 'none');
          assert.equal(image.style.willChange, 'auto');
        }
      } finally { r.cleanup(); }
    }
  }
});

test('the spotlight guides one complete frame and visits every tile between replacements', async () => {
  const r = fixture({ photoMs: 600000 });
  try {
    await r.cleanup.ready;
    const geometry = cells(r).map(cell => JSON.stringify(cell.style));
    const visited = new Set();
    for (let beat = 0; beat < 6; beat++) {
      if (beat) await r.advanceTo(beat * 3500);
      const selected = cells(r).filter(cell => cell.classList.contains('spot'));
      assert.equal(selected.length, 1); visited.add(selected[0]);
      assert.ok(moving(r).length <= 2);
      assert.ok(images(r).filter(image => image.style.willChange === 'transform').length <= 2);
      assert.deepEqual(cells(r).map(cell => JSON.stringify(cell.style)), geometry);
    }
    assert.equal(visited.size, 6);
  } finally { r.cleanup(); }
});

test('an in-place replacement holds outgoing pixels, then clears its URL and promotion', async () => {
  const r = fixture();
  try {
    await r.cleanup.ready; const before = new Set(images(r));
    await r.advanceTo(5000);
    const replacement = current(r).find(image => !before.has(image));
    assert.ok(replacement);
    const cell = r.slot(replacement).parent, outgoing = cell.children[0], old = outgoing.children[0];
    assert.equal(outgoing.opacity(), 1); assert.equal(r.slot(replacement).opacity(), 0);
    await r.advanceTo(5500);
    assert.equal(outgoing.opacity(), 1);
    assert.ok(r.slot(replacement).opacity() > 0 && r.slot(replacement).opacity() < 1);
    await r.advanceTo(6100); r.end(replacement); released(old);
    assert.equal(images(r).length, 6);
    assert.equal(r.prints().length, 12);
  } finally { r.cleanup(); }
});

test('full-library swaps exchange two photos atomically while retaining all unique current tile photos', async () => {
  const r = fixture({ photos: photographs(6), changingUrls: true });
  try {
    await r.cleanup.ready;
    const before = new Map(cells(r).map(cell => [cell, cell.children.at(-1).children[0].style.backgroundImage]));
    await r.advanceTo(5000);
    const changed = cells(r).filter(cell => cell.children.at(-1).children[0].style.backgroundImage !== before.get(cell));
    assert.equal(changed.length, 2);
    const seed = url => /mosaic-\d+/.exec(url)[0];
    assert.equal(seed(changed[0].children.at(-1).children[0].style.backgroundImage), seed(before.get(changed[1])));
    assert.equal(seed(changed[1].children.at(-1).children[0].style.backgroundImage), seed(before.get(changed[0])));
    assert.equal(new Set(current(r).map(image => seed(image.style.backgroundImage))).size, 6);
    await r.advanceTo(6200);
    assert.equal(images(r).length, 6);
    assert.equal(r.prints().length, 12);
  } finally { r.cleanup(); }
});

test('Mosaic pause retains live fades and photo motion without timer or derivative work', async () => {
  const r = fixture();
  try {
    await r.cleanup.ready; await r.advanceTo(5500);
    const before = images(r).map(image => [image.style.backgroundImage, r.slot(image).opacity()]);
    const requests = r.resolutions.length;
    r.cleanup.pause(); await r.advanceTo(50000);
    assert.deepEqual(images(r).map(image => [image.style.backgroundImage, r.slot(image).opacity()]), before);
    assert.equal(r.resolutions.length, requests);
    assert.equal(r.pending.size, 0);
    assert.ok(images(r).every(image => r.state(image).playState === 'paused'));
    r.cleanup.resume(); await r.advanceTo(50600);
    current(r).forEach(image => r.end(image));
    assert.equal(images(r).length, 6);
  } finally { r.cleanup(); }
});

test('rapid Mosaic exchanges keep six cells/twelve slots and at most two simultaneously moving photos', async () => {
  const r = fixture({ photoMs: 500, ambientMs: 350 });
  try {
    await r.cleanup.ready;
    for (let beat = 1; beat <= 40; beat++) {
      await r.advanceTo(beat * 500);
      assert.equal(cells(r).length, 6);
      assert.equal(r.prints().length, 12);
      assert.ok(moving(r).length <= 2);
      assert.ok(images(r).length <= 8);
    }
  } finally { r.cleanup(); }
});

test('Mosaic terminal cleanup clears every owned URL and rejects stale fade/timer callbacks', async () => {
  const r = fixture(); await r.cleanup.ready; await r.advanceTo(5500);
  const owned = images(r), callbacks = [...r.pending.values()].map(task => task.fn);
  const events = current(r).map(image => [r.slot(image), r.slot(image).ontransitionend]);
  r.cleanup.pause(); r.cleanup(); r.cleanup();
  const writes = r.writes.length, requests = r.resolutions.length;
  for (const [target, event] of events) event?.({ target, propertyName: 'opacity' });
  for (const callback of callbacks) { callback(); await r.flush(); }
  r.cleanup.resume(); await r.advanceTo(60000);
  owned.forEach(released);
  assert.equal(r.writes.length, writes);
  assert.equal(r.resolutions.length, requests);
  assert.equal(images(r).length, 0);
  assert.equal(r.pending.size, 0);
});

test('closing initial Mosaic preparation rejects late work at every decode-publication checkpoint', async () => {
  for (let checkpoint = 0; checkpoint <= 24; checkpoint++) {
    const r = fixture({ deferLoad: true });
    await r.flush(); r.finishLoad();
    for (let turn = 0; turn < checkpoint; turn++) await Promise.resolve();
    const owned = r.prints();
    r.cleanup();
    const writes = r.writes.length, requests = r.resolutions.length;
    await r.flush(); await r.advanceTo(60000);
    assert.equal(await r.cleanup.ready, false);
    assert.equal(r.writes.length, writes);
    assert.equal(r.resolutions.length, requests);
    assert.equal(r.pending.size, 0);
    assert.equal(cells(r).length, 0);
    owned.forEach(released);
  }
});

for (const initial of [true, false]) {
  test(`${initial ? 'initial' : 'replacement'} Mosaic preparation rejects resume/re-pause publications`, async () => {
    for (let checkpoint = 0; checkpoint <= 24; checkpoint++) {
      const r = fixture({ deferLoad: initial });
      try {
        if (initial) await r.flush();
        else { await r.cleanup.ready; r.deferNext(); await r.advanceTo(5000); }
        r.cleanup.pause(); r.finishLoad(); await r.flush(); r.cleanup.resume();
        for (let turn = 0; turn < checkpoint; turn++) await Promise.resolve();
        r.cleanup.pause();
        const writes = r.writes.length, requests = r.resolutions.length;
        await r.flush();
        assert.equal(r.writes.length, writes);
        assert.equal(r.resolutions.length, requests);
        r.cleanup.resume(); await r.cleanup.ready; await r.flush();
        assert.equal(current(r).length, 6);
      } finally { r.cleanup(); }
    }
  });
}

test('small Mosaic libraries have one tile per unique photo with no fabricated duplicates', async () => {
  for (const count of [1, 2, 5]) {
    const r = fixture({ photos: photographs(count) });
    try {
      await r.cleanup.ready;
      assert.equal(cells(r).length, count);
      assert.equal(r.prints().length, count * 2);
      assert.equal(new Set(current(r).map(image => image.style.backgroundImage)).size, count);
    } finally { r.cleanup(); }
  }
});
