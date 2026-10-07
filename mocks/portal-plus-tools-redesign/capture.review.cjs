/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */
'use strict';
const assert = require('node:assert/strict');
const { createHash } = require('node:crypto');
const fs = require('node:fs');
const path = require('node:path');
const { chromium } = require('~/.cache/codex-runtimes/codex-primary-runtime/dependencies/node/node_modules/playwright');
const origin = 'http://127.0.0.1:8765';
const toolTitles = { alarm: 'Alarm', timers: 'Timers', countdowns: 'Countdowns', converter: 'Converter', lamp: 'Lamp', notes: 'Leave a note', camera: 'Cameras', intercom: 'Intercom', stories: 'Bedtime story', iss: 'ISS passes', aurora: 'Aurora outlook', speed: 'Speed test' };
const digest = buffer => createHash('sha256').update(buffer).digest('hex');
(async () => {
  const browser = await chromium.launch({ headless: true, channel: 'chrome' });
  try {
    const errors = [], shots = [], requests = [], captures = [];
    const context = await browser.newContext({ viewport: { width: 2160, height: 1492 }, reducedMotion: 'reduce' });
    // Repeated parallel asset bursts can reset connections on the local review
    // server, leaving Tools exported but its renderer/navigation uninitialized.
    // Serve the original responses one at a time; never retry a failed fetch.
    let localRequests = Promise.resolve();
    await context.route('**/*', route => {
      if (new URL(route.request().url()).origin !== origin) return route.abort();
      localRequests = localRequests.then(async () => {
        try {
          const response = await route.fetch({ maxRetries: 0, maxRedirects: 0 });
          await route.fulfill({ response });
        } catch (error) {
          errors.push({ kind: 'fetch', url: route.request().url(), message: error.message });
          await route.abort('failed');
        }
      });
      return localRequests;
    });
    const page = await context.newPage();
    page.setDefaultTimeout(10000);
    let expected = {};
    page.on('pageerror', error => errors.push({ kind: 'pageerror', url: page.url(), message: error.message, stack: error.stack }));
    page.on('response', response => {
      const request = response.request();
      if (request.resourceType() === 'script' || response.status() >= 400) {
        requests.push({ url: response.url(), status: response.status(), type: request.resourceType() });
      }
      if (response.status() >= 400) errors.push({ kind: 'response', url: response.url(), status: response.status() });
    });
    page.on('requestfailed', request => errors.push({ kind: 'requestfailed', url: request.url(), message: request.failure()?.errorText }));
    page.on('console', message => {
      if (message.type() === 'error') errors.push({ kind: 'console', url: page.url(), message: message.text() });
    });
    const diagnose = () => page.evaluate(() => {
      const canvas = document.querySelector('#navigation-device');
      const tools = document.querySelector('#tools-overhaul');
      const describe = node => node && ({
        id: node.id, hidden: node.hidden, inert: node.inert, tool: node.dataset.tool, view: node.dataset.view,
        bounds: node.getBoundingClientRect().toJSON(), text: node.innerText.slice(0, 1600),
      });
      return {
        url: location.href, readyState: document.readyState,
        globals: { spec: typeof PORTAL_SPEC, mock: !!window.__portalMock, tools: !!window.__portalToolsRedesign, navigation: !!window.__portalNavigationDemo },
        navigation: window.__portalNavigationDemo?.getState(), state: window.__portalToolsRedesign?.getState(),
        canvas: describe(canvas), tools: describe(tools),
        titles: [...document.querySelectorAll('#tool-title')].map(node => node.textContent),
        actions: [...canvas?.querySelectorAll('[data-tool-action]') || []].map(node => ({
          action: node.dataset.toolAction, visible: !!node.getClientRects().length, inert: !!node.closest('[inert]'),
        })),
        scripts: [...document.scripts].map(node => node.src),
        resources: performance.getEntriesByType('resource').filter(entry => entry.initiatorType === 'script').map(entry => ({
          url: entry.name, duration: entry.duration, bytes: entry.decodedBodySize, status: entry.responseStatus,
        })),
      };
    });
    const root = page.locator('#navigation-device');
    const folder = path.join(__dirname, 'reference');
    const clean = () => assert.equal(errors.length, 0, JSON.stringify(errors, null, 2));
    const ready = async () => {
      clean();
      await page.waitForFunction(expected => {
        const canvas = document.querySelector('#navigation-device');
        const tools = canvas?.querySelector('#tools-overhaul');
        const navigation = window.__portalNavigationDemo?.getState();
        const state = window.__portalToolsRedesign?.getState();
        const visible = node => !!node?.getClientRects().length && getComputedStyle(node).visibility !== 'hidden';
        const url = new URL(location.href);
        if (typeof PORTAL_SPEC !== 'object' || !window.__portalMock || !visible(tools) ||
            navigation?.host !== 'launcher' || navigation.tab !== 'tools' || navigation.route.screen !== 'tools' ||
            !state?.active || state.tool !== (expected.tool || null) || state.scenario !== expected.scenario ||
            state.advanced !== (expected.tool === 'speed') ||
            tools.dataset.tool !== expected.tool || tools.dataset.view !== (expected.tool ? 'workspace' : 'library') ||
            url.searchParams.get('screen') !== 'tools' || (url.searchParams.get('tool') || '') !== expected.tool ||
            url.searchParams.get('scenario') !== expected.scenario ||
            (state.sheet?.kind || null) !== expected.sheet || !!state.picker !== expected.picker ||
            state.full !== expected.full || state.story !== expected.story) return false;
        const title = tools.querySelector('#tool-title');
        if (expected.tool ? !visible(title) || title.textContent !== expected.title
          : !visible(tools.querySelector('.tools-library')) || tools.querySelectorAll('.tool-entry').length !== 11) return false;
        const full = canvas.querySelector(':scope > .fullscreen-tool');
        if (expected.full ? !visible(full) || !canvas.querySelector('.live-launcher').inert
          : !!full || canvas.querySelector('.live-launcher').inert) return false;
        if (expected.sheet && !visible(tools.querySelector(`[data-tool-form="${expected.sheet}"]`))) return false;
        if (expected.picker && !visible(tools.querySelector('.tool-picker [role="dialog"]'))) return false;
        if (!expected.sheet && !expected.picker && tools.querySelector('.tool-overlay')) return false;
        if (expected.story !== null && !visible(tools.querySelector('.story-reader'))) return false;
        const bounds = canvas.getBoundingClientRect();
        return Math.abs(bounds.width - 2160) < .01 && Math.abs(bounds.height - 1440) < .01 &&
          document.fonts.status === 'loaded' && !canvas.getAnimations({ subtree: true }).length;
      }, expected, { timeout: 10000 });
      clean();
    };
    const route = async (tool = '', scenario = 'normal') => {
      expected = { tool, scenario, title: toolTitles[tool], sheet: null, picker: false, full: null, story: null, step: 'route' };
      const response = await page.goto(`${origin}/portal-plus-tools-redesign/?screen=tools${tool ? `&tool=${tool}` : ''}&scenario=${scenario}`);
      assert.equal(response.status(), 200, `Review route HTTP status: ${response.url()}`);
      clean();
      await page.evaluate(() => document.fonts.ready);
      await ready();
      if (process.argv.includes('--diagnostics')) console.log(JSON.stringify({ expected, diagnostics: await diagnose(), errors, requests: requests.slice(-12) }));
    };
    const click = async (action, view) => {
      expected.step = action;
      await ready();
      await root.locator(`[data-tool-action="${action}"]:not([inert] *)`).filter({ visible: true }).first().click();
      Object.assign(expected, view);
      await ready();
    };
    const shot = async (id, title, tool = '') => {
      expected.step = `capture ${id}`;
      assert.equal(tool, expected.tool, `Capture ${id} must match its route`);
      await ready();
      const file = `portal-${id}.png`;
      const buffer = await root.screenshot();
      clean();
      assert.equal(buffer.readUInt32BE(16), 2160, `${file} width`);
      assert.equal(buffer.readUInt32BE(20), 1440, `${file} height`);
      captures.push({ file, buffer });
      shots.push({
        file, title, tool, scenario: expected.scenario, url: page.url(),
        view: expected.full ? 'display' : expected.sheet ? 'sheet' : expected.picker ? 'picker' : expected.story !== null ? 'reader' : tool ? 'workspace' : 'library',
        sheet: expected.sheet, picker: expected.picker, full: expected.full, story: expected.story, sha256: digest(buffer),
      });
    };
    try {
      if (process.argv.includes('--probe-routes')) {
        for (let index = 0; index < 24; index++) {
          await route('camera', index % 2 ? 'normal' : 'offline');
          await click('camera-view', { full: 'camera' });
        }
        clean();
        console.log(JSON.stringify({ probed: 24, errors }));
        return;
      }
      await route(); await shot('tools-library', 'Tools library');
      for (const [id, title] of Object.entries(toolTitles)) {
        await route(id); await shot(`tool-${id}`, id === 'speed' ? `${title} · Advanced` : title, id);
      }
      for (const [tool, action, label, sheet = action] of [
        ['alarm', 'alarm-time', 'Change alarm time'], ['timers', 'timer-custom', 'Custom timer'],
        ['camera', 'camera-add', 'Add camera'], ['countdowns', 'countdown-add', 'Add countdown'],
        ['lamp', 'lamp-number', 'Lamp numeric control', 'lamp-brightness'],
      ]) {
        await route(tool);
        await click(action, { sheet });
        if (tool === 'countdowns') {
          await root.locator('[name=yearly]').uncheck();
          assert.ok(await root.locator('[name=year]').isVisible(), 'One-off countdown shows its year');
        }
        await shot(`flow-${action}`, label, tool);
      }
      await route('converter'); await click('picker', { picker: true });
      await shot('flow-unit-picker', 'Shared picker style', 'converter');
      for (const [tool, action, label] of [['camera', 'camera-view', 'Camera display'], ['lamp', 'lamp-preview', 'Lamp display'], ['alarm', 'alarm-preview', 'Alarm ringing']]) {
        await route(tool); await click(action, { full: tool });
        await shot(`display-${tool}`, label, tool);
      }
      await route('stories'); await click('story-open', { story: 0 });
      await shot('flow-story-reader', 'Story reader', 'stories');
      await route('camera', 'offline'); await click('camera-view', { full: 'camera' });
      assert.ok(await root.getByRole('heading', { name: 'Camera unavailable', exact: true }).isVisible(), 'Offline camera displays its unavailable state');
      await shot('state-camera-offline', 'Camera unavailable', 'camera');
      await route('notes', 'permission');
      assert.ok(await root.getByRole('heading', { name: 'Microphone access needed', exact: true }).isVisible(), 'Notes displays its microphone prerequisite');
      await shot('state-microphone', 'Microphone prerequisite', 'notes');
      clean();
      assert.equal(shots.length, 25, 'Complete gallery');
      assert.equal(new Set(shots.map(shot => shot.file)).size, 25, 'Unique capture files');
      // Publish only after every route, flow, state and image has passed.
      fs.mkdirSync(folder, { recursive: true });
      for (const { file, buffer } of captures) fs.writeFileSync(path.join(folder, file), buffer);
      const escape = s => s.replace(/[&<>"']/g, c => ({ '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;', "'": '&#39;' })[c]);
      const cards = shots.map(s => `<a class="screen" href="./?screen=tools${s.tool ? `&amp;tool=${s.tool}` : ''}&amp;scenario=${s.scenario}"><img loading="lazy" src="reference/${s.file}" alt="${escape(s.title)}"><h2>${escape(s.title)}</h2><span>Open interactive tool</span></a>`).join('');
      fs.writeFileSync(path.join(__dirname, 'review.html'), `<!doctype html>
<!-- Copyright (c) 2026 Starbright Lab. Licensed under the MIT license in the repository root. -->
<html lang="en"><head><meta charset="utf-8"><meta name="viewport" content="width=device-width,initial-scale=1"><link rel="icon" href="data:,"><title>Immortal · Tools screen review</title>
<style>@font-face{font-family:Portal;src:url("../portal-plus-complete/assets/Roboto-Regular.ttf")}*{box-sizing:border-box}body{margin:0;background:#14262d;color:#f4f7f9;font:18px/1.5 Portal,sans-serif}main{max-width:1600px;margin:auto;padding:48px 36px}header{margin-bottom:40px;max-width:950px}h1{font-size:42px;line-height:1.2;font-weight:500;margin:0 0 16px}p{color:#b8c9d0}header a{display:inline-block;padding:14px 22px;border-radius:14px;background:#b4dcd0;color:#182c39;text-decoration:none}.screens{display:grid;grid-template-columns:repeat(3,minmax(0,1fr));gap:28px}.screen{display:block;border:1px solid #38505b;border-radius:18px;overflow:hidden;background:#213840;text-decoration:none;color:inherit;padding-bottom:20px}.screen img{display:block;width:100%;aspect-ratio:3/2}.screen h2{font-size:22px;font-weight:500;margin:16px 20px 2px}.screen span{font-size:16px;color:#b8c9d0;margin:0 20px}a:focus-visible{outline:3px solid #9acaff;outline-offset:4px}@media(max-width:1100px){.screens{grid-template-columns:repeat(2,minmax(0,1fr))}}@media(max-width:680px){.screens{grid-template-columns:1fr}main{padding:28px 18px}}</style></head>
<body><main><header><h1>Tools, thoughtfully opened.</h1><p>A browser design reference for eleven ordinary tools plus Advanced Speed test, a retained launcher shell, contextual sheets, and simulated tool flows. The Tools body and rail are transparent over the launcher wallpaper, with no top All tools button. The active bottom Tools tab returns to the library preserving filter and scroll; switching other tabs and back retains the selected tool in this mock.</p><p>These references use a 2160 × 1440 pixel canvas matching the Portal+. All browser data and hardware actions are synthetic or simulated. Actual native checks and performance measurements are available in the separate device evidence gallery. These browser captures do not establish microphone/TTS operation, compatible household RTSP streams, or feed accuracy.</p><a href="./?screen=tools">Explore the interactive mock</a> <a href="./native-validation.html">Actual Portal captures and measured results</a></header><div class="screens">${cards}</div></main></body></html>`);
      expected.step = 'validate published gallery';
      await page.goto(`${origin}/portal-plus-tools-redesign/review.html`);
      assert.equal(await page.locator('.screen').count(), 25, 'Gallery contains all captures');
      assert.equal(await page.getByRole('link', { name: 'Actual Portal captures and measured results', exact: true }).getAttribute('href'), './native-validation.html');
      await page.locator('.screen img').evaluateAll(images => images.forEach(image => { image.loading = 'eager'; }));
      await page.waitForFunction(() => [...document.querySelectorAll('.screen img')].every(image =>
        image.complete && image.naturalWidth === 2160 && image.naturalHeight === 1440));
      clean();
      for (const shot of shots) assert.equal(digest(fs.readFileSync(path.join(folder, shot.file))), shot.sha256, `${shot.file} current bytes`);
      fs.writeFileSync(path.join(folder, 'manifest.json'), JSON.stringify({
        width: 2160, height: 1440, sampleData: true, capturedAt: new Date().toISOString(), errors, shots,
      }, null, 2) + '\n');
      console.log(JSON.stringify({ captured: shots.length, validatedImages: shots.length, width: 2160, height: 1440, errors, review: `${origin}/portal-plus-tools-redesign/review.html` }));
    } catch (error) {
      console.error(JSON.stringify({ expected, diagnostics: await diagnose().catch(error => ({ message: error.message })), errors, requests: requests.slice(-24) }, null, 2));
      throw error;
    }
  } finally { await browser.close(); }
})().catch(error => { console.error(error); process.exitCode = 1; });
