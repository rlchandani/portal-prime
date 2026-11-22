/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */
package com.immortal.launcher.jarvis

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Hearthpane's two Jarvis tools: untrusted args, and highlights only on the screen in front. */
class HearthpaneToolsTest {
  private var front: HearthpaneScreens.Entry? = null
  private val pointed = mutableListOf<Pair<String, String?>>()
  private val tools = HearthpaneTools({ front }, { target, hint -> pointed += target to hint })

  private fun screen(id: String) = HearthpaneScreens.Entry({ id }) { JSONObject().put("screen", id) }

  @Test fun screenContextDescribesTheScreenInFront() {
    front = screen("settings")
    assertEquals("settings", tools.invoke(HearthpaneTools.SCREEN_CONTEXT, null).getString("screen"))
  }

  @Test fun withNoHearthpaneScreenItSaysSo() {
    val out = tools.invoke(HearthpaneTools.SCREEN_CONTEXT, "{}")
    assertTrue(out.isNull("screen"))
    assertTrue(out.getString("note").contains("can't be read"))
  }

  @Test fun aFailingBuilderIsAnErrorNotACrash() {
    front = HearthpaneScreens.Entry({ "music" }) { throw IllegalStateException("boom") }
    assertTrue(tools.invoke(HearthpaneTools.SCREEN_CONTEXT, null).has("error"))
  }

  @Test fun highlightPointsAtAControlOfTheScreenInFront() {
    front = screen(HearthpaneScreens.SETTINGS)
    val out = tools.invoke(HearthpaneTools.HIGHLIGHT, """{"target":"settings:immortal.settingsTheme","hint":"Tap here"}""")
    assertEquals(true, out.getBoolean("ok"))
    assertEquals(listOf("settings:immortal.settingsTheme" to "Tap here"), pointed)
  }

  @Test fun highlightPointsAtMusicAndCallsControls() {
    front = screen(HearthpaneScreens.MUSIC)
    assertEquals(true, tools.invoke(HearthpaneTools.HIGHLIGHT, """{"target":"music.controls"}""").getBoolean("ok"))
    assertTrue(tools.invoke(HearthpaneTools.HIGHLIGHT, """{"target":"calls.video.p1"}""").has("error"))
    front = screen(HearthpaneScreens.CALLS)
    assertEquals(true, tools.invoke(HearthpaneTools.HIGHLIGHT, """{"target":"calls.video.p1"}""").getBoolean("ok"))
    assertTrue(tools.invoke(HearthpaneTools.HIGHLIGHT, """{"target":"music.queue"}""").has("error"))
    assertEquals(listOf("music.controls", "calls.video.p1"), pointed.map { it.first })
  }

  @Test fun anUnknownMusicTargetIsRefused() {
    front = screen(HearthpaneScreens.MUSIC)
    assertTrue(tools.invoke(HearthpaneTools.HIGHLIGHT, """{"target":"music.volume"}""").has("error"))
  }

  @Test fun highlightRefusesAControlOfAnotherScreen() {
    front = screen(HearthpaneScreens.SETTINGS)
    assertTrue(tools.invoke(HearthpaneTools.HIGHLIGHT, """{"target":"cc.room.kitchen"}""").has("error"))
    assertTrue(pointed.isEmpty())
  }

  @Test fun highlightRefusesMissingBadOrUnknownTargets() {
    front = screen(HearthpaneScreens.CONTROL_CENTER)
    val long = "cc.room." + "x".repeat(200)
    listOf("{}", "not json", """{"target":"   "}""", """{"target":"photos.album"}""", """{"target":"$long"}""").forEach {
      assertTrue(it, tools.invoke(HearthpaneTools.HIGHLIGHT, it).has("error"))
    }
    assertTrue(pointed.isEmpty())
  }

  @Test fun aLongHintIsCut() {
    front = screen(HearthpaneScreens.CONTROL_CENTER)
    val hint = "h".repeat(200)
    tools.invoke(HearthpaneTools.HIGHLIGHT, """{"target":"cc.entity.light.kitchen","hint":"$hint"}""")
    assertEquals(60, pointed.single().second?.length)
  }

  @Test fun anUnknownToolIsAnError() {
    assertTrue(tools.invoke("hearthpane.take_photo", "{}").has("error"))
    assertTrue(tools.invoke(null, null).has("error"))
  }

  @Test fun onlyJarvisMayCall() {
    assertTrue(HearthpaneToolProvider.allowedCaller(listOf("com.portal.assistant")))
    assertFalse(HearthpaneToolProvider.allowedCaller(listOf("com.portal.assistant", "com.evil.shared")))
    assertFalse(HearthpaneToolProvider.allowedCaller(listOf("com.example.other")))
    assertFalse(HearthpaneToolProvider.allowedCaller(emptyList()))
  }

  @Test fun aQueryAddsSettingsMatchesFromAnywhere() {
    val searching = HearthpaneTools({ screen("home") }, { _, _ -> }) { q ->
      if (q == "dark mode") listOf(ScreenContext.Match("immortal.settingsTheme", "Appearance", "Display & Brightness"))
      else emptyList()
    }
    val out = searching.invoke(HearthpaneTools.SCREEN_CONTEXT, """{"query":"dark mode"}""")
    val match = out.getJSONArray("settings_matches").getJSONObject(0)
    assertEquals("settings:immortal.settingsTheme", match.getString("target"))
    assertEquals("Display & Brightness", match.getString("where"))
    assertEquals("home", out.getString("screen"))
  }

  @Test fun noQueryMeansNoMatches() {
    front = screen("home")
    assertFalse(tools.invoke(HearthpaneTools.SCREEN_CONTEXT, "{}").has("settings_matches"))
  }

  @Test fun homeControlHandsTheRawArgumentsToTheControlCenter() {
    val seen = mutableListOf<String?>()
    val t = HearthpaneTools({ front }, { _, _ -> }, homeControl = { a -> seen += a; JSONObject().put("ok", true).put("result", "done") })
    assertEquals("done", t.invoke(HearthpaneTools.HOME_CONTROL, "{\"request\":\"turn off the kitchen lights\"}").getString("result"))
    assertEquals(listOf("{\"request\":\"turn off the kitchen lights\"}"), seen)
  }

  @Test fun aFailingHomeControlIsAnErrorNotACrash() {
    val t = HearthpaneTools({ front }, { _, _ -> }, homeControl = { throw IllegalStateException("boom") })
    assertTrue(t.invoke(HearthpaneTools.HOME_CONTROL, "{}").has("error"))
    // Without the Control Center seam (the default) it says so.
    assertTrue(tools.invoke(HearthpaneTools.HOME_CONTROL, "{}").has("error"))
  }

  @Test fun householdToolsAreRoutedByNameWithTheirRawArgumentsAndFailuresAreErrors() {
    val seen = mutableListOf<Pair<String, String?>>()
    val t = HearthpaneTools({ front }, { _, _ -> }, household = mapOf(
        HearthpaneLists.NAME to { a -> seen += HearthpaneLists.NAME to a; JSONObject().put("ok", true) },
        HearthpaneAlarms.NAME to { _ -> throw IllegalStateException("boom") }))
    assertTrue(t.invoke(HearthpaneLists.NAME, """{"action":"list"}""").getBoolean("ok"))
    assertEquals(listOf(HearthpaneLists.NAME to """{"action":"list"}"""), seen)
    assertEquals("hearthpane.alarms failed", t.invoke(HearthpaneAlarms.NAME, "{}").getString("error"))
    assertTrue(t.invoke(HearthpaneCall.NAME, "{}").getString("error").startsWith("unknown tool"))
  }
}
