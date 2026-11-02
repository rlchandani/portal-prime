/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */

// Explicit review-only QA. Not imported by the gallery's normal entry point.
export function auditAccessibility({ themes = ["dark", "light"], scales = ["1", "1.25", "1.5"] } = {}) {
  const studio = window.widgetStudio;
  if (!studio) throw new Error("Widget studio has not loaded");
  const previous = studio.selection;
  const canvas = document.createElement("canvas");
  canvas.width = canvas.height = 1;
  const ctx = canvas.getContext("2d", { willReadFrequently: true });
  const colors = new Map();
  const rgba = (css) => {
    if (colors.has(css)) return colors.get(css);
    ctx.clearRect(0, 0, 1, 1);
    ctx.fillStyle = css;
    ctx.fillRect(0, 0, 1, 1);
    const pixel = [...ctx.getImageData(0, 0, 1, 1).data];
    const result = [pixel[0] / 255, pixel[1] / 255, pixel[2] / 255, pixel[3] / 255];
    colors.set(css, result);
    return result;
  };
  const over = (fg, bg) => [
    ...fg.slice(0, 3).map((channel, index) => channel * fg[3] + bg[index] * (1 - fg[3])),
    1,
  ];
  const luminance = (color) => {
    const linear = color.slice(0, 3).map(c => c <= 0.04045 ? c / 12.92 : ((c + 0.055) / 1.055) ** 2.4);
    return linear[0] * 0.2126 + linear[1] * 0.7152 + linear[2] * 0.0722;
  };
  const contrast = (a, b) => {
    const x = luminance(a), y = luminance(b);
    return (Math.max(x, y) + 0.05) / (Math.min(x, y) + 0.05);
  };
  if (Math.abs(contrast(rgba("#fff"), rgba("#000")) - 21) > 0.001) {
    throw new Error("Contrast calculation self-check failed");
  }
  const failures = [];
  const unsupported = new Map();
  let selections = 0, textChecks = 0, controlChecks = 0, inactiveSkipped = 0;
  const inspected = new Set();
  let current;
  const fail = (kind, element, detail) => failures.push({
    ...current, kind, element: element.tagName.toLowerCase(),
    text: element.textContent.trim().slice(0, 100), ...detail,
  });
  const background = (element) => {
    const chain = [];
    for (let node = element; node; node = node.parentElement) chain.unshift(node);
    let result = [1, 1, 1, 1];
    for (const node of chain) {
      const style = getComputedStyle(node);
      if (style.backgroundImage !== "none" || Number(style.opacity) !== 1) {
        const reason = style.backgroundImage !== "none" ? "background image" : "group opacity";
        unsupported.set(`${current.widget}/${current.state}/${node.className}/${reason}`, reason);
        return null;
      }
      result = over(rgba(style.backgroundColor), result);
    }
    return result;
  };
  const visible = (el) => {
    const style = getComputedStyle(el);
    return el.getClientRects().length && style.visibility === "visible" && style.display !== "none";
  };
  try {
    for (const widget of studio.catalog) {
      for (const state of widget.states) {
        inspected.add(`${widget.id}/${state.id}`);
        for (const size of widget.sizes) for (const theme of themes) for (const text of scales) {
          current = { widget: widget.id, state: state.id, size, theme, text };
          studio.select({ ...current, view: "compare" });
          if (studio.selection.widget !== widget.id || studio.selection.state !== state.id) {
            throw new Error(`Selection did not render ${widget.id}/${state.id}`);
          }
          selections++;
          const widgets = [...document.querySelectorAll(".previews .widget")];
          if (widgets.length !== 3) throw new Error(`Missing platform preview: ${JSON.stringify(current)}`);
          for (const preview of widgets) {
            current.platform = ["android", "ios", "portal"].find(p => preview.classList.contains(p));
            for (const el of preview.querySelectorAll("*")) {
              if (!visible(el) || el.closest("svg")) continue;
              const directText = [...el.childNodes].some(n => n.nodeType === Node.TEXT_NODE && n.textContent.trim());
              if (!directText) continue;
              if (el.closest("button:disabled,[aria-disabled=true]")) { inactiveSkipped++; continue; }
              const bg = background(el);
              if (!bg) continue;
              const style = getComputedStyle(el);
              const fontSize = parseFloat(style.fontSize), weight = Number(style.fontWeight);
              const minimum = fontSize >= 24 || (fontSize >= 18.6667 && weight >= 700) ? 3 : 4.5;
              const ratio = contrast(over(rgba(style.color), bg), bg);
              textChecks++;
              if (ratio + 0.01 < minimum) fail("contrast", el, {
                ratio: Number(ratio.toFixed(3)), minimum, color: style.color,
                background: bg.slice(0, 3).map(c => Math.round(c * 255)), fontSize, weight,
              });
            }
            for (const el of preview.querySelectorAll("button,[role=button],input,select")) {
              if (!visible(el) || el.disabled) continue;
              const box = el.getBoundingClientRect();
              controlChecks++;
              if (box.width < 48 || box.height < 48) fail("target", el, { width: box.width, height: box.height });
              const name = el.getAttribute("aria-label") || el.textContent.trim();
              if (!name) fail("control-name", el, {});
            }
          }
        }
      }
    }
  } finally {
    studio.select(previous);
  }
  return {
    selections, widgets: studio.catalog.length, states: inspected.size,
    textChecks, controlChecks, inactiveSkipped, failures,
    unsupported: [...unsupported.entries()].map(([context, reason]) => ({ context, reason })),
    themes, scales,
    scope: "Rendered widget text, enabled control names and 48px targets in all three preview platforms.",
    limitations: "Browser review only. Does not audit native controls, screen readers, focus order, placeholders, icons, illustrations or full WCAG conformance. Background images and group opacity are recorded as unsupported rather than counted as passing.",
  };
}
