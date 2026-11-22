/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */
package com.immortal.launcher.jarvis

import com.immortal.launcher.BriefingInputs
import com.immortal.launcher.BriefingPart
import com.immortal.launcher.BriefingPersonalInputs
import com.immortal.launcher.BriefingWeather
import com.immortal.launcher.CalendarFeed
import com.immortal.launcher.GuestContent
import java.util.Locale
import java.util.TimeZone
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** `hearthpane.briefing`: the household summary as text, or the card on screen; nothing personal or guest-hidden. */
class HearthpaneBriefingTest {
  private val hour = 3_600_000L
  /** Tuesday 17 November 2026, 07:05 UTC. */
  private val now = 1_794_899_100_000L
  private var inputs = BriefingInputs(nowMs = now, zone = TimeZone.getTimeZone("UTC"), locale = Locale.US, use24Hour = true,
      greeting = "Good morning", name = "Sam", weather = BriefingPart.Ready(BriefingWeather(12, 2, 15, 6, 20)),
      events = BriefingPart.Ready(listOf(CalendarFeed.Event(now + hour, now + 2 * hour, "Dentist", false))),
      listItems = listOf("Milk"), speakOn = true)
  private var hidden = emptySet<GuestContent>()
  private var opened = 0
  private var aloud = false
  private val tool = HearthpaneBriefing({ inputs }, { opened++ }, { it in hidden }, { aloud })

  private fun call(vararg kv: Pair<String, Any>) = tool.invoke(JSONObject().apply { kv.forEach { (k, v) -> put(k, v) } }.toString())

  @Test fun readGivesTheHouseholdSummaryText() {
    val r = call()
    assertTrue(r.getBoolean("ok"))
    val text = r.getString("briefing")
    assertTrue(text, text.startsWith("Good morning, Sam. It's 7:05."))
    assertTrue(text, text.contains("Dentist"))
    assertTrue(text, text.contains("Milk"))
    assertFalse(r.has("note"))
    assertEquals(0, opened)
  }

  @Test fun guestModeKeepsCalendarAndListOut() {
    hidden = setOf(GuestContent.CALENDAR, GuestContent.HOUSEHOLD_LIST)
    val text = call().getString("briefing")
    assertFalse(text, text.contains("Dentist"))
    assertFalse(text, text.contains("Milk"))
  }

  @Test fun personalInputsNeverReachTheModel() {
    inputs = inputs.copy(personal = BriefingPersonalInputs("Maya", hidePrivate = false, reminders = listOf("Secret gift")))
    val text = call().getString("briefing")
    assertTrue(text, text.startsWith("Good morning, Sam."))
    assertFalse(text, text.contains("Secret gift"))
  }

  @Test fun missingWeatherIsNoted() {
    inputs = inputs.copy(weather = BriefingPart.Loading)
    assertTrue(call().getString("note").contains("weather"))
    inputs = inputs.copy(weather = BriefingPart.Ready(null))
    assertTrue(call().has("note"))
  }

  @Test fun showOpensTheCardAndSaysWhetherItSpeaks() {
    val quiet = call("show" to true)
    assertEquals(1, opened)
    assertFalse(quiet.getBoolean("reads_aloud"))
    aloud = true
    val loud = call("show" to true)
    assertTrue(loud.getBoolean("reads_aloud"))
    assertTrue(loud.getString("message").contains("say nothing more than OK"))
    assertTrue(tool.invoke("nope").has("error"))
  }
}
