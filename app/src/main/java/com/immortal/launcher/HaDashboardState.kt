/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */

package com.immortal.launcher

/**
 * What the dashboard can be showing, from the latest fetch. Every state in the approved mock has
 * one; before, loading / offline / sign-in / no rooms all collapsed into "no areas".
 * Pure transitions ([after]), unit-tested ([HaDashboardStateTest]).
 */
sealed interface HaDashboardState {
  data object Loading : HaDashboardState
  data object NotConfigured : HaDashboardState
  /** HA refused the token. */
  data object Unauthorized : HaDashboardState
  /** Never got a house: no answer ([code] null) or an error answer. */
  data class Unreachable(val code: Int?) : HaDashboardState
  data object NoRooms : HaDashboardState
  data class Ready(override val house: HaDashboardApi.House, val fetchedAtMs: Long) : HaDashboardState
  /** A refresh failed after a good one: last known states, controls paused. */
  data class Offline(val lastKnown: HaDashboardApi.House, val fetchedAtMs: Long) : HaDashboardState

  /** The house to draw (live or last known), null in the full-screen states. */
  val house: HaDashboardApi.House? get() = (this as? Offline)?.lastKnown

  /** Minutes since the last good fetch, only while [Offline]. */
  fun offlineMinutes(nowMs: Long): Long? = (this as? Offline)?.let { ((nowMs - it.fetchedAtMs) / MS_PER_MINUTE).coerceAtLeast(0) }

  companion object {
    private const val MS_PER_MINUTE = 60_000L

    /** The state after a fetch [result] at [nowMs], given the [previous] one. */
    fun after(previous: HaDashboardState, result: HaResult<HaDashboardApi.House>, nowMs: Long): HaDashboardState =
        when (result) {
          is HaResult.Ok -> if (result.value.areas.isEmpty()) NoRooms else Ready(result.value, nowMs)
          HaResult.NotConfigured -> NotConfigured
          HaResult.Unauthorized -> Unauthorized
          HaResult.Unreachable -> stale(previous) ?: Unreachable(null)
          is HaResult.Failed -> stale(previous) ?: Unreachable(result.code)
        }

    private fun stale(previous: HaDashboardState): HaDashboardState? = when (previous) {
      is Ready -> Offline(previous.house, previous.fetchedAtMs)
      is Offline -> previous
      else -> null
    }
  }
}
