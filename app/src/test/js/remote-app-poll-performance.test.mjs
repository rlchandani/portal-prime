/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */
import test from 'node:test';
import assert from 'node:assert/strict';
import { browserHarness } from './remote-browser-harness.mjs';

const pkg = 'com.fixture.app';
const operation = (state, results = []) => ({
  id: 'fixture-operation',
  type: 'install',
  target: { packageName: pkg },
  state,
  results,
  updatedAt: 1000,
  retrySupported: false,
  cancelSupported: false,
});
async function openApps(mode = 'installed', operations = []) {
  const fixture = { operations, offline: false, errorCode: null };
  const apps = [
    {
      packageName: pkg,
      label: 'Fixture app',
      versionName: '1.0',
      installed: true,
      compatible: true,
      updateAvailable: true,
    },
  ];
  const h = await browserHarness({
    saved: [{ base: 'http://127.0.0.1:8877', name: 'Fixture', token: 'fixture-only' }],
    routes: {
      '/remote/apps': ({ response }) => response({ ok: true, apps }),
      '/remote/catalog': ({ response }) => response({ ok: true, apps }),
      '/remote/operations': ({ response }) => {
        if (fixture.offline) throw new Error('offline fixture');
        if (fixture.errorCode) return response({ ok: false, error: fixture.errorCode }, 503);
        return response({ ok: true, operations: fixture.operations });
      },
      '/remote/icon': () =>
        new Response(new Uint8Array([0]), { headers: { 'Content-Type': 'image/png' } }),
    },
  });
  h.eval(`appMode=${JSON.stringify(mode)};go("apps")`);
  await h.settle();
  const page = h.document.getElementById('page');
  const list = () => page.querySelector('.apps-list');
  const calls = (path) => h.calls.filter((call) => call.path === path).length;
  const update = () => page.querySelectorAll('button').find((node) => node.textContent === 'Update');
  assert.equal(list().querySelectorAll('img').length, 1);
  return { h, fixture, page, list, calls, update };
}

function countRenderWork(h, page) {
  const work = { createdElements: 0, registeredListeners: 0, replacedPages: 0 };
  const create = h.document.createElement.bind(h.document);
  h.document.createElement = (tag) => {
    work.createdElements++;
    const node = create(tag);
    const listen = node.addEventListener.bind(node);
    node.addEventListener = (...args) => {
      work.registeredListeners++;
      return listen(...args);
    };
    return node;
  };
  const replace = page.replaceChildren.bind(page);
  page.replaceChildren = (...nodes) => {
    work.replacedPages++;
    return replace(...nodes);
  };
  return work;
}

for (const view of ['Apps', 'Activity']) {
  test(`${view} repeats the same operations error without rebuilding, then renders changes and recovery`, async (t) => {
    const { h, fixture, page, calls, update } = await openApps('catalog', [operation('running')]);
    if (view === 'Activity') {
      h.eval('go("home","activity")');
      await h.settle();
    }
    const healthyPage = page.firstChild;
    fixture.offline = true;
    await h.tick(4000);
    assert.equal(page.firstChild === healthyPage, false, 'The first error must render.');
    assert.equal(h.eval('data.operationsError.code'), 'unreachable');
    if (view === 'Activity') assert.match(page.textContent, /Activity unavailable/);
    else assert.equal(update().disabled, true, 'Keep the pending install disabled through failures.');
    const firstErrorPage = page.firstChild;
    const firstError = h.eval('data.operationsError');
    const work = countRenderWork(h, page);
    const before = { operations: calls('/remote/operations'), icons: calls('/remote/icon') };
    for (let i = 0; i < 3; i++) await h.tick(4000);
    const repeated = {
      ...work,
      operationsReads: calls('/remote/operations') - before.operations,
      iconReads: calls('/remote/icon') - before.icons,
    };
    t.diagnostic(JSON.stringify({ view, repeated }));
    assert.equal(repeated.operationsReads, 3, 'Keep the existing operations poll cadence.');
    assert.equal(h.eval('data.operationsError') === firstError, false, 'Requests create fresh errors.');
    assert.equal(repeated.iconReads, 0);
    assert.equal(repeated.createdElements, 0);
    assert.equal(repeated.registeredListeners, 0);
    assert.equal(repeated.replacedPages, 0);
    assert.equal(page.firstChild === firstErrorPage, true);
    assert.equal(h.eval('connectionState'), 'connected', 'Operations failures do not change connection status.');
    fixture.offline = false;
    fixture.errorCode = 'timeout';
    await h.tick(4000);
    assert.equal(page.firstChild === firstErrorPage, false, 'Changed errors must render.');
    assert.equal(h.eval('data.operationsError.code'), 'timeout');
    if (view === 'Activity') assert.match(page.textContent, /took too long/);
    const changedErrorPage = page.firstChild;
    fixture.errorCode = null;
    await h.tick(4000);
    assert.equal(page.firstChild === changedErrorPage, false, 'Recovery must render.');
    assert.equal(h.eval('data.operationsError'), undefined);
    if (view === 'Activity') {
      assert.equal(page.textContent.includes('Activity unavailable'), false);
      assert.match(page.textContent, /Running/);
    } else assert.equal(update().disabled, true);
  });
}

test('three unchanged Apps polls retain list and icon nodes without repeating icon requests', async () => {
  const { h, list, calls } = await openApps();
  const initialList = list();
  const initialIcon = initialList.querySelector('img');
  const before = { operations: calls('/remote/operations'), icons: calls('/remote/icon') };
  for (let i = 0; i < 3; i++) await h.tick(4000);
  assert.equal(calls('/remote/operations') - before.operations, 3);
  assert.equal(calls('/remote/icon') - before.icons, 0);
  assert.equal(list() === initialList, true);
  assert.equal(list().querySelector('img') === initialIcon, true);
});

test('unchanged pending operations retain disabled Update controls and their app list', async () => {
  const { h, list, update, calls } = await openApps('catalog', [operation('running')]);
  assert.equal(update().disabled, true);
  const initialList = list();
  const before = calls('/remote/icon');
  await h.tick(4000);
  assert.equal(calls('/remote/icon'), before);
  assert.equal(list() === initialList, true);
  assert.equal(update().disabled, true);
});

test('new pending operations, nested result changes and removal update the app controls', async () => {
  const { h, fixture, page, update } = await openApps('catalog');
  assert.equal(update().disabled, false);
  fixture.operations = [operation('running')];
  await h.tick(4000);
  assert.equal(update().disabled, true);
  assert.match(page.textContent, /Running/);
  fixture.operations = [operation('failed', [{ ok: false, result: 'fixture_first_result' }])];
  await h.tick(4000);
  assert.equal(update().disabled, false);
  assert.match(page.textContent, /fixture_first_result/);
  fixture.operations = [operation('failed', [{ ok: false, result: 'fixture_second_result' }])];
  await h.tick(4000);
  assert.match(page.textContent, /fixture_second_result/);
  assert.equal(page.textContent.includes('fixture_first_result'), false);
  fixture.operations = [];
  await h.tick(4000);
  assert.equal(page.textContent.includes('Failed'), false);
});

test('an operation accepted while search is focused becomes visible after focus leaves', async () => {
  const { h, fixture, list, update } = await openApps('catalog');
  const initialList = list();
  const search = h.document.getElementById('page').querySelector('[data-focus="apps-search"]');
  search.focus();
  fixture.operations = [operation('running')];
  await h.tick(4000);
  assert.equal(list() === initialList, true);
  assert.equal(update().disabled, false, 'Keep the focused page untouched.');
  assert.equal(h.eval('data.operations[0].state'), 'running', 'Still accept the poll result.');
  h.document.activeElement = null;
  await h.tick(4000);
  assert.equal(list() === initialList, false);
  assert.equal(update().disabled, true, 'Render a previously accepted pending operation.');
  const updatedList = list();
  await h.tick(4000);
  assert.equal(list() === updatedList, true, 'Once rendered, unchanged pending state needs no rebuild.');
});

test('Activity error recovery renders even when the recovered operation list is unchanged', async () => {
  const { h, fixture, page } = await openApps('catalog', [operation('running')]);
  h.eval('go("home","activity")');
  await h.settle();
  fixture.offline = true;
  await h.tick(4000);
  assert.match(page.textContent, /Activity unavailable/);
  assert.ok(page.querySelectorAll('button').some((node) => node.textContent === 'Retry'));
  // Poll rendering is also suppressed while an input in a sheet has focus.
  const input = h.document.createElement('input');
  h.document.getElementById('sheet-content').append(input);
  input.focus();
  fixture.offline = false;
  await h.tick(4000);
  assert.equal(h.eval('data.operationsError'), undefined);
  assert.match(page.textContent, /Activity unavailable/, 'Recovery must not disrupt focused input.');
  h.document.activeElement = null;
  await h.tick(4000);
  assert.equal(page.textContent.includes('Activity unavailable'), false);
  assert.match(page.textContent, /Running/);
  const recoveredPage = page.firstChild;
  await h.tick(4000);
  assert.equal(page.firstChild === recoveredPage, true, 'Repeated healthy state needs no rebuild.');
});

for (const mode of ['installed', 'catalog']) {
  const listingPath = mode === 'installed' ? '/remote/apps' : '/remote/catalog';
  test(`${mode} manual and forced refresh still reload unchanged app data`, async () => {
    const { h, list, calls } = await openApps(mode);
    const initialList = list();
    const before = calls(listingPath);
    h.document.getElementById('refresh-button').click();
    await h.settle();
    assert.equal(calls(listingPath), before + 1);
    assert.equal(list() === initialList, false);
    const refreshedList = list();
    h.eval('loadVisible(true)');
    await h.settle();
    assert.equal(calls(listingPath), before + 2);
    assert.equal(list() === refreshedList, false);
  });

  test(`${mode} confirmed install completion refreshes the visible listing exactly once`, async () => {
    const { h, fixture, calls } = await openApps(mode, [operation('running')]);
    const before = calls(listingPath);
    fixture.operations = [operation('succeeded', [{ ok: true, result: 'installed' }])];
    await h.tick(4000);
    assert.equal(calls(listingPath), before + 1);
    await h.tick(4000);
    assert.equal(calls(listingPath), before + 1);
    assert.equal(calls(mode === 'installed' ? '/remote/catalog' : '/remote/apps'), 0);
  });
}

test('retained app listeners dispatch once and navigation still clears the image pool', async () => {
  const { h, calls } = await openApps();
  for (let i = 0; i < 3; i++) await h.tick(4000);
  h.click('Open');
  await h.settle();
  assert.equal(calls('/remote/launch'), 1);
  h.eval('go("home")');
  await h.settle();
  assert.equal(h.eval('thumbs.size'), 0);
  const before = calls('/remote/operations');
  await h.tick(4000);
  assert.equal(calls('/remote/operations'), before);
  h.eval('go("apps")');
  await h.settle();
  h.click('Open');
  await h.settle();
  assert.equal(calls('/remote/launch'), 2);
});
