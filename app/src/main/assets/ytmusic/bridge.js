/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */
// Immortal Portal: observe and drive the embedded YouTube Music page (YtMusicBridge.kt).
// Read-only observation plus the page's own transport; it never touches sign-in or the
// user agent. Everything is best-effort: a missing selector simply does nothing.
(function () {
  'use strict';
  try {
    if (window.__portalYtm) return;
    if (window.top !== window) return;
    if (location.protocol !== 'https:' || location.hostname !== 'music.youtube.com') return;
  } catch (e) { return; }

  var SINK = 'portalYtMusic';
  var TICK_MS = 1000;
  var COALESCE_MS = 200;
  var MAX_TEXT = 400;
  var MAX_URL = 2048;

  // All page selectors live here so a YouTube Music markup change is a one-place fix.
  var SEL = {
    media: ['#movie_player video', 'video.html5-main-video', 'video', 'audio'],
    bar: 'ytmusic-player-bar',
    barTitle: ['ytmusic-player-bar .title', 'ytmusic-player-bar yt-formatted-string.title'],
    barArtist: ['ytmusic-player-bar .byline a', 'ytmusic-player-bar .byline'],
    barArt: ['ytmusic-player-bar img.image', 'ytmusic-player-bar .thumbnail-image-wrapper img'],
    next: ['ytmusic-player-bar .next-button', 'ytmusic-player-bar [aria-label="Next"]',
      'ytmusic-player-bar [title="Next"]'],
    previous: ['ytmusic-player-bar .previous-button', 'ytmusic-player-bar [aria-label="Previous"]',
      'ytmusic-player-bar [title="Previous"]'],
    playPause: ['ytmusic-player-bar #play-pause-button', 'ytmusic-player-bar .play-pause-button'],
    playerPageOpen: ['ytmusic-app-layout[player-page-open]', 'ytmusic-app-layout[player-page-open_]'],
    playerPageToggle: ['ytmusic-player-bar .toggle-player-page-button',
      'ytmusic-player-bar [aria-label="Open player page"]', 'ytmusic-player-bar .middle-controls'],
    playerPageClose: ['ytmusic-player-bar .toggle-player-page-button',
      'ytmusic-player-bar [aria-label="Close player page"]'],
    library: ['ytmusic-pivot-bar-item-renderer[tab-id="FEmusic_library_landing"]',
      'ytmusic-guide-entry-renderer a[href="library"]', 'a[href="library"]', 'a[href="/library"]'],
    searchInput: ['ytmusic-search-box input#input', 'ytmusic-search-box input'],
    searchOpen: ['ytmusic-search-box .search-icon', 'ytmusic-search-box [aria-label="Search"]',
      'ytmusic-search-box #placeholder', 'ytmusic-search-box']
  };
  // Documented YouTube Music keyboard shortcuts, used only when no control is found.
  var KEYS = { next: ['j', 'KeyJ', 74], previous: ['k', 'KeyK', 75] };

  function first(list) {
    for (var i = 0; i < list.length; i++) {
      try { var el = document.querySelector(list[i]); if (el) return el; } catch (e) {}
    }
    return null;
  }
  function clickFirst(list) {
    var el = first(list);
    if (!el) return false;
    try { el.click(); return true; } catch (e) { return false; }
  }
  function str(v, max) {
    if (typeof v !== 'string') return '';
    v = v.replace(/\s+/g, ' ').trim();
    return v.length > max ? v.slice(0, max) : v;
  }
  function httpsUrl(v) {
    v = str(v, MAX_URL);
    return v.indexOf('https://') === 0 ? v : '';
  }
  function num(v) {
    return (typeof v === 'number' && isFinite(v) && v > 0) ? Math.round(v * 1000) / 1000 : 0;
  }

  // Record the page's own media-session handlers so native transport goes through them.
  var handlers = {};
  var session = null;
  try {
    session = navigator.mediaSession || null;
    if (session && typeof session.setActionHandler === 'function') {
      var original = session.setActionHandler;
      session.setActionHandler = function (action, fn) {
        try { handlers[action] = typeof fn === 'function' ? fn : null; } catch (e) {}
        return original.apply(session, arguments);
      };
    }
  } catch (e) {}

  function media() {
    var el = first(SEL.media);
    return el && typeof el.paused === 'boolean' ? el : null;
  }

  function snapshot() {
    var el = media();
    var md = null;
    try { md = session && session.metadata; } catch (e) {}
    var title = md ? str(md.title, MAX_TEXT) : '';
    var artist = md ? str(md.artist, MAX_TEXT) : '';
    var album = md ? str(md.album, MAX_TEXT) : '';
    var art = '';
    try {
      var list = md && md.artwork;
      if (list && list.length) art = httpsUrl(list[list.length - 1].src);
    } catch (e) {}
    if (!title) { var t = first(SEL.barTitle); title = t ? str(t.textContent, MAX_TEXT) : ''; }
    if (!artist) { var a = first(SEL.barArtist); artist = a ? str(a.textContent, MAX_TEXT) : ''; }
    if (!art) { var i = first(SEL.barArt); art = i ? httpsUrl(i.src) : ''; }
    var state = 'none';
    if (el && title) state = el.ended ? 'ended' : (el.paused ? 'paused' : 'playing');
    return { v: 1, t: title, a: artist, al: album, art: art,
      d: el ? num(el.duration) : 0, p: el ? num(el.currentTime) : 0, s: state };
  }

  var lastSent = '';
  var lastSentAt = 0;
  var pending = null;
  function post(force) {
    pending = null;
    var sink = window[SINK];
    if (!sink || typeof sink.postMessage !== 'function') return;
    var json;
    try { json = JSON.stringify(snapshot()); } catch (e) { return; }
    var now = Date.now();
    if (!force && json === lastSent && now - lastSentAt < TICK_MS) return;
    lastSent = json;
    lastSentAt = now;
    try { sink.postMessage(json); } catch (e) {}
  }
  function soon() {
    if (pending === null) pending = setTimeout(function () { post(true); }, COALESCE_MS);
  }

  // Media events do not bubble, but they can be captured at the document.
  ['play', 'playing', 'pause', 'ended', 'loadedmetadata', 'durationchange', 'seeked', 'emptied']
    .forEach(function (type) { document.addEventListener(type, soon, true); });
  try {
    var proto = window.MediaSession && MediaSession.prototype;
    var desc = proto && Object.getOwnPropertyDescriptor(proto, 'metadata');
    if (session && desc && desc.set && desc.get) {
      Object.defineProperty(session, 'metadata', {
        configurable: true,
        get: function () { return desc.get.call(session); },
        set: function (v) { desc.set.call(session, v); soon(); }
      });
    }
  } catch (e) {}
  setInterval(function () { post(false); }, TICK_MS);

  function callHandler(action, details) {
    var fn = handlers[action];
    if (!fn) return false;
    try { fn(details || { action: action }); return true; } catch (e) { return false; }
  }
  function key(spec) {
    try {
      var opts = { key: spec[0], code: spec[1], keyCode: spec[2], which: spec[2], bubbles: true };
      (document.activeElement || document.body).dispatchEvent(new KeyboardEvent('keydown', opts));
      return true;
    } catch (e) { return false; }
  }
  function play() {
    var el = media();
    if (el) { try { var p = el.play(); if (p && p.catch) p.catch(function () {}); return true; } catch (e) {} }
    return callHandler('play') || clickFirst(SEL.playPause);
  }
  function pause() {
    var el = media();
    if (el) { try { el.pause(); return true; } catch (e) {} }
    return callHandler('pause') || clickFirst(SEL.playPause);
  }
  function seek(seconds) {
    if (typeof seconds !== 'number' || !isFinite(seconds) || seconds < 0) return false;
    if (callHandler('seekto', { action: 'seekto', seekTime: seconds })) return true;
    var el = media();
    if (!el) return false;
    try { el.currentTime = seconds; return true; } catch (e) { return false; }
  }

  function playerPageOpen() { return !!first(SEL.playerPageOpen); }
  function closePlayerPage() { if (playerPageOpen()) clickFirst(SEL.playerPageClose); }
  function go(page) {
    try {
      if (page === 'queue') {
        if (playerPageOpen()) return true;
        if (!first(SEL.bar) || !media()) return false;
        return clickFirst(SEL.playerPageToggle);
      }
      if (page === 'library') { closePlayerPage(); return clickFirst(SEL.library); }
      if (page === 'search') {
        closePlayerPage();
        clickFirst(SEL.searchOpen);
        var input = first(SEL.searchInput);
        if (!input) return false;
        input.focus();
        return true;
      }
    } catch (e) {}
    return false;
  }

  var api = {
    cmd: function (name, arg) {
      var done = false;
      try {
        if (name === 'play') done = play();
        else if (name === 'pause') done = pause();
        else if (name === 'next') done = callHandler('nexttrack') || clickFirst(SEL.next) || key(KEYS.next);
        else if (name === 'previous') done = callHandler('previoustrack') || clickFirst(SEL.previous) || key(KEYS.previous);
        else if (name === 'seek') done = seek(arg);
      } catch (e) {}
      soon();
      return done;
    },
    go: go
  };
  try { Object.defineProperty(window, '__portalYtm', { value: Object.freeze(api) }); } catch (e) {}
  soon();
})();
