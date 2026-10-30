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
    let ready = Promise.resolve(false);
    function running() {
      if (!active) return Promise.resolve(false);
      if (!paused) return Promise.resolve(true);
      return new Promise(resolve => waiters.push(resolve));
    }
    async function load(urls) {
      try {
        await Promise.all(Array.from(new Set([].concat(urls))).map(u => window.preloadDecoded(u, controller.signal)));
        return active && await running();
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
      finally { busy = false; }
    }
    function schedule(task) {
      if (!active || paused || (!started && !task.preparation)) return;
      task.due = Date.now() + task.remaining;
      task.id = setTimeout(async () => {
        task.id = null;
        if (!active || paused) return;
        if (task.repeat) await run(task.fn); else { tasks.delete(task); task.fn(); }
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
      tasks.forEach(task => { if (task.id == null) schedule(task); });
    };
    return {
      load, frame, cleanup,
      cancel: task => { if (task) { clearTimeout(task.id); task.id = null; tasks.delete(task); } },
      every: (fn, ms) => add(fn, ms, true), after: (fn, ms) => add(fn, ms, false),
      // Bridge deadlines run before readiness, but consume only active playback time.
      deadline: (fn, ms) => add(fn, ms, false, true),
      async navigate(fn) {
        const ok = await run(fn);
        if (ok) tasks.forEach(task => {
          if (task.repeat) { clearTimeout(task.id); task.id = null; task.remaining = task.ms; schedule(task); }
        });
        return ok;
      },
      start(fn) {
        ready = run(fn).then(ok => {
          if (ok) { started = true; tasks.forEach(task => { if (task.id == null) schedule(task); }); }
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
      hero.style.animation = "none"; hero.style.willChange = "auto";
      return { root, hero };
    }
    const dissolve = makeSceneDissolve(ctx, t, layers.map(l => l.root), 2200, root => {
      const hero = layers.find(l => l.root === root).hero;
      hero.style.backgroundImage = "none"; hero.style.animation = "none";
      hero.style.willChange = "auto"; hero.classList.remove("ss-motion");
    });
    async function step() {
      const p = SS.take(deck, ctx.cursor, ["any"])[0];
      const u = ctx.url(p, 2200); if (!await t.load(u) || !dissolve.active()) return false;
      const root = dissolve.next(), L = layers.find(l => l.root === root);
      bg(L.hero, u, "contain");
      L.hero.style.animation = "none";
      if (hasFace(p)) {
        L.hero.style.willChange = "auto";
      } else {
        kbVars(L.hero, p); L.hero.style.willChange = "transform";
        void L.hero.offsetWidth; L.hero.style.animation = "";
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
    function containCell(imageUrl) { const c = el("ss-cell contain"); const im = el("ss-img", c); bg(im, imageUrl, "contain"); return c; }
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
    const room = el("ss-room", ctx.root);
    const arts = [mk(), mk()]; arts.forEach(a => ctx.root.appendChild(a.root));
    let cur = 0, disposed = false;
    const FADE_MS = 2400; // Matches .ss-art; one fallback, never a polling loop.
    function retire(A, ticket) {
      if (disposed || A.ticket !== ticket || A.root.classList.contains("on")) return;
      // A delayed/stale transition event must not stop a newer fade still on screen.
      if (Number(getComputedStyle(A.root).opacity) !== 0) return;
      A.root.classList.add("retired");
    }
    function mk() {
      const root = el("ss-art retired"); const edge = el("ss-frameEdge", root); const mat = el("ss-mat", edge);
      const bevel = el("ss-bevel", mat); const photo = el("ss-print", bevel);
      const A = { root, photo, ticket: 0 };
      root.ontransitionend = event => {
        if (event.target === root && event.propertyName === "opacity") retire(A, A.ticket);
      };
      return A;
    }
    function sizeFor(p) { const maxW = ctx.W * 0.6, maxH = ctx.H * 0.72; const ar = aspect(p); let w = maxW, h = w / ar; if (h > maxH) { h = maxH; w = h * ar; } return { w: Math.round(w), h: Math.round(h) }; }
    async function step() {
      const p = SS.take(deck, ctx.cursor, ["any"])[0]; const u = ctx.url(p, 1800); if (!await t.load(u)) return false;
      const A = arts[1 - cur]; const { w, h } = sizeFor(p);
      A.photo.style.width = w + "px"; A.photo.style.height = h + "px"; bg(A.photo, u);
      A.photo.style.animationDelay = (-Math.random() * 10) + "s";
      A.ticket++; A.root.classList.remove("retired");
      const outgoing = arts[cur], fading = outgoing.root.classList.contains("on"), ticket = ++outgoing.ticket;
      outgoing.root.classList.remove("on"); A.root.classList.add("on"); cur = 1 - cur;
      if (fading) t.after(() => retire(outgoing, ticket), FADE_MS);
    }
    t.start(step); t.every(step, ctx.photoMs);
    const cleanup = () => {
      if (disposed) return;
      disposed = true;
      t.cleanup(); // Owns the fade-retire fallback as well as the repeat/decode work.
      arts.forEach(A => {
        A.ticket++;
        A.root.ontransitionend = null;
        A.root.classList.add("retired");
      });
    };
    Object.defineProperty(cleanup, "ready", { get: () => t.cleanup.ready });
    cleanup.pause = t.cleanup.pause; cleanup.resume = t.cleanup.resume;
    return cleanup;
  }};

  // ---- 04 Living gallery wall (justified rows + swaps) ----------------------
  MODES.galleryWall = { name: "Living Gallery Wall", run(ctx, deck) {
    const t = makeTimers(ctx); const wall = el("ss-wall", ctx.root); let cells = [];
    const retiring = new Map(); let disposed = false;
    function release(old, entry) {
      if (old.ontransitionend === entry.handler) old.ontransitionend = null;
      t.cancel(entry.fallback); entry.fallback = null; retiring.delete(old);
    }
    function retire(cell, old) {
      const entry = retiring.get(old);
      if (disposed || !entry || entry.cell !== cell || cell.img === old) return false;
      if (old.parentNode !== cell.el) { release(old, entry); return true; }
      // Missing style information is not transparency. Preserve content until CSS confirms zero.
      let opacity;
      try { opacity = getComputedStyle(old).opacity; } catch (_) { return false; }
      if (typeof opacity !== "string" ||
          !/^[+-]?(?:\d+\.?\d*|\.\d+)(?:e[+-]?\d+)?$/i.test(opacity.trim()) || Number(opacity) !== 0) return false;
      release(old, entry); old.remove(); return true;
    }
    function fallback(cell, old) {
      const entry = retiring.get(old);
      if (disposed || !entry || entry.cell !== cell) return;
      entry.fallback = null;
      if (!retire(cell, old) && retiring.get(old) === entry)
        entry.fallback = t.after(() => fallback(cell, old), 1200);
    }
    function replace(cell, photo, url) {
      const img = buildImg(photo, url), old = cell.img;
      cell.el.appendChild(img); cell.img = img; cell.photo = photo;
      if (!old) return;
      const entry = { cell, fallback: null, handler: null };
      entry.handler = event => {
        if (event.target === old && event.propertyName === "opacity") retire(cell, old);
      };
      retiring.set(old, entry); old.ontransitionend = entry.handler;
      t.frame(() => { if (retiring.get(old) === entry && cell.img !== old) old.style.opacity = "0"; });
      entry.fallback = t.after(() => fallback(cell, old), 1200);
    }
    function buildImg(p, url) { const img = el("ss-img kb"); bg(img, url); faceBg(img, p); if (Math.random() < 0.4) { img.classList.remove("kb"); return img; } img.style.setProperty("--d", (14 + Math.random() * 8) + "s"); img.style.setProperty("--dx", ((Math.random() * 2 - 1) * 2).toFixed(1) + "%"); img.style.setProperty("--dy", ((Math.random() * 2 - 1) * 2).toFixed(1) + "%"); img.style.animationDelay = (-Math.random() * 10) + "s"; return img; }
    function layout(preparedPhotos) {
      const W = ctx.W, H = ctx.H, pad = 16, gutter = 12; const targetH = H > 1000 ? 300 : 240;
      const rows = Math.max(2, Math.round((H - pad * 2 + gutter) / (targetH + gutter)));
      const pick = preparedPhotos || []; const seen = new Set(); let g = 0;
      if (!preparedPhotos) {
        while (pick.length < 10 && g < deck.length * 2) { const p = deck[ctx.cursor.i++ % deck.length]; g++; if (!seen.has(p.seed)) { seen.add(p.seed); pick.push(p); } }
      }
      const res = window.justifiedLayout(pick, W - pad * 2, { targetRowHeight: targetH, gutter, maxRows: rows });
      // Scale the whole packed layout UNIFORMLY to fill the height — cells keep their photo aspect
      // (justifiedLayout sized them to aspect) so cover never crops within a cell. When scaled up the
      // wall exceeds the width, so center it and let the outer columns bleed off-screen rather than
      // leaving black bands (fills the frame; no per-cell distortion/clipping).
      const sy = (H - pad * 2) / Math.max(res.height, 1);
      const scaledW = (W - pad * 2) * sy;
      const offX = pad + ((W - pad * 2) - scaledW) / 2;
      return res.cells.map(c => ({
        ...c, left: c.left * sy + offX, top: c.top * sy + pad, width: c.width * sy, height: c.height * sy,
      }));
    }
    function render(target) {
      while (cells.length < target.length) { const e = el("ss-gcell", wall); cells.push({ el: e, img: null, photo: null }); }
      while (cells.length > target.length) { cells.pop().el.remove(); }
      target.forEach((slot, i) => { const c = cells[i]; c.el.style.left = slot.left + "px"; c.el.style.top = slot.top + "px"; c.el.style.width = slot.width + "px"; c.el.style.height = slot.height + "px";
        if (c.photo !== slot.photo) replace(c, slot.photo, slot.url); });
    }
    async function rebuild() {
      const slots = layout().map(s => ({ ...s, url: ctx.url(s.photo, 1200) }));
      if (!await t.load(slots.map(s => s.url))) return false;
      // Decode may recover unknown ratios. Keep the same admitted photos/URLs,
      // then recalculate their rectangles before the composition becomes visible.
      const prepared = new Map(slots.map(s => [s.photo, s.url]));
      render(layout(slots.map(s => s.photo)).map(s => ({ ...s, url: prepared.get(s.photo) })));
    }
    async function swap() { if (!cells.length) return; const onWall = cells.map(x => x.photo && x.photo.seed).filter(Boolean); if (!SS.unused(deck, onWall).length) return; const c = cells[Math.floor(Math.random() * cells.length)]; let p = null; const wallSet = new Set(onWall); for (let k = 0; k < deck.length; k++) { const cand = deck[(ctx.cursor.i + k) % deck.length]; if (!wallSet.has(cand.seed) && SS.bucket(cand) === SS.bucket(c.photo)) { p = cand; ctx.cursor.i += k + 1; break; } } if (!p) p = SS.take(deck, ctx.cursor, ["any"], onWall)[0]; const url = ctx.url(p, 1200); if (!await t.load(url)) return false; replace(c, p, url); }
    t.start(rebuild); t.every(swap, ctx.microMs);
    const cleanup = () => {
      if (disposed) return;
      disposed = true; t.cleanup();
      retiring.forEach((entry, old) => release(old, entry)); retiring.clear();
    };
    Object.defineProperty(cleanup, "ready", { get: () => t.cleanup.ready });
    cleanup.pause = t.cleanup.pause; cleanup.resume = t.cleanup.resume;
    return cleanup;
  }};

  // ---- 05 Cinematic push -----------------------------------------------------
  MODES.push = { name: "Cinematic Push", run(ctx, deck) {
    const t = makeTimers(ctx); const layers = [mk(), mk()]; layers.forEach(l => ctx.root.appendChild(l.root)); let cur = 0, first = true;
    function mk() { const root = el("ss-layer"); const fill = el("ss-fill", root); const hero = el("ss-hero", root); return { root, fill, hero }; }
    async function step() {
      const p = SS.take(deck, ctx.cursor, ["any"])[0]; const u = ctx.url(p, 2200); if (!await t.load(u)) return false;
      const inL = layers[1 - cur], outL = layers[cur]; bg(inL.fill, u); bg(inL.hero, u, "contain");
      inL.root.className = "ss-layer push-enter"; if (!first) outL.root.className = "ss-layer push-leave";
      t.after(() => { inL.root.className = "ss-layer"; inL.root.style.opacity = "1"; inL.hero.style.animation = hasFace(p) ? "none" : `drift ${ctx.motionMs}ms linear forwards`; }, 1150);
      cur = 1 - cur; first = false;
    }
    t.start(step); t.every(step, ctx.photoMs);
    return t.cleanup;
  }};

  // ---- 06 Memories (single + occasional titled collage beat) ----------------
  MODES.memories = { name: "Memories", run(ctx, deck) {
    // Two alternating single layers (like kenBurns): reusing one layer swapped its
    // background in place — an instant hard cut whenever the image was already cached.
    const t = makeTimers(ctx); const singles = [mk(), mk()]; singles.forEach(s => ctx.root.appendChild(s.root)); let sCur = 0;
    const collage = el("ss-collage", ctx.root); const title = el("ss-mtitle", ctx.root); const big = el("big", title); const sub = el("sub", title);
    let beat = 0;
    function mk() { const root = el("ss-single"); const fill = el("ss-fill", root); const hero = el("ss-hero", root); return { root, fill, hero }; }
    async function showSingle() { const p = SS.take(deck, ctx.cursor, ["any"])[0]; const u = ctx.url(p, 2200); if (!await t.load(u)) return false; collage.classList.remove("on"); const S = singles[1 - sCur]; bg(S.fill, u); bg(S.hero, u, "contain"); kbVars(S.hero, p); S.hero.style.animation = "none"; void S.hero.offsetWidth; S.hero.style.animation = ""; singles[sCur].root.classList.remove("on"); S.root.classList.add("on"); sCur = 1 - sCur; }
    async function showCollage() {
      const ps = SS.take(deck, ctx.cursor, ["any", "any", "any"]), urls = ps.map(p => ctx.url(p, 1100)); if (!await t.load(urls)) return false;
      singles.forEach(s => s.root.classList.remove("on")); big.textContent = "Photo memories"; sub.textContent = ""; collage.innerHTML = "";
      const W = ctx.W, H = ctx.H; const slots = [{ l: W * .08, t: H * .16, w: W * .42, h: H * .5 }, { l: W * .53, t: H * .1, w: W * .36, h: H * .42 }, { l: W * .5, t: H * .56, w: W * .4, h: H * .3 }];
      ps.forEach((p, i) => { const s = slots[i]; const tile = el("ss-mtile", collage); tile.style.left = s.l + "px"; tile.style.top = s.t + "px"; tile.style.width = s.w + "px"; tile.style.height = s.h + "px"; tile.style.transitionDelay = (i * .15) + "s"; const im = el("ss-img breathe", tile); bg(im, urls[i]); faceBg(im, p); im.style.animationDelay = (-Math.random() * 8) + "s"; });
      t.frame(() => { collage.classList.add("on"); title.classList.add("on"); }); t.after(() => title.classList.remove("on"), 4500);
    }
    function step() { beat++; return beat % 6 === 0 ? showCollage() : showSingle(); }
    t.start(step); t.every(step, ctx.photoMs);
    return t.cleanup;
  }};

  // ---- 07 Panorama drift -----------------------------------------------------
  MODES.panorama = { name: "Panorama Drift", run(ctx, deck) {
    const t = makeTimers(ctx); const layers = [mk(), mk()]; layers.forEach(l => ctx.root.appendChild(l.root)); let cur = 0;
    function mk() { const root = el("ss-layer"); root.style.overflow = "hidden"; const pan = el("ss-pan", root); return { root, pan }; }
    async function step() {
      const p = SS.take(deck, ctx.cursor, [SS.counts(deck).pano ? "pano" : "landscape"])[0]; const u = ctx.url(p, 2600); if (!await t.load(u)) return false;
      // Size the pan element to the photo's OWN aspect vs the viewport so height fills with zero
      // vertical crop; only pan when that makes it wider than the screen (true panos). A plain
      // landscape gets ~100% width → shown whole, no crop.
      const L = layers[1 - cur]; const vp = ctx.W / ctx.H; const overW = Math.max(100, Math.round(aspect(p) / vp * 100)); L.pan.style.width = overW + "%"; bg(L.pan, u); L.pan.style.backgroundPosition = "left center";
      const travel = -(overW - 100) / overW * 100;
      L.pan.style.setProperty("--shift", travel + "%");
      L.pan.style.animation = "none"; void L.pan.offsetWidth;
      L.pan.style.animation = hasFace(p) ? "none" : "";
      L.pan.style.transform = hasFace(p) ? `translateX(${travel * SS.panStart(p.fx, overW)}%)` : "";
      layers[cur].root.classList.remove("on"); L.root.classList.add("on"); cur = 1 - cur;
    }
    t.start(step); t.every(step, ctx.photoMs);
    return t.cleanup;
  }};

  // ---- 08 Polaroid scatter ---------------------------------------------------
  MODES.polaroid = { name: "Polaroid Scatter", run(ctx, deck) {
    // MAX clamps to the pool so the table can never demand more distinct photos than exist;
    // dropping the oldest print always frees a seed before the next pick.
    const t = makeTimers(ctx); const table = el("ss-table", ctx.root); let live = []; const MAX = SS.slots(deck, 6);
    // The legacy input has no verified per-photo caption. Never invent a date, place or event.
    // Uniform-random placement clumps (independent points cluster and leave dead zones), so
    // scatter over a virtual grid instead: one print per cell, jittered inside it. Every print
    // then lands in its own region of the screen — guaranteed spread, still messy-looking.
    const COLS = Math.ceil(Math.sqrt(MAX * ctx.W / ctx.H)), ROWS = Math.ceil(MAX / COLS);
    const cellW = ctx.W / COLS, cellH = ctx.H / ROWS;
    let freeCells = window.shuffled(Array.from({ length: COLS * ROWS }, (_, i) => i)).slice(0, MAX);
    function place(pl, pw, ph, cell) {
      const cx = (cell % COLS) * cellW, cy = Math.floor(cell / COLS) * cellH;
      // Jitter within the cell; clamp so the print (incl. ~110px chin) stays on screen.
      const M = 16, footH = ph + 110;
      const x = Math.min(Math.max(cx + Math.random() * Math.max(1, cellW - pw), M), ctx.W - pw - M);
      const y = Math.min(Math.max(cy + Math.random() * Math.max(1, cellH - footH), M), ctx.H - footH - M);
      pl.style.left = x + "px"; pl.style.top = y + "px";
    }
    async function drop(delay = 0) {
      const excluded = live.length >= MAX ? live.slice(1) : live;
      const p = SS.take(deck, ctx.cursor, ["any"], excluded.map(x => x.photo.seed))[0];
      const url = ctx.url(p, 1400); if (!await t.load(url)) return false;
      if (live.length >= MAX) { const old = live.shift(); freeCells.push(old.cell); old.el.style.opacity = "0"; old.el.style.transform += " translateY(-30px)"; t.after(() => old.el.remove(), 1000); }
      if (!freeCells.length) return;
      const cell = freeCells.splice(Math.floor(Math.random() * freeCells.length), 1)[0];
      // Exclude prints still on the table so a photo never appears twice at once.
      const pl = el("ss-polaroid"); const a = aspect(p);
      // Big prints (2x the original 300/240) — the table is a 2160px wall display.
      const pw = a >= 1 ? 600 : 480; const ph = Math.round(pw / a);
      const inner = el("ss-ph", pl); inner.style.width = pw + "px"; inner.style.height = ph + "px"; bg(inner, url);
      table.appendChild(pl); pl.style.transitionDelay = delay + "ms";
      place(pl, pw, ph, cell);
      const rot = (Math.random() * 2 - 1) * 11;
      pl.style.transform = `rotate(${rot}deg) scale(0.86)`;
      t.frame(() => { pl.classList.add("on"); pl.style.transform = `rotate(${rot}deg) scale(1)`; });
      live.push({ el: pl, photo: p, cell });
    }
    t.start(async () => { for (let i = 0; i < MAX; i++) { if (await drop(i * 450) === false) return false; } });
    t.every(() => drop(), ctx.microMs);
    return t.cleanup;
  }};

  // ---- 09 Mosaic wall (BSP, choreographed) -----------------------------------
  // The layout is fixed for the mode's whole run — tiles never move or reshape
  // (rearranging read as restless). Motion comes from within the frames: per-tile
  // Ken Burns / pan-through, a roaming spotlight (filter/transform only), and
  // photo crossfades — including exchanging two tiles' photos in place when the
  // whole library is already on the wall.
  MODES.mosaic = { name: "Mosaic Wall", run(ctx, deck) {
    const t = makeTimers(ctx); const wall = el("ss-wall spotlit", ctx.root); let cells = [];
    function bsp(rect, n) { if (n <= 1) return [rect]; const k = Math.max(1, Math.round(n * (0.42 + Math.random() * 0.16))); const horiz = rect.w >= rect.h; const ratio = k / n; if (horiz) { const w1 = Math.round(rect.w * ratio); return bsp({ x: rect.x, y: rect.y, w: w1, h: rect.h }, k).concat(bsp({ x: rect.x + w1, y: rect.y, w: rect.w - w1, h: rect.h }, n - k)); } const h1 = Math.round(rect.h * ratio); return bsp({ x: rect.x, y: rect.y, w: rect.w, h: h1 }, k).concat(bsp({ x: rect.x, y: rect.y + h1, w: rect.w, h: rect.h - h1 }, n - k)); }
    // A photo whose shape fights its cell (portrait in a wide frame) would lose most of
    // itself to the cover crop, so it pans through its full extent instead of Ken Burns.
    // A cached point cannot prove a safe motion path: keep that focus still. Cover crops
    // anchor on it; photos without a point retain the existing pan presentation.
    function mkImg(p, rect, url) {
      const plan = SS.fitPlan(aspect(p), rect.w / rect.h);
      if (plan.mode !== "cover") {
        const img = el(plan.mode === "panV" ? "ss-img panv" : "ss-img panh");
        bg(img, url);
        if (plan.mode === "panV") { img.style.height = plan.size + "%"; img.style.setProperty("--travel", -(plan.size - 100) / plan.size * 100 + "%"); }
        else { img.style.width = plan.size + "%"; img.style.setProperty("--travel", -(plan.size - 100) / plan.size * 100 + "%"); }
        // Follow the configured hold plus dissolve, including long slideshow intervals.
        const dur = ctx.motionMs / 1000;
        img.style.setProperty("--d", dur + "s");
        // Place a known focus in the visible window and do not tour away from it.
        const start = SS.panStart(plan.mode === "panV" ? p.fy : p.fx, plan.size);
        if (hasFace(p)) {
          img.style.animation = "none";
          img.style.transform = `${plan.mode === "panV" ? "translateY" : "translateX"}(${-((plan.size - 100) / plan.size * 100) * start}%)`;
        }
        return img;
      }
      const img = el("ss-img kb"); bg(img, url); if (Math.random() < 0.4) img.classList.remove("kb"); img.style.setProperty("--d", (12 + Math.random() * 6) + "s"); img.style.setProperty("--dx", ((Math.random() * 2 - 1) * 2.5).toFixed(1) + "%"); img.style.setProperty("--dy", ((Math.random() * 2 - 1) * 2.5).toFixed(1) + "%"); img.style.animationDelay = (-Math.random() * 10) + "s";
      faceBg(img, p);
      return img;
    }
    function wantFor(cellA) { return cellA < 0.85 ? "portrait" : cellA > 1.8 ? "pano" : "landscape"; }
    // Every pick excludes what's already on the wall, so no tile duplicates another.
    function onWall() { return cells.map(x => x.photo && x.photo.seed).filter(Boolean); }
    function setRect(cell, r) { cell.rect = r; cell.c.style.left = r.x + "px"; cell.c.style.top = r.y + "px"; cell.c.style.width = r.w + "px"; cell.c.style.height = r.h + "px"; }
    // Replace a cell's image (crossfade in place — the cell itself never moves).
    function setImg(cell, p, url) { const img = mkImg(p, cell.rect, url); cell.c.appendChild(img); const old = cell.img; t.frame(() => { if (old) old.style.opacity = "0"; }); t.after(() => { if (old) old.remove(); }, 1200); cell.img = img; cell.photo = p; }
    async function build() {
      const selected = [];
      const slots = bsp({ x: 0, y: 0, w: ctx.W, h: ctx.H }, SS.slots(deck, 9)).map(rect => {
        const photo = SS.take(deck, ctx.cursor, [wantFor(rect.w / rect.h)], selected)[0];
        selected.push(photo.seed); return { rect, photo };
      }).map(s => ({ ...s, url: ctx.url(s.photo, 1200) }));
      if (!await t.load(slots.map(s => s.url))) return false;
      slots.forEach(({ rect, photo, url }) => {
        const c = el("ss-mcell", wall), cell = { c, img: mkImg(photo, rect, url), photo, rect };
        setRect(cell, rect); c.appendChild(cell.img); cells.push(cell);
      });
      spotlight();
    }
    // Refresh one tile with an unseen photo; when the whole library is already on the
    // wall, exchange two same-appetite tiles' photos instead (crossfades, no movement).
    async function swap() {
      if (!cells.length) return;
      if (SS.unused(deck, onWall()).length) { const c = cells[Math.floor(Math.random() * cells.length)]; const p = SS.take(deck, ctx.cursor, [wantFor(c.rect.w / c.rect.h)], onWall())[0]; const url = ctx.url(p, 1200); if (!await t.load(url)) return false; setImg(c, p, url); return; }
      const groups = {};
      cells.forEach(c => { const k = wantFor(c.rect.w / c.rect.h); (groups[k] = groups[k] || []).push(c); });
      const eligible = Object.values(groups).filter(g => g.length >= 2);
      if (!eligible.length) return;
      const [a, b] = window.shuffled(eligible[Math.floor(Math.random() * eligible.length)]).slice(0, 2);
      const pa = a.photo, pb = b.photo, ua = ctx.url(pa, 1200), ub = ctx.url(pb, 1200); if (!await t.load([ua, ub])) return false; setImg(a, pb, ub); setImg(b, pa, ua);
    }
    // One tile gently lifts while the rest dim a touch; the focus wanders per beat.
    function spotlight() { const i = Math.floor(Math.random() * cells.length); cells.forEach((c, k) => c.c.classList.toggle("spot", k === i)); }
    t.start(build);
    // The spotlight wanders every beat; photos change only every fourth beat (~20s) —
    // anything faster reads as restless on a wall display.
    let beat = 0;
    t.every(spotlight, ctx.ambientMs); t.every(swap, ctx.microMs);
    return t.cleanup;
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
    const t = makeTimers(ctx); const frame = el("ss-scope", ctx.root); const barT = el("ss-bar top", ctx.root), barB = el("ss-bar bot", ctx.root); const grain = el("ss-grain", ctx.root);
    const layers = [mk(), mk()]; layers.forEach(l => frame.appendChild(l.root)); let cur = 0;
    function mk() { const root = el("ss-layer"); const img = el("ss-img", root); return { root, img }; }
    async function step() { const c = SS.counts(deck); const p = SS.take(deck, ctx.cursor, [c.pano ? "pano" : "landscape"])[0]; const u = ctx.url(p, 2400); if (!await t.load(u)) return false; const L = layers[1 - cur]; const wide = aspect(p) > 2.2; bg(L.img, u, wide ? "cover" : "contain"); if (wide && !hasFace(p)) { kbVars(L.img, p); L.img.style.animation = "none"; void L.img.offsetWidth; L.img.style.animation = ""; } else { faceBg(L.img, p); L.img.style.animation = "none"; } layers[cur].root.classList.remove("on"); L.root.classList.add("on"); cur = 1 - cur; }
    t.start(step); t.every(step, ctx.photoMs);
    return t.cleanup;
  }};

  // ---- 12 Hero + sidebar -----------------------------------------------------
  MODES.heroSidebar = { name: "Hero + Sidebar", run(ctx, deck) {
    const t = makeTimers(ctx); const stage = el("ss-hs", ctx.root); const heroCol = el("ss-col", stage); heroCol.style.flex = "1.9"; const heroCell = el("ss-cell", heroCol); const side = el("ss-col", stage); side.style.flex = "1";
    // Each pane shows the WHOLE photo (contain) over a soft blurred fill of itself — never a
    // cover-crop, so a face is never clipped (reuses the .ss-cell.contain styling). Panes crossfade.
    async function fill(cell, p, le) {
      const fillUrl = ctx.url(p, 900), imageUrl = ctx.url(p, le || 1400);
      if (!await t.load([fillUrl, imageUrl])) return false;
      cell.classList.add("contain");
      const wrap = el("ss-pane", cell);
      bg(el("ss-fill", wrap), fillUrl);
      bg(el("ss-img", wrap), imageUrl, "contain");
      const old = cell._wrap;
      t.frame(() => { if (old) old.style.opacity = "0"; });
      t.after(() => { if (old) old.remove(); }, 1100);
      cell._wrap = wrap;
    }
    // Track what's on screen (hero + both side cells) so no pane duplicates another.
    let sideCells = []; const shown = { hero: null, sides: [null, null] };
    function seeds() { return [shown.hero, ...shown.sides].filter(Boolean).map(p => p.seed); }
    t.start(async () => {
      const h0 = SS.take(deck, ctx.cursor, ["landscape"], seeds())[0]; shown.hero = h0;
      if (await fill(heroCell, h0, 1800) === false) return false;
      for (let i = 0; i < Math.min(2, SS.slots(deck, 3) - 1); i++) {
        const c = el("ss-cell", side); sideCells.push(c);
        const p = SS.take(deck, ctx.cursor, ["portrait"], seeds())[0]; shown.sides[i] = p;
        if (await fill(c, p, 1100) === false) return false;
      }
    });
    let turn = 0; t.every(async () => { if (!SS.unused(deck, seeds()).length) return; turn++; if (turn % 3 === 0 || !sideCells.length) { const p = SS.take(deck, ctx.cursor, ["landscape"], seeds())[0]; if (await fill(heroCell, p, 1800) === false) return false; shown.hero = p; } else { const i = turn % sideCells.length; const p = SS.take(deck, ctx.cursor, ["portrait"], seeds())[0]; if (await fill(sideCells[i], p, 1100) === false) return false; shown.sides[i] = p; } }, ctx.microMs);
    return t.cleanup;
  }};

  SS.MODES = MODES;
})();
