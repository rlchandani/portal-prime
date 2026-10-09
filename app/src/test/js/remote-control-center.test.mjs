/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */
import test from 'node:test';
import assert from 'node:assert/strict';
import * as core from '../../main/assets/remote/core.mjs';
const rooms = [
  { name: 'Study', base: 'http://192.168.1.2:8723', token: 'session-a' },
  { name: 'Kitchen', base: 'http://192.168.1.3:8723', token: 'session-b' },
];
const deferred = () => {
  let resolve;
  const promise = new Promise((r) => {
    resolve = r;
  });
  return { promise, resolve };
};

test('legacy roster migrates index selection, deduplicates endpoints, and retains the locally renewed session', () => {
  const roster = core.mergeRoster(
    [...rooms, { ...rooms[0], base: rooms[0].base + '/' }],
    [
      { ...rooms[0], token: 'old' },
      { name: 'TV', base: 'http://192.168.1.4:8723', token: 'c' },
    ],
  );
  assert.equal(roster.length, 3);
  assert.equal(roster[0].token, 'session-a');
  assert.equal(core.activeBase(roster, '1'), rooms[1].base);
  assert.equal(core.normalizeBase('192.168.1.2'), rooms[0].base);
  assert.throws(() => core.normalizeBase('http://user:secret@192.168.1.2'));
  assert.throws(() => core.normalizeBase('javascript:alert(1)'));
});

test('group targets capture sessions by endpoint before roster reorder or active-device changes', () => {
  const session = new core.Session();
  session.select(rooms[0]);
  const captured = core.captureTargets(rooms, 'selected', rooms[0].base, new Set([rooms[1].base]));
  session.select(rooms[1]);
  rooms.reverse();
  assert.equal(captured[0].name, 'Kitchen');
  assert.equal(captured[0].token, 'session-b');
  assert.equal(Object.isFrozen(captured[0]), true);
  rooms.reverse();
});

test('page and request generations discard both late page reads and superseded refreshes', () => {
  const gate = new core.Session();
  gate.select(rooms[0]);
  const home = gate.enter('home');
  const first = gate.read('media');
  const later = gate.read('media');
  assert.equal(gate.current(first), false);
  assert.equal(gate.current(later), true);
  gate.enter('photos');
  assert.equal(gate.current(home), false);
  assert.equal(gate.current(later), false);
  gate.select(rooms[1]);
  assert.equal(gate.current(gate.capture(rooms[0])), false);
});

test('late thumbnail completions are revoked and never delivered to another room', async () => {
  const pending = deferred();
  const revoked = [];
  const received = [];
  const pool = new core.ImagePool({ create: () => 'blob:old', revoke: (u) => revoked.push(u) });
  const result = pool.add(
    'old',
    () => pending.promise,
    (u) => received.push(u),
  );
  pool.clear();
  pending.resolve(new Blob(['image']));
  await result;
  assert.deepEqual(received, []);
  assert.deepEqual(revoked, ['blob:old']);
});

test('thumbnail scheduler enforces 24 entries per page and four concurrent loads', async () => {
  let live = 0,
    max = 0;
  const waiting = [];
  const pool = new core.ImagePool({ create: () => `blob:${waiting.length}`, revoke: () => {} });
  const jobs = Array.from({ length: 30 }, (_, i) =>
    pool.add(
      String(i),
      async () => {
        live++;
        max = Math.max(max, live);
        const d = deferred();
        waiting.push(d);
        await d.promise;
        live--;
        return new Blob(['x']);
      },
      () => {},
    ),
  );
  assert.equal(waiting.length, 4);
  assert.equal(pool.size, 24);
  for (let i = 0; i < 24; i++) {
    while (!waiting[i]) await new Promise((r) => setImmediate(r));
    waiting[i].resolve();
  }
  await Promise.all(jobs);
  assert.equal(max, 4);
  pool.clear();
});

test('settings report partial applied keys and value mismatches separately for each room', async () => {
  const out = await core.applySettings(
    rooms,
    'screensaver',
    { shuffle: true, intervalSec: 45 },
    async (room) =>
      room.name === 'Study'
        ? {
            ok: true,
            applied: ['shuffle', 'intervalSec'],
            domain: {
              controls: [
                { key: 'shuffle', value: true },
                { key: 'intervalSec', value: 45 },
              ],
            },
          }
        : {
            ok: true,
            applied: ['shuffle'],
            domain: {
              controls: [
                { key: 'shuffle', value: true },
                { key: 'intervalSec', value: 30 },
              ],
            },
          },
  );
  assert.equal(out[0].ok, true);
  assert.equal(out[1].ok, false);
  assert.deepEqual(out[1].rejected, ['intervalSec']);
});

test('a room returning 401 requests pairing again without cancelling successful peers', async () => {
  const expired = [];
  const client = core.createClient({
    fetch: async (url) =>
      new Response(
        JSON.stringify(
          url.includes('.2:')
            ? { ok: false, error: 'unauthorized' }
            : {
                ok: true,
                applied: ['shuffle'],
                domain: { controls: [{ key: 'shuffle', value: true }] },
              },
        ),
        { status: url.includes('.2:') ? 401 : 200 },
      ),
    onUnauthorized: (r) => expired.push(r.base),
  });
  const result = await core.applySettings(rooms, 'screensaver', { shuffle: true }, (r, d, v) =>
    client(r, '/remote/settings', { body: { domain: d, values: v } }),
  );
  assert.equal(result[0].ok, false);
  assert.equal(result[1].ok, true);
  assert.deepEqual(expired, [rooms[0].base]);
});

test('HTTP transport preserves native Blob bodies and sends session only in Authorization', async () => {
  const file = new Blob(['photo']);
  let seen;
  const client = core.createClient({
    fetch: async (url, init) => {
      seen = { url, init };
      return new Response('{"ok":true}');
    },
  });
  await client(rooms[0], '/remote/photo-upload?path=%2Fsdcard%2FPictures&name=a.png', {
    body: file,
  });
  assert.equal(seen.init.body, file);
  assert.equal(seen.init.headers.Authorization, 'Bearer session-a');
  assert.equal(seen.url.includes('session-a'), false);
});

test('timeout aborts an unresponsive read and surfaces a retryable error', async () => {
  let aborted = false;
  const client = core.createClient({
    fetch: (_url, opts) =>
      new Promise((_, reject) =>
        opts.signal.addEventListener('abort', () => {
          aborted = true;
          reject(new DOMException('Aborted', 'AbortError'));
        }),
      ),
  });
  await assert.rejects(
    () => client(rooms[0], '/remote/companion', { timeout: 10 }),
    (e) => e.code === 'timeout',
  );
  assert.equal(aborted, true);
});

test('operation presentation never equates dispatch or mixed scene results with rendered completion', () => {
  assert.equal(core.operationView({ state: 'running', results: [] }).label, 'Running');
  const dispatched = core.operationView({
    state: 'succeeded',
    results: [{ ok: true, result: 'dispatched' }],
  });
  assert.match(dispatched.detail, /dispatch/i);
  assert.equal(
    core.operationView({
      state: 'succeeded',
      results: [{ ok: true }, { ok: false, result: 'no_accessibility' }],
    }).tone,
    'error',
  );
  assert.match(
    core.operationView({ state: 'failed', installUncertain: true, results: [] }).detail,
    /continue/i,
  );
});

test('photo pagination bounds loads, normalizes Android aliases, and retains shallow truncation', () => {
  const files = Array.from({ length: 53 }, (_, i) => ({ path: `/sdcard/Pictures/${i}.jpg` }));
  assert.equal(core.photoPage(files, 0).length, 24);
  assert.equal(core.photoPage(files, 2).length, 5);
  assert.equal(core.photoFolder('/storage/emulated/0/Pictures'), '/sdcard/Pictures');
  assert.throws(() => core.photoFolder('/sdcard/Android/data'));
  assert.throws(() => core.photoFolder('/sdcard/../secret'));
  const listing = core.photoListing({ path: '/sdcard', files, truncated: true }, true);
  assert.equal(listing.all, false);
  assert.equal(listing.truncated, true);
});

test('widget edits use stable identities and preserve bound widgets, holes, assignments and app ordering', () => {
  const layout = {
    assignments: { 'com.example.app': 'Daily' },
    homeOrder: ['com.example.app/.Home'],
    gridOrder: [
      'builtin:tools',
      'widget-tile:custom:weather:a',
      'app:com.example.app',
      'widget-tile:widget:12',
    ],
    gridSlots: [
      null,
      'widget-tile:custom:weather:a',
      'app:com.example.app',
      'widget-tile:widget:12',
    ],
    widgets: [
      { kind: 'weather', customId: 'weather:a', spanX: 2, spanY: 2 },
      {
        kind: 'appwidget',
        id: 12,
        package: 'com.example.app',
        class: 'Provider',
        spanX: 2,
        spanY: 2,
      },
    ],
  };
  const moved = core.moveWidget(layout, 'widget-tile:widget:12', -1);
  assert.equal(moved.widgets[0].id, 12);
  assert.deepEqual(moved.gridOrder, [
    'builtin:tools',
    'widget-tile:widget:12',
    'app:com.example.app',
    'widget-tile:custom:weather:a',
  ]);
  assert.equal(moved.gridSlots[0], null);
  assert.deepEqual(moved.assignments, layout.assignments);
  assert.equal(core.resizeWidget(moved, 'widget-tile:custom:weather:a', 4, 1).widgets[1].spanX, 4);
  assert.throws(() => core.resizeWidget(moved, 'widget-tile:custom:weather:a', 5, 1));
});

test('profiles keep ordinary legacy values reachable but never persist credentials or credential-bearing URLs', () => {
  const schema = {
    domains: [
      {
        id: 'x',
        controls: [
          { key: 'enabled', type: 'bool', value: true },
          { key: 'apiKey', type: 'string', secret: true, value: '' },
          { key: 'url', type: 'string', value: 'https://example.org/feed?token=secret' },
          { key: 'title', type: 'string', value: 'Hello' },
        ],
      },
    ],
  };
  assert.deepEqual(core.captureProfile(schema), { x: { enabled: true, title: 'Hello' } });
});

test('photo folder identities preserve discovered and stored leading/trailing folder whitespace', () => {
  assert.equal(core.photoFolder('/sdcard/Pictures/Family '), '/sdcard/Pictures/Family ');
  assert.equal(core.photoFolder('/storage/emulated/0/ Photos '), '/sdcard/ Photos ');
});

test('active-only registry policy matches native names, credentials, connection keys and metadata', () => {
  for (const [domain, key, control] of [
    ['mqtt', 'host', { type: 'string' }],
    ['mqtt', 'username', { type: 'string' }],
    ['mqtt', 'password', { type: 'string', secret: true }],
    ['immortal', 'maPassword', { type: 'string', secret: true }],
    ['other', 'roomName', { type: 'string' }],
    ['other', 'endpoint', { type: 'string' }],
    ['other', 'custom', { type: 'string', entry: 'url' }],
    ['other', 'custom', { type: 'string', entry: 'password' }],
    ['other', 'custom', { type: 'string', secret: true }],
    ['screensaver', 'folderPath', { type: 'string' }],
  ])
    assert.equal(core.activeOnlySetting(domain, key, control), true, `${domain}/${key}`);
  assert.equal(core.activeOnlySetting('mqtt', 'enabled', { type: 'bool' }), false);
  assert.equal(core.activeOnlySetting('immortal', 'weatherUnit', { type: 'enum' }), false);
});

test('shared settings FIFO captures sessions and values for every endpoint before queuing and caps total concurrency at four', async () => {
  const targets = Array.from({ length: 6 }, (_, i) => ({
    base: `http://192.168.50.${i + 1}:8723`,
    name: `Room ${i}`,
    token: `captured-${i}`,
  }));
  const releases = targets.map(deferred);
  const seen = new Map(targets.map((r) => [r.base, []]));
  let live = 0,
    maxLive = 0;
  const send = async (room, domain, values) => {
    live++;
    maxLive = Math.max(maxLive, live);
    seen.get(room.base).push({ value: values.intervalSec, token: room.token });
    if (values.intervalSec === 45)
      await releases[Number(new URL(room.base).hostname.split('.').at(-1)) - 1].promise;
    await new Promise((resolve) => setImmediate(resolve));
    live--;
    return {
      ok: true,
      applied: ['intervalSec'],
      domain: { controls: [{ key: 'intervalSec', value: values.intervalSec }] },
    };
  };
  const firstValues = { intervalSec: 45 },
    secondValues = { intervalSec: 60 };
  const first = core.applySettings(targets, 'screensaver', firstValues, send);
  const second = core.applySettings(targets, 'screensaver', secondValues, send);
  firstValues.intervalSec = 99;
  secondValues.intervalSec = 99;
  for (const target of targets) target.token = 'changed-after-queue';
  for (const d of releases) d.resolve();
  await Promise.all([first, second]);
  assert.ok(maxLive <= 4, `shared requests reached ${maxLive}`);
  for (const [i, target] of targets.entries())
    assert.deepEqual(seen.get(target.base), [
      { value: 45, token: `captured-${i}` },
      { value: 60, token: `captured-${i}` },
    ]);
});
