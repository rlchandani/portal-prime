/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */
(() => {
  const frame = document.getElementById("prototype");
  let concept = "everyday", screen = "home", scenario = "normal";
  const names = {everyday:"Everyday",gallery:"Gallery",control:"Control"};
  const screenNames = {home:"Home",settings:"Settings",source:"Connect photo source",customize:"Customize home",apps:"Apps & folders",store:"App Store",photos:"Photo display",tools:"Tools"};
  const descriptions = {
    everyday:["The essentials, with a place for everything else.","<b>The recommendation:</b> keep Apple-style grouped settings; adapt the home to a shared household display. Favorite apps are separate from the full library, and frequent actions stay visible."],
    gallery:["A home that belongs in the room.","<b>The alternative:</b> let photography set the tone. A single upcoming event and compact favorites keep it useful. Best when the Portal is primarily a photo frame."],
    control:["The next action is always within reach.","<b>The specialist:</b> group controls by household task. Smart-home and music take priority; photos become supporting content. Best for a kitchen or home-automation station."]
  };
  function select() {
    const qs = new URLSearchParams({concept,screen,scenario});
    frame.src = "prototype.html?" + qs;
    document.getElementById("full-link").href = frame.src;
    document.getElementById("review-title").textContent = names[concept] + " / " + screenNames[screen];
    document.getElementById("review-subtitle").textContent = descriptions[concept][0];
    document.getElementById("concept-caption").innerHTML = descriptions[concept][1];
    document.querySelectorAll("[data-concept]").forEach(b => {b.classList.toggle("selected",b.dataset.concept===concept);b.setAttribute("aria-pressed",String(b.dataset.concept===concept));});
    document.querySelectorAll("[data-screen]").forEach(b => {b.classList.toggle("selected",b.dataset.screen===screen);b.setAttribute("aria-pressed",String(b.dataset.screen===screen));});
  }
  document.querySelectorAll("[data-concept]").forEach(b=>b.addEventListener("click",()=>{concept=b.dataset.concept;select();}));
  document.querySelectorAll("[data-screen]").forEach(b=>b.addEventListener("click",()=>{screen=b.dataset.screen;select();}));
  document.getElementById("scenario").addEventListener("change",e=>{scenario=e.target.value;select();});
  window.addEventListener("message",event=>{
    if(event.source!==frame.contentWindow||event.origin!==location.origin||event.data?.type!=="immortal-navigation")return;
    screen=event.data.screen;
    const qs=new URLSearchParams({concept,screen,scenario});
    document.getElementById("full-link").href="prototype.html?"+qs;
    document.getElementById("review-title").textContent=names[concept]+" / "+(screenNames[screen]||"Tools");
    document.querySelectorAll("[data-screen]").forEach(b=>{b.classList.toggle("selected",b.dataset.screen===screen);b.setAttribute("aria-pressed",String(b.dataset.screen===screen));});
  });
  document.getElementById("notes-button").addEventListener("click",e=>{
    const panel=document.getElementById("research-panel");panel.hidden=!panel.hidden;
    e.currentTarget.setAttribute("aria-expanded",String(!panel.hidden));
    if(!panel.hidden)panel.scrollIntoView({behavior:matchMedia("(prefers-reduced-motion: reduce)").matches?"instant":"smooth",block:"start"});
  });
  new ResizeObserver(()=>{frame.style.transform=`scale(${document.getElementById("preview-wrap").clientWidth/1440})`;}).observe(document.getElementById("preview-wrap"));
  select();
})();
