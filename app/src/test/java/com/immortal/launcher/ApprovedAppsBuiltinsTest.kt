/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */
package com.immortal.launcher

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ApprovedAppsBuiltinsTest {
  @Test fun homeControl_isListedWhenHomePreparedItsTile() {
    val tiles = ApprovedAppsBuiltins.tiles(setOf("store", "music", "settings", "calls", ApprovedAppsBuiltins.CONTROL))
    assertTrue("control" to "Home Control" in tiles)
    assertTrue(tiles.none { it.first == ApprovedAppsBuiltins.DASHBOARD })
  }

  @Test fun optionalTiles_hiddenWithoutArtwork_alwaysTilesStay() {
    assertEquals(listOf("store", "music", "calls", "settings"), ApprovedAppsBuiltins.tiles(emptySet()).map { it.first })
    assertEquals(listOf("store", "music", "calls", "settings", "control", "dashboard"),
        ApprovedAppsBuiltins.tiles(setOf("control", "dashboard")).map { it.first })
  }

  @Test fun search_matchesLabels() {
    assertEquals(listOf("control"), ApprovedAppsBuiltins.tiles(setOf("control", "dashboard"), "control").map { it.first })
    assertEquals(listOf("control", "dashboard"), ApprovedAppsBuiltins.tiles(setOf("control", "dashboard"), " home ").map { it.first })
  }
}
