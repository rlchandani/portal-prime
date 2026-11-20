/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */
package com.immortal.launcher

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** The built-in feed's source chain: first answer wins, failures cool down, never blank. */
class PhotoFrameWebChainTest {
  private var now = 1_000_000L
  private val logs = ArrayList<String>()
  private val chain = PhotoFrameWebChain<String>({ now }, { logs += it })
  private val asked = ArrayList<String>()
  private val answers = HashMap<String, () -> String?>()
  private fun sources(vararg names: String) = names.map { n -> n to { asked += n; answers[n]?.invoke() } }
  private val discarded = ArrayList<String>()

  @Test fun theFirstSourceThatAnswersWins() {
    answers["picsum"] = { "p1" }
    assertEquals("p1", chain.fetch(sources("unsplash", "picsum", "bundled"), { true }, { discarded += it }))
    assertEquals(listOf("unsplash", "picsum"), asked)
    assertEquals(listOf("screensaver photo source 'unsplash' unavailable; backing off"), logs)
  }

  @Test fun aFailedSourceIsSkippedUntilItsCooldownEnds() {
    answers["wikimedia"] = { "w" }
    answers["picsum"] = { throw java.io.IOException("outage") }
    chain.fetch(sources("picsum", "wikimedia"), { true }, {})
    assertTrue(chain.coolingDown("picsum", now))
    asked.clear()
    now += PhotoFrameWebChain.COOLDOWN_MS - 1
    assertEquals("w", chain.fetch(sources("picsum", "wikimedia"), { true }, {}))
    assertEquals(listOf("wikimedia"), asked)
    asked.clear()
    now += 1
    assertFalse(chain.coolingDown("picsum", now))
    chain.fetch(sources("picsum", "wikimedia"), { true }, {})
    assertEquals(listOf("picsum", "wikimedia"), asked)
  }

  @Test fun whenEverythingFailsOrCoolsTheChainIsRetriedIgnoringCooldowns() {
    chain.fetch(sources("picsum", "bundled"), { true }, {})
    assertEquals("screensaver: no photo source available", logs.last())
    asked.clear()
    answers["bundled"] = { "b" }
    assertEquals("b", chain.fetch(sources("picsum", "bundled"), { true }, {}))
    assertEquals("both cooling: the second pass asks them anyway", listOf("picsum", "bundled"), asked)
  }

  @Test fun aSupersededRequestStopsAndDiscardsALatePhoto() {
    var current = true
    answers["picsum"] = { current = false; "late" }
    assertNull(chain.fetch(sources("picsum", "bundled"), { current }, { discarded += it }))
    assertEquals(listOf("late"), discarded)
    assertEquals(listOf("picsum"), asked)
    assertNull(chain.fetch(sources("picsum"), { false }, { discarded += it }))
  }

  @Test fun aRequestSupersededInTheSecondPassAlsoDiscards() {
    var calls = 0
    var current = true
    answers["bundled"] = { calls++; if (calls == 2) { current = false; "late" } else null }
    assertNull(chain.fetch(sources("bundled"), { current }, { discarded += it }))
    assertEquals(listOf("late"), discarded)
  }
}
