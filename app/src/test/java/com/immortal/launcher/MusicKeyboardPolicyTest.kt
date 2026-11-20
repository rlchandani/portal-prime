/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */
package com.immortal.launcher

import android.view.WindowManager.LayoutParams.SOFT_INPUT_ADJUST_NOTHING
import android.view.WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test

class MusicKeyboardPolicyTest {
    private class FakeWindow(override var mode: Int) : SoftInputTarget {
        val applied = mutableListOf<Int>()
        override fun apply(mode: Int) { this.mode = mode; applied += mode }
    }

    private class FakeScheduler : MusicMainScheduler {
        val queued = mutableListOf<Pair<Runnable, Long>>()
        override fun post(task: Runnable, delayMs: Long) { queued += task to delayMs }
        override fun cancel(task: Runnable) { queued.removeAll { it.first === task } }
        fun runAll() { val now = queued.toList(); queued.clear(); now.forEach { it.first.run() } }
    }

    private val window = FakeWindow(SOFT_INPUT_ADJUST_RESIZE)
    private val scheduler = FakeScheduler()

    @Before fun reset() = MusicKeyboardPolicy.resetForTest()
    @After fun clear() = MusicKeyboardPolicy.resetForTest()

    @Test fun closingRestoresTheWindowsModeAfterTheClosingFramesNotInThem() {
        val policy = MusicKeyboardPolicy(window, scheduler)
        policy.ensure()
        assertEquals(listOf(SOFT_INPUT_ADJUST_NOTHING), window.applied)
        policy.restoreLater()
        assertEquals(SOFT_INPUT_ADJUST_NOTHING, window.mode)
        assertEquals(MusicKeyboardPolicy.RESTORE_MS, scheduler.queued.single().second)
        scheduler.runAll()
        assertEquals(SOFT_INPUT_ADJUST_RESIZE, window.mode)
    }

    @Test fun aVisitThatNeverAppliedTheModeRestoresNothing() {
        MusicKeyboardPolicy(window, scheduler).restoreLater()
        assertEquals(emptyList<Pair<Runnable, Long>>(), scheduler.queued)
        assertEquals(emptyList<Int>(), window.applied)
    }

    @Test fun reopeningBeforeTheRestoreTakesItOverAndKeepsTheRealPreviousMode() {
        val first = MusicKeyboardPolicy(window, scheduler)
        first.ensure()
        first.restoreLater()
        val second = MusicKeyboardPolicy(window, scheduler)
        second.ensure()
        assertEquals(emptyList<Pair<Runnable, Long>>(), scheduler.queued)
        assertEquals(SOFT_INPUT_ADJUST_NOTHING, window.mode)
        assertEquals(1, window.applied.size)
        second.restoreLater()
        scheduler.runAll()
        assertEquals(SOFT_INPUT_ADJUST_RESIZE, window.mode)
    }

    @Test fun ensureAppliesOncePerVisit() {
        val policy = MusicKeyboardPolicy(window, scheduler)
        policy.ensure(); policy.ensure()
        assertEquals(1, window.applied.size)
    }
}
