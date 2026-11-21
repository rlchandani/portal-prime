/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */
package com.immortal.launcher

/**
 * Compact geometry for the photo-frame clock faces (Flip, Big, Bold) and the quiet night clock
 * (mock mini-screensaver-20261120, "Photo-frame clock faces" and "Night").
 *
 * Every helper returns null for [LayoutClass.LARGE]: the Portal+ keeps its shipped arithmetic in
 * the views themselves, untouched. Pure pixel geometry, JVM-testable.
 */
internal object ScreensaverFaceLayout {

  // ---- Flip photo face -------------------------------------------------------------------------

  /** Card size per Small / Medium / Large on a compact window (mock: 0.8 / 0.9 / 1.0). */
  private val FLIP_SIZE_FACTORS = listOf(.8f, .9f, 1f)

  /**
   * The compact card factor for a flip [sizeScale]: the catalog's Small/Medium/Large values map to
   * 0.8/0.9/1.0; anything else is placed by the nearest catalog step at or above it (Large past it).
   */
  fun flipSizeFactor(sizeScale: Int): Float {
    val sizes = FaceCatalog.entryFor("flip").sizes
    val index = sizes.indexOfFirst { sizeScale <= it }.let { if (it < 0) sizes.size - 1 else it }
    return FLIP_SIZE_FACTORS[index.coerceIn(0, FLIP_SIZE_FACTORS.size - 1)]
  }

  /**
   * Compact split-flap cards, in px within a [viewW] x [viewH] view. [rows] = 2 is hours over
   * minutes (two cards per row, [rowGap] between rows, no colon); [rows] = 1 is the shipped single
   * row with [colonW] between the hour and minute pairs. [lefts]/[tops] are the four cards' origins.
   */
  data class FlipCards(
      val rows: Int, val cardW: Float, val cardH: Float, val gap: Float, val colonW: Float,
      val rowGap: Float, val digit: Float, val lefts: FloatArray, val tops: FloatArray,
  ) {
    /** The colon slot's left edge (single row only): straight after the second card, as in the mock. */
    val colonLeft: Float get() = lefts[1] + cardW
  }

  /**
   * Upright: cw = (W - 80 - 28) / 2, ch = 1.22 cw, gap 28, row gap 36, raised 40 dp. Turned:
   * cw = .17 W, ch = min(.55 H, 1.35 cw), gap .06 cw, colon .3 cw, raised 30 dp. The digit is
   * .7 ch. [screenW]/[screenH] size the cards (the visible screen); the view may be larger (its
   * burn-in margin) and the group is centred in it. Card size follows [flipSizeFactor].
   */
  fun flip(
      cls: LayoutClass, viewW: Float, viewH: Float, screenW: Float, screenH: Float, density: Float,
      sizeScale: Int,
  ): FlipCards? {
    if (!cls.compact || viewW <= 0f || viewH <= 0f || density <= 0f) return null
    val w = if (screenW > 0f) minOf(screenW, viewW) else viewW
    val h = if (screenH > 0f) minOf(screenH, viewH) else viewH
    val s = flipSizeFactor(sizeScale)
    return if (cls.compactPortrait) {
      val gap = 28f * density
      val rowGap = 36f * density
      val cw = ((w - 80f * density - gap) / 2f).coerceAtLeast(0f) * s
      val ch = cw * 1.22f
      val x0 = (viewW - (2f * cw + gap)) / 2f
      val y0 = (viewH - (2f * ch + rowGap)) / 2f - 40f * density
      FlipCards(2, cw, ch, gap, 0f, rowGap, .7f * ch,
          floatArrayOf(x0, x0 + cw + gap, x0, x0 + cw + gap),
          floatArrayOf(y0, y0, y0 + ch + rowGap, y0 + ch + rowGap))
    } else {
      val baseW = .17f * w
      val cw = baseW * s
      val ch = minOf(.55f * h, 1.35f * baseW) * s
      val gap = .06f * cw
      val colonW = .3f * cw
      val x0 = (viewW - (4f * cw + 2f * gap + colonW)) / 2f
      val y = (viewH - ch) / 2f - 30f * density
      val x2 = x0 + 2f * cw + gap + colonW
      FlipCards(1, cw, ch, gap, colonW, 0f, .7f * ch,
          floatArrayOf(x0, x0 + cw + gap, x2, x2 + cw + gap), floatArrayOf(y, y, y, y))
    }
  }

  // ---- Big / Bold centre faces -----------------------------------------------------------------

  enum class Centre { BIG, BOLD }

  /** The Bold face's condensed font (FaceCatalog "bold"). */
  const val BOLD_FONT = "Bebas Neue"

  /**
   * Which centre face a clock spec is: a large (scale above 100) digital clock in the middle of
   * the screen without seconds. Bold is the condensed Bebas face; anything else is Big. Null for
   * every other face (Classic, Minimal, custom corner faces), which keep their shipped geometry.
   */
  fun centreKind(spec: ClockSpec): Centre? = when {
    spec.mode != ClockMode.DIGITAL || spec.position != GridPosition.MIDDLE_CENTER -> null
    spec.sizeScale <= 100 || spec.showSeconds -> null
    spec.font == BOLD_FONT -> Centre.BOLD
    else -> Centre.BIG
  }

  /**
   * Compact centre type in dp: [stacked] = hours over minutes at [lineHeight] em per line; [dateDp]
   * the one-line date (0 = none); the whole cluster at most [clusterMaxFraction] of the height.
   */
  data class CentreType(
      val stacked: Boolean, val clockDp: Float, val lineHeight: Float, val dateDp: Float,
      val clusterMaxFraction: Float,
  )

  /** Line height of the turned single line and the Bold date (mock 1.05 / 1.1). */
  const val DATE_LINE_HEIGHT = 1.1f

  /**
   * Big: 340 dp stacked upright, 260 dp turned. Bold: 470 dp stacked upright with a 58 dp date,
   * 300 dp + 64 dp turned with the cluster at most .7 H. Those are the Medium size; Small and
   * Large scale proportionally by the catalog's size ratio (as the shipped face already does).
   */
  fun centreType(cls: LayoutClass, kind: Centre?, sizeScale: Int): CentreType? {
    if (!cls.compact || kind == null) return null
    val medium = FaceCatalog.entryFor(if (kind == Centre.BOLD) "bold" else "big").sizes.getOrNull(1)
        ?: return null
    val r = sizeScale.coerceAtLeast(1).toFloat() / medium
    val portrait = cls.compactPortrait
    return when (kind) {
      Centre.BIG -> if (portrait) CentreType(true, 340f * r, .86f, 0f, 1f)
          else CentreType(false, 260f * r, 1f, 0f, 1f)
      Centre.BOLD -> if (portrait) CentreType(true, 470f * r, .86f, 58f * r, 1f)
          else CentreType(false, 300f * r, 1f, 64f * r, .7f)
    }
  }

  /**
   * The tallest the clock may be (px): the bucket's own limit, and on a cluster-capped class the
   * cap of the screen height minus the date line beneath it.
   */
  fun clockHeightLimit(type: CentreType, bucketLimit: Int, screenH: Int, density: Float): Int {
    if (type.clusterMaxFraction >= 1f || screenH <= 0) return bucketLimit
    val cap = type.clusterMaxFraction * screenH - type.dateDp * density * DATE_LINE_HEIGHT
    return minOf(bucketLimit, cap.toInt().coerceAtLeast(0))
  }

  /**
   * Hours and minutes of a formatted time ([raw] from the clock's pattern, always the real
   * separator) for the stacked layout: the meridiem is dropped (the stack has no room for it).
   */
  fun stackedLines(raw: String, separator: String, meridiem: Boolean): Pair<String, String> {
    val core = if (meridiem) raw.substringBefore(' ') else raw
    if (core.length < 2) return core to ""
    val minutes = core.takeLast(2)
    val hours = core.dropLast(2).let { if (separator.isNotEmpty() && it.endsWith(separator)) it.dropLast(separator.length) else it }
    return hours to minutes
  }

  /** Shrink-only fit: the scale that keeps [contentW] x [contentH] inside [maxW] x [maxH]. */
  fun fitScale(contentW: Float, contentH: Float, maxW: Float, maxH: Float): Float = minOf(
      1f,
      if (contentW > 0f) maxW / contentW else 1f,
      if (contentH > 0f) maxH / contentH else 1f,
  ).coerceAtLeast(0f)

  /** One-line auto-shrink: [desired] px, or smaller so text measured at [desired] fits [available]. */
  fun fitTextSize(desired: Float, measuredAtDesired: Float, available: Float): Float =
      if (measuredAtDesired <= available || measuredAtDesired <= 0f) desired
      else desired * (available.coerceAtLeast(0f) / measuredAtDesired)

  // ---- Quiet night clock -----------------------------------------------------------------------

  /** The quiet clock's compact type in px: clock, date and the gap under the clock. */
  data class QuietType(val clock: Float, val date: Float, val gap: Float)

  /**
   * Clock = min(.28 H, .82 W / 2.4), capped at 250 dp upright; date 34 dp upright / 32 dp turned,
   * 24 dp under the clock. Null on LARGE (the shipped .28 H / .041 H / .043 H type).
   */
  fun quietType(cls: LayoutClass, widthPx: Float, heightPx: Float, density: Float): QuietType? {
    if (!cls.compact) return null
    val fit = minOf(.28f * heightPx, .82f * widthPx / 2.4f)
    return if (cls.compactPortrait) QuietType(minOf(fit, 250f * density), 34f * density, 24f * density)
    else QuietType(fit, 32f * density, 24f * density)
  }
}
