/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */
async (page) => {
  const base = 'http://127.0.0.1:8765/portal-plus-complete/';
  const results = [], errors = [];
  const onError = error => errors.push(String(error));
  page.on('pageerror', onError);
  const check = (name, ok, detail = '') => results.push({ name, ok: !!ok, detail });
  const run = async (name, test) => {
    try {
      await test();
    } catch (error) {
      const detail = name + ': ' + String(error);
      errors.push(detail);
      check(name + ' completes', false, detail);
    }
  };
  const load = async (theme, params = {}) => {
    const query = new URLSearchParams({ theme, scenario: 'normal', screen: 'settings', ...params });
    await page.goto(base + 'native.html?' + query);
    await page.evaluate(() => document.fonts.ready);
  };
  const state = () => page.evaluate(() => __portalMock.getState());
  const configure = patch => page.evaluate(p => __portalMock.configure(p), patch);
  const navigate = (screen, category) => page.evaluate(
    ({ screen, category }) => __portalMock.navigate(screen, category), { screen, category });
  const action = (name, data = {}) => page.evaluate(
    ({ name, data }) => __portalMock.showAction(name, data), { name, data });
  const row = id => page.locator('[data-control=' + JSON.stringify(id) + ']');
  const visible = async locator => await locator.count() > 0 && await locator.first().isVisible();
  const advancedCategory = () => page.locator('[data-action="category"][data-id="advanced"]');
  const enable = () => page.locator('[data-action="advanced-enable"]');
  // Compare every setting, including values whose rows are currently unavailable.
  const preferences = snapshot => JSON.stringify({
    values: Object.entries(snapshot.values).sort(([a], [b]) => a.localeCompare(b)),
    source: snapshot.source
  });
  const unchanged = async (name, before) => check(name, preferences(await state()) === preferences(before));
  const switchState = async (name, expected) => {
    check(name + ' API mode', (await state()).advanced === expected);
    const toggle = page.getByRole('switch', { name: 'Advanced settings', exact: true });
    check(name + ' accessible global switch', await visible(toggle));
    check(name + ' switch selector', await visible(page.locator('[data-advanced-toggle]')));
    if (await toggle.count()) check(name + ' checked input', await toggle.first().isChecked() === expected);
    check(name + ' Advanced category', expected
      ? await visible(advancedCategory()) : await advancedCategory().count() === 0);
  };
  const guard = async name => {
    check(name + ' explains Advanced settings', /advanced/i.test(await page.locator('#detail-body').innerText()));
    check(name + ' explicit enable button', await visible(enable()));
    check(name + ' hides destination controls', await page.locator('[data-control]').count() === 0);
    check(name + ' remains off', (await state()).advanced === false);
  };
  const normalDestination = async (name, destination) => {
    check(name + ' destination', (await state()).screen === destination.id);
    check(name + ' normal heading', await visible(page.getByRole('heading', {
      name: destination.title, exact: true
    })));
    check(name + ' guard removed', await enable().count() === 0);
    check(name + ' valid destination',
      !(await page.locator('body').innerText()).includes('Unable to open this destination'));
  };
  await page.setViewportSize({ width: 2160, height: 1440 });

  for (const theme of ['light', 'dark']) {
    let manifest;
    await run(theme + ' public tier contract', async () => {
      await load(theme);
      manifest = await page.evaluate(() => {
        if (typeof PORTAL_SETTINGS_TIERS === 'undefined') throw Error('PORTAL_SETTINGS_TIERS is missing');
        return {
          advancedPages: PORTAL_SETTINGS_TIERS.advancedPages,
          advancedControls: PORTAL_SETTINGS_TIERS.advancedControls,
          advancedCategories: PORTAL_SETTINGS_TIERS.advancedCategories,
          pages: __portalMock.model.pages.map(p => ({
            id: p.id, title: p.title, controls: p.controls, readOnly: !!p.readOnly
          })),
          controls: __portalMock.model.controls.map(c => ({ id: c.id, title: c.title, hidden: !!c.hidden })),
          categories: __portalMock.model.categories.map(c => c[0])
        };
      });
      for (const key of ['advancedPages', 'advancedControls', 'advancedCategories']) {
        if (!Array.isArray(manifest[key]) || !manifest[key].length) throw Error(key + ' must list tier members');
      }
      check(theme + ' Advanced category declared', manifest.advancedCategories.includes('advanced'));
    });
    if (!manifest || !Array.isArray(manifest.advancedPages) ||
        !Array.isArray(manifest.advancedControls) || !Array.isArray(manifest.advancedCategories)) continue;

    await run(theme + ' default and global switch', async () => {
      await load(theme);
      await switchState(theme + ' simple default', false);
      check(theme + ' requested theme', (await state()).theme === theme);
      // Non-default fixtures make accidental resetting or convenience edits observable.
      await configure({
        source: 'dav',
        values: {
          'display.brightness': 63, 'display.mediaVolume': 27,
          'fleet.name': 'Review Portal+', 'mqtt.port': 2883,
          'screensaver.intervalSec': 45, 'screensaver.cacheBudgetGb': 7
        }
      });
      const before = await state();
      await page.getByRole('switch', { name: 'Advanced settings', exact: true }).click();
      await switchState(theme + ' user enables', true);
      await unchanged(theme + ' user enable preserves all values and photo source', before);
      await action('advanced-toggle');
      await switchState(theme + ' action toggles once to off', false);
      await unchanged(theme + ' action disable preserves all values and photo source', before);
      await action('advanced-toggle');
      await switchState(theme + ' action toggles once to on', true);
      await unchanged(theme + ' action enable preserves all values and photo source', before);
      for (const category of manifest.categories) {
        await navigate('settings', category);
        await switchState(theme + ' on through category ' + category, true);
      }
      for (const screen of ['display', 'screensaver', 'device', 'home']) {
        await navigate(screen);
        check(theme + ' on through page ' + screen, (await state()).advanced === true);
      }
      await navigate('settings');
      await switchState(theme + ' on after page navigation', true);
      await unchanged(theme + ' navigation preserves preferences', before);
      await page.getByRole('switch', { name: 'Advanced settings', exact: true }).click();
      await switchState(theme + ' user disables', false);
      await unchanged(theme + ' user disable preserves preferences', before);
      for (const category of manifest.categories.filter(id => !manifest.advancedCategories.includes(id))) {
        await navigate('settings', category);
        await switchState(theme + ' off through category ' + category, false);
      }
      for (const [screen, ids] of [
        ['display', ['display.brightness', 'display.mediaVolume']],
        ['screensaver', ['screensaver.enabled']], ['device', ['fleet.name']]
      ]) {
        await navigate(screen);
        for (const id of ids) check(theme + ' basic setting remains available ' + id, await visible(row(id)));
        check(theme + ' basic navigation does not enable mode ' + screen, (await state()).advanced === false);
      }
      await unchanged(theme + ' simple mode preserves customized preferences', before);
    });

    for (const id of manifest.advancedPages) {
      await run(theme + ' advanced page ' + id, async () => {
        const destination = manifest.pages.find(p => p.id === id);
        if (!destination) throw Error('Unknown advanced page ' + id);
        await load(theme);
        const before = await state();
        await navigate(id);
        await guard(theme + ' direct page off ' + id);
        await unchanged(theme + ' direct page guard preserves preferences ' + id, before);
        await enable().click();
        check(theme + ' page confirmation enables ' + id, (await state()).advanced === true);
        await normalDestination(theme + ' confirmed page ' + id, destination);
        await unchanged(theme + ' page confirmation preserves preferences ' + id, before);
        await configure({ advanced: false });
        await guard(theme + ' current page hidden after disable ' + id);
        await unchanged(theme + ' current page disable preserves preferences ' + id, before);
        await configure({ advanced: true });
        await navigate(id);
        await normalDestination(theme + ' configured page on ' + id, destination);
        await unchanged(theme + ' configured page on preserves preferences ' + id, before);
      });
    }

    await run(theme + ' direct Advanced category', async () => {
      await load(theme);
      const before = await state();
      await navigate('settings', 'advanced');
      await guard(theme + ' category off');
      check(theme + ' guarded category absent from navigation', await advancedCategory().count() === 0);
      await unchanged(theme + ' category guard preserves preferences', before);
      await enable().click();
      const confirmed = await state();
      check(theme + ' category confirmation returns to requested category',
        confirmed.advanced === true && confirmed.screen === 'settings' && confirmed.category === 'advanced');
      await switchState(theme + ' category confirmed', true);
      check(theme + ' confirmed category has no guard', await enable().count() === 0);
      await unchanged(theme + ' category confirmation preserves preferences', before);
    });

    // Tier metadata supplies the inventory, not expected native gate behavior.
    // The explicit review harness establishes each control's legitimate parent fixture.
    for (const id of manifest.advancedControls) {
      await run(theme + ' advanced control ' + id, async () => {
        const control = manifest.controls.find(c => c.id === id);
        if (!control) throw Error('Unknown advanced control ' + id);
        await load(theme);
        await page.evaluate(id => __portalMock.revealControl(id), id);
        const revealed = await state();
        const destination = manifest.pages.find(p => p.id === revealed.screen);
        if (!destination) throw Error('Review harness did not open a settings page for ' + id);
        check(theme + ' explicit review enables Advanced ' + id, revealed.advanced === true);
        const nativeUnavailable = id === 'screensaver.batterySaver' || (control.hidden && !destination.readOnly);
        check(theme + ' review respects native availability ' + id,
          nativeUnavailable ? await row(id).count() === 0 : await visible(row(id)));
        const basicRows = await page.locator('[data-control]').evaluateAll(
          (rows, advanced) => rows.map(r => r.dataset.control).filter(id => !advanced.includes(id)),
          manifest.advancedControls);
        await action('advanced-toggle');
        check(theme + ' control toggle turns off ' + id, (await state()).advanced === false);
        check(theme + ' control hidden off ' + id, await row(id).count() === 0);
        if (!manifest.advancedPages.includes(destination.id)) {
          for (const basic of basicRows) check(theme + ' off keeps basic row ' + basic + ' beside ' + id,
            await visible(row(basic)));
        }
        await unchanged(theme + ' hiding control preserves all preferences ' + id, revealed);
        await action('advanced-toggle');
        check(theme + ' control toggle turns on ' + id, (await state()).advanced === true);
        check(theme + ' restoring control respects native availability ' + id,
          nativeUnavailable ? await row(id).count() === 0 : await visible(row(id)));
        await unchanged(theme + ' restoring control preserves all preferences ' + id, revealed);
      });
    }

    await run(theme + ' native parents remain required', async () => {
      await load(theme);
      await configure({ source: 'immich', values: {
        'screensaver.enabled': false, 'screensaver.mode': 'classic', 'screensaver.cacheEnabled': true
      } });
      await navigate('screensaver');
      const before = await state();
      for (const advanced of [false, true, false, true]) {
        await configure({ advanced });
        check(theme + ' screensaver parent remains available ' + advanced, await visible(row('screensaver.enabled')));
        for (const id of ['screensaver.intervalSec', 'screensaver.fit', 'screensaver.cacheEnabled']) {
          check(theme + ' disabled screensaver hides ' + id + ' advanced ' + advanced, await row(id).count() === 0);
        }
        await unchanged(theme + ' Advanced does not turn on screensaver ' + advanced, before);
      }
      await configure({ values: { 'screensaver.enabled': true } });
      check(theme + ' native parent permits timing in Advanced', await visible(row('screensaver.intervalSec')));
      check(theme + ' technical cache requires parent and mode', await visible(row('screensaver.cacheEnabled')));
      await configure({ advanced: false });
      check(theme + ' simple mode hides technical cache despite enabled parent', await row('screensaver.cacheEnabled').count() === 0);
      await configure({ advanced: true });
      for (const [screen, values, child] of [
        ['mqtt', { 'mqtt.enabled': false }, 'mqtt.port'],
        ['multiroom', { 'immortal.multiRoomEnabled': false }, 'immortal.maPort']
      ]) {
        await configure({ values });
        await navigate(screen);
        const disabled = await state();
        await action('advanced-toggle');
        await action('advanced-toggle');
        check(theme + ' technical parent still required ' + child, await row(child).count() === 0);
        await unchanged(theme + ' mode does not activate technical parent ' + child, disabled);
        await configure({ values: Object.fromEntries(Object.keys(values).map(id => [id, true])) });
        check(theme + ' explicit technical parent reveals ' + child, await visible(row(child)));
      }
    });

    // Search must be discoverable while off, with consent separate from native parents.
    const searchControls = manifest.controls.filter(c =>
      !c.hidden && manifest.pages.some(p => p.controls.includes(c.id)) &&
      (manifest.advancedControls.includes(c.id) ||
       manifest.pages.some(p => manifest.advancedPages.includes(p.id) && p.controls.includes(c.id))));
    for (const control of searchControls) {
      await run(theme + ' advanced search ' + control.id, async () => {
        const destination = manifest.pages.find(p => p.controls.includes(control.id));
        await load(theme);
        await page.locator('#settings-search').fill(control.id);
        const result = page.locator('[data-action="search-result"][data-id=' + JSON.stringify(control.id) + ']');
        check(theme + ' search discovers ' + control.id, await visible(result));
        if (await result.count()) check(theme + ' search labels Advanced ' + control.id,
          /\bAdvanced\b/.test(await result.first().innerText()));
        const before = await state();
        await action('search-result', { id: control.id, screen: destination.id });
        check(theme + ' search does not silently enable ' + control.id, (await state()).advanced === false);
        check(theme + ' search asks for Advanced ' + control.id,
          await visible(page.getByRole('dialog')) &&
          /advanced/i.test(await page.getByRole('dialog').innerText()) && await visible(enable()));
        check(theme + ' search does not expose control before consent ' + control.id, await row(control.id).count() === 0);
        await unchanged(theme + ' search guidance preserves preferences ' + control.id, before);
        await action('close');
        check(theme + ' dismissing search leaves mode off ' + control.id, (await state()).advanced === false);
        check(theme + ' dismissing search removes confirmation ' + control.id, await enable().count() === 0);
        await unchanged(theme + ' dismissing search preserves preferences ' + control.id, before);
        await action('search-result', { id: control.id, screen: destination.id });
        await enable().click();
        check(theme + ' search confirmation enables ' + control.id, (await state()).advanced === true);
        check(theme + ' search confirmation returns destination ' + control.id, (await state()).screen === destination.id);
        check(theme + ' search confirmation removes Advanced prompt ' + control.id, await enable().count() === 0);
        await unchanged(theme + ' search confirmation preserves native parents and source ' + control.id, before);
        // If its native parents were already satisfied, selection should target the row.
        if (await visible(row(control.id))) check(theme + ' available search highlights exact row ' + control.id,
          await row(control.id).first().evaluate(el => el.classList.contains('row-highlight')));
      });
    }

    await run(theme + ' search consent preserves screensaver parent gate', async () => {
      await load(theme);
      await configure({ source: 'immich', values: { 'screensaver.enabled': false } });
      const before = await state();
      await action('search-result', { id: 'screensaver.cacheEnabled', screen: 'screensaver' });
      check(theme + ' gated search requires explicit consent', await visible(enable()));
      await enable().click();
      check(theme + ' gated search returns screensaver in Advanced',
        (await state()).advanced === true && (await state()).screen === 'screensaver');
      check(theme + ' search enable cannot bypass screensaver parent', await row('screensaver.cacheEnabled').count() === 0);
      await unchanged(theme + ' gated search consent preserves preferences', before);
      check(theme + ' gated search explains remaining parent', await visible(page.getByRole('dialog')) &&
        /screensaver|parent/i.test(await page.getByRole('dialog').innerText()));
    });

    await run(theme + ' basic search preserves native gate', async () => {
      await load(theme);
      await configure({ values: { 'display.nightShift': false } });
      const before = await state();
      await action('search-result', { id: 'display.warmth', screen: 'display' });
      check(theme + ' basic search stays simple', (await state()).advanced === false && await enable().count() === 0);
      check(theme + ' basic search explains Night Shift parent', await visible(page.getByRole('dialog')) &&
        /night shift/i.test(await page.getByRole('dialog').innerText()));
      check(theme + ' basic search does not expose gated child', await row('display.warmth').count() === 0);
      await unchanged(theme + ' basic search preserves preferences', before);
    });

    for (const destination of manifest.pages.filter(p => p.readOnly)) {
      await run(theme + ' native read-only page ' + destination.id, async () => {
        await load(theme);
        await configure({ advanced: true });
        await navigate(destination.id);
        const before = await state();
        const hidden = destination.controls.filter(id => manifest.controls.find(c => c.id === id)?.hidden);
        check(theme + ' managed page contains native hidden fields ' + destination.id, hidden.length > 0);
        for (const id of hidden) {
          check(theme + ' managed field displayed ' + id, await visible(row(id)));
          const editable = await row(id).evaluateAll(rows => rows.some(el =>
            el.matches('button,input,select,textarea,[role="switch"],[contenteditable="true"]') ||
            el.querySelector('button,input,select,textarea,[role="switch"],[contenteditable="true"]')));
          check(theme + ' native hidden field remains read-only ' + id, !editable);
        }
        await action('advanced-toggle');
        await action('advanced-toggle');
        await navigate(destination.id);
        for (const id of hidden) {
          check(theme + ' native field still read-only after toggling ' + id, await row(id).evaluateAll(rows =>
            rows.length > 0 && rows.every(el =>
              !el.matches('button,input,select,textarea,[role="switch"],[contenteditable="true"]') &&
              !el.querySelector('button,input,select,textarea,[role="switch"],[contenteditable="true"]'))));
        }
        await unchanged(theme + ' read-only review preserves preferences ' + destination.id, before);
      });
    }

    await run(theme + ' review harness and browser memory', async () => {
      await load(theme);
      await page.evaluate(() => __portalMock.revealControl('screensaver.cacheEnabled'));
      check(theme + ' explicit harness opts in', (await state()).advanced === true);
      check(theme + ' explicit harness prepares cache parents', await visible(row('screensaver.cacheEnabled')));
      await page.reload();
      await navigate('settings');
      await switchState(theme + ' reload clears memory-only opt-in', false);
      const baseline = await state();
      await configure({ advanced: true });
      await switchState(theme + ' configure opts in', true);
      await unchanged(theme + ' configure only changes mode', baseline);
      await page.reload();
      await switchState(theme + ' configured opt-in clears on reload', false);
      for (const params of [{ advanced: '1' }, { scenario: 'expanded' }]) {
        await load(theme, params);
        const label = params.advanced ? 'URL opt-in' : 'expanded capture';
        await switchState(theme + ' ' + label, true);
        const optedIn = await state();
        await configure({ advanced: false });
        await switchState(theme + ' explicit off within ' + label, false);
        await unchanged(theme + ' ' + label + ' disable preserves preferences', optedIn);
        await page.reload();
        await switchState(theme + ' reload reapplies ' + label, true);
      }
    });
  }

  page.off('pageerror', onError);
  check('no runtime or harness errors', errors.length === 0, errors.join('\n'));
  const report = {
    timestamp: new Date().toISOString(), viewport: { width: 2160, height: 1440 },
    total: results.length, passed: results.filter(result => result.ok).length,
    failed: results.filter(result => !result.ok), results, errors
  };
  await page.evaluate(report => { window.__portalAdvancedReport = report; }, report);
  return { total: report.total, passed: report.passed, failed: report.failed, errors };
}
