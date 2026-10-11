/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */
package com.immortal.launcher

import org.json.JSONArray
import org.json.JSONObject

/** Bounded, primitive-only same-callback tail records; caller serializes access. */
internal class FramePerformanceSlowFrames(private val minNanos: Long = 100_000_000L) {
  init { require(minNanos in 1L..60_000_000_000L) }
  private class Sample {
    var nanos = 0L
    var elapsedMs = 0L
    var firstDraw = false
    val components = LongArray(8)
    fun replace(nanos: Long, firstDraw: Boolean, elapsedMs: Long, components: LongArray) {
      this.nanos = nanos; this.elapsedMs = elapsedMs; this.firstDraw = firstDraw
      components.copyInto(this.components)
    }
  }
  private val samples = ArrayList<Sample>(MAX_RECORDS)
  private var smallest = 0
  private var eligibleFrames = 0L

  /** true means eligible, including records omitted from the bounded top set. */
  fun record(nanos: Long, firstDraw: Boolean, elapsedMs: Long, components: LongArray): Boolean {
    require(components.size == 8)
    if (nanos !in minNanos..60_000_000_000L || elapsedMs !in 0L..900_000L) return false
    eligibleFrames++
    val sample = if (samples.size < MAX_RECORDS) Sample().also { samples.add(it) }
        else if (nanos > samples[smallest].nanos) samples[smallest] else return true
    sample.replace(nanos, firstDraw, elapsedMs, components)
    smallest = 0
    for (i in 1 until samples.size) if (samples[i].nanos < samples[smallest].nanos) smallest = i
    return true
  }

  fun snapshot(): JSONObject = JSONObject().put("maxRecords", MAX_RECORDS).put("minTotalMs", minNanos / 1e6)
      .put("eligibleFrames", eligibleFrames).put("omittedFrames", eligibleFrames - samples.size)
      .put("frames", JSONArray().apply {
        // Sort only on explicit reads. Recording reuses slots and never creates JSON objects.
        for (sample in samples.sortedByDescending { it.nanos }) put(JSONObject()
            .put("elapsedMs", sample.elapsedMs).put("totalMs", sample.nanos / 1e6).put("firstDraw", sample.firstDraw)
            .put("components", JSONObject().apply {
              for (i in names.indices) put(names[i],
                  if (sample.components[i] in 0L..60_000_000_000L) sample.components[i] / 1e6 else JSONObject.NULL)
            }))
      })
  companion object {
    const val MAX_RECORDS = 32
    private val names = arrayOf("queued", "input", "animation", "layout", "draw", "sync", "commands", "swap")
  }
}
