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

class SlideshowWorkCaptureTest {
  private fun startedSpan(stage: SlideshowWorkStage): SlideshowWorkSpan {
    val span = FramePerformanceCapture.beginSlideshowWork(stage)
    assertNotNull("Active timing span must be admitted", span)
    return span!!
  }

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
    fun session(): Any = constructor.newInstance(++nextId, FramePerformanceRequest("start", 10, "slideshow"))
    fun select(session: Any?) { current.set(FramePerformanceCapture, session) }
    fun close(session: Any) {
      session.javaClass.getDeclaredField("closedAt").apply { isAccessible = true }.set(session, elapsedMs)
    }
    fun samples(session: Any): org.json.JSONObject =
        (session.javaClass.getDeclaredField("slideshowWork").apply { isAccessible = true }
            .get(session) as SlideshowWorkSamples).snapshot()
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

  @Test fun inactiveAndClosedCaptureReadNoClocks() = withFixture { f ->
    assertFalse(FramePerformanceCapture.slideshowWorkEnabled())
    assertNull(FramePerformanceCapture.beginSlideshowWork(SlideshowWorkStage.METADATA_PREPARATION))
    FramePerformanceCapture.markSlideshowWork(SlideshowWorkStage.ENGINE_PAGE_FINISHED)
    assertEquals(0, f.elapsedReads); assertEquals(0, f.nanoReads)
    val s = f.session(); f.select(s); f.close(s); f.resetCounters()
    assertFalse(FramePerformanceCapture.slideshowWorkEnabled())
    assertNull(FramePerformanceCapture.beginSlideshowWork(SlideshowWorkStage.METADATA_PREPARATION))
    FramePerformanceCapture.markSlideshowWork(SlideshowWorkStage.ENGINE_PAGE_FINISHED)
    assertEquals(0, f.elapsedReads); assertEquals(0, f.nanoReads)
  }

  @Test fun expiredCaptureCannotStartSpan() = withFixture { f ->
    val s = f.session(); f.select(s); f.elapsedMs += 10_001; f.resetCounters()
    assertFalse(FramePerformanceCapture.slideshowWorkEnabled())
    assertNull(FramePerformanceCapture.beginSlideshowWork(SlideshowWorkStage.METADATA_PREPARATION))
    assertEquals(0, f.nanoReads)
    assertEquals(0L, f.samples(s).getLong("records"))
  }

  @Test fun activeCaptureRecordsRealSpanAndMainThreadMetadata() = withFixture { f ->
    val s = f.session(); f.select(s); f.resetCounters()
    assertTrue(FramePerformanceCapture.slideshowWorkEnabled())
    val span = startedSpan(SlideshowWorkStage.METADATA_PREPARATION)
    assertEquals(0L, f.samples(s).getLong("records"))
    span.finish()
    val snapshot = f.samples(s)
    assertEquals(1L, snapshot.getLong("records"))
    val sample = snapshot.getJSONArray("samples").getJSONObject(0)
    assertEquals("metadataPreparation", sample.getString("stage"))
    assertEquals(2.5, sample.getDouble("durationMs"), 0.0)
    assertTrue(sample.getBoolean("onMain"))
  }

  @Test fun suppliedWorkerLooperAttributionIsRecorded() = withFixture { f ->
    val s = f.session(); f.select(s); f.onMain = false
    val span = startedSpan(SlideshowWorkStage.METADATA_PREPARATION)
    span.finish(); span.finish()
    val snapshot = f.samples(s)
    assertEquals(1L, snapshot.getLong("records"))
    val stage = snapshot.getJSONObject("stages").getJSONObject("metadataPreparation")
    assertEquals(0L, stage.getLong("mainThreadRecords"))
    assertEquals(1L, stage.getLong("otherThreadRecords"))
  }

  @Test fun replacementBeforeFinishCannotWriteToOldOrNewSession() = withFixture { f ->
    val first = f.session(); val second = f.session(); f.select(first)
    val span = startedSpan(SlideshowWorkStage.WEB_VIEW_ADMISSION)
    f.select(second); span.finish()
    assertEquals(0L, f.samples(first).getLong("records"))
    assertEquals(0L, f.samples(second).getLong("records"))
  }

  @Test fun closureBeforeFinishDiscardsObservation() = withFixture { f ->
    val s = f.session(); f.select(s)
    val span = startedSpan(SlideshowWorkStage.METADATA_PREPARATION)
    f.close(s); span.finish()
    assertEquals(0L, f.samples(s).getLong("records"))
  }

  @Test fun deadlineBeforeFinishDiscardsObservation() = withFixture { f ->
    val s = f.session(); f.select(s)
    val span = startedSpan(SlideshowWorkStage.WEB_VIEW_ADMISSION)
    f.elapsedMs += 10_001; span.finish()
    assertEquals(0L, f.samples(s).getLong("records"))
  }

  @Test fun eventRecordsTimestampAndOtherThreadWithoutNanoClock() = withFixture { f ->
    val s = f.session(); f.select(s); f.resetCounters(); f.onMain = false; f.elapsedMs += 3
    FramePerformanceCapture.markSlideshowWork(SlideshowWorkStage.ENGINE_PAGE_FINISHED)
    val sample = f.samples(s).getJSONArray("samples").getJSONObject(0)
    assertEquals("enginePageFinished", sample.getString("stage"))
    assertEquals(3L, sample.getLong("elapsedMs"))
    assertEquals(0.0, sample.getDouble("durationMs"), 0.0)
    assertFalse(sample.getBoolean("onMain"))
    assertEquals(0, f.nanoReads)
  }

  @Test fun startClockFailureDoesNotCreateSpanOrRecord() = withFixture { f ->
    val s = f.session(); f.select(s); f.throwNano = true
    assertNull(FramePerformanceCapture.beginSlideshowWork(SlideshowWorkStage.METADATA_PREPARATION))
    assertEquals(0L, f.samples(s).getLong("records"))
  }
}
