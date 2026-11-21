/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */
package com.immortal.launcher

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** The upright Mini's vertical filmstrip: the selected card fits the 800 dp width (was 1271 dp). */
class FilmstripVerticalGeometryTest {
  @Test fun selectedLandscapeCardFitsTheUprightWidth() {
    val layout = FilmstripGeometry.layout(800f, 1280f, listOf(1.5f, 2f / 3f, 1.5f), vertical = true)!!
    assertTrue(layout.vertical)
    val card = layout.cards[0]
    assertEquals(704f, card.width * 1.12f, .01f)
    assertEquals(card.width / 1.5f, card.height, .01f)
    // A portrait is capped by half the height instead.
    assertEquals(640f, layout.cards[1].height * 1.12f, .01f)
    // Offsets run down the screen with the shared 60 dp gap.
    assertEquals(card.height / 2f, card.offset, .01f)
    assertEquals(card.height + 60f + layout.cards[1].height / 2f, layout.cards[1].offset, .01f)
  }

  @Test fun centeredSelectedCardSitsInTheMiddleAndInsideTheScreen() {
    val layout = FilmstripGeometry.layout(800f, 1280f, List(5) { 1.5f }, vertical = true)!!
    for (index in layout.cards.indices) {
      val state = FilmstripGeometry.centered(layout, index)!!
      val rect = FilmstripGeometry.rect(layout, state, index)!!
      assertEquals(400f, rect.left + rect.width / 2f, .01f)
      assertEquals(640f, rect.top + rect.height / 2f, .01f)
      assertTrue(rect.left >= 0f && rect.left + rect.width <= 800f)
    }
  }

  @Test fun pixelBoundsCentreHorizontallyAndPlaceAlongY() {
    val layout = FilmstripGeometry.layout(800f, 1280f, listOf(1.5f, 1.5f), vertical = true)!!
    val b = FilmstripGeometry.cardPixelBounds(layout, 1, 2f)!!
    val card = layout.cards[1]
    assertEquals(((800f - card.width) / 2f * 2f).toInt(), b.left)
    assertTrue(kotlin.math.abs(((640f + card.offset - card.height / 2f) * 2f) - b.top) <= 1f)
  }

  @Test fun horizontalDefaultIsUnchanged() {
    val shipped = FilmstripGeometry.layout(1920f, 1219f, listOf(1.5f, .75f))!!
    assertFalse(shipped.vertical)
    assertEquals(1219f * .62f, shipped.cards[0].height, .01f)
    assertEquals(shipped, FilmstripGeometry.layout(1920f, 1219f, listOf(1.5f, .75f), vertical = false))
  }

  @Test fun verticalRejectsDegenerateViewports() {
    assertNull(FilmstripGeometry.layout(90f, 1280f, listOf(1.5f), vertical = true))
    assertNull(FilmstripGeometry.layout(800f, 1280f, listOf(0f), vertical = true))
    assertNotNull(FilmstripGeometry.layout(800f, 1280f, listOf(3f), vertical = true))
  }
}
