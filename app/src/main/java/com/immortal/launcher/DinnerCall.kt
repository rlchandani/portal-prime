/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */
package com.immortal.launcher

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Log
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.flow.updateAndGet
import java.security.SecureRandom
import java.util.TimeZone

/** Entry point for the integrator: `DinnerCallFeature.install(this)` from [ImmortalApp.onCreate]. */
internal object DinnerCallFeature {
  fun install(context: Context) = DinnerCall.install(context)
}

/**
 * Call everyone runtime. Sends signed [DinnerWire] messages over [HouseholdBus], shows incoming
 * calls with [DinnerCardOverlay] and [DinnerSpeaker], and keeps this Portal's sent calls in
 * [book]. Bus handlers decide on the listener thread (pure prefs reads) and hand presentation to
 * the main thread. No timers run except a one-shot answer window per send.
 *
 * This replaces the old unsigned `PingService` (port 50121, no callers, never started): Ping is
 * now a [DinnerKind.PING] call, signed like every other household message.
 */
internal object DinnerCall {
  private const val TAG = "ImmortalDinner"
  const val ANSWER_WINDOW_MS = 3_000L
  private const val HELLO_MIN_INTERVAL_MS = 30_000L
  private const val PEERS_PREFS = "immortal_dinner_peers"
  private const val RECEIVED_CAPACITY = 32

  /** Injectable for tests and the quiet-hours decision. */
  @Volatile internal var clock: () -> Long = System::currentTimeMillis
  @Volatile internal var zone: () -> TimeZone = TimeZone::getDefault

  private val main = Handler(Looper.getMainLooper())
  private val random = SecureRandom()
  private val mutableBook = MutableStateFlow(DinnerBook())
  val book: StateFlow<DinnerBook> = mutableBook.asStateFlow()
  private val mutablePeers = MutableStateFlow<List<DinnerPeer>>(emptyList())
  val peers: StateFlow<List<DinnerPeer>> = mutablePeers.asStateFlow()

  /** Send id of the results dialog on screen, so a reply there doesn't also raise a toast. */
  @Volatile var visibleSendId: String? = null
  @Volatile private var installed = false
  @Volatile private var lastHelloMs = 0L
  private val received = object : LinkedHashMap<String, DinnerStatus>(RECEIVED_CAPACITY, 0.75f, false) {
    override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, DinnerStatus>?) = size > RECEIVED_CAPACITY
  }
  private val answerWindows = HashMap<String, Runnable>()

  fun install(context: Context) {
    if (installed) return
    installed = true
    val app = context.applicationContext
    mutablePeers.value = DinnerPeers.decode(app.getSharedPreferences(PEERS_PREFS, Context.MODE_PRIVATE).getString("peers", null))
    HouseholdBus.register(DinnerWire.CALL) { c, m -> DinnerWire.decodeCall(m.body)?.let { onCall(c, m.from, it) } }
    HouseholdBus.register(DinnerWire.ACK) { c, m -> DinnerWire.decodeAck(m.body)?.let { onAck(c, m.from, it) } }
    HouseholdBus.register(DinnerWire.REPLY) { c, m -> DinnerWire.decodeReply(m.body)?.let { onReply(c, m.from, it) } }
    HouseholdBus.register(DinnerWire.HELLO) { c, m -> DinnerWire.decodeHello(m.body)?.let { onHello(c, m.from, it) } }
    RemoteFeatureRoutes.register("/remote/dinner", DinnerRoute)
    hello(app, force = true)
  }

  fun selfId(context: Context): String? = runCatching { RemoteCompanionStore.id(context) }.getOrNull()
  fun selfName(context: Context): String = DinnerWire.cleanName(FleetConfig.name(context)) ?: "Portal"
  fun householdReady(context: Context): Boolean = HouseholdKey.get(context) != null

  /** Ask the other Portals to say who they are and whether they're quiet (sheet open, phone GET). */
  fun hello(context: Context, force: Boolean = false) {
    val now = clock()
    if (!force && now - lastHelloMs < HELLO_MIN_INTERVAL_MS) return
    lastHelloMs = now
    HouseholdBus.send(context, DinnerWire.HELLO, DinnerWire.encode(selfHello(context, ask = true)))
  }

  /** From this Portal's sheet: every other Portal (rows for known ones; unknown ackers add rows). */
  fun sendFromPortal(context: Context, message: DinnerMessage): DinnerSend? {
    val self = selfId(context) ?: return null
    val rows = mutablePeers.value.filter { it.id != self }.map { DinnerRow(it.id, it.name, DinnerStatus.SENDING) }
    val send = DinnerSend(newSendId(), message, selfName(context), false, clock(), rows)
    mutableBook.update { it.start(send) }
    dispatch(context, DinnerWire.Call(send.id, message, send.senderName, false, self, null), rows.map { it.roomId })
    return mutableBook.value.find(send.id)
  }

  /** From the phone, relayed: deliver here if this room was picked, signal the rest over the bus. */
  fun sendFromPhone(context: Context, message: DinnerMessage, rooms: List<String>, senderName: String): DinnerSend? {
    val self = selfId(context) ?: return null
    val names = mutablePeers.value.associate { it.id to it.name } + (self to selfName(context))
    val send = DinnerSend(newSendId(), message, senderName, true, clock(),
        rooms.map { DinnerRow(it, names[it] ?: "Portal", DinnerStatus.SENDING) })
    mutableBook.update { it.start(send) }
    val call = DinnerWire.Call(send.id, message, senderName, true, self, null)
    if (self in rooms) deliverHere(context, call, self)
    val others = rooms.filter { it != self }
    if (others.isNotEmpty()) dispatch(context, call.copy(to = others), others)
    return mutableBook.value.find(send.id)
  }

  fun retry(context: Context, sendId: String, roomId: String): DinnerSend? {
    val send = mutableBook.value.find(sendId) ?: return null
    if (send.rows.none { it.roomId == roomId && it.status.retryable }) return send
    val self = selfId(context) ?: return null
    mutableBook.update { it.retrying(sendId, roomId) }
    val call = DinnerWire.Call(sendId, send.message, send.senderName, send.senderIsPhone, self, listOf(roomId))
    if (roomId == self) deliverHere(context, call, self) else dispatch(context, call, listOf(roomId))
    return mutableBook.value.find(sendId)
  }

  /** Touch and hold a preset: hear it on this Portal only; nothing is sent. */
  fun preview(context: Context, message: DinnerMessage) {
    DinnerSpeaker.play(context, message.text, speak = message.kind != DinnerKind.PING && DinnerSpeaker.ttsAvailable(context))
  }

  private fun dispatch(context: Context, call: DinnerWire.Call, roomIds: List<String>) {
    if (!HouseholdBus.send(context, DinnerWire.CALL, DinnerWire.encode(call))) {
      mutableBook.update { it.setStatus(call.sid, roomIds, DinnerStatus.NOT_SET_UP) }
      return
    }
    main.post {
      answerWindows.remove(call.sid)?.let(main::removeCallbacks)
      val window = Runnable { answerWindows.remove(call.sid); mutableBook.update { it.timeout(call.sid) } }
      answerWindows[call.sid] = window
      main.postDelayed(window, ANSWER_WINDOW_MS)
    }
  }

  // --- receiving ---------------------------------------------------------------------------

  private fun onCall(context: Context, from: String, call: DinnerWire.Call) {
    val self = selfId(context) ?: return
    if (!call.isFor(self)) return
    if (!call.senderIsPhone) rememberPeer(context, DinnerPeer(from, call.senderName, clock(), null, null))
    val previous = synchronized(received) { received[call.sid] }
    if (previous != null) { ack(context, call, previous); return } // a retry of a call already shown
    deliverHere(context, call, self)
  }

  /** Decides on the calling thread, presents on the main thread, and answers the collector. */
  private fun deliverHere(context: Context, call: DinnerWire.Call, self: String) {
    val chime = ChimeConfig.load(context)
    val config = DinnerConfig.load(context)
    val policy = DinnerReceivePolicy(config.receive, config.speak, DinnerQuiet.isQuiet(chime, clock(), zone()),
        DinnerSpeaker.ttsAvailable(context))
    val delivery = DinnerDecision.decide(call.message.kind, policy)
    synchronized(received) { received[call.sid] = delivery.status }
    if (delivery.showCard) {
      val use24 = runCatching { ImmortalSettings.use24HourClock(context) }.getOrDefault(false)
      val card = DinnerCardModel(
          title = call.message.text, icon = call.message.icon,
          fromLine = DinnerCopy.fromLine(call.message, call.senderName, call.senderIsPhone, DinnerCopy.timeLabel(clock(), zone(), use24)),
          statusLine = DinnerCopy.cardStatus(delivery, call.message.kind, policy, DinnerCopy.minuteLabel(chime.quietEndMin, use24)),
          replyLine = DinnerCopy.replyLine(call.senderName, call.senderIsPhone), quiet = delivery.status == DinnerStatus.QUIET)
      main.post {
        ScreenControl.wake(context)
        DinnerCardOverlay.show(card) { reply -> sendReply(context, call, reply) }
        if (delivery.chime) DinnerSpeaker.play(context, call.message.text, delivery.speak, loud = true)
      }
    }
    if (call.replyTo == self) mutableBook.update { it.ack(call.sid, self, selfName(context), delivery.status) }
    else ack(context, call, delivery.status)
  }

  private fun ack(context: Context, call: DinnerWire.Call, status: DinnerStatus) {
    HouseholdBus.send(context, DinnerWire.ACK, DinnerWire.encode(DinnerWire.Ack(call.sid, call.replyTo, status, selfName(context))))
  }

  private fun sendReply(context: Context, call: DinnerWire.Call, reply: DinnerReply) {
    val self = selfId(context) ?: return
    val target = DinnerCopy.senderLabel(call.senderName, call.senderIsPhone)
    val sent = if (call.replyTo == self) { mutableBook.update { it.reply(call.sid, self, reply) }; true }
        else HouseholdBus.send(context, DinnerWire.REPLY, DinnerWire.encode(DinnerWire.Reply(call.sid, call.replyTo, reply, selfName(context))))
    DinnerToast.show(if (sent) "Sent “${reply.label}” to $target" else "Reply not sent. Call everyone isn’t set up here yet.")
  }

  // --- answers coming back to the sender --------------------------------------------------------

  private fun onAck(context: Context, from: String, ack: DinnerWire.Ack) {
    if (ack.to != selfId(context)) return
    rememberPeer(context, DinnerPeer(from, ack.roomName, clock(), quiet = ack.status == DinnerStatus.QUIET, receive = ack.status != DinnerStatus.BLOCKED))
    mutableBook.update { it.ack(ack.sid, from, ack.roomName, ack.status) }
  }

  private fun onReply(context: Context, from: String, reply: DinnerWire.Reply) {
    if (reply.to != selfId(context)) return
    val before = mutableBook.value
    mutableBook.update { it.reply(reply.sid, from, reply.reply) }
    val send = before.find(reply.sid) ?: return
    if (!send.senderIsPhone && visibleSendId != reply.sid && send.rows.any { it.roomId == from }) {
      main.post { DinnerToast.show("${reply.roomName}: ${reply.reply.label}") }
    }
  }

  private fun onHello(context: Context, from: String, hello: DinnerWire.Hello) {
    rememberPeer(context, DinnerPeer(from, hello.roomName, clock(), hello.quiet, hello.receive))
    if (hello.ask) HouseholdBus.send(context, DinnerWire.HELLO, DinnerWire.encode(selfHello(context, ask = false)))
  }

  private fun selfHello(context: Context, ask: Boolean): DinnerWire.Hello = DinnerWire.Hello(selfName(context), ask,
      DinnerQuiet.isQuiet(ChimeConfig.load(context), clock(), zone()), DinnerConfig.load(context).receive)

  private fun rememberPeer(context: Context, peer: DinnerPeer) {
    val next = mutablePeers.updateAndGet { DinnerPeers.merge(it, peer) }
    runCatching { context.getSharedPreferences(PEERS_PREFS, Context.MODE_PRIVATE).edit().putString("peers", DinnerPeers.encode(next)).apply() }
        .onFailure { Log.w(TAG, "could not save peers", it) }
  }

  private fun newSendId(): String = ByteArray(6).also(random::nextBytes).let(HouseholdKey::toHex)

}
