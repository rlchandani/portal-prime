/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */
package com.immortal.launcher

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * The host builds its navigation callbacks once so their identity survives a page switch (sidebar
 * rows then skip recomposition). They must still act on the stack current when they run.
 */
class SettingsHostActionsTest {
    private var stack = SettingsBackStack.of(SettingsPageIds.DISPLAY)
    private val shown = ArrayList<SettingsBackStack>()
    private var homes = 0
    private val actions = settingsHostActions({ stack }, { shown += it; stack = it }) { homes++ }

    @Test fun selectReplacesTheLatestStack() {
        stack = SettingsBackStack.of(SettingsPageIds.ALPACA)
        actions.select(SettingsPageIds.SOUNDS)
        assertEquals(listOf(SettingsPageIds.SOUNDS), shown.single().pages)
    }

    @Test fun openAndBackUseTheStackAtCallTimeNotAtCreation() {
        actions.select(SettingsPageIds.WIDGETS)
        actions.open(SettingsPageIds.STOCKS)
        actions.open(SettingsPageIds.ALPACA)
        assertEquals(listOf(SettingsPageIds.WIDGETS, SettingsPageIds.STOCKS, SettingsPageIds.ALPACA), stack.pages)
        actions.back()
        assertEquals(listOf(SettingsPageIds.WIDGETS, SettingsPageIds.STOCKS), stack.pages)
    }

    @Test fun searchResultOpensItsPageWithTheControlToHighlight() {
        actions.searchResult(ApprovedSettingsSearchResult("Sounds", SettingsPageIds.SOUNDS, control = "chime.volume"))
        assertEquals(listOf(SettingsPageIds.SOUNDS), stack.pages)
        assertEquals("chime.volume", stack.control)
    }

    @Test fun backAtTheRootLeavesTheStackAsIs() {
        actions.back()
        assertEquals(listOf(SettingsPageIds.DISPLAY), stack.pages)
        assertNull(stack.control)
    }

    @Test fun homeIsForwarded() {
        actions.home()
        assertEquals(1, homes)
    }
}
