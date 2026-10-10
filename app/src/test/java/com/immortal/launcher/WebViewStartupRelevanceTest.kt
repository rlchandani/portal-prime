/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */
package com.immortal.launcher

import org.junit.Assert.*
import org.junit.Test

class WebViewStartupRelevanceTest {
  @Test fun `native slideshow modes do not opt into engine preparation`() {
    for (mode in listOf(ScreensaverConfig.MODE_SMART, ScreensaverConfig.MODE_PORTRAITS,
        ScreensaverConfig.MODE_WHOLE, ScreensaverConfig.MODE_CLASSIC)) {
      assertFalse(mode, webViewStartupRelevant(ScreensaverConfig.Settings(mode = mode)))
    }
  }

  @Test fun `existing legacy engine modes are relevant`() {
    for (mode in ScreensaverConfig.MODES.filter { ScreensaverConfig.usesEngine(it) }) {
      assertTrue(mode, webViewStartupRelevant(ScreensaverConfig.Settings(mode = mode)))
    }
  }

  @Test fun `configured webpage is relevant even when the stored slideshow mode is native`() {
    assertTrue(webViewStartupRelevant(ScreensaverConfig.Settings(
        source = ScreensaverConfig.SOURCE_WEBURL, webUrl = "https://fixture.invalid/frame",
        mode = ScreensaverConfig.MODE_SMART)))
  }

  @Test fun `unconfigured webpage does not turn native slideshow into a WebView consumer`() {
    for (url in listOf(null, "", "  ")) {
      assertFalse(webViewStartupRelevant(ScreensaverConfig.Settings(
          source = ScreensaverConfig.SOURCE_WEBURL, webUrl = url,
          mode = ScreensaverConfig.MODE_SMART)))
    }
  }

  @Test fun `disabled screensaver avoids preparation for either engine or webpage`() {
    assertFalse(webViewStartupRelevant(ScreensaverConfig.Settings(
        enabled = false, mode = ScreensaverConfig.MODE_SHUFFLE)))
    assertFalse(webViewStartupRelevant(ScreensaverConfig.Settings(
        enabled = false, source = ScreensaverConfig.SOURCE_WEBURL,
        webUrl = "https://fixture.invalid/frame")))
  }
}
