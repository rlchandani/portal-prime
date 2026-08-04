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
})();
