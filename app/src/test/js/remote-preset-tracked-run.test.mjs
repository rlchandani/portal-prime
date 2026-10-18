/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */
import test from 'node:test';
import assert from 'node:assert/strict';
import { browserHarness } from './remote-browser-harness.mjs';
import { operationView } from '../../main/assets/remote/core.mjs';

const preset = { id: 'fixture-preset', name: 'Evening fixture', steps: [{ type: 'wait', ms: 10 }] };
const operation = (patch = {}) => ({ id: 'fixture-operation', type: 'preset',
  target: { id: preset.id, name: preset.name }, state: 'running', results: [],
  updatedAt: 1000, cancelSupported: true, retrySupported: false, ...patch });
const legacy = { ok: true, running: preset.id, steps: 1 };
async function fixture(body, deferred = false, tab = 'home') {
  let release;
  const state = { operations: [] };
  const h = await browserHarness({ saved: [
    { base: 'http://127.0.0.1:8877', name: 'Fixture one', token: 'synthetic-only' },
    { base: 'http://127.0.0.1:8878', name: 'Fixture two', token: 'synthetic-only' },
  ], routes: {
    '/remote/presets': ({ response }) => response({ ok: true, presets: [preset] }),
    '/remote/preset': ({ response }) => {
      const finish = () => {
        if (body.operation?.id) state.operations = [body.operation];
        return response(body);
      };
      return deferred ? new Promise(resolve => { release = () => resolve(finish()); }) : finish();
    },
    '/remote/operations': ({ response }) => state.holdHistory
      ? new Promise(resolve => { state.releaseHistory = () => resolve(response({ ok: true, operations: state.operations })); })
      : response({ ok: true, operations: state.operations }),
    '/remote/operation': ({ response }) => {
      state.operations = [operation({ cancelRequested: true, cancelSupported: false })];
      return response({ ok: true, operation: state.operations[0] });
    },
  } });
  h.eval(`go(${JSON.stringify(tab)},"presets")`); await h.settle();
  return { h, state, release: () => release(), page: h.document.getElementById('page'),
    notice: h.document.getElementById('notice') };
}

for (const tab of ['home', 'remote']) test(`${tab} Run opens Activity with a real accepted preset operation`, async () => {
  const { h, state, page, notice } = await fixture({ ...legacy, operation: operation() }, false, tab);
  state.holdHistory = true; // Acceptance must seed Activity before its read completes.
  h.click('Run'); await h.settle();
  assert.equal(h.eval('tab + ":" + detail'), 'home:activity');
  assert.equal(h.eval('data.operations[0].id'), 'fixture-operation');
  assert.match(page.textContent, /Evening fixture/);
  assert.match(page.textContent, /Running/);
  assert.match(notice.textContent, /Preset accepted\. Activity reports each step’s result\./);
  const call = h.calls.find(c => c.path === '/remote/preset');
  assert.equal(call.opts.method, 'POST');
  assert.deepEqual(JSON.parse(call.opts.body), { id: preset.id });
  assert.equal(new URL(call.url).origin, 'http://127.0.0.1:8877');
  state.releaseHistory(); await h.settle();
});

test('deferred acceptance leaves the list and Activity history untouched until confirmed', async () => {
  const { h, state, release, notice } = await fixture({ ...legacy, operation: operation() }, true);
  state.holdHistory = true;
  h.eval('data.operations = [{id:"earlier",type:"scene",state:"succeeded",results:[]}]');
  h.click('Run'); await h.settle();
  assert.equal(h.eval('detail'), 'presets');
  assert.equal(h.eval('data.operations[0].id'), 'earlier');
  assert.doesNotMatch(notice.textContent, /accepted|completed|Finished/i);
  release(); await h.settle();
  assert.equal(h.eval('detail'), 'activity');
  assert.equal(h.eval('data.operations[0].id'), 'fixture-operation');
  assert.equal(h.eval('data.operations[1].id'), 'earlier');
  assert.match(notice.textContent, /Preset accepted/);
  state.releaseHistory(); await h.settle();
});

test('legacy acceptance gives honest guidance without claiming completion or creating Activity', async () => {
  const { h, notice } = await fixture(legacy);
  h.click('Run'); await h.settle();
  assert.equal(h.eval('detail'), 'presets');
  assert.equal(h.eval('(data.operations || []).length'), 0);
  assert.match(notice.textContent, /Preset started\. This Portal does not report completion; check/i);
  assert.doesNotMatch(notice.textContent, /accepted|completed|Finished/i);
});

for (const [name, body] of [
  ['empty', { ok: true }], ['wrong preset', { ...legacy, running: 'other' }],
  ['invalid step count', { ...legacy, steps: -1 }], ['string step count', { ...legacy, steps: '1' }],
  ['null operation', { ...legacy, operation: null }], ['empty operation', { ...legacy, operation: {} }],
  ['wrong operation type', { ...legacy, operation: operation({ type: 'scene' }) }],
  ['wrong operation target', { ...legacy, operation: operation({ target: { id: 'other', name: 'Other' } }) }],
  ['missing state', { ...legacy, operation: operation({ state: undefined }) }],
  ['bad results', { ...legacy, operation: operation({ results: [null] }) }],
]) test(`malformed success (${name}) stays unconfirmed without Activity mutation`, async () => {
  const { h, notice } = await fixture(body);
  h.click('Run'); await h.settle();
  assert.equal(h.eval('detail'), 'presets');
  assert.equal(h.eval('(data.operations || []).length'), 0);
  assert.match(notice.textContent, /did not confirm|unconfirmed/i);
  assert.doesNotMatch(notice.textContent, /Preset started|Preset accepted/);
});

for (const change of ['room', 'page', 'page round trip', 'hidden', 'list'])
  test(`deferred response after ${change} cannot navigate or overwrite current Activity`, async () => {
    const { h, release, notice } = await fixture({ ...legacy, operation: operation() }, true);
    h.click('Run'); await h.settle();
    if (change === 'room') h.eval('selectRoom("http://127.0.0.1:8878")');
    if (change === 'page') h.eval('go("home","activity")');
    if (change === 'page round trip') h.eval('go("home");go("home","presets")');
    if (change === 'hidden') h.hide();
    if (change === 'list') h.eval('data.presets = data.presets.slice()');
    await h.settle();
    h.eval('data.operations = [{id:"current-history",type:"scene",state:"succeeded",results:[]}]');
    const page = h.eval('tab + ":" + detail'), noticeBefore = notice.textContent;
    release(); await h.settle();
    assert.equal(h.eval('tab + ":" + detail'), page);
    assert.equal(h.eval('data.operations[0].id'), 'current-history');
    assert.equal(notice.textContent, noticeBefore);
  });

test('list help limits Activity reporting to updated Portals and empty Activity includes presets', async () => {
  const { h, page } = await fixture(legacy);
  assert.match(page.textContent, /updated Portal/i);
  assert.match(page.textContent, /Activity/);
  h.eval('go("home","activity")'); await h.settle();
  assert.match(page.textContent, /presets/i);
});

test('Activity cancel uses existing API, waits for worker stop and removes the cancel button', async () => {
  const { h, state, page } = await fixture({ ...legacy, operation: operation() });
  h.click('Run'); await h.settle();
  h.click('Cancel operation'); await h.settle();
  const sheet = h.document.getElementById('sheet');
  assert.match(sheet.textContent, /remaining steps/i);
  assert.match(sheet.textContent, /earlier actions.*not.*undone/i);
  sheet.querySelectorAll('button').find(b => b.textContent === 'Cancel operation').click();
  await h.settle();
  const call = h.calls.find(c => c.path === '/remote/operation');
  assert.deepEqual(JSON.parse(call.opts.body), { type: 'cancel', id: 'fixture-operation' });
  assert.equal(h.eval('data.operations[0].state'), 'running');
  assert.match(page.textContent, /Stopping/);
  assert.match(page.textContent, /current action/i);
  assert.equal(page.querySelectorAll('button').some(b => b.textContent === 'Cancel operation'), false);
  state.operations = [operation({ state: 'cancelled', cancelSupported: false,
    results: [{ ok: false, result: 'cancelled', step: 0, type: 'wait' }] })];
  await h.tick(4000);
  assert.match(page.textContent, /Stopped/);
  assert.match(page.textContent, /Remaining steps stopped\. Earlier actions were not undone\./);
  assert.equal(page.querySelector('.badge').className.includes('error'), false);
});

test('preset cancellation views are neutral while other operation semantics stay intact', () => {
  const stopping = operationView(operation({ cancelRequested: true, cancelSupported: false }));
  assert.equal(stopping.label, 'Stopping'); assert.equal(stopping.tone, 'neutral');
  assert.match(stopping.detail, /current action/i);
  const stopped = operationView(operation({ state: 'cancelled', results: [{ ok: false, result: 'cancelled' }] }));
  assert.equal(stopped.label, 'Stopped'); assert.equal(stopped.tone, 'neutral');
  assert.equal(stopped.detail, 'Remaining steps stopped. Earlier actions were not undone.');
  assert.equal(operationView(operation({ type: 'scene', cancelRequested: true })).label, 'Running');
  assert.equal(operationView(operation({ type: 'install', state: 'failed', results: [{ ok: false }] })).tone, 'error');
  assert.equal(operationView(operation({ state: 'succeeded' })).label, 'Finished');
});


test('unconfirmed preset never displays Finished, Failed or success styling', () => {
  for (const state of ['succeeded', 'failed']) {
    const view = operationView(operation({ state, backendMayContinue: true,
      results: [{ ok: true, result: 'completion_unconfirmed' }] }));
    assert.equal(view.label, 'Completion unconfirmed');
    assert.equal(view.tone, 'neutral');
    assert.match(view.detail, /Remaining steps were not run/);
  }
});
