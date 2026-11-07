// Interaction wiring, grip drag, scaling and screenshot presets.
'use strict';

const A = {
  customize() { S.edit = !S.edit; S.step = 'home'; S.fresh = null; },
  slot(d) { S.slot = [Number(d.c), Number(d.r)]; S.step = 'library'; S.more = false; },
  pick(d) { S.pick = d.kind; S.shape = SHAPES.find((sh) => fitAround(S.page, S.slot, sh)) || [1, 1]; S.step = 'place'; },
  shape(d) { S.shape = [Number(d.w), Number(d.h)]; },
  back() { S.step = 'library'; },
  close() { S.step = 'home'; },
  more() { S.more = !S.more; },
  add() {
    const at = fitAround(S.page, S.slot, S.shape); if (!at) return;
    const w = W(S.pick, S.page, at.c, at.r, at.w, at.h);
    S.widgets = [...S.widgets, w]; S.fresh = w.id; S.step = 'home';
  },
  page(d) { S.page = Number(d.page); },
  size(d) {
    const w = S.widgets.find((x) => x.id === d.id);
    const i = SHAPES.findIndex(([a, b]) => a === w.w && b === w.h);
    for (let k = 1; k <= SHAPES.length; k++) {
      const sh = SHAPES[(i + k) % SHAPES.length]; const at = fitAround(w.page, [w.c, w.r], sh, w.id);
      if (at) { S.widgets = S.widgets.map((x) => (x.id === w.id ? { ...x, ...at } : x)); S.fresh = w.id; return; }
    }
  },
  remove(d) { S.dialog = { type: 'remove', id: d.id }; },
  'remove-ok'() { S.widgets = S.widgets.filter((w) => w.id !== S.dialog.id); S.dialog = null; },
  move(d) { S.dialog = { type: 'move', id: d.id }; },
  'move-to'(d) { S.widgets = S.widgets.map((w) => (w.id === S.dialog.id ? { ...w, page: Number(d.p), c: Number(d.c), r: Number(d.r) } : w)); S.page = Number(d.p); S.dialog = null; },
  'dlg-x'() { S.dialog = null; },
  noop() {},
};

document.addEventListener('click', (e) => {
  const el = e.target.closest('[data-act]');
  if (el && A[el.dataset.act]) { A[el.dataset.act](el.dataset); render(); }
});

// ---------- grip drag: drop on any empty top-left that fits ----------
let drag = null;
const scale = () => document.getElementById('device').getBoundingClientRect().width / 1920;
function slotAt(clientX, clientY) {
  const d = document.getElementById('device').getBoundingClientRect(); const s = scale();
  const x = (clientX - d.left) / s - G.x; const y = (clientY - d.top) / s - G.y;
  return [Math.floor(x / (G.colW + G.gap)), Math.floor(y / (G.rowH + G.gap))];
}
document.addEventListener('pointerdown', (e) => {
  const g = e.target.closest('[data-grip]'); if (!g) return;
  const cell = g.closest('.cell'); drag = { id: g.dataset.grip, cell, x0: e.clientX, y0: e.clientY };
  cell.classList.add('dragging'); e.preventDefault();
});
document.addEventListener('pointermove', (e) => {
  if (!drag) return; const s = scale();
  drag.cell.style.transform = `translate(${(e.clientX - drag.x0) / s}px, ${(e.clientY - drag.y0) / s}px)`;
});
document.addEventListener('pointerup', (e) => {
  if (!drag) return;
  const w = S.widgets.find((x) => x.id === drag.id); const [c, r] = slotAt(e.clientX, e.clientY);
  const at = c >= 0 && r >= 0 ? fitAround(S.page, [c, r], [w.w, w.h], w.id) : null;
  if (at) S.widgets = S.widgets.map((x) => (x.id === w.id ? { ...x, c: at.c, r: at.r } : x));
  drag = null; render();
});

function fit() {
  const frame = document.getElementById('frame'); const dev = document.getElementById('device');
  const solo = document.body.classList.contains('solo');
  const availW = solo ? window.innerWidth : frame.parentElement.clientWidth;
  const availH = solo ? window.innerHeight : window.innerHeight - frame.getBoundingClientRect().top - 12;
  const s = Math.min(availW / 1920, availH / 1280);
  dev.style.transform = `scale(${s})`; frame.style.width = `${1920 * s}px`; frame.style.height = `${1280 * s}px`;
}

const PRESETS = {
  home() {},
  customize() { A.customize(); },
  library() { A.customize(); A.slot({ c: 3, r: 0 }); },
  more() { A.customize(); A.slot({ c: 3, r: 0 }); A.more(); },
  place() { A.customize(); A.slot({ c: 3, r: 0 }); A.pick({ kind: 'timers' }); A.shape({ w: 1, h: 2 }); },
  added() { PRESETS.place(); A.add(); },
  done() { PRESETS.added(); A.customize(); },
};
function reset(step) { Object.assign(S, { widgets: initial(), page: 0, edit: false, step: 'home', slot: null, pick: null, more: false, dialog: null, fresh: null }); PRESETS[step](); render(); }

document.querySelectorAll('[data-stepname]').forEach((b) => b.addEventListener('click', () => reset(b.dataset.stepname)));
const params = new URLSearchParams(location.search);
if (params.get('solo') === '1') document.body.classList.add('solo');
window.addEventListener('resize', fit);
reset(params.get('step') || 'home');
