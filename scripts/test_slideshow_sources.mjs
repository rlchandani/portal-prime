/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */
import test from 'node:test';
import assert from 'node:assert/strict';
import { readFileSync } from 'node:fs';
import { runInNewContext } from 'node:vm';

const source = readFileSync(new URL('../app/src/main/assets/screensaver/photos.js', import.meta.url), 'utf8');
const original = 'file:///owned/photo.jpg';
function engine(extra = {}) {
  const window = { Android: {
    getPhotos: () => JSON.stringify([{ url: original, w: 4000, h: 3000 }]),
    scaledUrl: () => 'file:///owned/full.jpg', ...extra,
  } };
  runInNewContext(source, { window });
  return { photo: window.PHOTOS[0], url: window.photoUrl };
}

test('small and full-screen slots pass distinct requested sizes to the actual bridge', () => {
  const calls = [];
  const e = engine({ scaledUrlForSize: (path, edge) => {
    calls.push([path, edge]); return `file:///owned/${edge}.jpg`;
  } });
  assert.equal(e.url(e.photo, 900), 'file:///owned/900.jpg');
  assert.equal(e.url(e.photo, 2200), 'file:///owned/2200.jpg');
  assert.deepEqual(calls, [[original, 900], [original, 2200]]);
});

test('evicted derivatives are revalidated rather than permanently memoized', () => {
  let ready = 'file:///owned/copy.jpg';
  const e = engine({ scaledUrlForSize: () => ready, scaledUrl: () => ready });
  assert.equal(e.url(e.photo, 1200), ready);
  ready = '';
  assert.equal(e.url(e.photo, 1200), original);
  ready = 'file:///owned/rebuilt.jpg';
  assert.equal(e.url(e.photo, 1200), ready);
});

test('size requests are bounded and invalid values use the full-screen default', () => {
  const calls = [];
  const e = engine({ scaledUrlForSize: (_, edge) => { calls.push(edge); return ''; } });
  for (const edge of [undefined, NaN, Infinity, -1, '900', false, 1, 999.1, 8000])
    assert.equal(e.url(e.photo, edge), original);
  assert.deepEqual(calls, [2400, 2400, 2400, 2400, 2400, 2400, 320, 1000, 2400]);
});

test('older hosts retain ready full-screen fallback behavior', () => {
  const e = engine();
  assert.equal(e.url(e.photo, 900), 'file:///owned/full.jpg');
});

test('bridge failure or a malformed result keeps the original source', () => {
  for (const value of [null, false, 42, {}, '']) {
    const e = engine({ scaledUrlForSize: () => value });
    assert.equal(e.url(e.photo, 1200), original);
  }
  const e = engine({ scaledUrlForSize: () => { throw new Error('Host unavailable'); } });
  assert.equal(e.url(e.photo, 1200), original);
});
