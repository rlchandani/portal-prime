/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */
import test from 'node:test';
import assert from 'node:assert/strict';
import { createHash } from 'node:crypto';
import { createSceneFixture } from './helpers/slideshow_scene_fixture.mjs';

const steadyModes = ['kenBurns', 'diptych', 'framed', 'push', 'memories',
  'panorama', 'mosaic', 'cinema', 'heroSidebar'];
const allModes = [...steadyModes, 'galleryWall', 'polaroid', 'filmstrip'];
const photographs = () => Array.from({ length: 16 }, (_, i) => ({
  seed: 'motion-' + i,
  w: i % 3 === 0 ? 10000 : i % 3 === 1 ? 400 : 1600,
  h: i % 3 === 1 ? 16000 : 1000,
}));
const fixture = (mode, options) => createSceneFixture({ mode, photos: photographs(), ...options });
const photoClasses = ['ss-hero', 'ss-img', 'ss-print', 'ss-pan', 'ss-ph'];
const photos = r => r.nodes().filter(node =>
  photoClasses.some(name => node.classList.contains(name)) &&
  node.style.backgroundImage?.startsWith('url('));
function scene(image) {
  let node = image;
  while (node.parent && !node.classList.contains('on')) node = node.parent;
  return node;
}
function still(r, image) {
  const style = r.computed(image);
  assert.equal(style.animation || 'none', 'none', 'A steady photo must not run a transform animation');
  assert.equal(style['will-change'] || 'auto', 'auto', 'A still photo must not request a compositor layer');
}
function snapshot(r) {
  return {
    nodes: r.nodes().map(node => ({ classes: node.className, style: { ...node.style },
      computed: r.computed(node), opacity: node.opacity() })),
    cursor: r.cursor.i,
    resources: r.resolutions.map(({ photo, edge, url }) => ({ seed: photo.seed, edge, url })),
    loads: r.loads.map(load => load.url),
    timers: [...r.pending.values()].map(task => task.at),
  };
}

// Captured by executing the current source before adding the preference gates.
// Pin geometry, publication order, CSS inputs and timers; matching two new paths alone
// would miss a common regression in both. Coercions count the existing random selections.
const legacyOutput = [
  ['kenBurns', 30, '1ec9a0c298c708acbc653e8987006355af4bea462fbe0e7606cba08f9666b675'],
  ['diptych', 12, '1306f202be7a187821287abc97d0bd6ab6664f761f9052081b157fa85fd116b0'],
  ['framed', 6, '6c84dd7e8fb48d8d0d0e705c878857ccd1849bfa4645c8f19c99210bc73c89a0'],
  ['push', 0, 'fb2b28a70e305ae83268e4e5b933276258f6811750ee8ce4beecd602efe35e8c'],
  ['memories', 0, '6f642dfabcc126c561f90a9e0507820b53b5a9bcb6039c0e4facd602e388a1a3'],
  ['panorama', 0, '03a82e8bf80c00c8a1aeef2c9135a050a2b91c7df7ec75cd9e5bbf22d348cb1f'],
  ['mosaic', 13, 'c5e69de053beac0b2dca4e947fdbcabdf5821604b187fb0f583361bd5d8983c7'],
  ['cinema', 0, '783c87acee8c1a3d2110c15410acd7ee9c178ded8627d5f2750051fee5f84700'],
  ['heroSidebar', 0, 'a2af06e42ebaffa3b7c5b71ec510db4365eb4613782ad3986374eb373ed5a96f'],
  ['galleryWall', 5, '15c1332c17a3e9be02d7331220f21267d81578589fca920ac208a3d9a0ac8702'],
  ['polaroid', 44, 'ab29d2674954ba5667b297904c3f63621a43ce5f9d5b6d7b716b503cda202007'],
  ['filmstrip', 0, 'e55bbd40fc31709d71052f73d61ea3de6333fa2b804527a014cb5d79bde5e677'],
];
for (const [mode, selections, output] of legacyOutput) {
  test(`${mode}: default output matches the pre-change source without extra random selections`, async () => {
    for (const photoMotion of [undefined, true, false]) {
      let draws = 0;
      const r = fixture(mode, { photoMotion, random: { valueOf() { draws++; return .5; } } });
      try {
        await r.cleanup.ready;
        const hash = createHash('sha256');
        for (const time of [0, 3500, 5000, 7500, 10000, 15000, 27500]) {
          await r.advanceTo(time); hash.update(JSON.stringify(snapshot(r)));
        }
        assert.equal(draws, selections, 'Motion must not change random selection order');
        if (photoMotion !== false) assert.equal(hash.digest('hex'), output);
      } finally { r.cleanup(); }
    }
  });
}

// Catches a missing gate in any mode, including reused outgoing slots and Mosaic spotlight beats.
for (const mode of steadyModes) {
  test(`${mode}: photoMotion=false leaves prepared photos steady across replacement beats`, async () => {
    const r = fixture(mode, { photoMotion: false });
    try {
      assert.equal(await r.cleanup.ready, true);
      assert.ok(photos(r).length > 0);
      for (const time of [0, 3500, 5000, 7500, 10000, 15000, 25000, 27500]) {
        await r.advanceTo(time);
        photos(r).forEach(image => still(r, image));
        if (mode === 'mosaic') {
          const cells = r.nodes().filter(node => node.classList.contains('ss-mcell'));
          assert.ok(cells.every(cell => !cell.classList.contains('spot')),
            'The roaming spotlight must not scale steady photos');
        }
      }
      assert.ok(r.resolutions.length > 1, 'Turning motion off must retain photo changes');
    } finally { r.cleanup(); }
  });
}

// Catches treating an absent preference as false, or changing true-mode geometry/resources/timers.
for (const mode of allModes) {
  test(`${mode}: explicit true and the legacy undefined default produce identical output`, async () => {
    const legacy = fixture(mode), enabled = fixture(mode, { photoMotion: true });
    try {
      assert.equal(await legacy.cleanup.ready, true);
      assert.equal(await enabled.cleanup.ready, true);
      for (const time of [0, 3500, 5000, 7500, 10000, 15000, 27500]) {
        await legacy.advanceTo(time); await enabled.advanceTo(time);
        assert.deepEqual(snapshot(enabled), snapshot(legacy), `Unchanged output at ${time}ms`);
      }
    } finally { legacy.cleanup(); enabled.cleanup(); }
  });
}

// Independent legacy expectations prevent both sides of the default comparison becoming still.
for (const [mode, animation] of [
  ['kenBurns', /^kb var\(--motion-duration, 32400ms\) linear forwards$/],
  ['framed', /^breathe 22s linear infinite alternate$/],
  ['push', /^cinematicDrift 5400ms linear forwards$/],
  ['memories', /^cinematicDrift 5400ms linear forwards$/],
  ['panorama', /^dolly 5000ms linear forwards$/],
  ['mosaic', /^(panH|panV|cinematicDrift) 12000ms ease-in-out infinite alternate$/],
  ['cinema', /^cinematicDrift 5400ms linear forwards$/],
]) {
  test(`${mode}: the legacy default retains its existing photo animation`, async () => {
    const r = fixture(mode);
    try {
      await r.cleanup.ready;
      assert.match(r.computed(photos(r)[0]).animation, animation);
      assert.ok(photos(r).some(image => r.computed(image)['will-change'] === 'transform'));
    } finally { r.cleanup(); }
  });
}

test('still Panorama centers its bounded crop on matte and preserves cached face anchors', async () => {
  for (const W of [640, 2160]) {
    for (const focal of [undefined, 0, .25, .5, .8, 1]) {
      const r = fixture('panorama', { photoMotion: false, W, H: 1440,
        photos: [{ seed: 'wide', w: 10000, h: 1000,
          ...(focal === undefined ? {} : { fx: focal, fy: .4 }) }] });
      try {
        await r.cleanup.ready;
        const image = photos(r)[0], width = parseFloat(image.style.width), height = parseFloat(image.style.height);
        const percent = Number(/translateX\(([^)]+)%\)/.exec(image.style.transform)?.[1]);
        const shift = percent * width / 100;
        still(r, image);
        assert.equal(width, W * 2);
        assert.equal(image.style.backgroundSize, 'contain');
        assert.equal(image.style.backgroundPosition, 'center');
        assert.equal(parseFloat(image.style.top), (1440 - height) / 2);
        assert.equal(r.computed(scene(image)).background, '#1c1b19');
        if (focal === undefined) assert.equal(shift, (W - width) / 2);
        else {
          const face = width * focal + shift;
          assert.ok(face >= -.001 && face <= W + .001, 'The cached face remains inside the viewport');
        }
      } finally { r.cleanup(); }
    }
  }
});

test('still Mosaic contains mismatched photos without changing BSP geometry', async () => {
  const r = fixture('mosaic', { photoMotion: false });
  try {
    await r.cleanup.ready;
    const cells = r.nodes().filter(node => node.classList.contains('ss-mcell'));
    assert.equal(cells.length, 9);
    const geometry = cells.map(cell => JSON.stringify(cell.style));
    for (const image of photos(r)) {
      const cell = image.parent.parent, W = parseFloat(cell.style.width), H = parseFloat(cell.style.height);
      const width = parseFloat(image.style.width), height = parseFloat(image.style.height);
      const left = parseFloat(image.style.left), top = parseFloat(image.style.top);
      assert.ok(left >= 0 && top >= 0 && left + width <= W && top + height <= H);
      assert.equal(image.style.backgroundSize, 'contain');
      assert.equal(r.computed(cell).background, '#1c1b19');
      assert.equal(image.style.transform, 'none');
      still(r, image);
    }
    await r.advanceTo(7500);
    assert.deepEqual(cells.map(cell => JSON.stringify(cell.style)), geometry);
  } finally { r.cleanup(); }
});

test('still Mosaic retains edge face points inside complete contained photos', async () => {
  for (const [w, h] of [[10000, 1000], [400, 16000]]) {
    for (const focal of [0, .25, .5, .8, 1]) {
      const r = fixture('mosaic', { photoMotion: false,
        photos: [{ seed: 'face', w, h, fx: focal, fy: focal }] });
      try {
        await r.cleanup.ready;
        const image = photos(r)[0], W = parseFloat(image.style.width), H = parseFloat(image.style.height);
        const scale = Math.min(W / w, H / h);
        const left = parseFloat(image.style.left) + (W - w * scale) / 2;
        const top = parseFloat(image.style.top) + (H - h * scale) / 2;
        const x = left + w * scale * focal, y = top + h * scale * focal;
        assert.ok(left >= 0 && top >= 0 && left + w * scale <= 2160 && top + h * scale <= 1440);
        assert.ok(x >= 0 && x <= 2160 && y >= 0 && y <= 1440);
        assert.equal(image.style.backgroundSize, 'contain');
        assert.equal(image.style.transform, 'none');
        still(r, image);
      } finally { r.cleanup(); }
    }
  }
});

test('still Framed Print keeps the complete upright portrait centered inside its original mat', async () => {
  const r = fixture('framed', { photoMotion: false,
    photos: [{ seed: 'portrait', w: 1000, h: 1600 }] });
  try {
    await r.cleanup.ready;
    const image = photos(r)[0];
    assert.equal(image.style.width, '648px');
    assert.equal(image.style.height, '1037px');
    assert.equal(image.style.backgroundSize, 'contain');
    assert.equal(image.style.backgroundPosition, 'center');
    assert.equal(r.computed(image.parent.parent).padding, '46px');
    assert.equal(r.computed(image.parent.parent.parent).padding, '10px');
    assert.equal(scene(image).style.transform, undefined);
    still(r, image);
  } finally { r.cleanup(); }
});

// Catches accidentally disabling scene opacity transitions or advancing timers while paused.
for (const [mode, fadeMs] of [
  ['kenBurns', 2200], ['diptych', 1800], ['framed', 2400], ['push', 2200],
  ['memories', 2000], ['panorama', 1800], ['mosaic', 1100], ['cinema', 2200], ['heroSidebar', 1100],
]) {
  test(`${mode}: still photos keep decode readiness, dissolves, pause/resume, and terminal cleanup`, async () => {
    const r = fixture(mode, { photoMotion: false, deferLoad: true });
    try {
      let ready = false;
      r.cleanup.ready.then(() => { ready = true; });
      await r.flush(); await r.advanceTo(20000);
      assert.equal(ready, false);
      assert.equal(photos(r).length, 0, 'Readiness must still wait for decode');
      assert.equal(r.pending.size, 0);
      r.finishLoad(); await r.flush();
      assert.equal(await r.cleanup.ready, true);
      assert.ok(photos(r).every(image => scene(image).opacity() === 1));
      await r.advanceTo(25000);
      const incoming = photos(r).find(image => scene(image).opacity() === 0);
      assert.ok(incoming, 'The photo cadence still admits a dissolving replacement');
      const incomingScene = scene(incoming);
      await r.advanceTo(25000 + fadeMs / 2);
      const held = incomingScene.opacity();
      assert.ok(held > 0 && held < 1);
      const urls = photos(r).map(image => image.style.backgroundImage), requests = r.resolutions.length;
      r.cleanup.pause(); await r.advanceTo(50000);
      assert.equal(incomingScene.opacity(), held);
      assert.equal(r.pending.size, 0);
      assert.equal(r.resolutions.length, requests);
      assert.deepEqual(photos(r).map(image => image.style.backgroundImage), urls);
      photos(r).forEach(image => still(r, image));
      r.cleanup.resume(); await r.advanceTo(50000 + fadeMs / 2 + 100);
      assert.equal(incomingScene.opacity(), 1);
      photos(r).forEach(image => still(r, image));
      const callbacks = [...r.pending.values()].map(task => task.fn);
      r.cleanup();
      const writes = r.writes.length;
      for (const callback of callbacks) { await callback(); await r.flush(); }
      r.cleanup.resume(); await r.advanceTo(70000);
      assert.equal(r.pending.size, 0);
      assert.equal(r.writes.length, writes, 'Closed owners reject stale callbacks');
      assert.equal(photos(r).length, 0);
      assert.ok(r.loads.every(load => load.signal.aborted));
    } finally { r.cleanup(); }
  });
}

// Catches broad CSS/scene suppression affecting modes whose movement represents navigation.
for (const mode of ['galleryWall', 'polaroid', 'filmstrip']) {
  test(`${mode}: photoMotion=false preserves existing photo changes and navigation output`, async () => {
    const legacy = fixture(mode), disabled = fixture(mode, { photoMotion: false });
    try {
      await legacy.cleanup.ready; await disabled.cleanup.ready;
      for (const time of [0, 3500, 5000, 7500, 10000, 15000]) {
        await legacy.advanceTo(time); await disabled.advanceTo(time);
        assert.deepEqual(snapshot(disabled), snapshot(legacy));
        photos(disabled).forEach(image => still(disabled, image));
      }
      assert.ok(disabled.resolutions.length > 1);
      if (mode === 'filmstrip') {
        const track = () => disabled.nodes().find(node => node.classList.contains('ss-track'));
        const before = track().style.transform;
        assert.equal(await disabled.cleanup.next(), true);
        assert.notEqual(track().style.transform, before);
        assert.equal(await disabled.cleanup.previous(), true);
        assert.equal(track().style.transform, before);
        disabled.cleanup.pause();
        const paused = track().style.transform, requests = disabled.resolutions.length;
        await disabled.advanceTo(50000);
        assert.equal(track().style.transform, paused);
        assert.equal(disabled.resolutions.length, requests);
        disabled.cleanup.resume(); await disabled.advanceTo(55000);
        assert.notEqual(track().style.transform, paused);
      }
    } finally { legacy.cleanup(); disabled.cleanup(); }
  });
}
