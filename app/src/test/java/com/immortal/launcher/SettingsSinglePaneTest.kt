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

/** Single-pane Settings (the Mini upright) and the deep link that a rotation must not replay. */
class SettingsSinglePaneTest {
  private val deepLinked = SettingsBackStack.of(SettingsPageIds.SOUNDS)
  private val restored = SettingsBackStack.of(SettingsPageIds.WIDGETS)
  private val last = SettingsBackStack.of(SettingsPageIds.DISPLAY)

  @Test fun aFirstOpenFollowsTheDeepLink() {
    assertEquals(deepLinked, settingsEntryStack(false, { deepLinked }, { restored }, { last }))
  }

  @Test fun aRecreateRestoresInsteadOfReplayingTheDeepLink() {
    var replayed = false
    val stack = settingsEntryStack(true, { replayed = true; deepLinked }, { restored }, { last })
    assertEquals(restored, stack)
    assertTrue("the deep link (and any editor it launches) is never re-run", !replayed)
  }

  @Test fun withoutALinkOrSavedStateTheLastPageOpens() {
    assertEquals(last, settingsEntryStack(false, { null }, { restored }, { last }))
    assertEquals(last, settingsEntryStack(true, { deepLinked }, { null }, { last }))
  }

  @Test fun theSplitViewMetricsAreUnchanged() {
    assertEquals(1008, SettingsSplitMetrics.pageContentWidthDp(1920))
    assertEquals(747, SettingsSplitMetrics.pageContentWidthDp(1280))
  }

  @Test fun aSinglePanePageGetsTheFullWidth() {
    // The Mini upright: 800 dp minus 2 × 28 dp, instead of 267 dp beside a 420 dp sidebar.
    assertEquals(744, SettingsSplitMetrics.pageContentWidthDp(800, singlePane = true))
    assertEquals(267, SettingsSplitMetrics.pageContentWidthDp(800))
    assertEquals(3, WallpaperSwatchLayout.columns(800, singlePane = true))
  }

  @Test fun pickingAPageFromTheListRevealsIt() {
    var stack = last
    val revealed = ArrayList<SettingsBackStack>()
    val shown = ArrayList<SettingsBackStack>()
    val actions = settingsHostActions({ stack }, { shown += it; stack = it }, { revealed += it; stack = it }) {}
    actions.select(SettingsPageIds.SOUNDS)
    actions.searchResult(ApprovedSettingsSearchResult("Sounds", SettingsPageIds.SOUNDS, control = "chime.volume"))
    actions.open(SettingsPageIds.ALPACA)
    assertEquals(3, revealed.size)
    actions.back()
    assertEquals("Back walks the stack without revealing", 1, shown.size)
  }
}
