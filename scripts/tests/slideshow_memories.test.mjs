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
  seed: 'memory-' + id, w: id % 2 ? 900 : 1800, h: id % 2 ? 1600 : 1000,
  ...(id % 3 === 0 ? { fx: .2, fy: .35 } : {}),
}));
const fixture = options => createSceneFixture({ mode: 'memories', photos: photographs(), ...options });
const photos = r => r.prints().filter(n => n.style.backgroundImage?.startsWith('url('));
const scenes = r => r.nodes().filter(n => n.classList.contains('ss-memory-scene'));
const current = r => scenes(r).at(-1);
const cards = r => r.nodes().filter(n => n.classList.contains('ss-memory-card'));
function released(image) {
  assert.equal(image.style.backgroundImage, 'none');
  assert.equal(image.style.animation, 'none');
  assert.equal(image.style.willChange, 'auto');
}
async function collage(r) { await r.cleanup.ready; await r.advanceTo(25000); }

test('Memories starts with one opaque whole photo and no duplicate blurred fills', async () => {
  const r = fixture();
  try {
    assert.equal(await r.cleanup.ready, true);
    assert.equal(scenes(r).length, 2);
    assert.equal(photos(r).length, 1);
    assert.equal(current(r).opacity(), 1);
    assert.equal(r.resolutions.length, 1);
    assert.equal(r.resolutions[0].edge, 2200);
    assert.equal(r.nodes().filter(n => n.classList.contains('ss-fill')).length, 0);
    assert.equal(r.computed(photos(r)[0])['background-size'], 'contain');
  } finally { r.cleanup(); }
});

test('the sixth beat retains the neutral three-photo collage with whole static photos', async () => {
  const r = fixture();
  try {
    await collage(r);
    await r.advanceTo(27000); r.end(photos(r).at(-1));
    assert.equal(cards(r).length, 3);
    assert.equal(photos(r).length, 3);
    assert.equal(new Set(photos(r).map(n => n.style.backgroundImage)).size, 3);
    const text = r.nodes().map(n => n.textContent).filter(Boolean);
    assert.deepEqual(text, ['Photo memories']);
    for (const image of photos(r)) {
      assert.equal(image.style.backgroundSize, 'contain');
      assert.equal(r.state(image).animation, 'none');
      assert.equal(r.state(image).promotion, 'auto');
    }
    for (const card of cards(r)) {
      const left = parseFloat(card.style.left), top = parseFloat(card.style.top);
      assert.ok(left >= 0 && top >= 0 && left + parseFloat(card.style.width) <= 2160 &&
        top + parseFloat(card.style.height) <= 1440);
    }
  } finally { r.cleanup(); }
});

test('single and collage transitions retain an opaque outgoing scene until retirement', async () => {
  const r = fixture();
  try {
    await r.cleanup.ready;
    for (let beat = 1; beat <= 12; beat++) {
      const oldImages = photos(r);
      await r.advanceTo(beat * 5000);
      const incoming = current(r), outgoing = scenes(r).find(n => n !== incoming);
      assert.equal(incoming.opacity(), 0);
      assert.equal(outgoing.opacity(), 1);
      await r.advanceTo(beat * 5000 + 1200);
      assert.ok(incoming.opacity() > 0 && incoming.opacity() < 1);
      assert.equal(outgoing.opacity(), 1);
      await r.advanceTo(beat * 5000 + 2000); r.end(photos(r).at(-1));
      oldImages.forEach(released);
      assert.equal(incoming.opacity(), 1);
      assert.ok(photos(r).length === 1 || photos(r).length === 3);
    }
  } finally { r.cleanup(); }
});

test('rapid beat changes retain only two scene roots and at most four photo owners', async () => {
  const r = fixture({ photoMs: 500 });
  try {
    await r.cleanup.ready;
    for (let beat = 1; beat <= 36; beat++) {
      await r.advanceTo(beat * 500);
      assert.equal(scenes(r).length, 2);
      assert.ok(photos(r).length <= 4);
      assert.ok(scenes(r).some(n => n.opacity() === 1));
    }
  } finally { r.cleanup(); }
});

test('a paused collage dissolve holds resources and resumes only its remaining active duration', async () => {
  const r = fixture();
  try {
    await collage(r); await r.advanceTo(25600);
    const incoming = current(r), opacity = incoming.opacity();
    const before = photos(r).map(n => n.style.backgroundImage);
    r.cleanup.pause(); await r.advanceTo(50000);
    assert.equal(incoming.opacity(), opacity);
    assert.deepEqual(photos(r).map(n => n.style.backgroundImage), before);
    assert.equal(r.pending.size, 0);
    r.cleanup.resume(); await r.advanceTo(51400); r.end(photos(r).at(-1));
    assert.equal(incoming.opacity(), 1);
    assert.equal(photos(r).length, 3);
  } finally { r.cleanup(); }
});

test('terminal cleanup releases single and collage photos and rejects stale retirement', async () => {
  const r = fixture(); await collage(r); await r.advanceTo(25600);
  const images = photos(r), incoming = current(r), event = incoming.ontransitionend;
  const callbacks = [...r.pending.values()].map(t => t.fn);
  r.cleanup.pause(); r.cleanup(); r.cleanup();
  const writes = r.writes.length;
  event?.({ target: incoming, propertyName: 'opacity' });
  for (const callback of callbacks) { callback(); await r.flush(); }
  r.cleanup.resume(); await r.advanceTo(60000);
  assert.equal(r.writes.length, writes);
  images.forEach(released);
  assert.equal(photos(r).length, 0);
  assert.equal(r.pending.size, 0);
});

test('initial decode handoff checkpoints reject publication and derivative work after close', async () => {
  for (let checkpoint = 0; checkpoint <= 24; checkpoint++) {
    const r = fixture({ deferLoad: true });
    await r.flush(); r.finishLoad();
    for (let turn = 0; turn < checkpoint; turn++) await Promise.resolve();
    r.cleanup(); const writes = r.writes.length, requests = r.resolutions.length;
    await r.cleanup.ready; await r.flush();
    assert.equal(r.writes.length, writes);
    assert.equal(r.resolutions.length, requests);
    assert.equal(photos(r).length, 0);
    assert.equal(r.pending.size, 0);
  }
});

for (const initial of [true, false]) {
  test(`${initial ? 'initial' : 'collage'} decode stays paused through rapid resume/re-pause`, async () => {
    for (let checkpoint = 0; checkpoint <= 24; checkpoint++) {
      const r = fixture({ deferLoad: initial });
      try {
        if (initial) await r.flush();
        else { await r.cleanup.ready; await r.advanceTo(20000); r.deferNext(); await r.advanceTo(25000); }
        r.cleanup.pause(); r.finishLoad(); await r.flush();
        r.cleanup.resume();
        for (let turn = 0; turn < checkpoint; turn++) await Promise.resolve();
        r.cleanup.pause();
        const writes = r.writes.length, requests = r.resolutions.length;
        await r.flush();
        assert.equal(r.writes.length, writes, `Paused publication at microtask ${checkpoint}`);
        assert.equal(r.resolutions.length, requests, `Paused admission at microtask ${checkpoint}`);
        r.cleanup.resume(); await r.cleanup.ready; await r.flush();
        assert.ok(photos(r).length >= 1);
      } finally { r.cleanup(); }
    }
  });
}

test('failed collage preparation retains the current photo and retries the collage beat', async () => {
  const r = fixture();
  try {
    await r.cleanup.ready; await r.advanceTo(20000);
    await r.advanceTo(22100);
    const before = photos(r).map(n => n.style.backgroundImage);
    r.deferNext(); await r.advanceTo(25000); r.failLoad(); await r.flush();
    assert.deepEqual(photos(r).map(n => n.style.backgroundImage), before);
    assert.equal(current(r).opacity(), 1);
    await r.advanceTo(30000);
    assert.equal(cards(r).length, 3);
  } finally { r.cleanup(); }
});

test('small libraries keep unique collage photos instead of manufacturing repeats', async () => {
  for (const count of [1, 2]) {
    const r = fixture({ photos: photographs().slice(0, count) });
    try {
      await collage(r); await r.advanceTo(27000); r.end(photos(r).at(-1));
      assert.equal(cards(r).length, count);
      assert.equal(photos(r).length, count);
      assert.equal(new Set(photos(r).map(n => n.style.backgroundImage)).size, count);
    } finally { r.cleanup(); }
  }
});

test('cached face hints retain a complete still single photo; unhinted singles use a bounded push', async () => {
  for (const hinted of [true, false]) {
    const photo = { seed: 'portrait', w: 900, h: 1600, ...(hinted ? { fx: .2, fy: .3 } : {}) };
    const r = fixture({ photos: [photo] });
    try {
      await r.cleanup.ready;
      const image = photos(r)[0];
      assert.equal(image.style.backgroundSize, 'contain');
      assert.equal(r.state(image).promotion, hinted ? 'auto' : 'transform');
      assert.equal(r.state(image).animation, hinted ? 'none' : 'cinematicDrift 5400ms linear forwards');
    } finally { r.cleanup(); }
  }
});
