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
  function preload(url) { return new Promise((r) => { const im = new Image(); im.decoding = "async"; im.onload = im.onerror = () => r(); im.src = url; }); }
  function kbVars(node, photo) {
    const zoomIn = Math.random() > 0.4; const s0 = zoomIn ? 1.04 : 1.15, s1 = zoomIn ? 1.15 : 1.04;
    const s = Math.max(s0, s1), bound = ((s - 1) / 2) * 100 * 0.82;
    const wide = aspect(photo) > 1.2; const bx = wide ? bound : bound * 0.5, by = wide ? bound * 0.5 : bound;
    const rx = () => (Math.random() * 2 - 1) * bx, ry = () => (Math.random() * 2 - 1) * by;
    node.style.setProperty("--s0", s0); node.style.setProperty("--s1", s1);
    node.style.setProperty("--x0", rx() + "%"); node.style.setProperty("--y0", ry() + "%");
    node.style.setProperty("--x1", rx() + "%"); node.style.setProperty("--y1", ry() + "%");
  }
  // A repeating interval that's auto-cleared by the returned cleanup.
  function makeTimers() { const ids = []; return { every: (fn, ms) => { const id = setInterval(fn, ms); ids.push(id); return id; }, after: (fn, ms) => { const id = setTimeout(fn, ms); ids.push(id); return id; }, clear: () => ids.forEach(clearInterval) }; }

  const MODES = {};

  // ---- 01 Ken Burns (single, blur-fill, keeps moving through fade) ----------
  MODES.kenBurns = { name: "Cinematic Ken Burns", run(ctx, deck) {
    const t = makeTimers();
    const layers = [mk(), mk()]; layers.forEach(l => ctx.root.appendChild(l.root));
    function mk() { const root = el("ss-layer"); const fill = el("ss-fill", root); const hero = el("ss-hero", root); return { root, fill, hero }; }
    let cur = 0;
    async function step() {
      const p = SS.take(deck, ctx.cursor, ["any"])[0];
      const u = ctx.url(p, 2200); await preload(u);
      const L = layers[1 - cur];
      bg(L.fill, u, "cover"); bg(L.hero, u, "contain"); kbVars(L.hero, p);
      L.hero.style.animation = "none"; void L.hero.offsetWidth; L.hero.style.animation = "";
      layers[cur].root.classList.remove("on"); L.root.classList.add("on"); cur = 1 - cur;
    }
    step(); t.every(step, 9000);
    return () => t.clear();
  }};

  // ---- 02 Diptych (orientation-aware pairing) -------------------------------
  MODES.diptych = { name: "Orientation Diptych", run(ctx, deck) {
    const t = makeTimers();
    const frames = [el("ss-frame"), el("ss-frame")]; frames.forEach(f => ctx.root.appendChild(f));
    let cur = 0;
    function coverCell(p) { const c = el("ss-cell"); const im = el("ss-img drift", c); bg(im, ctx.url(p, 1400)); im.style.animationDelay = (-Math.random() * 8) + "s"; return c; }
    function containCell(p) { const c = el("ss-cell contain"); const fill = el("ss-fill", c); bg(fill, ctx.url(p, 1400)); const im = el("ss-img", c); bg(im, ctx.url(p, 1800), "contain"); return c; }
    async function step() {
      const f = frames[1 - cur]; f.innerHTML = "";
      const c = SS.counts(deck); const roll = Math.random();
      let urls = [];
      if (roll < 0.28 && c.pano) { const p = SS.take(deck, ctx.cursor, ["pano"])[0]; f.appendChild(containCell(p)); urls = [ctx.url(p, 1800)]; }
      else if (roll < 0.6 && c.landscape) { const p = SS.take(deck, ctx.cursor, ["landscape"])[0]; f.appendChild(containCell(p)); urls = [ctx.url(p, 1800)]; }
      else { const ps = SS.take(deck, ctx.cursor, ["portrait", "portrait"]); ps.forEach(p => f.appendChild(coverCell(p))); urls = ps.map(p => ctx.url(p, 1400)); }
      await Promise.all(urls.map(preload));
      frames[cur].classList.remove("on"); f.classList.add("on"); cur = 1 - cur;
    }
    step(); t.every(step, 9000);
    return () => t.clear();
  }};

  // ---- 03 Framed print (art mode) -------------------------------------------
  MODES.framed = { name: "Framed Print", run(ctx, deck) {
    const t = makeTimers();
    const room = el("ss-room", ctx.root);
    const arts = [mk(), mk()]; arts.forEach(a => ctx.root.appendChild(a.root));
    let cur = 0;
    function mk() { const root = el("ss-art"); const edge = el("ss-frameEdge", root); const mat = el("ss-mat", edge); const bevel = el("ss-bevel", mat); const photo = el("ss-print", bevel); return { root, photo }; }
    function sizeFor(p) { const maxW = ctx.W * 0.6, maxH = ctx.H * 0.72; const ar = aspect(p); let w = maxW, h = w / ar; if (h > maxH) { h = maxH; w = h * ar; } return { w: Math.round(w), h: Math.round(h) }; }
    async function step() {
      const p = SS.take(deck, ctx.cursor, ["any"])[0]; const u = ctx.url(p, 1800); await preload(u);
      const A = arts[1 - cur]; const { w, h } = sizeFor(p);
      A.photo.style.width = w + "px"; A.photo.style.height = h + "px"; bg(A.photo, u);
      A.photo.style.animationDelay = (-Math.random() * 10) + "s";
      arts[cur].root.classList.remove("on"); A.root.classList.add("on"); cur = 1 - cur;
    }
    step(); t.every(step, 14000);
    return () => t.clear();
  }};

  // ---- 04 Living gallery wall (justified rows + swaps) ----------------------
  MODES.galleryWall = { name: "Living Gallery Wall", run(ctx, deck) {
    const t = makeTimers(); const wall = el("ss-wall", ctx.root); let cells = [];
    function buildImg(p) { const img = el("ss-img kb"); bg(img, ctx.url(p, 1200)); img.style.setProperty("--d", (14 + Math.random() * 8) + "s"); img.style.setProperty("--dx", ((Math.random() * 2 - 1) * 2).toFixed(1) + "%"); img.style.setProperty("--dy", ((Math.random() * 2 - 1) * 2).toFixed(1) + "%"); img.style.animationDelay = (-Math.random() * 10) + "s"; return img; }
    function layout() {
      const W = ctx.W, H = ctx.H, pad = 16, gutter = 12; const targetH = H > 1000 ? 300 : 240;
      const rows = Math.max(2, Math.round((H - pad * 2 + gutter) / (targetH + gutter)));
      const pick = []; const seen = new Set(); let g = 0;
      while (pick.length < 18 && g < deck.length * 2) { const p = deck[ctx.cursor.i++ % deck.length]; g++; if (!seen.has(p.seed)) { seen.add(p.seed); pick.push(p); } }
      const res = window.justifiedLayout(pick, W - pad * 2, { targetRowHeight: targetH, gutter, maxRows: rows });
      const sy = (H - pad * 2) / Math.max(res.height, 1);
      return res.cells.map(c => ({ ...c, left: c.left + pad, top: c.top * sy + pad, height: c.height * sy }));
    }
    function render(target) {
      while (cells.length < target.length) { const e = el("ss-gcell", wall); cells.push({ el: e, img: null, photo: null }); }
      while (cells.length > target.length) { cells.pop().el.remove(); }
      target.forEach((slot, i) => { const c = cells[i]; c.el.style.left = slot.left + "px"; c.el.style.top = slot.top + "px"; c.el.style.width = slot.width + "px"; c.el.style.height = slot.height + "px";
        if (c.photo !== slot.photo) { const img = buildImg(slot.photo); c.el.appendChild(img); const old = c.img; requestAnimationFrame(() => { if (old) old.style.opacity = "0"; }); t.after(() => { if (old) old.remove(); }, 1200); c.img = img; c.photo = slot.photo; } });
    }
    function rebuild() { render(layout()); }
    function swap() { if (!cells.length) return; const c = cells[Math.floor(Math.random() * cells.length)]; const onWall = cells.map(x => x.photo && x.photo.seed).filter(Boolean); let p = null; const wallSet = new Set(onWall); for (let k = 0; k < deck.length; k++) { const cand = deck[(ctx.cursor.i + k) % deck.length]; if (!wallSet.has(cand.seed) && SS.bucket(cand) === SS.bucket(c.photo)) { p = cand; ctx.cursor.i += k + 1; break; } } if (!p) p = SS.take(deck, ctx.cursor, ["any"], onWall)[0]; const img = buildImg(p); c.el.appendChild(img); const old = c.img; requestAnimationFrame(() => { if (old) old.style.opacity = "0"; }); t.after(() => { if (old) old.remove(); }, 1200); c.img = img; c.photo = p; }
    rebuild(); t.every(swap, 4000);
    return () => t.clear();
  }};

  // ---- 05 Cinematic push -----------------------------------------------------
  MODES.push = { name: "Cinematic Push", run(ctx, deck) {
    const t = makeTimers(); const layers = [mk(), mk()]; layers.forEach(l => ctx.root.appendChild(l.root)); let cur = 0, first = true;
    function mk() { const root = el("ss-layer"); const fill = el("ss-fill", root); const hero = el("ss-hero", root); return { root, fill, hero }; }
    async function step() {
      const p = SS.take(deck, ctx.cursor, ["any"])[0]; const u = ctx.url(p, 2200); await preload(u);
      const inL = layers[1 - cur], outL = layers[cur]; bg(inL.fill, u); bg(inL.hero, u, "contain");
      inL.root.className = "ss-layer push-enter"; if (!first) outL.root.className = "ss-layer push-leave";
      t.after(() => { inL.root.className = "ss-layer"; inL.root.style.opacity = "1"; inL.hero.style.animation = "drift 12s linear forwards"; }, 1150);
      cur = 1 - cur; first = false;
    }
    step(); t.every(step, 8000);
    return () => t.clear();
  }};

  // ---- 06 Memories (single + occasional titled collage beat) ----------------
  MODES.memories = { name: "Memories", run(ctx, deck) {
    const t = makeTimers(); const single = mk(); ctx.root.appendChild(single.root);
    const collage = el("ss-collage", ctx.root); const title = el("ss-mtitle", ctx.root); const big = el("big", title); const sub = el("sub", title);
    let beat = 0; const TITLES = [["Over the Years", "a look back"], ["This Weekend", "recently added"], ["On Our Travels", "places you've been"], ["Golden Hours", "favorites"]];
    function mk() { const root = el("ss-single"); const fill = el("ss-fill", root); const hero = el("ss-hero", root); return { root, fill, hero }; }
    async function showSingle() { collage.classList.remove("on"); const p = SS.take(deck, ctx.cursor, ["any"])[0]; const u = ctx.url(p, 2200); await preload(u); bg(single.fill, u); bg(single.hero, u, "contain"); kbVars(single.hero, p); single.hero.style.animation = "none"; void single.hero.offsetWidth; single.hero.style.animation = ""; single.root.classList.add("on"); }
    async function showCollage() {
      single.root.classList.remove("on"); const [tt, ss] = TITLES[Math.floor(Math.random() * TITLES.length)]; big.textContent = tt; sub.textContent = ss;
      const ps = SS.take(deck, ctx.cursor, ["any", "any", "any"]); await Promise.all(ps.map(p => preload(ctx.url(p, 1100)))); collage.innerHTML = "";
      const W = ctx.W, H = ctx.H; const slots = [{ l: W * .08, t: H * .16, w: W * .42, h: H * .5 }, { l: W * .53, t: H * .1, w: W * .36, h: H * .42 }, { l: W * .5, t: H * .56, w: W * .4, h: H * .3 }];
      ps.forEach((p, i) => { const s = slots[i]; const tile = el("ss-mtile", collage); tile.style.left = s.l + "px"; tile.style.top = s.t + "px"; tile.style.width = s.w + "px"; tile.style.height = s.h + "px"; tile.style.transitionDelay = (i * .15) + "s"; const im = el("ss-img breathe", tile); bg(im, ctx.url(p, 1100)); im.style.animationDelay = (-Math.random() * 8) + "s"; });
      requestAnimationFrame(() => { collage.classList.add("on"); title.classList.add("on"); }); t.after(() => title.classList.remove("on"), 4500);
    }
    function step() { beat++; if (beat % 6 === 0) showCollage(); else showSingle(); }
    step(); t.every(step, 8800);
    return () => t.clear();
  }};

  // ---- 07 Panorama drift -----------------------------------------------------
  MODES.panorama = { name: "Panorama Drift", run(ctx, deck) {
    const t = makeTimers(); const layers = [mk(), mk()]; layers.forEach(l => ctx.root.appendChild(l.root)); let cur = 0;
    function mk() { const root = el("ss-layer"); root.style.overflow = "hidden"; const pan = el("ss-pan", root); return { root, pan }; }
    async function step() {
      const p = SS.take(deck, ctx.cursor, [SS.counts(deck).pano ? "pano" : "landscape"])[0]; const u = ctx.url(p, 2600); await preload(u);
      const L = layers[1 - cur]; const overW = aspect(p) > 2.2 ? 165 : 128; L.pan.style.width = overW + "%"; bg(L.pan, u); L.pan.style.backgroundPosition = "left center";
      L.pan.style.setProperty("--shift", -(overW - 100) + "%"); L.pan.style.setProperty("--dur", (aspect(p) > 2.2 ? 30 : 22) + "s");
      L.pan.style.animation = "none"; void L.pan.offsetWidth; L.pan.style.animation = "";
      layers[cur].root.classList.remove("on"); L.root.classList.add("on"); cur = 1 - cur;
    }
    step(); t.every(step, 13000);
    return () => t.clear();
  }};

  // ---- 08 Polaroid scatter ---------------------------------------------------
  MODES.polaroid = { name: "Polaroid Scatter", run(ctx, deck) {
    const t = makeTimers(); const table = el("ss-table", ctx.root); let live = []; const MAX = 6;
    const CAPS = ["good times", "summer '24", "us", "♥", "don't forget", "golden hour", "home", "the trip"];
    function drop() {
      if (live.length >= MAX) { const old = live.shift(); old.el.style.opacity = "0"; old.el.style.transform += " translateY(-30px)"; t.after(() => old.el.remove(), 1000); }
      // Exclude prints still on the table so a photo never appears twice at once.
      const p = SS.take(deck, ctx.cursor, ["any"], live.map(x => x.photo.seed))[0];
      const pl = el("ss-polaroid"); const a = aspect(p);
      // Big prints (2x the original 300/240) — the table is a 2160px wall display.
      const pw = a >= 1 ? 600 : 480; const ph = Math.round(pw / a);
      const inner = el("ss-ph", pl); inner.style.width = pw + "px"; inner.style.height = ph + "px"; bg(inner, ctx.url(p, 1400));
      const cap = el("ss-cap", pl); cap.textContent = CAPS[Math.floor(Math.random() * CAPS.length)]; table.appendChild(pl);
      // Scatter across the WHOLE screen: uniform over the area the print can occupy (small 16px
      // breathing margin; ~110px reserved under the photo for the polaroid chin + caption).
      const W = ctx.W, H = ctx.H, M = 16;
      const x = M + Math.random() * Math.max(1, W - pw - M * 2);
      const y = M + Math.random() * Math.max(1, H - ph - 110 - M * 2);
      const rot = (Math.random() * 2 - 1) * 11;
      pl.style.left = x + "px"; pl.style.top = y + "px"; pl.style.transform = `rotate(${rot}deg) scale(0.86)`;
      requestAnimationFrame(() => { pl.classList.add("on"); pl.style.transform = `rotate(${rot}deg) scale(1)`; });
      live.push({ el: pl, photo: p });
    }
    for (let i = 0; i < 4; i++) t.after(drop, i * 450); t.every(drop, 3500);
    return () => t.clear();
  }};

  // ---- 09 Mosaic wall (BSP) --------------------------------------------------
  MODES.mosaic = { name: "Mosaic Wall", run(ctx, deck) {
    const t = makeTimers(); const wall = el("ss-wall", ctx.root); let cells = [];
    function bsp(rect, n) { if (n <= 1) return [rect]; const k = Math.max(1, Math.round(n * (0.42 + Math.random() * 0.16))); const horiz = rect.w >= rect.h; const ratio = k / n; if (horiz) { const w1 = Math.round(rect.w * ratio); return bsp({ x: rect.x, y: rect.y, w: w1, h: rect.h }, k).concat(bsp({ x: rect.x + w1, y: rect.y, w: rect.w - w1, h: rect.h }, n - k)); } const h1 = Math.round(rect.h * ratio); return bsp({ x: rect.x, y: rect.y, w: rect.w, h: h1 }, k).concat(bsp({ x: rect.x, y: rect.y + h1, w: rect.w, h: rect.h - h1 }, n - k)); }
    function mkImg(p) { const img = el("ss-img kb"); bg(img, ctx.url(p, 1200)); img.style.setProperty("--d", (16 + Math.random() * 8) + "s"); img.style.setProperty("--dx", ((Math.random() * 2 - 1) * 1.5).toFixed(1) + "%"); img.style.setProperty("--dy", ((Math.random() * 2 - 1) * 1.5).toFixed(1) + "%"); img.style.animationDelay = (-Math.random() * 10) + "s"; return img; }
    function wantFor(cellA) { return cellA < 0.85 ? "portrait" : cellA > 1.8 ? "pano" : "landscape"; }
    // Every pick excludes what's already on the wall, so no tile duplicates another.
    function onWall() { return cells.map(x => x.photo && x.photo.seed).filter(Boolean); }
    function build() { wall.innerHTML = ""; cells = []; bsp({ x: 0, y: 0, w: ctx.W, h: ctx.H }, 9).forEach(r => { const c = el("ss-mcell", wall); c.style.left = r.x + "px"; c.style.top = r.y + "px"; c.style.width = r.w + "px"; c.style.height = r.h + "px"; const p = SS.take(deck, ctx.cursor, [wantFor(r.w / r.h)], onWall())[0]; const img = mkImg(p); c.appendChild(img); cells.push({ c, img, photo: p }); }); }
    function swap() { if (!cells.length) return; const c = cells[Math.floor(Math.random() * cells.length)]; const p = SS.take(deck, ctx.cursor, [wantFor(c.c.offsetWidth / c.c.offsetHeight)], onWall())[0]; const img = mkImg(p); c.c.appendChild(img); const old = c.img; requestAnimationFrame(() => { if (old) old.style.opacity = "0"; }); t.after(() => { if (old) old.remove(); }, 1200); c.img = img; c.photo = p; }
    build(); t.every(swap, 3500);
    return () => t.clear();
  }};

  // ---- 10 Filmstrip coverflow ------------------------------------------------
  MODES.filmstrip = { name: "Filmstrip Coverflow", run(ctx, deck) {
    const t = makeTimers(); const bdA = el("ss-bd", ctx.root), bdB = el("ss-bd", ctx.root); bdB.style.opacity = "0"; const track = el("ss-track", ctx.root);
    const items = []; const set = SS.take(deck, ctx.cursor, new Array(12).fill("any"));
    set.forEach(p => { const s = el("ss-slide", track); const h = ctx.H * 0.62, w = h * aspect(p); s.style.width = w + "px"; const im = el("ss-img", s); im.style.width = w + "px"; im.style.height = "100%"; bg(im, ctx.url(p, 1400)); items.push({ el: s, w, photo: p }); });
    let center = 0, top = false;
    function go(i) { items.forEach((s, k) => s.el.classList.toggle("center", k === i)); let off = 0; for (let k = 0; k < i; k++) off += items[k].w + 60; off += items[i].w / 2; track.style.transform = `translateX(${-off}px)`; const u = ctx.url(items[i].photo, 1000); const b = top ? bdA : bdB, o = top ? bdB : bdA; bg(b, u); b.style.opacity = "1"; o.style.opacity = "0"; top = !top; }
    function loop() { center = (center + 1) % items.length; go(center); }
    bdA.style.opacity = "1"; go(0); t.every(loop, 5500);
    return () => t.clear();
  }};

  // ---- 11 Cinema letterbox ---------------------------------------------------
  MODES.cinema = { name: "Cinema Letterbox", run(ctx, deck) {
    const t = makeTimers(); const frame = el("ss-scope", ctx.root); const barT = el("ss-bar top", ctx.root), barB = el("ss-bar bot", ctx.root); const grain = el("ss-grain", ctx.root);
    const layers = [mk(), mk()]; layers.forEach(l => frame.appendChild(l.root)); let cur = 0;
    function mk() { const root = el("ss-layer"); const img = el("ss-img", root); return { root, img }; }
    async function step() { const c = SS.counts(deck); const p = SS.take(deck, ctx.cursor, [c.pano ? "pano" : "landscape"])[0]; const u = ctx.url(p, 2400); await preload(u); const L = layers[1 - cur]; bg(L.img, u); kbVars(L.img, p); L.img.style.animation = "none"; void L.img.offsetWidth; L.img.style.animation = ""; layers[cur].root.classList.remove("on"); L.root.classList.add("on"); cur = 1 - cur; }
    step(); t.every(step, 9000);
    return () => t.clear();
  }};

  // ---- 12 Hero + sidebar -----------------------------------------------------
  MODES.heroSidebar = { name: "Hero + Sidebar", run(ctx, deck) {
    const t = makeTimers(); const stage = el("ss-hs", ctx.root); const heroCol = el("ss-col", stage); heroCol.style.flex = "1.9"; const heroCell = el("ss-cell", heroCol); const side = el("ss-col", stage); side.style.flex = "1";
    function mkImg(p, le) { const img = el("ss-img kb"); bg(img, ctx.url(p, le || 1400)); img.style.setProperty("--d", (16 + Math.random() * 8) + "s"); img.style.setProperty("--dx", ((Math.random() * 2 - 1) * 1.5).toFixed(1) + "%"); img.style.setProperty("--dy", ((Math.random() * 2 - 1) * 1.5).toFixed(1) + "%"); img.style.animationDelay = (-Math.random() * 10) + "s"; return img; }
    function fill(cell, p, le) { const img = mkImg(p, le); cell.appendChild(img); const old = cell._img; requestAnimationFrame(() => { if (old) old.style.opacity = "0"; }); t.after(() => { if (old) old.remove(); }, 1100); cell._img = img; }
    // Track what's on screen (hero + both side cells) so no pane duplicates another.
    let sideCells = []; const shown = { hero: null, sides: [null, null] };
    function seeds() { return [shown.hero, ...shown.sides].filter(Boolean).map(p => p.seed); }
    const h0 = SS.take(deck, ctx.cursor, ["landscape"], seeds())[0]; shown.hero = h0; fill(heroCell, h0, 1800);
    for (let i = 0; i < 2; i++) { const c = el("ss-cell", side); sideCells.push(c); const p = SS.take(deck, ctx.cursor, ["portrait"], seeds())[0]; shown.sides[i] = p; fill(c, p, 1100); }
    let turn = 0; t.every(() => { turn++; if (turn % 3 === 0) { const p = SS.take(deck, ctx.cursor, ["landscape"], seeds())[0]; shown.hero = p; fill(heroCell, p, 1800); } else { const i = turn % sideCells.length; const p = SS.take(deck, ctx.cursor, ["portrait"], seeds())[0]; shown.sides[i] = p; fill(sideCells[i], p, 1100); } }, 4500);
    return () => t.clear();
  }};

  SS.MODES = MODES;
})();
