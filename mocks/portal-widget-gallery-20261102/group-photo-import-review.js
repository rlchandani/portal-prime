/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */
// Review-only simulation. No API client, storage, account, file-content or device access.
const $ = id => document.getElementById(id);
const rooms = [{ id: 'family', name: 'Family Area' }, { id: 'living', name: 'Living Area' }];
const examples = [
  { id: 'mountain', name: 'Mountain morning.jpg', size: 1_600_000 },
  { id: 'garden', name: 'Garden light.jpg', size: 2_400_000 },
  { id: 'weekend', name: 'Weekend.jpg', size: 950_000 },
];
let viewing = 'family', selected = new Set(['family']), files = [], operation = null;
let recipientsChosen = false;
let generation = 0, sequence = 0, currentScenario = 'normal', recovered = false;
let selectionPage = 0;
const resultPages = new Map();
const folders = new Map(rooms.map(room => [room.id, '/sdcard/Pictures/Immortal']));
const simulatedStoredNames = new Set();
const events = [];
const terminal = status => ['confirmed', 'failed', 'unconfirmed', 'cancelled'].includes(status);
const labels = {
  queued: 'Waiting', sending: 'Sending', confirmed: 'Stored and confirmed',
  failed: 'Could not import', cancelled: 'Not sent', unconfirmed: 'Confirmation missing',
};
const element = (tag, cls, text = '') => {
  const node = document.createElement(tag);
  if (cls) node.className = cls;
  node.textContent = text;
  return node;
};
const addEvent = (type, item, extra = {}) => events.push({ type, room: item?.room, file: item?.file.id, ...extra });
const running = () => operation?.items.some(item => !terminal(item.status)) === true;
const names = ids => ids.map(id => rooms.find(room => room.id === id).name).join(' and ');
const filesValid = () => files.length && files.every(file => file.size > 0 && file.size <= 20 * 1048576 &&
  file.name.length <= 128 && /^[^.][^/\\:\x00-\x1f]{0,127}\.(?:jpe?g|png|webp|gif)$/i.test(file.name));
// The review mirrors the shipped core.mjs photoFolder policy; production must reuse it.
function importFolder(value) {
  const path = value.replace(/^\/(?:storage\/emulated\/0|storage\/self\/primary|mnt\/sdcard)(?=\/|$)/, '/sdcard').replace(/\/+$/, '');
  if (!/^\/sdcard(?:\/|$)/.test(path) || path.length > 1024 ||
      path.split('/').slice(2).some(part => !part || part.startsWith('.') || /^Android$/i.test(part) || /[\\:\x00-\x1f]/.test(part))) return null;
  return path;
}

function renderView() {
  const room = rooms.find(room => room.id === viewing);
  $('view-name').textContent = $('library-room').textContent = room.name;
  $('view-options').replaceChildren(...rooms.map(room => {
    const node = element('button', 'view-option');
    node.type = 'button';
    node.append(element('strong', '', room.name), element('span', '', room.id === viewing ? '✓' : ''));
    node.setAttribute('aria-pressed', String(room.id === viewing));
    node.addEventListener('click', () => {
      viewing = room.id;
      $('view-dialog').close();
      renderView();
      if (operation) $('notice').textContent = `Viewing ${room.name}. This import still goes to ${names(operation.targets)}.`;
    });
    return node;
  }));
}
function renderSelection() {
  selectionPage = Math.min(selectionPage, Math.max(0, Math.ceil(files.length / 8) - 1));
  $('selection').replaceChildren(...files.slice(selectionPage * 8, selectionPage * 8 + 8).map(file => {
    const row = element('div', 'selected-file');
    row.append(element('span', '', `${file.name} · ${(file.size / 1048576).toFixed(1)} MiB`));
    const remove = element('button', '', '×');
    remove.type = 'button';
    remove.setAttribute('aria-label', `Remove ${file.name}`);
    remove.addEventListener('click', () => { files = files.filter(other => other.id !== file.id); renderSelection(); });
    row.append(remove);
    return row;
  }));
  appendPagination($('selection'), files.length, selectionPage, value => { selectionPage = value; renderSelection(); }, 'Selected photos');
  if (!files.length) $('selection').append(element('p', 'muted small', 'No photos selected.'));
  if (files.length && !filesValid()) $('form-error').textContent = 'Choose supported photos from 1 byte to 20 MiB, with valid filenames up to 128 characters.';
  else $('form-error').textContent = '';
  updateImportButton();
}
function updateImportButton() {
  $('start').textContent = `Import ${files.length || ''} ${files.length === 1 ? 'photo' : 'photos'} to ${selected.size} ${selected.size === 1 ? 'Portal' : 'Portals'}`;
  $('start').disabled = !filesValid() || selected.size === 0 || running();
  $('both').textContent = selected.size === rooms.length ? 'Clear selection' : 'Select both';
}
function renderDestinations() {
  $('destinations').replaceChildren(...rooms.map(room => {
    const label = element('label', 'destination-row');
    const copy = element('span', '');
    const path = folders.get(room.id);
    copy.append(element('strong', '', room.name), element('small', '', path.endsWith('/Immortal') ? 'Immortal photo library' : path.split('/').at(-1)));
    const input = document.createElement('input');
    input.type = 'checkbox'; input.checked = selected.has(room.id);
    input.setAttribute('aria-label', `Send photos to ${room.name}`);
    input.addEventListener('change', () => {
      recipientsChosen = true;
      if (input.checked) selected.add(room.id); else selected.delete(room.id);
      updateImportButton();
    });
    label.append(copy, input);
    return label;
  }));
  updateImportButton();
}
function openImport() {
  if (running()) return;
  if (operation?.items.some(item => item.status !== 'confirmed')) return;
  if (!recipientsChosen) selected = new Set([viewing]);
  files = [];
  selectionPage = 0;
  $('files').value = '';
  for (const room of rooms) $(`folder-${room.id}`).value = folders.get(room.id);
  $('form-error').textContent = '';
  renderSelection(); renderDestinations();
  $('import-dialog').showModal();
  $('import-dialog').querySelector('.dialog-content').scrollTop = 0;
  $('choose').focus({ preventScroll: true });
}
function renderBatch() {
  if (!operation) { $('batch').hidden = true; return; }
  $('batch').hidden = false;
  const active = running(), confirmed = operation.items.filter(item => item.status === 'confirmed').length;
  const unknown = operation.items.filter(item => item.status === 'unconfirmed').length;
  const failed = operation.items.filter(item => item.status === 'failed').length;
  $('batch-summary').textContent = `${confirmed} of ${operation.items.length} copies stored${unknown ? ` · ${unknown} unconfirmed` : ''}${failed ? ` · ${failed} failed` : ''}${operation.stop ? ' · Remaining imports stopped' : ''}.`;
  $('batch-note').textContent = `Sending to ${names(operation.targets)}. ${active ? 'Switching the viewed Portal keeps this import running.' : 'Confirmed copies are kept; retries target only failed copies.'}`;
  $('stop').hidden = !active || operation.stop;
  $('import').disabled = active || operation.items.some(item => item.status !== 'confirmed');
  $('dismiss').hidden = active;
  $('scenario').disabled = active;
  $('retry').hidden = active || !operation.items.some(item => item.status === 'failed' && item.reason !== 'duplicate');
  $('check').hidden = active || !operation.items.some(item => item.status === 'unconfirmed' && !item.checked);
  $('results').replaceChildren(...operation.targets.map(id => {
    const room = rooms.find(room => room.id === id), items = operation.items.filter(item => item.room === id);
    const done = items.filter(item => item.status === 'confirmed').length;
    const section = element('section', 'room-result');
    section.dataset.room = id;
    const header = element('header', '');
    header.append(element('h3', '', room.name), element('span', 'room-count', `${done} of ${items.length} stored`));
    const progress = document.createElement('progress');
    progress.max = items.length; progress.value = done;
    progress.setAttribute('aria-label', `Confirmed photos on ${room.name}`);
    section.append(header, element('p', 'muted small', `Folder: ${ownerFolder(id)}`), progress);
    const selectedPage = Math.min(resultPages.get(id) || 0, Math.max(0, Math.ceil(items.length / 8) - 1));
    for (const item of items.slice(selectedPage * 8, selectedPage * 8 + 8)) {
      const row = element('div', 'file-result');
      row.dataset.status = item.status; row.dataset.file = item.file.id;
      const copy = element('div', '');
      copy.append(element('p', '', item.file.name), element('p', '', item.detail || labels[item.status]));
      if (!active && item.reason === 'duplicate') {
        const rename = element('button', 'secondary', 'Use a new filename');
        rename.type = 'button';
        rename.addEventListener('click', () => {
          if (running()) return;
          const extension = item.file.name.match(/\.[^.]+$/)[0];
          const base = Array.from(item.file.name.slice(0, -extension.length));
          while ((base.join('') + ' (2)' + extension).length > 128) base.pop();
          item.file = { ...item.file, name: base.join('') + ' (2)' + extension };
          item.reason = null; item.detail = 'New filename selected. Ready to retry.';
          renderBatch();
        });
        copy.append(rename);
      }
      if (!active && item.status === 'unconfirmed' && item.checked) {
        const view = element('button', 'secondary', `View ${room.name}`);
        view.type = 'button';
        view.addEventListener('click', () => {
          viewing = room.id; renderView();
          $('notice').textContent = `Viewing ${room.name}. Check the photo before deciding; its import remains unconfirmed.`;
        });
        copy.append(view);
      }
      row.append(element('span', 'status-mark', item.status === 'confirmed' ? '✓' : ['failed', 'unconfirmed'].includes(item.status) ? '!' : '·'), copy);
      section.append(row);
    }
    appendPagination(section, items.length, selectedPage, value => { resultPages.set(id, value); renderBatch(); }, `${room.name} results`);
    return section;
  }));
}
function ownerFolder(id) {
  return operation.destinations[id].split('/').at(-1) || 'sdcard';
}
function appendPagination(parent, count, index, onChange, label) {
  if (count <= 8) return;
  const nav = element('div', 'pagination');
  nav.setAttribute('role', 'group'); nav.setAttribute('aria-label', label);
  for (const [text, next, disabled] of [['Previous', index - 1, index === 0], ['Next', index + 1, (index + 1) * 8 >= count]]) {
    const button = element('button', 'secondary', text); button.type = 'button'; button.disabled = disabled;
    button.addEventListener('click', () => onChange(next)); nav.append(button);
    if (text === 'Previous') nav.append(element('span', 'muted small', `${index + 1} of ${Math.ceil(count / 8)}`));
  }
  parent.append(nav);
}
function requestResult(item) {
  if (item.room === 'living' && !recovered && currentScenario === 'offline') return ['failed', 'offline', 'Could not connect. No photo was sent.'];
  if (item.room === 'living' && !recovered && currentScenario === 'expired') return ['failed', 'expired', 'Pairing needs renewal. No photo was sent.'];
  const key = JSON.stringify([item.room, operation.destinations[item.room], item.file.name]);
  if (simulatedStoredNames.has(key) ||
      (item.room === 'living' && currentScenario === 'duplicate' && item.file.id === 'garden' && !item.file.name.includes(' (2)')))
    return ['failed', 'duplicate', 'This filename already exists. The original file was kept.'];
  simulatedStoredNames.add(key);
  if (item.room === 'living' && currentScenario === 'uncertain' && item.file.id === 'garden' && item.attempts === 1)
    return ['unconfirmed', 'unknown', 'May already be stored. Check this Portal before retrying.'];
  return ['confirmed', null, 'Stored and confirmed'];
}
async function runItems(items) {
  const owner = operation, capturedGeneration = generation;
  const lanes = owner.targets.map(async id => {
    for (const item of items.filter(item => item.room === id)) {
      if (generation !== capturedGeneration || operation !== owner) return;
      if (owner.stop) { item.status = 'cancelled'; item.detail = 'Stopped before sending.'; renderBatch(); continue; }
      item.status = 'sending'; item.detail = 'Sending'; item.attempts++;
      addEvent('attempt', item, { attempt: item.attempts });
      renderBatch();
      await new Promise(resolve => setTimeout(resolve, 700));
      if (generation !== capturedGeneration || operation !== owner) return;
      [item.status, item.reason, item.detail] = requestResult(item);
      addEvent('result', item, { status: item.status });
      renderBatch();
    }
  });
  await Promise.all(lanes);
  if (generation === capturedGeneration && operation === owner) {
    renderBatch();
    $('notice').textContent = owner.items.every(item => item.status === 'confirmed') ? 'Every selected copy is stored and confirmed.' : 'Review each Portal’s result. Confirmed copies will not be resent.';
    $('retry').textContent = currentScenario === 'expired' && !recovered ? 'Renew pairing and retry failed imports' : 'Retry failed imports';
  }
}
$('import-form').addEventListener('submit', event => {
  event.preventDefault();
  if (running() || !filesValid() || !selected.size) return;
  const destinations = {};
  for (const room of rooms.filter(room => selected.has(room.id))) {
    const path = importFolder($(`folder-${room.id}`).value);
    if (path === null) {
      $('form-error').textContent = `${room.name}: choose a public shared-storage folder outside Android or hidden directories.`; return;
    }
    destinations[room.id] = path; folders.set(room.id, path);
  }
  const targets = rooms.filter(room => selected.has(room.id)).map(room => room.id);
  recipientsChosen = true;
  resultPages.clear();
  operation = { id: ++sequence, targets, destinations, stop: false, items: targets.flatMap(room => files.map(file => ({ room, file: { ...file }, status: 'queued', attempts: 0 }))) };
  addEvent('start', null, { targets: [...targets], copies: operation.items.length });
  $('import-dialog').close(); renderBatch(); runItems(operation.items);
});
$('import').addEventListener('click', openImport);
for (const id of ['close-import', 'cancel-import']) $(id).addEventListener('click', () => $('import-dialog').close());
$('choose').addEventListener('click', () => $('files').click());
$('files').addEventListener('change', () => {
  files = Array.from($('files').files).map((file, index) => ({ id: `chosen-${index}`, name: file.name, size: file.size }));
  renderSelection();
});
$('examples').addEventListener('click', () => { files = examples.map(file => ({ ...file })); renderSelection(); });
$('both').addEventListener('click', () => {
  recipientsChosen = true;
  selected = selected.size === rooms.length ? new Set() : new Set(rooms.map(room => room.id));
  renderDestinations();
});
$('view-picker').addEventListener('click', () => { renderView(); $('view-dialog').showModal(); });
$('close-view').addEventListener('click', () => $('view-dialog').close());
$('stop').addEventListener('click', () => {
  operation.stop = true;
  for (const item of operation.items) if (item.status === 'queued') { item.status = 'cancelled'; item.detail = 'Stopped before sending.'; }
  renderBatch();
});
$('retry').addEventListener('click', () => {
  if (running()) return;
  const failed = operation.items.filter(item => item.status === 'failed' && item.reason !== 'duplicate');
  if (!failed.length) return;
  // The button explicitly simulates restored connectivity/pairing; it obtains no credentials.
  recovered = true; operation.stop = false;
  for (const item of failed) { item.status = 'queued'; item.detail = 'Waiting to retry'; }
  renderBatch(); runItems(failed);
});
$('check').addEventListener('click', () => {
  if (running()) return;
  for (const item of operation.items.filter(item => item.status === 'unconfirmed')) {
    addEvent('check', item);
    item.checked = true;
    item.detail = 'A file with this name is present. View it before deciding. Import remains unconfirmed.';
  }
  renderBatch(); $('notice').textContent = 'A matching filename is present. That alone does not confirm this import. Nothing was uploaded again.';
});
$('dismiss').addEventListener('click', () => {
  if (running()) return;
  if (operation.items.some(item => item.status !== 'confirmed')) $('dismiss-dialog').showModal();
  else dismissResults();
});
$('keep-results').addEventListener('click', () => $('dismiss-dialog').close());
$('confirm-dismiss').addEventListener('click', dismissResults);
function dismissResults() {
  if (running()) return;
  generation++; operation = null; resultPages.clear();
  $('dismiss-dialog').close(); $('import').disabled = false;
  renderBatch(); $('notice').textContent = 'Result list dismissed. Stored photos are kept.';
}
$('theme').addEventListener('click', () => {
  const dark = document.documentElement.dataset.theme !== 'light';
  document.documentElement.dataset.theme = dark ? 'light' : 'dark';
  $('theme').textContent = dark ? 'Dark appearance' : 'Light appearance';
});
$('text-size').addEventListener('change', () => { document.documentElement.style.fontSize = `${16 * Number($('text-size').value)}px`; });
$('scenario').addEventListener('change', () => {
  if (running()) return;
  currentScenario = $('scenario').value;
  resetExample();
  folders.set('living', currentScenario === 'folders' ? '/sdcard/Pictures/Living wall' : '/sdcard/Pictures/Immortal');
});
function resetExample() {
  generation++; operation = null; files = []; selected = new Set([viewing]); recipientsChosen = false; recovered = false; events.length = 0;
  resultPages.clear(); selectionPage = 0; simulatedStoredNames.clear();
  $('import-dialog').close(); $('view-dialog').close(); $('dismiss-dialog').close(); $('notice').textContent = '';
  $('import').disabled = false; $('scenario').disabled = false; renderBatch();
}
$('reset').addEventListener('click', resetExample);
const illustrations = [
  '<rect width="240" height="180" fill="#6683a5"/><circle cx="177" cy="45" r="20" fill="#efdbaf"/><path d="m0 160 66-113 56 66 38-55 80 102v20H0" fill="#324e65"/><path d="m50 74 16-27 25 29-17-7-10 5" fill="#e5e9e5"/><path d="M0 147q75-25 240 4v29H0" fill="#526d58"/>',
  '<rect width="240" height="180" fill="#bfbd98"/><path d="M0 0h39v180H0M165 0h24v180h-24" fill="#676857"/><path d="M0 135q70-45 240 0v45H0" fill="#788655"/><circle cx="78" cy="95" r="35" fill="#456947"/><circle cx="177" cy="94" r="28" fill="#49684f"/><path d="M79 107v73m96-75v75" stroke="#566348" stroke-width="7"/>',
  '<rect width="240" height="180" fill="#b2c6c4"/><path d="M0 104q88-20 240 3v73H0" fill="#748f97"/><path d="M0 140q100-15 240 4v36H0" fill="#d2c3a7"/><path d="M153 58v76M153 58l42 60h-42" stroke="#ede6d7" stroke-width="3" fill="#ede6d7"/><path d="M137 132h62l-9 13h-41" fill="#725c4c"/>',
];
$('photo-grid').replaceChildren(...examples.map((file, index) => {
  const figure = element('figure', '');
  const svg = document.createElementNS('http://www.w3.org/2000/svg', 'svg');
  svg.setAttribute('viewBox', '0 0 240 180'); svg.setAttribute('role', 'img'); svg.setAttribute('aria-label', 'Generated example photo');
  svg.innerHTML = illustrations[index];
  figure.append(svg, element('figcaption', '', file.name));
  return figure;
}));
// Read-only diagnostics for review verification. It never triggers a simulated request.
Object.defineProperty(window, 'photoImportReview', { value: Object.freeze({
  snapshot: () => structuredClone({ viewing, selected: [...selected], operation, events }),
}) });
renderView();
