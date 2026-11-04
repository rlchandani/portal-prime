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
  let deleteMode = 'confirmed';
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
        if (mode === 'readable-invalid-response')
          return response({ ok: false, error: 'invalid_response' }, 400);
        if (mode === 'malformed-auth')
          return new Response('<html>pairing expired</html>', { status: 401 });
        files.get(parsed.origin).push(fileRow(path));
        if (mode === 'malformed-success')
          return new Response('{"ok":true,"path":', { status: 200 });
        if (mode === 'empty-success')
          return new Response('', { status: 200 });
        if (mode === 'malformed-gateway')
          return new Response('<html>upstream reply lost</html>', { status: 502 });
        if (mode === 'missing-ok') return response({ path, bytes: opts.body.size });
        if (mode === 'invalid-ok') return response({ ok: 'false', path, bytes: opts.body.size });
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
        if (deleteMode === 'wrong-path')
          return response({ ok: true, path: `${destination}/different.png` });
        if (deleteMode === 'missing-path') return response({ ok: true });
        if (deleteMode === 'missing-ok') return response({ path });
        if (deleteMode === 'invalid-ok') return response({ ok: 'true', path });
        if (deleteMode === 'false-ack') return response(false);
        if (deleteMode === 'zero-ack') return response(0);
        if (deleteMode === 'empty-string-ack') return response('');
        files.set(parsed.origin, files.get(parsed.origin).filter(row => row.path !== path));
        return response({ ok: true, path });
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
    deleteMode: value => { deleteMode = value; },
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
      picker.onchange?.({target:picker});
      h.document.body.querySelectorAll('button').find(b=>/^Import \d+ photo/.test(b.textContent)).click();
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

for (const mode of ['malformed-success', 'empty-success', 'malformed-gateway']) {
  test(`a ${mode} upload acknowledgment remains unconfirmed until the user refreshes`, async t => {
    const f = await fixture(t), { h } = f;
    f.uploadModes.set('new.png', mode);
    await f.import();
    assert.match(importStatus(h), /new.png: Completion unconfirmed/);
    assert.doesNotMatch(importStatus(h), /new.png: Failed|Stored and confirmed/);
    assert.match(h.document.getElementById('notice').textContent, /unconfirmed.*refresh/i);
    assert.equal(f.uploads.length, 1, 'An ambiguous acknowledgment must never cause an automatic resend');
    assert.equal(f.reads.length, 1, 'Unconfirmed storage retains the existing manual-review boundary');
    h.document.getElementById('refresh-button').click();
    await h.settle();
    assert.ok(galleryNames(h).includes('Preview new.png'));
    assert.match(importStatus(h), /Completion unconfirmed/, 'A listing is not an upload acknowledgment');
  });
}

test('an ambiguous reply preserves the next file and its independently confirmed outcome', async t => {
  const f = await fixture(t), { h } = f;
  f.uploadModes.set('uncertain.png', 'malformed-success');
  await f.import(['uncertain.png', 'confirmed.png']);
  assert.match(importStatus(h), /uncertain.png: Completion unconfirmed/);
  assert.match(importStatus(h), /confirmed.png: Stored and confirmed/);
  assert.match(importStatus(h), /1 of 2 uploaded/);
  assert.equal(f.uploads.length, 2);
  assert.equal(f.reads.length, 2, 'The one confirmed file retains the single batch readback');
});

test('a readable invalid_response rejection remains failed and never counts as stored', async t => {
  const f = await fixture(t), { h } = f;
  f.uploadModes.set('new.png', 'readable-invalid-response');
  await f.import();
  assert.match(importStatus(h), /new.png: Failed/);
  assert.doesNotMatch(importStatus(h), /Completion unconfirmed|Stored and confirmed/);
  assert.match(importStatus(h), /0 of 1 uploaded/);
});

for (const mode of ['missing-ok', 'invalid-ok']) {
  test(`matching path and bytes with ${mode} cannot confirm an upload`, async t => {
    const f = await fixture(t), { h } = f;
    f.uploadModes.set('new.png', mode);
    await f.import();
    assert.match(importStatus(h), /new.png: Completion unconfirmed/);
    assert.doesNotMatch(importStatus(h), /Stored and confirmed/);
    assert.match(importStatus(h), /0 of 1 uploaded/);
  });
}

test('an unreadable 401 acknowledgment stops queued imports and preserves transfer outcomes', async t => {
  const f = await fixture(t), { h } = f;
  f.uploadModes.set('first.png', 'malformed-auth');
  await f.import(['first.png', 'queued.png']);
  assert.equal(f.uploads.length, 1);
  assert.equal(h.eval('connectionState'), 'expired');
  assert.deepEqual(Array.from(h.eval('upload.items.map(item => item.state)')), ['unconfirmed', 'cancelled']);
  assert.equal(h.eval('upload.completed'), 0);
  assert.match(h.document.getElementById('connection').textContent, /Pair again/);
});

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
  assert.equal(f.uploads.length, 2, 'Queued uploads continue only to the captured Portal');
  assert.ok(f.uploads.every(upload=>upload.base===saved[0].base));
  assert.equal(f.reads.length, before);
  assert.deepEqual(galleryNames(h), ['Preview peer.png']);
  assert.ok(h.document.getElementById('upload-status'));
  assert.doesNotMatch(h.document.getElementById('notice').textContent, /photos stored/i);
});

for (const renamed of [false, true]) {
  test(`reselecting the active Portal keeps both imports and the folder view${renamed ? ' after a name update' : ''}`, async t => {
    const f = await fixture(t), { h } = f;
    h.click('Folders');
    await h.settle();
    await h.input('Folder on Portal', otherFolder);
    h.click('Open folder');
    await h.settle();
    f.uploadModes.set('first.png', 'hold');
    await f.import(['first.png', 'queued.png']);
    const generation = h.eval('session.generation');
    const pageGeneration = h.eval('session.pageGeneration');
    const calls = h.calls.length;
    if (renamed) h.eval('roster[0] = { ...roster[0], name: "Renamed A" }');
    h.document.getElementById('room-button').click();
    h.click('Selected');
    await h.settle();
    assert.equal(h.eval('upload.controller.signal.aborted'), false);
    assert.equal(h.eval('session.generation'), generation);
    assert.equal(h.eval('session.pageGeneration'), pageGeneration);
    assert.equal(h.document.getElementById('sheet').open, false);
    assert.equal(h.calls.length, calls, 'Selection adds no replacement reads or roster sync');
    assert.equal(h.eval('photoPath'), otherFolder);
    if (renamed)
      assert.match(h.document.getElementById('room-button').textContent, /Renamed A/);
    f.heldUploads[0].finish();
    await h.settle();
    assert.equal(f.uploads.length, 2);
    assert.equal(f.uploads.every(item => item.base === saved[0].base), true);
    assert.equal(f.uploads.every(item => item.path.startsWith(`${otherFolder}/`)), true);
    assert.match(importStatus(h), /2 of 2 uploaded/);
    assert.match(importStatus(h), /first.png: Stored and confirmed/);
    assert.match(importStatus(h), /queued.png: Stored and confirmed/);
    assert.doesNotMatch(importStatus(h), /Not sent|Completion unconfirmed/);
  });
}

test('replacing the active pairing token still cancels its queued imports', async t => {
  const f = await fixture(t), { h } = f;
  f.uploadModes.set('first.png', 'hold');
  await f.import(['first.png', 'queued.png']);
  const oldSignal = f.uploads[0].signal;
  const generation = h.eval('session.generation');
  h.eval('roster[0] = { ...roster[0], token: "replacement-session" }; selectRoom(roster[0].base)');
  await h.settle();
  assert.equal(oldSignal.aborted, true);
  assert.equal(h.eval('room().token'), 'replacement-session');
  assert.equal(h.eval('session.generation'), generation + 1);
  f.heldUploads[0].finish();
  await h.settle();
  assert.equal(f.uploads.length, 1, 'Old queued work must not start with replaced credentials');
});

test('reselecting an expired active pairing still retires its pending import', async t => {
  const f = await fixture(t), { h } = f;
  f.uploadModes.set('first.png', 'hold');
  await f.import(['first.png', 'queued.png']);
  const oldSignal = f.uploads[0].signal;
  h.eval('expired.set(room().base, room().token); connectionState = "expired"');
  h.document.getElementById('room-button').click();
  h.click('Selected');
  await h.settle();
  assert.equal(oldSignal.aborted, true);
  assert.equal(h.eval('connectionState'), 'expired');
  f.heldUploads[0].finish();
  await h.settle();
  assert.equal(f.uploads.length, 1);
  assert.match(h.document.getElementById('connection').textContent, /Pair again/);
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
  assert.match(h.document.getElementById('notice').textContent, /Photo deleted/);
});

for (const mode of ['wrong-path', 'missing-path', 'missing-ok', 'invalid-ok', 'false-ack', 'zero-ack', 'empty-string-ack']) {
  test(`a ${mode} Delete acknowledgment cannot claim the selected photo was deleted`, async t => {
    const f = await fixture(t), { h } = f;
    f.deleteMode(mode);
    await f.deleteOld();
    assert.equal(f.deletes.length, 1, 'Unconfirmed deletion must never be retried automatically');
    assert.equal(f.reads.length, 2, 'Keep the existing single metadata review');
    assert.ok(galleryNames(h).includes('Preview old.png'));
    assert.doesNotMatch(h.document.getElementById('notice').textContent, /Photo deleted/);
    assert.match(h.document.getElementById('notice').textContent, /did not confirm.*deletion/i);
  });
}

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
