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
 * (bandwidth-limited, or receiving fewer than half the frames it should) and back up slowly
 * once it has been clean for a while. CPU limits are handled for the whole call by
 * [CallingQualityPolicy.shouldDegrade]. Pure.
 */
internal object CallLinkBitrate {
  const val FLOOR_KBPS = 250
  const val STEP_DOWN = 0.7
  const val STEP_UP = 1.15
  /** Samples (2 s each) a link must stay clean before it may climb again. */
  const val CLEAN_SAMPLES_TO_RAISE = 10

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
