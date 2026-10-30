// One visible composition, one preparing composition, and one bounded outgoing dissolve.
// Modes return a cleanup function with ready/pause/resume; readiness includes load AND decode.
(function () {
  const SS = window.SS;
  let sceneSequence = 0, handoffSequence = 0;
  const handoffs = new Map();
  const nextHandoffId = () => { handoffSequence = handoffSequence % 2147483647 + 1; return handoffSequence; };
  SS.nativeFilmstripHandoffDone = (id, ok) => {
    const request = handoffs.get(id);
    if (request) request.reply(ok === true);
  };
  SS.startEngine = function (opts) {
    const stage = opts.stage, deck = window.shuffled(window.PHOTOS.slice()), cursor = { i: 0 };
    const intervalMs = Math.min(600, Math.max(5, Number(opts.intervalSec) || 30)) * 1000;
    const XFADE_MS = 1600, MOTION_OVERLAP_MS = 2400;
    let current = null, incoming = null, outgoing = null, prev = null;
    let stopped = false, paused = false, queued = null, promotionFrame = null, handoff = null;
    let history = [], historyIndex = -1;
    const clocks = { beat: null, fade: null, resize: null, handoff: null };
    const pausedAnimations = new Set();
    function arm(key, fn, ms) {
      disarm(key);
      const clock = { fn, remaining: ms, due: Date.now() + ms, id: null };
      clocks[key] = clock;
      if (!paused) clock.id = setTimeout(() => { clocks[key] = null; fn(); }, ms);
    }
    function disarm(key) { if (clocks[key]) clearTimeout(clocks[key].id); clocks[key] = null; }
    function dispose(scene) {
      if (!scene) return;
      scene.live = false;
      if (scene.cleanup) { try { scene.cleanup(); } catch (_) {} }
      scene.layer.remove();
    }
    function remember(scene, start) {
      scene.start = start;
      if (scene !== current) return;
      history = history.slice(0, historyIndex + 1);
      history.push({ modeId: scene.modeId, start });
      if (history.length > 32) history.shift();
      historyIndex = history.length - 1;
    }
    function finishFade() {
      dispose(outgoing); outgoing = null;
      if (queued && !stopped && !paused) {
        const direction = queued; queued = null; advance(direction);
      }
    }
    function commit(scene) {
      incoming = null;
      outgoing = current; current = scene;
      if (scene.historyIndex != null) historyIndex = scene.historyIndex;
      else remember(scene, scene.start);
      prev = scene.modeId;
      if (outgoing) {
        outgoing.layer.classList.add("freeze");
        outgoing.cleanup.pause();
      }
      scene.layer.classList.remove("freeze");
      scene.layer.classList.add("on");
      if (opts.onMode) opts.onMode(SS.MODES[scene.modeId].name, scene.modeId);
      if (opts.onReady) opts.onReady();
      if (outgoing) arm("fade", finishFade, XFADE_MS + 100);
      if (!opts.mode || opts.mode === "shuffle") {
        arm("beat", () => advance("auto"), Math.max(SS.BEAT[scene.modeId] || intervalMs, intervalMs));
      }
      if (!outgoing && queued) { const direction = queued; queued = null; navigate(direction); }
    }
    function cancelHandoff() {
      if (!handoff) return;
      const request = handoff;
      handoffs.delete(request.id); disarm("handoff"); handoff = null;
      if (request.restoreWait && request.restoreWait.cancel) request.restoreWait.cancel();
    }
    function promote(scene) {
      if (stopped || paused || incoming !== scene || !scene.live || !scene.ready) return;
      if (handoff) {
        if (handoff.scene === scene && handoff.previous === current && handoff.restored === true) {
          handoff.rollback(); return;
        }
        if (handoff.scene === scene && handoff.previous === current && handoff.ok === true) {
          cancelHandoff(); commit(scene);
        }
        return;
      }
      const previous = current;
      const incomingToken = scene.cleanup.nativeToken || "";
      const outgoingToken = previous && previous.cleanup.nativeToken || "";
      if (!incomingToken && !outgoingToken) { commit(scene); return; }
      const host = window.Android;
      const request = { id: nextHandoffId(), scene, previous, ok: null, accepted: null, early: null };
      handoff = request;
      if (previous) { previous.layer.classList.add("freeze"); previous.cleanup.pause(); }
      // Native sits above WV. An opaque successor underneath one native alpha fade
      // avoids multiplying two fades and exposing the black page through their product.
      const oldTransition = scene.layer.style.transition, oldOpacity = scene.layer.style.opacity;
      if (!incomingToken) { scene.layer.style.transition = "none"; scene.layer.style.opacity = "1"; void scene.layer.offsetWidth; }
      const ownsHandoff = () => handoff === request && !stopped && incoming === scene && scene.live &&
        current === previous && (!previous || previous.live);
      request.rollback = () => {
        if (!ownsHandoff() || paused) return;
        cancelHandoff();
        scene.layer.style.transition = oldTransition; scene.layer.style.opacity = oldOpacity;
        dispose(scene); incoming = null;
        if (previous) { previous.layer.classList.remove("freeze"); previous.cleanup.resume(); }
        try { console.warn("native_filmstrip_handoff_failed"); } catch (_) {}
        arm("beat", () => { const direction = queued || "auto"; queued = null; advance(direction); }, Math.min(intervalMs, 5000));
      };
      request.finish = (ok, uncertain = false) => {
        if (!ownsHandoff() || request.recovering) return;
        handoffs.delete(request.id); disarm("handoff");
        request.ok = ok;
        if (ok) { if (!paused) promote(scene); return; }
        if (uncertain && outgoingToken) {
          // A lost start ACK may have completed either native fade. Retain the
          // successor until the host acknowledges an opaque, immediate restore.
          request.recovering = true;
          scene.cleanup.pause();
          const restored = ok => {
            if (!ownsHandoff() || request.recoverySettled) return;
            request.recoverySettled = true; disarm("handoff");
            request.restored = ok === true;
            if (request.restored) { request.rollback(); return; }
            if (request.restoreWait && request.restoreWait.cancel) request.restoreWait.cancel();
            // Neither owner is safe to release without an opaque restoration.
            // Hold both, queue navigation, and let stop/reload release the owners.
            try { console.warn("native_filmstrip_restore_failed"); } catch (_) {}
          };
          // The outgoing mode is frozen; the engine owns the pausable deadline.
          arm("handoff", () => restored(false), 8000);
          try { request.restoreWait = previous.cleanup.restore(); }
          catch (_) { restored(false); return; }
          Promise.resolve(request.restoreWait).then(restored, () => restored(false));
          return;
        }
        // Definite rejection, or a WV predecessor, needs no native restoration.
        if (paused) { request.restored = true; return; }
        request.rollback();
      };
      request.reply = ok => {
        if (request.accepted === null) request.early = ok;
        else request.finish(request.accepted && ok);
      };
      if (handoffs.size >= 3) { request.finish(false); return; }
      handoffs.set(request.id, request);
      arm("handoff", () => request.finish(false, true), 8000);
      try { request.accepted = !!host && typeof host.promoteNativeFilmstrip === "function" &&
          host.promoteNativeFilmstrip(incomingToken, outgoingToken, request.id) === true; }
      catch (_) { request.accepted = false; }
      if (!request.accepted) request.finish(false);
      else if (request.early !== null) request.finish(request.early);
    }
    function present(scene) {
      if (promotionFrame != null) cancelAnimationFrame(promotionFrame);
      promotionFrame = requestAnimationFrame(() => { promotionFrame = null; promote(scene); });
    }
    function beatDeck() {
      let watching = false;
      try { watching = !!(window.Android && window.Android.isWatching && window.Android.isWatching()); } catch (_) {}
      return SS.peopleDeck(deck, watching);
    }
    function advance(direction = "next") {
      if (stopped || !deck.length || (paused && direction === "auto")) return false;
      if (outgoing || handoff) { queued = direction; return true; }
      disarm("beat");
      if (incoming) { dispose(incoming); incoming = null; }
      if (promotionFrame != null) { cancelAnimationFrame(promotionFrame); promotionFrame = null; }
      let target = null, targetIndex = null;
      if (direction === "previous" && historyIndex > 0) targetIndex = historyIndex - 1;
      else if (direction === "next" && historyIndex >= 0 && historyIndex + 1 < history.length) targetIndex = historyIndex + 1;
      if (targetIndex != null) target = history[targetIndex];
      if (direction === "previous" && !target && current) target = { modeId: current.modeId, start: Math.max(0, current.start - 1) };
      let modeId = target ? target.modeId : opts.mode && opts.mode !== "shuffle" ?
          opts.mode : SS.pickNextMode(opts.enabledModes || SS.MODE_IDS, deck, prev);
      if (!SS.MODES[modeId]) modeId = "kenBurns";
      if (target) cursor.i = target.start;
      const layer = document.createElement("div");
      layer.className = "ss-beat freeze";
      layer.style.setProperty("--motion-duration", (intervalMs + MOTION_OVERLAP_MS) + "ms");
      stage.appendChild(layer);
      const scene = { layer, modeId, start: cursor.i, historyIndex: targetIndex, live: true, ready: false,
        nativeToken: "ss-" + (++sceneSequence) };
      incoming = scene;
      const ctx = {
        root: layer, deck, cursor, W: innerWidth, H: innerHeight, nativeToken: scene.nativeToken,
        url: (photo, edge) => window.photoUrl(photo, edge),
        photoMs: intervalMs, microMs: intervalMs, motionMs: intervalMs + MOTION_OVERLAP_MS,
        photoMotion: opts.photoMotion !== false,
        ambientMs: Math.min(9000, Math.max(3500, Math.round(intervalMs / 4))),
        record: start => { if (scene.live) remember(scene, start); },
        done: () => { if (scene === current && scene.live) advance("auto"); },
      };
      try {
        scene.cleanup = SS.MODES[modeId].run(ctx, beatDeck());
        if (paused) scene.cleanup.pause();
        Promise.resolve(scene.cleanup.ready).then(ok => {
          if (!scene.live || stopped || incoming !== scene) return;
          if (!ok) {
            dispose(scene); incoming = null;
            // Keep the last decoded scene while a source fails; retry without a tight loop.
            arm("beat", () => advance("auto"), Math.min(intervalMs, 5000));
            return;
          }
          scene.ready = true;
          if (!paused) present(scene);
        });
      } catch (_) {
        dispose(scene); incoming = null;
        arm("beat", () => advance("auto"), Math.min(intervalMs, 5000));
      }
      return true;
    }
    function pause() {
      if (paused || stopped) return;
      paused = true;
      Object.values(clocks).filter(Boolean).forEach(clock => {
        if (clock.id != null) { clock.remaining = Math.max(0, clock.due - Date.now()); clearTimeout(clock.id); clock.id = null; }
      });
      [current, incoming, outgoing].filter(Boolean).forEach(scene => {
        scene.cleanup.pause();
        // CSS transitions where supported; timers and admission pause on every supported engine.
        try {
          scene.layer.getAnimations({ subtree: true }).forEach(animation => {
            // CSS owns paused/freeze/retired. API play() would override those later rules.
            const cssAnimation = typeof animation.animationName === "string" ||
                (typeof CSSAnimation !== "undefined" && animation instanceof CSSAnimation);
            if (!cssAnimation && animation.playState === "running") {
              animation.pause(); pausedAnimations.add(animation);
            }
          });
        } catch (_) {}
        scene.layer.classList.add("paused");
      });
    }
    function resume() {
      if (!paused || stopped) return;
      paused = false;
      pausedAnimations.forEach(animation => { try { animation.play(); } catch (_) {} }); pausedAnimations.clear();
      [current, incoming].filter(Boolean).forEach(scene => {
        scene.layer.classList.remove("paused");
        if (!handoff || (!handoff.recovering && scene !== handoff.previous)) scene.cleanup.resume();
      });
      if (outgoing) outgoing.layer.classList.remove("paused");
      Object.keys(clocks).forEach(key => {
        const clock = clocks[key];
        if (clock) arm(key, clock.fn, clock.remaining);
      });
      if (incoming && incoming.ready) present(incoming);
    }
    let recovered = false;
    const bootDegenerate = innerWidth < screen.width * .6 || innerHeight < screen.height * .6;
    function onResize() {
      if (stopped || recovered || !bootDegenerate) return;
      arm("resize", () => {
        if (innerWidth >= screen.width * .8 && innerHeight >= screen.height * .8 && !outgoing) {
          recovered = true; advance("auto");
        }
      }, 300);
    }
    addEventListener("resize", onResize);
    advance();
    function navigate(direction) {
      if (stopped) return false;
      if (current && !incoming && !outgoing && current.cleanup[direction]) return current.cleanup[direction]();
      return advance(direction);
    }
    return {
      advance: () => navigate("next"), next: () => navigate("next"), previous: () => navigate("previous"),
      pause, resume,
      stop() {
        if (stopped) return;
        stopped = true; queued = null; cancelHandoff();
        Object.keys(clocks).forEach(disarm);
        if (promotionFrame != null) cancelAnimationFrame(promotionFrame);
        removeEventListener("resize", onResize);
        [incoming, current, outgoing].forEach(dispose); incoming = current = outgoing = null;
        pausedAnimations.clear(); stage.innerHTML = "";
      },
    };
  };
})();
