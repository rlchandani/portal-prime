/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */
"use strict";

// Real production HTML/scripts, generated images and delayed local HTTP probes.
// This checks first-scene admission; it does not measure Portal panel timing or FPS.
const assert = require("node:assert/strict");
const fs = require("node:fs");
const path = require("node:path");
const http = require("node:http");
const crypto = require("node:crypto");
const { execFileSync } = require("node:child_process");
const { chromium } = require("playwright");

const repo = path.resolve(__dirname, "../../..");
const assetDir = path.join(repo, "app/src/main/assets/screensaver");
const files = ["index.html", "styles.css", "photos.js", "lib-engine.js",
  "modes.js", "mode-impls.js", "scheduler.js", "cinema-grain.png"];
const assets = Object.fromEntries(files.map(name => [name,
  process.env.SLIDESHOW_ASSET_REF
    ? execFileSync("git", ["-c", "core.hooksPath=/dev/null", "--no-optional-locks",
      "show", `${process.env.SLIDESHOW_ASSET_REF}:app/src/main/assets/screensaver/${name}`],
      { cwd: repo })
    : fs.readFileSync(path.join(assetDir, name))]));
const artwork = Buffer.from(`<svg xmlns="http://www.w3.org/2000/svg" width="720" height="480">
  <rect width="720" height="480" fill="#284653"/>
  <path d="M0 400L180 120L400 400L560 200L720 400V480H0Z" fill="#8bb6aa"/>
  <circle cx="570" cy="85" r="44" fill="#edd395"/></svg>`);
const delayed = new Set();
const server = http.createServer((req, res) => {
  const pathname = new URL(req.url, "http://fixture").pathname;
  if (pathname === "/photo.svg") {
    res.setHeader("Content-Type", "image/svg+xml");
    res.end(artwork);
  } else if (pathname === "/probe.svg") {
    res.setHeader("Content-Type", "image/svg+xml");
    const timer = setTimeout(() => {
      delayed.delete(timer);
      if (!res.destroyed) res.end(artwork);
    }, 2200);
    delayed.add(timer);
    res.on("close", () => { clearTimeout(timer); delayed.delete(timer); });
  } else {
    const name = pathname === "/" ? "index.html" : pathname.slice(1);
    if (!Object.hasOwn(assets, name)) { res.writeHead(404).end(); return; }
    const type = name.endsWith(".html") ? "text/html"
      : name.endsWith(".css") ? "text/css"
      : name.endsWith(".png") ? "image/png" : "application/javascript";
    res.setHeader("Content-Type", type);
    res.end(assets[name]);
  }
});

const checks = [], observations = [], errors = [];
function check(name, value) {
  checks.push({ name, passed: Boolean(value) });
  assert.ok(value, name);
}

(async () => {
  let browser;
  let failed = null;
  try {
    await new Promise(resolve => server.listen(0, "127.0.0.1", resolve));
    const origin = `http://127.0.0.1:${server.address().port}`;
    browser = await chromium.launch({ channel: "chrome", headless: true });
    const cases = ["kenBurns", "framed", "push", "memories", "polaroid"]
      .flatMap(mode => [true, false].map(known => ({ mode, known, protectedSeed: false })))
      .concat([{ mode: "cinema", known: true, protectedSeed: true },
        { mode: "shuffle", known: true, protectedSeed: true }]);
    for (const { mode, known, protectedSeed } of cases) {
      const page = await browser.newPage({ viewport: { width: 2160, height: 1440 } });
      page.on("pageerror", error => errors.push(error.message));
      page.on("request", request => {
        if (!request.url().startsWith(origin)) errors.push("unexpected_external_request");
      });
      await page.addInitScript(({ mode, known }) => {
        // Keep the real Fisher–Yates deck in source order to isolate the known-first case.
        Math.random = () => 0.999999;
        const source = Array.from({ length: 8 }, (_, i) => ({
          url: `/photo.svg?photo=${i}`,
          // The selected first photo is known; unrelated photos remain unknown.
          ...(known && i === 0 ? { w: 720, h: 480 } : {}),
        }));
        window.fixtureProbes = [];
        window.fixtureScenes = [];
        window.fixtureDecoded = [];
        const nativeDecode = HTMLImageElement.prototype.decode;
        HTMLImageElement.prototype.decode = function (...args) {
          return nativeDecode.apply(this, args).then(value => {
            window.fixtureDecoded.push(this.currentSrc || this.src);
            return value;
          });
        };
        window.Android = {
          getPhotos: () => JSON.stringify(source),
          getMode: () => mode,
          getIntervalSec: () => 120,
          getPhotoMotion: () => true,
          use24Hour: () => false,
          isWatching: () => false,
          supportsNativeFilmstrip: () => false,
          scaledUrlForSize: (url, edge) => {
            if (edge === 320) {
              window.fixtureProbes.push({ edge, at: performance.now() });
              return `/probe.svg?photo=${new URL(url, location.origin).searchParams.get("photo")}`;
            }
            return url;
          },
          reportPresentedStyle: style => {
            window.fixtureScenes.push({ style, at: performance.now(),
              probes: window.fixtureProbes.length,
              firstPhotoMetadata: [window.PHOTOS[0].w, window.PHOTOS[0].h,
                window.PHOTOS[0].measured] });
          },
        };
      }, { mode, known });
      await page.goto(origin, { waitUntil: "domcontentloaded", timeout: 10000 });
      await page.waitForFunction(() => window.fixtureScenes?.length > 0, null, { timeout: 10000 });
      const observation = await page.evaluate(() => ({
        ...window.fixtureScenes[0],
        loadingHidden: document.getElementById("loading").classList.contains("hide"),
        decodedImages: window.fixtureDecoded.length,
      }));
      const label = `${mode} (${known ? "known first dimensions" : "unknown dimensions"})`;
      observations.push({ mode, known, protectedSeed, ...observation });
      check(`${label}: selected style is admitted`,
        mode === "shuffle" ? typeof observation.style === "string" : observation.style === mode);
      check(`${label}: loading indicator is removed`, observation.loadingHidden);
      check(`${label}: actual photo image is decoded`, observation.decodedImages > 0);
      check(`${label}: first-photo dimensions are valid at scene admission`,
        JSON.stringify(observation.firstPhotoMetadata) === JSON.stringify([720, 480, true]));
      check(`${label}: orientation seed follows selected-style policy`,
        protectedSeed ? observation.probes === 2 : observation.probes === 0);
      await page.close();
    }
    check("no page errors or external image requests", errors.length === 0);
  } catch (error) {
    failed = error.code || error.name;
  } finally {
    if (browser) await browser.close();
    for (const timer of delayed) clearTimeout(timer);
    server.closeAllConnections();
    await new Promise(resolve => server.close(resolve));
  }
  const report = {
    success: failed === null,
    passed: checks.filter(item => item.passed).length,
    failed: checks.filter(item => !item.passed).length,
    failureType: failed,
    sourceRef: process.env.SLIDESHOW_ASSET_REF || "working-tree",
    observations, checks,
    assetHashes: Object.fromEntries(Object.entries(assets).map(([name, bytes]) => [
      name, crypto.createHash("sha256").update(bytes).digest("hex")])),
    physicalPortalMeasured: false, fpsMeasured: false,
    fixtureDeckOrderControlled: true,
    scope: "Actual production HTML first decoded scene with delayed local orientation probes",
  };
  const target = process.env.STARTUP_BROWSER_RECEIPT;
  if (target) fs.writeFileSync(target, JSON.stringify(report, null, 2) + "\n");
  console.log(JSON.stringify({ success: report.success, passed: report.passed,
    failed: report.failed, failureType: failed, observations }));
  process.exitCode = report.success ? 0 : 1;
})();
