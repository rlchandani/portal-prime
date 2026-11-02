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
  { base: 'http://127.0.0.1:8877', name: 'Preview A', token: 'fixture-preview-a' },
  { base: 'http://127.0.0.1:8878', name: 'Preview B', token: 'fixture-preview-b' },
];
const photo = { path: '/sdcard/Pictures/preview.png', name: 'preview.png', size: 4, modified: 1 };
const imageResponse = () => new Response(new Uint8Array([1]), {
  headers: { 'Content-Type': 'image/png' },
});

async function fixture(t, { pending = false, failFirst = false, expireOnResume = false } = {}) {
  const jobs = [], requests = [];
  let listings = 0;
  const h = await browserHarness({ saved, routes: {
    '/remote/photos': ({ response }) => {
      if (++listings > 1 && expireOnResume)
        return response({ ok: false, error: 'unauthorized' }, 401);
      return response({
        path: '/sdcard', recursive: true, folders: [], files: [photo],
      });
    },
    '/remote/photo': ({ opts, response }) => {
      requests.push(opts);
      if (failFirst && requests.length === 1)
        return response({ error: 'preview_fixture_unavailable' }, 503);
      if (!pending) return imageResponse();
      return new Promise(resolve => jobs.push({
        signal: opts.signal, finish: () => resolve(imageResponse()),
      }));
    },
  } });
  // Keep gallery requests out of these preview tests. Real viewport admission
  // has its own suite; the shipped preview button still runs unchanged.
  h.eval('globalThis.IntersectionObserver=class { observe(){} unobserve(){} disconnect(){} }');
  h.click('Photos');
  await h.settle();
  h.document.getElementById('page').querySelector('button.photo-tile').click();
  await h.settle();
  const sheet = h.document.getElementById('sheet-content');
  const image = sheet.querySelector('.preview-image');
  t.after(async () => {
    h.hide();
    jobs.forEach(job => job.finish());
    await h.settle();
  });
  return { h, jobs, requests, sheet, image };
}

test('visibility resumes an interrupted preview and rejects its old completion', async t => {
  const { h, jobs, requests, sheet, image } = await fixture(t, { pending: true });
  assert.equal(requests.length, 1);
  h.hide();
  assert.equal(jobs[0].signal.aborted, true);
  h.show();
  await h.settle();
  assert.equal(requests.length, 1, 'The aborted request still occupies the one preview slot');
  jobs[0].finish();
  await h.settle();
  assert.equal(requests.length, 2, 'Resume must queue one replacement for the active preview');
  assert.equal(image.hidden, true, 'The retired response must not populate the preview');
  jobs[1].finish();
  await h.settle();
  assert.equal(image.hidden, false);
  assert.match(image.src, /^blob:/);
  assert.match(sheet.textContent, /Thumbnail from your Portal/);
  assert.doesNotMatch(sheet.textContent, /Loading preview/);
});

test('deferred preview cannot dispatch after the resumed listing expires pairing', async t => {
  const { h, jobs, requests, image } = await fixture(t, {
    pending: true, expireOnResume: true,
  });
  assert.equal(requests.length, 1);
  h.hide();
  assert.equal(jobs[0].signal.aborted, true);
  h.show();
  await h.settle();
  assert.equal(h.eval('connectionState'), 'expired', 'The resumed listing must return 401');
  assert.equal(requests.length, 1, 'The old request still occupies the preview slot');
  // Let the aborted response settle only after resume queued a replacement and
  // the metadata read expired pairing. Dispatch must recheck that ownership.
  jobs[0].finish();
  await h.settle();
  assert.equal(requests.length, 1, 'Expired pairing must prevent the deferred preview request');
  assert.equal(image.hidden, true, 'The retired response must not populate the preview');
});

test('visibility retains a completed preview without another download', async t => {
  const { h, requests, image } = await fixture(t);
  const url = image.src;
  h.hide();
  h.show();
  await h.settle();
  assert.equal(requests.length, 1);
  assert.equal(image.src, url);
  assert.equal(image.hidden, false);
});

for (const action of ['close', 'navigate', 'room', 'expired']) {
  test(`visibility cannot resume a preview after ${action}`, async t => {
    const { h, jobs, requests, image } = await fixture(t, { pending: true });
    h.hide();
    if (action === 'close') h.eval('closeSheet()');
    if (action === 'navigate') h.eval('go("home")');
    if (action === 'room') h.eval('selectRoom(roster[1].base)');
    if (action === 'expired') h.eval('connectionState="expired"');
    h.show();
    jobs[0].finish();
    await h.settle();
    assert.equal(requests.length, 1);
    assert.equal(image.hidden, true);
  });
}

test('successful preview retry clears the preceding error presentation', async t => {
  const { h, sheet, image, requests } = await fixture(t, { failFirst: true });
  assert.ok(sheet.querySelector('.error'), 'First failure must be shown');
  h.click('Retry preview');
  await h.settle();
  assert.equal(requests.length, 2);
  assert.equal(image.hidden, false);
  assert.match(sheet.textContent, /Thumbnail from your Portal/);
  assert.equal(!!sheet.querySelector('.error'), false, 'A recovered preview must not retain error styling');
});
