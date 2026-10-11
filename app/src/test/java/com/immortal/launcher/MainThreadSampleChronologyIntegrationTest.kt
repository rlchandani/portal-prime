/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */
package com.immortal.launcher

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class MainThreadSampleChronologyIntegrationTest {
  private fun frame(name: String, method: String = "run") = StackTraceElement(name, method, "Private.kt", 923)
  private fun timeline(histogram: MainThreadSampleHistogram) = histogram.snapshot().getJSONObject("busyTimeline")

  @Test fun timedBusyObservationRetainsItsReadIntervalAndDerivedOperationFamily() {
    val histogram = MainThreadSampleHistogram()
    histogram.record(arrayOf(frame("androidx.compose.ui.semantics.SemanticsNode", "getBoundsInRoot")), 20, 27)
    val data = timeline(histogram)
    val event = data.getJSONArray("records").getJSONObject(0)
    assertEquals(1L, event.getLong("observation"))
    assertEquals(20L, event.getLong("startedMs"))
    assertEquals(27L, event.getLong("endedMs"))
    assertEquals(7L, event.getLong("readCostMs"))
    assertFalse(event.getBoolean("waiting"))
    val names = data.getJSONArray("operationFamilyNames")
    val semanticsIndex = (0 until names.length()).single { names.getString(it) == "semanticsWork" }
    assertEquals(1 shl semanticsIndex, event.getInt("operationMask"))
    assertEquals("androidx.compose.ui.semantics.SemanticsNode.getBoundsInRoot", data.getJSONArray("symbols").getString(0))
  }

  @Test fun nativePollDoesNotFillChronologyButOtherBlockingWorkStaysVisible() {
    val histogram = MainThreadSampleHistogram()
    histogram.record(arrayOf(frame("android.os.MessageQueue", "nativePollOnce")), 0, 2)
    histogram.record(arrayOf(frame("android.os.BinderProxy", "transactNative")), 10, 12)
    histogram.record(arrayOf(frame("java.lang.Thread", "sleep")), 20, 23)
    val data = timeline(histogram)
    assertEquals(1L, data.getLong("excludedNativePollSamples"))
    val records = data.getJSONArray("records")
    assertEquals(2, records.length())
    assertEquals(2L, records.getJSONObject(0).getLong("observation"))
    assertFalse(records.getJSONObject(0).getBoolean("waiting"))
    assertTrue(records.getJSONObject(1).getBoolean("waiting"))
    assertEquals(3L, records.getJSONObject(1).getLong("observation"))
  }

  @Test fun observationsWithoutTimingKeepAggregatesWithoutInventingChronology() {
    val histogram = MainThreadSampleHistogram()
    histogram.record(arrayOf(frame("example.Work")))
    assertEquals(1L, histogram.snapshot().getLong("samples"))
    assertEquals(0, timeline(histogram).getJSONArray("records").length())
  }

  @Test fun chronologicalAdmissionSurvivesOldInclusiveSymbolSaturation() {
    val histogram = MainThreadSampleHistogram()
    repeat(256) { histogram.record(arrayOf(frame("example.Earlier$it"))) }
    histogram.record(arrayOf(frame("example.LateBusy")), 40, 41)
    val full = histogram.snapshot()
    assertTrue(full.getBoolean("symbolLimitReached"))
    val chronological = timeline(histogram)
    assertEquals("example.LateBusy.run", chronological.getJSONArray("symbols").getString(0))
    assertEquals(257L, chronological.getJSONArray("records").getJSONObject(0).getLong("observation"))
  }

  @Test fun invalidTimingCannotBecomeAChronologicalRecord() {
    val histogram = MainThreadSampleHistogram()
    histogram.record(arrayOf(frame("example.Work")), 90, 10)
    assertEquals(1L, histogram.snapshot().getLong("samples"))
    assertEquals(0, timeline(histogram).getJSONArray("records").length())
    assertEquals(1L, timeline(histogram).getLong("invalidIntervals"))
  }
}
