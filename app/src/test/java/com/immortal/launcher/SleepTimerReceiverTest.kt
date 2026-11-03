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
import android.media.AudioManager
import android.os.Looper
import android.provider.Settings
import android.util.Log
import android.view.KeyEvent
import org.junit.Assert.*
import org.junit.Test
import org.mockito.Mockito
import org.mockito.stubbing.Answer

/** Actual arm/receiver/sleep policy; capture effects without Android storage or screen changes. */
class SleepTimerReceiverTest {
  @Test fun armedTimerExpiresWithPauseCloseDreamSuppressionAndSleepInOrder() =
      withFixture { f ->
        f.armAndDeliver()
        assertEquals(listOf("pause-down", "pause-up", "close", "dream-off", "lock"), f.effects)
        assertEquals(f.initial, f.prefs.getAll())
        assertEquals(0, f.prefs.writeCount)
      }

  @Test fun timerWithBothOptionalEffectsOffStillSuppressesDreamAndSleeps() =
      withFixture(pause = false, close = false) { f ->
        f.armAndDeliver()
        assertEquals(listOf("dream-off", "lock"), f.effects)
      }

  @Test fun pauseOnlyNeverClosesTheCurrentApp() =
      withFixture(pause = true, close = false) { f ->
        f.armAndDeliver()
        assertEquals(listOf("pause-down", "pause-up", "dream-off", "lock"), f.effects)
      }

  @Test fun closeOnlyNeverDispatchesMediaPause() =
      withFixture(pause = false, close = true) { f ->
        f.armAndDeliver()
        assertEquals(listOf("close", "dream-off", "lock"), f.effects)
      }

  @Test fun disablingAfterArmMakesDeliveryHarmless() = withFixture { f ->
    f.arm()
    ScreensaverConfig.setSleepTimerEnabled(f.context, false)
    val saved = f.prefs.getAll()
    val writes = f.prefs.writeCount
    SleepReceiver().onReceive(f.context, f.scheduled!!)
    assertTrue(f.effects.isEmpty())
    assertEquals(saved, f.prefs.getAll())
    assertEquals(writes, f.prefs.writeCount)
  }

  @Test fun expirationUsesCurrentOptionsRatherThanTheArmTimeSnapshot() = withFixture { f ->
    f.arm()
    ScreensaverConfig.setPauseAudioOnSleep(f.context, false)
    ScreensaverConfig.setCloseAppOnSleep(f.context, false)
    val saved = f.prefs.getAll()
    val writes = f.prefs.writeCount
    SleepReceiver().onReceive(f.context, f.scheduled!!)
    assertEquals(listOf("dream-off", "lock"), f.effects)
    assertEquals(saved, f.prefs.getAll())
    assertEquals(writes, f.prefs.writeCount)
  }

  @Test fun absentDeviceAdminKeepsTheExistingSleepNoOp() =
      withFixture(pause = false, close = false, adminActive = false) { f ->
        f.armAndDeliver()
        assertEquals(listOf("dream-off"), f.effects)
        Mockito.verify(f.admin, Mockito.never()).lockNow()
      }

  @Test fun stoppedTimerCancelsTheSameOwnedHandle() = withFixture { f ->
    f.arm()
    SleepScheduler.cancelSleepTimer(f.context)
    Mockito.verify(f.alarms).cancel(f.pending)
    Mockito.verify(f.pending).cancel()
    assertTrue(f.effects.isEmpty())
    assertEquals(f.initial, f.prefs.getAll())
  }

  @Test fun missingAndUnrelatedActionsHaveNoSleepEffects() = withFixture { f ->
    for (action in listOf(null, "unrelated")) {
      val intent = Mockito.mock(Intent::class.java)
      Mockito.`when`(intent.action).thenReturn(action)
      SleepReceiver().onReceive(f.context, intent)
    }
    assertTrue(f.effects.isEmpty())
    assertEquals(0, f.prefs.writeCount)
    Mockito.verifyNoInteractions(f.audio, f.admin, f.alarms)
  }

  private class Fixture(pause: Boolean, close: Boolean, adminActive: Boolean) {
    val initial = mapOf<String, Any?>(
        "sleep_timer_enabled" to true, "sleep_timer_min" to 3,
        "pause_audio_on_sleep" to pause, "close_app_on_sleep" to close,
        "unrelated" to "retained")
    val prefs = ScreensaverTestPrefs(initial)
    val context = prefs.context
    val resolver = Mockito.mock(ContentResolver::class.java)
    val alarms = Mockito.mock(AlarmManager::class.java)
    val audio = Mockito.mock(AudioManager::class.java)
    val admin = Mockito.mock(DevicePolicyManager::class.java)
    val pending = Mockito.mock(PendingIntent::class.java)
    val effects = mutableListOf<String>()
    var scheduled: Intent? = null
    var alarmAt = 0L

    init {
      Mockito.`when`(context.contentResolver).thenReturn(resolver)
      Mockito.`when`(context.getSystemService(AlarmManager::class.java)).thenReturn(alarms)
      Mockito.`when`(context.getSystemService(Context.AUDIO_SERVICE)).thenReturn(audio)
      Mockito.`when`(context.getSystemService(DevicePolicyManager::class.java)).thenReturn(admin)
      Mockito.`when`(admin.isAdminActive(Mockito.any(ComponentName::class.java))).thenReturn(adminActive)
      Mockito.doAnswer { effects += "lock"; null }.`when`(admin).lockNow()
      Mockito.doAnswer { call ->
        val event = call.getArgument<KeyEvent>(0)
        assertEquals(KeyEvent.KEYCODE_MEDIA_PAUSE, event.keyCode)
        effects += if (event.action == KeyEvent.ACTION_DOWN) "pause-down" else "pause-up"
        null
      }.`when`(audio).dispatchMediaKeyEvent(Mockito.any(KeyEvent::class.java))
      Mockito.doAnswer { call ->
        val intent = call.getArgument<Intent>(0)
        assertEquals("com.immortal.launcher.CLOSE_CURRENT_APP", intent.action)
        Mockito.verify(intent).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        effects += "close"
        null
      }.`when`(context).startActivity(Mockito.any(Intent::class.java))
      Mockito.doAnswer { call ->
        assertEquals(AlarmManager.RTC_WAKEUP, call.getArgument<Int>(0))
        alarmAt = call.getArgument(1)
        null
      }.`when`(alarms).setExactAndAllowWhileIdle(
          Mockito.anyInt(), Mockito.anyLong(), Mockito.any(PendingIntent::class.java))
    }

    fun arm() {
      val before = System.currentTimeMillis()
      SleepScheduler.armSleepTimer(context)
      assertNotNull(scheduled)
      assertEquals(SleepScheduler.ACTION_SLEEP_TIMER, scheduled!!.action)
      assertTrue(alarmAt in (before + 180_000L)..(System.currentTimeMillis() + 180_000L))
    }

    fun armAndDeliver() {
      arm()
      SleepReceiver().onReceive(context, scheduled!!)
    }
  }

  private fun withFixture(
      pause: Boolean = true,
      close: Boolean = true,
      adminActive: Boolean = true,
      operation: (Fixture) -> Unit,
  ) {
    val f = Fixture(pause, close, adminActive)
    Mockito.mockStatic(Log::class.java).use {
      Mockito.mockStatic(Looper::class.java).use {
        Mockito.mockStatic(Settings.Secure::class.java, Answer<Any?> { call ->
          when (call.method.name) {
            "getInt" -> 1 // The system Dream is initially enabled.
            "putInt" -> {
              assertSame(f.resolver, call.arguments[0])
              assertEquals("screensaver_enabled", call.arguments[1])
              assertEquals(0, call.arguments[2])
              f.effects += "dream-off"
              true
            }
            else -> Mockito.RETURNS_DEFAULTS.answer(call)
          }
        }).use {
          Mockito.mockConstruction(ComponentName::class.java).use {
            Mockito.mockConstruction(KeyEvent::class.java) { event, construction ->
              Mockito.`when`(event.action).thenReturn(construction.arguments()[0] as Int)
              Mockito.`when`(event.keyCode).thenReturn(construction.arguments()[1] as Int)
            }.use {
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
                    assertEquals(1004, call.arguments[1])
                    f.scheduled = call.arguments[2] as Intent
                    f.pending
                  } else Mockito.RETURNS_DEFAULTS.answer(call)
                }).use { operation(f) }
              }
            }
          }
        }
      }
    }
  }
}
