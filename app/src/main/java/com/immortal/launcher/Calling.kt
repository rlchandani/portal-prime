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
    HouseholdBus.register(CallWire.RING) { ctx, m -> CallWire.decodeRing(m.body)?.let { onRing(ctx, m.from, it) } }
    HouseholdBus.register(CallWire.REPLY) { ctx, m -> CallWire.decodeReply(m.body)?.takeIf { it.to == selfId(ctx) }
        ?.let { dispatch(CallEvent.Replied(it.id, m.from, it.reply, it.quietEndMin, now())) } }
    HouseholdBus.register(CallWire.END) { ctx, m -> CallWire.decodeEnd(m.body)?.takeIf { it.to == selfId(ctx) }
        ?.let { dispatch(CallEvent.RemoteEnded(it.id, m.from, it.reason, now())) } }
    HouseholdBus.register(CallWire.KEEP) { ctx, m -> CallWire.decodeKeep(m.body)?.takeIf { it.to == selfId(ctx) }
        ?.let { dispatch(CallEvent.RemoteKeepGoing(it.id, m.from, it.capAtMs)) } }
    HouseholdBus.register(CallWire.MEDIA) { ctx, m -> CallWire.decodeMedia(m.body)?.takeIf { it.to == selfId(ctx) }
        ?.let { dispatch(CallEvent.RemoteMedia(it.id, m.from, it.cameraOn, it.micOn)) } }
    HouseholdBus.register(CallWire.HELLO) { ctx, m -> CallWire.decodeHello(m.body)?.let { onHello(ctx, m.from, it) } }
    main.post { announce(c, ask = true) }
  }

  fun selfId(context: Context): String? = DinnerCall.selfId(context)
  fun selfName(context: Context): String = DinnerCall.selfName(context)
  fun ready(context: Context): Boolean = HouseholdKey.get(context) != null && CallMediaRuntime.available()

  // --- user actions (main thread) ----------------------------------------------------------

  /** Starts a call or drop-in to [peerId] and opens the call screen. False if one is running. */
  fun dial(context: Context, peerId: String, kind: CallKind, video: Boolean): Boolean {
    val peer = CallingStore.peers.value.firstOrNull { it.id == peerId } ?: return false
    if (state.value.call != null || !ready(context)) return false
    CallActivity.open(context)
    dispatch(CallEvent.Dial(newId(), peer.contact(), kind, video, now()))
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
    return state.value.call?.takeIf { it.id == callId && it.peer.deviceId == from && it.phase == CallPhase.CONNECTING }
  }

  // --- reducer + effects ---------------------------------------------------------------------

  fun dispatch(event: CallEvent) {
    val step = synchronized(lock) {
      val s = CallMachine.reduce(mutableState.value, event)
      mutableState.value = s.state
      s
    }
    if (step.effects.isNotEmpty()) main.post { step.effects.forEach(::perform) }
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
    val config = CallingConfig.load(context)
    val chime = ChimeConfig.load(context)
    val decision = CallPolicy.decide(IncomingContext(
        kind = ring.kind, whoCanCall = config.who, quiet = DinnerQuiet.isQuiet(chime, now(), zone()),
        quietMode = config.quiet, dropInsOn = config.dropInsOn,
        dropInFromCaller = DropInGrid.policyFor(config.dropIns, from),
        busy = state.value.call?.id?.let { it != ring.id } == true))
    Log.i(TAG, "ring ${ring.kind} decision=${decision.javaClass.simpleName}")
    dispatch(CallEvent.Incoming(ring.id, CallContact(from, ring.name), ring.kind, ring.video, ring.host, ring.port, decision, now()))
  }

  private fun onHello(context: Context, from: String, hello: CallWire.Hello) {
    CallingStore.heard(context, CallPeer(from, hello.name, now(), hello.inCall, hello.quiet, hello.acceptsCalls,
        hello.dropInsOn, hello.dropInPolicies))
    if (hello.ask) main.post { announce(context.applicationContext, ask = false) }
  }

  /** Broadcasts this room's presence and drop-in column (bus thread spawned by the bus). */
  fun announce(context: Context, ask: Boolean) {
    val config = CallingConfig.load(context)
    val quiet = DinnerQuiet.isQuiet(ChimeConfig.load(context), now(), zone())
    HouseholdBus.send(context, CallWire.HELLO, CallWire.encode(CallWire.Hello(selfName(context), ask, state.value.call != null,
        quiet, config.who == CallWhoCanCall.HOME, config.dropInsOn, config.dropIns)))
  }

  private fun newId(): String = ByteArray(ID_BYTES).also(random::nextBytes).let(HouseholdKey::toHex)
  private const val ID_BYTES = 8
}
