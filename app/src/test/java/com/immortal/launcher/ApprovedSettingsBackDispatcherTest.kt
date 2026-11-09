/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */
package com.immortal.launcher

import org.junit.Assert.*
import org.junit.Test

class ApprovedSettingsBackDispatcherTest {
  private class Host {
    var eligible = true
    var stack = SettingsBackStack.of("w-weather")
    var backCalls = 0
    fun back(): Boolean {
      if (!stack.canGoBack) return false
      backCalls++
      stack = stack.back()
      return true
    }
  }

  @Test fun oneRemoteBackUsesTheSameHistoryAndDoesNotConsumeAnOverviewBack() {
    val host = Host()
    val dispatcher = ApprovedSettingsBackDispatcher<Host>({ it.eligible }, { it.back() })
    dispatcher.resumed(host)
    assertTrue(dispatcher.dispatch())
    assertEquals(listOf("widgets"), host.stack.pages)
    assertEquals(1, host.backCalls)
    assertFalse(dispatcher.dispatch())
    assertEquals(1, host.backCalls)
  }

  @Test fun missingPausedOrIneligibleHostDoesNotNavigate() {
    val host = Host()
    val dispatcher = ApprovedSettingsBackDispatcher<Host>({ it.eligible }, { it.back() })
    assertFalse(dispatcher.dispatch())
    dispatcher.resumed(host)
    host.eligible = false
    assertFalse(dispatcher.dispatch())
    host.eligible = true
    dispatcher.unavailable(host)
    assertFalse(dispatcher.dispatch())
    assertEquals(0, host.backCalls)
  }

  @Test fun replacementDuringAdmissionCannotNavigateTheOldHost() {
    val old = Host()
    val next = Host()
    lateinit var dispatcher: ApprovedSettingsBackDispatcher<Host>
    dispatcher = ApprovedSettingsBackDispatcher({ host ->
      if (host === old) dispatcher.resumed(next)
      true
    }, { it.back() })
    dispatcher.resumed(old)
    assertFalse(dispatcher.dispatch())
    dispatcher.unavailable(old)
    assertTrue(dispatcher.dispatch())
    assertEquals(0, old.backCalls)
    assertEquals(1, next.backCalls)
  }

  @Test fun failedNavigationIsNotRetried() {
    val host = Host()
    var calls = 0
    val dispatcher = ApprovedSettingsBackDispatcher<Host>({ true }, {
      calls++
      throw IllegalStateException("navigation failed")
    })
    dispatcher.resumed(host)
    try {
      dispatcher.dispatch()
      fail("Expected navigation failure")
    } catch (_: IllegalStateException) { }
    assertEquals(1, calls)
  }
}
