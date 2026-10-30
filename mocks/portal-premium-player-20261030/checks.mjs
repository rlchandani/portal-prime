/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */
// Development-only browser checks. The mock itself has zero package dependencies.
import assert from "node:assert/strict";
import { createServer } from "node:http";
import { readFile, mkdir, writeFile } from "node:fs/promises";
import { dirname, join, resolve, extname, sep } from "node:path";
import { fileURLToPath, pathToFileURL } from "node:url";
import { createRequire } from "node:module";

const root = dirname(fileURLToPath(import.meta.url));
const require = createRequire(import.meta.url);
const { chromium } = require(process.env.PLAYWRIGHT_MODULE || "playwright");
const types = { ".html": "text/html", ".css": "text/css", ".js": "text/javascript", ".svg": "image/svg+xml" };
const server = createServer(async (request, response) => {
  try {
    const path = resolve(root, "." + decodeURIComponent(new URL(request.url, "http://localhost").pathname));
    assert(path === root || path.startsWith(root + sep));
    const file = path === root ? join(root, "index.html") : path;
    response.setHeader("Content-Type", types[extname(file)] || "application/octet-stream");
    response.end(await readFile(file));
  } catch { response.writeHead(404); response.end("Not found"); }
});
await new Promise((done) => server.listen(0, "127.0.0.1", done));
const base = `http://127.0.0.1:${server.address().port}`;
let browser;
const results = [], errors = [], external = [];
try {
  browser = await chromium.launch({ headless: true, channel: process.env.BROWSER_CHANNEL || "chrome" });
  const page = await browser.newPage({ viewport: { width: 2160, height: 1440 } });
  page.on("pageerror", (error) => errors.push(error.message));
  page.on("request", (request) => { if (!request.url().startsWith(base)) external.push(request.url()); });
  const check = async (name, run) => { await run(); results.push(name); console.log(`PASS ${name}`); };
  const scenario = (value) => page.locator("#scenario").selectOption(value);
  const click = (id) => page.locator(`#${id}`).click();
  const text = (id) => page.locator(`#${id}`).textContent();
  const setRange = (id, value) => page.locator(`#${id}`).evaluate((input, next) => {
    input.value = next; input.dispatchEvent(new Event("input", { bubbles: true })); input.dispatchEvent(new Event("change", { bubbles: true }));
  }, String(value));
  const reset = () => page.goto(base);
  const capture = async (name) => {
    await page.locator("#toast").evaluate((node) => { node.hidden = true; });
    await page.screenshot({ path: join(root, "evidence", `${name}.png`), fullPage: true });
  };
  await mkdir(join(root, "evidence"), { recursive: true });
  await reset();
  await check("Portal viewport fits all transport and footer controls", async () => {
    assert.deepEqual(await page.evaluate(() => [document.documentElement.scrollWidth, document.documentElement.scrollHeight]), [2160, 1440]);
    assert(await page.locator("#output").isVisible());
    assert.equal(await text("playback-status"), "Preview paused");
    assert.equal(await page.locator("audio,video").count(), 0);
    assert.equal(await page.locator("#queue-list .track-row").count(), 6);
    assert(await page.locator("#main-cover").evaluate((img) => img.complete && img.naturalWidth === 900));
  });
  await capture("portal-full");
  await check("Simulated play advances only the visible fixture timeline", async () => {
    const before = await page.locator("#seek").inputValue();
    await click("play"); await page.waitForTimeout(1150);
    assert.equal(await text("playback-status"), "Preview playing");
    assert(Number(await page.locator("#seek").inputValue()) > Number(before));
    await click("play"); assert.equal(await text("playback-status"), "Preview paused");
  });
  await check("Seek, previous restart and next select coherent sample tracks", async () => {
    await setRange("seek", 125); assert.equal(await text("elapsed"), "2:05");
    await click("previous"); assert.equal(await text("elapsed"), "0:00");
    await click("next"); assert.equal(await text("track-title"), "Blue distance");
    await click("previous"); assert.equal(await text("track-title"), "After the sun");
  });
  await check("Queue selection changes artwork and stays honestly paused", async () => {
    await page.locator('#queue-list [data-track="orbit"]').click();
    assert.equal(await text("track-title"), "Small hours");
    assert.equal(await text("playback-status"), "Preview paused");
    assert.match(await page.locator("#main-cover").getAttribute("src"), /orbit/);
  });
  await check("Compact and full player share state and controls", async () => {
    await click("compact-toggle"); assert(await page.locator("#compact-view").isVisible());
    assert.equal(await text("compact-title"), "Small hours"); await capture("portal-compact");
    await click("compact-play"); await click("expand");
    assert.equal(await text("playback-status"), "Preview playing");
    await click("play");
  });
  await check("Library search filters tracks and handles no results", async () => {
    await page.locator('.main-nav [data-view="library"]').click();
    await capture("portal-library");
    await page.locator("#search").fill("North Atlantic");
    assert.equal(await page.locator("#library-list .track-row").count(), 2);
    await page.locator("#search").fill("nothingmatches"); assert.match(await text("library-list"), /No matching tracks/);
    await page.locator("#search").fill("");
    await page.locator('#library-list [data-track="rose"]').click();
    assert.equal(await text("track-title"), "Rosewood");
  });
  await check("Playlist detail replaces only the fixture queue", async () => {
    await page.locator('.main-nav [data-view="playlists"]').click();
    await page.locator('[data-playlist="coast"]').click(); await capture("portal-playlists");
    await page.locator('[data-use-playlist="coast"]').click();
    assert.equal(await text("track-title"), "Waterline");
    assert.equal(await page.locator("#queue-list .track-row").count(), 5);
    assert.equal(await text("playback-status"), "Preview paused");
  });
  await check("Speaker selection, compatible grouping and volume work locally", async () => {
    await click("output"); await page.locator('[data-room="kitchen"]').click();
    assert.equal(await text("output-label"), "Kitchen");
    await page.locator('[data-group="living"]').click();
    assert.match(await page.locator('[data-group="living"]').textContent(), /Remove/);
    await setRange("volume-kitchen", 67);
    assert.equal(await page.locator("#volume-kitchen").inputValue(), "67");
    assert(await page.locator('[data-room="study"]').isDisabled());
    await capture("portal-speakers");
  });
  await check("Local session gates unsupported library, queue and speaker controls", async () => {
    await scenario("local");
    assert(await page.locator('.main-nav [data-view="library"]').isDisabled());
    assert(await page.locator("#output").isDisabled());
    await click("queue-tab"); assert.match(await text("queue-list"), /Queue unavailable/);
    assert(!(await page.locator("#play").isDisabled()));
  });
  await check("Live station and unknown duration never invent a seek timeline", async () => {
    await scenario("live"); assert(await page.locator("#next").isDisabled());
    assert(await page.locator("#seek-block").isHidden()); assert.match(await text("timeline-message"), /Live station/);
    await scenario("unknown"); assert(await page.locator("#seek-block").isHidden()); assert.match(await text("timeline-message"), /duration unavailable/);
  });
  await check("Empty queue has a usable playlist recovery path", async () => {
    await scenario("emptyqueue"); assert.match(await text("queue-list"), /A little quiet/);
    await page.locator('#queue-list [data-view="playlists"]').click();
    await page.locator('[data-playlist="quiet"]').click(); await page.locator('[data-use-playlist="quiet"]').click();
    assert.equal(await text("queue-count"), "6");
  });
  await check("Offline preserves last known artwork with all transport disabled", async () => {
    await scenario("offline"); assert.match(await text("notice"), /offline/);
    for (const id of ["play", "next", "previous", "seek"]) assert(await page.locator(`#${id}`).isDisabled());
    await capture("portal-offline");
  });
  await check("Setup, authorization, connecting and incompatibility are actionable states", async () => {
    for (const value of ["notconfigured", "connecting", "auth", "incompatible"]) {
      await scenario(value); assert(await page.locator("#empty-view").isVisible());
      await page.locator("#empty-view [data-settings]").click();
      assert(await page.locator("#settings-dialog").isVisible()); await click("dialog-close");
    }
    await scenario("notconfigured"); await capture("portal-not-configured");
    await page.locator("#empty-view [data-settings]").click(); await click("use-fixtures");
    assert(await page.locator("#now-view").isVisible());
  });
  await check("Interruption restricts starting and seeking while keeping supported pause and volume", async () => {
    await scenario("interrupted"); assert(await page.locator("#next").isDisabled()); assert(await page.locator("#seek").isDisabled());
    assert(await page.locator("#queue-list .track-row").first().isDisabled());
    assert(!(await page.locator("#play").isDisabled())); await click("play"); assert(await page.locator("#play").isDisabled());
    await click("speakers-tab"); assert(!(await page.locator("#volume-living").isDisabled()));
  });
  await check("Command failure never announces successful playback or seeking", async () => {
    await scenario("failed"); const title = await text("track-title"); const position = await page.locator("#seek").inputValue();
    await click("play"); assert.equal(await text("playback-status"), "Preview paused");
    await click("next"); assert.equal(await text("track-title"), title);
    await setRange("seek", 210); assert.equal(await page.locator("#seek").inputValue(), position);
    await setRange("volume-living", 90); assert.equal(await page.locator("#volume-living").inputValue(), "42");
  });
  await check("Keyboard tab navigation and reduced motion are respected", async () => {
    await reset(); await page.locator("#queue-tab").focus(); await page.keyboard.press("ArrowRight");
    assert.equal(await page.locator("#speakers-tab").getAttribute("aria-selected"), "true");
    await page.emulateMedia({ reducedMotion: "reduce" });
    assert.equal(await page.locator("#next").evaluate((node) => getComputedStyle(node).transitionDuration), "0s");
  });
  await check("Night clock matches Portal Canvas proportions and warm ink", async () => {
    await page.goto(`${base}/#night-clock`);
    assert(await page.locator("#night-clock-view").isVisible());
    const metrics = await page.locator("#night-canvas").evaluate((node) => ({
      clock: node.dataset.clockFont, date: node.dataset.dateFont, gap: Number(node.dataset.gap), ink: node.dataset.ink,
      black: [...node.getContext("2d").getImageData(0, 0, 1, 1).data],
    }));
    assert.match(metrics.clock, /403\.2/); assert.match(metrics.date, /59\.04/);
    assert.equal(metrics.gap, 61.919999999999995); assert.equal(metrics.ink, "185,159,135");
    assert.deepEqual(metrics.black, [0, 0, 0, 255]); await capture("night-clock-warm");
    await page.locator("#night-ink").selectOption("red"); await page.locator("#night-format").selectOption("24");
    assert.equal(await page.locator("#night-canvas").getAttribute("data-ink"), "166,85,72");
    assert.match(await page.locator("#night-canvas").getAttribute("aria-label"), /22:08/);
    await page.locator("#night-presence").selectOption("present");
    assert.match(await text("night-fixture-note"), /Sample presence: present.*No Portal sensor/);
    await capture("night-clock-red"); await click("night-back");
  });
  await check("Narrow touch viewport has no horizontal overflow or undersized controls", async () => {
    await page.setViewportSize({ width: 390, height: 844 }); await reset();
    assert.equal(await page.evaluate(() => document.documentElement.scrollWidth), 390);
    const heights = await page.locator("#play,#previous,#next,#compact-toggle,.main-nav button,#scenario").evaluateAll((nodes) => nodes.map((node) => node.getBoundingClientRect().height));
    assert(heights.every((height) => height >= 48));
    await capture("mobile-player");
    await page.locator('.main-nav [data-view="night-clock"]').click();
    assert.equal(await page.evaluate(() => document.documentElement.scrollWidth), 390);
    await capture("mobile-night-clock");
  });
  await check("Tablet and short landscape layouts remain usable without horizontal overflow", async () => {
    for (const [width, height] of [[1280, 800], [896, 414], [1440, 960]]) {
      await page.setViewportSize({ width, height }); await reset();
      assert.equal(await page.evaluate(() => document.documentElement.scrollWidth), width);
      assert(await page.locator(".artwork-frame").evaluate((node) => node.getBoundingClientRect().width >= 288));
      assert(await page.locator("#play").isVisible());
    }
  });
  await check("Standalone file opens and works without a static server", async () => {
    const standalone = await browser.newPage({ viewport: { width: 2160, height: 1440 } });
    standalone.on("pageerror", (error) => errors.push(error.message));
    await standalone.goto(pathToFileURL(join(root, "index.html")).href);
    await standalone.locator("#play").click();
    assert.equal(await standalone.locator("#playback-status").textContent(), "Preview playing");
    await standalone.close();
  });
  await check("No runtime errors or external network requests", async () => { assert.deepEqual(errors, []); assert.deepEqual(external, []); });
  await writeFile(join(root, "evidence", "checks.json"), JSON.stringify({ viewports: ["2160×1440", "390×844", "1280×800", "896×414", "1440×960"], passed: results.length, checks: results, runtimeErrors: errors, externalRequests: external }, null, 2) + "\n");
  console.log(`\n${results.length} checks passed. Evidence saved inside the mock folder.`);
} finally {
  await browser?.close();
  await new Promise((done) => server.close(done));
}
