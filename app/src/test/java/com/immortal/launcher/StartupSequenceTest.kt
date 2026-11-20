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
import org.junit.Assert.fail
import org.junit.Test

class StartupSequenceTest {
  private val ran = mutableListOf<String>()
  private fun steps(vararg names: String) = names.map { n -> StartupStep(n) { ran += n } }
  private fun sequence(steps: List<StartupStep>) = StartupSequence(steps) { it.action() }

  @Test fun runNextRunsOneStepAtATimeInOrder() {
    val s = sequence(steps("a", "b", "c"))
    assertTrue(s.runNext())
    assertEquals(listOf("a"), ran)
    assertTrue(s.runNext())
    assertFalse(s.runNext())
    assertEquals(listOf("a", "b", "c"), ran)
    assertTrue(s.done)
  }

  @Test fun runAllFinishesFromWherePacedRunsStopped() {
    val s = sequence(steps("a", "b", "c"))
    s.runNext()
    s.runAll()
    assertEquals(listOf("a", "b", "c"), ran)
    assertFalse(s.runNext())
    s.runAll()
    assertEquals("each step runs once", listOf("a", "b", "c"), ran)
  }

  @Test fun emptySequenceIsDone() {
    val s = sequence(emptyList())
    assertTrue(s.done)
    assertFalse(s.runNext())
    s.runAll()
    assertTrue(ran.isEmpty())
  }

  @Test fun reentrantCallsFromAStepRunNothingAndKeepOrder() {
    lateinit var s: StartupSequence
    val list = listOf(
        StartupStep("a") {
          ran += "a"
          s.runAll()
          s.runNext()
          assertEquals("nothing runs inside a running step", listOf("a"), ran)
        },
        StartupStep("b") { ran += "b" },
    )
    s = sequence(list)
    s.runAll()
    assertEquals(listOf("a", "b"), ran)
  }

  @Test fun aFailingStepIsNotRetriedAndLaterStepsStillRun() {
    val list = listOf(StartupStep("bad") { ran += "bad"; throw IllegalStateException("boom") }) + steps("b")
    val s = sequence(list)
    try {
      s.runAll()
      fail("the step's exception propagates")
    } catch (expected: IllegalStateException) {
    }
    s.runAll()
    assertEquals(listOf("bad", "b"), ran)
  }

  @Test fun runnerWrapsEveryStep() {
    val seen = mutableListOf<String>()
    StartupSequence(steps("a", "b")) { seen += "<" + it.name; it.action(); seen += it.name + ">" }.runAll()
    assertEquals(listOf("<a", "a>", "<b", "b>"), seen)
  }
}
