// Shared screensaver engine — the researched techniques distilled into reusable helpers.
// (Premium-craft findings: linear/near-linear Ken Burns, blur-fill for portraits, motion overlaps
//  the crossfade, justified-row layout from Flickr's algorithm, orientation buckets, FLIP morph.)

// ---- Easing curves that read "expensive" (from research) --------------------
window.EASE = {
  kenBurns: "cubic-bezier(0.33, 0, 0.15, 1)", // slow settle, no start/stop tell
  crossfade: "cubic-bezier(0.4, 0, 0.2, 1)",  // material standard
  outExpo: "cubic-bezier(0.16, 1, 0.3, 1)",   // luxurious entrance
  inOutQuart: "cubic-bezier(0.77, 0, 0.175, 1)", // push transitions
  outQuint: "cubic-bezier(0.22, 1, 0.36, 1)", // FLIP morphs
};

// ---- Preload + decode (kills the transition hitch; onload alone isn't enough) ----
window.preloadDecoded = function (url, signal) {
  return new Promise((resolve, reject) => {
    const img = new Image();
    let settled = false;
    const timeout = setTimeout(() => finish(false), 30000);
    function finish(ok) {
      if (settled) return;
      settled = true;
      if (ok && typeof window.recordPhotoDimensions === "function") {
        // Reuse the admitted display decode; slow startup probes need no retry worker.
        try { window.recordPhotoDimensions(url, img.naturalWidth, img.naturalHeight); } catch (_) {}
      }
      clearTimeout(timeout);
      img.onload = img.onerror = null;
      if (signal) signal.removeEventListener("abort", cancel);
      if (!ok) img.src = "";
      if (ok) resolve(url); else reject(new Error("Image preparation failed"));
    }
    function cancel() { finish(false); }
    if (signal) {
      signal.addEventListener("abort", cancel, { once: true });
      if (signal.aborted) { cancel(); return; }
    }
    img.decoding = "async";
    img.onload = async () => {
      try {
        if (img.decode) await img.decode();
        finish(img.naturalWidth > 0 && img.naturalHeight > 0);
      } catch (_) { finish(false); }
    };
    img.onerror = () => finish(false);
    img.src = url;
  });
};

// ---- Ken Burns: scale 1.0→~1.12, translate bounded so edges never reveal black ----
window.kenBurnsPair = function (photo) {
  const zoomIn = Math.random() > 0.4;
  const s0 = zoomIn ? 1.04 : 1.15, s1 = zoomIn ? 1.15 : 1.04;
  const s = Math.max(s0, s1);
  const bound = ((s - 1) / 2) * 100 * 0.82; // % of element, 0.82 safety margin
  // Bias drift along the photo's long axis so wide photos pan horizontally, tall ones vertically.
  const wide = (photo ? window.aspect(photo) : 1.5) > 1.2;
  const bx = wide ? bound : bound * 0.5;
  const by = wide ? bound * 0.5 : bound;
  const rx = () => (Math.random() * 2 - 1) * bx;
  const ry = () => (Math.random() * 2 - 1) * by;
  return { s0, s1, x0: rx(), y0: ry(), x1: rx(), y1: ry() };
};

// ---- Orientation buckets (from the collage research) ------------------------
window.bucketOf = function (p) {
  const a = window.aspect(p);
  if (a < 0.8) return "portrait";
  if (a <= 1.3) return "square";
  if (a <= 2.2) return "landscape";
  return "pano";
};

// ---- Justified layout (Flickr algorithm): pack photos into full-width rows ----
// photos: [{aspect,...}]. Returns { cells:[{photo,left,top,width,height}], height }.
window.justifiedLayout = function (photos, containerW, opts) {
  const targetH = opts.targetRowHeight || 320;
  const gutter = opts.gutter == null ? 10 : opts.gutter;
  const maxRows = opts.maxRows || Infinity;
  const cells = [];
  let row = [], aspectSum = 0, top = 0, rows = 0;

  function closeRow(rowPhotos, rowH) {
    let left = 0;
    rowPhotos.forEach((p) => {
      const w = window.aspect(p) * rowH;
      cells.push({ photo: p, left: Math.round(left), top: Math.round(top), width: Math.round(w), height: Math.round(rowH) });
      left += w + gutter;
    });
    top += rowH + gutter;
    rows++;
  }

  for (const p of photos) {
    row.push(p); aspectSum += window.aspect(p);
    const availW = containerW - (row.length - 1) * gutter;
    const rowH = availW / aspectSum;
    if (rowH <= targetH) {
      closeRow(row, rowH);
      row = []; aspectSum = 0;
      if (rows >= maxRows) break;
    }
  }
  if (row.length && rows < maxRows) {
    // last row: keep at target height, left-aligned (don't stretch a lone photo full-width)
    closeRow(row, Math.min(targetH, (containerW - (row.length - 1) * gutter) / aspectSum));
  }
  return { cells, height: top - gutter };
};

// ---- FLIP: animate an element from a previous rect to its current rect -------
window.flip = function (el, first, durationMs, easing) {
  const last = el.getBoundingClientRect();
  const dx = first.left - last.left, dy = first.top - last.top;
  const sx = first.width / last.width, sy = first.height / last.height;
  el.animate(
    [
      { transformOrigin: "top left", transform: `translate(${dx}px, ${dy}px) scale(${sx}, ${sy})` },
      { transformOrigin: "top left", transform: "none" },
    ],
    { duration: durationMs || 700, easing: easing || window.EASE.outQuint, fill: "both" }
  );
};

// ---- Clock helper ------------------------------------------------------------
window.startClock = function (timeEl, dateEl) {
  function tick() {
    const d = new Date();
    let h = d.getHours(); const m = String(d.getMinutes()).padStart(2, "0");
    const ampm = h >= 12 ? "PM" : "AM"; h = h % 12 || 12;
    if (timeEl) timeEl.textContent = `${h}:${m}`;
    if (dateEl) dateEl.textContent = d.toLocaleDateString(undefined, { weekday: "long", month: "long", day: "numeric" });
  }
  tick(); setInterval(tick, 1000);
};

// ---- Fill photo in bands: spread its GPU upload over frames --------------------
// Chromium in the WebView rasterises and uploads a CSS-background photo in one go at its first
// draw (a 2400 px fill copy is ~16.5 MB: 60-136 ms frames on the Portal+'s in-process GPU
// thread). A fill single is instead copied, before its change, into one accelerated canvas one
// band per frame. Each band is its own small bitmap, so a frame uploads only that band; the
// dissolve then composites the finished canvas, with nothing left to raster or upload.
(function () {
  const SS = window.SS = window.SS || {};
  // ~1 MB per band (about 100 rows of a 2400 px copy, 16 frames per photo).
  SS.STRIP_BUDGET_BYTES = 1 << 20;
  // Frames a band waits for a dissolve (or another painter) to end before painting anyway.
  SS.STRIP_MAX_WAIT_FRAMES = 300;

  // A CSS background-position of one or two percentages ("50%", "37.5% 50%"; "center") as
  // fractions; null for anything else, so the caller keeps the CSS background.
  SS.parsePosition = function (value) {
    const text = value == null || value === "" ? "50% 50%" : String(value).trim();
    const parts = text === "center" ? ["50%", "50%"] : text.split(/\s+/);
    if (parts.length < 1 || parts.length > 2) return null;
    const out = parts.map(p => {
      if (p === "center") return .5;
      const m = /^(-?\d+(?:\.\d+)?)%$/.exec(p);
      return m ? Number(m[1]) / 100 : NaN;
    });
    if (out.length === 1) out.push(.5);
    return out.every(v => Number.isFinite(v) && v >= 0 && v <= 1) ? out : null;
  };

  // Where an [iw] x [ih] photo drawn "cover" at [position] in a [W] x [H] box lands, as the
  // smallest whole-pixel source crop that covers the box. The canvas holds that crop 1:1
  // ([canvas], [src]: its top-left in the photo) and is placed at [css] inside the box, so the
  // compositor scales it exactly as it scaled the background. [slices] are the canvas's row
  // bands, each at most [budget] bytes (RGBA), covering every row once and in order.
  SS.stripLayout = function (iw, ih, W, H, position, budget) {
    const pos = SS.parsePosition(position);
    if (!pos || ![iw, ih, W, H].every(v => Number.isFinite(v) && v > 0)) return null;
    const s = Math.max(W / iw, H / ih), dw = iw * s, dh = ih * s;
    const offX = (W - dw) * pos[0], offY = (H - dh) * pos[1];
    const x0 = Math.max(0, Math.floor(-offX / s + 1e-6)), x1 = Math.min(iw, Math.ceil((W - offX) / s - 1e-6));
    const y0 = Math.max(0, Math.floor(-offY / s + 1e-6)), y1 = Math.min(ih, Math.ceil((H - offY) / s - 1e-6));
    const w = x1 - x0, h = y1 - y0;
    if (!(w > 0 && h > 0)) return null;
    const rows = Math.max(1, Math.floor((Number(budget) > 0 ? budget : SS.STRIP_BUDGET_BYTES) / (w * 4)));
    const slices = [];
    for (let y = 0; y < h; y += rows) slices.push({ y, h: Math.min(rows, h - y) });
    return { canvas: { w, h }, src: { x: x0, y: y0 }, slices,
      css: { left: offX + x0 * s, top: offY + y0 * s, width: w * s, height: h * s } };
  };

  // Dissolves in flight (whole-scene and in-mode). Bands wait while any runs, so their uploads
  // never share a frame with a transition.
  const fades = new Set();
  SS.fades = {
    begin: key => { if (key) fades.add(key); },
    end: key => { fades.delete(key); },
    busy: () => fades.size > 0,
  };

  // Copy [layout]'s bands from [source] into [g], one per frame. [hooks]:
  //   frame()  -> Promise<boolean>: the next frame while playback runs; false = abandoned
  //   crop(source, x, y, w, h) -> Promise<bitmap|null>: one band as its own bitmap
  //   gate()   -> boolean: no dissolve or other painter is running
  // Resolves true once every band is drawn; false (nothing more drawn) when abandoned. The next
  // band is cropped while the current one waits, and every cropped band is closed.
  SS.paintStrips = async function (g, source, layout, hooks) {
    const slices = layout && layout.slices || [];
    const maxWait = Number.isFinite(hooks.maxWaitFrames) ? hooks.maxWaitFrames : SS.STRIP_MAX_WAIT_FRAMES;
    const crop = i => Promise.resolve()
      .then(() => hooks.crop(source, layout.src.x, layout.src.y + slices[i].y, layout.canvas.w, slices[i].h))
      .catch(() => null);
    const close = b => { try { if (b && typeof b.close === "function") b.close(); } catch (_) {} };
    let next = slices.length ? crop(0) : null;
    try {
      for (let i = 0; i < slices.length; i++) {
        for (let waited = 0; ; waited++) {
          if (!await hooks.frame()) return false;
          if (waited >= maxWait || hooks.gate()) break;
        }
        const band = await next; next = null;
        if (!band) return false;
        if (i + 1 < slices.length) next = crop(i + 1);
        try { g.drawImage(band, 0, slices[i].y); } catch (_) { return false; } finally { close(band); }
      }
      return slices.length > 0;
    } finally { if (next) next.then(close); }
  };
})();
