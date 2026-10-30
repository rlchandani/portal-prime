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
  seed: 'paper-' + i, w: i % 3 === 0 ? 20000 : i % 3 === 1 ? 300 : 1500,
  h: i % 3 === 1 ? 12000 : 1000,
}));
const fixture = options => createSceneFixture({ mode: 'polaroid', photos: photographs(16), ...options });
const owned = r => r.prints().filter(image => image.style.backgroundImage?.startsWith('url('));
const live = r => owned(r).filter(image => !r.slot(image).classList.contains('retired'));
function released(r, image) {
  assert.equal(image.style.backgroundImage, 'none');
  assert.equal(r.slot(image).style.willChange, 'auto');
}
function bounds(card) {
  const W = parseFloat(card.style.width), H = parseFloat(card.style.height);
  const rotation = /rotate\(([-\d.]+)deg\)/.exec(card.style.transform)?.[1];
  assert.notEqual(rotation, undefined);
  const angle = Math.abs(Number(rotation)) * Math.PI / 180;
  const width = W * Math.cos(angle) + H * Math.sin(angle);
  const height = W * Math.sin(angle) + H * Math.cos(angle);
  const cx = parseFloat(card.style.left) + W / 2, cy = parseFloat(card.style.top) + H / 2;
  return { left: cx - width / 2, top: cy - height / 2, right: cx + width / 2, bottom: cy + height / 2 };
}

test('Polaroid initial composition owns six unique opaque whole photos and seven reusable slots', async () => {
  const r = fixture();
  try {
    assert.equal(await r.cleanup.ready, true);
    assert.equal(r.prints().length, 7);
    assert.equal(owned(r).length, 6);
    assert.equal(new Set(live(r).map(image => image.style.backgroundImage)).size, 6);
    for (const image of live(r)) {
      assert.equal(r.slot(image).opacity(), 1);
      assert.equal(image.style.backgroundSize, 'contain');
      assert.equal(r.slot(image).style.willChange, 'auto');
    }
    assert.equal(r.resolutions.length, 6);
  } finally { r.cleanup(); }
});

test('rotated Polaroid paper envelopes fit distinct regions across screen and photo aspects', async () => {
  for (const [W, H] of [[2160, 1440], [1920, 1080], [1024, 768], [640, 960]]) {
    for (const random of [.01, .99]) {
      const r = fixture({ W, H, random });
      try {
        await r.cleanup.ready;
        const boxes = live(r).map(image => bounds(r.slot(image)));
        for (const box of boxes) assert.ok(box.left >= 0 && box.top >= 0 && box.right <= W && box.bottom <= H);
        for (let a = 0; a < boxes.length; a++) for (let b = a + 1; b < boxes.length; b++) {
          const x = boxes[a], y = boxes[b];
          assert.ok(x.right <= y.left || y.right <= x.left || x.bottom <= y.top || y.bottom <= x.top);
        }
        for (const image of live(r)) {
          const seed = /paper-(\d+)/.exec(image.style.backgroundImage)[1], photo = photographs(16)[seed];
          assert.ok(Math.abs(parseFloat(image.style.width) / parseFloat(image.style.height) - photo.w / photo.h) < .0001);
        }
      } finally { r.cleanup(); }
    }
  }
});

test('Polaroid holds the oldest opaque print until the incoming card is opaque, then releases it', async () => {
  const r = fixture();
  try {
    await r.cleanup.ready; const before = owned(r), old = before[0];
    await r.advanceTo(5000);
    assert.equal(owned(r).length, 7);
    const incoming = owned(r).find(image => !before.includes(image));
    assert.ok(incoming);
    assert.equal(r.slot(old).opacity(), 1); assert.equal(r.slot(incoming).opacity(), 0);
    await r.advanceTo(5400);
    assert.equal(r.slot(old).opacity(), 1);
    assert.ok(r.slot(incoming).opacity() > 0 && r.slot(incoming).opacity() < 1);
    await r.advanceTo(6000);
    released(r, old);
    assert.equal(owned(r).length, 6); assert.equal(r.prints().length, 7);
    assert.ok(live(r).every(image => r.slot(image).style.willChange === 'auto'));
  } finally { r.cleanup(); }
});

test('Polaroid pause freezes a live drop, releases hints, and retains its remaining fade and cadence', async () => {
  const r = fixture();
  try {
    await r.cleanup.ready; await r.advanceTo(5400);
    r.cleanup.pause();
    const state = owned(r).map(image => [image.style.backgroundImage, r.slot(image).opacity()]);
    const requests = r.resolutions.length;
    await r.advanceTo(50000);
    assert.deepEqual(owned(r).map(image => [image.style.backgroundImage, r.slot(image).opacity()]), state);
    assert.equal(r.resolutions.length, requests); assert.equal(r.pending.size, 0);
    assert.ok(owned(r).every(image => r.slot(image).style.willChange === 'auto'));
    r.cleanup.resume(); await r.advanceTo(50600);
    assert.equal(owned(r).length, 6);
    assert.equal(r.resolutions.length, requests);
    await r.advanceTo(54600);
    assert.equal(r.resolutions.length, requests + 1);
  } finally { r.cleanup(); }
});

test('rapid Polaroid drops retain a bounded pool and reject stale retirement callbacks on reuse', async () => {
  const r = fixture({ photoMs: 250 });
  try {
    await r.cleanup.ready;
    let stale;
    for (let beat = 1; beat <= 30; beat++) {
      await r.advanceTo(beat * 250);
      assert.equal(r.prints().length, 7);
      assert.ok(owned(r).length <= 7);
      assert.ok(owned(r).filter(image => r.slot(image).style.willChange !== 'auto').length <= 1);
      if (beat === 1) {
        const target = owned(r).map(image => r.slot(image)).find(card => card.ontransitionend);
        const event = target.ontransitionend;
        stale = () => event?.({ target, propertyName: 'opacity' });
      } else {
        const before = owned(r).map(image => image.style.backgroundImage);
        stale(); await r.flush();
        assert.deepEqual(owned(r).map(image => image.style.backgroundImage), before);
      }
    }
  } finally { r.cleanup(); }
});

test('failed Polaroid replacement retains all six ready prints and retries on the next cadence', async () => {
  const r = fixture();
  try {
    await r.cleanup.ready; const before = live(r).map(image => image.style.backgroundImage);
    r.deferNext(); await r.advanceTo(5000); r.failLoad(); await r.flush();
    assert.deepEqual(live(r).map(image => image.style.backgroundImage), before);
    assert.ok(live(r).every(image => r.slot(image).opacity() === 1));
    await r.advanceTo(10000); await r.advanceTo(11000);
    assert.equal(owned(r).length, 6); assert.notDeepEqual(live(r).map(image => image.style.backgroundImage), before);
  } finally { r.cleanup(); }
});

test('Polaroid terminal cleanup releases every URL/hint and rejects stale motion, timer and resume work', async () => {
  const r = fixture(); await r.cleanup.ready; await r.advanceTo(5400);
  const all = r.prints(), callbacks = [...r.pending.values()].map(task => task.fn);
  const events = all.map(image => [r.slot(image), r.slot(image).ontransitionend]);
  r.cleanup.pause(); r.cleanup(); r.cleanup();
  const writes = r.writes.length, requests = r.resolutions.length;
  for (const [target, event] of events) event?.({ target, propertyName: 'opacity' });
  for (const callback of callbacks) { callback(); await r.flush(); }
  r.cleanup.resume(); await r.advanceTo(60000);
  all.forEach(image => released(r, image));
  assert.equal(r.writes.length, writes); assert.equal(r.resolutions.length, requests);
  assert.equal(r.pending.size, 0); assert.equal(r.prints().length, 0);
});

for (const initial of [true, false]) {
  test(`${initial ? 'initial' : 'replacement'} Polaroid loading rejects resume/re-pause publications`, async () => {
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
        assert.equal(r.writes.length, writes); assert.equal(r.resolutions.length, requests);
        r.cleanup.resume(); await r.cleanup.ready; await r.flush();
      } finally { r.cleanup(); }
    }
  });
}

test('closing initial Polaroid loading prevents late publication at every decode checkpoint', async () => {
  for (let checkpoint = 0; checkpoint <= 24; checkpoint++) {
    const r = fixture({ deferLoad: true });
    await r.flush(); r.finishLoad();
    for (let turn = 0; turn < checkpoint; turn++) await Promise.resolve();
    r.cleanup();
    const writes = r.writes.length, requests = r.resolutions.length;
    await r.flush();
    assert.equal(await r.cleanup.ready, false);
    assert.equal(r.writes.length, writes); assert.equal(r.resolutions.length, requests);
    assert.equal(r.pending.size, 0); assert.equal(r.prints().length, 0);
  }
});

test('one/two/five-photo Polaroid libraries retain unique whole prints with no invented labels', async () => {
  for (const count of [1, 2, 5]) {
    const r = fixture({ photos: photographs(count).map(p => ({ ...p, fx: .8, fy: .1 })) });
    try {
      await r.cleanup.ready;
      assert.equal(owned(r).length, count); assert.equal(r.prints().length, count + 1);
      assert.equal(new Set(owned(r).map(image => image.style.backgroundImage)).size, count);
      assert.ok(owned(r).every(image => image.style.backgroundSize === 'contain'));
      assert.ok(r.nodes().every(node => !node.textContent));
    } finally { r.cleanup(); }
  }
});
