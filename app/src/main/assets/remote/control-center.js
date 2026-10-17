/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */
import {
  STORAGE,
  normalizeBase,
  sanitizeRoster,
  mergeRoster,
  activeBase,
  captureTargets,
  activeOnlySetting,
  Session,
  ApiError,
  createClient,
  ImagePool,
  ControlCommandQueue,
  applySettings,
  readSettings,
  operationView,
  photoFolder,
  photoPage,
  photoListing,
  copy,
  widgetKey,
  moveWidget,
  resizeWidget,
  removeWidget,
  addWidget,
  captureProfile,
  visibleControls,
  validateValue,
  mixedValue,
  timeOfDayMinutes,
  timeOfDayValue,
  formatTime,
} from './core.mjs';

const $ = (id) => document.getElementById(id);
const page = $('page'),
  sheet = $('sheet'),
  content = $('sheet-content');
const storage = {
  get(key, fallback = null) {
    try {
      return localStorage.getItem(key) ?? fallback;
    } catch {
      return fallback;
    }
  },
  set(key, value) {
    try {
      localStorage.setItem(key, value);
      return true;
    } catch {
      toast(
        'Browser storage is unavailable. Saved pairings may not survive closing this page.',
        true,
      );
      return false;
    }
  },
};
const parse = (text, fallback) => {
  try {
    return JSON.parse(text) ?? fallback;
  } catch {
    return fallback;
  }
};
let roster = sanitizeRoster(parse(storage.get(STORAGE.roster, '[]'), []));
const session = new Session();
let tab = 'home',
  detail = '',
  connected = false,
  connectionState = 'connecting',
  data = {},
  pollTimer = null,
  pollGeneration = 0,
  noticeTimer = null,
  pairDiscoveryTimer = null;
let renderedOperations = null,
  renderedOperationsError = null,
  operationsView = null,
  mediaView = null,
  appIconInventory = null,
  appListView = null,
  navigationView = null,
  headerKey = null,
  searchComposition = null,
  settingsRowView = null;
let scope = 'this',
  selectedTargets = new Set(),
  scopeSchemas = new Map(),
  scopeErrors = [],
  scopeResults = [];
let appMode = 'installed',
  appQuery = '',
  appPage = 0,
  settingsQuery = '',
  category = '',
  advanced = false,
  photosAll = true,
  photoPath = '/sdcard',
  photoPageIndex = 0;
let layoutDraft = null,
  layoutRevision = null,
  sceneDraft = null,
  presetDraft = null,
  upload = null;
const drafts = new Map(),
  busy = new Set(),
  pageReads = new Map(),
  expired = new Map(),
  uploadTransfers = new Map();
const thumbs = new ImagePool(),
  artwork = new ImagePool({ limit: 1 }),
  previewPool = new ImagePool({ limit: 1 });
let photoObservation = null;
const icons = {
  home: '<path d="M3 10 12 3l9 7M5 9v12h14V9M10 21v-7h4v7"/>',
  remote:
    '<rect x="6" y="2" width="12" height="20" rx="3"/><circle cx="12" cy="11" r="3"/><path d="M12 7v8M8 11h8M12 18h.01"/>',
  photos:
    '<rect x="3" y="4" width="18" height="16" rx="1"/><circle cx="8" cy="9" r="1.6"/><path d="m4 18 6-6 4 4 3-4 4 5"/>',
  apps: '<rect x="3" y="3" width="7" height="7" rx="1"/><rect x="14" y="3" width="7" height="7" rx="1"/><rect x="3" y="14" width="7" height="7" rx="1"/><rect x="14" y="14" width="7" height="7" rx="1"/>',
  settings:
    '<path d="M3 6h18M3 12h18M3 18h18"/><circle cx="8" cy="6" r="2"/><circle cx="16" cy="12" r="2"/><circle cx="10" cy="18" r="2"/>',
  scenes: '<path d="m13 2-8 11h6l-1 9 9-12h-6z"/>',
  jarvis:
    '<rect x="9" y="3" width="6" height="11" rx="3"/><path d="M6 10v2a6 6 0 0 0 12 0v-2M12 18v4M9 22h6"/>',
  backup: '<rect x="4" y="5" width="16" height="15" rx="2"/><path d="M4 10h16M9 14h6M7 2h10"/>',
  refresh: '<path d="M20 10a8 8 0 1 0 0 6M20 4v6h-6"/>',
  pointer: '<path d="m5 3 14 10-7 1-3 7z"/>',
  play: '<path d="m9 5 11 7-11 7z"/>',
  pause: '<path d="M9 5v14M15 5v14"/>',
  next: '<path d="m5 5 11 7-11 7zM19 5v14"/>',
  prev: '<path d="m19 5-11 7 11 7zM5 5v14"/>',
  volume: '<path d="M3 9h4l5-4v14l-5-4H3zM16 8a5 5 0 0 1 0 8M19 5a9 9 0 0 1 0 14"/>',
  back: '<path d="m10 5-7 7 7 7M3 12h18"/>',
  upload: '<path d="M12 16V3m-5 5 5-5 5 5M4 16v5h16v-5"/>',
};
function el(tag, attrs = {}, ...children) {
  const node = document.createElement(tag);
  for (const [key, value] of Object.entries(attrs)) {
    if (key === 'class') node.className = value;
    else if (key === 'text') node.textContent = value;
    else if (key.startsWith('on') && typeof value === 'function')
      node.addEventListener(key.slice(2).toLowerCase(), value);
    else if (key === 'value') node.value = value ?? '';
    else if (['checked', 'disabled', 'hidden', 'open'].includes(key)) node[key] = !!value;
    else if (value !== undefined && value !== null) node.setAttribute(key, String(value));
  }
  for (const child of children.flat()) {
    if (child !== null && child !== undefined)
      node.append(child instanceof Node ? child : document.createTextNode(String(child)));
  }
  return node;
}
function glyph(name) {
  const span = el('span');
  span.innerHTML = `<svg viewBox="0 0 24 24" aria-hidden="true">${icons[name] || icons.apps}</svg>`;
  return span.firstChild;
}
const text = (value, cls = 'detail') => el('p', { class: cls, text: value });
const group = (...children) => el('section', { class: 'group' }, ...children);
const sections = (...children) => el('div', { class: 'sections' }, ...children);
const actions = (...children) => el('div', { class: 'actions' }, ...children);
function button(label, onClick, { kind = '', disabled = false, icon = null } = {}) {
  return el(
    'button',
    { class: `button ${kind}`, type: 'button', disabled, onClick },
    icon ? glyph(icon) : null,
    label,
  );
}
function choice(value, options, onChange, label) {
  const select = el('select', { 'aria-label': label, onChange: (e) => onChange(e.target.value) });
  for (const option of options)
    select.append(el('option', { value: option.value, text: option.label }));
  select.value = value ?? '';
  return select;
}
function segmented(value, options, onChange, label) {
  return el(
    'div',
    { class: 'segmented', role: 'group', 'aria-label': label },
    options.map((o) =>
      el(
        'button',
        { type: 'button', 'aria-pressed': o.value === value, onClick: () => onChange(o.value) },
        o.label,
      ),
    ),
  );
}
function field(
  label,
  value,
  onInput,
  { type = 'text', help = '', key = '', placeholder = '', required = false } = {},
) {
  const input = el('input', {
    type,
    value,
    placeholder,
    required,
    'aria-label': label,
    'data-focus': key || label,
    autocomplete: type === 'password' ? 'new-password' : 'off',
    onInput: (e) => onInput(e.target.value),
  });
  return el(
    'label',
    { class: 'field' },
    el('span', { text: label }),
    input,
    help ? text(help) : null,
  );
}
// Replacing a focused input discards the keyboard's unfinished composition.
// Hold only the search render until commitment; page/room/expiry changes bypass it.
function cancelSearchComposition() {
  if (searchComposition?.timer != null) clearTimeout(searchComposition.timer);
  searchComposition = null;
}
function searchField(label, value, onInput, options) {
  const ticket = session.capture();
  let input, unfocusedComposition = false;
  const current = () => session.current(ticket) && input.isConnected &&
    !document.hidden && connectionState !== 'expired';
  const row = field(label, value, (next) => {
    if (!current()) return;
    onInput(next);
    if (!unfocusedComposition || document.activeElement === input) render();
  }, options);
  input = row.querySelector('input');
  const commit = () => {
    const owner = searchComposition;
    if (!owner || owner.input !== input || !current()) return;
    onInput(input.value);
    if (document.activeElement !== input) {
      unfocusedComposition = true;
      cancelSearchComposition();
      return;
    }
    render();
    if (searchComposition !== owner) return;
    if (owner.timer != null) clearTimeout(owner.timer);
    // compositionend may precede the final input event: coalesce both events.
    owner.timer = setTimeout(() => {
      if (searchComposition !== owner) return;
      const shouldRender = owner.pending && current();
      cancelSearchComposition();
      if (shouldRender) render();
    }, 0);
  };
  input.addEventListener('compositionstart', () => {
    if (!current()) return;
    const pending = searchComposition?.input === input && searchComposition.pending;
    cancelSearchComposition();
    searchComposition = { input, ticket, pending: !!pending, timer: null };
  });
  input.addEventListener('compositionend', commit);
  input.addEventListener('blur', () => {
    if (searchComposition?.input !== input) return;
    // Keep a pending pointer activation connected; its own handler renders.
    if (current()) onInput(input.value);
    unfocusedComposition = true;
    cancelSearchComposition();
  });
  input.addEventListener('focus', () => { unfocusedComposition = false; });
  return row;
}
function checkbox(label, checked, onChange) {
  return el(
    'label',
    { class: 'checkbox-label' },
    el('input', { type: 'checkbox', checked, onChange: (e) => onChange(e.target.checked) }),
    label,
  );
}
function row(title, help, control) {
  // A native label forwards padding taps to the checkbox once, respecting disabled.
  if (control?.matches?.('input.switch')) control = el('label', { class: 'switch-hit' }, control);
  return el(
    'div',
    { class: 'setting-row' },
    el(
      'div',
      { class: 'setting-copy' },
      el('strong', { text: title }),
      help ? el('span', { class: 'detail', text: help }) : null,
    ),
    el('div', { class: 'setting-value' }, control),
  );
}
function actionRow(title, help, icon, onClick, enabled = true) {
  return el(
    'button',
    { class: 'action-row', type: 'button', disabled: !enabled, onClick },
    glyph(icon),
    el(
      'span',
      { class: 'action-copy' },
      el('strong', { text: title }),
      el('span', { class: 'detail', text: help }),
    ),
    el('span', { class: 'chevron', 'aria-hidden': true, text: '›' }),
  );
}
function heading(title, description) {
  return el(
    'div',
    { class: 'page-head' },
    el('div', {}, el('h1', { text: title }), description ? text(description) : null),
  );
}
function empty(title, message) {
  return group(el('h3', { text: title }), text(message));
}
function failure(title, error, retry) {
  return group(
    el('h3', { text: title }),
    text(message(error), 'error'),
    button('Retry', retry, { kind: 'outline' }),
  );
}
const supported = (cap) => data.companion?.capabilities?.[cap] === true;
const room = () => session.room;
const draftKey = (key) => `${room()?.base || 'none'}:${key}`;
function draft(key, fallback = '') {
  const id = draftKey(key);
  if (!drafts.has(id)) drafts.set(id, fallback);
  return drafts.get(id);
}
const setDraft = (key, value) => drafts.set(draftKey(key), value);
function toast(message, isError = false) {
  $('notice').textContent = message;
  $('notice').dataset.error = String(isError);
  $('notice').hidden = false;
  clearTimeout(noticeTimer);
  noticeTimer = setTimeout(() => ($('notice').hidden = true), isError ? 8000 : 4500);
}
function message(error) {
  const code = error?.code || error?.error || 'request_failed';
  const messages = {
    unauthorized: 'Pairing expired or was revoked. Pair this Portal again with a fresh PIN.',
    bad_pin: 'That PIN expired or was already used. Show a fresh PIN on the Portal.',
    unreachable: 'Portal unreachable. Check the address and your network, then retry.',
    timeout: 'The Portal took too long to respond. Retry when it is reachable.',
    no_accessibility:
      'Enable Immortal’s accessibility service on the Portal to use gestures and text.',
    no_gestures: 'Enable Immortal’s accessibility service on the Portal to use the touchpad.',
    stale_revision:
      'The Portal changed since this was loaded. Reload and review before saving again.',
    wrong_device: 'This snapshot belongs to another Portal. Choose the Portal that exported it.',
    busy: 'Another operation is running on this Portal. Check Activity before retrying.',
    install_state_uncertain:
      'An installer may still be working. Check Activity for confirmed device evidence.',
    photos_unavailable:
      'Allow photo imports and grant file access in Control from your phone on the Portal.',
    collision: 'A photo with this filename already exists. Rename the file and retry.',
    invalid_path:
      'Choose a public folder under /sdcard. Private Android folders and hidden paths cannot be used.',
    aborted: 'Request cancelled.',
    invalid_value: 'This value does not match the Portal’s setting constraints.',
    invalid_album_link: 'Enter a complete public iCloud, Google Photos or Synology Photos album link.',
    invalid_web_url: 'Enter a complete http:// or https:// address with a host.',
    invalid_source_field: 'Review the photo source fields and enter text values before saving.',
    partial_apply: 'Some values were rejected. Review the per-room results.',
    active_portal_only: 'This control applies to the active Portal only.',
    connect_required: 'Choose or pair a Portal first.',
    volume_fixed: 'Volume is fixed on this Portal.',
    invalid_image: 'This image could not be read. Use JPEG, PNG, WebP or GIF.',
    upload_size_limit: 'Each image must be 20 MiB or smaller.',
    incomplete_upload:
      'Transfer was interrupted. Refresh the library to check whether the photo arrived.',
    folder_not_found: 'That folder does not exist. Choose another public folder.',
    invalid_response: 'The Portal returned an unreadable response. Try refreshing.',
    invalid_address: 'Enter a Portal address or its pairing link, without a username or password.',
  };
  return (
    messages[code] ||
    `The Portal reported ${String(code).replace(/_/g, ' ')}. Review the settings and retry.`
  );
}
function persistRoster() {
  for (const base of uploadTransfers.keys())
    if (!roster.some((target) => target.base === base)) uploadTransfers.delete(base);
  storage.set(STORAGE.roster, JSON.stringify(roster));
  const index = roster.findIndex((r) => r.base === room()?.base);
  storage.set(STORAGE.active, String(Math.max(0, index)));
  storage.set(STORAGE.selected, room()?.base || '');
}
function updateHeader() {
  const active = room();
  $('refresh-button').disabled = !active;
  const key = JSON.stringify([
    active?.base, active?.name, connected, connectionState, data.companion?.model,
  ]);
  if (headerKey === key) return;
  headerKey = key;
  $('room-button').replaceChildren(
    el('span', { class: 'room-label', text: active?.name || 'Choose a Portal' }),
    el('span', { 'aria-hidden': true, text: '⌄' }),
  );
  const status = $('connection');
  status.replaceChildren();
  if (!active) return;
  const labels = {
    connecting: 'Connecting…',
    connected: 'Connected',
    offline: 'Offline · Retry when reachable',
    expired: 'Pair again',
    legacy: 'Connected · Update Immortal for management controls',
  };
  status.append(
    el('span', { class: `status-dot ${connected ? 'connected' : ''}` }),
    labels[connectionState] || 'Offline',
  );
  if (data.companion?.model) status.append(el('span', { text: data.companion.model }));
  if (['offline', 'expired'].includes(connectionState))
    status.append(
      el(
        'button',
        {
          class: 'text-button',
          onClick: () => (connectionState === 'expired' ? pairDialog(active.base) : refresh()),
        },
        connectionState === 'expired' ? 'Pair again' : 'Retry',
      ),
    );
}
const api = createClient({
  fetch: (...args) => fetch(...args),
  onUnauthorized: (target) => {
    expired.set(target.base, target.token);
    if (room()?.base === target.base && room()?.token === target.token) {
      connected = false;
      connectionState = 'expired';
      abortReads();
      updateHeader();
      stopPolling();
      render();
    }
  },
});
function abortReads() {
  for (const controller of pageReads.values()) controller.abort();
  pageReads.clear();
}
async function read(
  key,
  path,
  accept,
  { renderAfter = true, renderWhen = null, renderWith = render, timeout = 12000 } = {},
) {
  if (!room() || document.hidden || connectionState === 'expired') return;
  pageReads.get(key)?.abort();
  const controller = new AbortController(),
    ticket = session.read(key);
  pageReads.set(key, controller);
  try {
    const response = await api(ticket.room, path, { signal: controller.signal, timeout });
    if (!session.current(ticket) || document.hidden) return;
    accept(response);
    delete data[`${key}Error`];
    if (renderAfter && (!renderWhen || renderWhen())) renderWith();
    return response;
  } catch (error) {
    if (!session.current(ticket) || error.code === 'aborted') return;
    data[`${key}Error`] = error;
    if (key === 'status' && error.status !== 404 && connectionState !== 'expired') {
      connected = false;
      connectionState = 'offline';
      updateHeader();
    }
    if (renderAfter && (!renderWhen || renderWhen())) renderWith();
  } finally {
    if (pageReads.get(key) === controller) pageReads.delete(key);
  }
}
async function status() {
  return read(
    'status',
    '/remote/companion',
    (response) => {
      const firstStatus = !data.companion;
      data.companion = response;
      connected = true;
      connectionState = 'connected';
      if (response.name && room()) {
        roster = roster.map((r) => (r.base === room().base ? { ...r, name: response.name } : r));
        session.room = Object.freeze({ ...session.room, name: response.name });
        persistRoster();
      }
      updateHeader();
      if (
        firstStatus &&
        (detail || tab === 'photos' || (tab === 'apps' && appMode === 'catalog'))
      ) {
        queueMicrotask(() => loadVisible());
      }
    },
    { renderAfter: true },
  );
}
async function mediaRead() {
  return read(
    'media',
    '/remote/nowplaying',
    (response) => {
      data.media = response.np;
      if (connectionState !== 'expired' && !data.companion) {
        connected = true;
        connectionState = data.statusError?.status === 404 ? 'legacy' : 'connected';
        updateHeader();
      }
    },
    { renderAfter: true, renderWith: renderMedia },
  );
}
function stopPolling() {
  pollGeneration++;
  clearTimeout(pollTimer);
  pollTimer = null;
}
function startPolling() {
  stopPolling();
  if (!room() || document.hidden || connectionState === 'expired') return;
  const captured = session.capture();
  const generation = pollGeneration;
  // Aborted reads can settle after navigation, visibility changes or a same-page refresh.
  const current = () => generation === pollGeneration && session.current(captured) &&
    !document.hidden && connectionState !== 'expired';
  pollTimer = setTimeout(
    async () => {
      if (!current()) return;
      if ((tab === 'home' && !detail) || tab === 'remote') await mediaRead();
      if (!current()) return;
      if (detail === 'activity' || (tab === 'apps' && supported('operations')))
        await read('operations', '/remote/operations', acceptOperations, {
          renderAfter: !document.activeElement?.matches('input,textarea,select'),
          renderWith: renderOperations,
          // Use rendered state so changes deferred by focused input are still shown.
          renderWhen: () =>
            JSON.stringify(data.operations) !== renderedOperations ||
            JSON.stringify(data.operationsError) !== renderedOperationsError,
        });
      if (!current()) return;
      if (connectionState === 'offline' || !data.companion) await status();
      if (current()) startPolling();
    },
    connectionState === 'offline' ? 30000 : 4000,
  );
}
function go(next, sub = '') {
  tab = next;
  detail = sub;
  abortReads();
  session.enter(`${next}:${sub}`);
  stopPolling();
  thumbs.clear();
  appIconInventory = null;
  appListView = null;
  settingsRowView = null;
  artwork.clear();
  mediaView = null;
  previewPool.clear();
  closeSheet();
  scopeResults = [];
  render();
  loadVisible();
  startPolling();
  window.scrollTo({ top: 0, behavior: 'instant' });
}
function selectRoom(base) {
  const target = roster.find((r) => r.base === base) || null;
  abortReads();
  stopPolling();
  if (upload?.running) cancelUpload(upload, 'room_changed');
  upload = uploadTransfers.get(target?.base) || null;
  session.select(target);
  data = {};
  connected = false;
  connectionState = target
    ? expired.get(target.base) === target.token
      ? 'expired'
      : 'connecting'
    : 'offline';
  scope = 'this';
  selectedTargets = new Set(target ? [target.base] : []);
  scopeSchemas.clear();
  layoutDraft = null;
  sceneDraft = null;
  presetDraft = null;
  photosAll = true;
  photoPath = '/sdcard';
  photoPageIndex = 0;
  appPage = 0;
  persistRoster();
  go(tab);
  updateHeader();
  if (target && connectionState !== 'expired') {
    status().then(() => {
      if (!data.companion && data.statusError?.status !== 404 && connectionState !== 'expired') {
        connected = false;
        connectionState = 'offline';
        updateHeader();
      }
    });
    pullRoster(session.capture());
  }
}
async function pullRoster(ticket) {
  try {
    const response = await api(ticket.room, '/remote/roster');
    if (!session.current(ticket, { page: false })) return;
    roster = mergeRoster(roster, response.roster);
    persistRoster();
  } catch {
    /* Connection state is handled by status/401. */
  }
}
async function syncRoster(targets = roster) {
  const captured = captureTargets(targets, 'all', null);
  const body = { roster: roster.map((r) => ({ ...r })) };
  const results = [];
  for (let i = 0; i < captured.length; i += 4) {
    const batch = await Promise.all(
      captured.slice(i, i + 4).map(async (target) => {
        try {
          await api(target, '/remote/roster', { body });
          return { name: target.name, ok: true };
        } catch (error) {
          return { name: target.name, ok: false, error };
        }
      }),
    );
    results.push(...batch);
  }
  if (results.some((r) => !r.ok))
    toast(
      `Roster saved in this browser. Sync failed for ${results
        .filter((r) => !r.ok)
        .map((r) => r.name)
        .join(', ')}.`,
      true,
    );
}
function openSheet(title, body, onClose = null) {
  previewPool.clear();
  $('sheet-title').textContent = title;
  content.replaceChildren(body);
  const footer = $('sheet-actions');
  footer.replaceChildren();
  // Keep actions visible while form/list content scrolls, including above a phone keyboard.
  const joined = body.querySelector('.dialog-actions');
  if (joined) footer.append(joined);
  else for (const primary of body.querySelectorAll('button.button.primary')) footer.append(primary);
  footer.hidden = footer.children.length === 0;
  sheet.onclose = () => {
    previewPool.clear();
    clearTimeout(pairDiscoveryTimer);
    pairDiscoveryTimer = null;
    if (onClose) onClose();
  };
  if (!sheet.open) sheet.showModal();
}
function closeSheet() {
  if (sheet.open) sheet.close();
}
$('sheet-close').onclick = closeSheet;
sheet.addEventListener('click', (event) => {
  if (event.target === sheet) {
    const box = sheet.getBoundingClientRect();
    if (
      event.clientX < box.left ||
      event.clientX > box.right ||
      event.clientY < box.top ||
      event.clientY > box.bottom
    )
      closeSheet();
  }
});
async function confirmAction(
  title,
  description,
  action,
  label = 'Confirm',
  ticket = session.capture(),
) {
  let resolve;
  const answer = new Promise((r) => (resolve = r));
  openSheet(
    title,
    sections(
      text(description),
      el(
        'div',
        { class: 'dialog-actions' },
        button(
          'Cancel',
          () => {
            resolve(false);
            closeSheet();
          },
          { kind: 'outline' },
        ),
        button(
          label,
          () => {
            resolve(true);
            closeSheet();
          },
          { kind: 'primary' },
        ),
      ),
    ),
    () => resolve(false),
  );
  if ((await answer) && session.current(ticket)) return action(ticket);
}
function roomsDialog() {
  openSheet(
    'Your Portals',
    sections(
      roster.length
        ? el(
            'div',
            {},
            roster.map((target) =>
              el(
                'div',
                { class: 'room-entry' },
                el(
                  'div',
                  { class: 'action-copy' },
                  el('strong', { text: target.name }),
                  text(target.base),
                ),
                button(
                  target.base === room()?.base ? 'Selected' : 'Select',
                  () => selectRoom(target.base),
                  { kind: 'outline' },
                ),
                el(
                  'button',
                  {
                    class: 'icon-button',
                    'aria-label': `Forget ${target.name}`,
                    onClick: () => forgetRoom(target),
                  },
                  '×',
                ),
              ),
            ),
          )
        : text('Pair a Portal to start.'),
      button('Add a Portal', () => pairDialog(), { kind: 'primary', icon: 'remote' }),
      text('Pairings stay in this browser and sync to your paired Portals.'),
    ),
  );
}
function forgetRoom(target) {
  const ticket = session.capture();
  confirmAction(
    `Forget ${target.name}?`,
    'Remove the saved pairing from this browser and sync the remaining roster. You can pair again with a fresh PIN.',
    async () => {
      const survivors = roster.filter((r) => r.base !== target.base);
      roster = survivors;
      if (room()?.base === target.base) selectRoom(roster[0]?.base);
      else persistRoster();
      closeSheet();
      await syncRoster();
    },
    'Forget',
    ticket,
  );
}
function pairDialog(address = location.origin, pinValue = '') {
  let addressDraft = address,
    pin = pinValue,
    pairing = false;
  const error = text('');
  const peers = el('div', { class: 'sections' });
  const form = el(
    'form',
    {},
    field('Portal address or pairing link', addressDraft, (v) => (addressDraft = v), {
      type: 'text',
      key: 'pair-address',
      help: 'Local addresses default to port 8723. Use the same network or your existing VPN.',
    }),
    field(
      'Six-digit PIN',
      pin,
      (v) => {
        pin = v.replace(/\D/g, '').slice(0, 6);
      },
      {
        type: 'password',
        key: 'pair-pin',
        help: 'In Immortal, open Control from your phone and show a fresh PIN.',
      },
    ),
    error,
    button('Pair Portal', submit, { kind: 'primary' }),
    peers,
  );
  form.addEventListener('submit', (event) => {
    event.preventDefault();
    submit();
  });
  openSheet('Pair a Portal', form);
  const originTicket = session.capture();
  async function discover() {
    if (!sheet.open || pairing || !room() || !session.current(originTicket)) return;
    try {
      const response = await api(originTicket.room, '/remote/devices');
      if (!sheet.open || !session.current(originTicket)) return;
      peers.replaceChildren(
        text('Discovered on your network'),
        ...(response.devices || []).map((peer) =>
          button(
            peer.name || 'Portal',
            () => {
              try {
                const host = peer.host.includes(':') ? `[${peer.host}]` : peer.host;
                addressDraft = normalizeBase(`http://${host}:${peer.port || 8723}`);
                form.querySelector('[data-focus="pair-address"]').value = addressDraft;
              } catch {
                error.textContent = message(new ApiError('invalid_address'));
              }
            },
            { kind: 'outline' },
          ),
        ),
      );
    } catch {
      /* Discovery is optional; manual pairing remains available. */
    }
    if (sheet.open) pairDiscoveryTimer = setTimeout(discover, 10000);
  }
  discover();
  async function submit() {
    if (pairing) return;
    try {
      const candidate = new URL(
        /^https?:\/\//i.test(addressDraft) ? addressDraft : 'http://' + addressDraft,
      );
      const match = candidate.hash.match(/(?:^#|&)pin=(\d{6})(?:&|$)/);
      if (match && !pin) pin = match[1];
      const base = normalizeBase(addressDraft);
      if (!/^\d{6}$/.test(pin)) throw new ApiError('bad_pin');
      pairing = true;
      error.textContent = 'Pairing…';
      const response = await api({ base }, '/remote/pair', { body: { pin } });
      if (typeof response.token !== 'string' || !response.token)
        throw new ApiError('invalid_response');
      if (!sheet.open || !session.current(originTicket)) return;
      const paired = { base, token: response.token, name: response.name || 'Portal' };
      roster = sanitizeRoster([paired, ...roster.filter((r) => r.base !== base)]);
      expired.delete(base);
      closeSheet();
      selectRoom(base);
      await pullRoster(session.capture());
      await syncRoster();
      toast('Portal paired.');
    } catch (err) {
      error.className = 'error';
      error.textContent = message(err);
    } finally {
      pairing = false;
      pin = '';
      const input = form.querySelector('[data-focus="pair-pin"]');
      if (input) input.value = '';
    }
  }
}
$('room-button').onclick = roomsDialog;
$('refresh-button').onclick = () => refresh();
function refresh() {
  abortReads();
  data[`${detail || tab}Error`] = null;
  if (!room()) return;
  status();
  loadVisible(true);
  startPolling();
}
const detailCapabilities = {
  layout: 'layout',
  scenes: 'scenes',
  jarvis: 'jarvis',
  snapshots: 'snapshot',
  activity: 'operations',
};
function loadVisible(force = false) {
  if (!room() || document.hidden || connectionState === 'expired') return;
  if (detailCapabilities[detail] && !supported(detailCapabilities[detail])) return;
  if (!detail && (tab === 'home' || tab === 'remote')) mediaRead();
  if (tab === 'home' && detail === 'layout') loadLayout();
  else if (tab === 'home' && detail === 'scenes') loadScenes();
  else if (tab === 'home' && detail === 'jarvis')
    read('jarvis', '/remote/jarvis', (r) => (data.jarvis = r.jarvis));
  else if (tab === 'home' && detail === 'activity')
    read('operations', '/remote/operations', acceptOperations);
  else if (tab === 'home' && detail === 'snapshots')
    read('snapshot', '/remote/snapshot', (r) => (data.snapshot = r));
  else if (detail === 'presets') {
    read('presets', '/remote/presets', (r) => (data.presets = r.presets || []));
    read('apps', '/remote/apps', (r) => (data.apps = r.apps || []));
  } else if (tab === 'photos' && !detail) {
    loadPhotos();
  } else if (detail === 'sources') {
    loadSources();
    loadSettings();
  } else if (tab === 'apps') {
    loadApps();
    if (supported('operations')) read('operations', '/remote/operations', acceptOperations);
  } else if (tab === 'settings') {
    loadSettings();
  }
}
function renderNavigation() {
  if (!navigationView) {
    // Navigation owns no room snapshot: each action uses the current session.
    navigationView = ['home', 'remote', 'photos', 'apps', 'settings'].map((name) => ({
      name,
      node: el('button', { onClick: () => go(name) }, glyph(name),
        name[0].toUpperCase() + name.slice(1)),
    }));
    $('navigation').replaceChildren(...navigationView.map(({ node }) => node));
  }
  for (const { name, node } of navigationView) {
    if (tab === name) {
      if (node.getAttribute('aria-current') !== 'page') node.setAttribute('aria-current', 'page');
    } else if (node.getAttribute('aria-current') != null) {
      node.removeAttribute('aria-current');
    }
  }
}
function render() {
  if (searchComposition) {
    const owner = searchComposition;
    if (session.current(owner.ticket) && owner.input.isConnected &&
        document.activeElement === owner.input && !document.hidden &&
        connectionState !== 'expired') {
      owner.pending = true;
      return;
    }
    cancelSearchComposition();
  }
  stopPhotoObservation();
  operationsView = null,
  mediaView = null;
  updateHeader();
  renderNavigation();
  page.setAttribute('aria-busy', 'false');
  if (!room()) {
    page.replaceChildren(
      sections(
        group(
          el(
            'div',
            { class: 'pair-intro' },
            el('div', { class: 'brand-mark', 'aria-hidden': true, text: '∞' }),
            el('h1', { text: 'Your Portal, within reach.' }),
            text(
              'Pair once to control your home screen, photos, apps, and settings from your browser.',
            ),
          ),
          button('Pair a Portal', () => pairDialog(), { kind: 'primary', icon: 'remote' }),
          text('Open Control from your phone on your Portal to show a PIN.'),
        ),
      ),
    );
    return;
  }
  if (connectionState === 'expired') {
    settingsRowView = null;
    page.replaceChildren(
      sections(
        heading('Pair again', 'This Portal revoked or expired this browser pairing.'),
        group(
          text(
            'Open Control from your phone on the Portal and show a fresh PIN. Your other room pairings remain available.',
          ),
          button('Pair again', () => pairDialog(room().base), { kind: 'primary' }),
        ),
      ),
    );
    return;
  }
  if (detailCapabilities[detail] && data.companion && !supported(detailCapabilities[detail])) {
    page.replaceChildren(
      sections(
        heading('Control unavailable', 'This Portal does not advertise this feature.'),
        group(
          text('Update Immortal or finish the required setup on the Portal, then refresh.'),
          button('Back to Home', () => go('home'), { kind: 'outline' }),
        ),
      ),
    );
    return;
  }
  const active = document.activeElement;
  const focus = active?.dataset?.focus;
  const range = active?.tagName === 'INPUT' ? [active.selectionStart, active.selectionEnd] : null;
  const back = detail
    ? el(
        'button',
        { class: 'back-button', onClick: () => go(tab) },
        glyph('back'),
        `Back to ${tab[0].toUpperCase() + tab.slice(1)}`,
      )
    : null;
  let body;
  if (detail === 'sources') body = sourcesPage();
  else if (detail === 'profiles') body = profilesPage();
  else if (detail === 'presets') body = presetsPage();
  else if (tab === 'home') body = homePage();
  else if (tab === 'remote') body = remotePage();
  else if (tab === 'photos') body = photosPage();
  else if (tab === 'apps') body = appsPage();
  else body = settingsPage();
  page.replaceChildren(...[back, body].filter(Boolean));
  if (tab === 'apps' || detail === 'activity') {
    rememberOperations();
  }
  if (focus) {
    const restore = [...page.querySelectorAll('[data-focus]')].find(
      (n) => n.dataset.focus === focus,
    );
    if (restore) {
      restore.focus({ preventScroll: true });
      if (range && restore.type !== 'number')
        try {
          restore.setSelectionRange(...range);
        } catch {}
    }
  }
  if ((tab === 'home' && !detail) || tab === 'remote') renderMedia();
}
async function mutate(
  key,
  path,
  body,
  {
    target = room(),
    ticket = session.capture(),
    success = 'Saved.',
    refreshPage = false,
    method,
    timeout,
    onError,
  } = {},
) {
  const busyKey = `${target?.base}:${key}`;
  if (busy.has(busyKey)) return;
  busy.add(busyKey);
  try {
    const response = await api(target, path, { body, method, timeout });
    if (!session.current(ticket)) return null;
    if (response.applied === false || response.dispatched === false)
      throw new ApiError(response.applied === false ? 'no_editable_field' : 'not_dispatched');
    if (success) toast(success);
    if (refreshPage) loadVisible();
    return response;
  } catch (error) {
    if (session.current(ticket)) {
      onError?.(error);
      toast(message(error), true);
      if (error.data?.applied)
        scopeResults = (error.data.applied || []).map((a) => ({
          name: a.domain,
          ok: a.ok,
          error: a.error,
        }));
    }
    return null;
  } finally {
    busy.delete(busyKey);
  }
}
function dispatchKey(action) {
  return mutate(
    `key:${action}`,
    '/remote/key',
    { action },
    { success: 'Command dispatched. Check the Portal screen.' },
  );
}
function homePage() {
  if (detail === 'layout') return layoutPage();
  if (detail === 'scenes') return scenesPage();
  if (detail === 'jarvis') return jarvisPage();
  if (detail === 'snapshots') return snapshotsPage();
  if (detail === 'activity') return activityPage();
  return sections(
    heading('Home', 'A little closer to every room.'),
    el(
      'div',
      { class: 'columns' },
      sections(
        group(
          el('h2', { text: 'On your Portal' }),
          text(
            data.companion
              ? `${room().name} · Immortal ${data.companion.app?.versionName || ''}`
              : 'Home controls connect directly to Immortal.',
          ),
          actions(
            button('Start slideshow', () => dispatchKey('screensaver'), {
              kind: 'primary',
              icon: 'photos',
            }),
            button('Show Home', () => dispatchKey('home'), { kind: 'outline', icon: 'home' }),
          ),
          data.companion?.presence
            ? text(
                `Screen: ${data.companion.presence.screen} · Presence: ${data.companion.presence.presence}`,
              )
            : null,
        ),
        group(
          actionRow(
            'Home layout',
            'Arrange and resize your widgets.',
            'apps',
            () => go('home', 'layout'),
            supported('layout'),
          ),
          actionRow(
            'Scenes',
            'Everyday routines and schedules.',
            'scenes',
            () => go('home', 'scenes'),
            supported('scenes'),
          ),
          actionRow(
            'Jarvis',
            'Model and wake settings.',
            'jarvis',
            () => go('home', 'jarvis'),
            supported('jarvis'),
          ),
          actionRow(
            'Snapshots',
            'Save and restore your setup.',
            'backup',
            () => go('home', 'snapshots'),
            supported('snapshot'),
          ),
          actionRow(
            'Activity',
            'Progress and results.',
            'refresh',
            () => go('home', 'activity'),
            supported('operations'),
          ),
          actionRow(
            'Saved presets',
            'Your existing app, keyboard and configuration macros.',
            'remote',
            () => go('home', 'presets'),
          ),
        ),
      ),
      sections(
        el('div', { id: 'media-panel' }),
        !data.companion
          ? empty(
              'Management controls',
              data.statusError?.status === 404
                ? 'Update Immortal on this Portal to enable layouts, scenes, snapshots and the catalog.'
                : 'Connecting to the Portal’s management API…',
            )
          : null,
      ),
    ),
  );
}
function remotePage() {
  const pad = el(
    'div',
    {
      class: 'pad',
      tabindex: 0,
      role: 'application',
      'aria-label': 'Portal touchpad. Drag to move the pointer. Tap or press Enter to click.',
    },
    glyph('pointer'),
    el('strong', { text: 'Touchpad' }),
    text('Drag to move · Tap to click'),
  );
  const tap = setupPad(pad);
  const keyboard = group(
    el('h2', { text: 'Keyboard' }),
    text('Select a text field on the Portal, then send text.'),
    field('Text for the focused field', draft('keyboard'), (v) => setDraft('keyboard', v), {
      key: 'keyboard',
    }),
    actions(
      button('Send text', () => sendText('set'), { kind: 'primary' }),
      button('Append', () => sendText('append'), { kind: 'outline' }),
      button('Backspace', () => sendText('backspace'), { kind: 'outline' }),
      button(
        'Clear field',
        () =>
          confirmAction(
            'Clear the focused field?',
            'This clears the selected text field on the current Portal.',
            () => sendText('clear'),
            'Clear',
          ),
        { kind: 'outline' },
      ),
    ),
  );
  return sections(
    heading('Remote', 'Control the Portal without crossing the room.'),
    el(
      'div',
      { class: 'columns remote-columns' },
      sections(
        group(
          el(
            'div',
            { class: 'nav-keys' },
            button('Back', () => dispatchKey('back'), { icon: 'back' }),
            button('Home', () => dispatchKey('home'), { icon: 'home' }),
            button('Apps', () => dispatchKey('apps'), { icon: 'apps' }),
          ),
          pad,
          el(
            'div',
            { class: 'nav-keys' },
            button('Scroll up', () =>
              mutate('scroll', '/remote/scroll', { dir: 'up' }, { success: '' }),
            ),
            button('Tap', tap),
            button('Scroll down', () =>
              mutate('scroll', '/remote/scroll', { dir: 'down' }, { success: '' }),
            ),
          ),
          text('Gestures and text require Immortal’s accessibility service on the Portal.'),
        ),
        keyboard,
      ),
      sections(
        el('div', { id: 'media-panel' }),
        group(
          actionRow('Start slideshow', 'Open the photo frame on the Portal.', 'photos', () =>
            dispatchKey('screensaver'),
          ),
          actionRow('Saved presets', 'Run or edit your existing macros.', 'scenes', () =>
            go('remote', 'presets'),
          ),
        ),
      ),
    ),
  );
}
function sendText(mode) {
  return mutate(
    'text',
    '/remote/text',
    { mode, text: ['set', 'append'].includes(mode) ? draft('keyboard') : '' },
    { success: 'Text command sent to the focused field.' },
  );
}
function setupPad(pad) {
  let tracking = null,
    dx = 0,
    dy = 0,
    timer = null,
    sending = null;
  const captured = session.capture();
  const visibility = playbackVisibilityGeneration;
  const current = () => pad.isConnected && session.current(captured) &&
    visibility === playbackVisibilityGeneration && !document.hidden;
  const send = () => {
    clearTimeout(timer);
    timer = null;
    if (!current()) {
      dx = dy = 0;
      return Promise.resolve(false);
    }
    // Keep later deltas while the earlier request is still applying on the Portal.
    if (sending) return sending;
    const x = dx,
      y = dy;
    dx = dy = 0;
    if (!x && !y) return Promise.resolve(true);
    sending = api(captured.room, '/remote/cursor', {
        body: { dx: x * 1.9, dy: y * 1.9 },
        timeout: 2500,
      }).then(() => true, (error) => {
        if (current() && error.code !== 'aborted') toast(message(error), true);
        return false;
      }).finally(() => {
        sending = null;
        if (!current()) dx = dy = 0;
        else if (dx || dy) timer = setTimeout(send, 35);
      });
    return sending;
  };
  const click = async () => {
    clearTimeout(timer);
    timer = null;
    // A click must use the acknowledged final position, never overtake its move.
    while (current() && (sending || dx || dy)) {
      if (!await send()) return;
    }
    if (current()) return mutate('tap', '/remote/tap', undefined, {
      method: 'POST', ticket: captured, target: captured.room, success: '',
    });
  };
  pad.addEventListener('pointerdown', (event) => {
    if (event.button !== 0 || !current()) return;
    pad.setPointerCapture(event.pointerId);
    tracking = {
      id: event.pointerId,
      x: event.clientX,
      y: event.clientY,
      startX: event.clientX,
      startY: event.clientY,
      at: Date.now(),
      moved: false,
    };
  });
  pad.addEventListener('pointermove', (event) => {
    if (!tracking || tracking.id !== event.pointerId || !current()) return;
    dx += event.clientX - tracking.x;
    dy += event.clientY - tracking.y;
    tracking.x = event.clientX;
    tracking.y = event.clientY;
    if (Math.hypot(event.clientX - tracking.startX, event.clientY - tracking.startY) > 6)
      tracking.moved = true;
    if (!timer && !sending) timer = setTimeout(send, 35);
  });
  pad.addEventListener('pointerup', (event) => {
    if (!tracking || tracking.id !== event.pointerId) return;
    const tapped = !tracking.moved && Date.now() - tracking.at < 450;
    tracking = null;
    if (tapped) click();
  });
  pad.addEventListener('pointercancel', () => {
    tracking = null;
    dx = dy = 0;
    clearTimeout(timer);
    timer = null;
  });
  pad.addEventListener('keydown', (event) => {
    if (!current()) return;
    if (event.key === 'Enter' || event.key === ' ') {
      event.preventDefault();
      click();
    } else {
      const delta = {
        ArrowUp: [0, -25],
        ArrowDown: [0, 25],
        ArrowLeft: [-25, 0],
        ArrowRight: [25, 0],
      }[event.key];
      if (delta) {
        event.preventDefault();
        dx += delta[0];
        dy += delta[1];
        send();
      }
    }
  });
  return click;
}
function mediaButton(label, icon, onClick, primary = false) {
  return el(
    'button',
    {
      class: `icon-button${primary ? ' primary' : ''}`,
      type: 'button',
      'aria-label': label,
      onClick,
    },
    glyph(icon),
  );
}
function renderMedia() {
  const mount = $('media-panel');
  if (!mount) return;
  const np = data.media;
  if (!np) {
    mediaView = null;
    mount.replaceChildren(
      data.mediaError
        ? failure('Playback unavailable', data.mediaError, mediaRead)
        : empty('Playback', 'Loading media and volume…'),
    );
    return;
  }
  const ticket = session.capture();
  const key = JSON.stringify([
    ticket.generation, ticket.pageGeneration, !!np.active,
    !!np.active && np.durationMs > 0, Number.isInteger(np.volume?.max),
  ]);
  if (!mediaView || mediaView.mount !== mount || mediaView.key !== key) {
    const view = { mount, key, artKey: null, playing: null };
    const groupNode = group(el('h2', { text: 'Playback' }));
    view.art = el('img', { class: 'art', alt: '', hidden: true });
    view.fallback = el('span', { class: 'media-fallback', 'aria-hidden': 'true' }, glyph('volume'));
    view.title = el('strong');
    view.subtitle = text('');
    groupNode.append(el('div', { class: 'media-header' }, view.art, view.fallback,
      el('div', { class: 'media-title' }, view.title, view.subtitle)));
    if (np.active) {
      view.play = mediaButton('Pause', 'pause', () => mediaCommand('playpause'), true);
      groupNode.append(el('div', { class: 'media-controls' },
        mediaButton('Previous', 'prev', () => mediaCommand('prev')), view.play,
        mediaButton('Next', 'next', () => mediaCommand('next'))));
      if (np.durationMs > 0) {
        view.seek = el('input', { type: 'range', min: 0, max: np.durationMs,
          step: 1000, 'aria-label': 'Playback position' });
        view.current = el('span');
        view.duration = el('span');
        view.seek.oninput = () => (view.current.textContent = formatTime(Number(view.seek.value)));
        view.seek.onchange = () => mediaCommand('seek', Math.min(Number(view.seek.value), data.media.durationMs));
        groupNode.append(view.seek, el('div', { class: 'timeline-labels' }, view.current, view.duration));
      }
    }
    if (Number.isInteger(np.volume?.max)) {
      view.volume = el('input', { type: 'range', min: 0, max: np.volume.max,
        step: 1, 'aria-label': 'Portal music volume' });
      view.level = el('output');
      view.volume.oninput = () => (view.level.textContent = `${view.volume.value} / ${view.volume.getAttribute('max')}`);
      view.volume.onchange = () => volumeCommand('set', Math.min(Number(view.volume.value), data.media.volume.max));
      view.mute = button('Mute', () => volumeCommand('mute'));
      view.down = button('Volume −', () => volumeCommand('down'));
      view.up = button('Volume +', () => volumeCommand('up'));
      view.fixed = text('This Portal reports fixed volume.');
      groupNode.append(el('div', { class: 'volume-row' }, glyph('volume'), view.volume, view.level),
        actions(view.mute, view.down, view.up), view.fixed);
    }
    view.error = text('', 'media-error error');
    view.error.hidden = true;
    groupNode.append(view.error);
    mount.replaceChildren(groupNode);
    mediaView = view;
  }
  const view = mediaView;
  const setText = (node, value) => {
    if (node.textContent !== value) node.textContent = value;
  };
  const setBool = (node, property, value) => {
    if (node[property] !== value) node[property] = value;
  };
  const setMax = (node, value) => {
    if (node.getAttribute('max') !== String(value)) node.setAttribute('max', String(value));
  };
  const setRangeValue = (node, value) => {
    // Compare with the native range's clamped, step-aligned value.
    const min = Number(node.getAttribute('min'));
    const max = Number(node.getAttribute('max'));
    const step = Number(node.getAttribute('step'));
    const bounded = Math.max(min, Math.min(max, value));
    const aligned = Math.min(min + Math.floor((max - min) / step) * step,
      min + Math.round((bounded - min) / step) * step);
    if (Number(node.value) !== aligned) node.value = aligned;
  };
  setText(view.title, np.active ? np.title || 'Playing on Portal' : 'Nothing playing');
  setText(view.subtitle, np.active ? [np.artist, np.album].filter(Boolean).join(' · ')
    : 'Volume controls are available independently.');
  if (view.play && view.playing !== !!np.playing) {
    view.playing = !!np.playing;
    view.play.setAttribute('aria-label', view.playing ? 'Pause' : 'Play');
    view.play.replaceChildren(glyph(view.playing ? 'pause' : 'play'));
  }
  if (view.seek) {
    setBool(view.seek, 'disabled', np.canSeek !== true);
    if (document.activeElement !== view.seek) {
      setMax(view.seek, np.durationMs);
      setRangeValue(view.seek, np.positionMs || 0);
      setText(view.current, formatTime(np.positionMs));
    }
    setText(view.duration, formatTime(Number(view.seek.getAttribute('max'))));
  }
  if (view.volume) {
    const vol = np.volume;
    setBool(view.volume, 'disabled', vol.canSet !== true);
    if (document.activeElement !== view.volume) {
      setMax(view.volume, vol.max);
      setRangeValue(view.volume, vol.level);
      setText(view.level, `${vol.level} / ${vol.max}`);
    }
    setText(view.mute, vol.muted ? 'Unmute' : 'Mute');
    for (const control of [view.mute, view.down, view.up]) setBool(control, 'disabled', !vol.canSet);
    setBool(view.fixed, 'hidden', !!vol.canSet);
  }
  setText(view.error, data.mediaError ? message(data.mediaError) : '');
  setBool(view.error, 'hidden', !data.mediaError);
  const artKey = np.hasArt ? `${room().base}:${np.artVersion}` : null;
  if (view.artKey !== artKey || (!artKey && artwork.size > 0)) {
    view.artKey = artKey;
    view.art.src = '';
    setBool(view.art, 'hidden', !artKey);
    setBool(view.fallback, 'hidden', !!artKey);
    artwork.retain(artKey ? [artKey] : []);
    if (artKey) artwork.add(artKey,
      (signal) => api(ticket.room, `/remote/art?v=${encodeURIComponent(np.artVersion)}`, { blob: true, signal }),
      (url) => {
        if (mediaView === view && view.artKey === artKey && session.current(ticket) && view.art.isConnected)
          view.art.src = url;
      },
      () => {
        if (mediaView === view && view.artKey === artKey && session.current(ticket)) {
          view.artKey = null;
          setBool(view.art, 'hidden', true);
          setBool(view.fallback, 'hidden', false);
        }
      });
  }
}

const playbackCommands = new ControlCommandQueue();
let playbackVisibilityGeneration = 0;
async function playbackCommand(kind, path, body, coalesceKey, success) {
  const target = room(), ticket = session.capture(), visibility = playbackVisibilityGeneration;
  if (!target) return null;
  const key = `${target.base}:${kind}`;
  const response = await playbackCommands.enqueue(key, {
    coalesceKey,
    current: () => session.current(ticket) && visibility === playbackVisibilityGeneration &&
      !document.hidden && connectionState !== 'expired',
    run: () => mutate(kind, path, body, { target, ticket, success }),
  });
  // Read back after the stream drains; intermediate acknowledgements must not overwrite
  // the final slider draft or add a poll for every superseded value.
  if (response && session.current(ticket) && visibility === playbackVisibilityGeneration &&
      !playbackCommands.hasPending(key)) mediaRead();
  return response;
}
async function mediaCommand(action, positionMs) {
  return playbackCommand('media', '/remote/media',
    { action, ...(positionMs !== undefined ? { positionMs } : {}) },
    action === 'seek' ? 'seek' : null, 'Media command dispatched.');
}
async function volumeCommand(dir, level) {
  return playbackCommand('volume', '/remote/volume',
    { dir, ...(level !== undefined ? { level } : {}) },
    dir === 'set' ? 'set' : null, '');
}
async function loadSettings() {
  return read('settings', '/remote/settings', (r) => {
    data.settings = r.settings;
    scopeSchemas.set(room().base, r.settings);
    if (!category)
      category = r.settings?.domains?.find((d) => visibleControls(d, advanced).length)?.id || '';
  });
}
function settingsPage() {
  const schema = data.settings;
  const ticket = session.capture();
  const context = JSON.stringify([ticket.generation, ticket.pageGeneration]);
  if (document.hidden || connectionState === 'expired') settingsRowView = null;
  else if (settingsRowView?.context !== context)
    settingsRowView = { context, entries: new Map(), seen: new Set() };
  settingsRowView?.seen.clear();
  const filters = group(
    searchField(
      'Search settings',
      settingsQuery,
      (value) => {
        settingsQuery = value;
      },
      { key: 'settings-search' },
    ),
    checkbox('Show advanced settings', advanced, (value) => {
      advanced = value;
      render();
    }),
  );
  const options = el(
    'details',
    {
      class: 'settings-options',
      open: $('page').querySelector('details.settings-options')?.open === true,
    },
    el('summary', { text: 'Rooms & options' }),
    sections(
      scopePanel(),
      group(
        actionRow(
          'Photo sources',
          'Folder, Immich, WebDAV, network folder and calendar feed.',
          'photos',
          () => go('settings', 'sources'),
        ),
        actionRow(
          'Settings profiles',
          'Save or apply your browser’s named profiles.',
          'backup',
          () => go('settings', 'profiles'),
        ),
        actionRow('Manage Portals', 'Pair, select or forget a room.', 'remote', roomsDialog),
      ),
      group(
        el('h3', { text: 'Browser appearance' }),
        segmented(
          storage.get(STORAGE.theme, 'system'),
          [
            { value: 'system', label: 'System' },
            { value: 'light', label: 'Light' },
            { value: 'dark', label: 'Dark' },
          ],
          (value) => {
            storage.set(STORAGE.theme, value);
            applyTheme();
            render();
          },
          'Browser appearance',
        ),
      ),
      group(
        el('h3', { text: 'Device tools' }),
        text(
          'USB/ADB provisioning, screen mirroring, APK sideloading, uninstall, files and full backups require Portal Toolkit with a device connection. They cannot run in this LAN browser.',
        ),
      ),
    ),
  );
  let controls;
  if (!schema)
    controls = data.settingsError
      ? failure('Settings unavailable', data.settingsError, loadSettings)
      : empty('Settings', 'Loading the Portal’s settings registry…');
  else {
    const domains = (schema.domains || []).filter(
      (d) => visibleControls(d, advanced, settingsQuery).length,
    );
    const visibleCategory = domains.some((d) => d.id === category) ? category : domains[0]?.id;
    controls = sections(
      !settingsQuery && domains.length
        ? group(
            row(
              'Category',
              '',
              choice(
                visibleCategory,
                domains.map((d) => ({ value: d.id, label: d.title })),
                (value) => {
                  category = value;
                  render();
                },
                'Settings category',
              ),
            ),
          )
        : null,
      ...domains
        .filter((d) => settingsQuery || d.id === visibleCategory)
        .map((domain) => renderDomain(domain)),
      domains.length
        ? null
        : empty('No matching settings', 'Try another term or include advanced settings.'),
    );
  }
  // Keep only rows shown in this render; hidden categories/drafts are not retained.
  if (settingsRowView) {
    for (const key of settingsRowView.entries.keys())
      if (!settingsRowView.seen.has(key)) settingsRowView.entries.delete(key);
  }
  return sections(
    heading('Settings', 'Make each room feel right.'),
    el(
      'div',
      { class: 'settings-layout' },
      el(
        'div',
        { class: 'settings-primary' },
        sections(
          filters,
          roster.length > 1
            ? text(
                `Editing ${scope === 'this' ? room().name : scope === 'all' ? 'all rooms' : `${settingTargets().length} selected rooms`}. Change scope in Rooms & options.`,
              )
            : null,
          resultPanel(),
          controls,
        ),
      ),
      el('div', { class: 'settings-sidebar' }, options),
    ),
  );
}
function scopePanel() {
  if (roster.length < 2) return null;
  const targets = settingTargets();
  return group(
    el('h3', { text: 'Apply settings to' }),
    segmented(
      scope,
      [
        { value: 'this', label: 'This Portal' },
        { value: 'selected', label: 'Selected rooms' },
        { value: 'all', label: 'All rooms' },
      ],
      (value) => {
        scope = value;
        scopeResults = [];
        loadScopeSchemas();
        render();
      },
      'Settings target scope',
    ),
    scope === 'selected'
      ? el(
          'div',
          { class: 'room-checks' },
          roster.map((target) =>
            checkbox(target.name, selectedTargets.has(target.base), (checked) => {
              if (checked) selectedTargets.add(target.base);
              else selectedTargets.delete(target.base);
              scopeResults = [];
              loadScopeSchemas();
              render();
            }),
          ),
        )
      : null,
    text(
      `${targets.length} ${targets.length === 1 ? 'room' : 'rooms'} selected. Changes report a result for each room.`,
    ),
    scopeErrors.length
      ? text(
          `Could not read ${scopeErrors.join(', ')}. Those rooms will report their own errors on apply.`,
          'error',
        )
      : null,
  );
}
function settingTargets(domain, control) {
  const chosen = captureTargets(roster, scope, room()?.base, selectedTargets);
  return domain === 'fleet' || (control && activeOnlySetting(domain, control.key, control))
    ? captureTargets(roster, 'this', room()?.base)
    : chosen;
}
async function loadScopeSchemas() {
  const ticket = session.read('scope');
  scopeSchemas = new Map(data.settings ? [[room().base, data.settings]] : []);
  scopeErrors = [];
  for (const target of settingTargets()) {
    if (target.base === room().base) continue;
    try {
      const r = await api(target, '/remote/settings');
      if (!session.current(ticket)) return;
      scopeSchemas.set(target.base, r.settings);
    } catch (error) {
      if (!session.current(ticket)) return;
      scopeErrors.push(target.name);
    }
  }
  if (session.current(ticket)) render();
}
function resultPanel() {
  if (!scopeResults.length) return null;
  return group(
    el('h3', { text: 'Results by room' }),
    el(
      'div',
      { class: 'result-list' },
      scopeResults.map((result) =>
        el(
          'div',
          { class: result.ok ? 'success' : 'error' },
          el('strong', { text: result.name }),
          ` — ${result.ok ? 'Saved and confirmed' : result.error ? message({ code: result.error }) : `Rejected: ${(result.rejected || []).join(', ')}`}`,
        ),
      ),
    ),
  );
}
function renderDomain(domain) {
  const controls = visibleControls(domain, advanced, settingsQuery),
    groups = new Map();
  for (const c of controls) {
    const key = c.section || '';
    if (!groups.has(key)) groups.set(key, []);
    groups.get(key).push(c);
  }
  const reset = controls.filter(
    (c) => Object.hasOwn(c, 'default') && !c.secret && !c.readOnly && c.type !== 'info',
  );
  return sections(
    el(
      'div',
      { class: 'history-head' },
      el('h2', { text: domain.title }),
      reset.length
        ? button(
            'Reset',
            () => {
              const targets = settingTargets(domain.id),
                ticket = session.capture();
              confirmAction(
                `Reset ${domain.title}?`,
                `Apply ordinary defaults to ${targets.map((r) => r.name).join(', ')}. Active Portal only: ${ticket.room.name} for name and connection controls.`,
                () =>
                  applyDomain(
                    domain.id,
                    Object.fromEntries(reset.map((c) => [c.key, c.default])),
                    targets,
                    ticket,
                  ),
                'Reset',
                ticket,
              );
            },
            { kind: 'outline' },
          )
        : null,
    ),
    ...Array.from(groups, ([name, items]) =>
      sections(
        name ? el('h3', { class: 'section-title', text: name }) : null,
        group(...items.map((control) => renderSetting(domain.id, control))),
      ),
    ),
  );
}
function renderSetting(domain, control) {
  const id = `setting:${domain}:${control.key}`,
    isBusy = busy.has(`${room()?.base}:${id}`);
  const activeOnly = activeOnlySetting(domain, control.key, control);
  const availableSchemas = settingTargets(domain, control)
    .map((r) => scopeSchemas.get(r.base))
    .filter(Boolean);
  const mixed = mixedValue(availableSchemas, domain, control.key);
  const baseHelp =
    control.key === 'advancedSettings'
      ? 'Controls advanced settings on the Portal.'
      : advanced
        ? control.help
        : control.shortHelp || control.help;
  const help = [activeOnly ? `Active Portal only: ${room().name}.` : '', baseHelp]
    .filter(Boolean)
    .join(' ');
  const view = settingsRowView;
  const key = JSON.stringify([domain, control.key]);
  // Server responses are fresh objects. Compare all metadata, including limits/options.
  // Secret and oversized controls still render normally without retaining extra copies.
  const metadata = control.secret ? null : JSON.stringify(control);
  const retain = view && metadata !== null && metadata.length <= 16 * 1024;
  const presentation = JSON.stringify([advanced, isBusy, mixed, help]);
  const timeOfDay = control.type === 'int' && control.timeOfDay === true;
  const draftField = control.type === 'string' || (control.type === 'int' && (control.asText || timeOfDay));
  // Do not seed a time draft from a single room: mixed scope stays blank until edited.
  // Include time fields in the live-value guard so clearing a saved draft refreshes the input.
  const expectedDraft = timeOfDay
    ? drafts.get(draftKey(`${id}:draft`)) ?? (mixed ? '' : timeOfDayValue(control.value))
    : draftField && !control.secret
      ? draft(`${id}:draft`, String(control.value ?? '')) : null;
  const cached = retain ? view.entries.get(key) : null;
  // A rejected/no-target change may have altered the live native control already.
  const valueMatches = cached && (control.type === 'bool'
    ? cached.input?.checked === (control.value === true) && cached.input.indeterminate === mixed
    : control.type === 'enum'
      ? cached.select?.value === String(mixed ? '' : (control.value ?? ''))
      : !draftField || cached.input?.value === expectedDraft);
  if (cached && cached.metadata === metadata && cached.presentation === presentation && valueMatches) {
    view.seen.add(key);
    return cached.root;
  }
  const ticket = session.capture();
  let root;
  const ownsRow = () => session.current(ticket) && root?.isConnected &&
    !document.hidden && connectionState !== 'expired';
  const apply = (values) => ownsRow() ? applyDomain(domain, values) : Promise.resolve(undefined);
  const remember = (element) => {
    root = element;
    if (retain) {
      view.entries.set(key, { root, metadata, presentation,
        input: root.querySelector('input'), select: root.querySelector('select') });
      view.seen.add(key);
    }
    return root;
  };
  let ui;
  if (control.type === 'bool') {
    ui = el('input', {
      class: 'switch',
      type: 'checkbox',
      role: 'switch',
      checked: control.value === true,
      disabled: control.readOnly || isBusy,
      'aria-label': control.title,
      onChange: (e) => apply({ [control.key]: e.target.checked }),
    });
    ui.indeterminate = mixed;
  } else if (control.type === 'enum') {
    const options = (control.options || []).filter(
      (o) => advanced || o.tier !== 'advanced' || o.value === control.value,
    );
    ui = choice(
      mixed ? '' : control.value,
      [...(mixed ? [{ value: '', label: 'Mixed values' }] : []), ...options],
      (value) => {
        if (value !== '') apply({ [control.key]: value });
      },
      control.title,
    );
    ui.disabled = control.readOnly || isBusy;
  } else if (control.type === 'int' && !control.asText && !timeOfDay) {
    const value = control.value;
    const wrap = control.wrap === true;
    const min = control.min,
      max = control.max,
      step = control.step || 1;
    ui = el(
      'div',
      { class: 'stepper' },
      el(
        'button',
        {
          'aria-label': `Decrease ${control.title}`,
          disabled: control.readOnly || isBusy || (!wrap && value <= min),
          onClick: () =>
            apply({
              [control.key]: value - step < min && wrap ? max : Math.max(min, value - step),
            }),
        },
        '−',
      ),
      el('output', { text: mixed ? 'Mixed' : (control.display ?? value) }),
      el(
        'button',
        {
          'aria-label': `Increase ${control.title}`,
          disabled: control.readOnly || isBusy || (!wrap && value >= max),
          onClick: () =>
            apply({
              [control.key]: value + step > max && wrap ? min : Math.min(max, value + step),
            }),
        },
        '+',
      ),
    );
  } else if (control.type === 'string' || control.type === 'int') {
    const key = `${id}:draft`;
    const current = timeOfDay ? expectedDraft : draft(key, control.secret ? '' : String(control.value ?? ''));
    const errorKey = `${id}:error`, errorId = `${id}:error`;
    const error = timeOfDay ? el('p', { class: 'detail error', id: errorId,
      role: 'alert', hidden: !drafts.get(draftKey(errorKey)),
      text: drafts.get(draftKey(errorKey)) || '' }) : null;
    const input = el('input', {
      type: timeOfDay ? 'time' : control.secret ? 'password' : control.type === 'int' ? 'number' : 'text',
      value: current,
      placeholder:
        control.secret && control.hasValue ? 'Keep saved credential' : mixed ? 'Mixed values' : '',
      'aria-label': control.title,
      'data-focus': id,
      autocomplete: control.secret ? 'new-password' : 'off',
      disabled: control.readOnly || isBusy,
      ...(timeOfDay ? { step: 60, 'aria-describedby': errorId,
        'aria-invalid': error.hidden ? 'false' : 'true' } : {}),
      onInput: (e) => {
        if (timeOfDay && !ownsRow()) return;
        setDraft(key, e.target.value);
        if (timeOfDay) {
          drafts.delete(draftKey(errorKey));
          error.hidden = true;
          error.textContent = '';
          input.setAttribute('aria-invalid', 'false');
        }
      },
    });
    if (control.type === 'int' && !timeOfDay) {
      input.min = control.min;
      input.max = control.max;
      input.step = control.step || 1;
    }
    const save = button(
      timeOfDay ? 'Apply' : 'Save',
      () => {
        if (!ownsRow() || (timeOfDay && (control.readOnly || isBusy))) return;
        const value = timeOfDay ? timeOfDayMinutes(input.value)
          : control.type === 'int' ? Number(input.value) : input.value;
        if (!validateValue(control, value)) {
          if (timeOfDay) {
            const message = 'Choose a valid time (00:00–23:59) before applying.';
            setDraft(key, input.value);
            setDraft(errorKey, message);
            error.textContent = message;
            error.hidden = false;
            input.setAttribute('aria-invalid', 'true');
          } else toast(message(new ApiError('invalid_value')), true);
          return;
        }
        apply({ [control.key]: value }).then((results) => {
          if (session.current(ticket) && connectionState !== 'expired' && results?.every((r) => r.ok)) {
            drafts.delete(draftKey(key));
            if (timeOfDay) drafts.delete(draftKey(errorKey));
            render();
          }
        });
      },
      { kind: 'outline', disabled: control.readOnly || isBusy },
    );
    return remember(el(
      'div',
      { class: 'setting-row string-row' },
      el(
        'div',
        { class: 'setting-copy' },
        el('strong', { text: control.title }),
        help ? text(help) : null,
        timeOfDay && mixed ? text('Values differ between selected rooms. Choose a time to apply to all.') : null,
        error,
        control.secret && control.hasValue
          ? text('Leave blank to keep the saved credential.')
          : null,
      ),
      el('div', { class: 'setting-value' }, input, save),
    ));
  } else
    ui = el('span', {
      class: 'detail',
      text:
        typeof control.value === 'object'
          ? JSON.stringify(control.value)
          : String(control.value ?? ''),
    });
  return remember(row(
    control.title,
    mixed ? `${help || ''} Values differ between selected rooms.` : help,
    ui,
  ));
}
async function applyDomain(
  domain,
  values,
  targets = settingTargets(domain),
  ticket = session.capture(),
) {
  if (!session.current(ticket)) return;
  const requested = copy(values);
  let activeSchema = scopeSchemas.get(ticket.room.base) || data.settings;
  if (!activeSchema) {
    try {
      activeSchema = (await api(ticket.room, '/remote/settings')).settings;
    } catch (error) {
      if (session.current(ticket)) toast(message(error), true);
      return;
    }
    if (!session.current(ticket)) return;
  }
  const controls = activeSchema?.domains?.find((d) => d.id === domain)?.controls || [];
  const groupValues = {},
    activeValues = {};
  for (const [key, value] of Object.entries(requested)) {
    const control = controls.find((c) => c.key === key);
    (activeOnlySetting(domain, key, control) ? activeValues : groupValues)[key] = value;
  }
  // Build each target's patch before sending. Explicit target arrays, profiles and resets
  // cannot bypass the active-only policy or accidentally broadcast a mixed patch.
  const patches = new Map();
  if (Object.keys(groupValues).length) {
    for (const target of targets)
      patches.set(target.base, {
        target: Object.freeze({ ...target }),
        values: { ...groupValues },
      });
  }
  if (Object.keys(activeValues).length) {
    const active = patches.get(ticket.room.base);
    patches.set(ticket.room.base, {
      target: ticket.room,
      values: { ...(active?.values || {}), ...activeValues },
    });
  }
  if (!patches.size) {
    toast('Select at least one room.', true);
    return;
  }
  const ids = Object.keys(requested).map((key) => `${ticket.room.base}:setting:${domain}:${key}`);
  if (ids.some((id) => busy.has(id))) return;
  ids.forEach((id) => busy.add(id));
  render();
  const results = await applySettings(
    [...patches.values()].map((p) => p.target),
    domain,
    (target) => patches.get(target.base).values,
    async (target, id, patch) => {
      // Run fresh schema preflight after prior writes to this endpoint settle.
      const schema = (await api(target, `/remote/settings?domain=${encodeURIComponent(id)}&includeInactive=1`)).settings;
      const controlDomain = schema?.domains?.find((d) => d.id === id);
      for (const [key, value] of Object.entries(patch)) {
        const control = controlDomain?.controls?.find((c) => c.key === key);
        // Peer schemas may advertise additional secret/URL metadata. Guard again using
        // the exact target schema immediately before the transport write.
        if (activeOnlySetting(id, key, control) && target.base !== ticket.room.base)
          throw new ApiError('active_portal_only');
        if (!control || !validateValue(control, value)) throw new ApiError('unavailable_setting');
      }
      return api(target, '/remote/settings', { body: { domain: id, values: patch } });
    },
  );
  ids.forEach((id) => busy.delete(id));
  if (session.current(ticket)) {
    scopeResults = results;
    for (const result of results) {
      if (result.response?.domain) {
        const current = scopeSchemas.get(result.base);
        if (current) {
          current.domains = current.domains.map((d) =>
            d.id === domain ? result.response.domain : d,
          );
          scopeSchemas.set(result.base, current);
        }
        if (result.base === ticket.room.base && data.settings)
          data.settings.domains = data.settings.domains.map((d) =>
            d.id === domain ? result.response.domain : d,
          );
      }
    }
    toast(
      `Confirmed in ${results.filter((r) => r.ok).length} of ${results.length} rooms.`,
      results.some((r) => !r.ok),
    );
    render();
  }
  return results;
}
function readProfiles() {
  let raw;
  try {
    raw = localStorage.getItem(STORAGE.profiles);
  } catch {
    return { ok: false, error: 'Browser storage is unavailable. Your saved profiles have not been changed.' };
  }
  if (raw === null) return { ok: true, profiles: {} };
  try {
    const profiles = JSON.parse(raw);
    if (!profiles || typeof profiles !== 'object' || Array.isArray(profiles) ||
        Object.values(profiles).some((profile) => !profile || typeof profile !== 'object' || Array.isArray(profile)))
      throw new Error('invalid profile collection');
    return { ok: true, profiles };
  } catch {
    return { ok: false, error: 'Saved profiles could not be read. The original data has not been changed.' };
  }
}
function deleteProfile(name) {
  const current = readProfiles();
  if (!current.ok) {
    toast(`Profile not deleted. ${current.error}`, true);
    render();
    return;
  }
  if (!Object.hasOwn(current.profiles, name)) {
    toast('This profile was already removed.');
    render();
    return;
  }
  const updated = { ...current.profiles };
  delete updated[name];
  if (!storage.set(STORAGE.profiles, JSON.stringify(updated))) {
    toast('Profile not deleted. Browser storage is unavailable. Try again when storage is available.', true);
    render();
    return;
  }
  toast('Profile deleted from this browser.');
  render();
}
function profileCaptureKey(ticket = session.capture()) {
  return `${ticket.room?.base}:profile-capture:${ticket.generation}:${ticket.pageGeneration}`;
}
async function saveCurrentProfile(name, ticket) {
  const key = profileCaptureKey(ticket);
  if (!session.current(ticket) || busy.has(key)) return;
  const opening = readProfiles();
  if (!opening.ok) {
    toast(`Profile not saved. ${opening.error}`, true);
    render();
    return;
  }
  busy.add(key);
  render();
  try {
    const response = await readSettings(ticket.room, (target) =>
      session.current(ticket) ? api(target, '/remote/settings/profile') : null,
    );
    if (!session.current(ticket)) return;
    const domains = response?.settings?.domains;
    if (response?.profileComplete !== true || !Array.isArray(domains) || !domains.length ||
        domains.some((d) => typeof d?.id !== 'string' || !d.id || !Array.isArray(d.controls)) ||
        new Set(domains.map((d) => d.id)).size !== domains.length)
      throw new ApiError('invalid_response');
    const current = readProfiles();
    if (!current.ok) {
      toast(`Profile not saved. ${current.error}`, true);
      return;
    }
    const updated = { ...current.profiles, [name]: captureProfile(response.settings) };
    if (!storage.set(STORAGE.profiles, JSON.stringify(updated))) {
      toast('Profile not saved. Browser storage is unavailable. Try again when storage is available.', true);
      return;
    }
    toast('Profile saved in this browser.');
  } catch (error) {
    if (session.current(ticket)) toast(`Profile not saved. ${message(error)}`, true);
  } finally {
    busy.delete(key);
    if (session.current(ticket)) render();
  }
}
function profilesPage() {
  const current = readProfiles();
  if (!current.ok)
    return sections(
      heading('Settings profiles', 'Your profiles are saved in this browser.'),
      group(
        el('h3', { text: 'Saved profiles unavailable' }),
        text(current.error),
        text('Retry when browser storage is available. No profiles will be replaced or removed while they cannot be read.'),
        button('Retry', () => render(), { kind: 'primary' }),
      ),
    );
  const profiles = current.profiles;
  return sections(
    heading('Settings profiles', 'Your named profiles from the previous remote remain available.'),
    scopePanel(),
    resultPanel(),
    group(
      el('h3', { text: 'Saved in this browser' }),
      text(
        'Profiles contain registry values. New profiles omit secrets and addresses containing credentials or query strings.',
      ),
      ...Object.keys(profiles).map((name) =>
        el(
          'div',
          { class: 'reorder-row' },
          el('strong', { class: 'label', text: name }),
          actions(
            button(
              'Apply',
              () => {
                const ticket = session.capture(),
                  targets = settingTargets();
                confirmAction(
                  `Apply “${name}”?`,
                  `Apply ordinary settings to ${targets.map((r) => r.name).join(', ')}. Active Portal only: ${ticket.room.name} for names and connections. Saved credentials are omitted. Unavailable settings report a failure for their room.`,
                  () => applyProfile(name, profiles[name], targets, ticket),
                  'Apply',
                  ticket,
                );
              },
              { kind: 'outline' },
            ),
            button(
              'Delete',
              () =>
                confirmAction(
                  `Delete “${name}”?`,
                  'Remove this profile from browser storage.',
                  () => deleteProfile(name),
                  'Delete',
                ),
              { kind: 'danger' },
            ),
          ),
        ),
      ),
      field('Profile name', draft('profile-name'), (v) => setDraft('profile-name', v)),
      button(
        'Save current settings',
        () => {
          const name = draft('profile-name').trim();
          if (!name || !data.settings) {
            toast('Enter a name and load the settings first.', true);
            return;
          }
          const ticket = session.capture();
          const save = () => saveCurrentProfile(name, ticket);
          if (Object.hasOwn(profiles, name))
            confirmAction(
              `Replace “${name}”?`,
              'Replace the existing browser profile with current Portal settings.',
              save,
              'Replace',
              ticket,
            );
          else save();
        },
        { kind: 'primary', disabled: busy.has(profileCaptureKey()) },
      ),
    ),
  );
}
async function applyProfile(name, profile, targets, ticket) {
  const collected = [];
  for (const [domain, values] of Object.entries(profile || {})) {
    if (!session.current(ticket)) return;
    if (!values || typeof values !== 'object' || Array.isArray(values)) continue;
    const safe = { ...values };
    const schemaDomain = data.settings?.domains?.find((d) => d.id === domain);
    for (const key of Object.keys(safe))
      if (schemaDomain?.controls?.find((c) => c.key === key)?.secret) delete safe[key];
    if (!Object.keys(safe).length) continue;
    const results = await applyDomain(domain, safe, targets, ticket);
    for (const result of results || [])
      collected.push({ ...result, name: `${result.name} · ${schemaDomain?.title || domain}` });
    if (!session.current(ticket)) return;
  }
  scopeResults = collected;
  render();
  toast(
    `Profile “${name}”: ${collected.filter((r) => r.ok).length} of ${collected.length} room/category changes confirmed.`,
    collected.some((r) => !r.ok),
  );
}
async function loadSources() {
  return read('sources', '/remote/sources', (r) => {
    const source = { ...(r.sources || {}) };
    for (const key of ['immichKey', 'smbPass', 'davPass']) {
      source[`${key}Configured`] = source[`${key}Configured`] === true || !!source[key];
      delete source[key];
    }
    data.sources = source;
    data.calendar = r.calendar;
  });
}
const sourceNames = {
  folder: 'Photos on this Portal',
  immich: 'Immich',
  smb: 'Network folder',
  dav: 'WebDAV',
  album: 'Remote album',
  web: 'Web image',
  default: 'Built-in photos',
};
const sourceFields = {
  folder: [['folderPath', 'Folder on Portal']],
  immich: [
    ['immichUrl', 'Server address'],
    ['immichKey', 'API key', true],
    ['immichAlbumId', 'Album ID (optional)'],
    ['immichAlbumName', 'Album name (optional)'],
  ],
  smb: [
    ['smbHost', 'Server'],
    ['smbShare', 'Share'],
    ['smbPath', 'Folder (optional)'],
    ['smbUser', 'Username'],
    ['smbPass', 'Password', true],
  ],
  dav: [
    ['davUrl', 'Folder address'],
    ['davUser', 'Username'],
    ['davPass', 'Password', true],
  ],
  album: [['albumUrl', 'Album address']],
  web: [['webUrl', 'Web page address']],
  default: [],
};
function sourcesPage() {
  const s = data.sources;
  if (!s)
    return data.sourcesError
      ? failure('Photo sources unavailable', data.sourcesError, loadSources)
      : empty('Photo sources', 'Loading your current source…');
  const selected = draft('source:selected', s.source || 'default');
  const tech = ['immich', 'smb', 'dav', 'web'];
  const options = Object.entries(sourceNames)
    .filter(([id]) => advanced || !tech.includes(id) || id === selected)
    .map(([value, label]) => ({ value, label }));
  const hidden = tech.includes(selected) && !advanced;
  const editor = group(
    row(
      'Source',
      'Choose what your Portal displays.',
      choice(
        selected,
        options,
        (value) => {
          setDraft('source:selected', value);
          render();
        },
        'Photo source',
      ),
    ),
    checkbox('Show advanced sources', advanced, (value) => {
      advanced = value;
      render();
    }),
    hidden
      ? text(
          'Your configured source continues to play. Enable advanced sources to edit this connection.',
        )
      : null,
    ...(hidden ? [] : sourceFields[selected] || []).map(([key, label, secret]) =>
      field(
        label,
        draft(
          `source:${key}`,
          secret ? '' : s[key] || (key === 'folderPath' ? '/sdcard/Pictures/Immortal' : ''),
        ),
        (value) => setDraft(`source:${key}`, value),
        {
          type: secret ? 'password' : 'text',
          key: `source:${key}`,
          placeholder: secret && s[`${key}Configured`] ? 'Keep saved credential' : '',
          help:
            key === 'webUrl'
              ? 'Use a complete http:// or https:// URL with a host. A valid format does not guarantee the page will load.'
              : secret
                ? 'Leave blank to keep a credential for the same endpoint and account.'
                : '',
        },
      ),
    ),
    selected === 'immich' && !hidden
      ? actions(
          button('Load albums', loadAlbums, { kind: 'outline' }),
          data.albums
            ? choice(
                draft('source:immichAlbumId', s.immichAlbumId || ''),
                [
                  { value: '', label: 'Whole library' },
                  ...(data.albums || []).map((a) => ({
                    value: a.id,
                    label: `${a.name} (${a.count})`,
                  })),
                ],
                (id) => {
                  setDraft('source:immichAlbumId', id);
                  setDraft(
                    'source:immichAlbumName',
                    data.albums.find((a) => a.id === id)?.name || '',
                  );
                  render();
                },
                'Immich album',
              )
            : null,
        )
      : null,
    actions(
      button(
        selected === 'folder' ? 'Use this folder' : 'Activate source',
        () => saveSource(selected, s),
        { kind: 'primary', disabled: hidden },
      ),
      button('Preview on Portal', () => dispatchKey('screensaver'), { kind: 'outline' }),
    ),
    text('Connections apply to the selected Portal. Credentials are not copied to other rooms.'),
  );
  const calendar = group(
    el('h3', { text: 'Calendar feed' }),
    field(
      'Calendar feed URL',
      draft('calendar-url', data.calendar?.url || ''),
      (v) => setDraft('calendar-url', v),
      { key: 'calendar-url', help: 'Use an iCalendar feed. Clear the address to remove the feed.' },
    ),
    button(
      'Save calendar feed',
      async () => {
        const response = await mutate(
          'calendar',
          '/remote/sources',
          { calendarUrl: draft('calendar-url').trim() },
          { success: '' },
        );
        if (response) {
          data.calendar = response.calendar;
          if (
            (response.applied || []).includes('url') ||
            response.calendar?.url === draft('calendar-url').trim()
          )
            toast('Calendar feed saved.');
          else toast('Calendar feed was not confirmed. Refresh and review it.', true);
          render();
        }
      },
      { kind: 'primary' },
    ),
  );
  return sections(
    heading('Photo sources', 'Connect a collection and preview it on your Portal.'),
    el('div', { class: 'columns' }, sections(editor), sections(calendar)),
  );
}
async function loadAlbums() {
  const s = data.sources || {};
  const body = { url: draft('source:immichUrl', s.immichUrl || '').trim() };
  const key = draft('source:immichKey', '');
  if (key) body.key = key;
  const response = await mutate('albums', '/remote/immich/albums', body, { success: '' });
  if (response) {
    data.albums = response.albums || [];
    toast(data.albums.length ? 'Albums loaded.' : 'The server returned no albums.');
    render();
  }
}
async function saveSource(selected, s) {
  try {
    const body = {};
    if (selected === 'default') body.source = 'default';
    for (const [key, , secret] of sourceFields[selected] || []) {
      const value = draft(
        `source:${key}`,
        secret ? '' : s[key] || (key === 'folderPath' ? '/sdcard/Pictures/Immortal' : ''),
      );
      if (secret) {
        if (value) body[key] = value;
      } else body[key] = key === 'folderPath' ? photoFolder(value) : value.trim();
    }
    const response = await mutate('source', '/remote/sources', body, { success: '' });
    if (response) {
      const actual = response.sources?.source;
      const expected = selected;
      if (actual !== expected && !(expected === 'folder' && actual === 'local')) {
        toast('The source was not activated. Check the required fields.', true);
        return;
      }
      toast('Source saved and confirmed.');
      for (const [key, , secret] of sourceFields[selected] || [])
        if (secret) drafts.delete(draftKey(`source:${key}`));
      await loadSources();
    }
  } catch (error) {
    toast(message(error), true);
  }
}
async function loadPhotos() {
  if (!room()) return;
  if (data.companion && !supported('photoLibrary') && !supported('photos')) return;
  if (data.companion && !supported('photoLibrary')) photosAll = false;
  const path = photosAll && supported('photoLibrary') ? '/sdcard' : photoPath;
  const recursive = photosAll && supported('photoLibrary');
  const response = await read(
    'photos',
    `/remote/photos?path=${encodeURIComponent(path)}${recursive ? '&recursive=1' : ''}`,
    (r) => {
      data.photos = photoListing(r, recursive);
      data.photoMetadataRevision = (data.photoMetadataRevision || 0) + 1;
      if (data.photos.all) data.photoFolders = data.photos.folders;
      else if (data.photoFolders?.length) data.photos.folders = data.photoFolders;
      photoPageIndex = Math.min(
        photoPageIndex,
        Math.max(0, Math.ceil(data.photos.files.length / 24) - 1),
      );
    },
  );
  if (response && recursive && response.recursive !== true) {
    photosAll = false;
    photoPath = response.path;
    render();
  }
}
function freeSpace(bytes) {
  return bytes >= 1073741824
    ? `${(bytes / 1073741824).toLocaleString(undefined, { maximumFractionDigits: 1 })} GB`
    : `${Math.floor(bytes / 1048576)} MB`;
}
function photoThumbnailKey(file, ticket) {
  const versioned = Number.isFinite(file.size) && Number.isFinite(file.modified);
  // Legacy listings without a complete version are safe only until the next
  // accepted metadata read. Pairing tokens never form part of the cache identity.
  return JSON.stringify([
    ticket.room.base,
    file.path,
    file.size ?? null,
    file.modified ?? null,
    versioned ? null : data.photoMetadataRevision,
  ]);
}
function photosPage() {
  const info = data.photos;
  const files = info && !data.photosError ? photoPage(info.files, photoPageIndex) : [];
  const ticket = session.capture();
  thumbs.retain(files.map((file) => photoThumbnailKey(file, ticket)));
  const controls = group(
    segmented(
      photosAll ? 'all' : 'folder',
      [
        { value: 'all', label: 'All photos' },
        { value: 'folder', label: 'Folders' },
      ],
      (value) => {
        if (value === 'all' && data.companion && !supported('photoLibrary')) {
          toast(
            'This Portal supports folder browsing. All-photo scanning requires an updated Portal.',
            true,
          );
          return;
        }
        photosAll = value === 'all';
        photoPageIndex = 0;
        thumbs.clear();
        if (!photosAll && info?.all) {
          photoPath = info.folders[0]?.path || '/sdcard/Pictures/Immortal';
          setDraft('photo-folder', photoPath);
        }
        loadPhotos();
        render();
      },
      'Photo collection',
    ),
    !photosAll
      ? field(
          'Folder on Portal',
          draft('photo-folder', photoPath),
          (value) => setDraft('photo-folder', value),
          { key: 'photo-folder', help: 'Public folders under /sdcard are available.' },
        )
      : null,
    !photosAll
      ? actions(
          button(
            'Open folder',
            () => {
              try {
                photoPath = photoFolder(draft('photo-folder', photoPath));
                photoPageIndex = 0;
                thumbs.clear();
                loadPhotos();
                render();
              } catch (error) {
                toast(message(error), true);
              }
            },
            { kind: 'outline' },
          ),
          info?.folders?.length
            ? choice(
                photoPath,
                [
                  { value: photoPath, label: photoPath },
                  ...info.folders
                    .filter((f) => f.path !== photoPath)
                    .map((f) => ({ value: f.path, label: `${f.path} (${f.count})` })),
                ],
                (path) => {
                  photoPath = path;
                  setDraft('photo-folder', path);
                  photoPageIndex = 0;
                  loadPhotos();
                  render();
                },
                'Choose a photo folder',
              )
            : null,
        )
      : null,
    el(
      'div',
      { class: 'photo-actions' },
      button('Import photos', chooseUpload, {
        kind: 'primary wide',
        icon: 'upload',
        disabled: !supported('photos') || !!upload?.running,
      }),
      el(
        'div',
        { class: 'photo-secondary' },
        button(
          'Use for slideshow',
          () => {
            if (!info) return;
            const targetPath = info.all ? '/sdcard' : info.path;
            const ticket = session.capture();
            confirmAction(
              'Use this collection?',
              `Set ${room().name}’s slideshow folder to ${targetPath}.`,
              async () => {
                const r = await mutate(
                  'activate-collection',
                  '/remote/sources',
                  { folderPath: targetPath },
                  { ticket, success: '' },
                );
                if (r?.sources?.folderPath === targetPath) toast('Collection activated.');
                else if (r)
                  toast('The Portal did not confirm this folder. Refresh and review it.', true);
              },
              'Use collection',
              ticket,
            );
          },
          { kind: 'outline', disabled: !info },
        ),
        button('Preview on Portal', () => dispatchKey('screensaver'), { kind: 'outline' }),
      ),
    ),
    !supported('photos')
      ? text(
          'To import or delete photos, open Control from your phone on the Portal, choose Allow photo imports, and grant file access. Then refresh.',
        )
      : null,
    uploadPanel(),
    info
      ? text(
          `${info.files.length} photos${typeof info.freeBytes === 'number' ? ` · ${freeSpace(info.freeBytes)} free` : ''}`,
          'photo-summary detail',
        )
      : null,
    info?.truncated
      ? text(
          `This listing reached the Portal’s ${info.all ? 'recursive scan' : '500-photo folder'} limit. Open a narrower folder to find more photos.`,
        )
      : null,
  );
  let gallery;
  if (data.photosError) gallery = failure('Photos unavailable', data.photosError, loadPhotos);
  else if (!info)
    gallery = empty(
      'Photo library',
      data.companion && !supported('photoLibrary') && !supported('photos')
        ? 'Allow photo imports and file access on the Portal, then refresh.'
        : 'Loading photo metadata…',
    );
  else if (!info.files.length)
    gallery = empty('No photos here', 'Import photos or choose another folder.');
  else {
    gallery = sections(
      el(
        'div',
        { class: 'photo-grid' },
        files.map((file) => photoTile(file, ticket)),
      ),
      el(
        'div',
        { class: 'pagination' },
        button(
          'Previous',
          () => {
            photoPageIndex--;
            thumbs.clear();
            render();
          },
          { kind: 'outline', disabled: photoPageIndex === 0 },
        ),
        text(`${photoPageIndex + 1} / ${Math.ceil(info.files.length / 24)}`),
        button(
          'Next',
          () => {
            photoPageIndex++;
            thumbs.clear();
            render();
          },
          { kind: 'outline', disabled: (photoPageIndex + 1) * 24 >= info.files.length },
        ),
      ),
    );
  }
  return sections(
    heading('Photos', 'A collection that stays on your Portal.'),
    controls,
    gallery,
    group(
      actionRow(
        'Photo sources',
        'Connect albums, network folders and calendar feeds.',
        'settings',
        () => go('photos', 'sources'),
      ),
    ),
  );
}
// One observer owns only the currently mounted Photos tiles. ImagePool still owns
// caching, cancellation and its four-request concurrency limit.
function stopPhotoObservation() {
  const owner = photoObservation;
  photoObservation = null;
  if (!owner) return;
  owner.observer?.disconnect();
  owner.pending.clear();
}
function observePhotoTile(tile, ticket, load) {
  if (!photoObservation) {
    const owner = { observer: null, pending: new Map() };
    photoObservation = owner;
    owner.admit = (target) => {
      const job = owner.pending.get(target);
      if (!job || photoObservation !== owner || document.hidden ||
          connectionState === 'expired' || !target.isConnected || !session.current(job.ticket)) return;
      owner.pending.delete(target);
      owner.observer?.unobserve(target);
      job.load();
    };
    if (typeof IntersectionObserver === 'function') {
      try {
        owner.observer = new IntersectionObserver((entries) => {
          for (const entry of entries) if (entry.isIntersecting) owner.admit(entry.target);
        }, { rootMargin: '200px 0px' });
      } catch { /* Older browsers still load through the bounded image pool. */ }
    }
  }
  const owner = photoObservation;
  owner.pending.set(tile, { ticket, load });
  if (owner.observer) owner.observer.observe(tile);
  else queueMicrotask(() => owner.admit(tile)); // The render mounts tiles before admission.
}
function photoTile(file, ticket) {
  const image = el('img', { alt: '', loading: 'lazy', hidden: true });
  const hint = el('span', { class: 'detail', text: 'Loading…' });
  const cover = el('div', { class: 'photo-image' }, image, hint);
  const tile = el(
    'button',
    {
      class: 'photo-tile',
      'aria-label': `Preview ${file.name || file.path}`,
      onClick: () => photoPreview(file, ticket),
    },
    cover,
    el(
      'div',
      { class: 'photo-caption' },
      el('strong', { text: file.name || file.path.split('/').pop() }),
      el('span', {
        text: typeof file.size === 'number' ? `${Math.ceil(file.size / 1024)} KB` : '',
      }),
    ),
  );
  const load = () =>
    thumbs.add(
      photoThumbnailKey(file, ticket),
      (signal) =>
        api(ticket.room, `/remote/photo?path=${encodeURIComponent(file.path)}&thumbnail=1`, {
          blob: true,
          maxBytes: 512 * 1024,
          signal,
        }),
      (url) => {
        if (session.current(ticket) && tile.isConnected) {
          image.src = url;
          image.hidden = false;
          hint.hidden = true;
        }
      },
      (error) => {
        if (session.current(ticket) && tile.isConnected) {
          hint.textContent = 'Preview unavailable · Tap to retry';
        }
      },
    );
  // Already admitted work rebinds before layout; only new requests wait for visibility.
  if (thumbs.entries.has(photoThumbnailKey(file, ticket))) load();
  else observePhotoTile(tile, ticket, load);
  return tile;
}
function photoPreview(file, ticket) {
  if (!session.current(ticket)) return;
  const image = el('img', {
    class: 'preview-image',
    alt: file.name || 'Photo preview',
    hidden: true,
  });
  const status = text('Loading preview…');
  const retry = button('Retry preview', () => load(), { kind: 'outline' });
  retry.hidden = true;
  openSheet(
    file.name || 'Photo preview',
    sections(
      image,
      status,
      retry,
      text(file.path),
      el(
        'div',
        { class: 'dialog-actions' },
        button(
          'Delete photo',
          () => {
            closeSheet();
            confirmAction(
              `Delete ${file.name || 'this photo'}?`,
              `Permanently remove this photo from ${ticket.room.name}.`,
              async () => {
                const response = await mutate(
                  'delete-photo',
                  `/remote/photo?path=${encodeURIComponent(file.path)}`,
                  undefined,
                  { method: 'DELETE', ticket, success: 'Photo deleted.' },
                );
                if (response) loadPhotos();
              },
              'Delete',
              ticket,
            );
          },
          { kind: 'danger', disabled: !supported('photos') },
        ),
      ),
    ),
  );
  async function load() {
    previewPool.clear();
    status.textContent = 'Loading preview…';
    retry.hidden = true;
    previewPool.add(
      file.path,
      (signal) =>
        api(ticket.room, `/remote/photo?path=${encodeURIComponent(file.path)}&thumbnail=1`, {
          blob: true,
          maxBytes: 512 * 1024,
          signal,
        }),
      (url) => {
        if (session.current(ticket) && sheet.open && image.isConnected) {
          image.src = url;
          image.hidden = false;
          status.textContent = 'Thumbnail from your Portal.';
        }
      },
      (error) => {
        if (session.current(ticket) && sheet.open) {
          status.textContent = message(error);
          status.className = 'error';
          retry.hidden = false;
        }
      },
    );
  }
  load();
}
function chooseUpload() {
  const ticket = session.capture();
  let destination = data.photos?.all
    ? '/sdcard/Pictures/Immortal'
    : data.photos?.path || '/sdcard/Pictures/Immortal';
  const input = el('input', {
    type: 'file',
    accept: '.jpg,.jpeg,.png,.webp,.gif',
    multiple: true,
    'aria-label': 'Choose photos',
  });
  openSheet(
    'Import photos',
    sections(
      text('JPEG, PNG, WebP and GIF · Up to 20 MiB per file. Existing files are never replaced.'),
      field('Destination folder', destination, (v) => (destination = v), { key: 'upload-folder' }),
      input,
      button(
        'Start import',
        () => {
          try {
            const path = photoFolder(destination);
            if (!input.files.length) {
              toast('Choose at least one photo.', true);
              return;
            }
            const files = Array.from(input.files);
            closeSheet();
            startUpload(files, path, ticket);
          } catch (error) {
            toast(message(error), true);
          }
        },
        { kind: 'primary' },
      ),
    ),
  );
}
function cancelUpload(transfer = upload, reason = 'cancelled') {
  if (!transfer?.running) return;
  transfer.cancelled = true;
  transfer.cancelReason = reason;
  for (const item of transfer.items) {
    if (item.state === 'queued') item.state = 'cancelled';
    else if (item.state === 'sending') item.state = 'unconfirmed';
  }
  transfer.current = '';
  transfer.controller.abort();
  if (upload === transfer) renderUpload();
}
async function startUpload(files, path, ticket) {
  if (!session.current(ticket) || upload?.running) return;
  const controller = new AbortController();
  const transfer = {
    controller,
    target: ticket.room,
    path,
    total: files.length,
    completed: 0,
    bytes: 0,
    failures: [],
    running: true,
    current: '',
    cancelled: false,
    items: files.map((file) => ({ name: file.name, state: 'queued' })),
  };
  upload = transfer;
  uploadTransfers.set(ticket.room.base, transfer);
  render();
  for (let index = 0; index < files.length; index++) {
    if (controller.signal.aborted || !session.current(ticket, { page: false })) {
      cancelUpload(transfer, 'room_changed');
      break;
    }
    const file = files[index],
      item = transfer.items[index];
    let posted = false;
    try {
      if (file.size < 1 || file.size > 20 * 1048576) throw new ApiError('upload_size_limit');
      if (
        !/^[^.][^/\\:\x00-\x1f]{0,127}\.(?:jpe?g|png|webp|gif)$/i.test(file.name) ||
        file.name.length > 128
      )
        throw new ApiError('invalid_name');
      transfer.current = file.name;
      item.state = 'sending';
      if (upload === transfer) renderUpload();
      posted = true;
      const response = await api(
        ticket.room,
        `/remote/photo-upload?path=${encodeURIComponent(path)}&name=${encodeURIComponent(file.name)}`,
        {
          body: file,
          headers: {
            'Content-Type': /^image\/(jpeg|png|webp|gif)$/.test(file.type)
              ? file.type
              : 'application/octet-stream',
          },
          signal: controller.signal,
          timeout: 90000,
        },
      );
      if (response.path !== `${path}/${file.name}` || response.bytes !== file.size)
        throw new ApiError('upload_unconfirmed');
      item.state = 'confirmed';
      transfer.completed++;
      transfer.bytes += file.size;
    } catch (error) {
      const uncertain =
        posted && ['aborted', 'timeout', 'unreachable', 'upload_unconfirmed'].includes(error.code);
      item.state = uncertain ? 'unconfirmed' : 'failed';
      item.error = uncertain ? 'incomplete_upload' : error.code;
      transfer.failures.push({ name: file.name, error: item.error });
      if (error.code === 'aborted' || error.status === 401) {
        cancelUpload(transfer, error.status === 401 ? 'pairing_expired' : 'cancelled');
        break;
      }
    }
    transfer.current = '';
    if (upload === transfer) renderUpload();
  }
  transfer.running = false;
  transfer.current = '';
  transfer.cancelled ||= controller.signal.aborted;
  if (upload === transfer) renderUpload();
  if (!session.current(ticket, { page: false }) || upload !== transfer) return;
  if (transfer.completed && tab === 'photos' && !detail && !document.hidden) {
    const refreshTicket = session.capture();
    await loadPhotos();
    if (
      !session.current(refreshTicket) ||
      !session.current(ticket, { page: false }) ||
      upload !== transfer
    )
      return;
  }
  if (tab === 'photos' && !detail) render();
  const unconfirmed = transfer.items.filter((item) => item.state === 'unconfirmed').length;
  toast(
    `${transfer.completed} of ${transfer.total} photos stored on ${ticket.room.name}.${unconfirmed ? ` ${unconfirmed} unconfirmed; refresh this Portal before retrying.` : ''}${transfer.cancelled ? ' Remaining imports stopped.' : ''}`,
    transfer.failures.length > 0 || transfer.cancelled,
  );
}
function uploadPanel() {
  if (!upload) return null;
  const transfer = upload;
  const labels = {
    queued: 'Queued',
    sending: 'Sending',
    confirmed: 'Stored and confirmed',
    failed: 'Failed',
    cancelled: 'Not sent',
    unconfirmed: 'Completion unconfirmed — refresh this Portal',
  };
  return el(
    'div',
    { class: 'upload-status', id: 'upload-status' },
    text(
      `${transfer.completed} of ${transfer.total} uploaded to ${transfer.target.name} · ${Math.ceil(transfer.bytes / 1024)} KB confirmed${transfer.current ? ` · Sending ${transfer.current}` : ''}`,
    ),
    el('progress', {
      max: transfer.total,
      value: transfer.completed,
      'aria-label': 'Photos uploaded',
    }),
    transfer.running && !transfer.cancelled
      ? button('Cancel remaining uploads', () => cancelUpload(transfer), { kind: 'outline' })
      : null,
    transfer.cancelled
      ? text(
          'Transfer stopped. Already confirmed files remain. Refresh this Portal to check whether the in-flight file arrived.',
        )
      : null,
    ...transfer.items.map((item) =>
      text(
        `${item.name}: ${labels[item.state]}${item.error && item.state === 'failed' ? ` · ${message({ code: item.error })}` : ''}`,
        ['failed', 'unconfirmed'].includes(item.state) ? 'detail error' : 'detail',
      ),
    ),
  );
}
function renderUpload() {
  const node = $('upload-status');
  if (node) node.replaceWith(uploadPanel());
}
async function loadApps({ changedOnly = false } = {}) {
  const key = appMode === 'catalog' ? 'catalog' : 'apps';
  if (key === 'catalog' && data.companion && !supported('catalog')) return;
  return read(key, `/remote/${key}`, (r) => (data[key] = r.apps || []), {
    // Completion still reads fresh metadata; manual refresh always renders.
    renderWhen: changedOnly
      ? () =>
          !operationsView ||
          operationsView.kind !== 'apps' ||
          operationsView.key !== key ||
          JSON.stringify(data[key]) !== operationsView.metadata ||
          !!data[`${key}Error`]
      : null,
  });
}
function appsPage() {
  const entries = appMode === 'catalog' ? data.catalog : data.apps;
  const q = appQuery.toLowerCase();
  const filtered = (entries || []).filter((app) =>
    [app.name, app.label, app.packageName, app.description].some((v) =>
      String(v || '')
        .toLowerCase()
        .includes(q),
    ),
  );
  appPage = Math.min(appPage, Math.max(0, Math.ceil(filtered.length / 24) - 1));
  const error = appMode === 'catalog' ? data.catalogError : data.appsError;
  const visibleApps = filtered.slice(appPage * 24, (appPage + 1) * 24);
  // Fresh server inventory invalidates artwork, including updates without version metadata.
  if (appIconInventory !== entries) {
    thumbs.clear();
    appIconInventory = entries;
  }
  thumbs.retain((error ? [] : visibleApps)
    .filter(app => (appMode === 'installed' || app.installed === true) && app.packageName)
    .map(app => app.packageName));
  const ticket = session.capture();
  let records = new Map(), list = null;
  if (!error && entries && filtered.length) {
    const key = JSON.stringify([
      ticket.generation, ticket.pageGeneration, appMode, supported('operations'),
    ]);
    const cached = appListView;
    if (cached && cached.key === key && cached.entries === entries &&
        cached.visible.length === visibleApps.length &&
        visibleApps.every((app, i) => cached.visible[i] === app)) {
      list = cached.root;
      records = cached.records;
    } else {
      list = el('div', { class: 'apps-list' },
        visibleApps.map((app) => appCard(app, ticket, records)));
      appListView = { key, entries, visible: visibleApps, root: list, records };
    }
    updateAppRecords(records);
  } else appListView = null;
  const body = sections(
    heading('Apps', 'Launch apps and manage the Immortal catalog.'),
    group(
      searchField(
        'Find an app',
        appQuery,
        (value) => {
          appQuery = value;
          appPage = 0;
        },
        { key: 'apps-search' },
      ),
      segmented(
        appMode,
        [
          { value: 'installed', label: 'Installed' },
          { value: 'catalog', label: 'Catalog' },
        ],
        (value) => {
          appMode = value;
          appPage = 0;
          render();
          loadApps();
        },
        'App list',
      ),
      appMode === 'catalog' && data.companion?.install
        ? text(
            `Installer: ${data.companion.install.mode}${data.companion.install.paused ? ' · Paused on Portal' : ''}${data.companion.install.mode === 'dialog' ? ' · Confirm on the Portal when prompted' : ''}`,
          )
        : null,
    ),
    error
      ? failure('Apps unavailable', error, loadApps)
      : !entries
        ? empty(
            appMode === 'catalog' ? 'Catalog' : 'Installed apps',
            appMode === 'catalog' && data.companion && !supported('catalog')
              ? 'This Portal does not advertise catalog support. Update Immortal.'
              : 'Loading apps…',
          )
        : !filtered.length
          ? empty('No apps to show', 'Refresh or adjust your search.')
          : list,
    filtered.length > 24
      ? el(
          'div',
          { class: 'pagination' },
          button(
            'Previous',
            () => {
              appPage--;
              render();
            },
            { kind: 'outline', disabled: !appPage },
          ),
          text(`${appPage + 1} / ${Math.ceil(filtered.length / 24)}`),
          button(
            'Next',
            () => {
              appPage++;
              render();
            },
            { kind: 'outline', disabled: (appPage + 1) * 24 >= filtered.length },
          ),
        )
      : null,
    supported('operations')
      ? group(
          actionRow('Activity', 'See progress and confirmed install results.', 'refresh', () =>
            go('home', 'activity'),
          ),
        )
      : null,
  );
  if (list)
    operationsView = {
      kind: 'apps', ticket, root: list, records,
      key: appMode === 'catalog' ? 'catalog' : 'apps', metadata: JSON.stringify(entries),
    };
  return body;
}
function updateAppRecords(records) {
  const byPackage = new Map();
  for (const operation of data.operations || []) {
    const pkg = operation.target?.packageName;
    if (!byPackage.has(pkg)) byPackage.set(pkg, operation);
  }
  for (const [pkg, update] of records) update(byPackage.get(pkg));
}
function appCard(app, ticket, records) {
  const pkg = app.packageName;
  const installed = appMode === 'installed' || app.installed === true;
  const op = (data.operations || []).find((o) => o.target?.packageName === pkg);
  const pending = op?.state === 'running' || op?.installUncertain === true;
  const icon = el('div', { class: 'icon' }, glyph('apps'));
  if (installed && pkg) {
    thumbs.add(
      pkg,
      (signal) =>
        api(ticket.room, `/remote/icon?pkg=${encodeURIComponent(pkg)}`, {
          blob: true,
          signal,
          maxBytes: 1024 * 1024,
        }),
      (url) => {
        if (session.current(ticket) && icon.isConnected)
          icon.replaceChildren(el('img', { src: url, alt: '' }));
      },
    );
  }
  const card = group(
    el(
      'div',
      { class: 'app-row' },
      icon,
      el(
        'div',
        { class: 'app-copy' },
        el('strong', { text: app.name || app.label || pkg }),
        text(app.description || app.versionName || pkg),
      ),
      installed
        ? button(
            'Open',
            () =>
              mutate(
                `launch:${pkg}`,
                '/remote/launch',
                { packageName: pkg },
                { ticket, success: 'Launch command dispatched. Check the Portal screen.' },
              ),
            { kind: 'outline' },
          )
        : null,
    ),
  );
  let installButton = null,
    installDisabled = false,
    statusNode = null,
    statusKey = null;
  if (appMode === 'catalog') {
    const compatible = app.compatible === true;
    const latest = installed && app.updateAvailable === false;
    const action = installed ? 'update' : 'install';
    installDisabled = !compatible || latest || !supported('operations');
    card.append(
      actions(
        (installButton = button(
          !compatible
            ? 'Incompatible'
            : latest
              ? 'Up to date'
              : installed
                ? app.updateAvailable === true
                  ? 'Update'
                  : 'Check update'
                : 'Install',
          () => beginOperation({ type: action, packageName: pkg }, ticket),
          {
            kind: 'primary',
            disabled: installDisabled || pending,
          },
        )),
      ),
    );
    if (!compatible) card.append(text('This catalog app is not compatible with this Portal.'));
  }
  const update = (operation) => {
    if (installButton) {
      const disabled =
        installDisabled || operation?.state === 'running' || operation?.installUncertain === true;
      if (installButton.disabled !== disabled) installButton.disabled = disabled;
    }
    const view = operation ? operationView(operation) : null;
    const key = view ? JSON.stringify([view.label, view.detail, view.tone]) : null;
    if (key === statusKey) return;
    statusKey = key;
    if (!view) {
      statusNode?.remove();
      statusNode = null;
      return;
    }
    const label = `${view.label}${view.detail ? `: ${view.detail}` : ''}`;
    if (!statusNode) {
      statusNode = text('');
      card.append(statusNode);
    }
    statusNode.textContent = label;
    statusNode.className = view.tone === 'error' ? 'error' : 'detail';
  };
  update(op);
  records.set(pkg, update);
  return card;
}
async function beginOperation(body, ticket = session.capture()) {
  const response = await mutate(
    `operation:${body.packageName || body.id || body.action}`,
    '/remote/operation',
    body,
    { ticket, success: '' },
  );
  if (response?.operation && session.current(ticket)) {
    data.operations = [
      response.operation,
      ...(data.operations || []).filter((o) => o.id !== response.operation.id),
    ];
    toast('Operation accepted. Activity shows the device’s reported result.');
    render();
    startPolling();
  }
}
function uniqueId() {
  return (
    globalThis.crypto?.randomUUID?.() ||
    `web-${Date.now()}-${Math.random().toString(36).slice(2, 10)}`
  );
}
async function loadLayout() {
  return read('layout', '/remote/layout', (response) => {
    data.layout = response;
    if (!layoutDraft) {
      layoutDraft = copy(response.layout);
      layoutRevision = response.revision;
    }
  });
}
function editLayout(fn) {
  try {
    layoutDraft = fn(layoutDraft);
    render();
  } catch (error) {
    toast(message(error), true);
  }
}
function tileLabel(key) {
  if (!key) return 'Empty grid cell';
  if (key.startsWith('widget-tile:')) {
    const w = layoutDraft?.widgets.find((w) => widgetKey(w) === key);
    return w ? (w.kind === 'appwidget' ? w.package : `${w.kind.replace(/_/g, ' ')} widget`) : key;
  }
  return key.replace(/^(app:|builtin:|folder:)/, '').replace(/^([a-z]+\.[a-z.]+)\//, '');
}
function layoutPage() {
  if (!data.layout || !layoutDraft)
    return data.layoutError
      ? failure('Layout unavailable', data.layoutError, loadLayout)
      : empty('Home layout', 'Loading the current layout…');
  const changed = JSON.stringify(layoutDraft) !== JSON.stringify(data.layout.layout);
  const controls = group(
    el('h3', { text: 'Arrange your home' }),
    text(
      'Move rows with the arrow buttons. Widget sizes use Portal grid cells. Existing bound Android widgets retain their identity.',
    ),
    text(changed ? 'Unsaved changes' : 'Layout from Portal', 'draft-label'),
    actions(
      button(
        'Save layout',
        async () => {
          const candidate = copy(layoutDraft),
            revision = layoutRevision,
            ticket = session.capture();
          const response = await mutate(
            'layout',
            '/remote/layout',
            { layout: candidate, expectedRevision: revision },
            { ticket, success: '' },
          );
          if (response?.layout && session.current(ticket)) {
            data.layout = response;
            layoutDraft = copy(response.layout);
            layoutRevision = response.revision;
            toast('Layout saved and confirmed.');
            render();
          }
        },
        { kind: 'primary', disabled: !changed },
      ),
      button('Preview on Portal', () => dispatchKey('home'), { kind: 'outline' }),
      button(
        'Reload layout',
        () =>
          confirmAction(
            'Reload layout?',
            'Discard unsaved edits and read the current Portal layout.',
            () => {
              layoutDraft = null;
              loadLayout();
            },
            'Reload',
          ),
        { kind: 'outline' },
      ),
    ),
    text(
      'Save first to preview your changes. Concurrent settings/layout changes are rejected by the Portal.',
    ),
  );
  const widgetControls = group(
    el('h3', { text: 'Widgets' }),
    ...layoutDraft.widgets.map((w, index) => {
      const key = widgetKey(w);
      return sections(
        el(
          'div',
          { class: 'reorder-row' },
          el(
            'div',
            { class: 'label' },
            el('strong', { text: w.kind === 'appwidget' ? w.package : w.kind.replace(/_/g, ' ') }),
            text(w.kind === 'appwidget' ? 'Bound Android widget' : 'Immortal widget'),
          ),
          actions(
            button('↑', () => editLayout((l) => moveWidget(l, key, -1)), {
              kind: 'outline',
              disabled: index === 0,
            }),
            button('↓', () => editLayout((l) => moveWidget(l, key, 1)), {
              kind: 'outline',
              disabled: index === layoutDraft.widgets.length - 1,
            }),
            button('Remove', () => editLayout((l) => removeWidget(l, key)), { kind: 'danger' }),
          ),
        ),
        el(
          'div',
          { class: 'widget-size' },
          text('Width'),
          choice(
            String(w.spanX),
            [1, 2, 3, 4].map((v) => ({ value: String(v), label: String(v) })),
            (v) =>
              editLayout((l) =>
                resizeWidget(l, key, Number(v), l.widgets.find((x) => widgetKey(x) === key).spanY),
              ),
            `Width of ${tileLabel(key)}`,
          ),
          text('Height'),
          choice(
            String(w.spanY),
            [1, 2, 3, 4].map((v) => ({ value: String(v), label: String(v) })),
            (v) =>
              editLayout((l) =>
                resizeWidget(l, key, l.widgets.find((x) => widgetKey(x) === key).spanX, Number(v)),
              ),
            `Height of ${tileLabel(key)}`,
          ),
        ),
      );
    }),
    layoutDraft.widgets.length ? null : text('Add an Immortal widget to get started.'),
    row(
      'Add widget',
      'Third-party widgets must first be bound on the Portal.',
      choice(
        '',
        [
          { value: '', label: 'Choose a widget' },
          ...(data.layout.widgetKinds || []).map((kind) => ({
            value: kind,
            label: kind.replace(/_/g, ' '),
          })),
        ],
        (kind) => {
          if (kind) editLayout((l) => addWidget(l, kind, uniqueId()));
        },
        'Add widget',
      ),
    ),
  );
  const orderPanels = ['homeOrder', 'gridOrder', 'gridSlots'].map((field) =>
    group(
      el('h3', {
        text: { homeOrder: 'Apps order', gridOrder: 'Grid order', gridSlots: 'Grid positions' }[
          field
        ],
      }),
      !layoutDraft[field].length ? text('Automatic placement on Portal.') : null,
      ...layoutDraft[field].map((key, index) =>
        el(
          'div',
          { class: 'reorder-row' },
          el('span', { class: 'label', text: tileLabel(key) }),
          key
            ? actions(
                button('↑', () => moveOrder(field, key, -1), { kind: 'outline', disabled: !index }),
                button('↓', () => moveOrder(field, key, 1), {
                  kind: 'outline',
                  disabled: index === layoutDraft[field].length - 1,
                }),
              )
            : null,
        ),
      ),
    ),
  );
  const assignments = group(
    el('h3', { text: 'App folders' }),
    text('Folder assignments from the Portal. Leave a folder name empty to ungroup an app.'),
    ...Object.keys(layoutDraft.assignments).map((pkg) =>
      field(
        pkg,
        layoutDraft.assignments[pkg],
        (value) => {
          layoutDraft.assignments[pkg] = value;
          render();
        },
        { key: `folder:${pkg}` },
      ),
    ),
  );
  return sections(
    heading('Home layout', 'Arrange apps and widgets on your Portal.'),
    controls,
    el(
      'div',
      { class: 'columns' },
      sections(widgetControls),
      sections(...orderPanels, assignments),
    ),
  );
}
function moveOrder(field, key, delta) {
  editLayout((layout) => {
    const candidate = copy(layout);
    const from = candidate[field].indexOf(key),
      to = from + delta;
    if (from >= 0 && to >= 0 && to < candidate[field].length)
      candidate[field].splice(to, 0, candidate[field].splice(from, 1)[0]);
    return candidate;
  });
}
function acceptOperations(response) {
  const previous = new Map((data.operations || []).map((operation) => [operation.id, operation]));
  data.operations = response.operations || [];
  const completedInstall = data.operations.some(
    (operation) =>
      ['install', 'update'].includes(operation.type) &&
      operation.state === 'succeeded' &&
      previous.has(operation.id) &&
      previous.get(operation.id).state !== 'succeeded' &&
      operationView(operation).tone === 'success',
  );
  if (completedInstall && tab === 'apps') loadApps({ changedOnly: true });
}
function rememberOperations() {
  renderedOperations = JSON.stringify(data.operations);
  renderedOperationsError = JSON.stringify(data.operationsError);
}
function renderOperations() {
  const view = operationsView;
  if (!view || !view.root.isConnected || !session.current(view.ticket)) return render();
  const active = document.activeElement;
  const left = window.scrollX, top = window.scrollY;
  if (view.kind === 'apps') updateAppRecords(view.records);
  else updateActivity(view);
  rememberOperations();
  // Reordering or replacing a changed operation's contents may detach its action temporarily.
  if (active?.isConnected && document.activeElement !== active) active.focus({ preventScroll: true });
  if (window.scrollX !== left || window.scrollY !== top)
    window.scrollTo({ left, top, behavior: 'instant' });
}
function activityPage() {
  const view = {
    kind: 'activity', ticket: session.capture(), root: el('div'),
    list: el('div', { class: 'history-list' }), records: new Map(), state: null,
  };
  updateActivity(view);
  operationsView = view;
  return sections(heading('Activity', 'Progress and results retained on the Portal.'), view.root);
}
function updateActivity(view) {
  const operations = data.operations;
  const state = data.operationsError
    ? `error:${JSON.stringify(data.operationsError)}`
    : !operations ? 'loading' : !operations.length ? 'empty' : 'list';
  if (state !== 'list') {
    if (state !== view.state)
      view.root.replaceChildren(
        data.operationsError
          ? failure('Activity unavailable', data.operationsError, () => loadVisible())
          : !operations
            ? empty('Activity', 'Loading operations…')
            : empty('No recent operations', 'Catalog installs and scenes will appear here.'),
      );
    if (state === 'empty') {
      view.records.clear();
      view.list.replaceChildren();
    }
  } else {
    const ids = new Set(), nodes = [];
    for (const operation of operations) {
      ids.add(operation.id);
      const key = JSON.stringify(operation);
      const actionsKey = JSON.stringify([
        operation.retrySupported === true, operation.cancelSupported === true,
      ]);
      let record = view.records.get(operation.id);
      if (!record || record.key !== key) {
        const node = operationCard(
          operation, record?.actionsKey === actionsKey ? record.actions : null,
        );
        if (record) record.node.replaceChildren(...node.children);
        else record = { node };
        record.key = key;
        record.actionsKey = actionsKey;
        record.actions = record.node.children[record.node.children.length - 1];
        view.records.set(operation.id, record);
      }
      nodes.push(record.node);
    }
    for (const id of view.records.keys()) if (!ids.has(id)) view.records.delete(id);
    if (view.list.children.length !== nodes.length ||
        nodes.some((node, i) => view.list.children[i] !== node))
      view.list.replaceChildren(...nodes);
    if (view.root.firstChild !== view.list) view.root.replaceChildren(view.list);
  }
  view.state = state;
}
function operationCard(op, retainedActions = null) {
  const view = operationView(op);
  const name =
    op.target?.name || op.target?.packageName || op.target?.id || op.target?.action || op.type;
  return group(
    el(
      'div',
      { class: 'history-head' },
      el(
        'div',
        {},
        el('h3', { text: name }),
        text(op.type),
        el('p', {
          class: 'history-date',
          text: op.updatedAt ? new Date(op.updatedAt).toLocaleString() : '',
        }),
      ),
      el('span', {
        class: `badge ${view.tone === 'error' ? 'error' : view.tone === 'success' ? 'success' : ''}`,
        text: view.label,
      }),
    ),
    view.detail
      ? text(view.detail, view.tone === 'error' ? 'history-result error' : 'history-result')
      : null,
    ...(op.results || []).map((result, index) =>
      text(
        `${(op.results || []).length > 1 ? `Step ${index + 1}: ` : ''}${result.result || 'Result unavailable'}${result.error ? ` · ${result.error}` : ''}`,
        result.ok === false ? 'history-result error' : 'history-result',
      ),
    ),
    op.reconciliation
      ? text(`Confirmed by ${op.reconciliation.evidence?.replace(/_/g, ' ') || 'device evidence'}.`)
      : null,
    retainedActions || actions(
      op.retrySupported === true
        ? button('Retry', () => beginOperation({ type: 'retry', id: op.id }), { kind: 'outline' })
        : null,
      op.cancelSupported === true
        ? button(
            'Cancel operation',
            () =>
              confirmAction(
                'Cancel this operation?',
                'Ask the Portal to cancel this specific operation.',
                () => beginOperation({ type: 'cancel', id: op.id }),
                'Cancel operation',
              ),
            { kind: 'danger' },
          )
        : null,
    ),
  );
}
function downloadJson(name, value) {
  const blob = new Blob([JSON.stringify(value, null, 2)], { type: 'application/json' }),
    url = URL.createObjectURL(blob);
  const anchor = el('a', { href: url, download: name });
  document.body.append(anchor);
  anchor.click();
  anchor.remove();
  setTimeout(() => URL.revokeObjectURL(url), 1000);
}
function snapshotsPage() {
  const current = data.snapshot?.snapshot;
  const importInput = el('input', {
    type: 'file',
    accept: '.json,application/json',
    'aria-label': 'Choose snapshot JSON',
  });
  const status = text('');
  const restore = async () => {
    const file = importInput.files[0],
      ticket = session.capture();
    if (!file) {
      toast('Choose a snapshot JSON file.', true);
      return;
    }
    try {
      if (file.size > 240 * 1024) throw new ApiError('body_too_large');
      const candidate = JSON.parse(await file.text());
      if (!session.current(ticket)) return;
      if (
        candidate.schemaVersion !== 1 ||
        !candidate.layout ||
        !candidate.domains ||
        typeof candidate.revision !== 'string'
      )
        throw new ApiError('unsupported_schema');
      if (candidate.deviceId !== data.companion?.deviceId) throw new ApiError('wrong_device');
      status.textContent = 'Reading the current device revision…';
      const fresh = await api(ticket.room, '/remote/snapshot');
      if (!session.current(ticket)) return;
      const revision = fresh.snapshot?.revision;
      if (typeof revision !== 'string') throw new ApiError('invalid_response');
      status.textContent = '';
      const domainCount = Object.keys(candidate.domains).length;
      confirmAction(
        `Restore to ${ticket.room.name}?`,
        `Restore ${domainCount} settings categories and the layout from ${file.name}. This snapshot belongs to this Portal. Any further Portal changes will stop this restore. Credentials, photos and private app data stay on the Portal.`,
        async () => {
          const response = await mutate(
            'restore',
            '/remote/snapshot',
            { snapshot: candidate, expectedRevision: revision },
            { ticket, success: '' },
          );
          if (response?.snapshot) {
            data.snapshot = response;
            data.layout = null;
            layoutDraft = null;
            toast('Snapshot settings and layout restored and confirmed.');
            render();
          } else if (scopeResults.length) render();
        },
        'Restore snapshot',
        ticket,
      );
    } catch (error) {
      status.className = 'error';
      status.textContent =
        error instanceof SyntaxError ? 'This file is not valid snapshot JSON.' : message(error);
    }
  };
  return sections(
    heading('Snapshots', 'Save and restore supported settings and your home layout.'),
    el(
      'div',
      { class: 'columns' },
      group(
        el('h3', { text: 'Export' }),
        text(
          'Exports exclude credentials, pairing tokens, connection strings, photos, private app data and third-party widget data.',
        ),
        current
          ? text(
              `${Object.keys(current.domains || {}).length} settings categories · This Portal only`,
            )
          : text('Loading snapshot…'),
        data.snapshotError ? text(message(data.snapshotError), 'error') : null,
        button(
          'Export snapshot',
          async () => {
            const ticket = session.capture();
            const response = await mutate('export-snapshot', '/remote/snapshot', undefined, {
              ticket,
              method: 'GET',
              success: '',
            });
            if (response?.snapshot && session.current(ticket)) {
              data.snapshot = response;
              downloadJson('immortal-snapshot.json', response.snapshot);
              toast('Snapshot exported.');
            }
          },
          { kind: 'primary', icon: 'backup' },
        ),
      ),
      group(
        el('h3', { text: 'Restore' }),
        text(
          'Choose a snapshot exported by this Portal. Review the target before applying. Side-effect failures can leave a partial restore; the Portal returns the affected domains.',
        ),
        importInput,
        status,
        button('Review restore', restore, { kind: 'outline' }),
      ),
    ),
    resultPanel(),
  );
}
function jarvisPage() {
  const status = data.jarvis;
  if (!status)
    return data.jarvisError
      ? failure('Jarvis unavailable', data.jarvisError, () => loadVisible())
      : empty('Jarvis', 'Loading readiness and microphone status…');
  const models = (status.availableModels || status.models || [])
    .filter((m) => typeof m === 'string' && /^[A-Za-z0-9._-]{1,100}$/.test(m))
    .slice(0, 32);
  const model = draft('jarvis:model', status.model),
    wake = draft('jarvis:wake', status.wakeEnabled);
  const ready = status.supported === true;
  const mic =
    status.microphoneMuted === true
      ? 'Muted on Portal'
      : status.microphoneGranted === false
        ? 'Permission required on Portal'
        : status.microphoneGranted === true
          ? 'Permission granted'
          : 'Status unavailable';
  return sections(
    heading('Jarvis', 'Model and local wake settings.'),
    el(
      'div',
      { class: 'columns' },
      group(
        el('h3', { text: 'Assistant status' }),
        ready
          ? sections(
              row('Assistant', '', text(status.status || 'Status unavailable')),
              row('Microphone', '', text(mic)),
              row(
                'Background wake',
                '',
                text(status.wakeSupported === true ? 'Supported' : 'Unavailable on this Portal'),
              ),
              status.wakeSupported
                ? row(
                    'Wake listener',
                    '',
                    text(
                      status.wakeListening === true
                        ? 'Listening locally'
                        : status.wakeListening === false
                          ? status.wakeEnabled
                            ? 'Waiting to resume'
                            : 'Off'
                          : 'Status unavailable',
                    ),
                  )
                : null,
            )
          : text(
              status.installed
                ? 'This Jarvis version does not expose companion controls. Open it on the Portal or update it from Apps.'
                : 'Jarvis is not installed. Install it from Apps.',
            ),
        button(
          'Open Jarvis on Portal',
          () =>
            mutate(
              'jarvis-launch',
              '/remote/launch',
              { packageName: 'com.portal.assistant' },
              { success: 'Launch command dispatched. Check the Portal screen.' },
            ),
          { kind: 'outline', disabled: !status.installed },
        ),
        text('Account credentials and microphone permission are managed in Jarvis on the Portal.'),
      ),
      ready
        ? group(
            row(
              'Conversation model',
              models.length
                ? 'Choices reported by Jarvis.'
                : 'Jarvis has not reported an available model list.',
              choice(
                model,
                models.map((value) => ({ value, label: value })),
                (value) => {
                  setDraft('jarvis:model', value);
                  render();
                },
                'Conversation model',
              ),
            ),
            status.wakeSupported
              ? row(
                  'Allow background wake',
                  'Opt in to local wake-word listening in the background.',
                  el('input', {
                    class: 'switch',
                    type: 'checkbox',
                    role: 'switch',
                    checked: wake === true,
                    'aria-label': 'Allow background wake',
                    disabled: typeof status.wakeEnabled !== 'boolean',
                    onChange: (event) => {
                      setDraft('jarvis:wake', event.target.checked);
                      render();
                    },
                  }),
                )
              : null,
            text('Microphone permission and competing audio can pause the listener.'),
            button(
              'Save Jarvis settings',
              async () => {
                const changes = {};
                if (model !== status.model && models.includes(model)) changes.model = model;
                if (status.wakeSupported && wake !== status.wakeEnabled) changes.wakeEnabled = wake;
                const response = await mutate('jarvis', '/remote/jarvis', changes, { success: '' });
                if (response?.jarvis) {
                  data.jarvis = response.jarvis;
                  drafts.delete(draftKey('jarvis:model'));
                  drafts.delete(draftKey('jarvis:wake'));
                  toast('Jarvis configuration verified.');
                  render();
                }
              },
              { kind: 'primary', disabled: model === status.model && wake === status.wakeEnabled },
            ),
          )
        : null,
    ),
  );
}
const validSceneRevision = (value) => typeof value === 'string' && /^[a-f0-9]{64}$/i.test(value);
function acceptScenes(response) {
  data.scenes = response.scenes || [];
  data.scenesRevision = validSceneRevision(response.revision) ? response.revision : null;
  // Incoming lists never replace a draft's captured list or reviewed revision.
}
function loadScenes() {
  return read('scenes', '/remote/scenes', acceptScenes);
}
function sceneProtectionPanel(expectedRevision = data.scenesRevision) {
  if (!validSceneRevision(expectedRevision))
    return group(
      text(
        'Conflict protection is unavailable on this Portal. Update Immortal before saving or deleting scenes.',
        'detail error',
      ),
      button('Refresh to review', loadScenes, { kind: 'outline' }),
    );
  if (!data.sceneConflict && !sceneDraft?.conflict) return null;
  return group(
    text(
      'Scene conflict: this Portal changed after you opened these routines. Your draft has been kept. Refresh to review; discard and reopen a routine before saving the current version.',
      'detail error',
    ),
    button('Refresh to review', loadScenes, { kind: 'outline' }),
    ...(data.scenes || []).map((scene) =>
      text(
        `${scene.name}${scene.schedule?.enabled ? ` · ${String(scene.schedule.hour).padStart(2, '0')}:${String(scene.schedule.minute).padStart(2, '0')} local` : ' · No enabled schedule'}`,
      ),
    ),
  );
}
function scenesPage() {
  const scenes = data.scenes,
    revision = data.scenesRevision;
  if (sceneDraft) return sceneEditor();
  return sections(
    heading('Scenes', 'Everyday routines and schedules.'),
    sceneProtectionPanel(),
    group(
      text(
        'Scenes run ordered actions on this Portal. Schedules use its local clock and are inexact on Android.',
      ),
      button('New scene', () => openScene(null), { kind: 'primary', icon: 'scenes' }),
    ),
    data.scenesError
      ? failure('Scenes unavailable', data.scenesError, () => loadVisible())
      : !scenes
        ? empty('Scenes', 'Loading your routines…')
        : !scenes.length
          ? empty(
              'No scenes yet',
              'Create a routine with navigation, app launch, media, settings, sources and waits.',
            )
          : sections(
              ...scenes.map((scene) =>
                group(
                  el('h3', { text: scene.name }),
                  text(
                    `${scene.steps?.length || 0} steps${scene.schedule?.enabled ? ` · ${String(scene.schedule.hour).padStart(2, '0')}:${String(scene.schedule.minute).padStart(2, '0')} local · ${scene.schedule.days.length} days` : ''}`,
                  ),
                  actions(
                    button('Run', () => runScene(scene.id), { kind: 'primary' }),
                    button('Edit', () => openScene(scene), { kind: 'outline' }),
                    button(
                      'Delete',
                      () => {
                        const ticket = session.capture();
                        const candidate = copy(scenes.filter((s) => s.id !== scene.id));
                        confirmAction(
                          `Delete “${scene.name}”?`,
                          'Remove the scene and its local schedule from this Portal.',
                          () => saveScenes(candidate, ticket, revision),
                          'Delete',
                          ticket,
                        );
                      },
                      { kind: 'danger', disabled: !validSceneRevision(revision) },
                    ),
                  ),
                ),
              ),
            ),
  );
}
function openScene(scene) {
  const raw = scene ? copy(scene) : { id: uniqueId(), name: '', steps: [], schedule: null };
  sceneDraft = {
    ...raw,
    steps: raw.steps.map((step) => ({ id: uniqueId(), step })),
    baseScenes: copy(data.scenes || []),
    expectedRevision: data.scenesRevision,
    conflict: false,
  };
  data.sceneConflict = false;
  loadSettings();
  read('apps', '/remote/apps', (r) => (data.apps = r.apps || []));
  render();
}
function stepSummary(step) {
  if (step.type === 'launch') return `Open ${step.packageName}`;
  if (step.type === 'key' || step.type === 'media') return `${step.type}: ${step.action}`;
  if (step.type === 'wait') return `Wait ${step.ms} ms`;
  if (step.type === 'settings')
    return `Settings · ${step.domain} · ${Object.keys(step.values || {}).join(', ')}`;
  if (step.type === 'source')
    return `Photo source · ${step.values?.folderPath || step.values?.source}`;
  if (step.type === 'text') return `Keyboard · ${step.mode}`;
  if (step.type === 'config') return `Configuration · ${step.target || 'screensaver'}`;
  return step.type || 'Action';
}
function sceneEditor() {
  const d = sceneDraft;
  const schedule = d.schedule || { enabled: false, hour: 8, minute: 0, days: [1, 2, 3, 4, 5] };
  const time = `${String(schedule.hour).padStart(2, '0')}:${String(schedule.minute).padStart(2, '0')}`;
  return sections(
    heading(d.name || 'New scene', 'Actions run in order on the selected Portal.'),
    sceneProtectionPanel(d.expectedRevision),
    el(
      'div',
      { class: 'columns' },
      sections(
        group(
          field('Scene name', d.name, (value) => (d.name = value), { key: 'scene-name' }),
          el('h3', { text: 'Steps' }),
          ...d.steps.map((entry, index) =>
            el(
              'div',
              { class: 'reorder-row' },
              el('span', { class: 'label', text: `${index + 1}. ${stepSummary(entry.step)}` }),
              actions(
                button('↑', () => moveDraftStep(entry.id, -1), {
                  kind: 'outline',
                  disabled: !index,
                }),
                button('↓', () => moveDraftStep(entry.id, 1), {
                  kind: 'outline',
                  disabled: index === d.steps.length - 1,
                }),
                button('Edit', () => stepDialog(entry, 'scene'), { kind: 'outline' }),
                button(
                  '×',
                  () => {
                    d.steps = d.steps.filter((s) => s.id !== entry.id);
                    render();
                  },
                  { kind: 'danger' },
                ),
              ),
            ),
          ),
          button('Add step', () => stepDialog(null, 'scene'), {
            kind: 'outline',
            disabled: d.steps.length >= 16,
          }),
          text('Up to 16 steps. Total wait time is limited to 5 seconds.'),
        ),
        group(
          actions(
            button('Save scene', () => saveSceneDraft(), {
              kind: 'primary',
              disabled: !validSceneRevision(d.expectedRevision) || d.conflict,
            }),
            button(
              'Discard changes',
              () => {
                sceneDraft = null;
                data.sceneConflict = false;
                render();
              },
              { kind: 'outline' },
            ),
          ),
        ),
      ),
      group(
        el('h3', { text: 'Schedule' }),
        checkbox('Enable local schedule', schedule.enabled, (value) => {
          d.schedule = { ...(d.schedule || schedule), enabled: value };
          render();
        }),
        field(
          'Time on Portal',
          time,
          (value) => {
            const [hour, minute] = value.split(':').map(Number);
            d.schedule = { ...(d.schedule || schedule), hour, minute };
          },
          { type: 'time', key: 'scene-time' },
        ),
        el(
          'div',
          { class: 'days' },
          ['Mon', 'Tue', 'Wed', 'Thu', 'Fri', 'Sat', 'Sun'].map((day, index) =>
            el(
              'label',
              {},
              day,
              el('input', {
                type: 'checkbox',
                checked: schedule.days.includes(index + 1),
                'aria-label': day,
                onChange: (event) => {
                  const current = d.schedule || schedule;
                  const days = event.target.checked
                    ? [...current.days, index + 1]
                    : current.days.filter((v) => v !== index + 1);
                  d.schedule = { ...current, days: days.sort() };
                },
              }),
            ),
          ),
        ),
        text('Schedules use the Portal’s local clock. Android may delay an inexact alarm.'),
      ),
    ),
  );
}
function moveDraftStep(id, delta) {
  const draft = sceneDraft || presetDraft;
  const index = draft.steps.findIndex((s) => s.id === id),
    to = index + delta;
  if (index >= 0 && to >= 0 && to < draft.steps.length)
    draft.steps.splice(to, 0, draft.steps.splice(index, 1)[0]);
  render();
}
async function saveSceneDraft() {
  const d = sceneDraft;
  if (!d?.name.trim() || !d.steps.length) {
    toast('Enter a scene name and add at least one step.', true);
    return;
  }
  if (d.steps.reduce((sum, s) => sum + (s.step.type === 'wait' ? s.step.ms : 0), 0) > 5000) {
    toast('Total scene wait time must be 5 seconds or less.', true);
    return;
  }
  const scene = {
    id: d.id,
    name: d.name.trim(),
    steps: d.steps.map((s) => copy(s.step)),
    schedule: d.schedule ? copy(d.schedule) : null,
  };
  if (scene.schedule?.enabled && !scene.schedule.days.length) {
    toast('Choose at least one weekday.', true);
    return;
  }
  if (!validSceneRevision(d.expectedRevision) || d.conflict) {
    toast(
      d.conflict
        ? 'Scene conflict: refresh to review before discarding and reopening your draft.'
        : 'Conflict protection is unavailable. Update Immortal before saving scenes.',
      true,
    );
    render();
    return;
  }
  const candidate = [...d.baseScenes.filter((s) => s.id !== d.id), scene];
  const response = await saveScenes(candidate, session.capture(), d.expectedRevision);
  if (response && sceneDraft === d) {
    sceneDraft = null;
    render();
  }
}
async function saveScenes(
  scenes,
  ticket = session.capture(),
  expectedRevision = data.scenesRevision,
) {
  if (!session.current(ticket)) return null;
  if (!validSceneRevision(expectedRevision)) {
    toast(
      'Conflict protection is unavailable. Update Immortal before saving or deleting scenes.',
      true,
    );
    render();
    return null;
  }
  const editor = sceneDraft;
  const response = await mutate(
    'scenes',
    '/remote/scenes',
    { scenes, expectedRevision },
    {
      ticket,
      success: '',
      onError: (error) => {
        if (error.status === 409 && error.code === 'stale_revision') {
          data.sceneConflict = true;
          if (editor && sceneDraft === editor) editor.conflict = true;
          render();
        }
      },
    },
  );
  if (response?.scenes && session.current(ticket)) {
    acceptScenes(response);
    data.sceneConflict = false;
    toast('Scenes saved and confirmed.');
    render();
  }
  return response;
}
async function runScene(id) {
  const response = await mutate(`scene:${id}`, '/remote/scene', { id }, { success: '' });
  if (response?.operation) {
    data.operations = [response.operation, ...(data.operations || [])];
    go('home', 'activity');
    toast('Scene accepted. Activity reports each step’s result.');
  }
}
function stepDialog(existing, mode) {
  let type = existing?.step.type || 'key';
  const state = existing ? copy(existing.step) : { type: 'key', action: 'home' };
  const ticket = session.capture();
  const editor = el('div');
  const err = text('');
  const types =
    mode === 'preset'
      ? [
          ['key', 'Navigation'],
          ['launch', 'Open app'],
          ['text', 'Keyboard'],
          ['wait', 'Wait'],
          ['config', 'Configuration'],
        ]
      : [
          ['key', 'Navigation'],
          ['launch', 'Open app'],
          ['media', 'Media'],
          ['settings', 'Settings'],
          ['source', 'Photo source'],
          ['wait', 'Wait'],
        ];
  const typePicker = choice(
    type,
    types.map(([value, label]) => ({ value, label })),
    (value) => {
      type = value;
      for (const key of Object.keys(state)) delete state[key];
      state.type = type;
      paint();
    },
    'Action type',
  );
  openSheet(
    existing ? 'Edit step' : 'Add a step',
    sections(
      row('Action type', '', typePicker),
      editor,
      err,
      button('Save step', save, { kind: 'primary' }),
    ),
  );
  function paint() {
    const nodes = [];
    if (type === 'key' || type === 'media') {
      const values =
        type === 'media'
          ? ['playpause', 'next', 'prev']
          : mode === 'preset'
            ? ['home', 'back', 'apps', 'power']
            : ['home', 'back', 'apps', 'screensaver', 'wake', 'sleep'];
      state.action = state.action || values[0];
      nodes.push(
        row(
          'Action',
          '',
          choice(
            state.action,
            values.map((value) => ({
              value,
              label:
                value === 'playpause' ? 'Play / pause' : value[0].toUpperCase() + value.slice(1),
            })),
            (value) => (state.action = value),
            'Step action',
          ),
        ),
      );
    } else if (type === 'launch') {
      state.packageName = state.packageName || data.apps?.[0]?.packageName || '';
      if (data.apps?.length)
        nodes.push(
          row(
            'Installed app',
            '',
            choice(
              state.packageName,
              data.apps.map((a) => ({
                value: a.packageName,
                label: a.name || a.label || a.packageName,
              })),
              (value) => {
                state.packageName = value;
                paint();
              },
              'App to launch',
            ),
          ),
        );
      nodes.push(field('Package name', state.packageName, (value) => (state.packageName = value)));
    } else if (type === 'wait') {
      state.ms = state.ms ?? 300;
      nodes.push(
        field('Wait in milliseconds', state.ms, (value) => (state.ms = Number(value)), {
          type: 'number',
          help:
            mode === 'scene'
              ? '0–5000 ms; all waits combined must fit 5000 ms.'
              : '0–10000 ms per wait.',
        }),
      );
    } else if (type === 'text') {
      state.mode = state.mode || 'set';
      state.text = state.text || '';
      nodes.push(
        row(
          'Mode',
          '',
          choice(
            state.mode,
            ['set', 'append', 'backspace', 'clear'].map((value) => ({ value, label: value })),
            (value) => (state.mode = value),
            'Keyboard mode',
          ),
        ),
        field('Text', state.text, (value) => (state.text = value)),
      );
    } else if (type === 'source') {
      state.values = state.values || { source: 'default' };
      const chosen = state.values.folderPath ? 'folder' : state.values.source || 'default';
      nodes.push(
        row(
          'Source',
          'Uses saved connections on the Portal.',
          choice(
            chosen,
            Object.entries(sourceNames).map(([value, label]) => ({ value, label })),
            (value) => {
              state.values = { source: value };
              if (value === 'folder') state.values.folderPath = '/sdcard/Pictures/Immortal';
              paint();
            },
            'Scene photo source',
          ),
        ),
      );
      if (chosen === 'folder')
        nodes.push(
          field(
            'Folder on Portal',
            state.values.folderPath || '',
            (value) => (state.values.folderPath = value),
          ),
        );
    } else if (type === 'settings') {
      const domains = (data.settings?.domains || []).filter((d) =>
        d.controls.some((c) => ['bool', 'int', 'enum'].includes(c.type)),
      );
      state.domain = state.domain || domains[0]?.id;
      const selected = domains.find((d) => d.id === state.domain);
      let key =
        Object.keys(state.values || {})[0] ||
        selected?.controls.find((c) => ['bool', 'int', 'enum'].includes(c.type))?.key;
      const controls = (selected?.controls || []).filter((c) =>
        ['bool', 'int', 'enum'].includes(c.type),
      );
      const control = controls.find((c) => c.key === key);
      state.values = state.values || (control ? { [key]: control.value } : {});
      nodes.push(
        row(
          'Category',
          '',
          choice(
            state.domain,
            domains.map((d) => ({ value: d.id, label: d.title })),
            (value) => {
              state.domain = value;
              state.values = {};
              paint();
            },
            'Step settings category',
          ),
        ),
        row(
          'Setting',
          '',
          choice(
            key,
            controls.map((c) => ({ value: c.key, label: c.title })),
            (value) => {
              state.values = { [value]: controls.find((c) => c.key === value)?.value };
              paint();
            },
            'Step setting',
          ),
        ),
      );
      if (control) {
        if (!Object.hasOwn(state.values, key)) state.values[key] = control.value;
        const value = state.values[key];
        if (control.type === 'bool')
          nodes.push(
            checkbox(control.title, value === true, (checked) => (state.values[key] = checked)),
          );
        else if (control.type === 'enum')
          nodes.push(
            row(
              'Value',
              '',
              choice(value, control.options || [], (v) => (state.values[key] = v), 'Setting value'),
            ),
          );
        else
          nodes.push(
            field('Value', value, (v) => (state.values[key] = Number(v)), {
              type: 'number',
              help: `${control.min}–${control.max}`,
            }),
          );
      } else nodes.push(text('Load Settings to choose an available control.'));
    } else if (type === 'config') {
      state.target = state.target || 'screensaver';
      state.body = state.body || {};
      nodes.push(
        row(
          'Configuration target',
          '',
          choice(
            state.target,
            [
              { value: 'screensaver', label: 'Screensaver / photo source' },
              { value: 'calendar', label: 'Calendar' },
            ],
            (value) => (state.target = value),
            'Preset config target',
          ),
        ),
      );
      let configText = JSON.stringify(state.body, null, 2);
      const area = el('textarea', {
        class: 'raw-editor',
        value: configText,
        'aria-label': 'Configuration JSON',
        onInput: (event) => {
          configText = event.target.value;
          try {
            state.body = JSON.parse(configText);
            delete state.invalid;
          } catch {
            state.invalid = true;
          }
        },
      });
      nodes.push(
        text(
          'Existing configuration steps retain their full fields. Enter the same screensaver/source or calendar JSON accepted by the previous remote.',
        ),
        area,
      );
    }
    editor.replaceChildren(...nodes);
  }
  function save() {
    try {
      if (!session.current(ticket)) return;
      if (type === 'launch' && !/^[A-Za-z][\w]*(\.[\w]+)+$/.test(state.packageName || ''))
        throw new ApiError('packageName_required');
      if (
        type === 'wait' &&
        (!Number.isInteger(state.ms) ||
          state.ms < 0 ||
          state.ms > (mode === 'scene' ? 5000 : 10000))
      )
        throw new ApiError('invalid_wait');
      if (type === 'settings' && !Object.keys(state.values || {}).length)
        throw new ApiError('invalid_value');
      if (type === 'config' && state.invalid) throw new ApiError('bad_json');
      if (type === 'source' && state.values?.folderPath)
        state.values.folderPath = photoFolder(state.values.folderPath);
      const d = mode === 'scene' ? sceneDraft : presetDraft;
      if (!d) return;
      const step = copy(state);
      delete step.invalid;
      const entry = { id: existing?.id || uniqueId(), step };
      if (existing) d.steps = d.steps.map((s) => (s.id === existing.id ? entry : s));
      else d.steps.push(entry);
      closeSheet();
      render();
    } catch (error) {
      err.className = 'error';
      err.textContent = message(error);
    }
  }
  paint();
}
function presetsPage() {
  if (presetDraft) {
    const d = presetDraft;
    return sections(
      heading(
        d.name || 'New preset',
        'Legacy presets retain app, navigation, keyboard, wait and configuration actions.',
      ),
      group(
        field('Preset name', d.name, (value) => (d.name = value), { key: 'preset-name' }),
        ...d.steps.map((entry, index) =>
          el(
            'div',
            { class: 'reorder-row' },
            el('span', { class: 'label', text: `${index + 1}. ${stepSummary(entry.step)}` }),
            actions(
              button('↑', () => moveDraftStep(entry.id, -1), { kind: 'outline', disabled: !index }),
              button('↓', () => moveDraftStep(entry.id, 1), {
                kind: 'outline',
                disabled: index === d.steps.length - 1,
              }),
              button('Edit', () => stepDialog(entry, 'preset'), { kind: 'outline' }),
              button(
                '×',
                () => {
                  d.steps = d.steps.filter((s) => s.id !== entry.id);
                  render();
                },
                { kind: 'danger' },
              ),
            ),
          ),
        ),
        actions(
          button('Add step', () => stepDialog(null, 'preset'), { kind: 'outline' }),
          button(
            'Save preset',
            async () => {
              if (!d.name.trim() || !d.steps.length) {
                toast('Enter a name and add a step.', true);
                return;
              }
              const candidate = [
                ...(data.presets || []).filter((p) => p.id !== d.id),
                { id: d.id, name: d.name.trim(), steps: d.steps.map((s) => copy(s.step)) },
              ];
              const response = await savePresets(candidate);
              if (response) {
                presetDraft = null;
                render();
              }
            },
            { kind: 'primary' },
          ),
          button(
            'Discard changes',
            () => {
              presetDraft = null;
              render();
            },
            { kind: 'outline' },
          ),
        ),
      ),
    );
  }
  return sections(
    heading('Saved presets', 'The macros from your previous remote, with a complete editor.'),
    group(
      button(
        'New preset',
        () => {
          presetDraft = { id: uniqueId(), name: '', steps: [] };
          render();
        },
        { kind: 'primary' },
      ),
    ),
    data.presetsError
      ? failure('Presets unavailable', data.presetsError, () => loadVisible())
      : !data.presets
        ? empty('Presets', 'Loading your saved macros…')
        : !data.presets.length
          ? empty('No presets yet', 'Create a sequence of app, keyboard and navigation actions.')
          : sections(
              ...data.presets.map((preset) =>
                group(
                  el('h3', { text: preset.name || 'Unnamed preset' }),
                  text(
                    `${preset.steps?.length || 0} steps · Legacy presets do not report completion`,
                  ),
                  actions(
                    button(
                      'Run',
                      () =>
                        mutate(
                          `preset:${preset.id}`,
                          '/remote/preset',
                          { id: preset.id },
                          { success: 'Preset started. Check the Portal to confirm each action.' },
                        ),
                      { kind: 'primary' },
                    ),
                    button(
                      'Edit',
                      () => {
                        presetDraft = {
                          ...copy(preset),
                          steps: (preset.steps || []).map((step) => ({ id: uniqueId(), step })),
                        };
                        render();
                      },
                      { kind: 'outline' },
                    ),
                    button(
                      'Delete',
                      () =>
                        confirmAction(
                          `Delete “${preset.name}”?`,
                          'Remove this macro from the selected Portal.',
                          () => savePresets((data.presets || []).filter((p) => p.id !== preset.id)),
                          'Delete',
                        ),
                      { kind: 'danger' },
                    ),
                  ),
                ),
              ),
            ),
  );
}
async function savePresets(presets) {
  const response = await mutate('presets', '/remote/presets', { presets }, { success: '' });
  if (response?.presets) {
    data.presets = response.presets;
    toast('Presets saved.');
    render();
  }
  return response;
}
function applyTheme() {
  const theme = storage.get(STORAGE.theme, 'system');
  if (theme === 'system') delete document.documentElement.dataset.theme;
  else document.documentElement.dataset.theme = theme;
}
applyTheme();
if (window.visualViewport) {
  const sizeSheet = () =>
    document.documentElement.style.setProperty('--sheet-vh', `${window.visualViewport.height}px`);
  window.visualViewport.addEventListener('resize', sizeSheet);
  sizeSheet();
}
document.addEventListener('visibilitychange', () => {
  if (document.hidden) {
    playbackVisibilityGeneration++;
    cancelSearchComposition();
    stopPolling();
    abortReads();
    stopPhotoObservation();
    thumbs.clear();
    appListView = null;
    settingsRowView = null;
    artwork.clear();
    previewPool.clear();
    clearTimeout(pairDiscoveryTimer);
  } else {
    // Retire old queued input; a fresh pad admits gestures after visibility resumes.
    if ((tab === 'remote' || tab === 'photos') && !detail) render();
    loadVisible();
    if (connectionState === 'offline') status();
    startPolling();
  }
});
window.addEventListener('pagehide', () => {
  playbackVisibilityGeneration++;
  cancelSearchComposition();  stopPolling();
  abortReads();
  stopPhotoObservation();
  thumbs.clear();
  appListView = null;
  settingsRowView = null;
  artwork.clear();
  previewPool.clear();
  cancelUpload(upload, 'page_closed');
});
window.addEventListener('storage', (event) => {
  if (event.key === STORAGE.roster) {
    const fresh = sanitizeRoster(parse(event.newValue, []));
    roster = fresh;
    const existing = fresh.find((r) => r.base === room()?.base);
    if (!existing || existing.token !== room()?.token) selectRoom(existing?.base || fresh[0]?.base);
    else updateHeader();
  }
});
// Remove the single-use PIN fragment immediately, before any UI render or API call.
const qr = location.hash.match(/(?:^#|&)pin=(\d{6})(?:&|$)/);
if (qr) history.replaceState(null, '', location.pathname + location.search);
const initial = activeBase(roster, storage.get(STORAGE.active, '0'), storage.get(STORAGE.selected));
if (initial) selectRoom(initial);
else {
  render();
  updateHeader();
}
if (qr) {
  pairDialog(location.origin, qr[1]);
  queueMicrotask(() => {
    sheet.querySelector('button.button.primary')?.click();
  });
}
if (navigator.storage?.persist) navigator.storage.persist().catch(() => {});
