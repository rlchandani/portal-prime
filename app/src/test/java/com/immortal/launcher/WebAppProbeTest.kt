/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */
package com.immortal.launcher

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class WebAppProbeTest {
  @Test fun onlyFixedGeneratedDocumentsAreProbePages() {
    assertTrue(WebAppProbe.isProbeUrl("file:///android_asset/webapp-retention-probe.html?page=one"))
    assertTrue(WebAppProbe.isProbeUrl("file:///android_asset/webapp-retention-probe.html?page=two"))
    for (url in listOf(null, "https://music.youtube.com", "file:///android_asset/webapp-retention-probe.html", "file:///android_asset/webapp-retention-probe.html?page=one&url=https://private.invalid")) {
      assertFalse(WebAppProbe.isProbeUrl(url))
    }
  }
  @Test fun commandsCannotCarryUrlsOrJavascript() {
    for (action in listOf("start-probe", "seed-probe", "second-probe", "close-probe")) {
      assertEquals(action, WebAppProbe.command(JSONObject().put("action", action)))
      assertNull(WebAppProbe.command(JSONObject().put("action", action).put("url", "https://private.invalid")))
      assertNull(WebAppProbe.command(JSONObject().put("action", action).put("script", "alert(1)")))
    }
    assertNull(WebAppProbe.command(JSONObject()))
    assertNull(WebAppProbe.command(JSONObject().put("action", true)))
    assertNull(WebAppProbe.command(JSONObject().put("action", "launch")))
  }
}
