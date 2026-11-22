/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */
package com.immortal.launcher

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.compositeOver
import androidx.compose.ui.graphics.luminance
import com.immortal.launcher.ui.theme.ApprovedLightPalette
import org.junit.Assert.assertTrue
import org.junit.Test

class SettingsAdvancedBadgeContrastTest {
    private fun contrast(a: Color, b: Color): Double {
        val (hi, lo) = listOf(a.luminance().toDouble(), b.luminance().toDouble()).sortedDescending()
        return (hi + 0.05) / (lo + 0.05)
    }

    /** The ADV badge is text: it needs 4.5:1 on its amber wash over a white day-theme card. */
    @Test fun dayBadgeTextMeetsTextContrast() {
        val wash = AdvancedBadgeColor.copy(alpha = ADVANCED_BADGE_FILL_ALPHA).compositeOver(ApprovedLightPalette.surface)
        val ratio = contrast(AdvancedBadgeDayText, wash)
        assertTrue("ADV badge $ratio", ratio >= 4.5)
    }
}
