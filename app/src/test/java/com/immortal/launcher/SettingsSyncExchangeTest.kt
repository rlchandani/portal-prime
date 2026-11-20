/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */
package com.immortal.launcher

import com.immortal.launcher.SyncRig.Companion.EMPTY
import com.immortal.launcher.SyncRig.Companion.HI
import com.immortal.launcher.SyncRig.Companion.LO
import com.immortal.launcher.SyncRig.Companion.PEER_HOST
import com.immortal.launcher.SyncRig.Companion.rec
import com.immortal.launcher.SyncRig.Companion.synced
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Digest gossip, catch-up exchanges, the joiner rate limit and both signed ends, on fakes. */
class SettingsSyncExchangeTest {
  @Test fun gossipAnnouncesTheDigestEveryMinuteAfterTheNetworkSettles() {
    val r = SyncRig().apply { host.stored = synced(rec("chime.volume", 70, 2)) }.start()
    r.core.startGossip()
    assertEquals(listOf(4_000L), r.timers.posted.map { it.first })
    r.timers.fire()
    val sig = r.sentOf(SettingsSyncWire.SIGNAL_TYPE).single()
    assertEquals(r.digest(), sig.getString("d"))
    assertEquals(FakeSyncHost.PORT, sig.getInt("p"))
    assertEquals(listOf(SettingsSyncCore.GOSSIP_MS), r.timers.posted.map { it.first })
    r.core.networkAvailable()
    r.timers.fire()
    assertEquals(3, r.sentOf(SettingsSyncWire.SIGNAL_TYPE).size)
  }

  @Test fun anEqualDigestIsQuietAndADifferentOneExchangesFullState() {
    val r = SyncRig(mapOf("chime.volume" to 50)).apply { host.stored = synced(rec("chime.volume", 50, 3)) }.start()
    r.signal(HI, r.digest())
    assertTrue(r.posts.isEmpty())
    r.peerReply = { r.payload("Living", false, rec("chime.volume", 65, 4), rec("theme", "dark", 1)) }
    r.signal(HI, "0123456789ab")
    val (host, port, body) = r.posts.single()
    assertEquals(PEER_HOST to FakeSyncHost.PORT, host to port)
    assertEquals(listOf("chime.volume"), SettingsSyncWire.parsePayload(body)!!.records.map { it.key })
    assertEquals(65, r.settings.values["chime.volume"])
    assertEquals("dark", r.settings.values["theme"])
    assertEquals(1L, r.counter("exchangesStarted"))
    assertTrue(r.counter("httpBytesReceived") > 0)
  }

  @Test fun aFailedOrGarbledExchangeIsCountedAndChangesNothing() {
    val r = SyncRig().apply { host.stored = synced() }.start()
    r.peerReply = { error("refused") }
    r.signal(HI, "0123456789ab")
    r.peerReply = { "{not json" }
    r.signal(HI, "0123456789ab")
    assertEquals(2L, r.counter("exchangesFailed"))
    assertTrue(r.log.lines.any { it == "i exchange with $HI failed: IllegalStateException" })
    assertEquals(r.settings.defaults, r.settings.values)
  }

  @Test fun aSignalToAPrivilegedPortIsIgnored() {
    val r = SyncRig().apply { host.stored = synced() }.start()
    r.signal(HI, "0123456789ab", port = 22)
    assertTrue(r.posts.isEmpty())
    assertEquals(1L, r.counter("signalsReceived"))
  }

  @Test fun aJoiningPortalIsOfferedTheHouseholdAtMostEveryTenMinutes() {
    val r = SyncRig().apply { host.stored = synced(rec("theme", "dark", 1)) }.start()
    r.signal(LO, EMPTY, joining = true)
    r.signal(LO, EMPTY, joining = true)
    assertEquals(1, r.posts.size)
    r.now += SettingsSyncCore.JOINER_PUSH_MS
    r.signal(LO, EMPTY, joining = true)
    assertEquals(2, r.posts.size)
    // Claimed ids can't grow the rate-limit memory without bound.
    (0 until SettingsSyncCore.MAX_RATE_ENTRIES).forEach { r.signal("j$it", EMPTY, joining = true) }
    assertTrue(r.core.joinerPushedAt.size <= SettingsSyncCore.MAX_RATE_ENTRIES)
  }

  @Test fun whileJoiningAnExchangeFillsTheChoiceAndTheSameDigestIsNotFetchedAgain() {
    val r = SyncRig(mapOf("chime.volume" to 40)).start()
    val household = r.payload("Living", false, rec("chime.volume", 55, 3))
    r.peerReply = { household }
    r.signal(HI, "0123456789ab")
    assertEquals("Living", r.core.ledger.pending?.fromName)
    val pendingDigest = SettingsSyncEngine.digest(r.core.ledger.pending!!.records)
    r.signal(HI, pendingDigest)
    assertEquals(1, r.posts.size)
    assertTrue("our side says joining and carries nothing", SettingsSyncWire.parsePayload(r.posts.single().third)!!.let { it.joining && it.records.isEmpty() })
  }

  @Test fun aServedExchangeMergesTheirsAndRepliesWithOursWithoutSecrets() {
    val r = SyncRig(mapOf("chime.volume" to 50)).apply { host.stored = synced(rec("chime.volume", 50, 3)) }.start()
    val reply = r.core.handlePeer(HI, r.payload("Living", false, rec("chime.volume", 65, 4), rec("mqtt.password", "hunter2", 4),
        rec("fleet.name", "Hacked", 4)))
    val ours = SettingsSyncWire.parsePayload(reply)!!
    assertEquals(listOf("chime.volume"), ours.records.map { it.key })
    assertEquals(65, r.settings.values["chime.volume"])
    assertTrue(r.settings.applied.none { it.containsKey("mqtt.password") || it.containsKey("fleet.name") })
    assertEquals(2L, r.counter("recordsRefused"))
    assertNull(r.core.handlePeer(HI, "{not json"))
    // A joiner's records are never taken as the household's.
    r.core.handlePeer(LO, r.payload("Mini", true, rec("chime.volume", 1, 99, LO)))
    assertEquals(65, r.settings.values["chime.volume"])
  }

  @Test fun aPeerExchangeThatCannotReachTheWorkerAnswersNothing() {
    val r = SyncRig().start()
    val stuck = object : java.util.concurrent.AbstractExecutorService() {
      override fun execute(command: Runnable) = Unit
      override fun <T : Any?> submit(task: java.util.concurrent.Callable<T>): java.util.concurrent.Future<T> =
          java.util.concurrent.CompletableFuture<T>().apply { completeExceptionally(java.util.concurrent.TimeoutException()) }
      override fun shutdown() = Unit
      override fun shutdownNow(): MutableList<Runnable> = mutableListOf()
      override fun isShutdown() = false
      override fun isTerminated() = false
      override fun awaitTermination(timeout: Long, unit: java.util.concurrent.TimeUnit) = true
    }
    val core = SettingsSyncCore(r.settings, r.host, { _, _ -> true }, { _, _, _ -> null }, r.timers, r.log, { 0L }, stuck, stuck,
        r.status, java.util.concurrent.atomic.AtomicBoolean())
    assertNull(core.handlePeer(HI, r.payload("Living", false)))
    assertTrue(r.log.lines.any { it.startsWith("w peer exchange from $HI timed out") })
  }

  @Test fun theStatusCarriesDigestFingerprintAndCounters() {
    val r = SyncRig(mapOf("theme" to "dark")).apply { host.stored = synced(rec("theme", "dark", 1)) }.start()
    r.signal(HI, r.digest())
    val s = r.core.statusJson()
    assertEquals(r.digest(), s.getString("digest"))
    assertEquals(HouseholdKey.fingerprint(r.host.key!!), s.getString("keyFingerprint"))
    assertEquals("SYNCED", s.getString("phase"))
    assertEquals(1, s.getJSONObject("counters").getInt("signalsReceived"))
    assertEquals("dark", r.core.valuesJson().getString("theme"))
    val before = SettingsSyncCore.statusJson(SettingsSyncStatus(), SyncLedger(), null, emptyMap())
    assertEquals(false, before.getBoolean("available"))
    assertEquals(SyncRig.EMPTY, before.getString("digest"))
    assertEquals(0, before.getJSONObject("counters").length())
  }

  // --- the signed ends ------------------------------------------------------------------------

  private val key = ByteArray(HouseholdKey.SIZE) { 7 }
  private val now = 1_800_000_000_000L

  private fun client(transport: SyncTransport, key: ByteArray? = this.key) =
      SyncPeerClient({ key }, { HI }, transport, { now }, { "nonce-1" })

  @Test fun theClientOnlyTalksToPrivatePeersAndVerifiesTheReply() {
    var calls = 0
    val signed = { payload: String -> JSONObject().put("payload", payload).put("sig", SettingsSyncWire.signResponse(key, "nonce-1", payload)).toString() }
    assertNull(client({ _, _, _, _, _ -> calls++; null }).post("example.com", 8765, "{}"))
    assertNull(client({ _, _, _, _, _ -> calls++; null }, key = null).post(PEER_HOST, 8765, "{}"))
    assertEquals(0, calls)
    assertNull(client({ _, _, _, _, _ -> null }).post(PEER_HOST, 8765, "{}"))
    assertEquals("{\"ok\":1}", client({ _, _, path, header, _ ->
      assertEquals(SettingsSyncNet.PATH, path); assertTrue(header.isNotBlank()); signed("{\"ok\":1}") }).post(PEER_HOST, 8765, "{}"))
    assertNull("a reply signed for another nonce is refused",
        client({ _, _, _, _, _ -> JSONObject().put("payload", "x").put("sig", SettingsSyncWire.signResponse(key, "other", "x")).toString() })
            .post(PEER_HOST, 8765, "{}"))
  }

  private fun route(handle: (String, String) -> String? = { _, _ -> "{\"mine\":1}" }, routeKey: ByteArray? = key) =
      SyncPeerRoute({ routeKey }, { FakeSyncHost.SELF }, { now }, HouseholdMessage.ReplayGuard(), handle)

  private fun call(route: SyncPeerRoute, body: String, header: String?, method: String = "POST", length: Int = body.length): Pair<Int, JSONObject> {
    val headers = header?.let { mapOf(SettingsSyncWire.HEADER to it) } ?: emptyMap()
    val req = FleetHttpServer.Request(method, SettingsSyncNet.PATH, "", headers, ByteArrayInputStream(body.toByteArray()), length)
    return route.handle(req).use { resp ->
      val out = ByteArrayOutputStream().also { o -> resp.jsonBytes?.let(o::write) }
      resp.status to JSONObject(out.toString("UTF-8"))
    }
  }

  @Test fun theRouteGatesMethodSizeKeyAndSignatureBeforeReadingTheBody() {
    val good = { body: String, nonce: String -> SettingsSyncWire.requestHeader(key, HI, nonce, now, body)!! }
    val r = route()
    assertEquals(405, call(r, "{}", null, method = "GET").first)
    assertEquals(413, call(r, "", null, length = 0).first)
    assertEquals(413, call(r, "{}", null, length = SettingsSyncNet.MAX_BODY + 1).first)
    assertEquals(503, call(route(routeKey = null), "{}", good("{}", "a")).first)
    assertEquals(401, call(r, "{}", null).first)
    assertEquals(401, call(r, "{}", SettingsSyncWire.requestHeader(ByteArray(HouseholdKey.SIZE), HI, "b", now, "{}")).first)
    assertEquals("a body swapped after signing", 401, call(r, "{} ", good("{}", "c")).first)
    val (status, json) = call(r, "{}", good("{}", "d"))
    assertEquals(200, status)
    assertTrue(SettingsSyncWire.verifyResponse(key, "d", json.getString("payload"), json.getString("sig")))
    assertEquals("a replay", 401, call(r, "{}", good("{}", "d")).first)
    assertEquals(503, call(route({ _, _ -> null }), "{}", good("{}", "e")).first)
    assertEquals("our own id", 401, call(r, "{}", SettingsSyncWire.requestHeader(key, FakeSyncHost.SELF, "f", now, "{}")).first)
  }
}
