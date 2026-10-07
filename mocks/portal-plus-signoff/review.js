/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */
(() => {
  const frame=document.getElementById("preview");
  let screen="home",category="appearance",theme="light",scenario="normal";
  const titles={home:"Home",apps:"App library",folder:"App folder",store:"App Store","app-detail":"App details",tools:"Tools",timer:"Timer",edit:"Customize home","widget-library":"Widget library",settings:"Settings",sources:"Photo sources",connect:"Connect photo source",albums:"Choose albums","clock-picker":"Clock picker",photos:"Photo display",wallpaper:"Wallpaper",calendar:"Calendar","world-clock":"World clock",wake:"Sunrise & wake",ha:"Home Assistant",remote:"Phone remote",health:"Device health",advanced:"Advanced"};
  function updateMetadata(){
    document.getElementById("title").textContent=titles[screen]||"Settings";
    document.getElementById("full-link").href="native.html?"+new URLSearchParams({screen,category,theme,scenario});
    document.querySelectorAll("[data-screen]").forEach(b=>{
      const selected=b.dataset.screen===screen&&(screen!=="settings"||(b.dataset.category||"appearance")===category);
      b.classList.toggle("selected",selected);b.setAttribute("aria-pressed",String(selected));
    });
  }
  function load(){frame.src="native.html?"+new URLSearchParams({screen,category,theme,scenario});updateMetadata();}
  document.querySelectorAll("[data-screen]").forEach(b=>b.addEventListener("click",()=>{screen=b.dataset.screen;category=b.dataset.category||"appearance";load();}));
  document.getElementById("scenario").addEventListener("change",e=>{scenario=e.target.value;load();});
  document.getElementById("theme").addEventListener("change",e=>{theme=e.target.value;load();});
  document.getElementById("reset").addEventListener("click",load);
  document.getElementById("evidence-toggle").addEventListener("click",e=>{
    const panel=document.getElementById("evidence");panel.hidden=!panel.hidden;e.currentTarget.setAttribute("aria-expanded",String(!panel.hidden));
    if(!panel.hidden)panel.scrollIntoView({block:"start",behavior:"instant"});
  });
  window.addEventListener("message",e=>{
    if(e.source!==frame.contentWindow||e.origin!==location.origin||e.data?.type!=="portal-mock-navigation")return;
    ({screen,category,theme,scenario}=e.data);document.getElementById("theme").value=theme;document.getElementById("scenario").value=scenario;updateMetadata();
  });
  new ResizeObserver(()=>{frame.style.transform=`scale(${document.getElementById("frame-box").clientWidth/1920})`;}).observe(document.getElementById("frame-box"));
  load();
})();
