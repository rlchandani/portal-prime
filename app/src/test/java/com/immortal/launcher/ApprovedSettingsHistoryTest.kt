/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */
package com.immortal.launcher

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class ApprovedSettingsHistoryTest {
    private val overview = ApprovedSettingsRoute(category = "photos")
    private val frame = ApprovedSettingsRoute("screensaver", "photos")
    private val sources = ApprovedSettingsRoute("sources", "photos")

    @Test fun nestedBackReturnsToParentRatherThanCategory() {
        val history = ApprovedSettingsHistory.initial(overview).navigate(frame).navigate(sources)
        assertEquals(frame, history.back().current)
        assertEquals(overview, history.back().back().current)
        assertEquals(history.entries, history.back().back().entries)
        assertEquals(sources, history.back().back().forward().forward().current)
    }

    @Test fun directLaunchHasAnOverviewToReturnTo() {
        val history = ApprovedSettingsHistory.initial(sources)
        assertEquals(listOf(overview, sources), history.entries)
        assertEquals(overview, history.back().current)
        assertEquals(sources, history.back().forward().current)
    }

    @Test fun sidebarSelectionPreservesVisitsAcrossCategories() {
        val sound = ApprovedSettingsRoute(category = "sound")
        val chime = ApprovedSettingsRoute("chime", "sound")
        val history = ApprovedSettingsHistory.initial(frame).navigate(sources)
            .navigate(sound).navigate(chime)
        assertEquals(listOf(overview, frame, sources, sound, chime), history.entries)
        assertEquals(sound, history.back().current)
        assertEquals(sources, history.back().back().current)
        assertEquals(sound, history.back().back().forward().current)
        assertEquals(chime, history.back().back().forward().forward().current)
    }

    @Test fun repeatedTapDoesNotCreateDuplicateBackSteps() {
        val history = ApprovedSettingsHistory.initial(frame).navigate(sources)
        assertSame(history, history.navigate(sources))
        assertEquals(frame, history.back().current)
    }

    @Test fun repeatedCurrentRoutePreservesForwardHistory() {
        val history = ApprovedSettingsHistory.initial(frame).navigate(sources).back()
        assertSame(history, history.navigate(frame))
        assertTrue(history.navigate(frame).canGoForward)
        assertEquals(sources, history.navigate(frame).forward().current)
    }

    @Test fun searchHighlightReplacesSamePageWithoutAnotherBackStep() {
        val requested = frame.copy(control = "screensaver.enabled")
        val history = ApprovedSettingsHistory.initial(frame).navigate(requested)
        assertEquals(listOf(overview, requested), history.entries)
        assertEquals(1, history.position)
        assertEquals(requested, history.current)
        assertEquals(overview, history.back().current)
        assertEquals(requested, history.back().forward().current)
    }

    @Test fun searchHighlightAfterBackDiscardsForwardHistory() {
        val requested = frame.copy(control = "screensaver.enabled")
        val history = ApprovedSettingsHistory.initial(frame).navigate(sources).back()
            .navigate(requested)
        assertEquals(listOf(overview, requested), history.entries)
        assertEquals(1, history.position)
        assertFalse(history.canGoForward)
        assertSame(history, history.forward())
        assertEquals(overview, history.back().current)
    }

    @Test fun overviewHighlightAlsoReplacesCurrentVisit() {
        val requested = overview.copy(control = "photos")
        val history = ApprovedSettingsHistory.initial(overview).navigate(frame).back()
            .navigate(requested)
        assertEquals(listOf(requested), history.entries)
        assertFalse(history.canGoBack)
        assertFalse(history.canGoForward)
    }

    @Test fun samePageInDifferentCategoryCreatesAnotherVisit() {
        val otherCategory = frame.copy(category = "sound", control = "screensaver.enabled")
        val history = ApprovedSettingsHistory.initial(frame).navigate(otherCategory)
        assertEquals(listOf(overview, frame, otherCategory), history.entries)
        assertEquals(frame, history.back().current)
    }

    @Test fun backAndForwardAtBoundariesReturnSameHistory() {
        val start = ApprovedSettingsHistory.initial(overview)
        assertFalse(start.canGoBack)
        assertFalse(start.canGoForward)
        assertSame(start, start.back())
        assertSame(start, start.forward())

        val end = start.navigate(frame).navigate(sources)
        assertTrue(end.canGoBack)
        assertFalse(end.canGoForward)
        assertSame(end, end.forward())
        val middle = end.back()
        assertTrue(middle.canGoBack)
        assertTrue(middle.canGoForward)
        val beginning = middle.back()
        assertFalse(beginning.canGoBack)
        assertTrue(beginning.canGoForward)
        assertSame(beginning, beginning.back())
        assertEquals(end, beginning.forward().forward())
    }

    @Test fun newDestinationAfterBackTruncatesForwardVisits() {
        val sound = ApprovedSettingsRoute(category = "sound")
        val history = ApprovedSettingsHistory.initial(frame).navigate(sources).navigate(sound)
            .back().back().navigate(ApprovedSettingsRoute("faces", "photos"))
        assertEquals(listOf(overview, frame, ApprovedSettingsRoute("faces", "photos")), history.entries)
        assertEquals(2, history.position)
        assertFalse(history.canGoForward)
        assertEquals(frame, history.back().current)
    }

    @Test fun navigatingToForwardDestinationStillCreatesANewBranch() {
        val sound = ApprovedSettingsRoute(category = "sound")
        val history = ApprovedSettingsHistory.initial(frame).navigate(sources).navigate(sound)
            .back().back().navigate(sources)
        assertEquals(listOf(overview, frame, sources), history.entries)
        assertFalse(history.canGoForward)
    }

    @Test fun navigationRetainsOnlyMostRecent32Visits() {
        val routes = (1..40).map { ApprovedSettingsRoute("page$it", "photos") }
        val history = routes.fold(ApprovedSettingsHistory.initial(overview)) { visited, route ->
            visited.navigate(route).also { assertTrue(it.entries.size <= 32) }
        }
        assertEquals(routes.takeLast(32), history.entries)
        assertEquals(31, history.position)
        assertEquals(routes.last(), history.current)
        val beginning = (1..31).fold(history) { visited, _ -> visited.back() }
        assertEquals(routes[8], beginning.current)
        assertSame(beginning, beginning.back())
        assertEquals(history, (1..31).fold(beginning) { visited, _ -> visited.forward() })
    }

    @Test fun branchingAtCapacityKeepsPastVisitsAndDropsForwardVisits() {
        val routes = (1..40).map { ApprovedSettingsRoute("page$it", "photos") }
        val end = routes.fold(ApprovedSettingsHistory.initial(overview)) { visited, route ->
            visited.navigate(route)
        }
        val history = end.back().back().navigate(sources)
        assertEquals(routes.takeLast(32).dropLast(2) + sources, history.entries)
        assertEquals(30, history.position)
        assertFalse(history.canGoForward)
    }

    @Test fun backAndForwardSkipGatedRoutesWithoutRemovingVisits() {
        val sound = ApprovedSettingsRoute(category = "sound")
        val chime = ApprovedSettingsRoute("chime", "sound")
        val history = ApprovedSettingsHistory.initial(frame).navigate(sources)
            .navigate(sound).navigate(chime)
        val allowed: (ApprovedSettingsRoute) -> Boolean = { it != frame && it != sound }
        val middle = history.back(allowed)
        assertEquals(sources, middle.current)
        assertEquals(2, middle.position)
        val beginning = middle.back(allowed)
        assertEquals(overview, beginning.current)
        assertEquals(0, beginning.position)
        assertEquals(history.entries, beginning.entries)
        assertEquals(middle, beginning.forward(allowed))
        assertEquals(history, middle.forward(allowed))
        assertEquals(frame, beginning.forward().current)
    }

    @Test fun noEligibleDestinationReturnsSameHistoryInEitherDirection() {
        val history = ApprovedSettingsHistory.initial(frame).navigate(sources).back()
        assertSame(history, history.back { false })
        assertSame(history, history.forward { false })
        assertEquals(1, history.position)
        assertEquals(listOf(overview, frame, sources), history.entries)
    }

    @Test fun entriesAndPositionPersistWhileBrowsingHistory() {
        val history = ApprovedSettingsHistory.initial(frame).navigate(sources).back()
        val restored = ApprovedSettingsHistory(history.entries.toList(), history.position)
        assertEquals(history, restored)
        assertEquals(frame, restored.current)
        assertTrue(restored.canGoBack)
        assertTrue(restored.canGoForward)
        assertEquals(sources, restored.forward().current)
        assertEquals(sources, ApprovedSettingsHistory(history.entries).current)
    }

    @Test(expected = IllegalArgumentException::class)
    fun emptyHistoryIsRejected() {
        ApprovedSettingsHistory(emptyList())
    }

    @Test(expected = IllegalArgumentException::class)
    fun negativePositionIsRejected() {
        ApprovedSettingsHistory(listOf(overview), -1)
    }

    @Test(expected = IllegalArgumentException::class)
    fun positionPastLastEntryIsRejected() {
        ApprovedSettingsHistory(listOf(overview), 1)
    }
}
