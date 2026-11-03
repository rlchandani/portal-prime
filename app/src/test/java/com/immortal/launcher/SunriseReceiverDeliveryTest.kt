/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */

package com.immortal.launcher

import android.app.AlarmManager
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import java.util.Calendar
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.mockito.Mockito
import org.mockito.stubbing.Answer

/** Production receiver/config/scheduler; preferences and Android effects are captured locally. */
class SunriseReceiverDeliveryTest {
  @Test fun oneShotFiresOnceDisablesOnlyEnabledAndArmsNoNextOccurrence() {
    val fixture = Fixture()
    fixture.withPlatform {
      val before = SunriseConfig.load(fixture.context)
      SunriseReceiver().onReceive(fixture.context, fixture.delivery())
      assertEquals(before.copy(enabled = false), SunriseConfig.load(fixture.context))
      assertEquals(fixture.initial + ("enabled" to false), fixture.prefs.getAll())
      assertEquals(1, fixture.wakes.size)
      Mockito.verify(fixture.wakes.single()).putExtra(WakeLightActivity.EXTRA_RAMP_MIN, 35)
      Mockito.verify(fixture.wakes.single()).putExtra(WakeLightActivity.EXTRA_CHIME, false)
      assertTrue(fixture.armed.isEmpty())
      Mockito.verify(fixture.notifications).notify(Mockito.anyInt(), Mockito.any(Notification::class.java))
    }
  }

  @Test fun duplicateOneShotDeliveryDoesNotWakeOrWriteAgain() {
    val fixture = Fixture()
    fixture.withPlatform {
      val receiver = SunriseReceiver()
      receiver.onReceive(fixture.context, fixture.delivery())
      val writes = fixture.prefs.writeCount
      val firstWake = fixture.wakes.single()
      receiver.onReceive(fixture.context, fixture.delivery())
      assertEquals(listOf(firstWake), fixture.wakes)
      assertEquals(writes, fixture.prefs.writeCount)
      assertFalse(SunriseConfig.load(fixture.context).enabled)
      assertTrue(fixture.armed.isEmpty())
    }
  }

  @Test fun laterAppOrBootRescheduleCannotRearmFiredOneShot() {
    val fixture = Fixture()
    fixture.withPlatform {
      SunriseReceiver().onReceive(fixture.context, fixture.delivery())
      repeat(2) { SunriseScheduler.reschedule(fixture.context) }
      assertEquals(1, fixture.wakes.size)
      assertTrue(fixture.armed.isEmpty())
      assertFalse(SunriseConfig.load(fixture.context).enabled)
    }
  }

  @Test fun repeatingDaysRemainEnabledAndArmTheirNextMatchingOccurrence() {
    val fixture = Fixture(days = setOf(Calendar.MONDAY, Calendar.THURSDAY))
    fixture.withPlatform {
      val before = SunriseConfig.load(fixture.context)
      SunriseReceiver().onReceive(fixture.context, fixture.delivery())
      assertEquals(before, SunriseConfig.load(fixture.context))
      assertEquals(fixture.initial, fixture.prefs.getAll())
      assertEquals(0, fixture.prefs.writeCount)
      assertEquals(1, fixture.wakes.size)
      val next = Calendar.getInstance().apply { timeInMillis = fixture.armed.single() }
      assertTrue(before.days.contains(next.get(Calendar.DAY_OF_WEEK)))
      assertEquals(before.hour, next.get(Calendar.HOUR_OF_DAY))
      assertEquals(before.minute, next.get(Calendar.MINUTE))
    }
  }

  @Test fun disabledDeliveryNeitherWakesNorWritesNorRearms() {
    val fixture = Fixture(enabled = false)
    fixture.withPlatform {
      SunriseReceiver().onReceive(fixture.context, fixture.delivery())
      assertEquals(fixture.initial, fixture.prefs.getAll())
      assertEquals(0, fixture.prefs.writeCount)
      assertTrue(fixture.wakes.isEmpty())
      assertTrue(fixture.armed.isEmpty())
      Mockito.verifyNoInteractions(fixture.notifications)
    }
  }

  @Test fun nullAndUnrelatedActionsLeaveEverythingUntouched() {
    val fixture = Fixture()
    fixture.withPlatform {
      SunriseReceiver().onReceive(fixture.context, null)
      SunriseReceiver().onReceive(fixture.context, fixture.delivery("unrelated"))
      assertEquals(fixture.initial, fixture.prefs.getAll())
      assertEquals(0, fixture.prefs.writeCount)
      assertTrue(fixture.wakes.isEmpty())
      assertTrue(fixture.armed.isEmpty())
      Mockito.verifyNoInteractions(fixture.notifications, fixture.alarms)
    }
  }

  @Test fun containedPresentationFailuresStillConsumeOneShot() {
    val fixture = Fixture()
    fixture.withPlatform {
      Mockito.doThrow(IllegalStateException("notification refused")).`when`(fixture.notifications)
          .notify(Mockito.anyInt(), Mockito.any(Notification::class.java))
      Mockito.doThrow(IllegalStateException("background launch refused")).`when`(fixture.context)
          .startActivity(Mockito.any(Intent::class.java))
      SunriseReceiver().onReceive(fixture.context, fixture.delivery())
      assertFalse(SunriseConfig.load(fixture.context).enabled)
      assertEquals(fixture.initial + ("enabled" to false), fixture.prefs.getAll())
      assertTrue(fixture.armed.isEmpty())
    }
  }

  private class Fixture(enabled: Boolean = true, days: Set<Int> = emptySet()) {
    val initial = mapOf<String, Any?>(
        "enabled" to enabled, "hour" to 7, "minute" to 13, "ramp" to 35,
        "chime" to false, "days" to days.map { it.toString() }.toSet(), "unrelated" to "retained")
    val prefs = ScreensaverTestPrefs(initial)
    val context = prefs.context
    val alarms = Mockito.mock(AlarmManager::class.java)
    val notifications = Mockito.mock(NotificationManager::class.java)
    val wakes = mutableListOf<Intent>()
    val armed = mutableListOf<Long>()

    init {
      Mockito.`when`(context.getSharedPreferences("immortal_sunrise", Context.MODE_PRIVATE)).thenReturn(prefs)
      Mockito.`when`(context.getSystemService(AlarmManager::class.java)).thenReturn(alarms)
      Mockito.`when`(context.getSystemService(NotificationManager::class.java)).thenReturn(notifications)
      Mockito.doAnswer {
        wakes += it.getArgument<Intent>(0)
        null
      }.`when`(context).startActivity(Mockito.any(Intent::class.java))
      Mockito.doAnswer {
        armed += it.getArgument<Long>(1)
        null
      }.`when`(alarms).setExactAndAllowWhileIdle(
          Mockito.anyInt(), Mockito.anyLong(), Mockito.any(PendingIntent::class.java))
    }

    fun delivery(action: String = SunriseScheduler.ACTION_FIRE) = Mockito.mock(Intent::class.java).also {
      Mockito.`when`(it.action).thenReturn(action)
    }

    fun withPlatform(operation: () -> Unit) {
      val pending = Mockito.mock(PendingIntent::class.java)
      val notification = Mockito.mock(Notification::class.java)
      Mockito.mockConstruction(Intent::class.java,
          Mockito.withSettings().defaultAnswer(Mockito.RETURNS_SELF)).use {
        Mockito.mockConstruction(NotificationChannel::class.java).use {
          Mockito.mockConstruction(Notification.Builder::class.java,
              Mockito.withSettings().defaultAnswer(Mockito.RETURNS_SELF)) { builder, _ ->
            Mockito.`when`(builder.build()).thenReturn(notification)
          }.use {
            Mockito.mockStatic(PendingIntent::class.java, Answer<Any?> { call ->
              if (call.method.name in setOf("getActivity", "getBroadcast")) pending
              else Mockito.RETURNS_DEFAULTS.answer(call)
            }).use { operation() }
          }
        }
      }
    }
  }
}
