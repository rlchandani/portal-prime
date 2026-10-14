/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */
package com.immortal.launcher

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class WebAppOpeningGateTest {
    @Test fun foregroundResumesBeforeFirstFrameCannotStartOpening() {
        val gate = WebAppOpeningGate()

        repeat(3) {
            assertFalse(gate.tryStart(foreground = true))
        }
        gate.frameCommitted()
        assertTrue(gate.tryStart(foreground = true))
    }

    @Test fun committedFrameAllowsExactlyOneForegroundStart() {
        val gate = WebAppOpeningGate()
        gate.frameCommitted()

        assertTrue(gate.tryStart(foreground = true))
        assertFalse(gate.tryStart(foreground = true))
    }

    @Test fun backgroundFrameCommitRemainsPendingUntilForegroundResume() {
        val gate = WebAppOpeningGate()
        gate.frameCommitted()

        repeat(3) {
            assertFalse(gate.tryStart(foreground = false))
        }
        assertTrue(gate.tryStart(foreground = true))
        assertFalse(gate.tryStart(foreground = true))
    }

    @Test fun backgroundAttemptsBeforeFrameCommitDoNotConsumeOpening() {
        val gate = WebAppOpeningGate()
        assertFalse(gate.tryStart(foreground = false))
        assertFalse(gate.tryStart(foreground = true))
        assertFalse(gate.tryStart(foreground = false))

        gate.frameCommitted()
        assertFalse(gate.tryStart(foreground = false))
        assertTrue(gate.tryStart(foreground = true))
    }

    @Test fun repeatedCommitsAndResumeCyclesCannotRestartOpening() {
        val gate = WebAppOpeningGate()
        gate.frameCommitted()
        gate.frameCommitted()
        assertTrue(gate.tryStart(foreground = true))

        repeat(3) {
            assertFalse(gate.tryStart(foreground = false))
            gate.frameCommitted()
            assertFalse(gate.tryStart(foreground = true))
        }
    }

    @Test fun destructionBeforeFrameCommitPermanentlyPreventsOpening() {
        val gate = WebAppOpeningGate()
        gate.destroy()
        assertFalse(gate.tryStart(foreground = true))

        gate.frameCommitted()
        gate.destroy()
        gate.frameCommitted()
        assertFalse(gate.tryStart(foreground = false))
        assertFalse(gate.tryStart(foreground = true))
    }

    @Test fun destructionAfterBackgroundCommitPreventsLaterForegroundOpening() {
        val gate = WebAppOpeningGate()
        gate.frameCommitted()
        assertFalse(gate.tryStart(foreground = false))

        gate.destroy()
        assertFalse(gate.tryStart(foreground = true))
        gate.frameCommitted()
        assertFalse(gate.tryStart(foreground = true))
    }

    @Test fun destructionAfterOpeningCannotPermitAnotherStart() {
        val gate = WebAppOpeningGate()
        gate.frameCommitted()
        assertTrue(gate.tryStart(foreground = true))

        gate.destroy()
        assertFalse(gate.tryStart(foreground = false))
        gate.frameCommitted()
        gate.destroy()
        assertFalse(gate.tryStart(foreground = true))
    }
}
