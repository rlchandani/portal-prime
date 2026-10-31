/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */
"use strict";
// Real production HTML/CSS/scripts with generated images; no household media or Portal timing.
const assert = require("node:assert/strict");
const fs = require("node:fs"), path = require("node:path"), http = require("node:http");
const crypto = require("node:crypto");
const { execFileSync } = require("node:child_process");
const { chromium } = require("playwright");
const repo = path.resolve(__dirname, "../../..");
const names = ["index.html", "styles.css", "photos.js", "lib-engine.js",
  "modes.js", "mode-impls.js", "scheduler.js", "cinema-grain.png"];
const assets = Object.fromEntries(names.map(name => [name, process.env.SLIDESHOW_ASSET_REF
  ? execFileSync("git", ["-c", "core.hooksPath=/dev/null", "--no-optional-locks",
    "show", `${process.env.SLIDESHOW_ASSET_REF}:app/src/main/assets/screensaver/${name}`], { cwd: repo })
  : fs.readFileSync(path.join(repo, "app/src/main/assets/screensaver", name))]));
const art = Buffer.from(`<svg xmlns="http://www.w3.org/2000/svg" width="720" height="480">
  <rect width="720" height="480" fill="#497890"/>
  <path d="M0 480L210 100L520 480Z" fill="#a2c38a"/><circle cx="580" cy="80" r="44" fill="#efd19b"/></svg>`);
const server = http.createServer((req, res) => {
  const url = new URL(req.url, "http://fixture");
  if (url.pathname === "/photo.svg") {
    res.setHeader("Content-Type", "image/svg+xml"); res.end(art); return;
  }
  const name = url.pathname === "/" ? "index.html" : url.pathname.slice(1);
  if (!Object.hasOwn(assets, name)) { res.writeHead(404).end(); return; }
  res.setHeader("Content-Type", name.endsWith(".html") ? "text/html"
    : name.endsWith(".css") ? "text/css" : name.endsWith(".png") ? "image/png" : "application/javascript");
  res.end(assets[name]);
});
const checks = [], observations = [], errors = [];
function check(name, passed) {
  checks.push({ name, passed: Boolean(passed) }); assert.ok(passed, name);
}
(async () => {
  let browser, failure = null;
  try {
    await new Promise(resolve => server.listen(0, "127.0.0.1", resolve));
    const origin = `http://127.0.0.1:${server.address().port}`;
    browser = await chromium.launch({ channel: "chrome", headless: true });
    const cases = ["kenBurns", "push", "framed", "memories", "polaroid", "shuffle"]
      .flatMap(mode => [false, true].map(paused => ({ mode, paused })));
    for (const { mode, paused } of cases) {
      const page = await browser.newPage({ viewport: { width: 2160, height: 1440 } });
      page.on("pageerror", error => errors.push(error.message));
      page.on("request", request => {
        if (!request.url().startsWith(origin)) errors.push("external_request");
      });
      await page.addInitScript(({ mode }) => {
        Math.random = () => .999999;
        window.fixtureScenes = []; window.fixtureDecoded = 0;
        const decode = HTMLImageElement.prototype.decode;
        HTMLImageElement.prototype.decode = function (...args) {
          return decode.apply(this, args).then(result => { window.fixtureDecoded++; return result; });
        };
        window.Android = {
          getPhotos: () => JSON.stringify(Array.from({ length: 8 }, (_, i) =>
            ({ url: `/photo.svg?i=${i}`, w: 720, h: 480 }))),
          getMode: () => mode, getIntervalSec: () => 5, getPhotoMotion: () => true,
          use24Hour: () => false, isWatching: () => false, supportsNativeFilmstrip: () => false,
          scaledUrlForSize: url => url,
          reportPresentedStyle: style => {
            // Keep the production automatic Shuffle beat at its supported 5-second minimum.
            if (mode === "shuffle") SS.BEAT[style] = 5000;
            const scene = [...document.querySelectorAll(".ss-beat.on")].at(-1);
            const css = getComputedStyle(scene);
            window.fixtureScenes.push({ style, opacity: Number(css.opacity),
              transition: css.transitionDuration, decoded: window.fixtureDecoded,
              layerCount: document.querySelectorAll(".ss-beat").length });
          },
        };
      }, { mode });
      await page.goto(`${origin}/?nativeClock=1&hostPaused=${paused ? 1 : 0}`, {
        waitUntil: "domcontentloaded", timeout: 10000,
      });
      if (paused) {
        await page.waitForTimeout(150);
        check(`${mode}: paused startup admits no scene`,
          await page.evaluate(() => fixtureScenes.length === 0 && !document.querySelector(".ss-beat.on")));
        await page.evaluate(() => immortalSlideshow.resume());
      }
      await page.waitForFunction(() => fixtureScenes.length === 1, null, { timeout: 10000 });
      const first = await page.evaluate(() => fixtureScenes[0]);
      observations.push({ mode, paused, first });
      check(`${mode}/${paused}: first decoded composition is immediately opaque`, first.opacity === 1);
      check(`${mode}/${paused}: first composition has no parent dissolve`, first.transition === "0s");
      check(`${mode}/${paused}: first scene waits for actual image decoding`, first.decoded > 0);
      check(`${mode}/${paused}: first scene has only one composition`, first.layerCount === 1);
      await page.evaluate(() => immortalSlideshow.pause());
      await page.waitForTimeout(80);
      check(`${mode}/${paused}: pause retains the opaque first composition`,
        await page.evaluate(() => Number(getComputedStyle(document.querySelector(".ss-beat.on")).opacity) === 1));
      await page.evaluate(() => immortalSlideshow.resume());
      if (mode === "shuffle") {
        await page.waitForFunction(() => fixtureScenes.length >= 2, null, { timeout: 10000 });
        const second = await page.evaluate(() => fixtureScenes[1]);
        observations.at(-1).second = second;
        check(`${mode}/${paused}: next scene preserves the 1.6-second dissolve`, second.transition === "1.6s");
        check(`${mode}/${paused}: next scene starts its dissolve over the retained predecessor`,
          second.opacity < 1 && second.layerCount === 2);
        check(`${mode}/${paused}: next scene also waits for decoded images`, second.decoded >= first.decoded);
      }
      await page.close();
    }
    check("no page errors or external requests", errors.length === 0);
  } catch (error) { failure = error.code || error.name; }
  finally {
    if (browser) await browser.close();
    server.closeAllConnections(); await new Promise(resolve => server.close(resolve));
  }
  const report = { success: failure === null, passed: checks.filter(x => x.passed).length,
    failed: checks.filter(x => !x.passed).length, failureType: failure, observations, checks,
    sourceRef: process.env.SLIDESHOW_ASSET_REF || "working-tree",
    assetHashes: Object.fromEntries(Object.entries(assets).map(([name, bytes]) =>
      [name, crypto.createHash("sha256").update(bytes).digest("hex")])),
    physicalPortalMeasured: false, fpsMeasured: false,
    scope: "Production decoded scene opacity, pause, startup resume and subsequent dissolve in Chromium" };
  if (process.env.FIRST_SCENE_RECEIPT)
    fs.writeFileSync(process.env.FIRST_SCENE_RECEIPT, JSON.stringify(report, null, 2) + "\n");
  console.log(JSON.stringify({ success: report.success, passed: report.passed,
    failed: report.failed, failureType: failure, observations }));
  process.exitCode = report.success ? 0 : 1;
})();
