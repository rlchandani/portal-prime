/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */
package com.immortal.launcher

import android.content.Context
import android.content.Intent
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import java.security.SecureRandom
import java.util.TimeZone
import java.util.concurrent.Executors
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.mapNotNull
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

/**
 * "Move to <room>": hands the playing YouTube queue to another Portal in the household.
 *
 * Transport (see [MusicHandoffWire], [MusicHandoffAuth], [MusicHandoffNet]): the source broadcasts
 * a tiny signed `music_offer` naming the target and its own LAN address; the target decides
 * (quiet hours, routine on screen, call), then pulls the queue from the source's signed
 * `/remote/musicpeer` route, adopts it, brings the music screen up (playback must be on screen),
 * and acks the outcome. Pull keeps the queue (up to 200 songs, far over one datagram) off UDP and
 * needs no address book: the dinner roster knows rooms, not IPs. The source pauses only after the
 * target acks "playing"; every other outcome leaves it playing with a reason.
 *
 * Bus handlers run on the bus thread, HTTP on its own; state is a thread-safe [MutableStateFlow].
 */
internal object MusicHandoff {
  private const val TAG = "CalderMusicMove"
  private val main by lazy { Handler(Looper.getMainLooper()) }
  private val scope by lazy { CoroutineScope(SupervisorJob() + Dispatchers.Main) }
  internal val io = Executors.newSingleThreadExecutor { r -> Thread(r, "calder-music-move").apply { isDaemon = true } }
  private val random = SecureRandom()
  private val moveFlow = MutableStateFlow<MusicMoveState>(MusicMoveState.Idle)
  private val noticeFlow = MutableStateFlow<Notice?>(null)
  @Volatile private var installed = false
  private var receiving: Job? = null

  /** A one-off message for the music screen's toast; [seq] grows so each shows once. */
  data class Notice(val text: String, val seq: Int)

  val move: StateFlow<MusicMoveState> = moveFlow.asStateFlow()
  val notice: StateFlow<Notice?> = noticeFlow.asStateFlow()

  fun install(context: Context) {
    if (installed) return
    installed = true
    HouseholdBus.register(MusicHandoffWire.OFFER) { c, m -> MusicHandoffWire.decodeOffer(m.body)?.let { onOffer(c, m.from, it) } }
    HouseholdBus.register(MusicHandoffWire.ACK) { c, m -> MusicHandoffWire.decodeAck(m.body)?.let { onAck(c, m.from, it) } }
    HouseholdBus.register(MusicHandoffWire.RECALL) { c, m -> MusicHandoffWire.decodeRecall(m.body)?.let { onRecall(c, m.from, it) } }
    // Music playing here again (a tap on Play, or a "Bring it back" arriving) ends "Playing in …".
    scope.launch {
      YouTubeMusicEngine.state.map { it.isPlaying }.distinctUntilChanged().collect { playing ->
        if (playing) moveFlow.update(MusicMoveMachine::playingHere)
      }
    }
  }

  /** The household is set up (key + identity), so "Move to…" can be offered. */
  fun available(context: Context): Boolean = HouseholdKey.get(context) != null && DinnerCall.selfId(context) != null

  fun rooms(context: Context, nowMs: Long): List<MusicMoveRoom> =
      MusicMoveRooms.from(DinnerCall.peers.value, DinnerCall.selfId(context), nowMs)

  /** Ask the other Portals to say hello now, so the room list shows who is on. */
  fun refreshRooms(context: Context) = DinnerCall.hello(context, force = true)

  fun moveTo(context: Context, room: MusicMoveRoom) {
    val app = context.applicationContext
    val id = newId()
    val step = transition { MusicMoveMachine.start(it, id, room.id, room.name, now()) }
    if ((step.state as? MusicMoveState.Offering)?.id != id) return
    sendOffer(app, id, room.id, room.name)
  }

  /** "Bring it back": asks the Portal playing our music to move it here. */
  fun bringBack(context: Context) {
    val app = context.applicationContext
    val id = newId()
    val step = transition { MusicMoveMachine.recall(it, id, now()) }
    val recalling = step.state as? MusicMoveState.Recalling ?: return
    if (recalling.id != id) return
    val sent = HouseholdBus.send(app, MusicHandoffWire.RECALL,
        MusicHandoffWire.encode(MusicHandoffWire.Recall(id, recalling.targetId, DinnerCall.selfName(app))))
    if (!sent) transition { MusicMoveStep(MusicMoveState.Handed(recalling.targetId, recalling.targetName),
        toast = "Couldn't reach ${recalling.targetName}.") }
    else main.postDelayed({ tick() }, MusicMoveMachine.ANSWER_MS)
  }

  /** Called by [MusicHandoffNet] when the target fetches: serves offer [offerId] once, to [caller] only. */
  internal fun claim(offerId: String, caller: String): Boolean {
    var allowed = false
    moveFlow.update { s -> MusicMoveMachine.claim(s, offerId, caller, now()).also { allowed = it.allowed }.state }
    return allowed
  }

  // ---- source side ----

  private fun sendOffer(app: Context, id: String, targetId: String, targetName: String) {
    io.execute {
      val host = HouseholdListSync.lanAddress()
      val offer = host?.let { MusicHandoffWire.Offer(id, targetId, it, FleetConfig.port(app), DinnerCall.selfName(app)) }
      val sent = offer != null && HouseholdBus.send(app, MusicHandoffWire.OFFER, MusicHandoffWire.encode(offer))
      if (!sent) {
        transition { s ->
          if ((s as? MusicMoveState.Offering)?.id != id) MusicMoveStep(s)
          else MusicMoveStep(MusicMoveState.Idle, toast = "Couldn't reach $targetName. The music is still playing here.")
        }
        return@execute
      }
      main.postDelayed({ tick() }, MusicMoveMachine.FETCH_MS)
      main.postDelayed({ tick() }, MusicMoveMachine.ANSWER_MS)
    }
  }

  private fun onAck(context: Context, from: String, ack: MusicHandoffWire.Ack) {
    if (ack.to != DinnerCall.selfId(context)) return
    transition { MusicMoveMachine.ack(it, from, ack) }
  }

  private fun tick() {
    transition { MusicMoveMachine.tick(it, now()) }
  }

  // ---- target side ----

  private fun onOffer(context: Context, from: String, offer: MusicHandoffWire.Offer) {
    if (offer.to != DinnerCall.selfId(context)) return
    val recall = MusicMoveMachine.isRecallOffer(moveFlow.value, from, offer.id)
    val quiet = DinnerQuiet.isQuiet(ChimeConfig.load(context), System.currentTimeMillis(), TimeZone.getDefault())
    val decline = MusicTargetPolicy.decide(quiet, RoutineScreenState.ownsScreen(), RoutineScheduler.busy(context), recall)
    if (decline != null) {
      Log.i(TAG, "declined a move from ${offer.fromName}: $decline")
      return ack(context, offer.id, from, decline)
    }
    io.execute {
      val queue = runCatching { MusicHandoffNet.fetch(context, offer) }
          .onFailure { Log.i(TAG, "queue fetch failed: ${it.javaClass.simpleName}") }.getOrNull()
      if (queue == null) ack(context, offer.id, from, MusicHandoffStatus.FAILED)
      else main.post { present(context, offer, from, queue) }
    }
  }

  /** Main thread: adopt the queue, bring the music screen up, and report once the player plays. */
  private fun present(context: Context, offer: MusicHandoffWire.Offer, from: String, queue: MusicHandoffQueue) {
    receiving?.cancel()
    val previous = YouTubeMusicEngine.state.value.queue.current?.track?.videoId
    YouTubeMusicEngine.adopt(queue.toQueue(), queue.positionMs)
    openMusicScreen(context)
    val started = SystemClock.elapsedRealtime()
    receiving = scope.launch {
      val status = withTimeoutOrNull(MusicTargetPolicy.START_MS) {
        YouTubeMusicEngine.state.mapNotNull {
          MusicTargetPolicy.progress(it, SystemClock.elapsedRealtime() - started, previous)
        }.first()
      } ?: MusicHandoffStatus.FAILED
      ack(context, offer.id, from, status)
      if (status == MusicHandoffStatus.PLAYING) notify("Playing here from ${offer.fromName}")
    }
  }

  private fun onRecall(context: Context, from: String, recall: MusicHandoffWire.Recall) {
    if (recall.to != DinnerCall.selfId(context)) return
    if (YouTubeMusicEngine.state.value.queue.current == null) return ack(context, recall.id, from, MusicHandoffStatus.NOTHING)
    val step = transition { MusicMoveMachine.start(it, recall.id, from, recall.fromName, now()) }
    if ((step.state as? MusicMoveState.Offering)?.id != recall.id) return ack(context, recall.id, from, MusicHandoffStatus.FAILED)
    sendOffer(context.applicationContext, recall.id, from, recall.fromName)
  }

  private fun ack(context: Context, id: String, to: String, status: MusicHandoffStatus) {
    HouseholdBus.send(context, MusicHandoffWire.ACK,
        MusicHandoffWire.encode(MusicHandoffWire.Ack(id, to, status, DinnerCall.selfName(context))))
  }

  /** Playback must be on screen and visible: wake, leave the screensaver and open Calder's music. */
  private fun openMusicScreen(context: Context) {
    ScreenControl.wake(context)
    runCatching {
      context.startActivity(Intent(context, HomeActivity::class.java).putExtra(APPROVED_HOME_MUSIC, true)
          .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }.onFailure { Log.w(TAG, "couldn't open the music screen", it) }
  }

  // ---- helpers ----

  /** Applies [f] atomically, then its effects (pause here, toast). */
  private fun transition(f: (MusicMoveState) -> MusicMoveStep): MusicMoveStep {
    var step = MusicMoveStep(MusicMoveState.Idle)
    moveFlow.update { s -> f(s).also { step = it }.state }
    if (step.pauseLocal) YouTubeMusicEngine.pause()
    step.toast?.let(::notify)
    return step
  }

  private fun notify(text: String) = noticeFlow.update { Notice(text, (it?.seq ?: 0) + 1) }

  private fun now(): Long = System.currentTimeMillis()

  private fun newId(): String = ByteArray(6).also(random::nextBytes).let(HouseholdKey::toHex)
}
