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
import android.net.Uri
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.mockito.Mockito
import org.mockito.stubbing.Answer

/** Exercise real scheduler calls against Android's identity contract: extras are not identity. */
class TimerSchedulerIdentityTest {
  @Test fun collidingGeneratedIdsKeepBothDeadlinesAndPayloads() = withPlatform { f ->
    assertEquals(requestCode(FIRST.id), requestCode(SECOND.id))
    TimerScheduler.schedule(f.context, FIRST)
    TimerScheduler.schedule(f.context, SECOND)
    assertEquals(mapOf(FIRST.id to FIRST.endAtMillis, SECOND.id to SECOND.endAtMillis), f.deadlines())
  }

  @Test fun cancellingOneCollidingTimerKeepsTheOther() = withPlatform { f ->
    TimerScheduler.schedule(f.context, FIRST)
    TimerScheduler.schedule(f.context, SECOND)
    TimerScheduler.cancel(f.context, FIRST)
    assertEquals(mapOf(SECOND.id to SECOND.endAtMillis), f.deadlines())
  }

  @Test fun reschedulingKeepsIdentityAndUpdatesOnlyItsOwnDeadlineAndLabel() = withPlatform { f ->
    TimerScheduler.schedule(f.context, FIRST)
    TimerScheduler.schedule(f.context, SECOND)
    val before = f.pendingFor(FIRST.id)
    val changed = FIRST.copy(label = "Pasta ready", endAtMillis = FIRST.endAtMillis + 10_000)
    TimerScheduler.schedule(f.context, changed)
    assertEquals(before, f.pendingFor(FIRST.id))
    assertEquals("Pasta ready", f.payloads.getValue(before).label)
    assertEquals(mapOf(FIRST.id to changed.endAtMillis, SECOND.id to SECOND.endAtMillis), f.deadlines())
  }

  @Test fun exactAlarmFallbackKeepsCollidingTimersSeparate() = withPlatform { f ->
    Mockito.doThrow(SecurityException("Exact alarms unavailable"))
        .`when`(f.alarms).setExactAndAllowWhileIdle(
            Mockito.anyInt(), Mockito.anyLong(), Mockito.any(PendingIntent::class.java))
    TimerScheduler.schedule(f.context, FIRST)
    TimerScheduler.schedule(f.context, SECOND)
    assertEquals(mapOf(FIRST.id to FIRST.endAtMillis, SECOND.id to SECOND.endAtMillis), f.deadlines())
  }

  @Test fun upgradeRetiresLegacyIdentityAndRearmsBothSavedTimers() = withPlatform { f ->
    f.legacy(SECOND)
    TimerScheduler.schedule(f.context, FIRST)
    TimerScheduler.schedule(f.context, SECOND)
    assertFalse(f.identities.keys.any { it.data == null })
    assertEquals(mapOf(FIRST.id to FIRST.endAtMillis, SECOND.id to SECOND.endAtMillis), f.deadlines())
  }

  @Test fun cancellingALegacyTimerRetiresItsOldAlarm() = withPlatform { f ->
    f.legacy(FIRST)
    TimerScheduler.cancel(f.context, FIRST)
    assertTrue(f.identities.isEmpty())
    assertTrue(f.deadlines().isEmpty())
  }

  @Test fun cancellingUnknownIdCreatesNoPendingIntentAndKeepsExistingAlarm() = withPlatform { f ->
    TimerScheduler.schedule(f.context, FIRST)
    val count = f.identities.size
    TimerScheduler.cancel(f.context, FIRST.copy(id = "missing"))
    assertEquals(count, f.identities.size)
    assertEquals(mapOf(FIRST.id to FIRST.endAtMillis), f.deadlines())
  }

  private data class Identity(val code: Int, val component: Class<*>, val action: String?, val data: String?)
  private data class Payload(val id: String, val label: String)
  private data class IntentState(
      val component: Class<*>, var action: String? = null, var data: String? = null,
      var id: String = "", var label: String = "",
  )

  private class Fixture {
    val context = Mockito.mock(Context::class.java)
    val alarms = Mockito.mock(AlarmManager::class.java)
    val identities = linkedMapOf<Identity, PendingIntent>()
    val payloads = mutableMapOf<PendingIntent, Payload>()
    val armed = mutableMapOf<PendingIntent, Long>()
    val intents = mutableMapOf<Intent, IntentState>()

    init {
      Mockito.`when`(context.getSystemService(AlarmManager::class.java)).thenReturn(alarms)
      val arm = Answer<Any?> {
        armed[it.getArgument(2)] = it.getArgument(1)
        null
      }
      Mockito.doAnswer(arm).`when`(alarms).setExactAndAllowWhileIdle(
          Mockito.anyInt(), Mockito.anyLong(), Mockito.any(PendingIntent::class.java))
      Mockito.doAnswer(arm).`when`(alarms).setAndAllowWhileIdle(
          Mockito.anyInt(), Mockito.anyLong(), Mockito.any(PendingIntent::class.java))
      Mockito.doAnswer { armed.remove(it.getArgument<PendingIntent>(0)); null }
          .`when`(alarms).cancel(Mockito.any(PendingIntent::class.java))
    }

    fun broadcast(code: Int, intent: Intent, flags: Int): PendingIntent? {
      val s = intents.getValue(intent)
      val key = Identity(code, s.component, s.action, s.data)
      if (flags and PendingIntent.FLAG_NO_CREATE != 0) return identities[key]
      val pending = identities.getOrPut(key) {
        Mockito.mock(PendingIntent::class.java).also { p ->
          Mockito.doAnswer { identities.remove(key); armed.remove(p); payloads.remove(p); null }
              .`when`(p).cancel()
        }
      }
      if (flags and PendingIntent.FLAG_UPDATE_CURRENT != 0 || pending !in payloads)
        payloads[pending] = Payload(s.id, s.label)
      return pending
    }

    fun legacy(timer: TimerConfig.Timer) {
      val intent = Intent(context, TimerReceiver::class.java).setAction(TimerScheduler.ACTION_FIRE)
          .putExtra(TimerScheduler.EXTRA_ID, timer.id).putExtra(TimerScheduler.EXTRA_LABEL, timer.label)
      val p = PendingIntent.getBroadcast(context, requestCode(timer.id), intent,
          PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
      armed[p] = timer.endAtMillis
    }

    fun deadlines(): Map<String, Long> = armed.map { (p, deadline) ->
      payloads.getValue(p).id to deadline
    }.toMap()
    fun pendingFor(id: String): PendingIntent = payloads.entries.single { it.value.id == id }.key
  }

  private fun withPlatform(operation: (Fixture) -> Unit) {
    val f = Fixture()
    Mockito.mockStatic(Uri::class.java, Answer<Any?> { call ->
      if (call.method.name != "fromParts") Mockito.RETURNS_DEFAULTS.answer(call)
      else Mockito.mock(Uri::class.java).also {
        Mockito.`when`(it.toString()).thenReturn("${call.getArgument<String>(0)}:${call.getArgument<String>(1)}")
      }
    }).use {
      Mockito.mockConstruction(Intent::class.java,
          Mockito.withSettings().defaultAnswer(Mockito.RETURNS_SELF)) { intent, construction ->
        val s = IntentState(construction.arguments()[1] as Class<*>)
        f.intents[intent] = s
        Mockito.doAnswer { s.action = it.getArgument(0); intent }.`when`(intent).setAction(Mockito.anyString())
        Mockito.doAnswer { s.data = it.getArgument<Uri>(0).toString(); intent }
            .`when`(intent).setData(Mockito.any(Uri::class.java))
        Mockito.doAnswer {
          when (it.getArgument<String>(0)) {
            TimerScheduler.EXTRA_ID -> s.id = it.getArgument(1)
            TimerScheduler.EXTRA_LABEL -> s.label = it.getArgument(1)
          }
          intent
        }.`when`(intent).putExtra(Mockito.anyString(), Mockito.anyString())
      }.use {
        Mockito.mockStatic(PendingIntent::class.java, Answer<Any?> { call ->
          if (call.method.name != "getBroadcast") Mockito.RETURNS_DEFAULTS.answer(call)
          else f.broadcast(call.getArgument(1), call.getArgument(2), call.getArgument(3))
        }).use { operation(f) }
      }
    }
  }

  private companion object {
    // Both fit TimerConfig.add's time-in-base36/random format, 23 seconds apart.
    val FIRST = TimerConfig.Timer("mw069oc0-102", "Pasta", 60_000, 4_102_444_800_000)
    val SECOND = TimerConfig.Timer("mw06a62w-8470", "Tea", 90_000, 4_102_444_830_000)
    fun requestCode(id: String): Int = (id.hashCode() and 0x7FFFFFFF) or 1
  }
}
