/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */
const $ = id => document.getElementById(id);
const escape = value => String(value).replace(/[&<>"']/g, c => ({'&':'&amp;','<':'&lt;','>':'&gt;','"':'&quot;',"'":'&#39;'}[c]));
const pages = { setup:['Quick controls setup','Shortcuts to your dashboard and installed apps.','home'],
  home:['Home & widgets','Set up the shortcuts shown on Home.','home'],
  dashboard:['Home Assistant dashboard','Use the existing dashboard address.','connections'],
  connections:['Connections','Connect your dashboard and apps.','connections'],
  preview:['Quick controls widget','Try its heading and the Settings action in Customize.','home'] };
const icons = {
  jarvis:'<path d="M12 3v12m-4-8v4a4 4 0 0 0 8 0V7M5 10v1a7 7 0 0 0 14 0v-1M12 18v3m-4 0h8"/>',
  dashboard:'<path d="m3 11 9-8 9 8M6 10v11h12V10m-9 5h6v6"/>',
  lights:'<path d="M8 16c0-3-3-4-3-7a7 7 0 0 1 14 0c0 3-3 4-3 7M8 17h8m-7 3h6m-5 2h4"/>',
  scenes:'<path d="m12 2 2 7 7 3-7 2-2 8-3-8-7-2 7-3z"/>',
  climate:'<path d="M10 5a2 2 0 0 1 4 0v10a4 4 0 1 1-4 0zM12 10v8m6-10h3m-3 4h2"/>'
};
let state = {url:'',jarvis:true,ha:true};
let history = ['home','setup'], position = 1, editing = false;
const body = $('detail-body');

function row(title, subtitle, action, value='') {
  return `<button class="setting-row" type="button" data-action="${action}"><span class="row-label"><strong>${title}</strong>${subtitle ? `<span>${escape(subtitle)}</span>` : ''}</span>${value ? `<span class="row-value">${escape(value)}</span>` : ''}<span class="chevron" aria-hidden="true">›</span></button>`;
}
function inventory(title, available, detail) {
  const label=available===null?'Checking…':available?'Available':'Not available';
  return `<div class="setting-row"><span class="row-label"><strong>${title}</strong><span>${detail}</span></span><span class="row-value ${available?'available':''}">${label}</span></div>`;
}
function shortcuts() {
  const result = [];
  if (state.jarvis) result.push({id:'jarvis',title:'Hey Jarvis',detail:'Open voice assistant'});
  if (state.url) result.push({id:'dashboard',title:'Home dashboard',detail:'Open saved dashboard'});
  else if (state.ha) for (const [id,title] of [['lights','Lights'],['scenes','Scenes'],['climate','Climate'],['home','Home']])
    result.push({id, title, detail:'Open Home Assistant'});
  return result;
}
function preview() {
  const tiles = shortcuts();
  return `<section class="widget-card" aria-label="Quick controls widget preview">
    <button type="button" class="widget-heading" data-action="setup" aria-label="Quick controls widget heading">Quick controls<span aria-hidden="true">›</span></button>
    ${tiles.length ? `<div class="tiles">${tiles.map(t=>`<button type="button" class="shortcut" data-launch="${t.id}"><svg viewBox="0 0 24 24" aria-hidden="true">${icons[t.id]||icons.dashboard}</svg>${t.title}<small>${t.detail}</small></button>`).join('')}</div>`
      : '<p class="hint">Add a dashboard address or install a supported app to show shortcuts.</p>'}
    ${editing ? '<button type="button" class="edit-action" data-action="setup" aria-label="Widget Settings">Settings</button>' : ''}
  </section>`;
}
function navigate(page) {
  if (history[position] === page) return;
  history = history.slice(0,position+1).concat(page);position++;render();focusHeading();
}
function focusHeading() {
  $('page-title').focus({preventScroll:true});
  $('announcement').textContent=pages[history[position]][0];
}
function render() {
  const page = history[position], [title,subtitle,category] = pages[page];
  $('page-title').textContent=title;$('page-subtitle').textContent=subtitle;
  for (const b of document.querySelectorAll('[data-category]')) b.setAttribute('aria-current',String(b.dataset.category===category));
  for (const [id,step] of [['back',-1],['forward',1]]) {
    const target=history[position+step];$(id).disabled=!target;
    $(id).setAttribute('aria-label',`Settings ${step<0?'Back':'Forward'}${target?' to '+pages[target][0]:''}`);
  }
  if (page==='setup') {
    body.innerHTML=`<h3 class="section-label">Dashboard</h3><div class="card">${row('Home dashboard',state.url?'Address saved. Opens in the Portal browser.':'Add your dashboard address. No Home Assistant app is required.','dashboard',state.url?'Configured':'Not configured')}</div>
      <p class="hint">When a dashboard address is saved, its shortcut replaces the four Home Assistant app shortcuts. A saved address does not confirm connection or sign-in.</p>
      <h3 class="section-label">Apps</h3><div class="card">${inventory('Jarvis',state.jarvis,'Shows Hey Jarvis when the app can be opened.')}${inventory('Home Assistant',state.ha,state.url?'App shortcuts are hidden while a dashboard is configured.':'Lights, Scenes, Climate and Home all open the same app.')}${row('Manage apps','Open Apps to install or manage supported apps.','open-apps')}</div>
      <p class="hint">Availability is checked on the device when you return. These settings are shared by every Quick controls widget.</p>
      <h3 class="section-label">On Home</h3>${preview()}${!state.url&&state.ha?'<p class="hint">The four Home Assistant shortcuts launch the app. They do not directly switch a light, run a scene or change the climate.</p>':''}`;
  } else if(page==='dashboard') {
    body.innerHTML=`<div class="card">${row('Dashboard address','An HTTP or HTTPS address. Leave blank to disable.','edit-address',state.url||'Not configured')}${state.url?row('Open dashboard','Open the saved address in the Portal browser.','open-dashboard'):''}</div>
      <p class="hint">Your dashboard opens in the Portal browser and keeps its saved sign-in. It runs when you open it, keeping Home lightweight.</p>
      ${$('advanced').checked?'<p class="hint">Advanced native API controls remain in the existing settings owner. They are outside this focused review and do not configure these shortcuts.</p>':''}`;
  } else if(page==='preview') {
    body.innerHTML=`<button type="button" data-action="customize" aria-pressed="${editing}">${editing?'Done customizing':'Customize'}</button><p class="hint">Tap the widget heading, or use Customize and then Settings. Both reach Quick controls setup.</p>${preview()}`;
  } else if(page==='connections') {
    body.innerHTML=`<div class="card">${row('Home Assistant dashboard','Dashboard address and browser sign-in.','dashboard',state.url?'Configured':'Not configured')}</div>`;
  } else {
    body.innerHTML=`<div class="card">${row('Quick controls setup','Dashboard and app shortcut availability.','setup')}${row('Preview widget','Try both setup entry points.','preview')}</div>`;
  }
  body.scrollTop=0;
}
function example(value) {
  state={url:['dashboard','checking'].includes(value)?'https://home.example/dashboard/':'',
    jarvis:value==='checking'?null:value!=='empty',
    ha:value==='checking'?null:['dashboard','apps'].includes(value)};
  history=['home','setup'];position=1;editing=false;render();
  $('announcement').textContent='Example device reset. No Portal was changed.';
}
function normalizeUrl(raw) {
  // An illustrative browser guard, never a replacement for native HomeDashboardConfig.
  if(/[\x00-\x1f\x7f-\x9f]/.test(raw)) return null;
  const text=raw.trim();if(!text) return '';
  if(/[\\\s<>"{}|^]/.test(text)||/%(?![0-9a-f]{2})/i.test(text))return null;
  try {
    const parsed=new URL(text);
    if(!/^https?:$/i.test(parsed.protocol)||!/^https?:\/\//i.test(text)||!parsed.hostname||parsed.username||parsed.password) return null;
    const authority=text.replace(/^https?:\/\//i,'').split(/[/?#]/,1)[0];
    if(!authority||authority.includes('@'))return null;
    if(!parsed.hostname.startsWith('[')){
      if(!/^[-a-z0-9.]+(?::\d*)?$/i.test(authority))return null;
      const host=authority.split(':',1)[0].replace(/\.$/,'');
      if(host.split('.').some(label=>! /^[a-z0-9](?:[a-z0-9-]*[a-z0-9])?$/i.test(label)))return null;
    }
    return text;
  } catch {return null;}
}
function openEditor() {
  $('address').value=state.url;$('address-error').textContent='';$('address').removeAttribute('aria-invalid');
  $('editor').showModal();$('address').focus();
}
function launch(id) {
  if(id==='apps') {
    $('handoff-title').textContent='Open Apps';
    $('handoff-description').textContent='On the Portal this leaves Settings for the existing Apps tab. It does not create an Apps settings page. Installation and the full Apps screen are outside this focused mock.';
    $('handoff').showModal();return;
  }
  const jarvis=id==='jarvis',dashboard=id==='dashboard';
  $('handoff-title').textContent=jarvis?'Open Jarvis':dashboard?'Open Home dashboard':'Open Home Assistant';
  $('handoff-description').textContent=jarvis?'On the Portal this opens Jarvis and sends its existing wake handoff. This mock cannot confirm microphone activation.':dashboard?'On the Portal this opens the saved address, preferring Chrome and retaining its browser sign-in. The address is simulated; no site was opened.':'On the Portal Lights, Scenes, Climate and Home all open the same Home Assistant app. This mock does not issue a home-control command.';
  $('handoff').showModal();
}
body.addEventListener('click',event=>{
  const button=event.target.closest('button');if(!button)return;
  if(button.dataset.launch){launch(button.dataset.launch);return;}
  const action=button.dataset.action;
  if(action==='edit-address')openEditor();
  else if(action==='open-dashboard')launch('dashboard');
  else if(action==='open-apps')launch('apps');
  else if(action==='customize'){editing=!editing;render();body.querySelector('[data-action="customize"]').focus({preventScroll:true});}
  else if(pages[action])navigate(action);
});
$('address-form').addEventListener('submit',event=>{
  event.preventDefault();const normalized=normalizeUrl($('address').value);
  if(normalized===null){$('address-error').textContent='Enter a complete HTTP or HTTPS address without embedded credentials.';$('address').setAttribute('aria-invalid','true');return;}
  state.url=normalized;$('editor').close();render();
  body.querySelector('[data-action="edit-address"]').focus({preventScroll:true});
  $('announcement').textContent='Dashboard address saved in the mock.';
});
$('cancel-edit').addEventListener('click',()=>$('editor').close());
$('close-handoff').addEventListener('click',()=>$('handoff').close());
$('back').addEventListener('click',()=>{if(position>0){position--;render();focusHeading();}});
$('forward').addEventListener('click',()=>{if(position<history.length-1){position++;render();focusHeading();}});
$('home').addEventListener('click',()=>navigate('preview'));
$('theme').addEventListener('click',()=>{
  const light=document.documentElement.dataset.theme!=='light';document.documentElement.dataset.theme=light?'light':'dark';
  $('theme').textContent=light?'Dark appearance':'Light appearance';
});
$('advanced').addEventListener('change',render);
$('text-size').addEventListener('change',()=>document.documentElement.style.setProperty('--scale',$('text-size').value));
$('scenario').addEventListener('change',()=>example($('scenario').value));
document.querySelectorAll('[data-category]').forEach(b=>b.addEventListener('click',()=>navigate(b.dataset.category)));
document.querySelectorAll('[data-entry]').forEach(b=>b.addEventListener('click',()=>{
  editing=b.dataset.entry==='edit';history=['preview','setup'];position=1;render();focusHeading();
  $('announcement').textContent=`${editing?'Customize Settings':'Widget heading'} opens Quick controls setup.`;
}));
const params=new URLSearchParams(location.search);
if(params.get('theme')==='light'){$('theme').click();}
if(params.get('text')==='1.5'){$('text-size').value='1.5';$('text-size').dispatchEvent(new Event('change'));}
if(['dashboard','apps','jarvis','empty','checking'].includes(params.get('scenario')))$('scenario').value=params.get('scenario');
example($('scenario').value);
