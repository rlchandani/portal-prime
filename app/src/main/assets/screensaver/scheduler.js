// Unified engine: owns the stage, runs the scheduler, and hands control to one mode at a time.
// A mode's run(ctx, pool) returns a cleanup function; the engine calls it before the next mode.
//
// Shared ctx given to each mode:
//   ctx.root   — the stage element to render into (cleared between modes)
//   ctx.deck   — the shuffled photo pool (array of {seed,w,h,kind})
//   ctx.cursor — { i } rotating cursor into the deck (shared so photos don't repeat across beats)
//   ctx.W, ctx.H — viewport size
//   ctx.url(photo, longEdge) — build an image URL
//   ctx.done() — a mode may call this to end its beat early (optional)

(function () {
  const SS = window.SS;

  function url(photo, longEdge) { return window.photoUrl(photo, longEdge); }

  window.SS.startEngine = function (opts) {
    const stage = opts.stage;
    const deck = window.shuffled(window.PHOTOS.slice());
    const cursor = { i: 0 };
    // opts.mode: a specific mode id, or "shuffle" to let the scheduler choose each beat.
    let prev = null;
    let currentCleanup = null;
    let currentLayer = null;
    let beatTimer = null;
    let fadeTimer = null;

    // Modes render into a per-beat layer, not the bare stage, so beats can crossfade:
    // the new layer fades in OVER the still-running old mode, then the old one is torn
    // down — no flash-to-black between shuffle modes. XFADE matches the .ss-beat CSS.
    const XFADE_MS = 1600;

    function ctx(layer) {
      return {
        root: layer, deck, cursor, W: innerWidth, H: innerHeight, url,
        done: () => advance(),
      };
    }

    // A fade in flight: the outgoing layer + its mode's cleanup, torn down when the fade
    // ends — or flushed early if a new beat / stop() arrives first (never leaked: a mode's
    // cleanup stops its timers, and an unstopped mode would keep mutating a dead layer).
    let pendingFade = null;
    function flushFade() {
      clearTimeout(fadeTimer);
      if (!pendingFade) return;
      const { cleanup, layer } = pendingFade;
      pendingFade = null;
      if (cleanup) { try { cleanup(); } catch (_) {} }
      layer.remove();
    }

    function clearStage() {
      flushFade();
      if (currentCleanup) { try { currentCleanup(); } catch (_) {} currentCleanup = null; }
      currentLayer = null;
      stage.innerHTML = "";
    }

    // Presence-aware people bias: while someone is in front of the frame (host bridge
    // signal), the beat's deck leads with photos of people. Checked once per beat — the
    // bridge call is cheap but there's no reason to ask more often than modes change.
    function beatDeck() {
      let watching = false;
      try { watching = !!(window.Android && window.Android.isWatching && window.Android.isWatching()); } catch (_) {}
      return SS.peopleDeck(deck, watching);
    }

    function advance() {
      clearTimeout(beatTimer);
      flushFade(); // a beat arriving mid-fade finishes the previous teardown first
      const enabled = opts.enabledModes && opts.enabledModes.length ? opts.enabledModes : SS.MODE_IDS;
      let modeId;
      if (opts.mode && opts.mode !== "shuffle") modeId = opts.mode;
      else modeId = SS.pickNextMode(enabled, deck, prev);
      prev = modeId;

      const impl = SS.MODES[modeId];
      if (!impl) { modeId = "kenBurns"; }
      const label = (SS.MODES[modeId] && SS.MODES[modeId].name) || modeId;
      if (opts.onMode) opts.onMode(label, modeId);

      // Crossfade beats: the new mode renders into a fresh layer that fades in over the
      // still-running old one; the old layer (and its mode's timers) is torn down only
      // after the fade completes. First beat has nothing to fade over → shown immediately.
      const oldLayer = currentLayer, oldCleanup = currentCleanup;
      const layer = document.createElement("div");
      layer.className = "ss-beat";
      stage.appendChild(layer);
      currentLayer = layer;
      currentCleanup = SS.MODES[modeId].run(ctx(layer), beatDeck()) || null;
      if (oldLayer) {
        pendingFade = { cleanup: oldCleanup, layer: oldLayer };
        requestAnimationFrame(() => layer.classList.add("on"));
        fadeTimer = setTimeout(flushFade, XFADE_MS + 100);
      } else {
        layer.classList.add("on");
      }

      // Single-mode (non-shuffle) modes loop themselves internally; in shuffle we time each beat.
      const beat = (opts.mode && opts.mode !== "shuffle") ? null : (SS.BEAT[modeId] || 9000);
      if (beat) beatTimer = setTimeout(advance, beat);
    }

    advance();
    // Re-render on a REAL viewport change. A DreamService WebView can deliver its first layout
    // after the page boots, so the first mode renders against a tiny early viewport (content
    // squeezed into a corner, rest black) — and single-mode styles loop internally, so without
    // this they never recover. Debounced; ignores sub-5% jitter.
    let lastW = innerWidth, lastH = innerHeight, resizeTimer = null;
    addEventListener("resize", () => {
      clearTimeout(resizeTimer);
      resizeTimer = setTimeout(() => {
        const dw = Math.abs(innerWidth - lastW) / Math.max(1, lastW);
        const dh = Math.abs(innerHeight - lastH) / Math.max(1, lastH);
        if (dw > 0.05 || dh > 0.05) { lastW = innerWidth; lastH = innerHeight; advance(); }
      }, 250);
    });
    return { advance, stop: () => { clearTimeout(beatTimer); clearTimeout(resizeTimer); clearStage(); } };
  };
})();
