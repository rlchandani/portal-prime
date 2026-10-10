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
  ignoreAbort = false, start = 0, physicalScreen = { width: W, height: H }, controlledMotion = false } = {}) {
  let now = 0, serial = 1, generation = 1;
  const viewport = { width: W, height: H };
  const paint = { offset: 0, transformOverride: null };
  const windowListeners = new Map(), paintReads = [];
  const timers = new Map(), requests = [], calls = [], writes = [], visibilityWrites = [], decoded = new Set(), records = [];
  function schedule(fn, ms, kind = 'timer') {
    const id = serial++; timers.set(id, { fn, at: now + ms, ms, kind }); return id;
  }
  class Element {
    constructor() {
      this.children = []; this.className = ''; this.listeners = new Map();
      this.style = {};
      Object.defineProperty(this.style, 'visibility', { enumerable: true, configurable: true,
        get: () => this.visibility || '',
        set: value => { this.visibility = value; visibilityWrites.push({ node: this, value }); } });
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
    addEventListener(type, fn) {
      if (!this.listeners.has(type)) this.listeners.set(type, new Set());
      this.listeners.get(type).add(fn);
    }
    removeEventListener(type, fn) { this.listeners.get(type)?.delete(fn); }
    getBoundingClientRect() {
      if (this === root) return { x: 0, y: 0, left: 0, top: 0, width: uncertain ? viewport.width + 100 : viewport.width, height: viewport.height };
      if (this.className === 'ss-track') {
        const x = viewport.width / 2 - (controlledMotion ? paint.offset : targetOffset());
        const width = this.children.reduce((sum, child) => sum + Number.parseFloat(child.style.width), 0) +
          Math.max(0, this.children.length - 1) * 60;
        return { left: x, right: x + width, top: 0, bottom: viewport.height, width, height: viewport.height };
      }
      const w = Number.parseFloat(this.style.width), h = viewport.height * .62;
      const i = this.parentNode.children.indexOf(this);
      const left = this.parentNode.children.slice(0, i).reduce((n, s) => n + Number.parseFloat(s.style.width) + 60, 0);
      const translation = controlledMotion ? paint.offset : targetOffset();
      return { left: viewport.width / 2 - translation + left + w * .15,
        top: viewport.height / 2 - h * .35, width: w * .7, height: h * .7 };
    }
  }
  const root = new Element();
  function style(node) {
    if (node === root) return { transform: 'none' };
    if (node.className === 'ss-track') {
      const offset = controlledMotion ? paint.offset : targetOffset();
      paintReads.push({ at: now, offset });
      return { display: 'flex', flexDirection: 'row', alignItems: 'center',
        position: 'absolute', left: viewport.width / 2 + 'px', gap: '60px',
        transform: paint.transformOverride || (node.style.transform ? `matrix(1, 0, 0, 1, ${-offset}, 0)` : 'none'),
        transitionProperty: 'transform', transitionDuration: '1.4s', transitionDelay: '0s',
        transitionTimingFunction: 'cubic-bezier(0.22, 1, 0.36, 1)' };
    }
    const w = Number.parseFloat(node.style.width), h = viewport.height * .62;
    return { width: w + 'px', height: h + 'px', transform: 'matrix(0.7, 0, 0, 0.7, 0, 0)',
      boxShadow: 'rgba(0, 0, 0, 0.9) 0px 30px 70px -20px',
      transitionProperty: 'transform, opacity, filter',
      transitionDuration: '1.4s, 1.4s, 1.4s', transitionDelay: '0s, 0s, 0s',
      transitionTimingFunction: 'cubic-bezier(0.22, 1, 0.36, 1), ease, ease' };
  }
  const window = {
    screen: physicalScreen,
    addEventListener(type, fn) {
      if (!windowListeners.has(type)) windowListeners.set(type, new Set());
      windowListeners.get(type).add(fn);
    },
    removeEventListener(type, fn) { windowListeners.get(type)?.delete(fn); },
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
  // Browser globals and window methods share one fake owner/clock. Renderer
  // transform state is deliberately separate from both Date and performance.
  Object.assign(window, { getComputedStyle: style, setTimeout: schedule, clearTimeout: id => timers.delete(id),
    requestAnimationFrame: fn => schedule(fn, 16, 'frame'), cancelAnimationFrame: id => timers.delete(id),
    performance: { now: () => now } });
  const context = vm.createContext({ window, document: { createElement: () => new Element() }, AbortController,
    getComputedStyle: style, setTimeout: window.setTimeout, clearTimeout: window.clearTimeout,
    requestAnimationFrame: window.requestAnimationFrame, cancelAnimationFrame: window.cancelAnimationFrame,
    addEventListener: (...args) => window.addEventListener(...args),
    removeEventListener: (...args) => window.removeEventListener(...args), performance: window.performance,
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
    const end = now + ms;
    for (let steps = 0; ; steps++) {
      const next = [...timers].filter(([, task]) => task.at <= end).sort((a, b) => a[1].at - b[1].at)[0];
      if (!next) break;
      assert.ok(steps < 10000, 'Callback scheduling must stay bounded');
      const [id, task] = next; now = task.at; timers.delete(id); task.fn(now); await flush();
    }
    now = end;
  }
  // Independent conservative paint-support oracle; more permissive than the
  // product admission bound. It samples the whole monotone track-motion envelope.
  function exposed(from, to, viewWidth = viewport.width) {
    const widths = aspects.map(a => H * .62 * a);
    const offsets = widths.map((w, i) => widths.slice(0, i).reduce((n, w) => n + w + 60, 0) + w / 2);
    const answer = new Set();
    for (let frame = 0; frame <= 200; frame++) {
      const off = from == null ? offsets[to] * frame / 200
        : offsets[from] + (offsets[to] - offsets[from]) * frame / 200;
      widths.forEach((w, i) => {
        const x = viewWidth / 2 + offsets[i] - off, radius = (w / 2 + 160) * 1.12 + 2;
        if (x + radius >= 0 && x - radius <= viewWidth) answer.add(i);
      });
    }
    return answer;
  }
  function assertExposure(from, to) {
    for (const i of exposed(from, to))
      assert.ok(slides()[i]?.children[0]?.style.backgroundImage, `exposed slot ${i} needs a decoded image`);
    assert.ok(writes.every(w => w.decoded), 'Every published image must use its exact decoded URL');
  }
  // Test-side CSS visibility inheritance includes both the slide shadow and
  // its image. A child explicitly made visible can escape a hidden parent;
  // display:none/hidden removes the layout slot and is forbidden separately.
  function paintVisible(node) {
    for (let n = node; n; n = n.parentNode)
      if (n.hidden || n.style.display === 'none') return false;
    for (let n = node; n; n = n.parentNode)
      if (n.style.visibility) return n.style.visibility === 'visible';
    return true;
  }
  function physicalExposure(from, to) {
    const answer = new Set(exposed(from, to));
    // Check the two display widths separately rather than reading the product's
    // viewportBound/needed predicate. The original 160px test shadow allowance
    // remains independent of the 512px product padding. This is not raster proof.
    for (const width of [physicalScreen?.width, physicalScreen?.height])
      if (Number.isFinite(width) && width > 0)
        for (const i of exposed(from, to, width)) answer.add(i);
    return answer;
  }
  function assertLayout() {
    const slots = slides(), track = slots[0]?.parentNode;
    assert.equal(slots.length, Math.min(12, aspects.length), 'Every metadata slot must retain its layout box');
    assert.equal(track?.style.gap || '60px', '60px', 'The flex gap must remain 60px');
    assert.equal(track?.style.left || '50%', '50%', 'The strip must remain centered on the viewport');
    slots.forEach((s, i) => {
      const w = H * .62 * aspects[i], im = s.children[0];
      assert.equal(Number.parseFloat(s.style.width), w, `Slot ${i} must keep its original inline width`);
      assert.equal(Number.parseFloat(im.style.width), w);
      assert.equal(im.style.height, '100%');
      assert.equal(s.parentNode, track); assert.equal(im.parentNode, s);
      for (const n of [root, track, s, im]) {
        assert.notEqual(n.style.display, 'none', 'Culling must not remove any flex slot');
        assert.notEqual(n.hidden, true, 'The HTML hidden attribute removes layout');
        assert.notEqual(n.style.visibility, 'collapse', 'Flex collapse must not replace visibility:hidden');
      }
    });
  }
  function assertPaintExposure(from, to) {
    assertLayout();
    for (const i of physicalExposure(from, to)) {
      const s = slides()[i];
      assert.ok(paintVisible(s), `Exposed slot ${i} must retain its painted box/shadow`);
      assert.ok(paintVisible(s.children[0]), `Exposed slot ${i} must retain its image visibility`);
      assert.ok(s.children[0].style.backgroundImage, `Exposed slot ${i} needs its prepared image`);
    }
    assert.ok(writes.every(w => w.decoded), 'Visible publication must retain exact decoded URLs');
  }
  function assertSomeFarHidden(from, to) {
    const exposure = physicalExposure(from, to);
    const far = slides().filter((_, i) => !exposure.has(i));
    assert.ok(far.length, 'The fixture must contain cards outside both display paint envelopes');
    assert.ok(slides().every(s => paintVisible(s)),
      'Every original card/shadow paint layer must remain visible for raster fidelity');
    assert.ok(far.some(s => !paintVisible(s.children[0])),
      'At least one provably far image must be suppressed without removing its card slot or shadow layer');
    assertPaintExposure(from, to);
  }
  function listenerCount() {
    function count(n) {
      return [...n.listeners.values()].reduce((sum, set) => sum + set.size, 0) +
        n.children.reduce((sum, child) => sum + count(child), 0);
    }
    return count(root) + [...windowListeners.values()].reduce((sum, set) => sum + set.size, 0);
  }
  function track() { return root.children.find(n => n.className === 'ss-track'); }
  function targetOffset() {
    const value = track()?.style.transform;
    return value ? -Number(/^translateX\(([-+.\deE]+)px\)$/.exec(value)?.[1]) : 0;
  }
  function offset(i) {
    return slides().slice(0, i).reduce((sum, s) => sum + Number.parseFloat(s.style.width) + 60, 0) +
      Number.parseFloat(slides()[i].style.width) / 2;
  }
  function transition({ target = track(), propertyName = 'transform', type = 'transitionend' } = {}) {
    const event = { target, currentTarget: null, propertyName, type, elapsedTime: 1.4, pseudoElement: '', timeStamp: now };
    for (let node = target; node; node = node.parentNode) {
      event.currentTarget = node;
      for (const fn of [...(node.listeners.get(type) || [])]) fn.call(node, event);
    }
    event.currentTarget = window;
    for (const fn of [...(windowListeners.get(type) || [])]) fn.call(window, event);
  }
  function settlementCallbacks() {
    // The fixture's declared photo cadence is 5000ms. Capturing its callbacks
    // would test unrelated timer supersession; retain only settlement waits/RAF.
    return [...timers.values()].filter(task => task.kind === 'frame' || task.ms !== 5000).map(task => task.fn);
  }
  function listenerCallbacks() {
    const answer = [];
    function collect(node) {
      for (const [type, callbacks] of node.listeners)
        for (const fn of callbacks) answer.push({ fn, type, owner: node });
      node.children.forEach(collect);
    }
    collect(root);
    for (const [type, callbacks] of windowListeners)
      for (const fn of callbacks) answer.push({ fn, type, owner: window });
    return answer;
  }
  function assertOwnedCallbacks() {
    const tasks = [...timers.values()];
    assert.equal(tasks.filter(task => task.kind === 'timer' && task.ms === 5000).length, 1,
      'Exactly one existing photo-cadence owner must remain');
    assert.ok(tasks.length <= 3, 'Only a bounded settlement guard and frame verification may accompany cadence');
  }
  async function frames() { for (let i = 0; i < 4; i++) await advance(16); }
  async function settle() {
    // JS time never advances controlled CSS paint. Only this explicit fixture
    // boundary changes the computed renderer state; no product culling is here.
    paint.offset = targetOffset(); paint.transformOverride = null;
    transition(); await frames(); await advance(1600); await frames();
  }
  async function nextBeat() {
    const beat = [...timers.values()].find(task => task.kind === 'timer' && task.ms === 5000);
    assert.ok(beat, 'The original automatic photo cadence must remain scheduled');
    await advance(beat.at - now); await finishExcept();
  }
  return { cleanup, flush, finishExcept, slides, current, snapshot, requests, calls, writes, timers,
    cursor, records, advance, assertExposure, assertLayout, assertPaintExposure, assertSomeFarHidden,
    physicalExposure, paintVisible, visibilityWrites, listenerCount, listenerCallbacks, paintReads,
    transition, settlementCallbacks, assertOwnedCallbacks, frames, settle, nextBeat, offset, get ready() { return ready; },
    generation: n => { generation = n; },
    pinPaint: value => { paint.offset = value; },
    paintTransform: value => { paint.transformOverride = value; },
    resize: (width, height) => {
      viewport.width = width; viewport.height = height;
      for (const fn of [...(windowListeners.get('resize') || [])]) fn.call(window, { type: 'resize', target: window });
    } };
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

test('physical screen envelope covers viewport expansion while initial decode is pending', async () => {
  const r = fixture({ W: 320, H: 200, physicalScreen: { width: 1920, height: 1080 } });
  try {
    await r.finishExcept(q => q.url.startsWith('generated://0/1400/'));
    assert.equal(r.ready, undefined);
    r.resize(1920, 1080);
    await r.finishExcept(); assert.equal(r.ready, true); r.assertExposure(null, 0);
    assert.ok(r.calls.filter(c => c.edge === 1400).length < 12, 'Screen envelope should retain bounded preparation');
  } finally { r.cleanup(); }
});

test('physical screen envelope covers expansion after readiness and subsequent navigation', async () => {
  const r = fixture({ W: 320, H: 200, physicalScreen: { width: 1080, height: 1920 } });
  try {
    await r.finishExcept(); assert.equal(r.ready, true);
    r.resize(1920, 1080); r.assertExposure(null, 0);
    const next = r.cleanup.next(); await r.finishExcept(); assert.equal(await next, true);
    r.assertExposure(0, 1);
    r.resize(1080, 1920); r.assertExposure(0, 1);
    const previous = r.cleanup.previous(); await r.finishExcept(); assert.equal(await previous, true);
    r.assertExposure(1, 0);
  } finally { r.cleanup(); }
});

test('unknown or inconsistent physical screen bounds retain full preparation', async () => {
  for (const physicalScreen of [null, { width: NaN, height: 600 }, { width: 0, height: 600 },
    { width: 500, height: 500 }]) {
    const r = fixture({ physicalScreen });
    try {
      await r.finishExcept(q => q.url.startsWith('generated://11/1400/'));
      assert.equal(r.ready, undefined, 'Untrusted screen metadata must retain the complete decode gate');
      assert.equal(r.calls.filter(c => c.edge === 1400).length, 12);
      await r.finishExcept(); assert.equal(r.ready, true); r.assertExposure(null, 0);
    } finally { r.cleanup(); }
  }
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
    // Keep cadence/decode assertions intact; a bounded owned settlement guard
    // and RAF may coexist with the single photo timer. Pause/disposal still drain all.
    r.assertOwnedCallbacks();
  } finally { r.cleanup(); }
});

// FIRST PHASE ONLY: these contracts intentionally precede any production
// culling. Main owns execution of RED against the corrected decode-only asset.
// Original correctness intent is retained; only the one-timer assertion
// is expanded to permit bounded owned settlement work, never background decoding.
test('image-only culling preserves the original card and shadow paint layers across both wraps', async () => {
  const r = fixture({ controlledMotion: true });
  try {
    await r.finishExcept(); await r.settle();
    for (const direction of [null, 'previous', 'next']) {
      if (direction) {
        const job = r.cleanup[direction](); await r.finishExcept(); assert.equal(await job, true);
        await r.settle();
      }
      assert.ok(r.slides().every(s => r.paintVisible(s)),
        'Culling must keep the decorated card paint topology that preserves exact raster output');
      r.assertSomeFarHidden(r.current(), r.current());
      assert.ok(r.slides().every(s => !s.style.visibility && !s.style.display),
        'The existing card filters, shadows and layout slots must retain their original CSS ownership');
    }
  } finally { r.cleanup(); }
});

test('culling: initial admission suppresses some far cards while preserving all twelve slots', async () => {
  const r = fixture();
  try {
    await r.finishExcept(); assert.equal(r.ready, true);
    assert.equal(r.cursor.i, 12); assert.deepEqual(r.records, []);
    r.assertSomeFarHidden(null, 0);
  } finally { r.cleanup(); }
});

test('culling: an entering hidden card stays hidden through pending or failed preparation', async () => {
  const r = fixture();
  try {
    await r.finishExcept();
    // Hand-derived: w=558, stride=618. Slot 3 is far from center 0 but
    // enters the conservative demand batch on 0->1; the existing tests pin it.
    const entering = r.slides()[3];
    assert.equal(r.paintVisible(entering.children[0]), false, 'An unprepared far image must start hidden');
    const before = r.snapshot(), visibilityCount = r.visibilityWrites.length;
    const next = r.cleanup.next();
    await r.finishExcept(q => q.url.startsWith('generated://3/1400/'));
    assert.equal(r.current(), 0); assert.equal(r.snapshot(), before);
    assert.equal(r.visibilityWrites.length, visibilityCount, 'Partial decode cannot change the painted scene');
    assert.equal(await r.cleanup.previous(), false, 'Busy navigation must remain serialized');
    const request = r.requests.find(q => q.status === 'pending' && q.url.startsWith('generated://3/1400/'));
    assert.ok(request); request.fail(); await r.finishExcept();
    assert.equal(await next, false); assert.equal(r.snapshot(), before);
    assert.equal(r.visibilityWrites.length, visibilityCount);
    r.generation(2); const retry = r.cleanup.next(); await r.finishExcept();
    assert.equal(await retry, true); r.assertPaintExposure(0, 1);
  } finally { r.cleanup(); }
});

test('culling: a shadow-only edge contribution stays visible even when the image border is outside', async () => {
  const r = fixture({ aspects: Array(12).fill(.345) });
  try {
    await r.finishExcept();
    // w=128.34; slot 3's resting side-card border starts at 1020.101px.
    // Its 70px blurred black shadow still extends into the 1000px viewport.
    const borderLeft = 500 + 3 * (128.34 + 60) - 128.34 * .35;
    assert.ok(borderLeft > 1000);
    assert.ok(r.physicalExposure(0, 0).has(3), 'The independent paint oracle must include the shadow edge');
    r.assertPaintExposure(null, 0); r.assertPaintExposure(0, 0);
  } finally { r.cleanup(); }
});

test('culling: both display orientations retain transition interiors and both wraps for every geometry', async () => {
  for (const [W, H, aspects] of [
    [1000, 600, Array(12).fill(.45)], [600, 1000, Array(12).fill(1.7)],
    [1200, 600, Array(12).fill(5)],
    [600, 900, [.4, 1, 5, .7, 2, .2, 4, 1, .5, 2, 3, .8]],
    [1000, 600, [.4]], [600, 1000, [.5, 3]],
  ]) {
    const r = fixture({ W, H, aspects });
    try {
      await r.finishExcept(); r.assertPaintExposure(null, 0);
      let center = 0;
      for (const direction of ['previous', ...Array(aspects.length).fill('next'), 'previous']) {
        const target = (center + (direction === 'next' ? 1 : aspects.length - 1)) % aspects.length;
        const job = r.cleanup[direction](); await r.finishExcept();
        assert.equal(await job, true); assert.equal(r.current(), target);
        r.assertPaintExposure(center, target); center = target;
      }
      assert.equal(r.cursor.i, aspects.length); assert.deepEqual(r.records, []);
    } finally { r.cleanup(); }
  }
});

test('culling: interrupted motion retains every still-live earlier exposure union', async () => {
  const r = fixture({ controlledMotion: true });
  try {
    await r.finishExcept(); r.assertPaintExposure(null, 0);
    const next = r.cleanup.next(); await r.finishExcept(); assert.equal(await next, true);
    await r.advance(100); // Less than the unchanged 1400ms motion duration.
    const another = r.cleanup.next(); await r.finishExcept(); assert.equal(await another, true);
    r.assertPaintExposure(null, 0); r.assertPaintExposure(0, 1); r.assertPaintExposure(1, 2);
    const back = r.cleanup.previous(); await r.finishExcept(); assert.equal(await back, true);
    r.assertPaintExposure(0, 1); r.assertPaintExposure(1, 2); r.assertPaintExposure(2, 1);
    const first = r.cleanup.previous(); await r.finishExcept(); assert.equal(await first, true);
    const wrap = r.cleanup.previous(); await r.finishExcept(); assert.equal(await wrap, true);
    r.assertPaintExposure(0, 11);
    const reverse = r.cleanup.next(); await r.finishExcept(); assert.equal(await reverse, true);
    r.assertPaintExposure(0, 11); r.assertPaintExposure(11, 0);
    const short = r.cleanup.next(); await r.finishExcept(); assert.equal(await short, true);
    r.assertPaintExposure(11, 0); r.assertPaintExposure(0, 1);
  } finally { r.cleanup(); }
});

test('culling: far-card suppression remains safe on expansion before scheduler recovery', async () => {
  for (const pending of [false, true]) {
    const r = fixture({ W: 320, H: 200, physicalScreen: { width: 1080, height: 1920 } });
    try {
      if (pending) {
        await r.finishExcept(q => q.url.startsWith('generated://0/1400/'));
        assert.equal(r.ready, undefined);
      } else { await r.finishExcept(); assert.equal(r.ready, true); }
      r.resize(1920, 1080);
      await r.finishExcept(); assert.equal(r.ready, true);
      // No scene recreation is simulated: this is the existing, resized mode.
      r.assertSomeFarHidden(null, 0);
      const next = r.cleanup.next(); await r.finishExcept(); assert.equal(await next, true);
      r.assertPaintExposure(0, 1);
      r.resize(1080, 1920); r.assertPaintExposure(0, 1);
      const previous = r.cleanup.previous(); await r.finishExcept(); assert.equal(await previous, true);
      r.assertPaintExposure(1, 0);
    } finally { r.cleanup(); }
  }
});

test('culling: pause gates visibility publication alongside images without background work', async () => {
  for (const entering of [false, true]) {
    const r = fixture();
    try {
      if (entering) await r.finishExcept();
      const job = entering ? r.cleanup.next() : r.cleanup.ready;
      await r.flush(); const before = r.snapshot(), visibilityCount = r.visibilityWrites.length;
      r.cleanup.pause(); await r.finishExcept(); await r.advance(2000);
      assert.equal(r.snapshot(), before); assert.equal(r.visibilityWrites.length, visibilityCount);
      assert.equal(r.timers.size, 0);
      r.cleanup.resume(); await r.flush(); assert.equal(await job, true);
      r.assertPaintExposure(entering ? 0 : null, entering ? 1 : 0);
      r.assertOwnedCallbacks();
    } finally { r.cleanup(); }
  }
});

test('culling: disposal revokes late visibility writes and all timer/listener ownership', async () => {
  for (const entering of [false, true]) for (const ignoreAbort of [false, true]) {
    const r = fixture({ ignoreAbort });
    if (entering) await r.finishExcept();
    const job = entering ? r.cleanup.next() : r.cleanup.ready;
    await r.flush(); const before = r.snapshot(), visibilityCount = r.visibilityWrites.length;
    r.cleanup(); r.cleanup(); await r.finishExcept(); assert.equal(await job, false);
    await r.advance(60000); r.cleanup.resume(); await r.flush();
    assert.equal(r.snapshot(), before); assert.equal(r.visibilityWrites.length, visibilityCount);
    assert.equal(r.timers.size, 0); assert.equal(r.listenerCount(), 0);
    assert.ok(r.requests.every(q => q.signal.aborted));
  }
});

test('culling: unknown geometry or physical bounds keep every image and shadow visible', async () => {
  for (const options of [{ uncertain: true }, { physicalScreen: null },
    { physicalScreen: { width: NaN, height: 600 } }, { physicalScreen: { width: 0, height: 600 } },
    { physicalScreen: { width: Infinity, height: 600 } }, { physicalScreen: { width: 500, height: 500 } }]) {
    const r = fixture(options);
    try {
      await r.finishExcept(q => q.url.startsWith('generated://11/1400/'));
      assert.equal(r.ready, undefined); r.assertLayout();
      for (const s of r.slides()) {
        assert.ok(r.paintVisible(s)); assert.ok(r.paintVisible(s.children[0]));
      }
      await r.finishExcept(); assert.equal(r.ready, true);
      const next = r.cleanup.next(); await r.finishExcept(); assert.equal(await next, true);
      for (const s of r.slides()) {
        assert.ok(r.paintVisible(s)); assert.ok(r.paintVisible(s.children[0]));
      }
      r.assertLayout();
    } finally { r.cleanup(); }
  }
});

// Revised first phase: steady suppression must recover after every complete
// pan, not retain all revealed slides forever. Renderer state is independent of
// JS time so wall-clock-only retirement cannot satisfy these safety contracts.
test('culling: verified settlement re-hides decoded far cards after both full wraps', async () => {
  const r = fixture({ controlledMotion: true });
  try {
    await r.finishExcept(); await r.settle();
    for (const [direction, from, to] of [['previous', 0, 11], ['next', 11, 0]]) {
      const job = r.cleanup[direction](); await r.finishExcept(); assert.equal(await job, true);
      r.assertPaintExposure(from, to);
      const images = r.slides().map(s => s.children[0].style.backgroundImage);
      assert.ok(images.every(Boolean), 'A full wrap prepares all intermediate resources');
      await r.settle();
      r.assertSomeFarHidden(to, to);
      assert.deepEqual(r.slides().map(s => s.children[0].style.backgroundImage), images,
        'Re-hiding must retain decoded resources and all layout slots');
    }
  } finally { r.cleanup(); }
});

test('culling: automatic prolonged playback keeps suppressing far cards after repeated wraps', async () => {
  const r = fixture({ controlledMotion: true });
  try {
    await r.finishExcept(); await r.settle();
    for (let beat = 1; beat <= 25; beat++) {
      const from = r.current(); await r.nextBeat(); const to = r.current();
      assert.equal(to, (from + 1) % 12); r.assertPaintExposure(from, to);
      await r.settle(); r.assertPaintExposure(to, to);
      // First wrap has admitted every photo; lifetime reveal retention would
      // now leave all slots visible and fail every following steady state.
      if (beat >= 12) r.assertSomeFarHidden(to, to);
      r.assertOwnedCallbacks();
    }
    assert.equal(r.calls.filter(c => c.edge === 1400).length, 12,
      'Steady culling must not re-resolve or decode already-prepared photos');
    assert.equal(r.cursor.i, 12); assert.deepEqual(r.records, []);
  } finally { r.cleanup(); }
});

test('culling: elapsed JS deadlines and false end events cannot settle moving or unreadable CSS paint', async () => {
  const r = fixture({ controlledMotion: true });
  try {
    await r.finishExcept(); await r.settle();
    const wrap = r.cleanup.previous(); await r.finishExcept(); assert.equal(await wrap, true);
    r.pinPaint((r.offset(0) + r.offset(11)) / 2);
    const before = r.snapshot(), count = r.visibilityWrites.length;
    r.transition(); await r.frames(); await r.advance(3500); await r.frames();
    assert.equal(r.snapshot(), before); assert.equal(r.visibilityWrites.length, count);
    r.assertPaintExposure(0, 11);
    assert.equal(r.settlementCallbacks().length, 0,
      'An exhausted bounded guard must retain the union without timer/RAF polling');
    r.paintTransform('matrix(1, 0, 0, 1, NaN, 0)');
    r.transition(); await r.frames();
    assert.equal(r.snapshot(), before); assert.equal(r.visibilityWrites.length, count);
    r.paintTransform(null); await r.settle(); r.assertPaintExposure(11, 11);
  } finally { r.cleanup(); }
});

test('culling: bubbled, wrong-property and cancelled events cannot certify track settlement', async () => {
  const r = fixture({ controlledMotion: true });
  try {
    await r.finishExcept(); await r.settle();
    const wrap = r.cleanup.previous(); await r.finishExcept(); assert.equal(await wrap, true);
    r.pinPaint(r.offset(11)); // Endpoint is readable, but the events below are unrelated.
    const before = r.snapshot(), count = r.visibilityWrites.length;
    for (const event of [{ target: r.slides()[0] }, { propertyName: 'opacity' },
      { propertyName: 'filter' }, { type: 'transitioncancel' }]) {
      r.transition(event); await r.frames();
      assert.equal(r.snapshot(), before); assert.equal(r.visibilityWrites.length, count);
    }
    r.transition(); await r.frames(); r.assertPaintExposure(11, 11);
  } finally { r.cleanup(); }
});

test('culling: paused settlement cannot mutate visibility whether CSS freezes or continues', async () => {
  for (const cssContinues of [false, true]) {
    const r = fixture({ controlledMotion: true });
    try {
      await r.finishExcept(); await r.settle();
      const wrap = r.cleanup.previous(); await r.finishExcept(); assert.equal(await wrap, true);
      r.pinPaint((r.offset(0) + r.offset(11)) / 2);
      const late = r.settlementCallbacks(); r.cleanup.pause();
      const before = r.snapshot(), count = r.visibilityWrites.length;
      if (cssContinues) r.pinPaint(r.offset(11));
      r.transition(); for (const fn of late) fn(); await r.flush(); await r.advance(10000);
      assert.equal(r.snapshot(), before); assert.equal(r.visibilityWrites.length, count);
      assert.equal(r.timers.size, 0);
      r.cleanup.resume(); await r.flush();
      if (!cssContinues) {
        await r.advance(1600); await r.frames();
        assert.equal(r.snapshot(), before); r.assertPaintExposure(0, 11);
      }
      await r.settle(); r.assertPaintExposure(11, 11); r.assertOwnedCallbacks();
    } finally { r.cleanup(); }
  }
});

test('culling: pending navigation and supersession revoke old waits, events and verification frames', async () => {
  const r = fixture({ controlledMotion: true });
  try {
    await r.finishExcept(); await r.settle();
    const wrap = r.cleanup.previous(); await r.finishExcept(); assert.equal(await wrap, true);
    const oldWaits = r.settlementCallbacks();
    r.pinPaint(r.offset(11)); r.transition(); await r.advance(16);
    const oldFrames = r.settlementCallbacks();
    const next = r.cleanup.next();
    await r.finishExcept(q => q.url.startsWith('generated://0/1000/'));
    const pending = r.snapshot(), count = r.visibilityWrites.length;
    for (const fn of [...oldWaits, ...oldFrames]) fn();
    r.transition(); await r.frames();
    assert.equal(r.snapshot(), pending); assert.equal(r.visibilityWrites.length, count);
    assert.equal(await r.cleanup.previous(), false);
    await r.finishExcept(); assert.equal(await next, true);
    const committed = r.snapshot(), committedCount = r.visibilityWrites.length;
    // Renderer still paints the old endpoint; a stale frame must not cull for it.
    for (const fn of [...oldWaits, ...oldFrames]) fn(); await r.flush(); await r.frames();
    assert.equal(r.snapshot(), committed); assert.equal(r.visibilityWrites.length, committedCount);
    r.assertPaintExposure(11, 0); await r.settle(); r.assertPaintExposure(0, 0);
  } finally { r.cleanup(); }
});

test('culling: resize revokes old settlement proof while the physical-screen union stays safe', async () => {
  const r = fixture({ W: 320, H: 200, physicalScreen: { width: 1920, height: 1080 }, controlledMotion: true });
  try {
    await r.finishExcept(); await r.settle();
    const wrap = r.cleanup.previous(); await r.finishExcept(); assert.equal(await wrap, true);
    r.pinPaint(r.offset(11)); r.transition(); await r.advance(16);
    const oldProof = r.settlementCallbacks();
    r.resize(1920, 1080); r.pinPaint((r.offset(0) + r.offset(11)) / 2);
    const before = r.snapshot(), count = r.visibilityWrites.length;
    for (const fn of oldProof) fn(); await r.flush(); await r.frames();
    assert.equal(r.snapshot(), before); assert.equal(r.visibilityWrites.length, count);
    r.assertPaintExposure(0, 11);
    await r.settle(); r.assertPaintExposure(11, 11);
    r.resize(1080, 1920); r.assertPaintExposure(11, 11);
    const decodes = r.calls.filter(c => c.edge === 1400).length;
    await r.settle(); assert.equal(r.calls.filter(c => c.edge === 1400).length, decodes);
  } finally { r.cleanup(); }
});

test('culling: disposal revokes queued settlement frames and detached listener callbacks', async () => {
  const r = fixture({ controlledMotion: true });
  await r.finishExcept(); await r.settle();
  const wrap = r.cleanup.previous(); await r.finishExcept(); assert.equal(await wrap, true);
  r.pinPaint(r.offset(11)); r.transition(); await r.flush();
  const late = r.settlementCallbacks(), listeners = r.listenerCallbacks();
  r.cleanup(); const before = r.snapshot(), count = r.visibilityWrites.length;
  for (const fn of late) fn();
  for (const { fn, type, owner } of listeners)
    fn.call(owner, { type, target: owner, currentTarget: owner, propertyName: 'transform' });
  await r.flush(); await r.advance(60000); r.cleanup.resume(); await r.flush();
  assert.equal(r.snapshot(), before); assert.equal(r.visibilityWrites.length, count);
  assert.equal(r.timers.size, 0); assert.equal(r.listenerCount(), 0);
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
