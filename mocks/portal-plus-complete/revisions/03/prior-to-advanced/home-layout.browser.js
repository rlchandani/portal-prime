/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */
async (page) => {
  const base = 'http://127.0.0.1:8765/portal-plus-complete/';
  const results = [], errors = [], measurements = [];
  const onError = error => errors.push(String(error));
  page.on('pageerror', onError);
  await page.setViewportSize({ width: 2160, height: 1440 });
  const cases = [];
  for (const theme of ['light', 'dark']) {
    for (const scenario of ['normal', 'expanded', 'empty', 'offline', 'missing-permission', 'playing']) {
      for (const widgetPage of [1, 2]) cases.push({ theme, scenario, widgetPage });
    }
    for (const size of ['1 × 1', '1 × 2', '1 × 3', '1 × 4', '2 × 1', '2 × 2', '2 × 3', '2 × 4']) {
      for (const widgetPage of [1, 2]) cases.push({ theme, scenario: 'offline', widgetPage, size });
    }
    cases.push({ theme, scenario: 'normal', widgetPage: 1, noFavorites: true });
    cases.push({ theme, scenario: 'normal', widgetPage: 1, noPhoto: true });
  }
  for (const entry of cases) {
    await page.goto(base + 'native.html?screen=home&theme=' + entry.theme + '&scenario=' + entry.scenario);
    await page.evaluate(() => document.fonts.ready);
    const measured = await page.evaluate(entry => {
      const a = __portalMock;
      if (entry.size) a.showAction('widget-size-set', { index: 0, name: entry.size });
      if (entry.noPhoto) a.showAction('widget-remove', { index: a.getState().widgets.indexOf('photo_memory') });
      if (entry.noFavorites) {
        for (const app of a.model.installed.slice(0, 6)) a.showAction('favorite', { pkg: app.packageName });
        a.showAction('close');
      }
      if (entry.widgetPage === 2) a.showAction('page', { dir: 1 });
      const rect = selector => document.querySelector(selector).getBoundingClientRect();
      const device = rect('#device'), dock = rect('.dock'), favorites = rect('.favorite-section');
      const grid = rect('.home-grid'), header = rect('.app-header'), greeting = rect('.greeting');
      const scale = device.width / 1920;
      const tiles = [...document.querySelectorAll('.favorite-grid .app-tile')];
      return {
        gapDp: (dock.top - favorites.bottom) / scale,
        tileHeightDp: tiles[0]?.getBoundingClientRect().height / scale || null,
        checks: {
          'favorites clear dock by at least 24 dp': dock.top - favorites.bottom >= 24 * scale - 1,
          'widgets clear favorites': grid.bottom <= favorites.top + 1,
          'dock stays inside device': dock.bottom <= device.bottom && dock.top >= device.top,
          'greeting clears header': greeting.top >= header.bottom - 1,
          'favorite touch targets remain at least 48 dp': tiles.every(tile => {
            const r = tile.getBoundingClientRect(); return r.height >= 48 * scale && r.width >= 48 * scale;
          }),
          'all favorite labels fit': tiles.every(tile => {
            const label = tile.querySelector('strong'); return label.scrollWidth <= label.clientWidth + 1;
          })
        }
      };
    }, entry);
    const name = [entry.theme, entry.scenario, 'page ' + entry.widgetPage, entry.size,
      entry.noFavorites ? 'no favorites' : '', entry.noPhoto ? 'no photo' : ''].filter(Boolean).join(' / ');
    measurements.push({ name, gapDp: measured.gapDp, tileHeightDp: measured.tileHeightDp });
    for (const [check, ok] of Object.entries(measured.checks)) results.push({ name: name + ': ' + check, ok });
  }
  page.off('pageerror', onError);
  const report = {
    cases: cases.length, total: results.length,
    passed: results.filter(result => result.ok).length,
    failed: results.filter(result => !result.ok), errors, measurements
  };
  await page.evaluate(report => { window.__portalHomeLayoutReport = report; }, report);
  return { cases: report.cases, total: report.total, passed: report.passed, failed: report.failed, errors };
}
