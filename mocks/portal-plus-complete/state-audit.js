/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */
(async()=>{
 const [audit,walk,checks]=await Promise.all(['state-audit.json','device-walkthrough.json','conditional-verification.json'].map(p=>fetch(p).then(r=>r.json())));
 const $=id=>document.getElementById(id),esc=s=>String(s??'').replace(/[&<>"']/g,c=>({'&':'&amp;','<':'&lt;','>':'&gt;','"':'&quot;',"'":'&#39;'}[c]));
 $('summary').textContent=`${audit.families.length} state families · ${audit.registry.length} registry entries · ${audit.nativeRecords} native walkthrough records · ${checks.passed}/${checks.total} conditional browser checks passed`;
 $('families').innerHTML=audit.families.map(f=>`<article data-search="${esc(Object.values(f).join(' ').toLowerCase())}"><h2>${esc(f.title)}</h2><small>Parent: <code>${esc(f.parent)}</code></small><p>${esc(f.behavior)}</p><p><strong>Evidence:</strong> ${esc(f.evidence)}</p><small>Source: ${esc(f.source)}</small><a href="./?screen=${encodeURIComponent(f.screen)}&scenario=expanded">Open interactive screen ↗</a></article>`).join('');
 $('registry').innerHTML=audit.registry.map(c=>{const editor=!c.derived&&!c.hidden&&['enum','int','string'].includes(c.type)&&c.id!=='screensaver.batterySaver';const params=new URLSearchParams({screen:c.page||'managed',scenario:'expanded',...(editor?{control:c.id}:{})});return `<tr data-search="${esc([c.id,c.title,c.nativeRule].join(' ').toLowerCase())}"><td><strong>${esc(c.title)}</strong><small>${esc(c.id)}</small></td><td><code>${esc(c.visibility)}</code></td><td>${esc(c.derived?'Derived status field; native schema retained, no editor':c.hidden?'Intentionally hidden; persisted value retained':c.id==='screensaver.batterySaver'?'Absent on connected Portal+ (no battery)':c.nativeRule)}</td><td><a href="./?${params}">Review</a></td></tr>`;}).join('');
 $('walkthrough').innerHTML=walk.records.map(r=>`<details><summary>${esc(r.screen||r.parent||'Chime combination')} · ${esc(r.kind)}${r.restored?' · restored':''}</summary><p>${esc(JSON.stringify(r.parents||{parent:r.parent,state:r.state}))}</p><p>${(r.labels||[]).map(esc).join(' · ')}</p></details>`).join('');
 $('limits').innerHTML=audit.limitations.map(x=>`<li>${esc(x)}</li>`).join('');
 $('filter').addEventListener('input',e=>{const q=e.target.value.trim().toLowerCase();document.querySelectorAll('[data-search]').forEach(el=>el.hidden=!el.dataset.search.includes(q));});
})().catch(e=>{document.getElementById('summary').textContent='Audit could not load: '+e.message;});
