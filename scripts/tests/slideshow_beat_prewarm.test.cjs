/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */
// A beat that dissolves over the current one is built at .001 opacity (.ss-beat.prewarm) so it is
// rasterised before its dissolve, and every beat keeps its own layer (will-change: opacity) so the
// screen is not rasterised again when a dissolve ends (scheduler.js PREWARM_FRAMES, styles.css).
const test = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const vm = require('node:vm');

const assets = path.join(__dirname, '../../app/src/main/assets/screensaver/');
const FRAME = 16;

function fixture() {
  let now = 0, nextTimer = 1;
  const timers = new Map(), scenes = [];
  class Element {
    constructor() { this.children = []; this.className = ''; this.parent = null;
      this.style = { transition: '', opacity: '', setProperty(k, v) { this[k] = v; } }; }
    get classList() { return { contains: name => this.className.split(/\s+/).includes(name),
      add: (...names) => { this.className = [...new Set([...this.className.split(/\s+/), ...names])].join(' ').trim(); },
      remove: (...names) => { this.className = this.className.split(/\s+/).filter(n => !names.includes(n)).join(' '); },
      toggle: (name, on) => { if (on) this.classList.add(name); else this.classList.remove(name); } }; }
    appendChild(child) { this.children.push(child); child.parent = this; return child; }
    remove() { if (this.parent) this.parent.children = this.parent.children.filter(c => c !== this); this.parent = null; }
    get offsetWidth() { return 1200; }
    getAnimations() { return []; }
    set innerHTML(value) { assert.equal(value, ''); this.children = []; }
  }
  const stage = new Element();
  const photos = Array.from({ length: 8 }, (_, id) => ({ id, seed: String(id), url: `fixture-${id}`, w: 1200, h: 800 }));
  const schedule = (fn, ms) => { const id = nextTimer++; timers.set(id, { fn, at: now + Number(ms || 0) }); return id; };
  const context = vm.createContext({ PHOTOS: photos, AbortController, document: { createElement: () => new Element() },
    console: { info() {}, warn() {} }, innerWidth: 1200, innerHeight: 800, screen: { width: 1200, height: 800 },
    getComputedStyle: () => ({}), Date: class extends Date { static now() { return now; } },
    Math: Object.assign(Object.create(Math), { random: () => .5 }),
    setTimeout: schedule, clearTimeout: id => timers.delete(id),
    requestAnimationFrame: fn => schedule(fn, FRAME), cancelAnimationFrame: id => timers.delete(id),
    addEventListener() {}, removeEventListener() {}, shuffled: list => list.slice(), aspect: p => p.w / p.h,
    photoUrl: p => p.url,
  });
  context.window = context;
  for (const file of ['modes.js', 'mode-impls.js', 'scheduler.js']) {
    vm.runInContext(fs.readFileSync(assets + file, 'utf8'), context, { filename: file });
  }
  context.SS.pickNextMode = () => 'kenBurns';
  // Every WebView mode is a fake whose readiness the test resolves.
  for (const id of context.SS.MODE_IDS) if (id !== 'filmstrip') context.SS.MODES[id] = { name: id, run(ctx) {
    let resolve;
    const cleanup = () => { scene.disposed = true; };
    cleanup.ready = new Promise(r => { resolve = r; });
    cleanup.pause = () => {}; cleanup.resume = () => {};
    const scene = { layer: ctx.root, ready: () => resolve(true), disposed: false };
    scenes.push(scene);
    return cleanup;
  } };
  async function flush() { for (let i = 0; i < 60; i++) await Promise.resolve(); }
  async function tick(ms) {
    await flush(); const end = now + ms;
    for (;;) {
      const first = [...timers].filter(([, t]) => t.at <= end).sort((a, b) => a[1].at - b[1].at || a[0] - b[0])[0];
      if (!first) break;
      const [id, t] = first; timers.delete(id); now = t.at; t.fn(); await flush();
    }
    now = end; await flush();
  }
  const engine = context.SS.startEngine({ stage, mode: 'kenBurns', intervalSec: 30 });
  const has = (scene, name) => scene.layer.classList.contains(name);
  return { context, engine, scenes, stage, timers, tick, flush, has };
}

async function firstScene(f) {
  await f.flush(); f.scenes[0].ready(); await f.tick(FRAME);
  assert.equal(f.has(f.scenes[0], 'on'), true);
}

test('the first beat is never prewarmed and shows on the next frame', async () => {
  const f = fixture();
  await f.flush();
  assert.equal(f.has(f.scenes[0], 'prewarm'), false);
  f.scenes[0].ready(); await f.tick(FRAME - 1);
  assert.equal(f.has(f.scenes[0], 'on'), false);
  await f.tick(1);
  assert.equal(f.has(f.scenes[0], 'on'), true);
  f.engine.stop(); assert.equal(f.timers.size, 0);
});

test('a dissolving beat is built prewarmed and starts its dissolve three frames after it is ready', async () => {
  const f = fixture(); await firstScene(f);
  f.engine.next(); await f.flush();
  const incoming = f.scenes[1];
  assert.equal(f.has(incoming, 'prewarm'), true);
  assert.equal(f.has(incoming, 'on'), false);
  await f.tick(500);
  assert.equal(f.has(incoming, 'prewarm'), true, 'stays prewarmed while it is still being laid out');
  incoming.ready();
  await f.tick(FRAME * 3 - 1);
  assert.equal(f.has(incoming, 'on'), false);
  assert.equal(f.has(incoming, 'prewarm'), true);
  await f.tick(1);
  assert.equal(f.has(incoming, 'on'), true);
  assert.equal(f.has(incoming, 'prewarm'), false, 'the dissolve starts from the prewarm opacity');
  assert.equal(f.has(f.scenes[0], 'prewarm'), false);
  f.engine.stop(); assert.equal(f.timers.size, 0);
});

test('a superseded prewarmed beat is disposed and never dissolves', async () => {
  const f = fixture(); await firstScene(f);
  f.engine.next(); await f.flush();
  const superseded = f.scenes[1];
  superseded.ready(); await f.tick(FRAME);
  f.engine.next(); await f.flush();
  assert.equal(superseded.disposed, true);
  assert.equal(superseded.layer.parent, null);
  const latest = f.scenes[2];
  assert.equal(f.has(latest, 'prewarm'), true);
  latest.ready(); await f.tick(FRAME * 3);
  assert.equal(f.has(superseded, 'on'), false);
  assert.equal(f.has(latest, 'on'), true);
  f.engine.stop(); assert.equal(f.timers.size, 0);
});

test('a pause during the prewarm frames holds the dissolve until resume', async () => {
  const f = fixture(); await firstScene(f);
  f.engine.next(); await f.flush();
  const incoming = f.scenes[1];
  incoming.ready(); await f.tick(FRAME);
  f.engine.pause(); await f.tick(FRAME * 10);
  assert.equal(f.has(incoming, 'on'), false);
  f.engine.resume(); await f.tick(FRAME * 3);
  assert.equal(f.has(incoming, 'on'), true);
  f.engine.stop(); assert.equal(f.timers.size, 0);
});

test('beats keep their own layer and the prewarm opacity is below half an 8-bit step', () => {
  const css = fs.readFileSync(assets + 'styles.css', 'utf8');
  const rule = selector => {
    const match = new RegExp(`^${selector.replace(/\./g, '\\.')} \\{([^}]*)\\}`, 'm').exec(css);
    assert.ok(match, selector);
    return { body: match[1], at: match.index };
  };
  const beat = rule('.ss-beat'), prewarm = rule('.ss-beat.prewarm'), on = rule('.ss-beat.on');
  assert.match(beat.body, /will-change: opacity;/);
  assert.match(beat.body, /opacity: 0;/);
  const opacity = Number(/opacity: ([.\d]+);/.exec(prewarm.body)[1]);
  assert.ok(opacity > 0, 'a beat at opacity 0 is not rasterised');
  assert.ok(opacity * 255 < .5, 'the prewarm beat must not change any 8-bit value');
  assert.ok(on.at > prewarm.at, '.on must win over .prewarm when both are present');
});
