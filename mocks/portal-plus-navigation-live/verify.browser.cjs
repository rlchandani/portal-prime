/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */
'use strict';
const assert = require('node:assert/strict');
const playwrightModule = '~/.cache/codex-runtimes/codex-primary-runtime/dependencies/node/node_modules/playwright';
assert.ok(!process.env.PLAYWRIGHT_MODULE || process.env.PLAYWRIGHT_MODULE === playwrightModule,
  'Use only the authorized Playwright runtime');
const { chromium } = require(playwrightModule);
const origin = 'http://127.0.0.1:8765';
assert.ok(!process.env.DEMO_ORIGIN || process.env.DEMO_ORIGIN === origin,
  'Browser verification is restricted to the authorized loopback origin');

(async () => {
  const browser = await chromium.launch({ headless: true, channel: 'chrome' });
  const checks = [];
  const errors = [];
  const watchPage = page => {
    page.on('pageerror', error => errors.push(error.message));
    page.on('console', message => { if (message.type() === 'error') errors.push(message.text()); });
    page.on('requestfailed', request => errors.push(`${request.failure()?.errorText} ${request.url()}`));
    page.on('response', response => { if (response.status() >= 400) errors.push(`${response.status()} ${response.url()}`); });
  };
  try {
    const context = await browser.newContext({ viewport: { width: 1440, height: 1020 } });
    await context.route('**/*', route =>
      new URL(route.request().url()).origin === origin ? route.continue() : route.abort());
    const page = await context.newPage();
    watchPage(page);
    await page.goto(`${origin}/portal-plus-navigation-live/`);
    await page.waitForFunction(() => !!window.__portalNavigationDemo);
    const root = page.locator('#navigation-device');
    const settled = () => page.waitForFunction(() => document.querySelector('#navigation-device').getAnimations({ subtree: true }).length === 0);
    const click = async selector => { await root.locator(selector).click(); await settled(); };
    const state = () => page.evaluate(() => window.__portalNavigationDemo.getState());
    const check = (name, value) => { assert.ok(value, name); checks.push(name); };
    await page.evaluate(() => {
      window.auditNodes = {
        document, header: document.querySelector('.live-launcher > .app-header'),
        dock: document.querySelector('.live-launcher > .dock'),
        sidebar: document.querySelector('.live-settings .settings-sidebar'),
        settingsHeader: document.querySelector('.live-settings > .app-header'),
        homePanel: document.querySelector('[data-tab="home"]'),
      };
    });

    await click('.live-launcher > .dock [data-screen="apps"]');
    await page.evaluate(() => {
      const scroll = document.querySelector('[data-tab="apps"] .screen-body');
      // The refined sample grid fits the Portal. Constrain it to exercise real overflow.
      scroll.style.maxHeight = '620px';
      scroll.scrollTop = 260;
      window.appsScrollBefore = scroll.scrollTop;
      window.appsContentBefore = scroll;
    });
    await root.locator('.live-launcher > .dock [data-screen="tools"]').click();
    const tabAnimation = await page.evaluate(() => ({
      durations: document.querySelector('[data-tab="tools"]').getAnimations().map(animation => animation.effect.getTiming().duration),
      header: document.querySelector('.live-launcher > .app-header').getAnimations().length,
      dock: document.querySelector('.live-launcher > .dock').getAnimations().length,
    }));
    check('tab transition is 160 ms and chrome is not animated',
      tabAnimation.durations.includes(160) && tabAnimation.header === 0 && tabAnimation.dock === 0);
    await settled();
    check('launcher header/dock/document nodes survive Home → Apps → Tools', await page.evaluate(() =>
      auditNodes.document === document && auditNodes.header === document.querySelector('.live-launcher > .app-header') &&
      auditNodes.dock === document.querySelector('.live-launcher > .dock')));
    await click('.live-launcher > .dock [data-screen="apps"]');
    check('Apps content node and scroll survive a tab switch', await page.evaluate(() =>
      appsScrollBefore > 0 && appsContentBefore === document.querySelector('[data-tab="apps"] .screen-body') &&
      document.querySelector('[data-tab="apps"] .screen-body').scrollTop === appsScrollBefore));

    await click('[data-tab="apps"] .primary[data-screen="store"]');
    check('store nests in the Apps host', (await state()).tab === 'apps' && (await state()).route.screen === 'store');
    await page.evaluate(() => { document.querySelector('[data-tab="apps"] .screen-body').scrollTop = 320; });
    const storeScroll = await page.evaluate(() => document.querySelector('[data-tab="apps"] .screen-body').scrollTop);
    await root.locator('[data-tab="apps"] [data-action="product"]').first().click();
    await settled();
    await page.keyboard.press('Escape');
    await settled();
    check('app-detail Back returns to store and restores scroll', (await state()).route.screen === 'store' &&
      await page.evaluate(expected => document.querySelector('[data-tab="apps"] .screen-body').scrollTop === expected, storeScroll));
    await click('[data-tab="apps"] [data-live-action="back"]');
    check('second local Back returns to Apps', (await state()).route.screen === 'apps');

    await click('.live-launcher > .dock [data-screen="tools"]');
    await click('[data-tab="tools"] [data-screen="alarm"]');
    check('Alarm is a Tools detail, not a Settings shell', (await state()).host === 'launcher' &&
      (await state()).tab === 'tools' && (await state()).route.screen === 'alarm' &&
      await page.evaluate(() => document.querySelector('.live-settings').hidden));
    await click('[data-tab="tools"] [data-value="alarm.enabled"]');
    check('approved fixture switches still update', await page.evaluate(() => window.__portalMock.getState().values['alarm.enabled']));
    await page.keyboard.press('Escape');
    await settled();
    check('tool Back returns to Tools list', (await state()).route.screen === 'tools');
    await click('[data-tab="tools"] [data-screen="lamp"]');
    await click('[data-tab="tools"] [data-screen="lamp-preview"]');
    check('native fullscreen tool boundary is labelled as simulation', await root.locator('.live-handoff-label').textContent() === 'Fullscreen handoff simulation');
    await page.keyboard.press('Escape');
    await click('[data-tab="tools"] [data-live-action="back"]');
    check('handoff return preserves Tools route', (await state()).route.screen === 'tools');

    await click('.live-launcher > .app-header [data-screen="settings"]');
    await click('.settings-sidebar [data-id="photos"]');
    await click('.settings-pane:not([hidden]) [data-screen="screensaver"]');
    await page.evaluate(() => {
      const body = document.querySelector('.settings-pane:not([hidden]) .detail-body');
      window.settingsContentBefore = body;
      body.scrollTop = 400;
      window.settingsScrollBefore = body.scrollTop;
    });
    await root.locator('.settings-pane:not([hidden]) [data-screen="sources"]').click();
    const settingsAnimation = await page.evaluate(() => ({
      duration: document.querySelector('.settings-pane:not([inert])').getAnimations()[0]?.effect.getTiming().duration,
      chrome: document.querySelector('.settings-sidebar').getAnimations().length +
        document.querySelector('.live-settings > .app-header').getAnimations().length,
    }));
    check('Settings transition is 160 ms with stationary chrome', settingsAnimation.duration === 160 && settingsAnimation.chrome === 0);
    await settled();
    await page.keyboard.press('Escape');
    await settled();
    check('nested Settings Back preserves detail node and scroll', await page.evaluate(() =>
      settingsScrollBefore > 0 && settingsContentBefore === document.querySelector('.settings-pane:not([hidden]) .detail-body') &&
      document.querySelector('.settings-pane:not([hidden]) .detail-body').scrollTop === settingsScrollBefore));
    check('Settings header/sidebar identities survive category and detail routes', await page.evaluate(() =>
      auditNodes.sidebar === document.querySelector('.settings-sidebar') &&
      auditNodes.settingsHeader === document.querySelector('.live-settings > .app-header')));

    await click('.settings-pane:not([hidden]) [data-screen="sources"]');
    await click('.settings-sidebar [data-advanced-toggle]');
    await root.locator('.settings-pane:not([hidden]) [data-screen="immich"]').click();
    check('credential form uses a labelled separate Activity simulation with a 160 ms fade',
      await page.evaluate(() => __portalNavigationDemo.getState().host === 'activity' &&
        document.querySelector('.activity-boundary').textContent.includes('simulation') &&
        document.querySelector('.live-activity-window').getAnimations().some(animation => animation.effect.getTiming().duration === 160) &&
        getComputedStyle(document.querySelector('.live-activity-window')).backgroundColor !== 'rgba(0, 0, 0, 0)' &&
        auditNodes.sidebar === document.querySelector('.live-settings .settings-sidebar')));
    await settled();
    await root.locator('.activity-pane [data-value="immich.url"]').fill('https://example.invalid');
    await root.locator('.activity-pane [data-value="immich.key"]').fill('public-demo-fixture');
    await click('.activity-pane [data-action="secret"]');
    check('form actions do not accidentally submit or navigate', (await state()).route.screen === 'immich' &&
      (await root.locator('.activity-pane button[type="submit"]').textContent()) === 'Test & connect');
    await click('.activity-pane [data-action="cancel-form"]');
    check('atomic form Cancel restores values and the local parent', (await state()).route.screen === 'sources' &&
      await page.evaluate(() => __portalMock.getState().values['immich.url'] === '' &&
        __portalMock.getState().values['immich.key'] === ''));
    check('internal Activity return retains the parent Settings shell', await page.evaluate(() =>
      !document.querySelector('.live-activity-window') &&
      auditNodes.sidebar === document.querySelector('.live-settings .settings-sidebar')));
    await page.keyboard.press('Escape');
    await settled();

    await page.locator('#demo-theme').selectOption('dark');
    check('Settings appearance applies to the retained Settings shell', await page.evaluate(() =>
      __portalNavigationDemo.getState().theme === 'dark' &&
      getComputedStyle(document.querySelector('.live-settings > .app-header')).color === 'rgb(244, 247, 249)'));
    const launcherColors = await page.evaluate(() => [
      document.querySelector('.live-launcher'),
      document.querySelector('.live-launcher > .app-header'),
      document.querySelector('.live-launcher > .dock'),
      ...document.querySelectorAll('.native-tab-panel'),
    ].map(node => {
      const style = getComputedStyle(node);
      return { background: style.backgroundColor, text: style.color,
        surface: style.getPropertyValue('--surface'), accent: style.getPropertyValue('--accent') };
    }));
    await page.locator('#demo-theme').selectOption('light');
    check('Settings light toggle leaves Home/Apps/Tools colors dark and unchanged',
      await page.evaluate(expected => {
        const nodes = [
          document.querySelector('.live-launcher'), document.querySelector('.live-launcher > .app-header'),
          document.querySelector('.live-launcher > .dock'), ...document.querySelectorAll('.native-tab-panel'),
        ];
        const colors = nodes.map(node => {
          const style = getComputedStyle(node);
          return { background: style.backgroundColor, text: style.color,
            surface: style.getPropertyValue('--surface'), accent: style.getPropertyValue('--accent') };
        });
        return JSON.stringify(colors) === JSON.stringify(expected) &&
          getComputedStyle(nodes[1]).color === 'rgb(244, 247, 249)' &&
          getComputedStyle(document.querySelector('.live-settings > .app-header')).color === 'rgb(24, 44, 57)';
      }, launcherColors));
    await page.locator('#demo-theme').selectOption('dark');
    await click('.settings-sidebar [data-id="appearance"]');
    for (const screen of ['display', 'appearance', 'wallpaper', 'weather', 'calendar', 'quickbar']) {
      await page.evaluate(screen => __portalNavigationDemo.navigate(screen), screen);
      await settled();
      check(`Settings cache bounded after ${screen}`, (await state()).retainedSettings <= 4 &&
        await page.locator('.settings-pane').count() <= 4);
    }
    await page.evaluate(() => __portalNavigationDemo.navigate('screensaver'));
    await settled();
    check('evicted Settings detail restores its saved scroll', await page.evaluate(() =>
      document.querySelector('.settings-pane:not([hidden]) .detail-body').scrollTop === settingsScrollBefore));
    const sharedRoutes = [
      'display', 'appearance', 'wallpaper', 'weather', 'calendar', 'quickbar', 'touch',
      'device', 'system', 'managed', 'debug', 'speed', 'screensaver', 'sources', 'styles',
      'faces', 'clock', 'chime', 'welcome', 'sunrise', 'alarm', 'sleep', 'world', 'health',
      'multiroom', 'mqtt', 'boot', 'countdowns', 'crypto', 'todo', 'news', 'stocks',
      'options', 'alpaca', 'ha', 'intercom',
    ];
    for (const route of sharedRoutes) {
      await page.evaluate(route => __portalNavigationDemo.navigate(route), route);
      await settled();
      assert.equal((await state()).host, 'settings', `${route} must use the shared Settings host`);
      assert.ok((await state()).retainedSettings <= 4, `${route} exceeded the Settings DOM bound`);
    }
    check('all supplied shared native pages stay in the bounded Settings host', true);
    await page.evaluate(() => __portalNavigationDemo.navigate('ha'));
    await settled();
    await click('.settings-pane:not([hidden]) [data-screen="ha-entities"]');
    check('HA entity fixture opens a separate labelled HaSettingsActivity simulation',
      (await state()).host === 'activity' && (await state()).route.screen === 'ha-entities' &&
      (await root.locator('.activity-boundary').textContent()).includes('Separate internal Activity simulation'));
    await page.keyboard.press('Escape');
    await settled();
    assert.equal((await state()).route.screen, 'ha', 'HA entity Back returns to shared HA controls');
    await page.evaluate(() => __portalNavigationDemo.navigate('sources'));
    await settled();
    const internalRoutes = ['immich', 'albums', 'smb', 'dav', 'folder', 'dismiss', 'album', 'web', 'calendar-url', 'phone', 'help', 'apk', 'ha-entities'];
    for (const route of internalRoutes) {
      await page.evaluate(route => __portalNavigationDemo.navigate(route), route);
      await settled();
      assert.equal((await state()).host, 'activity', `${route} must mark a separate internal Activity`);
      assert.equal(await page.locator('.live-activity-window').count(), 1);
      await page.keyboard.press('Escape');
      await settled();
      assert.equal((await state()).route.screen, 'sources', `${route} Back must restore its parent`);
    }
    check('all remaining internal Activities are labelled, bounded, and return locally', true);
    await page.evaluate(() => {
      __portalNavigationDemo.navigate('immich');
      __portalNavigationDemo.navigate('albums');
    });
    await settled();
    await page.keyboard.press('Escape');
    await settled();
    check('nested internal Activity Back returns to its local form stage', (await state()).route.screen === 'immich' &&
      (await state()).host === 'activity');
    await page.keyboard.press('Escape');
    await settled();
    for (const route of ['music', 'camera-preview', 'lamp-preview', 'alarm-ring']) {
      await page.evaluate(route => __portalNavigationDemo.navigate(route), route);
      assert.ok((await state()).simulation, `${route} must mark its fullscreen boundary`);
      await page.keyboard.press('Escape');
    }
    check('Music, camera, Lamp, and alarm fullscreen policies remain simulated handoffs', true);
    await click('.live-settings > .app-header [data-screen="home"]');
    check('Home panel stays retained with a dark launcher palette', await page.evaluate(() =>
      auditNodes.homePanel === document.querySelector('[data-tab="home"]') &&
      getComputedStyle(document.querySelector('.live-launcher > .app-header')).color === 'rgb(244, 247, 249)'));
    await click('.live-launcher > .dock [data-screen="apps"]');
    await root.locator('[data-tab="apps"] [data-action="launch"]').first().click();
    await settled();
    check('third-party app opening is labelled simulation', (await root.locator('.live-handoff-label').textContent()).includes('simulation'));
    await page.keyboard.press('Escape');
    await click('.live-launcher > .dock [data-screen="photos"]');
    check('Photos marks a fullscreen simulation without navigation', (await state()).route.screen === 'apps' &&
      (await root.locator('.live-handoff-label').textContent()).includes('Fullscreen'));
    await page.keyboard.press('Escape');
    await page.evaluate(() => {
      for (const screen of ['tools', 'home', 'apps', 'tools', 'home']) __portalNavigationDemo.navigate(screen, null, { switching: true });
    });
    await settled();
    check('rapid navigation leaves only the intended tab interactive', await page.evaluate(() =>
      __portalNavigationDemo.getState().tab === 'home' &&
      document.querySelectorAll('.native-tab-panel:not([hidden])').length === 1 &&
      document.querySelectorAll('.native-tab-panel:not([inert])').length === 1));
    check('exactly three native tab DOM panels', await page.locator('.native-tab-panel').count() === 3);
    await page.emulateMedia({ reducedMotion: 'reduce' });
    await click('.live-launcher > .dock [data-screen="apps"]');
    check('reduced motion preserves navigation', (await state()).tab === 'apps');
    check('no document navigation or iframe route resets', await page.evaluate(() => auditNodes.document === document && document.querySelectorAll('iframe').length === 0));

    // Fresh touch-capable context: the retained navigation checks above stay intact.
    const touchContext = await browser.newContext({ viewport: { width: 1440, height: 1020 }, hasTouch: true });
    await touchContext.route('**/*', route =>
      new URL(route.request().url()).origin === origin ? route.continue() : route.abort());
    const touchPage = await touchContext.newPage();
    watchPage(touchPage);
    await touchPage.goto(`${origin}/portal-plus-navigation-live/`);
    await touchPage.waitForFunction(() => !!window.__portalNavigationDemo);
    const touchRoot = touchPage.locator('#navigation-device');
    const touchSettled = () => touchPage.waitForFunction(() =>
      document.querySelector('#navigation-device').getAnimations({ subtree: true }).length === 0);
    const touchClick = async selector => { await touchRoot.locator(selector).click(); await touchSettled(); };
    await touchClick('.live-launcher > .dock [data-screen="apps"]');
    await touchPage.evaluate(() => {
      window.appLaunches = 0;
      document.querySelector('[data-tab="apps"] .screen-body').style.maxHeight = '620px';
      document.querySelector('#device').addEventListener('click', event => {
        const data = event.target.closest('[data-action]')?.dataset;
        if (data?.action === 'launch' || (data?.action === 'app-option' && data.name === 'Open')) appLaunches++;
      }, true);
    });
    const appSelector = '[data-tab="apps"] .app-tile[data-action="launch"][data-pkg="org.chromium.chrome"]';
    const tile = touchRoot.locator(appSelector);
    const modal = touchRoot.locator('#live-overlays .modal');
    const launchCount = () => touchPage.evaluate(() => appLaunches);
    const menuOptions = () => modal.locator('[data-action="app-option"]').allTextContents();
    const expectedOptions = ['Open', 'App info', 'Close (free up memory)', 'Uninstall'];
    const waitForMenu = () => touchPage.waitForFunction(() =>
      !!document.querySelector('#live-overlays [data-action="app-option"]'), null, { timeout: 2000 });
    const dismiss = async () => { await touchPage.keyboard.press('Escape'); await touchSettled(); };
    const center = async () => {
      await tile.scrollIntoViewIfNeeded();
      const box = await tile.boundingBox();
      return { x: box.x + box.width / 2, y: box.y + box.height / 2 };
    };

    const mousePoint = await center();
    await touchPage.mouse.move(mousePoint.x, mousePoint.y);
    await touchPage.mouse.down();
    await waitForMenu();
    check('pointer hold opens the existing app menu without launching',
      await launchCount() === 0 && JSON.stringify(await menuOptions()) === JSON.stringify(expectedOptions));
    // Dismiss while still pressed: release over the now-active tile must not launch it.
    await dismiss();
    await touchPage.mouse.up();
    check('recognized hold suppresses release click even after menu dismissal',
      await launchCount() === 0 && await modal.count() === 0);
    await tile.click();
    check('a fresh regular all-app click still launches once after a hold',
      await launchCount() === 1 && await modal.locator('.external-note').count() === 1);
    await dismiss();

    check('Apps has no Options text or separate app-menu controls',
      !/\bOptions\b/.test(await touchRoot.locator('[data-tab="apps"]').innerText()) &&
      await touchRoot.locator('[data-tab="apps"] [data-action="app-menu"]').count() === 0);
    check('Apps omits the duplicate Tools tile while retaining the footer Tools dock',
      await touchRoot.locator('[data-tab="apps"] .app-grid [data-screen="tools"]').count() === 0 &&
      await touchRoot.locator('.live-launcher > .dock [data-screen="tools"]').isVisible());

    await tile.click({ button: 'right' });
    check('desktop contextmenu opens the unchanged app actions without launch',
      await launchCount() === 1 && JSON.stringify(await menuOptions()) === JSON.stringify(expectedOptions));
    await dismiss();
    check('menu dismissal restores focus to the installed app tile', await tile.evaluate(node => document.activeElement === node));
    check('installed tiles expose accessible menu instructions',
      await tile.getAttribute('aria-haspopup') === 'dialog' &&
      /Shift\+F10/.test(await tile.getAttribute('aria-description')));
    for (const key of ['Shift+F10', 'ContextMenu']) {
      await tile.focus();
      await touchPage.keyboard.press(key);
      check(`${key} opens the existing app menu without launch`,
        await launchCount() === 1 && JSON.stringify(await menuOptions()) === JSON.stringify(expectedOptions));
      await dismiss();
    }
    await tile.focus();
    await touchPage.keyboard.press('Enter');
    check('keyboard Enter preserves regular app launch',
      await launchCount() === 2 && await modal.locator('.external-note').count() === 1);
    await dismiss();
    await tile.focus();
    await touchPage.keyboard.press('Shift+F10');
    await touchClick('#live-overlays [data-action="app-option"][data-name="App info"]');
    check('existing App info action retains its Android simulation',
      /App info Browser/.test(await modal.innerText()) && await modal.locator('.external-note').count() === 1);
    await dismiss();

    // Real browser touch input includes compatibility click synthesis on touchEnd.
    const cdp = await touchContext.newCDPSession(touchPage);
    const touch = (type, points = []) => cdp.send('Input.dispatchTouchEvent', {
      type, touchPoints: points.map((point, id) => ({ ...point, id, radiusX: 1, radiusY: 1, force: 1 })),
    });
    const touchPoint = await center();
    await touch('touchStart', [touchPoint]);
    await waitForMenu();
    check('real touch hold opens the app action menu without launch',
      await launchCount() === 2 && JSON.stringify(await menuOptions()) === JSON.stringify(expectedOptions));
    await touch('touchEnd');
    check('real touch release does not activate Open or launch the tile',
      await launchCount() === 2 && JSON.stringify(await menuOptions()) === JSON.stringify(expectedOptions));
    await dismiss();
    await tile.tap();
    check('a regular touch tap launches the installed app exactly once',
      await launchCount() === 3 && await modal.locator('.external-note').count() === 1);
    await dismiss();

    // Each cancellation is followed by a full hold interval, catching stale timers.
    const pointer = (type, extra = {}) => tile.dispatchEvent(type, {
      pointerId: 71, pointerType: 'touch', isPrimary: true, button: 0,
      clientX: touchPoint.x, clientY: touchPoint.y, bubbles: true, ...extra,
    });
    for (const reason of ['movement', 'scroll', 'pointercancel', 'release', 'navigation', 'blur', 'second pointer']) {
      await pointer('pointerdown');
      if (reason === 'movement') {
        await pointer('pointermove', { clientX: touchPoint.x + 30 });
        await pointer('pointermove'); // Returning to the origin must not restart the timer.
      } else if (reason === 'scroll') {
        await touchPage.evaluate(() => { document.querySelector('[data-tab="apps"] .screen-body').scrollTop += 80; });
      } else if (reason === 'pointercancel') {
        await pointer('pointercancel');
      } else if (reason === 'release') {
        await pointer('pointerup');
      } else if (reason === 'navigation') {
        await touchPage.evaluate(() => __portalNavigationDemo.navigate('tools', null, { switching: true }));
      } else if (reason === 'blur') {
        await touchPage.evaluate(() => window.dispatchEvent(new Event('blur')));
      } else {
        await pointer('pointerdown', { pointerId: 72, isPrimary: false });
      }
      await touchPage.waitForTimeout(750);
      check(`${reason} cancels the pending app hold without a stale menu or launch`,
        await modal.count() === 0 && await launchCount() === 3);
      await pointer('pointerup');
      if (reason === 'navigation') await touchClick('.live-launcher > .dock [data-screen="apps"]');
    }

    await touchPage.evaluate(() => {
      window.touchCancellations = 0;
      document.addEventListener('pointercancel', () => touchCancellations++, true);
    });
    const swipePoint = await center();
    const beforeSwipe = await touchPage.evaluate(() => document.querySelector('[data-tab="apps"] .screen-body').scrollTop);
    await touch('touchStart', [swipePoint]);
    await touch('touchMove', [{ x: swipePoint.x, y: swipePoint.y - 100 }]);
    await touch('touchMove', [{ x: swipePoint.x, y: swipePoint.y - 200 }]);
    await touchPage.waitForTimeout(750);
    await touch('touchEnd');
    check('real touch scrolling stays enabled and cancels hold without launch',
      await touchPage.evaluate(before => touchCancellations > 0 &&
        document.querySelector('[data-tab="apps"] .screen-body').scrollTop > before, beforeSwipe) &&
      await modal.count() === 0 && await launchCount() === 3);

    await touchPage.evaluate(() => { __portalMock.configure({ advanced: true }); });
    await touchSettled();
    check('Apps removals survive fixture refresh',
      await touchRoot.locator('[data-tab="apps"] [data-action="app-menu"],[data-tab="apps"] .app-grid [data-screen="tools"]').count() === 0);
    await touchPage.evaluate(() => __portalNavigationDemo.navigate('options'));
    await touchSettled();
    check('Options scanner in Settings remains available outside Apps',
      await touchRoot.locator('.settings-pane:not([hidden]) [data-value="options.symbol"]').isVisible());
    await touchPage.evaluate(() => __portalNavigationDemo.navigate('apps'));
    await touchSettled();
    await touchClick('.live-launcher > .dock [data-screen="tools"]');
    check('preserved footer Tools dock still opens the Tools category',
      await touchPage.evaluate(() => __portalNavigationDemo.getState().route.screen === 'tools') &&
      await touchRoot.locator('[data-tab="tools"] [data-screen="alarm"]').isVisible());
    await touchContext.close();

    const widgetContext = await browser.newContext({ viewport: { width: 1440, height: 1020 } });
    await widgetContext.route('**/*', route =>
      new URL(route.request().url()).origin === origin ? route.continue() : route.abort());
    const widgetPage = await widgetContext.newPage();
    watchPage(widgetPage);
    await widgetPage.goto(`${origin}/portal-plus-navigation-live/`);
    await widgetPage.waitForFunction(() => !!window.__portalNavigationDemo);
    // Establish the approved photo-first default via the existing indexed action;
    // the frozen sample's raw order instead puts Photo last.
    await widgetPage.evaluate(() => __portalMock.showAction('widget-move-to', { from: '8', to: '0' }));
    await widgetPage.waitForFunction(() =>
      !!document.querySelector('.live-widget-grid [data-live-widget-index="0"][data-live-widget-kind="photo_memory"]'));
    const widgetRoot = widgetPage.locator('#navigation-device');
    const widgetSettled = () => widgetPage.waitForFunction(() =>
      document.querySelector('#navigation-device').getAnimations({ subtree: true }).length === 0);
    const widgetClick = async selector => { await widgetRoot.locator(selector).click(); await widgetSettled(); };
    const layout = () => widgetPage.evaluate(() => {
      const grid = document.querySelector('[data-tab="home"] .home-grid');
      if (!grid) return null;
      const rect = node => {
        const box = node.getBoundingClientRect();
        return ['x', 'y', 'width', 'height'].map(key => Math.round(box[key] * 1000) / 1000);
      };
      return {
        page: document.querySelector('[data-tab="home"] .widget-page > span:not(.live-widget-toolbar)')?.textContent,
        grid: rect(grid),
        viewport: rect(grid.closest('.home-body')),
        greeting: rect(grid.closest('.home-body').querySelector('.greeting')),
        favorites: rect(grid.closest('.home-body').querySelector('.favorite-section')),
        cards: [...grid.querySelectorAll('.home-card')].map(card => ({
          name: card.classList.contains('photo-card') ? 'Photo memory' : card.querySelector('.card-heading > span')?.textContent,
          box: rect(card),
        })),
      };
    });
    const placements = () => widgetPage.evaluate(() =>
      [...document.querySelectorAll('[data-tab="home"] .home-grid > [data-live-widget-index]')].map(slot => ({
        index: Number(slot.dataset.liveWidgetIndex), kind: slot.dataset.liveWidgetKind,
        column: slot.style.gridColumn, row: slot.style.gridRow,
      })));
    const noOverlap = async () => widgetPage.evaluate(() => {
      const boxes = [...document.querySelectorAll('[data-tab="home"] .home-grid > [data-live-widget-index]')]
        .map(node => node.getBoundingClientRect());
      return boxes.every((box, index) => boxes.slice(index + 1).every(other =>
        Math.min(box.right, other.right) - Math.max(box.left, other.left) < 0.1 ||
        Math.min(box.bottom, other.bottom) - Math.max(box.top, other.top) < 0.1));
    });
    const customize = () => widgetClick('.live-launcher > .app-header [data-screen="edit"]');
    const done = async () => {
      await widgetClick('.live-launcher > .app-header [data-live-widget-done]');
      assert.equal(await widgetPage.evaluate(() => __portalNavigationDemo.getState().route.screen), 'home',
        'Done must leave editing, including after visiting the widget library');
    };
    const nextWidgets = () => widgetClick('[data-tab="home"] [data-action="page"][data-dir="1"]');
    const previousWidgets = () => widgetClick('[data-tab="home"] [data-action="page"][data-dir="-1"]');
    const size = async (index, span) => {
      await widgetClick(`[data-live-widget-index="${index}"] [data-action="widget-size"]`);
      await widgetClick(`#live-overlays [data-action="widget-size-set"][data-name="${span}"]`);
    };
    await widgetPage.evaluate(() => {
      window.widgetChrome = {
        header: document.querySelector('.live-launcher > .app-header'),
        dock: document.querySelector('.live-launcher > .dock'),
        customize: document.querySelector('.live-launcher > .app-header [data-screen="edit"]'),
      };
    });
    const defaultHome = await layout();
    await customize();
    check('Customize projects exactly the same Home cards and geometry',
      JSON.stringify(await layout()) === JSON.stringify(defaultHome));
    check('mounted Customize becomes Done while Add widget stays in the Favorite apps row',
      await widgetPage.evaluate(() => widgetChrome.customize === document.querySelector('[data-live-widget-done]') &&
        widgetChrome.customize.textContent === 'Done' &&
        !!document.querySelector('.favorite-section .live-widget-toolbar [data-screen="widgets"]') &&
        !document.querySelector('.app-header .live-widget-toolbar')));
    check('default first-fit page preserves hero plus four original widget indices',
      JSON.stringify(await placements()) === JSON.stringify([
        { index: 0, kind: 'photo_memory', column: '1 / span 2', row: '1 / span 4' },
        { index: 1, kind: 'today', column: '3 / span 1', row: '1 / span 2' },
        { index: 2, kind: 'ha', column: '4 / span 1', row: '1 / span 2' },
        { index: 3, kind: 'calendar', column: '3 / span 1', row: '3 / span 2' },
        { index: 4, kind: 'weather', column: '4 / span 1', row: '3 / span 2' },
      ]));
    check('editing controls overlay the actual cards and retain original action indices',
      await widgetPage.evaluate(() => [...document.querySelectorAll('.live-widget-edit-controls')].length === 5 &&
        [...document.querySelectorAll('.live-widget-edit-controls')].every(controls => {
          const index = controls.parentElement.dataset.liveWidgetIndex;
          return ['widget-size', 'widget-remove', 'widget-move'].every(action =>
            controls.querySelector(`[data-action="${action}"]`)?.dataset.index === index) &&
            !!controls.querySelector('[data-action="go"]');
        })));
    await done();
    check('Done restores identical default Home positions', JSON.stringify(await layout()) === JSON.stringify(defaultHome));
    await nextWidgets();
    const secondPageDefault = await layout();
    await customize();
    check('Customize retains the selected second page and only its cards',
      JSON.stringify(await layout()) === JSON.stringify(secondPageDefault) &&
      JSON.stringify((await placements()).map(item => item.index)) === JSON.stringify([0, 5, 6, 7, 8]));
    await previousWidgets();
    await size(1, '1 × 1');
    const oneResized = await layout();
    check('one explicit resize leaves neighbor sizes and available positions unchanged',
      defaultHome.cards.filter(card => card.name !== 'Today').every(card => {
        const next = oneResized.cards.find(item => item.name === card.name);
        return next.box[2] === card.box[2] && next.box[3] === card.box[3] &&
          (card.name === 'Calendar' || JSON.stringify(next.box) === JSON.stringify(card.box));
      }));
    for (const [index, span] of [[1, '1 × 4'], [2, '1 × 1'], [3, '1 × 1'], [4, '1 × 2']]) await size(index, span);
    check('mixed-size widgets use deterministic first-fit holes without overlap',
      JSON.stringify(await placements()) === JSON.stringify([
        { index: 0, kind: 'photo_memory', column: '1 / span 2', row: '1 / span 4' },
        { index: 1, kind: 'today', column: '3 / span 1', row: '1 / span 4' },
        { index: 2, kind: 'ha', column: '4 / span 1', row: '1 / span 1' },
        { index: 3, kind: 'calendar', column: '4 / span 1', row: '2 / span 1' },
        { index: 4, kind: 'weather', column: '4 / span 1', row: '3 / span 2' },
      ]) && await noOverlap());
    check('short equal-width neighbors have only the 24 px gutter below them',
      await widgetPage.evaluate(() => {
        const grid = document.querySelector('.live-widget-grid');
        const first = grid.querySelector('[data-live-widget-index="2"]').getBoundingClientRect();
        const next = grid.querySelector('[data-live-widget-index="3"]').getBoundingClientRect();
        const scale = grid.getBoundingClientRect().width / grid.clientWidth;
        return Math.abs(next.y - first.bottom - 24 * scale) < 0.1 &&
          Math.abs(first.width - next.width) < 0.1 && Math.abs(first.x - next.x) < 0.1;
      }));
    check('editing overlays remain inside card bounds without increasing card heights',
      await widgetPage.evaluate(() => [...document.querySelectorAll('.live-widget-slot')].every(slot => {
        const box = slot.getBoundingClientRect();
        const card = slot.querySelector('.home-card').getBoundingClientRect();
        const controls = slot.querySelector('.live-widget-edit-controls').getBoundingClientRect();
        return Math.abs(box.height - card.height) < 0.1 && Math.abs(box.width - card.width) < 0.1 &&
          controls.left >= box.left && controls.right <= box.right &&
          controls.top >= box.top && controls.bottom <= box.bottom;
      })));
    const sizedEdit = await layout();
    await done();
    assert.deepEqual(await layout(), sizedEdit, 'resized Home and editor card geometry');
    check('resized Home and editor have identical card positions', true);
    await nextWidgets();
    check('custom sizes on page one do not reshape the untouched second page',
      JSON.stringify(await layout()) === JSON.stringify(secondPageDefault));
    await customize();
    await previousWidgets();
    await widgetClick('[data-live-widget-index="3"] [data-action="widget-move"]');
    check('opening the existing Move menu does not commit any reorder',
      await widgetPage.evaluate(() => JSON.stringify(__portalMock.getState().widgets) ===
        JSON.stringify(['photo_memory', 'today', 'ha', 'calendar', 'weather', 'alarm', 'world_clock', 'crypto', 'quick_controls'])));
    await widgetClick('#live-overlays [data-action="widget-move-to"][data-to="7"]');
    check('Move commits the selected original index once through the frozen renderer',
      await widgetPage.evaluate(() => JSON.stringify(__portalMock.getState().widgets) ===
        JSON.stringify(['photo_memory', 'today', 'ha', 'weather', 'alarm', 'world_clock', 'crypto', 'calendar', 'quick_controls'])) &&
      await noOverlap());
    await nextWidgets();
    await widgetClick('[data-live-widget-index="7"] [data-action="widget-size"]');
    await widgetPage.keyboard.press('Escape');
    check('a moved widget retains its saved size on its new page',
      (await placements()).some(item => item.index === 7 && item.kind === 'calendar' && item.column.endsWith('span 1') && item.row.endsWith('span 1')));
    await widgetClick('[data-live-widget-index="7"] [data-action="widget-remove"]');
    check('Remove uses the existing confirmation for the selected widget',
      /Remove Calendar/.test(await widgetRoot.locator('#live-overlays .modal').innerText()));
    await widgetClick('#live-overlays [data-action="widget-remove-confirm"]');
    check('confirmed removal updates the selected page and removes only the intended widget',
      await widgetPage.evaluate(() => !__portalMock.getState().widgets.includes('calendar') &&
        __portalMock.getState().widgets.length === 8) &&
      (await layout()).page === '2 of 2' && await noOverlap());
    await widgetClick('[data-live-widget-index="5"] .live-widget-edit-controls [data-action="go"]');
    check('overlay Settings opens the existing widget destination',
      await widgetPage.evaluate(() => __portalNavigationDemo.getState().route.screen === 'world'));
    await widgetPage.keyboard.press('Escape');
    await widgetSettled();
    await widgetPage.keyboard.press('Escape');
    await widgetSettled();
    check('Settings Back through its category returns to the selected editing page',
      await widgetPage.evaluate(() => __portalNavigationDemo.getState().route.screen === 'edit') &&
      await widgetRoot.locator('.live-widget-toolbar').isVisible() && (await layout()).page === '2 of 2');

    // Extra photos are separate indexed items even though the frozen Home omits them.
    for (let count = 0; count < 2; count++) {
      await widgetClick('.live-widget-toolbar [data-screen="widgets"]');
      await widgetClick('[data-tab="home"] [data-action="widget-add"][data-kind="photo_memory"]');
    }
    await nextWidgets();
    check('additional photo widgets are reachable on a third page with distinct original indices',
      (await layout()).page === '3 of 3' &&
      JSON.stringify((await placements()).map(item => item.index)) === JSON.stringify([0, 9]));
    await size(9, '2 × 3');
    await widgetClick('[data-live-widget-index="9"] [data-action="widget-move"]');
    await widgetClick('#live-overlays [data-action="widget-move-to"][data-to="0"]');
    check('duplicate-photo Move keeps the resized identity instead of losing its size',
      (await placements()).some(item => item.index === 0 && item.kind === 'photo_memory' && item.row === '1 / span 3') &&
      await noOverlap());
    const reached = new Set();
    for (let count = 0; count < 3; count++) {
      (await placements()).forEach(item => reached.add(item.index));
      assert.ok((await placements()).length <= 5, 'editor remains bounded to one page');
      await nextWidgets();
    }
    check('editor paging reaches every widget including duplicate photos', reached.size === 10);
    await size(9, '2 × 1');
    check('an explicit photo 2 by 1 preset remains one row instead of becoming a hero default',
      (await placements()).some(item => item.index === 9 && item.row.endsWith('span 1') && item.column.endsWith('span 2')));
    await nextWidgets();
    await size(4, '2 × 2');
    check('an explicit regular 2 by 2 preset remains two columns instead of becoming a legacy default',
      (await placements()).some(item => item.index === 4 && item.row.endsWith('span 2') && item.column.endsWith('span 2')) &&
      await noOverlap());
    const finalEdit = await layout();
    await done();
    check('Done preserves the selected page and final edited geometry',
      JSON.stringify(await layout()) === JSON.stringify(finalEdit));
    await widgetPage.evaluate(() => { document.querySelector('.live-widget-grid').scrollTop = 100; });
    const scrolledHome = await layout();
    await customize();
    check('Customize preserves the selected overflow page scroll and geometry',
      await widgetPage.evaluate(() => document.querySelector('.live-widget-grid').scrollTop > 0) &&
      JSON.stringify(await layout()) === JSON.stringify(scrolledHome));
    await done();
    check('Done also preserves the actual overflowing grid scroll and geometry',
      JSON.stringify(await layout()) === JSON.stringify(scrolledHome));
    check('Customize, paging and editing preserve the mounted parent header and dock',
      await widgetPage.evaluate(() => widgetChrome.header === document.querySelector('.live-launcher > .app-header') &&
        widgetChrome.dock === document.querySelector('.live-launcher > .dock')));
    await widgetContext.close();

    const photoContext = await browser.newContext({ viewport: { width: 1440, height: 1020 } });
    await photoContext.route('**/*', route =>
      new URL(route.request().url()).origin === origin ? route.continue() : route.abort());
    const photoPage = await photoContext.newPage();
    watchPage(photoPage);
    await photoPage.goto(`${origin}/portal-plus-navigation-live/`);
    await photoPage.waitForFunction(() => !!window.__portalNavigationDemo);
    // The frozen evidence lists Photo last. Establish the approved native default
    // through the existing action, without rewriting fixtures or manufacturing keys.
    await photoPage.evaluate(() => __portalMock.showAction('widget-move-to', { from: '8', to: '0' }));
    await photoPage.waitForFunction(() =>
      !!document.querySelector('.live-widget-grid [data-live-widget-index="0"][data-live-widget-kind="photo_memory"]'));
    const photoRoot = photoPage.locator('#navigation-device');
    const photoClick = async selector => {
      await photoRoot.locator(selector).click();
      await photoPage.waitForFunction(() =>
        document.querySelector('#navigation-device').getAnimations({ subtree: true }).length === 0);
    };
    const photoLayout = () => photoPage.evaluate(() => ({
      page: document.querySelector('.widget-page > span:not(.live-widget-toolbar)').textContent,
      cards: [...document.querySelectorAll('.live-widget-grid > [data-live-widget-index]')].map(slot => {
        const box = slot.querySelector('.home-card').getBoundingClientRect();
        return { index: Number(slot.dataset.liveWidgetIndex), kind: slot.dataset.liveWidgetKind,
          box: ['x', 'y', 'width', 'height'].map(key => Math.round(box[key] * 1000) / 1000) };
      }),
    }));
    const photoMove = async (from, to) => {
      await photoClick(`[data-live-widget-index="${from}"] [data-action="widget-move"]`);
      await photoClick(`#live-overlays [data-action="widget-move-to"][data-to="${to}"]`);
    };
    const photoNext = () => photoClick('[data-action="page"][data-dir="1"]');
    const approvedPhotoHome = await photoLayout();
    check('saved photo-first default retains the approved dashboard order',
      JSON.stringify(approvedPhotoHome.cards.map(card => card.kind)) ===
      JSON.stringify(['photo_memory', 'today', 'ha', 'calendar', 'weather']) &&
      approvedPhotoHome.cards[0].box[0] < approvedPhotoHome.cards[1].box[0] &&
      approvedPhotoHome.cards[0].box[3] > approvedPhotoHome.cards[1].box[3]);
    await photoClick('.live-launcher > .app-header [data-screen="edit"]');
    await photoMove(0, 3);
    const movedPhotoEdit = await photoLayout();
    check('moving the first photo to a later slot changes rendered order to saved order',
      JSON.stringify(movedPhotoEdit.cards.map(card => card.index)) === JSON.stringify([0, 1, 2, 3, 4]) &&
      JSON.stringify(movedPhotoEdit.cards.map(card => card.kind)) ===
        JSON.stringify(['today', 'ha', 'calendar', 'photo_memory', 'weather']) &&
      await photoPage.evaluate(() => JSON.stringify(__portalMock.getState().widgets) === JSON.stringify(
        ['today', 'ha', 'calendar', 'photo_memory', 'weather', 'alarm', 'world_clock', 'crypto', 'quick_controls'])));
    const movedPhotoBox = movedPhotoEdit.cards.find(card => card.kind === 'photo_memory').box;
    check('photo Move changes actual visual bounds instead of only the saved array',
      movedPhotoBox[1] > approvedPhotoHome.cards[0].box[1] &&
      movedPhotoBox[2] === approvedPhotoHome.cards[0].box[2] &&
      movedPhotoBox[3] === approvedPhotoHome.cards[0].box[3]);
    await photoClick('.live-launcher > .app-header [data-live-widget-done]');
    check('Home after Done preserves the moved photo order and visual bounds',
      JSON.stringify(await photoLayout()) === JSON.stringify(movedPhotoEdit));
    await photoClick('.live-launcher > .app-header [data-screen="edit"]');
    check('Customize preserves Home order and bounds after photo Move',
      JSON.stringify(await photoLayout()) === JSON.stringify(movedPhotoEdit));
    await photoNext();
    const earlierSecondPage = await photoLayout();
    check('shared first photo appears once in the second saved-order subset',
      earlierSecondPage.page === '2 of 2' &&
      JSON.stringify(earlierSecondPage.cards.map(card => card.index)) === JSON.stringify([3, 5, 6, 7, 8]) &&
      earlierSecondPage.cards.filter(card => card.kind === 'photo_memory').length === 1);
    await photoMove(3, 7);
    const laterSecondPage = await photoLayout();
    check('moving the shared photo across pages also changes its second-page order and bounds',
      laterSecondPage.page === '2 of 2' &&
      JSON.stringify(laterSecondPage.cards.map(card => card.index)) === JSON.stringify([4, 5, 6, 7, 8]) &&
      JSON.stringify(laterSecondPage.cards.map(card => card.kind)) ===
        JSON.stringify(['alarm', 'world_clock', 'crypto', 'photo_memory', 'quick_controls']) &&
      laterSecondPage.cards.find(card => card.kind === 'photo_memory').box[1] >
        earlierSecondPage.cards.find(card => card.kind === 'photo_memory').box[1]);
    for (let count = 0; count < 2; count++) {
      await photoClick('.live-widget-toolbar [data-screen="widgets"]');
      await photoClick('[data-tab="home"] [data-action="widget-add"][data-kind="photo_memory"]');
    }
    const photoReached = new Set();
    for (let count = 0; count < 3; count++) {
      const snapshot = await photoLayout();
      const ids = snapshot.cards.map(card => card.index);
      assert.deepEqual(ids, [...ids].sort((a, b) => a - b), 'each page follows current saved indices');
      assert.equal(new Set(ids).size, ids.length, 'every visible widget ID appears once');
      assert.equal(ids.filter(index => index === 7).length, 1, 'shared first photo appears once per page');
      snapshot.cards.forEach(card => photoReached.add(card.index));
      await photoNext();
    }
    check('photo reordering preserves duplicate-photo reachability without duplicate visible IDs',
      photoReached.size === 11 && photoReached.has(9) && photoReached.has(10));
    await photoContext.close();

    // New parity regressions are isolated from all 101 existing checks above.
    const parityContext = await browser.newContext({ viewport: { width: 1440, height: 1020 } });
    await parityContext.route('**/*', route =>
      new URL(route.request().url()).origin === origin ? route.continue() : route.abort());
    const parityPage = await parityContext.newPage();
    watchPage(parityPage);
    await parityPage.goto(`${origin}/portal-plus-navigation-live/`);
    await parityPage.waitForFunction(() => !!window.__portalNavigationDemo);
    await parityPage.emulateMedia({ reducedMotion: 'reduce' });
    const parityRoot = parityPage.locator('#navigation-device');
    const paritySettled = () => parityPage.waitForFunction(() =>
      document.querySelector('#navigation-device').getAnimations({ subtree: true }).length === 0);
    const parityClick = async selector => { await parityRoot.locator(selector).click(); await paritySettled(); };
    const parityState = () => parityPage.evaluate(() => __portalNavigationDemo.getState());
    const toolsBody = () => parityRoot.locator('.live-tools-stage > :not([hidden])');
    await parityPage.evaluate(() => {
      const favorite = document.querySelector('[data-tab="home"] .favorite-grid .app-icon');
      window.favoriteIconBefore = {
        background: getComputedStyle(favorite).backgroundColor,
        radius: getComputedStyle(favorite).borderRadius,
      };
      // Deliberate artwork fixture: this is not an extracted Android app icon.
      __portalMock.model.installed[0].artwork = 'data:image/svg+xml,' + encodeURIComponent(
        '<svg xmlns="http://www.w3.org/2000/svg" width="80" height="60" viewBox="0 0 80 60"><circle cx="40" cy="30" r="27" fill="#80c5ed"/><path d="M20 30h40M40 8v44" stroke="#183b52" stroke-width="4"/></svg>');
      const stockSearch = __portalMock.model.installed.find(app => app.packageName === 'com.android.quicksearchbox');
      if (!stockSearch) throw new Error('Captured stock Google Search fixture is required for this regression');
      // Bypass the frozen renderer's older exact-label exclusion to test package filtering.
      stockSearch.name = 'Google Search fixture';
      __portalMock.model.installed.push({ ...stockSearch,
        packageName: 'com.google.android.googlequicksearchbox', name: 'Google app fixture' });
    });
    await parityClick('.live-launcher > .dock [data-screen="apps"]');
    const hiddenAppsSelector = '[data-pkg="com.android.quicksearchbox"],[data-pkg="com.google.android.googlequicksearchbox"]';
    check('Apps filters both captured Google Search package identities even with different labels',
      await parityPage.locator(`#device .app-grid :is(${hiddenAppsSelector})`).count() === 4 &&
      await parityRoot.locator(`[data-tab="apps"] :is(${hiddenAppsSelector})`).count() === 0 &&
      !/Google Search fixture|Google app fixture/.test(await parityRoot.locator('[data-tab="apps"]').innerText()));
    check('every projected Apps tile has a transparent background without a card border or shadow',
      await parityPage.evaluate(() => [...document.querySelectorAll('[data-live-apps-grid] .app-tile')].every(tile => {
        const style = getComputedStyle(tile);
        return style.backgroundColor === 'rgba(0, 0, 0, 0)' && style.backgroundImage === 'none' &&
          style.borderTopWidth === '0px' && style.boxShadow === 'none';
      })));
    check('every projected Apps icon container is transparent without an artificial square',
      await parityPage.evaluate(() => [...document.querySelectorAll('[data-live-apps-grid] .app-icon')].every(icon => {
        const style = getComputedStyle(icon);
        return style.backgroundColor === 'rgba(0, 0, 0, 0)' && style.backgroundImage === 'none' &&
          style.borderTopWidth === '0px' && style.boxShadow === 'none' && style.borderRadius === '0px';
      })));
    const fixtureArtwork = parityRoot.locator('[data-live-apps-grid] [data-pkg="org.chromium.chrome"] img');
    await fixtureArtwork.evaluate(image => image.decode());
    check('supplied installed artwork keeps its pixels in a transparent uncropped image view',
      await fixtureArtwork.evaluate(image => {
        const style = getComputedStyle(image);
        return image.naturalWidth === 80 && image.naturalHeight === 60 && style.objectFit === 'contain' &&
          style.backgroundColor === 'rgba(0, 0, 0, 0)' && style.borderRadius === '0px' &&
          style.boxShadow === 'none' && style.borderTopWidth === '0px' &&
          style.filter === 'none' && style.opacity === '1' &&
          getComputedStyle(image.closest('.app-tile')).filter === 'none' &&
          image.parentElement.dataset.liveArtwork === 'fixture';
      }));
    check('builtin and missing-art Apps glyphs match the legible launcher text palette without background plates',
      await parityPage.evaluate(() => {
        const textColor = getComputedStyle(document.querySelector('[data-live-apps-grid]')).color;
        return textColor === 'rgb(244, 247, 249)' &&
          [...document.querySelectorAll('[data-live-apps-grid] .app-icon[data-live-artwork="glyph"]')]
          .every(icon => icon.dataset.liveArtwork === 'glyph' && !icon.querySelector('img') && !!icon.querySelector('svg') &&
            getComputedStyle(icon).color === textColor && getComputedStyle(icon.querySelector('svg')).color === textColor &&
            getComputedStyle(icon).backgroundColor === 'rgba(0, 0, 0, 0)');
      }));
    check('Apps explicitly labels the installed inventory as sample fixtures',
      /Sample installed-app fixtures/.test(await parityRoot.locator('[data-tab="apps"]').innerText()));
    await fixtureArtwork.dispatchEvent('error');
    check('unavailable artwork returns to a transparent glyph fallback',
      await parityPage.evaluate(() => {
        const icon = document.querySelector('[data-live-apps-grid] [data-pkg="org.chromium.chrome"] .app-icon');
        return icon.dataset.liveArtwork === 'glyph' && !!icon.querySelector('svg') && !icon.querySelector('img') &&
          getComputedStyle(icon).color === 'rgb(244, 247, 249)' &&
          getComputedStyle(icon).backgroundColor === 'rgba(0, 0, 0, 0)';
      }));
    await parityPage.keyboard.press('Tab');
    await parityRoot.locator('[data-live-apps-grid] [data-pkg="org.chromium.chrome"]').focus();
    check('transparent Apps tiles retain a visible keyboard focus outline',
      await parityRoot.locator('[data-live-apps-grid] [data-pkg="org.chromium.chrome"]').evaluate(tile =>
        getComputedStyle(tile).outlineStyle !== 'none' && getComputedStyle(tile).outlineWidth === '3px'));
    await parityClick('[data-tab="apps"] .primary[data-screen="store"]');
    check('Store icons keep their own background and shape outside the Apps projection',
      await parityRoot.locator('[data-tab="apps"] .store-card .app-icon').first().evaluate(icon =>
        getComputedStyle(icon).backgroundColor !== 'rgba(0, 0, 0, 0)' && getComputedStyle(icon).borderRadius !== '0px'));
    await parityClick('[data-tab="apps"] [data-live-action="back"]');
    await parityClick('[data-tab="apps"] [data-screen="app-folder"]');
    check('Settings folder removes the captured Google Search shortcut while keeping Portal Settings',
      await parityPage.locator('#device .app-grid [data-pkg="com.android.quicksearchbox"]').count() === 1 &&
      await parityRoot.locator(`[data-tab="apps"] :is(${hiddenAppsSelector})`).count() === 0 &&
      await parityRoot.locator('[data-tab="apps"] [data-pkg="com.facebook.alohaapps.settings"]').isVisible());
    await parityClick('[data-tab="apps"] [data-action="folder-edit"]');
    check('folder contents picker cannot reintroduce either hidden Google Search package',
      await parityPage.locator(`#device .modal :is(${hiddenAppsSelector})`).count() === 2 &&
      await parityRoot.locator(`#live-overlays :is(${hiddenAppsSelector})`).count() === 0 &&
      await parityRoot.locator('#live-overlays [data-action="folder-toggle"][data-pkg="org.chromium.chrome"]').isVisible());
    await parityPage.keyboard.press('Escape');
    await paritySettled();
    await parityClick('[data-tab="apps"] [data-live-action="back"]');
    await parityClick('[data-tab="apps"] [data-action="favorites"]');
    check('favorite apps picker removes both hidden Google Search choices and keeps other apps',
      await parityPage.locator(`#device .modal :is(${hiddenAppsSelector})`).count() === 2 &&
      await parityRoot.locator(`#live-overlays :is(${hiddenAppsSelector})`).count() === 0 &&
      await parityRoot.locator('#live-overlays [data-action="favorite"][data-pkg="org.chromium.chrome"]').isVisible());
    await parityPage.keyboard.press('Escape');
    await paritySettled();
    await parityClick('.live-launcher > .app-header [data-action="search"]');
    await parityRoot.locator('#global-search').fill('Google');
    assert.equal(await parityPage.locator(`#device #search-results :is(${hiddenAppsSelector})`).count(), 2,
      'The original fixture search must expose both identities to make the projection check meaningful');
    assert.equal(await parityRoot.locator(`#live-overlays :is(${hiddenAppsSelector})`).count(), 0);
    await parityRoot.locator('#global-search').fill('Browser');
    check('internal Apps search still finds other apps after filtering Google Search results',
      await parityRoot.locator('#global-search').isVisible() &&
      await parityRoot.locator('#search-results [data-action="launch"][data-pkg="org.chromium.chrome"]').isVisible());
    await parityPage.keyboard.press('Escape');
    await paritySettled();
    await parityClick('.live-launcher > .dock [data-screen="home"]');
    check('Home favorite icon backgrounds and shapes are unchanged',
      await parityPage.evaluate(() => {
        const style = getComputedStyle(document.querySelector('[data-tab="home"] .favorite-grid .app-icon'));
        return style.backgroundColor === favoriteIconBefore.background && style.borderRadius === favoriteIconBefore.radius;
      }));

    // Include the gated Speed test in the complete Tools route coverage.
    await parityClick('.live-launcher > .app-header [data-screen="settings"]');
    await parityRoot.locator('.settings-sidebar #settings-search').fill('Brightness');
    check('internal Settings search keeps its field and matching setting results',
      await parityRoot.locator('.settings-sidebar #settings-search').isVisible() &&
      await parityRoot.locator('.settings-sidebar .settings-results [data-action="search-result"][data-id="display.brightness"]').isVisible());
    await parityRoot.locator('.settings-sidebar #settings-search').fill('');
    await parityClick('.settings-sidebar [data-advanced-toggle]');
    await parityClick('.live-settings > .app-header [data-screen="home"]');
    await parityClick('.live-launcher > .dock [data-screen="tools"]');
    await parityPage.evaluate(() => {
      const panel = document.querySelector('[data-tab="tools"]');
      const list = panel.querySelector('.screen-body');
      list.style.maxHeight = '500px'; // Ensure actual list overflow with the short fixture.
      list.scrollTop = 280;
      window.toolsAudit = {
        panel, workspace: panel.querySelector('.live-tools-workspace'),
        back: panel.querySelector('[data-live-action="back"]'), title: panel.querySelector('#live-tools-title'),
        list, scroll: list.scrollTop, scrolled: false,
      };
    });
    const openTool = async route => {
      const tile = parityRoot.locator(`[data-tab="tools"] .tool-card[data-screen="${route}"]`);
      await tile.scrollIntoViewIfNeeded();
      // Record the offset at activation, after the browser reveals the target tile.
      await parityPage.evaluate(() => {
        toolsAudit.scroll = toolsAudit.list.scrollTop;
        toolsAudit.scrolled ||= toolsAudit.scroll > 0;
      });
      await tile.click();
      await paritySettled();
    };
    const toolRoutes = await parityRoot.locator('[data-tab="tools"] .tool-card').evaluateAll(tiles =>
      tiles.map(tile => tile.dataset.screen));
    assert.deepEqual([...toolRoutes].sort(), ['alarm', 'aurora', 'camera', 'converter', 'countdowns', 'intercom',
      'iss', 'lamp', 'notes', 'speed', 'stories', 'timers'].sort());
    for (const route of toolRoutes) {
      await openTool(route);
      check(`${route} opens controls in the same Tools workspace with one Back/title row`,
        (await parityState()).host === 'launcher' && (await parityState()).tab === 'tools' &&
        (await parityState()).route.screen === route && !(await parityState()).simulation &&
        await parityPage.evaluate(() => {
          const panel = document.querySelector('[data-tab="tools"]');
          const workspaceStyle = getComputedStyle(toolsAudit.workspace);
          const titleStyle = getComputedStyle(toolsAudit.title);
          const panelStyle = getComputedStyle(panel);
          return toolsAudit.panel === panel && toolsAudit.workspace === panel.querySelector('.live-tools-workspace') &&
            toolsAudit.back === panel.querySelector('[data-live-action="back"]') &&
            toolsAudit.title === panel.querySelector('#live-tools-title') &&
            panel.querySelectorAll('[data-live-action="back"]').length === 1 &&
            panel.querySelectorAll('h1').length === 1 && toolsAudit.list.hidden &&
            panelStyle.paddingTop === '36px' && panelStyle.paddingBottom === '36px' &&
            panelStyle.paddingLeft === '56px' && panelStyle.paddingRight === '56px' &&
            workspaceStyle.flexDirection === 'column' && workspaceStyle.gap === '26px' &&
            getComputedStyle(toolsAudit.back).minHeight === '68px' &&
            titleStyle.fontSize === '44px' && titleStyle.lineHeight === '56px' &&
            titleStyle.letterSpacing === '-0.5px' && titleStyle.paddingBottom === '34px' &&
            !toolsAudit.workspace.querySelector('p') &&
            getComputedStyle(panel.querySelector('.live-tools-stage > :not([hidden])')).padding === '0px' &&
            !document.querySelector('.live-activity-window') && document.querySelector('.live-settings').hidden;
        }));
      await parityClick('[data-tab="tools"] [data-live-action="back"]');
      assert.equal((await parityState()).route.screen, 'tools');
      assert.ok(await parityPage.evaluate(() => toolsAudit.list === document.querySelector('[data-tab="tools"] .screen-body') &&
        toolsAudit.list.scrollTop === toolsAudit.scroll && !toolsAudit.list.hidden),
      `${route} Back must retain the list node and actual scroll offset`);
    }
    check('every tool returns to the mounted Tools list at the saved scroll offset',
      await parityPage.evaluate(() => toolsAudit.scrolled));
    await openTool('stories');
    const storyName = await toolsBody().locator('[data-action="story"]').first().getAttribute('data-name');
    await toolsBody().locator('[data-action="story"]').first().click();
    await paritySettled();
    check('Bedtime reader stays dark inside the mounted Tools workspace without a modal or Activity',
      (await parityState()).route.reader === storyName && (await parityState()).host === 'launcher' &&
      await parityPage.evaluate(() => {
        const reader = document.querySelector('.live-story-reader');
        return toolsAudit.workspace === document.querySelector('.live-tools-workspace') &&
          document.querySelector('#live-tools-title').textContent === __portalNavigationDemo.getState().route.reader &&
          reader && getComputedStyle(reader).color === 'rgb(244, 247, 249)' &&
          getComputedStyle(reader).backgroundColor !== 'rgba(0, 0, 0, 0)' &&
          !document.querySelector('#live-overlays .modal-layer,.live-activity-window') &&
          /speech playback is simulated/.test(reader.textContent);
      }));
    await parityPage.evaluate(() => {
      const reader = document.querySelector('.live-story-reader');
      window.storyHeaderY = document.querySelector('.live-tools-workspace').getBoundingClientRect().y;
      reader.style.maxHeight = '200px';
      reader.scrollTop = 140;
      window.storyScroll = reader.scrollTop;
    });
    check('Tools Back/title stay fixed while the story body actually scrolls',
      await parityPage.evaluate(() => storyScroll > 0 &&
        document.querySelector('.live-tools-workspace').getBoundingClientRect().y === storyHeaderY));
    await parityClick('[data-tab="tools"] [data-action="story-stop"]');
    check('story speech action stays explicitly simulated in the reader',
      (await parityState()).route.reader === storyName &&
      await toolsBody().locator('[data-action="story-stop"]').textContent() === 'Read aloud' &&
      await parityRoot.locator('#live-overlays .modal-layer').count() === 0);
    await parityPage.evaluate(() => {
      const reader = document.querySelector('.live-story-reader');
      reader.style.maxHeight = '200px';
      reader.scrollTop = 140;
      window.storyScroll = reader.scrollTop;
    });
    await parityClick('.live-launcher > .dock [data-screen="apps"]');
    await parityClick('.live-launcher > .dock [data-screen="tools"]');
    check('tab return preserves the selected story reader and body scroll',
      (await parityState()).route.reader === storyName && await parityPage.evaluate(() =>
        document.querySelector('.live-story-reader').scrollTop === storyScroll &&
        toolsAudit.workspace === document.querySelector('.live-tools-workspace')));
    await parityPage.keyboard.press('Escape');
    await paritySettled();
    check('Bedtime Back returns from reader to the in-workspace story picker',
      (await parityState()).route.screen === 'stories' && !(await parityState()).route.reader &&
      await toolsBody().locator('[data-action="story"]').count() > 0 &&
      await parityRoot.locator('#live-overlays .modal-layer').count() === 0);
    await parityClick('[data-tab="tools"] [data-live-action="back"]');
    check('Bedtime picker Back returns to Tools with the saved list scroll',
      (await parityState()).route.screen === 'tools' && await parityPage.evaluate(() =>
        toolsAudit.list.scrollTop === toolsAudit.scroll && !toolsAudit.list.hidden));
    await openTool('camera');
    await toolsBody().locator('[data-value="camera.name"]').fill('Sample front door');
    await toolsBody().locator('[data-value="camera.url"]').fill('rtsp://sample.invalid/front-door');
    await parityClick('[data-tab="tools"] .live-tools-stage button[type="submit"]');
    check('Cameras fixture form adds a named sample in the Tools pane',
      (await parityState()).route.screen === 'camera' && await toolsBody().locator('[data-action="camera-view"]').count() === 1);
    await parityClick('[data-tab="tools"] [data-action="camera-view"]');
    check('camera View labels a fullscreen simulation without changing the controls route',
      (await parityState()).route.screen === 'camera' && (await parityState()).host === 'launcher' &&
      await parityRoot.locator('.live-handoff-label').textContent() === 'Fullscreen handoff simulation');
    await parityPage.keyboard.press('Escape');
    await paritySettled();
    check('camera simulation returns to its in-tab camera controls',
      (await parityState()).route.screen === 'camera' && !(await parityState()).simulation &&
      await toolsBody().locator('[data-action="camera-view"]').isVisible());
    await parityClick('[data-tab="tools"] [data-live-action="back"]');
    await openTool('lamp');
    await toolsBody().locator('[data-value="lamp.warmth"]').evaluate(input => {
      input.value = '37';
      input.dispatchEvent(new Event('input', { bubbles: true }));
    });
    check('Lamp controls update only the sample fixture and stay in the Tools workspace',
      (await parityState()).route.screen === 'lamp' && !(await parityState()).simulation &&
      await parityPage.evaluate(() => __portalMock.getState().values['lamp.warmth'] === 37));
    await parityPage.evaluate(() => {
      window.lampContentBefore = document.querySelector('.live-tools-stage > :not([hidden])');
    });
    await parityClick('[data-tab="tools"] [data-screen="lamp-preview"]');
    check('Lamp Preview alone opens the labelled fullscreen display simulation',
      (await parityState()).route.screen === 'lamp' && (await parityState()).simulation &&
      await parityRoot.locator('.live-handoff-label').textContent() === 'Fullscreen handoff simulation');
    await parityClick('#live-overlays [data-live-action="close-simulation"]');
    check('Lamp display return preserves the mounted controls and edited fixture value',
      await parityPage.evaluate(() => lampContentBefore === document.querySelector('.live-tools-stage > :not([hidden])') &&
        document.querySelector('[data-tab="tools"] [data-value="lamp.warmth"]').value === '37') &&
      !(await parityState()).simulation);
    await parityClick('.live-launcher > .dock [data-screen="apps"]');
    await parityClick('.live-launcher > .dock [data-screen="tools"]');
    check('tab switching retains the tool route, workspace and current controls',
      (await parityState()).route.screen === 'lamp' && await parityPage.evaluate(() =>
        toolsAudit.workspace === document.querySelector('.live-tools-workspace') &&
        lampContentBefore === document.querySelector('.live-tools-stage > :not([hidden])')));
    await parityPage.keyboard.press('Escape');
    await paritySettled();
    check('Escape from a retained tool restores the list and its saved scroll',
      (await parityState()).route.screen === 'tools' && await parityPage.evaluate(() =>
        toolsAudit.list === document.querySelector('[data-tab="tools"] .screen-body') &&
        toolsAudit.list.scrollTop === toolsAudit.scroll && !toolsAudit.list.hidden));
    await parityContext.close();
    assert.deepEqual(errors, [], 'browser errors');
  } finally {
    console.log(JSON.stringify({ passed: checks.length, checks, browserErrors: errors }, null, 2));
    await browser.close();
  }
})().catch(error => { console.error(error.stack); process.exitCode = 1; });
