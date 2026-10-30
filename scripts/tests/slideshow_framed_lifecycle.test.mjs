/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */
import test from 'node:test';
import assert from 'node:assert/strict';
import { createSceneFixture as fixture } from './helpers/slideshow_scene_fixture.mjs';

function retired(r, print) {
  assert.equal(r.state(print).animation, 'none', 'Retirement stops the animation');
  assert.equal(r.state(print).promotion, 'auto', 'An invisible slot must release its promotion hint');
  assert.equal(r.state(print).opacity, 0);
  assert.equal(r.slot(print).classList.contains('retired'), true);
  assert.equal(print.style.backgroundImage, 'none', 'Retirement releases the decoded image URL');
}
function breathing(r, print) {
  assert.match(r.state(print).animation, /^breathe 22s linear infinite alternate$/);
  assert.equal(r.state(print).promotion, 'transform');
  assert.equal(r.state(print).playState, 'running');
}

test('Cinematic Push shows an opaque whole photo without duplicate blurred layers', async () => {
  const r = fixture({ mode: 'push' });
  try {
    assert.equal(await r.cleanup.ready, true);
    assert.equal(r.nodes().filter(node => node.classList.contains('ss-fill')).length, 0);
    const photo = r.incoming();
    assert.ok(photo);
    assert.equal(r.state(photo).opacity, 1);
    assert.equal(r.computed(photo)['background-size'], 'contain');
    assert.equal(r.computed(photo).inset, '5%');
    assert.match(r.state(photo).animation, /^cinematicDrift 5400ms linear forwards$/);
    assert.equal(r.state(photo).promotion, 'transform');
    assert.ok(r.resolutions.every(resource => resource.edge === 2200));
  } finally { r.cleanup(); }
});

test('Cinematic Push retains the opaque outgoing scene until each incoming dissolve completes', async () => {
  const r = fixture({ mode: 'push' });
  try {
    await r.cleanup.ready;
    for (let swap = 1; swap <= 6; swap++) {
      await r.advanceTo(swap * 5000);
      const incoming = r.incoming(), outgoing = r.prints().find(photo => photo !== incoming);
      assert.equal(r.state(incoming).opacity, 0);
      assert.equal(r.state(outgoing).opacity, 1);
      assert.equal(r.computed(r.slot(incoming)).background, '#1c1b19');
      await r.advanceTo(swap * 5000 + 2199);
      assert.ok(r.state(incoming).opacity > 0 && r.state(incoming).opacity < 1);
      assert.equal(r.state(outgoing).opacity, 1);
      await r.advanceTo(swap * 5000 + 2200);
      r.end(incoming);
      retired(r, outgoing);
      assert.equal(r.state(incoming).opacity, 1);
    }
  } finally { r.cleanup(); }
});

test('Cinematic Push freezes the dissolve and motion during a long pause', async () => {
  const r = fixture({ mode: 'push' });
  try {
    await r.cleanup.ready; await r.advanceTo(5000); await r.advanceTo(5700);
    const incoming = r.incoming(), outgoing = r.prints().find(photo => photo !== incoming);
    const opacity = r.state(incoming).opacity;
    r.cleanup.pause();
    assert.equal(r.state(incoming).playState, 'paused');
    assert.equal(r.state(outgoing).playState, 'paused');
    await r.advanceTo(25000);
    assert.equal(r.state(incoming).opacity, opacity);
    assert.notEqual(outgoing.style.backgroundImage, 'none');
    r.cleanup.resume();
    await r.advanceTo(26500); r.end(incoming);
    retired(r, outgoing);
  } finally { r.cleanup(); }
});

test('Cinematic Push retains cached-face photographs still and whole', async () => {
  const r = fixture({ mode: 'push', photos: [{ seed: 'face', w: 900, h: 1600, fx: .1, fy: .4 }] });
  try {
    await r.cleanup.ready;
    const photo = r.incoming();
    assert.equal(r.computed(photo)['background-size'], 'contain');
    assert.equal(r.state(photo).animation, 'none');
    assert.equal(r.state(photo).promotion, 'auto');
  } finally { r.cleanup(); }
});

test('Cinematic Push rejects publication after cleanup at every decode admission microtask', async () => {
  for (let checkpoint = 0; checkpoint <= 16; checkpoint++) {
    const r = fixture({ mode: 'push', deferLoad: true });
    await r.flush(); r.finishLoad();
    for (let turn = 0; turn < checkpoint; turn++) await Promise.resolve();
    r.cleanup();
    const writes = r.writes.length;
    await r.cleanup.ready; await r.flush();
    assert.equal(r.writes.length, writes, `Cleanup at microtask ${checkpoint} forbids later publication`);
    r.prints().forEach(photo => retired(r, photo));
    assert.equal(r.pending.size, 0);
  }
});

// Catches fading the outgoing scene, retiring before incoming opacity 1, or changing cadence.
test('Framed Print admits an opaque first scene and fades only incoming scenes over eight swaps', async () => {
  const r = fixture();
  try {
    assert.equal(await r.cleanup.ready, true);
    const first = r.prints().find(print => r.state(print).visible);
    breathing(r, first);
    assert.equal(r.state(first).opacity, 1, 'Ready must mean fully opaque initial content');
    assert.equal(r.computed(r.slot(first)).background,
      'radial-gradient(120% 80% at 50% -10%, #23231f 0%, #141414 45%, #0b0b0c 100%)',
      'Each full-viewport scene must cover differently sized outgoing frames with the same room');
    assert.equal(r.computed(r.slot(first)).inset, '0');
    for (let swap = 1; swap <= 8; swap++) {
      await r.advanceTo(swap * 5000);
      const incoming = r.incoming();
      const outgoing = r.prints().find(print => print !== incoming);
      assert.equal(incoming.style.backgroundImage, `url("generated://generated-${swap % 8}")`);
      breathing(r, incoming); breathing(r, outgoing);
      assert.equal(r.state(incoming).opacity, 0);
      assert.equal(r.state(outgoing).opacity, 1, 'Outgoing must never fade underneath incoming');
      assert.equal(r.slot(incoming).parent.children.at(-1), r.slot(incoming), 'Incoming paints above outgoing');
      assert.equal(r.computed(r.slot(incoming)).background, r.computed(r.nodes().find(node =>
        node.classList.contains('ss-room'))).background, 'Incoming covers the outgoing frame with the same room');
      await r.advanceTo(swap * 5000 + 2399);
      assert.ok(r.state(incoming).opacity > 0 && r.state(incoming).opacity < 1);
      assert.equal(r.state(outgoing).opacity, 1);
      breathing(r, outgoing);
      await r.advanceTo(swap * 5000 + 2400);
      assert.equal(r.state(incoming).opacity, 1);
      r.end(incoming);
      retired(r, outgoing); breathing(r, incoming);
      assert.equal(r.pending.size, 1, 'Completion cancels its retirement fallback');
    }
    assert.equal(r.resolutions.length, 9, 'Resolve once per preparation');
    assert.ok(r.resolutions.every(resource => resource.edge === 1800));
    assert.equal(r.cursor.i, 9);
  } finally { r.cleanup(); }
});

// Catches outgoing, bubbled, unrelated and premature events retiring a still-visible print.
test('only the current incoming opacity completion may retire the outgoing print', async () => {
  const r = fixture();
  try {
    await r.cleanup.ready;
    await r.advanceTo(5000);
    const incoming = r.incoming();
    const outgoing = r.prints().find(print => print !== incoming);
    r.end(incoming); r.end(outgoing); breathing(r, incoming); breathing(r, outgoing);
    // Complete the simulated CSS transition before dispatching the owned fallback timer.
    r.elapseTo(7400);
    r.end(incoming, { propertyName: 'transform' }); breathing(r, outgoing);
    r.end(incoming, { target: incoming }); breathing(r, outgoing);
    r.end(outgoing); breathing(r, outgoing);
    r.end(incoming); retired(r, outgoing); breathing(r, incoming);
  } finally { r.cleanup(); }
});

// Catches delayed retirement from an earlier use overriding a slot's newer fade/reveal.
test('rapid slot reuse invalidates old retirement callbacks', async () => {
  const r = fixture({ photoMs: 1000 });
  try {
    await r.cleanup.ready;
    await r.advanceTo(1000);
    const oldIncoming = r.incoming(), stale = r.slot(oldIncoming).ontransitionend;
    const staleFallback = [...r.pending.values()].find(task => task.at > 2000).fn;
    await r.advanceTo(2000);
    const current = r.incoming();
    assert.notEqual(current, oldIncoming);
    stale?.({ target: r.slot(oldIncoming), propertyName: 'opacity' });
    for (const time of [2400, 3000, 3400, 4000, 4400, 5000]) {
      await r.advanceTo(time);
      stale?.({ target: r.slot(oldIncoming), propertyName: 'opacity' });
      await staleFallback(); await r.flush();
      for (const print of r.prints()) breathing(r, print);
      assert.equal(r.pending.size, 2, 'Only the current fade fallback and configured repeat may remain');
      assert.equal(r.state(r.prints().find(print => print !== r.incoming())).opacity, 1);
    }
  } finally { r.cleanup(); }
});

// Catches a lost completion event leaking URLs or fallback retirement consuming paused time.
test('the single retirement fallback uses remaining active time when completion events are absent', async () => {
  const r = fixture();
  try {
    await r.cleanup.ready;
    await r.advanceTo(6000);
    const incoming = r.incoming(), outgoing = r.prints().find(print => print !== incoming);
    r.cleanup.pause(); await r.advanceTo(20000); r.cleanup.resume();
    await r.advanceTo(21499);
    assert.equal(r.state(incoming).opacity, 1);
    assert.equal(r.state(outgoing).opacity, 1);
    assert.notEqual(outgoing.style.backgroundImage, 'none');
    assert.equal(r.pending.size, 2);
    await r.advanceTo(21500);
    retired(r, outgoing);
    assert.equal(r.pending.size, 1, 'Only the configured repeat remains; no polling loop');
    await r.advanceTo(23999); assert.equal(r.loads.length, 2);
    await r.advanceTo(24000); assert.equal(r.loads.length, 3, 'Repeat retains its own remaining interval');
  } finally { r.cleanup(); }
});

// Catches losing the shared pause/resume protocol when adding mode-specific cleanup.
test('old-WebView pause freezes incoming opacity and both prints until the remaining fade resumes', async () => {
  const r = fixture();
  try {
    await r.cleanup.ready;
    await r.advanceTo(6000);
    const incoming = r.incoming(), outgoing = r.prints().find(print => print !== incoming);
    const held = r.state(incoming).opacity;
    assert.ok(held > 0 && held < 1);
    r.cleanup.pause();
    assert.equal(r.pending.size, 0);
    await r.advanceTo(20000);
    assert.equal(r.state(incoming).opacity, held, 'Pause must freeze CSS pixels, not only JS timers');
    assert.equal(r.state(outgoing).opacity, 1);
    assert.equal(r.state(incoming).playState, 'paused');
    assert.equal(r.state(outgoing).playState, 'paused');
    r.end(incoming); assert.equal(r.slot(outgoing).classList.contains('retired'), false);
    r.cleanup.resume();
    await r.advanceTo(21399);
    assert.ok(r.state(incoming).opacity < 1); breathing(r, outgoing);
    await r.advanceTo(21400);
    assert.equal(r.state(incoming).opacity, 1);
    r.end(incoming); retired(r, outgoing);
    assert.equal(r.pending.size, 1);
  } finally { r.cleanup(); }
});

// Catches leaked event listeners, scheduled work, and mutation after disposal.
test('destruction cancels retirement and repeat callbacks and stops both prints', async () => {
  const r = fixture();
  await r.cleanup.ready;
  await r.advanceTo(5000);
  const capturedCallbacks = [...r.pending.values()].map(task => task.fn);
  const handler = r.slot(r.incoming()).ontransitionend, target = r.slot(r.incoming());
  r.cleanup(); r.cleanup();
  assert.equal(r.pending.size, 0);
  for (const print of r.prints()) retired(r, print);
  for (const node of r.nodes()) {
    assert.equal(node.ontransitionend == null, true, 'Disposal releases each owned transition handler');
  }
  const snapshot = () => r.prints().map(print => JSON.stringify({ style: print.style,
    slot: r.slot(print).className, animation: r.state(print).animation,
    promotion: r.state(print).promotion, playState: r.state(print).playState }));
  const before = snapshot();
  const published = r.writes.length;
  handler?.({ target, propertyName: 'opacity' });
  for (const fn of capturedCallbacks) await fn();
  r.cleanup.resume();
  await r.advanceTo(60000);
  for (const print of r.prints()) r.end(print);
  assert.equal(r.pending.size, 0);
  assert.deepEqual(snapshot(), before);
  assert.equal(r.writes.length, published, 'Captured work cannot re-publish resources after cleanup');
});

// Held inline opacity may survive disposal, so visibility and resource release own cleanup.
test('cleanup during a held fade hides both scenes and cannot be resumed by stale work', async () => {
  const r = fixture();
  await r.cleanup.ready; await r.advanceTo(6000);
  const incoming = r.incoming(), target = r.slot(incoming), handler = target.ontransitionend;
  const callbacks = [...r.pending.values()].map(task => task.fn);
  r.cleanup.pause(); r.cleanup();
  for (const print of r.prints()) {
    assert.equal(r.computed(r.slot(print)).visibility, 'hidden');
    assert.equal(print.style.backgroundImage, 'none');
    assert.equal(r.state(print).animation, 'none');
    assert.equal(r.state(print).promotion, 'auto');
    assert.equal(r.slot(print).ontransitionend, null);
  }
  const snapshot = JSON.stringify(r.prints().map(print => ({
    style: print.style, root: r.slot(print).style, classes: r.slot(print).className,
  })));
  const count = r.writes.length;
  r.cleanup.resume();
  handler?.({ target, propertyName: 'opacity' });
  for (const callback of callbacks) await callback();
  await r.advanceTo(60000);
  assert.equal(r.pending.size, 0);
  assert.equal(r.writes.length, count);
  assert.equal(JSON.stringify(r.prints().map(print => ({
    style: print.style, root: r.slot(print).style, classes: r.slot(print).className,
  }))), snapshot);
});

// Catches an initial decode publishing or rearming after the mode has been destroyed.
test('destruction during preparation prevents late publication', async () => {
  const r = fixture({ deferLoad: true });
  await r.flush();
  r.cleanup(); r.finishLoad();
  assert.equal(await r.cleanup.ready, false);
  await r.advanceTo(60000);
  assert.equal(r.pending.size, 0);
  for (const print of r.prints()) {
    retired(r, print);
  }
  assert.ok(r.writes.every(write => write.value === 'none'));
});

// Catches disposal between t.load's admission and the caller's publication continuation.
test('cleanup at the decode admission boundary cannot reassign retired image URLs', async () => {
  for (let checkpoint = 0; checkpoint <= 16; checkpoint++) {
    const r = fixture({ deferLoad: true });
    await r.flush(); r.finishLoad();
    for (let turn = 0; turn < checkpoint; turn++) await Promise.resolve();
    r.cleanup();
    const count = r.writes.length;
    await r.cleanup.ready; await r.flush();
    assert.equal(r.writes.length, count, `Cleanup at microtask ${checkpoint} forbids later resource writes`);
    assert.equal(r.pending.size, 0);
    r.prints().forEach(print => retired(r, print));
  }
});

// Catches an in-flight replacement repopulating a disposed or paused scene.
test('cleanup while a replacement decode is paused prevents late resource publication', async () => {
  const r = fixture();
  await r.cleanup.ready;
  r.deferNext(); await r.advanceTo(5000);
  r.cleanup.pause(); r.finishLoad(); await r.flush();
  assert.equal(r.writes.filter(write => write.value.startsWith('url(')).length, 1);
  r.cleanup();
  const count = r.writes.length;
  r.cleanup.resume(); await r.advanceTo(60000);
  assert.equal(r.writes.length, count);
  assert.equal(r.pending.size, 0);
  assert.equal(r.loads.at(-1).signal.aborted, true);
  r.prints().forEach(print => retired(r, print));
});

// Catches clearing the current resource before replacement decode has succeeded.
test('failed replacement preparation keeps the current opaque scene and configured retry interval', async () => {
  const r = fixture();
  try {
    await r.cleanup.ready;
    const current = r.incoming(), url = current.style.backgroundImage;
    r.deferNext(); await r.advanceTo(5000); r.failLoad(); await r.flush();
    assert.equal(r.incoming(), current);
    assert.equal(r.state(current).opacity, 1);
    assert.equal(current.style.backgroundImage, url);
    assert.equal(r.writes.filter(write => write.value.startsWith('url(')).length, 1);
    await r.advanceTo(9999); assert.equal(r.loads.length, 2);
    await r.advanceTo(10000); assert.equal(r.loads.length, 3);
  } finally { r.cleanup(); }
});

// Catches replacing a decoded URL with a new resolver result before publishing the print.
test('replacement publishes only its exact admitted URL and waits for paused decode publication', async () => {
  const r = fixture({ changingUrls: true });
  try {
    await r.cleanup.ready;
    const current = r.incoming(), firstUrl = current.style.backgroundImage;
    r.deferNext(); await r.advanceTo(5000);
    assert.equal(r.loads.at(-1).url, 'generated://generated-1-r2');
    assert.equal(r.resolutions.length, 2);
    assert.equal(r.state(current).opacity, 1);
    assert.equal(current.style.backgroundImage, firstUrl);
    r.cleanup.pause(); r.finishLoad(); await r.flush();
    assert.equal(r.writes.filter(write => write.value.startsWith('url(')).length, 1);
    await r.advanceTo(20000); r.cleanup.resume(); await r.flush();
    assert.notEqual(r.incoming(), current);
    assert.equal(r.incoming().style.backgroundImage, 'url("generated://generated-1-r2")');
    assert.equal(r.resolutions.length, 2, 'Publication must not resolve again after decode');
    assert.equal(r.state(current).opacity, 1);
    assert.equal(r.state(r.incoming()).opacity, 0);
  } finally { r.cleanup(); }
});

// Catches lost portrait geometry, motion overriding cached hints, or stale still state on reuse.
test('cached face hints show a whole still upright print; reused no-hint slots breathe again', async () => {
  const r = fixture({ photos: [
    { seed: 'portrait-face', w: 1000, h: 1600, fx: .25, fy: .2 },
    { seed: 'landscape', w: 1600, h: 1000 },
    { seed: 'portrait', w: 1000, h: 1600 },
  ] });
  try {
    await r.cleanup.ready;
    const first = r.incoming();
    assert.equal(first.style.width, '648px'); assert.equal(first.style.height, '1037px');
    assert.equal(r.state(first).animation, 'none');
    assert.equal(r.state(first).promotion, 'auto');
    assert.equal(first.style.backgroundSize, 'contain');
    assert.equal(first.style.backgroundPosition, 'center');
    assert.equal(r.computed(first.parent.parent).padding, '46px');
    assert.equal(r.computed(first.parent.parent.parent).padding, '10px');
    await r.advanceTo(5000);
    const second = r.incoming();
    assert.equal(second.style.width, '1296px'); assert.equal(second.style.height, '810px');
    breathing(r, second);
    await r.advanceTo(10000);
    assert.equal(r.incoming(), first);
    breathing(r, first);
    assert.equal(r.slot(first).style.transform, undefined, 'Photo presentation stays upright');
  } finally { r.cleanup(); }
});
