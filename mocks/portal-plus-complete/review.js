/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */
(() => {
 const C=PORTAL_COVERAGE,$=id=>document.getElementById(id),Q=new URLSearchParams(location.search),frame=$('preview');
 const extras=[['home','Home'],['apps','App library'],['app-folder','App folder'],['store','App Store'],['app-detail','App details'],['music','Music / YouTube Music'],['tools','Tools'],['edit','Customize home'],['widgets','Widget library'],['settings','Settings overview'],['photos','Photo display'],['digital-preview','Digital clock preview'],['welcome-preview','Welcome preview'],['wake-light','Wake light'],['lamp-preview','Lamp preview'],['alarm-ring','Alarm ringing'],['folder-preview','Folder preview'],['ha-dashboard','Home Assistant dashboard'],['switcher','Recent apps'],['provider-widgets','Android widget providers'],['chime-preview','Voice preview']];
 const screens=[...extras.map(([id,title])=>({id,title,category:'Everyday'})),...C.pages.map(p=>({...p,category:p.category}))];let screen=Q.get('screen')||'home';let category=Q.get('category')||'appearance';let advanced=Q.has('advanced')?Q.get('advanced')==='1':null;let highlight=Q.get('control'), activeReference=Q.get('reference'), activePopup=Q.get('popup')||Q.get('flow');
 $('theme').value=Q.get('theme')||'light';$('scenario').value=Q.get('scenario')||'normal';
 function nav(){const q=$('nav-search').value.toLowerCase();$('screen-nav').innerHTML=screens.filter(p=>(p.title+' '+p.category).toLowerCase().includes(q)).map(p=>`<button data-screen="${p.id}" class="${screen===p.id?'selected':''}">${p.title}</button>`).join('');}
 function url(){return 'native.html?'+new URLSearchParams({screen,category,theme:$('theme').value,scenario:$('scenario').value,...(advanced===null?{}:{advanced:advanced?'1':'0'}),...(highlight?{control:highlight}:{}),...(activeReference?{reference:activeReference}:{}),...(activePopup?{flow:activePopup}:{})});}
 function sync(){const target=url();$('full-link').href=target;$('title').textContent=screens.find(p=>p.id===screen)?.title||screen;const q=new URLSearchParams(target.split('?')[1]);if(q.has('flow')){q.set('popup',q.get('flow'));q.delete('flow');}history.replaceState(null,'','?'+q);nav();}
 function load(){frame.src=url();sync();}
 $('screen-nav').addEventListener('click',e=>{const b=e.target.closest('button');if(!b)return;screen=b.dataset.screen;highlight=null;activeReference=null;activePopup=null;$('popup').value='';category=screens.find(p=>p.id===screen)?.category==='Everyday'?'appearance':screens.find(p=>p.id===screen)?.category||category;load();});
 $('nav-search').addEventListener('input',nav);$('theme').addEventListener('change',load);$('scenario').addEventListener('change',()=>{advanced=null;load();});$('reset').addEventListener('click',load);
 $('popup').innerHTML+='<optgroup label="Source controls">'+C.popups.filter(p=>p.control).map((p,i)=>`<option value="${C.popups.indexOf(p)}">${p.screen} · ${p.title}</option>`).join('')+'</optgroup><optgroup label="Flows and confirmations">'+C.popups.filter(p=>!p.control).map(p=>`<option value="${C.popups.indexOf(p)}">${p.title}</option>`).join('')+'</optgroup>';
 function openPopup(){if(!$('popup').value)return;const p=C.popups[Number($('popup').value)];activePopup=p.id;activeReference=null;screen=p.screen;if(p.scenario)$('scenario').value=p.scenario;load();}
 $('popup').addEventListener('change',openPopup);
 window.addEventListener('message',e=>{if(e.origin!==location.origin||e.source!==frame.contentWindow||e.data.type!=='portal-mock-navigation')return;screen=e.data.screen;category=e.data.category;if(typeof e.data.advanced==='boolean')advanced=e.data.advanced;sync();$('boundary').hidden=!e.data.external;if(e.data.external)$('boundary').textContent='Existing interface: '+e.data.external.owner+'. The mock boundary panel is review annotation. Native Immortal hands off directly; that app’s own screens and dialogs remain unchanged.';});
 if(activeReference){fetch('reference-plan.json').then(r=>r.json()).then(entries=>{const e=entries.find(p=>p.id===activeReference);if(e){screen=e.screen;category=e.category||'appearance';$('theme').value=e.theme;$('scenario').value=e.scenario;}load();});}else if(activePopup){const index=C.popups.findIndex(p=>p.id===activePopup);if(index>=0){$('popup').value=String(index);openPopup();}else load();}else load();
})();
