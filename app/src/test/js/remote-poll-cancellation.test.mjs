/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */
import test from 'node:test';
import assert from 'node:assert/strict';
import { browserHarness } from './remote-browser-harness.mjs';

async function fixture(stage) {
  let held = false, finish;
  const path = { media: '/remote/nowplaying', operations: '/remote/operations', status: '/remote/companion' }[stage];
  const h = await browserHarness({
    saved: [{ base: 'http://127.0.0.1:8877', name: 'Fixture', token: 'fixture-only' }],
    routes: { [path]: ({ response }) => {
      const result = stage === 'media' ? { ok: true, np: { active: false } }
          : stage === 'operations' ? { ok: true, operations: [] }
          : { ok: true, name: 'Fixture', capabilities: { operations: true } };
      if (held) { held = false; return new Promise(resolve => { finish = () => resolve(response(result)); }); }
      return response(result);
    } },
  });
  if (stage === 'operations') { h.eval('go("apps")'); await h.settle(); }
  if (stage === 'status') h.eval('connectionState="offline";startPolling()');
  held = true;
  const pending = h.tick(stage === 'status' ? 30000 : 4000);
  await h.settle();
  assert.equal(typeof finish, 'function', 'The actual poll must be suspended at its request.');
  return { h, pending, finish, calls: path => h.calls.filter(call => call.path === path).length };
}

test('an old media poll cannot issue another operations request after switching to Apps', async () => {
  const { h, pending, finish, calls } = await fixture('media');
  h.eval('go("apps")'); await h.settle();
  const operations = calls('/remote/operations');
  const freshTimer = h.eval('pollTimer');
  finish(); await pending;
  assert.equal(calls('/remote/operations'), operations, 'The old Home poll must not read the new Apps page.');
  assert.equal(h.eval('pollTimer'), freshTimer, 'The new page owns its polling timer.');
  h.hide();
});

for (const stage of ['media', 'operations', 'status']) {
  test(`${stage}: hide and show retire the pending poll even when the page is unchanged`, async () => {
    const { h, pending, finish } = await fixture(stage);
    h.hide(); h.show(); await h.settle();
    const freshTimer = h.eval('pollTimer');
    assert.notEqual(freshTimer, null);
    finish(); await pending;
    assert.equal(h.eval('pollTimer'), freshTimer, 'A retired async continuation must not replace the resumed timer.');
    h.hide();
  });
}

test('same-page polling restart retires an outstanding operations continuation', async () => {
  const { h, pending, finish } = await fixture('operations');
  h.eval('startPolling()');
  const freshTimer = h.eval('pollTimer');
  finish(); await pending;
  assert.equal(h.eval('pollTimer'), freshTimer);
  h.hide();
});

test('navigation after an operations await cannot begin a status read for the new page', async () => {
  const { h, pending, finish, calls } = await fixture('operations');
  h.eval('connectionState="offline";go("home")'); await h.settle();
  const statuses = calls('/remote/companion');
  const freshTimer = h.eval('pollTimer');
  finish(); await pending;
  assert.equal(calls('/remote/companion'), statuses);
  assert.equal(h.eval('pollTimer'), freshTimer);
  h.hide();
});

test('a current poll still reads media and schedules exactly one next cycle', async () => {
  const h = await browserHarness({ saved: [{ base: 'http://127.0.0.1:8877', name: 'Fixture', token: 'fixture-only' }] });
  const calls = () => h.calls.filter(call => call.path === '/remote/nowplaying').length;
  const before = calls();
  await h.tick(4000); assert.equal(calls(), before + 1);
  await h.tick(4000); assert.equal(calls(), before + 2);
  h.hide(); assert.equal(h.eval('pollTimer'), null);
});
