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
        HearthpaneTimers.NAME, HearthpaneRoutines.NAME, HearthpaneBriefing.NAME, HearthpaneCalendar.NAME, HearthpaneNews.NAME,
        HearthpaneSports.NAME, HearthpaneStocks.NAME, HearthpaneCall.NAME,
        com.immortal.launcher.memory.MemoryTool.NAME), names)
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
    requires(HearthpaneAlarms.NAME, "action", "time", "repeat", "label", "replace", "from", "all", "minutes")
    requires(HearthpaneTimers.NAME, "action", "duration_seconds", "label", "all")
    requires(HearthpaneRoutines.NAME, "action", "name", "steps")
    requires(HearthpaneBriefing.NAME, "show")
    requires(HearthpaneCall.NAME, "room", "video")
    requires(HearthpaneCalendar.NAME, "day", "date", "for")
    requires(HearthpaneSports.NAME, "team", "league")
    requires(HearthpaneStocks.NAME, "symbols")
    assertEquals("ARRAY", tool(HearthpaneStocks.NAME).getJSONObject("parameters").getJSONObject("properties")
        .getJSONObject("symbols").getString("type"))
    requires(HearthpaneNews.NAME, "count", "source")
    assertEquals("ARRAY", tool(HearthpaneLists.NAME).getJSONObject("parameters").getJSONObject("properties")
        .getJSONObject("items").getString("type"))
    assertEquals("BOOLEAN", tool(HearthpaneAlarms.NAME).getJSONObject("parameters").getJSONObject("properties")
        .getJSONObject("replace").getString("type"))
  }

  @Test fun householdToolsPromiseTheirSafetyRules() {
    assertTrue(tool(HearthpaneAlarms.NAME).getString("description").contains("not a countdown timer"))
    assertTrue(tool(HearthpaneSports.NAME).getString("description").contains("do not guess scores"))
    assertTrue(tool(HearthpaneStocks.NAME).getString("description").contains("never as advice"))
    assertTrue(tool(HearthpaneNews.NAME).getString("description").contains("hearthpane.sports"))
    assertTrue(tool(HearthpaneAlarms.NAME).getString("description").contains("needs_which"))
    assertTrue(tool(HearthpaneAlarms.NAME).getString("description").contains("action snooze"))
    assertTrue(tool(HearthpaneCall.NAME).getString("description").contains("never phone numbers"))
    assertTrue(tool(HearthpaneTimers.NAME).getString("description").contains("every room"))
    assertTrue(tool(HearthpaneRoutines.NAME).getString("description").contains("never done by voice"))
    assertTrue(tool(HearthpaneRoutines.NAME).getString("description").contains("needs_confirmation"))
    assertTrue(tool(HearthpaneRoutines.NAME).getString("description").contains("also_call"))
    assertTrue(tool(HearthpaneTools.HOME_CONTROL).getString("description").contains(HearthpaneRoutines.NAME))
    assertTrue(tool(HearthpaneTimers.NAME).getString("description").contains(HearthpaneAlarms.NAME))
    assertTrue(tool(HearthpaneAnnounce.NAME).getString("description").contains("quiet hours"))
    assertTrue(tool(HearthpaneLists.NAME).getString("description").contains("shopping list and the to-do list"))
    assertTrue(tool(HearthpaneBriefing.NAME).getString("description").contains("reads_aloud"))
    assertTrue(tool(HearthpaneBriefing.NAME).getString("description").contains(HearthpaneNews.NAME))
    assertTrue(tool(HearthpaneCalendar.NAME).getString("description").contains("its own clock"))
    assertTrue(tool(HearthpaneNews.NAME).getString("description").contains("search the web"))
  }
}
