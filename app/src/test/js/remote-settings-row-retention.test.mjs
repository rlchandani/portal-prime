/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */
import test from 'node:test';
import assert from 'node:assert/strict';
import { browserHarness } from './remote-browser-harness.mjs';

const labels = ['P', 'Po', 'Por', 'Port', 'Portal'];
const control = i => ({ key: `flag${String(i).padStart(2, '0')}`, title: `Portal ${String(i).padStart(2, '0')}`,
  type: 'bool', value: false, default: false, tier: 'basic' });
const find = (h, label) => h.document.getElementById('page').querySelectorAll('input,select')
  .find(n => n.getAttribute('aria-label') === label);
const switches = h => h.document.getElementById('page').querySelectorAll('input.switch');
const posts = h => h.calls.filter(c => c.path === '/remote/settings' && c.opts.method === 'POST');
async function setup(extra = []) {
  const f = { controls: [...Array.from({ length: 24 }, (_, i) => control(i)), ...extra], hold: false,
    fail: false, omitDomain: false, jobs: [] };
  const domain = (peer = false) => ({ id: 'fixture', title: 'Fixture', controls: f.controls.map(c => ({ ...c,
    ...(peer && c.type === 'bool' ? { value: !c.value } : {}) })) });
  const h = await browserHarness({
    saved: [{ base: 'http://127.0.0.1:8877', name: 'Fixture', token: 'fixture-a' },
      { base: 'http://127.0.0.1:8878', name: 'Other', token: 'fixture-b' }],
    routes: { '/remote/settings': ({ url, opts, response }) => {
      if (opts.method !== 'POST') return response({ ok: true, settings: { domains: [domain(url.includes('8878'))] } });
      const patch=JSON.parse(opts.body).values;
      const confirm=()=> {
        if (f.fail) return response({ ok: false, error: 'request_failed' }, 500);
        f.controls=f.controls.map(c=>Object.hasOwn(patch,c.key)?{...c,value:patch[c.key]}:c);
        return response({ ok: true, applied: Object.keys(patch), ...(f.omitDomain?{}:{domain:domain()}) });
      };
      return f.hold?new Promise(resolve=>f.jobs.push(()=>resolve(confirm()))):confirm();
    } },
  });
  h.eval('go("settings")'); await h.settle();
  return { h, f };
}

test('five matching search keystrokes retain all 24 settings switches', async t => {
  const { h }=await setup();
  try {
    const before=switches(h), work={elements:0,listeners:0};
    const create=h.document.createElement.bind(h.document);
    h.document.createElement=tag=>{work.elements++;const node=create(tag),listen=node.addEventListener.bind(node);
      node.addEventListener=(...args)=>{work.listeners++;return listen(...args);};return node;};
    for (const value of labels) await h.input('Search settings',value);
    const retained=switches(h).filter((n,i)=>n===before[i]).length;
    t.diagnostic(JSON.stringify({workload:'settings-five-key-24-control',...work,retainedControls:retained}));
    assert.equal(retained,24);
  } finally {h.hide();}
});

test('identical fresh schema retains rows and one changed control replaces only its row', async () => {
  const {h,f}=await setup();
  try {
    const before=switches(h);h.eval('loadSettings()');await h.settle();
    assert.equal(switches(h).every((n,i)=>n===before[i]),true);
    f.controls=f.controls.map((c,i)=>i===0?{...c,value:true,readOnly:true,title:'Portal changed'}:c);
    h.eval('loadSettings()');await h.settle();
    const after=switches(h);
    assert.equal(after[0]===before[0],false);assert.equal(after[0].checked,true);assert.equal(after[0].disabled,true);
    assert.equal(after.slice(1).every((n,i)=>n===before[i+1]),true);
  } finally {h.hide();}
});

test('pending apply disables its row, ignores duplicate click, and failed apply restores value', async () => {
  const {h,f}=await setup();
  try {
    f.hold=true;f.fail=true;const first=switches(h)[0];first.click();await h.settle();
    const pending=switches(h)[0];assert.equal(pending.disabled,true);pending.click();await h.settle();
    assert.equal(posts(h).length,1);assert.equal(f.jobs.length,1);
    f.jobs[0]();await h.settle();
    assert.equal(switches(h)[0].disabled,false);assert.equal(switches(h)[0].checked,false);
  } finally {f.jobs.forEach(resolve=>resolve());await h.settle();h.hide();}
});

test('mixed-room and advanced presentation update while writes preserve current targets', async () => {
  const extra={key:'mode',title:'Portal mode',type:'enum',value:'basic',options:[{value:'basic',label:'Basic'},
    {value:'advanced',label:'Advanced',tier:'advanced'}]};
  const {h}=await setup([extra]);
  try {
    const before=switches(h)[0];h.eval('scope="all";loadScopeSchemas()');await h.settle();
    assert.equal(switches(h)[0]===before,false);assert.equal(switches(h)[0].indeterminate,true);
    const choice=find(h,'Portal mode');assert.equal(choice.children.length,1);
    h.eval('advanced=true;render()');assert.equal(find(h,'Portal mode').children.length,2);
    switches(h)[0].click();await h.settle();
    assert.equal(posts(h).length,2);assert.equal(posts(h).every(c=>JSON.parse(c.opts.body).values.flag00===true),true);
  } finally {h.hide();}
});

test('typed text survives retained searches and successful draft removal refreshes its input', async () => {
  const {h,f}=await setup([{key:'text',title:'Portal text',type:'string',value:'base'}]);
  try {
    await h.input('Portal text','edited');const input=find(h,'Portal text');
    await h.input('Search settings','Portal');assert.equal(find(h,'Portal text')===input,true);
    assert.equal(find(h,'Portal text').value,'edited');
    f.omitDomain=true;h.click('Save');await h.settle();
    assert.equal(find(h,'Portal text').value,'base');
    assert.equal(JSON.parse(posts(h)[0].opts.body).values.text,'edited');
    f.omitDomain=false;await h.input('Portal text','confirmed');h.click('Save');await h.settle();
    assert.equal(find(h,'Portal text').value,'confirmed');
  } finally {h.hide();}
});

test('narrowing keeps common rows, prunes hidden rows and resets only visible defaults', async () => {
  const {h}=await setup();
  try {
    const first=switches(h)[0];await h.input('Search settings','Portal 00');
    assert.equal(switches(h).length,1);assert.equal(switches(h)[0]===first,true);
    assert.equal(h.eval('settingsRowView.entries.size'),1);
    h.click('Reset');h.document.getElementById('sheet-actions').querySelectorAll('button').find(n=>n.textContent==='Reset').click();
    await h.settle();assert.equal(posts(h).length,1);assert.deepEqual(Object.keys(JSON.parse(posts(h)[0].opts.body).values),['flag00']);
    await h.input('Search settings','missing');assert.equal(h.eval('settingsRowView.entries.size'),0);
  } finally {h.hide();}
});

test('navigation and room selection discard rows and stale switches cannot write to another page or room', async () => {
  const {h}=await setup();
  try {
    const old=switches(h)[0];h.eval('go("home")');await h.settle();old.click();await h.settle();assert.equal(posts(h).length,0);
    assert.equal(h.eval('settingsRowView'),null);
    h.eval('go("settings")');await h.settle();const previous=switches(h)[0];
    h.eval('selectRoom("http://127.0.0.1:8878")');await h.settle();previous.click();await h.settle();
    assert.equal(posts(h).length,0);assert.equal(switches(h)[0]===previous,false);
    switches(h)[0].click();await h.settle();assert.equal(posts(h).length,1);assert.match(posts(h)[0].url,/8878/);
  } finally {h.hide();}
});

test('secret and oversized rows remain supported without retaining them', async () => {
  const {h}=await setup([{key:'secret',title:'Portal credential',type:'string',secret:true,hasValue:true},
    {key:'large',title:'Portal large',type:'info',value:'ok',help:'x'.repeat(17000)}]);
  try {
    await h.input('Portal credential','fixture-secret');const old=find(h,'Portal credential');
    await h.input('Search settings','Portal');assert.equal(find(h,'Portal credential')===old,false);
    assert.equal(find(h,'Portal credential').value,'fixture-secret');assert.equal(h.eval('settingsRowView.entries.size'),24);
  } finally {h.hide();}
});

test('hidden and expired pages release retention and ignore old control events', async () => {
  const {h}=await setup();
  try {
    const old=switches(h)[0];h.hide();old.click();await h.settle();assert.equal(posts(h).length,0);
    assert.equal(h.eval('settingsRowView'),null);h.show();await h.settle();const current=switches(h)[0];
    assert.equal(current===old,false);h.eval('connectionState="expired";render()');current.click();await h.settle();
    assert.equal(posts(h).length,0);assert.equal(h.eval('settingsRowView'),null);
  } finally {h.hide();}
});

test('late Save completion cannot clear the new room draft', async () => {
  const {h,f}=await setup([{key:'text',title:'Portal text',type:'string',value:'base'}]);
  try {
    f.hold=true;await h.input('Portal text','first-room');h.click('Save');await h.settle();
    assert.equal(f.jobs.length,1);h.eval('selectRoom("http://127.0.0.1:8878")');await h.settle();
    await h.input('Portal text','new-room-draft');f.jobs[0]();await h.settle();
    assert.equal(h.eval('draft("setting:fixture:text:draft")'),'new-room-draft');
    assert.equal(find(h,'Portal text').value,'new-room-draft');
  } finally {f.jobs.forEach(resolve=>resolve());await h.settle();h.hide();}
});

test('zero selected targets cannot retain an unapplied switch or enum value', async () => {
  const {h}=await setup([{key:'mode',title:'Portal mode',type:'enum',value:'basic',
    options:[{value:'basic',label:'Basic'},{value:'other',label:'Other'}]}]);
  try {
    h.eval('scope="selected";selectedTargets.clear();render()');
    switches(h)[0].click();await h.settle();assert.equal(posts(h).length,0);
    const select=find(h,'Portal mode');select.value='other';
    for(const handler of select.listeners.change||[])handler({target:select});await h.settle();
    await h.input('Search settings','Portal');
    assert.equal(switches(h)[0].checked,false);assert.equal(find(h,'Portal mode').value,'basic');
    assert.equal(posts(h).length,0);
  } finally {h.hide();}
});
