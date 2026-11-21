/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */
package com.immortal.launcher

import androidx.compose.ui.geometry.Size
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

/** Draw-time tab pieces: the icon lands where `Icon` / `Modifier.paint` put it ([ccFitCentered]). */
class ControlCenterSelectionTest {
  @Test fun aSquareIconFillsItsBox() {
    assertEquals(CcPlacement(0f, 0f, 114f, 114f), ccFitCentered(Size(63f, 63f), Size(114f, 114f)))
  }

  @Test fun aWideIconFitsAndCentresVertically() {
    assertEquals(CcPlacement(0f, 25f, 100f, 50f), ccFitCentered(Size(40f, 20f), Size(100f, 100f)))
  }

  @Test fun anUnsizedPainterTakesTheBox() {
    assertEquals(CcPlacement(0f, 0f, 80f, 60f), ccFitCentered(Size.Unspecified, Size(80f, 60f)))
  }

  @Test fun anEmptyBoxDrawsNothing() {
    assertEquals(CcPlacement(0f, 0f, 0f, 0f), ccFitCentered(Size(24f, 24f), Size(0f, 40f)))
  }

  @Test fun leaveKidsIsNeverSelected() {
    assertFalse(CcNeverSelected.shown.value)
    assertFalse(CcNeverSelected.spoken.value)
  }

  /** The selected look and card are shown only by layer alpha, so a switch re-renders no texture. */
  @Test fun theSelectedLookIsShownOnlyWhileSelected() {
    assertEquals(1f, ccLookAlpha(selected = true), 0f)
    assertEquals(0f, ccLookAlpha(selected = false), 0f)
  }
}
