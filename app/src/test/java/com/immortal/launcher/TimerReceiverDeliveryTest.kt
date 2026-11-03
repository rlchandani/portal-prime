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
import android.media.AudioAttributes
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.util.Log
import java.util.concurrent.atomic.AtomicReference
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.mockito.Mockito
import org.mockito.stubbing.Answer

/** Real receiver, Config and ChimePlayer; Android callbacks are captured, never executed. */
class TimerReceiverDeliveryTest {
  @Test fun cancelledTimerDeliverySchedulesNoAudioAndPreservesOtherTimers() {
    val fixture = Fixture(DUE_AND_KEEP)
    fixture.withPlatform {
      val otherTimers = TimerConfig.load(fixture.context).filterNot { it.id == "due" }
      val delivery = fixture.intent("due")
      TimerConfig.remove(fixture.context, "due")
      Mockito.verify(fixture.alarms).cancel(fixture.pending)
      Mockito.verify(fixture.pending).cancel()
      assertEquals(otherTimers, TimerConfig.load(fixture.context))

      TimerReceiver().onReceive(fixture.context, delivery)

      assertTrue("A cancelled timer must schedule no audio work", fixture.jobs.isEmpty())
      assertEquals(otherTimers, TimerConfig.load(fixture.context))
    }
  }

  @Test fun missingTimerDeliveryLeavesSavedTimersAndAudioAlone() {
    val fixture = Fixture(DUE_AND_KEEP)
    fixture.withPlatform { fixture.assertIgnored(fixture.intent("missing")) }
  }

  @Test fun deliveryWithoutIdLeavesSavedTimersAndAudioAlone() {
    val fixture = Fixture(DUE_AND_KEEP)
    fixture.withPlatform { fixture.assertIgnored(fixture.intent(null)) }
  }

  @Test fun deliveryWithBlankIdLeavesSavedTimersAndAudioAlone() {
    val fixture = Fixture(DUE_AND_KEEP)
    fixture.withPlatform { fixture.assertIgnored(fixture.intent("")) }
  }

  @Test fun duplicateDeliveryAddsNoJobsAfterFirstDueDelivery() {
    val fixture = Fixture(DUE_AND_KEEP)
    fixture.withPlatform {
      val otherTimers = TimerConfig.load(fixture.context).filterNot { it.id == "due" }
      val receiver = TimerReceiver()
      val delivery = fixture.intent("due")
      receiver.onReceive(fixture.context, delivery)
      assertEquals(listOf(0L, 1400L, 2800L, 4200L), fixture.jobs.map { it.delayMs })
      assertEquals(otherTimers, TimerConfig.load(fixture.context))
      // Retain the actual accepted callbacks, not just the original count or delays.
      val firstDeliveryJobs = fixture.jobs.toList()
      val firstDeliveryStorage = fixture.raw.get()

      receiver.onReceive(fixture.context, delivery)

      assertEquals("Duplicate delivery must add no audio work", firstDeliveryJobs, fixture.jobs)
      assertEquals(firstDeliveryStorage, fixture.raw.get())
      assertEquals(otherTimers, TimerConfig.load(fixture.context))
    }
  }

  @Test fun prematureDeliveryPreservesFutureTimerAndSchedulesNoAudio() {
    val fixture = Fixture(FUTURE_AND_KEEP)
    fixture.withPlatform { fixture.assertIgnored(fixture.intent("future")) }
  }

  @Test fun dueSavedTimerSchedulesThreeRingsAndSpeechAndRemovesOnlyItself() {
    val fixture = Fixture(DUE_AND_KEEP)
    fixture.withPlatform {
      val otherTimers = TimerConfig.load(fixture.context).filterNot { it.id == "due" }

      TimerReceiver().onReceive(fixture.context, fixture.intent("due"))

      assertEquals("Three ring jobs followed by one speech job",
          listOf(0L, 1400L, 2800L, 4200L), fixture.jobs.map { it.delayMs })
      assertEquals(otherTimers, TimerConfig.load(fixture.context))
    }
  }

  @Test fun unrelatedActionLeavesSavedTimersAndAudioAlone() {
    val fixture = Fixture(DUE_AND_KEEP)
    fixture.withPlatform {
      fixture.assertIgnored(fixture.intent("due", action = "com.immortal.launcher.UNRELATED"))
    }
  }

  private data class Job(val runnable: Runnable, val delayMs: Long)

  private class Fixture(initial: String) {
    val context = Mockito.mock(Context::class.java)
    val raw = AtomicReference(initial)
    val jobs = mutableListOf<Job>()
    val alarms = Mockito.mock(AlarmManager::class.java)
    val pending = Mockito.mock(PendingIntent::class.java)
    private val prefs = Mockito.mock(SharedPreferences::class.java)

    init {
      Mockito.`when`(context.applicationContext).thenReturn(context)
      Mockito.`when`(context.getSharedPreferences("immortal_timers", Context.MODE_PRIVATE)).thenReturn(prefs)
      Mockito.`when`(context.getSystemService(AlarmManager::class.java)).thenReturn(alarms)
      Mockito.`when`(prefs.getString("timers", null)).thenAnswer { raw.get() }
      Mockito.`when`(prefs.edit()).thenAnswer {
        val editor = Mockito.mock(SharedPreferences.Editor::class.java, Mockito.RETURNS_SELF)
        val staged = AtomicReference<String>()
        Mockito.`when`(editor.putString(Mockito.eq("timers"), Mockito.anyString())).thenAnswer {
          staged.set(it.getArgument(1))
          editor
        }
        Mockito.doAnswer {
          raw.set(staged.get())
          null
        }.`when`(editor).apply()
        editor
      }
    }

    fun intent(id: String?, action: String = TimerScheduler.ACTION_FIRE): Intent {
      val intent = Mockito.mock(Intent::class.java)
      Mockito.`when`(intent.action).thenReturn(action)
      Mockito.`when`(intent.getStringExtra(TimerScheduler.EXTRA_ID)).thenReturn(id)
      Mockito.`when`(intent.getStringExtra(TimerScheduler.EXTRA_LABEL)).thenReturn("Pasta")
      return intent
    }

    fun assertIgnored(intent: Intent) {
      val beforeTimers = TimerConfig.load(context)
      val beforeStorage = raw.get()

      TimerReceiver().onReceive(context, intent)

      assertTrue("Discarded delivery must schedule no audio work", jobs.isEmpty())
      assertEquals(beforeStorage, raw.get())
      assertEquals(beforeTimers, TimerConfig.load(context))
    }

    fun withPlatform(operation: () -> Unit) {
      Mockito.mockStatic(Uri::class.java).use { uris ->
        uris.`when`<Uri> { Uri.fromParts(Mockito.anyString(), Mockito.anyString(), Mockito.isNull()) }
            .thenAnswer { Mockito.mock(Uri::class.java) }
        Mockito.mockStatic(Log::class.java).use {
          Mockito.mockStatic(Looper::class.java).use { loopers ->
            val main = Mockito.mock(Looper::class.java)
            loopers.`when`<Looper> { Looper.getMainLooper() }.thenReturn(main)
            val attrs = Mockito.mock(AudioAttributes::class.java)
            Mockito.mockConstruction(AudioAttributes.Builder::class.java,
                Mockito.withSettings().defaultAnswer(Mockito.RETURNS_SELF)) { builder, _ ->
              Mockito.`when`(builder.build()).thenReturn(attrs)
            }.use {
              Mockito.mockConstruction(Handler::class.java) { handler, _ ->
                Mockito.doAnswer { call ->
                  jobs.add(Job(call.getArgument<Runnable>(0), call.getArgument<Long>(1)))
                  true
                }.`when`(handler).postDelayed(Mockito.any(Runnable::class.java), Mockito.anyLong())
              }.use {
                Mockito.mockConstruction(Intent::class.java,
                    Mockito.withSettings().defaultAnswer(Mockito.RETURNS_SELF)) { intent, _ ->
                  Mockito.doAnswer {
                    Mockito.`when`(intent.data).thenReturn(it.getArgument(0))
                    intent
                  }.`when`(intent).setData(Mockito.any(Uri::class.java))
                }.use {
                  Mockito.mockStatic(PendingIntent::class.java, Answer<Any?> { call ->
                    if (call.method.name == "getBroadcast") {
                      if (call.getArgument<Intent>(2).data == null) null else pending
                    }
                    else Mockito.RETURNS_DEFAULTS.answer(call)
                  }).use { operation() }
                }
              }
            }
          }
        }
      }
    }
  }

  private companion object {
    // Literal past/future deadlines keep receiver tests independent of millisecond timing.
    // Exact-deadline eligibility belongs in consumeDue tests with an explicit clock value.
    const val DUE_AND_KEEP = """[
      {"id":"due","label":"Pasta","dur":1000,"end":1},
      {"id":"keep","label":"Tea","dur":60000,"end":4102444800000}
    ]"""
    const val FUTURE_AND_KEEP = """[
      {"id":"future","label":"Pasta","dur":1000,"end":4102444800000},
      {"id":"keep","label":"Tea","dur":60000,"end":4102444800001}
    ]"""
  }
}
