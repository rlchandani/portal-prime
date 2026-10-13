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
    const value = { button: 0, pointerId: 1, clientX: 0, clientY: 0, ...overrides };
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
