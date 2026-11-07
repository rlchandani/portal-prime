// Widget kinds as they exist in source (HomeWidgetStore KIND_*, ApprovedWidgetLibrary built-ins)
// plus a renderer per kind that adapts to its span. All data is sample.
'use strict';

// settings: page opened by the widget's header action (ImmortalWidgetShell openPage / ApprovedHomeWidgetHeading).
// editSettings: page opened by the Customize-mode "Settings" action (HomeActivity.kt ~695-715).
const KINDS = [
  { kind: 'today', label: 'Today', icon: 'cal', settings: 'calendar', editSettings: 'calendar', retire: true,
    shows: ['Next event or sky facts', 'Next event plus feels-like, sun and moon', 'Event, sky facts and to-do count'] },
  { kind: 'ha', label: 'Home Assistant', icon: 'home', settings: 'dashboard', editSettings: 'ha',
    shows: ['One pinned device toggle', 'Two pinned toggles side by side', 'Pinned toggles plus a sensor'] },
  { kind: 'calendar', label: 'Calendar', icon: 'cal', settings: 'calendar', editSettings: 'calendar',
    shows: ['The next event', 'Next two events', 'Next five events, grouped by day'] },
  { kind: 'weather', label: 'Weather', icon: 'cloud', settings: 'weather', editSettings: 'weather',
    shows: ['Temperature and high/low', 'Now plus four hours', 'Now plus a five-day forecast'] },
  { kind: 'alarm', label: 'Alarm', icon: 'alarm', settings: 'alarm', editSettings: 'alarm',
    shows: ['Next alarm and its switch', 'Alarm time, repeat days, switch', 'Alarm, repeat days and snooze length'] },
  { kind: 'world_clock', label: 'World clock', icon: 'clock', settings: 'world', editSettings: 'world',
    shows: ['One other city', 'Two cities with offsets', 'Four cities with offsets'] },
  { kind: 'crypto', label: 'Crypto', icon: 'coin', settings: 'crypto', editSettings: 'crypto', retire: true,
    shows: ['One coin price and change', 'Three coins', 'Five coins with 24-hour change'] },
  { kind: 'quick_controls', label: 'Quick controls', icon: 'tools', settings: 'display', editSettings: 'quickbar', retire: true,
    shows: ['One shortcut tile', 'Two shortcut tiles', 'Four shortcut tiles'] },
  { kind: 'photo_memory', label: 'Photo memory', icon: 'photo', settings: 'preview', editSettings: 'sources',
    shows: ['A photo crop', 'A wide photo with caption', 'Photo with caption (Home hero is 2×4)'] },
  { kind: 'timers', label: 'Timers', icon: 'timer', settings: 'timers', editSettings: 'timers',
    shows: ['Time left, pause', 'Time left, pause and reset', 'Countdown ring, presets'] },
  { kind: 'todo', label: 'To-do', icon: 'check', settings: 'todo', editSettings: 'todo',
    shows: ['How many are left, the first task', 'Count plus three tasks', 'Count plus six tasks'] },
  { kind: 'news', label: 'News', icon: 'news', settings: 'news', editSettings: 'news', retire: true,
    shows: ['Top headline', 'Two headlines', 'Five headlines with sources'] },
  { kind: 'stocks', label: 'Stocks', icon: 'chart', settings: 'stocks', editSettings: 'stocks', retire: true,
    shows: ['One ticker', 'Three tickers', 'Five tickers with change'] },
  { kind: 'options', label: 'Options', icon: 'sliders', settings: 'options', editSettings: 'options', retire: true, advanced: true,
    shows: ['Top contract', 'Two contracts with delta', 'Contract scan list'] },
  { kind: 'appwidget', label: 'Android app widget', icon: 'apps', settings: 'appconfig', editSettings: 'appconfig',
    shows: ['The app’s own small layout', 'The app’s own wide layout', 'The app’s own large layout'] },
];
const KIND = Object.fromEntries(KINDS.map((k) => [k.kind, k]));

// ---------- helpers ----------
const shape = (w, h) => (w === 1 && h === 1 ? 's' : w === 1 ? 't' : h === 1 ? 'm' : 'l');
const row = (a, b, cls = '') => `<div class="r ${cls}"><span>${a}</span><b>${b}</b></div>`;
const tog = (on) => `<span class="tog ${on ? 'on' : ''}"></span>`;
const big = (v, cls = '') => `<div class="big ${cls}">${v}</div>`;

const R = {
  today(s) {
    const ev = '<div class="h26">Soccer practice</div><div class="s22">Today · 4:30 PM</div>';
    const facts = `<div class="facts"><div><small>Feels like</small><b>51°</b></div><div><small>Moon</small><b>Waning crescent</b></div>
      <div><small>Sunrise</small><b>7:01 AM</b></div><div><small>Sunset</small><b>4:43 PM</b></div></div>`;
    if (s === 's') return '<div class="h26 clamp1">Soccer practice · 4:30</div>';
    if (s === 'm') return `<div class="split"><div>${ev}</div><div class="s22">Feels like 51°<br>Sunset 4:43 PM</div></div>`;
    if (s === 't') return `${ev}<div class="gap"></div>${facts}`;
    return `<div class="split"><div>${ev}<div class="s22" style="margin-top:22px">✓ 5 to-dos open</div></div>${facts}</div>`;
  },
  ha(s) {
    const e = [['Kitchen lights', true], ['Porch light', false], ['Living room lamp', true], ['Garage door', false]];
    const ent = ([n, on]) => `<div class="ent"><span class="ic">${ICONS.bulb}</span><div><div class="h24 clamp1">${n}</div><div class="s20 ${on ? 'live' : ''}">${on ? 'On' : 'Off'}</div></div>${tog(on)}</div>`;
    if (s === 's') return ent(e[0]);
    if (s === 'm') return `<div class="split">${ent(e[0])}${ent(e[1])}</div>`;
    if (s === 't') return `<div class="h26">Your home dashboard</div><div class="s22">Open your rooms, devices and scenes.</div><span class="pill">Open dashboard</span>`;
    return `<div class="split">${ent(e[0])}${ent(e[1])}${ent(e[2])}${ent(e[3])}</div><div class="gap"></div>
      <div class="split"><div><div class="s20">Thermostat</div><div class="big sm">69°</div></div><div style="align-self:end"><span class="pill">Open dashboard</span></div></div>`;
  },
  calendar(s) {
    const ev = [['4:30 PM', 'Soccer practice', 'Today'], ['6:00 PM', 'Dinner with the Parks', 'Saturday'], ['9:00 AM', 'Farmers market', 'Sunday'], ['8:15 AM', 'School drop-off', 'Monday'], ['10:00 AM', 'Dentist · Ava', 'Monday']];
    const one = ([t, n, d]) => `<div class="s22">${d} · ${t}</div><div class="h26 clamp1">${n}</div>`;
    if (s === 's') return one(ev[0]);
    if (s === 'm') return `<div class="split"><div>${one(ev[0])}</div><div>${one(ev[1])}</div></div>`;
    const n = s === 't' ? 3 : 5;
    return ev.slice(0, n).map(([t, nm, d]) => `<div class="ev"><span class="s20">${d}<br>${t}</span><span class="h24 clamp1">${nm}</span></div>`).join('');
  },
  weather(s) {
    const now = `<div class="wx">${big('51°')}<span class="ic sunic">${ICONS.cloud}</span></div>`;
    const where = '<div class="h26">Edmonds</div><div class="s22">H: 55° · L: 51°</div>';
    const hours = `<div class="hours">${[['9 AM', 52], ['10 AM', 53], ['11 AM', 54], ['Noon', 55]].map(([h, t]) => `<div><small>${h}</small><span class="ic">${ICONS.cloudo}</span><b>${t}°</b></div>`).join('')}</div>`;
    const days = [['Today', 55, 51], ['Sat', 57, 49], ['Sun', 52, 46], ['Mon', 50, 44], ['Tue', 53, 45]].map(([d, hi, lo]) => row(d, `${hi}° <i>${lo}°</i>`)).join('');
    if (s === 's') return `<div class="wx">${big('51°', 'sm')}<div class="s22" style="margin-left:18px">Edmonds<br>H 55° · L 51°</div></div>`;
    if (s === 'm') return `<div class="split"><div>${big('51°', 'sm')}<div class="s22">Cloudy · Edmonds</div></div>${hours}</div>`;
    if (s === 't') return `${now}${where}<div class="gap"></div>`;
    return `<div class="split"><div>${now}${where}</div><div class="list">${days}</div></div>`;
  },
  alarm(s) {
    const a = `<div class="wx">${big('6:45<small>AM</small>', 'sm')}${tog(true)}</div><div class="s22">Weekdays</div>`;
    if (s === 'l') return `<div class="split"><div>${a}</div><div class="list">${row('Repeat', 'Mon–Fri')}${row('Snooze', '9 min')}${row('Sound', 'Morning birds')}</div></div>`;
    if (s === 'm') return `<div class="split"><div>${a}</div><div class="list">${row('Snooze', '9 min')}${row('Sound', 'Birds')}</div></div>`;
    return a;
  },
  world_clock(s) {
    const c = [['London', '4:04 PM', '8 hours ahead'], ['Mumbai', '9:34 PM', '13½ hours ahead'], ['Tokyo', '1:04 AM', 'Tomorrow'], ['New York', '11:04 AM', '3 hours ahead']];
    const one = ([n, t, o]) => `<div><div class="s20">${n}</div><div class="h34">${t}</div><div class="s20">${o}</div></div>`;
    if (s === 's') return one(c[0]);
    if (s === 'm') return `<div class="split">${one(c[0])}${one(c[1])}</div>`;
    if (s === 't') return `<div class="stack">${one(c[0])}${one(c[1])}</div>`;
    return `<div class="split" style="row-gap:24px">${c.map(one).join('')}</div>`;
  },
  crypto(s) {
    const c = [['BTC', '$68,420', '+1.2%', 1], ['ETH', '$3,512', '−0.4%', 0], ['SOL', '$162.10', '+3.1%', 1], ['DOGE', '$0.162', '+0.8%', 1], ['ADA', '$0.48', '−1.1%', 0]];
    const line = ([t, p, ch, up]) => row(t, `${p} <i class="${up ? 'up' : 'down'}">${ch}</i>`);
    if (s === 's') return `${big('$68,420', 'xs')}<div class="s22">BTC · <span class="up">+1.2%</span></div>`;
    return `<div class="list">${c.slice(0, s === 'm' ? 2 : s === 't' ? 3 : 5).map(line).join('')}</div>`;
  },
  quick_controls(s) {
    const q = [['mic', 'Hey Jarvis'], ['home', 'Home dashboard'], ['bulb', 'Lights'], ['film', 'Scenes']];
    const tile = ([i, l]) => `<div class="qc"><span class="ic">${ICONS[i]}</span><span class="h24 clamp1">${l}</span></div>`;
    const n = s === 's' ? 1 : s === 'l' ? 4 : 2;
    return `<div class="qcs ${s}">${q.slice(0, n).map(tile).join('')}</div>`;
  },
  photo_memory(s) {
    return `<img src="./assets/${s === 's' ? '03_yosemite' : '02_denali'}.jpg" alt=""><div class="scrim"></div>
      ${s === 's' ? '' : '<div class="cap"><div class="h34">Photo memory</div><div class="s22">Your selected photo source</div></div>'}`;
  },
  timers(s) {
    const t = big('6:42', 'live sm');
    if (s === 's') return `<div class="wx">${t}<span class="round">${ICONS.pause}</span></div>`;
    if (s === 'm' || s === 't') return `${t}<div class="s22">of 10 min</div><div class="chips"><span class="chip">${ICONS.pause}</span><span class="chip">Reset</span><span class="chip">+1 min</span></div>`;
    return `<div class="split"><div class="ring"><svg viewBox="0 0 100 100"><circle cx="50" cy="50" r="44" stroke="#414141" stroke-width="6" fill="none"/><circle cx="50" cy="50" r="44" stroke="#66c49e" stroke-width="6" fill="none" stroke-linecap="round" pathLength="100" stroke-dasharray="67 100" transform="rotate(-90 50 50)"/></svg><b>6:42</b></div>
      <div><div class="s22">Presets</div><div class="chips">${[1, 5, 10, 15, 30].map((m) => `<span class="chip">${m}m</span>`).join('')}</div></div></div>`;
  },
  todo(s) {
    const t = ['Oat milk', 'Call the plumber', 'Return library books', 'Water the fern', 'Birthday card for Ava', 'Bins out'];
    const task = (x, i) => `<div class="task ${i === 5 ? 'done' : ''}"><span class="box"></span><span class="clamp1">${x}</span></div>`;
    const count = `<div class="wx">${big('5', 'sm')}<span class="s22" style="margin-left:14px">left to do</span></div>`;
    if (s === 's') return `${count}`;
    if (s === 'm') return `<div class="split"><div>${count}</div><div>${t.slice(0, 2).map(task).join('')}</div></div>`;
    return `${count}${t.slice(0, s === 't' ? 3 : 6).map(task).join('')}`;
  },
  news(s) {
    const n = [['City council approves waterfront park', 'Local · 1 h'], ['Storm system expected Sunday night', 'Weather · 2 h'], ['Library extends weekend hours', 'Community · 3 h'], ['Ferry schedule changes next week', 'Transit · 5 h'], ['High school robotics team heads to state', 'Schools · 6 h']];
    const item = ([h, src]) => `<div class="nw"><div class="h24 clamp2">${h}</div><div class="s20">${src}</div></div>`;
    return n.slice(0, s === 's' ? 1 : s === 'l' ? 4 : 2).map(item).join('');
  },
  stocks(s) {
    const c = [['AAPL', '228.14', '+0.8%', 1], ['MSFT', '431.20', '−0.3%', 0], ['VOO', '512.07', '+0.4%', 1], ['NVDA', '139.60', '+2.2%', 1], ['AMZN', '201.33', '−0.6%', 0]];
    const line = ([t, p, ch, up]) => row(t, `${p} <i class="${up ? 'up' : 'down'}">${ch}</i>`);
    if (s === 's') return `${big('228.14', 'xs')}<div class="s22">AAPL · <span class="up">+0.8%</span></div>`;
    return `<div class="list">${c.slice(0, s === 'm' ? 2 : s === 't' ? 3 : 5).map(line).join('')}</div>`;
  },
  options(s) {
    const c = [['SPY 540C · Nov 15', 'Δ 0.32'], ['QQQ 470P · Nov 22', 'Δ −0.28'], ['AAPL 230C · Dec 20', 'Δ 0.41'], ['TSLA 260P · Nov 15', 'Δ −0.35'], ['MSFT 440C · Dec 20', 'Δ 0.30']];
    return `<div class="list">${c.slice(0, s === 's' ? 1 : s === 'm' ? 2 : s === 't' ? 3 : 5).map(([a, b]) => row(a, b)).join('')}</div>`;
  },
  appwidget(s, w) {
    const app = w?.app || 'Keep Notes';
    return `<div class="hosted"><div class="s20">${app}</div><div class="h26">${s === 's' ? 'Groceries' : 'Groceries · 4 items'}</div>
      ${s === 's' ? '' : '<div class="s22">Hosted Android layout; the app draws this, not Immortal.</div>'}</div>`;
  },
};

/** Card markup for one widget. `editable` adds the Customize-mode chrome from ApprovedWidgetCell. */
function widgetCard(w, span, opts = {}) {
  const k = KIND[w.kind];
  const s = shape(span.w, span.h);
  const isPhoto = w.kind === 'photo_memory';
  const isHosted = w.kind === 'appwidget';
  const head = isPhoto || isHosted ? '' : `<div class="head">${icon(k.icon)}<span class="ttl">${k.label}</span>
    ${k.settings === 'dashboard' ? '' : `<button class="hact" data-act="head-settings" data-id="${w.id}" type="button">Settings</button>`}</div>`;
  const edit = opts.editable ? `<div class="editbar">
      <button type="button" data-act="move" data-id="${w.id}">Move</button><button type="button" data-act="size" data-id="${w.id}">Size</button>
      <button type="button" data-act="edit-settings" data-id="${w.id}">Settings</button><button type="button" data-act="remove" data-id="${w.id}">Remove</button></div>
      <button class="rbadge" type="button" data-act="resize" data-id="${w.id}" aria-label="Resize widget">${ICONS.resize}</button>
      <span class="spantag">${span.w}×${span.h}</span>` : '';
  return `<div class="wcard ${isPhoto ? 'photo' : ''} sz-${s} ${opts.fresh ? 'fresh' : ''}" data-id="${w.id || ''}">${head}
    <div class="wbody">${R[w.kind](s, w)}</div>${edit}</div>`;
}
