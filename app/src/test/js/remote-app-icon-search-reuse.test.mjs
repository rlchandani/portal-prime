/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */
import test from 'node:test';
import assert from 'node:assert/strict';
import { browserHarness } from './remote-browser-harness.mjs';

const apps = (count = 4) => Array.from({ length: count }, (_, i) => ({
  packageName: `com.fixture.app${i}`,
  label: `Portal ${String(i).padStart(2, '0')}`,
  installed: true, compatible: true, updateAvailable: false,
}));
const image = () => new Response(new Uint8Array([0]), { headers: { 'Content-Type': 'image/png' } });
async function openApps({ entries = apps(), pending = null, mode = 'installed' } = {}) {
  const h = await browserHarness({
    saved: [
      { base: 'http://127.0.0.1:8877', name: 'Fixture', token: 'fixture-only' },
      { base: 'http://127.0.0.1:8878', name: 'Other', token: 'other-fixture-only' },
    ],
    routes: {
      '/remote/apps': ({ response }) => response({ ok: true, apps: entries }),
      '/remote/catalog': ({ response }) => response({ ok: true, apps: entries }),
      '/remote/icon': ({ opts }) => pending ? pending(opts.signal) : image(),
    },
  });
  h.eval(`appMode=${JSON.stringify(mode)};go('apps')`);
  await h.settle();
  const page = h.document.getElementById('page');
  const icons = () => page.querySelector('.apps-list')?.querySelectorAll('img') || [];
  const reads = () => h.calls.filter(c => c.path === '/remote/icon').length;
  const urls = () => icons().map(n => n.getAttribute('src'));
  return { h, page, icons, reads, urls };
}

test('five search keystrokes reuse every still-visible app icon without another request', async t => {
  const { h, icons, reads, urls } = await openApps();
  const initialUrls = urls(), initialReads = reads();
  for (const query of ['P', 'Po', 'Por', 'Port', 'Portal']) {
    await h.input('Find an app', query);
    assert.equal(icons().length, 4);
  }
  t.diagnostic(JSON.stringify({ keystrokes: 5, visibleApps: 4, extraIconRequests: reads() - initialReads }));
  assert.equal(reads() - initialReads, 0);
  assert.deepEqual(urls(), initialUrls);
  assert.equal(h.eval('thumbs.size'), 4);
  h.eval('go("home")');
});

test('narrowing search keeps its visible icon and releases hidden entries before widening', async () => {
  const { h, reads, urls } = await openApps();
  const first = urls()[0], before = reads();
  await h.input('Find an app', 'Portal 00');
  assert.deepEqual(urls(), [first]);
  assert.equal(reads(), before);
  assert.equal(h.eval('thumbs.size'), 1);
  await h.input('Find an app', 'Portal');
  assert.equal(urls()[0], first);
  assert.equal(reads() - before, 3, 'Only previously released images need fetching.');
  assert.equal(h.eval('thumbs.size'), 4);
  h.eval('go("home")');
});

test('pending icons bind to the latest search DOM without cancelling or duplicating requests', async () => {
  const jobs = [];
  const { h, page, reads, icons } = await openApps({ pending: signal => new Promise(resolve => jobs.push({ signal, resolve })) });
  try {
    const oldList = page.querySelector('.apps-list'), before = reads();
    assert.equal(jobs.length, 4);
    await h.input('Find an app', 'Portal');
    assert.equal(reads(), before);
    assert.equal(jobs.every(j => !j.signal.aborted), true);
    assert.equal(page.querySelector('.apps-list') === oldList, true);
    await h.input('Find an app', 'Portal 00');
    assert.equal(page.querySelector('.apps-list') === oldList, false);
    jobs.forEach(j => j.resolve(image()));
    await h.settle();
    assert.equal(icons().length, 1);
    assert.equal(oldList.querySelectorAll('img').length, 0);
  } finally {
    h.eval('go("home")');
    jobs.forEach(j => j.resolve(image()));
    await h.settle();
  }
});

test('fresh inventory from explicit refresh reloads icons even when labels and packages match', async () => {
  const { h, reads, urls } = await openApps();
  const initial = urls(), before = reads();
  h.eval('loadApps()');
  await h.settle();
  assert.equal(reads() - before, 4);
  assert.equal(urls().every((url, i) => url !== initial[i]), true);
  assert.equal(h.eval('thumbs.size'), 4);
  h.eval('go("home")');
});

test('pagination retains only the visible 24-image budget', async () => {
  const { h, reads, icons } = await openApps({ entries: apps(26) });
  assert.equal(icons().length, 24);
  assert.equal(h.eval('thumbs.size'), 24);
  const before = reads();
  h.click('Next');
  await h.settle();
  assert.equal(icons().length, 2);
  assert.equal(h.eval('thumbs.size'), 2);
  assert.equal(reads() - before, 2);
  h.click('Previous');
  await h.settle();
  assert.equal(icons().length, 24);
  assert.equal(h.eval('thumbs.size'), 24);
  h.eval('go("home")');
});

test('changing Portal clears cached icons for identical package names', async () => {
  const { h, reads, urls } = await openApps();
  const initial = urls(), before = reads();
  h.eval('selectRoom("http://127.0.0.1:8878")');
  await h.settle();
  assert.equal(reads() - before, 4);
  assert.equal(urls().every((url, i) => url !== initial[i]), true);
  assert.equal(h.calls.filter(c => c.path === '/remote/icon').slice(-4).every(c => c.url.startsWith('http://127.0.0.1:8878/')), true);
  h.eval('go("home")');
});

test('catalog search reuses installed icons and never requests uninstalled package icons', async () => {
  const entries = apps().map((app, i) => ({ ...app, installed: i < 2 }));
  const { h, reads, urls } = await openApps({ entries, mode: 'catalog' });
  assert.equal(reads(), 2);
  const before = reads(), initial = urls();
  await h.input('Find an app', 'Portal');
  assert.equal(reads(), before);
  assert.deepEqual(urls(), initial);
  assert.equal(h.eval('thumbs.size'), 2);
  h.eval('go("home")');
});
