/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */
// Native-list half of app/src/main/assets/ytmusic/bridge.js, run against a stub DOM whose
// querySelector(All) answers exact selector strings (the script's own SEL/LISTS entries).
import test from 'node:test';
import assert from 'node:assert/strict';
import { readFileSync } from 'node:fs';
import vm from 'node:vm';

const SOURCE = readFileSync(new URL('../../main/assets/ytmusic/bridge.js', import.meta.url), 'utf8');

function el({ text = '', attrs = {}, qs = {}, src, hidden = false } = {}) {
  return {
    textContent: text, src, clicks: 0,
    // On-screen by default; `hidden` models a closed dialog that stays in the DOM.
    getBoundingClientRect: () => (hidden ? { width: 0, height: 0 } : { width: 100, height: 40 }),
    getAttribute: n => (n in attrs ? attrs[n] : null),
    hasAttribute: n => n in attrs,
    querySelectorAll: s => qs[s] || [],
    querySelector: s => (qs[s] || [])[0] || null,
    click() { this.clicks++; },
    dispatchEvent() { return true; }, focus() {}, blur() {},
  };
}

function boot({ doc = {}, pathname = '/watch', search = '' } = {}) {
  const intervals = [];
  const messages = [];
  const sandbox = {
    location: { protocol: 'https:', hostname: 'music.youtube.com', pathname, search },
    navigator: {},
    document: {
      querySelectorAll: s => doc[s] || [],
      querySelector: s => (doc[s] || [])[0] || null,
      addEventListener() {}, body: el(), activeElement: null,
    },
    setInterval: fn => { intervals.push(fn); return intervals.length; },
    setTimeout: () => 0, clearTimeout() {},
    URLSearchParams, JSON, Math, Date, Object, String, Array, isFinite,
    Event: class { constructor(type) { this.type = type; } },
    KeyboardEvent: class { constructor(type) { this.type = type; } },
    portalYtMusic: { postMessage: m => messages.push(m) },
  };
  sandbox.window = sandbox;
  sandbox.top = sandbox;
  vm.createContext(sandbox);
  vm.runInContext(SOURCE, sandbox);
  const lists = () => messages.filter(m => m.startsWith('{"v":1,"type":"list"')).map(m => JSON.parse(m));
  return { api: sandbox.__portalYtm, lists, tick: () => intervals.forEach(fn => fn()), doc };
}

function queueRow(title, { selected = false, art = 'https://lh3.googleusercontent.com/a=w60' } = {}) {
  const info = el();
  const row = el({
    attrs: selected ? { selected: '', 'play-button-state': 'playing' } : {},
    qs: {
      '.song-title': [el({ text: `  ${title}\n` })], '.byline': [el({ text: 'Marrow & Vale' })],
      '.duration': [el({ text: '4:12' })], img: [el({ src: art })], '.song-info': [info],
    },
  });
  row.info = info;
  return row;
}

test('watch posts a list message with the routing prefix and sanitized rows', () => {
  const rows = [queueRow('Low tide, early', { selected: true }), queueRow('Embers', { art: 'http://x/a.jpg' })];
  const h = boot({ doc: { 'ytmusic-player-queue-item': rows } });
  assert.equal(h.api.watch('queue'), true);
  const [msg] = h.lists();
  assert.equal(msg.k, 'queue');
  assert.equal(msg.here, true);
  assert.equal(msg.items.length, 2);
  assert.deepEqual(msg.items[0], { i: 0, t: 'Low tide, early', s: 'Marrow & Vale', d: '4:12',
    art: 'https://lh3.googleusercontent.com/a=w60', p: true, k: 'song', pl: true });
  assert.equal(msg.items[1].art, '', 'non-https art is dropped');
  assert.equal(msg.items[1].p, false);
  assert.equal(h.api.watch('home'), false);
});

test('ticks re-post only on change, and a change bumps the generation', () => {
  const rows = [queueRow('A')];
  const h = boot({ doc: { 'ytmusic-player-queue-item': rows } });
  h.api.watch('queue');
  h.tick();
  assert.equal(h.lists().length, 1);
  const g = h.lists()[0].g;
  h.doc['ytmusic-player-queue-item'] = [queueRow('A'), queueRow('B')];
  h.tick();
  assert.equal(h.lists().length, 2);
  assert.equal(h.lists()[1].g, g + 1);
});

test('playItem clicks the row only for the current generation and a real index', () => {
  const rows = [queueRow('A'), queueRow('B')];
  const h = boot({ doc: { 'ytmusic-player-queue-item': rows } });
  h.api.watch('queue');
  const g = h.lists()[0].g;
  assert.equal(h.api.playItem('queue', 1, g + 1), false, 'stale generation refused');
  assert.equal(h.api.playItem('queue', 5, g), false);
  assert.equal(h.api.playItem('queue', 1.5, g), false);
  assert.equal(h.api.playItem('nope', 1, g), false);
  assert.equal(rows[1].info.clicks, 0);
  assert.equal(h.api.playItem('queue', 1, g), true);
  assert.equal(rows[1].info.clicks, 1);
  assert.equal(rows[0].info.clicks, 0);
});

test('search rows drop the kind word, lift the duration, and report the query', () => {
  const link = el();
  const row = el({ qs: {
    '.title': [el({ text: 'Harbour lights' })],
    '.secondary-flex-columns yt-formatted-string': [el({ text: 'Song • Marrow & Vale • Low tide • 3:36' })],
    '.title a[href*="watch"]': [link],
    'a[href]': [el({ attrs: { href: 'watch?v=abc' } })],
  } });
  const h = boot({ doc: { 'ytmusic-search-page ytmusic-responsive-list-item-renderer': [row] },
    pathname: '/search', search: '?q=harbour+lights' });
  h.api.watch('search');
  const [msg] = h.lists();
  assert.equal(msg.q, 'harbour lights');
  assert.equal(msg.here, true);
  assert.deepEqual([msg.items[0].s, msg.items[0].d, msg.items[0].k], ['Marrow & Vale • Low tide', '3:36', 'song']);
  assert.equal(h.api.playItem('search', 0, msg.g), true);
  assert.equal(link.clicks, 1);
});

test('library reports off-page and signed-out honestly; rows without a play target are marked', () => {
  const card = el({ qs: { '.title': [el({ text: 'Liked songs' })], '.subtitle': [el({ text: 'Playlist • Auto playlist' })] } });
  const h = boot({ doc: {
    'ytmusic-browse-response ytmusic-responsive-list-item-renderer, ytmusic-browse-response ytmusic-two-row-item-renderer': [card],
    'a[href*="accounts.google.com"]': [el()],
  }, pathname: '/' });
  h.api.watch('library');
  const [msg] = h.lists();
  assert.equal(msg.here, false);
  assert.equal(msg.so, true);
  assert.deepEqual([msg.items[0].s, msg.items[0].k, msg.items[0].pl], ['Auto playlist', 'playlist', false]);
});

test('promo: only "Not now" is ever clicked', () => {
  const open = el({ text: 'Open' });
  const notNow = el({ text: ' Not now ' });
  const dialog = el({ qs: { 'button, a, tp-yt-paper-button, yt-button-renderer, yt-button-shape': [open, notNow] } });
  const h = boot({ doc: { 'tp-yt-paper-dialog': [dialog] } });
  h.tick();
  assert.equal(notNow.clicks, 1);
  assert.equal(open.clicks, 0);
  const lone = el({ text: 'Open' });
  const h2 = boot({ doc: { '[role="dialog"]': [el({ qs: { 'button, a, tp-yt-paper-button, yt-button-renderer, yt-button-shape': [lone] } })] } });
  h2.tick();
  assert.equal(lone.clicks, 0);
  // A closed (zero-size) dialog left in the DOM is never clicked, so no click loop.
  const ghost = el({ text: 'Not now' });
  const h3 = boot({ doc: { 'tp-yt-paper-dialog': [el({ hidden: true, qs: { 'button, a, tp-yt-paper-button, yt-button-renderer, yt-button-shape': [ghost] } })] } });
  h3.tick(); h3.tick();
  assert.equal(ghost.clicks, 0);
});
