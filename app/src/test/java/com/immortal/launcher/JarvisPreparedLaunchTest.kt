/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */
package com.immortal.launcher

import org.junit.Assert.*
import org.junit.Test

class JarvisPreparedLaunchTest {
    @Test fun successIsSignalledOnlyAfterAcceptedLaunch() {
        val events = mutableListOf<String>()
        val request = JarvisPreparedLaunch({ true }, { events.add("launch"); true }, { events.add("success") }, { error("failure") })
        assertFalse(request.launched); assertTrue(events.isEmpty())
        request.prepared(true); assertTrue(request.launched)
        assertEquals(listOf("launch", "success"), events)
    }
    @Test fun failedPersistenceCannotDispatchOrWake() {
        var failures = 0
        val request = JarvisPreparedLaunch({ true }, { error("launch") }, { error("wake") }, { failures++ })
        request.prepared(false); assertFalse(request.launched); assertEquals(1, failures)
    }
    @Test fun cancelledPreparationCannotLaunchAfterNavigation() {
        val request = JarvisPreparedLaunch({ true }, { error("launch") }, { error("wake") }, { error("failure") })
        request.cancel(); request.prepared(true); assertFalse(request.launched)
    }
    @Test fun newerNavigationOwnsTheHandoffAndIsNotClearedByOldCompletion() {
        val request = JarvisPreparedLaunch({ false }, { error("launch") }, { error("wake") }, { error("clear_newer") })
        request.prepared(true); assertFalse(request.launched)
    }
    @Test fun repeatedCompletionCannotOpenDuplicateSessions() {
        var launches = 0; var successes = 0
        val request = JarvisPreparedLaunch({ true }, { launches++; true }, { successes++ }, { error("failure") })
        repeat(3) { request.prepared(true) }; assertEquals(1, launches); assertEquals(1, successes)
    }
    @Test fun rejectedIntentPreservesFailureWithoutWake() {
        var failures = 0
        val request = JarvisPreparedLaunch({ true }, { error("unavailable") }, { error("wake") }, { failures++ })
        request.prepared(true); assertFalse(request.launched); assertEquals(1, failures)
    }
    @Test fun supersededCompletionReleasesItsHostOnceWithoutFailureOrWake() {
        var retired = 0
        val request = JarvisPreparedLaunch({ false }, { error("launch") },
            { error("wake") }, { error("clear_newer") }, { retired++ })
        repeat(2) { request.prepared(true) }
        assertEquals(1, retired)
        assertFalse(request.launched)
    }

}
