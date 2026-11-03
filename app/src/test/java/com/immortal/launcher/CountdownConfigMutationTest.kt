/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */

package com.immortal.launcher

import android.content.Context
import android.content.SharedPreferences
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import org.json.JSONArray
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.mockito.Mockito

/** Actual config mutations with generated, process-local preference storage. */
class CountdownConfigMutationTest {
  @Test fun concurrentAddsRetainBothEventsAndSavedNeighbors() {
    val fixture = Fixture(SAVED_EVENTS)
    val existing = CountdownConfig.load(fixture.context)
    race(fixture,
        first = { CountdownConfig.add(fixture.context, "First new", "A", 6, 7, 2028) },
        second = { CountdownConfig.add(fixture.context, "Second new", "B", 8, 9, 0) })
    val saved = CountdownConfig.load(fixture.context)
    assertEquals(existing, saved.take(existing.size))
    assertEquals(listOf("First new", "Second new"), saved.drop(existing.size).map { it.label })
    assertEquals(listOf(Triple(6, 7, 2028), Triple(8, 9, 0)),
        saved.drop(existing.size).map { Triple(it.month, it.day, it.year) })
    assertEquals(2, fixture.writes.get())
  }

  @Test fun addRacingRemovalCannotResurrectTheRemovedEvent() {
    val fixture = Fixture(SAVED_EVENTS)
    val retained = CountdownConfig.load(fixture.context).filterNot { it.id == 13L }
    race(fixture,
        first = { CountdownConfig.add(fixture.context, "New", "N", 10, 11, 2030) },
        second = { CountdownConfig.remove(fixture.context, 13L) })
    val saved = CountdownConfig.load(fixture.context)
    assertEquals(retained, saved.dropLast(1))
    assertEquals("New", saved.last().label)
    assertEquals(2, fixture.writes.get())
  }

  @Test fun removalRacingAddCannotDiscardTheAddedEvent() {
    val fixture = Fixture(SAVED_EVENTS)
    val retained = CountdownConfig.load(fixture.context).filterNot { it.id == 13L }
    race(fixture,
        first = { CountdownConfig.remove(fixture.context, 13L) },
        second = { CountdownConfig.add(fixture.context, "New", "N", 10, 11, 2030) })
    val saved = CountdownConfig.load(fixture.context)
    assertEquals(retained, saved.dropLast(1))
    assertEquals("New", saved.last().label)
    assertEquals(2, fixture.writes.get())
  }

  @Test fun concurrentRemovalsKeepBothDeletions() {
    val fixture = Fixture(SAVED_EVENTS)
    val retained = CountdownConfig.load(fixture.context).filter { it.id == 17L }
    race(fixture,
        first = { CountdownConfig.remove(fixture.context, 11L) },
        second = { CountdownConfig.remove(fixture.context, 13L) })
    assertEquals(retained, CountdownConfig.load(fixture.context))
    assertEquals(2, fixture.writes.get())
  }

  @Test fun ordinaryAddPreservesPayloadOrderAndExistingIds() {
    val fixture = Fixture(SAVED_EVENTS)
    val existing = CountdownConfig.load(fixture.context)
    CountdownConfig.add(fixture.context, "  New date  ", "🌠", 2, 29, 0)
    val saved = CountdownConfig.load(fixture.context)
    assertEquals(existing, saved.dropLast(1))
    val added = saved.last()
    assertEquals(CountdownConfig.Event(added.id, "  New date  ", "🌠", 2, 29, 0), added)
    assertEquals(saved, CountdownConfig.load(Fixture(fixture.raw.get()).context))
    val persisted = JSONArray(fixture.raw.get()).getJSONObject(saved.lastIndex)
    assertTrue(persisted.get("id") is Number)
    assertEquals(1, fixture.writes.get())
  }

  @Test fun ordinaryRemovePreservesNeighborFieldsAndOrder() {
    val fixture = Fixture(SAVED_EVENTS)
    val existing = CountdownConfig.load(fixture.context)
    CountdownConfig.remove(fixture.context, 13L)
    val expected = listOf(existing[0], existing[2])
    assertEquals(expected, CountdownConfig.load(fixture.context))
    assertEquals(expected, CountdownConfig.load(Fixture(fixture.raw.get()).context))
    assertEquals(1, fixture.writes.get())
  }

  @Test fun repeatedReadOnlyLoadsPreserveExactStoredBytes() {
    val fixture = Fixture(SAVED_EVENTS)
    val initial = fixture.raw.get()
    val expected = CountdownConfig.load(fixture.context)
    repeat(3) { assertEquals(expected, CountdownConfig.load(fixture.context)) }
    assertEquals(initial, fixture.raw.get())
    assertEquals(0, fixture.writes.get())
    Mockito.verify(fixture.prefs, Mockito.never()).edit()
  }

  /** Hold the first apply while a competing public mutation reaches its boundary. */
  private fun race(fixture: Fixture, first: () -> Unit, second: () -> Unit) {
    val entered = CountDownLatch(1)
    val release = CountDownLatch(1)
    val claimed = AtomicBoolean()
    fixture.beforeApply = {
      if (claimed.compareAndSet(false, true)) {
        entered.countDown()
        check(release.await(5, TimeUnit.SECONDS)) { "First write was not released" }
      }
    }
    val executor = Executors.newFixedThreadPool(2)
    val secondaryThread = AtomicReference<Thread>()
    val secondaryStarted = CountDownLatch(1)
    fun blockedAtConfig(): Boolean {
      val thread = secondaryThread.get() ?: return false
      if (thread.state != Thread.State.BLOCKED) return false
      val frame = thread.stackTrace.firstOrNull() ?: return false
      return frame.className == CountdownConfig::class.java.name &&
          frame.methodName in setOf("add", "remove")
    }
    try {
      val primary = executor.submit { first() }
      assertTrue("First mutation must reach its write", entered.await(5, TimeUnit.SECONDS))
      val secondary = executor.submit {
        secondaryThread.set(Thread.currentThread())
        secondaryStarted.countDown()
        second()
      }
      assertTrue("Competing mutation must start", secondaryStarted.await(5, TimeUnit.SECONDS))
      // Observe completion or an actual config monitor wait, not an unrelated Mockito lock.
      val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5)
      while (!secondary.isDone && !blockedAtConfig() && System.nanoTime() < deadline) Thread.yield()
      assertTrue("Competing mutation must finish or wait at the config", secondary.isDone || blockedAtConfig())
      release.countDown()
      primary.get(5, TimeUnit.SECONDS)
      secondary.get(5, TimeUnit.SECONDS)
    } finally {
      release.countDown()
      executor.shutdownNow()
      assertTrue("Owned workers must terminate", executor.awaitTermination(5, TimeUnit.SECONDS))
    }
  }

  private class Fixture(initial: String = "[]") {
    val context = Mockito.mock(Context::class.java)
    val prefs = Mockito.mock(SharedPreferences::class.java)
    val raw = AtomicReference(initial)
    val writes = AtomicInteger()
    @Volatile var beforeApply: (() -> Unit)? = null

    init {
      Mockito.`when`(context.getSharedPreferences("immortal_countdown", Context.MODE_PRIVATE))
          .thenReturn(prefs)
      Mockito.`when`(prefs.getString("events", null)).thenAnswer { raw.get() }
      Mockito.`when`(prefs.edit()).thenAnswer {
        val editor = Mockito.mock(SharedPreferences.Editor::class.java, Mockito.RETURNS_SELF)
        val staged = AtomicReference<String>()
        Mockito.`when`(editor.putString(Mockito.eq("events"), Mockito.anyString())).thenAnswer {
          staged.set(it.getArgument(1))
          editor
        }
        Mockito.doAnswer {
          beforeApply?.invoke()
          raw.set(staged.get())
          writes.incrementAndGet()
          null
        }.`when`(editor).apply()
        editor
      }
    }
  }

  private companion object {
    const val SAVED_EVENTS = """[
      {"id":11,"label":"  First saved  ","emoji":"A","month":12,"day":31,"year":2032},
      {"id":13,"label":"Remove","emoji":"R","month":2,"day":29,"year":0},
      {"id":17,"label":"Legacy fields","emoji":"","month":15,"day":-2,"year":-10}
    ]"""
  }
}
