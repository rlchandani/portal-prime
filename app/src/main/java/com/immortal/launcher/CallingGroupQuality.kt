/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */
package com.immortal.launcher

import androidx.compose.runtime.Immutable

/** What this Portal captures and how much each peer connection may send. */
@Immutable
internal data class SendProfile(val width: Int, val height: Int, val fps: Int, val maxKbps: Int)

/**
 * Group sizing for the QCS605 (owner-approved: "2×2 at 360p each"). A mesh encodes once per peer,
 * so with three or more people the capture drops to 360p and each peer gets a smaller budget;
 * four people also drop to 24 fps. Low memory or a busy CPU always wins. Pure.
 */
internal object CallGroupQuality {
  val GROUP_3 = SendProfile(640, 360, 30, 800)
  val GROUP_4 = SendProfile(640, 360, 24, 600)

  fun profile(chosen: CallQuality, people: Int, degraded: Boolean): SendProfile {
    val base = if (degraded) CallQuality.LOW_MEMORY else chosen
    val solo = SendProfile(base.width, base.height, base.fps, base.maxKbps)
    return when {
      people >= CallGroup.MAX_PEOPLE -> smaller(solo, GROUP_4)
      people >= 3 -> smaller(solo, GROUP_3)
      else -> solo
    }
  }

  private fun smaller(a: SendProfile, b: SendProfile) =
      if (a.width * a.height <= b.width * b.height) a.copy(fps = minOf(a.fps, b.fps), maxKbps = minOf(a.maxKbps, b.maxKbps)) else b
}

/**
 * Per-peer bitrate: each peer connection steps its own ceiling down when that link is congested
 * (bandwidth-limited, or receiving fewer than half the frames it should) and back up once it has
 * been clean for a while. CPU limits are handled for the whole call by
 * [CallingQualityPolicy.shouldDegrade]. Pure.
 *
 * Recovery is tuned for the home LAN: an in-home link has plenty of headroom, so after a brief
 * Wi-Fi hiccup the ceiling should climb back quickly rather than leave the picture soft for most
 * of a minute. The step-down stays aggressive, and any fresh congestion resets the clean counter,
 * so a genuinely saturated link can't oscillate — it just settles low. The step-up pace
 * ([STEP_UP], [CLEAN_SAMPLES_TO_RAISE]) is the one knob worth confirming on a live 2-Portal call.
 */
internal object CallLinkBitrate {
  const val FLOOR_KBPS = 250
  const val STEP_DOWN = 0.7
  /** Each climb adds 30%: on a LAN the headroom is there, so recover briskly after a blip. */
  const val STEP_UP = 1.3
  /** Clean samples (2 s each) a link must hold before it may climb again (8 s — brisk but not twitchy). */
  const val CLEAN_SAMPLES_TO_RAISE = 4

  /**
   * Where to open a fresh link's bitrate estimate. The link is LAN-only, so start near the budget
   * — the picture opens sharp instead of ramping up from a low default — but cap the opening so a
   * very high ceiling (e.g. "Best") doesn't blast the full rate before the estimator settles.
   */
  const val LAN_FAST_START_KBPS = 2_500
  fun startEstimateKbps(ceilingKbps: Int): Int = minOf(ceilingKbps, LAN_FAST_START_KBPS).coerceAtLeast(FLOOR_KBPS)

  @Immutable
  data class State(val kbps: Int, val clean: Int = 0)

  fun next(state: State, targetKbps: Int, stats: CallStats): State {
    val congested = stats.limitedBy == "bandwidth"
    return when {
      congested -> State(maxOf(FLOOR_KBPS, (state.kbps * STEP_DOWN).toInt()), 0)
      state.kbps >= targetKbps -> State(targetKbps, 0)
      state.clean + 1 >= CLEAN_SAMPLES_TO_RAISE -> State(minOf(targetKbps, (state.kbps * STEP_UP).toInt()), 0)
      else -> state.copy(clean = state.clean + 1)
    }
  }
}
