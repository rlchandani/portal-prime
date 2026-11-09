/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */
package com.immortal.launcher

import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/** An axis-aligned rectangle in dp; immutable. */
internal data class MusicRect(val left: Float, val top: Float, val right: Float, val bottom: Float) {
  val width: Float get() = right - left
  val height: Float get() = bottom - top

  /** True when the two share more than [MusicStageGeometry.TOUCH_EPSILON] of area on both axes. */
  fun intersects(o: MusicRect): Boolean =
      min(right, o.right) - max(left, o.left) > MusicStageGeometry.TOUCH_EPSILON &&
          min(bottom, o.bottom) - max(top, o.top) > MusicStageGeometry.TOUCH_EPSILON

  fun inside(o: MusicRect): Boolean = left >= o.left && top >= o.top && right <= o.right && bottom <= o.bottom

  companion object {
    fun of(x: Float, y: Float, w: Float, h: Float) = MusicRect(x, y, x + w, y + h)
  }
}

/**
 * Fixed sizes per Portal (dp), taken from the approved "Full-screen + pull-up" mock. [LARGE] is the
 * 1920×1280 dp Portal+, [COMPACT] the 1280×800 dp Portal.
 */
internal data class MusicStageSpec(
    val compact: Boolean,
    /** Top bar height (back, wordmark, clock, account). */
    val top: Float,
    /** Closed sheet: the "Browse · Up next" bar along the bottom edge. */
    val peek: Float,
    /** Open sheet: height of the header strip above it. */
    val header: Float,
    val edge: Float,
    val pad: Float,
    val art: Float,
    val artRadius: Float,
    val artToPlayer: Float,
    val playerW: Float,
    val playerH: Float,
    val playerRadius: Float,
    val playerTopOpen: Float,
    val playerTopSearch: Float,
    /** Space between the player and the sheet edge while Search has focus. */
    val searchGap: Float,
    val columnGap: Float,
    val stripGap: Float,
    val resumeArt: Float,
    /** Smallest gap kept between the closed player and the sheet's bar. */
    val minClosedGap: Float,
    val accountsW: Float,
    val toastW: Float,
    val toastH: Float,
    val toastInset: Float,
)

/** Everything derived from one screen size; all values in dp. */
internal data class MusicStageLayout(
    val spec: MusicStageSpec,
    val width: Float,
    val height: Float,
    val art: Float,
    val artTop: Float,
    val playerClosed: MusicRect,
    val playerOpen: MusicRect,
    val playerSearch: MusicRect,
    val sheetClosedTop: Float,
    val sheetOpenTop: Float,
    val sheetSearchTop: Float,
    val columnX: Float,
    val columnW: Float,
    /** Where the strip's text and controls sit while open (right of the player). */
    val stripTextX: Float,
) {
  val screen: MusicRect get() = MusicRect(0f, 0f, width, height)
  /** The sheet's height: [MusicStageGeometry.OVERSHOOT] taller than needed so a spring never shows a gap. */
  val sheetHeight: Float get() = height - sheetSearchTop + MusicStageGeometry.OVERSHOOT
  val sheetTravel: Float get() = sheetClosedTop - sheetOpenTop
  /** Closed Now Playing: the right-hand column (title, transport, popovers). */
  val column: MusicRect get() = MusicRect(columnX, spec.top, columnX + columnW, sheetClosedTop)
  val artRect: MusicRect get() = MusicRect.of(spec.edge, artTop, art, art)
  /** Idle Resume card: the artwork, gap and player stacked. */
  val resumeCard: MusicRect get() = MusicRect(spec.edge, artTop, spec.edge + art, playerClosed.bottom)
  /** The account menu under the top bar, at the right edge. */
  val accountsMenu: MusicRect
    get() = MusicRect(width - spec.edge - spec.accountsW, spec.top - ACCOUNTS_LIFT, width - spec.edge, sheetClosedTop)
  /** "Video · Official YouTube player" caption beside the closed player (large Portal only). */
  val playerCaption: MusicRect?
    get() = if (spec.compact) null else MusicRect(playerClosed.right + CAPTION_GAP, playerClosed.bottom - CAPTION_H,
        columnX - CAPTION_GAP, playerClosed.bottom)

  /** Where a toast may draw: top centre while closed, bottom centre while open, top right while typing. */
  fun toast(open: Boolean, searching: Boolean): MusicRect {
    val w = min(spec.toastW, width - 2 * spec.pad)
    return when {
      open && searching -> {
        val room = min(w, width - spec.pad - stripTextX)
        MusicRect(width - spec.pad - room, spec.toastInset, width - spec.pad, spec.toastInset + spec.toastH)
      }
      open -> MusicRect((width - w) / 2, height - spec.toastInset - spec.toastH, (width + w) / 2, height - spec.toastInset)
      else -> MusicRect((width - w) / 2, spec.toastInset, (width + w) / 2, spec.toastInset + spec.toastH)
    }
  }

  /** The sheet's rectangle for its top edge at [top] (menus and lists live only inside it). */
  fun sheet(top: Float): MusicRect = MusicRect(0f, top, width, height)

  private companion object {
    const val ACCOUNTS_LIFT = 4f
    const val CAPTION_GAP = 28f
    const val CAPTION_H = 62f
  }
}

/** One instant of the sheet transition: every moving part comes from the same progress. */
internal data class MusicStageFrame(
    val player: MusicRect,
    val sheetTop: Float,
    /** Closed Now Playing (top bar, artwork, column). */
    val nowAlpha: Float,
    val stripAlpha: Float,
    val peekAlpha: Float,
    val tabsAlpha: Float,
    /** The closed column and artwork rise by this much as the sheet opens. */
    val lift: Float,
    /** The strip slides down into place by this much while it fades in. */
    val stripDrop: Float,
) {
  val gap: Float get() = sheetTop - player.bottom
}

/**
 * Layout of the full-screen Music stage. One progress value (0 closed → 1 open) plus the Search
 * focus value (0 → 1) place the YouTube player and the sheet edge on straight lines between
 * positive-gap rest states, so they can never meet, overshoot included.
 */
internal object MusicStageGeometry {
  /** YouTube: the player must be at least 200×200 CSS px (= dp in the WebView). */
  const val MIN_PLAYER = 200f
  const val MIN_TARGET = 64f
  const val OVERSHOOT = 80f
  /** Progress is clamped to this much past either end (spring overshoot, drag rubber band). */
  const val OVERSHOOT_PROGRESS = 0.02f
  const val TOUCH_EPSILON = 0.5f
  /** The artwork never shrinks below this on a very short window (dp). */
  private const val MIN_ART = 120f
  /** Screens at least this wide (dp) use [LARGE]. */
  const val LARGE_MIN_WIDTH = 1600f
  private const val NOW_FADE = 2.2f
  private const val STRIP_FROM = 0.45f
  private const val STRIP_SPAN = 0.5f
  private const val PEEK_FADE = 3f
  private const val TABS_FROM = 0.3f
  private const val TABS_SPAN = 0.5f
  private const val LIFT = 50f
  private const val STRIP_DROP = 30f

  val LARGE = MusicStageSpec(compact = false, top = 112f, peek = 128f, header = 384f, edge = 120f, pad = 64f,
      art = 720f, artRadius = 26f, artToPlayer = 20f, playerW = 384f, playerH = 216f, playerRadius = 18f,
      playerTopOpen = 84f, playerTopSearch = 24f, searchGap = 40f, columnGap = 120f, stripGap = 32f,
      resumeArt = 400f, minClosedGap = 24f, accountsW = 720f, toastW = 980f, toastH = 112f, toastInset = 28f)
  val COMPACT = MusicStageSpec(compact = true, top = 76f, peek = 92f, header = 240f, edge = 48f, pad = 36f,
      art = 380f, artRadius = 18f, artToPlayer = 14f, playerW = 364f, playerH = 205f, playerRadius = 14f,
      playerTopOpen = 18f, playerTopSearch = 12f, searchGap = 12f, columnGap = 56f, stripGap = 20f,
      resumeArt = 200f, minClosedGap = 16f, accountsW = 560f, toastW = 760f, toastH = 96f, toastInset = 24f)

  fun spec(widthDp: Float): MusicStageSpec = if (widthDp >= LARGE_MIN_WIDTH) LARGE else COMPACT

  fun layout(widthDp: Float, heightDp: Float, spec: MusicStageSpec = spec(widthDp)): MusicStageLayout {
    val sheetClosed = heightDp - spec.peek
    val avail = sheetClosed - spec.top
    // The artwork gives way first on a short screen, so the closed gap never drops below the floor.
    val art = min(spec.art, avail - spec.artToPlayer - spec.playerH - 2 * spec.minClosedGap)
        .coerceAtLeast(MIN_ART)
    val block = art + spec.artToPlayer + spec.playerH
    val artTop = spec.top + ((avail - block) / 2f).roundToInt()
    val closed = MusicRect.of(spec.edge, artTop + art + spec.artToPlayer, spec.playerW, spec.playerH)
    val open = MusicRect.of(spec.pad, spec.playerTopOpen, spec.playerW, spec.playerH)
    val search = MusicRect.of(spec.pad, spec.playerTopSearch, spec.playerW, spec.playerH)
    // The column starts right of whichever is wider, the artwork or the player under it.
    val columnX = spec.edge + max(art, spec.playerW) + spec.columnGap
    return MusicStageLayout(spec, widthDp, heightDp, art, artTop, closed, open, search,
        sheetClosedTop = sheetClosed, sheetOpenTop = spec.header,
        sheetSearchTop = search.bottom + spec.searchGap,
        columnX = columnX, columnW = widthDp - spec.edge - columnX,
        stripTextX = spec.pad + spec.playerW + spec.stripGap)
  }

  /** [progress]: 0 closed → 1 open (may overshoot). [focus]: 0 → 1 as Search takes focus. */
  fun frame(l: MusicStageLayout, progress: Float, focus: Float): MusicStageFrame {
    val q = progress.coerceIn(-OVERSHOOT_PROGRESS, 1f + OVERSHOOT_PROGRESS)
    val f = focus.coerceIn(0f, 1f)
    val openX = lerp(l.playerOpen.left, l.playerSearch.left, f)
    val openY = lerp(l.playerOpen.top, l.playerSearch.top, f)
    val player = MusicRect.of(lerp(l.playerClosed.left, openX, q), lerp(l.playerClosed.top, openY, q),
        l.spec.playerW, l.spec.playerH)
    val sheetOpen = lerp(l.sheetOpenTop, l.sheetSearchTop, f)
    return MusicStageFrame(
        player = player,
        sheetTop = lerp(l.sheetClosedTop, sheetOpen, q),
        nowAlpha = clamp01(1f - q * NOW_FADE),
        stripAlpha = clamp01((q - STRIP_FROM) / STRIP_SPAN),
        peekAlpha = clamp01(1f - q * PEEK_FADE),
        tabsAlpha = clamp01((q - TABS_FROM) / TABS_SPAN),
        lift = -LIFT * q,
        stripDrop = (1f - clamp01(q)) * STRIP_DROP,
    )
  }

  /** Below this progress the closed Now Playing takes touches; at or above it, the strip does. */
  const val INTERACTIVE_SPLIT = 0.5f

  fun lerp(a: Float, b: Float, t: Float): Float = a + (b - a) * t
  fun clamp01(x: Float): Float = x.coerceIn(0f, 1f)
}
