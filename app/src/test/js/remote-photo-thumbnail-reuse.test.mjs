/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */
import test from 'node:test';
import assert from 'node:assert/strict';
import { browserHarness } from './remote-browser-harness.mjs';

const saved = [
  { base: 'http://127.0.0.1:8877', name: 'Photo A', token: 'fixture-photo-a' },
  { base: 'http://127.0.0.1:8878', name: 'Photo B', token: 'fixture-photo-b' },
];
const files = (count = 24) => Array.from({ length: count }, (_, i) => ({
  name: `photo-${i}.jpg`, path: `/sdcard/Pictures/photo-${i}.jpg`, size: 1024, modified: 1000,
}));
const imageResponse = () => new Response(new Uint8Array([0]), {
  headers: { 'Content-Type': 'image/png' },
});
async function photos({ pending = false, count = 24, metadata = true } = {}) {
  const fixture = { files: files(count), waiting: [], running: 0, maxRunning: 0 };
  if (!metadata) for (const file of fixture.files) delete file.modified;
  const h = await browserHarness({ saved, routes: {
    '/remote/photos': ({ response }) => response({
      ok: true, path: '/sdcard', recursive: true, folders: [], files: fixture.files,
    }),
    '/remote/photo': ({ url, opts }) => {
      if (!pending) return imageResponse();
      fixture.running++;
      fixture.maxRunning = Math.max(fixture.maxRunning, fixture.running);
      return new Promise((resolve) => fixture.waiting.push({
        url, signal: opts.signal,
        finish() { fixture.running--; resolve(imageResponse()); },
      }));
    },
  } });
  h.eval('go("photos")');
  await h.settle();
  const page = h.document.getElementById('page');
  const images = () => page.querySelectorAll('.photo-tile').map((tile) => tile.querySelector('img'));
  const calls = () => h.calls.filter((c) => c.path === '/remote/photo');
  const complete = async () => {
    while (fixture.waiting.length) {
      fixture.waiting.splice(0).forEach((job) => job.finish());
      await h.settle();
    }
  };
  return { h, fixture, page, images, calls, complete };
}
function populated(images) {
  assert.equal(images.length, 24);
  for (const image of images) {
    assert.match(image.src || '', /^blob:/);
    assert.equal(image.hidden, false);
    assert.equal(image.isConnected, true);
  }
}

test('Photos ready blobs survive rebuilt tiles and unchanged explicit status/metadata refresh', async (t) => {
  const { h, images, calls } = await photos();
  try {
    populated(images());
    assert.equal(calls().length, 24);
    const initial = images().map((image) => image.src);
    h.eval('render();render();render()');
    await h.settle();
    assert.equal(calls().length, 24, 'Rebuilt Photos must reuse the initial 24 thumbnail requests.');
    populated(images());
    assert.deepEqual(images().map((image) => image.src), initial);
    const reads = h.calls.filter((c) => c.path === '/remote/photos').length;
    h.document.getElementById('refresh-button').click();
    await h.settle();
    assert.equal(h.calls.filter((c) => c.path === '/remote/photos').length, reads + 1);
    assert.equal(calls().length, 24);
    populated(images());
    t.diagnostic('24 initial requests; 0 extra for three renders; 0 extra for explicit Refresh.');
  } finally { h.hide(); }
});

test('Photos pending loads rebind to current tiles through renders and explicit refresh', async (t) => {
  const { h, fixture, images, calls, complete } = await photos({ pending: true });
  try {
    assert.equal(calls().length, 4);
    const detached = images();
    h.eval('render();render()');
    h.document.getElementById('refresh-button').click();
    await h.settle();
    assert.equal(calls().length, 4, 'Pending loads must stay bound without being aborted/restarted.');
    assert.equal(fixture.waiting.some((job) => job.signal.aborted), false);
    await complete();
    populated(images());
    assert.equal(detached.every((image) => !image.src), true);
    assert.equal(calls().length, 24);
    assert.equal(fixture.maxRunning, 4);
    t.diagnostic('24 total pending requests; 0 duplicate requests; peak concurrency 4.');
  } finally { h.hide(); await complete(); }
});

for (const field of ['size', 'modified']) {
  test(`Photos ${field} change invalidates exactly one visible thumbnail`, async () => {
    const { h, fixture, images, calls } = await photos();
    try {
      const initial = images().map((image) => image.src);
      fixture.files = fixture.files.map((file, i) => i === 7 ? { ...file, [field]: file[field] + 1 } : { ...file });
      h.eval('refresh()');
      await h.settle();
      assert.equal(calls().length, 25);
      assert.equal(new URL(calls().at(-1).url).searchParams.get('path'), '/sdcard/Pictures/photo-7.jpg');
      populated(images());
      images().forEach((image, i) => assert.equal(image.src === initial[i], i !== 7));
      assert.equal(h.eval('thumbs.size'), 24);
      assert.equal(h.eval('[...thumbs.entries.keys()].some(key => key.includes(room().token))'), false);
    } finally { h.hide(); }
  });
}

test('Photos deletion and pagination release old URLs while keeping only visible entries', async () => {
  const { h, fixture, images, calls } = await photos({ count: 48 });
  try {
    const initial = images().map((image) => image.src);
    fixture.files = fixture.files.filter((_, i) => i !== 7);
    h.eval('refresh()');
    await h.settle();
    assert.equal(calls().length, 25);
    assert.equal(images().some((image) => image.src === initial[7]), false);
    await assert.rejects(fetch(initial[7]));
    assert.equal(images()[0].src, initial[0]);
    const oldPage = images().map((image) => image.src);
    h.click('Next');
    await h.settle();
    assert.equal(images().length, 23);
    assert.equal(h.eval('thumbs.size'), 23);
    for (const url of oldPage) await assert.rejects(fetch(url));
    h.eval('go("home")');
    await h.settle();
    assert.equal(h.eval('thumbs.size'), 0);
  } finally { h.hide(); }
});

test('missing photo version metadata is reused for renders but invalidated on actual metadata refresh', async () => {
  const { h, images, calls } = await photos({ metadata: false });
  try {
    h.eval('render();render()');
    await h.settle();
    assert.equal(calls().length, 24);
    h.eval('refresh()');
    await h.settle();
    assert.equal(calls().length, 48);
    populated(images());
    h.eval('render()');
    await h.settle();
    assert.equal(calls().length, 48);
  } finally { h.hide(); }
});

test('late old-room thumbnails cannot populate new-room tiles or exceed four running loads', async () => {
  const { h, fixture, images, calls, complete } = await photos({ pending: true });
  try {
    const old = fixture.waiting.splice(0);
    const oldImages = images();
    h.eval('selectRoom(roster[1].base)');
    await h.settle();
    assert.equal(old.every((job) => job.signal.aborted), true);
    assert.equal(calls().length, 4, 'Aborted loads still occupy slots until their promises settle.');
    assert.equal(h.eval('thumbs.size'), 24);
    old.forEach((job) => job.finish());
    await h.settle();
    assert.equal(oldImages.every((image) => !image.src), true);
    assert.equal(images().every((image) => !image.src), true);
    await complete();
    populated(images());
    assert.equal(calls().length, 28, 'Only four old-room loads and 24 new-room loads run.');
    assert.equal(fixture.maxRunning, 4);
  } finally { h.hide(); await complete(); }
});

test('Photos modal uses its own thumbnail and closing it revokes only the preview', async () => {
  const { h, images, calls } = await photos();
  try {
    const gallery = images().map((image) => image.src);
    h.eval('photoPreview(data.photos.files[0],session.capture())');
    await h.settle();
    const preview = h.document.getElementById('sheet-content').querySelector('.preview-image');
    assert.match(preview.src || '', /^blob:/);
    assert.equal(preview.hidden, false);
    assert.equal(calls().length, 25);
    h.eval('closeSheet()');
    await assert.rejects(fetch(preview.src));
    assert.deepEqual(images().map((image) => image.src), gallery);
    assert.equal(h.eval('thumbs.size'), 24);
    h.eval('photoPreview(data.photos.files[0],session.capture())');
    await h.settle();
    const reopened = h.document.getElementById('sheet-content').querySelector('.preview-image');
    assert.match(reopened.src || '', /^blob:/);
    assert.notEqual(reopened.src, preview.src);
    assert.equal(calls().length, 26);
  } finally { h.hide(); }
});
