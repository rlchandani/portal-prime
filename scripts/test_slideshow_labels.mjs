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

// Exercise the actual mode rendering/timers, using an owned DOM and deterministic virtual time.
function renderer() {
  let now = 0, next = 1;
  const pending = new Map();
  function schedule(fn, ms) { const id = next++; pending.set(id, { fn, at: now + ms }); return id; }
  class Element {
    constructor() { this.children = []; this.style = { setProperty() {} }; this.textContent = ''; this.className = ''; }
    get classList() { return { contains: name => this.className.split(' ').includes(name), add: (...names) => { this.className = [...new Set([...this.className.split(' '), ...names])].join(' '); }, remove: (...names) => { this.className = this.className.split(' ').filter(n => !names.includes(n)).join(' '); }, toggle: (name, on) => { if (on) this.classList.add(name); else this.classList.remove(name); } }; }
    appendChild(child) { child.remove(); child.parent = this; this.children.push(child); return child; }
    remove() { if (this.parent) this.parent.children = this.parent.children.filter(c => c !== this); this.parent = null; }
    set innerHTML(_) { for (const child of [...this.children]) child.remove(); }
    get offsetWidth() { return 0; }
  }
  const window = { aspect: p => p.w / p.h, shuffled: a => a.slice(), preloadDecoded: async () => true };
  const context = vm.createContext({ window, document: { createElement: () => new Element() }, AbortController,
    getComputedStyle: node => ({ opacity: node.style.opacity || (node.classList.contains('on') ? '1' : '0') }),
    setTimeout: schedule, clearTimeout: id => pending.delete(id), requestAnimationFrame: fn => schedule(fn, 16),
    cancelAnimationFrame: id => pending.delete(id), Date: class extends Date { static now() { return now; } } });
  for (const file of ['modes.js', 'mode-impls.js']) vm.runInContext(readFileSync(new URL('../app/src/main/assets/screensaver/' + file, import.meta.url), 'utf8'), context);
  const root = new Element();
  const deck = Array.from({ length: 8 }, (_, i) => ({ seed: 'owned-' + i, url: 'owned-' + i, w: 1600, h: 1000 }));
  const ctx = { root, cursor: { i: 0 }, W: 2160, H: 1440, photoMs: 1000, microMs: 1000,
    motionMs: 32400, ambientMs: 3500, url: p => p.url };
  function elements(node = root) { return [node, ...node.children.flatMap(c => elements(c))]; }
  async function tick() {
    const [id, task] = [...pending].sort((a, b) => a[1].at - b[1].at || a[0] - b[0])[0] || [];
    assert.ok(task, 'An owned mode timer must be scheduled');
    pending.delete(id); now = task.at; await task.fn(); await Promise.resolve();
  }
  return { run: mode => window.SS.MODES[mode].run(ctx, deck), elements, tick, pending };
}

test('Polaroid photos never receive invented captions, including later drops', async () => {
  const r = renderer(), cleanup = r.run('polaroid');
  try {
    assert.equal(await cleanup.ready, true);
    for (let n = 0; n < 30; n++) {
      assert.ok(r.elements().some(e => e.className.includes('ss-ph')), 'Photos remain in the composition');
      assert.deepEqual(r.elements().map(e => e.textContent).filter(Boolean), [], 'Missing metadata must not become a guessed event, place or date');
      await r.tick();
    }
  } finally { cleanup(); assert.equal(r.pending.size, 0); }
});

test('Memories collages use a neutral heading without fabricated dates or travel claims', async () => {
  const r = renderer(), cleanup = r.run('memories');
  try {
    assert.equal(await cleanup.ready, true);
    let sawCollage = false;
    for (let n = 0; n < 25; n++) {
      const text = r.elements().map(e => e.textContent).filter(Boolean);
      for (const label of text) assert.equal(label, 'Photo memories');
       if (text.length) { sawCollage = true; assert.ok(r.elements().some(e => e.classList.contains('ss-memory-card'))); }
      await r.tick();
    }
    assert.ok(sawCollage, 'The real scheduled collage must be exercised');
  } finally { cleanup(); assert.equal(r.pending.size, 0); }
});
