/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */
// Pure feature seams; the browser and Node regression tests import the same module.
export const STORAGE = Object.freeze({
  roster: 'immortal_remote_devices',
  active: 'immortal_remote_active',
  selected: 'immortal_remote_active_base',
  profiles: 'immortal_remote_profiles',
  theme: 'immortal_remote_theme',
});
export function normalizeBase(address) {
  let text = String(address || '').trim();
  if (!text) throw new Error('address_required');
  if (!/^https?:\/\//i.test(text)) {
    if (/^[a-z]+:/i.test(text) && !/^[\w.-]+:\d+(?:\/|$)/.test(text))
      throw new Error('invalid_address');
    text = 'http://' + text;
  }
  const url = new URL(text);
  if (
    !['http:', 'https:'].includes(url.protocol) ||
    !url.hostname ||
    url.username ||
    url.password ||
    url.search
  )
    throw new Error('invalid_address');
  // Pairing links include /remote/ui and a PIN fragment; APIs live at the origin.
  if (!url.port && !/^https?:\/\//i.test(String(address))) url.port = '8723';
  return url.origin;
}
export function sanitizeRoster(input) {
  const result = [],
    seen = new Set();
  for (const room of Array.isArray(input) ? input : []) {
    if (result.length === 64) break;
    try {
      const base = normalizeBase(room.base),
        token = room.token;
      if (
        typeof token !== 'string' ||
        !token.trim() ||
        token.length > 512 ||
        /[\r\n]/.test(token) ||
        seen.has(base)
      )
        continue;
      seen.add(base);
      result.push({ base, token: token.trim(), name: String(room.name || 'Portal').slice(0, 48) });
    } catch {
      /* Ignore corrupt legacy entries without logging their session. */
    }
  }
  return result;
}
export const mergeRoster = (local, server) =>
  sanitizeRoster([...sanitizeRoster(local), ...sanitizeRoster(server)]);
export function activeBase(roster, legacyIndex, preferred) {
  if (roster.some((r) => r.base === preferred)) return preferred;
  return (roster[Number.parseInt(legacyIndex, 10)] || roster[0])?.base || null;
}
export function captureTargets(roster, scope, base, selected = new Set()) {
  return Object.freeze(
    roster
      .filter(
        (r) => scope === 'all' || (scope === 'selected' ? selected.has(r.base) : r.base === base),
      )
      .map((r) => Object.freeze({ ...r })),
  );
}
// Keep the native CompanionGroupSettings policy at both UI and transport boundaries.
export function activeOnlySetting(domain, key, control = {}) {
  const lower = String(key).toLowerCase();
  return (
    domain === 'fleet' ||
    (domain === 'mqtt' && control.type === 'string') ||
    ['name', 'devicename', 'portalname', 'roomname', 'hostname'].includes(lower) ||
    [
      'password',
      'passwd',
      'credential',
      'token',
      'apikey',
      'secret',
      'endpoint',
      'url',
      'folderpath',
      'immich',
      'smb',
      'dav',
      'source',
      'broker',
      'connection',
    ].some((part) => lower.includes(part)) ||
    control.secret === true ||
    ['url', 'password'].includes(control.entry)
  );
}
export class Session {
  constructor() {
    this.room = null;
    this.generation = 0;
    this.pageGeneration = 0;
    this.page = 'home';
    this.reads = new Map();
  }
  select(room) {
    this.room = room ? Object.freeze({ ...room }) : null;
    this.generation++;
    this.pageGeneration++;
    this.reads.clear();
  }
  enter(page) {
    this.page = page;
    this.pageGeneration++;
    this.reads.clear();
    return this.capture();
  }
  capture(room = this.room) {
    return Object.freeze({
      room: room ? Object.freeze({ ...room }) : null,
      generation: this.generation,
      pageGeneration: this.pageGeneration,
    });
  }
  read(key) {
    const number = (this.reads.get(key) || 0) + 1;
    this.reads.set(key, number);
    return Object.freeze({ ...this.capture(), key, number });
  }
  current(ticket, { page = true } = {}) {
    return (
      !!ticket &&
      ticket.generation === this.generation &&
      ticket.room?.base === this.room?.base &&
      ticket.room?.token === this.room?.token &&
      (!page || ticket.pageGeneration === this.pageGeneration) &&
      (!ticket.key || this.reads.get(ticket.key) === ticket.number)
    );
  }
}
// Serialize each control key while keeping unrelated controls independent.
export class ControlCommandQueue {
  constructor() {
    this.states = new Map();
  }
  hasPending(key) {
    return this.states.has(key);
  }
  enqueue(key, { coalesceKey = null, current = () => true, run }) {
    let state = this.states.get(key);
    if (!state) {
      state = { running: false, pending: [] };
      this.states.set(key, state);
    }
    const promise = new Promise((resolve, reject) => {
      const tail = state.pending[state.pending.length - 1];
      // The active command is already removed; relative actions form barriers.
      if (coalesceKey !== null && tail?.coalesceKey === coalesceKey) {
        state.pending.pop();
        tail.resolve(null);
      }
      state.pending.push({ coalesceKey, current, run, resolve, reject });
    });
    if (!state.running) this.drain(key, state);
    return promise;
  }
  async drain(key, state) {
    state.running = true;
    while (state.pending.length) {
      const command = state.pending.shift();
      try {
        command.resolve(command.current() ? await command.run() : null);
      } catch (error) {
        command.reject(error);
      }
    }
    this.states.delete(key);
  }
}
export class ApiError extends Error {
  constructor(code, status = 0, data = null) {
    super(code);
    this.code = code;
    this.status = status;
    this.data = data;
  }
}
const errorCode = (value) =>
  typeof value === 'string' && /^[a-zA-Z0-9_:.-]{1,100}$/.test(value) ? value : 'request_failed';
export function createClient({
  fetch: fetcher = globalThis.fetch,
  onUnauthorized = () => {},
} = {}) {
  return async (room, path, options = {}) => {
    if (!room?.base) throw new ApiError('connect_required');
    if (!/^\/remote\/[a-zA-Z0-9/?=&%_.+-]+$/.test(path)) throw new ApiError('invalid_route');
    const controller = new AbortController();
    let timedOut = false;
    const abort = () => controller.abort();
    options.signal?.addEventListener('abort', abort, { once: true });
    if (options.signal?.aborted) controller.abort();
    const timer = setTimeout(() => {
      timedOut = true;
      controller.abort();
    }, options.timeout ?? 12000);
    const native = typeof Blob !== 'undefined' && options.body instanceof Blob;
    const headers = { ...options.headers };
    if (room.token) headers.Authorization = 'Bearer ' + room.token;
    if (options.body !== undefined && !native) headers['Content-Type'] = 'application/json';
    try {
      const response = await fetcher(room.base + path, {
        method: options.method || (options.body !== undefined ? 'POST' : 'GET'),
        headers,
        body:
          options.body === undefined
            ? undefined
            : native
              ? options.body
              : JSON.stringify(options.body),
        signal: controller.signal,
        cache: 'no-store',
        credentials: 'omit',
        referrerPolicy: 'no-referrer',
      });
      if (response.status === 401 && room.token) onUnauthorized(Object.freeze({ ...room }));
      if (options.blob && response.ok) {
        const length = Number(response.headers.get('Content-Length'));
        if (length > (options.maxBytes ?? 4 * 1024 * 1024)) throw new ApiError('image_size_limit');
        const blob = await response.blob();
        if (blob.size > (options.maxBytes ?? 4 * 1024 * 1024))
          throw new ApiError('image_size_limit');
        if (!/^image\//.test(blob.type)) throw new ApiError('invalid_image');
        return blob;
      }
      let data;
      try {
        data = await response.json();
      } catch {
        throw new ApiError('invalid_response', response.status);
      }
      if (!response.ok || data.ok === false)
        throw new ApiError(errorCode(data.error), response.status, data);
      return data;
    } catch (error) {
      if (timedOut) throw new ApiError('timeout');
      if (controller.signal.aborted) throw new ApiError('aborted');
      if (error instanceof ApiError) throw error;
      throw new ApiError('unreachable');
    } finally {
      clearTimeout(timer);
      options.signal?.removeEventListener('abort', abort);
    }
  };
}
export class ImagePool {
  constructor({
    create = (b) => URL.createObjectURL(b),
    revoke = (u) => URL.revokeObjectURL(u),
    limit = 24,
    concurrency = 4,
  } = {}) {
    this.create = create;
    this.revoke = revoke;
    this.limit = limit;
    this.concurrency = concurrency;
    this.epoch = 0;
    this.entries = new Map();
    this.queue = [];
    this.live = 0;
  }
  get size() {
    return this.entries.size;
  }
  add(key, load, deliver, onError = () => {}) {
    let entry = this.entries.get(key);
    if (entry) {
      // Rebuilt DOM nodes replace the consumer, including while a load is pending.
      entry.consumer = { deliver, onError };
      if (entry.url) this.notify(key, entry);
      return entry.promise;
    }
    if (this.entries.size >= this.limit) return Promise.resolve(false);
    entry = {
      epoch: this.epoch,
      controller: new AbortController(),
      url: null,
      consumer: { deliver, onError },
    };
    entry.promise = new Promise((resolve) => {
      entry.resolve = resolve;
    });
    this.entries.set(key, entry);
    this.queue.push({ key, entry, load });
    this.pump();
    return entry.promise;
  }
  owns(key, entry) {
    return (
      entry.epoch === this.epoch &&
      this.entries.get(key) === entry &&
      !entry.controller.signal.aborted
    );
  }
  notify(key, entry, failed = false, error = null) {
    // Rendering attaches the new node before this runs. Never capture a DOM callback
    // here: only the latest consumer belongs to this entry, and release drops it.
    queueMicrotask(() => {
      if (!this.owns(key, entry)) return;
      const consumer = entry.consumer;
      entry.consumer = null;
      if (failed) this.entries.delete(key);
      if (consumer) {
        if (failed) consumer.onError(error);
        else consumer.deliver(entry.url);
      }
    });
  }
  pump() {
    while (this.live < this.concurrency && this.queue.length) {
      const task = this.queue.shift();
      this.live++;
      this.run(task);
    }
  }
  async run({ key, entry, load }) {
    try {
      const blob = await load(entry.controller.signal);
      const url = this.create(blob);
      if (!this.owns(key, entry)) this.revoke(url);
      else {
        entry.url = url;
        this.notify(key, entry);
      }
    } catch (error) {
      if (this.owns(key, entry)) this.notify(key, entry, true, error);
    } finally {
      // Aborting a request does not free its slot until the loader settles.
      this.live--;
      entry.resolve(true);
      this.pump();
    }
  }
  retain(keys) {
    const keep = new Set(keys);
    for (const [key, entry] of this.entries) {
      if (keep.has(key)) continue;
      this.entries.delete(key);
      entry.consumer = null;
      entry.controller.abort();
      if (entry.url) {
        this.revoke(entry.url);
        entry.url = null;
      }
    }
    this.queue = this.queue.filter(({ key, entry }) => {
      if (this.owns(key, entry)) return true;
      entry.resolve(false);
      return false;
    });
  }
  clear() {
    this.retain([]);
    this.epoch++;
  }
}
// Shared across every settings apply, independent of the active room or page.
// Waiting on an endpoint's prior work consumes no network slot.
const settingsTails = new Map();
const settingsWaiters = [];
let settingsInFlight = 0;
async function withSettingsSlot(work) {
  if (settingsInFlight >= 4) await new Promise((resolve) => settingsWaiters.push(resolve));
  else settingsInFlight++;
  try {
    return await work();
  } finally {
    const next = settingsWaiters.shift();
    if (next)
      next(); // Hand the occupied slot directly to the next waiter.
    else settingsInFlight--;
  }
}
function queueSettings(endpoint, work) {
  const previous = settingsTails.get(endpoint) || Promise.resolve();
  const queued = previous.catch(() => {}).then(() => withSettingsSlot(work));
  settingsTails.set(endpoint, queued);
  const cleanup = () => {
    if (settingsTails.get(endpoint) === queued) settingsTails.delete(endpoint);
  };
  queued.then(cleanup, cleanup);
  return queued;
}
// A complete profile read observes earlier writes in this browser's existing endpoint FIFO.
export function readSettings(target, read) {
  const captured = Object.freeze({ ...target });
  return queueSettings(captured.base, () => read(captured));
}

export async function applySettings(targets, domain, values, send) {
  // Reserve FIFO positions for ALL targets now, including those beyond the first
  // four slots. Later applies cannot jump ahead of an older queued endpoint.
  const captured = targets.map((target) => {
    const room = Object.freeze({ ...target });
    const requested = Object.freeze(copy(typeof values === 'function' ? values(room) : values));
    return { room, requested };
  });
  return Promise.all(
    captured.map(({ room, requested }) =>
      queueSettings(room.base, async () => {
        try {
          // The callback owns schema preflight AND POST, both inside the same FIFO job.
          const response = await send(room, domain, requested);
          const controls = new Map((response.domain?.controls || []).map((c) => [c.key, c.value]));
          const rejected = Object.keys(requested).filter(
            (key) =>
              !response.applied?.includes(key) ||
              (controls.has(key) && controls.get(key) !== requested[key]),
          );
          return {
            base: room.base,
            name: room.name,
            ok: rejected.length === 0,
            rejected,
            response,
          };
        } catch (error) {
          return {
            base: room.base,
            name: room.name,
            ok: false,
            rejected: Object.keys(requested),
            error: error.code || 'request_failed',
            response: error.data,
          };
        }
      }),
    ),
  );
}
export function operationView(op) {
  const results = Array.isArray(op.results) ? op.results : [];
  if (op.type === 'preset' && op.state === 'cancelled')
    return { label: 'Stopped', tone: 'neutral',
      detail: 'Remaining steps stopped. Earlier actions were not undone.', results };
  if (op.type === 'preset' && op.state === 'running' && op.cancelRequested === true)
    return { label: 'Stopping', tone: 'neutral',
      detail: 'Waiting for the current action to stop before stopping remaining steps. Earlier actions will not be undone.', results };
  if (op.type === 'preset' && op.backendMayContinue === true)
    return { label: 'Completion unconfirmed', tone: 'neutral',
      detail: 'The current action may continue. Remaining steps were not run. Check the Portal before running this preset again.', results };
  const partial = results.some((r) => r.ok === false);
  const uncertain = op.installUncertain === true || op.backendMayContinue === true;
  const labels = {
    running: 'Running',
    succeeded: 'Finished',
    failed: 'Failed',
    interrupted: 'Interrupted',
  };
  return {
    label: labels[op.state] || 'Status unavailable',
    tone:
      partial || ['failed', 'interrupted'].includes(op.state)
        ? 'error'
        : op.state === 'succeeded'
          ? 'success'
          : 'neutral',
    detail: uncertain
      ? 'The backend may continue. Wait for confirmed device evidence before retrying.'
      : results.some((r) => r.result === 'dispatched')
        ? 'Command dispatched. Check the Portal screen to confirm its appearance.'
        : results.map((r) => errorCode(r.result)).join(', '),
    results,
  };
}
export function photoFolder(value) {
  // Directory whitespace is part of its identity, including discovered/stored paths.
  let path = String(value || '')
    .replace(/^\/(?:storage\/emulated\/0|storage\/self\/primary|mnt\/sdcard)(?=\/|$)/, '/sdcard')
    .replace(/\/+$/, '');
  if (
    !/^\/sdcard(?:\/|$)/.test(path) ||
    path.length > 1024 ||
    path
      .split('/')
      .slice(2)
      .some((s) => !s || s.startsWith('.') || /^Android$/i.test(s) || /[\\:\x00-\x1f]/.test(s))
  )
    throw new ApiError('invalid_path');
  return path;
}
export const photoPage = (files, page) =>
  files.slice(Math.max(0, page) * 24, (Math.max(0, page) + 1) * 24);
export function photoListing(data, requestedAll) {
  const files = (data.files || []).filter((f) => typeof f.path === 'string').slice(0, 1000);
  return {
    files,
    path: data.path,
    folders: Array.isArray(data.folders) ? data.folders : [],
    all: requestedAll && data.recursive === true,
    truncated: data.truncated === true,
    freeBytes: data.freeBytes,
  };
}
export const copy = (value) => JSON.parse(JSON.stringify(value));
export const widgetKey = (w) =>
  w.kind === 'appwidget' ? `widget-tile:widget:${w.id}` : `widget-tile:custom:${w.customId}`;
export function moveWidget(layout, key, delta) {
  const out = copy(layout),
    index = out.widgets.findIndex((w) => widgetKey(w) === key),
    to = index + delta;
  if (index < 0 || to < 0 || to >= out.widgets.length) return out;
  out.widgets.splice(to, 0, out.widgets.splice(index, 1)[0]);
  const keys = out.widgets.map(widgetKey);
  for (const field of ['gridOrder', 'gridSlots']) {
    const present = new Set(out[field]);
    const ordered = keys.filter((k) => present.has(k));
    out[field] = out[field].map((v) => (keys.includes(v) ? ordered.shift() : v));
  }
  return out;
}
export function resizeWidget(layout, key, x, y) {
  if (!Number.isInteger(x) || !Number.isInteger(y) || x < 1 || x > 4 || y < 1 || y > 4)
    throw new ApiError('invalid_span');
  const out = copy(layout),
    w = out.widgets.find((w) => widgetKey(w) === key);
  if (w) Object.assign(w, { spanX: x, spanY: y, explicitSize: true });
  return out;
}
export function removeWidget(layout, key) {
  const out = copy(layout);
  out.widgets = out.widgets.filter((w) => widgetKey(w) !== key);
  out.gridOrder = out.gridOrder.filter((v) => v !== key);
  out.gridSlots = out.gridSlots.map((v) => (v === key ? null : v));
  return out;
}
export function addWidget(layout, kind, id) {
  const out = copy(layout);
  if (out.widgets.length >= 64 || out.gridOrder.length >= 512) throw new ApiError('layout_full');
  const w = {
    id: 0,
    package: '',
    class: '',
    kind,
    customId: `${kind}:${id}`,
    spanX: 2,
    spanY: 2,
    explicitSize: true,
  };
  out.widgets.push(w);
  const key = widgetKey(w);
  if (out.gridOrder.length) out.gridOrder.push(key);
  if (out.gridSlots.length) {
    const slot = out.gridSlots.findIndex((v) => v === null || v === '');
    if (slot >= 0) out.gridSlots[slot] = key;
    else {
      if (out.gridSlots.length >= 512) throw new ApiError('layout_full');
      out.gridSlots.push(key);
    }
  }
  return out;
}
export function safeDraft(value) {
  if (typeof value !== 'string') return true;
  if (!/^[a-z][a-z0-9+.-]*:\/\//i.test(value)) return true;
  try {
    const u = new URL(value);
    return !u.username && !u.password && !u.search && !u.hash;
  } catch {
    return false;
  }
}
export function captureProfile(schema) {
  const profile = {};
  for (const domain of schema.domains || []) {
    const values = {};
    for (const c of domain.controls || []) {
      if (
        !c.secret &&
        !c.readOnly &&
        ['bool', 'int', 'enum', 'string'].includes(c.type) &&
        safeDraft(c.value)
      )
        values[c.key] = c.value;
    }
    profile[domain.id] = values;
  }
  return profile;
}
export function visibleControls(domain, advanced, query = '') {
  const terms = query.trim().toLowerCase().split(/\s+/).filter(Boolean);
  return (domain.controls || []).filter((c) => {
    if (!advanced && c.tier === 'advanced') return false;
    if (!terms.length) return true;
    const text = [domain.title, c.title, c.help, c.shortHelp, c.key, c.section]
      .map((t) => String(t || ''))
      .join(' ')
      .toLowerCase();
    return terms.every((term) => text.includes(term));
  });
}
export function validateValue(control, value) {
  if (control.readOnly) return false;
  if (control.type === 'bool') return typeof value === 'boolean';
  if (control.type === 'int')
    return Number.isInteger(value) && value >= control.min && value <= control.max;
  if (control.type === 'enum') return (control.options || []).some((o) => o.value === value);
  return (
    control.type === 'string' &&
    typeof value === 'string' &&
    (!control.secret || value.trim().length > 0)
  );
}
export function mixedValue(schemas, domain, key) {
  const values = schemas.map(
    (s) => s.domains?.find((d) => d.id === domain)?.controls?.find((c) => c.key === key)?.value,
  );
  return values.length > 1 && values.some((v) => JSON.stringify(v) !== JSON.stringify(values[0]));
}
// Time-of-day controls retain the integer-minute wire format.
export function timeOfDayMinutes(value) {
  if (typeof value !== 'string' || !/^(?:[01]\d|2[0-3]):[0-5]\d$/.test(value) || value.length !== 5)
    return null;
  return Number(value.slice(0, 2)) * 60 + Number(value.slice(3));
}
export function timeOfDayValue(minutes) {
  if (!Number.isInteger(minutes) || minutes < 0 || minutes > 1439) return '';
  return `${String(Math.floor(minutes / 60)).padStart(2, '0')}:${String(minutes % 60).padStart(2, '0')}`;
}
export const formatTime = (ms) => {
  const sec = Math.max(0, Math.floor((ms || 0) / 1000));
  return `${Math.floor(sec / 60)}:${String(sec % 60).padStart(2, '0')}`;
};
