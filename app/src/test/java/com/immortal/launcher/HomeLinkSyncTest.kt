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

/** Sharing the vault between this home's Portals: sealed per request, Portals only, removals included. */
class HomeLinkSyncTest {
  private val random = SecureRandom()
  private val key = ByteArray(32) { (it * 5).toByte() }
  private val requester = "living-1"
  private val host = "10.9.8.21"
  private val now = 1_700_000_000_000L
  private val perms = HomeLinkPermissions.defaults(listOf("Kitchen"), emptySet())
  private val logs = ArrayList<String>()
  private val source = HomeLinkRepository(MemorySecureStore(), random, { now }, {})
  private var announced = mapOf(requester to host)
  private val route = HomeLinkSyncRoute({ key }, { "family-1" }, { now }, HouseholdMessage.ReplayGuard(), { announced[it] },
      { source.vault.value }, { source.merge(it) }, random, { logs += it })

  private fun vault(): HomeLinkVault {
    val r = HomeLinkRepository(MemorySecureStore(), random, { now }, {})
    r.identity()
    r.link(HomeLinkPeer("Lakeview", HomeLinkIdentity.generate(random).publicKey), perms)
    return r.vault.value
  }

  private fun post(v: HomeLinkVault, from: String = requester, addr: String = host, nonce: String = "n1"): Pair<Int, String> {
    val body = HomeLinkSyncWire.seal(key, from, nonce, HomeLinkSyncWire.REQUEST, v, random)!!
    val header = HomeLinkSyncWire.requestHeader(key, from, nonce, now, body)!!
    val req = FleetHttpServer.Request("POST", HomeLinkSyncWire.PATH, "", mapOf(HomeLinkSyncWire.HEADER to header),
        ByteArrayInputStream(body.toByteArray()), body.length, remoteAddress = addr)
    val resp = route.handle(req)
    return resp.status to FleetReq.text(resp)
  }

  @Test fun sealedVaultOpensOnlyForItsRequesterNonceAndDirection() {
    val v = vault()
    val s = HomeLinkSyncWire.seal(key, requester, "n1", HomeLinkSyncWire.REQUEST, v, random)!!
    assertEquals(v, HomeLinkSyncWire.open(key, requester, "n1", HomeLinkSyncWire.REQUEST, s))
    assertNull(HomeLinkSyncWire.open(key, "kitchen-2", "n1", HomeLinkSyncWire.REQUEST, s))
    assertNull(HomeLinkSyncWire.open(key, requester, "n2", HomeLinkSyncWire.REQUEST, s))
    assertNull(HomeLinkSyncWire.open(key, requester, "n1", HomeLinkSyncWire.RESPONSE, s))
    assertNull(HomeLinkSyncWire.open(ByteArray(32), requester, "n1", HomeLinkSyncWire.REQUEST, s))
  }

  @Test fun theWireNeverCarriesThePrivateKeyOrNamesInTheClear() {
    val v = vault()
    val s = HomeLinkSyncWire.seal(key, requester, "n1", HomeLinkSyncWire.REQUEST, v, random)!!
    assertFalse(s.contains(v.identity!!.privateKey))
    assertFalse(s.contains("Lakeview"))
  }

  @Test fun theDigestChangesWithTheVaultAndNeedsTheKey() {
    val v = vault()
    assertEquals(HomeLinkSyncWire.digest(v, key), HomeLinkSyncWire.digest(v, key))
    assertNotEquals(HomeLinkSyncWire.digest(v, key), HomeLinkSyncWire.digest(v.remove(v.book.links.single().id), key))
    assertNotEquals(HomeLinkSyncWire.digest(v, key), HomeLinkSyncWire.digest(v, ByteArray(32)))
  }

  @Test fun aPortalExchangesVaultsAndBothEndUpWithTheUnion() {
    source.link(HomeLinkPeer("Priya & June’s", HomeLinkIdentity.generate(random).publicKey), perms)
    val mine = vault()
    val (status, text) = post(mine)
    assertEquals(200, status)
    val o = JSONObject(text)
    val payload = o.getString("payload")
    assertTrue(HomeLinkSyncWire.verifyResponse(key, "n1", payload, o.getString("sig")))
    val theirs = HomeLinkSyncWire.open(key, requester, "n1", HomeLinkSyncWire.RESPONSE, payload)!!
    assertEquals(2, theirs.book.links.size)
    assertEquals(mine.identity, source.vault.value.identity)
    assertEquals(mine.merge(theirs).book, theirs.book)
  }

  @Test fun aRemovalTravelsAndWipesTheKeyOnTheOtherPortal() {
    val mine = vault()
    post(mine, nonce = "a")
    val gone = mine.book.links.single()
    post(mine.remove(gone.id), nonce = "b")
    assertTrue(source.vault.value.book.links.isEmpty())
    assertFalse(HomeLinkVaultCodec.encode(source.vault.value).contains(gone.publicKey))
  }

  @Test fun phonesAreRefusedEvenWithTheHouseholdKey() {
    val phone = CallPhoneId.of("token")
    announced = announced + (phone to host)
    assertEquals(403, post(vault(), from = phone).first)
    assertEquals(HomeLinkVault(), source.vault.value)
  }

  @Test fun unknownPeersAndWrongAddressesAreRefused() {
    assertEquals(403, post(vault(), from = "kitchen-2").first)
    assertEquals(403, post(vault(), addr = "10.9.8.99").first)
    assertEquals(HomeLinkVault(), source.vault.value)
    assertTrue(logs.all { !it.contains("priv") })
  }

  @Test fun aReplayedRequestIsRefused() {
    val v = vault()
    val body = HomeLinkSyncWire.seal(key, requester, "r1", HomeLinkSyncWire.REQUEST, v, random)!!
    val header = HomeLinkSyncWire.requestHeader(key, requester, "r1", now, body)!!
    fun send() = route.handle(FleetHttpServer.Request("POST", HomeLinkSyncWire.PATH, "", mapOf(HomeLinkSyncWire.HEADER to header),
        ByteArrayInputStream(body.toByteArray()), body.length, remoteAddress = host)).status
    assertEquals(200, send())
    assertEquals(401, send())
  }

  @Test fun onlyPortalsSignallingFromTheirAnnouncedAddressAreTrusted() {
    assertTrue(HomeLinkSyncPolicy.trusts(requester, host, host))
    assertFalse(HomeLinkSyncPolicy.trusts(requester, host, null))
    assertFalse(HomeLinkSyncPolicy.trusts(requester, host, "10.0.0.1"))
    assertFalse(HomeLinkSyncPolicy.trusts(CallPhoneId.of("t"), host, host))
  }
}
