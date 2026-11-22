/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */
// Pre-rasterise the Polaroid print shadow (box-shadow: 0 30px 64px -16px rgba(0,0,0,.75)) into
// 9-slice PNGs polaroid-shadow-{480,540,960}.png (480 x 480 CSS px at 1x, 1.125x = the Portal+
// device scale, and 2x). A tilted print's blurred box-shadow misses Skia's cached nine-patch path
// and is re-blurred in full on every raster; a stretched bitmap is a plain image draw.
//
// Geometry (CSS px, relative to a print): the shadow reaches 80 px beyond the left, right and top
// edges less the 30 px drop (so 50 px above), and 110 px below. The reference print is 320 x 320,
// drawn at (80, 50) in a 480 x 480 tile, so each 192 px slice (40 %) holds the whole blur ramp and
// the 96 px middle is the flat interior (hidden under the paper, so styles.css does not fill it).
// The tile holds full-strength coverage (rgba(0,0,0,1)); styles.css applies the 0.75 once as the
// layer's opacity, which tracks the live shadow's colour-alpha rounding far closer than a 0.75 tile.
// The shadow is cast from a print 10000 px off-tile so the capture also holds the part a real print
// hides (box-shadow never paints under its box).
// Usage: NODE_PATH=<dir containing playwright> node scripts/generate_polaroid_shadow.mjs
import { createRequire } from 'node:module';
import { writeFileSync } from 'node:fs';
import { fileURLToPath } from 'node:url';

const require = createRequire(import.meta.url);
const { chromium } = require('playwright');
const dir = fileURLToPath(new URL('../app/src/main/assets/screensaver/', import.meta.url));
const TILE = 480, PRINT = 320, LEFT = 80, TOP = 50, AWAY = 10000;
const SCALES = [1, 1.125, 2];
const SHADOW = `${AWAY}px 30px 64px -16px rgba(0,0,0,1)`;

// CHROMIUM_PATH optionally selects an installed Chromium when the Playwright default is absent.
const browser = await chromium.launch({ headless: true, executablePath: process.env.CHROMIUM_PATH || undefined });
try {
  for (const scale of SCALES) {
    const page = await browser.newPage({ viewport: { width: TILE, height: TILE }, deviceScaleFactor: scale });
    await page.setContent('<html><body style="margin:0;background:transparent">' +
      `<div style="position:absolute;left:${LEFT - AWAY}px;top:${TOP}px;width:${PRINT}px;height:${PRINT}px;` +
      `border-radius:4px;box-shadow:${SHADOW}"></div></body></html>`);
    const png = await page.screenshot({ omitBackground: true, clip: { x: 0, y: 0, width: TILE, height: TILE } });
    writeFileSync(`${dir}polaroid-shadow-${TILE * scale}.png`, png);
    await page.close();
  }
} finally {
  await browser.close();
}
