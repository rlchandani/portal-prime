/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */
import test from 'node:test';
import assert from 'node:assert/strict';
import { readFileSync } from 'node:fs';
import { execFileSync } from 'node:child_process';
import vm from 'node:vm';

const assets = new URL('../../app/src/main/assets/screensaver/', import.meta.url);
const modeSource = process.env.GALLERY_MODE_REF
  ? execFileSync('git', ['show', `${process.env.GALLERY_MODE_REF}:app/src/main/assets/screensaver/mode-impls.js`],
    { encoding: 'utf8', cwd: new URL('../../', import.meta.url) })
  : readFileSync(new URL('mode-impls.js', assets), 'utf8');

// Real mode code with controlled DOM/timer boundaries. Computed opacity is an input
// here, not a CSS/raster emulator; the separate Chromium probe checks actual pixels.
function fixture({ microMs = 5000, deferInitial = false } = {}) {
  let now = 0, id = 0, initialResolve, nextResolve, deferNext = false;
  const pending = new Map(), allNodes = [];
  function schedule(fn, delay) {
    const key = ++id;
    pending.set(key, { fn, at: now + delay });
    return key;
  }
  class Element {
    constructor() {
      this.children = []; this.parentNode = null; this.className = '';
      this.style = { setProperty(k, v) { this[k] = v; } };
      this.opacity = '1'; allNodes.push(this);
    }
    get classList() {
      return {
        contains: x => this.className.split(/\s+/).includes(x),
        add: x => { this.className += ' ' + x; },
        remove: x => { this.className = this.className.split(/\s+/).filter(c => c !== x).join(' '); },
      };
    }
    appendChild(child) { child.parentNode = this; this.children.push(child); return child; }
    remove() {
      if (this.parentNode) this.parentNode.children = this.parentNode.children.filter(c => c !== this);
      this.parentNode = null;
    }
    dispatchEvent(event) { this.ontransitionend?.(event); }
  }
  const initial = deferInitial ? new Promise(resolve => { initialResolve = resolve; }) : Promise.resolve();
  const window = {
    aspect: p => p.w / p.h,
    preloadDecoded: () => deferNext ? new Promise(resolve => { nextResolve = resolve; }) : initial,
    justifiedLayout: pick => ({ height: 100, cells: pick.slice(0, 2).map((photo, i) =>
      ({ left: i * 100, top: 0, width: 100, height: 100, photo })) }),
  };
  const context = vm.createContext({
    window, document: { createElement: () => new Element() }, AbortController,
    getComputedStyle: node => ({ opacity: node.opacity }),
    setTimeout: schedule, clearTimeout: key => pending.delete(key),
    requestAnimationFrame: fn => schedule(fn, 16), cancelAnimationFrame: key => pending.delete(key),
    Date: class extends Date { static now() { return now; } },
    Math: Object.assign(Object.create(Math), { random: () => .5 }),
  });
  for (const file of ['modes.js', 'mode-impls.js']) {
    vm.runInContext(file === 'mode-impls.js' ? modeSource : readFileSync(new URL(file, assets), 'utf8'),
      context, { filename: file });
  }
  const root = new Element(), cursor = { i: 0 };
  const deck = Array.from({ length: 16 }, (_, seed) => ({ seed: 'generated-' + seed, w: 400, h: 300 }));
  const cleanup = window.SS.MODES.galleryWall.run({
    root, cursor, W: 800, H: 600, microMs, url: p => 'generated://' + p.seed,
  }, deck);
  async function flush() { for (let i = 0; i < 30; i++) await Promise.resolve(); }
  async function advanceTo(time) {
    assert.ok(time >= now);
    while (true) {
      const entry = [...pending].sort((a, b) => a[1].at - b[1].at || a[0] - b[0])[0];
      if (!entry || entry[1].at > time) break;
      const [key, task] = entry; pending.delete(key); now = task.at;
      task.fn(); await flush();
    }
    now = time; await flush();
  }
  function cells() { return root.children[0].children; }
  function images() { return cells().flatMap(c => c.children); }
  function end(node, { target = node, propertyName = 'opacity' } = {}) {
    node.dispatchEvent({ type: 'transitionend', target, propertyName });
  }
  return { cleanup, root, cursor, pending, allNodes, cells, images, end, advanceTo, flush,
    deferNext: () => { deferNext = true; },
    finishNext: () => { deferNext = false; nextResolve(); },
    finishInitial: () => initialResolve(),
  };
}

async function swapped(options) {
  const r = fixture(options);
  await r.cleanup.ready; await r.advanceTo(options?.microMs ?? 5000);
  const cell = r.cells().find(c => c.children.length === 2);
  assert.ok(cell, 'Real Gallery Wall must publish a replacement');
  const [old, current] = cell.children;
  await r.advanceTo((options?.microMs ?? 5000) + 16);
  assert.equal(old.style.opacity, '0');
  return { r, cell, old, current };
}

// Missing transition retirement leaves a fully faded image attached after promotion pause.
test('Gallery Wall retires a zero-opacity obsolete image when pause straddles its fade', async () => {
  const { r, old, current, cell } = await swapped();
  try {
    old.opacity = '.4'; await r.advanceTo(5700); r.cleanup.pause();
    assert.equal(r.pending.size, 0);
    r.end(old); assert.equal(old.parentNode, cell, 'Visible image cannot retire');
    await r.advanceTo(6100); old.opacity = '0'; r.end(old);
    assert.equal(old.parentNode, null, 'Completed invisible image must retire while removal timer is paused');
    assert.equal(current.parentNode, cell);
  } finally { r.cleanup(); }
});

// Accepting bubbled/unrelated/stale completion or rounding near-zero opacity removes visible content.
test('retirement requires the exact obsolete target, opacity property, and computed zero', async () => {
  const { r, old, current, cell } = await swapped();
  try {
    r.cleanup.pause();
    old.opacity = '0';
    r.end(old, { target: current }); assert.equal(old.parentNode, cell);
    r.end(old, { propertyName: 'transform' }); assert.equal(old.parentNode, cell);
    old.opacity = '.000001'; r.end(old); assert.equal(old.parentNode, cell);
    current.opacity = '0'; r.end(current); assert.equal(current.parentNode, cell);
    old.opacity = '0'; r.end(old); assert.equal(old.parentNode, null);
    assert.equal(old.ontransitionend == null, true, 'Completed retirement releases its listener');
    assert.equal(current.parentNode, cell);
  } finally { r.cleanup(); }
});

// Timer-only engines retain the existing 1200ms fallback, with the same visibility guard.
test('the existing fallback retires only computed-zero obsolete images', async () => {
  const { r, old, cell } = await swapped();
  try {
    old.opacity = '.5';
    await r.advanceTo(6200);
    assert.equal(old.parentNode, cell, 'A delayed CSS fade must not be cut short by the fallback');
    old.opacity = '0'; r.end(old);
    assert.equal(old.parentNode, null);
  } finally { r.cleanup(); }
});

test('the fallback removes completed invisible images without a transition event', async () => {
  const { r, old } = await swapped();
  try {
    old.opacity = '0'; await r.advanceTo(6199); assert.notEqual(old.parentNode, null);
    await r.advanceTo(6200); assert.equal(old.parentNode, null);
    assert.equal(old.ontransitionend == null, true);
  } finally { r.cleanup(); }
});

// Two replacements of the same cell may finish independently; neither can delete the latest image.
test('superseded replacements retire independently without touching the current image', async () => {
  const { r, old, current: middle, cell } = await swapped({ microMs: 1000 });
  try {
    await r.advanceTo(2016);
    const current = cell.children.at(-1);
    assert.notEqual(current, middle);
    old.opacity = '0'; middle.opacity = '.2';
    const stale = old.ontransitionend;
    r.end(old); assert.equal(old.parentNode, null);
    assert.equal(middle.parentNode, cell); assert.equal(current.parentNode, cell);
    stale?.({ target: current, propertyName: 'opacity' });
    assert.equal(current.parentNode, cell);
    middle.opacity = '0'; r.end(middle); assert.equal(middle.parentNode, null);
    assert.deepEqual(cell.children, [current]);
  } finally { r.cleanup(); }
});

// Shared pause/resume must keep the fallback's remaining delay and never rearm dead work.
test('global pause/resume retains fallback timing and visible images', async () => {
  const { r, old, cell } = await swapped();
  try {
    await r.advanceTo(5700); old.opacity = '.3'; r.cleanup.pause();
    await r.advanceTo(20000); r.end(old); assert.equal(old.parentNode, cell);
    r.cleanup.resume();
    await r.advanceTo(20499); old.opacity = '0'; assert.equal(old.parentNode, cell);
    await r.advanceTo(20500); assert.equal(old.parentNode, null);
  } finally { r.cleanup(); }
});

// Disposal must release handlers and shared timers, including callbacks captured before disposal.
test('cleanup releases all retirement handlers/timers and rejects delayed callbacks', async () => {
  const { r, old, cell } = await swapped();
  const callbacks = [...r.pending.values()].map(t => t.fn), event = old.ontransitionend;
  r.cleanup.pause(); r.cleanup(); r.cleanup();
  assert.equal(r.pending.size, 0);
  for (const node of r.allNodes) assert.equal(node.ontransitionend == null, true);
  old.opacity = '0'; event?.({ target: old, propertyName: 'opacity' });
  for (const fn of callbacks) { fn(); await r.flush(); }
  r.cleanup.resume(); await r.advanceTo(60000);
  assert.equal(r.pending.size, 0);
  assert.equal(old.parentNode, cell, 'Disposed code cannot mutate the detached scene');
});

test('cleanup during initial decode prevents publication and timer creation', async () => {
  const r = fixture({ deferInitial: true });
  await r.flush(); r.cleanup(); r.finishInitial();
  assert.equal(await r.cleanup.ready, false);
  assert.equal(r.images().length, 0); assert.equal(r.pending.size, 0);
});

test('cleanup during a paused replacement decode prevents late publication', async () => {
  const r = fixture(); await r.cleanup.ready;
  const before = r.images().slice();
  r.deferNext(); await r.advanceTo(5000); r.cleanup.pause(); r.finishNext();
  await r.flush(); assert.deepEqual(r.images(), before);
  r.cleanup(); r.cleanup.resume(); await r.flush();
  assert.deepEqual(r.images(), before); assert.equal(r.pending.size, 0);
});
