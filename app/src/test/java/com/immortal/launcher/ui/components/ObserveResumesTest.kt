/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */
package com.immortal.launcher.ui.components

import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import org.junit.Assert.assertEquals
import org.junit.Test

class ObserveResumesTest {
  private class Owner(state: Lifecycle.State) : LifecycleOwner {
    val registry = LifecycleRegistry.createUnsafe(this).apply { currentState = state }
    override val lifecycle: Lifecycle get() = registry
  }

  @Test fun homeSemanticsReplayTheCurrentResumeOnceWhenAdded() {
    val owner = Owner(Lifecycle.State.RESUMED)
    var calls = 0
    observeResumes(owner.lifecycle, replayCurrent = true) { calls++ }
    assertEquals("Home widgets read once on their first composition", 1, calls)
  }

  @Test fun settingsSemanticsSkipTheCatchUpResume() {
    val owner = Owner(Lifecycle.State.RESUMED)
    var calls = 0
    observeResumes(owner.lifecycle, replayCurrent = false) { calls++ }
    assertEquals("A page that loaded in remember is not read twice", 0, calls)
  }

  @Test fun bothSemanticsRunOnEveryLaterResume() {
    listOf(true, false).forEach { replay ->
      val owner = Owner(Lifecycle.State.RESUMED)
      var calls = 0
      observeResumes(owner.lifecycle, replayCurrent = replay) { calls++ }
      val before = calls
      repeat(2) {
        owner.registry.handleLifecycleEvent(Lifecycle.Event.ON_PAUSE)
        owner.registry.handleLifecycleEvent(Lifecycle.Event.ON_RESUME)
      }
      assertEquals("replay=$replay", before + 2, calls)
    }
  }

  @Test fun addedWhileStartedBothWaitForTheRealResume() {
    listOf(true, false).forEach { replay ->
      val owner = Owner(Lifecycle.State.STARTED)
      var calls = 0
      observeResumes(owner.lifecycle, replayCurrent = replay) { calls++ }
      assertEquals(0, calls)
      owner.registry.handleLifecycleEvent(Lifecycle.Event.ON_RESUME)
      assertEquals("replay=$replay", 1, calls)
    }
  }

  @Test fun unsubscribeStopsFurtherCalls() {
    val owner = Owner(Lifecycle.State.RESUMED)
    var calls = 0
    val stop = observeResumes(owner.lifecycle, replayCurrent = false) { calls++ }
    stop()
    owner.registry.handleLifecycleEvent(Lifecycle.Event.ON_PAUSE)
    owner.registry.handleLifecycleEvent(Lifecycle.Event.ON_RESUME)
    assertEquals(0, calls)
  }
}
