/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */
package com.immortal.launcher

import android.os.Handler
import android.os.HandlerThread
import android.os.Looper
import android.os.SystemClock
import org.junit.Assert.*
import org.junit.Test
import org.mockito.Mockito

/** Real capture-owned admission; only Android thread/clock boundaries are mocked. */
class SettingsSearchPreparationCaptureTest {
  private class Fixture {
    var elapsedMs = 100L
    var elapsedReads = 0
    var nanoReads = 0
    var onMain = true
    var throwNano = false
    val current = FramePerformanceCapture.javaClass.getDeclaredField("current").apply { isAccessible = true }
    val constructor = Class.forName("com.immortal.launcher.FramePerformanceCapture\$Session")
        .getDeclaredConstructor(java.lang.Long.TYPE, FramePerformanceRequest::class.java)
        .apply { isAccessible = true }
    private var nextId = 0L
    fun session(): Any = constructor.newInstance(++nextId, FramePerformanceRequest("start", 10, "settings"))
    fun select(session: Any?) { current.set(FramePerformanceCapture, session) }
    fun close(session: Any) {
      session.javaClass.getDeclaredField("closedAt").apply { isAccessible = true }.set(session, elapsedMs)
    }
    fun samples(session: Any): org.json.JSONObject =
        (session.javaClass.getDeclaredField("settingsSearchPreparation").apply { isAccessible = true }
            .get(session) as SettingsSearchPreparationSamples).snapshot()
    fun resetCounters() { elapsedReads = 0; nanoReads = 0 }
  }

  private fun withFixture(block: (Fixture) -> Unit) {
    val f = Fixture()
    val previous = f.current.get(FramePerformanceCapture)
    val main = Mockito.mock(Looper::class.java)
    val other = Mockito.mock(Looper::class.java)
    Mockito.mockStatic(SystemClock::class.java).use { clock ->
      clock.`when`<Long> { SystemClock.elapsedRealtime() }.thenAnswer { f.elapsedReads++; f.elapsedMs }
      clock.`when`<Long> { SystemClock.elapsedRealtimeNanos() }.thenAnswer {
        f.nanoReads++
        if (f.throwNano) throw IllegalStateException("synthetic diagnostic clock failure")
        1_000_000_000L + (f.nanoReads - 1) * 2_500_000L
      }
      Mockito.mockStatic(Looper::class.java).use { loopers ->
        loopers.`when`<Looper> { Looper.getMainLooper() }.thenReturn(main)
        loopers.`when`<Looper> { Looper.myLooper() }.thenAnswer { if (f.onMain) main else other }
        Mockito.mockConstruction(HandlerThread::class.java) { thread, _ ->
          Mockito.`when`(thread.looper).thenReturn(main)
        }.use {
          Mockito.mockConstruction(Handler::class.java).use {
            try { f.select(null); block(f) } finally { f.select(previous) }
          }
        }
      }
    }
  }

  @Test fun inactiveCaptureDoesNotReadEitherClock() = withFixture { f ->
    val expected = listOf("synthetic metadata")
    assertSame(expected, FramePerformanceCapture.measureSettingsSearchIndex { expected })
    assertEquals(0, f.elapsedReads)
    assertEquals(0, f.nanoReads)
  }

  @Test fun closedCaptureDoesNotReadEitherClock() = withFixture { f ->
    val s = f.session(); f.select(s); f.close(s); f.resetCounters()
    val expected = listOf(1)
    assertSame(expected, FramePerformanceCapture.measureSettingsSearchIndex { expected })
    assertEquals(0, f.elapsedReads)
    assertEquals(0, f.nanoReads)
    assertEquals(0L, f.samples(s).getLong("records"))
  }

  @Test fun alreadyExpiredCaptureDoesNotStartTiming() = withFixture { f ->
    val s = f.session(); f.select(s); f.elapsedMs += 10_001; f.resetCounters()
    val expected = listOf(1)
    assertSame(expected, FramePerformanceCapture.measureSettingsSearchIndex { expected })
    assertEquals(0, f.nanoReads)
    assertEquals(0L, f.samples(s).getLong("records"))
  }

  @Test fun activeCaptureRecordsDurationAndEntryCount() = withFixture { f ->
    val s = f.session(); f.select(s); f.resetCounters()
    val expected = listOf(1, 2, 3)
    assertSame(expected, FramePerformanceCapture.measureSettingsSearchIndex { expected })
    val snapshot = f.samples(s)
    assertEquals(1L, snapshot.getLong("records"))
    assertEquals(2, f.nanoReads)
    val sample = snapshot.getJSONArray("samples").getJSONObject(0)
    assertEquals(2.5, sample.getDouble("durationMs"), 0.0)
    assertEquals(3, sample.getInt("entryCount"))
    assertTrue(sample.getBoolean("onMain"))
  }

  @Test fun otherThreadPreparationIsDistinguished() = withFixture { f ->
    val s = f.session(); f.select(s); f.resetCounters(); f.onMain = false
    FramePerformanceCapture.measureSettingsSearchIndex { listOf(1) }
    val snapshot = f.samples(s)
    assertEquals(1L, snapshot.getLong("otherThreadRecords"))
    assertEquals(0L, snapshot.getLong("mainThreadRecords"))
  }

  @Test fun replacementDuringPreparationCannotRecordIntoEitherSession() = withFixture { f ->
    val original = f.session(); val replacement = f.session(); f.select(original); f.resetCounters()
    val expected = listOf(1)
    assertSame(expected, FramePerformanceCapture.measureSettingsSearchIndex { f.select(replacement); expected })
    assertEquals(0L, f.samples(original).getLong("records"))
    assertEquals(0L, f.samples(replacement).getLong("records"))
  }

  @Test fun closureDuringPreparationCannotAppendAfterStop() = withFixture { f ->
    val s = f.session(); f.select(s); f.resetCounters()
    val expected = listOf(1)
    assertSame(expected, FramePerformanceCapture.measureSettingsSearchIndex { f.close(s); expected })
    assertEquals(0L, f.samples(s).getLong("records"))
  }

  @Test fun expiryDuringPreparationCannotAppendPastDeadline() = withFixture { f ->
    val s = f.session(); f.select(s); f.resetCounters()
    val expected = listOf(1)
    assertSame(expected, FramePerformanceCapture.measureSettingsSearchIndex { f.elapsedMs += 10_001; expected })
    assertEquals(0L, f.samples(s).getLong("records"))
  }

  @Test fun diagnosticTimerFailureDoesNotPreventPreparation() = withFixture { f ->
    val s = f.session(); f.select(s); f.resetCounters(); f.throwNano = true
    var prepared = 0
    val expected = listOf(1)
    assertSame(expected, FramePerformanceCapture.measureSettingsSearchIndex { prepared++; expected })
    assertEquals(1, prepared)
    assertEquals(0L, f.samples(s).getLong("records"))
  }

  @Test fun existingIndexBuilderUsesTheCaptureOwnedHook() = withFixture { f ->
    val s = f.session(); f.select(s); f.resetCounters()
    val index = approvedSettingsSearchIndex()
    val snapshot = f.samples(s)
    assertEquals(1L, snapshot.getLong("records"))
    assertEquals(index.size, snapshot.getJSONArray("samples").getJSONObject(0).getInt("entryCount"))
    assertEquals(2, f.nanoReads)
  }
}
