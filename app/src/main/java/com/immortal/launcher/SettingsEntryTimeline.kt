/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */
package com.immortal.launcher

import org.json.JSONArray
import org.json.JSONObject

internal enum class SettingsEntryStage(val key: String) {
  LAUNCH_REQUESTED("launchRequested"),
  LAUNCH_RETURNED("launchReturned"),
  LAUNCH_FAILED("launchFailed"),
  CREATE_STARTED("createStarted"),
  CREATE_FINISHED("createFinished"),
  RESUME_STARTED("resumeStarted"),
  RESUME_FINISHED("resumeFinished"),
  FOCUS_GAINED("focusGained"),
  FIRST_PRE_DRAW("firstPreDraw"),
  FIRST_FRAME_RECEIVED("firstFrameReceived"),
  DESTROYED("destroyed"),
}

/**
 * Pure, bounded startup evidence; caller serializes access. Timestamps are nanoseconds relative
 * to capture start and monotonic within each entry. Retains the first 32 entries without eviction
 * or ID reuse, storing only primitives. Launch failure and destruction close an entry.
 *
 * records counts accepted stage events. In-range repeats count as duplicates, including after
 * closure; other rejected events count as invalid. Valid allocations refused at capacity count
 * as discarded entries. Rejections never change accepted timestamps or attribution.
 */
internal class SettingsEntryTimeline {
  private val stageCount = SettingsEntryStage.entries.size
  private val eventsNs = LongArray(MAX_ENTRIES * stageCount) { MISSING_NS }
  private val latestNs = LongArray(MAX_ENTRIES) { MISSING_NS }
  private var entryCount = 0
  private var records = 0L
  private var invalidRecords = 0L
  private var duplicateRecords = 0L
  private var discardedEntries = 0L

  fun launchRequested(elapsedNs: Long): Long? =
      allocate(SettingsEntryStage.LAUNCH_REQUESTED, elapsedNs)

  /** A supplied ID must identify an observed, unclaimed launch; null always creates unmatched. */
  fun createStarted(elapsedNs: Long, launchId: Long? = null): Long? {
    if (launchId == null) return allocate(SettingsEntryStage.CREATE_STARTED, elapsedNs)
    return if (record(launchId, SettingsEntryStage.CREATE_STARTED, elapsedNs)) launchId else null
  }

  /** CREATE_STARTED may also claim a known launch here, using the same validation boundary. */
  fun record(entryId: Long, stage: SettingsEntryStage, elapsedNs: Long): Boolean {
    if (elapsedNs !in 0L..MAX_ELAPSED_NS || entryId !in 1L..entryCount.toLong()) {
      return invalid()
    }
    val index = (entryId - 1).toInt()
    if (eventNs(index, stage) != MISSING_NS) {
      duplicateRecords++
      return false
    }
    if (eventNs(index, SettingsEntryStage.LAUNCH_FAILED) != MISSING_NS ||
        eventNs(index, SettingsEntryStage.DESTROYED) != MISSING_NS) {
      return invalid()
    }
    val launched = eventNs(index, SettingsEntryStage.LAUNCH_REQUESTED) != MISSING_NS
    val created = eventNs(index, SettingsEntryStage.CREATE_STARTED) != MISSING_NS
    val eligible = when (stage) {
      // Launch observation is established only by allocation, never retroactively by record.
      SettingsEntryStage.LAUNCH_REQUESTED -> false
      SettingsEntryStage.LAUNCH_RETURNED -> launched
      SettingsEntryStage.LAUNCH_FAILED -> launched && !created
      SettingsEntryStage.CREATE_STARTED -> launched
      else -> created
    }
    if (!eligible || elapsedNs < latestNs[index]) return invalid()
    accept(index, stage, elapsedNs)
    return true
  }

  /** Every container is new; neither caller mutation nor later records affect prior snapshots. */
  fun snapshot(): JSONObject {
    val entries = JSONArray()
    for (index in 0 until entryCount) {
      val eventsMs = JSONObject()
      for (stage in SettingsEntryStage.entries) {
        val elapsedNs = eventNs(index, stage)
        if (elapsedNs != MISSING_NS) eventsMs.put(stage.key, elapsedNs / NS_PER_MS)
      }
      val intervalsMs = JSONObject()
          .put("launchToCreate", intervalMs(index,
              SettingsEntryStage.LAUNCH_REQUESTED, SettingsEntryStage.CREATE_STARTED))
          .put("createBody", intervalMs(index,
              SettingsEntryStage.CREATE_STARTED, SettingsEntryStage.CREATE_FINISHED))
          .put("resumeBody", intervalMs(index,
              SettingsEntryStage.RESUME_STARTED, SettingsEntryStage.RESUME_FINISHED))
          .put("launchToFocus", intervalMs(index,
              SettingsEntryStage.LAUNCH_REQUESTED, SettingsEntryStage.FOCUS_GAINED))
          .put("launchToPreDraw", intervalMs(index,
              SettingsEntryStage.LAUNCH_REQUESTED, SettingsEntryStage.FIRST_PRE_DRAW))
          .put("launchToFrameReceipt", intervalMs(index,
              SettingsEntryStage.LAUNCH_REQUESTED, SettingsEntryStage.FIRST_FRAME_RECEIVED))
          .put("createToPreDraw", intervalMs(index,
              SettingsEntryStage.CREATE_STARTED, SettingsEntryStage.FIRST_PRE_DRAW))
          .put("preDrawToFrameReceipt", intervalMs(index,
              SettingsEntryStage.FIRST_PRE_DRAW, SettingsEntryStage.FIRST_FRAME_RECEIVED))
      entries.put(JSONObject()
          .put("entryId", index + 1L)
          .put("observedLaunch", eventNs(index, SettingsEntryStage.LAUNCH_REQUESTED) != MISSING_NS)
          .put("eventsMs", eventsMs)
          .put("intervalsMs", intervalsMs))
    }
    return JSONObject()
        .put("entries", entries)
        .put("entryCount", entryCount)
        .put("maxEntries", MAX_ENTRIES)
        .put("records", records)
        .put("invalidRecords", invalidRecords)
        .put("duplicateRecords", duplicateRecords)
        .put("discardedEntries", discardedEntries)
        .put("measurement", "elapsed wall time; first frame callback receipt is not presentation time")
  }

  private fun allocate(stage: SettingsEntryStage, elapsedNs: Long): Long? {
    if (elapsedNs !in 0L..MAX_ELAPSED_NS) {
      invalid()
      return null
    }
    if (entryCount == MAX_ENTRIES) {
      discardedEntries++
      return null
    }
    val index = entryCount++
    accept(index, stage, elapsedNs)
    return index + 1L
  }

  private fun accept(index: Int, stage: SettingsEntryStage, elapsedNs: Long) {
    eventsNs[index * stageCount + stage.ordinal] = elapsedNs
    latestNs[index] = elapsedNs
    records++
  }

  private fun invalid(): Boolean {
    invalidRecords++
    return false
  }

  private fun eventNs(index: Int, stage: SettingsEntryStage): Long =
      eventsNs[index * stageCount + stage.ordinal]

  private fun intervalMs(index: Int, from: SettingsEntryStage, to: SettingsEntryStage): Any {
    val start = eventNs(index, from)
    val end = eventNs(index, to)
    return if (start == MISSING_NS || end < start) JSONObject.NULL else (end - start) / NS_PER_MS
  }

  private companion object {
    const val MAX_ENTRIES = 32
    const val MAX_ELAPSED_NS = 900_000_000_000L
    const val MISSING_NS = -1L
    const val NS_PER_MS = 1_000_000.0
  }
}
