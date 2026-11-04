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

const assets = new URL('../../app/src/main/assets/screensaver/', import.meta.url);
const ids = ['kenBurns', 'diptych', 'framed', 'galleryWall', 'push', 'memories', 'panorama',
  'polaroid', 'mosaic', 'filmstrip', 'cinema', 'heroSidebar'];
const html = readFileSync(new URL('index.html', assets), 'utf8');
const entry = [...html.matchAll(/<script>([\s\S]*?)<\/script>/g)].at(-1)[1];

// Actual entry page and scheduler, synthetic scene readiness/DOM/clock; no image data or network.
function fixture({ mode = 'filmstrip', bridge = 'present', motionBridge = 'missing',
  motionValue = true, configMotion, sequence } = {}) {
  let now = 0, nextId = 1;
  const timers = new Map(), handlers = new Map(), scenes = [], reports = [], surfaces = [];
  class Element {
    constructor() { this.children = []; this.classes = new Set(); this.style = { setProperty() {} }; }
    get classList() { return { add: (...ids) => ids.forEach(id => this.classes.add(id)),
      toggle: (id, value) => value ? this.classes.add(id) : this.classes.delete(id),
      remove: (...ids) => ids.forEach(id => this.classes.delete(id)), contains: id => this.classes.has(id) }; }
    appendChild(node) { node.parent = this; this.children.push(node); }
    remove() { if (this.parent) this.parent.children = this.parent.children.filter(node => node !== this); }
    set innerHTML(value) { assert.equal(value, ''); this.children = []; }
  }
  const elements = new Map(['stage', 'loading', 'chrome-clock', 'chrome-scrim', 'chrome-mode'].map(id => [id, new Element()]));
  function schedule(fn, ms) { const id = nextId++; timers.set(id, { fn, at: now + Number(ms || 0) }); return id; }
  const Android = { getMode: () => mode, getIntervalSec: () => 30 };
  Android.paperSurfaceChanged = enabled => surfaces.push({ enabled, at: now });
  if (motionBridge !== 'missing') Android.getPhotoMotion = () => {
    if (motionBridge === 'throwing') throw Error('synthetic motion bridge failure');
    return motionValue;
  };
  if (bridge !== 'missing') Android.reportPresentedStyle = id => {
    if (bridge === 'throwing') throw Error('synthetic bridge failure');
    reports.push({ id, at: now });
  };
  const modes = Object.fromEntries(ids.map(id => [id, { name: 'Generated style label', run(ctx) {
    let finish; const ready = new Promise(resolve => { finish = resolve; });
    const scene = { finish, cleanup: null, photoMotion: ctx.photoMotion }; scenes.push(scene);
    const cleanup = () => {}; cleanup.ready = ready; cleanup.pause = () => {}; cleanup.resume = () => {};
    scene.cleanup = cleanup; return cleanup;
  } }]));
  const context = vm.createContext({ URLSearchParams, AbortController, performance: { now: () => now },
    Date: class extends Date { static now() { return now; } },
    console: { info() {} }, location: { search: '?nativeClock=1' }, Android,
    SS_CONFIG: { photoMotion: configMotion },
    PHOTOS: [{ seed: 1 }], shuffled: photos => photos,
    photoUrl: () => 'file:///generated.jpg',
    innerWidth: 2160, innerHeight: 1440, screen: { width: 2160, height: 1440 },
    document: { body: new Element(), getElementById: id => elements.get(id), createElement: () => new Element() },
    setTimeout: schedule, clearTimeout: id => timers.delete(id), clearInterval: id => timers.delete(id),
    requestAnimationFrame: fn => schedule(fn, 16), cancelAnimationFrame: id => timers.delete(id),
    addEventListener: (name, handler) => handlers.set(name, handler), removeEventListener() {},
    SS: { MODES: modes, MODE_IDS: ids, BEAT: {}, peopleDeck: deck => deck,
      pickNextMode: () => sequence ? sequence.shift() || 'push' : 'filmstrip' },
  });
  context.window = context;
  vm.runInContext(readFileSync(new URL('scheduler.js', assets), 'utf8'), context);
  vm.runInContext(entry, context);
  async function flush() { for (let i = 0; i < 20; i++) await Promise.resolve(); }
  async function tick(ms) {
    await flush(); const end = now + ms;
    for (;;) {
      const first = [...timers].filter(([, task]) => task.at <= end).sort((a, b) => a[1].at - b[1].at || a[0] - b[0])[0];
      if (!first) break;
      const [id, task] = first; timers.delete(id); now = task.at; task.fn(); await flush();
    }
    now = end; await flush();
  }
  return { reports, surfaces, scenes, elements, context, tick, flush, close: () => handlers.get('pagehide')() };
}

for (const [name, options, expected] of [
  ['older host without motion bridge', {}, true],
  ['native motion disabled', { motionBridge: 'present', motionValue: false }, false],
  ['native motion enabled wins over fallback', { motionBridge: 'present', motionValue: true, configMotion: false }, true],
  ['fallback motion disabled', { configMotion: false }, false],
  ['throwing bridge uses fallback', { motionBridge: 'throwing', configMotion: false }, false],
  ['wrong-typed bridge does not silently disable motion', { motionBridge: 'present', motionValue: 'false' }, true],
]) test(`entry and scheduler preserve ${name}`, () => {
  const f = fixture({ mode: 'kenBurns', ...options });
  assert.equal(f.scenes[0].photoMotion, expected);
  f.close();
});

test('native finite IDs cover every actual bundled scheduler style', () => {
  const context = vm.createContext({}); context.window = context;
  vm.runInContext(readFileSync(new URL('modes.js', assets), 'utf8'), context);
  const source = readFileSync(new URL('../../app/src/main/java/com/immortal/launcher/SlideshowStyleSamples.kt', import.meta.url), 'utf8');
  const enumSource = source.split('internal enum class SlideshowStyle(val wireId: String) {')[1].split('companion object')[0];
  const nativeIds = [...enumSource.matchAll(/[A-Z_]+\("([^"\n]+)"\)/g)].map(match => match[1]);
  // Gallery print/spread are native stage styles; they are not WebView scheduler modes.
  assert.deepEqual(nativeIds, [...Array.from(context.SS.MODE_IDS), 'galleryPrint', 'gallerySpread']);
});

for (const mode of ids) test(`${mode}: emits only its finite ID after decoded promotion`, async () => {
  const f = fixture({ mode });
  assert.deepEqual(f.reports, []);
  f.scenes[0].finish(true); await f.flush(); assert.deepEqual(f.reports, []);
  await f.tick(16); assert.deepEqual(f.reports, [{ id: mode, at: 16 }]);
  assert.equal(f.elements.get('loading').classList.contains('hide'), true);
  f.close();
});

test('failed incoming scene and a page closed before decode emit no style', async () => {
  for (const closes of [false, true]) {
    const f = fixture(); if (closes) f.close();
    f.scenes[0].finish(!closes ? false : true); await f.tick(20);
    assert.deepEqual(f.reports, []); if (!closes) f.close();
  }
});

test('paused promotion waits for resume before reporting', async () => {
  const f = fixture(); f.context.immortalSlideshow.pause();
  f.scenes[0].finish(true); await f.tick(40); assert.deepEqual(f.reports, []);
  f.context.immortalSlideshow.resume(); await f.tick(16);
  assert.deepEqual(f.reports, [{ id: 'filmstrip', at: 56 }]); f.close();
});

test('a superseded scene cannot report after late decode', async () => {
  const f = fixture(); f.context.immortalSlideshow.next();
  assert.equal(f.scenes.length, 2);
  f.scenes[0].finish(true); await f.tick(20); assert.deepEqual(f.reports, []);
  f.scenes[1].finish(true); await f.tick(16); assert.equal(f.reports.length, 1); f.close();
});

test('missing or throwing optional native bridge preserves scene readiness and future navigation', async () => {
  for (const bridge of ['missing', 'throwing']) {
    const f = fixture({ bridge }); f.scenes[0].finish(true); await f.tick(16);
    assert.equal(f.elements.get('loading').classList.contains('hide'), true);
    assert.equal(f.context.immortalSlideshow.next(), true);
    await f.flush(); assert.equal(f.scenes.length, 2); f.close();
  }
});

test('Paper contrast survives the outgoing Shuffle fade and a pause before retirement', async () => {
  const f = fixture({ mode: 'shuffle', sequence: ['mosaic', 'push'] });
  f.scenes[0].finish(true); await f.tick(16);
  assert.equal(f.surfaces.at(-1)?.enabled, true);
  f.context.immortalSlideshow.next(); f.scenes[1].finish(true); await f.tick(16);
  assert.equal(f.surfaces.at(-1).enabled, true, 'Paper remains visible underneath the incoming mode');
  f.context.immortalSlideshow.pause(); await f.tick(10000);
  assert.equal(f.surfaces.at(-1).enabled, true, 'Paused outgoing Paper retains clock contrast');
  f.context.immortalSlideshow.resume(); await f.tick(1700);
  assert.equal(f.surfaces.at(-1).enabled, false, 'Contrast retires only with outgoing Paper pixels');
  const reports = f.surfaces.length; f.close(); await f.tick(10000);
  assert.equal(f.surfaces.length, reports, 'Closed page cannot change native contrast');
});
