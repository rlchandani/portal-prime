/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */
package com.immortal.launcher

import java.io.IOException
import java.net.SocketTimeoutException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

/** Scripted protocol fixtures only; these tests do not establish live-server compatibility. */
class MaMusicClientTest {
  private class Fake : MaMusicClient.Transport {
    val received = LinkedBlockingQueue<Any>()
    val sent = LinkedBlockingQueue<JSONObject>()
    @Volatile var closed = false
    @Volatile var worker: Thread? = null
    private var readFence: CountDownLatch? = null
    var connects = 0
    var failConnect = false
    override fun connect(timeoutMs: Int): Boolean {
      worker = Thread.currentThread()
      connects++
      if (failConnect) return false
      received.add("""{"server_version":"2.10.7","schema_version":65}""")
      return true
    }
    override fun sendText(text: String) { if (closed) throw IOException(); sent.add(JSONObject(text)) }
    override fun readText(): String? {
      readFence?.countDown()
      readFence = null
      if (closed) return null
      val next = received.poll(10, TimeUnit.MILLISECONDS) ?: throw SocketTimeoutException()
      if (next is CountDownLatch) {
        readFence = next
        throw SocketTimeoutException()
      }
      return if (next == "CLOSE") null else next as String
    }
    override fun close() { closed = true; received.offer("CLOSE") }
    fun next(command: String): JSONObject {
      val value = sent.poll(2, TimeUnit.SECONDS) ?: throw AssertionError("No request: $command")
      assertEquals(command, value.getString("command"))
      return value
    }
    fun reply(request: JSONObject, result: Any?, partial: Boolean = false) {
      received.add(JSONObject().put("message_id", request.getString("message_id"))
          .put("result", result ?: JSONObject.NULL).put("partial", partial).toString())
    }
    /** Wait until queued input and a subsequent worker scheduling cycle have completed. */
    fun cycle() {
      val fence = CountDownLatch(1)
      received.add(fence)
      assertTrue("Worker did not complete an idle cycle", fence.await(2, TimeUnit.SECONDS))
    }
  }

  private fun player(id: String = "family") = JSONObject()
      .put("player_id", id).put("name", id).put("available", true)
      .put("playback_state", "playing").put("supported_features", JSONArray(listOf("pause", "volume_set")))
      .put("active_source", "shared").put("synced_to", JSONObject.NULL).put("active_group", JSONObject.NULL)
      .put("group_members", JSONArray()).put("static_group_members", JSONArray())
      .put("can_group_with", JSONArray()).put("source_list", JSONArray())
      .put("volume_control", "native").put("volume_level", 30).put("volume_muted", false)
      .put("elapsed_time", 1).put("elapsed_time_last_updated", 1_700_000_000)
      .put("current_media", JSONObject.NULL)

  private fun item(id: String = "one") = JSONObject().put("queue_id", "shared")
      .put("queue_item_id", id).put("name", id).put("duration", 180).put("available", true)
      .put("media_item", JSONObject().put("name", id).put("media_type", "track").put("uri", "library://track/$id"))

  private fun queue(state: String = "playing", current: String = "one") = JSONObject()
      .put("queue_id", "shared").put("display_name", "Shared").put("active", true).put("available", true)
      .put("items", 2).put("state", state).put("elapsed_time", 1)
      .put("elapsed_time_last_updated", 1_700_000_000).put("playback_speed", 1)
      .put("current_index", 0).put("shuffle_enabled", false).put("repeat_mode", "off")
      .put("current_item", item(current)).put("next_item", item("two"))

  private val limits = MaMusicClient.Limits(pollMs = 10, requestTimeoutMs = 500,
      refreshCoalesceMs = 30, progressRefreshMs = 100, reconnectInitialMs = 20, reconnectMaxMs = 80)

  private class Seen {
    val selections = LinkedBlockingQueue<Pair<Long, MaMusicProtocol.Selection>>()
    val connections = LinkedBlockingQueue<Pair<Long, MusicConnection>>()
    val results = LinkedBlockingQueue<MaMusicClient.CommandResult>()
    fun ready(): Pair<Long, MaMusicProtocol.Selection> {
      val end = System.nanoTime() + TimeUnit.SECONDS.toNanos(2)
      while (System.nanoTime() < end) {
        val value = selections.poll(20, TimeUnit.MILLISECONDS) ?: continue
        if (value.second.snapshot.connection == MusicConnection.READY) return value
      }
      throw AssertionError("No ready selection")
    }
  }

  private fun client(fake: Fake, seen: Seen = Seen()) = MaMusicClient(
      onSelection = { g, s -> seen.selections.add(g to s) },
      onConnection = { g, s -> seen.connections.add(g to s) },
      transportFactory = { _, _ -> fake }, limits = limits,
      elapsedMs = { TimeUnit.NANOSECONDS.toMillis(System.nanoTime()) }, unixMs = { 1_700_000_000_000 })

  private fun config(player: String = "ma:family") = MaMusicClient.Config("ma.example", 8095, "fixture-token", player)

  private fun auth(fake: Fake) {
    val auth = fake.next("auth")
    fake.reply(auth, JSONObject().put("authenticated", true))
  }

  private fun refresh(fake: Fake, state: String = "playing", selected: String = "family") {
    fake.reply(fake.next("players/all"), JSONArray().put(player(selected)))
    val active = fake.next("player_queues/get_active_queue")
    assertEquals(selected, active.getJSONObject("args").getString("player_id"))
    fake.reply(active, queue(state))
    val items = fake.next("player_queues/items")
    assertEquals("shared", items.getJSONObject("args").getString("queue_id"))
    assertEquals(500, items.getJSONObject("args").getInt("limit"))
    fake.reply(items, JSONArray().put(item()).put(item("two")))
  }

  @Test fun correlatesAuthAndRequiresExplicitTrueBeforeLoadingOnSameSocket() {
    val fake = Fake(); val seen = Seen(); val client = client(fake, seen)
    try {
      val generation = client.config(config())
      val request = fake.next("auth")
      fake.received.add("""{"message_id":"unrelated","result":{"authenticated":true}}""")
      assertNull(fake.sent.poll(50, TimeUnit.MILLISECONDS))
      fake.reply(request, JSONObject().put("authenticated", true))
      refresh(fake)
      val ready = seen.ready()
      assertEquals(generation, ready.first)
      assertEquals("shared", ready.second.queueId)
      assertEquals("one", ready.second.snapshot.track?.id)
      assertEquals(1, fake.connects)
    } finally { client.close() }
  }

  @Test fun falseOrMissingAuthenticationNeverLoadsPlayersOrBecomesReady() {
    for (result in listOf(JSONObject().put("authenticated", false), JSONObject().put("success", true))) {
      val fake = Fake(); val seen = Seen(); val client = client(fake, seen)
      try {
        client.config(config())
        fake.reply(fake.next("auth"), result)
        val failure = seen.selections.poll(2, TimeUnit.SECONDS)!!
        assertNotEquals(MusicConnection.READY, failure.second.snapshot.connection)
        assertTrue(fake.closed)
        assertNull(fake.sent.poll(50, TimeUnit.MILLISECONDS))
      } finally { client.close() }
    }
  }

  @Test fun commandAckDoesNotChangePlaybackAndRefreshIsAuthoritative() {
    val fake = Fake(); val seen = Seen(); val client = client(fake, seen)
    try {
      val generation = client.config(config()); auth(fake); refresh(fake); seen.ready()
      assertTrue(client.command(MaMusicClient.Action.Transport(MusicCommand.PAUSE), generation,
          callback = { seen.results.add(it) }))
      val request = fake.next("player_queues/pause")
      assertEquals("shared", request.getJSONObject("args").getString("queue_id"))
      fake.reply(request, JSONObject.NULL)
      val ack = seen.results.poll(2, TimeUnit.SECONDS)!!
      assertEquals(MaMusicClient.Outcome.ACKNOWLEDGED, ack.outcome)
      assertNull(ack.selection)
      assertNull(seen.selections.poll(20, TimeUnit.MILLISECONDS))
      refresh(fake, "paused")
      assertEquals(PlaybackState.PAUSED, seen.ready().second.snapshot.playback)
      val observed = seen.results.poll(2, TimeUnit.SECONDS)!!
      assertEquals(MaMusicClient.Outcome.REFRESHED, observed.outcome)
      assertEquals(PlaybackState.PAUSED, observed.selection!!.snapshot.playback)
      assertEquals(ack.messageId, observed.messageId)
      assertEquals(1, fake.connects)
    } finally { client.close() }
  }

  @Test fun selectCancelsOldRepliesEvenWhenSwitchingAwayAndBackAndRejectsOldCommandGeneration() {
    val fake = Fake(); val seen = Seen(); val client = client(fake, seen)
    try {
      val first = client.config(config()); auth(fake)
      val old = fake.next("players/all")
      client.select("ma:other")
      val current = client.select("ma:family")
      fake.reply(old, JSONArray().put(player()))
      refresh(fake)
      assertEquals(current, seen.ready().first)
      assertFalse(client.command(MaMusicClient.Action.Transport(MusicCommand.PAUSE), first) {})
      assertNull(seen.selections.poll(30, TimeUnit.MILLISECONDS))
      assertEquals(1, fake.connects)
    } finally { client.close() }
  }

  @Test fun configReplacementClosesOldSocketAndSuppressesOldResults() {
    val old = Fake(); val fresh = Fake(); val seen = Seen()
    val client = MaMusicClient(onSelection = { g, s -> seen.selections.add(g to s) },
        transportFactory = { host, _ -> if (host == "old.example") old else fresh }, limits = limits)
    try {
      client.config(MaMusicClient.Config("old.example", 8095, "fixture-token", "ma:family"))
      val auth = old.next("auth")
      val current = client.config(config())
      old.reply(auth, JSONObject().put("authenticated", true))
      auth(fresh); refresh(fresh)
      assertTrue(old.closed)
      assertEquals(current, seen.ready().first)
      assertNull(old.sent.poll(30, TimeUnit.MILLISECONDS))
    } finally { client.close() }
  }

  @Test fun assemblesPartialItemsAndIgnoresUncorrelatedReply() {
    val fake = Fake(); val seen = Seen(); val client = client(fake, seen)
    try {
      client.config(config()); auth(fake)
      fake.reply(fake.next("players/all"), JSONArray().put(player()))
      fake.reply(fake.next("player_queues/get_active_queue"), queue())
      val request = fake.next("player_queues/items")
      fake.received.add("""{"message_id":"foreign","result":[]}""")
      fake.reply(request, JSONArray().put(item()), partial = true)
      assertNull(seen.selections.poll(30, TimeUnit.MILLISECONDS))
      fake.reply(request, JSONArray().put(item("two")))
      assertEquals(listOf("one", "two"), seen.ready().second.snapshot.queue.map { it.id })
    } finally { client.close() }
  }

  @Test fun progressTicksUseCoalescedQueueOnlyRefreshAndStructuralEventsLoadFullState() {
    val fake = Fake(); val seen = Seen(); val client = client(fake, seen)
    try {
      client.config(config()); auth(fake); refresh(fake); seen.ready()
      repeat(100) { fake.received.add("""{"event":"queue_time_updated","object_id":"shared","data":5}""") }
      val request = fake.next("player_queues/get")
      fake.reply(request, queue())
      seen.ready()
      assertNull(fake.sent.poll(40, TimeUnit.MILLISECONDS))
      repeat(20) { fake.received.add("""{"event":"queue_updated","object_id":"shared"}""") }
      refresh(fake)
      seen.ready()
    } finally { client.close() }
  }

  @Test fun timesOutAuthThenReconnectsWithNewIdsAndClosesOnDetach() {
    val sockets = CopyOnWriteArrayList<Fake>(); val seen = Seen()
    val client = MaMusicClient(onSelection = { g, s -> seen.selections.add(g to s) },
        transportFactory = { _, _ -> Fake().also { sockets.add(it) } }, limits = limits.copy(requestTimeoutMs = 80))
    client.config(config())
    try {
      await { sockets.isNotEmpty() }
      val first = sockets.first(); val firstId = first.next("auth").getString("message_id")
      await { sockets.size >= 2 }
      assertTrue(first.closed)
      val second = sockets[1]; val secondAuth = second.next("auth")
      assertNotEquals(firstId, secondAuth.getString("message_id"))
      second.reply(secondAuth, JSONObject().put("authenticated", true)); refresh(second); seen.ready()
      client.close()
      val count = seen.selections.size
      second.received.add("""{"event":"queue_updated","object_id":"shared"}""")
      assertTrue(second.closed)
      assertFalse(client.refresh())
      assertFalse(client.command(MaMusicClient.Action.Transport(MusicCommand.PAUSE), 1) {})
      Thread.sleep(30)
      assertEquals(count, seen.selections.size)
    } finally { client.close() }
  }

  @Test fun reconnectBackoffIsBoundedAndDetachStopsFurtherAttempts() {
    val attempts = CopyOnWriteArrayList<Long>()
    val client = MaMusicClient(transportFactory = { _, _ ->
      attempts.add(System.nanoTime()); Fake().also { it.failConnect = true }
    }, limits = limits)
    client.config(config())
    try {
      await { attempts.size >= 5 }
      val gaps = attempts.zipWithNext { a, b -> TimeUnit.NANOSECONDS.toMillis(b - a) }
      assertTrue(gaps[0] >= 15)
      assertTrue(gaps[1] >= 30)
      assertTrue(gaps[2] >= 65)
      assertTrue(gaps[3] < 300)
      client.close()
      val count = attempts.size
      Thread.sleep(120)
      assertEquals(count, attempts.size)
    } finally { client.close() }
  }

  @Test fun rejectsSecureUrlWithoutDowngradeAndRedactsConfigToken() {
    var opened = false; val seen = Seen()
    val client = MaMusicClient(onSelection = { g, s -> seen.selections.add(g to s) },
        transportFactory = { _, _ -> opened = true; Fake() }, limits = limits)
    try {
      val secure = MaMusicClient.Config("https://ma.example", 443, "fixture-token", "ma:family")
      assertFalse(secure.toString().contains("fixture-token"))
      client.config(secure)
      assertEquals(MusicConnection.INCOMPATIBLE,
          seen.selections.poll(2, TimeUnit.SECONDS)!!.second.snapshot.connection)
      assertFalse(opened)
    } finally { client.close() }
  }


  @Test fun progressObservationsDoNotMakeOldPlayerMappingFreshForCommands() {
    val fake = Fake(); val seen = Seen()
    val clock = java.util.concurrent.atomic.AtomicLong(1000)
    val client = MaMusicClient(onSelection = { g, s -> seen.selections.add(g to s) },
        transportFactory = { _, _ -> fake }, limits = limits.copy(selectionMaxAgeMs = 200),
        elapsedMs = { clock.get() }, unixMs = { 1_700_000_000_000 })
    try {
      val generation = client.config(config()); auth(fake); refresh(fake); seen.ready()
      clock.set(1300)
      fake.received.add("""{"event":"queue_time_updated","object_id":"shared"}""")
      fake.reply(fake.next("player_queues/get"), queue()); seen.ready()
      assertTrue(client.command(MaMusicClient.Action.Transport(MusicCommand.PAUSE), generation) {})
      // Fresh queue progress must not reuse an old player/group/source mapping.
      refresh(fake)
      seen.ready()
      fake.reply(fake.next("player_queues/pause"), JSONObject.NULL)
    } finally { client.close() }
  }

  @Test fun structuralEventDuringRefreshSuppressesOldSourceBatch() {
    val fake = Fake(); val seen = Seen(); val client = client(fake, seen)
    try {
      client.config(config()); auth(fake)
      fake.reply(fake.next("players/all"), JSONArray().put(player()))
      val oldQueue = fake.next("player_queues/get_active_queue")
      fake.received.add("""{"event":"player_updated","object_id":"family"}""")
      fake.reply(oldQueue, queue())
      val items = fake.next("player_queues/items")
      fake.reply(items, JSONArray().put(item()))
      assertNull(seen.selections.poll(20, TimeUnit.MILLISECONDS))
      refresh(fake)
      assertEquals("one", seen.ready().second.snapshot.track?.id)
    } finally { client.close() }
  }

  @Test fun rejectsCommandsWithoutAuthenticatedSelectionAndUnsupportedHello() {
    val fake = Fake(); val seen = Seen(); val client = client(fake, seen)
    try {
      val generation = client.config(config())
      assertFalse(client.command(MaMusicClient.Action.Transport(MusicCommand.PAUSE), generation) {})
      val request = fake.next("auth")
      fake.received.add("""{"server_version":"2.10.7","schema_version":64}""")
      fake.reply(request, JSONObject().put("authenticated", true))
      assertEquals(MusicConnection.INCOMPATIBLE,
          seen.selections.poll(2, TimeUnit.SECONDS)!!.second.snapshot.connection)
      assertTrue(fake.closed)
      assertNull(fake.sent.poll(30, TimeUnit.MILLISECONDS))
    } finally { client.close() }
  }

  @Test fun oversizedPartialPageFailsClosedWithoutPublishingReady() {
    val fake = Fake(); val seen = Seen(); val client = client(fake, seen)
    try {
      client.config(config()); auth(fake)
      fake.reply(fake.next("players/all"), JSONArray().put(player()))
      fake.reply(fake.next("player_queues/get_active_queue"), queue())
      val request = fake.next("player_queues/items")
      fake.reply(request, JSONArray(List(500) { item("$it") }), true)
      fake.reply(request, JSONArray().put(item("overflow")))
      assertEquals(MusicConnection.INCOMPATIBLE,
          seen.selections.poll(2, TimeUnit.SECONDS)!!.second.snapshot.connection)
      assertTrue(fake.closed)
    } finally { client.close() }
  }

  @Test fun explicitCommandErrorNeverAcknowledgesAndKeepsServerDetailsPrivate() {
    val fake = Fake(); val seen = Seen(); val client = client(fake, seen)
    try {
      val generation = client.config(config()); auth(fake); refresh(fake); seen.ready()
      assertTrue(client.command(MaMusicClient.Action.Seek(42_000), generation) { seen.results.add(it) })
      val request = fake.next("player_queues/seek")
      assertEquals(42, request.getJSONObject("args").getInt("position"))
      fake.received.add(JSONObject().put("message_id", request.getString("message_id"))
          .put("error_code", 20).put("details", "private-server-detail").toString())
      val result = seen.results.poll(2, TimeUnit.SECONDS)!!
      assertEquals(MaMusicClient.Outcome.FAILED, result.outcome)
      assertEquals(MusicConnection.AUTH_REQUIRED, result.connection)
      assertFalse(result.toString().contains("private-server-detail"))
      assertNull(seen.results.poll(30, TimeUnit.MILLISECONDS))
      assertTrue(fake.closed)
    } finally { client.close() }
  }

  @Test fun closingWhileConnectIsBlockedCancelsTransportAndNoCallbackStartsAfterDetach() {
    val entered = java.util.concurrent.CountDownLatch(1)
    val released = java.util.concurrent.CountDownLatch(1)
    val exited = java.util.concurrent.CountDownLatch(1)
    val events = java.util.concurrent.atomic.AtomicInteger()
    val worker = java.util.concurrent.atomic.AtomicReference<Thread>()
    val transport = object : MaMusicClient.Transport {
      override fun connect(timeoutMs: Int): Boolean {
        worker.set(Thread.currentThread())
        entered.countDown()
        try { released.await(2, TimeUnit.SECONDS) } finally { exited.countDown() }
        return true
      }
      override fun sendText(text: String) = Unit
      override fun readText(): String? = throw SocketTimeoutException()
      override fun close() { released.countDown() }
    }
    val client = MaMusicClient(onConnection = { _, _ -> events.incrementAndGet() },
        onSelection = { _, _ -> events.incrementAndGet() }, transportFactory = { _, _ -> transport }, limits = limits)
    client.config(config())
    assertTrue(entered.await(2, TimeUnit.SECONDS))
    client.close()
    val count = events.get()
    assertTrue(exited.await(1, TimeUnit.SECONDS))
    worker.get().join(1000)
    assertFalse(worker.get().isAlive)
    assertEquals(count, events.get())
  }


  @Test fun staleCommandAckCannotPublishOrConfirmAnotherSelectedPlayer() {
    val fake = Fake(); val seen = Seen(); val client = client(fake, seen)
    try {
      val oldGeneration = client.config(config()); auth(fake); refresh(fake); seen.ready()
      assertTrue(client.command(MaMusicClient.Action.Transport(MusicCommand.PAUSE), oldGeneration) { seen.results.add(it) })
      val oldCommand = fake.next("player_queues/pause")
      val current = client.select("ma:other")
      fake.reply(oldCommand, JSONObject.NULL)
      refresh(fake, selected = "other")
      val selected = seen.ready()
      assertEquals(current, selected.first)
      assertEquals("ma:other", selected.second.snapshot.sourceId)
      assertNull(seen.results.poll(30, TimeUnit.MILLISECONDS))
      assertEquals(1, fake.connects)
    } finally { client.close() }
  }

  @Test fun mailboxIsBoundedWhileACommandWaitsAndInvalidTypedActionsAreRejected() {
    val fake = Fake(); val seen = Seen(); val client = client(fake, seen)
    try {
      val generation = client.config(config()); auth(fake); refresh(fake); seen.ready()
      assertTrue(client.command(MaMusicClient.Action.Volume("ma:family", 42), generation) { seen.results.add(it) })
      val volume = fake.next("players/cmd/volume_set")
      assertEquals("family", volume.getJSONObject("args").getString("player_id"))
      assertEquals(42, volume.getJSONObject("args").getInt("volume_level"))
      var accepted = 0
      repeat(64) { if (client.command(MaMusicClient.Action.Transport(MusicCommand.PAUSE), generation) {}) accepted++ }
      assertEquals(16, accepted)
      fake.reply(volume, JSONObject.NULL)
      assertEquals(MaMusicClient.Outcome.ACKNOWLEDGED, seen.results.poll(2, TimeUnit.SECONDS)!!.outcome)
    } finally { client.close() }
    val fresh = Fake(); val rejected = Seen(); val next = client(fresh, rejected)
    try {
      val generation = next.config(config()); auth(fresh); refresh(fresh); rejected.ready()
      assertTrue(next.command(MaMusicClient.Action.Seek(-1), generation) { rejected.results.add(it) })
      assertEquals(MaMusicClient.Outcome.REJECTED, rejected.results.poll(2, TimeUnit.SECONDS)!!.outcome)
      assertNull(fresh.sent.poll(30, TimeUnit.MILLISECONDS))
    } finally { next.close() }
  }

  @Test fun missingTokenRequiresAuthenticationWithoutOpeningTransport() {
    val opened = java.util.concurrent.atomic.AtomicBoolean()
    val seen = Seen()
    val client = MaMusicClient(onSelection = { g, s -> seen.selections.add(g to s) },
        transportFactory = { _, _ -> opened.set(true); Fake() }, limits = limits)
    try {
      client.config(MaMusicClient.Config("ma.example", 8095, "", "family"))
      assertEquals(MusicConnection.AUTH_REQUIRED,
          seen.selections.poll(2, TimeUnit.SECONDS)!!.second.snapshot.connection)
      assertFalse(opened.get())
    } finally { client.close() }
  }


  @Test fun queuedTrackActionsRejectReplacementTrackAfterAuthoritativeRefresh() {
    for (action in listOf<MaMusicClient.Action>(
        MaMusicClient.Action.Transport(MusicCommand.NEXT), MaMusicClient.Action.Seek(42_000),
        MaMusicClient.Action.QueueItem("two"))) {
      val fake = Fake(); val seen = Seen(); val client = client(fake, seen)
      try {
        val generation = client.config(config()); auth(fake); refresh(fake); seen.ready()
        assertTrue(client.refresh())
        val players = fake.next("players/all")
        assertTrue(client.command(action, generation, expectedTrackId = "one") { seen.results.add(it) })
        fake.reply(players, JSONArray().put(player()))
        fake.reply(fake.next("player_queues/get_active_queue"), queue(current = "two"))
        fake.reply(fake.next("player_queues/items"), JSONArray().put(item()).put(item("two")))
        assertEquals("two", seen.ready().second.snapshot.track?.id)
        assertEquals(MaMusicClient.Outcome.REJECTED, seen.results.poll(2, TimeUnit.SECONDS)!!.outcome)
        assertNull(fake.sent.poll(30, TimeUnit.MILLISECONDS))
      } finally { client.close() }
    }
  }

  @Test fun originalTrackBindingAllowsMatchingActionAndDoesNotBindRoomVolume() {
    val fake = Fake(); val seen = Seen(); val client = client(fake, seen)
    try {
      val generation = client.config(config()); auth(fake); refresh(fake); seen.ready()
      assertTrue(client.command(MaMusicClient.Action.Seek(12_000), generation, expectedTrackId = "one") { seen.results.add(it) })
      fake.reply(fake.next("player_queues/seek"), JSONObject.NULL)
      assertEquals(MaMusicClient.Outcome.ACKNOWLEDGED, seen.results.poll(2, TimeUnit.SECONDS)!!.outcome)
      refresh(fake); seen.ready()
      assertEquals(MaMusicClient.Outcome.REFRESHED, seen.results.poll(2, TimeUnit.SECONDS)!!.outcome)
      assertTrue(client.command(MaMusicClient.Action.Volume("ma:family", 42), generation, expectedTrackId = "old-track") {})
      assertEquals(42, fake.next("players/cmd/volume_set").getJSONObject("args").getInt("volume_level"))
      assertEquals("ma:family", config("family").sourceId)
      assertEquals("ma:family", config().sourceId)
    } finally { client.close() }
  }


  private fun groupedPlayers(leader: String): JSONArray = JSONArray()
      .put(player().put("synced_to", leader))
      .put(player("leader-a").put("supported_features", JSONArray(listOf("pause", "set_members")))
          .put("can_group_with", JSONArray(listOf("room")))
          .put("group_members", JSONArray(if (leader == "leader-a") listOf("leader-a", "family") else emptyList<String>())))
      .put(player("leader-b").put("supported_features", JSONArray(listOf("pause", "set_members")))
          .put("can_group_with", JSONArray(listOf("room")))
          .put("group_members", JSONArray(if (leader == "leader-b") listOf("leader-b", "family") else emptyList<String>())))
      .put(player("room"))

  private fun groupRefresh(fake: Fake, leader: String, players: JSONObject = fake.next("players/all")) {
    fake.reply(players, groupedPlayers(leader))
    fake.reply(fake.next("player_queues/get_active_queue"), queue())
    fake.reply(fake.next("player_queues/items"), JSONArray().put(item()).put(item("two")))
  }

  @Test fun queuedGroupRejectsReplacementLeaderAfterStructuralRefreshIncludingDefaultBinding() {
    for (expectedLeader in listOf(null, "ma:leader-a")) {
      val fake = Fake(); val seen = Seen(); val client = client(fake, seen)
      try {
        val generation = client.config(config()); auth(fake); groupRefresh(fake, "leader-a")
        val original = seen.ready().second.snapshot
        assertEquals("ma:leader-a", original.controlPlayerId)
        assertTrue(original.can(MusicCommand.CHANGE_ROOMS))
        fake.received.add("""{"event":"player_updated","object_id":"family"}""")
        val players = fake.next("players/all")
        val action = MaMusicClient.Action.Group(add = setOf("ma:room"), expectedLeaderId = expectedLeader)
        assertTrue(client.command(action, generation) { seen.results.add(it) })
        groupRefresh(fake, "leader-b", players)
        val replaced = seen.ready().second.snapshot
        assertEquals("ma:leader-b", replaced.controlPlayerId)
        assertTrue(replaced.can(MusicCommand.CHANGE_ROOMS))
        assertEquals(MaMusicClient.Outcome.REJECTED, seen.results.poll(2, TimeUnit.SECONDS)!!.outcome)
        assertNull("No group mutation may target the replacement leader", fake.sent.poll(30, TimeUnit.MILLISECONDS))
      } finally { client.close() }
    }
  }

  @Test fun groupLeaderBindingAcceptsMatchingCanonicalLeaderAndBoundsExplicitIds() {
    for (expectedLeader in listOf(null, "ma:leader-a")) {
      val fake = Fake(); val seen = Seen(); val client = client(fake, seen)
      try {
        val generation = client.config(config()); auth(fake); groupRefresh(fake, "leader-a"); seen.ready()
        for (invalid in listOf("", "leader-a", "ma:", "ma:a\n", "ma:" + "x".repeat(257))) {
          assertFalse(client.command(MaMusicClient.Action.Group(add = setOf("ma:room"), expectedLeaderId = invalid), generation) {})
        }
        assertTrue(client.command(MaMusicClient.Action.Group(add = setOf("ma:room"), expectedLeaderId = expectedLeader), generation) {})
        val request = fake.next("players/cmd/set_members")
        assertEquals("leader-a", request.getJSONObject("args").getString("target_player"))
        val members = request.getJSONObject("args").getJSONArray("player_ids_to_add")
        assertEquals(1, members.length())
        assertEquals("room", members.getString(0))
      } finally { client.close() }
    }
  }

  @Test fun blankPlayerPreservesNotConfiguredStatusWithoutConnecting() {
    for (blank in listOf("", " ")) {
      val fake = Fake(); val seen = Seen(); val client = client(fake, seen)
      try {
        val generation = client.config(config(blank))
        val selected = seen.selections.poll(2, TimeUnit.SECONDS)!!
        assertEquals(generation, selected.first)
        assertEquals(MusicConnection.NOT_CONFIGURED, selected.second.snapshot.connection)
        assertEquals("", selected.second.snapshot.sourceId)
        assertEquals(MusicConnection.NOT_CONFIGURED, seen.connections.poll(2, TimeUnit.SECONDS)!!.second)
        assertEquals(0, fake.connects)
        assertNull(fake.sent.poll(30, TimeUnit.MILLISECONDS))
      } finally { client.close() }
    }
  }

  @Test fun clearingReadyPlayerPreservesNotConfiguredAndRemovesOldSelection() {
    val fake = Fake(); val seen = Seen(); val client = client(fake, seen)
    try {
      client.config(config()); auth(fake); refresh(fake); seen.ready()
      seen.connections.clear()
      val generation = client.select("")
      val selected = seen.selections.poll(2, TimeUnit.SECONDS)!!
      assertEquals(generation, selected.first)
      assertEquals(MusicConnection.NOT_CONFIGURED, selected.second.snapshot.connection)
      assertEquals("", selected.second.snapshot.sourceId)
      assertNull(selected.second.snapshot.track)
      assertTrue(selected.second.snapshot.rooms.isEmpty())
      assertTrue(selected.second.snapshot.queue.isEmpty())
      assertEquals(MusicConnection.NOT_CONFIGURED, seen.connections.poll(2, TimeUnit.SECONDS)!!.second)
      assertTrue(fake.closed)
      assertFalse(client.command(MaMusicClient.Action.Transport(MusicCommand.PLAY), generation) {})
    } finally { client.close() }
  }

  @Test fun idleAndPausedSilenceProbeExpiresThenReconnectsWithoutReplayingQueuedAction() {
    for (state in listOf("idle", "paused")) {
      val first = Fake(); val second = Fake(); val seen = Seen()
      val opened = LinkedBlockingQueue<Fake>()
      val clock = AtomicLong(1000)
      var attempts = 0
      val client = MaMusicClient(
          onSelection = { g, s -> seen.selections.add(g to s) },
          onConnection = { g, s -> seen.connections.add(g to s) },
          transportFactory = { _, _ -> (if (attempts++ == 0) first else second).also { opened.add(it) } },
          limits = limits.copy(requestTimeoutMs = 10_000), elapsedMs = { clock.get() },
          unixMs = { 1_700_000_000_000 })
      try {
        val generation = client.config(config())
        assertSame(first, opened.poll(2, TimeUnit.SECONDS))
        auth(first); refresh(first, state); seen.ready()
        seen.connections.clear()
        clock.set(30_999); first.cycle()
        assertNull(first.sent.poll())
        clock.set(31_000)
        val probe = first.next("players/all")
        assertEquals(0, probe.getJSONObject("args").length())
        assertTrue(client.command(MaMusicClient.Action.Transport(MusicCommand.PLAY), generation) {
          seen.results.add(it)
        })
        // Neither another request's valid data nor an unfinished chunk extends this deadline.
        clock.set(35_000)
        first.received.add("""{"message_id":"unrelated","result":[]}""")
        first.reply(probe, JSONArray().put(player()), partial = true)
        first.cycle()
        clock.set(40_999); first.cycle()
        assertFalse(first.closed)
        assertNull(first.sent.poll())
        clock.set(41_000)
        val offline = seen.selections.poll(2, TimeUnit.SECONDS)!!
        assertEquals(MusicConnection.OFFLINE, offline.second.snapshot.connection)
        assertEquals(MusicConnection.OFFLINE, seen.connections.poll(2, TimeUnit.SECONDS)!!.second)
        assertTrue(first.closed)
        assertEquals(MaMusicClient.Outcome.FAILED, seen.results.poll(2, TimeUnit.SECONDS)!!.outcome)
        assertFalse(client.command(MaMusicClient.Action.Transport(MusicCommand.PLAY), generation) {})
        clock.set(41_020)
        assertSame(second, opened.poll(2, TimeUnit.SECONDS))
        auth(second); refresh(second, state); seen.ready(); second.cycle()
        assertNull("Reconnect must issue reads only, with no replay or auto-resume", second.sent.poll())
        assertNull(seen.results.poll())
      } finally { client.close() }
    }
  }

  @Test fun healthyQueueAndProbeReadbacksResetSilenceWithoutFetchingItemsOrRenewingMapping() {
    val fake = Fake(); val seen = Seen(); val clock = AtomicLong(1000)
    val client = MaMusicClient(
        onSelection = { g, s -> seen.selections.add(g to s) },
        onConnection = { g, s -> seen.connections.add(g to s) },
        transportFactory = { _, _ -> fake }, limits = limits.copy(requestTimeoutMs = 10_000),
        elapsedMs = { clock.get() }, unixMs = { 1_700_000_000_000 })
    try {
      val generation = client.config(config()); auth(fake); refresh(fake, "paused"); seen.ready()
      seen.connections.clear()
      clock.set(21_000)
      fake.received.add("""{"event":"queue_time_updated","object_id":"shared"}""")
      fake.reply(fake.next("player_queues/get"), queue("paused")); seen.ready()
      seen.connections.clear()
      clock.set(31_000); fake.cycle()
      assertNull("A healthy queue reply postpones the probe", fake.sent.poll())
      clock.set(50_999); fake.cycle(); assertNull(fake.sent.poll())
      clock.set(51_000)
      val probe = fake.next("players/all")
      clock.set(56_000)
      fake.reply(probe, JSONArray().put(player()), partial = true)
      fake.reply(probe, JSONArray())
      fake.cycle()
      assertNull("A liveness read must not fetch the queue or its 500-item page", fake.sent.poll())
      assertNull("A probe must not publish a queue-less selection", seen.selections.poll())
      clock.set(61_000); fake.cycle()
      assertFalse(fake.closed)
      assertNull(seen.connections.poll())
      clock.set(85_999); fake.cycle(); assertNull(fake.sent.poll())
      clock.set(86_000)
      fake.reply(fake.next("players/all"), JSONArray().put(player()))
      fake.cycle()
      assertTrue(client.command(MaMusicClient.Action.Volume("ma:family", 42), generation) {})
      // Liveness is not an authoritative queue/source refresh: the old mapping still needs one.
      refresh(fake, "paused"); seen.ready()
      assertEquals(42, fake.next("players/cmd/volume_set").getJSONObject("args").getInt("volume_level"))
    } finally { client.close() }
  }

  @Test fun invalidOrOversizedLivenessRepliesFailClosedWithoutQueueFetches() {
    for (failure in listOf("auth", "invalid", "chunks")) {
      val fake = Fake(); val seen = Seen(); val clock = AtomicLong(1000)
      val client = MaMusicClient(
          onSelection = { g, s -> seen.selections.add(g to s) },
          transportFactory = { _, _ -> fake }, limits = limits.copy(requestTimeoutMs = 10_000),
          elapsedMs = { clock.get() }, unixMs = { 1_700_000_000_000 })
      try {
        val generation = client.config(config()); auth(fake); refresh(fake, "paused"); seen.ready()
        clock.set(31_000)
        val probe = fake.next("players/all")
        when (failure) {
          "auth" -> fake.received.add(JSONObject().put("message_id", probe.getString("message_id"))
              .put("error_code", 20).toString())
          "invalid" -> fake.reply(probe, JSONObject())
          "chunks" -> {
            repeat(16) { fake.reply(probe, JSONArray(), partial = true) }
            fake.cycle()
            assertFalse(fake.closed)
            assertNull(seen.selections.poll())
            fake.reply(probe, JSONArray())
          }
        }
        val rejected = seen.selections.poll(2, TimeUnit.SECONDS)!!.second.snapshot
        assertEquals(if (failure == "auth") MusicConnection.AUTH_REQUIRED else MusicConnection.INCOMPATIBLE,
            rejected.connection)
        assertTrue(fake.closed)
        assertFalse(client.command(MaMusicClient.Action.Volume("ma:family", 42), generation) {})
        assertNull(fake.sent.poll())
      } finally { client.close() }
    }
  }

  @Test fun detachDuringLivenessProbeClosesSocketAndStopsWorkerAndReconnectAttempts() {
    val fake = Fake(); val seen = Seen(); val clock = AtomicLong(1000)
    val opened = LinkedBlockingQueue<Fake>()
    val client = MaMusicClient(
        onSelection = { g, s -> seen.selections.add(g to s) },
        onConnection = { g, s -> seen.connections.add(g to s) },
        transportFactory = { _, _ -> fake.also { opened.add(it) } },
        limits = limits.copy(requestTimeoutMs = 10_000), elapsedMs = { clock.get() },
        unixMs = { 1_700_000_000_000 })
    try {
      client.config(config()); assertSame(fake, opened.poll(2, TimeUnit.SECONDS))
      auth(fake); refresh(fake, "paused"); seen.ready()
      clock.set(31_000)
      val probe = fake.next("players/all")
      client.close()
      val selections = seen.selections.size
      val connections = seen.connections.size
      clock.set(100_000)
      fake.reply(probe, JSONArray().put(player()))
      fake.worker!!.join(1000)
      assertFalse("Detach must terminate the worker", fake.worker!!.isAlive)
      assertTrue(fake.closed)
      assertFalse(client.refresh())
      assertNull(opened.poll())
      assertNull(fake.sent.poll())
      assertEquals(selections, seen.selections.size)
      assertEquals(connections, seen.connections.size)
    } finally { client.close() }
  }

  private fun await(predicate: () -> Boolean) {
    val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(3)
    while (!predicate() && System.nanoTime() < deadline) Thread.sleep(5)
    assertTrue(predicate())
  }
}
