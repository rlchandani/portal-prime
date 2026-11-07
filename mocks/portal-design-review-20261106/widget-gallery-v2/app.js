// Home v2: before/after review. Sample data mirrors the 2026-11-06 capture; fallback photo only.
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
  cal: svg('<rect x="7" y="10" width="34" height="31" rx="4"/><path d="M7 19h34M16 6v8M32 6v8"/>'),
  cloud: svg('<path d="M15 38h20a9 9 0 0 0 1.2-17.9A12 12 0 0 0 13 22a8 8 0 0 0 2 16z"/>', true),
  apps: svg('<g fill="currentColor" stroke="none"><circle cx="12" cy="12" r="3"/><circle cx="24" cy="12" r="3"/><circle cx="36" cy="12" r="3"/><circle cx="12" cy="24" r="3"/><circle cx="24" cy="24" r="3"/><circle cx="36" cy="24" r="3"/><circle cx="12" cy="36" r="3"/><circle cx="24" cy="36" r="3"/><circle cx="36" cy="36" r="3"/></g>'),
  tools: svg('<path d="M29 9a9 9 0 0 0-8.6 11.7L9 32a4.2 4.2 0 0 0 6 6l11.3-11.4A9 9 0 0 0 38 18l-6 2-4-4 2-6z"/>'),
  bug: svg('<rect x="15" y="15" width="18" height="24" rx="9"/><path d="M19 15a5 5 0 0 1 10 0M24 21v18M8 22h7M33 22h7M8 32h7M33 32h7M10 12l6 4M38 12l-6 4"/>'),
  call: svg('<path d="M15 7l5 9-3.5 3.5a22 22 0 0 0 12 12L32 28l9 5-2 6c-14 2-32-16-30-30z"/>', true),
  globe: svg('<circle cx="24" cy="24" r="16"/><path d="M8 24h32M24 8c-8 9-8 23 0 32M24 8c8 9 8 23 0 32"/>'),
  mic: svg('<rect x="18" y="7" width="12" height="22" rx="6"/><path d="M11 23a13 13 0 0 0 26 0M24 36v6"/>'),
  folder: svg('<path d="M6 13a3 3 0 0 1 3-3h10l4 5h16a3 3 0 0 1 3 3v18a3 3 0 0 1-3 3H9a3 3 0 0 1-3-3z"/>'),
  radio: svg('<rect x="6" y="15" width="36" height="25" rx="4"/><path d="M14 15L33 7"/><circle cx="31" cy="28" r="5"/><path d="M12 24h8M12 31h8"/>'),
};
const ic = (name, extra = '') => `<span class="ic" ${extra}>${I[name]}</span>`;

const FAVORITES = [
  ['Calls', 'call', 'linear-gradient(#4cd964,#28b64a)'], ['Browser', 'globe', 'linear-gradient(#4b93ff,#2363e6)'],
  ['Jarvis', 'mic', 'linear-gradient(#ffb340,#ff8a00)'], ['Material Files', 'folder', 'linear-gradient(#3d8bff,#1765e0)'],
  ['Portal Settings', 'gear', 'linear-gradient(#3fd6d0,#13aeb0)'], ['PortalHub', 'radio', 'linear-gradient(#ffa040,#ff7a1a)'],
];

// Change register. `kind` drives colour; elements carry data-c (After) / data-cb (Before, removed only).
const CHANGES = [
  { n: 1, kind: 'removed', title: 'Today: duplicate calendar prompt', why: '“Add your calendar” + button appeared twice on one page. The Calendar card keeps it.' },
  { n: 2, kind: 'removed', title: 'Home Assistant header “Open”', why: 'It does exactly what “Open dashboard” does, two lines below.' },
  { n: 3, kind: 'changed', title: 'Today: sky facts become the content', why: 'Same four facts (feels like, moon, sunrise, sunset), now readable as a grid instead of a run-on line.' },
  { n: 4, kind: 'changed', title: 'Header “Open” → “Settings”', why: 'On Today, Calendar and Weather it opens the widget’s settings page, so the label now says so. Quieter grey.' },
  { n: 5, kind: 'changed', title: 'Card actions aligned and shaped', why: '“Open dashboard” and “Add calendar” sat 20 dp inside the text column. Now flush, on a soft pill.' },
  { n: 6, kind: 'changed', title: 'Photo caption scrim', why: 'A neutral, taller scrim keeps the caption legible on bright photos without the teal cast.' },
  { n: 7, kind: 'changed', title: 'Favorites strip, smaller', why: 'Tiles 88 → 72 dp, icons 56 → 46 dp, heading 26 → 24 sp. All six apps and All apps kept.' },
  { n: 8, kind: 'changed', title: '“1 of 2” moved under the widgets', why: 'It counts widget pages, but sat in the Favorites row and read as favorites paging.' },
  { n: 9, kind: 'added', title: 'Page dots', why: 'Next to the moved counter, so swiping between widget pages has a visible cue.' },
];
const KIND = Object.fromEntries(CHANGES.map((c) => [c.n, c.kind]));

function header() {
  return `<div class="abs hdr"><span class="wordmark">immortal</span><span class="vsep"></span><span class="room">Family Area</span>
    <span class="rbtn">${ic('frame')}</span>
    <div class="hright"><span class="conn">${ic('wifi')}Connected</span><span class="customize">Customize</span>
      ${['phone', 'sleep', 'sun', 'music', 'search', 'gear'].map((n) => `<span class="rbtn">${ic(n)}</span>`).join('')}</div></div>
  <div class="abs greet"><h2>Good morning.</h2><p>Everything within reach.</p></div>
  <div class="abs clock"><span class="t">8:04</span><span class="d">Friday<br>November 6</span></div>`;
}

function cardHead(icon, title, open, after, tag = '') {
  return `<div class="head">${ic(icon)}<span class="ttl">${title}</span>${open ? `<span class="open" ${tag}>${open}</span>` : ''}</div>`;
}

function grid(after) {
  const a = (n) => (after ? `data-c="${n}"` : '');
  const photo = `<div class="card photo" ${a(6)}><img src="./assets/02_denali.jpg" alt=""><div class="scrim"></div>
    <div class="cap"><h3>Photo memory</h3><p>Your selected photo source</p></div></div>`;
  const ha = `<div class="card">${after
    ? `<div class="head" data-cpin="2">${ic('home')}<span class="ttl">Home Assistant</span></div>`
    : cardHead('home', 'Home Assistant', 'Open', false, 'data-cb="2"')}
    <div class="body"><div class="h26">Your home dashboard</div><div class="s22">Open your rooms, devices and scenes.</div>
    <span class="tbtn" ${a(5)}>Open dashboard</span></div></div>`;
  const today = after
    ? `<div class="card" data-cpin="1" data-pinpos="br">${cardHead('cal', 'Today', 'Settings', true, 'data-c="4"')}
      <div class="body"><div class="facts" data-c="3">
        <div><small>Feels like</small><b>51°</b></div><div><small>Moon</small><b>Waning crescent</b></div>
        <div><small>Sunrise</small><b>7:01 AM</b></div><div><small>Sunset</small><b>4:43 PM</b></div></div></div></div>`
    : `<div class="card">${cardHead('cal', 'Today', 'Open', false)}
      <div class="body"><div data-cb="1"><div class="h26">Add your calendar</div><div class="s22">See your upcoming events here.</div>
      <span class="tbtn">Add calendar</span></div>
      <div class="sky">Feels like 51° · Sunrise 7:01 AM\nWaning crescent · Sunset 4:43 PM</div></div></div>`;
  const cal = `<div class="card">${cardHead('cal', 'Calendar', after ? 'Settings' : 'Open', after, a(4))}
    <div class="body"><div class="h26">Add your calendar</div><div class="s22" style="margin-top:14px">See your upcoming events here.</div>
    <span class="tbtn" ${a(5)} style="${after ? '' : 'margin-top:18px'}">Add calendar</span></div></div>`;
  const wx = `<div class="card">${cardHead('cloud', 'Weather', after ? 'Settings' : 'Open', after, a(4))}
    <div class="body"><div class="wx-row"><span class="wx-t">51°</span><span class="ic wx-ic">${I.cloud}</span></div>
    <div class="h26" style="margin-top:18px">Edmonds</div><div class="s22">H: 55° · L: 51°</div></div></div>`;
  return `<div class="abs grid">${photo}${ha}${today}${cal}${wx}</div>`;
}

function favorites(after) {
  const pager = after ? '' : '<span class="pg" data-cb="8">1 of 2</span>';
  const chips = FAVORITES.map(([label, icon, bg]) =>
    `<div class="fav"><span class="app" style="background:${bg}">${ic(icon)}</span><span>${label}</span></div>`).join('');
  const moved = after ? `<div class="abs pager"><span class="dot on" data-c="9" data-pinpos="left"></span><span class="dot" data-c="9"></span><span data-c="8" data-pinpos="right">1 of 2</span></div>` : '';
  return `${moved}<div class="abs favhead" ${after ? 'data-c="7"' : ''}><span class="lbl">Favorite apps</span>${pager}<span class="all">All apps</span></div>
    <div class="abs favs" ${after ? 'data-c="7"' : ''}>${chips}</div>`;
}

function chrome() {
  return `<div class="abs dock"><span class="on">${ic('home')}Home</span><span>${ic('apps')}Apps</span><span>${ic('tools')}Tools</span></div>
    <div class="abs bug">${ic('bug')}</div>`;
}

function device(variant) {
  const after = variant === 'after';
  return `<div class="device ${variant}" role="img" aria-label="${after ? 'Proposed' : 'Current'} Portal Home, sample data">
    ${header()}${grid(after)}${favorites(after)}${chrome()}<div class="ov"></div></div>`;
}

// ---------- overlay: measured from the DOM, so boxes always match the render ----------
function rectIn(dev, el) {
  const d = dev.getBoundingClientRect(); const r = el.getBoundingClientRect(); const s = d.width / 1920;
  return { x: (r.left - d.left) / s, y: (r.top - d.top) / s, w: r.width / s, h: r.height / s };
}
function drawOverlay(dev) {
  const ov = dev.querySelector('.ov');
  const parts = []; const pinned = new Set();
  const add = (el, n, attr) => {
    const k = KIND[n]; const r = rectIn(dev, el); const pad = 8;
    if (attr !== 'cpin') parts.push(`<div class="box ${k}" style="left:${r.x - pad}px;top:${r.y - pad}px;width:${r.w + pad * 2}px;height:${r.h + pad * 2}px"></div>`);
    if (!pinned.has(n)) {
      pinned.add(n);
      const pos = el.dataset.pinpos || (attr === 'cpin' ? 'tr' : 'tl');
      const px = { tl: r.x - pad, tr: r.x + r.w - 30, br: r.x + r.w - 50, left: r.x - 42, right: r.x + r.w + 42 }[pos];
      const py = { tl: r.y - pad, tr: r.y + 10, br: r.y + r.h - 50, left: r.y + r.h / 2, right: r.y + r.h / 2 }[pos];
      parts.push(`<div class="pin ${k}" style="left:${px}px;top:${py}px">${n}</div>`);
    }
  };
  const attrs = dev.classList.contains('after') ? ['c', 'cpin'] : ['cb'];
  attrs.forEach((a) => dev.querySelectorAll(`[data-${a}]`).forEach((el) => add(el, Number(el.dataset[a]), a)));
  ov.innerHTML = parts.join('');
}

// ---------- page ----------
const params = new URLSearchParams(location.search);
const state = { view: params.get('view') || 'side', changes: params.get('changes') !== '0' };
if (params.get('solo') === '1') document.body.classList.add('solo');

function render() {
  const stage = document.getElementById('stage');
  const views = state.view === 'side' ? ['before', 'after'] : [state.view];
  stage.className = `stage ${views.length === 2 ? 'two' : ''} ${state.changes ? 'show-changes' : ''}`;
  stage.innerHTML = views.map((v) => `<div><p class="pane-label">${v === 'before' ? 'Before · current Home' : 'After · proposed'}</p>
    <div class="frame" data-v="${v}">${device(v)}</div></div>`).join('');
  document.querySelectorAll('.seg button').forEach((b) => b.setAttribute('aria-pressed', String(b.dataset.view === state.view)));
  document.getElementById('show').checked = state.changes;
  fit();
}

function fit() {
  const solo = document.body.classList.contains('solo');
  document.querySelectorAll('.frame').forEach((f) => {
    const w = solo ? window.innerWidth : f.parentElement.clientWidth;
    const s = solo ? Math.min(w / 1920, window.innerHeight / 1280) : w / 1920;
    const dev = f.querySelector('.device');
    dev.style.transform = `scale(${s})`;
    f.style.width = `${1920 * s}px`; f.style.height = `${1280 * s}px`;
    drawOverlay(dev);
  });
}

document.getElementById('changes-list').innerHTML = CHANGES.map((c) =>
  `<li><span class="num t-${c.kind}">${c.n}</span><div><b>${c.title}.</b> <span>${c.why}</span></div></li>`).join('');
document.querySelectorAll('.seg button').forEach((b) => b.addEventListener('click', () => { state.view = b.dataset.view; render(); }));
document.getElementById('show').addEventListener('change', (e) => { state.changes = e.target.checked; render(); });
window.addEventListener('resize', fit);
render();
