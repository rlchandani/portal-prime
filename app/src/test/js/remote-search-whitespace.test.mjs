/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */
import test from 'node:test';
import assert from 'node:assert/strict';
import { browserHarness } from './remote-browser-harness.mjs';

const saved = [{ base: 'http://127.0.0.1:8877', name: 'Fixture', token: 'fixture-only' }];
const page = h => h.document.getElementById('page');
const input = (h, label) => page(h).querySelectorAll('input,select')
  .find(node => node.getAttribute('aria-label') === label);
const switches = h => page(h).querySelectorAll('input.switch');
const domains = Array.from({ length: 6 }, (_, i) => ({
  id: `section${i}`, title: ['Photos', 'Home', 'Sound', 'Connections', 'Display', 'Tools'][i],
  controls: Array.from({ length: 17 }, (_, j) => ({
    key: `flag${j}`, title: `Setting ${String(i).padStart(2, '0')} ${String(j).padStart(2, '0')}`,
    type: 'bool', value: false, default: false, tier: j === 16 ? 'advanced' : 'basic',
  })),
}));
async function settings() {
  const h = await browserHarness({ saved, routes: {
    '/remote/settings': ({ response }) => response({ ok: true, settings: { domains } }),
  } });
  h.eval('category="section2";go("settings")'); await h.settle();
  return h;
}

test('whitespace-only Settings searches retain one selected category and its existing rows', async t => {
  const h = await settings();
  try {
    const before = switches(h), reads = h.calls.length;
    assert.equal(before.length, 16);
    for (const query of [' ', '   ', '\t\u00a0\u2003 ']) {
      await h.input('Search settings', query);
      t.diagnostic(JSON.stringify({ queryKind: 'whitespace', mountedRows: switches(h).length }));
      assert.equal(input(h, 'Settings category')?.value, 'section2');
      assert.equal(input(h, 'Search settings').value, query, 'Keep the user-owned search draft.');
      assert.equal(switches(h).length, 16, 'Do not mount all six categories for an empty query.');
      assert.equal(switches(h).every((node, i) => node === before[i]), true);
      assert.equal(h.eval('settingsRowView.entries.size'), 16);
    }
    assert.equal(h.calls.length, reads, 'Typing must not reload metadata or apply settings.');
    t.diagnostic(JSON.stringify({ categories: 6, basicRowsPerCategory: 16, mountedRows: 16,
      extraRequests: h.calls.length - reads, retainedRows: before.length }));
  } finally { h.hide(); }
});

test('padded meaningful Settings search and Advanced gating preserve category on clearing', async () => {
  const h = await settings();
  try {
    const reads = h.calls.length;
    await h.input('Search settings', '  dIsPlAy  ');
    assert.equal(input(h, 'Settings category'), undefined);
    assert.equal(switches(h).length, 16);
    assert.ok(input(h, 'Setting 04 00'));
    assert.equal(input(h, 'Setting 02 00'), undefined);
    assert.equal(h.eval('category'), 'section2');
    h.eval('advanced=true;render()');
    assert.equal(switches(h).length, 17);
    await h.input('Search settings', 'does-not-exist');
    assert.equal(switches(h).length, 0);
    assert.match(page(h).textContent, /No matching settings/);
    await h.input('Search settings', '\u00a0 ');
    assert.equal(input(h, 'Settings category')?.value, 'section2');
    assert.equal(switches(h).length, 17);
    assert.ok(input(h, 'Setting 02 00'));
    h.eval('advanced=false;render()');
    assert.equal(switches(h).length, 16);
    await h.input('Search settings', '');
    assert.equal(input(h, 'Settings category')?.value, 'section2');
    assert.equal(h.calls.length, reads);
  } finally { h.hide(); }
});

const entries = [
  { packageName: 'com.fixture.browser', label: 'Browser', installed: true },
  { packageName: 'com.fixture.files', label: 'Material Files', installed: true },
  { packageName: 'com.fixture.jarvis', label: 'Jarvis', installed: true },
];
async function apps(mode) {
  const h = await browserHarness({ saved, routes: {
    '/remote/apps': ({ response }) => response({ ok: true, apps: entries }),
    '/remote/catalog': ({ response }) => response({ ok: true, apps: entries }),
    '/remote/icon': () => new Response(new Uint8Array([0]), { headers: { 'Content-Type': 'image/png' } }),
  } });
  h.eval(`appMode=${JSON.stringify(mode)};go("apps")`); await h.settle();
  return h;
}
for (const mode of ['installed', 'catalog']) {
  test(`${mode} Apps whitespace searches retain the visible list and icons without new requests`, async () => {
    const h = await apps(mode);
    try {
      const list = page(h).querySelector('.apps-list'), icons = list.querySelectorAll('img');
      const calls = h.calls.length;
      assert.equal(icons.length, 3);
      for (const query of [' ', '   ', '\t\u00a0\u2003 ']) {
        await h.input('Find an app', query);
        assert.equal(page(h).querySelector('.apps-list') === list, true);
        assert.equal(input(h, 'Find an app').value, query);
        assert.equal(list.querySelectorAll('img').every((node, i) => node === icons[i]), true);
        assert.equal(h.eval('thumbs.size'), 3);
      }
      assert.equal(h.calls.length, calls);
    } finally { h.hide(); }
  });

  test(`${mode} Apps finds padded case-insensitive and multiword names while preserving the draft`, async () => {
    const h = await apps(mode);
    try {
      const reads = h.calls.filter(call => ['/remote/apps', '/remote/catalog'].includes(call.path)).length;
      await h.input('Find an app', '  bRoWsEr  ');
      let list = page(h).querySelector('.apps-list');
      assert.ok(list, 'A padded installed app name must match.');
      assert.equal(list.querySelectorAll('img').length, 1);
      assert.match(list.textContent, /Browser/);
      assert.equal(input(h, 'Find an app').value, '  bRoWsEr  ');
      const calls = h.calls.length;
      await h.input('Find an app', 'browser');
      assert.equal(page(h).querySelector('.apps-list') === list, true);
      assert.equal(h.calls.length, calls);
      await h.input('Find an app', '  Material Files  ');
      list = page(h).querySelector('.apps-list');
      assert.ok(list);
      assert.equal(list.querySelectorAll('img').length, 1);
      assert.match(list.textContent, /Material Files/);
      await h.input('Find an app', 'no-such-app');
      assert.equal(page(h).querySelector('.apps-list'), null);
      assert.equal(h.calls.filter(call => ['/remote/apps', '/remote/catalog'].includes(call.path)).length, reads);
      assert.equal(h.calls.some(call => call.opts.method === 'POST'), false);
    } finally { h.hide(); }
  });
}
