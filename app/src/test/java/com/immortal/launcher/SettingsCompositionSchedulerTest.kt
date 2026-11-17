/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */
package com.immortal.launcher

import androidx.compose.runtime.MonotonicFrameClock
import androidx.compose.ui.layout.SubcomposeLayoutState
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SettingsCompositionSchedulerTest {
    private class FrameCounter : MonotonicFrameClock {
        var frames = 0
        override suspend fun <R> withFrameNanos(onFrame: (Long) -> R): R {
            frames++
            return onFrame(frames * FRAME_NANOS)
        }
    }

    private fun task(onReady: () -> Unit) = SettingsCompositionScheduler.Task(SubcomposeLayoutState(), {}, onReady)

    @Test fun anEmptyPageSettlesAfterTwoIdleFrames() = runBlocking {
        val clock = FrameCounter()
        val scheduler = SettingsCompositionScheduler()
        assertFalse(scheduler.isSettled)
        runBlocking(clock) { scheduler.run() }
        assertTrue(scheduler.isSettled)
        assertEquals(2, clock.frames)
        scheduler.awaitSettled() // completes without suspending forever
    }

    @Test fun slotsAddedAfterSettlingComposeAtOnce() = runBlocking {
        val scheduler = SettingsCompositionScheduler()
        runBlocking(FrameCounter()) { scheduler.run() }
        var ready = false
        scheduler.enqueue(task { ready = true })
        assertTrue(ready)
        assertEquals(0, scheduler.pending)
    }

    @Test fun aCancelledSlotLeavesTheQueue() = runBlocking {
        val scheduler = SettingsCompositionScheduler()
        var ready = false
        val queued = task { ready = true }
        scheduler.enqueue(queued)
        assertEquals(1, scheduler.pending)
        scheduler.cancel(queued)
        assertEquals(0, scheduler.pending)
        runBlocking(FrameCounter()) { scheduler.run() }
        assertFalse("A disposed slot is never revealed", ready)
    }

    private companion object {
        const val FRAME_NANOS = 16_666_667L
    }
}
