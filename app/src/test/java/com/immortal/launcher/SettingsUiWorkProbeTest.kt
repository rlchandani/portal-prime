/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */
package com.immortal.launcher

import org.junit.Assert.*
import org.junit.Test

class SettingsUiWorkProbeTest {
  private fun startedSpan(probe: SettingsUiWorkProbe, stage: SettingsUiWorkStage): SettingsUiWorkSpan {
    val span = probe.begin(stage)
    assertNotNull("Active timing span must be admitted", span)
    return span!!
  }

  @Test fun inactiveSpanReadsNoClock() {
    var reads = 0
    val probe = SettingsUiWorkProbe({ null }, { reads++; 0L })
    assertNull(probe.begin(SettingsUiWorkStage.SHELL_COMPOSITION))
    probe.mark(SettingsUiWorkStage.VERSION_PUBLISHED)
    assertEquals(0, reads)
  }

  @Test fun activeSpanSelectsMeasurementBeforeClockAndRecordsOnlyAfterFinish() {
    val events = mutableListOf<String>()
    val durations = mutableListOf<Long>()
    var time = 5L
    val probe = SettingsUiWorkProbe({ stage ->
      events.add("select:" + stage.key)
      SettingsUiWorkMeasurement { durations.add(it); events.add("record") }
    }, { events.add("clock"); time })
    val span = probe.begin(SettingsUiWorkStage.SEARCH_MEASURE)
    assertNotNull(span)
    assertEquals(listOf("select:searchMeasure", "clock"), events)
    time = 25L
    span!!.finish()
    assertEquals(listOf(20L), durations)
    assertEquals(listOf("select:searchMeasure", "clock", "clock", "record"), events)
  }

  @Test fun finishingTwiceCannotRecordTwiceEvenWhenSinkFails() {
    var records = 0; var clocks = 0; var providers = 0
    val probe = SettingsUiWorkProbe({ providers++; SettingsUiWorkMeasurement {
      records++; throw IllegalStateException("synthetic sink")
    } }, { clocks++; clocks.toLong() })
    val span = startedSpan(probe, SettingsUiWorkStage.DETAIL_MEASURE)
    span.finish(); span.finish()
    assertEquals(1, providers)
    assertEquals(1, records)
    assertEquals(2, clocks)
  }

  @Test fun providerAndStartClockFailuresDegradeToNoSpan() {
    var clocks = 0
    val badProvider = SettingsUiWorkProbe({ throw IllegalStateException("synthetic provider") },
        { clocks++; 0L })
    assertNull(badProvider.begin(SettingsUiWorkStage.SHELL_MEASURE))
    assertEquals(0, clocks)
    var records = 0
    val badClock = SettingsUiWorkProbe({ SettingsUiWorkMeasurement { records++ } },
        { throw IllegalStateException("synthetic clock") })
    assertNull(badClock.begin(SettingsUiWorkStage.SHELL_MEASURE))
    assertEquals(0, records)
  }

  @Test fun failedFinishClockCannotReachSinkOrThrow() {
    var clocks = 0; var records = 0
    val probe = SettingsUiWorkProbe({ SettingsUiWorkMeasurement { records++ } }, {
      if (++clocks == 1) 5L else throw IllegalStateException("synthetic end clock")
    })
    val span = startedSpan(probe, SettingsUiWorkStage.SHELL_MEASURE)
    span.finish(); span.finish()
    assertEquals(2, clocks)
    assertEquals(0, records)
  }

  @Test fun eventMarksUseFixedStageAndZeroDurationWithoutNanoClock() {
    val selected = mutableListOf<SettingsUiWorkStage>()
    val durations = mutableListOf<Long>()
    var clocks = 0
    val probe = SettingsUiWorkProbe({ stage -> selected.add(stage); SettingsUiWorkMeasurement {
      durations.add(it)
    } }, { clocks++; 0L })
    probe.mark(SettingsUiWorkStage.VERSION_PUBLISHED)
    probe.mark(SettingsUiWorkStage.PANE_EFFECT_START)
    assertEquals(listOf(SettingsUiWorkStage.VERSION_PUBLISHED, SettingsUiWorkStage.PANE_EFFECT_START), selected)
    assertEquals(listOf(0L, 0L), durations)
    assertEquals(0, clocks)
  }

  @Test fun eventSpanAndElapsedStageMarkAreRejectedWithoutProviderOrClock() {
    var providers = 0; var clocks = 0
    val probe = SettingsUiWorkProbe({ providers++; null }, { clocks++; 0L })
    assertNull(probe.begin(SettingsUiWorkStage.VERSION_PUBLISHED))
    probe.mark(SettingsUiWorkStage.SHELL_COMPOSITION)
    assertEquals(0, providers)
    assertEquals(0, clocks)
  }

  @Test fun rawClockDeltaIsPassedToValidationWithoutMaskingNegativeValue() {
    val values = mutableListOf<Long>()
    var time = 9L
    val probe = SettingsUiWorkProbe({ SettingsUiWorkMeasurement { values.add(it) } }, { time })
    val span = startedSpan(probe, SettingsUiWorkStage.SEARCH_COMPOSITION)
    time = 4L; span.finish()
    assertEquals(listOf(-5L), values)
  }

  @Test fun eventProviderAndSinkFailuresCannotEscape() {
    var attempts = 0
    val badProvider = SettingsUiWorkProbe({ throw IllegalStateException("synthetic provider") }, { 0L })
    badProvider.mark(SettingsUiWorkStage.VERSION_PUBLISHED)
    val badSink = SettingsUiWorkProbe({ SettingsUiWorkMeasurement {
      attempts++; throw IllegalStateException("synthetic sink")
    } }, { 0L })
    badSink.mark(SettingsUiWorkStage.VERSION_PUBLISHED)
    assertEquals(1, attempts)
  }
}
