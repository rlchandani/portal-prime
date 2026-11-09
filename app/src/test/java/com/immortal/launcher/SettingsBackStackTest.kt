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
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class SettingsBackStackTest {
    @Test fun sidebarSelectionReplacesTheStackWithThatPage() {
        val deep = SettingsBackStack.of("alpaca")
        assertEquals(listOf("widgets", "w-stocks", "alpaca"), deep.pages)
        val selected = deep.select("sounds")
        assertEquals(listOf("sounds"), selected.pages)
        assertFalse(selected.canGoBack)
    }

    @Test fun linksPushAndBackWalksUp() {
        val stack = SettingsBackStack.of("widgets").open("w-stocks").open("alpaca")
        assertEquals(listOf("widgets", "w-stocks", "alpaca"), stack.pages)
        assertEquals("w-stocks", stack.parent)
        assertEquals(listOf("widgets", "w-stocks"), stack.back().pages)
        assertEquals(listOf("widgets"), stack.back().back().pages)
    }

    @Test fun neverMoreThanThreeDeep() {
        val full = SettingsBackStack.of("widgets").open("w-stocks").open("alpaca")
        val beyond = full.open("dismiss")
        assertTrue(beyond.pages.size <= SettingsBackStack.MAX_DEPTH)
        assertEquals(listOf("frame", "dismiss"), beyond.pages)
        try {
            SettingsBackStack(listOf("display", "sounds", "frame", "alarms"))
            throw AssertionError("A 4-deep stack must be rejected")
        } catch (_: IllegalArgumentException) { }
    }

    @Test fun crossLinksPushSoBackReturnsToTheCallingPage() {
        val stack = SettingsBackStack.of("frame").open("sources")
        assertEquals(listOf("frame", "sources"), stack.pages)
        assertEquals("frame", stack.back().current)
    }

    @Test fun openingAPageAlreadyInTheStackTruncatesInsteadOfRepeating() {
        val stack = SettingsBackStack.of("widgets").open("w-stocks").open("alpaca")
        assertEquals(listOf("widgets"), stack.open("widgets").pages)
        assertSame(stack, stack.open("not-a-page"))
    }

    @Test fun backAtTheTopIsANoOpSoThePlatformCanLeave() {
        val root = SettingsBackStack.of("display")
        assertSame(root, root.back())
        assertFalse(root.canGoBack)
    }

    @Test fun searchControlIsOneShot() {
        val stack = SettingsBackStack.of("night", "screensaver.overnightEnabled")
        assertEquals("screensaver.overnightEnabled", stack.control)
        assertNull(stack.withoutControl().control)
        assertEquals(stack.pages, stack.withoutControl().pages)
        assertNull(stack.open("w-calendar").control)
        assertNull(SettingsBackStack.of("w-weather", "immortal.weatherUnit").back().control)
    }

    @Test fun unknownPagesFallBackToTheDefault() {
        assertEquals(listOf(SettingsPageIds.DEFAULT), SettingsBackStack.of("gone").pages)
    }

    @Test fun restoreKeepsKnownPagesOnlyAndAtMostThree() {
        assertEquals(listOf("widgets", "w-stocks"), SettingsBackStack.restore(listOf("widgets", "gone", "w-stocks"))!!.pages)
        assertEquals(3, SettingsBackStack.restore(listOf("display", "sounds", "frame", "alarms"))!!.pages.size)
        assertNull(SettingsBackStack.restore(listOf("gone")))
        assertNull(SettingsBackStack.restore(null))
    }
}
