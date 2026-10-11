/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */
// Existing Playwright/Sharp only. Fresh offline headless Chrome; no saved images/URLs.
import assert from 'node:assert/strict';
import { createRequire } from 'node:module';
import { readFileSync, writeFileSync } from 'node:fs';
import { execFileSync } from 'node:child_process';
import { fileURLToPath } from 'node:url';
import { createHash } from 'node:crypto';
import { resolve } from 'node:path';

const require = createRequire(import.meta.url);
let chromium, sharp;
const root = fileURLToPath(new URL('../', import.meta.url)), assets = 'app/src/main/assets/screensaver/';
const base = 'f147765d6959bc36ea7d9ad4df2e6ccd4b60e909';
const legacyPhaseAdmission = process.argv.includes('--legacy-phase-admission');
const diagnostic = process.argv.includes('--diagnostic');
const baselineRepeats = Number(process.argv.find(a => a.startsWith('--baseline-repeats='))?.slice(19) || 3);
const candidateRepeats = 2;
assert.ok(Number.isInteger(baselineRepeats) && baselineRepeats >= 3, 'At least three independent baseline renders are required');

// Capture the native RAF before Playwright replaces window.requestAnimationFrame.
// Advancing the fixture's JS clock does not admit a CSS transition to the native
// renderer. Commit its paused start before seeking a later phase; otherwise equal
// computed values can have different cached filter/shadow paint histories.
// Preserve frozen proof bytes. Exclusive creation also refuses an existing symlink.
export function writeFilmstripEvidence(path, report) {
  writeFileSync(path, JSON.stringify(report, null, 2) + '\n', { flag: 'wx', mode: 0o600 });
}

export function installNativeFrameBarrier(target) {
  const requestFrame = target.requestAnimationFrame.bind(target);
  target.filmstripNativeFrame = () => new Promise(resolve => requestFrame(() => requestFrame(resolve)));
}
const controlOnly = process.argv.includes('--control-only');
const resizeChecks = process.argv.includes('--resize-checks');
const caseFilter = process.argv.find(a => a.startsWith('--case='))?.slice(7);
const out = process.argv.find(a => a.startsWith('--out='))?.slice(6);
const hash = bytes => createHash('sha256').update(bytes).digest('hex');
const baseline = file => execFileSync('git', ['show', `${base}:${assets}${file}`], { cwd: root, encoding: 'utf8' });
const current = file => readFileSync(root + assets + file, 'utf8');
let css, common, sources;
function initializeFrozenSources() {
  css = baseline('styles.css'); assert.equal(current('styles.css'), css, 'CSS must remain unchanged');
  common = ['lib-engine.js', 'modes.js', 'scheduler.js'].map(file => {
    const source = baseline(file); assert.equal(current(file), source, `${file} must remain unchanged`);
    return { file, source, sha256: hash(source) };
  });
  sources = { baseline: baseline('mode-impls.js'), candidate: current('mode-impls.js') };
}
const wait = ms => new Promise(resolve => setTimeout(resolve, ms));

async function generate(format) {
  const photos = [], hashes = [];
  for (let i = 0; i < 12; i++) {
    const [w, h] = [[96, 64], [48, 96], [192, 48], [80, 80]][i % 4];
    const bytes = Buffer.alloc(w * h * 4);
    for (let y = 0; y < h; y++) for (let x = 0; x < w; x++) {
      const n = (y * w + x) * 4;
      bytes[n] = (x * 3 + i * 37) % 256; bytes[n + 1] = (y * 5 + i * 29) % 256;
      bytes[n + 2] = ((x + y) * 2 + i * 17) % 256;
      bytes[n + 3] = x < w / 3 ? 0 : x < w * 2 / 3 ? 128 : 255;
      if (Math.abs(x - w * .7) < 2 && Math.abs(y - h * .25) < 2) {
        bytes[n] = 255; bytes[n + 1] = 255; bytes[n + 2] = 0;
      }
    }
    let image = sharp(bytes, { raw: { width: w, height: h, channels: 4 } });
    image = format === 'jpeg' ? image.flatten({ background: '#204060' }).jpeg({ quality: 95 })
      : format === 'webp' ? image.webp({ lossless: true }) : image.png();
    const encoded = await image.toBuffer(), decoded = await sharp(encoded).ensureAlpha().raw().toBuffer();
    assert.deepEqual([...new Set(decoded.filter((_, n) => n % 4 === 3))].sort((a, b) => a - b),
      format === 'jpeg' ? [255] : [0, 128, 255]);
    hashes.push(hash(encoded));
    photos.push({ seed: i, w, h, fx: .7, fy: .25, pw: .8,
      url: `data:image/${format};base64,${encoded.toString('base64')}` });
  }
  return { photos, hashes };
}
async function difference(a, b, state) {
  const A = await sharp(a).ensureAlpha().raw().toBuffer({ resolveWithObject: true });
  const B = await sharp(b).ensureAlpha().raw().toBuffer({ resolveWithObject: true });
  assert.deepEqual(A.info, B.info);
  let changedPixels = 0, maxChannelDelta = 0;
  let minX = A.info.width, minY = A.info.height, maxX = -1, maxY = -1;
  const bands = Array(6).fill(0); let clipInteriors = 0;
  for (let n = 0; n < A.data.length; n += 4) {
    let changed = false;
    for (let c = 0; c < 4; c++) {
      const d = Math.abs(A.data[n + c] - B.data[n + c]);
      changed ||= d !== 0; maxChannelDelta = Math.max(maxChannelDelta, d);
    }
    changedPixels += Number(changed);
    if (changed) {
      const x = (n / 4) % A.info.width, y = Math.floor(n / 4 / A.info.width);
      minX = Math.min(minX, x); maxX = Math.max(maxX, x); minY = Math.min(minY, y); maxY = Math.max(maxY, y);
      bands[Math.min(5, Math.floor(y / A.info.height * 6))]++;
      if (state?.slides.some(s => {
        const [left, top, width, height] = s.imageRect;
        return x >= left + 16 && x < left + width - 16 && y >= top + 16 && y < top + height - 16;
      })) clipInteriors++;
    }
  }
  return { changedPixels, maxChannelDelta, clipInteriors, bounds: changedPixels ? [minX, minY, maxX, maxY] : null, bands, baselinePixelSha256: hash(A.data), comparedPixelSha256: hash(B.data) };
}
async function render(browser, source, photos, viewport, resize = null) {
  const context = await browser.newContext({ viewport, ...(resize ? { screen: resize.screen } : {}), deviceScaleFactor: 1, colorScheme: 'dark',
    reducedMotion: 'no-preference' });
  await context.setOffline(true); await context.route('**/*', route => route.abort());
  const page = await context.newPage(), shots = new Map(), states = {}, errors = [], diagnostics = {};
  const cdp = await context.newCDPSession(page);
  await cdp.send('Animation.enable');
  await cdp.send('Animation.setPlaybackRate', { playbackRate: 0 });
  page.on('pageerror', e => errors.push(e.message));
  try {
    await page.evaluate('(' + installNativeFrameBarrier.toString() + ')(window)');
    await page.clock.install({ time: 0 }); await page.clock.pauseAt(1000);
    await page.setContent(`<style>${css}</style><div id="stage" style="background:#204060"></div>`);
    await page.evaluate(({ photos, resizeWhen }) => {
      window.PHOTOS = photos; window.aspect = p => p.w / p.h; window.shuffled = xs => xs.slice();
      Math.random = () => .5;
      window.fixture = { ready: 0, prepared: false, resolutions: [], decodes: 0, decoded: new Set(),
        resizeWhen, holdStarted: false, releaseDecode: null };
      window.photoUrl = (p, edge) => {
        fixture.resolutions.push({ seed: p.seed, edge }); return p.url + '#edge=' + edge;
      };
      fixture.flush = () => {
        for (const n of document.querySelectorAll('.ss-beat, .ss-track, .ss-slide, .ss-bd')) getComputedStyle(n).transform;
      };
    }, { photos, resizeWhen: resize?.when || null });
    for (const { source: code } of common.slice(0, 2)) await page.addScriptTag({ content: code });
    await page.addScriptTag({ content: source });
    await page.evaluate(() => {
      const decode = window.preloadDecoded;
      window.preloadDecoded = async (url, signal) => {
        fixture.decodes++;
        // Force both initial layouts to have an established pre-go style, regardless
        // of real image/decode completion speed. No visible animation duration changes.
        if (url.endsWith('#edge=1000')) fixture.flush();
        const value = await decode(url, signal);
        if (fixture.resizeWhen === 'during-decode' && !fixture.holdStarted) {
          fixture.holdStarted = true;
          await new Promise(resolve => { fixture.releaseDecode = resolve; });
        }
        fixture.decoded.add(url); return value;
      };
      const run = SS.MODES.filmstrip.run;
      SS.MODES.filmstrip.run = (...args) => {
        fixture.cleanup = run(...args);
        fixture.cleanup.ready.then(ok => { fixture.prepared = ok; });
        return fixture.cleanup;
      };
    });
    await page.addScriptTag({ content: common[2].source });
    await page.evaluate(() => {
      fixture.engine = SS.startEngine({ stage: document.querySelector('#stage'), mode: 'filmstrip',
        intervalSec: 30, onReady: () => fixture.ready++ });
      fixture.collect = () => {
        fixture.flush();
        return document.querySelector('#stage').getAnimations({ subtree: true }).filter(a => a instanceof CSSTransition);
      };
      fixture.seek = (animations, time) => {
        for (const a of animations) {
          a.pause(); a.currentTime = Math.min(time, a.effect.getTiming().duration);
          // Release completed native effects to the unchanged stylesheet target.
          // Retaining a paused endpoint would replay prior beat/backdrop effects
          // when getAnimations() collects the following navigation.
          if (time >= a.effect.getTiming().duration) { a.finish(); a.cancel(); }
        }
      };
      fixture.state = () => ({
        track: getComputedStyle(document.querySelector('.ss-track')).transform,
        beat: getComputedStyle(document.querySelector('.ss-beat')).opacity,
        backdrops: [...document.querySelectorAll('.ss-bd')].map(n => ({
          opacity: getComputedStyle(n).opacity, filter: getComputedStyle(n).filter,
        })),
        slides: [...document.querySelectorAll('.ss-slide')].map(n => {
          const p = getComputedStyle(n), im = n.firstElementChild, c = getComputedStyle(im);
          const r = n.getBoundingClientRect(), ir = im.getBoundingClientRect();
          return { classes: n.className, rect: [r.x, r.y, r.width, r.height],
            imageRect: [ir.x, ir.y, ir.width, ir.height], filter: p.filter, opacity: p.opacity,
            transform: p.transform, shadow: p.boxShadow, radius: p.borderRadius, overflow: p.overflow,
            focus: c.backgroundPosition, size: c.backgroundSize,
            transition: [p.transitionProperty, p.transitionDuration, p.transitionTimingFunction] };
        }),
      });
      fixture.diagnosticState = () => ({
        resources: [...document.querySelectorAll('.ss-img, .ss-bd')].map(n => ({
          target: n.className,
          seed: fixture.resolutions.find(r => n.style.backgroundImage.includes(PHOTOS[r.seed].url))?.seed ?? null,
          decoded: !n.style.backgroundImage || fixture.decoded.has(n.style.backgroundImage.slice(5, -2)),
        })),
        animations: document.querySelector('#stage').getAnimations({subtree:true}).map(a => ({
          property: a.transitionProperty, target: a.effect.target.className, currentTime: a.currentTime,
          playState: a.playState, progress: a.effect.getComputedTiming().progress,
        })),
      });
      fixture.absentImageBounds = () => [...document.querySelectorAll('.ss-slide')].flatMap((n, slot) => {
        if (n.firstElementChild.style.backgroundImage) return [];
        const r = n.getBoundingClientRect(), pad = 512 * 1.12 + 2;
        return [{slot, left: r.left - pad, right: r.right + pad}];
      });
      fixture.allPrepared = () => [...document.querySelectorAll('.ss-img, .ss-bd')].every(n => {
        const value = n.style.backgroundImage;
        return !value || fixture.decoded.has(value.slice(5, -2));
      });
    });
    async function until(fn, label) {
      for (let i = 0; i < 200; i++) { if (await page.evaluate(fn)) return; await wait(10); }
      throw Error(label);
    }
    if (resize?.when === 'during-decode') {
      await until(() => fixture.holdStarted, 'Initial decoder must reach the controlled admission gate');
      await page.setViewportSize(resize.viewport); viewport = resize.viewport;
      await page.evaluate(() => fixture.releaseDecode());
    }
    await until(() => fixture.prepared, 'Real decoded Filmstrip must become ready');
    const initialCounts = await page.evaluate(() => ({ decodes: fixture.decodes, resolutions: fixture.resolutions.slice(),
      preparedSlots: [...document.querySelectorAll('.ss-slide > .ss-img')].filter(n => n.style.backgroundImage).length }));
    await page.evaluate(() => { fixture.initial = fixture.collect(); fixture.seek(fixture.initial, 0); });
    await page.clock.runFor(32); assert.equal(await page.evaluate(() => fixture.ready), 1);
    await page.evaluate(() => { fixture.initial = fixture.collect(); fixture.seek(fixture.initial, 0); });
    if (resize?.when === 'after-ready') {
      await page.setViewportSize(resize.viewport); viewport = resize.viewport;
      await page.evaluate(() => { fixture.initial = fixture.collect(); fixture.seek(fixture.initial, 0); });
      await page.evaluate(() => filmstripNativeFrame());
    }
    // The virtual JS clock stays below the scheduler's 300ms recovery deadline.
    // Real native paint therefore checks the existing scene during the vulnerable
    // interval, before a replacement scene could hide incomplete exposure.
    async function snapshot(phase) {
      assert.equal(await page.evaluate(() => fixture.allPrepared()), true, 'No undecoded URL may be published');
      const absentImageBounds = await page.evaluate(() => fixture.absentImageBounds());
      assert.ok(absentImageBounds.every(b => b.right < 0 || b.left > viewport.width),
        `An unprepared image must remain beyond the conservative paint allowance at ${phase}`);
      await page.evaluate(async () => { await Promise.all(fixture.collect().map(a => a.ready)); });
      await wait(50);
      const before = await page.evaluate(() => fixture.diagnosticState());
      const frames = [];
      let previous = await page.screenshot({ animations: 'allow' }), stable;
      for (let i = 0; i < 8; i++) {
        const next = await page.screenshot({ animations: 'allow' });
        frames.push(hash(next));
        if (previous.equals(next)) { stable = next; break; }
        previous = next; await wait(20);
      }
      assert.ok(stable, `Pinned capture must stabilize at ${phase}`);
      states[phase] = await page.evaluate(() => fixture.state());
      diagnostics[phase] = { absentImageBounds, before, after: await page.evaluate(() => fixture.diagnosticState()), frames };
      shots.set(phase, stable);
    }
    for (const time of [0, 700, 1600]) {
      await page.evaluate(time => fixture.seek(fixture.initial, time), time); await snapshot(`initial-${time}`);
    }
    const transitions = [];
    async function navigate(direction, label, samples) {
      await page.evaluate(async direction => {
        fixture.flush(); if (!await fixture.engine[direction]()) throw Error('Actual mode navigation rejected');
        fixture.navigation = fixture.collect(); fixture.seek(fixture.navigation, 0);
      }, direction);
      if (!legacyPhaseAdmission) await page.evaluate(() => filmstripNativeFrame());
      transitions.push(await page.evaluate(() => fixture.navigation.map(a => ({
        property: a.transitionProperty, duration: a.effect.getTiming().duration, target: a.effect.target.className,
      }))));
      for (const time of samples) {
        await page.evaluate(time => fixture.seek(fixture.navigation, time), time);
        await snapshot(`${label}-${time}`);
      }
    }
    await navigate('next', 'step-1', [0, 700, 1400]);
    for (let i = 2; i <= 11; i++) await navigate('next', `step-${i}`, [1400]);
    await navigate('next', 'wrap-last-first', [0, 350, 700, 1050, 1399, 1400]);
    await navigate('previous', 'wrap-first-last', [0, 700, 1400]);
    await navigate('previous', 'previous-11-10', [700, 1400]);
    const finalCounts = await page.evaluate(() => ({
      decodes: fixture.decodes, resolutions: fixture.resolutions, preparedSlots: document.querySelectorAll('.ss-slide').length,
    }));
    assert.equal(states['wrap-last-first-1400'].slides.findIndex(n => n.classes.split(' ').includes('center')), 0);
    assert.equal(states['wrap-first-last-1400'].slides.findIndex(n => n.classes.split(' ').includes('center')), 11);
    assert.equal(states['previous-11-10-1400'].slides.findIndex(n => n.classes.split(' ').includes('center')), 10);
    for (const navigation of transitions) {
      assert.ok(navigation.some(t => t.target === 'ss-track' && t.property === 'transform' && t.duration === 1400));
      assert.equal(navigation.filter(t => t.property === 'filter' && t.duration === 1400).length, 2);
    }
    await page.evaluate(() => { fixture.engine.stop(); fixture.engine.stop(); });
    assert.equal(await page.evaluate(() => document.querySelector('#stage').children.length), 0);
    assert.deepEqual(errors, []);
    return { shots, states, diagnostics, initialCounts, finalCounts, transitions };
  } finally { await context.close(); }
}
async function main() {
  initializeFrozenSources();
  ({ chromium } = require('playwright')); sharp = require('sharp');
  const report = { base, mode: controlOnly ? 'baseline-control' : 'candidate',
    caseFilter: caseFilter || null, resizeChecks, phaseAdmission: legacyPhaseAdmission ? 'legacy-no-native-start-frame' : 'two-native-start-frames',
    baselineRepeats, candidateRepeats: controlOnly ? 0 : candidateRepeats,
    probeSha256: hash(readFileSync(fileURLToPath(import.meta.url))),
    sourceSha256: Object.fromEntries(Object.entries(sources).map(([k, v]) => [k, hash(v)])),
    cssSha256: hash(css), commonSha256: Object.fromEntries(common.map(c => [c.file, c.sha256])),
    tolerance: { changedPixels: 0, maxChannelDelta: 0 }, cases: [],
    limitations: ['Generated offline software Chrome 155 with phase-pinned real CSS; no hardware latency/GPU evidence.',
      'The probe commits each native transition start before seeking samples; production timing is unchanged.',
      'Existing Filmstrip centers image backgrounds despite supplied face metadata; this behavior is preserved.',
      'Sampled fidelity and resolve/decode call counts do not prove upload readiness or compositor savings.'] };
  let browser, failed = false;
  try {
    browser = await chromium.launch({ headless: true, channel: 'chrome',
      args: ['--disable-gpu', '--disable-threaded-animation'] });
    report.browser = await browser.version(); assert.match(report.browser, /^155\./);
    const preparedCases = [];
    // Complete the entire baseline repeat matrix BEFORE evaluating any candidate.
    const matrix = resizeChecks ? ['during-decode', 'after-ready'].map(when => ({
      format: 'png', viewport: { width: 320, height: 200 },
      resize: { when, screen: { width: 1920, height: 1080 }, viewport: { width: 1920, height: 1080 } },
    })) : ['jpeg', 'png', 'webp'].flatMap(format => [{ width: 600, height: 900 }, { width: 1200, height: 600 }]
      .map(viewport => ({ format, viewport, resize: null })));
    for (const { format, viewport, resize } of matrix) {
      if (caseFilter && caseFilter !== `${format}:${viewport.width}`) continue;
      const fixtures = await generate(format);
      const A = await render(browser, sources.baseline, fixtures.photos, viewport, resize), controls = [], repeatDiagnostics = [];
      for (let repeat = 1; repeat < baselineRepeats; repeat++) {
        const C = await render(browser, sources.baseline, fixtures.photos, viewport, resize);
        for (const [phase, bytes] of A.shots) controls.push({ repeat, phase,
          geometryEqual: JSON.stringify(A.states[phase]) === JSON.stringify(C.states[phase]),
          ...await difference(bytes, C.shots.get(phase), A.states[phase]) });
        if (diagnostic) repeatDiagnostics.push(C.diagnostics);
      }
      const stable = controls.every(c => !c.changedPixels && !c.maxChannelDelta && c.geometryEqual);
      const entry = { format, viewport, resize, fixtureHashes: fixtures.hashes, controls, stable, passed: stable,
        baselineInitial: A.initialCounts, baselineFinal: A.finalCounts,
        ...(diagnostic ? { baselineDiagnostics: A.diagnostics, repeatDiagnostics } : {}) };
      report.cases.push(entry); preparedCases.push({ entry, fixtures, A });
      failed ||= !stable;
      console.log(`${format} ${viewport.width}x${viewport.height}${resize ? " " + resize.when : ""}: baseline ${controls.filter(c => !c.changedPixels && !c.maxChannelDelta && c.geometryEqual).length}/${controls.length} exact`);
    }
    assert.equal(preparedCases.length, caseFilter ? 1 : resizeChecks ? 2 : 6, 'Never silently skip a matrix case');
    report.allBaselineControlsStable = !failed;
    if (!controlOnly && !failed) {
      for (const { entry, fixtures, A } of preparedCases) {
        const comparisons = [], candidateRuns = [];
        for (let repeat = 0; repeat < candidateRepeats; repeat++) {
          const B = await render(browser, sources.candidate, fixtures.photos, entry.viewport, entry.resize);
          for (const [phase, bytes] of A.shots) comparisons.push({ repeat, phase,
            geometryEqual: JSON.stringify(A.states[phase]) === JSON.stringify(B.states[phase]),
            ...await difference(bytes, B.shots.get(phase), A.states[phase]) });
          candidateRuns.push({ initial: B.initialCounts, final: B.finalCounts, transitions: B.transitions,
            ...(diagnostic ? { diagnostics: B.diagnostics } : {}) });
        }
        const pixelsEqual = comparisons.every(c => !c.changedPixels && !c.maxChannelDelta);
        const geometryEqual = comparisons.every(c => c.geometryEqual);
        const reduced = candidateRuns.every(B => B.initial.resolutions.filter(c => c.edge === 1400).length <
          A.initialCounts.resolutions.filter(c => c.edge === 1400).length && B.initial.decodes < A.initialCounts.decodes);
        entry.comparisons = comparisons; entry.candidateRuns = candidateRuns;
        entry.pixelsEqual = pixelsEqual; entry.geometryEqual = geometryEqual; entry.reduced = reduced;
        entry.passed = pixelsEqual && geometryEqual && reduced; failed ||= !entry.passed;
        console.log(`${entry.format} ${entry.viewport.width}x${entry.viewport.height}${entry.resize ? " " + entry.resize.when : ""}: candidate ${comparisons.filter(c => !c.changedPixels && !c.maxChannelDelta && c.geometryEqual).length}/${comparisons.length} exact, reduced=${reduced}`);
      }
    } else if (!controlOnly) console.log('Baseline matrix unstable; ALL candidate evaluations blocked');
  } catch (error) { failed = true; report.error = error.stack; console.error(error.stack); }
  finally {
    if (browser) await browser.close();
    report.passed = !failed;
    if (out) writeFilmstripEvidence(out, report);
  }
  process.exitCode = failed ? 1 : 0;
}
if (process.argv[1] && resolve(process.argv[1]) === fileURLToPath(import.meta.url)) await main();
