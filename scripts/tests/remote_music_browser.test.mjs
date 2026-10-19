/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */
// Opt-in isolated Chrome with synthetic music data, never real account credentials or playback.
import test from 'node:test';
import assert from 'node:assert/strict';
import { readFileSync, mkdirSync } from 'node:fs';
import { createRequire } from 'node:module';
const enabled = process.env.IMMORTAL_REMOTE_CHROME === '1';
const root = new URL('../../', import.meta.url);
const read = path => readFileSync(new URL(path, root), 'utf8');
const origin = 'https://remote-music-fixture.invalid';
test('mobile music sheet renders capability-safe queue and players without overflow or hidden polling', {
  skip: enabled ? false : 'Set IMMORTAL_REMOTE_CHROME=1 for isolated synthetic Chrome.',
  timeout: 30000,
}, async t => {
  const { chromium } = createRequire(import.meta.url)('playwright');
  const html = read('app/src/main/java/com/immortal/launcher/RemoteHtml.kt').match(/"""([\s\S]*?)"""/)[1].trim();
  const assets = Object.fromEntries(['control-center.js', 'core.mjs', 'control-center.css'].map(name => [
    `/remote/assets/${name}`, read(`app/src/main/assets/remote/${name}`)]));
  const f = { errors: [], reads: 0, outsideRequests: 0 };
  const music = {
    schemaVersion: 1, sourceRevision: 7, sourceId: 'ma:family', connection: 'ready', queueId: 'queue-shared',
    playback: 'paused', pending: null, audioOwnedByOther: false, controlPlayerId: 'ma:family',
    track: { id: 'track-1', title: 'A long title with words that must fit on a phone screen',
      artist: 'A very long artist name with multiple collaborators', album: 'Album',
      durationMs: 180000, isLive: false }, positionMs: 42000,
    capabilities: { play: true, pause: true, next: true, previous: true, seek: true,
      queue: true, lyrics: false, rooms: true, volume: true, selectPlayer: true },
    queue: [{ id: 'queue-1', title: 'First song', artist: 'Artist', available: true, canSelect: true },
      { id: 'queue-2', title: 'Unavailable song', artist: 'Artist', available: false, canSelect: false }],
    rooms: [{ id: 'ma:family', name: 'Family Area', available: true, volume: 25,
      inCurrentGroup: true, canSelect: false, canJoin: false, canRemove: false, canSetVolume: true },
      { id: 'ma:living', name: 'Living Area', available: true, volume: null,
        inCurrentGroup: false, canSelect: true, canJoin: true, canRemove: false, canSetVolume: true }],
    lyrics: [],
  };
  const browser = await chromium.launch({ headless: true,
    executablePath: '/Applications/Google Chrome.app/Contents/MacOS/Google Chrome',
    args: ['--disable-background-networking', '--disable-component-update', '--no-proxy-server',
      '--host-resolver-rules=MAP * ~NOTFOUND'] });
  try {
    const context = await browser.newContext({ viewport: { width: 402, height: 874 },
      isMobile: true, hasTouch: true, serviceWorkers: 'block', reducedMotion: 'reduce' });
    await context.routeWebSocket('**/*', socket => socket.close());
    await context.route('**/*', async route => {
      const url = new URL(route.request().url());
      const json = body => route.fulfill({ contentType: 'application/json', body: JSON.stringify(body) });
      if (url.origin !== origin) { f.outsideRequests++; return route.abort(); }
      if (url.pathname === '/remote/ui') return route.fulfill({ contentType: 'text/html', body: html });
      if (assets[url.pathname]) return route.fulfill({
        contentType: url.pathname.endsWith('.css') ? 'text/css' : 'text/javascript', body: assets[url.pathname] });
      if (url.pathname === '/remote/music') { f.reads++; return json({ ok: true, music }); }
      if (url.pathname === '/remote/music/command') return route.fulfill({ status: 409,
        contentType: 'application/json', body: JSON.stringify({ ok: false, admitted: false, error: 'unavailable' }) });
      if (url.pathname === '/remote/companion') return json({ ok: true, protocolVersion: 1,
        deviceId: 'fixture-device', name: 'Fixture', model: 'Synthetic Portal',
        capabilities: {}, app: { versionName: 'fixture' } });
      if (url.pathname === '/remote/nowplaying') return json({ ok: true,
        np: { active: false, volume: { level: 4, max: 15, canSet: true } } });
      if (url.pathname === '/remote/roster') return json({ ok: true, roster: [] });
      return json({ ok: true });
    });
    await context.addInitScript(origin => {
      localStorage.setItem('immortal_remote_devices', JSON.stringify([
        { base: origin, name: 'Fixture', token: 'fixture-only' }]));
      localStorage.setItem('immortal_remote_active', '0');
    }, origin);
    const page = await context.newPage(); page.setDefaultTimeout(5000);
    page.on('pageerror', error => f.errors.push(error.message));
    await page.goto(`${origin}/remote/ui`);
    await page.getByRole('button', { name: 'Queue & players', exact: true }).click();
    const sheet = page.locator('#sheet');
    await sheet.getByRole('button', { name: 'First song Artist', exact: true }).waitFor();
    assert.equal(await sheet.getByRole('button', { name: /Unavailable song/ }).isDisabled(), true);
    assert.equal(await sheet.getByRole('button', { name: 'Lyrics', exact: true }).count(), 0);
    for (const colorScheme of ['light', 'dark']) {
      await page.emulateMedia({ colorScheme });
      for (const width of [360, 402, 600]) {
      await page.setViewportSize({ width, height: 874 });
      assert.equal(await page.evaluate(() => document.documentElement.scrollWidth > innerWidth), false);
      assert.equal(await sheet.evaluate(node => node.scrollWidth > node.clientWidth), false);
    }
      }
    await sheet.getByRole('button', { name: 'Players', exact: true }).click();
    await sheet.getByText('Living Area', { exact: true }).waitFor();
    assert.equal(await sheet.getByRole('button', { name: 'Join group', exact: true }).isEnabled(), true);
    const unknown = sheet.getByRole('spinbutton', { name: 'Living Area volume', exact: true });
    assert.equal(await unknown.inputValue(), '');
    assert.equal(await sheet.getByText('Volume not reported', { exact: true }).isVisible(), true);
    if (process.env.IMMORTAL_REMOTE_ARTIFACT_DIR) {
      mkdirSync(process.env.IMMORTAL_REMOTE_ARTIFACT_DIR, { recursive: true });
      await page.setViewportSize({ width: 402, height: 874 });
      await page.evaluate(() => new Promise(resolve => requestAnimationFrame(() => requestAnimationFrame(resolve))));
      assert.equal(await page.locator('.sheet-header').count(), 1);
      await page.screenshot({ path: `${process.env.IMMORTAL_REMOTE_ARTIFACT_DIR}/music-players-dark.png` });
    }
    await sheet.getByRole('button', { name: 'Close dialog', exact: true }).click();
    const afterClose = f.reads;
    await page.waitForTimeout(1250);
    assert.equal(f.reads, afterClose);
    assert.deepEqual(f.errors, []);
    assert.equal(f.outsideRequests, 0);
    t.diagnostic(JSON.stringify({ scope: 'Synthetic Chrome UI only', widths: [360, 402, 600],
      unavailableDisabled: true, unknownVolumePreserved: true, noClosedSheetPolling: true,
      pageErrors: f.errors, outsideRequests: f.outsideRequests }));
  } finally { await browser.close(); }
});

test('real Chrome keeps known lyrics readable only for the same pending source and track', {
  skip: enabled ? false : 'Set IMMORTAL_REMOTE_CHROME=1 for isolated synthetic Chrome.',
  timeout: 30000,
}, async t => {
  const { chromium } = createRequire(import.meta.url)('playwright');
  const html = read('app/src/main/java/com/immortal/launcher/RemoteHtml.kt').match(/"""([\s\S]*?)"""/)[1].trim();
  const assets = Object.fromEntries(['control-center.js', 'core.mjs', 'control-center.css'].map(name => [
    `/remote/assets/${name}`, read(`app/src/main/assets/remote/${name}`)]));
  const music = {
    schemaVersion: 1, sourceRevision: 7, sourceId: 'ma:family', connection: 'ready',
    queueId: 'queue-fixture', playback: 'paused', pending: null, lastCommand: null,
    audioOwnedByOther: false, controlPlayerId: 'ma:family', positionMs: 1000,
    track: { id: 'track-one', title: 'Synthetic track', artist: 'Fixture artist', album: '',
      durationMs: 180000, isLive: false },
    capabilities: { play: true, pause: true, next: true, previous: true, seek: true,
      queue: true, lyrics: true, rooms: true, volume: true, selectPlayer: true },
    queue: [{ id: 'item-one', title: 'Fixture queue item', artist: 'Fixture artist',
      available: true, canSelect: true }],
    rooms: [{ id: 'ma:family', name: 'Fixture player', available: true, volume: 25,
      inCurrentGroup: true, canSelect: true, canJoin: false, canRemove: false, canSetVolume: true }],
    lyrics: [{ timeMs: 0, text: 'Initial browser lyric' }],
  };
  const f = { errors: [], outsideRequests: 0, reads: 0, posts: 0 };
  const browser = await chromium.launch({ headless: true,
    executablePath: '/Applications/Google Chrome.app/Contents/MacOS/Google Chrome',
    args: ['--disable-background-networking', '--disable-component-update', '--no-proxy-server',
      '--host-resolver-rules=MAP * ~NOTFOUND'] });
  try {
    const context = await browser.newContext({ viewport: { width: 402, height: 874 },
      isMobile: true, hasTouch: true, serviceWorkers: 'block', reducedMotion: 'reduce' });
    await context.routeWebSocket('**/*', socket => socket.close());
    await context.route('**/*', async route => {
      const url = new URL(route.request().url());
      const json = body => route.fulfill({ contentType: 'application/json', body: JSON.stringify(body) });
      if (url.origin !== origin) { f.outsideRequests++; return route.abort(); }
      if (url.pathname === '/remote/ui') return route.fulfill({ contentType: 'text/html', body: html });
      if (assets[url.pathname]) return route.fulfill({ contentType: url.pathname.endsWith('.css')
        ? 'text/css' : 'text/javascript', body: assets[url.pathname] });
      if (url.pathname === '/remote/music') { f.reads++; return json({ ok: true, music }); }
      if (url.pathname === '/remote/music/command') { f.posts++; return json({ ok: false, error: 'unavailable' }); }
      if (url.pathname === '/remote/companion') return json({ ok: true, protocolVersion: 1,
        deviceId: 'synthetic-pending-device', name: 'Fixture', model: 'Synthetic Portal', capabilities: {} });
      if (url.pathname === '/remote/nowplaying') return json({ ok: true, np: { active: false } });
      if (url.pathname === '/remote/roster') return json({ ok: true, roster: [] });
      return json({ ok: true });
    });
    await context.addInitScript(origin => {
      localStorage.setItem('immortal_remote_devices', JSON.stringify([
        { base: origin, name: 'Fixture', token: 'synthetic-fixture-only' }]));
      localStorage.setItem('immortal_remote_active', '0');
    }, origin);
    const page = await context.newPage(); page.setDefaultTimeout(4000);
    page.on('pageerror', error => f.errors.push(error.message));
    await page.goto(`${origin}/remote/ui`);
    await page.getByRole('button', { name: 'Queue & players', exact: true }).click();
    const sheet = page.locator('#sheet');
    await sheet.getByRole('button', { name: 'Lyrics', exact: true }).click();
    await sheet.getByText('Initial browser lyric', { exact: true }).waitFor();
    const retiredPlay = await sheet.getByRole('button', { name: 'Play', exact: true }).elementHandle();
    music.pending = { requestId: 101, command: 'pause', startedAtElapsedMs: 10 };
    for (const key of Object.keys(music.capabilities)) music.capabilities[key] = false;
    music.queue[0].canSelect = false;
    for (const key of ['canSelect', 'canJoin', 'canRemove', 'canSetVolume']) music.rooms[0][key] = false;
    music.lyrics[0].text = 'Pending browser lyric';
    await sheet.locator('.music-status').filter({ hasText: 'Command pending' }).waitFor();
    assert.equal(await sheet.getByText('Pending browser lyric', { exact: true }).isVisible(), true,
      'Production DOM must retain the current lyric through executable-capability withdrawal.');
    assert.equal(await sheet.getByRole('button', { name: 'Lyrics', exact: true }).count(), 1);
    assert.equal(await sheet.getByRole('button', { name: 'Play', exact: true }).isDisabled(), true);
    await retiredPlay.evaluate(node => node.click());
    assert.equal(f.posts, 0, 'A detached old control must not send during pending.');
    music.lyrics[0].text = 'Updated pending browser lyric';
    await sheet.getByText('Updated pending browser lyric', { exact: true }).waitFor();
    music.track.id = 'track-two';
    await sheet.getByRole('button', { name: 'Fixture queue item Fixture artist', exact: true }).waitFor();
    assert.equal(await sheet.locator('.music-lyric').count(), 0);
    assert.equal(await sheet.getByRole('button', { name: 'Lyrics', exact: true }).count(), 0);
    music.pending = null; music.capabilities.lyrics = true;
    music.lyrics[0].text = 'Supported second track';
    await sheet.getByRole('button', { name: 'Lyrics', exact: true }).click();
    await sheet.getByText('Supported second track', { exact: true }).waitFor();
    music.pending = { requestId: 102, command: 'pause', startedAtElapsedMs: 20 };
    music.capabilities.lyrics = false;
    music.lyrics[0].text = 'Second pending track';
    await sheet.getByText('Second pending track', { exact: true }).waitFor();
    music.sourceId = 'ma:replacement'; music.sourceRevision++;
    await sheet.getByRole('button', { name: 'Fixture queue item Fixture artist', exact: true }).waitFor();
    assert.equal(await sheet.getByRole('button', { name: 'Lyrics', exact: true }).count(), 0);
    music.pending = null; music.capabilities.lyrics = true;
    music.lyrics[0].text = 'Supported replacement source';
    await sheet.getByRole('button', { name: 'Lyrics', exact: true }).click();
    await sheet.getByText('Supported replacement source', { exact: true }).waitFor();
    music.pending = { requestId: 103, command: 'pause', startedAtElapsedMs: 30 };
    music.capabilities.lyrics = false;
    music.lyrics[0].text = 'Replacement pending lyric';
    await sheet.getByText('Replacement pending lyric', { exact: true }).waitFor();
    music.pending = null;
    await sheet.getByRole('button', { name: 'Fixture queue item Fixture artist', exact: true }).waitFor();
    assert.equal(await sheet.getByRole('button', { name: 'Lyrics', exact: true }).count(), 0);
    music.pending = { requestId: 104, command: 'pause', startedAtElapsedMs: 40 };
    await page.waitForFunction(() => document.querySelector('.music-status')?.textContent.includes('Command pending'));
    assert.equal(await sheet.locator('.music-lyric').count(), 0);
    assert.equal(await sheet.getByRole('button', { name: 'Lyrics', exact: true }).count(), 0);
    assert.equal(f.posts, 0);
    music.pending = null; music.capabilities.lyrics = true; music.capabilities.seek = true;
    music.lyrics[0].text = 'Focused terminal browser lyric';
    await sheet.getByRole('button', { name: 'Lyrics', exact: true }).click();
    await sheet.getByText('Focused terminal browser lyric', { exact: true }).waitFor();
    await sheet.getByRole('slider', { name: 'Music playback position', exact: true }).focus();
    music.capabilities.lyrics = false;
    await sheet.getByRole('button', { name: 'Fixture queue item Fixture artist', exact: true }).waitFor();
    assert.equal(await sheet.locator('.music-lyric').count(), 0);
    assert.equal(await sheet.getByRole('button', { name: 'Lyrics', exact: true }).count(), 0);
    assert.equal(f.posts, 0);
    await sheet.getByRole('button', { name: 'Close dialog', exact: true }).click();
    const stoppedAt = f.reads;
    await page.waitForTimeout(1250);
    assert.equal(f.reads, stoppedAt);
    assert.deepEqual(f.errors, []);
    assert.equal(f.outsideRequests, 0);
    t.diagnostic(JSON.stringify({ genuineDom: true, pendingLyricsPreserved: true,
      pendingLyricsUpdated: true, trackReplacementClosedLyrics: true, sourceReplacementClosedLyrics: true,
      terminalWithdrawalClosedLyrics: true, focusedDraftWithdrawalClosedLyrics: true,
      unsupportedLyricsAbsent: true, commandPosts: f.posts, outsideRequests: f.outsideRequests,
      noClosedSheetPolling: true }));
  } finally { await browser.close(); }
});
