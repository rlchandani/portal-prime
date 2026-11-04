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
  {base:'http://127.0.0.1:8877',token:'generated-a',name:'Family'},
  {base:'http://127.0.0.1:8878',token:'generated-b',name:'Living'},
];
const start = h => h.document.body.querySelectorAll('button').find(b=>/^Import \d+ photo/.test(b.textContent)).click();
const files = () => Array.from({length:10}, (_,i) => Object.assign(new Blob(['abc'], {type:'image/png'}), {name:`photo-${i}.png`}));

test('group picker sends each chosen Portal to its own folder and keeps a bounded result page', async () => {
  const h = await browserHarness({saved,routes:{
    '/remote/companion':({url,response})=>response({ok:true,deviceId:new URL(url).port==='8877'?'device-a':'device-b',capabilities:{photos:true,photoLibrary:true}}),
    '/remote/photo-upload':({url,opts,response})=>{const q=new URL(url).searchParams;return response({ok:true,path:`${q.get('path')}/${q.get('name')}`,bytes:opts.body.size});},
  }});
  h.eval('go("photos")'); await h.settle(); h.click('Import photos');
  assert.match(h.document.getElementById('sheet-content').textContent, /Send to/);
  h.click('Select both Portals');
  await h.input('Family destination folder', '/sdcard/Pictures/Family');
  await h.input('Living destination folder', '/sdcard/Pictures/Living');
  const picker = h.document.body.querySelectorAll('input').find(n=>n.attrs['aria-label']==='Choose photos');
  picker.files = files(); picker.onchange?.({target:picker});
  start(h); await h.settle();
  const posts = h.calls.filter(c=>c.path==='/remote/photo-upload');
  assert.equal(posts.length,20);
  assert.equal(posts.filter(c=>new URL(c.url).port==='8877' && new URL(c.url).searchParams.get('path')==='/sdcard/Pictures/Family').length,10);
  assert.equal(posts.filter(c=>new URL(c.url).port==='8878' && new URL(c.url).searchParams.get('path')==='/sdcard/Pictures/Living').length,10);
  assert.equal(h.eval('upload.completed'),20);
  assert.ok(h.document.getElementById('upload-status').textContent.includes('Family'));
  assert.ok(h.document.getElementById('upload-status').textContent.includes('Living'));
  assert.equal(h.document.getElementById('upload-status').querySelectorAll('[data-upload-result]').length,8);
  h.click('Next results');
  assert.equal(h.document.getElementById('upload-status').querySelectorAll('[data-upload-result]').length,8);
});

async function prepare(h,name='pending.png') {
  h.click('Import photos');
  const picker = h.document.body.querySelectorAll('input').find(n=>n.attrs['aria-label']==='Choose photos');
  picker.files = [Object.assign(new Blob(['abc'],{type:'image/png'}),{name})];
  picker.onchange?.({target:picker});
}
test('closing a pending import sheet cannot later replace a newer results ledger', async () => {
  let hold=false, release;
  const h=await browserHarness({saved,routes:{
    '/remote/companion':({response})=> hold ? new Promise(resolve=>{release=()=>resolve(response({ok:true,deviceId:'device-a',capabilities:{photos:true}}));})
      : response({ok:true,deviceId:'device-a',capabilities:{photos:true}}),
    '/remote/photo-upload':({response})=>response({ok:false,error:'file_exists'},409),
  }});
  h.eval('go("photos")');await h.settle();await prepare(h,'old.png');
  hold=true;start(h);await h.settle();
  h.eval('closeSheet()'); hold=false;
  await prepare(h,'new.png');start(h);await h.settle();
  assert.equal(h.eval('upload.items[0].name'),'new.png');
  release();await h.settle();
  assert.equal(h.eval('upload.items[0].name'),'new.png');
  assert.equal(h.calls.filter(c=>c.path==='/remote/photo-upload').length,1);
});
test('the folder captured by Start cannot change during its identity preflight', async () => {
  let hold=false,release;
  const h=await browserHarness({saved,routes:{
    '/remote/companion':({response})=> hold ? new Promise(resolve=>{release=()=>resolve(response({ok:true,deviceId:'device-a',capabilities:{photos:true}}));})
      : response({ok:true,deviceId:'device-a',capabilities:{photos:true}}),
    '/remote/photo-upload':({url,opts,response})=>{const q=new URL(url).searchParams;return response({ok:true,path:`${q.get('path')}/${q.get('name')}`,bytes:opts.body.size});},
  }});
  h.eval('go("photos")');await h.settle();await prepare(h);
  await h.input('Destination folder','/sdcard/BeforeStart');
  hold=true;start(h);await h.settle();
  await h.input('Destination folder','/sdcard/AfterStart');
  hold=false;release();await h.settle();
  const posts=h.calls.filter(c=>c.path==='/remote/photo-upload');
  assert.equal(posts.length,1);
  assert.equal(new URL(posts[0].url).searchParams.get('path'),'/sdcard/BeforeStart');
});

test('pagehide retires a pending sheet before it can post photos',async()=>{
  let hold=false,release;
  const h=await browserHarness({saved,routes:{
    '/remote/companion':({response})=> hold ? new Promise(resolve=>{release=()=>resolve(response({ok:true,deviceId:'device-a',capabilities:{photos:true}}));})
      : response({ok:true,deviceId:'device-a',capabilities:{photos:true}}),
  }});
  h.eval('go("photos")');await h.settle();await prepare(h);
  hold=true;start(h);await h.settle();
  h.pagehide();hold=false;release();await h.settle();
  assert.equal(h.calls.filter(c=>c.path==='/remote/photo-upload').length,0);
  assert.equal(h.eval('upload'),null);
});

test('replacement of a peer pairing retires its lane without disturbing the viewed Portal',async()=>{
  let release,peerSignal;
  const h=await browserHarness({saved,routes:{
    '/remote/companion':({url,response})=>response({ok:true,deviceId:new URL(url).port==='8877'?'device-a':'device-b',capabilities:{photos:true,photoLibrary:true}}),
    '/remote/photo-upload':({url,opts,response})=>{
      const q=new URL(url).searchParams,reply=()=>response({ok:true,path:`${q.get('path')}/${q.get('name')}`,bytes:opts.body.size});
      if(new URL(url).port==='8878'){peerSignal=opts.signal;return new Promise(resolve=>release=()=>resolve(reply()));}
      return reply();
    },
  }});
  h.eval('go("photos")');await h.settle();h.click('Import photos');h.click('Select both Portals');
  const picker=h.document.body.querySelectorAll('input').find(n=>n.attrs['aria-label']==='Choose photos');
  picker.files=['first.png','second.png'].map(name=>Object.assign(new Blob(['abc'],{type:'image/png'}),{name}));
  picker.onchange({target:picker});start(h);await h.settle();
  const replacement=saved.map((r,i)=>i===1?{...r,token:'replacement-session'}:r);
  h.storage({key:h.eval('STORAGE.roster'),newValue:JSON.stringify(replacement)});
  await h.settle();
  assert.equal(peerSignal.aborted,true);
  release();await h.settle();
  assert.equal(h.calls.filter(c=>c.path==='/remote/photo-upload' && new URL(c.url).port==='8878').length,1);
  assert.deepEqual(Array.from(h.eval('upload.items.filter(i=>i.room.base.includes("8877")).map(i=>i.state)')),['confirmed','confirmed']);
});

test('a pairing removed during sheet preflight cannot revive when the same roster returns',async()=>{
  let hold=false,release,heldSignal;
  const h=await browserHarness({saved,routes:{
    '/remote/companion':({url,opts,response})=>{
      const reply=()=>response({ok:true,deviceId:new URL(url).port==='8877'?'device-a':'device-b',capabilities:{photos:true,photoLibrary:true}});
      if(hold && new URL(url).port==='8878'){heldSignal=opts.signal;return new Promise(resolve=>release=()=>resolve(reply()));}
      return reply();
    },
  }});
  h.eval('go("photos")');await h.settle();await prepare(h);h.click('Select both Portals');
  hold=true;start(h);await h.settle();
  h.storage({key:h.eval('STORAGE.roster'),newValue:JSON.stringify([saved[0]])});
  h.storage({key:h.eval('STORAGE.roster'),newValue:JSON.stringify(saved)});
  hold=false;release();await h.settle();
  assert.equal(heldSignal.aborted,true);
  assert.equal(h.calls.filter(c=>c.path==='/remote/photo-upload').length,0);
  assert.equal(h.eval('upload'),null);
});
