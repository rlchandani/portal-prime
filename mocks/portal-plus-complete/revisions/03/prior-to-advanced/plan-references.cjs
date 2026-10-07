/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */
const fs = require('fs');
const path = require('path');
const dir = __dirname;
const C = JSON.parse(fs.readFileSync(path.join(dir, 'coverage.json')));
const extras = [
  ['home','Home'],['apps','App library'],['app-folder','App folder'],['store','App Store'],
  ['app-detail','App details'],['tools','Tools'],['edit','Customize home'],['widgets','Widget library'],
  ['settings','Settings overview'],['photos','Photo frame'],['digital-preview','Digital clock preview'],
  ['welcome-preview','Welcome preview'],['wake-light','Wake light'],['lamp-preview','Lamp preview'],
  ['alarm-ring','Alarm ringing'],['folder-preview','Photo folder preview'],
  ['ha-dashboard','Home Assistant dashboard'],['switcher','Recent apps'],
  ['provider-widgets','Android widget provider'],['chime-preview','Chime preview'],['music','Music']
];
const plan = [];
for (const theme of ['light','dark']) {
  for (const p of [...C.pages, ...extras.map(([id,title])=>({id,title}))]) {
    plan.push({id:theme+'-screen-'+p.id,title:p.title,screen:p.id,category:p.category||'appearance',
      theme,scenario:'expanded',kind:'Screen',contractTarget:!['music','provider-widgets','system'].includes(p.id)});
  }
  for (const p of C.popups) plan.push({...p,id:theme+'-popup-'+p.id,theme,scenario:p.scenario||'expanded',
    kind:'Popup / state',contractTarget:p.contractTarget!==false&&!['system-handoff','memo-permission'].includes(p.id)});
  for (const category of ['photos','home','sound','connections','access','device','advanced'])
    plan.push({id:theme+'-category-'+category,title:'Settings · '+category,screen:'settings',category,
      theme,scenario:'expanded',kind:'Settings category',contractTarget:true});
  for (const a of JSON.parse(fs.readFileSync(dir+'/inventory.json')).catalog.slice(1))
    plan.push({id:theme+'-product-'+a.packageName,title:a.name,screen:'store',theme,scenario:'normal',
      kind:'Catalog details',steps:[{action:'product',data:{pkg:a.packageName}}],contractTarget:true});
  for (let n=1;n<8;n++) plan.push({id:theme+'-welcome-step-'+n,title:'Getting started · step '+(n+1),
    screen:'help',theme,scenario:'normal',kind:'Onboarding',steps:Array.from({length:n},()=>({action:'help-step',data:{dir:1}})),
    contractTarget:true});
}
for (const [id,screen,kind] of [['screensaver.mode','photos','Screensaver style'],['faces.id','photos','Photo clock face'],['digitalclock.face','digital-preview','Digital clock face']]) {
  for (const opt of C.controls.find(c=>c.id===id).options) plan.push({
    id:kind.toLowerCase().replaceAll(' ','-')+'-'+opt.value,title:opt.label,screen,theme:'dark',scenario:'normal',
    kind,values:{[id]:opt.value,'faces.enabled':true,'digitalclock.enabled':true},contractTarget:true
  });
}
for (const scenario of ['empty','offline','missing-permission','playing'])
  for (const screen of ['home','health','phone','ha-dashboard','sources'])
    plan.push({id:'state-'+scenario+'-'+screen,title:screen+' · '+scenario,screen,theme:'dark',scenario,
      kind:'Alternate state',contractTarget:true});
const conditional=[];
for(let mask=0;mask<8;mask++)conditional.push({id:'chime-'+mask,title:'Chime combination '+mask,screen:'chime',values:{'chime.hourlyChimeOn':!!(mask&1),'chime.spokenTimeOn':!!(mask&2),'chime.goldenHourOn':!!(mask&4)}});
for(const source of ['default','folder','album','immich','smb','dav','web'])conditional.push({id:'source-'+source,title:'Photo source · '+source,screen:'screensaver',source,values:{'screensaver.enabled':true}});
for(const [id,screen,values] of [
 ['frame-off','screensaver',{'screensaver.enabled':false}],['frame-classic','screensaver',{'screensaver.enabled':true,'screensaver.mode':'classic'}],['night-shift-off','display',{'display.nightShift':false}],['night-shift-on','display',{'display.nightShift':true}],['overnight-on','screensaver',{'screensaver.enabled':true,'screensaver.overnightEnabled':true}],['sleep-off','sleep',{'screensaver.sleepTimerEnabled':false}],['sleep-on','sleep',{'screensaver.sleepTimerEnabled':true}],['clock-off','clock',{'digitalclock.enabled':false}],['clock-on','clock',{'digitalclock.enabled':true}],['faces-off','faces',{'faces.enabled':false}],['faces-fixed','faces',{'faces.enabled':true,'faces.id':'minimal'}],['faces-sized','faces',{'faces.enabled':true,'faces.id':'flip'}],['sunrise-off','sunrise',{'sunrise.enabled':false}],['sunrise-on','sunrise',{'sunrise.enabled':true}],['welcome-off','welcome',{'welcome.enableTts':false,'welcome.showGreeting':false,'welcome.showDate':false,'welcome.showClock':false}],['mqtt-off','mqtt',{'mqtt.enabled':false}],['mqtt-on','mqtt',{'mqtt.enabled':true}],['multiroom-off','multiroom',{'immortal.multiRoomEnabled':false}],['multiroom-on','multiroom',{'immortal.multiRoomEnabled':true}],['quickbar-off','quickbar',{'quickbar.enabled':false}],['quickbar-on','quickbar',{'quickbar.enabled':true}],['countdown-year','countdowns',{'countdowns.repeat':false}],['countdown-yearly','countdowns',{'countdowns.repeat':true}],['ha-dismiss','dismiss',{'dismiss.target':'Home Assistant dashboard','dismiss.haMode':true}],['calendar-no-link','calendar',{'calendar.url':''}],['calendar-off','calendar',{'calendar.url':'webcal://example.invalid/feed','calendar.widgetOn':false}],['calendar-on','calendar',{'calendar.url':'webcal://example.invalid/feed','calendar.widgetOn':true}],['calendar-webcal','calendar-url',{'calendar.url':'webcal://example.invalid/feed'}],['calendar-invalid','calendar-url',{'calendar.url':'https://example.invalid/home'}],['album-valid','album',{'album.url':'https://photos.icloud.com/shared/album/sample'}],['album-invalid','album',{'album.url':'https://example.invalid/photos'}]
])conditional.push({id,title:id.replaceAll('-',' '),screen,values});
conditional.push({id:'ha-expanded',title:'HA group cap',screen:'ha-entities',steps:[{action:'ha-group',data:{name:'Lights'}}]},{id:'ha-search',title:'HA entity-id search',screen:'ha-entities',values:{'ha.search':'light.'}},{id:'ha-no-match',title:'HA no results',screen:'ha-entities',values:{'ha.search':'not_found'}},{id:'ha-empty',title:'HA zero discoveries',screen:'ha-entities',scenario:'empty'},{id:'folder-nested',title:'Folder navigation',screen:'folder',steps:[{action:'folder',data:{name:'Pictures'}}]},{id:'folder-empty',title:'Folder empty',screen:'folder',scenario:'empty',steps:[{action:'folder',data:{name:'Pictures'}}]},{id:'folder-permission',title:'Folder permission',screen:'folder',scenario:'missing-permission'});
for(const screen of ['smb','dav'])conditional.push({id:screen+'-connected',title:screen+' connected',screen,connectionResult:{screen,count:42}});
for(const theme of ['light','dark'])for(const c of conditional)plan.push({...c,id:'conditional-'+theme+'-'+c.id,theme,scenario:c.scenario||'normal',kind:'Conditional state',contractTarget:true});
for (const item of plan) item.file='reference/'+item.id+'.png';
fs.mkdirSync(dir+'/reference',{recursive:true});
fs.writeFileSync(dir+'/reference-plan.json',JSON.stringify(plan,null,2)+'\n');
console.log(JSON.stringify({references:plan.length,destinations:C.pages.length+extras.length}));
