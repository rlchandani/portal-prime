/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */
// Opt-in isolated Chrome with generated responses; no existing profiles or devices.
import test from 'node:test';
import assert from 'node:assert/strict';
import { readFileSync } from 'node:fs';
import { createRequire } from 'node:module';

const enabled = process.env.IMMORTAL_REMOTE_CHROME === '1';
const root = new URL('../../', import.meta.url);
const read = path => readFileSync(new URL(path, root), 'utf8');
const origin = 'https://pairing-fixture.invalid';

test('pairing uses native Enter submission with its sticky footer action', {
  skip: enabled ? false : 'Set IMMORTAL_REMOTE_CHROME=1 for isolated synthetic Chrome.',
  timeout: 30000,
}, async t => {
  const { chromium } = createRequire(import.meta.url)('playwright');
  const html = read('app/src/main/java/com/immortal/launcher/RemoteHtml.kt')
    .match(/"""([\s\S]*?)"""/)[1].trim();
  const assets = Object.fromEntries(['control-center.js', 'core.mjs', 'control-center.css']
    .map(name => [`/remote/assets/${name}`, read(`app/src/main/assets/remote/${name}`)]));
  const browser = await chromium.launch({
    headless: true,
    executablePath: '/Applications/Google Chrome.app/Contents/MacOS/Google Chrome',
    timeout: 15000,
    args: ['--disable-background-networking', '--disable-component-update', '--no-proxy-server',
      '--host-resolver-rules=MAP * ~NOTFOUND'],
  });
  const reports = [];
  async function fixture(run) {
    const f = { posts: [], errors: [], outside: [], unexpected: [], websockets: [], held: null,
      hold: false };
    const context = await browser.newContext({
      viewport: { width: 402, height: 874 }, isMobile: true, hasTouch: true,
      serviceWorkers: 'block', reducedMotion: 'reduce',
    });
    await context.routeWebSocket('**/*', socket => {
      f.websockets.push(socket.url()); socket.close();
    });
    await context.route('**/*', async route => {
      const request = route.request(), url = new URL(request.url());
      if (url.origin !== origin) { f.outside.push(request.url()); return route.abort(); }
      if (url.pathname === '/remote/ui' && request.method() === 'GET')
        return route.fulfill({ contentType: 'text/html', body: html });
      if (assets[url.pathname] && request.method() === 'GET')
        return route.fulfill({
          contentType: url.pathname.endsWith('.css') ? 'text/css' : 'text/javascript',
          body: assets[url.pathname],
        });
      if (url.pathname === '/remote/manifest.webmanifest' && request.method() === 'GET')
        return route.fulfill({ contentType: 'application/manifest+json', body: '{}' });
      if (url.pathname === '/remote/app-icon' && request.method() === 'GET')
        return route.fulfill({ status: 204 });
      if (url.pathname === '/remote/pair' && request.method() === 'POST') {
        f.posts.push({ path: url.pathname, body: JSON.parse(request.postData()) });
        const fail = () => route.fulfill({ status: 400, contentType: 'application/json',
          body: JSON.stringify({ ok: false, error: 'bad_pin' }) });
        if (f.hold) { f.held = fail; return; }
        return fail();
      }
      f.unexpected.push({ path: url.pathname, method: request.method() });
      return route.abort();
    });
    // Every case starts with an empty pairing roster in a new disposable context.
    await context.addInitScript(() => localStorage.clear());
    const page = await context.newPage();
    page.setDefaultTimeout(3000);
    page.on('pageerror', error => f.errors.push(error.message));
    const settle = () => page.evaluate(() => new Promise(resolve =>
      requestAnimationFrame(() => requestAnimationFrame(resolve))));
    try {
      await page.goto(`${origin}/remote/ui`);
      await page.getByRole('button', { name: 'Pair a Portal', exact: true }).click();
      const dialog = page.getByRole('dialog', { name: 'Pair a Portal', exact: true });
      const address = dialog.getByLabel('Portal address or pairing link', { exact: true });
      const pin = dialog.getByLabel('Six-digit PIN', { exact: true });
      const action = dialog.getByRole('button', { name: /^Pair Portal$|^Pairing…$/ });
      await address.fill(`${origin}/remote/ui`);
      await pin.fill('123456');
      const submitted = async count => {
        const deadline = Date.now() + 500;
        while (f.posts.length < count && Date.now() < deadline)
          await new Promise(resolve => setTimeout(resolve, 10));
        await settle();
        assert.equal(f.posts.length, count, 'native action must send exactly one pairing request');
        assert.deepEqual(f.posts.at(-1), { path: '/remote/pair', body: { pin: '123456' } });
      };
      await run({ f, page, dialog, address, pin, action, settle, submitted });
    } finally {
      if (f.held) { await f.held(); f.held = null; }
      reports.push({ posts: f.posts, errors: f.errors, outside: f.outside,
        unexpected: f.unexpected, websockets: f.websockets });
      await context.close();
      assert.deepEqual(f.errors, [], 'production page must have no script errors');
      assert.deepEqual(f.outside, [], 'fixture must make no outside requests');
      assert.deepEqual(f.unexpected, [], 'only declared fixture routes may be requested');
      assert.deepEqual(f.websockets, [], 'fixture must open no WebSockets');
    }
  }
  try {
    for (const label of ['PIN', 'address']) {
      await t.test(`Enter in ${label} submits the displayed form`, async () => {
        await fixture(async ({ f, address, pin, action, submitted }) => {
          await (label === 'PIN' ? pin : address).press('Enter');
          await submitted(1);
          assert.equal(await action.evaluate(node => node.form ===
            document.querySelector('#sheet-content form')), true,
          'sticky footer action must still belong to the displayed form');
          assert.equal(await action.evaluate(node =>
            document.querySelector('#sheet-actions').contains(node)), true);
          assert.equal(f.posts.length, 1);
        });
      });
    }
    await t.test('click submits once and a failed request can be retried', async () => {
      await fixture(async ({ f, pin, action, submitted }) => {
        await action.click(); await submitted(1);
        await pin.waitFor({ state: 'visible' });
        await pin.page().waitForFunction(() =>
          !document.querySelector('[data-focus="pair-pin"]').disabled);
        assert.equal(await pin.inputValue(), '');
        await pin.fill('123456');
        await action.click(); await submitted(2);
        assert.equal(f.posts.length, 2);
      });
    });
    await t.test('invalid PIN reports the existing correction without sending a request', async () => {
      await fixture(async ({ f, dialog, pin, settle }) => {
        await pin.fill('123'); await pin.press('Enter'); await settle();
        assert.equal(f.posts.length, 0);
        assert.equal(await dialog.locator('.error').count(), 1,
          'invalid Enter submission must expose the existing inline error');
        assert.match(await dialog.locator('.error').textContent(), /fresh PIN/i);
        assert.equal(await pin.inputValue(), '');
      });
    });
    await t.test('pending Enter cannot duplicate the request', async () => {
      await fixture(async ({ f, page, pin, action, submitted, settle }) => {
        f.hold = true;
        await pin.press('Enter'); await submitted(1);
        assert.equal(await pin.isDisabled(), true);
        assert.equal(await action.isDisabled(), true);
        await page.keyboard.press('Enter'); await settle();
        assert.equal(f.posts.length, 1);
        f.hold = false; await f.held(); f.held = null;
        await page.waitForFunction(() =>
          !document.querySelector('[data-focus="pair-pin"]').disabled);
        await pin.fill('123456'); await pin.press('Enter'); await submitted(2);
      });
    });
    await t.test('closing a filled form sends no pairing request', async () => {
      await fixture(async ({ f, dialog, settle }) => {
        await dialog.getByRole('button', { name: 'Close dialog', exact: true }).click();
        await settle(); assert.equal(f.posts.length, 0);
        assert.equal(await dialog.isVisible(), false);
      });
    });
  } finally {
    t.diagnostic(JSON.stringify({ cases: reports }));
    await browser.close();
  }
});
