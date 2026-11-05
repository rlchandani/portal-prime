/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */
// Opt-in fresh Chrome contexts, generated drafts and intercepted requests only.
import test from 'node:test';
import assert from 'node:assert/strict';
import { readFileSync } from 'node:fs';
import { createRequire } from 'node:module';
import { fileURLToPath } from 'node:url';
import { resolve } from 'node:path';

const enabled = process.env.IMMORTAL_REMOTE_CHROME === '1';
const root = new URL('../../', import.meta.url);
const assetDirectory = process.env.IMMORTAL_REMOTE_ASSET_DIR ||
  fileURLToPath(new URL('app/src/main/assets/remote/', root));
const origin = 'https://remote-step-fixture.invalid';
const html = readFileSync(new URL('app/src/main/java/com/immortal/launcher/RemoteHtml.kt', root), 'utf8')
  .match(/"""([\s\S]*?)"""/)[1].trim();
const assets = Object.fromEntries(['control-center.js', 'core.mjs', 'control-center.css'].map(name => [
  `/remote/assets/${name}`, readFileSync(resolve(assetDirectory, name), 'utf8'),
]));

for (const mode of ['scene', 'preset']) {
  test(`${mode} draft step actions have numbered names and retain the selected wait`, {
    skip: enabled ? false : 'Set IMMORTAL_REMOTE_CHROME=1 for isolated generated Chrome.',
    timeout: 30000,
  }, async t => {
    const { chromium } = createRequire(import.meta.url)('playwright');
    const f = { errors: [], outsideRequests: 0, posts: [], reads: [] };
    const draft = { id: `generated-${mode}`, name: `Generated ${mode}`,
      steps: [{ type: 'wait', ms: 100 }, { type: 'wait', ms: 200 }] };
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
          f.posts.push({ method: request.method(), path: url.pathname });
          return route.abort();
        }
        f.reads.push(url.pathname);
        if (url.pathname === '/remote/ui') return route.fulfill({ contentType: 'text/html', body: html });
        if (assets[url.pathname]) return route.fulfill({
          contentType: url.pathname.endsWith('.css') ? 'text/css' : 'text/javascript', body: assets[url.pathname] });
        if (url.pathname === '/remote/companion') return json({ ok: true, protocolVersion: 1,
          deviceId: 'generated-step-device', name: 'Generated room', model: 'Generated Portal',
          capabilities: { scenes: true }, app: { versionName: 'generated' } });
        if (url.pathname === '/remote/scenes') return json({ ok: true,
          scenes: [draft], revision: 'a'.repeat(64) });
        if (url.pathname === '/remote/presets') return json({ ok: true, presets: [draft] });
        if (url.pathname === '/remote/settings') return json({ ok: true, settings: { domains: [] } });
        if (url.pathname === '/remote/apps') return json({ ok: true, apps: [] });
        if (url.pathname === '/remote/nowplaying') return json({ ok: true, np: { active: false } });
        if (url.pathname === '/remote/roster') return json({ ok: true, roster: [] });
        return json({ ok: true });
      });
      await context.addInitScript(origin => {
        localStorage.setItem('immortal_remote_devices', JSON.stringify([
          { base: origin, name: 'Generated room', token: 'generated-token-only' }]));
        localStorage.setItem('immortal_remote_active', '0');
      }, origin);
      const page = await context.newPage();
      page.setDefaultTimeout(5000);
      page.on('pageerror', error => f.errors.push(error.message));
      await page.goto(`${origin}/remote/ui`);
      await page.getByRole('button', { name: mode === 'scene'
        ? /Scenes Everyday routines/ : /Saved presets Your existing/ }).click();
      await page.getByRole('heading', { name: draft.name, exact: true }).waitFor();
      const edit = page.getByRole('button', { name: 'Edit', exact: true });
      await edit.waitFor();
      await edit.click();
      const rows = page.locator('.reorder-row');
      await rows.first().waitFor();
      assert.deepEqual(await rows.locator('.label').allTextContents(),
        ['1. Wait 100 ms', '2. Wait 200 ms']);
      // Immediate counts make absent accessible names assertion failures, never locator timeouts.
      for (const n of [1, 2]) {
        for (const name of [`Move step ${n} up`, `Move step ${n} down`,
          `Edit step ${n}`, `Remove step ${n}`]) {
          assert.equal(await page.getByRole('button', { name, exact: true }).count(), 1,
            `${mode}: missing named action ${name}`);
        }
      }
      const action = name => page.getByRole('button', { name, exact: true });
      assert.equal(await action('Move step 1 up').isDisabled(), true);
      assert.equal(await action('Move step 2 down').isDisabled(), true);
      await action('Move step 2 up').click();
      assert.deepEqual(await rows.locator('.label').allTextContents(),
        ['1. Wait 200 ms', '2. Wait 100 ms']);
      await action('Remove step 1').click();
      assert.deepEqual(await rows.locator('.label').allTextContents(), ['1. Wait 100 ms']);
      for (const prefix of ['Move step', 'Edit step', 'Remove step']) {
        assert.equal(await page.getByRole('button', { name: new RegExp(`^${prefix} 2(?: |$)`) }).count(), 0);
      }
      assert.equal(await action('Move step 1 up').isDisabled(), true);
      assert.equal(await action('Move step 1 down').isDisabled(), true);
      await action('Edit step 1').click();
      const sheet = page.locator('#sheet');
      assert.equal(await sheet.getByRole('heading', { name: 'Edit step', exact: true }).isVisible(), true);
      assert.equal(await sheet.getByRole('spinbutton', { name: 'Wait in milliseconds', exact: true }).inputValue(), '100');
      assert.deepEqual(f.posts, []);
      assert.equal(f.outsideRequests, 0);
      assert.deepEqual(f.errors, []);
    } finally {
      t.diagnostic(JSON.stringify({ mode, interceptedReads: f.reads, posts: f.posts,
        outsideRequests: f.outsideRequests, pageErrors: f.errors,
        scope: 'Actual remote assets, generated draft UI only; no save/run/device calls' }));
      await browser.close();
    }
  });
}
