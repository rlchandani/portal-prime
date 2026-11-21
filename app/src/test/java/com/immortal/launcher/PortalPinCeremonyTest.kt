/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */
package com.immortal.launcher

import com.immortal.launcher.youtube.MemorySecureStore
import org.junit.Assert.*
import org.junit.Test
import java.io.ByteArrayInputStream
import java.security.SecureRandom

/**
 * The whole "Pair this Portal with another Portal" round on the JVM: Family shows a code, Office
 * types it over a LAN wired straight into Family's route, both see the same pictures, both pin,
 * then attestation is swapped and recorded. Plus the flow state the screens follow.
 */
class PortalPinCeremonyTest {
  private val random = SecureRandom()
  private val key = ByteArray(32) { (it * 7).toByte() }
  private var now = 1_700_000_000_000L
  private val addresses = mapOf("family-1" to "10.0.0.1", "office-1" to "10.0.0.2")
  private val familyMailbox = PortalPinMailbox { now }
  private val route = PortalPinRoute({ key }, { "family-1" }, { now }, HouseholdMessage.ReplayGuard(), { addresses[it] }, familyMailbox, {})
  private val familyKey = HomeLinkIdentity.generate(random)
  private val officeKey = HomeLinkIdentity.generate(random)
  private val familyStore = PortalRosterStore(MemorySecureStore()) {}
  private val officeStore = PortalRosterStore(MemorySecureStore()) {}

  private val officeToFamily = PortalPinTransport { peer, body ->
    if (peer.id != "family-1") return@PortalPinTransport null
    val nonce = ByteArray(12).also(random::nextBytes).let(HouseholdKey::toHex)
    val header = PortalPinWire.requestHeader(key, "office-1", nonce, now, body)!!
    val resp = route.handle(FleetHttpServer.Request("POST", PortalPinWire.PATH, "", mapOf(PortalPinWire.HEADER to header),
        ByteArrayInputStream(body.toByteArray()), body.length, remoteAddress = "10.0.0.2"))
    resp.status to FleetReq.text(resp)
  }

  private fun family(chain: List<ByteArray> = emptyList()) = PortalPinCeremony(HomeLinkSelf(familyKey, "Family Area", "family-1"),
      familyStore, familyMailbox, { _, _ -> null }, { emptyList() }, { chain }, random, { now })

  private fun office(chain: List<ByteArray> = emptyList()) = PortalPinCeremony(HomeLinkSelf(officeKey, "Office", "office-1"),
      officeStore, PortalPinMailbox { now }, officeToFamily, { listOf(PortalPinPeer("family-1", "10.0.0.1", 8080)) }, { chain },
      random, { now })

  @Test fun showTypeComparePinBothWays() {
    val f = family()
    val o = office()
    val invite = f.show()
    val joined = o.join(invite.code) as PortalPinJoin.Result.Comparing
    val inviter = f.inviterState() as HomeLinkInviterState.Comparing
    assertEquals(inviter.safety, joined.safety)
    assertEquals(PortalPinOutcome.PINNED, f.confirm(PortalPinSide.SHOWED_CODE))
    assertEquals(PortalPinOutcome.PINNED, o.confirm(PortalPinSide.TYPED_CODE))
    assertEquals(officeKey.publicKey, familyStore.roster.byDevice("office-1")!!.publicKey)
    assertEquals(familyKey.publicKey, officeStore.roster.byDevice("family-1")!!.publicKey)
    // No chains on either side: attestation stays unchecked, nothing fails.
    assertEquals(PortalAttestationLevel.UNCHECKED, familyStore.roster.byDevice("office-1")!!.attestation)
  }

  @Test fun attestationIsSwappedAndRecordedOnBothSides() {
    val f = family(chain = listOf(byteArrayOf(1)))
    val o = office(chain = listOf(byteArrayOf(2), byteArrayOf(3)))
    o.join(f.show().code)
    f.confirm(PortalPinSide.SHOWED_CODE)
    o.confirm(PortalPinSide.TYPED_CODE)
    // One-certificate chains are "no attestation"; garbage two-certificate chains are invalid.
    assertEquals(PortalAttestationLevel.ABSENT, officeStore.roster.byDevice("family-1")!!.attestation)
    assertEquals(PortalAttestationLevel.INVALID, familyStore.roster.byDevice("office-1")!!.attestation)
  }

  @Test fun aMismatchOrCancelPinsNothingAndKillsTheCode() {
    val f = family()
    val o = office()
    val invite = f.show()
    o.join(invite.code)
    f.end()
    assertEquals(PortalPinOutcome.REFUSED, f.confirm(PortalPinSide.SHOWED_CODE))
    assertTrue(familyStore.roster.isEmpty)
    assertEquals(PortalPinJoin.Result.NotFound, office().join(invite.code))
  }

  @Test fun aResetPortalWithANewKeyNeedsAnExplicitRepair() {
    family().let { f -> office().let { o -> o.join(f.show().code); f.confirm(PortalPinSide.SHOWED_CODE) } }
    val reset = PortalPinCeremony(HomeLinkSelf(HomeLinkIdentity.generate(random), "Office", "office-1"), PortalPinCeremonyTestStores.fresh(),
        PortalPinMailbox { now }, officeToFamily, { listOf(PortalPinPeer("family-1", "10.0.0.1", 8080)) }, { emptyList() }, random, { now })
    val f = family()
    reset.join(f.show().code)
    assertEquals(PortalPinOutcome.NEEDS_REPAIR, f.confirm(PortalPinSide.SHOWED_CODE))
    assertEquals(officeKey.publicKey, familyStore.roster.byDevice("office-1")!!.publicKey)
    assertEquals(PortalPinOutcome.PINNED, f.confirm(PortalPinSide.SHOWED_CODE, repair = true))
    assertNotEquals(officeKey.publicKey, familyStore.roster.byDevice("office-1")!!.publicKey)
  }

  @Test fun theCodeOnScreenExpires() {
    val f = family()
    f.show()
    now += HomeLinkInvite.PIN_LIFETIME_MS
    assertEquals(HomeLinkInviterState.Failed(HomeLinkFailure.EXPIRED), f.inviterState())
  }

  @Test fun theFlowFollowsBothSides() {
    val invite = HomeLinkInvite(HomeLinkCode.generate(random, HomeLinkDomain.PORTAL_PIN), 0L, HomeLinkInvite.PIN_LIFETIME_MS)
    val showing = PortalPinFlowState().showCode(invite)
    assertEquals(PortalPinStage.SHOW, showing.stage)
    assertSame(showing, showing.inviter(HomeLinkInviterState.Waiting(invite, HomeLinkPake.start("1234567", "0".repeat(32), random))))
    assertEquals(PortalPinStage.FAILED, showing.inviter(HomeLinkInviterState.Failed(HomeLinkFailure.TOO_MANY_TRIES)).stage)
    val typing = PortalPinFlowState().typeCode()
    assertSame(typing, typing.joining())
    val full = typing.typed(invite.code.value.fold(typing.entry) { e, c -> e.type(c) })
    assertEquals(invite.code, full.entry.code)
    assertEquals(PortalPinStage.JOINING, full.joining().stage)
    assertEquals(PortalPinCopy.NOT_FOUND, full.joining().joined(PortalPinJoin.Result.NotFound).note)
    assertEquals(PortalPinStage.TYPE, full.joining().joined(PortalPinJoin.Result.NoAnswer).stage)
    assertEquals(HomeLinkFailure.TAMPERED, full.joined(PortalPinJoin.Result.Tampered).failure)
    val safety = HomeLinkSafety.derive(familyKey.publicKey, officeKey.publicKey, HomeLinkHash.b64(ByteArray(32)))!!
    val verify = full.comparing(HomeLinkPeer("Family Area", familyKey.publicKey, "family-1"), safety)
    assertEquals(PortalPinStage.VERIFY, verify.stage)
    assertEquals(PortalPinStage.DONE, verify.pinned(PortalPinOutcome.PINNED).stage)
    assertEquals(PortalPinStage.REPAIR, verify.pinned(PortalPinOutcome.NEEDS_REPAIR).stage)
    assertEquals(PortalPinStage.FAILED, verify.pinned(PortalPinOutcome.NOT_STORED).stage)
    assertEquals(HomeLinkFailure.MISMATCH, verify.mismatch().failure)
    assertNull(verify.mismatch().peer)
    assertEquals("k", PortalPinFlowState().askRemove("k").removing)
    assertEquals(PortalPinFlowState(), verify.list())
  }

  /** A home code typed on the pairing keypad never becomes a code to join with. */
  @Test fun thePairingKeypadOnlyTakesPairingCodes() {
    val home = HomeLinkCode.generate(random)
    val typed = home.value.fold(PortalPinFlowState().typeCode().entry) { e, c -> e.type(c) }
    assertTrue(typed.complete)
    assertNull(typed.code)
  }

  @Test fun copyIsSpecificToPairingPortals() {
    assertTrue(PortalPinCopy.failure(HomeLinkFailure.EXPIRED).body.contains("5 minutes"))
    assertEquals(HomeLinkCopy.failure(HomeLinkFailure.MISMATCH), PortalPinCopy.failure(HomeLinkFailure.MISMATCH))
    val pin = PortalPin("kid", "Office", officeKey.publicKey, 1L, "office-1", PortalAttestationLevel.TEE)
    assertEquals("office-1 · Key in secure hardware", PortalPinCopy.pinDetail(pin))
    assertEquals("office-1 · Key in secure hardware · a certificate is out of date",
        PortalPinCopy.pinDetail(pin.copy(attestationOutOfDate = true)))
    PortalAttestationLevel.entries.forEach { assertTrue(PortalPinCopy.attestation(it).isNotBlank()) }
  }
}

private object PortalPinCeremonyTestStores {
  fun fresh() = PortalRosterStore(MemorySecureStore()) {}
}
