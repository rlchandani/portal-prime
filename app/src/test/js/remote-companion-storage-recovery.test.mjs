/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */
import test from 'node:test';
import assert from 'node:assert/strict';
import { browserHarness } from './remote-browser-harness.mjs';

const saved = [{ base: 'http://127.0.0.1:8877', name: 'Public fixture', token: 'synthetic-only' }];
const revision = 'a'.repeat(64);
const scene = { id: 'fixture-scene', name: 'Saved fixture routine', steps: [{ type: 'wait', ms: 10 }], schedule: null };
const operation = { id: 'fixture-operation', type: 'scene', target: { id: scene.id, name: scene.name },
  state: 'succeeded', results: [{ ok: true, result: 'fixture_done' }], updatedAt: 1000,
  retrySupported: false, cancelSupported: true };
async function fixture({ readsUnavailable = false, statusUnavailable = false } = {}) {
  const state = { readsUnavailable, statusUnavailable, saveUnavailable: false,
    scenes: [scene], revision, operations: [operation], posts: [] };
  const h = await browserHarness({ saved, routes: {
    '/remote/companion': ({ response }) => state.statusUnavailable
      ? response({ ok: false, error: 'companion_storage_unavailable' }, 503)
      : response({ ok: true, name: 'Public fixture', model: 'Synthetic Portal',
        capabilities: { scenes: true, operations: true } }),
    '/remote/scenes': ({ opts, response }) => {
      if (opts.method === 'POST') {
        const body = JSON.parse(opts.body); state.posts.push(body);
        if (state.saveUnavailable) return response({ ok: false, error: 'companion_storage_unavailable' }, 503);
        state.scenes = body.scenes; state.revision = 'b'.repeat(64);
      } else if (state.readsUnavailable) return response({ ok: false, error: 'companion_storage_unavailable' }, 503);
      return response({ ok: true, scenes: state.scenes, revision: state.revision });
    },
    '/remote/operations': ({ response }) => state.readsUnavailable
      ? response({ ok: false, error: 'companion_storage_unavailable' }, 503)
      : response({ ok: true, operations: state.operations }),
    '/remote/nowplaying': ({ response }) => state.statusUnavailable
      ? response({ ok: false, error: 'unreachable' }, 503)
      : response({ ok: true, np: { active: false } }),
  } });
  return { h, state, page: h.document.getElementById('page'), notice: h.document.getElementById('notice') };
}
function preservationMessage(text) {
  assert.match(text, /saved scenes.*Activity metadata.*unavailable/i);
  assert.match(text, /kept unchanged/i);
  assert.match(text, /Retry|Check again/i);
}
function noRePairOrReset(h) {
  assert.equal(h.eval('connectionState === "expired"'), false);
  assert.equal(h.eval('roster.length'), 1);
  assert.equal(h.calls.some(c => c.path === '/remote/pair' || c.opts.method === 'DELETE' || /reset/.test(c.path)), false);
}

test('Activity first unreadable read shows preservation and Retry rather than empty history', async () => {
  const { h, state, page } = await fixture({ readsUnavailable: true });
  h.eval('go("home","activity")'); await h.settle();
  preservationMessage(page.textContent);
  assert.doesNotMatch(page.textContent, /No recent operations/);
  assert.equal(h.eval('data.operations === undefined'), true);
  noRePairOrReset(h);
  state.readsUnavailable = false; h.click('Retry'); await h.settle();
  assert.match(page.textContent, /fixture_done/);
  assert.doesNotMatch(page.textContent, /metadata are unavailable/);
});

test('Activity failed refresh keeps confirmed history and disables cancellation until healthy Retry', async () => {
  const { h, state, page } = await fixture();
  h.eval('go("home","activity")'); await h.settle();
  const history = h.eval('data.operations'), card = page.querySelector('.history-list').firstChild;
  state.readsUnavailable = true; h.eval('loadOperations()'); await h.settle();
  preservationMessage(page.textContent);
  assert.equal(h.eval('data.operations'), history);
  assert.equal(page.querySelector('.history-list').firstChild, card);
  assert.match(page.textContent, /fixture_done/);
  assert.equal(page.querySelectorAll('button').find(b => b.textContent === 'Cancel operation').disabled, true);
  state.readsUnavailable = false; h.click('Retry'); await h.settle();
  assert.equal(page.querySelector('.history-list').firstChild, card);
  assert.equal(page.querySelectorAll('button').find(b => b.textContent === 'Cancel operation').disabled, false);
  noRePairOrReset(h);
});

test('Scenes unreadable read keeps the confirmed list with guarded actions and recovers via Retry', async () => {
  const { h, state, page } = await fixture();
  h.eval('go("home","scenes")'); await h.settle();
  const confirmed = h.eval('data.scenes');
  state.readsUnavailable = true; h.eval('loadScenes()'); await h.settle();
  preservationMessage(page.textContent);
  assert.equal(h.eval('data.scenes'), confirmed);
  assert.match(page.textContent, /Saved fixture routine/);
  for (const label of ['Run', 'Edit', 'Delete'])
    assert.equal(page.querySelectorAll('button').find(b => b.textContent === label).disabled, true);
  assert.deepEqual(state.posts, []);
  state.readsUnavailable = false; h.click('Retry'); await h.settle();
  assert.equal(page.querySelectorAll('button').find(b => b.textContent === 'Edit').disabled, false);
  noRePairOrReset(h);
});

test('scene draft survives failed read and repeated Retry without losing edits or reviewed revision', async () => {
  const { h, state, page } = await fixture();
  h.eval('go("home","scenes")'); await h.settle(); h.click('Edit'); await h.settle();
  await h.input('Scene name', 'Unsaved fixture name');
  const draft = h.eval('sceneDraft'), before = h.eval('JSON.stringify(sceneDraft)');
  state.readsUnavailable = true; h.eval('loadScenes()'); await h.settle();
  preservationMessage(page.textContent);
  h.click('Retry'); await h.settle();
  preservationMessage(page.textContent);
  assert.equal(h.eval('sceneDraft'), draft);
  assert.equal(h.eval('JSON.stringify(sceneDraft)'), before);
  state.readsUnavailable = false; h.click('Retry'); await h.settle();
  assert.equal(h.eval('JSON.stringify(sceneDraft)'), before);
  assert.equal(page.querySelector('[data-focus="scene-name"]').value, 'Unsaved fixture name');
  assert.deepEqual(state.posts, []);
  noRePairOrReset(h);
});

test('unavailable scene Save preserves draft and confirmed data, then Retry and explicit Save recover', async () => {
  const { h, state, page, notice } = await fixture();
  h.eval('go("home","scenes")'); await h.settle(); h.click('Edit'); await h.settle();
  await h.input('Scene name', 'Unsaved fixture name');
  const draft = h.eval('sceneDraft'), before = h.eval('JSON.stringify(sceneDraft)'), confirmed = h.eval('data.scenes');
  state.saveUnavailable = true; h.click('Save scene'); await h.settle();
  preservationMessage(notice.textContent);
  preservationMessage(page.textContent);
  assert.equal(h.eval('sceneDraft'), draft);
  assert.equal(h.eval('JSON.stringify(sceneDraft)'), before);
  assert.equal(h.eval('data.scenes'), confirmed);
  assert.equal(state.scenes[0].name, 'Saved fixture routine');
  assert.equal(state.posts.length, 1);
  state.saveUnavailable = false; h.click('Retry'); await h.settle();
  assert.equal(h.eval('JSON.stringify(sceneDraft)'), before);
  assert.equal(state.posts.length, 1); // Retry reads; it must never replay a mutation.
  h.click('Save scene'); await h.settle();
  assert.equal(h.eval('sceneDraft'), null);
  assert.equal(h.eval('data.scenes[0].name'), 'Unsaved fixture name');
  assert.equal(state.posts.length, 2);
  assert.equal(state.posts[1].expectedRevision, revision);
  assert.match(notice.textContent, /Scene saved and confirmed/);
  noRePairOrReset(h);
});

test('unreadable initial status is visibly offline with metadata guidance and recovers without re-pairing', async () => {
  const { h, state, page } = await fixture({ statusUnavailable: true });
  assert.equal(h.eval('connectionState'), 'offline');
  assert.match(h.document.getElementById('connection').textContent, /Offline/);
  preservationMessage(page.textContent);
  assert.doesNotMatch(page.textContent, /Pair again/);
  noRePairOrReset(h);
  state.statusUnavailable = false;
  page.querySelectorAll('.group').find(group =>
    group.textContent.startsWith('Management metadata unavailable')).querySelector('button').click();
  await h.settle();
  assert.equal(h.eval('connectionState'), 'connected');
  assert.doesNotMatch(page.textContent, /metadata are unavailable/);
  assert.equal(h.eval('roster.length'), 1);
});

test('status failure after confirmed metadata remains visible while retaining metadata and scene draft', async () => {
  const { h, state, page } = await fixture();
  h.eval('go("home","scenes")'); await h.settle(); h.click('Edit'); await h.settle();
  await h.input('Scene name', 'Draft kept across status failure');
  const draft = h.eval('sceneDraft'), metadata = h.eval('data.companion');
  h.eval('go("home")'); await h.settle();
  state.statusUnavailable = true; await h.eval('status()'); await h.settle();
  assert.equal(h.eval('connectionState'), 'offline');
  assert.equal(h.eval('data.companion'), metadata);
  assert.equal(h.eval('sceneDraft'), draft);
  preservationMessage(page.textContent);
  state.statusUnavailable = false; h.click('Retry'); await h.settle();
  h.eval('go("home","scenes")'); await h.settle();
  assert.equal(h.eval('sceneDraft'), draft);
  assert.equal(page.querySelector('[data-focus="scene-name"]').value, 'Draft kept across status failure');
  noRePairOrReset(h);
});
