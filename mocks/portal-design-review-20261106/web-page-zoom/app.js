/*
 * Web page size mock. Simulates what the recommended native approach does:
 * the page's layout viewport becomes (1200 / size) CSS px wide and is fitted
 * to the screen, so a responsive dashboard genuinely reflows (column count
 * changes) instead of being magnified and cropped.
 */
(() => {
  const DEVICE_CSS_WIDTH = 1200; // 2160 px / density 1.8 on Family Portal
  const DEVICE_CSS_HEIGHT = 800;
  const SIZES = [
    { factor: 0.75, name: "Smallest", note: "Fits more on screen. Best when you stand close." },
    { factor: 0.9, name: "Smaller", note: "A little more on screen, a little smaller to read." },
    { factor: 1, name: "Default", note: "The page lays itself out for this screen, as it was designed to." },
    { factor: 1.25, name: "Larger", note: "Fewer, bigger cards. Easier to read across the room." },
    { factor: 1.5, name: "Largest", note: "Biggest text and controls. Long pages will need scrolling." },
  ];
  const DEFAULT_INDEX = 2;

  const params = new URLSearchParams(location.search);
  const root = document.documentElement;
  const portal = document.getElementById("portal");

  const clampIndex = (i) => Math.max(0, Math.min(SIZES.length - 1, i));
  const parsedSize = Number.parseInt(params.get("size") ?? "", 10);
  let state = {
    index: Number.isFinite(parsedSize) ? clampIndex(parsedSize) : DEFAULT_INDEX,
    view: params.get("view") === "adjust" ? "adjust" : "settings",
  };
  root.dataset.theme = params.get("theme") === "light" ? "light" : "dark";

  const CARDS = [
    { cls: "wx", title: "Outside", big: "14°", sub: "Light rain until 4 pm" },
    { cls: "", title: "Kitchen lights", big: "On", sub: "3 of 4 lights, 70%" },
    { cls: "", title: "Thermostat", big: "21.5°", sub: "Heating to 22°" },
    { cls: "tall", title: "Today", list: ["9:30  School run", "13:00  Grocery delivery", "18:30  Football practice", "20:00  Bins out"] },
    { cls: "", title: "Front door", big: "Locked", sub: "Since 8:12 am" },
    { cls: "bar", title: "Energy today", big: "6.4 kWh", sub: "Solar covered 41%" },
    { cls: "", title: "Living room", big: "Paused", sub: "Kitchen speaker" },
    { cls: "", title: "Washer", big: "28 min", sub: "Rinse cycle" },
    { cls: "", title: "Garage", big: "Closed", sub: "Car is home" },
    { cls: "", title: "Air quality", big: "Good", sub: "CO₂ 610 ppm" },
  ];

  const cardHtml = (c) => {
    const body = c.list
      ? `<ul>${c.list.map((l) => `<li>${l}</li>`).join("")}</ul>`
      : `<b>${c.big}</b><small>${c.sub}</small>${c.cls === "bar" ? '<i class="meter"><i></i></i>' : ""}`;
    return `<article class="dash-card ${c.cls}"><h4>${c.title}</h4>${body}</article>`;
  };

  const pageHtml = () => `
    <div class="dash">
      <header class="dash-top"><span>Kitchen wall</span><span>Thursday 6 November</span></header>
      <div class="dash-grid">${CARDS.map(cardHtml).join("")}</div>
    </div>`;

  // Build each simulated page once; size changes only touch layout width + scale.
  const viewports = [...document.querySelectorAll("[data-viewport]")].map((vp) => {
    const page = document.createElement("div");
    page.className = "page";
    page.innerHTML = pageHtml();
    vp.appendChild(page);
    return { vp, page };
  });

  const layoutPage = ({ vp, page }, factor) => {
    const cssWidth = DEVICE_CSS_WIDTH / factor;
    const cssHeight = DEVICE_CSS_HEIGHT / factor;
    const scale = vp.clientWidth / cssWidth;
    page.style.width = `${cssWidth}px`;
    page.style.height = `${cssHeight}px`;
    page.style.transform = `scale(${scale})`;
  };

  const renderStops = () => {
    document.querySelectorAll("[data-stops]").forEach((host) => {
      host.innerHTML = SIZES.map((s, i) => {
        const current = i === state.index;
        const isDefault = i === DEFAULT_INDEX;
        return `<button class="stop${current ? " is-current" : ""}${isDefault ? " is-default" : ""}"
          data-index="${i}" aria-label="${s.name}" aria-pressed="${current}"><span></span></button>`;
      }).join("");
    });
  };

  const render = () => {
    const size = SIZES[state.index];
    portal.dataset.view = state.view;
    document.querySelectorAll("[data-size-name]").forEach((el) => { el.textContent = size.name; });
    document.querySelectorAll("[data-size-note]").forEach((el) => { el.textContent = size.note; });
    document.querySelectorAll("[data-step='-1']").forEach((b) => { b.disabled = state.index === 0; });
    document.querySelectorAll("[data-step='1']").forEach((b) => { b.disabled = state.index === SIZES.length - 1; });
    renderStops();
    viewports.forEach((v) => layoutPage(v, size.factor));
  };

  const update = (patch) => {
    state = { ...state, ...patch };
    render();
  };

  document.addEventListener("click", (e) => {
    const step = e.target.closest("[data-step]");
    if (step) return update({ index: clampIndex(state.index + Number(step.dataset.step)) });
    const stop = e.target.closest("[data-index]");
    if (stop) return update({ index: clampIndex(Number(stop.dataset.index)) });
    const go = e.target.closest("[data-go]");
    if (go) return update({ view: go.dataset.go });
    return undefined;
  });

  window.addEventListener("resize", render);
  render();
  // Animate only changes the person makes, never the first paint.
  requestAnimationFrame(() => requestAnimationFrame(() => portal.classList.add("is-live")));
})();
