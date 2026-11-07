// Add-widget v2: tap an empty slot -> pick a widget -> see it in place, pick a shape -> Add.
// Baseline: 2-row x 4-column slot grid per page, free placement, whole-slot sizes only. Sample data.
'use strict';

const G = { x: 24, y: 240, cols: 4, rows: 2, gap: 12, colW: 459, rowH: 388 };
const SHAPES = [[1, 1], [2, 1], [1, 2], [2, 2]];
const CORE = ['weather', 'calendar', 'ha', 'timers', 'todo', 'alarm', 'world_clock'];
const MORE = ['today', 'quick_controls', 'news', 'stocks', 'crypto', 'options', 'appwidget'];
let seq = 0;
const W = (kind, page, c, r, w = 1, h = 1) => ({ id: `w${++seq}`, kind, page, c, r, w, h });
const initial = () => [W('photo_memory', 0, 0, 0, 2, 2), W('weather', 0, 2, 0), W('calendar', 0, 2, 1),
  W('today', 1, 0, 0, 2, 1), W('ha', 1, 2, 0)];

const S = { widgets: initial(), page: 0, edit: false, step: 'home', slot: null, pick: null, shape: [1, 1], more: false, dialog: null, fresh: null };

// ---------- slot model ----------
const onPage = (p) => S.widgets.filter((w) => w.page === p);
function occupied(p, ignore) {
  const grid = Array.from({ length: G.rows }, () => Array(G.cols).fill(null));
  onPage(p).forEach((w) => { if (w.id !== ignore) for (let y = w.r; y < w.r + w.h; y++) for (let x = w.c; x < w.c + w.w; x++) grid[y][x] = w.id; });
  return grid;
}
const free = (p, ignore) => { const g = occupied(p, ignore); const out = []; g.forEach((row, r) => row.forEach((v, c) => { if (!v) out.push([c, r]); })); return out; };
/** First placement of shape w×h that covers slot (c,r) and only uses empty slots. */
function fitAround(p, [c, r], [w, h], ignore) {
  const g = occupied(p, ignore);
  for (let y = r - h + 1; y <= r; y++) for (let x = c - w + 1; x <= c; x++) {
    if (x < 0 || y < 0 || x + w > G.cols || y + h > G.rows) continue;
    let ok = true;
    for (let yy = y; yy < y + h && ok; yy++) for (let xx = x; xx < x + w; xx++) if (g[yy][xx]) { ok = false; break; }
    if (ok) return { c: x, r: y, w, h };
  }
  return null;
}
const pageCount = () => Math.max(2, ...S.widgets.map((w) => w.page + 1));
const rect = ({ c, r, w, h }) => ({ x: G.x + c * (G.colW + G.gap), y: G.y + r * (G.rowH + G.gap), w: w * G.colW + (w - 1) * G.gap, h: h * G.rowH + (h - 1) * G.gap });
const pos = (o) => { const q = rect(o); return `left:${q.x}px;top:${q.y}px;width:${q.w}px;height:${q.h}px`; };

// ---------- rendering ----------
const rshape = (w, h) => (w === 2 ? 'l' : 't'); // slot sizes reuse the v1 renderers' roomy layouts
function card(w, { edit = false, ghost = false, preview = false } = {}) {
  const k = KIND[w.kind]; const photo = w.kind === 'photo_memory'; const hosted = w.kind === 'appwidget';
  const head = photo || hosted ? '' : `<div class="head">${icon(k.icon)}<span class="ttl">${k.label}</span>${preview || edit || ghost ? '' : '<span class="hact">Settings</span>'}</div>`;
  const chrome = edit ? `<button class="grip" type="button" data-grip="${w.id}" aria-label="Drag ${k.label}"><i></i><i></i><i></i><i></i><i></i><i></i></button>
    <div class="ebar"><button data-act="move" data-id="${w.id}" type="button">Move</button><button data-act="size" data-id="${w.id}" type="button">Size</button><button type="button" data-act="noop">Settings</button><button data-act="remove" data-id="${w.id}" type="button">Remove</button></div>` : '';
  return `<div class="wcard ${photo ? 'photo' : ''} sz-${rshape(w.w, w.h)} ${ghost ? 'ghost' : ''} ${S.fresh === w.id ? 'fresh' : ''}">${head}<div class="wbody">${R[w.kind](rshape(w.w, w.h), w)}</div>${chrome}</div>`;
}

function header() {
  return `<div class="abs hdr"><span class="wordmark">immortal</span><span class="vsep"></span><span class="room">Family Area</span><span class="rbtn">${icon('frame')}</span>
    <div class="hright"><span class="conn">${icon('wifi')}Connected</span>
    <button class="customize" type="button" data-act="customize">${S.edit ? 'Done' : 'Customize'}</button>
    ${['phone', 'sleep', 'sun', 'music', 'search', 'gear'].map((n) => `<span class="rbtn">${icon(n)}</span>`).join('')}</div></div>
    <div class="abs greet"><h2>Good morning.</h2></div><div class="abs clock"><span class="t">8:04</span><span class="d">Friday<br>November 7</span></div>`;
}

function gridHtml() {
  const placing = S.step === 'place';
  const cells = onPage(S.page).map((w) => `<div class="cell" style="${pos(w)}" data-cell="${w.id}">${card(w, { edit: S.edit && !placing })}</div>`).join('');
  const slots = S.edit ? free(S.page).map(([c, r]) => {
    const target = placing && S.slot && S.slot[0] === c && S.slot[1] === r;
    return `<button type="button" class="slot ${placing ? 'quiet' : ''} ${target ? 'target' : ''}" style="${pos({ c, r, w: 1, h: 1 })}" data-act="slot" data-c="${c}" data-r="${r}" aria-label="Add a widget here">${placing ? '' : `<span>${ICONS.plus}</span>`}</button>`;
  }).join('') : '';
  let ghost = '';
  if (placing) {
    const at = fitAround(S.page, S.slot, S.shape);
    if (at) ghost = `<div class="cell ghostcell" style="${pos(at)}">${card({ kind: S.pick, ...at }, { ghost: true })}</div>`;
  }
  const dots = Array.from({ length: pageCount() }, (_, i) => `<button type="button" class="dot ${i === S.page ? 'on' : ''}" data-act="page" data-page="${i}" aria-label="Page ${i + 1}"></button>`).join('');
  return `${slots}${cells}${ghost}<div class="abs dots">${dots}</div>`;
}

const FAVS = [['Calls', 'call', 'linear-gradient(#4cd964,#28b64a)'], ['Browser', 'globe', 'linear-gradient(#4b93ff,#2363e6)'], ['Jarvis', 'mic', 'linear-gradient(#ffb340,#ff8a00)'],
  ['Material Files', 'folder', 'linear-gradient(#3d8bff,#1765e0)'], ['Portal Settings', 'gear', 'linear-gradient(#3fd6d0,#13aeb0)'], ['PortalHub', 'radio', 'linear-gradient(#ffa040,#ff7a1a)']];
function bottom() {
  if (S.step === 'place') return placeBar();
  return `<div class="abs favs">${FAVS.map(([l, i, bg]) => `<div class="fav"><span class="app" style="background:${bg}">${icon(i)}</span><span>${l}</span></div>`).join('')}</div>
    <div class="abs dock"><span class="on">${icon('home')}Home</span><span>${icon('apps')}Apps</span><span>${icon('tools')}Tools</span></div>`;
}

const glyph = ([w, h]) => `<span class="glyph" style="grid-template-columns:repeat(${w},1fr);grid-template-rows:repeat(${h},1fr);width:${w * 30 + (w - 1) * 4}px;height:${h * 30 + (h - 1) * 4}px">${'<i></i>'.repeat(w * h)}</span>`;
function placeBar() {
  const shapes = SHAPES.map((sh) => {
    const ok = !!fitAround(S.page, S.slot, sh); const on = sh[0] === S.shape[0] && sh[1] === S.shape[1];
    return `<button type="button" class="shape ${on ? 'on' : ''}" data-act="shape" data-w="${sh[0]}" data-h="${sh[1]}" ${ok ? '' : 'disabled'} aria-label="${sh[0]} by ${sh[1]}${ok ? '' : ', does not fit here'}">${glyph(sh)}</button>`;
  }).join('');
  return `<div class="abs placebar"><button type="button" class="pill-btn" data-act="back">${icon('back')}${KIND[S.pick].label}</button>
    <div class="shapes">${shapes}</div><button type="button" class="add" data-act="add">Add</button></div>`;
}

function library() {
  const tile = (kind) => `<button type="button" class="pick" data-act="pick" data-kind="${kind}" aria-label="${KIND[kind].label}"><span class="pv">${card({ kind, w: 1, h: 1 }, { preview: true })}</span></button>`;
  const more = S.more ? MORE.map(tile).join('') : '';
  const map = `<span class="minimap">${Array.from({ length: 8 }, (_, i) => { const c = i % 4; const r = Math.floor(i / 4);
    const taken = occupied(S.page)[r][c]; const t = S.slot[0] === c && S.slot[1] === r; return `<i class="${taken ? 'taken' : ''} ${t ? 't' : ''}"></i>`; }).join('')}</span>`;
  return `<div class="scrim" data-act="close"></div><div class="abs sheet" role="dialog" aria-label="Add a widget">
    <div class="shead">${map}<h2>Add a widget</h2><button type="button" class="x" data-act="close" aria-label="Close">✕</button></div>
    <div class="picks">${CORE.map(tile).join('')}<button type="button" class="pick moretile" data-act="more">${S.more ? 'Fewer' : 'More'}<small>${S.more ? '' : MORE.length + ' more'}</small></button>${more}</div></div>`;
}

function dialog() {
  if (!S.dialog) return '';
  const w = S.widgets.find((x) => x.id === S.dialog.id); const label = KIND[w.kind].label;
  if (S.dialog.type === 'remove') return `<div class="scrim"></div><div class="abs dlg"><h3>Remove ${label}?</h3><div class="acts"><button type="button" data-act="dlg-x">Cancel</button><button type="button" data-act="remove-ok">Remove</button></div></div>`;
  const spots = []; for (let p = 0; p < pageCount(); p++) free(p, w.id).forEach(([c, r]) => { const at = fitAround(p, [c, r], [w.w, w.h], w.id); if (at && at.c === c && at.r === r) spots.push([p, c, r]); });
  return `<div class="scrim"></div><div class="abs dlg"><h3>Move ${label}</h3><div class="spots">${spots.map(([p, c, r]) => `<button type="button" data-act="move-to" data-p="${p}" data-c="${c}" data-r="${r}">Page ${p + 1} · ${r ? 'bottom' : 'top'} ${['left', 'centre-left', 'centre-right', 'right'][c]}</button>`).join('') || '<p>No empty spot fits.</p>'}</div>
    <div class="acts"><button type="button" data-act="dlg-x">Cancel</button></div></div>`;
}

function render() {
  const dev = document.getElementById('device');
  dev.innerHTML = `${header()}<div class="gridwrap ${S.edit ? 'editing' : ''} ${S.step === 'place' ? 'placing' : ''}">${gridHtml()}</div>${bottom()}${S.step === 'library' ? library() : ''}${dialog()}`;
  document.querySelectorAll('[data-stepname]').forEach((el) => el.classList.toggle('on', el.dataset.stepname === stepName()));
  fit();
}
function stepName() { if (S.step === 'library') return 'library'; if (S.step === 'place') return 'place'; if (S.fresh) return 'added'; return S.edit ? 'customize' : 'home'; }
