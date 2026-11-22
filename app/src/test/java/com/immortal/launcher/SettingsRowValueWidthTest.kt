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
