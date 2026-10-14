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
    assertTrue(WebAppProbe.isProbeUrl("http://127.0.0.1:17437/fixture/loading"))
    for (url in listOf(null, "http://127.0.0.1:17437/fixture/loading?url=https://private.invalid", "http://127.0.0.1:17437/fixture/private", "https://music.youtube.com", "file:///android_asset/webapp-retention-probe.html", "file:///android_asset/webapp-retention-probe.html?page=one&url=https://private.invalid")) {
      assertFalse(WebAppProbe.isProbeUrl(url))
    }
  }
  @Test fun commandsCannotCarryUrlsOrJavascript() {
    for (action in listOf("start-probe", "seed-probe", "second-probe", "start-loading-probe", "close-probe")) {
      assertEquals(action, WebAppProbe.command(JSONObject().put("action", action)))
      assertNull(WebAppProbe.command(JSONObject().put("action", action).put("url", "https://private.invalid")))
      assertNull(WebAppProbe.command(JSONObject().put("action", action).put("script", "alert(1)")))
    }
    assertNull(WebAppProbe.command(JSONObject()))
    assertNull(WebAppProbe.command(JSONObject().put("action", true)))
    assertNull(WebAppProbe.command(JSONObject().put("action", "launch")))
  }
}
