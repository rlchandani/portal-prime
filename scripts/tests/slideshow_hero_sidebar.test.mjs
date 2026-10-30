/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */
import test from 'node:test';
import assert from 'node:assert/strict';
import { createSceneFixture } from './helpers/slideshow_scene_fixture.mjs';

const photographs = () => Array.from({ length: 12 }, (_, id) => ({
  seed: 'sidebar-' + id, w: id % 2 ? 900 : 1800, h: id % 2 ? 1600 : 1000,
  ...(id % 3 === 0 ? { fx: .2, fy: .3 } : {}),
}));
const fixture = options => createSceneFixture({ mode: 'heroSidebar', photos: photographs(), ...options });
const cells = r => r.nodes().filter(n => n.classList.contains('ss-cell'));
const photos = r => r.prints().filter(n => n.style.backgroundImage?.startsWith('url('));
const fading = r => cells(r).find(cell => cell.children.filter(n => n.classList.contains('on')).length === 2);
function released(r, image) {
  assert.equal(image.style.backgroundImage, 'none');
  assert.equal(r.state(image).animation, 'none');
  assert.equal(r.state(image).promotion, 'auto');
  assert.equal(r.computed(r.slot(image)).visibility, 'hidden');
}

test('Hero + Sidebar prepares three whole static photos without duplicate blurred resources', async () => {
  const r = fixture();
  try {
    assert.equal(await r.cleanup.ready, true);
    assert.equal(cells(r).length, 3);
    assert.equal(r.nodes().filter(n => n.classList.contains('ss-fill')).length, 0);
    assert.equal(r.resolutions.length, 3);
    assert.deepEqual(r.resolutions.map(n => n.edge), [1800, 1100, 1100]);
    assert.equal(new Set(photos(r).map(n => n.style.backgroundImage)).size, 3);
    for (const image of photos(r)) {
      assert.equal(r.state(image).opacity, 1);
      assert.equal(r.state(image).animation, 'none');
      assert.equal(r.state(image).promotion, 'auto');
      assert.equal(r.computed(image)['background-size'], 'contain');
      assert.equal(r.computed(r.slot(image))['background-color'], '#1c1b19');
    }
  } finally { r.cleanup(); }
});

test('each incoming pane fades above an opaque outgoing pane before its URL retires', async () => {
  const r = fixture();
  try {
    await r.cleanup.ready;
    for (let beat = 1; beat <= 9; beat++) {
      await r.advanceTo(beat * 5000);
      const cell = fading(r); assert.ok(cell);
      const incoming = cell.children.at(-1), outgoing = cell.children.find(n => n !== incoming);
      assert.equal(incoming.opacity(), 0);
      assert.equal(outgoing.opacity(), 1);
      await r.advanceTo(beat * 5000 + 600);
      assert.ok(incoming.opacity() > 0 && incoming.opacity() < 1);
      assert.equal(outgoing.opacity(), 1);
      await r.advanceTo(beat * 5000 + 1100); r.end(incoming.children[0]);
      released(r, outgoing.children[0]);
      assert.equal(incoming.opacity(), 1);
      assert.equal(photos(r).length, 3);
    }
  } finally { r.cleanup(); }
});

test('rapid pane replacement keeps two image slots per cell without accumulating owners', async () => {
  const r = fixture({ photoMs: 500 });
  try {
    await r.cleanup.ready;
    const count = r.nodes().length;
    for (let beat = 1; beat <= 30; beat++) {
      await r.advanceTo(beat * 500);
      assert.equal(r.nodes().length, count);
      assert.ok(cells(r).every(cell => cell.children.length === 2));
      assert.ok(photos(r).length <= 6);
      assert.ok(cells(r).every(cell => cell.children.some(n => n.opacity() === 1)));
    }
  } finally { r.cleanup(); }
});

test('old-WebView pause retains live fades and resumes their remaining active duration', async () => {
  const r = fixture();
  try {
    await r.cleanup.ready; await r.advanceTo(5000); await r.advanceTo(5350);
    const cell = fading(r), incoming = cell.children.at(-1), outgoing = cell.children.find(n => n !== incoming);
    const opacity = incoming.opacity(), before = photos(r).map(n => n.style.backgroundImage);
    r.cleanup.pause(); await r.advanceTo(25000);
    assert.equal(incoming.opacity(), opacity);
    assert.deepEqual(photos(r).map(n => n.style.backgroundImage), before);
    assert.equal(r.pending.size, 0);
    r.cleanup.resume(); await r.advanceTo(25750); r.end(incoming.children[0]);
    released(r, outgoing.children[0]);
    assert.equal(incoming.opacity(), 1);
  } finally { r.cleanup(); }
});

test('cleanup during a held fade releases every pane URL and rejects stale events and timers', async () => {
  const r = fixture(); await r.cleanup.ready; await r.advanceTo(5000); await r.advanceTo(5350);
  const callbacks = [...r.pending.values()].map(t => t.fn);
  const incoming = fading(r).children.at(-1), event = incoming.ontransitionend;
  r.cleanup.pause(); r.cleanup(); r.cleanup();
  const writes = r.writes.length;
  event?.({ target: incoming, propertyName: 'opacity' });
  for (const fn of callbacks) { fn(); await r.flush(); }
  r.cleanup.resume(); await r.advanceTo(60000);
  assert.equal(r.writes.length, writes);
  r.prints().forEach(image => released(r, image));
  assert.equal(r.pending.size, 0);
});

test('cleanup at initial decode-admission boundaries rejects late pane publication', async () => {
  for (let checkpoint = 0; checkpoint <= 24; checkpoint++) {
    const r = fixture({ deferLoad: true });
    await r.flush(); r.finishLoad();
    for (let turn = 0; turn < checkpoint; turn++) await Promise.resolve();
    r.cleanup(); const writes = r.writes.length, requests = r.resolutions.length;
    await r.cleanup.ready; await r.flush();
    assert.equal(r.writes.length, writes, `Closed owner at microtask ${checkpoint} cannot publish`);
    assert.equal(r.resolutions.length, requests, 'A closed pane cannot request another derivative');
    assert.equal(photos(r).length, 0);
    assert.equal(r.pending.size, 0);
  }
});

test('a paused replacement decode cannot publish after the pane owner closes', async () => {
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

test('failed replacement decode retains every ready opaque photo pane', async () => {
  const r = fixture();
  try {
    await r.cleanup.ready;
    const before = photos(r).map(n => n.style.backgroundImage);
    r.deferNext(); await r.advanceTo(5000); r.failLoad(); await r.flush();
    assert.deepEqual(photos(r).map(n => n.style.backgroundImage), before);
    assert.ok(photos(r).every(n => r.state(n).opacity === 1));
  } finally { r.cleanup(); }
});

for (const initial of [true, false]) {
  test(`${initial ? 'initial' : 'replacement'} preparation cannot admit or publish work after resume/re-pause`, async () => {
    for (let checkpoint = 0; checkpoint <= 24; checkpoint++) {
      const r = fixture({ deferLoad: initial });
      try {
        if (initial) await r.flush();
        else { await r.cleanup.ready; r.deferNext(); await r.advanceTo(5000); }
        r.cleanup.pause(); r.finishLoad(); await r.flush();
        r.cleanup.resume();
        for (let turn = 0; turn < checkpoint; turn++) await Promise.resolve();
        r.cleanup.pause();
        const writes = r.writes.length, requests = r.resolutions.length;
        await r.flush();
        assert.equal(r.writes.length, writes, `Microtask ${checkpoint}: publication stays paused`);
        assert.equal(r.resolutions.length, requests, `Microtask ${checkpoint}: derivative admission stays paused`);
        r.cleanup.resume(); await r.cleanup.ready; await r.flush();
        assert.ok(photos(r).length >= 3, 'The same prepared work resumes without a blank scene');
      } finally { r.cleanup(); }
    }
  });
}

test('one- and two-photo libraries render only available unique panes', async () => {
  for (const count of [1, 2]) {
    const r = fixture({ photos: photographs().slice(0, count) });
    try {
      await r.cleanup.ready;
      assert.equal(cells(r).length, count);
      assert.equal(photos(r).length, count);
      assert.equal(r.resolutions.length, count);
      assert.equal(new Set(photos(r).map(n => n.style.backgroundImage)).size, count);
      await r.advanceTo(20000);
      assert.equal(r.resolutions.length, count, 'An exhausted library cannot create duplicate panes');
    } finally { r.cleanup(); }
  }
});
