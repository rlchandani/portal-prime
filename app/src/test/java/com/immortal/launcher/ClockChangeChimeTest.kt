/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */
package com.immortal.launcher

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import java.util.Calendar
import java.util.TimeZone
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.mockito.Mockito

/** Real receiver/config/scheduler path; only Android storage and alarm delivery are doubled. */
class ClockChangeChimeTest {
  @Test fun clockCorrectionRearmsHourlyChime() =
      expectHourlyAlarm(Intent.ACTION_TIME_CHANGED, "hourly_chime_on")

  @Test fun timezoneChangeRearmsHourlyChime() =
      expectHourlyAlarm(Intent.ACTION_TIMEZONE_CHANGED, "hourly_chime_on")

  @Test fun clockCorrectionRearmsSpokenTime() =
      expectHourlyAlarm(Intent.ACTION_TIME_CHANGED, "spoken_time_on")

  @Test fun timezoneChangeRearmsSpokenTime() =
      expectHourlyAlarm(Intent.ACTION_TIMEZONE_CHANGED, "spoken_time_on")

  @Test fun disabledHourlyFeaturesCancelTheOldChime() {
    withAlarms(null) { context, pending, scheduled, cancelled ->
      ClockChangeReceiver().onReceive(context, event(Intent.ACTION_TIMEZONE_CHANGED))
      assertTrue(scheduled.isEmpty())
      assertEquals(listOf(pending), cancelled)
    }
  }

  @Test fun unrelatedBroadcastLeavesTheExistingChimeAlone() {
    withAlarms("hourly_chime_on") { context, _, scheduled, cancelled ->
      ClockChangeReceiver().onReceive(context, event(Intent.ACTION_TIME_TICK))
      assertTrue(scheduled.isEmpty())
      assertTrue(cancelled.isEmpty())
    }
  }

  private fun expectHourlyAlarm(action: String, enabledKey: String) {
    val previousZone = TimeZone.getDefault()
    try {
      // A quarter-hour zone makes an old whole-hour UTC alarm visibly wrong locally.
      TimeZone.setDefault(TimeZone.getTimeZone("Asia/Kathmandu"))
      withAlarms(enabledKey) { context, _, scheduled, cancelled ->
        val before = System.currentTimeMillis()
        ClockChangeReceiver().onReceive(context, event(action))
        assertEquals(1, scheduled.size)
        assertTrue(cancelled.isEmpty())
        val at = scheduled.single()
        assertTrue(at > before && at <= before + 3_600_000L)
        val local = Calendar.getInstance().apply { timeInMillis = at }
        assertEquals(0, local.get(Calendar.MINUTE))
        assertEquals(0, local.get(Calendar.SECOND))
        assertEquals(0, local.get(Calendar.MILLISECOND))
      }
    } finally {
      TimeZone.setDefault(previousZone)
    }
  }

  private fun event(action: String): Intent = Mockito.mock(Intent::class.java).also {
    Mockito.`when`(it.action).thenReturn(action)
  }

  private fun withAlarms(enabledKey: String?,
      check: (Context, PendingIntent, List<Long>, List<PendingIntent>) -> Unit) {
    val scheduled = mutableListOf<Long>()
    val cancelled = mutableListOf<PendingIntent>()
    val context = Mockito.mock(Context::class.java)
    val prefs = Mockito.mock(SharedPreferences::class.java) { call ->
      when (call.method.name) {
        "getBoolean" -> call.arguments[0] == enabledKey || call.arguments[1] as Boolean
        "getInt", "getString", "getStringSet" -> call.arguments[1]
        "contains" -> false
        else -> Mockito.RETURNS_DEFAULTS.answer(call)
      }
    }
    Mockito.`when`(context.getSharedPreferences(Mockito.anyString(), Mockito.anyInt()))
        .thenReturn(prefs)
    val pending = Mockito.mock(PendingIntent::class.java)
    val manager = Mockito.mock(AlarmManager::class.java) { call ->
      when (call.method.name) {
        "setExactAndAllowWhileIdle" -> {
          assertEquals(AlarmManager.RTC_WAKEUP, call.arguments[0])
          assertEquals(pending, call.arguments[2])
          scheduled += call.arguments[1] as Long
        }
        "cancel" -> cancelled += call.arguments[0] as PendingIntent
      }
      null
    }
    Mockito.`when`(context.getSystemService(AlarmManager::class.java)).thenReturn(manager)
    Mockito.mockConstruction(Intent::class.java,
        Mockito.withSettings().defaultAnswer(Mockito.RETURNS_SELF)).use {
      Mockito.mockStatic(PendingIntent::class.java).use { intents ->
        intents.`when`<PendingIntent> {
          PendingIntent.getBroadcast(Mockito.eq(context), Mockito.eq(2001),
              Mockito.any(Intent::class.java), Mockito.anyInt())
        }.thenReturn(pending)
        check(context, pending, scheduled, cancelled)
      }
    }
  }
}
