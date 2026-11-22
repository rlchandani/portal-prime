/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */
import test from 'node:test';
import assert from 'node:assert/strict';
import { readFileSync } from 'node:fs';
import { createSceneFixture } from './helpers/slideshow_scene_fixture.mjs';

const assets = new URL('../../app/src/main/assets/screensaver/', import.meta.url);
const read = file => readFileSync(new URL(file, assets));
const css = read('styles.css').toString('utf8');
const rule = selector => {
  const match = new RegExp('(?:^|\\n)' + selector.replace(/[.*+?^${}()|[\]\\]/g, '\\$&') + ' \\{([^}]*)\\}').exec(css);
  assert.ok(match, selector + ' exists');
  return match[1];
};
const pngSize = bytes => {
  assert.equal(bytes.subarray(0, 8).toString('latin1'), '\x89PNG\r\n\x1a\n');
  assert.equal(bytes.subarray(12, 16).toString('latin1'), 'IHDR');
  return { w: bytes.readUInt32BE(16), h: bytes.readUInt32BE(20), depth: bytes[24], color: bytes[25] };
};
const SHADOW = '0 30px 64px -16px rgba(0,0,0,0.75)';

test('the Polaroid print no longer paints a live blurred box-shadow or its own paper', () => {
  const print = rule('.ss-polaroid');
  assert.ok(!/box-shadow/.test(print), 'a tilted live box-shadow is re-blurred on every raster');
  assert.ok(!/background/.test(print), 'the paper is drawn above the shadow bitmap on ::after');
  assert.match(print, /isolation: isolate;/, 'the pseudo layers stay inside the print');
  assert.match(rule('.ss-polaroid::before, .ss-polaroid::after'), /z-index: -1;/);
  assert.match(rule('.ss-polaroid::after'), /inset: 0; border-radius: 4px; background: #fbfaf6;/);
});

test('the shadow bitmap is a 9-slice over the box-shadow extent at 1x, 1.125x and 2x', () => {
  const shadow = rule('.ss-polaroid::before');
  assert.match(shadow, /left: -80px; right: -80px; top: -50px; bottom: -110px;/);
  assert.match(shadow, /opacity: \.75;/, 'the bitmap holds full-strength coverage; the 0.75 is applied once');
  const image = /border-image: (image-set\([^)]*\)[^)]*\)[^)]*\)[^)]*\)) 40% \/ 192px stretch;/.exec(shadow);
  assert.ok(image, '40 % of the 480 px tile is the 192 px blur ramp; the paper hides the middle');
  const candidates = [...image[1].matchAll(/url\("([^"]+)"\) ([\d.]+)x/g)].map(m => ({ file: m[1], scale: Number(m[2]) }));
  assert.deepEqual(candidates.map(c => c.scale), [1, 1.125, 2]);
  for (const { file, scale } of candidates) {
    assert.equal(file, `polaroid-shadow-${480 * scale}.png`);
    assert.deepEqual(pngSize(read(file)), { w: 480 * scale, h: 480 * scale, depth: 8, color: 6 }, file);
  }
});

test('a print too small for the 9-slice keeps the exact live box-shadow', () => {
  assert.equal(rule('.ss-polaroid.ss-live-shadow').trim(), `box-shadow: ${SHADOW};`);
  assert.equal(rule('.ss-polaroid.ss-live-shadow::before').trim(), 'content: none;');
  const generator = readFileSync(new URL('../generate_polaroid_shadow.mjs', import.meta.url), 'utf8');
  assert.match(generator, /30px 64px -16px rgba\(0,0,0,1\)/, 'the tile is cast by the same shadow at full strength');
});

const photographs = count => Array.from({ length: count }, (_, i) => ({
  seed: 'shadow-' + i, w: i % 3 === 0 ? 1600 : i % 3 === 1 ? 900 : 1200, h: i % 3 === 1 ? 1400 : 1200,
}));

test('only prints under 224 px on a side keep the live shadow, across screen sizes', async () => {
  const seen = { live: 0, bitmap: 0 };
  for (const [W, H] of [[2160, 1440], [1920, 1280], [800, 1280], [640, 960], [480, 320]]) {
    const r = createSceneFixture({ mode: 'polaroid', photos: photographs(12), W, H });
    try {
      assert.equal(await r.cleanup.ready, true);
      const cards = r.prints().map(image => r.slot(image)).filter(card => !card.classList.contains('retired'));
      assert.equal(cards.length, 6);
      for (const card of cards) {
        const small = Math.min(parseFloat(card.style.width), parseFloat(card.style.height)) < 224;
        assert.equal(card.classList.contains('ss-live-shadow'), small, `${W} x ${H}: ${card.style.width} x ${card.style.height}`);
        seen[small ? 'live' : 'bitmap']++;
        if (W >= 1920) assert.equal(small, false, 'every Portal+ print of an ordinary photo uses the bitmap');
      }
    } finally { r.cleanup(); }
  }
  assert.ok(seen.live > 0 && seen.bitmap > 0);
});
