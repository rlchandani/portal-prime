/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */
'use strict';
const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const { chromium } = require('~/.cache/codex-runtimes/codex-primary-runtime/dependencies/node/node_modules/playwright');
const origin = 'http://127.0.0.1:8765';
(async () => {
  const browser = await chromium.launch({ headless: true, channel: 'chrome' });
  const errors = [], checks = [];
  const check = (name, value) => { assert.ok(value, name); checks.push(name); };
  try {
    const context = await browser.newContext({ viewport: { width: 1440, height: 1020 } });
    await context.route('**/*', route => new URL(route.request().url()).origin === origin ? route.continue() : route.abort());
    const page = await context.newPage();
    page.on('pageerror', e => errors.push(e.message));
    page.on('response', r => { if (r.status() >= 400) errors.push(`${r.status()} ${r.url()}`); });
    page.on('requestfailed', r => errors.push(r.failure()?.errorText));
    await page.goto(`${origin}/portal-plus-tools-redesign/?screen=tools&theme=dark`);
    const settle = () => page.waitForFunction(() => !!window.__portalToolsRedesign &&
      !document.querySelector('#navigation-device').getAnimations({ subtree: true }).length);
    await settle();
    const action = a => page.locator(`[data-tool-action="${a}"]:not([inert] *)`).filter({ visible: true });
    const click = async (a, index = 0) => { await action(a).nth(index).click(); await settle(); };
    const state = () => page.evaluate(() => window.__portalToolsRedesign.getState());
    const open = async id => { await page.evaluate(id => window.__portalToolsRedesign.open(id), id); await settle(); };
    const escape = async () => { await page.keyboard.press('Escape'); await settle(); };
    const shots = process.env.PORTAL_BROWSER_SHOTS || path.join(__dirname, 'reference');
    fs.mkdirSync(shots, { recursive: true });
    await page.evaluate(() => {
      window.toolsChrome = { header: document.querySelector('.live-launcher > .app-header'), dock: document.querySelector('.live-launcher > .dock') };
    });
    check('11 ordinary tools and no Advanced speed tile', await page.locator('.tool-entry').count() === 11);
    check('library is visible with nonzero height', await page.locator('.tools-library').evaluate(e => e.getBoundingClientRect().height > 500));
    check('Tools inherits the shared flat Dark launcher wallpaper', await page.evaluate(() =>
      getComputedStyle(document.querySelector('#tools-overhaul')).backgroundColor === 'rgba(0, 0, 0, 0)' &&
      getComputedStyle(document.querySelector('[data-tab="tools"]')).backgroundColor === 'rgba(0, 0, 0, 0)' &&
      getComputedStyle(document.querySelector('.live-launcher')).backgroundImage === 'none' &&
      getComputedStyle(document.querySelector('.live-launcher')).backgroundColor === 'rgb(20, 22, 27)'));
    await page.screenshot({ path: path.join(shots, '01-tools-library.png') });
    await page.getByRole('textbox', { name: 'Find a tool' }).fill('camera');
    check('tool search filters', await page.locator('.tool-entry').count() === 1);
    await page.getByRole('textbox', { name: 'Find a tool' }).fill('');
    await page.locator('#tools-advanced').check();
    check('Advanced reveals speed test', await page.locator('.tool-entry').count() === 12);
    const ids = await page.locator('.tool-entry').evaluateAll(nodes => nodes.map(n => n.dataset.tool));
    for (const id of ids) {
      await page.locator(`.tool-entry[data-tool="${id}"]`).click();
      await settle();
      check(`${id} opens in the retained launcher`, await page.evaluate(() => window.__portalNavigationDemo.getState().host === 'launcher'));
      check(`${id} has one workspace title and no modal on entry`, await page.locator('#tool-title').count() === 1 && await page.locator('.tool-overlay').count() === 0);
      check(`${id} keeps header and dock`, await page.evaluate(() =>
        window.toolsChrome.header === document.querySelector('.live-launcher > .app-header') &&
        window.toolsChrome.dock === document.querySelector('.live-launcher > .dock')));
      check(`${id} workspace fits beside its rail`, await page.locator('.workspace-body').evaluate(e => e.clientWidth > 750 && e.clientHeight > 500));
      check(`${id} has no redundant All tools return`, await page.locator('[data-tool-action="library"]').count() === 0);
      await page.locator('.live-launcher > .dock [data-screen="tools"]').click();
      await settle();
      check(`${id} bottom Tools tab returns to its library`, !(await state()).tool &&
        await page.locator('.tools-library').isVisible());
    }
    await open('alarm');
    await page.evaluate(() => {
      window.themeToolsNodes = { root: document.querySelector('#tools-overhaul'),
        body: document.querySelector('.workspace-body'), rail: document.querySelector('.tool-rail'),
        route: JSON.stringify(__portalNavigationDemo.getState()) };
    });
    await page.locator('.live-launcher > .app-header [data-action="theme"]').click();
    check('Tools day theme uses dark text, white surfaces and white primary text on blue', await page.evaluate(() => {
      const tools = document.querySelector('#tools-overhaul');
      const panel = tools.querySelector('.tool-panel');
      const primary = tools.querySelector('.primary-button');
      const header = document.querySelector('.live-launcher > .app-header');
      return __portalNavigationDemo.getState().theme === 'light' &&
        getComputedStyle(tools).color === 'rgb(24, 44, 57)' &&
        getComputedStyle(panel).backgroundColor === 'rgb(255, 255, 255)' &&
        getComputedStyle(primary).backgroundColor === 'rgb(23, 106, 187)' &&
        getComputedStyle(primary).color === 'rgb(255, 255, 255)' &&
        getComputedStyle(header).color === 'rgb(24, 44, 57)' &&
        getComputedStyle(header).getPropertyValue('--surface') === '#ffffff';
    }));
    check('Tools day glyphs match each native tool tint', await page.evaluate(() => {
      const tints = { alarm: '153, 97, 15', timers: '153, 97, 15', countdowns: '23, 106, 187',
        converter: '23, 106, 187', lamp: '133, 97, 33', notes: '133, 97, 33', camera: '40, 115, 90',
        intercom: '40, 115, 90', stories: '116, 82, 157', iss: '23, 106, 187', aurora: '40, 115, 90',
        speed: '23, 106, 187' };
      return [...document.querySelectorAll('.rail-tool')].every(node =>
        getComputedStyle(node.querySelector('span')).color === `rgb(${tints[node.dataset.tool]})`);
    }));
    check('Tools theme inherits the parent wash with a transparent rail and retained workspace', await page.evaluate(() =>
      themeToolsNodes.root === document.querySelector('#tools-overhaul') &&
      themeToolsNodes.body === document.querySelector('.workspace-body') &&
      themeToolsNodes.rail === document.querySelector('.tool-rail') &&
      toolsChrome.header === document.querySelector('.live-launcher > .app-header') &&
      toolsChrome.dock === document.querySelector('.live-launcher > .dock') &&
      getComputedStyle(themeToolsNodes.root).backgroundColor === 'rgba(0, 0, 0, 0)' &&
      getComputedStyle(themeToolsNodes.rail).backgroundColor === 'rgba(0, 0, 0, 0)' &&
      getComputedStyle(document.querySelector('.live-launcher')).backgroundImage.includes('0.92') &&
      document.querySelector('.live-launcher [data-action="theme"]').getAttribute('aria-label') === 'Switch to night theme'));
    await page.locator('.live-launcher > .app-header [data-action="theme"]').click();
    check('Tools night restores white accent with dark primary text and exact existing glyph tints', await page.evaluate(() => {
      const primary = document.querySelector('#tools-overhaul .primary-button');
      const tints = { alarm: '238, 193, 132', timers: '238, 193, 132', countdowns: '169, 201, 245',
        converter: '169, 201, 245', lamp: '238, 214, 166', notes: '238, 214, 166', camera: '180, 220, 208',
        intercom: '180, 220, 208', stories: '196, 182, 231', iss: '169, 201, 245', aurora: '180, 220, 208',
        speed: '169, 201, 245' };
      return getComputedStyle(primary).backgroundColor === 'rgb(255, 255, 255)' &&
        getComputedStyle(primary).color === 'rgb(20, 22, 27)' &&
        getComputedStyle(document.querySelector('#tools-overhaul')).color === 'rgb(255, 255, 255)' &&
        [...document.querySelectorAll('.rail-tool')].every(node =>
          getComputedStyle(node.querySelector('span')).color === `rgb(${tints[node.dataset.tool]})`) &&
        !getComputedStyle(document.querySelector('.live-launcher')).backgroundImage.includes('0.92') &&
        JSON.stringify(__portalNavigationDemo.getState()) === themeToolsNodes.route;
    }));
    await click('alarm-time');
    await page.locator('[name=hour]').fill('9');
    await page.evaluate(() => { window.themeDraft = document.querySelector('.tool-sheet [name=hour]'); });
    await page.locator('#demo-theme').selectOption('light');
    check('open Tools sheet follows day theme without losing its unsubmitted time draft', await page.evaluate(() =>
      themeDraft === document.querySelector('.tool-sheet [name=hour]') && themeDraft.value === '9' &&
      getComputedStyle(document.querySelector('.tool-sheet')).backgroundColor === 'rgb(255, 255, 255)' &&
      getComputedStyle(document.querySelector('.tool-sheet')).color === 'rgb(24, 44, 57)' &&
      document.querySelector('.live-launcher [data-action="theme"]').dataset.glyph === 'theme_night'));
    await page.locator('#demo-theme').selectOption('dark');
    check('open Tools sheet follows night theme while retaining the same draft field', await page.evaluate(() =>
      themeDraft === document.querySelector('.tool-sheet [name=hour]') && themeDraft.value === '9' &&
      getComputedStyle(document.querySelector('.tool-sheet')).backgroundColor === 'rgb(43, 43, 43)' &&
      document.querySelector('.live-launcher [data-action="theme"]').getAttribute('aria-label') === 'Switch to day theme'));
    await escape();
    await open('camera');
    await page.locator('#demo-theme').selectOption('light');
    check('day camera placeholder uses surface2 and day secondary text', await page.locator('.camera-preview').evaluate(node =>
      getComputedStyle(node).backgroundColor === 'rgb(229, 235, 242)' &&
      getComputedStyle(node).color === 'rgb(83, 102, 116)'));
    await click('camera-view');
    check('camera fullscreen keeps native dark controls when launcher is day', await page.locator('.fullscreen-tool').evaluate(node =>
      getComputedStyle(node).color === 'rgb(255, 255, 255)' &&
      getComputedStyle(node).getPropertyValue('--tools-surface').trim() === '#2b2b2b'));
    await escape();
    await open('lamp'); await click('lamp-preview');
    check('lamp fullscreen keeps native dark controls when launcher is day', await page.locator('.fullscreen-tool').evaluate(node =>
      getComputedStyle(node).color === 'rgb(255, 255, 255)' &&
      getComputedStyle(node).getPropertyValue('--tools-accent-ink').trim() === '#14161b'));
    await escape();
    await open('alarm'); await click('alarm-preview');
    check('alarm fullscreen keeps native dark controls when launcher is day', await page.locator('.fullscreen-tool').evaluate(node =>
      getComputedStyle(node).color === 'rgb(255, 255, 255)' &&
      getComputedStyle(node).getPropertyValue('--tools-surface').trim() === '#2b2b2b'));
    await escape();
    await page.locator('#demo-theme').selectOption('dark');
    await open('alarm');
    check('tool rail has no separate background fill', await page.locator('.tool-rail').evaluate(e =>
      getComputedStyle(e).backgroundColor === 'rgba(0, 0, 0, 0)'));
    await page.screenshot({ path: path.join(shots, '02-alarm-workspace.png') });
    await click('alarm-time');
    await page.locator('[name=hour]').fill('8');
    await click('picker');
    await page.getByRole('button', { name: '45', exact: true }).click();
    check('nested time picker returns to draft', (await state()).sheet.kind === 'alarm-time' && !(await state()).picker);
    await page.locator('.tool-sheet button[type=submit]').click();
    check('time saved in same workspace', (await state()).alarm.hour === 8 && (await state()).alarm.minute === 45 && (await state()).tool === 'alarm');
    await click('alarm-preview');
    check('ring preview covers launcher', await page.locator('.fullscreen-tool').evaluate(e => e.getBoundingClientRect().height > 900));
    await click('alarm-snooze');
    check('snooze returns to alarm controls', !(await state()).full && (await state()).tool === 'alarm');
    await open('timers');
    await click('timer-custom');
    await page.locator('[name=minutes]').fill('0');
    await page.locator('.tool-sheet button[type=submit]').click();
    check('zero-duration timer rejected in context', await page.locator('.form-error').isVisible());
    await page.locator('[name=minutes]').fill('5');
    await page.locator('.tool-sheet button[type=submit]').click();
    check('custom timer starts', (await state()).timers.length === 1);
    await click('timer-cancel'); await escape();
    check('cancel confirmation Back keeps timer', (await state()).timers.length === 1 && !(await state()).sheet);
    await click('timer-cancel'); await page.locator('.tool-sheet button[type=submit]').click();
    check('confirm cancels selected timer', (await state()).timers.length === 0);
    await open('countdowns'); await click('countdown-add');
    await page.locator('[name=name]').fill('Holiday');
    await page.locator('[name=yearly]').uncheck();
    check('year appears for one-off countdown', await page.locator('[name=year]').isVisible());
    await click('picker', 2);
    await page.getByRole('button', { name: '✈️', exact: true }).click();
    await page.locator('.tool-sheet button[type=submit]').click();
    check('countdown added with icon and repeat state', (await state()).countdowns.at(-1).icon === '✈️' && !(await state()).countdowns.at(-1).yearly);
    await open('camera'); await click('camera-add');
    await page.locator('[name=name]').fill('Porch');
    await page.locator('[name=url]').fill('https://unsupported.example/video');
    await page.locator('.tool-sheet button[type=submit]').click();
    check('camera accepts supported RTSP only', await page.locator('.form-error').isVisible());
    await page.locator('[name=url]').fill('rtsp://sample-camera.local/porch');
    await page.locator('.tool-sheet button[type=submit]').click();
    check('camera add stays in Cameras', (await state()).cameras.length === 2 && (await state()).tool === 'camera');
    await click('camera-view');
    await page.screenshot({ path: path.join(shots, '03-camera-display.png') });
    await escape();
    check('camera Back retains saved cameras and workspace', !(await state()).full && (await state()).cameras.length === 2);
    await open('lamp'); await click('lamp-preview');
    await page.locator('.fullscreen-tool [name=lamp-brightness]').fill('75');
    check('preview slider does not reactivate underlying launcher', await page.locator('.live-launcher').evaluate(e => e.inert));
    await click('lamp-number');
    await page.locator('.fullscreen-tool [name=percent]').fill('80');
    await page.locator('.fullscreen-tool .tool-sheet button[type=submit]').click();
    check('numeric lamp sheet returns to fullscreen preview', (await state()).full === 'lamp' && (await state()).lamp.brightness === 80 && !(await state()).sheet);
    await click('lamp-red');
    check('red light hides warmth', await page.locator('.fullscreen-tool [name=lamp-warmth]').count() === 0);
    await click('lamp-controls');
    check('lamp controls can hide', await page.locator('.fullscreen-controls').count() === 0);
    await click('lamp-controls'); await escape();
    check('lamp values survive preview return', (await state()).lamp.brightness === 80 && (await state()).lamp.red);
    await open('notes');
    await page.locator('[name=note]').fill('Please water the plants.');
    await page.locator('[data-tool-form=note] button[type=submit]').click();
    check('text note saved', (await state()).note === 'Please water the plants.');
    await page.locator('[name=note]').fill('Unsaved draft stays here.');
    await page.evaluate(() => { window.noteThemeDraft = document.querySelector('[name=note]'); });
    await page.locator('.live-launcher > .app-header [data-action=theme]').click();
    await page.locator('.live-launcher > .app-header [data-action=theme]').click();
    check('header theme changes preserve the Notes draft, textarea and selected tool', await page.evaluate(() =>
      noteThemeDraft === document.querySelector('[name=note]') &&
      noteThemeDraft.value === 'Unsaved draft stays here.' && __portalToolsRedesign.getState().tool === 'notes'));
    await open('converter'); await open('notes');
    check('unsaved note draft survives tool switch', await page.locator('[name=note]').inputValue() === 'Unsaved draft stays here.');
    await click('memo-record'); await click('memo-record'); await click('memo-play');
    check('voice memo controls cover record/stop/play', (await state()).memo === 'playing');
    await open('intercom');
    check('leaving Notes stops playback', (await state()).memo === 'saved');
    await click('broadcast');
    check('broadcast starts explicitly', (await state()).intercom === 'broadcasting');
    await page.locator('.live-launcher > .dock [data-screen=apps]').click(); await settle();
    check('tab exit stops broadcasting', (await state()).intercom === 'idle' && !(await state()).active);
    await page.locator('.live-launcher > .dock [data-screen=tools]').click(); await settle();
    check('tab return retains selected tool', (await state()).tool === 'intercom' && (await state()).active);
    await open('stories'); await click('story-open');
    await page.screenshot({ path: path.join(shots, '04-story-reader.png') });
    await click('story-read'); await escape();
    check('reader Back stops narration and returns to picker', !(await state()).reading && (await state()).story === null && (await state()).tool === 'stories');
    await open('converter');
    await click('picker', 0); await page.getByRole('button', { name: 'Temperature', exact: true }).click();
    await page.locator('[name=value]').fill('100');
    check('converter calculates temperature locally', await page.locator('#conversion-result').textContent() === '212 °F');
    await click('convert-swap');
    check('unit swap updates result', (await state()).converter.from === '°F');
    await open('iss'); await page.locator('#tools-scenario').selectOption('offline');
    check('ISS offline prerequisite shown', await page.getByRole('heading', { name: 'Passes aren’t available yet' }).isVisible());
    await click('refresh'); check('offline retry stays in tool', (await state()).tool === 'iss');
    await open('aurora'); check('Aurora offline state', await page.getByRole('heading', { name: 'Forecast unavailable' }).isVisible());
    await page.locator('#tools-scenario').selectOption('normal');
    check('Aurora preserves native metrics', await page.getByText('Next 24-hour peak', { exact: true }).isVisible());
    await open('speed'); await click('speed-start'); await click('speed-cancel');
    check('explicit speed cancel', (await state()).speed === 'idle');
    await click('speed-start');
    await page.waitForFunction(() => window.__portalToolsRedesign.getState().speed === 'done');
    check('sample speed test completes all phases', (await state()).speed === 'done');
    await page.locator('#tools-advanced').uncheck();
    check('hiding Advanced exits speed detail', !(await state()).tool && await page.locator('.tool-entry').count() === 11);
    await open('notes'); await page.locator('#tools-scenario').selectOption('permission'); await click('permission');
    await page.locator('.tool-sheet button[type=submit]').click();
    check('permission preview returns to Notes', (await state()).scenario === 'normal' && (await state()).tool === 'notes');
    await open('camera'); await page.locator('#tools-scenario').selectOption('empty');
    check('camera empty state has contextual add', await page.getByRole('heading', { name: 'A view of home' }).isVisible() && await action('camera-add').isVisible());
    await page.locator('#tools-scenario').selectOption('normal');
    await click('camera-add');
    await page.screenshot({ path: path.join(shots, '05-camera-setup-sheet.png') });
    await escape();
    check('setup Cancel stays in originating tool', (await state()).tool === 'camera' && !(await state()).sheet);
    const small = await browser.newContext({ viewport: { width: 900, height: 650 }, reducedMotion: 'reduce' });
    await small.route('**/*', route => new URL(route.request().url()).origin === origin ? route.continue() : route.abort());
    const smallPage = await small.newPage();
    await smallPage.goto(`${origin}/portal-plus-tools-redesign/?screen=tools&tool=lamp`);
    await smallPage.waitForSelector('#tool-title');
    check('direct review link opens selected tool', await smallPage.locator('#tool-title').textContent() === 'Lamp');
    check('reduced-motion mode has no active animations', await smallPage.locator('#navigation-device').evaluate(e => !e.getAnimations({ subtree: true }).length));
    check('zero browser errors', errors.length === 0);
    console.log(JSON.stringify({ passed: checks.length, errors, screenshots: shots, checks }, null, 2));
  } finally { await browser.close(); }
})().catch(e => { console.error(e); process.exitCode = 1; });
