// Copyright (c) 2026 Starbright Lab.
// Licensed under the MIT license in the repository root.
// Actual production scripts/CSS, original fixtures, isolated local HTTP/Chrome.
const assert = require("node:assert/strict");
const fs = require("node:fs");
const path = require("node:path");
const http = require("node:http");
const { chromium } = require("playwright");
const here = __dirname, repo = path.resolve(here, "../../..");
const assets = path.join(repo, "app/src/main/assets/screensaver");
const evidence = path.join(here, "evidence");
fs.mkdirSync(evidence, { recursive: true });
let closedSlow = 0, admittedSlow = 0;
const held = new Map(), checks = [], errors = [];
const fixture = name => fs.readFileSync(path.join(here, "assets", name + ".png"));
const server = http.createServer((req, res) => {
  const u = new URL(req.url, "http://local");
  if (/^\/slow\/(dunes|tidal)\.png$/.test(u.pathname)) {
    admittedSlow++; held.set(res, path.basename(u.pathname, ".png"));
    res.on("close", () => { held.delete(res); closedSlow++; });
    return;
  }
  let content, type;
  if (u.pathname === "/render") {
    const slow = u.searchParams.has("slow"), unknown = u.searchParams.has("unknown");
    const photos = ["dunes", "tidal"].map(name => ({
      url: (slow ? "/slow/" : "/fixtures/") + name + ".png",
      w: unknown ? 0 : 480, h: unknown ? 0 : 720,
    }));
    const mode = u.searchParams.get("mode") || "diptych";
    const bridge = `<base href="/assets/"><script>window.Android={
      getPhotos:()=>${JSON.stringify(JSON.stringify(photos))},getMode:()=>${JSON.stringify(mode)},
      getIntervalSec:()=>30,scaledUrlForSize:url=>url};</script>`;
    content = fs.readFileSync(path.join(assets, "index.html"), "utf8").replace("<head>", "<head>" + bridge);
    type = "text/html";
  } else if (/^\/fixtures\/(dunes|tidal)\.png$/.test(u.pathname)) {
    content = fixture(path.basename(u.pathname, ".png")); type = "image/png";
  } else if (/^\/assets\/(?:styles\.css|photos\.js|lib-engine\.js|modes\.js|mode-impls\.js|scheduler\.js)$/.test(u.pathname)) {
    content = fs.readFileSync(path.join(assets, path.basename(u.pathname)));
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
    for (const mode of ["diptych", "framed", "kenBurns"]) {
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
