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

/** The Alerts side cards: one-line titles in the narrowest card, preview chips in an even grid. */
class ControlCenterAlertsLayoutTest {
  /** The Mini upright's full-width card: 800 dp / 0.85 canvas − 30 + 30 screen padding − 2 × 28. */
  private val uprightInner = 800 / CcCompactScale.PORTRAIT - 60.0 - 2 * CcAlertsLayout.CARD_PADDING

  @Test fun sectionLabelsFitOneLineOfTheSideCard() {
    for (label in listOf(CcAlertsLayout.PREVIEW_TITLE, CcAlertsLayout.BANNERS_TITLE)) {
      val w = CcAlertsLayout.SECTION_LABEL.width(label.uppercase())
      assertTrue("$label is $w of ${CcAlertsLayout.SIDE_INNER}", w <= CcAlertsLayout.SIDE_INNER)
    }
  }

  @Test fun theOldPreviewLabelIsWhatLeftHomeOnItsOwnLine() {
    // Guards the estimate itself: the shipped label measured wider than the card and wrapped.
    assertTrue(CcAlertsLayout.SECTION_LABEL.width("PREVIEW A BANNER ON PORTAL HOME") > CcAlertsLayout.SIDE_INNER)
    assertTrue(CcAlertsLayout.ROW_TITLE.width("Warnings — garage, doors, windows") >
        CcAlertsLayout.titleRoom(CcAlertsLayout.WARNINGS))
  }

  @Test fun bannerRowTitlesFitBesideTheirControl() {
    for (row in CcAlertsLayout.rows) {
      val w = CcAlertsLayout.ROW_TITLE.width(row.title)
      assertTrue("${row.title} is $w of ${CcAlertsLayout.titleRoom(row)}", w <= CcAlertsLayout.titleRoom(row))
    }
  }

  @Test fun previewChipsShareOneWidthPerRow() {
    val labels = CcBannerPreview.samples.map { it.label }
    // Too narrow for three across: one full-width column, never 2 + 1.
    assertEquals(1, CcAlertsLayout.chipColumns(labels, CcAlertsLayout.SIDE_INNER))
    assertEquals(1, CcAlertsLayout.chipColumns(labels, uprightInner))
    assertEquals(1, CcAlertsLayout.chipColumns(labels, CcAlertsLayout.SIDE_INNER, fontScale = 1.15f))
    // A wide card puts all three across.
    assertEquals(3, CcAlertsLayout.chipColumns(labels, 1200.0))
  }

  @Test fun chipColumnsOnlyPickFullRows() {
    val four = listOf("Aa", "Bb", "Cc", "Dd")
    val one = CcAlertsLayout.CHIP.width("Aa")
    assertEquals(4, CcAlertsLayout.chipColumns(four, 4 * one + 3 * CcAlertsLayout.CHIP_GAP))
    assertEquals("three would leave one alone", 2, CcAlertsLayout.chipColumns(four, 3 * one + 2 * CcAlertsLayout.CHIP_GAP))
    assertEquals(1, CcAlertsLayout.chipColumns(four, one))
    assertEquals(1, CcAlertsLayout.chipColumns(emptyList(), 100.0))
  }
}
