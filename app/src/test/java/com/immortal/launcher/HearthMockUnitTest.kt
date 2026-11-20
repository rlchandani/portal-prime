/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */
package com.immortal.launcher

import org.junit.Assert.assertEquals
import org.junit.Test

class HearthMockUnitTest {
  private val portalPlus = 1.125f // 2160x1440 px = 1920x1280 dp

  @Test fun portalPlus_landscape_isTheMockUnit() =
      assertEquals(1920f / 1440f, HearthMockUnit.boost(2160, 1440, portalPlus), 1e-4f)

  @Test fun portalPlus_portrait_usesTheLongSide() =
      assertEquals(1920f / 1440f, HearthMockUnit.boost(1440, 2160, portalPlus), 1e-4f)

  @Test fun miniAndSmallPanels_keepTheirSizes() {
    assertEquals(1f, HearthMockUnit.boost(1280, 800, 1f), 0f)       // Portal Mini / 10" panel
    assertEquals(1f, HearthMockUnit.boost(480, 320, portalPlus), 0f) // a Settings preview tile
  }

  @Test fun badInput_isNeutral() {
    assertEquals(1f, HearthMockUnit.boost(0, 1440, portalPlus), 0f)
    assertEquals(1f, HearthMockUnit.boost(2160, 1440, 0f), 0f)
  }

  @Test fun hugeSurfaces_areCapped() =
      assertEquals(HearthMockUnit.MAX_BOOST, HearthMockUnit.boost(8000, 4000, 1f), 0f)

  @Test fun splitGlance_portalPlus_marginsAreMockUnits() {
    val (card, panel) = SplitGlancePanel.layout(2160, 1440, portalPlus)
    val unit = portalPlus * 1920f / 1440f // px per mock unit
    assertEquals((44 * unit).toInt(), card.top)
    assertEquals(1440 - (44 * unit).toInt(), card.bottom)
    assertEquals((2160 * .56f).toInt(), card.width)
    assertEquals((64 * unit).toInt(), panel.left)
    assertEquals(card.left - (48 * unit).toInt(), panel.right)
  }

  @Test fun splitGlance_mini_layoutUnchanged() {
    val (card, panel) = SplitGlancePanel.layout(1280, 800, 1f)
    assertEquals(44, card.top)
    assertEquals(64, panel.left)
    assertEquals(card.left - 48, panel.right)
  }
}
