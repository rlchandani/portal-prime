/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */

package com.immortal.launcher

/** Compatible public entrypoint; all control-center behavior lives in bundled remote assets. */
object RemoteHtml {
  val PAGE =
      """
<!doctype html>
<html lang="en">
<head>
<meta charset="utf-8">
<meta name="viewport" content="width=device-width,initial-scale=1,viewport-fit=cover">
<meta name="theme-color" content="#14161B">
<meta name="color-scheme" content="light dark">
<meta name="referrer" content="no-referrer">
<meta name="apple-mobile-web-app-capable" content="yes">
<title>Calder Control Center</title>
<link rel="icon" type="image/png" sizes="64x64" href="/remote/app-icon?size=64">
<link rel="manifest" href="/remote/manifest.webmanifest">
<link rel="apple-touch-icon" href="/remote/app-icon?size=180">
<link rel="stylesheet" href="/remote/assets/control-center.css">
<script type="module" src="/remote/assets/control-center.js"></script>
</head>
<body>
<a class="skip-link" href="#page">Skip to controls</a>
<header class="topbar">
  <div class="topbar-inner">
    <div class="brand"><span class="brand-mark" aria-hidden="true">∞</span><span>Calder<span class="brand-detail">Control center</span></span></div>
    <div class="header-actions">
      <button id="room-button" class="room-button" aria-haspopup="dialog">Choose a Portal <span aria-hidden="true">⌄</span></button>
      <button id="refresh-button" class="icon-button" aria-label="Refresh current page">↻</button>
    </div>
  </div>
</header>
<div id="connection" class="connection" role="status"></div>
<main id="page" tabindex="-1" aria-busy="true"><div class="loading">Opening your control center…</div></main>
<nav id="navigation" class="navigation" aria-label="Control center"></nav>
<div id="notice" class="notice" role="status" aria-live="polite" hidden></div>
<dialog id="sheet" aria-labelledby="sheet-title"><div class="sheet-header"><h2 id="sheet-title"></h2><button id="sheet-close" class="icon-button" aria-label="Close dialog">×</button></div><div id="sheet-content"></div><div id="sheet-actions" class="sheet-actions"></div></dialog>
<noscript><div class="noscript">Enable JavaScript to pair and control your Portal. All browser assets are served locally by Calder.</div></noscript>
</body>
</html>
      """.trimIndent()
}
