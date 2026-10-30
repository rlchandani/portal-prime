// Copyright (c) 2026 Starbright Lab.
// Licensed under the MIT license in the repository root.
// Actual production scripts/CSS, original fixtures, isolated local HTTP/Chrome.
const assert = require("node:assert/strict");
const fs = require("node:fs");
const path = require("node:path");
const http = require("node:http");
const { execFileSync } = require("node:child_process");
const { chromium } = require("playwright");
const here = __dirname, repo = path.resolve(here, "../../..");
const assets = path.join(repo, "app/src/main/assets/screensaver");
const evidence = process.env.SLIDESHOW_EVIDENCE_DIR || path.join(here, "evidence");
fs.mkdirSync(evidence, { recursive: true });
const readAsset = name => process.env.SLIDESHOW_ASSET_REF
  ? execFileSync("git", ["-c", "core.hooksPath=/dev/null", "--no-optional-locks", "show",
      `${process.env.SLIDESHOW_ASSET_REF}:app/src/main/assets/screensaver/${name}`],
      { cwd: repo, encoding: "utf8" })
  : fs.readFileSync(path.join(assets, name), "utf8");
let closedSlow = 0, admittedSlow = 0;
const held = new Map(), checks = [], errors = [];
const fixture = name => fs.readFileSync(path.join(here, "assets", name + ".png"));
const galleryPhotos = JSON.parse(fs.readFileSync(path.join(here, "assets", "gallery-fixtures.json")))
  .map((photo, id) => ({ ...photo, id, seed: "gallery-" + id }));
const server = http.createServer((req, res) => {
  const u = new URL(req.url, "http://local");
  if (/^\/slow\/(dunes|tidal)\.png$/.test(u.pathname)) {
    admittedSlow++; held.set(res, path.basename(u.pathname, ".png"));
    res.on("close", () => { held.delete(res); closedSlow++; });
    return;
  }
  let content, type;
  if (["/diptych-unit", "/motion-unit", "/gallery-unit"].includes(u.pathname)) {
    const unitPhotos = u.pathname === "/gallery-unit" ? galleryPhotos :
      ["dunes", "tidal"].map((name, id) => ({
        id, url: "/fixtures/" + name + ".png", w: 480, h: 720,
      }));
    content = `<html><head><link rel="stylesheet" href="/assets/styles.css"></head><body>
      <div id="stage"></div><script src="/assets/lib-engine.js"></script>
      <script src="/assets/photos.js"></script><script src="/assets/modes.js"></script>
      <script src="/assets/mode-impls.js"></script><script>
      window.PHOTOS=${JSON.stringify(unitPhotos)};
      </script></body></html>`;
    type = "text/html";
  } else if (u.pathname === "/render") {
    const slow = u.searchParams.has("slow"), unknown = u.searchParams.has("unknown");
    const mode = u.searchParams.get("mode") || "diptych";
    const photos = mode === "galleryWall" ? galleryPhotos : ["dunes", "tidal"].map(name => ({
      url: (slow ? "/slow/" : "/fixtures/") + name + ".png",
      w: unknown ? 0 : 480, h: unknown ? 0 : 720,
    }));
    const bridge = `<base href="/assets/"><script>window.Android={
      getPhotos:()=>${JSON.stringify(JSON.stringify(photos))},getMode:()=>${JSON.stringify(mode)},
      getIntervalSec:()=>30,scaledUrlForSize:url=>url};</script>`;
    content = readAsset("index.html").replace("<head>", "<head>" + bridge);
    type = "text/html";
  } else if (/^\/fixtures\/(dunes|tidal)\.png$/.test(u.pathname)) {
    content = fixture(path.basename(u.pathname, ".png")); type = "image/png";
  } else if (galleryPhotos.some(photo => photo.url === u.pathname)) {
    content = fs.readFileSync(path.join(here, "assets", path.basename(u.pathname)));
    type = "image/svg+xml";
  } else if (/^\/assets\/(?:styles\.css|photos\.js|lib-engine\.js|modes\.js|mode-impls\.js|scheduler\.js)$/.test(u.pathname)) {
    content = readAsset(path.basename(u.pathname));
    type = u.pathname.endsWith(".css") ? "text/css" : "application/javascript";
  } else { res.writeHead(404); res.end(); return; }
  res.writeHead(200, { "Content-Type": type, "Cache-Control": "no-store" }); res.end(content);
});
const demand = (name, condition) => { assert.ok(condition, name); checks.push(name); };
async function until(condition) {
  const end = Date.now() + 5000;
  while (!condition()) {
    if (Date.now() >= end) throw Error("Bounded browser condition did not settle");
    await new Promise(resolve => setTimeout(resolve, 20));
  }
}
(async () => {
  await new Promise(resolve => server.listen(0, "127.0.0.1", resolve));
  const base = `http://127.0.0.1:${server.address().port}`;
  let browser;
  try {
    browser = await chromium.launch({ channel: "chrome", headless: true });
    const page = await browser.newPage({ viewport: { width: 2160, height: 1440 } });
    await page.addInitScript(() => {
      const NativeImage = window.Image;
      const source = Object.getOwnPropertyDescriptor(HTMLImageElement.prototype, "src");
      window.imageAdmissions = [];
      window.Image = function (...args) {
        const image = new NativeImage(...args), record = { url: "" };
        window.imageAdmissions.push(record);
        Object.defineProperty(image, "src", {
          get() { return source.get.call(image); },
          set(value) { record.url = value; source.set.call(image, value); },
        });
        return image;
      };
    });
    page.on("pageerror", error => errors.push(error.message));
    for (const mode of ["diptych", "framed", "kenBurns", "push", "galleryWall"]) {
      await page.goto(`${base}/render?nativeClock=1&mode=${mode}`);
      await page.locator("#loading.hide").waitFor({ state: "attached" });
      await page.waitForFunction(() => getComputedStyle(document.querySelector(".ss-beat.on")).opacity === "1" &&
        [...document.querySelectorAll(".ss-frame.on,.ss-art.on,.ss-layer.on")].every(n => getComputedStyle(n).opacity === "1"));
      if (mode === "diptych") {
        const state = await page.evaluate(() => {
          const frame = document.querySelector(".ss-frame.on");
          return { panes: frame.children.length, fills: frame.querySelectorAll(".ss-fill").length,
            padding: parseFloat(getComputedStyle(frame).paddingTop),
            images: [...frame.querySelectorAll(".ss-img")].map(n => ({
              size: getComputedStyle(n).backgroundSize, filter: getComputedStyle(n).filter,
              motion: getComputedStyle(n).animationName,
            })) };
        });
        demand("Diptych displays two complete static photos without blur", state.panes === 2 &&
          state.fills === 0 && state.images.every(n => n.size === "contain" && n.filter === "none" && n.motion === "none"));
        demand("Diptych matte inset scales to the actual viewport", Math.abs(state.padding - 43.2) < .1);
      }
      await page.screenshot({ path: path.join(evidence, mode + ".png") });
      demand(mode + " actual renderer becomes ready", await page.locator("#loading").evaluate(n => n.classList.contains("hide")));
    }
    await page.goto(`${base}/diptych-unit`);
    await page.evaluate(async () => {
      const root = document.createElement("div");
      root.style.cssText = "position:fixed;inset:0";
      document.querySelector("#stage").append(root);
      const ctx = { root, cursor: { i: 0 }, W: innerWidth, H: innerHeight,
        photoMs: 2800, url: photo => photo.url };
      window.diptychCheck = window.SS.MODES.diptych.run(ctx, window.PHOTOS);
      await window.diptychCheck.ready;
    });
    demand("Diptych initial readiness publishes an opaque scene without a second startup fade",
      await page.evaluate(() => [...document.querySelectorAll(".ss-frame")]
        .filter(n => n.children.length).every(n => +getComputedStyle(n).opacity === 1)));
    await page.waitForFunction(() => {
      const nodes = [...document.querySelectorAll(".ss-frame")];
      return nodes.filter(n => n.children.length && +getComputedStyle(n).opacity > 0).length === 2 &&
        nodes.some(n => {
          const opacity = +getComputedStyle(n).opacity;
          return opacity > .1 && opacity < .9;
        });
    });
    const dissolve = await page.evaluate(() => [...document.querySelectorAll(".ss-frame")]
      .filter(n => n.children.length).map(n => ({
        opacity: +getComputedStyle(n).opacity, top: n === n.parentNode.lastElementChild,
      })));
    demand("Diptych outgoing remains opaque throughout the dissolve",
      dissolve.some(n => n.opacity === 1));
    demand("Diptych incoming fades above the outgoing whole-photo scene",
      dissolve.some(n => n.top && n.opacity > 0 && n.opacity < 1));
    await page.evaluate(async () => {
      window.diptychCheck.pause();
      // Animation.pause schedules a compositor pause; ready acknowledges that change.
      await Promise.all([...document.querySelectorAll(".ss-frame")]
        .flatMap(frame => frame.getAnimations()).map(animation => animation.ready));
    });
    const frozen = await page.evaluate(() => [...document.querySelectorAll(".ss-frame")]
      .map(n => +getComputedStyle(n).opacity));
    await page.waitForTimeout(250);
    demand("Diptych pause freezes the live dissolve",
      await page.evaluate(values => [...document.querySelectorAll(".ss-frame")]
        .every((n, i) => Math.abs(+getComputedStyle(n).opacity - values[i]) < .001), frozen));
    await page.evaluate(() => window.diptychCheck.resume());
    await page.waitForFunction(() => [...document.querySelectorAll(".ss-frame")]
      .filter(n => n.children.length).length === 1);
    demand("Diptych retires outgoing resources only after incoming is opaque",
      await page.evaluate(() => [...document.querySelectorAll(".ss-frame")]
        .filter(n => n.children.length).every(n => +getComputedStyle(n).opacity === 1)));
    await page.evaluate(() => window.diptychCheck());
    demand("Diptych cleanup releases both scene resources",
      await page.locator(".ss-frame .ss-img").count() === 0);
    await page.waitForTimeout(1900);
    demand("Diptych stopped ownership cannot repopulate scene resources",
      await page.locator(".ss-frame .ss-img").count() === 0);
    await page.goto(`${base}/diptych-unit`);
    await page.evaluate(async () => {
      Element.prototype.getAnimations = undefined;
      const root = document.createElement("div");
      root.style.cssText = "position:fixed;inset:0";
      document.querySelector("#stage").append(root);
      window.diptychCheck = window.SS.MODES.diptych.run({
        root, cursor: { i: 0 }, W: innerWidth, H: innerHeight,
        photoMs: 2800, url: photo => photo.url,
      }, window.PHOTOS);
      await window.diptychCheck.ready;
    });
    await page.waitForFunction(() => [...document.querySelectorAll(".ss-frame")]
      .some(n => n.children.length && +getComputedStyle(n).opacity > .1 &&
        +getComputedStyle(n).opacity < .9));
    const fallbackFrozen = await page.evaluate(() => {
      window.diptychCheck.pause();
      return [...document.querySelectorAll(".ss-frame")].map(n => +getComputedStyle(n).opacity);
    });
    await page.waitForTimeout(1900);
    demand("Diptych old-WebView fallback freezes opacity and retains outgoing resources",
      await page.evaluate(values => [...document.querySelectorAll(".ss-frame")]
        .every((n, i) => Math.abs(+getComputedStyle(n).opacity - values[i]) < .001)
        && [...document.querySelectorAll(".ss-frame")].filter(n => n.children.length).length === 2,
      fallbackFrozen));
    await page.evaluate(() => window.diptychCheck.resume());
    await page.waitForFunction(() => [...document.querySelectorAll(".ss-frame")]
      .filter(n => n.children.length).length === 1);
    demand("Diptych old-WebView fallback resumes and retires safely",
      await page.evaluate(() => [...document.querySelectorAll(".ss-frame")]
        .filter(n => n.children.length).every(n => +getComputedStyle(n).opacity === 1)));
    await page.evaluate(() => window.diptychCheck());
    await page.goto(`${base}/diptych-unit`);
    const cancelledPublication = await page.evaluate(async () => {
      const original = window.preloadDecoded;
      const root = document.createElement("div");
      document.querySelector("#stage").append(root);
      let cleanup, atCleanup;
      window.preloadDecoded = (url, signal) => {
        const prepared = original(url, signal);
        prepared.then(() => queueMicrotask(() => queueMicrotask(() => {
          cleanup(); atCleanup = root.querySelectorAll(".ss-img").length;
        })), () => {});
        return prepared;
      };
      const url = "data:image/svg+xml," + encodeURIComponent(
        '<svg xmlns="http://www.w3.org/2000/svg" width="30" height="50">' +
        '<rect width="30" height="50" fill="red"/></svg>');
      cleanup = SS.MODES.diptych.run({
        root, cursor: { i: 0 }, photoMs: 30000, url: photo => photo.url,
      }, [{ id: 0, url, w: 30, h: 50 }]);
      const ready = await cleanup.ready;
      window.preloadDecoded = original;
      return { ready, atCleanup, afterReady: root.querySelectorAll(".ss-img").length };
    });
    demand("Diptych cleanup between decode admission and publication rejects the prepared scene",
      cancelledPublication.ready === false && cancelledPublication.atCleanup === 0 &&
      cancelledPublication.afterReady === 0);
    await page.goto(`${base}/motion-unit`);
    await page.evaluate(async () => {
      Element.prototype.getAnimations = undefined;
      const root = document.createElement("div");
      root.style.cssText = "position:fixed;inset:0";
      document.querySelector("#stage").append(root);
      window.motionCheck = SS.MODES.kenBurns.run({
        root, cursor: { i: 0 }, W: innerWidth, H: innerHeight,
        photoMs: 3400, url: photo => photo.url,
      }, window.PHOTOS);
      await window.motionCheck.ready;
    });
    demand("Ken Burns readiness publishes one opaque photo without a second startup fade",
      await page.evaluate(() => [...document.querySelectorAll(".ss-layer")]
        .filter(n => n.classList.contains("on")).every(n => +getComputedStyle(n).opacity === 1)));
    demand("Ken Burns uses a quiet matte without full-viewport blurred duplicate photos",
      await page.locator(".ss-fill").count() === 0 &&
      await page.evaluate(() => [...document.querySelectorAll(".ss-layer")]
        .every(n => getComputedStyle(n).filter === "none")));
    await page.waitForFunction(() => [...document.querySelectorAll(".ss-layer")]
      .some(n => +getComputedStyle(n).opacity > .1 && +getComputedStyle(n).opacity < .9));
    const movingFade = await page.evaluate(() => [...document.querySelectorAll(".ss-layer")]
      .filter(n => getComputedStyle(n.querySelector(".ss-hero")).backgroundImage !== "none")
      .map(n => ({ opacity: +getComputedStyle(n).opacity, top: n === n.parentNode.lastElementChild })));
    demand("Ken Burns keeps the outgoing photo opaque under the incoming dissolve",
      movingFade.some(n => n.opacity === 1) &&
      movingFade.some(n => n.top && n.opacity > 0 && n.opacity < 1));
    const heldMotion = await page.evaluate(async () => {
      window.motionCheck.pause();
      await new Promise(resolve => requestAnimationFrame(() => requestAnimationFrame(resolve)));
      return [...document.querySelectorAll(".ss-layer")].map(n => ({
        opacity: getComputedStyle(n).opacity,
        transform: getComputedStyle(n.querySelector(".ss-hero")).transform,
      }));
    });
    await page.waitForTimeout(450);
    demand("Ken Burns pause freezes both motion and dissolve without the newer animation API",
      await page.evaluate(before => [...document.querySelectorAll(".ss-layer")].every((n, i) =>
        getComputedStyle(n).opacity === before[i].opacity &&
        getComputedStyle(n.querySelector(".ss-hero")).transform === before[i].transform), heldMotion));
    await page.evaluate(() => window.motionCheck.resume());
    await page.waitForFunction(() => [...document.querySelectorAll(".ss-hero")]
      .filter(n => getComputedStyle(n).backgroundImage !== "none").length === 1);
    demand("Ken Burns retires the old photo and compositor hint after the incoming photo is opaque",
      await page.evaluate(() => [...document.querySelectorAll(".ss-layer")].every(n =>
        n.classList.contains("retired")
          ? getComputedStyle(n.querySelector(".ss-hero")).backgroundImage === "none" &&
            getComputedStyle(n.querySelector(".ss-hero")).willChange === "auto"
          : +getComputedStyle(n).opacity === 1)));
    await page.evaluate(() => window.motionCheck());
    demand("Ken Burns cleanup releases photo URLs and stops motion in both owned slots",
      await page.evaluate(() => [...document.querySelectorAll(".ss-hero")].every(n =>
        getComputedStyle(n).backgroundImage === "none" &&
        getComputedStyle(n).animationName === "none")));
    await page.goto(`${base}/motion-unit`);
    const faceMotion = await page.evaluate(async () => {
      const root = document.createElement("div");
      document.querySelector("#stage").append(root);
      const cleanup = SS.MODES.kenBurns.run({
        root, cursor: { i: 0 }, W: innerWidth, H: innerHeight,
        photoMs: 30000, url: photo => photo.url,
      }, [{ ...window.PHOTOS[0], fx: .2, fy: .3 }]);
      const ready = await cleanup.ready;
      const hero = root.querySelector(".on .ss-hero"), style = getComputedStyle(hero);
      const result = { ready, size: style.backgroundSize, motion: style.animationName,
        compositor: style.willChange };
      cleanup(); return result;
    });
    demand("Ken Burns cached face hints keep the whole photo still without a permanent compositor hint",
      faceMotion.ready && faceMotion.size === "contain" && faceMotion.motion === "none" &&
      faceMotion.compositor === "auto");
    await page.goto(`${base}/motion-unit`);
    const cancelledMotion = await page.evaluate(async () => {
      const original = window.preloadDecoded;
      const root = document.createElement("div"); document.querySelector("#stage").append(root);
      let cleanup, atCleanup;
      const images = () => [...root.querySelectorAll(".ss-hero")]
        .filter(n => getComputedStyle(n).backgroundImage !== "none").length;
      window.preloadDecoded = (url, signal) => {
        const prepared = original(url, signal);
        prepared.then(() => queueMicrotask(() => queueMicrotask(() => {
          cleanup(); atCleanup = images();
        })), () => {});
        return prepared;
      };
      cleanup = SS.MODES.kenBurns.run({
        root, cursor: { i: 0 }, W: innerWidth, H: innerHeight,
        photoMs: 30000, url: photo => photo.url,
      }, [window.PHOTOS[0]]);
      const ready = await cleanup.ready;
      window.preloadDecoded = original;
      return { ready, atCleanup, afterReady: images() };
    });
    demand("Ken Burns cancellation rejects a decoded photo immediately before publication",
      cancelledMotion.ready === false && cancelledMotion.atCleanup === 0 &&
      cancelledMotion.afterReady === 0);
    await page.goto(`${base}/motion-unit`);
    await page.evaluate(async () => {
      Element.prototype.getAnimations = undefined;
      const root = document.createElement("div"); root.style.cssText = "position:fixed;inset:0";
      document.querySelector("#stage").append(root);
      const landscape = "data:image/svg+xml," + encodeURIComponent(
        '<svg xmlns="http://www.w3.org/2000/svg" width="960" height="640">' +
        '<rect width="960" height="640" fill="#52695c"/></svg>');
      window.framedCheck = SS.MODES.framed.run({
        root, cursor: { i: 0 }, W: innerWidth, H: innerHeight,
        photoMs: 3700, url: photo => photo.url,
      }, [window.PHOTOS[0], { id: 2, url: landscape, w: 960, h: 640 }]);
      await window.framedCheck.ready;
    });
    demand("Framed Print readiness publishes an opaque scene without a second startup fade",
      await page.evaluate(() => +getComputedStyle(document.querySelector(".ss-art.on")).opacity === 1));
    demand("Framed Print scenes cover the outgoing frame with the identical room background",
      await page.evaluate(() => [...document.querySelectorAll(".ss-art")].every(n =>
        getComputedStyle(n).backgroundImage === getComputedStyle(document.querySelector(".ss-room")).backgroundImage)));
    await page.waitForFunction(() => [...document.querySelectorAll(".ss-art")]
      .some(n => +getComputedStyle(n).opacity > .1 && +getComputedStyle(n).opacity < .9));
    demand("Framed Print retains the opaque outgoing print under the incoming scene",
      await page.evaluate(() => {
        const roots = [...document.querySelectorAll(".ss-art")];
        return roots.some(n => +getComputedStyle(n).opacity === 1) &&
          roots.some(n => n === n.parentNode.lastElementChild &&
            +getComputedStyle(n).opacity > 0 && +getComputedStyle(n).opacity < 1);
      }));
    const heldPrint = await page.evaluate(async () => {
      window.framedCheck.pause();
      await new Promise(resolve => requestAnimationFrame(() => requestAnimationFrame(resolve)));
      return [...document.querySelectorAll(".ss-art")].map(n => ({
        opacity: getComputedStyle(n).opacity,
        transform: getComputedStyle(n.querySelector(".ss-print")).transform,
      }));
    });
    await page.waitForTimeout(450);
    demand("Framed Print pause freezes both breathe and opacity without the newer animation API",
      await page.evaluate(before => [...document.querySelectorAll(".ss-art")].every((n, i) =>
        getComputedStyle(n).opacity === before[i].opacity &&
        getComputedStyle(n.querySelector(".ss-print")).transform === before[i].transform), heldPrint));
    await page.evaluate(() => window.framedCheck.resume());
    await page.waitForFunction(() => [...document.querySelectorAll(".ss-print")]
      .filter(n => getComputedStyle(n).backgroundImage !== "none").length === 1);
    demand("Framed Print releases retired photo URLs and compositor hints after full incoming opacity",
      await page.evaluate(() => [...document.querySelectorAll(".ss-art")].every(n =>
        n.classList.contains("retired")
          ? getComputedStyle(n.querySelector(".ss-print")).backgroundImage === "none" &&
            getComputedStyle(n.querySelector(".ss-print")).willChange === "auto"
          : +getComputedStyle(n).opacity === 1)));
    await page.evaluate(() => window.framedCheck());
    demand("Framed Print terminal cleanup releases both photo slots and their animation",
      await page.evaluate(() => [...document.querySelectorAll(".ss-print")].every(n =>
        getComputedStyle(n).backgroundImage === "none" &&
        getComputedStyle(n).animationName === "none")));
    await page.goto(`${base}/motion-unit`);
    const facePrint = await page.evaluate(async () => {
      const root = document.createElement("div"); document.querySelector("#stage").append(root);
      const cleanup = SS.MODES.framed.run({
        root, cursor: { i: 0 }, W: innerWidth, H: innerHeight,
        photoMs: 30000, url: photo => photo.url,
      }, [{ ...window.PHOTOS[0], fx: .2, fy: .3 }]);
      const ready = await cleanup.ready;
      const print = root.querySelector(".on .ss-print"), style = getComputedStyle(print);
      const result = { ready, size: style.backgroundSize, motion: style.animationName,
        compositor: style.willChange, width: parseFloat(style.width), height: parseFloat(style.height) };
      cleanup(); return result;
    });
    demand("Framed Print face hints retain an upright complete static print with its original aspect",
      facePrint.ready && facePrint.size === "contain" && facePrint.motion === "none" &&
      facePrint.compositor === "auto" && Math.abs(facePrint.width / facePrint.height - 2 / 3) < .002);
    await page.goto(`${base}/motion-unit`);
    const cancelledPrint = await page.evaluate(async () => {
      const original = window.preloadDecoded;
      const root = document.createElement("div"); document.querySelector("#stage").append(root);
      let cleanup, atCleanup;
      const images = () => [...root.querySelectorAll(".ss-print")]
        .filter(n => getComputedStyle(n).backgroundImage !== "none").length;
      window.preloadDecoded = (url, signal) => {
        const prepared = original(url, signal);
        prepared.then(() => queueMicrotask(() => queueMicrotask(() => {
          cleanup(); atCleanup = images();
        })), () => {});
        return prepared;
      };
      cleanup = SS.MODES.framed.run({
        root, cursor: { i: 0 }, W: innerWidth, H: innerHeight,
        photoMs: 30000, url: photo => photo.url,
      }, [window.PHOTOS[0]]);
      const ready = await cleanup.ready; window.preloadDecoded = original;
      return { ready, atCleanup, afterReady: images() };
    });
    demand("Framed Print cancellation rejects the decoded print immediately before publication",
      cancelledPrint.ready === false && cancelledPrint.atCleanup === 0 && cancelledPrint.afterReady === 0);
    await page.goto(`${base}/motion-unit`);
    await page.evaluate(async () => {
      Element.prototype.getAnimations = undefined;
      const root = document.createElement("div");
      root.style.cssText = "position:fixed;inset:0";
      document.querySelector("#stage").append(root);
      window.pushCheck = SS.MODES.push.run({
        root, cursor: { i: 0 }, W: innerWidth, H: innerHeight,
        photoMs: 3400, motionMs: 6000, url: photo => photo.url,
      }, window.PHOTOS);
      await window.pushCheck.ready;
    });
    demand("Cinematic Push readiness publishes one complete opaque scene",
      await page.evaluate(() => [...document.querySelectorAll(".ss-layer")]
        .filter(n => n.classList.contains("on")).length === 1 &&
        [...document.querySelectorAll(".ss-layer.on")]
          .every(n => +getComputedStyle(n).opacity === 1)));
    demand("Cinematic Push uses whole photos on matte without duplicate blurred images",
      await page.locator(".ss-fill").count() === 0 &&
      await page.evaluate(() => [...document.querySelectorAll(".ss-layer")].every(n =>
        getComputedStyle(n).filter === "none" &&
        (getComputedStyle(n.querySelector(".ss-hero")).backgroundImage === "none" ||
          getComputedStyle(n.querySelector(".ss-hero")).backgroundSize === "contain"))));
    await page.waitForFunction(() => [...document.querySelectorAll(".ss-layer")]
      .some(n => +getComputedStyle(n).opacity > .1 && +getComputedStyle(n).opacity < .9));
    demand("Cinematic Push retains an opaque outgoing photo beneath its incoming dissolve",
      await page.evaluate(() => {
        const layers = [...document.querySelectorAll(".ss-layer")];
        return layers.some(n => +getComputedStyle(n).opacity === 1) &&
          +getComputedStyle(layers.at(-1)).opacity > 0 &&
          +getComputedStyle(layers.at(-1)).opacity < 1;
      }));
    const heldPush = await page.evaluate(async () => {
      window.pushCheck.pause();
      await new Promise(resolve => requestAnimationFrame(() => requestAnimationFrame(resolve)));
      return [...document.querySelectorAll(".ss-layer")].map(n => ({
        opacity: getComputedStyle(n).opacity,
        transform: getComputedStyle(n.querySelector(".ss-hero")).transform,
      }));
    });
    await page.waitForTimeout(450);
    demand("Cinematic Push old-WebView pause freezes photo motion and scene opacity",
      await page.evaluate(before => [...document.querySelectorAll(".ss-layer")].every((n, i) =>
        getComputedStyle(n).opacity === before[i].opacity &&
        getComputedStyle(n.querySelector(".ss-hero")).transform === before[i].transform), heldPush));
    await page.evaluate(() => window.pushCheck.resume());
    await page.waitForFunction(() => [...document.querySelectorAll(".ss-hero")]
      .filter(n => getComputedStyle(n).backgroundImage !== "none").length === 1);
    demand("Cinematic Push retires the old photo URL and promotion only after incoming opacity one",
      await page.evaluate(() => [...document.querySelectorAll(".ss-layer")].every(n =>
        n.classList.contains("retired")
          ? getComputedStyle(n.querySelector(".ss-hero")).backgroundImage === "none" &&
            getComputedStyle(n.querySelector(".ss-hero")).willChange === "auto"
          : +getComputedStyle(n).opacity === 1)));
    await page.evaluate(() => window.pushCheck());
    demand("Cinematic Push cleanup releases both image resources and their animations",
      await page.evaluate(() => [...document.querySelectorAll(".ss-hero")].every(n =>
        getComputedStyle(n).backgroundImage === "none" &&
        getComputedStyle(n).animationName === "none")));
    await page.goto(`${base}/motion-unit`);
    const pushFace = await page.evaluate(async () => {
      const root = document.createElement("div"); document.querySelector("#stage").append(root);
      const cleanup = SS.MODES.push.run({
        root, cursor: { i: 0 }, W: innerWidth, H: innerHeight,
        photoMs: 30000, motionMs: 32400, url: photo => photo.url,
      }, [{ ...window.PHOTOS[0], fx: .2, fy: .3 }]);
      await cleanup.ready;
      const photo = root.querySelector(".on .ss-hero"), style = getComputedStyle(photo);
      const result = { size: style.backgroundSize, animation: style.animationName, promotion: style.willChange };
      cleanup(); return result;
    });
    demand("Cinematic Push cached face hints keep the entire photo still without promotion",
      pushFace.size === "contain" && pushFace.animation === "none" && pushFace.promotion === "auto");
    await page.goto(`${base}/motion-unit`);
    const pushBounds = await page.evaluate(async () => {
      const root = document.createElement("div");
      root.style.cssText = "position:fixed;inset:0";
      document.querySelector("#stage").append(root);
      const cleanup = SS.MODES.push.run({
        root, cursor: { i: 0 }, W: innerWidth, H: innerHeight,
        photoMs: 30000, motionMs: 1, url: photo => photo.url,
      }, [window.PHOTOS[0]]);
      await cleanup.ready; await new Promise(resolve => setTimeout(resolve, 50));
      const box = root.querySelector(".on .ss-hero").getBoundingClientRect();
      const result = box.left >= 0 && box.top >= 0 && box.right <= innerWidth && box.bottom <= innerHeight;
      cleanup(); return result;
    });
    demand("Cinematic Push keeps the whole-photo container inside the screen at its final zoom", pushBounds);
    await page.goto(`${base}/motion-unit`);
    const cancelledPush = await page.evaluate(async () => {
      const original = window.preloadDecoded;
      const root = document.createElement("div"); document.querySelector("#stage").append(root);
      let cleanup, atCleanup;
      const images = () => [...root.querySelectorAll(".ss-hero")]
        .filter(n => getComputedStyle(n).backgroundImage !== "none").length;
      window.preloadDecoded = (url, signal) => {
        const prepared = original(url, signal);
        prepared.then(() => queueMicrotask(() => queueMicrotask(() => {
          cleanup(); atCleanup = images();
        })), () => {});
        return prepared;
      };
      cleanup = SS.MODES.push.run({
        root, cursor: { i: 0 }, W: innerWidth, H: innerHeight,
        photoMs: 30000, motionMs: 32400, url: photo => photo.url,
      }, [window.PHOTOS[0]]);
      const ready = await cleanup.ready; window.preloadDecoded = original;
      return { ready, atCleanup, afterReady: images() };
    });
    demand("Cinematic Push cancellation cannot republish a prepared photo after cleanup",
      cancelledPush.ready === false && cancelledPush.atCleanup === 0 && cancelledPush.afterReady === 0);
    await page.goto(`${base}/gallery-unit`);
    await page.evaluate(async () => {
      Element.prototype.getAnimations = undefined;
      const root = document.createElement("div");
      root.style.cssText = "position:fixed;inset:0";
      document.querySelector("#stage").append(root);
      window.galleryCheck = SS.MODES.galleryWall.run({
        root, cursor: { i: 0 }, W: innerWidth, H: innerHeight,
        photoMs: 30000, microMs: 2400, url: photo => photo.url,
      }, window.PHOTOS);
      await window.galleryCheck.ready;
    });
    demand("Gallery Wall initially publishes one opaque static whole photo per cell",
      await page.evaluate(() => {
        const cells = [...document.querySelectorAll(".ss-gcell")];
        return cells.length > 1 && cells.length <= 10 && cells.every(cell => {
          const images = [...cell.children].filter(n => getComputedStyle(n).backgroundImage !== "none");
          return cell.children.length === 2 && images.length === 1 && images.every(n => {
            const style = getComputedStyle(n);
            return +style.opacity === 1 && style.backgroundSize === "contain" &&
              style.backgroundColor === "rgb(28, 27, 25)" &&
              style.animationName === "none" && style.willChange === "auto";
          });
        });
      }));
    demand("Gallery Wall keeps every tile inside the actual 2160 by 1440 viewport",
      await page.evaluate(() => [...document.querySelectorAll(".ss-gcell")].every(n => {
        const box = n.getBoundingClientRect();
        return box.left >= 15 && box.top >= 15 &&
          box.right <= innerWidth - 15 && box.bottom <= innerHeight - 15;
      })));
    await page.waitForFunction(() => [...document.querySelectorAll(".gallery-photo")]
      .some(n => +getComputedStyle(n).opacity > .1 && +getComputedStyle(n).opacity < .9));
    demand("Gallery Wall fades the incoming tile above an opaque outgoing photo",
      await page.evaluate(() => [...document.querySelectorAll(".ss-gcell")].some(cell => {
        const incoming = cell.lastElementChild, outgoing = cell.firstElementChild;
        return +getComputedStyle(incoming).opacity > 0 &&
          +getComputedStyle(incoming).opacity < 1 && +getComputedStyle(outgoing).opacity === 1;
      })));
    const heldGallery = await page.evaluate(() => {
      window.galleryCheck.pause();
      return [...document.querySelectorAll(".gallery-photo")].map(n => ({
        opacity: getComputedStyle(n).opacity, url: getComputedStyle(n).backgroundImage,
      }));
    });
    await page.waitForTimeout(1400);
    demand("Gallery Wall old-WebView pause holds opacity and photo resources past the fade deadline",
      await page.evaluate(before => [...document.querySelectorAll(".gallery-photo")]
        .every((n, i) => getComputedStyle(n).opacity === before[i].opacity &&
          getComputedStyle(n).backgroundImage === before[i].url), heldGallery));
    await page.evaluate(() => window.galleryCheck.resume());
    await page.waitForFunction(() => [...document.querySelectorAll(".ss-gcell")].every(cell =>
      [...cell.children].filter(n => getComputedStyle(n).backgroundImage !== "none").length === 1));
    demand("Gallery Wall retires URLs only after the replacement is fully opaque",
      await page.evaluate(() => [...document.querySelectorAll(".ss-gcell")].every(cell =>
        [...cell.children].every(n => getComputedStyle(n).backgroundImage === "none"
          ? n.classList.contains("retired") && +getComputedStyle(n).opacity === 0
          : +getComputedStyle(n).opacity === 1))));
    const ownedGallerySlots = await page.locator(".gallery-photo").count();
    await page.waitForTimeout(6500);
    demand("Gallery Wall replacements retain exactly two fixed image slots per cell",
      await page.locator(".gallery-photo").count() === ownedGallerySlots &&
      await page.evaluate(() => [...document.querySelectorAll(".ss-gcell")]
        .every(cell => cell.children.length === 2 &&
          [...cell.children].some(n => +getComputedStyle(n).opacity === 1))));
    await page.evaluate(() => window.galleryCheck());
    demand("Gallery Wall terminal cleanup releases every photo URL and animation hint",
      await page.evaluate(() => [...document.querySelectorAll(".gallery-photo")].every(n => {
        const style = getComputedStyle(n);
        return style.backgroundImage === "none" && style.animationName === "none" &&
          style.willChange === "auto" && +style.opacity === 0;
      })));
    await page.goto(`${base}/gallery-unit`);
    const cancelledGallery = await page.evaluate(async () => {
      const original = window.preloadDecoded;
      const root = document.createElement("div"); document.querySelector("#stage").append(root);
      let cleanup, atCleanup;
      const images = () => [...root.querySelectorAll(".gallery-photo")]
        .filter(n => getComputedStyle(n).backgroundImage !== "none").length;
      window.preloadDecoded = (url, signal) => {
        const prepared = original(url, signal);
        prepared.then(() => queueMicrotask(() => queueMicrotask(() => {
          cleanup(); atCleanup = images();
        })), () => {});
        return prepared;
      };
      cleanup = SS.MODES.galleryWall.run({
        root, cursor: { i: 0 }, W: innerWidth, H: innerHeight,
        photoMs: 30000, microMs: 30000, url: photo => photo.url,
      }, [window.PHOTOS[0]]);
      const ready = await cleanup.ready; window.preloadDecoded = original;
      return { ready, atCleanup, afterReady: images() };
    });
    demand("Gallery Wall cancellation cannot republish photos after decode admission",
      cancelledGallery.ready === false && cancelledGallery.atCleanup === 0 && cancelledGallery.afterReady === 0);
    await page.goto(`${base}/gallery-unit`);
    const resumedCadence = await page.evaluate(async () => {
      const original = window.preloadDecoded;
      const root = document.createElement("div"); document.querySelector("#stage").append(root);
      let admissions = 0, hold = false, release, held;
      const reached = new Promise(resolve => { held = resolve; });
      window.preloadDecoded = (url, signal) => {
        admissions++;
        const prepared = original(url, signal);
        if (!hold) return prepared;
        hold = false;
        return prepared.then(() => new Promise(resolve => {
          release = () => resolve(true); held();
        }));
      };
      const cleanup = SS.MODES.galleryWall.run({
        root, cursor: { i: 0 }, W: innerWidth, H: innerHeight,
        photoMs: 30000, microMs: 500, url: photo => photo.url,
      }, window.PHOTOS);
      try {
        await cleanup.ready;
        const initial = admissions; hold = true;
        await reached; cleanup.pause(); release();
        await new Promise(resolve => setTimeout(resolve, 50));
        cleanup.resume();
        await new Promise(resolve => setTimeout(resolve, 550));
        return admissions - initial;
      } finally { cleanup(); window.preloadDecoded = original; }
    });
    demand("Gallery Wall resume during a real asynchronous decode preserves one swap per interval",
      resumedCadence === 2);
    await page.goto(`${base}/render?nativeClock=1&slow=1&unknown=1&hostPaused=1`);
    await page.waitForTimeout(200);
    demand("Paused startup admits zero HTTP images", admittedSlow === 0 && held.size === 0);
    await page.evaluate(() => window.immortalSlideshow.resume());
    await until(() => held.size === 2);
    demand("Orientation preparation has two concurrent HTTP requests", admittedSlow === 2);
    await page.evaluate(() => window.immortalSlideshow.pause());
    await until(() => held.size === 0);
    demand("Pausing closes both real HTTP image requests", closedSlow === 2);
    demand("Paused preparation does not construct a scene", await page.locator("#stage").evaluate(n => n.children.length === 0));
    await page.evaluate(() => window.immortalSlideshow.resume());
    await until(() => admittedSlow >= 4);
    await page.waitForFunction(() => window.imageAdmissions.slice(2, 4).every(image => image.url === "") &&
      document.querySelector("#stage").children.length > 0);
    demand("Startup timeout releases both measurement image owners",
      await page.evaluate(() => window.imageAdmissions.slice(2, 4).every(image => image.url === "")));
    await until(() => held.size > 0);
    const ownedUrls = await page.evaluate(() => window.imageAdmissions.slice(4).map(image => image.url));
    demand("Any retained HTTP request belongs to normal display preparation",
      held.size <= 2 && [...held.values()].every(name => ownedUrls.some(url => url.endsWith(name + ".png"))));
    for (const [response, name] of held) {
      response.writeHead(200, { "Content-Type": "image/png" }); response.end(fixture(name));
    }
    await page.locator("#loading.hide").waitFor({ state: "attached" });
    const metadata = await page.evaluate(() => window.PHOTOS.filter(p => p.measured));
    demand("Normal display decode recovers delayed portrait ratio", metadata.length > 0 &&
      metadata.every(p => p.w === 480 && p.h === 720));
    demand("No production-script errors", errors.length === 0);
    await page.close();
    fs.writeFileSync(path.join(evidence, "checks.json"), JSON.stringify({
      complete: true, passed: checks.length, checks, errors,
      scope: "Actual production renderer in synthetic desktop Chrome; no Portal GPU/FPS claim",
      physicalPortalPerformanceMeasured: false,
    }, null, 2) + "\n");
    console.log(`${checks.length} actual-source browser checks passed.`);
  } finally {
    await browser?.close();
    server.closeAllConnections();
    await new Promise(resolve => server.close(resolve));
  }
})().catch(error => { console.error(error); process.exitCode = 1; });
