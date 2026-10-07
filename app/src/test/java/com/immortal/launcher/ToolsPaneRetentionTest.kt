/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */
package com.immortal.launcher

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class ToolsPaneRetentionTest {
    @Test fun fifthLocalPaneEvictsLeastRecentlyUsedInsteadOfCurrentSelection() {
        var panes = emptyList<String>()
        listOf("alarm", "timers", "countdowns", "converter").forEach {
            panes = ToolsPaneRetention.afterSelection(panes, it)
        }
        panes = ToolsPaneRetention.afterSelection(panes, "alarm")
        panes = ToolsPaneRetention.afterSelection(panes, "lamp")
        assertEquals(listOf("countdowns", "converter", "alarm", "lamp"), panes)
    }

    @Test fun audioAndNetworkPanesNeverSurviveAnotherSelection() {
        val locals = listOf("alarm", "timers", "countdowns", "lamp")
        listOf("notes", "intercom", "stories", "iss", "aurora", "speed", "camera").forEach { transient ->
            val opened = ToolsPaneRetention.afterSelection(locals, transient)
            assertEquals(locals + transient, opened)
            val returned = ToolsPaneRetention.afterSelection(opened, "lamp")
            assertEquals(locals, returned)
            assertFalse(transient in returned)
        }
    }

    @Test fun repeatedSelectionDoesNotDuplicatePanesOrGrowTheCache() {
        var panes = listOf("alarm", "converter", "countdowns", "lamp")
        repeat(100) {
            listOf("alarm", "converter", "countdowns", "lamp").forEach { id ->
                panes = ToolsPaneRetention.afterSelection(panes, id)
            }
        }
        assertEquals(listOf("alarm", "converter", "countdowns", "lamp"), panes)
    }
}
