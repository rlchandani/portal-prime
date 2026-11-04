/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */
import test from 'node:test';
import assert from 'node:assert/strict';
import * as core from '../../main/assets/remote/core.mjs';

const options = { timeout: 3000 };
const a = () => ({ base: 'https://room-a.invalid', token: 'session-a', name: 'Room A' });
const b = () => ({ base: 'https://room-b.invalid', token: 'session-b', name: 'Room B' });
const c = () => ({ base: 'https://room-c.invalid', token: 'session-c', name: 'Room C' });
const destinations = () => [
  { room: a(), path: '/sdcard/Photos', deviceId: 'device-a' },
  { room: b(), path: '/sdcard/Photos', deviceId: 'device-b' },
];
function file(name = 'first.png', contents = 'abc', type = 'image/png') {
  const blob = new Blob([contents], { type });
  Object.defineProperty(blob, 'name', { value: name, writable: true });
  return blob;
}
function batch(targets = destinations(), files = [file()]) {
  assert.equal(typeof core.PhotoImportBatch, 'function',
    'core.mjs must export the approved PhotoImportBatch controller');
  return new core.PhotoImportBatch(targets, files);
}
function companion(room) {
  const ids = {
    'https://room-a.invalid': 'device-a',
    'https://room-b.invalid': 'device-b',
    'https://room-c.invalid': 'device-c',
  };
  return { deviceId: ids[room.base], capabilities: { photos: true } };
}
function receipt(item) {
  return { ok: true, path: `${item.path}/${item.name}`, bytes: item.file.size };
}
function runtime(overrides = {}) {
  return {
    current: () => true,
    inspect: async room => companion(room),
    send: async (_room, item) => receipt(item),
    changed: () => {},
    ...overrides,
  };
}
function deferred() {
  let resolve;
  const promise = new Promise(done => { resolve = done; });
  return { promise, resolve };
}
async function until(predicate, label) {
  for (let i = 0; i < 300; i++) {
    if (predicate()) return;
    await Promise.resolve();
  }
  assert.fail(`Controller did not reach ${label}`);
}
const states = transfer => transfer.items.map(item => item.state);

test('captures destination sessions, paths, identities and file names before running', options, async () => {
  const targets = destinations(), files = [file()];
  const transfer = batch(targets, files), sent = [], changes = [];
  targets[0].room.base = 'https://replacement.invalid';
  targets[0].room.token = 'replacement-session';
  targets[0].room.name = 'Renamed';
  targets[0].path = '/sdcard/Other';
  targets[0].deviceId = 'replacement-device';
  files[0].name = 'changed.png';
  files.push(file('later.png'));
  targets.push({ room: c(), path: '/sdcard/Other', deviceId: 'device-c' });
  await transfer.run(runtime({
    send: async (room, item) => {
      sent.push([room.base, room.token, room.name, item.path, item.deviceId, item.name]);
      return { ok: true, path: '/sdcard/Photos/first.png', bytes: 3 };
    },
    changed: current => changes.push([current.completed, current.bytes, current.running]),
  }));
  assert.deepEqual(sent.sort(), [
    ['https://room-a.invalid', 'session-a', 'Room A', '/sdcard/Photos', 'device-a', 'first.png'],
    ['https://room-b.invalid', 'session-b', 'Room B', '/sdcard/Photos', 'device-b', 'first.png'],
  ]);
  assert.equal(transfer.total, 2);
  assert.equal(transfer.completed, 2);
  assert.equal(transfer.bytes, 6);
  assert.equal(transfer.running, false);
  assert.deepEqual(states(transfer), ['confirmed', 'confirmed']);
  assert.ok(changes.length > 0, 'The public change callback exposes progress');
  assert.deepEqual(changes.at(-1), [2, 6, false]);
});

test('keeps definite failure and uncertain upload independent across room lanes', options, async () => {
  const transfer = batch(destinations(), [
    file('first.png', 'abc'), file('second.jpg', '12345', 'image/jpeg'),
    file('third.webp', 'xy', 'image/webp'),
  ]);
  await transfer.run(runtime({
    send: async (room, item) => {
      if (item.name === 'second.jpg' && room.base === 'https://room-a.invalid')
        throw new core.ApiError('file_exists', 409, { ok: false, error: 'file_exists' });
      if (item.name === 'second.jpg') throw new Error('owned transport reply lost');
      return receipt(item);
    },
  }));
  assert.equal(transfer.total, 6);
  assert.equal(transfer.completed, 4);
  assert.equal(transfer.bytes, 10);
  assert.deepEqual(transfer.items.map(item => [item.room.base, item.name, item.state]), [
    ['https://room-a.invalid', 'first.png', 'confirmed'],
    ['https://room-a.invalid', 'second.jpg', 'failed'],
    ['https://room-a.invalid', 'third.webp', 'confirmed'],
    ['https://room-b.invalid', 'first.png', 'confirmed'],
    ['https://room-b.invalid', 'second.jpg', 'unconfirmed'],
    ['https://room-b.invalid', 'third.webp', 'confirmed'],
  ]);
  assert.equal(transfer.items[1].error, 'file_exists');
});

test('requires exact positive acknowledgement and never retries uncertain posts', options, async () => {
  const transfer = batch([destinations()[0]], [
    file('wrong-path.png'), file('wrong-bytes.png'), file('missing-ok.png'),
    file('string-ok.png'), file('empty.png'), file('transport.png'),
  ]);
  const sent = [];
  const rt = runtime({
    send: async (_room, item) => {
      sent.push(item.name);
      switch (item.name) {
        case 'wrong-path.png': return { ok: true, path: '/sdcard/Other/wrong-path.png', bytes: 3 };
        case 'wrong-bytes.png': return { ok: true, path: '/sdcard/Photos/wrong-bytes.png', bytes: 2 };
        case 'missing-ok.png': return { path: '/sdcard/Photos/missing-ok.png', bytes: 3 };
        case 'string-ok.png': return { ok: 'true', path: '/sdcard/Photos/string-ok.png', bytes: 3 };
        case 'empty.png': return null;
        case 'transport.png': throw new core.ApiError('unreachable');
        default: assert.fail('Unexpected generated file');
      }
    },
  });
  await transfer.run(rt);
  assert.deepEqual(states(transfer), Array(6).fill('unconfirmed'));
  assert.equal(transfer.completed, 0);
  assert.equal(transfer.bytes, 0);
  await transfer.retry([a()], rt);
  await transfer.retry([a()], rt, transfer.items[0]);
  assert.deepEqual(sent, [
    'wrong-path.png', 'wrong-bytes.png', 'missing-ok.png',
    'string-ok.png', 'empty.png', 'transport.png',
  ]);
  assert.deepEqual(states(transfer), Array(6).fill('unconfirmed'));
});

test('explicit retry recaptures only failed jobs at the same endpoint', options, async () => {
  const transfer = batch([destinations()[0]], [
    file('confirmed.png'), file('failed.png'), file('uncertain.png'),
  ]), sent = [];
  await transfer.run(runtime({
    send: async (room, item) => {
      sent.push([room.base, room.token, item.name, item.path, item.deviceId]);
      if (item.name === 'failed.png')
        throw new core.ApiError('disk_full', 507, { ok: false, error: 'disk_full' });
      if (item.name === 'uncertain.png') throw new core.ApiError('timeout');
      return { ok: true, path: '/sdcard/Photos/confirmed.png', bytes: 3 };
    },
  }));
  const replacement = { ...a(), token: 'new-explicit-session', name: 'Room A renamed' };
  const rt = runtime({
    send: async (room, item) => {
      sent.push([room.base, room.token, item.name, item.path, item.deviceId]);
      return { ok: true, path: '/sdcard/Photos/failed.png', bytes: 3 };
    },
  });
  await transfer.retry([b()], rt);
  assert.equal(sent.length, 3, 'A different endpoint cannot adopt the failed job');
  await transfer.retry([replacement], rt);
  assert.deepEqual(sent[3], [
    'https://room-a.invalid', 'new-explicit-session', 'failed.png', '/sdcard/Photos', 'device-a',
  ]);
  assert.deepEqual(states(transfer), ['confirmed', 'confirmed', 'unconfirmed']);
  assert.equal(transfer.completed, 2);
  assert.equal(transfer.bytes, 6);
});

test('retry preserves captured physical identity before posting with a new session', options, async () => {
  const transfer = batch([destinations()[0]]), sent = [];
  await transfer.run(runtime({
    send: async (_room, item) => {
      sent.push(item.name);
      throw new core.ApiError('disk_full', 507, { ok: false, error: 'disk_full' });
    },
  }));
  await transfer.retry([{ ...a(), token: 'new-session' }], runtime({
    inspect: async () => ({ deviceId: 'different-device', capabilities: { photos: true } }),
    send: async (_room, item) => { sent.push(item.name); return receipt(item); },
  }));
  assert.deepEqual(sent, ['first.png']);
  assert.equal(transfer.items[0].deviceId, 'device-a');
  assert.equal(transfer.items[0].path, '/sdcard/Photos');
  assert.equal(transfer.items[0].error, 'identity_changed');
  assert.equal(transfer.completed, 0);
});

test('stop cancels queued jobs while allowing an in-flight exact receipt to settle', options, async () => {
  const transfer = batch([destinations()[0]], [
    file('first.png'), file('second.png'), file('third.png'),
  ]), held = deferred(), sent = [];
  const running = transfer.run(runtime({
    send: async (_room, item, signal) => {
      sent.push([item.name, signal]);
      return held.promise;
    },
  }));
  try {
    await until(() => sent.length === 1, 'first upload');
    transfer.stop();
    assert.equal(sent[0][1].aborted, false);
    held.resolve({ ok: true, path: '/sdcard/Photos/first.png', bytes: 3 });
    await running;
    assert.deepEqual(sent.map(row => row[0]), ['first.png']);
    assert.deepEqual(states(transfer), ['confirmed', 'cancelled', 'cancelled']);
    assert.equal(transfer.completed, 1);
    assert.equal(transfer.bytes, 3);
    assert.equal(transfer.running, false);
    assert.equal(transfer.cancelled, true);
    await transfer.retry([a()], runtime({
      send: async (_room, item) => { sent.push([item.name]); return receipt(item); },
    }));
    assert.equal(sent.length, 1);
  } finally {
    held.resolve({ ok: true, path: '/sdcard/Photos/first.png', bytes: 3 });
    transfer.stop('test_cleanup', { abort: true });
  }
});

test('limits work to two room lanes and keeps each room file sequence serial', options, async () => {
  const transfer = batch([
    ...destinations(), { room: c(), path: '/sdcard/Photos', deviceId: 'device-c' },
  ], [file('first.png'), file('second.png')]);
  const held = [], active = new Map();
  let maximum = 0;
  const running = transfer.run(runtime({
    send: async (room, item) => {
      assert.equal(active.get(room.base) || 0, 0, 'Two files in one room must not overlap');
      active.set(room.base, 1);
      maximum = Math.max(maximum, [...active.values()].reduce((sum, count) => sum + count, 0));
      const gate = deferred();
      held.push({ base: room.base, name: item.name, item, gate });
      try { return await gate.promise; }
      finally { active.set(room.base, 0); }
    },
  }));
  try {
    await until(() => held.length === 2, 'two room lanes');
    assert.equal(new Set(held.map(row => row.base)).size, 2);
    assert.deepEqual(held.map(row => row.name), ['first.png', 'first.png']);
    const firstLane = held[0].base, secondLane = held[1].base;
    const remainingLane = [
      'https://room-a.invalid', 'https://room-b.invalid', 'https://room-c.invalid',
    ].find(base => base !== firstLane && base !== secondLane);
    held[0].gate.resolve({ ok: true, path: '/sdcard/Photos/first.png', bytes: 3 });
    await until(() => held.length === 3, 'second file in first lane');
    assert.deepEqual([held[2].base, held[2].name], [firstLane, 'second.png']);
    assert.equal(held.length, 3, 'Third room waits until a complete room lane is free');
    held[2].gate.resolve({ ok: true, path: '/sdcard/Photos/second.png', bytes: 3 });
    await until(() => held.length === 4, 'third room after first room completes');
    assert.deepEqual([held[3].base, held[3].name], [remainingLane, 'first.png']);
    held[1].gate.resolve({ ok: true, path: '/sdcard/Photos/first.png', bytes: 3 });
    held[3].gate.resolve({ ok: true, path: '/sdcard/Photos/first.png', bytes: 3 });
    await until(() => held.length === 6, 'remaining serial files');
    held[4].gate.resolve({ ok: true, path: '/sdcard/Photos/second.png', bytes: 3 });
    held[5].gate.resolve({ ok: true, path: '/sdcard/Photos/second.png', bytes: 3 });
    await running;
    assert.equal(maximum, 2);
    assert.equal(transfer.completed, 6);
    assert.equal(transfer.bytes, 18);
    assert.deepEqual(states(transfer), Array(6).fill('confirmed'));
  } finally {
    transfer.stop('test_cleanup', { abort: true });
    for (const row of held) row.gate.resolve(receipt(row.item));
  }
});

test('rejects missing identity, changed device, unsupported photos and stale pairing before POST', options, async () => {
  assert.equal(typeof core.PhotoImportBatch, 'function',
    'core.mjs must export the approved PhotoImportBatch controller');
  const cases = [
    { id: '', info: companion(a()), current: true, error: 'identity_unverified' },
    { id: 'device-a', info: { capabilities: { photos: true } }, current: true, error: 'identity_unverified' },
    { id: 'device-a', info: { deviceId: 'device-other', capabilities: { photos: true } }, current: true, error: 'identity_changed' },
    { id: 'device-a', info: { deviceId: 'device-a', capabilities: { photos: false } }, current: true, error: 'photos_unsupported' },
    { id: 'device-a', info: companion(a()), current: false, error: 'pairing_changed' },
  ];
  for (const row of cases) {
    const transfer = batch([{ room: a(), path: '/sdcard/Photos', deviceId: row.id }]), sent = [];
    await transfer.run(runtime({
      current: () => row.current,
      inspect: async () => row.info,
      send: async (_room, item) => { sent.push(item.name); return receipt(item); },
    }));
    assert.deepEqual(sent, [], row.error);
    assert.equal(transfer.items[0].error, row.error);
    assert.equal(transfer.completed, 0);
    assert.equal(transfer.bytes, 0);
  }
});

test('rechecks session currency after a pending identity inspection before POST', options, async () => {
  const transfer = batch([destinations()[0]]), inspection = deferred(), sent = [];
  let current = true, inspected = false;
  const running = transfer.run(runtime({
    current: () => current,
    inspect: async () => { inspected = true; return inspection.promise; },
    send: async (_room, item) => { sent.push(item.name); return receipt(item); },
  }));
  try {
    await until(() => inspected, 'pending identity inspection');
    current = false;
    inspection.resolve({ deviceId: 'device-a', capabilities: { photos: true } });
    await running;
    assert.deepEqual(sent, []);
    assert.equal(transfer.items[0].error, 'pairing_changed');
    assert.equal(transfer.completed, 0);
  } finally {
    inspection.resolve({ deviceId: 'device-a', capabilities: { photos: true } });
    transfer.stop('test_cleanup', { abort: true });
  }
});

test('retiring one room aborts its posted job and queue while other room completes', options, async () => {
  const transfer = batch(destinations(), [file('first.png'), file('second.png')]), sent = [];
  let activeSignal;
  const running = transfer.run(runtime({
    send: async (room, item, signal) => {
      sent.push([room.base, item.name]);
      if (room.base === 'https://room-a.invalid') {
        activeSignal = signal;
        return new Promise((_resolve, reject) => {
          const fail = () => reject(new core.ApiError('aborted'));
          if (signal.aborted) fail();
          else signal.addEventListener('abort', fail, { once: true });
        });
      }
      return receipt(item);
    },
  }));
  try {
    await until(() => !!activeSignal, 'posted upload for retired room');
    transfer.retire('https://room-a.invalid');
    await running;
    assert.equal(activeSignal.aborted, true);
    assert.deepEqual(sent, [
      ['https://room-a.invalid', 'first.png'],
      ['https://room-b.invalid', 'first.png'],
      ['https://room-b.invalid', 'second.png'],
    ]);
    assert.deepEqual(states(transfer), ['unconfirmed', 'cancelled', 'confirmed', 'confirmed']);
    assert.equal(transfer.completed, 2);
    assert.equal(transfer.bytes, 6);
    await transfer.retry([a(), b()], runtime({
      send: async (room, item) => { sent.push([room.base, item.name]); return receipt(item); },
    }));
    assert.equal(sent.length, 3, 'Retired cancelled and uncertain work must not be replayed');
  } finally {
    transfer.stop('test_cleanup', { abort: true });
  }
});

test('collision rename changes only a failed item and sends only after explicit selected retry', options, async () => {
  const transfer = batch([destinations()[0]], [
    file('collision.png'), file('other.png'), file('confirmed.png'),
  ]), sent = [];
  await transfer.run(runtime({
    send: async (_room, item) => {
      sent.push(item.name);
      if (item.name !== 'confirmed.png')
        throw new core.ApiError('file_exists', 409, { ok: false, error: 'file_exists' });
      return { ok: true, path: '/sdcard/Photos/confirmed.png', bytes: 3 };
    },
  }));
  const selected = transfer.items[0];
  assert.throws(() => transfer.rename(selected, '../escape.png'), error => error.code === 'invalid_name');
  assert.throws(() => transfer.rename(selected, 'unsupported.txt'), error => error.code === 'invalid_name');
  assert.equal(selected.name, 'collision.png');
  transfer.rename(selected, 'renamed.gif');
  assert.equal(selected.name, 'renamed.gif');
  assert.deepEqual(sent, ['collision.png', 'other.png', 'confirmed.png'], 'Rename alone never posts');
  await transfer.retry([{ ...a(), token: 'explicit-retry' }], runtime({
    send: async (room, item) => {
      assert.equal(room.token, 'explicit-retry');
      sent.push(item.name);
      return { ok: true, path: '/sdcard/Photos/renamed.gif', bytes: 3 };
    },
  }), selected);
  assert.deepEqual(sent, ['collision.png', 'other.png', 'confirmed.png', 'renamed.gif']);
  assert.deepEqual(states(transfer), ['confirmed', 'failed', 'confirmed']);
  assert.equal(transfer.completed, 2);
  assert.equal(transfer.bytes, 6);
  assert.equal(transfer.items[1].name, 'other.png');
});

test('constructor rejects unsafe names, unsupported extensions, oversized files and invalid paths', options, () => {
  assert.equal(typeof core.PhotoImportBatch, 'function',
    'core.mjs must export the approved PhotoImportBatch controller');
  const cases = [
    { targets: [destinations()[0]], input: file('../escape.png'), code: 'invalid_name' },
    { targets: [destinations()[0]], input: file('image.txt'), code: 'invalid_name' },
    { targets: [destinations()[0]], input: file(`${'a'.repeat(125)}.png`), code: 'invalid_name' },
    { targets: [destinations()[0]], input: file('empty.png', ''), code: 'upload_size_limit' },
    { targets: [destinations()[0]], input: file('large.png', new Uint8Array(20 * 1048576 + 1)), code: 'upload_size_limit' },
    { targets: [{ ...destinations()[0], path: '/sdcard/../Other' }], input: file(), code: 'invalid_path' },
    { targets: [{ ...destinations()[0], path: '/private/Photos' }], input: file(), code: 'invalid_path' },
  ];
  for (const row of cases)
    assert.throws(() => new core.PhotoImportBatch(row.targets, [row.input]),
      error => error instanceof core.ApiError && error.code === row.code, row.code);
});

test('stop and retire preserve cancelled queued work when pending inspection rejects', options, async () => {
  const observations = [];
  for (const action of ['stop', 'retire']) {
    const transfer = batch([destinations()[0]]), sent = [];
    let rejectInspection, inspecting = false;
    const pending = new Promise((_resolve, reject) => { rejectInspection = reject; });
    const running = transfer.run(runtime({
      inspect: async () => { inspecting = true; return pending; },
      send: async (_room, item) => { sent.push(item.name); return receipt(item); },
    }));
    try {
      await until(() => inspecting, `${action} pending inspection`);
      if (action === 'stop') transfer.stop();
      else transfer.retire('https://room-a.invalid');
      rejectInspection(new core.ApiError('unreachable'));
      await running;
      const stateAfterInspection = transfer.items[0].state;
      const postsBeforeRetry = sent.length;
      await transfer.retry([a()], runtime({
        send: async (_room, item) => { sent.push(item.name); return receipt(item); },
      }));
      observations.push({
        action, stateAfterInspection, postsBeforeRetry,
        postsAfterRetry: sent.length, finalState: transfer.items[0].state,
        completed: transfer.completed,
      });
    } finally {
      rejectInspection(new core.ApiError('unreachable'));
      transfer.stop('test_cleanup', { abort: true });
    }
  }
  assert.deepEqual(observations, [
    {
      action: 'stop', stateAfterInspection: 'cancelled', postsBeforeRetry: 0,
      postsAfterRetry: 0, finalState: 'cancelled', completed: 0,
    },
    {
      action: 'retire', stateAfterInspection: 'cancelled', postsBeforeRetry: 0,
      postsAfterRetry: 0, finalState: 'cancelled', completed: 0,
    },
  ]);
});
