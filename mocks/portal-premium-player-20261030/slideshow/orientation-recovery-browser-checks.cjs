/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */
"use strict";

// Actual production scripts, a real HTTP 404 and an original generated portrait.
// No household media, external requests or physical Portal timing claims.
const assert = require("node:assert/strict");
const fs = require("node:fs");
const path = require("node:path");
const http = require("node:http");
const { execFileSync } = require("node:child_process");
const crypto = require("node:crypto");
const { chromium } = require("playwright");
const repo = path.resolve(__dirname, "../../..");
const assets = path.join(repo, "app/src/main/assets/screensaver");
const errors = [], requests = [], responses = [], checks = [];
const scripts = Object.fromEntries(["photos.js", "lib-engine.js", "modes.js"].map(name => [name,
  process.env.SLIDESHOW_ASSET_REF
    ? execFileSync("git", ["-c", "core.hooksPath=/dev/null", "--no-optional-locks", "show",
        `${process.env.SLIDESHOW_ASSET_REF}:app/src/main/assets/screensaver/${name}`], { cwd: repo })
    : fs.readFileSync(path.join(assets, name))]));
const server = http.createServer((req, res) => {
  const url = new URL(req.url, "http://local");
  if (url.pathname === "/") {
    res.setHeader("Content-Type", "text/html");
    res.end(`<html><body><script>
      window.Android = {
        getPhotos: () => JSON.stringify([{url:"/original.svg",fx:.5,fy:.2,pw:.8}]),
        scaledUrlForSize: (url, edge) => edge === 320 ? "/missing-probe.svg" : "/portrait.svg"
      };
      </script><script src="/photos.js"></script><script src="/lib-engine.js"></script><script src="/modes.js"></script></body></html>`);
  } else if (["/photos.js", "/lib-engine.js", "/modes.js"].includes(url.pathname)) {
    res.setHeader("Content-Type", "application/javascript");
    res.end(scripts[url.pathname.slice(1)]);
  } else if (url.pathname === "/portrait.svg") {
    res.setHeader("Content-Type", "image/svg+xml");
    res.end(`<svg xmlns="http://www.w3.org/2000/svg" width="480" height="720">
      <rect width="480" height="720" fill="#243b53"/>
      <circle cx="240" cy="180" r="72" fill="#efc58c"/>
      <path d="M120 660V360Q240 270 360 360V660Z" fill="#70a5aa"/>
    </svg>`);
  } else { res.statusCode = 404; res.end(); }
});
function check(name, actual, expected) {
  assert.deepEqual(actual, expected, name);
  checks.push({ name, pass: true });
}

(async () => {
  let browser;
  try {
    await new Promise(resolve => server.listen(0, "127.0.0.1", resolve));
    const origin = `http://127.0.0.1:${server.address().port}`;
    browser = await chromium.launch({ channel: "chrome", headless: true });
    const page = await browser.newPage({ viewport: { width: 2160, height: 1440 } });
    page.on("pageerror", error => errors.push(error.message));
    page.on("request", request => requests.push(request.url()));
    page.on("response", response => responses.push({ url: response.url(), status: response.status() }));
    await page.goto(origin, { waitUntil: "load" });
    await page.evaluate(() => {
      window.orientationImages = [];
      const BrowserImage = window.Image;
      window.Image = class extends BrowserImage {
        constructor(...args) { super(...args); window.orientationImages.push(this); }
      };
    });
    const failed = await page.evaluate(async () => {
      const photo = PHOTOS[0];
      await premeasureAll(1);
      return { measured: photo.measured, width: photo.w, height: photo.h,
        aspect: aspect(photo), kind: bucketOf(photo), faces: [photo.fx, photo.fy, photo.pw], count: PHOTOS.length };
    });
    check("real HTTP probe error leaves dimensions unknown", [failed.measured, failed.width, failed.height], [false, 0, 0]);
    check("orientation probe received an actual HTTP 404", responses.some(r => r.url === origin + "/missing-probe.svg" && r.status === 404), true);
    check("unknown orientation keeps the existing safe aspect", failed.aspect, 1600 / 1067);
    check("unknown orientation keeps safe landscape classification", failed.kind, "landscape");
    const recovered = await page.evaluate(async () => {
      const photo = PHOTOS[0], url = photoUrl(photo, 1800);
      const loadedUrl = await preloadDecoded(url);
      const image = window.orientationImages.at(-1);
      const result = { measured: photo.measured, width: photo.w, height: photo.h,
        aspect: aspect(photo), kind: bucketOf(photo), faces: [photo.fx, photo.fy, photo.pw],
        count: PHOTOS.length, source: photo.url, decoded: loadedUrl === url,
        handlersReleased: image?.onload === null && image?.onerror === null };
      if (image) image.src = "";
      return result;
    });
    check("successful real display decode supplies portrait dimensions", [recovered.decoded, recovered.measured, recovered.width, recovered.height], [true, true, 480, 720]);
    check("display image received an actual HTTP 200", responses.some(r => r.url === origin + "/portrait.svg" && r.status === 200), true);
    check("portrait aspect recovers after the failed startup probe", recovered.aspect, 2 / 3);
    check("shuffle can classify the recovered photo as portrait", recovered.kind, "portrait");
    check("cached face focus and people weight survive recovery", recovered.faces, [.5, .2, .8]);
    check("orientation recovery preserves the deck and original source", [recovered.count, recovered.source], [1, "/original.svg"]);
    check("display decode releases image event handlers", recovered.handlersReleased, true);
    check("only the owned local server receives requests", requests.every(url => url.startsWith(origin + "/")), true);
    check("browser has no page errors", errors, []);
    const result = { checks, expectedHttp404: true, viewport: { width: 2160, height: 1440 },
      productionScripts: ["photos.js", "lib-engine.js", "modes.js"], householdMediaAccessed: false,
      sourceSha256: Object.fromEntries(Object.entries(scripts).map(([name, content]) =>
        [name, crypto.createHash("sha256").update(content).digest("hex")])),
      deliveredFpsMeasured: false, physicalPortalVerified: false };
    if (process.env.ORIENTATION_EVIDENCE_FILE) {
      fs.writeFileSync(process.env.ORIENTATION_EVIDENCE_FILE, JSON.stringify(result, null, 2) + "\n");
    }
    console.log(JSON.stringify(result));
  } finally {
    await browser?.close();
    await new Promise(resolve => server.close(resolve));
  }
})().catch(error => { console.error(error.message); process.exitCode = 1; });
