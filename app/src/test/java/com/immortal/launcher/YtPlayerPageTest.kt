/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */
package com.immortal.launcher

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class YtPlayerPageTest {
  @Test fun baseUrl_isHttpsPackageName() {
    assertEquals("https://com.immortal.launcher", YtPlayerPage.baseUrl("com.immortal.launcher"))
    assertEquals("https://com.immortal.launcher.debug", YtPlayerPage.baseUrl("com.immortal.launcher.debug"))
  }

  @Test(expected = IllegalArgumentException::class)
  fun baseUrl_rejectsInjection() {
    YtPlayerPage.baseUrl("evil.com'/><script>")
  }

  @Test fun html_usesNoCookieHostOriginAndOfficialApi() {
    val html = YtPlayerPage.html("https://com.immortal.launcher")
    assertTrue(html.contains("host:'https://www.youtube-nocookie.com'"))
    assertTrue(html.contains("origin:'https://com.immortal.launcher'"))
    assertTrue(html.contains("https://www.youtube.com/iframe_api"))
    assertTrue(html.contains(YtPlayerPage.BRIDGE_NAME))
    assertTrue(html.contains("onAutoplayBlocked"))
    // Our own transport drives the player, so YouTube's duplicate controls are off via official
    // player parameters (not hidden by overlays); nothing is layered over the player.
    assertTrue(html.contains("controls:0"))
    assertTrue(html.contains("fs:0"))
    assertFalse(html.contains("controls:1"))
  }

  @Test fun commandScripts_validateVideoIdsAndNumbers() {
    assertEquals("window.__portalYt&&window.__portalYt.load('abcdefghijk',12.5);",
        YtPlayerPage.load("abcdefghijk", 12_500))
    assertEquals("window.__portalYt&&window.__portalYt.cue('abcdefghijk',0);",
        YtPlayerPage.cue("abcdefghijk", -3))
    assertNull(YtPlayerPage.load("x');alert(1);//", 0))
    assertEquals("window.__portalYt&&window.__portalYt.seek(61);", YtPlayerPage.seek(61_000))
    assertEquals("window.__portalYt&&window.__portalYt.volume(100);", YtPlayerPage.volume(250))
    assertEquals("window.__portalYt&&window.__portalYt.volume(0);", YtPlayerPage.volume(-1))
    assertEquals("window.__portalYt&&window.__portalYt.play();", YtPlayerPage.PLAY)
  }
}
