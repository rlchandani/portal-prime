/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */
package com.immortal.launcher

import org.junit.Assert.assertEquals
import org.junit.Test

/** Switch-frame weights and placeholder heights for every entry kind, including items of the wrong type. */
class SettingsRevealPlanCoverageTest {
  private fun entry(kind: SettingsEntryKind, item: SettingsItem? = null, key: String = kind.name) = SettingsPageEntry(key, kind, item)
  private val rows3 = SettingsDomainRows("screensaver", listOf("a", "b", "c"))
  private val external = SettingsExternalItem("Wi-Fi", SettingsExternal.WIFI)

  @Test fun weightsForEveryKind() {
    assertEquals(3, SettingsRevealPlan.weight(entry(SettingsEntryKind.LEDE)))
    for (k in listOf(SettingsEntryKind.HEADER, SettingsEntryKind.FOOTER, SettingsEntryKind.NOTICE, SettingsEntryKind.LINK)) {
      assertEquals(k.name, 1, SettingsRevealPlan.weight(entry(k)))
    }
    assertEquals(2, SettingsRevealPlan.weight(entry(SettingsEntryKind.ADVANCED_HINT)))
    assertEquals(0, SettingsRevealPlan.weight(entry(SettingsEntryKind.GAP)))
    assertEquals(SettingsRevealPlan.DEFERRED, SettingsRevealPlan.weight(entry(SettingsEntryKind.BLOCK)))
  }

  @Test fun externalRowsCostMoreWithASubtitle() {
    assertEquals(1, SettingsRevealPlan.weight(entry(SettingsEntryKind.EXTERNAL, external)))
    assertEquals(2, SettingsRevealPlan.weight(entry(SettingsEntryKind.EXTERNAL, external.copy(subtitle = "Home network"))))
    // A missing or mistyped item is treated as a title-only row.
    assertEquals(1, SettingsRevealPlan.weight(entry(SettingsEntryKind.EXTERNAL)))
    assertEquals(1, SettingsRevealPlan.weight(entry(SettingsEntryKind.EXTERNAL, rows3)))
  }

  @Test fun rowsWithoutADomainItemCountAsOneRow() {
    assertEquals(6, SettingsRevealPlan.weight(entry(SettingsEntryKind.ROWS, rows3)))
    assertEquals(2, SettingsRevealPlan.weight(entry(SettingsEntryKind.ROWS)))
    assertEquals(2, SettingsRevealPlan.weight(entry(SettingsEntryKind.ROWS, external)))
    assertEquals(100, SettingsRevealPlan.placeholderDp(entry(SettingsEntryKind.ROWS)))
    assertEquals(300, SettingsRevealPlan.placeholderDp(entry(SettingsEntryKind.ROWS, rows3)))
  }

  @Test fun placeholderHeightsForEveryKind() {
    val expected = mapOf(
        SettingsEntryKind.LEDE to 150, SettingsEntryKind.HEADER to 38, SettingsEntryKind.FOOTER to 44,
        SettingsEntryKind.NOTICE to 110, SettingsEntryKind.LINK to 100, SettingsEntryKind.EXTERNAL to 100,
        SettingsEntryKind.ADVANCED_HINT to 110, SettingsEntryKind.GAP to 34, SettingsEntryKind.BLOCK to 420,
    )
    expected.forEach { (k, dp) -> assertEquals(k.name, dp, SettingsRevealPlan.placeholderDp(entry(k))) }
  }

  @Test fun negativeStartIsClampedAndACustomBudgetIsHonoured() {
    val entries = listOf(entry(SettingsEntryKind.HEADER, key = "h0"), entry(SettingsEntryKind.HEADER, key = "h1"),
        entry(SettingsEntryKind.HEADER, key = "h2"))
    assertEquals(setOf("h0", "h1", "h2"), SettingsRevealPlan.immediateKeys(entries, -4))
    assertEquals(setOf("h0", "h1"), SettingsRevealPlan.immediateKeys(entries, 0, budget = 2))
    assertEquals(emptySet<String>(), SettingsRevealPlan.immediateKeys(entries, 0, budget = 0))
    assertEquals(setOf("gap"), SettingsRevealPlan.immediateKeys(listOf(entry(SettingsEntryKind.GAP, key = "gap")), 0, budget = 0))
  }
}
