/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */
package com.immortal.launcher

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
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

  @Test fun homeAssistant_isListedOnlyOnceHomePreparedIt() {
    assertTrue(ApprovedAppsBuiltins.tiles(emptySet()).none { it.first == ApprovedAppsBuiltins.HA_DASHBOARD })
    assertTrue(ApprovedAppsBuiltins.HA_DASHBOARD to "Home Assistant" in
        ApprovedAppsBuiltins.tiles(setOf(ApprovedAppsBuiltins.HA_DASHBOARD)))
  }

  @Test fun homeAssistant_sitsBetweenHomeControlAndTheBrowserDashboard() {
    assertEquals(listOf("store", "music", "calls", "settings", "control", "ha_dashboard", "dashboard"),
        ApprovedAppsBuiltins.tiles(setOf("control", "ha_dashboard", "dashboard")).map { it.first })
  }

  @Test fun homeAssistant_isFoundBySearch() {
    assertEquals(listOf("ha_dashboard"), ApprovedAppsBuiltins.tiles(setOf("control", "ha_dashboard"), "assistant").map { it.first })
  }

  /** The approved icon catalog draws the Home Assistant tile; the other built-ins keep Home's artwork. */
  @Test fun homeAssistant_usesTheCatalogGlyphNotLegacyArtwork() {
    assertNull(ApprovedAppsBuiltins.artwork(ApprovedAppsBuiltins.HA_DASHBOARD, "legacy"))
    assertEquals("art", ApprovedAppsBuiltins.artwork(ApprovedAppsBuiltins.CONTROL, "art"))
    assertEquals("art", ApprovedAppsBuiltins.artwork(null, "art"))
    assertEquals("home" to androidx.compose.ui.graphics.Color(0xFF2879B8), approvedAppIdentity("builtin:ha_dashboard"))
  }

  /**
   * The "Home Assistant" app-grid entry resolves to a branded identity tile (its teal squircle with
   * the house glyph), not a tile-less bare glyph — the same way its "Home Control" and "Home
   * dashboard" siblings are tiled. It has no prepared artwork (catalog glyph), sits on the plain
   * grid, yet still draws a tile because its package has a known identity.
   */
  @Test fun homeAssistant_resolvesToABrandedTileLikeItsHomeSiblings() {
    val pkg = "builtin:${ApprovedAppsBuiltins.HA_DASHBOARD}"
    val identity = approvedAppIdentity(pkg)
    assertNotNull("the Home Assistant entry has a branded identity", identity)
    // No prepared artwork → the catalog-glyph (NO_ARTWORK) branch renders it.
    assertNull(ApprovedAppsBuiltins.artwork(ApprovedAppsBuiltins.HA_DASHBOARD, "legacy"))
    // On the plain grid, a known identity is still tiled — never a bare glyph.
    assertTrue("Home Assistant must be tiled on the plain grid",
        IconRenderer.tiled(plain = true, hasIdentity = identity != null))
    // Its siblings resolve to their own branded tiles too.
    assertNotNull(approvedAppIdentity("builtin:${ApprovedAppsBuiltins.CONTROL}"))
    assertNotNull(approvedAppIdentity("builtin:${ApprovedAppsBuiltins.DASHBOARD}"))
  }
}
