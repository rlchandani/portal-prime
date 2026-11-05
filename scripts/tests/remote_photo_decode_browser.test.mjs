/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */
// Opt-in Chrome with actual remote assets and generated image responses only.
import test from 'node:test';
import assert from 'node:assert/strict';
import { readFileSync } from 'node:fs';
import { createRequire } from 'node:module';
import { fileURLToPath } from 'node:url';
import { resolve } from 'node:path';

const enabled = process.env.IMMORTAL_REMOTE_CHROME === '1';
const root = new URL('../../', import.meta.url);
const directory = process.env.IMMORTAL_REMOTE_ASSET_DIR ||
  fileURLToPath(new URL('app/src/main/assets/remote/', root));
const origin = 'https://photo-decode-fixture.invalid';
const html = readFileSync(new URL('app/src/main/java/com/immortal/launcher/RemoteHtml.kt', root), 'utf8')
  .match(/"""([\s\S]*?)"""/)[1].trim();
const assets = Object.fromEntries(['control-center.js', 'core.mjs', 'control-center.css'].map(name => [
  `/remote/assets/${name}`, readFileSync(resolve(directory, name), 'utf8'),
]));
const validPng = Buffer.from(
  'iVBORw0KGgoAAAANSUhEUgAAAAIAAAACCAYAAABytg0kAAAAEUlEQVR4nGP4z8DwH4QZYAwAR8oH+WdZbrcAAAAASUVORK5CYII=',
  'base64'); // Generated 2x2 red RGBA PNG with valid chunk checksums.
const invalidPng = Buffer.from('generated-nonempty-invalid-png');

for (const mode of ['gallery', 'modal']) {
  test(`${mode} reports browser image decode failure and recovers with a valid preview`, {
    skip: enabled ? false : 'Set IMMORTAL_REMOTE_CHROME=1 for isolated generated Chrome.',
    timeout: 30000,
  }, async t => {
    const { chromium } = createRequire(import.meta.url)('playwright');
    const f = { invalid: mode === 'gallery', photos: [], reads: [], posts: [],
      outsideRequests: 0, pageErrors: [], decodeErrors: [] };
    const file = { path: '/sdcard/Pictures/generated.png', name: 'generated.png', size: 70, modified: 1 };
    const browser = await chromium.launch({ headless: true,
      executablePath: '/Applications/Google Chrome.app/Contents/MacOS/Google Chrome',
      timeout: 15000, args: ['--disable-background-networking', '--disable-component-update',
        '--no-proxy-server', '--host-resolver-rules=MAP * ~NOTFOUND'] });
    try {
      const context = await browser.newContext({ viewport: { width: 402, height: 874 },
        isMobile: true, hasTouch: true, serviceWorkers: 'block', reducedMotion: 'reduce' });
      await context.routeWebSocket('**/*', socket => socket.close());
      await context.route('**/*', async route => {
        const request = route.request(), url = new URL(request.url());
        const json = value => route.fulfill({ contentType: 'application/json', body: JSON.stringify(value) });
        if (url.origin !== origin) { f.outsideRequests++; return route.abort(); }
        if (request.method() !== 'GET') {
          f.posts.push({ method: request.method(), path: url.pathname }); return route.abort();
        }
        f.reads.push(url.pathname);
        if (url.pathname === '/remote/ui') return route.fulfill({ contentType: 'text/html', body: html });
        if (assets[url.pathname]) return route.fulfill({ contentType: url.pathname.endsWith('.css')
          ? 'text/css' : 'text/javascript', body: assets[url.pathname] });
        if (url.pathname === '/remote/companion') return json({ ok: true, protocolVersion: 1,
          deviceId: 'generated-photo-device', name: 'Generated room', model: 'Generated Portal',
          capabilities: { photos: true, photoLibrary: true }, app: { versionName: 'generated' } });
        if (url.pathname === '/remote/photos') return json({ ok: true,
          path: url.searchParams.get('path'), recursive: url.searchParams.get('recursive') === '1',
          truncated: false, freeBytes: 1048576, files: [file], folders: [] });
        if (url.pathname === '/remote/photo') {
          assert.equal(url.searchParams.get('path'), file.path);
          f.photos.push({ invalid: f.invalid, status: 200, contentType: 'image/png' });
          return route.fulfill({ status: 200, contentType: 'image/png',
            body: f.invalid ? invalidPng : validPng });
        }
        if (url.pathname === '/remote/nowplaying') return json({ ok: true, np: { active: false } });
        if (url.pathname === '/remote/roster') return json({ ok: true, roster: [] });
        return json({ ok: true });
      });
      await context.addInitScript(origin => {
        localStorage.setItem('immortal_remote_devices', JSON.stringify([
          { base: origin, name: 'Generated room', token: 'generated-token-only' }]));
        localStorage.setItem('immortal_remote_active', '0');
        // Observe actual native decode errors before any target onerror handler.
        window.__fixtureDecodeErrors = [];
        document.addEventListener('error', event => {
          const image = event.target;
          if (!(image instanceof HTMLImageElement) || !image.src.startsWith('blob:')) return;
          window.__fixtureDecodeErrors.push({ scope: image.closest('.photo-tile') ? 'gallery' : 'modal',
            complete: image.complete, naturalWidth: image.naturalWidth });
        }, true);
      }, origin);
      const page = await context.newPage();
      page.setDefaultTimeout(5000);
      page.on('pageerror', error => f.pageErrors.push(error.message));
      await page.goto(`${origin}/remote/ui`);
      await page.getByRole('button', { name: 'Photos', exact: true }).click();
      const tile = page.getByRole('button', { name: 'Preview generated.png', exact: true });
      await tile.waitFor();
      if (mode === 'modal') {
        await page.waitForFunction(() => {
          const image = document.querySelector('.photo-tile img');
          return image?.complete && image.naturalWidth > 0;
        });
        f.invalid = true;
        await tile.click();
      }
      await page.waitForFunction(mode => window.__fixtureDecodeErrors.some(
        error => error.scope === mode && error.complete && error.naturalWidth === 0), mode);
      f.decodeErrors = await page.evaluate(() => window.__fixtureDecodeErrors);
      const broken = mode === 'gallery' ? tile.locator('img') : page.locator('#sheet img.preview-image');
      assert.equal(await broken.evaluate(image => image.hidden), true,
        `${mode}: broken decoded image must be hidden`);
      if (mode === 'gallery') {
        const hint = tile.locator('.photo-image .detail');
        assert.equal(await hint.isVisible(), true);
        assert.match(await hint.textContent(), /Preview unavailable.*Tap to retry/i);
        f.invalid = false;
        await tile.click(); // Public tile recovery opens the actual preview, without a device call.
      } else {
        const sheet = page.locator('#sheet');
        assert.equal(await sheet.locator('.error').count(), 1);
        assert.match(await sheet.locator('.error').textContent(), /image|preview/i);
        const retry = sheet.getByRole('button', { name: 'Retry preview', exact: true });
        assert.equal(await retry.isVisible(), true);
        f.invalid = false;
        await retry.click();
      }
      await page.waitForFunction(() => {
        const image = document.querySelector('#sheet img.preview-image');
        return image?.complete && image.naturalWidth === 2 && !image.hidden;
      });
      const sheet = page.locator('#sheet');
      assert.equal(await sheet.locator('.error').count(), 0);
      assert.equal(await sheet.getByRole('button', { name: 'Retry preview', exact: true }).isVisible(), false);
      assert.equal(await sheet.getByText('Thumbnail from your Portal.', { exact: true }).isVisible(), true);
      assert.equal(f.photos.some(photo => photo.invalid), true);
      assert.equal(f.photos.some(photo => !photo.invalid), true);
      assert.deepEqual(f.posts, []);
      assert.equal(f.outsideRequests, 0);
      assert.deepEqual(f.pageErrors, []);
    } finally {
      t.diagnostic(JSON.stringify({ mode, ...f, scope: 'Generated HTTP200 image/png client decode only' }));
      await browser.close();
    }
  });
}
