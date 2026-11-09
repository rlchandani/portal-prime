/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */
package com.immortal.launcher

import kotlin.math.abs

/** The pull-up sheet's tabs, in order. */
internal enum class MusicSheetTab(val label: String) {
  UP_NEXT("Up next"), LIBRARY("Library"), RECENT("Recently played"), SEARCH("Search"),
}

/** Cards that open over the closed Now Playing (never while the sheet is open). */
internal enum class MusicPopover { NONE, SLEEP, MOVE, ACCOUNTS }

/** The stage's UI state; immutable, changed only through [MusicSheetMachine]. */
internal data class MusicSheetState(
    val open: Boolean = false,
    val tab: MusicSheetTab = MusicSheetTab.UP_NEXT,
    val searchFocused: Boolean = false,
    val popover: MusicPopover = MusicPopover.NONE,
) {
  /** Spring target for the sheet progress. */
  val openTarget: Float get() = if (open) 1f else 0f
  /** Spring target for the Search-focus squeeze (only while open on Search). */
  val focusTarget: Float get() = if (open && searchFocused) 1f else 0f
}

/** Pure transitions of [MusicSheetState]. */
internal object MusicSheetMachine {
  /** Nothing loaded: the sheet opens on Library; something loaded: on Up next. */
  fun initial(idle: Boolean): MusicSheetState =
      MusicSheetState(tab = if (idle) MusicSheetTab.LIBRARY else MusicSheetTab.UP_NEXT)

  /** Opening closes every popover (they belong to the closed view). */
  fun open(s: MusicSheetState, tab: MusicSheetTab? = null): MusicSheetState =
      s.copy(open = true, tab = tab ?: s.tab, popover = MusicPopover.NONE,
          searchFocused = s.searchFocused && (tab ?: s.tab) == MusicSheetTab.SEARCH)

  fun close(s: MusicSheetState): MusicSheetState = s.copy(open = false, searchFocused = false)

  fun selectTab(s: MusicSheetState, tab: MusicSheetTab): MusicSheetState =
      s.copy(tab = tab, searchFocused = s.searchFocused && tab == MusicSheetTab.SEARCH)

  fun focusSearch(s: MusicSheetState, focused: Boolean): MusicSheetState =
      s.copy(searchFocused = focused && s.open && s.tab == MusicSheetTab.SEARCH)

  /** A popover button toggles its card; ignored while the sheet is open. */
  fun toggle(s: MusicSheetState, p: MusicPopover): MusicSheetState =
      if (s.open) s else s.copy(popover = if (s.popover == p) MusicPopover.NONE else p)

  fun dismissPopover(s: MusicSheetState): MusicSheetState = s.copy(popover = MusicPopover.NONE)

  /** Back / Escape: a popover first, then the sheet; null when there is nothing to undo here. */
  fun back(s: MusicSheetState): MusicSheetState? = when {
    s.popover != MusicPopover.NONE -> dismissPopover(s)
    s.open -> close(s)
    else -> null
  }

  /** Something started playing from idle while closed: Up next becomes the sheet's tab again. */
  fun onLoadedChanged(s: MusicSheetState, idle: Boolean): MusicSheetState = when {
    s.open -> s
    idle && s.tab == MusicSheetTab.UP_NEXT -> s.copy(tab = MusicSheetTab.LIBRARY)
    !idle && s.tab == MusicSheetTab.LIBRARY -> s.copy(tab = MusicSheetTab.UP_NEXT)
    else -> s
  }
}

/**
 * Finger-follow maths for dragging the bottom bar or the header strip. Progress units: 0 closed,
 * 1 open; drags past either end are rubber-banded; release flicks by velocity, else by position.
 */
internal object MusicSheetDrag {
  /** Movement before a press becomes a drag (dp). */
  const val SLOP_DP = 10f
  /** Past either end the sheet follows the finger at this share. */
  const val RUBBER = 0.25f
  /** Release faster than this (progress per second) decides by direction. */
  const val FLICK = 1.2f
  /** Velocity handed to the spring is clamped to this (progress per second). */
  const val MAX_VELOCITY = 6f

  /** Spring: damping ratio 0.8, stiffness 170, the mock's "critically-ish damped" spring. */
  const val SPRING_DAMPING = 0.8f
  const val SPRING_STIFFNESS = 170f
  const val SPRING_THRESHOLD = 0.0006f
  /** The focus squeeze settles a little faster than the sheet. */
  const val FOCUS_STIFFNESS = 260f

  /** Progress for a finger that has moved [dyDp] (down positive) since a press at [startProgress]. */
  fun progress(startProgress: Float, dyDp: Float, travelDp: Float): Float {
    if (travelDp <= 0f) return startProgress
    val p = startProgress - dyDp / travelDp
    return when {
      p > 1f -> 1f + (p - 1f) * RUBBER
      p < 0f -> p * RUBBER
      else -> p
    }
  }

  /** A finger velocity in dp/s (down positive) as progress per second (open positive), clamped. */
  fun velocity(dyPerSecondDp: Float, travelDp: Float): Float =
      if (travelDp <= 0f) 0f else (-dyPerSecondDp / travelDp).coerceIn(-MAX_VELOCITY, MAX_VELOCITY)

  /** Whether a release at [progress] with [velocity] (progress/s) ends open. */
  fun settlesOpen(progress: Float, velocity: Float): Boolean =
      if (abs(velocity) > FLICK) velocity > 0f else progress > MusicStageGeometry.INTERACTIVE_SPLIT
}
