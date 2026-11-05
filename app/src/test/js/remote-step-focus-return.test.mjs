/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */
import test from 'node:test';
import assert from 'node:assert/strict';
import { browserHarness } from './remote-browser-harness.mjs';

const saved = [{ base: 'http://127.0.0.1:8877', name: 'Generated Portal', token: 'generated-session' }];
const byName = (h, name) => h.document.body.querySelectorAll('button')
  .find(node => node.getAttribute('aria-label') === name);

async function editor(mode) {
  const item = { id: 'generated-draft', name: 'Generated draft', steps: [{ type: 'wait', ms: 100 }] };
  const h = await browserHarness({ saved, routes: {
    '/remote/scenes': ({ response }) => response({ ok: true, revision: 'current', scenes: [item] }),
    '/remote/presets': ({ response }) => response({ ok: true, presets: [item] }),
  } });
  h.eval(`go("home", "${mode === 'scene' ? 'scenes' : 'presets'}")`);
  await h.settle();
  h.click('Edit');
  await h.settle();
  const opener = byName(h, 'Edit step 1');
  opener.focus();
  opener.click();
  await h.settle();
  h.eval('render()');
  assert.equal(opener.isConnected, false);
  h.document.getElementById('sheet-close').focus();
  return h;
}

for (const mode of ['scene', 'preset']) {
  test(`${mode} editor close returns focus to the same step after a page rebuild`, async () => {
    const h = await editor(mode);
    h.eval('closeSheet()');
    await h.settle();
    assert.equal(h.document.activeElement === byName(h, 'Edit step 1'), true);
    assert.equal(h.calls.filter(call => call.opts.method === 'POST').length, 0);
  });
}

test('a retired page cannot reclaim focus from the new page', async () => {
  const h = await editor('scene');
  h.eval('go("settings")');
  const target = h.document.getElementById('refresh-button');
  target.focus();
  await h.settle();
  assert.equal(h.document.activeElement === target, true);
});

test('replacing the dialog cannot return focus behind its new modal', async () => {
  const h = await editor('preset');
  h.eval('openSheet("Replacement", text("Generated replacement"))');
  const target = h.document.getElementById('sheet-close');
  target.focus();
  await h.settle();
  assert.equal(h.document.getElementById('sheet').open, true);
  assert.equal(h.document.getElementById('sheet-title').textContent, 'Replacement');
  assert.equal(h.document.activeElement === target, true);
});
