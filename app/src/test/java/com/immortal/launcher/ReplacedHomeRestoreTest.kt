/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */
package com.immortal.launcher

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * After our own package is replaced (OTA, fleet or adb), Android force-removes every Hearthpane
 * activity and resumes whatever HOME activity is left in the stack: Meta's launcher. The 1.331 ->
 * 1.332 OTA on Family (15:57, 2026-11-22) left Meta's Home on top for 3.5 minutes because nothing
 * brought Hearthpane back. These tests pin the bounded restore that now does.
 */
class ReplacedHomeRestoreTest {
  private class FakeHost(
      var defaultHome: Boolean = true,
      var inCall: Boolean = false,
      /** How many startHome calls it takes before one of our activities is resumed (0 = never). */
      private val resumesAfterStarts: Int = 1,
      var startSucceeds: Boolean = true,
  ) : ReplacedHomeHost {
    var starts = 0
    var ownResumed = false
    val delays = mutableListOf<Long>()
    private val queue = ArrayDeque<() -> Unit>()

    override fun isDefaultHome() = defaultHome
    override fun inCall() = inCall
    override fun ownActivityResumed() = ownResumed
    override fun startHome(): Boolean {
      starts++
      if (!startSucceeds) return false
      if (resumesAfterStarts in 1..starts) ownResumed = true
      return true
    }
    override fun schedule(delayMs: Long, block: () -> Unit) {
      delays += delayMs
      queue.addLast(block)
    }
    override fun log(message: String) = Unit

    fun drain() {
      while (queue.isNotEmpty()) queue.removeFirst().invoke()
    }
  }

  private fun run(host: FakeHost): ReplacedHomeOutcome {
    var outcome: ReplacedHomeOutcome? = null
    ReplacedHomeRestore(host).run { outcome = it }
    host.drain()
    return checkNotNull(outcome) { "restore never finished" }
  }

  @Test fun metaHomeOnTopAfterOurReplaceBringsHearthpaneHomeBack() {
    val host = FakeHost()
    assertEquals(ReplacedHomeOutcome.RESTORED, run(host))
    assertEquals(1, host.starts)
  }

  @Test fun androidAlreadyRestoredOurActivityMeansNoExtraStart() {
    val host = FakeHost().apply { ownResumed = true }
    assertEquals(ReplacedHomeOutcome.ALREADY_ON_TOP, run(host))
    assertEquals(0, host.starts)
  }

  @Test fun notTheDefaultHomeLeavesTheScreenAlone() {
    val host = FakeHost(defaultHome = false)
    assertEquals(ReplacedHomeOutcome.NOT_DEFAULT_HOME, run(host))
    assertEquals(0, host.starts)
  }

  @Test fun aCallInProgressIsNeverInterrupted() {
    val host = FakeHost(inCall = true)
    assertEquals(ReplacedHomeOutcome.IN_CALL, run(host))
    assertEquals(0, host.starts)
  }

  @Test fun aStartThatDoesNotLandIsRetriedThenSucceeds() {
    val host = FakeHost(resumesAfterStarts = 2)
    assertEquals(ReplacedHomeOutcome.RESTORED, run(host))
    assertEquals(2, host.starts)
  }

  @Test fun retriesAreBoundedAndFinishInsideTheReceiverWindow() {
    val host = FakeHost(resumesAfterStarts = 0)
    assertEquals(ReplacedHomeOutcome.GAVE_UP, run(host))
    assertEquals(ReplacedHomeRestore.MAX_STARTS, host.starts)
    // goAsync() gives a receiver about 10 s; the whole restore must end well before that.
    assertTrue("total ${host.delays.sum()} ms", host.delays.sum() < 9_000L)
  }

  @Test fun aCallStartingBetweenRetriesStopsTheRetries() {
    val host = FakeHost(resumesAfterStarts = 0)
    var outcome: ReplacedHomeOutcome? = null
    ReplacedHomeRestore(host).run { outcome = it }
    assertEquals(1, host.starts)
    host.inCall = true
    host.drain()
    assertEquals(ReplacedHomeOutcome.IN_CALL, outcome)
    assertEquals(1, host.starts)
  }

  @Test fun aRejectedStartIsRetriedNotTreatedAsSuccess() {
    val host = FakeHost(startSucceeds = false)
    assertEquals(ReplacedHomeOutcome.GAVE_UP, run(host))
    assertEquals(ReplacedHomeRestore.MAX_STARTS, host.starts)
  }

  @Test fun finishIsCalledExactlyOnce() {
    val host = FakeHost(resumesAfterStarts = 3)
    var calls = 0
    ReplacedHomeRestore(host).run { calls++ }
    host.drain()
    assertEquals(1, calls)
  }

  @Test fun onlyOurOwnReplaceTriggersTheRestore() {
    assertTrue(ReplacedHomeRestore.handles("android.intent.action.MY_PACKAGE_REPLACED"))
    assertEquals(false, ReplacedHomeRestore.handles("android.intent.action.PACKAGE_REPLACED"))
    assertEquals(false, ReplacedHomeRestore.handles(null))
  }
}

class OwnActivityResumesTest {
  @Test fun countsResumedActivitiesAndNeverGoesNegative() {
    val activity = org.mockito.Mockito.mock(android.app.Activity::class.java)
    OwnActivityResumes.onActivityPaused(activity) // a pause with no resume seen (installed late)
    assertEquals(false, OwnActivityResumes.any())
    OwnActivityResumes.onActivityResumed(activity)
    assertEquals(true, OwnActivityResumes.any())
    OwnActivityResumes.onActivityPaused(activity)
    assertEquals(false, OwnActivityResumes.any())
  }
}
