/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */
package com.immortal.launcher

import org.junit.Assert.*
import org.junit.Test

class SlideshowWorkProbeTest {
  @Test fun inactiveMeasurementsReadNoClockAndNeverInvokeSink() {
    var providers = 0; var clocks = 0; var sinks = 0
    val active = false
    val sink = SlideshowWorkMeasurement { sinks++ }
    val probe = SlideshowWorkProbe({ providers++; if (active) sink else null }, { clocks++; 0L })
    assertNull(probe.begin(SlideshowWorkStage.METADATA_PREPARATION))
    assertNull(probe.begin(SlideshowWorkStage.WEB_VIEW_ADMISSION))
    probe.mark(SlideshowWorkStage.ENGINE_PAGE_FINISHED)
    probe.mark(SlideshowWorkStage.ENGINE_RETIRED)
    assertEquals(4, providers)
    assertEquals(0, clocks)
    assertEquals(0, sinks)
  }

  @Test fun activeSpanSelectsBeforeClockAndEmitsOnlyAtFinish() {
    val order = ArrayList<String>()
    val durations = ArrayList<Long>()
    var time = 5L
    val probe = SlideshowWorkProbe({ stage ->
      order.add(stage.key)
      SlideshowWorkMeasurement { durations.add(it); order.add("sink") }
    }, { order.add("clock"); time })
    val span = started(probe, SlideshowWorkStage.METADATA_PREPARATION)
    assertEquals(listOf("metadataPreparation", "clock"), order)
    assertTrue(durations.isEmpty())
    time = 25L
    span.finish()
    assertEquals(listOf(20L), durations)
    assertEquals(listOf("metadataPreparation", "clock", "clock", "sink"), order)
  }

  @Test fun overlappingSpansKeepIndependentStartTimesAndIdempotentFinishes() {
    val durations = ArrayList<Pair<SlideshowWorkStage, Long>>()
    var time = 5L
    val probe = SlideshowWorkProbe({ stage -> SlideshowWorkMeasurement { durations.add(stage to it) } }, { time })
    val first = started(probe, SlideshowWorkStage.METADATA_PREPARATION)
    time = 10L
    val second = started(probe, SlideshowWorkStage.WEB_VIEW_ADMISSION)
    time = 20L; first.finish(); first.finish()
    time = 25L; second.finish(); second.finish()
    assertEquals(listOf(SlideshowWorkStage.METADATA_PREPARATION to 15L,
        SlideshowWorkStage.WEB_VIEW_ADMISSION to 15L), durations)
  }

  @Test fun eventMarksRecordOnlyZeroAndNeverReadNanoClock() {
    val stages = ArrayList<SlideshowWorkStage>()
    val durations = ArrayList<Long>()
    var clocks = 0
    val probe = SlideshowWorkProbe({ stage -> stages.add(stage); SlideshowWorkMeasurement { durations.add(it) } },
        { clocks++; 0L })
    probe.mark(SlideshowWorkStage.ENGINE_PAGE_FINISHED)
    probe.mark(SlideshowWorkStage.ENGINE_RETIRED)
    assertEquals(listOf(SlideshowWorkStage.ENGINE_PAGE_FINISHED, SlideshowWorkStage.ENGINE_RETIRED), stages)
    assertEquals(listOf(0L, 0L), durations)
    assertEquals(0, clocks)
  }

  @Test fun eventSpansAndElapsedMarksHaveNoDiagnosticSideEffects() {
    var providers = 0; var clocks = 0; var sinks = 0
    val probe = SlideshowWorkProbe({ providers++; SlideshowWorkMeasurement { sinks++ } }, { clocks++; 0L })
    assertNull(probe.begin(SlideshowWorkStage.ENGINE_PAGE_FINISHED))
    assertNull(probe.begin(SlideshowWorkStage.ENGINE_RETIRED))
    probe.mark(SlideshowWorkStage.METADATA_PREPARATION)
    probe.mark(SlideshowWorkStage.WEB_VIEW_ADMISSION)
    assertEquals(0, providers)
    assertEquals(0, clocks)
    assertEquals(0, sinks)
  }

  @Test fun providerOrStartClockFailureDegradesToNoSpan() {
    var clocks = 0; var sinks = 0
    val providerFailure = SlideshowWorkProbe({ throw AssertionError("synthetic provider") }, { clocks++; 0L })
    assertNull(providerFailure.begin(SlideshowWorkStage.METADATA_PREPARATION))
    assertEquals(0, clocks)
    val clockFailure = SlideshowWorkProbe({ SlideshowWorkMeasurement { sinks++ } },
        { throw AssertionError("synthetic start clock") })
    assertNull(clockFailure.begin(SlideshowWorkStage.WEB_VIEW_ADMISSION))
    assertEquals(0, sinks)
  }

  @Test fun finishClockFailureIsIsolatedAndCannotBeRetried() {
    var clocks = 0; var sinks = 0
    val probe = SlideshowWorkProbe({ SlideshowWorkMeasurement { sinks++ } }, {
      if (++clocks == 1) 5L else throw AssertionError("synthetic end clock")
    })
    val span = started(probe, SlideshowWorkStage.METADATA_PREPARATION)
    span.finish(); span.finish()
    assertEquals(2, clocks)
    assertEquals(0, sinks)
  }

  @Test fun sinkFailureIsIsolatedAndFinishRemainsIdempotent() {
    var providers = 0; var clocks = 0; var sinks = 0
    val probe = SlideshowWorkProbe({ providers++; SlideshowWorkMeasurement {
      sinks++; throw AssertionError("synthetic sink")
    } }, { (++clocks).toLong() })
    val span = started(probe, SlideshowWorkStage.WEB_VIEW_ADMISSION)
    span.finish(); span.finish()
    assertEquals(1, providers)
    assertEquals(2, clocks)
    assertEquals(1, sinks)
  }

  @Test fun originalWorkFailureSurvivesEveryDiagnosticFailureBoundary() {
    for (failureAt in listOf("provider", "startClock", "finishClock", "sink")) {
      val original = IllegalArgumentException("synthetic work failure")
      var reads = 0
      val probe = SlideshowWorkProbe({
        if (failureAt == "provider") throw AssertionError("synthetic provider")
        SlideshowWorkMeasurement { if (failureAt == "sink") throw AssertionError("synthetic sink") }
      }, {
        reads++
        if ((failureAt == "startClock" && reads == 1) || (failureAt == "finishClock" && reads == 2))
          throw AssertionError("synthetic clock")
        reads.toLong()
      })
      val observed = runCatching {
        val span = probe.begin(SlideshowWorkStage.METADATA_PREPARATION)
        try { throw original } finally { span?.finish() }
      }.exceptionOrNull()
      assertSame(failureAt, original, observed)
    }
  }

  @Test fun eventDiagnosticsInFinallyCannotReplaceOriginalWorkFailure() {
    for (failureAt in listOf("provider", "sink")) {
      val original = IllegalStateException("synthetic work failure")
      val probe = SlideshowWorkProbe({
        if (failureAt == "provider") throw AssertionError("synthetic provider")
        SlideshowWorkMeasurement { throw AssertionError("synthetic sink") }
      }, { throw AssertionError("Events have no clock") })
      val observed = runCatching {
        try { throw original } finally { probe.mark(SlideshowWorkStage.ENGINE_RETIRED) }
      }.exceptionOrNull()
      assertSame(failureAt, original, observed)
    }
  }

  @Test fun negativeClockDeltaReachesRealValidationWithoutClamping() {
    val samples = SlideshowWorkSamples()
    var time = 9L
    val probe = SlideshowWorkProbe({ stage -> SlideshowWorkMeasurement {
      samples.record(stage, 1L, it, false)
    } }, { time })
    val span = started(probe, SlideshowWorkStage.METADATA_PREPARATION)
    time = 4L; span.finish()
    val snapshot = samples.snapshot()
    assertEquals(0L, snapshot.getLong("records"))
    assertEquals(1L, snapshot.getLong("invalidRecords"))
    assertEquals(0, snapshot.getJSONArray("samples").length())
  }

  @Test fun eachSpanKeepsItsSelectedMeasurementWhenSessionsChange() {
    val first = SlideshowWorkSamples()
    val second = SlideshowWorkSamples()
    var current = first
    var providers = 0
    var time = 0L
    val probe = SlideshowWorkProbe({ stage ->
      providers++
      val selected = current
      SlideshowWorkMeasurement { selected.record(stage, 1L, it, selected === second) }
    }, { time })
    val oldSpan = started(probe, SlideshowWorkStage.METADATA_PREPARATION)
    current = second
    time = 1_000_000L
    val newSpan = started(probe, SlideshowWorkStage.WEB_VIEW_ADMISSION)
    time = 2_000_000L
    oldSpan.finish(); newSpan.finish()
    assertEquals(2, providers)
    val firstStage = first.snapshot().getJSONObject("stages").getJSONObject("metadataPreparation")
    assertEquals(1L, firstStage.getLong("otherThreadRecords"))
    assertEquals(2.0, firstStage.getDouble("otherThreadTotalMs"), 0.0)
    val secondStage = second.snapshot().getJSONObject("stages").getJSONObject("webViewAdmission")
    assertEquals(1L, secondStage.getLong("mainThreadRecords"))
    assertEquals(1.0, secondStage.getDouble("mainThreadTotalMs"), 0.0)
    assertEquals(1L, first.snapshot().getLong("records"))
    assertEquals(1L, second.snapshot().getLong("records"))
  }

  private fun started(probe: SlideshowWorkProbe, stage: SlideshowWorkStage): SlideshowWorkSpan {
    val span = probe.begin(stage)
    assertNotNull("Active elapsed span must be admitted", span)
    return span!!
  }
}
