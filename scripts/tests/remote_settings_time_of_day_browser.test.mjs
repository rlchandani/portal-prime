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
const origin = 'https://remote-time-fixture.invalid';
test('native mobile time inputs preserve drafts and send minute integers only on Apply', {
  skip: enabled ? false : 'Set IMMORTAL_REMOTE_CHROME=1 for isolated synthetic Chrome.', timeout: 30000,
}, async t => {
  const { chromium } = createRequire(import.meta.url)('playwright');
  const html = read('app/src/main/java/com/immortal/launcher/RemoteHtml.kt').match(/"""([\s\S]*?)"""/)[1].trim();
  const assets = Object.fromEntries(['control-center.js', 'core.mjs', 'control-center.css'].map(name => [
    `/remote/assets/${name}`, read(`app/src/main/assets/remote/${name}`)]));
  const keys = ['overnightStartMin', 'overnightEndMin', 'quietStartMin', 'quietEndMin'];
  const f = { readOnly: false, value: 1320, posts: [], errors: [], outsideRequests: 0 };
  const domain = () => ({ id: 'screensaver', title: 'Screensaver', controls: keys.map((key, i) => ({
    key, title: key, type: 'int', timeOfDay: true, min: 0, max: 1439, step: 1,
    value: i === 0 ? f.value : [0, 420, 1380, 480][i], readOnly: f.readOnly })) });
  const browser = await chromium.launch({ headless: true,
    executablePath: '/Applications/Google Chrome.app/Contents/MacOS/Google Chrome', timeout: 15000,
    args: ['--disable-background-networking', '--disable-component-update', '--no-proxy-server',
      '--host-resolver-rules=MAP * ~NOTFOUND'] });
  try {
    const context = await browser.newContext({ viewport: { width: 402, height: 874 }, isMobile: true,
      hasTouch: true, serviceWorkers: 'block', reducedMotion: 'reduce' });
    await context.routeWebSocket('**/*', socket => socket.close());
    await context.route('**/*', async route => {
      const request = route.request(), url = new URL(request.url());
      const json = value => route.fulfill({ contentType: 'application/json', body: JSON.stringify(value) });
      if (url.origin !== origin) { f.outsideRequests++; return route.abort(); }
      if (url.pathname === '/remote/ui') return route.fulfill({ contentType: 'text/html', body: html });
      if (assets[url.pathname]) return route.fulfill({ contentType: url.pathname.endsWith('.css') ? 'text/css' : 'text/javascript', body: assets[url.pathname] });
      if (url.pathname === '/remote/settings') {
        if (request.method() === 'POST') {
          f.posts.push(request.postData()); f.value = JSON.parse(request.postData()).values.overnightStartMin;
          return json({ ok: true, applied: ['overnightStartMin'], domain: domain() });
        }
        return json({ ok: true, settings: { domains: [domain()] } });
      }
      if (url.pathname === '/remote/companion') return json({ ok: true, protocolVersion: 1,
        deviceId: 'fixture-device', name: 'Fixture', model: 'Synthetic Portal', capabilities: {}, app: { versionName: 'fixture' } });
      if (url.pathname === '/remote/nowplaying') return json({ ok: true, np: { active: false, volume: { level: 4, max: 15, canSet: true } } });
      if (url.pathname === '/remote/roster') return json({ ok: true, roster: [] });
      return json({ ok: true });
    });
    await context.addInitScript(origin => {
      localStorage.setItem('immortal_remote_devices', JSON.stringify([{ base: origin, name: 'Fixture', token: 'fixture-only' }]));
      localStorage.setItem('immortal_remote_active', '0');
    }, origin);
    const page = await context.newPage(); page.setDefaultTimeout(5000);
    page.on('pageerror', error => f.errors.push(error.message));
    await page.goto(`${origin}/remote/ui`);
    await page.locator('#navigation').getByRole('button', { name: 'Settings', exact: true }).click();
    // Native time inputs have browser-specific accessibility roles; use the shipped label.
    const picker = page.locator(`input[aria-label="${keys[0]}"]`);
    await picker.waitFor(); assert.equal(await picker.getAttribute('type'), 'time');
    assert.equal(await page.locator('input[type=time]').count(), 4);
    assert.equal(await picker.inputValue(), '22:00'); assert.equal(await page.locator('.stepper').count(), 0);
    const apply = picker.locator('..').getByRole('button', { name: 'Apply', exact: true });
    const refresh = async () => {
      const response = page.waitForResponse(r => new URL(r.url()).pathname === '/remote/settings' && r.request().method() === 'GET');
      await page.getByRole('button', { name: 'Refresh current page' }).click();
      await (await response).finished();
      await page.evaluate(() => new Promise(resolve => requestAnimationFrame(() => requestAnimationFrame(resolve))));
    };
    await picker.fill('07:45'); assert.equal(f.posts.length, 0);
    await picker.evaluate(node => { window.fixtureTimeInput = node; });
    await refresh();
    assert.equal(await picker.inputValue(), '07:45');
    assert.equal(await picker.evaluate(node => node === window.fixtureTimeInput), true);
    f.readOnly = true; await refresh();
    await page.waitForFunction(() => document.querySelector('input[type=time]').disabled);
    assert.equal(await picker.inputValue(), '07:45'); assert.equal(await apply.isDisabled(), true);
    f.readOnly = false; await refresh();
    await page.waitForFunction(() => !document.querySelector('input[type=time]').disabled);
    // Real browsers sanitize malformed native values to blank; blank must never become midnight.
    await picker.evaluate(node => { node.value = '24:00'; node.dispatchEvent(new Event('input', { bubbles: true })); });
    assert.equal(await picker.inputValue(), ''); await apply.click();
    assert.equal(f.posts.length, 0); assert.equal(await picker.getAttribute('aria-invalid'), 'true');
    const errorId = await picker.getAttribute('aria-describedby');
    assert.equal(await page.locator(`[id="${errorId}"]`).isVisible(), true);
    await refresh(); assert.equal(await picker.inputValue(), '');
    await picker.fill('12:00:30'); await apply.click();
    assert.equal(f.posts.length, 0); assert.equal(await picker.inputValue(), '12:00:30');
    assert.equal(await picker.getAttribute('aria-invalid'), 'true');
    await refresh(); assert.equal(await picker.inputValue(), '12:00:30');
    for (const [value, minutes] of [['00:00', 0], ['23:59', 1439]]) {
      await picker.fill(value); await apply.click();
      await page.waitForFunction(value => document.querySelector('input[type=time]').value === value && !document.querySelector('input[type=time]').disabled, value);
      assert.equal(await picker.getAttribute('aria-invalid'), 'false');
      assert.equal(f.posts.at(-1), JSON.stringify({ domain: 'screensaver', values: { overnightStartMin: minutes } }));
    }
    for (const width of [360, 402, 1200]) {
      await page.setViewportSize({ width, height: 874 });
      assert.equal(await page.evaluate(() => document.documentElement.scrollWidth > innerWidth), false);
      assert.equal(await picker.evaluate(node => node.getBoundingClientRect().width >= 44), true);
    }
    assert.deepEqual(f.errors, []); assert.equal(f.outsideRequests, 0);
    t.diagnostic(JSON.stringify({ nativeTimeInputs: 4, posts: f.posts, retainedDraft: true,
      invalidNoApply: true, widths: [360, 402, 1200], pageErrors: f.errors, outsideRequests: f.outsideRequests }));
  } finally { await browser.close(); }
});
