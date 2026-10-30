// Copyright (c) 2026 Starbright Lab.
// Licensed under the MIT license in the repository root.
// Actual production scripts/CSS, original fixtures, isolated local HTTP/Chrome.
const assert = require("node:assert/strict");
const fs = require("node:fs");
const path = require("node:path");
const http = require("node:http");
const { execFileSync } = require("node:child_process");
const { chromium } = require("playwright");
const { PNG } = require("pngjs");
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
// Same original-art deck and focal-point variant as the read-only preview sidecar.
const panoramaPhotos = [
  { url: "/fixtures/panorama-wide.svg", w: 2400, h: 800 },
  { url: "/fixtures/panorama-extreme.svg", w: 2400, h: 300 },
  { url: "/fixtures/panorama-wide.svg?anchor=beacon", w: 2400, h: 800, fx: .8, fy: .5 },
  galleryPhotos[1], galleryPhotos[7],
].map((photo, id) => ({ ...photo, id, seed: "panorama-" + id }));
const server = http.createServer((req, res) => {
  const u = new URL(req.url, "http://local");
  if (/^\/slow\/(dunes|tidal)\.png$/.test(u.pathname)) {
    admittedSlow++; held.set(res, path.basename(u.pathname, ".png"));
    res.on("close", () => { held.delete(res); closedSlow++; });
    return;
  }
  let content, type;
  if (["/diptych-unit", "/motion-unit", "/gallery-unit", "/sidebar-unit", "/memories-unit", "/panorama-unit", "/mosaic-unit"].includes(u.pathname)) {
    const unitPhotos = u.pathname === "/panorama-unit" ? panoramaPhotos :
      ["/gallery-unit", "/sidebar-unit", "/memories-unit", "/mosaic-unit"].includes(u.pathname) ? galleryPhotos :
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
  } else if (["/render", "/renderMosaic"].includes(u.pathname)) {
    const slow = u.searchParams.has("slow"), unknown = u.searchParams.has("unknown");
    const mode = u.pathname === "/renderMosaic" ? "mosaic" : u.searchParams.get("mode") || "diptych";
    const photos = mode === "panorama" ? panoramaPhotos :
      ["galleryWall", "heroSidebar", "memories", "mosaic"].includes(mode) ? galleryPhotos : ["dunes", "tidal"].map(name => ({
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
  } else if (["/fixtures/panorama-wide.svg", "/fixtures/panorama-extreme.svg"].includes(u.pathname)) {
    content = fs.readFileSync(path.join(here, "assets", path.basename(u.pathname)));
    type = "image/svg+xml";
  } else if (/^\/assets\/(?:styles\.css|photos\.js|lib-engine\.js|modes\.js|mode-impls\.js|scheduler\.js)$/.test(u.pathname)) {
    content = readAsset(path.basename(u.pathname));
    type = u.pathname.endsWith(".css") ? "text/css" : "application/javascript";
  } else { res.writeHead(404); res.end(); return; }
  res.writeHead(200, { "Content-Type": type, "Cache-Control": "no-store" }); res.end(content);
});
const demand = (name, condition, detail) => {
  assert.ok(condition, detail ? name + ": " + JSON.stringify(detail) : name); checks.push(name);
};
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
    for (const mode of ["diptych", "framed", "kenBurns", "push", "galleryWall", "heroSidebar", "memories", "panorama", "mosaic"]) {
      await page.goto(`${base}/${mode === "mosaic" ? "renderMosaic" : "render"}?nativeClock=1&mode=${mode}`);
      await page.locator("#loading.hide").waitFor({ state: "attached" });
      await page.waitForFunction(() => getComputedStyle(document.querySelector(".ss-beat.on")).opacity === "1" &&
        [...document.querySelectorAll(".ss-frame.on,.ss-art.on,.ss-layer.on,.ss-memory-scene.on")]
          .every(n => getComputedStyle(n).opacity === "1"));
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
      // Preserve all prior screenshots byte-for-byte during this bounded addition.
      if (mode === "mosaic")
        await page.screenshot({ path: path.join(evidence, mode + ".png") });
      demand(mode + " actual renderer becomes ready", await page.locator("#loading").evaluate(n => n.classList.contains("hide")));
      if (mode === "mosaic")
        demand("Mosaic outer publication waits for nine complete opaque tiles from all twelve synthetic fixtures",
          await page.evaluate(() => PHOTOS.length === 12 &&
            document.querySelectorAll(".mosaic-cell").length === 9 &&
            [...document.querySelectorAll(".mosaic-cell")].every(cell =>
              +getComputedStyle(cell.lastElementChild).opacity === 1 &&
              getComputedStyle(cell.lastElementChild.firstElementChild).backgroundImage !== "none")));
    }
    await page.goto(`${base}/mosaic-unit`);
    const mosaicInitial = await page.evaluate(async () => {
      Element.prototype.getAnimations = undefined; Element.prototype.animate = undefined;
      const original = window.preloadDecoded;
      let active = 0, peak = 0, count = 0, reached, releases = [];
      const blocked = new Promise(resolve => { reached = resolve; });
      window.preloadDecoded = (url, signal) => {
        active++; peak = Math.max(peak, active); const firstBatch = ++count <= 2;
        return original(url, signal).then(value => firstBatch ? new Promise(resolve => {
          releases.push(() => resolve(value)); if (releases.length === 2) reached();
        }) : value).finally(() => { active--; });
      };
      const root = document.createElement("div"); root.style.cssText = "position:fixed;inset:0";
      document.querySelector("#stage").append(root);
      window.mosaicRoot = root; window.mosaicRequests = []; window.mosaicReady = false;
      window.mosaicCheck = SS.MODES.mosaic.run({
        root, cursor: { i: 0 }, W: innerWidth, H: innerHeight,
        photoMs: 2500, microMs: 2500, ambientMs: 350, motionMs: 2500,
        url: (photo, edge) => { mosaicRequests.push({ url: photo.url, edge }); return photo.url; },
      }, PHOTOS);
      mosaicCheck.ready.then(ok => { mosaicReady = ok; });
      window.releaseMosaicInitial = () => releases.forEach(release => release());
      window.mosaicPeak = () => peak;
      window.restoreMosaicLoader = () => { window.preloadDecoded = original; };
      await blocked;
      return { admitted: mosaicRequests.length, active, peak, ready: mosaicReady,
        published: [...root.querySelectorAll(".mosaic-photo")]
          .filter(image => image.style.backgroundImage !== "none").length };
    });
    demand("Mosaic initial loading admits only two decoded owners and withholds full readiness",
      mosaicInitial.admitted === 2 && mosaicInitial.active === 2 && mosaicInitial.peak === 2 &&
      !mosaicInitial.ready && mosaicInitial.published === 0, mosaicInitial);
    await page.evaluate(async () => {
      releaseMosaicInitial(); await mosaicCheck.ready;
      window.mosaicCells = [...mosaicRoot.querySelectorAll(".mosaic-cell")];
      window.mosaicImages = [...mosaicRoot.querySelectorAll(".mosaic-photo")];
      window.mosaicLayout = () => mosaicCells.map(cell =>
        [cell.offsetLeft, cell.offsetTop, cell.offsetWidth, cell.offsetHeight]);
      window.mosaicOriginalLayout = mosaicLayout();
      window.mosaicCurrentUrls = () => mosaicCells.map(cell => cell.lastElementChild.firstElementChild.style.backgroundImage);
      window.mosaicInitialUrls = mosaicCurrentUrls();
      window.mosaicState = () => mosaicCells.map(cell => ({
        transform: getComputedStyle(cell).transform,
        panes: [...cell.children].map(pane => ({
          opacity: getComputedStyle(pane).opacity,
          url: pane.firstElementChild.style.backgroundImage,
          transform: getComputedStyle(pane.firstElementChild).transform,
        })),
      }));
      window.mosaicVisited = [mosaicCells.findIndex(cell => cell.classList.contains("spot"))];
      window.mosaicSpotObserver = new MutationObserver(() => {
        const selected = mosaicCells.findIndex(cell => cell.classList.contains("spot"));
        if (selected >= 0 && mosaicVisited.at(-1) !== selected) mosaicVisited.push(selected);
      });
      mosaicSpotObserver.observe(mosaicRoot, { attributes: true, attributeFilter: ["class"], subtree: true });
      window.mosaicMonitor = { steadyHints: 0, fadeHints: 0, steadyMotion: 0, fadeMotion: 0, wrongSpot: 0 };
      window.mosaicMonitoring = true;
      const sample = () => {
        if (!mosaicMonitoring) return;
        const fade = mosaicCells.some(cell => [...cell.children].some(pane => {
          const opacity = +getComputedStyle(pane).opacity; return opacity > 0 && opacity < 1;
        }));
        let hints = 0, motion = 0;
        mosaicImages.forEach(image => {
          const style = getComputedStyle(image), promoted = style.willChange === "transform";
          const running = style.animationName !== "none" && style.animationPlayState === "running";
          if (promoted) hints++; if (running) motion++;
          if ((promoted || running) && !image.parentNode.parentNode.classList.contains("spot"))
            mosaicMonitor.wrongSpot++;
        });
        const prefix = fade ? "fade" : "steady";
        mosaicMonitor[prefix + "Hints"] = Math.max(mosaicMonitor[prefix + "Hints"], hints);
        mosaicMonitor[prefix + "Motion"] = Math.max(mosaicMonitor[prefix + "Motion"], motion);
        window.mosaicMonitorFrame = requestAnimationFrame(sample);
      };
      sample();
    });
    demand("Mosaic full readiness publishes nine unique opaque photos in eighteen fixed image panes",
      await page.evaluate(() => mosaicReady && mosaicCells.length === 9 && mosaicImages.length === 18 &&
        new Set(mosaicCurrentUrls()).size === 9 && mosaicRequests.length === 9 &&
        mosaicPeak() === 2 && mosaicRequests.every(request => request.edge === 1200) &&
        mosaicCells.every(cell => cell.children.length === 2 &&
          +getComputedStyle(cell.lastElementChild).opacity === 1 &&
          cell.lastElementChild.firstElementChild.style.backgroundImage !== "none")));
    demand("Mosaic BSP cells fit the real viewport without blur or oversized photo strips",
      await page.evaluate(() => mosaicRoot.querySelectorAll(".ss-fill").length === 0 &&
        mosaicCells.every(cell => {
          const W = cell.offsetWidth, H = cell.offsetHeight, image = cell.lastElementChild.firstElementChild;
          const style = getComputedStyle(image), width = parseFloat(style.width), height = parseFloat(style.height);
          return cell.offsetLeft >= 0 && cell.offsetTop >= 0 && cell.offsetLeft + W <= innerWidth &&
            cell.offsetTop + H <= innerHeight && width > 0 && height > 0 && width <= W * 2 && height <= H * 2 &&
            style.backgroundSize === "contain" && style.backgroundRepeat === "no-repeat" && style.filter === "none" &&
            (image.classList.contains("panv") ? width <= W :
              image.classList.contains("panh") ? height <= H :
                Math.abs(width - W * .92) < .1 && Math.abs(height - H * .92) < .1);
        })));
    await page.waitForFunction(() => [...mosaicRoot.querySelectorAll(".ss-mosaic-pane")]
      .some(pane => +getComputedStyle(pane).opacity > .1 && +getComputedStyle(pane).opacity < .9));
    const mosaicReplacement = await page.evaluate(() => {
      const changed = mosaicCells.filter((cell, i) => mosaicCurrentUrls()[i] !== mosaicInitialUrls[i]);
      window.mosaicChangedCell = changed[0];
      window.mosaicOutgoingPane = mosaicChangedCell.firstElementChild;
      window.mosaicOutgoingImage = mosaicOutgoingPane.firstElementChild;
      window.mosaicOutgoingUrl = mosaicOutgoingImage.style.backgroundImage;
      const incoming = mosaicChangedCell.lastElementChild, style = getComputedStyle(incoming);
      return { changed: changed.length, admitted: mosaicRequests.length, unique: new Set(mosaicCurrentUrls()).size,
        incomingNew: !mosaicInitialUrls.includes(incoming.firstElementChild.style.backgroundImage),
        outgoing: +getComputedStyle(mosaicOutgoingPane).opacity,
        incoming: +style.opacity, duration: style.transitionDuration, property: style.transitionProperty };
    });
    demand("Mosaic unseen replacement changes one tile with incoming-only 1100ms fade over opaque outgoing URL",
      mosaicReplacement.changed === 1 && mosaicReplacement.admitted === 10 && mosaicReplacement.unique === 9 &&
      mosaicReplacement.incomingNew && mosaicReplacement.outgoing === 1 &&
      mosaicReplacement.incoming > 0 && mosaicReplacement.incoming < 1 &&
      mosaicReplacement.duration === "1.1s" && mosaicReplacement.property === "opacity", mosaicReplacement);
    const pausedMosaic = await page.evaluate(async () => {
      mosaicCheck.pause();
      await new Promise(resolve => requestAnimationFrame(() => requestAnimationFrame(resolve)));
      return { state: mosaicState(), admissions: mosaicRequests.length };
    });
    await page.waitForTimeout(2700);
    demand("Mosaic old-WebView pause freezes parent transforms, live opacity and photo CSS beyond cadence and retirement",
      await page.evaluate(before => !Element.prototype.getAnimations && !Element.prototype.animate &&
        JSON.stringify(mosaicState()) === JSON.stringify(before.state) &&
        mosaicRequests.length === before.admissions && mosaicOutgoingImage.style.backgroundImage === mosaicOutgoingUrl &&
        +getComputedStyle(mosaicOutgoingPane).opacity === 1, pausedMosaic));
    demand("Mosaic pause removes every photo compositor hint and pauses every owned animation",
      await page.evaluate(() => mosaicImages.every(image =>
        getComputedStyle(image).willChange === "auto" && getComputedStyle(image).animationPlayState === "paused")));
    const mosaicResume = await page.evaluate(() => {
      mosaicCheck.resume();
      return { clearParents: mosaicCells.every(cell => cell.style.transform === "" && cell.style.transition === ""),
        duration: parseFloat(getComputedStyle(mosaicChangedCell.lastElementChild).transitionDuration) * 1000 };
    });
    demand("Mosaic resume clears held parent transforms and consumes only the remaining dissolve duration",
      mosaicResume.clearParents && mosaicResume.duration > 0 && mosaicResume.duration < 1100, mosaicResume);
    await page.waitForFunction(() => mosaicOutgoingImage.style.backgroundImage === "none" &&
      +getComputedStyle(mosaicChangedCell.lastElementChild).opacity === 1);
    demand("Mosaic retirement clears outgoing URL, animation and hint only after replacement opacity one",
      await page.evaluate(() => mosaicOutgoingImage.style.backgroundImage === "none" &&
        mosaicOutgoingImage.style.animationName === "none" && mosaicOutgoingImage.style.willChange === "auto" &&
        getComputedStyle(mosaicOutgoingPane).visibility === "hidden" &&
        mosaicImages.filter(image => image.style.backgroundImage !== "none").length === 9));
    await page.waitForFunction(() => new Set(mosaicVisited).size === 9, null, { timeout: 6000 });
    demand("Mosaic spotlight visits every tile in round-robin order while cells retain their original BSP rectangles",
      await page.evaluate(() => new Set(mosaicVisited).size === 9 &&
        mosaicVisited.every((index, i) => i === 0 || index === (mosaicVisited[i - 1] + 1) % 9) &&
        JSON.stringify(mosaicLayout()) === JSON.stringify(mosaicOriginalLayout) &&
        mosaicRoot.querySelectorAll(".mosaic-cell").length === 9 &&
        mosaicRoot.querySelectorAll(".ss-mosaic-pane").length === 18 &&
        mosaicRoot.querySelectorAll(".mosaic-photo").length === 18));
    demand("Mosaic actual frame samples promote and animate only the spotlight with at most one steady or two fading photos",
      await page.evaluate(() => mosaicMonitor.wrongSpot === 0 &&
        mosaicMonitor.steadyHints <= 1 && mosaicMonitor.steadyMotion <= 1 &&
        mosaicMonitor.fadeHints <= 2 && mosaicMonitor.fadeMotion <= 2 &&
        mosaicMonitor.steadyMotion > 0), await page.evaluate(() => mosaicMonitor));
    await page.evaluate(() => {
      mosaicMonitoring = false; cancelAnimationFrame(mosaicMonitorFrame); mosaicSpotObserver.disconnect();
      const stale = [...mosaicRoot.querySelectorAll(".ss-mosaic-pane")].map(pane => [pane, pane.ontransitionend]);
      mosaicCheck.pause(); mosaicCheck(); mosaicCheck(); mosaicCheck.resume();
      stale.forEach(([pane, end]) => { if (end) end({ target: pane, propertyName: "opacity" }); });
      window.mosaicClosedAdmissions = mosaicRequests.length; restoreMosaicLoader();
    });
    demand("Mosaic terminal cleanup removes the wall and releases all eighteen retained image references",
      await page.evaluate(() => mosaicRoot.children.length === 0 && mosaicImages.every(image =>
        !image.isConnected && image.style.backgroundImage === "none" &&
        image.style.animationName === "none" && image.style.willChange === "auto")));
    await page.waitForTimeout(2600);
    demand("Mosaic cleanup gates reject stale fade, resume and cadence publication or derivative work",
      await page.evaluate(() => mosaicRoot.children.length === 0 && mosaicRequests.length === mosaicClosedAdmissions));
    await page.goto(`${base}/mosaic-unit`);
    const atomicMosaic = await page.evaluate(async () => {
      const original = window.preloadDecoded;
      const root = document.createElement("div"); root.style.cssText = "position:fixed;inset:0";
      document.querySelector("#stage").append(root);
      let hold = false, pair = 0, release, reached;
      const blocked = new Promise(resolve => { reached = resolve; });
      window.preloadDecoded = (url, signal) => {
        const prepared = original(url, signal);
        if (!hold || ++pair !== 2) return prepared;
        hold = false;
        return prepared.then(value => new Promise(resolve => { release = () => resolve(value); reached(); }));
      };
      const cleanup = SS.MODES.mosaic.run({
        root, cursor: { i: 0 }, W: innerWidth, H: innerHeight,
        photoMs: 2500, microMs: 2500, ambientMs: 900, motionMs: 2500, url: photo => photo.url,
      }, PHOTOS.slice(0, 9));
      try {
        await cleanup.ready;
        const cells = [...root.querySelectorAll(".mosaic-cell")];
        const urls = () => cells.map(cell => cell.lastElementChild.firstElementChild.style.backgroundImage);
        const before = urls(); hold = true; await blocked;
        const held = JSON.stringify(urls()) === JSON.stringify(before);
        release(); await new Promise(resolve => setTimeout(resolve, 80));
        const after = urls(), changed = cells.map((cell, i) => after[i] !== before[i] ? i : -1).filter(i => i >= 0);
        const appetite = cell => cell.offsetWidth / cell.offsetHeight < .85 ? "portrait" :
          cell.offsetWidth / cell.offsetHeight > 1.8 ? "pano" : "landscape";
        const [a, b] = changed;
        return { held, changed: changed.length, unique: new Set(after).size,
          exchange: changed.length === 2 && after[a] === before[b] && after[b] === before[a],
          compatible: changed.length === 2 && appetite(cells[a]) === appetite(cells[b]),
          slots: root.querySelectorAll(".mosaic-photo").length,
          outgoingOpaque: changed.every(i => +getComputedStyle(cells[i].firstElementChild).opacity === 1) };
      } finally { cleanup(); window.preloadDecoded = original; }
    });
    demand("Mosaic full-library exchange waits for both real decodes before any tile publication", atomicMosaic.held, atomicMosaic);
    demand("Mosaic full-library exchange atomically swaps two compatible-appetite cells without duplicates or extra slots",
      atomicMosaic.changed === 2 && atomicMosaic.unique === 9 && atomicMosaic.exchange &&
      atomicMosaic.compatible && atomicMosaic.slots === 18 && atomicMosaic.outgoingOpaque, atomicMosaic);
    for (const count of [1, 2, 5]) {
      await page.goto(`${base}/mosaic-unit`);
      const smallMosaic = await page.evaluate(async count => {
        const root = document.createElement("div"); root.style.cssText = "position:fixed;inset:0";
        document.querySelector("#stage").append(root);
        const cleanup = SS.MODES.mosaic.run({
          root, cursor: { i: 0 }, W: innerWidth, H: innerHeight,
          photoMs: 2500, microMs: 30000, ambientMs: 30000, motionMs: 2500, url: photo => photo.url,
        }, PHOTOS.slice(0, count));
        await cleanup.ready;
        const cells = [...root.querySelectorAll(".mosaic-cell")];
        const result = { cells: cells.length, slots: root.querySelectorAll(".mosaic-photo").length,
          unique: new Set(cells.map(cell => cell.lastElementChild.firstElementChild.style.backgroundImage)).size,
          opaque: cells.every(cell => +getComputedStyle(cell.lastElementChild).opacity === 1) };
        cleanup(); return result;
      }, count);
      demand(`Mosaic ${count}-photo library retains ${count} unique opaque cells and ${count * 2} fixed panes`,
        smallMosaic.cells === count && smallMosaic.slots === count * 2 &&
        smallMosaic.unique === count && smallMosaic.opaque, smallMosaic);
    }
    for (const ratio of [.025, 1.5, 10]) {
      await page.goto(`${base}/mosaic-unit`);
      const mosaicGeometry = await page.evaluate(async ratio => {
        const root = document.createElement("div"); root.style.cssText = "position:fixed;inset:0";
        document.querySelector("#stage").append(root);
        const w = ratio < 1 ? 100 : ratio * 100, h = ratio < 1 ? 100 / ratio : 100;
        const url = "data:image/svg+xml," + encodeURIComponent(
          `<svg xmlns="http://www.w3.org/2000/svg" width="${w}" height="${h}">` +
          `<rect width="${w}" height="${h}" fill="#647f83"/></svg>`);
        const photo = { seed: "mosaic-geometry", url, w, h, ...(ratio === 1.5 ? {} : { fx: .8, fy: .8 }) };
        const cleanup = SS.MODES.mosaic.run({
          root, cursor: { i: 0 }, W: innerWidth, H: innerHeight,
          photoMs: 2500, microMs: 30000, ambientMs: 30000, motionMs: 2500, url: photo => photo.url,
        }, [photo]);
        await cleanup.ready;
        const image = root.querySelector(".on .mosaic-photo"), style = getComputedStyle(image);
        if (ratio === 1.5) { const animation = image.getAnimations()[0]; animation.pause(); animation.currentTime = 12000; }
        const actual = new Image(); actual.src = url; await actual.decode();
        const width = parseFloat(style.width), height = parseFloat(style.height), box = image.getBoundingClientRect();
        const matrix = style.transform === "none" ? new DOMMatrixReadOnly() : new DOMMatrixReadOnly(style.transform);
        const result = { decodedAspect: actual.naturalWidth / actual.naturalHeight, width, height,
          panv: image.classList.contains("panv"), panh: image.classList.contains("panh"),
          left: box.left, top: box.top, right: box.right, bottom: box.bottom,
          focalX: parseFloat(style.left) + width * .8 + matrix.m41,
          focalY: parseFloat(style.top) + height * .8 + matrix.m42,
          motion: style.animationName, hint: style.willChange, size: style.backgroundSize,
          repeat: style.backgroundRepeat, scale: matrix.a };
        cleanup(); return result;
      }, ratio);
      demand(`Mosaic decoded ${ratio}:1 photo preserves bounded cross-axis fit and ${ratio === 1.5 ? "a contained maximum 4 percent push" : "a static visible cached focal anchor"}`,
        Math.abs(mosaicGeometry.decodedAspect - ratio) < .0001 &&
        mosaicGeometry.width <= 4320 && mosaicGeometry.height <= 2880 &&
        mosaicGeometry.size === "contain" && mosaicGeometry.repeat === "no-repeat" &&
        (ratio === 1.5
          ? Math.abs(mosaicGeometry.width - 2160 * .92) < .1 &&
            Math.abs(mosaicGeometry.height - 1440 * .92) < .1 && Math.abs(mosaicGeometry.scale - 1.04) < .0001 &&
            mosaicGeometry.left >= 0 && mosaicGeometry.top >= 0 &&
            mosaicGeometry.right <= 2160 && mosaicGeometry.bottom <= 1440
          : mosaicGeometry.motion === "none" && mosaicGeometry.hint === "auto" &&
            (ratio < 1 ? mosaicGeometry.panv && mosaicGeometry.width <= 2160 &&
              mosaicGeometry.focalY >= 0 && mosaicGeometry.focalY <= 1440 :
              mosaicGeometry.panh && mosaicGeometry.height <= 1440 &&
              mosaicGeometry.focalX >= 0 && mosaicGeometry.focalX <= 2160)), mosaicGeometry);
    }
    await page.goto(`${base}/mosaic-unit`);
    const cancelledMosaic = await page.evaluate(async () => {
      const original = window.preloadDecoded;
      const root = document.createElement("div"); document.querySelector("#stage").append(root);
      let cleanup, atCleanup, atRequests, requests = 0;
      window.preloadDecoded = (url, signal) => {
        const prepared = original(url, signal);
        prepared.then(() => queueMicrotask(() => queueMicrotask(() => {
          cleanup(); atCleanup = root.children.length; atRequests = requests;
        })), () => {});
        return prepared;
      };
      cleanup = SS.MODES.mosaic.run({
        root, cursor: { i: 0 }, W: innerWidth, H: innerHeight,
        photoMs: 2500, microMs: 2500, ambientMs: 350, motionMs: 2500,
        url: photo => { requests++; return photo.url; },
      }, PHOTOS);
      const ready = await cleanup.ready; window.preloadDecoded = original;
      return { ready, atCleanup, afterReady: root.children.length, atRequests, requests };
    });
    demand("Mosaic decode-admission cleanup rejects partial wall publication and later derivative admissions",
      cancelledMosaic.ready === false && cancelledMosaic.atCleanup === 0 &&
      cancelledMosaic.afterReady === 0 && cancelledMosaic.atRequests === cancelledMosaic.requests, cancelledMosaic);
    console.log(`${checks.filter(name => /mosaic/i.test(name)).length} Mosaic checks passed; replaying the retained browser baseline.`);
    await page.goto(`${base}/panorama-unit`);
    await page.evaluate(async () => {
      Element.prototype.getAnimations = undefined;
      Element.prototype.animate = undefined;
      const root = document.createElement("div");
      root.style.cssText = "position:fixed;inset:0";
      document.querySelector("#stage").append(root);
      window.panoramaRoot = root; window.panoramaRequests = [];
      window.panoramaCheck = SS.MODES.panorama.run({
        root, cursor: { i: 0 }, W: innerWidth, H: innerHeight,
        photoMs: 2500, motionMs: 4800,
        url: (photo, edge) => { panoramaRequests.push({ url: photo.url, edge }); return photo.url; },
      }, PHOTOS.slice(0, 2));
      await panoramaCheck.ready;
      window.panoramaScenes = [...root.querySelectorAll(".panorama-drift")];
      window.panoramaStrips = [...root.querySelectorAll(".ss-pan")];
      window.panoramaState = () => panoramaScenes.map(scene => ({
        opacity: getComputedStyle(scene).opacity, visibility: getComputedStyle(scene).visibility,
        url: getComputedStyle(scene.querySelector(".ss-pan")).backgroundImage,
        transform: getComputedStyle(scene.querySelector(".ss-pan")).transform,
      }));
    });
    demand("Panorama readiness retains exactly two fixed scene roots and two reusable strips",
      await page.evaluate(() => panoramaScenes.length === 2 && panoramaStrips.length === 2 &&
        panoramaScenes.every(scene => {
          const box = scene.getBoundingClientRect(), style = getComputedStyle(scene);
          return scene.parentNode === panoramaRoot && scene.children.length === 1 &&
            style.position === "absolute" && style.backgroundColor === "rgb(28, 27, 25)" &&
            box.x === 0 && box.y === 0 && box.width === 2160 && box.height === 1440;
        })));
    demand("Panorama starts with one opaque contained non-repeating photo and one 2400-edge admission",
      await page.evaluate(() => {
        const scene = panoramaRoot.lastElementChild, pan = scene.querySelector(".ss-pan");
        const style = getComputedStyle(pan);
        return +getComputedStyle(scene).opacity === 1 &&
          panoramaStrips.filter(pan => getComputedStyle(pan).backgroundImage !== "none").length === 1 &&
          panoramaRoot.querySelectorAll(".ss-fill").length === 0 &&
          style.backgroundSize === "contain" && style.backgroundRepeat === "no-repeat" &&
          style.filter === "none" && panoramaRequests.length === 1 && panoramaRequests[0].edge === 2400;
      }));
    demand("Panorama unhinted wide photo moves only its transform with linear forward dolly",
      await page.evaluate(() => {
        const style = getComputedStyle(panoramaRoot.lastElementChild.querySelector(".ss-pan"));
        return style.animationName === "dolly" && style.animationTimingFunction === "linear" &&
          style.animationFillMode === "forwards" && style.willChange === "transform";
      }));
    demand("Panorama clamps the longer scheduler motion duration to the 2500ms photo dwell",
      await page.evaluate(() => getComputedStyle(panoramaRoot.lastElementChild.querySelector(".ss-pan"))
        .animationDuration === "2.5s"));
    for (let swap = 1; swap <= 3; swap++) {
      await page.evaluate(() => {
        window.panoramaOutgoing = panoramaRoot.lastElementChild;
        window.panoramaOldStrip = panoramaOutgoing.querySelector(".ss-pan");
        window.panoramaOldUrl = panoramaOldStrip.style.backgroundImage;
      });
      await page.waitForFunction(() => panoramaRoot.lastElementChild !== panoramaOutgoing &&
        +getComputedStyle(panoramaRoot.lastElementChild).opacity > .1 &&
        +getComputedStyle(panoramaRoot.lastElementChild).opacity < .9);
      demand(`Panorama swap ${swap} uses incoming-only 1800ms opacity above an opaque outgoing URL`,
        await page.evaluate(() => {
          const incoming = panoramaRoot.lastElementChild, style = getComputedStyle(incoming);
          return style.transitionProperty === "opacity" && style.transitionDuration === "1.8s" &&
            +getComputedStyle(panoramaOutgoing).opacity === 1 &&
            panoramaOldStrip.style.backgroundImage === panoramaOldUrl &&
            panoramaRoot.querySelectorAll(".panorama-drift").length === 2 &&
            panoramaRoot.querySelectorAll(".ss-pan").length === 2 &&
            panoramaScenes.every(scene => scene.parentNode === panoramaRoot) &&
            panoramaStrips.every(pan => pan.isConnected);
        }));
      demand(`Panorama swap ${swap} outgoing strip reaches its right edge before URL retirement`,
        await page.evaluate(() => {
          const style = getComputedStyle(panoramaOldStrip), width = parseFloat(style.width);
          const shift = new DOMMatrixReadOnly(style.transform).m41;
          return Math.abs(shift - (innerWidth - width)) < .1 &&
            panoramaOldStrip.style.backgroundImage === panoramaOldUrl &&
            +getComputedStyle(panoramaOutgoing).opacity === 1;
        }));
      if (swap === 1) {
        const pausedPanorama = await page.evaluate(async () => {
          panoramaCheck.pause();
          await new Promise(resolve => requestAnimationFrame(() => requestAnimationFrame(resolve)));
          return { scenes: panoramaState(), admissions: panoramaRequests.length };
        });
        await page.waitForTimeout(2000);
        demand("Panorama old-WebView pause freezes both strip transforms and opacity beyond retirement",
          await page.evaluate(before => !Element.prototype.getAnimations && !Element.prototype.animate &&
            JSON.stringify(panoramaState()) === JSON.stringify(before.scenes) &&
            panoramaRequests.length === before.admissions &&
            +getComputedStyle(panoramaOutgoing).opacity === 1, pausedPanorama));
        const resumedDuration = await page.evaluate(() => {
          panoramaCheck.resume();
          return parseFloat(getComputedStyle(panoramaRoot.lastElementChild).transitionDuration) * 1000;
        });
        demand("Panorama resume consumes the remaining dissolve duration without restarting 1800ms",
          resumedDuration > 0 && resumedDuration < 1800, { resumedDuration });
      }
      if (swap === 3) break;
      await page.waitForFunction(() => panoramaOldStrip.style.backgroundImage === "none" &&
        +getComputedStyle(panoramaRoot.lastElementChild).opacity === 1);
      demand(`Panorama swap ${swap} retires the outgoing URL, motion and compositor hint after incoming opacity one`,
        await page.evaluate(() => getComputedStyle(panoramaOutgoing).visibility === "hidden" &&
          +getComputedStyle(panoramaOutgoing).opacity === 0 &&
          panoramaOldStrip.style.backgroundImage === "none" &&
          panoramaOldStrip.style.animationName === "none" &&
          panoramaOldStrip.style.willChange === "auto" && panoramaOldStrip.style.transform === "none" &&
          panoramaStrips.filter(pan => pan.style.backgroundImage !== "none").length === 1));
    }
    await page.evaluate(() => {
      const incoming = panoramaRoot.lastElementChild, staleEnd = incoming.ontransitionend;
      panoramaCheck(); panoramaCheck(); panoramaCheck.resume();
      if (staleEnd) staleEnd({ target: incoming, propertyName: "opacity" });
      window.panoramaClosedState = panoramaState();
      window.panoramaClosedAdmissions = panoramaRequests.length;
    });
    demand("Panorama terminal cleanup during dissolve hides both scenes and releases both fixed strips",
      await page.evaluate(() => panoramaScenes.every(scene =>
        getComputedStyle(scene).visibility === "hidden" && +getComputedStyle(scene).opacity === 0 &&
        scene.ontransitionend === null) && panoramaStrips.every(pan =>
        pan.style.backgroundImage === "none" && pan.style.animationName === "none" &&
        pan.style.willChange === "auto" && pan.style.transform === "none")));
    await page.waitForTimeout(2600);
    demand("Panorama stopped owners reject stale retirement, cadence and resume publication",
      await page.evaluate(() => JSON.stringify(panoramaState()) === JSON.stringify(panoramaClosedState) &&
        panoramaRequests.length === panoramaClosedAdmissions &&
        panoramaRoot.querySelectorAll(".ss-pan").length === 2));
    // Each geometry case uses a decoded synthetic SVG with exactly the declared aspect.
    for (const [W, H] of [[2160, 1440], [1920, 1080], [1024, 768], [640, 960]]) {
      await page.setViewportSize({ width: W, height: H });
      await page.goto(`${base}/panorama-unit`);
      for (const ratio of [1.3, 1.5, 2, 3, 8, 10]) {
        const geometry = await page.evaluate(async ({ W, H, ratio }) => {
          const root = document.createElement("div");
          root.style.cssText = "position:fixed;inset:0";
          document.querySelector("#stage").append(root);
          const w = ratio * 100, h = 100;
          const url = "data:image/svg+xml," + encodeURIComponent(
            `<svg xmlns="http://www.w3.org/2000/svg" width="${w}" height="${h}">` +
            `<rect width="${w}" height="${h}" fill="#647f83"/>` +
            `<rect width="10" height="${h}" fill="#a65b42"/>` +
            `<rect x="${w - 10}" width="10" height="${h}" fill="#286e73"/></svg>`);
          const cleanup = SS.MODES.panorama.run({
            root, cursor: { i: 0 }, W, H, photoMs: 30000, motionMs: 1,
            url: photo => photo.url,
          }, [{ id: 0, seed: "geometry", url, w, h }]);
          await cleanup.ready;
          await new Promise(resolve => setTimeout(resolve, 80));
          const image = new Image(); image.src = url; await image.decode();
          const pan = root.querySelector(".on .ss-pan"), style = getComputedStyle(pan);
          const box = pan.getBoundingClientRect(), width = parseFloat(style.width), height = parseFloat(style.height);
          const matrix = style.transform === "none" ? { m41: 0 } : new DOMMatrixReadOnly(style.transform);
          const result = { width, height, top: parseFloat(style.top), left: box.left, right: box.right,
            translateX: matrix.m41, size: style.backgroundSize, repeat: style.backgroundRepeat,
            aspect: image.naturalWidth / image.naturalHeight, motion: style.animationName, hint: style.willChange };
          cleanup(); root.remove(); return result;
        }, { W, H, ratio });
        const width = Math.min(2 * W, Math.max(W, H * ratio)), height = Math.min(H, width / ratio);
        demand(`Panorama ${W}x${H} ${ratio}:1 decoded photo preserves vertical extent within a two-viewport strip`,
          Math.abs(geometry.aspect - ratio) < .0001 &&
          Math.abs(geometry.width - width) < .1 && Math.abs(geometry.height - height) < .1 &&
          Math.abs(geometry.top - (H - height) / 2) < .1 &&
          geometry.width >= W && geometry.width <= 2 * W && geometry.height > 0 && geometry.height <= H &&
          geometry.size === "contain" && geometry.repeat === "no-repeat" &&
          Math.abs(geometry.translateX - (W - width)) < .1 && Math.abs(geometry.right - W) < .1 &&
          (width > W
            ? geometry.motion === "dolly" && geometry.hint === "transform" &&
              Math.abs(geometry.width / geometry.height - ratio) < .001
            : geometry.motion === "none" && geometry.hint === "auto" && geometry.left === 0), geometry);
      }
    }
    await page.setViewportSize({ width: 2160, height: 1440 });
    await page.goto(`${base}/panorama-unit`);
    const extremePanorama = await page.evaluate(async () => {
      const root = document.createElement("div");
      root.style.cssText = "position:fixed;inset:0";
      document.querySelector("#stage").append(root);
      window.extremePanoramaRoot = root;
      window.extremePanoramaCheck = SS.MODES.panorama.run({
        root, cursor: { i: 0 }, W: innerWidth, H: innerHeight,
        photoMs: 30000, motionMs: 1, url: photo => photo.url,
      }, [PHOTOS[1]]);
      await extremePanoramaCheck.ready;
      await new Promise(resolve => setTimeout(resolve, 80));
      const image = new Image(); image.src = PHOTOS[1].url; await image.decode();
      const pan = root.querySelector(".on .ss-pan"), box = pan.getBoundingClientRect();
      return { width: box.width, height: box.height, top: box.top, left: box.left,
        right: box.right, naturalWidth: image.naturalWidth, naturalHeight: image.naturalHeight };
    });
    demand("Panorama original 8:1 SVG keeps its complete vertical photo extent and reaches the right edge",
      extremePanorama.naturalWidth === 2400 && extremePanorama.naturalHeight === 300 &&
      extremePanorama.width === 4320 && extremePanorama.height === 540 &&
      extremePanorama.top === 450 && extremePanorama.left === -2160 && extremePanorama.right === 2160,
      extremePanorama);
    const endPixels = PNG.sync.read(await page.screenshot());
    const pixelAt = (x, y) => [...endPixels.data.slice((y * endPixels.width + x) * 4,
      (y * endPixels.width + x) * 4 + 3)];
    const rightEdge = pixelAt(2150, 720), matte = pixelAt(2150, 100), photoCenter = pixelAt(1080, 720);
    demand("Panorama final Chrome pixels show the original east-edge marker rather than an all-matte blank",
      JSON.stringify(rightEdge) === JSON.stringify([40, 110, 115]) &&
      JSON.stringify(matte) === JSON.stringify([28, 27, 25]) &&
      JSON.stringify(photoCenter) !== JSON.stringify(matte), { rightEdge, matte, photoCenter });
    await page.evaluate(() => extremePanoramaCheck());
    for (const fx of [0, .25, .5, .8, 1]) {
      await page.goto(`${base}/panorama-unit`);
      const anchor = await page.evaluate(async fx => {
        const root = document.createElement("div");
        root.style.cssText = "position:fixed;inset:0";
        document.querySelector("#stage").append(root);
        const cleanup = SS.MODES.panorama.run({
          root, cursor: { i: 0 }, W: innerWidth, H: innerHeight,
          photoMs: 30000, motionMs: 1, url: photo => photo.url,
        }, [{ ...PHOTOS[1], fx, fy: .5 }]);
        await cleanup.ready;
        const pan = root.querySelector(".on .ss-pan"), style = getComputedStyle(pan);
        const width = parseFloat(style.width), matrix = new DOMMatrixReadOnly(style.transform);
        const result = { width, shift: matrix.m41, focalX: width * fx + matrix.m41,
          expected: (innerWidth - width) * SS.panStart(fx, width / innerWidth * 100),
          motion: style.animationName, hint: style.willChange, size: style.backgroundSize };
        cleanup(); return result;
      }, fx);
      demand(`Panorama cached fx=${fx} anchor stays visible and static without a compositor hint`,
        Math.abs(anchor.shift - anchor.expected) < .1 && anchor.focalX >= -.1 &&
        anchor.focalX <= 2160.1 && anchor.width <= 4320 && anchor.motion === "none" &&
        anchor.hint === "auto" && anchor.size === "contain", anchor);
    }
    await page.goto(`${base}/panorama-unit`);
    const cancelledPanorama = await page.evaluate(async () => {
      const original = window.preloadDecoded;
      const root = document.createElement("div"); document.querySelector("#stage").append(root);
      let cleanup, atCleanup, atRequests, requests = 0;
      const images = () => [...root.querySelectorAll(".ss-pan")]
        .filter(pan => pan.style.backgroundImage && pan.style.backgroundImage !== "none").length;
      window.preloadDecoded = (url, signal) => {
        const prepared = original(url, signal);
        prepared.then(() => queueMicrotask(() => queueMicrotask(() => {
          cleanup(); atCleanup = images(); atRequests = requests;
        })), () => {});
        return prepared;
      };
      cleanup = SS.MODES.panorama.run({
        root, cursor: { i: 0 }, W: innerWidth, H: innerHeight,
        photoMs: 30000, motionMs: 32400,
        url: photo => { requests++; return photo.url; },
      }, PHOTOS);
      const ready = await cleanup.ready; window.preloadDecoded = original;
      return { ready, atCleanup, afterReady: images(), atRequests, requests };
    });
    demand("Panorama decode-admission cancellation rejects late publication and derivative work",
      cancelledPanorama.ready === false && cancelledPanorama.atCleanup === 0 &&
      cancelledPanorama.afterReady === 0 && cancelledPanorama.atRequests === cancelledPanorama.requests);
    await page.goto(`${base}/memories-unit`);
    await page.evaluate(async () => {
      // Portal's old WebView has no animation enumeration. Exercise the manual path
      // with both newer animation APIs unavailable, using real CSS/compositor timing.
      Element.prototype.getAnimations = undefined;
      Element.prototype.animate = undefined;
      const root = document.createElement("div");
      root.style.cssText = "position:fixed;inset:0";
      document.querySelector("#stage").append(root);
      window.memoryRoot = root;
      window.memoryRequests = [];
      window.memoryCheck = SS.MODES.memories.run({
        root, cursor: { i: 0 }, W: innerWidth, H: innerHeight,
        photoMs: 2800, motionMs: 6000,
        url: (photo, edge) => { memoryRequests.push({ url: photo.url, edge }); return photo.url; },
      }, window.PHOTOS);
      await memoryCheck.ready;
      window.memoryScenes = [...root.querySelectorAll(".ss-memory-scene")];
      window.memoryState = () => memoryScenes.map(scene => ({
        opacity: getComputedStyle(scene).opacity,
        visibility: getComputedStyle(scene).visibility,
        images: [...scene.querySelectorAll(".memory-photo")].map(image => ({
          url: getComputedStyle(image).backgroundImage,
          transform: getComputedStyle(image).transform,
        })),
      }));
    });
    demand("Memories readiness owns exactly two full-viewport fixed scene roots",
      await page.evaluate(() => memoryScenes.length === 2 && memoryScenes.every(scene => {
        const box = scene.getBoundingClientRect(), style = getComputedStyle(scene);
        return scene.parentNode === memoryRoot && style.position === "absolute" &&
          box.x === 0 && box.y === 0 && box.width === 2160 && box.height === 1440 &&
          style.backgroundColor === "rgb(28, 27, 25)";
      })));
    demand("Memories first beat is one opaque complete photo without a second startup fade or blur",
      await page.evaluate(() => {
        const scene = memoryRoot.lastElementChild, image = scene.querySelector(".memory-photo");
        return memoryRoot.querySelectorAll(".memory-photo").length === 1 &&
          scene.querySelectorAll(".ss-memory-card,.ss-memory-caption,.ss-fill").length === 0 &&
          +getComputedStyle(scene).opacity === 1 &&
          getComputedStyle(image).backgroundSize === "contain" &&
          getComputedStyle(image).filter === "none" && memoryRequests.length === 1 &&
          memoryRequests[0].edge === 2200;
      }));
    demand("Memories unhinted single photo uses a bounded gentle push inside the viewport",
      await page.evaluate(() => {
        const image = memoryRoot.querySelector(".memory-photo"), style = getComputedStyle(image);
        const box = image.getBoundingClientRect();
        return style.animationName === "cinematicDrift" && style.willChange === "transform" &&
          box.left >= 0 && box.top >= 0 && box.right <= innerWidth && box.bottom <= innerHeight;
      }));
    for (let beat = 2; beat <= 6; beat++) {
      await page.evaluate(() => {
        window.memoryOutgoing = memoryRoot.lastElementChild;
        window.memoryOldImages = [...memoryOutgoing.querySelectorAll(".memory-photo")];
        window.memoryOldUrls = memoryOldImages.map(image => image.style.backgroundImage);
      });
      await page.waitForFunction(() => memoryRoot.lastElementChild !== memoryOutgoing &&
        +getComputedStyle(memoryRoot.lastElementChild).opacity > .1 &&
        +getComputedStyle(memoryRoot.lastElementChild).opacity < .9);
      demand(`Memories beat ${beat} fades only the incoming scene for 2000ms over opaque outgoing URLs`,
        await page.evaluate(() => {
          const incoming = memoryRoot.lastElementChild, style = getComputedStyle(incoming);
          return style.transitionProperty === "opacity" && style.transitionDuration === "2s" &&
            +getComputedStyle(memoryOutgoing).opacity === 1 &&
            memoryOldImages.every((image, i) => image.isConnected &&
              image.style.backgroundImage === memoryOldUrls[i]) &&
            memoryScenes.every(scene => scene.parentNode === memoryRoot) &&
            memoryRoot.querySelectorAll(".ss-memory-scene").length === 2 &&
            memoryRoot.querySelectorAll(".memory-photo").length <= 4;
        }));
      demand(`Memories beat ${beat} ${beat === 6 ? "is the sixth-beat three-photo collage" : "remains a single photo"}`,
        await page.evaluate(beat => {
          const incoming = memoryRoot.lastElementChild;
          return incoming.querySelectorAll(".memory-photo").length === (beat === 6 ? 3 : 1) &&
            incoming.querySelectorAll(".ss-memory-card").length === (beat === 6 ? 3 : 0) &&
            incoming.querySelectorAll(".ss-memory-caption").length === (beat === 6 ? 1 : 0);
        }, beat));
      if (beat === 2 || beat === 6) {
        const heldMemories = await page.evaluate(async () => {
          memoryCheck.pause();
          await new Promise(resolve => requestAnimationFrame(() => requestAnimationFrame(resolve)));
          return { scenes: memoryState(), admissions: memoryRequests.length };
        });
        await page.waitForTimeout(2300);
        demand(`Memories old-WebView pause freezes ${beat === 6 ? "collage" : "single motion and"} dissolve beyond its retirement deadline`,
          await page.evaluate(before => !Element.prototype.getAnimations && !Element.prototype.animate &&
            JSON.stringify(memoryState()) === JSON.stringify(before.scenes) &&
            memoryRequests.length === before.admissions &&
            +getComputedStyle(memoryOutgoing).opacity === 1 &&
            memoryOldImages.every(image => image.isConnected), heldMemories));
        await page.evaluate(() => memoryCheck.resume());
      }
      await page.waitForFunction(() => memoryOutgoing.children.length === 0 &&
        +getComputedStyle(memoryRoot.lastElementChild).opacity === 1);
      const memoryRetirement = await page.evaluate(() => ({
        visibility: getComputedStyle(memoryOutgoing).visibility,
        opacity: +getComputedStyle(memoryOutgoing).opacity,
        incomingOpacity: +getComputedStyle(memoryRoot.lastElementChild).opacity,
        fixedRoots: memoryScenes.every(scene => scene.parentNode === memoryRoot),
        images: memoryOldImages.map(image => ({
          connected: image.isConnected, url: image.style.backgroundImage,
          animation: image.style.animationName, promotion: image.style.willChange,
        })),
      }));
      demand(`Memories beat ${beat} retires outgoing URLs and promotion only after incoming opacity one`,
        memoryRetirement.visibility === "hidden" && memoryRetirement.opacity === 0 &&
        memoryRetirement.incomingOpacity === 1 && memoryRetirement.fixedRoots &&
        memoryRetirement.images.every(image => !image.connected && image.url === "none" &&
          image.animation === "none" && image.promotion === "auto"), memoryRetirement);
    }
    await page.evaluate(() => memoryCheck.pause());
    demand("Memories sixth collage has three unique whole static photos with neutral factual caption",
      await page.evaluate(() => {
        const scene = memoryRoot.lastElementChild, images = [...scene.querySelectorAll(".memory-photo")];
        return scene.querySelector(".ss-memory-caption").textContent === "Photo memories" &&
          new Set(images.map(image => image.style.backgroundImage)).size === 3 &&
          images.every(image => {
            const style = getComputedStyle(image);
            return style.backgroundSize === "contain" && style.backgroundPosition === "50% 50%" &&
              style.backgroundRepeat === "no-repeat" && style.animationName === "none" &&
              style.transform === "none" && style.filter === "none" && style.willChange === "auto";
          }) && memoryRequests.slice(-3).every(request => request.edge === 1100);
      }));
    demand("Memories collage cards preserve complete photo aspect ratios and stay within 2160 by 1440",
      await page.evaluate(() => [...memoryRoot.querySelectorAll(".ss-memory-card")].every(card => {
        const box = card.getBoundingClientRect(), image = card.querySelector(".memory-photo");
        const photo = PHOTOS.find(photo => image.style.backgroundImage.includes(photo.url));
        const imageBox = image.getBoundingClientRect();
        return photo && box.left >= 0 && box.top >= 0 && box.right <= innerWidth &&
          box.bottom <= innerHeight && Math.abs(imageBox.width / imageBox.height - photo.w / photo.h) < .002 &&
          Math.abs(imageBox.left - box.left - 12) < .1 && Math.abs(imageBox.top - box.top - 12) < .1;
      })));
    // The previously verified Memories collage screenshot remains unchanged.
    await page.evaluate(() => {
      window.memoryCleanupImages = [...memoryRoot.querySelectorAll(".memory-photo")];
      window.memoryAdmissionsAtCleanup = memoryRequests.length;
      memoryCheck(); memoryCheck(); memoryCheck.resume();
    });
    demand("Memories terminal cleanup hides both roots and releases every owned image URL and animation",
      await page.evaluate(() => memoryScenes.length === 2 && memoryScenes.every(scene =>
        scene.children.length === 0 && getComputedStyle(scene).visibility === "hidden" &&
        +getComputedStyle(scene).opacity === 0 && scene.ontransitionend === null) &&
        memoryCleanupImages.length === 3 && memoryCleanupImages.every(image =>
          !image.isConnected && image.style.backgroundImage === "none" &&
          image.style.animationName === "none" && image.style.willChange === "auto")));
    await page.waitForTimeout(3000);
    demand("Memories stopped ownership cannot republish photos or admit new derivative work",
      await page.evaluate(() => memoryRoot.querySelectorAll(".memory-photo").length === 0 &&
        memoryRequests.length === memoryAdmissionsAtCleanup));
    for (const count of [1, 2, 3]) {
      await page.goto(`${base}/memories-unit`);
      await page.evaluate(async count => {
        const root = document.createElement("div");
        root.style.cssText = "position:fixed;inset:0";
        document.querySelector("#stage").append(root);
        window.smallMemoryRoot = root;
        // Rapid real timer cadence reaches the sixth beat without changing production
        // selection/publication. Pause before the seventh beat to inspect its cards.
        window.smallMemoryCheck = SS.MODES.memories.run({
          root, cursor: { i: 0 }, W: innerWidth, H: innerHeight,
          photoMs: 450, motionMs: 6000, url: photo => photo.url,
        }, PHOTOS.slice(0, count));
        await smallMemoryCheck.ready;
      }, count);
      await page.waitForFunction(() => smallMemoryRoot.lastElementChild.querySelector(".ss-memory-caption"));
      await page.evaluate(() => smallMemoryCheck.pause());
      demand(`Memories ${count}-photo library uses exactly ${count} unique complete static collage photos`,
        await page.evaluate(count => {
          const scene = smallMemoryRoot.lastElementChild, images = [...scene.querySelectorAll(".memory-photo")];
          return scene.querySelectorAll(".ss-memory-card").length === count && images.length === count &&
            new Set(images.map(image => image.style.backgroundImage)).size === count &&
            images.every(image => {
              const style = getComputedStyle(image), box = image.parentNode.getBoundingClientRect();
              return style.backgroundSize === "contain" && style.animationName === "none" &&
                style.transform === "none" && style.willChange === "auto" &&
                box.left >= 0 && box.top >= 0 && box.right <= innerWidth && box.bottom <= innerHeight;
            }) && smallMemoryRoot.querySelectorAll(".ss-memory-scene").length === 2 &&
            smallMemoryRoot.querySelectorAll(".memory-photo").length <= count + 1;
        }, count));
      await page.evaluate(() => smallMemoryCheck());
    }
    await page.goto(`${base}/memories-unit`);
    const faceMemory = await page.evaluate(async () => {
      const root = document.createElement("div"); document.querySelector("#stage").append(root);
      const cleanup = SS.MODES.memories.run({
        root, cursor: { i: 0 }, W: innerWidth, H: innerHeight,
        photoMs: 30000, motionMs: 32400, url: photo => photo.url,
      }, [{ ...PHOTOS[0], fx: .2, fy: .3 }]);
      const ready = await cleanup.ready, image = root.querySelector(".memory-photo");
      const style = getComputedStyle(image);
      const result = { ready, size: style.backgroundSize, motion: style.animationName, promotion: style.willChange };
      cleanup(); return result;
    });
    demand("Memories cached face hints retain a complete still single photo without promotion",
      faceMemory.ready && faceMemory.size === "contain" && faceMemory.motion === "none" &&
      faceMemory.promotion === "auto");
    await page.goto(`${base}/memories-unit`);
    const memoryZoomBounds = await page.evaluate(async () => {
      const root = document.createElement("div");
      root.style.cssText = "position:fixed;inset:0";
      document.querySelector("#stage").append(root);
      const cleanup = SS.MODES.memories.run({
        root, cursor: { i: 0 }, W: innerWidth, H: innerHeight,
        photoMs: 30000, motionMs: 1, url: photo => photo.url,
      }, [PHOTOS[0]]);
      await cleanup.ready;
      await new Promise(resolve => setTimeout(resolve, 80));
      const image = root.querySelector(".memory-photo"), style = getComputedStyle(image);
      const box = image.getBoundingClientRect(), transform = new DOMMatrixReadOnly(style.transform);
      const result = { scaleX: transform.a, scaleY: transform.d, left: box.left, top: box.top,
        right: box.right, bottom: box.bottom, size: style.backgroundSize };
      cleanup(); return result;
    });
    demand("Memories single at its maximum 4 percent zoom keeps the whole contained photo inside 2160 by 1440",
      Math.abs(memoryZoomBounds.scaleX - 1.04) < .0001 &&
      Math.abs(memoryZoomBounds.scaleY - 1.04) < .0001 &&
      memoryZoomBounds.left >= 0 && memoryZoomBounds.top >= 0 &&
      memoryZoomBounds.right <= 2160 && memoryZoomBounds.bottom <= 1440 &&
      memoryZoomBounds.size === "contain", memoryZoomBounds);
    await page.goto(`${base}/memories-unit`);
    const cancelledMemory = await page.evaluate(async () => {
      const original = window.preloadDecoded;
      const root = document.createElement("div"); document.querySelector("#stage").append(root);
      let cleanup, atCleanup, atRequests, requests = 0;
      window.preloadDecoded = (url, signal) => {
        const prepared = original(url, signal);
        prepared.then(() => queueMicrotask(() => queueMicrotask(() => {
          cleanup(); atCleanup = root.querySelectorAll(".memory-photo").length; atRequests = requests;
        })), () => {});
        return prepared;
      };
      cleanup = SS.MODES.memories.run({
        root, cursor: { i: 0 }, W: innerWidth, H: innerHeight,
        photoMs: 30000, motionMs: 32400,
        url: photo => { requests++; return photo.url; },
      }, PHOTOS);
      const ready = await cleanup.ready; window.preloadDecoded = original;
      return { ready, atCleanup, afterReady: root.querySelectorAll(".memory-photo").length, atRequests, requests };
    });
    demand("Memories decode-admission cancellation rejects late publication and derivative work",
      cancelledMemory.ready === false && cancelledMemory.atCleanup === 0 &&
      cancelledMemory.afterReady === 0 && cancelledMemory.atRequests === cancelledMemory.requests);
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
    await page.goto(`${base}/sidebar-unit`);
    await page.evaluate(async () => {
      Element.prototype.getAnimations = undefined;
      const root = document.createElement("div");
      root.style.cssText = "position:fixed;inset:0";
      document.querySelector("#stage").append(root);
      window.sidebarCheck = SS.MODES.heroSidebar.run({
        root, cursor: { i: 0 }, W: innerWidth, H: innerHeight,
        photoMs: 30000, microMs: 2400, url: photo => photo.url,
      }, window.PHOTOS);
      await window.sidebarCheck.ready;
    });
    demand("Hero + Sidebar initially shows three unique opaque whole photos without blur",
      await page.locator(".ss-fill").count() === 0 &&
      await page.evaluate(() => {
        const images = [...document.querySelectorAll(".ss-pane .ss-img")]
          .filter(n => getComputedStyle(n).backgroundImage !== "none");
        return images.length === 3 &&
          new Set(images.map(n => getComputedStyle(n).backgroundImage)).size === 3 &&
          images.every(n => {
            const style = getComputedStyle(n), pane = getComputedStyle(n.parentNode);
            return style.backgroundSize === "contain" && style.animationName === "none" &&
              style.willChange === "auto" && +pane.opacity === 1 &&
              pane.backgroundColor === "rgb(28, 27, 25)";
          });
      }));
    demand("Hero + Sidebar retains a larger hero and two stacked side panes inside the viewport",
      await page.evaluate(() => {
        const [hero, a, b] = [...document.querySelectorAll(".ss-hs .ss-cell")]
          .map(n => n.getBoundingClientRect());
        return hero.width > a.width * 1.8 && a.left === b.left && a.bottom < b.top &&
          hero.top === a.top && hero.bottom === b.bottom &&
          [hero, a, b].every(box => box.left >= 19 && box.top >= 19 &&
            box.right <= innerWidth - 19 && box.bottom <= innerHeight - 19);
      }));
    await page.waitForFunction(() => [...document.querySelectorAll(".ss-pane")]
      .some(n => +getComputedStyle(n).opacity > .1 && +getComputedStyle(n).opacity < .9));
    demand("Hero + Sidebar fades only the incoming pane over its opaque outgoing photo",
      await page.evaluate(() => [...document.querySelectorAll(".ss-hs .ss-cell")].some(cell =>
        +getComputedStyle(cell.lastElementChild).opacity > 0 &&
        +getComputedStyle(cell.lastElementChild).opacity < 1 &&
        +getComputedStyle(cell.firstElementChild).opacity === 1)));
    const heldSidebar = await page.evaluate(() => {
      window.sidebarCheck.pause();
      return [...document.querySelectorAll(".ss-pane")].map(n => ({
        opacity: getComputedStyle(n).opacity,
        url: getComputedStyle(n.querySelector(".ss-img")).backgroundImage,
      }));
    });
    await page.waitForTimeout(1400);
    demand("Hero + Sidebar old-WebView pause freezes the live pane fade and retains photo URLs",
      await page.evaluate(before => [...document.querySelectorAll(".ss-pane")].every((n, i) =>
        getComputedStyle(n).opacity === before[i].opacity &&
        getComputedStyle(n.querySelector(".ss-img")).backgroundImage === before[i].url), heldSidebar));
    await page.evaluate(() => window.sidebarCheck.resume());
    await page.waitForFunction(() => [...document.querySelectorAll(".ss-hs .ss-cell")]
      .every(cell => [...cell.querySelectorAll(".ss-img")]
        .filter(n => getComputedStyle(n).backgroundImage !== "none").length === 1));
    demand("Hero + Sidebar retires the old URL only after its replacement becomes opaque",
      await page.evaluate(() => [...document.querySelectorAll(".ss-pane")].every(n =>
        n.classList.contains("retired")
          ? getComputedStyle(n.querySelector(".ss-img")).backgroundImage === "none"
          : +getComputedStyle(n).opacity === 1)));
    await page.waitForTimeout(6500);
    demand("Hero + Sidebar retains exactly six image slots through repeated pane swaps",
      await page.locator(".ss-pane .ss-img").count() === 6 &&
      await page.evaluate(() => [...document.querySelectorAll(".ss-hs .ss-cell")]
        .every(cell => cell.children.length === 2 &&
          [...cell.children].some(n => +getComputedStyle(n).opacity === 1))));
    await page.evaluate(() => { window.sidebarCheck.pause(); window.sidebarCheck(); });
    demand("Hero + Sidebar terminal cleanup hides every pane and releases its photo resources",
      await page.evaluate(() => [...document.querySelectorAll(".ss-pane")].every(n =>
        getComputedStyle(n).visibility === "hidden" && +getComputedStyle(n).opacity === 0 &&
        getComputedStyle(n.querySelector(".ss-img")).backgroundImage === "none")));
    await page.goto(`${base}/sidebar-unit`);
    const cancelledSidebar = await page.evaluate(async () => {
      const original = window.preloadDecoded;
      const root = document.createElement("div"); document.querySelector("#stage").append(root);
      let cleanup, atCleanup, atRequests, requests = 0;
      const images = () => [...root.querySelectorAll(".ss-img")]
        .filter(n => getComputedStyle(n).backgroundImage !== "none").length;
      window.preloadDecoded = (url, signal) => {
        const prepared = original(url, signal);
        prepared.then(() => queueMicrotask(() => queueMicrotask(() => {
          cleanup(); atCleanup = images(); atRequests = requests;
        })), () => {});
        return prepared;
      };
      cleanup = SS.MODES.heroSidebar.run({
        root, cursor: { i: 0 }, W: innerWidth, H: innerHeight,
        photoMs: 30000, microMs: 30000,
        url: photo => { requests++; return photo.url; },
      }, window.PHOTOS);
      const ready = await cleanup.ready; window.preloadDecoded = original;
      return { ready, atCleanup, afterReady: images(), atRequests, requests };
    });
    demand("Hero + Sidebar cancellation rejects both late photo publication and derivative admission",
      cancelledSidebar.ready === false && cancelledSidebar.atCleanup === 0 &&
      cancelledSidebar.afterReady === 0 && cancelledSidebar.atRequests === cancelledSidebar.requests);
    await page.goto(`${base}/sidebar-unit`);
    const singleSidebar = await page.evaluate(async () => {
      const root = document.createElement("div");
      root.style.cssText = "position:fixed;inset:0";
      document.querySelector("#stage").append(root);
      const cleanup = SS.MODES.heroSidebar.run({
        root, cursor: { i: 0 }, W: innerWidth, H: innerHeight,
        photoMs: 30000, microMs: 30000, url: photo => photo.url,
      }, [window.PHOTOS[0]]);
      await cleanup.ready;
      const result = root.querySelectorAll(".ss-cell").length === 1 &&
        root.querySelectorAll(".ss-col").length === 1 &&
        root.querySelector(".ss-cell").getBoundingClientRect().width === innerWidth - 40;
      cleanup(); return result;
    });
    demand("Hero + Sidebar expands a one-photo library to one full-width pane", singleSidebar);
    for (const initial of [true, false]) {
      await page.goto(`${base}/sidebar-unit`);
      const rapidPause = await page.evaluate(async initial => {
        const original = window.preloadDecoded;
        const root = document.createElement("div"); document.querySelector("#stage").append(root);
        let hold = initial, release, reached, requests = 0;
        const blocked = new Promise(resolve => { reached = resolve; });
        window.preloadDecoded = (url, signal) => {
          const prepared = original(url, signal);
          if (!hold) return prepared;
          hold = false;
          return prepared.then(() => new Promise(resolve => {
            release = () => resolve(true); reached();
          }));
        };
        const cleanup = SS.MODES.heroSidebar.run({
          root, cursor: { i: 0 }, W: innerWidth, H: innerHeight,
          photoMs: 30000, microMs: 300,
          url: photo => { requests++; return photo.url; },
        }, window.PHOTOS);
        const photos = () => [...root.querySelectorAll(".ss-img")]
          .map(n => getComputedStyle(n).backgroundImage);
        try {
          if (!initial) { await cleanup.ready; hold = true; }
          await blocked; cleanup.pause(); release();
          await new Promise(resolve => setTimeout(resolve, 20));
          cleanup.resume(); cleanup.pause();
          const before = photos(), admitted = requests;
          await new Promise(resolve => setTimeout(resolve, 50));
          const held = JSON.stringify(photos()) === JSON.stringify(before) && requests === admitted;
          cleanup.resume(); await cleanup.ready;
          await new Promise(resolve => setTimeout(resolve, 50));
          return held && photos().filter(url => url !== "none").length >= 3;
        } finally { cleanup(); window.preloadDecoded = original; }
      }, initial);
      demand(`Hero + Sidebar ${initial ? "initial" : "replacement"} decode survives resume/re-pause without hidden work`,
        rapidPause);
    }
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
      viewport: page.viewportSize(), browser: browser.version(),
      memories: {
        passed: checks.filter(name => /memories/i.test(name)).length,
        fixtureSource: "Local synthetic gallery-fixtures.json and gallery SVGs",
        screenshots: ["memories.png", "memories-collage.png"],
        oldWebViewManualPath: "Element.getAnimations and Element.animate unavailable",
      },
      panorama: {
        passed: checks.filter(name => /panorama/i.test(name)).length,
        fixtureSource: "Local original panorama-wide.svg, panorama-extreme.svg and exact-aspect synthetic SVGs",
        screenshots: ["panorama.png"],
        geometryViewports: [[2160, 1440], [1920, 1080], [1024, 768], [640, 960]],
        geometryAspects: [1.3, 1.5, 2, 3, 8, 10],
        originalEndMarkerPixels: { rightEdge, matte, photoCenter },
        oldWebViewManualPath: "Element.getAnimations and Element.animate unavailable",
      },
      mosaic: {
        passed: checks.filter(name => /mosaic/i.test(name)).length,
        fixtureSource: "All twelve local synthetic gallery SVGs and exactly dimensioned synthetic geometry SVGs",
        screenshots: ["mosaic.png"],
        composition: { cells: 9, fixedImagePanes: 18, derivativeEdge: 1200 },
        oldWebViewManualPath: "Element.getAnimations and Element.animate unavailable",
      },
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
