/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */
package com.immortal.launcher

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Grandma mode with several people, and what its Messages button counts. */
class CallGrandmaGroupTest {
  private val t0 = 1_800_000_000_000L
  private val now = t0
  private val min = 60_000L
  private val rose = "rose-portal"
  private val walt = "walt-portal"
  private val call1 = "00000000000000a1"
  private val call2 = "00000000000000a2"
  private fun missed(id: String, from: String = rose, at: Long = now) =
      CallHistoryEntry(id, from, if (from == rose) "Rose" else "Walt", CallDirection.INCOMING, CallKind.CALL, CallEndReason.MISSED, at, 0L)
  private fun msg(n: Int, from: String = rose, at: Long = now + min, callId: String? = null, outgoing: Boolean = false,
                  seen: Boolean = outgoing) =
      CallMessage("%016x".format(n), from, if (from == rose) "Rose" else "Walt", at, 48_000, 1_000_000, false, seen, outgoing, callId)

  private fun contact(id: String, name: String) = CallContact(id, name)
  private fun call(vararg legs: CallLeg) = ActiveCall("0123456789abcdef", contact("sam", "Sam"), CallDirection.INCOMING, CallKind.CALL,
      true, CallPhase.LIVE, t0, t0, cameraOn = true, legs = legs.toList())
  private fun leg(id: String, name: String, phase: LegPhase = LegPhase.LIVE, mic: Boolean = true) =
      CallLeg(contact(id, name), phase, t0, offerer = false, remoteMicOn = mic)

  @Test fun everyOtherPersonGetsAFaceInOrder() {
    val c = call(leg("alex", "Alex"), leg("mia", "Mia & Leo", LegPhase.RINGING, mic = false))
    val members = GrandmaGroup.members(c)
    assertEquals(listOf("Sam", "Alex", "Mia & Leo"), members.map { it.contact.name })
    assertEquals(CallTile.State.RINGING, members[2].state)
    assertEquals(false, members[2].micOn)
    assertEquals(listOf("Sam"), GrandmaGroup.members(call()).map { it.contact.name })
  }

  @Test fun facesSitBigOnEveryWindow() {
    assertEquals(GrandmaTiles.ONE, GrandmaGroup.arrangement(1, LayoutClass.LARGE))
    assertEquals(GrandmaTiles.TWO_SIDE, GrandmaGroup.arrangement(2, LayoutClass.LARGE))
    assertEquals(GrandmaTiles.TWO_SIDE, GrandmaGroup.arrangement(2, LayoutClass.COMPACT_LANDSCAPE))
    assertEquals(GrandmaTiles.TWO_STACKED, GrandmaGroup.arrangement(2, LayoutClass.COMPACT_PORTRAIT))
    assertEquals(GrandmaTiles.THREE_SIDE, GrandmaGroup.arrangement(3, LayoutClass.LARGE))
    assertEquals(GrandmaTiles.THREE_STACKED, GrandmaGroup.arrangement(3, LayoutClass.COMPACT_PORTRAIT))
    LayoutClass.entries.forEach { l ->
      val small = GrandmaGroup.face(l, big = false)
      assertTrue("Grandma faces stay big on $l", small.avatar.value >= 120f)
      assertTrue("names stay readable on $l", small.name >= 34f)
      val self = GrandmaGroup.self(l)
      assertTrue("group self view is never larger than 1:1's on $l", self.widthDp <= GrandmaLayout.inCall(l).self.widthDp)
    }
  }

  @Test fun liveBarSaysWhoIsThere() {
    assertEquals("Sam", GrandmaGroup.with(listOf("Sam")))
    assertEquals("Sam and Alex", GrandmaGroup.with(listOf("Sam", "Alex")))
    assertEquals("Sam, Alex and Mia", GrandmaGroup.with(listOf("Sam", "Alex", "Mia")))
    assertEquals("", GrandmaGroup.with(emptyList()))
  }

  @Test fun grandmaMessagesButtonCountsNewMessagesAndMissesThatLeftNone() {
    val history = listOf(missed(call1), missed(call2, from = walt, at = now - 30 * min).copy(seen = true),
        missed("00000000000000a3", from = walt, at = now - 90 * min))
    val messages = listOf(msg(1, callId = call1, seen = false), msg(2, outgoing = true), msg(3, from = walt, at = now - 200 * min, seen = true))
    // Rose's miss shows once, as her unseen message; Walt's older miss is unseen; the seen one and the sent copy don't count.
    assertEquals(2, GrandmaInbox.unseen(messages, history))
    assertEquals(0, GrandmaInbox.unseen(emptyList(), emptyList()))
  }
}
