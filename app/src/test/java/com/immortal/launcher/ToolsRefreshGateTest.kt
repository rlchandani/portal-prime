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

class ToolsRefreshGateTest {
    @Test fun initialRevisionAllowsWarmCacheReuse() {
        val gate = ToolsRefreshGate()

        assertFalse(gate.isPending(0))
        assertTrue(gate.isPending(1))
    }

    @Test fun completedManualRefreshIsConsumedAcrossRepeatedWarmActivations() {
        val gate = ToolsRefreshGate()
        assertTrue(gate.isPending(1))

        gate.completed(1)

        repeat(5) {
            assertFalse(gate.isPending(1))
        }
    }

    @Test fun nextManualRefreshRemainsPendingUntilItsOwnCompletion() {
        val gate = ToolsRefreshGate()
        gate.completed(1)

        assertTrue(gate.isPending(2))
        assertFalse(gate.isPending(1))
        gate.completed(2)
        assertFalse(gate.isPending(2))
        assertTrue(gate.isPending(3))
    }

    @Test fun canceledOrUnacknowledgedRefreshRemainsPendingOnReactivation() {
        val gate = ToolsRefreshGate()
        gate.completed(1)

        // Cancellation or an unusable result does not call completed.
        repeat(5) {
            assertTrue(gate.isPending(2))
        }
        assertFalse(gate.isPending(1))

        gate.completed(2)
        assertFalse(gate.isPending(2))
    }

    @Test fun olderCompletionBeforeNewerCompletionDoesNotConsumeNewerRequest() {
        val gate = ToolsRefreshGate()
        assertTrue(gate.isPending(2))

        gate.completed(1)

        assertFalse(gate.isPending(1))
        assertTrue(gate.isPending(2))
        gate.completed(2)
        assertFalse(gate.isPending(2))
    }

    @Test fun lateOlderCompletionsCannotRegressCompletedRevision() {
        val gate = ToolsRefreshGate()
        gate.completed(3)

        gate.completed(1)
        gate.completed(0)
        gate.completed(3)

        assertFalse(gate.isPending(0))
        assertFalse(gate.isPending(1))
        assertFalse(gate.isPending(2))
        assertFalse(gate.isPending(3))
        assertTrue(gate.isPending(4))
    }

    @Test fun maximumIntRevisionCanBeConsumedWithoutOverflow() {
        val gate = ToolsRefreshGate()
        assertTrue(gate.isPending(Int.MAX_VALUE))

        gate.completed(Int.MAX_VALUE)
        gate.completed(Int.MAX_VALUE - 1)

        assertFalse(gate.isPending(Int.MAX_VALUE))
        assertFalse(gate.isPending(Int.MAX_VALUE - 1))
    }

    @Test(expected = IllegalArgumentException::class)
    fun negativePendingRevisionIsRejected() {
        ToolsRefreshGate().isPending(-1)
    }

    @Test(expected = IllegalArgumentException::class)
    fun negativeCompletedRevisionIsRejected() {
        ToolsRefreshGate().completed(-1)
    }
}
