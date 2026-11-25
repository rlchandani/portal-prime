/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */
package com.immortal.launcher

import com.immortal.launcher.jarvis.AlarmDays
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The Tools › Alarm "Add alarm" sheet's pure draft logic ([OtherAlarmDraft.config], [OtherAlarmDraft.toggled]) and its
 * row subtitle ([otherAlarmSubtitle]). Only a valid draft becomes a saved alarm; day toggles never mutate the draft.
 */
class OtherAlarmDraftTest {

  @Test fun validDraftBecomesAnEnabledConfig() {
    val config = OtherAlarmDraft(hour = "7", minute = "5", days = AlarmDays.WEEKDAYS, label = "Gym").config()!!
    assertTrue(config.enabled)
    assertEquals(7, config.hour)
    assertEquals(5, config.minute)
    assertEquals("Gym", config.label)
    assertEquals(AlarmDays.WEEKDAYS, config.days)
  }

  @Test fun outOfRangeOrNonNumericTimeKeepsTheDraftOpen() {
    assertNull("hour past 23 is not a clock time", OtherAlarmDraft(hour = "24", minute = "0").config())
    assertNull("minute past 59 is not a clock time", OtherAlarmDraft(hour = "7", minute = "60").config())
    assertNull("an empty field is still being typed", OtherAlarmDraft(hour = "", minute = "0").config())
    assertNull("letters are not a number", OtherAlarmDraft(hour = "7a", minute = "0").config())
    assertNull("a signed field never reaches save", OtherAlarmDraft(hour = "-1", minute = "0").config())
  }

  @Test fun midnightIsValid() {
    val config = OtherAlarmDraft(hour = "0", minute = "0").config()!!
    assertEquals(0, config.hour)
    assertEquals(0, config.minute)
  }

  @Test fun blankLabelDefaultsAndLongLabelIsCapped() {
    assertEquals("Alarm", OtherAlarmDraft(hour = "7", minute = "0", label = "   ").config()!!.label)
    val long = OtherAlarmDraft(hour = "7", minute = "0", label = "x".repeat(60)).config()!!
    assertEquals(40, long.label.length)
  }

  @Test fun togglingADayReturnsANewSetWithoutMutatingTheDraft() {
    val draft = OtherAlarmDraft(days = setOf(2))
    val added = draft.toggled(5)
    assertEquals(setOf(2, 5), added.days)
    assertEquals("original draft is unchanged", setOf(2), draft.days)
    assertEquals("toggling the same day again removes it", setOf(2), added.toggled(5).days)
  }

  @Test fun subtitleReadsRepeatThenLabelWhenItIsNotTheDefault() {
    fun subtitle(days: Set<Int>, label: String) =
        otherAlarmSubtitle(AlarmConfig.Config(enabled = true, hour = 7, minute = 0, label = label, days = days))
    assertEquals("Once", subtitle(emptySet(), "Alarm"))
    assertEquals("On weekdays", subtitle(AlarmDays.WEEKDAYS, "Alarm"))
    assertEquals("Every day", subtitle(AlarmDays.EVERY_DAY, ""))
    assertEquals("On weekdays · Gym", subtitle(AlarmDays.WEEKDAYS, "Gym"))
    assertFalse("the default label is never shown", subtitle(emptySet(), "Alarm").contains("·"))
  }
}
