/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */
import test from 'node:test';
import assert from 'node:assert/strict';
import { browserHarness } from './remote-browser-harness.mjs';
const saved = [{base:'http://127.0.0.1:8877',name:'A',token:'fixture-a'},
  {base:'http://127.0.0.1:8878',name:'B',token:'fixture-b'}];
async function setup({pending=false}={}) {
  const jobs=[];
  let metadataError=false;
  const h=await browserHarness({saved,routes:{
    '/remote/photos':({response})=>response(metadataError ? {error:'unreachable'} : {
      path:'/sdcard',recursive:true,folders:[],files:Array.from({length:24},(_,i)=>({
        path:`/sdcard/Pictures/${i}.jpg`,name:`${i}.jpg`,size:1024,modified:1000}))},metadataError ? 503 : 200),
    '/remote/photo':({opts})=>pending ? new Promise(resolve=>jobs.push({signal:opts.signal,
      finish:()=>resolve(new Response(new Uint8Array([1]), {headers:{'Content-Type':'image/png'}}))})) : new Response(new Uint8Array([1]), {headers:{'Content-Type':'image/png'}}),
  }});
  h.eval(`globalThis.observations=[]; globalThis.IntersectionObserver=class {
    constructor(callback,options){this.callback=callback;this.options=options;this.targets=[];observations.push(this)}
    observe(target){this.targets.push(target)}
    unobserve(target){this.targets=this.targets.filter(t=>t!==target)}
    disconnect(){this.disconnected=true;this.targets=[]}
    emit(targets,visible=true){this.callback(targets.map(target=>({target,isIntersecting:visible})),this)}
  }`);
  h.eval('go("photos")'); await h.settle();
  const calls=()=>h.calls.filter(c=>c.path==='/remote/photo');
  const tiles=()=>h.document.getElementById('page').querySelectorAll('.photo-tile');
  const current=()=>h.eval('observations.at(-1)');
  const emit=async (targets)=>{current().emit(targets);await h.settle()};
  const finish=async()=>{while(jobs.length){jobs.splice(0).forEach(j=>j.finish());await h.settle()}};
  return {h,jobs,calls,tiles,current,emit,finish,failMetadata:()=>{metadataError=true}};
}
test('offscreen photo tiles start no requests; nearby admission is once per tile',async()=>{
  const {h,calls,tiles,current,emit}=await setup();
  try {
    assert.equal(calls().length,0,'Offscreen photo requests must wait for viewport admission');
    assert.equal(current().options.rootMargin,'200px 0px');
    assert.equal(current().targets.length,24);
    const near=tiles().slice(0,4); await emit(near); await emit(near);
    assert.equal(calls().length,4);
    assert.ok(near.every(t=>!t.querySelector('img').hidden));
    assert.ok(tiles().slice(4).every(t=>t.querySelector('img').hidden));
    await emit(tiles().slice(4,6)); assert.equal(calls().length,6);
  } finally {h.hide()}
});
test('same-page render retires queued observation and reuses admitted cached thumbnails',async()=>{
  const {h,calls,tiles,current,emit}=await setup();
  try {
    assert.equal(calls().length,0);
    const old=current(),detached=tiles(); await emit(detached.slice(0,2));
    const urls=detached.slice(0,2).map(t=>t.querySelector('img').src);
    h.eval('render()'); await h.settle();
    assert.equal(old.disconnected,true);
    assert.deepEqual(tiles().slice(0,2).map(t=>t.querySelector('img').src),urls,
      'Already admitted cached images must rebind before another observer callback');
    old.emit(detached); await h.settle();
    assert.equal(calls().length,2,'Retired callback cannot admit old tiles');
    await emit(tiles().slice(0,2)); assert.equal(calls().length,2);
    assert.deepEqual(tiles().slice(0,2).map(t=>t.querySelector('img').src),urls);
  } finally {h.hide()}
});
test('pending admitted loads rebind after render and retain the four-request limit',async()=>{
  const {h,calls,tiles,current,emit,jobs,finish}=await setup({pending:true});
  try {
    assert.equal(calls().length,0); await emit(tiles().slice(0,6)); assert.equal(calls().length,4);
    const old=current(),detached=tiles(); h.eval('render()');await h.settle();
    old.emit(detached);await emit(tiles().slice(0,6));assert.equal(calls().length,4);
    assert.ok(jobs.every(j=>!j.signal.aborted));await finish();assert.equal(calls().length,6);
    assert.ok(tiles().slice(0,6).every(t=>!t.querySelector('img').hidden));
    assert.ok(detached.every(t=>!t.querySelector('img').src));
  } finally {h.hide();await finish()}
});
test('navigation and room changes reject callbacks queued for retired photo tiles',async()=>{
  const {h,calls,tiles,current,emit}=await setup();
  try {
    assert.equal(calls().length,0);const old=current(),detached=tiles();
    h.eval('go("home")');old.emit(detached);await h.settle();assert.equal(calls().length,0);
    h.eval('go("photos")');await h.settle();const roomObserver=current(),roomTiles=tiles();
    h.eval('selectRoom(roster[1].base)');await h.settle();roomObserver.emit(roomTiles);await h.settle();
    assert.equal(calls().length,0);await emit(tiles().slice(0,2));assert.equal(calls().length,2);
    assert.ok(calls().every(c=>new URL(c.url).port==='8878'));
  } finally {h.hide()}
});
test('visibility resume rearms tiles before a failed metadata refresh',async()=>{
  const {h,calls,tiles,current,emit,failMetadata}=await setup();
  try {
    assert.equal(calls().length,0);await emit(tiles().slice(0,2));const old=current(),oldTiles=tiles();
    h.hide();old.emit(oldTiles);await h.settle();assert.equal(calls().length,2);
    failMetadata();h.show();
    assert.notEqual(current(),old,'Resume must rearm immediately, before metadata settles');
    const resumed=current(),resumedTiles=tiles();resumed.emit(resumedTiles.slice(0,2));await h.settle();
    assert.equal(calls().length,4);
    assert.ok(oldTiles.every(t=>!t.isConnected));
    assert.equal(resumed.disconnected,true,'Failed metadata rendering also retires old callbacks');
  } finally {h.hide()}
});
