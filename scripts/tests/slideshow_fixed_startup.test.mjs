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
const html = readFileSync(new URL('index.html', assets), 'utf8');
const fixed = ['kenBurns', 'framed', 'push', 'memories', 'polaroid'];

// Execute every production script in index order. Only browser/host IO and time
// are controlled: photo normalization, decode metadata, selection, renderers,
// scheduler, entry lifecycle, and readiness are the shipped implementations.
function fixture({ mode = 'framed', paused = false, known = false, random = .5,
  width = 1200, height = 1800, face = true } = {}) {
  let now = 0, nextId = 0;
  const timers = new Map(), handlers = new Map(), images = [], requests = [], reports = [];
  class Element {
    constructor() {
      this.children = []; this.className = ''; this.style = {};
      Object.defineProperty(this.style, 'setProperty', { value(key, value) { this[key] = value; } });
    }
    get classList() {
      return {
        contains: name => this.className.split(/\s+/).includes(name),
        add: (...names) => { this.className = [...new Set([...this.className.split(/\s+/), ...names])].join(' ').trim(); },
        remove: (...names) => { this.className = this.className.split(/\s+/).filter(name => !names.includes(name)).join(' '); },
        toggle: (name, on) => { if (on) this.classList.add(name); else this.classList.remove(name); },
      };
    }
    appendChild(child) {
      child.remove(); child.parent = this; this.children.push(child); return child;
    }
    remove() {
      if (this.parent) this.parent.children = this.parent.children.filter(child => child !== this);
      this.parent = null;
    }
    set innerHTML(value) {
      assert.equal(value, '');
      this.children.forEach(child => { child.parent = null; }); this.children = [];
    }
    get offsetWidth() { return 2160; }
    get parentNode() { return this.parent; }
    getAnimations() { return []; }
  }
  class Image {
    constructor() { this.naturalWidth = 0; this.naturalHeight = 0; images.push(this); }
    set src(value) { this.url = value; }
    get src() { return this.url; }
    decode() { return Promise.resolve(); }
    complete(w = width, h = height) {
      this.naturalWidth = w; this.naturalHeight = h; this.onload?.();
    }
  }
  const elements = new Map(['stage', 'loading', 'chrome-clock', 'chrome-scrim', 'chrome-mode']
    .map(id => [id, new Element()]));
  const source = Array.from({ length: 8 }, (_, i) => ({
    url: `file:///sample/${i}.jpg`, ...(known ? { w: width, h: height } : {}),
    ...(face ? { fx: .5, fy: .2, pw: .8 } : {}),
  }));
  function schedule(fn, ms) {
    const id = ++nextId; timers.set(id, { fn, at: now + ms }); return id;
  }
  const context = vm.createContext({
    Image, AbortController, URLSearchParams,
    Android: {
      getPhotos: () => JSON.stringify(source), getMode: () => mode, getIntervalSec: () => 5,
      scaledUrlForSize: (url, edge) => {
        requests.push({ url, edge }); return url.replace('/sample/', `/scaled-${edge}/`);
      },
      reportPresentedStyle: id => reports.push(id),
    },
    location: { search: `?nativeClock=1&hostPaused=${paused ? 1 : 0}` },
    document: { createElement: () => new Element(), getElementById: id => elements.get(id) },
    innerWidth: 2160, innerHeight: 1440, screen: { width: 2160, height: 1440 },
    performance: { now: () => now }, Date: class extends Date { static now() { return now; } },
    Math: Object.assign(Object.create(Math), { random: () => random }),
    setTimeout: schedule, clearTimeout: id => timers.delete(id), clearInterval: id => timers.delete(id),
    requestAnimationFrame: fn => schedule(fn, 16), cancelAnimationFrame: id => timers.delete(id),
    addEventListener: (name, fn) => handlers.set(name, fn), removeEventListener: name => handlers.delete(name),
    getComputedStyle: node => ({ opacity: node.classList.contains('on') ? '1' : '0', transform: 'none' }),
    console: { info() {}, warn() {} },
  });
  context.window = context;
  for (const match of html.matchAll(/<script(?: src="([^"]+)")?>([\s\S]*?)<\/script>/g)) {
    vm.runInContext(match[1] ? readFileSync(new URL(match[1], assets), 'utf8') : match[2], context,
      { filename: match[1] || 'index.html' });
  }
  async function flush() { for (let i = 0; i < 100; i++) await Promise.resolve(); }
  async function tick(ms) {
    await flush(); const end = now + ms;
    for (;;) {
      const next = [...timers].filter(([, task]) => task.at <= end)
        .sort((a, b) => a[1].at - b[1].at || a[0] - b[0])[0];
      if (!next) break;
      const [id, task] = next; timers.delete(id); now = task.at; task.fn(); await flush();
    }
    now = end; await flush();
  }
  function nodes(root = elements.get('stage')) { return [root, ...root.children.flatMap(nodes)]; }
  const active = () => images.filter(image => image.src && image.onload);
  async function finishScene() {
    for (let i = 0; i < 12; i++) {
      active().forEach(image => image.complete()); await flush();
      if (elements.get('loading').classList.contains('hide')) break;
      await tick(16);
    }
  }
  return { context, requests, reports, images, elements, timers, active, nodes, flush, tick, finishScene,
    close: () => handlers.get('pagehide')?.() };
}

for (const mode of fixed) test(`${mode}: unknown metadata starts with display decode and no orientation probe`, async () => {
  const f = fixture({ mode });
  try {
    await f.flush();
    assert.equal(f.requests.length, 1, 'The first admitted request is the selected display photo');
    assert.equal(f.requests[0].edge, mode === 'framed' ? 1800 : mode === 'polaroid' ? 1400 : 2200);
    assert.equal(f.elements.get('stage').children.length, 1);
    assert.deepEqual(f.reports, [], 'Style cannot be reported before decode');
    await f.finishScene();
    assert.deepEqual(f.reports, [mode]);
    assert.equal(f.requests.some(request => request.edge === 320), false);
    assert.equal(f.requests.length, mode === 'polaroid' ? 6 : 1);
    assert.ok(f.context.PHOTOS.some(photo => photo.measured && photo.w / photo.h === 2 / 3));
    if (mode === 'framed') {
      const print = f.nodes().find(node => node.classList.contains('ss-print') && node.style.backgroundImage?.startsWith('url('));
      assert.deepEqual([print.style.width, print.style.height, print.style.backgroundSize], ['778.2399999999999px', '1167.36px', 'contain']);
    }
  } finally { f.close(); }
});

for (const mode of fixed) test(`${mode}: retained paused host admits no work until resume and close is terminal`, async () => {
  const f = fixture({ mode, paused: true });
  await f.tick(5000);
  assert.equal(f.requests.length, 0);
  assert.equal(f.elements.get('stage').children.length, 0);
  f.context.immortalSlideshow.resume(); await f.flush();
  assert.equal(f.requests.length, 1);
  const late = f.active()[0].onload;
  f.close(); late(); await f.tick(50000);
  assert.equal(f.active().length, 0);
  assert.equal(f.requests.length, 1);
  assert.equal(f.context.PHOTOS.every(photo => !photo.measured), true);
  assert.equal(f.timers.size, 0);
  assert.deepEqual(f.reports, []);
});

test('fixed display decode while paused records metadata but waits for resume to publish', async () => {
  const f = fixture({ mode: 'framed' });
  try {
    await f.flush();
    assert.equal(f.requests[0].edge, 1800, 'Pause must hold a display preparation, not an orientation probe');
    const display = f.active()[0];
    f.context.immortalSlideshow.pause();
    display.complete(); await f.tick(5000);
    assert.equal(f.context.PHOTOS.filter(photo => photo.measured).length, 1);
    assert.deepEqual(f.reports, []);
    assert.equal(f.requests.length, 1);
    assert.equal(f.nodes().some(node => node.style.backgroundImage?.startsWith('url(')), false);
    f.context.immortalSlideshow.resume(); await f.tick(16);
    assert.deepEqual(f.reports, ['framed']);
    assert.equal(f.requests.length, 1);
  } finally { f.close(); }
});

test('fixed geometry and readiness wait for display decode completion rather than load alone', async () => {
  const f = fixture({ mode: 'framed' });
  try {
    await f.flush();
    assert.equal(f.requests[0].edge, 1800);
    const display = f.active()[0];
    let finishDecode;
    display.decode = () => new Promise(resolve => { finishDecode = resolve; });
    display.complete(); await f.tick(1000);
    assert.equal(f.context.PHOTOS.every(photo => !photo.measured), true);
    assert.equal(f.nodes().some(node => node.style.backgroundImage?.startsWith('url(')), false);
    assert.deepEqual(f.reports, []);
    finishDecode(); await f.tick(16);
    assert.equal(f.context.PHOTOS.filter(photo => photo.measured).length, 1);
    assert.deepEqual(f.reports, ['framed']);
    assert.equal(f.requests.length, 1);
  } finally { f.close(); }
});

test('failed fixed display load retries through display decode without orientation requests', async () => {
  const f = fixture({ mode: 'framed' });
  try {
    await f.flush(); f.active()[0].onerror(); await f.flush();
    assert.deepEqual(f.reports, []);
    assert.equal(f.context.PHOTOS.every(photo => !photo.measured), true);
    await f.tick(5000);
    assert.equal(f.requests.length, 2);
    assert.equal(f.requests.every(request => request.edge === 1800), true);
    await f.finishScene(); assert.deepEqual(f.reports, ['framed']);
  } finally { f.close(); }
});

for (const mode of ['shuffle', 'diptych', 'panorama', 'cinema', 'heroSidebar', 'mosaic', 'filmstrip', 'galleryWall'])
  test(`${mode}: retains bounded orientation preparation before selecting its first scene`, async () => {
    const f = fixture({ mode });
    try {
      await f.flush();
      assert.deepEqual(f.requests.map(request => request.edge), [320, 320]);
      assert.equal(f.elements.get('stage').children.length, 0);
      const late = f.active()[0].onload;
      f.context.immortalSlideshow.pause(); await f.flush();
      assert.equal(f.active().length, 0);
      await f.tick(5000); assert.equal(f.requests.length, 2);
      late(); assert.equal(f.context.PHOTOS.every(photo => !photo.measured), true);
      f.context.immortalSlideshow.resume(); await f.flush();
      assert.equal(f.active().length, 2);
      assert.equal(f.requests.length, 4);
    } finally { f.close(); }
  });

test('Diptych receives portrait buckets before selection and prepares a distinct portrait pair', async () => {
  const f = fixture({ mode: 'diptych', random: .9 });
  try {
    await f.flush();
    for (let i = 0; i < 3; i++) { f.active().forEach(image => image.complete()); await f.flush(); }
    const display = f.requests.filter(request => request.edge === 1800);
    assert.equal(display.length, 2);
    assert.notEqual(display[0].url, display[1].url);
    assert.equal(f.context.SS.counts(f.context.PHOTOS).portrait, 6);
    await f.finishScene();
    assert.deepEqual(f.reports, ['diptych']);
    assert.equal(f.nodes().filter(node => node.classList.contains('ss-cell')).length, 2);
  } finally { f.close(); }
});

test('Shuffle still sees measured portraits and can select Hero + Sidebar on its first beat', async () => {
  const f = fixture({ mode: 'shuffle', random: .99 });
  try {
    await f.flush();
    for (let i = 0; i < 3; i++) { f.active().forEach(image => image.complete()); await f.flush(); }
    assert.equal(f.requests.filter(request => request.edge === 320).length, 6);
    assert.equal(f.context.SS.counts(f.context.PHOTOS).portrait, 6);
    assert.equal(f.requests.at(-1).edge, 1800, 'The hero is prepared only after the orientation batch');
    f.active()[0].complete(1800, 1200); await f.flush();
    assert.equal(f.requests.at(-1).edge, 1100);
    await f.finishScene();
    assert.deepEqual(f.reports, ['heroSidebar']);
    assert.equal(f.nodes().filter(node => node.classList.contains('ss-cell')).length, 3);
  } finally { f.close(); }
});

// Removing the gate must preserve the geometry/motion of real known metadata,
// including the long-axis Ken Burns choice and the complete Polaroid paper fit.
for (const mode of fixed) test(`${mode}: display-derived dimensions match known metadata geometry and resource admission`, async () => {
  for (const [width, height] of [[1200, 1800], [6000, 1200]]) {
    const known = fixture({ mode, known: true, width, height, face: false, random: .9 });
    const recovered = fixture({ mode, width, height, face: false, random: .9 });
    try {
      await known.finishScene(); await recovered.finishScene();
      assert.deepEqual(known.reports, [mode]); assert.deepEqual(recovered.reports, [mode]);
      const snapshot = f => f.nodes().map(node => ({ classes: node.className, style: { ...node.style } }));
      assert.deepEqual(snapshot(recovered), snapshot(known));
      assert.deepEqual(recovered.requests, known.requests);
    } finally { known.close(); recovered.close(); }
  }
});

test('Memories builds its later collage from display dimensions without adding probes', async () => {
  const f = fixture({ mode: 'memories' });
  try {
    await f.finishScene();
    for (let beat = 2; beat <= 6; beat++) {
      await f.tick(5000);
      f.active().forEach(image => image.complete()); await f.flush();
    }
    const cards = f.nodes().filter(node => node.classList.contains('ss-memory-card'));
    assert.equal(cards.length, 3);
    for (const card of cards) {
      const width = parseFloat(card.style.width) - 24, height = parseFloat(card.style.height) - 24;
      assert.ok(Math.abs(width / height - 2 / 3) < .000001);
    }
    assert.equal(f.requests.filter(request => request.edge === 1100).length, 3);
    assert.equal(f.requests.some(request => request.edge === 320), false);
  } finally { f.close(); }
});
