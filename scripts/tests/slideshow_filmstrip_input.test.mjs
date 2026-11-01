/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */
import test from 'node:test';
import assert from 'node:assert/strict';
import { createSceneFixture } from './helpers/slideshow_scene_fixture.mjs';

const fixture = () => createSceneFixture({ mode: 'filmstrip', photoMs: 5000 });
const center = f => f.nodes().filter(n => n.classList.contains('ss-slide'))
  .findIndex(n => n.classList.contains('center'));

test('legacy Filmstrip retains a reverse swipe during an undecoded forward move', async () => {
  const f = fixture();
  try {
    await f.flush(); assert.equal(await f.cleanup.ready, true); assert.equal(center(f), 0);
    f.deferNext();
    const first = f.cleanup.next(); await f.flush();
    const loadCount = f.loads.length;
    let settled = false;
    const reverse = f.cleanup.previous().then(ok => { settled = true; return ok; });
    await f.flush();
    assert.equal(settled, false); assert.equal(center(f), 0);
    assert.equal(f.loads.length, loadCount, 'Queued input cannot start a competing decode batch');
    f.finishLoad(); await f.flush();
    assert.equal(await first, true); assert.equal(await reverse, true); assert.equal(center(f), 0);
  } finally { f.cleanup(); }
});

test('manual Filmstrip input is retained behind an automatic decode', async () => {
  const f = fixture();
  try {
    await f.flush(); assert.equal(await f.cleanup.ready, true);
    f.deferNext(); await f.advanceTo(5000);
    const loadCount = f.loads.length;
    let settled = false;
    const reverse = f.cleanup.previous().then(ok => { settled = true; return ok; });
    await f.flush(); assert.equal(settled, false); assert.equal(f.loads.length, loadCount);
    f.finishLoad(); await f.flush(); assert.equal(await reverse, true); assert.equal(center(f), 0);
    await f.advanceTo(9999); assert.equal(center(f), 0, 'Manual publication gets a fresh dwell');
    await f.advanceTo(10000); assert.equal(center(f), 1);
  } finally { f.cleanup(); }
});

test('Filmstrip coalesces a rapid burst to one latest pending direction', async () => {
  const f = fixture();
  try {
    await f.flush(); await f.cleanup.ready; f.deferNext();
    const first = f.cleanup.next(); await f.flush();
    const superseded = Array.from({ length: 20 }, () => f.cleanup.next());
    let settled = false;
    const latest = f.cleanup.previous().then(ok => { settled = true; return ok; });
    await f.flush();
    assert.deepEqual(await Promise.all(superseded), Array(20).fill(false));
    assert.equal(settled, false);
    f.finishLoad(); await f.flush();
    assert.equal(await first, true); assert.equal(await latest, true); assert.equal(center(f), 0);
  } finally { f.cleanup(); }
});

test('a covered Filmstrip defers pending input and its publication until resume', async () => {
  const f = fixture();
  try {
    await f.flush(); await f.cleanup.ready; f.deferNext();
    const first = f.cleanup.next(); await f.flush(); f.cleanup.pause();
    const writes = f.writes.length;
    const reverse = f.cleanup.previous();
    f.finishLoad(); await f.flush(); await f.advanceTo(20000);
    assert.equal(center(f), 0); assert.equal(f.writes.length, writes);
    f.cleanup.resume(); await f.flush();
    assert.equal(await first, true); assert.equal(await reverse, true); assert.equal(center(f), 0);
    await f.advanceTo(24999); assert.equal(center(f), 0);
    await f.advanceTo(25000); assert.equal(center(f), 1);
  } finally { f.cleanup(); }
});

test('closing Filmstrip settles both requests and a late decoder cannot publish', async () => {
  const f = fixture();
  await f.flush(); await f.cleanup.ready; f.deferNext();
  const first = f.cleanup.next(); await f.flush();
  const reverse = f.cleanup.previous(); await f.flush();
  f.cleanup(); const writes = f.writes.length, loadCount = f.loads.length;
  assert.equal(await first, false); assert.equal(await reverse, false);
  f.finishLoad(); await f.flush(); await f.advanceTo(20000);
  assert.equal(f.writes.length, writes); assert.equal(f.loads.length, loadCount);
  assert.equal(f.pending.size, 0);
  assert.equal(await f.cleanup.next(), false);
});
