/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */
import test from 'node:test';
import assert from 'node:assert/strict';
import { browserHarness } from './remote-browser-harness.mjs';

const saved = [
  { base: 'http://127.0.0.1:8877', name: 'Public A', token: 'public-fixture-a' },
  { base: 'http://127.0.0.1:8878', name: 'Public B', token: 'public-fixture-b' },
];
const destination = '/sdcard/Pictures/Immortal';
const otherFolder = '/sdcard/Pictures/Other';
const fileRow = (path) => ({
  path, name: path.split('/').at(-1), size: 3, modified: 2,
});
const pageText = h => h.document.getElementById('page').textContent;
const galleryNames = h => h.document.getElementById('page')
  .querySelectorAll('button.photo-tile').map(node => node.attrs['aria-label']);
const importStatus = h => h.document.getElementById('upload-status').textContent;

// Only the public HTTP boundary is substituted. Every mutation starts through
// the shipped file picker or preview/confirmation buttons, with no loader patch.
async function fixture(t, { empty = false } = {}) {
  const files = new Map([
    [saved[0].base, empty ? [] : [fileRow(`${destination}/old.png`), fileRow(`${otherFolder}/other.png`)]],
    [saved[1].base, [fileRow(`${destination}/peer.png`)]],
  ]);
  const reads = [], uploads = [], deletes = [], readPlans = [], uploadModes = new Map();
  const heldReads = [], heldUploads = [], heldDeletes = [];
  let holdDelete = false, rejectDelete = false;
  const h = await browserHarness({ saved, routes: {
    '/remote/photos': ({ url, opts, response }) => {
      const parsed = new URL(url), path = parsed.searchParams.get('path');
      const recursive = parsed.searchParams.get('recursive') === '1';
      const payload = {
        ok: true, path, recursive, truncated: false, freeBytes: 1048576,
        files: (files.get(parsed.origin) || []).filter(row =>
          recursive || row.path.slice(0, row.path.lastIndexOf('/')) === path),
        folders: [{ path: destination, count: 1 }, { path: otherFolder, count: 1 }],
      };
      const record = { base: parsed.origin, path, recursive, signal: opts.signal };
      reads.push(record);
      const plan = readPlans.shift();
      if (plan === 'hold') {
        return new Promise(resolve => {
          let finished = false;
          const job = {
            ...record,
            finish(failed = false) {
              if (finished) return;
              finished = true;
              resolve(failed
                ? response({ ok: false, error: 'public_listing_failure' }, 503)
                : response(payload));
            },
          };
          // Ignore abort deliberately: a response body already in flight can
          // finish after replacement, so the controller must reject its owner.
          heldReads.push(job);
        });
      }
      if (plan === 'fail')
        return response({ ok: false, error: 'public_listing_failure' }, 503);
      return response(payload);
    },
    '/remote/photo-upload': ({ url, opts, response }) => {
      const parsed = new URL(url), name = parsed.searchParams.get('name');
      const path = `${parsed.searchParams.get('path')}/${name}`;
      uploads.push({ base: parsed.origin, name, path, signal: opts.signal });
      const complete = mode => {
        if (mode === 'failed') return response({ ok: false, error: 'file_exists' }, 409);
        files.get(parsed.origin).push(fileRow(path));
        return response({ ok: true, path, bytes: mode === 'unconfirmed' ? 1 : opts.body.size });
      };
      const mode = uploadModes.get(name);
      if (mode === 'hold') return new Promise(resolve => {
        let finished = false;
        heldUploads.push({
          finish() {
            if (finished) return;
            finished = true;
            resolve(complete('confirmed'));
          },
        });
      });
      return complete(mode);
    },
    '/remote/photo': ({ url, opts, response }) => {
      if (opts.method !== 'DELETE')
        return new Response(new Blob(['public-thumbnail'], { type: 'image/png' }));
      const parsed = new URL(url), path = parsed.searchParams.get('path');
      deletes.push({ base: parsed.origin, path });
      const complete = () => {
        if (rejectDelete) return response({ ok: false, error: 'public_delete_failure' }, 503);
        files.set(parsed.origin, files.get(parsed.origin).filter(row => row.path !== path));
        return response({ ok: true });
      };
      if (holdDelete) return new Promise(resolve => {
        let finished = false;
        heldDeletes.push({
          finish() {
            if (finished) return;
            finished = true;
            resolve(complete());
          },
        });
      });
      return complete();
    },
  } });
  t.after(async () => {
    h.hide();
    for (const job of [...heldReads, ...heldUploads, ...heldDeletes]) job.finish();
    await h.settle();
  });
  h.click('Photos');
  await h.settle();
  return {
    h, reads, uploads, deletes, readPlans, uploadModes, heldReads, heldUploads, heldDeletes,
    holdDelete: () => { holdDelete = true; },
    rejectDelete: () => { rejectDelete = true; },
    async refreshPending() {
      readPlans.push('hold');
      h.document.getElementById('refresh-button').click();
      await h.settle();
      return heldReads.at(-1);
    },
    async import(names = ['new.png']) {
      h.click('Import photos');
      const picker = h.document.body.querySelectorAll('input')
        .find(node => node.attrs['aria-label'] === 'Choose photos');
      assert.ok(picker, 'The shipped import dialog must expose its file picker');
      picker.files = names.map(name => Object.assign(
        new Blob(['abc'], { type: 'image/png' }), { name }));
      h.click('Start import');
      await h.settle();
    },
    async deleteOld() {
      const tile = h.document.getElementById('page').querySelectorAll('button.photo-tile')
        .find(node => node.attrs['aria-label'] === 'Preview old.png');
      assert.ok(tile);
      tile.click();
      await h.settle();
      h.click('Delete photo');
      h.document.getElementById('sheet-actions').querySelectorAll('button')
        .find(node => node.textContent === 'Delete').click();
      await h.settle();
    },
    async selectPeer() {
      h.document.getElementById('room-button').click();
      h.click('Select');
      await h.settle();
    },
  };
}

test('UI import replaces a pre-import Refresh so confirmed files appear without another user refresh', async t => {
  const f = await fixture(t, { empty: true }), { h } = f;
  const old = await f.refreshPending();
  await f.import();
  old.finish();
  await h.settle();
  assert.match(importStatus(h), /new.png: Stored and confirmed/);
  assert.deepEqual(galleryNames(h), ['Preview new.png']);
  assert.equal(f.reads.length, 3, 'Initial read, old Refresh, and one post-import readback');
  assert.equal(old.signal.aborted, true, 'Mutation confirmation retires the earlier read');
});

test('ordinary page reads still coalesce while a Photos Refresh is pending', async t => {
  const f = await fixture(t), old = await f.refreshPending();
  f.h.eval('loadVisible();loadVisible()');
  await f.h.settle();
  assert.equal(f.reads.length, 2);
  assert.equal(old.signal.aborted, false);
  assert.match(pageText(f.h), /Refreshing photos/);
  old.finish();
  await f.h.settle();
  assert.doesNotMatch(pageText(f.h), /Refreshing photos/);
});

for (const failed of [false, true]) {
  test(`old Photos ${failed ? 'failure' : 'success'} cannot publish or finish the pending post-import replacement`, async t => {
    const f = await fixture(t), { h } = f, old = await f.refreshPending();
    f.readPlans.push('hold');
    await f.import();
    assert.equal(f.reads.length, 3, 'Confirmation must request a new listing even while Refresh waits');
    assert.equal(f.heldReads.length, 2);
    const replacement = f.heldReads[1];
    old.finish(failed);
    await h.settle();
    assert.match(pageText(h), /Refreshing photos/);
    assert.doesNotMatch(pageText(h), /refresh failed/i);
    assert.deepEqual(galleryNames(h), ['Preview old.png', 'Preview other.png']);
    assert.equal(h.document.body.querySelectorAll('button')
      .find(node => node.textContent === 'Use for slideshow').disabled, true);
    assert.equal(replacement.signal.aborted, false);
    replacement.finish();
    await h.settle();
    assert.ok(galleryNames(h).includes('Preview new.png'));
    assert.doesNotMatch(pageText(h), /Refreshing photos|refresh failed/i);
  });
}

test('a batch import reads back once after its last confirmed file, with no per-file metadata requests', async t => {
  const f = await fixture(t), { h } = f;
  f.uploadModes.set('second.png', 'hold');
  await f.import(['first.png', 'second.png', 'third.png']);
  assert.equal(f.uploads.length, 2);
  assert.equal(f.reads.length, 1, 'No premature readback between files');
  assert.match(importStatus(h), /first.png: Stored and confirmed/);
  f.heldUploads[0].finish();
  await h.settle();
  assert.equal(f.uploads.length, 3);
  assert.equal(f.reads.length, 2, 'One fresh read for the whole batch');
  assert.ok(galleryNames(h).includes('Preview first.png'));
  assert.ok(galleryNames(h).includes('Preview second.png'));
  assert.ok(galleryNames(h).includes('Preview third.png'));
  assert.match(importStatus(h), /3 of 3 uploaded/);
});

test('a partly confirmed batch forces one fresh read while preserving rejected and unconfirmed outcomes', async t => {
  const f = await fixture(t), { h } = f, old = await f.refreshPending();
  f.uploadModes.set('rejected.png', 'failed');
  f.uploadModes.set('uncertain.png', 'unconfirmed');
  await f.import(['good.png', 'rejected.png', 'uncertain.png']);
  old.finish();
  await h.settle();
  assert.equal(f.reads.length, 3);
  assert.ok(galleryNames(h).includes('Preview good.png'));
  assert.match(importStatus(h), /good.png: Stored and confirmed/);
  assert.match(importStatus(h), /rejected.png: Failed/);
  assert.match(importStatus(h), /uncertain.png: Completion unconfirmed/);
  assert.match(importStatus(h), /1 of 3 uploaded/);
  assert.match(h.document.getElementById('notice').textContent, /unconfirmed.*refresh/i);
});

for (const mode of ['failed', 'unconfirmed']) {
  test(`an entirely ${mode} import does not replace an ordinary pending read or claim confirmed storage`, async t => {
    const f = await fixture(t), { h } = f, old = await f.refreshPending();
    f.uploadModes.set('new.png', mode);
    await f.import();
    assert.equal(f.reads.length, 2);
    assert.equal(old.signal.aborted, false);
    assert.match(importStatus(h), /0 of 1 uploaded/);
    assert.doesNotMatch(importStatus(h), /Stored and confirmed/);
    assert.match(importStatus(h), mode === 'failed' ? /new.png: Failed/ : /Completion unconfirmed/);
    old.finish();
    await h.settle();
  });
}

test('failed post-import readback retains confirmed upload status and the gallery with usable Retry', async t => {
  const f = await fixture(t), { h } = f;
  f.readPlans.push('fail');
  await f.import();
  assert.match(importStatus(h), /new.png: Stored and confirmed/);
  assert.match(pageText(h), /Photo refresh failed/);
  assert.deepEqual(galleryNames(h), ['Preview old.png', 'Preview other.png']);
  h.click('Retry photos');
  await h.settle();
  assert.ok(galleryNames(h).includes('Preview new.png'));
  assert.doesNotMatch(pageText(h), /Photo refresh failed/);
});

test('import completion refreshes the currently selected folder, without reverting to its upload destination', async t => {
  const f = await fixture(t), { h } = f;
  h.click('Folders');
  await h.settle();
  f.uploadModes.set('new.png', 'hold');
  await f.import();
  await h.input('Folder on Portal', otherFolder);
  f.readPlans.push('hold');
  h.click('Open folder');
  await h.settle();
  const old = f.heldReads.at(-1), before = f.reads.length;
  f.heldUploads[0].finish();
  await h.settle();
  assert.equal(f.uploads[0].path, `${destination}/new.png`);
  assert.equal(f.reads.length, before + 1);
  assert.equal(f.reads.at(-1).path, otherFolder);
  assert.equal(f.reads.at(-1).recursive, false);
  assert.equal(old.signal.aborted, true);
  old.finish();
  await h.settle();
  assert.deepEqual(galleryNames(h), ['Preview other.png']);
  assert.equal(h.document.body.querySelectorAll('input')
    .find(node => node.attrs['aria-label'] === 'Folder on Portal').value, otherFolder);
});

test('leaving Photos during upload keeps the transfer ledger but starts no readback on Apps', async t => {
  const f = await fixture(t), { h } = f;
  f.uploadModes.set('new.png', 'hold');
  await f.import();
  h.click('Apps');
  await h.settle();
  const before = f.reads.length;
  f.heldUploads[0].finish();
  await h.settle();
  assert.equal(f.reads.length, before);
  assert.doesNotMatch(pageText(h), /uploaded|new.png/);
  h.click('Photos');
  await h.settle();
  assert.match(importStatus(h), /new.png: Stored and confirmed/);
  assert.ok(galleryNames(h).includes('Preview new.png'));
});

test('switching Portals during upload cannot refresh or publish that upload in the peer gallery', async t => {
  const f = await fixture(t), { h } = f;
  f.uploadModes.set('first.png', 'hold');
  await f.import(['first.png', 'queued.png']);
  await f.selectPeer();
  const before = f.reads.length;
  f.heldUploads[0].finish();
  await h.settle();
  assert.equal(f.uploads.length, 1, 'Queued uploads remain cancelled');
  assert.equal(f.reads.length, before);
  assert.deepEqual(galleryNames(h), ['Preview peer.png']);
  assert.equal(h.document.getElementById('upload-status'), undefined);
  assert.doesNotMatch(h.document.getElementById('notice').textContent, /photos stored/i);
});

for (const retirement of ['page', 'room', 'hidden']) {
  test(`late post-import listing cannot replace the gallery after ${retirement} retirement`, async t => {
    const f = await fixture(t), { h } = f;
    f.readPlans.push('hold');
    await f.import();
    const replacement = f.heldReads[0];
    assert.ok(replacement);
    if (retirement === 'page') { h.click('Apps'); await h.settle(); }
    else if (retirement === 'room') await f.selectPeer();
    else h.hide();
    const before = f.reads.length, notice = h.document.getElementById('notice').textContent;
    replacement.finish();
    await h.settle();
    assert.equal(f.reads.length, before);
    if (retirement !== 'hidden')
      assert.equal(h.document.getElementById('notice').textContent, notice);
    assert.equal(replacement.signal.aborted, true);
    if (retirement === 'room') assert.deepEqual(galleryNames(h), ['Preview peer.png']);
    else if (retirement === 'page') assert.doesNotMatch(pageText(h), /new.png/);
    else {
      assert.deepEqual(galleryNames(h), ['Preview old.png', 'Preview other.png']);
      h.show();
      await h.settle();
      assert.ok(galleryNames(h).includes('Preview new.png'));
    }
  });
}

test('a confirmed Delete replaces a listing started before deletion and does not resurrect the removed photo', async t => {
  const f = await fixture(t), { h } = f;
  f.holdDelete();
  await f.deleteOld();
  assert.equal(f.deletes.length, 1);
  const old = await f.refreshPending();
  f.heldDeletes[0].finish();
  await h.settle();
  old.finish();
  await h.settle();
  assert.equal(f.reads.length, 3);
  assert.equal(old.signal.aborted, true);
  assert.deepEqual(galleryNames(h), ['Preview other.png']);
});

test('a rejected Delete preserves the photo and does not replace a useful pending listing', async t => {
  const f = await fixture(t), { h } = f;
  f.holdDelete();
  f.rejectDelete();
  await f.deleteOld();
  const old = await f.refreshPending();
  f.heldDeletes[0].finish();
  await h.settle();
  assert.equal(f.reads.length, 2);
  assert.equal(old.signal.aborted, false);
  old.finish();
  await h.settle();
  assert.ok(galleryNames(h).includes('Preview old.png'));
  assert.doesNotMatch(h.document.getElementById('notice').textContent, /Photo deleted/);
});
