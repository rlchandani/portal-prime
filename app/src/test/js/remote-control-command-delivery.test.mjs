/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */
import test from 'node:test';
import assert from 'node:assert/strict';
import { browserHarness } from './remote-browser-harness.mjs';
async function playback(path) {
  let release, starts = 0;
  const h = await browserHarness({
    saved: [
      { base: 'http://127.0.0.1:8877', name: 'A', token: 'fixture-a' },
      { base: 'http://127.0.0.1:8878', name: 'B', token: 'fixture-b' },
    ],
    routes: {
      '/remote/nowplaying': ({ response }) => response({ ok: true, np: {
        active: true, playing: true, durationMs: 180000, positionMs: 10000,
        canSeek: true, hasArt: false, volume: { level: 4, max: 15, canSet: true },
      } }),
      [path]: ({ response }) => ++starts === 1 ? new Promise(resolve => {
        release = () => resolve(response({ ok: true, dispatched: true }));
      }) : response({ ok: true, dispatched: true }),
    },
  });
  return { h, release: () => release(), calls: () => h.calls.filter(c => c.path === path) };
}
test('rapid released volume values keep the final value with only two serial writes', async () => {
  const { h, release, calls } = await playback('/remote/volume');
  try {
    const slider = h.document.body.querySelector('[aria-label="Portal music volume"]');
    slider.value = 5; slider.onchange(); await h.settle();
    for (let level = 6; level <= 15; level++) { slider.value = level; slider.onchange(); }
    await h.settle(); assert.equal(calls().length, 1);
    release(); await h.settle();
    assert.deepEqual(calls().map(c => JSON.parse(c.opts.body).level), [5, 15]);
  } finally { h.hide(); }
});
test('rapid released seek positions preserve the final position without duplicate intermediate posts', async () => {
  const { h, release, calls } = await playback('/remote/media');
  try {
    const slider = h.document.body.querySelector('[aria-label="Playback position"]');
    slider.value = 20000; slider.onchange(); await h.settle();
    for (const position of [40000, 60000, 120000]) { slider.value = position; slider.onchange(); }
    await h.settle(); assert.equal(calls().length, 1);
    release(); await h.settle();
    assert.deepEqual(calls().map(c => JSON.parse(c.opts.body).positionMs), [20000, 120000]);
  } finally { h.hide(); }
});
test('relative volume commands remain ordered between absolute slider values', async () => {
  const { h, release, calls } = await playback('/remote/volume');
  try {
    h.eval('volumeCommand("set", 5)'); await h.settle();
    h.eval('volumeCommand("set", 8);volumeCommand("up");volumeCommand("set", 11);volumeCommand("set", 13)');
    await h.settle(); release(); await h.settle();
    assert.deepEqual(calls().map(c => JSON.parse(c.opts.body)), [
      { dir: 'set', level: 5 }, { dir: 'set', level: 8 }, { dir: 'up' }, { dir: 'set', level: 13 },
    ]);
  } finally { h.hide(); }
});
test('queued volume values are discarded after selecting another Portal', async () => {
  const { h, release, calls } = await playback('/remote/volume');
  try {
    h.eval('volumeCommand("set", 5)'); await h.settle();
    h.eval('volumeCommand("set", 12)'); await h.settle();
    h.eval('selectRoom("http://127.0.0.1:8878")'); await h.settle();
    release(); await h.settle(); assert.equal(calls().length, 1);
    h.eval('volumeCommand("set", 9)'); await h.settle();
    assert.equal(calls().length, 2);
    assert.equal(calls()[1].url, 'http://127.0.0.1:8878/remote/volume');
    assert.equal(JSON.parse(calls()[1].opts.body).level, 9);
  } finally { h.hide(); }
});
test('queued media actions stop after leaving the playback page', async () => {
  const { h, release, calls } = await playback('/remote/media');
  try {
    h.eval('mediaCommand("seek", 20000)'); await h.settle();
    h.eval('mediaCommand("seek", 120000);mediaCommand("next")'); await h.settle();
    h.eval('go("apps")'); await h.settle(); release(); await h.settle();
    assert.equal(calls().length, 1);
  } finally { h.hide(); }
});
test('hide then show retires queued values even when the old response finishes after reopening', async () => {
  const { h, release, calls } = await playback('/remote/volume');
  try {
    h.eval('volumeCommand("set", 5)'); await h.settle();
    h.eval('volumeCommand("set", 12)'); await h.settle();
    h.hide(); h.show(); await h.settle(); release(); await h.settle();
    assert.equal(calls().length, 1);
    h.eval('volumeCommand("set", 7)'); await h.settle();
    assert.deepEqual(calls().map(c => JSON.parse(c.opts.body).level), [5, 7]);
  } finally { h.hide(); }
});

const keyRooms = [
  { base: 'http://127.0.0.1:8877', name: 'A', token: 'fixture-a' },
  { base: 'http://127.0.0.1:8878', name: 'B', token: 'fixture-b' },
];

async function keyHarness(reply) {
  return browserHarness({
    saved: keyRooms,
    routes: { '/remote/key': ({ response }) => response(reply) },
  });
}

for (const [name, reply] of [
  ['empty object', {}],
  ['array', []],
  ['false', false],
  ['missing ok with dispatch fields', { action: 'home', dispatched: true }],
  ['non-Boolean ok', { ok: 'true', action: 'home', dispatched: true }],
]) {
  test(`Show Home rejects a ${name} acknowledgment without reporting dispatch`, async () => {
    const h = await keyHarness(reply);
    try {
      h.click('Show Home');
      await h.settle();
      const calls = h.calls.filter(c => c.path === '/remote/key');
      assert.equal(calls.length, 1);
      assert.deepEqual(JSON.parse(calls[0].opts.body), { action: 'home' });
      const notice = h.document.getElementById('notice');
      assert.equal(notice.hidden, false);
      assert.equal(notice.dataset.error, 'true');
      assert.match(notice.textContent, /unreadable response/i);
      assert.doesNotMatch(notice.textContent, /command dispatched/i);
    } finally { h.hide(); }
  });
}

test('Show Home reports an explicit successful key acknowledgment', async () => {
  const h = await keyHarness({ ok: true, action: 'home', dispatched: true });
  try {
    h.click('Show Home');
    await h.settle();
    const notice = h.document.getElementById('notice');
    assert.equal(notice.dataset.error, 'false');
    assert.match(notice.textContent, /command dispatched/i);
    assert.equal(h.calls.filter(c => c.path === '/remote/key').length, 1);
  } finally { h.hide(); }
});

test('Apps accepts its explicit ok acknowledgment without a dispatched field', async () => {
  const h = await keyHarness({ ok: true, action: 'apps' });
  try {
    h.eval('go("remote")');
    await h.settle();
    h.click('Apps');
    await h.settle();
    const calls = h.calls.filter(c => c.path === '/remote/key');
    assert.equal(calls.length, 1);
    assert.deepEqual(JSON.parse(calls[0].opts.body), { action: 'apps' });
    const notice = h.document.getElementById('notice');
    assert.equal(notice.dataset.error, 'false');
    assert.match(notice.textContent, /command dispatched/i);
  } finally { h.hide(); }
});

for (const [name, reply] of [
  ['explicit rejection', { ok: false, error: 'fixture_rejected' }],
  ['failed dispatch', { ok: true, action: 'home', dispatched: false }],
]) {
  test(`Show Home retains error feedback for ${name}`, async () => {
    const h = await keyHarness(reply);
    try {
      h.click('Show Home');
      await h.settle();
      const notice = h.document.getElementById('notice');
      assert.equal(notice.dataset.error, 'true');
      assert.doesNotMatch(notice.textContent, /command dispatched/i);
      assert.equal(h.calls.filter(c => c.path === '/remote/key').length, 1);
    } finally { h.hide(); }
  });
}

test('An old key acknowledgment cannot replace feedback after choosing another Portal', async () => {
  let release;
  const h = await browserHarness({
    saved: keyRooms,
    routes: {
      '/remote/key': ({ response }) => new Promise(resolve => {
        release = () => resolve(response({ action: 'home', dispatched: true }));
      }),
    },
  });
  try {
    h.click('Show Home');
    await h.settle();
    h.eval('selectRoom("http://127.0.0.1:8878");toast("Current room notice.")');
    await h.settle();
    release();
    await h.settle();
    const notice = h.document.getElementById('notice');
    assert.equal(notice.textContent, 'Current room notice.');
    assert.equal(notice.dataset.error, 'false');
    const calls = h.calls.filter(c => c.path === '/remote/key');
    assert.equal(calls.length, 1);
    assert.equal(calls[0].url, 'http://127.0.0.1:8877/remote/key');
  } finally { h.hide(); }
});
