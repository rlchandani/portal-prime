/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */
import test from 'node:test';
import assert from 'node:assert/strict';
import { visibleControls } from '../../main/assets/remote/core.mjs';

test('settings search trims and splits repeated whitespace without regard to case', () => {
  const control = { key: 'intervalSec', title: 'Photo interval' };
  const domain = { title: 'Screensaver', controls: [control] };

  assert.deepEqual(visibleControls(domain, false, ' \tSCREENSAVER  \n pHoTo\t '), [control]);
});

test('settings search requires every unordered term across the combined registry context', () => {
  const control = {
    key: 'intervalSec',
    title: 'Photo interval',
    help: 'Cycle every few seconds',
    shortHelp: 'Family album',
    section: 'Slideshow timing',
  };
  const domain = {
    title: 'Screensaver',
    controls: [control, { ...control, key: 'delay' }],
  };

  assert.deepEqual(
    visibleControls(domain, false, 'screensaver photo cycle family intervalsec timing'),
    [control],
  );
  assert.deepEqual(
    visibleControls(domain, false, 'TIMING intervalSec FAMILY cycle PHOTO screensaver'),
    [control],
  );
  assert.deepEqual(
    visibleControls(domain, false, 'screensaver photo cycle family intervalsec timing missing'),
    [],
  );
  assert.deepEqual(
    visibleControls(
      { title: 'Screensaver', controls: [{ title: 'Photo' }, { title: 'Shuffle' }] },
      false,
      'photo shuffle',
    ),
    [],
  );
});

test('settings search finds controls by section even when their titles omit it', () => {
  const timing = { key: 'intervalSec', title: 'Photo interval', section: 'Slideshow timing' };
  const sound = { key: 'volume', title: 'Volume', section: 'Audio' };
  const domain = { title: 'Screensaver', controls: [timing, sound] };

  assert.deepEqual(visibleControls(domain, false, 'SLIDESHOW'), [timing]);
});

test('settings search finds short help when long help is absent', () => {
  const control = { key: 'folder', title: 'Photos', shortHelp: 'Choose a collection' };
  const domain = { title: 'Screensaver', controls: [control, { key: 'shuffle' }] };

  assert.deepEqual(visibleControls(domain, false, 'COLLECTION'), [control]);
});

test('context matches retain original controls and registry order behind the Advanced gate', () => {
  const first = Object.freeze({ key: 'zulu', title: 'Enabled', section: 'Connection' });
  const advanced = Object.freeze({
    key: 'diagnostics',
    title: 'Log diagnostics',
    section: 'Connection',
    tier: 'advanced',
  });
  const unrelated = Object.freeze({ key: 'volume', title: 'Volume', section: 'Audio' });
  const last = Object.freeze({ key: 'alpha', title: 'Enabled', section: 'Connection' });
  const controls = Object.freeze([first, advanced, unrelated, last]);
  const domain = Object.freeze({ title: 'MQTT', controls });

  const basicMatches = visibleControls(domain, false, 'mqtt connection');
  assert.deepEqual(basicMatches, [first, last]);
  assert.equal(basicMatches[0], first);
  assert.equal(basicMatches[1], last);
  const advancedMatches = visibleControls(domain, true, 'mqtt connection');
  assert.deepEqual(advancedMatches, [first, advanced, last]);
  assert.equal(advancedMatches[0], first);
  assert.equal(advancedMatches[1], advanced);
  assert.equal(advancedMatches[2], last);
  assert.deepEqual(controls, [first, advanced, unrelated, last]);
});

test('blank queries retain visibility and missing controls remain an empty result', () => {
  const basic = { key: 'enabled' };
  const advanced = { key: 'diagnostics', tier: 'advanced' };
  const domain = { controls: [basic, advanced] };

  for (const query of ['', ' \t\n  ', undefined]) {
    assert.deepEqual(visibleControls(domain, false, query), [basic]);
    assert.deepEqual(visibleControls(domain, true, query), [basic, advanced]);
  }
  assert.deepEqual(visibleControls({ title: 'Screensaver' }, false, 'screensaver'), []);
  assert.deepEqual(visibleControls({ controls: null }, true, ' \t '), []);
  assert.deepEqual(visibleControls({ controls: [] }, false), []);
  assert.deepEqual(visibleControls(domain, true, 'undefined'), []);
  assert.deepEqual(visibleControls(domain, true, 'null'), []);
});
