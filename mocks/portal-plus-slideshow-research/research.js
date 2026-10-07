/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */
'use strict';
const illustrations = {
  portrait: { w:800, h:1200, region:{x:260,y:90,w:220,h:260},
    content:'<rect width="800" height="1200" fill="#bac9cf"/><rect x="520" width="280" height="1200" fill="#84959c"/><rect y="810" width="800" height="390" fill="#746c60"/><path d="M140 970 Q150 360 360 345 Q580 360 620 970Z" fill="#435863"/><ellipse cx="370" cy="225" rx="85" ry="110" fill="#d8bc9f"/><path d="M286 215Q270 90 367 107Q465 110 453 210L430 172L311 180Z" fill="#383837"/><circle cx="341" cy="230" r="6" fill="#383837"/><circle cx="400" cy="230" r="6" fill="#383837"/><path d="M345 278Q371 294 395 277" fill="none" stroke="#976c58" stroke-width="6"/><rect x="45" y="990" width="710" height="130" rx="10" fill="#607278"/>',
    before:'The middle of a tall image can exclude the face near its top.',
    after:'The protected region remains inside the view for the complete proposed move.' },
  group: { w:1200, h:600, region:{x:15,y:45,w:1170,h:180},
    content:'<rect width="1200" height="600" fill="#9db2b5"/><path d="M0 290L130 150L340 310L560 175L790 280L1060 90L1200 250V600H0Z" fill="#71888c"/><rect y="450" width="1200" height="150" fill="#586b59"/>'+[70,390,790,1130].map((x,i)=>`<path d="M${x-75} 465Q${x-72} 230 ${x} 200Q${x+72} 230 ${x+75} 465Z" fill="${['#425c64','#666d82','#687b67','#5c7184'][i]}"/><ellipse cx="${x}" cy="130" rx="42" ry="58" fill="#d8bc9f"/><path d="M${x-43} 123Q${x-38} 66 ${x} 68Q${x+46} 76 ${x+41} 120Z" fill="#383837"/>`).join(''),
    before:'Filling the screen cuts into the people at the outer edges.',
    after:'The group cannot fit a safe cover crop. Show the whole photo instead.' },
  panorama: { w:1600, h:550, region:null,
    content:'<rect width="1600" height="550" fill="#adc5d1"/><circle cx="1330" cy="90" r="38" fill="#e6d6aa"/><path d="M0 360L230 115L360 275L600 85L850 320L1050 175L1280 320L1450 90L1600 300V550H0Z" fill="#788c94"/><path d="M0 470L350 330L650 465L1020 280L1390 420L1600 330V550H0Z" fill="#526c62"/><rect y="480" width="1600" height="70" fill="#304b49"/>',
    before:'A center crop loses much of the landscape.',
    after:'Whole-photo framing preserves the panorama. A static image is a complete presentation.' },
};
let selected = 'portrait';
const width = 600, height = 400;
function renderPane(container, scene, transform, showRegion) {
  const box = scene.region;
  const mark = showRegion && box ? `<rect x="${box.x}" y="${box.y}" width="${box.w}" height="${box.h}" fill="none" stroke="#fff" stroke-width="${3/transform.scale}" stroke-dasharray="${8/transform.scale} ${6/transform.scale}"/>` : '';
  container.innerHTML = `<svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 ${width} ${height}" role="img" aria-label="Illustrated photograph and framing"><rect width="${width}" height="${height}" fill="#14161b"/><g transform="translate(${transform.x} ${transform.y}) scale(${transform.scale})">${scene.content}${mark}</g></svg>`;
}
function safePlan(scene, t) {
  const cover = Math.max(width/scene.w, height/scene.h);
  const contain = Math.min(width/scene.w, height/scene.h);
  const b = scene.region;
  const maximum = b ? Math.min(width/b.w, height/b.h) : contain;
  if (!b || cover > maximum) {
    return {scale:contain,x:(width-scene.w*contain)/2,y:(height-scene.h*contain)/2,whole:true};
  }
  const scale = Math.min(cover*(1+.03*t), maximum);
  // Translation must satisfy both image-edge and protected-region constraints.
  const minX = Math.max(width-scene.w*scale, -b.x*scale);
  const maxX = Math.min(0, width-(b.x+b.w)*scale);
  const minY = Math.max(height-scene.h*scale, -b.y*scale);
  const maxY = Math.min(0, height-(b.y+b.h)*scale);
  const clamp = (value,min,max) => Math.min(max,Math.max(min,value));
  return {scale,x:clamp(width/2-(b.x+b.w/2)*scale,minX,maxX),
    y:clamp(height/2-(b.y+b.h/2)*scale+(t-.5)*height*.03,minY,maxY),whole:false};
}
function update() {
  const scene = illustrations[selected];
  const t = Number(document.getElementById('progress').value)/100;
  const cover = Math.max(width/scene.w,height/scene.h);
  const centered = {scale:cover,x:(width-scene.w*cover)/2,y:(height-scene.h*cover)/2};
  const safe = safePlan(scene,t);
  const regions = document.getElementById('regions').checked;
  renderPane(document.getElementById('centered'),scene,centered,regions);
  renderPane(document.getElementById('safe'),scene,safe,regions);
  document.getElementById('progress-label').textContent = `${Math.round(t*100)}%`;
  document.getElementById('before-description').textContent = scene.before;
  document.getElementById('after-description').textContent = scene.after;
  document.getElementById('safe-title').textContent = safe.whole ? 'Whole-photo fallback' : 'Constrained framing';
  document.getElementById('geometry-result').textContent = safe.whole
    ? 'Preserve the entire photograph. Do not force a moving crop when its protected content cannot fit.'
    : 'Both scale and position are limited by the protected region. The face stays visible as you scrub.';
  document.querySelectorAll('[data-case]').forEach(b=>b.setAttribute('aria-pressed',String(b.dataset.case===selected)));
  // Expose only illustrative geometry for verification, not detector or performance results.
  window.illustrationState = {selected,progress:t,scene:{w:scene.w,h:scene.h,region:scene.region},safe,viewport:{width,height}};
}
document.querySelectorAll('[data-case]').forEach(b=>b.addEventListener('click',()=>{
  selected=b.dataset.case;document.getElementById('progress').value='0';update();
}));
document.getElementById('progress').addEventListener('input',update);
document.getElementById('regions').addEventListener('change',update);
update();
