/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */
import test from 'node:test';
import assert from 'node:assert/strict';
import { createSceneFixture } from './helpers/slideshow_scene_fixture.mjs';

function fixture(options = {}) {
  const events = new Set();
  const r = createSceneFixture({ mode: 'cinema', W: 1600, H: 1000,
    photoMotion: false, photos: [{ seed: 'wide', w: 3000, h: 1000 }], ...options,
    configureWindow(window) {
      window.addEventListener = (name, fn) => { if (name === 'resize') events.add(fn); };
      window.removeEventListener = (name, fn) => { if (name === 'resize') events.delete(fn); };
    },
  });
  const root = r.nodes()[0]; root.clientWidth = 1600; root.clientHeight = 1000;
  return Object.assign(r, { events, resize(w, h) {
    root.clientWidth = w; root.clientHeight = h;
    for (const fn of [...events]) fn();
  }, photos: () => r.prints().filter(n => n.style.backgroundImage?.startsWith('url(')) });
}
const geometry = image => ['width', 'height', 'left', 'top'].map(k => image.style[k]);
function fits(image, W, H) {
  const width = parseFloat(image.style.width), height = parseFloat(image.style.height),
    left = parseFloat(image.style.left), top = parseFloat(image.style.top), scopeH = H * .78;
  assert.ok(width > 0 && width <= W + .00001, `Width ${width} exceeds viewport ${W}`);
  assert.ok(height > 0 && height <= scopeH + .00001, `Height ${height} exceeds Cinema ${scopeH}`);
  assert.ok(left >= 0 && left + width <= W + .00001);
  assert.ok(top >= 0 && top + height <= scopeH + .00001);
  assert.ok(Math.abs(left - (W - width) / 2) < .00001);
  assert.ok(Math.abs(top - (scopeH - height) / 2) < .00001);
}
for (const shape of [{seed:'wide',w:3000,h:1000},{seed:'portrait',w:500,h:1500}]) {
  test(`Cinema retains ${shape.seed} photo through landscape and portrait resize`, async () => {
    const r = fixture({ photos: [shape] });
    try {
      await r.cleanup.ready; const image = r.photos()[0], url = image.style.backgroundImage,
        requests = r.resolutions.length; let time = 0;
      fits(image, 1600, 1000);
      for (const [w,h] of [[640,400],[800,1200],[1600,1000]]) {
        r.resize(w,h); await r.advanceTo(time += 16); fits(image,w,h);
        assert.equal(r.photos()[0],image); assert.equal(image.style.backgroundImage,url);
        assert.equal(r.resolutions.length,requests);
      }
    } finally { r.cleanup(); }
  });
}
test('Cinema publishes a pending initial decode using the current viewport', async () => {
  const r = fixture({ deferLoad: true });
  try {
    await r.flush(); r.resize(640,400); await r.advanceTo(16);
    r.finishLoad(); await r.cleanup.ready; fits(r.photos()[0],640,400);
  } finally { r.cleanup(); }
});
test('Cinema reflows retained layers and subsequent photos at current bounds', async () => {
  const r = fixture({ photos: [{seed:'a',w:3000,h:1000},{seed:'b',w:1800,h:1200}], photoMs:5000 });
  try {
    await r.cleanup.ready; r.resize(640,400); await r.advanceTo(5016);
    assert.equal(r.photos().length,2); r.photos().forEach(p=>fits(p,640,400));
  } finally { r.cleanup(); }
});
test('Cinema coalesces resize callbacks and defers covered layout writes', async () => {
  const r = fixture();
  try {
    await r.cleanup.ready; const image=r.photos()[0], original=geometry(image), requests=r.resolutions.length;
    r.cleanup.pause(); for (let w=700;w>=640;w--) r.resize(w,400);
    await r.advanceTo(1000); assert.deepEqual(geometry(image),original);
    r.cleanup.resume(); await r.advanceTo(1016); fits(image,640,400);
    assert.equal(r.resolutions.length,requests);
  } finally { r.cleanup(); }
});
test('Cinema close retires resize listener and pending callbacks', async () => {
  const r=fixture(); await r.cleanup.ready; r.resize(640,400);
  const callbacks=[...r.pending.values()].map(task=>task.fn), images=r.prints();
  r.cleanup(); const after=images.map(geometry),writes=r.writes.length,requests=r.resolutions.length;
  for (const fn of callbacks) { fn(); await r.flush(); }
  r.resize(800,1200); await r.advanceTo(60000);
  assert.deepEqual(images.map(geometry),after); assert.equal(r.events.size,0);
  assert.equal(r.writes.length,writes); assert.equal(r.resolutions.length,requests);assert.equal(r.pending.size,0);
});
