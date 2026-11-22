/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */
// Pre-rasterise screensaver/photo-doodles-dark.svg and photo-doodles.svg into the screensaver doodle
// tile PNGs (480 x 360 CSS px at 1x, 1.125x = the Portal+ device scale, and 2x). Chromium rasterises
// each vector exactly as the screensaver WebView would, on a transparent background.
// Usage: NODE_PATH=<dir containing playwright> node scripts/generate_doodle_tiles.mjs
import { createRequire } from 'node:module';
import { readFileSync, writeFileSync } from 'node:fs';
import { fileURLToPath } from 'node:url';

const require = createRequire(import.meta.url);
const { chromium } = require('playwright');
const dir = fileURLToPath(new URL('../app/src/main/assets/screensaver/', import.meta.url));
const THEMES = ['photo-doodles-dark', 'photo-doodles'];
const SCALES = [1, 1.125, 2];

const browser = await chromium.launch({ headless: true });
try {
  for (const theme of THEMES) {
    const svg = readFileSync(`${dir}${theme}.svg`, 'utf8');
    for (const scale of SCALES) {
      const page = await browser.newPage({ viewport: { width: 480, height: 360 }, deviceScaleFactor: scale });
      await page.setContent(`<html><body style="margin:0;background:transparent">${svg}</body></html>`);
      const png = await page.locator('svg').screenshot({ omitBackground: true });
      writeFileSync(`${dir}${theme}-${480 * scale}.png`, png);
      await page.close();
    }
  }
} finally {
  await browser.close();
}
