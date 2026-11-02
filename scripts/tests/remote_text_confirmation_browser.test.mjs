/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */
// Opt-in isolated Chrome, synthetic in-memory backend; no existing tabs or profiles.
import test from 'node:test';
import assert from 'node:assert/strict';
import { readFileSync } from 'node:fs';
import { createRequire } from 'node:module';

const enabled = process.env.IMMORTAL_REMOTE_CHROME === '1';
const root = new URL('../../', import.meta.url);
const read = path => readFileSync(new URL(path, root), 'utf8');
const origin = 'https://fixture.invalid';
const uncertainty = 'Text sending is unconfirmed. Review the Portal before sending again.';
const success = 'Text command sent to the focused field.';
const unicode = '  café e\u0301 Ελληνικά 中文 العربية 👩🏽‍💻 🧑‍🚀 “quoted” \\ / <>&  ';

test('Remote Keyboard requires explicit text confirmation and never replays commands', {
  skip: enabled ? false : 'Set IMMORTAL_REMOTE_CHROME=1 for isolated synthetic Chrome.',
  timeout: 60000,
}, async t => {
  const { chromium } = createRequire(import.meta.url)('playwright');
  const html = read('app/src/main/java/com/immortal/launcher/RemoteHtml.kt')
    .match(/"""([\s\S]*?)"""/)[1].trim();
  const assets = Object.fromEntries(['control-center.js', 'core.mjs', 'control-center.css']
    .map(name => [`/remote/assets/${name}`, read(`app/src/main/assets/remote/${name}`)]));
  const f = { response: 'missing', posts: [], errors: [], outside: [], websockets: [], cases: [] };
  const failures = [];
  const check = (label, fn) => {
    try { fn(); } catch (error) { failures.push(`${label}: ${error.message}`); }
  };
  const browser = await chromium.launch({
    headless: true,
    executablePath: '/Applications/Google Chrome.app/Contents/MacOS/Google Chrome',
    timeout: 15000,
    args: ['--disable-background-networking', '--disable-component-update', '--no-proxy-server',
      '--host-resolver-rules=MAP * ~NOTFOUND'],
  });
  try {
    const context = await browser.newContext({
      viewport: { width: 402, height: 874 }, isMobile: true, hasTouch: true,
      serviceWorkers: 'block', reducedMotion: 'reduce',
    });
    await context.routeWebSocket('**/*', socket => {
      f.websockets.push(socket.url()); socket.close();
    });
    await context.route('**/*', async route => {
      const request = route.request(), url = new URL(request.url());
      const json = value => route.fulfill({
        contentType: 'application/json', body: JSON.stringify(value),
      });
      if (url.origin !== origin) { f.outside.push(request.url()); return route.abort(); }
      if (url.pathname === '/remote/ui')
        return route.fulfill({ contentType: 'text/html', body: html });
      if (assets[url.pathname])
        return route.fulfill({
          contentType: url.pathname.endsWith('.css') ? 'text/css' : 'text/javascript',
          body: assets[url.pathname],
        });
      if (request.method() === 'POST') {
        f.posts.push({ path: url.pathname, body: JSON.parse(request.postData()) });
        if (url.pathname === '/remote/text') {
          if (f.response === 'networkfailure') return route.abort('failed');
          if (f.response === 'malformedJSON')
            return route.fulfill({ contentType: 'application/json', body: '{"ok":true,' });
          if (f.response === 'missing') return json({ ok: true });
          if (f.response === 'nonBoolean') return json({ ok: true, applied: 'true' });
          return json({ ok: true, applied: f.response === 'true' });
        }
      }
      if (url.pathname === '/remote/companion')
        return json({ ok: true, protocolVersion: 1, deviceId: 'fixture-device',
          name: 'Fixture', model: 'Synthetic Portal', capabilities: {},
          app: { versionName: 'fixture' } });
      if (url.pathname === '/remote/nowplaying')
        return json({ ok: true, np: { active: false,
          volume: { level: 4, max: 15, canSet: true } } });
      if (url.pathname === '/remote/roster') return json({ ok: true, roster: [] });
      if (url.pathname === '/remote/settings') return json({ ok: true, settings: { domains: [] } });
      return json({ ok: true });
    });
    await context.addInitScript(base => {
      localStorage.setItem('immortal_remote_devices', JSON.stringify([
        { base, name: 'Fixture', token: 'fixture-only' },
      ]));
      localStorage.setItem('immortal_remote_active', '0');
    }, origin);
    const page = await context.newPage();
    page.setDefaultTimeout(5000);
    page.on('pageerror', error => f.errors.push(error.message));
    await page.goto(`${origin}/remote/ui`);
    const navigate = name => page.locator('#navigation')
      .getByRole('button', { name, exact: true }).click();
    await navigate('Remote');
    const input = page.getByLabel('Text for the focused field', { exact: true });
    await input.fill(unicode);
    const settle = () => page.evaluate(() => new Promise(resolve =>
      requestAnimationFrame(() => requestAnimationFrame(resolve))));
    const refresh = async () => {
      const response = page.waitForResponse(r =>
        new URL(r.url()).pathname === '/remote/companion' && r.request().method() === 'GET');
      await page.getByRole('button', { name: 'Refresh current page', exact: true }).click();
      await (await response).finished();
      await settle();
    };
    const textPosts = () => f.posts.filter(post => post.path === '/remote/text');
    const modes = [['set', 'Send text'], ['append', 'Append'],
      ['backspace', 'Backspace'], ['clear', 'Clear field']];
    const responses = ['missing', 'nonBoolean', 'true', 'false', 'malformedJSON', 'networkfailure'];
    for (const response of responses) {
      f.response = response;
      for (const [mode, label] of modes) {
        const caseName = `${mode}/${response}`, before = textPosts().length;
        await page.locator('#notice').evaluate(node => {
          node.hidden = true; node.textContent = '';
        });
        await page.getByRole('button', { name: label, exact: true }).click();
        if (mode === 'clear') {
          const dialog = page.getByRole('dialog', { name: 'Clear the focused field?' });
          await dialog.waitFor();
          check(`${caseName} requires confirmation`, () => assert.equal(textPosts().length, before));
          await dialog.getByRole('button', { name: 'Cancel', exact: true }).click();
          await settle();
          check(`${caseName} cancellation sends nothing`, () => assert.equal(textPosts().length, before));
          await page.getByRole('button', { name: label, exact: true }).click();
          await dialog.getByRole('button', { name: 'Clear', exact: true }).click();
        }
        await page.waitForFunction(() => {
          const notice = document.querySelector('#notice');
          return !notice.hidden && notice.textContent.length > 0;
        });
        await settle();
        const notice = await page.locator('#notice').textContent();
        check(`${caseName} one POST`, () => assert.equal(textPosts().length, before + 1));
        check(`${caseName} exact payload`, () => assert.deepEqual(textPosts().at(-1)?.body,
          { mode, text: ['set', 'append'].includes(mode) ? unicode : '' }));
        const retained = await input.inputValue();
        check(`${caseName} exact Unicode draft`, () => assert.equal(retained, unicode));
        if (response === 'true')
          check(`${caseName} success`, () => assert.equal(notice, success));
        else if (response === 'false') {
          check(`${caseName} rejected`, () => {
            assert.notEqual(notice, success);
            assert.match(notice, /no editable field|focused.*field|select.*field|not applied/i);
          });
        } else
          check(`${caseName} uncertainty`, () => assert.equal(notice, uncertainty));
        await refresh();
        await navigate('Settings');
        await navigate('Remote');
        await settle();
        check(`${caseName} no replay after refresh/navigation`, () =>
          assert.equal(textPosts().length, before + 1));
        const afterNavigation = await input.inputValue();
        check(`${caseName} retained after refresh/navigation`, () => assert.equal(afterNavigation, unicode));
        f.cases.push({ mode, response, notice });
      }
    }
    for (const width of [360, 402, 1200]) {
      await page.setViewportSize({ width, height: 874 });
      await settle();
      const overflow = await page.evaluate(() => ({
        document: document.documentElement.scrollWidth > innerWidth,
        body: document.body.scrollWidth > innerWidth,
      }));
      check(`width ${width} no overflow`, () =>
        assert.deepEqual(overflow, { document: false, body: false }));
    }
    // Allow an ordinary media poll to run: it must never replay the last text command.
    const beforePoll = textPosts().length;
    await page.waitForResponse(r => new URL(r.url()).pathname === '/remote/nowplaying'
      && r.request().method() === 'GET', { timeout: 6000 });
    await settle();
    check('no replay on polling', () => assert.equal(textPosts().length, beforePoll));
    check('no unrelated POSTs', () => assert.deepEqual(
      f.posts.filter(post => post.path !== '/remote/text'), []));
    check('no page errors', () => assert.deepEqual(f.errors, []));
    check('no outside requests', () => assert.deepEqual(f.outside, []));
    check('no WebSockets', () => assert.deepEqual(f.websockets, []));
    t.diagnostic(JSON.stringify({ cases: f.cases, textPosts: textPosts().length,
      widths: [360, 402, 1200], pageErrors: f.errors, outsideRequests: f.outside,
      failures }));
    assert.deepEqual(failures, []);
  } finally {
    await browser.close();
  }
});
