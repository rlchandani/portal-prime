/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */
import test from 'node:test';
import assert from 'node:assert/strict';
import { browserHarness } from './remote-browser-harness.mjs';

const inputFor = (h, label) => h.document.body.querySelectorAll('input')
  .find(n => n.getAttribute('aria-label') === label);
function dispatch(input, type, extra = {}) {
  for (const callback of input.listeners[type] || []) callback({ target: input, ...extra });
}
async function setup(tab = 'apps') {
  const fixture = { expire: false };
  const h = await browserHarness({
    saved: [
      { base: 'http://127.0.0.1:8877', name: 'Fixture', token: 'fixture-a' },
      { base: 'http://127.0.0.1:8878', name: 'Other', token: 'fixture-b' },
    ],
    routes: {
      '/remote/apps': ({ response }) => response({ ok: true, apps: [
        { packageName: 'com.fixture.camera', label: 'Camera', installed: true },
        { packageName: 'com.fixture.music', label: 'Music', installed: true },
      ] }),
      '/remote/icon': () => new Response(new Uint8Array([0]), { headers: { 'Content-Type': 'image/png' } }),
      '/remote/settings': ({ response }) => response({ ok: true, settings: { domains: [] } }),
      '/remote/companion': ({ response }) => fixture.expire
        ? response({ ok: false, error: 'unauthorized' }, 401)
        : response({ ok: true, capabilities: [] }),
    },
  });
  h.eval(`go(${JSON.stringify(tab)})`); await h.settle();
  return { h, fixture, label: tab === 'apps' ? 'Find an app' : 'Search settings' };
}

for (const tab of ['apps', 'settings']) test(`${tab} retains focused input throughout composition and commits final query once`, async () => {
  const { h, label } = await setup(tab);
  try {
    const input = inputFor(h, label); input.focus();
    dispatch(input, 'compositionstart');
    for (const value of ['M', 'Mu', 'Music']) {
      input.value = value; dispatch(input, 'input', { isComposing: true }); await h.settle();
      assert.equal(inputFor(h, label) === input, true);
      assert.equal(input.isConnected, true);
      assert.equal(h.document.activeElement === input, true);
    }
    dispatch(input, 'compositionend');
    dispatch(input, 'input', { isComposing: false });
    assert.equal(inputFor(h, label) === input, true);
    await h.tick(0);
    assert.equal(inputFor(h, label).value, 'Music');
    assert.equal(h.document.activeElement === inputFor(h, label), true);
    if (tab === 'apps') assert.equal(h.document.getElementById('page').querySelector('.apps-list').children.length, 1);
  } finally { h.hide(); }
});

test('compositionend commits its final value even without a final input event', async () => {
  const { h, label } = await setup();
  try {
    const input = inputFor(h, label); input.focus(); dispatch(input, 'compositionstart');
    input.value = 'Camera'; dispatch(input, 'compositionend'); await h.tick(0);
    assert.equal(inputFor(h, label).value, 'Camera');
    assert.equal(h.document.getElementById('page').querySelector('.apps-list').children.length, 1);
  } finally { h.hide(); }
});

test('navigation is immediate and detached composing events cannot change the next query', async () => {
  const { h, label } = await setup();
  try {
    const input = inputFor(h, label); input.focus(); dispatch(input, 'compositionstart');
    h.eval('go("home")'); await h.settle();
    assert.equal(input.isConnected, false);
    input.value = 'stale'; dispatch(input, 'compositionend'); dispatch(input, 'input'); await h.tick(0);
    h.eval('go("apps")'); await h.settle();
    assert.equal(inputFor(h, label).value, '');
  } finally { h.hide(); }
});

test('room selection invalidates composing ownership and stale events', async () => {
  const { h, label } = await setup();
  try {
    const input = inputFor(h, label); input.focus(); dispatch(input, 'compositionstart');
    h.eval('selectRoom("http://127.0.0.1:8878")'); await h.settle();
    assert.equal(input.isConnected, false);
    input.value = 'stale'; dispatch(input, 'compositionend'); dispatch(input, 'input'); await h.tick(0);
    assert.equal(inputFor(h, label).value, '');
  } finally { h.hide(); }
});

test('pairing expiry bypasses composition deferral', async () => {
  const { h, fixture, label } = await setup();
  try {
    const input = inputFor(h, label); input.focus(); dispatch(input, 'compositionstart');
    fixture.expire = true; h.eval('status()'); await h.settle();
    assert.equal(input.isConnected, false);
    assert.match(h.document.getElementById('page').textContent, /Pair again/);
    input.value = 'stale'; dispatch(input, 'compositionend'); await h.tick(0);
    assert.match(h.document.getElementById('page').textContent, /Pair again/);
  } finally { h.hide(); }
});

test('hide cancels pending composition and returning permits a fresh render', async () => {
  const { h, label } = await setup();
  try {
    const input = inputFor(h, label); input.focus(); dispatch(input, 'compositionstart');
    input.value = 'Camera'; dispatch(input, 'input', { isComposing: true });
    dispatch(input, 'compositionend'); h.hide(); h.show(); await h.settle(); await h.tick(0);
    assert.equal(inputFor(h, label) === input, false);
    await h.input(label, 'Music');
    assert.equal(inputFor(h, label).value, 'Music');
  } finally { h.hide(); }
});

test('blur flushes pending composition without stealing focus; ordinary search remains immediate', async () => {
  const { h, label } = await setup();
  try {
    const input = inputFor(h, label); input.focus(); dispatch(input, 'compositionstart');
    input.value = 'Camera'; dispatch(input, 'input', { isComposing: true });
    h.document.activeElement = h.document.body; dispatch(input, 'blur'); await h.tick(0);
    assert.equal(inputFor(h, label).value, 'Camera');
    assert.equal(h.document.activeElement === h.document.body, true);
    inputFor(h, label).focus(); dispatch(inputFor(h, label), 'focus');
    await h.input(label, 'Music');
    assert.equal(h.document.getElementById('page').querySelector('.apps-list').children.length, 1);
    assert.equal(inputFor(h, label).value, 'Music');
  } finally { h.hide(); }
});

test('blur and late commitment retain the Catalog activation target until its click', async () => {
  const { h, label } = await setup();
  try {
    const input = inputFor(h, label), catalog = h.document.body.querySelectorAll('button').find(n => n.textContent === 'Catalog');
    input.focus(); dispatch(input, 'compositionstart');
    input.value = 'Camera'; dispatch(input, 'input', { isComposing: true });
    h.document.activeElement = catalog; dispatch(input, 'blur');
    dispatch(input, 'compositionend'); dispatch(input, 'input', { isComposing: false });
    await h.tick(0);
    assert.equal(catalog.isConnected, true);
    catalog.click(); await h.settle();
    assert.equal(h.eval('appMode'), 'catalog');
    assert.equal(inputFor(h, label).value, 'Camera');
  } finally { h.hide(); }
});
