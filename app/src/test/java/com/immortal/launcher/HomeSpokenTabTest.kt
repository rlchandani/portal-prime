/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */
package com.immortal.launcher

import androidx.compose.runtime.MonotonicFrameClock
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.yield
import org.junit.Assert.assertEquals
import org.junit.Test

/** [followAfterFrames]: how Home's tab switches reach accessibility a few frames late. */
class HomeSpokenTabTest {
  /** A frame clock whose frames are released one at a time by the test. */
  private class ManualClock : MonotonicFrameClock {
    private val frames = Channel<Unit>(Channel.UNLIMITED)
    private var count = 0L
    fun frame() { frames.trySend(Unit) }
    override suspend fun <R> withFrameNanos(onFrame: (Long) -> R): R {
      frames.receive()
      return onFrame(++count * 16_666_667L)
    }
  }

  private suspend fun settle() = repeat(20) { yield() }

  @Test fun aSwitchIsExposedOnlyAfterTheExposeFrames() = runBlocking {
    val clock = ManualClock()
    val shown = MutableStateFlow(ApprovedLauncherTab.HOME)
    val exposed = mutableListOf<ApprovedLauncherTab>()
    val job = launch(clock, CoroutineStart.UNDISPATCHED) {
      followAfterFrames(shown, HOME_TAB_EXPOSE_FRAMES) { exposed += it }
    }
    repeat(HOME_TAB_EXPOSE_FRAMES) { clock.frame() }
    settle()
    assertEquals(listOf(ApprovedLauncherTab.HOME), exposed)

    shown.value = ApprovedLauncherTab.APPS
    settle()
    assertEquals("Nothing on the switch frame", listOf(ApprovedLauncherTab.HOME), exposed)
    repeat(HOME_TAB_EXPOSE_FRAMES - 1) { clock.frame() }
    settle()
    assertEquals("Still nothing one frame early", listOf(ApprovedLauncherTab.HOME), exposed)
    clock.frame()
    settle()
    assertEquals(listOf(ApprovedLauncherTab.HOME, ApprovedLauncherTab.APPS), exposed)
    job.cancel()
  }

  @Test fun aQuickSecondSwitchExposesOnlyTheLastTab() = runBlocking {
    val clock = ManualClock()
    val shown = MutableStateFlow(ApprovedLauncherTab.HOME)
    val exposed = mutableListOf<ApprovedLauncherTab>()
    val job = launch(clock, CoroutineStart.UNDISPATCHED) {
      followAfterFrames(shown, 2) { exposed += it }
    }
    clock.frame(); clock.frame()
    settle()
    exposed.clear()

    shown.value = ApprovedLauncherTab.APPS
    settle()
    clock.frame()
    settle()
    shown.value = ApprovedLauncherTab.TOOLS
    settle()
    clock.frame()
    settle()
    assertEquals("The newer switch restarted the countdown", emptyList<ApprovedLauncherTab>(), exposed)
    clock.frame()
    settle()
    assertEquals(listOf(ApprovedLauncherTab.TOOLS), exposed)
    job.cancel()
  }

  @Test fun pagesWakeOnTheirOwnFrameAfterTheAccessibilitySwap() {
    assertEquals(HOME_TAB_EXPOSE_FRAMES + 1, HOME_TAB_WAKE_FRAMES)
    assert(HOME_TAB_EXPOSE_FRAMES >= 1) { "The switch frame itself changes no semantics" }
  }

  @Test fun theDockListsTheLauncherTabsInOrder() {
    assertEquals(ApprovedLauncherTab.entries.map { it.id }, HomeDockTabs.map { it.first })
    assertEquals(listOf("Home", "Apps", "Tools"), HomeDockTabs.map { it.second })
  }
}
