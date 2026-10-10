/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */
package com.immortal.launcher

import org.junit.Assert.*
import org.junit.Test

class PinnedDreamExitTest {
    @Test fun expiredOriginKeepsNativeDreamWhenNoBarrierCanAttach() {
        val calls = mutableListOf<String>()
        exitPinnedDream(true, false, { calls += "admit"; false }, { calls += "lost" },
            { calls += "launch" }, { calls += "finish" })
        assertEquals(listOf("admit", "lost"), calls)
    }
    @Test fun knownLostCurrentPinKeepsDreamOnBarrierAttachmentFailure() {
        val calls = mutableListOf<String>()
        exitPinnedDream(false, true, { false }, { calls += "lost" },
            { fail("Must not launch") }, { fail("Must not reveal replacement") })
        assertEquals(listOf("lost"), calls)
    }
    @Test fun admittedPinnedReturnFinishesWithoutAnyConfiguredLaunch() {
        val calls = mutableListOf<String>()
        exitPinnedDream(true, true, { calls += "admit"; true }, { fail("No loss") },
            { fail("Must not launch") }, { calls += "finish" })
        assertEquals(listOf("admit", "finish"), calls)
    }
    @Test fun ordinaryUnpinnedDreamRetainsConfiguredDismissBehavior() {
        val calls = mutableListOf<String>()
        exitPinnedDream(false, false, { fail("Unpinned"); false }, { fail("No loss") },
            { calls += "launch" }, { calls += "finish" })
        assertEquals(listOf("launch", "finish"), calls)
    }
}
