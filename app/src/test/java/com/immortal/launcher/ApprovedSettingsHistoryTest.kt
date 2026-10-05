/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */
package com.immortal.launcher

import org.junit.Assert.assertEquals
import org.junit.Test

class ApprovedSettingsHistoryTest {
    private val overview = ApprovedSettingsRoute(category = "photos")
    private val frame = ApprovedSettingsRoute("screensaver", "photos")
    private val sources = ApprovedSettingsRoute("sources", "photos")

    @Test fun nestedBackReturnsToParentRatherThanCategory() {
        val history = ApprovedSettingsHistory.initial(overview).navigate(frame).navigate(sources)
        assertEquals(frame, history.back().current)
        assertEquals(overview, history.back().back().current)
    }

    @Test fun directLaunchHasAnOverviewToReturnTo() {
        assertEquals(overview, ApprovedSettingsHistory.initial(sources).back().current)
    }

    @Test fun sidebarSelectionReplacesPreviousCategoryStack() {
        val sound = ApprovedSettingsRoute(category = "sound")
        val history = ApprovedSettingsHistory.initial(frame).navigate(sources).navigate(sound)
        assertEquals(listOf(sound), history.entries)
        assertEquals(sound, history.back().current)
    }

    @Test fun repeatedTapDoesNotCreateDuplicateBackSteps() {
        val history = ApprovedSettingsHistory.initial(frame).navigate(sources).navigate(sources)
        assertEquals(frame, history.back().current)
    }

    @Test fun searchHighlightReplacesSamePageWithoutAnotherBackStep() {
        val requested = frame.copy(control = "screensaver.enabled")
        val history = ApprovedSettingsHistory.initial(frame).navigate(requested)
        assertEquals(requested, history.current)
        assertEquals(overview, history.back().current)
    }
}
