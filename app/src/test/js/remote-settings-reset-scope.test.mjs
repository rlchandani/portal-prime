/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */
import test from 'node:test';
import assert from 'node:assert/strict';
import { browserHarness } from './remote-browser-harness.mjs';
const saved = [
  { base: 'http://127.0.0.1:8877', name: 'Family fixture', token: 'public-a' },
  { base: 'http://127.0.0.1:8878', name: 'Living fixture', token: 'public-b' },
];
const controls = [
  { key: 'interval', type: 'int', title: 'Photo interval', value: 30, default: 45, min: 5, max: 600 },
  { key: 'shuffle', type: 'bool', title: 'Shuffle photos', value: true, default: false },
  { key: 'advanced', type: 'bool', title: 'Diagnostic logging', value: true, default: false, tier: 'advanced' },
  { key: 'password', type: 'string', title: 'Private credential', value: '', default: '', secret: true },
  { key: 'readonly', type: 'int', title: 'Read only', value: 1, default: 0, readOnly: true },
  { key: 'information', type: 'info', title: 'Information', value: 'public', default: '' },
  { key: 'noDefault', type: 'bool', title: 'No default', value: false },
];
async function fixture(domain = 'screensaver', fields = controls) {
  const state = { posts: [] };
  const schema = { domains: [{ id: domain, title: 'Photos', controls: fields.map(c => ({ ...c })) }] };
  const h = await browserHarness({ saved, routes: {
    '/remote/companion': ({ url, response }) => response({ ok: true, name: url.includes('8877') ? 'Family fixture' : 'Living fixture', capabilities: {} }),
    '/remote/settings': ({ url, opts, response }) => {
      if (opts.method !== 'POST') return response({ ok: true, settings: schema });
      const body = JSON.parse(opts.body); state.posts.push({ url, values: body.values });
      const current = schema.domains[0];
      for (const c of current.controls) if (Object.hasOwn(body.values, c.key)) c.value = body.values[c.key];
      return response({ ok: true, applied: Object.keys(body.values), domain: current });
    },
  } });
  h.eval('go("settings")'); await h.settle();
  return { h, state };
}
function resetButton(h) {
  return h.document.getElementById('page').querySelectorAll('button')
    .find(b => b.textContent === 'Reset shown settings' || b.textContent === 'Reset');
}
function review(h) { resetButton(h).click(); return h.document.getElementById('sheet-content').textContent; }
function confirm(h) {
  h.document.getElementById('sheet-actions').querySelectorAll('button')
    .find(b => b.textContent === 'Reset settings' || b.textContent === 'Reset').click();
}

test('reset review describes and sends exactly the searched editable defaults', async () => {
  const { h, state } = await fixture();
  await h.input('Search settings', 'photo interval');
  const copy = review(h);
  assert.match(copy, /1 setting/);
  assert.match(copy, /Photo interval/);
  assert.doesNotMatch(copy, /Shuffle photos|Diagnostic logging|Private credential/);
  assert.match(h.document.getElementById('sheet-title').textContent, /shown settings/);
  confirm(h); await h.settle();
  assert.deepEqual(state.posts.map(p => p.values), [{ interval: 45 }]);
});

test('reset review follows Advanced visibility while omitting secrets read-only info and missing defaults', async () => {
  const { h, state } = await fixture();
  let copy = review(h);
  assert.match(copy, /2 settings/);
  assert.match(copy, /Photo interval/); assert.match(copy, /Shuffle photos/);
  assert.doesNotMatch(copy, /Diagnostic logging|Private credential|Read only|Information|No default/);
  h.click('Cancel'); h.eval('advanced=true;render()');
  copy = review(h); assert.match(copy, /3 settings/); assert.match(copy, /Diagnostic logging/);
  assert.doesNotMatch(copy, /Private credential|Read only|Information|No default/);
  confirm(h); await h.settle();
  assert.deepEqual(state.posts.map(p => p.values), [{ interval: 45, shuffle: false, advanced: false }]);
});

test('ordinary reset review names all chosen rooms without an irrelevant active-only warning', async () => {
  const { h, state } = await fixture(); h.eval('scope="all";loadScopeSchemas()'); await h.settle();
  const copy = review(h);
  assert.match(copy, /Family fixture/); assert.match(copy, /Living fixture/);
  assert.doesNotMatch(copy, /Active Portal only/);
  confirm(h); await h.settle();
  assert.equal(state.posts.length, 2);
  assert.deepEqual(state.posts.map(p => p.values), [{ interval: 45, shuffle: false }, { interval: 45, shuffle: false }]);
});

test('reset review separates active-only connection fields from shared fields and preserves transport partition', async () => {
  const fields = [
    { key: 'enabled', type: 'bool', title: 'Enable MQTT', value: true, default: false },
    { key: 'host', type: 'string', title: 'Broker address', value: 'public.invalid', default: '' },
  ];
  const { h, state } = await fixture('mqtt', fields); h.eval('scope="all";loadScopeSchemas()'); await h.settle();
  review(h);
  const lists = h.document.getElementById('sheet-content').querySelectorAll('ul');
  assert.deepEqual(lists.map(list => list.textContent), ['Enable MQTT', 'Broker address']);
  assert.match(h.document.getElementById('sheet-content').textContent, /Active Portal only: Family fixture/);
  confirm(h); await h.settle();
  assert.deepEqual(state.posts.find(p => p.url.includes('8878')).values, { enabled: false });
  assert.deepEqual(state.posts.find(p => p.url.includes('8877')).values, { enabled: false, host: '' });
});

test('selected scope review names only actual shared targets and still identifies the active-only room', async () => {
  const { h, state } = await fixture('mqtt', [
    { key: 'enabled', type: 'bool', title: 'Enable MQTT', value: true, default: false },
    { key: 'host', type: 'string', title: 'Broker address', value: 'public.invalid', default: '' },
  ]);
  h.eval(`scope="selected";selectedTargets=new Set([${JSON.stringify(saved[1].base)}]);loadScopeSchemas()`); await h.settle();
  review(h);
  const sections = h.document.getElementById('sheet-content').querySelectorAll('ul').map(list => list.parent.textContent);
  assert.match(sections[0], /Living fixture/); assert.doesNotMatch(sections[0], /Family fixture/);
  assert.match(sections[1], /Family fixture/); assert.doesNotMatch(sections[1], /Living fixture/);
  confirm(h); await h.settle(); assert.equal(state.posts.length, 2);
  assert.deepEqual(state.posts.find(p => p.url.includes('8878')).values, { enabled: false });
  assert.deepEqual(state.posts.find(p => p.url.includes('8877')).values, { host: '' });
});

test('canceling a scoped reset review preserves settings without dispatch', async () => {
  const { h, state } = await fixture(); const before = h.eval('JSON.stringify(data.settings)');
  review(h); h.click('Cancel'); await h.settle();
  assert.equal(state.posts.length, 0); assert.equal(h.eval('JSON.stringify(data.settings)'), before);
});

test('a reset review from a departed room cannot write to either room', async () => {
  const { h, state } = await fixture(); review(h);
  const old = h.document.getElementById('sheet-actions').querySelectorAll('button').at(-1);
  h.eval(`selectRoom(${JSON.stringify(saved[1].base)})`); await h.settle(); old.click(); await h.settle();
  assert.equal(state.posts.length, 0);
});

test('no reset action is offered when every shown field lacks an editable nonsecret default', async () => {
  const { h } = await fixture('screensaver', controls.slice(3));
  assert.equal(resetButton(h), undefined);
});
