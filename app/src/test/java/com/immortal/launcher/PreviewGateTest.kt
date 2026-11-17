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

class PreviewGateTest {
    private val live = PreviewGate(started = true, visible = true, resumed = true, pageActive = true)

    @Test fun runsOnlyWhenEveryConditionHolds() {
        assertTrue(live.running)
        assertFalse("Not before the page has settled", live.copy(started = false).running)
        assertFalse("Paused when scrolled off", live.copy(visible = false).running)
        assertFalse("Paused when the screen is not resumed", live.copy(resumed = false).running)
        assertFalse("Paused on an inactive page", live.copy(pageActive = false).running)
    }

    @Test fun aNewPreviewWaitsForTheFirstFrameAndVisibility() {
        val opened = PreviewGate(resumed = true)
        assertFalse(opened.running)
        assertFalse(opened.copy(started = true).running)
        assertTrue(opened.copy(started = true, visible = true).running)
    }

    @Test fun resumingAfterScrollingBackRestartsIt() {
        val scrolledOff = live.copy(visible = false)
        assertTrue(scrolledOff.copy(visible = true).running)
    }
}
