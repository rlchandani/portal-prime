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
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

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
    fun report(observeLayer: () -> SlideshowLayerObservation?) = FramePerformanceCapture.prepareSlideshowStyleReport(
        SlideshowStyleHost.PREVIEW, SlideshowStyle.FILMSTRIP, observeLayer)
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

  @Test fun inactivePrepareDoesNotReadLayerSupplierOrClock() = fixture { f ->
    var layerReads = 0
    assertNull(f.report { layerReads++; null })
    assertEquals(0, layerReads); assertEquals(0, f.clockReads)
  }
  @Test fun closedPrepareDoesNotReadLayerSupplierOrClock() = fixture { f ->
    val s = f.session(); f.select(s); f.close(s); f.clockReads = 0
    var layerReads = 0
    assertNull(f.report { layerReads++; null })
    assertEquals(0, layerReads); assertEquals(0, f.clockReads)
    assertEquals(0L, f.samples(s).getLong("records"))
  }
  @Test fun expiredPrepareDoesNotReadLayerSupplierAtOrAfterDeadline() = fixture { f ->
    for (elapsed in listOf(10_000L, 10_001L)) {
      val s = f.session(); f.select(s); f.now += elapsed
      var layerReads = 0
      assertNull(f.report { layerReads++; null })
      assertEquals(0, layerReads); assertEquals(0L, f.samples(s).getLong("records"))
    }
  }
  @Test fun mainAcceptanceReadsLatestLayerStateOnceAndPreservesBridgeReceipt() = fixture { f ->
    val s = f.session(); f.select(s)
    var layer = SlideshowLayerObservation(layerType = 0, hardwareAccelerated = false,
        attached = false, widthPx = 0, heightPx = 0)
    var layerReads = 0
    var observedOnMain = false
    var observedAt = 0L
    f.onMain = false; f.now += 5
    val report = f.report {
      layerReads++; observedOnMain = f.onMain; observedAt = f.now
      layer
    }
    assertNotNull(report); assertEquals(0, layerReads)
    assertEquals(0L, f.samples(s).getLong("records"))
    layer = SlideshowLayerObservation(layerType = 2, hardwareAccelerated = true,
        attached = true, widthPx = 1920, heightPx = 1080)
    f.onMain = true; f.now += 20; report!!.accept()
    assertEquals(1, layerReads); assertTrue(observedOnMain); assertEquals(f.now, observedAt)
    layer = SlideshowLayerObservation(layerType = 1, hardwareAccelerated = false,
        attached = false, widthPx = 640, heightPx = 480)
    f.now += 30; report.accept()
    assertEquals(1, layerReads)
    val samples = f.samples(s); assertEquals(1L, samples.getLong("records"))
    val events = samples.getJSONArray("events"); assertEquals(1, events.length())
    val event = events.getJSONObject(0)
    assertEquals("PhotoFramePreviewActivity", event.getString("host"))
    assertEquals("filmstrip", event.getString("style"))
    assertEquals(5L, event.getLong("receivedElapsedMs")); assertEquals(25L, event.getLong("acceptedElapsedMs"))
    assertEquals(20L, event.getLong("mainAdmissionDelayMs"))
    val observed = event.getJSONObject("webViewLayer")
    assertEquals(2, observed.getInt("layerType")); assertTrue(observed.getBoolean("hardwareAccelerated"))
    assertTrue(observed.getBoolean("attached"))
    assertEquals(1920, observed.getInt("widthPx")); assertEquals(1080, observed.getInt("heightPx"))
  }
  @Test fun detachedLayerWithZeroBoundsIsStillRecorded() = fixture { f ->
    val s = f.session(); f.select(s)
    val report = f.report {
      SlideshowLayerObservation(layerType = 0, hardwareAccelerated = false,
          attached = false, widthPx = 0, heightPx = 0)
    }
    assertNotNull(report); report!!.accept()
    val samples = f.samples(s); assertEquals(1L, samples.getLong("records"))
    val observed = samples.getJSONArray("events").getJSONObject(0).getJSONObject("webViewLayer")
    assertEquals(0, observed.getInt("layerType")); assertFalse(observed.getBoolean("hardwareAccelerated"))
    assertFalse(observed.getBoolean("attached"))
    assertEquals(0, observed.getInt("widthPx")); assertEquals(0, observed.getInt("heightPx"))
  }
  @Test fun captureReplacementRejectsAcceptanceBeforeReadingLayerSupplier() = fixture { f ->
    val first = f.session(); val second = f.session(); f.select(first)
    var layerReads = 0
    val report = f.report { layerReads++; null }; assertNotNull(report)
    assertEquals(0, layerReads)
    f.select(second); report!!.accept(); report.accept()
    assertEquals(0, layerReads)
    assertEquals(0L, f.samples(first).getLong("records")); assertEquals(0L, f.samples(second).getLong("records"))
  }
  @Test fun captureClosureRejectsAcceptanceBeforeReadingLayerSupplier() = fixture { f ->
    val s = f.session(); f.select(s)
    var layerReads = 0
    val report = f.report { layerReads++; null }; assertNotNull(report)
    assertEquals(0, layerReads)
    f.close(s); report!!.accept(); report.accept()
    assertEquals(0, layerReads); assertEquals(0L, f.samples(s).getLong("records"))
  }
  @Test fun expiredAcceptanceRejectsBeforeReadingLayerSupplierAtOrAfterDeadline() = fixture { f ->
    for (elapsed in listOf(10_000L, 10_001L)) {
      val s = f.session(); f.select(s)
      var layerReads = 0
      val report = f.report { layerReads++; null }; assertNotNull(report)
      assertEquals(0, layerReads)
      f.now += elapsed; report!!.accept(); report.accept()
      assertEquals(0, layerReads); assertEquals(0L, f.samples(s).getLong("records"))
    }
  }
  @Test fun expiryWhileMainWaitsForSessionMonitorRejectsLayerObservation() = fixture { f ->
    val s = f.session(); f.select(s)
    var layerReads = 0
    val report = f.report { layerReads++; null }; assertNotNull(report)
    val caller = Thread.currentThread()
    val monitorHeld = CountDownLatch(1)
    val acceptanceStarting = CountDownLatch(1)
    val workerFailure = AtomicReference<Throwable?>(null)
    val holder = Thread({
      try {
        synchronized(s) {
          monitorHeld.countDown()
          check(acceptanceStarting.await(5, TimeUnit.SECONDS)) { "Main did not begin acceptance" }
          val waitDeadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5)
          while (caller.state != Thread.State.BLOCKED) {
            check(System.nanoTime() < waitDeadline) { "Main did not block on the session monitor" }
            Thread.sleep(1)
          }
          // No mocked Android calls on this worker. Unlock publishes the clock advance to Main.
          f.now += 10_001
        }
      } catch (failure: Throwable) {
        workerFailure.set(failure)
      }
    }, "slideshow-style-monitor-contention").apply { isDaemon = true }
    holder.start()
    try {
      assertTrue("Worker did not acquire the session monitor", monitorHeld.await(5, TimeUnit.SECONDS))
      acceptanceStarting.countDown()
      report!!.accept()
    } finally {
      acceptanceStarting.countDown()
      holder.interrupt()
      holder.join(5_000)
      assertFalse("Session monitor worker did not terminate", holder.isAlive)
    }
    workerFailure.get()?.let { throw AssertionError("Session monitor coordination failed", it) }
    assertEquals(0, layerReads)
    assertEquals(0L, f.samples(s).getLong("records"))
  }
  @Test fun wrongThreadAcceptanceNeverReadsLayerSupplierEvenIfRetriedOnMain() = fixture { f ->
    val s = f.session(); f.select(s)
    var layerReads = 0
    val report = f.report { layerReads++; null }; assertNotNull(report)
    assertEquals(0, layerReads)
    f.onMain = false; report!!.accept()
    assertEquals(0, layerReads); assertEquals(0L, f.samples(s).getLong("records"))
    f.onMain = true; report.accept()
    assertEquals(0, layerReads); assertEquals(0L, f.samples(s).getLong("records"))
  }
  @Test fun nullLayerSupplierStillRecordsStyleOnceWithoutLayer() = fixture { f ->
    val s = f.session(); f.select(s)
    var layerReads = 0
    val report = f.report { layerReads++; null }; assertNotNull(report)
    assertEquals(0, layerReads)
    report!!.accept(); report.accept()
    assertEquals(1, layerReads)
    val samples = f.samples(s); assertEquals(1L, samples.getLong("records"))
    val events = samples.getJSONArray("events"); assertEquals(1, events.length())
    val event = events.getJSONObject(0)
    assertEquals("filmstrip", event.getString("style")); assertFalse(event.has("webViewLayer"))
    assertEquals(1L, samples.getJSONObject("styles").getJSONObject("filmstrip").getLong("previewRecords"))
  }
  @Test fun failingLayerSupplierStillRecordsStyleOnceWithoutLayer() = fixture { f ->
    val s = f.session(); f.select(s)
    var layerReads = 0
    val report = f.report { layerReads++; error("synthetic layer observation failure") }; assertNotNull(report)
    assertEquals(0, layerReads)
    f.now += 20; report!!.accept(); report.accept()
    assertEquals(1, layerReads)
    val samples = f.samples(s); assertEquals(1L, samples.getLong("records"))
    val events = samples.getJSONArray("events"); assertEquals(1, events.length())
    val event = events.getJSONObject(0)
    assertEquals("PhotoFramePreviewActivity", event.getString("host"))
    assertEquals("filmstrip", event.getString("style")); assertFalse(event.has("webViewLayer"))
    assertEquals(0L, event.getLong("receivedElapsedMs")); assertEquals(20L, event.getLong("acceptedElapsedMs"))
    assertEquals(1L, samples.getJSONObject("styles").getJSONObject("filmstrip").getLong("previewRecords"))
  }

  private class SuspendedQueue(private val f: Fixture) {
    var ownerCurrent = true
    private val wrappers = mutableListOf<Runnable>()
    val queue = PausableFrameCallbacks(clock = { f.now },
        schedule = { wrapper, _ -> wrappers.add(wrapper); true },
        cancel = { wrapper -> wrappers.removeAll { it === wrapper } })
    val reporter = SlideshowStyleReporter(SlideshowStyleHost.PREVIEW,
        prepare = FramePerformanceCapture::prepareSlideshowStyleReport,
        postMain = { task -> queue.post { task() } }, isCurrent = { ownerCurrent })
    fun drain() {
      val pending = wrappers.toList(); wrappers.clear(); pending.forEach { it.run() }
    }
  }

  @Test fun realPausedQueueResumePreservesOriginalReceiptAndIncludesSuspensionInAdmissionDelay() = fixture { f ->
    val s = f.session(); f.select(s); val q = SuspendedQueue(f)
    f.now += 20; q.reporter.receive("filmstrip"); q.queue.setActive(false)
    assertEquals(1, q.queue.pendingCount)
    f.now += 3_000; q.drain(); assertEquals(0L, f.samples(s).getLong("records"))
    q.queue.setActive(true); q.drain(); q.drain()
    val snapshot = f.samples(s); assertEquals(1L, snapshot.getLong("records"))
    val event = snapshot.getJSONArray("events").getJSONObject(0)
    assertEquals(20L, event.getLong("receivedElapsedMs")); assertEquals(3_020L, event.getLong("acceptedElapsedMs"))
    assertEquals(3_000L, event.getLong("mainAdmissionDelayMs")); assertEquals(0, q.queue.pendingCount)
  }

  @Test fun realPausedQueueRejectsReportsAfterCaptureReplacementClosureExpiryOrOwnerRetirement() = fixture { f ->
    for (change in listOf("replace", "close", "expire", "retire")) {
      val first = f.session(); f.select(first); val q = SuspendedQueue(f)
      q.reporter.receive("filmstrip"); q.queue.setActive(false)
      val replacement = if (change == "replace") f.session().also { f.select(it) } else null
      if (change == "close") f.close(first)
      if (change == "expire") f.now += 10_001
      if (change == "retire") q.ownerCurrent = false
      q.queue.setActive(true); q.drain()
      assertEquals(0L, f.samples(first).getLong("records"))
      if (replacement != null) assertEquals(0L, f.samples(replacement).getLong("records"))
    }
  }

  @Test fun closingTheRealPausedQueuePermanentlyDiscardsPendingStyleReport() = fixture { f ->
    val s = f.session(); f.select(s); val q = SuspendedQueue(f)
    q.reporter.receive("filmstrip"); q.queue.setActive(false); q.queue.close()
    q.queue.setActive(true); q.drain()
    assertEquals(0, q.queue.pendingCount); assertEquals(0L, f.samples(s).getLong("records"))
  }

}
