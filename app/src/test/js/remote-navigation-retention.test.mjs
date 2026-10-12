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
  {base:'http://127.0.0.1:8877',name:'Fixture',token:'fixture-a'},
  {base:'http://127.0.0.1:8878',name:'Other',token:'fixture-b'},
];
const nav = h => h.document.getElementById('navigation');
const header = h => [h.document.getElementById('room-button'),h.document.getElementById('connection')];

test('unchanged renders retain navigation and header children without adding listeners', async t => {
  const h=await browserHarness({saved});
  try {
    const buttons=[...nav(h).children], children=header(h).map(n=>[...n.children]);
    let elements=0,listeners=0;
    const create=h.document.createElement.bind(h.document);
    h.document.createElement=tag=>{elements++;const n=create(tag),listen=n.addEventListener.bind(n);n.addEventListener=(...args)=>{listeners++;return listen(...args);};return n;};
    for(let i=0;i<5;i++)h.eval('updateHeader();render()');
    assert.equal(nav(h).children.every((n,i)=>n===buttons[i]&&n.isConnected),true);
    assert.equal(header(h).every((n,i)=>n.children.every((c,j)=>c===children[i][j])),true);
    t.diagnostic(JSON.stringify({renders:5,navigationButtons:buttons.length,elements,listeners}));
  } finally {h.hide();}
});

test('delayed inventory keeps navigation connected through asynchronous rendering', async () => {
  let resolveInventory,hold=false;
  const h=await browserHarness({saved,routes:{'/remote/apps':({response})=>hold?new Promise(resolve=>{resolveInventory=()=>resolve(response({ok:true,apps:[{packageName:'com.fixture.app',label:'Fixture app',installed:true}]}));}):response({ok:true,apps:[]})}});
  try {
    h.eval('go("apps")');await h.settle();
    const buttons=[...nav(h).children];buttons[0].focus();hold=true;
    h.eval('loadApps()');await h.settle();assert.equal(typeof resolveInventory,'function');
    resolveInventory();await h.settle();
    assert.equal(buttons.every((n,i)=>n===nav(h).children[i]&&n.isConnected),true);
    assert.match(h.document.getElementById('page').textContent,/Fixture app/);
  } finally {h.hide();}
});

test('retained buttons select every tab with exactly one aria-current and one callback', async () => {
  const h=await browserHarness({saved});
  try {
    const buttons=[...nav(h).children],names=['home','remote','photos','apps','settings'];
    for(let i=0;i<names.length;i++){
      buttons[i].click();await h.settle();
      assert.equal(h.eval('tab'),names[i]);
      assert.equal(nav(h).children.every((n,j)=>n===buttons[j]),true);
      assert.equal(buttons.filter(n=>n.getAttribute('aria-current')==='page').length,1);
      assert.equal(buttons[i].getAttribute('aria-current'),'page');
      assert.equal(buttons.every(n=>(n.listeners.click||[]).length===1),true);
    }
  } finally {h.hide();}
});

test('retained navigation uses the current room and obeys expired and unpaired states', async () => {
  const h=await browserHarness({saved});
  try {
    const buttons=[...nav(h).children];
    h.eval('selectRoom("http://127.0.0.1:8878")');await h.settle();
    const start=h.calls.length;buttons[3].click();await h.settle();
    assert.equal(h.calls.slice(start).filter(c=>c.path==='/remote/apps').every(c=>c.url.startsWith(saved[1].base+'/')),true);
    assert.equal(h.calls.slice(start).filter(c=>c.path==='/remote/apps').length,1);
    h.eval('connectionState="expired";connected=false;render()');
    const expiredStart=h.calls.length;buttons[4].click();await h.settle();
    assert.equal(h.calls.length,expiredStart);assert.match(h.document.getElementById('page').textContent,/Pair again/);
    h.eval('selectRoom("missing")');await h.settle();
    assert.equal(h.document.getElementById('refresh-button').disabled,true);
    assert.match(h.document.getElementById('room-button').textContent,/Choose a Portal/);
    assert.equal(nav(h).children.every((n,i)=>n===buttons[i]),true);
  } finally {h.hide();}
});

test('header updates actual identity/status changes and preserves an unchanged retry control', async () => {
  const h=await browserHarness({saved});
  try {
    h.eval('session.room=Object.freeze({...session.room,name:"Renamed"});data.companion={model:"New model"};updateHeader()');
    assert.match(header(h)[0].textContent,/Renamed/);assert.match(header(h)[1].textContent,/New model/);
    h.eval('connected=false;connectionState="offline";updateHeader()');
    const retry=header(h)[1].querySelector('button');assert.equal(retry.textContent,'Retry');
    h.eval('updateHeader();render()');assert.equal(header(h)[1].querySelector('button')===retry,true);
    h.eval('connectionState="expired";updateHeader()');assert.equal(header(h)[1].querySelector('button').textContent,'Pair again');
    h.eval('connectionState="connected";connected=true;updateHeader()');assert.equal(header(h)[1].querySelector('button'),null);
    assert.match(header(h)[1].textContent,/Connected/);
  } finally {h.hide();}
});
