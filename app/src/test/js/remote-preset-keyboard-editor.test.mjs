/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */
import test from 'node:test';
import assert from 'node:assert/strict';
import { browserHarness } from './remote-browser-harness.mjs';

const saved = [{ base: 'http://127.0.0.1:8877', name: 'Fixture', token: 'public-fixture' },
  { base: 'http://127.0.0.1:8878', name: 'Other', token: 'public-peer' }];
const labels = ['Replace text', 'Append text', 'Backspace', 'Clear field'];
const modes = ['set', 'append', 'backspace', 'clear'];
const content = h => h.document.getElementById('sheet-content');
const find = (h, label) => content(h).querySelectorAll('input,select')
  .find(node => node.getAttribute('aria-label') === label);
async function select(h, label, value) {
  const picker = find(h, label) || (label === 'Keyboard action' ? find(h, 'Keyboard mode') : null);
  assert.ok(picker, `Missing ${label}`);
  picker.value = value;
  for (const listener of picker.listeners.change || []) listener({ target: picker });
  await h.settle();
}
async function setup(step = null) {
  const presets = [{ id: 'fixture', name: 'Public preset', steps: step ? [step] : [] }];
  const h = await browserHarness({ saved, routes: {
    '/remote/presets': ({ response }) => response({ ok: true, presets }),
  } });
  h.eval('go("remote","presets")'); await h.settle();
  h.click('Edit'); await h.settle();
  if (step) h.click('Edit');
  else { h.click('Add step'); await h.settle(); await select(h, 'Action type', 'text'); }
  await h.settle(); return h;
}
const noWrites = h => assert.equal(h.calls.some(call => call.opts.method === 'POST'), false);

test('keyboard action choices use readable names without changing wire values', async () => {
  const h = await setup();
  try {
    const picker = find(h, 'Keyboard action'); assert.ok(picker);
    assert.deepEqual(picker.children.map(option => option.textContent), ['Replace', 'Append', 'Backspace', 'Clear field']);
    assert.deepEqual(picker.children.map(option => option.value), modes);
    assert.match(content(h).textContent, /Replaces all text in the focused field on the Portal/);
    assert.ok(find(h, 'Text'));
    await h.input('Text', '  Draft stays exact  ');
    h.click('Save step'); await h.settle();
    assert.equal(h.eval('presetDraft.steps[0].step.mode'), 'set');
    assert.equal(h.eval('presetDraft.steps[0].step.text'), '  Draft stays exact  ');
    assert.match(h.document.getElementById('page').textContent, /Keyboard · Replace text/);
    noWrites(h);
  } finally { h.hide(); }
});

test('Backspace and Clear hide ignored text and switching back preserves the draft', async () => {
  const h = await setup();
  try {
    await h.input('Text', '  Keep my draft  ');
    await select(h, 'Keyboard action', 'backspace');
    assert.equal(!!find(h, 'Text'), false);
    assert.match(content(h).textContent, /Deletes from the end of the focused field on the Portal/);
    await select(h, 'Keyboard action', 'clear');
    assert.equal(!!find(h, 'Text'), false);
    assert.match(content(h).textContent, /Clears all text in the focused field on the Portal/);
    await select(h, 'Keyboard action', 'append');
    assert.equal(find(h, 'Text').value, '  Keep my draft  ');
    assert.match(content(h).textContent, /Adds text to the end of the focused field on the Portal/);
    await select(h, 'Keyboard action', 'set');
    assert.equal(find(h, 'Text').value, '  Keep my draft  ');
    noWrites(h);
  } finally { h.hide(); }
});

for (const [mode, label, showText] of [
  [' APPEND ', 'Append text', true], [' CLEAR ', 'Clear field', false],
  ['future-mode', 'Replace text', true], ['', 'Replace text', true],
  ['\u001cAPPEND\u001f', 'Append text', true], ['\ufeffappend\ufeff', 'Replace text', true],
  ['\u00a0clear\u2007', 'Clear field', false], ['\u0085append', 'Replace text', true],
]) {
  test(`existing ${JSON.stringify(mode)} mode displays backend semantics without rewriting saved presets`, async () => {
    const step = { type: 'text', mode, text: 'legacy text', extension: { keep: true } };
    const h = await setup(step);
    try {
      assert.equal((find(h, 'Keyboard action') || find(h, 'Keyboard mode'))?.value, modes[labels.indexOf(label)]);
      assert.equal(!!find(h, 'Text'), showText);
      assert.match(h.document.getElementById('page').textContent, new RegExp(`Keyboard · ${label}`));
      assert.deepEqual(JSON.parse(h.eval('JSON.stringify(data.presets[0].steps[0])')), step);
      h.click('Save step'); await h.settle();
      const edited = JSON.parse(h.eval('JSON.stringify(presetDraft.steps[0].step)'));
      assert.equal(edited.mode, mode || 'set');
      assert.equal(edited.text, 'legacy text');
      assert.deepEqual(edited.extension, { keep: true });
      noWrites(h);
    } finally { h.hide(); }
  });
}

test('canceling a changed keyboard step preserves the original entry and extension fields', async () => {
  const step = { type: 'text', mode: 'append', text: 'original', extension: { keep: true } };
  const h = await setup(step);
  try {
    await h.input('Text', 'unsaved');
    await select(h, 'Keyboard action', 'clear');
    h.eval('closeSheet()'); await h.settle();
    assert.deepEqual(JSON.parse(h.eval('JSON.stringify(presetDraft.steps[0].step)')), step);
    assert.deepEqual(JSON.parse(h.eval('JSON.stringify(data.presets[0].steps[0])')), step);
    noWrites(h);
  } finally { h.hide(); }
});

for (const departure of ['page', 'Portal']) {
  test(`keyboard step Save refuses a departed ${departure}`, async () => {
    const h = await setup();
    try {
      const save = h.document.getElementById('sheet-actions').querySelectorAll('button')
        .find(node => node.textContent === 'Save step');
      assert.ok(save);
      await h.input('Text', 'old-page');
      h.eval(departure === 'page' ? 'go("home")' : 'selectRoom("http://127.0.0.1:8878")');
      await h.settle();
      const current = h.eval('JSON.stringify(presetDraft)');
      save.click(); await h.settle();
      assert.equal(h.eval('JSON.stringify(presetDraft)'), current); noWrites(h);
    } finally { h.hide(); }
  });
}

test('legacy keyboard step without a mode shows Replace text and keeps unrelated fields', async () => {
  const step = { type: 'text', text: 'legacy', extension: { keep: true } };
  const h = await setup(step);
  try {
    assert.equal(find(h, 'Keyboard action')?.value, 'set');
    assert.match(h.document.getElementById('page').textContent, /Keyboard · Replace text/);
    assert.deepEqual(JSON.parse(h.eval('JSON.stringify(data.presets[0].steps[0])')), step);
    h.click('Save step'); await h.settle();
    assert.equal(h.eval('presetDraft.steps[0].step.mode'), 'set');
    assert.equal(h.eval('presetDraft.steps[0].step.extension.keep'), true); noWrites(h);
  } finally { h.hide(); }
});
