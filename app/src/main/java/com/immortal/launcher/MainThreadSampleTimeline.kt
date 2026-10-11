/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */
package com.immortal.launcher

import org.json.JSONArray
import org.json.JSONObject

/**
 * Caller serializes access. Retains bounded code identities and copied frame IDs, never VM
 * frames, source filenames, line numbers or threads. Observations describe wall time with
 * profiling overhead; neither stack-read cost nor a non-waiting stack measures app CPU time.
 */
internal class MainThreadSampleTimeline {
  private class Record(
      val observation: Long,
      val startedMs: Long,
      val endedMs: Long,
      val waiting: Boolean,
      val operationMask: Int,
      val frameIds: IntArray,
  )

  private val records = arrayOfNulls<Record>(MAX_RECORDS)
  private var oldest = 0
  private var recordCount = 0
  // Symbols outlive record eviction, so every admitted ID stays stable for this timeline.
  private val symbols = LinkedHashMap<String, Int>()
  private var eligibleRecords = 0L
  private var omittedRecords = 0L
  private var excludedNativePollSamples = 0L
  private var invalidIntervals = 0L
  private var invalidObservations = 0L
  private var invalidOperationMasks = 0L
  private var emptySamples = 0L
  private var discardedDepthFrames = 0L
  private var discardedIdentities = 0L

  fun record(
      stack: Array<StackTraceElement>,
      startedMs: Long,
      endedMs: Long,
      waiting: Boolean,
      operationMask: Int,
      observation: Long,
  ): Boolean {
    if (startedMs !in 0L..MAX_INTERVAL_MS || endedMs !in startedMs..MAX_INTERVAL_MS) {
      invalidIntervals++
      return false
    }
    if (observation !in 1L..MAX_OBSERVATION) {
      invalidObservations++
      return false
    }
    if (operationMask < 0) {
      invalidOperationMasks++
      return false
    }
    if (stack.isEmpty()) {
      emptySamples++
      return false
    }
    if (stack[0].className == "android.os.MessageQueue" &&
        stack[0].methodName == "nativePollOnce") {
      excludedNativePollSamples++
      return false
    }
    val depth = minOf(stack.size, MAX_DEPTH)
    discardedDepthFrames += stack.size.toLong() - depth
    val frameIds = IntArray(depth)
    for (i in 0 until depth) {
      val frame = stack[i]
      if (frame.className.length.toLong() + 1 + frame.methodName.length > MAX_SYMBOL_CHARS) {
        discardedIdentities++
        frameIds[i] = -1
        continue
      }
      val identity = frame.className + "." + frame.methodName
      val id = symbols[identity] ?: if (symbols.size < MAX_SYMBOLS) {
        symbols.size.also { symbols[identity] = it }
      } else null
      if (id == null) {
        discardedIdentities++
        frameIds[i] = -1
      } else frameIds[i] = id
    }
    val record = Record(observation, startedMs, endedMs, waiting, operationMask, frameIds)
    if (recordCount < MAX_RECORDS) {
      records[(oldest + recordCount) % MAX_RECORDS] = record
      recordCount++
    } else {
      records[oldest] = record
      oldest = (oldest + 1) % MAX_RECORDS
      omittedRecords++
    }
    eligibleRecords++
    return true
  }

  /** Every JSON container is new; callers cannot mutate records or the symbol pool through it. */
  fun snapshot(): JSONObject = JSONObject()
      .put("measurement", "wall-time stack observations")
      .put("profilingOverhead", true)
      .put("cpuTimeMeasured", false)
      .put("deliveredFpsMeasured", false)
      .put("frameIdentity", "class.method; ordered stack frames including recursion")
      .put("recordOrder", "oldest-to-newest retained observations")
      .put("intervalMeaning", "capture-relative milliseconds; readCostMs measures stack retrieval, not app execution")
      .put("waitingMeaning", "caller-supplied wait classification; false does not imply CPU execution")
      .put("operationMaskMeaning", "caller-supplied operation family bit mask")
      .put("maxRecords", MAX_RECORDS)
      .put("maxDepth", MAX_DEPTH)
      .put("maxSymbols", MAX_SYMBOLS)
      .put("maxSymbolChars", MAX_SYMBOL_CHARS)
      .put("maxIntervalMs", MAX_INTERVAL_MS)
      .put("maxObservation", MAX_OBSERVATION)
      .put("eligibleRecords", eligibleRecords)
      .put("omittedRecords", omittedRecords)
      .put("excludedNativePollSamples", excludedNativePollSamples)
      .put("invalidIntervals", invalidIntervals)
      .put("invalidObservations", invalidObservations)
      .put("invalidOperationMasks", invalidOperationMasks)
      .put("emptySamples", emptySamples)
      .put("discardedDepthFrames", discardedDepthFrames)
      .put("discardedIdentities", discardedIdentities)
      .put("uniqueSymbols", symbols.size)
      .put("symbols", JSONArray().apply { symbols.keys.forEach { put(it) } })
      .put("records", JSONArray().apply {
        for (i in 0 until recordCount) {
          val record = checkNotNull(records[(oldest + i) % MAX_RECORDS])
          put(JSONObject()
              .put("observation", record.observation)
              .put("startedMs", record.startedMs)
              .put("endedMs", record.endedMs)
              .put("readCostMs", record.endedMs - record.startedMs)
              .put("waiting", record.waiting)
              .put("operationMask", record.operationMask)
              .put("frameIds", JSONArray().apply { record.frameIds.forEach { put(it) } }))
        }
      })

  private companion object {
    const val MAX_RECORDS = 128
    const val MAX_SYMBOLS = 1024
    const val MAX_DEPTH = 64
    const val MAX_SYMBOL_CHARS = 512
    const val MAX_INTERVAL_MS = 60000L
    const val MAX_OBSERVATION = 6000L
  }
}
