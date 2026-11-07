/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */
package com.immortal.launcher

import org.junit.Assert.*
import org.junit.Test
import java.util.TimeZone

/** Call everyone: messages, the receiving decision, quiet hours and copy. */
class DinnerCallModelTest {
  @Test fun sixPresetsInTheApprovedOrder() {
    assertEquals(listOf("Dinner’s ready", "Come downstairs", "Time to leave", "Bedtime",
        "Lights out in 5 minutes", "Home in 10 minutes"), DinnerPresets.all.map { it.text })
    assertEquals("Home in 10 minutes", DinnerMessage.preset("home10")!!.text)
    assertNull(DinnerMessage.preset("nope"))
    assertEquals("Ping", DinnerMessage.ping().text)
  }

  @Test fun typedMessagesAreCleanedAndCappedAtFortyCharacters() {
    assertEquals("Pizza’s here", DinnerMessage.custom("  Pizza’s\nhere ")!!.text)
    assertEquals("a b", DinnerMessage.custom("a\u0000\u202E b")!!.text)
    assertNull(DinnerMessage.custom("   "))
    assertNull(DinnerMessage.custom("x".repeat(41)))
    assertNotNull(DinnerMessage.custom("x".repeat(40)))
    // Code points, not UTF-16 units: forty emoji still fit.
    assertNotNull(DinnerMessage.custom("🍕".repeat(40)))
    assertEquals(DinnerKind.CUSTOM, DinnerMessage.custom("Hi")!!.kind)
  }

  private fun policy(receive: Boolean = true, speak: Boolean = true, quiet: Boolean = false, tts: Boolean = true) =
      DinnerReceivePolicy(receive, speak, quiet, tts)

  @Test fun theReceivingRoomDecidesAndQuietHoursStillShowTheCard() {
    val dinner = DinnerKind.PRESET
    assertEquals(DinnerDelivery(DinnerStatus.SPOKEN, showCard = true, chime = true, speak = true), DinnerDecision.decide(dinner, policy()))
    assertEquals(DinnerDelivery(DinnerStatus.QUIET, showCard = true, chime = false, speak = false), DinnerDecision.decide(dinner, policy(quiet = true)))
    assertEquals(DinnerStatus.BLOCKED, DinnerDecision.decide(dinner, policy(receive = false)).status)
    assertFalse(DinnerDecision.decide(dinner, policy(receive = false)).showCard)
    assertEquals(DinnerStatus.CHIMED, DinnerDecision.decide(DinnerKind.PING, policy()).status)
    assertFalse(DinnerDecision.decide(DinnerKind.PING, policy()).speak)
    val noVoice = DinnerDecision.decide(dinner, policy(tts = false))
    assertEquals(DinnerStatus.CHIMED_ONLY, noVoice.status)
    assertTrue(noVoice.chime)
    assertFalse(noVoice.speak)
    assertEquals(DinnerStatus.CHIMED_ONLY, DinnerDecision.decide(dinner, policy(speak = false)).status)
  }

  @Test fun quietHoursUseTheChimeWindowAndAnInjectedClock() {
    val utc = TimeZone.getTimeZone("UTC")
    val day = 1_800_000_000_000L - (1_800_000_000_000L % 86_400_000L)
    fun at(h: Int, m: Int) = day + (h * 60 + m) * 60_000L
    val s = ChimeConfig.Settings(quietHoursOn = true, quietStartMin = 21 * 60, quietEndMin = 7 * 60)
    assertTrue(DinnerQuiet.isQuiet(s, at(21, 20), utc))
    assertTrue(DinnerQuiet.isQuiet(s, at(6, 59), utc))
    assertFalse(DinnerQuiet.isQuiet(s, at(18, 28), utc))
    assertFalse(DinnerQuiet.isQuiet(s.copy(quietHoursOn = false), at(21, 20), utc))
  }

  @Test fun cardCopyMatchesThePrototype() {
    val dinner = DinnerMessage.preset("dinner")!!
    assertEquals("From the Family Area at 6:28 PM", DinnerCopy.fromLine(dinner, "Family Area", false, "6:28 PM"))
    assertEquals("Typed by Rohit’s Pixel at 6:28 PM", DinnerCopy.fromLine(DinnerMessage.custom("Hi")!!, "Rohit’s Pixel", true, "6:28 PM"))
    assertEquals("Your reply goes to the Family Area", DinnerCopy.replyLine("Family Area", false))
    assertEquals("Quiet hours until 7:00 AM, so no chime or voice",
        DinnerCopy.cardStatus(DinnerDecision.decide(DinnerKind.PRESET, policy(quiet = true)), DinnerKind.PRESET, policy(quiet = true), "7:00 AM"))
    assertEquals("Chimed and said it once",
        DinnerCopy.cardStatus(DinnerDecision.decide(DinnerKind.PRESET, policy()), DinnerKind.PRESET, policy(), "7:00 AM"))
    assertEquals("Chimed. Couldn’t speak it this time",
        DinnerCopy.cardStatus(DinnerDecision.decide(DinnerKind.PRESET, policy(tts = false)), DinnerKind.PRESET, policy(tts = false), ""))
    assertEquals("Chimed. Voice is off on this Portal",
        DinnerCopy.cardStatus(DinnerDecision.decide(DinnerKind.PRESET, policy(speak = false)), DinnerKind.PRESET, policy(speak = false), ""))
    assertEquals("Chimed once", DinnerCopy.cardStatus(DinnerDecision.decide(DinnerKind.PING, policy()), DinnerKind.PING, policy(), ""))
    assertEquals("7:00 AM", DinnerCopy.minuteLabel(7 * 60, use24 = false))
    assertEquals("21:00", DinnerCopy.minuteLabel(21 * 60, use24 = true))
    assertEquals("12:05 AM", DinnerCopy.minuteLabel(5, use24 = false))
  }

  @Test fun rowCopyAndRetryRules() {
    assertEquals("Chimed and said it", DinnerStatus.SPOKEN.rowText)
    assertEquals("Shown quietly, quiet hours", DinnerStatus.QUIET.rowText)
    assertEquals("No answer, not delivered", DinnerStatus.NO_ANSWER.rowText)
    assertEquals("Calls are turned off there", DinnerStatus.BLOCKED.rowText)
    assertTrue(DinnerStatus.NO_ANSWER.retryable)
    assertFalse(DinnerStatus.QUIET.retryable)
    assertEquals(DinnerStatus.SPOKEN, DinnerStatus.fromWire("spoken"))
    assertNull(DinnerStatus.fromWire("sending")) // peers never report "sending"
    assertNull(DinnerStatus.fromWire("bogus"))
  }

  @Test fun sheetLineSaysWhoWillHearIt() {
    assertEquals("Living Area chimes and says it once", DinnerCopy.toLine(listOf("Living Area"), emptyList(), offline = false))
    assertEquals("Living Area is in quiet hours, so it shows without sound",
        DinnerCopy.toLine(listOf("Living Area"), listOf("Living Area"), offline = false))
    assertEquals("This Portal is offline. Reconnect to send.", DinnerCopy.toLine(listOf("Living Area"), emptyList(), offline = true))
    assertEquals("Den and Living Area chime and say it once", DinnerCopy.toLine(listOf("Den", "Living Area"), emptyList(), offline = false))
  }
}
