/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */
// A deliberately small DOM test adapter. It exercises shipped controllers, not rendering.
import vm from 'node:vm';
import { readFileSync } from 'node:fs';
import * as core from '../../main/assets/remote/core.mjs';
class Element {
  constructor(tag = 'div', doc = null) {
    this.tagName = tag.toUpperCase();
    this.doc = doc;
    this.children = [];
    this.attrs = {};
    this.dataset = {};
    this.listeners = {};
    this.value = '';
    this.hidden = false;
    this.open = false;
    this.text = '';
  }
  get textContent() {
    return this.text + this.children.map((c) => c.textContent).join('');
  }
  set textContent(value) {
    this.text = String(value ?? '');
    this.children = [];
  }
  get innerHTML() {
    return this.textContent;
  }
  set innerHTML(value) {
    this.text = '';
    this.children = [new Element('svg', this.doc)];
    this.children[0].parent = this;
  }
  get firstChild() {
    return this.children[0];
  }
  get isConnected() {
    return this.doc?.roots.some((root) => root === this || root.contains(this)) || false;
  }
  get className() {
    return this.attrs.class || '';
  }
  set className(value) {
    this.attrs.class = value;
  }
  setAttribute(key, value) {
    this.attrs[key] = String(value);
    if (key.startsWith('data-'))
      this.dataset[key.slice(5).replace(/-([a-z])/g, (_, letter) => letter.toUpperCase())] =
        String(value);
    if (key === 'type') this.type = value;
  }
  getAttribute(key) {
    return this.attrs[key];
  }
  append(...children) {
    for (let child of children) {
      if (!(child instanceof Element)) child = this.doc.createTextNode(child);
      if (child.parent) child.remove();
      child.parent = this;
      this.children.push(child);
    }
  }
  replaceChildren(...children) {
    this.text = '';
    this.children = [];
    this.append(...children);
  }
  replaceWith(node) {
    const index = this.parent.children.indexOf(this);
    this.parent.children[index] = node;
    node.parent = this.parent;
  }
  remove() {
    if (this.parent) this.parent.children = this.parent.children.filter((c) => c !== this);
  }
  contains(node) {
    return this.children.some((c) => c === node || c.contains(node));
  }
  addEventListener(name, fn) {
    (this.listeners[name] ??= []).push(fn);
  }
  matches(selector) {
    return selector.split(',').some((s) => {
      s = s.trim();
      const tag = s.match(/^[a-zA-Z]+/)?.[0];
      if (tag && tag.toUpperCase() !== this.tagName) return false;
      const attr = s.match(/\[([^=\]]+)(?:="([^"]*)")?\]/);
      if (
        attr &&
        (!Object.hasOwn(this.attrs, attr[1]) ||
          (attr[2] !== undefined && this.attrs[attr[1]] !== attr[2]))
      )
        return false;
      const cls = s.match(/\.([\w-]+)/)?.[1];
      return !cls || this.className.split(' ').includes(cls);
    });
  }
  querySelectorAll(selector) {
    return this.children.flatMap((c) => [
      ...(c.matches(selector) ? [c] : []),
      ...c.querySelectorAll(selector),
    ]);
  }
  querySelector(selector) {
    return this.querySelectorAll(selector)[0] || null;
  }
  focus() {
    this.doc.activeElement = this;
  }
  setSelectionRange() {}
  showModal() {
    this.open = true;
  }
  close() {
    this.open = false;
    this.onclose?.();
  }
  getBoundingClientRect() {
    return { left: 0, right: 500, top: 0, bottom: 500 };
  }
  setPointerCapture() {}
  click() {
    if (this.disabled) return;
    const checkbox = this.tagName === 'INPUT' && this.type === 'checkbox';
    const oldChecked = this.checked;
    if (checkbox) {
      this.checked = !this.checked;
      this.indeterminate = false;
    }
    const event = {
      target: this,
      defaultPrevented: false,
      preventDefault() {
        this.defaultPrevented = true;
      },
    };
    for (let node = this; node; node = node.parent) {
      node.onclick?.(event);
      for (const listener of node.listeners.click || []) listener(event);
    }
    if (event.defaultPrevented) {
      if (checkbox) this.checked = oldChecked;
      return;
    }
    // Model the HTML label's native default action when its padding is clicked.
    // A direct input click never forwards through its containing label again.
    if (this.tagName === 'LABEL') this.querySelector('input')?.click();
    if (checkbox) {
      for (const name of ['input', 'change']) {
        this[`on${name}`]?.({ target: this });
        for (const listener of this.listeners[name] || []) listener({ target: this });
      }
    }
  }
}
const state = {
  protocolVersion: 1,
  deviceId: 'fixture-device',
  name: 'Preview room',
  model: 'Synthetic Portal',
  capabilities: {
    layout: true,
    snapshot: true,
    catalog: true,
    operations: true,
    scenes: true,
    jarvis: true,
    photos: true,
    photoLibrary: true,
  },
  app: { versionName: 'fixture' },
};
export async function browserHarness({ routes = {}, saved = [], active = '0' } = {}) {
  const document = {
    hidden: false,
    roots: [],
    listeners: {},
    activeElement: null,
    createElement(tag) {
      return new Element(tag, this);
    },
    createTextNode(value) {
      const node = new Element('text', this);
      node.textContent = value;
      return node;
    },
    getElementById(id) {
      return this.roots
        .flatMap((r) => [r, ...r.querySelectorAll('[id]')])
        .find((n) => n.attrs.id === id);
    },
    addEventListener(name, fn) {
      this.listeners[name] = fn;
    },
  };
  document.documentElement = document.createElement('html');
  document.body = document.createElement('body');
  document.roots = [document.documentElement, document.body];
  for (const id of [
    'page',
    'sheet',
    'sheet-content',
    'sheet-actions',
    'sheet-title',
    'sheet-close',
    'room-button',
    'refresh-button',
    'connection',
    'navigation',
    'notice',
  ])
    document.body.append(Object.assign(document.createElement('div'), { attrs: { id } }));
  const sheet = document.getElementById('sheet');
  for (const id of ['sheet-title', 'sheet-close', 'sheet-content', 'sheet-actions'])
    sheet.append(document.getElementById(id));
  const savedMap = new Map([
      [core.STORAGE.roster, JSON.stringify(saved)],
      [core.STORAGE.active, active],
    ]),
    calls = [],
    timeouts = new Map();
  let timerId = 0;
  const response = (body, status = 200) => new Response(JSON.stringify(body), { status });
  const fetcher = async (url, opts) => {
    const path = new URL(url).pathname;
    calls.push({ url, path, opts });
    const route = routes[path];
    if (route) return route({ url, opts, response });
    if (path === '/remote/companion') return response({ ok: true, ...state });
    if (path === '/remote/nowplaying')
      return response({
        ok: true,
        np: { active: false, volume: { level: 4, max: 15, canSet: true } },
      });
    if (path === '/remote/roster') return response({ ok: true, roster: [] });
    if (path === '/remote/photos')
      return response({ ok: true, path: '/sdcard', recursive: true, files: [], folders: [] });
    if (path === '/remote/settings') return response({ ok: true, settings: { domains: [] } });
    if (path === '/remote/layout')
      return response({
        ok: true,
        layout: {
          assignments: { 'com.test.app': 'Room' },
          homeOrder: ['com.test.app/.Main'],
          gridOrder: [],
          gridSlots: [],
          widgets: [],
        },
        revision: 'current',
        widgetKinds: ['weather'],
      });
    return response({
      ok: true,
      apps: [],
      operations: [],
      scenes: [],
      presets: [],
      jarvis: {
        supported: true,
        installed: true,
        model: 'test',
        availableModels: ['test'],
        wakeSupported: true,
        wakeEnabled: false,
      },
    });
  };
  const context = vm.createContext({
    __core: core,
    document,
    Node: Element,
    URL,
    Blob,
    AbortController,
    Response,
    DOMException,
    console,
    fetch: fetcher,
    location: { origin: 'http://127.0.0.1:8877', hash: '', pathname: '/remote/ui', search: '' },
    history: { replaceState() {} },
    navigator: {},
    localStorage: {
      getItem: (key) => savedMap.get(key) ?? null,
      setItem: (key, value) => savedMap.set(key, value),
    },
    window: { scrollTo() {}, addEventListener() {} },
    setTimeout: (fn, delay) => {
      timeouts.set(++timerId, { fn, delay });
      return timerId;
    },
    clearTimeout: (id) => timeouts.delete(id),
    queueMicrotask,
    crypto: { randomUUID: () => `fixture-id-${++timerId}` },
  });
  const source = readFileSync(
    new URL('../../main/assets/remote/control-center.js', import.meta.url),
    'utf8',
  ).replace(/import\s+\{([\s\S]+?)\}\s+from\s+['"]\.\/core\.mjs['"];?/, 'const {$1}=__core;');
  vm.runInContext(source, context);
  const settle = async () => {
    for (let i = 0; i < 20; i++) await new Promise((r) => setImmediate(r));
  };
  await settle();
  return {
    document,
    calls,
    tick: async (delay) => {
      const ready = [...timeouts].filter(([, timer]) => timer.delay === delay);
      for (const [id, timer] of ready) {
        timeouts.delete(id);
        await timer.fn();
      }
      await settle();
    },
    eval: (expression) => vm.runInContext(expression, context),
    settle,
    click: (label) => {
      const buttons = document.body.querySelectorAll('button');
      const found = buttons.find((b) => b.textContent === label);
      if (!found) throw new Error(`Button ${label} not found in ${document.body.textContent}`);
      found.click();
      return found;
    },
    input: async (label, value) => {
      const field = document.body
        .querySelectorAll('input')
        .find((n) => n.attrs['aria-label'] === label);
      if (!field) throw new Error(`Input ${label} missing`);
      field.value = value;
      for (const handler of field.listeners.input || []) handler({ target: field });
      field.oninput?.({ target: field });
      await settle();
      return field;
    },
    hide: () => {
      document.hidden = true;
      document.listeners.visibilitychange();
    },
    show: () => {
      document.hidden = false;
      document.listeners.visibilitychange();
    },
  };
}
