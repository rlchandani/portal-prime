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
}
