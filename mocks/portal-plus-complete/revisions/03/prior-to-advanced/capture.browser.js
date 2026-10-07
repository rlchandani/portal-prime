/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */
async (page) => {
  const base = 'http://127.0.0.1:8765/portal-plus-complete/';
  const plan = await (await page.request.get(base + 'reference-plan.json')).json();
  const previous = await page.evaluate(() => window.__portalCaptureProgress || { next: 0, results: [] });
  const results = previous.results;
  const limit = Math.min(previous.next + 60, plan.length);
  await page.setViewportSize({ width: 2160, height: 1440 });
  for (let i = previous.next; i < limit; i++) {
    const entry = plan[i];
    await page.goto(base + 'native.html?screen=' + encodeURIComponent(entry.screen) +
      '&category=' + (entry.category || 'appearance') + '&theme=' + entry.theme + '&scenario=' + entry.scenario);
    await page.evaluate(() => document.fonts.ready);
    await page.evaluate(entry => {
      const a = __portalMock;
      if (entry.values?.['digitalclock.face']) {
        a.showAction('choose', { id: 'digitalclock.face', value: entry.values['digitalclock.face'] });
      }
      if (entry.values||entry.source) a.configure({ values: entry.values||{},source:entry.source });
      a.navigate(entry.screen, entry.category);if(entry.connectionResult)a.configure({connectionResult:entry.connectionResult});
      for (const step of entry.steps || []) a.showAction(step.action, step.data || {});
      if (entry.control) {
        a.revealControl(entry.control);
        a.showControl(entry.control);
      } else if (entry.action) a.showAction(entry.action, entry.data || {});
    }, entry);
    await page.waitForTimeout(entry.afterMs || (entry.kind === 'Screensaver style' ? 1200 : 180));
    const state = await page.evaluate(() => ({
      screen: __portalMock.getState().screen,
      dialog: document.querySelector('[role="dialog"]')?.querySelector('h2')?.textContent || null,
      missing: document.body.innerText.includes('Unable to open this destination'),
      images: [...document.images].every(i => i.complete && i.naturalWidth > 0)
    }));
    if (state.missing || !state.images) throw Error('Invalid reference ' + entry.id);
    await page.screenshot({
      path: '~/portal-prime/mocks/portal-plus-complete/' + entry.file,
      animations: 'disabled', scale: 'css'
    });
    results.push({ id: entry.id, ...state });
  }
  const progress = { next: limit, total: plan.length, results };
  await page.evaluate(progress => { window.__portalCaptureProgress = progress; }, progress);
  return { completed: limit, total: plan.length, last: plan[limit - 1]?.id };
}
