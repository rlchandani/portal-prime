/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */
import test from 'node:test';
import assert from 'node:assert/strict';
import { readFileSync } from 'node:fs';
import { GALLERY_DOODLE, ROOM_DOODLE } from './helpers/slideshow_scene_fixture.mjs';

const assets = new URL('../../app/src/main/assets/screensaver/', import.meta.url);
const read = file => readFileSync(new URL(file, assets));
const css = read('styles.css').toString('utf8');
const impls = read('mode-impls.js').toString('utf8');
const candidatesOf = tile => [...tile.matchAll(/url\("([^"]+)"\) ([\d.]+)x/g)].map(m => ({ file: m[1], scale: Number(m[2]) }));
const candidates = candidatesOf(GALLERY_DOODLE);
const pngSize = bytes => {
  assert.equal(bytes.subarray(0, 8).toString('latin1'), '\x89PNG\r\n\x1a\n');
  assert.equal(bytes.subarray(12, 16).toString('latin1'), 'IHDR');
  return { w: bytes.readUInt32BE(16), h: bytes.readUInt32BE(20), depth: bytes[24], color: bytes[25] };
};

test('every doodle tile candidate is an RGBA PNG of the 480 x 360 tile at its scale', () => {
  assert.deepEqual(candidates.map(c => c.scale), [1, 1.125, 2], 'Portal 1x, Portal+ 1.125x, 2x fallback, ascending');
  for (const { file, scale } of candidates) {
    assert.deepEqual(pngSize(read(file)), { w: 480 * scale, h: 360 * scale, depth: 8, color: 6 }, file);
  }
});

test('the wall, its tiles and the photo mattes all use the same pre-rasterised tile', () => {
  const rule = /\.ss-wall\.gallery-wall, \.gallery-wall \.ss-gcell \{([^}]*)\}/.exec(css);
  assert.ok(rule, 'the Gallery Wall doodle rule exists');
  assert.match(rule[1], /background-size: 480px 360px;/);
  assert.equal(/background-image: (image-set\([^;]*\));/.exec(rule[1])[1], GALLERY_DOODLE);
  const js = /const GALLERY_DOODLE = ('[^']*') \+\s*('[^']*');/.exec(impls);
  assert.ok(js, 'mode-impls.js declares the tile once');
  assert.equal(js[1].slice(1, -1) + js[2].slice(1, -1), GALLERY_DOODLE);
});

test('Gallery Wall no longer paints the vector doodle', () => {
  const start = impls.indexOf('MODES.galleryWall'), end = impls.indexOf('MODES.push');
  assert.ok(start > 0 && end > start);
  assert.ok(!impls.slice(start, end).includes('photo-doodles-dark.svg'));
  assert.ok(!/gallery[^{]*\{[^}]*photo-doodles-dark\.svg/.test(css));
});

test('the light room tile has the same three RGBA candidates as the dark tile', () => {
  const room = candidatesOf(ROOM_DOODLE);
  assert.deepEqual(room.map(c => c.scale), [1, 1.125, 2]);
  for (const { file, scale } of room) {
    assert.deepEqual(pngSize(read(file)), { w: 480 * scale, h: 360 * scale, depth: 8, color: 6 }, file);
  }
});

// Diptych, Framed, Mosaic, Panorama, Polaroid and Cinema painted the tiled SVG once or more per scene.
test('no screensaver style paints a vector doodle; every doodle is a pre-rasterised tile', () => {
  assert.ok(!/photo-doodles(-dark)?\.svg/.test(css), 'styles.css must not reference a doodle SVG');
  assert.ok(!/photo-doodles(-dark)?\.svg/.test(impls), 'mode-impls.js must not reference a doodle SVG');
  const doodles = [...css.matchAll(/image-set\(url\("photo-doodles[^;]*?2x\)/g)].map(m => m[0]);
  // Diptych, Framed room/art, Gallery Wall, Mosaic, Panorama, Polaroid table, Cinema scope/layer/bars.
  assert.equal(doodles.length, 7);
  for (const value of doodles) assert.ok(value === GALLERY_DOODLE || value === ROOM_DOODLE, value);
  for (const rule of css.matchAll(/\{([^}]*photo-doodles[^}]*)\}/g)) {
    assert.match(rule[1], /background-size: 480px 360px/, 'each tile keeps the 480 x 360 CSS px repeat');
  }
});
