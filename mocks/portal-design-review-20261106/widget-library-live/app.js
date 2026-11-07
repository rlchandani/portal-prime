// Live prototype: Home -> Customize -> Add widget -> library -> size -> Home -> Settings -> back.
// Layout rules ported from HomeWidgetStore / HomePresentation / ApprovedWidgetPacking. Sample data only.
'use strict';

const GRID = { x: 56, y: 267, col: 434, row: 156.25, gap: 24, rows: 4, cols: 4 };
const DEFAULT_SMALL_Y = ['today', 'quick_controls', 'photo_memory']; // added with spanY = 1 (HomeActivity onAdd)
let seq = 0;
const make = (kind, extra = {}) => ({ id: `w${++seq}`, kind, sx: 2, sy: DEFAULT_SMALL_Y.includes(kind) ? 1 : 2, explicit: false, ...extra });
const initialWidgets = () => [make('photo_memory'), make('ha'), make('today'), make('calendar'), make('weather'), make('quick_controls')];

const S = { tab: 'proto', screen: 'home', edit: false, page: 0, widgets: initialWidgets(), sizeStep: true, advanced: false,
  sizeFor: null, sizePick: '1x2', settings: { page: null, cat: 'home', from: 'home' }, dialog: null, toast: '', fresh: null, draft: null, visited: new Set(['home']) };

// ---------- presentation (HomePresentation.spans / page) ----------
function displaySpan(w) {
  const def = DEFAULT_SMALL_Y.includes(w.kind) ? [2, 1] : [2, 2];
  if (w.explicit || w.kind === 'appwidget' || w.sx !== def[0] || w.sy !== def[1]) return { w: w.sx, h: w.sy };
  return w.kind === 'photo_memory' ? { w: 2, h: 4 } : { w: 1, h: 2 };
}
function pages(widgets) {
  const hero = widgets.find((w) => w.kind === 'photo_memory');
  const rest = widgets.filter((w) => w !== hero);
  const count = Math.max(1, Math.ceil(rest.length / 4));
  return { hero, rest, count };
}
function pageWidgets(widgets, index) {
  const { hero, rest } = pages(widgets);
  const chosen = [hero, ...rest.slice(index * 4, index * 4 + 4)].filter(Boolean);
  return chosen.sort((a, b) => widgets.indexOf(a) - widgets.indexOf(b));
}
function pack(spans, columns = 4) { // ApprovedWidgetPacking.pack
  const occupied = []; const out = [];
  spans.forEach((sp) => {
    const w = Math.min(Math.max(sp.w, 1), columns); const h = Math.min(Math.max(sp.h, 1), 4);
    for (let r = 0; ; r++) {
      let blocked = 0;
      for (let y = r; y < r + h; y++) blocked |= occupied[y] || 0;
      const mask = (1 << w) - 1;
      let placed = false;
      for (let c = 0; c <= columns - w; c++) {
        if (blocked & (mask << c)) continue;
        for (let y = r; y < r + h; y++) occupied[y] = (occupied[y] || 0) | (mask << c);
        out.push({ c, r, w, h }); placed = true; break;
      }
      if (placed) break;
    }
  });
  return out;
}
const nextSpan = ({ w, h }) => (w === 1 && h === 1 ? [2, 1] : w === 2 && h === 1 ? [2, 2] : [1, 1]); // nextWidgetSpan
const cellRect = (p) => ({ x: p.c * (GRID.col + GRID.gap), y: p.r * (GRID.row + GRID.gap),
  w: p.w * GRID.col + (p.w - 1) * GRID.gap, h: p.h * GRID.row + (p.h - 1) * GRID.gap });

// ---------- shared chrome ----------
const FAVORITES = [['Calls', 'call', 'linear-gradient(#4cd964,#28b64a)'], ['Browser', 'globe', 'linear-gradient(#4b93ff,#2363e6)'],
  ['Jarvis', 'mic', 'linear-gradient(#ffb340,#ff8a00)'], ['Material Files', 'folder', 'linear-gradient(#3d8bff,#1765e0)'],
  ['Portal Settings', 'gear', 'linear-gradient(#3fd6d0,#13aeb0)'], ['PortalHub', 'radio', 'linear-gradient(#ffa040,#ff7a1a)']];

function topHeader(extra = '') {
  return `<div class="abs hdr"><button class="wordmark" data-act="go-home" type="button">immortal</button><span class="vsep"></span><span class="room">Family Area</span>${extra}
    <div class="hright"><span class="conn">${icon('wifi')}Connected</span>
      ${S.screen === 'home' ? `<button class="customize" data-act="customize" type="button">${S.edit ? 'Done' : 'Customize'}</button>` : ''}
      ${['phone', 'sleep', 'sun', 'music', 'search'].map((n) => `<span class="rbtn">${icon(n)}</span>`).join('')}
      <button class="rbtn" data-act="open-settings" type="button" aria-label="Settings">${icon('gear')}</button></div></div>`;
}

function homeScreen() {
  const { count } = pages(S.widgets);
  S.page = Math.min(S.page, count - 1);
  const list = pageWidgets(S.widgets, S.page);
  const places = pack(list.map(displaySpan));
  const cells = list.map((w, i) => {
    const r = cellRect(places[i]);
    const span = { w: places[i].w, h: places[i].h };
    return `<div class="cell" style="left:${r.x}px;top:${r.y}px;width:${r.w}px;height:${r.h}px">${widgetCard(w, span, { editable: S.edit, fresh: S.fresh === w.id })}</div>`;
  }).join('');
  const draft = S.draft ? (() => { const r = cellRect({ ...S.draft.place, w: S.draft.w, h: S.draft.h }); return `<div class="draft" style="left:${r.x}px;top:${r.y}px;width:${r.w}px;height:${r.h}px"></div>`; })() : '';
  const dots = Array.from({ length: count }, (_, i) => `<button type="button" class="dot ${i === S.page ? 'on' : ''}" data-act="page" data-page="${i}" aria-label="Widget page ${i + 1}"></button>`).join('');
  return `${topHeader(`<span class="rbtn">${icon('frame')}</span>`)}
    <div class="abs greet"><h2>Good morning.</h2><p>Everything within reach.</p></div>
    <div class="abs clock"><span class="t">8:04</span><span class="d">Friday<br>November 6</span></div>
    <div class="abs grid ${S.edit ? 'editing' : ''}" id="grid">${cells}${draft}</div>
    <div class="abs pager">${count > 1 ? `<button type="button" class="pgarrow" data-act="page" data-page="${S.page - 1}" ${S.page === 0 ? 'disabled' : ''}>‹</button>${dots}<span>${S.page + 1} of ${count}</span><button type="button" class="pgarrow" data-act="page" data-page="${S.page + 1}" ${S.page >= count - 1 ? 'disabled' : ''}>›</button>` : ''}</div>
    <div class="abs favhead"><span class="lbl">Favorite apps</span>${S.edit ? '<button class="addw" data-act="add-widget" type="button">Add widget</button>' : ''}<span class="all">All apps</span></div>
    <div class="abs favs">${FAVORITES.map(([l, i, bg]) => `<div class="fav"><span class="app" style="background:${bg}">${icon(i)}</span><span>${l}</span></div>`).join('')}</div>
    <div class="abs dock"><span class="on">${icon('home')}Home</span><span>${icon('apps')}Apps</span><span>${icon('tools')}Tools</span></div>
    <div class="abs bug">${icon('bug')}</div>`;
}

function libraryScreen() {
  const onHome = new Set(S.widgets.map((w) => w.kind));
  const providers = S.screen === 'providers';
  const cards = providers
    ? [['Keep Notes', 'Note', 'note'], ['Clock', 'Digital clock', 'clock'], ['Calendar', 'Month', 'month']].map(([app, name, key]) =>
      `<button class="libcard" type="button" data-act="pick-provider" data-app="${app}" data-name="${name}">${icon('apps')}<b>${name}</b><span>${app}</span></button>`)
    : KINDS.filter((k) => S.advanced || !k.advanced).map((k) =>
      `<button class="libcard" type="button" data-act="pick-kind" data-kind="${k.kind}">${icon(k.icon)}<b>${k.label}</b><span>${onHome.has(k.kind) ? 'On your home' : 'Add to your home'}</span></button>`);
  return `<div class="abs hdr lib"><button class="wordmark" data-act="go-home" type="button">immortal</button><span class="vsep"></span><span class="room">Family Area</span>
      <button class="backbtn" data-act="lib-back" type="button">${icon('back')}${providers ? 'Back' : 'Home'}</button>
      <div class="hright"><span class="conn">${icon('wifi')}Connected</span>${['phone', 'sleep', 'sun', 'search'].map((n) => `<span class="rbtn">${icon(n)}</span>`).join('')}
      <button class="rbtn" data-act="open-settings" type="button">${icon('gear')}</button></div></div>
    <div class="abs hline"></div>
    <div class="abs libscroll"><div class="libhead"><h2>${providers ? 'Android app widgets' : 'Home &amp; widgets'}</h2>
      <p>${providers ? 'Choose a widget from an installed app.' : 'Add, configure or remove any existing widget family.'}</p>
      <div class="libacts"><button class="primary" data-act="${providers ? 'noop' : 'lib-customize'}" type="button">${providers ? 'Refresh' : 'Customize home'}</button>
      ${providers ? '<button class="textbtn" data-act="lib-back" type="button">Home &amp; widgets</button>' : ''}</div></div>
      <div class="libgrid">${cards.join('')}</div></div>
    ${S.sizeFor ? sizeSheet() : ''}`;
}

const SIZE_OPTIONS = [['1x1', 'Small', 1, 1], ['1x2', 'Standard', 1, 2], ['2x1', 'Medium', 2, 1], ['2x2', 'Large', 2, 2]];
function sizeSheet() {
  const k = KIND[S.sizeFor];
  const opts = (S.sizeFor === 'photo_memory' ? [...SIZE_OPTIONS.filter(([id]) => id !== '1x2'), ['2x4', 'Hero', 2, 4]] : SIZE_OPTIONS);
  const scale = 0.55;
  const tiles = opts.map(([id, name, w, h]) => {
    const r = cellRect({ c: 0, r: 0, w, h });
    return `<button type="button" class="szopt ${S.sizePick === id ? 'on' : ''}" data-act="size-pick" data-size="${id}">
      <span class="szprev" style="width:${r.w * scale}px;height:${r.h * scale}px"><span class="szinner" style="width:${r.w}px;height:${r.h}px;transform:scale(${scale})">${widgetCard({ kind: k.kind }, { w, h }, { preview: true })}</span></span>
      <b>${name} · ${w}×${h}</b><small>${id === (S.sizeFor === 'photo_memory' ? '2x4' : '1x2') ? 'Default today' : ''}</small></button>`;
  }).join('');
  return `<div class="scrimall"></div><div class="abs sheet"><div class="sheethead"><h3>Add ${k.label}</h3><span class="proposed">Proposed step · not in the app today</span></div>
    <p class="sheetp">Today the widget is added at its default size and you resize it in Customize. Pick a size here and see what each one shows.</p>
    <div class="szrow">${tiles}</div>
    <div class="sheetacts"><button class="textbtn" data-act="size-cancel" type="button">Cancel</button><button class="primary" data-act="size-add" type="button">Add to Home</button></div></div>`;
}

function settingsScreen() {
  const { page, cat } = S.settings;
  return `<div class="abs hdr"><button class="wordmark" data-act="go-home" type="button">immortal</button><span class="vsep"></span><span class="room">Family Area</span>
      <button class="backbtn" data-act="settings-back" type="button">${icon('back')}${page && S.settings.from !== 'home' ? 'Back' : 'Home'}</button>
      <div class="hright"><span class="conn">${icon('wifi')}Connected</span></div></div><div class="abs hline"></div>
    <div class="abs sside"><h2>Settings</h2>${CATEGORIES.map(([id, title]) => `<button type="button" data-act="settings-cat" data-cat="${id}" class="scat ${id === cat ? 'on' : ''}">${title}</button>`).join('')}</div>
    <div class="abs smain">${settingsContent(page, cat)}</div>`;
}

function timersScreen() {
  return `<div class="abs hdr"><button class="wordmark" data-act="go-home" type="button">immortal</button><span class="vsep"></span><span class="room">Timers</span>
    <button class="backbtn" data-act="go-home-keep" type="button">${icon('back')}Home</button></div><div class="abs hline"></div>
    <div class="abs timerspage"><div class="big live">6:42</div><p>of 10 min · opens in place on Home (ApprovedHomeTimersPage), not in Settings</p>
    <div class="chips">${[1, 5, 10, 15, 30].map((m) => `<span class="chip">${m} min</span>`).join('')}<span class="chip">Custom</span></div></div>`;
}

function dialogHtml() {
  if (!S.dialog) return '';
  const w = S.widgets.find((x) => x.id === S.dialog.id); const label = KIND[w.kind].label;
  if (S.dialog.type === 'remove') return `<div class="scrimall"></div><div class="abs dlg"><h3>Remove ${label}?</h3><p>You can add it again from the widget library.</p>
    <div class="sheetacts"><button class="textbtn" data-act="dlg-cancel" type="button">Cancel</button><button class="textbtn" data-act="remove-ok" type="button">Remove</button></div></div>`;
  return `<div class="scrimall"></div><div class="abs dlg"><h3>Move ${label}</h3><p>Choose a position, including on another page.</p>
    <div class="movelist">${S.widgets.map((t, i) => (t.id === w.id ? '' : `<button class="textbtn" type="button" data-act="move-to" data-target="${t.id}">${i + 1}. ${KIND[t.kind].label}</button>`)).join('')}</div>
    <div class="sheetacts"><button class="textbtn" data-act="dlg-cancel" type="button">Cancel</button></div></div>`;
}

function deviceHtml() {
  const body = { home: homeScreen, library: libraryScreen, providers: libraryScreen, settings: settingsScreen, timers: timersScreen }[S.screen]();
  return `${body}${dialogHtml()}${S.toast ? `<div class="abs toast">${S.toast}</div>` : ''}`;
}
