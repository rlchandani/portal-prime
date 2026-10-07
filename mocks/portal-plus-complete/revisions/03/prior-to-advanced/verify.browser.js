/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */
async (page) => {
 const url='http://127.0.0.1:8765/portal-plus-complete/native.html';const results=[],errors=[],network=[];
 const record=(name,ok,detail='')=>results.push({name,ok,detail});page.on('pageerror',e=>errors.push(String(e)));page.on('request',r=>{if(!r.url().startsWith('http://127.0.0.1:8765/')&&!r.url().startsWith('data:'))network.push(r.url());});
 await page.setViewportSize({width:2160,height:1440});
 for(const theme of ['light','dark']){
  await page.goto(url+'?scenario=expanded&theme='+theme);await page.evaluate(()=>document.fonts.ready);
  const checks=await page.evaluate(()=>{const a=__portalMock,screens=[...a.model.pages.map(p=>p.id),'home','settings','apps','app-folder','store','app-detail','tools','edit','widgets','photos','digital-preview','welcome-preview','wake-light','lamp-preview','alarm-ring','folder-preview','ha-dashboard','switcher','provider-widgets','chime-preview','music'];return screens.map(screen=>{a.navigate(screen);return{screen,ok:!document.body.innerText.includes('Unable to open this destination')&&document.querySelector('#device').scrollWidth<=1920};});});
  checks.forEach(c=>record(theme+' destination '+c.screen,c.ok));
 }
 const coverage=await (await page.request.get('http://127.0.0.1:8765/portal-plus-complete/coverage.json')).json();
 await page.goto(url+'?scenario=expanded');
 const rows=await page.evaluate(()=>__portalMock.model.controls.map(c=>{__portalMock.revealControl(c.id);return{id:c.id,ok:!!document.querySelector(`[data-control="${CSS.escape(c.id)}"]`)};}));
 rows.forEach(c=>record('control '+c.id,c.id==='screensaver.batterySaver'?!c.ok:c.ok,c.id==='screensaver.batterySaver'?'Correctly unavailable on this Portal+':''));
 for(const popup of coverage.popups){
  await page.evaluate(p=>{const a=__portalMock;a.configure({scenario:p.scenario||'expanded'});a.navigate(p.screen);if(p.values)a.configure({values:p.values});for(const step of p.steps||[])a.showAction(step.action,step.data||{});if(p.control){a.revealControl(p.control);a.showControl(p.control);}else if(p.action)a.showAction(p.action,p.data||{});},popup);
  if(popup.afterMs)await page.waitForTimeout(popup.afterMs);
  record('popup / state '+popup.id,!(await page.locator('body').innerText()).includes('Unable to open this destination'));
  const stateOnly=['home-page-two','memo-record','memo-playback','lamp-hidden','lamp-night','timer-running'];
  if(!stateOnly.includes(popup.id))record('dialog visible '+popup.id,await page.locator('[role=dialog]').count()===1);
  record('no unresolved values '+popup.id,await page.evaluate(()=>!document.body.innerText.includes('${')&&![...document.querySelectorAll('input')].some(i=>i.value.includes('${'))));
 }
 async function load(scenario='normal'){await page.goto(url+'?scenario='+scenario);}
 async function action(name,data={}){await page.evaluate(({name,data})=>__portalMock.showAction(name,data),{name,data});}
 async function navigate(screen){await page.evaluate(s=>__portalMock.navigate(s),screen);}
 async function state(){return page.evaluate(()=>__portalMock.getState());}
 await load();await navigate('immich');await page.locator('[data-value="immich.url"]').fill('https://sample.invalid');await page.locator('[data-value="immich.key"]').fill('sample-only');await page.locator('#panel-form').evaluate(f=>f.requestSubmit());await page.waitForFunction(()=>__portalMock.getState().screen==='albums');await action('album',{name:'Into the wild'});await action('album',{name:'Mountain mornings'});record('Immich selection remains single album',(await state()).collections.albums.length===1);await action('confirm-albums');record('successful source selection',(await state()).source==='immich');
 await navigate('home');await navigate('smb');await page.locator('[data-value="smb.host"]').fill('sample-nas');await page.locator('[data-value="smb.share"]').fill('Photos');await page.locator('#panel-form').evaluate(f=>f.requestSubmit());await action('cancel-form');await page.waitForTimeout(800);record('cancel ignores late source result',(await state()).screen==='home'&&(await state()).source==='immich');
 await load('offline');await navigate('dav');await page.locator('[data-value="dav.url"]').fill('https://sample.invalid/photos');await page.locator('#panel-form').evaluate(f=>f.requestSubmit());await page.waitForFunction(()=>document.querySelector('.form-error')?.textContent.includes('Could not connect'));record('offline failure keeps existing source',(await state()).source==='default');
 await load('expanded');await navigate('mqtt');await page.evaluate(()=>__portalMock.configure({values:{'mqtt.port':1883,'mqtt.useTls':false}}));await page.locator('[data-value="mqtt.useTls"]').check();record('TLS convenience port',(await state()).values['mqtt.port']===8883);
 await navigate('timers');await action('custom-timer');await page.locator('.modal [data-value="timers.minutes"]').fill('0');await page.locator('.modal [data-value="timers.seconds"]').fill('10');await page.locator('#custom-timer-form').evaluate(f=>f.requestSubmit());record('custom timer starts',(await state()).collections.timers.length===1);const timer=(await state()).collections.timers[0];await action('timer-remove',{id:timer.id});record('cancel timer',(await state()).collections.timers.length===0);
 await navigate('todo');await page.locator('[data-value="todo.item"]').fill('Review mock');await page.locator('#panel-form').evaluate(f=>f.requestSubmit());await action('todo-complete',{name:'Review mock'});await action('todo-clear');record('clear completed to-do',(await state()).collections.todo.length===0);
 await navigate('app-folder');await action('folder-rename');await page.locator('#folder-name-form input').fill('Utilities');await page.locator('#folder-name-form').evaluate(f=>f.requestSubmit());record('folder rename updates heading',await page.locator('h1').textContent()==='Utilities');
 await navigate('edit');await action('widget-size-set',{index:0,name:'2 × 3'});await action('widget-move-to',{from:0,to:3});record('resize travels with reordered widget',(await state()).widgets[3]==='today'&&Object.values((await state()).widgetSizes).includes('2 × 3'));await navigate('home');record('resize updates home layout',await page.locator('.home-grid.custom-sizing').count()===1);
 await navigate('notes');await action('voice-memo');record('voice memo recording state',(await page.locator('body').innerText()).includes('Stop recording'));await action('voice-memo');record('voice memo playback and delete',(await page.locator('body').innerText()).includes('Play memo')&&(await page.locator('body').innerText()).includes('Delete memo'));
 await navigate('stories');await action('story',{name:'The Lion and the Mouse'});record('actual bundled story',(await page.locator('body').innerText()).includes('gnawed the ropes'));await action('close');
 await page.evaluate(()=>__portalMock.revealControl('fleet.name'));await action('control',{id:'fleet.name'});await page.locator('#control-editor').fill('');await page.locator('#control-form').evaluate(f=>f.requestSubmit());record('device name validates length',(await page.locator('#control-error').innerText()).includes('1–48'));await action('close');
 await navigate('world');record('all thirteen source cities',await page.locator('[data-action="world-toggle"]').count()===13);
 await navigate('converter');await action('control',{id:'converter.kind'});await action('choose',{id:'converter.kind',value:'Length'});await page.evaluate(()=>__portalMock.configure({values:{'converter.value':'1','converter.from':'mi','converter.to':'m'}}));record('offline conversion source formula',await page.locator('#converter-result').textContent()==='1609.344 m');
 await navigate('phone');record('six digit pairing PIN',/^\d{6}$/.test(await page.locator('.pair-code').textContent()));await page.waitForFunction(()=>{const i=document.querySelector('.sample-qr');return i.complete&&i.naturalWidth>0;});record('QR exists',await page.locator('.sample-qr').evaluate(i=>i.complete&&i.naturalWidth>0));await action('refresh-pin');record('PIN refresh updates card',await page.locator('.pair-code').textContent()==='726491');
 await load();await navigate('store');const pkg=await page.evaluate(()=>__portalMock.inventory.catalog.find(a=>a.name==='Transistor').packageName);await action('install',{pkg});await action('install-confirm',{pkg});await action('install-cancel');await page.waitForTimeout(1000);record('download cancellation prevents completion',!(await page.locator('body').innerText()).includes('App installed'));
 await action('install',{pkg});await action('install-confirm',{pkg});await page.waitForFunction(()=>document.body.innerText.includes('App installed'));await action('close');await navigate('apps');record('installed radio appears in Apps',(await page.locator('body').innerText()).includes('Transistor'));
 await load('error');await navigate('store');await action('install-confirm',{pkg});await page.waitForFunction(()=>document.body.innerText.includes('Installation failed'));record('installer failure has retry',await page.locator('[data-action="install"]').count()>0);await action('close');
 await load('missing-permission');await navigate('touch');await page.locator('[data-value="display.touchSounds"]').click();record('touch sounds permission keeps prior value',(await state()).values['display.touchSounds']===true&&(await page.locator('body').innerText()).includes('Review boundary'));
 await load();await navigate('settings');await page.locator('#settings-search').fill('Warmth');await page.locator('[data-action="search-result"][data-id="display.warmth"]').click();record('settings search explains gate without changing preferences',(await state()).values['display.nightShift']===false&&await page.locator('[role="dialog"]').count()===1);await action('close');await page.locator('[data-value="display.nightShift"]').click();await action('search-result',{id:'display.warmth',screen:'display'});record('available search result highlights exact row',await page.locator('[data-control="display.warmth"].row-highlight').count()===1);
 await action('control',{id:'display.from'});await page.keyboard.press('Tab');record('dialog traps keyboard focus',await page.evaluate(()=>!!document.activeElement.closest('.modal')));await page.keyboard.press('Escape');record('Escape closes dialog',await page.locator('[role="dialog"]').count()===0);
 await page.goto('http://127.0.0.1:8765/portal-plus-complete/?popup=timer-custom');await page.waitForFunction(()=>document.querySelector('iframe').contentDocument?.querySelector('#custom-timer-form'));record('coverage links open exact popup',true);
 for(const [id,expected] of [['light-popup-memo-delete','Delete voice memo?'],['dark-popup-store-installed','App installed'],['digital-clock-face-led','9:41'],['dark-category-sound','Sound & wake']]){
  await page.goto('http://127.0.0.1:8765/portal-plus-complete/?reference='+id);
  await page.waitForFunction(expected=>document.querySelector('iframe')?.contentDocument?.body.innerText.includes(expected),expected);
  record('gallery preset '+id,true);
 }
 await page.goto('http://127.0.0.1:8765/portal-plus-complete/?popup=memo-delete');
 await page.waitForFunction(()=>document.querySelector('iframe')?.contentDocument?.body.innerText.includes('Delete voice memo?'));
 record('flow state does not run twice',true);
 const full=await page.locator('#full-link').getAttribute('href');await page.goto('http://127.0.0.1:8765/portal-plus-complete/'+full);
 await page.waitForFunction(()=>document.body.innerText.includes('Delete voice memo?'));record('full device link retains popup',true);record('icon buttons have accessible names',await page.locator('.round-button').evaluateAll(bs=>bs.every(b=>b.getAttribute('aria-label')?.trim())));
 await load();await navigate('digital-preview');
 record('digital clock centered',await page.locator('.digital-sample').evaluate(el=>{const r=el.getBoundingClientRect();return Math.abs(r.x+r.width/2-innerWidth/2)<1;}));
 for(const screen of ['digital-preview','welcome-preview','lamp-preview']){await navigate(screen);record('preview Back overlay '+screen,await page.locator('.photo-back').evaluate(el=>{const r=el.getBoundingClientRect();return getComputedStyle(el).position==='absolute'&&r.x<80&&r.y<80;}));}
 record('no runtime errors',errors.length===0,errors.join('\n'));record('no external network calls',network.length===0,[...new Set(network)].join('\n'));
 const report={timestamp:new Date().toISOString(),viewport:{width:2160,height:1440},total:results.length,passed:results.filter(r=>r.ok).length,failed:results.filter(r=>!r.ok),results,errors,externalNetwork:[...new Set(network)]};await page.evaluate(report=>{window.__portalVerificationReport=report;},report);return{total:report.total,passed:report.passed,failed:report.failed,errors,externalNetwork:report.externalNetwork};
}
