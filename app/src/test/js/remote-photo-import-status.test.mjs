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
  { base: 'https://generated-a.invalid', token: 'generated-a', name: 'Generated A' },
  { base: 'https://generated-b.invalid', token: 'generated-b', name: 'Generated B' },
];
const generatedFile = name =>
  Object.assign(new Blob(['generated'], { type: 'image/png' }), { name });

async function picker() {
  const h = await browserHarness({ saved });
  h.eval('go("photos")');
  await h.settle();
  h.click('Import photos');
  return h;
}

function chooseFiles(h, names) {
  const input = h.document.body.querySelectorAll('input')
    .find(node => node.attrs['aria-label'] === 'Choose photos');
  input.files = names.map(generatedFile);
  input.onchange({ target: input });
}

test('import copy count exposes polite status feedback as selection changes', async () => {
  const h = await picker();
  const summary = h.document.getElementById('sheet-content').querySelectorAll('p')
    .find(node => /photos? × \d+ Portals? = \d+ cop(?:y|ies)/.test(node.textContent));
  assert.ok(summary, 'The actual picker exposes its computed copy count');
  assert.equal(summary.getAttribute('role'), 'status');
  assert.equal(summary.getAttribute('aria-live'), 'polite');
  h.click('Select both Portals');
  chooseFiles(h, ['first.png', 'second.png']);
  assert.equal(summary.textContent, '2 photos × 2 Portals = 4 copies');
  assert.equal(h.calls.filter(call => call.path === '/remote/photo-upload').length, 0);
});

test('import folder validation exposes polite feedback and clears when corrected', async () => {
  const h = await picker();
  chooseFiles(h, ['first.png']);
  await h.input('Destination folder', '/private/Photos');
  const content = h.document.getElementById('sheet-content');
  const hint = content.querySelectorAll('p')
    .find(node => node.textContent === 'Choose a public folder under /sdcard. Private Android folders and hidden paths cannot be used.');
  const start = h.document.body.querySelectorAll('button')
    .find(node => /^Import \d+ photo/.test(node.textContent));
  assert.ok(hint, 'The actual validation message is visible');
  assert.equal(start.disabled, true);
  assert.equal(hint.getAttribute('role'), 'status');
  assert.equal(hint.getAttribute('aria-live'), 'polite');
  await h.input('Destination folder', '/sdcard/Pictures/Immortal');
  assert.equal(hint.textContent, '');
  assert.equal(start.disabled, false);
  assert.equal(h.calls.filter(call => call.path === '/remote/photo-upload').length, 0);
});
