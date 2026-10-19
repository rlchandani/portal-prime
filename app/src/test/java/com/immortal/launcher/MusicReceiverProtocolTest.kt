/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */
package com.immortal.launcher

import org.junit.Assert.*
import org.junit.Test

class MusicReceiverProtocolTest {
  private val query = MusicReceiverProtocol.Action.Query

  @Test fun exactTypedSnapshotPreservesReceiverIdentityWithoutInventingMaIdentity() {
    val reply = MusicReceiverProtocol.parse(1, state()) as MusicReceiverProtocol.Reply.State
    assertEquals("receiver-client", reply.observation.clientId)
    assertEquals("instance-a", reply.observation.instance)
    assertEquals(3L, reply.observation.epoch)
    assertTrue(reply.observation.stoppedConfirmed)
    assertNull(reply.error)
  }

  @Test fun wireTypesAreCheckedWithoutBundleOrNumberCoercion() {
    for (key in listOf("requestId", "epoch", "observedAtElapsedMs", "pcmFramesDrained")) {
      for (bad in listOf<Any>("1", 1, 1.0, true)) rejects {
        MusicReceiverProtocol.parse(1, state().plus(key to bad))
      }
    }
    for (bad in listOf<Any>("1", 1L, 1.0, true)) rejects {
      MusicReceiverProtocol.parse(1, state().plus("protocol" to bad))
    }
    for (key in listOf("processAlive", "rendererActive", "focusHeld", "playbackRequested")) {
      for (bad in listOf<Any>("false", 0, 0L)) rejects {
        MusicReceiverProtocol.parse(1, state().plus(key to bad))
      }
    }
    for (key in listOf("instance", "clientId", "outputState", "reason")) rejects {
      MusicReceiverProtocol.parse(1, state().plus(key to 7))
    }
  }

  @Test fun missingNullOversizedUnknownAndNegativeMetadataAreRejected() {
    for (key in state().keys) {
      rejects { MusicReceiverProtocol.parse(1, state().minus(key)) }
      rejects { MusicReceiverProtocol.parse(1, state().plus(key to null)) }
    }
    for (key in listOf("instance", "clientId", "outputState", "reason")) rejects {
      MusicReceiverProtocol.parse(1, state().plus(key to "x".repeat(4097)))
    }
    for (key in listOf("instance", "clientId")) rejects {
      MusicReceiverProtocol.parse(1, state().plus(key to " \t"))
    }
    for (key in listOf("requestId", "epoch", "observedAtElapsedMs", "pcmFramesDrained")) rejects {
      MusicReceiverProtocol.parse(1, state().plus(key to -1L))
    }
    rejects { MusicReceiverProtocol.parse(1, state().plus("requestId" to 0L)) }
    rejects { MusicReceiverProtocol.parse(1, state().plus("protocol" to 1)) }
    rejects { MusicReceiverProtocol.parse(1, state().plus("outputState" to "audible")) }
    rejects { MusicReceiverProtocol.parse(1, state().plus("reason" to "raw private error")) }
    rejects { MusicReceiverProtocol.parse(1, state().plus("token" to "not a wire field")) }
    rejects { MusicReceiverProtocol.parse(9, state()) }
  }

  @Test fun androidBundleBoundAcceptsCurrentStateAndStateErrorReplies() {
    for (fields in listOf(state(), state().plus("error" to "stale"),
        state().plus("error" to "invalid_endpoint"), state().plus("error" to "unsupported"))) {
      assertTrue("Real Android decoding must admit the complete current schema.",
          MusicReceiverProtocol.acceptsReplyFieldCount(fields.size))
      assertTrue(MusicReceiverProtocol.parse(1, fields) is MusicReceiverProtocol.Reply.State)
    }
    for (error in listOf("not_ready", "forbidden")) {
      val fields = mapOf("requestId" to 1L, "error" to error)
      assertTrue(MusicReceiverProtocol.acceptsReplyFieldCount(fields.size))
      assertTrue(MusicReceiverProtocol.parse(1, fields) is MusicReceiverProtocol.Reply.Error)
    }
  }

  @Test fun androidBundleBoundRejectsEmptyNegativeAndOversizedReplies() {
    for (count in listOf(-1, 0, state().size + 2, Int.MAX_VALUE)) {
      assertFalse(MusicReceiverProtocol.acceptsReplyFieldCount(count))
    }
    // Size admission never authorizes unknown keys that fit under the allocation guard.
    val unknown = state().plus("unknown" to false)
    rejects { MusicReceiverProtocol.parse(1, unknown) }
  }

  @Test fun minimalErrorsAreUnknownNotStoppedOrPermissionToStart() {
    for (error in listOf("not_ready", "forbidden")) {
      val reply = MusicReceiverProtocol.parse(1, mapOf("requestId" to 1L, "error" to error))
      assertEquals(MusicReceiverProtocol.Reply.Error(1L, error), reply)
    }
    rejects { MusicReceiverProtocol.parse(1, mapOf("requestId" to 1L, "error" to "stale")) }
    rejects { MusicReceiverProtocol.parse(1, mapOf("requestId" to 1, "error" to "not_ready")) }
  }

  @Test fun stoppedObservationRequiresAllReceiverFlagsAndDoesNotClaimAudibility() {
    for (key in listOf("rendererActive", "focusHeld", "playbackRequested")) {
      val reply = MusicReceiverProtocol.parse(1, state().plus(key to true)) as MusicReceiverProtocol.Reply.State
      assertFalse(reply.observation.stoppedConfirmed)
    }
    for (output in listOf("starting_unverified", "process_running_unverified", "termination_unconfirmed")) {
      val reply = MusicReceiverProtocol.parse(1, state().plus("outputState" to output)) as MusicReceiverProtocol.Reply.State
      assertFalse(reply.observation.stoppedConfirmed)
    }
  }

  @Test fun everyRequestHasTheExactDocumentedKeySetAndIntegerTypes() {
    val session = MusicReceiverProtocol.Session()
    session.connect()
    val first = session.issue(query, 10_000)!!
    assertEquals(1, first.what)
    assertEquals(mapOf("requestId" to 1L), first.data)
    accept(session, first, state(id = first.requestId))
    val observed = session.observation!!
    val start = session.issue(MusicReceiverProtocol.Action.UserStart(observed, "snapserver.local"), 10_001)!!
    assertEquals(3, start.what)
    assertEquals(mapOf("requestId" to 2L, "instance" to "instance-a", "epoch" to 3L,
        "host" to "snapserver.local", "port" to 1704), start.data)
    assertNull(session.observation)
    val suppress = session.issue(MusicReceiverProtocol.Action.Suppress, 10_002)!!
    assertEquals(2, suppress.what)
    assertEquals(mapOf("requestId" to 3L), suppress.data)
    val stop = session.issue(MusicReceiverProtocol.Action.UserStop, 10_003)!!
    assertEquals(4, stop.what)
    assertEquals(mapOf("requestId" to 4L), stop.data)
    assertTrue(start.data["port"] is Int)
    assertTrue(start.data["epoch"] is Long)
  }

  @Test fun startRejectsAbsentStaleOrReplacedObservationsAndCredentialLikeEndpoints() {
    val session = observedSession()
    val observed = session.observation!!
    for (host in listOf("", " ", "https://server", "user:password@server", "server/path", "a\nserver",
        "x".repeat(254))) {
      assertNull(host, session.issue(MusicReceiverProtocol.Action.UserStart(observed, host), 10_010))
    }
    for (port in listOf(0, 65536, -1)) assertNull(session.issue(
        MusicReceiverProtocol.Action.UserStart(observed, "server", port), 10_010))
    assertNull(session.issue(MusicReceiverProtocol.Action.UserStart(observed, "server"), 11_000))
    session.disconnect()
    assertNull(session.observation)
    session.connect()
    assertNull(session.issue(MusicReceiverProtocol.Action.UserStart(observed, "server"), 10_020))
    assertNotNull(session.issue(MusicReceiverProtocol.Action.Suppress, 10_020))
  }

  @Test fun supersededQueryCannotAuthorizeStartAfterSuppression() {
    val session = observedSession()
    val observed = session.observation!!
    val oldQuery = session.issue(query, 10_010)!!
    val suppress = session.issue(MusicReceiverProtocol.Action.Suppress, 10_020)!!
    assertEquals(MusicReceiverProtocol.Acceptance.Ignored, session.accept(oldQuery.generation, 1,
        state(id = oldQuery.requestId, at = 10_030), 10_030))
    assertNull(session.observation)
    assertNull(session.issue(MusicReceiverProtocol.Action.UserStart(observed, "server"), 10_030))
    accept(session, suppress, state(id = suppress.requestId, epoch = 4, at = 10_040))
    assertEquals(4L, session.observation!!.epoch)
    assertNull(session.issue(MusicReceiverProtocol.Action.UserStart(observed, "server"), 10_041))
  }

  @Test fun outOfOrderDuplicateAndWrongCommandRepliesCannotReplaceCurrentObservation() {
    val session = observedSession()
    val previous = session.observation!!
    val pending = session.issue(query, 10_010)!!
    assertEquals(MusicReceiverProtocol.Acceptance.Ignored, session.accept(pending.generation, 1,
        state(id = 1, at = 10_011), 10_011))
    assertEquals(previous, session.observation)
    assertEquals(MusicReceiverProtocol.Acceptance.Ignored, session.accept(pending.generation, 4,
        state(id = pending.requestId, at = 10_011), 10_011))
    accept(session, pending, state(id = pending.requestId, at = 10_020))
    val latest = session.observation
    assertEquals(MusicReceiverProtocol.Acceptance.Ignored, session.accept(pending.generation, 1,
        state(id = pending.requestId, at = 10_020), 10_021))
    assertEquals(latest, session.observation)
  }

  @Test fun instanceSourceEpochAndElapsedTimeRegressionsClearObservations() {
    for ((field, bad) in listOf("instance" to "old-instance", "clientId" to "other-client",
        "epoch" to 2L, "observedAtElapsedMs" to 9_999L, "observedAtElapsedMs" to 10_100L)) {
      val session = observedSession()
      val request = session.issue(query, 10_010)!!
      val result = session.accept(request.generation, 1,
          state(id = request.requestId, at = 10_020).plus(field to bad), 10_020)
      assertTrue(field, result is MusicReceiverProtocol.Acceptance.Rejected)
      assertNull(session.observation)
      assertNull(session.pending)
    }
  }

  @Test fun priorBindingReplyCannotPublishAfterDisconnectOrReconnect() {
    val session = MusicReceiverProtocol.Session()
    session.connect()
    val old = session.issue(query, 10_000)!!
    session.disconnect()
    assertNull(session.observation)
    assertEquals(MusicReceiverProtocol.Acceptance.Ignored,
        session.accept(old.generation, 1, state(), 10_000))
    session.connect()
    val next = session.issue(query, 10_010)!!
    assertTrue(next.requestId > old.requestId)
    assertEquals(MusicReceiverProtocol.Acceptance.Ignored,
        session.accept(old.generation, 1, state(), 10_010))
    accept(session, next, state(id = next.requestId, instance = "new-instance", at = 10_020))
    assertEquals("new-instance", session.observation!!.instance)
  }

  @Test fun malformedCorrelatedReplyAndTimeoutClearObservations() {
    val session = observedSession()
    val request = session.issue(query, 10_010)!!
    assertTrue(session.accept(request.generation, 1,
        state(id = request.requestId, at = 10_020).minus("focusHeld"), 10_020)
        is MusicReceiverProtocol.Acceptance.Rejected)
    assertNull(session.observation)
    val retry = session.issue(query, 10_030)!!
    assertFalse(session.timeout(11_029))
    assertTrue(session.timeout(11_030))
    assertEquals(MusicReceiverProtocol.Acceptance.Ignored, session.accept(retry.generation, 1,
        state(id = retry.requestId, at = 11_030), 11_030))
    assertNull(session.observation)
  }

  @Test fun freshnessExpiresWithoutAReplyAndErrorsNeverAuthorizeStart() {
    val session = observedSession()
    assertFalse(session.expireObservation(10_999))
    assertTrue(session.expireObservation(11_000))
    assertNull(session.observation)
    val queryRequest = session.issue(query, 11_010)!!
    accept(session, queryRequest, state(id = queryRequest.requestId, epoch = 4, at = 11_020)
        .plus("error" to "stale"))
    assertNull(session.observation)
  }


  @Test fun freshCapturedStartSurvivesAnUnchangedPollingReplyWithoutRebindingItsEpoch() {
    val session = observedSession()
    val captured = session.observation!!
    val refresh = session.issue(query, 10_500)!!
    accept(session, refresh, state(id = refresh.requestId, at = 10_510))
    val start = session.issue(MusicReceiverProtocol.Action.UserStart(captured, "server"), 10_520)
    assertNotNull("An unchanged fresh poll must not invalidate the captured user gesture", start)
    assertEquals(captured.epoch, start!!.data["epoch"])
    assertEquals(captured.instance, start.data["instance"])
  }

  @Test fun capturedStartMustBeARealFreshReceiptFromTheCurrentBindingAndUnchangedState() {
    fun refreshed(): Pair<MusicReceiverProtocol.Session, MusicReceiverProtocol.Observation> {
      val session = observedSession()
      val captured = session.observation!!
      val refresh = session.issue(query, 10_500)!!
      accept(session, refresh, state(id = refresh.requestId, at = 10_510))
      return session to captured
    }
    val (forged, captured) = refreshed()
    assertNull(forged.issue(MusicReceiverProtocol.Action.UserStart(
        captured.copy(requestId = 99), "server"), 10_520))
    val (expired, old) = refreshed()
    val fresh = expired.issue(query, 10_900)!!
    accept(expired, fresh, state(id = fresh.requestId, at = 10_910))
    assertNull(expired.issue(MusicReceiverProtocol.Action.UserStart(old, "server"), 11_000))
    val (changed, previous) = refreshed()
    val change = changed.issue(query, 10_550)!!
    accept(changed, change, state(id = change.requestId, at = 10_560).plus("reason" to "user_stopped"))
    assertNull(changed.issue(MusicReceiverProtocol.Action.UserStart(previous, "server"), 10_570))
    val (rebound, detached) = refreshed()
    rebound.disconnect(); rebound.connect()
    val newBinding = rebound.issue(query, 10_550)!!
    accept(rebound, newBinding, state(id = newBinding.requestId, at = 10_560))
    assertNull(rebound.issue(MusicReceiverProtocol.Action.UserStart(detached, "server"), 10_570))
  }


  @Test fun acceptedReceiptHistoryIsBoundedEvenIfPollingIsFasterThanProduction() {
    val session = observedSession()
    val first = session.observation!!
    var recent = first
    repeat(12) { index ->
      val at = 10_010L + index * 10
      val refresh = session.issue(query, at)!!
      accept(session, refresh, state(id = refresh.requestId, at = at + 1))
      if (index == 10) recent = session.observation!!
    }
    assertNull(session.issue(MusicReceiverProtocol.Action.UserStart(first, "server"), 10_130))
    assertNotNull(session.issue(MusicReceiverProtocol.Action.UserStart(recent, "server"), 10_130))
  }

  @Test fun connectionOnlyStateConfirmsLocalStopWithoutClaimingDecoderTermination() {
    val fields = state().plus(mapOf("outputState" to "connection_running_unverified",
        "processAlive" to true, "rendererActive" to false, "pcmFramesDrained" to Long.MAX_VALUE,
        "reason" to "pcm_output_failed"))
    val observed = (MusicReceiverProtocol.parse(1, fields) as MusicReceiverProtocol.Reply.State).observation
    assertTrue(observed.processAlive)
    assertFalse(observed.rendererActive)
    assertEquals(Long.MAX_VALUE, observed.pcmFramesDrained)
    assertTrue(observed.stoppedConfirmed)
    for (key in listOf("focusHeld", "playbackRequested")) {
      val busy = (MusicReceiverProtocol.parse(1, fields.plus(key to true))
          as MusicReceiverProtocol.Reply.State).observation
      assertFalse(busy.stoppedConfirmed)
    }
  }

  @Test fun connectionOnlyOutputRequiresALiveDecoderAndInactiveLocalRenderer() {
    val fields = state().plus(mapOf("outputState" to "connection_running_unverified", "processAlive" to true))
    rejects { MusicReceiverProtocol.parse(1, fields.plus("processAlive" to false)) }
    rejects { MusicReceiverProtocol.parse(1, fields.plus("rendererActive" to true)) }
  }

  @Test fun protocolOneIsRejectedWithEitherOldOrNewStateFields() {
    rejects { MusicReceiverProtocol.parse(1, state().plus("protocol" to 1)) }
    rejects { MusicReceiverProtocol.parse(1, state().minus("rendererActive").minus("pcmFramesDrained")
        .plus("protocol" to 1)) }
  }

  @Test fun directObservationBuildersKeepCompatibleRendererAndCounterDefaults() {
    val alive = MusicReceiverProtocol.Observation(1, "instance", 0, 10_000, "client",
        "process_running_unverified", "user_start", true, true, true)
    assertTrue(alive.rendererActive)
    assertEquals(0L, alive.pcmFramesDrained)
    assertFalse(alive.stoppedConfirmed)
    val stopped = MusicReceiverProtocol.Observation(1, "instance", 0, 10_000, "client",
        "stopped_confirmed", "idle", false, false, false)
    assertFalse(stopped.rendererActive)
    assertTrue(stopped.stoppedConfirmed)
  }

  @Test fun continuouslyDrainedFramesDoNotInvalidateARealFreshCapturedStart() {
    val session = observedSession()
    val captured = session.observation!!
    val refresh = session.issue(query, 10_500)!!
    accept(session, refresh, state(id = refresh.requestId, at = 10_510).plus("pcmFramesDrained" to 480L))
    assertEquals(480L, session.observation!!.pcmFramesDrained)
    assertNotNull(session.issue(MusicReceiverProtocol.Action.UserStart(captured, "server"), 10_520))
  }

  @Test fun counterNormalizationNeverTurnsAForgedObservationIntoAnAcceptedReceipt() {
    val session = observedSession()
    val captured = session.observation!!
    val refresh = session.issue(query, 10_500)!!
    accept(session, refresh, state(id = refresh.requestId, at = 10_510).plus("pcmFramesDrained" to 480L))
    assertNull(session.issue(MusicReceiverProtocol.Action.UserStart(
        captured.copy(pcmFramesDrained = 480L), "server"), 10_520))
    assertNull(session.issue(MusicReceiverProtocol.Action.UserStart(
        session.observation!!.copy(pcmFramesDrained = 481L), "server"), 10_520))
    assertNotNull(session.issue(MusicReceiverProtocol.Action.UserStart(captured, "server"), 10_520))
  }

  @Test fun frameCounterCannotRegressWithinAnInstanceEvenAfterAnEpochChangeOrRejection() {
    for (nextEpoch in listOf(3L, 4L)) {
      val session = observedSession()
      val advance = session.issue(query, 10_010)!!
      accept(session, advance, state(id = advance.requestId, at = 10_020).plus("pcmFramesDrained" to 100L))
      val regression = session.issue(query, 10_030)!!
      assertEquals(MusicReceiverProtocol.Acceptance.Rejected(MusicReceiverProtocol.Rejection.COUNTER_REGRESSED),
          session.accept(regression.generation, 1,
              state(id = regression.requestId, epoch = nextEpoch, at = 10_040)
                  .plus("pcmFramesDrained" to 99L), 10_040))
      assertNull(session.observation)
      val retry = session.issue(query, 10_050)!!
      assertEquals(MusicReceiverProtocol.Acceptance.Rejected(MusicReceiverProtocol.Rejection.COUNTER_REGRESSED),
          session.accept(retry.generation, 1, state(id = retry.requestId, at = 10_060), 10_060))
    }
  }

  @Test fun newBindingCanReportANewInstanceWithAResetFrameCounter() {
    val session = observedSession()
    val advance = session.issue(query, 10_010)!!
    accept(session, advance, state(id = advance.requestId, at = 10_020).plus("pcmFramesDrained" to 100L))
    session.disconnect(); session.connect()
    val rebound = session.issue(query, 10_030)!!
    accept(session, rebound, state(id = rebound.requestId, instance = "instance-b", at = 10_040))
    assertEquals(0L, session.observation!!.pcmFramesDrained)
  }

  @Test fun sameServiceInstanceCannotResetItsFrameCounterOnRebind() {
    val session = observedSession()
    val advance = session.issue(query, 10_010)!!
    accept(session, advance, state(id = advance.requestId, at = 10_020).plus("pcmFramesDrained" to 100L))
    session.disconnect(); session.connect()
    val rebound = session.issue(query, 10_030)!!
    assertEquals(MusicReceiverProtocol.Acceptance.Rejected(MusicReceiverProtocol.Rejection.COUNTER_REGRESSED),
        session.accept(rebound.generation, 1, state(id = rebound.requestId, at = 10_040), 10_040))
    assertNull(session.observation)
  }

  @Test fun counterProgressCannotHideAnActualRendererFocusPlaybackOrEpochChangeFromStart() {
    for (patch in listOf(mapOf<String, Any?>("rendererActive" to true), mapOf("focusHeld" to true),
        mapOf("playbackRequested" to true), mapOf("processAlive" to true), mapOf("epoch" to 4L),
        mapOf("outputState" to "starting_unverified"), mapOf("reason" to "user_stopped"))) {
      val session = observedSession()
      val captured = session.observation!!
      val refresh = session.issue(query, 10_500)!!
      accept(session, refresh, state(id = refresh.requestId, at = 10_510)
          .plus("pcmFramesDrained" to 480L).plus(patch))
      assertNull(session.issue(MusicReceiverProtocol.Action.UserStart(captured, "server"), 10_520))
    }
  }

  @Test fun drainProgressNeverExtendsTheCapturedReceiptsOwnFreshness() {
    val session = observedSession()
    val captured = session.observation!!
    val refresh = session.issue(query, 10_900)!!
    accept(session, refresh, state(id = refresh.requestId, at = 10_910).plus("pcmFramesDrained" to 480L))
    assertNull(session.issue(MusicReceiverProtocol.Action.UserStart(captured, "server"), 11_000))
    assertNotNull(session.issue(MusicReceiverProtocol.Action.UserStart(session.observation!!, "server"), 11_000))
  }

  private fun observedSession(): MusicReceiverProtocol.Session = MusicReceiverProtocol.Session().also {
    it.connect()
    val request = it.issue(query, 10_000)!!
    accept(it, request, state())
  }

  private fun accept(session: MusicReceiverProtocol.Session, request: MusicReceiverProtocol.Request,
      fields: Map<String, Any?>) {
    val now = fields["observedAtElapsedMs"] as Long
    assertTrue(session.accept(request.generation, request.what, fields, now)
        is MusicReceiverProtocol.Acceptance.Accepted)
  }

  private fun state(id: Long = 1, instance: String = "instance-a", epoch: Long = 3,
      at: Long = 10_000): Map<String, Any?> = mapOf(
      "protocol" to 2, "requestId" to id, "instance" to instance, "epoch" to epoch,
      "observedAtElapsedMs" to at, "clientId" to "receiver-client", "outputState" to "stopped_confirmed",
      "reason" to "idle", "processAlive" to false, "focusHeld" to false, "playbackRequested" to false,
      "rendererActive" to false, "pcmFramesDrained" to 0L)

  private fun rejects(block: () -> Unit) {
    try { block(); fail("Malformed receiver fields must be rejected") }
    catch (_: IllegalArgumentException) { }
  }
}
