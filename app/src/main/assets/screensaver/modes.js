// Unified screensaver — mode registry + intelligent shuffle scheduler.
//
// Each mode is a self-contained module: { id, name, wants(pool)->bool, beatMs, run(ctx, pool) }.
// The scheduler picks, per beat, a mode whose orientation "appetite" the current photo pool can
// satisfy — this is the "knows which images fit which mode" intelligence. A single always-on
// engine drives whichever mode is active into a shared stage; modes clean up after themselves.
//
// This file defines the FRAMEWORK + appetites. The individual mode render functions live in
// mode-impls.js so this stays readable.

(function () {
  // Orientation buckets (shared with photos): portrait <0.8, square <=1.3, landscape <=2.2, pano >2.2
  function bucket(p) {
    const a = p.w / p.h;
    if (!(a > 0)) return "landscape"; // unmeasured (w/h 0 or NaN) → safe default, never 'pano'
    if (a < 0.8) return "portrait";
    if (a <= 1.3) return "square";
    if (a <= 2.2) return "landscape";
    return "pano";
  }
  function counts(pool) {
    const c = { portrait: 0, square: 0, landscape: 0, pano: 0 };
    pool.forEach((p) => c[bucket(p)]++);
    return c;
  }

  // A mode's appetite is expressed as a predicate over bucket counts. If it can't be satisfied by
  // the current pool, the scheduler skips it (so Diptych never runs without ≥2 portraits, etc.).
  const APPETITE = {
    kenBurns:    (c) => true,                                   // any single photo
    diptych:     (c) => c.portrait >= 2 || c.landscape + c.pano >= 1,
    framed:      (c) => true,
    galleryWall: (c) => c.portrait + c.square + c.landscape + c.pano >= 6,
    push:        (c) => true,
    memories:    (c) => c.portrait + c.square + c.landscape >= 3,
    panorama:    (c) => c.pano + c.landscape >= 1,               // wants wide material
    polaroid:    (c) => c.portrait + c.square + c.landscape + c.pano >= 4,
    mosaic:      (c) => c.portrait + c.square + c.landscape + c.pano >= 6,
    filmstrip:   (c) => c.portrait + c.square + c.landscape + c.pano >= 5,
    cinema:      (c) => c.landscape + c.pano >= 1,               // scope framing wants wide
    heroSidebar: (c) => (c.landscape + c.pano >= 1) && (c.portrait + c.square >= 2),
  };

  // How long each mode holds the screen before the scheduler advances (ms). Collage/gallery modes
  // hold longer (they have internal micro-motion); single-photo modes advance per photo.
  // How long each mode holds the screen in Shuffle before rotating. Single-photo modes are set so a
  // mode shows ~2-3 photos before switching (not one), so shuffle feels composed, not restless — the
  // research's "a wall display should feel calm" guidance. Collage/gallery modes have internal
  // micro-motion so they can hold longer.
  const BEAT = {
    kenBurns: 27000, diptych: 27000, framed: 28000, galleryWall: 30000, push: 24000,
    memories: 27000, panorama: 26000, polaroid: 24000, mosaic: 32000, filmstrip: 24000,
    cinema: 27000, heroSidebar: 26000,
  };

  const MODE_IDS = Object.keys(APPETITE);

  window.SS = window.SS || {};
  // Paper layouts use a bounded measured reservation search in CSS pixels. The bridge
  // publishes immutable window-pixel snapshots; never read Android views on its thread.
  window.SS.photoLayoutArea = function (ctx) {
    const W = Math.max(1, ctx.root.clientWidth || ctx.W), H = Math.max(1, ctx.root.clientHeight || ctx.H);
    let occupied = null;
    try {
      const host = window.Android;
      const raw = host && host.getPhotoLayoutBounds && host.getPhotoLayoutBounds();
      const data = typeof raw === "string" && raw.length <= 16384 ? JSON.parse(raw) : null;
      if (data && Number.isFinite(data.width) && data.width > 0 &&
          Number.isFinite(data.height) && data.height > 0 &&
          Array.isArray(data.occupied) && data.occupied.length <= 32 &&
          data.occupied.every(r => ["left", "top", "right", "bottom"].every(k => Number.isFinite(r[k])) &&
            r.left < r.right && r.top < r.bottom)) {
        occupied = data.occupied.map(r => ({
          x: Math.max(0, r.left * W / data.width), y: Math.max(0, r.top * H / data.height),
          right: Math.min(W, r.right * W / data.width), bottom: Math.min(H, r.bottom * H / data.height),
        }));
      }
    } catch (_) {}
    // Standalone clock / unknown initial native geometry: reserve a modest lower strip.
    let candidates = [{ x: 0, y: 0, w: W, h: occupied === null ? H - Math.min(H * .22, 180) : H }];
    for (const r of occupied || []) {
      if (r.x >= r.right || r.y >= r.bottom) continue;
      candidates = candidates.flatMap(a => {
        const right = a.x + a.w, bottom = a.y + a.h;
        if (a.x >= r.right || right <= r.x || a.y >= r.bottom || bottom <= r.y) return [a];
        return [
          { x: a.x, y: a.y, w: a.w, h: Math.min(bottom, r.y) - a.y },
          { x: a.x, y: Math.max(a.y, r.bottom), w: a.w, h: bottom - Math.max(a.y, r.bottom) },
          { x: a.x, y: a.y, w: Math.min(right, r.x) - a.x, h: a.h },
          { x: Math.max(a.x, r.right), y: a.y, w: right - Math.max(a.x, r.right), h: a.h },
        ].filter(a => a.w > 0 && a.h > 0);
      }).sort((a, b) => b.w * b.h - a.w * a.h).slice(0, 16);
    }
    const area = candidates[0] || { x: 0, y: 0, w: W, h: H };
    const pad = Math.min(Math.min(W, H) * .028, area.w * .1, area.h * .1);
    return { x: area.x + pad, y: area.y + pad, w: area.w - pad * 2, h: area.h - pad * 2 };
  };
  window.SS.photoLayoutRows = function (photos, width, height, rawGap) {
    const selected = photos.slice(0, 6);
    if (!selected.length) return [];
    const ratios = selected.map(p => {
      const a = p.w / p.h;
      return Number.isFinite(a) && a > 0 ? a : 1;
    });
    const gap = Math.min(rawGap, width / (selected.length * 2), height / 6);
    let best = null;
    // Six photos admit at most 32 partitions. Small libraries also allow single-photo rows.
    for (let mask = 0; mask < 1 << (selected.length - 1); mask++) {
      const rows = [[]];
      selected.forEach((_, i) => { rows[rows.length - 1].push(i); if (mask & (1 << i)) rows.push([]); });
      if (rows.length > 3) continue;
      const heights = rows.map(row => (width - (row.length - 1) * gap) /
        row.reduce((sum, i) => sum + ratios[i], 0));
      const total = heights.reduce((sum, h) => sum + h, 0) + (rows.length - 1) * gap;
      const scale = Math.min(1, height / total);
      const area = heights.reduce((sum, h, i) => sum + (width - (rows[i].length - 1) * gap) * h, 0);
      const score = area * scale * scale / (1 + .035 * (Math.max(...heights) / Math.min(...heights) - 1));
      if (!best || score > best.score) best = { rows, heights, total, scale, score };
    }
    const boxes = [];
    let y = (height - best.total * best.scale) / 2;
    best.rows.forEach((row, index) => {
      const h = best.heights[index] * best.scale;
      let x = (width - width * best.scale) / 2;
      row.forEach(i => {
        const w = h * ratios[i];
        boxes[i] = { x, y, w, h };
        x += w + gap * best.scale;
      });
      y += h + gap * best.scale;
    });
    return boxes;
  };
  // ---- Screen-aware layout (mock mini-screensaver-20261120) ----------------------------------
  // The layout class comes from the window size in CSS px (= dp here), never the device model,
  // and mirrors LayoutClass.kt: a short side under 1000 is compact. "L" (the Portal+) is every
  // shipped layout, unchanged; "CP" is the Portal Mini upright, "CL" the Mini turned.
  const COMPACT_SHORT_SIDE = 1000;
  window.SS.layoutClass = function (W, H) {
    if (!(W > 0 && H > 0) || Math.min(W, H) >= COMPACT_SHORT_SIDE) return "L";
    return H > W ? "CP" : "CL";
  };
  // Cinema's letterbox bars make no sense on a tall screen: upright it is hidden, and Panorama
  // drift (the best tall-screen treatment of wide material) stands in for it.
  window.SS.poolFor = function (cls, ids) {
    return cls === "CP" ? ids.filter((id) => id !== "cinema") : ids;
  };
  window.SS.modeFor = function (cls, id) {
    return cls === "CP" && id === "cinema" ? "panorama" : id;
  };
  // One photo on a compact screen: full-bleed when the cover crop keeps >= 80 % of it, a sweep
  // at full height for a panorama, otherwise whole over its own blurred backdrop in the zone
  // (raised clear of the clock upright). Mirrors ScreensaverLayout.single/wholeZone.
  window.SS.compactSingle = function (photoAspect, W, H, cls) {
    const a = Number.isFinite(photoAspect) && photoAspect > 0 ? photoAspect : 1;
    const va = W / H, keep = Math.min(a / va, va / a);
    if (keep >= 0.8) return { mode: "fill", rect: { x: 0, y: 0, w: W, h: H } };
    if (a > 2) { const h = H, w = h * a; return { mode: "sweep", rect: { x: 0, y: 0, w, h }, travel: W - w }; }
    const zone = cls === "CP" ? { x: 32, y: 40, w: W - 64, h: H - 330 } : { x: 64, y: 40, w: W - 128, h: H - 80 };
    const s = Math.min(zone.w / a, zone.h) > 0 ? Math.min(zone.w / a, zone.h) : 1;
    const w = s * a, h = s;
    return { mode: "whole", zone, rect: { x: zone.x + (zone.w - w) / 2, y: zone.y + (zone.h - h) / 2, w, h } };
  };
  // ---- One or two photos on the Portal+ ("L", mock screensaver-polish-20261121) ---------------
  // The visible window of a face-centred cover crop along the clipped axis (0..1), whether the
  // cached face box (padded) survives it, and the matching CSS background-position.
  const LARGE_KEEP = 0.8, LARGE_MARGIN = 0.035, FACE_PAD = 0.03, PAIR_GAP = 24;
  function validFace(p) {
    return !!p && Number.isFinite(p.fx) && Number.isFinite(p.fy) && p.fx >= 0 && p.fx <= 1 && p.fy >= 0 && p.fy <= 1;
  }
  function faceBox(p) {
    const b = p && p.fb;
    return Array.isArray(b) && b.length === 4 && b.every(v => Number.isFinite(v) && v >= 0 && v <= 1) &&
      b[0] < b[2] && b[1] < b[3] ? b : null;
  }
  window.SS.coverCrop = function (photo, W, H) {
    const raw = photo && photo.w / photo.h, a = Number.isFinite(raw) && raw > 0 ? raw : 1, va = W / H;
    const tall = a < va, visible = Math.min(1, tall ? a / va : va / a);
    const focal = validFace(photo) ? (tall ? photo.fy : photo.fx) : .5;
    const start = Math.min(1 - visible, Math.max(0, focal - visible / 2)), end = start + visible;
    const fb = faceBox(photo), lo = fb && (tall ? fb[1] : fb[0]), hi = fb && (tall ? fb[3] : fb[2]);
    const faces = !fb || visible >= 1 ||
      (lo >= (start > 0 ? start + FACE_PAD : 0) && hi <= (end < 1 ? end - FACE_PAD : 1));
    const pos = visible < 1 ? (start / (1 - visible) * 100).toFixed(1) + "%" : "50%";
    return { visible, faces, position: tall ? "50% " + pos : pos + " 50%" };
  };
  // A single photo fills the screen when the crop keeps >= 80 % of it and the faces stay in;
  // otherwise the whole photo sits, as large as the margin allows, over its own soft backdrop.
  window.SS.largeMargin = function (W, H) {
    const m = Math.round(Math.min(W, H) * LARGE_MARGIN);
    return { x: m, y: m, w: Math.max(1, W - m * 2), h: Math.max(1, H - m * 2) };
  };
  window.SS.largeSingle = function (photo, W, H) {
    const crop = window.SS.coverCrop(photo, W, H);
    if (crop.visible >= LARGE_KEEP && crop.faces)
      return { mode: "fill", rect: { x: 0, y: 0, w: W, h: H }, position: crop.position };
    const raw = photo && photo.w / photo.h, a = Number.isFinite(raw) && raw > 0 ? raw : 1;
    const zone = window.SS.largeMargin(W, H), s = Math.min(zone.w / a, zone.h), w = s * a;
    return { mode: "whole", zone, rect: { x: zone.x + (zone.w - w) / 2, y: zone.y + (zone.h - s) / 2, w, h: s } };
  };
  // Two portraits side by side at one height inside the margin, nothing cropped. [widths] are
  // each photo's drawn width; the gap between them is PAIR_GAP.
  window.SS.pairPlan = function (aspects, W, H) {
    const as = aspects.map(v => Number.isFinite(v) && v > 0 ? v : 1), zone = window.SS.largeMargin(W, H);
    const h = Math.min(zone.h, (zone.w - PAIR_GAP) / (as[0] + as[1])), widths = as.map(a => a * h);
    const w = widths[0] + widths[1] + PAIR_GAP;
    return { mode: "whole", pair: true, zone, gap: PAIR_GAP, widths,
      rect: { x: (W - w) / 2, y: (H - h) / 2, w, h } };
  };

  // ---- Two or three photos as one exact-fit grid ------------------------------------------------
  // Every tile keeps its photo's own aspect (nothing cropped or letterboxed), every gutter is
  // exactly [gap] and the composition shares its outer edges. Candidates: one row, a hero beside
  // (heroLeft) or above (heroTop) a stacked pair, and one column; each is solved in closed form at
  // the largest size that fits [area], centred there. The largest covered area wins, damped when
  // one tile would be a postage stamp. [only] fixes the shape (a named hero is photo 0).
  window.SS.trioLayout = function (aspects, area, gap, only) {
    const a = aspects.map(v => Number.isFinite(v) && v > 0 ? v : 1), n = a.length;
    if (!n || !(area && area.w > 0 && area.h > 0)) return [];
    const g = Math.max(0, gap || 0), cands = [];
    const centre = (boxes, w, h) => boxes.map(b => ({ x: area.x + (area.w - w) / 2 + b.x,
      y: area.y + (area.h - h) / 2 + b.y, w: b.w, h: b.h }));
    if (!only || only === "row") {
      const sum = a.reduce((s, v) => s + v, 0), h = Math.min(area.h, (area.w - (n - 1) * g) / sum);
      let x = 0; const boxes = a.map(v => { const b = { x, y: 0, w: v * h, h }; x += v * h + g; return b; });
      if (h > 0) cands.push(centre(boxes, x - g, h));
    }
    if (!only || only === "column") {
      const sum = a.reduce((s, v) => s + 1 / v, 0), w = Math.min(area.w, (area.h - (n - 1) * g) / sum);
      let y = 0; const boxes = a.map(v => { const b = { x: 0, y, w, h: w / v }; y += w / v + g; return b; });
      if (w > 0) cands.push(centre(boxes, w, y - g));
    }
    if (n === 3) for (let i = 0; i < 3; i++) {
      if (only && i !== 0) break;
      const [j, k] = [0, 1, 2].filter(m => m !== i);
      if (!only || only === "heroLeft") {
        // Hero height H; the stack's width c satisfies c / a[j] + g + c / a[k] = H.
        const s = 1 / a[j] + 1 / a[k], H = Math.min(area.h, (area.w - g * (1 - 1 / s)) / (a[i] + 1 / s));
        const c = (H - g) / s, hw = H * a[i], boxes = [];
        boxes[i] = { x: 0, y: 0, w: hw, h: H };
        boxes[j] = { x: hw + g, y: 0, w: c, h: c / a[j] };
        boxes[k] = { x: hw + g, y: c / a[j] + g, w: c, h: c / a[k] };
        if (c > 0) cands.push(centre(boxes, hw + g + c, H));
      }
      if (!only || only === "heroTop") {
        // Hero width W; the pair's height r satisfies r * a[j] + g + r * a[k] = W.
        const t = a[j] + a[k], W = Math.min(area.w, (area.h - g * (1 - 1 / t)) / (1 / a[i] + 1 / t));
        const r = (W - g) / t, hh = W / a[i], boxes = [];
        boxes[i] = { x: 0, y: 0, w: W, h: hh };
        boxes[j] = { x: 0, y: hh + g, w: r * a[j], h: r };
        boxes[k] = { x: r * a[j] + g, y: hh + g, w: r * a[k], h: r };
        if (r > 0) cands.push(centre(boxes, W, hh + g + r));
      }
    }
    let best = [], bestScore = -1;
    cands.forEach(boxes => {
      const areas = boxes.map(b => b.w * b.h), total = areas.reduce((s, v) => s + v, 0);
      const balance = Math.min(...areas) / Math.max(...areas);
      const score = total * (0.6 + 0.4 * Math.min(1, balance * 2.5));
      if (score > bestScore) { bestScore = score; best = boxes; }
    });
    return best;
  };
  window.SS.TRIO_GAP = { L: 20, compact: 14 };

  // Upright: two landscapes stacked, each the full width (a 3:2 photo keeps 83 %).
  window.SS.stackable = function (p) {
    const a = p && p.w / p.h;
    return Number.isFinite(a) && a >= 1.2 && a <= 2;
  };
  // Vertical filmstrip (upright): cards sized by width so the selected 1.12x card fits the
  // screen width minus 96 and half its height; mirrors FilmstripGeometry.layout(vertical = true).
  window.SS.verticalCard = function (photoAspect, W, H) {
    const a = Number.isFinite(photoAspect) && photoAspect > 0 ? photoAspect : 1;
    let w = (W - 96) / 1.12, h = w / a;
    const maxH = H * 0.5 / 1.12;
    if (h > maxH) { h = maxH; w = h * a; }
    return { w, h };
  };
  // Ken Burns and Cinematic push on a compact screen (mock AB.kenBurns): pan along the long axis
  // so the whole photo is seen. Upright a landscape fills the height and travels its full width
  // (the direction alternates per beat); turned a portrait fills the width and travels top to
  // bottom; a panorama (> 2:1) sweeps at full height; a photo that already matches the screen
  // covers it with a gentle zoom (1 -> 1.09 and a 2 % drift; every third beat zooms out). Push
  // keeps its own 1 -> 1.06 push. [from]/[to] are px offsets along [axis].
  window.SS.compactPan = function (photoAspect, W, H, cls, beat, push) {
    const a = Number.isFinite(photoAspect) && photoAspect > 0 ? photoAspect : 1, k = Math.abs(beat | 0);
    if (a > 2 || (cls === "CP" && a > 1)) {
      const w = H * a, travel = Math.min(0, W - w), back = a <= 2 && k % 2 === 1;
      return { mode: "pan", axis: "x", rect: { x: 0, y: 0, w, h: H },
        from: back ? travel : 0, to: back ? 0 : travel };
    }
    if (cls === "CL" && a < 1) {
      const h = W / a;
      return { mode: "pan", axis: "y", rect: { x: 0, y: 0, w: W, h }, from: 0, to: Math.min(0, H - h) };
    }
    const out = !push && k % 3 === 2;
    return { mode: "fill", rect: { x: 0, y: 0, w: W, h: H },
      zoom: push ? [1, 1.06] : out ? [1.09, 1] : [1, 1.09], drift: push || out ? 0 : k % 2 ? 2 : -2 };
  };
  // The longest edge (CSS px) a compact single photo is drawn at: a cover crop renders the
  // photo past the screen on one axis, a pan or sweep draws the whole strip, a zoom adds its scale.
  window.SS.compactDrawnEdge = function (plan, photoAspect) {
    if (!plan || !plan.rect) return null;
    const a = Number.isFinite(photoAspect) && photoAspect > 0 ? photoAspect : 1, r = plan.rect;
    const zoom = plan.zoom ? Math.max(plan.zoom[0], plan.zoom[1]) : 1;
    if (plan.mode !== "fill") return Math.max(r.w, r.h) * zoom;
    const w = Math.max(r.w, r.h * a);
    return Math.max(w, w / a) * zoom;
  };
  // Decode size on a compact screen: never more than the photo is drawn at in device pixels
  // ([drawn] CSS px; undefined = at most the display's long edge), never more than the mode
  // asked for. [drawn] null keeps the mode's own request (shape not yet known).
  window.SS.compactEdge = function (edge, drawn, W, H, dpr) {
    const ratio = Number.isFinite(dpr) && dpr > 0 ? dpr : 1;
    const asked = Number.isFinite(edge) && edge > 0 ? edge : 2400;
    if (drawn === null) return asked;
    const css = drawn === undefined ? Math.max(W, H) : drawn;
    if (!(Number.isFinite(css) && css > 0)) return asked;
    return Math.max(320, Math.min(asked, Math.ceil(css * ratio)));
  };
  // Gallery wall on a compact screen (mock AB.galleryWall): the tile count follows the area.
  // Upright 7 tiles in rows of ~0.2 H above the clock; turned 6 tiles in rows of >= 0.42 H.
  // The Portal+ ("L") keeps its measured layout (null).
  window.SS.galleryPlan = function (cls, W, H) {
    if (cls === "CP") {
      return { count: 7, area: { x: 28, y: 28, w: Math.max(1, W - 56), h: Math.max(1, H - 300) },
        target: H * .2, gutter: 12, maxRows: 5 };
    }
    if (cls === "CL") {
      const area = { x: 28, y: 28, w: Math.max(1, W - 56), h: Math.max(1, H - 56) };
      return { count: 6, area, target: Math.max(H * .42, area.h * .46), gutter: 14, maxRows: 3 };
    }
    return null;
  };
  // Polaroid grid for [count] prints in a W x H area: the same rule the shipped boot uses.
  window.SS.polaroidGrid = function (count, W, H) {
    const n = Math.max(1, count | 0), cols = Math.max(1, Math.min(n, Math.ceil(Math.sqrt(n * W / H)) || 1));
    return { cols, rows: Math.ceil(n / cols) };
  };
  // Memories collage upright (mock AB.memories): a tall slot table, three cards staggered down
  // the screen with a slight tilt each.
  window.SS.MEMORY_SLOTS_UPRIGHT = [[.06, .09, .7, .3], [.42, .3, .52, .42], [.08, .55, .62, .26]];
  window.SS.MEMORY_TILT_UPRIGHT = [-2, 1.5, -1];

  window.SS.bucket = bucket;
  window.SS.counts = counts;
  window.SS.APPETITE = APPETITE;
  window.SS.BEAT = BEAT;
  window.SS.MODE_IDS = MODE_IDS;

  // Scheduler: given the enabled-mode list + the pool, pick the next mode. Avoids repeating the
  // immediately-previous mode, and only picks modes whose appetite the pool satisfies.
  // [cls] (optional, default "L") applies the screen's pool: upright Cinema never runs.
  window.SS.pickNextMode = function (enabled, pool, prev, cls) {
    const c = counts(pool);
    let candidates = window.SS.poolFor(cls || "L", enabled).filter((id) => APPETITE[id] && APPETITE[id](c));
    if (candidates.length > 1 && prev) candidates = candidates.filter((id) => id !== prev);
    if (!candidates.length) return "kenBurns"; // universal fallback
    return candidates[Math.floor(Math.random() * candidates.length)];
  };

  // Helper: pull N photos of specific buckets from a rotating deck (for a mode's appetite).
  // DUPLICATE-SAFE: photos already picked in this call — plus anything in [exclude] (the seeds
  // currently on screen) — are skipped, so a composition never shows the same photo twice. When
  // a bucket runs dry it falls back to any UNUSED photo (previously it wrapped the deck and
  // re-picked the same one — the source of duplicate tiles in mosaic/hero/gallery/polaroid).
  // Only if the deck is genuinely smaller than the request does it allow a repeat.
  window.SS.take = function (deck, cursorRef, spec, exclude) {
    const out = [];
    const used = new Set(exclude || []);
    // Photos a pairing already showed early (SS.takePartner) are skipped once, so each photo
    // still appears once per pass of the deck.
    const early = () => new Set(cursorRef.ahead || []);
    for (const want of spec) {
      let found = null;
      const ahead = early();
      for (let k = 0; k < deck.length; k++) {
        const p = deck[(cursorRef.i + k) % deck.length];
        if (used.has(p.seed) || ahead.has(p.seed)) continue;
        if (want === "any" || bucket(p) === want) { found = p; consume(deck, cursorRef, k); break; }
      }
      if (!found) {
        for (let k = 0; k < deck.length; k++) {
          const p = deck[(cursorRef.i + k) % deck.length];
          if (!used.has(p.seed) && !ahead.has(p.seed)) { found = p; consume(deck, cursorRef, k); break; }
        }
      }
      if (!found) { found = deck[cursorRef.i % deck.length]; consume(deck, cursorRef, 0); }
      used.add(found.seed);
      out.push(found);
    }
    return out;
  };
  // Advance the cursor past [k + 1] slots; an early-shown photo whose slot is passed is settled.
  function consume(deck, cursorRef, k) {
    if (cursorRef.ahead && cursorRef.ahead.length) {
      const passed = new Set();
      for (let m = 0; m <= k; m++) passed.add(deck[(cursorRef.i + m) % deck.length].seed);
      cursorRef.ahead = cursorRef.ahead.filter(seed => !passed.has(seed));
    }
    cursorRef.i += k + 1;
  }
  // The second portrait of a Portal+ pair: the next portrait within PAIR_LOOKAHEAD slots of
  // the cursor that is not [first] and not on screen ([exclude]). The very next slot is taken
  // in order; a later one is shown early and its own slot is skipped once (cursor.ahead), so
  // the pair never repeats a photo in a pass and never skips one. Null when none is near.
  const PAIR_LOOKAHEAD = 6;
  window.SS.takePartner = function (deck, cursorRef, first, exclude) {
    if (!first || bucket(first) !== "portrait" || deck.length < 2) return null;
    const used = new Set([first.seed].concat(exclude || [], cursorRef.ahead || []));
    const span = Math.min(PAIR_LOOKAHEAD, deck.length - 1);
    for (let k = 0; k < span; k++) {
      const p = deck[(cursorRef.i + k) % deck.length];
      if (used.has(p.seed) || bucket(p) !== "portrait") continue;
      if (k === 0) consume(deck, cursorRef, 0);
      else cursorRef.ahead = (cursorRef.ahead || []).concat(p.seed);
      return p;
    }
    return null;
  };

  // Compositions scale to the pool: a mode that designs for N slots asks for
  // min(N, distinct photos) so a small library never forces repeats on screen.
  window.SS.slots = function (deck, want) {
    return Math.min(want, new Set(deck.map((p) => p.seed)).size);
  };

  // Photos not currently on screen. Swap/drop beats call this first and skip
  // the beat when it's empty — a static composition beats a duplicated one.
  window.SS.unused = function (deck, exclude) {
    const ex = new Set(exclude || []);
    return deck.filter((p) => !ex.has(p.seed));
  };

  // People bias: when someone is watching (host presence signal), reorder the deck so
  // photos with faces (higher pw = people-weight, set by the host's face analysis) come
  // first — the wall leans toward people while preserving shuffle order within each tier.
  // A stable sort keeps this deterministic; watching=false returns the deck untouched so
  // the common path costs nothing.
  window.SS.peopleDeck = function (deck, watching) {
    if (!watching) return deck;
    return deck.slice().sort((a, b) => (b.pw || 0) - (a.pw || 0));
  };

  // Where a pan-through should START, as a fraction of its total travel (0..1), so the
  // first thing on screen is the face, not whatever the top of the photo happens to be.
  // [focal] is the face's position along the pan axis (fx or fy, 0..1; undefined = no
  // face → start at 0). [sizePct] is the element's size along that axis as a percentage
  // of the cell (from fitPlan). The start centers the face in the visible window,
  // clamped to the travel range. Callers with a cached face point keep this offset static;
  // a point alone cannot establish safe bounds for an animated tour.
  window.SS.panStart = function (focal, sizePct) {
    if (!(focal >= 0)) return 0;
    const visible = 100 / sizePct;          // window size as a fraction of the photo
    if (visible >= 1) return 0;
    const windowTop = focal - visible / 2;  // put the face mid-window
    return Math.min(1, Math.max(0, windowTop / (1 - visible)));
  };

  // Largest crop on the overflowing axis retaining the padded union of faces. Older
  // point-only cache entries deliberately keep their original framing.
  window.SS.faceCrop = function (photo, renderedSize, viewportSize, vertical = false) {
    const bounds = photo.fb;
    if (!Number.isFinite(photo.fx) || !Number.isFinite(photo.fy) ||
        photo.fx < 0 || photo.fx > 1 || photo.fy < 0 || photo.fy > 1 ||
        !Array.isArray(bounds) || bounds.length !== 4 ||
        !bounds.every(v => Number.isFinite(v) && v >= 0 && v <= 1) ||
        bounds[0] >= bounds[2] || bounds[1] >= bounds[3]) return null;
    const start = bounds[vertical ? 1 : 0], end = bounds[vertical ? 3 : 2];
    const pad = (end - start) * .1;
    const first = Math.max(0, start - pad), last = Math.min(1, end + pad);
    const size = Math.max(viewportSize, Math.min(renderedSize, viewportSize / (last - first)));
    const excess = size - viewportSize;
    const minOffset = Math.max(0, size * last - viewportSize);
    const maxOffset = Math.max(minOffset, Math.min(excess, size * first));
    const focal = vertical ? photo.fy : photo.fx;
    const offset = Math.min(maxOffset, Math.max(minOffset, size * focal - viewportSize / 2));
    return { size, offset, position: excess > .000001 ? offset / excess : .5 };
  };

  // How a photo should live in a cell. When the aspects roughly agree, a cover
  // crop loses little and the normal Ken Burns applies. Past MISMATCH the crop
  // discards most of the photo (a portrait in a wide cell keeps only a middle
  // band), so instead the photo is sized to its full extent along the clipped
  // axis and slowly pans through it — every part of the image gets shown.
  // Returns { mode: "cover"|"panV"|"panH", size } where size is the element's
  // height (panV) or width (panH) as a percentage of the cell.
  window.SS.fitPlan = function (photoAspect, cellAspect) {
    const MISMATCH = 1.15;
    if (photoAspect * MISMATCH < cellAspect)
      return { mode: "panV", size: (cellAspect / photoAspect) * 100 };
    if (photoAspect > cellAspect * MISMATCH)
      return { mode: "panH", size: (photoAspect / cellAspect) * 100 };
    return { mode: "cover", size: 100 };
  };
})();
