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
import android.os.SystemClock
import android.provider.Settings
import android.util.Log
import android.view.KeyEvent
import org.junit.Assert.*
import org.junit.Test
import org.mockito.Mockito
import org.mockito.stubbing.Answer

/** Real idle arm/receiver/policy with generated prefs and captured Android-only effects. */
class SleepIdleReceiverTest {
  @Test fun disablingIdleAfterArmMakesTheCapturedDeliveryHarmless() = withFixture { f ->
    f.arm()
    ScreensaverConfig.setIdleSleepMin(f.context, 0)
    f.deliverCaptured()
    assertTrue(f.effects.isEmpty())
  }

  @Test fun disablingScreensaverAfterArmMakesTheCapturedDeliveryHarmless() = withFixture { f ->
    f.arm()
    ScreensaverConfig.setEnabled(f.context, false)
    f.deliverCaptured()
    assertTrue(f.effects.isEmpty())
  }

  @Test fun initiallyOffIdleNeitherArmsNorAcceptsAnOldIdleAction() =
      withFixture(idleMinutes = 0) { f ->
        f.assertNotArmed()
        f.deliver(SleepScheduler.ACTION_IDLE)
        assertTrue(f.effects.isEmpty())
      }

  @Test fun initiallyDisabledScreensaverNeitherArmsNorAcceptsAnOldIdleAction() =
      withFixture(enabled = false) { f ->
        f.assertNotArmed()
        f.deliver(SleepScheduler.ACTION_IDLE)
        assertTrue(f.effects.isEmpty())
      }

  @Test fun currentEnabledIdleSuppressesDreamThenLocksWithoutChangingPreferences() =
      withFixture { f ->
        f.arm()
        f.deliverCaptured()
        f.assertSleepEffects()
      }

  @Test fun reenabledIdleUsesCurrentPolicyRatherThanAnEarlierDisabledSnapshot() =
      withFixture { f ->
        f.arm()
        ScreensaverConfig.setIdleSleepMin(f.context, 0)
        ScreensaverConfig.setIdleSleepMin(f.context, 5)
        f.deliverCaptured()
        f.assertSleepEffects()
      }

  @Test fun reenabledScreensaverUsesCurrentPolicyAtDelivery() = withFixture { f ->
    f.arm()
    ScreensaverConfig.setEnabled(f.context, false)
    ScreensaverConfig.setEnabled(f.context, true)
    f.deliverCaptured()
    f.assertSleepEffects()
  }

  @Test fun idleDeliveryDoesNotBorrowSleepTimerPauseOrCloseOptions() =
      withFixture(timerEnabled = true) { f ->
        f.arm()
        f.deliverCaptured()
        f.assertSleepEffects()
        assertEquals(0, f.mediaKeys)
        assertEquals(0, f.activityLaunches)
      }

  @Test fun enabledSleepTimerDoesNotAuthorizeDisabledIdleDelivery() =
      withFixture(timerEnabled = true) { f ->
        f.arm()
        ScreensaverConfig.setIdleSleepMin(f.context, 0)
        f.deliverCaptured()
        assertTrue(f.effects.isEmpty())
        assertEquals(0, f.mediaKeys)
        assertEquals(0, f.activityLaunches)
      }

  @Test fun missingAndUnrelatedActionsRemainInert() = withFixture { f ->
    f.deliver(null)
    f.deliver("generated.unrelated.action")
    assertTrue(f.effects.isEmpty())
    assertTrue(f.secureWrites.isEmpty())
    assertTrue(f.alarmCalls.isEmpty())
  }

  @Test fun missingDeviceAdminRetainsDreamSuppressionAndTheExistingLockNoOp() =
      withFixture(adminActive = false) { f ->
        f.arm()
        f.deliverCaptured()
        assertEquals(listOf("dream-off"), f.effects)
        f.assertDreamWrite()
      }

  @Test fun disabledIdleDoesNotSuppressDreamEvenWhenDeviceAdminIsMissing() =
      withFixture(adminActive = false) { f ->
        f.arm()
        ScreensaverConfig.setIdleSleepMin(f.context, 0)
        f.deliverCaptured()
        assertTrue(f.effects.isEmpty())
        assertTrue(f.secureWrites.isEmpty())
      }

  private data class AlarmCall(val type: Int, val at: Long, val pending: PendingIntent)
  private data class SecureWrite(val resolver: Any?, val name: Any?, val value: Any?)

  private class Fixture(
      enabled: Boolean, idleMinutes: Int, timerEnabled: Boolean, adminActive: Boolean,
  ) {
    val prefs = ScreensaverTestPrefs(mapOf(
        "enabled" to enabled, "idle_sleep_min" to idleMinutes,
        "sleep_timer_enabled" to timerEnabled, "sleep_timer_min" to 30,
        "pause_audio_on_sleep" to true, "close_app_on_sleep" to true,
        "unrelated" to "retained"))
    val context = prefs.context
    val resolver = Mockito.mock(ContentResolver::class.java)
    val alarms = Mockito.mock(AlarmManager::class.java)
    val admin = Mockito.mock(DevicePolicyManager::class.java)
    val audio = Mockito.mock(AudioManager::class.java)
    val pending = Mockito.mock(PendingIntent::class.java)
    val effects = mutableListOf<String>()
    val secureWrites = mutableListOf<SecureWrite>()
    val alarmCalls = mutableListOf<AlarmCall>()
    var scheduled: Intent? = null
    var mediaKeys = 0
    var activityLaunches = 0

    init {
      Mockito.`when`(context.contentResolver).thenReturn(resolver)
      Mockito.`when`(context.getSystemService(AlarmManager::class.java)).thenReturn(alarms)
      Mockito.`when`(context.getSystemService(DevicePolicyManager::class.java)).thenReturn(admin)
      Mockito.`when`(context.getSystemService(Context.AUDIO_SERVICE)).thenReturn(audio)
      Mockito.`when`(admin.isAdminActive(Mockito.any(ComponentName::class.java))).thenReturn(adminActive)
      Mockito.doAnswer { effects += "lock"; null }.`when`(admin).lockNow()
      Mockito.doAnswer { mediaKeys++; effects += "media-key"; null }.`when`(audio)
          .dispatchMediaKeyEvent(Mockito.any(KeyEvent::class.java))
      Mockito.doAnswer { activityLaunches++; effects += "activity"; null }.`when`(context)
          .startActivity(Mockito.any(Intent::class.java))
      Mockito.doAnswer { call ->
        alarmCalls += AlarmCall(call.getArgument(0), call.getArgument(1), call.getArgument(2))
        null
      }.`when`(alarms).setExactAndAllowWhileIdle(
          Mockito.anyInt(), Mockito.anyLong(), Mockito.any(PendingIntent::class.java))
    }

    fun arm() {
      SleepScheduler.onScreensaverStarted(context)
      assertNotNull("Real arm callback must produce its idle Intent", scheduled)
      assertEquals(SleepScheduler.ACTION_IDLE, scheduled!!.action)
      assertEquals(1, alarmCalls.size)
      assertEquals(AlarmManager.ELAPSED_REALTIME_WAKEUP, alarmCalls.single().type)
      assertEquals(612_345L, alarmCalls.single().at) // 12,345 elapsed + ten generated minutes.
      assertSame(pending, alarmCalls.single().pending)
    }

    fun assertNotArmed() {
      SleepScheduler.onScreensaverStarted(context)
      assertNull(scheduled)
      assertTrue(alarmCalls.isEmpty())
    }

    fun deliverCaptured() {
      assertNotNull(scheduled)
      deliverIntent(scheduled!!)
    }

    fun deliver(action: String?) {
      val intent = Mockito.mock(Intent::class.java)
      Mockito.`when`(intent.action).thenReturn(action)
      deliverIntent(intent)
    }

    private fun deliverIntent(intent: Intent) {
      val before = prefs.getAll()
      val writes = prefs.writeCount
      SleepReceiver().onReceive(context, intent)
      assertEquals("Expiry must not alter any generated preference", before, prefs.getAll())
      assertEquals("Expiry must not write preferences", writes, prefs.writeCount)
      assertEquals(0, mediaKeys)
      assertEquals(0, activityLaunches)
    }

    fun assertDreamWrite() {
      assertEquals(listOf(SecureWrite(resolver, "screensaver_enabled", 0)), secureWrites)
    }

    fun assertSleepEffects() {
      assertEquals(listOf("dream-off", "lock"), effects)
      assertDreamWrite()
    }
  }

  private fun withFixture(
      enabled: Boolean = true,
      idleMinutes: Int = 10,
      timerEnabled: Boolean = false,
      adminActive: Boolean = true,
      test: (Fixture) -> Unit,
  ) {
    val f = Fixture(enabled, idleMinutes, timerEnabled, adminActive)
    Mockito.mockStatic(Log::class.java).use {
      Mockito.mockStatic(SystemClock::class.java, Answer<Any?> { call ->
        if (call.method.name == "elapsedRealtime") 12_345L
        else Mockito.RETURNS_DEFAULTS.answer(call)
      }).use {
        Mockito.mockStatic(Settings.Secure::class.java, Answer<Any?> { call ->
          when (call.method.name) {
            "getInt" -> 1
            "putInt" -> {
              // Capture here; assertions run outside production's exception boundary.
              f.secureWrites += SecureWrite(call.arguments[0], call.arguments[1], call.arguments[2])
              f.effects += "dream-off"
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
                  val flags = call.arguments[3] as Int
                  if (flags and PendingIntent.FLAG_NO_CREATE != 0) null
                  else {
                    f.scheduled = call.arguments[2] as Intent
                    f.pending
                  }
                } else Mockito.RETURNS_DEFAULTS.answer(call)
              }).use { test(f) }
            }
          }
        }
      }
    }
  }
}
