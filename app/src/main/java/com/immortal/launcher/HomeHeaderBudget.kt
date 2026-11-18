/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */
package com.immortal.launcher

/** Which optional pieces Home's header has right now (the buttons are always there). */
internal data class HomeHeaderItems(
    /** The compact music chip (a song is current or saved). */
    val musicChip: Boolean,
    /** The legacy 300 dp mini player (Settings › show mini player). */
    val miniPlayer: Boolean,
    /** The voice-assistant button (an assistant app is installed). */
    val mic: Boolean,
    /** Customize / Done (Home tab only). */
    val customize: Boolean,
)

/** What the header shows at a given width. The round buttons are never dropped or squished. */
internal data class HomeHeaderPlan(
    val wordmark: Boolean,
    val room: Boolean,
    val connectedLabel: Boolean,
    val miniPlayer: Boolean,
    val musicChip: Boolean,
)

/**
 * Home header width budget (dp), so the round header buttons (screensaver, phone, sleep, theme,
 * voice, music, search, settings) always get their full 68 dp. Optional pieces drop, in this order,
 * until everything fits: the mini player, the "Connected" text (the Wi-Fi glyph stays), the room
 * name, the wordmark, and last the music chip. The room name is also an ellipsizing weight in the
 * row, so a long name can never push a button. Pure.
 */
internal object HomeHeaderBudget {
  const val EDGE = 24
  const val GAP = 16
  const val BUTTON = 68
  /** Wordmark + its 18 dp gaps + the 1 dp divider with 12 dp padding each side. */
  const val WORDMARK_GROUP = 210 + 18 + 25
  /** The room name's reserved width (it ellipsizes beyond this when space is short). */
  const val ROOM = 18 + 140
  const val WIFI = 22
  /** "Connecting" at 20 sp, the longest status text. */
  const val CONNECTED_LABEL = 120
  /** "Customize" at 22 sp with 20 dp padding each side. */
  const val CUSTOMIZE = 160
  const val MINI_PLAYER = 300
  /** Screensaver, phone, sleep, theme, music, search, settings (voice is [HomeHeaderItems.mic]). */
  private const val FIXED_BUTTONS = 7

  /** Header widths the layout is checked against (the Portal 10" and a 1280×800 dp tablet). */
  val CHECKED_WIDTHS = listOf(1920, 1280)

  fun plan(widthDp: Int, items: HomeHeaderItems): HomeHeaderPlan {
    val full = HomeHeaderPlan(wordmark = true, room = true, connectedLabel = true,
        miniPlayer = items.miniPlayer, musicChip = items.musicChip)
    val drops = listOf<(HomeHeaderPlan) -> HomeHeaderPlan>(
        { it.copy(miniPlayer = false) },
        { it.copy(connectedLabel = false) },
        { it.copy(room = false) },
        { it.copy(wordmark = false) },
        { it.copy(musicChip = false) },
    )
    var plan = full
    for (drop in drops) {
      if (width(plan, items) <= widthDp) return plan
      plan = drop(plan)
    }
    return plan
  }

  /**
   * The dp the header needs with [plan]: every child, the gaps between them and both edges. The
   * leading group (wordmark, room, screensaver, chip) takes the free width itself, so no spacer.
   */
  fun width(plan: HomeHeaderPlan, items: HomeHeaderItems): Int {
    val pieces = buildList {
      val left = (if (plan.wordmark) WORDMARK_GROUP else 0) + (if (plan.room) ROOM else 0)
      if (left > 0) add(left)
      repeat(FIXED_BUTTONS + if (items.mic) 1 else 0) { add(BUTTON) }
      if (plan.musicChip) add(MusicV3HomeChipDims.WIDTH)
      add(WIFI)
      if (plan.connectedLabel) add(CONNECTED_LABEL)
      if (items.customize) add(CUSTOMIZE)
      if (plan.miniPlayer) add(MINI_PLAYER)
    }
    return 2 * EDGE + pieces.sum() + GAP * (pieces.size - 1)
  }
}
