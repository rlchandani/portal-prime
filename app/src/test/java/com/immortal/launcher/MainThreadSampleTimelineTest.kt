/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */
package com.immortal.launcher

import java.lang.reflect.Modifier
import java.util.IdentityHashMap
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class MainThreadSampleTimelineTest {
  private fun frame(
      className: String = "com.immortal.launcher.Fixture",
      method: String = "work",
      file: String? = "PRIVATE_SOURCE_PATH_DO_NOT_EXPORT",
      line: Int = 918273,
  ) = StackTraceElement(className, method, file, line)

  private fun snapshot(timeline: MainThreadSampleTimeline): JSONObject = timeline.snapshot().also {
    assertTrue("records must be a JSON array", it.opt("records") is JSONArray)
    assertTrue("symbols must be a JSON array", it.opt("symbols") is JSONArray)
  }

  private fun counter(value: JSONObject, name: String, expected: Long) {
    assertEquals(name, expected, value.optLong(name, -1L))
  }

  private fun ids(row: JSONObject): List<Int> {
    val frames = row.getJSONArray("frameIds")
    return (0 until frames.length()).map { frames.getInt(it) }
  }

  @Test fun recordsCopyInputAndRetainOnlyClassMethodMetadata() {
    val timeline = MainThreadSampleTimeline()
    val stack = arrayOf(
        frame(method = "alpha"),
        frame(method = "beta"),
        frame(method = "alpha", file = "SECOND_PRIVATE_SOURCE", line = 765432),
    )
    assertTrue(timeline.record(stack, 14, 17, true, 5, 7))
    stack[0] = frame(method = "replacement")
    val value = snapshot(timeline)
    val row = value.getJSONArray("records").getJSONObject(0)
    assertEquals(7L, row.getLong("observation"))
    assertEquals(14L, row.getLong("startedMs"))
    assertEquals(17L, row.getLong("endedMs"))
    assertEquals(3L, row.getLong("readCostMs"))
    assertTrue(row.getBoolean("waiting"))
    assertEquals(5, row.getInt("operationMask"))
    assertEquals(listOf(0, 1, 0), ids(row))
    assertEquals(setOf("observation", "startedMs", "endedMs", "readCostMs",
        "waiting", "operationMask", "frameIds"), row.keys().asSequence().toSet())
    val symbols = value.getJSONArray("symbols")
    assertEquals(2, symbols.length())
    assertEquals("com.immortal.launcher.Fixture.alpha", symbols.getString(0))
    assertEquals("com.immortal.launcher.Fixture.beta", symbols.getString(1))
    counter(value, "uniqueSymbols", 2)
    val json = value.toString()
    for (privateValue in listOf("PRIVATE_SOURCE_PATH_DO_NOT_EXPORT", "SECOND_PRIVATE_SOURCE",
        "918273", "765432", "replacement")) assertFalse(json.contains(privateValue))
    assertNoRetainedFramesOrThreads(timeline)
  }

  @Test fun excludesOnlyTheLiteralNativePollLeafAndKeepsJniAndWaitingStacks() {
    val timeline = MainThreadSampleTimeline()
    val poll = frame("android.os.MessageQueue", "nativePollOnce", null, -2)
    assertFalse(timeline.record(arrayOf(poll, frame()), 0, 1, true, 0, 1))
    val admitted = listOf(
        arrayOf(frame("android.graphics.HardwareRenderer", "nSyncAndDrawFrame", null, -2)),
        arrayOf(frame("java.lang.Object", "wait", null, -2)),
        arrayOf(frame("fixture.MessageQueue", "nativePollOnce")),
        arrayOf(frame("android.os.MessageQueue", "nativePollOnceExtra")),
        arrayOf(frame(), poll),
    )
    admitted.forEachIndexed { index, stack ->
      assertTrue(timeline.record(stack, index + 1L, index + 2L, true, 0, index + 2L))
    }
    val value = snapshot(timeline)
    counter(value, "excludedNativePollSamples", 1)
    counter(value, "eligibleRecords", 5)
    val rows = value.getJSONArray("records")
    assertEquals(5, rows.length())
    for (i in 0 until rows.length()) assertTrue(rows.getJSONObject(i).getBoolean("waiting"))
    assertEquals(2, ids(rows.getJSONObject(4)).size)
  }

  @Test fun emptyStacksDoNotCreateRecordsOrSymbols() {
    val timeline = MainThreadSampleTimeline()
    assertFalse(timeline.record(emptyArray(), 0, 1, false, 0, 1))
    val value = snapshot(timeline)
    assertEquals(0, value.getJSONArray("records").length())
    assertEquals(0, value.getJSONArray("symbols").length())
    counter(value, "eligibleRecords", 0)
  }

  @Test fun admitsInclusiveTimeAndObservationBoundsAndZeroCostReads() {
    val timeline = MainThreadSampleTimeline()
    assertTrue(timeline.record(arrayOf(frame()), 0, 0, false, 0, 1))
    assertTrue(timeline.record(arrayOf(frame()), 0, 60000, false, 0, 2))
    assertTrue(timeline.record(arrayOf(frame()), 60000, 60000, true, Int.MAX_VALUE, 6000))
    val value = snapshot(timeline)
    val rows = value.getJSONArray("records")
    assertEquals(3, rows.length())
    assertEquals(0L, rows.getJSONObject(0).getLong("readCostMs"))
    assertEquals(60000L, rows.getJSONObject(1).getLong("readCostMs"))
    assertEquals(0L, rows.getJSONObject(2).getLong("readCostMs"))
    assertEquals(6000L, rows.getJSONObject(2).getLong("observation"))
    assertEquals(Int.MAX_VALUE, rows.getJSONObject(2).getInt("operationMask"))
    counter(value, "invalidIntervals", 0)
  }

  @Test fun rejectsReversedNegativeAndOutOfRangeIntervalsBeforeSymbolAdmission() {
    val timeline = MainThreadSampleTimeline()
    val invalid = listOf(
        -1L to 0L, 0L to -1L, 2L to 1L,
        60001L to 60001L, 0L to 60001L, 60001L to 60002L,
        Long.MIN_VALUE to Long.MAX_VALUE, 0L to Long.MAX_VALUE,
        Long.MAX_VALUE to Long.MIN_VALUE,
    )
    invalid.forEachIndexed { index, (start, end) ->
      assertFalse(timeline.record(arrayOf(frame(method = "rejected$index")),
          start, end, false, 0, 1))
    }
    val value = snapshot(timeline)
    counter(value, "invalidIntervals", 9)
    counter(value, "eligibleRecords", 0)
    assertEquals(0, value.getJSONArray("records").length())
    assertEquals(0, value.getJSONArray("symbols").length())
  }

  @Test fun rejectsObservationsOutsideOneThroughSixThousand() {
    val timeline = MainThreadSampleTimeline()
    for (observation in listOf(0L, -1L, 6001L, Long.MIN_VALUE, Long.MAX_VALUE)) {
      assertFalse(timeline.record(arrayOf(frame()), 0, 1, false, 0, observation))
    }
    val value = snapshot(timeline)
    assertEquals(0, value.getJSONArray("records").length())
    assertEquals(0, value.getJSONArray("symbols").length())
    counter(value, "eligibleRecords", 0)
  }

  @Test fun rejectsNegativeOperationMasks() {
    val timeline = MainThreadSampleTimeline()
    for (mask in listOf(-1, Int.MIN_VALUE)) {
      assertFalse(timeline.record(arrayOf(frame()), 0, 1, false, mask, 1))
    }
    val value = snapshot(timeline)
    assertEquals(0, value.getJSONArray("records").length())
    assertEquals(0, value.getJSONArray("symbols").length())
    counter(value, "eligibleRecords", 0)
  }

  @Test fun retainsTheNewest128RecordsInOldestToNewestOrder() {
    val timeline = MainThreadSampleTimeline()
    for (observation in 1L..131L) {
      assertTrue(timeline.record(arrayOf(frame()), observation - 1, observation,
          false, 0, observation))
    }
    val value = snapshot(timeline)
    val rows = value.getJSONArray("records")
    assertEquals(128, rows.length())
    for (i in 0 until rows.length()) {
      val row = rows.getJSONObject(i)
      assertEquals(i + 4L, row.getLong("observation"))
      assertEquals(i + 3L, row.getLong("startedMs"))
      assertEquals(listOf(0), ids(row))
    }
    counter(value, "eligibleRecords", 131)
    counter(value, "omittedRecords", 3)
    counter(value, "maxRecords", 128)
    counter(value, "uniqueSymbols", 1)
  }

  @Test fun symbolPoolReaches1024IndependentlyOfRecordEvictionAndKeepsStableIds() {
    val timeline = MainThreadSampleTimeline()
    for (index in 0 until 1024) {
      assertTrue(timeline.record(arrayOf(frame("fixture.Symbol$index")),
          index.toLong(), index + 1L, false, 0, index + 1L))
    }
    assertTrue(timeline.record(arrayOf(
        frame("fixture.Symbol0"), frame("fixture.Unadmitted"), frame("fixture.Symbol1023")),
        1024, 1025, false, 0, 1025))
    val value = snapshot(timeline)
    val symbols = value.getJSONArray("symbols")
    assertEquals(1024, symbols.length())
    assertEquals("fixture.Symbol0.work", symbols.getString(0))
    assertEquals("fixture.Symbol256.work", symbols.getString(256))
    assertEquals("fixture.Symbol1023.work", symbols.getString(1023))
    val rows = value.getJSONArray("records")
    assertEquals(128, rows.length())
    assertEquals(898L, rows.getJSONObject(0).getLong("observation"))
    assertEquals(listOf(0, -1, 1023), ids(rows.getJSONObject(127)))
    counter(value, "uniqueSymbols", 1024)
    counter(value, "maxSymbols", 1024)
    counter(value, "discardedIdentities", 1)
    counter(value, "eligibleRecords", 1025)
    counter(value, "omittedRecords", 897)
  }

  @Test fun depthLimitKeeps64FramesAndDoesNotInternDiscardedDepth() {
    val timeline = MainThreadSampleTimeline()
    assertTrue(timeline.record(Array(70) { frame("fixture.Depth$it") },
        0, 1, false, 0, 1))
    val value = snapshot(timeline)
    assertEquals((0 until 64).toList(), ids(value.getJSONArray("records").getJSONObject(0)))
    assertEquals(64, value.getJSONArray("symbols").length())
    counter(value, "discardedDepthFrames", 6)
    counter(value, "uniqueSymbols", 64)
    counter(value, "maxDepth", 64)
  }

  @Test fun oversizedIdentitiesLeaveMinusOneSlotsAndThe512CharacterBoundaryIsAdmitted() {
    val timeline = MainThreadSampleTimeline()
    val boundary = frame("C".repeat(510), "x")
    assertTrue(timeline.record(arrayOf(
        frame("C".repeat(511), "x"), boundary, frame("C", "x".repeat(511)), frame()),
        0, 1, false, 0, 1))
    val value = snapshot(timeline)
    assertEquals(listOf(-1, 0, -1, 1), ids(value.getJSONArray("records").getJSONObject(0)))
    assertEquals(2, value.getJSONArray("symbols").length())
    assertEquals("C".repeat(510) + ".x", value.getJSONArray("symbols").getString(0))
    counter(value, "discardedIdentities", 2)
    counter(value, "uniqueSymbols", 2)
    counter(value, "maxSymbolChars", 512)
  }

  @Test fun snapshotsAreDetachedFromLaterRecordsAndNestedJsonMutation() {
    val timeline = MainThreadSampleTimeline()
    assertTrue(timeline.record(arrayOf(frame()), 0, 1, false, 0, 1))
    val detached = snapshot(timeline)
    val original = detached.toString()
    assertTrue(timeline.record(arrayOf(frame(method = "later")), 2, 3, true, 4, 2))
    assertEquals(original, detached.toString())
    detached.getJSONArray("records").getJSONObject(0).apply {
      put("observation", 999)
      getJSONArray("frameIds").put(0, 999)
    }
    detached.getJSONArray("records").put(JSONObject().put("injected", true))
    detached.getJSONArray("symbols").put(0, "corrupted")
    detached.put("eligibleRecords", -100)
    val fresh = snapshot(timeline)
    val rows = fresh.getJSONArray("records")
    assertEquals(2, rows.length())
    assertEquals(1L, rows.getJSONObject(0).getLong("observation"))
    assertEquals(listOf(0), ids(rows.getJSONObject(0)))
    assertEquals(listOf(1), ids(rows.getJSONObject(1)))
    assertEquals("com.immortal.launcher.Fixture.work", fresh.getJSONArray("symbols").getString(0))
    assertEquals("com.immortal.launcher.Fixture.later", fresh.getJSONArray("symbols").getString(1))
    counter(fresh, "eligibleRecords", 2)
  }

  /** Privacy contract: storage shape may vary, but raw VM frames and threads cannot survive. */
  private fun assertNoRetainedFramesOrThreads(root: Any) {
    val seen = IdentityHashMap<Any, Boolean>()
    fun inspect(value: Any?) {
      if (value == null || seen.put(value, true) != null) return
      assertFalse("timeline retained a raw VM frame", value is StackTraceElement)
      assertFalse("timeline retained a Thread", value is Thread)
      when (value) {
        is Map<*, *> -> value.forEach { (key, entry) -> inspect(key); inspect(entry) }
        is Iterable<*> -> value.forEach { inspect(it) }
        is Array<*> -> value.forEach { inspect(it) }
        is JSONObject -> value.keys().forEach { inspect(value.get(it)) }
        is JSONArray -> for (i in 0 until value.length()) inspect(value.get(i))
        else -> if (value.javaClass.name.startsWith("com.immortal.launcher.")) {
          for (field in value.javaClass.declaredFields) {
            if (!Modifier.isStatic(field.modifiers)) {
              field.isAccessible = true
              inspect(field.get(value))
            }
          }
        }
      }
    }
    inspect(root)
  }
}
