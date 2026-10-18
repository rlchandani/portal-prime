/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */
import test from 'node:test';
import assert from 'node:assert/strict';
import { browserHarness } from './remote-browser-harness.mjs';
const saved = [{ base: 'http://127.0.0.1:8877', name: 'Public Portal', token: 'public-preset-fixture' }];
const preset = { id: 'public-preset', name: 'Public preset', steps: [{ type: 'wait', ms: 0 }] };
async function fixture(initiallyUnavailable = true) {
  const state = { unavailable: initiallyUnavailable, posts: [] };
  const h = await browserHarness({ saved, routes: {
    '/remote/presets': ({ opts, response }) => {
      if (opts.method === 'POST') { state.posts.push(opts.body); return response({ ok: true, presets: JSON.parse(opts.body).presets }); }
      return state.unavailable ? response({ ok: false, error: 'saved_presets_unavailable' }, 503)
        : response({ ok: true, presets: [preset] });
    },
  } });
  h.eval('go("home","presets")'); await h.settle();
  return { h, state, page: h.document.getElementById('page') };
}
test('unreadable stored presets show a specific persistent preservation message and Retry', async () => {
  const { h, page } = await fixture();
  assert.match(page.textContent, /saved presets could not be read/i);
  assert.match(page.textContent, /kept unchanged/i);
  assert.equal(page.textContent.includes('No presets yet'), false);
  assert.ok(page.querySelectorAll('button').find(b => b.textContent === 'Retry'));
  assert.equal(h.calls.filter(c => c.path === '/remote/presets').length, 1);
});
test('draft creation during unreadable storage keeps text and cannot replace the saved list', async () => {
  const { h, state, page } = await fixture();
  h.click('New preset'); await h.settle(); await h.input('Preset name', 'My draft');
  const save = page.querySelectorAll('button').find(b => b.textContent === 'Save preset');
  assert.equal(save.disabled, true); save.click(); await h.settle();
  assert.equal(h.eval('presetDraft.name'), 'My draft'); assert.deepEqual(state.posts, []);
  assert.match(page.textContent, /kept unchanged/i);
});
test('retry after storage recovery keeps the draft and restores Save readiness', async () => {
  const { h, state, page } = await fixture();
  h.click('New preset'); await h.settle(); await h.input('Preset name', 'My draft');
  state.unavailable = false; h.click('Retry'); await h.settle();
  assert.equal(h.eval('presetDraft.name'), 'My draft');
  assert.equal(page.querySelectorAll('button').find(b => b.textContent === 'Save preset').disabled, false);
  assert.equal(page.textContent.includes('could not be read'), false);
  assert.deepEqual(state.posts, []);
});
test('unreadable refresh retains confirmed macros while disabling their actions', async () => {
  const { h, state, page } = await fixture(false);
  state.unavailable = true; h.eval('loadPresets()'); await h.settle();
  assert.match(page.textContent, /Public preset/); assert.match(page.textContent, /kept unchanged/i);
  for (const label of ['Run', 'Edit', 'Delete']) assert.equal(page.querySelectorAll('button').find(b => b.textContent === label).disabled, true);
  assert.deepEqual(state.posts, []);
});
