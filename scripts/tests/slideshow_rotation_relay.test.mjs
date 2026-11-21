/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */
// Rotation mid-show (mock mini-screensaver-20261120): hold, dim to 20 %, re-lay the same beat
// for the new screen, then reveal. A resize that keeps the orientation changes nothing.
import test from 'node:test';
import assert from 'node:assert/strict';
import { readFileSync } from 'node:fs';
import vm from 'node:vm';
import { createSceneFixture } from './helpers/slideshow_scene_fixture.mjs';

const scheduler = readFileSync(new URL('../../app/src/main/assets/screensaver/scheduler.js', import.meta.url), 'utf8');
function fixture() {
  const photos = Array.from({ length: 8 }, (_, index) => ({
    seed: `turn-${index}`, w: 1600, h: 1000, fx: .5, fy: .5, url: `generated://turn/${index}`,
  }));
  let context, window;
  const events = new Map();
  const f = createSceneFixture({ photos, mode: 'framed', photoMs: 600000, configureWindow: (w, c) => {
    context = c; window = w;
    w.PHOTOS = photos;
    w.photoUrl = p => p.url;
    c.innerWidth = 1280; c.innerHeight = 800; c.screen = { width: 1280, height: 800 };
    c.addEventListener = (name, listener) => events.set(name + events.size, listener);
    c.removeEventListener = () => {};
  } });
  f.cleanup();
  const stage = f.nodes()[0]; stage.innerHTML = '';
  vm.runInContext(scheduler, context, { filename: 'scheduler.js' });
  const engine = window.SS.startEngine({ stage, mode: 'framed', intervalSec: 600, photoMotion: false });
  let now = 0;
  async function at(ms) { await f.flush(); await f.advanceTo(now += ms); }
  function photo() {
    const beat = stage.children.filter(n => n.classList.contains('on')).at(-1);
    const print = beat && f.nodes(beat).find(n => n.classList.contains('ss-print') &&
      n.style.backgroundImage?.startsWith('url('));
    return print ? Number(/turn\/(\d+)/.exec(print.style.backgroundImage)[1]) : -1;
  }
  function resize(w, h) {
    context.innerWidth = w; context.innerHeight = h;
    for (const [name, listener] of events) if (name.startsWith('resize')) listener();
  }
  const beats = () => stage.children.filter(n => n.classList.contains('ss-beat')).length;
  return { ...f, engine, stage, at, photo, resize, beats };
}

test('a turn dims, re-lays the same photo for the new screen, then reveals', async () => {
  const f = fixture();
  try {
    await f.at(1800);
    const shown = f.photo();
    assert.ok(shown >= 0);
    const first = f.stage.children.filter(n => n.classList.contains('on')).at(-1);
    f.resize(800, 1280);
    assert.equal(f.stage.style.opacity, '0.2', 'dims at once under the system rotation');
    await f.at(399);
    assert.equal(f.stage.style.opacity, '0.2', 'holds the dim before the re-lay');
    await f.at(1200);
    assert.equal(f.photo(), shown, 'the same beat is re-laid, not the next photo');
    const relaid = f.stage.children.filter(n => n.classList.contains('on')).at(-1);
    assert.notEqual(relaid, first, 'a fresh layer laid out at the new size');
    assert.ok(relaid.classList.contains('first-scene'), 'no dissolve under the dim');
    assert.equal(f.stage.style.opacity, '', 'revealed');
    assert.match(f.stage.style.transition, /500ms/);
  } finally { f.engine.stop(); }
});

test('a resize that keeps the orientation is not a turn', async () => {
  const f = fixture();
  try {
    await f.at(1800);
    const shown = f.photo(), layers = f.beats();
    f.resize(1280, 740);
    await f.at(1200);
    assert.notEqual(f.stage.style.opacity, '0.2');
    assert.equal(f.photo(), shown);
    assert.equal(f.beats(), layers);
  } finally { f.engine.stop(); }
});

test('closing the frame during the rotation dim releases it', async () => {
  const f = fixture();
  await f.at(1800);
  f.resize(800, 1280);
  assert.equal(f.stage.style.opacity, '0.2');
  f.engine.stop();
  assert.equal(f.stage.style.opacity, '');
  await f.at(1200);
  assert.equal(f.stage.style.opacity, '');
});

test('a turn while the first beat is still loading re-lays it once it lands', async () => {
  const f = fixture();
  try {
    f.deferNext();
    await f.flush();
    f.resize(800, 1280);
    assert.notEqual(f.stage.style.opacity, '0.2', 'nothing on screen yet to dim');
    f.finishLoad();
    await f.at(40);
    const first = f.stage.children.filter(n => n.classList.contains('on')).at(-1);
    assert.ok(first, 'the stale beat committed');
    assert.equal(f.stage.style.opacity, '0.2', 'and is re-laid under the dim');
    const shown = f.photo();
    await f.at(1800);
    const relaid = f.stage.children.filter(n => n.classList.contains('on')).at(-1);
    assert.notEqual(relaid, first);
    assert.equal(f.photo(), shown);
    assert.equal(f.stage.style.opacity, '');
  } finally { f.engine.stop(); }
});
