/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */
package com.immortal.launcher

import android.content.Context
import kotlin.math.roundToInt

/**
 * The screensaver's screen-aware layout (mock mini-screensaver-20261120): one renderer, three
 * [LayoutClass]es derived from the window size, never the device model.
 *
 * [LayoutClass.LARGE] (the Portal+) is never changed here: every helper either returns null for it
 * or reproduces the shipped arithmetic exactly. The compact classes (the Portal Mini upright and
 * turned) use 1 design unit = 1 dp, the compact overlay grid and the compact photo rules.
 * Pure geometry in pixels; the only Android use is reading the window's configuration in [of].
 */
internal object ScreensaverLayout {
  /** A pixel rectangle (left, top, right, bottom); kept free of android.graphics for JVM tests. */
  data class Box(val left: Int, val top: Int, val right: Int, val bottom: Int) {
    val width: Int get() = right - left
    val height: Int get() = bottom - top
  }

  /** The window's class; previews inside a Portal+ window stay LARGE whatever their own size. */
  fun of(context: Context): LayoutClass {
    val configuration: android.content.res.Configuration? = context.resources?.configuration
    return if (configuration == null) LayoutClass.LARGE else LayoutClass.of(configuration)
  }

  /** Classify a surface measured in pixels (tests and pure callers). */
  fun of(widthPx: Int, heightPx: Int, density: Float): LayoutClass =
      if (density <= 0f) LayoutClass.LARGE
      else LayoutClass.of((widthPx / density).roundToInt(), (heightPx / density).roundToInt())

  // ---- Type ----------------------------------------------------------------------------------

  /** The shipped type factor (0.72..1 by short side, times the Portal+ mock unit). LARGE only. */
  fun shippedFaceScale(widthPx: Int, heightPx: Int, density: Float): Float =
      (minOf(widthPx, heightPx) / density / 1219f).coerceIn(.72f, 1f) *
          HearthMockUnit.boost(widthPx, heightPx, density)

  /**
   * Dp per mock unit for Split glance and Night photo: 1 on a compact window (so the Mini no
   * longer shrinks the approved faces to 0.72), else exactly the shipped factor.
   */
  fun faceScale(cls: LayoutClass, widthPx: Int, heightPx: Int, density: Float): Float =
      if (cls.compact) 1f else shippedFaceScale(widthPx, heightPx, density)

  // ---- Overlay grid --------------------------------------------------------------------------

  /**
   * The compact overlay grid in dp. Upright, the timer, now playing and calendar stack at the top
   * ([topStack]); turned, they keep the Portal+ corners at compact sizes.
   */
  data class Grid(
      val sideDp: Int, val topDp: Int, val bottomDp: Int,
      /** Clock cluster and caption width caps as fractions of the window width. */
      val clockMaxFraction: Float, val captionMaxFraction: Float,
      val topStack: Boolean,
      /** Now playing's widest card (dp); 0 = the full width between the side margins. */
      val nowPlayingMaxDp: Int,
      /** Calendar card width (dp); 0 = the full width between the side margins. */
      val calendarWidthDp: Int,
      val calendarRows: Int,
      /** Gap between cards in the upright top stack. */
      val stackGapDp: Int = 14,
  )

  private val PORTRAIT_GRID = Grid(sideDp = 40, topDp = 40, bottomDp = 56, clockMaxFraction = .56f,
      captionMaxFraction = .38f, topStack = true, nowPlayingMaxDp = 0, calendarWidthDp = 0, calendarRows = 3)
  private val LANDSCAPE_GRID = Grid(sideDp = 48, topDp = 40, bottomDp = 56, clockMaxFraction = .45f,
      captionMaxFraction = .30f, topStack = false, nowPlayingMaxDp = 440, calendarWidthDp = 380, calendarRows = 4)

  /** The compact grid, or null on LARGE (the shipped fixed buckets stay as they are). */
  fun grid(cls: LayoutClass): Grid? = when (cls) {
    LayoutClass.COMPACT_PORTRAIT -> PORTRAIT_GRID
    LayoutClass.COMPACT_LANDSCAPE -> LANDSCAPE_GRID
    LayoutClass.LARGE -> null
  }

  /** Which third of the width an overlay bucket sits in. */
  enum class Column { START, CENTER, END }

  /**
   * An overlay bucket's margins in px (left, top, right, bottom). LARGE is the shipped 40 dp on
   * every side and 80 dp under the bottom row, unchanged. A compact window uses its grid, and
   * [capFraction] narrows the bucket so its content never runs past that share of the width.
   */
  fun bucketMargins(
      cls: LayoutClass, column: Column, bottomRow: Boolean, widthPx: Int, density: Float,
      capFraction: Float? = null,
  ): IntArray {
    fun dp(v: Int) = (v * density).toInt()
    val g = grid(cls) ?: return intArrayOf(dp(40), dp(40), dp(40), if (bottomRow) dp(80) else dp(40))
    val side = dp(g.sideDp)
    val bottom = if (bottomRow) dp(g.bottomDp) else dp(g.topDp)
    var left = side
    var right = side
    if (capFraction != null && widthPx > 0) {
      val spare = (widthPx - side - capFraction * widthPx).toInt()
      when (column) {
        Column.START -> right = maxOf(side, spare)
        Column.END -> left = maxOf(side, spare)
        Column.CENTER -> maxOf(side, ((widthPx - capFraction * widthPx) / 2).toInt()).let { left = it; right = it }
      }
    }
    return intArrayOf(left, dp(g.topDp), right, bottom)
  }

  /** Calendar card width in px for a compact window (uses [HearthLayout.calendarWidthDp]'s cap). */
  fun calendarWidthPx(grid: Grid, widthPx: Int, density: Float): Int {
    val widthDp = widthPx / density
    val dp = if (grid.calendarWidthDp == 0) widthDp - 2 * grid.sideDp
        else minOf(grid.calendarWidthDp.toFloat(), HearthLayout.calendarWidthDp(Float.MAX_VALUE, widthDp))
    return (dp.coerceAtLeast(0f) * density).roundToInt()
  }

  /** Calendar rows that fit the compact card, keeping one line for "+N more". */
  fun calendarVisible(grid: Grid, events: Int): Pair<Int, Int> {
    val shown = minOf(events, grid.calendarRows)
    return shown to (events - shown).coerceAtLeast(0)
  }

  // ---- Photo rules ---------------------------------------------------------------------------

  /** A photo fills the screen only when the crop keeps at least this fraction of it. */
  const val FILL_KEEP = .8
  /** Wider than this is a panorama, swept across at full height. */
  const val PANORAMA_ASPECT = 2.0

  enum class Single { FILL, WHOLE, SWEEP }

  /** Fraction of the photo a cover crop keeps on a [vw] x [vh] viewport. */
  fun keep(pw: Int, ph: Int, vw: Int, vh: Int): Double {
    if (pw <= 0 || ph <= 0 || vw <= 0 || vh <= 0) return 0.0
    val m = PremiumFraming.mismatch(pw, ph, vw, vh)
    return if (m.isFinite() && m > 0) 1.0 / m else 0.0
  }

  /**
   * How a single photo lives on a compact screen: full-bleed when the crop keeps 80 %, swept at
   * full height when it is a panorama, otherwise whole over its own blurred backdrop.
   */
  fun single(pw: Int, ph: Int, vw: Int, vh: Int): Single = when {
    keep(pw, ph, vw, vh) >= FILL_KEEP -> Single.FILL
    ph > 0 && pw.toDouble() / ph > PANORAMA_ASPECT -> Single.SWEEP
    else -> Single.WHOLE
  }

  /** Where a whole photo sits: raised clear of the clock upright, the full screen minus 64/40 turned. */
  fun wholeZone(cls: LayoutClass, widthPx: Int, heightPx: Int, density: Float): Box? = when (cls) {
    LayoutClass.COMPACT_PORTRAIT -> inset(widthPx, heightPx, density, 32, 40, 32, 290)
    LayoutClass.COMPACT_LANDSCAPE -> inset(widthPx, heightPx, density, 64, 40, 64, 40)
    LayoutClass.LARGE -> null
  }

  /** Upright: two landscapes stacked, each card the full width (a 3:2 photo keeps 83 %). */
  fun stackPair(widthPx: Int, heightPx: Int, density: Float, marginDp: Int = 24, gapDp: Int = 16): Pair<Box, Box> {
    val m = (marginDp * density).roundToInt()
    val g = (gapDp * density).roundToInt()
    val h = ((heightPx - 2 * m - g) / 2).coerceAtLeast(1)
    return Box(m, m, widthPx - m, m + h) to Box(m, m + h + g, widthPx - m, m + 2 * h + g)
  }

  /** Is this photo a landscape that can share a stacked upright beat? */
  fun stackable(pw: Int, ph: Int): Boolean {
    if (pw <= 0 || ph <= 0) return false
    val a = pw.toDouble() / ph
    return a >= 1.2 && a <= PANORAMA_ASPECT
  }

  // ---- Classic -------------------------------------------------------------------------------

  /**
   * Compact Classic's motion for a FIT_CENTER photo view scaled about its centre: a pose is the
   * view scale and translation. Start and end are the beat's two ends.
   */
  data class ClassicPan(
      val scale0: Float, val scale1: Float, val x0: Float, val x1: Float, val y0: Float, val y1: Float,
      /** True for a long-axis pan (eased), false for the gentle cover zoom. */
      val pan: Boolean,
  )

  /** The cover zoom's growth over a beat for a photo that already matches the screen (mock 1.09). */
  const val CLASSIC_ZOOM = 1.09f
  /** The zoom's sideways drift as a fraction of the view width (mock 2 %). */
  const val CLASSIC_DRIFT = .02f

  /**
   * Compact Classic (mock): a photo whose cover crop keeps 80 % covers the screen and zooms
   * gently; anything else fills the screen's short side and pans along its long axis over the beat,
   * so the whole photo is seen (a landscape upright, a portrait turned). [beat] alternates the
   * direction. Null for an empty photo or view (the caller leaves the view at rest).
   */
  fun classicPan(pw: Int, ph: Int, vw: Int, vh: Int, beat: Int): ClassicPan? {
    if (pw <= 0 || ph <= 0 || vw <= 0 || vh <= 0) return null
    val contain = minOf(vw.toFloat() / pw, vh.toFloat() / ph)
    val cw = pw * contain
    val ch = ph * contain
    val cover = maxOf(vw / cw, vh / ch)
    val forward = beat % 2 == 0
    if (keep(pw, ph, vw, vh) >= FILL_KEEP) {
      val drift = (if (forward) -CLASSIC_DRIFT else CLASSIC_DRIFT) * vw
      // Every third beat zooms out instead, as the shipped Ken Burns alternates.
      return if (beat % 3 == 2) ClassicPan(cover * CLASSIC_ZOOM, cover, drift, 0f, 0f, 0f, false)
      else ClassicPan(cover, cover * CLASSIC_ZOOM, 0f, drift, 0f, 0f, false)
    }
    val ox = (cw * cover - vw).coerceAtLeast(0f) / 2f
    val oy = (ch * cover - vh).coerceAtLeast(0f) / 2f
    val sign = if (forward) 1f else -1f
    return ClassicPan(cover, cover, sign * ox, -sign * ox, sign * oy, -sign * oy, true)
  }

  // ---- Night photo ---------------------------------------------------------------------------

  /**
   * Night photo keeps the day's whole-photo rule (all Portals): a photo that mismatches the panel
   * by more than [PremiumFraming.MISMATCH] is shown whole over its blurred backdrop instead of
   * losing more than half of itself to the cover crop. Matching photos still cover, as approved.
   */
  fun nightWhole(pw: Int, ph: Int, vw: Int, vh: Int): Boolean =
      PremiumFraming.mismatch(pw, ph, vw, vh) > PremiumFraming.MISMATCH

  /** The whole Night photo's zone: above the amber clock (mock zones per class). */
  fun nightZone(cls: LayoutClass, widthPx: Int, heightPx: Int, density: Float): Box = when (cls) {
    LayoutClass.COMPACT_PORTRAIT -> inset(widthPx, heightPx, density, 28, 60, 28, 240)
    else -> inset(widthPx, heightPx, density, 80, 40, 80, 160)
  }

  /** Night photo's whole photo grows this much over its dwell (mock 1 -> 1.03). */
  const val WHOLE_ZOOM = 1.03f

  /**
   * A whole photo contained in [zone], centred, with an optional slow zoom about its centre. The
   * start is the smaller end, so a backdrop hole cut to it stays covered by the photo throughout.
   */
  fun wholeMotion(pw: Int, ph: Int, zone: Box, motion: Boolean): SlideshowGeometry.Motion {
    if (pw <= 0 || ph <= 0 || zone.width <= 0 || zone.height <= 0) {
      val empty = SlideshowGeometry.Transform(0f, 0f, 0f)
      return SlideshowGeometry.Motion(empty, empty, true)
    }
    val s = minOf(zone.width.toFloat() / pw, zone.height.toFloat() / ph)
    val cx = zone.left + zone.width / 2f
    val cy = zone.top + zone.height / 2f
    fun at(scale: Float) = SlideshowGeometry.Transform(scale, cx - pw * scale / 2f, cy - ph * scale / 2f)
    val start = at(s)
    return SlideshowGeometry.Motion(start, if (motion) at(s * WHOLE_ZOOM) else start, true)
  }

  /** The on-screen rectangle of [t] for a [pw] x [ph] photo (backdrop holes). */
  fun rectOf(t: SlideshowGeometry.Transform, pw: Int, ph: Int): FloatArray =
      floatArrayOf(t.x, t.y, t.x + pw * t.scale, t.y + ph * t.scale)

  /**
   * Night photo clock type: mock sizes (clock, date, gap, bottom margin) drawn at
   * `size * density * factor`, the shipped expression order, so LARGE renders exactly as before.
   */
  data class NightType(
      val clock: Float, val date: Float, val gap: Float, val bottom: Float, val factor: Float, val driftUnit: Float,
  )

  fun nightType(cls: LayoutClass, widthPx: Int, heightPx: Int, density: Float): NightType = when (cls) {
    LayoutClass.COMPACT_PORTRAIT -> NightType(96f, 24f, 8f, 64f, 1f, 1f)
    LayoutClass.COMPACT_LANDSCAPE -> NightType(92f, 24f, 8f, 64f, 1f, 1f)
    LayoutClass.LARGE -> NightType(92f, 22f, 8f, 70f, shippedFaceScale(widthPx, heightPx, density),
        HearthMockUnit.boost(widthPx, heightPx, density))
  }

  // ---- Split glance --------------------------------------------------------------------------

  /** Split glance type in dp (mock "ty"); LARGE keeps the shipped numbers times its scale. */
  data class SplitType(
      val brand: Float, val clock: Float, val date: Float, val ruleTop: Float, val ruleBottom: Float,
      val label: Float, val temperature: Float, val next: Float, val nextTime: Float, val factsGap: Float,
      val cornerDp: Float,
  )

  val SHIPPED_SPLIT = SplitType(13f, 176f, 34f, 30f, 22f, 12f, 30f, 24f, 17f, 36f, 30f)

  fun splitType(cls: LayoutClass): SplitType = when (cls) {
    LayoutClass.COMPACT_PORTRAIT -> SplitType(13f, 176f, 34f, 26f, 20f, 14f, 30f, 24f, 18f, 40f, 28f)
    LayoutClass.COMPACT_LANDSCAPE -> SplitType(13f, 160f, 32f, 26f, 20f, 14f, 30f, 24f, 18f, 36f, 28f)
    LayoutClass.LARGE -> SHIPPED_SPLIT
  }

  /** Compact Split glance card and panel in px, or null on LARGE (the shipped layout). */
  fun splitBoxes(cls: LayoutClass, widthPx: Int, heightPx: Int, density: Float): Pair<Box, Box>? {
    fun px(dp: Float) = (dp * density).roundToInt()
    return when (cls) {
      LayoutClass.COMPACT_PORTRAIT -> {
        val cardBottom = px(40f) + (heightPx * .52f).roundToInt()
        Box(px(40f), px(40f), widthPx - px(40f), cardBottom) to
            Box(px(52f), cardBottom + px(24f), widthPx - px(52f), heightPx - px(44f))
      }
      LayoutClass.COMPACT_LANDSCAPE -> {
        val cardLeft = widthPx - (widthPx * .52f).roundToInt() - px(40f)
        Box(cardLeft, px(40f), widthPx - px(40f), heightPx - px(40f)) to
            Box(px(60f), px(40f), cardLeft - px(44f), heightPx - px(40f))
      }
      LayoutClass.LARGE -> null
    }
  }

  // ---- Digital clock dream ---------------------------------------------------------------------

  /** Upright compact, Large and XL: hours over minutes. Small/Medium and every other class: one line. */
  fun stackedDigits(cls: LayoutClass, size: String): Boolean =
      cls.compactPortrait && (size == DigitalClockConfig.SIZE_LARGE || size == DigitalClockConfig.SIZE_XL)

  /** Stacked numeral height in dp (mock 420 at Large), scaled with the size setting. */
  fun stackedDigitsDp(size: String): Float = 420f * DigitalClockFaceSpec.timeSizeDp(size) / 250f

  // ---- Quiet night clock -----------------------------------------------------------------------

  /** Quiet night clock burn-in drift radius (dp): x and y. Applied on every panel. */
  const val QUIET_DRIFT_X_DP = 12f
  const val QUIET_DRIFT_Y_DP = 9f

  /** The quiet clock's offset in px at [timeMs]: a slow orbit, evaluated only at its minute redraws. */
  fun quietDrift(timeMs: Long, density: Float): Pair<Float, Float> =
      HearthOrbit.x(timeMs, QUIET_DRIFT_X_DP * density, HearthOrbit.NIGHT_PERIOD_X_MS) to
          HearthOrbit.y(timeMs, QUIET_DRIFT_Y_DP * density, HearthOrbit.NIGHT_PERIOD_Y_MS)

  // ---- Rotation --------------------------------------------------------------------------------

  /** Rotation mid-show: dim to [ROTATION_DIM], re-lay, hold [ROTATION_DIM_MS], reveal over [ROTATION_REVEAL_MS]. */
  const val ROTATION_DIM = .2f
  const val ROTATION_DIM_MS = 400L
  const val ROTATION_REVEAL_MS = 500L

  /** A resize re-lays the screensaver only when the window changes orientation. */
  fun rotated(oldW: Int, oldH: Int, w: Int, h: Int): Boolean =
      oldW > 0 && oldH > 0 && w > 0 && h > 0 && (oldW > oldH) != (w > h)

  private fun inset(w: Int, h: Int, density: Float, l: Int, t: Int, r: Int, b: Int): Box {
    fun px(dp: Int) = (dp * density).roundToInt()
    val left = px(l).coerceAtMost(w / 2)
    val top = px(t).coerceAtMost(h / 2)
    return Box(left, top, maxOf(left + 1, w - px(r)), maxOf(top + 1, h - px(b)))
  }
}
