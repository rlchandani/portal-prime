/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */
package com.immortal.launcher

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PortalAmbientExitSessionTest {
    @Test fun noRequestNeverReadsWindow() {
        val session = PortalAmbientExitSession()
        assertFalse(session.pending)
        assertBlocked(session, 1, true, 0)
    }

    @Test fun successfulDismissCompletesExactlyOnce() {
        val session = PortalAmbientExitSession()
        session.request(7, 100)
        assertTrue(session.pending)
        var clicks = 0
        assertTrue(session.attempt(7, true, 101) { clicks++; true })
        assertFalse(session.pending)
        assertFalse(session.attempt(7, true, 102) { clicks++; true })
        assertEquals(1, clicks)
    }

    @Test fun wrongCurrentTicketClearsWithoutReadingWindow() {
        val session = PortalAmbientExitSession()
        session.request(7, 100)
        assertBlocked(session, 8, true, 101)
        assertFalse(session.pending)
        assertBlocked(session, 7, true, 102)
    }

    @Test fun nonPortalHandoffClearsWithoutReadingWindow() {
        val session = PortalAmbientExitSession()
        session.request(7, 100)
        assertBlocked(session, 7, false, 101)
        assertFalse(session.pending)
        assertBlocked(session, 7, true, 102)
    }

    @Test fun defaultDeadlineRejectsExactlyThreeSecondsAndLater() {
        for (now in listOf(3_100L, 3_101L)) {
            val session = PortalAmbientExitSession()
            session.request(7, 100)
            assertBlocked(session, 7, true, now)
            assertFalse(session.pending)
        }
    }

    @Test fun immediatelyBeforeDeadlineStillAdmitsDismiss() {
        val session = PortalAmbientExitSession(windowMs = 10)
        session.request(7, 100)
        assertTrue(session.attempt(7, true, 109) { true })
        assertFalse(session.pending)
    }

    @Test fun nullWindowDoesNotConsumeAttempts() {
        val session = PortalAmbientExitSession(maxAttempts = 1)
        session.request(7, 100)
        repeat(20) {
            assertFalse(session.attempt(7, true, 101L + it) { null })
            assertTrue(session.pending)
        }
        assertTrue(session.attempt(7, true, 150) { true })
    }

    @Test fun nullWindowDoesNotExtendDeadline() {
        val session = PortalAmbientExitSession(windowMs = 10)
        session.request(7, 100)
        assertFalse(session.attempt(7, true, 109) { null })
        assertTrue(session.pending)
        assertBlocked(session, 7, true, 110)
        assertFalse(session.pending)
    }

    @Test fun thirdKnownFailureExhaustsDefaultBudget() {
        val session = PortalAmbientExitSession()
        session.request(7, 100)
        var clicks = 0
        repeat(2) {
            assertFalse(session.attempt(7, true, 101L + it) { clicks++; false })
            assertTrue(session.pending)
        }
        assertFalse(session.attempt(7, true, 103) { clicks++; false })
        assertFalse(session.pending)
        assertFalse(session.attempt(7, true, 104) { clicks++; true })
        assertEquals(3, clicks)
    }

    @Test fun successIsAllowedOnLastBudgetedAttempt() {
        val session = PortalAmbientExitSession(maxAttempts = 2)
        session.request(7, 100)
        assertFalse(session.attempt(7, true, 101) { false })
        assertTrue(session.attempt(7, true, 102) { true })
        assertFalse(session.pending)
    }

    @Test fun replacementResetsTicketDeadlineAndFailureBudget() {
        val session = PortalAmbientExitSession(windowMs = 10, maxAttempts = 2)
        session.request(7, 100)
        assertFalse(session.attempt(7, true, 101) { false })
        session.request(8, 109)
        assertFalse(session.attempt(8, true, 110) { false })
        assertTrue(session.pending)
        assertTrue(session.attempt(8, true, 118) { true })
    }

    @Test fun sameTicketReplacementResetsExhaustedSession() {
        val session = PortalAmbientExitSession(maxAttempts = 1)
        session.request(7, 100)
        assertFalse(session.attempt(7, true, 101) { false })
        session.request(7, 102)
        assertTrue(session.attempt(7, true, 103) { true })
    }

    @Test fun reentrantReplacementSurvivesEveryOldCallbackOutcome() {
        for (outcome in listOf(true, false, null)) {
            val session = PortalAmbientExitSession(windowMs = 10, maxAttempts = 1)
            session.request(7, 100)
            assertEquals(outcome == true, session.attempt(7, true, 109) {
                // Reuse the ticket: request identity must still distinguish ownership.
                session.request(7, 109)
                outcome
            })
            assertTrue(session.pending)
            assertTrue(session.attempt(7, true, 118) { true })
            assertFalse(session.pending)
        }
    }

    @Test fun reentrantDifferentTicketSurvivesOldSuccess() {
        val session = PortalAmbientExitSession()
        session.request(7, 100)
        assertTrue(session.attempt(7, true, 101) {
            session.request(8, 101)
            true
        })
        assertTrue(session.pending)
        assertTrue(session.attempt(8, true, 102) { true })
    }

    @Test fun callbackExceptionKeepsRequestAndDoesNotConsumeBudget() {
        val session = PortalAmbientExitSession(maxAttempts = 1)
        session.request(7, 100)
        repeat(4) {
            assertFalse(session.attempt(7, true, 101) { throw IllegalStateException("Window gone") })
            assertTrue(session.pending)
        }
        assertTrue(session.attempt(7, true, 102) { true })
    }

    @Test fun reentrantReplacementSurvivesOldCallbackException() {
        val session = PortalAmbientExitSession(maxAttempts = 1)
        session.request(7, 100)
        assertFalse(session.attempt(7, true, 101) {
            session.request(8, 101)
            throw IllegalStateException("Window gone")
        })
        assertTrue(session.pending)
        assertTrue(session.attempt(8, true, 102) { true })
    }

    @Test fun recursiveAttemptCannotDismissSameRequestTwice() {
        val session = PortalAmbientExitSession()
        session.request(7, 100)
        assertTrue(session.attempt(7, true, 101) {
            assertBlocked(session, 7, true, 101)
            true
        })
        assertFalse(session.pending)
    }
    private fun assertBlocked(
        session: PortalAmbientExitSession,
        ticket: Long,
        portalHomeHandoff: Boolean,
        nowMs: Long,
    ) {
        var windowReads = 0
        assertFalse(session.attempt(ticket, portalHomeHandoff, nowMs) {
            windowReads++
            true
        })
        assertEquals("Rejected attempts must not read the window", 0, windowReads)
    }
}
