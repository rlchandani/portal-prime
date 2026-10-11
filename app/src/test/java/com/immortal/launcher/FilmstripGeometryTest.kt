/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */
package com.immortal.launcher

import com.immortal.launcher.FilmstripGeometry.Appearance
import com.immortal.launcher.FilmstripGeometry.Card
import com.immortal.launcher.FilmstripGeometry.Layout
import com.immortal.launcher.FilmstripGeometry.Rect
import com.immortal.launcher.FilmstripGeometry.State
import org.junit.Assert.*
import org.junit.Test

class FilmstripGeometryTest {
  @Test fun androidCardBoundsUseTheViewportForEveryCenteredCard() {
    val layout = FilmstripGeometry.layout(1080f,720f,List(12) { 1.5f })!!
    for (index in layout.cards.indices) {
      val bounds = FilmstripGeometry.cardPixelBounds(layout,index,2f)!!
      assertEquals(1080f,bounds.left+bounds.width/2f-layout.cards[index].offset*2f,1f)
      assertEquals(720f,bounds.top+bounds.height/2f,1f)
      assertTrue(bounds.top > 0)
    }
  }
  @Test fun androidBackdropOverscanIsCenteredOnTheFullViewport() {
    val layout = FilmstripGeometry.layout(1080f,720f,listOf(1f))!!
    val bounds = FilmstripGeometry.backdropPixelBounds(layout,2f)!!
    assertEquals(1080f,bounds.left+bounds.width/2f,1f)
    assertEquals(720f,bounds.top+bounds.height/2f,1f)
    assertEquals(2981,bounds.width)
    assertEquals(1987,bounds.height)
  }
  @Test fun androidPixelBoundsRejectInvalidDensityAndRetainPortraitCentering() {
    val layout = FilmstripGeometry.layout(720f,1080f,listOf(.5f,1.5f))!!
    assertNull(FilmstripGeometry.cardPixelBounds(layout,0,0f))
    assertNull(FilmstripGeometry.backdropPixelBounds(layout,Float.NaN))
    val bounds = FilmstripGeometry.cardPixelBounds(layout,1,2f)!!
    assertEquals(720f,bounds.left+bounds.width/2f-layout.cards[1].offset*2f,1f)
    assertEquals(1080f,bounds.top+bounds.height/2f,1f)
  }

  @Test fun mixedAspectsKeepFractionalSizesAndSixtyCssUnitGaps() {
    val layout = FilmstripGeometry.layout(1200.5f, 801.25f, listOf(.5f, 1.625f, 2.25f))!!
    assertEquals(1200.5f, layout.width, 0f)
    assertEquals(801.25f, layout.height, 0f)
    assertEquals(3, layout.cards.size)
    // Hand-derived from height 496.775 and successive unscaled widths plus 60.
    assertCard(layout.cards[0], 248.3875f, 496.775f, 124.19375f)
    assertCard(layout.cards[1], 807.259375f, 496.775f, 712.0171875f)
    assertCard(layout.cards[2], 1117.74375f, 496.775f, 1734.51875f)
    for (index in 1 until layout.cards.size) {
      val previous = layout.cards[index - 1]
      val current = layout.cards[index]
      assertEquals(60f, current.offset - current.width / 2 -
          (previous.offset + previous.width / 2), .001f)
    }
  }

  @Test fun callerUnitsKeepTheFixedGapEvenWhenViewportDimensionsDouble() {
    val small = FilmstripGeometry.layout(1000f, 500f, listOf(1f, 1f))!!
    val large = FilmstripGeometry.layout(2000f, 1000f, listOf(1f, 1f))!!
    assertCard(small.cards[1], 310f, 310f, 525f)
    assertCard(large.cards[1], 620f, 620f, 990f)
  }

  @Test fun initialAdmissionHasZeroTrackOffsetAndAllCardsDimmed() {
    val layout = FilmstripGeometry.layout(1200.5f, 801.25f, listOf(.5f, 1.625f, 2.25f))!!
    val state = FilmstripGeometry.initial(layout)
    assertEquals(0f, state.trackOffset, 0f)
    assertEquals(List(3) { Appearance(.7f, .4f, .55f) }, state.appearances)
    assertRect(FilmstripGeometry.rect(layout, state, 0)!!,
        637.508125f, 226.75375f, 173.87125f, 347.7425f)
    assertTrue(FilmstripGeometry.rect(layout, state, 2)!!.left > layout.width)
  }

  @Test fun centeringUsesTheExactCardOffsetAndScalesAroundItsCenter() {
    val layout = FilmstripGeometry.layout(1200.5f, 801.25f, listOf(.5f, 1.625f, 2.25f))!!
    for (index in layout.cards.indices) {
      val state = FilmstripGeometry.centered(layout, index)!!
      assertEquals(layout.cards[index].offset, state.trackOffset, 0f)
      state.appearances.forEachIndexed { cardIndex, appearance ->
        assertEquals(if (cardIndex == index) Appearance(1.12f, 1f, 1f)
            else Appearance(.7f, .4f, .55f), appearance)
      }
      val rect = FilmstripGeometry.rect(layout, state, index)!!
      assertEquals(600.25f, rect.left + rect.width / 2, .001f)
      assertEquals(400.625f, rect.top + rect.height / 2, .001f)
    }
    val centered = FilmstripGeometry.centered(layout, 1)!!
    assertRect(FilmstripGeometry.rect(layout, centered, 1)!!,
        148.18475f, 122.431f, 904.1305f, 556.388f)
    assertRect(FilmstripGeometry.rect(layout, centered, 0)!!,
        -74.5090625f, 226.75375f, 173.87125f, 347.7425f)
  }

  @Test fun interpolationPreservesBothEndpointsExactly() {
    val layout = FilmstripGeometry.layout(1000f, 500f, listOf(.5f, 2f))!!
    val from = FilmstripGeometry.initial(layout)
    val to = FilmstripGeometry.centered(layout, 1)!!
    assertEquals(from, FilmstripGeometry.interpolate(from, to, 0f))
    assertEquals(to, FilmstripGeometry.interpolate(from, to, 1f))
    assertEquals(to, FilmstripGeometry.interpolate(to, to, .5f))
  }

  @Test fun trackAndScaleInvertTheCustomBezierTimeCoordinate() {
    val from = State(0f, listOf(Appearance(.7f, .4f, .55f)))
    val to = State(160f, listOf(Appearance(1.12f, 1f, 1f)))
    // At Bezier parameters .25, .5, .75, elapsed time x differs from the parameter.
    // Their (x, y) pairs are (.1590625, .578125), (.3425, .875),
    // and (.6046875, .984375), independently evaluated from the CSS controls.
    val samples = listOf(
        Triple(.1590625f, 92.5f, .9428125f),
        Triple(.3425f, 140f, 1.0675f),
        Triple(.6046875f, 157.5f, 1.1134375f))
    for ((progress, offset, scale) in samples) {
      val state = FilmstripGeometry.interpolate(from, to, progress)!!
      assertEquals(offset, state.trackOffset, .0001f)
      assertEquals(scale, state.appearances.single().scale, .000001f)
    }
  }

  @Test fun opacityAndBrightnessUseCssEaseRatherThanTheTransformCurve() {
    val from = State(0f, listOf(Appearance(.7f, .4f, .55f)))
    val to = State(160f, listOf(Appearance(1.12f, 1f, 1f)))
    // ease(.25, .1, .25, 1) at parameters .25, .5, .75 gives y
    // .1984375, .5375, .8578125 at elapsed times .15625, .3125, .5625.
    val samples = listOf(
        Triple(.15625f, .5190625f, .639296875f),
        Triple(.3125f, .7225f, .791875f),
        Triple(.5625f, .9146875f, .936015625f))
    for ((progress, opacity, brightness) in samples) {
      val appearance = FilmstripGeometry.interpolate(from, to, progress)!!.appearances.single()
      assertEquals(opacity, appearance.opacity, .000001f)
      assertEquals(brightness, appearance.brightness, .000001f)
    }
  }

  @Test fun interruptedRetargetStartsAtTheActualCurrentTrackAndScales() {
    val layout = FilmstripGeometry.layout(1000f, 500f, listOf(1f, 1f, 1f))!!
    val from = FilmstripGeometry.centered(layout, 0)!!
    val abandoned = FilmstripGeometry.centered(layout, 1)!!
    val current = FilmstripGeometry.interpolate(from, abandoned, .3425f)!!
    assertEquals(478.75f, current.trackOffset, .0001f)
    assertEquals(.7525f, current.appearances[0].scale, .000001f)
    val target = FilmstripGeometry.centered(layout, 2)!!
    val resumed = FilmstripGeometry.interpolate(current, target, 0f)!!
    assertEquals(current, resumed)
    for (index in layout.cards.indices) {
      assertEquals(FilmstripGeometry.rect(layout, current, index),
          FilmstripGeometry.rect(layout, resumed, index))
    }
    val advanced = FilmstripGeometry.interpolate(current, target, .1590625f)!!
    assertEquals(719.39453125f, advanced.trackOffset, .0001f)
    assertEquals(.7221484375f, advanced.appearances[0].scale, .000001f)
    assertEquals(.8550390625f, advanced.appearances[1].scale, .000001f)
    assertEquals(.9428125f, advanced.appearances[2].scale, .000001f)
    assertEquals(target, FilmstripGeometry.interpolate(current, target, 1f))
  }

  @Test fun interruptedRetargetPreservesTheCurrentOpacityAndBrightness() {
    val layout = FilmstripGeometry.layout(1000f, 500f, listOf(1f, 1f, 1f))!!
    val current = FilmstripGeometry.interpolate(FilmstripGeometry.centered(layout, 0)!!,
        FilmstripGeometry.centered(layout, 1)!!, .3125f)!!
    val target = FilmstripGeometry.centered(layout, 2)!!
    val advanced = FilmstripGeometry.interpolate(current, target, .3125f)!!
    assertEquals(.52834375f, advanced.appearances[0].opacity, .000001f)
    assertEquals(.6462578125f, advanced.appearances[0].brightness, .000001f)
    assertEquals(.54915625f, advanced.appearances[1].opacity, .000001f)
    assertEquals(.6618671875f, advanced.appearances[1].brightness, .000001f)
    assertEquals(.7225f, advanced.appearances[2].opacity, .000001f)
    assertEquals(.791875f, advanced.appearances[2].brightness, .000001f)
  }

  @Test fun lastToFirstWrapTravelsTheWholeTrackPastTheIntermediateCard() {
    val layout = FilmstripGeometry.layout(1000f, 500f, listOf(1f, 1f, 1f))!!
    val last = FilmstripGeometry.centered(layout, 2)!!
    val first = FilmstripGeometry.centered(layout, 0)!!
    assertEquals(895f, last.trackOffset, 0f)
    assertEquals(155f, first.trackOffset, 0f)
    val passing = FilmstripGeometry.interpolate(last, first, .1590625f)!!
    assertEquals(467.1875f, passing.trackOffset, .0001f)
    assertEquals(3, passing.appearances.size)
    assertEquals(Appearance(.7f, .4f, .55f), passing.appearances[1])
    val middle = FilmstripGeometry.rect(layout, passing, 1)!!
    assertRect(middle, 449.3125f, 141.5f, 217f, 217f)
    assertEquals(557.8125f, middle.left + middle.width / 2, .0001f)
    var previous = last.trackOffset
    for (step in 0..100) {
      val state = FilmstripGeometry.interpolate(last, first, step / 100f)!!
      assertTrue(state.trackOffset <= previous)
      assertTrue(state.trackOffset >= first.trackOffset)
      assertEquals(3, state.appearances.size)
      previous = state.trackOffset
    }
    assertEquals(first, FilmstripGeometry.interpolate(last, first, 1f))
  }

  @Test fun singleCardStillPansInFromInitialAdmissionAndCanRemainCentered() {
    val layout = FilmstripGeometry.layout(1000f, 500f, listOf(1f))!!
    val initial = FilmstripGeometry.initial(layout)
    val centered = FilmstripGeometry.centered(layout, 0)!!
    assertRect(FilmstripGeometry.rect(layout, initial, 0)!!, 546.5f, 141.5f, 217f, 217f)
    assertEquals(155f, centered.trackOffset, 0f)
    assertRect(FilmstripGeometry.rect(layout, centered, 0)!!, 326.4f, 76.4f, 347.2f, 347.2f)
    assertEquals(centered, FilmstripGeometry.interpolate(centered, centered, .3425f))
    assertNull(FilmstripGeometry.centered(layout, 1))
  }

  @Test fun invalidDimensionsAspectsAndCardCountsAreRejected() {
    val invalid = listOf(0f, -1f, Float.NaN, Float.NEGATIVE_INFINITY, Float.POSITIVE_INFINITY)
    for (value in invalid) {
      assertNull(FilmstripGeometry.layout(value, 500f, listOf(1f)))
      assertNull(FilmstripGeometry.layout(1000f, value, listOf(1f)))
      assertNull(FilmstripGeometry.layout(1000f, 500f, listOf(1f, value, 2f)))
    }
    assertNull(FilmstripGeometry.layout(1000f, 500f, emptyList()))
    assertNull(FilmstripGeometry.layout(1000f, 500f, List(13) { 1f }))
    val maximum = FilmstripGeometry.layout(1000f, 500f, List(12) { 1f })!!
    assertEquals(12, maximum.cards.size)
    assertEquals(4225f, FilmstripGeometry.centered(maximum, 11)!!.trackOffset, 0f)
  }

  @Test fun unrepresentableCardDimensionsAndAccumulatedOffsetsAreRejected() {
    assertNull(FilmstripGeometry.layout(1000f, Float.MAX_VALUE, listOf(2f)))
    assertNull(FilmstripGeometry.layout(1000f, Float.MAX_VALUE, listOf(1f, 1f, 1f)))
    assertNull(FilmstripGeometry.layout(1000f, Float.MIN_VALUE, listOf(.5f)))
    assertNull(FilmstripGeometry.layout(1000f, Float.MIN_VALUE, listOf(Float.MIN_VALUE)))
  }

  @Test fun finitePositiveAspectsAreNotQuantizedOrClamped() {
    val layout = FilmstripGeometry.layout(1200f, 800f, listOf(.01f, 100f))!!
    assertCard(layout.cards[0], 4.96f, 496f, 2.48f)
    assertCard(layout.cards[1], 49600f, 496f, 24864.96f)
    val tiny = FilmstripGeometry.layout(1000f, 500f, listOf(Float.MIN_VALUE))!!
    assertTrue(tiny.cards.single().width > 0f)
    assertTrue(tiny.cards.single().offset > 0f)
  }

  @Test fun interpolationRejectsInvalidProgressAndMismatchedCorrespondence() {
    val state = State(0f, listOf(Appearance(.7f, .4f, .55f)))
    for (progress in listOf(-.001f, 1.001f, Float.NaN,
        Float.NEGATIVE_INFINITY, Float.POSITIVE_INFINITY)) {
      assertNull(FilmstripGeometry.interpolate(state, state, progress))
    }
    val mismatched = state.copy(appearances = List(2) { state.appearances.single() })
    for (progress in listOf(0f, .5f, 1f)) {
      assertNull(FilmstripGeometry.interpolate(state, mismatched, progress))
      assertNull(FilmstripGeometry.interpolate(mismatched, state, progress))
      assertNull(FilmstripGeometry.interpolate(state.copy(appearances = emptyList()),
          state.copy(appearances = emptyList()), progress))
      val oversized = state.copy(appearances = List(13) { state.appearances.single() })
      assertNull(FilmstripGeometry.interpolate(oversized, oversized, progress))
    }
  }

  @Test fun malformedStatesAreRejectedBeforeReturningEndpointsOrRectangles() {
    val layout = FilmstripGeometry.layout(1000f, 500f, listOf(1f))!!
    val valid = FilmstripGeometry.initial(layout)
    val appearance = valid.appearances.single()
    val invalidAppearances = listOf(
        appearance.copy(scale = 0f), appearance.copy(scale = -1f),
        appearance.copy(scale = Float.NaN), appearance.copy(scale = Float.POSITIVE_INFINITY),
        appearance.copy(opacity = -.1f), appearance.copy(opacity = 1.1f),
        appearance.copy(opacity = Float.NaN), appearance.copy(opacity = Float.POSITIVE_INFINITY),
        appearance.copy(brightness = -1f), appearance.copy(brightness = Float.NaN),
        appearance.copy(brightness = Float.POSITIVE_INFINITY))
    val invalidStates = invalidAppearances.map { valid.copy(appearances = listOf(it)) } +
        listOf(valid.copy(trackOffset = Float.NaN),
            valid.copy(trackOffset = Float.POSITIVE_INFINITY),
            valid.copy(trackOffset = Float.NEGATIVE_INFINITY),
            valid.copy(appearances = emptyList()),
            valid.copy(appearances = List(2) { appearance }))
    for (invalid in invalidStates) {
      for (progress in listOf(0f, .5f, 1f)) {
        assertNull(FilmstripGeometry.interpolate(invalid, valid, progress))
        assertNull(FilmstripGeometry.interpolate(valid, invalid, progress))
      }
      assertNull(FilmstripGeometry.rect(layout, invalid, 0))
    }
  }

  @Test fun malformedLayoutsAndInvalidIndicesAreRejected() {
    val valid = FilmstripGeometry.layout(1000f, 500f, listOf(1f))!!
    val state = FilmstripGeometry.initial(valid)
    assertNull(FilmstripGeometry.centered(valid, -1))
    assertNull(FilmstripGeometry.centered(valid, 1))
    assertNull(FilmstripGeometry.rect(valid, state, -1))
    assertNull(FilmstripGeometry.rect(valid, state, 1))
    val card = valid.cards.single()
    val malformed = listOf(
        valid.copy(width = 0f), valid.copy(height = Float.NaN),
        valid.copy(cards = emptyList()), valid.copy(cards = List(13) { card }),
        valid.copy(cards = listOf(card.copy(width = 0f))),
        valid.copy(cards = listOf(card.copy(height = Float.POSITIVE_INFINITY))),
        valid.copy(cards = listOf(card.copy(offset = Float.NaN))),
        valid.copy(cards = listOf(card.copy(offset = -1f))))
    for (layout in malformed) {
      assertNull(FilmstripGeometry.centered(layout, 0))
      assertNull(FilmstripGeometry.rect(layout, state, 0))
    }
  }

  @Test(expected = IllegalArgumentException::class)
  fun initialRequiresAValidatedLayout() {
    FilmstripGeometry.initial(Layout(1000f, 500f, emptyList()))
  }

  @Test fun convexInterpolationAvoidsOverflowForFiniteStateEndpoints() {
    val from = State(-Float.MAX_VALUE, listOf(Appearance(Float.MAX_VALUE, 0f, Float.MAX_VALUE)))
    val to = State(Float.MAX_VALUE, listOf(Appearance(Float.MAX_VALUE, 1f, Float.MAX_VALUE)))
    val state = FilmstripGeometry.interpolate(from, to, .3425f)!!
    assertEquals(Float.MAX_VALUE * .75f, state.trackOffset, Float.MAX_VALUE * .000001f)
    assertEquals(Float.MAX_VALUE, state.appearances.single().scale, 0f)
    assertEquals(Float.MAX_VALUE, state.appearances.single().brightness, 0f)
    assertTrue(state.appearances.single().opacity in 0f..1f)
  }

  @Test fun tinyElapsedTimeDoesNotCreateAnArtificialMinimumTrackMovement() {
    val from = State(0f, listOf(Appearance(.7f, .4f, .55f)))
    val to = from.copy(trackOffset = Float.MAX_VALUE)
    val state = FilmstripGeometry.interpolate(from, to, Float.MIN_VALUE)!!
    // Near zero, dy/dx = 3 / .66 = 50/11; quadratic terms are negligible here.
    val expected = (Float.MAX_VALUE.toDouble() * Float.MIN_VALUE.toDouble() * (50.0 / 11)).toFloat()
    assertEquals(expected, state.trackOffset, expected * .000001f)
    assertEquals(from.appearances, state.appearances)
  }

  @Test fun rectRejectsOverflowInsteadOfPublishingInfiniteCoordinates() {
    val layout = FilmstripGeometry.layout(1000f, 500f, listOf(1f))!!
    val enlarged = FilmstripGeometry.initial(layout).copy(
        appearances = listOf(Appearance(Float.MAX_VALUE, 1f, 1f)))
    assertNull(FilmstripGeometry.rect(layout, enlarged, 0))
    val far = Layout(Float.MAX_VALUE, 500f, listOf(Card(1f, 1f, Float.MAX_VALUE)))
    assertNull(FilmstripGeometry.rect(far,
        State(-Float.MAX_VALUE, listOf(Appearance(1f, 1f, 1f))), 0))
  }

  private fun assertCard(card: Card, width: Float, height: Float, offset: Float) {
    assertEquals(width, card.width, .001f)
    assertEquals(height, card.height, .001f)
    assertEquals(offset, card.offset, .001f)
  }

  private fun assertRect(rect: Rect, left: Float, top: Float, width: Float, height: Float) {
    assertEquals(left, rect.left, .001f)
    assertEquals(top, rect.top, .001f)
    assertEquals(width, rect.width, .001f)
    assertEquals(height, rect.height, .001f)
  }
}
