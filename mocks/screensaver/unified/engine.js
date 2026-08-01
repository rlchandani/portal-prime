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
    let beatTimer = null;

    function ctx() {
      return {
        root: stage, deck, cursor, W: innerWidth, H: innerHeight, url,
        done: () => advance(),
      };
    }

    function clearStage() {
      if (currentCleanup) { try { currentCleanup(); } catch (_) {} currentCleanup = null; }
      stage.innerHTML = "";
    }

    function advance() {
      clearTimeout(beatTimer);
      const enabled = opts.enabledModes && opts.enabledModes.length ? opts.enabledModes : SS.MODE_IDS;
      let modeId;
      if (opts.mode && opts.mode !== "shuffle") modeId = opts.mode;
      else modeId = SS.pickNextMode(enabled, deck, prev);
      prev = modeId;

      clearStage();
      const impl = SS.MODES[modeId];
      if (!impl) { modeId = "kenBurns"; }
      const label = (SS.MODES[modeId] && SS.MODES[modeId].name) || modeId;
      if (opts.onMode) opts.onMode(label, modeId);
      currentCleanup = SS.MODES[modeId].run(ctx(), deck) || null;

      // Single-mode (non-shuffle) modes loop themselves internally; in shuffle we time each beat.
      const beat = (opts.mode && opts.mode !== "shuffle") ? null : (SS.BEAT[modeId] || 9000);
      if (beat) beatTimer = setTimeout(advance, beat);
    }

    advance();
    addEventListener("resize", () => { /* modes read ctx.W/H on next beat */ });
    return { advance, stop: () => { clearTimeout(beatTimer); clearStage(); } };
  };
})();
