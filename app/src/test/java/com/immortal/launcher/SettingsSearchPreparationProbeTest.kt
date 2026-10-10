/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */
package com.immortal.launcher

import org.junit.Assert.*
import org.junit.Test

class SettingsSearchPreparationProbeTest {
  @Test fun inactivePreparationReadsNoTimerAndKeepsTheOriginalResult() {
    val events = mutableListOf<String>()
    var ticks = 0
    val expected = listOf("synthetic metadata")
    val probe = SettingsSearchPreparationProbe(
        { events += "provider"; null }, { ticks++; 20L })
    val actual = probe.prepare { events += "prepare"; expected }
    assertSame(expected, actual)
    assertEquals(listOf("provider", "prepare"), events)
    assertEquals(0, ticks)
  }

  @Test fun activePreparationTimesOnlyTheBuilderAndReportsCountOnce() {
    val events = mutableListOf<String>()
    val records = mutableListOf<Pair<Long, Int>>()
    val expected = listOf("first", "second")
    var ticks = 0
    val measurement = SettingsSearchPreparationMeasurement { ns, size ->
      events += "record"; records += ns to size
    }
    val probe = SettingsSearchPreparationProbe(
        { events += "provider"; measurement },
        { events += if (ticks++ == 0) "start" else "end"; if (ticks == 1) 10L else 1_250_010L })
    val actual = probe.prepare { events += "prepare"; expected }
    assertSame(expected, actual)
    assertEquals(listOf("provider", "start", "prepare", "end", "record"), events)
    assertEquals(listOf(1_250_000L to 2), records)
    assertEquals(2, ticks)
  }

  @Test fun emptyPreparedCollectionIsMeasured() {
    var ticks = 0
    val records = mutableListOf<Pair<Long, Int>>()
    val expected = emptyList<String>()
    val probe = SettingsSearchPreparationProbe(
        { SettingsSearchPreparationMeasurement { ns, size -> records += ns to size } },
        { if (ticks++ == 0) 10L else 20L })
    assertSame(expected, probe.prepare { expected })
    assertEquals(listOf(10L to 0), records)
  }

  @Test fun providerFailureCannotPreventPreparation() {
    var prepared = 0
    var ticks = 0
    val expected = listOf(1)
    val probe = SettingsSearchPreparationProbe(
        { throw IllegalStateException("diagnostic provider") }, { ticks++; 1L })
    assertSame(expected, probe.prepare { prepared++; expected })
    assertEquals(1, prepared)
    assertEquals(0, ticks)
  }

  @Test fun startTimerFailureCannotPreventOrRepeatPreparation() {
    var prepared = 0
    var ticks = 0
    var recorded = 0
    val expected = listOf(1)
    val probe = SettingsSearchPreparationProbe(
        { SettingsSearchPreparationMeasurement { _, _ -> recorded++ } },
        { ticks++; throw IllegalStateException("diagnostic timer") })
    assertSame(expected, probe.prepare { prepared++; expected })
    assertEquals(1, prepared)
    assertEquals(1, ticks)
    assertEquals(0, recorded)
  }

  @Test fun endTimerFailureDoesNotChangeSuccessfulPreparation() {
    var prepared = 0
    var ticks = 0
    var recorded = 0
    val expected = listOf(1)
    val probe = SettingsSearchPreparationProbe(
        { SettingsSearchPreparationMeasurement { _, _ -> recorded++ } },
        { if (ticks++ == 0) 10L else throw IllegalStateException("diagnostic timer") })
    assertSame(expected, probe.prepare { prepared++; expected })
    assertEquals(1, prepared)
    assertEquals(2, ticks)
    assertEquals(0, recorded)
  }

  @Test fun recordingFailureDoesNotChangeSuccessfulPreparation() {
    var prepared = 0
    var recorded = 0
    var ticks = 0
    val expected = listOf(1)
    val probe = SettingsSearchPreparationProbe(
        { SettingsSearchPreparationMeasurement { _, _ -> recorded++; throw IllegalStateException("diagnostic sink") } },
        { ticks++; ticks.toLong() })
    assertSame(expected, probe.prepare { prepared++; expected })
    assertEquals(1, prepared)
    assertEquals(1, recorded)
    assertEquals(2, ticks)
  }

  @Test fun builderFailurePropagatesUnchangedWithoutCompletionRecord() {
    val failure = IllegalArgumentException("synthetic preparation failure")
    var prepared = 0
    var ticks = 0
    var recorded = 0
    val probe = SettingsSearchPreparationProbe(
        { SettingsSearchPreparationMeasurement { _, _ -> recorded++ } }, { ticks++; 10L })
    try {
      probe.prepare<String> { prepared++; throw failure }
      fail("The preparation failure must propagate")
    } catch (caught: IllegalArgumentException) { assertSame(failure, caught) }
    assertEquals(1, prepared)
    assertEquals(1, ticks)
    assertEquals(0, recorded)
  }

  @Test fun providerFailureCannotMaskTheBuilderFailure() {
    val failure = IllegalArgumentException("synthetic preparation failure")
    val probe = SettingsSearchPreparationProbe(
        { throw IllegalStateException("diagnostic provider") }, { 10L })
    try {
      probe.prepare<String> { throw failure }
      fail("The preparation failure must propagate")
    } catch (caught: IllegalArgumentException) { assertSame(failure, caught) }
  }

  @Test fun measurementIsCapturedOnceBeforePreparation() {
    var providerCalls = 0
    var firstRecords = 0
    var secondRecords = 0
    var ticks = 0
    var selected = SettingsSearchPreparationMeasurement { _, _ -> firstRecords++ }
    val probe = SettingsSearchPreparationProbe({ providerCalls++; selected }, { ticks++; ticks.toLong() })
    val expected = listOf(1)
    assertSame(expected, probe.prepare {
      selected = SettingsSearchPreparationMeasurement { _, _ -> secondRecords++ }
      expected
    })
    assertEquals(1, providerCalls)
    assertEquals(1, firstRecords)
    assertEquals(0, secondRecords)
  }

  @Test fun timerDifferenceIsPassedToTheAccumulatorWithoutReclamping() {
    var ticks = 0
    val records = mutableListOf<Pair<Long, Int>>()
    val probe = SettingsSearchPreparationProbe(
        { SettingsSearchPreparationMeasurement { ns, size -> records += ns to size } },
        { if (ticks++ == 0) 20L else 10L })
    val expected = listOf(1)
    assertSame(expected, probe.prepare { expected })
    assertEquals(listOf(-10L to 1), records)
  }
}
