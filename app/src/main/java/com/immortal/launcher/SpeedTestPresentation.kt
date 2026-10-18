/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */

package com.immortal.launcher

import java.util.Locale

internal enum class SpeedTestDisplayStatus { READY, RUNNING, COMPLETE, INCOMPLETE, STOPPED }
internal data class SpeedTestValues(
    val pingMs: Double = 0.0, val jitterMs: Double = 0.0,
    val downMbps: Double = 0.0, val upMbps: Double = 0.0,
)
internal data class SpeedTestMetric(val label: String, val value: String)

/** Shared interpretation of finished and interrupted speed measurements. */
internal object SpeedTestPresentation {
  const val PING = 1
  const val DOWNLOAD = 2
  const val UPLOAD = 4
  const val ALL = PING or DOWNLOAD or UPLOAD

  fun effectiveMissingMask(missing: Set<SpeedTest.Phase>, values: SpeedTestValues): Int {
    var mask = missing.fold(0) { bits, phase -> bits or when (phase) {
      SpeedTest.Phase.PING -> PING
      SpeedTest.Phase.DOWNLOAD -> DOWNLOAD
      SpeedTest.Phase.UPLOAD -> UPLOAD
      SpeedTest.Phase.DONE -> ALL
    } }
    if (!positive(values.pingMs) || !values.jitterMs.isFinite() || values.jitterMs < 0.0)
      mask = mask or PING
    if (!positive(values.downMbps)) mask = mask or DOWNLOAD
    if (!positive(values.upMbps)) mask = mask or UPLOAD
    return mask
  }

  private fun positive(value: Double) = value.isFinite() && value > 0.0

  fun caption(outcome: SpeedTestDisplayStatus): String? = when (outcome) {
    SpeedTestDisplayStatus.READY, SpeedTestDisplayStatus.COMPLETE -> null
    SpeedTestDisplayStatus.RUNNING -> "Live estimates. The test is still running."
    SpeedTestDisplayStatus.STOPPED -> "Test stopped. These are partial estimates."
    SpeedTestDisplayStatus.INCOMPLETE -> "Some measurements are unavailable. Completed results are shown."
  }

  fun rows(values: SpeedTestValues, outcome: SpeedTestDisplayStatus, missingMask: Int,
      locale: Locale = Locale.getDefault()): List<SpeedTestMetric> {
    fun metric(value: Double, unit: String, phase: Int, valid: Boolean): String {
      if (outcome == SpeedTestDisplayStatus.READY) return "—"
      if (!valid || missingMask and phase != 0) return when (outcome) {
        SpeedTestDisplayStatus.RUNNING -> "—"
        SpeedTestDisplayStatus.STOPPED -> "Not measured"
        else -> "Unavailable"
      }
      val qualifier = when (outcome) {
        SpeedTestDisplayStatus.RUNNING -> " · Live"
        SpeedTestDisplayStatus.STOPPED -> " · Partial"
        else -> ""
      }
      return String.format(locale, "%.1f %s", value, unit) + qualifier
    }
    val pingValid = positive(values.pingMs)
    return listOf(
        SpeedTestMetric("Ping", metric(values.pingMs, "ms", PING, pingValid)),
        SpeedTestMetric("Jitter", metric(values.jitterMs, "ms", PING,
            pingValid && values.jitterMs.isFinite() && values.jitterMs >= 0.0)),
        SpeedTestMetric("Download", metric(values.downMbps, "Mbps", DOWNLOAD, positive(values.downMbps))),
        SpeedTestMetric("Upload", metric(values.upMbps, "Mbps", UPLOAD, positive(values.upMbps))),
    )
  }

  fun failureText(mask: Int): String {
    val names = buildList {
      if (mask and PING != 0) { add("ping"); add("jitter") }
      if (mask and DOWNLOAD != 0) add("download")
      if (mask and UPLOAD != 0) add("upload")
    }
    if (names.isEmpty()) return "The test couldn’t finish every measurement. Check your connection and run again."
    val joined = when (names.size) {
      1 -> names[0]
      2 -> names.joinToString(" and ")
      else -> names.dropLast(1).joinToString(", ") + " and " + names.last()
    }
    return joined.replaceFirstChar { it.uppercaseChar() } +
        " couldn’t finish. Check your connection and run again."
  }
}
