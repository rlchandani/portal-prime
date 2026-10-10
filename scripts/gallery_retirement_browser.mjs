/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */
// NODE_PATH must point to existing packages containing playwright and sharp.
// Launches a new headless profile. All image bytes and page content are generated.
import assert from 'node:assert/strict';
import { createRequire } from 'node:module';
import { readFileSync, writeFileSync } from 'node:fs';
import { execFileSync } from 'node:child_process';
import { fileURLToPath } from 'node:url';
import { createHash } from 'node:crypto';

const require = createRequire(import.meta.url);
const { chromium } = require('playwright');
const sharp = require('sharp');
const root = fileURLToPath(new URL('../', import.meta.url));
const relative = 'app/src/main/assets/screensaver/';
const base = '227d2ffe0fb8baefebbf1670686731827927966c';
const red = process.argv.includes('--red');
const out = process.argv.find(a => a.startsWith('--out='))?.slice(6);
const baseline = file => execFileSync('git', ['show', `${base}:${relative}${file}`], { cwd: root, encoding: 'utf8' });
const current = file => readFileSync(root + relative + file, 'utf8');
const hash = bytes => createHash('sha256').update(bytes).digest('hex');
const css = baseline('styles.css');
assert.equal(current('styles.css'), css, 'This candidate must not change CSS');
const common = ['lib-engine.js', 'modes.js', 'scheduler.js'].map(file => {
  const source = baseline(file);
  assert.equal(current(file), source, `${file} must remain unchanged`);
  return source;
});
const sources = { baseline: baseline('mode-impls.js'), candidate: red ? baseline('mode-impls.js') : current('mode-impls.js') };

async function fixtures(format) {
  const photos = [], hashes = [];
  for (let i = 0; i < 14; i++) {
    const [w, h] = i % 3 === 0 ? [96, 128] : i % 3 === 1 ? [160, 96] : [192, 80];
    const raw = Buffer.alloc(w * h * 4);
    for (let y = 0; y < h; y++) for (let x = 0; x < w; x++) {
      const n = (y * w + x) * 4;
      raw[n] = (x * 3 + i * 29) % 256;
      raw[n + 1] = (y * 5 + i * 43) % 256;
      raw[n + 2] = ((x + y) * 2 + i * 17) % 256;
      raw[n + 3] = x < w / 3 ? 0 : x < w * 2 / 3 ? 128 : 255;
    }
    let image = sharp(raw, { raw: { width: w, height: h, channels: 4 } });
    if (format === 'jpeg') image = image.flatten({ background: '#204060' }).jpeg({ quality: 95 });
    else if (format === 'webp') image = image.webp({ lossless: true });
    else image = image.png();
    const bytes = await image.toBuffer();
    hashes.push(hash(bytes));
    photos.push({ seed: i, w, h, url: `data:image/${format};base64,${bytes.toString('base64')}`,
      ...(i % 2 === 0 ? { fx: .7, fy: .25, pw: .8 } : {}) });
  }
  return { photos, hashes };
}

async function pixels(buffer) {
  return sharp(buffer).ensureAlpha().raw().toBuffer({ resolveWithObject: true });
}
async function difference(a, b) {
  const A = await pixels(a), B = await pixels(b);
  assert.deepEqual(A.info, B.info);
  let changedPixels = 0, maxChannelDelta = 0;
  for (let n = 0; n < A.data.length; n += 4) {
    let changed = false;
    for (let c = 0; c < 4; c++) {
      const delta = Math.abs(A.data[n + c] - B.data[n + c]);
      maxChannelDelta = Math.max(maxChannelDelta, delta); changed ||= delta !== 0;
    }
    if (changed) changedPixels++;
  }
  return { changedPixels, maxChannelDelta, baselinePixelSha256: hash(A.data), candidatePixelSha256: hash(B.data) };
}

async function render(browser, source, photos) {
  const context = await browser.newContext({ viewport: { width: 800, height: 600 }, deviceScaleFactor: 1,
    colorScheme: 'dark', reducedMotion: 'no-preference' });
  await context.setOffline(true);
  const page = await context.newPage();
  const errors = [];
  page.on('pageerror', error => errors.push(error.message));
  const shots = new Map(), states = {};
  try {
    await page.clock.install({ time: 0 });
    await page.setContent(`<style>${css}</style><div id="stage" style="background:#204060"></div>`);
    await page.evaluate(photos => {
      window.PHOTOS = photos; window.aspect = p => p.w / p.h;
      window.shuffled = xs => xs.slice(); window.photoUrl = p => p.url;
      Math.random = () => .5;
      window.fixture = { ready: 0, nativeEnds: 0 };
    }, photos);
    for (const content of common.slice(0, 2)) await page.addScriptTag({ content });
    await page.addScriptTag({ content: source });
    await page.addScriptTag({ content: common[2] });
    await page.evaluate(() => {
      let choice = 0;
      SS.pickNextMode = () => choice++ === 0 ? 'galleryWall' : 'cinema';
      fixture.engine = SS.startEngine({ stage: document.querySelector('#stage'), mode: 'shuffle',
        enabledModes: ['galleryWall', 'cinema'], intervalSec: 5, onReady: () => fixture.ready++ });
    });
    // Wait for actual Image.decode while keeping JS timers and admission RAF controlled.
    for (let i = 0; i < 50; i++) {
      if (await page.evaluate(() => !!document.querySelector('.ss-gcell .ss-img'))) break;
      await new Promise(resolve => setTimeout(resolve, 10));
    }
    assert.ok(await page.evaluate(() => !!document.querySelector('.ss-gcell .ss-img')), 'Decoded initial gallery was not published');
    await page.evaluate(() => getComputedStyle(document.querySelector('.ss-beat')).opacity);
    await page.clock.runFor(32);
    assert.equal(await page.evaluate(() => fixture.ready), 1);
    await page.evaluate(() => {
      fixture.gallery = document.querySelector('.ss-beat');
      for (const a of fixture.gallery.getAnimations({ subtree: true })) {
        if (a instanceof CSSAnimation) { a.pause(); a.currentTime = 5000; }
        else a.finish();
      }
      fixture.pinMotion = () => {
        for (const a of document.querySelector('#stage').getAnimations({ subtree: true })) {
          if (a instanceof CSSAnimation) { a.pause(); a.currentTime = 5000; }
        }
      };
    });
    async function snapshot(name) {
      await page.evaluate(() => fixture.pinMotion());
      // Playwright's JS clock does not advance the renderer/compositor clock.
      // All effects are pinned; allow their sampled state to reach the renderer.
      await new Promise(resolve => setTimeout(resolve, 50));
      // Flush admission/raster state once before recording the same pinned frame.
      await page.screenshot({ animations: 'allow' });
      shots.set(name, await page.screenshot({ animations: 'allow' }));
    }
    await snapshot('rest');
    await page.evaluate(() => {
      // Choose a visible cell independently of Gallery Wall's height-fill overscan.
      const cells = [...document.querySelectorAll('.ss-gcell')];
      const areas = cells.map(cell => {
        const r = cell.getBoundingClientRect();
        return Math.max(0, Math.min(800, r.right) - Math.max(0, r.left)) *
          Math.max(0, Math.min(600, r.bottom) - Math.max(0, r.top));
      });
      const index = areas.indexOf(Math.max(...areas));
      if (areas[index] === 0) throw Error('Generated gallery has no visible replacement cell');
      let first = true;
      Math.random = () => {
        if (first) { first = false; return (index + .25) / cells.length; }
        return .5;
      };
    });
    await page.clock.runFor(5000 - await page.evaluate(() => Date.now()));
    for (let i = 0; i < 50; i++) {
      if (await page.evaluate(() => [...document.querySelectorAll('.ss-gcell')].some(c => c.children.length === 2))) break;
      await new Promise(resolve => setTimeout(resolve, 10));
    }
    await page.evaluate(() => {
      fixture.cell = [...document.querySelectorAll('.ss-gcell')].find(c => c.children.length === 2);
      if (!fixture.cell) throw Error('No actual Gallery Wall swap');
      fixture.old = fixture.cell.firstElementChild;
      fixture.latest = fixture.cell.lastElementChild;
      getComputedStyle(fixture.old).opacity;
    });
    await page.clock.runFor(16);
    await page.evaluate(() => {
      getComputedStyle(fixture.old).opacity;
      fixture.fade = fixture.old.getAnimations().find(a => a instanceof CSSTransition && a.transitionProperty === 'opacity');
      if (!fixture.fade) throw Error('No real opacity transition');
      fixture.fade.pause(); fixture.fade.currentTime = 0;
      fixture.old.addEventListener('transitionend', event => {
        if (event.target === fixture.old && event.propertyName === 'opacity' && event.isTrusted) {
          fixture.nativeEnds++;
          // Capture runs before the production at-target handler removes the node.
          fixture.endOpacity = Number(getComputedStyle(fixture.old).opacity);
          fixture.connectedAtEnd = fixture.old.isConnected;
        }
      }, { capture: true });
    });
    await snapshot('fade-start');
    await page.evaluate(() => { fixture.fade.currentTime = 350; });
    await snapshot('fade-350');
    await page.evaluate(() => { fixture.fade.currentTime = 550; });
    await snapshot('fade-mid');
    // Exercise the real scheduler's transition pause/resume API with positive opacity.
    states.globalPause = await page.evaluate(() => {
      fixture.fade.play(); fixture.engine.pause();
      const state = { transitionState: fixture.fade.playState, opacity: Number(getComputedStyle(fixture.old).opacity),
        connected: fixture.old.isConnected };
      fixture.engine.resume(); state.resumedState = fixture.fade.playState;
      fixture.fade.pause(); fixture.fade.currentTime = 700;
      return state;
    });
    assert.equal(states.globalPause.transitionState, 'paused');
    assert.equal(states.globalPause.resumedState, 'running');
    assert.ok(states.globalPause.opacity > 0 && states.globalPause.connected);
    await snapshot('fade-700');
    states.geometry = await page.evaluate(() => {
      const rect = fixture.cell.getBoundingClientRect(), style = getComputedStyle(fixture.cell);
      return { x: rect.x, y: rect.y, width: rect.width, height: rect.height, shadow: style.boxShadow,
        radius: style.borderRadius, focus: getComputedStyle(fixture.latest).backgroundPosition };
    });
    assert.ok(states.geometry.x < 800 && states.geometry.x + states.geometry.width > 0);
    assert.ok(states.geometry.y < 600 && states.geometry.y + states.geometry.height > 0);
    assert.equal(states.geometry.focus, '70% 25%', 'The replacement must exercise the generated face focus');
    await page.evaluate(() => {
      const decode = window.preloadDecoded;
      const gate = new Promise(resolve => { fixture.releaseIncoming = resolve; });
      window.preloadDecoded = async (...args) => { const result = await decode(...args); await gate; return result; };
      fixture.engine.next();
      fixture.incoming = [...document.querySelectorAll('.ss-beat')].at(-1);
      // Make preparation's initial hidden style observable before releasing decode.
      getComputedStyle(fixture.incoming).opacity;
      for (const layer of fixture.incoming.querySelectorAll('.ss-layer')) getComputedStyle(layer).opacity;
      fixture.releaseIncoming();
    });
    for (let i = 0; i < 50; i++) {
      if (await page.evaluate(() => !!document.querySelector('.ss-scope .ss-img[style*="background-image"]'))) break;
      await new Promise(resolve => setTimeout(resolve, 10));
    }
    await page.evaluate(() => {
      fixture.incoming = [...document.querySelectorAll('.ss-beat')].at(-1);
      getComputedStyle(fixture.incoming).opacity;
    });
    await page.clock.runFor(32);
    assert.equal(await page.evaluate(() => fixture.ready), 2);
    await page.evaluate(() => {
      getComputedStyle(fixture.incoming).opacity;
      fixture.dissolve = fixture.incoming.getAnimations().find(a => a instanceof CSSTransition && a.transitionProperty === 'opacity');
      if (!fixture.dissolve) throw Error('No actual scheduler dissolve');
      fixture.innerFades = fixture.incoming.getAnimations({ subtree: true }).filter(a =>
        a instanceof CSSTransition && a.effect.target !== fixture.incoming);
      fixture.seekIncoming = time => {
        fixture.dissolve.pause(); fixture.dissolve.currentTime = time;
        for (const a of fixture.innerFades) { a.pause(); a.currentTime = time + 32; }
      };
      fixture.seekIncoming(0);
      fixture.fade.currentTime = 700;
    });
    await snapshot('promotion-start');
    for (const [name, fadeTime, dissolveTime] of [['dissolve-200', 900, 200], ['just-before-zero', 1099, 399]]) {
      states[name] = await page.evaluate(([fadeTime, dissolveTime]) => {
        fixture.fade.currentTime = fadeTime; fixture.seekIncoming(dissolveTime);
        fixture.old.dispatchEvent(new TransitionEvent('transitionend', { propertyName: 'transform', bubbles: true }));
        fixture.latest.dispatchEvent(new TransitionEvent('transitionend', { propertyName: 'opacity', bubbles: true }));
        fixture.old.dispatchEvent(new TransitionEvent('transitionend', { propertyName: 'opacity' }));
        return { opacity: Number(getComputedStyle(fixture.old).opacity), connected: fixture.old.isConnected };
      }, [fadeTime, dissolveTime]);
      assert.ok(states[name].opacity > 0 && states[name].connected, 'No visible image may retire');
      await snapshot(name);
    }
    await page.evaluate(() => { fixture.seekIncoming(400); fixture.fade.finish(); });
    // Let the renderer dispatch its native CSS transitionend; no fabricated zero event.
    for (let i = 0; i < 5 && !await page.evaluate(() => fixture.nativeEnds); i++) {
      await page.clock.runFor(16);
      await page.screenshot({ animations: 'allow' });
    }
    states.zero = await page.evaluate(() => ({ opacity: fixture.endOpacity, connectedAtEnd: fixture.connectedAtEnd,
      connected: fixture.old.isConnected, nativeEnds: fixture.nativeEnds,
      handlerReleased: fixture.old.ontransitionend == null, currentConnected: fixture.latest.isConnected }));
    assert.ok(states.zero.nativeEnds > 0, 'The real browser must deliver a native opacity completion event');
    assert.equal(states.zero.opacity, 0, 'Native completion must have computed zero before production retirement');
    assert.equal(states.zero.connectedAtEnd, true);
    await snapshot('zero-alpha');
    await page.evaluate(() => { fixture.seekIncoming(800); });
    await snapshot('dissolve-mid');
    await page.evaluate(() => { fixture.seekIncoming(1600); fixture.dissolve.finish(); });
    await snapshot('dissolve-complete');
    await page.clock.runFor(1800);
    await page.evaluate(() => { for (const a of fixture.innerFades) a.finish(); });
    await snapshot('outgoing-disposed');
    states.disposed = await page.evaluate(() => {
      fixture.engine.stop(); fixture.engine.stop();
      return { children: document.querySelector('#stage').children.length,
        oldHandlerReleased: fixture.old.ontransitionend == null };
    });
    assert.equal(states.disposed.children, 0);
    assert.deepEqual(errors, []);
    return { shots, states };
  } finally { await context.close(); }
}

const report = { base, red, tolerance: { maxChannelDelta: 0, changedPixels: 0 },
  sourceSha256: Object.fromEntries(Object.entries(sources).map(([k, v]) => [k, hash(v)])),
  cssSha256: hash(css), browserArgs: ['--disable-gpu', '--disable-threaded-animation'], cases: [], limitations: [
    'Synthetic Chrome rendering and lifecycle evidence, not Portal WebView or GPU performance evidence.',
    'Software rendering and main-thread animation sampling; CSS phases and transition times are explicitly sampled.',
    'Generated fixtures contain no household data. This does not certify Portal raster/compositor equivalence.',
  ] };
let browser, failed = false;
try {
  browser = await chromium.launch({ headless: true, channel: process.env.GALLERY_BROWSER_CHANNEL || 'chrome',
    args: report.browserArgs });
  report.browser = await browser.version();
  for (const format of ['jpeg', 'png', 'webp']) {
    const generated = await fixtures(format);
    const A = await render(browser, sources.baseline, generated.photos);
    const B = await render(browser, sources.candidate, generated.photos);
    const comparisons = [];
    for (const [phase, bytes] of A.shots) comparisons.push({ phase, ...await difference(bytes, B.shots.get(phase)) });
    const pixelsEqual = comparisons.every(c => c.changedPixels === 0 && c.maxChannelDelta === 0);
    const geometryEqual = JSON.stringify(A.states.geometry) === JSON.stringify(B.states.geometry);
    const retired = !B.states.zero.connected && B.states.zero.handlerReleased && B.states.zero.currentConnected;
    const clean = B.states.disposed.oldHandlerReleased;
    const passed = pixelsEqual && geometryEqual && retired && clean;
    report.cases.push({ format, generatedImageSha256: generated.hashes, baseline: A.states, candidate: B.states,
      comparisons, pixelsEqual, geometryEqual, retired, clean, passed });
    console.log(`${format}: pixels=${pixelsEqual} geometry=${geometryEqual} zeroRetirement=${retired} cleanup=${clean}`);
    failed ||= !passed;
  }
} catch (error) {
  report.error = error.stack; failed = true; console.error(error.stack);
} finally {
  if (browser) await browser.close();
  report.passed = !failed;
  if (out) writeFileSync(out, JSON.stringify(report, null, 2) + '\n');
}
process.exitCode = failed ? 1 : 0;
