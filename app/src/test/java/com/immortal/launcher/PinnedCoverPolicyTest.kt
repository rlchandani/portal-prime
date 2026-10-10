/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */
package com.immortal.launcher

import org.junit.Assert.*
import org.junit.Test

class PinnedCoverPolicyTest {
    private val app = PinnedWindowIdentity("org.chromium.chrome", 17)
    private val replacement = app.copy(windowId = 23)
    private val home = PinnedWindowIdentity("com.immortal.launcher", 4)
    private val stockHome = PinnedWindowIdentity("com.facebook.portal.launcher", 5)
    private fun pinned() = PinnedCoverPolicy().also { assertTrue(it.pin(app)) }

    @Test fun continuityLossWithoutATargetKeepsTheRuntimeGuardUntilExplicitRepin() {
        val policy = PinnedCoverPolicy()
        policy.expire(app)
        policy.observe(listOf(replacement), replacement, covered = false)
        assertTrue(policy.pinned)
        assertTrue(policy.lost)
        assertNull(policy.target)
        assertEquals(PinnedRevealDecision.LOST, policy.reveal(listOf(replacement), 100, 300))
        assertFalse(policy.pin(replacement.copy(windowId = -1)))
        assertTrue(policy.lost)
        assertTrue(policy.pin(replacement))
        assertFalse(policy.lost)
        assertEquals(PinnedRevealDecision.REVEAL, policy.reveal(listOf(replacement), 100, 300))
    }

    @Test fun dayNightSelectionChangesBeforeAnExistingCoverCanBeReused() {
        val policy = pinned()
        assertEquals(PinnedCoverMode.DAY, policy.select(overnight = false, nightEnabled = true)?.mode)
        assertEquals(PinnedCoverMode.NIGHT, policy.select(overnight = true, nightEnabled = true)?.mode)
        assertEquals(PinnedCoverMode.DAY, policy.select(overnight = false, nightEnabled = true)?.mode)
        assertNull(policy.select(overnight = true, nightEnabled = false))
    }

    @Test fun morningAndDarkWindowCannotDropAKnownLostSessionBarrier() {
        val policy = pinned()
        policy.observe(listOf(replacement), replacement, covered = true)
        assertTrue(policy.lost)
        assertTrue(policy.select(overnight = true, nightEnabled = true)!!.lostBarrier)
        val morning = policy.select(overnight = false, nightEnabled = true)!!
        assertEquals(PinnedCoverMode.DAY, morning.mode)
        assertTrue(morning.lostBarrier)
        assertTrue(policy.select(overnight = true, nightEnabled = false)!!.lostBarrier)
        assertEquals(PinnedRevealDecision.LOST, policy.reveal(listOf(app), 500, 0))
    }

    @Test fun physicalHomeReleasesTheCurtainEvenWhileTheOriginalAppStillExists() {
        val policy = pinned()
        assertTrue(policy.observe(listOf(app, home), home, covered = true))
        assertFalse(policy.pinned)
        assertNull(policy.select(overnight = false, nightEnabled = false))
    }

    @Test fun coveredForeignApplicationTransitionReleasesTheOldPin() {
        val policy = pinned()
        val other = PinnedWindowIdentity("example.music", 18)
        assertTrue(policy.observe(listOf(app, other), other, covered = true))
        assertFalse(policy.pinned)
    }

    @Test fun uncoveredStockHomeIsNavigationEvenThoughItIsIneligibleForPinning() {
        val policy = pinned()
        assertTrue(policy.observe(listOf(app, stockHome), stockHome, covered = false))
        assertFalse(policy.pinned)
    }

    @Test fun systemUiAndNonApplicationDreamWavesDoNotReplaceThePinnedApp() {
        val policy = pinned()
        val systemUi = PinnedWindowIdentity("com.android.systemui", 9)
        assertFalse(policy.observe(emptyList(), systemUi, covered = true))
        assertFalse(policy.observe(emptyList(), null, covered = true))
        assertEquals(app, policy.target)
        assertFalse(policy.lost)
        assertEquals(PinnedRevealDecision.REVEAL, policy.reveal(listOf(app), 150, 200))
    }

    @Test fun nativeDetachMetadataCanSettleWithinTheSingleBoundedRevealWindow() {
        val policy = pinned()
        val revision = policy.revision
        assertFalse(policy.observe(emptyList(), null, covered = true))
        assertEquals(PinnedRevealDecision.WAIT, policy.reveal(emptyList(), 100, 200))
        assertFalse(policy.lost)
        assertEquals(revision, policy.revision) // a settling wave cannot invalidate the one pending reveal
        assertFalse(policy.observe(listOf(app), app, covered = true))
        assertEquals(PinnedRevealDecision.REVEAL, policy.reveal(listOf(app), 160, 200, revision))
        assertFalse(policy.lost)
    }

    @Test fun graceExpiryFailsClosedAndCannotResurrectAnUncertainWindow() {
        val policy = pinned()
        assertEquals(PinnedRevealDecision.WAIT, policy.reveal(emptyList(), 199, 200))
        assertEquals(PinnedRevealDecision.LOST, policy.reveal(emptyList(), 200, 200))
        assertTrue(policy.pinned)
        assertEquals(PinnedRevealDecision.LOST, policy.reveal(listOf(app), 201, 200))
    }

    @Test fun aKnownReplacementNeverReceivesTheNativeDetachGrace() {
        val policy = pinned()
        assertEquals(PinnedRevealDecision.LOST, policy.reveal(listOf(replacement), 100, 200))
        assertEquals(app, policy.target)
        assertTrue(policy.lost)
    }

    @Test fun delayedRevealCannotApplyToARepinnedSessionWithTheSameWindowId() {
        val policy = pinned()
        val oldRevision = policy.revision
        policy.release()
        assertTrue(policy.pin(app))
        assertEquals(PinnedRevealDecision.OBSOLETE,
            policy.reveal(listOf(app), 100, 200, expectedRevision = oldRevision))
        assertEquals(PinnedRevealDecision.REVEAL, policy.reveal(listOf(app), 100, 200))
    }

    @Test fun expiredGuardAllowsALaterRealHomeTransitionButNotTheInitialReplacement() {
        val policy = PinnedCoverPolicy()
        policy.expire(replacement)
        assertFalse(policy.observe(listOf(replacement), replacement, covered = true))
        assertTrue(policy.pinned)
        assertTrue(policy.observe(listOf(home), home, covered = true))
        assertFalse(policy.pinned)
        assertFalse(policy.lost)
    }

    @Test fun batteryPowerReconciliationPreservesPortalPlusAndBatteryHardwarePolicies() {
        assertTrue(PinnedCoverPolicy.keepScreenOn(PinnedCoverMode.DAY, FrameMode.ALWAYS_ON,
            hasBattery = false, batterySaver = true, powered = false))
        assertFalse(PinnedCoverPolicy.keepScreenOn(PinnedCoverMode.DAY, FrameMode.ALWAYS_ON,
            hasBattery = true, batterySaver = true, powered = false))
        assertTrue(PinnedCoverPolicy.keepScreenOn(PinnedCoverMode.DAY, FrameMode.ALWAYS_ON,
            hasBattery = true, batterySaver = true, powered = true))
        assertTrue(PinnedCoverPolicy.keepScreenOn(PinnedCoverMode.DAY, FrameMode.ALWAYS_ON,
            hasBattery = true, batterySaver = false, powered = false))
        assertFalse(PinnedCoverPolicy.keepScreenOn(PinnedCoverMode.DAY, FrameMode.PRESENCE,
            hasBattery = false, batterySaver = false, powered = true))
        assertTrue(PinnedCoverPolicy.keepScreenOn(PinnedCoverMode.NIGHT, FrameMode.PRESENCE,
            hasBattery = true, batterySaver = true, powered = false))
    }
}
