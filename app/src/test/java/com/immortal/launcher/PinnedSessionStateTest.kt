/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */
package com.immortal.launcher

import org.junit.Assert.*
import org.junit.Test

class PinnedSessionStateTest {
    private val browser = PinnedWindowIdentity("org.chromium.chrome", 17)
    private val replacement = browser.copy(windowId = 23)
    private val other = PinnedWindowIdentity("example.music", 18)

    @Test fun pinCapturesOnlyAValidRuntimeIdentity() {
        val state = PinnedSessionState()
        assertFalse(state.pin(browser.copy(windowId = -1)))
        assertFalse(state.pin(browser.copy(packageName = "")))
        assertTrue(state.pin(browser))
        assertEquals(browser, state.target)
        assertFalse(state.lost)
    }
    @Test fun coveringAndRevealingTheSameWindowPreservesItsIdentity() {
        val state = PinnedSessionState(); state.pin(browser)
        state.observe(emptyList(), null, covered = true)
        assertEquals(browser, state.target)
        assertFalse(state.lost)
        assertTrue(state.canReveal(listOf(browser)))
        assertEquals(browser, state.target)
    }
    @Test fun coveredReplacementCannotBeTreatedAsThePinnedSession() {
        val state = PinnedSessionState(); state.pin(browser)
        state.observe(listOf(replacement), replacement, covered = true)
        assertTrue(state.lost)
        assertEquals(browser, state.target) // block fallback launch until deliberate exit
        assertFalse(state.canReveal(listOf(replacement)))
    }
    @Test fun absentWindowAtContinuationTapFailsClosedWithoutAReplacementLaunch() {
        val state = PinnedSessionState(); state.pin(browser)
        assertFalse(state.canReveal(emptyList()))
        assertTrue(state.lost)
        assertFalse(state.canReveal(listOf(browser))) // no resurrection of uncertain session
    }
    @Test fun extraWindowsDoNotInvalidateAStillPresentTarget() {
        val state = PinnedSessionState(); state.pin(browser)
        state.observe(listOf(browser, replacement), replacement, covered = true)
        assertFalse(state.lost)
        assertTrue(state.canReveal(listOf(browser, replacement)))
    }
    @Test fun explicitNavigationAwayReleasesUncoveredPin() {
        val state = PinnedSessionState(); state.pin(browser)
        state.observe(listOf(other), other, covered = false)
        assertNull(state.target)
        assertFalse(state.lost)
    }
    @Test fun coveredForeignWindowIsNotEvidenceOfUserNavigation() {
        val state = PinnedSessionState(); state.pin(browser)
        state.observe(listOf(other), other, covered = true)
        assertEquals(browser, state.target)
        assertFalse(state.lost)
    }
    @Test fun recreationWithoutCoverExpiresThePin() {
        val state = PinnedSessionState(); state.pin(browser)
        state.observe(listOf(replacement), replacement, covered = false)
        assertNull(state.target)
    }
    @Test fun releaseAndRepinClearPriorLoss() {
        val state = PinnedSessionState(); state.pin(browser)
        state.canReveal(emptyList()); state.clear()
        assertNull(state.target); assertFalse(state.lost)
        assertTrue(state.pin(replacement)); assertFalse(state.lost)
        assertTrue(state.canReveal(listOf(replacement)))
    }
    @Test fun initialMissingWindowDoesNotInventASelectionOrReturnTarget() {
        val state = PinnedSessionState()
        state.observe(listOf(browser), browser, covered = false)
        assertNull(state.target)
        assertFalse(state.canReveal(listOf(browser)))
        assertFalse(state.lost)
    }
}
