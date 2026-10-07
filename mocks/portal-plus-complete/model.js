/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */
const PORTAL_MODEL = (() => {
 const R=PORTAL_INVENTORY.registry;
 const registry=R.filter(x=>!x.derived).map(x=>({...x,sourceLabel:x.domain,default:x.type==='bool'?false:x.type==='int'?x.min:x.type==='enum'?x.options[0].value:'',kind:'control'}));
 const extra=[];
 const c=(id,title,type,defaultValue,more={})=>{const row={id,title,type,default:defaultValue,kind:'control',section:more.section||'Options',source:more.source||'',...more};extra.push(row);return id;};
 const b=(id,title,value=false,more={})=>c(id,title,'bool',value,more);
 const n=(id,title,value,min,max,step=1,more={})=>c(id,title,'int',value,{min,max,step,...more});
 const e=(id,title,value,options,more={})=>c(id,title,'enum',value,{options:options.map(x=>typeof x==='string'?{value:x,label:x}:x),...more});
 const t=(id,title,value='',more={})=>c(id,title,'string',value,more);
 const fieldSources={display:'ImmortalSettingsActivity.kt',wallpaper:'ImmortalSettingsActivity.kt',faces:'FacePickerActivity.kt',welcome:'WelcomeSettingsActivity.kt',sources:'ScreensaverSourcesActivity.kt',alarm:'AlarmSettingsActivity.kt',countdowns:'CountdownSettingsActivity.kt',ha:'HaSettingsActivity.kt',camera:'CameraViewerActivity.kt',alpaca:'StocksSettingsActivity.kt',options:'OptionsSettingsActivity.kt'};
 const defaults={'screensaver.enabled':true,'screensaver.shuffle':true,'screensaver.includeVideo':true,'screensaver.intervalSec':30,'screensaver.presenceMode':'ALWAYS_ON','screensaver.soundscape':'off','screensaver.overnightStartMin':1320,'screensaver.overnightEndMin':420,'screensaver.sleepTimerMin':30,'screensaver.albumRefreshMin':60,'screensaver.cacheBudgetGb':4,'screensaver.antiBurnIn':true,'immortal.showMiniPlayer':true,'immortal.hideStatusBar':true,'immortal.maPort':8095,'immortal.weatherUnit':'auto','immortal.clockFormat':'auto','mqtt.port':1883,'mqtt.validateCert':true,'chime.volume':30,'chime.quietStartMin':1320,'chime.quietEndMin':420,'sunrise.hour':7,'sunrise.rampMinutes':20,'sunrise.chime':true,'digitalclock.enabled':false,'digitalclock.showDate':true,'digitalclock.size':'large','digitalclock.font':'light','digitalclock.glow':'soft','welcome.durationMs':3200,'welcome.showGreeting':true,'welcome.showClock':true,'welcome.showDate':true,'fleet.name':'Portal+'};
 registry.find(x=>x.id==='calendar.size').choices=['Small','Medium','Large'].map((label,value)=>({label,value}));registry.find(x=>x.id==='chime.sunriseVariant').choices=['Morning','Rooster'].map((label,value)=>({label,value}));
 registry.forEach(x=>{if(x.id in defaults)x.default=defaults[x.id];if(x.type==='nav')x.default='';});
 n('display.brightness','Brightness',72,1,100,1,{slider:true});b('display.auto','Auto-brightness');n('display.mediaVolume','Media volume',45,0,100,1,{slider:true});b('display.nightShift','Night Shift');n('display.from','From',1200,0,1439,30,{time:true,gate:'display.nightShift'});n('display.until','Until',420,0,1439,30,{time:true,gate:'display.nightShift'});n('display.warmth','Warmth',50,0,100,10,{gate:'display.nightShift'});b('display.touchSounds','Touch sounds',true);
 e('wallpaper.mode','Background','dark',[{value:'dusk_aurora',label:'Dusk Aurora'},{value:'dark',label:'Dark'},...PORTAL_FIXTURES.gradients,...['wilderness','denali','yosemite'].map((name,i)=>({value:'photo:0'+(i+1)+'_'+name+'.jpg',label:name[0].toUpperCase()+name.slice(1)})),{value:'screensaver',label:'Screensaver'},{value:'sky',label:'Sky'},{value:'starfield',label:'Star field'}]);b('wallpaper.grain','Film grain');
 b('faces.enabled','Show clock face',true);e('faces.id','Photo clock face','immortal-classic',[{value:'immortal-classic',label:'Immortal'},{value:'flip',label:'Flip clock'},{value:'big',label:'Big clock'},{value:'bold',label:'Bold'},{value:'minimal',label:'Minimal'}]);e('faces.size','Size','Medium',['Small','Medium','Large'],{gate:'faces.enabled'});
 e('sources.selected','Photo source','default',[{value:'default',label:'Immortal photos'},{value:'folder',label:'My photos & videos'},{value:'album',label:'Shared album link'},{value:'immich',label:'Immich'},{value:'smb',label:'SMB / NAS'},{value:'dav',label:'WebDAV'},{value:'web',label:'Web page'}]);
 t('immich.url','Server address','',{required:true,format:'url'});t('immich.key','API key','',{required:true,secret:true});e('immich.album','Album','Whole library',['Whole library','Into the wild','Mountain mornings']);
 t('smb.host','Server','',{required:true});t('smb.share','Share name','',{required:true});t('smb.path','Folder in the share');t('smb.username','Username');t('smb.password','Password','',{secret:true});
 t('dav.url','WebDAV folder address','',{required:true,format:'url'});t('dav.username','Username (optional)');t('dav.password','Password (optional)','',{secret:true});
 t('album.url','Shared album link','',{required:true,format:'url'});t('web.url','Web page address','',{required:true,format:'url'});t('folder.path','Folder','Pictures');
 e('screensaver.mode','Screensaver style','shuffle',[{value:'classic',label:'Classic (crossfade)'},{value:'shuffle',label:'Shuffle — all styles'},{value:'kenBurns',label:'Cinematic Ken Burns'},{value:'diptych',label:'Orientation Diptych'},{value:'framed',label:'Framed Print'},{value:'galleryWall',label:'Living Gallery Wall'},{value:'push',label:'Cinematic Push'},{value:'memories',label:'Memories'},{value:'panorama',label:'Panorama Drift'},{value:'polaroid',label:'Polaroid Scatter'},{value:'mosaic',label:'Mosaic Wall'},{value:'filmstrip',label:'Filmstrip Coverflow'},{value:'cinema',label:'Cinema Letterbox'},{value:'heroSidebar',label:'Hero + Sidebar'}]);
 e('dismiss.target','Open when you tap to exit','Immortal launcher',['Immortal launcher','Home Assistant dashboard','Choose an installed app']);
 ['Night','Morning','Afternoon','Evening'].forEach((x,i)=>t('welcome.greeting'+x,x+' greeting',['Good night','Good morning','Good afternoon','Good evening'][i]));t('welcome.userName','Your name');e('welcome.voice','Greeting voice','System default',['System default','Installed device voice']);e('chime.voice','Spoken-time voice','System default',['System default','Installed device voice'],{gate:'chime.spokenTimeOn'});e('welcome.textScale','Text size','Medium',['Small','Medium','Large']);n('welcome.opacity','Background opacity',70,0,100,10);
 t('dismiss.haPath','Home Assistant dashboard path','',{gate:'dismiss.haMode'});
 e('sunrise.days','Repeat on','Weekdays',['Every day','Weekdays','Weekends','Choose days']);
 b('alarm.enabled','Enabled');n('alarm.hour','Hour',7,0,23,1,{wrap:true});n('alarm.minute','Minute',0,0,59,5,{wrap:true});e('alarm.days','Repeat on','Weekdays',['Every day','Weekdays','Weekends','Choose days']);
 t('countdowns.name','Name','',{required:true});n('countdowns.year','Year',2026,2000,2100);n('countdowns.month','Month',1,1,12);n('countdowns.day','Day',1,1,31);e('countdowns.emoji','Icon','🎂',PORTAL_FIXTURES.emoji);b('countdowns.repeat','Repeats every year');
 t('ha.url','Base URL','',{required:true,format:'url'});t('ha.token','Long-lived token','',{required:true,secret:true});t('ha.search','Search entities');
 t('crypto.id','CoinGecko id','',{required:true});t('crypto.symbol','Display symbol (optional)');t('todo.item','What needs doing?','',{required:true});t('news.name','Name (optional)');t('news.url','RSS / Atom URL','',{required:true,format:'url'});t('stocks.symbol','Symbol','',{required:true});t('alpaca.key','API Key ID','',{required:true,secret:true});t('alpaca.secret','API Secret Key','',{required:true,secret:true});e('options.type','Contract type','Calls',['Calls','Puts']);n('options.deltaMin','Delta min',20,1,99);n('options.deltaMax','Delta max',30,1,99);n('options.expiry','Expiry within (days)',45,1,365,5);t('options.symbol','Symbol','',{required:true});
 t('world.city','Search city');
 n('timers.minutes','Minutes',5,0,180);n('timers.seconds','Seconds',0,0,55,5);t('notes.message','Leave a note');e('converter.kind','Measurement','Temperature',[...Object.keys(PORTAL_FIXTURES.units),'Currency']);t('converter.value','Value','20');e('converter.from','From','°C',['°C','°F','K']);e('converter.to','To','°F',['°C','°F','K']);
 n('lamp.brightness','Brightness',90,5,100,1,{slider:true});n('lamp.warmth','Warmth',60,0,100,1,{slider:true});b('lamp.red','Red night light');t('intercom.address',"Other Portal’s address",'',{required:true});t('camera.name','Name (optional)');t('camera.url','RTSP URL','',{required:true,format:'rtsp'});b('debug.enabled','Debug Mode');
 e('sources.albumRefresh','Refresh album','60',[15,30,45,60,120,180,240,360,720,1440].map(v=>({value:String(v),label:v<60?v+' min':v/60+' hours'})));
 const controls=[...registry,...extra];
 const panel=(id,title,category,ids=[],more={})=>({id,title,category,controls:ids,...more});
 const ids=d=>registry.filter(x=>x.domain===d&&!x.hidden).map(x=>x.id);
 const pages=[
 panel('touch','Touch sounds','sound',['display.touchSounds']),
 panel('display','Display','appearance',['display.brightness','display.auto','display.mediaVolume','display.nightShift','display.from','display.until','display.warmth'],{activity:'ImmortalSettingsActivity',permission:'Brightness control'}),
 panel('appearance','App appearance','appearance',['immortal.tileSize','immortal.showMiniPlayer','immortal.hideStatusBar','immortal.clockFormat']),
 panel('wallpaper','Wallpaper','appearance',['wallpaper.mode','wallpaper.grain']),
 panel('screensaver','Photos & screensaver','photos',ids('screensaver'),{activity:'ScreensaverSettingsActivity'}),
 panel('sources','Photo sources','photos',['sources.selected','sources.albumRefresh'],{activity:'ScreensaverSourcesActivity',links:['phone','folder','album','immich','smb','dav','web']}),
 panel('styles','Screensaver style','photos',['screensaver.mode'],{activity:'ScreensaverModeActivity',preview:'photos'}),
 panel('faces','Photo clock faces','photos',['faces.enabled','faces.id','faces.size','immortal.clockFormat','screensaver.showNowPlaying'],{activity:'FacePickerActivity',preview:'photos'}),
 panel('dismiss','Open when you tap to exit','photos',['dismiss.target','dismiss.haPath'],{activity:'ScreensaverDismissAppActivity'}),
 panel('immich','Connect Immich','photos',['immich.url','immich.key'],{activity:'ImmichConnectActivity',form:'source',success:'albums'}),
 panel('smb','Connect SMB / NAS','photos',['smb.host','smb.share','smb.path','smb.username','smb.password'],{activity:'SmbConnectActivity',form:'source',success:'screensaver'}),
 panel('dav','Connect WebDAV','photos',['dav.url','dav.username','dav.password'],{activity:'DavConnectActivity',form:'source',success:'screensaver'}),
 panel('album','Shared album link','photos',['album.url'],{activity:'AlbumUrlEntryActivity',form:'source',success:'screensaver'}),
 panel('web','Web page','photos',['web.url'],{activity:'WebUrlEntryActivity',form:'source',success:'screensaver'}),
 panel('folder','Choose photo folder','photos',['folder.path'],{activity:'FolderPickerActivity',collection:'folder',preview:'folder-preview'}),
 panel('albums','Choose Immich album','photos',['immich.album'],{collection:'albums'}),
 panel('calendar','Calendar','home',ids('calendar'),{links:['calendar-url']}),
 panel('calendar-url','Calendar feed','home',['calendar.url'],{activity:'CalendarUrlEntryActivity',form:'calendar',success:'calendar',remove:'Remove calendar'}),
 panel('clock','Digital clock','photos',ids('digitalclock'),{activity:'ClockSettingsActivity',preview:'digital-preview'}),
 panel('quickbar','Quick buttons','home',ids('quickbar')),
 panel('weather','Weather','home',['immortal.weatherUnit','immortal.weatherWidget']),
 panel('world','World clock','home',['world.city'],{activity:'WorldClockActivity',collection:'world'}),
 panel('countdowns','Countdowns','home',['countdowns.name','countdowns.year','countdowns.month','countdowns.day','countdowns.emoji','countdowns.repeat'],{activity:'CountdownSettingsActivity',collection:'countdowns',form:'add'}),
 panel('chime','Sounds','sound',ids('chime').concat('chime.voice'),{activity:'ChimeSettingsActivity',preview:'chime-preview'}),
 panel('welcome','Welcome greeting','sound',ids('welcome').concat(['welcome.voice','welcome.userName','welcome.greetingNight','welcome.greetingMorning','welcome.greetingAfternoon','welcome.greetingEvening','welcome.textScale','welcome.opacity']),{activity:'WelcomeSettingsActivity',preview:'welcome-preview'}),
 panel('sunrise','Sunrise & wake','sound',ids('sunrise').concat('sunrise.days'),{activity:'SunriseSettingsActivity',preview:'wake-light'}),
 panel('alarm','Alarm','sound',['alarm.enabled','alarm.hour','alarm.minute','alarm.days'],{activity:'AlarmSettingsActivity',preview:'alarm-ring'}),
 panel('multiroom','Multi-room audio','connections',['immortal.multiRoomEnabled','immortal.snapcastHost','immortal.maPort','immortal.maUsername','immortal.maPassword'],{activity:'MultiRoomActivity',form:'connection',success:'multiroom'}),
 panel('mqtt','MQTT publishing','connections',ids('mqtt'),{activity:'MqttActivity',form:'connection',success:'mqtt'}),
 panel('ha','Home Assistant controls','connections',['ha.url','ha.token'],{activity:'HaSettingsActivity',form:'connection',success:'ha-entities',links:['ha-entities','ha-dashboard']}),
 panel('ha-entities','Choose Home Assistant entities','connections',['ha.search'],{collection:'ha'}),
 panel('phone','Phone remote','connections',[],{activity:'RemotePairActivity',special:'phone'}),
 panel('boot','Start on boot','device',[],{activity:'BootAppsActivity',collection:'boot'}),
 panel('device','Device name','device',['fleet.name']),
 panel('health','About & device health','device',[],{links:['sources','debug'],activity:'DeviceHealthActivity',special:'health'}),
 panel('sleep','Sleep timer','device',['screensaver.sleepTimerEnabled','screensaver.sleepTimerMin','screensaver.pauseAudioOnSleep','screensaver.closeAppOnSleep'],{activity:'SleepSettingsActivity'}),
 panel('crypto','Crypto','home',['crypto.id','crypto.symbol'],{activity:'CryptoSettingsActivity',collection:'crypto',form:'add',popular:['BTC','ETH','SOL','ADA','XRP','DOGE','DOT','LINK','LTC','AVAX']}),
 panel('todo','To-do','home',['todo.item'],{activity:'TodoSettingsActivity',collection:'todo',form:'add'}),
 panel('news','News','home',['news.name','news.url'],{activity:'NewsSettingsActivity',collection:'news',form:'add',popular:['BBC','NPR','Reuters','Hacker News','The Verge','Ars Technica']}),
 panel('stocks','Stocks','home',['stocks.symbol'],{activity:'StocksSettingsActivity',collection:'stocks',form:'add',links:['alpaca'],popular:['AAPL','MSFT','NVDA','GOOGL','AMZN','TSLA','SPY','QQQ']}),
 panel('alpaca','Alpaca account','home',['alpaca.key','alpaca.secret'],{form:'connection',success:'stocks'}),
 panel('options','Options scanner','home',['options.type','options.deltaMin','options.deltaMax','options.expiry','options.symbol'],{activity:'OptionsSettingsActivity',collection:'options',form:'add',links:['alpaca']}),
 panel('camera','Cameras','home',['camera.name','camera.url'],{activity:'CameraViewerActivity',collection:'camera',form:'add'}),
 panel('lamp','Lamp','sound',['lamp.brightness','lamp.warmth','lamp.red'],{activity:'LampActivity',preview:'lamp-preview'}),
 panel('intercom','Intercom','connections',['intercom.address'],{activity:'IntercomActivity',special:'intercom'}),
 panel('timers','Timers','home',['timers.minutes','timers.seconds'],{collection:'timers',form:'timer'}),
 panel('notes','Leave a note','home',['notes.message'],{special:'notes'}),
 panel('converter','Converter','home',['converter.kind','converter.value','converter.from','converter.to'],{special:'converter'}),
 panel('stories','Bedtime stories','sound',[],{activity:'BedtimeStoryActivity',special:'stories'}),
 panel('iss','ISS passes','home',[],{special:'iss'}),panel('aurora','Aurora outlook','home',[],{special:'aurora'}),panel('speed','Speed test','connections',[],{special:'speed'}),
 panel('apk','Install an APK','device',[],{activity:'ApkBrowserActivity',special:'apk'}),
 panel('help','Welcome to Immortal','device',[],{activity:'HelpActivity',special:'help'}),
 panel('system','Android & Portal settings','advanced',[],{special:'system'}),
 panel('managed','Automatically managed settings','advanced',registry.filter(x=>x.hidden).map(x=>x.id),{readOnly:true}),panel('debug','Diagnostics','advanced',['debug.enabled'],{special:'debug'}),
 ];
 const navMap={screensaverStyle:'styles',clockFace:'faces',photoSource:'sources',dismissTarget:'dismiss'};
 const categories=[['appearance','Appearance','display'],['photos','Photos & screensaver','photo'],['home','Home & widgets','gridapps'],['sound','Sound & wake','bell'],['connections','Connections','wifi'],['access','Input & accessibility','hand'],['device','Device','info'],['advanced','Advanced','tools']];
 const categoryPages={appearance:['display','appearance','wallpaper'],photos:['screensaver','faces','clock','sources'],home:['widgets','weather','calendar','world','countdowns','quickbar','alarm','crypto','todo','news','stocks','options'],sound:['touch','chime','welcome','sunrise','alarm','multiroom','lamp'],connections:['ha','mqtt','multiroom','phone','intercom'],access:['touch','system','quickbar'],device:['device','boot','sleep','health','apk','help'],advanced:['system','managed','debug']};
 const installed=[['Browser','org.chromium.chrome','globe','blue'],['VLC','org.videolan.vlc','vlc','orange'],['NewPipe','org.schabi.newpipe','film','rose'],['Home Assistant','io.homeassistant.companion.android.minimal','home','blue'],['Material Files','me.zhanghai.android.files','folder','blue'],['F-Droid','org.fdroid.fdroid','bag','green'],['Aurora Store','com.aurora.store','bag','purple'],['Breezy Weather','org.breezyweather','weather','blue'],['Jarvis','com.portal.assistant','notes','purple'],['PortalHub','com.portal.calendar','cal','orange'],['Portal Settings','com.facebook.alohaapps.settings','gear','gray'],['Google Search','com.android.quicksearchbox','search','gray']].map(([name,packageName,glyph,color])=>({name,packageName,glyph,color,installed:true,external:true}));
 const bootApps=[...installed,...[['Camera','com.android.camera2'],['Android Settings','com.android.settings'],['Avatar editor','com.facebook.aloha.app.cameraeditor'],['Contacts','com.facebook.alohaapps.contacts'],['Portal Home','com.facebook.alohaapps.launcher'],['Portal Photos','com.facebook.alohaapps.superframe'],['Ability Center','com.facebook.alohaservices.abilitymanager'],['Portal AI','com.facebook.portal.aiservice']].map(([name,packageName])=>({name,packageName}))].sort((a,b)=>a.name.localeCompare(b.name));
 const tools=[['alarm','Alarm','bell'],['camera','Cameras','film'],['countdowns','Countdowns','hourglass'],['lamp','Lamp','bulb'],['stories','Bedtime story','notes'],['intercom','Intercom','megaphone'],['timers','Timers','timer'],['notes','Leave a note','notes'],['converter','Converter','calc'],['iss','ISS passes','globe'],['aurora','Aurora outlook','weather'],['speed','Speed test','wifi']];
 const widgetKinds=[['today','Today','cal','calendar'],['ha','Home Assistant','homeS','ha'],['calendar','Calendar','cal','calendar'],['weather','Weather','weather','weather'],['alarm','Alarm','bell','alarm'],['world_clock','World clock','clock','world'],['crypto','Crypto','calc','crypto'],['quick_controls','Quick controls','tools','display'],['photo_memory','Photo memory','photo','sources'],['timers','Timers','timer','timers'],['todo','To-do','notes','todo'],['news','News','globe','news'],['stocks','Stocks','calc','stocks'],['options','Options','calc','options'],['appwidget','Android app widget','gridapps','provider-widgets']];
 extra.forEach(x=>{const prefix=x.id.split('.')[0];x.source='app/src/main/java/com/immortal/launcher/'+(fieldSources[prefix]||({'immich':'ImmichConnectActivity.kt',smb:'SmbConnectActivity.kt',dav:'DavConnectActivity.kt',album:'AlbumUrlEntryActivity.kt',web:'WebUrlEntryActivity.kt',folder:'FolderPickerActivity.kt',world:'ImmortalSettingsActivity.kt',dismiss:'ScreensaverDismissAppActivity.kt',chime:'ChimeSettingsActivity.kt',sunrise:'SunriseSettingsActivity.kt',crypto:'CryptoSettingsActivity.kt',todo:'TodoSettingsActivity.kt',news:'NewsSettingsActivity.kt',stocks:'StocksSettingsActivity.kt',options:'OptionsSettingsActivity.kt',timers:'HomeActivity.kt',notes:'HomeToolOverlays.kt',converter:'HomeToolOverlays.kt',lamp:'LampActivity.kt',intercom:'IntercomActivity.kt',debug:'ImmortalSettingsActivity.kt'}[prefix]||'ImmortalSettingsActivity.kt'));});
 return {controls,pages,categories,categoryPages,installed,bootApps,tools,widgetKinds,navMap};
})();
