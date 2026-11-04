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
  window.SS.bucket = bucket;
  window.SS.counts = counts;
  window.SS.APPETITE = APPETITE;
  window.SS.BEAT = BEAT;
  window.SS.MODE_IDS = MODE_IDS;

  // Scheduler: given the enabled-mode list + the pool, pick the next mode. Avoids repeating the
  // immediately-previous mode, and only picks modes whose appetite the pool satisfies.
  window.SS.pickNextMode = function (enabled, pool, prev) {
    const c = counts(pool);
    let candidates = enabled.filter((id) => APPETITE[id] && APPETITE[id](c));
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
    for (const want of spec) {
      let found = null;
      for (let k = 0; k < deck.length; k++) {
        const p = deck[(cursorRef.i + k) % deck.length];
        if (used.has(p.seed)) continue;
        if (want === "any" || bucket(p) === want) { found = p; cursorRef.i += k + 1; break; }
      }
      if (!found) {
        for (let k = 0; k < deck.length; k++) {
          const p = deck[(cursorRef.i + k) % deck.length];
          if (!used.has(p.seed)) { found = p; cursorRef.i += k + 1; break; }
        }
      }
      if (!found) { found = deck[cursorRef.i % deck.length]; cursorRef.i++; }
      used.add(found.seed);
      out.push(found);
    }
    return out;
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
