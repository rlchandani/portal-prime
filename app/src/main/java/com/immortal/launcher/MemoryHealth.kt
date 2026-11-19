/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */

package com.immortal.launcher

import android.os.Debug
import android.os.Process
import android.os.SystemClock
import android.util.Log
import java.io.File
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.TimeUnit
import org.json.JSONArray
import org.json.JSONObject

/**
 * A lightweight memory-health monitor: one sample every [SAMPLE_INTERVAL_MS] on a background
 * thread, judged by [MemoryHealthPolicy]. It only flags — Hearthpane can't reboot a Portal — and
 * the result surfaces in fleet `/diag` + `/info` (`memoryHealth`) and one Settings row. No popups.
 *
 * Where each reading comes from (an app uid on Android 10, `/proc` mounted `hidepid=2`):
 * - **system_server PSS**: ActivityManager's own `am_pss` lines in the events log (READ_LOGS,
 *   granted by the provisioning kit). `getProcessMemoryInfo` returns zeros for another uid and
 *   `/proc/<pid>` of system_server isn't visible. Android samples it about every 25 minutes, so the
 *   last value is kept with its age; without READ_LOGS it reads as unavailable, never as low.
 * - **MemAvailable / SwapTotal / SwapFree**: `/proc/meminfo`.
 * - **own PSS**: [Debug.getPss] for this process.
 *
 * Logs at most once per [LOG_INTERVAL_MS].
 */
internal object MemoryHealth {
  private const val TAG = "MemoryHealth"
  const val SAMPLE_INTERVAL_MS = 15L * 60 * 1000
  const val LOG_INTERVAL_MS = 60L * 60 * 1000
  private const val FIRST_SAMPLE_DELAY_MS = 60L * 1000
  private const val EVENTS_MAX_BYTES = 512 * 1024
  const val SOURCE_EVENTS_LOG = "am_pss_event_log"
  const val SOURCE_UNAVAILABLE = "unavailable"

  data class Sample(
      val values: MemoryHealthPolicy.Values,
      val result: MemoryHealthPolicy.Result,
      val sampledAtEpochMs: Long,
      val systemServerSource: String,
  )

  @Volatile private var latest: Sample? = null
  private var systemServer: MemoryHealthPolicy.SystemServerSample? = null // guarded by this
  private val logThrottle = MemoryHealthPolicy.LogThrottle(LOG_INTERVAL_MS) // guarded by this
  private var scheduler: ScheduledExecutorService? = null // guarded by this

  /** Starts the periodic sampler once per process. Safe to call repeatedly. */
  @Synchronized
  fun start() {
    if (scheduler != null) return
    scheduler = Executors.newSingleThreadScheduledExecutor { task ->
      Thread({
        Process.setThreadPriority(Process.THREAD_PRIORITY_BACKGROUND)
        task.run()
      }, "memory-health").apply { isDaemon = true }
    }.also {
      it.scheduleWithFixedDelay({ runCatching { sampleNow() }.onFailure { e -> Log.w(TAG, "sample failed", e) } },
          FIRST_SAMPLE_DELAY_MS, SAMPLE_INTERVAL_MS, TimeUnit.MILLISECONDS)
    }
  }

  /** The newest sample, or null before the first one. Never blocks. */
  fun latest(): Sample? = latest

  /** The newest sample, taking one now if none exists yet. Call off the main thread. */
  fun latestOrSample(): Sample = latest ?: sampleNow()

  /** Takes a sample now (blocking: reads the events log and this process's smaps). Off main thread. */
  @Synchronized
  fun sampleNow(): Sample {
    val now = System.currentTimeMillis()
    MemoryHealthPolicy.parseSystemServerPss(readEventsLog())?.let { fresh ->
      val kept = systemServer
      if (kept == null || fresh.atEpochMs >= kept.atEpochMs) systemServer = fresh
    }
    val meminfo = runCatching { MemoryHealthPolicy.parseMeminfo(File("/proc/meminfo").readText()) }
        .onFailure { Log.w(TAG, "meminfo unreadable", it) }
        .getOrDefault(emptyMap())
    val system = systemServer
    val values = MemoryHealthPolicy.Values(
        systemServerPssKb = system?.pssKb,
        systemServerSampleAgeMs = system?.let { (now - it.atEpochMs).coerceAtLeast(0) },
        memAvailableKb = meminfo["MemAvailable"],
        swapTotalKb = meminfo["SwapTotal"],
        swapFreeKb = meminfo["SwapFree"],
        ownPssKb = runCatching { Debug.getPss() }.getOrNull()?.takeIf { it > 0 },
    )
    val sample = Sample(values, MemoryHealthPolicy.evaluate(values), now,
        if (system != null) SOURCE_EVENTS_LOG else SOURCE_UNAVAILABLE)
    latest = sample
    if (logThrottle.shouldLog(SystemClock.elapsedRealtime())) Log.i(TAG, summary(sample))
    return sample
  }

  private fun readEventsLog(): String = runCatching {
    val process = ProcessBuilder("logcat", "-d", "-b", "events", "-v", "epoch", "-s", "am_pss")
        .redirectErrorStream(true).start()
    try {
      process.inputStream.use { input ->
        val bytes = java.io.ByteArrayOutputStream()
        val buf = ByteArray(16 * 1024)
        while (bytes.size() < EVENTS_MAX_BYTES) {
          val n = input.read(buf, 0, minOf(buf.size, EVENTS_MAX_BYTES - bytes.size()))
          if (n < 0) break
          bytes.write(buf, 0, n)
        }
        bytes.toString("UTF-8")
      }
    } finally {
      process.destroy()
    }
  }.onFailure { Log.w(TAG, "events log unreadable", it) }.getOrDefault("")

  private fun summary(sample: Sample): String {
    val v = sample.values
    return "memory ${sample.result.status.wire}" +
        (if (sample.result.reasons.isEmpty()) "" else " reasons=${sample.result.reasons.joinToString(",") { it.wire }}") +
        " systemServer=${MemoryHealthPolicy.formatMb(v.systemServerPssKb)}" +
        " available=${MemoryHealthPolicy.formatMb(v.memAvailableKb)}" +
        " swapFree=${v.swapFreePercent?.let { "$it%" } ?: "n/a"}" +
        " own=${MemoryHealthPolicy.formatMb(v.ownPssKb)}"
  }

  /** Fleet wire form: `{status, reasons, values, thresholds, sampledAt}`; sizes in MB. */
  fun toJson(sample: Sample?, nowEpochMs: Long = System.currentTimeMillis()): JSONObject {
    if (sample == null) {
      return JSONObject().put("status", MemoryHealthPolicy.Status.UNKNOWN.wire).put("reasons", JSONArray())
          .put("values", JSONObject()).put("sampled", false)
    }
    val v = sample.values
    fun mb(kb: Long?): Any = kb?.let { it / 1024 } ?: JSONObject.NULL
    return JSONObject()
        .put("status", sample.result.status.wire)
        .put("reasons", JSONArray(sample.result.reasons.map { it.wire }))
        .put("values", JSONObject()
            .put("systemServerPssMb", mb(v.systemServerPssKb))
            .put("systemServerSampleAgeMin", v.systemServerSampleAgeMs?.let { it / 60_000 } ?: JSONObject.NULL)
            .put("systemServerSource", sample.systemServerSource)
            .put("memAvailableMb", mb(v.memAvailableKb))
            .put("swapTotalMb", mb(v.swapTotalKb))
            .put("swapFreeMb", mb(v.swapFreeKb))
            .put("swapFreePercent", v.swapFreePercent ?: JSONObject.NULL)
            .put("ownPssMb", mb(v.ownPssKb)))
        .put("thresholds", JSONObject()
            .put("systemServerPssMaxMb", MemoryHealthPolicy.SYSTEM_SERVER_LIMIT_KB / 1024)
            .put("swapFreeMinPercent", MemoryHealthPolicy.SWAP_FREE_MIN_PERCENT)
            .put("memAvailableMinMb", MemoryHealthPolicy.MEM_AVAILABLE_MIN_KB / 1024))
        .put("sampled", true)
        .put("sampleAgeSec", ((nowEpochMs - sample.sampledAtEpochMs) / 1000).coerceAtLeast(0))
  }
}
