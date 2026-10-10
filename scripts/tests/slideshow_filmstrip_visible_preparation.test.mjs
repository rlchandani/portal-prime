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
import { installNativeFrameBarrier } from '../filmstrip_visible_preparation_browser.mjs';

const assets = new URL('../../app/src/main/assets/screensaver/', import.meta.url);
// Execute the actual mode and timer owner. Synthetic DOM geometry and controlled
// decode gates are not raster evidence; the companion Chrome test supplies that.
function fixture({ W = 1000, H = 600, aspects = Array(12).fill(1.5), uncertain = false,
  ignoreAbort = false, start = 0 } = {}) {
  let now = 0, serial = 1, generation = 1;
  const timers = new Map(), requests = [], calls = [], writes = [], decoded = new Set(), records = [];
  function schedule(fn, ms) { const id = serial++; timers.set(id, { fn, at: now + ms }); return id; }
  class Element {
    constructor() {
      this.children = []; this.className = '';
      this.style = {};
      Object.defineProperty(this.style, 'backgroundImage', { enumerable: true, configurable: true,
        get: () => this.background,
        set: value => { this.background = value; writes.push({ node: this, value, decoded: decoded.has(value.slice(5, -2)) }); } });
    }
    get classList() {
      return {
        contains: k => this.className.split(' ').includes(k),
        toggle: (k, on) => { this.className = this.className.split(' ').filter(x => x !== k).concat(on ? [k] : []).join(' '); },
      };
    }
    appendChild(child) { child.parentNode = this; this.children.push(child); return child; }
    getBoundingClientRect() {
      if (this === root) return { x: 0, y: 0, left: 0, top: 0, width: uncertain ? W + 100 : W, height: H };
      if (this.className === 'ss-track') return { left: W / 2, top: 0, height: H };
      const w = Number.parseFloat(this.style.width), h = H * .62;
      const i = this.parentNode.children.indexOf(this);
      const left = this.parentNode.children.slice(0, i).reduce((n, s) => n + Number.parseFloat(s.style.width) + 60, 0);
      return { left: W / 2 + left + w * .15, top: H / 2 - h * .35, width: w * .7, height: h * .7 };
    }
  }
  const root = new Element();
  function style(node) {
    if (node === root) return { transform: 'none' };
    if (node.className === 'ss-track') return { display: 'flex', flexDirection: 'row', alignItems: 'center',
      position: 'absolute', left: W / 2 + 'px', gap: '60px' };
    const w = Number.parseFloat(node.style.width), h = H * .62;
    return { width: w + 'px', height: h + 'px', transform: 'matrix(0.7, 0, 0, 0.7, 0, 0)',
      boxShadow: 'rgba(0, 0, 0, 0.9) 0px 30px 70px -20px',
      transitionProperty: 'transform, opacity, filter',
      transitionDuration: '1.4s, 1.4s, 1.4s',
      transitionTimingFunction: 'cubic-bezier(0.22, 1, 0.36, 1), ease, ease' };
  }
  const window = {
    aspect: p => p.w / p.h,
    preloadDecoded(url, signal) {
      const request = { url, signal, status: 'pending' }; requests.push(request);
      return new Promise((resolve, reject) => {
        request.complete = () => {
          if (request.status !== 'pending') return;
          request.status = 'decoded'; decoded.add(url); resolve(true);
        };
        request.fail = () => { request.status = 'failed'; reject(Error('generated decode failure')); };
        signal.addEventListener('abort', () => {
          if (ignoreAbort || request.status !== 'pending') return;
          request.status = 'aborted'; reject(Error('aborted'));
        }, { once: true });
      });
    },
  };
  const context = vm.createContext({ window, document: { createElement: () => new Element() }, AbortController,
    getComputedStyle: style, setTimeout: schedule, clearTimeout: id => timers.delete(id),
    requestAnimationFrame: fn => schedule(fn, 16), cancelAnimationFrame: id => timers.delete(id),
    Date: class extends Date { static now() { return now; } }, Math });
  for (const file of ['modes.js', 'mode-impls.js']) vm.runInContext(readFileSync(new URL(file, assets), 'utf8'), context);
  const deck = aspects.map((a, i) => ({ seed: i, w: 1000 * a, h: 1000, fx: .7, fy: .25 }));
  const cursor = { i: start };
  const cleanup = window.SS.MODES.filmstrip.run({ root, cursor, W, H, microMs: 5000,
    record: n => records.push(n),
    url(p, edge) {
      const url = `generated://${p.seed}/${edge}/${generation}/${calls.length}`;
      calls.push({ seed: p.seed, edge, generation, url }); return url;
    },
  }, deck);
  let ready;
  cleanup.ready.then(value => { ready = value; });
  async function flush() { for (let i = 0; i < 60; i++) await Promise.resolve(); }
  async function finishExcept(held = () => false) {
    for (let i = 0; i < 8; i++) { await flush(); requests.filter(r => !held(r)).forEach(r => r.complete()); }
    await flush();
  }
  const slides = () => root.children.find(n => n.className === 'ss-track')?.children || [];
  const current = () => slides().findIndex(s => s.classList.contains('center'));
  const snapshot = () => JSON.stringify(root.children.map(n => ({ className: n.className, style: n.style,
    children: n.children.map(s => ({ className: s.className, style: s.style, img: s.children[0]?.style })) })));
  async function advance(ms) {
    now += ms;
    for (const [id, task] of [...timers]) if (task.at <= now) { timers.delete(id); task.fn(); await flush(); }
  }
  // Independent conservative paint-support oracle; more permissive than the
  // product admission bound. It samples the whole monotone track-motion envelope.
  function exposed(from, to) {
    const widths = aspects.map(a => H * .62 * a);
    const offsets = widths.map((w, i) => widths.slice(0, i).reduce((n, w) => n + w + 60, 0) + w / 2);
    const answer = new Set();
    for (let frame = 0; frame <= 200; frame++) {
      const off = from == null ? offsets[to] * frame / 200
        : offsets[from] + (offsets[to] - offsets[from]) * frame / 200;
      widths.forEach((w, i) => {
        const x = W / 2 + offsets[i] - off, radius = (w / 2 + 160) * 1.12 + 2;
        if (x + radius >= 0 && x - radius <= W) answer.add(i);
      });
    }
    return answer;
  }
  function assertExposure(from, to) {
    for (const i of exposed(from, to))
      assert.ok(slides()[i]?.children[0]?.style.backgroundImage, `exposed slot ${i} needs a decoded image`);
    assert.ok(writes.every(w => w.decoded), 'Every published image must use its exact decoded URL');
  }
  return { cleanup, flush, finishExcept, slides, current, snapshot, requests, calls, writes, timers,
    cursor, records, advance, assertExposure, get ready() { return ready; },
    generation: n => { generation = n; } };
}

test('initial readiness excludes a held fully offscreen decoder but retains all twelve geometry slots', async () => {
  const r = fixture();
  try {
    await r.finishExcept(q => q.url.startsWith('generated://11/1400/'));
    assert.equal(r.ready, true, 'An irrelevant offscreen decoder must not block ready');
    assert.equal(r.slides().length, 12);
    assert.ok(r.calls.filter(c => c.edge === 1400).length < 12);
    assert.equal(r.calls.filter(c => c.edge === 1000).length, 1);
    r.assertExposure(null, 0);
  } finally { r.cleanup(); }
});

test('visible decode and the separate backdrop both gate initial publication', async () => {
  for (const edge of [1400, 1000]) {
    const r = fixture();
    try {
      await r.finishExcept(q => q.url.startsWith(`generated://0/${edge}/`));
      assert.equal(r.ready, undefined); assert.equal(r.current(), -1);
      await r.finishExcept(); assert.equal(r.ready, true); r.assertExposure(null, 0);
    } finally { r.cleanup(); }
  }
});

test('entering decode holds the complete previous scene and busy navigation cannot overlap it', async () => {
  const r = fixture();
  try {
    await r.finishExcept(); assert.equal(r.ready, true);
    const before = r.snapshot();
    const next = r.cleanup.next(); await r.flush();
    await r.finishExcept(q => q.url.startsWith('generated://3/1400/'));
    assert.equal(r.snapshot(), before); assert.equal(r.current(), 0);
    assert.equal(await r.cleanup.previous(), false);
    await r.finishExcept(); assert.equal(await next, true); assert.equal(r.current(), 1);
    r.assertExposure(0, 1);
  } finally { r.cleanup(); }
});

for (const [name, W, H, aspects] of [
  ['portrait cards / landscape viewport', 1000, 600, Array(12).fill(.45)],
  ['landscape cards / portrait viewport', 600, 1000, Array(12).fill(1.7)],
  ['panoramas', 1200, 600, Array(12).fill(5)],
  ['mixed geometry', 600, 900, [.4, 1, 5, .7, 2, .2, 4, 1, .5, 2, 3, .8]],
  ['one-photo deck', 1000, 600, [.4]],
  ['two-photo deck', 600, 1000, [.5, 3]],
]) {
  test(`${name}: initial, stepped navigation and both wraps retain the whole exposure envelope`, async () => {
    const r = fixture({ W, H, aspects, start: 0 });
    try {
      await r.finishExcept(); assert.equal(r.ready, true);
      assert.equal(r.cursor.i, aspects.length); r.assertExposure(null, 0);
      let center = 0;
      for (const direction of ['previous', ...Array(aspects.length).fill('next'), 'previous']) {
        const target = (center + (direction === 'next' ? 1 : aspects.length - 1)) % aspects.length;
        const job = r.cleanup[direction](); await r.finishExcept();
        assert.equal(await job, true); assert.equal(r.current(), target);
        r.assertExposure(center, target); center = target;
      }
      assert.equal(r.cursor.i, aspects.length); assert.deepEqual(r.records, []);
      assert.ok(r.calls.filter(c => c.edge === 1400).length <= aspects.length, 'At most one live preparation per slot');
    } finally { r.cleanup(); }
  });
}

test('first-to-last wrap blocks on an intermediate offscreen card, not just its endpoints', async () => {
  const r = fixture();
  try {
    await r.finishExcept(); const before = r.snapshot();
    const previous = r.cleanup.previous(); await r.finishExcept(q => q.url.startsWith('generated://6/1400/'));
    assert.equal(r.snapshot(), before); assert.equal(r.current(), 0);
    assert.ok(r.requests.some(q => q.url.startsWith('generated://6/1400/') && q.status === 'pending'));
    await r.finishExcept(); assert.equal(await previous, true); r.assertExposure(0, 11);
    const next = r.cleanup.next(); await r.finishExcept(); assert.equal(await next, true); r.assertExposure(11, 0);
  } finally { r.cleanup(); }
});

test('new preparations resolve fresh resources once; existing live images keep their exact prepared URL', async () => {
  const r = fixture();
  try {
    await r.finishExcept(); const image = r.slides()[0].children[0].style.backgroundImage;
    const count = r.calls.filter(c => c.edge === 1400).length;
    r.generation(2); const next = r.cleanup.next(); await r.finishExcept(); assert.equal(await next, true);
    assert.equal(r.slides()[0].children[0].style.backgroundImage, image);
    assert.ok(r.calls.filter(c => c.edge === 1400).slice(count).every(c => c.generation === 2));
    assert.equal(new Set(r.calls.filter(c => c.edge === 1400).map(c => c.seed)).size,
      r.calls.filter(c => c.edge === 1400).length);
    r.assertExposure(0, 1);
  } finally { r.cleanup(); }
});

test('a nonzero cursor retains the original twelve-slot selection order and history recording', async () => {
  const aspects = Array.from({ length: 16 }, (_, i) => .5 + i * .1);
  const r = fixture({ aspects, start: 5 });
  try {
    await r.finishExcept(); assert.equal(r.ready, true); assert.equal(r.cursor.i, 17);
    assert.deepEqual(r.slides().map(s => Number.parseFloat(s.style.width)),
      Array.from({ length: 12 }, (_, i) => 600 * .62 * aspects[(5 + i) % 16]));
    assert.equal(r.calls.find(c => c.edge === 1000).seed, 5);
    const previous = r.cleanup.previous(); await r.finishExcept(); assert.equal(await previous, true);
    assert.equal(r.calls.filter(c => c.edge === 1000).at(-1).seed, 0);
    assert.equal(r.cursor.i, 17); assert.deepEqual(r.records, []);
  } finally { r.cleanup(); }
});

test('pause straddling partial initial or entering preparation prevents publication until resume', async () => {
  for (const entering of [false, true]) {
    const r = fixture();
    try {
      if (entering) await r.finishExcept();
      const job = entering ? r.cleanup.next() : r.cleanup.ready;
      await r.flush(); const before = r.snapshot();
      r.cleanup.pause(); await r.finishExcept();
      assert.equal(r.snapshot(), before); assert.equal(r.timers.size, 0);
      r.cleanup.resume(); await r.flush();
      assert.equal(r.requests.filter(q => q.status === 'pending').length, 0,
        'Resume must publish the prepared batch without requesting another unresolved decode');
      assert.equal(await job, true);
      assert.equal(r.current(), entering ? 1 : 0);
    } finally { r.cleanup(); }
  }
});

test('disposal during partial preparation aborts decode, timers and late ownership', async () => {
  for (const entering of [false, true]) for (const ignoreAbort of [false, true]) {
    const r = fixture({ ignoreAbort });
    if (entering) await r.finishExcept();
    const job = entering ? r.cleanup.next() : r.cleanup.ready;
    await r.flush(); const before = r.snapshot(); r.cleanup(); r.cleanup();
    await r.finishExcept(); assert.equal(await job, false);
    assert.equal(r.snapshot(), before); assert.equal(r.timers.size, 0);
    assert.ok(r.requests.every(q => q.signal.aborted));
    await r.advance(60000); assert.equal(r.snapshot(), before);
  }
});

test('a failed entering batch publishes no partial images and retry resolves a new exact batch', async () => {
  const r = fixture();
  try {
    await r.finishExcept(); const before = r.snapshot();
    const next = r.cleanup.next(); await r.flush();
    const entering = r.requests.find(q => q.status === 'pending' && q.url.startsWith('generated://3/1400/'));
    assert.ok(entering, 'Entering card must have its own demand preparation');
    entering.fail();
    await r.finishExcept(); assert.equal(await next, false); assert.equal(r.snapshot(), before);
    r.generation(3); const retry = r.cleanup.next(); await r.finishExcept();
    assert.equal(await retry, true); r.assertExposure(0, 1);
  } finally { r.cleanup(); }
});

test('one failed demand decode aborts every sibling before another preparation can start', async () => {
  const r = fixture();
  try {
    await r.finishExcept(); const before = r.snapshot();
    const next = r.cleanup.next(); await r.flush();
    const batch = r.requests.filter(q => q.status === 'pending');
    const entering = batch.find(q => q.url.startsWith('generated://3/1400/'));
    assert.ok(entering, 'Demand batch must include the entering card');
    entering.fail();
    await r.flush(); assert.equal(await next, false);
    assert.ok(batch.every(q => q.signal.aborted), 'Failed demand batch must release all sibling decoders');
    assert.equal(r.snapshot(), before);
    r.generation(3); const retry = r.cleanup.next(); await r.finishExcept();
    assert.equal(await retry, true); r.assertExposure(0, 1);
  } finally { r.cleanup(); }
});

test('uncertain viewport geometry fails open to original complete preparation', async () => {
  const r = fixture({ uncertain: true });
  try {
    await r.finishExcept(q => q.url.startsWith('generated://11/1400/'));
    assert.equal(r.ready, undefined); assert.equal(r.calls.filter(c => c.edge === 1400).length, 12);
    await r.finishExcept(); assert.equal(r.ready, true);
  } finally { r.cleanup(); }
});

test('micro interval and resume retain the original cadence without background preparation', async () => {
  const r = fixture();
  try {
    await r.finishExcept(); const count = r.calls.length;
    await r.advance(4999); assert.equal(r.current(), 0); assert.equal(r.calls.length, count);
    r.cleanup.pause(); await r.advance(20000); assert.equal(r.current(), 0);
    r.cleanup.resume(); await r.advance(1); await r.finishExcept();
    assert.equal(r.current(), 1); r.assertExposure(0, 1);
    assert.equal(r.timers.size, 1);
  } finally { r.cleanup(); }
});

// Clock affinity is observable behavior: fixture-clock callbacks cannot commit
// Blink's native renderer. This guards the actual browser probe's admission fence.
test('probe admission retains native RAF ownership after the virtual JS clock replaces RAF', async () => {
  const native = [], virtual = [], events = [];
  const browser = { requestAnimationFrame(fn) { native.push(fn); } };
  installNativeFrameBarrier(browser);
  browser.requestAnimationFrame = fn => virtual.push(fn);
  let admitted = false;
  const fence = browser.filmstripNativeFrame().then(() => { admitted = true; events.push('admitted'); });
  assert.equal(native.length, 1, 'Renderer admission must request the captured native clock');
  assert.equal(virtual.length, 0, 'No virtual fixture callback can admit native CSS paint');
  await Promise.resolve(); assert.equal(admitted, false);
  events.push('style-frame'); native.shift()(16);
  await Promise.resolve(); assert.equal(admitted, false, 'Style commit alone is not the following paint frame');
  assert.equal(native.length, 1);
  events.push('paint-frame'); native.shift()(32);
  await fence;
  assert.deepEqual(events, ['style-frame', 'paint-frame', 'admitted']);
  assert.equal(native.length, 0); assert.equal(virtual.length, 0);
});
