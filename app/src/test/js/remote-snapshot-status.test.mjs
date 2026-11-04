/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */
import test from 'node:test';
import assert from 'node:assert/strict';
import { browserHarness } from './remote-browser-harness.mjs';

const saved = [{ base: 'http://127.0.0.1:8877', name: 'Snapshot fixture', token: 'fixture-token' }];
const snapshot = (domains = {}) => ({
  schemaVersion: 1, deviceId: 'fixture-device', revision: 'confirmed-revision', domains, layout: {},
});
const pageText = h => h.document.getElementById('page').textContent;
const snapshotReads = h => h.calls.filter(call =>
  call.path === '/remote/snapshot' && call.opts.method !== 'POST');

async function openSnapshots(route) {
  const h = await browserHarness({ saved, routes: { '/remote/snapshot': route } });
  const navigation = h.document.body.querySelectorAll('button').find(button =>
    button.querySelector('strong')?.textContent === 'Snapshots');
  assert.ok(navigation, 'The rendered Home snapshot action must exist');
  assert.equal(navigation.disabled, false);
  navigation.click();
  await h.settle();
  return h;
}

test('Snapshot initial HTTP failure shows unavailable after the read settles', async () => {
  const h = await openSnapshots(({ response }) =>
    response({ ok: false, error: 'unavailable' }, 503));
  try {
    assert.equal(snapshotReads(h).length, 1);
    assert.match(pageText(h), /The Portal reported unavailable/);
    assert.doesNotMatch(pageText(h), /Loading snapshot…/);
    assert.match(pageText(h), /Snapshot unavailable/);
  } finally { h.hide(); }
});

test('Snapshot pending first read keeps Loading until the confirmed response arrives', async () => {
  let finish;
  const h = await openSnapshots(({ response }) => new Promise(resolve => {
    finish = () => resolve(response({ ok: true, snapshot: snapshot() }));
  }));
  try {
    assert.equal(snapshotReads(h).length, 1);
    assert.match(pageText(h), /Loading snapshot…/);
    assert.doesNotMatch(pageText(h), /Snapshot unavailable|The Portal reported unavailable/);
    finish(); await h.settle();
    assert.match(pageText(h), /0 settings categories · This Portal only/);
    assert.doesNotMatch(pageText(h), /Loading snapshot…|Snapshot unavailable/);
  } finally { h.hide(); }
});

test('Snapshot confirmed empty backup shows zero categories without a loading or failure label', async () => {
  const h = await openSnapshots(({ response }) =>
    response({ ok: true, snapshot: snapshot() }));
  try {
    assert.equal(snapshotReads(h).length, 1);
    assert.match(pageText(h), /0 settings categories · This Portal only/);
    assert.doesNotMatch(pageText(h), /Loading snapshot…|Snapshot unavailable|The Portal reported unavailable/);
  } finally { h.hide(); }
});

test('Snapshot failed public Refresh retains its confirmed category summary and adds the error', async () => {
  let reads = 0;
  const h = await openSnapshots(({ response }) => ++reads === 1
    ? response({ ok: true, snapshot: snapshot({ clock: {}, welcome: {}, screensaver: {} }) })
    : response({ ok: false, error: 'unavailable' }, 503));
  try {
    assert.match(pageText(h), /3 settings categories · This Portal only/);
    h.document.getElementById('refresh-button').click(); await h.settle();
    assert.equal(snapshotReads(h).length, 2);
    assert.match(pageText(h), /3 settings categories · This Portal only/);
    assert.match(pageText(h), /The Portal reported unavailable/);
    assert.doesNotMatch(pageText(h), /Loading snapshot…|Snapshot unavailable/);
  } finally { h.hide(); }
});
