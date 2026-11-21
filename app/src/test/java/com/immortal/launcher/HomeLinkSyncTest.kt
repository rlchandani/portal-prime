/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */
package com.immortal.launcher

import com.immortal.launcher.youtube.MemorySecureStore
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.io.ByteArrayInputStream
import java.security.SecureRandom

/**
 * Sharing the vault between this home's Portals (wire v2): only a pinned Portal device key can
 * pull it, the answer is sealed to a one-time key and signed, and the household key alone — which
 * paired phones hold — gets nothing. Software keys stand in for the Keystore device keys.
 */
class HomeLinkSyncTest {
  private val random = SecureRandom()
  private val key = ByteArray(32) { (it * 5).toByte() }
  private val requester = "living-1"
  private val host = "10.9.8.21"
  private val now = 1_700_000_000_000L
  private val perms = HomeLinkPermissions.defaults(listOf("Kitchen"), emptySet())
  private val logs = ArrayList<String>()
  private val pulledBack = ArrayList<String>()
  private val source = HomeLinkRepository(MemorySecureStore(), random, { now }, {})
  private val responderDevice = HomeLinkIdentity.generate(random)
  private val requesterDevice = HomeLinkIdentity.generate(random)
  private val responderKid = HomeLinkIdentity.fingerprint(responderDevice.publicKey)
  private val requesterKid = HomeLinkIdentity.fingerprint(requesterDevice.publicKey)
  /** What the requester (living-1) pinned: the responder. */
  private val requesterRoster = PortalRoster().pin(HomeLinkPeer("Family", responderDevice.publicKey, "family-1"), requesterDevice.publicKey, now)!!
  /** What the responder (family-1) pinned: the requester. */
  private var responderRoster = PortalRoster().pin(HomeLinkPeer("Living room", requesterDevice.publicKey, requester), responderDevice.publicKey, now)!!
  private var announced = mapOf(requester to host)
  private val route = HomeLinkSyncRoute({ key }, { "family-1" }, { responderDevice }, { responderRoster }, { now },
      HouseholdMessage.ReplayGuard(), { announced[it] }, { source.vault.value }, { pulledBack += it }, random, { logs += it })

  init {
    source.identity()
    source.link(HomeLinkPeer("Lakeview", HomeLinkIdentity.generate(random).publicKey), perms)
  }

  private fun nonce() = ByteArray(12).also(random::nextBytes).let(HouseholdKey::toHex)

  private fun ask(device: HomeLinkSigner = requesterDevice, digest: String = HomeLinkSyncWire.digest(HomeLinkVault(), key),
                  back: Boolean = false, ts: Long = now) =
      HomeLinkSyncWire.ask(device, nonce(), ts, digest, back, random)

  private fun post(asking: HomeLinkSyncWire.Asking, from: String = requester, addr: String = host): Pair<Int, String> {
    val header = HomeLinkSyncWire.requestHeader(key, from, asking.pull.nonce, now, asking.body)!!
    val req = FleetHttpServer.Request("POST", HomeLinkSyncWire.PATH, "", mapOf(HomeLinkSyncWire.HEADER to header),
        ByteArrayInputStream(asking.body.toByteArray()), asking.body.length, remoteAddress = addr)
    val resp = route.handle(req)
    return resp.status to FleetReq.text(resp)
  }

  @Test fun aPinnedPortalPullsTheVaultAndOnlyItsOneTimeKeyOpensIt() {
    val asking = ask()
    val (status, answer) = post(asking)
    assertEquals(200, status)
    val theirs = HomeLinkSyncWire.receive(asking, answer, requesterRoster, requesterKid)!!
    assertEquals(source.vault.value, theirs)
    // Another pull's key (same requester, same device) can't open this answer.
    assertNull(HomeLinkSyncWire.receive(ask(), answer, requesterRoster, requesterKid))
  }

  @Test fun theAnswerNeverCarriesThePrivateKeyOrNamesInTheClear() {
    val (_, answer) = post(ask())
    assertFalse(answer.contains(source.vault.value.identity!!.privateKey))
    assertFalse(answer.contains("Lakeview"))
  }

  @Test fun aPhoneWithTheHouseholdKeyButNoPinnedDeviceKeyGetsNothing() {
    // Posing as the Portal: right id, announced address, valid household header, its own key.
    val phoneKey = HomeLinkIdentity.generate(random)
    val (status, text) = post(ask(device = phoneKey))
    assertEquals(403, status)
    assertTrue(text.contains("not_pinned"))
    assertFalse(text.contains("\"s\""))
  }

  @Test fun aPullCantBeReSignedWithAnotherEphemeralKey() {
    val good = ask()
    val swapped = JSONObject(good.body).put("eph", HomeLinkEphemeral.generate(random).publicKey).toString()
    val forged = HomeLinkSyncWire.parsePull(swapped)!!
    assertNull(HomeLinkSyncWire.admit(forged, responderRoster, responderKid, now))
    assertNotNull(HomeLinkSyncWire.admit(good.pull, responderRoster, responderKid, now))
  }

  @Test fun anAnswerFromAnUnpinnedKeyOrTamperedCiphertextIsDropped() {
    val asking = ask()
    val (_, answer) = post(asking)
    assertNull(HomeLinkSyncWire.receive(asking, answer, PortalRoster(), requesterKid))
    val o = JSONObject(answer)
    val s = o.getString("s")
    val flipped = s.substring(0, s.length - 1) + (if (s.last() == '0') '1' else '0')
    assertNull(HomeLinkSyncWire.receive(asking, o.put("s", flipped).toString(), requesterRoster, requesterKid))
    // A phone answering in the responder's place signs with its own key: not pinned.
    val phone = HomeLinkIdentity.generate(random)
    val fake = HomeLinkSyncWire.give(phone, asking.pull, HomeLinkVault(), random)
    assertNull(HomeLinkSyncWire.receive(asking, fake, requesterRoster, requesterKid))
  }

  @Test fun ourOwnKeyIsNeverAPeer() {
    val selfRoster = PortalRoster().pin(HomeLinkPeer("Me", requesterDevice.publicKey, requester), "other", now)!!
    val asking = ask()
    val selfAnswer = HomeLinkSyncWire.give(requesterDevice, asking.pull, HomeLinkVault(), random)
    assertNull(HomeLinkSyncWire.receive(asking, selfAnswer, selfRoster, requesterKid))
    assertNull(HomeLinkSyncWire.admit(asking.pull, selfRoster, requesterKid, now))
  }

  @Test fun aStalePullIsRefused() {
    val stale = ask(ts = now - HomeLinkSyncWire.FRESH_MS - 1)
    assertNull(HomeLinkSyncWire.admit(stale.pull, responderRoster, responderKid, now))
  }

  @Test fun anUnpinnedPortalIsRefusedAfterRemoval() {
    responderRoster = responderRoster.unpin(requesterKid)
    assertEquals(403, post(ask()).first)
  }

  /** A pin is bound to the household id it was pinned under: the same key calling as another Portal gets nothing. */
  @Test fun aPinnedKeyCallingUnderAnotherDeviceIdIsRefused() {
    announced = announced + ("kitchen-1" to "10.9.8.22")
    val (status, body) = post(ask(), from = "kitchen-1", addr = "10.9.8.22")
    assertEquals(403, status)
    assertTrue(body.contains("device_mismatch"))
    assertTrue(pulledBack.isEmpty())
  }

  @Test fun aDifferentVaultIsPulledBackButAPullBackIsNot() {
    post(ask())
    assertEquals(listOf(requester), pulledBack)
    post(ask(back = true))
    post(ask(digest = HomeLinkSyncWire.digest(source.vault.value, key)))
    assertEquals(listOf(requester), pulledBack)
  }

  @Test fun theDigestChangesWithTheVaultAndNeedsTheKey() {
    val v = source.vault.value
    assertEquals(HomeLinkSyncWire.digest(v, key), HomeLinkSyncWire.digest(v, key))
    assertNotEquals(HomeLinkSyncWire.digest(v, key), HomeLinkSyncWire.digest(v.remove(v.book.links.single().id), key))
    assertNotEquals(HomeLinkSyncWire.digest(v, key), HomeLinkSyncWire.digest(v, ByteArray(32)))
  }

  @Test fun aRemovalTravelsAndWipesTheKeyOnThePullingPortal() {
    val puller = HomeLinkRepository(MemorySecureStore(), random, { now }, {})
    val asking = ask()
    puller.merge(HomeLinkSyncWire.receive(asking, post(asking).second, requesterRoster, requesterKid)!!)
    val gone = source.vault.value.book.links.single()
    source.remove(gone.id)
    val again = ask()
    puller.merge(HomeLinkSyncWire.receive(again, post(again).second, requesterRoster, requesterKid)!!)
    assertTrue(puller.vault.value.book.links.isEmpty())
    assertFalse(HomeLinkVaultCodec.encode(puller.vault.value).contains(gone.publicKey))
  }

  @Test fun phonesUnknownPeersAndWrongAddressesAreRefusedBeforeThePull() {
    val phone = CallPhoneId.of("token")
    announced = announced + (phone to host)
    assertEquals(403, post(ask(), from = phone).first)
    assertEquals(403, post(ask(), from = "kitchen-2").first)
    assertEquals(403, post(ask(), addr = "10.9.8.99").first)
    assertTrue(logs.none { it.contains("priv") || it.contains("Lakeview") })
  }

  @Test fun aReplayedRequestIsRefused() {
    val asking = ask()
    val header = HomeLinkSyncWire.requestHeader(key, requester, asking.pull.nonce, now, asking.body)!!
    fun send() = route.handle(FleetHttpServer.Request("POST", HomeLinkSyncWire.PATH, "", mapOf(HomeLinkSyncWire.HEADER to header),
        ByteArrayInputStream(asking.body.toByteArray()), asking.body.length, remoteAddress = host)).status
    assertEquals(200, send())
    assertEquals(401, send())
  }

  @Test fun malformedPullsDecodeToNull() {
    val o = JSONObject(ask().body)
    for (field in listOf("kid", "eph", "n", "ts", "d", "back", "sig")) {
      assertNull(field, HomeLinkSyncWire.parsePull(JSONObject(o.toString()).apply { remove(field) }.toString()))
    }
    assertNull(HomeLinkSyncWire.parsePull(JSONObject(o.toString()).put("v", 1).toString()))
    assertNull(HomeLinkSyncWire.parsePull(JSONObject(o.toString()).put("eph", "garbage").toString()))
    assertNull(HomeLinkSyncWire.parsePull("not json"))
  }

  @Test fun onlyPortalsSignallingFromTheirAnnouncedAddressAreTrusted() {
    assertTrue(HomeLinkSyncPolicy.trusts(requester, host, host))
    assertFalse(HomeLinkSyncPolicy.trusts(requester, host, null))
    assertFalse(HomeLinkSyncPolicy.trusts(requester, host, "10.0.0.1"))
    assertFalse(HomeLinkSyncPolicy.trusts(CallPhoneId.of("t"), host, host))
  }
}
