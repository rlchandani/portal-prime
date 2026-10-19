/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */
import test from 'node:test';
import assert from 'node:assert/strict';
import { browserHarness } from './remote-browser-harness.mjs';

const state = () => ({
  schemaVersion: 1,
  sourceRevision: 7,
  sourceId: 'ma:living',
  queueId: 'queue-shared',
  connection: 'ready',
  playback: 'paused',
  pending: null,
  audioOwnedByOther: false,
  controlPlayerId: 'ma:family',
  track: {
    id: 'track-1', title: '<script>unsafe()</script>', artist: 'Artist', album: 'Album',
    isLive: false, durationMs: 120000,
  },
  positionMs: 1000,
  capabilities: {
    play: true, pause: true, next: true, previous: true, seek: true, queue: true,
    lyrics: false, rooms: true, volume: true, selectPlayer: true,
  },
  queue: [
    { id: 'queue-1', title: 'Queued song', artist: 'Artist', available: true, canSelect: true },
    { id: 'off', title: 'Offline song', artist: '', available: false, canSelect: false },
  ],
  rooms: [
    {
      id: 'ma:family', name: 'Family', available: true, volume: 42, inCurrentGroup: true,
      canSelect: true, canJoin: false, canRemove: false, canSetVolume: true,
    },
    {
      id: 'ma:kitchen', name: 'Kitchen', available: true, volume: null, inCurrentGroup: false,
      canSelect: true, canJoin: true, canRemove: false, canSetVolume: false,
    },
  ],
  lyrics: [],
});

async function fixture(t, options = {}) {
  const f = { music: state(), gets: 0, posts: 0, holdRead: false, holdCommand: false };
  const h = await browserHarness({
    saved: [
      { base: 'http://127.0.0.1:8877', name: 'A', token: 'fixture-a' },
      { base: 'http://127.0.0.1:8878', name: 'B', token: 'fixture-b' },
    ],
    routes: {
      '/remote/music': ({ response, opts }) => {
        f.gets++;
        if (f.holdRead) {
          f.holdRead = false;
          return new Promise(resolve => {
            f.readSignal = opts.signal;
            f.finishRead = () => resolve(response({ ok: true, music: f.music }));
          });
        }
        return response({ ok: true, music: f.music }, options.getStatus || 200);
      },
      '/remote/music/command': ({ response, opts }) => {
        f.posts++;
        if (f.holdCommand) {
          return new Promise(resolve => {
            f.postSignal = opts.signal;
            f.finishPost = () => resolve(response({
              ok: true, admitted: true, confirmed: false, status: 'admitted',
            }, 202));
          });
        }
        if (options.uncertain) {
          return response({
            ok: false, error: 'music_request_timeout', admitted: null, refreshRequired: true,
          }, 503);
        }
        if (options.deny) return response({ ok: false, error: 'stale' }, 409);
        return response({ ok: true, admitted: true, confirmed: false, status: 'admitted',
          ...(options.commandResult ? { music: {
            ...f.music, pending: { requestId: 101, command: 'pause', startedAtElapsedMs: 10 },
          } } : {}) }, 202);
      },
    },
  });
  // The harness advances timer queues explicitly; keep the controller's admission clock in step.
  // Date belongs to this harness's VM, so concurrent test fixtures retain independent clocks.
  h.eval('let musicClock = 100000; Date.now = () => musicClock');
  t.after(() => {
    h.hide();
    f.finishRead?.();
    f.finishPost?.();
  });
  const root = () => h.document.getElementById('sheet-content').firstChild;
  const textClick = h.click;
  h.click = label => {
    const accessible = root()?.querySelector(`[aria-label="${label}"]`);
    if (!accessible || accessible.tagName !== 'BUTTON') return textClick(label);
    accessible.click();
    return accessible;
  };
  const advance = async () => {
    h.eval('musicClock += 1000');
    await h.tick(1000);
  };
  const open = async () => {
    h.click('Queue & players');
    await h.settle();
  };
  const control = label => root().querySelector(`[aria-label="${label}"]`);
  const lastCommand = () => JSON.parse(
    h.calls.filter(call => call.path === '/remote/music/command').at(-1).opts.body,
  );
  return { h, f, root, advance, open, control, lastCommand };
}

test('music reads only on open; metadata is text and unavailable queue rows are disabled', async t => {
  const { h, f, root, open } = await fixture(t);
  assert.equal(f.gets, 0);
  await open();
  assert.equal(f.gets, 1);
  assert.match(root().textContent, /<script>unsafe/);
  assert.equal(root().querySelectorAll('script').length, 0);
  const rows = root().querySelectorAll('.music-queue-row');
  assert.equal(rows.length, 2);
  assert.equal(rows[0].disabled, false);
  assert.equal(rows[1].disabled, true);
  rows[1].click();
  await h.settle();
  assert.equal(f.posts, 0);
});

test('explicit play admission remains pending until a fresh GET without predicting playback', async t => {
  const { h, f, root, open, advance, lastCommand } = await fixture(t);
  await open();
  h.click('Play');
  await h.settle();
  assert.deepEqual(lastCommand(), {
    command: 'play', sourceRevision: 7, sourceId: 'ma:living', trackId: 'track-1',
  });
  const post = h.calls.find(call => call.path === '/remote/music/command');
  assert.equal(post.opts.headers.Authorization, 'Bearer fixture-a');
  assert.equal(f.gets, 1, 'Command admission must respect the shared read-rate limit.');
  assert.match(root().textContent, /pending/i);
  assert.doesNotMatch(root().textContent, /\bconfirmed\b/i);
  assert.equal(h.eval('musicSheet.state.playback'), 'paused');
  h.click('Play');
  await h.settle();
  assert.equal(f.posts, 1, 'Pending admission must block another mutation.');
  f.music.pending = { command: 'play', startedAtElapsedMs: 10 };
  await advance();
  assert.match(root().textContent, /pending/i);
  f.music.pending = null;
  await advance();
  assert.equal(f.gets, 3);
  assert.doesNotMatch(root().textContent, /Command pending|Command admitted|Waiting for the latest/);
  assert.equal(h.eval('musicSheet.state.playback'), 'paused', 'A fresh state may remain unchanged.');
});

test('pause, seek, queue, volume, grouping and selection send exact bound command keys', async t => {
  const { h, f, open, advance, root, control, lastCommand } = await fixture(t);
  f.music.playback = 'playing';
  await open();
  h.click('Pause');
  await h.settle();
  const base = { sourceRevision: 7, sourceId: 'ma:living' };
  assert.deepEqual(lastCommand(), { ...base, command: 'pause', trackId: 'track-1' });
  await advance();
  const seek = control('Music playback position');
  seek.value = 25000;
  seek.onchange();
  await h.settle();
  assert.deepEqual(lastCommand(), { ...base, command: 'seek', positionMs: 25000, trackId: 'track-1' });
  await advance();
  root().querySelectorAll('.music-queue-row')[0].click();
  await h.settle();
  assert.deepEqual(lastCommand(), {
    ...base, command: 'queue-item', queueItemId: 'queue-1', queueId: 'queue-shared',
  });
  await advance();
  h.click('Players');
  h.click('Join group');
  await h.settle();
  assert.deepEqual(lastCommand(), {
    ...base, command: 'toggle-room', roomId: 'ma:kitchen', wasInGroup: false, leaderId: 'ma:family',
  });
  await advance();
  root().querySelectorAll('.music-player-row')[1].querySelectorAll('button')
    .find(button => button.textContent === 'Use player').click();
  await h.settle();
  assert.deepEqual(lastCommand(), { ...base, command: 'select-player', roomId: 'ma:kitchen' });
  await advance();
  const volume = control('Family volume');
  volume.value = 55;
  volume.onchange();
  await h.settle();
  assert.deepEqual(lastCommand(), { ...base, command: 'volume', roomId: 'ma:family', percent: 55 });
});

test('false capabilities expose no seek or lyrics and cannot admit mutations', async t => {
  const { h, f, root, open, control } = await fixture(t);
  for (const key of Object.keys(f.music.capabilities)) f.music.capabilities[key] = false;
  f.music.queue[0].canSelect = false;
  for (const player of f.music.rooms)
    player.canSelect = player.canJoin = player.canRemove = player.canSetVolume = false;
  await open();
  assert.equal(control('Music playback position'), null);
  assert.equal(root().querySelectorAll('button').some(button => button.textContent === 'Lyrics'), false);
  h.click('Play');
  await h.settle();
  assert.equal(f.posts, 0);
  h.click('Players');
  assert.equal(control('Family volume'), null);
  assert.equal(root().querySelectorAll('button').some(button => button.textContent === 'Join group'), false);
});

test('one-second polling stops when hidden and a retired read cannot replace resumed work', async t => {
  const { h, f, open, advance } = await fixture(t);
  await open();
  h.eval('readMusicSheet(musicSheet); readMusicSheet(musicSheet)');
  await h.settle();
  await h.tick(1000);
  assert.equal(f.gets, 1, 'A timer tick without elapsed time cannot bypass the admission limit.');
  await advance();
  assert.equal(f.gets, 2);
  f.holdRead = true;
  await advance();
  assert.equal(f.gets, 3);
  h.hide();
  assert.equal(f.readSignal.aborted, true);
  await advance();
  assert.equal(f.gets, 3);
  h.show();
  await h.settle();
  const after = f.gets;
  f.finishRead();
  await h.settle();
  assert.equal(f.gets, after);
  await advance();
  assert.equal(f.gets, after + 1, 'Resumption must schedule exactly one music read.');
});

test('closing aborts an outstanding read and late completion creates no music work', async t => {
  const { h, f, open, advance } = await fixture(t);
  f.holdRead = true;
  await open();
  h.eval('closeSheet()');
  assert.equal(f.readSignal.aborted, true);
  f.finishRead();
  await h.settle();
  await advance();
  assert.equal(f.gets, 1);
  assert.equal(h.eval('musicSheet'), null);
});

test('Portal switching aborts a POST and its late reply cannot restore old polling', async t => {
  const { h, f, open, advance } = await fixture(t);
  await open();
  f.holdCommand = true;
  h.click('Play');
  await h.settle();
  h.eval('selectRoom(roster[1].base)');
  await h.settle();
  assert.equal(f.postSignal.aborted, true);
  f.finishPost();
  await h.settle();
  await advance();
  assert.equal(f.gets, 1);
  assert.equal(h.eval('musicSheet'), null);
  await open();
  const get = h.calls.filter(call => call.path === '/remote/music').at(-1);
  assert.equal(new URL(get.url).port, '8878');
  assert.equal(get.opts.headers.Authorization, 'Bearer fixture-b');
});

test('a stale 409 refreshes state and never automatically retries the mutation', async t => {
  const { h, f, root, open, advance } = await fixture(t, { deny: true });
  await open();
  h.click('Play');
  await h.settle();
  assert.match(root().textContent, /Music changed/);
  await advance();
  await advance();
  assert.equal(f.posts, 1);
  assert.equal(f.gets, 3);
});

test('an old server stops music polling gracefully while preserving legacy playback controls', async t => {
  const { h, f, root, open, advance } = await fixture(t, { getStatus: 404 });
  await open();
  assert.match(root().textContent, /Existing playback controls still work/);
  await advance();
  assert.equal(f.gets, 1);
  assert.equal(h.document.getElementById('media-panel')
    .querySelector('[aria-label="Portal music volume"]').disabled, false);
});

test('polling retains a dragged seek control without posting its unfinished value', async t => {
  const { f, open, advance, control } = await fixture(t);
  await open();
  const seek = control('Music playback position');
  seek.focus();
  seek.value = 50000;
  seek.oninput();
  f.music.positionMs = 2000;
  await advance();
  assert.equal(control('Music playback position'), seek);
  assert.equal(Number(seek.value), 50000);
  assert.equal(f.posts, 0);
});

test('client rendering bounds queue, players and lyrics to their wire limits', async t => {
  const { h, f, root, open } = await fixture(t);
  const queueItem = f.music.queue[0], player = f.music.rooms[0];
  f.music.queue = Array.from({ length: 501 }, (_, i) => ({ ...queueItem, id: `q${i}` }));
  f.music.rooms = Array.from({ length: 65 }, (_, i) => ({ ...player, id: `ma:p${i}` }));
  f.music.capabilities.lyrics = true;
  f.music.lyrics = Array.from({ length: 1001 }, () => ({ timeMs: 0, text: 'Line' }));
  await open();
  assert.equal(root().querySelectorAll('.music-queue-row').length, 500);
  h.click('Players');
  assert.equal(root().querySelectorAll('.music-player-row').length, 64);
  h.click('Lyrics');
  assert.equal(root().querySelectorAll('.music-lyric').length, 1000);
});

test('503 unknown admission reads back state without claiming rejection or retrying the mutation', async t => {
  const { h, f, root, open, advance } = await fixture(t, { uncertain: true });
  await open();
  h.click('Play');
  await h.settle();
  assert.match(root().textContent, /outcome unknown/i);
  assert.doesNotMatch(root().textContent, /rejected|Command admitted/i);
  assert.equal(h.eval('musicSheet.uncertain'), true);
  h.click('Play');
  await h.settle();
  assert.equal(f.posts, 1);
  await advance();
  await advance();
  assert.equal(f.posts, 1);
  assert.equal(f.gets, 3);
  assert.equal(h.eval('musicSheet.uncertain'), false);
  assert.equal(h.eval('musicSheet.state.playback'), 'paused');
});

test('a late GET started before a command cannot clear pending admission', async t => {
  const { h, f, root, open, advance } = await fixture(t);
  await open();
  f.holdRead = true;
  await advance();
  h.click('Play');
  await h.settle();
  assert.equal(f.readSignal.aborted, true);
  f.finishRead();
  await h.settle();
  assert.equal(h.eval('musicSheet.admitted'), true);
  assert.match(root().textContent, /pending/i);
  await advance();
  assert.equal(h.eval('musicSheet.admitted'), false);
});

test('stale displayed controls cannot command a changed source, revision, track or group leader', async t => {
  for (const changed of ['source', 'revision', 'track', 'leader']) {
    const { h, f, root, open, advance } = await fixture(t);
    await open();
    if (changed === 'leader') h.click('Players');
    const label = changed === 'leader' ? 'Join group' : 'Play';
    const oldControl = root().querySelectorAll('button').find(button => (button.getAttribute('aria-label') || button.textContent) === label);
    if (changed === 'source') f.music.sourceId = 'ma:other';
    if (changed === 'revision') f.music.sourceRevision = 8;
    if (changed === 'track') f.music.track.id = 'track-2';
    if (changed === 'leader') f.music.controlPlayerId = 'ma:new';
    await advance();
    oldControl.click();
    await h.settle();
    assert.equal(f.posts, 0, `A retired ${changed} observation cannot dispatch a mutation.`);
    assert.match(root().textContent, /Music changed/);
    h.hide();
  }
});

test('unknown volume needs an explicit valid value and group removal binds the observed leader', async t => {
  const { h, f, open, control, advance, lastCommand } = await fixture(t);
  f.music.rooms[1].canSetVolume = true;
  f.music.rooms[1].canJoin = false;
  f.music.rooms[1].canRemove = true;
  f.music.rooms[1].inCurrentGroup = true;
  await open();
  h.click('Players');
  h.click('Set volume');
  await h.settle();
  assert.equal(f.posts, 0, 'An unreported volume must not become an invented zero.');
  const input = control('Kitchen volume');
  assert.equal(input.type, 'number');
  input.value = 101;
  h.click('Set volume');
  await h.settle();
  assert.equal(f.posts, 0);
  await advance();
  control('Kitchen volume').value = 27;
  h.click('Set volume');
  await h.settle();
  assert.deepEqual(lastCommand(), {
    command: 'volume', sourceRevision: 7, sourceId: 'ma:living', roomId: 'ma:kitchen', percent: 27,
  });
  await advance();
  h.click('Remove from group');
  await h.settle();
  assert.deepEqual(lastCommand(), {
    command: 'toggle-room', sourceRevision: 7, sourceId: 'ma:living',
    roomId: 'ma:kitchen', wasInGroup: true, leaderId: 'ma:family',
  });
});

test('a displayed group operation is rejected when membership changes under the same leader', async t => {
  for (const originallyGrouped of [false, true]) {
    const { h, f, root, open, advance } = await fixture(t);
    Object.assign(f.music.rooms[1], {
      inCurrentGroup: originallyGrouped,
      canJoin: !originallyGrouped,
      canRemove: originallyGrouped,
    });
    await open();
    h.click('Players');
    const label = originallyGrouped ? 'Remove from group' : 'Join group';
    const displayed = root().querySelectorAll('button').find(button => button.textContent === label);
    Object.assign(f.music.rooms[1], {
      inCurrentGroup: !originallyGrouped,
      canJoin: originallyGrouped,
      canRemove: !originallyGrouped,
    });
    await advance();
    displayed.click();
    await h.settle();
    assert.equal(f.posts, 0, 'A changed membership cannot invert the displayed operation.');
    assert.match(root().textContent, /Music changed/);
    h.hide();
  }
});

test('an admitted command reports its later safe failure without replaying it', async t => {
  const { h, f, root, open, advance } = await fixture(t, { commandResult: true });
  f.music.playback = 'playing';
  await open();
  h.click('Pause');
  await h.settle();
  f.music.lastCommand = { requestId: 101, command: 'pause', outcome: 'failed' };
  await advance();
  assert.match(root().textContent, /player could not apply/i);
  assert.equal(f.posts, 1);
  await advance();
  assert.match(root().textContent, /player could not apply/i, 'A healthy poll must not erase failure.');
  assert.equal(f.posts, 1, 'Reading a failure must never replay the mutation.');
});

test('a different command result cannot confirm the browser request', async t => {
  const { h, f, root, open, advance } = await fixture(t, { commandResult: true });
  f.music.playback = 'playing';
  await open();
  h.click('Pause');
  await h.settle();
  f.music.lastCommand = { requestId: 102, command: 'pause', outcome: 'confirmed' };
  await advance();
  assert.match(root().textContent, /previous command was not confirmed/i);
  assert.equal(f.posts, 1);
});

test('a populated idle queue can start its selected item without a current track', async t => {
  const { h, f, root, open, lastCommand } = await fixture(t);
  f.music.track = null;
  f.music.playback = 'idle';
  await open();
  const first = root().querySelectorAll('.music-queue-row')[0];
  assert.equal(first.disabled, false);
  first.click();
  await h.settle();
  assert.deepEqual(lastCommand(), {
    command: 'queue-item', sourceRevision: 7, sourceId: 'ma:living',
    queueId: 'queue-shared', queueItemId: 'queue-1',
  });
});

test('an old queue row cannot start an item in a replacement queue', async t => {
  const { h, f, root, open, advance } = await fixture(t);
  await open();
  const first = root().querySelectorAll('.music-queue-row')[0];
  f.music.queueId = 'replacement';
  await advance();
  first.click();
  await h.settle();
  assert.equal(f.posts, 0);
  assert.match(root().textContent, /Music changed/);
});
