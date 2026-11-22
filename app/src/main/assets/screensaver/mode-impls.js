// The 12 screensaver modes, unified into the SS.MODES registry. Each: { name, run(ctx, deck) }.
// run() renders into ctx.root and returns a cleanup() the engine calls before the next mode.
// Shared helpers from SS: bucket, take, counts. Photo helpers: ctx.url, window.aspect.

(function () {
  const SS = window.SS;
  const aspect = window.aspect;
  // Dissolves in flight (lib-engine.js); photo bands wait for them.
  const FADES = SS.fades || { begin() {}, end() {}, busy: () => false };
  const EASE = {
    kb: "cubic-bezier(0.33,0,0.15,1)", fade: "cubic-bezier(0.4,0,0.2,1)",
    outExpo: "cubic-bezier(0.16,1,0.3,1)", quart: "cubic-bezier(0.77,0,0.175,1)", quint: "cubic-bezier(0.22,1,0.36,1)",
  };

  // ---- tiny DOM helpers -----------------------------------------------------
  function el(cls, parent) { const d = document.createElement("div"); if (cls) d.className = cls; if (parent) parent.appendChild(d); return d; }
  // Publish the exact URL admitted by t.load. Resolve again only for a new preparation
  // so derivative completion/eviction cannot silently substitute an unprepared resource.
  function bg(node, url, size) { node.style.backgroundImage = `url("${url}")`; node.style.backgroundSize = size || "cover"; node.style.backgroundPosition = "center"; node.style.backgroundRepeat = "no-repeat"; }
  // Anchor a cover-crop on the detected face (fx/fy in 0..1) so heads aren't clipped. Falls back to
  // the CSS default (a top-biased center) when the photo has no focal point yet.
  function hasFace(p) { return p && Number.isFinite(p.fx) && Number.isFinite(p.fy) && p.fx >= 0 && p.fx <= 1 && p.fy >= 0 && p.fy <= 1; }
  function faceBg(node, p) {
    if (hasFace(p)) {
      node.style.backgroundPosition = (p.fx * 100).toFixed(1) + "% " + (p.fy * 100).toFixed(1) + "%";
      node.style.animation = "none";
    }
  }
  // [fit] (a whole photo or pair on the Portal+) is the largest scale that keeps the box on screen:
  // the zoom then runs between 1 and that scale, centred, so nothing leaves the screen. The same
  // random draws are taken either way, so photo selection never depends on the layout.
  function kbVars(node, photo, fit) {
    // Keep a prepared photo's animation alive when its parent starts fading out.
    node.classList.add("ss-motion");
    if (hasFace(photo)) {
      ["--s0", "--s1"].forEach(k => node.style.setProperty(k, 1));
      ["--x0", "--y0", "--x1", "--y1"].forEach(k => node.style.setProperty(k, "0%"));
      return;
    }
    const zoomIn = Math.random() > 0.4; let s0 = zoomIn ? 1.04 : 1.15, s1 = zoomIn ? 1.15 : 1.04;
    const s = Math.max(s0, s1), bound = ((s - 1) / 2) * 100 * 0.82;
    const wide = aspect(photo) > 1.2; const bx = wide ? bound : bound * 0.5, by = wide ? bound * 0.5 : bound;
    const rx = () => (Math.random() * 2 - 1) * bx, ry = () => (Math.random() * 2 - 1) * by;
    let x = [rx(), ry(), rx(), ry()];
    if (fit) {
      const top = Math.max(1, Math.min(1.15, fit));
      s0 = zoomIn ? 1 : top; s1 = zoomIn ? top : 1; x = [0, 0, 0, 0];
    }
    node.style.setProperty("--s0", s0); node.style.setProperty("--s1", s1);
    node.style.setProperty("--x0", x[0] + "%"); node.style.setProperty("--y0", x[1] + "%");
    node.style.setProperty("--x1", x[2] + "%"); node.style.setProperty("--y1", x[3] + "%");
  }
  // One owner for initial readiness, decode cancellation, non-overlapping swaps and pausable timers.
  function makeTimers(ctx) {
    const controller = new AbortController(), tasks = new Set(), frames = new Set();
    let active = true, paused = false, started = false, busy = false, waiters = [];
    let pendingNavigation = null, navigating = null;
    let ready = Promise.resolve(false);
    function running() {
      if (!active) return Promise.resolve(false);
      if (!paused) return Promise.resolve(true);
      return new Promise(resolve => waiters.push(resolve));
    }
    async function publish(fn) {
      while (active) {
        if (!await running() || !active) return false;
        // A resume grant can be followed by another pause before this continuation.
        if (paused) continue;
        return fn();
      }
      return false;
    }
    async function load(urls) {
      try {
        const loaded = await publish(() => Promise.all(Array.from(new Set([].concat(urls)))
          .map(u => window.preloadDecoded(u, controller.signal))));
        if (loaded === false) return false;
        return publish(() => {
          if (typeof window.refreshPhotoFocuses === "function") window.refreshPhotoFocuses([].concat(urls));
          return true;
        });
      } catch (_) { return false; }
    }
    async function run(fn) {
      if (!active || busy) return false;
      if (!await running() || !active || busy) return false;
      if (paused) return run(fn);
      busy = true;
      const start = ctx.cursor.i;
      try {
        const result = await fn();
        if (active && result !== false && ctx.cursor.i !== start && ctx.record) ctx.record(start);
        return active && result !== false;
      } catch (_) { return false; }
      finally { busy = false; drainNavigation(); }
    }
    function drainNavigation() {
      if (!active || paused || !started || busy || navigating || !pendingNavigation) return;
      const request = pendingNavigation;
      pendingNavigation = null; navigating = request;
      run(request.fn).then(ok => {
        if (ok) tasks.forEach(task => {
          if (task.repeat) { clearTimeout(task.id); task.id = null; task.remaining = task.ms; schedule(task); }
        });
        request.resolve(ok);
      }).finally(() => { navigating = null; drainNavigation(); });
    }
    function schedule(task) {
      if (!active || paused || task.running || (!started && !task.preparation)) return;
      task.due = Date.now() + task.remaining;
      task.id = setTimeout(async () => {
        task.id = null;
        if (!active || paused) return;
        if (task.repeat) {
          // Resume must not schedule a second callback while this one awaits decode.
          task.running = true;
          try { await run(task.fn); } finally { task.running = false; }
        } else { tasks.delete(task); task.fn(); }
        if (task.repeat && active) { task.remaining = task.ms; schedule(task); }
      }, task.remaining);
    }
    function add(fn, ms, repeat, preparation = false) {
      const task = { fn, ms, repeat, preparation, remaining: ms, id: null };
      tasks.add(task); schedule(task); return task;
    }
    function frame(fn) {
      const id = requestAnimationFrame(() => {
        frames.delete(id);
        if (!active) return;
        if (paused) { add(fn, 0, false); return; }
        fn();
      });
      frames.add(id);
    }
    function clear() {
      active = false; controller.abort();
      if (pendingNavigation) pendingNavigation.resolve(false);
      if (navigating) navigating.resolve(false);
      pendingNavigation = null;
      tasks.forEach(task => clearTimeout(task.id)); tasks.clear();
      frames.forEach(cancelAnimationFrame); frames.clear();
      waiters.splice(0).forEach(resolve => resolve(false));
    }
    const cleanup = () => clear();
    Object.defineProperty(cleanup, "ready", { get: () => ready });
    cleanup.pause = () => {
      if (paused || !active) return;
      paused = true;
      tasks.forEach(task => {
        if (task.id != null) { task.remaining = Math.max(0, task.due - Date.now()); clearTimeout(task.id); task.id = null; }
      });
    };
    cleanup.resume = () => {
      if (!paused || !active) return;
      paused = false; waiters.splice(0).forEach(resolve => resolve(true));
      drainNavigation();
      tasks.forEach(task => { if (task.id == null) schedule(task); });
    };
    return {
      load, publish, frame, cleanup,
      cancel: task => { if (task) { clearTimeout(task.id); task.id = null; tasks.delete(task); } },
      every: (fn, ms) => add(fn, ms, true), after: (fn, ms) => add(fn, ms, false),
      // Bridge deadlines run before readiness, but consume only active playback time.
      deadline: (fn, ms) => add(fn, ms, false, true),
      navigate(fn) {
        if (!active) return Promise.resolve(false);
        if (pendingNavigation) pendingNavigation.resolve(false);
        return new Promise(resolve => {
          pendingNavigation = { fn, resolve };
          drainNavigation();
        });
      },
      start(fn) {
        ready = run(fn).then(ok => {
          if (ok) {
            started = true; drainNavigation();
            tasks.forEach(task => { if (task.id == null) schedule(task); });
          }
          return ok;
        });
      },
    };
  }

  // Incoming-only dissolve shared by whole-photo scenes. The outgoing image stays
  // opaque until its replacement is ready, and owns no pixels after retirement.
  function makeSceneDissolve(ctx, t, roots, fadeMs, release) {
    let cur = 0, disposed = false, fading = null, admitted = false, paused = false;
    const now = () => typeof performance !== "undefined" ? performance.now() : Date.now();
    function retire(request) {
      if (disposed || paused || fading !== request || roots[cur] !== request.incoming) return;
      if (Number(getComputedStyle(request.incoming).opacity) !== 1) return;
      request.outgoing.classList.add("retired");
      request.outgoing.classList.remove("on");
      release(request.outgoing);
      request.incoming.ontransitionend = null;
      request.incoming.style.opacity = ""; request.incoming.style.transition = "";
      t.cancel(request.timer); fading = null; FADES.end(request);
    }
    function settle(request = fading) {
      if (!request || request !== fading || disposed || paused) return;
      request.incoming.style.transition = "none";
      request.incoming.style.opacity = "1";
      void request.incoming.offsetWidth;
      request.incoming.style.opacity = ""; request.incoming.style.transition = "";
      retire(request);
    }
    function hold(request) {
      request.remaining = Math.max(0, request.remaining - (now() - request.startedAt));
      const opacity = getComputedStyle(request.incoming).opacity;
      request.incoming.style.transition = "none";
      request.incoming.style.opacity = opacity;
      void request.incoming.offsetWidth;
      request.held = true;
    }
    const cleanup = () => {
      if (disposed) return;
      disposed = true; t.cleanup(); if (fading) FADES.end(fading); fading = null;
      roots.forEach(root => {
        root.ontransitionend = null; root.classList.add("retired"); release(root);
      });
    };
    Object.defineProperty(cleanup, "ready", { get: () => t.cleanup.ready });
    cleanup.pause = () => {
      if (disposed || paused) return;
      paused = true; t.cleanup.pause();
      roots.forEach(root => root.classList.add("ss-dissolve-paused"));
      if (fading) hold(fading);
    };
    cleanup.resume = () => {
      if (disposed || !paused) return;
      paused = false;
      roots.forEach(root => root.classList.remove("ss-dissolve-paused"));
      if (fading && fading.held) {
        fading.held = false; fading.startedAt = now();
        fading.incoming.style.transition = `opacity ${fading.remaining}ms ${EASE.fade}`;
        fading.incoming.style.opacity = "1";
      }
      t.cleanup.resume();
    };
    return {
      cleanup, active: () => !disposed,
      next() { settle(); return roots[1 - cur]; },
      present(incoming) {
        if (disposed) return;
        const outgoing = roots[cur];
        incoming.classList.remove("on"); incoming.classList.remove("retired");
        ctx.root.appendChild(incoming);
        cur = roots.indexOf(incoming);
        if (!admitted) {
          // The scheduler already supplies the whole-beat startup fade.
          incoming.style.transition = "none"; incoming.classList.add("on");
          void incoming.offsetWidth;
          incoming.style.transition = ""; admitted = true; return;
        }
        void incoming.offsetWidth;
        const request = { incoming, outgoing, timer: null, remaining: fadeMs,
          startedAt: now(), held: false };
        if (fading) FADES.end(fading);
        fading = request; FADES.begin(request);
        incoming.ontransitionend = event => {
          if (event.target === incoming && event.propertyName === "opacity") retire(request);
        };
        incoming.classList.add("on");
        request.timer = t.after(() => settle(request), fadeMs + 100);
        if (paused) hold(request);
      },
    };
  }

  // Only live native Filmstrip adapters register callbacks; legacy/browser modes bypass this.
  const nativeFilmstrips = new Map();
  const NATIVE_TIMEOUT_MS = 8000;
  SS.nativeFilmstripPrepared = (token, ok) => {
    const entry = nativeFilmstrips.get(token);
    if (entry && entry.preparation) entry.preparation.reply(ok === true);
  };
  SS.nativeFilmstripCommandDone = (token, requestId, ok) => {
    const entry = nativeFilmstrips.get(token), request = entry && entry.commands.get(requestId);
    if (request) request.reply(ok === true);
  };
  function nativeFilmstripAvailable(ctx, set) {
    try {
      const host = window.Android, photos = window.PHOTOS;
      return typeof ctx.nativeToken === "string" && /^[A-Za-z0-9_-]{1,64}$/.test(ctx.nativeToken) &&
        !nativeFilmstrips.has(ctx.nativeToken) && nativeFilmstrips.size < 3 &&
        Array.isArray(photos) && set.length >= 1 && set.length <= 12 &&
        new Set(set.map(p => p.id)).size === set.length &&
        set.every(p => Number.isInteger(p.id) && p.id >= 0 && p.id < photos.length && photos[p.id] === p) &&
        host && typeof host.supportsNativeFilmstrip === "function" && host.supportsNativeFilmstrip() === true &&
        typeof host.prepareNativeFilmstrip === "function" && typeof host.nativeFilmstripCommand === "function";
    } catch (_) { return false; }
  }
  function runNativeFilmstrip(ctx, set) {
    const t = makeTimers(ctx), token = ctx.nativeToken, host = window.Android;
    const entry = { preparation: null, commands: new Map() };
    let active = true, paused = false, requested = false, legacy = null, requestId = 0;
    nativeFilmstrips.set(token, entry);
    const nextId = () => { requestId = requestId % 2147483647 + 1; return requestId; };
    // Register before calling the bridge. Synchronous callbacks cannot override rejection.
    function wait(register, remove, start) {
      return new Promise(resolve => {
        let accepted = null, early = null, finished = false, timer = null;
        const request = {
          finish(ok) {
            if (finished) return;
            finished = true; t.cancel(timer); remove(request); resolve(active && ok);
          },
          reply(ok) { if (accepted === null) early = ok; else request.finish(accepted && ok); },
        };
        register(request);
        timer = t.deadline(() => request.finish(false), NATIVE_TIMEOUT_MS);
        try { accepted = start() === true; } catch (_) { accepted = false; }
        if (!accepted) request.finish(false);
        else if (early !== null) request.finish(early);
      });
    }
    function command(action) {
      if (!active || !requested) return Promise.resolve(false);
      const control = action === "pause" || action === "resume";
      if (control) Array.from(entry.commands.values()).filter(r => r.control).forEach(r => r.finish(false));
      if (entry.commands.size >= 2) return Promise.resolve(false);
      const id = nextId();
      const pending = wait(r => { r.control = control; entry.commands.set(id, r); },
        r => { if (entry.commands.get(id) === r) entry.commands.delete(id); },
        () => host.nativeFilmstripCommand(token, action, id));
      pending.cancel = () => { const request = entry.commands.get(id); if (request) request.finish(false); };
      return pending;
    }
    function retireNative() {
      if (nativeFilmstrips.get(token) === entry) nativeFilmstrips.delete(token);
      if (entry.preparation) entry.preparation.finish(false);
      Array.from(entry.commands.values()).forEach(r => r.finish(false));
      if (requested) {
        requested = false;
        try { host.nativeFilmstripCommand(token, "release", nextId()); } catch (_) {}
      }
    }
    const cleanup = () => {
      if (!active) return;
      active = false; delete cleanup.nativeToken;
      retireNative(); t.cleanup(); if (legacy) legacy();
    };
    cleanup.pause = () => {
      if (!active || paused) return;
      paused = true;
      if (legacy) legacy.pause(); else { t.cleanup.pause(); command("pause"); }
    };
    cleanup.resume = () => {
      if (!active || !paused) return;
      paused = false;
      if (legacy) legacy.resume(); else { t.cleanup.resume(); command("resume"); }
    };
    async function move(action) {
      return !!cleanup.nativeToken && await command(action) && await t.load([]);
    }
    cleanup.next = () => legacy ? legacy.next() : t.navigate(() => move("next"));
    cleanup.previous = () => legacy ? legacy.previous() : t.navigate(() => move("previous"));
    cleanup.restore = () => command("restore");
    t.every(() => move("next"), ctx.microMs);
    t.start(async () => {
      const ok = await wait(r => { entry.preparation = r; },
        r => { if (entry.preparation === r) entry.preparation = null; },
        () => { requested = true; return host.prepareNativeFilmstrip(token, JSON.stringify(set.map(p => p.id))); });
      return ok && await t.load([]);
    });
    const ready = t.cleanup.ready.then(async ok => {
      if (!active) return false;
      if (ok) { cleanup.nativeToken = token; return true; }
      retireNative(); t.cleanup();
      // The selection and cursor already belong to this scene. Never take a second set.
      legacy = runSelectedFilmstrip(ctx, set);
      if (paused) legacy.pause();
      const admitted = await legacy.ready;
      return active && admitted === true;
    });
    Object.defineProperty(cleanup, "ready", { get: () => ready });
    return cleanup;
  }

  // ---- Compact windows (the Portal Mini) -------------------------------------
  // One photo per beat follows the screen. Memories singles use SS.compactSingle: full-bleed when
  // the crop keeps >= 80 %, a full-height sweep for a panorama, else whole over its own blurred
  // backdrop in the zone above the clock. Ken Burns and Push pan along the long axis
  // (SS.compactPan). The Portal+ ("L") never reaches these helpers.
  const COMPACT_GEOMETRY = ["left", "top", "width", "height", "right", "bottom", "inset", "transform"];
  const compactClass = ctx => !!ctx.cls && ctx.cls !== "L";
  const knownShape = p => !!p && p.w > 0 && p.h > 0;
  function compactSize(ctx) {
    return { W: ctx.root.clientWidth || ctx.W, H: ctx.root.clientHeight || ctx.H };
  }
  function compactPlan(ctx, p) {
    if (!compactClass(ctx)) return null;
    const { W, H } = compactSize(ctx);
    return SS.compactSingle(aspect(p), W, H, ctx.cls);
  }
  function compactPanPlan(ctx, p, beat, push) {
    if (!compactClass(ctx)) return null;
    const { W, H } = compactSize(ctx);
    return SS.compactPan(aspect(p), W, H, ctx.cls, beat, push);
  }
  // Ask for the photo at the size it is drawn on a compact screen (shape known before the load),
  // else the mode's own request; the Portal+ request is untouched.
  function compactUrl(ctx, p, edge, plan) {
    if (!compactClass(ctx)) return ctx.url(p, edge);
    return ctx.url(p, edge, plan && knownShape(p) ? SS.compactDrawnEdge(plan, aspect(p)) : null);
  }
  // The backdrop is the photo's own small (320 px) copy, drawn once into a tiny canvas (the
  // downscale itself is the blur; the dimming is painted in) and only composited afterwards. A
  // live CSS blur on a full-screen layer is re-applied by the compositor on every frame.
  const PREBLUR_EDGE = 48, PREBLUR_DIM = "rgba(0,0,0,0.45)", PREBLUR_TIMEOUT_MS = 4000;
  function preblur(url, W, H) {
    let canvas = null, g = null;
    try {
      canvas = document.createElement("canvas");
      g = canvas && typeof canvas.getContext === "function" ? canvas.getContext("2d") : null;
    } catch (_) { g = null; }
    if (!g || typeof Image !== "function") return Promise.resolve(null);
    return new Promise(resolve => {
      const img = new Image();
      let timer = null;
      function finish(ok) {
        if (!img.onload) return;
        img.onload = img.onerror = null; clearTimeout(timer);
        let drawn = null;
        if (ok) try { drawn = drawPreblur(canvas, g, img, W, H); } catch (_) { drawn = null; }
        img.src = ""; resolve(drawn);
      }
      img.onload = () => finish(img.naturalWidth > 0 && img.naturalHeight > 0);
      img.onerror = () => finish(false);
      timer = setTimeout(() => finish(false), PREBLUR_TIMEOUT_MS);
      img.src = url;
    });
  }
  function drawPreblur(canvas, g, img, W, H) {
    const s = PREBLUR_EDGE / Math.max(W, H, 1);
    const cw = Math.max(2, Math.round(W * s)), ch = Math.max(2, Math.round(H * s));
    canvas.width = cw; canvas.height = ch;
    // Cover-crop the copy into the canvas, overscanned so the soft edge stays off-screen.
    const ia = img.naturalWidth / img.naturalHeight, over = 2;
    let dw = cw + over * 2, dh = dw / ia;
    if (dh < ch + over * 2) { dh = ch + over * 2; dw = dh * ia; }
    g.imageSmoothingEnabled = true;
    if ("imageSmoothingQuality" in g) g.imageSmoothingQuality = "high";
    if ("filter" in g) g.filter = "blur(1.5px) saturate(1.1)";
    g.drawImage(img, (cw - dw) / 2, (ch - dh) / 2, dw, dh);
    if ("filter" in g) g.filter = "none";
    g.fillStyle = PREBLUR_DIM; g.fillRect(0, 0, cw, ch);
    canvas.className = "ss-preblur-canvas";
    return canvas;
  }
  function compactFillOf(root) {
    const first = root.firstChild;
    return first && first.classList && first.classList.contains("ss-compact-fill") ? first : null;
  }
  function compactFill(root) {
    let fill = compactFillOf(root);
    if (!fill) { fill = el("ss-fill ss-compact-fill"); root.insertBefore(fill, root.firstChild); }
    return fill;
  }
  // Release the backdrop's pixels with its layer; a shipped (non-compact) photo has none.
  function hideCompactFill(root) {
    const fill = compactFillOf(root);
    if (!fill) return;
    fill.style.backgroundImage = "none"; fill.style.display = "none";
    if (fill.children && fill.children.length) { Array.from(fill.children).forEach(releaseCanvas); fill.innerHTML = ""; }
  }
  // A detached canvas keeps its backing store until collected; zero it so it is freed now.
  function releaseCanvas(canvas) {
    if (canvas && canvas.className === "ss-preblur-canvas") { canvas.width = 0; canvas.height = 0; }
  }
  // Only a layer that was laid out compact has anything to undo; the Portal+ DOM is untouched.
  function clearCompact(root, hero) {
    if (hero.classList.contains("ss-compact")) {
      COMPACT_GEOMETRY.forEach(k => { hero.style[k] = ""; });
      hero.classList.remove("ss-compact");
    }
    hideCompactFill(root);
  }
  // Lay out [hero] for [plan] and return the background size the photo needs. [backdrop] is
  // { url, canvas } for a whole photo: the pre-blurred canvas, else the CSS-blurred copy.
  function applyCompact(plan, root, hero, backdrop) {
    const r = plan.rect;
    hero.classList.add("ss-compact");
    Object.assign(hero.style, { inset: "auto", right: "auto", bottom: "auto", transform: "none",
      left: r.x + "px", top: r.y + "px", width: r.w + "px", height: r.h + "px" });
    if (plan.mode === "whole" && backdrop && backdrop.url) {
      const fill = compactFill(root);
      fill.style.display = "";
      if (fill.children && fill.children.length) {
        Array.from(fill.children).filter(c => c !== backdrop.canvas).forEach(releaseCanvas); fill.innerHTML = "";
      }
      if (backdrop.canvas) {
        fill.classList.add("ss-preblur"); fill.style.backgroundImage = "none";
        fill.appendChild(backdrop.canvas);
      } else { fill.classList.remove("ss-preblur"); bg(fill, backdrop.url, "cover"); }
    } else hideCompactFill(root);
    return plan.mode === "fill" ? "cover" : plan.mode === "whole" ? "contain" : "100% 100%";
  }
  // A sweep travels the panorama across the screen at full height over the photo's dwell.
  function compactSweep(ctx, hero, plan) {
    hero.style.setProperty("--sweep", plan.travel + "px");
    hero.style.willChange = "transform"; void hero.offsetWidth;
    hero.style.animation = `compactSweep ${ctx.motionMs}ms ease-in-out forwards`;
  }
  // Ken Burns / Push motion for a compact plan: a long-axis pan (or the matched photo's zoom).
  // A cached face, or motion turned off, holds a still frame: the pan rests on the face (or the
  // middle) and the cover crop is anchored on the face.
  function compactMotion(ctx, hero, plan, p) {
    hero.style.animation = "none"; hero.style.willChange = "auto";
    const still = ctx.photoMotion === false || hasFace(p);
    if (plan.mode === "fill") {
      faceBg(hero, p);
      if (still) return;
      hero.style.setProperty("--z0", plan.zoom[0]); hero.style.setProperty("--z1", plan.zoom[1]);
      hero.style.setProperty("--zx", plan.drift + "%");
      hero.style.willChange = "transform"; void hero.offsetWidth;
      hero.style.animation = `compactZoom ${ctx.motionMs}ms linear forwards`;
      return;
    }
    const axis = plan.axis === "y" ? "Y" : "X", travel = Math.min(plan.from, plan.to);
    if (still) {
      const { W, H } = compactSize(ctx);
      const focal = hasFace(p) ? (axis === "X" ? p.fx : p.fy) : .5;
      const view = axis === "X" ? W : H, size = axis === "X" ? plan.rect.w : plan.rect.h;
      const at = Math.min(0, Math.max(travel, view / 2 - focal * size));
      hero.style.transform = `translate${axis}(${at}px)`;
      return;
    }
    hero.style.setProperty("--pan-from", plan.from + "px"); hero.style.setProperty("--pan-to", plan.to + "px");
    hero.style.willChange = "transform"; void hero.offsetWidth;
    hero.style.animation = `compactPan${axis} ${ctx.motionMs}ms ease-in-out forwards`;
  }
  // Load the photo, then (now that its shape is known) the backdrop a whole photo needs.
  async function loadCompact(ctx, t, p, url, loaded = false, planner = compactPlan) {
    if (!loaded && !await t.load(url)) return null;
    const plan = planner(ctx, p);
    if (!plan) return { plan: null };
    if (plan.mode !== "whole") return { plan };
    const fillUrl = ctx.url(p, 320);
    if (!await t.load(fillUrl)) return null;
    const { W, H } = compactSize(ctx);
    const canvas = await preblur(fillUrl, W, H);
    return { plan, fillUrl, backdrop: { url: fillUrl, canvas } };
  }

  // Ken Burns / Push on a compact screen: one photo per beat panned along its long axis (or a
  // matched photo zoomed), full-bleed with no border or backdrop. The plan is drawn up again
  // once the photo is loaded, when an unknown shape has been measured.
  function compactPanStep(ctx, t, dissolve, layers, push) {
    let beat = 0;
    return async function (p) {
      const k = beat, u = compactUrl(ctx, p, 2200, compactPanPlan(ctx, p, k, push));
      if (!await t.load(u) || !dissolve.active()) return false;
      const plan = compactPanPlan(ctx, p, k, push);
      const root = dissolve.next(), layer = layers.find(l => l.root === root), hero = layer.hero;
      hero.classList.remove("ss-motion");
      bg(hero, u, applyCompact(plan, root, hero, null));
      compactMotion(ctx, hero, plan, p);
      beat = k + 1;
      dissolve.present(root);
    };
  }

  // ---- One photo, or a portrait pair, on the Portal+ ("L") -------------------
  // Ken Burns, Push and Memories singles: full-bleed when the crop keeps >= 80 % and the faces
  // (SS.largeSingle), else the whole photo over its own pre-blurred backdrop (the Mini's preblur,
  // no live filter). A portrait is paired with the next portrait of the deck (SS.takePartner)
  // at one height (SS.pairPlan). Everything is decoded and drawn before the dissolve starts.
  // Each photo is asked for at its drawn size (SS.largeDrawnEdge) at the top of its motion:
  // Ken Burns zooms to 1.15, stopping where a whole photo or pair touches the screen; the
  // drift of Push and Memories reaches 1.04. An unknown shape (no drawn size, undefined) gets
  // the copy it got before. Portal+ modes below pass a drawn size only on the Portal+, so a
  // compact screen keeps its own default (the display's long edge).
  const KEN_BURNS_MOTION = { zoom: 1.15, fitCapped: true }, DRIFT_MOTION = { zoom: 1.04, fitCapped: false };
  // A fill single's photo is copied into an accelerated canvas one band per frame before its
  // change (SS.paintStrips), so its upload is spread over ~16 frames instead of landing in the
  // dissolve's first one. The canvas holds the cover crop 1:1 and replaces the hero's CSS
  // background; the hero, its motion and the dissolve are unchanged. While it is painted the
  // canvas sits in a 1 px, 0.001-opacity corner (.ss-strip-warm), so each band is composited,
  // and so flushed to the GPU, in its own frame. Without canvas support, or when a step fails,
  // the photo is the CSS background as before.
  let stripOwner = null;
  function stripsSupported() {
    return typeof createImageBitmap === "function" && typeof requestAnimationFrame === "function" &&
      typeof Image === "function" && typeof SS.paintStrips === "function" && !!document.body;
  }
  function loadImage(url) {
    return new Promise(resolve => {
      const img = new Image();
      let timer = null;
      function finish(ok) {
        if (!img.onload) return;
        img.onload = img.onerror = null; clearTimeout(timer);
        if (!ok) img.src = "";
        resolve(ok ? img : null);
      }
      img.onload = () => finish(img.naturalWidth > 0 && img.naturalHeight > 0);
      img.onerror = () => finish(false);
      timer = setTimeout(() => finish(false), PREBLUR_TIMEOUT_MS);
      img.src = url;
    });
  }
  // A finished canvas in its warm corner, or null (the caller then uses the CSS background).
  async function prepareStrips(t, active, url, plan, W, H) {
    if (!stripsSupported()) return null;
    let canvas = null, g = null;
    try { canvas = document.createElement("canvas"); g = canvas.getContext && canvas.getContext("2d"); } catch (_) { g = null; }
    if (!g) return null;
    const began = Date.now();
    // One diagnostic line per photo (tag ImmortalEngine); never photo data.
    const report = (ok, what) => { try { console[ok ? "info" : "warn"]("photo_strips " + what); } catch (_) {} };
    const img = await loadImage(url);
    if (!img || !active()) { if (img) img.src = ""; else report(false, "load_failed"); return null; }
    const layout = SS.stripLayout(img.naturalWidth, img.naturalHeight, W, H, plan.position, SS.STRIP_BUDGET_BYTES);
    let source = null;
    if (layout) try { source = await createImageBitmap(img); } catch (_) { source = null; }
    img.src = "";
    if (!source || !active()) {
      if (source) source.close(); else report(false, layout ? "bitmap_failed" : "no_layout");
      return null;
    }
    const strips = { canvas, warm: el("ss-strip-warm"), layout, url, lost: false };
    canvas.className = "ss-strip-canvas";
    canvas.width = layout.canvas.w; canvas.height = layout.canvas.h;
    canvas.addEventListener("contextlost", () => { strips.lost = true; });
    strips.warm.appendChild(canvas); document.body.appendChild(strips.warm);
    const me = {};
    let ok = false;
    try {
      ok = await SS.paintStrips(g, source, layout, {
        frame: () => new Promise(resolve => requestAnimationFrame(() => resolve(true)))
          .then(() => t.publish(() => active())),
        crop: (s, x, y, w, h) => createImageBitmap(s, x, y, w, h),
        gate: () => {
          if (FADES.busy() || (stripOwner && stripOwner !== me)) return false;
          stripOwner = me; return true;
        },
      });
    } catch (_) { ok = false; }
    finally { if (stripOwner === me) stripOwner = null; source.close(); }
    if (!ok || strips.lost || !active()) {
      releaseStrips(strips);
      if (active()) report(false, strips.lost ? "context_lost" : "paint_failed");
      return null;
    }
    report(true, layout.slices.length + " bands " + layout.canvas.w + "x" + layout.canvas.h + " " + (Date.now() - began) + " ms");
    return strips;
  }
  // Free a canvas's pixels now (a detached canvas keeps them until collected).
  function releaseStrips(strips) {
    if (!strips) return;
    strips.canvas.width = 0; strips.canvas.height = 0;
    strips.canvas.remove(); strips.warm.remove();
  }
  function releaseHeroStrips(hero) {
    if (!hero || !hero.classList.contains("ss-strips")) return;
    hero.classList.remove("ss-strips");
    Array.from(hero.children).filter(c => c.className === "ss-strip-canvas").forEach(c => {
      c.width = 0; c.height = 0; c.remove();
    });
  }
  // Move the finished canvas into [hero]. If the GPU context is lost later, the hero falls back
  // to its CSS background (one ordinary upload) rather than showing an empty canvas.
  function showStrips(hero, strips, position) {
    const c = strips.canvas, css = strips.layout.css;
    hero.style.backgroundImage = "none";
    hero.classList.add("ss-strips");
    Object.assign(c.style, { left: css.left + "px", top: css.top + "px", width: css.width + "px", height: css.height + "px" });
    hero.appendChild(c); strips.warm.remove();
    c.addEventListener("contextlost", () => {
      if (c.parentNode !== hero) return;
      releaseHeroStrips(hero);
      bg(hero, strips.url, "cover"); if (position) hero.style.backgroundPosition = position;
    });
  }
  // [onScreen] are the seeds still visible (the outgoing layer). Resolves null when the scene
  // was disposed or a load failed; a computed backdrop is then released at once.
  async function prepareLarge(ctx, t, active, deck, p, onScreen, edge, motion) {
    const { W, H } = compactSize(ctx);
    const partner = SS.takePartner(deck, ctx.cursor, p, onScreen);
    const ps = partner ? [p, partner] : [p];
    const pair = partner ? SS.pairPlan(ps.map(aspect), W, H) : null;
    const asked = pair || (knownShape(p) ? SS.largeSingle(p, W, H) : null);
    const urls = ps.map((x, i) => ctx.url(x, edge, asked && knownShape(x) ?
      SS.largeDrawnEdge(asked, aspect(x), i, W, H, motion.zoom, motion.fitCapped) : undefined));
    if (!await t.load(urls) || !active()) return null;
    // A shape measured by the load is honoured: plan again now.
    const plan = pair || SS.largeSingle(p, W, H);
    try { console.info("photo_plan " + (plan.pair ? "pair" : plan.mode)); } catch (_) {}
    if (plan.mode !== "whole") {
      const strips = plan.mode === "fill" ? await prepareStrips(t, active, urls[0], plan, W, H) : null;
      if (!active()) { releaseStrips(strips); return null; }
      return { ps, urls, plan, backdrop: null, strips };
    }
    const fillUrl = ctx.url(p, 320);
    if (!await t.load(fillUrl) || !active()) return null;
    const canvas = await preblur(fillUrl, W, H);
    if (!active()) { releaseCanvas(canvas); return null; }
    return { ps, urls, plan, backdrop: { url: fillUrl, canvas } };
  }
  // Publish a prepared beat once playback runs; a beat dropped before it shows frees its backdrop.
  function publishLarge(t, active, prep, render) {
    return t.publish(() => {
      if (!active()) { releaseCanvas(prep.backdrop && prep.backdrop.canvas); releaseStrips(prep.strips); return false; }
      return render();
    }).then(result => {
      if (result === false && prep.backdrop) releaseCanvas(prep.backdrop.canvas);
      if (result === false) releaseStrips(prep.strips);
      return result;
    });
  }
  function paintLarge(prep, root, hero) {
    releaseHeroStrips(hero);
    const plan = prep.plan, size = applyCompact(plan, root, hero, prep.backdrop);
    hero.classList.toggle("ss-whole", plan.mode === "whole" && !plan.pair);
    if (plan.pair) {
      hero.style.backgroundImage = prep.urls.map(u => `url("${u}")`).join(", ");
      hero.style.backgroundSize = plan.widths.map(w => `${w}px ${plan.rect.h}px`).join(", ");
      hero.style.backgroundPosition = "left center, right center";
      hero.style.backgroundRepeat = "no-repeat";
    } else if (prep.strips && !prep.strips.lost && prep.strips.canvas.width > 0) {
      showStrips(hero, prep.strips, plan.position);
    } else {
      releaseStrips(prep.strips);
      bg(hero, prep.urls[0], size);
      if (plan.position) hero.style.backgroundPosition = plan.position;
    }
  }
  // The scale at which a whole photo (or pair) would touch the screen edge; null for a fill.
  function largeFit(ctx, plan) {
    if (plan.mode !== "whole") return null;
    const { W, H } = compactSize(ctx), r = plan.rect;
    return Math.min(W / r.w, H / r.h);
  }
  const stillPhotos = ps => ps.some(hasFace);
  const seedsOf = ps => ps.map(p => p.seed);

  const MODES = {};

  // ---- 01 Ken Burns (single, quiet matte, keeps moving through fade) --------
  MODES.kenBurns = { name: "Cinematic Ken Burns", run(ctx, deck) {
    const t = makeTimers(ctx);
    const layers = [mk(), mk()]; layers.forEach(l => ctx.root.appendChild(l.root));
    function mk() {
      const root = el("ss-layer ken-burns retired"), hero = el("ss-hero", root);
      hero.style.animation = "none"; hero.style.willChange = "auto";
      return { root, hero };
    }
    const dissolve = makeSceneDissolve(ctx, t, layers.map(l => l.root), 2200, root => {
      const hero = layers.find(l => l.root === root).hero;
      hero.style.backgroundImage = "none"; hero.style.animation = "none";
      hero.style.willChange = "auto"; hero.classList.remove("ss-motion");
      releaseHeroStrips(hero); hideCompactFill(root);
    });
    let onScreen = [];
    async function step() {
      const p = SS.take(deck, ctx.cursor, ["any"])[0];
      if (compactClass(ctx)) return compactStep(p);
      const prep = await prepareLarge(ctx, t, dissolve.active, deck, p, onScreen, 2200, KEN_BURNS_MOTION);
      if (!prep) return false;
      return publishLarge(t, dissolve.active, prep, () => {
        const root = dissolve.next(), L = layers.find(l => l.root === root);
        L.hero.classList.remove("ss-motion");
        paintLarge(prep, root, L.hero);
        L.hero.style.animation = "none"; L.hero.style.willChange = "auto";
        if (!stillPhotos(prep.ps)) {
          // Keep the same random draws so motion preference cannot alter later photo selection.
          kbVars(L.hero, p, largeFit(ctx, prep.plan));
          if (ctx.photoMotion !== false) {
            L.hero.style.willChange = "transform";
            void L.hero.offsetWidth; L.hero.style.animation = "";
          }
        }
        onScreen = seedsOf(prep.ps);
        dissolve.present(root);
      });
    }
    const compactStep = compactPanStep(ctx, t, dissolve, layers, false);
    t.start(step); t.every(step, ctx.photoMs);
    return dissolve.cleanup;
  }};

  // ---- 02 Diptych (orientation-aware pairing) -------------------------------
  MODES.diptych = { name: "Orientation Diptych", run(ctx, deck) {
    const t = makeTimers(ctx);
    const frames = [el("ss-frame retired"), el("ss-frame retired")];
    frames.forEach(f => ctx.root.appendChild(f));
    const dissolve = makeSceneDissolve(ctx, t, frames, 1800, frame => { frame.innerHTML = ""; });
    // A quiet, whole-photo spread: no second decoded copy or viewport-sized blur per pane.
    function containCell(imageUrl) {
      const c = el("ss-cell contain"), im = el("ss-img", c); bg(im, imageUrl, "contain");
      if (ctx.photoMotion === false) { im.style.animation = "none"; im.style.willChange = "auto"; }
      return c;
    }
    // Upright (the Mini): a column. Two landscapes stack, each the full width; otherwise one
    // photo stands alone (a portrait fills the tall frame). Never two narrow side-by-side bands.
    function uprightSet(c, roll) {
      if (roll < 0.6 && c.landscape >= 2) return SS.take(deck, ctx.cursor, ["landscape", "landscape"]);
      if (c.portrait) return SS.take(deck, ctx.cursor, ["portrait"]);
      return SS.take(deck, ctx.cursor, ["any"]);
    }
    async function step() {
      const c = SS.counts(deck); const roll = Math.random(), upright = ctx.cls === "CP";
      let ps;
      if (upright) ps = uprightSet(c, roll);
      else if (roll < 0.28 && c.pano) ps = SS.take(deck, ctx.cursor, ["pano"]);
      else if (roll < 0.6 && c.landscape) ps = SS.take(deck, ctx.cursor, ["landscape"]);
      else ps = SS.take(deck, ctx.cursor, ["portrait", "portrait"]);
      // The Portal+ asks for each photo at its contained size in its cell (SS.diptychDrawnEdges).
      const { W, H } = compactSize(ctx), shaped = !compactClass(ctx) && ps.every(knownShape);
      const drawn = shaped ? SS.diptychDrawnEdges(ps.map(aspect), W, H) : [];
      const resources = ps.map((p, i) => ctx.url(p, 1800, shaped ? drawn[i] : undefined));
      if (!await t.load(resources) || !dissolve.active()) return false;
      const f = dissolve.next();
      f.innerHTML = "";
      f.classList.toggle("column", upright);
      resources.forEach(url => f.appendChild(containCell(url)));
      dissolve.present(f);
    }
    t.start(step); t.every(step, ctx.photoMs);
    return dissolve.cleanup;
  }};

  // ---- 03 Framed print (art mode) -------------------------------------------
  MODES.framed = { name: "Framed Print", run(ctx, deck) {
    const t = makeTimers(ctx);
    el("ss-room", ctx.root);
    const arts = [mk(), mk()]; arts.forEach(a => ctx.root.appendChild(a.root));
    function mk() {
      const root = el("ss-art retired"); const edge = el("ss-frameEdge", root); const mat = el("ss-mat", edge);
      const bevel = el("ss-bevel", mat); const photo = el("ss-print", bevel);
      photo.style.animation = "none"; photo.style.willChange = "auto";
      return { root, edge, mat, photo, source: null };
    }
    const dissolve = makeSceneDissolve(ctx, t, arts.map(a => a.root), 2400, root => {
      const art = arts.find(a => a.root === root), photo = art.photo;
      art.source = null;
      art.edge.style.animation = "none"; art.edge.style.willChange = "auto";
      photo.style.backgroundImage = "none"; photo.style.animation = "none";
      photo.style.willChange = "auto";
    });
    function printLimits() {
      const area = SS.photoLayoutArea(ctx);
      const decoration = Math.min(6, area.w * .08, area.h * .08);
      return { area, decoration, maxW: area.w - decoration * 2, maxH: area.h - decoration * 2 };
    }
    function printSize(p, { maxW, maxH } = printLimits()) {
      const ar = aspect(p);
      let w = maxW, h = w / ar;
      if (h > maxH) { h = maxH; w = h * ar; }
      return { w, h };
    }
    // The print only lifts (translateY), so its drawn long edge is its laid-out size.
    function printEdge(p) { const { w, h } = printSize(p); return Math.max(w, h); }
    function resizePrint(art, limits = printLimits()) {
      if (!art.source) return;
      const { area, decoration } = limits, { w, h } = printSize(art.source, limits);
      const width = w + "px", height = h + "px";
      if (art.photo.style.width !== width) art.photo.style.width = width;
      if (art.photo.style.height !== height) art.photo.style.height = height;
      art.edge.style.padding = decoration / 3 + "px";
      art.mat.style.padding = decoration * 2 / 3 + "px";
      art.edge.style.position = "absolute";
      art.edge.style.left = area.x + (area.w - w - decoration * 2) / 2 + "px";
      const top = area.y + (area.h - h - decoration * 2) / 2;
      art.edge.style.top = top + "px";
      art.edge.style.setProperty("--paper-lift", -Math.min(2, top) + "px");
    }
    // Keep admitted photos/resources; coalesce viewport events into one pausable frame.
    let resizePending = false;
    function onResize() {
      if (resizePending || !dissolve.active()) return;
      resizePending = true;
      t.frame(() => {
        resizePending = false;
        if (dissolve.active()) {
          const limits = printLimits();
          arts.forEach(art => resizePrint(art, limits));
        }
      });
    }
    if (typeof window.addEventListener === "function") window.addEventListener("resize", onResize);
    async function step() {
      const p = SS.take(deck, ctx.cursor, ["any"])[0];
      const u = ctx.url(p, 1800, !compactClass(ctx) && knownShape(p) ? printEdge(p) : undefined);
      if (!await t.load(u) || !dissolve.active()) return false;
      const root = dissolve.next(), A = arts.find(a => a.root === root);
      A.source = p; resizePrint(A);
      // Frame geometry already follows the photo's aspect. Cached face hints keep
      // the complete print still; do not introduce a focal crop or another decode.
      bg(A.photo, u, "contain");
      A.photo.style.animation = "none"; A.photo.style.willChange = "auto";
      A.edge.style.animation = "none"; A.edge.style.willChange = "auto";
      A.edge.style.animationDelay = (-Math.random() * 10) + "s";
      if (ctx.photoMotion !== false && !hasFace(p)) {
        A.edge.style.willChange = "transform";
        A.edge.style.animation = "paperPrintLift 22s ease-in-out infinite alternate";
      }
      dissolve.present(root);
    }
    t.start(step); t.every(step, ctx.photoMs);
    const cleanup = () => {
      if (typeof window.removeEventListener === "function") window.removeEventListener("resize", onResize);
      dissolve.cleanup();
    };
    Object.defineProperty(cleanup, "ready", { get: () => dissolve.cleanup.ready });
    cleanup.pause = dissolve.cleanup.pause; cleanup.resume = dissolve.cleanup.resume;
    return cleanup;
  }};

  // ---- 04 Living gallery wall (justified rows + swaps) ----------------------
  // The doodle tile at 1x (Portal), 1.125x (Portal+) and 2x: the same pixels as the vector at that
  // scale, decoded once instead of re-rasterised per tile (styles.css .gallery-wall).
  const GALLERY_DOODLE = 'image-set(url("photo-doodles-dark-480.png") 1x, ' +
    'url("photo-doodles-dark-540.png") 1.125x, url("photo-doodles-dark-960.png") 2x)';
  MODES.galleryWall = { name: "Living Gallery Wall", run(ctx, deck) {
    const t = makeTimers(ctx), wall = el("ss-wall gallery-wall", ctx.root), cells = [];
    let disposed = false;
    function replace(cell, photo, url) {
      if (disposed || !cell.dissolve.active()) return;
      const img = cell.dissolve.next();
      bg(img, url, "contain"); img.style.animation = "none"; img.style.willChange = "auto";
      // The opaque photo owner also covers outgoing pixels in its letterbox.
      img.style.backgroundImage += ", " + GALLERY_DOODLE;
      img.style.backgroundSize = "contain, 480px 360px";
      img.style.backgroundPosition = "center, left top";
      img.style.backgroundRepeat = "no-repeat, repeat";
      cell.photo = photo; cell.dissolve.present(img);
    }
    // Compact screens (the Mini) scale the tile count, rows and area to the screen (SS.galleryPlan)
    // and never animate the cells' layout properties; the Portal+ keeps its measured layout.
    const compact = compactClass(ctx);
    if (compact) wall.classList.add("compact");
    function geometry() {
      const { W, H } = compactSize(ctx), plan = compact ? SS.galleryPlan(ctx.cls, W, H) : null;
      if (plan) return { area: plan.area, pad: 0, gutter: plan.gutter, targetH: plan.target,
        count: plan.count, maxRows: plan.maxRows };
      const area = SS.photoLayoutArea(ctx);
      return { area, pad: Math.min(16, area.w * .1, area.h * .1), gutter: 12,
        targetH: Math.max(240, area.h * .4), count: 10, maxRows: 10 };
    }
    function layout(preparedPhotos) {
      const { area, pad, gutter, targetH, count, maxRows } = geometry(), W = area.w, H = area.h;
      const pick = preparedPhotos || []; const seen = new Set(); let g = 0;
      if (!preparedPhotos) {
        while (pick.length < count && g < deck.length * 2) { const p = deck[ctx.cursor.i++ % deck.length]; g++; if (!seen.has(p.seed)) { seen.add(p.seed); pick.push(p); } }
      }
      const res = window.justifiedLayout(pick, W - pad * 2, { targetRowHeight: targetH, gutter, maxRows });
      // Fit both dimensions. Filling height alone pushes complete outer photos
      // off-screen; quiet matte space is preferable to clipping the gallery.
      const sy = Math.min(1, (H - pad * 2) / Math.max(res.height, 1));
      const offY = pad + ((H - pad * 2) - res.height * sy) / 2;
      const rowWidths = new Map();
      res.cells.forEach(c => rowWidths.set(c.top, Math.max(rowWidths.get(c.top) || 0, c.left + c.width)));
      return res.cells.map(c => ({
        ...c, left: area.x + c.left * sy + (W - rowWidths.get(c.top) * sy) / 2,
        top: area.y + c.top * sy + offY, width: c.width * sy, height: c.height * sy,
      }));
    }
    function position(root, slot) {
      root.style.left = slot.left + "px"; root.style.top = slot.top + "px";
      root.style.width = slot.width + "px"; root.style.height = slot.height + "px";
    }
    let resizePending = false;
    function onResize() {
      if (disposed || resizePending) return;
      resizePending = true;
      t.frame(() => {
        resizePending = false;
        if (disposed || !cells.length) return;
        layout(cells.map(cell => cell.photo)).forEach((slot, i) => position(cells[i].el, slot));
      });
    }
    if (typeof window.addEventListener === "function") window.addEventListener("resize", onResize);
    function render(target) {
      target.forEach(slot => {
        const root = el("ss-gcell", wall);
        position(root, slot);
        const layers = [0, 1].map(() => {
          const img = el("ss-img gallery-photo retired", root);
          img.style.backgroundImage = "none"; img.style.animation = "none";
          img.style.willChange = "auto"; return img;
        });
        const dissolve = makeSceneDissolve({ ...ctx, root }, t, layers, 1100, img => {
          img.style.backgroundImage = "none"; img.style.animation = "none";
          img.style.willChange = "auto";
        });
        const cell = { el: root, photo: null, dissolve }; cells.push(cell);
        replace(cell, slot.photo, slot.url);
      });
    }
    async function rebuild() {
      // The Portal+ asks for each tile at its laid-out size when every shape is known (a
      // measured shape could re-justify the rows), else the mode's request.
      const planned = layout(), sized = !compact && planned.every(s => knownShape(s.photo));
      const slots = planned.map(s => ({ ...s,
        url: ctx.url(s.photo, 1200, sized ? SS.containEdge(aspect(s.photo), s.width, s.height) : undefined) }));
      if (!await t.load(slots.map(s => s.url)) || disposed) return false;
      const prepared = new Map(slots.map(s => [s.photo, s.url]));
      render(layout(slots.map(s => s.photo)).map(s => ({ ...s, url: prepared.get(s.photo) })));
    }
    async function swap() {
      if (!cells.length || disposed) return;
      const onWall = cells.map(c => c.photo.seed);
      if (!SS.unused(deck, onWall).length) return;
      const cell = cells[Math.floor(Math.random() * cells.length)], wallSet = new Set(onWall);
      let photo = null;
      for (let k = 0; k < deck.length; k++) {
        const candidate = deck[(ctx.cursor.i + k) % deck.length];
        if (!wallSet.has(candidate.seed) && SS.bucket(candidate) === SS.bucket(cell.photo)) {
          photo = candidate; ctx.cursor.i += k + 1; break;
        }
      }
      if (!photo) photo = SS.take(deck, ctx.cursor, ["any"], onWall)[0];
      // A swap is contained in the cell already on the wall: never larger than that cell.
      const w = parseFloat(cell.el.style.width), h = parseFloat(cell.el.style.height);
      const url = ctx.url(photo, 1200, !compact && w > 0 && h > 0 ? Math.max(w, h) : undefined);
      if (!await t.load(url) || disposed) return false;
      replace(cell, photo, url);
    }
    t.start(rebuild); t.every(swap, ctx.microMs);
    const cleanup = () => {
      if (disposed) return;
      disposed = true; t.cleanup(); cells.forEach(c => c.dissolve.cleanup());
      if (typeof window.removeEventListener === "function") window.removeEventListener("resize", onResize);
    };
    Object.defineProperty(cleanup, "ready", { get: () => t.cleanup.ready });
    cleanup.pause = () => {
      if (disposed) return;
      cells.forEach(c => c.dissolve.cleanup.pause()); t.cleanup.pause();
    };
    cleanup.resume = () => {
      if (disposed) return;
      cells.forEach(c => c.dissolve.cleanup.resume()); t.cleanup.resume();
    };
    return cleanup;
  }};

  // ---- 05 Cinematic push -----------------------------------------------------
  MODES.push = { name: "Cinematic Push", run(ctx, deck) {
    const t = makeTimers(ctx); const layers = [mk(), mk()]; layers.forEach(l => ctx.root.appendChild(l.root));
    function mk() {
      const root = el("ss-layer cinematic-push retired"), hero = el("ss-hero", root);
      hero.style.animation = "none"; hero.style.willChange = "auto";
      return { root, hero };
    }
    const dissolve = makeSceneDissolve(ctx, t, layers.map(l => l.root), 2200, root => {
      const hero = layers.find(l => l.root === root).hero;
      hero.style.backgroundImage = "none"; hero.style.animation = "none";
      hero.style.willChange = "auto";
      releaseHeroStrips(hero); hideCompactFill(root);
    });
    let onScreen = [];
    async function step() {
      const p = SS.take(deck, ctx.cursor, ["any"])[0];
      if (compactClass(ctx)) return compactStep(p);
      const prep = await prepareLarge(ctx, t, dissolve.active, deck, p, onScreen, 2200, DRIFT_MOTION);
      if (!prep) return false;
      return publishLarge(t, dissolve.active, prep, () => {
        const root = dissolve.next(), layer = layers.find(l => l.root === root), hero = layer.hero;
        paintLarge(prep, root, hero); hero.style.animation = "none"; hero.style.willChange = "auto";
        if (ctx.photoMotion !== false && !stillPhotos(prep.ps)) {
          hero.style.willChange = "transform"; void hero.offsetWidth;
          hero.style.animation = `cinematicDrift ${ctx.motionMs}ms linear forwards`;
        }
        onScreen = seedsOf(prep.ps);
        dissolve.present(root);
      });
    }
    const compactStep = compactPanStep(ctx, t, dissolve, layers, true);
    t.start(step); t.every(step, ctx.photoMs);
    return dissolve.cleanup;
  }};

  // ---- 06 Memories (single + occasional titled collage beat) ----------------
  MODES.memories = { name: "Memories", run(ctx, deck) {
    const t = makeTimers(ctx), scenes = [0, 1].map(() => el("ss-memory-scene retired", ctx.root));
    const resources = new Map(scenes.map(scene => [scene, []]));
    const collages = new Map();
    const dissolve = makeSceneDissolve(ctx, t, scenes, 2000, scene => {
      resources.get(scene).forEach(image => {
        image.style.backgroundImage = "none"; image.style.animation = "none";
        image.style.willChange = "auto"; releaseHeroStrips(image);
      });
      resources.set(scene, []); collages.delete(scene); hideCompactFill(scene); scene.innerHTML = "";
      scene.style.opacity = ""; scene.style.transition = "";
    });
    let beat = 0;
    function photo(parent, p, url, single, compact) {
      const image = el((single ? "ss-hero" : "ss-img") + " memory-photo", parent);
      const plan = single && compact && compact.plan;
      bg(image, url, plan ? applyCompact(plan, parent, image, compact.backdrop) : "contain");
      image.style.animation = "none"; image.style.willChange = "auto";
      if (plan && plan.mode === "fill") faceBg(image, p);
      if (plan && plan.mode === "sweep") { if (ctx.photoMotion !== false) compactSweep(ctx, image, plan); }
      else if (single && ctx.photoMotion !== false && !hasFace(p)) {
        image.style.animation = `cinematicDrift ${ctx.motionMs}ms linear forwards`;
        image.style.willChange = "transform";
      }
      return image;
    }
    // The collage is one exact-fit grid (SS.trioLayout) under its caption, clear of the clock.
    const upright = ctx.cls === "CP", CAPTION = upright ? 96 : 72, CAPTION_LIFT = 52;
    function layoutCollage(ps, images) {
      const W = ctx.root.clientWidth || ctx.W, H = ctx.root.clientHeight || ctx.H;
      if (ps.length === 1) {
        const image = images[0], tile = image.parentNode;
        const boxW = W * .68, boxH = H * .72, a = aspect(ps[0]);
        const imageH = Math.max(1, Math.min(boxH - 24, (boxW - 24) / a));
        const width = imageH * a + 24, height = imageH + 24;
        Object.assign(tile.style, { left: (W * .16 + (boxW - width) / 2) + "px", top: (H * .14 + (boxH - height) / 2) + "px",
          width: width + "px", height: height + "px" });
        return;
      }
      const boxes = collageBoxes(ps);
      // Each card has its photo's exact aspect: cover only absorbs sub-pixel rounding.
      boxes.forEach((b, i) => {
        Object.assign(images[i].parentNode.style, { left: b.x + "px", top: b.y + "px",
          width: b.w + "px", height: b.h + "px", transform: "none" });
        images[i].style.backgroundSize = "cover";
      });
      const scene = images[0].parentNode.parentNode;
      const caption = Array.from(scene.children).find(node => node.classList.contains("ss-memory-caption"));
      scene.classList.add("trio");
      if (caption && boxes.length) Object.assign(caption.style, {
        left: Math.min(...boxes.map(b => b.x)) + "px",
        top: Math.max(8, Math.min(...boxes.map(b => b.y)) - CAPTION_LIFT) + "px" });
    }
    function collageBoxes(ps) {
      const area = SS.photoLayoutArea(ctx), gap = compactClass(ctx) ? SS.TRIO_GAP.compact : SS.TRIO_GAP.L;
      const grid = { x: area.x, y: area.y + CAPTION, w: area.w, h: Math.max(1, area.h - CAPTION) };
      return SS.trioLayout(ps.map(aspect), grid, gap);
    }
    // A Portal+ collage card has its photo's exact aspect and no motion: ask for the card's size.
    function collageEdges(ps) {
      if (compactClass(ctx) || ps.length < 2 || !ps.every(knownShape)) return ps.map(() => undefined);
      return collageBoxes(ps).map(b => Math.max(b.w, b.h));
    }
    function renderCollage(scene, ps, urls) {
      const title = el("ss-memory-caption", scene);
      title.textContent = upright ? "On this day" : "Photo memories";
      if (upright) title.classList.add("upright");
      const images = ps.map((p, i) => photo(el("ss-memory-card", scene), p, urls[i], false));
      layoutCollage(ps, images); collages.set(scene, { ps, images });
      return images;
    }
    let resizePending = false;
    function onResize() {
      if (resizePending || !dissolve.active()) return;
      resizePending = true;
      t.frame(() => {
        resizePending = false;
        if (!dissolve.active()) return;
        collages.forEach(({ ps, images }) => layoutCollage(ps, images));
      });
    }
    if (typeof window.addEventListener === "function") window.addEventListener("resize", onResize);
    async function step() {
      if (!dissolve.active()) return false;
      const nextBeat = beat + 1, collage = nextBeat % 6 === 0;
      const ps = SS.take(deck, ctx.cursor, new Array(SS.slots(deck, collage ? 3 : 1)).fill("any"));
      if (!collage && !compactClass(ctx)) return largeStep(ps[0], nextBeat);
      const urls = await t.publish(() => {
        const drawn = collage ? collageEdges(ps) : null;
        return ps.map((p, i) => collage ? ctx.url(p, 1100, drawn[i]) :
          compactUrl(ctx, p, 2200, compactPlan(ctx, p)));
      });
      if (urls === false || !await t.load(urls) || !dissolve.active()) return false;
      const compact = collage ? null : await loadCompact(ctx, t, ps[0], urls[0], true);
      if (!collage && !compact || !dissolve.active()) {
        if (compact && compact.backdrop) releaseCanvas(compact.backdrop.canvas);
        return false;
      }
      return t.publish(() => {
        if (!dissolve.active()) return false;
        const scene = dissolve.next();
        const images = collage ? renderCollage(scene, ps, urls) : [photo(scene, ps[0], urls[0], true, compact)];
        resources.set(scene, images); beat = nextBeat; onScreen = seedsOf(ps); dissolve.present(scene);
      });
    }
    // A Portal+ single (or portrait pair) keeps the shipped gentle push and its 2 s dissolve.
    let onScreen = [];
    async function largeStep(p, nextBeat) {
      const prep = await prepareLarge(ctx, t, dissolve.active, deck, p, onScreen, 2200, DRIFT_MOTION);
      if (!prep) return false;
      return publishLarge(t, dissolve.active, prep, () => {
        const scene = dissolve.next(), image = el("ss-hero memory-photo", scene);
        paintLarge(prep, scene, image);
        image.style.animation = "none"; image.style.willChange = "auto";
        if (ctx.photoMotion !== false && !stillPhotos(prep.ps)) {
          image.style.animation = `cinematicDrift ${ctx.motionMs}ms linear forwards`;
          image.style.willChange = "transform";
        }
        resources.set(scene, [image]); beat = nextBeat; onScreen = seedsOf(prep.ps); dissolve.present(scene);
      });
    }
    t.start(step); t.every(step, ctx.photoMs);
    const cleanup = () => {
      if (typeof window.removeEventListener === "function") window.removeEventListener("resize", onResize);
      dissolve.cleanup();
    };
    Object.defineProperty(cleanup, "ready", { get: () => dissolve.cleanup.ready });
    cleanup.pause = dissolve.cleanup.pause; cleanup.resume = dissolve.cleanup.resume;
    return cleanup;
  }};

  // ---- 07 Panorama drift -----------------------------------------------------
  MODES.panorama = { name: "Panorama Drift", run(ctx, deck) {
    const t = makeTimers(ctx), layers = [mk(), mk()];
    const dissolve = makeSceneDissolve(ctx, t, layers.map(layer => layer.root), 1800, root => {
      const layer = layers.find(layer => layer.root === root), pan = layer.pan;
      layer.photo = null;
      pan.style.backgroundImage = "none"; pan.style.animation = "none";
      pan.style.willChange = "auto"; pan.style.transform = "none";
    });
    function mk() {
      const root = el("ss-layer panorama-drift retired", ctx.root), pan = el("ss-pan", root);
      pan.style.animation = "none"; pan.style.willChange = "auto";
      return { root, pan, photo: null };
    }
    function strip(p) {
      const W = ctx.root.clientWidth || ctx.W, H = ctx.root.clientHeight || ctx.H;
      const rawAspect = aspect(p), ratio = Number.isFinite(rawAspect) && rawAspect > 0 ? rawAspect : W / H;
      // Bound the moving strip instead of making an extreme panorama many screens wide.
      // Fit its full height on matte; transform motion still reaches both horizontal edges.
      const baseWidth = Math.min(W * 2, Math.max(W, H * ratio));
      const faceCrop = SS.faceCrop(p, baseWidth, W);
      const width = faceCrop ? faceCrop.size : baseWidth;
      return { W, H, ratio, faceCrop, width, height: Math.min(H, width / ratio) };
    }
    // The strip only translates: the Portal+ asks for its photo at the strip's contained size.
    function stripEdge(p) {
      const { ratio, width, height } = strip(p);
      return SS.containEdge(ratio, width, height);
    }
    function layout(layer, url) {
      const p = layer.photo, pan = layer.pan;
      const { W, H, faceCrop, width, height } = strip(p), travel = (W - width) / width * 100;
      pan.style.width = width + "px"; pan.style.height = height + "px";
      pan.style.top = (H - height) / 2 + "px";
      if (url !== undefined) bg(pan, url, "contain");
      pan.style.setProperty("--shift", travel + "%");
      const moving = ctx.photoMotion !== false && width > W && !hasFace(p);
      // Unlike zoom, the full panoramic sweep must finish before the next photo.
      const duration = Math.min(ctx.motionMs, ctx.photoMs);
      pan.style.animation = moving ? `dolly ${duration}ms linear forwards` : "none";
      pan.style.willChange = moving ? "transform" : "auto";
      // A still strip uses the cached face or the middle of the admitted crop.
      pan.style.transform = faceCrop ? `translateX(${-faceCrop.offset / width * 100}%)` :
        hasFace(p) || ctx.photoMotion === false ?
        `translateX(${travel * SS.panStart(hasFace(p) ? p.fx : .5, width / W * 100)}%)` : "none";
    }
    let resizePending = false;
    function onResize() {
      if (resizePending || !dissolve.active()) return;
      resizePending = true;
      t.frame(() => {
        resizePending = false;
        if (!dissolve.active()) return;
        layers.forEach(layer => { if (layer.photo) layout(layer); });
      });
    }
    if (typeof window.addEventListener === "function") window.addEventListener("resize", onResize);
    async function step() {
      if (!dissolve.active()) return false;
      const p = SS.take(deck, ctx.cursor, [SS.counts(deck).pano ? "pano" : "landscape"])[0];
      const url = await t.publish(() => ctx.url(p, 2400,
        !compactClass(ctx) && knownShape(p) ? stripEdge(p) : undefined));
      if (url === false || !await t.load(url) || !dissolve.active()) return false;
      return t.publish(() => {
        if (!dissolve.active()) return false;
        const root = dissolve.next(), layer = layers.find(layer => layer.root === root);
        layer.photo = p; layout(layer, url);
        dissolve.present(root);
      });
    }
    t.start(step); t.every(step, ctx.photoMs);
    const cleanup = () => {
      if (typeof window.removeEventListener === "function") window.removeEventListener("resize", onResize);
      dissolve.cleanup();
    };
    Object.defineProperty(cleanup, "ready", { get: () => dissolve.cleanup.ready });
    cleanup.pause = dissolve.cleanup.pause; cleanup.resume = dissolve.cleanup.resume;
    return cleanup;
  }};

  // ---- 08 Polaroid scatter ---------------------------------------------------
  MODES.polaroid = { name: "Polaroid Scatter", run(ctx, deck) {
    const t = makeTimers(ctx), table = el("ss-table", ctx.root), MAX = SS.slots(deck, 6);
    let live = [], disposed = false, paused = false, fading = null;
    let area = SS.photoLayoutArea(ctx), resizePending = false;
    const now = () => typeof performance !== "undefined" ? performance.now() : Date.now();
    let COLS = Math.min(MAX, Math.ceil(Math.sqrt(MAX * ctx.W / ctx.H))), ROWS = Math.ceil(MAX / COLS);
    let freeCells = window.shuffled(Array.from({ length: COLS * ROWS }, (_, i) => i)).slice(0, MAX);
    // Six live prints and one reusable incoming owner. Resting paper never asks
    // for permanent promotion; no guessed date, event or place belongs on it.
    const pool = Array.from({ length: MAX + 1 }, () => {
      const card = el("ss-polaroid retired", table), image = el("ss-ph", card);
      card.style.willChange = "auto"; card.style.transition = "none";
      image.style.backgroundImage = "none"; image.style.animation = "none";
      image.style.willChange = "auto";
      return { card, image, photo: null, cell: null, target: null };
    });
    function release(print) {
      print.card.ontransitionend = null;
      print.card.classList.add("retired"); print.card.classList.remove("on");
      print.card.style.transition = "none"; print.card.style.opacity = "0";
      print.card.style.transform = "none"; print.card.style.willChange = "auto";
      print.image.style.backgroundImage = "none"; print.photo = null;
    }
    function layout(print, photo, cell, retained = false) {
      const cellW = area.w / COLS, cellH = area.h / ROWS;
      const raw = aspect(photo), ratio = Number.isFinite(raw) && raw > 0 ? raw : 1;
      const pad = Math.max(8, Math.min(28, Math.min(cellW, cellH) * .045));
      const edge = Math.max(4, Math.min(22, Math.min(cellW, cellH) * .035)), chin = edge * 2.5;
      const rotation = retained ? print.rotation : (Math.random() * 2 - 1) * 8;
      const angle = Math.abs(rotation) * Math.PI / 180;
      const cos = Math.cos(angle), sin = Math.sin(angle), baseW = ratio >= 1 ? 600 : 480, baseH = baseW / ratio;
      // Fit the rotated PAPER envelope, not only its unrotated photograph.
      const scale = Math.max(Number.EPSILON, Math.min(1,
        (cellW - pad * 2 - cos * edge * 2 - sin * (edge + chin)) / (cos * baseW + sin * baseH),
        (cellH - pad * 2 - sin * edge * 2 - cos * (edge + chin)) / (sin * baseW + cos * baseH)));
      const pw = baseW * scale, ph = baseH * scale, W = pw + edge * 2, H = ph + edge + chin;
      const boundW = cos * W + sin * H, boundH = sin * W + cos * H;
      const cx = area.x + (cell % COLS) * cellW, cy = area.y + Math.floor(cell / COLS) * cellH;
      if (!retained) { print.biasX = Number(Math.random()); print.biasY = Number(Math.random()); }
      const x = cx + pad + boundW / 2 + print.biasX * Math.max(0, cellW - pad * 2 - boundW);
      const y = cy + pad + boundH / 2 + print.biasY * Math.max(0, cellH - pad * 2 - boundH);
      print.card.style.width = W + "px"; print.card.style.height = H + "px";
      print.card.style.padding = `${edge}px ${edge}px ${chin}px`;
      print.card.style.left = (x - W / 2) + "px"; print.card.style.top = (y - H / 2) + "px";
      print.image.style.width = pw + "px"; print.image.style.height = ph + "px";
      print.target = `rotate(${rotation}deg) scale(1)`; print.rotation = rotation;
    }
    function onResize() {
      if (disposed || resizePending) return;
      resizePending = true;
      t.frame(() => {
        resizePending = false;
        if (disposed) return;
        area = SS.photoLayoutArea(ctx);
        if (compactClass(ctx)) regrid();
        pool.forEach(print => { if (print.photo) layout(print, print.photo, print.cell, true); });
      });
    }
    // Compact (the Mini turns): the grid follows the window instead of staying the boot one. The
    // prints keep their photos, tilt and in-cell bias; they are dealt into the new grid in order.
    function regrid() {
      const { W, H } = compactSize(ctx), grid = SS.polaroidGrid(MAX, W, H);
      if (grid.cols === COLS && grid.rows === ROWS) return;
      COLS = grid.cols; ROWS = grid.rows;
      const cells = window.shuffled(Array.from({ length: COLS * ROWS }, (_, i) => i));
      live.forEach(print => { print.cell = cells.shift(); });
      pool.forEach(print => {
        if (print.photo && !live.includes(print)) print.cell = print.cell % (COLS * ROWS);
      });
      freeCells = cells.slice(0, Math.max(0, MAX - live.length));
    }
    if (typeof window.addEventListener === "function") window.addEventListener("resize", onResize);
    function settle(request = fading) {
      if (disposed || paused || !request || fading !== request) return;
      const incoming = request.incoming.card;
      incoming.style.transition = "none"; incoming.style.opacity = "1";
      incoming.style.transform = request.incoming.target;
      incoming.style.willChange = "auto"; incoming.ontransitionend = null;
      t.cancel(request.timer); release(request.outgoing); fading = null;
    }
    function present(incoming, outgoing) {
      const card = incoming.card;
      card.classList.remove("retired"); card.classList.add("on");
      card.style.transition = "none"; card.style.opacity = outgoing ? "0" : "1";
      card.style.transform = outgoing ? `rotate(${incoming.rotation}deg) scale(.9)` : incoming.target;
      table.appendChild(card);
      if (!outgoing) return; // The first decoded composition needs no internal dissolve.
      void card.offsetWidth;
      const request = { incoming, outgoing, remaining: 900, startedAt: now(), timer: null };
      fading = request;
      card.style.willChange = "transform, opacity";
      card.style.transition = `opacity 900ms ${EASE.outExpo}, transform 900ms ${EASE.outExpo}`;
      card.style.opacity = "1"; card.style.transform = incoming.target;
      card.ontransitionend = event => {
        if (event.target === card && event.propertyName === "opacity" &&
          Number(getComputedStyle(card).opacity) === 1) settle(request);
      };
      request.timer = t.after(() => settle(request), 1000);
    }
    async function drop(initial = false) {
      const excluded = live.length >= MAX ? live.slice(1) : live;
      const p = SS.take(deck, ctx.cursor, ["any"], excluded.map(x => x.photo.seed))[0];
      const url = await t.publish(() => ctx.url(p, 1400,
        !compactClass(ctx) && knownShape(p) ? SS.polaroidDrawnEdge(aspect(p)) : undefined));
      if (url === false || !await t.load(url) || disposed) return false;
      return t.publish(() => {
        if (disposed) return false;
        settle();
        const outgoing = live.length >= MAX ? live.shift() : null;
        if (outgoing) freeCells.push(outgoing.cell);
        const print = pool.find(candidate => !candidate.photo);
        const cell = freeCells.splice(Math.floor(Math.random() * freeCells.length), 1)[0];
        layout(print, p, cell); bg(print.image, url, "contain");
        print.photo = p; print.cell = cell; live.push(print);
        present(print, initial ? null : outgoing);
      });
    }
    t.start(async () => { for (let i = 0; i < MAX; i++) if (await drop(true) === false) return false; });
    t.every(() => drop(), ctx.microMs);
    const cleanup = () => {
      if (disposed) return;
      disposed = true; t.cleanup(); fading = null;
      if (typeof window.removeEventListener === "function") window.removeEventListener("resize", onResize);
      pool.forEach(release); live = []; table.remove();
    };
    Object.defineProperty(cleanup, "ready", { get: () => t.cleanup.ready });
    cleanup.pause = () => {
      if (disposed || paused) return;
      paused = true; t.cleanup.pause();
      if (!fading) return;
      const card = fading.incoming.card, style = getComputedStyle(card);
      const opacity = style.opacity, transform = style.transform;
      fading.remaining = Math.max(0, fading.remaining - (now() - fading.startedAt));
      card.style.transition = "none"; card.style.opacity = opacity; card.style.transform = transform;
      card.style.willChange = "auto";
    };
    cleanup.resume = () => {
      if (disposed || !paused) return;
      paused = false;
      if (fading) {
        const card = fading.incoming.card;
        fading.startedAt = now();
        card.style.transition = `opacity ${fading.remaining}ms ${EASE.outExpo}, transform ${fading.remaining}ms ${EASE.outExpo}`;
        card.style.willChange = "transform, opacity";
        card.style.opacity = "1"; card.style.transform = fading.incoming.target;
      }
      t.cleanup.resume();
    };
    return cleanup;
  }};

  // ---- 09 Mosaic wall (proportional whole photos, choreographed) -------------
  // Invisible layout slots own two photo-sized frames. Decode/publication/retirement
  // stays with the existing owners; a replacement reflows from its decoded shape.
  MODES.mosaic = { name: "Mosaic Wall", run(ctx, deck) {
    const t = makeTimers(ctx), wall = el("ss-wall spotlit mosaic-wall", ctx.root);
    let cells = [], disposed = false, paused = false, spot = -1;
    function applySpot(cell) {
      cell.c.classList.toggle("spot", ctx.photoMotion !== false && cell.spot && !hasFace(cell.photo));
      cell.layers.forEach(layer => {
        const active = cell.spot && layer.moves && !paused;
        layer.image.style.animationPlayState = active ? "running" : "paused";
        layer.image.style.willChange = active ? "transform" : "auto";
      });
    }
    function createCell(rect) {
      const c = el("ss-mcell mosaic-cell", wall);
      c.style.left = rect.x + "px"; c.style.top = rect.y + "px";
      c.style.width = rect.w + "px"; c.style.height = rect.h + "px";
      const layers = [0, 1].map(() => {
        const pane = el("ss-mosaic-pane retired", c), image = el("ss-img mosaic-photo", pane);
        image.style.backgroundImage = "none"; image.style.animation = "none";
        image.style.willChange = "auto"; image.style.animationPlayState = "paused";
        return { pane, image, photo: null, moves: false };
      });
      const dissolve = makeSceneDissolve({ ...ctx, root: c }, t, layers.map(layer => layer.pane), 1100, pane => {
        const layer = layers.find(layer => layer.pane === pane);
        layer.image.style.backgroundImage = "none"; layer.image.style.animation = "none";
        layer.image.style.willChange = "auto"; layer.image.style.transform = "none";
        layer.image.style.animationPlayState = "paused"; layer.moves = false; layer.photo = null;
      });
      return { c, rect, layers, dissolve, photo: null, spot: false };
    }
    function setImg(cell, p, url) {
      const pane = cell.dissolve.next(), layer = cell.layers.find(layer => layer.pane === pane), image = layer.image;
      layer.photo = p;
      image.className = "ss-img mosaic-photo";
      bg(image, url, "contain"); layer.moves = false;
      image.style.animation = "none";
      image.style.transform = "none";
      cell.photo = p; applySpot(cell); cell.dissolve.present(pane);
    }
    function reflow() {
      if (disposed || !cells.length) return;
      const area = SS.photoLayoutArea(ctx);
      const boxes = SS.photoLayoutRows(cells.map(cell => cell.photo || {}), area.w, area.h,
        Math.min(area.w, area.h) * .018);
      cells.forEach((cell, i) => {
        const rect = boxes[i]; cell.rect = rect;
        cell.c.style.left = area.x + rect.x + "px"; cell.c.style.top = area.y + rect.y + "px";
        cell.c.style.width = rect.w + "px"; cell.c.style.height = rect.h + "px";
        cell.c.style.setProperty("--paper-lift", -Math.min(2, area.y + rect.y) + "px");
        cell.layers.forEach(layer => {
          if (!layer.photo) return;
          const raw = aspect(layer.photo), ratio = Number.isFinite(raw) && raw > 0 ? raw : 1;
          const edge = Math.min(6, rect.w * .08, rect.h * .08);
          let w = rect.w - edge * 2, h = w / ratio;
          if (h > rect.h - edge * 2) { h = rect.h - edge * 2; w = h * ratio; }
          layer.pane.style.left = (rect.w - w - edge * 2) / 2 + "px";
          layer.pane.style.top = (rect.h - h - edge * 2) / 2 + "px";
          layer.pane.style.right = "auto"; layer.pane.style.bottom = "auto";
          layer.pane.style.width = w + edge * 2 + "px"; layer.pane.style.height = h + edge * 2 + "px";
          layer.image.style.left = edge + "px"; layer.image.style.top = edge + "px";
          layer.image.style.right = "auto"; layer.image.style.bottom = "auto";
          layer.image.style.width = w + "px"; layer.image.style.height = h + "px";
        });
      });
    }
    let resizePending = false;
    function onResize() {
      if (resizePending || disposed) return;
      resizePending = true;
      t.frame(() => { resizePending = false; reflow(); });
    }
    if (typeof window.addEventListener === "function") window.addEventListener("resize", onResize);
    function wantFor(cellA) { return cellA < 0.85 ? "portrait" : cellA > 1.8 ? "pano" : "landscape"; }
    function onWall() { return cells.map(x => x.photo && x.photo.seed).filter(Boolean); }
    async function prepare(pairs) {
      const urls = await t.publish(() => pairs.map(pair => ctx.url(pair.photo, 1200)));
      if (urls === false || !await t.load(urls) || disposed) return false;
      return t.publish(() => {
        if (disposed) return false;
        pairs.forEach((pair, i) => setImg(pair.cell, pair.photo, urls[i]));
        reflow();
      });
    }
    async function build() {
      const selected = SS.take(deck, ctx.cursor, new Array(SS.slots(deck, 6)).fill("any"));
      const slots = selected.map(photo => ({ rect: { x: 0, y: 0, w: 1, h: 1 }, photo }));
      if (await t.publish(() => {
        if (disposed) return false;
        slots.forEach(slot => { slot.cell = createCell(slot.rect); cells.push(slot.cell); });
      }) === false) return false;
      // Build the full composition before readiness, with only two simultaneous decodes.
      for (let i = 0; i < slots.length; i += 2)
        if (await prepare(slots.slice(i, i + 2)) === false) return false;
      return t.publish(spotlight);
    }
    async function swap() {
      if (disposed || !cells.length) return false;
      if (SS.unused(deck, onWall()).length) {
        const cell = cells[Math.floor(Math.random() * cells.length)];
        const photo = SS.take(deck, ctx.cursor, [wantFor(cell.rect.w / cell.rect.h)], onWall())[0];
        return prepare([{ cell, photo }]);
      }
      const groups = {};
      cells.forEach(c => { const k = wantFor(c.rect.w / c.rect.h); (groups[k] = groups[k] || []).push(c); });
      const eligible = Object.values(groups).filter(g => g.length >= 2);
      if (!eligible.length) return;
      const [a, b] = window.shuffled(eligible[Math.floor(Math.random() * eligible.length)]).slice(0, 2);
      return prepare([{ cell: a, photo: b.photo }, { cell: b, photo: a.photo }]);
    }
    function spotlight() {
      if (disposed || !cells.length) return false;
      if (spot >= 0) { cells[spot].spot = false; applySpot(cells[spot]); }
      spot = (spot + 1) % cells.length;
      cells[spot].spot = true; applySpot(cells[spot]);
    }
    t.start(build);
    if (SS.slots(deck, 6) > 1) { t.every(spotlight, ctx.ambientMs); t.every(swap, ctx.microMs); }
    const cleanup = () => {
      if (disposed) return;
      disposed = true; t.cleanup(); cells.forEach(cell => cell.dissolve.cleanup());
      if (typeof window.removeEventListener === "function") window.removeEventListener("resize", onResize);
      cells = []; wall.remove();
    };
    Object.defineProperty(cleanup, "ready", { get: () => t.cleanup.ready });
    cleanup.pause = () => {
      if (disposed || paused) return;
      paused = true; t.cleanup.pause();
      const transforms = cells.map(cell => getComputedStyle(cell.c).transform);
      cells.forEach((cell, i) => {
        cell.c.style.transition = "none"; cell.c.style.transform = transforms[i];
        cell.dissolve.cleanup.pause(); applySpot(cell);
      });
    };
    cleanup.resume = () => {
      if (disposed || !paused) return;
      paused = false;
      cells.forEach(cell => {
        cell.c.style.transition = ""; cell.c.style.transform = "";
        applySpot(cell); cell.dissolve.cleanup.resume();
      });
      t.cleanup.resume();
    };
    return cleanup;
  }};

  // ---- 10 Filmstrip coverflow ------------------------------------------------
  MODES.filmstrip = { name: "Filmstrip Coverflow", run(ctx, deck) {
    const set = SS.take(deck, ctx.cursor, new Array(SS.slots(deck, 12)).fill("any"));
    return nativeFilmstripAvailable(ctx, set) ? runNativeFilmstrip(ctx, set) : runSelectedFilmstrip(ctx, set);
  }};
  function runSelectedFilmstrip(ctx, set) {
    const t = makeTimers(ctx); const bdA = el("ss-bd", ctx.root), bdB = el("ss-bd", ctx.root); bdB.style.opacity = "0"; const track = el("ss-track", ctx.root);
    // Upright (the Mini): a vertical strip of width-sized cards (SS.verticalCard). Its geometry is
    // not the bounded horizontal one, so it keeps the original full admission.
    const vertical = ctx.cls === "CP";
    if (vertical) track.classList.add("vertical");
    const items = [];
    let center = 0, top = false, admitted = false, bounded = false, disposed = false, preparation = null, viewportBound = 0;
    // The unchanged CSS scales at most 1.12 and has a 70px black shadow blur.
    // 512 local pixels deliberately overbound its finite raster support, spread
    // and offset; 2px additionally cover flex/subpixel rounding over twelve slots.
    const MAX_SCALE = 1.12, SHADOW_PAD = 512, ROUNDING_PAD = 2;
    function knownGeometry() {
      if (vertical) return false;
      try {
        if (!(Number.isFinite(ctx.W) && ctx.W > 0 && Number.isFinite(ctx.H) && ctx.H > 0) ||
            items.some(s => !(Number.isFinite(s.w) && s.w > 0 && Number.isFinite(s.off) &&
              Number.isFinite(s.photo.w) && s.photo.w > 0 &&
              Number.isFinite(s.photo.h) && s.photo.h > 0))) return false;
        // Native WebView layout can recover from a small initial measurement or
        // swap orientation. Card widths/offsets stay fixed; admit the horizontal
        // exposure for either full-display orientation before declaring ready.
        const screen = window.screen;
        if (!screen || ![screen.width, screen.height].every(n => Number.isFinite(n) && n > 0)) return false;
        viewportBound = Math.max(screen.width, screen.height);
        if (ctx.W > viewportBound || ctx.H > viewportBound) return false;
        const root = ctx.root.getBoundingClientRect(), tr = track.getBoundingClientRect(), ts = getComputedStyle(track);
        if (![root.width, root.height, root.left, tr.left, tr.height].every(Number.isFinite) ||
            Math.abs(root.width - ctx.W) > 1 || Math.abs(root.height - ctx.H) > 1 ||
            getComputedStyle(ctx.root).transform !== "none" || ts.position !== "absolute" ||
            ts.display !== "flex" || ts.flexDirection !== "row" || ts.alignItems !== "center" ||
            ts.gap !== "60px" || Math.abs(tr.left - root.left - ctx.W / 2) > 1 ||
            Math.abs(tr.height - ctx.H) > 1) return false;
        return items.every(s => {
          const p = getComputedStyle(s.el), r = s.el.getBoundingClientRect();
          return Math.abs(parseFloat(p.width) - s.w) < 1 && Math.abs(parseFloat(p.height) - ctx.H * .62) < 1 &&
            p.transform === "matrix(0.7, 0, 0, 0.7, 0, 0)" &&
            p.boxShadow === "rgba(0, 0, 0, 0.9) 0px 30px 70px -20px" &&
            p.transitionProperty === "transform, opacity, filter" &&
            p.transitionDuration === "1.4s, 1.4s, 1.4s" &&
            p.transitionTimingFunction === "cubic-bezier(0.22, 1, 0.36, 1), ease, ease" &&
            Math.abs(r.left - root.left - (ctx.W / 2 + s.off - s.w * .35)) < ROUNDING_PAD &&
            Math.abs(r.width - s.w * .7) < 1 && Math.abs(r.height - ctx.H * .62 * .7) < 1;
        });
      } catch (_) { return false; } // Unknown geometry retains original full admission.
    }
    function needed(s, from, to) {
      if (!bounded) return true;
      const radius = (s.w / 2 + SHADOW_PAD) * MAX_SCALE + ROUNDING_PAD;
      const left = viewportBound / 2 + s.off - Math.max(from, to) - radius;
      const right = viewportBound / 2 + s.off - Math.min(from, to) + radius;
      return right >= 0 && left <= viewportBound;
    }
    async function prepare(urls) {
      const controller = new AbortController();
      preparation = controller;
      try {
        await Promise.all(Array.from(new Set(urls)).map(url => window.preloadDecoded(url, controller.signal)));
      } catch (_) { return false; }
      finally {
        // Failure/disposal cancels siblings before another serialized batch starts.
        controller.abort();
        if (preparation === controller) preparation = null;
      }
      // Reuse the timer owner's pause/disposal gate without decoding a second time.
      return !disposed && await t.load([]);
    }
    async function go(i) {
      if (disposed || !items[i]) return false;
      const from = admitted ? items[center].off : 0, plannedTo = items[i].off;
      // Retain all previously prepared images. Their earlier admission envelope
      // also covers interrupted CSS motion; wrap pans include intermediate cards.
      const batch = items.filter(s => !s.prepared && needed(s, from, plannedTo))
        .map(s => ({ item: s, url: ctx.url(s.photo, 1400) }));
      const u = ctx.url(items[i].photo, 1000);
      if (!await prepare(batch.map(s => s.url).concat(u)) || disposed) return false;
      if (!admitted && vertical) {
        let top = 0;
        items.forEach(s => {
          const c = SS.verticalCard(aspect(s.photo), ctx.W, ctx.H);
          s.el.style.width = c.w + "px"; s.el.style.height = c.h + "px";
          s.img.style.width = c.w + "px"; s.w = c.w; s.off = top + c.h / 2; top += c.h + 60;
        });
      } else if (!admitted) {
        // Unknown geometry uses full admission above. Recover ratios from those
        // same display decodes before publishing the strip, not in a later jump.
        let left = 0;
        items.forEach(s => {
          const w = ctx.H * .62 * aspect(s.photo);
          if (w !== s.w) { s.el.style.width = w + "px"; s.img.style.width = w + "px"; }
          s.w = w; s.off = left + w / 2; left += w + 60;
        });
        bounded = knownGeometry();
      }
      const to = items[i].off;
      // Commit one complete batch after decode/pause/ownership gates. Never
      // re-resolve at publication, or replace/clear an already-live image.
      batch.forEach(({ item, url }) => { bg(item.img, url); item.prepared = true; });
      items.forEach((s, k) => s.el.classList.toggle("center", k === i));
      track.style.transform = vertical ? `translateY(${-to}px)` : `translateX(${-to}px)`;
      const b = top ? bdA : bdB, o = top ? bdB : bdA;
      bg(b, u); b.style.opacity = "1"; o.style.opacity = "0";
      top = !top; center = i; admitted = true;
    }
    function loop() { return go((center + 1) % items.length); }
    t.start(async () => {
      let left = 0;
      set.forEach(p => {
        const card = vertical ? SS.verticalCard(aspect(p), ctx.W, ctx.H) : null;
        const s = el("ss-slide", track), h = card ? card.h : ctx.H * .62, w = card ? card.w : h * aspect(p);
        s.style.width = w + "px";
        if (card) s.style.height = h + "px";
        const im = el("ss-img", s); im.style.width = w + "px"; im.style.height = "100%";
        im.style.backgroundSize = "cover"; im.style.backgroundPosition = "center"; im.style.backgroundRepeat = "no-repeat";
        items.push({ el: s, img: im, w, off: left + (card ? h : w) / 2, photo: p, prepared: false });
        left += (card ? h : w) + 60;
      });
      bounded = knownGeometry();
      return go(0);
    });
    t.every(loop, ctx.microMs);
    const cleanup = () => { disposed = true; if (preparation) preparation.abort(); t.cleanup(); };
    Object.defineProperty(cleanup, "ready", { get: () => t.cleanup.ready });
    cleanup.pause = t.cleanup.pause; cleanup.resume = t.cleanup.resume;
    cleanup.next = () => t.navigate(() => go((center + 1) % items.length));
    cleanup.previous = () => t.navigate(() => go((center + items.length - 1) % items.length));
    return cleanup;
  }

  // ---- 11 Cinema letterbox ---------------------------------------------------
  MODES.cinema = { name: "Cinema Letterbox", run(ctx, deck) {
    const t = makeTimers(ctx), frame = el("ss-scope", ctx.root);
    const bars = [el("ss-bar top", ctx.root), el("ss-bar bot", ctx.root)];
    let disposed = false;
    const layers = [0, 1].map(() => {
      const root = el("ss-layer cinema-layer retired", frame), img = el("ss-img ss-cinema-photo", root);
      const grain = el("ss-grain", img);
      img.style.backgroundImage = "none"; img.style.animation = "none"; img.style.willChange = "auto";
      return { root, img, grain, photo: null };
    });
    const dissolve = makeSceneDissolve({ ...ctx, root: frame }, t, layers.map(layer => layer.root), 2200, root => {
      const layer = layers.find(layer => layer.root === root), image = layer.img;
      layer.photo = null;
      image.style.backgroundImage = "none"; image.style.animation = "none";
      image.style.willChange = "auto"; image.style.transform = "none";
    });
    function layoutImage(layer, u) {
      const p = layer.photo; if (!p) return;
      const image = layer.img, viewportW = ctx.root.clientWidth || ctx.W,
        viewportH = ctx.root.clientHeight || ctx.H, scopeH = viewportH * SS.CINEMA_SCOPE;
    const raw = aspect(p), ratio = Number.isFinite(raw) && raw > 0 ? raw : 1;
    const wide = ratio > SS.CINEMA_WIDE, W = wide ? viewportW : Math.min(viewportW, scopeH * ratio), H = wide ? scopeH : W / ratio;
    image.style.width = W + "px"; image.style.height = H + "px";
    image.style.left = ((viewportW - W) / 2) + "px"; image.style.top = ((scopeH - H) / 2) + "px";
    image.style.right = "auto"; image.style.bottom = "auto";
    if (u !== undefined) {
      image.style.transform = "none"; image.style.animation = "none"; image.style.willChange = "auto";
      bg(image, u, wide ? "cover" : "contain");
    }
    if (wide && hasFace(p)) {
      // Keep the immersive crop, but center the cached focal point rather than
      // confusing CSS edge alignment with the focal point's image coordinate.
      const renderedW = Math.max(viewportW, scopeH * ratio), renderedH = renderedW / ratio;
      // Compare the cover constraints directly; division roundoff can make
      // a height fitted exactly to scopeH appear to overflow it.
      const vertical = viewportW > scopeH * ratio;
      const faceCrop = SS.faceCrop(p, vertical ? renderedH : renderedW,
        vertical ? scopeH : viewportW, vertical);
      if (faceCrop) {
        const safeWidth = vertical ? faceCrop.size * ratio : viewportW;
        const safeHeight = vertical ? scopeH : faceCrop.size / ratio;
        image.style.width = safeWidth + "px";
        image.style.height = safeHeight + "px";
        image.style.left = (viewportW - safeWidth) / 2 + "px";
        image.style.top = (scopeH - safeHeight) / 2 + "px";
        image.style.backgroundPosition = vertical ? `50% ${faceCrop.position * 100}%` :
          `${faceCrop.position * 100}% 50%`;
      } else {
        image.style.backgroundPosition =
          `${SS.panStart(p.fx, renderedW / viewportW * 100) * 100}% ${SS.panStart(p.fy, renderedH / scopeH * 100) * 100}%`;
      }
    } else if (u !== undefined && wide && ctx.photoMotion !== false) {
      image.style.willChange = "transform";
      image.style.animation = `cinematicDrift ${ctx.motionMs}ms linear forwards`;
    }
    }
    let resizePending = false;
    function onResize() {
      if (disposed || resizePending) return;
      resizePending = true;
      t.frame(() => {
        resizePending = false;
        if (!disposed && dissolve.active()) layers.forEach(layer => layoutImage(layer));
      });
    }
    if (typeof window.addEventListener === "function") window.addEventListener("resize", onResize);
    async function step() {
      const c = SS.counts(deck), p = SS.take(deck, ctx.cursor, [c.pano ? "pano" : "landscape"])[0];
      // Portal+: a contained photo is asked for at its drawn size (SS.cinemaDrawnEdge).
      const u = await t.publish(() => ctx.url(p, 2400, !compactClass(ctx) && knownShape(p) ?
        SS.cinemaDrawnEdge(aspect(p), ctx.root.clientWidth || ctx.W, ctx.root.clientHeight || ctx.H) ?? undefined :
        undefined));
      if (u === false || !await t.load(u) || disposed) return false;
      return t.publish(() => {
        if (disposed || !dissolve.active()) return false;
        const root = dissolve.next(), image = layers.find(layer => layer.root === root).img;
        const layer = layers.find(layer => layer.root === root);
        layer.photo = p; layoutImage(layer, u);
        dissolve.present(root);
      });
    }
    t.start(step); t.every(step, ctx.photoMs);
    const cleanup = () => {
      if (disposed) return;
      disposed = true; dissolve.cleanup();
      layers.forEach(layer => { layer.grain.style.backgroundImage = "none"; });
      if (typeof window.removeEventListener === "function") window.removeEventListener("resize", onResize);
      frame.remove(); bars.forEach(bar => bar.remove());
    };
    Object.defineProperty(cleanup, "ready", { get: () => t.cleanup.ready });
    cleanup.pause = dissolve.cleanup.pause; cleanup.resume = dissolve.cleanup.resume;
    return cleanup;
  }};

  // ---- 12 Hero + sidebar -----------------------------------------------------
  MODES.heroSidebar = { name: "Hero + Sidebar", run(ctx, deck) {
    const t = makeTimers(ctx), backdrop = el("ss-hero-sidebar", ctx.root);
    const stage = el("ss-hs", backdrop), heroCol = el("ss-col", stage), cells = [];
    const count = SS.slots(deck, 3);
    // Upright (the Mini) the hero spans the width on top, the side photos share the row below.
    const upright = ctx.cls === "CP";
    let disposed = false;
    function makeCell(column, edge) {
      const root = el("ss-cell contain", column);
      const layers = [0, 1].map(() => {
        const pane = el("ss-pane retired", root), image = el("ss-img", pane);
        image.style.backgroundImage = "none"; image.style.animation = "none";
        image.style.willChange = "auto"; return { pane, image };
      });
      const dissolve = makeSceneDissolve({ ...ctx, root }, t, layers.map(l => l.pane), 1100, pane => {
        const image = layers.find(l => l.pane === pane).image;
        image.style.backgroundImage = "none"; image.style.animation = "none";
        image.style.willChange = "auto";
        pane.style.opacity = ""; pane.style.transition = "";
      });
      const cell = { photo: null, edge, layers, dissolve, root }; cells.push(cell); return cell;
    }
    const hero = makeCell(heroCol, 1800), sides = [];
    if (count > 1) {
      const side = el("ss-col", stage);
      for (let i = 1; i < count; i++) sides.push(makeCell(side, 1100));
    }
    function seeds() { return cells.filter(c => c.photo).map(c => c.photo.seed); }
    // Exact-fit tiles (SS.trioLayout) clear of the clock: the hero left (on top upright) and
    // every gutter one constant. Once shown, a swap to a new shape glides the grid (.8 s).
    backdrop.classList.add("trio"); stage.classList.add("trio");
    function relayout() {
      const live = cells.filter(c => c.photo);
      if (!live.length) return;
      const area = SS.photoLayoutArea(ctx), gap = compactClass(ctx) ? SS.TRIO_GAP.compact : SS.TRIO_GAP.L;
      const shape = live.length < 3 ? "row" : upright ? "heroTop" : "heroLeft";
      const boxes = SS.trioLayout(live.map(c => aspect(c.photo)), area, gap, shape);
      live.forEach((c, i) => Object.assign(c.root.style, { left: boxes[i].x + "px", top: boxes[i].y + "px",
        width: boxes[i].w + "px", height: boxes[i].h + "px" }));
    }
    async function fill(cell, photo) {
      if (disposed || !cell.dissolve.active()) return false;
      const url = await t.publish(() => ctx.url(photo, cell.edge));
      if (url === false) return false;
      if (!await t.load(url) || disposed || !cell.dissolve.active()) return false;
      return t.publish(() => {
        if (disposed || !cell.dissolve.active()) return false;
        const pane = cell.dissolve.next(), image = cell.layers.find(l => l.pane === pane).image;
        // The cell takes the photo's exact aspect; cover keeps a gliding swap free of bars.
        bg(image, url, "cover");
        cell.photo = photo; relayout(); cell.dissolve.present(pane);
      });
    }
    t.start(async () => {
      if (await fill(hero, SS.take(deck, ctx.cursor, ["landscape"], seeds())[0]) === false) return false;
      for (const cell of sides) {
        if (await fill(cell, SS.take(deck, ctx.cursor, ["portrait"], seeds())[0]) === false) return false;
      }
    });
    // The startup layout lands without a glide; later swaps animate.
    t.cleanup.ready.then(ok => { if (ok && !disposed) stage.classList.add("glide"); });
    let turn = 0;
    t.every(async () => {
      if (disposed || !SS.unused(deck, seeds()).length) return;
      turn++;
      const cell = turn % 3 === 0 || !sides.length ? hero : sides[turn % sides.length];
      return fill(cell, SS.take(deck, ctx.cursor, [cell === hero ? "landscape" : "portrait"], seeds())[0]);
    }, ctx.microMs);
    const cleanup = () => {
      if (disposed) return;
      disposed = true; t.cleanup(); cells.forEach(c => c.dissolve.cleanup());
    };
    Object.defineProperty(cleanup, "ready", { get: () => t.cleanup.ready });
    cleanup.pause = () => {
      if (disposed) return;
      cells.forEach(c => c.dissolve.cleanup.pause()); t.cleanup.pause();
    };
    cleanup.resume = () => {
      if (disposed) return;
      cells.forEach(c => c.dissolve.cleanup.resume()); t.cleanup.resume();
    };
    return cleanup;
  }};

  SS.MODES = MODES;
})();
