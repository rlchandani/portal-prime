/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */
// Actual remote assets, generated drafts and isolated Chrome; no device calls.
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
const origin = 'https://step-focus-fixture.invalid';
const html = readFileSync(new URL('app/src/main/java/com/immortal/launcher/RemoteHtml.kt', root), 'utf8')
  .match(/"""([\s\S]*?)"""/)[1].trim();
const assets = Object.fromEntries(['control-center.js', 'core.mjs', 'control-center.css'].map(name => [
  `/remote/assets/${name}`, readFileSync(resolve(directory, name), 'utf8'),
]));

for (const mode of ['scene', 'preset']) {
  for (const variant of ['refresh', 'stable', 'replacement']) {
    test(`${mode} step dialog focus after ${variant}`, {
      skip: enabled ? false : 'Set IMMORTAL_REMOTE_CHROME=1 for isolated generated Chrome.',
      timeout: 30000,
    }, async t => {
      const { chromium } = createRequire(import.meta.url)('playwright');
      let holdApps = mode === 'preset', releaseApps, appsSeen;
      const gate = new Promise(resolve => { releaseApps = resolve; });
      const seen = new Promise(resolve => { appsSeen = resolve; });
      const f = { mode, variant, posts: [], outsideRequests: 0, pageErrors: [], reads: [],
        heldApps: 0, oldOpenerDisconnected: false, closeObserved: false, focused: null };
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
            f.posts.push({ method: request.method(), path: url.pathname }); return route.abort();
          }
          f.reads.push(url.pathname);
          if (url.pathname === '/remote/ui') return route.fulfill({ contentType: 'text/html', body: html });
          if (assets[url.pathname]) return route.fulfill({ contentType: url.pathname.endsWith('.css')
            ? 'text/css' : 'text/javascript', body: assets[url.pathname] });
          if (url.pathname === '/remote/companion') return json({ ok: true, protocolVersion: 1,
            deviceId: 'generated-focus-device', name: 'Generated room', model: 'Generated Portal',
            capabilities: { scenes: true }, app: { versionName: 'generated' } });
          if (url.pathname === '/remote/scenes') return json({ ok: true,
            scenes: [draft], revision: 'a'.repeat(64) });
          if (url.pathname === '/remote/presets') return json({ ok: true, presets: [draft] });
          if (url.pathname === '/remote/settings') return json({ ok: true, settings: { domains: [] } });
          if (url.pathname === '/remote/apps') {
            if (holdApps) { f.heldApps++; appsSeen(); await gate; }
            return json({ ok: true, apps: [] });
          }
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
        page.on('pageerror', error => f.pageErrors.push(error.message));
        await page.goto(`${origin}/remote/ui`);
        await page.getByRole('button', { name: mode === 'scene'
          ? /Scenes Everyday routines/ : /Saved presets Your existing/ }).click();
        await page.getByRole('heading', { name: draft.name, exact: true }).waitFor();
        holdApps = true;
        await page.getByRole('button', { name: 'Edit', exact: true }).click();
        const edit = page.getByRole('button', { name: 'Edit step 1', exact: true });
        await edit.waitFor();
        await seen;
        let opener = await edit.elementHandle();
        if (variant === 'stable') {
          releaseApps();
          await page.waitForFunction(node => !node.isConnected, opener);
          // Settle the completed read's rendering before this no-refresh control opens its sheet.
          await page.evaluate(() => new Promise(resolve =>
            requestAnimationFrame(() => requestAnimationFrame(resolve))));
          opener = await edit.elementHandle();
        }
        await page.evaluate(() => {
          window.__fixtureClickedOpener = null;
          document.addEventListener('click', event => {
            const button = event.target.closest('button');
            if (button?.getAttribute('aria-label') === 'Edit step 1')
              window.__fixtureClickedOpener = button;
          }, { capture: true, once: true });
        });
        await edit.click();
        const sheet = page.getByRole('dialog', { name: 'Edit step', exact: true });
        await sheet.waitFor();
        opener = (await page.evaluateHandle(() => window.__fixtureClickedOpener)).asElement();
        assert.ok(opener, 'Fixture admission: actual clicked Edit step 1 captured');
        assert.equal(await sheet.getByRole('spinbutton', { name: 'Wait in milliseconds', exact: true })
          .inputValue(), '100');
        if (variant !== 'stable') {
          assert.equal(await opener.evaluate(node => node.isConnected), true,
            'Fixture admission: actual clicked opener remains mounted before held apps release');
          releaseApps();
          await page.waitForFunction(node => !node.isConnected, opener);
          f.oldOpenerDisconnected = !(await opener.evaluate(node => node.isConnected));
          assert.equal(f.oldOpenerDisconnected, true);
        } else {
          assert.equal(await opener.evaluate(node => node.isConnected), true);
        }
        await page.evaluate(replacement => {
          const sheet = document.querySelector('#sheet');
          window.__fixtureClose = new Promise(resolve => sheet.addEventListener('close', () => {
            if (replacement) {
              // Public DOM button activation models a new UI action in the close event.
              const next = [...document.querySelectorAll('button')]
                .find(button => button.getAttribute('aria-label') === 'Edit step 2');
              if (!next) throw new Error('Generated replacement opener missing');
              next.click();
            }
            queueMicrotask(() => queueMicrotask(resolve));
          }, { once: true }));
        }, variant === 'replacement');
        await page.keyboard.press('Escape');
        await page.evaluate(() => window.__fixtureClose);
        f.closeObserved = true;
        f.focused = await page.evaluate(() => ({
          name: document.activeElement?.getAttribute('aria-label'),
          tag: document.activeElement?.tagName,
          inSheet: document.querySelector('#sheet').contains(document.activeElement),
          sheetOpen: document.querySelector('#sheet').open,
        }));
        if (variant === 'replacement') {
          assert.equal(f.focused.sheetOpen, true, 'Replacement sheet remains open');
          assert.equal(f.focused.inSheet, true, 'Deferred old focus must not escape the replacement sheet');
          assert.equal(await sheet.getByRole('spinbutton', { name: 'Wait in milliseconds', exact: true })
            .inputValue(), '200');
        } else {
          assert.equal(f.focused.sheetOpen, false);
          assert.equal(f.focused.name, 'Edit step 1',
            `${mode}/${variant}: closing returns focus to the current numbered opener`);
        }
        assert.deepEqual(f.posts, []);
        assert.equal(f.outsideRequests, 0);
        assert.deepEqual(f.pageErrors, []);
      } finally {
        releaseApps();
        t.diagnostic(JSON.stringify(f));
        await browser.close();
      }
    });
  }
}
