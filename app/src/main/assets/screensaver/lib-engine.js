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
