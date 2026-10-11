/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */
package com.immortal.launcher

import java.util.concurrent.CountDownLatch
import java.util.concurrent.atomic.AtomicInteger
import org.junit.Assert.*
import org.junit.Test

class SettingsPaneWorkProbeTest {
  @Test fun disabledMeasurementReadsNoTimestampForActiveOrInactivePanes() {
    var providers = 0
    var clocks = 0
    val probe = SettingsPaneWorkProbe({ _, _, _, _, _, _ -> providers++; null },
        { clocks++; error("Disabled diagnostics must not read the clock") })

    assertNull(probe.begin(SettingsPaneWorkId.APPEARANCE, true, 0, 100, 0, 200))
    assertNull(probe.begin(SettingsPaneWorkId.APPEARANCE, false, 0, 100, 0, 200))
    assertEquals(2, providers)
    assertEquals(0, clocks)
  }

  @Test fun measurementCapturesPaneActivityAndAllConstraintsBeforeStartTimestamp() {
    val events = mutableListOf<String>()
    val selected = mutableListOf<List<Any>>()
    val durations = mutableListOf<Long>()
    var time = 5L
    val probe = SettingsPaneWorkProbe({ pane, active, minWidth, maxWidth, minHeight, maxHeight ->
      events.add("select")
      selected.add(listOf(pane, active, minWidth, maxWidth, minHeight, maxHeight))
      SettingsPaneWorkMeasurement { durations.add(it); events.add("record") }
    }, { events.add("clock"); time })

    val span = probe.begin(SettingsPaneWorkId.WALLPAPER, false, 10, Int.MAX_VALUE, 20, 200)
    assertNotNull(span)
    assertEquals(listOf(listOf(SettingsPaneWorkId.WALLPAPER, false, 10, Int.MAX_VALUE, 20, 200)), selected)
    assertEquals(listOf("select", "clock"), events)
    assertTrue(durations.isEmpty())
    time = 25L
    span!!.finish()
    assertEquals(listOf(20L), durations)
    assertEquals(listOf("select", "clock", "clock", "record"), events)
  }

  @Test fun finishUsesCapturedMeasurementAfterSelectionChangesOrIsDisabled() {
    val firstDurations = mutableListOf<Long>()
    val secondDurations = mutableListOf<Long>()
    var selected: SettingsPaneWorkMeasurement? = SettingsPaneWorkMeasurement { firstDurations.add(it) }
    var providers = 0
    var clocks = 0
    var time = 10L
    val probe = SettingsPaneWorkProbe({ _, _, _, _, _, _ -> providers++; selected },
        { clocks++; time })
    val first = startedSpan(probe)
    selected = SettingsPaneWorkMeasurement { secondDurations.add(it) }
    time = 20L
    val second = startedSpan(probe)
    selected = null
    assertNull(probe.begin(SettingsPaneWorkId.TOUCH, true, 0, 100, 0, 200))
    time = 50L
    first.finish()
    second.finish()

    assertEquals(listOf(40L), firstDurations)
    assertEquals(listOf(30L), secondDurations)
    assertEquals(3, providers)
    assertEquals(4, clocks)
  }

  @Test fun startClockCannotReplaceTheAlreadyCapturedMeasurement() {
    val original = mutableListOf<Long>()
    val replacement = mutableListOf<Long>()
    var selected = SettingsPaneWorkMeasurement { original.add(it) }
    var clocks = 0
    val probe = SettingsPaneWorkProbe({ _, _, _, _, _, _ -> selected }, {
      selected = SettingsPaneWorkMeasurement { replacement.add(it) }
      (++clocks * 10).toLong()
    })

    startedSpan(probe).finish()
    assertEquals(listOf(10L), original)
    assertTrue(replacement.isEmpty())
  }

  @Test fun finishingTwiceRecordsExactlyOnceAndDoesNotReadClockAgain() {
    val durations = mutableListOf<Long>()
    var providers = 0
    var clocks = 0
    val probe = SettingsPaneWorkProbe({ _, _, _, _, _, _ ->
      providers++; SettingsPaneWorkMeasurement { durations.add(it) }
    }, { (++clocks * 10).toLong() })

    val span = startedSpan(probe)
    span.finish()
    span.finish()
    assertEquals(listOf(10L), durations)
    assertEquals(1, providers)
    assertEquals(2, clocks)
  }

  @Test fun concurrentFinishRecordsExactlyOnce() {
    val records = AtomicInteger()
    val clocks = AtomicInteger()
    val probe = SettingsPaneWorkProbe({ _, _, _, _, _, _ ->
      SettingsPaneWorkMeasurement { records.incrementAndGet() }
    }, { clocks.incrementAndGet().toLong() })
    val span = startedSpan(probe)
    val start = CountDownLatch(1)
    val finishers = List(8) { Thread { start.await(); span.finish() } }
    finishers.forEach { it.start() }
    start.countDown()
    finishers.forEach { it.join(5_000L); assertFalse("Finisher did not return", it.isAlive) }

    assertEquals(1, records.get())
    assertEquals(2, clocks.get())
  }

  @Test fun providerFailureProducesNoSpanAndReadsNoTimestamp() {
    var clocks = 0
    val probe = SettingsPaneWorkProbe({ _, _, _, _, _, _ -> error("Synthetic provider failure") },
        { clocks++; 0L })

    assertNull(probe.begin(SettingsPaneWorkId.OTHER, false, 0, 100, 0, 200))
    assertEquals(0, clocks)
  }

  @Test fun startTimestampFailureProducesNoSpanAndCannotReachMeasurement() {
    var providers = 0
    var records = 0
    var clocks = 0
    val probe = SettingsPaneWorkProbe({ _, _, _, _, _, _ ->
      providers++; SettingsPaneWorkMeasurement { records++ }
    }, { clocks++; error("Synthetic start clock failure") })

    assertNull(probe.begin(SettingsPaneWorkId.TOUCH, true, 0, 100, 0, 200))
    assertEquals(1, providers)
    assertEquals(1, clocks)
    assertEquals(0, records)
  }

  @Test fun finishTimestampFailureCannotEscapeOrRetryOrReachMeasurement() {
    var clocks = 0
    var records = 0
    val probe = SettingsPaneWorkProbe({ _, _, _, _, _, _ -> SettingsPaneWorkMeasurement { records++ } }, {
      if (++clocks == 1) 5L else error("Synthetic end clock failure")
    })

    val span = startedSpan(probe)
    span.finish()
    span.finish()
    assertEquals(2, clocks)
    assertEquals(0, records)
  }

  @Test fun measurementFailureCannotEscapeOrRetry() {
    var clocks = 0
    var records = 0
    val probe = SettingsPaneWorkProbe({ _, _, _, _, _, _ -> SettingsPaneWorkMeasurement {
      records++; error("Synthetic sink failure")
    } }, { (++clocks).toLong() })

    val span = startedSpan(probe)
    span.finish()
    span.finish()
    assertEquals(1, records)
    assertEquals(2, clocks)
  }

  @Test fun rawNegativeDeltaReachesCollectorValidationWithoutClamping() {
    val samples = SettingsPaneWorkSamples()
    var time = 9L
    val probe = SettingsPaneWorkProbe({ pane, active, minWidth, maxWidth, minHeight, maxHeight ->
      SettingsPaneWorkMeasurement { durationNs ->
        samples.record(pane, active, 0L, durationNs, minWidth, maxWidth, minHeight, maxHeight, true)
      }
    }, { time })

    val span = startedSpan(probe)
    time = 4L
    span.finish()
    val snapshot = samples.snapshot()
    assertEquals(0L, snapshot.getLong("records"))
    assertEquals(1L, snapshot.getLong("invalidRecords"))
    assertEquals(0, snapshot.getJSONArray("samples").length())
  }

  @Test fun monotonicTimestampWrapStillProducesCorrectSmallDelta() {
    val durations = mutableListOf<Long>()
    var time = Long.MAX_VALUE - 4L
    val probe = SettingsPaneWorkProbe({ _, _, _, _, _, _ ->
      SettingsPaneWorkMeasurement { durations.add(it) }
    }, { time })

    val span = startedSpan(probe)
    time = Long.MIN_VALUE + 5L
    span.finish()
    assertEquals(listOf(10L), durations)
  }

  @Test fun nestedSpansReportInclusiveWallTime() {
    val samples = SettingsPaneWorkSamples()
    var time = 0L
    val probe = SettingsPaneWorkProbe({ pane, active, minWidth, maxWidth, minHeight, maxHeight ->
      SettingsPaneWorkMeasurement { durationNs ->
        samples.record(pane, active, 0L, durationNs, minWidth, maxWidth, minHeight, maxHeight, true)
      }
    }, { time })

    val outer = startedSpan(probe)
    time = 5_000_000L
    val inner = startedSpan(probe)
    time = 11_000_000L
    inner.finish()
    time = 20_000_000L
    outer.finish()
    val snapshot = samples.snapshot()
    val retained = snapshot.getJSONArray("samples")
    assertEquals(2, retained.length())
    assertEquals(6.0, retained.getJSONObject(0).getDouble("durationMs"), 0.0)
    assertEquals(20.0, retained.getJSONObject(1).getDouble("durationMs"), 0.0)
    val totals = snapshot.getJSONObject("panes").getJSONObject("touch").getJSONObject("active")
    assertEquals(26.0, totals.getDouble("totalMs"), 0.0)
    assertTrue(snapshot.getString("measurement").contains("not additive"))
  }

  private fun startedSpan(probe: SettingsPaneWorkProbe): SettingsPaneWorkSpan {
    val span = probe.begin(SettingsPaneWorkId.TOUCH, true, 0, 100, 0, 200)
    assertNotNull("Enabled diagnostics must admit a timing span", span)
    return span!!
  }
}
