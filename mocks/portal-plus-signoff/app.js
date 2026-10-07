/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */
(() => {
  "use strict";
  const query=new URLSearchParams(location.search),device=document.getElementById("device");
  const state={
    screen:query.get("screen")||"home",category:query.get("category")||"appearance",theme:query.get("theme")==="dark"?"dark":"light",scenario:query.get("scenario")||"normal",
    room:"Living room",clock:"Digital",source:"Immich",album:"Into the wild",selectedAlbums:["Into the wild"],photo:0,connected:false,playing:false,track:0,lights:true,lamp:false,brightness:72,interval:"30 seconds",crop:"Fill",idle:"5 minutes",
    widgets:["Photos","Weather","Today","Music","Quick controls"],favorites:["Browser","VLC","Radio","Home Assistant","Music","App Store"],density:"Comfortable",wallpaper:"Ambient sky",installed:new Set(),installing:new Set(),currentApp:"VLC",highlight:"",
    switches:{shuffle:true,clock:true,screensaver:true,showDate:true,showTime:true,miniPlayer:true,chime:false,touch:true,wake:false,autoStart:true,remote:true,statusBar:false,gesture:true,overnight:false,welcome:true,mqtt:false},
    timerMinutes:5,timerLeft:300,timerRunning:false,timerEnd:0,modal:null,toast:"",passwordVisible:false,sourceError:"",testing:false,calendarAdded:false,worldCities:["London","Tokyo"],motion:false,values:{}
  };
  const categories=[
    ["appearance","Appearance","display","#527ab0"],["photos","Photos & screensaver","photo","#5b9379"],["home","Home & widgets","gridapps","#8874ad"],["sound","Sound & wake","bell","#b58155"],["connections","Connections","wifi","#4c83a6"],["access","Input & accessibility","hand","#7f8ca4"],["device","Device","info","#708890"],["advanced","Advanced","tools","#7b8792"]
  ];
  const apps=[
    ["Browser","globe","blue","Browse the web"],["VLC","vlc","orange","Video & music"],["Radio","radio","purple","Internet stations"],["Home Assistant","home","blue","Your smart home"],["Music","music","rose","Your players"],["App Store","bag","green","More for your Portal"],["Photo display","photo","green","Your photo frame"],["Utilities","folder","gray","Four everyday tools"],["Intercom","megaphone","purple","Around the house"],["Timer","timer","orange","Time, set aside"],["Stories","notes","rose","A quieter bedtime"],["Camera viewer","film","gray","Your home cameras"]
  ].map(([name,glyph,color,subtitle])=>({name,glyph,color,subtitle}));
  const widgets=[["Photos","photo","Keep your selected album close."],["Weather","weather","Current conditions and the day ahead."],["Today","cal","Your next events, at a glance."],["Music","music","Playback controls for your room."],["Quick controls","homeS","The household actions you use most."],["World clock","clock","The time in places that matter."],["Countdown","hourglass","Days until something special."],["Alarm","bell","Your next alarm and wake schedule."],["To-do","notes","The things you want to get done."],["News","globe","Headlines from your chosen sources."],["Stocks","calc","Your selected market watchlist."],["Crypto","calc","Your chosen digital assets."],["Options","calc","Your selected options positions."],["Timers","timer","Timers for your everyday tasks."]];
  const images=["assets/yosemite.jpg","assets/denali.jpg","assets/wilderness.jpg"];
  let sourceRequest=0;
  const history=[],esc=value=>String(value).replace(/[&<>"']/g,c=>({"&":"&amp;","<":"&lt;",">":"&gt;",'"':"&quot;","'":"&#39;"}[c]));
  const icon=glyph=>`<span class="icon" aria-hidden="true">${I[glyph]||I.info}</span>`;
  const chevron=()=>`<span class="chevron" aria-hidden="true"><svg viewBox="0 0 14 24" fill="none" stroke="currentColor" stroke-width="2.5" stroke-linecap="round" stroke-linejoin="round"><path d="m3 3 8 9-8 9"/></svg></span>`;
  const btn=(label,action,css="button",attributes="")=>`<button class="${css}" data-action="${action}" ${attributes}>${label}</button>`;
  const badge=(glyph,color="#4b81b0")=>`<span class="badge" style="background:${color}">${icon(glyph)}</span>`;
  const appIcon=app=>`<span class="app-icon ${app.color}">${icon(app.glyph)}</span>`;
  const getApp=name=>apps.find(app=>app.name===name)||apps[0];
  const appTile=(app,details=false)=>btn(`${appIcon(app)}<span><strong>${app.name}</strong>${details?`<small>${app.subtitle}</small>`:""}</span>`,"open-app","app-tile",`data-name="${esc(app.name)}"`);
  function header(back=false){
    return `<header class="app-header">${back?btn(icon("chevL")+"Home","home","text-button"):`<div class="brand"><span class="brand-mark"></span>immortal<span class="room-name">${esc(state.room)}</span></div>`}<div class="header-actions">${back?`<span class="header-status">${esc(state.room)}</span>`:`<span class="header-status">${icon("wifi")}${state.scenario==="offline"?"Offline · saved content":"Connected"}</span>${state.screen==="home"?btn("Customize","edit","text-button"):""}${btn(icon("search"),"global-search","round-button",'aria-label="Search apps and settings"')}`}${back?btn(state.theme==="light"?"Dark appearance":"Light appearance","theme","text-button"):btn(icon("gear"),"settings","round-button",'aria-label="Settings"')}</div></header>`;
  }
  function dock(){
    return `<nav class="dock" aria-label="Main navigation">${[["home","Home","homeS"],["apps","Apps","gridapps"],["photos","Photos","photo"],["tools","Tools","tools"]].map(([screen,label,glyph])=>btn(icon(glyph)+label,screen,state.screen===screen?"selected":"",state.screen===screen?'aria-current="page"':"")).join("")}</nav>`;
  }
  function heading(title,description="",action=""){
    return `<div class="page-heading"><div><h1>${title}</h1>${description?`<p>${description}</p>`:""}</div>${action}</div>`;
  }
  function shell(content,css="",withDock=true){
    return `<main class="app-screen ${css}">${header()}<div class="screen-body">${content}</div>${withDock?dock():""}</main>`;
  }
  function offline(){
    return state.scenario==="offline"?`<div class="status-banner">${icon("wifi")}<span>Offline. Your photos and last weather update are still available.</span>${btn("Connection help","connection-help","text-button")}</div>`:"";
  }
  function favorites(){
    return `<section class="favorite-section"><div class="section-heading"><h2>Favorite apps</h2>${btn("All apps","apps","text-button")}</div><div class="favorite-grid">${state.favorites.slice(0,6).map(name=>appTile(getApp(name))).join("")}</div></section>`;
  }
  function calendarEvents(){
    if(state.scenario==="empty"&&!state.calendarAdded)return `<div class="empty-card"><h3>Your day, at a glance</h3><p>Add a calendar to see what’s next.</p>${btn("Add a calendar","calendar","text-button")}</div>`;
    return `<div class="event-kicker">Next · 10:30 AM</div><div class="event"><span class="event-bar"></span><span><strong>Sunday farmers’ market</strong><small>Downtown · until 12:00 PM</small></span></div><div class="event"><span class="event-bar"></span><span><strong>Dinner with friends</strong><small>6:00 PM · at home</small></span></div>`;
  }
  function music(){
    if(state.scenario==="empty")return `<div class="empty-card"><h3>Make room for music</h3><p>Choose a player or tune into radio.</p>${btn("Choose a player","music-picker","text-button")}</div>`;
    return `<div class="music-row"><div class="album-art" aria-hidden="true"></div><span><strong>${state.track%2?"A quiet morning":"Sunday slow-down"}</strong><small>${state.playing?"Playing":"Ready to play"} · Living room</small></span></div><div class="music-buttons">${btn(icon("chevL"),"track-previous","",'aria-label="Previous track"')}${btn(state.playing?'<span class="pause" aria-hidden="true"></span>':icon("play"),"music-play","play",`aria-label="${state.playing?"Pause":"Play"} music"`)}${btn(`<span style="transform:rotate(180deg);display:flex">${icon("chevL")}</span>`,"track-next","",'aria-label="Next track"')}</div>`;
  }
  function quickControl(label,key){
    return btn(`${icon("bulb")}<strong>${label}</strong><small>${state[key]?"On":"Off"}</small>`,"quick-control",`quick-control ${state[key]?"on":""}`,`data-key="${key}" aria-pressed="${state[key]}" aria-label="${label}, ${state[key]?"on":"off"}"`);
  }
  function homeWidget(name){
    const title=(glyph,label,action="")=>`<h2 class="card-heading"><span>${icon(glyph)}${label}</span>${action}</h2>`;
    if(name==="Photos")return btn(`<img src="${images[state.photo]}" alt="Yosemite landscape"><span class="photo-copy"><strong>${esc(state.album)}</strong><small>${icon("photo")}Your photo display · 248 photos</small></span>`,"photos","home-card photo-card");
    if(name==="Weather")return `<section class="home-card weather-card">${title("weather","San Francisco")}<div class="weather-main"><strong>68°</strong>${icon("weather")}</div><p class="weather-summary">A little sunshine</p><p class="weather-low">H: 71° &nbsp; L: 58°${state.scenario==="offline"?" · Saved 9:24 AM":""}</p></section>`;
    if(name==="Today")return `<section class="home-card">${title("cal","Today")}${calendarEvents()}</section>`;
    if(name==="Music")return `<section class="home-card">${title("music","Now playing",btn(icon("plus"),"music-picker","small-icon-button",'aria-label="Choose music player"'))}${music()}</section>`;
    if(name==="Quick controls")return `<section class="home-card">${title("homeS","Your home",btn("Edit","ha","small-icon-button"))}${state.scenario==="empty"?`<div class="empty-card"><h3>Bring your home together</h3><p>Connect your Home Assistant server.</p>${btn("Connect","ha","text-button")}</div>`:`<div class="quick-grid">${quickControl("Living room","lights")}${quickControl("Reading lamp","lamp")}</div>`}</section>`;
    const sample=({ "World clock":["5:41","London · afternoon"],Countdown:["12","Days to your next adventure"],Alarm:["7:00","Next alarm · weekdays"],"To-do":["3","Things left to do"],News:["Today","Your selected headlines"],Stocks:["MSFT","Your selected market watchlist"],Crypto:["BTC","Your selected assets"],Options:["Positions","Your options summary"],Timers:["05:00","Kitchen timer"]})[name]||["Ready","Your selected content"];
    return `<section class="home-card">${title(widgets.find(w=>w[0]===name)?.[1]||"clock",name)}<div class="weather-main"><strong style="font-size:70px;line-height:84px">${sample[0]}</strong></div><p class="weather-low">${sample[1]}</p></section>`;
  }
  function home(){
    const background=state.wallpaper==="Solid color"?"background:#284a4f":state.wallpaper==="Yosemite"?`background:linear-gradient(0deg,#11292ee6,#11292ecc),url('${images[0]}') center/cover`:"";
    return `<main class="app-screen home-screen" style="${background}">${header()}<div class="home-body"><div class="greeting"><div><h1>Good morning.</h1><p>A quiet Sunday. Everything within reach.</p></div>${state.switches.showTime?`<div class="home-time"><strong>9:41</strong><span>Sunday<br>October 4</span></div>`:""}</div>${offline()}<div class="home-grid">${state.widgets.slice(0,5).map(homeWidget).join("")}</div>${favorites()}</div>${dock()}</main>`;
  }
  function settingRow(key,label,sub="",value="",action=""){
    value=state.values[key]||value;
    const element=action?"button":"div";
    return `<${element} id="setting-${key}" class="settings-row ${state.highlight===key?"row-highlight":""}" ${action?`data-action="${action}"`:""}><span class="row-label"><strong>${label}</strong>${sub?`<small>${sub}</small>`:""}</span><span class="row-value">${value}${action?chevron():""}</span></${element}>`;
  }
  function switchRow(key,label,sub=""){
    return `<label class="settings-row ${state.highlight===key?"row-highlight":""}" id="setting-${key}"><span class="row-label"><strong>${label}</strong>${sub?`<small>${sub}</small>`:""}</span><span class="toggle"><input type="checkbox" role="switch" aria-label="${label}" data-switch="${key}" ${state.switches[key]?"checked":""}><span class="switch-track" aria-hidden="true"></span></span></label>`;
  }
  const segment=(key,choices,value)=>`<span class="segment" role="group" aria-label="${key}">${choices.map(choice=>btn(choice,"segment",choice===value?"selected":"",`data-key="${key}" data-value="${choice}" aria-pressed="${choice===value}"`)).join("")}</span>`;
  const group=(title,content,note="")=>`<h2 class="group-title">${title}</h2><div class="settings-group">${content}</div>${note?`<p class="settings-note">${note}</p>`:""}`;
  const detailHeading=(title,sub,button="")=>`<div class="detail-heading"><div><h1>${title}</h1><p>${sub}</p></div>${button}</div>`;
  const breadcrumb=(title,action)=>`<div class="breadcrumb">${btn(icon("chevL")+title,action,"text-button")}</div>`;
  function categoryNav(){
    return categories.map(([key,label,glyph,color])=>btn(`${badge(glyph,color)}${label}`,"category",state.category===key?"selected":"",`data-category="${key}" ${state.category===key?'aria-current="page"':""}`)).join("");
  }
  function settingsShell(content){
    return `<main class="app-screen settings-screen">${header(true)}<div class="settings-layout"><aside class="settings-sidebar"><h1>Settings</h1><label class="search">${icon("search")}<input id="settings-search" type="search" placeholder="Search settings" aria-label="Search settings"></label><nav class="sidebar-nav" id="category-nav" aria-label="Settings categories">${categoryNav()}</nav><div class="sidebar-device">${esc(state.room)}<small>Portal+ · Immortal 1.64</small></div></aside><section class="detail-body" id="detail-body">${content}</section></div></main>`;
  }
  function sourceSummary(){
    return `<div class="source-summary">${badge("photo")}<span><strong>${state.connected?state.source:"Local photos"}</strong><small>${esc(state.album)} · 248 photos${state.scenario==="offline"?" · saved on device":""}</small></span><span class="status-pill">${state.scenario==="offline"?"Cached":"Ready"}</span></div>`;
  }
  function settingsDetail(){
    if(state.category==="appearance")return detailHeading("Appearance","Make the display feel at home in your room.")+
      `<div class="appearance-preview"><div class="preview-block"><div class="preview-photo"><img src="${images[0]}" alt="Wallpaper preview"><span>9:41</span></div><div><h2>Your everyday display</h2><p class="sample-label">Comfortable type. A clear, calm surface.</p></div></div>${btn("Preview home","home","button")}</div>`+
      group("Display",settingRow("brightness","Brightness","Comfortable to read, day or night",`<label class="slider"><span class="slider-control" style="--value:${state.brightness}%"><span class="slider-track"><span class="slider-fill"></span><span class="slider-thumb"></span></span><input type="range" min="0" max="100" value="${state.brightness}" data-slider="brightness" aria-label="Brightness"></span><output>${state.brightness}%</output></label>`)+settingRow("theme","Appearance","Choose a familiar light or calm dark surface",segment("theme",["Light","Dark"],state.theme==="light"?"Light":"Dark")))+
      group("Personalize",settingRow("wallpaper","Wallpaper","Scenic, ambient, or a solid color",state.wallpaper,"wallpaper")+switchRow("showTime","Time on home","Always visible at a glance")+settingRow("density","App & widget size","Keep everyday actions easy to reach",state.density,"density"))+
      `<p class="settings-note">Appearance choices apply immediately. Your home layout stays in place.</p>`;
    if(state.category==="photos")return detailHeading("Photos & screensaver","Your memories, with a display that quietly takes care of itself.",btn("Preview","photos","button"))+sourceSummary()+
      group("Your photos",settingRow("source","Photo sources","Manage your albums and connections",state.connected?state.source:"Local folder","sources")+settingRow("interval","Change photo every","Give each moment a little time",state.interval,"interval")+switchRow("shuffle","Shuffle photos","Show your photos in a different order"))+
      group("Clock & display",settingRow("clock-face","Clock face","Preview the clock before choosing",state.clock,"clock-picker")+switchRow("clock","Show clock","Visible over your photos")+settingRow("idle","Start screensaver","When the Portal is resting",`After ${state.idle}`,"idle"))+
      group("More options",settingRow("photo-advanced","Advanced photo options","Fit, transitions, overnight schedule, and overlays","","photo-advanced"));
    if(state.category==="home")return detailHeading("Home & widgets","Choose the things you want within reach.")+
      group("Your home",settingRow("home-layout","Customize home","Move, add, or remove widgets",`${state.widgets.length} widgets`,"edit")+settingRow("favorites","Favorite apps","A short list for everyday use",`${state.favorites.length} apps`,"favorites"))+
      group("At a glance",settingRow("calendar","Calendar","Your events and daily almanac",state.calendarAdded?"3 calendars":"2 calendars","calendar")+settingRow("world-clock","World clock","The time in places you care about",state.worldCities.join(", "),"world-clock")+settingRow("countdown","Countdown","Days until something special","Next adventure","countdown")+settingRow("quickbar","Quick buttons","Household actions and shortcuts","2 buttons","quick-buttons")+settingRow("weather","Weather","Location and temperature units","San Francisco · °F","weather"));
    if(state.category==="sound")return detailHeading("Sound & wake","Gentle cues and music for the rhythm of your day.")+
      group("Everyday sounds",switchRow("chime","Hourly chime","A quiet reminder as the hour changes")+settingRow("chime-options","Chime sound & schedule","Volume, sound, and quiet hours","Soft bell","chime-options")+switchRow("touch","Touch sounds","Hear when a tap is recognized")+settingRow("welcome","Welcome greeting","What you see when the display wakes","Good morning","welcome"))+
      group("Mornings",settingRow("wake","Sunrise & wake","A gentle light before your day begins",state.switches.wake?"7:00 AM":"Off","wake"))+
      group("Music",settingRow("multiroom","Multi-room audio","Music across your home","Not connected","multiroom"));
    if(state.category==="connections")return detailHeading("Connections","Clear setup and status for the things your Portal connects to.")+
      group("Your home",settingRow("ha","Home Assistant","Connect your server and choose your controls",state.scenario==="empty"?"Not connected":state.scenario==="offline"?"Offline":"Connected","ha")+settingRow("mqtt","MQTT publishing","Share Portal state with your smart home",state.switches.mqtt?"Enabled":"Off","mqtt"))+
      group("Remote control",switchRow("remote","Phone remote","Control this display from a phone on your Wi-Fi")+settingRow("phone","Connect your phone","Open the remote on the same network","Same Wi-Fi","remote"))+
      group("Media connections",settingRow("photo-services","Photo services","Local files, Immich, SMB, or WebDAV","Manage","sources")+settingRow("audio","Music & rooms","Players and synchronized audio","Manage","multiroom"));
    if(state.category==="access")return detailHeading("Input & accessibility","Comfortable ways to read and interact with this display.")+
      group("Navigation",switchRow("gesture","Back gesture","Keep a visible Back action as well")+settingRow("remote-focus","Remote navigation","A clear highlight for the selected control","Preview","focus-preview"))+
      group("System accessibility",settingRow("text-size","Text size & accessibility","Managed by Android system settings","Opens system","system-accessibility"))+
      `<p class="settings-note">The preview uses the Portal’s standard font scale. Larger system text must also be verified during native acceptance.</p>`;
    if(state.category==="device")return detailHeading("Device","The essentials about your Portal, without the technical clutter.")+
      group("Identity",settingRow("name","Device name","Shown when connecting your phone",esc(state.room),"rename")+switchRow("autoStart","Start on boot","Return to Immortal when the Portal starts"))+
      group("Rest & power",settingRow("sleep","Sleep timer","Dim the room on your schedule","Not scheduled","sleep"))+
      group("About",settingRow("version","Immortal version","Independent community software","1.64")+settingRow("updates","Software updates","Keep your display experience current","Up to date","updates")+settingRow("health","Device health","Connection, storage, and diagnostics","View","health"));
    return detailHeading("Advanced","System controls and specialist options, when you need them.")+
      group("System",settingRow("android","Android settings","Wi-Fi, Bluetooth, and system preferences","Opens system","android")+switchRow("statusBar","Show system status bar","Extra system information above your home"))+
      group("Management",settingRow("fleet","Fleet management","Device management and agent configuration","Agent settings","fleet")+settingRow("diagnostics","Diagnostics","Inspect the display’s current state","View","health"))+
      `<p class="settings-note">Common controls remain in their primary category.</p>`;
  }
  function appsScreen(){
    return shell(heading("Your apps.","Everything you’ve installed, easy to find.",`<label class="search">${icon("search")}<input type="search" id="app-search" placeholder="Search apps" aria-label="Search apps"></label>`)+
      `<div class="button-group">${btn("All apps","apps","primary")}${btn("App Store","store","button")}${btn("Edit favorites","favorites","text-button")}</div><section class="app-grid" id="app-grid" aria-label="Installed apps">${apps.map(app=>appTile(app,true)).join("")}</section>`);
  }
  function folderScreen(){
    return shell(breadcrumb("App library","apps")+`<div class="folder-layout">${heading("Utilities","A little help for your day.",btn("Edit folder","edit-folder","text-button"))}<div class="app-grid">${["Timer","Intercom","Stories","Camera viewer"].map(name=>appTile(getApp(name),true)).join("")}</div></div>`);
  }
  function installButton(name){
    return btn(state.installed.has(name)?"Open":state.installing.has(name)?"Adding…":"Get","install","button",`data-name="${name}" ${state.installing.has(name)||state.scenario==="offline"?"disabled":""}`);
  }
  function storeScreen(){
    return shell(`<section class="store-feature"><div><h1>More life for your Portal.</h1><p>Useful apps for listening, watching, and making home a little easier.</p></div><div class="feature-icons">${["VLC","Home Assistant","Radio"].map(name=>appIcon(getApp(name))).join("")}</div></section>${offline()}<div class="section-heading"><h2>Everyday essentials</h2>${btn("Updates","updates","text-button")}</div><div class="store-grid">${["VLC","Radio","Home Assistant","Browser"].map(name=>{const app=getApp(name);return `<article class="store-card">${appIcon(app)}<button class="product-label" data-action="app-detail" data-name="${name}"><strong>${name}</strong><small>${app.subtitle}</small></button>${installButton(name)}</article>`;}).join("")}</div><p class="settings-note">Selected for Portal. Each app’s details show its source and device requirements.</p>`);
  }
  function appDetail(){
    const app=getApp(state.currentApp);
    return shell(breadcrumb("App Store","store")+`<section class="detail-hero">${appIcon(app)}<div><h1>${app.name}</h1><p>${app.subtitle}</p><div class="button-group">${installButton(app.name)}${btn("Source & requirements","app-source","text-button")}</div></div></section><div class="feature-grid"><article class="information-card"><h2>${app.name==="VLC"?"Your media, on a bigger screen.":"A little more possibility."}</h2><p>${app.name==="VLC"?"Play videos, music, and network streams from a familiar player. Immortal keeps the way back home close.":"Add this app to your Portal’s library and keep your everyday favorites easy to find."}</p></article><article class="information-card"><h2>Before you install</h2><ul><li>Source and version come from the app catalog.</li><li>Device requirements are checked before installation.</li><li>Existing app data stays in place during an update.</li></ul></article></div><h2 class="group-title">Installation</h2><div class="settings-group">${settingRow("install-source","Source","The catalog lists the publisher and APK source",app.name==="VLC"?"VideoLAN":"Catalog source")}${settingRow("requirements","Device requirements","Portal+ · Android 10 · ARM","Check before install")}</div>`);
  }
  function toolsScreen(){
    const tools=[["Timer","timer","A little time, set aside.","timer"],["Reading light","bulb","A soft glow for your room.","reading-light"],["Intercom","megaphone","Talk across the house.","intercom"],["Bedtime stories","notes","Something calm for the evening.","stories"],["Camera viewer","film","Keep an eye on your home.","cameras"],["Conversions","calc","Quick everyday measurements.","converter"]];
    return shell(heading("A little help, right here.","Useful tools for your home and your day.")+`<div class="tool-grid">${tools.map(([label,glyph,sub,action])=>btn(`${icon(glyph)}<strong>${label}</strong><small>${sub}</small>`,action,"tool-card")).join("")}</div>`);
  }
  function formatTime(seconds){return `${String(Math.floor(seconds/60)).padStart(2,"0")}:${String(seconds%60).padStart(2,"0")}`;}
  function timerScreen(){
    return shell(breadcrumb("Tools","tools")+heading("A little time, set aside.","Choose a duration. The display will let you know when it’s ready.")+`<section class="timer-panel"><div class="segment" aria-label="Timer duration">${[5,10,15,30].map(minutes=>btn(`${minutes} min`,"timer-set",minutes===state.timerMinutes?"selected":"",`data-minutes="${minutes}" ${state.timerRunning?"disabled":""}`)).join("")}</div><span class="timer-digits" id="timer-digits">${formatTime(state.timerLeft)}</span><div class="button-group">${btn(state.timerRunning?"Pause":"Start timer","timer-toggle","primary")}${btn("Reset","timer-reset","button")}</div><p>${state.timerRunning?"Your timer is running.":"A clear countdown, with a simple way to pause."}</p></section>`);
  }
  function editScreen(){
    return shell(heading("A home that’s yours.","Keep what matters. Make room for the rest.")+`<div class="editing-toolbar"><div><h2>Customize home</h2><p>Move and remove widgets. Your other settings stay in place.</p></div><div class="button-group">${btn(icon("plus")+"Add widget","widget-library","button")}${btn("Done","home","primary")}</div></div><div class="editor-layout"><section class="editor-grid">${state.widgets.map((name,index)=>`<article class="widget-edit">${badge(widgets.find(w=>w[0]===name)?.[1]||"clock")}<h3>${name}</h3><div class="button-group">${btn(name==="Photos"?"Large slot":"Move","move-widget","button",`data-index="${index}" aria-label="Move ${name}" ${name==="Photos"?"disabled":""}`)}${btn("Remove","remove-widget","text-button",`data-index="${index}" aria-label="Remove ${name}"`)}</div></article>`).join("")}</section><aside class="editor-aside"><h2>Give everything its place.</h2><p>Five useful widgets keep this layout easy to read. The photo widget uses the large slot; the other widgets can be reordered with Move.</p><h3 style="margin-bottom:20px">Home density</h3>${["Comfortable","Compact"].map(value=>btn(`<span><strong>${value}</strong><small>${value==="Comfortable"?"More breathing room, larger controls":"A more closely spaced home"}</small></span><span class="choice-end">${value===state.density?"✓":""}</span>`,"density-choice",`choice ${value===state.density?"selected":""}`,`data-value="${value}" aria-pressed="${value===state.density}"`)).join("")}<p class="small">Your home order and installed apps are preserved when you change appearance.</p></aside></div>`, "",false);
  }
  function widgetLibrary(){
    return shell(breadcrumb("Customize home","edit")+heading("Make room for something useful.","Choose a widget. You can always remove it later.")+`<div class="widget-library">${widgets.map(([name,glyph,sub])=>btn(`${icon(glyph)}<strong>${name}</strong><small>${sub}</small><span class="pill">${state.widgets.includes(name)?"Added":state.widgets.length>=5?"Replace a widget":"Add widget"}</span>`,"add-widget","widget-choice",`data-name="${name}" ${state.widgets.includes(name)?"disabled":""}`)).join("")}</div>`,"",false);
  }
  function sourcesScreen(){
    return settingsShell(breadcrumb("Photos & screensaver","category-photos")+detailHeading("Where are your photos?","Choose a source. Your current album stays available while you set up another.")+
      `<div class="source-grid">${[["Local folder","folder","Photos already on your Portal","#6b947d"],["Immich","photo","Your own photo library","#5c82c1"],["SMB share","folder","A shared folder on your network","#a28758"],["WebDAV","globe","A compatible photo server","#8d79ad"]].map(([name,glyph,sub,color])=>btn(`${badge(glyph,color)}<span><strong>${name}</strong><small>${sub}</small></span>${chevron()}`,"source-choice","source-choice",`data-source="${name}"`)).join("")}</div>`+
      group("Shared albums",settingRow("shared-album","Shared album link","Use a supported public album URL","Connect","shared-album"))+
      `<p class="settings-note">Only selected albums are added to this display. Cached photos stay available when your server is offline.</p>`);
  }
  function connectScreen(){
    const smb=state.source==="SMB share",shared=state.source==="Shared album";
    return settingsShell(breadcrumb("Photo sources","sources")+detailHeading(`Connect ${state.source}`,shared?"Add a supported shared-album link.":"Bring your photos into one calm space.")+
      `<div class="form-grid"><form class="form-panel" id="source-form" novalidate><label class="field"><span>${smb?"Share address":shared?"Album link":"Server address"}</span><input id="source-address" name="address" type="url" placeholder="${smb?"smb://photos.local/family":shared?"https://…":"https://photos.example.com"}" autocomplete="off" aria-describedby="source-error"></label>${smb?`<label class="field"><span>Username</span><input name="username" placeholder="Your share username" autocomplete="off"></label>`:""}${!shared?`<label class="field"><span>${state.source==="Immich"?"API key":"Password"}</span><span class="input-with-action"><input name="credential" type="${state.passwordVisible?"text":"password"}" placeholder="${state.source==="Immich"?"Your Immich API key":"Your server password"}" autocomplete="off">${btn(state.passwordVisible?"Hide":"Show","show-password","button")}</span></label>`:""}<p class="form-error" id="source-error" role="alert">${state.sourceError}</p><div class="button-group"><button class="primary" type="submit" ${state.testing?"disabled":""}>${state.testing?"Testing connection…":shared?"Check album":"Test connection"}</button>${btn("Cancel","sources","text-button")}</div><p class="form-help">Your existing photo source is kept until you confirm a new album.</p></form><aside class="information-card"><h2>${state.source==="Immich"?"Find your API key":"A simple connection"}</h2><ol class="help-list">${state.source==="Immich"?'<li>Open your Immich account settings.</li><li>Create an API key with access to your selected albums.</li><li>Copy the server address and key here.</li>':smb?'<li>Use a share on the same network.</li><li>Enter the share address and your account.</li><li>Choose the photos you want to display.</li>':'<li>Copy your service address.</li><li>Add the account details if required.</li><li>Choose the photos you want to display.</li>'}</ol><p class="connection-result">Test the connection before choosing an album. An error appears beside the affected field.</p></aside></div>`);
  }
  function albumsScreen(){
    return settingsShell(breadcrumb("Photo source","sources")+detailHeading("Choose what’s on display.","Select one or more albums. You can change them later.")+
      `<div class="source-summary">${badge("photo")}<span><strong>${state.source} is ready</strong><small>Choose the moments you want on your Portal.</small></span><span class="status-pill">Connected</span></div><div class="album-grid">${[["Into the wild",248,images[0]],["Mountain mornings",86,images[1]],["A quieter place",112,images[2]]].map(([name,count,image])=>btn(`<img src="${image}" alt=""><span class="album-label"><span><strong>${name}</strong><small>${count} photos</small></span><span aria-hidden="true">${state.selectedAlbums.includes(name)?"✓":""}</span></span>`,"album-select",`album-option ${state.selectedAlbums.includes(name)?"selected":""}`,`data-album="${name}" aria-pressed="${state.selectedAlbums.includes(name)}"`)).join("")}</div><div class="flow-footer"><p>${state.selectedAlbums.length} ${state.selectedAlbums.length===1?"album":"albums"} selected</p>${btn("Use selected albums","albums-confirm","primary",state.selectedAlbums.length?"":"disabled")}</div>`);
  }
  function clockPicker(){
    return settingsShell(breadcrumb("Photos & screensaver","category-photos")+detailHeading("A clock that feels like home.","Choose a clear digital clock or let your photos take the lead.",btn("Preview","photos","button"))+
      `<div class="clock-grid">${["Digital","Minimal","Photo only"].map((name,index)=>btn(`<div class="clock-sample"><img src="${images[index]}" alt=""><span style="${name==="Minimal"?"font-size:54px;line-height:64px":""}">${name==="Photo only"?"": "9:41"}</span></div><div class="clock-label"><strong>${name}</strong><span aria-hidden="true">${state.clock===name?"✓":""}</span></div>`,"clock-select",`clock-option ${state.clock===name?"selected":""}`,`data-clock="${name}" aria-pressed="${state.clock===name}"`)).join("")}</div>`+
      group("Clock details",switchRow("showDate","Show date","Day and date below the clock")+settingRow("clock-size","Clock size","Keep it easy to read at a distance","Large","clock-size"))+
      `<div class="flow-footer"><p>The clock uses the same font assets as the Portal.</p>${btn("Done","category-photos","primary")}</div>`);
  }
  function photoScreen(){
    return `<main class="app-screen photo-display"><img src="${images[state.photo]}" alt="Scenic landscape from the selected album">${btn(icon("chevL")+"Home","home","button photo-back")}${state.switches.clock&&state.clock!=="Photo only"?`<div class="frame-clock" style="${state.clock==="Minimal"?"font-size:78px;line-height:94px":""}">9:41${state.switches.showDate?"<small>Sunday, October 4 · 68°</small>":""}</div>`:""}<div class="frame-caption">${esc(state.album)}</div></main>`;
  }
  function wallpaperScreen(){
    return settingsShell(breadcrumb("Appearance","category-appearance")+detailHeading("Set a calm tone.","Choose a scenic photo, ambient colors, or a simple solid background.")+
      `<div class="clock-grid">${["Yosemite","Ambient sky","Solid color"].map((name,index)=>btn(`<div class="clock-sample" style="${index===1?"background:linear-gradient(180deg,#4b6e88,#bdc5ab)":index===2?"background:#284a4f":""}">${index===0?`<img src="${images[0]}" alt="Yosemite">`:""}<span>9:41</span></div><div class="clock-label"><strong>${name}</strong><span aria-hidden="true">${state.wallpaper===name?"✓":""}</span></div>`,"wallpaper-select",`clock-option ${state.wallpaper===name?"selected":""}`,`data-value="${name}" aria-pressed="${state.wallpaper===name}"`)).join("")}</div><div class="flow-footer"><p>Your photo-display album stays separate from the home background.</p>${btn("Done","category-appearance","primary")}</div>`);
  }
  function calendarScreen(){
    return settingsShell(breadcrumb("Home & widgets","category-home")+detailHeading("Your calendar, together.","Events from your calendars, with a clear view of what’s next.")+
      group("Your calendars",settingRow("family-calendar","Family","Public iCalendar feed","Connected","calendar-edit")+settingRow("personal-calendar","Personal","Public iCalendar feed","Connected","calendar-edit"))+
      `<div class="button-group" style="margin:26px 0">${btn(icon("plus")+"Add calendar","calendar-add","button")}</div>`+
      group("Display",settingRow("calendar-range","Date range","Choose how far ahead to look","7 days","calendar-range")+settingRow("calendar-size","Widget size","A comfortable amount of detail","Medium","calendar-size"))+
      group("Daily content",settingRow("almanac","Almanac & calendar packs","Holidays, name days, and daily content","Choose packs","almanac")));
  }
  function worldClock(){
    const zones={"London":"Europe/London","Tokyo":"Asia/Tokyo","New York":"America/New_York","Mumbai":"Asia/Kolkata","Sydney":"Australia/Sydney"},moment=new Date("2026-10-04T16:41:00Z");
    const cityTime=city=>new Intl.DateTimeFormat("en-US",{timeZone:zones[city],hour:"numeric",minute:"2-digit",hour12:true}).format(moment);
    return settingsShell(breadcrumb("Home & widgets","category-home")+detailHeading("The time where it matters.","Keep the places you care about close.",btn(icon("plus")+"Add city","city-add","button"))+
      `<div class="world-clock-grid">${state.worldCities.map(city=>`<article class="world-clock-card"><h2>${esc(city)}</h2><strong>${cityTime(city)}</strong><small>${new Intl.DateTimeFormat("en-US",{timeZone:zones[city],weekday:"long"}).format(moment)}</small><div style="margin-top:24px">${btn("Remove","city-remove","text-button",`data-city="${esc(city)}"`)}</div></article>`).join("")}</div>`);
  }
  function wakeScreen(){
    return settingsShell(breadcrumb("Sound & wake","category-sound")+detailHeading("A gentler start.","Let the room brighten gradually before your wake time.")+
      group("Sunrise wake light",switchRow("wake","Enable wake light","Brighten the display before your day begins")+settingRow("wake-time","Wake time","The time you want the room ready","7:00 AM","wake-time")+settingRow("wake-days","Repeat","Choose the days you need it","Weekdays","wake-days"))+
      group("Light",settingRow("wake-duration","Brighten over","A gradual change before your wake time","20 minutes","wake-duration")+settingRow("wake-color","Light color","A warm start to the morning","Warm white","wake-color"))+
      `<div class="button-group" style="margin-top:28px">${btn("Preview wake light","wake-preview","button")}</div>`);
  }
  function haScreen(){
    return settingsShell(breadcrumb("Connections","category-connections")+detailHeading("Your home, within reach.","Connect Home Assistant and choose the controls that belong here.")+
      (state.scenario==="offline"?offline():"")+
      `<div class="form-grid"><form class="form-panel" id="ha-form" novalidate><label class="field"><span>Server address</span><input name="address" type="url" placeholder="http://homeassistant.local:8123" autocomplete="off"></label><label class="field"><span>Access token</span><input name="credential" type="password" placeholder="Long-lived access token" autocomplete="off"></label><p class="form-error" id="ha-error" role="alert"></p><div class="button-group"><button type="submit" class="primary">Test connection</button>${btn("Choose controls","ha-controls","button")}</div></form><aside class="information-card"><h2>Your everyday controls</h2><p>Connect directly to your Home Assistant server. Then pin the lights, switches, scenes, and status you use most.</p><p style="margin-top:25px">MQTT publishing is a separate connection, available under Connections.</p></aside></div>`);
  }
  function remoteScreen(){
    return settingsShell(breadcrumb("Connections","category-connections")+detailHeading("Your phone is a remote.","Connect on the same Wi-Fi to manage this display.")+
      `<div class="pair-card"><div><h2>Open the remote on your phone</h2><p>Type this address into your phone’s browser:</p><p style="color:var(--accent);font-size:30px;line-height:42px">http://192.168.0.70:8723/remote/ui</p><p>The address changes if your Portal joins another network.</p></div><div class="information-card"><h3>On the same network</h3><p>Keep the Portal and your phone connected to the same Wi-Fi.</p>${btn("Connection help","connection-help","text-button")}</div></div>`+
      group("Remote access",switchRow("remote","Enable phone remote","Keep your controls accessible from your phone"))+
      `<p class="settings-note">Pairing and access rules continue to use Immortal’s existing remote service.</p>`);
  }
  function healthScreen(){
    return settingsShell(breadcrumb("Device","category-device")+detailHeading("A little clarity about your display.","Useful status first. Detailed diagnostics when you need them.")+
      group("Identity",settingRow("model","Model","","Portal+")+settingRow("android-version","Android version","","10 · API 29")+settingRow("app-version","Immortal version","","1.64"))+
      group("Status",settingRow("network","Connection","Sample connection status",state.scenario==="offline"?"Offline":"Wi-Fi connected")+settingRow("storage","Storage","Sample usage for this design","3.2 GB available")+settingRow("screensaver-state","Photo display","Your selected photo source","Local folder"))+
      group("Diagnostics",settingRow("logs","Recent logs","Detailed information for troubleshooting","View","logs")));
  }
  function modalMarkup(){
    if(!state.modal)return "";
    const m=state.modal;
    return `<div class="modal-layer"><section class="modal" role="dialog" aria-modal="true" aria-labelledby="modal-title"><div class="modal-heading"><h2 id="modal-title">${m.title}</h2>${btn(icon("x"),"close-modal","round-button",'aria-label="Close"')}</div>${m.description?`<p>${m.description}</p>`:""}${m.content}${m.footer?`<div class="modal-footer">${m.footer}</div>`:""}</section></div>`;
  }
  function fit(){
    const scale=Math.min(innerWidth/PORTAL_SPEC.target.dpWidth,innerHeight/PORTAL_SPEC.target.dpHeight);
    device.style.transform=`scale(${scale})`;
    device.style.left=`${(innerWidth-1920*scale)/2}px`;device.style.top=`${(innerHeight-1280*scale)/2}px`;
  }
  function announceNavigation(){
    if(parent!==window)parent.postMessage({type:"portal-mock-navigation",screen:state.screen,category:state.category,theme:state.theme,scenario:state.scenario},location.origin);
  }
  function render(){
    Object.entries(PORTAL_SPEC[state.theme]).forEach(([key,value])=>device.style.setProperty("--"+key,value));
    const screens={home,apps:appsScreen,folder:folderScreen,store:storeScreen,"app-detail":appDetail,tools:toolsScreen,timer:timerScreen,edit:editScreen,"widget-library":widgetLibrary,settings:()=>settingsShell(settingsDetail()),sources:sourcesScreen,connect:connectScreen,albums:albumsScreen,"clock-picker":clockPicker,photos:photoScreen,wallpaper:wallpaperScreen,calendar:calendarScreen,"world-clock":worldClock,wake:wakeScreen,ha:haScreen,remote:remoteScreen,health:healthScreen};
    device.classList.toggle("dark",state.theme==="dark");
    device.innerHTML=(screens[state.screen]||home)()+modalMarkup()+(state.toast?`<div class="toast" role="status" aria-live="polite">${esc(state.toast)}</div>`:"");
    device.classList.toggle("compact",state.density==="Compact");
    document.title=`Immortal — ${state.screen} / Portal+`;
    fit();
  }
  function go(screen,category){
    if(state.screen==="connect"&&screen!=="connect"){sourceRequest++;state.testing=false;}
    history.push({screen:state.screen,category:state.category});
    state.screen=screen;if(category)state.category=category;state.modal=null;state.highlight="";
    if(["sources","connect","albums","clock-picker"].includes(screen))state.category="photos";
    if(screen==="wallpaper")state.category="appearance";
    if(["calendar","world-clock"].includes(screen))state.category="home";
    if(screen==="wake")state.category="sound";
    if(["ha","remote"].includes(screen))state.category="connections";
    if(screen==="health")state.category="device";
    render();announceNavigation();
    const h1=device.querySelector("h1");if(h1){h1.tabIndex=-1;h1.focus({preventScroll:true});}
  }
  function back(){
    if(state.modal){state.modal=null;render();return;}
    const previous=history.pop();
    if(previous){Object.assign(state,previous);render();announceNavigation();}else go("home");
  }
  function showModal(title,description,content,footer=""){
    state.modal={title,description,content,footer};render();
    device.querySelector(".modal input,.modal button")?.focus({preventScroll:true});
  }
  function toast(message){
    clearTimeout(toast.timeout);state.toast=message;
    const old=device.querySelector(".toast");old?.remove();
    const node=document.createElement("div");node.className="toast";node.setAttribute("role","status");node.textContent=message;device.append(node);
    toast.timeout=setTimeout(()=>{state.toast="";device.querySelector(".toast")?.remove();},2800);
  }
  function choices(title,description,options,action="value-select",key=""){
    showModal(title,description,`<div class="modal-options">${options.map(option=>{
      const [label,sub,glyph]=Array.isArray(option)?option:[option,"","gear"];
      return btn(`${icon(glyph||"gear")}<span>${esc(label)}${sub?`<small>${esc(sub)}</small>`:""}</span>${chevron()}`,action,"modal-option",`data-value="${esc(label)}" data-key="${esc(key)}"`);
    }).join("")}</div>`);
  }
  const searchItems=[
    ["Brightness","appearance","brightness","screen dim light"],["Appearance","appearance","theme","light dark theme"],["Wallpaper","appearance","wallpaper","background"],["Photo sources","photos","source","immich smb webdav album"],["Clock face","photos","clock-face","digital clock style"],["Photo interval","photos","interval","slideshow seconds"],["Shuffle photos","photos","shuffle","random order"],["Calendar","home","calendar","events feed"],["World clock","home","world-clock","timezone city"],["Quick buttons","home","quickbar","quickbar shortcut"],["Countdown","home","countdown","days"],["Chime","sound","chime","bell hourly"],["Sunrise & wake","sound","wake","alarm morning"],["Touch sounds","sound","touch","tap"],["Multi-room audio","sound","multiroom","snapcast music"],["Home Assistant","connections","ha","home server smart"],["MQTT publishing","connections","mqtt","broker publish"],["Phone remote","connections","phone","phone connect control"],["Back gesture","access","gesture","accessibility input"],["Device name","device","name","rename room"],["Start on boot","device","autoStart","start power"],["Device health","device","health","version diagnostics"],["Android settings","advanced","android","wifi bluetooth system"]
  ];
  function searchResults(value){
    const q=value.trim().toLowerCase();
    return searchItems.filter(item=>(item[0]+" "+item[3]).toLowerCase().includes(q)).slice(0,7).map(([label,category,key])=>btn(`<span>${label}</span><small>${categories.find(c=>c[0]===category)[1]}</small>`,"search-setting","search-result",`data-category="${category}" data-key="${key}"`)).join("");
  }
  function secondaryAction(action,target){
    const key=target.closest(".settings-row")?.id.replace("setting-","")||action;
    const lists={
      interval:["Change photo every","Give each photo time to be seen.",["15 seconds","30 seconds","1 minute","2 minutes"],"interval"],
      idle:["Start screensaver","Choose when photo display begins.",["2 minutes","5 minutes","10 minutes"],"idle"],
      density:["Display density","Choose a comfortable amount of space.",["Comfortable","Compact"],"density"],
      "clock-size":["Clock size","Keep the clock readable at a distance.",["Medium","Large"],"clock-size"],
      "calendar-range":["Look ahead","Choose how far ahead the calendar looks.",["Today","3 days","7 days","Agenda"],"calendar-range"],
      "calendar-size":["Calendar size","Choose how much detail to show.",["Small","Medium","Large"],"calendar-size"],
      "wake-time":["Wake time","Choose the start of your morning.",["6:00 AM","6:30 AM","7:00 AM","7:30 AM"],"wake-time"],
      "wake-days":["Repeat wake light","Choose a schedule that fits your week.",["Weekdays","Every day","Weekends"],"wake-days"],
      "wake-duration":["Brighten over","A gentle change before your wake time.",["10 minutes","20 minutes","30 minutes"],"wake-duration"],
      "wake-color":["Wake-light color","Choose a warm or neutral light.",["Warm white","Neutral white","Soft amber"],"wake-color"],
      "music-picker":["Choose your music","Start listening in this room.",[["Radio","Internet stations","radio"],["VLC","Play from your library","vlc"],["Multi-room audio","Listen around the house","music"]],"player"],
      multiroom:["Music across your home","Choose a player or set up synchronized audio.",[["This Portal","Play in this room","music"],["Set up synchronized audio","Connect Snapcast or your music server","link"]],"multiroom"],
      "quick-buttons":["Your quick buttons","Choose the shortcuts that belong on home.",[["Room lights","Living room lighting","bulb"],["Reading lamp","A smaller pool of light","bulb"]],"quickbar"],
      "chime-options":["Hourly chime","Choose a sound for the passing hour.",["Soft bell","Wood chime","Quiet tone"],"chime-options"],
      weather:["Weather","Choose how the forecast is shown.",["San Francisco · °F","San Francisco · °C"],"weather"],
      "ha-controls":["Choose home controls","Pin the actions you want on this display.",[["Room lights","Living room lighting","bulb"],["Reading lamp","A reading corner","bulb"],["Evening scene","Wind down together","moonz"]],"ha-controls"],
      sleep:["Sleep timer","Let the display rest on your schedule.",["Off","30 minutes","1 hour","2 hours"],"sleep"],
      almanac:["Daily content","Choose the calendar packs you want.",["Public holidays","Name days","Daily astronomy","Seasonal content"],"almanac"]
    };
    if(lists[action]){const [title,sub,options,setting]=lists[action];choices(title,sub,options,action==="music-picker"?"player-select":"value-select",setting);return;}
    if(action==="connection-help"){choices("Your connection","Saved photos stay available while the network is offline.",[["Try again","Reconnect this display","wifi"],["Wi-Fi settings","Open Android’s network settings","gear"]],"connection-choice");return;}
    if(action==="favorites"){showModal("Your everyday favorites","Choose up to six apps for home.",`<div class="modal-options">${apps.map(app=>btn(`${appIcon(app)}<span>${app.name}<small>${state.favorites.includes(app.name)?"On home":"Add to home"}</small></span><span class="choice-end" aria-hidden="true">${state.favorites.includes(app.name)?"✓":""}</span>`,"favorite-toggle","modal-option",`data-name="${app.name}" aria-pressed="${state.favorites.includes(app.name)}"`)).join("")}</div>`,btn("Done","close-modal","primary"));return;}
    if(action==="global-search"){showModal("Find what you need","Search your apps and settings.",`<label class="search">${icon("search")}<input type="search" id="global-search" aria-label="Search apps and settings" placeholder="Try clock, photos, or radio"></label><div class="modal-options" id="global-results" style="margin-top:25px"></div>`);return;}
    if(action==="rename"){showModal("Device name","Choose something recognizable when connecting your phone.",`<label class="field"><span>Name</span><input id="room-name" value="${esc(state.room)}" maxlength="40"></label>`,btn("Save name","name-save","primary"));return;}
    if(action==="calendar-add"||action==="calendar-edit"){showModal(action==="calendar-add"?"Add calendar":"Edit calendar","Use a public or supported iCalendar feed.",`<form id="calendar-form" novalidate><label class="field"><span>Calendar name</span><input name="name" placeholder="Family"></label><label class="field"><span>iCalendar link</span><input name="url" type="url" placeholder="https://…/calendar.ics"></label><p id="calendar-error" class="form-error" role="alert"></p><div class="modal-footer"><button type="submit" class="primary">Save calendar</button></div></form>`);return;}
    if(action==="city-add"){choices("Add a city","Keep the places you care about close.",["New York","London","Tokyo","Mumbai","Sydney"].filter(city=>!state.worldCities.includes(city)),"city-select");return;}
    if(action==="photo-advanced"){showModal("Advanced photo options","A few more ways to make photo display comfortable.",`<div class="settings-group">${settingRow("crop","Fit & crop","Keep the full image or fill the display",state.crop,"crop")+settingRow("transitions","Transitions","Move gently from one photo to the next","Fade","transitions")+switchRow("overnight","Overnight schedule","Rest the display during quiet hours")+settingRow("burn-in","Burn-in protection","Automatic protection for long display sessions","Automatic")}</div>`,btn("Done","close-modal","primary"));return;}
    if(action==="crop"){choices("Fit & crop","Choose how photos use the display.",["Fill","Fit"],"value-select","crop");return;}
    if(action==="transitions"){choices("Photo transitions","Choose the pace of change.",["Fade","Still","Ken Burns"],"value-select","transitions");return;}
    if(action==="mqtt"){showModal("MQTT publishing","Share Portal state with your home automation. This is separate from direct Home Assistant controls.",`<div class="settings-group">${switchRow("mqtt","Enable MQTT","Publish selected Portal status to your broker")}</div><label class="field" style="margin-top:25px"><span>Broker address</span><input placeholder="mqtt://broker.local:1883"></label>`,btn("Save connection","connection-saved","primary"));return;}
    if(action==="countdown"){showModal("Countdown","Days until something special.",`<label class="field"><span>Title</span><input id="countdown-title" value="Next adventure"></label><label class="field"><span>Date</span><input type="text" value="October 16, 2026"></label>`,btn("Save countdown","countdown-save","primary"));return;}
    if(action==="welcome"){showModal("Welcome greeting","A familiar hello when your display wakes.",`<div class="settings-group">${switchRow("welcome","Show welcome greeting","A quiet overlay when your Portal wakes")}</div><label class="field" style="margin-top:25px"><span>Greeting</span><input value="Good morning"></label>`,btn("Save greeting","connection-saved","primary"));return;}
    if(action==="updates"){showModal("You’re up to date.","Immortal 1.64 is the current version in this preview.",`<div class="source-summary">${badge("info")}<span><strong>Immortal 1.64</strong><small>Last checked just now</small></span><span class="status-pill">Current</span></div>`,btn("Done","close-modal","primary"));return;}
    if(action==="android"||action==="system-accessibility"){showModal("Open Android settings","Wi-Fi, Bluetooth, text size, and system accessibility are managed by Android.",`<p class="connection-result">The system screen opens separately. Back returns you to Immortal.</p>`,btn("Return to Immortal","close-modal","primary"));return;}
    if(action==="reading-light"||action==="wake-preview"){showModal("A soft light for your room","Adjust the light, then return when you’re ready.",`<div style="height:270px;background:#fff0c9;border:1px solid #d4b98b;border-radius:24px"></div><p class="settings-note">Warm white · comfortable brightness</p>`,btn("Done","close-modal","primary"));return;}
    if(action==="focus-preview"){state.modal=null;render();device.querySelector(".sidebar-nav button.selected")?.focus();return;}
    if(action==="fleet"){showModal("Fleet management","Manage device identity, diagnostics, and the existing Fleet Agent.",`<div class="settings-group">${settingRow("fleet-port","Agent port","","8723")+settingRow("fleet-name","Device name","",esc(state.room))}</div>`,btn("Done","close-modal","primary"));return;}
    if(action==="logs"){showModal("Recent diagnostics","A focused view for troubleshooting.",`<div class="settings-group">${settingRow("log-app","App state","","Running")+settingRow("log-dream","Photo display","","Active")+settingRow("log-agent","Remote service","","Available")}</div>`,btn("Done","close-modal","primary"));return;}
    if(action==="app-source"){showModal("Source & requirements","The actual values come from Immortal’s existing catalog.",`<div class="settings-group">${settingRow("publisher","Publisher","",state.currentApp==="VLC"?"VideoLAN":"Catalog publisher")+settingRow("platform","Device","","Portal+ · Android 10")}</div>`,btn("Done","close-modal","primary"));return;}
    if(action==="intercom"){choices("Around the house","Choose a room on your local network.",[["Kitchen","Intercom on the same Wi-Fi","megaphone"],["Living room","This Portal","megaphone"]],"tool-choice","intercom");return;}
    if(action==="stories"){choices("Something calm for bedtime","Choose a story to read together.",[["The little lighthouse","A quiet adventure","notes"],["A walk among the stars","A gentle journey","notes"]],"tool-choice","stories");return;}
    if(action==="cameras"){showModal("Your home cameras","Add an existing camera stream to this display.",`<label class="field"><span>Camera name</span><input placeholder="Front door"></label><label class="field"><span>Stream address</span><input placeholder="rtsp://…"></label>`,btn("Save camera","connection-saved","primary"));return;}
    if(action==="converter"){showModal("Quick conversions","A little help with everyday measurements.",`<label class="field"><span>Celsius</span><input id="conversion-value" inputmode="decimal" value="20"></label><div class="source-summary"><span><strong id="conversion-result">68° Fahrenheit</strong><small>Temperature</small></span></div>`,btn("Done","close-modal","primary"));return;}
    if(action==="edit-folder"){showModal("Your Utilities folder","Folder name and contents stay together.",`<label class="field"><span>Folder name</span><input id="folder-name" value="Utilities"></label>`,btn("Save folder","folder-save","primary"));return;}
    choices("Choose an option","This control uses the same settings patterns.",["Default","Custom"],"value-select",key);
  }
  function handle(target){
    const action=target.dataset.action;
    const screens=["home","apps","folder","store","tools","timer","edit","widget-library","settings","sources","connect","albums","clock-picker","photos","wallpaper","calendar","world-clock","wake","ha","remote","health"];
    if(screens.includes(action)){go(action);return;}
    if(action==="category"){go("settings",target.dataset.category);return;}
    if(action.startsWith("category-")){go("settings",action.slice(9));return;}
    if(action==="close-modal"){state.modal=null;render();return;}
    if(action==="back"){back();return;}
    if(action==="theme"){state.theme=state.theme==="light"?"dark":"light";render();announceNavigation();return;}
    if(action==="segment"){const key=target.dataset.key;if(key==="theme"){state.theme=target.dataset.value.toLowerCase();render();announceNavigation();}return;}
    if(action==="open-app"){const name=target.dataset.name;if(name==="App Store"){go("store");return;}if(name==="Photo display"){go("photos");return;}if(name==="Utilities"){go("folder");return;}if(name==="Timer"){go("timer");return;}if(name==="Intercom"||name==="Stories"||name==="Camera viewer"){secondaryAction(({Intercom:"intercom",Stories:"stories","Camera viewer":"cameras"})[name],target);return;}showModal(name,"This app opens separately from Immortal.",`<div class="source-summary">${appIcon(getApp(name))}<span><strong>${name}</strong><small>Back returns to your home.</small></span></div>`,btn("Return home","home","primary"));return;}
    if(action==="app-detail"){state.currentApp=target.dataset.name||"VLC";go("app-detail");return;}
    if(action==="install"){
      const name=target.dataset.name;if(state.installed.has(name)){handle({dataset:{action:"open-app",name}});return;}
      if(state.scenario==="offline")return;
      state.installing.add(name);render();
      setTimeout(()=>{state.installing.delete(name);state.installed.add(name);render();toast(`${name} added to your preview library`);},700);return;
    }
    if(action==="favorite-toggle"){
      const name=target.dataset.name;
      if(state.favorites.includes(name))state.favorites=state.favorites.filter(v=>v!==name);
      else if(state.favorites.length<6)state.favorites.push(name);
      else {toast("Choose up to six favorites. Remove one to add another.");return;}
      secondaryAction("favorites",target);return;
    }
    if(action==="quick-control"){if(state.scenario==="offline"){choices("Unable to reach this device","Your Portal is offline. Try reconnecting before changing your home controls.",[["Connection help","View network options","wifi"]],"connection-choice");return;}state[target.dataset.key]=!state[target.dataset.key];render();return;}
    if(action==="music-play"||action==="track-previous"||action==="track-next"){
      if(state.scenario==="offline"){toast("Music is unavailable while offline. Saved photos still work.");return;}
      if(action==="music-play")state.playing=!state.playing;else state.track++;
      render();return;
    }
    if(action==="player-select"){state.scenario="normal";state.playing=true;state.modal=null;render();toast(`${target.dataset.value} selected for this room`);announceNavigation();return;}
    if(action==="remove-widget"){state.widgets.splice(Number(target.dataset.index),1);render();toast("Widget removed. Add it again from the widget library.");return;}
    if(action==="move-widget"){
      const from=Number(target.dataset.index),options=state.widgets.map((name,index)=>[index,name]).filter(([,name])=>name!=="Photos");
      showModal(`Move ${state.widgets[from]}`,"Choose a position in the small-widget grid.",`<div class="modal-options">${options.map(([index,name],position)=>btn(`${icon("gridapps")}<span>${position+1}. ${name}</span>${chevron()}`,"move-to","modal-option",`data-from="${from}" data-to="${index}"`)).join("")}</div>`);return;
    }
    if(action==="move-to"){const [widget]=state.widgets.splice(Number(target.dataset.from),1);state.widgets.splice(Number(target.dataset.to),0,widget);state.modal=null;render();return;}
    if(action==="add-widget"){
      if(state.widgets.length<5){state.widgets.push(target.dataset.name);go("edit");return;}
      showModal("Make room for a new widget","Choose the widget to replace. Your other widgets stay in place.",`<div class="modal-options">${state.widgets.map((name,index)=>btn(`${icon(widgets.find(w=>w[0]===name)?.[1]||"clock")}<span>${name}</span>${chevron()}`,"replace-widget","modal-option",`data-index="${index}" data-name="${target.dataset.name}"`)).join("")}</div>`);return;
    }
    if(action==="replace-widget"){state.widgets[Number(target.dataset.index)]=target.dataset.name;go("edit");return;}
    if(action==="density-choice"){state.density=target.dataset.value;render();return;}
    if(action==="source-choice"){state.source=target.dataset.source;state.sourceError="";state.selectedAlbums=[state.album];go(state.source==="Local folder"?"albums":"connect");return;}
    if(action==="shared-album"){state.source="Shared album";state.sourceError="";go("connect");return;}
    if(action==="show-password"){
      const form=device.querySelector("#source-form"),data=Object.fromEntries(new FormData(form));state.passwordVisible=!state.passwordVisible;render();
      Object.entries(data).forEach(([key,value])=>{const field=device.querySelector(`#source-form [name="${key}"]`);if(field)field.value=value;});return;
    }
    if(action==="album-select"){const name=target.dataset.album;state.selectedAlbums=state.selectedAlbums.includes(name)?state.selectedAlbums.filter(v=>v!==name):[...state.selectedAlbums,name];render();return;}
    if(action==="albums-confirm"){state.connected=true;state.album=state.selectedAlbums.length===1?state.selectedAlbums[0]:`${state.selectedAlbums.length} selected albums`;state.photo=Math.max(0,["Into the wild","Mountain mornings","A quieter place"].indexOf(state.selectedAlbums[0]));go("settings","photos");toast("Your selected albums are ready");return;}
    if(action==="clock-select"){state.clock=target.dataset.clock;state.switches.clock=state.clock!=="Photo only";render();return;}
    if(action==="wallpaper-select"){state.wallpaper=target.dataset.value;render();return;}
    if(action==="value-select"){const key=target.dataset.key;state.values[key]=target.dataset.value;if(["interval","idle","density","crop"].includes(key))state[key]=target.dataset.value;state.modal=null;render();return;}
    if(action==="connection-choice"){if(target.dataset.value==="Try again"){state.scenario="normal";state.modal=null;render();announceNavigation();toast("Connection restored in the preview");}else secondaryAction(target.dataset.value==="Wi-Fi settings"?"android":"connection-help",target);return;}
    if(action==="search-setting"){state.screen="settings";state.category=target.dataset.category;state.highlight=target.dataset.key;state.modal=null;render();announceNavigation();const row=device.querySelector("#setting-"+state.highlight);if(row){row.tabIndex=-1;row.scrollIntoView({block:"center"});row.focus({preventScroll:true});}return;}
    if(action==="name-save"){const name=device.querySelector("#room-name").value.trim();if(!name){toast("Enter a device name");return;}state.room=name;state.modal=null;render();return;}
    if(action==="city-select"){if(state.worldCities.length>=3){toast("This layout supports three cities. Remove one to add another.");return;}state.worldCities.push(target.dataset.value);state.modal=null;render();return;}
    if(action==="city-remove"){state.worldCities=state.worldCities.filter(city=>city!==target.dataset.city);render();return;}
    if(action==="timer-set"){state.timerMinutes=Number(target.dataset.minutes);state.timerLeft=state.timerMinutes*60;state.timerRunning=false;render();return;}
    if(action==="timer-toggle"){state.timerRunning=!state.timerRunning;if(state.timerRunning)state.timerEnd=Date.now()+state.timerLeft*1000;render();return;}
    if(action==="timer-reset"){state.timerRunning=false;state.timerLeft=state.timerMinutes*60;render();return;}
    if(action==="connection-saved"||action==="countdown-save"||action==="folder-save"){if(action==="countdown-save")state.values.countdown=device.querySelector("#countdown-title").value||"Next adventure";state.modal=null;render();toast("Saved in this preview");return;}
    if(action==="tool-choice"){showModal(target.dataset.value,target.dataset.key==="stories"?"A gentle story, ready to read together.":"Connect to this room over your local network.",`<div class="information-card"><p>${target.dataset.key==="stories"?"The evening grew quiet around the little lighthouse. One by one, the boats found their way home.":"Kitchen is available on the same Wi-Fi. Choose Talk to begin."}</p></div>`,btn(target.dataset.key==="stories"?"Close story":"Talk","close-modal","primary"));return;}
    secondaryAction(action,target);
  }
  device.addEventListener("click",event=>{
    const target=event.target.closest("[data-action]");if(!target||target.disabled)return;
    event.preventDefault();const details={...target.dataset},previousScreen=state.screen;
    handle(target);
    if(state.screen===previousScreen&&!target.isConnected){
      const scope=state.modal?device.querySelector(".modal"):device;
      const replacement=[...scope.querySelectorAll("[data-action]")].find(el=>Object.entries(details).every(([key,value])=>el.dataset[key]===value));
      replacement?.focus({preventScroll:true});
    }
  });
  device.addEventListener("change",event=>{
    const input=event.target;if(input.dataset.switch)state.switches[input.dataset.switch]=input.checked;
  });
  device.addEventListener("input",event=>{
    const input=event.target;
    if(input.dataset.slider){state[input.dataset.slider]=Number(input.value);input.closest(".slider-control").style.setProperty("--value",input.value+"%");input.closest(".slider").querySelector("output").textContent=input.value+"%";}
    if(input.id==="settings-search")device.querySelector("#category-nav").innerHTML=input.value.trim()?searchResults(input.value)||'<p class="settings-note">No results. Try clock, photos, or sound.</p>':categoryNav();
    if(input.id==="app-search"){const found=apps.filter(app=>(app.name+" "+app.subtitle).toLowerCase().includes(input.value.toLowerCase()));device.querySelector("#app-grid").innerHTML=found.length?found.map(app=>appTile(app,true)).join(""):'<p class="muted">No apps found. Try another name.</p>';}
    if(input.id==="global-search"){const value=input.value.trim().toLowerCase();device.querySelector("#global-results").innerHTML=value?apps.filter(app=>app.name.toLowerCase().includes(value)).slice(0,3).map(app=>btn(`${appIcon(app)}<span>${app.name}<small>App</small></span>`,"open-app","modal-option",`data-name="${app.name}"`)).join("")+searchResults(value):"";}
    if(input.id==="conversion-value"){const value=Number(input.value);device.querySelector("#conversion-result").textContent=Number.isFinite(value)?`${Math.round((value*9/5+32)*10)/10}° Fahrenheit`:"Enter a number";}
  });
  device.addEventListener("submit",event=>{
    event.preventDefault();const form=event.target,data=new FormData(form);
    if(form.id==="source-form"||form.id==="ha-form"){
      const isSource=form.id==="source-form",address=String(data.get("address")||"").trim(),credential=String(data.get("credential")||"").trim();
      let valid=false;try{const url=new URL(address);valid=Boolean(url.hostname)&&(isSource&&state.source==="SMB share"?url.protocol==="smb:":["http:","https:"].includes(url.protocol));}catch{}
      const error=!valid?"Enter a complete server or album address.":!(isSource&&state.source==="Shared album")&&credential.length<4?"Enter an access key or password.":state.scenario==="offline"?"The Portal is offline. Check the connection and try again.":"";
      if(error){device.querySelector(isSource?"#source-error":"#ha-error").textContent=error;return;}
      if(!isSource){toast("Home Assistant connection tested in this preview");return;}
      state.testing=true;const request=++sourceRequest,values=Object.fromEntries(data);render();Object.entries(values).forEach(([key,value])=>{const input=device.querySelector(`#source-form [name="${key}"]`);if(input)input.value=value;});
      setTimeout(()=>{if(request!==sourceRequest||state.screen!=="connect")return;state.testing=false;state.sourceError="";go("albums");},500);return;
    }
    if(form.id==="calendar-form"){
      const url=String(data.get("url")||"").trim(),name=String(data.get("name")||"").trim();
      let valid=false;try{const address=new URL(url);valid=Boolean(address.hostname)&&["http:","https:"].includes(address.protocol);}catch{}
      if(!name||!valid){device.querySelector("#calendar-error").textContent="Enter a name and a complete calendar URL.";return;}
      state.calendarAdded=true;state.modal=null;render();toast("Calendar added to the preview");
    }
  });
  document.addEventListener("keydown",event=>{
    if(event.key==="Escape"){event.preventDefault();back();return;}
    const focused=document.activeElement,scope=state.modal?device.querySelector(".modal"):device;
    const targets=[...scope.querySelectorAll("button:not(:disabled),input:not(:disabled)")].filter(el=>el.getClientRects().length);
    if(state.modal&&event.key==="Tab"){const index=targets.indexOf(focused);if((event.shiftKey&&index<=0)||(!event.shiftKey&&index===targets.length-1)){event.preventDefault();targets[event.shiftKey?targets.length-1:0]?.focus();}return;}
    if(!["ArrowLeft","ArrowRight","ArrowUp","ArrowDown"].includes(event.key)||focused.matches("input")||!focused.matches("button"))return;
    const r=focused.getBoundingClientRect(),x=r.x+r.width/2,y=r.y+r.height/2,vertical=["ArrowUp","ArrowDown"].includes(event.key),positive=["ArrowDown","ArrowRight"].includes(event.key);let best=null,score=Infinity;
    targets.filter(el=>el!==focused).forEach(el=>{const b=el.getBoundingClientRect(),dx=b.x+b.width/2-x,dy=b.y+b.height/2-y,forward=vertical?dy:dx,cross=vertical?dx:dy;if((positive?forward:-forward)<5)return;const candidate=Math.abs(forward)+3*Math.abs(cross);if(candidate<score){best=el;score=candidate;}});
    if(best){event.preventDefault();best.focus();}
  });
  setInterval(()=>{
    if(!state.timerRunning)return;state.timerLeft=Math.max(0,Math.ceil((state.timerEnd-Date.now())/1000));
    const digits=device.querySelector("#timer-digits");if(digits)digits.textContent=formatTime(state.timerLeft);
    if(state.timerLeft===0){state.timerRunning=false;toast("Your timer is ready");}
  },1000);
  window.addEventListener("resize",fit);
  if(["sources","connect","albums","clock-picker"].includes(state.screen))state.category="photos";
  render();
  window.__portalMock={spec:PORTAL_SPEC,getState:()=>({screen:state.screen,category:state.category,theme:state.theme,scenario:state.scenario,widgets:[...state.widgets],favorites:[...state.favorites]})};
})();
