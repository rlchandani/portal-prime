/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */
package com.immortal.launcher

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SettingsRevealPlanTest {
    private fun entry(key: String, kind: SettingsEntryKind, item: SettingsItem? = null) = SettingsPageEntry(key, kind, item)
    private val lede = entry("lede", SettingsEntryKind.LEDE)
    private val header = entry("header:0", SettingsEntryKind.HEADER)
    private val gap = entry("gap:0", SettingsEntryKind.GAP)
    private fun rows(n: Int) = entry("rows:$n", SettingsEntryKind.ROWS, SettingsDomainRows("screensaver", List(n) { "k$it" }))
    private val block = entry("block:CHIMES", SettingsEntryKind.BLOCK, SettingsBlockItem(SettingsBlock.CHIMES))

    @Test fun blocksNeverComposeInTheSwitchFrame() {
        assertEquals(SettingsRevealPlan.DEFERRED, SettingsRevealPlan.weight(block))
        assertEquals(emptySet<String>(), SettingsRevealPlan.immediateKeys(listOf(block, header), 0))
    }

    @Test fun rowsCostTwoTextsEachAndStopTheRunWhenOverBudget() {
        assertEquals(8, SettingsRevealPlan.weight(rows(4)))
        // Lede (3) + header (1) fit; 4 rows (8) would exceed 8, so the run stops there.
        val keys = SettingsRevealPlan.immediateKeys(listOf(lede, header, rows(4), header.copy(key = "header:1")), 0)
        assertEquals(setOf("lede", "header:0"), keys)
    }

    @Test fun nothingBelowAPendingEntryComposesFirst() {
        val keys = SettingsRevealPlan.immediateKeys(listOf(header, block, gap, header.copy(key = "after")), 0)
        assertEquals(setOf("header:0"), keys)
        assertFalse("after" in keys)
    }

    @Test fun gapsAreFreeAndTheBudgetIsInclusive() {
        val keys = SettingsRevealPlan.immediateKeys(listOf(lede, gap, rows(2), gap.copy(key = "gap:1")), 0)
        assertEquals(setOf("lede", "gap:0", "rows:2", "gap:1"), keys)
        assertEquals(7, SettingsRevealPlan.weight(lede) + SettingsRevealPlan.weight(rows(2)))
    }

    @Test fun planStartsAtTheFirstVisibleEntry() {
        val entries = listOf(block, header, rows(1), header.copy(key = "header:2"))
        assertEquals(setOf("header:0", "rows:1", "header:2"), SettingsRevealPlan.immediateKeys(entries, 1))
        assertEquals(emptySet<String>(), SettingsRevealPlan.immediateKeys(entries, entries.size))
        assertEquals(emptySet<String>(), SettingsRevealPlan.immediateKeys(entries, 0))
    }

    @Test fun placeholdersApproximateTheirEntries() {
        assertEquals(400, SettingsRevealPlan.placeholderDp(rows(4)))
        assertTrue(SettingsRevealPlan.placeholderDp(block) > SettingsRevealPlan.placeholderDp(header))
    }

    @Test fun everyRealPageOpensWithinTheFirstFrameBudget() {
        SettingsTree.pages.forEach { page ->
            val entries = settingsPageLayout(page.id, advanced = true, screensaverOn = true).entries
            val keys = SettingsRevealPlan.immediateKeys(entries, 0)
            val spent = entries.filter { it.key in keys }.sumOf { SettingsRevealPlan.weight(it) }
            assertTrue("${page.id} spends $spent", spent <= SettingsRevealPlan.FIRST_FRAME_TEXT_BUDGET)
            assertTrue("${page.id} composes a block in the switch frame",
                entries.none { it.key in keys && it.kind == SettingsEntryKind.BLOCK })
        }
    }
}
