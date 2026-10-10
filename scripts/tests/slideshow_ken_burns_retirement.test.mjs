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
const source = process.env.KEN_BURNS_MODE_REF
  ? execFileSync('git', ['show', `${process.env.KEN_BURNS_MODE_REF}:app/src/main/assets/screensaver/mode-impls.js`],
    { cwd: new URL('../../', import.meta.url), encoding: 'utf8' })
  : readFileSync(new URL('mode-impls.js', assets), 'utf8');

// Execute the actual production mode and makeTimers, replacing only DOM, decode,
// and clock boundaries. Computed opacity is input, NOT proof of real CSS rendering.
function fixture({ photoMs = 5000, deferInitial = false } = {}) {
  let now = 0, nextId = 0, deferred = deferInitial;
  const pending = new Map(), nodes = [], loads = [], urls = [], promotions = [], restarts = [];
  function schedule(fn, delay) { const id = ++nextId; pending.set(id, { fn, at: now + delay }); return id; }
  class Element {
    constructor() {
      this.children = []; this.parentNode = null; this.className = ''; this.opacity = '1';
      const values = { animation: '', willChange: '' };
      this.style = { setProperty(k, v) { this[k] = String(v); } };
      for (const k of ['animation', 'willChange']) Object.defineProperty(this.style, k, {
        get: () => values[k], set: v => { values[k] = v; if (k === 'animation') restarts.push(v); }, enumerable: true,
      });
      nodes.push(this);
    }
    get classList() { return {
      contains: c => this.className.split(/\s+/).includes(c),
      add: c => {
        if (!this.classList.contains(c)) this.className += ' ' + c;
        if (c === 'on') promotions.push({ root: this, animation: this.children[1].style.animation,
          motion: this.children[1].classList.contains('ss-motion'), hint: this.children[1].style.willChange,
          background: this.children[1].style.backgroundImage });
      },
      remove: c => { this.className = this.className.split(/\s+/).filter(x => x !== c).join(' '); },
    }; }
    appendChild(child) { child.parentNode = this; this.children.push(child); return child; }
    get offsetWidth() { restarts.push('layout'); return 800; }
  }
  const window = {
    aspect: p => p.w / p.h,
    preloadDecoded: (url, signal) => {
      const load = { url, signal }; loads.push(load);
      return deferred ? new Promise(resolve => { load.resolve = resolve; }) : Promise.resolve(url);
    },
  };
  const context = vm.createContext({ window, document: { createElement: () => new Element() }, AbortController,
    getComputedStyle: n => { if (n.opacity instanceof Error) throw n.opacity; return { opacity: n.opacity }; },
    setTimeout: schedule, clearTimeout: id => pending.delete(id),
    requestAnimationFrame: fn => schedule(fn, 16), cancelAnimationFrame: id => pending.delete(id),
    Date: class extends Date { static now() { return now; } },
    Math: Object.assign(Object.create(Math), { random: () => .7 }),
  });
  vm.runInContext(readFileSync(new URL('modes.js', assets), 'utf8'), context);
  vm.runInContext(source, context);
  const root = new Element();
  const deck = [
    { seed: 0, w: 160, h: 90, fx: .7, fy: .25 },
    { seed: 1, w: 90, h: 160 },
    { seed: 2, w: 160, h: 90 },
    { seed: 3, w: 90, h: 160, fx: .3, fy: .6 },
  ];
  const cleanup = window.SS.MODES.kenBurns.run({ root, cursor: { i: 0 }, W: 800, H: 600, photoMs,
    url: (p, edge) => { const url = `prepared:${p.seed}:${urls.length}`; urls.push({ url, edge }); return url; },
  }, deck);
  async function flush() { for (let i = 0; i < 30; i++) await Promise.resolve(); }
  async function advanceTo(to) {
    for (;;) {
      const next = [...pending].filter(([, task]) => task.at <= to).sort((a, b) => a[1].at - b[1].at || a[0] - b[0])[0];
      if (!next) break;
      now = next[1].at; pending.delete(next[0]); next[1].fn(); await flush();
    }
    now = to; await flush();
  }
  return { root, cleanup, pending, nodes, loads, urls, promotions, restarts, flush, advanceTo,
    defer: () => { deferred = true; }, resolve: () => loads.at(-1).resolve(),
    end: (slot, overrides = {}) => slot.ontransitionend?.({ target: slot, propertyName: 'opacity', ...overrides }),
  };
}
function image(slot) { return slot.children[1].style.backgroundImage; }
function retained(slot) { assert.match(image(slot), /^url\(/, 'Outgoing image must remain retained'); }
function retired(slot) {
  assert.equal(slot.children[0].style.backgroundImage, 'none', 'Obsolete blur-fill background must retire');
  assert.equal(image(slot), 'none', 'Obsolete hero background must retire');
  assert.equal(slot.children[1].style.animation, 'none');
  assert.equal(slot.children[1].classList.contains('ss-motion'), false);
  assert.equal(slot.children[1].style.willChange, 'auto');
  assert.equal(slot.ontransitionend == null, true);
}
async function swapped(options) {
  const r = fixture(options); assert.equal(await r.cleanup.ready, true);
  await r.advanceTo(options?.photoMs ?? 5000);
  const [current, old] = r.root.children;
  retained(old); retained(current);
  assert.equal(old.classList.contains('on'), false); assert.equal(current.classList.contains('on'), true);
  return { r, old, current };
}

// Missing retirement retains both obsolete image resources and compositor work.
test('exact zero completion releases only the outgoing slot resources and cancels fallback', async () => {
  const { r, old, current } = await swapped();
  try {
    old.opacity = '0'; r.end(old); retired(old); retained(current);
    assert.equal(r.pending.size, 1, 'Only the repeating slideshow timer remains');
    r.cleanup.pause(); r.cleanup.resume(); assert.equal(r.pending.size, 1, 'No ghost fallback on resume');
  } finally { r.cleanup(); }
});

// Bubbled, unrelated, near-zero or unknown opacity cannot remove visible content.
test('own root/property and strict numeric zero guard both event and fallback', async () => {
  const { r, old, current } = await swapped();
  try {
    old.opacity = '0';
    for (const overrides of [{ target: old.children[1] }, { target: current }, { propertyName: 'transform' }]) {
      r.end(old, overrides); retained(old);
    }
    for (const opacity of ['1', '.4', '0.000001', '1e-100', '1e-999', '-1e-999', '', ' ', null, undefined, 0,
      '0x0', '0px', '0junk', 'NaN', 'Infinity', new Error('unavailable')]) {
      old.opacity = opacity; r.end(old); retained(old);
    }
    old.opacity = ''; await r.advanceTo(7200); retained(old);
    old.opacity = '.000001'; await r.advanceTo(9400); retained(old);
    current.opacity = '0'; r.end(current); retained(current);
    old.opacity = '0.0'; r.end(old); retired(old); retained(current);
  } finally { r.cleanup(); }
});

test('missing event fallback waits for exact computed zero through a delayed fade', async () => {
  const { r, old, current } = await swapped({ photoMs: 10000 });
  try {
    old.opacity = '.25'; await r.advanceTo(12200); retained(old);
    assert.equal(r.pending.size, 2, 'One repeating timer and one owned fallback');
    old.opacity = '0'; await r.advanceTo(14399); retained(old);
    await r.advanceTo(14400); retired(old); retained(current); assert.equal(r.pending.size, 1);
  } finally { r.cleanup(); }
});

test('missing event fallback retires at completion without changing live resources', async () => {
  const { r, old, current } = await swapped();
  try {
    old.opacity = '0'; await r.advanceTo(7199); retained(old);
    await r.advanceTo(7200); retired(old); retained(current); assert.equal(r.pending.size, 1);
  } finally { r.cleanup(); }
});

// Captured old handlers/tasks must not cancel or release the newer generation.
test('reuse rejects captured stale event and fallback even during a newer outgoing fade', async () => {
  const { r, old } = await swapped({ photoMs: 1000 });
  try {
    const staleEvent = old.ontransitionend;
    const staleTimer = [...r.pending.values()].find(task => task.at === 3200)?.fn;
    assert.equal(typeof staleEvent, 'function'); assert.equal(typeof staleTimer, 'function');
    await r.advanceTo(2000); old.opacity = '0';
    const liveImage = image(old);
    staleEvent({ target: old, propertyName: 'opacity' }); staleTimer(); await r.flush();
    assert.equal(image(old), liveImage); assert.equal(old.classList.contains('on'), true);
    await r.advanceTo(3000); old.opacity = '.2';
    const newerEvent = old.ontransitionend, pendingBefore = r.pending.size;
    staleEvent({ target: old, propertyName: 'opacity' }); staleTimer(); await r.flush();
    assert.equal(old.ontransitionend, newerEvent); assert.equal(r.pending.size, pendingBefore); retained(old);
    // Opacity alone must not let an older generation release the newer one.
    old.opacity = '0';
    staleEvent({ target: old, propertyName: 'opacity' }); staleTimer(); await r.flush();
    retained(old); assert.equal(old.ontransitionend, newerEvent); assert.equal(r.pending.size, pendingBefore);
    newerEvent({ target: old, propertyName: 'opacity' }); retired(old);
  } finally { r.cleanup(); }
});

// Restoration must precede promotion and preserve original animation restart.
test('face and non-face retired slot reuse restores animation and hint before promotion', async () => {
  const { r, old } = await swapped();
  try {
    old.opacity = '0'; r.end(old); retired(old);
    await r.advanceTo(10000);
    const live = r.promotions.at(-1);
    assert.equal(live.root, old); assert.equal(live.animation, ''); assert.equal(live.motion, true);
    assert.notEqual(live.hint, 'auto'); assert.match(live.background, /^url\(/);
    assert.deepEqual(r.restarts.slice(-3), ['none', 'layout', '']);
    assert.equal(old.children[1].style['--s0'], '1.04'); assert.equal(old.children[1].style['--s1'], '1.15');
    const other = r.root.children[0]; other.opacity = '0'; r.end(other); retired(other);
    await r.advanceTo(15000);
    assert.equal(r.promotions.at(-1).root, other);
    assert.equal(other.children[1].style['--s0'], '1'); assert.equal(other.children[1].style['--s1'], '1');
    for (const key of ['--x0', '--y0', '--x1', '--y1']) assert.equal(other.children[1].style[key], '0%');
    assert.deepEqual(r.restarts.slice(-3), ['none', 'layout', '']);
    assert.equal(r.urls.length, r.loads.length, 'Source URL is prepared once per admission');
    assert.ok(r.urls.every(u => u.edge === 2200));
    assert.equal(image(other), `url("${r.loads.at(-1).url}")`);
  } finally { r.cleanup(); }
});

test('global pause holds timers and visible content; resume retains fallback remaining time', async () => {
  const { r, old, current } = await swapped();
  try {
    await r.advanceTo(5700); old.opacity = '.3'; r.cleanup.pause();
    assert.equal(r.pending.size, 0); await r.advanceTo(20000); r.end(old); retained(old); retained(current);
    r.cleanup.resume(); old.opacity = '0'; await r.advanceTo(21499); retained(old);
    await r.advanceTo(21500); retired(old); retained(current);
  } finally { r.cleanup(); }
});

test('paused completion at own zero may retire while the visible slot remains intact', async () => {
  const { r, old, current } = await swapped();
  try {
    r.cleanup.pause(); old.opacity = '.4'; r.end(old); retained(old);
    old.opacity = '0'; r.end(old); retired(old); retained(current);
    r.cleanup.resume(); assert.equal(r.pending.size, 1);
  } finally { r.cleanup(); }
});

// Timers/listeners must be released without mutating scene resources after disposal.
test('cleanup is idempotent and captured callbacks cannot mutate dead slots or rearm work', async () => {
  const { r, old } = await swapped();
  const event = old.ontransitionend, callbacks = [...r.pending.values()].map(task => task.fn);
  const before = image(old); r.cleanup.pause(); r.cleanup(); r.cleanup();
  assert.equal(r.pending.size, 0); assert.ok(r.nodes.every(n => n.ontransitionend == null));
  old.opacity = '0'; event?.({ target: old, propertyName: 'opacity' });
  for (const fn of callbacks) { fn(); await r.flush(); }
  r.cleanup.resume(); await r.advanceTo(60000);
  assert.equal(r.pending.size, 0); assert.equal(image(old), before);
  assert.ok(r.loads.every(load => load.signal.aborted));
});

test('initial decode cancellation prevents readiness, publication and retirement work', async () => {
  const r = fixture({ deferInitial: true }); await r.flush(); r.cleanup(); r.resolve();
  assert.equal(await r.cleanup.ready, false); assert.equal(r.pending.size, 0);
  assert.equal(r.promotions.length, 0); assert.ok(r.root.children.every(slot => !image(slot)));
  assert.ok(r.loads.every(load => load.signal.aborted));
});

test('inflight decode waits while paused and publishes exactly once on resume', async () => {
  const r = fixture(); await r.cleanup.ready;
  try {
    r.defer(); await r.advanceTo(5000); r.cleanup.pause(); r.resolve(); await r.flush();
    assert.equal(r.promotions.length, 1); r.cleanup.resume(); await r.flush();
    assert.equal(r.promotions.length, 2);
    // Shared makeTimers can rearm a repeat while an inflight run resumes; assert
    // the candidate owns/cancels only its retirement work, not that shared count.
    const before = r.pending.size, old = r.root.children[1];
    old.opacity = '0'; r.end(old); retired(old);
    assert.equal(r.pending.size, before - 1);
  } finally { r.cleanup(); }
});

test('superseded scene decode cannot publish after paused disposal', async () => {
  const r = fixture(); await r.cleanup.ready;
  const before = r.root.children.map(image);
  r.defer(); await r.advanceTo(5000); r.cleanup.pause(); r.resolve(); await r.flush();
  assert.deepEqual(r.root.children.map(image), before);
  r.cleanup(); r.cleanup.resume(); await r.flush();
  assert.deepEqual(r.root.children.map(image), before); assert.equal(r.pending.size, 0);
  assert.equal(r.promotions.length, 1); assert.ok(r.loads.every(load => load.signal.aborted));
});

// Root ownership is separate from transparency: a moved slot is no longer ours.
test('a slot detached from its owning scene cannot retire through a captured callback', async () => {
  const { r, old } = await swapped();
  try {
    const handler = old.ontransitionend;
    assert.equal(typeof handler, 'function', 'Outgoing retirement must own a handler');
    old.parentNode = null; old.opacity = '0';
    handler({ target: old, propertyName: 'opacity' }); retained(old);
    await r.advanceTo(7200); retained(old);
    assert.equal(r.pending.size, 1, 'Detached work must not rearm a fallback');
  } finally { r.cleanup(); }
});
