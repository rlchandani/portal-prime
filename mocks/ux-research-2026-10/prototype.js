/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */
(() => {
  "use strict";
  const params = new URLSearchParams(location.search);
  const app = document.getElementById("app");
  const sheet = document.getElementById("sheet");
  const initialScreen = params.get("screen") || "home";
  const state = {
    concept: ["everyday", "gallery", "control"].includes(params.get("concept")) ? params.get("concept") : "everyday",
    screen: initialScreen,
    scenario: params.get("scenario") || "normal",
    category: initialScreen==="source"?"photos":"appearance", theme: "Light", sourceStep: 0, source: "Immich",
    album: "Into the wild", connected: false, sourceError: "", highlight: "",
    playing: false, track: 0, lights: true, lamp: false, room: "Living room",
    layout: "Comfortable", widgets: ["Photos", "Weather", "Today", "Music", "Quick controls"],
    installed: new Set(), installing: new Set(), settings: {brightness: 72, interval: "30 seconds", shuffle: true, clock: true, chime: false, touch: true, wake: false, autoStart: true, remote: true, largeText: false, reducedMotion: false, showTime: true, statusBar: false}
  };
  const photos = ["assets/yosemite.jpg", "assets/denali.jpg", "assets/wilderness.jpg"];
  const apps = [
    {name: "Browser", icon: "globe", color: "blue", detail: "Browse the web"},
    {name: "VLC", icon: "vlc", color: "orange", detail: "Video & audio"},
    {name: "Radio", icon: "radio", color: "purple", detail: "Internet stations"},
    {name: "Home Assistant", icon: "home", color: "blue", detail: "Your home"},
    {name: "Music", icon: "music", color: "rose", detail: "Your players"},
    {name: "App Store", icon: "bag", color: "green", detail: "Find Portal apps"},
    {name: "Photo display", icon: "photo", color: "green", detail: "Your photo frame"},
    {name: "Utilities", icon: "folder", color: "gray", detail: "4 tools"},
    {name: "Timer", icon: "timer", color: "orange", detail: "Kitchen companion"},
    {name: "Intercom", icon: "megaphone", color: "purple", detail: "Around the house"}
  ];
  const categories = [
    ["appearance", "Appearance", "display", "#476faf"],
    ["photos", "Photos & screensaver", "photo", "#579175"],
    ["home", "Home & widgets", "gridapps", "#8170a9"],
    ["sound", "Sound & wake", "bell", "#b27855"],
    ["connections", "Connections", "wifi", "#477ea2"],
    ["access", "Privacy & accessibility", "hand", "#717e94"],
    ["device", "Device", "info", "#718387"],
    ["advanced", "Advanced", "tools", "#78818c"]
  ];
  const searchIndex = [
    ["Brightness", "appearance", "brightness", "display screen dim"],
    ["Theme", "appearance", "theme", "light dark appearance"],
    ["Wallpaper", "appearance", "wallpaper", "background"],
    ["Photo source", "photos", "photo-source", "immich smb webdav folder album"],
    ["Clock face", "photos", "clock-face", "clock style"],
    ["Photo interval", "photos", "interval", "speed seconds slideshow"],
    ["Shuffle photos", "photos", "shuffle", "random order"],
    ["Calendar", "home", "calendar", "events feed"],
    ["World clock", "home", "world-clock", "timezone time zone"],
    ["Quick buttons", "home", "quick-buttons", "quickbar shortcuts"],
    ["Countdown", "home", "countdown", "days event"],
    ["Chime", "sound", "chime", "hourly sound bell"],
    ["Sunrise & wake", "sound", "wake", "alarm light morning"],
    ["Multi-room audio", "sound", "multiroom", "music snapcast"],
    ["Touch sounds", "sound", "touch", "tap feedback"],
    ["Home Assistant", "connections", "mqtt", "mqtt smart home"],
    ["Phone remote", "connections", "remote", "phone control"],
    ["Larger text", "access", "largeText", "font accessibility"],
    ["Reduce motion", "access", "reducedMotion", "animation"],
    ["Device name", "device", "name", "room rename"],
    ["Start on boot", "device", "autoStart", "startup power"],
    ["Device health", "device", "health", "memory version about"],
    ["Android settings", "advanced", "android", "system network wifi bluetooth"]
  ];
  const icon = name => `<span class="icon" aria-hidden="true">${I[name] || I.info}</span>`;
  const esc = text => String(text).replace(/[&<>"']/g, c => ({"&":"&amp;","<":"&lt;",">":"&gt;",'"':"&quot;","'":"&#39;"}[c]));
  const button = (label, action, css = "plain-button", extra = "") => `<button class="${css}" data-action="${action}" ${extra}>${label}</button>`;
  const appIcon = item => `<span class="app-icon ${item.color}">${icon(item.icon)}</span>`;
  const appTile = (item, details = false) => `<button class="app-tile" data-action="app" data-name="${esc(item.name)}">${appIcon(item)}<span><strong>${esc(item.name)}</strong>${details ? `<span class="small">${item.detail}</span>` : ""}</span></button>`;
  function toast(message) {
    const el = document.getElementById("toast");
    clearTimeout(toast.timeout);
    el.textContent = message;
    el.classList.add("visible");
    toast.timeout = setTimeout(() => el.classList.remove("visible"), 2800);
  }
  function topbar() {
    return `<header class="topbar"><div class="wordmark">immortal<span class="room-label">Living room</span></div><div class="top-actions"><span class="connection">${icon("wifi")}${state.scenario === "offline" ? "Offline · saved content" : "Connected"}</span>${state.screen==="home"&&state.concept==="everyday"?button("Customize","customize","text-button"):""}${button(icon("search"), "global-search", "round-button", 'aria-label="Search apps and settings"')}${button(icon("gear"), "settings", "round-button", 'aria-label="Settings"')}</div></header>`;
  }
  function dock() {
    return `<nav class="dock" aria-label="Main navigation">${[["home","Home","homeS"],["apps","Apps","gridapps"],["photos","Photos","photo"],["tools","Tools","tools"]].map(([screen,label,glyph]) => button(icon(glyph) + label, screen, state.screen === screen ? "active" : "", state.screen === screen ? 'aria-current="page"' : "")).join("")}</nav>`;
  }
  function offlineNote() {
    return state.scenario === "offline" ? `<div class="state-note">${icon("wifi")}<span>Offline. Photos and your last weather update are still available.</span>${button("Connection", "connection-help", "text-button")}</div>` : "";
  }
  function calendarContent(extended = false) {
    if (state.scenario === "empty") return `<div class="empty-content"><h3>Your day, at a glance</h3><p>Add a calendar to see what’s next.</p>${button("Add calendar →", "calendar-connect", "text-button")}</div>`;
    return `<div class="next-label">NEXT · 10:30 AM</div><div class="today-event"><span class="event-line"></span><div class="event-info"><strong>Sunday farmers’ market</strong><span>Downtown · until 12:00 PM</span></div></div><div class="today-event"><span class="event-line green"></span><div class="event-info"><strong>Dinner with friends</strong><span>6:00 PM · at home</span></div></div>${extended ? `<div class="group-label">Tomorrow</div><div class="today-event"><span class="event-line"></span><div class="event-info"><strong>School drop-off</strong><span>8:15 AM</span></div></div><div class="today-event"><span class="event-line green"></span><div class="event-info"><strong>Evening walk</strong><span>5:30 PM</span></div></div>` : ""}`;
  }
  function weatherWidget() {
    return `<section class="widget weather-widget"><h2 class="widget-title"><span>${icon("weather")}San Francisco</span></h2><div class="weather-main"><span class="temperature">68°</span>${icon("weather")}</div><p class="weather-description">A little sunshine</p><p class="weather-detail">H: 71° &nbsp; L: 58°${state.scenario === "offline" ? " · Saved 9:24 AM" : ""}</p></section>`;
  }
  function musicContent() {
    if (state.scenario === "empty") return `<div class="empty-content"><h3>Make room for music</h3><p>Choose a player or tune into radio.</p>${button("Choose a player →", "music-picker", "text-button")}</div>`;
    return `<div class="music-content"><div class="cover" aria-hidden="true"></div><div class="music-info"><strong>${state.track % 2 ? "A quiet morning" : "Sunday slow-down"}</strong><span>Living room · ${state.playing ? "Playing" : "Ready to play"}</span></div></div><div class="music-controls">${button('<span class="skip">‹</span>', "previous-track", "", 'aria-label="Previous track"')}${button(state.playing ? '<span class="pause-mark" aria-hidden="true"></span>' : icon("play"), "play", "play-button", `aria-label="${state.playing ? "Pause" : "Play"} music"`)}${button('<span class="skip">›</span>', "next-track", "", 'aria-label="Next track"')}</div>`;
  }
  function quickTile(label, setting, glyph, subtitle) {
    return button(`${icon(glyph)}<strong>${label}</strong><small>${state[setting] ? "On" : subtitle || "Off"}</small>`, "quick-toggle", `quick-tile ${state[setting] ? "on" : ""}`, `data-key="${setting}" aria-pressed="${state[setting]}" aria-label="${label}, ${state[setting] ? "on" : "off"}"`);
  }
  function favorites(count = 6) {
    return `<section class="favorites"><div class="favorites-heading"><h2>Favorite apps</h2>${button("All apps →", "apps", "text-button")}</div><div class="favorite-grid">${apps.slice(0,count).map(item => appTile(item)).join("")}</div></section>`;
  }
  function everydayHome() {
    const available = name => state.widgets.includes(name);
    return `<main class="app-shell">${topbar()}<div class="greeting"><div><h1>Good morning.</h1><p>A quiet Sunday. Everything within reach.</p></div><div class="time-block"><strong>9:41</strong><span>Sunday<br>October 4</span></div></div>${offlineNote()}<div class="home-board ${available("Photos") ? "" : "without-photo"}">
      ${available("Photos") ? `<button class="widget photo-widget" data-action="photos"><img src="${photos[0]}" alt="Yosemite mountains above a forest"><span class="photo-copy"><strong>Into the wild</strong><span>${icon("photo")}Your photo display · 248 photos</span></span></button>` : ""}
      ${available("Weather") ? weatherWidget() : ""}
      ${available("Today") ? `<section class="widget"><h2 class="widget-title"><span>${icon("cal")}Today</span></h2>${calendarContent()}</section>` : ""}
      ${available("Music") ? `<section class="widget music-widget"><h2 class="widget-title"><span>${icon("music")}Now playing</span>${button(icon("plus"), "music-picker", "widget-menu", 'aria-label="Choose music player"')}</h2>${musicContent()}</section>` : ""}
      ${available("Quick controls") ? `<section class="widget quick-widget"><h2 class="widget-title"><span>${icon("homeS")}Your home</span>${button("Edit", "category-connections", "widget-menu")}</h2><div class="quick-tiles">${quickTile("Living room", "lights", "bulb")}${quickTile("Reading lamp", "lamp", "bulb")}</div></section>` : ""}
      ${state.widgets.filter(w => !["Photos","Weather","Today","Music","Quick controls"].includes(w)).map(w=>`<section class="widget"><h2 class="widget-title"><span>${icon("clock")}${esc(w)}</span></h2><div class="temperature">${w === "World clock" ? "5:41" : "12"}</div><p class="weather-detail">${w === "World clock" ? "London · afternoon" : "Days to the next adventure"}</p></section>`).join("")}
    </div>${favorites()}${dock()}</main>`;
  }
  function galleryHome() {
    return `<main class="app-shell gallery-shell"><img class="gallery-background" src="${photos[0]}" alt="Scenic Yosemite landscape"><div class="gallery-shade"></div>${topbar()}<div class="gallery-content"><div class="gallery-hero"><div class="gallery-time">9:41</div><h1>Sunday, October 4</h1><p>68° · A little sunshine</p></div><div class="gallery-bottom"><div class="gallery-caption"><strong>Into the wild</strong><p>Your moments. A little more space.</p>${button("Customize home", "customize", "text-button")}</div><div class="gallery-agenda"><div class="small">${state.scenario === "offline" ? "OFFLINE · SAVED AGENDA" : "COMING UP"}</div><strong>${state.scenario === "empty" ? "Make this home yours" : "Sunday farmers’ market"}</strong><span>${state.scenario === "empty" ? "Add your calendar in Settings" : "10:30 AM · Downtown"}</span></div></div></div><div class="gallery-favorites">${[apps[0],apps[2],apps[3],apps[5]].map(item=>appTile(item)).join("")}</div>${dock()}</main>`;
  }
  function controlHome() {
    return `<main class="app-shell control-shell">${topbar()}<div class="control-header"><div><h1>A good day starts here.</h1><p>Sunday, October 4 · 68° in San Francisco</p></div>${button("Customize", "customize", "plain-button")}</div>${offlineNote()}<div class="control-board"><section class="widget control-house"><h2 class="widget-title"><span>${icon("homeS")}Your home</span><span class="small">${state.scenario === "empty" ? "Demo controls" : "4 devices"}</span></h2><div class="room-tabs" aria-label="Room">${["Living room","Kitchen","All rooms"].map(room=>button(room,"room",room===state.room?"selected":"",`data-room="${room}" aria-pressed="${room===state.room}"`)).join("")}</div><div class="quick-tiles">${quickTile(state.room==="Kitchen"?"Ceiling light":"Room lights","lights","bulb")}${quickTile(state.room==="Kitchen"?"Counter lamp":"Reading lamp","lamp","bulb")}${button(`${icon("music")}<strong>Music</strong><small>${state.playing?"Playing":"Ready"}</small>`,"play",`quick-tile ${state.playing?"on":""}`)}${button(`${icon("moonz")}<strong>Wind down</strong><small>Evening scene</small>`,"scene","quick-tile")}</div></section><section class="widget control-calendar"><h2 class="widget-title"><span>${icon("cal")}Your day</span></h2>${calendarContent()}<button class="control-photo" data-action="photos"><img src="${photos[1]}" alt="Denali mountain reflected in water"><span>Into the wild · Photo display</span></button></section><section class="widget control-media"><div><h2 class="widget-title"><span>${icon("music")}Music at home</span></h2>${musicContent()}</div><div class="small muted"><h3>One room, or every room.</h3><p style="margin:14px 0;line-height:1.7">Choose where your music belongs.</p>${button("Choose rooms", "audio-rooms", "plain-button")}</div></section></div>${favorites()}${dock()}</main>`;
  }
  function row(key, label, desc, right, action) {
    const element = action ? "button" : "div";
    return `<${element} class="settings-row ${action ? "button-row" : ""} ${state.highlight === key ? "focused-setting" : ""}" id="setting-${key}" ${action ? `data-action="${action}"` : ""}><span class="row-label"><strong>${label}</strong>${desc?`<small>${desc}</small>`:""}</span><span class="row-end">${right || ""}${action?'<span class="chevron" aria-hidden="true">›</span>':""}</span></${element}>`;
  }
  function toggleRow(key,label,desc) {
    return `<label class="settings-row ${state.highlight===key?"focused-setting":""}" id="setting-${key}"><span class="row-label"><strong>${label}</strong>${desc?`<small>${desc}</small>`:""}</span><span class="switch-control"><input type="checkbox" role="switch" aria-label="${label}" data-setting="${key}" ${state.settings[key]?"checked":""}></span></label>`;
  }
  const group = (title,contents,note="") => `<h2 class="group-label">${title}</h2><div class="settings-group">${contents}</div>${note?`<p class="settings-footnote">${note}</p>`:""}`;
  const segment = (key,options,value) => `<span class="segment" role="group" aria-label="${key}">${options.map(option=>button(option,"segment",option===value?"selected":"",`data-key="${key}" data-value="${option}" aria-pressed="${option===value}"`)).join("")}</span>`;
  function categoryDetail() {
    if (state.category === "appearance") return `<div class="detail-header"><div><h1>Appearance</h1><p>Make the display feel at home in your room.</p></div></div>
      <div class="preview-mini"><img src="${photos[0]}" alt="Preview of photo display"><div class="preview-clock">9:41<small>Sunday, October 4</small></div>${button("Preview", "photos", "plain-button")}</div>
      ${group("Display",row("brightness","Brightness","Comfortable to read, day or night",`<label class="setting-slider"><input type="range" min="10" max="100" value="${state.settings.brightness}" aria-label="Brightness" data-setting="brightness"><output>${state.settings.brightness}%</output></label>`)+row("theme","Settings appearance","Choose a familiar light or calm dark surface",segment("theme",["Light","Dark"],state.theme)))}
      ${group("Personalize",row("wallpaper","Wallpaper","Scenic, ambient, or a solid color","Yosemite","wallpaper")+toggleRow("showTime","Time on home","Keep time visible at a glance"))}
      <p class="settings-footnote">Changes preview here immediately. This study does not change your Portal.</p>`;
    if (state.category === "photos") return `<div class="detail-header"><div><h1>Photos & screensaver</h1><p>Your memories, with a display that quietly takes care of itself.</p></div>${button("Preview", "photos", "text-button")}</div>
      <div class="connection-summary">${icon("photo")}<div><strong>${state.connected?state.source:"Local photos"}</strong><p>${state.album} · 248 photos${state.scenario==="offline"?" · saved on device":""}</p></div><span class="status-tag">${state.scenario==="offline"?"Cached":"Ready"}</span></div>
      ${group("Your photos",row("photo-source","Photo source","Manage albums and connections",state.connected?state.source:"Local folder","source")+row("interval","Change photo every","Give each moment a little time",state.settings.interval,"interval")+toggleRow("shuffle","Shuffle photos","Show photos in a different order"))}
      ${group("Clock & display",row("clock-face","Clock face","A simple clock, or something more expressive","Digital","clock-picker")+toggleRow("clock","Show clock","Visible over your photos")+row("screensaver-start","Start screensaver","When the Portal is resting","After 5 minutes","screensaver-delay"))}
      ${group("More options",row("photo-advanced","Advanced photo options","Transitions, fit, burn-in protection, and overlays","","photo-advanced"))}`;
    if (state.category === "home") return `<div class="detail-header"><div><h1>Home & widgets</h1><p>Choose the things you want within reach.</p></div></div>
      ${group("Your home",row("layout","Home layout","Move, add, or remove widgets",state.layout,"customize")+row("favorites","Favorite apps","A short list for everyday use","6 apps","favorites-edit"))}
      ${group("At a glance",row("calendar","Calendar","Your events and daily almanac","2 calendars","calendar-connect")+row("world-clock","World clock","The time in places you care about","London","world-clock")+row("countdown","Countdown","Days until something special","Next adventure","countdown")+row("quick-buttons","Quick buttons","Household actions and shortcuts","2 buttons","quick-buttons")+row("weather","Weather","Location and temperature units","San Francisco · °F","weather"))}
      <p class="settings-footnote">You can also use Customize home directly from the home screen.</p>`;
    if (state.category === "sound") return `<div class="detail-header"><div><h1>Sound & wake</h1><p>Gentle cues and music for the rhythm of your day.</p></div></div>
      ${group("Everyday sounds",toggleRow("chime","Hourly chime","A quiet reminder as the hour changes")+toggleRow("touch","Touch sounds","Hear when a tap is recognized")+row("greeting","Welcome greeting","What you see when the display wakes","Good morning","greeting"))}
      ${group("Mornings",toggleRow("wake","Sunrise wake light","Gradually brighten before your wake time")+row("wake-time","Wake time","Choose the days and the light","7:00 AM · weekdays","wake-time"))}
      ${group("Music",row("multiroom","Multi-room audio","Music across your home","Not connected","audio-rooms"))}`;
    if (state.category === "connections") return `<div class="detail-header"><div><h1>Connections</h1><p>Clear setup and status for the things your Portal connects to.</p></div></div>
      ${group("Your home",row("mqtt","Home Assistant","Connect your server and choose your controls",state.scenario==="offline"?"Offline":"Connected","mqtt")+row("mqtt-publish","MQTT publishing","Share Portal state with your smart home","Not connected","mqtt-publish"))}
      ${group("Remote control",toggleRow("remote","Phone remote","Control this display from a phone on your Wi-Fi")+row("phone-link","Connect your phone","Pairing and connection details","Same Wi-Fi","phone-pair"))}
      ${group("Media connections",row("albums","Photo services","Local files, Immich, SMB, or WebDAV","Manage","source")+row("music-services","Music & rooms","Players and synchronized audio","Manage","audio-rooms"))}
      <p class="settings-footnote">Connection errors appear next to the affected service, with a way to try again.</p>`;
    if (state.category === "access") return `<div class="detail-header"><div><h1>Privacy & accessibility</h1><p>A shared display should feel comfortable for everyone.</p></div></div>
      ${group("Read & interact",toggleRow("largeText","Larger text","More comfortable reading at a distance")+toggleRow("reducedMotion","Reduce motion","Keep transitions simple and direct")+row("back","Back gesture","Keep a visible way back as well","Enabled","back-gesture"))}
      ${group("Shared-display privacy",row("photo-privacy","Photo privacy","Choose which albums appear in shared spaces",state.album,"source")+row("calendar-privacy","Calendar privacy","Control which event details are shown","Titles & time","calendar-privacy"))}
      <p class="settings-footnote">Larger text, reduced motion, and calendar privacy are proposed additions. Native support needs implementation after design approval.</p>`;
    if (state.category === "device") return `<div class="detail-header"><div><h1>Device</h1><p>The essentials about your Portal, without the technical clutter.</p></div></div>
      ${group("Identity",row("name","Device name","Shown when connecting your phone","Living room","rename-device")+toggleRow("autoStart","Start on boot","Return to Immortal when the Portal starts"))}
      ${group("Rest & power",row("sleep","Sleep timer","Dim the room on your schedule","Not scheduled","sleep"))}
      ${group("About",row("version","Immortal version","Sample device information","1.64")+row("updates","Software updates","Keep your Portal experience current","Up to date","updates")+row("health","Device health","Connection, storage, and diagnostics","View","health"))}
      <p class="settings-footnote">Immortal is an independent community project.</p>`;
    return `<div class="detail-header"><div><h1>Advanced</h1><p>System controls and specialist options, when you need them.</p></div></div>
      ${group("System",row("android","Android settings","Wi-Fi, Bluetooth, and system preferences","Opens system","android")+toggleRow("statusBar","Show system status bar","Extra system information above your home"))}
      ${group("Management",row("fleet","Fleet management","Remote diagnostics and device management","Agent settings","fleet")+row("diagnostics","Diagnostics","Inspect the display’s current state","View","health"))}
      <p class="settings-footnote">Frequently used controls remain in their primary category. Advanced contains specialist options.</p>`;
  }
  function settingsSidebar() {
    return `<aside class="settings-sidebar"><h1>Settings</h1><label class="search-field">${icon("search")}<input id="settings-search" type="search" placeholder="Search settings" aria-label="Search settings" autocomplete="off"></label><nav class="settings-nav" id="settings-nav" aria-label="Settings categories">${categoryNav()}</nav><div class="settings-device"><strong>Living room</strong><span>Portal+ · Immortal 1.64</span></div></aside>`;
  }
  function categoryNav() {
    return categories.map(([key,label,glyph,color])=>button(`<span class="setting-badge" style="background:${color}">${icon(glyph)}</span>${label}`,"category",key===state.category?"selected":"",`data-category="${key}" ${key===state.category?'aria-current="page"':""}`)).join("");
  }
  function settingsScreen(source = false) {
    return `<main class="settings-shell ${state.theme==="Light"?"light":""}"><header class="settings-top">${button(icon("chevL")+"Home","home","text-button")}<span class="small">Living room</span>${button(state.theme==="Light"?"Dark appearance":"Light appearance","theme-toggle","text-button")}</header><div class="settings-layout">${settingsSidebar()}<section class="settings-detail" aria-label="Settings detail">${source?sourceDetail():categoryDetail()}</section></div></main>`;
  }
  function sourceDetail() {
    if (state.sourceStep === 0) return `<div class="detail-breadcrumb">${button("Photos & screensaver","category-photos","")}<span>/ Photo source</span></div><div class="detail-header"><div><h1>Where are your photos?</h1><p>Choose a source. You can add more later.</p></div></div><div class="source-grid">${[
      ["Local folder","folder","Photos already on your Portal","#6b8f73"],
      ["Immich","photo","Your own photo library","#587bc5"],
      ["SMB share","folder","A shared folder on your network","#a78b59"],
      ["WebDAV","globe","A compatible photo server","#8b74aa"]
    ].map(([name,glyph,desc,color])=>button(`<span class="setting-badge" style="background:${color}">${icon(glyph)}</span><span><strong>${name}</strong><small>${desc}</small></span>`,"source-choice","source-option",`data-source="${name}"`)).join("")}</div><p class="settings-footnote" style="margin-top:25px">Prefer a shared album? ${button("See shared-album options","shared-albums","text-button")}</p>`;
    if (state.sourceStep === 1) return `<div class="detail-breadcrumb">${button("Photo source","source-back","")}<span>/ ${state.source}</span></div><div class="detail-header"><div><h1>Connect ${state.source}</h1><p>Bring your photos into one calm space.</p></div></div><form id="source-form" novalidate><label class="form-field"><span>${state.source==="SMB share"?"Share address":"Server address"}</span><input name="server" id="source-server" type="url" placeholder="${state.source==="SMB share"?"smb://photos.local/family":"https://photos.example.com"}" aria-describedby="source-hint source-error" autocomplete="off"></label><label class="form-field"><span>${state.source==="Immich"?"API key":"Access key or password"}</span><input name="access" type="password" placeholder="Use any sample key for this demo" autocomplete="off"></label><p class="form-hint" id="source-hint">Interactive setup preview. Use a sample address and key; this prototype makes no connection.</p><p class="inline-error" id="source-error" role="alert"></p><div class="button-row"><button class="primary-button" type="submit">Test connection</button>${button("Cancel","source-back","text-button")}</div></form>`;
    return `<div class="detail-breadcrumb">${button("Photo source","source-back","")}<span>/ Choose an album</span></div><div class="detail-header"><div><h1>Choose what’s on display.</h1><p>${state.source} is ready. Select an album for your home.</p></div></div><div class="connection-summary">${icon("photo")}<div><strong>Preview connection successful</strong><p>Sample albums · no external connection</p></div></div>${[["Into the wild",248,photos[0]],["Mountain mornings",86,photos[1]],["A quieter place",112,photos[2]]].map(([name,count,img])=>button(`<img src="${img}" alt=""><span><strong>${name}</strong><small>${count} photos</small></span><span aria-hidden="true">${name===state.album?"✓":""}</span>`,"album-choice",`album-choice ${name===state.album?"selected":""}`,`data-album="${name}" aria-pressed="${name===state.album}"`)).join("")}<div class="button-row" style="margin-top:26px">${button("Use this album","source-done","primary-button")}${button("Back","source-back","text-button")}</div>`;
  }
  function customizeScreen() {
    return `<main class="app-shell">${topbar()}<div class="greeting"><div><h1>A home that’s yours.</h1><p>Keep what matters. Make room for the rest.</p></div></div><div class="editing-toolbar"><div><strong>Customize home</strong><p>Use Move to reorder. Remove a widget to keep things simple.</p></div><div class="button-row">${button(icon("plus")+"Add widget","add-widget","plain-button")}${button("Done","home","primary-button")}</div></div><div class="editing-grid"><section class="editing-preview" aria-label="Home widget order">${state.widgets.map((name,index)=>`<article class="editable-widget"><span class="setting-badge">${icon(({Photos:"photo",Weather:"weather",Today:"cal",Music:"music","Quick controls":"homeS"})[name]||"clock")}</span><strong>${esc(name)}</strong><div class="button-row">${button("Move","move-widget","",`data-index="${index}" aria-label="Move ${name}"`)}${button("Remove","remove-widget","",`data-index="${index}" aria-label="Remove ${name}"`)}</div></article>`).join("")}${button(icon("plus")+"Add widget","add-widget","add-widget")}</section><aside class="editing-info"><h2>Give everything its place.</h2><p>A few useful widgets keep your home easy to read. Changes are visible when you return home.</p><h3 style="margin-bottom:16px">Home density</h3>${["Comfortable","Compact"].map(mode=>`<label class="choice-card ${state.layout===mode?"selected":""}"><span><strong>${mode}</strong><small>${mode==="Comfortable"?"More breathing room, larger controls":"Fit more information on the screen"}</small></span><input type="radio" name="layout" value="${mode}" ${state.layout===mode?"checked":""}></label>`).join("")}<p class="small" style="margin-top:24px">Your browser preview keeps edits until you reload. The default home is the Everyday concept.</p></aside></div>${dock()}</main>`;
  }
  function appsScreen() {
    return `<main class="app-shell">${topbar()}<div class="apps-header"><div><h1>Your apps.</h1><p>Everything you’ve installed, easy to find.</p></div><label class="search-field">${icon("search")}<input type="search" id="apps-search" placeholder="Search apps" aria-label="Search apps"></label></div><div class="button-row">${button("All apps","apps","primary-button")}${button("App Store →","store","plain-button")}${button("Edit favorites","favorites-edit","text-button")}</div><section class="apps-grid" id="apps-grid" aria-label="Installed apps">${apps.map(item=>appTile(item,true)).join("")}</section>${dock()}</main>`;
  }
  function storeScreen() {
    const products = [
      {name:"VLC",icon:"vlc",color:"orange",desc:"Your videos, on a bigger screen.",source:"VideoLAN · open source"},
      {name:"Radio",icon:"radio",color:"purple",desc:"Find a station for every mood.",source:"Community catalog"},
      {name:"Home Assistant",icon:"home",color:"blue",desc:"A place for your smart home.",source:"Home Assistant · open source"},
      {name:"Browser",icon:"globe",color:"blue",desc:"A window to the web.",source:"Community catalog"}
    ];
    return `<main class="app-shell">${topbar()}<section class="store-hero"><div><div class="small">MADE FOR YOUR PORTAL</div><h1>Little additions.<br>A lot more possibility.</h1><p>Useful apps, curated for a display that has more to give.</p></div><div class="hero-art">${[products[0],products[2],products[1]].map(appIcon).join("")}</div></section>${offlineNote()}<div class="favorites-heading"><h2>Everyday essentials</h2><span class="small muted">Compatibility preview</span></div><section class="store-list">${products.map(item=>`<article class="store-app">${appIcon(item)}<div class="store-app-info"><strong>${item.name}</strong><p>${item.desc}</p><small>${item.source}</small></div>${button(state.installed.has(item.name)?"Open":state.installing.has(item.name)?"Adding…":"Get","store-install","plain-button",`data-name="${item.name}" ${state.installing.has(item.name)||state.scenario==="offline"?"disabled":""}`)}</article>`).join("")}</section><p class="settings-footnote" style="margin-top:24px">Demo catalog. “Get” previews progress without downloading or installing an app.</p>${dock()}</main>`;
  }
  function photosScreen() {
    return `<main class="photo-screen"><img src="${photos[state.album==="Mountain mornings"?1:state.album==="A quieter place"?2:0]}" alt="Scenic landscape from the selected sample album">${button(icon("chevL")+"Home","home","photo-exit")}<div class="photo-clock">9:41<small>Sunday, October 4 · 68°</small></div><div class="photo-caption">${state.album} · Sample photo display</div></main>`;
  }
  function toolsScreen() {
    const tools=[["Timer","timer","timer"],["Reading light","bulb","reading-light"],["Intercom","megaphone","intercom"],["Bedtime stories","notes","stories"],["Camera viewer","film","cameras"],["Conversions","calc","converter"]];
    return `<main class="app-shell">${topbar()}<div class="apps-header"><div><h1>A little help, right here.</h1><p>Useful tools for your home and your day.</p></div></div><section class="tools-grid">${tools.map(([label,glyph,action])=>button(icon(glyph)+label,action,"tool-button")).join("")}</section>${dock()}</main>`;
  }
  function render() {
    const screens={home:()=>({everyday:everydayHome,gallery:galleryHome,control:controlHome})[state.concept](),settings:()=>settingsScreen(),source:()=>settingsScreen(true),customize:customizeScreen,apps:appsScreen,store:storeScreen,photos:photosScreen,tools:toolsScreen};
    app.innerHTML=(screens[state.screen]||screens.home)();
    app.classList.toggle("compact-home",state.layout==="Compact");
    document.title=`Immortal — ${state.screen} / ${state.concept}`;
  }
  function navigate(screen, category) {
    if (sheet.open) sheet.close();
    if (category) { state.category=category; state.highlight=""; }
    state.screen=screen;
    const query=new URLSearchParams({concept:state.concept,screen,scenario:state.scenario});
    history.replaceState(null,"","?"+query);
    render();
    window.scrollTo(0,0);
    const title=app.querySelector("h1");
    if(title){title.tabIndex=-1;title.focus({preventScroll:true});}
    if(window.parent!==window)window.parent.postMessage({type:"immortal-navigation",screen,concept:state.concept},"*");
  }
  function showSheet(title,description,contents,footer="") {
    if(sheet.open)sheet.close();
    sheet.classList.toggle("light-sheet",state.theme==="Light"&&["settings","source"].includes(state.screen));
    sheet.innerHTML=`<div class="sheet-header"><h2 id="sheet-title">${title}</h2>${button(icon("x"),"close-sheet","sheet-close",'aria-label="Close"')}</div>${description?`<p class="sheet-description">${description}</p>`:""}${contents}${footer?`<div class="dialog-footer">${footer}</div>`:""}`;
    sheet.showModal();
  }
  function choices(title,description,items,action,key) {
    showSheet(title,description,`<div class="sheet-choices">${items.map(([label,sub,glyph])=>button(`${icon(glyph||"gear")}<span>${label}${sub?`<small>${sub}</small>`:""}</span><span class="chevron" aria-hidden="true">›</span>`,action,"sheet-option",`data-value="${esc(label)}" ${key?`data-key="${key}"`:""}`)).join("")}</div>`);
  }
  function genericDetail(action) {
    const details={
      "calendar-connect":["Your calendar","Calendar feeds stay in one place.",[["Add a calendar","Paste an iCalendar feed URL","cal"],["Daily almanac","Holidays, name days, and daily content","cal"]]],
      "audio-rooms":["Music across your home","Preview where music plays.",[["Living room","This Portal","music"],["Kitchen","Second room · sample","radio"],["Set up multi-room audio","Connect your music server","link"]]],
      "music-picker":["Choose your music","A simple starting point for listening.",[["Radio","Tune into an internet station","radio"],["VLC","Play from your library","vlc"],["Multi-room audio","Listen together","music"]]],
      "clock-picker":["Choose a clock","A clear preview before you choose.",[["Digital","Simple and easy to read","clock"],["Flip clock","A little character","clock"],["Minimal","Let the photo lead","photo"]]],
      "wallpaper":["Choose a background","Set a calm tone for your home.",[["Yosemite","A scenic favorite","photo"],["Ambient sky","Colors that follow the day","weather"],["Solid color","Quiet and simple","display"]]],
      "interval":["Give each photo its time","Choose the pace of your photo display.",[["15 seconds","A faster flow","clock"],["30 seconds","A comfortable pace","clock"],["1 minute","Stay a little longer","clock"]]],
      "screensaver-delay":["When the Portal rests","Choose how long before photo display begins.",[["After 2 minutes","","clock"],["After 5 minutes","","clock"],["After 10 minutes","","clock"]]],
      "photo-advanced":["Advanced photo options","Specialist controls stay one level deeper.",[["Fit & crop","Keep the full image or fill the screen","photo"],["Transitions","Simple fades or expressive movement","film"],["Burn-in protection","Subtle protection for long display sessions","display"]]],
      "health":["Device health","Sample information for layout review.",[["Connection","Wi-Fi connected · preview data","wifi"],["Storage","3.2 GB available · preview data","folder"],["Diagnostics","Open specialist tools","tools"]]],
      "phone-pair":["Connect your phone","On the Portal, this step would show the pairing link or code.",[["Pair on the same Wi-Fi","Preview only · no phone connection","phone"]]],
      "mqtt":["Home Assistant","Keep connection state beside the service.",[["Connection details","Server and credentials","link"],["Quick controls","Choose actions for your home","bulb"]]],
      "shared-albums":["Shared-album options","Preview an existing remote-album feature.",[["Shared album link","Use a supported album URL","link"]]],
      "connection-help":["Your connection","Saved photos are still available while your network is offline.",[["Try again","Preview connection recovery","wifi"],["Wi-Fi settings","On the Portal, opens Android settings","gear"]]]
    };
    const detail=details[action];
    if(detail){choices(detail[0],detail[1],detail[2],action==="interval"?"choose-interval":action==="clock-picker"?"choose-clock":action==="wallpaper"?"choose-wallpaper":action==="connection-help"?"recover-connection":"preview-choice");return;}
    const names={"world-clock":"World clock",countdown:"Countdown","quick-buttons":"Quick buttons",weather:"Weather",greeting:"Welcome greeting","wake-time":"Sunrise & wake",sleep:"Sleep timer","back-gesture":"Back gesture","calendar-privacy":"Calendar privacy",android:"Android settings",fleet:"Fleet management",updates:"Software updates"};
    showSheet(names[action]||"Preview","This destination will use Immortal’s existing settings and dedicated screens. This study previews its place in the navigation.",`<div class="connection-preview">${action==="android"?"System settings open as a separate Android screen on the Portal.":action==="updates"?"Your sample device is up to date.":`${names[action]||"This feature"} is available from this category, with a clear way back.`}</div>`,button("Done","close-sheet","primary-button"));
  }
  function handleAction(target) {
    const action=target.dataset.action;
    if(["home","apps","photos","tools","store","customize","settings"].includes(action)){navigate(action);return;}
    if(action==="source"){state.category="photos";state.sourceStep=0;navigate("source");return;}
    if(action==="category"){navigate("settings",target.dataset.category);return;}
    if(action.startsWith("category-")){navigate("settings",action.slice(9));return;}
    if(action==="theme-toggle"){state.theme=state.theme==="Light"?"Dark":"Light";render();return;}
    if(action==="segment"){state[target.dataset.key]=target.dataset.value;render();return;}
    if(action==="close-sheet"){sheet.close();return;}
    if(action==="play"||action==="previous-track"||action==="next-track"){if(state.scenario==="offline"){toast("Music is unavailable while offline. Your photos still work.");return;}if(action==="play")state.playing=!state.playing;else state.track++;render();return;}
    if(action==="quick-toggle"){if(state.scenario==="offline"){showSheet("Unable to reach this device","The Portal is offline. Your saved layout and photos are still available.",button("Connection help","connection-help","plain-button"));return;}state[target.dataset.key]=!state[target.dataset.key];render();return;}
    if(action==="scene"){if(state.scenario==="offline"){genericDetail("connection-help");return;}state.lights=false;state.lamp=true;state.playing=false;render();toast("Wind down scene previewed");return;}
    if(action==="room"){state.room=target.dataset.room;render();return;}
    if(action==="app"){const name=target.dataset.name;if(name==="App Store"){navigate("store");return;}if(name==="Photo display"){navigate("photos");return;}if(name==="Timer"){handleAction({dataset:{action:"timer"}});return;}if(name==="Utilities"){choices("Utilities","Everything useful, grouped together.",[["Timer","A simple kitchen timer","timer"],["Conversions","Quick measurements","calc"],["Intercom","Across the house","megaphone"],["Reading light","A little brightness","bulb"]],"tool-from-folder");return;}showSheet(name,"This is an interactive design preview. On the Portal, tapping this app would open the installed application.",`<div class="connection-preview">Your home stays one Back action away.</div>`,button("Back to home","home","primary-button"));return;}
    if(action==="tool-from-folder"){const map={Timer:"timer",Conversions:"converter",Intercom:"intercom","Reading light":"reading-light"};sheet.close();handleAction({dataset:{action:map[target.dataset.value]}});return;}
    if(action==="store-install"){const name=target.dataset.name;if(state.installed.has(name)){handleAction({dataset:{action:"app",name}});return;}state.installing.add(name);render();setTimeout(()=>{state.installing.delete(name);state.installed.add(name);render();toast(`${name} added in this preview`);},850);return;}
    if(action==="source-choice"){state.source=target.dataset.source;state.sourceStep=state.source==="Local folder"?2:1;render();return;}
    if(action==="source-back"){state.sourceStep=Math.max(0,state.sourceStep-1);render();return;}
    if(action==="album-choice"){state.album=target.dataset.album;render();return;}
    if(action==="source-done"){state.connected=true;navigate("settings","photos");toast("Album selected for this preview");return;}
    if(action==="choose-interval"){state.settings.interval=target.dataset.value;sheet.close();render();return;}
    if(action==="choose-clock"){sheet.close();toast(`${target.dataset.value} clock selected in preview`);return;}
    if(action==="choose-wallpaper"){sheet.close();toast(`${target.dataset.value} background selected in preview`);return;}
    if(action==="preview-choice"){sheet.close();toast(`${target.dataset.value}: navigation preview`);return;}
    if(action==="recover-connection"){if(target.dataset.value==="Try again"){state.scenario="normal";sheet.close();render();toast("Connection restored in preview");}else genericDetail("android");return;}
    if(action==="global-search"){showSheet("Find what you need","Search your apps or go straight to a setting.",`<label class="search-field">${icon("search")}<input id="global-search" type="search" placeholder="Try “clock” or “radio”" aria-label="Search apps and settings" autofocus></label><div id="global-results" class="sheet-choices" style="margin-top:20px"></div>`);return;}
    if(action==="search-setting"){state.category=target.dataset.category;state.highlight=target.dataset.key;state.screen="settings";sheet.close();render();const rowEl=document.getElementById("setting-"+state.highlight);if(rowEl){rowEl.scrollIntoView({block:"center"});rowEl.tabIndex=-1;rowEl.focus({preventScroll:true});}return;}
    if(action==="move-widget"){choices(`Move ${state.widgets[Number(target.dataset.index)]}`,"Choose a position. Reordering does not require dragging.",state.widgets.map((name,index)=>[`${index+1}. ${name}`,"","gridapps"]),"move-to",target.dataset.index);return;}
    if(action==="move-to"){const from=Number(target.dataset.key),to=Number(target.dataset.value.split(".")[0])-1;const [item]=state.widgets.splice(from,1);state.widgets.splice(to,0,item);sheet.close();render();return;}
    if(action==="remove-widget"){state.widgets.splice(Number(target.dataset.index),1);render();toast("Widget removed. Use Add widget to bring it back.");return;}
    if(action==="add-widget"){const names=["Photos","Weather","Today","Music","Quick controls","World clock","Countdown"].filter(n=>!state.widgets.includes(n));choices("Make room for something useful","Choose a widget for your home.",names.map(name=>[name,"","plus"]),"widget-add");return;}
    if(action==="widget-add"){state.widgets.push(target.dataset.value);sheet.close();render();return;}
    if(action==="favorites-edit"){choices("Your everyday apps","Favorite editing is a preview of the proposed launcher model.",apps.map(item=>[item.name,"Add to favorites",item.icon]),"favorite-add");return;}
    if(action==="favorite-add"){sheet.close();toast(`${target.dataset.value} favorite previewed`);return;}
    if(action==="rename-device"){showSheet("Device name","Choose something recognizable when connecting your phone.",`<label class="form-field"><span>Name</span><input id="device-name" value="Living room" maxlength="40"></label>`,button("Save","save-name","primary-button"));return;}
    if(action==="save-name"){const value=document.getElementById("device-name").value.trim();if(!value){toast("Enter a device name");return;}sheet.close();toast(`Device name previewed: ${value}`);return;}
    if(action==="timer"){showSheet("A little time, set aside.","Choose a duration to preview the timer.",'<div class="timer-readout" id="timer-readout">05:00</div><div class="timer-presets">'+[5,10,15].map(m=>button(`${m} min`,"timer-duration","",`data-value="${m}"`)).join("")+'</div>',button("Start timer","timer-start","primary-button"));return;}
    if(action==="timer-duration"){document.getElementById("timer-readout").textContent=String(target.dataset.value).padStart(2,"0")+":00";return;}
    if(action==="timer-start"){toast(`${document.getElementById("timer-readout").textContent} timer started in preview`);sheet.close();return;}
    genericDetail(action);
  }
  document.addEventListener("click",event=>{
    const target=event.target.closest("[data-action]");
    if(target){
      event.preventDefault();
      const previousScreen=state.screen,details={...target.dataset};
      handleAction(target);
      if(!sheet.open&&state.screen===previousScreen&&!target.isConnected){
        const replacement=[...app.querySelectorAll("[data-action]")].find(el=>Object.entries(details).every(([key,value])=>el.dataset[key]===value));
        replacement?.focus({preventScroll:true});
      }
    }
  });
  document.addEventListener("change",event=>{
    const input=event.target;
    if(input.dataset.setting){state.settings[input.dataset.setting]=input.checked;if(input.dataset.setting==="reducedMotion")document.documentElement.classList.toggle("reduce-motion",input.checked);}
    if(input.name==="layout"){state.layout=input.value;render();}
  });
  function searchResults(query) {
    const q=query.trim().toLowerCase();
    return searchIndex.filter(item=>(item[0]+" "+item[3]).toLowerCase().includes(q)).slice(0,7).map(([label,category,key])=>button(`<span>${label}</span><small>${categories.find(c=>c[0]===category)[1]}</small>`,"search-setting","search-result",`data-category="${category}" data-key="${key}"`)).join("");
  }
  document.addEventListener("input",event=>{
    const input=event.target;
    if(input.dataset.setting==="brightness"){state.settings.brightness=Number(input.value);input.nextElementSibling.textContent=input.value+"%";}
    if(input.id==="settings-search"){document.getElementById("settings-nav").innerHTML=input.value.trim()?searchResults(input.value)||'<p class="settings-footnote">No settings found. Try “photos”, “clock”, or “sound”.</p>':categoryNav();}
    if(input.id==="apps-search"){const matched=apps.filter(item=>(item.name+" "+item.detail).toLowerCase().includes(input.value.toLowerCase()));document.getElementById("apps-grid").innerHTML=matched.length?matched.map(item=>appTile(item,true)).join(""):'<p class="muted">No apps found. Try a different name.</p>';}
    if(input.id==="global-search"){const query=input.value.trim().toLowerCase();document.getElementById("global-results").innerHTML=query?apps.filter(item=>item.name.toLowerCase().includes(query)).slice(0,3).map(item=>button(`${appIcon(item)}<span>${item.name}<small>App</small></span>`,"app","sheet-option",`data-name="${item.name}"`)).join("")+searchResults(query):"";}
  });
  document.addEventListener("submit",event=>{
    if(event.target.id!=="source-form")return;
    event.preventDefault();
    const data=new FormData(event.target),server=String(data.get("server")||"").trim(),access=String(data.get("access")||"").trim();
    let valid=false;
    try{const url=new URL(server);valid=(state.source==="SMB share"?url.protocol==="smb:":["https:","http:"].includes(url.protocol))&&Boolean(url.hostname);}catch{}
    if(!valid||access.length<4){document.getElementById("source-error").textContent=!valid?`Enter a complete ${state.source==="SMB share"?"smb://":"https://"} server address.`:"Enter a sample key with at least 4 characters.";document.getElementById(!valid?"source-server":"source-error").focus();return;}
    state.sourceStep=2;render();
  });
  document.addEventListener("keydown",event=>{
    if(event.key==="Escape"&&!sheet.open&&state.screen!=="home"){event.preventDefault();if(state.screen==="source"&&state.sourceStep>0){state.sourceStep--;render();}else navigate("home");}
    if(!["ArrowUp","ArrowDown","ArrowLeft","ArrowRight"].includes(event.key)||event.target.matches("input,textarea,select"))return;
    const focused=document.activeElement;
    if(!focused.matches("button,a"))return;
    const rect=focused.getBoundingClientRect(),cx=rect.x+rect.width/2,cy=rect.y+rect.height/2;
    const options=[...(sheet.open?sheet:app).querySelectorAll("button:not(:disabled),a[href]")].filter(el=>el!==focused&&el.getClientRects().length);
    const vertical=event.key==="ArrowDown"||event.key==="ArrowUp",positive=event.key==="ArrowDown"||event.key==="ArrowRight";
    let best=null,score=Infinity;
    options.forEach(el=>{const r=el.getBoundingClientRect(),dx=r.x+r.width/2-cx,dy=r.y+r.height/2-cy,forward=vertical?dy:dx,cross=vertical?dx:dy;if((positive?forward:-forward)<6)return;const s=Math.abs(forward)+Math.abs(cross)*3;if(s<score){best=el;score=s;}});
    if(best){event.preventDefault();best.focus();}
  });
  render();
})();
