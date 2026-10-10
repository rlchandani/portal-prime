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
const css = readFileSync(new URL('styles.css', assets), 'utf8');
// Evaluate the real class/descendant declarations used by Framed Print. This fixture does
// not emulate rasterization; it checks the mode's DOM lifecycle and resulting CSS inputs.
const rules = [...css.matchAll(/([^{}]+)\{([^{}]*)\}/g)].map(([, selectors, declarations]) => ({
  selectors: selectors.replace(/\/\*[\s\S]*?\*\//g, '').trim().split(','),
  declarations: Object.fromEntries(declarations.split(';').map(part => {
    const colon = part.indexOf(':');
    return colon < 0 ? [] : [part.slice(0, colon).trim(), part.slice(colon + 1).trim()];
  }).filter(pair => pair.length)),
}));

function fixture({ photoMs = 5000, deferLoad = false } = {}) {
  let now = 0, nextId = 1, finishLoad;
  const pending = new Map();
  function schedule(fn, ms) {
    const id = nextId++;
    pending.set(id, { fn, at: now + ms });
    return id;
  }
  function simpleMatch(node, selector) {
    if (selector === '*') return true;
    if (!/^\.[\w.-]+$/.test(selector)) return false;
    return selector.slice(1).split('.').every(name => node.classList.contains(name));
  }
  function matches(node, selector) {
    const parts = selector.trim().split(/\s+/);
    if (!simpleMatch(node, parts.pop())) return false;
    let ancestor = node.parent;
    while (parts.length) {
      const part = parts.pop();
      while (ancestor && !simpleMatch(ancestor, part)) ancestor = ancestor.parent;
      if (!ancestor) return false;
      ancestor = ancestor.parent;
    }
    return true;
  }
  function declarations(node) {
    const result = {};
    for (const rule of rules) {
      if (rule.selectors.some(selector => matches(node, selector))) Object.assign(result, rule.declarations);
    }
    return result;
  }
  class Element {
    constructor() { this.children = []; this.style = {}; this.names = ''; }
    get className() { return this.names; }
    set className(value) {
      const before = this.opacity(), oldTarget = declarations(this).opacity;
      this.names = value;
      const current = declarations(this), target = current.opacity;
      if (oldTarget !== target && this.parent) {
        const duration = /opacity\s+([\d.]+)(ms|s)/.exec(current.transition || '');
        this.fade = { from: before, to: Number(target ?? 1), start: now,
          duration: duration ? Number(duration[1]) * (duration[2] === 's' ? 1000 : 1) : 0 };
      }
    }
    get classList() {
      return {
        contains: name => this.names.split(/\s+/).includes(name),
        add: (...names) => { this.className = [...new Set([...this.names.split(/\s+/), ...names])].join(' ').trim(); },
        remove: (...names) => { this.className = this.names.split(/\s+/).filter(name => !names.includes(name)).join(' '); },
      };
    }
    opacity() {
      if (!this.fade) return Number(declarations(this).opacity ?? 1);
      const progress = this.fade.duration ? Math.min(1, (now - this.fade.start) / this.fade.duration) : 1;
      return this.fade.from + (this.fade.to - this.fade.from) * progress;
    }
    appendChild(child) { child.parent = this; this.children.push(child); return child; }
    dispatchEvent(event) { this['on' + event.type]?.(event); }
  }
  const load = deferLoad ? new Promise(resolve => { finishLoad = resolve; }) : Promise.resolve(true);
  const window = { aspect: photo => photo.w / photo.h, preloadDecoded: () => load };
  const context = vm.createContext({ window, document: { createElement: () => new Element() }, AbortController,
    getComputedStyle: node => ({ ...declarations(node), opacity: String(node.opacity()) }),
    setTimeout: schedule, clearTimeout: id => pending.delete(id),
    requestAnimationFrame: fn => schedule(fn, 16), cancelAnimationFrame: id => pending.delete(id),
    Date: class extends Date { static now() { return now; } },
    Math: Object.assign(Object.create(Math), { random: () => .5 }),
  });
  for (const file of ['modes.js', 'mode-impls.js']) {
    vm.runInContext(readFileSync(new URL(file, assets), 'utf8'), context, { filename: file });
  }
  const root = new Element();
  const deck = Array.from({ length: 8 }, (_, i) => ({ seed: 'generated-' + i, w: 1600, h: 1000 }));
  const cleanup = window.SS.MODES.framed.run({ root, cursor: { i: 0 }, W: 2160, H: 1440,
    photoMs, url: photo => 'generated://' + photo.seed }, deck);
  function nodes(node = root) { return [node, ...node.children.flatMap(child => nodes(child))]; }
  function prints() { return nodes().filter(node => node.classList.contains('ss-print')); }
  function slot(print) { return print.parent.parent.parent.parent; }
  function state(print) {
    const style = declarations(print), animation = style.animation || 'none';
    return { animation, promotion: style['will-change'] || 'auto', playState: style['animation-play-state'] || 'running',
      visible: slot(print).classList.contains('on'), opacity: slot(print).opacity() };
  }
  async function flush() { for (let i = 0; i < 12; i++) await Promise.resolve(); }
  async function advanceTo(time) {
    assert.ok(time >= now);
    while (true) {
      const entry = [...pending].sort((a, b) => a[1].at - b[1].at || a[0] - b[0])[0];
      if (!entry || entry[1].at > time) break;
      const [id, task] = entry;
      pending.delete(id); now = task.at;
      await task.fn(); await flush();
    }
    now = time; await flush();
  }
  function end(print, { propertyName = 'opacity', target = slot(print) } = {}) {
    slot(print).dispatchEvent({ type: 'transitionend', propertyName, target });
  }
  return { cleanup, prints, state, slot, nodes, pending, advanceTo, end,
    elapseTo: time => { assert.ok(time >= now); now = time; },
    finishLoad: () => finishLoad(true), flush };
}

function retired(r, print) {
  assert.match(r.state(print).animation, /^breathe 22s linear infinite alternate$/, 'Retirement must retain the breathe animation declaration');
  assert.equal(r.state(print).promotion, 'auto', 'An invisible slot must release its promotion hint');
  assert.equal(r.state(print).playState, 'paused');
}
function breathing(r, print) {
  assert.match(r.state(print).animation, /^breathe 22s linear infinite alternate$/);
  assert.equal(r.state(print).promotion, 'transform');
  assert.equal(r.state(print).playState, 'running');
}

// Catches retiring on fade start, failing to revive a reused slot, or changing cadence.
test('Framed Print breathes throughout fades, retires hidden slots, and revives them over eight swaps', async () => {
  const r = fixture();
  try {
    assert.equal(await r.cleanup.ready, true);
    const first = r.prints().find(print => r.state(print).visible);
    breathing(r, first);
    retired(r, r.prints().find(print => print !== first));
    for (let swap = 1; swap <= 8; swap++) {
      await r.advanceTo(swap * 5000);
      const incoming = r.prints().find(print => r.state(print).visible);
      const outgoing = r.prints().find(print => print !== incoming);
      assert.equal(incoming.style.backgroundImage, `url("generated://generated-${swap % 8}")`);
      breathing(r, incoming); breathing(r, outgoing);
      await r.advanceTo(swap * 5000 + 2399);
      assert.ok(r.state(outgoing).opacity > 0);
      breathing(r, outgoing);
      await r.advanceTo(swap * 5000 + 2400);
      assert.equal(r.state(outgoing).opacity, 0);
      retired(r, outgoing); breathing(r, incoming);
    }
  } finally { r.cleanup(); }
});

// Catches bubbled/unrelated/stale events retiring a currently visible or fading slot.
test('only completion of the slot opacity fade may retire its print', async () => {
  const r = fixture();
  try {
    await r.cleanup.ready;
    await r.advanceTo(5000);
    const incoming = r.prints().find(print => r.state(print).visible);
    const outgoing = r.prints().find(print => print !== incoming);
    r.end(incoming); r.end(outgoing); breathing(r, incoming); breathing(r, outgoing);
    // Complete the simulated CSS transition before dispatching the owned fallback timer.
    r.elapseTo(7400);
    r.end(outgoing, { propertyName: 'transform' }); breathing(r, outgoing);
    r.end(outgoing, { target: outgoing }); breathing(r, outgoing);
    r.end(outgoing); retired(r, outgoing); breathing(r, incoming);
  } finally { r.cleanup(); }
});

// Catches delayed retirement from an earlier use overriding a slot's newer fade/reveal.
test('rapid slot reuse invalidates old retirement callbacks', async () => {
  const r = fixture({ photoMs: 1000 });
  try {
    await r.cleanup.ready;
    for (const time of [1000, 2000, 2400, 3000, 3400, 4000, 4400, 5000]) {
      await r.advanceTo(time);
      for (const print of r.prints()) breathing(r, print);
    }
  } finally { r.cleanup(); }
});

// Catches losing the shared pause/resume protocol when adding mode-specific cleanup.
test('pause and resume preserve the outstanding retirement timer remaining time', async () => {
  const r = fixture();
  try {
    await r.cleanup.ready;
    await r.advanceTo(6000);
    const outgoing = r.prints().find(print => !r.state(print).visible);
    r.cleanup.pause();
    assert.equal(r.pending.size, 0);
    await r.advanceTo(20000);
    r.cleanup.resume();
    await r.advanceTo(21399); breathing(r, outgoing);
    await r.advanceTo(21400); retired(r, outgoing);
  } finally { r.cleanup(); }
});

// Catches leaked event listeners, scheduled work, and mutation after disposal.
test('destruction cancels retirement and repeat callbacks and stops both prints', async () => {
  const r = fixture();
  await r.cleanup.ready;
  await r.advanceTo(5000);
  const capturedCallbacks = [...r.pending.values()].map(task => task.fn);
  r.cleanup(); r.cleanup();
  assert.equal(r.pending.size, 0);
  for (const print of r.prints()) retired(r, print);
  for (const node of r.nodes()) {
    assert.equal(node.ontransitionend == null, true, 'Disposal releases each owned transition handler');
  }
  const snapshot = () => r.prints().map(print => JSON.stringify({ style: print.style,
    slot: r.slot(print).className, animation: r.state(print).animation,
    promotion: r.state(print).promotion, playState: r.state(print).playState }));
  const before = snapshot();
  for (const fn of capturedCallbacks) await fn();
  await r.advanceTo(60000);
  for (const print of r.prints()) r.end(print);
  assert.equal(r.pending.size, 0);
  assert.deepEqual(snapshot(), before);
});

// Catches an initial decode publishing or rearming after the mode has been destroyed.
test('destruction during preparation prevents late publication', async () => {
  const r = fixture({ deferLoad: true });
  await r.flush();
  r.cleanup(); r.finishLoad();
  assert.equal(await r.cleanup.ready, false);
  await r.advanceTo(60000);
  assert.equal(r.pending.size, 0);
  for (const print of r.prints()) {
    assert.equal(print.style.backgroundImage, undefined);
    retired(r, print);
  }
});
