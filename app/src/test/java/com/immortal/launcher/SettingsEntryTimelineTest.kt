/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */
package com.immortal.launcher

import java.lang.reflect.Modifier
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class SettingsEntryTimelineTest {
  @Test fun emptySnapshotIncludesBoundsCountersAndMeasurementDisclosure() {
    val snapshot = SettingsEntryTimeline().snapshot()
    assertEquals(0, snapshot.getJSONArray("entries").length())
    assertCounts(snapshot, entries = 0, records = 0)
  }

  @Test fun unmatchedCreateHasNoInventedLaunchAndOnlySupportedIntervals() {
    val timeline = SettingsEntryTimeline()
    val id = created(timeline, 1_000_000)
    assertTrue(timeline.record(id, SettingsEntryStage.CREATE_FINISHED, 2_500_000))
    assertTrue(timeline.record(id, SettingsEntryStage.FIRST_PRE_DRAW, 3_000_000))
    assertTrue(timeline.record(id, SettingsEntryStage.FIRST_FRAME_RECEIVED, 4_250_000))
    assertEntry(row(timeline.snapshot()), id, false, mapOf(
        "createStarted" to 1.0, "createFinished" to 2.5,
        "firstPreDraw" to 3.0, "firstFrameReceived" to 4.25), mapOf(
        "createBody" to 1.5, "createToPreDraw" to 2.0, "preDrawToFrameReceipt" to 1.25))
    assertCounts(timeline.snapshot(), entries = 1, records = 4)
  }

  @Test fun nullLaunchIdDoesNotClaimAnUnclaimedLaunch() {
    val timeline = SettingsEntryTimeline()
    val launch = launched(timeline, 0)
    val unmatched = created(timeline, 1_000_000)
    assertNotEquals(launch, unmatched)
    assertEquals(launch, created(timeline, 2_000_000, launch))
    val snapshot = timeline.snapshot()
    assertEntry(row(snapshot), launch, true,
        mapOf("launchRequested" to 0.0, "createStarted" to 2.0),
        mapOf("launchToCreate" to 2.0))
    assertEntry(row(snapshot, 1), unmatched, false, mapOf("createStarted" to 1.0))
    assertCounts(snapshot, entries = 2, records = 3)
  }

  @Test fun launchAndLifecycleProduceFractionalMillisecondEventsAndAllIntervals() {
    val timeline = SettingsEntryTimeline()
    val id = launched(timeline, 1_000_000)
    assertTrue(timeline.record(id, SettingsEntryStage.LAUNCH_RETURNED, 2_000_000))
    assertEquals(id, created(timeline, 3_500_000, id))
    assertTrue(timeline.record(id, SettingsEntryStage.CREATE_FINISHED, 4_250_000))
    assertTrue(timeline.record(id, SettingsEntryStage.RESUME_STARTED, 4_500_000))
    assertTrue(timeline.record(id, SettingsEntryStage.RESUME_FINISHED, 5_000_000))
    assertTrue(timeline.record(id, SettingsEntryStage.FOCUS_GAINED, 5_500_000))
    assertTrue(timeline.record(id, SettingsEntryStage.FIRST_PRE_DRAW, 6_250_000))
    assertTrue(timeline.record(id, SettingsEntryStage.FIRST_FRAME_RECEIVED, 7_750_000))
    assertTrue(timeline.record(id, SettingsEntryStage.DESTROYED, 8_000_000))
    val snapshot = timeline.snapshot()
    assertEntry(row(snapshot), id, true, mapOf(
        "launchRequested" to 1.0, "launchReturned" to 2.0,
        "createStarted" to 3.5, "createFinished" to 4.25,
        "resumeStarted" to 4.5, "resumeFinished" to 5.0, "focusGained" to 5.5,
        "firstPreDraw" to 6.25, "firstFrameReceived" to 7.75, "destroyed" to 8.0), mapOf(
        "launchToCreate" to 2.5, "createBody" to 0.75, "resumeBody" to 0.5,
        "launchToFocus" to 4.5, "launchToPreDraw" to 5.25, "launchToFrameReceipt" to 6.75,
        "createToPreDraw" to 2.75, "preDrawToFrameReceipt" to 1.5))
    assertCounts(snapshot, entries = 1, records = 10)
  }

  @Test fun launchReturnedCanArriveAfterCreateAndOtherLifecycleCallbacks() {
    val timeline = SettingsEntryTimeline()
    val id = launched(timeline, 0)
    created(timeline, 1_000_000, id)
    assertTrue(timeline.record(id, SettingsEntryStage.FIRST_FRAME_RECEIVED, 2_000_000))
    assertTrue(timeline.record(id, SettingsEntryStage.LAUNCH_RETURNED, 3_000_000))
    assertEntry(row(timeline.snapshot()), id, true, mapOf(
        "launchRequested" to 0.0, "createStarted" to 1.0,
        "firstFrameReceived" to 2.0, "launchReturned" to 3.0), mapOf(
        "launchToCreate" to 1.0, "launchToFrameReceipt" to 2.0))
    assertCounts(timeline.snapshot(), entries = 1, records = 4)
  }

  @Test fun callbackOrderIsFlexibleButReversedIntervalEndpointsYieldNull() {
    val timeline = SettingsEntryTimeline()
    val id = created(timeline, 0)
    assertTrue(timeline.record(id, SettingsEntryStage.RESUME_FINISHED, 1_000_000))
    assertTrue(timeline.record(id, SettingsEntryStage.FIRST_FRAME_RECEIVED, 2_000_000))
    assertTrue(timeline.record(id, SettingsEntryStage.FOCUS_GAINED, 3_000_000))
    assertTrue(timeline.record(id, SettingsEntryStage.FIRST_PRE_DRAW, 4_000_000))
    assertTrue(timeline.record(id, SettingsEntryStage.RESUME_STARTED, 5_000_000))
    assertTrue(timeline.record(id, SettingsEntryStage.CREATE_FINISHED, 6_000_000))
    assertEntry(row(timeline.snapshot()), id, false, mapOf(
        "createStarted" to 0.0, "resumeFinished" to 1.0, "firstFrameReceived" to 2.0,
        "focusGained" to 3.0, "firstPreDraw" to 4.0, "resumeStarted" to 5.0,
        "createFinished" to 6.0), mapOf("createBody" to 6.0, "createToPreDraw" to 4.0))
    assertCounts(timeline.snapshot(), entries = 1, records = 7)
  }

  @Test fun duplicatesPreserveFirstEventsAndDoNotAdvanceTheAcceptedTimestamp() {
    val timeline = SettingsEntryTimeline()
    val id = launched(timeline, 0)
    created(timeline, 1_000_000, id)
    assertTrue(timeline.record(id, SettingsEntryStage.FIRST_PRE_DRAW, 2_000_000))
    assertTrue(timeline.record(id, SettingsEntryStage.FOCUS_GAINED, 3_000_000))
    assertFalse(timeline.record(id, SettingsEntryStage.FIRST_PRE_DRAW, 4_000_000))
    assertFalse(timeline.record(id, SettingsEntryStage.FIRST_PRE_DRAW, 2_000_000))
    assertNull(timeline.createStarted(4_000_000, id))
    assertFalse(timeline.record(id, SettingsEntryStage.LAUNCH_REQUESTED, 4_000_000))
    assertTrue(timeline.record(id, SettingsEntryStage.FIRST_FRAME_RECEIVED, 3_500_000))
    assertEntry(row(timeline.snapshot()), id, true, mapOf(
        "launchRequested" to 0.0, "createStarted" to 1.0, "firstPreDraw" to 2.0,
        "focusGained" to 3.0, "firstFrameReceived" to 3.5), mapOf(
        "launchToCreate" to 1.0, "launchToFocus" to 3.0, "launchToPreDraw" to 2.0,
        "launchToFrameReceipt" to 3.5, "createToPreDraw" to 1.0,
        "preDrawToFrameReceipt" to 1.5))
    assertCounts(timeline.snapshot(), entries = 1, records = 5, duplicates = 4)
  }

  @Test fun failedLaunchCannotBeClaimedOrReceiveFurtherNewEvents() {
    val timeline = SettingsEntryTimeline()
    val failed = launched(timeline, 0)
    assertTrue(timeline.record(failed, SettingsEntryStage.LAUNCH_FAILED, 1_000_000))
    assertNull(timeline.createStarted(2_000_000, failed))
    assertFalse(timeline.record(failed, SettingsEntryStage.LAUNCH_RETURNED, 2_000_000))
    assertFalse(timeline.record(failed, SettingsEntryStage.CREATE_FINISHED, 2_000_000))
    assertFalse(timeline.record(failed, SettingsEntryStage.DESTROYED, 2_000_000))
    assertFalse(timeline.record(failed, SettingsEntryStage.LAUNCH_FAILED, 2_000_000))
    val next = launched(timeline, 3_000_000)
    assertNotEquals(failed, next)
    created(timeline, 4_000_000, next)
    assertEntry(row(timeline.snapshot()), failed, true,
        mapOf("launchRequested" to 0.0, "launchFailed" to 1.0))
    assertCounts(timeline.snapshot(), entries = 2, records = 4, invalid = 4, duplicates = 1)
  }

  @Test fun suppliedUnknownIdsNeverCreateEntriesOrConsumeIds() {
    val timeline = SettingsEntryTimeline()
    for (id in listOf(0L, -1L, 1L, Long.MIN_VALUE, Long.MAX_VALUE)) {
      assertNull(timeline.createStarted(0, id))
      assertFalse(timeline.record(id, SettingsEntryStage.LAUNCH_RETURNED, 0))
    }
    assertCounts(timeline.snapshot(), entries = 0, records = 0, invalid = 10)
    assertEquals(1L, launched(timeline, 0))
    assertCounts(timeline.snapshot(), entries = 1, records = 1, invalid = 10)
  }

  @Test fun claimedLaunchCannotBeReclaimedThroughEitherRecordingMethod() {
    val timeline = SettingsEntryTimeline()
    val id = launched(timeline, 0)
    created(timeline, 1_000_000, id)
    assertNull(timeline.createStarted(2_000_000, id))
    assertFalse(timeline.record(id, SettingsEntryStage.CREATE_STARTED, 2_000_000))
    val unmatched = created(timeline, 3_000_000)
    assertNotEquals(id, unmatched)
    assertNull(timeline.createStarted(4_000_000, unmatched))
    assertCounts(timeline.snapshot(), entries = 2, records = 3, duplicates = 3)
    assertEntry(row(timeline.snapshot(), 1), unmatched, false, mapOf("createStarted" to 3.0))
  }

  @Test fun destroyedEntryIsClosedButDuplicateStagesRemainCounted() {
    val timeline = SettingsEntryTimeline()
    val id = launched(timeline, 0)
    created(timeline, 1_000_000, id)
    assertTrue(timeline.record(id, SettingsEntryStage.DESTROYED, 2_000_000))
    val before = row(timeline.snapshot()).toString()
    for (stage in listOf(SettingsEntryStage.CREATE_FINISHED, SettingsEntryStage.RESUME_STARTED,
        SettingsEntryStage.RESUME_FINISHED, SettingsEntryStage.FOCUS_GAINED,
        SettingsEntryStage.FIRST_PRE_DRAW, SettingsEntryStage.FIRST_FRAME_RECEIVED,
        SettingsEntryStage.LAUNCH_RETURNED, SettingsEntryStage.LAUNCH_FAILED)) {
      assertFalse(stage.name, timeline.record(id, stage, 3_000_000))
    }
    assertFalse(timeline.record(id, SettingsEntryStage.DESTROYED, 3_000_000))
    assertNull(timeline.createStarted(3_000_000, id))
    assertEquals(before, row(timeline.snapshot()).toString())
    assertCounts(timeline.snapshot(), entries = 1, records = 3, invalid = 8, duplicates = 2)
  }

  @Test fun unmatchedEntryCannotRetroactivelyAcquireLaunchEvents() {
    val timeline = SettingsEntryTimeline()
    val id = created(timeline, 0)
    for (stage in listOf(SettingsEntryStage.LAUNCH_REQUESTED, SettingsEntryStage.LAUNCH_RETURNED,
        SettingsEntryStage.LAUNCH_FAILED)) {
      assertFalse(timeline.record(id, stage, 1_000_000))
    }
    assertEntry(row(timeline.snapshot()), id, false, mapOf("createStarted" to 0.0))
    assertCounts(timeline.snapshot(), entries = 1, records = 1, invalid = 3)
  }

  @Test fun lifecycleRequiresCreateAndLaunchFailureIsRejectedAfterCreate() {
    val timeline = SettingsEntryTimeline()
    val id = launched(timeline, 0)
    for (stage in listOf(SettingsEntryStage.CREATE_FINISHED, SettingsEntryStage.RESUME_STARTED,
        SettingsEntryStage.RESUME_FINISHED, SettingsEntryStage.FOCUS_GAINED,
        SettingsEntryStage.FIRST_PRE_DRAW, SettingsEntryStage.FIRST_FRAME_RECEIVED,
        SettingsEntryStage.DESTROYED)) {
      assertFalse(timeline.record(id, stage, 100_000_000))
    }
    created(timeline, 1_000_000, id)
    assertFalse(timeline.record(id, SettingsEntryStage.LAUNCH_FAILED, 100_000_000))
    assertTrue(timeline.record(id, SettingsEntryStage.LAUNCH_RETURNED, 2_000_000))
    assertEntry(row(timeline.snapshot()), id, true, mapOf(
        "launchRequested" to 0.0, "createStarted" to 1.0, "launchReturned" to 2.0),
        mapOf("launchToCreate" to 1.0))
    assertCounts(timeline.snapshot(), entries = 1, records = 3, invalid = 8)
  }

  @Test fun recordCreateStartedClaimsOnlyAKnownUnclaimedLaunch() {
    val timeline = SettingsEntryTimeline()
    val id = launched(timeline, 0)
    assertTrue(timeline.record(id, SettingsEntryStage.CREATE_STARTED, 1_000_000))
    assertNull(timeline.createStarted(2_000_000, id))
    assertTrue(timeline.record(id, SettingsEntryStage.CREATE_FINISHED, 2_000_000))
    assertEntry(row(timeline.snapshot()), id, true, mapOf(
        "launchRequested" to 0.0, "createStarted" to 1.0, "createFinished" to 2.0),
        mapOf("launchToCreate" to 1.0, "createBody" to 1.0))
    assertCounts(timeline.snapshot(), entries = 1, records = 3, duplicates = 1)
  }

  @Test fun invalidAllocationTimestampsDoNotAllocateOrConsumeIds() {
    val timeline = SettingsEntryTimeline()
    for (time in listOf(-1L, 900_000_000_001L, Long.MIN_VALUE, Long.MAX_VALUE)) {
      assertNull(timeline.launchRequested(time))
      assertNull(timeline.createStarted(time))
    }
    assertCounts(timeline.snapshot(), entries = 0, records = 0, invalid = 8)
    val id = launched(timeline, 0)
    assertEquals(1L, id)
    created(timeline, 0, id)
    assertCounts(timeline.snapshot(), entries = 1, records = 2, invalid = 8)
  }

  @Test fun invalidRecordAndClaimTimestampsLeaveAcceptedEventsAndClaimsUntouched() {
    val timeline = SettingsEntryTimeline()
    val id = launched(timeline, 0)
    for (time in listOf(-1L, 900_000_000_001L, Long.MIN_VALUE, Long.MAX_VALUE)) {
      assertNull(timeline.createStarted(time, id))
      assertFalse(timeline.record(id, SettingsEntryStage.LAUNCH_RETURNED, time))
      assertFalse(timeline.record(id, SettingsEntryStage.LAUNCH_REQUESTED, time))
    }
    created(timeline, 0, id)
    for (time in listOf(-1L, 900_000_000_001L, Long.MIN_VALUE, Long.MAX_VALUE)) {
      assertFalse(timeline.record(id, SettingsEntryStage.FOCUS_GAINED, time))
    }
    assertTrue(timeline.record(id, SettingsEntryStage.FOCUS_GAINED, 0))
    assertEntry(row(timeline.snapshot()), id, true, mapOf(
        "launchRequested" to 0.0, "createStarted" to 0.0, "focusGained" to 0.0),
        mapOf("launchToCreate" to 0.0, "launchToFocus" to 0.0))
    assertCounts(timeline.snapshot(), entries = 1, records = 3, invalid = 16)
  }

  @Test fun backwardsNewEventsAndClaimsDoNotChangeTheLatestAcceptedTimestamp() {
    val timeline = SettingsEntryTimeline()
    val id = launched(timeline, 100_000_000)
    assertTrue(timeline.record(id, SettingsEntryStage.LAUNCH_RETURNED, 110_000_000))
    assertNull(timeline.createStarted(109_000_000, id))
    assertFalse(timeline.record(id, SettingsEntryStage.CREATE_FINISHED, 120_000_000))
    created(timeline, 115_000_000, id)
    assertFalse(timeline.record(id, SettingsEntryStage.FOCUS_GAINED, 114_000_000))
    assertTrue(timeline.record(id, SettingsEntryStage.FIRST_PRE_DRAW, 116_000_000))
    assertFalse(timeline.record(id, SettingsEntryStage.FIRST_FRAME_RECEIVED, 115_000_000))
    assertTrue(timeline.record(id, SettingsEntryStage.FIRST_FRAME_RECEIVED, 117_000_000))
    assertEntry(row(timeline.snapshot()), id, true, mapOf(
        "launchRequested" to 100.0, "launchReturned" to 110.0, "createStarted" to 115.0,
        "firstPreDraw" to 116.0, "firstFrameReceived" to 117.0), mapOf(
        "launchToCreate" to 15.0, "launchToPreDraw" to 16.0, "launchToFrameReceipt" to 17.0,
        "createToPreDraw" to 1.0, "preDrawToFrameReceipt" to 1.0))
    assertCounts(timeline.snapshot(), entries = 1, records = 5, invalid = 4)
  }

  @Test fun chronologyAndEntryIdsAreIndependentForInterleavedEntries() {
    val timeline = SettingsEntryTimeline()
    val first = launched(timeline, 100_000_000)
    val second = launched(timeline, 1_000_000)
    assertTrue(first > 0 && second > 0)
    assertNotEquals(first, second)
    created(timeline, 2_000_000, second)
    assertTrue(timeline.record(first, SettingsEntryStage.LAUNCH_RETURNED, 101_000_000))
    assertTrue(timeline.record(second, SettingsEntryStage.FOCUS_GAINED, 3_000_000))
    assertEntry(row(timeline.snapshot()), first, true,
        mapOf("launchRequested" to 100.0, "launchReturned" to 101.0))
    assertEntry(row(timeline.snapshot(), 1), second, true, mapOf(
        "launchRequested" to 1.0, "createStarted" to 2.0, "focusGained" to 3.0),
        mapOf("launchToCreate" to 1.0, "launchToFocus" to 2.0))
    assertCounts(timeline.snapshot(), entries = 2, records = 5)
  }

  @Test fun zeroAndInclusiveUpperBoundAllowEqualTimestampsAndZeroIntervals() {
    val timeline = SettingsEntryTimeline()
    val id = launched(timeline, 0)
    created(timeline, 0, id)
    assertTrue(timeline.record(id, SettingsEntryStage.LAUNCH_RETURNED, 0))
    for (stage in listOf(SettingsEntryStage.CREATE_FINISHED, SettingsEntryStage.RESUME_STARTED,
        SettingsEntryStage.RESUME_FINISHED, SettingsEntryStage.FIRST_PRE_DRAW,
        SettingsEntryStage.FIRST_FRAME_RECEIVED)) {
      assertTrue(timeline.record(id, stage, 900_000_000_000))
    }
    assertEntry(row(timeline.snapshot()), id, true, mapOf(
        "launchRequested" to 0.0, "createStarted" to 0.0, "launchReturned" to 0.0,
        "createFinished" to 900_000.0, "resumeStarted" to 900_000.0,
        "resumeFinished" to 900_000.0, "firstPreDraw" to 900_000.0,
        "firstFrameReceived" to 900_000.0), mapOf(
        "launchToCreate" to 0.0, "createBody" to 900_000.0, "resumeBody" to 0.0,
        "launchToPreDraw" to 900_000.0, "launchToFrameReceipt" to 900_000.0,
        "createToPreDraw" to 900_000.0, "preDrawToFrameReceipt" to 0.0))
    val upperLaunch = launched(timeline, 900_000_000_000)
    created(timeline, 900_000_000_000, upperLaunch)
    val upperCreate = created(timeline, 900_000_000_000)
    assertEntry(row(timeline.snapshot(), 2), upperCreate, false,
        mapOf("createStarted" to 900_000.0))
    assertCounts(timeline.snapshot(), entries = 3, records = 11)
  }

  @Test fun nanosecondDifferencesArePreservedNearTheUpperBound() {
    val timeline = SettingsEntryTimeline()
    val id = launched(timeline, 899_999_999_997)
    created(timeline, 899_999_999_998, id)
    assertTrue(timeline.record(id, SettingsEntryStage.FIRST_PRE_DRAW, 899_999_999_999))
    assertTrue(timeline.record(id, SettingsEntryStage.FIRST_FRAME_RECEIVED, 900_000_000_000))
    assertEntry(row(timeline.snapshot()), id, true, mapOf(
        "launchRequested" to 899_999.999997, "createStarted" to 899_999.999998,
        "firstPreDraw" to 899_999.999999, "firstFrameReceived" to 900_000.0), mapOf(
        "launchToCreate" to 0.000001, "launchToPreDraw" to 0.000002,
        "launchToFrameReceipt" to 0.000003, "createToPreDraw" to 0.000001,
        "preDrawToFrameReceipt" to 0.000001))
  }

  @Test fun capRetainsFirst32EntriesAndStillAllowsKnownEntryUpdates() {
    val timeline = SettingsEntryTimeline()
    val ids = (0 until 32).map { index ->
      if (index % 2 == 0) launched(timeline, index.toLong())
      else created(timeline, index.toLong())
    }
    assertEquals(32, ids.toSet().size)
    assertTrue(ids.all { it > 0 })
    assertNull(timeline.launchRequested(100))
    assertNull(timeline.createStarted(100))
    assertTrue(timeline.record(ids[0], SettingsEntryStage.LAUNCH_FAILED, 101))
    assertNull(timeline.createStarted(102, ids[0]))
    created(timeline, 102, ids[30])
    assertTrue(timeline.record(ids[30], SettingsEntryStage.DESTROYED, 103))
    assertNull(timeline.createStarted(104))
    assertNull(timeline.createStarted(104, ids[31]))
    assertNull(timeline.createStarted(104, 33))
    assertNull(timeline.launchRequested(-1))
    val snapshot = timeline.snapshot()
    assertCounts(snapshot, entries = 32, records = 35, invalid = 3, duplicates = 1, discarded = 3)
    for (index in 0 until 32) assertEquals(ids[index], row(snapshot, index).getLong("entryId"))
    assertEntry(row(snapshot), ids[0], true,
        mapOf("launchRequested" to 0.0, "launchFailed" to 0.000101))
    assertEntry(row(snapshot, 31), ids[31], false, mapOf("createStarted" to 0.000031))
  }

  @Test fun repeatedSnapshotsDetachEveryContainerAndRemainStableAfterRecordsAndMutation() {
    val timeline = SettingsEntryTimeline()
    val id = launched(timeline, 0)
    created(timeline, 1_000_000, id)
    assertTrue(timeline.record(id, SettingsEntryStage.FIRST_PRE_DRAW, 2_000_000))
    val first = timeline.snapshot()
    val sibling = timeline.snapshot()
    val original = first.toString()
    assertNotSame(first, sibling)
    assertNotSame(first.getJSONArray("entries"), sibling.getJSONArray("entries"))
    assertNotSame(row(first), row(sibling))
    assertNotSame(row(first).getJSONObject("eventsMs"), row(sibling).getJSONObject("eventsMs"))
    assertNotSame(row(first).getJSONObject("intervalsMs"), row(sibling).getJSONObject("intervalsMs"))
    assertTrue(timeline.record(id, SettingsEntryStage.FIRST_FRAME_RECEIVED, 3_000_000))
    assertFalse(timeline.record(999, SettingsEntryStage.FOCUS_GAINED, 4_000_000))
    assertEquals(original, first.toString())
    first.put("records", -1).put("entryCount", -1)
    row(first).put("entryId", -1).put("observedLaunch", false)
    row(first).getJSONObject("eventsMs").put("launchRequested", -1).put("injected", "private")
    row(first).getJSONObject("intervalsMs").put("launchToCreate", -1).remove("resumeBody")
    first.getJSONArray("entries").put(JSONObject().put("injected", true))
    assertEquals(original, sibling.toString())
    assertCounts(sibling, entries = 1, records = 3)
    val fresh = timeline.snapshot()
    assertCounts(fresh, entries = 1, records = 4, invalid = 1)
    assertEntry(row(fresh), id, true, mapOf(
        "launchRequested" to 0.0, "createStarted" to 1.0,
        "firstPreDraw" to 2.0, "firstFrameReceived" to 3.0), mapOf(
        "launchToCreate" to 1.0, "launchToPreDraw" to 2.0, "launchToFrameReceipt" to 3.0,
        "createToPreDraw" to 1.0, "preDrawToFrameReceipt" to 1.0))
  }

  @Test fun separateInstancesDoNotShareEntriesIdsOrCounters() {
    val first = SettingsEntryTimeline()
    val second = SettingsEntryTimeline()
    repeat(32) { created(first, it.toLong()) }
    assertNull(first.createStarted(32))
    assertFalse(first.record(999, SettingsEntryStage.FOCUS_GAINED, 32))
    assertFalse(first.record(1, SettingsEntryStage.CREATE_STARTED, 32))
    assertCounts(second.snapshot(), entries = 0, records = 0)
    val id = launched(second, 0)
    assertEquals(1L, id)
    created(second, 1_000_000, id)
    assertCounts(second.snapshot(), entries = 1, records = 2)
    assertCounts(first.snapshot(), entries = 32, records = 32,
        invalid = 1, duplicates = 1, discarded = 1)
  }

  @Test fun recordingAndSnapshotsRetainOnlyPrimitiveInstanceState() {
    val timeline = SettingsEntryTimeline()
    val id = launched(timeline, 0)
    created(timeline, 1, id)
    timeline.snapshot()
    // JSON, arbitrary strings and platform objects must never become retained diagnostic state.
    fun inspect(value: Any?) {
      when (value) {
        null, is Number, is Boolean, is Char -> Unit
        is LongArray, is IntArray, is BooleanArray, is DoubleArray, is FloatArray,
        is ByteArray, is ShortArray, is CharArray -> Unit
        is Array<*> -> value.forEach { inspect(it) }
        else -> {
          assertTrue("retained non-primitive state: ${value.javaClass.name}",
              value.javaClass.name.startsWith("com.immortal.launcher.SettingsEntryTimeline"))
          for (field in value.javaClass.declaredFields) {
            if (!Modifier.isStatic(field.modifiers)) {
              field.isAccessible = true
              inspect(field.get(value))
            }
          }
        }
      }
    }
    inspect(timeline)
  }

  private fun launched(timeline: SettingsEntryTimeline, time: Long): Long {
    val id = timeline.launchRequested(time)
    assertNotNull("valid launch should allocate an entry", id)
    return id!!
  }

  private fun created(timeline: SettingsEntryTimeline, time: Long, launchId: Long? = null): Long {
    val id = timeline.createStarted(time, launchId)
    assertNotNull("valid create should allocate or claim an entry", id)
    return id!!
  }

  private fun row(snapshot: JSONObject, index: Int = 0): JSONObject =
      snapshot.getJSONArray("entries").getJSONObject(index)

  private fun assertCounts(
    snapshot: JSONObject,
    entries: Int,
    records: Long,
    invalid: Long = 0,
    duplicates: Long = 0,
    discarded: Long = 0,
  ) {
    assertEquals(setOf("entries", "entryCount", "maxEntries", "records", "invalidRecords",
        "duplicateRecords", "discardedEntries", "measurement"), snapshot.keys().asSequence().toSet())
    assertEquals("elapsed wall time; first frame callback receipt is not presentation time",
        snapshot.getString("measurement"))
    assertEquals(entries, snapshot.getInt("entryCount"))
    assertEquals(entries, snapshot.getJSONArray("entries").length())
    assertEquals(32, snapshot.getInt("maxEntries"))
    assertEquals(records, snapshot.getLong("records"))
    assertEquals(invalid, snapshot.getLong("invalidRecords"))
    assertEquals(duplicates, snapshot.getLong("duplicateRecords"))
    assertEquals(discarded, snapshot.getLong("discardedEntries"))
  }

  private fun assertEntry(
    entry: JSONObject,
    id: Long,
    observedLaunch: Boolean,
    events: Map<String, Double>,
    intervals: Map<String, Double> = emptyMap(),
  ) {
    assertEquals(setOf("entryId", "observedLaunch", "eventsMs", "intervalsMs"),
        entry.keys().asSequence().toSet())
    assertEquals(id, entry.getLong("entryId"))
    assertEquals(observedLaunch, entry.getBoolean("observedLaunch"))
    val actualEvents = entry.getJSONObject("eventsMs")
    assertEquals(events.keys, actualEvents.keys().asSequence().toSet())
    events.forEach { (key, value) ->
      assertTrue(key, actualEvents.get(key) is Number)
      assertEquals(key, value, actualEvents.getDouble(key), 0.0)
    }
    val actualIntervals = entry.getJSONObject("intervalsMs")
    val intervalKeys = setOf("launchToCreate", "createBody", "resumeBody", "launchToFocus",
        "launchToPreDraw", "launchToFrameReceipt", "createToPreDraw", "preDrawToFrameReceipt")
    assertEquals(intervalKeys, actualIntervals.keys().asSequence().toSet())
    for (key in intervalKeys) {
      val expected = intervals[key]
      if (expected == null) assertSame(key, JSONObject.NULL, actualIntervals.get(key)) else {
        assertTrue(key, actualIntervals.get(key) is Number)
        assertEquals(key, expected, actualIntervals.getDouble(key), 0.0)
      }
    }
  }
}
