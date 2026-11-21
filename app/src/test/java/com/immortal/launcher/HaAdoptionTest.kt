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
import org.junit.Test

/** Adopting a peer's connection: resolution, validation before save, idempotency and the hello retry. */
class HaAdoptionTest {
  private val token = "eyJhbGciOiJIUzI1NiJ9.family-ha-token.sig"
  private val theirs = HaShareWire.Connection.of("http://ha.local:8123", token)!!
  private val family = HaSharePeer("family-1", "Family Area", "10.9.8.10", 8723)
  private val office = HaSharePeer("mini-1", "Rohit Office", "10.9.8.30", 8723)

  private var roster = listOf(family, office)
  private var answers = mutableMapOf<String, MutableList<HaShareAnswer>>()
  private var valid = true
  private var current: HaShareWire.Connection? = null
  private val saved = ArrayList<HaShareWire.Connection>()
  private val events = ArrayList<String>()
  private val logs = ArrayList<String>()

  private val asker = object : HaAdoption.Asker {
    override fun probe(peer: HaSharePeer) = next(peer, "probe")
    override fun share(peer: HaSharePeer) = next(peer, "share")
    private fun next(peer: HaSharePeer, op: String): HaShareAnswer {
      events += "$op:${peer.id}"
      val queue = answers["$op:${peer.id}"] ?: return HaShareAnswer.Unreachable
      return if (queue.size > 1) queue.removeAt(0) else queue.first()
    }
  }

  private val adoption = HaAdoption({ roster }, asker, { events += "validate"; valid }, { current },
      { saved += it; current = it }, { events += "announce" }, { events += "settle" }, { logs += it })

  private fun answer(op: String, peer: HaSharePeer, vararg a: HaShareAnswer) { answers["$op:${peer.id}"] = a.toMutableList() }

  @Test fun anUnconnectedPortalAdoptsAValidatedConnection() {
    answer("share", family, HaShareAnswer.Shared("Family Area", theirs))
    val out = adoption.adopt("Family Area", replace = false)
    assertEquals(HaAdoption.Outcome(HaAdoption.Result.ADOPTED, "Family Area", validated = true), out)
    assertEquals(listOf(theirs), saved)
    assertEquals(listOf("share:family-1", "validate"), events)
  }

  @Test fun nothingIsSavedWhenHomeAssistantRefusesIt() {
    answer("share", family, HaShareAnswer.Shared("Family Area", theirs))
    valid = false
    assertEquals(HaAdoption.Result.VALIDATION_FAILED, adoption.adopt("family-1", replace = false).result)
    assertTrue(saved.isEmpty())
  }

  @Test fun theSameConnectionIsUnchangedButStillValidated() {
    current = HaShareWire.Connection.of("http://ha.local:8123/", token)
    answer("share", family, HaShareAnswer.Shared("Family Area", theirs))
    val out = adoption.adopt("family-1", replace = false)
    assertEquals(HaAdoption.Outcome(HaAdoption.Result.UNCHANGED, "Family Area", validated = true), out)
    assertTrue(out.result.ok)
    assertTrue(saved.isEmpty())
    assertTrue("validate" in events)
  }

  @Test fun aDifferentSavedConnectionIsOnlyReplacedWhenAsked() {
    current = HaShareWire.Connection.of("http://old.local:8123", "old-token-0123456789")
    answer("share", family, HaShareAnswer.Shared("Family Area", theirs))
    assertEquals(HaAdoption.Result.ALREADY_CONNECTED, adoption.adopt("family-1", replace = false).result)
    assertTrue(saved.isEmpty())
    assertFalse("validate" in events)
    assertEquals(HaAdoption.Result.ADOPTED, adoption.adopt("family-1", replace = true).result)
    assertEquals(listOf(theirs), saved)
  }

  @Test fun peersResolveByIdOrNameButNotAmbiguously() {
    assertEquals(HaAdoption.Resolution.Found(family), HaAdoption.resolve(roster, "family-1"))
    assertEquals(HaAdoption.Resolution.Found(family), HaAdoption.resolve(roster, "  family area "))
    assertEquals(HaAdoption.Resolution.None, HaAdoption.resolve(roster, "Kitchen"))
    assertEquals(HaAdoption.Resolution.None, HaAdoption.resolve(roster, " "))
    val twins = roster + family.copy(id = "family-2")
    assertEquals(HaAdoption.Resolution.Many, HaAdoption.resolve(twins, "Family Area"))
    assertEquals(HaAdoption.Resolution.Found(family), HaAdoption.resolve(twins, "family-1"))
    roster = twins
    assertEquals(HaAdoption.Result.AMBIGUOUS, adoption.adopt("Family Area", replace = false).result)
    assertEquals(HaAdoption.Result.NO_PEER, adoption.adopt("Kitchen", replace = false).result)
    assertTrue(events.isEmpty())
  }

  @Test fun anUnknownPeerRefusalReannouncesAndRetriesOnce() {
    answer("share", family, HaShareAnswer.Refused(403, HaAdoption.UNKNOWN_PEER), HaShareAnswer.Shared("Family Area", theirs))
    assertEquals(HaAdoption.Result.ADOPTED, adoption.adopt("family-1", replace = false).result)
    assertEquals(listOf("share:family-1", "announce", "settle", "share:family-1", "validate"), events)
  }

  @Test fun theRetryHappensOnlyOnce() {
    answer("share", family, HaShareAnswer.Refused(403, HaAdoption.UNKNOWN_PEER))
    assertEquals(HaAdoption.Result.REFUSED, adoption.adopt("family-1", replace = false).result)
    assertEquals(2, events.count { it == "share:family-1" })
  }

  @Test fun failuresMapToOutcomesAndAreLoggedWithoutSecrets() {
    val cases = mapOf(
        HaShareAnswer.Refused(404, "not_connected") to HaAdoption.Result.PEER_NOT_CONNECTED,
        HaShareAnswer.Refused(429, "rate_limited") to HaAdoption.Result.RATE_LIMITED,
        HaShareAnswer.Refused(403, "forbidden") to HaAdoption.Result.REFUSED,
        HaShareAnswer.Refused(404, "not_found") to HaAdoption.Result.REFUSED,
        HaShareAnswer.Unreachable to HaAdoption.Result.UNREACHABLE,
        HaShareAnswer.Invalid to HaAdoption.Result.INVALID_REPLY,
    )
    for ((a, expected) in cases) {
      answer("share", family, a)
      assertEquals(a.toString(), expected, adoption.adopt("family-1", replace = false).result)
      assertFalse(expected.ok)
    }
    assertTrue(saved.isEmpty())
    answer("share", family, HaShareAnswer.Shared("Family Area", theirs))
    adoption.adopt("family-1", replace = false)
    assertTrue(logs.isNotEmpty())
    assertTrue(logs.none { it.contains(token) || it.contains("ha.local") })
  }

  @Test fun offersListOnlyConnectedPeers() {
    answer("probe", family, HaShareAnswer.Connected("Family Area"))
    answer("probe", office, HaShareAnswer.Refused(404, "not_connected"))
    assertEquals(listOf(HaAdoption.Offer(family, "Family Area")), adoption.offers())
  }

  @Test fun offersAskPeersInParallel() {
    val gate = java.util.concurrent.CountDownLatch(2)
    val parallel = object : HaAdoption.Asker {
      override fun probe(peer: HaSharePeer): HaShareAnswer {
        gate.countDown()
        // Each probe only finishes once both have started: a sequential loop would time out here.
        return if (gate.await(5, java.util.concurrent.TimeUnit.SECONDS)) HaShareAnswer.Connected(peer.name) else HaShareAnswer.Unreachable
      }
      override fun share(peer: HaSharePeer) = HaShareAnswer.Unreachable
    }
    val a = HaAdoption({ roster }, parallel, { true }, { null }, {}, {}, {}, {})
    assertEquals(listOf(family, office), a.offers().map { it.peer })
  }

  @Test fun candidatesAreLanPortalsOtherThanUs() {
    val peers = listOf(
        CallPeer("family-1", "Family Area", 1L, host = "10.9.8.10", port = 8723),
        CallPeer("self-1", "Living", 1L, host = "10.9.8.21", port = 8723),
        CallPeer("noaddr-1", "Old", 1L),
        CallPeer("public-1", "Public", 1L, host = "8.8.8.8", port = 8723),
        CallPeer("lowport-1", "Ssh", 1L, host = "10.9.8.40", port = 22),
        CallPeer(CallPhoneId.of("t"), "Phone", 1L, host = "10.9.8.50", port = 8723),
    )
    assertEquals(listOf(HaSharePeer("family-1", "Family Area", "10.9.8.10", 8723)), HaAdoption.candidates(peers, "self-1"))
  }

  @Test fun onlyHomeAssistantsOwnApiAnswerValidates() {
    assertTrue(HaAdoption.isApiRunning("""{"message": "API running."}"""))
    assertFalse(HaAdoption.isApiRunning("""{"message": "401: Unauthorized"}"""))
    assertFalse(HaAdoption.isApiRunning("<html>API running</html>"))
    assertFalse(HaAdoption.isApiRunning(null))
  }

  @Test fun failureMessagesNeverMentionTheToken() {
    for (r in HaAdoption.Result.values()) {
      val text = haAdoptMessage(r, "Family Area")
      assertFalse(text.contains("token", ignoreCase = true))
    }
  }
}
