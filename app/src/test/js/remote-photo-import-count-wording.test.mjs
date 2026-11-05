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
async function picker(names) {
  const h = await browserHarness({ saved });
  h.eval('go("photos")'); await h.settle(); h.click('Import photos');
  const input = h.document.body.querySelectorAll('input')
    .find(node => node.attrs['aria-label'] === 'Choose photos');
  input.files = names.map(name => Object.assign(new Blob(['generated'], { type: 'image/png' }), { name }));
  input.onchange({ target: input });
  const summary = h.document.getElementById('sheet-content').querySelectorAll('p')
    .find(node => node.getAttribute('role') === 'status' && node.textContent.includes(' × '));
  assert.ok(summary, 'The actual picker copy count is mounted');
  return { h, summary };
}

test('one photo for one Portal names one copy', async () => {
  const { h, summary } = await picker(['one.png']);
  assert.equal(summary.textContent, '1 photo × 1 Portal = 1 copy');
  assert.equal(h.calls.filter(call => call.path === '/remote/photo-upload').length, 0);
});

test('one photo for both Portals names two copies', async () => {
  const { h, summary } = await picker(['one.png']); h.click('Select both Portals');
  assert.equal(summary.textContent, '1 photo × 2 Portals = 2 copies');
  assert.equal(h.calls.filter(call => call.path === '/remote/photo-upload').length, 0);
});

test('multiple and empty selections retain their actual counts', async () => {
  const { h, summary } = await picker(['one.png', 'two.png']);
  assert.equal(summary.textContent, '2 photos × 1 Portal = 2 copies');
  h.click('Select both Portals');
  assert.equal(summary.textContent, '2 photos × 2 Portals = 4 copies');
  const empty = await picker([]);
  assert.equal(empty.summary.textContent, '0 photos × 1 Portal = 0 copies');
  assert.equal(h.calls.filter(call => call.path === '/remote/photo-upload').length, 0);
  assert.equal(empty.h.calls.filter(call => call.path === '/remote/photo-upload').length, 0);
});
