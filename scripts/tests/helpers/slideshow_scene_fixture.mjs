/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */
import assert from 'node:assert/strict';
import { readFileSync } from 'node:fs';
import vm from 'node:vm';

const assets = new URL('../../../app/src/main/assets/screensaver/', import.meta.url);
const css = readFileSync(new URL('styles.css', assets), 'utf8');
// Evaluate the real class/descendant declarations used by the retained-photo styles. This fixture does
// not emulate rasterization; it checks the mode's DOM lifecycle and resulting CSS inputs.
const rules = [...css.matchAll(/([^{}]+)\{([^{}]*)\}/g)].map(([, selectors, declarations]) => ({
  selectors: selectors.replace(/\/\*[\s\S]*?\*\//g, '').trim().split(','),
  declarations: Object.fromEntries(declarations.split(';').map(part => {
    const colon = part.indexOf(':');
    return colon < 0 ? [] : [part.slice(0, colon).trim(), part.slice(colon + 1).trim()];
  }).filter(pair => pair.length)),
}));

export function createSceneFixture({ photoMs = 5000, deferLoad = false, photos, changingUrls = false, mode = 'framed' } = {}) {
  let now = 0, nextId = 1, deferred = deferLoad;
  const pending = new Map(), loads = [], writes = [];
  function schedule(fn, ms) {
    const id = nextId++;
    pending.set(id, { fn, at: now + ms });
    return id;
  }
  function simpleMatch(node, selector) {
    if (selector === '*') return true;
    if (!/^\.[\w.-]+$/.test(selector)) return false;
    return selector.slice(1).split('.').every(name => node.classList.contains(name));
  }
  function matches(node, selector) {
    const parts = selector.trim().split(/\s+/);
    if (!simpleMatch(node, parts.pop())) return false;
    let ancestor = node.parent;
    while (parts.length) {
      const part = parts.pop();
      while (ancestor && !simpleMatch(ancestor, part)) ancestor = ancestor.parent;
      if (!ancestor) return false;
      ancestor = ancestor.parent;
    }
    return true;
  }
  function declarations(node) {
    const result = {};
    for (const rule of rules) {
      if (rule.selectors.some(selector => matches(node, selector))) Object.assign(result, rule.declarations);
    }
    return result;
  }
  function computed(node) {
    const declared = declarations(node);
    const result = Object.fromEntries(Object.entries(declared).map(([key, value]) =>
      [key, value.replace(/\s*!important$/, '')]));
    for (const [key, value] of Object.entries(node.style)) {
      if (value !== '') result[key.replace(/[A-Z]/g, letter => '-' + letter.toLowerCase())] = value;
    }
    for (const [key, value] of Object.entries(declared)) {
      if (value.endsWith('!important')) result[key] = value.replace(/\s*!important$/, '');
    }
    return result;
  }
  class Element {
    constructor() {
      this.children = []; this.names = '';
      this.style = new Proxy({}, { set: (style, key, value) => {
        const before = this.opacity(), oldTarget = computed(this).opacity;
        style[key] = value;
        if (key === 'transition' && value === 'none') this.fade = null;
        if (key === 'opacity') this.changedOpacity(before, oldTarget);
        if (key === 'backgroundImage') writes.push({ node: this, value });
        return true;
      } });
      Object.defineProperty(this.style, 'setProperty', { value(key, value) { this[key] = value; } });
    }
    changedOpacity(before, oldTarget) {
      const current = computed(this), target = current.opacity;
      if (oldTarget === target || !this.parent) return;
      const duration = /opacity\s+([\d.]+)(ms|s)/.exec(current.transition || '');
      this.fade = { from: before, to: Number(target ?? 1), start: now,
        duration: duration ? Number(duration[1]) * (duration[2] === 's' ? 1000 : 1) : 0 };
    }
    get className() { return this.names; }
    set className(value) {
      const before = this.opacity(), oldTarget = computed(this).opacity;
      this.names = value;
      this.changedOpacity(before, oldTarget);
    }
    get classList() {
      return {
        contains: name => this.names.split(/\s+/).includes(name),
        add: (...names) => { this.className = [...new Set([...this.names.split(/\s+/), ...names])].join(' ').trim(); },
        remove: (...names) => { this.className = this.names.split(/\s+/).filter(name => !names.includes(name)).join(' '); },
      };
    }
    opacity() {
      if (!this.style || !this.fade) return Number(this.style ? computed(this).opacity ?? 1 : 1);
      const progress = this.fade.duration ? Math.min(1, (now - this.fade.start) / this.fade.duration) : 1;
      if (progress === 1) return this.fade.to;
      return this.fade.from + (this.fade.to - this.fade.from) * progress;
    }
    appendChild(child) {
      if (child.parent) child.parent.children = child.parent.children.filter(node => node !== child);
      child.parent = this; this.children.push(child); return child;
    }
    get parentNode() { return this.parent; }
    remove() {
      if (this.parent) this.parent.children = this.parent.children.filter(node => node !== this);
      this.parent = null;
    }
    set innerHTML(value) {
      assert.equal(value, '');
      this.children.forEach(child => { child.parent = null; });
      this.children = [];
    }
    get offsetWidth() { return 2160; }
    dispatchEvent(event) { this['on' + event.type]?.(event); }
  }
  const window = { aspect: photo => photo.w / photo.h, preloadDecoded: (url, signal) => {
    const request = { url, signal };
    loads.push(request);
    if (!deferred) return Promise.resolve(true);
    deferred = false;
    return new Promise((resolve, reject) => { request.resolve = resolve; request.reject = reject; });
  } };
  const context = vm.createContext({ window, document: { createElement: () => new Element() }, AbortController,
    getComputedStyle: node => ({ ...computed(node), opacity: String(node.opacity()) }),
    setTimeout: schedule, clearTimeout: id => pending.delete(id),
    requestAnimationFrame: fn => schedule(fn, 16), cancelAnimationFrame: id => pending.delete(id),
    Date: class extends Date { static now() { return now; } },
    Math: Object.assign(Object.create(Math), { random: () => .5 }),
  });
  if (mode === 'galleryWall') {
    const loader = window.preloadDecoded;
    vm.runInContext(readFileSync(new URL('lib-engine.js', assets), 'utf8'), context,
      { filename: 'lib-engine.js' });
    window.preloadDecoded = loader;
  }
  for (const file of ['modes.js', 'mode-impls.js']) {
    vm.runInContext(readFileSync(new URL(file, assets), 'utf8'), context, { filename: file });
  }
  const root = new Element();
  const deck = photos || Array.from({ length: 8 }, (_, i) => ({
    seed: 'generated-' + i, w: i % 2 ? 1000 : 1600, h: i % 2 ? 1600 : 1000,
  }));
  const cursor = { i: 0 }, resolutions = [];
  const cleanup = window.SS.MODES[mode].run({ root, cursor, W: 2160, H: 1440,
    photoMs, microMs: photoMs, motionMs: 5400, url: (photo, edge) => {
      const url = 'generated://' + photo.seed + (changingUrls ? '-r' + (resolutions.length + 1) : '');
      resolutions.push({ photo, edge, url }); return url;
    } }, deck);
  function nodes(node = root) { return [node, ...node.children.flatMap(child => nodes(child))]; }
  function prints() { return nodes().filter(node =>
    node.classList.contains(mode === 'memories' ? 'memory-photo' : ['galleryWall', 'heroSidebar'].includes(mode) ? 'ss-img' : mode === 'push' ? 'ss-hero' : 'ss-print')); }
  function slot(print) {
    if (mode === 'memories') {
      let node = print;
      while (node.parent && !node.classList.contains('ss-memory-scene')) node = node.parent;
      return node;
    }
    return mode === 'galleryWall' ? print : ['push', 'heroSidebar'].includes(mode) ? print.parent : print.parent.parent.parent.parent;
  }
  function state(print) {
    const style = computed(print), animation = style.animation || 'none';
    return { animation, promotion: style['will-change'] || 'auto', playState: style['animation-play-state'] || 'running',
      visible: slot(print).classList.contains('on'), opacity: slot(print).opacity() };
  }
  // Drain the complete gated decode/publication chain before advancing the virtual clock.
  async function flush() { for (let i = 0; i < 64; i++) await Promise.resolve(); }
  async function advanceTo(time) {
    assert.ok(time >= now);
    while (true) {
      const entry = [...pending].sort((a, b) => a[1].at - b[1].at || a[0] - b[0])[0];
      if (!entry || entry[1].at > time) break;
      const [id, task] = entry;
      pending.delete(id); now = task.at;
      task.fn(); await flush();
    }
    now = time; await flush();
  }
  function end(print, { propertyName = 'opacity', target = slot(print) } = {}) {
    slot(print).dispatchEvent({ type: 'transitionend', propertyName, target });
  }
  return { cleanup, prints, state, slot, nodes, pending, loads, writes, resolutions, cursor, computed, advanceTo, end,
    incoming: () => prints().filter(print => state(print).visible).at(-1),
    elapseTo: time => { assert.ok(time >= now); now = time; },
    deferNext: () => { deferred = true; },
    finishLoad: () => loads.findLast(load => load.resolve)?.resolve(true),
    failLoad: () => loads.findLast(load => load.reject)?.reject(new Error('unavailable')),
    flush };
}
