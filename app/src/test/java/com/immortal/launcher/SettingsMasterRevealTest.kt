/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */
package com.immortal.launcher

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SettingsMasterRevealTest {
    private val step = SettingsMasterRevealPlan.ENTRIES_PER_FRAME

    @Test fun eachFrameAddsAFewEntries() {
        assertEquals(step, SettingsMasterRevealPlan.next(0, 30, viewportFilled = false))
        assertEquals(2 * step, SettingsMasterRevealPlan.next(step, 30, viewportFilled = false))
    }

    @Test fun theLastStepStopsAtTheTotal() {
        assertEquals(10, SettingsMasterRevealPlan.next(8, 10, viewportFilled = false))
    }

    @Test fun aFullViewportOrEveryEntryCompletesTheReveal() {
        assertNull(SettingsMasterRevealPlan.next(8, 30, viewportFilled = true))
        assertNull(SettingsMasterRevealPlan.next(30, 30, viewportFilled = false))
    }

    @Test fun anEmptyListIsCompleteAtOnce() {
        assertNull(SettingsMasterRevealPlan.next(0, 0, viewportFilled = false))
    }

    @Test fun onlyTheOpeningMasterIsStaged() {
        val opening = SettingsMasterReveal(staged = true)
        assertFalse(opening.shown)
        assertFalse(opening.searchComposed)
        assertEquals(0, opening.listLimit)
        val later = SettingsMasterReveal(staged = false)
        assertTrue(later.shown)
        assertTrue(later.searchComposed)
        assertEquals(Int.MAX_VALUE, later.listLimit)
    }

    @Test fun theSidebarCountCoversTheProfileCardEveryGroupAndItsGap() {
        val expected = 1 + SettingsGroup.entries.sumOf {
            (if (it.title != null) 1 else 0) + SettingsTree.groupPages(it).size + 1
        }
        assertEquals(expected, settingsSidebarEntryCount())
        assertTrue(settingsSidebarEntryCount() > SettingsGroup.entries.size * 2)
    }
}
