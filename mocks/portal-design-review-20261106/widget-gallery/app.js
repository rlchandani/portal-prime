// Home widget family — design review 2026-11-06. Sample data only; no network, no commands.
'use strict';

const svg = (body, sw = 2.4) =>
  `<svg viewBox="0 0 48 48" fill="none" stroke="currentColor" stroke-width="${sw}" stroke-linecap="round" stroke-linejoin="round" aria-hidden="true">${body}</svg>`;

const ICON = {
  sun: svg('<circle cx="24" cy="24" r="8"/><path d="M24 6v4M24 38v4M6 24h4M38 24h4M11.3 11.3l2.8 2.8M33.9 33.9l2.8 2.8M11.3 36.7l2.8-2.8M33.9 14.1l2.8-2.8"/>'),
  moon: svg('<path d="M36 30.5A14 14 0 0 1 17.5 12a14 14 0 1 0 18.5 18.5z"/>'),
  cloud: svg('<path d="M15 36h19a8 8 0 0 0 1-15.9A11 11 0 0 0 14 22a7 7 0 0 0 1 14z"/>'),
  partly: svg('<path d="M19 13.5a7 7 0 0 1 11.6 4.8"/><path d="M19 6v2.5M8.5 10.5l1.8 1.8M5 21h2.5"/><path d="M17 40h17a7 7 0 0 0 .9-13.9A9.5 9.5 0 0 0 16.6 27 6.5 6.5 0 0 0 17 40z"/>'),
  rain: svg('<path d="M15 30h19a8 8 0 0 0 1-15.9A11 11 0 0 0 14 16a7 7 0 0 0 1 14z"/><path d="M18 35l-2 5M26 35l-2 5M34 35l-2 5"/>'),
  mic: svg('<rect x="18" y="7" width="12" height="22" rx="6"/><path d="M11 23a13 13 0 0 0 26 0M24 36v6"/>'),
  search: svg('<circle cx="21" cy="21" r="12"/><path d="M30 30l10 10"/>'),
  gear: svg('<circle cx="24" cy="24" r="5.5"/><path d="M20.5 6h7l1 5.2 3.6 2.1 5-1.8 3.5 6-4 3.5v4l4 3.5-3.5 6-5-1.8-3.6 2.1-1 5.2h-7l-1-5.2-3.6-2.1-5 1.8-3.5-6 4-3.5v-4l-4-3.5 3.5-6 5 1.8 3.6-2.1z"/>', 2.2),
  home: svg('<path d="M8 22L24 9l16 13v17a2 2 0 0 1-2 2H28V30h-8v11H10a2 2 0 0 1-2-2z"/>'),
  apps: svg('<rect x="8" y="8" width="13" height="13" rx="3"/><rect x="27" y="8" width="13" height="13" rx="3"/><rect x="8" y="27" width="13" height="13" rx="3"/><rect x="27" y="27" width="13" height="13" rx="3"/>'),
  tools: svg('<path d="M29 9a9 9 0 0 0-8.6 11.7L9 32a4.2 4.2 0 0 0 6 6l11.3-11.4A9 9 0 0 0 38 18l-6 2-4-4 2-6z"/>'),
  pause: svg('<path d="M18 13v22M30 13v22"/>', 4),
  reset: svg('<path d="M12 24a12 12 0 1 0 4-9"/><path d="M12 9v7h7"/>', 3),
  scene: svg('<path d="M24 8l4.5 9.5L39 19l-7.5 7.2L33.3 37 24 32l-9.3 5 1.8-10.8L9 19l10.5-1.5z"/>'),
  photo: svg('<rect x="7" y="10" width="34" height="28" rx="4"/><circle cx="17" cy="20" r="3.5"/><path d="M7 33l10-9 8 7 6-5 10 8"/>'),
};
const icon = (name, cls = '') => `<span class="icon ${cls}">${ICON[name]}</span>`;

// ---------- widget renderers: (size, props) -> inner HTML; class modifiers via props ----------
const R = {
  weather(size, p) {
    if (p.state === 'unavailable') return `<div class="grow"></div><div class="title">Weather unavailable</div>
      <div class="meta">Last updated ${p.updated}. Retrying automatically.</div>`;
    const now = `<div class="row"><div class="hero ${size === 's' ? 'sm' : ''}">${p.temp}°</div>${icon(p.icon, size === 'l' ? 'big' : '')}</div>`;
    const ctx = `<div class="body clamp1" style="margin-top:12px">${p.cond}</div><div class="meta clamp1">${p.city} · H ${p.hi}° L ${p.lo}°</div>`;
    const hours = (n) => `<div class="hours">${p.hours.slice(0, n).map(([t, i, d]) =>
      `<div><div class="meta">${t}</div>${icon(i, 'sm')}<div class="t">${d}°</div></div>`).join('')}</div>`;
    if (size === 's') return `${now}<div class="grow"></div><div class="meta clamp1">${p.cond} · H ${p.hi}° L ${p.lo}°</div>`;
    if (size === 'm') return p.state === 'forecast_off'
      ? `<div class="row grow">${now}<div class="col" style="margin-left:24px">${ctx}</div></div>`
      : `<div class="split"><div class="col">${now}<div class="grow"></div><div class="meta clamp1">${p.cond} · ${p.city}</div></div>${hours(4)}</div>`;
    return `<div class="row"><div class="col grow">${now}${ctx}</div></div><div class="grow"></div>
      <div class="days">${p.days.slice(0, 4).map(([d, i, hi, lo]) => `<div class="day"><span>${d}</span>${icon(i, 'sm')}<span></span><span class="hi">${hi}°</span><span class="lo">${lo}°</span></div>`).join('')}</div>`;
  },

  calendar(size, p) {
    if (p.state === 'setup') return `<div class="grow"></div><div class="title">Add your calendar</div><div class="meta">Paste a calendar link from your phone to see what's next.</div>`;
    if (p.state === 'empty') return `<div class="meta">Up next</div><div class="grow"></div><div class="title">Nothing scheduled</div><div class="meta">Through ${p.through}</div>`;
    const ev = p.events;
    const kicker = (e) => `<div class="body ${e.now ? 'live' : ''}" style="color:${e.now ? '' : 'var(--muted)'}">${e.when}</div>`;
    const stale = p.state === 'stale' ? `<div class="meta">Couldn't refresh. Showing saved events.</div>` : '';
    if (size === 's') return `<div class="meta">Up next</div><div class="grow"></div>${kicker(ev[0])}<div class="title clamp2">${ev[0].title}</div>${stale}`;
    if (size === 'm') return `<div class="meta">Up next</div><div class="grow"></div>
      <div class="split" style="height:auto">${ev.slice(0, 2).map((e) => `<div class="col">${kicker(e)}<div class="title clamp1">${e.title}</div></div>`).join('')}</div>${stale}`;
    let last = '';
    const rows = ev.slice(0, 5).map((e) => {
      const head = e.day !== last ? `<div class="group">${e.day}</div>` : '';
      last = e.day;
      return `${head}<div class="ev"><span class="meta ${e.now ? 'live' : ''}">${e.time}</span><span class="body clamp1">${e.title}</span></div>`;
    }).join('');
    return `${rows}<div class="grow"></div>${stale}`;
  },

  timer(size, p) {
    if (p.state === 'ringing') return `<div class="meta">Timer</div><div class="grow"></div>
      <div class="row"><div class="title grow">Time's up</div><span class="chip">Stop</span></div>`;
    if (p.state === 'idle') {
      const presets = size === 's' ? [5, 10, 15] : [1, 5, 10, 15, 30];
      return `<div class="meta">Timer</div><div class="grow"></div><div class="chips">${presets.map((m) => `<span class="chip">${m} min</span>`).join('')}${size === 's' ? '' : '<span class="chip">Custom</span>'}</div>`;
    }
    const tone = p.state === 'paused' ? '' : 'live';
    const status = p.state === 'paused' ? 'Paused' : `of ${p.total}`;
    const controls = `<span class="round">${p.state === 'paused' ? '<b style="font-size:24px">▶</b>' : ICON.pause}</span>`;
    if (size === 's') return `<div class="hero ${tone}">${p.left}</div><div class="grow"></div><div class="row"><div class="meta grow">${status}</div>${controls}</div>`;
    if (size === 'm') return `<div class="row grow"><div class="col grow"><div class="hero ${tone}">${p.left}</div><div class="meta" style="margin-top:14px">${status}</div></div>
      ${controls}<span class="round">${ICON.reset}</span></div>`;
    const frac = p.fraction;
    return `<div class="row grow" style="gap:48px"><div class="ring-wrap"><svg viewBox="0 0 100 100"><circle cx="50" cy="50" r="45" stroke="var(--raised)" stroke-width="5" fill="none"/>
      <circle cx="50" cy="50" r="45" stroke="${tone ? 'var(--accent)' : 'var(--faint)'}" stroke-width="5" fill="none" stroke-linecap="round" pathLength="100" stroke-dasharray="${frac * 100} 100"/></svg>
      <div class="hero ${tone}">${p.left}</div></div><div class="col grow" style="gap:20px"><div class="meta">${status}</div><div class="row" style="gap:16px">${controls}<span class="round">${ICON.reset}</span></div></div></div>`;
  },

  todo(size, p) {
    const left = p.tasks.filter((t) => !t.done).length;
    if (left === 0) return `<div class="meta">To-do</div><div class="grow"></div><div class="title">All done</div><div class="meta">${p.tasks.length === 1 ? '1 task' : p.tasks.length + ' tasks'} checked off</div>`;
    const task = (t) => `<div class="task ${t.done ? 'done' : ''}"><span class="box ${t.done ? 'done' : ''}"></span><span class="clamp1">${t.text}</span></div>`;
    if (size === 's') return `<div class="row"><div class="hero sm">${left}</div><div class="meta">left to do</div></div><div class="grow"></div>${task(p.tasks.find((t) => !t.done))}`;
    if (size === 'm') return `<div class="row grow" style="align-items:stretch;gap:44px"><div class="col" style="width:200px"><div class="hero sm">${left}</div><div class="meta">left to do</div></div>
      <div class="col grow" style="justify-content:center">${p.tasks.slice(0, 3).map(task).join('')}</div></div>`;
    return `<div class="row"><div class="hero sm">${left}</div><div class="meta">left to do</div></div><div style="margin-top:16px">${p.tasks.slice(0, 6).map(task).join('')}</div>`;
  },

  ha(size, p) {
    const ent = (e, big) => `<div class="row"><div class="col grow"><div class="${big ? 'title' : 'body'} clamp1">${e.name}</div>
      <div class="meta ${e.on ? 'live' : ''}">${e.unknown ? 'Unavailable' : e.on ? 'On' : 'Off'}</div></div><span class="toggle ${e.on ? 'on' : ''} ${e.unknown ? 'off-limits' : ''}"></span></div>`;
    if (p.state === 'setup') return `<div class="grow"></div><div class="title">Connect Home Assistant</div><div class="meta">Pin lights and switches you use every day.</div>`;
    if (size === 's') return `<div class="grow"></div>${ent(p.entities[0], true)}`;
    if (size === 'm') return `<div class="grow"></div><div class="split" style="height:auto">${p.entities.slice(0, 2).map((e) => ent(e, true)).join('')}</div>`;
    return `<div class="col" style="gap:28px">${p.entities.slice(0, 2).map((e) => ent(e, true)).join('')}</div><div class="grow"></div>
      <div class="split" style="height:auto"><div class="col"><div class="meta">${p.sensor.name}</div><div class="hero sm">${p.sensor.value}</div></div>
      <div class="col" style="justify-content:flex-end"><span class="chip" style="gap:12px;align-self:flex-start"><span class="icon sm" style="color:inherit">${ICON.scene}</span>${p.scene}</span></div></div>`;
  },

  clock(size, p) {
    const city = (c, big) => `<div class="col"><div class="${big ? 'body' : 'meta'}">${c.city}</div><div class="hero ${big ? '' : 'sm'}">${c.time}<span class="unit">${c.ampm}</span></div><div class="meta">${c.rel}</div></div>`;
    if (p.state === 'empty') return `<div class="grow"></div><div class="title">Add a city</div><div class="meta">See the time where family lives.</div>`;
    if (size === 's') return city(p.cities[0], false);
    if (size === 'm') return `<div class="split">${p.cities.slice(0, 2).map((c) => city(c, false)).join('')}</div>`;
    return `<div class="split" style="row-gap:40px;grid-template-rows:1fr 1fr">${p.cities.slice(0, 4).map((c) => city(c, false)).join('')}</div>`;
  },

  alarm(size, p) {
    if (p.state === 'empty') return `<div class="meta">Alarm</div><div class="grow"></div><div class="title">No alarm set</div>`;
    return `<div class="meta">Alarm</div><div class="grow"></div><div class="row"><div class="col grow"><div class="hero sm" style="${p.on ? '' : 'color:var(--faint)'}">${p.time}<span class="unit">${p.ampm}</span></div>
      <div class="meta">${p.on ? p.repeat : 'Off'}</div></div><span class="toggle ${p.on ? 'on' : ''}"></span></div>`;
  },

  photo(size, p) {
    if (p.state === 'empty') return `<div class="grow"></div>${size === 's' ? '' : icon('photo', 'big')}<div class="title" style="margin-top:16px">Choose photos for Home</div><div class="meta">Pick a folder or album in Settings &gt; Photos.</div>`;
    const cap = p.caption ? `<div class="cap"><div class="title">${p.caption.place}</div><div class="meta" style="font-size:24px">${p.caption.date}</div></div>` : '';
    return `<img src="${p.src}" alt="">${cap}`;
  },
};

function widget(kind, size, p = {}, place = '') {
  const cls = ['w', size, kind === 'photo' && p.state !== 'empty' ? 'photo' : '', p.state === 'ringing' ? 'alert' : '', p.needs ? 'needs' : '', p.state === 'empty' && kind === 'photo' ? 'quiet' : ''].join(' ');
  const tag = p.needs ? `<span class="note-tag">Needs: ${p.needs}</span>` : p.label ? `<span class="state-tag">${p.label}</span>` : '';
  return `<div class="${cls}" style="${place}">${tag}${R[kind](size, p)}</div>`;
}

// ---------- sample data ----------
const D = {
  wxNight: { temp: 9, icon: 'moon', cond: 'Clear', city: 'Portland', hi: 13, lo: 6,
    hours: [['7 PM', 'moon', 8], ['8 PM', 'moon', 8], ['9 PM', 'cloud', 7], ['10 PM', 'cloud', 7], ['11 PM', 'rain', 6]],
    days: [['Sat', 'partly', 14, 7], ['Sun', 'rain', 11, 6], ['Mon', 'rain', 10, 5], ['Tue', 'cloud', 12, 6], ['Wed', 'sun', 13, 4]] },
  wxDay: { temp: 14, icon: 'partly', cond: 'Partly cloudy', city: 'Portland', hi: 18, lo: 9,
    hours: [['9 AM', 'partly', 15], ['10 AM', 'partly', 16], ['11 AM', 'sun', 17], ['Noon', 'sun', 18], ['1 PM', 'sun', 18]],
    days: [['Today', 'partly', 18, 9], ['Sat', 'sun', 19, 9], ['Sun', 'rain', 13, 8], ['Mon', 'rain', 11, 7], ['Tue', 'cloud', 12, 6]] },
  calEvening: { events: [
    { day: 'Tomorrow', time: '9:00 AM', when: 'Tomorrow · 9:00 AM', title: 'Farmers market' },
    { day: 'Sunday', time: '4:30 PM', when: 'Sunday · 4:30 PM', title: 'Video call with Grandma' },
    { day: 'Sunday', time: '6:00 PM', when: 'Sunday · 6:00 PM', title: 'Dinner at the Parks' },
    { day: 'Monday', time: '8:15 AM', when: 'Monday · 8:15 AM', title: 'School drop-off' }] },
  calMorning: { events: [
    { day: 'Today', time: 'Now', when: 'Now · until 8:00', title: 'Breakfast', now: true },
    { day: 'Today', time: '8:15 AM', when: 'In 33 min', title: 'School drop-off' },
    { day: 'Today', time: '12:30 PM', when: '12:30 PM', title: 'Lunch with Sam' },
    { day: 'Today', time: '6:00 PM', when: '6:00 PM', title: 'Swim practice' },
    { day: 'Tomorrow', time: '9:00 AM', when: 'Tomorrow · 9:00 AM', title: 'Farmers market' }] },
  timerRun: { state: 'running', left: '6:42', total: '10 min', fraction: 0.67 },
  todo: { tasks: [{ text: 'Oat milk' }, { text: 'Call the plumber' }, { text: 'Return library books' }, { text: 'Water the fern' }, { text: 'Birthday card for Ava' }, { text: 'Bins out', done: true }] },
  ha: { entities: [{ name: 'Kitchen lights', on: true }, { name: 'Porch light', on: false }], sensor: { name: 'Living room', value: '21.5°' }, scene: 'Evening' },
  clock: { cities: [{ city: 'London', time: '3:42', ampm: 'PM', rel: '8 hours ahead' }, { city: 'Mumbai', time: '9:12', ampm: 'PM', rel: '13½ hours ahead' },
    { city: 'New York', time: '10:42', ampm: 'AM', rel: '3 hours ahead' }, { city: 'Tokyo', time: '12:42', ampm: 'AM', rel: 'Tomorrow' }] },
  alarm: { time: '6:45', ampm: 'AM', repeat: 'Weekdays', on: true },
};

const HOMES = {
  dark: { greet: 'Good evening.', time: '6:18', day: 'Friday', date: 'November 6', img: './assets/01_wilderness.jpg',
    cells: () => [
      widget('photo', 'l', { src: './assets/01_wilderness.jpg' }, 'grid-column:1/span 2;grid-row:1/span 4'),
      widget('weather', 'm', D.wxNight, 'grid-column:3/span 2;grid-row:1'),
      widget('calendar', 'm', D.calEvening, 'grid-column:3/span 2;grid-row:2'),
      widget('timer', 's', D.timerRun, 'grid-column:3;grid-row:3'),
      widget('ha', 's', D.ha, 'grid-column:4;grid-row:3'),
      widget('todo', 'm', D.todo, 'grid-column:3/span 2;grid-row:4')] },
  light: { greet: 'Good morning.', time: '7:42', day: 'Friday', date: 'November 6',
    cells: () => [
      widget('photo', 'l', { src: './assets/03_yosemite.jpg' }, 'grid-column:1/span 2;grid-row:1/span 4'),
      widget('weather', 'm', D.wxDay, 'grid-column:3/span 2;grid-row:1'),
      widget('calendar', 'l', D.calMorning, 'grid-column:3/span 2;grid-row:2/span 2'),
      widget('alarm', 's', { ...D.alarm }, 'grid-column:3;grid-row:4'),
      widget('clock', 's', D.clock, 'grid-column:4;grid-row:4')] },
};

function renderHome(theme) {
  const h = HOMES[theme];
  const el = document.getElementById('home');
  el.setAttribute('aria-label', `Proposed Portal Home, ${theme} theme, sample data`);
  el.innerHTML = `<div class="top"><div class="greet">${h.greet}</div><div class="clock">${h.time}</div>
      <div class="date">${h.day}<br>${h.date}</div><div class="hbtns"><span class="hbtn">${ICON.mic}</span><span class="hbtn">${ICON.search}</span><span class="hbtn">${ICON.gear}</span></div></div>
    <div class="grid">${h.cells().join('')}</div>
    <div class="dock"><span class="on">${ICON.home}Home</span><span>${ICON.apps}Apps</span><span>${ICON.tools}Tools</span></div>
    <div class="mock-tag">Mock · sample data</div>`;
  document.getElementById('home-caption').textContent = theme === 'dark'
    ? 'Evening, 2160 × 1440. The photo takes half the screen; the right half answers “what now?”: tonight, the next plans, the pasta timer, the kitchen lights, the list. Timer and light are green because they are live.'
    : 'Morning, light theme. The same family in a different order: the day ahead gets the large slot. The event happening now is the only green on screen.';
}

// ---------- specimen boards ----------
const BOARDS = [
  { kind: 'photo', name: 'Photo', verdict: 'Keep', why: 'Full bleed with no title. Today it shows the words “Photo memory” and “Your selected photo source” over the picture; that text goes. Tap opens the photo preview, as it does now.',
    cells: [['s', { src: './assets/02_denali.jpg' }], ['m', { src: './assets/03_yosemite.jpg' }], ['l', { src: './assets/01_wilderness.jpg', caption: { place: 'Hurricane Ridge', date: 'August 2024' }, needs: 'photo date & place' }],
      ['s', { state: 'empty', label: 'No source' }], ['m', { src: './assets/02_denali.jpg', caption: { place: 'Denali', date: 'June 2023' }, needs: 'photo date & place' }]] },
  { kind: 'weather', name: 'Weather', verdict: 'Keep · approved', why: 'Same approved hierarchy: temperature, condition, city with high/low, then forecast. Two changes: the “Weather” heading and the “Weather settings” button come off the widget, and icons drop the yellow so green stays the only colour.',
    cells: [['s', D.wxDay], ['m', D.wxDay], ['l', D.wxDay], ['s', { state: 'unavailable', updated: '7:10 AM', label: 'Unavailable' }], ['m', { ...D.wxNight, state: 'forecast_off', label: 'Forecast off' }]] },
  { kind: 'calendar', name: 'Calendar', verdict: 'Keep', why: 'Uses the event rows the feed already produces. “Now” and “In 33 min” are worked out from event start times. Showing whose event it is would need people attached to calendars, which the product doesn’t have, so this design leaves it out.',
    cells: [['s', D.calMorning], ['m', D.calMorning], ['l', D.calMorning], ['s', { state: 'setup', label: 'Setup' }], ['m', { ...D.calEvening, state: 'stale', label: 'Stale' }]] },
  { kind: 'timer', name: 'Timer', verdict: 'Keep', why: 'TimerStore holds one timer with no name, so the widget doesn’t pretend otherwise: no “Pasta” label, no second timer. When it rings, the card turns solid green, the only full-colour surface in the family, so it reads from across the kitchen.',
    cells: [['s', D.timerRun], ['m', D.timerRun], ['l', D.timerRun], ['s', { state: 'ringing' }], ['m', { state: 'idle', label: 'Idle' }]] },
  { kind: 'todo', name: 'To-do', verdict: 'Keep', why: 'A local list, and the big number tells you how much is left. Everyone in the household shares it because they share the Portal; syncing it with phones would be new capability and is not shown.',
    cells: [['s', D.todo], ['m', D.todo], ['l', D.todo], ['s', { tasks: [{ text: 'Bins out', done: true }, { text: 'Oat milk', done: true }] }], ['m', { tasks: [{ text: 'Bins out', done: true }] }]] },
  { kind: 'ha', name: 'Home controls', verdict: 'Keep · replaces Quick controls', why: 'Pinned Home Assistant entities with real on/off state. It looks like a switch because it is one. A scene runs once, so it looks like a button. If an entity is unavailable, its switch is disabled rather than guessed.',
    cells: [['s', D.ha], ['m', D.ha], ['l', D.ha], ['s', { entities: [{ name: 'Porch light', unknown: true }], label: 'Unavailable' }], ['m', { state: 'setup', label: 'Setup' }]] },
  { kind: 'clock', name: 'World clock', verdict: 'Keep, for families with someone far away', why: 'Home already shows the local time in large type, so this widget only shows other cities. Each city gives its offset as a plain sentence, with no UTC notation.',
    cells: [['s', D.clock], ['m', D.clock], ['l', D.clock], ['s', { state: 'empty', label: 'Empty' }], ['m', { cities: [D.clock.cities[3], D.clock.cities[1]] }]] },
  { kind: 'alarm', name: 'Alarm', verdict: 'Keep · Small only', why: 'There is only one alarm, so there is one size: the time, the repeat days and a real toggle. Medium and Large would add space with no new information.',
    cells: [['s', D.alarm], ['s', { ...D.alarm, on: false, label: 'Off' }], ['s', { state: 'empty', label: 'Empty' }]] },
];

function renderBoards() {
  document.getElementById('boards').innerHTML = BOARDS.map((b) => {
    const [s, m, l, s2, m2] = b.cells;
    const cells = b.kind === 'alarm'
      ? `${widget('alarm', 's', s[1])}${widget('alarm', 's', m[1], 'grid-column:1;grid-row:2')}${widget('alarm', 's', l[1], 'grid-column:2;grid-row:1')}`
      : `${widget(b.kind, 's', s[1])}${widget(b.kind, 'm', m[1])}${widget(b.kind, 'l', l[1])}${widget(b.kind, 's', s2[1])}${widget(b.kind, 'm', m2[1])}`;
    const legend = b.kind === 'alarm' ? '' : '<div class="board-legend"><span>Small 1×1 · lower: failure or empty</span><span>Medium 2×1 · lower: alternate state</span><span>Large 2×2</span></div>';
    return `<article class="spec"><div class="spec-head"><h3>${b.name}</h3><span class="verdict keep">${b.verdict}</span><p>${b.why}</p></div>
      <div class="board-wrap fit" data-w="2282"><div class="board">${cells}</div></div>${legend}</article>`;
  }).join('');
}

const CUTS = [
  ['Today', 'Repeats the greeting row, Weather and Calendar on a single strip. When three widgets say the same thing, none of them reads clearly.'],
  ['Quick controls', 'Tiles named Lights, Scenes and Climate look like switches, but each one only opens the Home Assistant app. Home controls does this honestly.'],
  ['News', 'Two-line headlines can’t be read from the sofa, and a stream of headlines in a family room is noise, not information.'],
  ['Stocks', 'One person’s portfolio, shown to the whole household. Stocks belongs on a phone.'],
  ['Crypto', 'Same as Stocks, and it also needs a sparkline to mean anything.'],
  ['Options', 'Delta bands and contract expiries are a trader’s tool. On a shared kitchen object they are the clearest case to cut.'],
  ['Android app widget', 'Stays in the library as an escape hatch for installed apps. We don’t control its look, so it isn’t part of this family.'],
];

function renderRules() {
  const rows = [['Hero 96', '96px', 400, '16°'], ['Hero small 80', '80px', 400, '6:45'], ['Title 34', '34px', 500, 'Farmers market'], ['Body 26', '26px', 400, 'Partly cloudy'], ['Meta 22 (floor)', '22px', 400, 'Portland · H 18° L 9°']];
  document.getElementById('type-scale').innerHTML = `<div style="font-size:15px;color:var(--muted);margin-bottom:8px">Type scale in sp, drawn here at half size. Nothing on Home is smaller than 22 sp.</div>` +
    rows.map(([n, s, w, t]) => `<div class="scale-row"><span class="spec-name">${n}</span><span style="font-size:calc(${s} / 2);font-weight:${w};letter-spacing:${parseInt(s) > 60 ? '-.03em' : '0'}">${t}</span></div>`).join('');
  document.getElementById('cuts').innerHTML = CUTS.map(([n, r]) => `<li><b>${n}</b><span>${r}</span></li>`).join('');
}

// ---------- fit + theme ----------
function fit() {
  const solo = document.body.classList.contains('solo');
  document.querySelectorAll('.fit').forEach((el) => {
    const base = Number(el.dataset.w);
    const width = solo ? window.innerWidth : el.parentElement.clientWidth;
    let z = width / base;
    if (solo) z = Math.min(z, window.innerHeight / 1280);
    el.style.zoom = z;
  });
}

function setTheme(theme) {
  document.documentElement.dataset.theme = theme;
  const btn = document.getElementById('theme');
  btn.textContent = theme === 'dark' ? 'Show light' : 'Show dark';
  btn.setAttribute('aria-label', `Switch to ${theme === 'dark' ? 'light' : 'dark'} theme`);
  renderHome(theme);
}

const params = new URLSearchParams(location.search);
if (params.get('show') === 'home') document.body.classList.add('solo');
renderRules();
renderBoards();
setTheme(params.get('theme') === 'light' ? 'light' : 'dark');
document.getElementById('theme').addEventListener('click', () =>
  setTheme(document.documentElement.dataset.theme === 'dark' ? 'light' : 'dark'));
window.addEventListener('resize', fit);
fit();
