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
import android.net.Uri
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import org.json.JSONArray
import org.junit.Assert.assertNull
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.mockito.Mockito
import org.mockito.stubbing.Answer

/** Exercise real Config mutations; only Android storage and alarm transport are replaced. */
class TimerConfigMutationTest {
  @Test fun concurrentAddsKeepBothPersistedTimers() {
    val fixture = Fixture()
    race(fixture, first = { TimerConfig.add(fixture.context, "First", 60_000L) },
        second = { TimerConfig.add(fixture.context, "Second", 60_000L) })
    assertEquals(setOf("First", "Second"), TimerConfig.load(fixture.context).map { it.label }.toSet())
    assertEquals(2, fixture.armed.size)
  }

  @Test fun receiverForgetsCannotResurrectEachOthersTimers() {
    val fixture = Fixture(THREE_TIMERS)
    race(fixture, first = { TimerConfig.forget(fixture.context, "first") },
        second = { TimerConfig.forget(fixture.context, "second") })
    assertEquals(listOf("keep"), TimerConfig.load(fixture.context).map { it.id })
  }

  @Test fun startupCleanupCannotDropATimerStartedDuringCleanup() {
    val fixture = Fixture(EXPIRED_TIMERS)
    race(fixture, first = { TimerConfig.rearmAll(fixture.context) },
        second = { TimerConfig.add(fixture.context, "Fresh", 60_000L) })
    assertEquals(listOf("Fresh"), TimerConfig.load(fixture.context).map { it.label })
    assertEquals(1, fixture.armed.size)
  }

  @Test fun receiverForgetAndStartupCleanupCannotResurrectAnExpiredTimer() {
    val fixture = Fixture(EXPIRED_TIMERS)
    race(fixture, first = { TimerConfig.forget(fixture.context, "first") },
        second = { TimerConfig.rearmAll(fixture.context) })
    assertTrue(TimerConfig.load(fixture.context).isEmpty())
  }

  @Test fun coldProcessRestoreKeepsAndRearmsDueTimersWithoutRewritingStorage() {
    val fixture = Fixture(DUE_AND_FUTURE_TIMERS)
    fixture.withPlatform { TimerConfig.rearmAll(fixture.context, dropExpired = false) }
    assertEquals(DUE_AND_FUTURE_TIMERS, fixture.raw.get())
    assertEquals(2, fixture.armed.size)
  }

  @Test fun coldProcessRestoreLeavesDueDeliveryClaimableExactlyOnce() {
    val fixture = Fixture(DUE_AND_FUTURE_TIMERS)
    fixture.withPlatform { TimerConfig.rearmAll(fixture.context, dropExpired = false) }
    assertEquals("Due", TimerConfig.consumeDue(fixture.context, "due")?.label)
    assertNull(TimerConfig.consumeDue(fixture.context, "due"))
    assertEquals(listOf("future"), TimerConfig.load(fixture.context).map { it.id })
  }

  @Test fun cancellingDuringAddCannotLeaveAnAlarmForADeletedTimer() {
    val fixture = Fixture()
    race(fixture, blockScheduling = true,
        first = { TimerConfig.add(fixture.context, "Cancel me", 60_000L) },
        second = {
          val id = JSONArray(fixture.raw.get()).getJSONObject(0).getString("id")
          TimerConfig.remove(fixture.context, id)
        })
    assertTrue(TimerConfig.load(fixture.context).isEmpty())
    assertTrue("A removed timer must not remain scheduled", fixture.armed.isEmpty())
  }

  @Test fun expiryAtItsExactDeadlineClaimsOnlyThatTimer() {
    val fixture = Fixture(THREE_TIMERS)
    val completed = TimerConfig.consumeDue(fixture.context, "first", nowMillis = 1L)
    assertEquals("First", completed?.label)
    assertEquals(listOf("second", "keep"), TimerConfig.load(fixture.context).map { it.id })
  }

  @Test fun expiryBeforeItsDeadlineLeavesSavedTimersUntouched() {
    val fixture = Fixture(THREE_TIMERS)
    assertNull(TimerConfig.consumeDue(fixture.context, "first", nowMillis = 0L))
    assertEquals(THREE_TIMERS, fixture.raw.get())
  }

  @Test fun concurrentDuplicateExpiryClaimsCompleteATimerOnlyOnce() {
    val fixture = Fixture(THREE_TIMERS)
    val completions = AtomicInteger()
    val claim = {
      if (TimerConfig.consumeDue(fixture.context, "first", nowMillis = 1L) != null) {
        completions.incrementAndGet()
      }
      Unit
    }
    race(fixture, first = claim, second = claim)
    assertEquals(1, completions.get())
    assertEquals(listOf("second", "keep"), TimerConfig.load(fixture.context).map { it.id })
  }

  @Test fun timerStartedDuringExpiryIsNotLost() {
    val fixture = Fixture(THREE_TIMERS)
    race(fixture,
        first = { TimerConfig.consumeDue(fixture.context, "first", nowMillis = 1L) },
        second = { TimerConfig.add(fixture.context, "Fresh", 60_000L) })
    assertEquals(listOf("Second", "Keep", "Fresh"),
        TimerConfig.load(fixture.context).map { it.label })
    assertEquals(1, fixture.armed.size)
  }

  /** Hold the first write or alarm registration while a competing public API call starts. */
  private fun race(
      fixture: Fixture, blockScheduling: Boolean = false,
      first: () -> Unit, second: () -> Unit,
  ) {
    val entered = CountDownLatch(1)
    val release = CountDownLatch(1)
    val claimed = AtomicBoolean()
    val pause = {
      if (claimed.compareAndSet(false, true)) {
        entered.countDown()
        check(release.await(5, TimeUnit.SECONDS)) { "Mutation was not released" }
      }
    }
    if (blockScheduling) fixture.beforeSchedule = pause else fixture.beforeApply = pause
    val executor = Executors.newFixedThreadPool(2)
    val secondaryThread = AtomicReference<Thread>()
    val secondaryStarted = CountDownLatch(1)
    try {
      val primary = executor.submit { fixture.withPlatform(first) }
      assertTrue(entered.await(5, TimeUnit.SECONDS))
      val secondary = executor.submit {
        fixture.withPlatform {
          secondaryThread.set(Thread.currentThread())
          secondaryStarted.countDown()
          second()
        }
      }
      assertTrue(secondaryStarted.await(5, TimeUnit.SECONDS))
      // A serialized mutation blocks; an unsynchronized competitor completes and exposes
      // the lost update when the first write/registration resumes. No sleep-based race.
      val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5)
      while (!secondary.isDone && secondaryThread.get().state != Thread.State.BLOCKED &&
          System.nanoTime() < deadline) Thread.yield()
      assertTrue(secondary.isDone || secondaryThread.get().state == Thread.State.BLOCKED)
      release.countDown()
      primary.get(5, TimeUnit.SECONDS)
      secondary.get(5, TimeUnit.SECONDS)
    } finally {
      release.countDown()
      executor.shutdownNow()
      assertTrue(executor.awaitTermination(5, TimeUnit.SECONDS))
    }
  }

  private class Fixture(initial: String = "[]") {
    val context = Mockito.mock(Context::class.java)
    val raw = AtomicReference(initial)
    val armed = ConcurrentHashMap.newKeySet<PendingIntent>()
    private val pending = ConcurrentHashMap<Pair<Int, String?>, PendingIntent>()
    private val prefs = Mockito.mock(SharedPreferences::class.java)
    private val alarms = Mockito.mock(AlarmManager::class.java)
    @Volatile var beforeApply: (() -> Unit)? = null
    @Volatile var beforeSchedule: (() -> Unit)? = null

    init {
      Mockito.`when`(context.getSharedPreferences("immortal_timers", Context.MODE_PRIVATE)).thenReturn(prefs)
      Mockito.`when`(context.getSystemService(AlarmManager::class.java)).thenReturn(alarms)
      Mockito.`when`(prefs.getString("timers", null)).thenAnswer { raw.get() }
      Mockito.`when`(prefs.edit()).thenAnswer {
        // Editors keep independent staged values, as real SharedPreferences editors do.
        val editor = Mockito.mock(SharedPreferences.Editor::class.java, Mockito.RETURNS_SELF)
        val staged = AtomicReference<String>()
        Mockito.`when`(editor.putString(Mockito.eq("timers"), Mockito.anyString())).thenAnswer {
          staged.set(it.getArgument(1))
          editor
        }
        Mockito.doAnswer {
          beforeApply?.invoke()
          raw.set(staged.get())
          null
        }.`when`(editor).apply()
        editor
      }
      Mockito.doAnswer {
        armed.add(it.getArgument(2))
        null
      }.`when`(alarms).setExactAndAllowWhileIdle(Mockito.anyInt(), Mockito.anyLong(), Mockito.any(PendingIntent::class.java))
      Mockito.doAnswer {
        armed.remove(it.getArgument<PendingIntent>(0))
        null
      }.`when`(alarms).cancel(Mockito.any(PendingIntent::class.java))
    }

    // Mockito constructor/static interception is thread-local; install it in each worker.
    fun withPlatform(operation: () -> Unit) {
      Mockito.mockStatic(Uri::class.java, Answer<Any?> { call ->
        if (call.method.name != "fromParts") Mockito.RETURNS_DEFAULTS.answer(call)
        else Mockito.mock(Uri::class.java).also {
          Mockito.`when`(it.toString()).thenReturn(call.getArgument<String>(1))
        }
      }).use {
        Mockito.mockConstruction(Intent::class.java,
            Mockito.withSettings().defaultAnswer(Mockito.RETURNS_SELF)) { intent, _ ->
          Mockito.doAnswer {
            Mockito.`when`(intent.data).thenReturn(it.getArgument(0))
            intent
          }.`when`(intent).setData(Mockito.any(Uri::class.java))
        }.use {
          Mockito.mockStatic(PendingIntent::class.java, Answer<Any?> { call ->
            if (call.method.name != "getBroadcast") Mockito.RETURNS_DEFAULTS.answer(call)
            else {
              val key = call.getArgument<Int>(1) to call.getArgument<Intent>(2).data?.toString()
              val flags = call.getArgument<Int>(3)
              if (flags and PendingIntent.FLAG_NO_CREATE != 0) pending[key]
              else {
                beforeSchedule?.invoke()
                pending.computeIfAbsent(key) {
                  Mockito.mock(PendingIntent::class.java).also { intent ->
                    Mockito.doAnswer { pending.remove(key); null }.`when`(intent).cancel()
                  }
                }
              }
            }
          }).use { operation() }
        }
      }
    }
  }

  private companion object {
    const val THREE_TIMERS = """[
      {"id":"first","label":"First","dur":1000,"end":1},
      {"id":"second","label":"Second","dur":1000,"end":2},
      {"id":"keep","label":"Keep","dur":1000,"end":3}
    ]"""
    const val EXPIRED_TIMERS = """[
      {"id":"first","label":"First","dur":1000,"end":1},
      {"id":"second","label":"Second","dur":1000,"end":2}
    ]"""
    const val DUE_AND_FUTURE_TIMERS = """[
      {"id":"due","label":"Due","dur":1000,"end":1},
      {"id":"future","label":"Future","dur":1000,"end":4102444800000}
    ]"""
  }
}
