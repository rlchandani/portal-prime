/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */
import test from 'node:test';
import assert from 'node:assert/strict';
import * as core from '../../main/assets/remote/core.mjs';

const deferred = () => {
  let resolve, reject;
  const promise = new Promise((yes, no) => {
    resolve = yes;
    reject = no;
  });
  return { promise, resolve, reject };
};

// Advance promise continuations without relying on elapsed time.
const flush = () => new Promise((resolve) => setImmediate(resolve));

test('an absolute burst while in flight sends only the active and latest values', async () => {
  const queue = new core.ControlCommandQueue();
  const gates = [deferred(), deferred()];
  const sent = [];
  let live = 0, peak = 0;
  const enqueue = (value) => queue.enqueue('volume', {
    coalesceKey: 'absolute',
    run: async () => {
      sent.push(value);
      live++;
      peak = Math.max(peak, live);
      await gates[sent.length - 1].promise;
      live--;
      return value;
    },
  });
  const first = enqueue(0);
  const burst = Array.from({ length: 25 }, (_, i) => enqueue(i + 1));
  assert.deepEqual(sent, [0]);
  assert.deepEqual(await Promise.all(burst.slice(0, -1)), Array(24).fill(null));
  assert.equal(queue.hasPending('volume'), true);
  gates[0].resolve();
  assert.equal(await first, 0);
  assert.deepEqual(sent, [0, 25]);
  assert.equal(live, 1);
  gates[1].resolve();
  assert.equal(await burst.at(-1), 25);
  assert.equal(peak, 1);
  assert.deepEqual(sent, [0, 25]);
  assert.equal(queue.hasPending('volume'), false);
});

test('relative commands retain FIFO order and never overlap on the same key', async () => {
  const queue = new core.ControlCommandQueue();
  const gates = [deferred(), deferred(), deferred()];
  const sent = [];
  const commands = ['up', 'down', 'up'].map((action, i) => queue.enqueue('volume', {
    run: () => {
      sent.push(action);
      return gates[i].promise;
    },
  }));
  assert.deepEqual(sent, ['up']);
  gates[0].resolve('first');
  assert.equal(await commands[0], 'first');
  assert.deepEqual(sent, ['up', 'down']);
  gates[1].resolve('second');
  assert.equal(await commands[1], 'second');
  assert.deepEqual(sent, ['up', 'down', 'up']);
  gates[2].resolve('third');
  assert.deepEqual(await Promise.all(commands), ['first', 'second', 'third']);
});

test('absolute coalescing stops at relative actions and different pending tail keys', async () => {
  const queue = new core.ControlCommandQueue();
  const gate = deferred();
  const sent = [];
  const enqueue = (value, coalesceKey) => queue.enqueue('controls', {
    coalesceKey,
    run: () => {
      sent.push(value);
      return value === 10 ? gate.promise : value;
    },
  });
  const commands = [
    enqueue(10, 'volume'),
    enqueue(20, 'volume'),
    enqueue(30, 'volume'),
    enqueue('up', null),
    enqueue(40, 'volume'),
    enqueue(50, 'volume'),
    enqueue(60, 'brightness'),
    enqueue(70, 'volume'),
  ];
  assert.deepEqual(sent, [10]);
  assert.deepEqual(await Promise.all([commands[1], commands[4]]), [null, null]);
  gate.resolve(10);
  assert.deepEqual(await Promise.all(commands), [10, null, 30, 'up', null, 50, 60, 70]);
  assert.deepEqual(sent, [10, 30, 'up', 50, 60, 70]);
});

test('queued commands check their session, room, and page tickets just before running', async () => {
  const queue = new core.ControlCommandQueue();
  const session = new core.Session();
  session.select({ base: 'http://study:8723', token: 'old' });
  session.enter('home');
  const gate = deferred();
  const sent = [];
  const first = queue.enqueue('controls', { run: () => gate.promise });
  const ticket = session.capture();
  const stale = queue.enqueue('controls', {
    current: () => session.current(ticket),
    run: () => sent.push('stale'),
  });
  session.select({ base: 'http://kitchen:8723', token: 'new' });
  session.enter('apps');
  const freshTicket = session.capture();
  const fresh = queue.enqueue('controls', {
    current: () => session.current(freshTicket),
    run: () => { sent.push('fresh'); return 'done'; },
  });
  gate.resolve('active completed');
  assert.deepEqual(await Promise.all([first, stale, fresh]), ['active completed', null, 'done']);
  assert.deepEqual(sent, ['fresh']);
  assert.equal(queue.hasPending('controls'), false);
});

test('a failed run rejects its caller while queued and later commands still work', async () => {
  const queue = new core.ControlCommandQueue();
  const gate = deferred();
  const failure = new Error('send failed');
  const thrown = new Error('synchronous failure');
  const sent = [];
  const first = queue.enqueue('volume', { run: () => gate.promise });
  const rejected = assert.rejects(first, (error) => error === failure);
  const second = queue.enqueue('volume', { run: () => { sent.push('queued'); return 2; } });
  gate.reject(failure);
  await rejected;
  assert.equal(await second, 2);
  const syncFailure = queue.enqueue('volume', { run: () => { throw thrown; } });
  await assert.rejects(syncFailure, (error) => error === thrown);
  assert.equal(await queue.enqueue('volume', { run: () => { sent.push('future'); return 3; } }), 3);
  assert.deepEqual(sent, ['queued', 'future']);
  assert.equal(queue.hasPending('volume'), false);
});

test('independent keys dispatch in parallel without waiting for each other', async () => {
  const queue = new core.ControlCommandQueue();
  const volumeGate = deferred();
  const brightnessGate = deferred();
  const sent = [];
  const volume = queue.enqueue('volume', {
    run: () => { sent.push('volume'); return volumeGate.promise; },
  });
  const brightness = queue.enqueue('brightness', {
    run: () => { sent.push('brightness'); return brightnessGate.promise; },
  });
  assert.deepEqual(sent, ['volume', 'brightness']);
  assert.equal(queue.hasPending('volume'), true);
  assert.equal(queue.hasPending('brightness'), true);
  brightnessGate.resolve(75);
  assert.equal(await brightness, 75);
  assert.equal(queue.hasPending('brightness'), false);
  assert.equal(queue.hasPending('volume'), true);
  volumeGate.resolve(25);
  assert.equal(await volume, 25);
});

test('idle entries are removed after draining and the same key can dispatch again', async () => {
  const queue = new core.ControlCommandQueue();
  const gate = deferred();
  assert.equal(queue.hasPending('volume'), false);
  const first = queue.enqueue('volume', { run: () => gate.promise });
  const second = queue.enqueue('volume', { run: () => 2 });
  assert.equal(queue.hasPending('volume'), true);
  gate.resolve(1);
  assert.deepEqual(await Promise.all([first, second]), [1, 2]);
  assert.equal(queue.hasPending('volume'), false);
  assert.equal(queue.states.size, 0, 'Completed keys must not retain idle state.');
  const again = queue.enqueue('volume', { run: () => 3 });
  assert.equal(queue.hasPending('volume'), true);
  assert.equal(await again, 3);
  assert.equal(queue.states.size, 0);
});

test('a superseded promise settles to null before the active command completes', async () => {
  const queue = new core.ControlCommandQueue();
  const gate = deferred();
  const sent = [];
  const active = queue.enqueue('volume', { coalesceKey: 'absolute', run: () => gate.promise });
  const superseded = queue.enqueue('volume', {
    coalesceKey: 'absolute',
    run: () => sent.push('superseded'),
  });
  let settled = false, result;
  superseded.then((value) => { settled = true; result = value; });
  const latest = queue.enqueue('volume', {
    coalesceKey: 'absolute',
    run: () => { sent.push('latest'); return 90; },
  });
  await flush();
  assert.equal(settled, true);
  assert.equal(result, null);
  assert.deepEqual(sent, []);
  assert.equal(queue.hasPending('volume'), true);
  gate.resolve(10);
  assert.deepEqual(await Promise.all([active, superseded, latest]), [10, null, 90]);
  assert.deepEqual(sent, ['latest']);
});

test('an initially stale command resolves null without running or blocking the key', async () => {
  const queue = new core.ControlCommandQueue();
  const sent = [];
  const stale = queue.enqueue('volume', {
    current: () => false,
    run: () => sent.push('stale'),
  });
  assert.equal(await stale, null);
  assert.deepEqual(sent, []);
  assert.equal(queue.hasPending('volume'), false);
  assert.equal(await queue.enqueue('volume', { run: () => { sent.push('fresh'); return 5; } }), 5);
  assert.deepEqual(sent, ['fresh']);
});

test('one command dispatches immediately and returns the original run result', async () => {
  const queue = new core.ControlCommandQueue();
  const gate = deferred();
  const result = { applied: true };
  let checks = 0, runs = 0;
  const pending = queue.enqueue('volume', {
    current: () => { checks++; return true; },
    run: () => { runs++; return gate.promise; },
  });
  assert.equal(checks, 1);
  assert.equal(runs, 1, 'The first dispatch must not wait for a later turn.');
  assert.equal(queue.hasPending('volume'), true);
  gate.resolve(result);
  assert.equal(await pending, result);
  assert.equal(runs, 1);
  assert.equal(queue.hasPending('volume'), false);
});
