/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */
import test from 'node:test';
import assert from 'node:assert/strict';
import { browserHarness } from './remote-browser-harness.mjs';

const saved = [{ base: 'http://127.0.0.1:8877', name: 'Generated room', token: 'fixture-only' }];
const file = { path: '/sdcard/Pictures/generated.png', name: 'generated.png', size: 1, modified: 1 };
const fireError = image => {
  for (const listener of image.listeners.error || []) listener({ target: image });
};

async function fixture(t) {
  const h = await browserHarness({ saved, routes: {
    '/remote/photos': ({ response }) => response({
      ok: true, path: '/sdcard', recursive: true, folders: [], files: [file],
    }),
    '/remote/photo': () => new Response(new Uint8Array([1]), {
      headers: { 'Content-Type': 'image/png' },
    }),
  } });
  t.after(() => h.hide());
  h.click('Photos');
  await h.settle();
  const tile = h.document.getElementById('page').querySelector('.photo-tile');
  const image = tile.querySelector('img');
  assert.match(image.src, /^blob:/);
  return { h, tile, image };
}

test('gallery decode failure hides the broken image and offers preview retry', async t => {
  const { h, tile, image } = await fixture(t);
  fireError(image);
  assert.equal(image.hidden, true);
  const hint = tile.querySelector('.photo-image').querySelector('span');
  assert.equal(hint.hidden, false);
  assert.equal(hint.textContent, 'Preview unavailable · Tap to retry');
  tile.click();
  await h.settle();
  assert.equal(h.document.getElementById('sheet').open, true);
  assert.equal(h.calls.filter(c => c.path === '/remote/photo').length, 2);
});

test('modal decode failure offers retry and a new response clears its error', async t => {
  const { h, tile } = await fixture(t);
  tile.click();
  await h.settle();
  const sheet = h.document.getElementById('sheet-content');
  const image = sheet.querySelector('.preview-image');
  const firstUrl = image.src;
  fireError(image);
  assert.equal(image.hidden, true);
  assert.match(sheet.textContent, /Preview unavailable/);
  assert.doesNotMatch(sheet.textContent, /Thumbnail from your Portal/);
  assert.equal(sheet.querySelector('.error').textContent, 'Preview unavailable. Try again.');
  const retry = sheet.querySelectorAll('button').find(b => b.textContent === 'Retry preview');
  assert.equal(retry.hidden, false);
  retry.click();
  await h.settle();
  assert.notEqual(image.src, firstUrl);
  assert.equal(image.hidden, false);
  assert.equal(retry.hidden, true);
  assert.equal(sheet.querySelector('.error'), null);
});

test('a retired gallery decode event cannot alter its detached tile', async t => {
  const { h, tile, image } = await fixture(t);
  h.eval('go("home")');
  await h.settle();
  const before = { hidden: image.hidden, text: tile.textContent };
  fireError(image);
  assert.deepEqual({ hidden: image.hidden, text: tile.textContent }, before);
});

test('a retired modal decode event cannot reopen recovery feedback', async t => {
  const { h, tile } = await fixture(t);
  tile.click();
  await h.settle();
  const sheet = h.document.getElementById('sheet-content');
  const image = sheet.querySelector('.preview-image');
  const before = { hidden: image.hidden, text: sheet.textContent };
  h.eval('closeSheet()');
  fireError(image);
  assert.deepEqual({ hidden: image.hidden, text: sheet.textContent }, before);
});
