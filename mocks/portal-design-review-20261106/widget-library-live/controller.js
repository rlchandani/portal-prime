// Event wiring, review panel, catalog and screenshot presets.
'use strict';

const STEPS = [['home', 'Start on Home'], ['edit', 'Tap Customize (edit mode)'], ['library', 'Tap Add widget → library'],
  ['size', 'Pick a widget → choose size'], ['added', 'It lands in the Home grid'], ['settings', 'Tap its header Settings'], ['back', 'Back to Home']];

const WHERE = {
  home: ['Home', 'HomeActivity. Customize turns on edit mode; “Add widget” then appears in the Favorite apps row. Long-press a widget also enters edit mode.'],
  library: ['Separate sheet over Home', 'The widget library is a full-screen Compose Dialog owned by HomeActivity, not a Settings page. Settings can only ask Home to open it.'],
  providers: ['Separate sheet over Home', 'Android app widgets are a second list inside the same library dialog; binding stays with Home’s AppWidgetHost.'],
  settings: ['Settings (separate activity)', 'Per-widget settings live in the Settings shell under Home & widgets (and Connections for Home Assistant). Back returns to Home.'],
  timers: ['In place on Home', 'Timers has no Settings page. Its header and Settings action open the Timers page over Home.'],
};

function mark(step) { S.visited.add(step); }
function toast(msg) { S.toast = msg; clearTimeout(toast.t); toast.t = setTimeout(() => { S.toast = ''; render(); }, 2600); }

function addWidget(kind, size, extra = {}) {
  let w;
  if (!size || size === (kind === 'photo_memory' ? '2x4' : '1x2')) w = make(kind, extra);
  else { const [sx, sy] = size.split('x').map(Number); w = make(kind, { sx, sy, explicit: true, ...extra }); }
  S.widgets = [...S.widgets, w];
  const idx = pages(S.widgets).rest.indexOf(w);
  S.page = idx < 0 ? 0 : Math.floor(idx / 4);
  S.screen = 'home'; S.sizeFor = null; S.fresh = w.id; mark('added');
  toast(`${KIND[kind].label} added to page ${S.page + 1}. In the app you stay on the page you were on.`);
}

function openSettings(page, from = 'home') {
  S.settings = { page, cat: page ? categoryOf(page) : 'home', from };
  S.screen = 'settings'; mark('settings');
}

function widgetSettings(w, edit) {
  const k = KIND[w.kind]; const target = edit ? k.editSettings : k.settings;
  if (target === 'timers') { S.screen = 'timers'; mark('settings'); return; }
  if (target === 'appconfig') { toast('Opens the app’s own configure screen (or Android app info).'); return; }
  if (target === 'preview') { toast('Tapping the photo opens the photo-frame preview.'); return; }
  if (target === 'dashboard') { toast('Opens the Home Assistant dashboard.'); return; }
  openSettings(target);
}

const ACTIONS = {
  customize() { S.edit = !S.edit; if (S.edit) mark('edit'); },
  'add-widget'() { S.screen = 'library'; mark('library'); },
  'go-home'() { S.screen = 'home'; S.edit = false; S.sizeFor = null; mark('back'); },
  'go-home-keep'() { S.screen = 'home'; mark('back'); },
  'lib-back'() { if (S.screen === 'providers') S.screen = 'library'; else { S.screen = 'home'; } },
  'lib-customize'() { S.screen = 'home'; S.edit = true; mark('edit'); },
  'pick-kind'(el) {
    const kind = el.dataset.kind;
    if (kind === 'appwidget') { S.screen = 'providers'; return; }
    if (!S.sizeStep) { addWidget(kind); return; }
    S.sizeFor = kind; S.sizePick = kind === 'photo_memory' ? '2x4' : '1x2'; mark('size');
  },
  'pick-provider'(el) { addWidget('appwidget', '2x2', { app: el.dataset.app }); },
  'size-pick'(el) { S.sizePick = el.dataset.size; },
  'size-cancel'() { S.sizeFor = null; },
  'size-add'() { addWidget(S.sizeFor, S.sizePick); },
  'open-settings'() { openSettings(null); },
  'settings-cat'(el) { S.settings = { page: null, cat: el.dataset.cat, from: 'settings' }; },
  'settings-nav'(el) {
    const t = el.dataset.target;
    if (t === 'customize') { S.screen = 'home'; S.edit = false; toast('Settings › Customize home only returns to Home. Touch and hold a widget to edit.'); return; }
    S.settings = { page: t, cat: S.settings.page ? S.settings.cat : categoryOf(t), from: 'settings' };
  },
  'settings-back'() {
    if (S.settings.page && S.settings.from === 'settings') S.settings = { ...S.settings, page: null };
    else { S.screen = 'home'; mark('back'); }
  },
  'head-settings'(el) { widgetSettings(S.widgets.find((w) => w.id === el.dataset.id), false); },
  'edit-settings'(el) { widgetSettings(S.widgets.find((w) => w.id === el.dataset.id), true); },
  size(el) {
    const w = S.widgets.find((x) => x.id === el.dataset.id);
    const [sx, sy] = nextSpan(displaySpan(w));
    S.widgets = S.widgets.map((x) => (x.id === w.id ? { ...x, sx, sy, explicit: true } : x));
    S.fresh = w.id;
  },
  remove(el) { S.dialog = { type: 'remove', id: el.dataset.id }; },
  move(el) { S.dialog = { type: 'move', id: el.dataset.id }; },
  'dlg-cancel'() { S.dialog = null; },
  'remove-ok'() { S.widgets = S.widgets.filter((w) => w.id !== S.dialog.id); S.dialog = null; },
  'move-to'(el) {
    const src = S.widgets.find((w) => w.id === S.dialog.id); const without = S.widgets.filter((w) => w !== src);
    const at = without.findIndex((w) => w.id === el.dataset.target);
    S.widgets = [...without.slice(0, at), src, ...without.slice(at)]; S.dialog = null;
  },
  page(el) { const p = Number(el.dataset.page); if (p >= 0 && p < pages(S.widgets).count) S.page = p; },
  noop() {},
};

// ---------- resize badge: drag snaps to grid steps like ResizeBadge (+0.35 rounding) ----------
let drag = null;
function startResize(e, el) {
  const w = S.widgets.find((x) => x.id === el.dataset.id);
  const list = pageWidgets(S.widgets, S.page); const place = pack(list.map(displaySpan))[list.indexOf(w)];
  drag = { w, place, x0: e.clientX, y0: e.clientY, start: displaySpan(w) };
  S.draft = { place, w: drag.start.w, h: drag.start.h };
  e.preventDefault();
}
window.addEventListener('pointermove', (e) => {
  if (!drag) return;
  const s = currentScale();
  const steps = (v, unit) => Math.sign(v) * Math.floor(Math.abs(v / unit) + 0.35);
  const nw = Math.min(4, Math.max(1, drag.start.w + steps((e.clientX - drag.x0) / s, GRID.col + GRID.gap)));
  const nh = Math.min(4, Math.max(1, drag.start.h + steps((e.clientY - drag.y0) / s, GRID.row + GRID.gap)));
  if (nw !== S.draft.w || nh !== S.draft.h) { S.draft = { ...S.draft, w: nw, h: nh }; render(); }
});
window.addEventListener('pointerup', () => {
  if (!drag) return;
  const { w, h } = S.draft;
  if (w !== drag.start.w || h !== drag.start.h) S.widgets = S.widgets.map((x) => (x.id === drag.w.id ? { ...x, sx: w, sy: h, explicit: true } : x));
  drag = null; S.draft = null; render();
});

// ---------- long press to enter edit mode ----------
let hold = null;
function startHold(card) { hold = setTimeout(() => { S.edit = true; mark('edit'); render(); }, 550); card.addEventListener('pointerup', () => clearTimeout(hold), { once: true }); }

document.addEventListener('pointerdown', (e) => {
  const badge = e.target.closest('[data-act=resize]');
  if (badge) { startResize(e, badge); render(); return; }
  const card = e.target.closest('#device .wcard');
  if (card && !S.edit && !e.target.closest('button')) startHold(card);
});
document.addEventListener('pointerleave', () => clearTimeout(hold));
document.addEventListener('click', (e) => {
  const el = e.target.closest('[data-act]');
  if (!el || el.dataset.act === 'resize') return;
  const fn = ACTIONS[el.dataset.act];
  if (fn) { fn(el); render(); }
});

// ---------- review chrome ----------
function panelHtml() {
  const [where, detail] = WHERE[S.screen];
  return `<div class="note"><small>Where this lives</small><b>${where}</b><p>${detail}</p></div>
    <ol class="steps">${STEPS.map(([id, t]) => `<li class="${S.visited.has(id) ? 'done' : ''}">${t}</li>`).join('')}</ol>
    <label class="chk"><input type="checkbox" id="sizestep" ${S.sizeStep ? 'checked' : ''}> Size step (proposed)</label>
    <label class="chk"><input type="checkbox" id="adv" ${S.advanced ? 'checked' : ''}> Advanced settings on (shows Options)</label>
    <button type="button" id="reset" class="resetbtn">Reset prototype</button>`;
}

function catalogHtml() {
  return `<div class="cat-intro"><p class="eyebrow">Every widget kind in source · 15</p><h1>Widget catalog</h1>
    <p class="lede">Each kind at the three sizes the Size button cycles through (HomeWidgetStore.nextWidgetSpan): Small 1×1, Medium 2×1, Large 2×2. New widgets arrive at Standard 1×2 (Photo memory: 2×4 hero). Dragging the corner allows any size up to 4×4.</p></div>
    ${KINDS.map((k) => `<section class="kind"><div class="kind-head"><h2>${icon(k.icon)}${k.label}</h2>
      ${k.retire ? '<span class="tag retire">Recommended to retire</span>' : ''}${k.advanced ? '<span class="tag">Advanced only</span>' : ''}
      <span class="where">${whereLine(k)}</span></div>
      <div class="specs">${[['Small', 1, 1], ['Medium', 2, 1], ['Large', 2, 2]].map(([n, w, h], i) => {
        const r = cellRect({ c: 0, r: 0, w, h });
        return `<figure class="spec" style="--w:${r.w};--h:${r.h}"><div class="specframe"><div class="specscale" style="width:${r.w}px;height:${r.h}px">${widgetCard({ kind: k.kind }, { w, h }, { preview: true })}</div></div>
          <figcaption><b>${n} · ${w}×${h}</b>${k.shows[i]}</figcaption></figure>`;
      }).join('')}</div></section>`).join('')}`;
}
function whereLine(k) {
  const m = { dashboard: 'Header opens the HA dashboard', timers: 'Header opens the Timers page on Home', preview: 'Tap opens the photo preview', appconfig: 'Settings opens the app’s configure screen' };
  return m[k.settings] || `Header → Settings › ${PAGE_TITLES[k.settings]}`;
}

function currentScale() { const d = document.getElementById('device'); return d ? d.getBoundingClientRect().width / 1920 : 1; }

function fit() {
  const frame = document.getElementById('frame'); const dev = document.getElementById('device');
  if (frame && dev) {
    const solo = document.body.classList.contains('solo');
    const w = solo ? window.innerWidth : frame.parentElement.clientWidth;
    const s = solo ? Math.min(w / 1920, window.innerHeight / 1280) : w / 1920;
    dev.style.transform = `scale(${s})`; frame.style.width = `${1920 * s}px`; frame.style.height = `${1280 * s}px`;
  }
  document.querySelectorAll('.specs').forEach((row) => {
    const avail = row.clientWidth - 2 * 20; const s = avail / (434 + 892 + 892);
    row.querySelectorAll('.spec').forEach((f) => {
      const fw = Number(f.style.getPropertyValue('--w')); const fh = Number(f.style.getPropertyValue('--h'));
      f.querySelector('.specframe').style.cssText = `width:${fw * s}px;height:${fh * s}px`;
      f.querySelector('.specscale').style.transform = `scale(${s})`;
      f.style.width = `${fw * s}px`;
    });
  });
}

function render() {
  document.querySelectorAll('.tabs button').forEach((b) => b.setAttribute('aria-pressed', String(b.dataset.tab === S.tab)));
  document.getElementById('proto').hidden = S.tab !== 'proto';
  document.getElementById('catalog').hidden = S.tab !== 'catalog';
  if (S.tab === 'proto') {
    document.getElementById('device').innerHTML = deviceHtml();
    document.getElementById('panel').innerHTML = panelHtml();
    document.getElementById('sizestep').onchange = (e) => { S.sizeStep = e.target.checked; };
    document.getElementById('adv').onchange = (e) => { S.advanced = e.target.checked; render(); };
    document.getElementById('reset').onclick = () => { Object.assign(S, { screen: 'home', edit: false, page: 0, widgets: initialWidgets(), sizeFor: null, dialog: null, fresh: null, visited: new Set(['home']) }); render(); };
  } else if (!document.getElementById('catalog').dataset.done) {
    document.getElementById('catalog').innerHTML = catalogHtml(); document.getElementById('catalog').dataset.done = '1';
  }
  fit();
}

// ---------- presets for screenshots: ?step=home|edit|library|size|added|settings|catalog ----------
function preset(step) {
  const run = (...acts) => acts.forEach(([a, data]) => ACTIONS[a]({ dataset: data || {} }));
  if (step === 'edit') run(['customize']);
  if (step === 'library') run(['customize'], ['add-widget']);
  if (step === 'size') run(['customize'], ['add-widget'], ['pick-kind', { kind: 'world_clock' }], ['size-pick', { size: '2x1' }]);
  if (step === 'added') { run(['customize'], ['add-widget'], ['pick-kind', { kind: 'world_clock' }], ['size-pick', { size: '2x1' }], ['size-add'], ['customize']); }
  if (step === 'settings') { run(['customize'], ['add-widget'], ['pick-kind', { kind: 'world_clock' }], ['size-add']); S.edit = false; S.toast = ''; run(['head-settings', { id: S.widgets.at(-1).id }]); }
  if (step === 'catalog') S.tab = 'catalog';
}

const params = new URLSearchParams(location.search);
if (params.get('solo') === '1') document.body.classList.add('solo');
document.querySelectorAll('.tabs button').forEach((b) => b.addEventListener('click', () => { S.tab = b.dataset.tab; render(); }));
window.addEventListener('resize', fit);
preset(params.get('step') || 'home');
render();
