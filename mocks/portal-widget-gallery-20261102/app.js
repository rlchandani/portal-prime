/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */
const $ = (selector) => document.querySelector(selector);
const escape = (value) => String(value ?? "").replace(/[&<>"']/g, c => ({"&":"&amp;","<":"&lt;",">":"&gt;",'"':"&quot;","'":"&#39;"}[c]));
const paths = {
  weather: '<path d="M8 16h10a4 4 0 0 0 0-8 6 6 0 0 0-11-1 4.5 4.5 0 0 0 1 9Z"/><path d="M5 3v2M2 7l2 1M14 3l-1 2"/>',
  world_clock: '<circle cx="12" cy="12" r="9"/><path d="M12 6v6l4 2"/>',
  timers: '<path d="M9 2h6M12 2v3M18 5l2 2"/><circle cx="12" cy="14" r="8"/><path d="M12 9v5l3 2"/>',
  calendar: '<rect x="3" y="5" width="18" height="16" rx="3"/><path d="M7 3v4M17 3v4M3 10h18M8 14h2M14 14h2M8 17h2"/>',
  alarm: '<path d="m3 5 3-3M18 2l3 3M6 20l-2 2M18 20l2 2"/><circle cx="12" cy="12" r="8"/><path d="M12 7v5l3 2"/>',
  crypto: '<path d="M9 4v16M13 3v2M13 19v2M7 5h8a3.5 3.5 0 0 1 0 7H9m0 0h7a3.5 3.5 0 0 1 0 7H7"/>',
  todo: '<rect x="3" y="3" width="18" height="18" rx="4"/><path d="m7 12 3 3 7-7"/>',
  news: '<rect x="3" y="3" width="18" height="18" rx="3"/><path d="M7 7h10M7 11h4v6H7zM14 11h3M14 14h3M14 17h3"/>',
  stocks: '<path d="M3 3v18h18M6 16l4-5 4 2 6-8M16 5h4v4"/>',
  options: '<path d="M4 6h16M4 12h16M4 18h16"/><circle cx="8" cy="6" r="2"/><circle cx="16" cy="12" r="2"/><circle cx="10" cy="18" r="2"/>',
  ha: '<path d="m3 10 9-7 9 7M5 9v12h14V9M9 21v-8h6v8"/>',
  today: '<circle cx="12" cy="12" r="4"/><path d="M12 2v2M12 20v2M2 12h2M20 12h2M5 5l1 1M18 18l1 1M5 19l1-1M18 6l1-1"/>',
  quick_controls: '<path d="m13 2-8 12h6l-1 8 9-12h-6z"/>',
  photo_memory: '<rect x="3" y="3" width="18" height="18" rx="3"/><circle cx="8" cy="8" r="1.5"/><path d="m3 18 6-6 4 4 3-3 5 5"/>',
  appwidget: '<rect x="3" y="3" width="7" height="7" rx="2"/><rect x="14" y="3" width="7" height="7" rx="2"/><rect x="3" y="14" width="7" height="7" rx="2"/><rect x="14" y="14" width="7" height="7" rx="2"/>',
  moon: '<path d="M20 14a9 9 0 0 1-10-10 9 9 0 1 0 10 10Z"/>',
  error: '<circle cx="12" cy="12" r="9"/><path d="M12 7v6M12 17v.1"/>'
};
const icon = (id) => `<svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.7" stroke-linecap="round" stroke-linejoin="round" aria-hidden="true">${paths[id] || paths.appwidget}</svg>`;
const title = (text) => `<p class="headline">${escape(text)}</p>`;
const detail = (text) => `<p class="detail">${escape(text)}</p>`;
const value = (text) => `<p class="value">${escape(text)}</p>`;
const item = (a, b, sub = "") => `<div class="item"><div><strong>${escape(a)}</strong>${sub ? `<div class="secondary">${escape(sub)}</div>` : ""}</div><span>${escape(b)}</span></div>`;
const action = (text, command = "explain", primary = false, disabled = false) => text ? `<button type="button" data-command="${escape(command)}" data-action="${escape(text)}" class="${primary ? "primary" : ""}" ${disabled ? "disabled" : ""}>${escape(text)}</button>` : "";
const landscape = '<div class="landscape" role="img" aria-label="Illustrative mountain landscape, not a personal photo"><svg viewBox="0 0 500 240" preserveAspectRatio="xMidYMid slice" xmlns="http://www.w3.org/2000/svg"><rect width="500" height="240" fill="#a6becd"/><circle cx="389" cy="51" r="23" fill="#f4dfac"/><path d="m0 197 111-132 127 153 72-134 190 143v50H0" fill="#698493"/><path d="m0 246 110-131 74 96 91-66 123 101" fill="#37545d"/><path d="M0 227q134-39 250-12t250-19v44H0" fill="#4f756c"/></svg></div>';
const sparkline = '<svg class="sparkline" viewBox="0 0 220 38" preserveAspectRatio="none" aria-hidden="true"><path d="m0 31 20-6 18 3 24-14 18 4 22-12 19 8 24-10 19 3 24-4 32-2" fill="none" stroke="currentColor" stroke-width="2"/></svg>';
let catalog = [];
let selection = {widget:"weather",size:"medium",state:"normal",view:"compare",theme:"dark",text:"1"};
let local = {};
let nextStateOnClose = null;
const measurements = [];

function fromUrl() {
  const p = new URLSearchParams(location.search);
  for (const k of Object.keys(selection)) if (p.has(k)) selection[k] = p.get(k);
  if (!catalog.some(w => w.id === selection.widget)) selection.widget = catalog[0].id;
  const w = currentWidget();
  if (!w.sizes.includes(selection.size)) selection.size = w.sizes[0];
  if (!w.states.some(s => s.id === selection.state)) selection.state = w.states[0].id;
  if (!["compare","portal"].includes(selection.view)) selection.view = "compare";
  if (!["dark","light"].includes(selection.theme)) selection.theme = "dark";
  if (!["1","1.25","1.5"].includes(selection.text)) selection.text = "1";
}
function currentWidget() { return catalog.find(w => w.id === selection.widget); }
function select(changes, push = true, keepLocal = false) {
  const start = performance.now();
  if ((changes.widget || changes.state) && !keepLocal) local = {};
  selection = {...selection,...changes};
  const w = currentWidget();
  if (!w.sizes.includes(selection.size)) selection.size = w.sizes[0];
  if (!w.states.some(s => s.id === selection.state)) selection.state = w.states[0].id;
  if (push) history.pushState({}, "", `?${new URLSearchParams(selection)}`);
  render();
  measurements.push(performance.now() - start);
  if (measurements.length > 200) measurements.shift();
}
function navigation() {
  const search = $("#search").value.trim().toLowerCase();
  $("#widget-list").innerHTML = catalog.filter(w => `${w.name} ${w.description}`.toLowerCase().includes(search)).map(w =>
    `<button type="button" data-widget="${escape(w.id)}" aria-current="${w.id === selection.widget}">${icon(w.id)}<span>${escape(w.name)}</span></button>`).join("");
}
function previewContent(w, s) {
  const expanded = selection.size === "large";
  const compact = selection.size === "small";
  const normalLike = ["normal","stale","refreshing","forecast_daily","forecast_off","forecast_missing","assistant_only","off"].includes(s.id);
  let body = "", buttons = action(s.action, "explain", true);
  const shortDetails = {
    empty:"Nothing to show yet. Your data stays yours.",
    setup:"Connect a source to see your information here.",
    loading:"Getting your information.",
    error:"Your information could not be loaded.",
    no_quotes:"No quotes were returned for this selection.",
    no_tickers:"Choose tickers to begin.",
    unknown:"The current state is not available.",
    configuration_error:"Open the app to check its widget configuration.",
    assistant_error:"Open Apps to check that your assistant is available."
  };
  if (normalLike) {
    switch (w.id) {
      case "weather":
        body = `<div class="weather-layout">${value(s.value)}${icon("weather")}</div>${title("Partly cloudy")}${detail(s.id === "forecast_missing" ? "Example City" : "Example City · H: 24° · L: 16°")}`;
        if (!compact && !["forecast_off","forecast_missing"].includes(s.id)) {
          const times = s.id === "forecast_daily" ? ["Mon","Tue","Wed",...(expanded ? ["Thu","Fri"] : [])] : ["Now","11 AM","Noon",...(expanded ? ["1 PM","2 PM"] : [])];
          body += `<div class="forecast">${times.map((h,i) => `<div><span>${h}</span>${icon("weather")}<strong>${(s.id === "forecast_daily" ? 24 : 21)+i}°</strong>${s.id === "forecast_daily" ? `<span class="low">${16+i}°</span>` : ""}</div>`).join("")}</div>`;
        }
        break;
      case "world_clock":
        body = detail("Example UTC city") + value(s.value) + detail("Monday · local time");
        if (!compact) body += item("Tokyo","7:30 PM","Monday");
        if (expanded) body += item("London","10:30 AM","Monday") + item("Los Angeles","2:30 AM","Monday");
        break;
      case "timers":
        body = detail("Running") + value(local.timerValue || s.value) + detail("A little time for yourself.");
        buttons = action("Pause","timer-pause",true) + (!compact ? action("Reset","timer-reset") : "");
        break;
      case "calendar":
        body = detail(s.id === "stale" ? "Saved event · refresh unavailable" : "Today · 10:30 AM") + title(s.value) + detail("Until 11:30 AM");
        if (!compact) body += item("Afternoon walk","4:00 PM","Today");
        if (expanded) body += item("Weekly planning","9:00 AM","Tomorrow") + item("Lunch with friends","12:30 PM","Tomorrow");
        buttons = action(s.action,"refresh",true,s.id === "refreshing");
        break;
      case "alarm":
        body = detail("Next alarm · tomorrow") + value(s.value) + detail("Weekdays · Morning");
        buttons = action("Disable","alarm-disable",true);
        break;
      case "crypto":
      case "stocks":
        body = detail(w.id === "crypto" ? "Bitcoin · sample quote" : "Example ticker · sample quote") + value(w.id === "crypto" ? "$43,210" : "$123.45") + '<p class="detail positive">+1.24% · sample change</p>';
        if (!compact) body += sparkline;
        if (expanded) body += item(w.id === "crypto" ? "ETH" : "SECOND","$2,345.67") + item(w.id === "crypto" ? "SOL" : "THIRD","$98.76");
        break;
      case "todo":
        body = value(local.done ? "2 tasks" : "3 tasks") + detail("A clear mind, one thing at a time.");
        if (!compact) body += item(local.done ? "✓ Review example plan" : "○ Review example plan","") + item("○ Water the plants","");
        if (expanded) body += item("○ Read a chapter","");
        buttons = action(local.done ? "Mark incomplete" : "Complete first task","todo-toggle",true) + (expanded ? action("Edit items") : "");
        break;
      case "news":
        body = detail("Example feed · sample headline") + title(s.value);
        if (!compact) body += detail("A neighborhood space brings people together.");
        if (expanded) body += item("A quieter way to get around","","Example feed") + item("The library opens its new wing","","Second example feed");
        break;
      case "options":
        body = detail("Sample contract · not a live quote") + title(s.value) + value("Δ 0.30") + detail("Mid-price $2.45 · Calls");
        if (expanded) body += item("EXAMPLE 130C 12/18","Δ 0.24");
        break;
      case "ha":
        {
          const off = local.off ?? s.id === "off";
          const bedroomOff = local.bedroomOff ?? true;
          body = detail("Example light") + title("Reading lamp") + value(off ? "Off" : "On");
          if (!compact) body += item("Bedroom lamp",bedroomOff ? "Off" : "On");
          if (expanded) body += item("Example temperature","22 °C") + item("Evening scene","Available");
          buttons = action(off ? "Turn on" : "Turn off","ha-toggle",true) + (!compact ? action(bedroomOff ? "Bedroom on" : "Bedroom off","bedroom-toggle") : "");
        }
        break;
      case "today":
        body = detail("Monday · November 2") + title(s.value) + detail(s.id === "stale" ? "Saved event · 10:30 AM" : "Next · 10:30 AM");
        if (!compact) body += item("Partly cloudy","21°","Example City");
        if (expanded) body += item("Sunset","6:42 PM") + item("To-do","3 items");
        break;
      case "quick_controls":
        body = title(s.id === "assistant_only" ? "Hey Jarvis" : "Your everyday shortcuts") + detail("Each shortcut opens its installed app.");
        buttons = s.id === "assistant_only" ? action("Hey Jarvis","explain",true) : action("Home","explain",true) + (!compact ? action("Hey Jarvis") : "") + (expanded ? action("Lights") + action("Scenes") : "");
        break;
      case "photo_memory":
        body = landscape + detail("Your chosen photo source");
        break;
      case "appwidget":
        body = `<div class="state-art">${icon("appwidget")}</div>${title("Installed app widget")}${detail("The provider owns its content and layout.")}`;
        break;
    }
  } else if (w.id === "timers" && s.id === "paused") {
    body = detail("Paused") + value(local.timerValue || s.value) + detail("Ready when you are.");
    buttons = action("Start","timer-start",true) + action("Reset","timer-reset");
  } else if (w.id === "timers" && s.id === "empty") {
    body = value("Ready") + detail("Choose how long you need.");
    buttons = action("5 min","timer-start",true) + (!compact ? action("10 min","timer-start") + action("Custom") : "");
  } else if (w.id === "timers" && s.id === "ringing") {
    body = title("Time’s up") + detail("Your timer has finished.");
    buttons = action("Stop","timer-reset",true);
  } else if (w.id === "alarm" && s.id === "empty") {
    body = detail("Alarm is off") + value(s.value) + detail("Turn it on when you need it.");
    buttons = action("Enable","alarm-enable",true);
  } else if (w.id === "todo" && s.id === "complete") {
    body = value("All done") + detail("Everything on your list is complete.") + (!compact ? item("✓ Review example plan","") : "");
  } else if (["ha","quick_controls"].includes(w.id) && s.id === "dashboard") {
    body = `<div class="state-art">${icon("ha")}</div>${title("Your home dashboard")}${detail("Continue in your configured dashboard.")}`;
  } else if (w.id === "ha" && ["scene","sensor","unknown"].includes(s.id)) {
    body = title(s.headline) + value(s.value) + detail(s.id === "unknown" ? "Current state is unknown. A new pending/error treatment is proposed." : "Illustrative entity");
    buttons = action(s.action,"explain",true,s.id === "unknown");
  } else if (w.id === "photo_memory" && ["empty","web"].includes(s.id)) {
    body = `<div class="state-art">${icon("photo_memory")}</div>${title(s.id === "web" ? "Your web source" : "Choose your photos")}${detail(s.id === "web" ? "Open your configured web page." : "A fallback photo remains visible in the current app.")}`;
  } else if (w.id === "today" && s.id === "weather_unavailable") {
    body = title("Design session") + detail("Today · 10:30 AM") + item("Weather unavailable","—");
  } else {
    body = `<div class="state-art">${s.id === "loading" ? '<div class="state-loading" aria-hidden="true"></div>' : icon(s.id === "error" || s.id.endsWith("_error") ? "error" : w.id)}</div>${title(s.headline)}${detail(shortDetails[s.id] || "Choose a source or adjust your selection.")}`;
    if (["loading","refreshing"].includes(s.id)) buttons = action(s.action,"explain",true,true);
    if (w.id === "today" && s.id !== "setup") body += item("Example weather","21°");
  }
  return `<div class="body">${body}</div>${buttons ? `<div class="actions">${buttons}</div>` : ""}`;
}
function render() {
  const w = currentWidget(), s = w.states.find(s => s.id === selection.state);
  document.documentElement.dataset.theme = selection.theme;
  document.documentElement.style.setProperty("--scale",selection.text);
  $("#theme").innerHTML = icon(selection.theme === "dark" ? "today" : "moon");
  $("#theme").setAttribute("aria-label",`Switch to ${selection.theme === "dark" ? "light" : "dark"} theme`);
  $("#title").textContent = w.name;
  $("#description").textContent = w.description.replace(/^Generated fixture; no live data\.\s*/,"");
  $("#widget-count").textContent = catalog.length;
  navigation();
  $("#size-controls").innerHTML = ["small","medium","large"].map(size => `<button type="button" data-size="${size}" aria-pressed="${selection.size === size}" ${w.sizes.includes(size) ? "" : "disabled"}>${size[0].toUpperCase()+size.slice(1)}</button>`).join("");
  $("#state").innerHTML = w.states.map(s => `<option value="${escape(s.id)}">${escape(s.label)}</option>`).join("");
  $("#state").value = s.id;
  $("#text-size").value = selection.text;
  document.querySelectorAll("[data-view]").forEach(b => b.setAttribute("aria-pressed",b.dataset.view === selection.view));
  $("#state-origin").textContent = s.implemented ? "State exists in the current app · layout proposed" : "Proposed state · requires native implementation";
  $("#state-origin").classList.toggle("proposed", !s.implemented);
  const platforms = selection.view === "portal" ? ["portal"] : ["android","ios","portal"];
  $("#previews").classList.toggle("portal-only",selection.view === "portal");
  $("#previews").innerHTML = platforms.map(p => `<article class="platform" data-platform="${p}"><div class="platform-heading">${p === "ios" ? "iOS" : p === "portal" ? "Meta Portal" : "Android"}<span>${p === "portal" ? "Proposed native" : "Design reference"}</span></div><div class="preview-stage"><div class="widget ${p} ${selection.size}" aria-label="${escape(w.name)} ${p} ${escape(s.label)} preview"><div class="widget-header"><span>${escape(w.name)}</span>${icon(w.id)}</div>${previewContent(w,s)}</div></div><p class="platform-caption">${p === "portal" ? "Approved neutral palette. Direct Compose rendering; no backdrop blur." : p === "ios" ? "Quiet surfaces, rounded corners and a clear leading value. Design interpretation." : "Room for readable content and focused actions. Design interpretation."}</p></article>`).join("");
  $("#size-title").textContent = ({small:"One clear summary.",medium:"Summary with useful actions.",large:"More space, more useful detail."})[selection.size];
  $("#rationale").textContent = w.sizeRationale;
  $("#implementation-title").textContent = w.id === "appwidget" ? "Provider controls the presentation." : s.implemented ? "Preserve the working state contract." : "Add an explicit state contract.";
  $("#source").innerHTML = `<code>${escape(w.sourceSymbol)} · ${escape(w.source)}</code><br>${escape(s.detail.replace(/^Generated fixture; no live data\.\s*/,""))}`;
  $("#status").textContent = `${w.name}, ${selection.size}, ${s.label}`;
}
function coverage() {
  const all = catalog.flatMap(w => w.states), existing = all.filter(s => s.implemented).length;
  $("#coverage-summary").textContent = `${catalog.length} widget categories · ${all.length} catalog states · ${existing} source-backed states · ${all.length-existing} proposed states. This is a source audit, not a claim that every runtime combination is verified.`;
  $("#coverage-table").innerHTML = `<table class="coverage-table"><thead><tr><th>Widget</th><th>States</th><th>Sizes</th><th>Review</th></tr></thead><tbody>${catalog.map(w => `<tr><td>${escape(w.name)}</td><td>${w.states.map(s => `${escape(s.label)}${s.implemented ? "" : " *"}`).join(" · ")}</td><td>${w.sizes.join(", ")}</td><td><button type="button" data-widget="${w.id}">Open</button></td></tr>`).join("")}</tbody></table>`;
}
function explain(label) {
  $("#action-title").textContent = label;
  const w = currentWidget();
  const extra = w.id === "appwidget" ? "The installed provider supplies its own configuration and supported sizes."
    : w.id === "quick_controls" ? "This shortcut opens the configured dashboard or installed app. It does not directly toggle an entity."
    : w.id === "photo_memory" ? "This opens the chosen photo source/screensaver path."
    : "The app uses this widget’s existing data source or settings editor.";
  $("#action-description").textContent = `${extra} This review uses sample data; no real device, account or service command is sent.`;
  $("#close-dialog").textContent = nextStateOnClose ? "Show sample result" : "Done";
  $("#action-dialog").showModal();
}
document.addEventListener("click", e => {
  const b = e.target.closest("button");
  if (!b || b.disabled) return;
  if (b.dataset.widget) select({widget:b.dataset.widget,state:"normal"});
  else if (b.dataset.size) select({size:b.dataset.size});
  else if (b.dataset.view) select({view:b.dataset.view});
  else if (b.id === "theme") select({theme:selection.theme === "dark" ? "light" : "dark"});
  else if (b.id === "close-dialog") $("#action-dialog").close();
  else if (b.dataset.command) {
    const command = b.dataset.command;
    if (command.startsWith("timer-")) {
      if (command === "timer-reset") delete local.timerValue;
      if (b.dataset.action === "5 min") local.timerValue = "05:00";
      if (b.dataset.action === "10 min") local.timerValue = "10:00";
      select({state:command === "timer-pause" ? "paused" : command === "timer-reset" ? "empty" : "normal"},true,true);
    }
    else if (command.startsWith("alarm-")) select({state:command === "alarm-disable" ? "empty" : "normal"});
    else if (["todo-toggle","ha-toggle","bedroom-toggle"].includes(command)) {
      const key = command === "todo-toggle" ? "done" : command === "ha-toggle" ? "off" : "bedroomOff";
      const initial = key === "off" ? selection.state === "off" : key === "bedroomOff";
      local[key] = !(local[key] ?? initial);
      render();
    } else if (command === "refresh") {
      select({state:"refreshing"});
      nextStateOnClose = "normal";
      explain("Refreshing sample calendar");
    } else explain(b.dataset.action);
  }
});
$("#action-dialog").addEventListener("close",() => {
  if (nextStateOnClose) {
    const result = nextStateOnClose;
    nextStateOnClose = null;
    select({state:result});
  }
});
$("#state").addEventListener("change", e => select({state:e.target.value}));
$("#text-size").addEventListener("change", e => select({text:e.target.value}));
$("#search").addEventListener("input",navigation);
window.addEventListener("popstate",() => {fromUrl();local = {};render();});
try {
  const response = await fetch("./catalog.json");
  if (!response.ok) throw Error(`Catalog HTTP ${response.status}`);
  catalog = await response.json();
  if (!catalog.length) throw Error("Empty widget catalog");
  fromUrl();render();coverage();
  // Read-only review hooks for deterministic browser QA, not a native performance claim.
  window.widgetStudio = {
    catalog,
    select,
    get selection() {return {...selection};},
    get timings() {return [...measurements];}
  };
} catch (error) {
  $("#title").textContent = "The gallery couldn’t load.";
  $("#description").textContent = "Reload this page after the local review server is available.";
  console.error(error);
}
