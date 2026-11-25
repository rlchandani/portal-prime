/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */
package com.immortal.launcher

import androidx.compose.runtime.Immutable

/** How a live call is doing right now, from one member's stats. Drives the quiet in-call signal. */
internal enum class CallHealth { GOOD, FAIR, POOR }

/**
 * Reads one [CallStats] sample into a human call-health level. Pure, so the thresholds are pinned
 * by tests rather than guessed on a live call. The home LAN should sit comfortably in [GOOD] — a
 * round trip over home Wi-Fi is a few milliseconds and nothing limits the encoder — so [FAIR] and
 * [POOR] only show when the link is genuinely strained (contended Wi-Fi, a busy CPU, a picture
 * that has frozen). That is exactly when the person wants a plain-words reason the picture softened,
 * rather than wondering whether the other room left.
 */
internal object CallHealthMeter {
  /** A round trip at or above this (ms) is slow even for busy home Wi-Fi. */
  const val POOR_RTT_MS = 300
  /** Noticeably slow, but still usable. */
  const val FAIR_RTT_MS = 150
  /** Inbound video at or below this fps while bytes still arrive reads as a frozen/stuttering picture. */
  const val FROZEN_RECV_FPS = 1.0
  /** Inbound video this low while the link is congested is a poor, not merely eased, picture. */
  const val LOW_RECV_FPS = 8.0

  @Immutable
  data class Reading(val health: CallHealth, val reason: String)

  /**
   * Classify one sample. [remoteCameraOn] gates the inbound-picture checks: a camera the other
   * room turned off sends no frames and must never read as a problem.
   */
  fun of(stats: CallStats, remoteCameraOn: Boolean = true): Reading {
    val rtt = stats.rttMs
    val congested = stats.limitedBy == "bandwidth"
    val cpu = stats.limitedBy == "cpu"
    val receiving = remoteCameraOn && stats.recvKbps > 0
    val frozen = receiving && stats.recvFps <= FROZEN_RECV_FPS
    return when {
      frozen -> Reading(CallHealth.POOR, "Picture is catching up")
      rtt != null && rtt >= POOR_RTT_MS -> Reading(CallHealth.POOR, if (congested) "Network is busy" else "Connection is slow")
      congested && receiving && stats.recvFps <= LOW_RECV_FPS -> Reading(CallHealth.POOR, "Network is busy")
      congested -> Reading(CallHealth.FAIR, "Easing quality to keep up")
      cpu -> Reading(CallHealth.FAIR, "Keeping it smooth")
      rtt != null && rtt >= FAIR_RTT_MS -> Reading(CallHealth.FAIR, "Connection is a little slow")
      else -> Reading(CallHealth.GOOD, "Good")
    }
  }
}
