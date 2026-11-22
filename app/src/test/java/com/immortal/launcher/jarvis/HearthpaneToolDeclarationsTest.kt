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
    assertEquals(listOf(HearthpaneTools.SCREEN_CONTEXT, HearthpaneTools.HIGHLIGHT, HearthpaneTools.HOME_CONTROL,
        HearthpaneTools.REMINDERS, HearthpaneAnnounce.NAME, HearthpaneLists.NAME, HearthpaneAlarms.NAME,
        HearthpaneBriefing.NAME, HearthpaneCall.NAME), names)
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

  @Test fun remindersTakesAnActionAndPromisesPrivateItemsAreNeverReadOut() {
    val decls = declarations()
    val r = (0 until decls.length()).map { decls.getJSONObject(it) }.single { it.getString("name") == HearthpaneTools.REMINDERS }
    assertEquals("action", r.getJSONObject("parameters").getJSONArray("required").getString(0))
    val props = r.getJSONObject("parameters").getJSONObject("properties")
    for (key in listOf("action", "title", "for", "time", "day", "date", "private", "id")) assertTrue(key, props.has(key))
    assertEquals("BOOLEAN", props.getJSONObject("private").getString("type"))
    assertTrue(r.getString("description").contains("needs_person"))
    assertTrue(r.getString("description").contains("never read out"))
  }

  private fun tool(name: String) = declarations().let { d -> (0 until d.length()).map { d.getJSONObject(it) } }
      .single { it.getString("name") == name }

  private fun requires(name: String, vararg keys: String) {
    val params = tool(name).getJSONObject("parameters")
    val props = params.getJSONObject("properties")
    for (key in keys) assertTrue("$name $key", props.has(key))
    if (params.has("required")) assertTrue(props.has(params.getJSONArray("required").getString(0)))
  }

  @Test fun householdToolsDeclareTheArgumentsTheirHandlersRead() {
    requires(HearthpaneAnnounce.NAME, "message", "room")
    requires(HearthpaneLists.NAME, "action", "items", "list")
    requires(HearthpaneAlarms.NAME, "action", "time", "repeat", "label", "replace")
    requires(HearthpaneBriefing.NAME, "show")
    requires(HearthpaneCall.NAME, "room", "video")
    assertEquals("ARRAY", tool(HearthpaneLists.NAME).getJSONObject("parameters").getJSONObject("properties")
        .getJSONObject("items").getString("type"))
    assertEquals("BOOLEAN", tool(HearthpaneAlarms.NAME).getJSONObject("parameters").getJSONObject("properties")
        .getJSONObject("replace").getString("type"))
  }

  @Test fun householdToolsPromiseTheirSafetyRules() {
    assertTrue(tool(HearthpaneAlarms.NAME).getString("description").contains("not a countdown timer"))
    assertTrue(tool(HearthpaneAlarms.NAME).getString("description").contains("needs_replace"))
    assertTrue(tool(HearthpaneCall.NAME).getString("description").contains("never phone numbers"))
    assertTrue(tool(HearthpaneAnnounce.NAME).getString("description").contains("quiet hours"))
    assertTrue(tool(HearthpaneLists.NAME).getString("description").contains("shopping list and the to-do list"))
    assertTrue(tool(HearthpaneBriefing.NAME).getString("description").contains("reads_aloud"))
  }
}
