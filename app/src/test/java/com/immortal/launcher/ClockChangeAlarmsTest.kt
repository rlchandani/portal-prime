/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */
package com.immortal.launcher

import android.content.Intent
import java.io.IOException
import org.junit.Assert.assertEquals
import org.junit.Test

/** Exercise the production dispatcher with counters only; no scheduler or Android state. */
class ClockChangeAlarmsTest {
  private val actions = listOf(Intent.ACTION_TIME_CHANGED, Intent.ACTION_TIMEZONE_CHANGED)

  @Test fun recognizedEventsRearmAlarmWithoutCancellingSnoozeThenWakeLight() {
    for (action in actions) {
      val calls = mutableListOf<String>()
      ClockChangeAlarms.rearm(action,
          alarm = { cancelSnooze -> calls += "alarm:$cancelSnooze" },
          wakeLight = { calls += "wakeLight" })
      assertEquals(listOf("alarm:false", "wakeLight"), calls)
    }
  }

  @Test fun eachRecognizedEventIncrementsRevisionExactlyOnce() {
    for (action in actions) {
      val before = ClockChangeAlarms.revision
      ClockChangeAlarms.rearm(action, {}, {})
      assertEquals(before + 1, ClockChangeAlarms.revision)
    }
  }

  @Test fun revisionIsPublishedBeforeEitherCallbackRuns() {
    for (action in actions) {
      val before = ClockChangeAlarms.revision
      val observed = mutableListOf<Int>()
      ClockChangeAlarms.rearm(action,
          alarm = { observed += ClockChangeAlarms.revision },
          wakeLight = { observed += ClockChangeAlarms.revision })
      assertEquals(listOf(before + 1, before + 1), observed)
    }
  }

  @Test fun ignoredActionsNeitherInvokeCallbacksNorChangeRevision() {
    for (action in listOf(null, "", Intent.ACTION_BOOT_COMPLETED, Intent.ACTION_TIME_TICK,
        Intent.ACTION_DATE_CHANGED, "android.intent.action.time_set")) {
      val before = ClockChangeAlarms.revision
      var calls = 0
      ClockChangeAlarms.rearm(action, { calls++ }, { calls++ })
      assertEquals(0, calls)
      assertEquals(before, ClockChangeAlarms.revision)
    }
  }

  @Test fun repeatedAndMixedEventsAreNotDeduplicated() {
    val before = ClockChangeAlarms.revision
    val snoozeFlags = mutableListOf<Boolean>()
    var wakes = 0
    for (action in listOf(actions[0], actions[0], null, actions[1], actions[1])) {
      ClockChangeAlarms.rearm(action, { snoozeFlags += it }, { wakes++ })
    }
    assertEquals(listOf(false, false, false, false), snoozeFlags)
    assertEquals(4, wakes)
    assertEquals(before + 4, ClockChangeAlarms.revision)
  }

  @Test fun alarmExceptionDoesNotBlockWakeLightOrRevision() {
    for (action in actions) expectIsolatedFailure(action, alarmFails = true, wakeFails = false)
  }

  @Test fun wakeLightExceptionDoesNotEscapeOrRepeatAlarm() {
    for (action in actions) expectIsolatedFailure(action, alarmFails = false, wakeFails = true)
  }

  @Test fun bothExceptionsAreIndependentlyContained() {
    for (action in actions) expectIsolatedFailure(action, alarmFails = true, wakeFails = true)
  }

  @Test fun laterEventsStillWorkAfterBothCallbacksFail() {
    val before = ClockChangeAlarms.revision
    expectIsolatedFailure(actions[0], alarmFails = true, wakeFails = true)
    val calls = mutableListOf<String>()
    ClockChangeAlarms.rearm(actions[1], { calls += "alarm:$it" }, { calls += "wakeLight" })
    assertEquals(listOf("alarm:false", "wakeLight"), calls)
    assertEquals(before + 2, ClockChangeAlarms.revision)
  }

  private fun expectIsolatedFailure(action: String, alarmFails: Boolean, wakeFails: Boolean) {
    val before = ClockChangeAlarms.revision
    val calls = mutableListOf<String>()
    ClockChangeAlarms.rearm(action,
        alarm = {
          calls += "alarm:$it"
          if (alarmFails) throw IOException()
        },
        wakeLight = {
          calls += "wakeLight"
          if (wakeFails) throw IllegalArgumentException()
        })
    assertEquals(listOf("alarm:false", "wakeLight"), calls)
    assertEquals(before + 1, ClockChangeAlarms.revision)
  }
}
