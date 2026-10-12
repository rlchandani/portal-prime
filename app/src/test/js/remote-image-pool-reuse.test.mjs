/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */
import test from 'node:test';
import assert from 'node:assert/strict';
import { ImagePool } from '../../main/assets/remote/core.mjs';
const deferred = () => {
  let resolve, reject;
  const promise = new Promise((yes, no) => {
    resolve = yes;
    reject = no;
  });
  return { promise, resolve, reject };
};
const settle = () => new Promise((resolve) => setImmediate(resolve));
function fixture(options = {}) {
  const revoked = [], delivered = [];
  let created = 0;
  const pool = new ImagePool({ create: () => `blob:${++created}`, revoke: (url) => revoked.push(url), ...options });
  return { pool, revoked, delivered };
}

test('duplicate pending and ready images deliver asynchronously to the latest consumer only', async () => {
  const { pool, delivered } = fixture();
  const pending = deferred();
  let loads = 0;
  const load = () => { loads++; return pending.promise; };
  const first = pool.add('photo', load, () => delivered.push('detached'));
  const current = pool.add('photo', load, (url) => delivered.push(url));
  assert.equal(first, current);
  pending.resolve(new Blob(['image']));
  assert.deepEqual(delivered, []);
  await first;
  assert.deepEqual(delivered, ['blob:1']);
  pool.add('photo', load, () => delivered.push('superseded-ready'));
  pool.add('photo', load, (url) => delivered.push(`ready:${url}`));
  assert.deepEqual(delivered, ['blob:1']);
  await settle();
  assert.deepEqual(delivered, ['blob:1', 'ready:blob:1']);
  assert.equal(loads, 1);
  pool.clear();
});

for (const action of ['clear', 'retain']) {
  test(`${action} cancels a scheduled ready delivery and revokes exactly once`, async () => {
    const { pool, delivered, revoked } = fixture();
    await pool.add('photo', async () => new Blob(['image']), () => {});
    pool.add('photo', () => { throw new Error('Cached image reloaded'); }, (url) => delivered.push(url));
    if (action === 'clear') pool.clear(); else pool.retain([]);
    pool.clear();
    await settle();
    assert.deepEqual(delivered, []);
    assert.deepEqual(revoked, ['blob:1']);
  });
}

test('retain cancels queued work but aborted running loads occupy slots until settlement', async () => {
  const { pool, revoked, delivered } = fixture();
  const pending = [];
  let aborts = 0;
  const load = (signal) => {
    signal.addEventListener('abort', () => aborts++);
    const job = deferred(); pending.push(job); return job.promise;
  };
  const old = Array.from({ length: 24 }, (_, i) => pool.add(`old:${i}`, load, (url) => delivered.push(url)));
  assert.equal(pending.length, 4);
  pool.retain([]);
  assert.deepEqual(await Promise.all(old.slice(4)), Array(20).fill(false));
  const fresh = Array.from({ length: 24 }, (_, i) => pool.add(`new:${i}`, load, (url) => delivered.push(url)));
  assert.equal(await pool.add('overflow', load, () => {}), false);
  assert.equal(pool.size, 24);
  assert.equal(pending.length, 4);
  assert.equal(aborts, 4);
  pending.splice(0).forEach((job) => job.resolve(new Blob(['late'])));
  await Promise.all(old.slice(0, 4));
  assert.equal(delivered.length, 0);
  assert.equal(pending.length, 4);
  assert.equal(revoked.length, 4);
  pool.clear();
  assert.deepEqual(await Promise.all(fresh.slice(4)), Array(20).fill(false));
  pending.splice(0).forEach((job) => job.resolve(new Blob(['late'])));
  await Promise.all(fresh.slice(0, 4));
  pool.clear();
  assert.equal(aborts, 8);
  assert.equal(revoked.length, 8);
  assert.equal(new Set(revoked).size, 8);
});

test('failed loads notify the rebound consumer and allow a successful retry', async () => {
  const { pool, delivered } = fixture();
  const pending = deferred();
  const errors = [];
  const first = pool.add('photo', () => pending.promise, () => {}, () => errors.push('detached'));
  pool.add('photo', () => { throw new Error('Duplicate load'); }, () => {}, (error) => errors.push(error.message));
  pending.reject(new Error('unavailable'));
  await first;
  assert.deepEqual(errors, ['unavailable']);
  assert.equal(pool.size, 0);
  await pool.add('photo', async () => new Blob(['retry']), (url) => delivered.push(url));
  assert.deepEqual(delivered, ['blob:1']);
  pool.clear();
});

for (const action of ['clear', 'retain']) {
  test(`${action} between load settlement and delivery cancels the microtask`, async () => {
    const { pool, delivered, revoked } = fixture();
    const pending = deferred();
    const result = pool.add('photo', () => pending.promise, (url) => delivered.push(url));
    pending.resolve(new Blob(['image']));
    queueMicrotask(() => action === 'clear' ? pool.clear() : pool.retain([]));
    await result;
    await settle();
    assert.deepEqual(delivered, []);
    assert.deepEqual(revoked, ['blob:1']);
    pool.clear();
    assert.deepEqual(revoked, ['blob:1']);
  });
}

test('rebinding between failure settlement and delivery still reports failure and permits retry', async () => {
  const { pool, delivered } = fixture();
  const pending = deferred();
  const errors = [];
  const result = pool.add('photo', () => pending.promise, () => {}, () => errors.push('detached'));
  pending.reject(new Error('unavailable'));
  queueMicrotask(() => pool.add('photo', () => { throw new Error('Duplicate load'); }, () => {}, (error) => errors.push(error.message)));
  await result;
  await settle();
  assert.deepEqual(errors, ['unavailable']);
  assert.equal(pool.size, 0);
  await pool.add('photo', async () => new Blob(['retry']), (url) => delivered.push(url));
  assert.deepEqual(delivered, ['blob:1']);
  pool.clear();
});
