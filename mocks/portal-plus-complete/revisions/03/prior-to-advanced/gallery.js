/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */
(() => {
  const data = PORTAL_REFERENCES, $ = id => document.getElementById(id);
  const escape = s => String(s).replace(/[&<>"]/g,c=>({'&':'&amp;','<':'&lt;','>':'&gt;','"':'&quot;'}[c]));
  $('intro').textContent = `${data.entries.length} references at 2160 × 1440 pixels. ` +
    'Filter by screen, state or theme; open a PNG for the visual target or its interactive state for behavior.';
  for (const kind of [...new Set(data.entries.map(e=>e.kind))]) $('kind').add(new Option(kind,kind));
  function render() {
    const query = $('search').value.toLowerCase(), theme = $('theme').value,
      kind = $('kind').value, owner = $('ownership').value;
    const shown = data.entries.filter(e=>(!theme||e.theme===theme)&&(!kind||e.kind===kind)&&
      (!owner||(owner==='native')===e.contractTarget)&&
      (e.title+' '+e.screen+' '+e.kind+' '+e.scenario+' '+e.id).toLowerCase().includes(query));
    $('count').textContent = shown.length+' of '+data.entries.length+' references';
    $('gallery').innerHTML = shown.map(e=>`<article><a href="${escape(e.file)}" target="_blank"><img src="${escape(e.thumbnail)}" loading="lazy" alt="${escape(e.title)} · ${e.theme}"></a><div class="copy"><h2>${escape(e.title)}</h2><p>${escape(e.kind)} · ${e.theme} · ${escape(e.scenario)}</p>${e.contractTarget?'':'<p class="tag">Existing interface / review annotation</p>'}<a href="./?reference=${encodeURIComponent(e.id)}" target="_blank">Try this state ↗</a><a href="${escape(e.file)}" target="_blank">Full PNG ↗</a></div></article>`).join('');
  }
  for (const id of ['search','theme','kind','ownership']) $(id).addEventListener(id==='search'?'input':'change',render);
  render();
})();
