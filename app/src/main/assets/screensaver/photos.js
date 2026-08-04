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
  }));

  // Measure a photo's real dimensions (once). Returns a promise resolving to the photo with w/h set.
  function measure(p) {
    if (p.measured) return Promise.resolve(p);
    return new Promise((res) => {
      const im = new Image();
      im.onload = () => { p.w = im.naturalWidth || 1600; p.h = im.naturalHeight || 1067; p.measured = true; res(p); };
      im.onerror = () => { p.w = 1600; p.h = 1067; p.measured = true; res(p); }; // assume landscape on failure
      im.src = p.url;
    });
  }

  // Pre-measure the whole set (bounded, fire-and-forget) so bucket logic has real ratios quickly.
  // We cap concurrency so we don't open 500 decodes at once on the old device.
  async function premeasureAll(max) {
    const list = PHOTOS.slice(0, max || PHOTOS.length);
    let i = 0;
    const CONC = 6;
    async function worker() { while (i < list.length) { const p = list[i++]; await measure(p); } }
    await Promise.all(Array.from({ length: CONC }, worker));
  }

  window.PHOTOS = PHOTOS;
  window.measurePhoto = measure;
  window.premeasureAll = premeasureAll;

  // Engine helpers (mirror the mock's API so mode code is unchanged).
  window.photoUrl = function (p) { return p.url; }; // app already sized/proxied the image
  window.aspect = function (p) { return (p.w || 1600) / (p.h || 1067); };
  window.byKind = function (kind) { return PHOTOS.filter((p) => window.bucketOf(p) === kind); };
  window.shuffled = function (arr) {
    const a = arr.slice();
    for (let i = a.length - 1; i > 0; i--) { const j = Math.floor(Math.random() * (i + 1)); [a[i], a[j]] = [a[j], a[i]]; }
    return a;
  };
})();
