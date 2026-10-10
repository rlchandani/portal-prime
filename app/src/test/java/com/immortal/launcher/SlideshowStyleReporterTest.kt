/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */
package com.immortal.launcher

import org.junit.Assert.*
import org.junit.Test

class SlideshowStyleReporterTest {
  private class Fixture {
    var active = true
    var current = true
    var accepted = 0
    var prepared = 0
    val queued = mutableListOf<() -> Unit>()
    val reporter = SlideshowStyleReporter(SlideshowStyleHost.DREAM,
        prepare = { host, style ->
          prepared++
          assertEquals(SlideshowStyleHost.DREAM, host)
          assertEquals(SlideshowStyle.FILMSTRIP, style)
          if (active) SlideshowStyleReport { accepted++ } else null
        }, postMain = { queued.add(it) }, isCurrent = { current })
    fun drain() { val tasks = queued.toList(); queued.clear(); tasks.forEach { it() } }
  }
  @Test fun currentEngineReportsAfterGuardedMainAdmission() {
    val f = Fixture(); f.reporter.receive("filmstrip")
    assertEquals(1, f.prepared); assertEquals(1, f.queued.size); assertEquals(0, f.accepted)
    f.drain(); assertEquals(1, f.accepted)
  }
  @Test fun retiredReplacedOrPausedEngineCannotReportAfterQueuedCallback() {
    val f = Fixture(); f.reporter.receive("filmstrip"); f.current = false; f.drain()
    assertEquals(0, f.accepted)
  }
  @Test fun unknownIdCannotReachTheCaptureSinkOrMainQueue() {
    val f = Fixture(); f.reporter.receive("file:///synthetic.jpg")
    assertEquals(0, f.prepared); assertEquals(0, f.queued.size)
  }
  @Test fun inactiveCaptureSchedulesNoMainWork() {
    val f = Fixture(); f.active = false; f.reporter.receive("filmstrip")
    assertEquals(1, f.prepared); assertEquals(0, f.queued.size)
  }
  @Test fun captureAndPostingFailuresAreNonFatalToTheBridgeCaller() {
    for (postFails in listOf(true, false)) {
      val reporter = SlideshowStyleReporter(SlideshowStyleHost.UNKNOWN,
          prepare = { _, _ -> if (!postFails) error("synthetic capture failure") else SlideshowStyleReport {} },
          postMain = { error("synthetic queue failure") }, isCurrent = { true })
      reporter.receive("filmstrip")
    }
  }
  @Test fun guardAndAcceptanceFailuresAreNonFatalToTheMainCallback() {
    for (guardFails in listOf(true, false)) {
      val queued = mutableListOf<() -> Unit>()
      val reporter = SlideshowStyleReporter(SlideshowStyleHost.UNKNOWN,
          prepare = { _, _ -> SlideshowStyleReport { error("synthetic sink failure") } },
          postMain = { queued.add(it) }, isCurrent = { if (guardFails) error("synthetic guard failure") else true })
      reporter.receive("filmstrip"); assertEquals(1, queued.size); queued.single()()
    }
  }
}
