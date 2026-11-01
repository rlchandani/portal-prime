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
import { createSceneFixture } from './helpers/slideshow_scene_fixture.mjs';

const W = 2160, H = 1440;
const photoLoader = readFileSync(new URL('../../app/src/main/assets/screensaver/photos.js', import.meta.url), 'utf8');
function bridgePhoto(raw) {
  const window = { Android: { getPhotos: () => JSON.stringify([raw]) } };
  vm.runInNewContext(photoLoader, { window, Image: function () {
    throw new Error('Known metadata must not trigger another image decode');
  } });
  return JSON.parse(JSON.stringify(window.PHOTOS[0]));
}
function photo(fb, fx) {
  return bridgePhoto({ url: 'fixture:group', w: 8000, h: 1000, fx, fy: .45, fb });
}
test('the actual Android photo normalizer retains optional face-region metadata', () => {
  const fb = [.04, .3, .86, .55];
  assert.deepEqual(photo(fb, .28).fb, fb);
  assert.equal(Object.hasOwn(photo(undefined, .28), 'fb'), false);
});
function imageOf(r, mode) {
  return r.incoming();
}
function geometry(image, mode, p) {
  const height = parseFloat(image.style.height);
  const width = mode === 'panorama' ? parseFloat(image.style.width) : height * p.w / p.h;
  const offset = mode === 'panorama' ?
    -Number(/translateX\(([^)]+)%\)/.exec(image.style.transform)[1]) * width / 100 :
    (width - W) * parseFloat(image.style.backgroundPosition) / 100;
  return { width, height, offset };
}
for (const mode of ['panorama', 'cinema']) {
  for (const [label, fb, fx] of [
    ['spread group', [.15, .2, .75, .7], .2],
    ['narrow group', [.35, .2, .45, .7], .4],
    ['left edge', [0, .1, .65, .8], .1],
    ['right edge', [.35, .1, 1, .8], .9],
    ['whole width', [0, .1, 1, .8], .5],
  ]) {
    test(`${mode}: ${label} retains the padded group at the largest safe scale`, async () => {
      for (const photoMotion of [true, false]) {
        const p = photo(fb, fx);
        const r = createSceneFixture({ mode, W, H, photos: [p], photoMotion });
        try {
          assert.equal(await r.cleanup.ready, true);
          const image = imageOf(r, mode), g = geometry(image, mode, p);
          const pad = (fb[2] - fb[0]) * .1;
          const left = Math.max(0, fb[0] - pad), right = Math.min(1, fb[2] + pad);
          const base = mode === 'panorama' ? W * 2 : H * .78 * p.w / p.h;
          const largest = Math.min(base, W / (right - left));
          assert.ok(Math.abs(g.width - largest) < .0001, 'Must retain the largest safe scale');
          assert.ok(g.width * left - g.offset >= -.0001, 'Left of padded face union must be visible');
          assert.ok(g.width * right - g.offset <= W + .0001, 'Right of padded face union must be visible');
          assert.ok(Math.abs(g.width / g.height - p.w / p.h) < .0001);
          assert.equal(image.style.animation, 'none');
          assert.equal(image.style.willChange, 'auto');
        } finally { r.cleanup(); }
      }
    });
  }
  test(`${mode}: malformed or absent bounds retain the exact point-only layout`, async () => {
    const reference = createSceneFixture({ mode, photos: [photo(undefined, .2)] });
    try {
      await reference.cleanup.ready;
      const expected = { ...imageOf(reference, mode).style };
      for (const fb of [null, [], [.1, .2, .7], [.7, .2, .1, .8],
        [-.1, .2, .7, .8], [.1, .2, NaN, .8], [.1, .8, .7, .2]]) {
        const r = createSceneFixture({ mode, photos: [photo(fb, .2)] });
        try {
          await r.cleanup.ready;
          assert.deepEqual({ ...imageOf(r, mode).style }, expected);
        } finally { r.cleanup(); }
      }
    } finally { reference.cleanup(); }
  });
  test(`${mode}: a reused face-group layer restores ordinary photo geometry`, async () => {
    const plain = { seed: 'plain', w: 8000, h: 1000 };
    const r = createSceneFixture({ mode, photoMs: 5000, photos:
      [photo([.1, .2, .8, .7], .2), plain, plain] });
    const reference = createSceneFixture({ mode, photos: [plain] });
    try {
      await r.cleanup.ready; await reference.cleanup.ready;
      const firstSlot = r.slot(r.incoming());
      await r.advanceTo(5000);
      assert.notEqual(r.slot(r.incoming()), firstSlot);
      await r.advanceTo(10000);
      const current = imageOf(r, mode), expected = imageOf(reference, mode);
      assert.equal(r.slot(current), firstSlot, 'Must inspect the reused incoming scene');
      for (const key of ['width', 'height', 'top', 'backgroundSize', 'backgroundPosition',
        'transform', 'animation', 'willChange']) {
        assert.equal(current.style[key], expected.style[key], key);
      }
    } finally { r.cleanup(); reference.cleanup(); }
  });
}

test('Cinema preserves vertical face bounds on a wider display without always containing small groups', async () => {
  for (const fb of [[.2, 0, .8, 1], [.2, .3, .8, .5], [.2, .8, .8, 1]]) {
    const W = 1920, H = 1080, scopeH = H * .78, ratio = 2.21;
    const p = bridgePhoto({ url: 'fixture:vertical', w: 2210, h: 1000, fx: .5, fy: .9, fb });
    const r = createSceneFixture({ mode: 'cinema', W, H, photos: [p] });
    try {
      await r.cleanup.ready;
      const image = r.incoming(), height = parseFloat(image.style.height);
      const renderedH = parseFloat(image.style.width) / ratio;
      const position = Number(image.style.backgroundPosition.split(' ')[1].replace('%', '')) / 100;
      const offset = (renderedH - height) * position;
      const pad = (fb[3] - fb[1]) * .1;
      const top = Math.max(0, fb[1] - pad), bottom = Math.min(1, fb[3] + pad);
      assert.ok(Math.abs(renderedH - Math.min(W / ratio, scopeH / (bottom - top))) < .0001);
      assert.ok(renderedH * top - offset >= -.0001);
      assert.ok(renderedH * bottom - offset <= height + .0001);
      assert.equal(height, scopeH);
      assert.ok(Math.abs(parseFloat(image.style.left) - (W - parseFloat(image.style.width)) / 2) < .0001);
      assert.equal(image.style.top, '0px');
      assert.equal(image.style.animation, 'none');
    } finally { r.cleanup(); }
  }
});

test('Cinema does not mistake floating-point height roundoff for vertical overflow', async () => {
  const p = bridgePhoto({ url: 'fixture:roundoff', w: 3793, h: 1000,
    fx: .9, fy: .5, fb: [.35, .2, 1, .8] });
  const r = createSceneFixture({ mode: 'cinema', W, H, photos: [p] });
  try {
    await r.cleanup.ready;
    const image = r.incoming(), g = geometry(image, 'cinema', p);
    assert.equal(parseFloat(image.style.width), W);
    assert.ok(Math.abs(g.width - W / .715) < .0001);
    assert.ok(g.width * .285 - g.offset >= -.0001);
    assert.ok(g.width - g.offset <= W + .0001);
  } finally { r.cleanup(); }
});
