/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */
import test from 'node:test';
import assert from 'node:assert/strict';
import { readFileSync } from 'node:fs';
import { spawnSync } from 'node:child_process';
import { inflateSync } from 'node:zlib';
import { createSceneFixture } from './helpers/slideshow_scene_fixture.mjs';

const wide = count => Array.from({ length: count }, (_, i) => ({ seed: 'scope-' + i, w: 3000 + i * 400, h: 1000 }));
const fixture = options => createSceneFixture({ mode: 'cinema', photos: wide(8), ...options });
const owned = r => r.prints().filter(image => image.style.backgroundImage?.startsWith('url('));
function released(r, image) {
  assert.equal(image.style.backgroundImage, 'none');
  assert.equal(image.style.animation, 'none');
  assert.equal(image.style.willChange, 'auto');
  assert.ok(r.slot(image).classList.contains('retired'));
}

test('Cinema retains two fixed scenes, an opaque initial wide crop and a gentle bounded zoom', async () => {
  const r = fixture();
  try {
    assert.equal(await r.cleanup.ready, true);
    assert.equal(r.prints().length, 2); assert.equal(owned(r).length, 1);
    const image = r.incoming();
    assert.equal(r.slot(image).opacity(), 1);
    assert.equal(image.style.backgroundSize, 'cover');
    assert.equal(parseFloat(image.style.width), 2160);
    assert.ok(Math.abs(parseFloat(image.style.height) - 1440 * .78) < .001);
    assert.match(image.style.animation, /^cinematicDrift 5400ms linear forwards$/);
    assert.equal(image.style.willChange, 'transform');
    assert.ok(r.resolutions.every(resource => resource.edge === 2400));
    assert.equal(r.nodes().filter(node => node.classList.contains('ss-fill')).length, 0);
  } finally { r.cleanup(); }
});

test('Cinema retains black 11-percent bars and scopes each raster grain to its own photo', async () => {
  const r = fixture();
  try {
    await r.cleanup.ready;
    const scope = r.nodes().find(node => node.classList.contains('ss-scope'));
    assert.equal(r.computed(scope).top, '11%'); assert.equal(r.computed(scope).bottom, '11%');
    assert.equal(r.computed(scope).background, '#000');
    const bars = r.nodes().filter(node => node.classList.contains('ss-bar'));
    assert.equal(bars.length, 2); assert.ok(bars.every(bar => r.computed(bar).height === '11%'));
    const grains = r.nodes().filter(node => node.classList.contains('ss-grain'));
    assert.equal(grains.length, 2);
    for (const grain of grains) {
      assert.ok(grain.parent.classList.contains('ss-cinema-photo'));
      assert.equal(r.computed(grain)['mix-blend-mode'], 'normal');
      assert.equal(r.computed(grain).filter, 'none');
      assert.equal(r.computed(grain).animation, 'none');
      assert.match(r.computed(grain)['background-image'], /cinema-grain\.png/);
    }
  } finally { r.cleanup(); }
});

test('Cinema keeps ordinary landscapes and portraits whole, centered and static', async () => {
  for (const ratio of [.2, 2 / 3, 1, 1.5, 2.2]) {
    const r = fixture({ photos: [{ seed: 'ordinary', w: ratio * 1000, h: 1000 }] });
    try {
      await r.cleanup.ready; const image = r.incoming();
      const width = parseFloat(image.style.width), height = parseFloat(image.style.height);
      assert.equal(image.style.backgroundSize, 'contain');
      assert.ok(Math.abs(width / height - ratio) < .0001);
      assert.ok(width <= 2160 && height <= 1440 * .78);
      assert.equal(image.style.animation, 'none'); assert.equal(image.style.willChange, 'auto');
      assert.ok(Math.abs(parseFloat(image.style.left) - (2160 - width) / 2) < .001);
      assert.ok(Math.abs(parseFloat(image.style.top) - (1440 * .78 - height) / 2) < .001);
    } finally { r.cleanup(); }
  }
});

test('cached Cinema focal points remain still and centered in the wide crop whenever edges permit', async () => {
  for (const fx of [0, .1, .5, .8, 1]) {
    const r = fixture({ photos: [{ seed: 'face', w: 8000, h: 1000, fx, fy: .5 }] });
    try {
      await r.cleanup.ready; const image = r.incoming();
      assert.equal(image.style.backgroundSize, 'cover');
      assert.equal(image.style.animation, 'none'); assert.equal(image.style.willChange, 'auto');
      const position = parseFloat(image.style.backgroundPosition);
      const renderedW = 1440 * .78 * 8, excess = renderedW - 2160;
      const focalX = fx * renderedW - excess * position / 100;
      assert.ok(position >= 0 && position <= 100);
      assert.ok(focalX >= 0 && focalX <= 2160);
      if (fx > 0 && fx < 1 && fx * renderedW >= 1080 && fx * renderedW <= renderedW - 1080)
        assert.ok(Math.abs(focalX - 1080) < .001);
    } finally { r.cleanup(); }
  }
});

test('Cinema incoming-only fades retain opaque outgoing pixels and clear the exact old resource after retirement', async () => {
  const r = fixture();
  try {
    await r.cleanup.ready;
    for (let beat = 1; beat <= 6; beat++) {
      const old = r.incoming(), oldUrl = old.style.backgroundImage;
      await r.advanceTo(beat * 5000);
      const incoming = r.incoming();
      assert.notEqual(incoming, old);
      assert.equal(r.slot(old).opacity(), 1); assert.equal(old.style.backgroundImage, oldUrl);
      assert.equal(r.slot(incoming).opacity(), 0);
      await r.advanceTo(beat * 5000 + 1100);
      assert.equal(r.slot(old).opacity(), 1);
      assert.ok(r.slot(incoming).opacity() > 0 && r.slot(incoming).opacity() < 1);
      await r.advanceTo(beat * 5000 + 2300);
      released(r, old); assert.equal(owned(r).length, 1); assert.equal(r.prints().length, 2);
    }
  } finally { r.cleanup(); }
});

test('Cinema manual pause retains a live dissolve, photo animation and remaining cadence', async () => {
  const r = fixture();
  try {
    await r.cleanup.ready; await r.advanceTo(6000);
    r.cleanup.pause();
    const state = owned(r).map(image => [image.style.backgroundImage, r.slot(image).opacity()]);
    const requests = r.resolutions.length;
    await r.advanceTo(50000);
    assert.deepEqual(owned(r).map(image => [image.style.backgroundImage, r.slot(image).opacity()]), state);
    assert.equal(r.resolutions.length, requests); assert.equal(r.pending.size, 0);
    assert.ok(owned(r).every(image => r.state(image).playState === 'paused'));
    r.cleanup.resume(); await r.advanceTo(51300);
    assert.equal(owned(r).length, 1);
    await r.advanceTo(54000); assert.equal(r.resolutions.length, requests + 1);
  } finally { r.cleanup(); }
});

test('failed Cinema replacement retains the ready scene and retries at the existing photo interval', async () => {
  const r = fixture();
  try {
    await r.cleanup.ready; const old = r.incoming(), oldUrl = old.style.backgroundImage;
    r.deferNext(); await r.advanceTo(5000); r.failLoad(); await r.flush();
    assert.equal(r.incoming(), old); assert.equal(old.style.backgroundImage, oldUrl);
    assert.equal(r.slot(old).opacity(), 1);
    await r.advanceTo(10000); assert.notEqual(r.incoming(), old);
  } finally { r.cleanup(); }
});

test('rapid Cinema scene reuse guards stale transition callbacks and stays at two image owners', async () => {
  const r = fixture({ photoMs: 500 });
  try {
    await r.cleanup.ready;
    let stale;
    for (let beat = 1; beat <= 20; beat++) {
      await r.advanceTo(beat * 500);
      const incoming = r.incoming();
      assert.equal(r.prints().length, 2); assert.ok(owned(r).length <= 2);
      if (beat === 1) {
        const target = r.slot(incoming), event = target.ontransitionend;
        stale = () => event?.({ target, propertyName: 'opacity' });
      } else {
        const before = owned(r).map(image => image.style.backgroundImage);
        stale(); await r.flush();
        assert.deepEqual(owned(r).map(image => image.style.backgroundImage), before);
      }
    }
  } finally { r.cleanup(); }
});

test('Cinema cleanup clears photos and grain owners and prevents late event, timer and resume work', async () => {
  const r = fixture(); await r.cleanup.ready; await r.advanceTo(6000);
  const images = r.prints(), grains = r.nodes().filter(node => node.classList.contains('ss-grain'));
  const events = images.map(image => [r.slot(image), r.slot(image).ontransitionend]);
  const callbacks = [...r.pending.values()].map(task => task.fn);
  r.cleanup.pause(); r.cleanup(); r.cleanup();
  const writes = r.writes.length, requests = r.resolutions.length;
  for (const [target, event] of events) event?.({ target, propertyName: 'opacity' });
  for (const callback of callbacks) { callback(); await r.flush(); }
  r.cleanup.resume(); await r.advanceTo(60000);
  images.forEach(image => released(r, image));
  assert.ok(grains.every(grain => grain.style.backgroundImage === 'none'));
  assert.equal(r.writes.length, writes); assert.equal(r.resolutions.length, requests);
  assert.equal(r.pending.size, 0); assert.equal(r.nodes().length, 1);
});

for (const initial of [true, false]) {
  test(`${initial ? 'initial' : 'replacement'} Cinema loading rejects resume/re-pause publications`, async () => {
    for (let checkpoint = 0; checkpoint <= 24; checkpoint++) {
      const r = fixture({ deferLoad: initial });
      try {
        if (initial) await r.flush();
        else { await r.cleanup.ready; r.deferNext(); await r.advanceTo(5000); }
        r.cleanup.pause(); r.finishLoad(); await r.flush(); r.cleanup.resume();
        for (let turn = 0; turn < checkpoint; turn++) await Promise.resolve();
        r.cleanup.pause();
        const writes = r.writes.length, requests = r.resolutions.length;
        await r.flush();
        assert.equal(r.writes.length, writes); assert.equal(r.resolutions.length, requests);
        r.cleanup.resume(); await r.cleanup.ready; await r.flush();
      } finally { r.cleanup(); }
    }
  });
}

test('closing initial Cinema loading rejects late publication at every decode checkpoint', async () => {
  for (let checkpoint = 0; checkpoint <= 24; checkpoint++) {
    const r = fixture({ deferLoad: true });
    await r.flush(); r.finishLoad();
    for (let turn = 0; turn < checkpoint; turn++) await Promise.resolve();
    const publishedBeforeClose = owned(r).length > 0;
    r.cleanup();
    const writes = r.writes.length, requests = r.resolutions.length;
    await r.flush();
    const ready = await r.cleanup.ready;
    assert.ok(!ready || publishedBeforeClose, 'readiness cannot precede publication or survive a pre-publication close');
    if (checkpoint === 0) assert.equal(ready, false);
    assert.equal(r.writes.length, writes); assert.equal(r.resolutions.length, requests);
    assert.equal(r.pending.size, 0); assert.equal(r.nodes().length, 1);
  }
});

test('the original grain asset is deterministic 128-pixel grayscale without SVG filters or blend effects', () => {
  const root = new URL('../../', import.meta.url);
  const path = new URL('app/src/main/assets/screensaver/cinema-grain.png', root);
  const data = readFileSync(path);
  const generated = spawnSync('python3', ['-c',
    "import runpy,sys;sys.stdout.buffer.write(runpy.run_path('scripts/generate_cinema_grain.py')['grain_png']())"],
  { cwd: root, encoding: null });
  assert.equal(generated.status, 0); assert.deepEqual(data, generated.stdout);
  assert.deepEqual(data.subarray(0, 8), Buffer.from([137, 80, 78, 71, 13, 10, 26, 10]));
  assert.equal(data.readUInt32BE(16), 128); assert.equal(data.readUInt32BE(20), 128);
  assert.equal(data[24], 8); assert.equal(data[25], 0);
  const blocks = [];
  for (let at = 8; at < data.length;) {
    const length = data.readUInt32BE(at), kind = data.toString('ascii', at + 4, at + 8);
    if (kind === 'IDAT') blocks.push(data.subarray(at + 8, at + 8 + length));
    at += 12 + length;
  }
  assert.equal(inflateSync(Buffer.concat(blocks)).length, 129 * 128);
  assert.ok(data.length < 20000);
  const css = readFileSync(new URL('app/src/main/assets/screensaver/styles.css', root), 'utf8');
  assert.ok(!css.includes('feTurbulence'));
  assert.ok(!/\.ss-grain[^}]*mix-blend-mode:\s*overlay/.test(css));
});
