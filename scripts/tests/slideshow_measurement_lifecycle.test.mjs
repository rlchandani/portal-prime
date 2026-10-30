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
const photos = readFileSync(new URL('photos.js', assets), 'utf8');
const entry = [...readFileSync(new URL('index.html', assets), 'utf8')
  .matchAll(/<script>([\s\S]*?)<\/script>/g)].at(-1)[1];

function fixture({ paused = false, known = false, runEntry = true } = {}) {
  let now = 0, sequence = 0, engineStarts = 0;
  const images = [], timers = new Map(), handlers = new Map(), requestedSizes = [];
  class Image {
    constructor() { images.push(this); this.naturalWidth = 320; this.naturalHeight = 480; }
    set src(value) { this.url = value; }
    get src() { return this.url; }
    complete() { this.onload?.(); }
  }
  const element = { style: {}, classList: { add() {}, remove() {} } };
  const source = Array.from({ length: 8 }, (_, i) => ({
    url: `file:///sample/${i}.jpg`, ...(known ? { w: 4000, h: 3000 } : {}),
  }));
  const context = vm.createContext({
    Image, AbortController, URLSearchParams,
    Android: { getPhotos: () => JSON.stringify(source),
      scaledUrlForSize: (url, edge) => {
        requestedSizes.push(edge);
        return url.replace('/sample/', '/scaled/');
      } },
    location: { search: `?nativeClock=1&hostPaused=${paused ? 1 : 0}` },
    document: { getElementById: () => element }, performance: { now: () => now },
    setTimeout(fn, ms) { const id = ++sequence; timers.set(id, { fn, at: now + ms }); return id; },
    clearTimeout: id => timers.delete(id), clearInterval: id => timers.delete(id),
    addEventListener: (name, fn) => handlers.set(name, fn),
    console: { info() {} },
    SS: { startEngine() {
      engineStarts++;
      return { pause() {}, resume() {}, stop() {}, next() {}, previous() {} };
    } },
  });
  context.window = context;
  vm.runInContext(photos, context);
  vm.runInContext(readFileSync(new URL('lib-engine.js', assets), 'utf8'), context);
  if (runEntry) vm.runInContext(entry, context);
  const flush = async () => { for (let i = 0; i < 40; i++) await Promise.resolve(); };
  async function tick(ms) {
    await flush(); const end = now + ms;
    for (;;) {
      const due = [...timers].filter(([, task]) => task.at <= end)
        .sort((a, b) => a[1].at - b[1].at)[0];
      if (!due) break;
      const [id, task] = due; timers.delete(id); now = task.at; task.fn(); await flush();
    }
    now = end; await flush();
  }
  return { context, images, requestedSizes, timers, flush, tick,
    active: () => images.filter(image => image.url).length,
    starts: () => engineStarts,
    close: () => handlers.get('pagehide')?.() };
}

test('paused startup admits no measurements or renderer until resume', async () => {
  const f = fixture({ paused: true });
  await f.tick(5000);
  assert.equal(f.images.length, 0);
  assert.equal(f.starts(), 0);
  assert.equal(f.timers.size, 0);
  f.context.immortalSlideshow.resume(); await f.flush();
  assert.equal(f.active(), 2);
  await f.tick(1500);
  assert.equal(f.starts(), 1);
  assert.equal(f.active(), 0);
  f.close();
});

test('dimension measurement uses two small derivative requests, not six originals', async () => {
  const f = fixture(); await f.flush();
  assert.equal(f.active(), 2);
  assert.deepEqual(f.requestedSizes, [320, 320]);
  assert.ok(f.images.every(image => image.src.startsWith('file:///scaled/')));
  f.close(); await f.flush();
});

test('startup timeout releases in-flight images and suppresses late metadata', async () => {
  const f = fixture(); await f.flush();
  const late = f.images[0].onload;
  await f.tick(1500);
  assert.equal(f.active(), 0);
  assert.equal(f.starts(), 1);
  late?.(); await f.flush();
  assert.equal(f.context.PHOTOS[0].measured, false);
  assert.equal(f.context.PHOTOS[0].w, 0);
  assert.equal(f.timers.size, 0);
  f.close();
});

test('pause cancels preparation; resume admits only a new bounded batch', async () => {
  const f = fixture(); await f.flush();
  f.context.immortalSlideshow.pause(); await f.flush();
  assert.equal(f.active(), 0);
  await f.tick(10000); assert.equal(f.starts(), 0);
  f.context.immortalSlideshow.resume(); await f.flush();
  assert.equal(f.active(), 2);
  await f.tick(1500); assert.equal(f.starts(), 1);
  f.close();
});

test('closing during pause or measurement releases all work and never starts engine', async () => {
  for (const paused of [false, true]) {
    const f = fixture({ paused }); await f.flush(); f.close();
    await f.tick(10000);
    assert.equal(f.active(), 0);
    assert.equal(f.starts(), 0);
    assert.equal(f.timers.size, 0);
  }
});

test('host-supplied dimensions need zero image loads', async () => {
  const f = fixture({ known: true }); await f.flush();
  assert.equal(f.images.length, 0);
  assert.equal(f.starts(), 1);
  assert.equal(f.timers.size, 0);
  f.close();
});

test('successful derivative metadata releases handlers and preserves aspect ratio', async () => {
  const f = fixture({ runEntry: false }), controller = new AbortController();
  const batch = f.context.premeasureAll(1, controller.signal); await f.flush();
  f.images[0].complete(); await batch;
  assert.equal(f.context.PHOTOS[0].w / f.context.PHOTOS[0].h, 2 / 3);
  assert.equal(f.context.PHOTOS[0].measured, true);
  assert.equal(f.images[0].onload, null);
  assert.equal(f.images[0].onerror, null);
  assert.equal(f.active(), 0);
});

test('normal display preparation recovers portrait metadata after startup timeout', async () => {
  const f = fixture(); await f.tick(1500);
  const photo = f.context.PHOTOS[0];
  assert.equal(photo.measured, false);
  const url = f.context.photoUrl(photo, 1800);
  const ready = f.context.preloadDecoded(url);
  f.images.at(-1).complete(); await ready;
  assert.equal(photo.measured, true);
  assert.equal(photo.w / photo.h, 2 / 3);
  f.close();
});

test('cancelled display decode cannot publish metadata after a late callback', async () => {
  const f = fixture({ runEntry: false }), controller = new AbortController();
  const photo = f.context.PHOTOS[0], url = f.context.photoUrl(photo, 1800);
  const ready = f.context.preloadDecoded(url, controller.signal).catch(() => null);
  const late = f.images.at(-1).onload;
  controller.abort(); late(); await ready;
  assert.equal(photo.measured, false);
  assert.equal(photo.w, 0);
});
