/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */
package com.immortal.launcher

import android.os.Debug
import android.os.Handler
import android.os.HandlerThread
import android.view.FrameMetrics
import android.view.Window
import org.json.JSONObject
import kotlin.math.ceil

/** Bounded histograms; no per-frame allocation, file writes, shell command or busy polling. */
internal class SlideshowDeviceMetrics : AutoCloseable {
  enum class Phase { STARTUP, CONTROLS, NAVIGATION, TRANSITION, MOTION, HOLD, SOAK, LEGACY }
  @Volatile var phase = Phase.STARTUP
  private val lock = Any()
  private val thread = HandlerThread("slideshow-test-frames").apply { start() }
  private val handler = Handler(thread.looper)
  private val samples = Phase.values().associateWith { Histogram() }
  private var window: Window? = null
  private val listener = Window.OnFrameMetricsAvailableListener { _, metrics, dropped ->
    val nanos = metrics.getMetric(FrameMetrics.TOTAL_DURATION)
    synchronized(lock) {
      samples.getValue(phase).record(nanos, dropped,
          metrics.getMetric(FrameMetrics.FIRST_DRAW_FRAME) == 1L, metrics)
    }
  }

  /** Invoke on the main thread; listeners follow only the test-owned preview Window. */
  fun attach(next: Window) {
    window?.removeOnFrameMetricsAvailableListener(listener)
    window = next
    next.addOnFrameMetricsAvailableListener(listener, handler)
  }

  fun detach() {
    window?.removeOnFrameMetricsAvailableListener(listener)
    window = null
  }

  fun frames(): JSONObject = synchronized(lock) {
    JSONObject().apply { samples.forEach { (phase, histogram) -> put(phase.name.lowercase(), histogram.json()) } }
  }

  override fun close() {
    thread.quitSafely()
    thread.join(2_000)
  }

  private class Histogram {
    private val buckets = LongArray(2001)
    private var count = 0L
    private var dropped = 0L
    private var first = 0L
    private var invalid = 0L
    private var over16 = 0L
    private var over33 = 0L
    private var maximumNanos = 0L
    private var totalNanos = 0L
    private val componentTotals = LongArray(COMPONENTS.size)
    private val componentMaxima = LongArray(COMPONENTS.size)

    fun record(nanos: Long, skipped: Int, firstDraw: Boolean, metrics: FrameMetrics) {
      dropped += skipped.coerceAtLeast(0)
      if (firstDraw) first++
      if (nanos <= 0 || nanos > 60_000_000_000L) { invalid++; return }
      count++
      totalNanos += nanos
      maximumNanos = maxOf(maximumNanos, nanos)
      COMPONENTS.forEachIndexed { index, id ->
        val value = metrics.getMetric(id).coerceAtLeast(0)
        componentTotals[index] += value
        componentMaxima[index] = maxOf(componentMaxima[index], value)
      }
      if (nanos > 16_666_667L) over16++
      if (nanos > 33_333_333L) over33++
      buckets[(nanos / 250_000L).coerceAtMost(2000).toInt()]++
    }

    fun json(): JSONObject {
      fun percentile(fraction: Double): Any {
        if (count == 0L) return JSONObject.NULL
        val wanted = ceil(count * fraction).toLong()
        var cumulative = 0L
        buckets.forEachIndexed { index, value ->
          cumulative += value
          if (cumulative >= wanted) return (index + 1) * .25
        }
        return JSONObject.NULL
      }
      return JSONObject().put("frames", count).put("callbackDrops", dropped)
          .put("firstDrawFrames", first).put("invalidDurationSamples", invalid)
          .put("totalDurationMsMean", if (count == 0L) JSONObject.NULL else totalNanos / count / 1e6)
          .put("totalDurationMsP50", percentile(.5)).put("totalDurationMsP95", percentile(.95))
          .put("totalDurationMsP99", percentile(.99)).put("totalDurationMsMax", maximumNanos / 1e6)
          .put("over16_667Ms", over16).put("over33_333Ms", over33)
          .put("histogramOverflowSamples", buckets.last()).put("histogramBinMs", .25)
          .put("histogramOverflowFromMs", 500)
          .put("components", JSONObject().apply {
            COMPONENT_NAMES.forEachIndexed { index, name ->
              put(name, JSONObject()
                  .put("meanMs", if (count == 0L) JSONObject.NULL else componentTotals[index] / count / 1e6)
                  .put("maxMs", componentMaxima[index] / 1e6))
            }
          })
    }
  }

  companion object {
    private val COMPONENTS = intArrayOf(
        FrameMetrics.UNKNOWN_DELAY_DURATION, FrameMetrics.INPUT_HANDLING_DURATION,
        FrameMetrics.ANIMATION_DURATION, FrameMetrics.LAYOUT_MEASURE_DURATION,
        FrameMetrics.DRAW_DURATION, FrameMetrics.SYNC_DURATION,
        FrameMetrics.COMMAND_ISSUE_DURATION, FrameMetrics.SWAP_BUFFERS_DURATION)
    private val COMPONENT_NAMES = arrayOf("queued", "input", "animation", "layout",
        "draw", "sync", "commands", "swap")

    fun memory(): JSONObject {
      val info = Debug.MemoryInfo()
      Debug.getMemoryInfo(info)
      val runtime = Runtime.getRuntime()
      return JSONObject().put("processPssKiB", info.totalPss)
          .put("nativeHeapAllocatedBytes", Debug.getNativeHeapAllocatedSize())
          .put("javaHeapUsedBytes", runtime.totalMemory() - runtime.freeMemory())
          .put("javaHeapCommittedBytes", runtime.totalMemory())
    }
  }
}
