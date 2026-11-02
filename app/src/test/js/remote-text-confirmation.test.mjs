/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */
import test from 'node:test';
import assert from 'node:assert/strict';
import { browserHarness } from './remote-browser-harness.mjs';
const saved = [{base:'http://127.0.0.1:8877',name:'Fixture',token:'fixture-only'},
  {base:'http://127.0.0.1:8878',name:'Other',token:'fixture-other'}];
const draft = 'Keep exactly — café 🏠\nSecond line';
const warning = 'Text sending is unconfirmed. Review the Portal before sending again.';
for (const [name, reply] of [
  ['missing applied', ({response})=>response({ok:true})],
  ['string applied', ({response})=>response({ok:true,applied:'true'})],
  ['numeric applied', ({response})=>response({ok:true,applied:1})],
  ['missing ok', ({response})=>response({applied:true})],
  ['string ok', ({response})=>response({ok:'true',applied:true})],
  ['malformed JSON', ()=>new Response('{', {status:200})],
  ['transport uncertainty', ()=>{throw new Error('synthetic disconnect');}],
]) test(`text ${name} retains draft and reports uncertainty without replay`, async()=>{
  const h=await browserHarness({saved,routes:{'/remote/text':reply}});
  try {
    h.eval('go("remote")'); await h.settle();
    h.eval(`setDraft("keyboard",${JSON.stringify(draft)});render()`);
    h.click('Send text'); await h.settle();
    assert.equal(h.document.getElementById('notice').textContent,warning);
    assert.equal(h.eval('draft("keyboard")'),draft);
    const posts=()=>h.calls.filter(c=>c.path==='/remote/text');
    assert.equal(posts().length,1);
    assert.deepEqual(JSON.parse(posts()[0].opts.body),{mode:'set',text:draft});
    h.eval('render();refresh()');await h.settle();
    assert.equal(posts().length,1);assert.equal(h.eval('draft("keyboard")'),draft);
  } finally {h.hide();}
});
test('confirmed text and explicit rejection preserve existing outcomes and command payloads',async()=>{
  let applied=true;
  const h=await browserHarness({saved,routes:{'/remote/text':({response})=>response({ok:true,applied})}});
  try {
    h.eval('go("remote")');await h.settle();h.eval(`setDraft("keyboard",${JSON.stringify(draft)})`);
    for(const mode of ['set','append','backspace','clear']){
      await h.eval(`sendText(${JSON.stringify(mode)})`);
      assert.equal(h.document.getElementById('notice').textContent,'Text command sent to the focused field.');
      assert.deepEqual(JSON.parse(h.calls.filter(c=>c.path==='/remote/text').at(-1).opts.body),{mode,text:['set','append'].includes(mode)?draft:''});
    }
    applied=false;await h.eval('sendText("append")');
    assert.equal(h.document.getElementById('notice').dataset.error,'true');
    assert.notEqual(h.document.getElementById('notice').textContent,warning);
    assert.equal(h.eval('draft("keyboard")'),draft);assert.equal(h.calls.filter(c=>c.path==='/remote/text').length,5);
  }finally{h.hide();}
});
test('late text result cannot report into a newly selected room',async()=>{
  let resolve;
  const h=await browserHarness({saved,routes:{'/remote/text':({response})=>new Promise(r=>{resolve=()=>r(response({ok:true}));})}});
  try{
    h.eval('go("remote")');await h.settle();h.eval('setDraft("keyboard","first-room")');
    h.click('Send text');await h.settle();h.eval('selectRoom("http://127.0.0.1:8878")');await h.settle();
    const notice=h.document.getElementById('notice').textContent;resolve();await h.settle();
    assert.equal(h.document.getElementById('notice').textContent,notice);
    assert.equal(h.eval('draft("keyboard")'),'');
    h.eval('selectRoom("http://127.0.0.1:8877")');await h.settle();assert.equal(h.eval('draft("keyboard")'),'first-room');
    assert.equal(h.calls.filter(c=>c.path==='/remote/text').length,1);
  }finally{h.hide();}
});

for(const [name,reply,expected] of [
  ['authorization',({response})=>response({ok:false,error:'unauthorized'},401),'Pairing expired'],
  ['accessibility',({response})=>response({ok:false,error:'no_accessibility'},503),'accessibility service'],
])test(`explicit ${name} rejection is preserved`,async()=>{
  const h=await browserHarness({saved,routes:{'/remote/text':reply}});
  try{h.eval('go("remote")');await h.settle();h.eval(`setDraft("keyboard",${JSON.stringify(draft)})`);
    await h.eval('sendText("set")');assert.match(h.document.getElementById('notice').textContent,new RegExp(expected));
    assert.equal(h.calls.filter(c=>c.path==='/remote/text').length,1);
    assert.equal(h.eval('draft("keyboard")'),draft);
  }finally{h.hide();}
});
test('a timed-out text request remains uncertain and cannot resend itself',{timeout:18000},async()=>{
  const h=await browserHarness({saved,routes:{'/remote/text':({opts})=>new Promise((_,reject)=>opts.signal.addEventListener('abort',()=>reject(new Error('synthetic abort'))))}});
  try{h.eval('go("remote")');await h.settle();h.eval(`setDraft("keyboard",${JSON.stringify(draft)})`);
    // core.mjs is an imported module: its transport deadline uses real timers.
    await h.eval('sendText("set")');
    assert.equal(h.document.getElementById('notice').textContent,warning);
    assert.equal(h.eval('draft("keyboard")'),draft);
    h.eval('render()');await h.settle();assert.equal(h.calls.filter(c=>c.path==='/remote/text').length,1);
  }finally{h.hide();}
});
