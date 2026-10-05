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
  try {
    const context = await browser.newContext({ viewport: { width: 1440, height: 1020 } });
    const origin = process.env.DEMO_ORIGIN || 'http://127.0.0.1:8765';
    await context.route('**/*', route =>
      new URL(route.request().url()).origin === origin ? route.continue() : route.abort());
    const page = await context.newPage();
    page.on('pageerror', error => errors.push(error.message));
    page.on('response', response => { if (response.status() >= 400) errors.push(`${response.status()} ${response.url()}`); });
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
    assert.deepEqual(errors, [], 'browser errors');
    console.log(JSON.stringify({ passed: checks.length, checks, browserErrors: errors }, null, 2));
  } finally {
    await browser.close();
  }
})().catch(error => { console.error(error.stack); process.exitCode = 1; });
