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

class SlideshowStyleCaptureTest {
  private class Fixture {
    var now = 100L
    var clockReads = 0
    var onMain = true
    val current = FramePerformanceCapture.javaClass.getDeclaredField("current").apply { isAccessible = true }
    val constructor = Class.forName("com.immortal.launcher.FramePerformanceCapture\$Session")
        .getDeclaredConstructor(java.lang.Long.TYPE, FramePerformanceRequest::class.java).apply { isAccessible = true }
    var nextId = 0L
    fun session(): Any = constructor.newInstance(++nextId, FramePerformanceRequest("start", 10, "slideshow"))
    fun select(session: Any?) { current.set(FramePerformanceCapture, session) }
    fun close(session: Any) { session.javaClass.getDeclaredField("closedAt").apply { isAccessible = true }.set(session, now) }
    fun samples(session: Any) = (session.javaClass.getDeclaredField("slideshowStyles").apply { isAccessible = true }
        .get(session) as SlideshowStyleSamples).snapshot()
    fun report() = FramePerformanceCapture.prepareSlideshowStyleReport(SlideshowStyleHost.PREVIEW, SlideshowStyle.FILMSTRIP)
  }
  private fun fixture(block: (Fixture) -> Unit) {
    val f = Fixture()
    val previous = f.current.get(FramePerformanceCapture)
    val main = Mockito.mock(Looper::class.java)
    val other = Mockito.mock(Looper::class.java)
    Mockito.mockStatic(SystemClock::class.java).use { clock ->
      clock.`when`<Long> { SystemClock.elapsedRealtime() }.thenAnswer { f.clockReads++; f.now }
      Mockito.mockStatic(Looper::class.java).use { loopers ->
        loopers.`when`<Looper> { Looper.getMainLooper() }.thenReturn(main)
        loopers.`when`<Looper> { Looper.myLooper() }.thenAnswer { if (f.onMain) main else other }
        Mockito.mockConstruction(HandlerThread::class.java) { thread, _ -> Mockito.`when`(thread.looper).thenReturn(main) }.use {
          Mockito.mockConstruction(Handler::class.java).use {
            try { f.select(null); block(f) } finally { f.select(previous) }
          }
        }
      }
    }
  }
  @Test fun inactiveCaptureAvoidsAllClocksAndReports() = fixture { f ->
    assertNull(f.report()); assertEquals(0, f.clockReads)
  }
  @Test fun closedCaptureAvoidsClockReads() = fixture { f ->
    val s = f.session(); f.select(s); f.close(s); f.clockReads = 0
    assertNull(f.report()); assertEquals(0, f.clockReads)
  }
  @Test fun activeReportKeepsBridgeReceiptAndLaterMainAcceptanceSeparateAndRecordsOnce() = fixture { f ->
    val s = f.session(); f.select(s); f.now += 5
    val report = f.report(); assertNotNull(report)
    assertEquals(0L, f.samples(s).getLong("records"))
    f.now += 20; report!!.accept(); report.accept()
    val samples = f.samples(s); assertEquals(1L, samples.getLong("records"))
    val event = samples.getJSONArray("events").getJSONObject(0)
    assertEquals(5L, event.getLong("receivedElapsedMs")); assertEquals(25L, event.getLong("acceptedElapsedMs"))
  }
  @Test fun reportCannotCrossCaptureReplacement() = fixture { f ->
    val first = f.session(); val second = f.session(); f.select(first)
    val report = f.report(); assertNotNull(report)
    f.select(second); report!!.accept()
    assertEquals(0L, f.samples(first).getLong("records")); assertEquals(0L, f.samples(second).getLong("records"))
  }
  @Test fun reportCannotCrossClosureOrExpiry() = fixture { f ->
    for (close in listOf(true, false)) {
      val s = f.session(); f.select(s); val report = f.report(); assertNotNull(report)
      if (close) f.close(s) else f.now += 10_001
      report!!.accept(); assertEquals(0L, f.samples(s).getLong("records"))
    }
  }
  @Test fun expiredCaptureDoesNotAdmitReports() = fixture { f ->
    val s = f.session(); f.select(s); f.now += 10_001
    assertNull(f.report()); assertEquals(0L, f.samples(s).getLong("records"))
  }
  @Test fun acceptanceOnTheBridgeThreadIsRejected() = fixture { f ->
    val s = f.session(); f.select(s); val report = f.report(); assertNotNull(report)
    f.onMain = false; report!!.accept()
    assertEquals(0L, f.samples(s).getLong("records"))
  }

}
