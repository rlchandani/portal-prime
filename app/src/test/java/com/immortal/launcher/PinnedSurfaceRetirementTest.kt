/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */
package com.immortal.launcher

import org.junit.Assert.*
import org.junit.Test

class PinnedSurfaceRetirementTest {
    private class Surface(val name: String) {
        var attached = true
        var visible = true
        var removalCalls = 0
        var stopCalls = 0
        var failures = 0
        var asyncRemoval = false
    }
    private class Windows {
        val events = mutableListOf<String>()
        val retirement = PinnedSurfaceRetirement<Surface>(
            hide = { it.visible = false; events += "hide:${it.name}" },
            remove = {
                it.removalCalls++
                events += "remove:${it.name}"
                if (it.failures-- > 0) throw IllegalStateException("Window removal failed")
                if (!it.asyncRemoval) it.attached = false
            },
            attached = { it.attached },
            attemptLimit = 3,
            retainedLimit = 3,
        )
        fun renderer(surface: Surface) = PinnedRendererLifetime {
            surface.stopCalls++
            events += "stop:${surface.name}"
        }
    }

    @Test fun hidePrecedesDeferredStopAndRemoval() {
        val windows = Windows()
        val surface = Surface("old")
        val ticket = windows.retirement.retire(surface, windows.renderer(surface))
        assertEquals(listOf("hide:old"), windows.events)
        assertEquals(1, windows.retirement.pendingCount)
        assertEquals(PinnedRetirementResult.DONE, windows.retirement.attempt(ticket))
        assertEquals(listOf("hide:old", "stop:old", "remove:old"), windows.events)
        assertFalse(surface.attached)
        assertEquals(0, windows.retirement.pendingCount)
    }

    @Test fun failedRemovalRetainsOwnershipAndStopsTheRendererExactlyOnce() {
        val windows = Windows()
        val surface = Surface("old").apply { failures = 1 }
        val ticket = windows.retirement.retire(surface, windows.renderer(surface))
        assertEquals(PinnedRetirementResult.RETRY, windows.retirement.attempt(ticket))
        assertFalse(surface.visible)
        assertTrue(surface.attached)
        assertEquals(1, windows.retirement.pendingCount)
        assertEquals(1, surface.stopCalls)
        assertEquals(PinnedRetirementResult.DONE, windows.retirement.attempt(ticket))
        assertFalse(surface.attached)
        assertEquals(1, surface.stopCalls)
        assertEquals(0, windows.retirement.pendingCount)
    }

    @Test fun persistentFailureHasABoundedAttemptBudgetWithoutPretendingCleanupFinished() {
        val windows = Windows()
        val surface = Surface("old").apply { failures = 100 }
        val ticket = windows.retirement.retire(surface, windows.renderer(surface))
        repeat(20) { windows.retirement.attempt(ticket) }
        assertEquals(3, surface.removalCalls)
        assertEquals(1, surface.stopCalls)
        assertTrue(surface.attached)
        assertEquals(1, windows.retirement.pendingCount)
        assertEquals(1, windows.retirement.failedCount)
        assertFalse(windows.retirement.canCreateCover)
        assertEquals(PinnedRetirementResult.FAILED, windows.retirement.attempt(ticket))
    }

    @Test fun delayedOldCleanupCannotStopOrRemoveANewerCover() {
        val windows = Windows()
        val old = Surface("old").apply { failures = 1 }
        val newer = Surface("new")
        val oldTicket = windows.retirement.retire(old, windows.renderer(old))
        assertEquals(PinnedRetirementResult.RETRY, windows.retirement.attempt(oldTicket))
        assertTrue(windows.retirement.canCreateCover)
        assertEquals(PinnedRetirementResult.DONE, windows.retirement.attempt(oldTicket))
        assertTrue(newer.attached)
        assertTrue(newer.visible)
        assertEquals(0, newer.removalCalls)
        assertEquals(0, newer.stopCalls)
        assertEquals(PinnedRetirementResult.IGNORED, windows.retirement.attempt(oldTicket))
        assertEquals(2, old.removalCalls)
    }

    @Test fun asynchronousSuccessfulRemovalIsTrackedUntilAnActualDetach() {
        val windows = Windows()
        val surface = Surface("old").apply { asyncRemoval = true }
        val ticket = windows.retirement.retire(surface, windows.renderer(surface))
        assertEquals(PinnedRetirementResult.RETRY, windows.retirement.attempt(ticket))
        assertEquals(1, windows.retirement.pendingCount)
        assertFalse(windows.retirement.detached(ticket))
        surface.attached = false
        assertTrue(windows.retirement.detached(ticket))
        assertEquals(0, windows.retirement.pendingCount)
        assertEquals(PinnedRetirementResult.IGNORED, windows.retirement.attempt(ticket))
        assertEquals(1, surface.stopCalls)
    }

    @Test fun aRemovalExceptionCannotConsumeOwnershipFromAnUncertainAttachProbe() {
        val windows = Windows()
        val surface = Surface("old").apply { attached = false; failures = 100 }
        val ticket = windows.retirement.retire(surface, windows.renderer(surface))
        assertEquals(PinnedRetirementResult.RETRY, windows.retirement.attempt(ticket))
        assertEquals(1, windows.retirement.pendingCount)
        assertTrue(windows.retirement.owns(ticket))
    }

    @Test fun repeatedRetirementAdmissionDoesNotReplaceTheOldOwnersStopCallback() {
        val windows = Windows()
        val surface = Surface("old")
        val renderer = windows.renderer(surface)
        val ticket = windows.retirement.retire(surface, renderer)
        val duplicate = windows.retirement.retire(surface, PinnedRendererLifetime { fail("Wrong owner") })
        assertSame(ticket, duplicate)
        assertEquals(PinnedRetirementResult.DONE, windows.retirement.attempt(ticket))
        assertEquals(1, surface.stopCalls)
    }

    @Test fun lostPanelStopAndLaterRetirementShareOneControllerLifetime() {
        val windows = Windows()
        val surface = Surface("old").apply { failures = 1 }
        val renderer = windows.renderer(surface)
        renderer.stopOnce() // installing the lost panel closes its existing renderer
        val ticket = windows.retirement.retire(surface, renderer)
        windows.retirement.attempt(ticket)
        windows.retirement.attempt(ticket)
        assertEquals(1, surface.stopCalls)
        assertFalse(surface.attached)
    }

    @Test fun throwingStopStillAllowsWindowRemovalAndIsNotRetried() {
        val windows = Windows()
        val surface = Surface("old")
        val renderer = PinnedRendererLifetime { surface.stopCalls++; throw IllegalStateException() }
        val ticket = windows.retirement.retire(surface, renderer)
        assertEquals(PinnedRetirementResult.DONE, windows.retirement.attempt(ticket))
        renderer.stopOnce()
        assertEquals(1, surface.stopCalls)
        assertTrue(renderer.failed)
        assertFalse(surface.attached)
    }

    @Test fun serviceCloseGetsOnlyOneFinalAttemptPerFailedOldWindow() {
        val windows = Windows()
        val surface = Surface("old").apply { failures = 100 }
        val ticket = windows.retirement.retire(surface, windows.renderer(surface))
        repeat(3) { windows.retirement.attempt(ticket) }
        repeat(20) { windows.retirement.drainOnce(ticket) }
        assertEquals(4, surface.removalCalls)
        assertEquals(1, surface.stopCalls)
        assertEquals(1, windows.retirement.pendingCount)
        surface.attached = false // Android tears down the unbound service's window
        assertTrue(windows.retirement.detached(ticket))
        assertEquals(0, windows.retirement.pendingCount)
    }

    @Test fun rapidRetirementCannotAdmitUnboundedAdditionalCovers() {
        val windows = Windows()
        repeat(3) { index ->
            val surface = Surface("old-$index")
            windows.retirement.retire(surface, windows.renderer(surface))
        }
        assertEquals(3, windows.retirement.pendingCount)
        assertFalse(windows.retirement.canCreateCover)
    }

    @Test fun actualDetachDuringWindowRemovalConsumesOnlyItsCapturedOwner() {
        val surface = Surface("old")
        lateinit var ticket: PinnedSurfaceRetirement.Ticket<Surface>
        lateinit var retirement: PinnedSurfaceRetirement<Surface>
        retirement = PinnedSurfaceRetirement(
            hide = { it.visible = false },
            remove = {
                it.removalCalls++
                it.attached = false
                assertTrue(retirement.detached(ticket)) // WindowManager's reentrant detach event
            },
            attached = { it.attached },
        )
        ticket = retirement.retire(surface, PinnedRendererLifetime { surface.stopCalls++ })
        assertEquals(PinnedRetirementResult.DONE, retirement.attempt(ticket))
        assertEquals(1, surface.stopCalls)
        assertEquals(1, surface.removalCalls)
        assertEquals(0, retirement.pendingCount)
        assertFalse(retirement.detached(ticket))
    }

    @Test fun anUnreadableAttachProbeCannotPretendARegisteredWindowWasRemoved() {
        val surface = Surface("old")
        val retirement = PinnedSurfaceRetirement<Surface>(
            hide = { it.visible = false },
            remove = { it.removalCalls++ },
            attached = { throw IllegalStateException("Attach state unavailable") },
        )
        val ticket = retirement.retire(surface, PinnedRendererLifetime { surface.stopCalls++ })
        repeat(20) { retirement.attempt(ticket) }
        assertEquals(3, surface.removalCalls)
        assertEquals(1, surface.stopCalls)
        assertEquals(1, retirement.pendingCount)
        assertEquals(1, retirement.failedCount)
        assertFalse(retirement.canCreateCover)
    }

}
