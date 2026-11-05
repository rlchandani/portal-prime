/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */
package com.immortal.launcher

import android.app.AlarmManager
import android.app.PendingIntent
import android.app.admin.DevicePolicyManager
import android.content.ComponentName
import android.content.ContentResolver
import android.content.Context
import android.content.Intent
import android.os.PowerManager
import android.provider.Settings
import android.util.Log
import org.junit.Assert.*
import org.junit.Test
import org.mockito.Mockito
import org.mockito.stubbing.Answer

class SleepOvernightDisabledTest {
  @Test fun disabledDarkWindowDoesNotLock() = withFixture(enabled = false) { f ->
    f.deliver()
    assertTrue(f.effects.isEmpty())
  }

  @Test fun disablingAfterArmMakesCapturedStartHarmless() = withFixture { f ->
    SleepScheduler.scheduleOvernight(f.context)
    ScreensaverConfig.setOvernightEnabled(f.context, false)
    f.deliver(f.startIntent!!)
    assertTrue(f.effects.isEmpty())
  }

  @Test fun disabledNightClockDoesNotWakeOrLaunch() =
      withFixture(enabled = false, nightClock = true) { f ->
        f.deliver()
        assertTrue(f.effects.isEmpty())
        Mockito.verifyNoInteractions(f.power)
      }

  @Test fun enabledSleepTimerDoesNotAuthorizeDisabledOvernight() =
      withFixture(enabled = false, sleepTimer = true) { f ->
        f.deliver()
        assertTrue(f.effects.isEmpty())
      }

  @Test fun enabledDarkWindowStillRequestsSleep() = withFixture { f ->
    f.deliver()
    assertTrue(f.effects.contains("lock"))
  }

  @Test fun missingAdminRetainsTheSleepNoOp() = withFixture(adminActive = false) { f ->
    f.deliver()
    assertFalse(f.effects.contains("lock"))
    Mockito.verify(f.admin, Mockito.never()).lockNow()
  }

  @Test fun disabledDeliveryStillCancelsBothOwnedWindowAlarms() = withFixture(enabled = false) { f ->
    f.deliver()
    Mockito.verify(f.alarms).cancel(f.pending.getValue(1002))
    Mockito.verify(f.alarms).cancel(f.pending.getValue(1003))
    assertTrue(f.effects.isEmpty())
  }

  private class Fixture(enabled: Boolean, nightClock: Boolean, sleepTimer: Boolean, adminActive: Boolean) {
    val prefs = ScreensaverTestPrefs(mapOf(
        "enabled" to true, "overnight_enabled" to enabled,
        "overnight_night_clock" to nightClock,
        "sleep_timer_enabled" to sleepTimer, "unrelated" to "retained"))
    val context = prefs.context
    val resolver = Mockito.mock(ContentResolver::class.java)
    val alarms = Mockito.mock(AlarmManager::class.java)
    val admin = Mockito.mock(DevicePolicyManager::class.java)
    val power = Mockito.mock(PowerManager::class.java)
    val pending = listOf(1001, 1002, 1003).associateWith { Mockito.mock(PendingIntent::class.java) }
    val effects = mutableListOf<String>()
    var startIntent: Intent? = null

    init {
      Mockito.`when`(context.contentResolver).thenReturn(resolver)
      Mockito.`when`(context.getSystemService(AlarmManager::class.java)).thenReturn(alarms)
      Mockito.`when`(context.getSystemService(DevicePolicyManager::class.java)).thenReturn(admin)
      Mockito.`when`(context.getSystemService(PowerManager::class.java)).thenReturn(power)
      Mockito.`when`(admin.isAdminActive(Mockito.any(ComponentName::class.java))).thenReturn(adminActive)
      Mockito.doAnswer { effects += "lock"; null }.`when`(admin).lockNow()
      Mockito.doAnswer { effects += "activity"; null }.`when`(context)
          .startActivity(Mockito.any(Intent::class.java))
    }

    fun deliver(intent: Intent? = null) {
      val action = intent ?: Mockito.mock(Intent::class.java).also {
        Mockito.`when`(it.action).thenReturn(SleepScheduler.ACTION_OVERNIGHT_START)
      }
      val before = prefs.getAll()
      val writes = prefs.writeCount
      SleepReceiver().onReceive(context, action)
      assertEquals(before, prefs.getAll())
      assertEquals(writes, prefs.writeCount)
    }
  }

  private fun withFixture(
      enabled: Boolean = true, nightClock: Boolean = false,
      sleepTimer: Boolean = false, adminActive: Boolean = true,
      test: (Fixture) -> Unit,
  ) {
    val f = Fixture(enabled, nightClock, sleepTimer, adminActive)
    Mockito.mockStatic(Log::class.java).use {
      Mockito.mockStatic(Settings.Secure::class.java, Answer<Any?> { call ->
        when (call.method.name) {
          "getInt" -> 1
          "putInt", "putString" -> {
            f.effects += "secure-write"
            true
          }
          else -> Mockito.RETURNS_DEFAULTS.answer(call)
        }
      }).use {
        Mockito.mockConstruction(ComponentName::class.java).use {
          Mockito.mockConstruction(Intent::class.java,
              Mockito.withSettings().defaultAnswer(Mockito.RETURNS_SELF)) { intent, _ ->
            var action: String? = null
            Mockito.`when`(intent.setAction(Mockito.anyString())).thenAnswer { call ->
              action = call.getArgument(0)
              intent
            }
            Mockito.`when`(intent.action).thenAnswer { action }
          }.use {
            Mockito.mockStatic(PendingIntent::class.java, Answer<Any?> { call ->
              if (call.method.name == "getBroadcast") {
                val requestCode = call.arguments[1] as Int
                if (requestCode == 1002) f.startIntent = call.arguments[2] as Intent
                f.pending.getValue(requestCode)
              } else Mockito.RETURNS_DEFAULTS.answer(call)
            }).use { test(f) }
          }
        }
      }
    }
  }
}
