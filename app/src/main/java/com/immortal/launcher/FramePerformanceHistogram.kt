/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */
package com.immortal.launcher

import org.json.JSONObject
import kotlin.math.ceil

/** Caller serializes access. Bounded storage and primitive-only frame recording. */
internal class FramePerformanceHistogram(private val budgetNs: Long) {
  private val bins = LongArray(2001)
  private val sums = LongArray(8)
  private val maxima = LongArray(8)
  private val samples = LongArray(8)
  private val missing = LongArray(8)
  private var count = 0L
  private var invalid = 0L
  private var drops = 0L
  private var first = 0L
  private var steady = 0L
  private var steadyOver = 0L
  private var over = 0L
  private var over33 = 0L
  private var total = 0L
  private var maximum = 0L
  private var detailedFrames = 0L
  private var invalidDetails = 0L
  private var worstDetailedNanos = 0L
  private var worstElapsedMs = 0L
  private var worstFirstDraw = false
  private val worstComponents = LongArray(8)

  fun record(nanos: Long, dropped: Int, firstDraw: Boolean): Boolean {
    drops += dropped.coerceAtLeast(0)
    if (nanos <= 0 || nanos > 60_000_000_000L) { invalid++; return false }
    count++
    if (firstDraw) first++ else {
      steady++
      if (nanos > budgetNs) steadyOver++
    }
    if (nanos > budgetNs) over++
    if (nanos > 33_333_333L) over33++
    total += nanos
    maximum = maxOf(maximum, nanos)
    bins[(nanos / 250_000L).coerceAtMost(2000).toInt()]++
    return true
  }

  /** Primitive-only capture entry; caller reuses the component buffer. */
  fun recordDetailed(nanos: Long, dropped: Int, firstDraw: Boolean,
      elapsedMs: Long, componentNanos: LongArray): Boolean {
    require(componentNanos.size == sums.size)
    if (!record(nanos, dropped, firstDraw)) return false
    for (i in componentNanos.indices) component(i, componentNanos[i])
    if (elapsedMs !in 0L..900_000L) {
      invalidDetails++
      return true // Aggregate timing remains valid; timestamp cannot describe the worst frame.
    }
    detailedFrames++
    if (nanos > worstDetailedNanos) {
      worstDetailedNanos = nanos
      worstElapsedMs = elapsedMs
      worstFirstDraw = firstDraw
      componentNanos.copyInto(worstComponents)
    }
    return true
  }

  fun component(index: Int, nanos: Long) {
    require(index in sums.indices)
    if (nanos < 0 || nanos > 60_000_000_000L) { missing[index]++; return }
    sums[index] += nanos
    maxima[index] = maxOf(maxima[index], nanos)
    samples[index]++
  }

  fun snapshot(): JSONObject {
    fun bucket(fraction: Double): Int? {
      if (count == 0L) return null
      val rank = ceil(count * fraction).toLong()
      var cumulative = 0L
      for (index in bins.indices) {
        cumulative += bins[index]
        if (cumulative >= rank) return index
      }
      return null
    }
    val out = JSONObject().put("frames", count).put("invalidSamples", invalid)
        .put("callbackDrops", drops).put("firstDrawFrames", first)
        .put("steadyFrames", steady).put("steadyOverBudget", steadyOver)
        .put("overBudget", over).put("overBudgetIncludesFirstDraw", true)
        .put("over33_333Ms", over33).put("budgetMs", budgetNs / 1e6)
        .put("meanMs", if (count == 0L) JSONObject.NULL else total.toDouble() / count / 1e6)
        .put("maxMs", maximum / 1e6).put("overflowSamples", bins.last())
        .put("binMs", .25).put("overflowFromMs", 500)
    for ((name, fraction) in listOf("p50" to .5, "p95" to .95, "p99" to .99)) {
      val b = bucket(fraction)
      out.put(name + "Ms", if (b == null || b == 2000) JSONObject.NULL else (b + 1) * .25)
      out.put(name + "AtLeastMs", if (b == 2000) 500.0 else JSONObject.NULL)
    }
    out.put("components", JSONObject().apply {
      for (i in names.indices) put(names[i], JSONObject().put("samples", samples[i])
          .put("missing", missing[i])
          .put("meanMs", if (samples[i] == 0L) JSONObject.NULL else sums[i].toDouble() / samples[i] / 1e6)
          .put("maxMs", maxima[i] / 1e6))
    })
    out.put("detailedFrames", detailedFrames).put("invalidDetailSamples", invalidDetails)
        .put("worstFrame", if (worstDetailedNanos == 0L || worstDetailedNanos != maximum) JSONObject.NULL
        else JSONObject().put("elapsedMs", worstElapsedMs).put("totalMs", worstDetailedNanos / 1e6)
            .put("firstDraw", worstFirstDraw).put("components", JSONObject().apply {
              for (i in names.indices) put(names[i],
                  if (worstComponents[i] in 0L..60_000_000_000L) worstComponents[i] / 1e6
                  else JSONObject.NULL)
            }))
    return out
  }

  companion object {
    private val names = arrayOf("queued", "input", "animation", "layout", "draw", "sync", "commands", "swap")
  }
}
