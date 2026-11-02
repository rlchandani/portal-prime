/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */
import test from 'node:test';
import assert from 'node:assert/strict';
import { browserHarness } from './remote-browser-harness.mjs';

const master = 'Show the photo-frame screensaver';
const everydayTitles = [master, 'Screensaver style', 'Photo interval', 'Shuffle'];
const advancedTitle = 'Store media on this device';
const page = h => h.document.getElementById('page');
const field = (h, title) => page(h).querySelectorAll('input,select')
  .find(node => node.getAttribute('aria-label') === title);
const settingRows = h => page(h).querySelectorAll('.setting-row')
  .filter(node => node.querySelector('strong').textContent !== 'Category');
const rowTitles = h => settingRows(h)
  .map(node => node.querySelector('strong').textContent);
const advancedToggle = h => page(h).querySelectorAll('label')
  .find(node => node.textContent === 'Show advanced settings').querySelector('input');
const posts = h => h.calls.filter(call => call.path === '/remote/settings' && call.opts.method === 'POST');

async function fixture() {
  // Deliberately synthetic server schema. This exercises the shipped PWA consumer,
  // not SettingsDomains.kt predicates, persistence, or a live Portal HTTP route.
  const state = { enabled: true, mode: 'smart', intervalSec: 75, shuffle: true, cacheEnabled: false };
  const reads = [], replies = [];
  const domain = (includeInactive = false) => ({
    id: 'screensaver',
    title: 'Screensaver',
    controls: [
      { key: 'enabled', title: master, type: 'bool', value: state.enabled, default: true, tier: 'everyday' },
      ...(state.enabled || includeInactive ? [
        { key: 'mode', title: 'Screensaver style', type: 'enum', value: state.mode, default: 'smart',
          tier: 'everyday', options: [
            { value: 'smart', label: 'Smart frame', tier: 'everyday' },
            { value: 'classic', label: 'Classic', tier: 'advanced' },
          ] },
        { key: 'intervalSec', title: 'Photo interval', type: 'int', value: state.intervalSec,
          default: 45, min: 5, max: 600, step: 5, asText: true, tier: 'everyday' },
        { key: 'shuffle', title: 'Shuffle', type: 'bool', value: state.shuffle, default: false, tier: 'everyday' },
        { key: 'cacheEnabled', title: advancedTitle, type: 'bool', value: state.cacheEnabled,
          default: true, tier: 'advanced' },
      ] : []),
    ],
  });
  const h = await browserHarness({
    saved: [{ base: 'http://127.0.0.1:8877', name: 'Fixture', token: 'fixture-only' }],
    routes: { '/remote/settings': ({ url, opts, response }) => {
      if (opts.method !== 'POST') {
        const query = new URL(url).searchParams;
        const schema = domain(query.get('includeInactive') === '1');
        reads.push({ domain: query.get('domain'), inactive: query.get('includeInactive'), schema });
        return response({ ok: true, settings: { domains: [schema] } });
      }
      const body = JSON.parse(opts.body);
      assert.equal(body.domain, 'screensaver');
      assert.equal(typeof body.values.enabled, 'boolean');
      Object.assign(state, body.values);
      const readback = domain();
      replies.push(readback);
      return response({ ok: true, applied: Object.keys(body.values), domain: readback });
    } },
  });
  h.eval('go("settings")');
  await h.settle();
  return { h, state, reads, replies };
}

for (const showAdvanced of [false, true]) {
  test(`master off readback prunes children and on restores saved values with Advanced ${showAdvanced ? 'on' : 'off'}`, async () => {
    const { h, state, reads, replies } = await fixture();
    try {
      if (showAdvanced) {
        advancedToggle(h).click();
        await h.settle();
      }
      const expected = showAdvanced ? [...everydayTitles, advancedTitle] : everydayTitles;
      assert.deepEqual(rowTitles(h), expected);
      assert.equal(field(h, 'Photo interval').value, '75');
      assert.equal(field(h, 'Shuffle').checked, true);
      assert.equal(field(h, 'Screensaver style').children.length, showAdvanced ? 2 : 1);
      const oldChildren = settingRows(h).slice(1);
      const oldShuffle = field(h, 'Shuffle');
      const savedChildren = { mode: state.mode, intervalSec: state.intervalSec,
        shuffle: state.shuffle, cacheEnabled: state.cacheEnabled };

      field(h, master).click();
      await h.settle();
      assert.deepEqual(posts(h).map(call => JSON.parse(call.opts.body).values), [{ enabled: false }]);
      assert.deepEqual(replies[0].controls.map(control => control.key), ['enabled']);
      assert.deepEqual(rowTitles(h), [master]);
      assert.equal(field(h, master).checked, false);
      assert.equal(field(h, 'Photo interval'), undefined);
      assert.equal(field(h, 'Shuffle'), undefined);
      assert.equal(field(h, advancedTitle), undefined);
      assert.equal(oldChildren.every(node => !node.isConnected), true);
      // Retained detached handlers must not submit a child after its master hides it.
      oldShuffle.click();
      await h.settle();
      assert.equal(posts(h).length, 1);
      for (let i = 0; i < 2; i++) {
        advancedToggle(h).click();
        await h.settle();
        assert.deepEqual(rowTitles(h), [master], 'Advanced cannot expose server-inactive children');
      }
      assert.equal(posts(h).length, 1);

      // Exercise an ordinary GET through the real refresh-button handler too.
      const readCount = reads.length;
      h.document.getElementById('refresh-button').click();
      await h.settle();
      assert.equal(reads.length > readCount, true);
      assert.deepEqual(reads.at(-1).schema.controls.map(control => control.key), ['enabled']);
      assert.deepEqual(rowTitles(h), [master]);

      field(h, master).click();
      await h.settle();
      assert.deepEqual(posts(h).map(call => JSON.parse(call.opts.body).values),
        [{ enabled: false }, { enabled: true }]);
      const preflights = reads.filter(read => read.inactive === '1');
      assert.equal(preflights.length, 2);
      assert.equal(preflights.every(read => read.domain === 'screensaver'), true);
      // The off-state write preflight deliberately includes inactive children;
      // they must become visible only when the successful POST readback restores them.
      assert.deepEqual(preflights[1].schema.controls.map(control => control.key),
        ['enabled', 'mode', 'intervalSec', 'shuffle', 'cacheEnabled']);
      assert.equal(preflights[1].schema.controls[0].value, false);
      assert.deepEqual(rowTitles(h), expected);
      assert.equal(field(h, master).checked, true);
      assert.equal(field(h, 'Screensaver style').value, savedChildren.mode);
      assert.equal(field(h, 'Photo interval').value, String(savedChildren.intervalSec));
      assert.equal(field(h, 'Shuffle').checked, savedChildren.shuffle);
      assert.equal(field(h, 'Shuffle') === oldShuffle, false);

      if (!showAdvanced) {
        advancedToggle(h).click();
        await h.settle();
      }
      assert.deepEqual(rowTitles(h), [...everydayTitles, advancedTitle]);
      assert.equal(field(h, advancedTitle).checked, savedChildren.cacheEnabled);
      assert.equal(field(h, 'Screensaver style').children.length, 2);
      advancedToggle(h).click();
      await h.settle();
      assert.deepEqual(rowTitles(h), everydayTitles);
      assert.equal(field(h, advancedTitle), undefined);
      assert.equal(field(h, 'Screensaver style').children.length, 1);
      assert.equal(field(h, 'Photo interval').value, '75');
      assert.equal(posts(h).length, 2, 'Advanced filtering must not write settings');
      assert.deepEqual(state, { enabled: true, ...savedChildren });
    } finally {
      h.hide();
    }
  });
}
