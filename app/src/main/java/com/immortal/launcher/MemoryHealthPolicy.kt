/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */

package com.immortal.launcher

/**
 * Pure memory-health rules for a Portal: is it time to restart? On 2026-11-19 a system_server
 * that had grown to 1.86 GB pushed Family into swap and made calls stutter; a reboot cured it.
 * A Portal "needs restart" when any one of these holds:
 *
 * - system_server PSS above [SYSTEM_SERVER_LIMIT_KB] (500 MB) — counted only when the sample
 *   is fresher than [SYSTEM_SERVER_MAX_AGE_MS], since Android logs it every ~25 minutes;
 * - swap free below [SWAP_FREE_MIN_PERCENT] (15 %) of swap total (skipped without swap);
 * - MemAvailable below [MEM_AVAILABLE_MIN_KB] (300 MB).
 *
 * With none of the readings available the status is [Status.UNKNOWN], never a guessed "good".
 * Inputs are kB, as /proc/meminfo reports them.
 */
internal object MemoryHealthPolicy {
  const val SYSTEM_SERVER_LIMIT_KB = 500L * 1024
  const val SWAP_FREE_MIN_PERCENT = 15
  const val MEM_AVAILABLE_MIN_KB = 300L * 1024
  const val SYSTEM_SERVER_MAX_AGE_MS = 2L * 60 * 60 * 1000

  enum class Status(val wire: String) {
    GOOD("good"),
    RESTART_RECOMMENDED("restart_recommended"),
    UNKNOWN("unknown"),
  }

  enum class Reason(val wire: String) {
    SYSTEM_SERVER_HIGH("system_server_pss_high"),
    SWAP_FREE_LOW("swap_free_low"),
    MEM_AVAILABLE_LOW("mem_available_low"),
  }

  /** One reading. Null = not readable on this device or not sampled yet. */
  data class Values(
      val systemServerPssKb: Long? = null,
      val systemServerSampleAgeMs: Long? = null,
      val memAvailableKb: Long? = null,
      val swapTotalKb: Long? = null,
      val swapFreeKb: Long? = null,
      val ownPssKb: Long? = null,
  ) {
    /** The system_server reading, if it is recent enough to judge by. */
    val freshSystemServerPssKb: Long?
      get() = systemServerPssKb?.takeIf { pss ->
        pss > 0 && systemServerSampleAgeMs != null && systemServerSampleAgeMs in 0..SYSTEM_SERVER_MAX_AGE_MS
      }

    /** Whole percent of swap still free; null without swap or without a reading. */
    val swapFreePercent: Int?
      get() {
        val total = swapTotalKb ?: return null
        val free = swapFreeKb ?: return null
        if (total <= 0) return null
        return ((free.coerceIn(0, total) * 100) / total).toInt()
      }
  }

  data class Result(val status: Status, val reasons: List<Reason>)

  fun evaluate(values: Values): Result {
    val system = values.freshSystemServerPssKb
    val swapPercent = values.swapFreePercent
    val available = values.memAvailableKb
    val reasons = buildList {
      if (system != null && system > SYSTEM_SERVER_LIMIT_KB) add(Reason.SYSTEM_SERVER_HIGH)
      if (swapPercent != null && swapFreeBelowLimit(values)) add(Reason.SWAP_FREE_LOW)
      if (available != null && available < MEM_AVAILABLE_MIN_KB) add(Reason.MEM_AVAILABLE_LOW)
    }
    val status = when {
      reasons.isNotEmpty() -> Status.RESTART_RECOMMENDED
      system == null && swapPercent == null && available == null -> Status.UNKNOWN
      else -> Status.GOOD
    }
    return Result(status, reasons)
  }

  /** Exact comparison (free/total < 15 %), not the rounded [Values.swapFreePercent]. */
  private fun swapFreeBelowLimit(values: Values): Boolean {
    val total = values.swapTotalKb ?: return false
    val free = values.swapFreeKb ?: return false
    return total > 0 && free.coerceAtLeast(0) * 100 < total * SWAP_FREE_MIN_PERCENT
  }

  /** The plain-words cause shown under the Settings row, most actionable first. */
  fun describe(reason: Reason, values: Values): String = when (reason) {
    Reason.SYSTEM_SERVER_HIGH ->
      "Android’s system process is using ${formatMb(values.systemServerPssKb)}."
    Reason.SWAP_FREE_LOW -> "Only ${values.swapFreePercent ?: 0}% of swap is free."
    Reason.MEM_AVAILABLE_LOW -> "Only ${formatMb(values.memAvailableKb)} of memory is available."
  }

  internal fun formatMb(kb: Long?): String {
    if (kb == null) return "unknown"
    val mb = kb / 1024
    return if (mb >= 1024) String.format(java.util.Locale.US, "%.1f GB", mb / 1024.0) else "$mb MB"
  }

  // --- parsers (pure, so the JVM tests cover the on-device formats) ---------------------------

  /** `/proc/meminfo` → kB per key (`MemAvailable`, `SwapTotal`, `SwapFree`, …). Unreadable lines are skipped. */
  fun parseMeminfo(text: String): Map<String, Long> {
    val out = HashMap<String, Long>()
    for (line in text.lineSequence()) {
      val colon = line.indexOf(':')
      if (colon <= 0) continue
      val value = line.substring(colon + 1).trim().substringBefore(' ').toLongOrNull() ?: continue
      out[line.substring(0, colon).trim()] = value
    }
    return out
  }

  /** The newest system_server PSS ActivityManager wrote to the events log. */
  data class SystemServerSample(val pssKb: Long, val atEpochMs: Long)

  private val AM_PSS_LINE = Regex("""^\s*(\d+)(?:\.(\d{1,9}))?\s+\d+\s+\d+\s+\w\s+am_pss\s*:\s*\[(.*)]\s*$""")

  /**
   * Parses `logcat -b events -v epoch -s am_pss` output. ActivityManager logs
   * `am_pss [pid,uid,process,pss,uss,swapPss,rss,…]` (bytes) for every process it samples,
   * system_server included as uid 1000 named `system`. Returns the newest such line, or null.
   */
  fun parseSystemServerPss(events: String): SystemServerSample? {
    var newest: SystemServerSample? = null
    for (line in events.lineSequence()) {
      val match = AM_PSS_LINE.matchEntire(line) ?: continue
      val fields = match.groupValues[3].split(',').map { it.trim() }
      if (fields.size < 4 || fields[1] != "1000" || fields[2] != "system") continue
      val pssBytes = fields[3].toLongOrNull()?.takeIf { it > 0 } ?: continue
      val seconds = match.groupValues[1].toLongOrNull() ?: continue
      val millis = match.groupValues[2].padEnd(3, '0').take(3).toLongOrNull() ?: 0L
      val sample = SystemServerSample(pssBytes / 1024, seconds * 1000 + millis)
      if (newest == null || sample.atEpochMs >= newest.atEpochMs) newest = sample
    }
    return newest
  }

  /** Allows one log line per [intervalMs]; the first call always logs. Not thread-safe by itself. */
  class LogThrottle(private val intervalMs: Long) {
    private var lastAtMs: Long? = null

    fun shouldLog(nowMs: Long): Boolean {
      val last = lastAtMs
      if (last != null && nowMs - last in 0 until intervalMs) return false
      lastAtMs = nowMs
      return true
    }
  }
}
