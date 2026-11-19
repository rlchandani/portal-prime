/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */
package com.immortal.launcher

/**
 * Pure sizes (dp / sp as plain numbers) for the Calls app and the call screens, by [LayoutClass].
 * [LayoutClass.LARGE] returns exactly the Portal+ design values the screens always used.
 *
 * The Mini has no density scale on the call screens (1 dp = 1 px): upright the window is
 * 800×1220, turned 1280×740, so the compact values are chosen to fit those whole.
 */
internal object CallsCompactLayout {
  /** A box in dp. */
  data class Box(val widthDp: Int, val heightDp: Int)

  /**
   * The self view. LARGE: 420×280 (3:2, the landscape camera cropped a little). Upright the camera
   * delivers portrait frames (Camera2Capturer tags each 1280×720 frame with the display rotation,
   * so it renders 720×1280), so the frame is 9:16 and nothing is letterboxed or squashed; the
   * renderer centre-crops to whatever shape it is given anyway.
   */
  fun selfView(layout: LayoutClass): Box = when (layout) {
    LayoutClass.LARGE -> Box(420, 280)
    LayoutClass.COMPACT_LANDSCAPE -> Box(300, 200)
    LayoutClass.COMPACT_PORTRAIT -> Box(216, 384)
  }

  /** Distance of the self view, live bar and title from the screen edges. */
  fun edge(layout: LayoutClass): Int = if (layout.compact) 24 else 40

  /** The in-call control strip (`.ctl`): four round controls and End. */
  data class Controls(val circle: Int, val column: Int, val icon: Int, val endWidth: Int, val endHeight: Int, val endColumn: Int,
                      val endIcon: Int, val padding: Int, val label: Float, val bottom: Int) {
    /** The strip's whole width. */
    val width: Int get() = CONTROLS * column + endColumn + 2 * padding
    /** Its height (top/bottom padding, circle, label gap and a label line). */
    val height: Int get() = TOP_PAD + circle + LABEL_GAP + LABEL_LINE + BOTTOM_PAD
  }

  fun controls(layout: LayoutClass): Controls =
      if (layout.compact) Controls(circle = 80, column = 112, icon = 36, endWidth = 124, endHeight = 80, endColumn = 136, endIcon = 40,
          padding = 20, label = 18f, bottom = 24)
      else Controls(circle = 96, column = 134, icon = 42, endWidth = 150, endHeight = 96, endColumn = 170, endIcon = 46,
          padding = 24, label = 19.5f, bottom = 36)

  /** Ring / ended screens: avatar, title sizes, the big answer/decline buttons and their side toggles. */
  data class Ring(val outgoingAvatar: Int, val incomingAvatar: Int, val outgoingTitle: Float, val incomingTitle: Float,
                  val endedTitle: Float, val sub: Float, val big: Int, val bigIcon: Int, val toggle: Int, val toggleIcon: Int,
                  val gap: Int, val buttonsGap: Int)

  fun ring(layout: LayoutClass): Ring = when (layout) {
    LayoutClass.LARGE -> Ring(outgoingAvatar = 260, incomingAvatar = 280, outgoingTitle = 72f, incomingTitle = 84f, endedTitle = 60f,
        sub = 30f, big = 176, bigIcon = 70, toggle = 110, toggleIcon = 46, gap = 34, buttonsGap = 120)
    LayoutClass.COMPACT_PORTRAIT -> Ring(outgoingAvatar = 220, incomingAvatar = 240, outgoingTitle = 56f, incomingTitle = 64f,
        endedTitle = 50f, sub = 26f, big = 156, bigIcon = 62, toggle = 100, toggleIcon = 42, gap = 28, buttonsGap = 88)
    LayoutClass.COMPACT_LANDSCAPE -> Ring(outgoingAvatar = 150, incomingAvatar = 164, outgoingTitle = 50f, incomingTitle = 56f,
        endedTitle = 46f, sub = 26f, big = 136, bigIcon = 56, toggle = 92, toggleIcon = 40, gap = 20, buttonsGap = 72)
  }

  /** The other room's avatar while its picture hasn't arrived. */
  fun remoteAvatar(layout: LayoutClass): Int = when (layout) {
    LayoutClass.LARGE -> 260
    LayoutClass.COMPACT_PORTRAIT -> 240
    LayoutClass.COMPACT_LANDSCAPE -> 180
  }

  /** How far below the centre the connecting / reconnecting chip sits (its top padding). */
  fun connectionChipOffset(layout: LayoutClass): Int = if (layout.compactLandscape) 280 else 360

  /** Rooms grid columns in the Calls app. */
  fun roomColumns(layout: LayoutClass): Int = if (layout.compactPortrait) 1 else 2

  /** Horizontal page padding in the Calls app. */
  fun pagePadding(layout: LayoutClass): Int = if (layout.compact) 32 else 56

  private const val CONTROLS = 4
  private const val TOP_PAD = 20
  private const val BOTTOM_PAD = 16
  private const val LABEL_GAP = 8
  private const val LABEL_LINE = 26
}
