/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */
// Deterministic source fixtures, not a browser/device FPS benchmark. No dependencies.
const assert = require("node:assert/strict");
const fs = require("node:fs");
const path = require("node:path");
const vm = require("node:vm");
const assets = path.join(__dirname, "../app/src/main/assets/screensaver");

function fixture({ mode = "kenBurns", intervalSec = 30, photos, actualModes = true } = {}) {
  let now = 0, id = 0;
  const timers = new Map(), images = [], events = new Map();
  function element() {
    const classes = new Set();
    const node = {
      children: [], parent: null, removed: false, style: {
        setProperty(key, value) { this[key] = String(value); },
        getPropertyValue(key) { return this[key] || ""; },
      },
      classList: {
        add(...items) { items.forEach(x => classes.add(x)); },
        remove(...items) { items.forEach(x => classes.delete(x)); },
        contains(x) { return classes.has(x); },
        toggle(x, on) { if (on == null) on = !classes.has(x); on ? classes.add(x) : classes.delete(x); },
      },
      appendChild(child) { child.parent = this; this.children.push(child); return child; },
      remove() { this.removed = true; if (this.parent) this.parent.children = this.parent.children.filter(x => x !== this); },
      get className() { return [...classes].join(" "); },
      set className(s) { classes.clear(); s.split(/\s+/).filter(Boolean).forEach(x => classes.add(x)); },
      get innerHTML() { return ""; },
      set innerHTML(s) { assert.equal(s, ""); this.children.forEach(x => { x.removed = true; }); this.children = []; },
      get offsetWidth() { return 100; },
      getAnimations() { return []; },
    };
    return node;
  }
  function timeout(fn, ms, repeat = false) {
    const key = ++id;
    timers.set(key, { fn, due: now + Number(ms || 0), repeat: repeat ? Number(ms) : 0 });
    return key;
  }
  class Image {
    constructor() { images.push(this); this.naturalWidth = 1600; this.naturalHeight = 1067; }
    set src(s) { this.url = s; }
    get src() { return this.url; }
    decode() { this.decodingStarted = true; return new Promise((resolve, reject) => { this.decodeOK = resolve; this.decodeFail = reject; }); }
    load() { this.onload?.(); }
    fail() { this.onerror?.(new Error("fixture load failure")); }
  }
  const context = {
    Image, AbortController, innerWidth: 1920, innerHeight: 1280, screen: { width: 1920, height: 1280 },
    console: { log() {}, info() {} }, document: { createElement: element },
    setTimeout: timeout, clearTimeout: key => timers.delete(key),
    setInterval: (fn, ms) => timeout(fn, ms, true), clearInterval: key => timers.delete(key),
    requestAnimationFrame: fn => timeout(fn, 16), cancelAnimationFrame: key => timers.delete(key),
    addEventListener: (name, fn) => events.set(name, fn),
    removeEventListener: (name, fn) => { if (events.get(name) === fn) events.delete(name); },
    performance: { now: () => now }, Date: class extends Date { static now() { return now; } },
  };
  context.window = context;
  context.PHOTOS = photos || [
    { seed: "a", url: "fixture:a", w: 1600, h: 1067 },
    { seed: "b", url: "fixture:b", w: 1600, h: 1067 },
    { seed: "c", url: "fixture:c", w: 1600, h: 1067 },
  ];
  context.shuffled = xs => xs.slice();
  context.aspect = p => p.w / p.h;
  context.photoUrl = p => p.url;
  vm.createContext(context);
  for (const file of ["lib-engine.js", "modes.js", ...(actualModes ? ["mode-impls.js"] : []), "scheduler.js"]) {
    vm.runInContext(fs.readFileSync(path.join(assets, file), "utf8"), context, { filename: file });
  }
  const stage = element();
  const settle = async () => { for (let i = 0; i < 30; i++) await Promise.resolve(); };
  async function tick(ms) {
    await settle();
    const end = now + ms;
    for (;;) {
      const due = [...timers].filter(([, t]) => t.due <= end).sort((a, b) => a[1].due - b[1].due)[0];
      if (!due) break;
      const [key, t] = due; now = t.due; timers.delete(key);
      if (t.repeat) timers.set(key, { ...t, due: now + t.repeat });
      t.fn(); await settle();
    }
    now = end; await settle();
  }
  async function readyImages() {
    for (let i = 0; i < 8; i++) {
      images.filter(x => x.url && !x.decodingStarted).forEach(x => x.load());
      await settle();
      images.forEach(x => x.decodeOK?.());
      await settle(); await tick(20);
    }
  }
  function nodes(root = stage) { return [root, ...root.children.flatMap(nodes)]; }
  return { context, stage, images, timers, events, tick, settle, readyImages, nodes,
    start(opts = {}) { return context.SS.startEngine({ stage, mode, intervalSec, ...opts }); } };
}

const tests = [];
const test = (name, run) => tests.push({ name, run });
test("outgoing scene survives incoming load and decode, including failure", async () => {
  const f = fixture(), engine = f.start(); await f.settle(); await f.readyImages();
  const old = f.stage.children[0]; assert(old.classList.contains("on"));
  (engine.next || engine.advance)(); await f.settle();
  const incoming = f.stage.children.at(-1);
  await f.tick(2000); assert.equal(old.removed, false, "outgoing removed before load");
  const image = f.images.at(-1); image.load(); await f.settle();
  await f.tick(2000); assert.equal(old.removed, false, "outgoing removed before decode");
  image.decodeOK(); await f.settle(); await f.tick(1800);
  assert.equal(old.removed, true); assert(incoming.classList.contains("on"));
  engine.next(); await f.settle(); f.images.at(-1).fail(); await f.settle(); await f.tick(2000);
  assert.equal(incoming.removed, false, "failed image replaced last ready scene");
  engine.stop();
});
test("cleanup cancels pending image completion and a stopped engine cannot restart", async () => {
  const f = fixture(), engine = f.start(); await f.settle();
  const dead = f.stage.children[0], before = f.nodes(dead).map(x => x.className + JSON.stringify(x.style));
  const image = f.images[0]; engine.stop(); image.load(); await f.settle(); image.decodeOK?.();
  await f.settle(); await f.tick(35000);
  assert.deepEqual(f.nodes(dead).map(x => x.className + JSON.stringify(x.style)), before,
      "completion mutated a dead root");
  engine.advance(); await f.settle(); assert.equal(f.stage.children.length, 0);
  assert.equal(f.events.has("resize"), false);
});
test("manual next supersedes slow incoming work instead of building queued scenes", async () => {
  const f = fixture(), engine = f.start(); await f.settle(); await f.readyImages();
  engine.next(); await f.settle(); const obsolete = f.stage.children.at(-1), image = f.images.at(-1);
  engine.next(); await f.settle();
  assert.equal(obsolete.removed, true); assert.equal(f.stage.children.length, 2);
  image.load(); await f.settle(); image.decodeOK?.(); await f.settle();
  assert.equal(obsolete.classList.contains("on"), false);
  await f.readyImages(); await f.tick(1800); assert.equal(f.stage.children.length, 1); engine.stop();
});
test("single-photo next and previous navigate the deck", async () => {
  const f = fixture(), engine = f.start(); await f.settle(); await f.readyImages();
  const hero = () => f.nodes().filter(x => x.classList.contains("ss-hero") && x.style.backgroundImage).at(-1).style.backgroundImage;
  assert.match(hero(), /fixture:a/);
  engine.next(); await f.settle(); await f.readyImages(); await f.tick(1800); assert.match(hero(), /fixture:b/);
  engine.previous(); await f.settle(); await f.readyImages(); await f.tick(1800); assert.match(hero(), /fixture:a/);
  engine.stop();
});
test("pause preserves remaining dwell and suspends pending promotion until resume", async () => {
  const f = fixture(), engine = f.start(); await f.settle(); await f.readyImages();
  await f.tick(10000); engine.pause();
  const count = f.images.length; await f.tick(40000); assert.equal(f.images.length, count);
  engine.resume(); await f.tick(19000); assert.equal(f.images.length, count);
  await f.tick(1500); assert(f.images.length > count);
  engine.next(); await f.settle(); engine.pause();
  const old = f.stage.children[0]; await f.readyImages(); await f.tick(3000);
  assert.equal(old.removed, false);
  engine.resume(); await f.tick(1800); assert.equal(old.removed, true); engine.stop();
});
test("every named style gates initial composition on decode and cancels late completion", async () => {
  const inventory = ["kenBurns", "diptych", "framed", "galleryWall", "push", "memories",
    "panorama", "polaroid", "mosaic", "filmstrip", "cinema", "heroSidebar"];
  for (const mode of inventory) {
    const f = fixture({ mode }), engine = f.start(); await f.settle(); await f.tick(1000);
    assert.equal(f.stage.children[0].classList.contains("on"), false, `${mode} visible before readiness`);
    await f.readyImages(); assert(f.stage.children[0].classList.contains("on"), `${mode} never became ready`);
    engine.stop(); await f.tick(40000); assert.equal(f.stage.children.length, 0);
    const pending = fixture({ mode }), canceled = pending.start(); await pending.settle();
    const dead = pending.stage.children[0];
    pending.images.forEach(x => x.load()); await pending.settle();
    const state = () => pending.nodes(dead).map(x => x.className + JSON.stringify(x.style));
    const before = state(); canceled.stop();
    pending.images.forEach(x => x.decodeOK?.()); await pending.settle(); await pending.tick(40000);
    assert.deepEqual(state(), before, `${mode} mutated a dead root after decode`);
    assert.equal(pending.timers.size, 0, `${mode} leaked timers`);
  }
});
test("motion duration follows the configured dwell and covers the fade", async () => {
  for (const intervalSec of [5, 30, 120]) {
    const f = fixture({ intervalSec }), engine = f.start(); await f.settle(); await f.readyImages();
    const duration = parseFloat(f.stage.children[0].style.getPropertyValue("--motion-duration"));
    assert(duration >= intervalSec * 1000 + 2200, "motion ends during the configured hold/fade");
    const css = fs.readFileSync(path.join(assets, "styles.css"), "utf8");
    for (const selector of [".ss-layer.on .ss-hero", ".ss-single.on .ss-hero", ".ss-scope .ss-layer.on .ss-img"]) {
      const rule = css.slice(css.indexOf(selector)).split("}")[0];
      assert.match(rule, /var\(--motion-duration/, `${selector} ignores the configured motion duration`);
    }
    engine.stop();
  }
});
test("cached face focus stays still throughout a mismatched mosaic pan", async () => {
  const f = fixture({ mode: "mosaic", photos: [{ seed: "face", url: "fixture:face", w: 1000, h: 1500, fx: .5, fy: .1 }] });
  const engine = f.start(); await f.settle(); await f.readyImages();
  const image = f.nodes().find(x => x.classList.contains("panv"));
  assert(image); assert.equal(image.style.animation, "none");
  assert.match(image.style.transform, /translateY\(0%\)/); engine.stop();
});
test("decode failure and timeout retain the ready scene with bounded retries", async () => {
  const f = fixture(), engine = f.start(); await f.settle(); await f.readyImages();
  const old = f.stage.children[0];
  engine.next(); await f.settle(); const image = f.images.at(-1);
  image.load(); await f.settle(); image.decodeFail(new Error("corrupt fixture"));
  await f.settle(); await f.tick(2000); assert.equal(old.removed, false);
  engine.next(); await f.settle(); await f.tick(32000);
  assert.equal(old.removed, false); assert(f.stage.children.length <= 2); engine.stop();
});
test("pause during a dissolve keeps the outgoing layer until resumed", async () => {
  const f = fixture(), engine = f.start(); await f.settle(); await f.readyImages();
  const old = f.stage.children[0]; engine.next(); await f.settle(); await f.readyImages();
  engine.pause(); await f.tick(30000); assert.equal(old.removed, false);
  engine.resume(); await f.tick(1600); assert.equal(old.removed, true); engine.stop();
});
test("bundled page exposes the exact bridge before measurement and honors early pause/exit", async () => {
  const inline = fs.readFileSync(path.join(assets, "index.html"), "utf8").match(/<script>([\s\S]*?)<\/script>/)[1];
  for (const exit of [false, true]) {
    const f = fixture(), elements = new Map([["stage", f.stage]]);
    f.context.document.getElementById = id => {
      if (!elements.has(id)) elements.set(id, f.context.document.createElement("div"));
      return elements.get(id);
    };
    f.context.location = { search: "?nativeClock=1" };
    f.context.URLSearchParams = URLSearchParams;
    f.context.SS_CONFIG = { mode: "kenBurns", intervalSec: 30 };
    let measured;
    f.context.premeasureAll = () => new Promise(resolve => { measured = resolve; });
    vm.runInContext(inline, f.context, { filename: "index.html" });
    const bridge = f.context.immortalSlideshow;
    assert(bridge, "bridge unavailable while dimensions are pending");
    assert.deepEqual(Object.keys(bridge).sort(), ["next", "pause", "previous", "resume"]);
    bridge.pause();
    if (exit) f.events.get("pagehide")();
    measured(); await f.settle(); await f.tick(50);
    assert.equal(f.images.length, 0, "early pause or exit was lost");
    if (!exit) {
      bridge.resume(); await f.settle(); await f.readyImages();
      assert(elements.get("loading").classList.contains("hide"));
      f.events.get("pagehide")();
    }
    assert.equal(f.stage.children.length, 0);
  }
});
test("filmstrip next/previous move the centered photograph even when the deck fits on the strip", async () => {
  const f = fixture({ mode: "filmstrip" }), engine = f.start(); await f.settle(); await f.readyImages();
  const center = () => f.nodes().find(x => x.classList.contains("ss-slide") && x.classList.contains("center"))
    .children[0].style.backgroundImage;
  assert.match(center(), /fixture:a/);
  engine.next(); await f.settle(); await f.readyImages(); await f.tick(1800);
  assert.match(center(), /fixture:b/);
  engine.previous(); await f.settle(); await f.readyImages(); await f.tick(1800);
  assert.match(center(), /fixture:a/); engine.stop();
});


test("CSS classes retain motion ownership while transition and programmatic animations pause", async () => {
  const f = fixture(), engine = f.start(); await f.settle(); await f.readyImages();
  function animation(extra = {}, state = "running") {
    return { ...extra, playState: state, pauses: 0, plays: 0,
      pause() { this.pauses++; this.playState = "paused"; },
      play() { this.plays++; this.playState = "running"; } };
  }
  const css = animation({ animationName: "breathe" });
  const transition = animation({ transitionProperty: "opacity" });
  const programmatic = animation(), held = animation({}, "paused");
  const layer = f.stage.children[0];
  layer.getAnimations = options => {
    assert.equal(options.subtree, true);
    return [css, transition, programmatic, held];
  };
  try {
    engine.pause(); assert(layer.classList.contains("paused"));
    assert.equal(css.pauses, 0, "API pause would take ownership from the CSS rules");
    assert.equal(transition.playState, "paused"); assert.equal(programmatic.playState, "paused");
    engine.resume(); assert.equal(layer.classList.contains("paused"), false);
    assert.equal(css.plays, 0, "API play would override later CSS retirement/freeze");
    assert.equal(transition.plays, 1); assert.equal(programmatic.plays, 1);
    assert.equal(held.plays, 0, "An independently paused animation must remain paused");
  } finally { engine.stop(); }
});
test("CSS animation ownership also respects the native interface without a name property", async () => {
  const f = fixture(), engine = f.start(); await f.settle(); await f.readyImages();
  class CSSAnimation {
    constructor() { this.playState = "running"; this.pauses = 0; this.plays = 0; }
    pause() { this.pauses++; this.playState = "paused"; }
    play() { this.plays++; this.playState = "running"; }
  }
  f.context.CSSAnimation = CSSAnimation;
  const css = new CSSAnimation(), layer = f.stage.children[0];
  layer.getAnimations = () => [css];
  try {
    engine.pause(); assert(layer.classList.contains("paused"));
    engine.resume(); assert.equal(layer.classList.contains("paused"), false);
    assert.equal(css.pauses, 0); assert.equal(css.plays, 0);
  } finally { engine.stop(); }
});

(async () => {
  let failures = 0;
  for (const { name, run } of tests) {
    try { await run(); console.log(`PASS ${name}`); }
    catch (error) { failures++; console.error(`FAIL ${name}\n${error.stack}`); }
  }
  console.log(`${tests.length - failures}/${tests.length} source fixtures passed (not device benchmarks).`);
  process.exitCode = failures ? 1 : 0;
})();
