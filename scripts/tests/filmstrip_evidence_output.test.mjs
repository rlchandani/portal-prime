/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */
import test from 'node:test';
import assert from 'node:assert/strict';
import { mkdtempSync, readFileSync, writeFileSync, statSync, symlinkSync, rmSync } from 'node:fs';
import { join } from 'node:path';
import { tmpdir } from 'node:os';
import { writeFilmstripEvidence } from '../filmstrip_visible_preparation_browser.mjs';

function inEvidenceDirectory(check) {
  const root = mkdtempSync(join(tmpdir(), 'filmstrip-evidence-test-'));
  try { check(root); } finally { rmSync(root, { recursive: true, force: true }); }
}

test('evidence output creates one private file containing the complete generated report', () => {
  inEvidenceDirectory(root => {
    const path = join(root, 'report.json');
    const report = { passed: true, changedPixels: 0, scope: 'generated fixture' };
    writeFilmstripEvidence(path, report);
    assert.deepEqual(JSON.parse(readFileSync(path, 'utf8')), report);
    assert.equal(statSync(path).mode & 0o777, 0o600);
    assert.ok(readFileSync(path, 'utf8').endsWith('\n'));
  });
});

test('evidence output refuses to overwrite retained proof bytes', () => {
  inEvidenceDirectory(root => {
    const path = join(root, 'report.json');
    writeFileSync(path, 'retained generated proof\n', { flag: 'wx', mode: 0o600 });
    assert.throws(() => writeFilmstripEvidence(path, { passed: false }), { code: 'EEXIST' });
    assert.equal(readFileSync(path, 'utf8'), 'retained generated proof\n');
  });
});

test('evidence output refuses an existing symlink and leaves its target intact', () => {
  inEvidenceDirectory(root => {
    const target = join(root, 'retained.json'), link = join(root, 'report.json');
    writeFileSync(target, 'retained generated target\n', { flag: 'wx', mode: 0o600 });
    symlinkSync(target, link);
    assert.throws(() => writeFilmstripEvidence(link, { passed: false }), { code: 'EEXIST' });
    assert.equal(readFileSync(target, 'utf8'), 'retained generated target\n');
  });
});
