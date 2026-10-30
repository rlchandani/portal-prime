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
const modes = ['kenBurns', 'diptych', 'framed', 'galleryWall', 'push', 'memories',
  'panorama', 'polaroid', 'mosaic', 'filmstrip', 'cinema', 'heroSidebar'];
const affected = ['diptych', 'galleryWall', 'polaroid', 'mosaic', 'filmstrip'];
const expectedEdges = { kenBurns: [2200], diptych: [1800], framed: [1800], galleryWall: [1200],
  push: [2200], memories: [2200], panorama: [2400], polaroid: [1400], mosaic: [1200],
  filmstrip: [1000, 1400], cinema: [2400], heroSidebar: [1100, 1800] };

// Synthetic DOM, clock and image transport only. Execute the actual photo resolver,
// preload/decode helper, modes and scheduler; this fixture cannot measure rasterization.
function fixture({ mode = 'galleryWall', count = 12, changing = true, random = .5, unknown = false } = {}) {
  let now = 0, nextId = 1, available = true, generation = 1;
  const pending = new Map(), images = [], decoded = new Set(), writes = [], calls = [];
  const resolutions = new Map();
  function schedule(fn, ms) {
    const id = nextId++;
    pending.set(id, { fn, at: now + Number(ms || 0) });
    return id;
  }
  class Element {
    constructor() {
      this.children = []; this.names = ''; this.style = { setProperty(key, value) { this[key] = String(value); } };
      let background = '';
      Object.defineProperty(this.style, 'backgroundImage', { enumerable: true,
        get: () => background,
        set: value => {
          background = value;
          const url = /^url\("(.*)"\)$/.exec(value)?.[1];
          if (url) writes.push({ node: this, url, prepared: decoded.has(url) });
        },
      });
    }
    get className() { return this.names; }
    set className(value) { this.names = value; }
    get classList() {
      return {
        contains: name => this.names.split(/\s+/).includes(name),
        add: (...names) => { this.names = [...new Set([...this.names.split(/\s+/), ...names])].join(' ').trim(); },
        remove: (...names) => { this.names = this.names.split(/\s+/).filter(name => !names.includes(name)).join(' '); },
        toggle: (name, on) => { if (on == null) on = !this.classList.contains(name); on ? this.classList.add(name) : this.classList.remove(name); },
      };
    }
    get parentNode() { return this.parent; }
    appendChild(child) {
      child.remove(); child.parent = this; this.children.push(child); return child;
    }
    remove() { if (this.parent) this.parent.children = this.parent.children.filter(child => child !== this); this.parent = null; }
    set innerHTML(value) { assert.equal(value, ''); this.children.forEach(child => { child.parent = null; }); this.children = []; }
    get offsetWidth() { return 100; }
    getAnimations() { return []; }
  }
  class Image {
    constructor() { images.push(this); this.naturalWidth = 1600; this.naturalHeight = 1067; this.state = 'new'; }
    set src(value) {
      this.url = value; this.state = value ? 'loading' : 'cancelled';
      const id = /(?:copy|photo)-(\d+)/.exec(value)?.[1];
      if (id != null) { this.naturalWidth = photos[id].w; this.naturalHeight = photos[id].h; }
    }
    decode() { this.state = 'decoding'; return new Promise((resolve, reject) => { this.finish = resolve; this.reject = reject; }); }
    load() { if (this.state === 'loading') this.onload?.(); }
    complete() { if (this.state === 'decoding') { decoded.add(this.url); this.state = 'decoded'; this.finish(); } }
    fail() { this.onerror?.(); }
  }
  const photos = Array.from({ length: count }, (_, i) => ({ url: `file:///synthetic/photo-${i}.jpg`,
    w: i % 3 === 0 ? 1000 : 1600, h: i % 3 === 0 ? 1500 : 1067,
    fx: .5, fy: .2, pw: .7 }));
  const context = vm.createContext({ Image, AbortController, document: { createElement: () => new Element() },
    innerWidth: 2160, innerHeight: 1440, screen: { width: 2160, height: 1440 },
    getComputedStyle: node => ({ opacity: node.classList.contains('on') ? '1' : '0' }),
    setTimeout: schedule, clearTimeout: id => pending.delete(id),
    requestAnimationFrame: fn => schedule(fn, 16), cancelAnimationFrame: id => pending.delete(id),
    addEventListener() {}, removeEventListener() {},
    Date: class extends Date { static now() { return now; } },
    Math: Object.assign(Object.create(Math), { random: () => random }),
    Android: {
      getPhotos: () => JSON.stringify(unknown ? photos.map(p => ({ ...p, w: 0, h: 0 })) : photos),
      isWatching: () => false,
      scaledUrlForSize: (url, edge) => {
        calls.push({ url, edge });
        const key = `${url}|${edge}`, revision = (resolutions.get(key) || 0) + 1;
        resolutions.set(key, revision);
        if (!available) return '';
        return `file:///synthetic/copy-${photos.findIndex(p => p.url === url)}-${edge}-g${generation}-r${changing ? revision : 1}.jpg`;
      },
    },
  });
  context.window = context;
  for (const file of ['photos.js', 'lib-engine.js', 'modes.js', 'mode-impls.js', 'scheduler.js']) {
    vm.runInContext(readFileSync(new URL(file, assets), 'utf8'), context, { filename: file });
  }
  const stage = new Element();
  let engine;
  async function flush() { for (let i = 0; i < 60; i++) await Promise.resolve(); }
  async function tick(ms) {
    await flush();
    const end = now + ms;
    for (;;) {
      const entry = [...pending].filter(([, task]) => task.at <= end).sort((a, b) => a[1].at - b[1].at || a[0] - b[0])[0];
      if (!entry) break;
      const [id, task] = entry; pending.delete(id); now = task.at;
      task.fn(); await flush();
    }
    now = end; await flush();
  }
  async function loadImages() { await flush(); images.forEach(image => image.load()); await flush(); }
  async function finishImages() { images.forEach(image => image.complete()); await flush(); }
  async function drain() {
    for (let i = 0; i < 20; i++) { await loadImages(); await finishImages(); await tick(20); }
  }
  function nodes(root = stage) { return [root, ...root.children.flatMap(child => nodes(child))]; }
  function assertPrepared(from = 0) {
    const publications = writes.slice(from);
    assert.ok(publications.length, 'No resources were published');
    assert.deepEqual(publications.filter(write => !write.prepared).map(write => write.url), [],
      'A background used a resource that had not completed the actual decode gate');
  }
  return { context, stage, images, pending, writes, calls, nodes, flush, tick, drain, loadImages, finishImages, assertPrepared,
    setAvailable(value) { available = value; }, setGeneration(value) { generation = value; },
    start() { engine = context.SS.startEngine({ stage, mode, intervalSec: 30 }); return engine; },
    stop() { engine?.stop(); },
  };
}

test('Diptych prepares exactly one whole-photo resource per portrait pane with no blurred copy', async () => {
  const r = fixture({ mode: 'diptych', random: .9 });
  try {
    r.start(); await r.drain();
    const visible = r.stage.children[0].children.find(frame => frame.classList.contains('on'));
    assert.equal(visible.children.length, 2);
    assert.equal(r.images.length, 2);
    assert.equal(r.calls.length, 2);
    assert.equal(r.nodes().filter(node => node.classList.contains('ss-fill')).length, 0);
    assert.ok(visible.children.every(cell => cell.children.length === 1 &&
      cell.children[0].style.backgroundSize === 'contain'));
    r.assertPrepared();
  } finally { r.stop(); }
});

test('Filmstrip sizes previously unknown portraits from the actual display decode before promotion', async () => {
  const r = fixture({ mode: 'filmstrip', unknown: true });
  try {
    r.start(); await r.drain();
    const cards = r.nodes().filter(node => node.classList.contains('ss-slide'));
    assert.ok(cards.length >= 5);
    for (const card of cards) {
      const id = /(?:copy|photo)-(\d+)/.exec(card.children[0].style.backgroundImage)[1];
      const expectedAspect = Number(id) % 3 === 0 ? 1000 / 1500 : 1600 / 1067;
      assert.ok(Math.abs(parseFloat(card.style.width) - 1440 * .62 * expectedAspect) < .01);
    }
    r.assertPrepared();
  } finally { r.stop(); }
});

test('Gallery Wall publishes geometry from recovered portrait dimensions', async () => {
  const r = fixture({ mode: 'galleryWall', unknown: true });
  try {
    r.start(); await r.drain();
    const cells = r.nodes().filter(node => node.classList.contains('ss-gcell'));
    let portraits = 0;
    for (const cell of cells) {
      const image = cell.children.find(node => node.style.backgroundImage.startsWith('url('));
      const id = /(?:copy|photo)-(\d+)/.exec(image.style.backgroundImage)[1];
      const aspect = Number(id) % 3 === 0 ? 1000 / 1500 : 1600 / 1067;
      if (Number(id) % 3 === 0) portraits++;
      assert.ok(Math.abs(parseFloat(cell.style.width) / parseFloat(cell.style.height) - aspect) < .01);
    }
    assert.ok(portraits > 0);
    r.assertPrepared();
  } finally { r.stop(); }
});

// A resolver changes its result on a second lookup of the same requested resource.
// Catches both re-resolution after decode and publishing a hidden background before decoding it.
for (const mode of modes) {
  test(`${mode}: first composition publishes only its exact decoded resources`, async () => {
    const r = fixture({ mode });
    try {
      r.start(); await r.drain();
      assert.ok(r.stage.children[0].classList.contains('on'), 'Initial scene never promoted');
      r.assertPrepared();
      assert.deepEqual([...new Set(r.calls.map(call => call.edge))].sort((a, b) => a - b), expectedEdges[mode]);
      assert.equal(new Set(r.calls.map(call => `${call.url}|${call.edge}`)).size, r.calls.length,
        'The same requested resource was resolved again within the initial preparation');
    } finally { r.stop(); }
  });
}

// Initial compositions alone miss the Gallery/Mosaic swap paths and subsequent Polaroid drops.
for (const mode of ['diptych', 'galleryWall', 'mosaic', 'polaroid']) {
  test(`${mode}: a subsequent swap resolves fresh resources and carries them through decode`, async () => {
    const r = fixture({ mode });
    try {
      r.start(); await r.drain();
      const before = r.writes.length;
      r.setGeneration(2); await r.tick(30000); await r.drain();
      r.assertPrepared(before);
      assert.ok(r.writes.slice(before).every(write => write.url.includes('-g2-')));
    } finally { r.stop(); }
  });
}

test('Memories: the sixth beat carries the three decoded collage resources', async () => {
  const r = fixture({ mode: 'memories' });
  try {
    r.start(); await r.drain();
    for (let i = 0; i < 4; i++) { await r.tick(30000); await r.drain(); }
    const before = r.writes.length;
    await r.tick(30000); await r.drain();
    r.assertPrepared(before);
    assert.equal(r.writes.slice(before).length, 3);
    assert.ok(r.writes.slice(before).every(write => write.node.classList.contains('breathe')));
  } finally { r.stop(); }
});

test('Mosaic: exchanging two occupied tiles carries each corresponding prepared resource', async () => {
  const r = fixture({ mode: 'mosaic', count: 9 });
  try {
    r.start(); await r.drain();
    const before = r.writes.length;
    const photoId = value => /(?:copy|photo)-(\d+)[-.]/.exec(value)[1];
    const previous = new Map(r.nodes().filter(node => node.classList.contains('ss-mcell'))
      .map(cell => [cell, photoId(cell.children.at(-1).style.backgroundImage)]));
    r.setGeneration(2); await r.tick(30000); await r.drain();
    r.assertPrepared(before);
    const exchanged = r.writes.slice(before);
    assert.equal(exchanged.length, 2);
    assert.ok(exchanged.every(write => write.url.includes('-g2-')));
    assert.equal(photoId(exchanged[0].url), previous.get(exchanged[1].node.parent));
    assert.equal(photoId(exchanged[1].url), previous.get(exchanged[0].node.parent));
  } finally { r.stop(); }
});

test('Filmstrip: next and previous freshly prepare the backdrop while retaining decoded strip resources', async () => {
  const r = fixture({ mode: 'filmstrip' });
  try {
    const engine = r.start(); await r.drain();
    const first = r.nodes().find(node => node.classList.contains('center'));
    const strip = r.nodes().filter(node => node.classList.contains('ss-slide')).map(node => node.children[0].style.backgroundImage);
    let before = r.writes.length;
    r.setGeneration(2); const next = engine.next(); await r.drain(); assert.equal(await next, true);
    r.assertPrepared(before);
    assert.ok(r.writes.slice(before).every(write => write.url.includes('-1000-g2-')));
    assert.notEqual(r.nodes().find(node => node.classList.contains('center')), first);
    before = r.writes.length;
    r.setGeneration(3); const previous = engine.previous(); await r.drain(); assert.equal(await previous, true);
    r.assertPrepared(before);
    assert.equal(r.nodes().find(node => node.classList.contains('center')), first);
    assert.deepEqual(r.nodes().filter(node => node.classList.contains('ss-slide')).map(node => node.children[0].style.backgroundImage), strip);
  } finally { r.stop(); }
});

// No permanent URL memo: the same photo must fall back to its original after eviction,
// and a new derivative becomes eligible only through a later load/decode operation.
for (const mode of ['mosaic', 'polaroid', 'filmstrip']) {
  test(`${mode}: eviction and rebuilding revalidate even a previously displayed photo`, async () => {
    const r = fixture({ mode, count: mode === 'mosaic' ? 6 : 1, changing: false });
    try {
      const engine = r.start(); await r.drain();
      const before = r.writes.length;
      r.setAvailable(false);
      let navigation;
      if (mode === 'filmstrip') navigation = engine.next(); else await r.tick(30000);
      await r.loadImages();
      assert.equal(r.writes.length, before, 'Replacement published before original fallback decoded');
      assert.ok(r.images.some(image => image.state === 'decoding' && image.url.startsWith('file:///synthetic/photo-')));
      await r.drain(); if (navigation) assert.equal(await navigation, true);
      r.assertPrepared(before);
      assert.ok(r.writes.slice(before).every(write => write.url.startsWith('file:///synthetic/photo-')));
      const rebuilt = r.writes.length;
      r.setAvailable(true); r.setGeneration(2);
      if (mode === 'filmstrip') navigation = engine.next(); else await r.tick(30000);
      await r.drain(); if (navigation) assert.equal(await navigation, true);
      r.assertPrepared(rebuilt);
      assert.ok(r.writes.slice(rebuilt).every(write => write.url.includes('-g2-')));
    } finally { r.stop(); }
  });
}

test('an evicted in-flight resource fails admission; retry decodes the original before replacing the current scene', async () => {
  const r = fixture({ changing: false });
  try {
    const engine = r.start(); await r.drain();
    const current = r.stage.children[0], before = r.writes.length;
    engine.next(); await r.flush();
    r.setAvailable(false);
    r.images.filter(image => image.state === 'loading').forEach(image => image.fail());
    await r.flush();
    assert.equal(r.stage.children.length, 1);
    assert.equal(r.stage.children[0], current);
    await r.tick(5000); await r.loadImages();
    assert.equal(r.writes.length, before);
    assert.equal(r.stage.children[0], current);
    assert.ok(r.images.some(image => image.state === 'decoding' && image.url.startsWith('file:///synthetic/photo-')));
    await r.drain(); await r.tick(1800);
    r.assertPrepared(before);
    assert.equal(r.stage.children.length, 1);
    assert.notEqual(r.stage.children[0], current);
  } finally { r.stop(); }
});

test('a derivative becoming ready during an original decode is used only by a subsequent preparation', async () => {
  const r = fixture({ mode: 'galleryWall', changing: false });
  try {
    r.setAvailable(false); r.start(); await r.loadImages();
    r.setAvailable(true);
    await r.drain(); r.assertPrepared();
    assert.ok(r.writes.every(write => write.url.startsWith('file:///synthetic/photo-')));
    const before = r.writes.length;
    await r.tick(30000); await r.drain(); r.assertPrepared(before);
    assert.ok(r.writes.slice(before).every(write => write.url.startsWith('file:///synthetic/copy-')));
  } finally { r.stop(); }
});

test('pause holds prepared resource publication and promotion until resume', async () => {
  const r = fixture();
  try {
    const engine = r.start(); await r.loadImages(); engine.pause();
    await r.finishImages(); await r.tick(1000);
    assert.equal(r.writes.length, 0);
    assert.equal(r.stage.children[0].classList.contains('on'), false);
    engine.resume(); await r.drain();
    r.assertPrepared(); assert.ok(r.stage.children[0].classList.contains('on'));
  } finally { r.stop(); }
});

for (const mode of affected) {
  test(`${mode}: destruction during decode prevents late publication and releases all timers`, async () => {
    const r = fixture({ mode });
    const engine = r.start(); await r.loadImages();
    const before = r.writes.length, late = r.images.filter(image => image.state === 'decoding');
    assert.ok(late.length, 'The fixture must have pending decoder completions');
    engine.stop();
    assert.ok(late.every(image => image.url === '' && image.onload === null && image.onerror === null));
    late.reverse().forEach(image => image.finish()); await r.flush(); await r.tick(35000);
    assert.equal(r.writes.length, before);
    assert.equal(r.stage.children.length, 0);
    assert.equal(r.pending.size, 0);
  });
}

test('Memories: destruction during a pending collage decode prevents late publication', async () => {
  const r = fixture({ mode: 'memories' });
  try {
    r.start(); await r.drain();
    for (let i = 0; i < 4; i++) { await r.tick(30000); await r.drain(); }
    const before = r.writes.length;
    await r.tick(30000); await r.loadImages();
    const late = r.images.filter(image => image.state === 'decoding');
    assert.equal(late.length, 3);
    r.stop(); late.reverse().forEach(image => image.finish()); await r.flush(); await r.tick(35000);
    assert.equal(r.writes.length, before);
    assert.equal(r.stage.children.length, 0);
    assert.equal(r.pending.size, 0);
  } finally { r.stop(); }
});

test('superseded incoming resources cannot publish into a replacement scene', async () => {
  const r = fixture();
  try {
    const engine = r.start(); await r.drain();
    engine.next(); await r.loadImages();
    const obsolete = r.images.filter(image => image.state === 'decoding');
    const before = r.writes.length;
    engine.next(); await r.flush();
    obsolete.forEach(image => image.finish()); await r.flush();
    assert.equal(r.writes.length, before);
    await r.drain(); r.assertPrepared(before);
    assert.equal(r.stage.children.length, 2);
    await r.tick(1800); assert.equal(r.stage.children.length, 1);
  } finally { r.stop(); }
});

test('an empty engine deck never prepares or publishes a resource', async () => {
  const r = fixture();
  try {
    r.context.PHOTOS = [];
    const engine = r.start(); await r.drain();
    assert.equal(engine.next(), false);
    assert.equal(r.calls.length, 0);
    assert.equal(r.images.length, 0);
    assert.equal(r.writes.length, 0);
    assert.equal(r.stage.children.length, 0);
  } finally { r.stop(); }
});
