// The 12 screensaver modes, unified into the SS.MODES registry. Each: { name, run(ctx, deck) }.
// run() renders into ctx.root and returns a cleanup() the engine calls before the next mode.
// Shared helpers from SS: bucket, take, counts. Photo helpers: ctx.url, window.aspect.

(function () {
  const SS = window.SS;
  const aspect = window.aspect;
  const EASE = {
    kb: "cubic-bezier(0.33,0,0.15,1)", fade: "cubic-bezier(0.4,0,0.2,1)",
    outExpo: "cubic-bezier(0.16,1,0.3,1)", quart: "cubic-bezier(0.77,0,0.175,1)", quint: "cubic-bezier(0.22,1,0.36,1)",
  };

  // ---- tiny DOM helpers -----------------------------------------------------
  function el(cls, parent) { const d = document.createElement("div"); if (cls) d.className = cls; if (parent) parent.appendChild(d); return d; }
  function bg(node, url, size) { node.style.backgroundImage = `url("${url}")`; node.style.backgroundSize = size || "cover"; node.style.backgroundPosition = "center"; node.style.backgroundRepeat = "no-repeat"; }
  // Anchor a cover-crop on the detected face (fx/fy in 0..1) so heads aren't clipped. Falls back to
  // the CSS default (a top-biased center) when the photo has no focal point yet.
  function hasFace(p) { return p && Number.isFinite(p.fx) && Number.isFinite(p.fy) && p.fx >= 0 && p.fx <= 1 && p.fy >= 0 && p.fy <= 1; }
  function faceBg(node, p) {
    if (hasFace(p)) {
      node.style.backgroundPosition = (p.fx * 100).toFixed(1) + "% " + (p.fy * 100).toFixed(1) + "%";
      node.style.animation = "none";
    }
  }
  function kbVars(node, photo) {
    // Keep a prepared photo's animation alive when its parent starts fading out.
    node.classList.add("ss-motion");
    if (hasFace(photo)) {
      ["--s0", "--s1"].forEach(k => node.style.setProperty(k, 1));
      ["--x0", "--y0", "--x1", "--y1"].forEach(k => node.style.setProperty(k, "0%"));
      return;
    }
    const zoomIn = Math.random() > 0.4; const s0 = zoomIn ? 1.04 : 1.15, s1 = zoomIn ? 1.15 : 1.04;
    const s = Math.max(s0, s1), bound = ((s - 1) / 2) * 100 * 0.82;
    const wide = aspect(photo) > 1.2; const bx = wide ? bound : bound * 0.5, by = wide ? bound * 0.5 : bound;
    const rx = () => (Math.random() * 2 - 1) * bx, ry = () => (Math.random() * 2 - 1) * by;
    node.style.setProperty("--s0", s0); node.style.setProperty("--s1", s1);
    node.style.setProperty("--x0", rx() + "%"); node.style.setProperty("--y0", ry() + "%");
    node.style.setProperty("--x1", rx() + "%"); node.style.setProperty("--y1", ry() + "%");
  }
  // One owner for initial readiness, decode cancellation, non-overlapping swaps and pausable timers.
  function makeTimers(ctx) {
    const controller = new AbortController(), tasks = new Set(), frames = new Set();
    let active = true, paused = false, started = false, busy = false, waiters = [];
    let ready = Promise.resolve(false);
    function running() {
      if (!active) return Promise.resolve(false);
      if (!paused) return Promise.resolve(true);
      return new Promise(resolve => waiters.push(resolve));
    }
    async function load(urls) {
      try {
        await Promise.all(Array.from(new Set([].concat(urls))).map(u => window.preloadDecoded(u, controller.signal)));
        return active && await running();
      } catch (_) { return false; }
    }
    async function run(fn) {
      if (!active || busy) return false;
      if (!await running() || !active || busy) return false;
      if (paused) return run(fn);
      busy = true;
      const start = ctx.cursor.i;
      try {
        const result = await fn();
        if (active && result !== false && ctx.cursor.i !== start && ctx.record) ctx.record(start);
        return active && result !== false;
      } catch (_) { return false; }
      finally { busy = false; }
    }
    function schedule(task) {
      if (!active || paused || !started) return;
      task.due = Date.now() + task.remaining;
      task.id = setTimeout(async () => {
        task.id = null;
        if (!active || paused) return;
        if (task.repeat) await run(task.fn); else { tasks.delete(task); task.fn(); }
        if (task.repeat && active) { task.remaining = task.ms; schedule(task); }
      }, task.remaining);
    }
    function add(fn, ms, repeat) {
      const task = { fn, ms, repeat, remaining: ms, id: null };
      tasks.add(task); schedule(task); return task;
    }
    function frame(fn) {
      const id = requestAnimationFrame(() => {
        frames.delete(id);
        if (!active) return;
        if (paused) { add(fn, 0, false); return; }
        fn();
      });
      frames.add(id);
    }
    function clear() {
      active = false; controller.abort();
      tasks.forEach(task => clearTimeout(task.id)); tasks.clear();
      frames.forEach(cancelAnimationFrame); frames.clear();
      waiters.splice(0).forEach(resolve => resolve(false));
    }
    const cleanup = () => clear();
    Object.defineProperty(cleanup, "ready", { get: () => ready });
    cleanup.pause = () => {
      if (paused || !active) return;
      paused = true;
      tasks.forEach(task => {
        if (task.id != null) { task.remaining = Math.max(0, task.due - Date.now()); clearTimeout(task.id); task.id = null; }
      });
    };
    cleanup.resume = () => {
      if (!paused || !active) return;
      paused = false; waiters.splice(0).forEach(resolve => resolve(true));
      tasks.forEach(task => { if (task.id == null) schedule(task); });
    };
    return {
      load, frame, cleanup,
      every: (fn, ms) => add(fn, ms, true), after: (fn, ms) => add(fn, ms, false),
      async navigate(fn) {
        const ok = await run(fn);
        if (ok) tasks.forEach(task => {
          if (task.repeat) { clearTimeout(task.id); task.id = null; task.remaining = task.ms; schedule(task); }
        });
        return ok;
      },
      start(fn) {
        ready = run(fn).then(ok => {
          if (ok) { started = true; tasks.forEach(schedule); }
          return ok;
        });
      },
    };
  }

  const MODES = {};

  // ---- 01 Ken Burns (single, blur-fill, keeps moving through fade) ----------
  MODES.kenBurns = { name: "Cinematic Ken Burns", run(ctx, deck) {
    const t = makeTimers(ctx);
    const layers = [mk(), mk()]; layers.forEach(l => ctx.root.appendChild(l.root));
    function mk() { const root = el("ss-layer"); const fill = el("ss-fill", root); const hero = el("ss-hero", root); return { root, fill, hero }; }
    let cur = 0;
    async function step() {
      const p = SS.take(deck, ctx.cursor, ["any"])[0];
      const u = ctx.url(p, 2200); if (!await t.load(u)) return false;
      const L = layers[1 - cur];
      bg(L.fill, u, "cover"); bg(L.hero, u, "contain"); kbVars(L.hero, p);
      L.hero.style.animation = "none"; void L.hero.offsetWidth; L.hero.style.animation = "";
      layers[cur].root.classList.remove("on"); L.root.classList.add("on"); cur = 1 - cur;
    }
    t.start(step); t.every(step, ctx.photoMs);
    return t.cleanup;
  }};

  // ---- 02 Diptych (orientation-aware pairing) -------------------------------
  MODES.diptych = { name: "Orientation Diptych", run(ctx, deck) {
    const t = makeTimers(ctx);
    const frames = [el("ss-frame"), el("ss-frame")]; frames.forEach(f => ctx.root.appendChild(f));
    let cur = 0;
    function coverCell(p) { const c = el("ss-cell"); const im = el("ss-img drift", c); bg(im, ctx.url(p, 1400)); faceBg(im, p); im.style.animationDelay = (-Math.random() * 8) + "s"; return c; }
    function containCell(p) { const c = el("ss-cell contain"); const fill = el("ss-fill", c); bg(fill, ctx.url(p, 1400)); const im = el("ss-img", c); bg(im, ctx.url(p, 1800), "contain"); return c; }
    async function step() {
      const f = frames[1 - cur]; f.innerHTML = "";
      const c = SS.counts(deck); const roll = Math.random();
      let urls = [];
      if (roll < 0.28 && c.pano) { const p = SS.take(deck, ctx.cursor, ["pano"])[0]; f.appendChild(containCell(p)); urls = [ctx.url(p, 1800)]; }
      else if (roll < 0.6 && c.landscape) { const p = SS.take(deck, ctx.cursor, ["landscape"])[0]; f.appendChild(containCell(p)); urls = [ctx.url(p, 1800)]; }
      else { const ps = SS.take(deck, ctx.cursor, ["portrait", "portrait"]); ps.forEach(p => f.appendChild(containCell(p))); urls = ps.map(p => ctx.url(p, 1800)); }
      if (!await t.load(urls)) return false;
      frames[cur].classList.remove("on"); f.classList.add("on"); cur = 1 - cur;
    }
    t.start(step); t.every(step, ctx.photoMs);
    return t.cleanup;
  }};

  // ---- 03 Framed print (art mode) -------------------------------------------
  MODES.framed = { name: "Framed Print", run(ctx, deck) {
    const t = makeTimers(ctx);
    const room = el("ss-room", ctx.root);
    const arts = [mk(), mk()]; arts.forEach(a => ctx.root.appendChild(a.root));
    let cur = 0;
    function mk() { const root = el("ss-art"); const edge = el("ss-frameEdge", root); const mat = el("ss-mat", edge); const bevel = el("ss-bevel", mat); const photo = el("ss-print", bevel); return { root, photo }; }
    function sizeFor(p) { const maxW = ctx.W * 0.6, maxH = ctx.H * 0.72; const ar = aspect(p); let w = maxW, h = w / ar; if (h > maxH) { h = maxH; w = h * ar; } return { w: Math.round(w), h: Math.round(h) }; }
    async function step() {
      const p = SS.take(deck, ctx.cursor, ["any"])[0]; const u = ctx.url(p, 1800); if (!await t.load(u)) return false;
      const A = arts[1 - cur]; const { w, h } = sizeFor(p);
      A.photo.style.width = w + "px"; A.photo.style.height = h + "px"; bg(A.photo, u);
      A.photo.style.animationDelay = (-Math.random() * 10) + "s";
      arts[cur].root.classList.remove("on"); A.root.classList.add("on"); cur = 1 - cur;
    }
    t.start(step); t.every(step, ctx.photoMs);
    return t.cleanup;
  }};

  // ---- 04 Living gallery wall (justified rows + swaps) ----------------------
  MODES.galleryWall = { name: "Living Gallery Wall", run(ctx, deck) {
    const t = makeTimers(ctx); const wall = el("ss-wall", ctx.root); let cells = [];
    function buildImg(p) { const img = el("ss-img kb"); bg(img, ctx.url(p, 1200)); faceBg(img, p); if (Math.random() < 0.4) { img.classList.remove("kb"); return img; } img.style.setProperty("--d", (14 + Math.random() * 8) + "s"); img.style.setProperty("--dx", ((Math.random() * 2 - 1) * 2).toFixed(1) + "%"); img.style.setProperty("--dy", ((Math.random() * 2 - 1) * 2).toFixed(1) + "%"); img.style.animationDelay = (-Math.random() * 10) + "s"; return img; }
    function layout() {
      const W = ctx.W, H = ctx.H, pad = 16, gutter = 12; const targetH = H > 1000 ? 300 : 240;
      const rows = Math.max(2, Math.round((H - pad * 2 + gutter) / (targetH + gutter)));
      const pick = []; const seen = new Set(); let g = 0;
      while (pick.length < 10 && g < deck.length * 2) { const p = deck[ctx.cursor.i++ % deck.length]; g++; if (!seen.has(p.seed)) { seen.add(p.seed); pick.push(p); } }
      const res = window.justifiedLayout(pick, W - pad * 2, { targetRowHeight: targetH, gutter, maxRows: rows });
      // Scale the whole packed layout UNIFORMLY to fill the height — cells keep their photo aspect
      // (justifiedLayout sized them to aspect) so cover never crops within a cell. When scaled up the
      // wall exceeds the width, so center it and let the outer columns bleed off-screen rather than
      // leaving black bands (fills the frame; no per-cell distortion/clipping).
      const sy = (H - pad * 2) / Math.max(res.height, 1);
      const scaledW = (W - pad * 2) * sy;
      const offX = pad + ((W - pad * 2) - scaledW) / 2;
      return res.cells.map(c => ({
        ...c, left: c.left * sy + offX, top: c.top * sy + pad, width: c.width * sy, height: c.height * sy,
      }));
    }
    function render(target) {
      while (cells.length < target.length) { const e = el("ss-gcell", wall); cells.push({ el: e, img: null, photo: null }); }
      while (cells.length > target.length) { cells.pop().el.remove(); }
      target.forEach((slot, i) => { const c = cells[i]; c.el.style.left = slot.left + "px"; c.el.style.top = slot.top + "px"; c.el.style.width = slot.width + "px"; c.el.style.height = slot.height + "px";
        if (c.photo !== slot.photo) { const img = buildImg(slot.photo); c.el.appendChild(img); const old = c.img; t.frame(() => { if (old) old.style.opacity = "0"; }); t.after(() => { if (old) old.remove(); }, 1200); c.img = img; c.photo = slot.photo; } });
    }
    async function rebuild() { const slots = layout(); if (!await t.load(slots.map(s => ctx.url(s.photo, 1200)))) return false; render(slots); }
    async function swap() { if (!cells.length) return; const onWall = cells.map(x => x.photo && x.photo.seed).filter(Boolean); if (!SS.unused(deck, onWall).length) return; const c = cells[Math.floor(Math.random() * cells.length)]; let p = null; const wallSet = new Set(onWall); for (let k = 0; k < deck.length; k++) { const cand = deck[(ctx.cursor.i + k) % deck.length]; if (!wallSet.has(cand.seed) && SS.bucket(cand) === SS.bucket(c.photo)) { p = cand; ctx.cursor.i += k + 1; break; } } if (!p) p = SS.take(deck, ctx.cursor, ["any"], onWall)[0]; if (!await t.load(ctx.url(p, 1200))) return false; const img = buildImg(p); c.el.appendChild(img); const old = c.img; t.frame(() => { if (old) old.style.opacity = "0"; }); t.after(() => { if (old) old.remove(); }, 1200); c.img = img; c.photo = p; }
    t.start(rebuild); t.every(swap, ctx.microMs);
    return t.cleanup;
  }};

  // ---- 05 Cinematic push -----------------------------------------------------
  MODES.push = { name: "Cinematic Push", run(ctx, deck) {
    const t = makeTimers(ctx); const layers = [mk(), mk()]; layers.forEach(l => ctx.root.appendChild(l.root)); let cur = 0, first = true;
    function mk() { const root = el("ss-layer"); const fill = el("ss-fill", root); const hero = el("ss-hero", root); return { root, fill, hero }; }
    async function step() {
      const p = SS.take(deck, ctx.cursor, ["any"])[0]; const u = ctx.url(p, 2200); if (!await t.load(u)) return false;
      const inL = layers[1 - cur], outL = layers[cur]; bg(inL.fill, u); bg(inL.hero, u, "contain");
      inL.root.className = "ss-layer push-enter"; if (!first) outL.root.className = "ss-layer push-leave";
      t.after(() => { inL.root.className = "ss-layer"; inL.root.style.opacity = "1"; inL.hero.style.animation = hasFace(p) ? "none" : `drift ${ctx.motionMs}ms linear forwards`; }, 1150);
      cur = 1 - cur; first = false;
    }
    t.start(step); t.every(step, ctx.photoMs);
    return t.cleanup;
  }};

  // ---- 06 Memories (single + occasional titled collage beat) ----------------
  MODES.memories = { name: "Memories", run(ctx, deck) {
    // Two alternating single layers (like kenBurns): reusing one layer swapped its
    // background in place — an instant hard cut whenever the image was already cached.
    const t = makeTimers(ctx); const singles = [mk(), mk()]; singles.forEach(s => ctx.root.appendChild(s.root)); let sCur = 0;
    const collage = el("ss-collage", ctx.root); const title = el("ss-mtitle", ctx.root); const big = el("big", title); const sub = el("sub", title);
    let beat = 0;
    function mk() { const root = el("ss-single"); const fill = el("ss-fill", root); const hero = el("ss-hero", root); return { root, fill, hero }; }
    async function showSingle() { const p = SS.take(deck, ctx.cursor, ["any"])[0]; const u = ctx.url(p, 2200); if (!await t.load(u)) return false; collage.classList.remove("on"); const S = singles[1 - sCur]; bg(S.fill, u); bg(S.hero, u, "contain"); kbVars(S.hero, p); S.hero.style.animation = "none"; void S.hero.offsetWidth; S.hero.style.animation = ""; singles[sCur].root.classList.remove("on"); S.root.classList.add("on"); sCur = 1 - sCur; }
    async function showCollage() {
      const ps = SS.take(deck, ctx.cursor, ["any", "any", "any"]); if (!await t.load(ps.map(p => ctx.url(p, 1100)))) return false;
      singles.forEach(s => s.root.classList.remove("on")); big.textContent = "Photo memories"; sub.textContent = ""; collage.innerHTML = "";
      const W = ctx.W, H = ctx.H; const slots = [{ l: W * .08, t: H * .16, w: W * .42, h: H * .5 }, { l: W * .53, t: H * .1, w: W * .36, h: H * .42 }, { l: W * .5, t: H * .56, w: W * .4, h: H * .3 }];
      ps.forEach((p, i) => { const s = slots[i]; const tile = el("ss-mtile", collage); tile.style.left = s.l + "px"; tile.style.top = s.t + "px"; tile.style.width = s.w + "px"; tile.style.height = s.h + "px"; tile.style.transitionDelay = (i * .15) + "s"; const im = el("ss-img breathe", tile); bg(im, ctx.url(p, 1100)); faceBg(im, p); im.style.animationDelay = (-Math.random() * 8) + "s"; });
      t.frame(() => { collage.classList.add("on"); title.classList.add("on"); }); t.after(() => title.classList.remove("on"), 4500);
    }
    function step() { beat++; return beat % 6 === 0 ? showCollage() : showSingle(); }
    t.start(step); t.every(step, ctx.photoMs);
    return t.cleanup;
  }};

  // ---- 07 Panorama drift -----------------------------------------------------
  MODES.panorama = { name: "Panorama Drift", run(ctx, deck) {
    const t = makeTimers(ctx); const layers = [mk(), mk()]; layers.forEach(l => ctx.root.appendChild(l.root)); let cur = 0;
    function mk() { const root = el("ss-layer"); root.style.overflow = "hidden"; const pan = el("ss-pan", root); return { root, pan }; }
    async function step() {
      const p = SS.take(deck, ctx.cursor, [SS.counts(deck).pano ? "pano" : "landscape"])[0]; const u = ctx.url(p, 2600); if (!await t.load(u)) return false;
      // Size the pan element to the photo's OWN aspect vs the viewport so height fills with zero
      // vertical crop; only pan when that makes it wider than the screen (true panos). A plain
      // landscape gets ~100% width → shown whole, no crop.
      const L = layers[1 - cur]; const vp = ctx.W / ctx.H; const overW = Math.max(100, Math.round(aspect(p) / vp * 100)); L.pan.style.width = overW + "%"; bg(L.pan, u); L.pan.style.backgroundPosition = "left center";
      const travel = -(overW - 100) / overW * 100;
      L.pan.style.setProperty("--shift", travel + "%");
      L.pan.style.animation = "none"; void L.pan.offsetWidth;
      L.pan.style.animation = hasFace(p) ? "none" : "";
      L.pan.style.transform = hasFace(p) ? `translateX(${travel * SS.panStart(p.fx, overW)}%)` : "";
      layers[cur].root.classList.remove("on"); L.root.classList.add("on"); cur = 1 - cur;
    }
    t.start(step); t.every(step, ctx.photoMs);
    return t.cleanup;
  }};

  // ---- 08 Polaroid scatter ---------------------------------------------------
  MODES.polaroid = { name: "Polaroid Scatter", run(ctx, deck) {
    // MAX clamps to the pool so the table can never demand more distinct photos than exist;
    // dropping the oldest print always frees a seed before the next pick.
    const t = makeTimers(ctx); const table = el("ss-table", ctx.root); let live = []; const MAX = SS.slots(deck, 6);
    // The legacy input has no verified per-photo caption. Never invent a date, place or event.
    // Uniform-random placement clumps (independent points cluster and leave dead zones), so
    // scatter over a virtual grid instead: one print per cell, jittered inside it. Every print
    // then lands in its own region of the screen — guaranteed spread, still messy-looking.
    const COLS = Math.ceil(Math.sqrt(MAX * ctx.W / ctx.H)), ROWS = Math.ceil(MAX / COLS);
    const cellW = ctx.W / COLS, cellH = ctx.H / ROWS;
    let freeCells = window.shuffled(Array.from({ length: COLS * ROWS }, (_, i) => i)).slice(0, MAX);
    function place(pl, pw, ph, cell) {
      const cx = (cell % COLS) * cellW, cy = Math.floor(cell / COLS) * cellH;
      // Jitter within the cell; clamp so the print (incl. ~110px chin) stays on screen.
      const M = 16, footH = ph + 110;
      const x = Math.min(Math.max(cx + Math.random() * Math.max(1, cellW - pw), M), ctx.W - pw - M);
      const y = Math.min(Math.max(cy + Math.random() * Math.max(1, cellH - footH), M), ctx.H - footH - M);
      pl.style.left = x + "px"; pl.style.top = y + "px";
    }
    async function drop(delay = 0) {
      const excluded = live.length >= MAX ? live.slice(1) : live;
      const p = SS.take(deck, ctx.cursor, ["any"], excluded.map(x => x.photo.seed))[0];
      if (!await t.load(ctx.url(p, 1400))) return false;
      if (live.length >= MAX) { const old = live.shift(); freeCells.push(old.cell); old.el.style.opacity = "0"; old.el.style.transform += " translateY(-30px)"; t.after(() => old.el.remove(), 1000); }
      if (!freeCells.length) return;
      const cell = freeCells.splice(Math.floor(Math.random() * freeCells.length), 1)[0];
      // Exclude prints still on the table so a photo never appears twice at once.
      const pl = el("ss-polaroid"); const a = aspect(p);
      // Big prints (2x the original 300/240) — the table is a 2160px wall display.
      const pw = a >= 1 ? 600 : 480; const ph = Math.round(pw / a);
      const inner = el("ss-ph", pl); inner.style.width = pw + "px"; inner.style.height = ph + "px"; bg(inner, ctx.url(p, 1400));
      table.appendChild(pl); pl.style.transitionDelay = delay + "ms";
      place(pl, pw, ph, cell);
      const rot = (Math.random() * 2 - 1) * 11;
      pl.style.transform = `rotate(${rot}deg) scale(0.86)`;
      t.frame(() => { pl.classList.add("on"); pl.style.transform = `rotate(${rot}deg) scale(1)`; });
      live.push({ el: pl, photo: p, cell });
    }
    t.start(async () => { for (let i = 0; i < MAX; i++) { if (await drop(i * 450) === false) return false; } });
    t.every(() => drop(), ctx.microMs);
    return t.cleanup;
  }};

  // ---- 09 Mosaic wall (BSP, choreographed) -----------------------------------
  // The layout is fixed for the mode's whole run — tiles never move or reshape
  // (rearranging read as restless). Motion comes from within the frames: per-tile
  // Ken Burns / pan-through, a roaming spotlight (filter/transform only), and
  // photo crossfades — including exchanging two tiles' photos in place when the
  // whole library is already on the wall.
  MODES.mosaic = { name: "Mosaic Wall", run(ctx, deck) {
    const t = makeTimers(ctx); const wall = el("ss-wall spotlit", ctx.root); let cells = [];
    function bsp(rect, n) { if (n <= 1) return [rect]; const k = Math.max(1, Math.round(n * (0.42 + Math.random() * 0.16))); const horiz = rect.w >= rect.h; const ratio = k / n; if (horiz) { const w1 = Math.round(rect.w * ratio); return bsp({ x: rect.x, y: rect.y, w: w1, h: rect.h }, k).concat(bsp({ x: rect.x + w1, y: rect.y, w: rect.w - w1, h: rect.h }, n - k)); } const h1 = Math.round(rect.h * ratio); return bsp({ x: rect.x, y: rect.y, w: rect.w, h: h1 }, k).concat(bsp({ x: rect.x, y: rect.y + h1, w: rect.w, h: rect.h - h1 }, n - k)); }
    // A photo whose shape fights its cell (portrait in a wide frame) would lose most of
    // itself to the cover crop, so it pans through its full extent instead of Ken Burns.
    // A cached point cannot prove a safe motion path: keep that focus still. Cover crops
    // anchor on it; photos without a point retain the existing pan presentation.
    function mkImg(p, rect) {
      const plan = SS.fitPlan(aspect(p), rect.w / rect.h);
      if (plan.mode !== "cover") {
        const img = el(plan.mode === "panV" ? "ss-img panv" : "ss-img panh");
        bg(img, ctx.url(p, 1200));
        if (plan.mode === "panV") { img.style.height = plan.size + "%"; img.style.setProperty("--travel", -(plan.size - 100) / plan.size * 100 + "%"); }
        else { img.style.width = plan.size + "%"; img.style.setProperty("--travel", -(plan.size - 100) / plan.size * 100 + "%"); }
        // Follow the configured hold plus dissolve, including long slideshow intervals.
        const dur = ctx.motionMs / 1000;
        img.style.setProperty("--d", dur + "s");
        // Place a known focus in the visible window and do not tour away from it.
        const start = SS.panStart(plan.mode === "panV" ? p.fy : p.fx, plan.size);
        if (hasFace(p)) {
          img.style.animation = "none";
          img.style.transform = `${plan.mode === "panV" ? "translateY" : "translateX"}(${-((plan.size - 100) / plan.size * 100) * start}%)`;
        }
        return img;
      }
      const img = el("ss-img kb"); bg(img, ctx.url(p, 1200)); if (Math.random() < 0.4) img.classList.remove("kb"); img.style.setProperty("--d", (12 + Math.random() * 6) + "s"); img.style.setProperty("--dx", ((Math.random() * 2 - 1) * 2.5).toFixed(1) + "%"); img.style.setProperty("--dy", ((Math.random() * 2 - 1) * 2.5).toFixed(1) + "%"); img.style.animationDelay = (-Math.random() * 10) + "s";
      faceBg(img, p);
      return img;
    }
    function wantFor(cellA) { return cellA < 0.85 ? "portrait" : cellA > 1.8 ? "pano" : "landscape"; }
    // Every pick excludes what's already on the wall, so no tile duplicates another.
    function onWall() { return cells.map(x => x.photo && x.photo.seed).filter(Boolean); }
    function setRect(cell, r) { cell.rect = r; cell.c.style.left = r.x + "px"; cell.c.style.top = r.y + "px"; cell.c.style.width = r.w + "px"; cell.c.style.height = r.h + "px"; }
    // Replace a cell's image (crossfade in place — the cell itself never moves).
    function setImg(cell, p) { const img = mkImg(p, cell.rect); cell.c.appendChild(img); const old = cell.img; t.frame(() => { if (old) old.style.opacity = "0"; }); t.after(() => { if (old) old.remove(); }, 1200); cell.img = img; cell.photo = p; }
    async function build() {
      const selected = [];
      const slots = bsp({ x: 0, y: 0, w: ctx.W, h: ctx.H }, SS.slots(deck, 9)).map(rect => {
        const photo = SS.take(deck, ctx.cursor, [wantFor(rect.w / rect.h)], selected)[0];
        selected.push(photo.seed); return { rect, photo };
      });
      if (!await t.load(slots.map(s => ctx.url(s.photo, 1200)))) return false;
      slots.forEach(({ rect, photo }) => {
        const c = el("ss-mcell", wall), cell = { c, img: mkImg(photo, rect), photo, rect };
        setRect(cell, rect); c.appendChild(cell.img); cells.push(cell);
      });
      spotlight();
    }
    // Refresh one tile with an unseen photo; when the whole library is already on the
    // wall, exchange two same-appetite tiles' photos instead (crossfades, no movement).
    async function swap() {
      if (!cells.length) return;
      if (SS.unused(deck, onWall()).length) { const c = cells[Math.floor(Math.random() * cells.length)]; const p = SS.take(deck, ctx.cursor, [wantFor(c.rect.w / c.rect.h)], onWall())[0]; if (!await t.load(ctx.url(p, 1200))) return false; setImg(c, p); return; }
      const groups = {};
      cells.forEach(c => { const k = wantFor(c.rect.w / c.rect.h); (groups[k] = groups[k] || []).push(c); });
      const eligible = Object.values(groups).filter(g => g.length >= 2);
      if (!eligible.length) return;
      const [a, b] = window.shuffled(eligible[Math.floor(Math.random() * eligible.length)]).slice(0, 2);
      const pa = a.photo; if (!await t.load([ctx.url(pa, 1200), ctx.url(b.photo, 1200)])) return false; setImg(a, b.photo); setImg(b, pa);
    }
    // One tile gently lifts while the rest dim a touch; the focus wanders per beat.
    function spotlight() { const i = Math.floor(Math.random() * cells.length); cells.forEach((c, k) => c.c.classList.toggle("spot", k === i)); }
    t.start(build);
    // The spotlight wanders every beat; photos change only every fourth beat (~20s) —
    // anything faster reads as restless on a wall display.
    let beat = 0;
    t.every(spotlight, ctx.ambientMs); t.every(swap, ctx.microMs);
    return t.cleanup;
  }};

  // ---- 10 Filmstrip coverflow ------------------------------------------------
  MODES.filmstrip = { name: "Filmstrip Coverflow", run(ctx, deck) {
    const t = makeTimers(ctx); const bdA = el("ss-bd", ctx.root), bdB = el("ss-bd", ctx.root); bdB.style.opacity = "0"; const track = el("ss-track", ctx.root);
    const items = []; const set = SS.take(deck, ctx.cursor, new Array(SS.slots(deck, 12)).fill("any"));
    set.forEach(p => { const s = el("ss-slide", track); const h = ctx.H * 0.62, w = h * aspect(p); s.style.width = w + "px"; const im = el("ss-img", s); im.style.width = w + "px"; im.style.height = "100%"; bg(im, ctx.url(p, 1400)); items.push({ el: s, w, photo: p }); });
    let center = 0, top = false;
    async function go(i) { const u = ctx.url(items[i].photo, 1000); if (!await t.load(u)) return false; items.forEach((s, k) => s.el.classList.toggle("center", k === i)); let off = 0; for (let k = 0; k < i; k++) off += items[k].w + 60; off += items[i].w / 2; track.style.transform = `translateX(${-off}px)`; const b = top ? bdA : bdB, o = top ? bdB : bdA; bg(b, u); b.style.opacity = "1"; o.style.opacity = "0"; top = !top; center = i; }
    function loop() { return go((center + 1) % items.length); }
    t.start(async () => { if (!await t.load(set.map(p => ctx.url(p, 1400)))) return false; return go(0); });
    t.every(loop, ctx.microMs);
    t.cleanup.next = () => t.navigate(() => go((center + 1) % items.length));
    t.cleanup.previous = () => t.navigate(() => go((center + items.length - 1) % items.length));
    return t.cleanup;
  }};

  // ---- 11 Cinema letterbox ---------------------------------------------------
  MODES.cinema = { name: "Cinema Letterbox", run(ctx, deck) {
    const t = makeTimers(ctx); const frame = el("ss-scope", ctx.root); const barT = el("ss-bar top", ctx.root), barB = el("ss-bar bot", ctx.root); const grain = el("ss-grain", ctx.root);
    const layers = [mk(), mk()]; layers.forEach(l => frame.appendChild(l.root)); let cur = 0;
    function mk() { const root = el("ss-layer"); const img = el("ss-img", root); return { root, img }; }
    async function step() { const c = SS.counts(deck); const p = SS.take(deck, ctx.cursor, [c.pano ? "pano" : "landscape"])[0]; const u = ctx.url(p, 2400); if (!await t.load(u)) return false; const L = layers[1 - cur]; const wide = aspect(p) > 2.2; bg(L.img, u, wide ? "cover" : "contain"); if (wide && !hasFace(p)) { kbVars(L.img, p); L.img.style.animation = "none"; void L.img.offsetWidth; L.img.style.animation = ""; } else { faceBg(L.img, p); L.img.style.animation = "none"; } layers[cur].root.classList.remove("on"); L.root.classList.add("on"); cur = 1 - cur; }
    t.start(step); t.every(step, ctx.photoMs);
    return t.cleanup;
  }};

  // ---- 12 Hero + sidebar -----------------------------------------------------
  MODES.heroSidebar = { name: "Hero + Sidebar", run(ctx, deck) {
    const t = makeTimers(ctx); const stage = el("ss-hs", ctx.root); const heroCol = el("ss-col", stage); heroCol.style.flex = "1.9"; const heroCell = el("ss-cell", heroCol); const side = el("ss-col", stage); side.style.flex = "1";
    // Each pane shows the WHOLE photo (contain) over a soft blurred fill of itself — never a
    // cover-crop, so a face is never clipped (reuses the .ss-cell.contain styling). Panes crossfade.
    async function fill(cell, p, le) {
      const fillUrl = ctx.url(p, 900), imageUrl = ctx.url(p, le || 1400);
      if (!await t.load([fillUrl, imageUrl])) return false;
      cell.classList.add("contain");
      const wrap = el("ss-pane", cell);
      bg(el("ss-fill", wrap), fillUrl);
      bg(el("ss-img", wrap), imageUrl, "contain");
      const old = cell._wrap;
      t.frame(() => { if (old) old.style.opacity = "0"; });
      t.after(() => { if (old) old.remove(); }, 1100);
      cell._wrap = wrap;
    }
    // Track what's on screen (hero + both side cells) so no pane duplicates another.
    let sideCells = []; const shown = { hero: null, sides: [null, null] };
    function seeds() { return [shown.hero, ...shown.sides].filter(Boolean).map(p => p.seed); }
    t.start(async () => {
      const h0 = SS.take(deck, ctx.cursor, ["landscape"], seeds())[0]; shown.hero = h0;
      if (await fill(heroCell, h0, 1800) === false) return false;
      for (let i = 0; i < Math.min(2, SS.slots(deck, 3) - 1); i++) {
        const c = el("ss-cell", side); sideCells.push(c);
        const p = SS.take(deck, ctx.cursor, ["portrait"], seeds())[0]; shown.sides[i] = p;
        if (await fill(c, p, 1100) === false) return false;
      }
    });
    let turn = 0; t.every(async () => { if (!SS.unused(deck, seeds()).length) return; turn++; if (turn % 3 === 0 || !sideCells.length) { const p = SS.take(deck, ctx.cursor, ["landscape"], seeds())[0]; if (await fill(heroCell, p, 1800) === false) return false; shown.hero = p; } else { const i = turn % sideCells.length; const p = SS.take(deck, ctx.cursor, ["portrait"], seeds())[0]; if (await fill(sideCells[i], p, 1100) === false) return false; shown.sides[i] = p; } }, ctx.microMs);
    return t.cleanup;
  }};

  SS.MODES = MODES;
})();
