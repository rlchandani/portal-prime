/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */
import test from 'node:test';
import assert from 'node:assert/strict';
import { createSceneFixture } from './helpers/slideshow_scene_fixture.mjs';

const photographs = () => Array.from({ length: 16 }, (_, id) => ({
  seed: 'wall-' + id, w: id % 3 === 0 ? 900 : id % 3 === 1 ? 1800 : 1200,
  h: id % 3 === 0 ? 1600 : id % 3 === 1 ? 1000 : 1200,
  ...(id % 2 === 0 ? { fx: .2, fy: .35 } : {}),
}));
const fixture = options => createSceneFixture({ mode: 'galleryWall', photos: photographs(), ...options });
const cells = r => r.nodes().filter(n => n.classList.contains('ss-gcell'));
const photos = r => r.prints().filter(n => n.style.backgroundImage?.startsWith('url('));
const fading = r => cells(r).find(c => c.children.filter(n => n.classList.contains('on')).length === 2);
const current = cell => cell.children.at(-1);
function released(r, image) {
  assert.equal(image.style.backgroundImage, 'none');
  assert.equal(r.state(image).animation, 'none');
  assert.equal(r.state(image).promotion, 'auto');
  assert.equal(r.state(image).opacity, 0);
}
function inside(r) {
  for (const cell of cells(r)) {
    const x = parseFloat(cell.style.left), y = parseFloat(cell.style.top);
    const width = parseFloat(cell.style.width), height = parseFloat(cell.style.height);
    assert.ok(x >= 15 && y >= 15 && x + width <= 2145 && y + height <= 1425,
      `The entire photo tile must fit inside the viewport: ${x},${y},${width},${height}`);
  }
}

test('Gallery Wall shows static whole photos with every tile inside the viewport', async () => {
  const r = fixture();
  try {
    assert.equal(await r.cleanup.ready, true);
    assert.ok(cells(r).length >= 2 && cells(r).length <= 10);
    inside(r);
    assert.equal(photos(r).length, cells(r).length);
    for (const image of photos(r)) {
      assert.equal(r.state(image).animation, 'none', 'A wall tile must not animate continuously');
      assert.equal(r.state(image).promotion, 'auto');
      assert.equal(r.computed(image)['background-size'], 'contain, 480px 360px');
      assert.match(image.style.backgroundImage, /^url\("[^"]+"\), url\("photo-doodles-dark\.svg"\)$/);
      assert.equal(r.computed(image)['background-position'], 'center, left top');
      assert.equal(r.computed(image)['background-repeat'], 'no-repeat, repeat');
      assert.equal(r.computed(image)['background-color'], '#1c1b19',
        'The incoming matte must cover outgoing-photo margins before retirement');
      assert.equal(r.state(image).opacity, 1);
    }
    assert.ok(r.resolutions.every(n => n.edge === 1200));
    assert.equal(new Set(photos(r).map(n => n.style.backgroundImage)).size, cells(r).length);
  } finally { r.cleanup(); }
});

test('uniform portrait and landscape collections keep all ten selected photos within centered rows', async () => {
  for (const [w, h] of [[900, 1600], [1800, 1000]]) {
    const r = fixture({ photos: photographs().map(p => ({ ...p, w, h })) });
    try {
      await r.cleanup.ready;
      assert.equal(cells(r).length, 10, 'The row limit must not discard prepared gallery photos');
      inside(r);
      const rows = new Map();
      for (const cell of cells(r)) {
        const top = cell.style.top;
        rows.set(top, [...(rows.get(top) || []), cell]);
      }
      for (const row of rows.values()) {
        const left = Math.min(...row.map(c => parseFloat(c.style.left)));
        const right = Math.max(...row.map(c => parseFloat(c.style.left) + parseFloat(c.style.width)));
        assert.ok(Math.abs(left - (2160 - right)) < .01, 'A shorter final row remains centered');
      }
    } finally { r.cleanup(); }
  }
});

test('only an incoming tile fades, and outgoing resources retire after incoming opacity one', async () => {
  const r = fixture();
  try {
    await r.cleanup.ready;
    for (let beat = 1; beat <= 6; beat++) {
      await r.advanceTo(beat * 5000);
      const cell = fading(r); assert.ok(cell);
      const incoming = current(cell), outgoing = cell.children.find(n => n !== incoming);
      assert.equal(r.state(outgoing).opacity, 1);
      assert.equal(r.state(incoming).opacity, 0);
      await r.advanceTo(beat * 5000 + 700);
      assert.ok(r.state(incoming).opacity > 0 && r.state(incoming).opacity < 1);
      assert.equal(r.state(outgoing).opacity, 1);
      await r.advanceTo(beat * 5000 + 1100); r.end(incoming);
      released(r, outgoing);
      assert.equal(r.state(incoming).opacity, 1);
      assert.equal(photos(r).length, cells(r).length);
      inside(r);
    }
  } finally { r.cleanup(); }
});

test('Gallery Wall retains only two image slots per cell through rapid replacements', async () => {
  const r = fixture({ photoMs: 500 });
  try {
    await r.cleanup.ready;
    const count = r.nodes().length;
    for (let beat = 1; beat <= 20; beat++) {
      await r.advanceTo(beat * 500);
      assert.equal(r.nodes().length, count, 'Rapid replacement cannot accumulate DOM/image owners');
      assert.ok(cells(r).every(cell => cell.children.length === 2));
      assert.ok(photos(r).length <= cells(r).length + 1);
      assert.ok(cells(r).every(cell => cell.children.some(n => r.state(n).opacity === 1)));
    }
  } finally { r.cleanup(); }
});

test('pausing the wall freezes a live tile fade without relying on getAnimations', async () => {
  const r = fixture();
  try {
    await r.cleanup.ready; await r.advanceTo(5000); await r.advanceTo(5350);
    const cell = fading(r), incoming = current(cell), outgoing = cell.children.find(n => n !== incoming);
    const opacity = r.state(incoming).opacity, before = photos(r).map(n => n.style.backgroundImage);
    r.cleanup.pause(); await r.advanceTo(25000);
    assert.equal(r.state(incoming).opacity, opacity);
    assert.deepEqual(photos(r).map(n => n.style.backgroundImage), before);
    assert.equal(r.pending.size, 0);
    r.cleanup.resume(); await r.advanceTo(25750); r.end(incoming);
    released(r, outgoing);
    assert.equal(r.state(incoming).opacity, 1);
  } finally { r.cleanup(); }
});

test('terminal cleanup releases every wall photo and rejects stale timer/event work', async () => {
  const r = fixture(); await r.cleanup.ready; await r.advanceTo(5000);
  const callbacks = [...r.pending.values()].map(t => t.fn);
  const cell = fading(r), incoming = current(cell), event = incoming.ontransitionend;
  r.cleanup.pause(); r.cleanup(); r.cleanup();
  const writes = r.writes.length;
  event?.({ target: incoming, propertyName: 'opacity' });
  for (const fn of callbacks) { fn(); await r.flush(); }
  r.cleanup.resume(); await r.advanceTo(60000);
  assert.equal(r.writes.length, writes);
  r.prints().forEach(image => released(r, image));
  assert.equal(r.pending.size, 0);
});

test('cleanup at each initial decode-admission boundary prevents late wall publication', async () => {
  for (let checkpoint = 0; checkpoint <= 24; checkpoint++) {
    const r = fixture({ deferLoad: true });
    await r.flush(); r.finishLoad();
    for (let turn = 0; turn < checkpoint; turn++) await Promise.resolve();
    r.cleanup(); const writes = r.writes.length;
    await r.cleanup.ready; await r.flush();
    assert.equal(r.writes.length, writes, `Cleanup at microtask ${checkpoint} rejects publication`);
    assert.equal(photos(r).length, 0);
    assert.equal(r.pending.size, 0);
  }
});

test('a paused replacement decode cannot publish after terminal cleanup', async () => {
  const r = fixture(); await r.cleanup.ready;
  const before = photos(r).map(n => n.style.backgroundImage);
  r.deferNext(); await r.advanceTo(5000); r.cleanup.pause(); r.finishLoad(); await r.flush();
  assert.deepEqual(photos(r).map(n => n.style.backgroundImage), before);
  r.cleanup(); const writes = r.writes.length;
  r.cleanup.resume(); await r.flush();
  assert.equal(r.writes.length, writes);
  assert.equal(photos(r).length, 0);
  assert.equal(r.pending.size, 0);
});

test('resuming an in-flight wall replacement keeps one swap cadence and cancels every timeout', async () => {
  const r = fixture();
  try {
    await r.cleanup.ready;
    const initial = r.resolutions.length;
    r.deferNext(); await r.advanceTo(5000);
    r.cleanup.pause(); r.finishLoad(); await r.flush();
    await r.advanceTo(25000);
    r.cleanup.resume(); await r.flush();
    await r.advanceTo(31000);
    assert.equal(r.resolutions.length, initial + 2,
      'The decoded replacement and the next interval each admit exactly one photo');
    r.cleanup();
    assert.equal(r.pending.size, 0, 'Cleanup cancels all scheduled swap callbacks');
  } finally { r.cleanup(); }
});

test('failed replacement decode retains every opaque current tile', async () => {
  const r = fixture();
  try {
    await r.cleanup.ready;
    const before = photos(r).map(n => n.style.backgroundImage);
    r.deferNext(); await r.advanceTo(5000); r.failLoad(); await r.flush();
    assert.deepEqual(photos(r).map(n => n.style.backgroundImage), before);
    assert.ok(photos(r).every(n => r.state(n).opacity === 1));
  } finally { r.cleanup(); }
});

test('recovered dimensions recalculate the same admitted photos before wall publication', async () => {
  const deck = photographs().map(p => ({ ...p, w: 0, h: 0 }));
  const r = fixture({ photos: deck, deferLoad: true });
  try {
    await r.flush();
    deck.forEach((p, i) => { p.w = i % 2 ? 1800 : 900; p.h = i % 2 ? 1000 : 1600; });
    r.finishLoad(); assert.equal(await r.cleanup.ready, true);
    inside(r);
    const admitted = new Set(r.resolutions.map(n => `url("${n.url}")`));
    assert.ok(photos(r).every(n => admitted.has(/^url\("[^"]+"\)/.exec(n.style.backgroundImage)?.[0])));
    assert.equal(new Set(photos(r).map(n => n.style.backgroundImage)).size, photos(r).length);
  } finally { r.cleanup(); }
});
