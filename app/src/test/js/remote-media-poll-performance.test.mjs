/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */
import test from 'node:test';
import assert from 'node:assert/strict';
import { browserHarness } from './remote-browser-harness.mjs';

async function playback() {
  const fixture = { fail: false, np: {
    active: true, playing: true, title: 'Fixture track', artist: 'Fixture artist',
    album: 'Fixture album', durationMs: 180000, positionMs: 10000, canSeek: true,
    hasArt: false, volume: { level: 4, max: 15, muted: false, canSet: true },
  } };
  const h = await browserHarness({
    saved: [
      { base: 'http://127.0.0.1:8877', name: 'A', token: 'fixture-a' },
      { base: 'http://127.0.0.1:8878', name: 'B', token: 'fixture-b' },
    ],
    routes: {
      '/remote/nowplaying': ({ response }) => {
        if (fixture.fail) throw new Error('fixture offline');
        return response({ ok: true, np: fixture.np });
      },
      '/remote/media': ({ response }) => response({ ok: true }),
      '/remote/volume': ({ response }) => response({ ok: true }),
    },
  });
  const mount = () => h.document.getElementById('media-panel');
  const control = (label) => mount().querySelector(`[aria-label="${label}"]`);
  return { h, fixture, mount, control };
}

for (const tab of ['home', 'remote']) {
  test(`${tab}: five unchanged playback polls create no controls or listeners and keep focus`, async (t) => {
    const { h, mount, control } = await playback();
    if (tab === 'remote') { h.eval('go("remote")'); await h.settle(); }
    const group = mount().firstChild, pause = control('Pause');
    pause.focus();
    const work = { elements: 0, listeners: 0, replacements: 0 };
    const create = h.document.createElement.bind(h.document);
    h.document.createElement = (tag) => {
      work.elements++;
      const node = create(tag), listen = node.addEventListener.bind(node);
      node.addEventListener = (...args) => { work.listeners++; return listen(...args); };
      return node;
    };
    const replace = mount().replaceChildren.bind(mount());
    mount().replaceChildren = (...nodes) => { work.replacements++; return replace(...nodes); };
    const before = h.calls.filter(c => c.path === '/remote/nowplaying').length;
    for (let i = 0; i < 5; i++) await h.tick(4000);
    t.diagnostic(JSON.stringify({ tab, ...work }));
    assert.equal(h.calls.filter(c => c.path === '/remote/nowplaying').length - before, 5);
    assert.deepEqual(work, { elements: 0, listeners: 0, replacements: 0 });
    assert.equal(mount().firstChild === group, true);
    assert.equal(control('Pause') === pause, true);
    assert.equal(pause.isConnected, true);
    assert.equal(h.document.activeElement === pause, true);
    h.hide();
  });
}

test('position, track, transport and volume update without replacing controls', async () => {
  const { h, fixture, mount, control } = await playback();
  const group = mount().firstChild, pause = control('Pause'), seek = control('Playback position');
  const volume = control('Portal music volume');
  pause.focus();
  Object.assign(fixture.np, { title: 'Next fixture track', playing: false, positionMs: 34000, durationMs: 240000, canSeek: false });
  Object.assign(fixture.np.volume, { level: 7, max: 20, muted: true, canSet: false });
  await h.tick(4000);
  assert.equal(mount().firstChild === group, true);
  assert.equal(control('Play') === pause, true);
  assert.equal(h.document.activeElement === pause, true);
  assert.equal(control('Playback position') === seek, true);
  assert.equal(Number(seek.value), 34000);
  assert.equal(Number(seek.getAttribute('max')), 240000);
  assert.equal(seek.disabled, true);
  assert.equal(control('Portal music volume') === volume, true);
  assert.equal(Number(volume.value), 7);
  assert.equal(Number(volume.getAttribute('max')), 20);
  assert.equal(volume.disabled, true);
  assert.match(mount().textContent, /Next fixture track/);
  assert.match(mount().textContent, /0:34/);
  assert.match(mount().textContent, /7 \/ 20/);
  assert.match(mount().textContent, /Unmute/);
  pause.click(); await h.settle();
  const commands = h.calls.filter(c => c.path === '/remote/media');
  assert.equal(commands.length, 1);
  assert.equal(JSON.parse(commands[0].opts.body).action, 'playpause');
  h.hide();
});

test('dragged volume keeps its draft while playback position continues updating', async () => {
  const { h, fixture, mount, control } = await playback();
  const volume = control('Portal music volume'), seek = control('Playback position');
  volume.focus(); volume.value = 11; volume.oninput();
  fixture.np.positionMs = 42000;
  fixture.np.volume.level = 6;
  await h.tick(4000);
  assert.equal(control('Portal music volume') === volume, true);
  assert.equal(Number(volume.value), 11);
  assert.match(mount().textContent, /11 \/ 15/);
  assert.equal(Number(seek.value), 42000);
  h.document.activeElement = null;
  await h.tick(4000);
  assert.equal(Number(volume.value), 6);
  assert.match(mount().textContent, /6 \/ 15/);
  h.hide();
});

test('first playback failure is shown and the first successful response clears it', async () => {
  const { h, fixture, mount, control } = await playback();
  const pause = control('Pause');
  fixture.fail = true;
  await h.tick(4000);
  assert.match(mount().textContent, /reach|connect/i);
  assert.equal(control('Pause') === pause, true);
  fixture.fail = false;
  await h.tick(4000);
  assert.equal(mount().querySelector('.media-error').hidden, true);
  assert.equal(control('Pause') === pause, true);
  h.hide();
});

test('inactive and active transitions rebuild only the necessary playback structure', async () => {
  const { h, fixture, mount, control } = await playback();
  fixture.np.active = false;
  await h.tick(4000);
  assert.equal(control('Pause'), null);
  assert.match(mount().textContent, /Nothing playing/);
  const inactive = mount().firstChild;
  await h.tick(4000);
  assert.equal(mount().firstChild === inactive, true);
  fixture.np.active = true;
  await h.tick(4000);
  assert.ok(control('Pause'));
  h.hide();
});

test('room changes replace playback controls and commands use only the selected room', async () => {
  const { h, control } = await playback();
  const oldPause = control('Pause');
  h.eval('selectRoom(roster[1].base)'); await h.settle();
  assert.notEqual(control('Pause'), oldPause);
  control('Pause').click(); await h.settle();
  const command = h.calls.filter(c => c.path === '/remote/media').at(-1);
  assert.equal(new URL(command.url).port, '8878');
  h.hide();
});

test('artwork stays mounted across polls; obsolete artwork cannot replace a new version', async () => {
  const fixture = { version: 1, waiting: [] };
  const h = await browserHarness({ saved: [{ base: 'http://127.0.0.1:8877', name: 'Art', token: 'fixture-art' }], routes: {
    '/remote/nowplaying': ({ response }) => response({ ok: true, np: {
      active: true, playing: true, hasArt: true, artVersion: fixture.version,
      title: 'Artwork fixture', volume: { level: 4, max: 15, canSet: true },
    } }),
    '/remote/art': ({ opts }) => new Promise(resolve => fixture.waiting.push({
      signal: opts.signal, finish: () => resolve(new Response(new Uint8Array([0]), {
        headers: { 'Content-Type': 'image/png' },
      })),
    })),
  } });
  const mount = h.document.getElementById('media-panel'), art = mount.querySelector('.art');
  assert.equal(fixture.waiting.length, 1);
  await h.tick(4000);
  assert.equal(mount.querySelector('.art') === art, true);
  assert.equal(fixture.waiting.length, 1);
  fixture.version = 2;
  await h.tick(4000);
  assert.equal(fixture.waiting[0].signal.aborted, true);
  fixture.waiting[0].finish(); await h.settle();
  assert.equal(art.src, '');
  assert.equal(fixture.waiting.length, 2);
  fixture.waiting[1].finish(); await h.settle();
  assert.match(art.src, /^blob:/);
  assert.equal(mount.querySelector('.media-fallback').tagName, 'SPAN');
  assert.equal(mount.querySelector('.media-fallback').hidden, true);
  const version2 = art.src;
  await h.tick(4000);
  assert.equal(art.src, version2);
  assert.equal(fixture.waiting.length, 2);
  h.hide();
});

test('a shrinking maximum preserves the focused draft and clamps its submitted command', async () => {
  const { h, fixture, control } = await playback();
  const volume = control('Portal music volume');
  volume.focus(); volume.value = 11; volume.oninput();
  fixture.np.volume.max = 5;
  fixture.np.volume.level = 3;
  await h.tick(4000);
  assert.equal(Number(volume.getAttribute('max')), 15);
  assert.equal(Number(volume.value), 11);
  volume.onchange(); await h.settle();
  assert.equal(JSON.parse(h.calls.filter(c => c.path === '/remote/volume').at(-1).opts.body).level, 5);
  h.document.activeElement = null;
  await h.tick(4000);
  assert.equal(Number(volume.getAttribute('max')), 5);
  assert.equal(Number(volume.value), 3);
  h.hide();
});

test('artwork retries the same version after a failed load', async () => {
  const fixture = { fail: true, attempts: 0 };
  const h = await browserHarness({ saved: [{ base: 'http://127.0.0.1:8877', name: 'Art', token: 'fixture-art' }], routes: {
    '/remote/nowplaying': ({ response }) => response({ ok: true, np: { active: true, playing: true, hasArt: true, artVersion: 1 } }),
    '/remote/art': () => {
      fixture.attempts++;
      if (fixture.fail) throw new Error('fixture artwork failure');
      return new Response(new Uint8Array([0]), { headers: { 'Content-Type': 'image/png' } });
    },
  } });
  assert.equal(fixture.attempts, 1);
  fixture.fail = false;
  await h.tick(4000);
  assert.equal(fixture.attempts, 2);
  const art = h.document.getElementById('media-panel').querySelector('.art');
  assert.equal(art.hidden, false);
  assert.match(art.src, /^blob:/);
  await h.tick(4000);
  assert.equal(fixture.attempts, 2);
  h.hide();
});


test('fractional-second positions align to the native seek step without repeating value writes', async () => {
  const { h, fixture, control } = await playback();
  const seek = control('Playback position');
  fixture.np.positionMs = 10345;
  let value = seek.value, writes = 0;
  Object.defineProperty(seek, 'value', {
    get: () => value, set: next => { writes++; value = next; }, configurable: true,
  });
  for (let i = 0; i < 3; i++) await h.tick(4000);
  assert.equal(Number(seek.value), 10000);
  assert.equal(writes, 0);
  fixture.np.positionMs = 11600;
  await h.tick(4000);
  assert.equal(Number(seek.value), 12000);
  assert.equal(writes, 1);
  h.hide();
});
