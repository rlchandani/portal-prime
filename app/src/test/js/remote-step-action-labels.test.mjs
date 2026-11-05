/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */
import test from 'node:test';
import assert from 'node:assert/strict';
import { browserHarness } from './remote-browser-harness.mjs';

const saved = [{ base: 'http://127.0.0.1:8877', name: 'Generated Portal', token: 'generated-session' }];
const steps = [{ type: 'wait', ms: 100 }, { type: 'wait', ms: 200 }];
const byName = (h, name) => h.document.body.querySelectorAll('button')
  .find(button => button.getAttribute('aria-label') === name);

for (const mode of ['scene', 'preset']) {
  test(`${mode} step actions identify their current row and retain action targeting`, async () => {
    const h = await browserHarness({ saved, routes: {
      '/remote/scenes': ({ response }) => response({ ok: true, revision: 'current',
        scenes: [{ id: 'generated-scene', name: 'Generated scene', steps, schedule: null }] }),
      '/remote/presets': ({ response }) => response({ ok: true,
        presets: [{ id: 'generated-preset', name: 'Generated preset', steps }] }),
    } });
    h.eval(`go("home", "${mode === 'scene' ? 'scenes' : 'presets'}")`);
    await h.settle();
    h.click('Edit');
    await h.settle();
    const labels = () => h.document.body.querySelectorAll('.reorder-row')
      .map(row => row.querySelectorAll('button').map(button => button.getAttribute('aria-label')));
    assert.deepEqual(labels(), [
      ['Move step 1 up', 'Move step 1 down', 'Edit step 1', 'Remove step 1'],
      ['Move step 2 up', 'Move step 2 down', 'Edit step 2', 'Remove step 2'],
    ]);
    assert.equal(byName(h, 'Move step 1 up').disabled, true);
    assert.equal(byName(h, 'Move step 2 down').disabled, true);
    byName(h, 'Move step 2 up').click();
    await h.settle();
    assert.equal(h.eval(`${mode}Draft.steps.map(entry => entry.step.ms).join(",")`), '200,100');
    byName(h, 'Remove step 1').click();
    await h.settle();
    assert.equal(h.eval(`${mode}Draft.steps.map(entry => entry.step.ms).join(",")`), '100');
    assert.deepEqual(labels(), [
      ['Move step 1 up', 'Move step 1 down', 'Edit step 1', 'Remove step 1'],
    ]);
    byName(h, 'Edit step 1').click();
    await h.settle();
    assert.equal(h.document.getElementById('sheet').open, true);
    assert.equal(h.calls.filter(call => call.opts.method === 'POST').length, 0);
  });
}

test('ordinary button labels and default styling remain intact', async () => {
  const h = await browserHarness({ saved });
  const ordinary = h.eval('button("Save", () => {})');
  assert.equal(ordinary.textContent, 'Save');
  assert.equal(ordinary.getAttribute('aria-label'), undefined);
  assert.equal(ordinary.getAttribute('class'), 'button ');
});
