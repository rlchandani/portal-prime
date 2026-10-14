/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */
import test from 'node:test';
import assert from 'node:assert/strict';
import { browserHarness } from './remote-browser-harness.mjs';

async function touchpad(cursorRoute) {
  const h = await browserHarness({
    saved: [{ base: 'http://127.0.0.1:8877', name: 'Fixture', token: 'fixture-only' }],
    routes: { '/remote/cursor': cursorRoute || (({ response }) => response({ ok: true })) },
  });
  h.eval('go("remote")'); await h.settle();
  const pad = h.document.body.querySelector('.pad');
  assert.ok(pad);
  const event = (name, overrides = {}) => {
    const value = {
      button: 0, pointerId: 1, clientX: 0, clientY: 0, preventDefault() {}, ...overrides,
    };
    for (const listener of pad.listeners[name] || []) listener(value);
  };
  const calls = () => h.calls.filter(call => call.path === '/remote/cursor');
  return { h, event, calls };
}

test('a cancelled queued drag never sends and the next drag responds on the same page', async () => {
  const { h, event, calls } = await touchpad();
  event('pointerdown'); event('pointermove', { clientX: 30, clientY: 20 });
  event('pointercancel'); await h.tick(35);
  assert.equal(calls().length, 0);
  event('pointerdown', { clientX: 100, clientY: 100 });
  event('pointermove', { clientX: 109, clientY: 104 }); await h.tick(35);
  assert.equal(calls().length, 1);
  assert.deepEqual(JSON.parse(calls()[0].opts.body), { dx: 9 * 1.9, dy: 4 * 1.9 });
  h.hide();
});

async function heldTouchpad() {
  const pending = [];
  const fixture = await touchpad(({ response }) => new Promise(resolve => {
    pending.push({ resolve, response, released: false });
  }));
  const release = (index, status = 200) => {
    const request = pending[index];
    assert.ok(request, `Cursor request ${index + 1} has started`);
    if (request.released) return;
    request.released = true;
    request.resolve(request.response({ ok: status === 200 }, status));
  };
  return {
    ...fixture,
    key: key => fixture.event('keydown', { key }),
    release,
    taps: () => fixture.h.calls.filter(call => call.path === '/remote/tap'),
    writes: () => fixture.h.calls.filter(call =>
      ['/remote/cursor', '/remote/tap'].includes(call.path)),
    cleanup: async () => {
      fixture.h.hide();
      pending.forEach((_, index) => release(index));
      await fixture.h.settle();
    },
  };
}

async function queueMovementBehindHeldCursor(fixture) {
  fixture.key('ArrowRight');
  await fixture.h.settle();
  assert.equal(fixture.calls().length, 1);
  fixture.event('pointerdown', { clientX: 100, clientY: 100 });
  fixture.event('pointermove', { clientX: 107, clientY: 103 });
  fixture.event('pointerup', { clientX: 107, clientY: 103 });
}

const tapActions = [
  ['pointer tap', fixture => {
    fixture.event('pointerdown', { clientX: 200, clientY: 200 });
    fixture.event('pointerup', { clientX: 200, clientY: 200 });
  }],
  ['Enter', fixture => fixture.key('Enter')],
  ['Space', fixture => fixture.key(' ')],
  ['standalone Tap button', fixture => fixture.h.click('Tap')],
];

test('a second ArrowRight during a held cursor response preserves its accumulated movement', async () => {
  const fixture = await heldTouchpad();
  try {
    fixture.key('ArrowRight');
    await fixture.h.settle();
    fixture.key('ArrowRight');
    await fixture.h.settle();
    assert.equal(fixture.calls().length, 1, 'only one cursor POST may be in flight');
    fixture.release(0);
    await fixture.h.settle();
    const drain = fixture.h.tick(35);
    await fixture.h.settle();
    assert.equal(fixture.calls().length, 2, 'the accumulated second movement must drain');
    assert.deepEqual(fixture.calls().map(call => JSON.parse(call.opts.body)), [
      { dx: 47.5, dy: 0 }, { dx: 47.5, dy: 0 },
    ]);
    fixture.release(1);
    await drain;
    await fixture.h.tick(35);
    assert.equal(fixture.calls().length, 2, 'movement must not be replayed');
  } finally { await fixture.cleanup(); }
});

for (const [name, tap] of tapActions) {
  test(`${name} waits for both the held cursor and the queued movement before posting a tap`, async () => {
    const fixture = await heldTouchpad();
    try {
      await queueMovementBehindHeldCursor(fixture);
      tap(fixture);
      await fixture.h.settle();
      assert.equal(fixture.taps().length, 0, 'tap cannot pass the first held movement');
      assert.equal(fixture.calls().length, 1);
      fixture.release(0);
      await fixture.h.settle();
      const drain = fixture.h.tick(35);
      await fixture.h.settle();
      assert.equal(fixture.calls().length, 2, 'the second movement must be delivered');
      assert.deepEqual(JSON.parse(fixture.calls()[1].opts.body), { dx: 7 * 1.9, dy: 3 * 1.9 });
      assert.equal(fixture.taps().length, 0, 'tap must also wait for the second held movement');
      fixture.release(1);
      await drain;
      await fixture.h.settle();
      assert.deepEqual(fixture.writes().map(call => call.path), [
        '/remote/cursor', '/remote/cursor', '/remote/tap',
      ]);
      assert.ok(fixture.writes().every(call => call.opts.method === 'POST'));
      await fixture.h.tick(35);
      assert.equal(fixture.writes().length, 3, 'the queued tap must post exactly once');
    } finally { await fixture.cleanup(); }
  });
}

for (const [name, retire] of [
  ['leaving Remote', fixture => fixture.h.eval('go("apps")')],
  ['hiding the page', fixture => fixture.h.hide()],
]) {
  test(`${name} retires pending movement and tap while a cursor response is held`, async () => {
    const fixture = await heldTouchpad();
    try {
      await queueMovementBehindHeldCursor(fixture);
      fixture.key('Enter');
      retire(fixture);
      await fixture.h.settle();
      fixture.release(0);
      await fixture.h.settle();
      const drain = fixture.h.tick(35);
      await fixture.h.settle();
      assert.equal(fixture.calls().length, 1, 'retired movement cannot post after completion');
      assert.equal(fixture.taps().length, 0, 'retired tap cannot reach the old position');
      await drain;
    } finally { await fixture.cleanup(); }
  });
}

test('a failed cursor suppresses its queued tap and a subsequent gesture works without retrying old movement', async () => {
  const fixture = await heldTouchpad();
  try {
    fixture.key('ArrowRight');
    await fixture.h.settle();
    fixture.key('Enter');
    await fixture.h.settle();
    assert.equal(fixture.taps().length, 0);
    fixture.release(0, 503);
    await fixture.h.settle();
    await fixture.h.tick(35);
    assert.equal(fixture.taps().length, 0, 'failed movement must not permit a misplaced tap');
    assert.equal(fixture.calls().length, 1, 'failed movement is not retried');

    fixture.event('pointerdown', { clientX: 100, clientY: 100 });
    fixture.event('pointermove', { clientX: 110, clientY: 95 });
    fixture.event('pointerup', { clientX: 110, clientY: 95 });
    const fresh = fixture.h.tick(35);
    await fixture.h.settle();
    assert.equal(fixture.calls().length, 2, 'a new gesture must recover on the same page');
    assert.deepEqual(JSON.parse(fixture.calls()[1].opts.body), { dx: 19, dy: -9.5 });
    fixture.key('Enter');
    await fixture.h.settle();
    assert.equal(fixture.taps().length, 0);
    fixture.release(1);
    await fresh;
    await fixture.h.settle();
    assert.deepEqual(fixture.writes().map(call => call.path), [
      '/remote/cursor', '/remote/cursor', '/remote/tap',
    ]);
  } finally { await fixture.cleanup(); }
});

test('repeated gesture cancellations cannot strand the following movement or leak old deltas', async () => {
  const { h, event, calls } = await touchpad();
  for (let index = 0; index < 5; index++) {
    event('pointerdown'); event('pointermove', { clientX: 40 + index, clientY: 20 });
    event('pointercancel'); await h.tick(35);
  }
  assert.equal(calls().length, 0);
  event('pointerdown'); event('pointermove', { clientX: 10, clientY: -5 });
  await h.tick(35);
  assert.equal(calls().length, 1);
  assert.deepEqual(JSON.parse(calls()[0].opts.body), { dx: 19, dy: -9.5 });
  h.hide();
});

test('cancelling during an in-flight movement still drains the new gesture once afterward', async () => {
  let finishFirst;
  let started = 0;
  const { h, event, calls } = await touchpad(({ response }) => {
    if (++started === 1) return new Promise(resolve => { finishFirst = () => resolve(response({ ok: true })); });
    return response({ ok: true });
  });
  event('pointerdown'); event('pointermove', { clientX: 20 });
  const firstTick = h.tick(35); await h.settle();
  assert.equal(calls().length, 1);
  event('pointercancel'); event('pointerdown', { clientX: 100 });
  event('pointermove', { clientX: 107, clientY: 3 });
  finishFirst(); await firstTick; await h.tick(35);
  assert.equal(calls().length, 2);
  assert.deepEqual(JSON.parse(calls()[1].opts.body), { dx: 7 * 1.9, dy: 3 * 1.9 });
  h.hide();
});


test('showing Remote restores fresh movement and standalone Tap without reviving its old pad', async () => {
  const { h, calls } = await touchpad();
  const oldPad = h.document.body.querySelector('.pad');
  await h.input('Text for the focused field', 'Keep this draft');
  h.hide(); h.show(); await h.settle();
  const freshPad = h.document.body.querySelector('.pad');
  assert.notEqual(freshPad, oldPad);
  assert.equal(oldPad.isConnected, false);
  const field = h.document.body.querySelectorAll('input').find(n =>
    n.attrs['aria-label'] === 'Text for the focused field');
  assert.equal(field.value, 'Keep this draft');
  for (const listener of freshPad.listeners.keydown || [])
    listener({ key: 'ArrowRight', preventDefault() {} });
  await h.settle();
  assert.equal(calls().length, 1);
  assert.deepEqual(JSON.parse(calls()[0].opts.body), { dx: 47.5, dy: 0 });
  h.click('Tap'); await h.settle();
  assert.equal(h.calls.filter(call => call.path === '/remote/tap').length, 1);
  h.hide();
});

test('a hide-show cycle keeps a held old tap retired while the new pad can accept input', async () => {
  const fixture = await heldTouchpad();
  try {
    await queueMovementBehindHeldCursor(fixture);
    fixture.key('Enter');
    const oldPad = fixture.h.document.body.querySelector('.pad');
    fixture.h.hide(); fixture.h.show(); await fixture.h.settle();
    const freshPad = fixture.h.document.body.querySelector('.pad');
    assert.notEqual(freshPad, oldPad);
    fixture.release(0); await fixture.h.settle(); await fixture.h.tick(35);
    assert.equal(fixture.calls().length, 1);
    assert.equal(fixture.taps().length, 0);
    for (const listener of freshPad.listeners.keydown || [])
      listener({ key: 'ArrowLeft', preventDefault() {} });
    await fixture.h.settle();
    assert.equal(fixture.calls().length, 2);
    assert.deepEqual(JSON.parse(fixture.calls()[1].opts.body), { dx: -47.5, dy: 0 });
    fixture.release(1); await fixture.h.settle();
    fixture.h.click('Tap'); await fixture.h.settle();
    assert.equal(fixture.taps().length, 1);
  } finally { await fixture.cleanup(); }
});
