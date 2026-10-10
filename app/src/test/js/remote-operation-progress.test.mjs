/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */
import test from 'node:test';
import assert from 'node:assert/strict';
import { browserHarness } from './remote-browser-harness.mjs';

const op = (id, state = 'running', result = '') => ({
  id, type: 'install', target: { packageName: `com.fixture.${id}` }, state,
  results: result ? [{ ok: state !== 'failed', result }] : [],
  updatedAt: 1000, retrySupported: state === 'failed', cancelSupported: false,
});
async function setup(mode = 'catalog', operations = []) {
  const fixture = { operations, error: false, metadataError: false, defer: null,
    apps: ['a', 'b'].map((id) => ({ packageName: `com.fixture.${id}`, label: `Fixture ${id}`,
      versionName: '1.0', installed: true, compatible: true, updateAvailable: true })) };
  const h = await browserHarness({
    saved: [{ base: 'http://127.0.0.1:8877', name: 'Fixture', token: 'fixture-only' }],
    routes: {
      '/remote/apps': ({ response }) => fixture.defer ? fixture.defer(response) : response({ ok: true, apps: fixture.apps }),
      '/remote/catalog': ({ response }) => {
        if (fixture.metadataError) throw new Error('fixture offline');
        return response({ ok: true, apps: fixture.apps });
      },
      '/remote/operations': ({ response }) => {
        if (fixture.error) throw new Error('fixture offline');
        return response({ ok: true, operations: fixture.operations });
      },
      '/remote/icon': () => new Response(new Uint8Array([0]), { headers: { 'Content-Type': 'image/png' } }),
    },
  });
  h.eval(`appMode=${JSON.stringify(mode)};go("apps")`);
  await h.settle();
  const page = h.document.getElementById('page');
  const calls = (path) => h.calls.filter((call) => call.path === path).length;
  const list = () => page.querySelector('.apps-list');
  const update = () => list().children[0].querySelectorAll('button').find((b) => b.textContent === 'Update');
  return { h, fixture, page, calls, list, update };
}

for (const mode of ['installed', 'catalog']) {
  test(`${mode} changed progress updates status and controls without rebuilding cards/icons`, async () => {
    const { h, fixture, page, calls, list, update } = await setup(mode);
    const original = list(), card = original.children[0], icon = card.querySelector('img');
    const before = calls('/remote/icon');
    const search = page.querySelector('[data-focus="apps-search"]');
    h.eval('setDraft("fixture-draft","retained")');
    for (const operation of [op('a'), op('a', 'running', 'fixture_progress'), op('a', 'failed', 'fixture_failed')]) {
      fixture.operations = [operation];
      await h.tick(4000);
      assert.equal(list() === original && list().children[0] === card && card.querySelector('img') === icon, true);
      assert.equal(page.querySelector('[data-focus="apps-search"]') === search, true);
      assert.equal(calls('/remote/icon'), before);
      if (mode === 'catalog') assert.equal(update().disabled, operation.state === 'running');
      assert.equal(card.textContent.includes(operation.results[0]?.result || 'Running'), true);
    }
    fixture.operations = [];
    await h.tick(4000);
    assert.equal(card.textContent.includes('fixture_failed'), false);
    assert.equal(h.eval('draft("fixture-draft")'), 'retained');
    if (mode === 'catalog') assert.equal(update().disabled, false);
  });

  test(`${mode} completed install refreshes metadata once and retains unchanged cards/icons`, async () => {
    const { h, fixture, calls, list } = await setup(mode, [op('a')]);
    const original = list(), icon = original.querySelector('img');
    const path = mode === 'catalog' ? '/remote/catalog' : '/remote/apps';
    const before = { metadata: calls(path), icons: calls('/remote/icon') };
    fixture.operations = [op('a', 'succeeded', 'installed')];
    await h.tick(4000);
    assert.equal(calls(path), before.metadata + 1);
    assert.equal(calls('/remote/icon'), before.icons);
    assert.equal(list() === original && list().querySelector('img') === icon, true);
    assert.equal(list().textContent.includes('Finished'), true);
    await h.tick(4000);
    assert.equal(calls(path), before.metadata + 1);
  });
}

test('changed completion metadata renders exactly once with accurate version and catalog action', async () => {
  const { h, fixture, page, list, calls } = await setup('catalog', [op('a')]);
  let replacements = 0;
  const replace = page.replaceChildren.bind(page);
  page.replaceChildren = (...nodes) => { replacements++; return replace(...nodes); };
  const before = calls('/remote/catalog');
  fixture.apps[0] = { ...fixture.apps[0], versionName: '2.0', updateAvailable: false };
  fixture.operations = [op('a', 'succeeded', 'installed')];
  await h.tick(4000);
  assert.equal(replacements, 1);
  assert.equal(calls('/remote/catalog'), before + 1);
  assert.equal(list().children[0].textContent.includes('2.0'), true);
  assert.equal(list().children[0].querySelectorAll('button').some((b) => b.textContent === 'Up to date' && b.disabled), true);
});

test('Activity reconciles progress, insertion, removal and order by operation ID', async () => {
  const { h, fixture, page } = await setup('catalog', [op('a'), op('b', 'failed', 'fixture_failure')]);
  h.eval('go("home","activity")'); await h.settle();
  const root = page.firstChild, list = page.querySelector('.history-list');
  const first = list.children[0], second = list.children[1];
  const retry = second.querySelector('button');
  retry.focus();
  fixture.operations = [op('a', 'running', 'fixture_progress'), op('b', 'failed', 'fixture_failure')];
  await h.tick(4000);
  assert.equal(page.firstChild === root && page.querySelector('.history-list') === list, true);
  assert.equal(list.children[0] === first && list.children[1] === second, true);
  assert.equal(first.textContent.includes('fixture_progress'), true);
  assert.equal(second.querySelector('button') === retry && h.document.activeElement === retry, true);
  fixture.operations = [op('b', 'failed', 'fixture_failure'), op('c'), op('a', 'running', 'fixture_progress')];
  await h.tick(4000);
  assert.equal(list.children[0] === second && list.children[2] === first, true);
  fixture.operations = [op('a', 'running', 'fixture_progress')];
  await h.tick(4000);
  assert.equal(list.children.length, 1);
  assert.equal(list.children[0] === first, true);
});

test('Activity error and recovery update their mount and preserve surrounding page and keyed cards', async () => {
  const { h, fixture, page } = await setup('catalog', [op('a')]);
  h.eval('go("home","activity")'); await h.settle();
  const root = page.firstChild, list = page.querySelector('.history-list'), card = list.children[0];
  fixture.error = true; await h.tick(4000);
  assert.equal(page.firstChild === root, true);
  assert.equal(page.textContent.includes('Activity unavailable'), true);
  fixture.error = false; await h.tick(4000);
  assert.equal(page.firstChild === root && page.querySelector('.history-list') === list, true);
  assert.equal(list.children[0] === card, true);
  assert.equal(page.textContent.includes('Activity unavailable'), false);
});

test('completion metadata failure stays visible and manual refresh recovers it', async () => {
  const { h, fixture, page, list } = await setup('catalog', [op('a')]);
  fixture.metadataError = true;
  fixture.operations = [op('a', 'succeeded', 'installed')];
  await h.tick(4000);
  assert.equal(page.textContent.includes('Apps unavailable'), true);
  fixture.metadataError = false;
  h.eval('refresh()'); await h.settle();
  assert.equal(list().children.length, 2);
  assert.equal(page.textContent.includes('Apps unavailable'), false);
});

test('late completion metadata cannot overwrite a newly selected room', async () => {
  const { h, fixture, page } = await setup('installed', [op('a')]);
  let release;
  fixture.defer = (response) => new Promise((resolve) => { release = () => resolve(response({ ok: true, apps: [{ packageName: 'com.fixture.stale', label: 'Fixture stale' }] })); });
  fixture.operations = [op('a', 'succeeded', 'installed')];
  await h.tick(4000);
  fixture.defer = null;
  h.eval('roster.push({base:"http://127.0.0.1:8878",name:"Fixture second",token:"fixture-only"});selectRoom("http://127.0.0.1:8878")');
  await h.settle(); release(); await h.settle();
  assert.equal(page.textContent.includes('Fixture stale'), false);
});
