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
    @Test fun valueKeepsAtMostFortyPercentOfTheCardAndAThirdOnTheMiniSoTheTitleNeverWrapsWordByWord() {
        // Portal+ (1920 dp): cards are 1008 dp wide.
        assertEquals(403, settingsRowValueMaxWidthDp(1920, singlePane = false))
        // Mini upright (single pane, 744 dp cards) and turned (split, 747 dp cards).
        assertEquals(238, settingsRowValueMaxWidthDp(800, singlePane = true))
        assertEquals(239, settingsRowValueMaxWidthDp(1280, singlePane = false))
    }

    @Test fun aValueBesideAShortTitleTakesTheFreeRoomInsteadOfWrapping() {
        // Mini turned, Photo Sources › Selected: 691 px row, "Selected" 160 px, value 330 px, cap 239 px.
        assertEquals(330, settingsRowValueWidthPx(availablePx = 691, textIntrinsicPx = 160, valueIntrinsicPx = 330, capPx = 239))
    }

    @Test fun longHelpTextStillKeepsAllButTheCap() {
        // Photo Frame › Style: help is far wider than the row, so the value keeps the cap and wraps.
        assertEquals(239, settingsRowValueWidthPx(availablePx = 691, textIntrinsicPx = 2400, valueIntrinsicPx = 420, capPx = 239))
    }

    @Test fun theValueGetsWhateverTheTitleLeavesWhenThatIsMoreThanTheCap() {
        assertEquals(400, settingsRowValueWidthPx(availablePx = 700, textIntrinsicPx = 300, valueIntrinsicPx = 900, capPx = 239))
    }

    @Test fun aShortValueKeepsItsOwnWidthAndNoValueTakesNothing() {
        assertEquals(60, settingsRowValueWidthPx(availablePx = 700, textIntrinsicPx = 2400, valueIntrinsicPx = 60, capPx = 239))
        assertEquals(0, settingsRowValueWidthPx(availablePx = 700, textIntrinsicPx = 100, valueIntrinsicPx = 0, capPx = 239))
        assertEquals(0, settingsRowValueWidthPx(availablePx = 0, textIntrinsicPx = 100, valueIntrinsicPx = 50, capPx = 239))
    }

    @Test fun onlyTheMinisRowsWithHelpAndAValueStackTheHelpUnderBoth() {
        val miniCards = SettingsSplitMetrics.compactCards(SettingsSplitMetrics.pageContentWidthDp(800, singlePane = true))
        val portalCards = SettingsSplitMetrics.compactCards(SettingsSplitMetrics.pageContentWidthDp(1920))
        org.junit.Assert.assertTrue(settingsRowStacksHelp(miniCards, hasHelp = true, hasValue = true))
        org.junit.Assert.assertFalse(settingsRowStacksHelp(portalCards, hasHelp = true, hasValue = true))
        org.junit.Assert.assertFalse(settingsRowStacksHelp(miniCards, hasHelp = false, hasValue = true))
        org.junit.Assert.assertFalse(settingsRowStacksHelp(miniCards, hasHelp = true, hasValue = false))
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

class SettingsSinglePaneTitleTest {
    @org.junit.Test fun aShortBackLabelLeavesTheLongTitleItsWidth() {
        // Mini upright: 760 dp bar, 228 dp back cap. "Back" is 134 dp, so the title gets 476 dp.
        org.junit.Assert.assertEquals(476, settingsSinglePaneTitleMaxWidthDp(760, 228, "Back"))
        // "Advanced" (190 dp) still keeps the title clear of it on both sides.
        org.junit.Assert.assertEquals(364, settingsSinglePaneTitleMaxWidthDp(760, 228, "Advanced"))
    }

    @org.junit.Test fun aLongBackLabelUsesAtMostItsCapAndNoBackButtonNone() {
        org.junit.Assert.assertEquals(288, settingsSinglePaneTitleMaxWidthDp(760, 228, "People & Recognition"))
        org.junit.Assert.assertEquals(744, settingsSinglePaneTitleMaxWidthDp(760, 228, null))
    }
}
