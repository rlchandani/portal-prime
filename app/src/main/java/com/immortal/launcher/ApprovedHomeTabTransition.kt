/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */
package com.immortal.launcher

/**
 * Main-thread transition ownership, independent of Compose snapshots and Android animators.
 * Positions are viewport fractions so a resize does not change the displayed progress.
 */
internal class ApprovedHomeTabTransition(initial: ApprovedLauncherTab) {
  internal data class Motion(val tab: ApprovedLauncherTab, val from: Float, val to: Float)
  internal class Plan internal constructor(val motions: List<Motion>)

  var selected = initial
    private set
  var visible: Set<ApprovedLauncherTab> = setOf(initial)
    private set
  private val positions = FloatArray(ApprovedLauncherTab.entries.size) {
    if (it == initial.ordinal) 0f else 1f
  }
  private var current: Plan? = null

  fun offset(tab: ApprovedLauncherTab): Float = positions[tab.ordinal]

  /** Only a fully hidden incoming page starts at an edge; interrupted pages keep their position. */
  fun select(tab: ApprovedLauncherTab): Plan? {
    if (tab == selected) return null
    val direction = if (tab.ordinal > selected.ordinal) 1f else -1f
    if (tab !in visible) positions[tab.ordinal] = direction
    selected = tab
    visible = visible + tab
    return Plan(visible.map {
      Motion(it, offset(it), if (it == tab) 0f else -direction)
    }).also { current = it }
  }

  /** A cancelled animator can still dispatch a callback; it cannot mutate the newer plan. */
  fun advance(plan: Plan, progress: Float): Boolean {
    if (current !== plan) return false
    plan.motions.forEach {
      positions[it.tab.ordinal] = it.from + (it.to - it.from) * progress
    }
    return true
  }

  fun finish(plan: Plan): Boolean {
    if (!advance(plan, 1f)) return false
    settle()
    return true
  }

  /** Release invalidates callbacks before the native animator and page compositions are disposed. */
  fun settle() {
    current = null
    positions[selected.ordinal] = 0f
    visible = setOf(selected)
  }
}

/** One bounded clock drives every moving page, without snapping an interrupted clock. */
internal class ApprovedHomeTabAnimation(
    val plan: ApprovedHomeTabTransition.Plan,
    private val start: Float,
) {
  // Alternate endpoints instead of resetting a clock still read by the outgoing placement.
  val target = if (start < .5f) 1f else 0f

  fun progress(clock: Float): Float = ((clock - start) / (target - start)).coerceIn(0f, 1f)

  fun offset(tab: ApprovedLauncherTab, clock: Float): Float? {
    val motion = plan.motions.firstOrNull { it.tab == tab } ?: return null
    return motion.from + (motion.to - motion.from) * progress(clock)
  }
}
