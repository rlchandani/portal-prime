/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */
package com.immortal.launcher

import kotlin.math.roundToInt

/**
 * Pure geometry for the filmstrip in screensaver/mode-impls.js and styles.css.
 * Dimensions are caller-supplied CSS units; the gap remains 60 in those units.
 * Card order is stable, including offscreen cards and full last-to-first travel.
 */
internal object FilmstripGeometry {
  /** Unscaled size and center offset measured from the track's left edge. */
  data class Card(val width: Float, val height: Float, val offset: Float)

  /** Width and height describe the viewport, rather than the complete track. */
  data class Layout(val width: Float, val height: Float, val cards: List<Card>)

  data class Appearance(val scale: Float, val opacity: Float, val brightness: Float)

  /** The track translates by negative trackOffset; appearances retain card order. */
  data class State(val trackOffset: Float, val appearances: List<Appearance>)

  /** Scaled card bounds, excluding the CSS shadow, clipping and backdrop. */
  data class Rect(val left: Float, val top: Float, val width: Float, val height: Float)

  data class PixelBounds(val left: Int, val top: Int, val width: Int, val height: Int)

  /** Android layout bounds before property transforms; viewport coordinates are explicit. */
  fun cardPixelBounds(layout: Layout, index: Int, density: Float): PixelBounds? {
    if (!valid(layout) || index !in layout.cards.indices || !positive(density)) return null
    val card = layout.cards[index]
    return PixelBounds(((layout.width/2f+card.offset-card.width/2f)*density).roundToInt(),
        ((layout.height-card.height)/2f*density).roundToInt(),
        (card.width*density).roundToInt().coerceAtLeast(1),
        (card.height*density).roundToInt().coerceAtLeast(1))
  }

  fun backdropPixelBounds(layout: Layout, density: Float): PixelBounds? {
    if (!valid(layout) || !positive(density)) return null
    return PixelBounds(-(layout.width*density*.19f).roundToInt(),
        -(layout.height*density*.19f).roundToInt(),
        (layout.width*density*1.38f).roundToInt(),(layout.height*density*1.38f).roundToInt())
  }

  private const val MAX_CARDS = 12
  private const val HEIGHT_FRACTION = .62
  private const val GAP = 60.0
  private val dimmed = Appearance(.7f, .4f, .55f)
  private val selected = Appearance(1.12f, 1f, 1f)

  /** Reject invalid inputs or geometry that cannot be represented by finite positive Floats. */
  fun layout(width: Float, height: Float, aspects: List<Float>): Layout? {
    if (!positive(width) || !positive(height) || aspects.size !in 1..MAX_CARDS) return null
    // Double intermediates match the JS arithmetic before the final Float boundary.
    val cardHeight = height.toDouble() * HEIGHT_FRACTION
    val roundedHeight = cardHeight.toFloat()
    if (!positive(roundedHeight)) return null
    val cards = ArrayList<Card>(aspects.size)
    var left = 0.0
    for (aspect in aspects) {
      if (!positive(aspect)) return null
      val cardWidth = cardHeight * aspect.toDouble()
      val roundedWidth = cardWidth.toFloat()
      val offset = (left + cardWidth / 2.0).toFloat()
      if (!positive(roundedWidth) || !positive(offset)) return null
      cards.add(Card(roundedWidth, roundedHeight, offset))
      left += cardWidth + GAP
    }
    return Layout(width, height, cards)
  }

  /** Initial CSS pose before the first center transition; requires a valid layout. */
  fun initial(layout: Layout): State {
    require(valid(layout)) { "Filmstrip requires a valid layout with 1..12 cards" }
    return State(0f, List(layout.cards.size) { dimmed })
  }

  fun centered(layout: Layout, index: Int): State? {
    if (!valid(layout) || index !in layout.cards.indices) return null
    return State(layout.cards[index].offset,
        List(layout.cards.size) { if (it == index) selected else dimmed })
  }

  /**
   * Progress is normalized elapsed time in [0, 1], before easing. The caller owns
   * the CSS 1.4-second timeline and can retarget from an actual intermediate State.
   * Both states must describe the same ordered cards; only count is verifiable here.
   * Invalid progress, appearance values or correspondence return null.
   */
  fun interpolate(from: State, to: State, progress: Float): State? {
    if (!progress.isFinite() || progress !in 0f..1f || !valid(from) || !valid(to) ||
        from.appearances.size != to.appearances.size) return null
    if (progress == 0f) return from
    if (progress == 1f) return to
    val transform = bezier(progress.toDouble(), .22, 1.0, .36, 1.0)
    val fade = bezier(progress.toDouble(), .25, .1, .25, 1.0)
    return State(lerp(from.trackOffset, to.trackOffset, transform),
        List(from.appearances.size) { index ->
          val a = from.appearances[index]
          val b = to.appearances[index]
          Appearance(lerp(a.scale, b.scale, transform), lerp(a.opacity, b.opacity, fade),
              lerp(a.brightness, b.brightness, fade))
        })
  }

  /** Return every card's actual bounds without culling or wrapping its center. */
  fun rect(layout: Layout, state: State, index: Int): Rect? {
    if (!valid(layout) || !valid(state) || state.appearances.size != layout.cards.size ||
        index !in layout.cards.indices) return null
    val card = layout.cards[index]
    val scale = state.appearances[index].scale.toDouble()
    val width = card.width.toDouble() * scale
    val height = card.height.toDouble() * scale
    val centerX = layout.width.toDouble() / 2.0 + card.offset.toDouble() - state.trackOffset
    val centerY = layout.height.toDouble() / 2.0
    val rect = Rect((centerX - width / 2.0).toFloat(), (centerY - height / 2.0).toFloat(),
        width.toFloat(), height.toFloat())
    return rect.takeIf { it.left.isFinite() && it.top.isFinite() &&
        positive(it.width) && positive(it.height) }
  }

  private fun positive(value: Float) = value.isFinite() && value > 0f

  private fun valid(layout: Layout): Boolean =
      positive(layout.width) && positive(layout.height) && layout.cards.size in 1..MAX_CARDS &&
          layout.cards.all { positive(it.width) && positive(it.height) && positive(it.offset) }

  private fun valid(state: State): Boolean =
      state.trackOffset.isFinite() && state.appearances.size in 1..MAX_CARDS &&
          state.appearances.all { positive(it.scale) && it.opacity.isFinite() &&
              it.opacity in 0f..1f && it.brightness.isFinite() && it.brightness >= 0f }

  /** Convex Double arithmetic avoids overflow in a Float endpoint difference. */
  private fun lerp(from: Float, to: Float, progress: Double): Float =
      if (from == to) from else
        (from.toDouble() * (1.0 - progress) + to.toDouble() * progress).toFloat()

  /** Solve x(t) = elapsed time, then evaluate y(t); both CSS curves have monotone x. */
  private fun bezier(progress: Double, x1: Double, y1: Double, x2: Double, y2: Double): Double {
    fun coordinate(t: Double, first: Double, second: Double): Double {
      val inverse = 1.0 - t
      return 3.0 * inverse * inverse * t * first +
          3.0 * inverse * t * t * second + t * t * t
    }
    var low = 0.0
    // Both CSS curves have dx/dt >= .5, so their solution is at most 2 * progress.
    // A relative bracket preserves tiny elapsed times instead of imposing a minimum movement.
    var high = minOf(1.0, 2.0 * progress)
    // Fixed work even for tiny progress; the initial interval shrinks by 2^-40.
    repeat(40) {
      val middle = (low + high) / 2.0
      if (coordinate(middle, x1, x2) < progress) low = middle else high = middle
    }
    return coordinate((low + high) / 2.0, y1, y2)
  }
}
