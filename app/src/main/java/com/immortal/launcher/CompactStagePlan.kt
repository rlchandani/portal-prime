/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */
package com.immortal.launcher

import kotlin.math.roundToInt

/**
 * The native photo styles on a compact window (the Portal Mini, mock mini-screensaver-20261120,
 * phase 2). Pure geometry in pixels: [SlideshowStage] turns a [Plan] into views.
 *
 * [LayoutClass.LARGE] never gets a plan ([plan] returns null), so the Portal+ keeps every shipped
 * style exactly. Compact rules:
 *  - Smart frame: upright, two landscapes stack (cards keep 83 % of a 3:2); a photo whose crop keeps
 *    80 % fills the screen; a panorama sweeps at full height; anything else is whole over its own
 *    blurred backdrop, raised clear of the clock. Turned, the same single rule.
 *  - Pair: upright it stacks landscapes edge to edge; turned, two portraits in rounded cover tiles
 *    over the backdrop. A lone photo uses the Smart frame single rule.
 *  - Whole photo: never crops; a photo more than [WHOLE_MISMATCH] off the panel sits over its
 *    backdrop in both orientations.
 *  - Gallery print / spread: upright, a fixed print zone above the clock (spread is a column of
 *    two landscape prints). Turned, unchanged (null: the shipped measured placement).
 */
internal object CompactStagePlan {
  enum class Style { SMART, PAIR, WHOLE, PRINT, SPREAD }

  /** What sits behind the tiles: black, the doodle matte, the print matte, or photo 0's backdrop. */
  enum class Ground { BLACK, MATTE, PRINT, BACKDROP }

  /** Which partner the loader looks for (none, a portrait, or a stackable landscape). */
  enum class Pairing { NONE, PORTRAIT, LANDSCAPE }

  /**
   * One photo in a clipped holder at [box]; [motion] maps bitmap pixels into the holder.
   * [elevationDp] casts the card's soft shadow, [border] draws the thin print edge.
   */
  data class Tile(
      val photo: Int, val box: ScreensaverLayout.Box, val motion: SlideshowGeometry.Motion,
      val cornerDp: Float = 0f, val elevationDp: Float = 0f, val border: Boolean = false,
  )

  data class Plan(val ground: Ground, val tiles: List<Tile>, val backdropBrightness: Float = SINGLE_BACKDROP)

  /** Whole photo's backdrop threshold (mock 1.15: tighter than the shipped 1.4). */
  const val WHOLE_MISMATCH = 1.15
  const val SINGLE_BACKDROP = .55f
  const val PAIR_BACKDROP = .45f
  private const val CARD_ELEVATION_DP = 12f
  private const val PRINT_ELEVATION_DP = 8f

  fun style(mode: String): Style? = when (mode) {
    ScreensaverConfig.MODE_SMART -> Style.SMART
    ScreensaverConfig.MODE_PORTRAITS -> Style.PAIR
    ScreensaverConfig.MODE_WHOLE -> Style.WHOLE
    ScreensaverConfig.MODE_GALLERY_PRINT -> Style.PRINT
    ScreensaverConfig.MODE_GALLERY_SPREAD -> Style.SPREAD
    else -> null
  }

  /**
   * The partner a mode asks the loader for. LARGE keeps the shipped rule (Portrait pair and Gallery
   * spread pair portraits); upright the pair styles and Smart frame stack landscapes; turned the
   * pair styles keep portrait pairs. A single-photo feature (Night photo, Split glance) never pairs.
   */
  fun pairing(mode: String, cls: LayoutClass, singleFeature: Boolean = false): Pairing {
    val pairStyle = mode == ScreensaverConfig.MODE_PORTRAITS || mode == ScreensaverConfig.MODE_GALLERY_SPREAD
    return when (cls) {
      LayoutClass.LARGE -> if (pairStyle) Pairing.PORTRAIT else Pairing.NONE
      LayoutClass.COMPACT_LANDSCAPE -> if (pairStyle && !singleFeature) Pairing.PORTRAIT else Pairing.NONE
      LayoutClass.COMPACT_PORTRAIT ->
        if (!singleFeature && (pairStyle || mode == ScreensaverConfig.MODE_SMART)) Pairing.LANDSCAPE else Pairing.NONE
    }
  }

  /** A portrait that can share a turned pair (the shipped portrait test). */
  fun portrait(pw: Int, ph: Int): Boolean = pw > 0 && ph > pw

  /** Can these two photos share one beat on [cls]? (Re-checked after a rotation.) */
  fun pairs(style: Style?, cls: LayoutClass, a: Pair<Int, Int>, b: Pair<Int, Int>): Boolean = when {
    style == null || cls == LayoutClass.LARGE -> true
    cls.compactPortrait && (style == Style.SMART || style == Style.PAIR || style == Style.SPREAD) ->
      ScreensaverLayout.stackable(a.first, a.second) && ScreensaverLayout.stackable(b.first, b.second)
    cls.compactLandscape && (style == Style.PAIR || style == Style.SPREAD) ->
      portrait(a.first, a.second) && portrait(b.first, b.second)
    else -> false
  }

  /**
   * The compact scene for [photos] (bitmap sizes) on a [width] x [height] window, or null to keep
   * the shipped presentation (LARGE, or a style that is unchanged on this class). Two photos that
   * no longer pair here (after a rotation) show the first alone.
   */
  fun plan(
      style: Style?, cls: LayoutClass, photos: List<Pair<Int, Int>>, width: Int, height: Int,
      density: Float, motion: Boolean, parity: Boolean, backdrop: Boolean,
      regions: List<SlideshowGeometry.Region> = emptyList(),
      /** Overlays measured on screen (the upright calendar card); whole cards and prints clear them. */
      occupied: List<ScreensaverLayout.Box> = emptyList(),
  ): Plan? {
    if (style == null || !cls.compact || photos.isEmpty() || width <= 0 || height <= 0 || density <= 0f) return null
    if (photos.any { it.first <= 0 || it.second <= 0 }) return null
    if (cls.compactLandscape && (style == Style.PRINT || style == Style.SPREAD)) return null
    val pair = photos.size >= 2 && pairs(style, cls, photos[0], photos[1])
    val clear = { zone: ScreensaverLayout.Box -> clearTop(zone, occupied, height, density) }
    val plan = when (style) {
      Style.SMART -> if (pair) stack(photos, width, height, density, motion, 24, 16, 18f)
          else single(photos[0], cls, width, height, density, motion, parity, regions, clear)
      Style.PAIR -> when {
        pair && cls.compactPortrait -> stack(photos, width, height, density, motion, 0, 12, 0f)
        pair -> sideBySide(photos, width, height, density, motion)
        else -> single(photos[0], cls, width, height, density, motion, parity, regions, clear)
      }
      Style.WHOLE -> whole(photos[0], cls, width, height, density, clear)
      Style.PRINT -> print(listOf(photos[0]), width, height, density, clear)
      Style.SPREAD -> print(if (pair) photos.take(2) else listOf(photos[0]), width, height, density, clear)
    }
    return if (plan.ground == Ground.BACKDROP && !backdrop) plan.copy(ground = Ground.MATTE) else plan
  }

  // ---- Rules ---------------------------------------------------------------------------------

  private fun single(
      photo: Pair<Int, Int>, cls: LayoutClass, w: Int, h: Int, density: Float, motion: Boolean,
      parity: Boolean, regions: List<SlideshowGeometry.Region>,
      clear: (ScreensaverLayout.Box) -> ScreensaverLayout.Box = { it },
  ): Plan {
    val (pw, ph) = photo
    val screen = ScreensaverLayout.Box(0, 0, w, h)
    val rule = ScreensaverLayout.single(pw, ph, w, h)
    // A known face that the 80 % crop would cut keeps the photo whole instead.
    val faceSafe = regions.isEmpty() || !SlideshowGeometry.plan(pw, ph, w, h, regions, fill = true, motion = false).whole
    if (rule == ScreensaverLayout.Single.FILL && faceSafe) {
      val base = if (regions.isEmpty()) null
          else SlideshowGeometry.plan(pw, ph, w, h, regions, fill = true, motion = false).start
      val dx = if (parity) .012f else -.012f
      val m = if (motion) drift(pw, ph, w, h, 1.02f, 1.06f, 0f, dx, 0f, 0f, base)
          else drift(pw, ph, w, h, 1f, 1f, 0f, 0f, 0f, 0f, base)
      return Plan(Ground.BLACK, listOf(Tile(0, screen, m)))
    }
    if (rule == ScreensaverLayout.Single.SWEEP) {
      val s = h.toFloat() / ph
      val travel = w - pw * s
      val a = SlideshowGeometry.Transform(s, 0f, 0f)
      val b = SlideshowGeometry.Transform(s, travel, 0f)
      val m = if (!motion) SlideshowGeometry.Motion(
          SlideshowGeometry.Transform(s, travel / 2f, 0f), SlideshowGeometry.Transform(s, travel / 2f, 0f), false)
          else if (parity) SlideshowGeometry.Motion(b, a, false) else SlideshowGeometry.Motion(a, b, false)
      return Plan(Ground.BLACK, listOf(Tile(0, screen, m)))
    }
    val zone = clear(ScreensaverLayout.wholeZone(cls, w, h, density) ?: screen)
    val card = contain(pw, ph, zone)
    val m = if (motion) drift(pw, ph, card.width, card.height, 1f, ScreensaverLayout.WHOLE_ZOOM, 0f, 0f, 0f, 0f)
        else drift(pw, ph, card.width, card.height, 1f, 1f, 0f, 0f, 0f, 0f)
    return Plan(Ground.BACKDROP, listOf(Tile(0, card, m, cornerDp = 6f, elevationDp = CARD_ELEVATION_DP)))
  }

  /** Two landscapes stacked upright, drifting in opposite directions (mock stackPair). */
  private fun stack(
      photos: List<Pair<Int, Int>>, w: Int, h: Int, density: Float, motion: Boolean,
      marginDp: Int, gapDp: Int, cornerDp: Float,
  ): Plan {
    val (top, bottom) = ScreensaverLayout.stackPair(w, h, density, marginDp, gapDp)
    fun tile(i: Int, box: ScreensaverLayout.Box, s0: Float, s1: Float, x0: Float, x1: Float): Tile {
      val (pw, ph) = photos[i]
      val m = if (motion) drift(pw, ph, box.width, box.height, s0, s1, x0, x1, 0f, 0f)
          else drift(pw, ph, box.width, box.height, 1f, 1f, 0f, 0f, 0f, 0f)
      return Tile(i, box, m, cornerDp = cornerDp, elevationDp = if (marginDp > 0) CARD_ELEVATION_DP else 0f)
    }
    return Plan(Ground.BACKDROP, listOf(tile(0, top, 1.02f, 1.05f, -.01f, .01f),
        tile(1, bottom, 1.05f, 1.02f, .01f, -.01f)), PAIR_BACKDROP)
  }

  /** Two portraits side by side turned, cover-cropped in rounded tiles over the backdrop. */
  private fun sideBySide(photos: List<Pair<Int, Int>>, w: Int, h: Int, density: Float, motion: Boolean): Plan {
    fun px(dp: Int) = (dp * density).roundToInt()
    val m = px(32)
    val gutter = px(20)
    val tw = ((w - 2 * m - gutter) / 2).coerceAtLeast(1)
    val tiles = (0..1).map { i ->
      val left = m + i * (tw + gutter)
      val box = ScreensaverLayout.Box(left, m, left + tw, maxOf(m + 1, h - m))
      val (pw, ph) = photos[i]
      val dy = if (i == 1) .01f else -.01f
      val path = if (motion) drift(pw, ph, box.width, box.height, 1.02f, 1.05f, 0f, 0f, dy, -dy)
          else drift(pw, ph, box.width, box.height, 1f, 1f, 0f, 0f, 0f, 0f)
      Tile(i, box, path, cornerDp = 14f, elevationDp = CARD_ELEVATION_DP)
    }
    return Plan(Ground.BACKDROP, tiles, PAIR_BACKDROP)
  }

  /** Whole photo never crops; a mismatched photo sits over its backdrop in a raised zone. */
  private fun whole(
      photo: Pair<Int, Int>, cls: LayoutClass, w: Int, h: Int, density: Float,
      clear: (ScreensaverLayout.Box) -> ScreensaverLayout.Box = { it },
  ): Plan {
    val (pw, ph) = photo
    val still = { box: ScreensaverLayout.Box -> drift(pw, ph, box.width, box.height, 1f, 1f, 0f, 0f, 0f, 0f) }
    if (PremiumFraming.mismatch(pw, ph, w, h) <= WHOLE_MISMATCH) {
      val box = contain(pw, ph, ScreensaverLayout.Box(0, 0, w, h))
      return Plan(Ground.BLACK, listOf(Tile(0, box, still(box))))
    }
    fun px(dp: Int) = (dp * density).roundToInt()
    val zone = clear(if (cls.compactPortrait) ScreensaverLayout.Box(px(24), px(40), w - px(24), h - px(280))
        else ScreensaverLayout.Box(px(56), px(32), w - px(56), h - px(32)))
    val box = contain(pw, ph, zone)
    return Plan(Ground.BACKDROP, listOf(Tile(0, box, still(box), cornerDp = 6f, elevationDp = CARD_ELEVATION_DP)))
  }

  /** Upright prints sit still in the fixed zone above the clock; a spread is a column of two. */
  private fun print(
      photos: List<Pair<Int, Int>>, w: Int, h: Int, density: Float,
      clear: (ScreensaverLayout.Box) -> ScreensaverLayout.Box = { it },
  ): Plan {
    fun px(dp: Int) = (dp * density).roundToInt()
    val single = photos.size == 1
    val zone = clear(ScreensaverLayout.Box(px(40), px(if (single) 48 else 44), w - px(40), h - px(if (single) 282 else 286)))
    val boxes = if (single) listOf(zone) else {
      val gap = px(24)
      val ch = ((zone.height - gap) / 2).coerceAtLeast(1)
      listOf(ScreensaverLayout.Box(zone.left, zone.top, zone.right, zone.top + ch),
          ScreensaverLayout.Box(zone.left, zone.top + ch + gap, zone.right, zone.top + 2 * ch + gap))
    }
    val tiles = photos.mapIndexed { i, (pw, ph) ->
      val box = contain(pw, ph, boxes[i])
      Tile(i, box, drift(pw, ph, box.width, box.height, 1f, 1f, 0f, 0f, 0f, 0f),
          elevationDp = if (single) PRINT_ELEVATION_DP else 0f, border = true)
    }
    return Plan(Ground.PRINT, tiles)
  }

  // ---- Geometry ------------------------------------------------------------------------------

  /** The gap kept under a cleared overlay. */
  private const val CLEAR_GAP_DP = 16

  /**
   * [zone] with its top moved below any overlay hanging into the top of it (the upright calendar
   * card), keeping at least half the zone's height; bottom overlays (the clock) are already below.
   */
  fun clearTop(zone: ScreensaverLayout.Box, occupied: List<ScreensaverLayout.Box>, h: Int, density: Float): ScreensaverLayout.Box {
    val gap = (CLEAR_GAP_DP * density).roundToInt()
    val reach = occupied.filter { it.top <= zone.top + zone.height / 4 && it.bottom > zone.top && it.bottom < h / 2 &&
        it.right > zone.left && it.left < zone.right }.maxOfOrNull { it.bottom + gap } ?: return zone
    val top = minOf(reach, zone.top + zone.height / 2)
    return if (top <= zone.top) zone else zone.copy(top = top)
  }

  /** The [pw] x [ph] photo contained in [zone], centred. */
  fun contain(pw: Int, ph: Int, zone: ScreensaverLayout.Box): ScreensaverLayout.Box {
    val s = minOf(zone.width.toFloat() / pw, zone.height.toFloat() / ph)
    val cw = (pw * s).roundToInt().coerceIn(1, maxOf(1, zone.width))
    val ch = (ph * s).roundToInt().coerceIn(1, maxOf(1, zone.height))
    val left = zone.left + (zone.width - cw) / 2
    val top = zone.top + (zone.height - ch) / 2
    return ScreensaverLayout.Box(left, top, left + cw, top + ch)
  }

  /**
   * A cover of a [w] x [h] holder that grows from [s0] to [s1] times the cover scale about the
   * holder's centre (or [base]'s face-aware framing), drifting by [dx]/[dy] fractions of the shown
   * photo. Every pose is clamped so the photo always covers the holder.
   */
  fun drift(
      pw: Int, ph: Int, w: Int, h: Int, s0: Float, s1: Float, dx0: Float, dx1: Float, dy0: Float, dy1: Float,
      base: SlideshowGeometry.Transform? = null,
  ): SlideshowGeometry.Motion {
    val cover = maxOf(w.toFloat() / pw, h.toFloat() / ph)
    val c = base?.scale?.takeIf { it >= cover } ?: cover
    // The base framing's centre offset from the holder centre, carried through the zoom.
    val bx = base?.let { it.x + pw * c / 2f - w / 2f } ?: 0f
    val by = base?.let { it.y + ph * c / 2f - h / 2f } ?: 0f
    fun at(f: Float, dx: Float, dy: Float): SlideshowGeometry.Transform {
      val s = c * f
      val sw = pw * s
      val sh = ph * s
      val x = (w / 2f + bx * f - sw / 2f + dx * sw).coerceIn(minOf(w - sw, 0f), 0f)
      val y = (h / 2f + by * f - sh / 2f + dy * sh).coerceIn(minOf(h - sh, 0f), 0f)
      return SlideshowGeometry.Transform(s, x, y)
    }
    return SlideshowGeometry.Motion(at(s0, dx0, dy0), at(s1, dx1, dy1), false)
  }
}
