/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */
// NODE_PATH=~/.cache/codex-runtimes/codex-primary-runtime/dependencies/node/node_modules
// Fresh offline headless profile; actual bundled mode, CSS, decoder and scheduler.
// Only generated fixture hashes and raw pixel comparisons are saved (no screenshots).
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
const base = 'c53ff61a935b777731a70d0b06875ecf24e46bf4';
const executablePath = '/Applications/Google Chrome.app/Contents/MacOS/Google Chrome';
const red = process.argv.includes('--red');
const out = process.argv.find(a => a.startsWith('--out='))?.slice(6);
const baseline = file => execFileSync('git', ['show', `${base}:${relative}${file}`], { cwd: root, encoding: 'utf8' });
const current = file => readFileSync(root + relative + file, 'utf8');
const hash = bytes => createHash('sha256').update(bytes).digest('hex');
const css = baseline('styles.css');
assert.equal(current('styles.css'), css, 'Probe requires unchanged bundled CSS');
const common = ['lib-engine.js', 'modes.js', 'scheduler.js'].map(file => {
  const content = baseline(file); assert.equal(current(file), content, `${file} must remain unchanged`); return content;
});
const sources = { baseline: baseline('mode-impls.js'), candidate: red ? baseline('mode-impls.js') : current('mode-impls.js') };
const wait = ms => new Promise(resolve => setTimeout(resolve, ms));

async function fixtures(format) {
  const photos = [], hashes = [];
  for (let i = 0; i < 4; i++) {
    const [w, h] = i % 2 ? [96, 144] : [160, 96];
    const raw = Buffer.alloc(w * h * 4);
    for (let y = 0; y < h; y++) for (let x = 0; x < w; x++) {
      const n = (y * w + x) * 4;
      raw[n] = (x * 3 + i * 29) % 256; raw[n + 1] = (y * 5 + i * 43) % 256;
      raw[n + 2] = ((x + y) * 2 + i * 17) % 256;
      raw[n + 3] = x < w / 3 ? 0 : x < w * 2 / 3 ? 128 : 255;
    }
    let image = sharp(raw, { raw: { width: w, height: h, channels: 4 } });
    if (format === 'jpeg') image = image.flatten({ background: '#204060' }).jpeg({ quality: 95 });
    else if (format === 'webp') image = image.webp({ lossless: true });
    else image = image.png();
    const bytes = await image.toBuffer(); hashes.push(hash(bytes));
    photos.push({ seed: i, w, h, url: `data:image/${format};base64,${bytes.toString('base64')}`,
      ...(i === 0 || i === 3 ? { fx: .7, fy: .25, pw: .8 } : {}) });
  }
  return { photos, hashes };
}
async function difference(a, b) {
  const A = await sharp(a).ensureAlpha().raw().toBuffer({ resolveWithObject: true });
  const B = await sharp(b).ensureAlpha().raw().toBuffer({ resolveWithObject: true });
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
  return { changedPixels, maxChannelDelta, baselinePixelSha256: hash(A.data), comparedPixelSha256: hash(B.data) };
}

async function render(browser, source, photos) {
  const context = await browser.newContext({ viewport: { width: 800, height: 600 }, deviceScaleFactor: 1,
    colorScheme: 'dark', reducedMotion: 'no-preference' });
  await context.setOffline(true);
  await context.route('**/*', route => route.abort());
  const page = await context.newPage();
  const errors = [], requests = []; page.on('pageerror', e => errors.push(e.message));
  page.on('request', r => { if (!r.url().startsWith('data:')) requests.push(r.url()); });
  const shots = new Map(), states = { swaps: [] };
  try {
    await page.clock.install({ time: 0 });
    await page.setContent(`<style>${css}</style><div id="stage" style="background:#204060"></div>`);
    await page.evaluate(photos => {
      window.PHOTOS = photos; window.aspect = p => p.w / p.h;
      window.shuffled = xs => xs.slice(); window.photoUrl = p => p.url; Math.random = () => .7;
      window.fixture = { ready: 0, nativeEnds: 0 };
    }, photos);
    for (const content of common.slice(0, 2)) await page.addScriptTag({ content });
    await page.addScriptTag({ content: source }); await page.addScriptTag({ content: common[2] });
    await page.evaluate(() => {
      fixture.engine = SS.startEngine({ stage: document.querySelector('#stage'), mode: 'kenBurns',
        intervalSec: 5, onReady: () => fixture.ready++ });
      fixture.layers = () => [...document.querySelectorAll('.ss-layer')];
      fixture.resources = root => {
        const fill = root.querySelector('.ss-fill'), hero = root.querySelector('.ss-hero');
        const style = getComputedStyle(hero), rect = hero.getBoundingClientRect();
        return { opacity: getComputedStyle(root).opacity, on: root.classList.contains('on'),
          background: getComputedStyle(fill).backgroundImage === 'none' ? 'none' : 'image',
          heroBackground: style.backgroundImage === 'none' ? 'none' : 'image',
          motion: hero.classList.contains('ss-motion'), animation: style.animationName,
          hint: style.willChange, handlerReleased: root.ontransitionend == null,
          geometry: { x: rect.x, y: rect.y, width: rect.width, height: rect.height,
            fillSize: getComputedStyle(fill).backgroundSize, heroSize: style.backgroundSize,
            focus: style.backgroundPosition, transform: style.transform,
            vars: ['--s0', '--s1', '--x0', '--y0', '--x1', '--y1'].map(k => hero.style.getPropertyValue(k)) } };
      };
      fixture.pinMotion = () => {
        for (const a of document.querySelector('#stage').getAnimations({ subtree: true })) {
          if (a instanceof CSSAnimation) { a.pause(); a.currentTime = 5000; }
        }
      };
    });
    async function until(fn, label) {
      for (let i = 0; i < 100; i++) { if (await page.evaluate(fn)) return; await wait(10); }
      throw Error(label);
    }
    await until(() => fixture.layers().some(l => l.classList.contains('on')), 'Initial real decode never published');
    await page.evaluate(() => {
      getComputedStyle(document.querySelector('.ss-beat')).opacity;
      fixture.layers().forEach(l => getComputedStyle(l).opacity);
    });
    await page.clock.runFor(32); assert.equal(await page.evaluate(() => fixture.ready), 1);
    await page.evaluate(() => {
      for (const a of document.querySelector('#stage').getAnimations({ subtree: true })) {
        if (a instanceof CSSTransition) a.finish();
      }
      fixture.pinMotion();
    });
    async function snapshot(name) {
      await page.evaluate(() => fixture.pinMotion());
      await wait(50); // Fake JS clock does not advance renderer; phases are explicitly pinned.
      await page.screenshot({ animations: 'allow' });
      shots.set(name, await page.screenshot({ animations: 'allow' }));
    }
    await snapshot('initial-rest');
    states.initial = await page.evaluate(() => fixture.resources(document.querySelector('.ss-layer.on')));
    for (let swap = 1; swap <= 3; swap++) {
      await page.evaluate(() => {
        fixture.old = document.querySelector('.ss-layer.on');
        fixture.latest = fixture.layers().find(l => l !== fixture.old);
        fixture.oldImage = fixture.old.querySelector('.ss-hero').style.backgroundImage;
        fixture.layers().forEach(l => getComputedStyle(l).opacity);
      });
      await page.clock.runFor(swap * 5000 - await page.evaluate(() => Date.now()));
      await until(() => fixture.latest.classList.contains('on'), 'Real decoded swap never published');
      await page.evaluate(() => {
        getComputedStyle(fixture.old).opacity; getComputedStyle(fixture.latest).opacity;
        fixture.fade = fixture.old.getAnimations().find(a => a instanceof CSSTransition && a.transitionProperty === 'opacity');
        fixture.incoming = fixture.latest.getAnimations().find(a => a instanceof CSSTransition && a.transitionProperty === 'opacity');
        if (!fixture.fade || !fixture.incoming) throw Error('Expected two actual 2.2s CSS opacity transitions');
        fixture.seek = time => {
          fixture.fade.pause(); fixture.fade.currentTime = time;
          fixture.incoming.pause(); fixture.incoming.currentTime = time;
          fixture.pinMotion();
        };
        fixture.seek(0); fixture.endState = null;
        fixture.captureEnd = event => {
          if (event.target === fixture.old && event.propertyName === 'opacity' && event.isTrusted) {
            fixture.nativeEnds++; fixture.endState = fixture.resources(fixture.old);
          }
        };
        fixture.old.addEventListener('transitionend', fixture.captureEnd, { capture: true });
      });
      const state = { swap, phases: {} };
      for (const time of [0, 550, 1100, 1650, 2199]) {
        const phase = `swap-${swap}-fade-${time}`;
        state.phases[time] = await page.evaluate(time => {
          fixture.seek(time);
          fixture.old.querySelector('.ss-hero').dispatchEvent(new TransitionEvent('transitionend', { propertyName: 'opacity', bubbles: true }));
          fixture.old.dispatchEvent(new TransitionEvent('transitionend', { propertyName: 'transform' }));
          fixture.old.dispatchEvent(new TransitionEvent('transitionend', { propertyName: 'opacity' }));
          return { old: fixture.resources(fixture.old), live: fixture.resources(fixture.latest),
            fadeDuration: fixture.fade.effect.getTiming().duration };
        }, time);
        assert.equal(state.phases[time].fadeDuration, 2200, 'Preserve real CSS fade duration');
        assert.ok(Number(state.phases[time].old.opacity) > 0);
        assert.equal(state.phases[time].old.heroBackground, 'image', 'Visible outgoing slot may not retire');
        if (time === 1100) {
          state.pause = await page.evaluate(() => {
            fixture.fade.play(); fixture.incoming.play(); fixture.engine.pause();
            const value = { old: fixture.resources(fixture.old), transitionState: fixture.fade.playState };
            fixture.engine.resume(); value.resumedState = fixture.fade.playState;
            fixture.seek(1100); return value;
          });
          assert.equal(state.pause.transitionState, 'paused'); assert.equal(state.pause.resumedState, 'running');
          assert.ok(Number(state.pause.old.opacity) > 0); assert.equal(state.pause.old.heroBackground, 'image');
        }
        await snapshot(phase);
      }
      await page.evaluate(() => { fixture.fade.finish(); fixture.incoming.finish(); });
      await until(() => !!fixture.endState, 'Native opacity completion event missing');
      state.zero = await page.evaluate(() => ({ captured: fixture.endState, old: fixture.resources(fixture.old),
        live: fixture.resources(fixture.latest), nativeEnds: fixture.nativeEnds }));
      assert.equal(Number(state.zero.captured.opacity), 0);
      assert.equal(state.zero.captured.heroBackground, 'image', 'Capture precedes production retirement');
      await snapshot(`swap-${swap}-zero`);
      // Also prove an owned fallback leaves no visual change after native completion.
      await page.clock.runFor(swap * 5000 + 2300 - await page.evaluate(() => Date.now()));
      await snapshot(`swap-${swap}-after-fallback`);
      state.after = await page.evaluate(() => ({ old: fixture.resources(fixture.old), live: fixture.resources(fixture.latest) }));
      await page.evaluate(() => fixture.old.removeEventListener('transitionend', fixture.captureEnd, { capture: true }));
      states.swaps.push(state);
    }
    // Actual scheduler preparation, promotion and outgoing freeze/disposal remain operational.
    await page.evaluate(() => {
      fixture.outgoingSlots = fixture.layers(); fixture.engine.next();
    });
    await until(() => document.querySelectorAll('.ss-beat').length === 2 &&
      [...document.querySelectorAll('.ss-beat')].at(-1).querySelector('.ss-layer.on'), 'Scheduler next did not decode');
    await page.clock.runFor(32);
    states.scheduler = await page.evaluate(() => {
      const scenes = [...document.querySelectorAll('.ss-beat')];
      const value = { ready: fixture.ready, outgoingFrozen: scenes[0].classList.contains('freeze'),
        incomingOn: scenes[1].classList.contains('on') };
      fixture.engine.pause(); value.paused = scenes.every(s => s.classList.contains('paused'));
      fixture.engine.resume();
      for (const a of document.querySelector('#stage').getAnimations({ subtree: true })) if (a instanceof CSSTransition) a.finish();
      return value;
    });
    assert.equal(states.scheduler.ready, 2); assert.ok(states.scheduler.outgoingFrozen && states.scheduler.incomingOn && states.scheduler.paused);
    await page.clock.runFor(1800);
    states.disposed = await page.evaluate(() => {
      const scenes = document.querySelectorAll('.ss-beat').length;
      const oldHandlersReleased = fixture.outgoingSlots.every(l => l.ontransitionend == null);
      const outgoingDetached = fixture.outgoingSlots.every(l => !l.isConnected);
      fixture.engine.stop(); fixture.engine.stop();
      return { scenes, oldHandlersReleased, outgoingDetached, children: document.querySelector('#stage').children.length };
    });
    assert.equal(states.disposed.scenes, 1); assert.equal(states.disposed.children, 0);
    assert.ok(states.disposed.oldHandlersReleased && states.disposed.outgoingDetached);
    assert.deepEqual(errors, []); assert.deepEqual(requests, []);
    return { shots, states };
  } finally { await context.close(); }
}

const report = { base, red, tolerance: { changedPixels: 0, maxChannelDelta: 0 },
  executablePath, browserArgs: ['--disable-gpu', '--disable-threaded-animation'],
  sourceSha256: Object.fromEntries(Object.entries(sources).map(([k, v]) => [k, hash(v)])),
  cssSha256: hash(css), commonSha256: common.map(hash), cases: [], limitations: [
    'Generated offline Chrome155 software-rendered pixels, not Portal WebView/GPU or performance benefit evidence.',
    'JS clock is controlled; actual unchanged CSS effects are phase-pinned through Web Animations APIs.',
    'Screenshots remain in memory only; output contains fixture and raw pixel hashes/counts, no personal data.',
    'Native completion event retirement and scheduler pause/resume are browser-tested; missing-event and malformed-style cases use controlled Node tests.',
  ] };
let browser, failed = false;
try {
  browser = await chromium.launch({ headless: true, executablePath, args: report.browserArgs });
  report.browser = await browser.version(); assert.match(report.browser, /^155\./, 'Use requested installed Chrome155');
  for (const format of ['jpeg', 'png', 'webp']) {
    const generated = await fixtures(format);
    const A = await render(browser, sources.baseline, generated.photos);
    const repeat = await render(browser, sources.baseline, generated.photos);
    const B = await render(browser, sources.candidate, generated.photos);
    const comparisons = [], repeatControls = [];
    for (const [phase, bytes] of A.shots) {
      repeatControls.push({ phase, ...await difference(bytes, repeat.shots.get(phase)) });
      comparisons.push({ phase, ...await difference(bytes, B.shots.get(phase)) });
    }
    const exact = list => list.every(c => c.changedPixels === 0 && c.maxChannelDelta === 0);
    const controlsStable = exact(repeatControls), pixelsEqual = exact(comparisons);
    const geometryEqual = A.states.swaps.every((s, i) => Object.keys(s.phases).every(phase =>
      JSON.stringify(s.phases[phase].old.geometry) === JSON.stringify(B.states.swaps[i].phases[phase].old.geometry) &&
      JSON.stringify(s.phases[phase].live.geometry) === JSON.stringify(B.states.swaps[i].phases[phase].live.geometry)));
    const isRetired = s => s.heroBackground === 'none' && s.background === 'none' && !s.motion &&
      s.animation === 'none' && s.hint === 'auto' && s.handlerReleased;
    const retired = B.states.swaps.every(s => isRetired(s.zero.old) && isRetired(s.after.old) && s.zero.live.heroBackground === 'image');
    const passed = controlsStable && pixelsEqual && geometryEqual && retired;
    report.cases.push({ format, generatedImageSha256: generated.hashes, baseline: A.states,
      baselineRepeat: repeat.states, candidate: B.states, repeatControls, comparisons, controlsStable, pixelsEqual, geometryEqual, retired, passed });
    console.log(`${format}: controls=${controlsStable} pixels=${pixelsEqual} geometry=${geometryEqual} retired=${retired}`);
    failed ||= !passed;
  }
} catch (error) { report.error = error.stack; failed = true; console.error(error.stack); }
finally {
  if (browser) await browser.close(); report.passed = !failed;
  if (out) writeFileSync(out, JSON.stringify(report, null, 2) + '\n', { flag: 'wx' });
}
process.exitCode = failed ? 1 : 0;
