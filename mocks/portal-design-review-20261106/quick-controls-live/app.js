// Quick controls live prototype. Simulated device state only; no network, no device.
// Tile rules mirror HomeActivity.kt QuickControlsWidget (~L2990-3020):
//   Jarvis launchable -> "Hey Jarvis"; dashboard saved -> "Home dashboard" (replaces HA tiles);
//   else HA app launchable -> Lights/Scenes/Climate/Home, all launch(haPackage); none -> message.
'use strict';

const svg = (body, fill = false) =>
  `<svg viewBox="0 0 48 48" fill="${fill ? 'currentColor' : 'none'}" stroke="${fill ? 'none' : 'currentColor'}" stroke-width="3" stroke-linecap="round" stroke-linejoin="round" aria-hidden="true">${body}</svg>`;
const I = {
  frame: svg('<rect x="13" y="9" width="27" height="22" rx="3"/><path d="M8 16v19a3 3 0 0 0 3 3h22"/><path d="M17 27l6-6 5 4 4-3 5 5"/>'),
  wifi: svg('<path d="M6 19a26 26 0 0 1 36 0M12 26a17 17 0 0 1 24 0M18 33a8 8 0 0 1 12 0"/><circle cx="24" cy="39" r="1.5" fill="currentColor"/>'),
  phone: svg('<rect x="15" y="6" width="18" height="36" rx="4"/><path d="M22 36h4"/>'),
  sleep: svg('<path d="M36 30A14 14 0 0 1 18 12a14 14 0 1 0 18 18z"/><path d="M28 9h7l-7 8h7"/>'),
  sun: svg('<circle cx="24" cy="24" r="7"/><path d="M24 6v4M24 38v4M6 24h4M38 24h4M11.3 11.3l2.8 2.8M33.9 33.9l2.8 2.8M11.3 36.7l2.8-2.8M33.9 14.1l2.8-2.8"/>'),
  music: svg('<path d="M19 35V11l18-4v24"/><circle cx="14" cy="35" r="5"/><circle cx="32" cy="31" r="5"/>'),
  search: svg('<circle cx="21" cy="21" r="12"/><path d="M30 30l10 10"/>'),
  gear: svg('<path d="M20.5 5h7l1.2 5.6 3.9 2.2 5.4-1.8 3.5 6-4.2 3.8v4.4l4.2 3.8-3.5 6-5.4-1.8-3.9 2.2L27.5 43h-7l-1.2-5.6-3.9-2.2-5.4 1.8-3.5-6 4.2-3.8v-4.4L6.5 17l3.5-6 5.4 1.8 3.9-2.2zM24 30a6 6 0 1 0 0-12 6 6 0 0 0 0 12z"/>', true),
  home: svg('<path d="M8 22L24 9l16 13v17a2 2 0 0 1-2 2H10a2 2 0 0 1-2-2z"/>'),
  cloud: svg('<path d="M15 38h20a9 9 0 0 0 1.2-17.9A12 12 0 0 0 13 22a8 8 0 0 0 2 16z"/>', true),
  climate: svg('<path d="M15 38h20a9 9 0 0 0 1.2-17.9A12 12 0 0 0 13 22a8 8 0 0 0 2 16z"/>'),
  apps: svg('<g fill="currentColor" stroke="none"><circle cx="12" cy="12" r="3"/><circle cx="24" cy="12" r="3"/><circle cx="36" cy="12" r="3"/><circle cx="12" cy="24" r="3"/><circle cx="24" cy="24" r="3"/><circle cx="36" cy="24" r="3"/><circle cx="12" cy="36" r="3"/><circle cx="24" cy="36" r="3"/><circle cx="36" cy="36" r="3"/></g>'),
  tools: svg('<path d="M29 9a9 9 0 0 0-8.6 11.7L9 32a4.2 4.2 0 0 0 6 6l11.3-11.4A9 9 0 0 0 38 18l-6 2-4-4 2-6z"/>'),
  bug: svg('<rect x="15" y="15" width="18" height="24" rx="9"/><path d="M19 15a5 5 0 0 1 10 0M24 21v18M8 22h7M33 22h7M8 32h7M33 32h7M10 12l6 4M38 12l-6 4"/>'),
  mic: svg('<rect x="18" y="7" width="12" height="22" rx="6"/><path d="M11 23a13 13 0 0 0 26 0M24 36v6"/>'),
  bulb: svg('<path d="M18 36h12M20 42h8M17 29c-3-2.5-5-6-5-10a12 12 0 0 1 24 0c0 4-2 7.5-5 10z"/>'),
  film: svg('<rect x="7" y="10" width="34" height="28" rx="4"/><path d="M7 18h34M17 10v8M31 10v8"/>'),
  qc: svg('<rect x="8" y="8" width="13" height="13" rx="3"/><rect x="27" y="8" width="13" height="13" rx="3"/><rect x="8" y="27" width="13" height="13" rx="3"/><rect x="27" y="27" width="13" height="13" rx="3"/>'),
  out: svg('<path d="M28 8h12v12M40 8L22 26M36 28v10a2 2 0 0 1-2 2H10a2 2 0 0 1-2-2V14a2 2 0 0 1 2-2h10"/>'),
  left: svg('<path d="M30 10L16 24l14 14"/>'),
  right: svg('<path d="M18 10l14 14-14 14"/>'),
  plug: svg('<path d="M18 6v10M30 6v10M13 16h22v8a11 11 0 0 1-22 0zM24 35v8"/>'),
  lock: svg('<rect x="11" y="21" width="26" height="19" rx="3"/><path d="M17 21v-5a7 7 0 0 1 14 0v5"/>'),
  call: svg('<path d="M15 7l5 9-3.5 3.5a22 22 0 0 0 12 12L32 28l9 5-2 6c-14 2-32-16-30-30z"/>', true),
  globe: svg('<circle cx="24" cy="24" r="16"/><path d="M8 24h32M24 8c-8 9-8 23 0 32M24 8c8 9 8 23 0 32"/>'),
  folder: svg('<path d="M6 13a3 3 0 0 1 3-3h10l4 5h16a3 3 0 0 1 3 3v18a3 3 0 0 1-3 3H9a3 3 0 0 1-3-3z"/>'),
  radio: svg('<rect x="6" y="15" width="36" height="25" rx="4"/><path d="M14 15L33 7"/><circle cx="31" cy="28" r="5"/><path d="M12 24h8M12 31h8"/>'),
};
const ic = (name, cls = '') => `<span class="ic ${cls}">${I[name]}</span>`;
const esc = (v) => String(v).replace(/[&<>"']/g, (c) => ({ '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;', "'": '&#39;' }[c]));
const EXAMPLE_URL = 'http://homeassistant.local:8123/lovelace/home';
const FAVORITES = [
  ['Calls', 'call', 'linear-gradient(#4cd964,#28b64a)'], ['Browser', 'globe', 'linear-gradient(#4b93ff,#2363e6)'],
  ['Jarvis', 'mic', 'linear-gradient(#ffb340,#ff8a00)'], ['Material Files', 'folder', 'linear-gradient(#3d8bff,#1765e0)'],
  ['Portal Settings', 'gear', 'linear-gradient(#3fd6d0,#13aeb0)'], ['PortalHub', 'radio', 'linear-gradient(#ffa040,#ff7a1a)'],
];

// ---------- state ----------
const params = new URLSearchParams(location.search);
const flag = (k, d) => (params.has(k) ? params.get(k) === '1' : d);
const initial = () => ({
  jarvis: flag('jarvis', true), haApp: flag('haApp', true), haApi: flag('haApi', false),
  url: params.get('url') === '1' ? EXAMPLE_URL : '',
  mode: params.get('mode') === 'before' ? 'before' : 'after',
  tiles: params.get('tiles') === 'rec' ? 'rec' : 'current',
  customize: params.get('customize') === '1',
  entities: { lamp: true, porch: false },
  stack: [{ s: 'home' }],
  editor: params.get('editor') === '1' ? { value: '', error: '' } : null,
  toast: '', pulse: false,
});
let S = initial();
if (params.get('screen')) S.stack.push({ s: params.get('screen'), from: params.get('from') || '' });
if (params.get('solo') === '1') document.body.classList.add('solo');

const current = () => S.stack[S.stack.length - 1];
const set = (patch) => { S = { ...S, ...patch }; render(); };
const go = (s, from = '') => set({ stack: [...S.stack, { s, from }], editor: null, toast: '' });
const back = () => set({ stack: S.stack.length > 1 ? S.stack.slice(0, -1) : S.stack, toast: '' });
const home = () => set({ stack: [{ s: 'home' }], toast: '' });
let toastTimer;
const toast = (msg) => { clearTimeout(toastTimer); set({ toast: msg }); toastTimer = setTimeout(() => set({ toast: '' }), 3600); };
// Any change to inputs re-derives tiles; flag a pulse so the Home widget visibly reacts next time it shows.
const changeDevice = (patch) => set({ ...patch, pulse: true });

// ---------- derived ----------
function tiles() {
  const out = [], rec = S.tiles === 'rec';
  if (S.jarvis) out.push({ k: 'jarvis', icon: 'mic', label: 'Hey Jarvis', d: 'Open your assistant' });
  if (S.url) out.push({ k: 'dash', icon: 'home', label: 'Home dashboard', d: 'Open your dashboard', ext: rec });
  else if (S.haApp && rec) out.push({ k: 'ha', icon: 'home', label: 'Home Assistant', d: 'Opens the app', ext: true });
  else if (S.haApp) [['bulb', 'Lights'], ['film', 'Scenes'], ['climate', 'Climate'], ['home', 'Home']]
    .forEach(([icon, label]) => out.push({ k: 'ha', icon, label, d: 'Open Home Assistant' }));
  return out;
}
const list = (a) => (a.length < 2 ? a.join('') : `${a.slice(0, -1).join(', ')} and ${a[a.length - 1]}`);
function effectText() {
  const t = tiles();
  if (!t.length) return 'No tiles. The widget shows a setup message.';
  return `${list(t.map((x) => x.label))}.`;
}

// ---------- Home ----------
function header() {
  return `<div class="abs hdr"><span class="wordmark">immortal</span><span class="vsep"></span><span class="room">Family Area</span>
    <span class="rbtn">${ic('frame')}</span>
    <div class="hright"><span class="conn">${ic('wifi')}Connected</span>
      <button type="button" class="customize ${S.customize ? 'on' : ''}" data-act="customize">${S.customize ? 'Done' : 'Customize'}</button>
      ${['phone', 'sleep', 'sun', 'music', 'search', 'gear'].map((n) => `<span class="rbtn">${ic(n)}</span>`).join('')}</div></div>
  <div class="abs greet"><h2>Good morning.</h2><p>Everything within reach.</p></div>
  <div class="abs clock"><span class="t">8:04</span><span class="d">Friday<br>November 6</span></div>`;
}

function qcCard(preview = false) {
  const t = tiles(), after = S.mode === 'after';
  const open = preview ? '' : `<button type="button" class="open ${after ? 'lbl-settings' : ''}" data-act="qc-heading">${after ? 'Settings' : 'Open'}</button>`;
  const body = t.length
    ? `<div class="qc-tiles">${t.map((x) => `<button type="button" class="qc-tile ${x.ext ? 'ext-wrap' : ''}" data-act="tile" data-k="${x.k}" data-label="${x.label}" ${preview ? 'tabindex="-1"' : ''}>
        ${ic(x.icon)}${x.ext ? ic('out', 'out') : ''}<b>${x.label}</b><span class="d">${x.d}</span></button>`).join('')}</div>`
    : `<div class="qc-empty">${after ? 'Add your home dashboard, or install Jarvis or Home Assistant.' : 'Add an assistant or Home Assistant app to use quick controls.'}
        ${after && !preview ? '<br><button type="button" class="tbtn" data-act="qc-heading">Set up</button>' : ''}</div>`;
  const edit = S.customize && !preview ? '<button type="button" class="edit-settings" data-act="qc-customize-settings">Settings</button>' : '';
  return `<div class="card qc ${S.pulse && !preview ? 'pulse' : ''}" aria-label="Quick controls widget">
    <div class="head">${ic('qc')}<span class="ttl">Quick controls</span>${open}</div>${body}${edit}</div>`;
}

function haCard() {
  const head = (open) => `<div class="head">${ic('home')}<span class="ttl">Home Assistant</span>${open}</div>`;
  if (S.url) {
    return `<div class="card">${head('<button type="button" class="open" data-act="open-dash">Open</button>')}
      <div class="body"><div class="h26">Your home dashboard</div><div class="s22">Open your rooms, devices and scenes.</div>
      <button type="button" class="tbtn" data-act="open-dash">Open dashboard</button></div></div>`;
  }
  if (S.haApi) {
    const e = S.entities;
    const ent = (k, icon, label) => `<button type="button" class="ent ${e[k] ? 'on' : ''}" data-act="ent" data-k="${k}">${ic(icon)}<b>${label}</b><em>${e[k] ? 'On' : 'Off'}</em></button>`;
    return `<div class="card">${head('')}<div class="ha-ent">${ent('lamp', 'bulb', 'Living room lamp')}${ent('porch', 'bulb', 'Porch light')}
      <button type="button" class="ent" data-act="scene">${ic('film')}<b>Movie night</b><em>Tap to run</em></button></div></div>`;
  }
  return `<div class="card">${head('')}<div class="body"><div class="h26">Connect Home Assistant</div>
    <div class="s22">Add your server to control devices here.</div>
    <button type="button" class="tbtn" data-act="go" data-to="st:ha">Connect</button></div></div>`;
}

function homeScreen() {
  const photo = `<div class="card photo"><img src="./assets/02_denali.jpg" alt=""><div class="scrim"></div>
    <div class="cap"><h3>Photo memory</h3><p>Your selected photo source</p></div></div>`;
  const wx = `<div class="card"><div class="head">${ic('cloud')}<span class="ttl">Weather</span><span class="open">Open</span></div>
    <div class="body"><div class="wx-row"><span class="wx-t">51°</span><span class="ic wx-ic">${I.cloud}</span></div>
    <div class="h26" style="margin-top:18px">Edmonds</div><div class="s22">H: 55° · L: 51°</div></div></div>`;
  const chips = FAVORITES.map(([label, icon, bg]) => `<div class="fav"><span class="app" style="background:${bg}">${ic(icon)}</span><span>${label}</span></div>`).join('');
  return `<div class="${S.customize ? 'customizing' : ''}">${header()}
    <div class="abs grid qc-layout">${photo}${qcCard()}${haCard()}${wx}</div>
    <div class="abs favhead"><span class="lbl">Favorite apps</span><span class="pg">1 of 2</span><span class="all">All apps</span></div>
    <div class="abs favs">${chips}</div>
    <div class="abs dock"><span class="on">${ic('home')}Home</span><span>${ic('apps')}Apps</span><span>${ic('tools')}Tools</span></div>
    <div class="abs bug">${ic('bug')}</div></div>`;
}

// ---------- Settings ----------
const CATS = [['display', 'Display', 'sun'], ['home', 'Home & widgets', 'apps'], ['connections', 'Connections', 'plug']];
const PAGE_CAT = { display: 'display', 'quick-buttons': 'home', 'home-widgets': 'home', 'quick-controls': 'home', ha: 'connections', connections: 'connections' };
const CAT_PAGE = { display: 'display', home: 'home-widgets', connections: 'connections' };

const row = (title, sub, { act = '', to = '', value = '', valueCls = '', chev = true, knob = null } = {}) => {
  const inner = `<span class="t"><b>${title}</b>${sub ? `<span>${sub}</span>` : ''}</span>${value ? `<span class="v ${valueCls}">${value}</span>` : ''}${knob !== null ? `<span class="knob ${knob ? 'on' : ''}"></span>` : ''}${chev ? ic('right', 'chev') : ''}`;
  return act || to ? `<button type="button" class="row" data-act="${act || 'go'}" data-to="${to}">${inner}</button>` : `<div class="row static">${inner}</div>`;
};

function pageContent(page, from) {
  const before = S.mode === 'before';
  switch (page) {
    case 'display': return {
      title: 'Display', sub: 'Appearance, text and clock for this Portal.',
      body: `${from === 'qc' ? `<div class="banner"><span>⚠︎</span><span><b>This is today’s behavior.</b> You tapped Open on Quick controls and landed on Display. Nothing on this page changes which tiles Quick controls shows.</span></div>` : ''}
        <div class="rows">${row('Appearance', '', { value: 'Dark', chev: false })}${row('Text size', '', { value: 'Standard', chev: false })}${row('Clock', '', { value: '12-hour', chev: false })}${row('Greeting', 'Show Good morning on Home.', { chev: false, knob: true })}</div>`,
    };
    case 'quick-buttons': return {
      title: 'Quick buttons', sub: 'Apps pinned to the app switcher.',
      body: `${from === 'customize' ? `<div class="banner"><span>⚠︎</span><span><b>This is today’s behavior.</b> Customize, then Settings on Quick controls, opens Quick buttons. These pin apps to the app switcher; they don’t change the Quick controls tiles.</span></div>` : ''}
        <div class="rows">${['Calls', 'Browser', 'Jarvis', 'PortalHub'].map((a, i) => row(a, '', { chev: false, knob: i < 3 })).join('')}</div>`,
    };
    case 'home-widgets': return {
      title: 'Home & widgets', sub: 'What appears on Home.',
      body: `<div class="rows">${before ? '' : row('Quick controls', 'Which shortcuts the widget shows.', { to: 'st:quick-controls' })}
        ${row('Quick buttons', 'Apps pinned to the app switcher.', { to: 'st:quick-buttons' })}${row('Widget pages', '', { value: '2', chev: false })}</div>`,
    };
    case 'connections': return {
      title: 'Connections', sub: 'Your dashboard and home server.',
      body: `<div class="rows">${row('Home Assistant dashboard', 'Dashboard address and browser sign-in.', { to: 'st:ha', value: S.url ? 'Saved' : 'Not set up' })}</div>`,
    };
    case 'ha': return {
      title: 'Home Assistant dashboard', sub: 'Your dashboard opens in the Portal browser, which keeps your sign-in.',
      body: `<div class="rows">${row('Dashboard address', 'An http or https address. Leave blank to turn off.', { act: 'edit', value: S.url ? esc(S.url) : 'Not set up' })}
          ${S.url ? row('Open dashboard', 'Open it now in the Portal browser.', { act: 'open-dash' }) : ''}</div>
        <h3 class="sec">Home controls (the separate Home Assistant widget)</h3>
        <div class="rows">${row('Server connection', 'Lets the Home Assistant widget switch lights and run scenes directly.', { value: S.haApi ? 'Connected' : 'Not connected', valueCls: S.haApi ? 'in' : '', chev: false })}</div>`,
    };
    case 'quick-controls': return qcSetupPage();
    default: return { title: page, sub: '', body: '' };
  }
}

function qcSetupPage() {
  const t = tiles();
  const sub = t.length ? `Home shows ${list(t.map((x) => `<b>${x.label}</b>`))}.` : 'Nothing to show on Home yet. Save a dashboard address, or install Jarvis or Home Assistant.';
  let haVal, haSub;
  if (!S.haApp) { haVal = 'Not installed'; haSub = S.url ? 'Not needed while a dashboard is saved.' : 'Install it, or save a dashboard address instead.'; }
  else if (S.url) { haVal = 'Not used'; haSub = 'Your dashboard tile takes its place.'; }
  else { haVal = 'In use'; haSub = S.tiles === 'rec' ? 'Adds one tile that opens the app.' : 'Adds Lights, Scenes, Climate and Home. All four open the same app.'; }
  return {
    title: 'Quick controls', sub,
    body: `<div class="qc-setup"><figure>${qcCard(true)}<figcaption>Live preview of the widget on Home.</figcaption></figure>
      <div><h3 class="sec">Dashboard</h3><div class="rows">${row('Home dashboard', S.url ? 'Saved. Replaces the Home Assistant app tiles.' : 'Open your dashboard in the browser. No app needed.', { to: 'st:ha', value: S.url ? 'In use' : 'Not set up', valueCls: S.url ? 'in' : '' })}</div>
      <h3 class="sec">Apps on this Portal</h3><div class="rows">
        ${row('Jarvis', 'Adds Hey Jarvis.', { value: S.jarvis ? 'In use' : 'Not installed', valueCls: S.jarvis ? 'in' : '', chev: false })}
        ${row('Home Assistant app', haSub, { value: haVal, valueCls: haVal === 'In use' ? 'in' : '', chev: false })}
        ${row('Manage apps', 'Opens the Apps tab to install or remove apps.', { act: 'apps' })}</div>
      <p class="st-sub" style="font-size:20px;margin-top:0">Every Quick controls widget on Home shares this.</p></div></div>`,
  };
}

function settingsScreen(page, from) {
  const { title, sub, body } = pageContent(page, from);
  const prev = S.stack[S.stack.length - 2];
  const canBack = prev && prev.s.startsWith('st:');
  return `<div class="st"><div class="st-top"><button type="button" data-act="home">${ic('left')}Home</button></div>
    <div class="st-panes"><aside class="st-side"><h1>Settings</h1><nav>${CATS.map(([k, label, icon]) =>
      `<button type="button" data-act="cat" data-to="st:${CAT_PAGE[k]}" aria-current="${PAGE_CAT[page] === k}"><span class="cat">${ic(icon)}</span>${label}</button>`).join('')}</nav></aside>
      <section class="st-detail"><div class="st-head"><div class="hist"><button type="button" data-act="back" ${canBack ? '' : 'disabled'} aria-label="Back">${ic('left')}</button><button type="button" disabled aria-label="Forward">${ic('right')}</button></div>
        <h2>${title}</h2></div><p class="st-sub">${sub}</p>${body}</section></div></div>`;
}

// ---------- Landings (what a tile really does) ----------
function landing(kind, label) {
  const shell = (title, inner) => `<div class="land"><div class="land-top"><button type="button" data-act="back">${ic('left')}Back</button>
    <span style="font-size:24px;color:var(--secondary)">${title}</span><span class="simtag">Simulated result</span></div><div class="land-body">${inner}</div></div>`;
  if (kind === 'jarvis') return shell('Jarvis', `<div class="orb">${ic('mic')}</div><h2>Jarvis is listening</h2>
    <p>On the Portal, <b>Hey Jarvis</b> opens the Jarvis app and asks it to start listening, as if you had said “Hey Jarvis”.</p>
    <p>Jarvis does the work. The tile only starts it.</p>`);
  if (kind === 'ha') {
    const four = S.tiles === 'current';
    return shell('Home Assistant app', `<div class="appicon">${ic('home')}</div><h2>Home Assistant app opens</h2>
      <p>You tapped <b>${esc(label)}</b>. The Home Assistant app opens to its own start screen${four ? ', exactly as it would from Scenes, Climate or Home' : ''}.</p>
      <p><b>Nothing in your home changed.</b> ${four ? 'The Lights tile doesn’t switch lights or open a lights page.' : 'The tile is a door to the app.'}</p>`);
  }
  if (kind === 'dash') return shell('Portal browser', `<div class="browser"><div class="url">${ic('lock')}${esc(S.url || EXAMPLE_URL)}</div>
      <div class="page"><div>Living room</div><div>Kitchen</div><div>Thermostat</div><div>Scenes</div></div></div>
    <p style="margin-top:30px">Your dashboard opens in the <b>Portal browser</b>, which keeps your existing sign-in. Controls live on the dashboard page itself.</p>
    <p>A saved address doesn’t prove the page loads. If it can’t open, the Portal says “Your dashboard couldn’t be opened.”</p>`);
  if (kind === 'apps') {
    const card = (k, name, icon, bg) => `<div class="app-card"><span class="app" style="background:${bg}">${ic(icon)}</span><b>${name}</b>
      <button type="button" class="pillbtn ${S[k] ? '' : 'inst'}" data-act="install" data-k="${k}">${S[k] ? 'Uninstall' : 'Install'}</button></div>`;
    return shell('Apps', `<h2 style="margin-top:0">Apps</h2><p>Manage apps leaves Settings and opens the Apps tab. Installing here changes the Quick controls tiles when you return.</p>
      <div class="apps-grid">${card('jarvis', 'Jarvis', 'mic', 'linear-gradient(#ffb340,#ff8a00)')}${card('haApp', 'Home Assistant', 'home', 'linear-gradient(#41bdf5,#1a8ad1)')}</div>`);
  }
  return shell('', '');
}

// ---------- Editor ----------
function editorModal() {
  const e = S.editor;
  return `<div class="scrim-modal"><form class="editor" id="editor-form" novalidate>
    <h3>Dashboard address</h3><p>An http or https dashboard address. Leave blank to turn the dashboard off.</p>
    <input id="addr" type="text" inputmode="url" autocomplete="off" spellcheck="false" value="${esc(e.value)}" placeholder="http://homeassistant.local:8123/…">
    <button type="button" class="fill" data-act="fill">Use example address</button>
    <p class="err" role="alert">${esc(e.error)}</p>
    <div class="acts"><button type="button" data-act="cancel-edit">Cancel</button><button type="submit" class="primary">Save</button></div></form></div>`;
}
function validUrl(raw) {
  const text = raw.trim();
  if (!text) return '';
  try {
    const u = new URL(text);
    if (!/^https?:$/.test(u.protocol) || !u.hostname || u.username || u.password || /\s/.test(text)) return null;
    return text;
  } catch { return null; }
}

// ---------- render ----------
function render() {
  const t = current();
  let html;
  if (t.s === 'home') html = homeScreen();
  else if (t.s.startsWith('st:')) html = settingsScreen(t.s.slice(3), t.from);
  else { const [, kind, label = ''] = t.s.split(':'); html = landing(kind, label); }
  if (S.editor) html += editorModal();
  if (S.toast) html += `<div class="toast" role="status">${S.toast}</div>`;
  const dev = document.getElementById('device');
  dev.innerHTML = html;
  if (t.s === 'home' && S.pulse) S = { ...S, pulse: false };
  if (S.editor) { const a = document.getElementById('addr'); a.focus(); a.setSelectionRange(a.value.length, a.value.length); }
  syncPanel();
  fit();
}

let lastEffect = '';
function syncPanel() {
  document.querySelectorAll('[data-sim]').forEach((el) => { el.checked = S[el.dataset.sim]; });
  document.getElementById('sim-url').textContent = S.url ? 'Saved' : 'Not set up';
  document.querySelectorAll('[data-mode]').forEach((b) => b.setAttribute('aria-pressed', String(b.dataset.mode === S.mode)));
  document.querySelectorAll('[data-tiles]').forEach((b) => b.setAttribute('aria-pressed', String(b.dataset.tiles === S.tiles)));
  document.getElementById('mode-hint').textContent = S.mode === 'before'
    ? 'Today: the card’s Open goes to Display, and Customize → Settings goes to Quick buttons (the app switcher). Neither changes these tiles.'
    : 'Proposed: the card’s Settings and Customize → Settings both open one Quick controls page.';
  const eff = document.getElementById('effect');
  const text = effectText();
  eff.textContent = text;
  if (lastEffect && text !== lastEffect) { eff.classList.remove('flash'); void eff.offsetWidth; eff.classList.add('flash'); }
  lastEffect = text;
}

function fit() {
  const frame = document.getElementById('frame'), dev = document.getElementById('device');
  const solo = document.body.classList.contains('solo');
  const w = solo ? window.innerWidth : document.getElementById('frame-wrap').clientWidth;
  const s = solo ? Math.min(w / 1920, window.innerHeight / 1280) : Math.min(w / 1920, Math.max(0.3, (window.innerHeight - 120) / 1280));
  dev.style.transform = `scale(${s})`;
  frame.style.width = `${1920 * s}px`; frame.style.height = `${1280 * s}px`;
}

// ---------- events ----------
document.getElementById('device').addEventListener('click', (ev) => {
  const b = ev.target.closest('[data-act]'); if (!b) return;
  const a = b.dataset.act, after = S.mode === 'after';
  if (a === 'customize') set({ customize: !S.customize });
  else if (a === 'qc-heading') go(after ? 'st:quick-controls' : 'st:display', after ? '' : 'qc');
  else if (a === 'qc-customize-settings') { S = { ...S, customize: false }; go(after ? 'st:quick-controls' : 'st:quick-buttons', after ? '' : 'customize'); }
  else if (a === 'tile') { if (S.customize) return; go(b.dataset.k === 'jarvis' ? 'land:jarvis' : b.dataset.k === 'dash' ? 'land:dash' : `land:ha:${b.dataset.label}`); }
  else if (a === 'open-dash') go('land:dash');
  else if (a === 'go' || a === 'cat') go(b.dataset.to);
  else if (a === 'back') back();
  else if (a === 'home') home();
  else if (a === 'apps') go('land:apps');
  else if (a === 'install') { const k = b.dataset.k; changeDevice({ [k]: !S[k] }); toast(`<b>Simulated.</b> ${k === 'jarvis' ? 'Jarvis' : 'Home Assistant'} ${S[k] ? 'installed' : 'removed'}. Go back to see the tiles change.`); }
  else if (a === 'edit') set({ editor: { value: S.url, error: '' } });
  else if (a === 'fill') set({ editor: { value: EXAMPLE_URL, error: '' } });
  else if (a === 'cancel-edit') set({ editor: null });
  else if (a === 'ent') { const k = b.dataset.k; set({ entities: { ...S.entities, [k]: !S.entities[k] } }); toast('<b>Simulated.</b> The Home Assistant widget sends this to your server. This one really switches the light.'); }
  else if (a === 'scene') toast('<b>Simulated.</b> Movie night runs on your Home Assistant server.');
});
document.getElementById('device').addEventListener('submit', (ev) => {
  ev.preventDefault();
  const raw = document.getElementById('addr').value;
  const v = validUrl(raw);
  if (v === null) { set({ editor: { value: raw, error: 'Enter a full http or https address, like http://homeassistant.local:8123.' } }); return; }
  changeDevice({ url: v, editor: null });
  toast(v ? '<b>Saved.</b> The dashboard tile now replaces the Home Assistant app tiles.' : '<b>Dashboard turned off.</b> Home Assistant app tiles return if the app is installed.');
});
document.getElementById('device').addEventListener('input', (ev) => {
  if (ev.target.id === 'addr') S = { ...S, editor: { ...S.editor, value: ev.target.value } };
});
document.addEventListener('keydown', (ev) => { if (ev.key === 'Escape' && S.editor) set({ editor: null }); });
document.querySelectorAll('[data-sim]').forEach((el) => el.addEventListener('change', () => changeDevice({ [el.dataset.sim]: el.checked })));
document.querySelectorAll('[data-mode]').forEach((b) => b.addEventListener('click', () => set({ mode: b.dataset.mode, stack: [{ s: 'home' }], customize: false })));
document.querySelectorAll('[data-tiles]').forEach((b) => b.addEventListener('click', () => changeDevice({ tiles: b.dataset.tiles })));
document.getElementById('reset').addEventListener('click', () => { S = { ...initial(), stack: [{ s: 'home' }], editor: null, customize: false }; render(); });
window.addEventListener('resize', fit);
render();
