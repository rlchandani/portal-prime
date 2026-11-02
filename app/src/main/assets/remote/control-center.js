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
  pairDiscoveryTimer = null,
  pairDialogOwner = null;
let renderedOperations = null,
  renderedOperationsError = null,
  operationsView = null,
  mediaView = null,
  appIconInventory = null,
  appListView = null,
  navigationView = null,
  headerKey = null,
  searchComposition = null,
  settingsRowView = null,
  settingsLoadState = null,
  jarvisLoadState = null,
  mediaLoadState = null;
let scope = 'this',
  selectedTargets = new Set(),
  scopeSchemas = new Map(),
  scopeErrors = [],
  scopeResults = [],
  scopeLoadState = null;
let appMode = 'installed',
  appQuery = '',
  appPage = 0,
  settingsQuery = '',
  category = '',
  advanced = false,
  photosAll = true,
  photoPath = '/sdcard',
  photoPageIndex = 0,
  photoLoadState = null;
let layoutDraft = null,
  layoutRevision = null,
  layoutLoadState = null,
  sceneDraft = null,
  scenesLoadState = null,
  presetDraft = null,
  presetsLoadState = null,
  upload = null;
const drafts = new Map(),
  settingsEditedDrafts = new Set(),
  busy = new Set(),
  pageReads = new Map(),
  expired = new Map(),
  uploadTransfers = new Map();
const thumbs = new ImagePool(),
  artwork = new ImagePool({ limit: 1 }),
  previewPool = new ImagePool({ limit: 1 });
let photoObservation = null,
  photoPreviewState = null,
  snapshotRestoreDraft = null;
let snapshotExport = null;
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
// Hold focused field renders until commitment; page/room/expiry changes bypass it.
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
    if (options?.renderOnInput !== false &&
        (!unfocusedComposition || document.activeElement === input)) render();
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
function row(title, help, control, helpId = null) {
  // A native label forwards padding taps to the checkbox once, respecting disabled.
  if (control?.matches?.('input.switch')) control = el('label', { class: 'switch-hit' }, control);
  return el(
    'div',
    { class: 'setting-row' },
    el(
      'div',
      { class: 'setting-copy' },
      el('strong', { text: title }),
      help ? el('span', { class: 'detail', id: helpId, text: help }) : null,
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
  // A passive status message must not intercept retry or navigation taps.
  $('notice').setAttribute('style', 'pointer-events: none');
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
    download_failed: 'Snapshot export could not be downloaded. Try again.',
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
    invalid_immich_endpoint: 'Enter a complete http:// or https:// Immich server address.',
    immichKey_required: 'Enter an Immich API key for this server.',
    smb_endpoint_required: 'Enter both the NAS address and share name.',
    smbPass_required: 'Enter the password for this NAS account.',
    invalid_dav_endpoint: 'Enter a complete http:// or https:// WebDAV folder address, including the server name.',
    davPass_required: 'Enter the password for this WebDAV account.',
    source_not_activated: 'The source was not activated. Check the required fields.',
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
    companion_storage_unavailable:
      'Saved scenes and Activity metadata are unavailable. They are kept unchanged. Retry after restoring readable metadata on the Portal.',
    saved_presets_unavailable:
      'Your saved presets could not be read. They are kept unchanged. Retry after restoring readable preset data on the Portal.',
    readback_failed: 'Jarvis did not confirm the requested settings. Your edits are kept.',
    unsupported_model: 'That model is no longer reported by Jarvis. Refresh and choose an available model.',
    configuration_rejected: 'Jarvis rejected these settings. Your edits are kept; check Jarvis on the Portal.',
    ipc_failed: 'Jarvis did not respond. Your edits are kept; check Jarvis on the Portal before retrying.',
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
      if (musicSheet) closeSheet();
      abortReads();
      updateHeader();
      stopPolling();
      render();
    }
  },
});
function retireRead(key) {
  pageReads.get(key)?.abort();
  pageReads.delete(key);
  session.read(key);
}
function abortReads() {
  suspendMusicSheet();
  cancelSnapshotExport();
  cancelSnapshotRestoreReview();
  // Retire completed failure notices as well as pending transport reads.
  scopeLoadState?.controller.abort();
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
    { renderAfter: true, renderWith: () => {
      if (tab === 'home' && detail === 'activity' && supported('operations') &&
          operationsView?.kind === 'activity') renderOperations();
      else render();
    } },
  );
}
function mediaPageCurrent(ticket) {
  return session.current(ticket) && !detail && (tab === 'home' || tab === 'remote') &&
    !document.hidden && connectionState !== 'expired';
}
function mediaReadCurrent(owner) {
  return !!owner && mediaLoadState === owner && mediaPageCurrent(owner.ticket) &&
    !owner.controller?.signal.aborted;
}
function validateMediaResponse(response) {
  const np = response?.np;
  if (!np || typeof np !== 'object' || Array.isArray(np) || typeof np.active !== 'boolean')
    throw new ApiError('invalid_response');
  for (const key of ['playing', 'canSeek', 'hasArt'])
    if (np[key] != null && typeof np[key] !== 'boolean') throw new ApiError('invalid_response');
  for (const key of ['title', 'artist', 'album', 'source'])
    if (np[key] != null && typeof np[key] !== 'string') throw new ApiError('invalid_response');
  // Negative duration/position can mean unknown in older media sessions.
  for (const key of ['durationMs', 'positionMs'])
    if (np[key] != null && (typeof np[key] !== 'number' || !Number.isFinite(np[key])))
      throw new ApiError('invalid_response');
  if (np.artVersion != null && typeof np.artVersion !== 'string' &&
      !(typeof np.artVersion === 'number' && Number.isFinite(np.artVersion)))
    throw new ApiError('invalid_response');
  if (np.volume !== undefined) {
    const volume = np.volume;
    if (!volume || typeof volume !== 'object' || Array.isArray(volume) ||
        !Number.isInteger(volume.max) || volume.max < 0 ||
        !Number.isInteger(volume.level) || volume.level < 0 || volume.level > volume.max)
      throw new ApiError('invalid_response');
    for (const key of ['muted', 'canSet'])
      if (volume[key] != null && typeof volume[key] !== 'boolean') throw new ApiError('invalid_response');
  }
  return np;
}
function retryMedia(owner, error, view = null) {
  if (!mediaReadCurrent(owner) || !error || data.mediaError !== error ||
      (view && mediaView !== view)) return;
  return mediaRead({ showProgress: true });
}
function mediaRead({ showProgress = false, replacePending = false } = {}) {
  if (!room() || !mediaPageCurrent(session.capture())) return;
  if (mediaReadCurrent(mediaLoadState) && mediaLoadState.pending && !replacePending)
    return mediaLoadState.promise;
  mediaLoadState?.controller?.abort();
  const owner = { ticket: session.capture(), pending: true, showProgress, controller: null };
  mediaLoadState = owner;
  const work = read('media', '/remote/nowplaying', response => {
    if (!mediaReadCurrent(owner)) throw new ApiError('aborted');
    data.media = validateMediaResponse(response);
    if (connectionState !== 'expired' && !data.companion) {
      connected = true;
      connectionState = data.statusError?.status === 404 ? 'legacy' : 'connected';
      updateHeader();
    }
  }, { renderAfter: false });
  owner.controller = pageReads.get('media');
  owner.promise = work.finally(() => {
    if (!mediaReadCurrent(owner)) return;
    owner.pending = false;
    renderMedia();
  });
  if (showProgress || !data.media) renderMedia();
  return owner.promise;
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
        await (detail === 'activity' ? loadOperations : read.bind(null, 'operations', '/remote/operations', acceptOperations))({
          showProgress: false,
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
  cancelSnapshotRestoreReview(true);
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
  // Navigation retires the prior page's status read; replace missing metadata immediately.
  if (connectionState === 'offline' || !data.companion) status();
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
function cancelPairDialog() {
  const owner = pairDialogOwner;
  pairDialogOwner = null;
  owner?.pairController?.abort();
  owner?.discoveryController?.abort();
  clearTimeout(pairDiscoveryTimer);
  pairDiscoveryTimer = null;
}
let sheetCloseHandler = null;
function finishSheetClose() {
  const onClose = sheetCloseHandler;
  sheetCloseHandler = null;
  if (onClose) onClose();
}
function openSheet(title, body, onClose = null) {
  finishSheetClose();
  cancelPairDialog();
  delete sheet.dataset.presetEditor;
  delete sheet.dataset.sceneEditor;
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
  sheetCloseHandler = onClose;
  sheet.onclose = () => {
    // A queued close event can arrive after a replacement dialog has opened.
    if (sheet.open) return;
    cancelPairDialog();
    delete sheet.dataset.presetEditor;
    delete sheet.dataset.sceneEditor;
    previewPool.clear();
    clearTimeout(pairDiscoveryTimer);
    pairDiscoveryTimer = null;
    finishSheetClose();
  };
  if (!sheet.open) sheet.showModal();
}
function closeSheet() {
  finishSheetClose();
  cancelPairDialog();
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
  let resolve, settled = false;
  const answer = new Promise((r) => (resolve = r));
  const body = sections(
    description instanceof Node ? description : text(description),
    el('div', { class: 'dialog-actions' },
      button('Cancel', () => decide(false), { kind: 'outline' }),
      button(label, () => decide(true), { kind: 'primary' }),
    ),
  );
  function decide(confirmed) {
    if (settled || !sheet.open || content.firstChild !== body || !session.current(ticket)) return;
    settled = true;
    resolve(confirmed);
    closeSheet();
  }
  openSheet(title, body, () => {
    if (settled) return;
    settled = true;
    resolve(false);
  });
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
  let addressDraft = address, pin = pinValue, pairing = false;
  const owner = { pairController: null, discoveryController: null };
  const originTicket = session.capture();
  const error = el('p', { role: 'status', 'aria-live': 'polite' });
  const peers = el('div', { class: 'sections' });
  const submitButton = button('Pair Portal', submit, { kind: 'primary' });
  const form = el(
    'form', {},
    field('Portal address or pairing link', addressDraft,
      value => { if (current() && !pairing) addressDraft = value; }, {
        type: 'text', key: 'pair-address',
        help: 'Local addresses default to port 8723. Use the same network or your existing VPN.',
      }),
    field('Six-digit PIN', pin,
      value => { if (current() && !pairing) pin = value.replace(/\D/g, '').slice(0, 6); }, {
        type: 'password', key: 'pair-pin',
        help: 'In Immortal, open Control from your phone and show a fresh PIN.',
      }),
    error, submitButton, peers,
  );
  form.addEventListener('submit', event => { event.preventDefault(); submit(); });
  openSheet('Pair a Portal', form);
  pairDialogOwner = owner;
  function current() {
    return pairDialogOwner === owner && sheet.open && content.firstChild === form &&
      session.current(originTicket);
  }
  function pending(on) {
    pairing = on;
    submitButton.disabled = on;
    submitButton.textContent = on ? 'Pairing…' : 'Pair Portal';
    for (const input of form.querySelectorAll('input')) input.disabled = on;
    for (const peer of peers.querySelectorAll('button')) peer.disabled = on;
  }
  async function discover() {
    if (!current() || pairing || !room() || owner.discoveryController) return;
    const controller = new AbortController();
    owner.discoveryController = controller;
    try {
      const response = await api(originTicket.room, '/remote/devices', { signal: controller.signal });
      if (!current() || pairing || owner.discoveryController !== controller) return;
      if (!Array.isArray(response.devices)) return;
      peers.replaceChildren(
        text('Discovered on your network'),
        ...response.devices.filter(peer => typeof peer?.host === 'string').map(peer =>
          button(peer.name || 'Portal', () => {
            if (!current() || pairing) return;
            try {
              const host = peer.host.includes(':') ? `[${peer.host}]` : peer.host;
              addressDraft = normalizeBase(`http://${host}:${peer.port || 8723}`);
              form.querySelector('[data-focus="pair-address"]').value = addressDraft;
            } catch { error.textContent = message(new ApiError('invalid_address')); }
          }, { kind: 'outline' }),
        ),
      );
    } catch { /* Discovery is optional; manual pairing remains available. */ }
    finally {
      if (owner.discoveryController === controller) {
        owner.discoveryController = null;
        if (current() && !pairing) pairDiscoveryTimer = setTimeout(discover, 10000);
      }
    }
  }
  discover();
  async function submit() {
    if (!current() || pairing) return;
    let controller = null;
    try {
      const candidate = new URL(
        /^https?:\/\//i.test(addressDraft) ? addressDraft : 'http://' + addressDraft,
      );
      const match = candidate.hash.match(/(?:^#|&)pin=(\d{6})(?:&|$)/);
      if (match && !pin) pin = match[1];
      const base = normalizeBase(addressDraft);
      if (!/^\d{6}$/.test(pin)) throw new ApiError('bad_pin');
      pending(true);
      clearTimeout(pairDiscoveryTimer); pairDiscoveryTimer = null;
      owner.discoveryController?.abort(); owner.discoveryController = null;
      controller = new AbortController(); owner.pairController = controller;
      error.className = 'detail'; error.textContent = 'Pairing…';
      const response = await api({ base }, '/remote/pair', { body: { pin }, signal: controller.signal });
      if (!current() || owner.pairController !== controller) return;
      if (typeof response.token !== 'string' || !response.token) throw new ApiError('invalid_response');
      const paired = { base, token: response.token, name: response.name || 'Portal' };
      roster = sanitizeRoster([paired, ...roster.filter(target => target.base !== base)]);
      expired.delete(base);
      closeSheet(); selectRoom(base);
      const pairedTicket = session.capture();
      await pullRoster(pairedTicket);
      if (!session.current(pairedTicket, { page: false })) return;
      await syncRoster();
      if (session.current(pairedTicket, { page: false })) toast('Portal paired.');
    } catch (err) {
      if (!current()) return;
      error.className = 'error'; error.textContent = message(err);
    } finally {
      if (current()) {
        owner.pairController = null;
        pending(false); pin = '';
        form.querySelector('[data-focus="pair-pin"]').value = '';
        discover();
      }
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
  readMusicSheet(musicSheet);
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
  if (!detail && (tab === 'home' || tab === 'remote')) mediaRead({ showProgress: force });
  if (tab === 'home' && detail === 'layout') loadLayout();
  else if (tab === 'home' && detail === 'scenes') loadScenes();
  else if (tab === 'home' && detail === 'jarvis')
    loadJarvis();
  else if (tab === 'home' && detail === 'activity')
    loadOperations();
  else if (tab === 'home' && detail === 'snapshots')
    read('snapshot', '/remote/snapshot', (r) => (data.snapshot = r));
  else if (detail === 'presets') {
    loadPresets();
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
    // Resume a scope read interrupted by navigation, hiding or explicit Refresh.
    if (settingTargets().some(target => target.base !== room().base && !scopeSchemas.has(target.base)) &&
        !scopePending() && (force || !scopeOwnerCurrent(scopeLoadState) || !scopeErrors.length)) loadScopeSchemas();
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
  updatePhotoPreview();
  stopPhotoObservation();
  const retainedMedia = mediaView?.ticket && mediaPageCurrent(mediaView.ticket) &&
    mediaView.mount.isConnected ? mediaView : null;
  operationsView = null;
  mediaView = retainedMedia;
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
  // Status refreshes rebuild surrounding chrome, but keep the current playback card
  // and its in-progress slider value. Navigation/room changes retire this view first.
  if (retainedMedia) body.querySelector('[id="media-panel"]')?.replaceWith(retainedMedia.mount);
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
  if (retainedMedia && active?.isConnected && retainedMedia.mount.contains(active))
    active.focus({ preventScroll: true });
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
    errorToast = true,
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
      if (errorToast) toast(message(error), true);
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
        data.statusError?.code === 'companion_storage_unavailable'
          ? failure('Management metadata unavailable', data.statusError, refresh)
          : !data.companion
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
      data.mediaError && !(mediaReadCurrent(mediaLoadState) && mediaLoadState.pending)
        ? failure('Playback unavailable', data.mediaError,
            (() => { const owner = mediaLoadState, error = data.mediaError;
              return () => retryMedia(owner, error); })())
        : empty('Playback', 'Loading media and volume…'),
    );
    const ticket = session.capture();
    const music = button('Queue & players', () => {
      if (music.isConnected && mediaPageCurrent(ticket)) openMusicSheet();
    }, { kind: 'outline', icon: 'volume' });
    mount.append(music);
    return;
  }
  const ticket = session.capture();
  const key = JSON.stringify([
    ticket.generation, ticket.pageGeneration, !!np.active,
    !!np.active && np.durationMs > 0, Number.isInteger(np.volume?.max),
  ]);
  if (!mediaView || mediaView.mount !== mount || mediaView.key !== key) {
    const view = { mount, key, ticket, artKey: null, playing: null };
    const owned = fn => () => {
      if (mediaView === view && mediaPageCurrent(ticket) && mount.isConnected) return fn();
    };
    const groupNode = group(el('h2', { text: 'Playback' }));
    view.art = el('img', { class: 'art', alt: '', hidden: true });
    view.fallback = el('span', { class: 'media-fallback', 'aria-hidden': 'true' }, glyph('volume'));
    view.title = el('strong');
    view.subtitle = text('');
    groupNode.append(el('div', { class: 'media-header' }, view.art, view.fallback,
      el('div', { class: 'media-title' }, view.title, view.subtitle)));
    if (np.active) {
      view.play = mediaButton('Pause', 'pause', owned(() => mediaCommand('playpause')), true);
      groupNode.append(el('div', { class: 'media-controls' },
        mediaButton('Previous', 'prev', owned(() => mediaCommand('prev'))), view.play,
        mediaButton('Next', 'next', owned(() => mediaCommand('next')))));
      if (np.durationMs > 0) {
        view.seek = el('input', { type: 'range', min: 0, max: np.durationMs,
          step: 1000, 'aria-label': 'Playback position' });
        view.current = el('span');
        view.duration = el('span');
        view.seek.oninput = () => (view.current.textContent = formatTime(Number(view.seek.value)));
        view.seek.onchange = owned(() => mediaCommand('seek', Math.min(Number(view.seek.value), data.media.durationMs)));
        groupNode.append(view.seek, el('div', { class: 'timeline-labels' }, view.current, view.duration));
      }
    }
    if (Number.isInteger(np.volume?.max)) {
      view.volume = el('input', { type: 'range', min: 0, max: np.volume.max,
        step: 1, 'aria-label': 'Portal music volume' });
      view.level = el('output');
      view.volume.oninput = () => (view.level.textContent = `${view.volume.value} / ${view.volume.getAttribute('max')}`);
      view.volume.onchange = owned(() => volumeCommand('set', Math.min(Number(view.volume.value), data.media.volume.max)));
      view.mute = button('Mute', owned(() => volumeCommand('mute')));
      view.down = button('Volume −', owned(() => volumeCommand('down')));
      view.up = button('Volume +', owned(() => volumeCommand('up')));
      view.fixed = text('This Portal reports fixed volume.');
      groupNode.append(el('div', { class: 'volume-row' }, glyph('volume'), view.volume, view.level),
        actions(view.mute, view.down, view.up), view.fixed);
    }
    view.error = text('', 'media-error error');
    view.error.hidden = true;
    view.progress = text('', 'media-refresh');
    view.progress.setAttribute('role', 'status');
    view.progress.hidden = true;
    view.retry = button('Retry playback', () => {}, { kind: 'outline' });
    view.retry.hidden = true;
    groupNode.append(view.progress, view.error, view.retry,
      button('Queue & players', owned(openMusicSheet), { kind: 'outline', icon: 'volume' }));
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
  const owner = mediaLoadState, error = data.mediaError;
  const pending = mediaReadCurrent(owner) && owner.pending;
  const refreshing = pending && owner.showProgress;
  setText(view.progress, refreshing ? 'Refreshing playback… Last reported track and volume are shown.' : '');
  setBool(view.progress, 'hidden', !refreshing);
  setText(view.error, error ? `Last reported playback. ${message(error)}` : '');
  setBool(view.error, 'hidden', !error || refreshing);
  setBool(view.retry, 'hidden', !error || pending);
  view.retry.onclick = () => retryMedia(owner, error, view);
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

// This dialog owns its reads and commands; the main PWA owns pairing and Portal selection.
let musicSheet = null;
const musicTrackCommands = new Set(['play', 'pause', 'next', 'previous', 'seek']);
function musicSheetCurrent(owner) {
  return !!owner && musicSheet === owner && sheet.open && content.firstChild === owner.root &&
    session.current(owner.ticket) && !document.hidden && connectionState !== 'expired';
}
function suspendMusicSheet(owner = musicSheet) {
  if (!owner) return;
  owner.epoch++;
  clearTimeout(owner.timer);
  owner.timer = null;
  owner.readController?.abort();
  owner.commandController?.abort();
  if (owner.sending) {
    owner.uncertain = true;
    owner.feedback = 'Request interrupted. Read the latest state before trying again.';
    owner.feedbackAfterRead = 'Latest state from the Portal. The interrupted command was not confirmed.';
  }
  owner.readController = owner.commandController = null;
  owner.sending = false;
  owner.fresh = false;
}
function closeMusicSheet(owner) {
  suspendMusicSheet(owner);
  if (musicSheet === owner) musicSheet = null;
}
function openMusicSheet() {
  if (!room() || document.hidden || connectionState === 'expired') return;
  const owner = {
    ticket: session.capture(), root: el('div', { class: 'music-sheet' }),
    epoch: 0, timer: null, readController: null, commandController: null,
    lastReadAt: -Infinity, state: null, fresh: false, sending: false, admitted: false, uncertain: false,
    unsupported: false, error: null, feedback: '', feedbackAfterRead: null, awaitingCommand: null,
    panel: 'queue', view: null,
  };
  openSheet('Music', owner.root, () => closeMusicSheet(owner));
  musicSheet = owner;
  renderMusicSheet(owner);
  readMusicSheet(owner);
}
function scheduleMusicRead(owner, delay = 1000) {
  if (!musicSheetCurrent(owner) || owner.unsupported) return;
  clearTimeout(owner.timer);
  owner.timer = setTimeout(() => {
    owner.timer = null;
    readMusicSheet(owner);
  }, Math.max(0, delay));
}
function validateMusicState(response) {
  const s = response?.music;
  const object = value => value && typeof value === 'object' && !Array.isArray(value);
  const id = value => typeof value === 'string' && value.length <= 4096;
  if (!object(s) || s.schemaVersion !== 1) throw new ApiError('music_unsupported');
  if (!Number.isSafeInteger(s.sourceRevision) || s.sourceRevision < 0 || !id(s.sourceId) ||
      !['not_configured', 'connecting', 'ready', 'offline', 'auth_required', 'incompatible'].includes(s.connection) ||
      !['playing', 'paused', 'stopped', 'idle'].includes(s.playback) ||
      typeof s.audioOwnedByOther !== 'boolean' || !object(s.capabilities) ||
      (s.controlPlayerId !== null && !id(s.controlPlayerId)) ||
      (s.queueId != null && !id(s.queueId)) ||
      (s.pending !== null && (!object(s.pending) || typeof s.pending.command !== 'string')) ||
      (s.pending?.requestId !== undefined && (!Number.isSafeInteger(s.pending.requestId) || s.pending.requestId < 1)) ||
      (s.lastCommand != null && (!object(s.lastCommand) ||
        !Number.isSafeInteger(s.lastCommand.requestId) || s.lastCommand.requestId < 1 ||
        typeof s.lastCommand.command !== 'string' ||
        !['confirmed', 'failed', 'timed_out', 'disconnected'].includes(s.lastCommand.outcome))) ||
      (s.track !== null && (!object(s.track) || !id(s.track.id) ||
        !['title', 'artist', 'album'].every(key => typeof s.track[key] === 'string') ||
        typeof s.track.isLive !== 'boolean'))) throw new ApiError('invalid_response');
  for (const key of ['play', 'pause', 'next', 'previous', 'seek', 'queue', 'lyrics', 'rooms', 'volume', 'selectPlayer'])
    if (typeof s.capabilities[key] !== 'boolean') throw new ApiError('invalid_response');
  for (const key of ['queue', 'rooms', 'lyrics'])
    if (!Array.isArray(s[key])) throw new ApiError('invalid_response');
  for (const item of s.queue.slice(0, 500))
    if (!object(item) || !id(item.id) || typeof item.title !== 'string' ||
        typeof item.artist !== 'string' || typeof item.available !== 'boolean' ||
        typeof item.canSelect !== 'boolean') throw new ApiError('invalid_response');
  for (const player of s.rooms.slice(0, 64)) {
    if (!object(player) || !id(player.id) || typeof player.name !== 'string' ||
        !['available', 'inCurrentGroup', 'canSelect', 'canJoin', 'canRemove', 'canSetVolume']
          .every(key => typeof player[key] === 'boolean') ||
        (player.volume !== null && (!Number.isInteger(player.volume) || player.volume < 0 || player.volume > 100)))
      throw new ApiError('invalid_response');
  }
  for (const line of s.lyrics.slice(0, 1000))
    if (!object(line) || typeof line.text !== 'string') throw new ApiError('invalid_response');
  return s;
}
async function readMusicSheet(owner) {
  if (!musicSheetCurrent(owner) || owner.unsupported || owner.readController || owner.sending) return;
  // Polls, command readback and refresh taps share the same one-second admission gate.
  const wait = 1000 - (Date.now() - owner.lastReadAt);
  if (wait > 0) { scheduleMusicRead(owner, wait); return; }
  clearTimeout(owner.timer);
  owner.timer = null;
  const controller = new AbortController(), epoch = owner.epoch;
  owner.readController = controller;
  owner.lastReadAt = Date.now();
  try {
    const response = await api(owner.ticket.room, '/remote/music', { signal: controller.signal });
    if (!musicSheetCurrent(owner) || epoch !== owner.epoch) return;
    const next = validateMusicState(response);
    if (owner.state && (owner.state.sourceRevision !== next.sourceRevision ||
        owner.state.sourceId !== next.sourceId)) owner.feedback = '';
    owner.state = next;
    owner.fresh = true;
    owner.error = null;
    if (owner.state.pending === null) {
      owner.admitted = false;
      owner.uncertain = false;
      if (owner.awaitingCommand) {
        const awaited = owner.awaitingCommand, result = owner.state.lastCommand;
        const matches = owner.state.sourceRevision === awaited.revision &&
          owner.state.sourceId === awaited.sourceId && result?.requestId === awaited.requestId &&
          result.command === awaited.command;
        owner.feedback = matches ? ({
          confirmed: 'Change confirmed by the player.',
          failed: 'The player could not apply this change.',
          timed_out: 'The player did not confirm this change in time.',
          disconnected: 'The player disconnected before confirming this change.',
        })[result.outcome] : 'Latest state from the Portal. The previous command was not confirmed.';
        owner.awaitingCommand = null;
      } else if (owner.feedbackAfterRead !== null) owner.feedback = owner.feedbackAfterRead;
      owner.feedbackAfterRead = null;
    }
    renderMusicSheet(owner);
  } catch (error) {
    if (!musicSheetCurrent(owner) || epoch !== owner.epoch || error.code === 'aborted') return;
    owner.fresh = false;
    owner.error = error;
    owner.unsupported = [404, 405, 501].includes(error.status) || error.code === 'music_unsupported';
    renderMusicSheet(owner);
  } finally {
    if (epoch !== owner.epoch) return;
    if (owner.readController === controller) owner.readController = null;
    scheduleMusicRead(owner);
  }
}
function musicMutable(owner, s = owner.state) {
  return musicSheetCurrent(owner) && owner.fresh && !owner.sending && !owner.admitted && !owner.uncertain &&
    s?.connection === 'ready' && !s.pending;
}
function musicCommandAllowed(s, command, extra) {
  const caps = s.capabilities;
  // Other audio blocks starting or repositioning music, while supported safe controls remain usable.
  if (s.audioOwnedByOther && ['play', 'next', 'previous', 'seek', 'queue-item'].includes(command)) return false;
  if (musicTrackCommands.has(command) && !s.track?.id) return false;
  if (['play', 'pause', 'next', 'previous'].includes(command)) return caps[command] === true;
  if (command === 'seek') return caps.seek && !s.track.isLive &&
    Number.isSafeInteger(s.track.durationMs) && s.track.durationMs > 0 &&
    Number.isSafeInteger(extra.positionMs) && extra.positionMs >= 0 && extra.positionMs <= s.track.durationMs;
  if (command === 'queue-item') return caps.queue && !!s.queueId?.trim() &&
    s.queue.slice(0, 500).some(item => item.id === extra.queueItemId && item.available && item.canSelect);
  const player = s.rooms.slice(0, 64).find(item => item.id === extra.roomId);
  if (!player?.available) return false;
  if (command === 'select-player') return caps.selectPlayer && player.canSelect;
  if (command === 'toggle-room') return caps.rooms && !!s.controlPlayerId &&
    typeof extra.wasInGroup === 'boolean' && extra.wasInGroup === player.inCurrentGroup &&
    (player.inCurrentGroup ? player.canRemove : !s.audioOwnedByOther && player.canJoin);
  if (command === 'volume') return caps.volume && player.canSetVolume &&
    Number.isInteger(extra.percent) && extra.percent >= 0 && extra.percent <= 100;
  return false;
}
async function sendMusicCommand(owner, observed, command, extra = {}) {
  if (!musicMutable(owner)) return;
  const latest = owner.state;
  const same = latest.sourceRevision === observed.sourceRevision && latest.sourceId === observed.sourceId &&
    (!musicTrackCommands.has(command) || latest.track?.id === observed.track?.id) &&
    (command !== 'queue-item' || latest.queueId === observed.queueId) &&
    (command !== 'toggle-room' || (latest.controlPlayerId === observed.controlPlayerId &&
      latest.rooms.find(player => player.id === extra.roomId)?.inCurrentGroup === extra.wasInGroup));
  if (!same || !musicCommandAllowed(latest, command, extra)) {
    owner.feedback = 'Music changed. Review the latest controls before trying again.';
    owner.fresh = false;
    renderMusicSheet(owner);
    readMusicSheet(owner);
    return;
  }
  const body = { command, sourceRevision: observed.sourceRevision, sourceId: observed.sourceId, ...extra };
  if (musicTrackCommands.has(command)) body.trackId = observed.track.id;
  if (command === 'queue-item') body.queueId = observed.queueId;
  if (command === 'toggle-room') body.leaderId = observed.controlPlayerId;
  // Retire a pre-command GET: its late result cannot clear pending admission.
  owner.epoch++;
  owner.readController?.abort();
  owner.readController = null;
  clearTimeout(owner.timer);
  owner.timer = null;
  const controller = new AbortController(), epoch = owner.epoch;
  owner.commandController = controller;
  owner.sending = true;
  owner.feedback = '';
  owner.feedbackAfterRead = '';
  owner.awaitingCommand = null;
  owner.error = null;
  renderMusicSheet(owner);
  try {
    const response = await api(owner.ticket.room, '/remote/music/command',
      { method: 'POST', body, signal: controller.signal });
    if (!musicSheetCurrent(owner) || epoch !== owner.epoch) return;
    if (response.admitted === true && response.status === 'admitted') {
      owner.admitted = true;
      owner.uncertain = false;
      owner.feedback = 'Command admitted. Waiting for the latest state…';
      const pending = response.music?.pending;
      if (Number.isSafeInteger(pending?.requestId) && pending.requestId > 0)
        owner.awaitingCommand = { requestId: pending.requestId, command: pending.command,
          revision: observed.sourceRevision, sourceId: observed.sourceId };
    } else if (response.status === 'already_applied') {
      owner.feedback = 'Already in that playback state. Reading the latest state…';
    } else throw new ApiError('invalid_response');
  } catch (error) {
    if (!musicSheetCurrent(owner) || epoch !== owner.epoch || error.code === 'aborted') return;
    owner.unsupported = [404, 405, 501].includes(error.status);
    owner.uncertain = (error.data?.admitted === null && error.data?.refreshRequired === true) ||
      error.code === 'music_request_timeout' || ['timeout', 'unreachable', 'invalid_response'].includes(error.code) ||
      (error.status >= 500 && !owner.unsupported);
    owner.feedback = ['stale', 'player_changed', 'stale_revision'].includes(error.code)
      ? 'Music changed. Refreshing state; review the controls before trying again.'
      : error.code === 'unavailable'
        ? 'That control is unavailable. Reading the latest state…'
        : 'The command was not confirmed. Reading the latest state before another request.';
    if (owner.uncertain) owner.feedback = 'Command outcome unknown. Reading the latest state…';
    owner.feedbackAfterRead = ['stale', 'player_changed', 'stale_revision'].includes(error.code)
      ? 'Music changed. Review the latest controls before trying again.'
      : error.code === 'unavailable'
        ? 'That control is unavailable in the latest state.'
        : 'Latest state from the Portal. The previous command was not confirmed.';
    owner.error = error;
  } finally {
    if (epoch !== owner.epoch) return;
    owner.commandController = null;
    owner.sending = false;
    owner.fresh = false;
    renderMusicSheet(owner);
    // A read is safe after a denial or uncertain reply. Never retry a mutation.
    readMusicSheet(owner);
  }
}
function renderMusicSheet(owner) {
  if (!musicSheetCurrent(owner)) return;
  if (owner.unsupported) {
    owner.root.replaceChildren(text(
      'Queue and player controls are unavailable on this Portal version. Existing playback controls still work.'));
    return;
  }
  const s = owner.state;
  if (!s) {
    owner.root.replaceChildren(text(owner.error ? message(owner.error) : 'Loading music…'),
      owner.error ? button('Refresh music', () => readMusicSheet(owner), { kind: 'outline' }) : null);
    return;
  }
  const usable = musicMutable(owner, s);
  const feedback = owner.sending ? 'Sending command…'
    : owner.uncertain ? 'Command outcome unknown. Reading the latest state…'
    : owner.admitted || s.pending ? 'Command pending. Waiting for the latest state…'
    : owner.error ? `${owner.feedback || 'Last reported music.'} ${message(owner.error)}`
    : owner.feedback;
  // A command outcome never replaces the current connection or interruption notice.
  const statusText = [feedback, ({
    not_configured: 'Music is not configured on this Portal.',
    connecting: 'Connecting to music…', ready: '',
    offline: 'Music is offline.', auth_required: 'Music sign-in is required on the Portal.',
    incompatible: 'This music source is not compatible.',
  })[s.connection], s.audioOwnedByOther ? 'Audio is in use on the Portal.' : '']
    .filter(Boolean).join(' ') || 'Latest state from the Portal.';
  // Keep a dragged range and its observed source/track binding; do not replace it on each tick.
  const focusedRange = owner.root.contains(document.activeElement) &&
    document.activeElement?.matches('input[type="range"],input[type="number"]');
  const identity = JSON.stringify([s.sourceRevision, s.sourceId, s.track?.id, s.controlPlayerId, s.queueId]);
  if (owner.view && owner.view.identity !== identity && owner.panel === 'lyrics') owner.panel = 'queue';
  // Executable capabilities disappear during pending readback. Retain only lyrics that
  // were readable for this exact source/track binding; never retain command admission.
  const lyricsReadable = !!s.track?.id && s.connection === 'ready' &&
    s.lyrics.length > 0 && (s.capabilities.lyrics ||
      (!!s.pending && owner.lyricsIdentity === identity));
  owner.lyricsIdentity = lyricsReadable ? identity : null;
  const key = JSON.stringify([identity, s.connection, s.playback, s.track, s.capabilities,
    s.queue.slice(0, 500), s.rooms.slice(0, 64), owner.panel,
    lyricsReadable ? s.lyrics.slice(0, 1000) : null]);
  if (!owner.view || (owner.view.key !== key && (!focusedRange || owner.view.identity !== identity ||
      owner.view.lyricsReadable !== lyricsReadable))) {
    const view = { key, identity, lyricsReadable, controls: [] };
    const bind = (node, command, extra = {}) => {
      view.controls.push({ node, command, extra });
      return node;
    };
    const control = (label, command, extra = {}, options = {}) =>
      bind(button(label, () => sendMusicCommand(owner, s, command, extra), options), command, extra);
    const title = el('div', { class: 'media-title' },
      el('strong', { text: s.track?.title || 'Nothing playing' }),
      text([s.track?.artist, s.track?.album].filter(Boolean).join(' · ')));
    view.status = text('', 'detail music-status');
    view.status.setAttribute('role', 'status');
    const nodes = [title, view.status];
    if (s.track) {
      const playing = s.playback === 'playing';
      const transport = (label, command, icon) => {
        const node = control(label, command, {}, { icon });
        node.className += ' music-transport';
        node.setAttribute('aria-label', label);
        node.replaceChildren(glyph(icon));
        return node;
      };
      nodes.push(el('div', { class: 'media-controls', role: 'group', 'aria-label': 'Music playback' },
        transport('Previous', 'previous', 'prev'),
        transport(playing ? 'Pause' : 'Play', playing ? 'pause' : 'play', playing ? 'pause' : 'play'),
        transport('Next', 'next', 'next')));
      if (s.capabilities.seek && !s.track.isLive && Number.isSafeInteger(s.track.durationMs) &&
          s.track.durationMs > 0 && Number.isSafeInteger(s.positionMs)) {
        view.seek = el('input', { type: 'range', min: 0, max: s.track.durationMs, step: 1,
          value: s.positionMs, 'aria-label': 'Music playback position' });
        view.position = el('span', { text: formatTime(s.positionMs) });
        view.seek.oninput = () => { view.position.textContent = formatTime(Number(view.seek.value)); };
        view.seek.onchange = () => sendMusicCommand(owner, s, 'seek', { positionMs: Number(view.seek.value) });
        bind(view.seek, 'seek', { positionMs: 0 });
        nodes.push(view.seek, el('div', { class: 'timeline-labels' },
          view.position, el('span', { text: formatTime(s.track.durationMs) })));
      }
    }
    const panels = [{ value: 'queue', label: 'Queue' }, { value: 'players', label: 'Players' }];
    if (lyricsReadable) panels.push({ value: 'lyrics', label: 'Lyrics' });
    if (!panels.some(panel => panel.value === owner.panel)) owner.panel = 'queue';
    nodes.push(segmented(owner.panel, panels, panel => {
      if (!musicSheetCurrent(owner)) return;
      owner.panel = panel;
      owner.view = null;
      renderMusicSheet(owner);
    }, 'Music views'));
    const list = el('div', { class: 'music-list' });
    if (owner.panel === 'queue') {
      for (const item of s.queue.slice(0, 500)) {
        const row = el('button', { class: 'action-row music-queue-row', type: 'button',
          onClick: () => sendMusicCommand(owner, s, 'queue-item', { queueItemId: item.id }) },
          el('span', { class: 'action-copy' }, el('strong', { text: item.title }),
            el('span', { class: 'detail', text: item.artist })),
          item.available ? null : el('span', { class: 'detail', text: 'Unavailable' }));
        list.append(bind(row, 'queue-item', { queueItemId: item.id }));
      }
      if (!s.queue.length) list.append(text('No queue is reported for this source.'));
    } else if (owner.panel === 'players') {
      for (const player of s.rooms.slice(0, 64)) {
        const controls = actions(control(player.id === s.sourceId ? 'Selected' : 'Use player',
          'select-player', { roomId: player.id }));
        if (player.canJoin || player.canRemove) controls.append(control(
          player.canRemove ? 'Remove from group' : 'Join group', 'toggle-room',
          { roomId: player.id, wasInGroup: player.inCurrentGroup }));
        const row = el('section', { class: 'music-player-row' },
          el('strong', { text: player.name }),
          text(!player.available ? 'Offline' : player.id === s.sourceId ? 'Selected player'
            : player.inCurrentGroup ? 'In the current group' : 'Available player'), controls);
        if (player.canSetVolume) {
          const known = Number.isInteger(player.volume);
          const range = el('input', { type: known ? 'range' : 'number', min: 0, max: 100, step: 1,
            value: known ? player.volume : '', placeholder: '0–100',
            'aria-label': `${player.name} volume` });
          const value = el('output', { text: known ? `${player.volume}%` : 'Volume not reported' });
          range.oninput = () => { value.textContent = range.value === '' ? 'Volume not reported' : `${range.value}%`; };
          const submit = () => {
            if (range.value !== '') sendMusicCommand(owner, s, 'volume',
              { roomId: player.id, percent: Number(range.value) });
          };
          range.onchange = known ? submit : null;
          bind(range, 'volume', { roomId: player.id, percent: 0 });
          row.append(el('div', { class: 'volume-row' }, range, value));
          if (!known) row.append(bind(button('Set volume', submit), 'volume', { roomId: player.id, percent: 0 }));
        } else if (Number.isInteger(player.volume)) row.append(text(`Volume ${player.volume}%`));
        list.append(row);
      }
      if (!s.rooms.length) list.append(text('No players are reported for this source.'));
    } else {
      for (const line of s.lyrics.slice(0, 1000)) list.append(text(line.text, 'music-lyric'));
    }
    nodes.push(list);
    owner.root.replaceChildren(...nodes);
    owner.view = view;
  }
  const view = owner.view;
  view.status.textContent = statusText;
  for (const { node, command, extra } of view.controls)
    node.disabled = !usable || !musicCommandAllowed(s, command, extra);
  if (view.seek && document.activeElement !== view.seek && Number.isSafeInteger(s.positionMs)) {
    view.seek.value = s.positionMs;
    view.position.textContent = formatTime(s.positionMs);
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
      !playbackCommands.hasPending(key)) mediaRead({ replacePending: true });
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
function settingsReadCurrent(owner) {
  return owner === settingsLoadState && session.current(owner.ticket) &&
    pageReads.get('settings') === owner.controller && !owner.controller.signal.aborted &&
    !document.hidden && connectionState !== 'expired';
}
function settingsPending() {
  return !!settingsLoadState?.pending && settingsReadCurrent(settingsLoadState);
}
function validateSettingsResponse(response) {
  const object = value => value && typeof value === 'object' && !Array.isArray(value);
  const named = value => typeof value === 'string' && value.trim().length > 0;
  const schema = response?.settings;
  if (!object(schema) || !Array.isArray(schema.domains)) throw new ApiError('invalid_response');
  const domains = new Set();
  for (const domain of schema.domains) {
    if (!object(domain) || !named(domain.id) || domains.has(domain.id) ||
        (domain.title !== undefined && !named(domain.title)) || !Array.isArray(domain.controls))
      throw new ApiError('invalid_response');
    domains.add(domain.id);
    const controls = new Set();
    for (const control of domain.controls) {
      if (!object(control) || !named(control.key) || controls.has(control.key) ||
          (control.title !== undefined && !named(control.title)) || !named(control.type))
        throw new ApiError('invalid_response');
      controls.add(control.key);
      if (control.type === 'enum' && control.options != null &&
          (!Array.isArray(control.options) || control.options.some(option => !object(option) ||
            !['string', 'number', 'boolean'].includes(typeof option.value) ||
            (option.label !== undefined && typeof option.label !== 'string'))))
        throw new ApiError('invalid_response');
    }
  }
  return schema;
}
function loadSettings() {
  if (!room() || document.hidden || connectionState === 'expired') return;
  if (settingsPending()) return settingsLoadState.promise;
  pageReads.get('settings')?.abort();
  const owner = { ticket: session.read('settings'), controller: new AbortController(), pending: true };
  settingsLoadState = owner;
  pageReads.set('settings', owner.controller);
  delete data.settingsError;
  owner.promise = (async () => {
    try {
      const response = await api(owner.ticket.room, '/remote/settings', { signal: owner.controller.signal });
      if (!settingsReadCurrent(owner)) return;
      const schema = validateSettingsResponse(response);
      // Rendered scalar fallbacks are seeded as drafts. Only actual edits should mask
      // a newer confirmed value; matching the previous value is not an unsaved edit.
      for (const domain of data.settings?.domains || []) for (const control of domain.controls || []) {
        if (control.secret || control.timeOfDay || !(control.type === 'string' ||
            (control.type === 'int' && control.asText))) continue;
        const key = draftKey(`setting:${domain.id}:${control.key}:draft`);
        const previous = String(control.value ?? '');
        if (!settingsEditedDrafts.has(key) && drafts.get(key) === previous) drafts.delete(key);
      }
      data.settings = schema;
      scopeSchemas.set(owner.ticket.room.base, schema);
      if (!category) category = schema.domains.find(d => visibleControls(d, advanced).length)?.id || '';
      return response;
    } catch (error) {
      if (settingsReadCurrent(owner) && error.code !== 'aborted') data.settingsError = error;
    } finally {
      const current = settingsReadCurrent(owner);
      if (pageReads.get('settings') === owner.controller) pageReads.delete('settings');
      if (current) { owner.pending = false; render(); }
    }
  })();
  render();
  return owner.promise;
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
  const owner = settingsLoadState;
  const retry = () => {
    if (session.current(ticket) && tab === 'settings' && !detail && !document.hidden &&
        connectionState !== 'expired' && data.settings === schema && settingsLoadState === owner)
      loadSettings();
  };
  const refreshNotice = settingsPending()
    ? group(text(schema ? 'Refreshing settings…' : 'Loading current settings…'))
    : data.settingsError ? group(
      el('strong', { text: schema ? 'Settings refresh failed' : 'Settings unavailable' }),
      text(message(data.settingsError)),
      schema ? text('Last confirmed settings are shown. Your unsaved edits are kept.') : null,
      button('Retry settings', retry, { kind: 'outline' })) : null;
  let controls;
  if (!schema)
    controls = refreshNotice ? null : empty('Settings', 'Loading the Portal’s settings registry…');
  else {
    const domains = (schema.domains || []).filter(
      (d) => visibleControls(d, advanced, settingsQuery).length,
    );
    const searching = settingsQuery.trim().length > 0;
    const visibleCategory = domains.some((d) => d.id === category) ? category : domains[0]?.id;
    controls = sections(
      !searching && domains.length
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
        .filter((d) => searching || d.id === visibleCategory)
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
          refreshNotice,
          scopeReadNotice(),
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
function scopePanel(disabled = false) {
  if (roster.length < 2) return null;
  const targets = settingTargets();
  const panel = group(
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
  );
  if (disabled) for (const control of [...panel.querySelectorAll('button'), ...panel.querySelectorAll('input')]) control.disabled = true;
  return panel;
}
function settingTargets(domain, control) {
  const chosen = captureTargets(roster, scope, room()?.base, selectedTargets);
  return domain === 'fleet' || (control && activeOnlySetting(domain, control.key, control))
    ? captureTargets(roster, 'this', room()?.base)
    : chosen;
}
function scopeReadKey() {
  return JSON.stringify([scope, settingTargets().map(target => target.base)]);
}
function scopeOwnerCurrent(owner) {
  return owner && scopeLoadState === owner && session.current(owner.ticket) &&
    owner.key === scopeReadKey() && !owner.controller.signal.aborted &&
    !document.hidden && connectionState !== 'expired';
}
function scopePending() {
  return !!scopeLoadState?.pending && scopeOwnerCurrent(scopeLoadState);
}
function scopeReadNotice() {
  const owner = scopeLoadState;
  if (!scopeOwnerCurrent(owner)) return null;
  if (owner.pending) return group(el('p', { role: 'status', 'aria-live': 'polite',
    class: 'detail', text: 'Loading room settings… Shared controls will be ready when the values arrive.' }));
  if (!scopeErrors.length) return null;
  return group(text(`Could not read ${scopeErrors.join(', ')}. Those rooms will report their own errors on apply.`, 'error'),
    button('Retry room settings', () => {
      if (scopeOwnerCurrent(owner)) loadScopeSchemas();
    }, { kind: 'outline' }));
}
async function loadScopeSchemas() {
  if (!room() || tab !== 'settings' || document.hidden || connectionState === 'expired' || scopePending()) return;
  pageReads.get('scope')?.abort();
  const targets = settingTargets(), peers = targets.filter(target => target.base !== room().base);
  const owner = { ticket: session.read('scope'), key: scopeReadKey(),
    controller: new AbortController(), pending: peers.length > 0 };
  scopeLoadState = owner;
  pageReads.set('scope', owner.controller);
  scopeSchemas = new Map(data.settings ? [[room().base, data.settings]] : []);
  scopeErrors = [];
  if (owner.pending) render();
  try {
    for (const target of peers) {
      if (!scopeOwnerCurrent(owner)) break;
      try {
        const r = await api(target, '/remote/settings', { signal: owner.controller.signal });
        if (!scopeOwnerCurrent(owner)) return;
        scopeSchemas.set(target.base, validateSettingsResponse(r));
      } catch (error) {
        if (!scopeOwnerCurrent(owner)) return;
        if (error.code !== 'aborted') scopeErrors.push(target.name);
      }
    }
  } finally {
    owner.pending = false;
    if (pageReads.get('scope') === owner.controller) pageReads.delete('scope');
    if (scopeOwnerCurrent(owner)) render();
  }
}

function resultPanel(results = scopeResults) {
  if (!results.length) return null;
  return group(
    el('h3', { text: 'Results by room' }),
    el(
      'div',
      { class: 'result-list' },
      results.map((result) =>
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
            'Reset shown settings',
            () => {
              const targets = settingTargets(domain.id),
                ticket = session.capture();
              // Describe the exact filtered payload, using the transport's existing room policy.
              const active = reset.filter(c => activeOnlySetting(domain.id, c.key, c));
              const shared = reset.filter(c => !activeOnlySetting(domain.id, c.key, c));
              const list = fields => el('ul', { class: 'detail' },
                fields.map(c => el('li', { text: c.title || c.key })));
              const review = sections(
                text(`${reset.length} ${reset.length === 1 ? 'setting' : 'settings'} will return to default values.`),
                shared.length ? group(
                  text(targets.length ? `Selected rooms: ${targets.map(r => r.name).join(', ')}`
                    : 'No rooms selected. Select a room to reset these settings.'),
                  list(shared)) : null,
                active.length ? group(text(`Active Portal only: ${ticket.room.name}`), list(active)) : null,
              );
              confirmAction(
                `Reset shown settings in ${domain.title}?`,
                review,
                () =>
                  applyDomain(
                    domain.id,
                    Object.fromEntries(reset.map((c) => [c.key, c.default])),
                    targets,
                    ticket,
                  ),
                'Reset settings',
                ticket,
              );
            },
            { kind: 'outline', disabled: scopePending() && reset.some(c => !activeOnlySetting(domain.id, c.key, c)) },
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
  const id = `setting:${domain}:${control.key}`;
  const activeOnly = activeOnlySetting(domain, control.key, control);
  const isBusy = busy.has(`${room()?.base}:${id}`) || (!activeOnly && scopePending());
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
  const mixedBoolean = control.type === 'bool' && mixed;
  const mixedHelpId = mixedBoolean ? `${id}:mixed` : null;
  const help = [activeOnly ? `Active Portal only: ${room().name}.` : '', baseHelp,
    mixedBoolean ? 'Mixed values. Tap to turn on for all selected rooms.' : '']
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
  const numericDraft = control.type === 'int' && control.asText && !timeOfDay;
  // Mixed numeric/time fields stay blank until edited; do not seed one room's value.
  // Include both in the live-value guard so a confirmed Save refreshes the input.
  const expectedDraft = timeOfDay || numericDraft
    ? drafts.get(draftKey(`${id}:draft`)) ??
      (mixed ? '' : timeOfDay ? timeOfDayValue(control.value) : String(control.value ?? ''))
    : draftField && !control.secret
      ? draft(`${id}:draft`, String(control.value ?? '')) : null;
  const cached = retain ? view.entries.get(key) : null;
  // A rejected/no-target change may have altered the live native control already.
  const valueMatches = cached && (control.type === 'bool'
    ? cached.input?.checked === (!mixed && control.value === true) && cached.input.indeterminate === mixed
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
      role: mixedBoolean ? 'checkbox' : 'switch',
      checked: !mixed && control.value === true,
      'aria-describedby': mixedHelpId,
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
    const current = timeOfDay || numericDraft ? expectedDraft : draft(key, control.secret ? '' : String(control.value ?? ''));
    const numericText = control.type === 'int' && !timeOfDay;
    const hasValidation = timeOfDay || numericText;
    const errorKey = `${id}:error`, errorId = `${id}:error`;
    const boundsId = `${id}:bounds`;
    const error = hasValidation ? el('p', { class: 'detail error', id: errorId,
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
      ...(hasValidation ? { 'aria-describedby': numericText ? `${boundsId} ${errorId}` : errorId,
        'aria-invalid': error.hidden ? 'false' : 'true' } : {}),
      ...(timeOfDay ? { step: 60 } : {}),
      onInput: (e) => {
        if (!ownsRow()) return;
        setDraft(key, e.target.value);
        if (!timeOfDay && !control.secret) settingsEditedDrafts.add(draftKey(key));
        if (hasValidation) {
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
        if (!ownsRow() || control.readOnly || isBusy) return;
        const value = timeOfDay ? timeOfDayMinutes(input.value)
          : control.type === 'int' ? Number(input.value) : input.value;
        if ((numericText && !input.value.trim()) || !validateValue(control, value)) {
          if (hasValidation) {
            const message = timeOfDay ? 'Choose a valid time (00:00–23:59) before applying.'
              : `Enter a whole number from ${control.min} to ${control.max}.`;
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
            settingsEditedDrafts.delete(draftKey(key));
            if (hasValidation) drafts.delete(draftKey(errorKey));
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
        numericText ? el('p', { class: 'detail', id: boundsId,
          text: `Allowed: ${control.min}–${control.max}.` }) : null,
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
    mixed && !mixedBoolean ? `${help || ''} Values differ between selected rooms.` : help,
    ui,
    mixedHelpId,
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
  if (scopePending() && Object.keys(groupValues).length) return;
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
    // A confirmed active-domain write is newer than any preceding registry refresh.
    // Rejected/unconfirmed responses must leave that read eligible to recover the view.
    if (results.some(result => result.ok && result.base === ticket.room.base &&
        result.response?.domain?.id === domain && Array.isArray(result.response.domain.controls))) {
      retireRead('settings');
      settingsLoadState = null;
    }
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
let profileApplyState = null;
function profileControlsLocked(ticket = session.capture()) {
  return !!profileApplyState?.pending || busy.has(profileCaptureKey(ticket)) || scopePending();
}
function profileApplyPanel() {
  const state = profileApplyState;
  if (!state) return null;
  const title = state.pending ? `Applying “${state.name}”…`
    : state.stopped ? 'Profile application stopped'
    : state.error ? 'Profile could not finish'
    : state.results.some((r) => !r.ok) ? 'Some profile changes need attention' : 'Profile applied';
  return group(
    el('h3', { text: title }),
    text(`Profile “${state.name}” · ${state.targets.map((target) => target.name).join(', ') || 'No ordinary rooms selected'}`),
    el('p', { class: 'detail', role: 'status', 'aria-live': 'polite', text: `${state.completed} of ${state.total} categories completed. ${state.results.filter((r) => r.ok).length} of ${state.results.length} room/category changes confirmed.` }),
    state.stopped ? text('Navigation stopped the remaining categories. Earlier confirmed changes remain; review the results before applying again.') : null,
    state.error ? text(state.error, 'error') : null,
  );
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
  if (profileControlsLocked()) return;
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
  if (!session.current(ticket) || profileControlsLocked(ticket)) return;
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
  const locked = profileControlsLocked();
  const current = readProfiles();
  if (!current.ok)
    return sections(
      heading('Settings profiles', 'Your profiles are saved in this browser.'),
      profileApplyPanel(),
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
    scopePanel(!!profileApplyState?.pending || busy.has(profileCaptureKey())),
    scopeReadNotice(),
    profileApplyPanel(),
    resultPanel(profileApplyState?.results ?? scopeResults),
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
              { kind: 'outline', disabled: locked },
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
              { kind: 'danger', disabled: locked },
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
        { kind: 'primary', disabled: locked },
      ),
    ),
  );
}
async function applyProfile(name, profile, targets, ticket) {
  if (!session.current(ticket) || profileControlsLocked(ticket)) return;
  const jobs = [];
  for (const [domain, values] of Object.entries(copy(profile || {}))) {
    if (!values || typeof values !== 'object' || Array.isArray(values)) continue;
    const safe = { ...values };
    const schemaDomain = data.settings?.domains?.find((d) => d.id === domain);
    for (const key of Object.keys(safe))
      if (schemaDomain?.controls?.find((c) => c.key === key)?.secret) delete safe[key];
    if (Object.keys(safe).length) jobs.push({ domain, values: safe, title: schemaDomain?.title || domain });
  }
  if (!jobs.length) {
    toast('This profile has no applicable settings.', true);
    return;
  }
  const state = { name, targets: targets.map((target) => Object.freeze({ ...target })), pending: true,
    completed: 0, total: jobs.length, results: [], stopped: false, error: null };
  profileApplyState = state;
  scopeResults = [];
  render();
  try {
    for (const job of jobs) {
      if (!session.current(ticket)) { state.stopped = true; break; }
      const results = await applyDomain(job.domain, job.values, state.targets, ticket);
      if (!Array.isArray(results) || !results.length) {
        state.error = 'This category could not be applied. Review the selected rooms and retry after pending changes finish.';
        break;
      }
      state.results.push(...results.map((result) => ({ ...result, name: `${result.name} · ${job.title}` })));
      state.completed++;
      if (session.current(ticket)) render();
    }
  } catch (error) {
    state.error = message(error);
  } finally {
    state.pending = false;
    if (session.current(ticket)) {
      scopeResults = state.results;
      render();
      if (state.error) toast(`Profile “${name}” could not finish. ${state.error}`, true);
      else toast(`Profile “${name}”: ${state.results.filter((r) => r.ok).length} of ${state.results.length} room/category changes confirmed.`, state.results.some((r) => !r.ok));
    }
  }
}

function acceptSources(response, { calendar = true } = {}) {
  const source = { ...(response.sources || {}) };
  for (const key of ['immichKey', 'smbPass', 'davPass']) {
    source[`${key}Configured`] = source[`${key}Configured`] === true || !!source[key];
    delete source[key];
  }
  data.sources = source;
  if (calendar) {
    invalidateAlbumQuery();
    data.calendar = response.calendar;
  }
}
async function loadSources() {
  return read('sources', '/remote/sources', acceptSources);
}
function sourceSaving(target = room()) {
  return busy.has(`${target?.base}:source`);
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
function albumConnection() {
  const sources = data.sources || {};
  return { base: room()?.base, sources,
    url: draft('source:immichUrl', sources.immichUrl || '').trim(),
    key: draft('source:immichKey', '') };
}
function albumConnectionMatches(connection) {
  if (!connection || draft('source:selected', data.sources?.source || 'default') !== 'immich') return false;
  const current = albumConnection();
  return connection.base === current.base && connection.sources === current.sources &&
    connection.url === current.url && connection.key === current.key;
}
function albumQueryLoading() {
  return busy.has(`${room()?.base}:albums`);
}
function invalidateAlbumQuery() {
  const owner = data.albumState;
  if (!owner) return false;
  owner.controller?.abort();
  delete data.albumState;
  return true;
}
const sourceErrorFields = {
  invalid_immich_endpoint: ['immichUrl'], immichKey_required: ['immichKey'],
  smb_endpoint_required: ['smbHost', 'smbShare'], smbPass_required: ['smbPass'],
  invalid_dav_endpoint: ['davUrl'], davPass_required: ['davPass'],
  invalid_path: ['folderPath'], invalid_album_link: ['albumUrl'], invalid_web_url: ['webUrl'],
};
function clearSourceError() {
  if (!data.sourceSaveError) return;
  delete data.sourceSaveError;
  const view = $('page').querySelector('[data-source-error]');
  if (view) { view.hidden = true; view.textContent = ''; }
  for (const input of $('page').querySelectorAll('[aria-describedby="photo-source-error"]')) {
    input.removeAttribute('aria-invalid'); input.removeAttribute('aria-describedby');
  }
}
function sourceField(key, label, secret, source) {
  const field = searchField(label, draft(`source:${key}`,
    secret ? '' : source[key] || (key === 'folderPath' ? '/sdcard/Pictures/Immortal' : '')),
    (value) => {
      setDraft(`source:${key}`, value);
      clearSourceError();
      if (['immichUrl', 'immichKey'].includes(key) && data.albumState &&
          !albumConnectionMatches(data.albumState.connection)) {
        invalidateAlbumQuery();
        render();
      }
    }, {
      renderOnInput: false,
      type: secret ? 'password' : 'text', key: `source:${key}`,
      placeholder: secret && source[`${key}Configured`] ? 'Keep saved credential' : '',
      help: key === 'webUrl'
        ? 'Use a complete http:// or https:// URL with a host. A valid format does not guarantee the page will load.'
        : secret ? 'Leave blank to keep a credential for the same endpoint and account.' : '',
    });
  if (sourceErrorFields[data.sourceSaveError?.code]?.includes(key)) {
    const input = field.querySelector('input');
    input.setAttribute('aria-invalid', 'true');
    input.setAttribute('aria-describedby', 'photo-source-error');
  }
  return field;
}

function albumControls(source) {
  const connection = albumConnection();
  const state = albumConnectionMatches(data.albumState?.connection) ? data.albumState : null;
  const loading = albumQueryLoading(), albums = state?.albums || [];
  const selected = draft('source:immichAlbumId', source.immichAlbumId || '');
  const name = draft('source:immichAlbumName', source.immichAlbumName || '');
  const options = [{ value: '', label: 'Whole library' },
    ...albums.map((album) => ({ value: album.id, label: `${album.name} (${album.count})` }))];
  if (selected && !albums.some((album) => album.id === selected))
    options.push({ value: selected, label: `Current album · ${name || selected}` });
  const picker = choice(selected, options, (id) => {
    if (albumQueryLoading() || !albumConnectionMatches(connection) ||
        !options.some((option) => option.value === id)) return;
    setDraft('source:immichAlbumId', id);
    setDraft('source:immichAlbumName', id ? albums.find((album) => album.id === id)?.name || name : '');
    render();
  }, 'Immich album');
  picker.disabled = loading;
  return group(
    actions(button(loading ? 'Loading albums…' : state?.error ? 'Try albums again' : 'Load albums',
      loadAlbums, { kind: 'outline', disabled: loading || sourceSaving() }), picker),
    loading ? el('p', { role: 'status', 'aria-live': 'polite', class: 'detail', text: 'Loading albums…' }) : null,
    state?.error ? text(`Couldn't load albums. ${state.error}${albums.length ? ' Showing previously loaded albums.' : ''}`, 'error')
      : state?.albums?.length === 0 ? text('No albums found. You can use Whole library.')
      : !state?.albums ? text('Load albums to choose from this connection. Whole library is always available.') : null,
  );
}
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
  const saving = sourceSaving();
  const editor = group(
    text(`Active on Portal: ${sourceNames[s.source === 'local' ? 'folder' : s.source] || 'Unknown source'}`),
    saving ? el('p', { role: 'status', 'aria-live': 'polite', class: 'detail',
      text: 'Saving source… Any newer edits will need another activation.' }) : null,
    row(
      'Source',
      'Choose what your Portal displays.',
      choice(
        selected,
        options,
        (value) => {
          setDraft('source:selected', value);
          clearSourceError();
          if (data.albumState && !albumConnectionMatches(data.albumState.connection)) invalidateAlbumQuery();
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
      sourceField(key, label, secret, s)),
    selected === 'immich' && !hidden ? albumControls(s) : null,
    el('p', { id: 'photo-source-error', 'data-source-error': '', role: 'alert',
      class: 'error', hidden: !data.sourceSaveError || data.sourceSaveError.selected !== selected,
      text: data.sourceSaveError?.selected === selected ? data.sourceSaveError.message : '' }),
    actions(
      button(
        selected === 'folder' ? 'Use this folder' : 'Activate source',
        () => saveSource(selected, s),
        { kind: 'primary', disabled: hidden || saving },
      ),
      button('Preview active source', () => dispatchKey('screensaver'), {
        kind: 'outline', disabled: saving,
      }),
    ),
    text('Connections apply to the selected Portal. Credentials are not copied to other rooms.'),
  );
  const calendar = group(
    el('h3', { text: 'Calendar feed' }),
    text(data.calendar?.url ? 'A calendar feed is saved on this Portal.' : 'No calendar feed is saved.'),
    calendarSaving() ? el('p', { role: 'status', 'aria-live': 'polite', class: 'detail',
      text: 'Saving calendar feed… Any newer edits will need another save.' }) : null,
    searchField(
      'Calendar feed URL',
      draft('calendar-url', data.calendar?.url || ''),
      (v) => setDraft('calendar-url', v),
      { key: 'calendar-url', renderOnInput: false,
        help: 'Use an iCalendar feed. Clear the address and save to remove the feed after confirmation.' },
    ),
    button('Save calendar feed', () => saveCalendarFeed(),
      { kind: 'primary', disabled: calendarSaving() }),
  );
  return sections(
    heading('Photo sources', 'Connect a collection and preview it on your Portal.'),
    el('div', { class: 'columns' }, sections(editor), sections(calendar)),
  );
}
function calendarSaving(target = room()) {
  return busy.has(`${target?.base}:calendar`);
}
async function saveCalendarFeed({ confirmedRemoval = false } = {}) {
  if (!room() || calendarSaving()) return;
  const target = room(), ticket = session.capture(), key = draftKey('calendar-url');
  const raw = draft('calendar-url', data.calendar?.url || '');
  const submitted = raw.trim(), previous = data.calendar?.url || '';
  if (submitted === previous) {
    toast('Calendar feed already saved.');
    return;
  }
  if (!submitted && previous && !confirmedRemoval) {
    return confirmAction('Remove calendar feed?',
      `Remove the saved calendar feed from ${target.name}? Calendar events from this feed will no longer appear.`,
      () => {
        if (drafts.get(key) !== raw || data.calendar?.url !== previous) {
          toast('The calendar feed changed. Review it before removing.', true);
          return;
        }
        return saveCalendarFeed({ confirmedRemoval: true });
      }, 'Remove feed', ticket);
  }
  try {
    const pending = mutate('calendar', '/remote/sources', { calendarUrl: submitted },
      { target, ticket, success: '' });
    render();
    const response = await pending;
    if (!response) return;
    if (response.calendar?.url !== submitted) {
      toast('Calendar feed was not confirmed. Refresh and review it.', true);
      return;
    }
    const newer = drafts.get(key) !== raw;
    // Accept this Calendar snapshot only; a source save owns its separate snapshot.
    pageReads.get('sources')?.abort();
    session.read('sources');
    data.calendar = response.calendar;
    toast(`${submitted ? 'Calendar feed saved' : 'Calendar feed removed'} and confirmed.${newer ? ' Your newer edits are still unsaved.' : ''}`);
  } finally {
    if (room()?.base === target.base && tab === 'photos' && detail === 'sources') render();
  }
}
async function loadAlbums() {
  if (!room() || tab !== 'photos' || detail !== 'sources' || !advanced ||
      draft('source:selected', data.sources?.source || 'default') !== 'immich' ||
      albumQueryLoading() || sourceSaving()) return;
  const target = room(), ticket = session.capture(), connection = albumConnection();
  const previous = albumConnectionMatches(data.albumState?.connection) ? data.albumState.albums : undefined;
  const controller = new AbortController();
  const owner = { connection, controller, albums: previous, error: null };
  const busyKey = `${target.base}:albums`, body = { url: connection.url };
  if (connection.key) body.key = connection.key;
  data.albumState = owner;
  busy.add(busyKey);
  pageReads.set('albums', controller);
  render();
  const current = () => data.albumState === owner && session.current(ticket) &&
    albumConnectionMatches(owner.connection) && !document.hidden;
  try {
    const response = await api(target, '/remote/immich/albums', { body, signal: controller.signal });
    if (!current()) return;
    const albums = response.albums, ids = new Set();
    if (!Array.isArray(albums) || albums.some((album) => {
      if (!album || typeof album.id !== 'string' || !album.id || ids.has(album.id) ||
          typeof album.name !== 'string' || !Number.isInteger(album.count) || album.count < 0) return true;
      ids.add(album.id);
      return false;
    })) throw new ApiError('invalid_response');
    owner.albums = albums.map((album) => ({ id: album.id, name: album.name, count: album.count }));
  } catch (error) {
    if (current() && error.code !== 'aborted') owner.error = message(error);
  } finally {
    busy.delete(busyKey);
    if (pageReads.get('albums') === controller) pageReads.delete('albums');
    owner.controller = null;
    if (session.current(ticket) && detail === 'sources') render();
  }
}

async function saveSource(selected, s) {
  if (!room() || sourceSaving()) return;
  const target = room(), ticket = session.capture(), submitted = new Map();
  const selectedKey = draftKey('source:selected');
  const submittedSelected = draft('source:selected', selected);
  clearSourceError();
  const reportError = (error) => {
    if (!session.current(ticket) || draft('source:selected', selected) !== submittedSelected ||
        [...submitted].some(([key, value]) => drafts.has(key) && drafts.get(key) !== value)) return;
    data.sourceSaveError = { selected, code: error.code, message: message(error) };
  };
  try {
    const body = {};
    if (selected === 'default') body.source = 'default';
    for (const [key, , secret] of sourceFields[selected] || []) {
      const value = draft(
        `source:${key}`,
        secret ? '' : s[key] || (key === 'folderPath' ? '/sdcard/Pictures/Immortal' : ''),
      );
      submitted.set(draftKey(`source:${key}`), value);
      if (secret) {
        if (value) body[key] = value;
      } else body[key] = key === 'folderPath' ? photoFolder(value) : value.trim();
    }
    const pending = mutate('source', '/remote/sources', body, { target, ticket, success: '',
      onError: reportError, errorToast: false });
    render();
    const response = await pending;
    if (!response) return;
    const actual = response.sources?.source;
    if (actual !== selected && !(selected === 'folder' && actual === 'local')) {
      reportError(new ApiError('source_not_activated'));
      return;
    }
    const newer = drafts.get(selectedKey) !== submittedSelected ||
      [...submitted].some(([key, value]) => drafts.get(key) !== value);
    const albumOwner = albumConnectionMatches(data.albumState?.connection) ? data.albumState : null;
    for (const [key, , secret] of sourceFields[selected] || []) {
      const ownerKey = `${target.base}:source:${key}`;
      if (secret && body[key] && drafts.get(ownerKey) === submitted.get(ownerKey))
        drafts.delete(ownerKey);
    }
    // The mutation already returns its saved snapshot. Invalidate any older source read.
    pageReads.get('sources')?.abort();
    session.read('sources');
    acceptSources(response, { calendar: false });
    if (albumOwner && data.albumState === albumOwner) albumOwner.connection = albumConnection();
    delete data.sourcesError;
    toast(newer ? `${sourceNames[selected]} saved. Your newer edits are still unsaved.`
      : 'Source saved and confirmed.');
  } catch (error) {
    reportError(error);
  } finally {
    if (room()?.base === target.base && detail === 'sources') render();
  }
}
function currentPhotoCollection() {
  const info = data.photos;
  const all = photosAll && supported('photoLibrary');
  return info && info.all === all && info.path === (all ? '/sdcard' : photoPath) ? info : null;
}
function photoReadCurrent(owner) {
  const all = photosAll && supported('photoLibrary');
  return !!owner && photoLoadState === owner && session.current(owner.ticket) &&
    !document.hidden && connectionState !== 'expired' && !owner.controller?.signal.aborted &&
    owner.path === (all ? '/sdcard' : photoPath) && owner.recursive === all;
}
function photosPending() {
  return !!photoLoadState?.pending && photoReadCurrent(photoLoadState);
}
function photoActionCurrent(file, ticket, collection) {
  return session.current(ticket) && tab === 'photos' && !detail && !document.hidden &&
    connectionState !== 'expired' && supported('photos') && !!collection &&
    confirmedPhotoCollection() === collection && collection.files.includes(file);
}
function validatePhotoResponse(response, path) {
  const object = value => value && typeof value === 'object' && !Array.isArray(value);
  const named = value => typeof value === 'string' && value.trim().length > 0;
  const number = value => typeof value === 'number' && Number.isFinite(value) && value >= 0;
  if (!object(response) || response.path !== path || !Array.isArray(response.files) ||
      (response.folders != null && !Array.isArray(response.folders)) ||
      ['recursive', 'truncated'].some(key => response[key] != null && typeof response[key] !== 'boolean') ||
      (response.freeBytes != null && !number(response.freeBytes))) throw new ApiError('invalid_response');
  for (const [rows, numeric] of [[response.files, ['size', 'modified']], [response.folders || [], ['count']]]) {
    const seen = new Set();
    for (const item of rows) {
      if (!object(item) || !named(item.path) || seen.has(item.path) ||
          (item.name != null && typeof item.name !== 'string') ||
          numeric.some(key => item[key] != null && !number(item[key]))) throw new ApiError('invalid_response');
      seen.add(item.path);
    }
  }
}
function confirmedPhotoCollection() {
  return !photosPending() && !data.photosError ? currentPhotoCollection() : null;
}
async function loadPhotos({ force = false } = {}) {
  if (!room() || document.hidden || connectionState === 'expired') return;
  if (data.companion && !supported('photoLibrary') && !supported('photos')) return;
  if (data.companion && !supported('photoLibrary')) photosAll = false;
  const path = photosAll && supported('photoLibrary') ? '/sdcard' : photoPath;
  const recursive = photosAll && supported('photoLibrary');
  // A completed mutation invalidates listings begun before it; ordinary refreshes coalesce.
  if (!force && photosPending()) return photoLoadState.promise;
  const owner = { ticket: session.capture(), path, recursive, pending: true };
  photoLoadState = owner;
  delete data.photosError;
  owner.promise = read(
      'photos',
      `/remote/photos?path=${encodeURIComponent(path)}${recursive ? '&recursive=1' : ''}`,
      (r) => {
        if (!photoReadCurrent(owner)) return;
        validatePhotoResponse(r, path);
        const listing = photoListing(r, recursive);
        // A legacy shallow response changes the selected mode before the one final render.
        if (recursive && !listing.all) {
          photosAll = false;
          photoPath = listing.path;
          setDraft('photo-folder', photoPath);
          owner.path = listing.path;
          owner.recursive = listing.all;
        }
        data.photos = listing;
        data.photoMetadataRevision = (data.photoMetadataRevision || 0) + 1;
        if (listing.all) data.photoFolders = listing.folders;
        else if (data.photoFolders?.length) listing.folders = data.photoFolders;
        photoPageIndex = Math.min(photoPageIndex, Math.max(0, Math.ceil(listing.files.length / 24) - 1));
      },
      { renderAfter: false },
    ).finally(() => {
      if (photoReadCurrent(owner)) { owner.pending = false; render(); }
    });
  owner.controller = pageReads.get('photos');
  render();
  return owner.promise;
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
  const info = currentPhotoCollection();
  const folders = data.photoFolders || info?.folders || [];
  const pending = photosPending();
  const loading = pending ? el('p', { role: 'status', 'aria-live': 'polite', class: 'detail', text: info ? 'Refreshing photos…' : 'Loading photo metadata…' }) : null;
  const files = info ? photoPage(info.files, photoPageIndex) : [];
  const owner = photoLoadState;
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
        if (value === (photosAll ? 'all' : 'folder')) return;
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
          folders.length
            ? choice(
                photoPath,
                [
                  { value: photoPath, label: photoPath },
                  ...folders
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
            const selected = confirmedPhotoCollection();
            if (!selected) return;
            const targetPath = selected.all ? '/sdcard' : selected.path;
            const ticket = session.capture();
            confirmAction(
              'Use this collection?',
              `Set ${room().name}’s slideshow folder to ${targetPath}.`,
              async () => {
                const current = confirmedPhotoCollection();
                if (!current || current.path !== targetPath || current.all !== selected.all) {
                  toast('The collection changed. Review it and choose Use for slideshow again.', true);
                  return;
                }
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
          { kind: 'outline', disabled: !confirmedPhotoCollection() },
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
    info ? loading : null,
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
  const retry = () => {
    if (session.current(ticket) && tab === 'photos' && !detail && !document.hidden &&
        connectionState !== 'expired' && photoLoadState === owner && currentPhotoCollection() === info)
      loadPhotos();
  };
  const refreshNotice = !pending && info && data.photosError ? group(
    el('strong', { text: 'Photo refresh failed' }), text(message(data.photosError)),
    text('Last confirmed photos are shown. Refresh and review the photo list before deleting or changing the slideshow collection.'),
    button('Retry photos', retry, { kind: 'outline' })) : null;
  let gallery;
  if (!pending && !info && data.photosError) gallery = failure('Photos unavailable', data.photosError, retry);
  else if (!info && pending) gallery = group(el('h3', { text: 'Photo library' }), loading);
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
    refreshNotice,
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
  const load = () => {
    const key = photoThumbnailKey(file, ticket);
    // Viewing retained metadata is safe while refresh is pending or failed; deletion
    // separately requires confirmedPhotoCollection(). Visibility resume clears the
    // image pool, so known photos must still be allowed back through viewport admission.
    if (!currentPhotoCollection()?.files.includes(file)) return;
    return thumbs.add(
      key,
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
  };
  // Already admitted work rebinds before layout; only new requests wait for visibility.
  if (thumbs.entries.has(photoThumbnailKey(file, ticket))) load();
  else observePhotoTile(tile, ticket, load);
  return tile;
}
function updatePhotoPreview() {
  const owner = photoPreviewState;
  if (!owner || !sheet.open || !owner.remove?.isConnected) return;
  const allowed = photoActionCurrent(owner.file, owner.ticket, owner.collection);
  owner.remove.disabled = !allowed;
  owner.hint.hidden = allowed;
}
function photoPreview(file, ticket) {
  if (!session.current(ticket) || tab !== 'photos' || detail || document.hidden ||
      connectionState === 'expired' || !currentPhotoCollection()?.files.includes(file)) return;
  const collection = confirmedPhotoCollection();
  const canDelete = () => photoActionCurrent(file, ticket, collection);
  const reviewAgain = () => toast('Refresh the photo list and review this photo before deleting.', true);
  const refreshHint = text('Refresh the photo list and open this photo again before deleting.', 'detail');
  const image = el('img', {
    class: 'preview-image',
    alt: file.name || 'Photo preview',
    hidden: true,
  });
  const status = text('Loading preview…');
  const retry = button('Retry preview', () => load(), { kind: 'outline' });
  retry.hidden = true;
  const remove = button(
  'Delete photo',
  () => {
    if (!canDelete()) { reviewAgain(); return; }
    closeSheet();
    confirmAction(
      `Delete ${file.name || 'this photo'}?`,
      `Permanently remove this photo from ${ticket.room.name}.`,
      async () => {
        if (!canDelete()) { reviewAgain(); return; }
        const response = await mutate(
          'delete-photo',
          `/remote/photo?path=${encodeURIComponent(file.path)}`,
          undefined,
          { method: 'DELETE', ticket, success: 'Photo deleted.' },
        );
        if (response) loadPhotos({ force: true });
      },
      'Delete',
      ticket,
    );
  },
  { kind: 'danger', disabled: !canDelete() },
);
  openSheet(
    file.name || 'Photo preview',
    sections(
      image,
      status,
      retry,
      text(file.path),
      refreshHint,
      el(
        'div',
        { class: 'dialog-actions' },
        remove,
      ),
    ),
    () => { photoPreviewState = null; },
  );
  photoPreviewState = { file, ticket, collection, hint: refreshHint, remove };
  updatePhotoPreview();
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
  let destination = photosAll && supported('photoLibrary')
    ? '/sdcard/Pictures/Immortal'
    : photoPath;
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
    await loadPhotos({ force: true });
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
  if (!room() || document.hidden || connectionState === 'expired' ||
      (key === 'catalog' && data.companion && !supported('catalog'))) return;
  // A retry joins the active read; explicit Refresh still cancels and replaces it.
  if (!changedOnly && pageReads.get(key) && !pageReads.get(key).signal.aborted) return;
  const ticket = session.capture(), snapshot = data,
    hadVisibleProgress = !!data[`${key}Loading`];
  delete snapshot[`${key}Error`];
  const pending = read(key, `/remote/${key}`, (response) => {
    if (!Array.isArray(response.apps) || response.apps.some(app => !app || typeof app !== 'object'))
      throw new ApiError('invalid_response');
    // Quiet completion with identical metadata keeps the retained card/action snapshot.
    // Manual refresh still replaces inventory and invalidates artwork as before.
    if (!changedOnly || JSON.stringify(snapshot[key]) !== JSON.stringify(response.apps))
      snapshot[key] = response.apps;
  }, { renderAfter: false });
  const owner = pageReads.get(key);
  snapshot[`${key}Loading`] = owner;
  if (!changedOnly) render();
  else if (operationsView?.kind === 'apps') updateAppRecords(operationsView.records);
  try { return await pending; }
  finally {
    if (snapshot[`${key}Loading`] === owner) delete snapshot[`${key}Loading`];
    if (!session.current(ticket) || document.hidden ||
        (appMode === 'catalog' ? 'catalog' : 'apps') !== key) return;
    // Automatic completion reads retain the existing metadata-based rendering policy.
    if (!changedOnly || hadVisibleProgress || !operationsView || operationsView.kind !== 'apps' ||
        operationsView.key !== key || JSON.stringify(data[key]) !== operationsView.metadata ||
        data[`${key}Error`]) render();
    else updateAppRecords(operationsView.records);
  }
}
function catalogActionAvailable(app, ticket) {
  return session.current(ticket) && appMode === 'catalog' &&
    !data.catalogLoading && !data.catalogError && data.catalog?.includes(app);
}
function appsPage() {
  const entries = appMode === 'catalog' ? data.catalog : data.apps;
  const q = appQuery.trim().toLowerCase();
  const filtered = (entries || []).filter((app) =>
    [app.name, app.label, app.packageName, app.description].some((v) =>
      String(v || '')
        .toLowerCase()
        .includes(q),
    ),
  );
  appPage = Math.min(appPage, Math.max(0, Math.ceil(filtered.length / 24) - 1));
  const key = appMode === 'catalog' ? 'catalog' : 'apps';
  const error = data[`${key}Error`], loading = !!data[`${key}Loading`];
  const visibleApps = filtered.slice(appPage * 24, (appPage + 1) * 24);
  // Fresh server inventory invalidates artwork, including updates without version metadata.
  if (appIconInventory !== entries) {
    thumbs.clear();
    appIconInventory = entries;
  }
  thumbs.retain(visibleApps
    .filter(app => (appMode === 'installed' || app.installed === true) && app.packageName)
    .map(app => app.packageName));
  const ticket = session.capture();
  let records = new Map(), list = null;
  if (entries && filtered.length) {
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
          if (value === appMode) return;
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
    entries && loading
      ? group(el('p', { class: 'detail', role: 'status', text: 'Refreshing apps…' }),
          appMode === 'catalog' ? text('Refresh the catalog before installing or updating.') : null)
      : entries && error
        ? group(el('h3', { text: 'Apps unavailable from Portal' }), text(message(error), 'error'),
            text('Showing the last confirmed list.'),
            appMode === 'catalog' ? text('Refresh the catalog before installing or updating.') : null,
            button('Retry', () => loadApps(), { kind: 'outline', disabled: loading }))
        : null,
    !entries && error
      ? failure('Apps unavailable', error, () => loadApps())
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
          () => {
            if (!catalogActionAvailable(app, ticket)) {
              if (session.current(ticket)) toast('Refresh the catalog before installing or updating.', true);
              return;
            }
            return beginOperation({ type: action, packageName: pkg }, ticket);
          },
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
        installDisabled || !catalogActionAvailable(app, ticket) ||
        operation?.state === 'running' || operation?.installUncertain === true;
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
function layoutPending() {
  return !!layoutLoadState?.pending && session.current(layoutLoadState.ticket);
}
function layoutSaveReadBlocked() {
  return layoutPending() && (layoutLoadState.replacing || layoutLoadState.recovering);
}
function layoutViewCurrent(ticket, confirmed, owner) {
  return session.current(ticket) && tab === 'home' && detail === 'layout' && !document.hidden &&
    connectionState !== 'expired' && data.layout === confirmed && layoutLoadState === owner;
}
function validateLayoutResponse(response) {
  const object = value => value && typeof value === 'object' && !Array.isArray(value);
  const layout = response?.layout;
  if (!object(response) || typeof response.revision !== 'string' || !response.revision.trim() ||
      !object(layout) || !object(layout.assignments) ||
      Object.values(layout.assignments).some(value => typeof value !== 'string') ||
      ['homeOrder', 'gridOrder'].some(key => !Array.isArray(layout[key]) ||
        layout[key].some(value => typeof value !== 'string')) ||
      !Array.isArray(layout.gridSlots) || layout.gridSlots.some(value => value !== null && typeof value !== 'string') ||
      !Array.isArray(layout.widgets) || layout.widgets.some(widget => !object(widget) ||
        typeof widget.kind !== 'string' || !widget.kind.trim() ||
        !Number.isInteger(widget.spanX) || !Number.isInteger(widget.spanY) ||
        (widget.kind === 'appwidget'
          ? !Number.isInteger(widget.id) || typeof widget.package !== 'string' || typeof widget.class !== 'string'
          : typeof widget.customId !== 'string' || !widget.customId)) ||
      (response.widgetKinds !== undefined && (!Array.isArray(response.widgetKinds) ||
        response.widgetKinds.some(kind => typeof kind !== 'string' || !kind.trim()))))
    throw new ApiError('invalid_response');
  const keys = layout.widgets.map(widgetKey);
  if (new Set(keys).size !== keys.length) throw new ApiError('invalid_response');
}
function loadLayout({ replaceDraft = null } = {}) {
  if (!room() || document.hidden || connectionState === 'expired') return;
  if (layoutPending() && pageReads.get('layout') && !pageReads.get('layout').signal.aborted)
    return layoutLoadState.promise;
  const owner = { ticket: session.capture(), pending: true, replacing: !!replaceDraft,
    recovering: !!data.layoutError };
  layoutLoadState = owner;
  delete data.layoutError;
  render();
  owner.promise = read('layout', '/remote/layout', response => {
    validateLayoutResponse(response);
    data.layout = response;
    if (!layoutDraft || (replaceDraft && JSON.stringify(layoutDraft) === replaceDraft.value &&
        layoutRevision === replaceDraft.revision)) {
      layoutDraft = copy(response.layout);
      layoutRevision = response.revision;
    } else if (replaceDraft) {
      toast('Current layout loaded. Your newer edits are still unsaved.');
    }
  }, { renderAfter: false }).finally(() => {
    if (layoutLoadState === owner && session.current(owner.ticket)) {
      owner.pending = false;
      render();
    }
  });
  return owner.promise;
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
function layoutSaving() {
  return busy.has(`${room()?.base}:layout`);
}
async function saveLayout(ticket = session.capture(), confirmed = data.layout,
    owner = layoutLoadState, draftValue = JSON.stringify(layoutDraft)) {
  if (!layoutViewCurrent(ticket, confirmed, owner) || layoutSaveReadBlocked() || data.layoutError ||
      layoutSaving() || !layoutDraft || JSON.stringify(layoutDraft) !== draftValue) return;
  const candidate = copy(layoutDraft),
    revision = layoutRevision,
    target = room();
  const pending = mutate(
    'layout',
    '/remote/layout',
    { layout: candidate, expectedRevision: revision },
    { ticket, target, success: '' },
  );
  render();
  const response = await pending;
  if (response?.layout && session.current(ticket)) {
    retireRead('layout');
    delete data.layoutError;
    const newerEdits = JSON.stringify(layoutDraft) !== JSON.stringify(candidate);
    data.layout = response;
    if (!newerEdits) layoutDraft = copy(response.layout);
    layoutRevision = response.revision;
    toast(newerEdits
      ? 'Submitted layout saved. Your newer edits are still unsaved.'
      : 'Layout saved and confirmed.');
  }
  if (room()?.base === target?.base && tab === 'home' && detail === 'layout') render();
}
function reloadLayout(ticket = session.capture(), confirmed = data.layout,
    owner = layoutLoadState, draftValue = JSON.stringify(layoutDraft)) {
  const revision = layoutRevision;
  const current = () => layoutViewCurrent(ticket, confirmed, owner) && !layoutSaving() &&
    !layoutPending() && !!layoutDraft && JSON.stringify(layoutDraft) === draftValue &&
    layoutRevision === revision;
  if (!current()) return;
  return confirmAction(
    'Reload layout?',
    'Replace unsaved edits after the current Portal layout loads successfully.',
    () => {
      if (!current()) return;
      loadLayout({ replaceDraft: { value: draftValue, revision } });
    },
    'Reload',
    ticket,
  );
}
function layoutPage() {
  const ticket = session.capture(), confirmed = data.layout, owner = layoutLoadState,
    draftValue = JSON.stringify(layoutDraft), pending = layoutPending();
  const retry = () => {
    if (layoutViewCurrent(ticket, confirmed, owner) && !layoutPending() && !layoutSaving()) loadLayout();
  };
  const readState = pending
    ? el('p', { role: 'status', 'aria-live': 'polite', text: confirmed ? 'Refreshing layout…' : 'Loading current layout…' })
    : data.layoutError ? group(text('Could not load the current layout'), text(message(data.layoutError)),
      button('Retry layout', retry, { kind: 'outline' })) : null;
  if (!data.layout || !layoutDraft)
    return sections(heading('Home layout', 'Arrange apps and widgets on your Portal.'), readState);
  // Keep a confirmed layout and local edits usable even when the replacement read fails.
  const changed = JSON.stringify(layoutDraft) !== JSON.stringify(data.layout.layout);
  const controls = group(
    el('h3', { text: 'Arrange your home' }),
    text(
      'Move rows with the arrow buttons. Widget sizes use Portal grid cells. Existing bound Android widgets retain their identity.',
    ),
    text(layoutSaving() ? 'Saving submitted layout…' : changed ? 'Unsaved changes' : 'Layout from Portal', 'draft-label'),
    layoutSaving() ? text('You can keep editing. Newer changes will need another Save.', 'muted') : null,
    actions(
      button('Save layout', () => saveLayout(ticket, confirmed, owner, draftValue),
        { kind: 'primary', disabled: !changed || layoutSaving() || layoutSaveReadBlocked() || !!data.layoutError }),
      button('Preview on Portal', () => dispatchKey('home'), { kind: 'outline' }),
      button('Reload layout', () => reloadLayout(ticket, confirmed, owner, draftValue),
        { kind: 'outline', disabled: layoutSaving() || pending }),
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
    readState,
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
function operationsRefreshing() {
  const owner = data.operationsLoad;
  return !!owner?.showProgress && session.current(owner.ticket);
}
function loadOperations({ showProgress = true, ...options } = {}) {
  if (!room() || document.hidden || connectionState === 'expired' || !supported('operations')) return;
  const previous = data.operationsLoad, pending = pageReads.get('operations');
  if (previous?.showProgress && session.current(previous.ticket) &&
      pending && !pending.signal.aborted) return previous.promise;
  const owner = { ticket: session.capture(), showProgress, promise: null };
  data.operationsLoad = owner;
  if (showProgress) {
    delete data.operationsError;
    renderOperations();
  }
  owner.promise = read('operations', '/remote/operations', acceptOperations, { renderAfter: false })
    .finally(() => {
      if (data.operationsLoad !== owner) return;
      data.operationsLoad = null;
      if (!session.current(owner.ticket) || document.hidden || options.renderAfter === false) return;
      if (showProgress || !options.renderWhen || options.renderWhen())
        (options.renderWith || renderOperations)();
    });
  return owner.promise;
}
function operationActionAvailable(id, kind, ticket) {
  if (!['retry', 'cancel'].includes(kind) || !session.current(ticket) || !supported('operations') ||
      data.operationsError || operationsRefreshing() || !Array.isArray(data.operations)) return false;
  return data.operations.find(operation => operation.id === id)?.[`${kind}Supported`] === true;
}
function operationAction(id, kind, ticket) {
  if (!operationActionAvailable(id, kind, ticket)) return;
  return beginOperation({ type: kind, id }, ticket);
}
function acceptOperations(response) {
  if (!Array.isArray(response.operations)) throw new ApiError('invalid_response');
  const ids = new Set();
  for (const operation of response.operations) {
    if (!operation || typeof operation.id !== 'string' || !operation.id.trim() || ids.has(operation.id) ||
        (operation.results !== undefined && (!Array.isArray(operation.results) ||
          operation.results.some(result => !result || typeof result !== 'object' || Array.isArray(result)))))
      throw new ApiError('invalid_response');
    ids.add(operation.id);
  }
  const previous = new Map((data.operations || []).map((operation) => [operation.id, operation]));
  data.operations = response.operations;
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
    list: el('div', { class: 'history-list' }), notice: el('div'), records: new Map(), state: null, noticeKey: null,
  };
  updateActivity(view);
  operationsView = view;
  return sections(heading('Activity', 'Progress and results retained on the Portal.'), view.root);
}
function updateActivity(view) {
  const operations = data.operations, confirmed = Array.isArray(operations);
  const state = confirmed ? !operations.length ? 'empty' : 'list'
    : data.operationsError ? `error:${JSON.stringify(data.operationsError)}` : 'loading';
  if (state !== 'list') {
    const uncertainEmpty = state === 'empty' && (data.operationsError || operationsRefreshing());
    const key = `${state}:${!!uncertainEmpty}`;
    if (key !== view.state)
      view.root.replaceChildren(
        !confirmed && data.operationsError
          ? failure('Activity unavailable', data.operationsError, () => loadOperations())
          : !confirmed ? empty('Activity', 'Loading operations…')
          : uncertainEmpty ? empty('Last confirmed history was empty', 'Refresh to check for newer operations.')
          : empty('No recent operations', 'Catalog installs, scenes and presets will appear here.'),
      );
    if (state === 'empty') {
      view.records.clear();
      view.list.replaceChildren();
    }
    view.state = key;
  } else {
    const ids = new Set(), nodes = [];
    for (const operation of operations) {
      ids.add(operation.id);
      const key = JSON.stringify(operation);
      const actionsKey = JSON.stringify([
        operation.retrySupported === true, operation.cancelSupported === true,
        operationActionAvailable(operation.id, 'retry', view.ticket),
        operationActionAvailable(operation.id, 'cancel', view.ticket),
      ]);
      let record = view.records.get(operation.id);
      if (!record || record.key !== key || record.actionsKey !== actionsKey) {
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
    if (view.root.children[0] !== view.notice || view.root.children[1] !== view.list)
      view.root.replaceChildren(view.notice, view.list);
    view.state = state;
  }
  if (confirmed) {
    const noticeKey = data.operationsError ? `error:${JSON.stringify(data.operationsError)}`
      : operationsRefreshing() ? 'refreshing' : 'none';
    if (noticeKey !== view.noticeKey) {
      view.notice.replaceChildren(...(data.operationsError
        ? [failure('Activity unavailable', data.operationsError, () => loadOperations()),
          text('Last confirmed results are shown. Refresh before retrying or cancelling an operation.')]
        : operationsRefreshing() ? [el('p', { role: 'status', 'aria-live': 'polite', text: 'Refreshing activity… Last confirmed results are kept below.' })] : []));
      view.noticeKey = noticeKey;
    }
    if (state === 'empty' && noticeKey !== 'none' && view.root.firstChild !== view.notice)
      view.root.replaceChildren(view.notice, ...view.root.children);
  }
}
function operationCard(op, retainedActions = null) {
  const ticket = session.capture();
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
        ? button('Retry', () => operationAction(op.id, 'retry', ticket),
          { kind: 'outline', disabled: !operationActionAvailable(op.id, 'retry', ticket) })
        : null,
      op.cancelSupported === true
        ? button(
            'Cancel operation',
            () => {
              if (!operationActionAvailable(op.id, 'cancel', ticket)) return;
              return confirmAction(
                'Cancel this operation?',
                op.type === 'preset'
                  ? 'Stop the remaining steps. Earlier actions will not be undone.'
                  : 'Ask the Portal to cancel this specific operation.',
                () => operationAction(op.id, 'cancel', ticket),
                'Cancel operation', ticket,
              );
            },
            { kind: 'danger', disabled: !operationActionAvailable(op.id, 'cancel', ticket) },
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
  try { anchor.click(); }
  finally {
    anchor.remove();
    setTimeout(() => URL.revokeObjectURL(url), 1000);
  }
}
function cancelSnapshotRestoreReview(clearSelection = false) {
  const confirming = snapshotRestoreDraft?.review?.confirming;
  snapshotRestoreDraft?.review?.controller.abort();
  if (clearSelection) snapshotRestoreDraft = null;
  else if (snapshotRestoreDraft) snapshotRestoreDraft.review = null;
  if (confirming) closeSheet();
}
function chooseSnapshotRestoreFile(file) {
  const confirming = snapshotRestoreDraft?.review?.confirming;
  cancelSnapshotRestoreReview(true);
  if (file) snapshotRestoreDraft = { file, review: null, error: '' };
  if (confirming) closeSheet();
  render();
}
function snapshotReviewCurrent(owner, review) {
  return snapshotRestoreDraft === owner && owner.review === review &&
    session.current(review.ticket) && tab === 'home' && detail === 'snapshots' &&
    !document.hidden && connectionState !== 'expired';
}
async function reviewSnapshotRestore() {
  if (snapshotExport?.pending) return;
  const owner = snapshotRestoreDraft;
  if (!owner?.file) { toast('Choose a snapshot JSON file.', true); return; }
  if (owner.review || busy.has(`${room()?.base}:restore`)) return;
  const ticket = session.capture(), file = owner.file;
  const review = { ticket, controller: new AbortController(), phase: 'Reading snapshot file…' };
  owner.review = review;
  owner.error = '';
  render();
  try {
    if (file.size > 240 * 1024) throw new ApiError('body_too_large');
    const candidate = JSON.parse(await file.text());
    if (!snapshotReviewCurrent(owner, review)) return;
    const record = value => value && typeof value === 'object' && !Array.isArray(value);
    if (!record(candidate) || candidate.schemaVersion !== 1 || !record(candidate.layout) ||
        !record(candidate.domains) || typeof candidate.revision !== 'string')
      throw new ApiError('unsupported_schema');
    if (candidate.deviceId !== data.companion?.deviceId) throw new ApiError('wrong_device');
    review.phase = 'Reading the current device revision…';
    render();
    const fresh = await api(ticket.room, '/remote/snapshot', { signal: review.controller.signal });
    if (!snapshotReviewCurrent(owner, review)) return;
    const revision = fresh.snapshot?.revision;
    if (typeof revision !== 'string' || !revision) throw new ApiError('invalid_response');
    review.phase = 'Review ready. Confirm or cancel before restoring.';
    review.confirming = true;
    render();
    const domainCount = Object.keys(candidate.domains).length;
    await confirmAction(
      `Restore to ${ticket.room.name}?`,
      `Restore ${domainCount} settings categories and the layout from ${file.name}. This snapshot belongs to this Portal. Any further Portal changes will stop this restore. Credentials, photos and private app data stay on the Portal.`,
      async () => {
        if (!snapshotReviewCurrent(owner, review)) return;
        review.phase = 'Restoring snapshot…';
        const pending = mutate('restore', '/remote/snapshot',
          { snapshot: candidate, expectedRevision: revision }, { ticket, success: '' });
        render();
        const response = await pending;
        if (response?.snapshot && session.current(ticket)) {
          data.snapshot = response;
          data.layout = null;
          layoutDraft = null;
          toast('Snapshot settings and layout restored and confirmed.');
          render();
        } else if (scopeResults.length) render();
      }, 'Restore snapshot', ticket,
    );
  } catch (error) {
    if (snapshotReviewCurrent(owner, review) && error.code !== 'aborted')
      owner.error = error instanceof SyntaxError ? 'This file is not valid snapshot JSON.'
        : error.code === 'unsupported_schema' ? 'This file is not a supported Immortal snapshot. Choose a snapshot exported by this Portal.'
        : error.code === 'body_too_large' ? 'Snapshot JSON must be 240 KiB or smaller.' : message(error);
  } finally {
    if (owner.review === review) owner.review = null;
    if (snapshotRestoreDraft === owner && session.current(ticket) && tab === 'home' && detail === 'snapshots') render();
  }
}
function cancelSnapshotExport() {
  snapshotExport?.controller.abort();
  snapshotExport = null;
}
function canExportSnapshot(ticket, owner) {
  return session.current(ticket) && tab === 'home' && detail === 'snapshots' &&
    !document.hidden && connectionState !== 'expired' && snapshotExport === owner &&
    !owner?.pending && !busy.has(`${room()?.base}:restore`) && !snapshotRestoreDraft?.review &&
    typeof data.companion?.deviceId === 'string' && !!data.companion.deviceId;
}
function snapshotExportCurrent(owner) {
  return snapshotExport === owner && session.current(owner.ticket) &&
    tab === 'home' && detail === 'snapshots' && !document.hidden &&
    connectionState !== 'expired' && data.companion?.deviceId === owner.deviceId &&
    !busy.has(`${room()?.base}:restore`) && !snapshotRestoreDraft?.review;
}
function validateSnapshotExport(response, deviceId) {
  const record = value => value && typeof value === 'object' && !Array.isArray(value);
  const snapshot = response?.snapshot;
  if (!record(snapshot) || snapshot.schemaVersion !== 1 ||
      typeof snapshot.deviceId !== 'string' || !snapshot.deviceId ||
      typeof snapshot.revision !== 'string' || !snapshot.revision.trim() ||
      !record(snapshot.domains) || !record(snapshot.layout)) throw new ApiError('invalid_response');
  if (snapshot.deviceId !== deviceId) throw new ApiError('wrong_device');
}
async function exportSnapshot(ticket, previousOwner) {
  if (!canExportSnapshot(ticket, previousOwner)) return;
  const owner = { ticket, deviceId: data.companion.deviceId,
    controller: new AbortController(), pending: true, error: '' };
  snapshotExport = owner;
  render();
  try {
    const response = await api(ticket.room, '/remote/snapshot',
      { signal: owner.controller.signal, timeout: 12000 });
    if (!snapshotExportCurrent(owner)) return;
    validateSnapshotExport(response, owner.deviceId);
    try { downloadJson('immortal-snapshot.json', response.snapshot); }
    catch { throw new ApiError('download_failed'); }
    if (!snapshotExportCurrent(owner)) return;
    retireRead('snapshot');
    data.snapshot = response;
    delete data.snapshotError;
    toast('Snapshot exported.');
  } catch (error) {
    if (snapshotExportCurrent(owner) && error.code !== 'aborted') owner.error = message(error);
  } finally {
    if (snapshotExport === owner) {
      owner.pending = false;
      if (session.current(ticket) && tab === 'home' && detail === 'snapshots' && !document.hidden) render();
    }
  }
}
function snapshotsPage() {
  const current = data.snapshot?.snapshot, selected = snapshotRestoreDraft,
    exportOwner = snapshotExport, exportTicket = session.capture();
  const restoring = busy.has(`${room()?.base}:restore`),
    exporting = !!exportOwner?.pending && session.current(exportOwner.ticket),
    restoreBlocked = restoring || exporting;
  const importInput = el('input', {
    type: 'file', hidden: true, accept: '.json,application/json',
    'aria-label': 'Choose snapshot JSON', disabled: restoreBlocked,
    onChange: event => chooseSnapshotRestoreFile(event.target.files?.[0] || null),
  });
  const status = el('p', { role: 'status', 'aria-live': 'polite',
    class: selected?.error ? 'error' : 'detail',
    text: selected?.error || selected?.review?.phase ||
      (selected?.file ? 'Selected file is kept in this page until you leave.' : 'Choose a snapshot JSON file to review.'),
  });
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
        exporting || exportOwner?.error ? el('p', { role: 'status', 'aria-live': 'polite',
          class: exportOwner.error ? 'error' : 'detail',
          text: exporting ? 'Preparing snapshot…' : exportOwner.error }) : null,
        actions(
          button(exportOwner?.error ? 'Retry export' : 'Export snapshot',
            () => exportSnapshot(exportTicket, exportOwner),
            { kind: 'primary', icon: 'backup', disabled: !canExportSnapshot(exportTicket, exportOwner) }),
          exporting ? button('Cancel export', () => {
            if (snapshotExport !== exportOwner || !session.current(exportTicket)) return;
            cancelSnapshotExport();
            render();
          }, { kind: 'outline' }) : null,
        ),
      ),
      group(
        el('h3', { text: 'Restore' }),
        text(
          'Choose a snapshot exported by this Portal. Review the target before applying. Side-effect failures can leave a partial restore; the Portal returns the affected domains.',
        ),
        importInput,
        button('Choose snapshot JSON', () => importInput.click(), { kind: 'outline', disabled: restoreBlocked }),
        selected?.file ? text(`Selected: ${selected.file.name}`) : null,
        status,
        button('Review restore', reviewSnapshotRestore, { kind: 'outline',
          disabled: !selected?.file || !!selected.review || restoreBlocked }),
      ),
    ),
    resultPanel(),
  );
}
function jarvisReadCurrent(owner) {
  return !!owner && jarvisLoadState === owner && session.current(owner.ticket) &&
    tab === 'home' && detail === 'jarvis' && !document.hidden &&
    connectionState !== 'expired' && !owner.controller?.signal.aborted;
}
function jarvisPending() {
  return !!jarvisLoadState?.pending && jarvisReadCurrent(jarvisLoadState);
}
function jarvisReady() {
  return !!data.jarvis && !data.jarvisError && !jarvisPending();
}
function jarvisStatusCurrent(status, ticket, owner = jarvisLoadState) {
  return session.current(ticket) && tab === 'home' && detail === 'jarvis' &&
    !document.hidden && connectionState !== 'expired' && supported('jarvis') &&
    jarvisLoadState === owner && jarvisReady() && data.jarvis === status;
}
function validateJarvisStatus(status) {
  const bools = ['installed', 'supported', 'microphoneGranted', 'microphoneMuted',
    'wakeEnabled', 'wakeSupported', 'running', 'foreground', 'serviceRunning', 'wakeListening'];
  if (!status || typeof status !== 'object' || Array.isArray(status) ||
      typeof status.installed !== 'boolean' || typeof status.supported !== 'boolean' ||
      (status.supported && !status.installed) ||
      bools.some(key => status[key] != null && typeof status[key] !== 'boolean') ||
      ['status', 'model', 'reason'].some(key => status[key] != null && typeof status[key] !== 'string'))
    throw new ApiError('invalid_response');
  // Optional model lists are filtered by jarvisEditState; older providers may omit them.
}
function loadJarvis() {
  if (!room() || document.hidden || connectionState === 'expired' ||
      tab !== 'home' || detail !== 'jarvis' || !supported('jarvis')) return;
  if (jarvisPending()) return jarvisLoadState.promise;
  const owner = { ticket: session.capture(), pending: true };
  jarvisLoadState = owner;
  delete data.jarvisError;
  owner.promise = read('jarvis', '/remote/jarvis', (response) => {
    if (!jarvisReadCurrent(owner)) return;
    validateJarvisStatus(response.jarvis);
    data.jarvis = response.jarvis;
  }, { renderAfter: false }).finally(() => {
    if (jarvisReadCurrent(owner)) { owner.pending = false; render(); }
  });
  owner.controller = pageReads.get('jarvis');
  render();
  return owner.promise;
}
function jarvisSaving() {
  return busy.has(`${room()?.base}:jarvis`);
}
function jarvisEditState(status = data.jarvis) {
  const currentModel = typeof status?.model === 'string' ? status.model : '';
  const model = draft('jarvis:model', currentModel), wake = draft('jarvis:wake', status?.wakeEnabled);
  const reported = Array.isArray(status?.availableModels) ? status.availableModels
    : Array.isArray(status?.models) ? status.models : [];
  const models = [...new Set(reported.filter(value =>
    typeof value === 'string' && /^[A-Za-z0-9._-]{1,100}$/.test(value)))].slice(0, 32);
  const modelChanged = model !== currentModel;
  const wakeEditable = status?.wakeSupported === true && typeof status.wakeEnabled === 'boolean';
  const wakeChanged = wakeEditable && wake !== status.wakeEnabled;
  const invalid = (modelChanged && !models.includes(model)) || (wakeChanged && typeof wake !== 'boolean');
  const changes = {};
  if (modelChanged && models.includes(model)) changes.model = model;
  if (wakeChanged && typeof wake === 'boolean') changes.wakeEnabled = wake;
  return { model, wake, models, changes, invalid, changed: modelChanged || wakeChanged };
}
async function saveJarvisSettings(expectedStatus = data.jarvis, expectedTicket = session.capture(),
    expectedOwner = jarvisLoadState) {
  if (jarvisSaving() || !jarvisStatusCurrent(expectedStatus, expectedTicket, expectedOwner) ||
      expectedStatus?.supported !== true) return;
  const submitted = jarvisEditState(), target = room(), ticket = session.capture();
  if (submitted.invalid || !Object.keys(submitted.changes).length) return;
  delete data.jarvisSaveError;
  const pending = mutate('jarvis', '/remote/jarvis', submitted.changes, {
    target, ticket, success: '', onError: error => { data.jarvisSaveError = error; },
  });
  render();
  const response = await pending;
  if (response && session.current(ticket)) {
    const confirmed = response.jarvis;
    if (confirmed?.supported === true && Object.entries(submitted.changes)
        .every(([key, value]) => confirmed[key] === value)) {
      // Retire older status reads before accepting the provider's exact configuration readback.
      pageReads.get('jarvis')?.abort();
      session.read('jarvis');
      const current = jarvisEditState();
      for (const [field, value] of [['model', submitted.model], ['wake', submitted.wake]])
        if (current[field] === value) drafts.delete(draftKey(`jarvis:${field}`));
      data.jarvis = confirmed;
      delete data.jarvisError;
      toast(jarvisEditState().changed
        ? 'Submitted Jarvis settings confirmed. Your newer edits are still unsaved.'
        : 'Jarvis settings saved and confirmed.');
    } else {
      data.jarvisSaveError = new ApiError('readback_failed');
      toast(message(data.jarvisSaveError), true);
    }
  }
  if (room()?.base === target?.base && tab === 'home' && detail === 'jarvis') render();
}
function jarvisPage() {
  const status = data.jarvis, owner = jarvisLoadState, ticket = session.capture();
  const pending = jarvisPending();
  const retry = () => {
    if (session.current(ticket) && tab === 'home' && detail === 'jarvis' &&
        !document.hidden && connectionState !== 'expired' && supported('jarvis') &&
        jarvisLoadState === owner && data.jarvis === status) loadJarvis();
  };
  const loading = pending ? el('p', { role: 'status', 'aria-live': 'polite', class: 'detail',
    text: status ? 'Refreshing Jarvis status…' : 'Loading Jarvis status…' }) : null;
  if (!status)
    return data.jarvisError
      ? failure('Jarvis unavailable', data.jarvisError, retry)
      : empty('Jarvis', 'Loading Jarvis status…');
  const refreshNotice = !pending && data.jarvisError ? group(
    el('strong', { text: 'Jarvis status refresh failed' }), text(message(data.jarvisError)),
    text('Last reported status is shown. Your edits are kept. Retry to confirm readiness before saving or opening Jarvis.'),
    button('Retry Jarvis status', retry, { kind: 'outline' })) : null;
  const canUseStatus = () => jarvisStatusCurrent(status, ticket, owner);
  const edit = jarvisEditState(status), { model, wake, models } = edit;
  const modelOptions = models.map(value => ({ value, label: value }));
  if (!models.includes(model)) modelOptions.unshift({ value: model,
    label: model ? `${model} (${model === status.model ? 'Current' : 'Unavailable'})` : 'Model not reported' });
  const modelChoice = choice(model, modelOptions, value => {
    setDraft('jarvis:model', value); delete data.jarvisSaveError; render();
  }, 'Conversation model');
  modelChoice.disabled = !models.length;
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
    loading,
    refreshNotice,
    pending ? text('Last reported status is shown while refreshing. Your edits are kept.') : null,
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
              status.wakeSupported === true
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
          () => {
            if (!canUseStatus() || status.installed !== true) return;
            return mutate('jarvis-launch', '/remote/launch',
              { packageName: 'com.portal.assistant' },
              { ticket, target: ticket.room, success: 'Launch command dispatched. Check the Portal screen.' });
          },
          { kind: 'outline', disabled: !canUseStatus() || status.installed !== true },
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
              modelChoice,
            ),
            status.wakeSupported === true
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
                      delete data.jarvisSaveError; render();
                    },
                  }),
                )
              : null,
            text('Microphone permission and competing audio can pause the listener.'),
            el('p', { role: 'status', class: 'draft-label', text: jarvisSaving()
              ? 'Saving submitted Jarvis settings…'
              : edit.changed ? 'Unsaved changes' : 'Settings reported by Jarvis' }),
            jarvisSaving() ? text('You can keep editing. Newer changes will need another Save.') : null,
            edit.invalid ? text('The selected model is no longer available. Choose a reported model before saving.', 'error') : null,
            data.jarvisSaveError ? sections(text(message(data.jarvisSaveError), 'error'),
              text('Your edits are kept. Retry Save or refresh the reported status.')) : null,
            button('Save Jarvis settings', () => saveJarvisSettings(status, ticket, owner), {
              kind: 'primary', disabled: !canUseStatus() || jarvisSaving() || edit.invalid || !Object.keys(edit.changes).length,
            }),
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
function scenesPending() {
  return !!scenesLoadState?.pending && session.current(scenesLoadState.ticket);
}
function scenesReady() {
  return Array.isArray(data.scenes) && !data.scenesError && !scenesPending();
}
function sceneListCurrent(ticket, scenes, revision) {
  return session.current(ticket) && tab === 'home' && detail === 'scenes' &&
    !document.hidden && connectionState !== 'expired' && scenesReady() &&
    !scenesSaving() && data.scenes === scenes && data.scenesRevision === revision;
}
function validateSceneList(response) {
  const scenes = response?.scenes, ids = new Set();
  if (!Array.isArray(scenes)) throw new ApiError('invalid_response');
  for (const scene of scenes) {
    if (!scene || typeof scene !== 'object' || typeof scene.id !== 'string' ||
        !scene.id.trim() || ids.has(scene.id) || typeof scene.name !== 'string' ||
        !scene.name.trim() || !Array.isArray(scene.steps) || scene.steps.some(step =>
          !step || typeof step !== 'object' || typeof step.type !== 'string' || !step.type.trim()))
      throw new ApiError('invalid_response');
    ids.add(scene.id);
    const schedule = scene.schedule;
    if (schedule != null && (typeof schedule !== 'object' || typeof schedule.enabled !== 'boolean' ||
        !Number.isInteger(schedule.hour) || schedule.hour < 0 || schedule.hour > 23 ||
        !Number.isInteger(schedule.minute) || schedule.minute < 0 || schedule.minute > 59 ||
        !Array.isArray(schedule.days) || schedule.days.length < 1 || schedule.days.length > 7 ||
        new Set(schedule.days).size !== schedule.days.length ||
        schedule.days.some(day => !Number.isInteger(day) || day < 1 || day > 7)))
      throw new ApiError('invalid_response');
  }
}
function loadScenes() {
  if (!room() || document.hidden || connectionState === 'expired') return;
  if (scenesPending() && pageReads.get('scenes') && !pageReads.get('scenes').signal.aborted)
    return scenesLoadState.promise;
  const owner = { ticket: session.capture(), pending: true };
  scenesLoadState = owner;
  delete data.scenesError;
  render();
  owner.promise = read('scenes', '/remote/scenes', response => {
    validateSceneList(response);
    acceptScenes(response);
  }, { renderAfter: false }).finally(() => {
    if (scenesLoadState === owner && session.current(owner.ticket)) {
      owner.pending = false;
      render();
    }
  });
  return owner.promise;
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
  const scenes = data.scenes, revision = data.scenesRevision,
    ticket = session.capture(), ready = scenesReady(), pending = scenesPending();
  if (sceneDraft) return sceneEditor();
  const current = () => sceneListCurrent(ticket, scenes, revision) && !sceneDraft;
  return sections(
    heading('Scenes', 'Everyday routines and schedules.'),
    sceneProtectionPanel(),
    scenesSaving() ? text('Saving scenes…', 'draft-label') : null,
    pending ? el('p', { role: 'status', 'aria-live': 'polite', class: 'detail',
      text: scenes ? 'Refreshing routines…' : 'Loading your routines…' }) : null,
    data.scenesError ? failure('Scenes unavailable', data.scenesError, loadScenes) : null,
    scenes && (pending || data.scenesError)
      ? text('Last confirmed routines are shown. Refresh successfully before using these actions.', 'detail') : null,
    group(
      text('Scenes run ordered actions on this Portal. Schedules use its local clock and are inexact on Android.'),
      button('New scene', () => { if (current()) openScene(null); },
        { kind: 'primary', icon: 'scenes', disabled: !ready || scenesSaving() }),
    ),
    !scenes
      ? !pending && !data.scenesError ? empty('Scenes', 'Loading your routines…') : null
      : !scenes.length
        ? empty('No scenes yet', 'Create a routine with navigation, app launch, media, settings, sources and waits.')
        : sections(...scenes.map(scene => group(
            el('h3', { text: scene.name }),
            text(`${scene.steps?.length || 0} steps${scene.schedule?.enabled ? ` · ${String(scene.schedule.hour).padStart(2, '0')}:${String(scene.schedule.minute).padStart(2, '0')} local · ${scene.schedule.days.length} days` : ''}`),
            actions(
              button('Run', () => { if (current()) runScene(scene.id, ticket); },
                { kind: 'primary', disabled: !ready || scenesSaving() }),
              button('Edit', () => { if (current()) openScene(scene); },
                { kind: 'outline', disabled: !ready || scenesSaving() }),
              button('Delete', () => {
                if (!current() || !validSceneRevision(revision)) return;
                const candidate = copy(scenes.filter(s => s.id !== scene.id));
                confirmAction(`Delete “${scene.name}”?`,
                  'Remove the scene and its local schedule from this Portal.',
                  () => {
                    if (session.current(ticket) && scenesReady() && !scenesSaving() && !sceneDraft &&
                        tab === 'home' && detail === 'scenes' && !document.hidden && connectionState !== 'expired')
                      return saveScenes(candidate, ticket, revision);
                  },
                  'Delete', ticket);
              }, { kind: 'danger', disabled: !ready || scenesSaving() || !validSceneRevision(revision) }),
            ),
          ))),
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
const keyboardActions = {
  set: { label: 'Replace text', pickerLabel: 'Replace', help: 'Replaces all text in the focused field on the Portal. Leave the text empty to clear it.' },
  append: { label: 'Append text', pickerLabel: 'Append', help: 'Adds text to the end of the focused field on the Portal.' },
  backspace: { label: 'Backspace', help: 'Deletes from the end of the focused field on the Portal. No text value is needed.' },
  clear: { label: 'Clear field', help: 'Clears all text in the focused field on the Portal. No text value is needed.' },
};
function keyboardAction(mode) {
  // Match Kotlin Char.isWhitespace: browser trim also removes BOM, which the Portal does not.
  const value = String(mode || 'set').toLowerCase().replace(
    /^[\t-\r\u001c-\u0020\u00a0\u1680\u2000-\u200a\u2028\u2029\u202f\u205f\u3000]+|[\t-\r\u001c-\u0020\u00a0\u1680\u2000-\u200a\u2028\u2029\u202f\u205f\u3000]+$/g, '',
  );
  return Object.hasOwn(keyboardActions, value) ? value : 'set';
}
function stepSummary(step) {
  if (step.type === 'launch') return `Open ${step.packageName}`;
  if (step.type === 'key' || step.type === 'media') return `${step.type}: ${step.action}`;
  if (step.type === 'wait') return `Wait ${step.ms} ms`;
  if (step.type === 'settings')
    return `Settings · ${step.domain} · ${Object.keys(step.values || {}).join(', ')}`;
  if (step.type === 'source')
    return `Photo source · ${step.values?.folderPath || step.values?.source}`;
  if (step.type === 'text') return `Keyboard · ${keyboardActions[keyboardAction(step.mode)].label}`;
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
    scenesPending() ? text('Refreshing routines… Your draft is kept.', 'detail') : null,
    data.scenesError ? failure('Scenes unavailable. Your draft is kept.', data.scenesError, loadScenes) : null,
    scenesSaving() ? text('Saving scene… You can keep editing; newer changes will need another Save.', 'draft-label') : null,
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
                button('↑', () => moveDraftStep(entry.id, -1, 'scene'), {
                  kind: 'outline',
                  disabled: !index,
                }),
                button('↓', () => moveDraftStep(entry.id, 1, 'scene'), {
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
              disabled: scenesSaving() || !validSceneRevision(d.expectedRevision) || d.conflict,
            }),
            button('Discard changes', discardSceneDraft, { kind: 'outline', disabled: scenesSaving() }),
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
function moveDraftStep(id, delta, mode) {
  // Both editors retain drafts across navigation; only the active owner may reorder.
  const draft = mode === 'scene' && tab === 'home' && detail === 'scenes' ? sceneDraft
    : mode === 'preset' && (tab === 'home' || tab === 'remote') && detail === 'presets' ? presetDraft
    : null;
  if (!draft || !Number.isInteger(delta) || Math.abs(delta) !== 1) return;
  const index = draft.steps.findIndex((s) => s.id === id),
    to = index + delta;
  if (index < 0 || to < 0 || to >= draft.steps.length) return;
  draft.steps.splice(to, 0, draft.steps.splice(index, 1)[0]);
  render();
}
function scenesSaving(target = room()) {
  return busy.has(`${target?.base}:scenes`);
}
function discardSceneDraft() {
  if (scenesSaving() || !sceneDraft) return;
  const owner = sceneDraft;
  return confirmAction('Discard scene changes?', 'Discard your unsaved edits to this scene.', () => {
    if (scenesSaving() || sceneDraft !== owner) return;
    sceneDraft = null;
    data.sceneConflict = false;
    render();
  }, 'Discard');
}
async function saveSceneDraft() {
  if (scenesSaving() || !sceneDraft) return;
  const owner = sceneDraft, submitted = copy(owner), target = room(), ticket = session.capture();
  if (!submitted.name.trim() || !submitted.steps.length) {
    toast('Enter a scene name and add at least one step.', true);
    return;
  }
  if (submitted.steps.reduce((sum, s) => sum + (s.step.type === 'wait' ? s.step.ms : 0), 0) > 5000) {
    toast('Total scene wait time must be 5 seconds or less.', true);
    return;
  }
  const scene = {
    id: submitted.id,
    name: submitted.name.trim(),
    steps: submitted.steps.map((s) => copy(s.step)),
    schedule: submitted.schedule ? copy(submitted.schedule) : null,
  };
  if (scene.schedule?.enabled && !scene.schedule.days.length) {
    toast('Choose at least one weekday.', true);
    return;
  }
  if (!validSceneRevision(submitted.expectedRevision) || submitted.conflict) {
    toast(
      submitted.conflict
        ? 'Scene conflict: refresh to review before discarding and reopening your draft.'
        : 'Conflict protection is unavailable. Update Immortal before saving scenes.',
      true,
    );
    render();
    return;
  }
  const candidate = [...submitted.baseScenes.filter((s) => s.id !== submitted.id), scene];
  const response = await saveScenes(candidate, ticket, submitted.expectedRevision, {
    target, success: '', renderAfter: false,
  });
  if (response && session.current(ticket) && sceneDraft === owner) {
    const newerEdits = JSON.stringify(owner) !== JSON.stringify(submitted) ||
      (sheet.open && sheet.dataset.sceneEditor === owner.id);
    if (newerEdits) {
      owner.baseScenes = copy(response.scenes);
      owner.expectedRevision = response.revision;
      owner.conflict = false;
    } else sceneDraft = null;
    toast(newerEdits
      ? 'Submitted scene saved. Your newer edits are still unsaved.'
      : 'Scene saved and confirmed.');
  }
  if (room()?.base === target?.base && tab === 'home' && detail === 'scenes') render();
}
async function saveScenes(
  scenes,
  ticket = session.capture(),
  expectedRevision = data.scenesRevision,
  { target = room(), success = 'Scenes saved and confirmed.', renderAfter = true } = {},
) {
  if (!session.current(ticket) || scenesSaving(target)) return null;
  if (!validSceneRevision(expectedRevision)) {
    toast(
      'Conflict protection is unavailable. Update Immortal before saving or deleting scenes.',
      true,
    );
    render();
    return null;
  }
  const editor = sceneDraft;
  const pending = mutate(
    'scenes',
    '/remote/scenes',
    { scenes, expectedRevision },
    {
      target,
      ticket,
      success: '',
      onError: (error) => {
        if (error.status === 409 && error.code === 'stale_revision') {
          data.sceneConflict = true;
          if (editor && sceneDraft === editor) editor.conflict = true;
          render();
        } else if (error.code === 'companion_storage_unavailable') {
          data.scenesError = error;
          render();
        }
      },
    },
  );
  render();
  const response = await pending;
  const confirmed = Array.isArray(response?.scenes) && validSceneRevision(response.revision) && session.current(ticket);
  if (confirmed) {
    retireRead('scenes');
    scenesLoadState = null;
    delete data.scenesError;
    acceptScenes(response);
    data.sceneConflict = false;
    if (success) toast(success);
  } else if (response && session.current(ticket)) {
    toast('The Portal did not confirm saved scenes. Your draft is kept.', true);
  }
  if (renderAfter && room()?.base === target?.base && tab === 'home' && detail === 'scenes') render();
  return confirmed ? response : null;
}
async function runScene(id, ticket = session.capture()) {
  if (!sceneListCurrent(ticket, data.scenes, data.scenesRevision) || !data.scenes.some(scene => scene.id === id)) return;
  const response = await mutate(`scene:${id}`, '/remote/scene', { id }, { ticket, success: '' });
  if (response?.operation && session.current(ticket)) {
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
  if (mode === 'preset' && presetDraft) sheet.dataset.presetEditor = presetDraft.id;
  if (mode === 'scene' && sceneDraft) sheet.dataset.sceneEditor = sceneDraft.id;
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
      const action = keyboardAction(state.mode);
      nodes.push(
        row(
          'Action',
          '',
          choice(
            action,
            Object.entries(keyboardActions).map(([value, item]) => ({ value, label: item.pickerLabel || item.label })),
            (value) => { state.mode = value; paint(); },
            'Keyboard action',
          ),
        ),
        text(keyboardActions[action].help),
      );
      if (action === 'set' || action === 'append')
        nodes.push(field('Text', state.text, (value) => (state.text = value)));
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
function presetsPending() {
  return !!presetsLoadState?.pending && session.current(presetsLoadState.ticket);
}
function presetsReady() {
  return presetListReady() && !presetsPending();
}
function presetListCurrent(ticket, presets) {
  return session.current(ticket) && (tab === 'home' || tab === 'remote') &&
    detail === 'presets' && !document.hidden && connectionState !== 'expired' &&
    presetsReady() && !presetsSaving() && !presetDraft && data.presets === presets;
}
async function runPreset(id, ticket = session.capture(), presets = data.presets) {
  if (!presetListCurrent(ticket, presets) || !presets.some(preset => preset.id === id)) return;
  const response = await mutate(`preset:${id}`, '/remote/preset', { id },
    { target: ticket.room, ticket, success: '' });
  if (!response || !presetListCurrent(ticket, presets)) return;
  const op = response.operation;
  if (response.ok === true && op && typeof op === 'object' && !Array.isArray(op) &&
      typeof op.id === 'string' && op.id.trim() && op.type === 'preset' &&
      op.target && typeof op.target === 'object' && !Array.isArray(op.target) &&
      op.target.id === id && typeof op.target.name === 'string' &&
      ['running', 'succeeded', 'failed', 'interrupted', 'cancelled'].includes(op.state) &&
      (op.results === undefined || (Array.isArray(op.results) && op.results.every(result =>
        result && typeof result === 'object' && !Array.isArray(result))))) {
    data.operations = [op, ...(data.operations || []).filter(operation => operation.id !== op.id)];
    go('home', 'activity');
    toast('Preset accepted. Activity reports each step’s result.');
  } else if (op === undefined && response.ok === true && response.running === id &&
      Number.isInteger(response.steps) && response.steps >= 0) {
    toast('Preset started. This Portal does not report completion; check the Portal to confirm each action.');
  } else {
    toast('The Portal did not confirm preset acceptance. Check the Portal before running it again.', true);
  }
}
function validatePresetList(response) {
  const presets = response?.presets, ids = new Set();
  if (!Array.isArray(presets)) throw new ApiError('invalid_response');
  for (const preset of presets) {
    if (!preset || typeof preset !== 'object' || Array.isArray(preset) ||
        typeof preset.id !== 'string' || !preset.id.trim() || ids.has(preset.id) ||
        typeof preset.name !== 'string' ||
        (preset.steps != null && (!Array.isArray(preset.steps) || preset.steps.some(step =>
          !step || typeof step !== 'object' || Array.isArray(step) ||
          (step.type != null && typeof step.type !== 'string')))))
      throw new ApiError('invalid_response');
    ids.add(preset.id);
  }
}
function loadPresets() {
  if (!room() || document.hidden || connectionState === 'expired') return;
  if (presetsPending() && pageReads.get('presets') && !pageReads.get('presets').signal.aborted)
    return presetsLoadState.promise;
  const owner = { ticket: session.capture(), pending: true };
  presetsLoadState = owner;
  delete data.presetsError;
  render();
  owner.promise = read('presets', '/remote/presets', response => {
    validatePresetList(response);
    data.presets = response.presets;
  }, { renderAfter: false }).finally(() => {
    if (presetsLoadState === owner && session.current(owner.ticket)) {
      owner.pending = false;
      render();
    }
  });
  return owner.promise;
}
function presetsSaving(target = room()) {
  return busy.has(`${target?.base}:presets`);
}
function presetListReady() {
  return Array.isArray(data.presets) && !data.presetsError;
}
async function savePresetDraft() {
  if (presetsSaving() || !presetDraft) return;
  if (!presetListReady()) {
    toast('Load saved presets before saving. Your draft is kept.', true);
    return;
  }
  const owner = presetDraft;
  if (!owner.name.trim() || !owner.steps.length) {
    toast('Enter a name and add a step.', true);
    return;
  }
  const submitted = copy(owner), target = room(), ticket = session.capture();
  const candidate = [
    ...data.presets.filter((p) => p.id !== submitted.id),
    { id: submitted.id, name: submitted.name.trim(), steps: submitted.steps.map((s) => copy(s.step)) },
  ];
  const response = await savePresets(candidate, { target, ticket, success: '', renderAfter: false });
  if (response && session.current(ticket)) {
    const newerEdits = presetDraft === owner && (
      JSON.stringify(presetDraft) !== JSON.stringify(submitted) ||
      (sheet.open && sheet.dataset.presetEditor === owner.id)
    );
    if (presetDraft === owner && !newerEdits) presetDraft = null;
    toast(newerEdits
      ? 'Submitted preset saved. Your newer edits are still unsaved.'
      : 'Preset saved and confirmed.');
  }
  if (room()?.base === target?.base && detail === 'presets') render();
}
function discardPresetDraft() {
  if (presetsSaving() || !presetDraft) return;
  const owner = presetDraft;
  return confirmAction('Discard preset changes?', 'Discard your unsaved edits to this preset.', () => {
    if (presetsSaving() || presetDraft !== owner) return;
    presetDraft = null;
    render();
  }, 'Discard');
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
        presetsPending() ? text('Refreshing saved presets… Your draft is kept.', 'detail') : null,
        presetsSaving() ? text('Saving preset… You can keep editing; newer changes will need another Save.', 'draft-label') : null,
        !presetListReady() ? data.presetsError
          ? failure('Saved presets unavailable. Your draft is kept.', data.presetsError, () => loadVisible())
          : text('Loading saved presets before saving…') : null,
        field('Preset name', d.name, (value) => (d.name = value), { key: 'preset-name' }),
        ...d.steps.map((entry, index) =>
          el(
            'div',
            { class: 'reorder-row' },
            el('span', { class: 'label', text: `${index + 1}. ${stepSummary(entry.step)}` }),
            actions(
              button('↑', () => moveDraftStep(entry.id, -1, 'preset'), { kind: 'outline', disabled: !index }),
              button('↓', () => moveDraftStep(entry.id, 1, 'preset'), {
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
          button('Save preset', savePresetDraft, { kind: 'primary', disabled: presetsSaving() || !presetListReady() }),
          button('Discard changes', discardPresetDraft, { kind: 'outline', disabled: presetsSaving() }),
        ),
      ),
    );
  }
  const presets = data.presets, ticket = session.capture(), owner = presetsLoadState,
    ready = presetsReady(), pending = presetsPending();
  const current = () => presetListCurrent(ticket, presets);
  const newCurrent = () => session.current(ticket) && (tab === 'home' || tab === 'remote') &&
    detail === 'presets' && !document.hidden && connectionState !== 'expired' &&
    !presetsSaving() && !presetDraft && data.presets === presets && presetsLoadState === owner;
  return sections(
    heading('Saved presets', 'The macros from your previous remote, with a complete editor.'),
    presetsSaving() ? text('Saving presets…', 'draft-label') : null,
    pending ? el('p', { role: 'status', 'aria-live': 'polite', class: 'detail',
      text: presets ? 'Refreshing saved presets…' : 'Loading your saved presets…' }) : null,
    data.presetsError ? failure('Presets unavailable', data.presetsError, loadPresets) : null,
    presets && (pending || data.presetsError)
      ? text('Last confirmed presets are shown. Refresh successfully before using these actions.', 'detail') : null,
    group(button('New preset', () => {
      if (!newCurrent()) return;
      presetDraft = { id: uniqueId(), name: '', steps: [] };
      render();
    }, { kind: 'primary', disabled: presetsSaving() })),
    !presets
      ? !pending && !data.presetsError ? empty('Presets', 'Loading your saved presets…') : null
      : !presets.length
        ? empty('No presets yet', 'Create a sequence of app, keyboard and navigation actions.')
        : sections(...presets.map(preset => group(
            el('h3', { text: preset.name || 'Unnamed preset' }),
            text(`${preset.steps?.length || 0} steps · Updated Portals report each step in Activity; older Portals do not report completion`),
            actions(
              button('Run', () => {
                if (!current()) return;
                return runPreset(preset.id, ticket, presets);
              }, { kind: 'primary', disabled: !ready || presetsSaving() }),
              button('Edit', () => {
                if (!current()) return;
                presetDraft = { ...copy(preset),
                  steps: (preset.steps || []).map(step => ({ id: uniqueId(), step })) };
                render();
              }, { kind: 'outline', disabled: !ready || presetsSaving() }),
              button('Delete', () => {
                if (!current()) return;
                return confirmAction(`Delete “${preset.name || 'Unnamed preset'}”?`,
                  'Remove this macro from the selected Portal.', () => {
                    if (!current()) {
                      if (session.current(ticket) && presetsReady() && !presetsSaving() &&
                          !presetDraft && (tab === 'home' || tab === 'remote') && detail === 'presets')
                        toast('Presets changed. Review them before deleting.', true);
                      return;
                    }
                    return savePresets(presets.filter(p => p.id !== preset.id), { ticket });
                  }, 'Delete', ticket);
              }, { kind: 'danger', disabled: !ready || presetsSaving() }),
            ),
          ))),
  );
}
async function savePresets(presets, {
  target = room(), ticket = session.capture(), success = 'Presets saved.', renderAfter = true,
} = {}) {
  if (presetsSaving(target) || !session.current(ticket)) return null;
  if (!presetListReady()) {
    toast('Load saved presets before saving. Your draft is kept.', true);
    return null;
  }
  const pending = mutate('presets', '/remote/presets', { presets }, { target, ticket, success: '' });
  render();
  const response = await pending;
  const confirmed = Array.isArray(response?.presets) && session.current(ticket);
  if (confirmed) {
    retireRead('presets');
    presetsLoadState = null;
    data.presets = response.presets;
    data.presetsError = null;
    if (success) toast(success);
  } else if (response && session.current(ticket)) {
    toast('The Portal did not confirm saved presets. Your draft is kept.', true);
  }
  if (renderAfter && room()?.base === target?.base && detail === 'presets') render();
  return confirmed ? response : null;
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
    if (connectionState === 'offline' || !data.companion) status();
    startPolling();
    if (musicSheetCurrent(musicSheet)) {
      renderMusicSheet(musicSheet);
      readMusicSheet(musicSheet);
    }
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
