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

/** Exercises real capture identity/deadline guards without starting collectors or using Main. */
class EngineImageResolutionCaptureTest {
  private class Fixture {
    var now = 100L
    var clockReads = 0
    val current = FramePerformanceCapture.javaClass.getDeclaredField("current").apply { isAccessible = true }
    val constructor = Class.forName("com.immortal.launcher.FramePerformanceCapture\$Session")
        .getDeclaredConstructor(java.lang.Long.TYPE, FramePerformanceRequest::class.java).apply { isAccessible = true }
    var nextId = 0L
    fun session(): Any = constructor.newInstance(++nextId, FramePerformanceRequest("start", 10, "slideshow"))
    fun select(session: Any?) { current.set(FramePerformanceCapture, session) }
    fun close(session: Any) { session.javaClass.getDeclaredField("closedAt").apply { isAccessible = true }.set(session, now) }
    fun samples(session: Any) = (session.javaClass.getDeclaredField("imageResolution").apply { isAccessible = true }
        .get(session) as EngineImageResolutionSamples).snapshot()
    fun record(measurement: EngineImageResolutionMeasurement?) = measurement?.record(
        EngineImageResolutionResult.LARGER_TIER_READY, 1200, 2400, 2400)
  }
  private fun fixture(block: (Fixture, List<Handler>) -> Unit) {
    val f = Fixture(); val previous = f.current.get(FramePerformanceCapture)
    val looper = Mockito.mock(Looper::class.java)
    Mockito.mockStatic(SystemClock::class.java).use { clock ->
      clock.`when`<Long> { SystemClock.elapsedRealtime() }.thenAnswer { f.clockReads++; f.now }
      Mockito.mockStatic(Looper::class.java).use { loopers ->
        loopers.`when`<Looper> { Looper.getMainLooper() }.thenReturn(looper)
        // This numeric probe must work on the bridge thread without a Main acceptance callback.
        loopers.`when`<Looper> { Looper.myLooper() }.thenReturn(null)
        Mockito.mockConstruction(HandlerThread::class.java) { thread, _ -> Mockito.`when`(thread.looper).thenReturn(looper) }.use {
          Mockito.mockConstruction(Handler::class.java).use { handlers ->
            try { f.select(null); block(f, handlers.constructed()) } finally { f.select(previous) }
          }
        }
      }
    }
  }

  @Test fun inactiveAndClosedCapturesReadNoClockOrPostCallbacks() = fixture { f, handlers ->
    var calls = 0
    FramePerformanceCapture.measureImageResolution { measurement -> calls++; assertNull(measurement) }
    assertEquals(1, calls); assertEquals(0, f.clockReads); assertTrue(handlers.isEmpty())
    val session = f.session(); f.select(session); f.close(session); f.clockReads = 0
    FramePerformanceCapture.measureImageResolution { assertNull(it) }
    assertEquals(0, f.clockReads)
    handlers.forEach { Mockito.verifyNoInteractions(it) }
  }

  @Test fun activeBridgeThreadResolutionRecordsWithoutPostingMain() = fixture { f, handlers ->
    val session = f.session(); f.select(session)
    val result = Any()
    assertSame(result, FramePerformanceCapture.measureImageResolution { measurement ->
      assertNotNull(measurement); f.record(measurement); result
    })
    assertEquals(1L, f.samples(session).getLong("records"))
    handlers.forEach { Mockito.verifyNoInteractions(it) }
  }

  @Test fun selectionCannotCrossReplacementClosureOrExpiryDuringResolution() = fixture { f, _ ->
    for (change in listOf("replace", "close", "expire")) {
      val first = f.session(); f.select(first); var second: Any? = null
      FramePerformanceCapture.measureImageResolution { measurement ->
        assertNotNull(measurement)
        when (change) {
          "replace" -> { second = f.session(); f.select(second) }
          "close" -> f.close(first)
          "expire" -> f.now += 10_000
        }
        f.record(measurement)
      }
      assertEquals(change, 0L, f.samples(first).getLong("records"))
      second?.let { assertEquals(0L, f.samples(it).getLong("records")) }
    }
  }

  @Test fun expiredCaptureRejectsSelectionAndInactiveStartCannotRecordIntoLaterCapture() = fixture { f, _ ->
    val expired = f.session(); f.select(expired); f.now += 10_000
    FramePerformanceCapture.measureImageResolution { assertNull(it) }
    assertEquals(0L, f.samples(expired).getLong("records"))
    f.select(null)
    var next: Any? = null
    FramePerformanceCapture.measureImageResolution { measurement ->
      assertNull(measurement); next = f.session(); f.select(next); f.record(measurement)
    }
    assertEquals(0L, f.samples(next!!).getLong("records"))
  }
}
