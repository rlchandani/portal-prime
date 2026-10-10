/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */
// Opt-in native Chrome DOM/layout/decode coverage. All resources are fulfilled in memory.
// No live server, existing browser profile, screenshots, DOM dumps, or latency benchmark.
import test from 'node:test';
import assert from 'node:assert/strict';
import { readFileSync } from 'node:fs';
import { execFileSync } from 'node:child_process';
import { createRequire } from 'node:module';
import { deflateSync } from 'node:zlib';
import { setTimeout as wait } from 'node:timers/promises';

const enabled = process.env.IMMORTAL_REMOTE_CHROME === '1';
const source = process.env.IMMORTAL_REMOTE_SOURCE || 'candidate';
const origin = 'https://remote-fixture.invalid';
const root = new URL('../../', import.meta.url);
const read = (path) => readFileSync(new URL(path, root), 'utf8');

function tinyPng() {
  const crc = (bytes) => {
    let value = 0xffffffff;
    for (const byte of bytes) {
      value ^= byte;
      for (let bit = 0; bit < 8; bit++)
        value = (value >>> 1) ^ (value & 1 ? 0xedb88320 : 0);
    }
    return (value ^ 0xffffffff) >>> 0;
  };
  const chunk = (type, data) => {
    const content = Buffer.concat([Buffer.from(type), data]);
    const size = Buffer.alloc(4), checksum = Buffer.alloc(4);
    size.writeUInt32BE(data.length);
    checksum.writeUInt32BE(crc(content));
    return Buffer.concat([size, content, checksum]);
  };
  const header = Buffer.alloc(13);
  header.writeUInt32BE(2, 0);
  header.writeUInt32BE(2, 4);
  header[8] = 8;
  header[9] = 6;
  const row = Buffer.from([0, 30, 80, 140, 255, 30, 80, 140, 255]);
  return Buffer.concat([
    Buffer.from([137, 80, 78, 71, 13, 10, 26, 10]),
    chunk('IHDR', header),
    chunk('IDAT', deflateSync(Buffer.concat([row, row]))),
    chunk('IEND', Buffer.alloc(0)),
  ]);
}

// Match the VM adapter's synthetic roster/status/media contract, using native browser APIs.
// The roster marker is synthetic and is only consumed by intercepted fixture requests.
function instrument({ origin }) {
  localStorage.setItem('immortal_remote_devices', JSON.stringify([
    { base: origin, name: 'Fixture room', token: 'fixture-only' },
  ]));
  localStorage.setItem('immortal_remote_active', '0');
  const work = {
    createdElements: 0, registeredListeners: 0, replacedPages: 0, pending: 0, pendingBodies: 0,
  };
  const create = document.createElement.bind(document);
  document.createElement = (...args) => {
    work.createdElements++;
    return create(...args);
  };
  const listen = EventTarget.prototype.addEventListener;
  EventTarget.prototype.addEventListener = function (...args) {
    if (this instanceof Element && this.ownerDocument === document) work.registeredListeners++;
    return listen.apply(this, args);
  };
  const replace = Element.prototype.replaceChildren;
  Element.prototype.replaceChildren = function (...args) {
    if (this.id === 'page') work.replacedPages++;
    return replace.apply(this, args);
  };
  const fetcher = window.fetch.bind(window);
  window.fetch = async (...args) => {
    work.pending++;
    try {
      const response = await fetcher(...args);
      for (const method of ['json', 'blob']) {
        const consume = response[method].bind(response);
        response[method] = async () => {
          work.pendingBodies++;
          try { return await consume(); }
          finally { work.pendingBodies--; }
        };
      }
      return response;
    }
    finally { work.pending--; }
  };
  window.__pollProbe = { work };
}

async function settle(page, gallery = true) {
  // Native fetch/blob/decode settle independently of the paused application clock.
  // This is a bounded condition wait, not a timing measurement.
  let previous = null;
  for (let attempt = 0; attempt < 150; attempt++) {
    const sample = await page.evaluate((gallery) => {
      const work = window.__pollProbe.work;
      const images = [...document.querySelectorAll('.apps-list .icon img')];
      const ready = work.pending === 0 && work.pendingBodies === 0 &&
        document.getElementById('connection').textContent.includes('Synthetic Portal') && (!gallery ||
        (images.length === 24 && images.every((img) =>
          img.complete && img.naturalWidth === 2 && img.naturalHeight === 2)));
      return { ready, work: { ...work } };
    }, gallery);
    const fingerprint = JSON.stringify(sample.work);
    if (sample.ready && fingerprint === previous) {
      await page.evaluate(async () => {
        await Promise.all([...document.querySelectorAll('.apps-list .icon img')].map((img) => img.decode()));
      });
      return;
    }
    previous = sample.ready ? fingerprint : null;
    await wait(20);
  }
  assert.fail('fixture_native_settle_timeout');
}

async function capture(page, counts) {
  await page.evaluate(() => {
    const probe = window.__pollProbe;
    probe.start = {
      work: { ...probe.work },
      root: document.getElementById('page').firstElementChild,
      list: document.querySelector('.apps-list'),
      icons: [...document.querySelectorAll('.apps-list .icon img')],
    };
  });
  return { ...counts };
}

async function result(page, counts, before) {
  const work = await page.evaluate(() => {
    const { work, start } = window.__pollProbe;
    const icons = [...document.querySelectorAll('.apps-list .icon img')];
    return {
      createdElements: work.createdElements - start.work.createdElements,
      registeredListeners: work.registeredListeners - start.work.registeredListeners,
      replacedPages: work.replacedPages - start.work.replacedPages,
      rootRetained: document.getElementById('page').firstElementChild === start.root,
      listRetained: document.querySelector('.apps-list') === start.list,
      iconsRetained: icons.length === start.icons.length &&
        icons.every((img, i) => img === start.icons[i] && img.isConnected),
      decodedIcons: icons.filter((img) => img.complete && img.naturalWidth === 2 &&
        img.naturalHeight === 2).length,
    };
  });
  return {
    ...work,
    operationsReads: counts.operations - before.operations,
    iconReads: counts.icons - before.icons,
    appReads: counts.apps - before.apps,
    catalogReads: counts.catalog - before.catalog,
  };
}

async function mobileBounds(page) {
  return page.evaluate(() => {
    const rows = [...document.querySelectorAll('.app-row')];
    const controls = [...document.querySelectorAll('.apps-list button')];
    const rects = rows.map((row) => row.getBoundingClientRect());
    const fits = (child, parent) => {
      const a = child.getBoundingClientRect(), b = parent.getBoundingClientRect();
      return a.width > 0 && a.height > 0 && a.left >= b.left - 0.5 &&
        a.right <= b.right + 0.5 && a.top >= b.top - 0.5 && a.bottom <= b.bottom + 0.5;
    };
    const childrenFit = rows.every((row) => [...row.children].every((child) => fits(child, row)));
    let unobstructed = 0;
    for (const button of controls) {
      button.scrollIntoView({ block: 'center', inline: 'nearest', behavior: 'instant' });
      const box = button.getBoundingClientRect();
      const hit = document.elementFromPoint(box.left + box.width / 2, box.top + box.height / 2);
      if ((hit === button || button.contains(hit)) && box.top >= 0 &&
          box.bottom <= innerHeight && box.left >= 0 && box.right <= innerWidth) unobstructed++;
    }
    const buttonRects = controls.map((button) => button.getBoundingClientRect());
    window.scrollTo({ top: 0, behavior: 'instant' });
    return {
      viewportWidth: innerWidth,
      viewportHeight: innerHeight,
      documentWidth: document.documentElement.scrollWidth,
      rows: rows.length,
      controls: controls.length,
      minRowLeft: Math.min(...rects.map((box) => box.left)),
      maxRowRight: Math.max(...rects.map((box) => box.right)),
      minControlWidth: Math.min(...buttonRects.map((box) => box.width)),
      minControlHeight: Math.min(...buttonRects.map((box) => box.height)),
      horizontalOverflow: document.documentElement.scrollWidth > innerWidth,
      rowsFit: rects.every((box) => box.left >= 0 && box.right <= innerWidth && box.width > 0),
      childrenFit,
      controlsUnclipped: controls.every((button) => button.scrollWidth <= button.clientWidth &&
        button.scrollHeight <= button.clientHeight),
      unobstructedControls: unobstructed,
    };
  });
}

test('synthetic Chrome mobile operations polls retain native app/icon nodes and recover rendered state', {
  skip: enabled ? false : 'Set IMMORTAL_REMOTE_CHROME=1 for isolated synthetic Chrome; no browser dependency loaded.',
  timeout: 45000,
}, async (t) => {
  assert.ok(['baseline', 'candidate'].includes(source), 'fixture_source_invalid');
  const require = createRequire(import.meta.url);
  const { chromium } = require('playwright');
  assert.equal(require('playwright/package.json').version, '1.62.1', 'fixture_playwright_version');
  const controller = source === 'baseline'
    ? execFileSync('git', ['show', '4d512a5:app/src/main/assets/remote/control-center.js'],
      { cwd: root, encoding: 'utf8' })
    : read('app/src/main/assets/remote/control-center.js');
  const html = read('app/src/main/java/com/immortal/launcher/RemoteHtml.kt').match(/"""([\s\S]*?)"""/)[1].trim();
  const core = read('app/src/main/assets/remote/core.mjs');
  const css = read('app/src/main/assets/remote/control-center.css');
  const png = tinyPng();
  const apps = Array.from({ length: 24 }, (_, i) => ({
    packageName: `com.fixture.app${i}`, label: `Fixture app ${i}`, versionName: '1.0',
    description: 'Synthetic offline app metadata for bounded layout checks.',
    installed: true, compatible: true, updateAvailable: true,
  }));
  const packages = new Set(apps.map((app) => app.packageName));
  const fixture = { operations: [], error: false };
  const counts = { operations: 0, icons: 0, apps: 0, catalog: 0 };
  let unknownRequests = 0, blockedSockets = 0, pageErrors = 0, browser;
  try {
    // launch() creates a new ephemeral profile; no connection to existing Chrome sessions.
    browser = await chromium.launch({
      executablePath: '/Applications/Google Chrome.app/Contents/MacOS/Google Chrome',
      headless: true,
      timeout: 15000,
      args: ['--disable-background-networking', '--disable-component-update', '--no-proxy-server',
        '--host-resolver-rules=MAP * ~NOTFOUND'],
    });
  } catch {
    assert.fail('isolated_chrome_launch_failed');
  }
  try {
    const context = await browser.newContext({
      viewport: { width: 402, height: 874 }, deviceScaleFactor: 1,
      isMobile: true, hasTouch: true, colorScheme: 'light', reducedMotion: 'reduce',
      serviceWorkers: 'block', permissions: [],
    });
    await context.routeWebSocket('**/*', (socket) => { blockedSockets++; socket.close(); });
    await context.route('**/*', async (route) => {
      const request = route.request(), url = new URL(request.url());
      const body = (value, status = 200) => route.fulfill({
        status, contentType: 'application/json', body: JSON.stringify(value),
      });
      if (url.origin !== origin || request.method() !== 'GET') {
        unknownRequests++;
        return route.abort();
      }
      switch (url.pathname) {
        case '/remote/ui': return route.fulfill({ contentType: 'text/html', body: html });
        case '/remote/assets/control-center.js':
          return route.fulfill({ contentType: 'text/javascript', body: controller });
        case '/remote/assets/core.mjs':
          return route.fulfill({ contentType: 'text/javascript', body: core });
        case '/remote/assets/control-center.css':
          return route.fulfill({ contentType: 'text/css', body: css });
        case '/remote/manifest.webmanifest':
          return body({ name: 'Fixture', start_url: '/remote/ui', display: 'standalone' });
        case '/favicon.ico':
        case '/remote/app-icon': return route.fulfill({ contentType: 'image/png', body: png });
        case '/remote/companion': return body({
          ok: true, protocolVersion: 1, deviceId: 'fixture-device', name: 'Fixture room',
          model: 'Synthetic Portal', app: { versionName: 'fixture' },
          capabilities: { layout: true, snapshot: true, catalog: true, operations: true,
            scenes: true, jarvis: true, photos: true, photoLibrary: true },
        });
        case '/remote/nowplaying':
          return body({ ok: true, np: { active: false, volume: { level: 4, max: 15, canSet: true } } });
        case '/remote/roster': return body({ ok: true, roster: [] });
        case '/remote/apps': counts.apps++; return body({ ok: true, apps });
        case '/remote/catalog': counts.catalog++; return body({ ok: true, apps });
        case '/remote/operations':
          counts.operations++;
          return fixture.error
            ? body({ ok: false, error: 'unreachable' }, 503)
            : body({ ok: true, operations: fixture.operations });
        case '/remote/icon':
          if (url.searchParams.size === 1 && packages.has(url.searchParams.get('pkg'))) {
            counts.icons++;
            return route.fulfill({ contentType: 'image/png', body: png });
          }
      }
      unknownRequests++;
      return route.abort();
    });
    await context.addInitScript(instrument, { origin });
    const page = await context.newPage();
    page.on('pageerror', () => { pageErrors++; });
    page.setDefaultTimeout(5000);
    await page.clock.install({ time: 0 });
    await page.clock.pauseAt(1000);
    await page.goto(`${origin}/remote/ui`, { waitUntil: 'load' });
    await settle(page, false);
    await page.evaluate(() => document.querySelectorAll('#navigation button')[3].click());
    await settle(page);
    const bounds = await mobileBounds(page);
    const before = await capture(page, counts);
    await page.clock.runFor(3999);
    const noEarlyPoll = counts.operations === before.operations;
    await page.clock.runFor(1);
    await settle(page);
    for (let i = 0; i < 2; i++) {
      await page.clock.runFor(4000);
      await settle(page);
    }
    const idle = await result(page, counts, before);
    const poll = async (gallery = true) => {
      const previous = counts.operations;
      await page.clock.runFor(4000);
      await settle(page, gallery);
      assert.equal(counts.operations, previous + 1, 'fixture_poll_cadence');
    };
    await page.evaluate(() => {
      document.querySelectorAll('.segmented[aria-label="App list"] button')[1].click();
    });
    await settle(page);
    const catalogBounds = await mobileBounds(page);
    const pendingBefore = await capture(page, counts);
    fixture.operations = [{
      id: 'fixture-operation', type: 'install', target: { packageName: apps[0].packageName },
      state: 'running', results: [], updatedAt: 1000, retrySupported: false, cancelSupported: false,
    }];
    await page.evaluate(() => document.querySelector('[data-focus="apps-search"]').focus());
    await poll();
    const focused = await result(page, counts, pendingBefore);
    await page.evaluate(() => document.activeElement.blur());
    await poll();
    const pending = await result(page, counts, pendingBefore);
    const pendingVisible = await page.evaluate(() => {
      const card = document.querySelector('.apps-list > section');
      return card.textContent.includes('Running') &&
        [...card.querySelectorAll('button')].some((button) => button.textContent === 'Update' && button.disabled);
    });
    const errorBefore = await capture(page, counts);
    fixture.error = true;
    await poll();
    const firstError = await result(page, counts, errorBefore);
    const repeatedBefore = await capture(page, counts);
    for (let i = 0; i < 3; i++) await poll();
    const repeatedError = await result(page, counts, repeatedBefore);
    const recoveryBefore = await capture(page, counts);
    fixture.error = false;
    await poll();
    const recovery = await result(page, counts, recoveryBefore);
    const completedBefore = await capture(page, counts);
    fixture.operations = [{ ...fixture.operations[0], state: 'succeeded',
      results: [{ ok: true, result: 'installed' }] }];
    await poll();
    const completed = await result(page, counts, completedBefore);
    const completedVisible = await page.evaluate(() =>
      document.querySelector('.apps-list > section').textContent.includes('Finished'));
    await page.evaluate(() => {
      [...document.querySelectorAll('button.action-row')].find((button) =>
        button.querySelector('strong')?.textContent === 'Activity').click();
    });
    await settle(page, false);
    const activityBefore = await capture(page, counts);
    fixture.error = true;
    await poll(false);
    const activityFirstError = await result(page, counts, activityBefore);
    const errorVisible = await page.evaluate(() =>
      document.getElementById('page').textContent.includes('Activity unavailable'));
    const activityRepeatedBefore = await capture(page, counts);
    for (let i = 0; i < 3; i++) await poll(false);
    const activityRepeatedError = await result(page, counts, activityRepeatedBefore);
    const activityRecoveryBefore = await capture(page, counts);
    fixture.error = false;
    await poll(false);
    const activityRecovery = await result(page, counts, activityRecoveryBefore);
    const recoveredVisible = await page.evaluate(() => {
      const page = document.getElementById('page');
      return !page.textContent.includes('Activity unavailable') && page.textContent.includes('Finished');
    });
    const report = {
      chromeVersion: browser.version().split('.').map(Number),
      clockControlled: true, pngBytes: png.length, noEarlyPoll, bounds, catalogBounds,
      idle, focused, pending, pendingVisible, firstError, repeatedError, recovery,
      completed, completedVisible, activityFirstError, errorVisible, activityRepeatedError,
      activityRecovery, recoveredVisible, unknownRequests, blockedSockets, pageErrors,
    };
    t.diagnostic(JSON.stringify({ [source]: report }));
    assert.equal(unknownRequests, 0, 'fixture_unknown_requests');
    assert.equal(blockedSockets, 0, 'fixture_unexpected_websockets');
    assert.equal(pageErrors, 0, 'fixture_page_errors');
    assert.equal(noEarlyPoll, true, 'fixture_no_poll_before_4000ms');
    for (const check of [bounds, catalogBounds]) {
      assert.equal(check.viewportWidth, 402, 'fixture_viewport_width');
      assert.equal(check.viewportHeight, 874, 'fixture_viewport_height');
      assert.equal(check.rows, 24, 'fixture_app_rows');
      assert.equal(check.horizontalOverflow, false, 'fixture_horizontal_overflow');
      assert.equal(check.rowsFit && check.childrenFit && check.controlsUnclipped, true, 'fixture_row_fit');
      assert.equal(check.unobstructedControls, check.controls, 'fixture_controls_unobstructed');
      assert.ok(check.minControlWidth >= 44 && check.minControlHeight >= 44, 'fixture_control_bounds');
    }
    assert.equal(bounds.controls, 24, 'fixture_installed_controls');
    assert.equal(catalogBounds.controls, 48, 'fixture_catalog_controls');
    assert.equal(focused.rootRetained && focused.listRetained && focused.iconsRetained, true, 'fixture_focused_state');
    assert.equal(pending.replacedPages > 0 && pendingVisible, true, 'fixture_pending_rendered');
    assert.equal(firstError.replacedPages > 0 && recovery.replacedPages > 0, true, 'fixture_apps_error_recovery');
    assert.equal(completed.catalogReads, 1, 'fixture_install_completion_refresh');
    assert.equal(completedVisible, true, 'fixture_install_completion_rendered');
    assert.equal(activityFirstError.replacedPages > 0 && errorVisible, true, 'fixture_activity_error_rendered');
    assert.equal(activityRecovery.replacedPages > 0 && recoveredVisible, true, 'fixture_activity_recovered');
    for (const unchanged of [idle, repeatedError, activityRepeatedError]) {
      assert.equal(unchanged.operationsReads, 3, 'fixture_three_poll_reads');
      assert.equal(unchanged.iconReads, 0, 'unchanged_polls_no_icon_reads');
      assert.equal(unchanged.replacedPages, 0, 'unchanged_polls_no_page_replacements');
      assert.equal(unchanged.createdElements, 0, 'unchanged_polls_no_element_creation');
      assert.equal(unchanged.registeredListeners, 0, 'unchanged_polls_no_listener_registration');
      assert.equal(unchanged.rootRetained && unchanged.listRetained && unchanged.iconsRetained, true,
        'unchanged_polls_native_node_identity');
    }
    assert.equal(idle.decodedIcons, 24, 'fixture_native_png_decode');
  } finally {
    await browser.close();
  }
});
