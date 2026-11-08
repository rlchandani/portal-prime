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
  { base: 'http://127.0.0.1:8877', name: 'Room A', token: 'synthetic-a' },
  { base: 'http://127.0.0.1:8878', name: 'Room B', token: 'synthetic-b' },
];
const companion = (name, model = 'Synthetic Portal') => ({
  ok: true, protocolVersion: 1, deviceId: name, name, model,
  capabilities: { layout: true, operations: true, scenes: true, photos: true },
  app: { versionName: 'fixture' },
});
const connection = h => h.document.getElementById('connection').textContent;
const page = h => h.document.getElementById('page');
const statusCalls = h => h.calls.filter(call => call.path === '/remote/companion');

// Hold the transport boundary, while running the shipped controller and client.
// Ignoring abort models a response already in flight when cancellation happens.
async function fixture({ ignoreAbort = false, routes = {} } = {}) {
  const pending = [];
  const h = await browserHarness({ saved, routes: {
    ...routes,
    '/remote/companion': ({ url, opts, response }) => new Promise((resolve, reject) => {
      const request = {
        base: new URL(url).origin,
        signal: opts.signal,
        succeed: (name, model) => resolve(response(companion(name, model))),
        fail: () => reject(new TypeError('Synthetic network failure')),
        reply: (body, status) => resolve(response(body, status)),
      };
      pending.push(request);
      if (!ignoreAbort)
        opts.signal.addEventListener('abort', () => reject(new DOMException('Cancelled', 'AbortError')),
          { once: true });
    }),
  } });
  assert.equal(pending.length, 1, 'Startup must really be waiting on its companion GET.');
  assert.match(connection(h), /Connected/);
  const dispose = async () => {
    h.hide();
    for (const request of pending) request.succeed('Discarded fixture');
    await h.settle();
  };
  return { h, pending, dispose };
}

function selectB(h) {
  h.document.getElementById('room-button').click();
  h.click('Select');
}

test('navigating to Settings cancels the initial GET without displaying Offline over usable settings', async () => {
  const { h, pending, dispose } = await fixture();
  try {
    h.click('Settings');
    await h.settle();
    assert.equal(pending[0].signal.aborted, true);
    assert.equal(h.calls.filter(call => call.path === '/remote/settings').length, 1);
    assert.equal(h.document.getElementById('navigation').children[4].getAttribute('aria-current'), 'page');
    assert.doesNotMatch(page(h).textContent, /Pair again/);
    assert.doesNotMatch(connection(h), /Offline|Retry/,
      'Cancellation is not a failed reachability check for the newly visible Settings page.');
  } finally { await dispose(); }
});

for (const destination of ['Settings', 'Apps']) {
  test(`navigation to ${destination} immediately replaces a cancelled initial companion GET`, async () => {
    const { h, pending, dispose } = await fixture();
    try {
      h.click(destination);
      await h.settle();
      assert.equal(pending[0].signal.aborted, true);
      assert.equal(statusCalls(h).length, 2,
        'The successor page must request status before any 4-second or 30-second poll fires.');
      assert.equal(pending[1].base, saved[0].base);
      pending[1].succeed('Room A', 'Fresh status');
      await h.settle();
      assert.match(connection(h), /Connected.*Fresh status/);
      assert.doesNotMatch(connection(h), /Offline|Retry/);
    } finally { await dispose(); }
  });
}

for (const outcome of ['success', 'network failure', '401']) {
  test(`cancelled initial success cannot overwrite the successor Settings status ${outcome}`, async () => {
    const { h, pending, dispose } = await fixture({ ignoreAbort: true });
    try {
      h.click('Settings');
      await h.settle();
      assert.equal(pending[0].signal.aborted, true);
      assert.equal(statusCalls(h).length, 2, 'Navigation must issue the successor GET immediately.');
      if (outcome === 'success') pending[1].succeed('Room A', 'Successor model');
      else if (outcome === '401') pending[1].reply({ ok: false, error: 'unauthorized' }, 401);
      else pending[1].fail();
      await h.settle();
      const before = connection(h);
      if (outcome === 'success') assert.match(before, /Connected.*Successor model/);
      else if (outcome === '401') assert.match(before, /Pair again/);
      else assert.match(before, /Offline.*Retry/);
      pending[0].succeed('Stale initial room', 'Stale initial model');
      await h.settle();
      assert.equal(connection(h), before);
      assert.doesNotMatch(h.document.body.textContent, /Stale initial/);
      assert.equal(h.calls.filter(call => call.path === '/remote/settings').length, 1);
    } finally { await dispose(); }
  });
}

test('changing rooms while A is pending keeps B Connecting while its status and media are pending', async () => {
  let finishMedia;
  const { h, pending, dispose } = await fixture({ routes: {
    '/remote/nowplaying': ({ url, response }) => url.startsWith(saved[1].base + '/')
      ? new Promise(resolve => {
        finishMedia = () => resolve(response({ ok: true, np: { active: false } }));
      }) : response({ ok: true, np: { active: false } }),
  } });
  try {
    selectB(h);
    await h.settle();
    assert.equal(pending[0].signal.aborted, true);
    assert.equal(statusCalls(h).length, 2);
    assert.equal(pending[1].base, saved[1].base);
    assert.match(h.document.getElementById('room-button').textContent, /Room B/);
    assert.equal(typeof finishMedia, 'function');
    assert.match(connection(h), /Connecting/,
      'A cancelled status continuation must not declare B unreachable before its responses arrive.');
    pending[1].succeed('Room B', 'B model');
    await h.settle();
    assert.match(connection(h), /Connected.*B model/);
  } finally { finishMedia?.(); await dispose(); }
});

for (const outcome of ['success', 'network failure', '401']) {
  test(`late A success cannot overwrite B ${outcome} after changing rooms`, async () => {
    const { h, pending, dispose } = await fixture({ ignoreAbort: true });
    try {
      selectB(h);
      await h.settle();
      assert.equal(pending[0].signal.aborted, true);
      assert.equal(statusCalls(h).length, 2);
      if (outcome === 'success') pending[1].succeed('Room B', 'B model');
      else if (outcome === '401') pending[1].reply({ ok: false, error: 'unauthorized' }, 401);
      else pending[1].fail();
      await h.settle();
      const before = connection(h);
      if (outcome === 'success') assert.match(before, /Connected.*B model/);
      else if (outcome === '401') {
        assert.match(before, /Pair again/);
        assert.match(page(h).textContent, /revoked or expired/);
      } else assert.match(before, /Offline.*Retry/);
      pending[0].succeed('Stale A', 'A model');
      await h.settle();
      assert.equal(connection(h), before);
      assert.match(h.document.getElementById('room-button').textContent, /Room B/);
      assert.doesNotMatch(h.document.body.textContent, /Stale A|A model/);
    } finally { await dispose(); }
  });
}

for (const staleOutcome of ['network failure', '401']) {
  test(`late A ${staleOutcome} cannot change B connected status or pairing`, async () => {
    const { h, pending, dispose } = await fixture({ ignoreAbort: true });
    try {
      selectB(h);
      await h.settle();
      assert.equal(statusCalls(h).length, 2);
      pending[1].succeed('Room B', 'B model');
      await h.settle();
      const before = connection(h);
      assert.match(before, /Connected.*B model/);
      if (staleOutcome === '401') pending[0].reply({ ok: false, error: 'unauthorized' }, 401);
      else pending[0].fail();
      await h.settle();
      assert.equal(connection(h), before);
      assert.match(h.document.getElementById('room-button').textContent, /Room B/);
      assert.doesNotMatch(page(h).textContent, /Pair again/);
      h.click('Settings');
      await h.settle();
      assert.equal(h.calls.at(-1).url, saved[1].base + '/remote/settings');
    } finally { await dispose(); }
  });
}

test('Refresh cancels the initial GET without marking its pending replacement Offline', async () => {
  let holdMedia = false, finishMedia;
  const { h, pending, dispose } = await fixture({ routes: {
    '/remote/nowplaying': ({ response }) => holdMedia
      ? new Promise(resolve => {
        finishMedia = () => resolve(response({ ok: true, np: { active: false } }));
      }) : response({ ok: true, np: { active: false } }),
  } });
  try {
    holdMedia = true;
    h.document.getElementById('refresh-button').click();
    await h.settle();
    assert.equal(pending[0].signal.aborted, true);
    assert.equal(statusCalls(h).length, 2);
    assert.doesNotMatch(connection(h), /Offline|Retry/);
    pending[1].succeed('Room A', 'Refreshed model');
    await h.settle();
    assert.match(connection(h), /Connected.*Refreshed model/);
  } finally { finishMedia?.(); await dispose(); }
});

test('hiding cancels initial status without converting cancellation into an Offline banner', async () => {
  const { h, pending, dispose } = await fixture();
  try {
    const before = connection(h), count = h.calls.length;
    h.hide();
    await h.settle();
    assert.equal(pending[0].signal.aborted, true);
    assert.equal(h.calls.length, count, 'Hidden pages must not start replacement requests.');
    assert.equal(connection(h), before);
    await h.tick(4000);
    await h.tick(30000);
    assert.equal(h.calls.length, count);
  } finally { await dispose(); }
});

test('showing after a cancelled initial GET requests fresh status immediately and rejects late hidden status', async () => {
  const { h, pending, dispose } = await fixture({ ignoreAbort: true });
  try {
    h.hide();
    await h.settle();
    h.show();
    await h.settle();
    assert.equal(pending[0].signal.aborted, true);
    assert.equal(statusCalls(h).length, 2,
      'Visibility resume must not wait for the polling interval to replace unknown companion status.');
    pending[1].succeed('Room A', 'Resumed model');
    await h.settle();
    const before = connection(h);
    assert.match(before, /Connected.*Resumed model/);
    pending[0].succeed('Stale hidden room', 'Stale hidden model');
    await h.settle();
    assert.equal(connection(h), before);
    assert.doesNotMatch(h.document.body.textContent, /Stale hidden/);
  } finally { await dispose(); }
});

test('a genuine initial network failure shows Offline and Retry makes a fresh request immediately', async () => {
  const { h, pending, dispose } = await fixture();
  try {
    pending[0].fail();
    await h.settle();
    assert.match(connection(h), /Offline.*Retry/);
    assert.doesNotMatch(page(h).textContent, /Pair again/);
    h.click('Retry');
    await h.settle();
    assert.equal(statusCalls(h).length, 2);
    pending[1].succeed('Room A', 'Recovered model');
    await h.settle();
    assert.match(connection(h), /Connected.*Recovered model/);
    assert.doesNotMatch(connection(h), /Offline|Retry/);
  } finally { await dispose(); }
});

test('a genuine initial 401 keeps Pair again across navigation and hide/show without automatic requests', async () => {
  const { h, pending, dispose } = await fixture();
  try {
    pending[0].reply({ ok: false, error: 'unauthorized' }, 401);
    await h.settle();
    assert.match(connection(h), /Pair again/);
    assert.match(page(h).textContent, /revoked or expired/);
    assert.doesNotMatch(connection(h), /Offline|Retry/);
    const count = h.calls.length;
    h.click('Settings');
    h.hide();
    h.show();
    await h.settle();
    await h.tick(4000);
    await h.tick(30000);
    assert.equal(h.calls.length, count);
    assert.match(connection(h), /Pair again/);
    assert.match(page(h).textContent, /revoked or expired/);
  } finally { await dispose(); }
});

test('initial unavailable companion storage still shows Offline with preservation guidance', async () => {
  const { h, pending, dispose } = await fixture();
  try {
    pending[0].reply({ ok: false, error: 'companion_storage_unavailable' }, 503);
    await h.settle();
    assert.match(connection(h), /Offline.*Retry/);
    assert.match(page(h).textContent, /Management metadata unavailable/);
    assert.match(page(h).textContent, /kept unchanged/i);
    assert.doesNotMatch(page(h).textContent, /Pair again/);
    assert.equal(statusCalls(h).length, 1);
  } finally { await dispose(); }
});

test('initial unsupported companion endpoint keeps working controls and update guidance', async () => {
  const { h, pending, dispose } = await fixture();
  try {
    pending[0].reply({ ok: false, error: 'not_found' }, 404);
    await h.settle();
    assert.match(connection(h), /Connected/);
    assert.doesNotMatch(connection(h), /Offline|Retry|Pair again/);
    assert.match(page(h).textContent, /Update Calder on this Portal/);
    assert.equal(statusCalls(h).length, 1);
  } finally { await dispose(); }
});

for (const failure of ['unavailable', 'malformed']) {
  test(`${failure} settings schema retains its own error and Retry settings on a connected room`, async () => {
    let failing = true;
    const { h, pending, dispose } = await fixture({ routes: {
      '/remote/settings': ({ response }) => !failing
        ? response({ ok: true, settings: { domains: [] } })
        : failure === 'malformed'
          ? response({ ok: true, settings: { domains: 'invalid fixture schema' } })
          : response({ ok: false, error: 'settings_unavailable' }, 503),
    } });
    try {
      pending[0].succeed('Room A', 'A model');
      await h.settle();
      h.click('Settings');
      await h.settle();
      assert.match(connection(h), /Connected.*A model/);
      assert.match(page(h).textContent, /Settings unavailable/);
      assert.doesNotMatch(connection(h), /Offline|Pair again/);
      failing = false;
      h.click('Retry settings');
      await h.settle();
      assert.equal(h.calls.filter(call => call.path === '/remote/settings').length, 2);
      assert.equal(statusCalls(h).length, 1);
      assert.doesNotMatch(page(h).textContent, /Settings unavailable/);
    } finally { await dispose(); }
  });
}

test('unavailable photo source retains local Retry and recovers without expiring a connected pairing', async () => {
  let failing = true;
  const { h, pending, dispose } = await fixture({ routes: {
    '/remote/sources': ({ response }) => failing
      ? response({ ok: false, error: 'sources_unavailable' }, 503)
      : response({ ok: true, sources: { source: 'default' }, calendar: { url: '' } }),
  } });
  try {
    pending[0].succeed('Room A', 'A model');
    await h.settle();
    h.click('Settings');
    await h.settle();
    const sourceButton = page(h).querySelectorAll('button').find(button =>
      button.textContent.includes('Photo sources'));
    assert.ok(sourceButton, 'Settings must offer the photo-source screen.');
    sourceButton.click();
    await h.settle();
    assert.match(page(h).textContent, /Photo sources unavailable/);
    assert.match(connection(h), /Connected.*A model/);
    assert.doesNotMatch(connection(h), /Offline|Pair again/);
    failing = false;
    h.click('Retry');
    await h.settle();
    assert.equal(h.calls.filter(call => call.path === '/remote/sources').length, 2);
    assert.equal(statusCalls(h).length, 1);
    assert.match(page(h).textContent, /Active on Portal: Built-in photos/);
    assert.doesNotMatch(page(h).textContent, /Photo sources unavailable/);
  } finally { await dispose(); }
});
