/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */
package com.immortal.launcher

/**
 * Pure rules of the redesigned digital clock screensaver (mock "Digital clock · After"): which
 * face draws, how big, its backdrop colours, the 7-segment table, flip-card geometry and the
 * analog hand angles. Rendering lives in [DigitalDreamFaceView].
 */
internal object DigitalClockFaceSpec {
  enum class Face { CLASSIC, BOLD, NEON, LED, FLIP, ANALOG }

  fun face(style: String): Face = when (style) {
    DigitalClockConfig.STYLE_BOLD -> Face.BOLD
    DigitalClockConfig.STYLE_NEON -> Face.NEON
    DigitalClockConfig.STYLE_SEGMENT -> Face.LED
    DigitalClockConfig.STYLE_FLIP -> Face.FLIP
    DigitalClockConfig.STYLE_ANALOG -> Face.ANALOG
    else -> Face.CLASSIC
  }

  /** Mock size at "Large" is 250 dp of a 1280 dp-tall panel; other sizes scale from it. */
  fun timeSizeDp(size: String): Float = when (size) {
    DigitalClockConfig.SIZE_SMALL -> 150f
    DigitalClockConfig.SIZE_MEDIUM -> 200f
    DigitalClockConfig.SIZE_XL -> 320f
    else -> 250f
  }

  /** Flip cards are this fraction of the panel height at each size (mock: 0.40 at Large). */
  fun flipHeightFraction(size: String): Float = when (size) {
    DigitalClockConfig.SIZE_SMALL -> .26f
    DigitalClockConfig.SIZE_MEDIUM -> .33f
    DigitalClockConfig.SIZE_XL -> .48f
    else -> .40f
  }

  /** Shrink [wanted] so text measuring [measured] at that size fits [room]; never enlarge. */
  fun fit(wanted: Float, measured: Float, room: Float): Float =
      if (measured <= 0f || room <= 0f) wanted else minOf(wanted, wanted * room / measured)

  /** Backdrop as (centre colour, edge colour, radial). Settings Black = the face's own dark room. */
  data class Backdrop(val inner: Int, val outer: Int, val radial: Boolean)

  fun backdrop(face: Face, background: String): Backdrop = when (background) {
    DigitalClockConfig.BG_RED -> Backdrop(0xFF1A0500.toInt(), 0xFF1A0500.toInt(), false)
    DigitalClockConfig.BG_GRADIENT -> Backdrop(0xFF2A1D14.toInt(), 0xFF050403.toInt(), false)
    else -> when (face) {
      Face.BOLD -> Backdrop(0xFF1C1612.toInt(), 0xFF0B0908.toInt(), false)
      Face.NEON -> Backdrop(0xFF1A0D07.toInt(), 0xFF030202.toInt(), true)
      Face.LED -> Backdrop(0xFF050303.toInt(), 0xFF050303.toInt(), false)
      Face.ANALOG -> Backdrop(0xFF1B1612.toInt(), 0xFF060504.toInt(), true)
      else -> Backdrop(0xFF17130F.toInt(), 0xFF050403.toInt(), true)
    }
  }

  /** Lit segments (a..g) per character; a space lights none (ghost segments only). */
  fun segments(c: Char): String = when (c) {
    '0' -> "abcdef"; '1' -> "bc"; '2' -> "abged"; '3' -> "abgcd"; '4' -> "fgbc"
    '5' -> "afgcd"; '6' -> "afgedc"; '7' -> "abc"; '8' -> "abcdefg"; '9' -> "abcfgd"
    else -> ""
  }

  /** Segment outlines on a 60x100 cell (same paths as the mock's SVG). */
  val SEGMENT_PATHS = mapOf(
      'a' to "M14 6h32l-5 6H19z", 'b' to "M48 8l-5 6v28l5 4z", 'c' to "M48 54l-5 4v28l5 6z",
      'd' to "M14 94h32l-5-6H19z", 'e' to "M12 54l5 4v28l-5 6z", 'f' to "M12 8l5 6v28l-5 4z",
      'g' to "M16 50l4-4h20l4 4-4 4H20z")

  /** Flip layout for a card height [h]: card width, gap, corner radius, colon slot, total width. */
  data class Flip(val cardH: Float, val cardW: Float, val gap: Float, val radius: Float, val colon: Float) {
    val total: Float get() = 4 * cardW + 3 * gap + colon
  }

  /** Mock proportions (W = .74 H, gap .1 W, radius .1 W, colon .3 W), shrunk to fit [maxWidth]. */
  fun flip(wantedHeight: Float, maxWidth: Float): Flip {
    fun at(h: Float) = Flip(h, h * .74f, h * .074f, h * .074f, h * .222f)
    val wanted = at(wantedHeight)
    return if (maxWidth <= 0f || wanted.total <= maxWidth) wanted else at(wantedHeight * maxWidth / wanted.total)
  }

  /** Flip progress for card [index] [elapsedMs] after the minute change (620 ms, 60 ms stagger). */
  fun flipProgress(index: Int, elapsedMs: Long): Float =
      ((elapsedMs - index * FLIP_STAGGER_MS).toFloat() / FLIP_MS).coerceIn(0f, 1f)

  const val FLIP_MS = 620L
  const val FLIP_STAGGER_MS = 60L

  /** Hour, minute, second hand angles in degrees clockwise from 12; the second hand sweeps. */
  fun handAngles(hour: Int, minute: Int, second: Int, millis: Int): FloatArray {
    val s = second + millis / 1000f
    val m = minute + s / 60f
    val h = (hour % 12) + m / 60f
    return floatArrayOf(h * 30f, m * 6f, s * 6f)
  }

  /** The analog sweep redraws at this cadence: smooth (0.3 deg steps) without 60 fps cost. */
  const val SWEEP_FRAME_MS = 50L
}
