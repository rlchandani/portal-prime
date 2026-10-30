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

function fixture({ photoMs = 5000, deferLoad = false, photos, changingUrls = false } = {}) {
  let now = 0, nextId = 1, deferred = deferLoad;
  const pending = new Map(), loads = [], writes = [];
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
  function computed(node) {
    const declared = declarations(node);
    const result = Object.fromEntries(Object.entries(declared).map(([key, value]) =>
      [key, value.replace(/\s*!important$/, '')]));
    for (const [key, value] of Object.entries(node.style)) {
      if (value !== '') result[key.replace(/[A-Z]/g, letter => '-' + letter.toLowerCase())] = value;
    }
    for (const [key, value] of Object.entries(declared)) {
      if (value.endsWith('!important')) result[key] = value.replace(/\s*!important$/, '');
    }
    return result;
  }
  class Element {
    constructor() {
      this.children = []; this.names = '';
      this.style = new Proxy({}, { set: (style, key, value) => {
        const before = this.opacity(), oldTarget = computed(this).opacity;
        style[key] = value;
        if (key === 'transition' && value === 'none') this.fade = null;
        if (key === 'opacity') this.changedOpacity(before, oldTarget);
        if (key === 'backgroundImage') writes.push({ node: this, value });
        return true;
      } });
    }
    changedOpacity(before, oldTarget) {
      const current = computed(this), target = current.opacity;
      if (oldTarget === target || !this.parent) return;
      const duration = /opacity\s+([\d.]+)(ms|s)/.exec(current.transition || '');
      this.fade = { from: before, to: Number(target ?? 1), start: now,
        duration: duration ? Number(duration[1]) * (duration[2] === 's' ? 1000 : 1) : 0 };
    }
    get className() { return this.names; }
    set className(value) {
      const before = this.opacity(), oldTarget = computed(this).opacity;
      this.names = value;
      this.changedOpacity(before, oldTarget);
    }
    get classList() {
      return {
        contains: name => this.names.split(/\s+/).includes(name),
        add: (...names) => { this.className = [...new Set([...this.names.split(/\s+/), ...names])].join(' ').trim(); },
        remove: (...names) => { this.className = this.names.split(/\s+/).filter(name => !names.includes(name)).join(' '); },
      };
    }
    opacity() {
      if (!this.style || !this.fade) return Number(this.style ? computed(this).opacity ?? 1 : 1);
      const progress = this.fade.duration ? Math.min(1, (now - this.fade.start) / this.fade.duration) : 1;
      if (progress === 1) return this.fade.to;
      return this.fade.from + (this.fade.to - this.fade.from) * progress;
    }
    appendChild(child) {
      if (child.parent) child.parent.children = child.parent.children.filter(node => node !== child);
      child.parent = this; this.children.push(child); return child;
    }
    get offsetWidth() { return 2160; }
    dispatchEvent(event) { this['on' + event.type]?.(event); }
  }
  const window = { aspect: photo => photo.w / photo.h, preloadDecoded: (url, signal) => {
    const request = { url, signal };
    loads.push(request);
    if (!deferred) return Promise.resolve(true);
    deferred = false;
    return new Promise((resolve, reject) => { request.resolve = resolve; request.reject = reject; });
  } };
  const context = vm.createContext({ window, document: { createElement: () => new Element() }, AbortController,
    getComputedStyle: node => ({ ...computed(node), opacity: String(node.opacity()) }),
    setTimeout: schedule, clearTimeout: id => pending.delete(id),
    requestAnimationFrame: fn => schedule(fn, 16), cancelAnimationFrame: id => pending.delete(id),
    Date: class extends Date { static now() { return now; } },
    Math: Object.assign(Object.create(Math), { random: () => .5 }),
  });
  for (const file of ['modes.js', 'mode-impls.js']) {
    vm.runInContext(readFileSync(new URL(file, assets), 'utf8'), context, { filename: file });
  }
  const root = new Element();
  const deck = photos || Array.from({ length: 8 }, (_, i) => ({
    seed: 'generated-' + i, w: i % 2 ? 1000 : 1600, h: i % 2 ? 1600 : 1000,
  }));
  const cursor = { i: 0 }, resolutions = [];
  const cleanup = window.SS.MODES.framed.run({ root, cursor, W: 2160, H: 1440,
    photoMs, url: (photo, edge) => {
      const url = 'generated://' + photo.seed + (changingUrls ? '-r' + (resolutions.length + 1) : '');
      resolutions.push({ photo, edge, url }); return url;
    } }, deck);
  function nodes(node = root) { return [node, ...node.children.flatMap(child => nodes(child))]; }
  function prints() { return nodes().filter(node => node.classList.contains('ss-print')); }
  function slot(print) { return print.parent.parent.parent.parent; }
  function state(print) {
    const style = computed(print), animation = style.animation || 'none';
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
      task.fn(); await flush();
    }
    now = time; await flush();
  }
  function end(print, { propertyName = 'opacity', target = slot(print) } = {}) {
    slot(print).dispatchEvent({ type: 'transitionend', propertyName, target });
  }
  return { cleanup, prints, state, slot, nodes, pending, loads, writes, resolutions, cursor, computed, advanceTo, end,
    incoming: () => prints().filter(print => state(print).visible).at(-1),
    elapseTo: time => { assert.ok(time >= now); now = time; },
    deferNext: () => { deferred = true; },
    finishLoad: () => loads.findLast(load => load.resolve)?.resolve(true),
    failLoad: () => loads.findLast(load => load.reject)?.reject(new Error('unavailable')),
    flush };
}

function retired(r, print) {
  assert.equal(r.state(print).animation, 'none', 'Retirement stops the animation');
  assert.equal(r.state(print).promotion, 'auto', 'An invisible slot must release its promotion hint');
  assert.equal(r.state(print).opacity, 0);
  assert.equal(r.slot(print).classList.contains('retired'), true);
  assert.equal(print.style.backgroundImage, 'none', 'Retirement releases the decoded image URL');
}
function breathing(r, print) {
  assert.match(r.state(print).animation, /^breathe 22s linear infinite alternate$/);
  assert.equal(r.state(print).promotion, 'transform');
  assert.equal(r.state(print).playState, 'running');
}

// Catches fading the outgoing scene, retiring before incoming opacity 1, or changing cadence.
test('Framed Print admits an opaque first scene and fades only incoming scenes over eight swaps', async () => {
  const r = fixture();
  try {
    assert.equal(await r.cleanup.ready, true);
    const first = r.prints().find(print => r.state(print).visible);
    breathing(r, first);
    assert.equal(r.state(first).opacity, 1, 'Ready must mean fully opaque initial content');
    assert.equal(r.computed(r.slot(first)).background,
      'radial-gradient(120% 80% at 50% -10%, #23231f 0%, #141414 45%, #0b0b0c 100%)',
      'Each full-viewport scene must cover differently sized outgoing frames with the same room');
    assert.equal(r.computed(r.slot(first)).inset, '0');
    for (let swap = 1; swap <= 8; swap++) {
      await r.advanceTo(swap * 5000);
      const incoming = r.incoming();
      const outgoing = r.prints().find(print => print !== incoming);
      assert.equal(incoming.style.backgroundImage, `url("generated://generated-${swap % 8}")`);
      breathing(r, incoming); breathing(r, outgoing);
      assert.equal(r.state(incoming).opacity, 0);
      assert.equal(r.state(outgoing).opacity, 1, 'Outgoing must never fade underneath incoming');
      assert.equal(r.slot(incoming).parent.children.at(-1), r.slot(incoming), 'Incoming paints above outgoing');
      assert.equal(r.computed(r.slot(incoming)).background, r.computed(r.nodes().find(node =>
        node.classList.contains('ss-room'))).background, 'Incoming covers the outgoing frame with the same room');
      await r.advanceTo(swap * 5000 + 2399);
      assert.ok(r.state(incoming).opacity > 0 && r.state(incoming).opacity < 1);
      assert.equal(r.state(outgoing).opacity, 1);
      breathing(r, outgoing);
      await r.advanceTo(swap * 5000 + 2400);
      assert.equal(r.state(incoming).opacity, 1);
      r.end(incoming);
      retired(r, outgoing); breathing(r, incoming);
      assert.equal(r.pending.size, 1, 'Completion cancels its retirement fallback');
    }
    assert.equal(r.resolutions.length, 9, 'Resolve once per preparation');
    assert.ok(r.resolutions.every(resource => resource.edge === 1800));
    assert.equal(r.cursor.i, 9);
  } finally { r.cleanup(); }
});

// Catches outgoing, bubbled, unrelated and premature events retiring a still-visible print.
test('only the current incoming opacity completion may retire the outgoing print', async () => {
  const r = fixture();
  try {
    await r.cleanup.ready;
    await r.advanceTo(5000);
    const incoming = r.incoming();
    const outgoing = r.prints().find(print => print !== incoming);
    r.end(incoming); r.end(outgoing); breathing(r, incoming); breathing(r, outgoing);
    // Complete the simulated CSS transition before dispatching the owned fallback timer.
    r.elapseTo(7400);
    r.end(incoming, { propertyName: 'transform' }); breathing(r, outgoing);
    r.end(incoming, { target: incoming }); breathing(r, outgoing);
    r.end(outgoing); breathing(r, outgoing);
    r.end(incoming); retired(r, outgoing); breathing(r, incoming);
  } finally { r.cleanup(); }
});

// Catches delayed retirement from an earlier use overriding a slot's newer fade/reveal.
test('rapid slot reuse invalidates old retirement callbacks', async () => {
  const r = fixture({ photoMs: 1000 });
  try {
    await r.cleanup.ready;
    await r.advanceTo(1000);
    const oldIncoming = r.incoming(), stale = r.slot(oldIncoming).ontransitionend;
    const staleFallback = [...r.pending.values()].find(task => task.at > 2000).fn;
    await r.advanceTo(2000);
    const current = r.incoming();
    assert.notEqual(current, oldIncoming);
    stale?.({ target: r.slot(oldIncoming), propertyName: 'opacity' });
    for (const time of [2400, 3000, 3400, 4000, 4400, 5000]) {
      await r.advanceTo(time);
      stale?.({ target: r.slot(oldIncoming), propertyName: 'opacity' });
      await staleFallback(); await r.flush();
      for (const print of r.prints()) breathing(r, print);
      assert.equal(r.pending.size, 2, 'Only the current fade fallback and configured repeat may remain');
      assert.equal(r.state(r.prints().find(print => print !== r.incoming())).opacity, 1);
    }
  } finally { r.cleanup(); }
});

// Catches a lost completion event leaking URLs or fallback retirement consuming paused time.
test('the single retirement fallback uses remaining active time when completion events are absent', async () => {
  const r = fixture();
  try {
    await r.cleanup.ready;
    await r.advanceTo(6000);
    const incoming = r.incoming(), outgoing = r.prints().find(print => print !== incoming);
    r.cleanup.pause(); await r.advanceTo(20000); r.cleanup.resume();
    await r.advanceTo(21499);
    assert.equal(r.state(incoming).opacity, 1);
    assert.equal(r.state(outgoing).opacity, 1);
    assert.notEqual(outgoing.style.backgroundImage, 'none');
    assert.equal(r.pending.size, 2);
    await r.advanceTo(21500);
    retired(r, outgoing);
    assert.equal(r.pending.size, 1, 'Only the configured repeat remains; no polling loop');
    await r.advanceTo(23999); assert.equal(r.loads.length, 2);
    await r.advanceTo(24000); assert.equal(r.loads.length, 3, 'Repeat retains its own remaining interval');
  } finally { r.cleanup(); }
});

// Catches losing the shared pause/resume protocol when adding mode-specific cleanup.
test('old-WebView pause freezes incoming opacity and both prints until the remaining fade resumes', async () => {
  const r = fixture();
  try {
    await r.cleanup.ready;
    await r.advanceTo(6000);
    const incoming = r.incoming(), outgoing = r.prints().find(print => print !== incoming);
    const held = r.state(incoming).opacity;
    assert.ok(held > 0 && held < 1);
    r.cleanup.pause();
    assert.equal(r.pending.size, 0);
    await r.advanceTo(20000);
    assert.equal(r.state(incoming).opacity, held, 'Pause must freeze CSS pixels, not only JS timers');
    assert.equal(r.state(outgoing).opacity, 1);
    assert.equal(r.state(incoming).playState, 'paused');
    assert.equal(r.state(outgoing).playState, 'paused');
    r.end(incoming); assert.equal(r.slot(outgoing).classList.contains('retired'), false);
    r.cleanup.resume();
    await r.advanceTo(21399);
    assert.ok(r.state(incoming).opacity < 1); breathing(r, outgoing);
    await r.advanceTo(21400);
    assert.equal(r.state(incoming).opacity, 1);
    r.end(incoming); retired(r, outgoing);
    assert.equal(r.pending.size, 1);
  } finally { r.cleanup(); }
});

// Catches leaked event listeners, scheduled work, and mutation after disposal.
test('destruction cancels retirement and repeat callbacks and stops both prints', async () => {
  const r = fixture();
  await r.cleanup.ready;
  await r.advanceTo(5000);
  const capturedCallbacks = [...r.pending.values()].map(task => task.fn);
  const handler = r.slot(r.incoming()).ontransitionend, target = r.slot(r.incoming());
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
  const published = r.writes.length;
  handler?.({ target, propertyName: 'opacity' });
  for (const fn of capturedCallbacks) await fn();
  r.cleanup.resume();
  await r.advanceTo(60000);
  for (const print of r.prints()) r.end(print);
  assert.equal(r.pending.size, 0);
  assert.deepEqual(snapshot(), before);
  assert.equal(r.writes.length, published, 'Captured work cannot re-publish resources after cleanup');
});

// Held inline opacity may survive disposal, so visibility and resource release own cleanup.
test('cleanup during a held fade hides both scenes and cannot be resumed by stale work', async () => {
  const r = fixture();
  await r.cleanup.ready; await r.advanceTo(6000);
  const incoming = r.incoming(), target = r.slot(incoming), handler = target.ontransitionend;
  const callbacks = [...r.pending.values()].map(task => task.fn);
  r.cleanup.pause(); r.cleanup();
  for (const print of r.prints()) {
    assert.equal(r.computed(r.slot(print)).visibility, 'hidden');
    assert.equal(print.style.backgroundImage, 'none');
    assert.equal(r.state(print).animation, 'none');
    assert.equal(r.state(print).promotion, 'auto');
    assert.equal(r.slot(print).ontransitionend, null);
  }
  const snapshot = JSON.stringify(r.prints().map(print => ({
    style: print.style, root: r.slot(print).style, classes: r.slot(print).className,
  })));
  const count = r.writes.length;
  r.cleanup.resume();
  handler?.({ target, propertyName: 'opacity' });
  for (const callback of callbacks) await callback();
  await r.advanceTo(60000);
  assert.equal(r.pending.size, 0);
  assert.equal(r.writes.length, count);
  assert.equal(JSON.stringify(r.prints().map(print => ({
    style: print.style, root: r.slot(print).style, classes: r.slot(print).className,
  }))), snapshot);
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
    retired(r, print);
  }
  assert.ok(r.writes.every(write => write.value === 'none'));
});

// Catches disposal between t.load's admission and the caller's publication continuation.
test('cleanup at the decode admission boundary cannot reassign retired image URLs', async () => {
  for (let checkpoint = 0; checkpoint <= 16; checkpoint++) {
    const r = fixture({ deferLoad: true });
    await r.flush(); r.finishLoad();
    for (let turn = 0; turn < checkpoint; turn++) await Promise.resolve();
    r.cleanup();
    const count = r.writes.length;
    await r.cleanup.ready; await r.flush();
    assert.equal(r.writes.length, count, `Cleanup at microtask ${checkpoint} forbids later resource writes`);
    assert.equal(r.pending.size, 0);
    r.prints().forEach(print => retired(r, print));
  }
});

// Catches an in-flight replacement repopulating a disposed or paused scene.
test('cleanup while a replacement decode is paused prevents late resource publication', async () => {
  const r = fixture();
  await r.cleanup.ready;
  r.deferNext(); await r.advanceTo(5000);
  r.cleanup.pause(); r.finishLoad(); await r.flush();
  assert.equal(r.writes.filter(write => write.value.startsWith('url(')).length, 1);
  r.cleanup();
  const count = r.writes.length;
  r.cleanup.resume(); await r.advanceTo(60000);
  assert.equal(r.writes.length, count);
  assert.equal(r.pending.size, 0);
  assert.equal(r.loads.at(-1).signal.aborted, true);
  r.prints().forEach(print => retired(r, print));
});

// Catches clearing the current resource before replacement decode has succeeded.
test('failed replacement preparation keeps the current opaque scene and configured retry interval', async () => {
  const r = fixture();
  try {
    await r.cleanup.ready;
    const current = r.incoming(), url = current.style.backgroundImage;
    r.deferNext(); await r.advanceTo(5000); r.failLoad(); await r.flush();
    assert.equal(r.incoming(), current);
    assert.equal(r.state(current).opacity, 1);
    assert.equal(current.style.backgroundImage, url);
    assert.equal(r.writes.filter(write => write.value.startsWith('url(')).length, 1);
    await r.advanceTo(9999); assert.equal(r.loads.length, 2);
    await r.advanceTo(10000); assert.equal(r.loads.length, 3);
  } finally { r.cleanup(); }
});

// Catches replacing a decoded URL with a new resolver result before publishing the print.
test('replacement publishes only its exact admitted URL and waits for paused decode publication', async () => {
  const r = fixture({ changingUrls: true });
  try {
    await r.cleanup.ready;
    const current = r.incoming(), firstUrl = current.style.backgroundImage;
    r.deferNext(); await r.advanceTo(5000);
    assert.equal(r.loads.at(-1).url, 'generated://generated-1-r2');
    assert.equal(r.resolutions.length, 2);
    assert.equal(r.state(current).opacity, 1);
    assert.equal(current.style.backgroundImage, firstUrl);
    r.cleanup.pause(); r.finishLoad(); await r.flush();
    assert.equal(r.writes.filter(write => write.value.startsWith('url(')).length, 1);
    await r.advanceTo(20000); r.cleanup.resume(); await r.flush();
    assert.notEqual(r.incoming(), current);
    assert.equal(r.incoming().style.backgroundImage, 'url("generated://generated-1-r2")');
    assert.equal(r.resolutions.length, 2, 'Publication must not resolve again after decode');
    assert.equal(r.state(current).opacity, 1);
    assert.equal(r.state(r.incoming()).opacity, 0);
  } finally { r.cleanup(); }
});

// Catches lost portrait geometry, motion overriding cached hints, or stale still state on reuse.
test('cached face hints show a whole still upright print; reused no-hint slots breathe again', async () => {
  const r = fixture({ photos: [
    { seed: 'portrait-face', w: 1000, h: 1600, fx: .25, fy: .2 },
    { seed: 'landscape', w: 1600, h: 1000 },
    { seed: 'portrait', w: 1000, h: 1600 },
  ] });
  try {
    await r.cleanup.ready;
    const first = r.incoming();
    assert.equal(first.style.width, '648px'); assert.equal(first.style.height, '1037px');
    assert.equal(r.state(first).animation, 'none');
    assert.equal(r.state(first).promotion, 'auto');
    assert.equal(first.style.backgroundSize, 'contain');
    assert.equal(first.style.backgroundPosition, 'center');
    assert.equal(r.computed(first.parent.parent).padding, '46px');
    assert.equal(r.computed(first.parent.parent.parent).padding, '10px');
    await r.advanceTo(5000);
    const second = r.incoming();
    assert.equal(second.style.width, '1296px'); assert.equal(second.style.height, '810px');
    breathing(r, second);
    await r.advanceTo(10000);
    assert.equal(r.incoming(), first);
    breathing(r, first);
    assert.equal(r.slot(first).style.transform, undefined, 'Photo presentation stays upright');
  } finally { r.cleanup(); }
});
