/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */
// Opt-in production UI with generated files and intercepted HTTP boundaries.
import test from 'node:test';
import assert from 'node:assert/strict';
import { readFileSync, mkdirSync, writeFileSync } from 'node:fs';
import { createRequire } from 'node:module';
import { createHash } from 'node:crypto';

const enabled = process.env.IMMORTAL_REMOTE_CHROME === '1';
const root = new URL('../../', import.meta.url);
const read = path => readFileSync(new URL(path, root), 'utf8');
const shellOrigin = 'https://group-photo-fixture.invalid';
const targets = [
  { base: shellOrigin, name: 'Generated A', token: 'generated-session-a' },
  { base: 'https://group-photo-peer.invalid', name: 'Generated B', token: 'generated-session-b' },
];
const output = process.env.IMMORTAL_REMOTE_QA_OUTPUT;
const generatedFile = name => ({ name, mimeType: 'image/png',
  buffer: Buffer.from(`generated-only-browser-payload:${name}`) });

test('production group photo import browser workflow and sheet geometry', {
  skip: enabled ? false : 'Set IMMORTAL_REMOTE_CHROME=1 for isolated generated Chromium QA.',
  timeout: 120000,
}, async t => {
  const startedAtUtc = new Date().toISOString();
  const { chromium } = createRequire(import.meta.url)('playwright');
  const html = read('app/src/main/java/com/immortal/launcher/RemoteHtml.kt')
    .match(/"""([\s\S]*?)"""/)[1].trim();
  const assets = Object.fromEntries(['control-center.js', 'core.mjs', 'control-center.css']
    .map(name => [`/remote/assets/${name}`, read(`app/src/main/assets/remote/${name}`)]));
  const browser = await chromium.launch({
    headless: true,
    executablePath: process.env.IMMORTAL_REMOTE_BROWSER_PATH || (process.platform === 'darwin'
      ? '/Applications/Google Chrome.app/Contents/MacOS/Google Chrome' : undefined),
    timeout: 15000,
    args: ['--disable-background-networking', '--disable-component-update', '--no-proxy-server',
      '--host-resolver-rules=MAP * ~NOTFOUND'],
  });
  const reports = [];
  if (output) mkdirSync(output, { recursive: true });
  async function fixture(run, { width = 390, theme = 'light', font = 100,
    hold = false, failureModes = false, missingIdentity = false } = {}) {
    const f = { posts: [], pending: [], outside: [], unexpected: [], errors: [], websockets: [],
      reads: [], geometry: [], screenshots: [], selectedFileCount: 0 };
    const libraries = new Map(targets.map((target, i) => [target.base, [
      { path: `/sdcard/Pictures/generated-${i}-existing.png`, name: `generated-${i}-existing.png`,
        size: 4, modified: 1 },
    ]]));
    const context = await browser.newContext({
      viewport: { width, height: 844 }, isMobile: true, hasTouch: true,
      colorScheme: theme, serviceWorkers: 'block', reducedMotion: 'reduce',
    });
    const cors = { 'access-control-allow-origin': shellOrigin,
      'access-control-allow-headers': 'Authorization,Content-Type',
      'access-control-allow-methods': 'GET,POST,OPTIONS' };
    await context.routeWebSocket('**/*', socket => {
      f.websockets.push(socket.url()); socket.close();
    });
    await context.route('**/*', async route => {
      const request = route.request(), url = new URL(request.url());
      const target = targets.find(item => item.base === url.origin);
      if (!target) { f.outside.push(request.url()); return route.abort(); }
      const fulfill = (body, status = 200) => route.fulfill({ status,
        headers: cors, contentType: 'application/json', body: JSON.stringify(body) });
      if (request.method() === 'OPTIONS') return route.fulfill({ status: 204, headers: cors });
      if (url.origin === shellOrigin && url.pathname === '/remote/ui' && request.method() === 'GET')
        return route.fulfill({ contentType: 'text/html', body: html });
      if (url.origin === shellOrigin && assets[url.pathname] && request.method() === 'GET')
        return route.fulfill({ contentType: url.pathname.endsWith('.css') ? 'text/css'
          : 'text/javascript', body: assets[url.pathname] });
      if (url.pathname === '/remote/manifest.webmanifest')
        return route.fulfill({ contentType: 'application/manifest+json', body: '{}' });
      if (url.pathname === '/remote/app-icon') return route.fulfill({ status: 204 });
      if (url.pathname === '/remote/companion' && request.method() === 'GET')
        return fulfill({ ok: true, protocolVersion: 1,
          deviceId: missingIdentity ? undefined : `generated-device-${targets.indexOf(target)}`,
          name: target.name, model: 'Generated Portal',
          capabilities: { photos: true, photoLibrary: true },
          app: { versionName: 'generated' } });
      if (url.pathname === '/remote/roster' && request.method() === 'GET')
        return fulfill({ ok: true, roster: [] });
      if (url.pathname === '/remote/nowplaying' && request.method() === 'GET')
        return fulfill({ ok: true, np: { active: false } });
      if (url.pathname === '/remote/photos' && request.method() === 'GET') {
        const path = url.searchParams.get('path'), recursive = url.searchParams.get('recursive') === '1';
        f.reads.push({ base: target.base, path, recursive });
        return fulfill({ ok: true, path, recursive, truncated: false, freeBytes: 1048576,
          files: libraries.get(target.base).filter(file => recursive ||
            file.path.slice(0, file.path.lastIndexOf('/')) === path), folders: [] });
      }
      if (url.pathname === '/remote/photo' && request.method() === 'GET')
        return route.fulfill({ status: 200, headers: cors, contentType: 'image/png',
          body: Buffer.from('generated-thumbnail-boundary') });
      if (url.pathname === '/remote/photo-upload' && request.method() === 'POST') {
        const name = url.searchParams.get('name'), folder = url.searchParams.get('path');
        const path = `${folder}/${name}`, bytes = request.postDataBuffer()?.length ?? 0;
        f.posts.push({ base: target.base, name, folder, path, bytes });
        const finish = async () => {
          if (failureModes && target === targets[0] && name === 'collision.png')
            return fulfill({ ok: false, error: 'file_exists' }, 409);
          libraries.get(target.base).push({ path, name, size: bytes, modified: 2 });
          if (failureModes && target === targets[1] && name === 'uncertain.png')
            return route.fulfill({ status: 200, headers: cors,
              contentType: 'application/json', body: '{"ok":true,"path":' });
          return fulfill({ ok: true, path, bytes });
        };
        if (hold && name === 'first.png') { f.pending.push(finish); return; }
        return finish();
      }
      f.unexpected.push({ url: request.url(), method: request.method() });
      return route.abort();
    });
    await context.addInitScript(({ saved, mode, rootFont }) => {
      localStorage.clear();
      localStorage.setItem('immortal_remote_devices', JSON.stringify(saved));
      localStorage.setItem('immortal_remote_active', '0');
      localStorage.setItem('immortal_remote_active_base', saved[0].base);
      localStorage.setItem('immortal_remote_theme', mode);
      document.addEventListener('DOMContentLoaded', () => {
        document.documentElement.style.fontSize = `${16 * rootFont / 100}px`;
      });
    }, { saved: targets, mode: theme, rootFont: font });
    const page = await context.newPage();
    page.setDefaultTimeout(5000);
    page.on('pageerror', error => f.errors.push(error.message));
    const waitPosts = async count => {
      await page.waitForFunction(() => document.querySelector('#upload-status'),
        undefined, { timeout: 5000 });
      const end = Date.now() + 5000;
      while (f.posts.length < count && Date.now() < end)
        await new Promise(resolve => setTimeout(resolve, 10));
      assert.equal(f.posts.length, count);
    };
    const selectFiles = async names => {
      f.selectedFileCount = names.length;
      await page.getByRole('button', { name: 'Import photos', exact: true }).click();
      const sheet = page.getByRole('dialog', { name: 'Import photos', exact: true });
      await sheet.getByLabel('Choose photos', { exact: true }).setInputFiles(names.map(generatedFile));
      return sheet;
    };
    const chooseBoth = async sheet => {
      await sheet.getByRole('button', { name: 'Select both Portals', exact: true }).click();
      await sheet.getByLabel('Generated A destination folder', { exact: true }).fill('/sdcard/Pictures/A');
      await sheet.getByLabel('Generated B destination folder', { exact: true }).fill('/sdcard/Pictures/B');
      const action = await sheet.locator('#sheet-actions button').innerText();
      assert.match(action, new RegExp(`${f.selectedFileCount} photos?`, 'i'),
        'approved primary action must name the selected photo count');
      assert.match(action, /2 Portals?/i,
        'approved primary action must name the selected destination count');
    };
    const start = async sheet => {
      await sheet.getByRole('button', { name: /^Start import$|^Send |^Import \d/ }).click();
    };
    const done = async () => {
      await page.getByRole('button', { name: 'Dismiss results', exact: true }).waitFor();
    };
    try {
      await page.goto(`${shellOrigin}/remote/ui`);
      await page.getByRole('button', { name: 'Photos', exact: true }).click();
      await page.locator('button.photo-tile').first().waitFor();
      await run({ f, page, selectFiles, chooseBoth, start, waitPosts, done, context });
    } finally {
      for (const finish of f.pending.splice(0)) await finish().catch(() => {});
      reports.push({ posts: f.posts, reads: f.reads, geometry: f.geometry,
        screenshots: f.screenshots, errors: f.errors, outside: f.outside,
        unexpected: f.unexpected, websockets: f.websockets });
      await context.close();
      assert.deepEqual(f.errors, [], 'production page script errors');
      assert.deepEqual(f.outside, [], 'outside fixture requests');
      assert.deepEqual(f.unexpected, [], 'undeclared fixture requests');
      assert.deepEqual(f.websockets, [], 'WebSocket requests');
    }
  }
  try {
    await t.test('missing stable identity cannot send a selected file', async () => {
      await fixture(async ({ f, page, selectFiles, start }) => {
        const sheet = await selectFiles(['identity.png']);
        await start(sheet);
        await page.locator('#notice').filter({ hasText: /identity|verified/i }).waitFor();
        assert.equal(f.posts.length, 0);
        assert.equal(await sheet.isVisible(), true);
      }, { missingIdentity: true });
    });
    await t.test('two captured folders continue after changing the viewed Portal', async () => {
      await fixture(async ({ f, page, selectFiles, chooseBoth, start, waitPosts, done }) => {
        const sheet = await selectFiles(['first.png', 'second.png']);
        await chooseBoth(sheet); await start(sheet); await waitPosts(2);
        await page.locator('#room-button').click();
        await page.getByRole('button', { name: 'Select', exact: true }).click();
        await page.locator('button.photo-tile').filter({ hasText: 'generated-1-existing.png' }).waitFor();
        for (const finish of f.pending.splice(0)) await finish();
        await waitPosts(4); await done();
        assert.equal(f.posts.filter(post => post.base === targets[0].base).length, 2);
        assert.equal(f.posts.filter(post => post.base === targets[1].base).length, 2);
        assert.ok(f.posts.every(post => post.folder === (post.base === targets[0].base
          ? '/sdcard/Pictures/A' : '/sdcard/Pictures/B')));
        assert.match(await page.locator('#upload-status').innerText(), /4 of 4/);
        assert.equal(await page.locator('button.photo-tile')
          .filter({ hasText: 'generated-0-existing.png' }).count(), 0);
      }, { hold: true });
    });
    await t.test('explicit stop leaves queued copies unsent and lets in-flight acknowledgments settle', async () => {
      await fixture(async ({ f, page, selectFiles, chooseBoth, start, waitPosts, done }) => {
        const sheet = await selectFiles(['first.png', 'second.png']);
        await chooseBoth(sheet); await start(sheet); await waitPosts(2);
        await page.getByRole('button', { name: /^Cancel remaining uploads$|^Stop remaining imports$/ }).click();
        for (const finish of f.pending.splice(0)) await finish();
        await done();
        assert.equal(f.posts.length, 2);
        const text = await page.locator('#upload-status').innerText();
        assert.match(text, /2 of 4/);
        assert.equal((text.match(/Not sent/g) || []).length, 2);
        assert.equal((text.match(/Stored and confirmed/g) || []).length, 2);
      }, { hold: true });
    });
    await t.test('retry excludes uncertainty and collision rename sends only the selected failed copy', async () => {
      await fixture(async ({ f, page, selectFiles, chooseBoth, start, waitPosts, done }) => {
        const sheet = await selectFiles(['collision.png', 'uncertain.png']);
        await chooseBoth(sheet); await start(sheet); await waitPosts(4); await done();
        assert.match(await page.locator('#upload-status').innerText(), /Completion unconfirmed/);
        await page.getByRole('button', { name: /^Retry definite failures$|^Retry failed imports$/ }).click();
        await waitPosts(5); await done();
        assert.equal(f.posts.filter(post => post.base === targets[1].base &&
          post.name === 'uncertain.png').length, 1);
        await page.getByRole('button', { name: 'Rename collision.png for Generated A', exact: true }).click();
        await page.getByLabel('New filename', { exact: true }).fill('renamed.png');
        await page.getByRole('button', { name: 'Rename and retry this copy', exact: true }).click();
        await waitPosts(6); await done();
        assert.deepEqual(f.posts.at(-1), { base: targets[0].base, name: 'renamed.png',
          folder: '/sdcard/Pictures/A', path: '/sdcard/Pictures/A/renamed.png',
          bytes: generatedFile('collision.png').buffer.length });
        assert.match(await page.locator('#upload-status').innerText(), /Completion unconfirmed/);
      }, { failureModes: true });
    });
    for (const width of [320, 390, 768]) for (const theme of ['light', 'dark'])
      for (const font of [100, 130]) await t.test(
        `eight-row selection/results and viewport footer ${width}px ${theme} ${font}%`, async () => {
          await fixture(async ({ f, page, selectFiles, chooseBoth, start, done }) => {
            const names = Array.from({ length: 9 }, (_, i) => `generated-file-${i}.png`);
            const sheet = await selectFiles(names); await chooseBoth(sheet);
            const selection = await sheet.innerText();
            assert.equal(names.filter(name => selection.includes(name)).length, 8);
            await sheet.getByRole('button', { name: 'Next files', exact: true }).click();
            const secondPage = await sheet.innerText();
            assert.equal(names.filter(name => secondPage.includes(name)).length, 1);
            await sheet.getByRole('button', { name: 'Previous files', exact: true }).click();
            const geometry = await page.evaluate(() => {
              const box = id => { const r = document.getElementById(id).getBoundingClientRect();
                return { top: r.top, bottom: r.bottom, left: r.left, right: r.right,
                  width: r.width, height: r.height }; };
              return { width: innerWidth, height: innerHeight,
                overflow: document.documentElement.scrollWidth > innerWidth,
                dialog: box('sheet'), actions: box('sheet-actions'),
                content: box('sheet-content') };
            });
            f.geometry.push({ width, theme, font, ...geometry });
            assert.equal(geometry.overflow, false);
            assert.ok(geometry.actions.top >= 0 && geometry.actions.bottom <= geometry.height,
              JSON.stringify(geometry));
            assert.ok(geometry.dialog.left >= 0 && geometry.dialog.right <= geometry.width,
              JSON.stringify(geometry));
            if (output && ((width === 320 && font === 130) || (width === 390 && font === 100))) {
              const path = `${output}/group-photo-${width}-${theme}-${font}.png`;
              await page.screenshot({ path }); f.screenshots.push(path);
            }
            await start(sheet); await done();
            assert.equal(f.posts.length, 18);
            assert.equal(await page.locator('[data-upload-result]').count(), 8);
            await page.getByRole('button', { name: 'Next results', exact: true }).click();
            assert.equal(await page.locator('[data-upload-result]').count(), 8);
            await page.getByRole('button', { name: 'Next results', exact: true }).click();
            assert.equal(await page.locator('[data-upload-result]').count(), 2);
          }, { width, theme, font });
        });
  } finally {
    await browser.close();
    if (output) writeFileSync(`${output}/browser-observations.json`, JSON.stringify({
      startedAtUtc, endedAtUtc: new Date().toISOString(),
      scope: 'Actual bundled UI; generated payloads and intercepted responses only; no server/device transfer.',
      sourceHashes: Object.fromEntries(Object.entries(assets).map(([path, source]) => [
        path, createHash('sha256').update(source).digest('hex'),
      ])),
      cases: reports.length, reports,
    }, null, 2) + '\n');
  }
});
