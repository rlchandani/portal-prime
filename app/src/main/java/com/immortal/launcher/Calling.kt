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
import java.security.SecureRandom
import java.util.TimeZone

/**
 * Hearthpane Calling runtime: feeds bus messages, taps and timers into the pure [CallMachine] and
 * performs its effects. One call at a time; reductions are serialised under [lock] and effects run
 * on the main thread in order. Media lives in [CallMediaHub], rings in [CallRinger], the room
 * directory and history in [CallingStore].
 */
internal object Calling {
  private const val TAG = "HearthpaneCalling"
  private const val TICK_MS = 500L
  private const val ASK_MIN_INTERVAL_MS = 15_000L

  @Volatile internal var clock: () -> Long = System::currentTimeMillis
  @Volatile internal var zone: () -> TimeZone = TimeZone::getDefault
  fun now(): Long = clock()

  private val main = Handler(Looper.getMainLooper())
  private val random = SecureRandom()
  private val lock = Any()
  private val mutableState = MutableStateFlow(CallState())
  val state: StateFlow<CallState> = mutableState.asStateFlow()
  @Volatile private var app: Context? = null
  @Volatile private var lastAskMs = 0L
  private var ticking = false

  fun install(context: Context) {
    if (app != null) return
    val c = context.applicationContext
    app = c
    CallingStore.load(c)
    // A phone id never speaks on the bus: phones reach Calling only through their signed session routes.
    fun portal(m: HouseholdMessage) = !CallPhoneId.isPhone(m.from)
    HouseholdBus.register(CallWire.RING) { ctx, m -> if (portal(m)) CallWire.decodeRing(m.body)?.let { onRing(ctx, m.from, it) } }
    HouseholdBus.register(CallWire.REPLY) { ctx, m -> if (portal(m)) CallWire.decodeReply(m.body)?.takeIf { it.to == selfId(ctx) }
        ?.let { dispatch(CallEvent.Replied(it.id, m.from, it.reply, it.quietEndMin, now())) } }
    HouseholdBus.register(CallWire.END) { ctx, m -> if (portal(m)) CallWire.decodeEnd(m.body)?.takeIf { it.to == selfId(ctx) }
        ?.let { dispatch(CallEvent.RemoteEnded(it.id, m.from, it.reason, now())) } }
    HouseholdBus.register(CallWire.KEEP) { ctx, m -> if (portal(m)) CallWire.decodeKeep(m.body)?.takeIf { it.to == selfId(ctx) }
        ?.let { dispatch(CallEvent.RemoteKeepGoing(it.id, m.from, it.capAtMs)) } }
    HouseholdBus.register(CallWire.MEDIA) { ctx, m -> if (portal(m)) CallWire.decodeMedia(m.body)?.takeIf { it.to == selfId(ctx) }
        ?.let { dispatch(CallEvent.RemoteMedia(it.id, m.from, it.cameraOn, it.micOn)) } }
    HouseholdBus.register(CallWire.HELLO) { ctx, m -> if (portal(m)) CallWire.decodeHello(m.body)?.let { onHello(ctx, m.from, it) } }
    HouseholdBus.register(CallWire.JOIN) { ctx, m -> if (portal(m)) CallWire.decodeJoin(m.body)
        ?.takeIf { it.to == selfId(ctx) && it.newcomer.deviceId != it.to }
        ?.let { dispatch(CallEvent.RemoteJoin(it.id, m.from, it.newcomer, now())) } }
    main.post { announce(c, ask = true) }
  }

  fun selfId(context: Context): String? = DinnerCall.selfId(context)
  fun selfName(context: Context): String = DinnerCall.selfName(context)
  fun ready(context: Context): Boolean = HouseholdKey.get(context) != null && CallMediaRuntime.available()

  // --- user actions (main thread) ----------------------------------------------------------

  /** Starts a call or drop-in to [peerId] and opens the call screen. False if one is running. */
  fun dial(context: Context, peerId: String, kind: CallKind, video: Boolean): Boolean {
    val contact = CallingStore.peers.value.firstOrNull { it.id == peerId }?.contact()
        ?: CallPhoneMailbox.phone(peerId)?.contact() ?: return false
    // Phones can be called, never dropped in on.
    if (contact.phone && kind == CallKind.DROP_IN) return false
    if (state.value.call != null || !ready(context)) return false
    CallActivity.open(context)
    dispatch(CallEvent.Dial(newId(), contact, kind, video, now()))
    return true
  }

  fun accept(audioOnly: Boolean) = dispatch(CallEvent.Accept(audioOnly, now()))
  fun decline() = dispatch(CallEvent.Decline(now()))
  fun hangUp() = dispatch(CallEvent.HangUp(now()))
  fun left() = dispatch(CallEvent.Left(now()))
  fun keepGoing() = dispatch(CallEvent.KeepGoing(now()))
  fun toggleMic() = dispatch(CallEvent.ToggleMic)
  fun toggleCamera() = dispatch(CallEvent.ToggleCamera)
  fun dismissEnded() = dispatch(CallEvent.DismissEnded)

  /** Add person: ring a room in this home into the current call (no-op when full or not allowed). */
  fun addPerson(context: Context, peerId: String): Boolean {
    val peer = CallingStore.peers.value.firstOrNull { it.id == peerId } ?: return false
    if (!CallingConfig.load(context).groupsOn) return false
    dispatch(CallEvent.AddPerson(peer.contact(), now()))
    return state.value.call?.leg(peerId) != null
  }

  /** Settings changed: tell the other rooms (drop-in column, calls on/off). */
  fun settingsChanged(context: Context) = main.post { announce(context.applicationContext, ask = false) }

  /** Calls app / Home widget on screen: ask the rooms who's around (rate-limited). */
  fun refreshPresence(context: Context) {
    val t = now()
    if (t - lastAskMs < ASK_MIN_INTERVAL_MS) return
    lastAskMs = t
    announce(context.applicationContext, ask = true)
  }

  /** The route's check: is this a verified offer for the call we're ringing? Returns the call. */
  fun acceptRemoteOffer(callId: String, from: String): ActiveCall? {
    dispatch(CallEvent.RemoteAccepted(callId, from, now()))
    val call = state.value.call?.takeIf { it.id == callId } ?: return null
    val first = call.peer.deviceId == from && call.phase == CallPhase.CONNECTING
    // A group member offers only after it answered our ring (now JOINING) or a member vouched for it.
    val leg = call.leg(from)?.let { it.phase == LegPhase.JOINING && !it.offerer } == true
    return call.takeIf { first || leg }
  }

  // --- reducer + effects ---------------------------------------------------------------------

  fun dispatch(event: CallEvent) {
    var before = 0
    val step = synchronized(lock) {
      before = mutableState.value.call?.people ?: 0
      val s = CallMachine.reduce(mutableState.value, event)
      mutableState.value = s.state
      s
    }
    if (step.effects.isNotEmpty()) main.post { step.effects.forEach(::perform) }
    // A group grew or shrank: the send profile follows (360p per peer at 3+ people).
    val people = step.state.call?.people ?: 0
    if (people != before && people > 0) main.post { CallMediaHub.groupSize(people) }
    main.post(::syncTicker)
  }

  private fun perform(effect: CallEffect) {
    val c = app ?: return
    runCatching { CallingEffects.perform(c, effect) }.onFailure { Log.w(TAG, "effect ${effect.javaClass.simpleName} failed", it) }
  }

  private fun syncTicker() {
    val active = state.value.call != null
    if (active && !ticking) { ticking = true; main.postDelayed(tick, TICK_MS) }
  }

  private val tick = object : Runnable {
    override fun run() {
      dispatch(CallEvent.Tick(now()))
      if (state.value.call != null) main.postDelayed(this, TICK_MS) else ticking = false
    }
  }

  // --- receiving ------------------------------------------------------------------------------

  private fun onRing(context: Context, from: String, ring: CallWire.Ring) {
    if (ring.to != selfId(context)) return
    CallingStore.heard(context, CallPeer(from, ring.name, now()), keepFlags = true)
    val decision = decide(context, from, ring.kind, ring.id, fromPhone = false)
    Log.i(TAG, "ring ${ring.kind} decision=${decision.javaClass.simpleName}")
    dispatch(CallEvent.Incoming(ring.id, CallContact(from, ring.name), ring.kind, ring.video, ring.host, ring.port, decision, now(),
        members = if (CallingConfig.load(context).groupsOn) ring.members else emptyList(), groupRing = ring.members.isNotEmpty()))
  }

  /** A paired phone rang this Portal (its signed `/remote/call/send`). Returns what was decided. */
  fun onPhoneRing(context: Context, phone: CallContact, id: String, video: Boolean): IncomingDecision {
    val decision = decide(context, phone.deviceId, CallKind.CALL, id, fromPhone = true)
    Log.i(TAG, "phone ring decision=${decision.javaClass.simpleName}")
    dispatch(CallEvent.Incoming(id, phone, CallKind.CALL, video, null, 0, decision, now()))
    return decision
  }

  private fun decide(context: Context, from: String, kind: CallKind, ringId: String, fromPhone: Boolean): IncomingDecision {
    val config = CallingConfig.load(context)
    val t = now()
    val quiet = DinnerQuiet.isQuiet(ChimeConfig.load(context), t, zone())
    val autoAnswer = CallAutoAnswer.eligible(CallAutoAnswerStore.rules(context), from, kind, quiet,
        CallAutoAnswer.lastDeclinedMs(CallingStore.history.value, from), t)
    return CallPolicy.decide(IncomingContext(
        kind = kind, whoCanCall = config.who, quiet = quiet,
        quietMode = config.quiet, dropInsOn = config.dropInsOn,
        dropInFromCaller = DropInGrid.policyFor(config.dropIns, from),
        busy = state.value.call?.id?.let { it != ringId } == true,
        autoAnswer = autoAnswer, fromPhone = fromPhone, phonesAllowed = config.phonesOn))
  }

  private fun onHello(context: Context, from: String, hello: CallWire.Hello) {
    CallingStore.heard(context, CallPeer(from, hello.name, now(), hello.inCall, hello.quiet, hello.acceptsCalls,
        hello.dropInsOn, hello.dropInPolicies, host = hello.host, port = hello.port))
    if (hello.ask) main.post { announce(context.applicationContext, ask = false) }
  }

  /** Broadcasts this room's presence and drop-in column (bus thread spawned by the bus). */
  fun announce(context: Context, ask: Boolean) {
    val config = CallingConfig.load(context)
    val quiet = DinnerQuiet.isQuiet(ChimeConfig.load(context), now(), zone())
    HouseholdBus.send(context, CallWire.HELLO, CallWire.encode(CallWire.Hello(selfName(context), ask, state.value.call != null,
        quiet, config.who == CallWhoCanCall.HOME, config.dropInsOn, config.dropIns,
        host = HouseholdListSync.lanAddress(), port = FleetConfig.port(context))))
  }

  private fun newId(): String = ByteArray(ID_BYTES).also(random::nextBytes).let(HouseholdKey::toHex)
  private const val ID_BYTES = 8
}
