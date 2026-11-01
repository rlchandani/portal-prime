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

const scheduler = readFileSync(new URL('../../app/src/main/assets/screensaver/scheduler.js', import.meta.url), 'utf8');
function fixture() {
  const photos = Array.from({ length: 8 }, (_, index) => ({
    seed: `history-${index}`, w: 1600, h: 1000, fx: .5, fy: .5,
    url: `generated://history/${index}`,
  }));
  let context, window;
  const events = new Map();
  const f = createSceneFixture({ photos, mode: 'framed', photoMs: 600000, configureWindow: (w, c) => {
    context = c; window = w;
    w.PHOTOS = photos;
    w.photoUrl = p => p.url;
    c.innerWidth = 2160; c.innerHeight = 1440; c.screen = { width: 2160, height: 1440 };
    c.addEventListener = (name, listener) => events.set(name, listener);
    c.removeEventListener = name => events.delete(name);
  } });
  // Reuse the fixture DOM/clock/decode gate; only the real scheduler owns scenes.
  f.cleanup();
  const stage = f.nodes()[0]; stage.innerHTML = '';
  vm.runInContext(scheduler, context, { filename: 'scheduler.js' });
  const engine = window.SS.startEngine({ stage, mode: 'framed', intervalSec: 600, photoMotion: false });
  let now = 0;
  async function show() { await f.flush(); await f.advanceTo(now += 1800); }
  function photo() {
    const beat = stage.children.filter(n => n.classList.contains('on')).at(-1);
    const print = beat && f.nodes(beat).find(n => n.classList.contains('ss-print') &&
      n.style.backgroundImage?.startsWith('url('));
    return print ? Number(/history\/(\d+)/.exec(print.style.backgroundImage)[1]) : -1;
  }
  return { ...f, engine, stage, show, photo };
}

test('Next cancelling a pending Previous preserves the original backward history', async () => {
  const f = fixture();
  try {
    await f.show(); assert.equal(f.photo(), 0);
    f.engine.next(); await f.show(); assert.equal(f.photo(), 1);
    f.deferNext(); f.engine.previous(); await f.flush();
    const cancelled = f.stage.children.at(-1);
    assert.equal(f.photo(), 1, 'Unaccepted Previous must leave the current photo visible');
    f.engine.next(); await f.show(); assert.equal(f.photo(), 1);
    assert.equal(cancelled.parentNode, null);
    f.finishLoad(); await f.flush(); assert.equal(f.photo(), 1);
    f.engine.previous(); await f.show();
    assert.equal(f.photo(), 0, 'Cancelling Previous cannot append a second copy of the current entry');
  } finally { f.engine.stop(); }
});

test('Previous cancelling a pending forward history entry preserves forward navigation', async () => {
  const f = fixture();
  try {
    await f.show(); f.engine.next(); await f.show(); f.engine.next(); await f.show();
    assert.equal(f.photo(), 2);
    f.engine.previous(); await f.show(); assert.equal(f.photo(), 1);
    f.deferNext(); f.engine.next(); await f.flush();
    f.engine.previous(); await f.show(); assert.equal(f.photo(), 1);
    f.finishLoad(); await f.flush();
    f.engine.next(); await f.show(); assert.equal(f.photo(), 2);
  } finally { f.engine.stop(); }
});

test('multiple pending history requests retain their requested position without extra scene owners', async () => {
  const f = fixture();
  try {
    await f.show(); f.engine.next(); await f.show(); f.engine.next(); await f.show();
    assert.equal(f.photo(), 2);
    f.deferNext(); f.engine.previous(); await f.flush();
    f.deferNext(); f.engine.previous(); await f.flush();
    assert.equal(f.stage.children.length, 2, 'Only current and latest preparing scenes may survive');
    f.engine.next(); await f.show(); assert.equal(f.photo(), 1);
    f.finishLoad(); await f.flush();
    f.engine.previous(); await f.show(); assert.equal(f.photo(), 0);
  } finally { f.engine.stop(); }
});

test('stopping an interrupted history move remains terminal after its decoder completes', async () => {
  const f = fixture();
  await f.show(); f.engine.next(); await f.show();
  f.deferNext(); f.engine.previous(); await f.flush();
  f.engine.next(); await f.flush(); f.engine.stop();
  f.finishLoad(); await f.flush(); await f.advanceTo(600000);
  assert.equal(f.stage.children.length, 0); assert.equal(f.pending.size, 0);
  assert.equal(f.engine.next(), false); assert.equal(f.engine.previous(), false);
});
