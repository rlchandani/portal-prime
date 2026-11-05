/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */
import test from 'node:test';
import assert from 'node:assert/strict';
import {readFileSync} from 'node:fs';
import {runInNewContext} from 'node:vm';
import {PhotoImportBatch} from '../../main/assets/remote/core.mjs';

const source = readFileSync(new URL('../../main/assets/remote/control-center.js',
  import.meta.url), 'utf8');
const retirement = source.slice(source.indexOf('function retireStaleUploads() {'),
  source.indexOf('\nfunction chooseUpload()', source.indexOf('function retireStaleUploads() {')));
assert.ok(retirement.startsWith('function retireStaleUploads() {'));
const oldRoom = {base:'https://generated.invalid',token:'old-generated-session'};
const currentRoom = {...oldRoom,token:'new-generated-session'};
const files = [{name:'history.png',size:2},{name:'retry.png',size:3}];
function batch(historyState, activeState, activeRoom = currentRoom) {
  const upload = new PhotoImportBatch(
    [{room:oldRoom,path:'/sdcard/Pictures/Generated',deviceId:'generated-device'}],files);
  upload.items[0].state = historyState;
  upload.items[1].state = activeState;
  upload.items[1].room = Object.freeze({...activeRoom});
  upload.running = true;
  upload.lanes.set(oldRoom.base,new AbortController());
  return upload;
}
function retire(upload, pendingPhotoImport = null) {
  runInNewContext(`${retirement}\nretireStaleUploads();`, {
    upload,pendingPhotoImport,currentUploadRoom:room=>room.token===currentRoom.token,
  });
}

for (const history of ['confirmed','failed','unconfirmed','cancelled'])
  for (const active of ['queued','sending'])
    test(`${history} history with old pairing cannot retire a current ${active} copy`,()=>{
      const upload = batch(history,active);
      retire(upload);
      assert.equal(upload.lanes.get(oldRoom.base).signal.aborted,false);
      assert.equal(upload.retired.size,0);
      assert.equal(upload.items[0].state,history);
      assert.equal(upload.items[0].room.token,oldRoom.token);
      assert.equal(upload.items[1].state,active);
    });

for (const active of ['queued','sending'])
  test(`a stale ${active} copy still retires its destination`,()=>{
    const upload = batch('confirmed',active,oldRoom);
    retire(upload);
    assert.equal(upload.lanes.get(oldRoom.base).signal.aborted,true);
    assert.equal(upload.retired.has(oldRoom.base),true);
    assert.equal(upload.items[1].state,active==='queued'?'cancelled':'sending');
  });
test('current queued work remains owned when terminal history is current too',()=>{
  const upload = batch('confirmed','queued');
  upload.items[0].room = Object.freeze({...currentRoom});
  retire(upload);
  assert.equal(upload.retired.size,0);
  assert.equal(upload.lanes.get(oldRoom.base).signal.aborted,false);
});
test('stale pending preflight still aborts before a batch exists',()=>{
  const pending = {rooms:[oldRoom],controller:new AbortController()};
  retire(null,pending);
  assert.equal(pending.retired,true);
  assert.equal(pending.controller.signal.aborted,true);
});
test('settled historical results do not start retirement work',()=>{
  const upload = batch('confirmed','failed',oldRoom);
  upload.running = false;
  retire(upload);
  assert.equal(upload.retired.size,0);
  assert.equal(upload.lanes.get(oldRoom.base).signal.aborted,false);
});
