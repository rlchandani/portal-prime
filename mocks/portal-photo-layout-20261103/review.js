/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */
const samples = [
  { seed: 1, w: 960, h: 638, url: "samples/landscape.jpg" },
  { seed: 2, w: 480, h: 720, url: "samples/portrait.jpg" },
  { seed: 3, w: 720, h: 720, url: "samples/square.jpg" },
  { seed: 4, w: 1200, h: 450, url: "samples/panorama.jpg" },
  { seed: 5, w: 480, h: 720, url: "samples/portrait-mountain.jpg" },
  { seed: 6, w: 960, h: 640, url: "samples/valley.jpg" },
  { seed: 7, w: 720, h: 720, url: "samples/square-forest.jpg" },
  { seed: 8, w: 960, h: 640, url: "samples/mountain.jpg" },
  { seed: 9, w: 480, h: 720, url: "samples/portrait-forest.jpg" },
];
const preview = document.querySelector("#preview");
const photographs = document.querySelector("#photographs");
const baseline = document.querySelector("#baseline");
const parameters = new URLSearchParams(location.search);
let offset = 0;
let snapshot = {};
const fields = ["layout", "version", "theme"];
for (const name of fields) {
  const value = parameters.get(name);
  const input = [...document.querySelectorAll(`input[name="${name}"]`)].find(x => x.value === value);
  if (input) input.checked = true;
}
for (const id of ["format", "photo"]) {
  const value = parameters.get(id);
  const select = document.getElementById(id);
  if ([...select.options].some(x => x.value === value)) select.value = value;
}
function choice(name) { return document.querySelector(`input[name="${name}"]:checked`).value; }

// Exhaust the small review set's row partitions; maximize whole-photo area with
// a mild penalty for uneven row heights. This is proposed geometry, not shipped code.
function arrange(photos, width, height, gap) {
  let best = null;
  for (let mask = 0; mask < 1 << (photos.length - 1); mask++) {
    const rows = [[]];
    photos.forEach((photo, index) => {
      rows[rows.length - 1].push(photo);
      if (mask & (1 << index)) rows.push([]);
    });
    if (rows.length > 3 || rows.some(row => row.length < 2)) continue;
    const heights = rows.map(row => (width - (row.length - 1) * gap) /
      row.reduce((sum, photo) => sum + photo.w / photo.h, 0));
    const rawHeight = heights.reduce((a, b) => a + b, 0) + (rows.length - 1) * gap;
    const scale = Math.min(1, height / rawHeight);
    const occupied = heights.reduce((sum, h, i) =>
      sum + (width - (rows[i].length - 1) * gap) * h, 0) * scale * scale;
    const score = occupied * (1 - .035 * (Math.max(...heights) / Math.min(...heights) - 1));
    if (!best || score > best.score) best = { rows, heights, rawHeight, scale, score };
  }
  const boxes = [];
  let y = (height - best.rawHeight * best.scale) / 2;
  best.rows.forEach((row, index) => {
    const h = best.heights[index] * best.scale;
    let x = (width - width * best.scale) / 2;
    row.forEach(photo => {
      const w = h * photo.w / photo.h;
      boxes.push({ photo, x, y, w, h });
      x += w + gap * best.scale;
    });
    y += h + gap * best.scale;
  });
  return boxes;
}
function render() {
  const layout = choice("layout"), version = choice("version"), theme = choice("theme");
  const format = document.querySelector("#format").value;
  const ratios = { plus: 3 / 2, wide: 1024 / 600, portrait: 2 / 3 };
  preview.style.aspectRatio = ratios[format];
  preview.dataset.theme = theme;
  preview.classList.toggle("no-pattern", !document.querySelector("#pattern").checked);
  preview.classList.toggle("no-frame", !document.querySelector("#frame").checked);
  document.querySelector("#shape-option").hidden = layout !== "single";
  const { width, height } = preview.getBoundingClientRect();
  const pad = Math.max(8, Math.min(width, height) * .028);
  const footer = Math.max(32, height * .095);
  const edge = document.querySelector("#frame").checked ? Math.max(1, Math.min(4, width * .003)) : 0;
  const availableW = width - pad * 2;
  const availableH = height - pad * 2 - footer;
  const visible = [0, 1, 2, 4, 5, 6].map(i => samples[(i + offset) % samples.length]);
  const selected = samples[Number(document.querySelector("#photo").value)];
  let boxes;
  if (layout === "wall") {
    boxes = arrange(visible, availableW, availableH, Math.max(6, width * .009));
  } else {
    const scale = Math.min((availableW - edge * 2) / selected.w, (availableH - edge * 2) / selected.h);
    const w = selected.w * scale + edge * 2, h = selected.h * scale + edge * 2;
    boxes = [{ photo: selected, x: (availableW - w) / 2, y: (availableH - h) / 2, w, h }];
  }
  photographs.replaceChildren(...boxes.map(box => {
    const figure = document.createElement("figure");
    figure.className = "photograph";
    // Frame adds to the natural image box. Row layout removes its padding from
    // the interior uniformly so neither dimension silently stretches the image.
    const insetScale = Math.min((box.w - edge * 2) / box.photo.w, (box.h - edge * 2) / box.photo.h);
    const w = box.photo.w * insetScale + edge * 2, h = box.photo.h * insetScale + edge * 2;
    figure.style.cssText = `left:${pad + box.x + (box.w - w) / 2}px;top:${pad + box.y + (box.h - h) / 2}px;width:${w}px;height:${h}px;--edge:${edge}px`;
    const image = document.createElement("img");
    image.src = box.photo.url;
    image.alt = `Scenic sample ${box.photo.seed}`;
    image.width = box.photo.w; image.height = box.photo.h;
    figure.append(image);
    return figure;
  }));
  baseline.hidden = version !== "current";
  document.querySelector(".options").classList.toggle("is-current", version === "current");
  document.querySelectorAll('input[name="theme"],#pattern,#frame').forEach(input => {
    input.disabled = version === "current";
  });
  if (!baseline.hidden) {
    const url = `baseline.html?layout=${layout}&photo=${document.querySelector("#photo").value}&offset=${offset}&size=${Math.round(width)}x${Math.round(height)}`;
    if (baseline.getAttribute("src") !== url) baseline.src = url;
  } else if (baseline.hasAttribute("src")) {
    baseline.removeAttribute("src"); // retire the current renderer's timers when leaving comparison
  }
  const currentDescription = layout === "wall" ?
    "Current Mosaic: nine fixed tiles, 92% image boxes and whole-photo containment. The visible tile edges are still present in local source." :
    "Current Framed Print: photo limited to 60% of display width and 72% of height. The thin mat was fixed; the large dark surround remains.";
  const proposedDescription = layout === "wall" ?
    "Proposal: six larger whole photos, arranged by their proportions. More photos appear on the next page; a quiet background fills the remaining space." :
    "Proposal: the whole photo uses the available space above the clock. Sparse line drawings soften the remaining margins.";
  document.querySelector("#explanation").textContent = version === "current" ? currentDescription : proposedDescription;
  document.querySelector("#scene-caption").textContent = layout === "wall" ? "Whole photos · gentle edges" : "Room for the whole picture";
  const query = new URLSearchParams({ layout, version, theme, format, photo: document.querySelector("#photo").value });
  history.replaceState(null, "", "?" + query);
  snapshot = { layout, version, theme, width, height, pad, footer, edge, boxes };
}
document.querySelectorAll("input,select").forEach(input => input.addEventListener("change", render));
document.querySelector("#next").addEventListener("click", () => {
  if (choice("layout") === "single") {
    const select = document.querySelector("#photo");
    select.selectedIndex = (select.selectedIndex + 1) % select.options.length;
  } else offset = (offset + 3) % samples.length;
  render();
});
new ResizeObserver(render).observe(preview);
window.photoReview = { get snapshot() { return snapshot; } };
render();
