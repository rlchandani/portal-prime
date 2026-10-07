/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */
async (page) => {
 const base='http://127.0.0.1:8765/portal-plus-complete/',results=[],errors=[];
 page.on('pageerror',e=>errors.push(String(e)));
 await page.setViewportSize({width:2160,height:1440});
 const check=(name,ok,detail='')=>results.push({name,ok,detail});
 const load=async screen=>{await page.goto(base+'native.html?advanced=1&screen='+screen);};
 const config=async patch=>page.evaluate(p=>__portalMock.configure(p),patch);
 const action=async (name,data={})=>page.evaluate(({name,data})=>__portalMock.showAction(name,data),{name,data});
 const count=async action=>page.locator('[data-action="'+action+'"]').count();
 const row=async id=>page.locator('[data-control="'+id+'"]').count();
 // Expected rules copied from the Activity, independent of the mock's gate().
 await load('chime');
 for(let mask=0;mask<8;mask++){
  const hourly=!!(mask&1),spoken=!!(mask&2),golden=!!(mask&4);
  await config({values:{'chime.hourlyChimeOn':hourly,'chime.spokenTimeOn':spoken,'chime.goldenHourOn':golden}});
  for(const [name,wanted] of [['voice-test',spoken],['play-chime',hourly],['test-sunrise',golden],['test-sunset',golden],['play-ping',hourly||spoken||golden]])
   check('Chime mask '+mask+' '+name,(await count(name))===Number(wanted));
  check('Chime mask '+mask+' voice picker',(await row('chime.voice'))===Number(spoken));
  check('Chime mask '+mask+' sunrise variant',(await row('chime.sunriseVariant'))===Number(golden));
 }
 const cases=[
  ['sleep',{'screensaver.sleepTimerEnabled':false},['screensaver.sleepTimerMin'],['screensaver.pauseAudioOnSleep','screensaver.closeAppOnSleep']],
  ['sleep',{'screensaver.sleepTimerEnabled':true},[],['screensaver.sleepTimerMin','screensaver.pauseAudioOnSleep','screensaver.closeAppOnSleep']],
  ['screensaver',{'screensaver.enabled':false},['screensaver.fit','screensaver.intervalSec','screensaver.photoSource','screensaver.screensaverStyle','screensaver.clockFace','screensaver.dismissTarget'],[]],
  ['screensaver',{'screensaver.enabled':true,'screensaver.mode':'classic'},[],['screensaver.fit','screensaver.intervalSec']],
  ['screensaver',{'screensaver.enabled':true,'screensaver.mode':'shuffle'},['screensaver.fit'],['screensaver.intervalSec']],
  ['screensaver',{'screensaver.overnightEnabled':false,'screensaver.sleepTimerEnabled':false},['screensaver.overnightStartMin','screensaver.overnightEndMin','screensaver.overnightNightClock'],[]],
  ['screensaver',{'screensaver.overnightEnabled':true,'screensaver.sleepTimerEnabled':true},[],['screensaver.overnightStartMin','screensaver.overnightEndMin','screensaver.overnightNightClock']],
  ['calendar',{'calendar.url':'','calendar.widgetOn':true},['calendar.widgetOn','calendar.range','calendar.size','calendar.side'],[]],
  ['calendar',{'calendar.url':'webcal://example.invalid/feed','calendar.widgetOn':false},['calendar.range','calendar.size','calendar.side'],['calendar.widgetOn']],
  ['calendar',{'calendar.url':'webcal://example.invalid/feed','calendar.widgetOn':true},[],['calendar.widgetOn','calendar.range','calendar.size','calendar.side']],
  ['mqtt',{'mqtt.enabled':false},['mqtt.host','mqtt.port','mqtt.username','mqtt.password','mqtt.useTls'],['mqtt.enabled']],
  ['mqtt',{'mqtt.enabled':true},[],['mqtt.host','mqtt.port','mqtt.username','mqtt.password','mqtt.useTls']],
  ['multiroom',{'immortal.multiRoomEnabled':false},['immortal.snapcastHost','immortal.maPort','immortal.maUsername','immortal.maPassword'],[]],
  ['multiroom',{'immortal.multiRoomEnabled':true},[],['immortal.snapcastHost','immortal.maPort','immortal.maUsername','immortal.maPassword']],
  ['chime',{'chime.quietHoursOn':false},['chime.quietStartMin','chime.quietEndMin'],[]],
  ['chime',{'chime.quietHoursOn':true},[],['chime.quietStartMin','chime.quietEndMin']],
  ['display',{'display.nightShift':false},['display.from','display.until','display.warmth'],[]],
  ['display',{'display.nightShift':true},[],['display.from','display.until','display.warmth']],
  ['faces',{'faces.enabled':false},['faces.id','faces.size'],[]],
  ['faces',{'faces.enabled':true,'faces.id':'minimal'},['faces.size'],['faces.id']],
  ['faces',{'faces.enabled':true,'faces.id':'flip'},[],['faces.id','faces.size']],
  ['countdowns',{'countdowns.repeat':true},['countdowns.year'],[]],
  ['countdowns',{'countdowns.repeat':false},[],['countdowns.year']],
 ];
 for(const [screen,values,absent,present] of cases){
  await load(screen);await config({values});
  for(const id of absent)check(screen+' hides '+id,(await row(id))===0);
  for(const id of present)check(screen+' shows '+id,(await row(id))===1);
 }
 await load('screensaver');
 for(const source of ['default','folder','album','immich','smb','dav','web'])for(const enabled of [false,true]){
  await config({source,values:{'screensaver.enabled':enabled,'screensaver.soundscape':'off'}});
  check('Cache source '+source+' enabled '+enabled,(await row('screensaver.cacheEnabled'))===Number(enabled&&['immich','dav'].includes(source)));
 }
 for(const mode of ['classic','shuffle','kenBurns','diptych','framed','galleryWall','push','memories','panorama','polaroid','mosaic','filmstrip','cinema','heroSidebar'])for(const enabled of [false,true]){
  await config({values:{'screensaver.enabled':enabled,'screensaver.mode':mode}});
  check('Fit mode '+mode+' enabled '+enabled,(await row('screensaver.fit'))===Number(enabled&&mode==='classic'));
 }
 for(const sound of ['off','rain','ocean','fire','white','pink','brown']){await config({values:{'screensaver.enabled':true,'screensaver.soundscape':sound}});check('Sound volume '+sound,(await row('screensaver.soundscapeVolume'))===Number(sound!=='off'));}
 await load('clock');for(const enabled of [false,true]){await config({values:{'digitalclock.enabled':enabled}});for(const id of ['digitalclock.face','digitalclock.size','digitalclock.layout','digitalclock.background','digitalclock.showDate','digitalclock.showSeconds'])check('Digital '+enabled+' '+id,(await row(id))===Number(enabled));for(const id of ['digitalclock.style','digitalclock.color','digitalclock.font','digitalclock.glow'])check('Legacy clock field hidden '+id,(await row(id))===0);}
 await load('quickbar');for(const enabled of [false,true]){await config({values:{'quickbar.enabled':enabled}});check('Quick buttons child '+enabled,(await row('quickbar.alwaysShow'))===Number(enabled));}
 await load('welcome');await config({values:{'welcome.showGreeting':false,'welcome.showClock':false,'welcome.showDate':false,'welcome.enableTts':false}});for(const id of ['welcome.voice','welcome.userName','welcome.greetingNight','welcome.greetingMorning','welcome.greetingAfternoon','welcome.greetingEvening','welcome.textScale','welcome.opacity'])check('Welcome editor stays available '+id,(await row(id))===1);
 await load('sunrise');await config({values:{'sunrise.enabled':false}});for(const id of ['sunrise.hour','sunrise.minute','sunrise.rampMinutes','sunrise.chime','sunrise.days'])check('Native sunrise remains editable '+id,(await row(id))===1);
 await load('screensaver');await config({values:{'screensaver.enabled':false}});check('Screensaver master hides entire body',await page.locator('[data-control]').count()===1);
 await load('dismiss');await config({values:{'dismiss.haMode':true,'dismiss.target':'Home Assistant dashboard'}});check('HA dismissal path editor exists',(await row('dismiss.haPath'))===1);await config({values:{'dismiss.haMode':false}});check('HA dismissal path hides outside HA mode',(await row('dismiss.haPath'))===0);
 await load('sleep');await action('sleep-start');check('Disabled countdown stays stopped',await page.locator('#sleep-remaining').textContent()==='00:00');await config({values:{'screensaver.sleepTimerEnabled':true}});await action('sleep-start');check('Sleep countdown starts',await page.locator('#sleep-remaining').textContent()!=='00:00');await action('sleep-stop');check('Sleep countdown stops',await page.locator('#sleep-remaining').textContent()==='00:00');
 await load('ha-entities');await config({scenario:'normal'});check('HA discovery respects HaClient domain filter',(await count('ha-group'))===13);for(const domain of ['climate','cover','media_player'])check('HA picker excludes unsupported '+domain,await page.locator('[data-ha-domain="'+domain+'"]').count()===0);await action('ha-group',{name:'Lights'});
 check('HA group cap is twelve',await page.locator('[data-ha-domain="light"] [data-action="add-item"]').count()===12);
 check('HA overflow hint',(await page.locator('body').innerText()).includes('+3 more — search to narrow'));
 await config({values:{'ha.search':'light.'}});
 check('HA search entity id uncaps list',await page.locator('[data-ha-domain="light"] [data-action="add-item"]').count()===15);
 check('HA searched groups cannot collapse',await page.locator('[data-action="ha-group"]').evaluateAll(bs=>bs.every(b=>b.disabled)));
 await config({values:{'ha.search':'no_such_entity'}});
 check('HA explicit no matches',(await page.locator('body').innerText()).includes('No entities match'));
 await config({scenario:'empty'});check('HA empty discovery has no fake groups',(await count('ha-group'))===0);check('HA empty discovery hides search',(await row('ha.search'))===0);await load('photos');await config({source:'web',values:{'faces.enabled':true,'calendar.url':'https://example.invalid/feed.ics','calendar.widgetOn':true}});check('Web source hides Immortal overlays',await page.locator('.frame-clock,.frame-calendar,.frame-playing').count()===0);
 for(const [screen,id,valid,invalid] of [
  ['calendar-url','calendar.url',['webcal://example.invalid/calendar','https://calendar.google.com/calendar/ical/sample','https://example.invalid/feed.ics'],['https://example.invalid/home','ftp://example.invalid/feed.ics']],
  ['album','album.url',['https://photos.icloud.com/shared/album/sample','https://photos.app.goo.gl/sample','https://nas.invalid/mo/sharing/sample'],['https://example.invalid/photos','https://nas.invalid/mo/sharing/']],
  ['web','web.url',['http://example.invalid','https://example.invalid'],['ftp://example.invalid','HTTPS://example.invalid']]
 ]){
  await load(screen);
  check(screen+' blank save disabled',await page.locator('#panel-form button[type="submit"]').isDisabled());
  check(screen+' absent removal for blank initial',await count('remove-source')===0);
  for(const value of valid){await page.locator('[data-value="'+id+'"]').fill(value);check(screen+' accepts '+value,await page.locator('#panel-form button[type="submit"]').isEnabled());}
  for(const value of invalid){await page.locator('[data-value="'+id+'"]').fill(value);check(screen+' rejects '+value,await page.locator('#panel-form button[type="submit"]').isDisabled());}
 }
 await load('calendar-url');await config({values:{'calendar.url':'webcal://example.invalid/calendar'}});await page.evaluate(()=>__portalMock.navigate('calendar-url'));
 check('existing calendar exposes removal',await count('remove-source')===1);
 await load('folder');check('folder roots have no Use button',await count('folder-use')===0);await action('folder',{name:'Pictures'});
 check('folder navigation updates breadcrumb',(await page.locator('body').innerText()).includes('/storage/emulated/0/Pictures'));
 check('folder offers Up',await count('folder-up')===1);check('folder selection only inside folder',await count('folder-use')===1);
 await action('folder-up');check('folder Up returns to roots',await count('folder-use')===0);
 for(const [screen,values] of [['dav',{'dav.url':'https://example.invalid/photos'}],['smb',{'smb.host':'sample','smb.share':'photos'}]]){
  await load(screen);await config({scenario:'empty',values});await page.locator('#panel-form').evaluate(f=>f.requestSubmit());await page.waitForFunction(()=>document.querySelector('.form-error')?.textContent.includes("Couldn't read images"));
  check(screen+' empty media does not connect',await page.evaluate(s=>__portalMock.getState().screen===s,screen));
  check(screen+' empty media explains failure',(await page.locator('body').innerText()).includes("Couldn't read images"));
 }
 for(const screen of ['smb','dav']){await load(screen);await config({connectionResult:{screen,count:42}});check(screen+' successful media count displayed',(await page.locator('body').innerText()).includes('42'));check(screen+' success has Done',(await count('source-done'))===1);check(screen+' success hides credentials',await page.locator('#panel-form').count()===0);await action('source-done');check(screen+' Done returns to frame',await page.evaluate(()=>__portalMock.getState().screen==='screensaver'));}
 await load('immich');await config({scenario:'empty',values:{'immich.url':'https://example.invalid','immich.key':'sample-only'}});await page.locator('#panel-form').evaluate(f=>f.requestSubmit());await page.waitForFunction(()=>__portalMock.getState().screen==='albums');check('Immich no albums offers whole library',await page.locator('[data-action="album"]').count()===1&&(await page.locator('body').innerText()).includes('Whole library'));
 await load('multiroom');await config({values:{'immortal.multiRoomEnabled':false}});check('Disabled multiroom hides Sign in',(await count('connection-test'))===0);await config({values:{'immortal.multiRoomEnabled':true}});check('Enabled multiroom shows Sign in',(await count('connection-test'))===1);await load('chime-preview');await config({values:{'chime.hourlyChimeOn':false,'chime.spokenTimeOn':false,'chime.goldenHourOn':false}});check('Sound preview cannot bypass disabled parents',(await count('voice-test'))===0&&(await count('play-ping'))===0);
 await load('ha-dashboard');await action('ha-temp',{dir:1});check('HA target step is one degree',(await page.locator('body').innerText()).includes('22.0°'));
 await load('chime');await config({values:{'chime.spokenTimeOn':false}});await page.evaluate(()=>__portalMock.revealControl('chime.voice'));check('Review harness exposes voice through its parent',(await row('chime.voice'))===1);await action('control',{id:'chime.voice'});check('All observed offline voice labels included',await page.locator('[data-action="voice-installed"]').count()===42);await action('close');await load('calendar');await action('search-result',{id:'calendar.range',screen:'calendar'});check('Calendar search never manufactures a feed',await page.evaluate(()=>__portalMock.getState().values['calendar.url']==='')&&await page.locator('[role="dialog"]').count()===1);
 await load('alarm');await config({values:{'alarm.enabled':false}});for(const id of ['alarm.hour','alarm.minute','alarm.days'])check('Alarm editor stays available '+id,(await row(id))===1);
 await action('control',{id:'alarm.minute'});await page.locator('#control-editor').fill('55');await action('step',{id:'alarm.minute',dir:1});check('Alarm minute wraps by five',await page.locator('#control-editor').inputValue()==='0');await action('close');
 await load('options');check('Options uses native default delta band',await page.evaluate(()=>__portalMock.getState().values['options.deltaMin']===20&&__portalMock.getState().values['options.deltaMax']===30));
 await load('camera');
 for(const value of ['rtsp://x','rtsps://sample.invalid/stream','RTSP://sample.invalid/stream']){
  await config({values:{'camera.name':'','camera.url':value}});
  await page.locator('#panel-form').evaluate(f=>f.requestSubmit());
  check('Camera rejects unsupported address '+value,(await page.locator('.form-error').textContent()).includes('rtsp://')&&await page.evaluate(()=>__portalMock.getState().collections.camera.length===0));
 }
 await config({values:{'camera.name':'','camera.url':'rtsp://sample.invalid/stream'}});await page.locator('#panel-form').evaluate(f=>f.requestSubmit());check('Camera optional name falls back to Camera',await page.evaluate(()=>__portalMock.getState().collections.camera.includes('Camera')));
 await load('crypto');await config({values:{'crypto.id':' solana ','crypto.symbol':''}});await page.locator('#panel-form').evaluate(f=>f.requestSubmit());check('Crypto optional symbol falls back to normalized ID',await page.evaluate(()=>__portalMock.getState().collections.crypto.includes('SOLANA')));
 await load('todo');check('Clear completed absent with no completed items',(await count('todo-clear'))===0);await action('add-item',{key:'todo',name:'Sample task'});await action('todo-complete',{name:'Sample task'});check('Clear completed appears only with completed items',(await count('todo-clear'))===1);await action('todo-clear');check('Clearing completed removes its action',(await count('todo-clear'))===0);
 await load('lamp-preview');await config({values:{'lamp.red':false}});check('Lamp normal mode shows Warmth',(await row('lamp.warmth'))===1);await config({values:{'lamp.red':true}});check('Lamp night light hides Warmth and retains Brightness',(await row('lamp.warmth'))===0&&(await row('lamp.brightness'))===1);await action('hide-light');check('Lamp hidden controls remove the panel',await page.locator('.information-card').count()===0);await action('show-light');check('Lamp controls can be restored',await page.locator('.information-card').count()===1);
 check('no runtime errors',errors.length===0,errors.join('\n'));
 const report={timestamp:new Date().toISOString(),total:results.length,passed:results.filter(r=>r.ok).length,failed:results.filter(r=>!r.ok),results,errors};
 await page.evaluate(r=>window.__portalConditionalReport=r,report);
 return {total:report.total,passed:report.passed,failed:report.failed,errors};
}
