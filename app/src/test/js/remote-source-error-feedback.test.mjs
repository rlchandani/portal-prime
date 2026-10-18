/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */
import test from 'node:test';
import assert from 'node:assert/strict';
import { browserHarness } from './remote-browser-harness.mjs';
const saved=[{base:'http://127.0.0.1:8877',name:'Fixture',token:'public-fixture'},
  {base:'http://127.0.0.1:8878',name:'Other',token:'public-peer'}];
const cases=[
 ['immich','invalid_immich_endpoint','Server address',/complete http:\/\/ or https:\/\/.*Immich/i],
 ['immich','immichKey_required','API key',/Enter.*Immich API key/i],
 ['smb','smb_endpoint_required','Server',/NAS address and share name/i],
 ['smb','smbPass_required','Password',/password.*NAS account/i],
 ['dav','invalid_dav_endpoint','Folder address',/complete http:\/\/ or https:\/\/.*WebDAV/i],
 ['dav','davPass_required','Password',/password.*WebDAV account/i],
];
const error=h=>{const node=h.document.getElementById('page').querySelector('[data-source-error]');assert.ok(node,'Inline source correction is missing');return node;};
const input=(h,label)=>h.document.body.querySelectorAll('input').find(n=>n.getAttribute('aria-label')===label);
const posts=h=>h.calls.filter(c=>c.path==='/remote/sources'&&c.opts.method==='POST');
async function setup(kind='dav',code='invalid_dav_endpoint',{held=false,wrong=false}={}){
 let fail=true,release;let source={source:kind,immichUrl:'https://saved.invalid',immichKey:'saved-private-fixture',
  smbHost:'saved-nas',smbShare:'Photos',smbUser:'reader',smbPass:'saved-private-fixture',
  davUrl:'https://saved.invalid/photos/',davUser:'reader',davPass:'saved-private-fixture'};
 const h=await browserHarness({saved,routes:{'/remote/sources':({opts,response})=>{
  if(opts.method!=='POST')return response({ok:true,sources:source,calendar:{url:''}});
  const send=()=>{if(fail)return response({ok:false,error:code},400);
   source={...source,...JSON.parse(opts.body),source:wrong?'default':kind};return response({ok:true,sources:source});};
  return held?new Promise(resolve=>release=()=>resolve(send())):send();
 }}});
 h.eval('advanced=true;go("photos","sources")');await h.settle();
 return {h,succeed:()=>fail=false,release:()=>release()};
}
async function submit(h,kind='dav'){await h.eval(`saveSource(${JSON.stringify(kind)},data.sources)`);await h.settle();}
for(const [kind,code,label,expected] of cases)test(`${code} stays beside the source form with a correction and affected field`,async()=>{
 const {h}=await setup(kind,code);await submit(h,kind);
 assert.ok(error(h));assert.equal(error(h).hidden,false);assert.match(error(h).textContent,expected);
 assert.equal(error(h).getAttribute('role'),'alert');assert.equal(input(h,label).getAttribute('aria-invalid'),'true');
 assert.equal(input(h,label).getAttribute('aria-describedby'),error(h).getAttribute('id'));
 assert.equal(h.document.getElementById('notice').textContent,'');
 assert.equal(posts(h).length,1);assert.equal(h.eval('sourceSaving()'),false);
 assert.equal(h.document.body.textContent.includes('saved-private-fixture'),false);
});
test('editing clears source feedback without replacing the focused input or losing exact draft',async()=>{
 const {h}=await setup();await submit(h);const field=input(h,'Folder address');field.focus();
 await h.input('Folder address','  https://new.invalid/photos/  ');
 assert.equal(input(h,'Folder address'),field);assert.equal(h.document.activeElement,field);
 assert.equal(error(h).hidden,true);assert.equal(error(h).textContent,'');assert.equal(field.getAttribute('aria-invalid'),undefined);
 assert.equal(field.getAttribute('aria-describedby'),undefined);assert.equal(h.eval('draft("source:davUrl")'),'  https://new.invalid/photos/  ');
});
test('changing source provider clears prior source error',async()=>{
 const {h}=await setup();await submit(h);const picker=h.document.body.querySelectorAll('select').find(n=>n.getAttribute('aria-label')==='Photo source');
 picker.value='smb';for(const fn of picker.listeners.change||[])fn({target:picker});await h.settle();
 assert.equal(error(h).hidden,true);assert.equal(h.eval('data.sourceSaveError'),undefined);
});
test('local invalid folder also has persistent feedback and sends no write',async()=>{
 const {h}=await setup('folder','invalid_path');await h.input('Folder on Portal','/sdcard/../private');await submit(h,'folder');
 assert.equal(posts(h).length,0);assert.equal(error(h).hidden,false);assert.match(error(h).textContent,/public folder/i);
 assert.equal(input(h,'Folder on Portal').getAttribute('aria-invalid'),'true');
});
test('a late failure cannot label newer edits as invalid',async()=>{
 const {h,release}=await setup('dav','invalid_dav_endpoint',{held:true});const pending=h.eval('saveSource("dav",data.sources)');await h.settle();
 await h.input('Folder address','https://newer.invalid/');release();await pending;await h.settle();
 assert.equal(error(h).hidden,true);assert.equal(h.eval('draft("source:davUrl")'),'https://newer.invalid/');
 assert.equal(h.document.getElementById('notice').textContent,'');
});
test('a late failure cannot label a different provider',async()=>{
 const {h,release}=await setup('dav','invalid_dav_endpoint',{held:true});const pending=h.eval('saveSource("dav",data.sources)');await h.settle();
 h.eval('setDraft("source:selected","smb");render()');release();await pending;await h.settle();assert.equal(error(h).hidden,true);
});
test('a late failure cannot label a different room',async()=>{
 const {h,release}=await setup('dav','invalid_dav_endpoint',{held:true});const pending=h.eval('saveSource("dav",data.sources)');await h.settle();
 h.eval(`selectRoom('${saved[1].base}')`);await h.settle();release();await pending;await h.settle();
 assert.equal(h.eval('data.sourceSaveError'),undefined);assert.equal(h.document.getElementById('notice').textContent,'');
});
test('refresh retains unsaved source draft and its correction',async()=>{
 const {h}=await setup();await h.input('Folder address','bad draft');await submit(h);await h.eval('loadSources()');await h.settle();
 assert.equal(error(h).hidden,false);assert.equal(h.eval('draft("source:davUrl")'),'bad draft');
});
test('successful retry clears old correction and confirms the chosen source',async()=>{
 const {h,succeed}=await setup();await submit(h);succeed();await submit(h);
 assert.equal(error(h).hidden,true);assert.equal(h.eval('data.sourceSaveError'),undefined);assert.equal(posts(h).length,2);
 assert.match(h.document.getElementById('notice').textContent,/saved and confirmed/i);
});
test('wrong active-source confirmation is persistent without erasing the draft',async()=>{
 const {h,succeed}=await setup('dav','invalid_dav_endpoint',{wrong:true});succeed();await h.input('Password',' public-draft-password ');await submit(h);
 assert.equal(error(h).hidden,false);assert.match(error(h).textContent,/not activated/i);
 assert.equal(h.eval('draft("source:davPass")'),' public-draft-password ');
});
test('unrelated mutations retain the existing global error notice',async()=>{
 const {h}=await setup();await h.eval('mutate("unrelated","/remote/sources",{davUrl:"https://"})');await h.settle();
 assert.match(h.document.getElementById('notice').textContent,/complete.*WebDAV/i);assert.equal(error(h).hidden,true);
});
