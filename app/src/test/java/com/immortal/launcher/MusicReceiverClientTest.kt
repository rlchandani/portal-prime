/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */
package com.immortal.launcher

import android.content.ComponentName
import android.content.Context
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import org.junit.Assert.*
import org.junit.Test
import org.mockito.Mockito

/** Exercises the production client with deterministic transport/clock ports, not an Android emulator. */
class MusicReceiverClientTest {
  @Test fun attachAndReconnectOnlyQueryAndNeverRecoverByStarting() {
    val f = Fixture()
    f.connect()
    assertEquals(listOf(1), f.transport.sent.map { it.what })
    f.reply()
    assertEquals("receiver-client", f.client.view.observation!!.clientId)
    f.transport.callbacks!!.disconnected()
    f.clock.runCurrent()
    assertNull(f.client.view.observation)
    f.clock.advance(500)
    f.transport.callbacks!!.connected()
    f.clock.runCurrent()
    assertEquals(listOf(1, 1), f.transport.sent.map { it.what })
    assertTrue(f.transport.unbinds > 0)
  }

  @Test fun startIsDeniedByDefaultUntilCallerVerifiesTargetAndCommunicationAdmission() {
    val f = Fixture()
    f.connect(); f.reply()
    f.client.execute(MusicReceiverProtocol.Action.UserStart(f.client.view.observation!!, "server")) {
      f.results += it
    }
    f.clock.runCurrent()
    assertEquals(listOf(1), f.transport.sent.map { it.what })
    assertEquals(MusicReceiverClient.Result.Failed(MusicReceiverClient.Failure.NOT_ADMITTED), f.results.single())
  }

  @Test fun admittedExplicitStartIsSentOnceAndPostCommandObservationOnlyQueries() {
    val f = Fixture()
    f.connect(); f.reply(); f.admitStart = true
    val action = MusicReceiverProtocol.Action.UserStart(f.client.view.observation!!, "server", 1704)
    f.client.execute(action) { f.results += it }
    f.clock.runCurrent()
    assertEquals(listOf(1, 3), f.transport.sent.map { it.what })
    assertNull(f.client.view.observation)
    f.reply(epoch = 4)
    assertEquals(listOf(1, 3, 1), f.transport.sent.map { it.what })
    assertTrue(f.results.single() is MusicReceiverClient.Result.Received)
    f.reply(epoch = 4)
    f.clock.advance(500)
    assertEquals(listOf(1, 3, 1, 1), f.transport.sent.map { it.what })
    assertEquals(1, f.transport.sent.count { it.what == 3 })
  }

  @Test fun admissionCallbackCannotDetachThenSendStartFromTheOldLifecycle() {
    val f = Fixture()
    f.connect(); f.reply()
    f.admission = { f.client.detach(); true }
    f.client.execute(MusicReceiverProtocol.Action.UserStart(f.client.view.observation!!, "server")) {
      f.results += it
    }
    f.clock.runCurrent()
    assertEquals(listOf(1), f.transport.sent.map { it.what })
    assertEquals(MusicReceiverClient.Connection.DETACHED, f.client.view.connection)
    assertNull(f.client.view.observation)
    assertTrue(f.results.isEmpty())
    assertEquals(0, f.clock.activeJobs)
  }

  @Test fun supersededCallbackCannotDetachThenSendItsReplacementCommand() {
    val f = Fixture()
    f.connect(); f.reply()
    f.client.execute(MusicReceiverProtocol.Action.Query) { f.client.detach() }
    f.clock.runCurrent()
    f.client.execute(MusicReceiverProtocol.Action.Suppress) { f.results += it }
    f.clock.runCurrent()
    assertEquals(listOf(1, 1), f.transport.sent.map { it.what })
    assertEquals(MusicReceiverClient.Connection.DETACHED, f.client.view.connection)
    assertNull(f.client.view.observation)
    assertTrue(f.results.isEmpty())
  }

  @Test fun observerDetachingDuringConnectCannotCreateANewBinding() {
    val clock = FakeScheduler()
    val transport = FakeTransport()
    lateinit var client: MusicReceiverClient
    client = MusicReceiverClient(transport, clock, onChanged = {
      if (it.connection == MusicReceiverClient.Connection.CONNECTING) client.detach()
    })
    client.attach(); clock.runCurrent()
    assertEquals(0, transport.binds)
    assertEquals(MusicReceiverClient.Connection.DETACHED, client.view.connection)
    assertEquals(0, clock.activeJobs)
  }

  @Test fun suppressAndUserStopRemainAvailableWithoutAnObservation() {
    val f = Fixture()
    f.connect()
    f.client.execute(MusicReceiverProtocol.Action.Suppress) { f.results += it }
    f.clock.runCurrent()
    assertEquals(listOf(1, 2), f.transport.sent.map { it.what })
    assertNull(f.client.view.observation)
    f.reply(epoch = 4)
    f.client.execute(MusicReceiverProtocol.Action.UserStop) { f.results += it }
    f.clock.runCurrent()
    assertEquals(4, f.transport.sent.last().what)
    assertEquals(setOf("requestId"), f.transport.sent.last().data.keys)
    assertEquals(0, f.transport.sent.count { it.what == 3 })
  }

  @Test fun staleStartObservationIsNotRetriedOrReboundToLatestEpoch() {
    val f = Fixture()
    f.connect(); f.reply(); f.admitStart = true
    val old = f.client.view.observation!!
    f.client.execute(MusicReceiverProtocol.Action.Suppress)
    f.clock.runCurrent(); f.reply(epoch = 4); f.reply(epoch = 4)
    f.client.execute(MusicReceiverProtocol.Action.UserStart(old, "server")) { f.results += it }
    f.clock.runCurrent()
    assertEquals(0, f.transport.sent.count { it.what == 3 })
    assertEquals(MusicReceiverClient.Result.Failed(MusicReceiverClient.Failure.UNAVAILABLE), f.results.single())
  }

  @Test fun detachClearsObservationUnbindsAndCancelsEveryClientTimer() {
    val f = Fixture()
    f.connect(); f.reply()
    val oldCallbacks = f.transport.callbacks!!
    val oldRequest = f.transport.sent.last()
    f.client.detach(); f.clock.runCurrent()
    assertEquals(MusicReceiverClient.Connection.DETACHED, f.client.view.connection)
    assertNull(f.client.view.observation)
    assertEquals(1, f.transport.unbinds)
    assertEquals(0, f.clock.activeJobs)
    val publications = f.views.size
    oldCallbacks.received(1, f.fields(oldRequest.requestId))
    oldCallbacks.connected()
    f.clock.advance(5_000)
    assertEquals(publications, f.views.size)
    assertEquals(listOf(1), f.transport.sent.map { it.what })
  }

  @Test fun cancellingQueuedStartClearsObservationWithoutSendingOrInvokingCallback() {
    val f = Fixture()
    f.connect(); f.reply(); f.admitStart = true
    val cancellation = f.client.execute(MusicReceiverProtocol.Action.UserStart(
        f.client.view.observation!!, "server")) { f.results += it }
    cancellation.cancel(); f.clock.runCurrent()
    assertEquals(0, f.transport.sent.count { it.what == 3 })
    assertNull(f.client.view.observation)
    assertTrue(f.results.isEmpty())
    assertTrue(f.transport.unbinds > 0)
  }

  @Test fun cancellationAfterSendingStartDiscardsLateReplyAndReconnectsOnlyWithQuery() {
    val f = Fixture()
    f.connect(); f.reply(); f.admitStart = true
    val oldCallbacks = f.transport.callbacks!!
    val cancellation = f.client.execute(MusicReceiverProtocol.Action.UserStart(
        f.client.view.observation!!, "server")) { f.results += it }
    f.clock.runCurrent()
    val sent = f.transport.sent.last()
    cancellation.cancel(); f.clock.runCurrent()
    oldCallbacks.received(3, f.fields(sent.requestId, epoch = 4))
    f.clock.runCurrent()
    assertNull(f.client.view.observation)
    assertTrue(f.results.isEmpty())
    f.clock.advance(500); f.transport.callbacks!!.connected(); f.clock.runCurrent()
    assertEquals(listOf(1, 3, 1), f.transport.sent.map { it.what })
  }

  @Test fun connectAndReplyTimeoutsAreBoundedAndNeverRetryStart() {
    val f = Fixture()
    f.client.attach(); f.clock.runCurrent()
    f.clock.advance(2_000)
    assertEquals(MusicReceiverClient.Failure.CONNECT_TIMEOUT, f.client.view.failure)
    assertEquals(1, f.transport.unbinds)
    f.clock.advance(500); f.transport.callbacks!!.connected(); f.clock.runCurrent(); f.reply()
    f.admitStart = true
    f.client.execute(MusicReceiverProtocol.Action.UserStart(f.client.view.observation!!, "server")) {
      f.results += it
    }
    f.clock.runCurrent(); f.clock.advance(1_000)
    assertNull(f.client.view.observation)
    assertEquals(MusicReceiverClient.Result.Failed(MusicReceiverClient.Failure.REPLY_TIMEOUT), f.results.single())
    f.clock.advance(500); f.transport.callbacks!!.connected(); f.clock.runCurrent()
    assertEquals(1, f.transport.sent.count { it.what == 3 })
    assertEquals(1, f.transport.sent.last().what)
  }

  @Test fun outOfOrderReplyDoesNotPublishAndObservationExpiresBeforeReplyTimeout() {
    val f = Fixture()
    f.connect(); f.reply()
    val old = f.transport.sent.last()
    val original = f.client.view.observation!!
    f.clock.advance(500)
    f.transport.callbacks!!.received(1, f.fields(old.requestId))
    f.clock.runCurrent()
    assertEquals(original, f.client.view.observation)
    f.clock.advance(500)
    assertNull(f.client.view.observation)
    f.reply()
    assertNotNull(f.client.view.observation)
  }

  @Test fun changedServiceInstanceAndClientIdentityInvalidateTheBinding() {
    for (field in listOf("instance", "clientId")) {
      val f = Fixture()
      f.connect(); f.reply(); f.clock.advance(500)
      val request = f.transport.sent.last()
      f.transport.callbacks!!.received(request.what, f.fields(request.requestId).plus(field to "replacement"))
      f.clock.runCurrent()
      assertNull(field, f.client.view.observation)
      assertEquals(MusicReceiverClient.Failure.INVALID_REPLY, f.client.view.failure)
      assertTrue(f.transport.unbinds > 0)
      assertEquals(0, f.transport.sent.count { it.what == 3 })
    }
  }

  @Test fun forbiddenAndUntrustedReceiverFailClosedWithoutRebindOrStart() {
    val f = Fixture()
    f.connect()
    f.transport.callbacks!!.received(1, mapOf("requestId" to 1L, "error" to "forbidden"))
    f.clock.runCurrent(); f.clock.advance(10_000)
    assertEquals(MusicReceiverClient.Connection.UNTRUSTED, f.client.view.connection)
    assertEquals(1, f.transport.binds)
    assertNull(f.client.view.observation)
    val untrusted = Fixture()
    untrusted.transport.bindResult = MusicReceiverClient.BindResult.UNTRUSTED
    untrusted.client.attach(); untrusted.clock.runCurrent(); untrusted.clock.advance(10_000)
    assertEquals(0, untrusted.transport.sent.size)
    assertEquals(1, untrusted.transport.binds)
  }

  @Test fun staleCommandErrorIsNotSuccessfulObservationAndOnlyQueriesFollow() {
    val f = Fixture()
    f.connect(); f.reply(); f.admitStart = true
    f.client.execute(MusicReceiverProtocol.Action.UserStart(f.client.view.observation!!, "server")) {
      f.results += it
    }
    f.clock.runCurrent()
    val start = f.transport.sent.last()
    f.transport.callbacks!!.received(3, f.fields(start.requestId, epoch = 4).plus("error" to "stale"))
    f.clock.runCurrent()
    assertNull(f.client.view.observation)
    assertEquals(listOf(1, 3, 1), f.transport.sent.map { it.what })
    assertEquals(1, f.transport.sent.count { it.what == 3 })
  }

  @Test fun packageTrustChecksActualReceiverUidAgainstRunningLauncherUid() {
    val f = TrustFixture()
    assertEquals(123, MusicReceiverClient.trustedReceiverUid(f.context, f.component, 777))
    Mockito.verify(f.pm).checkSignatures(777, 123)
    Mockito.`when`(f.pm.checkSignatures(777, 123)).thenReturn(PackageManager.SIGNATURE_NO_MATCH)
    assertNull(MusicReceiverClient.trustedReceiverUid(f.context, f.component, 777))
  }

  @Test fun wrongPackageServiceUidDisabledServiceAndImpersonatedLauncherAreRejected() {
    val f = TrustFixture()
    f.receiver.packageName = "de.badaix.snapcast"
    assertNull(MusicReceiverClient.trustedReceiverUid(f.context, f.component, 777))
    f.receiver.packageName = MusicReceiverProtocol.RECEIVER_PACKAGE
    f.service.name = "de.badaix.snapcast.OtherService"
    assertNull(MusicReceiverClient.trustedReceiverUid(f.context, f.component, 777))
    f.service.name = MusicReceiverProtocol.SERVICE_CLASS
    f.service.exported = false
    assertNull(MusicReceiverClient.trustedReceiverUid(f.context, f.component, 777))
    f.service.exported = true; f.service.enabled = false
    assertNull(MusicReceiverClient.trustedReceiverUid(f.context, f.component, 777))
    f.service.enabled = true; f.launcher.uid = 778
    assertNull(MusicReceiverClient.trustedReceiverUid(f.context, f.component, 777))
  }

  @Test fun serviceUidMismatchMissingPackageAndSignatureCheckFailureAreRejected() {
    val f = TrustFixture()
    f.service.applicationInfo = Mockito.mock(ApplicationInfo::class.java).apply { uid = 124 }
    assertNull(MusicReceiverClient.trustedReceiverUid(f.context, f.component, 777))
    f.service.applicationInfo = f.receiver
    Mockito.`when`(f.pm.getApplicationInfo(MusicReceiverProtocol.RECEIVER_PACKAGE, 0))
        .thenThrow(PackageManager.NameNotFoundException())
    assertNull(MusicReceiverClient.trustedReceiverUid(f.context, f.component, 777))
    val signature = TrustFixture()
    Mockito.`when`(signature.pm.checkSignatures(777, 123)).thenThrow(SecurityException("denied"))
    assertNull(MusicReceiverClient.trustedReceiverUid(signature.context, signature.component, 777))
  }

  @Test fun continuouslyDrainedFramesKeepARealFreshUserStartReceiptUsable() {
    val f = Fixture()
    f.connect(); f.reply(); f.admitStart = true
    val captured = f.client.view.observation!!
    f.clock.advance(500)
    val query = f.transport.sent.last()
    f.transport.callbacks!!.received(1, f.fields(query.requestId).plus("pcmFramesDrained" to 480L))
    f.clock.runCurrent()
    assertEquals(480L, f.client.view.observation!!.pcmFramesDrained)
    f.client.execute(MusicReceiverProtocol.Action.UserStart(captured, "server")) { f.results += it }
    f.clock.runCurrent()
    assertEquals(listOf(1, 1, 3), f.transport.sent.map { it.what })
    assertEquals(captured.epoch, f.transport.sent.last().data["epoch"])
  }

  @Test fun forgedFrameCounterReceiptCannotSendStartEvenWithCallerAdmission() {
    val f = Fixture()
    f.connect(); f.reply(); f.admitStart = true
    val captured = f.client.view.observation!!
    f.clock.advance(500)
    val query = f.transport.sent.last()
    f.transport.callbacks!!.received(1, f.fields(query.requestId).plus("pcmFramesDrained" to 480L))
    f.clock.runCurrent()
    f.client.execute(MusicReceiverProtocol.Action.UserStart(captured.copy(pcmFramesDrained = 480L), "server")) {
      f.results += it
    }
    f.clock.runCurrent()
    assertEquals(0, f.transport.sent.count { it.what == 3 })
    assertEquals(MusicReceiverClient.Result.Failed(MusicReceiverClient.Failure.UNAVAILABLE), f.results.single())
  }

  @Test fun regressingFrameCounterInvalidatesBindingAndReconnectsWithQueryOnly() {
    val f = Fixture()
    f.connect()
    val first = f.transport.sent.last()
    f.transport.callbacks!!.received(1, f.fields(first.requestId).plus("pcmFramesDrained" to 100L))
    f.clock.runCurrent(); f.clock.advance(500)
    val next = f.transport.sent.last()
    f.transport.callbacks!!.received(1, f.fields(next.requestId, epoch = 4).plus("pcmFramesDrained" to 99L))
    f.clock.runCurrent()
    assertEquals(MusicReceiverClient.Failure.INVALID_REPLY, f.client.view.failure)
    assertNull(f.client.view.observation)
    assertTrue(f.transport.unbinds > 0)
    f.clock.advance(500); f.transport.callbacks!!.connected(); f.clock.runCurrent()
    assertTrue(f.transport.sent.all { it.what == 1 })
    val rebound = f.transport.sent.last()
    f.transport.callbacks!!.received(1, f.fields(rebound.requestId).plus("pcmFramesDrained" to 100L))
    f.clock.runCurrent()
    assertNotNull(f.client.view.observation)
  }

  @Test fun connectionOnlyDecoderStateIsPassiveAndStartStillDefaultsToDenied() {
    val f = Fixture()
    f.connect()
    val query = f.transport.sent.last()
    f.transport.callbacks!!.received(1, f.fields(query.requestId).plus(mapOf(
        "outputState" to "connection_running_unverified", "processAlive" to true,
        "rendererActive" to false, "pcmFramesDrained" to 480L)))
    f.clock.runCurrent()
    val observed = f.client.view.observation!!
    assertTrue(observed.stoppedConfirmed)
    assertTrue(observed.processAlive)
    assertFalse(observed.rendererActive)
    f.client.execute(MusicReceiverProtocol.Action.UserStart(observed, "server")) { f.results += it }
    f.clock.runCurrent()
    assertEquals(MusicReceiverClient.Result.Failed(MusicReceiverClient.Failure.NOT_ADMITTED), f.results.single())
    assertEquals(listOf(1), f.transport.sent.map { it.what })
  }

  @Test fun protocolOneOrUntypedPcmMetadataFailsClosedWithoutStarting() {
    for (patch in listOf(mapOf<String, Any?>("protocol" to 1), mapOf("rendererActive" to "false"),
        mapOf("pcmFramesDrained" to 1), mapOf("pcmFramesDrained" to -1L))) {
      val f = Fixture()
      f.connect()
      val query = f.transport.sent.last()
      f.transport.callbacks!!.received(1, f.fields(query.requestId).plus(patch))
      f.clock.runCurrent()
      assertEquals(MusicReceiverClient.Failure.INVALID_REPLY, f.client.view.failure)
      assertNull(f.client.view.observation)
      assertEquals(0, f.transport.sent.count { it.what == 3 })
    }
  }

  private class TrustFixture {
    val context = Mockito.mock(Context::class.java)
    val component = Mockito.mock(ComponentName::class.java)
    val pm = Mockito.mock(PackageManager::class.java)
    val receiver = Mockito.mock(ApplicationInfo::class.java).apply {
      packageName = MusicReceiverProtocol.RECEIVER_PACKAGE; uid = 123; enabled = true
    }
    val launcher = Mockito.mock(ApplicationInfo::class.java).apply {
      packageName = "com.immortal.launcher.debug"; uid = 777; enabled = true
    }
    val service = Mockito.mock(ServiceInfo::class.java).apply {
      name = MusicReceiverProtocol.SERVICE_CLASS; packageName = MusicReceiverProtocol.RECEIVER_PACKAGE
      applicationInfo = receiver; exported = true; enabled = true
    }
    init {
      Mockito.`when`(context.packageManager).thenReturn(pm)
      Mockito.`when`(context.packageName).thenReturn(launcher.packageName)
      Mockito.`when`(component.packageName).thenReturn(MusicReceiverProtocol.RECEIVER_PACKAGE)
      Mockito.`when`(component.className).thenReturn(MusicReceiverProtocol.SERVICE_CLASS)
      Mockito.`when`(pm.getApplicationInfo(launcher.packageName, 0)).thenReturn(launcher)
      Mockito.`when`(pm.getApplicationInfo(MusicReceiverProtocol.RECEIVER_PACKAGE, 0)).thenReturn(receiver)
      Mockito.`when`(pm.getServiceInfo(component, 0)).thenReturn(service)
      Mockito.`when`(pm.checkSignatures(777, 123)).thenReturn(PackageManager.SIGNATURE_MATCH)
    }
  }

  private class Fixture {
    val clock = FakeScheduler()
    val transport = FakeTransport()
    val views = mutableListOf<MusicReceiverClient.View>()
    val results = mutableListOf<MusicReceiverClient.Result>()
    var admitStart = false
    var admission: (() -> Boolean)? = null
    val client = MusicReceiverClient(transport, clock, { views += it },
        canStart = { admission?.invoke() ?: admitStart })
    fun connect() {
      client.attach(); clock.runCurrent()
      transport.callbacks!!.connected(); clock.runCurrent()
    }
    fun reply(epoch: Long = 3) {
      val request = transport.sent.last()
      transport.callbacks!!.received(request.what, fields(request.requestId, epoch))
      clock.runCurrent()
    }
    fun fields(id: Long, epoch: Long = 3): Map<String, Any?> = mapOf(
        "protocol" to 2, "requestId" to id, "instance" to "instance-a", "epoch" to epoch,
        "observedAtElapsedMs" to clock.nowElapsedMs, "clientId" to "receiver-client",
        "outputState" to "stopped_confirmed", "reason" to "idle", "processAlive" to false,
        "focusHeld" to false, "playbackRequested" to false,
        "rendererActive" to false, "pcmFramesDrained" to 0L)
  }

  private class FakeTransport : MusicReceiverClient.Transport {
    var callbacks: MusicReceiverClient.Callbacks? = null
    var binds = 0
    var unbinds = 0
    var bindResult = MusicReceiverClient.BindResult.BINDING
    val sent = mutableListOf<MusicReceiverProtocol.Request>()
    override fun bind(callbacks: MusicReceiverClient.Callbacks): MusicReceiverClient.BindResult {
      this.callbacks = callbacks; binds++
      return bindResult
    }
    override fun send(request: MusicReceiverProtocol.Request): Boolean { sent += request; return true }
    override fun unbind() { unbinds++; callbacks = null }
  }

  private class FakeScheduler : MusicReceiverClient.Scheduler {
    override var nowElapsedMs = 10_000L
      private set
    private data class Job(val at: Long, val order: Long, val task: () -> Unit, var cancelled: Boolean = false)
    private var order = 0L
    private val jobs = mutableListOf<Job>()
    val activeJobs: Int get() = jobs.count { !it.cancelled }
    override fun post(delayMs: Long, task: () -> Unit): MusicReceiverClient.Cancellation {
      val job = Job(nowElapsedMs + delayMs, order++, task)
      jobs += job
      return MusicReceiverClient.Cancellation { job.cancelled = true }
    }
    fun runCurrent() = advance(0)
    fun advance(ms: Long) {
      val end = nowElapsedMs + ms
      var executions = 0
      while (true) {
        val next = jobs.filter { !it.cancelled && it.at <= end }.minWithOrNull(
            compareBy<Job> { it.at }.thenBy { it.order }) ?: break
        jobs.remove(next); nowElapsedMs = next.at
        check(executions++ < 1000) { "Unbounded receiver scheduling" }
        next.task()
      }
      nowElapsedMs = end
    }
  }
}
