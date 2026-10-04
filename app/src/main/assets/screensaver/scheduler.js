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
    // Per-photo dwell comes from the user's interval (host bridge, seconds). Single-photo modes
    // hold each photo this long; collage modes derive a gentler micro-motion cadence from it.
    // Falls back to 12s (research sweet spot) when no host interval is provided (dev/browser).
    const intervalSec = (opts.intervalSec && opts.intervalSec > 0) ? opts.intervalSec : 12;
    const intervalMs = Math.min(600, Math.max(5, intervalSec)) * 1000;
    // microMs drives visible IMAGE SWAPS → tie it to the interval so a photo changes ~every
    // interval (not 4x faster). ambientMs drives pure MOTION (e.g. the mosaic spotlight) that should
    // stay gentle regardless of interval.
    const microMs = intervalMs;
    const ambientMs = Math.min(9000, Math.max(3500, Math.round(intervalMs / 4)));
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
        photoMs: intervalMs, microMs: microMs, ambientMs: ambientMs,
        done: () => advance(),
      };
    }

    // A fade in flight: the outgoing layer + its mode's cleanup, torn down when the fade
    // ends — or flushed early if a new beat / stop() arrives first (never leaked: a mode's
    // cleanup stops its timers, and an unstopped mode would keep mutating a dead layer).
    let pendingFade = null;
    let queuedAdvance = false;
    let stopped = false;
    function flushFade() {
      clearTimeout(fadeTimer);
      if (pendingFade) {
        const { cleanup, layer } = pendingFade;
        pendingFade = null;
        if (cleanup) { try { cleanup(); } catch (_) {} }
        layer.remove();
      }
      // A beat that arrived mid-fade was deferred (not stacked); run it now the fade is done.
      if (queuedAdvance && !stopped) { queuedAdvance = false; advance(); }
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
      // Re-entrancy guard: never rip out a half-faded layer. If a crossfade is still in flight,
      // defer this advance until it completes (coalesced) instead of stacking a second fade —
      // stacked/flushed layers were the "two images coasting" ghost.
      if (pendingFade) { queuedAdvance = true; return; }
      clearTimeout(beatTimer);
      const enabled = opts.enabledModes && opts.enabledModes.length ? opts.enabledModes : SS.MODE_IDS;
      let modeId;
      if (opts.mode && opts.mode !== "shuffle") modeId = opts.mode;
      else modeId = SS.pickNextMode(enabled, deck, prev);
      prev = modeId;

      const impl = SS.MODES[modeId];
      if (!impl) { modeId = "kenBurns"; }
      const label = (SS.MODES[modeId] && SS.MODES[modeId].name) || modeId;
      if (opts.onMode) opts.onMode(label, modeId);
      try { console.log("[ss] beat " + modeId + " vp=" + innerWidth + "x" + innerHeight + " dpr=" + (window.devicePixelRatio || 1)); } catch (_) {}

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
        oldLayer.classList.add("freeze"); // stop the outgoing mode animating during the crossfade
        pendingFade = { cleanup: oldCleanup, layer: oldLayer };
        requestAnimationFrame(() => layer.classList.add("on"));
        fadeTimer = setTimeout(flushFade, XFADE_MS + 100);
      } else {
        layer.classList.add("on");
      }

      // Single-mode (non-shuffle) modes loop themselves internally; in shuffle we time each beat.
      // Honor the user's interval: a mode holds at least as long as one photo dwell, so a long
      // interval slows scene changes (not clamped by the mode's base beat) while a short one still
      // lets a mode compose 2-3 photos before rotating.
      const beat =
          (opts.mode && opts.mode !== "shuffle") ? null : Math.max(SS.BEAT[modeId] || 9000, intervalMs);
      if (beat) beatTimer = setTimeout(advance, beat);
    }

    advance();
    // ONE-SHOT boot recovery only. A DreamService WebView can deliver its first layout at a
    // degenerate (tiny) size, so the opening mode composes into a corner. Recover exactly once,
    // and only when the viewport grows from that degenerate state to ~full-screen. We must NOT
    // re-advance on ordinary viewport changes — that was tearing down live beats mid-photo and
    // stacking half-faded crossfades (the "jumps to a new mode" + "two images coasting" bugs).
    let recovered = false, resizeTimer = null;
    const bootDegenerate = innerWidth < screen.width * 0.6 || innerHeight < screen.height * 0.6;
    addEventListener("resize", () => {
      if (recovered || !bootDegenerate) return;
      clearTimeout(resizeTimer);
      resizeTimer = setTimeout(() => {
        const full = innerWidth >= screen.width * 0.8 && innerHeight >= screen.height * 0.8;
        if (full && !pendingFade) { recovered = true; advance(); }
      }, 300);
    });
    return {
      advance,
      stop: () => { stopped = true; clearTimeout(beatTimer); clearTimeout(resizeTimer); clearStage(); },
    };
  };
})();
