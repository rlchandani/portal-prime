/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */
'use strict';
const assert = require('node:assert/strict');
const { chromium } = require(process.env.PLAYWRIGHT_MODULE || 'playwright');

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
    const origin = process.env.DEMO_ORIGIN || 'http://127.0.0.1:8765';
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
    check('native fullscreen tool boundary is labelled as simulation', await root.locator('.live-handoff-label').textContent() === 'Fullscreen handoff simulation');
    await page.keyboard.press('Escape');
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
    for (const route of ['music', 'camera', 'lamp', 'alarm-ring']) {
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
    assert.deepEqual(errors, [], 'browser errors');
  } finally {
    console.log(JSON.stringify({ passed: checks.length, checks, browserErrors: errors }, null, 2));
    await browser.close();
  }
})().catch(error => { console.error(error.stack); process.exitCode = 1; });
