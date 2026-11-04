// The 12 screensaver modes, unified into the SS.MODES registry. Each: { name, run(ctx, deck) }.
// run() renders into ctx.root and returns a cleanup() the engine calls before the next mode.
// Shared helpers from SS: bucket, take, counts. Photo helpers: ctx.url, window.aspect.

(function () {
  const SS = window.SS;
  const aspect = window.aspect;
  const EASE = {
    kb: "cubic-bezier(0.33,0,0.15,1)", fade: "cubic-bezier(0.4,0,0.2,1)",
    outExpo: "cubic-bezier(0.16,1,0.3,1)", quart: "cubic-bezier(0.77,0,0.175,1)", quint: "cubic-bezier(0.22,1,0.36,1)",
  };

  // ---- tiny DOM helpers -----------------------------------------------------
  function el(cls, parent) { const d = document.createElement("div"); if (cls) d.className = cls; if (parent) parent.appendChild(d); return d; }
  // Publish the exact URL admitted by t.load. Resolve again only for a new preparation
  // so derivative completion/eviction cannot silently substitute an unprepared resource.
  function bg(node, url, size) { node.style.backgroundImage = `url("${url}")`; node.style.backgroundSize = size || "cover"; node.style.backgroundPosition = "center"; node.style.backgroundRepeat = "no-repeat"; }
  // The transparent outline uses the same contain fit and aspect as the decoded photo.
  // As a hero child it follows resizing and the existing motion without another photo load.
  function photoBorderUrl(photo) {
    const ar = aspect(photo);
    if (!Number.isFinite(ar) || ar <= 0) return null;
    const w = Math.min(1000, 1000 * ar), h = w / ar;
    const svg = `<svg xmlns="http://www.w3.org/2000/svg" width="${w}" height="${h}" viewBox="0 0 ${w} ${h}"><rect width="100%" height="100%" fill="none" stroke="#c7b79c" stroke-opacity=".92" stroke-width="2" vector-effect="non-scaling-stroke"/></svg>`;
    return "data:image/svg+xml," + encodeURIComponent(svg);
  }
  // Anchor a cover-crop on the detected face (fx/fy in 0..1) so heads aren't clipped. Falls back to
  // the CSS default (a top-biased center) when the photo has no focal point yet.
  function hasFace(p) { return p && Number.isFinite(p.fx) && Number.isFinite(p.fy) && p.fx >= 0 && p.fx <= 1 && p.fy >= 0 && p.fy <= 1; }
  function faceBg(node, p) {
    if (hasFace(p)) {
      node.style.backgroundPosition = (p.fx * 100).toFixed(1) + "% " + (p.fy * 100).toFixed(1) + "%";
      node.style.animation = "none";
    }
  }
  function kbVars(node, photo) {
    // Keep a prepared photo's animation alive when its parent starts fading out.
    node.classList.add("ss-motion");
    if (hasFace(photo)) {
      ["--s0", "--s1"].forEach(k => node.style.setProperty(k, 1));
      ["--x0", "--y0", "--x1", "--y1"].forEach(k => node.style.setProperty(k, "0%"));
      return;
    }
    const zoomIn = Math.random() > 0.4; const s0 = zoomIn ? 1.04 : 1.15, s1 = zoomIn ? 1.15 : 1.04;
    const s = Math.max(s0, s1), bound = ((s - 1) / 2) * 100 * 0.82;
    const wide = aspect(photo) > 1.2; const bx = wide ? bound : bound * 0.5, by = wide ? bound * 0.5 : bound;
    const rx = () => (Math.random() * 2 - 1) * bx, ry = () => (Math.random() * 2 - 1) * by;
    node.style.setProperty("--s0", s0); node.style.setProperty("--s1", s1);
    node.style.setProperty("--x0", rx() + "%"); node.style.setProperty("--y0", ry() + "%");
    node.style.setProperty("--x1", rx() + "%"); node.style.setProperty("--y1", ry() + "%");
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
      t.cancel(request.timer); fading = null;
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
      disposed = true; t.cleanup(); fading = null;
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
        fading = request;
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

  const MODES = {};

  // ---- 01 Ken Burns (single, quiet matte, keeps moving through fade) --------
  MODES.kenBurns = { name: "Cinematic Ken Burns", run(ctx, deck) {
    const t = makeTimers(ctx);
    const layers = [mk(), mk()]; layers.forEach(l => ctx.root.appendChild(l.root));
    function mk() {
      const root = el("ss-layer ken-burns retired"), hero = el("ss-hero", root);
      const border = el("ss-photo-border", hero);
      hero.style.animation = "none"; hero.style.willChange = "auto";
      return { root, hero, border };
    }
    const dissolve = makeSceneDissolve(ctx, t, layers.map(l => l.root), 2200, root => {
      const hero = layers.find(l => l.root === root).hero;
      hero.style.backgroundImage = "none"; hero.style.animation = "none";
      hero.style.willChange = "auto"; hero.classList.remove("ss-motion");
      layers.find(l => l.root === root).border.style.backgroundImage = "none";
    });
    async function step() {
      const p = SS.take(deck, ctx.cursor, ["any"])[0];
      const u = ctx.url(p, 2200); if (!await t.load(u) || !dissolve.active()) return false;
      const borderUrl = photoBorderUrl(p);
      if (borderUrl && !await t.load(borderUrl) || !dissolve.active()) return false;
      const root = dissolve.next(), L = layers.find(l => l.root === root);
      bg(L.hero, u, "contain");
      if (borderUrl) bg(L.border, borderUrl, "contain");
      else L.border.style.backgroundImage = "none";
      L.hero.style.animation = "none"; L.hero.style.willChange = "auto";
      if (hasFace(p)) {
        L.hero.style.willChange = "auto";
      } else {
        // Keep the same random draws so motion preference cannot alter later photo selection.
        kbVars(L.hero, p);
        if (ctx.photoMotion !== false) {
          L.hero.style.willChange = "transform";
          void L.hero.offsetWidth; L.hero.style.animation = "";
        }
      }
      dissolve.present(root);
    }
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
    async function step() {
      const c = SS.counts(deck); const roll = Math.random();
      let ps;
      if (roll < 0.28 && c.pano) ps = SS.take(deck, ctx.cursor, ["pano"]);
      else if (roll < 0.6 && c.landscape) ps = SS.take(deck, ctx.cursor, ["landscape"]);
      else ps = SS.take(deck, ctx.cursor, ["portrait", "portrait"]);
      const resources = ps.map(p => ctx.url(p, 1800));
      if (!await t.load(resources) || !dissolve.active()) return false;
      const f = dissolve.next();
      f.innerHTML = "";
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
    function resizePrint(art, { area, decoration, maxW, maxH } = printLimits()) {
      if (!art.source) return;
      const ar = aspect(art.source);
      let w = maxW, h = w / ar;
      if (h > maxH) { h = maxH; w = h * ar; }
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
      const p = SS.take(deck, ctx.cursor, ["any"])[0]; const u = ctx.url(p, 1800);
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
  MODES.galleryWall = { name: "Living Gallery Wall", run(ctx, deck) {
    const t = makeTimers(ctx), wall = el("ss-wall gallery-wall", ctx.root), cells = [];
    let disposed = false;
    function replace(cell, photo, url) {
      if (disposed || !cell.dissolve.active()) return;
      const img = cell.dissolve.next();
      bg(img, url, "contain"); img.style.animation = "none"; img.style.willChange = "auto";
      cell.photo = photo; cell.dissolve.present(img);
    }
    function layout(preparedPhotos) {
      const W = ctx.W, H = ctx.H, pad = 16, gutter = 12;
      const targetH = Math.max(240, H * .4);
      const pick = preparedPhotos || []; const seen = new Set(); let g = 0;
      if (!preparedPhotos) {
        while (pick.length < 10 && g < deck.length * 2) { const p = deck[ctx.cursor.i++ % deck.length]; g++; if (!seen.has(p.seed)) { seen.add(p.seed); pick.push(p); } }
      }
      const res = window.justifiedLayout(pick, W - pad * 2, { targetRowHeight: targetH, gutter, maxRows: 10 });
      // Fit both dimensions. Filling height alone pushes complete outer photos
      // off-screen; quiet matte space is preferable to clipping the gallery.
      const sy = Math.min(1, (H - pad * 2) / Math.max(res.height, 1));
      const offY = pad + ((H - pad * 2) - res.height * sy) / 2;
      const rowWidths = new Map();
      res.cells.forEach(c => rowWidths.set(c.top, Math.max(rowWidths.get(c.top) || 0, c.left + c.width)));
      return res.cells.map(c => ({
        ...c, left: c.left * sy + (W - rowWidths.get(c.top) * sy) / 2,
        top: c.top * sy + offY, width: c.width * sy, height: c.height * sy,
      }));
    }
    function render(target) {
      target.forEach(slot => {
        const root = el("ss-gcell", wall);
        root.style.left = slot.left + "px"; root.style.top = slot.top + "px";
        root.style.width = slot.width + "px"; root.style.height = slot.height + "px";
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
      const slots = layout().map(s => ({ ...s, url: ctx.url(s.photo, 1200) }));
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
      const url = ctx.url(photo, 1200);
      if (!await t.load(url) || disposed) return false;
      replace(cell, photo, url);
    }
    t.start(rebuild); t.every(swap, ctx.microMs);
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

  // ---- 05 Cinematic push -----------------------------------------------------
  MODES.push = { name: "Cinematic Push", run(ctx, deck) {
    const t = makeTimers(ctx); const layers = [mk(), mk()]; layers.forEach(l => ctx.root.appendChild(l.root));
    function mk() {
      const root = el("ss-layer cinematic-push retired"), hero = el("ss-hero", root);
      const border = el("ss-photo-border", hero);
      hero.style.animation = "none"; hero.style.willChange = "auto";
      return { root, hero, border };
    }
    const dissolve = makeSceneDissolve(ctx, t, layers.map(l => l.root), 2200, root => {
      const hero = layers.find(l => l.root === root).hero;
      hero.style.backgroundImage = "none"; hero.style.animation = "none";
      hero.style.willChange = "auto";
      layers.find(l => l.root === root).border.style.backgroundImage = "none";
    });
    async function step() {
      const p = SS.take(deck, ctx.cursor, ["any"])[0], u = ctx.url(p, 2200);
      if (!await t.load(u) || !dissolve.active()) return false;
      const borderUrl = photoBorderUrl(p);
      if (borderUrl && !await t.load(borderUrl) || !dissolve.active()) return false;
      const root = dissolve.next(), layer = layers.find(l => l.root === root), hero = layer.hero;
      bg(hero, u, "contain"); hero.style.animation = "none"; hero.style.willChange = "auto";
      if (borderUrl) bg(layer.border, borderUrl, "contain");
      else layer.border.style.backgroundImage = "none";
      if (ctx.photoMotion !== false && !hasFace(p)) {
        hero.style.willChange = "transform"; void hero.offsetWidth;
        hero.style.animation = `cinematicDrift ${ctx.motionMs}ms linear forwards`;
      }
      dissolve.present(root);
    }
    t.start(step); t.every(step, ctx.photoMs);
    return dissolve.cleanup;
  }};

  // ---- 06 Memories (single + occasional titled collage beat) ----------------
  MODES.memories = { name: "Memories", run(ctx, deck) {
    const t = makeTimers(ctx), scenes = [0, 1].map(() => el("ss-memory-scene retired", ctx.root));
    const resources = new Map(scenes.map(scene => [scene, []]));
    const dissolve = makeSceneDissolve(ctx, t, scenes, 2000, scene => {
      resources.get(scene).forEach(image => {
        image.style.backgroundImage = "none"; image.style.animation = "none";
        image.style.willChange = "auto";
      });
      resources.set(scene, []); scene.innerHTML = "";
      scene.style.opacity = ""; scene.style.transition = "";
    });
    let beat = 0;
    function photo(parent, p, url, single) {
      const image = el((single ? "ss-hero" : "ss-img") + " memory-photo", parent);
      bg(image, url, "contain"); image.style.animation = "none"; image.style.willChange = "auto";
      if (single && ctx.photoMotion !== false && !hasFace(p)) {
        image.style.animation = `cinematicDrift ${ctx.motionMs}ms linear forwards`;
        image.style.willChange = "transform";
      }
      return image;
    }
    function renderCollage(scene, ps, urls) {
      const title = el("ss-memory-caption", scene); title.textContent = "Photo memories";
      const slots = ps.length === 1 ? [[.16, .14, .68, .72]] :
        ps.length === 2 ? [[.07, .14, .40, .72], [.53, .14, .40, .72]] :
        [[.08, .16, .42, .5], [.53, .1, .36, .42], [.5, .56, .4, .3]];
      return ps.map((p, i) => {
        const [x, y, sw, sh] = slots[i], boxW = ctx.W * sw, boxH = ctx.H * sh;
        const imageH = Math.max(1, Math.min(boxH - 24, (boxW - 24) / aspect(p)));
        const width = imageH * aspect(p) + 24, height = imageH + 24;
        const tile = el("ss-memory-card", scene);
        tile.style.left = (ctx.W * x + (boxW - width) / 2) + "px";
        tile.style.top = (ctx.H * y + (boxH - height) / 2) + "px";
        tile.style.width = width + "px"; tile.style.height = height + "px";
        return photo(tile, p, urls[i], false);
      });
    }
    async function step() {
      if (!dissolve.active()) return false;
      const nextBeat = beat + 1, collage = nextBeat % 6 === 0;
      const ps = SS.take(deck, ctx.cursor, new Array(SS.slots(deck, collage ? 3 : 1)).fill("any"));
      const urls = await t.publish(() => ps.map(p => ctx.url(p, collage ? 1100 : 2200)));
      if (urls === false || !await t.load(urls) || !dissolve.active()) return false;
      return t.publish(() => {
        if (!dissolve.active()) return false;
        const scene = dissolve.next();
        const images = collage ? renderCollage(scene, ps, urls) : [photo(scene, ps[0], urls[0], true)];
        resources.set(scene, images); beat = nextBeat; dissolve.present(scene);
      });
    }
    t.start(step); t.every(step, ctx.photoMs);
    return dissolve.cleanup;
  }};

  // ---- 07 Panorama drift -----------------------------------------------------
  MODES.panorama = { name: "Panorama Drift", run(ctx, deck) {
    const t = makeTimers(ctx), layers = [mk(), mk()];
    const dissolve = makeSceneDissolve(ctx, t, layers.map(layer => layer.root), 1800, root => {
      const pan = layers.find(layer => layer.root === root).pan;
      pan.style.backgroundImage = "none"; pan.style.animation = "none";
      pan.style.willChange = "auto"; pan.style.transform = "none";
    });
    function mk() {
      const root = el("ss-layer panorama-drift retired", ctx.root), pan = el("ss-pan", root);
      pan.style.animation = "none"; pan.style.willChange = "auto";
      return { root, pan };
    }
    async function step() {
      if (!dissolve.active()) return false;
      const p = SS.take(deck, ctx.cursor, [SS.counts(deck).pano ? "pano" : "landscape"])[0];
      const url = await t.publish(() => ctx.url(p, 2400));
      if (url === false || !await t.load(url) || !dissolve.active()) return false;
      return t.publish(() => {
        if (!dissolve.active()) return false;
        const root = dissolve.next(), pan = layers.find(layer => layer.root === root).pan;
        const rawAspect = aspect(p), ratio = Number.isFinite(rawAspect) && rawAspect > 0 ? rawAspect : ctx.W / ctx.H;
        // Bound the moving strip instead of making an extreme panorama many screens wide.
        // Fit its full height on matte; transform motion still reaches both horizontal edges.
        const baseWidth = Math.min(ctx.W * 2, Math.max(ctx.W, ctx.H * ratio));
        const faceCrop = SS.faceCrop(p, baseWidth, ctx.W);
        const width = faceCrop ? faceCrop.size : baseWidth;
        const height = Math.min(ctx.H, width / ratio), travel = (ctx.W - width) / width * 100;
        pan.style.width = width + "px"; pan.style.height = height + "px";
        pan.style.top = (ctx.H - height) / 2 + "px";
        bg(pan, url, "contain"); pan.style.setProperty("--shift", travel + "%");
        const moving = ctx.photoMotion !== false && width > ctx.W && !hasFace(p);
        // Unlike zoom, the full panoramic sweep must finish before the next photo.
        const duration = Math.min(ctx.motionMs, ctx.photoMs);
        pan.style.animation = moving ? `dolly ${duration}ms linear forwards` : "none";
        pan.style.willChange = moving ? "transform" : "auto";
        // A still strip uses the cached face or the middle of the admitted crop.
        pan.style.transform = faceCrop ? `translateX(${-faceCrop.offset / width * 100}%)` :
          hasFace(p) || ctx.photoMotion === false ?
          `translateX(${travel * SS.panStart(hasFace(p) ? p.fx : .5, width / ctx.W * 100)}%)` : "none";
        dissolve.present(root);
      });
    }
    t.start(step); t.every(step, ctx.photoMs);
    return dissolve.cleanup;
  }};

  // ---- 08 Polaroid scatter ---------------------------------------------------
  MODES.polaroid = { name: "Polaroid Scatter", run(ctx, deck) {
    const t = makeTimers(ctx), table = el("ss-table", ctx.root), MAX = SS.slots(deck, 6);
    let live = [], disposed = false, paused = false, fading = null;
    const now = () => typeof performance !== "undefined" ? performance.now() : Date.now();
    const COLS = Math.min(MAX, Math.ceil(Math.sqrt(MAX * ctx.W / ctx.H))), ROWS = Math.ceil(MAX / COLS);
    const cellW = ctx.W / COLS, cellH = ctx.H / ROWS;
    const freeCells = window.shuffled(Array.from({ length: COLS * ROWS }, (_, i) => i)).slice(0, MAX);
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
    function layout(print, photo, cell) {
      const raw = aspect(photo), ratio = Number.isFinite(raw) && raw > 0 ? raw : 1;
      const pad = Math.max(8, Math.min(28, Math.min(cellW, cellH) * .045));
      const edge = Math.max(4, Math.min(22, Math.min(cellW, cellH) * .035)), chin = edge * 2.5;
      const rotation = (Math.random() * 2 - 1) * 8, angle = Math.abs(rotation) * Math.PI / 180;
      const cos = Math.cos(angle), sin = Math.sin(angle), baseW = ratio >= 1 ? 600 : 480, baseH = baseW / ratio;
      // Fit the rotated PAPER envelope, not only its unrotated photograph.
      const scale = Math.max(Number.EPSILON, Math.min(1,
        (cellW - pad * 2 - cos * edge * 2 - sin * (edge + chin)) / (cos * baseW + sin * baseH),
        (cellH - pad * 2 - sin * edge * 2 - cos * (edge + chin)) / (sin * baseW + cos * baseH)));
      const pw = baseW * scale, ph = baseH * scale, W = pw + edge * 2, H = ph + edge + chin;
      const boundW = cos * W + sin * H, boundH = sin * W + cos * H;
      const cx = (cell % COLS) * cellW, cy = Math.floor(cell / COLS) * cellH;
      const x = cx + pad + boundW / 2 + Math.random() * Math.max(0, cellW - pad * 2 - boundW);
      const y = cy + pad + boundH / 2 + Math.random() * Math.max(0, cellH - pad * 2 - boundH);
      print.card.style.width = W + "px"; print.card.style.height = H + "px";
      print.card.style.padding = `${edge}px ${edge}px ${chin}px`;
      print.card.style.left = (x - W / 2) + "px"; print.card.style.top = (y - H / 2) + "px";
      print.image.style.width = pw + "px"; print.image.style.height = ph + "px";
      print.target = `rotate(${rotation}deg) scale(1)`; print.rotation = rotation;
    }
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
      const url = await t.publish(() => ctx.url(p, 1400));
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
    const items = [];
    let center = 0, top = false, admitted = false, bounded = false, disposed = false, preparation = null, viewportBound = 0;
    // The unchanged CSS scales at most 1.12 and has a 70px black shadow blur.
    // 512 local pixels deliberately overbound its finite raster support, spread
    // and offset; 2px additionally cover flex/subpixel rounding over twelve slots.
    const MAX_SCALE = 1.12, SHADOW_PAD = 512, ROUNDING_PAD = 2;
    function knownGeometry() {
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
      if (!admitted) {
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
      track.style.transform = `translateX(${-to}px)`;
      const b = top ? bdA : bdB, o = top ? bdB : bdA;
      bg(b, u); b.style.opacity = "1"; o.style.opacity = "0";
      top = !top; center = i; admitted = true;
    }
    function loop() { return go((center + 1) % items.length); }
    t.start(async () => {
      let left = 0;
      set.forEach(p => {
        const s = el("ss-slide", track), h = ctx.H * .62, w = h * aspect(p);
        s.style.width = w + "px";
        const im = el("ss-img", s); im.style.width = w + "px"; im.style.height = "100%";
        im.style.backgroundSize = "cover"; im.style.backgroundPosition = "center"; im.style.backgroundRepeat = "no-repeat";
        items.push({ el: s, img: im, w, off: left + w / 2, photo: p, prepared: false });
        left += w + 60;
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
    const t = makeTimers(ctx), frame = el("ss-scope", ctx.root), scopeH = ctx.H * .78;
    const bars = [el("ss-bar top", ctx.root), el("ss-bar bot", ctx.root)];
    let disposed = false;
    const layers = [0, 1].map(() => {
      const root = el("ss-layer cinema-layer retired", frame), img = el("ss-img ss-cinema-photo", root);
      const grain = el("ss-grain", img);
      img.style.backgroundImage = "none"; img.style.animation = "none"; img.style.willChange = "auto";
      return { root, img, grain };
    });
    const dissolve = makeSceneDissolve({ ...ctx, root: frame }, t, layers.map(layer => layer.root), 2200, root => {
      const image = layers.find(layer => layer.root === root).img;
      image.style.backgroundImage = "none"; image.style.animation = "none";
      image.style.willChange = "auto"; image.style.transform = "none";
    });
    async function step() {
      const c = SS.counts(deck), p = SS.take(deck, ctx.cursor, [c.pano ? "pano" : "landscape"])[0];
      const u = await t.publish(() => ctx.url(p, 2400));
      if (u === false || !await t.load(u) || disposed) return false;
      return t.publish(() => {
        if (disposed || !dissolve.active()) return false;
        const root = dissolve.next(), image = layers.find(layer => layer.root === root).img;
        const raw = aspect(p), ratio = Number.isFinite(raw) && raw > 0 ? raw : 1;
        const wide = ratio > 2.2, W = wide ? ctx.W : Math.min(ctx.W, scopeH * ratio), H = wide ? scopeH : W / ratio;
        image.style.width = W + "px"; image.style.height = H + "px";
        image.style.left = ((ctx.W - W) / 2) + "px"; image.style.top = ((scopeH - H) / 2) + "px";
        image.style.right = "auto"; image.style.bottom = "auto"; image.style.transform = "none";
        image.style.animation = "none"; image.style.willChange = "auto";
        bg(image, u, wide ? "cover" : "contain");
        if (wide && hasFace(p)) {
          // Keep the immersive crop, but center the cached focal point rather than
          // confusing CSS edge alignment with the focal point's image coordinate.
          const renderedW = Math.max(ctx.W, scopeH * ratio), renderedH = renderedW / ratio;
          // Compare the cover constraints directly; division roundoff can make
          // a height fitted exactly to scopeH appear to overflow it.
          const vertical = ctx.W > scopeH * ratio;
          const faceCrop = SS.faceCrop(p, vertical ? renderedH : renderedW,
            vertical ? scopeH : ctx.W, vertical);
          if (faceCrop) {
            const safeWidth = vertical ? faceCrop.size * ratio : ctx.W;
            const safeHeight = vertical ? scopeH : faceCrop.size / ratio;
            image.style.width = safeWidth + "px";
            image.style.height = safeHeight + "px";
            image.style.left = (ctx.W - safeWidth) / 2 + "px";
            image.style.top = (scopeH - safeHeight) / 2 + "px";
            image.style.backgroundPosition = vertical ? `50% ${faceCrop.position * 100}%` :
              `${faceCrop.position * 100}% 50%`;
          } else {
            image.style.backgroundPosition =
              `${SS.panStart(p.fx, renderedW / ctx.W * 100) * 100}% ${SS.panStart(p.fy, renderedH / scopeH * 100) * 100}%`;
          }
        } else if (wide && ctx.photoMotion !== false) {
          image.style.willChange = "transform";
          image.style.animation = `cinematicDrift ${ctx.motionMs}ms linear forwards`;
        }
        dissolve.present(root);
      });
    }
    t.start(step); t.every(step, ctx.photoMs);
    const cleanup = () => {
      if (disposed) return;
      disposed = true; dissolve.cleanup();
      layers.forEach(layer => { layer.grain.style.backgroundImage = "none"; });
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
    heroCol.style.flex = count > 1 ? "1.9" : "1";
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
      const cell = { photo: null, edge, layers, dissolve }; cells.push(cell); return cell;
    }
    const hero = makeCell(heroCol, 1800), sides = [];
    if (count > 1) {
      const side = el("ss-col", stage); side.style.flex = "1";
      for (let i = 1; i < count; i++) sides.push(makeCell(side, 1100));
    }
    function seeds() { return cells.filter(c => c.photo).map(c => c.photo.seed); }
    async function fill(cell, photo) {
      if (disposed || !cell.dissolve.active()) return false;
      const url = await t.publish(() => ctx.url(photo, cell.edge));
      if (url === false) return false;
      if (!await t.load(url) || disposed || !cell.dissolve.active()) return false;
      return t.publish(() => {
        if (disposed || !cell.dissolve.active()) return false;
        const pane = cell.dissolve.next(), image = cell.layers.find(l => l.pane === pane).image;
        bg(image, url, "contain");
        cell.photo = photo; cell.dissolve.present(pane);
      });
    }
    t.start(async () => {
      if (await fill(hero, SS.take(deck, ctx.cursor, ["landscape"], seeds())[0]) === false) return false;
      for (const cell of sides) {
        if (await fill(cell, SS.take(deck, ctx.cursor, ["portrait"], seeds())[0]) === false) return false;
      }
    });
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
