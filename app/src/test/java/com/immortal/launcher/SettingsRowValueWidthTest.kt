/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */
package com.immortal.launcher

import org.junit.Assert.assertEquals
import org.junit.Test

class SettingsRowValueWidthTest {
    @Test fun valueKeepsAtMostFortyPercentOfTheCardSoTheTitleNeverWrapsWordByWord() {
        // Portal+ (1920 dp): cards are 1008 dp wide.
        assertEquals(403, settingsRowValueMaxWidthDp(1920, singlePane = false))
        // Mini upright (single pane, 744 dp cards) and turned (split, 747 dp cards).
        assertEquals(297, settingsRowValueMaxWidthDp(800, singlePane = true))
        assertEquals(298, settingsRowValueMaxWidthDp(1280, singlePane = false))
    }

    @Test fun unknownWidthFallsBackToTheFixedCap() {
        assertEquals(ROW_VALUE_MAX_DP, settingsRowValueMaxWidthDp(0, singlePane = true))
    }
}

class SettingsCompactCardsTest {
    /** Guest card preview and Photo Sources tiles stack on the Mini and sit side by side on Portal+. */
    @org.junit.Test fun sideBySideOnPortalPlusStackedOnTheMini() {
        org.junit.Assert.assertFalse(SettingsSplitMetrics.compactCards(SettingsSplitMetrics.pageContentWidthDp(1920)))
        org.junit.Assert.assertTrue(SettingsSplitMetrics.compactCards(SettingsSplitMetrics.pageContentWidthDp(800, singlePane = true)))
        org.junit.Assert.assertTrue(SettingsSplitMetrics.compactCards(SettingsSplitMetrics.pageContentWidthDp(1280)))
        org.junit.Assert.assertFalse(SettingsSplitMetrics.compactCards(0))
    }
}

class SettingsBackLabelTest {
    @org.junit.Test fun parentNameWhenItFitsElseBack() {
        // Mini upright: the back button has 30% of 760 dp.
        org.junit.Assert.assertEquals("Settings", settingsBackLabel("Settings", 228))
        org.junit.Assert.assertEquals("Back", settingsBackLabel("Widgets & Calendar", 228))
        // Portal+: 40% of the detail column fits the longest parent name.
        org.junit.Assert.assertEquals("People & Recognition", settingsBackLabel("People & Recognition", 518))
        org.junit.Assert.assertEquals("Back", settingsBackLabel(null, 518))
    }
}

class SettingsSidebarCompactTest {
    @org.junit.Test fun onlyTheMinisTurnedSplitViewUsesCompactSidebarRows() {
        org.junit.Assert.assertFalse(SettingsSplitMetrics.sidebarCompact(1920, singlePane = false))
        org.junit.Assert.assertTrue(SettingsSplitMetrics.sidebarCompact(1280, singlePane = false))
        // Upright the list is the whole window, so its rows keep the full size.
        org.junit.Assert.assertFalse(SettingsSplitMetrics.sidebarCompact(800, singlePane = true))
    }
}
