/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */
package com.immortal.launcher

/** A width × height in px. */
internal data class MusicFloatSize(val width: Float, val height: Float)

/** A top-left corner in px, relative to the [MusicFloatArea]'s origin. */
internal data class MusicFloatOffset(val x: Float, val y: Float) {
  operator fun plus(o: MusicFloatOffset) = MusicFloatOffset(x + o.x, y + o.y)
}

/**
 * Where a saved floating player sits, as fractions (0..1) of the free travel in each axis, so it
 * survives a different area size (another tab, another density) and a card ↔ tile size change.
 */
internal data class MusicFloatPosition(val fx: Float, val fy: Float) {
  companion object {
    /** Bottom-right, where the card first appears (above the favourites strip). */
    val DEFAULT = MusicFloatPosition(1f, 1f)
  }
}

/**
 * The px rectangle a floating player may occupy: Home's content area between the header bar and the
 * dock (the caller sizes it), inset by [start]/[top]/[end]/[bottom] so it never touches the header,
 * the favourites strip or the dock.
 */
internal data class MusicFloatArea(
    val width: Float,
    val height: Float,
    val start: Float,
    val top: Float,
    val end: Float,
    val bottom: Float,
) {
  val minX get() = start
  val minY get() = top
  fun maxX(size: MusicFloatSize) = maxOf(minX, width - end - size.width)
  fun maxY(size: MusicFloatSize) = maxOf(minY, height - bottom - size.height)
}

/**
 * Drag, clamp and snap math for Home's draggable Now Playing card and its minimized video tile.
 * Pure (px in, px out).
 *
 * - While dragging the player follows the finger and may overshoot the bounds by at most
 *   [overshoot] (a rubber edge); the caller keeps that inside the area's margins so it still never
 *   reaches the header or the dock.
 * - On release it settles inside the bounds ([settle]): clamped, then pulled onto an edge it is
 *   within [snap] of.
 */
internal object MusicFloatMath {
  fun clamp(o: MusicFloatOffset, size: MusicFloatSize, area: MusicFloatArea): MusicFloatOffset =
      MusicFloatOffset(o.x.coerceIn(area.minX, area.maxX(size)), o.y.coerceIn(area.minY, area.maxY(size)))

  /** The next drag position: [o] moved by [delta], allowed at most [overshoot] past the bounds. */
  fun drag(
      o: MusicFloatOffset,
      delta: MusicFloatOffset,
      size: MusicFloatSize,
      area: MusicFloatArea,
      overshoot: Float,
  ): MusicFloatOffset {
    val next = o + delta
    val slack = overshoot.coerceAtLeast(0f)
    return MusicFloatOffset(
        next.x.coerceIn(area.minX - slack, area.maxX(size) + slack),
        next.y.coerceIn(area.minY - slack, area.maxY(size) + slack))
  }

  /** Where the player rests after a release at [o]: inside the bounds, edge-snapped within [snap]. */
  fun settle(o: MusicFloatOffset, size: MusicFloatSize, area: MusicFloatArea, snap: Float): MusicFloatOffset {
    val c = clamp(o, size, area)
    return MusicFloatOffset(snapAxis(c.x, area.minX, area.maxX(size), snap),
        snapAxis(c.y, area.minY, area.maxY(size), snap))
  }

  private fun snapAxis(v: Float, min: Float, max: Float, snap: Float): Float = when {
    v - min <= snap -> min
    max - v <= snap -> max
    else -> v
  }

  fun toPosition(o: MusicFloatOffset, size: MusicFloatSize, area: MusicFloatArea): MusicFloatPosition {
    val c = clamp(o, size, area)
    return MusicFloatPosition(fraction(c.x, area.minX, area.maxX(size)), fraction(c.y, area.minY, area.maxY(size)))
  }

  fun toOffset(p: MusicFloatPosition, size: MusicFloatSize, area: MusicFloatArea): MusicFloatOffset =
      MusicFloatOffset(lerp(area.minX, area.maxX(size), p.fx), lerp(area.minY, area.maxY(size), p.fy))

  private fun fraction(v: Float, min: Float, max: Float): Float =
      if (max <= min) 0f else ((v - min) / (max - min)).coerceIn(0f, 1f)

  private fun lerp(min: Float, max: Float, f: Float): Float = min + (max - min) * f.coerceIn(0f, 1f)
}
