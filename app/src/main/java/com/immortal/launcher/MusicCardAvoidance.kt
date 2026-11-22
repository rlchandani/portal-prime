/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */
package com.immortal.launcher

/** The floating card's px rectangle within Home's content area of [areaWidth] × [areaHeight]. */
internal data class MusicFloatRect(
    val x: Float,
    val y: Float,
    val width: Float,
    val height: Float,
    val areaWidth: Float,
    val areaHeight: Float,
) {
  val right get() = x + width
  val bottom get() = y + height
  val centerY get() = y + height / 2f
}

/** Horizontal px insets for Home's bottom-centred toasts. */
internal data class MusicToastInsets(val start: Float, val end: Float) {
  companion object {
    val NONE = MusicToastInsets(0f, 0f)
  }
}

/**
 * How Home's smaller overlays AVOID the draggable Now Playing card (YouTube: nothing may cover the
 * player), wherever it sits now. Pure; see [MusicCoverRules] for what covers and pauses instead.
 */
internal object MusicCardAvoidance {
  /**
   * Home's bottom toasts occupy the bottom [band] px. When the card reaches into that band they
   * centre in the wider free space left or right of it (with [gap]); otherwise they stay centred.
   */
  fun toastInsets(card: MusicFloatRect?, band: Float, gap: Float): MusicToastInsets {
    if (card == null || card.bottom <= card.areaHeight - band) return MusicToastInsets.NONE
    val left = card.x
    val right = card.areaWidth - card.right
    return if (left >= right) MusicToastInsets(0f, (card.areaWidth - card.x + gap).coerceAtMost(card.areaWidth))
    else MusicToastInsets((card.right + gap).coerceAtMost(card.areaWidth), 0f)
  }

  /**
   * A full-width notification toast is shown at the bottom when asked for, unless Home's card shows
   * the player in the lower half; then it moves to the top. Asked for the top while the card sits in
   * the upper half, it moves to the bottom.
   */
  fun notificationAtBottom(requestedBottom: Boolean, shownIn: MusicPlayerHostKind?, card: MusicFloatRect?): Boolean {
    if (shownIn != MusicPlayerHostKind.HOME_CARD || card == null) return requestedBottom
    return card.centerY < card.areaHeight / 2f
  }
}
