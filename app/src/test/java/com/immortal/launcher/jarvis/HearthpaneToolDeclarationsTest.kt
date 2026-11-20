/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */
package com.immortal.launcher.jarvis

import org.json.JSONArray
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * What Hearthpane advertises to Jarvis (the provider's `com.portal.assistant.tools` meta-data): valid JSON after
 * Android's string unescaping, every tool Hearthpane answers, each with a description and a parameters object
 * (Jarvis drops a declaration without them), and home_control requiring the user's words.
 */
class HearthpaneToolDeclarationsTest {
  private fun declarations(): JSONArray {
    val xml = File("src/main/res/values/jarvis_tools.xml").readText()
    val raw = Regex("""<string name="jarvis_tools" translatable="false">(.*)</string>""").find(xml)!!.groupValues[1]
    return JSONArray(raw.replace("\\\"", "\"").replace("\\'", "'"))
  }

  @Test fun everyToolHearthpaneAnswersIsDeclaredWithItsShape() {
    val decls = declarations()
    val names = (0 until decls.length()).map { decls.getJSONObject(it).getString("name") }
    assertEquals(listOf(HearthpaneTools.SCREEN_CONTEXT, HearthpaneTools.HIGHLIGHT, HearthpaneTools.HOME_CONTROL), names)
    for (i in 0 until decls.length()) {
      val d = decls.getJSONObject(i)
      assertTrue(d.getString("description").isNotBlank())
      assertEquals("OBJECT", d.getJSONObject("parameters").getString("type"))
    }
  }

  @Test fun homeControlTakesTheUsersWordsAndSaysConfirmationsStayOnScreen() {
    val decls = declarations()
    val home = (0 until decls.length()).map { decls.getJSONObject(it) }.single { it.getString("name") == HearthpaneTools.HOME_CONTROL }
    assertEquals("request", home.getJSONObject("parameters").getJSONArray("required").getString(0))
    assertTrue(home.getString("description").contains("needs_confirmation"))
    // No percent sign: aapt would read it as a format string.
    assertTrue(!File("src/main/res/values/jarvis_tools.xml").readText().contains('%'))
  }
}
