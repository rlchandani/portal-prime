/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */
// Offline work-count probe of the shipped controller through its existing VM/DOM adapter.
// No browser, socket, device, image decoding, layout, or wall-clock benchmark is involved.
// Run normally to audit; --assert-idle fails until unchanged operations polls retain the list.
import assert from 'node:assert/strict';
import { browserHarness } from '../app/src/test/js/remote-browser-harness.mjs';

const args = process.argv.slice(2);
assert.ok(
  args.every((arg) => arg === '--assert-idle'),
  'Only --assert-idle is supported; the fixture and poll count are bounded.',
);
const apps = Array.from({ length: 24 }, (_, i) => ({
  packageName: `com.fixture.app${i}`,
  label: `Fixture app ${i}`,
  versionName: '1.0',
}));
const h = await browserHarness({
  saved: [{ base: 'http://127.0.0.1:8877', name: 'Fixture', token: 'fixture-only' }],
  routes: {
    '/remote/apps': ({ response }) => response({ ok: true, apps }),
    '/remote/operations': ({ response }) => response({ ok: true, operations: [] }),
    // Synthetic bytes exercise ImagePool delivery/revocation; they are never decoded.
    '/remote/icon': () =>
      new Response(new Uint8Array([0]), { headers: { 'Content-Type': 'image/png' } }),
  },
});
const work = { createdElements: 0, registeredListeners: 0, replacedAppPages: 0 };
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
const page = h.document.getElementById('page');
const replace = page.replaceChildren.bind(page);
page.replaceChildren = (...nodes) => {
  work.replacedAppPages++;
  return replace(...nodes);
};
const cardCount = () =>
  page.querySelector('.apps-list')?.querySelectorAll('section').length ?? 0;
const iconCount = () =>
  page.querySelector('.apps-list')?.querySelectorAll('img').length ?? 0;

h.eval('go("apps")');
await h.settle();
assert.equal(cardCount(), 24, 'The fixture must fill one app page before measuring.');
assert.equal(iconCount(), 24, 'Initial synthetic icon requests must finish before measuring.');
const initialList = page.querySelector('.apps-list');
const before = { ...work };
const firstCall = h.calls.length;
const pollTicks = 3;
for (let tick = 0; tick < pollTicks; tick++) await h.tick(4000);

const reads = h.calls.slice(firstCall);
const result = {
  fixtureApps: 24,
  pollTicks,
  operationsReads: reads.filter((call) => call.path === '/remote/operations').length,
  appListReads: reads.filter((call) => call.path === '/remote/apps').length,
  iconReads: reads.filter((call) => call.path === '/remote/icon').length,
  createdElements: work.createdElements - before.createdElements,
  registeredListeners: work.registeredListeners - before.registeredListeners,
  replacedAppPages: work.replacedAppPages - before.replacedAppPages,
  retainedAppList: page.querySelector('.apps-list') === initialList,
  visibleAppCards: cardCount(),
  visibleIcons: iconCount(),
};
assert.equal(result.operationsReads, 3, 'Keep the existing poll cadence in the fixture.');
assert.equal(result.appListReads, 0, 'Idle polls must not refresh the app listing.');
assert.equal(result.visibleAppCards, 24, 'All fixture apps must remain available.');
assert.equal(result.visibleIcons, 24, 'Synthetic icon delivery must finish after each poll.');
console.log(JSON.stringify(result, null, 2));
if (args.includes('--assert-idle')) {
  assert.equal(result.iconReads, 0, 'Unchanged operations must not refetch app icons.');
  assert.equal(result.replacedAppPages, 0, 'Unchanged operations must retain the app page.');
  assert.equal(result.retainedAppList, true, 'Unchanged operations must retain app list nodes.');
}
