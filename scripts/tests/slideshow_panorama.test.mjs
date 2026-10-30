/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */
import test from 'node:test';
import assert from 'node:assert/strict';
import { createSceneFixture } from './helpers/slideshow_scene_fixture.mjs';

const photographs = () => Array.from({ length: 8 }, (_, id) => ({
  seed: 'panorama-' + id, w: id % 2 ? 10000 : 3000, h: 1000,
}));
const fixture = options => createSceneFixture({ mode: 'panorama', photos: photographs(), ...options });
const photos = r => r.prints().filter(image => image.style.backgroundImage?.startsWith('url('));
const scenes = r => r.nodes().filter(node => node.classList.contains('panorama-drift'));
function released(image) {
  assert.equal(image.style.backgroundImage, 'none');
  assert.equal(image.style.animation, 'none');
  assert.equal(image.style.willChange, 'auto');
  assert.equal(image.style.transform, 'none');
}

test('Panorama starts opaque with one prepared photo and two reusable scene roots', async () => {
  const r = fixture();
  try {
    assert.equal(await r.cleanup.ready, true);
    assert.equal(scenes(r).length, 2);
    assert.equal(photos(r).length, 1);
    assert.equal(r.slot(photos(r)[0]).opacity(), 1);
    assert.equal(r.resolutions.length, 1);
    assert.equal(r.resolutions[0].edge, 2400);
    assert.equal(photos(r)[0].style.backgroundSize, 'contain');
  } finally { r.cleanup(); }
});

test('Panorama strips have a two-viewport width ceiling and preserve full vertical photo extent', async () => {
  for (const [W, H] of [[2160, 1440], [1920, 1080], [1024, 768], [640, 960]]) {
    for (const ratio of [1.3, 1.5, 2, 3, 8, 40]) {
      const r = fixture({ W, H, photos: [{ seed: 'wide', w: ratio * 1000, h: 1000 }] });
      try {
        await r.cleanup.ready;
        const image = photos(r)[0], width = parseFloat(image.style.width),
          height = parseFloat(image.style.height), top = parseFloat(image.style.top);
        assert.ok(image.style.width.endsWith('px') && image.style.height.endsWith('px'));
        assert.ok(width >= W && width <= 2 * W);
        assert.ok(height > 0 && height <= H && top >= 0 && top + height <= H);
        assert.ok(Math.abs(top - (H - height) / 2) < .0001);
        if (ratio >= W / H) assert.ok(Math.abs(width / height - ratio) < .0001);
        else assert.equal(image.style.backgroundSize, 'contain');
        const travel = parseFloat(image.style['--shift']) * width / 100;
        assert.ok(travel <= 0 && travel >= -W);
      } finally { r.cleanup(); }
    }
  }
});

test('moving panoramas animate only their transform; fitting landscapes remain whole and static', async () => {
  for (const moving of [true, false]) {
    const r = fixture({ photos: [{ seed: 'motion', w: moving ? 4000 : 1300, h: 1000 }] });
    try {
      await r.cleanup.ready;
      const image = photos(r)[0];
      assert.equal(r.state(image).animation, moving ? 'dolly 5000ms linear forwards' : 'none');
      assert.equal(r.state(image).promotion, moving ? 'transform' : 'auto');
      assert.equal(image.style.backgroundSize, 'contain');
      if (!moving) assert.equal(image.style.transform, 'none');
    } finally { r.cleanup(); }
  }
});

test('cached face anchors remain within the visible panoramic window without ongoing motion or a hint', async () => {
  for (const fx of [0, .25, .5, .8, 1]) {
    const r = fixture({ photos: [{ seed: 'face', w: 10000, h: 1000, fx, fy: .4 }] });
    try {
      await r.cleanup.ready;
      const image = photos(r)[0], width = parseFloat(image.style.width);
      const shift = Number(/translateX\(([^)]+)%\)/.exec(image.style.transform)[1]) * width / 100;
      const faceX = width * fx + shift;
      assert.ok(faceX >= -.001 && faceX <= 2160.001);
      assert.equal(image.style.animation, 'none');
      assert.equal(image.style.willChange, 'auto');
      assert.equal(image.style.backgroundSize, 'contain');
    } finally { r.cleanup(); }
  }
});

test('replacement fades retain opaque outgoing pixels then retire their URLs and hints', async () => {
  const r = fixture({ changingUrls: true });
  try {
    await r.cleanup.ready;
    for (let swap = 1; swap <= 12; swap++) {
      const old = photos(r).at(-1), outgoing = r.slot(old);
      await r.advanceTo(swap * 5000);
      const incoming = r.slot(photos(r).at(-1));
      assert.equal(outgoing.opacity(), 1);
      assert.equal(incoming.opacity(), 0);
      assert.equal(scenes(r).length, 2);
      assert.equal(photos(r).length, 2);
      await r.advanceTo(swap * 5000 + 900);
      assert.ok(incoming.opacity() > 0 && incoming.opacity() < 1);
      assert.equal(outgoing.opacity(), 1);
      await r.advanceTo(swap * 5000 + 1800); r.end(photos(r).at(-1));
      released(old);
      assert.equal(incoming.opacity(), 1);
      assert.equal(photos(r).length, 1);
    }
  } finally { r.cleanup(); }
});

test('old-WebView pause freezes motion and fade ownership until the remaining active duration elapses', async () => {
  const r = fixture();
  try {
    await r.cleanup.ready; await r.advanceTo(5600);
    const incoming = photos(r).at(-1), opacity = r.slot(incoming).opacity();
    const urls = photos(r).map(image => image.style.backgroundImage);
    r.cleanup.pause(); await r.advanceTo(50000);
    assert.equal(r.slot(incoming).opacity(), opacity);
    assert.deepEqual(photos(r).map(image => image.style.backgroundImage), urls);
    assert.equal(r.state(incoming).playState, 'paused');
    assert.equal(r.pending.size, 0);
    r.cleanup.resume(); await r.advanceTo(51200); r.end(incoming);
    assert.equal(r.slot(incoming).opacity(), 1);
    assert.equal(photos(r).length, 1);
  } finally { r.cleanup(); }
});

test('rapid panoramas retain exactly two strips and one opaque scene without accumulating URLs', async () => {
  const r = fixture({ photoMs: 500 });
  try {
    await r.cleanup.ready;
    for (let swap = 1; swap <= 36; swap++) {
      await r.advanceTo(swap * 500);
      assert.equal(scenes(r).length, 2);
      assert.equal(r.prints().length, 2);
      assert.ok(photos(r).length <= 2);
      assert.ok(scenes(r).some(scene => scene.opacity() === 1));
    }
  } finally { r.cleanup(); }
});

test('closed Panorama owners release resources and reject stale retirement and timer callbacks', async () => {
  const r = fixture(); await r.cleanup.ready; await r.advanceTo(5600);
  const images = photos(r), scene = r.slot(images.at(-1)), event = scene.ontransitionend;
  const callbacks = [...r.pending.values()].map(task => task.fn);
  r.cleanup.pause(); r.cleanup(); r.cleanup();
  const writes = r.writes.length, requests = r.resolutions.length;
  event?.({ target: scene, propertyName: 'opacity' });
  for (const callback of callbacks) { callback(); await r.flush(); }
  r.cleanup.resume(); await r.advanceTo(60000);
  images.forEach(released);
  assert.equal(r.writes.length, writes);
  assert.equal(r.resolutions.length, requests);
  assert.equal(photos(r).length, 0);
  assert.equal(r.pending.size, 0);
});

for (const initial of [true, false]) {
  test(`${initial ? 'initial' : 'replacement'} Panorama decode publication stays paused through resume/re-pause`, async () => {
    for (let checkpoint = 0; checkpoint <= 24; checkpoint++) {
      const r = fixture({ deferLoad: initial });
      try {
        if (initial) await r.flush();
        else { await r.cleanup.ready; r.deferNext(); await r.advanceTo(5000); }
        r.cleanup.pause(); r.finishLoad(); await r.flush();
        r.cleanup.resume();
        for (let turn = 0; turn < checkpoint; turn++) await Promise.resolve();
        r.cleanup.pause();
        const writes = r.writes.length, requests = r.resolutions.length;
        await r.flush();
        assert.equal(r.writes.length, writes);
        assert.equal(r.resolutions.length, requests);
        r.cleanup.resume(); await r.cleanup.ready; await r.flush();
        assert.ok(photos(r).length >= 1);
      } finally { r.cleanup(); }
    }
  });
}

test('Panorama decode handoff checkpoints reject late publication and requests after close', async () => {
  for (let checkpoint = 0; checkpoint <= 24; checkpoint++) {
    const r = fixture({ deferLoad: true }); await r.flush(); r.finishLoad();
    for (let turn = 0; turn < checkpoint; turn++) await Promise.resolve();
    r.cleanup(); const writes = r.writes.length, requests = r.resolutions.length;
    await r.cleanup.ready; await r.flush();
    assert.equal(r.writes.length, writes);
    assert.equal(r.resolutions.length, requests);
    assert.equal(photos(r).length, 0);
    assert.equal(r.pending.size, 0);
  }
});

test('failed Panorama preparation retains the ready scene and recovers on the next cadence', async () => {
  const r = fixture();
  try {
    await r.cleanup.ready;
    const before = photos(r)[0];
    r.deferNext(); await r.advanceTo(5000); r.failLoad(); await r.flush();
    assert.equal(photos(r)[0], before);
    assert.equal(r.slot(before).opacity(), 1);
    await r.advanceTo(10000);
    assert.equal(photos(r).length, 2);
    await r.advanceTo(11800); r.end(photos(r).at(-1)); released(before);
    assert.equal(photos(r).length, 1);
  } finally { r.cleanup(); }
});
