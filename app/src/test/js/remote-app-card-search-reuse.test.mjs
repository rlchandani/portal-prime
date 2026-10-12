/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */
import test from 'node:test';
import assert from 'node:assert/strict';
import { browserHarness } from './remote-browser-harness.mjs';

const fixtures = count => Array.from({ length: count }, (_, i) => ({
  packageName: `com.fixture.app${i}`, label: `Portal ${String(i).padStart(2, '0')}`,
  versionName: '1.0', installed: true, compatible: true, updateAvailable: false,
}));

async function openApps(count = 24) {
  const fixture = { entries: fixtures(count), hold: false, jobs: [] };
  const h = await browserHarness({
    saved: [
      { base: 'http://127.0.0.1:8877', name: 'Fixture', token: 'fixture-a' },
      { base: 'http://127.0.0.1:8878', name: 'Other', token: 'fixture-b' },
    ],
    routes: {
      '/remote/apps': ({ response }) => fixture.hold
        ? new Promise(resolve => fixture.jobs.push(() => resolve(response({ ok: true, apps: fixture.entries }))))
        : response({ ok: true, apps: fixture.entries }),
      '/remote/icon': () => new Response(new Uint8Array([0]), { headers: { 'Content-Type': 'image/png' } }),
      '/remote/launch': ({ response }) => response({ ok: true, dispatched: true }),
    },
  });
  h.eval('go("apps")');
  await h.settle();
  const list = () => h.document.getElementById('page').querySelector('.apps-list');
  return { h, fixture, list };
}

test('five unchanged-result search keystrokes retain all 24 cards and their actions', async t => {
  const { h, list } = await openApps();
  try {
    const initial = list(), cards = [...initial.children];
    const work = { elements: 0, listeners: 0 };
    const create = h.document.createElement.bind(h.document);
    h.document.createElement = tag => {
      work.elements++;
      const node = create(tag), listen = node.addEventListener.bind(node);
      node.addEventListener = (...args) => { work.listeners++; return listen(...args); };
      return node;
    };
    for (const query of ['P', 'Po', 'Por', 'Port', 'Portal']) await h.input('Find an app', query);
    const retained = list().children.filter((card, i) => card === cards[i]).length;
    t.diagnostic(JSON.stringify({ keystrokes: 5, visibleCards: 24, retainedCards: retained, ...work }));
    assert.equal(list() === initial, true);
    assert.equal(retained, 24);
    h.click('Open'); await h.settle();
    const commands = h.calls.filter(c => c.path === '/remote/launch');
    assert.equal(commands.length, 1);
    assert.equal(JSON.parse(commands[0].opts.body).packageName, 'com.fixture.app0');
  } finally { h.hide(); }
});

test('operation changes update retained cards during a full search render', async () => {
  const { h, list } = await openApps(4);
  try {
    const initial = list(), cards = [...initial.children];
    h.eval('data.operations=[{id:"fixture-op",type:"install",state:"running",stage:"download",progress:{current:1,total:4},target:{packageName:"com.fixture.app0"}}]');
    await h.input('Find an app', 'Portal');
    assert.equal(list() === initial, true);
    assert.equal(list().children.every((card, i) => card === cards[i]), true);
    assert.match(cards[0].textContent, /Downloading|Running/);
    h.eval('data.operations=[]');
    await h.input('Find an app', 'Port');
    assert.doesNotMatch(cards[0].textContent, /Downloading|Running/);
  } finally { h.hide(); }
});

test('fresh inventory rebuilds cards and shows updated metadata', async () => {
  const { h, fixture, list } = await openApps(4);
  try {
    const initial = list();
    fixture.entries = fixture.entries.map(app => ({ ...app, versionName: '2.0' }));
    h.eval('loadApps()'); await h.settle();
    assert.equal(list() === initial, false);
    assert.match(list().textContent, /2\.0/);
    assert.doesNotMatch(list().textContent, /1\.0/);
  } finally { h.hide(); }
});

test('returning from hidden rebuilds revoked images before delayed inventory arrives', async () => {
  const { h, fixture, list } = await openApps(4);
  try {
    const initial = list(), urls = initial.querySelectorAll('img').map(n => n.getAttribute('src'));
    fixture.hold = true;
    h.hide();
    h.show();
    await h.settle();
    assert.equal(fixture.jobs.length, 1);
    await h.input('Find an app', 'Portal');
    assert.equal(list() === initial, false);
    const images = list().querySelectorAll('img');
    assert.equal(images.length, 4);
    assert.equal(images.every((n, i) => n.getAttribute('src') !== urls[i]), true);
  } finally {
    fixture.jobs.forEach(resolve => resolve());
    await h.settle();
    h.hide();
  }
});

test('narrowing, pagination, navigation and room changes discard stale visible-card sets', async () => {
  const { h, list } = await openApps(26);
  try {
    const first = list();
    await h.input('Find an app', 'Portal 00');
    assert.equal(list() === first, false);
    assert.equal(list().children.length, 1);
    await h.input('Find an app', 'Portal');
    const widened = list();
    assert.equal(widened.children.length, 24);
    h.click('Next'); await h.settle();
    assert.equal(list() === widened, false);
    assert.equal(list().children.length, 2);
    h.eval('go("home");go("apps")'); await h.settle();
    const beforeRoom = list();
    h.eval('selectRoom("http://127.0.0.1:8878")'); await h.settle();
    assert.equal(list() === beforeRoom, false);
    h.click('Open'); await h.settle();
    const command = h.calls.filter(c => c.path === '/remote/launch').at(-1);
    assert.ok(command.url.startsWith('http://127.0.0.1:8878/'));
  } finally { h.hide(); }
});
