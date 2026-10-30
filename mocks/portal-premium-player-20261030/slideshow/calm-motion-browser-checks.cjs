/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */
"use strict";

// Run only after the production worker and review server have announced readiness:
// NODE_PATH=<bundled node_modules> <bundled node> calm-motion-browser-checks.cjs
// This sidecar serves unchanged production assets, decodes finite original/generated
// fixtures in an isolated Chrome process, and writes only renderer-calm-motion/.
const fs = require("node:fs");
const path = require("node:path");
const http = require("node:http");
const crypto = require("node:crypto");
const { chromium } = require("playwright");
const { PNG } = require("pngjs");

const here = __dirname;
const repo = path.resolve(here, "../../..");
const assetDir = path.join(repo, "app/src/main/assets/screensaver");
const evidenceDir = path.join(here, "evidence/renderer-calm-motion");
const previewBase = process.env.CALM_MOTION_PREVIEW_URL || "http://127.0.0.1:8891";
const modes = ["kenBurns", "diptych", "framed", "push", "memories", "panorama", "mosaic", "cinema", "heroSidebar"];
const movingModes = new Set(["kenBurns", "framed", "push", "memories", "panorama", "mosaic", "cinema"]);
const assetNames = ["index.html", "styles.css", "photos.js", "lib-engine.js", "modes.js",
  "mode-impls.js", "scheduler.js", "cinema-grain.png"];
const assetBytes = Object.fromEntries(assetNames.map(name => [name, fs.readFileSync(path.join(assetDir, name))]));
const sourceText = assetBytes["index.html"].toString("utf8");
const bridgeMethod = process.env.CALM_MOTION_BRIDGE_METHOD ||
  (sourceText.match(/(?:Android|host)\.(\w*[Pp]hoto[Mm]otion\w*)\s*\(/) || [])[1] || "getPhotoMotion";
const gallery = JSON.parse(fs.readFileSync(path.join(here, "assets/gallery-fixtures.json"), "utf8"))
  .map((photo, id) => ({ ...photo, id, seed: "gallery-" + id }));
const wide = [
  { url: "/fixtures/panorama-wide.svg", w: 2400, h: 800 },
  { url: "/fixtures/panorama-extreme.svg", w: 2400, h: 300 },
  gallery[1], gallery[7],
].map((photo, id) => ({ ...photo, id, seed: "wide-" + id }));
const fixtureNames = new Set(["dunes.png", "tidal.png", "panorama-wide.svg", "panorama-extreme.svg",
  ...gallery.map(photo => path.basename(photo.url))]);
const generated = new Map();
const checks = [];
const browserErrors = [];
const report = {
  startedAt: new Date().toISOString(),
  viewport: { width: 2160, height: 1440 },
  sources: Object.fromEntries(assetNames.map(name => [name,
    crypto.createHash("sha256").update(assetBytes[name]).digest("hex")])),
  bridgeMethod, checks, browserErrors,
  sampling: "Pause baselines are captured after two render frames so the CSS compositor pause boundary is complete.",
  navigation: "Manual next verifies a new decoded scene and advanced deck cursor; orientation buckets may legitimately revisit the same photo set.",
  limitations: [
    "Actual desktop Chrome source/lifecycle verification; no physical Portal FPS or GPU claim.",
    "Only the review server's original art and finite generated art were decoded or exported.",
    "Lifecycle decode races hold completion after a real decode; they do not emulate every network failure.",
  ],
};
function check(name, condition, numeric = {}) {
  checks.push({ name, pass: !!condition, numeric });
  if (!condition) console.error("FAIL " + name + " " + JSON.stringify(numeric));
}
const sleep = ms => new Promise(resolve => setTimeout(resolve, ms));
async function section(name, fn) {
  const started = Date.now();
  try { await fn(); } catch (error) {
    check(name + " completes", false, { error: error.message });
  }
  console.log(name + " (" + (Date.now() - started) + "ms)");
}
function art(ratio) {
  const w = ratio < 1 ? 96 : Math.round(1200 * ratio), h = ratio < 1 ? Math.round(96 / ratio) : 1200;
  const key = ratio.toString();
  if (!generated.has(key)) {
    generated.set(key, { w, h, svg: `<svg xmlns="http://www.w3.org/2000/svg" width="${w}" height="${h}">
      <defs><linearGradient id="sky" x2="1" y2="1"><stop stop-color="#365b71"/>
      <stop offset=".5" stop-color="#84aab2"/><stop offset="1" stop-color="#e2bd85"/></linearGradient></defs>
      <rect width="${w}" height="${h}" fill="url(#sky)"/>
      <path d="M0 ${h * .75} L${w * .25} ${h * .35} L${w * .5} ${h * .7}
      L${w * .8} ${h * .4} L${w} ${h * .75} V${h} H0Z" fill="#416f65"/>
      <rect width="${Math.max(3, w * .04)}" height="${h}" fill="#a65b42"/>
      <rect x="${w * .96}" width="${w * .04}" height="${h}" fill="#286e73"/>
      <circle cx="${w * .8}" cy="${h * .5}" r="${Math.min(w, h) * .08}" fill="#ffe3a8"/>
      </svg>` });
  }
  return { url: "/generated/" + key + ".svg", w, h, seed: "generated-" + key, id: key };
}

// Observational wrappers expose actual scheduler options and mode owners, without
// replacing any production renderer, timer, decode, CSS, or navigation function.
const observeBoot = `<script>
  window.__runs = [];
  for (const [id, mode] of Object.entries(SS.MODES)) {
    const run = mode.run;
    mode.run = function(ctx, deck) {
      const start = ctx.cursor.i;
      const cleanup = run.call(this, ctx, deck);
      window.__runs.push({ id, ctx, cleanup, start });
      return cleanup;
    };
  }
  const start = SS.startEngine;
  SS.startEngine = function(opts) {
    window.__engineOptions = { mode: opts.mode, photoMotion: opts.photoMotion };
    return window.__engine = start.call(this, opts);
  };
</script>`;
const server = http.createServer((req, res) => {
  const url = new URL(req.url, "http://127.0.0.1");
  let bytes, type;
  if (url.pathname === "/render") {
    const mode = url.searchParams.get("mode"), motion = url.searchParams.get("motion");
    if (!modes.includes(mode)) { res.writeHead(400); res.end(); return; }
    const photos = ["panorama", "cinema"].includes(mode) ? wide : gallery;
    const motionMethod = motion === "default" ? "" :
      `,${bridgeMethod}:()=>${motion === "still" ? "false" : "true"}`;
    const bridge = `<base href="/assets/"><script>window.Android={
      getPhotos:()=>${JSON.stringify(JSON.stringify(photos))},getMode:()=>${JSON.stringify(mode)},
      getIntervalSec:()=>5,scaledUrlForSize:(url,edge)=>{
        (window.__derivatives||(window.__derivatives=[])).push({url,edge});return url;
      }${motionMethod}};</script>`;
    bytes = sourceText.replace("<head>", "<head>" + bridge)
      .replace('<script src="scheduler.js"></script>', '<script src="scheduler.js"></script>' + observeBoot);
    type = "text/html";
  } else if (url.pathname === "/unit") {
    bytes = `<html><head><link rel="stylesheet" href="/assets/styles.css"></head><body>
      <div id="stage"></div><script>window.Android={
      getPhotos:()=>${JSON.stringify(JSON.stringify(gallery))},scaledUrlForSize:url=>url};</script>
      <script src="/assets/photos.js"></script><script src="/assets/lib-engine.js"></script>
      <script src="/assets/modes.js"></script><script src="/assets/mode-impls.js"></script>
      <script src="/assets/scheduler.js"></script></body></html>`;
    type = "text/html";
  } else if (url.pathname.startsWith("/assets/") && assetNames.includes(path.basename(url.pathname))) {
    const name = path.basename(url.pathname); bytes = assetBytes[name];
    type = name.endsWith(".css") ? "text/css" : name.endsWith(".png") ? "image/png" : "application/javascript";
  } else if (url.pathname.startsWith("/fixtures/") && fixtureNames.has(path.basename(url.pathname))) {
    const name = path.basename(url.pathname); bytes = fs.readFileSync(path.join(here, "assets", name));
    type = name.endsWith(".svg") ? "image/svg+xml" : "image/png";
  } else if (url.pathname.startsWith("/generated/")) {
    const fixture = generated.get(path.basename(url.pathname, ".svg"));
    if (fixture) { bytes = fixture.svg; type = "image/svg+xml"; }
  }
  if (bytes == null) { res.writeHead(404); res.end(); return; }
  res.writeHead(200, { "Content-Type": type, "Cache-Control": "no-store" }); res.end(bytes);
});

// Serialized into the page. IDs let stability checks compare the same retained
// DOM owners rather than accidentally compare one photo with its replacement.
function stateInPage(root = document.querySelector("#stage")) {
  window.__photoIds ||= new WeakMap(); window.__nextPhotoId ||= 0;
  const photoNodes = [...root.querySelectorAll(".ss-hero,.ss-print,.ss-img,.ss-pan")];
  function item(node) {
    if (!__photoIds.has(node)) __photoIds.set(node, ++window.__nextPhotoId);
    const css = getComputedStyle(node), box = node.getBoundingClientRect();
    let opacity = 1, visible = true;
    for (let parent = node; parent && parent !== root.parentElement; parent = parent.parentElement) {
      const style = getComputedStyle(parent); opacity *= Number(style.opacity);
      visible &&= style.visibility !== "hidden" && style.display !== "none";
    }
    const match = css.backgroundImage.match(/url\(["']?(.*?)["']?\)/);
    const url = match ? new URL(match[1], location.href).pathname : null;
    return { id: __photoIds.get(node), cls: node.className, url,
      transform: css.transform, animation: css.animationName, hint: css.willChange,
      playState: css.animationPlayState, opacity, visible: visible && opacity > .01,
      size: css.backgroundSize, repeat: css.backgroundRepeat, position: css.backgroundPosition,
      width: box.width, height: box.height, left: box.left, top: box.top,
      right: box.right, bottom: box.bottom };
  }
  const photos = photoNodes.map(item);
  return { photos, live: photos.filter(photo => photo.visible && photo.url),
    mosaic: [...root.querySelectorAll(".mosaic-cell")].map(cell => ({
      transform: getComputedStyle(cell).transform, spot: cell.classList.contains("spot"),
    })), derivatives: (window.__derivatives || []).map(({ edge }) => edge) };
}
function transformDelta(a, b) {
  let max = 0, compared = 0;
  for (const first of a.live) {
    const second = b.live.find(photo => photo.id === first.id && photo.url === first.url);
    if (!second) continue;
    const numbers = value => value === "none" ? [1, 0, 0, 1, 0, 0] :
      (value.match(/-?\d+(?:\.\d+)?(?:e[+-]?\d+)?/gi) || []).map(Number);
    const x = numbers(first.transform), y = numbers(second.transform);
    max = Math.max(max, ...x.map((number, i) => Math.abs(number - y[i])));
    compared++;
  }
  return { max, compared };
}
const quiet = state => state.photos.every(photo => photo.animation === "none" && photo.hint === "auto") &&
  state.mosaic.every(cell => cell.transform === "none" && !cell.spot);
const urls = state => state.live.map(photo => photo.url).sort().join("|");
function pixels(buffer) {
  const png = PNG.sync.read(buffer); let bright = 0, colorful = 0, sampled = 0;
  for (let y = 20; y < png.height; y += 20) for (let x = 20; x < png.width; x += 20) {
    const offset = (y * png.width + x) * 4;
    const rgb = [...png.data.subarray(offset, offset + 3)];
    bright += Math.max(...rgb) > 65 ? 1 : 0;
    colorful += Math.max(...rgb) - Math.min(...rgb) > 20 ? 1 : 0; sampled++;
  }
  return { width: png.width, height: png.height, brightFraction: bright / sampled, colorfulFraction: colorful / sampled };
}
async function ready(page) {
  await page.locator("#loading.hide").waitFor({ state: "attached", timeout: 8000 });
  await page.waitForFunction(() => {
    const beat = document.querySelector(".ss-beat.on");
    return beat && Number(getComputedStyle(beat).opacity) === 1;
  }, null, { timeout: 8000 });
}
async function settleNavigation(page, old) {
  await page.waitForFunction(count => __runs.length > count, old, { timeout: 5000 });
  await page.waitForFunction(() => document.querySelectorAll(".ss-beat").length === 1 &&
    +getComputedStyle(document.querySelector(".ss-beat.on")).opacity === 1, null, { timeout: 6000 });
}
async function integration(page, base, mode, motion) {
  await page.goto(`${base}/render?nativeClock=1&mode=${mode}&motion=${motion}`);
  await ready(page);
  const first = await page.evaluate(stateInPage);
  const wiring = await page.evaluate(() => ({
    option: __engineOptions.photoMotion, contexts: __runs.map(run => run.ctx.photoMotion),
  }));
  const enabled = motion !== "still";
  check(`${mode}/${motion}: bridge to scheduler to mode`, wiring.option === enabled &&
    wiring.contexts.every(value => value === enabled), wiring);
  const screenshot = pixels(await page.screenshot());
  check(`${mode}/${motion}: first ready composition has decoded visible photo pixels`,
    first.live.length > 0 && first.live.every(photo => photo.width > 25 && photo.height > 25) &&
    screenshot.brightFraction > .025 && screenshot.colorfulFraction > .01,
    { liveCount: first.live.length, ...screenshot, edges: first.derivatives });
  check(`${mode}/${motion}: fitting keeps display-resolution requests`, first.derivatives.length > 0 &&
    first.derivatives.every(edge => edge >= 1000 && edge <= 2400), { edges: first.derivatives });
  await page.waitForTimeout(400);
  const steady = await page.evaluate(stateInPage), delta = transformDelta(first, steady);
  check(`${mode}/${motion}: expected steady transforms`, delta.compared > 0 &&
    (enabled && movingModes.has(mode) ? delta.max > .00001 : delta.max <= .00001),
    { ...delta, animations: steady.live.map(photo => photo.animation), hints: steady.live.map(photo => photo.hint) });
  if (!enabled) check(`${mode}/still: all photo animations none and hints auto`, quiet(steady),
    { photos: steady.photos.map(({ animation, hint }) => ({ animation, hint })), mosaic: steady.mosaic });
  await page.evaluate(async () => {
    immortalSlideshow.pause();
    // CSS pause reaches the compositor at the next render boundary. Compare
    // held frames after that boundary, not the pre-pause computed frame.
    await new Promise(requestAnimationFrame); await new Promise(requestAnimationFrame);
  });
  const held = await page.evaluate(stateInPage);
  await page.waitForTimeout(250);
  const paused = await page.evaluate(stateInPage), heldDelta = transformDelta(held, paused);
  check(`${mode}/${motion}: pause holds photo transforms and owners`, heldDelta.compared > 0 &&
    heldDelta.max <= .00001 && urls(held) === urls(paused), heldDelta);
  await page.evaluate(async () => {
    immortalSlideshow.resume(); immortalSlideshow.pause();
    await new Promise(requestAnimationFrame); await new Promise(requestAnimationFrame);
  });
  const rePaused = await page.evaluate(stateInPage);
  await page.waitForTimeout(180);
  const afterRePause = await page.evaluate(stateInPage);
  const rePauseDelta = transformDelta(rePaused, afterRePause);
  check(`${mode}/${motion}: immediate re-pause does not restart motion`, rePauseDelta.compared > 0 &&
    rePauseDelta.max <= .00001 && urls(rePaused) === urls(afterRePause), rePauseDelta);
  await page.evaluate(() => immortalSlideshow.resume());
  {
    const before = await page.evaluate(() => __runs.length);
    const acceptedNext = await page.evaluate(() => immortalSlideshow.next());
    await settleNavigation(page, before);
    const next = await page.evaluate(stateInPage);
    const navigation = await page.evaluate(() => ({
      scenes: __runs.length, firstStart: __runs[0].start, nextStart: __runs.at(-1).start,
    }));
    // Orientation bucket selection can revisit a set after a complete deck
    // revolution. Engine generation and cursor advancement establish navigation.
    check(`${mode}/${motion}: manual next presents a new composition`, acceptedNext === true &&
      navigation.scenes > before && navigation.nextStart > navigation.firstStart &&
      next.live.length > 0 && (enabled || quiet(next)),
      { before: urls(steady), after: urls(next), liveCount: next.live.length,
        sameSortedPhotoSet: urls(next) === urls(steady), ...navigation });
    const nextCount = await page.evaluate(() => __runs.length);
    const acceptedPrevious = await page.evaluate(() => immortalSlideshow.previous());
    await settleNavigation(page, nextCount);
    const previous = await page.evaluate(stateInPage);
    const restoredStart = await page.evaluate(() => ({
      first: __runs[0].start, previous: __runs.at(-1).start,
    }));
    check(`${mode}/${motion}: manual previous restores a decoded composition`, acceptedPrevious === true &&
      previous.live.length > 0 && (enabled || quiet(previous)) && restoredStart.first === restoredStart.previous,
      { liveCount: previous.live.length, urls: urls(previous), ...restoredStart });
  }
  const closed = await page.evaluate(() => {
    window.__retainedPhotos = [...document.querySelectorAll("#stage .ss-hero,#stage .ss-print,#stage .ss-img,#stage .ss-pan")];
    const owners = __retainedPhotos.map(node => node.closest(".ss-beat"));
    __engine.stop();
    const values = __retainedPhotos.map((node, index) => ({ url: node.style.backgroundImage,
      ownedAfterCleanup: owners[index].contains(node), hint: node.style.willChange }));
    return { stageChildren: document.querySelector("#stage").children.length, values };
  });
  check(`${mode}/${motion}: engine stop removes scenes and releases photo assets`,
    closed.stageChildren === 0 && closed.values.every(photo => !photo.ownedAfterCleanup ||
      !photo.url || photo.url === "none"), closed);
}

async function autoAndDecodeRaces(page, base, mode, enabled, initial) {
  await page.goto(`${base}/unit`);
  const deck = ["panorama", "cinema"].includes(mode) ? wide : gallery;
  const result = await page.evaluate(async ({ mode, enabled, initial, deck }) => {
    const root = document.createElement("div"); root.style.cssText = "position:fixed;inset:0";
    document.querySelector("#stage").append(root);
    const original = window.preloadDecoded;
    let hold = initial, release, reach, admissions = 0, records = 0, decoded = 0;
    const reached = new Promise(resolve => { reach = resolve; });
    const deadline = (promise, ms = 4000) => Promise.race([promise,
      new Promise((_, reject) => setTimeout(() => reject(Error("real decode gate timeout")), ms))]);
    const delay = ms => new Promise(resolve => setTimeout(resolve, ms));
    window.preloadDecoded = (url, signal) => original(url, signal).then(value => {
      decoded++;
      if (!hold) return value;
      hold = false;
      return new Promise(resolve => { release = () => resolve(value); reach(); });
    });
    let cleanup;
    const capture = () => [...root.querySelectorAll(".ss-hero,.ss-print,.ss-img,.ss-pan")].map(node => ({
      url: node.style.backgroundImage, transform: getComputedStyle(node).transform,
      animation: getComputedStyle(node).animationName, hint: getComputedStyle(node).willChange,
    }));
    try {
      cleanup = SS.MODES[mode].run({ root, cursor: { i: 0 }, W: innerWidth, H: innerHeight,
        photoMotion: enabled, photoMs: 260, microMs: 260, ambientMs: 150, motionMs: 6000,
        url: photo => { admissions++; return photo.url; }, record: () => records++ }, deck);
      if (!initial) { await deadline(cleanup.ready); hold = true; }
      await deadline(reached); cleanup.pause(); release(); await delay(25);
      cleanup.resume(); cleanup.pause();
      await new Promise(requestAnimationFrame); await new Promise(requestAnimationFrame);
      const before = capture(), admitted = admissions;
      await delay(320);
      const after = capture();
      const stable = JSON.stringify(before) === JSON.stringify(after) && admissions === admitted;
      const quietHeld = enabled || after.every(photo => photo.animation === "none" && photo.hint === "auto");
      cleanup.resume(); const ready = await deadline(cleanup.ready);
      await delay(initial ? 370 : 60);
      const advanced = records > 1;
      cleanup.pause();
      const live = capture(), retained = [...root.querySelectorAll(".ss-hero,.ss-print,.ss-img,.ss-pan")];
      cleanup(); const closedAdmissions = admissions;
      cleanup.resume(); cleanup.pause();
      root.querySelectorAll("*").forEach(node => node.dispatchEvent(new Event("transitionend")));
      await delay(320);
      const released = retained.every(node => !root.contains(node) ||
        !node.style.backgroundImage || node.style.backgroundImage === "none");
      return { ready, stable, quietHeld, advanced, records, admissions, decoded,
        liveResources: live.filter(photo => photo.url && photo.url !== "none").length,
        released, noLateAdmission: admissions === closedAdmissions };
    } finally { if (cleanup) cleanup(); window.preloadDecoded = original; root.remove(); }
  }, { mode, enabled, initial, deck });
  const label = `${mode}/${enabled ? "moving" : "still"}/${initial ? "initial" : "replacement"} decode`;
  check(label + ": real decode resumes through a re-pause without stale work",
    result.ready === true && result.stable && result.quietHeld && result.decoded > 0, result);
  if (initial) check(label + ": automatic photo advancement remains active",
    result.advanced && result.liveResources > 0, result);
  check(label + ": cleanup releases retained assets and rejects late work",
    result.released && result.noLateAdmission, result);
}

async function geometry(page, base, mode, ratio, focal) {
  const photo = art(ratio);
  if (focal != null) { photo.fx = focal; photo.fy = focal; }
  const numeric = await page.evaluate(async ({ mode, photo }) => {
    const root = document.createElement("div"); root.style.cssText = "position:fixed;inset:0";
    document.querySelector("#stage").append(root);
    const cleanup = SS.MODES[mode].run({ root, cursor: { i: 0 }, W: innerWidth, H: innerHeight,
      photoMotion: false, photoMs: 30000, microMs: 30000, ambientMs: 30000, motionMs: 30000,
      url: photo => photo.url }, [photo]);
    try {
      const ready = await cleanup.ready;
      const image = root.querySelector(mode === "panorama" ? ".on .ss-pan" : ".on .mosaic-photo");
      const decoded = new Image(); decoded.src = photo.url; await decoded.decode();
      const css = getComputedStyle(image), box = image.getBoundingClientRect();
      const width = parseFloat(css.width), height = parseFloat(css.height);
      const scale = Math.min(width / decoded.naturalWidth, height / decoded.naturalHeight);
      const renderedW = decoded.naturalWidth * scale, renderedH = decoded.naturalHeight * scale;
      const matrix = css.transform === "none" ? new DOMMatrixReadOnly() : new DOMMatrixReadOnly(css.transform);
      const axis = image.classList.contains("panv") ? "vertical" : "horizontal";
      return { ready, decodedAspect: decoded.naturalWidth / decoded.naturalHeight,
        naturalWidth: decoded.naturalWidth, naturalHeight: decoded.naturalHeight,
        width, height, renderedW, renderedH, axis,
        left: box.left, top: box.top, right: box.right, bottom: box.bottom,
        tx: matrix.m41, ty: matrix.m42, animation: css.animationName, hint: css.willChange,
        size: css.backgroundSize, repeat: css.backgroundRepeat,
        focalX: box.left + (width - renderedW) / 2 + renderedW * (photo.fx ?? .5),
        focalY: box.top + (height - renderedH) / 2 + renderedH * (photo.fy ?? .5),
        mosaicCells: root.querySelectorAll(".mosaic-cell").length };
    } finally { cleanup(); root.remove(); }
  }, { mode, photo });
  const common = numeric.ready === true && Math.abs(numeric.decodedAspect - ratio) < .00001 &&
    numeric.width > 0 && numeric.height > 0 && numeric.width <= 4320.1 && numeric.height <= 2880.1 &&
    numeric.renderedW * numeric.renderedH > 10000 &&
    numeric.size === "contain" && numeric.repeat === "no-repeat" &&
    numeric.animation === "none" && numeric.hint === "auto";
  let bounded, anchored;
  if (mode === "panorama") {
    const width = Math.min(4320, Math.max(2160, 1440 * ratio)), height = Math.min(1440, width / ratio);
    bounded = Math.abs(numeric.width - width) < .1 && Math.abs(numeric.height - height) < .1 &&
      numeric.left <= .1 && numeric.right >= 2159.9 && numeric.top >= -.1 && numeric.bottom <= 1440.1;
    const focalFraction = focal ?? .5;
    const visible = 2160 / width;
    const start = visible >= 1 ? 0 : Math.min(1, Math.max(0, (focalFraction - visible / 2) / (1 - visible)));
    anchored = Math.abs(numeric.tx - (2160 - width) * start) < .1 &&
      numeric.focalX >= -.1 && numeric.focalX <= 2160.1;
  } else {
    bounded = numeric.mosaicCells === 1 && numeric.left <= 2160 && numeric.right >= 0 &&
      numeric.top <= 1440 && numeric.bottom >= 0;
    anchored = numeric.focalX >= -.1 && numeric.focalX <= 2160.1 &&
      numeric.focalY >= -.1 && numeric.focalY <= 1440.1;
    if (focal == null) anchored &&= Math.abs(numeric.focalX - 1080) < .2 &&
      Math.abs(numeric.focalY - 720) < .2;
  }
  check(`${mode}/still ${ratio}:1 focal=${focal ?? "center"} decoded aspect and bounded anchor`,
    common && bounded && anchored, numeric);
}

async function previewCapture(page, mode) {
  await page.goto(`${previewBase}/preview.html?mode=${mode}&motion=still&review=1&nativeClock=1`);
  await ready(page);
  const state = await page.evaluate(stateInPage);
  check(`${mode}: original-fixture review URL is quiet`, quiet(state) && state.live.length > 0,
    { animations: state.live.map(photo => photo.animation), hints: state.live.map(photo => photo.hint),
      photos: state.live.map(photo => photo.url) });
  const file = mode + "-still-2160x1440.png";
  const image = await page.screenshot({ path: path.join(evidenceDir, file) });
  const numeric = pixels(image);
  check(`${mode}: quiet review PNG preserves visible original art`,
    numeric.width === 2160 && numeric.height === 1440 &&
    numeric.brightFraction > .025 && numeric.colorfulFraction > .01, { file, ...numeric });
}

(async () => {
  fs.mkdirSync(evidenceDir, { recursive: true });
  let browser, context;
  await new Promise(resolve => server.listen(0, "127.0.0.1", resolve));
  const base = "http://127.0.0.1:" + server.address().port;
  try {
    browser = await chromium.launch({ channel: "chrome", headless: true });
    report.browser = browser.version();
    context = await browser.newContext({ viewport: report.viewport, deviceScaleFactor: 1 });
    await context.route("**/*", route => {
      const url = new URL(route.request().url());
      return ["127.0.0.1", "localhost"].includes(url.hostname) || ["data:", "blob:"].includes(url.protocol)
        ? route.continue() : route.abort();
    });
    const page = await context.newPage();
    page.on("pageerror", error => browserErrors.push(error.message));
    page.setDefaultTimeout(7000);
    for (const mode of modes) for (const motion of ["still", "default"]) {
      await section(`${mode}/${motion} production bridge and lifecycle`, () => integration(page, base, mode, motion));
    }
    for (const mode of modes) for (const enabled of [false, true]) for (const initial of [true, false]) {
      await section(`${mode}/${enabled ? "moving" : "still"}/${initial ? "initial" : "replacement"} decode race`,
        () => autoAndDecodeRaces(page, base, mode, enabled, initial));
    }
    await page.goto(`${base}/unit`);
    for (const mode of ["panorama", "mosaic"]) {
      const ratios = mode === "panorama" ? [1.3, 1.5, 2, 3, 8, 10] : [.025, .5, 1.5, 3, 10];
      for (const ratio of ratios) {
        for (const focal of [null, 0, .25, .5, .8, 1]) {
          await section(`${mode} geometry ${ratio}/${focal ?? "center"}`, () => geometry(page, base, mode, ratio, focal));
        }
      }
    }
    for (const mode of ["kenBurns", "panorama", "mosaic", "cinema"]) {
      await section(mode + " review capture", () => previewCapture(page, mode));
    }
    check("No uncaught browser errors", browserErrors.length === 0, { count: browserErrors.length, errors: browserErrors });
    const changedDuringRun = assetNames.filter(name => !fs.readFileSync(path.join(assetDir, name)).equals(assetBytes[name]));
    check("Production asset snapshot stayed unchanged during the run", changedDuringRun.length === 0,
      { changedAssets: changedDuringRun });
  } catch (error) {
    check("Full run completes", false, { error: error.stack });
  } finally {
    if (context) await context.close();
    if (browser) await browser.close();
    await new Promise(resolve => server.close(resolve));
    report.completedAt = new Date().toISOString();
    report.elapsedMs = Date.parse(report.completedAt) - Date.parse(report.startedAt);
    report.counts = { total: checks.length, passed: checks.filter(check => check.pass).length,
      failed: checks.filter(check => !check.pass).length };
    fs.writeFileSync(path.join(evidenceDir, "checks.json"), JSON.stringify(report, null, 2) + "\n");
    console.log(JSON.stringify({ ...report.counts, elapsedMs: report.elapsedMs, evidence: evidenceDir }));
    process.exitCode = report.counts.failed ? 1 : 0;
  }
})();
