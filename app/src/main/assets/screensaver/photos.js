// App photo source for the screensaver engine. Unlike the Chrome mock (fixed Picsum seeds), this
// pulls the real photo list from the host app via a JS bridge (window.Android), then measures each
// image's true dimensions on load — because remote sources (Immich/NAS/album) don't hand over
// width/height metadata, but the engine's mode-matching needs orientation. Local folder photos come
// as file:// URLs; remote sources as http(s) URLs. Both just work as <img>/background-image.
//
// Contract with the host (PhotoFrameController):
//   window.Android.getPhotos()  -> JSON string: [{ url, w?, h? }, ...]  (w/h optional; measured if absent)
//   window.Android.getMode()    -> "shuffle" | one of the mode ids (optional; defaults to shuffle)
//   window.SS_CONFIG            -> optional injected object { mode, use24 } as a fallback
//
// If no bridge is present (e.g. opened in a browser for testing), falls back to a tiny Picsum set so
// the page still renders.

(function () {
  function fromBridge() {
    try {
      if (window.Android && typeof window.Android.getPhotos === "function") {
        const raw = window.Android.getPhotos();
        const arr = JSON.parse(raw || "[]");
        if (Array.isArray(arr) && arr.length) return arr;
      }
    } catch (e) { /* fall through */ }
    return null;
  }

  function picsumFallback() {
    // Only used when running outside the app (dev/browser). Mixed orientations.
    const specs = [
      ["ridge", 1600, 1067], ["harbor", 1600, 1067], ["desert", 1600, 1067],
      ["portraitA", 1000, 1500], ["portraitB", 1000, 1500], ["portraitC", 1000, 1400],
      ["square1", 1200, 1200], ["square2", 1200, 1200],
      ["pano1", 2400, 900], ["waterfall", 1000, 1600], ["city88", 1600, 1067], ["tower55", 1000, 1500],
    ];
    return specs.map(([seed, w, h]) => ({ url: `https://picsum.photos/seed/${seed}/${Math.round(w / 2)}/${Math.round(h / 2)}`, w, h }));
  }

  const raw = fromBridge() || picsumFallback();

  // Normalize into the engine's photo objects. w/h may be 0/absent → measured lazily on first use.
  const PHOTOS = raw.map((p, i) => ({
    id: i,
    // The engine's duplicate-avoidance (SS.take exclude lists) keys photos by `seed`. The Chrome
    // mock's Picsum photos carry one natively; bridge photos must get one too or every photo
    // shares seed=undefined and the dedup collapses into a no-op.
    seed: p.url || String(i),
    url: p.url,
    w: p.w || 0,
    h: p.h || 0,
    measured: !!(p.w && p.h),
    // Face focal point (0..1 of width/height), when the host has analyzed this photo.
    // Framing code reads these to keep faces in crops and open pans on them.
    fx: typeof p.fx === "number" ? p.fx : undefined,
    fy: typeof p.fy === "number" ? p.fy : undefined,
    // People-weight (0..1): how face-centric the photo is. Drives the presence-aware
    // deck bias (SS.peopleDeck) — people photos lead while someone is watching.
    pw: typeof p.pw === "number" ? p.pw : 0,
  }));

  // Measure a photo's real dimensions (once). Returns a promise resolving to the photo with w/h set.
  function measure(p, signal) {
    if (p.measured) return Promise.resolve(p);
    if (signal && signal.aborted) return Promise.resolve(p);
    return new Promise((res) => {
      const im = new Image();
      let settled = false;
      function finish(loaded, cancelled = false) {
        if (settled) return;
        settled = true;
        if (!cancelled) {
          p.w = loaded && im.naturalWidth || 1600;
          p.h = loaded && im.naturalHeight || 1067;
          p.measured = true;
        }
        im.onload = im.onerror = null;
        if (signal) signal.removeEventListener("abort", cancel);
        im.src = "";
        res(p);
      }
      function cancel() { finish(false, true); }
      im.onload = () => finish(true);
      im.onerror = () => finish(false); // assume landscape on an actual load failure
      if (signal) signal.addEventListener("abort", cancel, { once: true });
      // Dimensions need an aspect ratio, not display-resolution pixels. Known metadata
      // bypasses this entirely; unknown photos use a ready small copy when available.
      im.src = window.photoUrl(p, 320);
    });
  }

  // Pre-measure the whole set (bounded, fire-and-forget) so bucket logic has real ratios quickly.
  // We cap concurrency so we don't open 500 decodes at once on the old device.
  async function premeasureAll(max, signal) {
    const list = PHOTOS.slice(0, max || PHOTOS.length);
    let i = 0;
    const CONC = 2;
    async function worker() {
      while (i < list.length && !(signal && signal.aborted)) {
        const p = list[i++]; await measure(p, signal);
      }
    }
    await Promise.all(Array.from({ length: CONC }, worker));
  }

  window.PHOTOS = PHOTOS;
  window.measurePhoto = measure;
  window.premeasureAll = premeasureAll;

  // Only unknown photos need display-load metadata. Bound aliases because ready
  // derivative names can change after cache eviction; never retain image pixels here.
  const dimensionSources = new Map();
  function sizeSource(p, url) {
    if (!p.measured) {
      dimensionSources.delete(url); dimensionSources.set(url, p);
      if (dimensionSources.size > 64) dimensionSources.delete(dimensionSources.keys().next().value);
    }
    return url;
  }
  window.recordPhotoDimensions = function (url, width, height) {
    const p = dimensionSources.get(url);
    dimensionSources.delete(url);
    if (!p || p.measured || !Number.isFinite(width) || !Number.isFinite(height) ||
        width <= 0 || height <= 0) return;
    p.w = width; p.h = height; p.measured = true;
  };

  // Engine helpers (mirror the mock's API so mode code is unchanged).
  // Ask for the slot's size at load cadence, never per animation frame. Revalidate ready files
  // on each load: the native disk budget may evict a copy while this page is still alive.
  window.photoUrl = function (p, requestedEdge) {
    const edge = typeof requestedEdge === "number" && Number.isFinite(requestedEdge) && requestedEdge > 0
      ? Math.max(320, Math.min(2400, Math.ceil(requestedEdge))) : 2400;
    try {
      const host = window.Android;
      const ready = host && typeof host.scaledUrlForSize === "function"
        ? host.scaledUrlForSize(p.url, edge)
        : host && typeof host.scaledUrl === "function" ? host.scaledUrl(p.url) : "";
      if (typeof ready === "string" && ready.length) return sizeSource(p, ready);
    } catch (_) {}
    return sizeSource(p, p.url);
  };
  window.aspect = function (p) { return (p.w || 1600) / (p.h || 1067); };
  window.byKind = function (kind) { return PHOTOS.filter((p) => window.bucketOf(p) === kind); };
  window.shuffled = function (arr) {
    const a = arr.slice();
    for (let i = a.length - 1; i > 0; i--) { const j = Math.floor(Math.random() * (i + 1)); [a[i], a[j]] = [a[j], a[i]]; }
    return a;
  };
})();
