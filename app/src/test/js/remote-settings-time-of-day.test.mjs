/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */
import test from 'node:test';
import assert from 'node:assert/strict';
import * as core from '../../main/assets/remote/core.mjs';
import { browserHarness } from './remote-browser-harness.mjs';
const time = { key: 'overnightStartMin', title: 'Overnight start', type: 'int', timeOfDay: true,
  min: 0, max: 1439, step: 1, value: 1320, default: 1320 };
const find = h => h.document.getElementById('page').querySelectorAll('input')
  .find(n => n.getAttribute('aria-label') === time.title);
const posts = h => h.calls.filter(c => c.path === '/remote/settings' && c.opts.method === 'POST');
async function setup(extra = []) {
  const f = { control: { ...time }, hold: false, fail: false, jobs: [], omitDomain: false };
  const domain = peer => ({ id: 'screensaver', title: 'Screensaver', controls: [
    { ...f.control, ...(peer ? { value: 60 } : {}) }, ...extra] });
  const h = await browserHarness({ saved: [
    { base: 'http://127.0.0.1:8877', name: 'Fixture A', token: 'fixture-a' },
    { base: 'http://127.0.0.1:8878', name: 'Fixture B', token: 'fixture-b' }],
    routes: { '/remote/settings': ({ url, opts, response }) => {
      if (opts.method !== 'POST') return response({ ok: true, settings: { domains: [domain(url.includes('8878'))] } });
      const patch = JSON.parse(opts.body).values;
      const confirm = () => {
        if (f.fail) return response({ ok: false, error: 'request_failed' }, 500);
        f.control.value = patch.overnightStartMin;
        return response({ ok: true, applied: Object.keys(patch), ...(f.omitDomain ? {} : { domain: domain(false) }) });
      };
      return f.hold ? new Promise(resolve => f.jobs.push(() => resolve(confirm()))) : confirm();
    } } });
  h.eval('go("settings")'); await h.settle();
  return { h, f };
}
test('strict time conversion covers midnight and the last minute, rejects malformed drafts', () => {
  assert.equal(typeof core.timeOfDayMinutes, 'function');
  for (const [draft, minutes] of [['00:00', 0], ['23:59', 1439], ['09:05', 545]]) {
    assert.equal(core.timeOfDayMinutes(draft), minutes);
    assert.equal(core.timeOfDayValue(minutes), draft);
  }
  for (const draft of ['', '9:05', '24:00', '23:60', '12:00:00', ' 12:00', '12:00\n', 0])
    assert.equal(core.timeOfDayMinutes(draft), null);
  for (const value of [-1, 1440, 1.5, null, '60']) assert.equal(core.timeOfDayValue(value), '');
});
test('flagged times use native minute picker and explicit Apply while other controls stay unchanged', async () => {
  const { h } = await setup([{ key: 'count', title: 'Count', type: 'int', min: 0, max: 10, value: 2 },
    { key: 'name', title: 'Name', type: 'string', value: 'base' },
    { key: 'number', title: 'Number', type: 'int', asText: true, min: 0, max: 10, value: 3 }]);
  try {
    assert.equal(find(h)?.type, 'time'); assert.equal(find(h).value, '22:00');
    assert.equal(find(h).getAttribute('step'), '60');
    assert.equal(h.document.getElementById('page').querySelectorAll('.stepper').length, 1);
    for (const [label, type, value] of [['Name', 'text', 'base'], ['Number', 'number', '3']]) {
      const input = h.document.getElementById('page').querySelectorAll('input').find(n => n.getAttribute('aria-label') === label);
      assert.equal(input.type, type); assert.equal(input.value, value);
    }
    for (const [value, minutes] of [['00:00', 0], ['23:59', 1439]]) {
      await h.input(time.title, value); assert.equal(posts(h).length, minutes === 0 ? 0 : 1);
      h.click('Apply'); await h.settle();
      assert.equal(posts(h).at(-1).opts.body, JSON.stringify({ domain: 'screensaver', values: { overnightStartMin: minutes } }));
      assert.equal(find(h).value, value);
    }
  } finally { h.hide(); }
});
test('invalid and blank drafts show inline error, survive polls, and never apply', async () => {
  const { h, f } = await setup();
  try {
    assert.equal(find(h)?.type, 'time');
    for (const value of ['', '24:00', '09:5']) {
      await h.input(time.title, value); h.click('Apply'); await h.settle();
      assert.equal(posts(h).length, 0); assert.equal(find(h).value, value);
      assert.equal(find(h).getAttribute('aria-invalid'), 'true');
      const error = h.document.getElementById(find(h).getAttribute('aria-describedby'));
      assert.equal(error.hidden, false); assert.match(error.textContent, /time/i);
      f.control.help = `poll ${value}`; h.eval('loadSettings()'); await h.settle();
      assert.equal(find(h).value, value); assert.equal(find(h).getAttribute('aria-invalid'), 'true');
    }
  } finally { h.hide(); }
});
test('time drafts retain cached DOM, survive readOnly and busy transitions, and refresh after clearing', async () => {
  const { h, f } = await setup();
  try {
    assert.equal(find(h)?.type, 'time');
    await h.input(time.title, '07:45'); const input = find(h);
    h.eval('loadSettings()'); await h.settle(); assert.equal(find(h), input);
    f.control.readOnly = true; h.eval('loadSettings()'); await h.settle();
    assert.equal(find(h).disabled, true); assert.equal(find(h).value, '07:45');
    h.click('Apply'); await h.settle(); assert.equal(posts(h).length, 0);
    f.control.readOnly = false; h.eval('loadSettings()'); await h.settle();
    f.hold = true; f.fail = true; h.click('Apply'); await h.settle();
    assert.equal(find(h).disabled, true); assert.equal(find(h).value, '07:45');
    h.click('Apply'); assert.equal(posts(h).length, 1);
    f.jobs.shift()(); await h.settle(); assert.equal(find(h).value, '07:45'); assert.equal(find(h).disabled, false);
    f.hold = false; f.fail = false; f.omitDomain = true;
    h.click('Apply'); await h.settle(); assert.equal(find(h).value, '22:00');
  } finally { f.jobs.forEach(resolve => resolve()); await h.settle(); h.hide(); }
});
test('mixed times stay blank until chosen and send integer minute bytes to both Portals', async () => {
  const { h } = await setup();
  try {
    assert.equal(find(h)?.type, 'time');
    h.eval('scope="all";loadScopeSchemas()'); await h.settle(); assert.equal(find(h).value, '');
    h.click('Apply'); await h.settle(); assert.equal(posts(h).length, 0);
    await h.input(time.title, '06:30'); h.click('Apply'); await h.settle();
    assert.equal(posts(h).length, 2);
    assert.equal(new Set(posts(h).map(c => new URL(c.url).port)).size, 2);
    assert.equal(posts(h).every(c => c.opts.body === '{"domain":"screensaver","values":{"overnightStartMin":390}}'), true);
  } finally { h.hide(); }
});
test('stale time rows cannot apply and late completion keeps the new room draft', async () => {
  const { h, f } = await setup();
  try {
    assert.equal(find(h)?.type, 'time');
    await h.input(time.title, '03:00'); const oldApply = h.document.getElementById('page').querySelectorAll('button').find(n => n.textContent === 'Apply');
    f.hold = true; oldApply.click(); await h.settle();
    h.eval('selectRoom("http://127.0.0.1:8878")'); await h.settle();
    await h.input(time.title, '04:00'); oldApply.click(); await h.settle(); assert.equal(posts(h).length, 1);
    f.jobs.shift()(); await h.settle(); assert.equal(find(h).value, '04:00');
    const currentApply = h.document.getElementById('page').querySelectorAll('button').find(n => n.textContent === 'Apply');
    h.eval('go("home")'); await h.settle(); currentApply.click(); await h.settle(); assert.equal(posts(h).length, 1);
  } finally { f.jobs.forEach(resolve => resolve()); await h.settle(); h.hide(); }
});
