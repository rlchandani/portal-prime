/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */

package com.immortal.launcher

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** Pure grammar + state machine for the Meta presence logcat signal (no Android). */
class MetaPresenceTest {

  // ---- line classification --------------------------------------------------

  @Test
  fun `available line is a presence beat`() {
    assertEquals(
        MetaPresence.Signal.BEAT,
        MetaPresence.classify("I aloha.UserPresenceManager: updateAvailability Availability available"))
  }

  @Test
  fun `PresenceManager heartbeat (gen1 grammar) is a beat`() {
    assertEquals(
        MetaPresence.Signal.BEAT,
        MetaPresence.classify("I PresenceManager: presence detected, confidence 0.92"))
  }

  @Test
  fun `idle availability is an idle hint`() {
    assertEquals(
        MetaPresence.Signal.IDLE,
        MetaPresence.classify("I aloha.UserPresenceManager: Availability idle since top app is not whitelisted"))
  }

  @Test
  fun `unrelated chatter is ignored`() {
    assertNull(MetaPresence.classify("I aloha.UserPresenceManager: COMMAND_TOP_APP_UPATED"))
    assertNull(MetaPresence.classify("I aloha.UserPresenceAppStateHelper: top activity changed"))
    assertNull(MetaPresence.classify(""))
  }

  // ---- state machine ----------------------------------------------------------

  private fun tracker() = MetaPresence.Tracker()

  @Test
  fun `starts unknown`() {
    assertNull(tracker().state(nowMs = 0))
  }

  @Test
  fun `a beat means present`() {
    val t = tracker()
    t.onSignal(MetaPresence.Signal.BEAT, atMs = 1_000)
    assertEquals(true, t.state(nowMs = 2_000))
  }

  @Test
  fun `presence survives while beats keep arriving`() {
    val t = tracker()
    t.onSignal(MetaPresence.Signal.BEAT, atMs = 0)
    t.onSignal(MetaPresence.Signal.BEAT, atMs = 40_000)
    assertEquals(true, t.state(nowMs = 80_000)) // 40s since last beat < expiry
  }

  @Test
  fun `presence expires when beats stop`() {
    val t = tracker()
    t.onSignal(MetaPresence.Signal.BEAT, atMs = 0)
    assertNull(t.state(nowMs = 0 + MetaPresence.BEAT_EXPIRY_MS + 1)) // stale → unknown, not absent
  }

  @Test
  fun `idle after a beat turns absent once the grace passes`() {
    val t = tracker()
    t.onSignal(MetaPresence.Signal.BEAT, atMs = 0)
    t.onSignal(MetaPresence.Signal.IDLE, atMs = 10_000)
    assertEquals(true, t.state(nowMs = 11_000)) // inside grace: still present
    assertEquals(false, t.state(nowMs = 10_000 + MetaPresence.IDLE_GRACE_MS + 1))
  }

  @Test
  fun `a fresh beat cancels a pending idle`() {
    val t = tracker()
    t.onSignal(MetaPresence.Signal.BEAT, atMs = 0)
    t.onSignal(MetaPresence.Signal.IDLE, atMs = 10_000)
    t.onSignal(MetaPresence.Signal.BEAT, atMs = 20_000)
    assertEquals(true, t.state(nowMs = 10_000 + MetaPresence.IDLE_GRACE_MS + 1))
  }

  @Test
  fun `idle without any prior beat stays unknown`() {
    val t = tracker()
    t.onSignal(MetaPresence.Signal.IDLE, atMs = 5_000)
    assertNull(t.state(nowMs = 60_000)) // never saw a person; can't claim absent
  }
}
